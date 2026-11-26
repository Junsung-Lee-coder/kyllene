package dev.hermeskotlin.wearlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork (requirements §9.1): the reader's messages on the wire, and older peers on either side. */
class WatchHistoryWireTest {
    @Test
    fun partsJoinBackToTheWholeTextAndNeverSplitASurrogatePair() {
        val text = "ab😀".repeat(40_000) + "end"
        val parts = historyParts(text, max = 1_001)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1_001 })
        assertTrue(parts.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertEquals(listOf("short"), historyParts("short"))
        assertEquals(listOf(""), historyParts(""))
    }

    @Test
    fun anOlderPhonesStatusSaysItDoesntRead() {
        val status = WearJson.decode<PhoneStatus>("""{"signedIn":true,"connected":true}""".encodeToByteArray())!!
        assertFalse(status.history)
        assertNull(status.account)
    }

    @Test
    fun anOlderWatchStillReadsANewerStatus() {
        val bytes = WearJson.encode(PhoneStatus(signedIn = true, connected = true, history = true, account = "a-" + "0".repeat(32)))
        // What an older watch decodes it as: its own fields, the new ones ignored.
        val older = WearJson.decode<PhoneStatus>(bytes)!!
        assertTrue(older.signedIn && older.connected)
    }

    @Test
    fun requestsAndPagesRoundTripAndToleratePeersNewerFields() {
        val request = HistoryRequest("r1", WatchScope("https://gw.example", "work"), chatId = "c", offset = 20, messageId = "s:1", part = 2)
        assertEquals(request, WearJson.decode<HistoryRequest>(WearJson.encode(request)))
        val page = WearJson.decode<HistoryPage>(
            """{"request":{"requestId":"r1","scope":{"gateway":"https://gw.example"}},"account":"a-1","messages":[{"id":"s:1","role":"user","text":"hi","later":1}],"nextOffset":20,"newer":true}""".encodeToByteArray(),
        )!!
        assertEquals("hi", page.messages.single().text)
        assertEquals(1, page.messages.single().parts)
        assertEquals(20, page.nextOffset)
    }
}
