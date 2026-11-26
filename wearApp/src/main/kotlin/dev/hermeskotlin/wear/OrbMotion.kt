package dev.hermeskotlin.wear

import androidx.lifecycle.Lifecycle

/*
 * Fork customization (requirements §11): the orb moves only while the watch's microphone or speaker is really in
 * use, and only on screen. It's still (the frame it stopped on, drawn once) at Ready, while Hermes works or the phone
 * is waited on, while a call connects, and in a call where neither side makes a sound. This only decides what's
 * drawn: nothing here starts, stops or holds a recording, a reply's voice or a call.
 */

/** The watch's own audio as its sources tell it: sound being taken from the microphone, or played out. */
data class WatchMedia(val capturing: Boolean = false, val playing: Boolean = false) {
    val active: Boolean get() = capturing || playing
}

/**
 * Talk and the assistant: [Listening.recording] spans the recorder's run (the microphone open and read); [replyPlaying]
 * is [SpeechOut.sounding], a reply's clip or the watch's own voice actually sounding (not waiting for its next clip).
 */
internal fun talkMedia(listening: Listening, replyPlaying: Boolean) = WatchMedia(capturing = listening.recording, playing = replyPlaying)

/**
 * A live call: the voice heard, from the call's own inbound audio level ([WatchCallState.speaking]), and your voice
 * picked up, from the microphone's samples ([WatchCallState.level], unmuted). Starting, connecting (its ringback
 * included), ended, or connected with no sound either way: none.
 */
internal fun callMedia(call: WatchCallState?): WatchMedia {
    if (call == null || call.stage != CallStage.Live) return WatchMedia()
    return WatchMedia(capturing = !call.muted && call.level >= CALL_VOICE_LEVEL, playing = call.speaking)
}

/** Both: a call's media and Talk's, as the talk screen shows either. */
internal operator fun WatchMedia.plus(other: WatchMedia) = WatchMedia(capturing || other.capturing, playing || other.playing)

/**
 * On screen and awake: resumed. An app without ambient support is paused when the watch goes ambient, and stopped
 * when it's covered, closed or the screen goes off; any of those stops the orb, and resuming starts it again.
 */
internal fun shownIn(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.RESUMED)

/** Whether the orb (and its size change) moves now. */
internal fun orbMoves(media: WatchMedia, shown: Boolean): Boolean = shown && media.active

/** A voice picked up in a call, on the microphone level's scale: the watch recorder's own speech level. */
internal const val CALL_VOICE_LEVEL = 0.025f
