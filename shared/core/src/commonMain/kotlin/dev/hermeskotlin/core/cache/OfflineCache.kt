package dev.hermeskotlin.core.cache

import dev.hermeskotlin.core.custom.RetainedCopies
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

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
 *
 * Fork: copies belong to the account that was signed in when they were read ([bindOwner]). Rows that no longer read
 * are set aside, never dropped with the readable ones; copies put away (signed out, another account, unreadable)
 * stay on the device until deleted on purpose ([forgetPutAway]).
 */
class OfflineCache(
    private val dao: OfflineDao,
    private val sealer: Sealer,
    /** Where sealing and JSON run; tests keep them on their own dispatcher. */
    private val work: CoroutineContext = Dispatchers.Default,
    private val now: () -> Long,
) {
    /** Fork: one write at a time, so a chat's read-add-write can't straddle its gateway's copies being put away. */
    private val writes = Mutex()

    suspend fun savedList(gateway: GatewayUrl, profile: String?, filter: SessionListFilter): SavedList? = quietly {
        val rows = dao.list(gateway.value, profile.key, filter.name)
        if (rows.isEmpty()) return@quietly null
        // A row that no longer opens (the key was reset) spoils the order the list had: show none of it.
        val sessions = rows.map { open(it.row, SessionSummary.serializer()) ?: return@quietly null }
        SavedList(sessions, rows.minOf { it.savedAt })
    }

    /** Saves [sessions] in their order, followed by the saved chats they don't have, which stay. Null when it failed. */
    suspend fun saveList(gateway: GatewayUrl, profile: String?, filter: SessionListFilter, sessions: List<SessionSummary>) = writing {
        writeList(gateway.value, profile.key, filter.name, sessions)
    }

    private suspend fun writeList(gateway: String, profile: String, filter: String, sessions: List<SessionSummary>) {
        val at = now()
        val stored = dao.list(gateway, profile, filter)
        val saved = stored.map { open(it.row, SessionSummary.serializer()) }
        val rows = RetainedCopies.mergeList(saved.filterNotNull(), sessions).mapIndexed { index, session ->
            SavedListRow(gateway, profile, filter, session.id, index, at, seal(session, SessionSummary.serializer()))
        }
        // Entries that no longer read are set aside as they are, not dropped with the rest.
        if (saved.any { it == null }) dao.replaceListSettingAside(gateway, profile, filter, rows, RetainedCopies.unreadableKey(gateway, at))
        else dao.replaceList(gateway, profile, filter, rows)
    }

    suspend fun savedTranscript(gateway: GatewayUrl, profile: String?, sessionId: String): SavedTranscript? = quietly {
        val transcript = dao.transcript(gateway.value, profile.key, sessionId) ?: return@quietly null
        val read = savedRows(gateway.value, profile.key, sessionId)
        // Some row didn't read: none of it shows, as a changed order would mislead. The next save sets it aside.
        if (!read.intact || read.rows.isEmpty()) return@quietly null
        SavedTranscript(read.rows, transcript.rowsSessionId, transcript.savedAt)
    }

    private class Rows(val rows: List<SessionMessage>, val intact: Boolean)

    /** Every saved row of [sessionId] that reads, split rows put back together, and whether all of them did. */
    private suspend fun savedRows(gateway: String, profile: String, sessionId: String): Rows {
        val read = RetainedCopies.joinReadable(dao.messages(gateway, profile, sessionId).map { sealer.open(it.row) })
        var intact = read.intact
        val rows = read.texts.mapNotNull { text ->
            runCatching { json.decodeFromString(SessionMessage.serializer(), text) }.getOrNull().also { if (it == null) intact = false }
        }
        return Rows(rows, intact)
    }

    /**
     * Adds [rows] to what [sessionId] has saved: rows read again replace their saved copy, and the rest stay
     * ([RetainedCopies.mergeRows]). A row too big for one database row is kept whole, in pieces. Saved rows that no
     * longer read are set aside unchanged. Null when it failed; the saved copy is then as it was.
     */
    suspend fun saveTranscript(
        gateway: GatewayUrl,
        profile: String?,
        sessionId: String,
        rows: List<SessionMessage>,
        rowsSessionId: String?,
    ) = writing { writeTranscript(gateway.value, profile.key, sessionId, rows, rowsSessionId, now()) }

    private suspend fun writeTranscript(gateway: String, profile: String, sessionId: String, rows: List<SessionMessage>, rowsSessionId: String?, savedAt: Long) {
        val saved = savedRows(gateway, profile, sessionId)
        val merged = RetainedCopies.mergeRows(saved.rows, rows)
        if (merged.isEmpty()) return
        val texts = merged.flatMap { row -> RetainedCopies.split(json.encodeToString(SessionMessage.serializer(), row), MAX_PIECE_CHARS) }
        val sealed = texts.map { text -> sealer.seal(text).also { check(it.size <= MAX_ROW_BYTES) { "A saved row is too big." } } }
        val transcript = SavedTranscriptRow(gateway = gateway, profile = profile, sessionId = sessionId, rowsSessionId = rowsSessionId, savedAt = savedAt)
        val written = sealed.mapIndexed { index, row -> SavedMessageRow(gateway, profile, sessionId, index, row) }
        if (saved.intact) dao.replaceTranscript(transcript, written)
        else dao.replaceTranscriptSettingAside(transcript, written, RetainedCopies.unreadableKey(gateway, now()))
    }

    /** Drops [sessionId] from the device: the person deleted it. */
    suspend fun forgetChat(gateway: GatewayUrl, profile: String?, sessionId: String) = writing {
        dao.forgetTranscript(gateway.value, profile.key, sessionId)
        dao.removeListRow(gateway.value, profile.key, sessionId)
    }

    /** Drops everything saved from [gateway]. Signing out keeps the copies ([detachGateway]); this is for deleting them. */
    suspend fun forgetGateway(gateway: GatewayUrl) = writing { dao.forgetGateway(gateway.value) }

    /**
     * Signing out of [gateway] or removing it: its copies, with their owner, stay on the device under a key no gateway
     * reads ([RetainedCopies.detachedKey]), so whoever signs in next, to it or another, doesn't see them. The same
     * account signing in again gets them back ([bindOwner]). Null when it failed.
     */
    suspend fun detachGateway(gateway: GatewayUrl) = writing { detach(gateway.value) }

    private suspend fun detach(gateway: String) = dao.moveGateway(gateway, RetainedCopies.detachedKey(gateway, now()))

    /**
     * Gives [gateway]'s copies to the account just verified there ([owner], from [RetainedCopies.owner]; null when it
     * couldn't be verified). Copies kept for another account, or for no known account, are detached first; then the
     * copies detached for [owner] come back. False when the copies that aren't [owner]'s couldn't be put away: the
     * new account must not go on then, or it would read them. A failure after that only leaves copies detached.
     */
    suspend fun bindOwner(gateway: GatewayUrl, owner: String?): Boolean = withContext(work) {
        writes.withLock {
            val live = gateway.value
            val current = try { ownerOf(live) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (owner != null && current == owner) return@withLock true.also { bringBack(live, owner) }
            val hasCopies = try {
                dao.transcriptsOf(live).isNotEmpty() || dao.listsOf(live).isNotEmpty()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withLock false }
            if (hasCopies) {
                try { detach(live) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withLock false }
            }
            if (owner == null) return@withLock true
            try {
                dao.replaceTranscript(SavedTranscriptRow(live, RetainedCopies.OWNER_PROFILE, RetainedCopies.OWNER_SESSION, null, now()),
                    listOf(SavedMessageRow(live, RetainedCopies.OWNER_PROFILE, RetainedCopies.OWNER_SESSION, 0, sealer.seal(owner))))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing of another account is under the gateway now; without an owner recorded, what's saved next
                // is put away at the next sign-in.
                return@withLock true
            }
            bringBack(live, owner)
            true
        }
    }

    /**
     * Brings [owner]'s detached copies back under [live]. A failure leaves them detached, for the next sign-in: there's
     * nothing of another account under [live] by then.
     */
    private suspend fun bringBack(live: String, owner: String) {
        try {
            for (key in putAwayKeys(live)) if (RetainedCopies.isDetached(key) && ownerOf(key) == owner) absorb(key, live)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** The owner recorded under [key], null when none is or it doesn't read. */
    private suspend fun ownerOf(key: String): String? =
        dao.messages(key, RetainedCopies.OWNER_PROFILE, RetainedCopies.OWNER_SESSION).singleOrNull()?.let { sealer.open(it.row) }

    private suspend fun putAwayKeys(gateway: String?): List<String> =
        (dao.transcriptGateways() + dao.listGateways()).distinct().filter { RetainedCopies.isPutAway(it, gateway) }.sorted()

    /** Adds the copies under [from] to [to]'s, then drops [from], unless some of it didn't read: that stays put away. */
    private suspend fun absorb(from: String, to: String) {
        var intact = true
        for (transcript in dao.transcriptsOf(from)) {
            if (transcript.profile == RetainedCopies.OWNER_PROFILE) continue
            val read = savedRows(from, transcript.profile, transcript.sessionId)
            if (!read.intact) intact = false
            // Rows read since signing in are newer than their detached copies: they win.
            val since = savedRows(to, transcript.profile, transcript.sessionId).rows
            if (read.rows.isNotEmpty()) {
                writeTranscript(to, transcript.profile, transcript.sessionId, RetainedCopies.mergeRows(read.rows, since), transcript.rowsSessionId, transcript.savedAt)
            }
        }
        for ((list, rows) in dao.listsOf(from).groupBy { it.profile to it.filter }) {
            val detached = rows.map { open(it.row, SessionSummary.serializer()) }
            if (detached.any { it == null }) intact = false
            val current = dao.list(to, list.first, list.second).mapNotNull { open(it.row, SessionSummary.serializer()) }
            // The detached list comes first as the saved one; what was read since leads.
            writeList(to, list.first, list.second, RetainedCopies.mergeList(detached.filterNotNull(), current))
        }
        if (intact) dao.forgetGateway(from)
    }

    /**
     * Deletes copies put away from [gateway] (from every gateway when null): detached ones, another account's or
     * this one's, and rows set aside as unreadable. For the person deleting saved chats on purpose. Null when it failed.
     */
    suspend fun forgetPutAway(gateway: GatewayUrl? = null) = writing {
        for (key in putAwayKeys(gateway?.value)) dao.forgetGateway(key)
    }

    /** Drops everything saved from [profile] on [gateway]: it was deleted (a bot, with its chats). */
    suspend fun forgetProfile(gateway: GatewayUrl, profile: String) = writing { dao.forgetProfile(gateway.value, profile) }

    private suspend fun <T> seal(value: T, serializer: KSerializer<T>): ByteArray = sealer.seal(json.encodeToString(serializer, value))

    private suspend fun <T> open(bytes: ByteArray, serializer: KSerializer<T>): T? =
        sealer.open(bytes)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private suspend fun <T> writing(block: suspend () -> T): T? = quietly { writes.withLock { block() } }

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
