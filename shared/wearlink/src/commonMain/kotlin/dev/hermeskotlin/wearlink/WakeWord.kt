package dev.hermeskotlin.wearlink

import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/*
 * Fork customization (requirements §5): a wake word on the phone and on the watch. Hearing one of the phrases starts a
 * live call (Live on) or one request (Live off), as the device's own button does; it is only a start: words said after
 * it aren't taken as the request. What both apps share is here: the phrases and how they match, the watch's settings as
 * the phone sends them, the claim that keeps one utterance from starting on both devices, when each device listens, and
 * the listening loop over a platform recognizer.
 */

/** The phrases as typed in Settings: space-separated, with the limits the original app had. */
object WakePhrases {
    const val DEFAULT = "루미 루미야 룸이 룸이야 루미봇 룸이봇 룸미 룸미야 룸미봇"
    const val MAX_PHRASES = 24
    const val MAX_PHRASE_CHARS = 40
    const val MAX_TOTAL_CHARS = 240

    /** A greeting that may come first: "hey 루미", "안녕, 루미". */
    val GREETINGS = setOf("hey", "hi", "hello", "ok", "okay", "안녕", "저기")

    /**
     * [typed] as phrases: split on spaces, each cut to [MAX_PHRASE_CHARS], repeats dropped, at most [MAX_PHRASES] and
     * [MAX_TOTAL_CHARS] in all (what doesn't fit is left off). Empty: the default list.
     */
    fun parse(typed: String): List<String> {
        val words = typed.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return parse(DEFAULT)
        val out = mutableListOf<String>()
        var total = 0
        for (word in words) {
            val phrase = word.take(MAX_PHRASE_CHARS)
            if (out.any { it.equals(phrase, ignoreCase = true) }) continue
            if (out.size == MAX_PHRASES || total + phrase.length > MAX_TOTAL_CHARS) break
            out += phrase
            total += phrase.length
        }
        return out
    }

    /** What's saved: the phrases [parse] keeps, joined; empty stays empty (the default list). */
    fun normalize(typed: String): String = if (typed.isBlank()) "" else parse(typed).joinToString(" ")

    /**
     * Whether [said] starts with one of [phrases] (or with one greeting and then a phrase). Word by word, case aside,
     * punctuation around a word ignored; `*` stands for any characters inside one word. Only a start: a phrase later
     * in the sentence doesn't count.
     */
    fun matches(said: String, phrases: List<String>): Boolean {
        val words = said.split(WHITESPACE).map { it.trim(*PUNCTUATION).lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val patterns = phrases.map(::pattern)
        if (patterns.any { it.matches(words[0]) }) return true
        return words.size > 1 && words[0] in GREETINGS && patterns.any { it.matches(words[1]) }
    }

    /** Whether any phrase is Korean, so the recognizer is asked for Korean. */
    fun korean(phrases: List<String>): Boolean = phrases.any { phrase -> phrase.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' } }

    private fun pattern(phrase: String): Regex = Regex(
        phrase.lowercase().trim(*PUNCTUATION).split('*').joinToString("[^\\s]*") { Regex.escape(it) },
    )

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = charArrayOf(',', '.', '!', '?', '~', '…', '"', '\'', '“', '”', '‘', '’', '(', ')', ':', ';', '、', '。', '！', '？')
}

/** How long a quiet spell ends a request a wake word started: 0.5 to 10 seconds in 0.5 steps, 2.0 by default. */
object WakeEndSilence {
    const val DEFAULT_MS = 2_000
    const val MIN_MS = 500
    const val MAX_MS = 10_000
    const val STEP_MS = 500

    fun clamp(ms: Int): Int = ((ms.coerceIn(MIN_MS, MAX_MS) + STEP_MS / 2) / STEP_MS * STEP_MS).coerceIn(MIN_MS, MAX_MS)

    /** Every value Settings offers. */
    val choices: List<Int> = (MIN_MS..MAX_MS step STEP_MS).toList()
}

/** With nothing said this long after the wake word, nothing is sent. */
const val WAKE_IDLE_MS = 8_000L

/** A window on the watch (and the phone in sight) after the screen turns on, or the app shows. */
const val WAKE_SCREEN_WINDOW_MS = 5_000L

/** The phone out of sight: listen this long at most, then rest [PHONE_WAKE_REST_MS]. */
const val PHONE_WAKE_WINDOW_MS = 30_000L
const val PHONE_WAKE_REST_MS = 60_000L

/** After a reply played, the wake word waits this long, so the reply's end isn't heard as the phrase. */
const val WAKE_PLAYBACK_COOLDOWN_MS = 4_000L

/** A claim stands this long: the other device hearing the same words then is turned down. */
const val WAKE_CLAIM_TTL_MS = 10_000L

/** The watch waits this long for the phone's answer to its claim. */
const val WAKE_CLAIM_TIMEOUT_MS = 2_500L

/**
 * The watch's wake word settings, as the phone keeps them and sends them ([PhoneStatus.wake]). Null there means the
 * phone said nothing (an older phone, or a status without them): the watch keeps what it has.
 */
@Serializable
data class WatchWakeSettings(
    val enabled: Boolean = false,
    /** As typed ([WakePhrases.normalize]); empty is the default list. */
    val phrases: String = "",
    val endSilenceMs: Int = WakeEndSilence.DEFAULT_MS,
    /** Outside the app: the 5-second window when the screen turns on, from a listening service started in the app. */
    val background: Boolean = false,
    /** With the background on: listening all the while the screen is off. */
    val screenOff: Boolean = false,
    /** With the background on: listening all the while the watch charges. */
    val charging: Boolean = false,
)

/**
 * Watch → phone: the wake word was heard on the watch; may it start? Phone → watch: the same [id], [granted] or not. The
 * phone answers the first claim of the last [WAKE_CLAIM_TTL_MS], its own included, and turns the others down.
 */
@Serializable
data class WakeClaim(val id: String, val granted: Boolean? = null)

/** Whether a device's listening continues right now (beyond a 5-second window), by its settings and state. */
object WakeRules {
    /**
     * The watch listens all the while: the background service runs ([serviceRunning]) and either the screen is off with
     * that option, or it charges with that option.
     */
    fun watchContinuous(s: WatchWakeSettings, serviceRunning: Boolean, screenOn: Boolean, charging: Boolean): Boolean =
        s.enabled && s.background && serviceRunning && ((!screenOn && s.screenOff) || (charging && s.charging))

    /** The watch opens a 5-second window as the screen turns on: in the app, or outside it with the service running. */
    fun watchScreenWindow(s: WatchWakeSettings, appVisible: Boolean, serviceRunning: Boolean): Boolean =
        s.enabled && (appVisible || (s.background && serviceRunning))

    /**
     * The phone out of sight listens in windows ([PHONE_WAKE_WINDOW_MS], then a rest): with the service the person
     * started running, the app not on screen, and the screen on, or off with that option, or charging with that option.
     */
    fun phoneBackgroundWindows(on: Boolean, background: Boolean, screenOff: Boolean, chargingOption: Boolean, serviceRunning: Boolean, appVisible: Boolean, screenOn: Boolean, charging: Boolean): Boolean =
        on && background && serviceRunning && !appVisible && (screenOn || screenOff || (chargingOption && charging))
}

/**
 * The first claim of the last [WAKE_CLAIM_TTL_MS] wins; the same claim asked again gets the same answer. On the phone,
 * for its own wake word and the watch's alike. [now]: a monotonic clock in milliseconds.
 */
@OptIn(ExperimentalAtomicApi::class)
class WakeClaims(private val now: () -> Long) {
    // Asked from the phone's main thread and the relay's coroutines alike: one compare-and-set decides.
    private val holder = AtomicReference<Pair<String, Long>?>(null)

    fun claim(id: String): Boolean {
        while (true) {
            val t = now()
            val current = holder.load()
            val held = current?.takeIf { t - it.second < WAKE_CLAIM_TTL_MS }
            if (held != null) return held.first == id
            if (holder.compareAndSet(current, id to t)) return true
        }
    }
}

/** How one recognizer session ended. */
sealed interface WakeSessionEnd {
    /** It ended by itself (heard nothing it could make out, or a sentence that wasn't a phrase). */
    data object Quiet : WakeSessionEnd

    /** It failed; worth trying again after a while. */
    data class Failed(val code: Int) : WakeSessionEnd

    /** It can't run here at all (no recognizer, not the language, no permission): don't try again. */
    data class Unavailable(val why: String) : WakeSessionEnd
}

/**
 * The platform's speech recognizer, one session at a time: [onReady] once it actually listens, [onText] for every
 * partial or final hypothesis, then it returns how it ended. Cancelling stops the recognizer and releases the
 * microphone; nothing it says after that is passed on.
 */
fun interface WakeRecognizer {
    suspend fun session(onReady: () -> Unit, onText: (String) -> Unit): WakeSessionEnd
}

/** How a listening window ended. */
enum class WakeWindowEnd { Matched, TimedOut, Unavailable }

/**
 * Listening in windows over [recognizer]: a window renews sessions that end early until a phrase is heard, the window
 * is up, or the recognizer can't run. Sessions that fail wait before the next, longer each time (to a minute), so a
 * broken recognizer never spins; quiet ones that end at once wait too. [now] is a monotonic clock in milliseconds.
 */
class WakeLoop(
    private val recognizer: WakeRecognizer,
    private val phrases: () -> List<String>,
    private val now: () -> Long,
) {
    private var backoffMs = 0L

    /** Why the recognizer can't run, once it said so; null while it can. */
    var unavailable: String? = null
        private set

    /**
     * Listens up to [windowMs] (or for good: [Long.MAX_VALUE]). [onReady] when the recognizer first actually listens in
     * this window. Returns how it ended; the recognizer is let go either way.
     */
    suspend fun window(windowMs: Long, onReady: () -> Unit = {}): WakeWindowEnd {
        var ready = false
        val end = withTimeoutOrNull(windowMs) {
            var outcome: WakeWindowEnd? = null
            while (outcome == null) {
                if (backoffMs > 0) delay(backoffMs)
                var matched = false
                val started = now()
                // Heard: the session is cancelled at once (the recognizer lets go of the microphone), not left to run on.
                val result = coroutineScope {
                    val heard = CompletableDeferred<Unit>()
                    val session = async {
                        try {
                            recognizer.session(
                                onReady = { if (!ready) { ready = true; onReady() } },
                                onText = { text ->
                                    if (!matched && WakePhrases.matches(text, phrases())) {
                                        matched = true
                                        heard.complete(Unit)
                                    }
                                },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            WakeSessionEnd.Failed(-1)
                        }
                    }
                    val stopper = launch {
                        heard.await()
                        session.cancel()
                    }
                    try {
                        session.await()
                    } catch (e: CancellationException) {
                        if (!matched || !isActive) throw e
                        WakeSessionEnd.Quiet
                    } finally {
                        stopper.cancel()
                    }
                }
                when {
                    matched -> {
                        backoffMs = 0
                        outcome = WakeWindowEnd.Matched
                    }
                    result is WakeSessionEnd.Unavailable -> {
                        unavailable = result.why
                        outcome = WakeWindowEnd.Unavailable
                    }
                    result is WakeSessionEnd.Failed -> backoffMs = (backoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                    else -> backoffMs = if (now() - started < MIN_SESSION_MS) MIN_BACKOFF_MS else 0
                }
            }
            outcome
        }
        return end ?: WakeWindowEnd.TimedOut
    }

    companion object {
        /** A session shorter than this, or a failure, waits at least [MIN_BACKOFF_MS] before the next. */
        const val MIN_SESSION_MS = 1_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
