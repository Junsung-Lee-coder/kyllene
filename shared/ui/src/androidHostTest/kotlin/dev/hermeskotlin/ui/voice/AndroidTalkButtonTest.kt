package dev.hermeskotlin.ui.voice

import android.content.Intent
import android.view.KeyEvent
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.ui.voice.TalkFixtures.Gateway
import dev.hermeskotlin.ui.voice.TalkFixtures.KeepAlive
import dev.hermeskotlin.ui.voice.TalkFixtures.Player
import dev.hermeskotlin.ui.voice.TalkFixtures.Recorder
import dev.hermeskotlin.ui.voice.TalkFixtures.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork: the earphones' button for Talk on Android, through the real [VoiceMediaSession] (a framework `MediaSession` on
 * Robolectric) and the key path the activity's onKeyDown takes ([voiceKeyEvent]), driving the real [VoiceController].
 * The session holds the button only while a chat's voice is on; Talk at Ready out of sight keeps it only with the
 * background earphone button on.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTalkButtonTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun clean() = VoiceButton.reset()
    @After fun cleanUp() = VoiceButton.reset()

    private fun key(code: Int, action: Int, downTime: Long) = KeyEvent(downTime, downTime, action, code, 0)
    private fun button(event: KeyEvent) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)

    private fun talk(readyAway: Boolean, check: suspend TestScope.(VoiceController, Recorder, Socket, VoiceMediaSession) -> Unit) = runTest {
        val gateway = Gateway()
        val socket = Socket()
        val session = talkSession(gateway, socket)
        val recorder = Recorder()
        val voice = VoiceController(
            gateway.audio, recorder, Player(), backgroundScope, backgroundScope,
            keepAlive = KeepAlive(), speechStreams = { _, _ -> null }, liveCalls = LiveCalls { null },
        )
        VoiceButton.bind(this, voice) { voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual, readyAway = { readyAway }) }
        val buttons = VoiceMediaSession(context, backgroundScope)
        try {
            testScheduler.runCurrent()
            // Voice off: the button stays with music and Read aloud.
            assertFalse(buttons.active)

            // In front, the activity's key: Talk records at once.
            assertTrue(voiceKeyEvent(key(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, 100)))
            assertTrue(voiceKeyEvent(key(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_UP, 100)))
            recorder.recordings.first { it == 1 }
            voice.chat.first { it.hearing }
            testScheduler.runCurrent()
            assertTrue(buttons.active)

            // Out of sight now, through the session: the press sends it, and the reply is read.
            voice.onBackground()
            assertTrue(buttons.mediaButton(button(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 200))))
            assertTrue(buttons.mediaButton(button(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP, 200))))
            socket.submits.first { it.size == 1 }
            socket.reply("Dimming.")
            check(voice, recorder, socket, buttons)
        } finally {
            buttons.close()
            voice.stopChat()
        }
    }

    @Test
    fun withTheBackgroundButtonTalkWaitsReadyOutOfSightAndAPressRecordsTheNext() = talk(readyAway = true) { voice, recorder, _, buttons ->
        voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.runCurrent()
        assertTrue(buttons.active)
        assertTrue(buttons.mediaButton(button(key(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_DOWN, 300))))
        recorder.recordings.first { it == 2 }
        assertEquals(VoicePhase.Listening, voice.chat.value.phase)
    }

    @Test
    fun withoutItTalkEndsOnceReadyOutOfSightAndTheSessionLetsTheButtonGo() = talk(readyAway = false) { voice, recorder, _, buttons ->
        voice.chat.first { it.phase == VoicePhase.Off }
        // The session follows the chat's voice: off, it holds nothing, so music gets the button.
        testScheduler.runCurrent()
        assertFalse(buttons.active)
        assertEquals(1, recorder.recordings.value)
    }
}
