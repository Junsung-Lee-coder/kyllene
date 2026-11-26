package dev.hermeskotlin.wear

import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fork (requirements §3.2): the talk screen's window stays on while Talk records, as the talk screen runs it (the
 * real [WatchRecorder] through [recordAsk] in the activity's scope, on simulated sound), and while a live call runs;
 * it times out again once neither does. Robolectric's activity and window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@Suppress("MissingPermission") // No microphone: the frames are simulated.
class TalkKeepsScreenOnTest {

    private val dir: File = Files.createTempDirectory("talk-screen-test").toFile()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val call = MutableStateFlow<WatchCallState?>(null)
    private val listening = MutableStateFlow(Listening())

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screenOn(): Boolean = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    /** Sound held at its first frame until [release]; Send after [sendAfter] frames, if any. */
    private class HeldFrames(private val release: CountDownLatch, private val send: () -> Unit, private val sendAfter: Int?) : PcmSource {
        var read = 0
        var closed = 0
        override fun start() = Unit
        override fun read(frame: ShortArray): Int {
            if (read == 1) release.await(10, TimeUnit.SECONDS)
            frame.fill(0)
            read++
            if (read == sendAfter) send()
            Thread.sleep(0, 100_000)
            return frame.size
        }
        override fun close() {
            closed++
        }
    }

    private fun waitFor(job: Job) {
        repeat(2_000) {
            idle()
            if (job.isCompleted) return
            Thread.sleep(5)
        }
        fail("the recording didn't end")
    }

    private fun talk(release: CountDownLatch, sendAfter: Int?, asked: MutableList<File>): Pair<Job, HeldFrames> {
        lateinit var recorder: WatchRecorder
        val frames = HeldFrames(release, { recorder.finish() }, sendAfter)
        recorder = WatchRecorder({ dir }, { frames })
        // As MainActivity.record() runs it.
        listening.value = Listening(recording = true)
        val job = activity.lifecycleScope.launch {
            recordAsk(listening, 1, { 1 }, { onLevel -> recorder.record(onLevel) }, drop = { it.delete() }) { asked += it }
        }
        return job to frames
    }

    @Test
    fun theScreenStaysOnWhileTalkRecordsAndTimesOutOnceItsSent() {
        activity.keepScreenOnWhile(call, listening)
        idle()
        assertFalse(screenOn(), "idle: the screen times out as usual")
        val release = CountDownLatch(1)
        val asked = mutableListOf<File>()
        val (job, frames) = talk(release, sendAfter = 3_000, asked)
        idle()
        assertTrue(screenOn(), "recording: the screen stays on")
        // A minute of sound, then Send.
        release.countDown()
        waitFor(job)
        assertEquals(3_000, frames.read)
        assertEquals(1, asked.size, "sent")
        assertEquals(1, frames.closed)
        assertFalse(screenOn(), "sent: the screen times out again")
    }

    @Test
    fun aRecordingLeftLetsTheScreenTimeOutAndKeepsNothing() {
        activity.keepScreenOnWhile(call, listening)
        val release = CountDownLatch(1)
        val asked = mutableListOf<File>()
        val (job, frames) = talk(release, sendAfter = null, asked)
        idle()
        assertTrue(screenOn())
        // Left (as onStop cancels it).
        job.cancel()
        release.countDown()
        waitFor(job)
        assertEquals(Listening(), listening.value)
        assertFalse(screenOn())
        assertTrue(asked.isEmpty())
        assertEquals(1, frames.closed)
        assertTrue(dir.list().orEmpty().isEmpty(), "the recording is deleted")
    }

    @Test
    fun aLiveCallAndARecordingEachKeepItOnUntilBothEnd() {
        activity.keepScreenOnWhile(call, listening)
        call.value = WatchCallState("call", CallStage.Live)
        idle()
        assertTrue(screenOn(), "a live call keeps it on, as before")
        listening.value = Listening(recording = true)
        idle()
        assertTrue(screenOn())
        listening.value = Listening()
        idle()
        assertTrue(screenOn(), "the call still runs")
        call.value = WatchCallState("call", CallStage.Ended)
        idle()
        assertFalse(screenOn())
        // And the other way round: a call ending while Talk records leaves it on until the recording ends.
        call.value = WatchCallState("call2", CallStage.Starting)
        listening.value = Listening(recording = true)
        idle()
        call.value = null
        idle()
        assertTrue(screenOn(), "still recording")
        listening.value = Listening(error = NOTHING_HEARD)
        idle()
        assertFalse(screenOn())
    }
}
