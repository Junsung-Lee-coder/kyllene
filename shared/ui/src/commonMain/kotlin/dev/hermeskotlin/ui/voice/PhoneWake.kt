package dev.hermeskotlin.ui.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * Fork customization (requirements §5): what the phone's wake word needs from the voice screens, and what Settings
 * shows of it. The listening itself is the app's (it needs Android's recognizer and a microphone service).
 */

/** Voice phases that leave the microphone and the speaker free: no chat, or Talk waiting at Ready. */
internal val IDLE_PHASES = setOf(VoicePhase.Off, VoicePhase.Ready)

/** Fork: whether any voice on the phone has the microphone or the speaker: chats, dictation and Read aloud alike. */
object VoiceMedia {
    private val owners = MutableStateFlow<Set<Any>>(emptySet())
    private val _busy = MutableStateFlow(false)
    private val probes = MutableStateFlow<Map<Any, () -> Boolean>>(emptyMap())

    /** True while one does: the wake word doesn't listen then. Told as each changes: for watching, not for a check. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Whether one does right now, asked of each: for the check just before the wake word starts anything. */
    fun busyNow(): Boolean = probes.value.values.any { it() }

    internal fun report(owner: Any, busy: Boolean) {
        owners.update { if (busy) it + owner else it - owner }
        _busy.value = owners.value.isNotEmpty()
    }

    internal fun probe(owner: Any, busy: (() -> Boolean)?) {
        probes.update { if (busy == null) it - owner else it + (owner to busy) }
    }

    /** Fork (requirements §6): whether a voice other than [owner] has the media, as each changes. */
    internal fun busyApartFrom(owner: Any): Flow<Boolean> = owners.map { (it - owner).isNotEmpty() }.distinctUntilChanged()
}

/** What the phone's wake word is actually doing, for Settings to say: never what was asked for, only what is. */
enum class PhoneWakeState {
    /** Not listening out of the app. */
    Stopped,

    /** The listening service runs, and the recognizer is listening now or between windows. */
    Listening,

    /** The app is on screen: it listens itself there, and the service waits. */
    InApp,

    /** This phone has no on-device recognizer (or not for the phrases' language): it can't listen out of the app. */
    NoOnDevice,

    /** The microphone isn't allowed. */
    NoMicrophone,

    /** Android didn't let the listening service start. */
    Refused,
}

/** Fork: the phone's wake word as Settings sees it, and the Start and Stop it offers; set by the app. */
object PhoneWake {
    val state = MutableStateFlow(PhoneWakeState.Stopped)

    /** Starts listening out of the app: only from the Settings button, with the app on screen. */
    var start: () -> Unit = {}

    /** Stops it, as the notification's Stop does. */
    var stop: () -> Unit = {}

    /** Whether this phone has an on-device recognizer at all; null until asked. */
    val onDevice = MutableStateFlow<Boolean?>(null)
}
