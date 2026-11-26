package dev.hermeskotlin.wearlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork (requirements §3.2): a long watch Talk recording on its way: the watch writes it from its file a piece at a
 * time ([writeRecordedAsk]), the phone reads it as it arrives ([readRecordedAsk]), as [RecordedAsk] has it, with no
 * size dropping it.
 */
class RecordedAskStreamTest {

    /** A channel that gives at most [most] bytes per read, as a stream from another device may. */
    private class Trickle(private val bytes: ByteArray, private val most: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(len, most, bytes.size - at)
            bytes.copyInto(b, off, at, at + n)
            at += n
            return n
        }
    }

    @Test
    fun aRecordingPastTheOldSizeLimitGoesAcrossWhole() {
        // Nine minutes of 16 kHz speech, past the 8 MB the phone used to drop without a word.
        val audio = ByteArray(9 * 60 * 32_000 + 44) { (it * 31).toByte() }
        val file = File.createTempFile("recorded-ask", ".wav").apply { writeBytes(audio) }
        try {
            val ask = WatchAsk("ask-1", chatId = "s1", audioType = "audio/wav")
            val sent = ByteArrayOutputStream().also { writeRecordedAsk(it, ask, file) }.toByteArray()
            assertContentEquals(RecordedAsk.write(ask, audio), sent, "the same stream RecordedAsk.write makes")
            val (read, readAudio) = readRecordedAsk(Trickle(sent, 1_000))!!
            assertEquals(ask, read)
            assertContentEquals(audio, readAudio)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aTruncatedOrForeignStreamReadsAsNull() {
        val whole = RecordedAsk.write(WatchAsk("a", audioType = "audio/wav"), ByteArray(10))
        assertNull(readRecordedAsk(ByteArrayInputStream(ByteArray(0))))
        assertNull(readRecordedAsk(ByteArrayInputStream(whole.copyOfRange(0, 8))))
        assertNull(readRecordedAsk(ByteArrayInputStream(byteArrayOf(0, 0))))
        // A header far too long to be one.
        assertNull(readRecordedAsk(ByteArrayInputStream(byteArrayOf(0x7F, 0, 0, 0, 1, 2, 3))))
        assertNull(readRecordedAsk(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0, 1))))
        // A header that isn't an ask.
        assertNull(readRecordedAsk(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, '{'.code.toByte(), 'x'.code.toByte()))))
    }

    @Test
    fun anAskWithNoAudioYetStillReads() {
        val ask = WatchAsk("a", audioType = "audio/wav")
        val (read, audio) = readRecordedAsk(ByteArrayInputStream(RecordedAsk.write(ask, ByteArray(0))))!!
        assertEquals(ask, read)
        assertEquals(0, audio.size)
    }
}
