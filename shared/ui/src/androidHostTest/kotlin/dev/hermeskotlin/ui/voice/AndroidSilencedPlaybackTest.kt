package dev.hermeskotlin.ui.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour of the real Android player's playback after the output a chat plays through goes away
 * (AndroidSpeechPlayer's mute): no let-go hold, stream, clip or late "prepared" callback makes sound until the
 * silenced chats have closed and their playback has ended; a new chat then plays as usual.
 */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSilencedPlaybackTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private val clips = CopyOnWriteArrayList<ShadowMediaPlayer>()
    private val wav = SpokenAudio(ByteArray(64), "audio/wav")

    @Before fun setUp() {
        shadowOf(manager).setOutputDevices(listOf(speaker, earbuds))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        // Every clip prepares only when the test says so, and lasts long enough not to finish by itself.
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(600_000, -1) }
        ShadowMediaPlayer.setCreateListener { _, shadow -> clips += shadow }
    }

    @After fun tearDown() = ShadowMediaPlayer.resetStaticState()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun until(what: String, condition: () -> Boolean) {
        repeat(500) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("Timed out waiting for $what")
    }

    /** The stream the player is playing now: its own field, read here to see the real track's state. */
    private fun AndroidSpeechPlayer.track(): AudioTrack? =
        AndroidSpeechPlayer::class.java.getDeclaredField("track").apply { isAccessible = true }.get(this) as AudioTrack?

    private fun launch(block: suspend () -> Unit): Job = GlobalScope.launch(Dispatchers.Unconfined) { block() }

    private fun loseTheEarbuds() {
        shadowOf(manager).removeOutputDevice(earbuds, true)
        idle()
    }

    @Test
    fun aHeldStreamLetGoAfterTheEarbudsWentStaysSilentAndItsRestIsntPlayed() {
        val player = AndroidSpeechPlayer(context)
        var lost = 0
        val close = player.chainedChatAudio(talkOver = true) { lost++ }
        // Held while someone talks over the reply.
        player.hold(true)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).apply { trySend(ByteArray(3_200)) }
        val stream = launch { player.playPcm(16_000, 1, chunks) {} }
        until("the stream's track") { player.track() != null }
        val track = player.track()!!
        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)

        loseTheEarbuds()
        assertEquals(1, lost)
        player.hold(false)

        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState, "let go, it stays silent")
        // The rest of the reply ends it without being played out.
        chunks.trySend(ByteArray(3_200))
        until("the stream to end") { stream.isCompleted }
        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        close()
    }

    @Test
    fun aStreamThatStartsAfterSilenceIsntPlayed() {
        val player = AndroidSpeechPlayer(context)
        val close = player.chainedChatAudio(talkOver = true) {}
        loseTheEarbuds()

        val chunks = Channel<ByteArray>(Channel.UNLIMITED).apply { trySend(ByteArray(3_200)) }
        val stream = launch { player.playPcm(16_000, 1, chunks) {} }

        until("the stream to end") { stream.isCompleted }
        assertNull(player.track())
        // Nothing of it was taken to play.
        assertTrue(chunks.tryReceive().isSuccess)
        close()
    }

    @Test
    fun aClipThatGetsReadyAfterSilenceNeverStartsAndEndsQuietly() {
        val player = AndroidSpeechPlayer(context)
        val close = player.chainedChatAudio(talkOver = false) {}
        val clip = launch { player.play(wav) }
        until("the clip to prepare") { clips.isNotEmpty() && clips.last().state == ShadowMediaPlayer.State.PREPARING }
        val shadow = clips.last()

        loseTheEarbuds()
        shadow.invokePreparedListener()
        idle()

        until("the clip to end") { clip.isCompleted }
        assertNotEquals(ShadowMediaPlayer.State.STARTED, shadow.state)
        close()
    }

    @Test
    fun aHeldClipLetGoAfterSilenceStaysSilent() {
        val player = AndroidSpeechPlayer(context)
        val close = player.chainedChatAudio(talkOver = true) {}
        player.hold(true)
        val clip = launch { player.play(wav) }
        until("the clip to prepare") { clips.isNotEmpty() && clips.last().state == ShadowMediaPlayer.State.PREPARING }
        val shadow = clips.last()
        shadow.invokePreparedListener()
        idle()
        assertNotEquals(ShadowMediaPlayer.State.STARTED, shadow.state, "held: ready but waiting")

        loseTheEarbuds()
        player.hold(false)
        idle()

        assertNotEquals(ShadowMediaPlayer.State.STARTED, shadow.state, "let go, it stays silent")
        until("the clip to end") { clip.isCompleted }
        close()
    }

    @Test
    fun theMuteOutlastsTheChatWhileItsPlaybackRunsAndANewChatThenPlays() {
        val player = AndroidSpeechPlayer(context)
        val old = player.chainedChatAudio(talkOver = true) {}
        player.hold(true)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).apply { trySend(ByteArray(3_200)) }
        val stream = launch { player.playPcm(16_000, 1, chunks) {} }
        until("the stream's track") { player.track() != null }
        val oldTrack = player.track()!!
        loseTheEarbuds()

        // The chat closes while its stream still runs: its late let-go stays silent.
        old()
        player.hold(false)
        assertNotEquals(AudioTrack.PLAYSTATE_PLAYING, oldTrack.playState)
        // A clip started meanwhile is silent too, ending quietly when it gets ready.
        val early = launch { player.play(wav) }
        until("the early clip to end") { early.isCompleted }
        assertTrue(clips.none { it.state == ShadowMediaPlayer.State.STARTED })

        // The old stream ends (its chat cancelled); nothing silenced can sound now, so the mute is let go.
        stream.cancel()
        until("the old stream to end") { stream.isCompleted }

        // A new chat, earbuds back in: its reply plays as usual.
        shadowOf(manager).addOutputDevice(earbuds, true)
        idle()
        val close = player.chainedChatAudio(talkOver = true) {}
        // The early clip was never made (silenced at once): the new chat's is the first.
        val made = clips.size
        val clip = launch { player.play(wav) }
        until("the new clip to prepare") { clips.size > made && clips.last().state == ShadowMediaPlayer.State.PREPARING }
        clips.last().invokePreparedListener()
        idle()
        assertEquals(ShadowMediaPlayer.State.STARTED, clips.last().state)
        clip.cancel()
        close()
    }
}
