package dev.hermeskotlin.ui.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import dev.hermeskotlin.core.custom.HeadsetAudio.LiveOutput
import dev.hermeskotlin.core.voice.LiveAudioRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fork: a live call's route ([LiveAudioRoute], which `AndroidLiveCall` runs) on Robolectric's audio manager. Headphones
 * first, music-only earbuds included; losing them hangs the call up rather than moving it to the speaker. This shows the
 * route the app asks Android for, not where a real phone's audio ends up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidLiveRouteTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun musicOnlyEarbudsTakeTheCallAsMediaNotTheSpeaker() {
        val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        shadowOf(manager).setOutputDevices(listOf(speaker, earbuds))
        // A2DP isn't a communication device: stock picked the speaker here.
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val route = LiveAudioRoute(context)
        var lost = 0

        assertEquals(LiveOutput.Earphones, route.route { lost++ })

        // No call routing, no speaker: the voice plays as media, where Android plays media.
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        assertEquals(AudioAttributes.USAGE_MEDIA, route.playout()?.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, route.playout()?.contentType)

        // The earbuds go: the call hangs up, once, and nothing is routed to the speaker.
        shadowOf(manager).removeOutputDevice(earbuds, true)
        idle()
        assertEquals(1, lost)
        assertNull(manager.communicationDevice)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        route.unroute()
        assertNull(route.output)
    }

    @Test
    fun androidsBecomingNoisyWarningHangsAnEarphoneCallUpFirst() {
        shadowOf(manager).setOutputDevices(listOf(speaker, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        val route = LiveAudioRoute(context)
        var lost = 0
        route.route { lost++ }

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(1, lost)
        // Told once only, even as the device callback follows.
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(1, lost)

        route.unroute()

        // A call on earbuds that ends normally: the warning afterwards tells no one.
        val again = LiveAudioRoute(context)
        var lostAgain = 0
        assertEquals(LiveOutput.Earphones, again.route { lostAgain++ })
        again.unroute()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(0, lostAgain)
    }

    @Test
    fun aHeadsetWithAMicrophoneTakesTheCallAsStockDidAndLosingItHangsUp() {
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(manager).setOutputDevices(listOf(speaker, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), sco))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker, sco))
        manager.mode = AudioManager.MODE_NORMAL
        val route = LiveAudioRoute(context)
        var lost = 0

        assertEquals(LiveOutput.Headset, route.route { lost++ })
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(sco, manager.communicationDevice)
        // WebRTC keeps its own voice-communication usage, which follows the call routing.
        assertNull(route.playout())

        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        shadowOf(manager).removeOutputDevice(sco, true)
        idle()
        assertEquals(1, lost)
        route.unroute()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    @Test
    fun withNothingPrivateTheCallIsOnTheSpeakerAsStock() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val route = LiveAudioRoute(context)

        assertEquals(LiveOutput.Speaker, route.route { error("nothing to lose") })
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)
        assertNull(route.playout())
        route.unroute()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun beforeAndroid12MusicOnlyEarbudsKeepTheSpeakerphoneOff() {
        val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        shadowOf(manager).setOutputDevices(listOf(speaker, earbuds))
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
        val route = LiveAudioRoute(context)
        var lost = 0

        assertEquals(LiveOutput.Earphones, route.route { lost++ })
        // Stock turned the speakerphone on here.
        assertEquals(false, manager.isSpeakerphoneOn)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertEquals(AudioAttributes.USAGE_MEDIA, route.playout()?.usage)

        shadowOf(manager).removeOutputDevice(earbuds, true)
        idle()
        assertEquals(1, lost)
        assertEquals(false, manager.isSpeakerphoneOn)
        route.unroute()
    }
}
