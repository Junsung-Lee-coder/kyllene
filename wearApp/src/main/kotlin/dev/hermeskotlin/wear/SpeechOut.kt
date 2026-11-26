package dev.hermeskotlin.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Plays a reply aloud on the watch: the gateway's voice in clips as the phone sends them, in order, or the watch's
 * own voice when the gateway couldn't make one. Main thread only.
 */
class SpeechOut(private val context: Context) {

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _sounding = MutableStateFlow(false)

    /**
     * Fork (requirements §11): a reply is sounding right now: a clip's player started and not yet done, or the
     * watch's own voice between its start and end. Unlike [speaking], not while a clip is on its way or preparing.
     */
    val sounding: StateFlow<Boolean> = _sounding.asStateFlow()

    private var askId: String? = null
    private val clips = sortedMapOf<Int, File>()
    private var next = 0
    private var count = 0
    private var player: MediaPlayer? = null
    private var playing: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { stop() }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSay: String? = null

    /**
     * Fork (requirements §6): earphones that a reply read here would go to are gone, mid-reply: the reply is silenced
     * there ([WatchLink.quiet]), never moved on to the watch's speaker. Unset, it only stops.
     */
    var onOutputLost: (() -> Unit)? = null

    /**
     * Fork: Android's warning that audio is about to move to the watch's own speaker (earbuds disconnected), listened
     * for only while a reply plays, as Android's guide on outputs advises.
     */
    private val becomingNoisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY || !_speaking.value) return
            main.post { if (_speaking.value) onOutputLost?.invoke() ?: stop() }
        }
    }
    private var listeningForUnplug = false

    /** Fork (requirements §6): earphones or a headset are connected to the watch, to say so with an ask. */
    fun earphones(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val earphones = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) EARPHONES + AudioDeviceInfo.TYPE_BLE_HEADSET else EARPHONES
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in earphones }
    }

    private fun speakingNow(on: Boolean) {
        _speaking.value = on
        if (on == listeningForUnplug) return
        listeningForUnplug = on
        if (on) {
            runCatching { ContextCompat.registerReceiver(context, becomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED) }
        } else {
            runCatching { context.unregisterReceiver(becomingNoisy) }
        }
    }

    /**
     * Whether anything would be heard: a speaker, or earbuds or a headset over Bluetooth. Many watches have
     * no speaker, and asking the gateway for a voice nobody hears only costs time.
     */
    fun canPlay(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val audible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AUDIBLE + setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        } else {
            AUDIBLE
        }
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in audible }
    }

    /** Clip [index] of [count] for the reply to [askId]; a clip for another ask than the one playing starts over. */
    fun clip(askId: String, index: Int, count: Int, mimeType: String, bytes: ByteArray) {
        if (askId != this.askId) {
            stop()
            this.askId = askId
        }
        this.count = count
        val file = File.createTempFile("reply-", "." + extensionOf(mimeType), context.cacheDir).apply { writeBytes(bytes) }
        clips[index] = file
        pump()
    }

    /** Reads [text] in the watch's own voice. */
    fun say(askId: String, text: String) {
        stop()
        this.askId = askId
        val engine = tts
        if (engine != null && ttsReady) {
            speakNow(engine, text)
            return
        }
        pendingSay = text
        if (engine == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                val waiting = pendingSay
                pendingSay = null
                if (ttsReady && waiting != null) tts?.let { speakNow(it, waiting) }
            }
        }
    }

    fun stop() {
        main.removeCallbacks(giveUp)
        player?.release()
        player = null
        playing?.delete()
        playing = null
        clips.values.forEach { it.delete() }
        clips.clear()
        next = 0
        count = 0
        askId = null
        pendingSay = null
        tts?.stop()
        speakingNow(false)
        _sounding.value = false
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun pump() {
        if (player != null) return
        main.removeCallbacks(giveUp)
        val file = clips.remove(next)
        if (file == null) {
            // A clip lost on the way (or never made) would leave the reply "speaking" for good.
            if (next < count) main.postDelayed(giveUp, CLIP_WITHIN_MS)
            return
        }
        speakingNow(true)
        val media = MediaPlayer()
        player = media
        playing = file
        fun finished() {
            if (player === media) _sounding.value = false
            media.release()
            file.delete()
            if (player === media) {
                player = null
                playing = null
            }
            next++
            if (next >= count) speakingNow(false) else pump()
        }
        try {
            media.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener { finished() }
            media.setOnErrorListener { _, _, _ ->
                finished()
                true
            }
            media.setOnPreparedListener {
                it.start()
                if (player === media) _sounding.value = true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            finished()
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        speakingNow(true)
        engine.language = Locale.getDefault()
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _sounding.value = true
                }

                override fun onDone(utteranceId: String?) {
                    main.post { speakingNow(false) }
                    _sounding.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { speakingNow(false) }
                    _sounding.value = false
                }
            },
        )
        // The engine takes at most getMaxSpeechInputLength() at a time; the rest of a long reply waits in its queue.
        val most = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(MAX_TTS_PIECE)
        text.chunked(most).forEachIndexed { i, piece ->
            engine.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "reply-$i")
        }
    }

    private fun extensionOf(mimeType: String) = when {
        "ogg" in mimeType || "opus" in mimeType -> "ogg"
        "wav" in mimeType -> "wav"
        "flac" in mimeType -> "flac"
        else -> "mp3"
    }

    private companion object {
        const val MAX_TTS_PIECE = 3_000

        /** How long the next clip may take after the last one ended: the gateway makes one in a few seconds. */
        const val CLIP_WITHIN_MS = 20_000L
        /** Fork: outputs that are earphones or a headset, not a speaker. */
        val EARPHONES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val AUDIBLE = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
