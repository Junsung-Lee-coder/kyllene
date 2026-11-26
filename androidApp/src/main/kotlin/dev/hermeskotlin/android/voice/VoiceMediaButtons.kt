package dev.hermeskotlin.android.voice

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import dev.hermeskotlin.ui.voice.VoiceButton
import dev.hermeskotlin.ui.voice.VoiceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Fork: the earphones' buttons that are the voice toggle ([VoiceButton]); any other key isn't. */
internal fun voiceKey(keyCode: Int): VoiceKey? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY -> VoiceKey.Play
    KeyEvent.KEYCODE_MEDIA_PAUSE -> VoiceKey.Pause
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> VoiceKey.PlayPause
    KeyEvent.KEYCODE_MEDIA_STOP -> VoiceKey.Stop
    else -> null
}

/**
 * Fork: one key event to [VoiceButton]: true when the chat's voice took it. Known by its device and the time its
 * press went down, so the same press told again (down, up, repeats, or by both the activity and the session) is one.
 */
internal fun voiceKeyEvent(event: KeyEvent, press: (VoiceKey, Boolean, Int, Long, Int) -> Boolean = VoiceButton::press): Boolean {
    val key = voiceKey(event.keyCode) ?: return false
    if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
    val downTime = event.downTime.takeIf { it > 0 } ?: event.eventTime
    return press(key, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, downTime, event.deviceId)
}

/**
 * Fork: holds the earphones' button while the chat's voice is on, so it still ends or sends with the app out of sight
 * or the screen off. With the app in front, the activity gets the key first ([voiceKeyEvent] from onKeyDown and onKeyUp).
 * Inactive otherwise, so Read aloud's session and music apps keep the button as before.
 */
class VoiceMediaSession(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val session = MediaSession(context.applicationContext, "Kyllene voice").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val key = mediaButtonIntent.keyEvent() ?: return false
                return voiceKeyEvent(key) || super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
    }

    private val following = scope.launch {
        VoiceButton.voiceOn.distinctUntilChanged().collect { on ->
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED, 0L, 1f)
                    .build(),
            )
            session.isActive = on
        }
    }

    override fun close() {
        following.cancel()
        session.isActive = false
        session.release()
    }
}

@Suppress("DEPRECATION")
private fun Intent.keyEvent(): KeyEvent? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) else getParcelableExtra(Intent.EXTRA_KEY_EVENT)
