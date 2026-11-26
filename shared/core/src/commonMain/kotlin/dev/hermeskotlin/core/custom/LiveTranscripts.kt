package dev.hermeskotlin.core.custom

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.voice.LiveFragment
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * One piece of a live call's transcript as the call sent it (requirements §8): [speaker] is `user`, `assistant`, or
 * `raw` for an event that should have been transcript but couldn't be read, kept as it came. [sealed] is the text
 * (or the raw event), encrypted as the offline cache's rows are.
 *
 * Kept by [owner] (the gateway's verified account, or [LiveTranscripts.UNVERIFIED]), [gateway] and [sessionId] (the
 * chat's stored session; "" until the call's chat has one). Nothing trims or expires them.
 */
@Entity(
    tableName = "live_transcript_piece",
    primaryKeys = ["owner", "gateway", "callId", "seq"],
    indices = [Index(value = ["owner", "gateway", "sessionId"])],
)
class LiveTranscriptPiece(
    val owner: String,
    val gateway: String,
    val sessionId: String,
    val callId: String,
    val seq: Int,
    /** `phone` or `watch`: which one held the call. */
    val device: String,
    val speaker: String,
    val startMs: Long,
    val endMs: Long,
    /** When the call started, on this phone's clock (epoch ms): where it sits among the chat's messages. */
    val callStartedAt: Long,
    val receivedAt: Long,
    val sealed: ByteArray,
)

@Dao
interface LiveTranscriptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(piece: LiveTranscriptPiece): Long

    @Query("UPDATE live_transcript_piece SET sessionId = :sessionId WHERE owner = :owner AND gateway = :gateway AND callId = :callId AND sessionId = ''")
    suspend fun assign(owner: String, gateway: String, callId: String, sessionId: String)

    @Query("SELECT * FROM live_transcript_piece WHERE owner = :owner AND gateway = :gateway AND sessionId = :sessionId ORDER BY callStartedAt, callId, seq")
    suspend fun session(owner: String, gateway: String, sessionId: String): List<LiveTranscriptPiece>

    @Query("SELECT * FROM live_transcript_piece WHERE callId = :callId ORDER BY seq")
    suspend fun call(callId: String): List<LiveTranscriptPiece>
}

/**
 * Fork: live call transcripts, apart from the offline cache, so deleting saved chats or messages (or the cache's own
 * migrations, which start it over) never touches them. Version 1; a later one needs a real migration.
 */
@Database(entities = [LiveTranscriptPiece::class], version = 1, exportSchema = false)
@ConstructedBy(LiveTranscriptDatabaseConstructor::class)
abstract class LiveTranscriptDatabase : RoomDatabase() {
    abstract fun dao(): LiveTranscriptDao
}

// Room's compiler writes the actual.
@Suppress("KotlinNoActualForExpect")
expect object LiveTranscriptDatabaseConstructor : RoomDatabaseConstructor<LiveTranscriptDatabase> {
    override fun initialize(): LiveTranscriptDatabase
}

/**
 * Fork customization (requirements §8): the Phone's and the Watch's actual live call transcripts, kept on the phone
 * by verified account, gateway and the chat's session, so a later call in the same chat opens knowing what was said.
 *
 * - Each call is [begin]ed once, then every transcript piece it sends is [Recording.add]ed, in order, once.
 * - A call's chat may get its stored session only during the call: its pieces wait under "" and move to it then.
 * - [earlier] gives the same account's and session's earlier calls, for the next call's opening history; another
 *   account's, an unverified one's, another session's or an unassigned call's never.
 * - What can't be written stays in memory and is tried again; what can't be read stays on disk as it is.
 */
class LiveTranscripts(
    private val dao: LiveTranscriptDao,
    private val sealer: Sealer,
    /** The gateway's verified account (`SessionBindings.owner`); null when it isn't known. */
    private val owner: suspend (GatewayUrl) -> String?,
    private val now: () -> Long,
    private val newId: () -> String = { "call-" + Random.nextLong().toULong().toString(36) + Random.nextLong().toULong().toString(36) },
) {
    enum class Device(val key: String) { Phone("phone"), Watch("watch") }

    /** One earlier call: when it started (epoch ms) and what was said, turn by turn. */
    data class EarlierCall(val startedAt: Long, val turns: List<Pair<LiveFragment.Speaker, String>>)

    /** Earlier calls in a session, and how many pieces of them couldn't be read (they stay on disk). */
    data class Earlier(val calls: List<EarlierCall>, val unreadable: Int, val failed: Boolean = false)

    /** Starts keeping a call held by [device], in [gateway]'s chat with stored session [sessionId] (null: none yet). */
    suspend fun begin(gateway: GatewayUrl, sessionId: String?, device: Device, callId: String = newId()): Recording {
        val account = ownerOf(gateway) ?: UNVERIFIED
        return Recording(account, gateway.value, sessionId.orEmpty(), callId, device, now())
    }

    /** The earlier calls of [sessionId] in [gateway] for its verified account, oldest first. */
    suspend fun earlier(gateway: GatewayUrl, sessionId: String?, except: String? = null): Earlier {
        if (sessionId.isNullOrEmpty()) return Earlier(emptyList(), 0)
        val account = ownerOf(gateway) ?: return Earlier(emptyList(), 0)
        val pieces = try {
            dao.session(account, gateway.value, sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Earlier(emptyList(), 0, failed = true)
        }
        var unreadable = 0
        val calls = pieces.filter { it.callId != except }.groupBy { it.callId }.values.mapNotNull { call ->
            val turns = mutableListOf<Pair<LiveFragment.Speaker, StringBuilder>>()
            for (piece in call) {
                val speaker = when (piece.speaker) {
                    USER -> LiveFragment.Speaker.User
                    ASSISTANT -> LiveFragment.Speaker.Assistant
                    else -> continue
                }
                val text = try { sealer.open(piece.sealed) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (text == null) {
                    unreadable++
                    continue
                }
                val last = turns.lastOrNull()
                if (last != null && last.first == speaker) last.second.append(text) else turns += speaker to StringBuilder(text)
            }
            val said = turns.map { (speaker, text) -> speaker to text.toString() }.filter { it.second.isNotBlank() }
            if (said.isEmpty()) null else EarlierCall(call.first().callStartedAt, said)
        }
        return Earlier(calls, unreadable)
    }

    private suspend fun ownerOf(gateway: GatewayUrl): String? =
        try { owner(gateway) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** One call being kept. Not thread-safe: the call's events come one at a time. */
    inner class Recording internal constructor(
        private val account: String,
        private val gateway: String,
        private var sessionId: String,
        val callId: String,
        private val device: Device,
        private val startedAt: Long,
    ) {
        private var seq = 0
        private val pending = ArrayDeque<LiveTranscriptPiece>()
        /** Timed pieces already taken, so the same piece told twice is kept once. */
        private val seen = HashSet<String>()
        private var assignPending = false

        /** Whether everything taken so far is on disk. */
        val saved: Boolean get() = pending.isEmpty() && unsealed.isEmpty() && !assignPending

        /** Keeps [fragment]; false when it couldn't be written yet (it's kept here and tried again). */
        suspend fun add(fragment: LiveFragment): Boolean {
            if (fragment.text.isEmpty()) return flush()
            // Untimed pieces can't be told apart from words said twice; timed ones can.
            if (fragment.startMs > 0 || fragment.endMs > 0) {
                val identity = "${fragment.speaker}:${fragment.startMs}:${fragment.endMs}:${fragment.text}"
                if (!seen.add(identity)) return flush()
            }
            val speaker = if (fragment.speaker == LiveFragment.Speaker.User) USER else ASSISTANT
            return keep(speaker, fragment.text, fragment.startMs, fragment.endMs)
        }

        /** Keeps an event that should have been transcript but couldn't be read, as it came; never used as context. */
        suspend fun raw(event: String): Boolean = keep(RAW, event, 0, 0)

        /** The call's chat got its stored session: the call's pieces so far, and the rest, go to it. */
        suspend fun session(id: String): Boolean {
            if (id.isEmpty() || sessionId == id) return flush()
            if (sessionId.isNotEmpty()) return flush()
            sessionId = id
            assignPending = true
            return flush()
        }

        /** Writes what's waiting; false when some of it still can't be. */
        suspend fun flush(): Boolean {
            try {
                while (pending.isNotEmpty()) {
                    val next = pending.first()
                    val piece = if (next.sessionId == sessionId) next else next.copy(sessionId = sessionId)
                    dao.insert(piece)
                    pending.removeFirst()
                }
                if (assignPending && sessionId.isNotEmpty()) {
                    dao.assign(account, gateway, callId, sessionId)
                    assignPending = false
                }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
        }

        private suspend fun keep(speaker: String, text: String, startMs: Long, endMs: Long): Boolean {
            val sealed = try { sealer.seal(text) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            // Never written in the clear: a piece that can't be sealed waits here, as it came, and is tried again.
            if (sealed == null) {
                unsealed += Unsealed(seq++, speaker, text, startMs, endMs, now())
                return false
            }
            pending += piece(seq++, speaker, startMs, endMs, now(), sealed)
            return resealAndFlush()
        }

        private val unsealed = ArrayDeque<Unsealed>()

        private suspend fun resealAndFlush(): Boolean {
            while (unsealed.isNotEmpty()) {
                val u = unsealed.first()
                val sealed = try { sealer.seal(u.text) } catch (e: CancellationException) { throw e } catch (_: Exception) { return false }
                pending += piece(u.seq, u.speaker, u.startMs, u.endMs, u.at, sealed)
                unsealed.removeFirst()
            }
            return flush()
        }

        private fun piece(seq: Int, speaker: String, startMs: Long, endMs: Long, at: Long, sealed: ByteArray) =
            LiveTranscriptPiece(account, gateway, sessionId, callId, seq, device.key, speaker, startMs, endMs, startedAt, at, sealed)
    }

    /** A piece that couldn't be sealed yet: kept in memory as it came, never written in the clear. */
    private class Unsealed(val seq: Int, val speaker: String, val text: String, val startMs: Long, val endMs: Long, val at: Long)

    private fun LiveTranscriptPiece.copy(sessionId: String) =
        LiveTranscriptPiece(owner, gateway, sessionId, callId, seq, device, speaker, startMs, endMs, callStartedAt, receivedAt, sealed)

    companion object {
        /** Where a call of a gateway whose account isn't verified is kept: never given to any call as context. */
        const val UNVERIFIED = "\u0001unverified"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val RAW = "raw"
    }
}

/** One message of the chat as the call's opening history sees it: [at] is its time in epoch seconds, when it has one. */
data class ChatTurn(val at: Double?, val speaker: LiveFragment.Speaker, val text: String)

/**
 * Fork: the chat's turns with the session's earlier live calls put where they happened, oldest first, for the next
 * call's opening history (`liveHistory`, which keeps the newest that fit). A call goes before the first message
 * stamped after it started; calls after the last stamped message go at the end. Each earlier call is put in once.
 */
fun withEarlierCalls(chat: List<ChatTurn>, earlier: List<LiveTranscripts.EarlierCall>): List<Pair<LiveFragment.Speaker, String>> {
    val calls = earlier.sortedBy { it.startedAt }
    var next = 0
    val out = mutableListOf<Pair<LiveFragment.Speaker, String>>()
    for (turn in chat) {
        val at = turn.at
        if (at != null) {
            while (next < calls.size && calls[next].startedAt <= (at * 1000).toLong()) out += calls[next++].turns
        }
        out += turn.speaker to turn.text
    }
    while (next < calls.size) out += calls[next++].turns
    return out
}
