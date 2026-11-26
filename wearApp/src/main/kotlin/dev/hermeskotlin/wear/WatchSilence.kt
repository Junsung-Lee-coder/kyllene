package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.LiveSilence
import dev.hermeskotlin.wearlink.liveSilenceSeconds

/**
 * Fork (requirements §3.3): one watch call's silence. The watch holds the call's audio, so it's measured here, with the
 * same rule as the phone's own calls ([LiveSilence]): from the session being ready (`session.started`, once the phone
 * said the call is live, with its setting), back to zero with the voice playing, the person at the microphone or words
 * either side said, held while Hermes works or the link is down. Muted, or waiting for an answer, it runs.
 */
internal class WatchSilence(private val now: () -> Long) {
    private var ready = false
    private var silence: LiveSilence? = null

    /** An event from the voice model, by type. */
    fun event(type: String?) {
        when (type) {
            "session.started" -> ready = true
            // Words heard or said: sound, though their absence alone is never silence.
            "session.input_transcript.delta", "session.output_transcript.delta" -> silence?.activity()
        }
    }

    /**
     * One look at the call ([call], as the phone last told it, and the watch's own [speaking], [micLevel] and
     * [linked]); true once the silence has lasted [WatchCallState.silenceSeconds] (the default, from an older phone).
     */
    fun sample(call: WatchCallState?, speaking: Boolean, micLevel: Float, linked: Boolean): Boolean {
        if (call == null || call.stage != CallStage.Live || !ready) return false
        val clock = silence ?: LiveSilence(liveSilenceSeconds(call.silenceSeconds), now).also { it.start(); silence = it }
        return clock.sample(playing = speaking, micLevel = micLevel, muted = call.muted, working = call.thinking && !call.waiting, linked = linked)
    }

    /** The setting this call ends at, for saying why. */
    fun seconds(call: WatchCallState?): Int = liveSilenceSeconds(call?.silenceSeconds)
}
