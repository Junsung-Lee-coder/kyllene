package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.custom.RetainedCopies
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour: what the device received stays on it. Pages read are added to the saved copy, a row is kept
 * whole however big it is, the saved list has no cap and keeps chats the gateway stops listing, signing out puts
 * copies away where no one signing in next reads them, and a copy that can't be written is said.
 */
class RetainedCopiesTest {
    private val url = FakeGateway.URL
    private val dao = InMemoryOfflineDao()
    private var clock = 1_000L
    private val cache = OfflineCache(dao, PlainSealer, EmptyCoroutineContext) { clock }

    private fun row(id: Long, text: String = "m$id") =
        SessionMessage(id = id, role = if (id % 2 == 1L) "user" else "assistant", content = JsonPrimitive(text))

    /** The gateway's transcript of chat "c", served newest first by limit and offset as the real endpoint does. */
    private var transcript: List<SessionMessage> = (1L..150L).map { row(it) }

    private fun page(limit: Int, offset: Int): String {
        val end = (transcript.size - offset).coerceAtLeast(0)
        val rows = transcript.subList((end - limit).coerceAtLeast(0), end)
        return """{"session_id":"c","messages":[${rows.joinToString(",") { """{"id":${it.id},"role":"${it.role}","content":"${it.text}"}""" }}]}"""
    }

    private suspend fun TestScope.gateway() = FakeGateway(backgroundScope, reconnects = false).apply {
        answer = { call -> if (call.method == "session.resume") """{"session_id":"rt","running":false}""" else "{}" }
        http = { request ->
            json(page(request.url.parameters["limit"]!!.toInt(), request.url.parameters["offset"]?.toInt() ?: 0))
        }
        start(awaitConnected = false)
    }

    private suspend fun saved(chat: String = "c") = cache.savedTranscript(url, null, chat)?.rows?.map { it.id }

    @Test
    fun everyPageReadIsKeptAndRowsTheGatewayStopsSendingStay() = runTest {
        val gateway = gateway()
        val chat = ChatSession(url, "c", "Chat", gateway.connection, SessionsApi(gateway.client), backgroundScope, cache = cache)
        chat.start()
        chat.state.first { it.historyLoaded && it.messages.isNotEmpty() }
        testScheduler.runCurrent()
        assertEquals((91L..150L).toList(), saved())

        chat.loadOlder()
        testScheduler.runCurrent()
        assertEquals((31L..150L).toList(), saved())
        chat.stop()

        // The gateway drops rows (an undo) and has new ones: the newest page is laid over what the device kept.
        transcript = (1L..139L).map { row(it) } + (151L..152L).map { row(it, "new $it") }
        val again = ChatSession(url, "c", "Chat", gateway.connection, SessionsApi(gateway.client), backgroundScope, cache = cache)
        again.start()
        again.state.first { it.historyLoaded && it.savedCopyAt == null && it.messages.isNotEmpty() }
        testScheduler.runCurrent()
        assertEquals((31L..152L).toList(), saved())
        assertEquals("new 152", cache.savedTranscript(url, null, "c")!!.rows.last().text)
    }

    @Test
    fun aRowReadAgainReplacesItsSavedCopyInPlace() = runTest {
        cache.saveTranscript(url, null, "c", listOf(row(1), row(2, "draft"), row(3)), "c")
        cache.saveTranscript(url, null, "c", listOf(row(2, "final"), row(4)), "c")
        val rows = assertNotNull(cache.savedTranscript(url, null, "c")).rows
        assertEquals(listOf(1L, 2L, 3L, 4L), rows.map { it.id })
        assertEquals("final", rows[1].text)
    }

    @Test
    fun aRowTooBigForOneDatabaseRowIsKeptWholeInPieces() = runTest {
        // Emoji are surrogate pairs: a piece must not cut one in half.
        val huge = "😀" + "x".repeat(OfflineCache.MAX_PIECE_CHARS - 2) + "😀".repeat(OfflineCache.MAX_PIECE_CHARS) + "end"
        val rows = listOf(row(1), row(2, huge), row(3))
        cache.saveTranscript(url, null, "c", rows, "c")

        val saved = assertNotNull(cache.savedTranscript(url, null, "c")).rows
        assertEquals(rows, saved)
        val stored = dao.messages(url.value, "", "c")
        assertTrue(stored.size > 3, "the big row is split: ${stored.size} rows")
        assertTrue(stored.all { it.row.size <= OfflineCache.MAX_ROW_BYTES })
        // Saving again (a newer page) keeps it whole too.
        cache.saveTranscript(url, null, "c", listOf(row(3), row(4)), "c")
        assertEquals(huge, cache.savedTranscript(url, null, "c")!!.rows[1].text)
    }

    @Test
    fun theSavedListHasNoCapAndKeepsChatsTheGatewayStopsListing() = runTest {
        val many = (1..150).map { SessionSummary("s$it") }
        cache.saveList(url, null, SessionListFilter.Recent, many)
        assertEquals(150, cache.savedList(url, null, SessionListFilter.Recent)?.sessions?.size)

        cache.saveList(url, null, SessionListFilter.Recent, listOf(SessionSummary("new"), SessionSummary("s2", title = "Renamed")))
        val list = assertNotNull(cache.savedList(url, null, SessionListFilter.Recent)).sessions
        assertEquals(listOf("new", "s2") + (1..150).filter { it != 2 }.map { "s$it" }, list.map { it.id })
        assertEquals("Renamed", list[1].title)

        // Deleting a chat still drops it from the list.
        cache.forgetChat(url, null, "s7")
        assertTrue(cache.savedList(url, null, SessionListFilter.Recent)!!.sessions.none { it.id == "s7" })
    }

    @Test
    fun signingOutPutsCopiesAwayWhereTheNextAccountDoesntReadThem() = runTest {
        val other = GatewayUrl.parse("https://work.example.ts.net")
        cache.saveTranscript(url, null, "c", listOf(row(1, "first account")), "c")
        cache.saveList(url, null, SessionListFilter.Recent, listOf(SessionSummary("c")))
        cache.saveTranscript(other, null, "w", listOf(row(1)), "w")

        assertNotNull(cache.detachGateway(url))

        assertNull(cache.savedTranscript(url, null, "c"))
        assertNull(cache.savedList(url, null, SessionListFilter.Recent))
        assertNotNull(cache.savedTranscript(other, null, "w"))
        // Another account signs in to the same address and reads the same chat id: only its own rows show.
        cache.saveTranscript(url, null, "c", listOf(row(5, "second account")), "c")
        assertEquals(listOf("second account"), cache.savedTranscript(url, null, "c")!!.rows.map { it.text })
        // The first account's copies are still on the device.
        val detached = dao.gatewayKeys().single { RetainedCopies.isDetached(it) }
        assertTrue(RetainedCopies.isDetached(detached, url.value))
        assertEquals(1, dao.messages(detached, "", "c").size)
        assertEquals(1, dao.list(detached, "", SessionListFilter.Recent.name).size)
        // A second sign-out in the same millisecond goes to a key of its own.
        assertNotNull(cache.detachGateway(url))
        assertEquals(2, dao.gatewayKeys().count { RetainedCopies.isDetached(it, url.value) })
    }

    @Test
    fun aCopyThatCantBeWrittenIsSaidAndWhatWasSavedStays() = runTest {
        cache.saveTranscript(url, null, "c", listOf(row(1, "kept")), "c")
        val failing = object : OfflineDao by dao {
            override suspend fun replaceTranscript(transcript: SavedTranscriptRow, rows: List<SavedMessageRow>) =
                throw IllegalStateException("disk full")
        }
        val full = OfflineCache(failing, PlainSealer, EmptyCoroutineContext) { clock }
        val gateway = gateway()
        val chat = ChatSession(url, "c", "Chat", gateway.connection, SessionsApi(gateway.client), backgroundScope, cache = full)
        chat.start()
        val state = chat.state.first { it.error != null }
        assertTrue(state.error!!.startsWith("Couldn't save this chat on this device."), state.error)
        assertEquals(listOf("kept"), cache.savedTranscript(url, null, "c")!!.rows.map { it.text })
    }

    @Test
    fun rowsWithoutAnIdKeepTheirPlaceAndArentDoubled() {
        val note = SessionMessage(role = "system", content = JsonPrimitive("note"))
        val first = RetainedCopies.mergeRows(emptyList(), listOf(row(1), note, row(2)))
        val again = RetainedCopies.mergeRows(first, listOf(row(1), note, row(2), row(3)))
        assertEquals(listOf(row(1), note, row(2), row(3)), again)
        assertEquals(listOf(row(1), note, row(2), row(3), row(4)), RetainedCopies.mergeRows(again, listOf(row(4))))
    }

    @Test
    fun brokenPiecesAreNoSavedCopy() {
        val pieces = RetainedCopies.split("abcdefghij", 3)
        assertEquals(listOf("abcdefghij"), RetainedCopies.join(pieces))
        assertNull(RetainedCopies.join(pieces.drop(1)))
        assertNull(RetainedCopies.join(pieces.dropLast(1)))
        assertNull(RetainedCopies.join(listOf(pieces[1], pieces[0]) + pieces.drop(2)))
        assertEquals(listOf("{}"), RetainedCopies.join(listOf("{}")))
    }

    // Fork repair: unreadable rows are set aside, never dropped with the readable ones.

    /** Plain text, but a row starting with "X" doesn't open, as a damaged or foreign-key row wouldn't. */
    private object Tampered : Sealer {
        override suspend fun seal(plain: String) = plain.encodeToByteArray()
        override suspend fun open(sealed: ByteArray): String? = sealed.decodeToString().takeUnless { it.startsWith("X") }
    }

    private suspend fun tamper(gateway: String, profile: String, session: String, position: Int) {
        val transcript = dao.transcript(gateway, profile, session)!!
        val rows = dao.messages(gateway, profile, session).map {
            if (it.position == position) SavedMessageRow(it.gateway, it.profile, it.sessionId, it.position, "X".encodeToByteArray() + it.row) else it
        }
        dao.replaceTranscript(transcript, rows)
    }

    @Test
    fun aDamagedPieceThenASaveKeepsTheOtherRowsAndSetsTheOriginalAside() = runTest {
        val tampered = OfflineCache(dao, Tampered, EmptyCoroutineContext) { clock }
        val huge = "y".repeat(OfflineCache.MAX_PIECE_CHARS * 2 + 5)
        tampered.saveTranscript(url, null, "c", listOf(row(1), row(2, huge), row(3)), "c")
        val stored = dao.messages(url.value, "", "c")
        assertTrue(stored.size == 5, "row 2 is in 3 pieces: ${stored.size}")
        tamper(url.value, "", "c", position = 2) // the middle piece of row 2
        assertNull(tampered.savedTranscript(url, null, "c"))

        assertNotNull(tampered.saveTranscript(url, null, "c", listOf(row(4)), "c"))

        assertEquals(listOf(1L, 3L, 4L), tampered.savedTranscript(url, null, "c")?.rows?.map { it.id })
        val aside = dao.gatewayKeys().single { RetainedCopies.isPutAway(it, url.value) }
        assertEquals(stored.size, dao.messages(aside, "", "c").size)
        assertTrue(dao.messages(aside, "", "c")[2].row.decodeToString().startsWith("X"))
    }

    @Test
    fun aRowThatDoesntOpenThenASaveKeepsTheOthers() = runTest {
        val tampered = OfflineCache(dao, Tampered, EmptyCoroutineContext) { clock }
        tampered.saveTranscript(url, null, "c", (1L..6L).map { row(it) }, "c")
        tamper(url.value, "", "c", position = 0)
        tampered.saveTranscript(url, null, "c", listOf(row(7)), "c")
        assertEquals((2L..7L).toList(), tampered.savedTranscript(url, null, "c")?.rows?.map { it.id })
        assertEquals(1, dao.gatewayKeys().count { RetainedCopies.isPutAway(it, url.value) })
    }

    @Test
    fun aListEntryThatDoesntOpenThenASaveKeepsTheOthers() = runTest {
        val tampered = OfflineCache(dao, Tampered, EmptyCoroutineContext) { clock }
        tampered.saveList(url, null, SessionListFilter.Recent, listOf(SessionSummary("a"), SessionSummary("b"), SessionSummary("c")))
        val rows = dao.list(url.value, "", SessionListFilter.Recent.name)
        dao.replaceList(url.value, "", SessionListFilter.Recent.name, rows.map {
            if (it.id == "b") SavedListRow(it.gateway, it.profile, it.filter, it.id, it.position, it.savedAt, "X".encodeToByteArray() + it.row) else it
        })

        assertNotNull(tampered.saveList(url, null, SessionListFilter.Recent, listOf(SessionSummary("d"))))

        assertEquals(listOf("d", "a", "c"), tampered.savedList(url, null, SessionListFilter.Recent)?.sessions?.map { it.id })
        val aside = dao.gatewayKeys().single { RetainedCopies.isPutAway(it, url.value) }
        assertEquals(3, dao.list(aside, "", SessionListFilter.Recent.name).size)
    }

    // Fork repair: copies belong to the verified account.

    private val alice = RetainedCopies.owner("password", "alice")
    private val bob = RetainedCopies.owner("password", "bob")

    private suspend fun text(chat: String = "c") = cache.savedTranscript(url, null, chat)?.rows?.map { it.text }

    @Test
    fun copiesFollowTheVerifiedAccount() = runTest {
        assertTrue(cache.bindOwner(url, alice))
        cache.saveTranscript(url, null, "c", listOf(row(1, "alice's")), "c")
        assertTrue(cache.bindOwner(url, alice))
        assertEquals(listOf("alice's"), text())

        assertTrue(cache.bindOwner(url, bob))
        assertNull(text())
        cache.saveTranscript(url, null, "c", listOf(row(9, "bob's")), "c")
        assertEquals(listOf("bob's"), text())

        assertTrue(cache.bindOwner(url, alice))
        assertEquals(listOf("alice's"), text())
        assertTrue(cache.bindOwner(url, bob))
        assertEquals(listOf("bob's"), text())
    }

    @Test
    fun aDetachedAccountsRowsComeBackUnderTheOnesReadSince() = runTest {
        cache.bindOwner(url, alice)
        cache.saveTranscript(url, null, "c", listOf(row(1), row(2, "old")), "c")
        cache.detachGateway(url)
        // Alice signs in again, but her copies can't be found to bring back; she reads on meanwhile.
        val blind = OfflineCache(object : OfflineDao by dao {
            override suspend fun transcriptGateways(): List<String> = throw IllegalStateException("busy")
        }, PlainSealer, EmptyCoroutineContext) { clock }
        assertTrue(blind.bindOwner(url, alice))
        cache.saveTranscript(url, null, "c", listOf(row(2, "new"), row(3)), "c")
        assertEquals(listOf(2L, 3L), saved())
        // The next sign-in as Alice brings them back, under what she read since.
        assertTrue(cache.bindOwner(url, alice))
        val rows = assertNotNull(cache.savedTranscript(url, null, "c")).rows
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertEquals("new", rows[1].text)
    }

    @Test
    fun anUnverifiedSignInPutsCopiesAwayAndGivesNoneBack() = runTest {
        cache.bindOwner(url, alice)
        cache.saveTranscript(url, null, "c", listOf(row(1, "alice's")), "c")
        assertTrue(cache.bindOwner(url, null))
        assertNull(text())
        // What an unverified session saves belongs to no known account: put away at the next verified sign-in too.
        cache.saveTranscript(url, null, "c", listOf(row(5, "unknown")), "c")
        cache.bindOwner(url, alice)
        assertEquals(listOf("alice's"), text())
        assertEquals(1, dao.gatewayKeys().count { RetainedCopies.isPutAway(it, url.value) })
    }

    @Test
    fun aDetachThatFailsIsAFailedBindAndMovesNothing() = runTest {
        cache.bindOwner(url, alice)
        cache.saveTranscript(url, null, "c", listOf(row(1, "alice's")), "c")
        val stuck = OfflineCache(object : OfflineDao by dao {
            override suspend fun moveGateway(from: String, to: String) = throw IllegalStateException("disk full")
        }, PlainSealer, EmptyCoroutineContext) { clock }

        assertEquals(false, stuck.bindOwner(url, bob))
        assertNull(stuck.detachGateway(url))
        assertEquals(listOf("alice's"), text())
        assertTrue(stuck.bindOwner(url, alice))
    }

    @Test
    fun copiesPutAwayCanBeDeletedForOneGatewayOrAll() = runTest {
        val other = GatewayUrl.parse("https://work.example.ts.net")
        cache.saveTranscript(url, null, "c", listOf(row(1)), "c")
        cache.saveTranscript(other, null, "w", listOf(row(1)), "w")
        cache.detachGateway(url)
        cache.detachGateway(other)
        cache.saveTranscript(url, null, "c", listOf(row(2)), "c")

        assertNotNull(cache.forgetPutAway(url))
        assertEquals(setOf(url.value), dao.gatewayKeys().filterNot { RetainedCopies.isPutAway(it, other.value) }.toSet())
        assertNotNull(cache.forgetPutAway())
        assertEquals(setOf(url.value), dao.gatewayKeys())
        assertEquals(listOf(2L), saved())
    }
}
