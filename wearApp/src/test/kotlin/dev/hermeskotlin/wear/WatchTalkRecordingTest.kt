package dev.hermeskotlin.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork (requirements §3.2): the watch's Talk recording, the real [WatchRecorder] on simulated sound (20 ms frames, as
 * the microphone gives them) and through [recordAsk] as the talk screen and the assistant call it: it ends only when
 * it's sent or dropped; a request the wake word started still ends on its quiet spell.
 */
@Suppress("MissingPermission") // No microphone: the frames are simulated.
class WatchTalkRecordingTest {

    private val dir: File = Files.createTempDirectory("watch-talk-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /**
     * 20 ms frames, each at [amplitude] for its index; [onFrame] gets the count read so far as each is read (a person
     * tapping Send after that many).
     */
    private class Frames(private val amplitude: (Int) -> Int, private val onFrame: (Int) -> Unit = {}) : PcmSource {
        var read = 0
        var started = 0
        var closed = 0

        override fun start() {
            started++
        }

        override fun read(frame: ShortArray): Int {
            val a = amplitude(read).toShort()
            for (i in frame.indices) frame[i] = if (i % 2 == 0) a else (-a).toShort()
            read++
            onFrame(read)
            return frame.size
        }

        override fun close() {
            closed++
        }
    }

    private lateinit var recorder: WatchRecorder

    private fun recorder(frames: Frames): WatchRecorder = WatchRecorder({ dir }, { frames }).also { recorder = it }

    private fun speech(fromS: Double, toS: Double): (Int) -> Int = { i -> if (i >= fromS * 50 && i < toS * 50) SPEECH else 0 }

    private fun pcmBytes(file: File): Long = ByteBuffer.wrap(file.readBytes(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun leftover(): List<String> = dir.list().orEmpty().toList()

    @Test
    fun quietPastTheOldEndpointDoesNotEndTalkOnlySendDoes() = runBlocking {
        // A second of speech, then five of quiet (the old rule ended it 1.25 s into the quiet), then Send at 6 s.
        val frames = Frames(speech(0.3, 1.0)) { i -> if (i == 300) recorder.finish() }
        val file = assertNotNull(recorder(frames).record {})
        assertEquals(300, frames.read, "every frame up to Send was taken")
        assertEquals(300L * FRAME_BYTES, pcmBytes(file))
        assertEquals(44 + 300L * FRAME_BYTES, file.length())
        assertContentEquals("RIFF".toByteArray(), file.readBytes().copyOfRange(0, 4))
        assertEquals(1, frames.closed, "the microphone let go")
    }

    @Test
    fun overSixtySecondsGoesOnToSendAndIsWrittenToAFile() = runBlocking {
        // Ninety seconds, speech and long quiet in turn: past the old 60 s cap and every quiet spell.
        val frames = Frames({ i -> if ((i / 500) % 2 == 0) SPEECH else 0 }) { i -> if (i == 4_500) recorder.finish() }
        val file = assertNotNull(recorder(frames).record {})
        assertEquals(4_500, frames.read)
        assertEquals(4_500L * FRAME_BYTES, pcmBytes(file))
        assertEquals(44 + 4_500L * FRAME_BYTES, file.length(), "on disk, as recorded")
        assertEquals(listOf(file.name), leftover())
        assertTrue(file.name.startsWith(WatchRecorder.FILE_PREFIX))
        // The sound itself, in order: the last frame is the 90th second's.
        val bytes = file.readBytes()
        val last = ByteBuffer.wrap(bytes, bytes.size - FRAME_BYTES, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(if ((4_499 / 500) % 2 == 0) SPEECH else 0, last)
    }

    @Test
    fun aQuietRecordingIsSentWithoutTheSpeechDetector() = runBlocking {
        // A voice too quiet ever to count as speech, three seconds of it, then Send.
        val frames = Frames({ 0 }) { i -> if (i == 150) recorder.finish() }
        val file = assertNotNull(recorder(frames).record {})
        assertEquals(150L * FRAME_BYTES, pcmBytes(file))
    }

    @Test
    fun anEmptySendSendsNothingAndLeavesNoFile() = runBlocking {
        // Send a fifth of a second in, nothing said.
        val frames = Frames({ 0 }) { i -> if (i == 10) recorder.finish() }
        assertNull(recorder(frames).record {})
        assertTrue(leftover().isEmpty())
        assertEquals(1, frames.closed)
    }

    @Test
    fun aSendBeforeTheMicrophoneOpensEndsItAtOnce() = runBlocking {
        // Tapped while the recording starts: it ends at once, with nothing to send, and the next one starts afresh.
        val frames = Frames({ 0 }) { i -> if (i == 100) recorder.finish() }
        val r = recorder(frames)
        r.finish()
        assertNull(r.record {})
        assertEquals(0, frames.read)
        assertNotNull(r.record {})
        assertEquals(100, frames.read)
    }

    @Test
    fun droppedItDeletesTheRecordingAndLetsTheMicrophoneGo() = runBlocking {
        val going = CompletableDeferred<Unit>()
        val frames = Frames({ SPEECH }) { i ->
            if (i == 200) going.complete(Unit)
            Thread.sleep(1)
        }
        val r = recorder(frames)
        val job = launch(Dispatchers.Default) { r.record {} }
        going.await()
        job.cancelAndJoin()
        assertEquals(1, frames.closed)
        assertTrue(leftover().isEmpty(), "nothing kept")
    }

    @Test
    fun theWakeWordsRecordingStillEndsOnItsQuietSpell() = runBlocking {
        // Speech for a second, then quiet with no Send: it ends 2 s into the quiet (frame 75 + 100).
        val frames = Frames(speech(0.5, 1.5))
        val file = assertNotNull(withTimeout(10_000) { recorder(frames).record(WatchRecorder.Endpoint.wake(2_000)) {} })
        assertTrue(frames.read in 170..180, "ended on the quiet spell, at ${frames.read} frames")
        assertEquals(frames.read.toLong() * FRAME_BYTES, pcmBytes(file))
        assertEquals(1, frames.closed)
        // And with nothing said, it gives up after its idle wait, keeping nothing.
        val quiet = Frames({ 0 })
        assertNull(withTimeout(10_000) { WatchRecorder({ dir }, { quiet }).record(WatchRecorder.Endpoint.wake(2_000)) {} })
        assertEquals(400, quiet.read)
        assertEquals(listOf(file.name), leftover())
    }

    @Test
    fun aFailingMicrophoneEndsItAndKeepsNothing() = runBlocking {
        val failing = object : PcmSource {
            var closed = 0
            override fun start() = Unit
            override fun read(frame: ShortArray): Int = -6
            override fun close() {
                closed++
            }
        }
        assertFailsWith<MicrophoneUnavailableException> { WatchRecorder({ dir }, { failing }).record {} }
        assertEquals(1, failing.closed)
        assertTrue(leftover().isEmpty())
    }

    // As the talk screen and the assistant call it: recordAsk with recorder::record, sent with the file or deleted.

    private val listening = MutableStateFlow(Listening(waiting = true))
    private val asked = mutableListOf<File>()

    private suspend fun talk(frames: Frames, generation: Long = 1, current: Long = 1) {
        val r = recorder(frames)
        recordAsk(listening, generation, { current }, { onLevel -> r.record(onLevel) }, ask = { asked += it }, drop = { it.delete() })
    }

    @Test
    fun talkSendsTheWholeRecordingOnSendAndThenWaits() = runBlocking {
        val seen = mutableListOf<Boolean>()
        val frames = Frames(speech(0.5, 2.0)) { i ->
            if (i == 3_250) seen += listening.value.recording
            if (i == 3_500) recorder.finish()
        }
        talk(frames)
        assertEquals(listOf(true), seen, "still recording 65 s in")
        val file = asked.single()
        assertEquals(3_500L * FRAME_BYTES, pcmBytes(file))
        // Sent: the screen is idle, the microphone shut, and nothing records again on its own.
        assertEquals(Listening(), listening.value)
        assertEquals(1, frames.started)
        assertEquals(1, frames.closed)
    }

    @Test
    fun talkSentEmptySaysNothingWasHeardAndSendsNothing() = runBlocking {
        talk(Frames({ 0 }) { i -> if (i == 5) recorder.finish() })
        assertEquals(NOTHING_HEARD, listening.value.error)
        assertTrue(asked.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun talkLeftMidwayDropsTheRecording() = runBlocking {
        val going = CompletableDeferred<Unit>()
        val frames = Frames({ SPEECH }) { i ->
            if (i == 100) going.complete(Unit)
            Thread.sleep(1)
        }
        val job = async(Dispatchers.Default) { talk(frames) }
        going.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(Listening(), listening.value)
        assertTrue(asked.isEmpty())
        assertEquals(1, frames.closed)
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun talkAfterAProfileChangeDeletesTheRecording() = runBlocking {
        talk(Frames(speech(0.5, 1.5)) { i -> if (i == 100) recorder.finish() }, generation = 1, current = 2)
        assertEquals(PROFILE_CHANGED, listening.value.error)
        assertTrue(asked.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    private companion object {
        /** Well above the speech bar (a level of about 0.2). */
        const val SPEECH = 2_000
        const val FRAME_BYTES = 640
    }
}
