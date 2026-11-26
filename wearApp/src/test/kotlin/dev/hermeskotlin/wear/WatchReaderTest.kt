package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.HistoryMessage
import dev.hermeskotlin.wearlink.HistoryPage
import dev.hermeskotlin.wearlink.HistoryRequest
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.historyParts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork (requirements §9.1, §9.2): the watch reader's identity, ordering and delete rules, with the phone faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchReaderTest {
    private val base: File = Files.createTempDirectory("watch-reader").toFile()
    private val root = File(base, "history")
    private val a = "a-" + "a".repeat(32)
    private val b = "a-" + "b".repeat(32)
    private val scope = WatchScope("https://gw.example")
    private val chat = WatchHistoryStore.Conversation(scope, "chat-1", null)

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private class Memory : ReaderAccount {
        override var account: String? = null
    }

    /** The phone: what it was asked, and whether it can be reached. */
    private class Phone {
        val asked = mutableListOf<HistoryRequest>()
        var offline = false
    }

    private var ids = 0

    private fun TestScope.reader(memory: ReaderAccount = Memory(), phone: Phone = Phone(), store: WatchHistoryStore = WatchHistoryStore(root)) =
        WatchReader(store, memory, backgroundScope, UnconfinedTestDispatcher(testScheduler),
            send = { request -> if (phone.offline) "offline" else { phone.asked += request; null } },
            newId = { "req-${ids++}" }, now = { 1_000L })

    private fun messages(range: IntRange) = range.map { HistoryMessage("s:$it", if (it % 2 == 1) "user" else "assistant", "message $it", at = it.toDouble()) }

    private suspend fun WatchReader.answer(request: HistoryRequest, account: String?, messages: List<HistoryMessage> = emptyList(), nextOffset: Int? = null, error: String? = null) =
        accept(WearJson.encode(HistoryPage(request, account, "s", messages, nextOffset, error)))

    private fun quarantined() = File(root, "quarantine").listFiles().orEmpty().toList()

    @Test
    fun whatWasReadShowsAgainAfterARestartWithNoPhone() = runTest {
        val memory = Memory()
        val phone = Phone()
        val first = reader(memory, phone)
        first.onStatus(signedIn = true, history = true, account = a)
        first.open(chat, "Plans")
        first.answer(phone.asked.single(), a, messages(11..30), nextOffset = 20)
        assertEquals(ReaderStatus.UpToDate, first.state.value.status)

        val offline = Phone().apply { this.offline = true }
        val again = reader(memory, offline)
        again.open(chat, "Plans")
        val state = again.state.value
        assertEquals(ReaderStatus.Offline, state.status)
        assertEquals((11..30).map { "message $it" }, state.messages.map { it.text })
        assertEquals(20, state.olderOffset)
    }

    @Test
    fun olderPagesComeInOrderWithoutRepeats() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(11..30), nextOffset = 20)
        reader.older()
        assertEquals(20, phone.asked.last().offset)
        // New turns moved the offsets: the older page overlaps what's saved.
        reader.answer(phone.asked.last(), a, messages(1..13), nextOffset = null)
        assertEquals((1..30).map { "s:$it" }, reader.state.value.messages.map { it.id })
        assertNull(reader.state.value.olderOffset)
    }

    @Test
    fun aPendingAccountShowsNothingAndKeepsWhatArrivesAside() = runTest {
        val memory = Memory()
        val phone = Phone()
        val reader = reader(memory, phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(1..5))
        reader.refresh()
        val inFlight = phone.asked.last()
        // The phone is confirming its account again: nothing saved shows, and nothing more is asked.
        reader.onStatus(signedIn = true, history = true, account = null)
        assertTrue(reader.state.value.messages.isEmpty())
        assertTrue(reader.state.value.locked)
        assertNull(memory.account)
        val asked = phone.asked.size
        reader.refresh()
        assertEquals(asked, phone.asked.size)
        // What the phone still sends for the request before is kept, never shown.
        reader.answer(inFlight, a, messages(1..6))
        assertTrue(reader.state.value.messages.isEmpty())
        assertEquals(1, quarantined().size)
        // Confirmed again as the same account: its copy is back, as it was.
        reader.onStatus(true, true, a)
        assertEquals(5, reader.state.value.messages.size)
    }

    @Test
    fun anAccountSwitchDropsTheOldCopyFromViewAndALateReplyNeverCrosses() = runTest {
        val memory = Memory()
        val phone = Phone()
        val reader = reader(memory, phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(1..5))
        reader.refresh()
        val late = phone.asked.last()
        reader.onStatus(true, true, b)
        assertTrue(reader.state.value.messages.isEmpty())
        assertEquals(b, memory.account)
        // A's reply, late: kept aside, not shown for B, and B stays the account.
        reader.answer(late, a, messages(1..8))
        assertTrue(reader.state.value.messages.isEmpty())
        assertEquals(b, memory.account)
        assertEquals(1, quarantined().size)
        // Offline now, B is still the one shown: never A again.
        val again = reader(memory, Phone().apply { offline = true })
        again.open(chat, null)
        assertTrue(again.state.value.messages.isEmpty())
        assertEquals(b, again.account)
    }

    @Test
    fun aReplyNamingAnotherAccountThanAskedIsKeptAside() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), b, messages(1..3))
        assertTrue(reader.state.value.messages.isEmpty())
        assertEquals(1, quarantined().size)
        assertTrue(WatchHistoryStore(root).read(b, chat).messages.isEmpty())
    }

    @Test
    fun aMissingConversationKeepsTheSavedCopy() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(1..5))
        reader.refresh()
        reader.answer(phone.asked.last(), a, error = "Not Found")
        assertEquals(ReaderStatus.Failed, reader.state.value.status)
        assertEquals("Not Found", reader.state.value.error)
        assertEquals(5, reader.state.value.messages.size)
    }

    @Test
    fun signingOutHidesTheCopyButKeepsIt() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(1..5))
        reader.onStatus(signedIn = false, history = true, account = null)
        assertTrue(reader.state.value.messages.isEmpty())
        assertTrue(!reader.state.value.signedIn)
        assertEquals(5, WatchHistoryStore(root).read(a, chat).messages.size)
    }

    @Test
    fun deletingDropsWhatIsStillOnItsWay() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(1..5))
        reader.refresh()
        val inFlight = phone.asked.last()
        assertTrue(reader.deleteAll())
        assertTrue(reader.state.value.messages.isEmpty())
        reader.answer(inFlight, a, messages(1..6))
        assertTrue(reader.state.value.messages.isEmpty())
        assertTrue(WatchHistoryStore(root).read(a, chat).messages.isEmpty())
        assertTrue(quarantined().isEmpty())
        assertEquals(0 to 0L, reader.saved.value)
        // Reading again after the delete works as before.
        reader.refresh()
        reader.answer(phone.asked.last(), a, messages(1..2))
        assertEquals(2, reader.state.value.messages.size)
    }

    @Test
    fun aLongMessageIsFetchedWholePartByPart() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        val long = "x".repeat(250_000)
        val parts = historyParts(long)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, listOf(HistoryMessage("s:1", "assistant", parts[0], parts = parts.size)))
        val partial = reader.state.value.messages.single()
        assertTrue(!partial.complete)
        reader.rest("s:1")
        for (part in 1 until parts.size) {
            val request = phone.asked.last()
            assertEquals("s:1", request.messageId)
            assertEquals(part, request.part)
            reader.answer(request, a, listOf(HistoryMessage("s:1", "assistant", parts[part], part = part, parts = parts.size)))
        }
        val whole = reader.state.value.messages.single()
        assertTrue(whole.complete)
        assertEquals(long, whole.text)
        assertNull(reader.state.value.restFor)
    }

    @Test
    fun aSaveThatFailsSaysSoAndKeepsTheLastCopy() = runTest {
        var failing = false
        val store = WatchHistoryStore(root) { file, bytes -> if (failing) throw IOException("disk full") else file.writeBytes(bytes) }
        val phone = Phone()
        val reader = reader(phone = phone, store = store)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        reader.answer(phone.asked.last(), a, messages(1..5))
        failing = true
        reader.refresh()
        reader.answer(phone.asked.last(), a, messages(1..7))
        assertEquals("disk full", reader.state.value.storageError)
        assertEquals(5, reader.state.value.messages.size)
    }

    @Test
    fun noPhoneMeansOfflineAndAnOlderPhoneMeansUpdate() = runTest {
        val phone = Phone().apply { offline = true }
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        assertEquals(ReaderStatus.Offline, reader.state.value.status)
        val older = reader(phone = Phone())
        older.onStatus(true, history = false, account = null)
        older.open(chat, null)
        assertTrue(older.state.value.locked)
    }

    @Test
    fun aPhoneThatDoesntAnswerLeavesTheCopyAndALateAnswerStillCounts() = runTest {
        val phone = Phone()
        val reader = reader(phone = phone)
        reader.onStatus(true, true, a)
        reader.open(chat, null)
        advanceTimeBy(WatchReader.ANSWER_WITHIN_MS + 1)
        assertEquals(ReaderStatus.Offline, reader.state.value.status)
        reader.answer(phone.asked.last(), a, messages(1..3))
        assertEquals(3, reader.state.value.messages.size)
        assertEquals(ReaderStatus.UpToDate, reader.state.value.status)
    }

    @Test
    fun theMessageScreenLaysOutEveryCharacter() {
        val text = (1..400).joinToString(" ") { "word$it" } + "\n\n" + "y".repeat(2_000)
        val chunks = readerChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 600 })
    }
}
