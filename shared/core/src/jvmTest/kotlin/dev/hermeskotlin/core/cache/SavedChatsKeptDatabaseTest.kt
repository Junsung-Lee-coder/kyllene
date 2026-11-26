package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Fork behaviour on the real Room database the apps use: more than 50 saved chats all stay, deletion still works. */
class SavedChatsKeptDatabaseTest {
    @Test
    fun theDatabaseKeepsEverySavedChat(): Unit = runBlocking {
        val dir = Files.createTempDirectory("saved-chats").toFile()
        val database = offlineDatabase(dir)
        try {
            var clock = 1_000L
            val cache = OfflineCache(database.dao(), PlainSealer, EmptyCoroutineContext) { clock }
            val home = GatewayUrl.parse("https://hermes.example.ts.net")
            repeat(80) { n ->
                clock = 1_000L + n
                cache.saveTranscript(home, null, "s$n", listOf(SessionMessage(id = n.toLong(), role = "user", content = JsonPrimitive("m$n"))), "s$n")
            }
            repeat(80) { n -> assertEquals(listOf(n.toLong()), assertNotNull(cache.savedTranscript(home, null, "s$n")).rows.map { it.id }) }

            cache.forgetChat(home, null, "s3")
            assertNull(cache.savedTranscript(home, null, "s3"))
            assertNotNull(cache.savedTranscript(home, null, "s4"))
        } finally {
            database.close()
            dir.deleteRecursively()
        }
    }
}
