package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OfflineCacheTest {

    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val work = GatewayUrl.parse("https://work.example.ts.net")
    private var clock = 1_000L
    private val dao = InMemoryOfflineDao()
    private val cache = OfflineCache(dao, PlainSealer, EmptyCoroutineContext) { clock }

    private fun message(id: Long, role: String, text: String = "m$id") = SessionMessage(id = id, role = role, content = JsonPrimitive(text))

    @Test
    fun aListComesBackInOrderForItsGatewayProfileAndFilterOnly() = runTest {
        val rows = listOf(SessionSummary("b", title = "Beta", pinned = true), SessionSummary("a", title = "Alpha"))
        cache.saveList(home, null, SessionListFilter.Recent, rows)

        val saved = assertNotNull(cache.savedList(home, null, SessionListFilter.Recent))
        assertEquals(rows, saved.sessions)
        assertEquals(1_000L, saved.savedAt)
        assertNull(cache.savedList(home, null, SessionListFilter.Archived))
        assertNull(cache.savedList(home, "coder", SessionListFilter.Recent))
        assertNull(cache.savedList(work, null, SessionListFilter.Recent))
    }

    @Test
    fun deletingABotForgetsOnlyItsProfilesChats() = runTest {
        cache.saveTranscript(home, "coder", "a", listOf(message(1, "user")), rowsSessionId = "a")
        cache.saveList(home, "coder", SessionListFilter.Recent, listOf(SessionSummary("a")))
        cache.saveTranscript(home, null, "b", listOf(message(1, "user")), rowsSessionId = "b")

        cache.forgetProfile(home, "coder")

        assertNull(cache.savedTranscript(home, "coder", "a"))
        assertNull(cache.savedList(home, "coder", SessionListFilter.Recent))
        assertNotNull(cache.savedTranscript(home, null, "b"))
    }

    @Test
    fun forgettingAChatDropsItsRowsAndItsPlaceInTheList() = runTest {
        cache.saveList(home, null, SessionListFilter.Recent, listOf(SessionSummary("a"), SessionSummary("b")))
        cache.saveTranscript(home, null, "a", listOf(message(1, "user")), rowsSessionId = "a")

        cache.forgetChat(home, null, "a")

        assertNull(cache.savedTranscript(home, null, "a"))
        assertEquals(listOf("b"), cache.savedList(home, null, SessionListFilter.Recent)?.sessions?.map { it.id })
    }

    @Test
    fun signingOutOfAGatewayForgetsOnlyItsChats() = runTest {
        cache.saveList(home, null, SessionListFilter.Recent, listOf(SessionSummary("a")))
        cache.saveTranscript(home, "coder", "a", listOf(message(1, "user")), rowsSessionId = "a")
        cache.saveList(work, null, SessionListFilter.Recent, listOf(SessionSummary("w")))

        cache.forgetGateway(home)

        assertNull(cache.savedList(home, null, SessionListFilter.Recent))
        assertNull(cache.savedTranscript(home, "coder", "a"))
        assertNotNull(cache.savedList(work, null, SessionListFilter.Recent))
    }

    @Test
    fun rowsThatNoLongerOpenAreNoSavedCopy() = runTest {
        val locked = OfflineCache(dao, object : Sealer {
            override suspend fun seal(plain: String) = plain.encodeToByteArray()
            override suspend fun open(sealed: ByteArray): String? = null
        }, EmptyCoroutineContext) { clock }
        locked.saveList(home, null, SessionListFilter.Recent, listOf(SessionSummary("a")))
        locked.saveTranscript(home, null, "a", listOf(message(1, "user")), rowsSessionId = "a")

        assertNull(locked.savedList(home, null, SessionListFilter.Recent))
        assertNull(locked.savedTranscript(home, null, "a"))
    }
}
