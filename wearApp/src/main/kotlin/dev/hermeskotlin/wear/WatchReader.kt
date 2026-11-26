package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.HistoryPage
import dev.hermeskotlin.wearlink.HistoryRequest
import dev.hermeskotlin.wearlink.SavedHistoryMessage
import dev.hermeskotlin.wearlink.WearJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** The verified account the watch last heard from its phone, kept across launches. */
interface ReaderAccount {
    var account: String?
}

/** Where the reader stands, for its screen to say. */
enum class ReaderStatus {
    /** Nothing asked yet. */
    Idle,
    Reading,
    ReadingOlder,
    UpToDate,

    /** The phone didn't take the request or didn't answer: what's saved here is shown. */
    Offline,

    /** The phone answered with an error ([ReaderState.error]); what's saved stays. */
    Failed,

    /** The phone isn't signed in, or hasn't confirmed its account: nothing saved is shown. */
    Locked,

    /** The phone's Kyllene can't read conversations for the watch. */
    UpdatePhone,
}

/**
 * The reader's screen: the open conversation's saved [messages] (oldest first) for the verified account, how it
 * stands, and whether older ones can be read ([olderOffset]). [storageError]: the watch couldn't save or read its copy.
 */
data class ReaderState(
    val conversation: WatchHistoryStore.Conversation? = null,
    val title: String? = null,
    val messages: List<SavedHistoryMessage> = emptyList(),
    val unreadable: Int = 0,
    val olderOffset: Int? = null,
    /** Nothing older is saved here, and the phone wasn't asked yet (or couldn't be). */
    val olderSaved: Boolean = false,
    val status: ReaderStatus = ReaderStatus.Idle,
    val error: String? = null,
    val storageError: String? = null,
    /** The message whose rest is being fetched, and the error fetching it. */
    val restFor: String? = null,
    val restError: String? = null,
    val locked: Boolean = false,
    val signedIn: Boolean = true,
)

/**
 * Fork customization (requirements §9.1, §9.2): the watch's conversation reader. It asks the phone for pages only when
 * the reader is open and asks, saves what comes in ([WatchHistoryStore]) under the account it came for, and shows only
 * the verified account's copy.
 *
 * Identity: the phone's status names the verified account ([onStatus]). A new account replaces the old one at once;
 * an account the phone hasn't confirmed, or signed out, shows nothing, and the last verified one is used again only
 * once the phone names it. Offline, the last verified account the phone named is used. A page counts only for the
 * request it answers, made for the same account and conversation, and since no account switch or delete; a page
 * for another account is kept in quarantine, never shown.
 */
class WatchReader(
    private val store: WatchHistoryStore,
    private val memory: ReaderAccount,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Sends a request to the phone; null when it went, else what to say. */
    private val send: suspend (HistoryRequest) -> String?,
    private val newId: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val answerWithinMs: Long = ANSWER_WITHIN_MS,
) {
    private data class Pending(val request: HistoryRequest, val account: String, val conversation: WatchHistoryStore.Conversation, val older: Boolean, val generation: Long)

    private val lock = Mutex()
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _saved = MutableStateFlow<Pair<Int, Long>?>(null)

    /** How many conversations the watch saved, and their bytes; null until counted. */
    val saved: StateFlow<Pair<Int, Long>?> = _saved.asStateFlow()

    private val pending = mutableMapOf<String, Pending>()

    /** Requests a switch of account cancelled: what they still bring is kept in quarantine. */
    private val switched = mutableSetOf<String>()

    /** Bumped by an account switch and by a delete: requests made before don't count. */
    private var generation = 0L
    private var phoneReads = true
    private var timeouts = mutableMapOf<String, Job>()

    /** The verified account the reader shows, or null. */
    val account: String? get() = memory.account?.takeIf(WatchHistoryStore::isAccountKey)

    /**
     * What the phone's status said: [history] whether it reads for the watch, and its verified [account] (null: signed
     * out or not confirmed). Another account than the last cancels what was asked for the last one.
     */
    suspend fun onStatus(signedIn: Boolean, history: Boolean, account: String?) = lock.withLock {
        phoneReads = history
        val next = account?.takeIf { signedIn && WatchHistoryStore.isAccountKey(it) }
        if (next != memory.account) {
            memory.account = next
            generation++
            switched += pending.keys
            pending.clear()
            timeouts.values.forEach { it.cancel() }
            timeouts.clear()
        }
        _state.update { it.copy(signedIn = signedIn) }
        reload()
    }

    /** Opens [conversation] and asks for its newest page. */
    suspend fun open(conversation: WatchHistoryStore.Conversation, title: String?) {
        lock.withLock {
            if (_state.value.conversation != conversation) _state.value = ReaderState(conversation, title, signedIn = _state.value.signedIn)
            else _state.update { it.copy(title = title ?: it.title) }
            reload()
        }
        request(older = false)
    }

    fun close() {
        _state.update { ReaderState(signedIn = it.signedIn) }
    }

    /** Asks for the page before the oldest one saved. */
    suspend fun older() = request(older = true)

    /** Asks for the newest page again. */
    suspend fun refresh() = request(older = false)

    /** Asks for the rest of [messageId], a part at a time, until it's whole. */
    suspend fun rest(messageId: String) {
        val (request, message) = lock.withLock {
            val state = _state.value
            val conversation = state.conversation ?: return
            val account = account ?: return
            val message = state.messages.firstOrNull { it.id == messageId }?.takeIf { !it.complete } ?: return
            if (!phoneReads) return@withLock null to message
            val request = HistoryRequest(newId(), conversation.scope, conversation.chatId, conversation.botProfile, message.offset, message.id, message.have)
            pending[request.requestId] = Pending(request, account, conversation, older = false, generation)
            _state.update { it.copy(restFor = messageId, restError = null) }
            request to message
        }
        if (request == null) return _state.update { it.copy(restFor = message.id, restError = null, status = ReaderStatus.UpdatePhone) }
        dispatch(request)
    }

    private suspend fun request(older: Boolean) {
        val request = lock.withLock {
            val state = _state.value
            val conversation = state.conversation ?: return
            val account = account ?: return _state.update { it.copy(status = ReaderStatus.Locked) }
            if (!phoneReads) return _state.update { it.copy(status = ReaderStatus.UpdatePhone) }
            val offset = if (older) state.olderOffset ?: return else 0
            if (older && state.status == ReaderStatus.ReadingOlder) return
            val request = HistoryRequest(newId(), conversation.scope, conversation.chatId, conversation.botProfile, offset)
            pending[request.requestId] = Pending(request, account, conversation, older, generation)
            _state.update { it.copy(status = if (older) ReaderStatus.ReadingOlder else ReaderStatus.Reading, error = null) }
            request
        }
        dispatch(request)
    }

    private suspend fun dispatch(request: HistoryRequest) {
        val failed = send(request)
        lock.withLock {
            val waiting = pending[request.requestId] ?: return
            if (failed != null) {
                pending.remove(request.requestId)
                showFailure(waiting, failed, offline = true)
                return
            }
            timeouts[request.requestId] = scope.launch {
                delay(answerWithinMs)
                lock.withLock {
                    timeouts.remove(request.requestId)
                    // Still taken if it comes late, while it's for the same account and conversation.
                    val late = pending[request.requestId] ?: return@withLock
                    showFailure(late, null, offline = true)
                }
            }
        }
    }

    private fun showFailure(waiting: Pending, message: String?, offline: Boolean) {
        if (_state.value.conversation != waiting.conversation) return
        _state.update {
            if (waiting.request.messageId != null) it.copy(restError = message ?: "", status = if (offline) ReaderStatus.Offline else it.status)
            else it.copy(status = if (offline) ReaderStatus.Offline else ReaderStatus.Failed, error = message)
        }
    }

    /** A page from the phone, as it came ([bytes]). */
    suspend fun accept(bytes: ByteArray) {
        val page = WearJson.decode<HistoryPage>(bytes) ?: return
        lock.withLock {
            val id = page.request.requestId
            val waiting = pending[id]
            if (waiting == null) {
                // Asked for an account since switched from: kept, never shown. Anything else (deleted since, or not
                // asked) is dropped.
                if (switched.remove(id) && page.messages.isNotEmpty()) quarantine(bytes)
                return
            }
            pending.remove(id)
            timeouts.remove(id)?.cancel()
            val sameScope = page.request == waiting.request && waiting.generation == generation && waiting.account == account
            if (!sameScope || page.account != waiting.account) {
                // Content for an account the watch can't confirm here: kept aside, never shown.
                if (page.messages.isNotEmpty()) quarantine(bytes)
                if (page.account == null && page.error != null && _state.value.conversation == waiting.conversation) {
                    _state.update { it.copy(status = ReaderStatus.Failed, error = page.error, restFor = null) }
                }
                return
            }
            if (page.error != null) return showFailure(waiting, page.error, offline = false)
            try {
                withContext(io) {
                    val request = waiting.request
                    if (request.messageId != null) {
                        val part = page.messages.singleOrNull { it.id == request.messageId && it.part == request.part }
                            ?: throw IOException(PART_MISSING)
                        store.savePart(waiting.account, waiting.conversation, part)
                    } else {
                        store.savePage(waiting.account, waiting.conversation, request.offset, page.messages, page.nextOffset, _state.value.title.takeIf { _state.value.conversation == waiting.conversation }, now())
                    }
                }
                _state.update { it.copy(storageError = null) }
            } catch (e: IOException) {
                if (_state.value.conversation == waiting.conversation) _state.update { it.copy(storageError = e.message ?: STORAGE_FAILED, status = ReaderStatus.Failed, restFor = null) }
                reload()
                return
            }
            countSaved()
            if (_state.value.conversation != waiting.conversation) return
            reload()
            if (waiting.request.messageId == null) _state.update { it.copy(status = ReaderStatus.UpToDate, error = null) }
        }
        // The rest of a long message, part by part, while it's the one asked for.
        val state = _state.value
        val restFor = state.restFor ?: return
        val message = state.messages.firstOrNull { it.id == restFor } ?: return
        if (page.request.messageId == restFor && !message.complete) rest(restFor)
        else if (message.complete) _state.update { it.copy(restFor = null, restError = null) }
    }

    /**
     * Deletes everything the reader saved on this watch. What's asked of the phone until now is dropped when it
     * answers, so nothing comes back after the delete.
     */
    suspend fun deleteAll(): Boolean = lock.withLock {
        generation++
        pending.clear()
        switched.clear()
        timeouts.values.forEach { it.cancel() }
        timeouts.clear()
        val deleted = withContext(io) { store.deleteAll() }
        _state.update {
            it.copy(messages = emptyList(), unreadable = 0, olderOffset = null, olderSaved = false, status = ReaderStatus.Idle, restFor = null,
                storageError = if (deleted) null else DELETE_FAILED)
        }
        countSaved()
        deleted
    }

    /** Counts what's saved, for settings. */
    suspend fun countSaved() {
        _saved.value = runCatching { withContext(io) { store.summary() } }.getOrNull()
    }

    private suspend fun reload() {
        val state = _state.value
        val conversation = state.conversation
        val account = account
        if (conversation == null) return
        if (account == null) {
            _state.update { it.copy(messages = emptyList(), unreadable = 0, olderOffset = null, locked = true, status = ReaderStatus.Locked) }
            return
        }
        try {
            val saved = withContext(io) { store.read(account, conversation) }
            _state.update {
                it.copy(
                    messages = saved.messages, unreadable = saved.unreadable, locked = false,
                    olderOffset = saved.meta.nextOffset.takeIf { saved.meta.savedAt > 0 }, olderSaved = saved.meta.savedAt > 0,
                    title = it.title ?: saved.meta.title,
                    status = if (it.status == ReaderStatus.Locked) ReaderStatus.Idle else it.status,
                )
            }
        } catch (e: IOException) {
            _state.update { it.copy(storageError = e.message ?: STORAGE_FAILED, locked = false) }
        }
    }

    private suspend fun quarantine(bytes: ByteArray) {
        try {
            withContext(io) { store.quarantine(bytes, now()) }
        } catch (e: IOException) {
            _state.update { it.copy(storageError = e.message ?: STORAGE_FAILED) }
        }
    }

    companion object {
        const val ANSWER_WITHIN_MS = 40_000L
        const val STORAGE_FAILED = "Couldn't save on this watch."
        const val DELETE_FAILED = "Couldn't delete everything saved on this watch."
        const val PART_MISSING = "The phone sent another part."
    }
}

/**
 * [text] in pieces of about [size] characters for the message screen to lay out one at a time, split at a paragraph
 * or line break, else a space, when there's one near; joined again they're [text], every character of it.
 */
internal fun readerChunks(text: String, size: Int = 600): List<String> {
    if (text.length <= size) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + size, text.length)
        if (end < text.length) {
            val window = text.substring(start, end)
            val cut = listOf("\n\n", "\n", " ").firstNotNullOfOrNull { mark -> window.lastIndexOf(mark).takeIf { it > size / 2 }?.let { it + mark.length } }
            end = if (cut != null) start + cut else if (text[end - 1].isHighSurrogate()) end - 1 else end
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
