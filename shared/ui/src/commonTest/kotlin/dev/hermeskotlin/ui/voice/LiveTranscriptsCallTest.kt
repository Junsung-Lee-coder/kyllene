package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.cache.PlainSealer
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.custom.LiveTranscriptDao
import dev.hermeskotlin.core.custom.LiveTranscriptPiece
import dev.hermeskotlin.core.custom.LiveTranscripts
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.JvmOnly
import dev.hermeskotlin.wearlink.CallEnd
import dev.hermeskotlin.wearlink.CallEvents
import dev.hermeskotlin.wearlink.CallStart
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork (requirements §8): live call transcripts from both entry paths, the phone's own call ([VoiceController]) and a
 * watch's ([WatchCalls] over the real [WatchRelay]), kept by account and session and given to the next call in the
 * same session through the gateway's `voice-live/session` history. The calls' events are fixtures fed by the test,
 * not a real call; the store is in memory here (its Room database is tested on the JVM).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveTranscriptsCallTest {
    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")

    /** The store's table, in memory, shared by "restarts" of [LiveTranscripts]. */
    class MemoryDao : LiveTranscriptDao {
        val rows = mutableListOf<LiveTranscriptPiece>()
        override suspend fun insert(piece: LiveTranscriptPiece): Long {
            if (rows.any { it.owner == piece.owner && it.gateway == piece.gateway && it.callId == piece.callId && it.seq == piece.seq }) return -1
            rows += piece
            return rows.size.toLong()
        }

        override suspend fun assign(owner: String, gateway: String, callId: String, sessionId: String) {
            for (i in rows.indices) {
                val p = rows[i]
                if (p.owner == owner && p.gateway == gateway && p.callId == callId && p.sessionId == "") {
                    rows[i] = LiveTranscriptPiece(p.owner, p.gateway, sessionId, p.callId, p.seq, p.device, p.speaker, p.startMs, p.endMs, p.callStartedAt, p.receivedAt, p.sealed)
                }
            }
        }

        override suspend fun session(owner: String, gateway: String, sessionId: String) =
            rows.filter { it.owner == owner && it.gateway == gateway && it.sessionId == sessionId }.sortedWith(compareBy({ it.callStartedAt }, { it.callId }, { it.seq }))

        override suspend fun call(callId: String) = rows.filter { it.callId == callId }.sortedBy { it.seq }
    }

    /** The gateway's socket: resumes any stored chat and takes prompts. */
    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        override val incoming: Flow<String> = inbound.receiveAsFlow()
        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "session.resume" -> """{"session_id":"runtime","running":false}"""
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-new","message_count":0,"messages":[],"info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}"""
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit
    }

    /** What every `voice-live/session` asked for: the history each call opened with. */
    private class Gateway {
        val histories = MutableStateFlow<List<List<Pair<String, String>>>>(emptyList())
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" -> respond("""{"ok":true,"mode":"gpt-live","available":true}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/session" -> {
                        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        val history = body["history"]?.jsonArray.orEmpty().map { m ->
                            val o = m.jsonObject
                            o["role"]!!.jsonPrimitive.content to o["content"]!!.jsonArray.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
                        }
                        histories.update { it + listOf(history) }
                        respond("""{"ok":true,"session":{"id":"live"},"transport":{"type":"webrtc","sdp":"v=0 answer\r\n"}}""", HttpStatusCode.OK, json)
                    }
                    else -> if (request.url.encodedPath.endsWith("/messages")) respond("""{"session_id":"x","messages":[]}""", HttpStatusCode.OK, json)
                    else respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )

        companion object {
            private val json = headersOf(HttpHeaders.ContentType, "application/json")
        }
    }

    /** A phone call whose events the test sends. */
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

        fun receive(event: String) {
            incoming.trySend(event)
        }
    }

    private fun said(user: Boolean, text: String, start: Long) =
        """{"type":"session.${if (user) "input" else "output"}_transcript.delta","delta":"$text","start_ms":$start,"end_ms":${start + 500}}"""

    private suspend fun TestScope.phoneSession(g: Gateway, storedId: String): ChatSession {
        val connection = GatewayConnection(AuthApi(g.client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> Socket() }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        return ChatSession(gateway, storedId, null, connection, SessionsApi(g.client), backgroundScope).also { it.start() }
    }

    /** One phone call in [storedId], saying [lines] (user first, then the voice, in turn), then hung up. */
    private suspend fun TestScope.phoneCall(g: Gateway, store: LiveTranscripts, storedId: String, vararg lines: String) {
        val call = Call()
        val voice = VoiceController(AudioApi(g.client), TalkFixtures.Recorder(), TalkFixtures.Player(), backgroundScope, backgroundScope, liveCalls = LiveCalls { call }, liveTranscripts = store)
        voice.startChat(phoneSession(g, storedId), gateway, null, mode = VoiceMode.Live)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        lines.forEachIndexed { i, line -> call.receive(said(user = i % 2 == 0, line, 1_000L * (i + 1))) }
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        voice.stopChat()
        voice.chat.first { it.phase == VoicePhase.Off }
    }

    /** A store over [dao] for the account [owner] says; [clock] moves a minute on every reading. */
    private fun store(dao: LiveTranscriptDao, owner: () -> String?, clock: MutableList<Long>) =
        LiveTranscripts(dao, PlainSealer, { owner() }, { clock[0].also { clock[0] = it + 60_000 } })

    @Test
    fun aPhoneCallIsKeptAndTheSessionsNextCallOpensWithItAfterARestartOnly() = runTest {
        val dao = MemoryDao()
        val clock = mutableListOf(1_000_000L)
        val g = Gateway()
        phoneCall(g, store(dao, { "acct-A" }, clock), "s1", "Book the train", "Which day?", "Friday")
        assertEquals(3, dao.rows.size)
        assertTrue(dao.rows.all { it.owner == "acct-A" && it.sessionId == "s1" && it.device == "phone" })

        // "Restarted": a new store over what's on disk, a new controller.
        phoneCall(g, store(dao, { "acct-A" }, clock), "s1")
        val second = g.histories.value[1]
        assertEquals(listOf("user" to "Book the train", "assistant" to "Which day?", "user" to "Friday"), second)

        // Another session of the same account, and the same session of another account, or an unverified one: nothing.
        phoneCall(g, store(dao, { "acct-A" }, clock), "s2")
        phoneCall(g, store(dao, { "acct-B" }, clock), "s1")
        phoneCall(g, store(dao, { null }, clock), "s1")
        assertEquals(listOf(emptyList<Pair<String, String>>(), emptyList(), emptyList()), g.histories.value.drop(2))
        // The first call's three pieces were given once; the empty calls kept nothing.
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun thePiecesOfACallAreKeptOnceAndInOrderAndUnreadableOnesStayAsTheyCame() = runTest {
        val dao = MemoryDao()
        val clock = mutableListOf(1_000_000L)
        val g = Gateway()
        val call = Call()
        val store = store(dao, { "acct-A" }, clock)
        val voice = VoiceController(AudioApi(g.client), TalkFixtures.Recorder(), TalkFixtures.Player(), backgroundScope, backgroundScope, liveCalls = LiveCalls { call }, liveTranscripts = store)
        voice.startChat(phoneSession(g, "s1"), gateway, null, mode = VoiceMode.Live)
        voice.chat.first { it.live && it.phase == VoicePhase.Listening }
        call.receive(said(true, "Turn it ", 100))
        // The same timed piece told twice: kept once.
        call.receive(said(true, "Turn it ", 100))
        call.receive(said(true, "up", 700))
        // A transcript event that doesn't read: kept raw, never context.
        call.receive("""{"type":"session.input_transcript.delta","delta":""")
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        voice.stopChat()
        voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(listOf("user", "user", "raw"), dao.rows.map { it.speaker })
        assertEquals(listOf(0, 1, 2), dao.rows.map { it.seq })
        assertEquals("""{"type":"session.input_transcript.delta","delta":""", dao.rows.last().sealed.decodeToString())

        val earlier = store.earlier(gateway, "s1")
        assertEquals(listOf(listOf(dev.hermeskotlin.core.voice.LiveFragment.Speaker.User to "Turn it up")), earlier.calls.map { it.turns })
        assertEquals(0, earlier.unreadable)
    }

    /**
     * The watch's call, as the watch sends it to the phone, is kept with the phone's and opens the phone's next call.
     * JVM only: the watch hanging up reaches the stock "call dropped" line, read from the string catalogue.
     */
    @Test
    @JvmOnly
    fun aWatchCallIsKeptAndThePhonesNextCallInTheSessionOpensWithIt() = runTest {
        val dao = MemoryDao()
        val clock = mutableListOf(1_000_000L)
        val g = Gateway()
        val keys = InMemoryKeyValueStore()
        val gateways = GatewayRepository(keys).apply { save(SavedGateway(gateway.value)) }
        val tokens = NativeTokens(keys).apply { set(gateway, NativeSession("test-access", "test-refresh")) }
        val cookies = PersistentCookiesStorage(keys)
        val auth = AuthApi(g.client, cookies, tokens)
        val connection = GatewayConnection(auth, { _, _ -> Socket() }, backgroundScope)
        val sessions = SessionsApi(g.client)
        val bots = BotsApi(connection)
        val settings = SettingsStore(keys, backgroundScope)
        settings.settings.filterNotNull().first()
        val outbox = object : WatchOutbox {
            override suspend fun send(node: String, path: String, data: ByteArray) = true
            override suspend fun stream(node: String, path: String, data: ByteArray) = true
        }
        val relay = WatchRelay(gateways, auth, ProfileStore(keys), connection, sessions, AudioApi(g.client), ChatHost(connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), settings)
        val store = store(dao, { "acct-A" }, clock)
        val calls = WatchCalls(relay, AudioApi(g.client), outbox, backgroundScope, transcripts = store)

        calls.start("watch", CallStart("w1", "s1", "v=0 offer\r\n"))
        // The watch runs on its own threads: wait in real time for the call to open.
        withContext(Dispatchers.Default) { withTimeout(10_000) { g.histories.first { it.isNotEmpty() } } }
        calls.events("watch", CallEvents("w1", 1, listOf(said(true, "Remind me at six", 100), said(false, "Done.", 900))))
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (dao.rows.size < 2) delay(10) } }
        calls.end(CallEnd("w1"))
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (relay.busy.value) delay(10) } }

        assertTrue(dao.rows.all { it.device == "watch" && it.sessionId == "s1" && it.owner == "acct-A" })
        // The phone's next call in the same chat opens with what was said on the watch.
        phoneCall(g, store(dao, { "acct-A" }, clock), "s1")
        val opened = g.histories.value.last()
        assertEquals(listOf("user" to "Remind me at six", "assistant" to "Done."), opened)
    }
}
