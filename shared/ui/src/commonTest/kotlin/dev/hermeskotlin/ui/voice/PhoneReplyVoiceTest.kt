package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §6): the phone reading a watch ask's reply ([PhoneReplyVoice]) over a fake player and the real
 * [VoiceMedia]: through the chat's route, stopped for good when the earphones go, one media owner at a time.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneReplyVoiceTest {
    private val other = Any()

    @AfterTest
    fun cleanUp() {
        VoiceMedia.report(other, false)
    }

    private class Player : SpeechPlayer {
        var earphones = false
        var lost: (() -> Unit)? = null
        var routes = 0
        var closed = 0
        val played = mutableListOf<String>()
        /** Each clip plays until this completes; cancelling stops it. */
        var playing = CompletableDeferred<Unit>()
        override fun earphonesConnected() = earphones
        override fun chainedChatAudio(talkOver: Boolean, onHeadsetLost: () -> Unit): () -> Unit {
            routes++
            lost = onHeadsetLost
            return { closed++ }
        }
        override suspend fun play(audio: SpokenAudio) {
            played += audio.mimeType
            playing.await()
        }
    }

    private fun clip(i: Int) = SpokenAudio(ByteArray(1), "clip-$i")

    @Test
    fun itReadsEveryClipThroughTheChatsRouteAndLetsTheMediaGo() = runTest {
        val player = Player().apply { earphones = true; playing.complete(Unit) }
        val voice = PhoneReplyVoice(player)
        assertTrue(voice.earphones())
        assertTrue(voice.free())
        assertTrue(voice.play { i -> if (i < 3) clip(i) else null })
        assertEquals(listOf("clip-0", "clip-1", "clip-2"), player.played)
        assertEquals(1, player.routes)
        assertEquals(1, player.closed)
        assertFalse(VoiceMedia.busy.value)
    }

    @Test
    fun earphonesGoneStopItAndNothingMorePlays() = runTest {
        val player = Player()
        val voice = PhoneReplyVoice(player)
        var asked = 0
        val reading = async { voice.play { i -> asked++; clip(i) } }
        runCurrent()
        assertEquals(listOf("clip-0"), player.played)
        assertTrue(VoiceMedia.busy.value, "the reading has the media")
        player.lost!!()
        runCurrent()
        assertTrue(reading.await())
        assertEquals(1, asked, "no further clip made")
        assertEquals(1, player.closed)
        assertFalse(VoiceMedia.busy.value)
    }

    @Test
    fun anotherVoiceHoldingTheMediaKeepsItOff() = runTest {
        val player = Player().apply { playing.complete(Unit) }
        val voice = PhoneReplyVoice(player)
        VoiceMedia.report(other, true)
        assertFalse(voice.free())
        assertFalse(voice.play { clip(it) })
        assertTrue(player.played.isEmpty())
        assertEquals(0, player.routes)
    }

    @Test
    fun anotherVoiceTakingTheMediaStopsIt() = runTest {
        val player = Player()
        val voice = PhoneReplyVoice(player)
        val reading = async { voice.play { i -> clip(i) } }
        runCurrent()
        assertEquals(1, player.played.size)
        VoiceMedia.report(other, true)
        runCurrent()
        assertTrue(reading.await())
        assertEquals(1, player.played.size)
        assertEquals(1, player.closed)
    }
}
