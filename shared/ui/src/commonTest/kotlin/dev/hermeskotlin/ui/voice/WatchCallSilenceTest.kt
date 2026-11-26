package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.custom.SpeechGate
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
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.JvmOnly
import dev.hermeskotlin.wearlink.CallEvents
import dev.hermeskotlin.wearlink.CallStart
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.wearlink.CallEnd
import kotlinx.serialization.json.int
import kotlin.test.assertEquals

/**
 * Fork (requirements §3.3): a watch call takes Settings › Watch Live silence as it is when the call starts, sent with
 * the call (the watch holds the audio and ends it); a later change waits for the next call.
 */
class WatchCallSilenceTest {
    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

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
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit
    }

    private class Outbox : WatchOutbox {
        val updates = MutableStateFlow<List<JsonObject>>(emptyList())
        override suspend fun send(node: String, path: String, data: ByteArray): Boolean {
            if (path == WearPaths.CALL_UPDATE) updates.update { it + WearJson.decode<JsonObject>(data)!! }
            return true
        }

        override suspend fun stream(node: String, path: String, data: ByteArray) = true
    }

    @Test
    @JvmOnly
    fun theWatchGetsItsSettingWithTheCallAndAChangeWaitsForTheNext() = runTest {
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
        val keys = InMemoryKeyValueStore()
        val gateways = GatewayRepository(keys).apply { save(SavedGateway(gateway.value)) }
        val tokens = NativeTokens(keys).apply { set(gateway, NativeSession("test-access", "test-refresh")) }
        val auth = AuthApi(client, PersistentCookiesStorage(keys), tokens)
        val connection = GatewayConnection(auth, { _, _ -> Socket() }, backgroundScope)
        val sessions = SessionsApi(client)
        val bots = BotsApi(connection)
        val settings = SettingsStore(keys, backgroundScope)
        settings.settings.filterNotNull().first()
        settings.update { it.copy(watchLiveSilenceSeconds = 25, phoneLiveSilenceSeconds = 40) }
        settings.settings.first { it?.watchLiveSilenceSeconds == 25 }
        val outbox = Outbox()
        val relay = WatchRelay(gateways, auth, ProfileStore(keys), connection, sessions, AudioApi(client), ChatHost(connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), settings)
        val calls = WatchCalls(relay, AudioApi(client), outbox, backgroundScope, settings = settings)

        calls.start("watch", CallStart("w1", "s1", "v=0 offer\r\n"))
        fun live() = outbox.updates.value.filter { it["phase"]?.jsonPrimitive?.content == "Live" }
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (live().isEmpty()) delay(10) } }
        // The watch's own setting, not the phone's.
        assertEquals(25, live().first()["silenceSeconds"]?.jsonPrimitive?.int)
        calls.events("watch", CallEvents("w1", 1, emptyList()))

        // Changed mid-call: this call keeps what it started with.
        settings.update { it.copy(watchLiveSilenceSeconds = 5) }
        val before = live().size
        calls.events("watch", CallEvents("w1", 2, listOf("""{"type":"session.output_transcript.delta","delta":"Hello there"}""")))
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (live().size == before) delay(10) } }
        assertTrue(live().all { it["silenceSeconds"]?.jsonPrimitive?.int == 25 })
        calls.end(CallEnd("w1"))
    }

    @Test
    fun anOutOfRangeSettingIsSentInRange() {
        assertEquals(60, dev.hermeskotlin.wearlink.liveSilenceSeconds(AppSettings(watchLiveSilenceSeconds = 600).watchLiveSilenceSeconds))
        assertEquals(10, dev.hermeskotlin.wearlink.liveSilenceSeconds(AppSettings().watchLiveSilenceSeconds))
    }
}
