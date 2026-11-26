package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.custom.RetainedCopies
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A chat list as last read from the gateway; [savedAt] in epoch ms. */
data class SavedList(val sessions: List<SessionSummary>, val savedAt: Long)

/** A chat's newest rows as last read from the gateway; [savedAt] in epoch ms. */
data class SavedTranscript(
    val rows: List<SessionMessage>,
    val rowsSessionId: String?,
    val savedAt: Long,
)

/**
 * The device's copy of the chat list and of the chats read, so they still show while the gateway can't be reached.
 * What the gateway sends is added to what's saved ([RetainedCopies]); nothing here is ever sent back. Rows are sealed
 * with a device key before they're written. A failed read means no saved copy, and a failed write returns null and
 * leaves the saved copy as it was: the cache never stands in the way of the gateway.
 */
class OfflineCache(
    private val dao: OfflineDao,
    private val sealer: Sealer,
    /** Where sealing and JSON run; tests keep them on their own dispatcher. */
    private val work: CoroutineContext = Dispatchers.Default,
    private val now: () -> Long,
) {
    suspend fun savedList(gateway: GatewayUrl, profile: String?, filter: SessionListFilter): SavedList? = quietly {
        val rows = dao.list(gateway.value, profile.key, filter.name)
        if (rows.isEmpty()) return@quietly null
        // A row that no longer opens (the key was reset) spoils the order the list had: show none of it.
        val sessions = rows.map { open(it.row, SessionSummary.serializer()) ?: return@quietly null }
        SavedList(sessions, rows.minOf { it.savedAt })
    }

    /** Saves [sessions] in their order, followed by the saved chats they don't have, which stay. Null when it failed. */
    suspend fun saveList(gateway: GatewayUrl, profile: String?, filter: SessionListFilter, sessions: List<SessionSummary>) = quietly {
        val at = now()
        val saved = dao.list(gateway.value, profile.key, filter.name).map { open(it.row, SessionSummary.serializer()) }
        // Rows that no longer open (the key was reset) can't be kept readable: the new list stands alone then.
        val kept = if (saved.any { it == null }) emptyList() else saved.filterNotNull()
        val rows = RetainedCopies.mergeList(kept, sessions).mapIndexed { index, session ->
            SavedListRow(gateway.value, profile.key, filter.name, session.id, index, at, seal(session, SessionSummary.serializer()))
        }
        dao.replaceList(gateway.value, profile.key, filter.name, rows)
    }

    suspend fun savedTranscript(gateway: GatewayUrl, profile: String?, sessionId: String): SavedTranscript? = quietly {
        val transcript = dao.transcript(gateway.value, profile.key, sessionId) ?: return@quietly null
        val rows = savedRows(gateway, profile, sessionId) ?: return@quietly null
        if (rows.isEmpty()) return@quietly null
        SavedTranscript(rows, transcript.rowsSessionId, transcript.savedAt)
    }

    /** Every saved row of [sessionId], split rows put back together; null when one doesn't open. */
    private suspend fun savedRows(gateway: GatewayUrl, profile: String?, sessionId: String): List<SessionMessage>? {
        val texts = dao.messages(gateway.value, profile.key, sessionId).map { sealer.open(it.row) ?: return null }
        return RetainedCopies.join(texts)?.map { text ->
            runCatching { json.decodeFromString(SessionMessage.serializer(), text) }.getOrNull() ?: return null
        }
    }

    /**
     * Adds [rows] to what [sessionId] has saved: rows read again replace their saved copy, and the rest stay
     * ([RetainedCopies.mergeRows]). A row too big for one database row is kept whole, in pieces. Null when it failed;
     * the saved copy is then as it was.
     */
    suspend fun saveTranscript(
        gateway: GatewayUrl,
        profile: String?,
        sessionId: String,
        rows: List<SessionMessage>,
        rowsSessionId: String?,
    ) = quietly {
        // Rows that no longer open (the key was reset) can't be kept readable: the new rows stand alone then.
        val saved = savedRows(gateway, profile, sessionId).orEmpty()
        val merged = RetainedCopies.mergeRows(saved, rows)
        if (merged.isEmpty()) return@quietly Unit
        val texts = merged.flatMap { row -> RetainedCopies.split(json.encodeToString(SessionMessage.serializer(), row), MAX_PIECE_CHARS) }
        val sealed = texts.map { text -> sealer.seal(text).also { check(it.size <= MAX_ROW_BYTES) { "A saved row is too big." } } }
        val transcript = SavedTranscriptRow(
            gateway = gateway.value,
            profile = profile.key,
            sessionId = sessionId,
            rowsSessionId = rowsSessionId,
            savedAt = now(),
        )
        dao.replaceTranscript(transcript, sealed.mapIndexed { index, row -> SavedMessageRow(gateway.value, profile.key, sessionId, index, row) })
    }

    /** Drops [sessionId] from the device: it was deleted, or the gateway no longer has it. */
    suspend fun forgetChat(gateway: GatewayUrl, profile: String?, sessionId: String) = quietly {
        dao.forgetTranscript(gateway.value, profile.key, sessionId)
        dao.removeListRow(gateway.value, profile.key, sessionId)
    }

    /** Drops everything saved from [gateway]. Signing out keeps the copies ([detachGateway]); this is for deleting them. */
    suspend fun forgetGateway(gateway: GatewayUrl) = quietly { dao.forgetGateway(gateway.value) }

    /**
     * Signing out of [gateway] or removing it: its copies stay on the device under a key no gateway reads
     * ([RetainedCopies.detachedKey]), so whoever signs in next, to it or another, doesn't see them. Null when it failed.
     */
    suspend fun detachGateway(gateway: GatewayUrl) = quietly { dao.moveGateway(gateway.value, RetainedCopies.detachedKey(gateway.value, now())) }

    /** Drops everything saved from [profile] on [gateway]: it was deleted (a bot, with its chats). */
    suspend fun forgetProfile(gateway: GatewayUrl, profile: String) = quietly { dao.forgetProfile(gateway.value, profile) }

    private suspend fun <T> seal(value: T, serializer: KSerializer<T>): ByteArray = sealer.seal(json.encodeToString(serializer, value))

    private suspend fun <T> open(bytes: ByteArray, serializer: KSerializer<T>): T? =
        sealer.open(bytes)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    // Off the main thread: sealing and JSON for a hundred rows is real work.
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        withContext(work) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private val String?.key: String get() = this ?: ""

    companion object {
        /** Android reads a database row through a 2 MB window; no sealed row is written over this. */
        const val MAX_ROW_BYTES = 1_000_000

        /** A piece of a row too big for one database row: at most 3 UTF-8 bytes each, well under [MAX_ROW_BYTES]. */
        const val MAX_PIECE_CHARS = 200_000

        private val json = Json { ignoreUnknownKeys = true }
    }
}
