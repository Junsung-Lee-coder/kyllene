package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.wearlink.WakeRecognizer
import dev.hermeskotlin.wearlink.WakeSessionEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork (requirements §5): the phone's wake word windows ([PhoneWakeRunner]) with fake recognizers on test time: a
 * window lives only while its condition holds, never two at once, and a phrase heard is checked again before anything
 * starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneWakeRunnerTest {
    @BeforeTest fun clean() { PhoneWake.state.value = PhoneWakeState.Stopped }
    @AfterTest fun cleanUp() { PhoneWake.state.value = PhoneWakeState.Stopped }

    /** Sessions open across both recognizers, and the most at once. */
    private class Mic {
        var open = 0
        var most = 0
    }

    private class Fake(private val mic: Mic, private val script: suspend (onReady: () -> Unit, onText: (String) -> Unit) -> WakeSessionEnd) : WakeRecognizer {
        var sessions = 0
        var cancelled = 0
        override suspend fun session(onReady: () -> Unit, onText: (String) -> Unit): WakeSessionEnd {
            sessions++
            mic.open++
            mic.most = maxOf(mic.most, mic.open)
            try {
                return script(onReady, onText)
            } catch (e: CancellationException) {
                cancelled++
                throw e
            } finally {
                mic.open--
            }
        }
    }

    private class Rig(
        val runner: PhoneWakeRunner,
        val mic: Mic,
        val inApp: Fake,
        val outOfApp: Fake,
        val visible: MutableStateFlow<Boolean>,
        val busy: MutableStateFlow<Boolean>,
        val running: MutableStateFlow<Boolean>,
        val settings: MutableStateFlow<AppSettings?>,
    ) {
        var claims = 0
        val inAppWakes = mutableListOf<Int>()
        var outOfAppWakes = 0
        var stopped = 0
        var unavailable: String? = null
    }

    private fun TestScope.rig(
        inApp: suspend (() -> Unit, (String) -> Unit) -> WakeSessionEnd = { _, _ -> awaitCancellation() },
        outOfApp: suspend (() -> Unit, (String) -> Unit) -> WakeSessionEnd = { _, _ -> awaitCancellation() },
        visible: Boolean = false,
        running: Boolean = false,
    ): Rig {
        val mic = Mic()
        val inFake = Fake(mic, inApp)
        val outFake = Fake(mic, outOfApp)
        val settings = MutableStateFlow<AppSettings?>(AppSettings(phoneWake = true, phoneWakeBackground = true))
        val shown = MutableStateFlow(visible)
        val busy = MutableStateFlow(false)
        val serviceRunning = MutableStateFlow(running)
        lateinit var rig: Rig
        val runner = PhoneWakeRunner(
            inAppRecognizer = inFake,
            outOfAppRecognizer = outFake,
            settings = settings,
            visible = shown,
            screenOn = MutableStateFlow(true),
            charging = MutableStateFlow(false),
            busy = busy,
            serviceRunning = serviceRunning,
            now = { testScheduler.currentTime },
            allowed = { true },
            outOfAppUnavailable = { rig.unavailable },
            chatInFront = { true },
            claim = { rig.claims++; true },
            wakeInApp = { ms -> rig.inAppWakes += ms; true },
            wakeOutOfApp = { rig.outOfAppWakes++ },
            stopService = { rig.stopped++ },
        )
        rig = Rig(runner, mic, inFake, outFake, shown, busy, serviceRunning, settings)
        // As the app's service says once it runs.
        if (running) PhoneWake.state.value = PhoneWakeState.Listening
        return rig
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun theAppComingToTheFrontCancelsTheOutOfAppWindowAndOnlyOneListensAtATime() = runTest {
        val r = rig(running = true)
        r.runner.start(backgroundScope)
        // Past the cooldown, out of the app: the on-device window listens.
        wait(5_000)
        assertEquals(1, r.outOfApp.sessions)
        assertEquals(1, r.mic.open)
        // The person opens the app: the background window goes at once, before the app's own window opens.
        r.visible.value = true
        runCurrent()
        assertEquals(1, r.outOfApp.cancelled)
        assertEquals(0, r.mic.open)
        wait(1_000)
        assertEquals(1, r.inApp.sessions, "the app's own window")
        assertEquals(1, r.mic.most, "never two recognizers at once")
        assertEquals(0, r.outOfAppWakes)
        assertEquals(PhoneWakeState.InApp, PhoneWake.state.value)
        // Back out of the app: its window goes, and the service listens again.
        r.visible.value = false
        wait(1_000)
        assertEquals(1, r.inApp.cancelled)
        assertEquals(2, r.outOfApp.sessions)
        assertEquals(1, r.mic.most)
    }

    @Test
    fun aVoiceTakingTheMicrophoneDuringAWindowCancelsItAndNothingStarts() = runTest {
        // The phrase would be heard two seconds into the window.
        val r = rig(inApp = { onReady, onText -> onReady(); delay(2_000); onText("루미"); awaitCancellation() }, visible = true)
        r.runner.start(backgroundScope)
        wait(4_000)
        // The app's window opens once it's shown and quiet long enough (the screen-on edge came at start, within the
        // cooldown, so a fresh one is needed): show it again.
        r.visible.value = false
        runCurrent()
        r.visible.value = true
        wait(700)
        assertEquals(1, r.inApp.sessions)
        // The composer's dictation (or a chat, or Read aloud) takes the microphone a second in.
        wait(1_000)
        r.busy.value = true
        runCurrent()
        assertEquals(1, r.inApp.cancelled)
        wait(5_000)
        assertEquals(0, r.claims)
        assertTrue(r.inAppWakes.isEmpty())
        assertEquals(0, r.mic.open)
    }

    @Test
    fun aPhraseHeardJustAsTheMicrophoneIsTakenStartsNothing() = runTest {
        lateinit var r: Rig
        r = rig(outOfApp = { _, onText ->
            delay(1_000)
            // The same moment a voice starts: busy as the phrase comes in.
            r.busy.value = true
            onText("루미야")
            awaitCancellation()
        }, running = true)
        r.runner.start(backgroundScope)
        wait(10_000)
        assertEquals(0, r.claims, "checked again before claiming")
        assertEquals(0, r.outOfAppWakes)
    }

    @Test
    fun heardInAndOutOfTheAppItStartsOnceWithTheSetting() = runTest {
        val r = rig(
            inApp = { _, onText -> delay(500); onText("hey 루미"); awaitCancellation() },
            outOfApp = { _, onText -> delay(1_000); onText("루미 지금 몇 시야"); awaitCancellation() },
            running = true,
        )
        r.settings.value = r.settings.value!!.copy(wakeEndSilenceMs = 3_500)
        r.runner.start(backgroundScope)
        // Out of the app: the window opens after the cooldown (4 s), the phrase a second in.
        wait(5_500)
        assertEquals(1, r.outOfAppWakes)
        assertEquals(1, r.claims)
        // The chat it started takes the microphone, then goes quiet; listening out of the app is stopped.
        r.busy.value = true
        runCurrent()
        r.running.value = false
        r.busy.value = false
        runCurrent()
        // Past the cooldown, a chat comes on screen: its window, the phrase half a second in.
        wait(4_500)
        r.visible.value = true
        wait(2_000)
        assertEquals(listOf(3_500), r.inAppWakes)
        assertEquals(2, r.claims)
        assertEquals(1, r.outOfAppWakes)
        assertEquals(1, r.mic.most)
    }

    @Test
    fun anUnavailableOnDeviceRecognizerStopsTheServiceAndSaysSo() = runTest {
        val r = rig(running = true)
        r.unavailable = "no_on_device_recognizer"
        r.runner.start(backgroundScope)
        wait(1_000)
        assertEquals(1, r.stopped)
        assertEquals(PhoneWakeState.NoOnDevice, PhoneWake.state.value)
        assertEquals(0, r.outOfApp.sessions)
    }

    @Test
    fun theWakeWordTurnedOffCancelsTheWindow() = runTest {
        val r = rig(running = true)
        r.runner.start(backgroundScope)
        wait(5_000)
        assertEquals(1, r.mic.open)
        r.settings.value = r.settings.value!!.copy(phoneWake = false)
        runCurrent()
        assertEquals(0, r.mic.open)
        assertEquals(1, r.outOfApp.cancelled)
    }
}
