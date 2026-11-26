package dev.hermeskotlin.wearlink

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Fork (requirements §3.2): a [RecordedAsk] down a channel, as [RecordedAsk.write] of [ask] and [audio]'s bytes would
 * make it, written a piece at a time from the file, so a long Talk recording is never all in the watch's memory.
 */
fun writeRecordedAsk(output: OutputStream, ask: WatchAsk, audio: File) {
    output.write(RecordedAsk.write(ask, ByteArray(0)))
    audio.inputStream().use { it.copyTo(output, CHUNK) }
    output.flush()
}

/**
 * Fork (requirements §3.2): a [RecordedAsk] read as it arrives: its header first, then the recording, however long
 * Talk ran (no size ends it: Talk has no time limit). The phone holds the recording as it holds its own Talk's, for
 * the gateway to transcribe. Null when the stream isn't one.
 */
fun readRecordedAsk(stream: InputStream): Pair<WatchAsk, ByteArray>? {
    val input = DataInputStream(stream)
    val prefix = ByteArray(4)
    if (!input.readAll(prefix)) return null
    val size = RecordedAsk.headerSize(prefix)?.takeIf { it <= MAX_HEADER } ?: return null
    val header = ByteArray(size)
    if (!input.readAll(header)) return null
    val ask = RecordedAsk.header(header) ?: return null
    return ask to input.readBytes()
}

private fun DataInputStream.readAll(bytes: ByteArray): Boolean = try {
    readFully(bytes)
    true
} catch (_: EOFException) {
    false
}

private const val CHUNK = 64 * 1024

/** An ask's header is a few hundred bytes of JSON; past this the stream isn't one. */
private const val MAX_HEADER = 64 * 1024
