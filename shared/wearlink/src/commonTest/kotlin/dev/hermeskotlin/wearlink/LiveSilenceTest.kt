package dev.hermeskotlin.wearlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork (requirements §3.3): a live call's silence, on a fake monotonic clock. */
class LiveSilenceTest {
    private var time = 0L
    private fun silence(seconds: Int = 10) = LiveSilence(seconds) { time }

    /** One poll every 100 ms for [ms], as the calls do; true if it expired on the way. */
    private fun LiveSilence.run(ms: Long, playing: Boolean = false, micLevel: Float = 0f, muted: Boolean = false, working: Boolean = false, linked: Boolean = true): Boolean {
        val until = time + ms
        while (time < until) {
            time += 100
            if (sample(playing, micLevel, muted, working, linked)) return true
        }
        return false
    }

    @Test
    fun silenceEndsAtTheSettingFromTheSessionBeingReadyNotBefore() {
        val s = silence(10)
        // Dialling: nothing counts before the session is ready.
        assertFalse(s.run(120_000))
        s.start()
        assertFalse(s.run(9_900))
        assertTrue(s.run(100))
    }

    @Test
    fun speechAndPlaybackKeepACallGoingFarPastFiveMinutes() {
        val s = silence(10)
        s.start()
        repeat(40) {
            // Nine seconds quiet, then a word, then the voice answering: never ten in a row.
            assertFalse(s.run(9_000))
            assertFalse(s.run(300, micLevel = LIVE_SPEECH_LEVEL))
            assertFalse(s.run(9_000))
            assertFalse(s.run(500, playing = true))
        }
        assertTrue(time > 5 * 60_000L)
        assertTrue(s.run(10_000))
    }

    @Test
    fun roomNoiseBelowTheSpeechLevelIsSilence() {
        val s = silence(5)
        s.start()
        assertTrue(s.run(5_000, micLevel = LIVE_SPEECH_LEVEL / 2))
    }

    @Test
    fun hermesWorkingAndALinkDownHoldItAndItStartsAgainAfter() {
        val s = silence(10)
        s.start()
        assertFalse(s.run(9_000))
        assertFalse(s.run(300_000, working = true))
        // From zero once it's over: the first look after counts as its start.
        assertFalse(s.run(10_000))
        assertTrue(s.run(100))
        val t = silence(10)
        t.start()
        assertFalse(t.run(120_000, linked = false))
        assertFalse(t.run(10_000))
        assertTrue(t.run(100))
    }

    @Test
    fun mutedTheMicrophoneIsSilenceAndWaitingForAnAnswerRunsToo() {
        val s = silence(10)
        s.start()
        // Muted: what the microphone picks up doesn't count.
        assertTrue(s.run(10_000, micLevel = 0.5f, muted = true))
        // Waiting for the person's answer isn't Hermes working: the caller doesn't hold it, so it runs.
        val t = silence(10)
        t.start()
        assertTrue(t.run(10_000, working = false))
    }

    @Test
    fun transcriptsAreActivityAndBeforeStartNothingCounts() {
        val s = silence(10)
        s.activity()
        assertFalse(s.started)
        s.start()
        s.run(9_000)
        s.activity()
        assertFalse(s.run(9_900))
        assertTrue(s.run(100))
    }

    @Test
    fun settingsAreWholeSecondsFromOneToSixtyTenByDefault() {
        assertEquals(10, LIVE_SILENCE_DEFAULT_SECONDS)
        assertEquals(10, liveSilenceSeconds(null))
        assertEquals(1, liveSilenceSeconds(0))
        assertEquals(1, liveSilenceSeconds(-5))
        assertEquals(60, liveSilenceSeconds(61))
        assertEquals(37, liveSilenceSeconds(37))
        for (ok in listOf("1", "10", "60", " 45 ")) assertEquals(ok.trim().toInt(), parseLiveSilence(ok))
        for (bad in listOf("", "0", "61", "100", "-1", "1.5", "ten", "007")) assertNull(parseLiveSilence(bad), bad)
        // 1 second, exactly.
        val s = LiveSilence(1) { time }
        s.start()
        assertTrue(s.run(1_000))
    }

    @Test
    fun theSettingTravelsWithTheCallAndOlderPeersStillRead() {
        val update = CallUpdate("c", CallPhase.Live, silenceSeconds = 25, waiting = true)
        val back = WearJson.decode<CallUpdate>(WearJson.encode(update))!!
        assertEquals(25, back.silenceSeconds)
        assertTrue(back.waiting)
        // From an older phone: neither field, so the watch uses the default.
        val old = WearJson.decode<CallUpdate>("""{"callId":"c","phase":"Live"}""".encodeToByteArray())!!
        assertNull(old.silenceSeconds)
        assertFalse(old.waiting)
        assertEquals(10, liveSilenceSeconds(old.silenceSeconds))
        // Unset, neither is written: an older watch reads the same message as before.
        val plain = WearJson.encode(CallUpdate("c", CallPhase.Live)).decodeToString()
        assertFalse("silenceSeconds" in plain || "waiting" in plain, plain)
    }
}
