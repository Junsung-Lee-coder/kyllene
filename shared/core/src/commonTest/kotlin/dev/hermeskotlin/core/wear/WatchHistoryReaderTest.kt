package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.custom.SessionBindings
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.wearlink.HISTORY_MESSAGE_BYTES
import dev.hermeskotlin.wearlink.HISTORY_PART_CHARS
import dev.hermeskotlin.wearlink.HistoryPage
import dev.hermeskotlin.wearlink.HistoryRequest
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork (requirements §9.1): the phone's side of the watch reader, against a fake gateway. */
class WatchHistoryReaderTest {
    private data class Row(val id: Long, val role: String, val text: String, val hidden: Boolean = false)

    private class Outbox : WatchOutbox {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val streamed = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun send(node: String, path: String, data: ByteArray): Boolean { sent += path to data; return true }
        override suspend fun stream(node: String, path: String, data: ByteArray): Boolean { streamed += path to data; return true }
    }

    private class Setup(val reader: WatchHistoryReader, val relay: WatchRelay, val outbox: Outbox, val bindings: SessionBindings, val profiles: ProfileStore) {
        var rows = mutableListOf<Row>()
        var missing = false
        var reads = 0
    }

    private val scope = WatchScope(FakeGateway.URL.value)
    private val roster = """{"profiles":[{"name":"assistant","display_name":"Assistant","canonical_session":{"id":"root","resolved_id":"tip"}}]}"""

    private suspend fun TestScope.setup(owner: String? = "account-1"): Setup {
        val store = InMemoryKeyValueStore()
        val bindings = SessionBindings(store)
        if (owner != null) bindings.bound(FakeGateway.URL, owner) else bindings.pending(FakeGateway.URL)
        lateinit var s: Setup
        val gateway = FakeGateway(backgroundScope).apply {
            ready = { FakeGateway.QUIET_READY }
            http = { request ->
                val path = request.url.encodedPath
                if (path.startsWith("/api/sessions/") && path.endsWith("/messages")) {
                    s.reads++
                    if (s.missing) json("""{"detail":"Not Found"}""", HttpStatusCode.NotFound)
                    else {
                        val limit = request.url.parameters["limit"]!!.toInt()
                        val offset = request.url.parameters["offset"]?.toInt() ?: 0
                        val all = s.rows
                        val window = all.subList((all.size - offset - limit).coerceAtLeast(0), (all.size - offset).coerceAtLeast(0))
                        val session = path.removePrefix("/api/sessions/").removeSuffix("/messages")
                        json(buildJsonObject {
                            put("session_id", session)
                            put("messages", buildJsonArray {
                                window.forEach { row ->
                                    add(buildJsonObject {
                                        put("id", row.id); put("role", row.role); put("content", row.text); put("timestamp", 1_000.0 + row.id)
                                        if (row.hidden) put("display_kind", "hidden")
                                    })
                                }
                            })
                        }.toString())
                    }
                } else json("{}")
            }
            answer = { call -> if (call.method == "profiles.list") roster else "{}" }
        }
        gateway.start()
        val tokens = NativeTokens(store).apply { set(FakeGateway.URL, NativeSession("test-access", "test-refresh")) }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens, bindings)
        val gateways = GatewayRepository(store).apply { save(SavedGateway(FakeGateway.URL.value)) }
        val sessions = SessionsApi(gateway.client)
        val bots = BotsApi(gateway.connection)
        val profiles = ProfileStore(store)
        val outbox = Outbox()
        val relay = WatchRelay(gateways, auth, profiles, gateway.connection, sessions, AudioApi(gateway.client),
            ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), SettingsStore(store, backgroundScope))
        s = Setup(WatchHistoryReader(gateways, auth, profiles, gateway.connection, sessions, bots), relay, outbox, bindings, profiles)
        return s
    }

    private fun conversation(count: Int, from: Long = 1) = (from until from + count).map { Row(it, if (it % 2L == 1L) "user" else "assistant", "message $it") }

    private fun request(offset: Int = 0, id: String = "r$offset", chatId: String? = "chat", botProfile: String? = null, messageId: String? = null, part: Int = 0, scope: WatchScope = this.scope) =
        HistoryRequest(id, scope, chatId, botProfile, offset, messageId, part)

    @Test
    fun pagesGoBackByStoredRowsAndShowOnlyMessages() = runTest {
        val s = setup()
        s.rows += conversation(30)
        // A tool row and a hidden row count toward the offset but aren't shown.
        s.rows.add(25, Row(100, "tool", "tool output"))
        s.rows.add(10, Row(101, "assistant", "hidden", hidden = true))
        val newest = s.reader.read(request(0))
        assertNull(newest.error)
        assertNotNull(newest.account)
        assertEquals(19, newest.messages.size)
        assertEquals("message 30", newest.messages.last().text)
        assertEquals(20, newest.nextOffset)
        val older = s.reader.read(request(20))
        assertEquals(11, older.messages.size)
        assertTrue(older.messages.none { it.text == "hidden" })
        assertNull(older.nextOffset)
        val ids = (older.messages + newest.messages).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals((1..30).map { "message $it" }, (older.messages + newest.messages).map { it.text })
    }

    @Test
    fun aLongMessageComesWholeInPartsEvenAfterNewTurnsShiftIt() = runTest {
        val s = setup()
        val long = buildString { while (length < HISTORY_PART_CHARS * 2 + 5_000) append("A paragraph of the reply. ") }
        s.rows += conversation(5)
        s.rows += Row(6, "assistant", long)
        val page = s.reader.read(request(0))
        val first = page.messages.last()
        assertEquals(3, first.parts)
        assertEquals(HISTORY_PART_CHARS, first.text.length)
        // 25 new turns since: the message is a page older now.
        s.rows += conversation(25, from = 7)
        val rest = (1 until first.parts).map { part ->
            val answer = s.reader.read(request(0, id = "p$part", messageId = first.id, part = part))
            assertNull(answer.error)
            answer.messages.single().also { assertEquals(part, it.part) }.text
        }
        assertEquals(long, first.text + rest.joinToString(""))
    }

    @Test
    fun aPendingAccountGetsNoContentAtAll() = runTest {
        val s = setup(owner = null)
        s.rows += conversation(5)
        val page = s.reader.read(request(0))
        assertEquals(WatchHistoryReader.UNCONFIRMED, page.error)
        assertNull(page.account)
        assertTrue(page.messages.isEmpty())
        assertEquals(0, s.reads)
    }

    @Test
    fun theAccountKeyNamesTheAccountWithoutTellingIt() = runTest {
        val s = setup(owner = "account-1")
        val one = s.reader.accountKey(FakeGateway.URL)!!
        assertTrue(Regex("a-[0-9a-f]{32}").matches(one))
        assertTrue("account-1" !in one)
        assertEquals(one, s.reader.accountKey(FakeGateway.URL))
        s.bindings.bound(FakeGateway.URL, "account-2")
        assertNotEquals(one, s.reader.accountKey(FakeGateway.URL))
        s.bindings.pending(FakeGateway.URL)
        assertNull(s.reader.accountKey(FakeGateway.URL))
    }

    @Test
    fun aMissingConversationIsAnErrorAndSendsNothing() = runTest {
        val s = setup()
        s.missing = true
        val page = s.reader.read(request(0))
        assertNotNull(page.error)
        assertTrue(page.messages.isEmpty())
        assertNotNull(page.account)
    }

    @Test
    fun anotherProfileOrGatewayIsNotRead() = runTest {
        val s = setup()
        s.rows += conversation(3)
        s.profiles.set(FakeGateway.URL, "work")
        assertEquals(WatchHistoryReader.CHANGED, s.reader.read(request(0)).error)
        assertEquals(WatchHistoryReader.CHANGED, s.reader.read(request(0, scope = WatchScope("https://other.example"))).error)
        assertEquals(0, s.reads)
        assertEquals(3, s.reader.read(request(0, scope = WatchScope(FakeGateway.URL.value, "work"))).messages.size)
    }

    @Test
    fun aBotIsReadFromItsStoredChatOnly() = runTest {
        val s = setup()
        s.rows += conversation(4)
        val page = s.reader.read(request(0, chatId = null, botProfile = "assistant", scope = WatchScope(FakeGateway.URL.value, "assistant")))
        assertNull(page.error)
        assertEquals("tip", page.sessionId)
        assertEquals(4, page.messages.size)
    }

    @Test
    fun theRelaySendsSmallPagesAsMessagesAndLargeOnesOnAChannel() = runTest {
        val s = setup()
        s.rows += conversation(3)
        s.relay.history("watch", request(0, id = "small"))
        withTimeout(10_000) { while (s.outbox.sent.none { it.first == WearPaths.HISTORY }) delay(10) }
        assertEquals("small", WearJson.decode<HistoryPage>(s.outbox.sent.single { it.first == WearPaths.HISTORY }.second)!!.request.requestId)
        // Over the message size, and still one part.
        val big = HISTORY_MESSAGE_BYTES + 10_000
        assertTrue(big < HISTORY_PART_CHARS)
        s.rows += Row(50, "assistant", "x".repeat(big))
        s.relay.history("watch", request(0, id = "large"))
        withTimeout(10_000) { while (s.outbox.streamed.none { it.first == WearPaths.HISTORY }) delay(10) }
        val large = WearJson.decode<HistoryPage>(s.outbox.streamed.single().second)!!
        assertEquals("large", large.request.requestId)
        assertEquals(big, large.messages.last().text.length)
        assertEquals(1, large.messages.last().parts)
    }

    @Test
    fun theStatusTellsTheWatchItReadsAndWhoseAccount() = runTest {
        val s = setup()
        s.relay.status("watch", requestId = "st")
        withTimeout(10_000) { while (s.outbox.sent.none { it.first == WearPaths.STATUS }) delay(10) }
        val status = WearJson.decode<PhoneStatus>(s.outbox.sent.last { it.first == WearPaths.STATUS }.second)!!
        assertTrue(status.history)
        assertEquals(s.reader.accountKey(FakeGateway.URL), status.account)
    }
}
