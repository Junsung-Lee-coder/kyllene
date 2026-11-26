package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.sha256
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.wearlink.HISTORY_PAGE_ROWS
import dev.hermeskotlin.wearlink.HistoryMessage
import dev.hermeskotlin.wearlink.HistoryPage
import dev.hermeskotlin.wearlink.HistoryRequest
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.historyParts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork customization (requirements §9.1): reads a conversation's stored messages for the watch, a page at a time, only
 * when the watch asks. Read-only: it never creates or resumes a chat, a bot's included (its canonical stored chat, as
 * upstream Kyllene PR #370 resolves it). Whole messages go, in parts when long ([historyParts]); nothing is clipped.
 *
 * Each page carries the gateway's verified account as an opaque key ([accountKey]); when the account isn't confirmed
 * (a binding still pending) nothing is read and the page says so: the watch never gets content it can't place.
 */
class WatchHistoryReader(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val profiles: ProfileStore,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val bots: BotsApi,
) {
    suspend fun read(request: HistoryRequest): HistoryPage {
        fun failed(message: String, account: String? = null) = HistoryPage(request, account = account, error = message)
        val saved = gateways.current() ?: return failed(SIGNED_OUT)
        val gateway = saved.gatewayUrl
        if (!auth.hasStoredSession(gateway) || connection.state.value is ConnectionState.SessionExpired) return failed(SIGNED_OUT)
        val account = accountKey(gateway) ?: return failed(UNCONFIRMED)
        suspend fun current(): Boolean = gateways.current()?.gatewayUrl == gateway && auth.hasStoredSession(gateway) &&
            connection.state.value !is ConnectionState.SessionExpired && accountKey(gateway) == account &&
            request.scope == WatchScope(gateway.value, request.botProfile ?: profiles.get(gateway))
        if (request.scope.gateway != gateway.value || request.offset < 0 || request.part < 0 || !current()) {
            return failed(CHANGED, account)
        }
        return try {
            withTimeoutOrNull(READ_MS) {
                val bot = request.botProfile
                val stored = if (bot != null) {
                    connection.start(gateway)
                    val ready = connection.state.first { it is ConnectionState.Connected || it is ConnectionState.SessionExpired || it is ConnectionState.Failed }
                    if (ready !is ConnectionState.Connected) return@withTimeoutOrNull failed(UNREACHABLE, account)
                    bots.botChat(bot)?.openId ?: return@withTimeoutOrNull failed(NO_BOT_CHAT, account)
                } else {
                    request.chatId?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull failed(CHOOSE, account)
                }
                if (request.messageId != null) return@withTimeoutOrNull part(request, gateway, stored, account) { current() }
                val result = sessions.messages(gateway, stored, limit = HISTORY_PAGE_ROWS, profile = request.scope.profile, offset = request.offset)
                if (!current()) return@withTimeoutOrNull failed(CHANGED, account)
                if (result !is ApiResult.Success) return@withTimeoutOrNull failed(result.errorMessage ?: COULDNT_READ, account)
                val page = result.value
                val messages = page.messages.mapIndexedNotNull { index, row -> shown(row, page.sessionId, request.offset + index) }
                    .map { (id, row) ->
                        val parts = historyParts(row.text)
                        HistoryMessage(id, row.role, parts.first(), row.timestamp, part = 0, parts = parts.size)
                    }
                HistoryPage(
                    request, account, page.sessionId, messages,
                    nextOffset = (request.offset + page.messages.size).takeIf { page.messages.size >= HISTORY_PAGE_ROWS },
                )
            } ?: failed(NO_ANSWER, account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message ?: COULDNT_READ, account)
        }
    }

    /** Part [HistoryRequest.part] of one message, looked for from its page's offset on: new rows push it older. */
    private suspend fun part(request: HistoryRequest, gateway: GatewayUrl, stored: String, account: String, current: suspend () -> Boolean): HistoryPage {
        var offset = request.offset
        repeat(PART_SEARCH_PAGES) {
            val result = sessions.messages(gateway, stored, limit = HISTORY_PAGE_ROWS, profile = request.scope.profile, offset = offset)
            if (!current()) return HistoryPage(request, account, error = CHANGED)
            if (result !is ApiResult.Success) return HistoryPage(request, account, error = result.errorMessage ?: COULDNT_READ)
            val page = result.value
            val found = page.messages.withIndex().firstNotNullOfOrNull { (index, row) ->
                shown(row, page.sessionId, offset + index)?.takeIf { it.first == request.messageId }
            }
            if (found != null) {
                val (id, row) = found
                val parts = historyParts(row.text)
                val text = parts.getOrNull(request.part) ?: return HistoryPage(request, account, error = MESSAGE_CHANGED)
                return HistoryPage(request, account, page.sessionId, listOf(HistoryMessage(id, row.role, text, row.timestamp, request.part, parts.size)))
            }
            if (page.messages.size < HISTORY_PAGE_ROWS) return HistoryPage(request, account, error = MESSAGE_GONE)
            offset += page.messages.size
        }
        return HistoryPage(request, account, error = MESSAGE_GONE)
    }

    /** A row the reader shows, with its id; tool and storage-only rows aren't messages. */
    private fun shown(row: SessionMessage, pageSession: String, at: Int): Pair<String, SessionMessage>? {
        if (row.isHidden || row.role !in SHOWN_ROLES || row.text.isBlank()) return null
        // A row without an id is known by its time, role and words: offsets shift as new turns arrive.
        val key = row.id?.toString() ?: row.timestamp?.let { "t$it:${row.role}:${row.text.hashCode()}" } ?: "o$at"
        return "${row.sessionId ?: pageSession}:$key" to row
    }

    /**
     * An opaque key of [gateway]'s verified account (`SessionBindings.owner`), the same for the same account and
     * gateway and telling neither; null when the account isn't confirmed.
     */
    suspend fun accountKey(gateway: GatewayUrl): String? {
        val owner = try { auth.sessionBindings.owner(gateway) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        val digest = sha256("kyllene-watch-history\u0000${gateway.value}\u0000$owner".encodeToByteArray())
        return "a-" + digest.take(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    companion object {
        private val SHOWN_ROLES = setOf("user", "assistant")
        private const val READ_MS = 25_000L
        private const val PART_SEARCH_PAGES = 10
        const val SIGNED_OUT = "Sign in to Kyllene on your phone first."
        const val UNCONFIRMED = "Kyllene on your phone is still confirming your account. Try again in a moment."
        const val CHANGED = "Conversation changed. Choose it again on your watch."
        const val UNREACHABLE = "Kyllene can't reach your gateway right now."
        const val NO_BOT_CHAT = "This bot has no saved conversation yet."
        const val CHOOSE = "Choose a saved conversation first."
        const val COULDNT_READ = "Couldn't read the conversation."
        const val NO_ANSWER = "The gateway didn't answer. Try again."
        const val MESSAGE_CHANGED = "This message changed. Read the conversation again."
        const val MESSAGE_GONE = "This message isn't in the conversation any more."
    }
}
