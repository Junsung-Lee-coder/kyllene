package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.custom.RetainedCopies
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour on the real Room database and AES sealer the apps use: copies outlive the database being closed
 * and opened again, a row too big for one database row comes back whole, the list has no cap, and signed-out
 * copies stay on disk where the gateway's reads don't reach them.
 */
class RetainedCopiesDatabaseTest {
    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val keys = InMemoryKeyValueStore()

    private fun row(id: Long, text: String = "m$id") =
        SessionMessage(id = id, role = if (id % 2 == 1L) "user" else "assistant", content = JsonPrimitive(text))

    @Test
    fun copiesOutliveTheDatabaseClosingAndRowsComeBackWhole(): Unit = runBlocking {
        val dir = Files.createTempDirectory("retained").toFile()
        try {
            val huge = "tool output ".repeat(250_000) // about 3 MB, past Android's 2 MB row window
            val first = offlineDatabase(dir)
            OfflineCache(first.dao(), KeystoreSealer(keys), EmptyCoroutineContext) { 1_000L }.apply {
                assertNotNull(saveTranscript(home, null, "c", (1L..300L).map { row(it) }, "c"))
                assertNotNull(saveTranscript(home, null, "c", listOf(row(299), row(300, huge), row(301)), "c"))
                assertNotNull(saveList(home, null, SessionListFilter.Recent, (1..150).map { SessionSummary("s$it") }))
                assertNotNull(saveList(home, null, SessionListFilter.Recent, listOf(SessionSummary("new"))))
            }
            first.close()

            val again = offlineDatabase(dir)
            try {
                val dao = again.dao()
                val cache = OfflineCache(dao, KeystoreSealer(keys), EmptyCoroutineContext) { 2_000L }
                val rows = assertNotNull(cache.savedTranscript(home, null, "c")).rows
                assertEquals((1L..301L).toList(), rows.map { it.id })
                assertEquals(huge, rows[299].text)
                assertTrue(dao.messages(home.value, "", "c").all { it.row.size <= OfflineCache.MAX_ROW_BYTES })
                assertEquals(151, cache.savedList(home, null, SessionListFilter.Recent)?.sessions?.size)

                assertNotNull(cache.detachGateway(home))
                assertNull(cache.savedTranscript(home, null, "c"))
                assertNull(cache.savedList(home, null, SessionListFilter.Recent))
            } finally {
                again.close()
            }

            // Still on disk after the sign-out, under its detached key.
            val last = offlineDatabase(dir)
            try {
                val dao = last.dao()
                val detached = dao.list(home.value, "", SessionListFilter.Recent.name)
                assertTrue(detached.isEmpty())
                val cache = OfflineCache(dao, KeystoreSealer(keys), EmptyCoroutineContext) { 3_000L }
                assertNull(cache.savedTranscript(home, null, "c"))
                assertTrue(dao.messages(home.value, "", "c").isEmpty())
                val key = dao.transcriptGateways().single()
                assertTrue(RetainedCopies.isDetached(key, home.value), key)
                assertEquals(301, RetainedCopies.join(dao.messages(key, "", "c").map { KeystoreSealer(keys).open(it.row)!! })?.size)
                assertEquals(151, dao.list(key, "", SessionListFilter.Recent.name).size)
            } finally {
                last.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun <T> withCache(dir: java.io.File, at: Long, block: suspend (OfflineCache, OfflineDao) -> T): T {
        val database = offlineDatabase(dir)
        try {
            return block(OfflineCache(database.dao(), KeystoreSealer(keys), EmptyCoroutineContext) { at }, database.dao())
        } finally {
            database.close()
        }
    }

    @Test
    fun copiesStayWithTheirAccountAcrossRestarts(): Unit = runBlocking {
        val dir = Files.createTempDirectory("owners").toFile()
        val alice = RetainedCopies.owner("password", "alice")
        val bob = RetainedCopies.owner("password", "bob")
        try {
            withCache(dir, 1_000L) { cache, _ ->
                assertTrue(cache.bindOwner(home, alice))
                assertNotNull(cache.saveTranscript(home, null, "c", listOf(row(1, "alice's")), "c"))
            }
            // Alice's session expires; Bob signs in after a restart.
            withCache(dir, 2_000L) { cache, _ ->
                assertTrue(cache.bindOwner(home, bob))
                assertNull(cache.savedTranscript(home, null, "c"))
                assertNotNull(cache.saveTranscript(home, null, "c", listOf(row(7, "bob's")), "c"))
            }
            // Bob signs out; Alice signs in after another restart and has hers, not his.
            withCache(dir, 3_000L) { cache, _ -> assertNotNull(cache.detachGateway(home)) }
            withCache(dir, 4_000L) { cache, dao ->
                assertTrue(cache.bindOwner(home, alice))
                assertEquals(listOf("alice's"), cache.savedTranscript(home, null, "c")?.rows?.map { it.text })
                assertEquals(1, dao.transcriptGateways().count { RetainedCopies.isDetached(it, home.value) })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aCorruptedSealedPieceThenASaveKeepsTheRestAndTheOriginal(): Unit = runBlocking {
        val dir = Files.createTempDirectory("corrupt").toFile()
        try {
            val huge = "z".repeat(OfflineCache.MAX_PIECE_CHARS * 2)
            val stored = withCache(dir, 1_000L) { cache, dao ->
                cache.saveTranscript(home, null, "c", listOf(row(1), row(2, huge), row(3)), "c")
                val transcript = dao.transcript(home.value, "", "c")!!
                // Flip one byte of the second piece: AES-GCM refuses it.
                dao.replaceTranscript(transcript, dao.messages(home.value, "", "c").map {
                    if (it.position != 2) it else SavedMessageRow(it.gateway, it.profile, it.sessionId, it.position,
                        it.row.copyOf().also { bytes -> bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte() })
                })
                dao.messages(home.value, "", "c").size
            }
            assertTrue(stored > 3, "row 2 is in pieces: $stored")
            withCache(dir, 2_000L) { cache, dao ->
                assertNull(cache.savedTranscript(home, null, "c"))
                assertNotNull(cache.saveTranscript(home, null, "c", listOf(row(4)), "c"))
                assertEquals(listOf(1L, 3L, 4L), cache.savedTranscript(home, null, "c")?.rows?.map { it.id })
                val aside = dao.transcriptGateways().single { RetainedCopies.isPutAway(it, home.value) }
                assertEquals(stored, dao.messages(aside, "", "c").size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
