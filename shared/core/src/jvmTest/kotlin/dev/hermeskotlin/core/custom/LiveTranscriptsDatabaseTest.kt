package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.cache.KeystoreSealer
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.cache.offlineDatabase
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.LiveFragment
import dev.hermeskotlin.core.voice.LiveFragment.Speaker.Assistant
import dev.hermeskotlin.core.voice.LiveFragment.Speaker.User
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fork (requirements §8): live call transcripts on the real Room database and AES sealer the phone uses. The
 * transcript pieces are fixtures, not a real call.
 */
class LiveTranscriptsDatabaseTest {
    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val other = GatewayUrl.parse("https://other.example.ts.net")
    private val keys = InMemoryKeyValueStore()
    private var clock = 1_000_000L

    private fun store(db: LiveTranscriptDatabase, owner: String?, sealer: Sealer = KeystoreSealer(keys), dao: LiveTranscriptDao = db.dao()) =
        LiveTranscripts(dao, sealer, { owner }, { clock.also { clock += 1_000 } })

    private fun said(speaker: LiveFragment.Speaker, text: String, start: Long = 0) = LiveFragment(speaker, text, start, if (start > 0) start + 400 else 0)

    private fun <T> withDir(block: suspend (File) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("live-transcripts").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun callsComeBackAfterTheDatabaseIsClosedOnlyForTheirAccountGatewayAndSession(): Unit = withDir { dir ->
        val first = liveTranscriptDatabase(dir)
        store(first, "acct-A").begin(home, "s1", LiveTranscripts.Device.Phone).apply {
            assertTrue(add(said(User, "Book the ", 100)))
            assertTrue(add(said(User, "train", 600)))
            assertTrue(add(said(Assistant, "Which day?", 1_200)))
        }
        store(first, "acct-A").begin(home, "s1", LiveTranscripts.Device.Watch).apply { assertTrue(add(said(User, "Friday", 100))) }
        store(first, "acct-A").begin(home, "s2", LiveTranscripts.Device.Phone).apply { assertTrue(add(said(User, "Other chat", 100))) }
        store(first, "acct-B").begin(home, "s1", LiveTranscripts.Device.Phone).apply { assertTrue(add(said(User, "Other account", 100))) }
        store(first, "acct-A").begin(other, "s1", LiveTranscripts.Device.Phone).apply { assertTrue(add(said(User, "Other gateway", 100))) }
        store(first, null).begin(home, "s1", LiveTranscripts.Device.Phone).apply { assertTrue(add(said(User, "Unverified", 100))) }
        first.close()

        val again = liveTranscriptDatabase(dir)
        try {
            val earlier = store(again, "acct-A").earlier(home, "s1")
            assertEquals(
                listOf(listOf(User to "Book the train", Assistant to "Which day?"), listOf(User to "Friday")),
                earlier.calls.map { it.turns },
            )
            assertEquals(0, earlier.unreadable)
            assertEquals(listOf(listOf(User to "Other chat")), store(again, "acct-A").earlier(home, "s2").calls.map { it.turns })
            assertEquals(listOf(listOf(User to "Other account")), store(again, "acct-B").earlier(home, "s1").calls.map { it.turns })
            assertEquals(listOf(listOf(User to "Other gateway")), store(again, "acct-A").earlier(other, "s1").calls.map { it.turns })
            // An unverified account is given nothing, though its call is kept.
            assertTrue(store(again, null).earlier(home, "s1").calls.isEmpty())
            assertEquals(1, again.dao().session(LiveTranscripts.UNVERIFIED, home.value, "s1").size)
            // Each piece is sealed on disk, not in the clear.
            assertFalse(again.dao().session("acct-A", home.value, "s1").any { "train" in it.sealed.decodeToString() })
        } finally {
            again.close()
        }
    }

    @Test
    fun aCallBeforeItsChatHasASessionGoesToItOnceItHasOne(): Unit = withDir { dir ->
        val db = liveTranscriptDatabase(dir)
        try {
            val call = store(db, "acct-A").begin(home, null, LiveTranscripts.Device.Phone)
            assertTrue(call.add(said(User, "Start a list", 100)))
            // Not anyone's session's yet: no call gets it.
            assertTrue(store(db, "acct-A").earlier(home, "new").calls.isEmpty())
            assertTrue(call.session("new"))
            assertTrue(call.add(said(Assistant, "Started.", 800)))
            assertEquals(listOf(listOf(User to "Start a list", Assistant to "Started.")), store(db, "acct-A").earlier(home, "new").calls.map { it.turns })
            assertTrue(db.dao().call(call.callId).all { it.sessionId == "new" })
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingSavedChatsAndMessagesLeavesTheTranscripts(): Unit = withDir { dir ->
        val db = liveTranscriptDatabase(dir)
        val offline = offlineDatabase(dir)
        try {
            store(db, "acct-A").begin(home, "s1", LiveTranscripts.Device.Phone).apply { assertTrue(add(said(User, "Keep me", 100))) }
            val cache = OfflineCache(offline.dao(), KeystoreSealer(keys), EmptyCoroutineContext) { 1_000L }
            assertNotNull(cache.saveTranscript(home, null, "s1", listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("hi"))), "s1"))
            cache.forgetChat(home, null, "s1")
            cache.forgetGateway(home)
            cache.detachGateway(home)
            assertEquals(listOf(listOf(User to "Keep me")), store(db, "acct-A").earlier(home, "s1").calls.map { it.turns })
        } finally {
            offline.close()
            db.close()
        }
    }

    @Test
    fun theSamePieceToldTwiceIsKeptOnceAndWordsSaidTwiceStay(): Unit = withDir { dir ->
        val db = liveTranscriptDatabase(dir)
        try {
            val call = store(db, "acct-A").begin(home, "s1", LiveTranscripts.Device.Phone)
            call.add(said(User, "Turn it ", 100))
            call.add(said(User, "Turn it ", 100))
            // Untimed pieces can't be told apart from a word said again: both kept.
            call.add(said(User, "up "))
            call.add(said(User, "up "))
            assertEquals(3, db.dao().call(call.callId).size)
            assertEquals(listOf(listOf(User to "Turn it up up ")), store(db, "acct-A").earlier(home, "s1").calls.map { it.turns })
        } finally {
            db.close()
        }
    }

    @Test
    fun whatCantBeWrittenWaitsAndWhatCantBeReadStaysOnDisk(): Unit = withDir { dir ->
        val db = liveTranscriptDatabase(dir)
        try {
            // A disk that refuses writes for a while.
            var refusing = true
            val flaky = object : LiveTranscriptDao by db.dao() {
                override suspend fun insert(piece: LiveTranscriptPiece): Long = if (refusing) error("disk full") else db.dao().insert(piece)
            }
            val call = store(db, "acct-A", dao = flaky).begin(home, "s1", LiveTranscripts.Device.Phone)
            assertFalse(call.add(said(User, "First ", 100)))
            assertFalse(call.add(said(User, "second", 600)))
            assertFalse(call.saved)
            assertTrue(db.dao().call(call.callId).isEmpty())
            refusing = false
            assertTrue(call.flush())
            assertTrue(call.saved)
            assertEquals(listOf(0, 1), db.dao().call(call.callId).map { it.seq })

            // A sealer that can't seal for a while: nothing is written in the clear, and it's kept until it can.
            var sealing = false
            val sealer = KeystoreSealer(keys)
            val shy = object : Sealer {
                override suspend fun seal(plain: String) = if (sealing) sealer.seal(plain) else error("no key")
                override suspend fun open(sealed: ByteArray) = sealer.open(sealed)
            }
            val later = store(db, "acct-A", sealer = shy).begin(home, "s1", LiveTranscripts.Device.Phone)
            assertFalse(later.add(said(Assistant, "Hold on", 100)))
            assertTrue(db.dao().call(later.callId).isEmpty())
            sealing = true
            assertTrue(later.add(said(Assistant, ", done", 600)))
            assertEquals(listOf(0, 1), db.dao().call(later.callId).map { it.seq })

            // Another key (the keystore's was lost): those pieces can't be read, are counted, and stay as they are.
            val lostKey = store(db, "acct-A", sealer = KeystoreSealer(InMemoryKeyValueStore())).earlier(home, "s1")
            assertEquals(4, lostKey.unreadable)
            assertTrue(lostKey.calls.isEmpty())
            assertEquals(4, db.dao().session("acct-A", home.value, "s1").size)
        } finally {
            db.close()
        }
    }
}
