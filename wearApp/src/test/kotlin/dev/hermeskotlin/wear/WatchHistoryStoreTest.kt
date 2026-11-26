package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.HistoryMessage
import dev.hermeskotlin.wearlink.WatchScope
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork (requirements §9.2): the watch's saved copy of what it read. */
class WatchHistoryStoreTest {
    private val base: File = Files.createTempDirectory("history-store").toFile()
    private val root = File(base, "history")
    private val account = "a-" + "1".repeat(32)
    private val chat = WatchHistoryStore.Conversation(WatchScope("https://gw.example", "work"), "chat-1", null)

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun message(n: Int) = HistoryMessage("s:$n", if (n % 2 == 1) "user" else "assistant", "message $n", at = n.toDouble())

    /** What the gateway gives for [offset] when it holds messages 1..[count], 20 rows a page, newest first. */
    private fun page(count: Int, offset: Int): Pair<List<HistoryMessage>, Int?> {
        val end = count - offset
        val start = (end - 20).coerceAtLeast(0)
        return (start + 1..end).map(::message) to (offset + 20).takeIf { start > 0 }
    }

    private fun WatchHistoryStore.read(count: Int, offset: Int, conversation: WatchHistoryStore.Conversation = chat) =
        page(count, offset).let { (messages, next) -> savePage(account, conversation, offset, messages, next, "Plans", now = 1L) }

    @Test
    fun moreThanFiftyChatsAreAllKeptAcrossARestart() {
        val store = WatchHistoryStore(root)
        val chats = (1..60).map { chat.copy(chatId = "chat-$it") }
        chats.forEach { store.read(30, 0, it) }
        val again = WatchHistoryStore(root)
        chats.forEach { assertEquals(20, again.read(account, it).messages.size) }
        assertEquals(60, again.summary().first)
        assertTrue(again.summary().second > 0)
    }

    @Test
    fun olderPagesGoBeforeAndNothingRepeatsWhenNewTurnsShiftTheOffsets() {
        val store = WatchHistoryStore(root)
        store.read(30, 0)
        assertEquals(20, store.read(account, chat).meta.nextOffset)
        // Three new turns since: the newest page overlaps the saved copy, and the older page's offset now lands
        // three messages later.
        store.read(33, 0)
        val older = store.read(33, store.read(account, chat).meta.nextOffset!!)
        assertEquals((1..33).map { "s:$it" }, older.messages.map { it.id })
        assertTrue(older.messages.none { it.gapBefore })
        assertEquals(null, older.meta.nextOffset)
    }

    @Test
    fun aNewestPageThatDoesntMeetTheCopyIsMarkedAndReadingOnFillsTheGap() {
        val store = WatchHistoryStore(root)
        store.read(20, 0)
        // 30 new turns while the watch was away.
        val newest = store.read(50, 0)
        assertTrue(newest.messages.single { it.id == "s:31" }.gapBefore)
        assertEquals(20, newest.meta.nextOffset)
        val filled = store.read(50, 20)
        assertEquals((1..50).map { "s:$it" }, filled.messages.map { it.id })
        assertTrue(filled.messages.none { it.gapBefore })
    }

    @Test
    fun linesThatDontReadAreKeptAsTheyWereAndCounted() {
        val store = WatchHistoryStore(root)
        store.read(5, 0)
        val file = root.walkTopDown().single { it.name == "messages.jsonl" }
        file.appendText("{not json\n")
        val meta = File(file.parentFile, "meta.json")
        meta.writeText("{broken")
        assertEquals(1, store.read(account, chat).unreadable)
        store.read(6, 0)
        assertTrue(file.readLines().contains("{not json"))
        assertEquals(1, store.read(account, chat).unreadable)
        assertEquals(6, store.read(account, chat).messages.size)
        // The meta that didn't read is set aside whole, not written over.
        assertEquals("{broken", file.parentFile.listFiles()!!.single { it.name.startsWith("meta.json.unreadable") }.readText())
    }

    @Test
    fun aWriteThatFailsLeavesTheLastCopyWhole() {
        var failing = false
        val store = WatchHistoryStore(root) { file, bytes ->
            if (failing) { file.writeBytes(bytes.copyOf(bytes.size / 2)); throw IOException("disk full") }
            file.writeBytes(bytes)
        }
        store.read(10, 0)
        failing = true
        assertFailsWith<IOException> { store.read(12, 0) }
        val saved = store.read(account, chat)
        assertEquals((1..10).map { "s:$it" }, saved.messages.map { it.id })
        assertEquals(0, saved.unreadable)
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun noIdOrNameIsEverAPath() {
        val store = WatchHistoryStore(root)
        val sneaky = WatchHistoryStore.Conversation(WatchScope("../../gw", "../p"), "../../../etc/passwd", null)
        store.read(3, 0, sneaky)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            assertTrue(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
            val relative = file.relativeTo(root).invariantSeparatorsPath
            assertTrue(Regex("v1/a-[0-9a-f]{32}/[0-9a-f]{64}/(messages\\.jsonl|meta\\.json)").matches(relative), relative)
        }
        assertFailsWith<IOException> { store.savePage("../../escape", chat, 0, listOf(message(1)), null, null, 1L) }
        assertTrue(store.read("../../escape", chat).messages.isEmpty())
        assertFalse(WatchHistoryStore.isAccountKey("a-" + "1".repeat(31) + "/"))
    }

    @Test
    fun partsJoinInOrderOnly() {
        val store = WatchHistoryStore(root)
        store.savePage(account, chat, 0, listOf(HistoryMessage("s:1", "assistant", "one ", parts = 3)), null, null, 1L)
        assertFailsWith<IOException> { store.savePart(account, chat, HistoryMessage("s:1", "assistant", "three", part = 2, parts = 3)) }
        store.savePart(account, chat, HistoryMessage("s:1", "assistant", "two ", part = 1, parts = 3))
        val whole = store.savePart(account, chat, HistoryMessage("s:1", "assistant", "three", part = 2, parts = 3)).messages.single()
        assertEquals("one two three", whole.text)
        assertTrue(whole.complete)
        // Read again from the start, the whole copy stays whole.
        store.savePage(account, chat, 0, listOf(HistoryMessage("s:1", "assistant", "one ", parts = 3)), null, null, 2L)
        assertEquals("one two three", store.read(account, chat).messages.single().text)
    }

    @Test
    fun theOldestPageReadIsRememberedAsTheEnd() {
        val store = WatchHistoryStore(root)
        store.read(10, 0)
        assertEquals(null, WatchHistoryStore(root).read(account, chat).meta.nextOffset)
        assertTrue(WatchHistoryStore(root).read(account, chat).meta.savedAt > 0)
    }

    @Test
    fun accountsAreKeptApart() {
        val store = WatchHistoryStore(root)
        store.read(5, 0)
        assertTrue(store.read("a-" + "2".repeat(32), chat).messages.isEmpty())
    }

    @Test
    fun deletingTakesOnlyTheReadersFolder() {
        val store = WatchHistoryStore(root)
        store.read(5, 0)
        store.quarantine("{}".encodeToByteArray(), 1L)
        val other = File(base, "other-app-data").apply { writeText("keep") }
        assertTrue(store.deleteAll())
        assertFalse(root.exists())
        assertEquals("keep", other.readText())
        assertEquals(0 to 0L, store.summary())
    }
}
