package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.WakeEndSilence
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * This app's own recording, on a watch without an input screen: [level] while it hears, [error] when it missed.
 * [waiting] while it checks with the phone first, before the microphone opens.
 */
data class Listening(
    val recording: Boolean = false,
    val level: Float = 0f,
    val error: WatchLine? = null,
    val waiting: Boolean = false,
)

/**
 * The talk screen. A tap on the mic records one request here until it's sent (the orb, Done talking) or dropped
 * (leaving the screen), and the gateway transcribes it (requirements §3.2). Launched with
 * [EXTRA_CALL] (the tile, the complication) it resolves the phone’s default target and calls.
 * With [EXTRA_LISTEN], or as the watch's assistant (`ACTION_ASSIST`), it starts listening at once.
 */
class MainActivity : ComponentActivity() {

    private val app get() = HeraldWatch.of(this)
    private val recorder = WatchRecorder(this)
    private val listening = MutableStateFlow(Listening())
    private var recordingJob: Job? = null
    private var defaultCallJob: Job? = null
    private var permissionDefault: String? = null
    private var callPermissionPending = false
    private var inputGeneration: Long? = null
    private var callGeneration: Long? = null

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) completeInput(inputGeneration, ::record) else listening.value = Listening(error = NEEDS_MICROPHONE)
    }

    private val callMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = permissionDefault
        val pending = callPermissionPending
        permissionDefault = null
        callPermissionPending = false
        if (granted) {
            if (pending) completeInput(inputGeneration) { app.link.startCall(target) }
        } else listening.value = Listening(error = NEEDS_MICROPHONE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeraldWatchApp(
                link = app.link,
                store = app.store,
                speech = app.speech,
                listening = listening,
                onMic = ::talk,
                onFinishRecording = recorder::finish,
                onDismissListeningError = { listening.value = Listening() },
            )
        }
        // How the phone stands, for the dot at the top edge: asked again now and then while the app is in sight.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (app.link.synchronizeProfile()) app.link.refreshChats()
                while (true) {
                    delay(PHONE_CHECK_MS)
                    app.link.refreshPhone()
                }
            }
        }
        lifecycleScope.launch {
            var previous = app.link.profileReset.value
            app.link.profileReset.collect { generation ->
                if (generation == previous) return@collect
                previous = generation
                recordingJob?.cancel()
                permissionDefault = null
                callPermissionPending = false
                inputGeneration = null
                // A shortcut waiting for its first check survives that check's initial reset.
                if (callGeneration != null && callGeneration != generation) defaultCallJob?.cancel()
                callGeneration = null
                listening.value = Listening()
            }
        }
        // Fork: the wake word doesn't listen while this screen records.
        lifecycleScope.launch { listening.collect { app.wake.appRecording.value = it.recording } }
        // A call keeps the screen on: with it off, the activity stops, and the call with it.
        lifecycleScope.launch {
            app.link.call.collect { call ->
                if (call?.active == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (savedInstanceState == null) {
            handle(intent)
            // Hermes rings the watch when it needs an answer, which takes notifications. Asked once; a no stays a no.
            if (Build.VERSION.SDK_INT >= 33 && !app.rings.canRing && !app.store.askedToRing) {
                app.store.askedToRing = true
                notificationsAllowed.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.callDisplay.refresh()
    }

    // Fork (requirements §5): the wake word's window each time this screen comes on, and its start here while it shows.
    override fun onResume() {
        super.onResume()
        app.wake.inApp = ::wakeStart
        app.wake.appShown()
    }

    override fun onPause() {
        app.wake.inApp = null
        app.wake.appHidden()
        super.onPause()
    }

    /** Fork: the wake word was heard on this screen: a call as Talk would with Talk live on, else one request recorded. */
    private fun wakeStart() {
        if (recordingJob?.isActive == true || app.link.call.value?.active == true) return
        if (app.link.liveVoice.value && app.store.live.value) return call()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val generation = app.link.profileReset.value
        val endpoint = WatchRecorder.Endpoint.wake(WakeEndSilence.clamp(app.store.wake.value.endSilenceMs))
        app.link.quiet()
        listening.value = Listening(recording = true)
        @Suppress("MissingPermission") // checked above.
        recordingJob = lifecycleScope.launch {
            recordAsk(listening, generation, { app.link.profileReset.value }, { onLevel -> recorder.record(endpoint, onLevel) }, ask = { audio ->
                app.link.ask(null, audio, WatchRecorder.MIME_TYPE)
            }, drop = { it.delete() })
        }
    }

    private val notificationsAllowed = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        // Debug builds take a typed question from adb (`--es ask "…"`), for trying a turn on an emulator without a mic.
        val typed = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_DEBUG_ASK) else null
        when {
            !typed.isNullOrBlank() -> app.link.ask(typed)
            intent.getBooleanExtra(EXTRA_CALL, false) -> defaultCall()
            intent.getStringExtra(EXTRA_ANSWER_CALL) == app.callToken -> call()
            AssistEntry.asksToListen(intent.action, intent.getBooleanExtra(EXTRA_LISTEN, false)) -> talk()
        }
    }

    /** A ring answered: a live call in its chat, whatever the talk setting, since that's how the question is asked. */
    private fun call() {
        defaultCallJob?.cancel()
        permissionDefault = null
        callPermissionPending = true
        inputGeneration = app.link.profileReset.value
        app.link.dismissCall()
        listening.value = Listening()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.link.startCall()
        } else {
            callMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Out of sight, a recording or a call would go on hearing the room.
        recordingJob?.cancel()
        if (!isChangingConfigurations) {
            defaultCallJob?.cancel()
            permissionDefault = null
            callPermissionPending = false
            app.link.endCall()
        }
    }

    /** The mic: a live call when the gateway talks live and it's on, else listening for one ask. */
    private fun defaultCall() {
        defaultCallJob?.cancel()
        permissionDefault = null
        callPermissionPending = false
        callGeneration = null
        defaultCallJob = lifecycleScope.launch {
            if (!ready()) return@launch
            callGeneration = app.link.profileReset.value
            val target = try { app.link.resolveDefaultCall() } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                listening.value = Listening(error = WatchLine.Res(R.string.wear_phone_no_answer))
                return@launch
            }
            target.error?.let { listening.value = Listening(error = WatchLine.Raw(it)); return@launch }
            listening.value = Listening()
            inputGeneration = app.link.profileReset.value
            // No live voice there: the tile still talks to the target, one spoken ask at a time.
            if (!target.live) return@launch listen()
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                app.link.startCall(target.requestId)
            } else {
                permissionDefault = target.requestId
                callPermissionPending = true
                callMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun talk() {
        // A second call-up while Herald already hears (the assistant button pressed twice) changes nothing: its own
        // recording, or a live call.
        if (recordingJob?.isActive == true || app.link.call.value?.active == true) return
        defaultCallJob?.cancel()
        permissionDefault = null
        callPermissionPending = false
        callGeneration = null
        defaultCallJob = lifecycleScope.launch {
            if (!ready()) return@launch
            inputGeneration = app.link.profileReset.value
            if (!(app.link.liveVoice.value && app.store.live.value)) return@launch listen()
            app.link.dismissCall()
            listening.value = Listening()
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                app.link.startCall()
            } else {
                callPermissionPending = true
                callMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private suspend fun ready(): Boolean {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
        if (app.link.synchronizeProfile()) return true
        listening.value = Listening(error = WatchLink.NO_HERALD)
        return false
    }

    private fun completeInput(generation: Long?, action: () -> Unit) {
        lifecycleScope.launch {
            if (!ready()) return@launch
            if (generation == null || generation != app.link.profileReset.value) {
                listening.value = Listening(error = PROFILE_CHANGED)
            } else action()
        }
    }

    /**
     * Fork (requirements §3.2): Talk records here, straight to listening, until it's sent or dropped. Not the watch's
     * speech recognizer: that one ends the session itself once speech stops, which Talk mustn't.
     */
    private fun listen() {
        // Nothing of the last reply may play while the microphone is open.
        app.link.quiet()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            record()
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("MissingPermission") // listen() checks it first.
    private fun record() {
        if (recordingJob?.isActive == true) return
        val generation = app.link.profileReset.value
        listening.value = Listening(recording = true)
        recordingJob = lifecycleScope.launch {
            recordAsk(listening, generation, { app.link.profileReset.value }, { onLevel -> recorder.record(onLevel) }, ask = { audio ->
                app.link.ask(null, audio, WatchRecorder.MIME_TYPE)
            }, drop = { it.delete() })
        }
    }

    companion object {
        const val EXTRA_CALL = "call_default"
        const val EXTRA_LISTEN = "listen"
        private const val EXTRA_ANSWER_CALL = "call"
        private const val EXTRA_DEBUG_ASK = "ask"
        private const val PHONE_CHECK_MS = 30_000L
        private val NEEDS_MICROPHONE = WatchLine.Res(R.string.wear_needs_microphone)

        /** For a PendingIntent, which starts it in its own task (it's singleTask) and to its onNewIntent when open. */
        fun callIntent(context: Context): Intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_CALL, true)

        fun listenIntent(context: Context): Intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_LISTEN, true)

        /** A ring answered: starts the call in the watch's chat, which [WatchLink.answerRing] just set. */
        fun answerCallIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ANSWER_CALL, HeraldWatch.of(context).callToken)
    }
}
