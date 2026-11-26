package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.ui.voice.TalkFixtures.Gateway
import dev.hermeskotlin.ui.voice.TalkFixtures.KeepAlive
import dev.hermeskotlin.ui.voice.TalkFixtures.Player
import dev.hermeskotlin.ui.voice.TalkFixtures.Recorder
import dev.hermeskotlin.ui.voice.TalkFixtures.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fork: Talk (Phone Live off) on the real [VoiceController], a real chat on a gateway socket, and the earphones'
 * button ([VoiceButton]): recorded until it's sent or dropped, sent once, read, then Ready with the microphone shut.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManualTalkTest {
    @BeforeTest fun clean() = VoiceButton.reset()
    @AfterTest fun cleanUp() = VoiceButton.reset()

    private class Talk(val voice: VoiceController, val socket: Socket, val gateway: Gateway, val recorder: Recorder, val player: Player, val keepAlive: KeepAlive)

    private suspend fun TestScope.talk(
        recorder: Recorder = Recorder(),
        live: Boolean = false,
        start: VoiceMode? = VoiceMode.Manual,
        readyAway: () -> Boolean = { false },
    ): Talk {
        val gateway = Gateway(live)
        val socket = Socket()
        val session = talkSession(gateway, socket)
        val player = Player()
        val keepAlive = KeepAlive()
        val voice = VoiceController(gateway.audio, recorder, player, backgroundScope, backgroundScope, keepAlive = keepAlive, speechStreams = { _, _ -> null }, liveCalls = LiveCalls { null })
        VoiceButton.bind(this, voice) { voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual, readyAway = readyAway) }
        if (start != null) voice.startChat(session, TalkFixtures.gateway, "rin", mode = start, readyAway = readyAway)
        return Talk(voice, socket, gateway, recorder, player, keepAlive)
    }

    /** One request: recorded until Send, sent once, its reply read, then Ready. */
    private suspend fun Talk.request(reply: String = "Dimming.") {
        val before = socket.submits.value.size
        voice.chat.first { it.phase == VoicePhase.Listening && it.hearing }
        voice.sendNow()
        socket.submits.first { it.size == before + 1 }
        socket.reply(reply)
        voice.chat.first { it.phase == VoicePhase.Ready }
    }

    @Test
    fun aRecordingGoesOnUntilSentThenTheReplyIsReadAndItWaitsReady() = runTest {
        val talk = talk()
        talk.recorder.recordings.first { it == 1 }
        // No pause, silence or limit ends it: it's the one recording, still under way, ten minutes on.
        assertEquals(MANUAL_TALK, talk.recorder.activities.single())
        assertEquals(Long.MAX_VALUE, MANUAL_TALK.silenceMs)
        assertEquals(0L, MANUAL_TALK.idleMs)
        assertEquals(Long.MAX_VALUE, MANUAL_TALK.maxMs)
        advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Listening, talk.voice.chat.value.phase)
        assertTrue(talk.voice.chat.value.manual)
        assertTrue(talk.socket.submits.value.isEmpty(), "sent before Send")

        talk.request()
        assertEquals(1, talk.socket.submits.value.size)
        assertTrue("Turn the lights down" in talk.socket.submits.value.single())
        assertTrue(talk.player.played.value >= 1, "the reply wasn't read")
        // Ready: nothing records on its own, however long it waits; the request's audio was let go.
        advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Ready, talk.voice.chat.value.phase)
        assertEquals(1, talk.recorder.recordings.value)
        assertEquals(listOf(false), talk.player.routes)
        assertEquals(1, talk.player.closed)
        // Nothing listened over the reply, talk-over on or not: one recording, the one asked for.
        assertFalse("/api/config" in talk.gateway.paths)
        assertFalse("/api/audio/voice-live/status" in talk.gateway.paths, "Talk asked about a live call")
        talk.voice.end()
    }

    @Test
    fun endDropsTheRecordingUnsentAndEndAtReadyClosesTheChat() = runTest {
        val talk = talk()
        talk.request()
        talk.voice.talk()
        talk.recorder.recordings.first { it == 2 }
        talk.voice.chat.first { it.phase == VoicePhase.Listening }

        talk.voice.end()
        talk.voice.chat.first { it.phase == VoicePhase.Ready }
        assertEquals(1, talk.recorder.dropped.value)
        advanceTimeBy(60_000L)
        testScheduler.runCurrent()
        assertEquals(1, talk.socket.submits.value.size, "a dropped recording was sent")
        assertEquals(2, talk.recorder.recordings.value)

        talk.voice.end()
        talk.voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(null, talk.voice.chat.value.error)
    }

    @Test
    fun endWhileHermesWorksLetsTheReplyGoWithoutStoppingItAndWaitsReady() = runTest {
        val talk = talk()
        talk.voice.chat.first { it.hearing }
        talk.voice.sendNow()
        talk.socket.submits.first { it.size == 1 }
        talk.socket.event("message.start")
        talk.voice.chat.first { it.phase == VoicePhase.Thinking }

        talk.voice.end()
        talk.voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.runCurrent()
        assertFalse("session.interrupt" in talk.socket.methods, "End stopped Hermes's turn")
        assertEquals(1, talk.socket.submits.value.size)
        // The next press records the next request.
        talk.voice.talk()
        talk.recorder.recordings.first { it == 2 }
        talk.voice.stopChat()
    }

    @Test
    fun theEarphonesStartSendRecordAgainAndDropAQuietRecording() = runTest {
        // The second recording never hears anyone.
        val talk = talk(Recorder(hears = { it != 2 }), start = null)
        var at = 100L
        fun press() = VoiceButton.press(VoiceKey.PlayPause, true, 0, at++, 7)

        // Voice off, the chat on screen: the press is Talk's, and records at once.
        assertTrue(press())
        talk.recorder.recordings.first { it == 1 }
        talk.voice.chat.first { it.hearing }
        // Heard: the press sends it.
        assertTrue(press())
        talk.socket.submits.first { it.size == 1 }
        talk.socket.reply("Dimming.")
        talk.voice.chat.first { it.phase == VoicePhase.Ready }
        // Ready: the press records the next.
        assertTrue(press())
        talk.recorder.recordings.first { it == 2 }
        talk.voice.chat.first { it.phase == VoicePhase.Listening }
        // Nothing heard: the press drops it, and Talk is Ready again.
        assertTrue(press())
        talk.voice.chat.first { it.phase == VoicePhase.Ready }
        assertEquals(1, talk.socket.submits.value.size)
        assertEquals(1, talk.recorder.dropped.value)
        talk.voice.stopChat()
    }

    @Test
    fun aPressTableForTalk() {
        val manual = VoiceChatState(manual = true)
        assertEquals(VoicePress.Talk, voicePress(manual.copy(phase = VoicePhase.Ready), canStart = true))
        assertEquals(VoicePress.Send, voicePress(manual.copy(phase = VoicePhase.Listening, hearing = true), canStart = true))
        assertEquals(VoicePress.End, voicePress(manual.copy(phase = VoicePhase.Listening), canStart = true))
        assertEquals(VoicePress.End, voicePress(manual.copy(phase = VoicePhase.Transcribing), canStart = true))
        assertEquals(VoicePress.End, voicePress(manual.copy(phase = VoicePhase.Thinking), canStart = true))
        assertEquals(VoicePress.End, voicePress(manual.copy(phase = VoicePhase.Speaking), canStart = true))
        assertEquals(VoicePress.End, voicePress(VoiceChatState(VoicePhase.Listening, live = true), canStart = true))
        assertEquals(VoicePress.Start, voicePress(VoiceChatState(), canStart = true))
    }

    @Test
    fun readyOutOfSightEndsUnlessTheBackgroundButtonKeepsIt() = runTest {
        val off = talk()
        off.request()
        off.voice.onBackground()
        off.voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(1, off.keepAlive.releases, "the microphone service wasn't let go")

        VoiceButton.reset()
        var keep = true
        val on = talk(readyAway = { keep })
        on.request()
        on.voice.onBackground()
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Ready, on.voice.chat.value.phase)
        // Out of sight, a press records the next request, in the call service held since the chat began.
        assertTrue(VoiceButton.press(VoiceKey.PlayPause, true, 0, 500, 7))
        on.recorder.recordings.first { it == 2 }
        // Turned off meanwhile: once Ready again out of sight, the chat ends and lets the button go.
        keep = false
        on.voice.chat.first { it.hearing }
        on.voice.sendNow()
        on.socket.submits.first { it.size == 2 }
        on.socket.reply("Done.")
        on.voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(1, on.keepAlive.releases)
    }

    @Test
    fun aRequestUnderWayOutOfSightGoesOnAndItsChatEndsOnceReady() = runTest {
        val talk = talk()
        talk.voice.chat.first { it.hearing }
        talk.voice.onBackground()
        testScheduler.runCurrent()
        // The recording isn't cut off by leaving: it's sent when asked, and read.
        assertEquals(VoicePhase.Listening, talk.voice.chat.value.phase)
        talk.voice.sendNow()
        talk.socket.submits.first { it.size == 1 }
        talk.socket.reply("Dimming.")
        talk.voice.chat.first { it.phase == VoicePhase.Off }
        assertTrue(talk.player.played.value >= 1)
    }

    @Test
    fun backInSightReadyNoLongerDependsOnTheSetting() = runTest {
        val talk = talk()
        talk.voice.chat.first { it.hearing }
        talk.voice.onBackground()
        talk.voice.onForeground()
        talk.request()
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Ready, talk.voice.chat.value.phase)
        talk.voice.stopChat()
    }

    @Test
    fun phoneLiveOnIsALiveCallOrItsReasonNeverTalk() = runTest {
        // The gateway's profile isn't on GPT-Live: no call, and no chained chat in its place.
        val talk = talk(start = VoiceMode.Live)
        talk.voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
        testScheduler.runCurrent()
        assertNotNull(talk.voice.chat.value.error)
        assertEquals(0, talk.recorder.recordings.value)
        assertTrue(talk.socket.submits.value.isEmpty())
        assertTrue("/api/audio/voice-live/status" in talk.gateway.paths)
    }

    @Test
    fun bothSwitchesStartOffAndPickTheMode() {
        assertFalse(AppSettings().phoneLive)
        assertFalse(AppSettings().backgroundEarphoneButton)
        assertEquals(VoiceMode.Manual, phoneVoiceMode(phoneLive = false))
        assertEquals(VoiceMode.Live, phoneVoiceMode(phoneLive = true))
    }
}
