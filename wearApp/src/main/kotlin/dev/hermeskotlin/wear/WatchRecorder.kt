package dev.hermeskotlin.wear

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/** The microphone couldn't be opened: the screen says so in the watch's language. */
class MicrophoneUnavailableException : IllegalStateException("The microphone isn't available.")

/** Where a recording's sound comes from: the microphone, or a test's frames. */
internal interface PcmSource {
    fun start()

    /** Samples read into [frame]: 0 when none yet, negative when the input failed. */
    fun read(frame: ShortArray): Int

    fun close()
}

/**
 * Records what's said for the gateway to transcribe: 16 kHz mono WAV, as the phone records. Talk's recording ([record], [Endpoint.TALK]) goes on until [finish] (send) or
 * until it's cancelled (left, ended); a wake-started one ends a moment after the speaker stops. The end of speech
 * is the phone's rule, simplified: speech must stand clearly above the room's noise measured at the start.
 *
 * The sound goes to a WAV file in [dir] as it's recorded, never held in memory, so a long recording costs no more
 * than a short one; the caller sends it and deletes it.
 */
class WatchRecorder internal constructor(private val dir: () -> File, private val source: (() -> PcmSource)?) {

    constructor(context: Context) : this({ context.cacheDir }, null)

    @Volatile private var finishRequested = false

    /** Talk: the recording as a WAV file, or null when there was nothing to send. [onLevel] gets the input level, 0..1. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(onLevel: (Float) -> Unit): File? = record(Endpoint.TALK, onLevel)

    /**
     * Fork (requirements §3.2, §5): when a recording ends by itself: after [Endpoint.silenceMs] of quiet once speech
     * was heard, after [Endpoint.idleMs] with none, at [Endpoint.maxMs] in all. Talk never does ([Endpoint.TALK]): it
     * ends when it's sent or dropped. A request the wake word started ends on the wake setting's quiet spell, with no
     * length cap.
     */
    data class Endpoint(val silenceMs: Long, val idleMs: Long, val maxMs: Long) {
        companion object {
            val TALK = Endpoint(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)

            fun wake(endSilenceMs: Int) = Endpoint(endSilenceMs.toLong(), dev.hermeskotlin.wearlink.WAKE_IDLE_MS, Long.MAX_VALUE)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(endpoint: Endpoint, onLevel: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val input = source?.invoke() ?: microphone()
        val file = try {
            File.createTempFile(FILE_PREFIX, ".wav", dir())
        } catch (e: Exception) {
            input.close()
            throw e
        }
        var kept = false
        val frame = ShortArray(FRAME)
        val bytes = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
        var pcmBytes = 0L
        var elapsed = 0L
        var noise = -1f
        var heard = false
        var quietSince = -1L
        try {
            BufferedOutputStream(FileOutputStream(file), WRITE_BUFFER).use { out ->
                out.write(ByteArray(HEADER_SIZE))
                try {
                    input.start()
                    while (!finishRequested) {
                        ensureActive()
                        val read = input.read(frame)
                        if (read < 0) throw MicrophoneUnavailableException()
                        if (read == 0) continue
                        bytes.clear()
                        var sum = 0.0
                        for (i in 0 until read) {
                            bytes.putShort(frame[i])
                            sum += frame[i].toDouble() * frame[i]
                        }
                        out.write(bytes.array(), 0, read * 2)
                        pcmBytes += read * 2
                        elapsed += read * 1000L / SAMPLE_RATE
                        val level = min(1.0, sqrt(sum / read) / 256.0 / 42.0).toFloat()
                        onLevel(level)
                        if (elapsed <= CALIBRATION_MS) {
                            noise = if (noise < 0) level else noise + (level - noise) * 0.2f
                            continue
                        }
                        val speechBar = maxOf(SPEECH_LEVEL, noise * 2.5f)
                        if (level >= speechBar) {
                            heard = true
                            quietSince = -1
                        } else if (heard && level < maxOf(SPEECH_LEVEL * 0.8f, noise * 1.6f)) {
                            if (quietSince < 0) quietSince = elapsed
                            if (elapsed - quietSince >= endpoint.silenceMs) break
                        } else {
                            quietSince = -1
                        }
                        if (!heard && elapsed >= endpoint.idleMs) break
                        if (elapsed >= endpoint.maxMs) break
                    }
                } finally {
                    // The microphone goes the moment the recording ends, however it ends.
                    input.close()
                    onLevel(0f)
                }
            }
            // Sent (or ended by its quiet spell) with speech in it, or with more than a moment of sound even quiet;
            // a recording sent before anything was taken has nothing to send.
            kept = heard || finishRequested && pcmBytes > MIN_SEND_BYTES
            if (kept) RandomAccessFile(file, "rw").use { it.write(wavHeader(pcmBytes)) }
            if (kept) file else null
        } finally {
            finishRequested = false
            if (!kept) file.delete()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun microphone(): PcmSource {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicrophoneUnavailableException()
        }
        return object : PcmSource {
            override fun start() = record.startRecording()
            override fun read(frame: ShortArray): Int = record.read(frame, 0, frame.size)
            override fun close() {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    fun finish() {
        finishRequested = true
    }

    companion object {
        const val MIME_TYPE = "audio/wav"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME = SAMPLE_RATE / 50
        private const val CALIBRATION_MS = 300L
        private const val SPEECH_LEVEL = 0.025f
        /** Half a second of sound: less, sent with no speech heard, is nothing to send. */
        private const val MIN_SEND_BYTES = SAMPLE_RATE.toLong()
        private const val HEADER_SIZE = 44
        private const val WRITE_BUFFER = 64 * 1024
        internal const val FILE_PREFIX = "watch-talk-"

        /** The 16 kHz mono 16-bit WAV header for [pcmBytes] of sound (sizes held at the format's 32-bit limit). */
        internal fun wavHeader(pcmBytes: Long): ByteArray {
            val data = minOf(pcmBytes, UInt.MAX_VALUE.toLong() - 36)
            return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt((36 + data).toInt())
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(data.toInt())
            }.array()
        }
    }
}
