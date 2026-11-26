package dev.hermeskotlin.wear

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §6): the watch's reply voice and its earphones, through the real [SpeechOut] on Robolectric's
 * MediaPlayer and audio manager: earbuds going away mid-reply silence it (never on to the watch's speaker), and an
 * ask says whether earphones are connected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeechOutEarphonesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10_000, 100) }
    }

    @After fun tearDown() = ShadowMediaPlayer.resetStaticState()

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun unplugged() {
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        advance(10)
    }

    @Test
    fun earbudsGoneMidReplySilenceIt() {
        val speech = SpeechOut(context)
        var lost = 0
        speech.onOutputLost = {
            lost++
            speech.stop()
        }
        speech.clip("ask", 0, 2, "audio/mpeg", ByteArray(32))
        advance(200)
        assertTrue(speech.sounding.value)
        unplugged()
        assertEquals(1, lost)
        assertFalse(speech.speaking.value)
        assertFalse(speech.sounding.value)
    }

    @Test
    fun withNoOneToTellItStillStops() {
        val speech = SpeechOut(context)
        speech.clip("ask", 0, 1, "audio/mpeg", ByteArray(32))
        advance(200)
        unplugged()
        assertFalse(speech.speaking.value)
    }

    @Test
    fun nothingPlayingNothingIsListenedFor() {
        val speech = SpeechOut(context)
        var lost = 0
        speech.onOutputLost = { lost++ }
        unplugged()
        // A reply that played and ended: the warning is no longer listened for.
        speech.clip("ask", 0, 1, "audio/mpeg", ByteArray(32))
        advance(10_500)
        assertFalse(speech.speaking.value)
        unplugged()
        assertEquals(0, lost)
    }

    @Test
    fun anAskSaysWhetherEarphonesAreConnected() {
        val manager = context.getSystemService(AudioManager::class.java)
        fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        shadowOf(manager).setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertFalse(SpeechOut(context).earphones())
        shadowOf(manager).setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertTrue(SpeechOut(context).earphones())
    }
}
