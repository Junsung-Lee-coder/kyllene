package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.custom.LiveTranscripts
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
import kotlin.test.assertNull

/**
 * Fork (review repair): a phone live call's last transcript pieces that couldn't be sealed are written as the call
 * ends once the sealer works again, and said in the panel when they still can't be, a hang-up too. Fixture events.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveTranscriptsEndTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Seals by reversing (never the text as is); refuses while [refusing]. */
    private class Flaky : Sealer {
        var refusing = false
        override suspend fun seal(plain: String): ByteArray {
            if (refusing) error("keystore busy")
            return ("S:" + plain.reversed()).encodeToByteArray()
        }

        override suspend fun open(sealed: ByteArray) = sealed.decodeToString().removePrefix("S:").reversed()
    }

    private class Call : LiveCall {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
            answer("v=0 offer\r\n")
        }

        override fun send(event: String) = true
        override fun speaking() = false
        override fun micLevel() = 0f
        override fun setMuted(muted: Boolean) = Unit
        override fun close() {
            incoming.close()
        }

        fun said(user: Boolean, text: String, start: Long) {
            incoming.trySend("""{"type":"session.${if (user) "input" else "output"}_transcript.delta","delta":"$text","start_ms":$start,"end_ms":${start + 400}}""")
        }
    }

    private suspend fun TestScope.call(store: LiveTranscripts, call: Call): VoiceController {
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/session" ->
                        respond("""{"ok":true,"session":{"id":"live"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                    else -> if (request.url.encodedPath.endsWith("/messages")) respond("""{"session_id":"x","messages":[]}""", HttpStatusCode.OK, json)
                    else respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> TalkFixtures.Socket() }, backgroundScope)
        connection.start(TalkFixtures.gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(TalkFixtures.gateway, "s1", null, connection, SessionsApi(client), backgroundScope).also { it.start() }
        val voice = VoiceController(AudioApi(client), TalkFixtures.Recorder(), TalkFixtures.Player(), backgroundScope, backgroundScope, liveTranscripts = store, liveCalls = LiveCalls { call })
        voice.startChat(session, TalkFixtures.gateway, null, mode = VoiceMode.Live)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        return voice
    }

    @Test
    fun theLastPiecesAreWrittenInOrderWhenTheCallEndsOnceTheSealerWorksAgain() = runTest {
        val dao = LiveTranscriptsCallTest.MemoryDao()
        val sealer = Flaky()
        val store = LiveTranscripts(dao, sealer, { "acct-A" }, { 1_000L })
        val call = Call()
        val voice = call(store, call)
        call.said(true, "Book the train", 100)
        testScheduler.runCurrent()
        sealer.refusing = true
        call.said(false, "Which ", 900)
        call.said(false, "day?", 1_300)
        testScheduler.runCurrent()
        assertEquals(1, dao.rows.size)
        // In the call, the panel says it couldn't save yet (its words are read from the catalogue, so it's awaited).
        voice.chat.first { it.error != null }

        sealer.refusing = false
        voice.stopChat()
        val ended = voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(listOf(0, 1, 2), dao.rows.map { it.seq })
        assertEquals(listOf("Book the train", "Which ", "day?"), dao.rows.map { sealer.open(it.sealed) })
        // All saved: a hang-up ends quietly.
        assertNull(ended.error)
    }

    @Test
    fun aTailThatStillCantBeSavedIsSaidWhenTheCallEndsEvenOnAHangUp() = runTest {
        val dao = LiveTranscriptsCallTest.MemoryDao()
        val sealer = Flaky()
        val store = LiveTranscripts(dao, sealer, { "acct-A" }, { 1_000L })
        val call = Call()
        val voice = call(store, call)
        call.said(true, "Kept", 100)
        testScheduler.runCurrent()
        sealer.refusing = true
        call.said(true, "secret", 500)
        testScheduler.runCurrent()

        voice.stopChat()
        val ended = voice.chat.first { it.phase == VoicePhase.Off }
        assertNotNull(ended.error, "an unsaved tail went unsaid")
        assertEquals(listOf(0), dao.rows.map { it.seq })
        assertFalse(dao.rows.any { "secret" in it.sealed.decodeToString() })
    }
}
