package dev.hermeskotlin.core.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.custom.HeadsetAudio
import dev.hermeskotlin.core.custom.HeadsetAudio.LiveOutput

/**
 * Fork customization: where a live call's audio goes ([HeadsetAudio.liveOutput]), and its hang-up when that output goes
 * ([HeadsetAudio.liveLost]).
 *
 * - [LiveOutput.Headset] and [LiveOutput.Speaker] are stock: `MODE_IN_COMMUNICATION` with the headset, or the phone's
 *   speaker, as the communication device (on Android 12+; the speakerphone before).
 * - [LiveOutput.Earphones] is the fork's: earphones that aren't a communication device (music-only Bluetooth earbuds,
 *   a hearing aid; Android lists only `getAvailableCommunicationDevices()` for call routing,
 *   https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).
 *   The audio mode and the communication device are left alone, and the call's voice plays with [playout]'s media
 *   usage, which Android routes where media goes: the earphones. The microphone is the phone's own; the chat says so.
 *
 * Once routed to earphones or a headset, losing them ([onLost], on the main thread) hangs the call up rather than
 * letting Android move it to the speaker: Android's device callback, and for earphones its
 * `ACTION_AUDIO_BECOMING_NOISY` warning, sent "when an A2DP audio sink is disconnected, and the audio system is about to
 * automatically switch audio route to the speaker".
 */
class LiveAudioRoute(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var lost: (() -> Unit)? = null

    /** Where the call's audio was put; null before [route] and after [unroute]. */
    var output: LiveOutput? = null
        private set

    private val watch = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = check()
    }

    private val becomingNoisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && output == LiveOutput.Earphones) lose()
        }
    }

    /** Routes the call, before its audio starts; [onLost] hangs it up. */
    @Suppress("DEPRECATION")
    fun route(onLost: () -> Unit): LiveOutput {
        lost = onLost
        val ports = HeadsetPorts.of(audioManager)
        val to = HeadsetAudio.liveOutput(callHeadset(), ports)
        output = to
        if (to != LiveOutput.Earphones) {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val device = if (to == LiveOutput.Headset) devices.firstOrNull { it.type in CALL_HEADSETS }
                else devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                device?.let(audioManager::setCommunicationDevice)
            } else {
                audioManager.isSpeakerphoneOn = to == LiveOutput.Speaker
            }
        }
        if (to != LiveOutput.Speaker) {
            runCatching { audioManager.registerAudioDeviceCallback(watch, Handler(Looper.getMainLooper())) }
        }
        if (to == LiveOutput.Earphones) {
            runCatching {
                ContextCompat.registerReceiver(appContext, becomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
            }
        }
        return to
    }

    /**
     * How the call's voice plays: as media for [LiveOutput.Earphones], so it goes where media goes; null keeps
     * WebRTC's own voice-communication usage, which follows the call routing.
     */
    fun playout(): AudioAttributes? = if (output == LiveOutput.Earphones) {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    } else null

    /** Puts the routing back as it was; the call has ended. */
    @Suppress("DEPRECATION")
    fun unroute() {
        val was = output ?: return
        output = null
        lost = null
        if (was != LiveOutput.Speaker) runCatching { audioManager.unregisterAudioDeviceCallback(watch) }
        if (was == LiveOutput.Earphones) {
            runCatching { appContext.unregisterReceiver(becomingNoisy) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        else audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }

    private fun check() {
        val was = output ?: return
        if (HeadsetAudio.liveLost(was, callHeadset(), HeadsetPorts.of(audioManager))) lose()
    }

    private fun lose() {
        val then = lost ?: return
        lost = null
        then()
    }

    /** A headset the call can be routed to, as stock picks one. */
    @Suppress("DEPRECATION")
    private fun callHeadset(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { audioManager.availableCommunicationDevices.any { it.type in CALL_HEADSETS } }.getOrDefault(false)
    } else audioManager.isWiredHeadsetOn || audioManager.isBluetoothScoOn

    private companion object {
        /** Stock's: what a live call takes as a headset. */
        val CALL_HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
