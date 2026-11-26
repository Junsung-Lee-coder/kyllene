package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.ui.JvmOnly
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §3.3): a phone live call ends after the Phone Live silence setting, through the real
 * [VoiceController] and [LiveConversation] on a fake WebRTC call whose sound the test sets, on the test's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSilenceCallTest {
    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val submits = MutableStateFlow<List<JsonObject>>(emptyList())
        override val incoming: Flow<String> = inbound.receiveAsFlow()
        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["method"] == null && message["result"] != null) return
            val id = message["id"] ?: return
            val result = when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}""".also { submits.update { it + message } }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit
        fun push(raw: String) { inbound.trySend(raw) }
        fun event(type: String, payload: String = "{}") {
            inbound.trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt9","payload":$payload}}""")
        }
    }

    /** The call's sound, as the test sets it: the voice playing, the microphone's level, the media link. */
    private class SoundCall : LiveCall {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        val sent = MutableStateFlow<List<String>>(emptyList())
        var playing = false
        var mic = 0f
        var link = true
        var closed = false
        override suspend fun connect(answer: suspend (offerSdp: String) -> String) { answer("v=0 offer\r\n") }
        override fun send(event: String): Boolean { sent.update { it + event }; return true }
        override fun speaking() = playing
        override fun micLevel() = mic
        override fun linked() = link
        override fun setMuted(muted: Boolean) = Unit
        override fun close() { closed = true; incoming.close() }
        fun receive(event: String) { incoming.trySend(event) }
    }

    private object Silent : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private class Waiting : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            awaitCancellation()
        }
        override fun finish() = Unit
    }

    private data class Call(val voice: VoiceController, val call: SoundCall, val socket: Socket, val session: ChatSession, val created: () -> Int)

    private suspend fun TestScope.call(seconds: Int = 10, mode: VoiceMode = VoiceMode.Live, recorder: VoiceRecorder = Waiting()): Call {
        val client = createHttpClient(
            MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                        "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true}""", HttpStatusCode.OK, json)
                        "/api/audio/voice-live/session" ->
                            respond("""{"ok":true,"session":{"id":"live-1"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                    }
                }
            }),
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = SoundCall()
        var created = 0
        val voice = VoiceController(AudioApi(client), recorder, Silent, backgroundScope, backgroundScope, liveClock = { testScheduler.currentTime }) {
            created++
            call
        }
        voice.startChat(session, gateway, null, mode = mode, liveSilenceSeconds = seconds)
        if (mode != VoiceMode.Manual) voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        return Call(voice, call, socket, session, { created })
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Call.ready() = call.receive("""{"type":"session.started","session":{"id":"live-1"}}""")
    private val Call.live get() = voice.chat.value.live && voice.chat.value.phase != VoicePhase.Off
    private fun Call.endedOnSilence(seconds: Int) {
        assertFalse(live, "the call is still on")
        assertEquals("The live call ended after $seconds seconds of silence.", voice.chat.value.error)
        assertTrue(call.closed)
        // One clean hang-up: the close goes to the voice once.
        assertEquals(1, call.sent.value.count { "session.close" in it })
    }

    @Test
    @JvmOnly
    fun silenceCountsOnlyOnceTheSessionIsReadyAndEndsTheCallOnceAtTheSetting() = runTest {
        val c = call(seconds = 10)
        // Dialling: a minute of nothing ends nothing.
        wait(60_000)
        assertTrue(c.live)
        c.ready()
        runCurrent()
        wait(9_800)
        assertTrue(c.live)
        wait(400)
        c.endedOnSilence(10)
        // Never starts again on its own.
        wait(60_000)
        assertEquals(1, c.created())
        assertEquals(VoicePhase.Off, c.voice.chat.value.phase)
    }

    @Test
    @JvmOnly
    fun speechAndTheVoicePlayingKeepItGoingPastFiveMinutes() = runTest {
        val c = call(seconds = 10)
        c.ready()
        runCurrent()
        repeat(40) {
            wait(8_000)
            c.call.mic = 0.4f
            wait(300)
            c.call.mic = 0f
            wait(8_000)
            c.call.playing = true
            wait(500)
            c.call.playing = false
        }
        assertTrue(c.live)
        assertTrue(testScheduler.currentTime > 5 * 60_000L)
        // Words in the transcript count too, but their absence alone never ends a call early.
        wait(8_000)
        c.call.receive("""{"type":"session.input_transcript.delta","delta":"and one more thing","start_ms":1,"end_ms":2}""")
        runCurrent()
        wait(8_000)
        assertTrue(c.live)
        wait(2_300)
        c.endedOnSilence(10)
    }

    @Test
    @JvmOnly
    fun hermesWorkingAndALinkThatDroppedHoldIt() = runTest {
        val c = call(seconds = 10)
        c.ready()
        c.call.receive("""{"type":"session.input_transcript.delta","delta":"Clean the build","start_ms":0,"end_ms":900}""")
        c.call.receive("""{"type":"session.delegation.created","delegation":{"id":"d1"}}""")
        c.socket.submits.first { it.size == 1 }
        c.socket.event("message.start")
        c.session.state.first { it.running }
        // Hermes works for two minutes: not silence.
        wait(120_000)
        assertTrue(c.live)
        c.socket.event("message.complete", """{"text":"Done.","status":"complete"}""")
        c.session.state.first { !it.running }
        wait(2_000)
        assertTrue(c.live)
        // The media link drops for a minute (WebRTC disconnected): not silence either.
        c.call.link = false
        wait(60_000)
        assertTrue(c.live)
        c.call.link = true
        wait(9_000)
        assertTrue(c.live)
        wait(1_300)
        c.endedOnSilence(10)
    }

    @Test
    @JvmOnly
    fun mutedItsSilenceThoughTheRoomIsLoudAndMuteIsntSpeech() = runTest {
        val c = call(seconds = 10)
        c.ready()
        runCurrent()
        wait(5_000)
        c.voice.toggleMute()
        c.call.mic = 0.6f
        wait(4_000)
        assertTrue(c.live)
        wait(1_300)
        c.endedOnSilence(10)
    }

    @Test
    @JvmOnly
    fun aQuestionWaitingForTheAnswerIsSilenceThoughTheTurnIsOpen() = runTest {
        val c = call(seconds = 10)
        c.ready()
        c.call.receive("""{"type":"session.input_transcript.delta","delta":"Clean the build","start_ms":0,"end_ms":900}""")
        c.call.receive("""{"type":"session.delegation.created","delegation":{"id":"d1"}}""")
        c.socket.submits.first { it.size == 1 }
        c.socket.event("message.start")
        c.session.state.first { it.running }
        c.socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt9","command":"rm -rf build","description":"Delete the build folder","choices":["once","session","deny"]}}""")
        c.session.state.first { it.inputRequests.isNotEmpty() }
        c.voice.chat.first { it.waitingOnYou }
        wait(9_000)
        assertTrue(c.live)
        wait(1_300)
        c.endedOnSilence(10)
    }

    @Test
    @JvmOnly
    fun theSettingIsReadWhenTheCallStarts() = runTest {
        val c = call(seconds = 3)
        c.ready()
        runCurrent()
        wait(2_800)
        assertTrue(c.live)
        wait(400)
        assertEquals("The live call ended after 3 seconds of silence.", c.voice.chat.value.error)
    }

    @Test
    fun talkNeverEndsOnSilence() = runTest {
        val recorder = Waiting()
        val c = call(seconds = 1, mode = VoiceMode.Manual, recorder = recorder)
        c.voice.chat.first { it.manual && it.phase != VoicePhase.Connecting }
        wait(120_000)
        assertTrue(c.voice.chat.value.manual)
        assertTrue(c.voice.chat.value.phase != VoicePhase.Off)
        assertEquals(0, c.created(), "Talk made no live call")
        c.voice.stopChat()
    }
}
