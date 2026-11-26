package dev.hermeskotlin.wearlink

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork (requirements §5): the wake word's shared rules, and its listening loop over a fake recognizer, on test time. */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordTest {
    private val defaults = WakePhrases.parse("")

    @Test
    fun theDefaultListIsTheWholeCanonicalOne() {
        assertEquals(listOf("루미", "루미야", "룸이", "룸이야", "루미봇", "룸이봇", "룸미", "룸미야", "룸미봇"), defaults)
        assertEquals(defaults, WakePhrases.parse("   "))
        assertEquals("", WakePhrases.normalize("  "))
    }

    @Test
    fun phrasesKeepTheirLimits() {
        // Repeats once, case aside.
        assertEquals(listOf("Rumi", "hermes"), WakePhrases.parse("Rumi rumi hermes RUMI"))
        // Each cut to 40 characters.
        assertEquals(40, WakePhrases.parse("a".repeat(55)).single().length)
        // At most 24.
        assertEquals(24, WakePhrases.parse((1..30).joinToString(" ") { "w$it" }).size)
        // At most 240 characters in all: seven of 40 would be 280.
        val long = (1..7).joinToString(" ") { n -> n.toString().repeat(40) }
        val kept = WakePhrases.parse(long)
        assertEquals(6, kept.size)
        assertTrue(kept.sumOf { it.length } <= WakePhrases.MAX_TOTAL_CHARS)
    }

    @Test
    fun onlyAtTheStartOrAfterOneGreeting() {
        assertTrue(WakePhrases.matches("루미 오늘 날씨 어때", defaults))
        assertTrue(WakePhrases.matches("루미야", defaults))
        assertTrue(WakePhrases.matches("안녕, 루미!", defaults))
        assertTrue(WakePhrases.matches("hey 룸이", defaults))
        assertTrue(WakePhrases.matches("OK 루미봇", defaults))
        assertFalse(WakePhrases.matches("오늘 루미랑 얘기했어", defaults), "mid-sentence")
        assertFalse(WakePhrases.matches("hey hey 루미", defaults), "only one greeting")
        assertFalse(WakePhrases.matches("", defaults))
        assertFalse(WakePhrases.matches("루미나리에", defaults), "a longer word isn't the phrase")
        // `*` stands for characters inside one word.
        val custom = WakePhrases.parse("Herm*")
        assertTrue(WakePhrases.matches("hermes, what's up", custom))
        assertTrue(WakePhrases.matches("Herm.", custom))
        assertFalse(WakePhrases.matches("her mes", custom))
        assertTrue(WakePhrases.korean(defaults))
        assertFalse(WakePhrases.korean(custom))
    }

    @Test
    fun theRequestsQuietSpellIsHalfSecondsFromHalfToTen() {
        assertEquals(2_000, WakeEndSilence.DEFAULT_MS)
        assertEquals(500, WakeEndSilence.clamp(0))
        assertEquals(10_000, WakeEndSilence.clamp(60_000))
        assertEquals(1_500, WakeEndSilence.clamp(1_400))
        assertEquals(20, WakeEndSilence.choices.size)
        assertEquals(500, WakeEndSilence.choices.first())
        assertEquals(10_000, WakeEndSilence.choices.last())
    }

    @Test
    fun whenTheWatchAndThePhoneListen() {
        val off = WatchWakeSettings()
        val on = WatchWakeSettings(enabled = true)
        val outside = on.copy(background = true, screenOff = true, charging = true)
        assertFalse(WakeRules.watchScreenWindow(off, appVisible = true, serviceRunning = false))
        assertTrue(WakeRules.watchScreenWindow(on, appVisible = true, serviceRunning = false))
        assertFalse(WakeRules.watchScreenWindow(on, appVisible = false, serviceRunning = false), "outside needs the option and the service")
        assertTrue(WakeRules.watchScreenWindow(on.copy(background = true), appVisible = false, serviceRunning = true))
        assertFalse(WakeRules.watchContinuous(outside, serviceRunning = false, screenOn = false, charging = false), "nothing started it")
        assertTrue(WakeRules.watchContinuous(outside, serviceRunning = true, screenOn = false, charging = false))
        assertFalse(WakeRules.watchContinuous(outside, serviceRunning = true, screenOn = true, charging = false), "screen on: windows only")
        assertTrue(WakeRules.watchContinuous(outside, serviceRunning = true, screenOn = true, charging = true))
        assertFalse(WakeRules.watchContinuous(outside.copy(background = false), serviceRunning = true, screenOn = false, charging = true))
        // The phone out of its app.
        assertTrue(WakeRules.phoneBackgroundWindows(true, true, false, false, serviceRunning = true, appVisible = false, screenOn = true, charging = false))
        assertFalse(WakeRules.phoneBackgroundWindows(true, true, false, false, serviceRunning = true, appVisible = true, screenOn = true, charging = false), "in the app it listens itself")
        assertFalse(WakeRules.phoneBackgroundWindows(true, true, false, false, serviceRunning = true, appVisible = false, screenOn = false, charging = false))
        assertTrue(WakeRules.phoneBackgroundWindows(true, true, true, false, serviceRunning = true, appVisible = false, screenOn = false, charging = false))
        assertTrue(WakeRules.phoneBackgroundWindows(true, true, false, true, serviceRunning = true, appVisible = false, screenOn = false, charging = true))
        assertFalse(WakeRules.phoneBackgroundWindows(true, true, true, true, serviceRunning = false, appVisible = false, screenOn = true, charging = true), "never without its start")
        assertTrue(PHONE_WAKE_REST_MS > PHONE_WAKE_WINDOW_MS)
        assertEquals(30_000L, PHONE_WAKE_WINDOW_MS)
    }

    @Test
    fun oneUtteranceStartsOnce() {
        var time = 0L
        val claims = WakeClaims { time }
        assertTrue(claims.claim("watch-1"))
        assertTrue(claims.claim("watch-1"), "the same claim again: the same answer")
        assertFalse(claims.claim("phone-1"))
        time += WAKE_CLAIM_TTL_MS
        assertTrue(claims.claim("phone-2"))
    }

    @Test
    fun theWireKeepsOlderPeersReading() {
        val status = WearJson.decode<PhoneStatus>(WearJson.encode(PhoneStatus(true, true, wake = WatchWakeSettings(enabled = true, endSilenceMs = 3_500))))!!
        assertEquals(3_500, status.wake?.endSilenceMs)
        assertTrue(status.wake!!.enabled)
        // An older phone, or a status that says nothing of it: null, and the watch keeps its own.
        assertNull(WearJson.decode<PhoneStatus>("""{"signedIn":true,"connected":true}""".encodeToByteArray())!!.wake)
        assertEquals(WakeClaim("w", true), WearJson.decode<WakeClaim>(WearJson.encode(WakeClaim("w", true))))
        assertNull(WearJson.decode<WakeClaim>("""{"id":"w"}""".encodeToByteArray())!!.granted)
    }

    /** A recognizer that plays [script]: each session gets the next step. */
    private class Fake(val script: List<suspend (onReady: () -> Unit, onText: (String) -> Unit) -> WakeSessionEnd>) : WakeRecognizer {
        var sessions = 0
        override suspend fun session(onReady: () -> Unit, onText: (String) -> Unit): WakeSessionEnd {
            val step = script.getOrElse(sessions) { script.last() }
            sessions++
            return step(onReady, onText)
        }
    }

    private fun TestScope.loop(fake: Fake) = WakeLoop(fake, { defaults }) { testScheduler.currentTime }

    @Test
    fun aPartialWithThePhraseEndsTheWindowAtOnceAndReadyIsOnlyTheRealOne() = runTest {
        var ready = 0
        var cancelled = false
        // The recognizer would go on listening: hearing the phrase in a partial cancels it there.
        val fake = Fake(listOf({ onReady, onText ->
            delay(300)
            onReady()
            onReady()
            delay(700)
            onText("루미")
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }))
        val started = testScheduler.currentTime
        assertEquals(WakeWindowEnd.Matched, loop(fake).window(5_000) { ready++ })
        assertEquals(1_000, testScheduler.currentTime - started, "ended when heard, not at the window's end")
        assertTrue(cancelled, "the session was let go")
        assertEquals(1, ready)
        // A recognizer that never says it's ready, nor hears a phrase: the window simply ends, with no "ready".
        var notReady = 0
        val silent = Fake(listOf({ _, _ -> awaitCancellation() }))
        assertEquals(WakeWindowEnd.TimedOut, loop(silent).window(5_000) { notReady++ })
        assertEquals(0, notReady)
    }

    @Test
    fun sessionsAreRenewedWithinTheWindowButNeverSpin() = runTest {
        // A recognizer that gives up at once every time: the loop waits between, never a tight loop.
        val failing = Fake(listOf({ _, _ -> WakeSessionEnd.Failed(8) }))
        assertEquals(WakeWindowEnd.TimedOut, loop(failing).window(60_000))
        // 1 s, 2 s, 4 s … up to a minute between tries: a handful in a minute, not thousands.
        assertTrue(failing.sessions in 2..8, "${failing.sessions} sessions in a minute")
        // Quiet sessions of a normal length follow one another directly.
        val quiet = Fake(listOf({ _, _ -> delay(5_000); WakeSessionEnd.Quiet }))
        assertEquals(WakeWindowEnd.TimedOut, loop(quiet).window(30_000))
        assertEquals(6, quiet.sessions)
    }

    @Test
    fun noRecognizerStopsAndSaysWhy() = runTest {
        val none = Fake(listOf({ _, _ -> WakeSessionEnd.Unavailable("no_on_device_recognizer") }))
        val loop = loop(none)
        assertEquals(WakeWindowEnd.Unavailable, loop.window(Long.MAX_VALUE))
        assertEquals("no_on_device_recognizer", loop.unavailable)
        assertEquals(1, none.sessions)
    }

    @Test
    fun wordsThatArentTheStartOfAnUtteranceDontWake() = runTest {
        val chatter = Fake(listOf({ _, onText -> delay(2_000); onText("오늘 루미한테 물어봤어"); WakeSessionEnd.Quiet }))
        assertEquals(WakeWindowEnd.TimedOut, loop(chatter).window(5_000))
    }
}
