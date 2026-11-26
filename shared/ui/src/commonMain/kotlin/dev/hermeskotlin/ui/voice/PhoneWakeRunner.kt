package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.wearlink.PHONE_WAKE_REST_MS
import dev.hermeskotlin.wearlink.PHONE_WAKE_WINDOW_MS
import dev.hermeskotlin.wearlink.WAKE_PLAYBACK_COOLDOWN_MS
import dev.hermeskotlin.wearlink.WAKE_SCREEN_WINDOW_MS
import dev.hermeskotlin.wearlink.WakeEndSilence
import dev.hermeskotlin.wearlink.WakeLoop
import dev.hermeskotlin.wearlink.WakePhrases
import dev.hermeskotlin.wearlink.WakeRecognizer
import dev.hermeskotlin.wearlink.WakeRules
import dev.hermeskotlin.wearlink.WakeWindowEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fork (requirements §5): when the phone's wake word listens, and what a phrase heard does. Platform-free: the app
 * gives it the recognizers, its state and what to do ([PhoneWakeController] on Android).
 *
 * - In the app: a [WAKE_SCREEN_WINDOW_MS] window each time a chat can come on screen (the app shown, the screen
 *   turned on while it shows), only while the app shows and every voice is idle ([busy] false).
 * - Out of the app: while the service runs ([serviceRunning]), [PHONE_WAKE_WINDOW_MS] windows on the on-device
 *   recognizer, then [PHONE_WAKE_REST_MS] of rest, only while the app is hidden, every voice idle, and the screen and
 *   power options allow ([WakeRules.phoneBackgroundWindows]).
 *
 * Each window runs only while its condition holds: the app coming to the front, a voice taking the microphone (a
 * chat, the composer's dictation, Read aloud) or an option turned off cancels it at once, and the recognizer lets go.
 * A phrase heard is checked again just before anything starts, then claimed ([claim]) so one utterance starts once.
 * Never within [WAKE_PLAYBACK_COOLDOWN_MS] of a voice going idle. Only one window at a time.
 */
class PhoneWakeRunner(
    inAppRecognizer: WakeRecognizer,
    outOfAppRecognizer: WakeRecognizer,
    private val settings: StateFlow<AppSettings?>,
    private val visible: StateFlow<Boolean>,
    private val screenOn: StateFlow<Boolean>,
    private val charging: StateFlow<Boolean>,
    private val busy: StateFlow<Boolean>,
    private val serviceRunning: StateFlow<Boolean>,
    private val now: () -> Long,
    /** The microphone is allowed. */
    private val allowed: () -> Boolean,
    /** The out-of-app recognizer can't run here (no on-device recognizer); null when it can. */
    private val outOfAppUnavailable: () -> String?,
    /** A chat is in front to take the wake word ([VoiceButton.canWake]). */
    private val chatInFront: () -> Boolean,
    private val claim: () -> Boolean,
    /** Heard in the app: the chat in front starts ([VoiceButton.wake]); false if it didn't take it. */
    private val wakeInApp: (endMs: Int) -> Boolean,
    /** Heard out of the app: the chat with no screen starts ([WakeVoice]). */
    private val wakeOutOfApp: () -> Unit,
    /** Out of the app it can't listen at all: the service goes. */
    private val stopService: () -> Unit,
    /** Whether a voice has the microphone right now ([VoiceMedia.busyNow]), for the check just before a start. */
    private val busyNow: () -> Boolean = { busy.value },
) {
    private fun phrases(): List<String> = WakePhrases.parse(settings.value?.wakePhrases.orEmpty())
    private val inApp = WakeLoop(inAppRecognizer, ::phrases, now)
    private val outOfApp = WakeLoop(outOfAppRecognizer, ::phrases, now)
    private var idleSince = now()

    private val inAppAllowed: Flow<Boolean> = combine(visible, busy, settings) { shown, isBusy, s -> shown && !isBusy && s?.phoneWake == true }

    private val outOfAppAllowed: Flow<Boolean> = combine(settings, visible, screenOn, charging, busy) { s, shown, on, power, isBusy ->
        val now = s ?: AppSettings()
        !isBusy && WakeRules.phoneBackgroundWindows(now.phoneWake, now.phoneWakeBackground, now.phoneWakeScreenOff, now.phoneWakeCharging, true, shown, on, power)
    }

    fun start(scope: CoroutineScope) {
        scope.launch { busy.collect { if (!it) idleSince = now() } }
        // In the app: a window each time a chat could come on screen.
        scope.launch {
            combine(visible, screenOn) { shown, on -> shown && on }.distinctUntilChanged().filter { it }.collectLatest {
                // The chat screen binds itself a moment after the activity starts.
                delay(SHOW_SETTLE_MS)
                inAppWindow()
            }
        }
        // Out of the app: windows while the service runs.
        scope.launch { serviceRunning.collectLatest { running -> if (running) outOfAppLoop() } }
        scope.launch {
            combine(serviceRunning, visible) { running, shown -> running to shown }.collect { (running, shown) ->
                if (running && PhoneWake.state.value in RUNNING) PhoneWake.state.value = if (shown) PhoneWakeState.InApp else PhoneWakeState.Listening
            }
        }
    }

    private fun inAppNow(): Boolean = visible.value && !busy.value && !busyNow() && settings.value?.phoneWake == true && chatInFront() && allowed() && cooled()

    private fun outOfAppNow(): Boolean {
        val s = settings.value ?: return false
        return !busy.value && !busyNow() && cooled() && allowed() &&
            WakeRules.phoneBackgroundWindows(s.phoneWake, s.phoneWakeBackground, s.phoneWakeScreenOff, s.phoneWakeCharging, serviceRunning.value, visible.value, screenOn.value, charging.value)
    }

    private fun cooled(): Boolean = now() - idleSince >= WAKE_PLAYBACK_COOLDOWN_MS

    private suspend fun inAppWindow() {
        if (!inAppNow()) return
        val end = whileHolds(inAppAllowed) { inApp.window(WAKE_SCREEN_WINDOW_MS) }
        // Checked again before anything starts: the chat may have taken the microphone, or the app gone, meanwhile.
        if (end == WakeWindowEnd.Matched && inAppNow() && claim()) {
            wakeInApp(WakeEndSilence.clamp(settings.value?.wakeEndSilenceMs ?: WakeEndSilence.DEFAULT_MS))
        }
    }

    private suspend fun outOfAppLoop() {
        while (true) {
            if (outOfAppUnavailable() != null) {
                PhoneWake.state.value = PhoneWakeState.NoOnDevice
                stopService()
                return
            }
            if (!allowed()) {
                PhoneWake.state.value = PhoneWakeState.NoMicrophone
                stopService()
                return
            }
            // Until it may listen.
            outOfAppAllowed.first { it }
            val cooling = WAKE_PLAYBACK_COOLDOWN_MS - (now() - idleSince)
            if (cooling > 0) {
                delay(cooling)
                continue
            }
            when (whileHolds(outOfAppAllowed) { outOfApp.window(PHONE_WAKE_WINDOW_MS) }) {
                WakeWindowEnd.Matched -> if (outOfAppNow() && claim()) wakeOutOfApp()
                WakeWindowEnd.Unavailable -> {
                    PhoneWake.state.value = PhoneWakeState.NoOnDevice
                    stopService()
                    return
                }
                // Android may stretch the rest while the phone is idle (Doze): it's a delay, not an alarm.
                WakeWindowEnd.TimedOut -> delay(PHONE_WAKE_REST_MS)
                // Cut short (the app came to the front, a voice took the microphone): wait until it may listen again.
                null -> Unit
            }
        }
    }

    private companion object {
        const val SHOW_SETTLE_MS = 600L
        val RUNNING = setOf(PhoneWakeState.Listening, PhoneWakeState.InApp)
    }
}

/**
 * Runs [block] while [condition] holds, cancelling it the moment it stops holding; null when it was cut short. The
 * condition is read as it is now first: already false, [block] doesn't run.
 */
internal suspend fun <T> whileHolds(condition: Flow<Boolean>, block: suspend () -> T): T? = coroutineScope {
    if (!condition.first()) return@coroutineScope null
    var stopped = false
    val work = async { block() }
    val watcher = launch {
        condition.first { !it }
        stopped = true
        work.cancel()
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        if (!stopped || !isActive) throw e
        null
    } finally {
        watcher.cancel()
    }
}
