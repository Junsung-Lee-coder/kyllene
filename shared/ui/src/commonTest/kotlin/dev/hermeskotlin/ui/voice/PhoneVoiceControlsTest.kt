package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.custom.HeadsetAudio
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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour on the phone's real voice paths: the earphones' play/pause/stop as one toggle ([VoiceButton]) for
 * the chat's [VoiceController], and the chained chat asking for the earphones' microphone and stopping when they go.
 */
class PhoneVoiceControlsTest {
    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun clean() = VoiceButton.reset()
    @AfterTest fun cleanUp() = VoiceButton.reset()

    /** Hears you, then never finds the pause until it's told to finish. */
    private class HearingRecorder : VoiceRecorder {
        var finishes = 0
        private val finished = CompletableDeferred<Unit>()
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            onSpeech()
            finished.await()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() {
            finishes++
            finished.complete(Unit)
        }
    }

    /** Listens and hears nothing, for good. */
    private class QuietRecorder : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = awaitCancellation()
        override fun finish() = Unit
    }

    /** The phone's player: records how the chat asked for its audio, and can lose the earphones. */
    private class EarphonePlayer(private val micUnavailable: Boolean = false) : SpeechPlayer {
        val asked = mutableListOf<Boolean>()
        var lose: (() -> Unit)? = null
        var closed = 0
        /** Told on the first close, before the count goes up. */
        var onFirstClose: () -> Unit = {}
        override suspend fun play(audio: SpokenAudio) = Unit
        override fun chainedChatAudio(talkOver: Boolean, onHeadsetLost: () -> Unit): () -> Unit {
            asked += talkOver
            lose = onHeadsetLost
            return {
                if (closed == 0) onFirstClose()
                closed++
            }
        }

        override fun headsetMicUnavailable() = micUnavailable
    }

    /** The gateway: talk-over off, transcribes "hello", and never answers a reply's speech (Read aloud stays on). */
    private fun audio(talkOver: Boolean = false) = AudioApi(createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/config" -> respond("""{"voice":{"barge_in":$talkOver}}""", HttpStatusCode.OK, json)
                "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"hello"}""", HttpStatusCode.OK, json)
                "/api/audio/tts-lease" -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                else -> awaitCancellation()
            }
        },
        PersistentCookiesStorage(InMemoryKeyValueStore()),
    ))

    /** A chat that never connects: the chained chat runs its listens, and nothing is sent. */
    private fun TestScope.offlineSession(): ChatSession {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { error("no REST in this test") }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("not connecting") }, backgroundScope)
        return ChatSession(gateway, null, null, connection, SessionsApi(http), backgroundScope)
    }

    private fun down(at: Long, device: Int = 7, repeat: Int = 0) = VoiceButton.press(VoiceKey.PlayPause, true, repeat, at, device)
    private fun up(at: Long, device: Int = 7) = VoiceButton.press(VoiceKey.PlayPause, false, 0, at, device)

    @Test
    fun withNoChatOnScreenThePressGoesWhereItWould() {
        assertFalse(down(100))
        assertFalse(up(100))
    }

    @Test
    fun onePressStartsVoiceOnceAndTheNextPressIsItsOwn() = runTest {
        val voice = VoiceController(audio(), QuietRecorder(), EarphonePlayer(), backgroundScope, backgroundScope)
        var starts = 0
        VoiceButton.bind(this, voice) { starts++ }
        VoiceButton.resumed(this, true)

        assertTrue(down(100))
        // The same press told again (by the activity and the session), held (repeats) and let go: still one.
        assertTrue(down(100))
        assertTrue(down(100, repeat = 1))
        assertTrue(up(100))
        assertEquals(1, starts)
        // The next press went down later: it's its own.
        assertTrue(down(250))
        assertEquals(2, starts)
        // Every key is the same toggle.
        assertTrue(VoiceButton.press(VoiceKey.Stop, true, 0, 400, 7))
        assertTrue(VoiceButton.press(VoiceKey.Play, true, 0, 500, 7))
        assertTrue(VoiceButton.press(VoiceKey.Pause, true, 0, 600, 7))
        assertEquals(5, starts)
    }

    @Test
    fun aPressWhileTheChatHearsYouSendsWhatItHeard() = runTest {
        val recorder = HearingRecorder()
        val voice = VoiceController(audio(), recorder, EarphonePlayer(), backgroundScope, backgroundScope)
        VoiceButton.bind(this, voice) { error("voice is on already") }
        voice.startChat(offlineSession(), gateway, null)
        voice.chat.first { it.hearing }

        assertTrue(down(100))
        assertTrue(down(100))

        assertEquals(1, recorder.finishes)
        voice.stopChat()
    }

    @Test
    fun aPressWhileTheChatListensAndHasHeardNothingEndsIt() = runTest {
        val voice = VoiceController(audio(), QuietRecorder(), EarphonePlayer(), backgroundScope, backgroundScope)
        VoiceButton.bind(this, voice) { error("voice is on already") }
        voice.startChat(offlineSession(), gateway, null)
        voice.chat.first { it.phase == VoicePhase.Listening }

        assertTrue(down(100))

        val ended = voice.chat.first { it.phase == VoicePhase.Off }
        assertNull(ended.error)
    }

    @Test
    fun readAloudKeepsTheButtonWhileItReads() = runTest {
        val voice = VoiceController(audio(), QuietRecorder(), EarphonePlayer(), backgroundScope, backgroundScope)
        var starts = 0
        VoiceButton.bind(this, voice) { starts++ }
        VoiceButton.resumed(this, true)
        voice.readAloud("reply-1", "Here is the summary.", gateway, null)
        assertEquals("reply-1", voice.reading.value.key)

        assertFalse(down(100))
        assertFalse(up(100))
        assertEquals(0, starts)
        // Fork: the voice session isn't held meanwhile, so Read aloud's own session gets the button.
        assertFalse(VoiceButton.holding.first())
        voice.stopReading()
        assertTrue(VoiceButton.holding.first())
    }

    @Test
    fun aChainedChatAsksForTheEarphonesWithTalkOverOff() = runTest {
        val player = EarphonePlayer()
        val voice = VoiceController(audio(), QuietRecorder(), player, backgroundScope, backgroundScope)
        voice.startChat(offlineSession(), gateway, null)
        voice.chat.first { it.phase == VoicePhase.Listening }

        // Stock asked only with talk-over on; the gateway has it off here, and the chat still asks.
        assertEquals(listOf(false), player.asked)
        voice.stopChat()
        // The close action is safe to call again, and the chat may (End and its own end): closed at least once.
        assertTrue(player.closed >= 1)
    }

    @Test
    fun earphonesThatGoAwayEndTheChatSayingWhy() = runTest {
        val player = EarphonePlayer()
        val voice = VoiceController(audio(), QuietRecorder(), player, backgroundScope, backgroundScope)
        voice.startChat(offlineSession(), gateway, null)
        voice.chat.first { it.phase == VoicePhase.Listening }

        player.lose!!.invoke()

        // The chat ends first, then gets its reason (looked up after its audio closed).
        val ended = voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        assertTrue(ended.error.orEmpty().isNotEmpty(), "the chat says why it ended")
        // The close action is safe to call again, and the chat may (End and its own end): closed at least once.
        assertTrue(player.closed >= 1)
    }

    @Test
    fun losingTheEarphonesEndsTheChatBeforeItsReasonIsLookedUpAndANewChatThenRuns() = runTest {
        val player = EarphonePlayer()
        val voice = VoiceController(audio(), QuietRecorder(), player, backgroundScope, backgroundScope)
        voice.startChat(offlineSession(), gateway, null)
        voice.chat.first { it.phase == VoicePhase.Listening }
        var errorAtClose: String? = "unset"
        player.onFirstClose = { errorAtClose = voice.chat.value.error }

        player.lose!!.invoke()
        val ended = voice.chat.first { it.phase == VoicePhase.Off && it.error != null }

        // The chat's audio was closed before the (suspending) reason lookup set its error.
        assertNull(errorAtClose)
        assertTrue(ended.error!!.isNotEmpty())
        // A new chat starts and listens as usual, its error line clear.
        voice.startChat(offlineSession(), gateway, null)
        val again = voice.chat.first { it.phase == VoicePhase.Listening }
        assertNull(again.error)
        assertEquals(listOf(false, false), player.asked)
        voice.stopChat()
    }

    /**
     * The Android player's policy, without Android: the real [HeadsetAudio.Tracker] decides every route and loss, as
     * `AndroidSpeechPlayer` runs it. It records the routes it would take and whether it stopped what played.
     */
    private class TrackedPlayer(private var ports: HeadsetAudio.Ports) : SpeechPlayer {
        private val tracker = HeadsetAudio.Tracker()
        private val open = mutableMapOf<Any, Pair<Boolean, () -> Unit>>()
        val routes = mutableListOf<HeadsetAudio.Route>()
        var stopped = 0
        override suspend fun play(audio: SpokenAudio) = Unit
        override fun chainedChatAudio(talkOver: Boolean, onHeadsetLost: () -> Unit): () -> Unit {
            val owner = Any()
            open[owner] = talkOver to onHeadsetLost
            reroute()
            return { if (open.remove(owner) != null) reroute() }
        }

        /** The phone's outputs are now [now]: the device callback. */
        fun outputs(now: HeadsetAudio.Ports) {
            ports = now
            reroute()
        }

        private fun reroute() {
            when (val step = tracker.change(open.isNotEmpty(), open.values.any { it.first }, ports)) {
                HeadsetAudio.Step.Silence -> {
                    stopped++
                    tracker.held(HeadsetAudio.Route.Usual)
                    open.values.toList().forEach { it.second() }
                }
                is HeadsetAudio.Step.Use -> {
                    routes += step.route
                    tracker.held(step.route)
                }
            }
        }
    }

    @Test
    fun musicOnlyEarbudsGoneMidChatEndItInsteadOfMovingToTheSpeaker() = runTest {
        val earbuds = HeadsetAudio.Ports(headsetWithMic = false, privateOutput = true, earphonesWithoutUsableMic = true)
        val phoneOnly = HeadsetAudio.Ports(headsetWithMic = false, privateOutput = false, earphonesWithoutUsableMic = false)
        val player = TrackedPlayer(earbuds)
        // Talk-over on: stock would move this chat to speaker call routing once the earbuds go.
        val voice = VoiceController(audio(talkOver = true), QuietRecorder(), player, backgroundScope, backgroundScope)
        voice.startChat(offlineSession(), gateway, null)
        voice.chat.first { it.phase == VoicePhase.Listening }
        assertEquals(listOf(HeadsetAudio.Route.Usual), player.routes)

        player.outputs(phoneOnly)

        // The chat ends first, then gets its reason (looked up after its audio closed).
        val ended = voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        assertTrue(ended.error.orEmpty().isNotEmpty(), "the chat says why it ended")
        assertEquals(1, player.stopped)
        assertFalse(HeadsetAudio.Route.Speaker in player.routes, "never on the speaker: ${player.routes}")
    }

    @Test
    fun earphonesWhoseMicrophoneCantBeUsedAreSaidAndTheChatGoesOn() = runTest {
        val voice = VoiceController(audio(), QuietRecorder(), EarphonePlayer(micUnavailable = true), backgroundScope, backgroundScope)
        voice.startChat(offlineSession(), gateway, null)

        val listening = voice.chat.first { it.phase == VoicePhase.Listening && it.error != null }

        assertTrue(listening.error!!.isNotEmpty())
        assertEquals(VoicePhase.Listening, voice.chat.value.phase)
        voice.stopChat()
    }

    // A live call: the press ends it.

    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        override val incoming: Flow<String> = inbound.receiveAsFlow()
        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}"""
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit
    }

    private class FakeCall : LiveCall {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        var closed = false
        override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
            answer("v=0 offer\r\n")
        }

        override fun send(event: String) = true
        override fun speaking() = false
        override fun micLevel() = 0f
        override fun setMuted(muted: Boolean) = Unit
        override fun close() {
            closed = true
            incoming.close()
        }
    }

    /** Fork: a live call with music-only earbuds plays in them (AndroidLiveCall's route); the panel says the phone listens. */
    @Test
    fun aLiveCallSaysWhenTheEarphonesMicrophoneCantBeUsed() = runTest {
        for (micUnavailable in listOf(true, false)) {
            val client = createHttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                        "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true,"reason":null,"model":"gpt-live-1","voice":"marin"}""", HttpStatusCode.OK, json)
                        "/api/audio/voice-live/session" -> respond("""{"ok":true,"session":{"id":"live-1"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                    }
                },
                PersistentCookiesStorage(InMemoryKeyValueStore()),
            )
            val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
            connection.start(gateway)
            connection.state.first { it is ConnectionState.Connected }
            val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
            session.start()
            val call = FakeCall()
            val player = EarphonePlayer(micUnavailable = micUnavailable)
            val voice = VoiceController(AudioApi(client), QuietRecorder(), player, backgroundScope, backgroundScope) { call }
            voice.startChat(session, gateway, null, mode = VoiceMode.Live)
            val on = voice.chat.first { it.live && it.phase == VoicePhase.Listening }
            if (micUnavailable) assertTrue(on.error != null, "the panel didn't say the phone's microphone listens")
            else assertNull(on.error)
            // Still a live call, not the chained chat.
            assertTrue(player.asked.isEmpty())
            voice.stopChat()
            voice.chat.first { it.phase == VoicePhase.Off }
            assertTrue(call.closed)
        }
    }

    @Test
    fun aPressEndsALiveCall() = runTest {
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true,"reason":null,"model":"gpt-live-1","voice":"marin"}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/session" -> respond("""{"ok":true,"session":{"id":"live-1"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val call = FakeCall()
        val player = EarphonePlayer()
        val voice = VoiceController(AudioApi(client), QuietRecorder(), player, backgroundScope, backgroundScope) { call }
        VoiceButton.bind(this, voice) { error("voice is on already") }
        voice.startChat(session, gateway, null)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }

        assertTrue(down(100))

        voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(call.closed)
        // A live call isn't the chained chat: its own audio is the call's, so it doesn't ask the player.
        assertTrue(player.asked.isEmpty())
    }
}
