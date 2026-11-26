package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.custom.SpeechGate
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.LiveCalls
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fork (requirements §6.1, review repair): text sent where it reaches a phone live call hangs it up, on the real
 * [VoiceController] and [LiveConversation] with a fake call; and a request made while its account was still pending
 * is silenced by text once the account is known. Fixture events only. These use only what the published version
 * already had, so they show its behaviour before the repair too.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechPolicyRepairTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Call : LiveCall {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        var closes = 0
        val sent = mutableListOf<String>()
        override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
            answer("v=0 offer\r\n")
        }

        override fun send(event: String): Boolean {
            sent += event
            return true
        }

        override fun speaking() = false
        override fun micLevel() = 0f
        override fun setMuted(muted: Boolean) = Unit
        override fun close() {
            closes++
            incoming.close()
        }
    }

    private suspend fun TestScope.live(gate: SpeechGate, calls: MutableList<Call>): VoiceController {
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/session" ->
                        respond("""{"ok":true,"session":{"id":"live"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> TalkFixtures.Socket() }, backgroundScope)
        connection.start(TalkFixtures.gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(TalkFixtures.gateway, "s1", null, connection, SessionsApi(client), backgroundScope).also { it.start() }
        val voice = VoiceController(
            AudioApi(client), TalkFixtures.Recorder(), TalkFixtures.Player(), backgroundScope, backgroundScope,
            speechGate = gate,
            liveCalls = LiveCalls { Call().also { calls += it } },
        )
        voice.startChat(session, TalkFixtures.gateway, null, mode = VoiceMode.Live)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        return voice
    }

    @Test
    fun textWhereItReachesALiveCallHangsItUpSayingWhyAndOnlyANewStartCallsAgain() = runTest {
        val gate = SpeechGate { "acct-A" }
        val calls = mutableListOf<Call>()
        val voice = live(gate, calls)
        gate.textInput(TalkFixtures.gateway)
        val ended = kotlinx.coroutines.withTimeoutOrNull(10_000) { voice.chat.first { it.phase == VoicePhase.Off } }
        assertNotNull(ended, "text didn't end the live call")
        testScheduler.runCurrent()
        // Hung up as Hang up does: the voice told to close, the call closed once, and why it ended said.
        assertEquals(1, calls.single().closes)
        assertEquals(1, calls.single().sent.count { "session.close" in it })
        assertNotNull(ended.error, "the call ended without saying why")
        // Nothing starts again on its own.
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(1, calls.size)
        assertEquals(VoicePhase.Off, voice.chat.value.phase)
    }

    @Test
    fun anotherAccountsOrGatewaysTextLeavesTheLiveCallOn() = runTest {
        var owner = "acct-A"
        val gate = SpeechGate { owner }
        val calls = mutableListOf<Call>()
        val voice = live(gate, calls)
        owner = "acct-B"
        gate.textInput(TalkFixtures.gateway)
        owner = "acct-A"
        gate.textInput(GatewayUrl.parse("https://other.example.ts.net"))
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Listening, voice.chat.value.phase)
        assertEquals(0, calls.single().closes)
        voice.stopChat()
    }

    @Test
    fun aRequestMadeWhileTheAccountWasPendingIsSilencedOnceItsKnown() = runTest {
        var owner: String? = null
        val gate = SpeechGate { owner }
        val home = TalkFixtures.gateway
        val pending = gate.voiceInput(home)
        owner = "acct-A"
        gate.textInput(home)
        assertFalse(gate.allows(pending), "text after the account was verified didn't reach a request made while it was pending")

        // And the other way: a request under a known account, text while the gateway's account is pending.
        val known = gate.voiceInput(home)
        owner = null
        gate.textInput(home)
        owner = "acct-A"
        assertFalse(gate.allows(known))
        // A later request is read.
        assertTrue(gate.allows(gate.voiceInput(home)))
    }
}
