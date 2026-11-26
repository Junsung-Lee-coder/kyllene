package dev.hermeskotlin.android.wake

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.notify.AppVisibility
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.PhoneWake
import dev.hermeskotlin.ui.voice.PhoneWakeState
import dev.hermeskotlin.ui.voice.VoiceButton
import dev.hermeskotlin.ui.voice.VoiceMedia
import dev.hermeskotlin.wearlink.AndroidWakeRecognizer
import dev.hermeskotlin.wearlink.PHONE_WAKE_REST_MS
import dev.hermeskotlin.wearlink.PHONE_WAKE_WINDOW_MS
import dev.hermeskotlin.wearlink.WAKE_PLAYBACK_COOLDOWN_MS
import dev.hermeskotlin.wearlink.WAKE_SCREEN_WINDOW_MS
import dev.hermeskotlin.wearlink.WakeEndSilence
import dev.hermeskotlin.wearlink.WakeLoop
import dev.hermeskotlin.wearlink.WakePhrases
import dev.hermeskotlin.wearlink.WakeRules
import dev.hermeskotlin.wearlink.WakeWindowEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Fork (requirements §5): the phone's wake word.
 *
 * - In the app: each time a chat comes on screen (the app shown, or the screen turned on while it shows), a
 *   [WAKE_SCREEN_WINDOW_MS] window. Heard, the chat in front starts its voice ([VoiceButton.wake]).
 * - Out of the app: only while [PhoneWakeService] runs (Start listening in Settings): the on-device recognizer alone,
 *   [PHONE_WAKE_WINDOW_MS] windows, then [PHONE_WAKE_REST_MS] of rest, with the screen on (or off, or charging, by those
 *   options). Heard, [WakeVoice] starts a chat with no screen. A phone with no on-device recognizer doesn't listen
 *   out of the app, and Settings says so.
 *
 * Never while any voice on the phone has the microphone or the speaker ([VoiceMedia]), nor within
 * [WAKE_PLAYBACK_COOLDOWN_MS] after; nor without the microphone allowed. One utterance starts one request: the phone's
 * own wake word claims as the watch's does ([WatchRelay.wakeClaims]). Main thread.
 */
class PhoneWakeController(
    private val context: Context,
    private val settings: SettingsStore,
    private val visibility: AppVisibility,
    private val relay: WatchRelay,
    private val voice: WakeVoice,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serviceRunning = MutableStateFlow(false)
    private val screenOn = MutableStateFlow(true)
    private val charging = MutableStateFlow(false)
    private var idleSince = SystemClock.elapsedRealtime()

    private fun phrases(): List<String> = WakePhrases.parse(settings.settings.value?.wakePhrases.orEmpty())
    private val inApp = WakeLoop(AndroidWakeRecognizer(context, onDeviceOnly = false) { WakePhrases.korean(phrases()) }, ::phrases, SystemClock::elapsedRealtime)
    private val onDeviceRecognizer = AndroidWakeRecognizer(context, onDeviceOnly = true) { WakePhrases.korean(phrases()) }
    private val outOfApp = WakeLoop(onDeviceRecognizer, ::phrases, SystemClock::elapsedRealtime)

    fun start() {
        PhoneWake.start = ::startService
        PhoneWake.stop = ::stop
        PhoneWake.onDevice.value = onDeviceRecognizer.unavailable() == null
        screenOn.value = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        charging.value = pluggedIn(ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> screenOn.value = true
                    Intent.ACTION_SCREEN_OFF -> screenOn.value = false
                    Intent.ACTION_POWER_CONNECTED -> charging.value = true
                    Intent.ACTION_POWER_DISCONNECTED -> charging.value = false
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { VoiceMedia.busy.collect { busy -> if (!busy) idleSince = SystemClock.elapsedRealtime() } }
        // In the app: a window each time a chat could come on screen.
        scope.launch {
            combine(visibility.visible, screenOn) { visible, on -> visible && on }.distinctUntilChanged().filter { it }.collectLatest {
                // The chat screen binds itself a moment after the activity starts.
                delay(SHOW_SETTLE_MS)
                inAppWindow()
            }
        }
        // Out of the app: windows while the service runs.
        scope.launch { serviceRunning.collectLatest { running -> if (running) outOfAppLoop() } }
        // The wake word, or listening out of the app, turned off: the service goes, and the microphone with it.
        scope.launch {
            settings.settings.map { it?.phoneWake == true && it.phoneWakeBackground }.distinctUntilChanged().collect { on ->
                if (!on && serviceRunning.value) stop()
            }
        }
    }

    private fun allowed(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun idle(): Boolean = !VoiceMedia.busy.value && SystemClock.elapsedRealtime() - idleSince >= WAKE_PLAYBACK_COOLDOWN_MS

    private suspend fun inAppWindow() {
        val s = settings.settings.value ?: return
        if (!s.phoneWake || !allowed() || !VoiceButton.canWake || !idle()) return
        if (inApp.window(WAKE_SCREEN_WINDOW_MS) == WakeWindowEnd.Matched && claim()) {
            VoiceButton.wake(WakeEndSilence.clamp(s.wakeEndSilenceMs))
        }
    }

    private suspend fun outOfAppLoop() {
        while (true) {
            if (onDeviceRecognizer.unavailable() != null) {
                PhoneWake.state.value = PhoneWakeState.NoOnDevice
                stop()
                return
            }
            if (!allowed()) {
                PhoneWake.state.value = PhoneWakeState.NoMicrophone
                stop()
                return
            }
            PhoneWake.state.value = if (visibility.visible.value) PhoneWakeState.InApp else PhoneWakeState.Listening
            // Until it may listen: out of the app, by the screen and power options, every voice idle.
            combine(settings.settings, visibility.visible, screenOn, charging, VoiceMedia.busy) { settings, visible, on, power, busy ->
                val now = settings ?: AppSettings()
                PhoneWake.state.value = if (visible) PhoneWakeState.InApp else PhoneWakeState.Listening
                !busy && WakeRules.phoneBackgroundWindows(now.phoneWake, now.phoneWakeBackground, now.phoneWakeScreenOff, now.phoneWakeCharging, true, visible, on, power)
            }.first { it }
            val cooling = WAKE_PLAYBACK_COOLDOWN_MS - (SystemClock.elapsedRealtime() - idleSince)
            if (cooling > 0) {
                delay(cooling)
                continue
            }
            when (outOfApp.window(PHONE_WAKE_WINDOW_MS)) {
                WakeWindowEnd.Matched -> if (claim()) voice.wake()
                WakeWindowEnd.Unavailable -> {
                    PhoneWake.state.value = PhoneWakeState.NoOnDevice
                    stop()
                    return
                }
                // Android may stretch the rest while the phone is idle (Doze): it's a delay, not an alarm.
                WakeWindowEnd.TimedOut -> delay(PHONE_WAKE_REST_MS)
            }
        }
    }

    /** One utterance, one start: the phone's claim against the watch's. */
    private fun claim(): Boolean = relay.wakeClaims.claim("phone-" + UUID.randomUUID())

    /** From Settings, with the app on screen: Android allows a microphone service only then. */
    private fun startService() {
        val s = settings.settings.value ?: return
        if (!s.phoneWake || !s.phoneWakeBackground) return
        if (onDeviceRecognizer.unavailable() != null) {
            PhoneWake.state.value = PhoneWakeState.NoOnDevice
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, PhoneWakeService::class.java))
        } catch (_: Exception) {
            refused()
        }
    }

    /** Stop: the listening ends, a chat it started ends, and the service goes. */
    fun stop() {
        voice.end()
        context.stopService(Intent(context, PhoneWakeService::class.java))
        serviceRunning.value = false
        if (PhoneWake.state.value == PhoneWakeState.Listening || PhoneWake.state.value == PhoneWakeState.InApp) PhoneWake.state.value = PhoneWakeState.Stopped
    }

    fun serviceStarted() {
        serviceRunning.value = true
        PhoneWake.state.value = if (visibility.visible.value) PhoneWakeState.InApp else PhoneWakeState.Listening
    }

    fun serviceStopped() {
        voice.end()
        serviceRunning.value = false
        if (PhoneWake.state.value == PhoneWakeState.Listening || PhoneWake.state.value == PhoneWakeState.InApp) PhoneWake.state.value = PhoneWakeState.Stopped
    }

    fun refused() {
        serviceRunning.value = false
        PhoneWake.state.value = PhoneWakeState.Refused
    }

    private fun pluggedIn(battery: Intent?): Boolean = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private companion object {
        const val SHOW_SETTLE_MS = 600L
    }
}
