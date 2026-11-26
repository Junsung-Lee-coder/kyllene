package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.VoiceActivity

/**
 * Fork customization: which voice a chat started on the phone is (requirements §3.1, §3.2).
 *
 * - [Stock]: the chat's voice as it was: a live call when the profile picks one, the chained chat otherwise. Calls the
 *   agent places, and the watch's, stay this way.
 * - [Manual]: Phone Live off. Talk: one request recorded until it's sent (Send, or the earphones' button) or dropped
 *   (End), with no pause or time limit ending it; then the reply is read and the chat waits, Ready, for the next press.
 *   The microphone is never opened on its own.
 * - [Live]: Phone Live on. A live call, or the reason it can't be one; never the chained chat instead.
 */
enum class VoiceMode { Stock, Manual, Live }

/** Fork: a Talk recording ends only when it's sent or dropped: no pause ends it, nor silence, nor a time limit. */
internal val MANUAL_TALK = VoiceActivity(silenceMs = Long.MAX_VALUE, idleMs = 0, maxMs = Long.MAX_VALUE)

/** Fork: the mode a chat started from the phone's Voice button, the earphones or Call Hermes takes. */
fun phoneVoiceMode(phoneLive: Boolean): VoiceMode = if (phoneLive) VoiceMode.Live else VoiceMode.Manual
