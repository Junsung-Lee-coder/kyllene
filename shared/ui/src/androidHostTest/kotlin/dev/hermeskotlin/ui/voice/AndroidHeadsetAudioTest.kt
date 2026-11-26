package dev.hermeskotlin.ui.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import dev.hermeskotlin.core.voice.ChatAudioRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour of the real Android player ([AndroidSpeechPlayer]) on Robolectric's audio manager: earphones with a
 * microphone take the chained chat, and the output a chat plays through going away silences it on every route, the
 * phone's speaker never taking over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidHeadsetAudioTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aHeadsetWithAMicrophoneTakesTheChatWithTalkOverOff() {
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(manager).setOutputDevices(listOf(speaker, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), sco))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker, sco))
        manager.mode = AudioManager.MODE_NORMAL
        val route = ChatAudioRoute()
        var lost = 0

        val close = AndroidSpeechPlayer(context, route).chainedChatAudio(talkOver = false) { lost++ }

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(sco, manager.communicationDevice)
        // The recorder listens on call audio: the headset's microphone.
        assertTrue(route.callAudio())
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        assertEquals(0, lost)
    }

    @Test
    fun aHeadsetThatGoesAwayIsLostAndTheSpeakerIsntRouted() {
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadowOf(manager).setOutputDevices(listOf(speaker, sco))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker, sco))
        manager.mode = AudioManager.MODE_NORMAL
        var lost = 0
        val close = AndroidSpeechPlayer(context).chainedChatAudio(talkOver = true) { lost++ }

        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        shadowOf(manager).removeOutputDevice(sco, true)
        idle()

        assertEquals(1, lost)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        close()
    }

    /** The review's case: music-only earbuds on the usual route, talk-over on. Stock gave the speaker call routing. */
    @Test
    fun musicOnlyEarbudsGoneAreLostAndTheSpeakerIsntRouted() {
        val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        shadowOf(manager).setOutputDevices(listOf(speaker, earbuds))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        var lost = 0
        val close = AndroidSpeechPlayer(context).chainedChatAudio(talkOver = true) { lost++ }
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)

        shadowOf(manager).removeOutputDevice(earbuds, true)
        idle()

        assertEquals(1, lost)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        // Nothing routes it to the speaker afterwards either, until the chat has closed.
        shadowOf(manager).addOutputDevice(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), true)
        idle()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertEquals(1, lost)
        close()
    }

    @Test
    fun wiredHeadphonesUnpluggedWithTalkOverOffAreLost() {
        val headphones = device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        shadowOf(manager).setOutputDevices(listOf(speaker, headphones))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        var lost = 0
        val close = AndroidSpeechPlayer(context).chainedChatAudio(talkOver = false) { lost++ }

        shadowOf(manager).removeOutputDevice(headphones, true)
        idle()

        assertEquals(1, lost)
        close()
    }

    /** Android's warning that audio is about to move to the speaker can come first: it silences the chat on its own. */
    @Test
    fun androidsBecomingNoisyWarningSilencesTheChat() {
        shadowOf(manager).setOutputDevices(listOf(speaker, device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        var lost = 0
        val close = AndroidSpeechPlayer(context).chainedChatAudio(talkOver = true) { lost++ }

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()

        assertEquals(1, lost)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close()
        // Closed: the receiver is gone, and a later warning tells no one.
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(1, lost)
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun olderAndroidBluetoothEarbudsGoneAreLostAndTheSpeakerphoneIsntTurnedOn() {
        val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        shadowOf(manager).setOutputDevices(listOf(speaker, earbuds))
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
        var lost = 0
        val close = AndroidSpeechPlayer(context).chainedChatAudio(talkOver = true) { lost++ }

        shadowOf(manager).removeOutputDevice(earbuds, true)
        idle()

        assertEquals(1, lost)
        assertEquals(false, manager.isSpeakerphoneOn)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close()
    }
}
