package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.LIVE_SPEECH_LEVEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §3.3): the watch's own call silence, from what it measures (the voice playing, its microphone,
 * its link) and what the phone tells it ([WatchCallState]), on a fake monotonic clock, as WatchLink's poll runs it.
 */
class WatchSilenceTest {
    private var time = 0L
    private val silence = WatchSilence { time }
    private var state: WatchCallState? = WatchCallState("c", CallStage.Live, silenceSeconds = 20)

    /** Polls every 100 ms for [ms]; true if it expired on the way. */
    private fun run(ms: Long, speaking: Boolean = false, mic: Float = 0f, linked: Boolean = true): Boolean {
        val until = time + ms
        while (time < until) {
            time += 100
            if (silence.sample(state, speaking, mic, linked)) return true
        }
        return false
    }

    @Test
    fun itCountsFromTheSessionBeingReadyWithThePhonesSetting() {
        state = WatchCallState("c", CallStage.Connecting)
        silence.event("session.started")
        // Connecting (no Live word from the phone yet): nothing counts.
        assertFalse(run(60_000))
        state = WatchCallState("c", CallStage.Live, silenceSeconds = 20)
        assertFalse(run(19_900))
        assertTrue(run(200))
        assertEquals(20, silence.seconds(state))
    }

    @Test
    fun beforeTheSessionIsReadyNothingCounts() {
        assertFalse(run(120_000))
        silence.event("session.started")
        assertFalse(run(19_900))
        assertTrue(run(200))
    }

    @Test
    fun theVoiceYourVoiceAndWordsKeepItGoing() {
        silence.event("session.started")
        repeat(30) {
            assertFalse(run(15_000))
            assertFalse(run(300, speaking = true))
            assertFalse(run(15_000))
            assertFalse(run(300, mic = LIVE_SPEECH_LEVEL))
            assertFalse(run(15_000))
            silence.event("session.input_transcript.delta")
        }
        assertTrue(time > 5 * 60_000L)
        assertTrue(run(20_100))
    }

    @Test
    fun hermesWorkingAndALinkDownHoldItButAQuestionWaitingDoesnt() {
        silence.event("session.started")
        state = state!!.copy(thinking = true)
        assertFalse(run(180_000))
        // Its turn waits on the person: their silence.
        state = state!!.copy(waiting = true)
        assertFalse(run(19_900))
        assertTrue(run(200))
        val other = WatchSilence { time }
        state = WatchCallState("c", CallStage.Live, silenceSeconds = 20)
        other.event("session.started")
        var ended = false
        repeat(1_200) { time += 100; ended = ended || other.sample(state, speaking = false, micLevel = 0f, linked = false) }
        assertFalse(ended)
    }

    @Test
    fun mutedTheRoomIsntSpeechAndAnOlderPhoneMeansTheDefault() {
        silence.event("session.started")
        state = state!!.copy(muted = true)
        assertTrue(run(20_100, mic = 0.8f))
        val old = WatchSilence { time }
        state = WatchCallState("c", CallStage.Live)
        old.event("session.started")
        assertEquals(10, old.seconds(state))
        var at = 0
        while (!old.sample(state, false, 0f, true)) { time += 100; at += 100 }
        assertEquals(10_000, at)
    }
}
