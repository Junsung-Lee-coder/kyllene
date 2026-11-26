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
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.PhoneWake
import dev.hermeskotlin.ui.voice.PhoneWakeRunner
import dev.hermeskotlin.ui.voice.PhoneWakeState
import dev.hermeskotlin.ui.voice.VoiceButton
import dev.hermeskotlin.ui.voice.VoiceMedia
import dev.hermeskotlin.wearlink.AndroidWakeRecognizer
import dev.hermeskotlin.wearlink.WakePhrases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Fork (requirements §5): the phone's wake word on Android: Android's recognizers, the screen and the charger, the
 * listening service and its status, around [PhoneWakeRunner], which decides when it listens and what a phrase heard
 * does (in the app: the chat in front, [VoiceButton.wake]; out of it: [WakeVoice], only while [PhoneWakeService] runs,
 * which only Settings › Start listening starts). Main thread.
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

    private fun korean(): Boolean = WakePhrases.korean(WakePhrases.parse(settings.settings.value?.wakePhrases.orEmpty()))
    private val onDeviceRecognizer = AndroidWakeRecognizer(context, onDeviceOnly = true, ::korean)
    private val runner = PhoneWakeRunner(
        inAppRecognizer = AndroidWakeRecognizer(context, onDeviceOnly = false, ::korean),
        outOfAppRecognizer = onDeviceRecognizer,
        settings = settings.settings,
        visible = visibility.visible,
        screenOn = screenOn,
        charging = charging,
        busy = VoiceMedia.busy,
        serviceRunning = serviceRunning,
        now = SystemClock::elapsedRealtime,
        allowed = ::allowed,
        outOfAppUnavailable = onDeviceRecognizer::unavailable,
        chatInFront = { VoiceButton.canWake },
        // One utterance, one start: the phone's claim against the watch's.
        claim = { relay.wakeClaims.claim("phone-" + UUID.randomUUID()) },
        wakeInApp = VoiceButton::wake,
        wakeOutOfApp = voice::wake,
        stopService = ::stop,
        busyNow = VoiceMedia::busyNow,
    )

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
        runner.start(scope)
        // The wake word, or listening out of the app, turned off: the service goes, and the microphone with it.
        scope.launch {
            settings.settings.map { it?.phoneWake == true && it.phoneWakeBackground }.distinctUntilChanged().collect { on ->
                if (!on && serviceRunning.value) stop()
            }
        }
    }

    private fun allowed(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
        PhoneWake.state.value = if (visibility.visible.value) PhoneWakeState.InApp else PhoneWakeState.Listening
        serviceRunning.value = true
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
}
