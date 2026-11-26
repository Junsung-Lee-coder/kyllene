package dev.hermeskotlin.wearlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: the watch's New chat flag travels only when set, so older watches and phones read each other as before. */
class NewChatWireTest {
    private fun text(value: Any) = when (value) {
        is WatchAsk -> WearJson.encode(value).decodeToString()
        is CallStart -> WearJson.encode(value).decodeToString()
        else -> error("unexpected")
    }

    @Test
    fun anAskOrCallFromAnOlderWatchIsNotNewChat() {
        assertFalse(WearJson.decode<WatchAsk>("""{"id":"a","text":"hi","speak":false}""".encodeToByteArray())!!.newChat)
        assertFalse(WearJson.decode<CallStart>("""{"id":"c","chatId":null,"offerSdp":"o"}""".encodeToByteArray())!!.newChat)
    }

    @Test
    fun theFlagIsLeftOutUnlessSetAndComesBackWhenSet() {
        assertFalse("newChat" in text(WatchAsk("a", "hi")))
        assertFalse("newChat" in text(CallStart("c", null, "o")))
        val ask = WearJson.decode<WatchAsk>(WearJson.encode(WatchAsk("a", "hi", newChat = true)))!!
        assertTrue(ask.newChat)
        assertEquals(null, ask.chatId)
        assertTrue(WearJson.decode<CallStart>(WearJson.encode(CallStart("c", null, "o", newChat = true)))!!.newChat)
        // A recorded ask carries it in its header too.
        val recorded = RecordedAsk.read(RecordedAsk.write(WatchAsk("r", newChat = true), ByteArray(4)))
        assertTrue(recorded!!.first.newChat)
    }
}
