package dev.hermeskotlin.ui.voice

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
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
fun voiceKeyEvent(event: KeyEvent, press: (VoiceKey, Boolean, Int, Long, Int) -> Boolean = VoiceButton::press): Boolean {
    val key = voiceKey(event.keyCode) ?: return false
    if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
    val downTime = event.downTime.takeIf { it > 0 } ?: event.eventTime
    return press(key, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, downTime, event.deviceId)
}

/**
 * Fork: holds the earphones' button ([VoiceButton.holding]):
 * - while the chat's voice is on, so it still ends, sends or (Talk at Ready) records with the app out of sight or the
 *   screen off;
 * - while a chat screen is resumed with voice off, so a press starts Talk rather than music, by whichever way Android
 *   hands it over: the activity's keys ([voiceKeyEvent] from onKeyDown and onKeyUp) or this session. The same press
 *   told both ways counts once. Becoming active starts nothing.
 *
 * Inactive otherwise, and while Read aloud reads with voice off, so Read aloud's session and music apps keep the
 * button.
 *
 * Out of sight, Android sends a press to the last app with a media session that played audio
 * (https://developer.android.com/media/legacy/media-buttons): to Herald once it has just read a reply, but to music
 * that has played since. Talk at Ready stays on out of sight only with Settings › Background earphone button
 * ([VoiceController.onBackground]); off, the chat ends there and this session lets the button go.
 */
class VoiceMediaSession(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val key = mediaButtonIntent.keyEvent() ?: return false
            return voiceKeyEvent(key) || super.onMediaButtonEvent(mediaButtonIntent)
        }
    }

    private val session = MediaSession(context.applicationContext, "Kyllene voice").apply { setCallback(callback) }

    /** Holding the button now; for tests. */
    internal val active: Boolean get() = session.isActive

    /** A media button as Android hands it to the session; for tests. */
    internal fun mediaButton(intent: Intent): Boolean = callback.onMediaButtonEvent(intent)

    private val following = scope.launch {
        combine(VoiceButton.voiceOn, VoiceButton.holding) { on, holding -> on to holding }.distinctUntilChanged().collect { (on, holding) ->
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    // Voice off but held for the chat in front: ready to play, not playing.
                    .setState(
                        when {
                            on -> PlaybackState.STATE_PLAYING
                            holding -> PlaybackState.STATE_PAUSED
                            else -> PlaybackState.STATE_STOPPED
                        },
                        0L,
                        1f,
                    )
                    .build(),
            )
            session.isActive = holding
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
