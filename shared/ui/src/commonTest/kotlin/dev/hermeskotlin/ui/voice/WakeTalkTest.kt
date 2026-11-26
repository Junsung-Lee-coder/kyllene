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
        VoiceButton.bind(this, voice, wakeStart = { ms -> voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual, wakeEndMs = ms) }) {
            voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual)
        }
        // No chat in front: nothing takes the wake word.
        assertFalse(VoiceButton.wake(1_500))
        assertFalse(VoiceButton.canWake)
        VoiceButton.resumed(this, true)
        assertTrue(VoiceButton.canWake)
        assertFalse(VoiceMedia.busyNow())

        // Heard with no voice on: the chat starts, and its first recording ends on 1.5 s of quiet.
        assertTrue(VoiceButton.wake(1_500))
        recorder.recordings.first { it == 1 }
        assertEquals(wakeActivity(1_500), recorder.activities.last())
        voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(VoiceMedia.busyNow(), "recording holds the microphone")
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
        assertFalse(VoiceMedia.busyNow(), "Ready leaves the microphone free")

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
    fun anOutOfRangeSettingIsUsedInRangeAndNothingCarriesOver() = runTest {
        val recorder = Recorder()
        val socket = Socket()
        val session = talkSession(Gateway(), socket)
        val voice = VoiceController(Gateway().audio, recorder, Player(), backgroundScope, backgroundScope, keepAlive = KeepAlive(), speechStreams = { _, _ -> null }, liveCalls = LiveCalls { null })
        voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual, wakeEndMs = 60_000)
        recorder.recordings.first { it == 1 }
        assertEquals(wakeActivity(10_000), recorder.activities.single())
        // A wake word while it records isn't taken, and arms nothing.
        assertFalse(voice.wakeTalk(1_000))
        voice.end()
        voice.chat.first { it.phase == VoicePhase.Ready }
        // The Mic press by hand: Talk's own recording, never the wake word's quiet spell.
        voice.talk()
        recorder.recordings.first { it == 2 }
        assertEquals(MANUAL_TALK, recorder.activities.last())
        voice.end()
        voice.chat.first { it.phase == VoicePhase.Ready }
        voice.stopChat()
        voice.chat.first { it.phase == VoicePhase.Off }
        // A later chat started by hand: as ever.
        voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual)
        recorder.recordings.first { it == 3 }
        assertEquals(MANUAL_TALK, recorder.activities.last())
        voice.end()
    }

    @Test
    fun theComposersDictationKeepsTheWakeWordFromStartingVoice() = runTest {
        val listening = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dictation = object : dev.hermeskotlin.core.voice.DeviceDictation {
            override fun available() = true
            override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String {
                onLevel(0.3f)
                listening.await()
                return "type this"
            }
            override fun finish() { listening.complete(Unit) }
        }
        val recorder = Recorder()
        val session = talkSession(Gateway(), Socket())
        val voice = VoiceController(Gateway().audio, recorder, Player(), backgroundScope, backgroundScope, deviceDictation = dictation, keepAlive = KeepAlive(), speechStreams = { _, _ -> null }, liveCalls = LiveCalls { null })
        var wakeStarts = 0
        VoiceButton.bind(this, voice, wakeStart = { wakeStarts++ }) {}
        VoiceButton.resumed(this, true)
        voice.startDictation(TalkFixtures.gateway, null, dev.hermeskotlin.core.settings.DictationEngine.Device) {}
        voice.dictation.first { it.active }
        // The chat's own voice is Off while the composer dictates, but the microphone is taken.
        assertEquals(VoicePhase.Off, voice.chat.value.phase)
        assertTrue(VoiceMedia.busyNow())
        assertFalse(VoiceButton.wake(2_000))
        assertEquals(0, wakeStarts)
        assertEquals(0, recorder.recordings.value)
        voice.finishDictation()
        voice.dictation.first { !it.active }
        assertFalse(VoiceMedia.busyNow())
        assertTrue(VoiceButton.wake(2_000))
        assertEquals(1, wakeStarts)
    }
}
