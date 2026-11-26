package dev.hermeskotlin.wear

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.hermeskotlin.wearlink.AndroidWakeRecognizer
import dev.hermeskotlin.wearlink.WakeSessionEnd
import dev.hermeskotlin.wearlink.WatchWakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork (requirements §5): the watch's wake word seams on Robolectric: the real [AndroidWakeRecognizer] over Android's
 * SpeechRecognizer (its shadow plays the recognition service), the recognizer chosen (never this app's own), the
 * settings kept between launches, and the recordings' endpoints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchWakeTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun recognitionService(pkg: String, name: String) {
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; this.name = name } }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), info)
    }

    @Test
    fun withOnlyThisAppsOwnRecognizerItSaysThereIsNone() {
        recognitionService(context.packageName, "dev.hermeskotlin.wear.assist.HeraldWearRecognitionService")
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", ComponentName(context.packageName, "Own").flattenToString())
        assertEquals(AndroidWakeRecognizer.NO_RECOGNIZER, AndroidWakeRecognizer(context, onDeviceOnly = false) { false }.unavailable())
        recognitionService("com.example.speech", "com.example.speech.Recognizer")
        assertNull(AndroidWakeRecognizer(context, onDeviceOnly = false) { false }.unavailable())
    }

    @Test
    fun aSessionPassesOnReadyAndWordsAndNothingAfterItEnds() {
        recognitionService("com.example.speech", "com.example.speech.Recognizer")
        val recognizer = AndroidWakeRecognizer(context, onDeviceOnly = false) { true }
        var ready = 0
        val heard = mutableListOf<String>()
        var end: WakeSessionEnd? = null
        CoroutineScope(Dispatchers.Main).launch { end = recognizer.session({ ready++ }, { heard += it }) }
        idle()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        // Korean was asked for, with partial results.
        val asked = shadow.lastRecognizerIntent
        assertEquals("ko-KR", asked.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(asked.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals(0, ready, "not ready before the recognizer says so")
        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnPartialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("루미")) })
        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle()
        assertEquals(WakeSessionEnd.Quiet, end)
        assertEquals(1, ready)
        assertEquals(listOf("루미"), heard)
        // A late callback from the ended session goes nowhere.
        shadow.triggerOnPartialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late")) })
        idle()
        assertEquals(listOf("루미"), heard)
    }

    @Test
    fun aRecognizerThatFailsOrLacksPermissionSaysWhich() {
        recognitionService("com.example.speech", "com.example.speech.Recognizer")
        val recognizer = AndroidWakeRecognizer(context, onDeviceOnly = false) { false }
        var end: WakeSessionEnd? = null
        CoroutineScope(Dispatchers.Main).launch { end = recognizer.session({}, {}) }
        idle()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        idle()
        assertEquals(WakeSessionEnd.Unavailable(AndroidWakeRecognizer.NO_PERMISSION), end)
        CoroutineScope(Dispatchers.Main).launch { end = recognizer.session({}, {}) }
        idle()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        idle()
        assertEquals(WakeSessionEnd.Failed(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), end)
    }

    @Test
    fun theWatchKeepsThePhonesSettingsBetweenLaunchesAndStartsOff() {
        assertEquals(WatchWakeSettings(), WatchStore(context).wake.value)
        val on = WatchWakeSettings(enabled = true, phrases = "hermes", endSilenceMs = 4_500, background = true)
        WatchStore(context).setWake(on)
        assertEquals(on, WatchStore(context).wake.value)
    }

    @Test
    fun onlyTheWakeStartedRecordingEndsOnTheSetting() {
        assertEquals(WatchRecorder.Endpoint(2_000, 8_000, Long.MAX_VALUE), WatchRecorder.Endpoint.wake(2_000))
        // Talk's recordings never end by themselves (§3.2): only Send or leaving ends them.
        assertEquals(WatchRecorder.Endpoint(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), WatchRecorder.Endpoint.TALK)
    }
}
