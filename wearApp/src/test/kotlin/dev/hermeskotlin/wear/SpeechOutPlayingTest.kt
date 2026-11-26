package dev.hermeskotlin.wear

import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §11): [SpeechOut.sounding], the signal the orb follows, through the real player path on
 * Robolectric's MediaPlayer: true only while a clip sounds; and the orb stopping never stops the sound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeechOutPlayingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val players = CopyOnWriteArrayList<ShadowMediaPlayer>()

    @Before fun setUp() {
        // Each clip takes 200 ms to prepare and plays for a second.
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1_000, 200) }
        ShadowMediaPlayer.setCreateListener { _, shadow -> players += shadow }
    }

    @After fun tearDown() = ShadowMediaPlayer.resetStaticState()

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun itPlaysOnlyWhileAClipSoundsNotWhileOneIsOnItsWay() {
        val speech = SpeechOut(context)
        speech.clip("ask", 0, 2, "audio/mpeg", ByteArray(32))
        assertTrue(speech.speaking.value)
        assertFalse(speech.sounding.value, "preparing: no sound yet")
        advance(250)
        assertTrue(speech.sounding.value, "started")
        assertTrue((players.single().state == ShadowMediaPlayer.State.STARTED))
        advance(1_100)
        // The first clip ended and the second is still on its way from the phone: the reply isn't over, but
        // nothing sounds.
        assertTrue(speech.speaking.value)
        assertFalse(speech.sounding.value, "waiting for the next clip")
        speech.clip("ask", 1, 2, "audio/mpeg", ByteArray(32))
        advance(250)
        assertTrue(speech.sounding.value)
        advance(1_100)
        assertFalse(speech.sounding.value)
        assertFalse(speech.speaking.value)
    }

    @Test
    fun stoppingTheReplyStopsItsSignal() {
        val speech = SpeechOut(context)
        speech.clip("ask", 0, 1, "audio/mpeg", ByteArray(32))
        advance(250)
        assertTrue(speech.sounding.value)
        speech.stop()
        assertFalse(speech.sounding.value)
    }

    @Test
    fun theOrbStoppingOffScreenLeavesTheReplyPlaying() {
        val speech = SpeechOut(context)
        speech.clip("ask", 0, 1, "audio/mpeg", ByteArray(32))
        advance(250)
        val media = talkMedia(Listening(), speech.sounding.value)
        // Ambient, then hidden: the orb is still, and the clip plays on.
        for (state in listOf(androidx.lifecycle.Lifecycle.State.STARTED, androidx.lifecycle.Lifecycle.State.CREATED)) {
            assertFalse(orbMoves(media, shownIn(state)))
            assertTrue((players.single().state == ShadowMediaPlayer.State.STARTED))
            assertTrue(speech.sounding.value)
        }
        assertTrue(orbMoves(media, shownIn(androidx.lifecycle.Lifecycle.State.RESUMED)), "back on screen")
        advance(500)
        assertEquals(1, players.size)
        assertTrue((players.single().state == ShadowMediaPlayer.State.STARTED), "the same clip, never restarted or paused")
    }
}
