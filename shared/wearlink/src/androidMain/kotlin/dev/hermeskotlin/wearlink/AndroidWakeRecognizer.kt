package dev.hermeskotlin.wearlink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Fork (requirements §5): one session of Android's [SpeechRecognizer] for [WakeLoop], on the main thread as it must be.
 *
 * [onDeviceOnly]: the on-device recognizer and nothing else (the phone out of sight); none there is [WakeSessionEnd.Unavailable].
 * Otherwise the system's recognizer, never this app's own: as the digital assistant its own recognition service may be
 * the default, and that one turns requests away (it would only call back into itself). Partial results are asked for, so a
 * phrase is caught as it's said; [korean] asks for Korean. A session cancelled or replaced passes nothing on afterwards.
 */
class AndroidWakeRecognizer(
    private val context: Context,
    private val onDeviceOnly: Boolean,
    private val korean: () -> Boolean,
) : WakeRecognizer {

    /** Whether this device can run it at all; null if it can, else why not. */
    fun unavailable(): String? = when {
        onDeviceOnly && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) -> NO_ON_DEVICE
        !onDeviceOnly && component() == null -> NO_RECOGNIZER
        else -> null
    }

    override suspend fun session(onReady: () -> Unit, onText: (String) -> Unit): WakeSessionEnd = withContext(Dispatchers.Main) {
        unavailable()?.let { return@withContext WakeSessionEnd.Unavailable(it) }
        val recognizer = try {
            if (onDeviceOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context, component())
        } catch (e: RuntimeException) {
            return@withContext WakeSessionEnd.Unavailable(e.message ?: NO_RECOGNIZER)
        }
        try {
            suspendCancellableCoroutine<WakeSessionEnd> { continuation ->
                // This session's: once it's over, whatever the recognizer still says goes nowhere.
                var over = false
                fun finish(end: WakeSessionEnd) {
                    if (over) return
                    over = true
                    if (continuation.isActive) continuation.resume(end)
                }
                fun texts(bundle: Bundle?) {
                    if (over) return
                    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.forEach(onText)
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (!over) onReady() }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = texts(partialResults)
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        texts(results)
                        finish(WakeSessionEnd.Quiet)
                    }
                    override fun onError(error: Int) = finish(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> WakeSessionEnd.Quiet
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> WakeSessionEnd.Unavailable(NO_PERMISSION)
                            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> WakeSessionEnd.Unavailable(NO_LANGUAGE)
                            else -> WakeSessionEnd.Failed(error)
                        },
                    )
                })
                continuation.invokeOnCancellation { over = true }
                recognizer.startListening(intent())
            }
        } finally {
            // Always let go of the microphone: cancelled, replaced or done.
            runCatching { recognizer.cancel() }
            recognizer.destroy()
        }
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .apply {
            if (korean()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            if (onDeviceOnly) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /** The system's recognizer unless it's this app's own; else another installed one; null if there's none. */
    private fun component(): ComponentName? {
        val own = context.packageName
        val chosen = Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
            ?.let(ComponentName::unflattenFromString)?.takeIf { it.packageName != own }
        if (chosen != null) return chosen
        return context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != own }
            .sortedByDescending { it.packageName == GOOGLE }
            .firstOrNull()
    }

    companion object {
        const val NO_ON_DEVICE = "no_on_device_recognizer"
        const val NO_RECOGNIZER = "no_recognizer"
        const val NO_PERMISSION = "no_microphone_permission"
        const val NO_LANGUAGE = "language_not_supported"
        private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
        private const val GOOGLE = "com.google.android.googlequicksearchbox"
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and _UNAVAILABLE, API 31; the same numbers before.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
