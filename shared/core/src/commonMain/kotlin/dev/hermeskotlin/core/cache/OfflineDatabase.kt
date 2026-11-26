package dev.hermeskotlin.core.cache

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction

/**
 * One row of a saved chat list: [gateway], [profile] ("" for the launch profile) and [filter] name the list,
 * [position] keeps its order. [row] is the sealed `SessionSummary` JSON.
 */
@Entity(tableName = "saved_list_row", primaryKeys = ["gateway", "profile", "filter", "id"])
class SavedListRow(
    val gateway: String,
    val profile: String,
    val filter: String,
    val id: String,
    val position: Int,
    val savedAt: Long,
    val row: ByteArray,
)

/** A chat whose newest rows are saved; [rowsSessionId] is the session the rows were read under. */
@Entity(tableName = "saved_transcript", primaryKeys = ["gateway", "profile", "sessionId"])
class SavedTranscriptRow(
    val gateway: String,
    val profile: String,
    val sessionId: String,
    val rowsSessionId: String?,
    val savedAt: Long,
)

/** One stored message of a saved chat, in order; [row] is the sealed `SessionMessage` JSON. */
@Entity(tableName = "saved_message", primaryKeys = ["gateway", "profile", "sessionId", "position"])
class SavedMessageRow(
    val gateway: String,
    val profile: String,
    val sessionId: String,
    val position: Int,
    val row: ByteArray,
)

/** What [OfflineCache] reads and writes; whole lists and transcripts are replaced at once. */
@Dao
interface OfflineDao {
    @Query("SELECT * FROM saved_list_row WHERE gateway = :gateway AND profile = :profile AND filter = :filter ORDER BY position")
    suspend fun list(gateway: String, profile: String, filter: String): List<SavedListRow>

    @Query("DELETE FROM saved_list_row WHERE gateway = :gateway AND profile = :profile AND filter = :filter")
    suspend fun clearList(gateway: String, profile: String, filter: String)

    @Insert
    suspend fun insertList(rows: List<SavedListRow>)

    @Transaction
    suspend fun replaceList(gateway: String, profile: String, filter: String, rows: List<SavedListRow>) {
        clearList(gateway, profile, filter)
        insertList(rows)
    }

    @Query("DELETE FROM saved_list_row WHERE gateway = :gateway AND profile = :profile AND id = :id")
    suspend fun removeListRow(gateway: String, profile: String, id: String)

    @Query("SELECT * FROM saved_transcript WHERE gateway = :gateway AND profile = :profile AND sessionId = :sessionId")
    suspend fun transcript(gateway: String, profile: String, sessionId: String): SavedTranscriptRow?

    @Query("SELECT * FROM saved_message WHERE gateway = :gateway AND profile = :profile AND sessionId = :sessionId ORDER BY position")
    suspend fun messages(gateway: String, profile: String, sessionId: String): List<SavedMessageRow>

    @Insert
    suspend fun insertTranscript(transcript: SavedTranscriptRow)

    @Insert
    suspend fun insertMessages(rows: List<SavedMessageRow>)

    @Query("DELETE FROM saved_transcript WHERE gateway = :gateway AND profile = :profile AND sessionId = :sessionId")
    suspend fun clearTranscript(gateway: String, profile: String, sessionId: String)

    @Query("DELETE FROM saved_message WHERE gateway = :gateway AND profile = :profile AND sessionId = :sessionId")
    suspend fun clearMessages(gateway: String, profile: String, sessionId: String)

    @Transaction
    suspend fun replaceTranscript(transcript: SavedTranscriptRow, rows: List<SavedMessageRow>) {
        forgetTranscript(transcript.gateway, transcript.profile, transcript.sessionId)
        insertTranscript(transcript)
        insertMessages(rows)
    }

    @Transaction
    suspend fun forgetTranscript(gateway: String, profile: String, sessionId: String) {
        clearTranscript(gateway, profile, sessionId)
        clearMessages(gateway, profile, sessionId)
    }

    /** The saved chats past the newest [keep], to drop. */
    @Query("SELECT * FROM saved_transcript ORDER BY savedAt DESC LIMIT -1 OFFSET :keep")
    suspend fun transcriptsBeyond(keep: Int): List<SavedTranscriptRow>

    @Query("DELETE FROM saved_list_row WHERE gateway = :gateway")
    suspend fun clearGatewayLists(gateway: String)

    @Query("DELETE FROM saved_transcript WHERE gateway = :gateway")
    suspend fun clearGatewayTranscripts(gateway: String)

    @Query("DELETE FROM saved_message WHERE gateway = :gateway")
    suspend fun clearGatewayMessages(gateway: String)

    @Query("DELETE FROM saved_list_row WHERE gateway = :gateway AND profile = :profile")
    suspend fun clearProfileLists(gateway: String, profile: String)

    @Query("DELETE FROM saved_transcript WHERE gateway = :gateway AND profile = :profile")
    suspend fun clearProfileTranscripts(gateway: String, profile: String)

    @Query("DELETE FROM saved_message WHERE gateway = :gateway AND profile = :profile")
    suspend fun clearProfileMessages(gateway: String, profile: String)

    @Transaction
    suspend fun forgetProfile(gateway: String, profile: String) {
        clearProfileLists(gateway, profile)
        clearProfileTranscripts(gateway, profile)
        clearProfileMessages(gateway, profile)
    }

    @Transaction
    suspend fun forgetGateway(gateway: String) {
        clearGatewayLists(gateway)
        clearGatewayTranscripts(gateway)
        clearGatewayMessages(gateway)
    }

    // Fork: moving a gateway's copies to another key (see OfflineCache.detachGateway). No new table or column.
    @Query("UPDATE saved_list_row SET gateway = :to WHERE gateway = :from")
    suspend fun moveGatewayLists(from: String, to: String)

    @Query("UPDATE saved_transcript SET gateway = :to WHERE gateway = :from")
    suspend fun moveGatewayTranscripts(from: String, to: String)

    @Query("UPDATE saved_message SET gateway = :to WHERE gateway = :from")
    suspend fun moveGatewayMessages(from: String, to: String)

    /** Every gateway key a saved chat is under, detached ones too. */
    @Query("SELECT DISTINCT gateway FROM saved_transcript")
    suspend fun transcriptGateways(): List<String>

    /** Every gateway key a saved list is under, detached ones too. */
    @Query("SELECT DISTINCT gateway FROM saved_list_row")
    suspend fun listGateways(): List<String>

    @Query("SELECT * FROM saved_transcript WHERE gateway = :gateway")
    suspend fun transcriptsOf(gateway: String): List<SavedTranscriptRow>

    @Query("SELECT * FROM saved_list_row WHERE gateway = :gateway ORDER BY position")
    suspend fun listsOf(gateway: String): List<SavedListRow>

    /** Copies a saved chat's rows, unchanged, to the gateway key [to]: kept aside before its readable rows are saved again. */
    @Transaction
    suspend fun replaceTranscriptSettingAside(transcript: SavedTranscriptRow, rows: List<SavedMessageRow>, to: String) {
        val old = transcript(transcript.gateway, transcript.profile, transcript.sessionId)
        if (old != null) insertTranscript(SavedTranscriptRow(to, old.profile, old.sessionId, old.rowsSessionId, old.savedAt))
        insertMessages(messages(transcript.gateway, transcript.profile, transcript.sessionId).map {
            SavedMessageRow(to, it.profile, it.sessionId, it.position, it.row)
        })
        replaceTranscript(transcript, rows)
    }

    /** As [replaceTranscriptSettingAside], for a saved list. */
    @Transaction
    suspend fun replaceListSettingAside(gateway: String, profile: String, filter: String, rows: List<SavedListRow>, to: String) {
        insertList(list(gateway, profile, filter).map { SavedListRow(to, it.profile, it.filter, it.id, it.position, it.savedAt, it.row) })
        replaceList(gateway, profile, filter, rows)
    }

    @Transaction
    suspend fun moveGateway(from: String, to: String) {
        moveGatewayLists(from, to)
        moveGatewayTranscripts(from, to)
        moveGatewayMessages(from, to)
    }
}

/** The copy of chats kept on the device, so they open without the gateway. */
@Database(entities = [SavedListRow::class, SavedTranscriptRow::class, SavedMessageRow::class], version = 1)
@ConstructedBy(OfflineDatabaseConstructor::class)
abstract class OfflineDatabase : RoomDatabase() {
    abstract fun dao(): OfflineDao
}

// Room's compiler writes the actual.
@Suppress("KotlinNoActualForExpect")
expect object OfflineDatabaseConstructor : RoomDatabaseConstructor<OfflineDatabase> {
    override fun initialize(): OfflineDatabase
}
