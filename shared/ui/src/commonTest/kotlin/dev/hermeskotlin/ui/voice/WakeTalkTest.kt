package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.ui.voice.TalkFixtures.Gateway
import dev.hermeskotlin.ui.voice.TalkFixtures.KeepAlive
import dev.hermeskotlin.ui.voice.TalkFixtures.Player
import dev.hermeskotlin.ui.voice.TalkFixtures.Recorder
import dev.hermeskotlin.ui.voice.TalkFixtures.Socket
import dev.hermeskotlin.wearlink.WAKE_IDLE_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §5): the phone's wake word on the real [VoiceController] and Talk, through [VoiceButton.wake] as
 * the app calls it: only the request the wake word started ends on its quiet spell; Talk's own stay manual; the chat
 * waits at Ready after the reply and records nothing more on its own.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WakeTalkTest {
    @BeforeTest fun clean() = VoiceButton.reset()
    @AfterTest fun cleanUp() = VoiceButton.reset()

    private fun wakeActivity(ms: Long) = VoiceActivity(silenceMs = ms, idleMs = WAKE_IDLE_MS, maxMs = Long.MAX_VALUE)

    @Test
    fun onlyTheWakeStartedRequestEndsOnItsQuietSpell() = runTest {
        val recorder = Recorder()
        val socket = Socket()
        val session = talkSession(Gateway(), socket)
        val player = Player()
        val voice = VoiceController(Gateway().audio, recorder, player, backgroundScope, backgroundScope, keepAlive = KeepAlive(), speechStreams = { _, _ -> null }, liveCalls = LiveCalls { null })
        VoiceButton.bind(this, voice) { voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual) }
        // No chat in front: nothing takes the wake word.
        assertFalse(VoiceButton.wake(1_500))
        assertFalse(VoiceButton.canWake)
        VoiceButton.resumed(this, true)
        assertTrue(VoiceButton.canWake)
        assertFalse(VoiceMedia.busy.value)

        // Heard with no voice on: the chat starts, and its first recording ends on 1.5 s of quiet.
        assertTrue(VoiceButton.wake(1_500))
        recorder.recordings.first { it == 1 }
        assertEquals(wakeActivity(1_500), recorder.activities.last())
        voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(VoiceMedia.busy.value, "recording holds the microphone")
        // While it records, the wake word has nothing to take.
        assertFalse(VoiceButton.wake(2_000))

        // Sent, read, Ready: and nothing records on its own after.
        val before = socket.submits.value.size
        voice.chat.first { it.hearing }
        voice.sendNow()
        socket.submits.first { it.size == before + 1 }
        socket.reply("Done.")
        voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(1, recorder.recordings.value)
        assertFalse(VoiceMedia.busy.value, "Ready leaves the microphone free")

        // The Mic button (or the earphones) at Ready: Talk's own recording, never ending by itself.
        voice.talk()
        recorder.recordings.first { it == 2 }
        assertEquals(MANUAL_TALK, recorder.activities.last())
        voice.end()
        voice.chat.first { it.phase == VoicePhase.Ready }

        // The wake word again at Ready: the next request, in the same chat, on the setting's quiet spell.
        assertTrue(VoiceButton.wake(3_000))
        recorder.recordings.first { it == 3 }
        assertEquals(wakeActivity(3_000), recorder.activities.last())
        voice.end()
        voice.chat.first { it.phase == VoicePhase.Ready }
        voice.end()
        voice.chat.first { it.phase == VoicePhase.Off }
    }

    @Test
    fun anOutOfRangeSettingIsUsedInRange() = runTest {
        val recorder = Recorder()
        val socket = Socket()
        val session = talkSession(Gateway(), socket)
        val voice = VoiceController(Gateway().audio, recorder, Player(), backgroundScope, backgroundScope, keepAlive = KeepAlive(), speechStreams = { _, _ -> null }, liveCalls = LiveCalls { null })
        voice.armWake(60_000)
        voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual)
        recorder.recordings.first { it == 1 }
        assertEquals(wakeActivity(10_000), recorder.activities.single())
        voice.end()
        voice.chat.first { it.phase == VoicePhase.Ready }
        // Stopped before the armed recording came: it doesn't carry over to a later chat.
        voice.armWake(1_000)
        voice.stopChat()
        voice.chat.first { it.phase == VoicePhase.Off }
        voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual)
        recorder.recordings.first { it == 2 }
        assertEquals(MANUAL_TALK, recorder.activities.last())
        voice.end()
    }
}
