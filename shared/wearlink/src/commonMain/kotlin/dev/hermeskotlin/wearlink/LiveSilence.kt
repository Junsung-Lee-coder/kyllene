package dev.hermeskotlin.wearlink

/*
 * Fork customization (requirements §3.3): a live call ends after a stretch of silence, the phone's and the watch's each
 * set apart (1–60 seconds, 10 by default). It's not a limit on how long a call lasts: calls have none. One rule for
 * both devices, each run where its call's audio is (the phone for its own calls, the watch for the watch's).
 */

const val LIVE_SILENCE_DEFAULT_SECONDS = 10
const val LIVE_SILENCE_MIN_SECONDS = 1
const val LIVE_SILENCE_MAX_SECONDS = 60

/**
 * A microphone level (the calls' RMS scale, 1.0 loud speech) at or above which the person counts as speaking. The watch
 * recorder's own speech level. Approximate: a level, not speech recognition, so loud room noise counts too; never
 * calibrated on the owner's devices.
 */
const val LIVE_SPEECH_LEVEL = 0.025f

/** A saved or sent silence setting as used: whole seconds from 1 to 60; none (an older peer) is the default. */
fun liveSilenceSeconds(value: Int?): Int = (value ?: LIVE_SILENCE_DEFAULT_SECONDS).coerceIn(LIVE_SILENCE_MIN_SECONDS, LIVE_SILENCE_MAX_SECONDS)

/** Whether [text], as typed, is a silence setting: a whole number from 1 to 60. */
fun parseLiveSilence(text: String): Int? =
    text.trim().takeIf { it.isNotEmpty() && it.length <= 2 && it.all(Char::isDigit) }?.toInt()
        ?.takeIf { it in LIVE_SILENCE_MIN_SECONDS..LIVE_SILENCE_MAX_SECONDS }

/**
 * Silence on one live call, on a monotonic clock [now] (milliseconds). It runs from [start] (the session is ready, not
 * while dialling) and goes back to zero with every sign of sound: the person's voice at the microphone, or the voice
 * playing. Held while the call waits on something other than the person (Hermes working, the link down), and back to
 * zero when that's over. Muted, or waiting for the person to answer, it runs: that is silence.
 */
class LiveSilence(seconds: Int, private val now: () -> Long) {
    private val limitMs = liveSilenceSeconds(seconds) * 1_000L
    private var since: Long? = null
    private var held = false

    val started: Boolean get() = since != null

    /** The session is ready: the clock starts (once). */
    fun start() {
        if (since == null) since = now()
    }

    /** Sound: back to zero. Before [start] there's nothing to reset. */
    fun activity() {
        if (since != null) since = now()
    }

    /** Waiting on something other than the person: the clock doesn't run, and starts from zero once it lets go. */
    fun hold(held: Boolean) {
        if (since != null && (held || this.held)) since = now()
        this.held = held
    }

    /** One look at the call's own signals; true once the silence has lasted the setting. */
    fun sample(playing: Boolean, micLevel: Float, muted: Boolean, working: Boolean, linked: Boolean): Boolean {
        hold(working || !linked)
        if (playing || (!muted && micLevel >= LIVE_SPEECH_LEVEL)) activity()
        return expired()
    }

    fun expired(): Boolean {
        val from = since ?: return false
        return !held && now() - from >= limitMs
    }
}
