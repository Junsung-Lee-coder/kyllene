package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.voice.SpokenAudio

/** Fork (requirements §6): where a voice request was made. */
enum class VoiceDevice { Phone, Watch }

/**
 * Fork customization (requirements §6): which device reads an automatic reply aloud, once [SpeechGate] allows it at
 * all (text sent since its request silences it first):
 *
 * 1. Earphones connected to one device: that device.
 * 2. None: the device the latest voice request was made on ([SpeechGate.latestVoice]).
 *
 * Earphones on both: no order is agreed for that yet, so the reply stays where it was asked ([origin]), as before.
 */
object ReplyOutput {
    fun choose(origin: VoiceDevice, latestVoice: VoiceDevice?, phoneEarphones: Boolean, watchEarphones: Boolean): VoiceDevice = when {
        phoneEarphones && watchEarphones -> origin
        phoneEarphones -> VoiceDevice.Phone
        watchEarphones -> VoiceDevice.Watch
        else -> latestVoice ?: origin
    }
}

/**
 * Fork (requirements §6): the phone reading aloud a reply asked for on the watch, when [ReplyOutput] picks the phone.
 * The phone's own voice media stays one owner's: [free] is false while a call, a voice chat, dictation or a reading
 * has it.
 */
interface PhoneReplyOutput {
    /** Earphones are connected to the phone now. */
    fun earphones(): Boolean

    /** No other voice has the phone's media now. */
    fun free(): Boolean

    /**
     * Plays the reply's clips in turn as [next] makes them (null: no more), through the phone's usual voice route.
     * Stops at once, and plays nothing more, when the earphones it plays through go away (never on to the phone's
     * speaker) or another voice takes the media; cancelling stops it too. [earphonesOnly]: the phone was picked for
     * its earphones, so with none connected once the route is open, nothing plays at all. False when nothing could
     * start.
     */
    suspend fun play(earphonesOnly: Boolean, next: suspend (index: Int) -> SpokenAudio?): Boolean
}
