package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Fork behaviour: the device keeps every chat it has saved. Stock Kyllene dropped the chat read longest ago once more
 * than 50 were saved; here only the person deleting a chat (or its bot, or signing out) drops a saved copy, and a
 * copy stays under its own gateway, profile and chat.
 */
class SavedChatsKeptTest {
    private val url = FakeGateway.URL
    private val other = GatewayUrl.parse("https://work.example.ts.net")
    private val dao = InMemoryOfflineDao()
    private var clock = 1_000L
    private val cache = OfflineCache(dao, PlainSealer, EmptyCoroutineContext) { clock }

    private fun history(id: String) = """{"session_id":"$id","messages":[
        {"id":1,"role":"user","content":"hello $id"},{"id":2,"role":"assistant","content":"Hi from $id"}]}"""

    @Test
    fun everyChatReadThroughAChatSessionStaysSaved() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false).apply {
            answer = { call -> if (call.method == "session.resume") """{"session_id":"rt","running":false}""" else "{}" }
            http = { request ->
                val id = request.url.encodedPath.removePrefix("/api/sessions/").removeSuffix("/messages")
                json(history(id))
            }
            start(awaitConnected = false)
        }
        val count = 60 // past stock's limit of 50
        repeat(count) { n ->
            clock = 1_000L + n
            val chat = ChatSession(url, "chat-$n", "Chat $n", gateway.connection, SessionsApi(gateway.client), backgroundScope, cache = cache)
            chat.start()
            chat.state.first { it.historyLoaded && it.messages.size == 2 }
            testScheduler.runCurrent() // the copy is written after the rows show
            chat.stop()
        }

        repeat(count) { n ->
            val saved = assertNotNull(cache.savedTranscript(url, null, "chat-$n"), "chat-$n was dropped")
            assertEquals(listOf(1L, 2L), saved.rows.map { it.id })
        }
    }

    @Test
    fun onlyAnExplicitDeletionDropsASavedChatAndOnlyItsOwn() = runTest {
        repeat(55) { n ->
            clock = 1_000L + n
            cache.saveTranscript(url, null, "s$n", listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("m"))), "s$n")
        }
        // The same chat id under another profile and another gateway is another copy.
        cache.saveTranscript(url, "coder", "s0", listOf(SessionMessage(id = 7, role = "user")), "s0")
        cache.saveTranscript(other, null, "s0", listOf(SessionMessage(id = 8, role = "user")), "s0")
        cache.saveList(url, null, SessionListFilter.Recent, listOf(SessionSummary("s0"), SessionSummary("s1")))

        cache.forgetChat(url, null, "s0")

        assertNull(cache.savedTranscript(url, null, "s0"))
        assertEquals(listOf("s1"), cache.savedList(url, null, SessionListFilter.Recent)?.sessions?.map { it.id })
        assertEquals(listOf(7L), cache.savedTranscript(url, "coder", "s0")?.rows?.map { it.id })
        assertEquals(listOf(8L), cache.savedTranscript(other, null, "s0")?.rows?.map { it.id })
        (1 until 55).forEach { assertNotNull(cache.savedTranscript(url, null, "s$it")) }
    }
}
