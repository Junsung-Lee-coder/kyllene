package dev.hermeskotlin.core.voice

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dev.hermeskotlin.core.custom.HeadsetAudio

/**
 * Fork customization: what's connected to the phone, as [HeadsetAudio] reads it. Shared by the chained chat's player
 * ([AndroidSpeechPlayer]) and the live call's route ([LiveAudioRoute]), so both tell earphones apart the same way.
 */
internal object HeadsetPorts {
    /** Outputs that aren't the phone itself: with one in, a chained chat leaves the route alone. */
    @SuppressLint("InlinedApi") // A device only reports the types its Android knows.
    val OWN_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    /** Communication devices with a microphone a chat can talk through, as a live call picks them. */
    @SuppressLint("InlinedApi")
    val HEADSETS_WITH_MIC = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    /**
     * Outputs that are earphones, whose microphone the chat should use. A wired or USB headset isn't among them: its
     * microphone is the phone's usual input anyway.
     */
    @SuppressLint("InlinedApi")
    val EARPHONES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun of(audioManager: AudioManager): HeadsetAudio.Ports {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }.getOrDefault(emptyList())
        // Before Android 12 there's no communication device to pick: a Bluetooth headset's microphone isn't reachable.
        val talkable = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { audioManager.availableCommunicationDevices.map { it.type } }.getOrDefault(emptyList())
        } else emptyList()
        return HeadsetAudio.Ports(
            headsetWithMic = talkable.any { it in HEADSETS_WITH_MIC },
            privateOutput = outputs.any { it in OWN_OUTPUTS },
            earphonesWithoutUsableMic = outputs.any { it in EARPHONES },
        )
    }
}
