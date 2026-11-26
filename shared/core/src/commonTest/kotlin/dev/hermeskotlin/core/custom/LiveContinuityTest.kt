package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.voice.LiveFragment.Speaker.Assistant
import dev.hermeskotlin.core.voice.LiveFragment.Speaker.User
import dev.hermeskotlin.core.voice.liveHistory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork (requirements §8): where a session's earlier calls go in the next call's opening history. */
class LiveContinuityTest {
    private val morning = LiveTranscripts.EarlierCall(startedAt = 1_000_000L, turns = listOf(User to "Book the train", Assistant to "Which day?"))
    private val evening = LiveTranscripts.EarlierCall(startedAt = 9_000_000L, turns = listOf(User to "Cancel it"))

    @Test
    fun earlierCallsGoWhereTheyHappenedEachOnce() {
        val chat = listOf(
            ChatTurn(500.0, User, "Hello"),
            ChatTurn(2_000.0, Assistant, "Booked for Friday."),
            ChatTurn(null, User, "Thanks"),
        )
        assertEquals(
            listOf(User to "Hello", User to "Book the train", Assistant to "Which day?", Assistant to "Booked for Friday.", User to "Thanks", User to "Cancel it"),
            withEarlierCalls(chat, listOf(evening, morning)),
        )
    }

    @Test
    fun withoutEarlierCallsItsTheChatAsBefore() {
        val chat = listOf(ChatTurn(null, User, "Hi"), ChatTurn(null, Assistant, "Hello"))
        assertEquals(listOf(User to "Hi", Assistant to "Hello"), withEarlierCalls(chat, emptyList()))
    }

    @Test
    fun theOpeningHistoryKeepsTheNewestThatFit() {
        val history = liveHistory(withEarlierCalls(emptyList(), listOf(morning, evening)), maxMessages = 2)
        assertEquals(listOf("assistant", "user"), history.map { it.role })
        assertEquals("Cancel it", history.last().content.single().text)
    }
}
