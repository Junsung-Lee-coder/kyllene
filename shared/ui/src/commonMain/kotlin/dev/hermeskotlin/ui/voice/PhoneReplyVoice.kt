package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.custom.PhoneReplyOutput
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fork (requirements §6): the phone reading aloud a reply the watch asked for, through the same player and route as
 * the phone's own Talk replies ([SpeechPlayer.chainedChatAudio]): earphones that go away stop it, and it never moves
 * on to the phone's speaker; picked for its earphones, it plays only if they're still there once that route is open.
 * While it reads it has the phone's voice media ([VoiceMedia]), so the wake word doesn't
 * listen over it; a call, a voice chat, dictation or Read aloud taking the media stops it.
 */
class PhoneReplyVoice(private val player: SpeechPlayer) : PhoneReplyOutput {
    override fun earphones(): Boolean = player.earphonesConnected()

    override fun free(): Boolean = !VoiceMedia.busy.value && !VoiceMedia.busyNow()

    override suspend fun play(earphonesOnly: Boolean, next: suspend (index: Int) -> SpokenAudio?): Boolean {
        if (!free()) return false
        val owner = Any()
        VoiceMedia.report(owner, true)
        var started = true
        try {
            coroutineScope {
                var reading: Job? = null
                var lost = false
                // The route first, so the first clip already plays through it. Earphones gone: what played has
                // stopped already, and nothing more plays.
                val close = player.chainedChatAudio(false) {
                    lost = true
                    reading?.cancel()
                }
                try {
                    // Picked for its earphones, which went in the meantime: the route has no private output to
                    // watch, so the phone's speaker would play it. Nothing plays.
                    if (earphonesOnly && !player.earphonesConnected()) {
                        started = false
                        return@coroutineScope
                    }
                    if (lost) return@coroutineScope
                    val job = launch {
                        var index = 0
                        while (true) player.play(next(index++) ?: break)
                    }
                    reading = job
                    if (lost) job.cancel()
                    val taken = launch { VoiceMedia.busyApartFrom(owner).first { it }; job.cancel() }
                    job.join()
                    taken.cancel()
                } finally {
                    close()
                }
            }
        } finally {
            VoiceMedia.report(owner, false)
        }
        return started
    }
}
