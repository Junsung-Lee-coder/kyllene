package dev.hermeskotlin.wear

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
import dev.hermeskotlin.wearlink.AndroidWakeRecognizer
import dev.hermeskotlin.wearlink.WAKE_PLAYBACK_COOLDOWN_MS
import dev.hermeskotlin.wearlink.WAKE_SCREEN_WINDOW_MS
import dev.hermeskotlin.wearlink.WakeEndSilence
import dev.hermeskotlin.wearlink.WakeLoop
import dev.hermeskotlin.wearlink.WakePhrases
import dev.hermeskotlin.wearlink.WakeRules
import dev.hermeskotlin.wearlink.WakeWindowEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the watch's wake word is doing, for its screens to say: only what is, never what was asked for. */
enum class WatchWakeState {
    /** Off, or nothing has started it. */
    Off,

    /** On: a 5-second window each time the app's screen turns on. */
    InApp,

    /** The listening service runs: outside the app too, by the options. */
    Outside,

    /** No speech recognizer on the watch (other than this app's own). */
    NoRecognizer,

    /** The microphone isn't allowed. */
    NoMicrophone,
}

/**
 * Fork customization (requirements §5): the watch's wake word.
 *
 * - On: a [WAKE_SCREEN_WINDOW_MS] window each time the screen actually turns on with the app showing (its activity
 *   resumed, which the screen turning on or the app being opened does), never on a timer.
 * - Outside the app (that option, and [WatchWakeService] running, which only the app on screen starts): the same window
 *   each time the screen turns on ([Intent.ACTION_SCREEN_ON]); all the while the screen is off, or the watch charges, by
 *   those options ([WakeRules.watchContinuous]): recognizer sessions renewed back to back, failures backing off.
 *
 * The watch's ordinary recognizer listens (never this app's own). Never while it records, talks in a call or plays a
 * reply, nor within [WAKE_PLAYBACK_COOLDOWN_MS] after; nor without the microphone allowed. Heard: the phone is asked
 * ([WatchLink.claimWake]) so the phone and the watch never both start; then a live call (Talk live on, the gateway
 * live) or one request recorded until its quiet spell ([WatchRecorder.Endpoint.wake]). Main thread.
 */
class WatchWake(private val context: Context, private val app: HeraldWatch) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = app.store
    private val link get() = app.link

    private val recognizer = AndroidWakeRecognizer(context, onDeviceOnly = false) { WakePhrases.korean(phrases()) }
    private val loop = WakeLoop(recognizer, ::phrases, SystemClock::elapsedRealtime)
    private fun phrases(): List<String> = WakePhrases.parse(store.wake.value.phrases)

    private val _state = MutableStateFlow(WatchWakeState.Off)
    val state: StateFlow<WatchWakeState> = _state.asStateFlow()

    private val _listening = MutableStateFlow(false)

    /** The recognizer is listening now (its ready callback came), for the Talk screen's "Say …" line. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val appVisible = MutableStateFlow(false)
    private val screenOn = MutableStateFlow(true)
    private val charging = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private var idleSince = SystemClock.elapsedRealtime()
    private var window: Job? = null

    /** The app's own start when it shows: records the request on its screen, or calls. Null: the app isn't showing. */
    var inApp: (() -> Unit)? = null

    private val recorder = WatchRecorder()
    private val outsideListening = MutableStateFlow(Listening())
    /** What the wake word started outside the app, which needs the service's microphone: ended with it. */
    private var outsideRecording: Job? = null
    private var outsideCall = false

    fun start() {
        screenOn.value = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        charging.value = (ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn.value = true
                        // Outside the app, the screen turning on opens a window; in it, the activity resuming does.
                        if (!appVisible.value) screenWindow()
                    }
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
        // Busy: recording (on screen or out of it), a call, a reply playing.
        scope.launch {
            combine(link.call, app.speech.speaking, outsideListening, appRecording) { call, speaking, outside, recording ->
                call?.active == true || speaking || outside.recording || recording
            }.distinctUntilChanged().collect { now ->
                busy.value = now
                if (now) window?.cancel() else idleSince = SystemClock.elapsedRealtime()
            }
        }
        // All the while, outside the app, by the screen-off and charging options.
        scope.launch {
            combine(store.wake, _serviceRunning, screenOn, charging, busy) { s, running, on, power, isBusy ->
                !isBusy && WakeRules.watchContinuous(s, running, on, power)
            }.distinctUntilChanged().collectLatest { continuous ->
                if (!continuous) return@collectLatest
                while (true) {
                    cooldown()
                    if (!allowed()) return@collectLatest
                    if (listen(Long.MAX_VALUE) != WakeWindowEnd.TimedOut) return@collectLatest
                }
            }
        }
        // The wake word off, or outside listening off: the service goes, and the microphone with it.
        scope.launch {
            store.wake.map { it.enabled && it.background }.distinctUntilChanged().collect { on -> if (!on && _serviceRunning.value) stopService() }
        }
        scope.launch {
            combine(store.wake, _serviceRunning) { s, running ->
                when {
                    !s.enabled -> WatchWakeState.Off
                    recognizer.unavailable() != null -> WatchWakeState.NoRecognizer
                    !allowed() -> WatchWakeState.NoMicrophone
                    running -> WatchWakeState.Outside
                    else -> WatchWakeState.InApp
                }
            }.collect { _state.value = it }
        }
    }

    /** The app's recording, which the activity reports. */
    val appRecording = MutableStateFlow(false)

    /** The app's screen came on (opened, or the screen turned on with it showing). Starts the service if asked for. */
    fun appShown() {
        appVisible.value = true
        val s = store.wake.value
        if (s.enabled && s.background && allowed() && !_serviceRunning.value) {
            // Only here, with the app on screen: Android lets a microphone service start then, and no other way.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchWakeService::class.java)) }
        }
        screenWindow()
    }

    fun appHidden() {
        appVisible.value = false
        // Leaving the app ends its own window; outside, the service's rules take over.
        if (!_serviceRunning.value) window?.cancel()
    }

    private fun screenWindow() {
        val s = store.wake.value
        if (!WakeRules.watchScreenWindow(s, appVisible.value, _serviceRunning.value)) return
        if (busy.value || !allowed()) return
        // The continuous listening already listens; a window on top would fight it for the recognizer.
        if (WakeRules.watchContinuous(s, _serviceRunning.value, screenOn.value, charging.value)) return
        window?.cancel()
        window = scope.launch {
            cooldown()
            listen(WAKE_SCREEN_WINDOW_MS)
        }
    }

    private suspend fun cooldown() {
        val left = WAKE_PLAYBACK_COOLDOWN_MS - (SystemClock.elapsedRealtime() - idleSince)
        if (left > 0) delay(left)
        busy.first { !it }
    }

    /** One window; heard and allowed, starts. How it ended. */
    private suspend fun listen(ms: Long): WakeWindowEnd {
        val end = try {
            loop.window(ms) { _listening.value = true }
        } finally {
            _listening.value = false
        }
        if (end == WakeWindowEnd.Matched && link.claimWake()) trigger()
        if (end == WakeWindowEnd.Unavailable) _state.value = WatchWakeState.NoRecognizer
        return end
    }

    private fun trigger() {
        inApp?.let { return it() }
        // Outside the app: a call, as Talk would with Talk live on; else one request, recorded here.
        if (link.liveVoice.value && store.live.value) {
            outsideCall = true
            link.startCall()
            return
        }
        val endMs = WakeEndSilence.clamp(store.wake.value.endSilenceMs)
        outsideRecording = scope.launch {
            @Suppress("MissingPermission") // allowed() was checked before listening.
            recordAsk(outsideListening, link.profileReset.value, { link.profileReset.value }, { onLevel -> recorder.record(WatchRecorder.Endpoint.wake(endMs), onLevel) }) { audio ->
                link.ask(null, audio, WatchRecorder.MIME_TYPE)
            }
        }
    }

    fun allowed(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun serviceStarted() {
        _serviceRunning.value = true
    }

    fun serviceStopped() {
        _serviceRunning.value = false
        window?.cancel()
        // The microphone goes with the service: a recording it started out of the app ends unsent, and a call it
        // started ends, as Hang up does.
        outsideRecording?.cancel()
        outsideRecording = null
        if (outsideCall && !appVisible.value) link.endCall()
        outsideCall = false
    }

    /** Stop listening (Settings, or the notification): the service goes, and with it outside listening. */
    fun stopService() {
        context.stopService(Intent(context, WatchWakeService::class.java))
        serviceStopped()
    }
}
