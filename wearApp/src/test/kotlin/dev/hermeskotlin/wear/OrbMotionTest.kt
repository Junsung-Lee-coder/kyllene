package dev.hermeskotlin.wear

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork (requirements §11): when the watch's orb moves, from the media sources the screens read. */
class OrbMotionTest {
    private fun live(level: Float = 0f, speaking: Boolean = false, muted: Boolean = false, thinking: Boolean = false) =
        WatchCallState("call", CallStage.Live, muted = muted, speaking = speaking, level = level, thinking = thinking)

    @Test
    fun recordingMovesItForAsLongAsTheRecorderRunsAndNoLonger() {
        val listening = MutableStateFlow(Listening())
        val seen = mutableListOf<Boolean>()
        assertFalse(orbMoves(talkMedia(listening.value, replyPlaying = false), shown = true), "Ready: still")
        runBlocking {
            recordAsk(listening, 1, { 1 }, { onLevel ->
                seen += orbMoves(talkMedia(listening.value, false), true)
                // Silence while recording still moves it: the microphone is taking sound.
                onLevel(0f)
                seen += orbMoves(talkMedia(listening.value, false), true)
                byteArrayOf(1)
            }) {}
        }
        assertEquals(listOf(true, true), seen)
        assertFalse(orbMoves(talkMedia(listening.value, false), true), "sent and waiting for Hermes: still")
    }

    @Test
    fun aMicrophoneThatFailsLeavesItStill() {
        val listening = MutableStateFlow(Listening())
        runBlocking { recordAsk(listening, 1, { 1 }, { throw MicrophoneUnavailableException() }) {} }
        assertFalse(orbMoves(talkMedia(listening.value, false), true))
    }

    @Test
    fun aReplyMovesItOnlyWhileItSounds() {
        assertTrue(orbMoves(talkMedia(Listening(), replyPlaying = true), true))
        // Waiting for the network (the next clip, or the phone) is no media.
        assertFalse(orbMoves(talkMedia(Listening(), replyPlaying = false), true))
    }

    @Test
    fun aCallMovesItOnlyWhenSomeoneIsHeard() {
        assertFalse(orbMoves(callMedia(WatchCallState("c", CallStage.Starting)), true), "starting")
        assertFalse(orbMoves(callMedia(WatchCallState("c", CallStage.Connecting)), true), "connecting, ringback too")
        assertFalse(orbMoves(callMedia(live()), true), "connected, silent both ways")
        assertFalse(orbMoves(callMedia(live(level = CALL_VOICE_LEVEL / 2)), true), "room noise below the voice level")
        assertTrue(orbMoves(callMedia(live(level = CALL_VOICE_LEVEL)), true), "your voice picked up")
        assertTrue(orbMoves(callMedia(live(speaking = true)), true), "the voice heard")
        assertFalse(orbMoves(callMedia(live(level = 0.5f, muted = true)), true), "muted: the microphone takes nothing")
        assertFalse(orbMoves(callMedia(live(thinking = true)), true), "Hermes on a request, nobody talking")
        assertFalse(orbMoves(callMedia(WatchCallState("c", CallStage.Ended, speaking = true)), true), "ended")
        assertFalse(orbMoves(callMedia(null), true))
        // The talk screen adds both: a reply still sounding after a call ended moves it.
        assertTrue(orbMoves(talkMedia(Listening(), true) + callMedia(WatchCallState("c", CallStage.Ended)), true))
    }

    @Test
    fun ambientHiddenAndResumedFollowTheScreensLifecycle() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val registry = owner.registry
        val playing = talkMedia(Listening(), replyPlaying = true)
        fun moves() = orbMoves(playing, shownIn(registry.currentState))
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertFalse(moves(), "started, not yet resumed")
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(moves(), "on screen")
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertFalse(moves(), "ambient: paused")
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(moves(), "hidden")
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(moves(), "resumed")
        // What it decides from is only read: the media is the same before and after.
        assertEquals(WatchMedia(playing = true), playing)
    }
}
