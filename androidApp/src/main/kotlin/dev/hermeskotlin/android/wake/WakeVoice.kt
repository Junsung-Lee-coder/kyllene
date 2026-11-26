package dev.hermeskotlin.android.wake

import dev.hermeskotlin.core.custom.LiveTranscripts
import dev.hermeskotlin.core.custom.SpeechGate
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.DeviceLock
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.wear.CallChat
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.VoiceController
import dev.hermeskotlin.ui.voice.VoicePhase
import dev.hermeskotlin.ui.voice.phoneVoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fork (requirements §5): the voice chat a wake word heard out of the app starts, with no screen: as Call Hermes does,
 * in the Default Call Target's conversation (else a new one), a live call with Phone Live on, else one request. Its
 * reply is read and it waits at Ready, as Talk does: the next request needs the wake word again (or a button). It runs
 * inside the wake word's microphone service, which keeps it allowed out of sight; stopping that ends it.
 */
class WakeVoice(
    private val relay: WatchRelay,
    private val settings: SettingsStore,
    audio: AudioApi,
    recorder: VoiceRecorder,
    player: SpeechPlayer,
    appScope: CoroutineScope,
    liveCalls: LiveCalls,
    deviceLock: DeviceLock,
    liveTranscripts: LiveTranscripts? = null,
    speechGate: SpeechGate? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val voice = VoiceController(audio, recorder, player, scope, appScope, liveCalls = liveCalls, deviceLock = deviceLock, liveTranscripts = liveTranscripts, speechGate = speechGate)
    private var counted = false

    /** The wake word was heard: starts the chat, or records its next request when it waits at Ready. */
    fun wake() {
        val now = settings.settings.value ?: AppSettings()
        when (voice.chat.value.phase) {
            VoicePhase.Ready -> {
                voice.armWake(now.wakeEndSilenceMs)
                voice.talk()
            }
            VoicePhase.Off -> scope.launch {
                val chat = relay.chatForCall(null, voiceDefault = true) as? CallChat.Ready ?: return@launch
                if (!counted) {
                    // Counted while it lasts, as a call: the gateway link stays up, and its turns aren't notified over it.
                    counted = true
                    relay.callStarted()
                }
                voice.armWake(now.wakeEndSilenceMs)
                voice.startChat(
                    chat.session, chat.gateway, chat.profile, now.voicePause.millis,
                    mode = phoneVoiceMode(now.phoneLive),
                    // Out of sight is where it lives: Ready stays.
                    readyAway = { true },
                    liveSilenceSeconds = now.phoneLiveSilenceSeconds,
                )
                voice.chat.first { it.phase == VoicePhase.Off }
                if (counted) {
                    counted = false
                    relay.callEnded()
                }
            }
            // Busy: the wake word doesn't listen then; nothing to do.
            else -> Unit
        }
    }

    /** Ends the chat: the wake word's service is going. */
    fun end() {
        if (voice.chat.value.phase != VoicePhase.Off) voice.stopChat()
    }
}
