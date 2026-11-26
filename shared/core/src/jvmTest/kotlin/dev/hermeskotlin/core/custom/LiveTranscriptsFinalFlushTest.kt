package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.cache.KeystoreSealer
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.LiveFragment
import dev.hermeskotlin.core.voice.LiveFragment.Speaker.Assistant
import dev.hermeskotlin.core.voice.LiveFragment.Speaker.User
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (review repair): pieces that couldn't be sealed are sealed and written by the call's last flush, or an empty
 * piece's, not only by a later piece; on the real Room database and sealer, with fixture pieces.
 */
class LiveTranscriptsFinalFlushTest {
    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val keys = InMemoryKeyValueStore()

    /** The real sealer, refusing while [refusing]; counts every attempt. */
    private inner class Flaky : Sealer {
        private val real = KeystoreSealer(keys)
        var refusing = false
        var attempts = 0
        override suspend fun seal(plain: String): ByteArray {
            attempts++
            if (refusing) error("keystore busy")
            return real.seal(plain)
        }

        override suspend fun open(sealed: ByteArray) = real.open(sealed)
    }

    private fun said(speaker: LiveFragment.Speaker, text: String, start: Long) = LiveFragment(speaker, text, start, start + 400)

    private fun test(block: suspend (LiveTranscriptDatabase, Flaky, LiveTranscripts.Recording) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("live-final").toFile()
        val db = liveTranscriptDatabase(dir)
        try {
            val sealer = Flaky()
            var clock = 1_000L
            val store = LiveTranscripts(db.dao(), sealer, { "acct-A" }, { clock++ })
            block(db, sealer, store.begin(home, "s1", LiveTranscripts.Device.Phone))
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    /** What's on disk for [call], opened, in seq order; and that no row holds its text in the clear. */
    private suspend fun LiveTranscriptDatabase.written(call: LiveTranscripts.Recording, sealer: Sealer, texts: List<String>): List<Pair<Int, String?>> {
        val rows = dao().call(call.callId)
        for (row in rows) for (text in texts) assertFalse(text in row.sealed.decodeToString(), "written in the clear: $text")
        return rows.map { it.seq to sealer.open(it.sealed) }
    }

    @Test
    fun theLastPiecesThatCouldntBeSealedAreWrittenInOrderByTheCallsLastFlush() = test { db, sealer, call ->
        assertTrue(call.add(said(User, "Book the ", 100)))
        assertTrue(call.add(said(User, "train", 500)))
        sealer.refusing = true
        assertFalse(call.add(said(Assistant, "Which ", 900)))
        assertFalse(call.add(said(Assistant, "day?", 1_300)))
        assertFalse(call.saved)
        val texts = listOf("Book the ", "train", "Which ", "day?")
        assertEquals(listOf(0 to "Book the ", 1 to "train"), db.written(call, sealer, texts))

        // The call ends: its last flush seals and writes them, after the rest, in order.
        sealer.refusing = false
        assertTrue(call.flush())
        assertTrue(call.saved)
        assertEquals(listOf(0 to "Book the ", 1 to "train", 2 to "Which ", 3 to "day?"), db.written(call, sealer, texts))
        // Flushing again writes nothing twice.
        assertTrue(call.flush())
        assertEquals(4, db.dao().call(call.callId).size)
    }

    @Test
    fun anEmptyPieceFlushesWhatCouldntBeSealed() = test { db, sealer, call ->
        sealer.refusing = true
        assertFalse(call.add(said(User, "Remind me", 100)))
        sealer.refusing = false
        assertTrue(call.add(LiveFragment(User, "", 0, 0)))
        assertEquals(listOf(0 to "Remind me"), db.written(call, sealer, listOf("Remind me")))
    }

    @Test
    fun aSealerThatNeverRecoversLeavesNothingInTheClearAndSaysSoWithoutLooping() = test { db, sealer, call ->
        assertTrue(call.add(said(User, "Kept ", 100)))
        sealer.refusing = true
        assertFalse(call.add(said(User, "secret one", 500)))
        assertFalse(call.add(said(User, "secret two", 900)))
        val before = sealer.attempts
        // Each flush tries the first waiting piece once and stops: no loop, nothing in the clear.
        assertFalse(call.flush())
        assertEquals(before + 1, sealer.attempts)
        assertFalse(call.flush())
        assertEquals(before + 2, sealer.attempts)
        assertFalse(call.saved)
        assertEquals(listOf(0 to "Kept "), db.written(call, sealer, listOf("Kept ", "secret one", "secret two")))
    }
}
