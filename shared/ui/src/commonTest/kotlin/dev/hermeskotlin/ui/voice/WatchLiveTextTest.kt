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

/**
 * Fork (requirements §6.1, review repair): text sent on the phone where it reaches a live call the watch holds hangs
 * it up through the real [WatchCalls] over the real [WatchRelay], and the watch is told why. Fixture events; JVM only,
 * as the reason is read from the string catalogue.
 */
class WatchLiveTextTest {
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
    fun phoneTextHangsUpTheWatchsLiveCallAndTellsTheWatchWhy() = runTest {
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
        val outbox = Outbox()
        val gate = SpeechGate { "acct-A" }
        val relay = WatchRelay(gateways, auth, ProfileStore(keys), connection, sessions, AudioApi(client), ChatHost(connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), settings, speechGate = gate)
        val calls = WatchCalls(relay, AudioApi(client), outbox, backgroundScope, speechGate = gate)

        calls.start("watch", CallStart("w1", "s1", "v=0 offer\r\n"))
        fun phases() = outbox.updates.value.map { it["phase"]?.jsonPrimitive?.content }
        withContext(Dispatchers.Default) { withTimeout(10_000) { while ("Live" !in phases()) delay(10) } }
        // The watch says it's there, so the call isn't ended for going quiet.
        calls.events("watch", CallEvents("w1", 1, emptyList()))

        gate.textInput(gateway)
        withContext(Dispatchers.Default) { withTimeout(10_000) { while ("Ended" !in phases()) delay(10) } }
        val ended = outbox.updates.value.last { it["phase"]?.jsonPrimitive?.content == "Ended" }
        assertNotNull(ended["error"]?.jsonPrimitive?.contentOrNull, "the watch wasn't told why its call ended")
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (relay.busy.value) delay(10) } }
        assertTrue(phases().count { it == "Ended" } >= 1)
    }
}
