package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.custom.SpeechGate
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork (requirements §6.1): the phone's Talk reads a reply only if no text was sent after its request, on the real
 * [VoiceController] and a real chat over a gateway socket, with Hermes's replies held back by the test. Text sent from
 * the phone's chat is told to the [SpeechGate] as the chat's Send does (ChatViewModel.textSent). Fixtures, not audio.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechPolicyTest {
    @BeforeTest fun clean() = VoiceButton.reset()
    @AfterTest fun cleanUp() = VoiceButton.reset()

    /** Plays a clip until it's let go ([hold]) or stopped; counts what started and what was cut off. */
    private class Player : SpeechPlayer {
        val started = MutableStateFlow(0)
        val stopped = MutableStateFlow(0)
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun play(audio: SpokenAudio) {
            started.update { it + 1 }
            try {
                hold?.await()
            } catch (e: CancellationException) {
                stopped.update { it + 1 }
                throw e
            }
        }

        override fun chainedChatAudio(talkOver: Boolean, onHeadsetLost: () -> Unit): () -> Unit = {}
    }

    private class Talk(val voice: VoiceController, val socket: TalkFixtures.Socket, val recorder: TalkFixtures.Recorder, val player: Player)

    private suspend fun TestScope.talk(gate: SpeechGate): Talk {
        val gateway = TalkFixtures.Gateway()
        val socket = TalkFixtures.Socket()
        val session = talkSession(gateway, socket)
        val recorder = TalkFixtures.Recorder()
        val player = Player()
        val voice = VoiceController(gateway.audio, recorder, player, backgroundScope, backgroundScope, speechStreams = { _, _ -> null }, speechGate = gate)
        voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual)
        return Talk(voice, socket, recorder, player)
    }

    /** Records and sends one request (the [n]th recording), leaving its reply to the test. */
    private suspend fun Talk.ask(n: Int) {
        if (n > 1) {
            voice.chat.first { it.phase == VoicePhase.Ready }
            voice.talk()
        }
        recorder.recordings.first { it == n }
        voice.chat.first { it.phase == VoicePhase.Listening }
        voice.sendNow()
        socket.submits.first { it.size == n }
    }

    @Test
    fun textAfterAVoiceRequestKeepsItsLateReplySilent() = runTest {
        val gate = SpeechGate { "acct-A" }
        val t = talk(gate)
        t.ask(1)
        // Text sent from the phone's chat while Hermes works on the spoken request.
        gate.textInput(TalkFixtures.gateway)
        t.socket.reply("Dimming the lights now.")
        t.voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.runCurrent()
        assertEquals(0, t.player.started.value, "the late reply was read after text")
        t.voice.stopChat()
    }

    @Test
    fun textStopsAReplyBeingReadAtOnce() = runTest {
        val gate = SpeechGate { "acct-A" }
        val t = talk(gate)
        t.player.hold = CompletableDeferred()
        t.ask(1)
        t.socket.reply("Dimming the lights now.")
        t.player.started.first { it == 1 }
        assertEquals(VoicePhase.Speaking, t.voice.chat.value.phase)

        gate.textInput(TalkFixtures.gateway)
        t.player.stopped.first { it == 1 }
        t.voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.runCurrent()
        assertEquals(1, t.player.started.value)
        t.voice.stopChat()
    }

    @Test
    fun aVoiceRequestAfterTextIsReadAndTheSilencedReplyIsntReplayed() = runTest {
        val gate = SpeechGate { "acct-A" }
        val t = talk(gate)
        t.ask(1)
        gate.textInput(TalkFixtures.gateway)
        t.socket.reply("Old reply.")
        t.voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.runCurrent()
        assertEquals(0, t.player.started.value)

        // A new spoken request: its reply is read, once; the old one isn't brought back.
        t.ask(2)
        t.socket.reply("New reply.")
        t.player.started.first { it == 1 }
        t.voice.chat.first { it.phase == VoicePhase.Ready }
        testScheduler.runCurrent()
        assertEquals(1, t.player.started.value)
        t.voice.stopChat()
    }

    @Test
    fun anotherAccountsOrGatewaysTextLeavesTheReplyAlone() = runTest {
        var owner = "acct-A"
        val gate = SpeechGate { owner }
        val t = talk(gate)
        t.ask(1)
        owner = "acct-B"
        gate.textInput(TalkFixtures.gateway)
        owner = "acct-A"
        gate.textInput(GatewayUrl.parse("https://other.example.ts.net"))
        t.socket.reply("Dimming the lights now.")
        t.player.started.first { it == 1 }
        t.voice.chat.first { it.phase == VoicePhase.Ready }
        assertTrue(t.player.started.value >= 1)
        t.voice.stopChat()
    }

    @Test
    fun withoutAGateEveryReplyIsReadAsBefore() = runTest {
        val gateway = TalkFixtures.Gateway()
        val socket = TalkFixtures.Socket()
        val session = talkSession(gateway, socket)
        val recorder = TalkFixtures.Recorder()
        val player = Player()
        val voice = VoiceController(gateway.audio, recorder, player, backgroundScope, backgroundScope, speechStreams = { _, _ -> null })
        voice.startChat(session, TalkFixtures.gateway, "rin", mode = VoiceMode.Manual)
        Talk(voice, socket, recorder, player).ask(1)
        socket.reply("Dimming the lights now.")
        player.started.first { it == 1 }
        voice.stopChat()
    }
}
