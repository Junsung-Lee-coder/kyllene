package dev.hermeskotlin.wearlink

import kotlinx.serialization.Serializable

/*
 * Fork customization (requirements §9.1): the watch reads a conversation's stored messages a page at a time, through
 * the phone, and keeps what it received. The request and page shapes, the raw-row offset paging and the bot's
 * canonical stored chat follow upstream Kyllene PR #370 ("Watch: read saved and bot conversations", StavenCross,
 * MIT, https://github.com/Giton22/kyllene/pull/370); the account key, whole text in parts, and the watch's saved copy
 * are this fork's.
 */

/** Stored rows the phone reads per page (raw rows: hidden and tool rows count toward the offset). */
const val HISTORY_PAGE_ROWS = 20

/** A message's text goes in parts of at most this many characters; the first comes with its page. */
const val HISTORY_PART_CHARS = 100_000

/** A page encoded above this many bytes goes over a channel rather than a message. */
const val HISTORY_MESSAGE_BYTES = 80_000

/**
 * What the watch asks: a page of [chatId] (or the bot [botProfile]'s canonical chat) in [scope], [offset] raw rows
 * back from the newest; or, with [messageId], part [part] of that message, found from [offset] on.
 */
@Serializable
data class HistoryRequest(
    val requestId: String,
    val scope: WatchScope,
    val chatId: String? = null,
    val botProfile: String? = null,
    val offset: Int = 0,
    val messageId: String? = null,
    val part: Int = 0,
)

/**
 * One stored message, or one part of it: [text] is part [part] of [parts], whole when [parts] is 1. [id] is the
 * stored session and row id, so the same message read again, at another offset, is known.
 */
@Serializable
data class HistoryMessage(
    val id: String,
    val role: String,
    val text: String,
    val at: Double? = null,
    val part: Int = 0,
    val parts: Int = 1,
)

/**
 * The phone's answer. [account] is an opaque key of the gateway's verified account (never the account itself); null
 * when the phone couldn't confirm it, when nothing is sent. [nextOffset]: where the next older page starts; null at the
 * oldest. [error] in place of messages.
 */
@Serializable
data class HistoryPage(
    val request: HistoryRequest,
    val account: String? = null,
    val sessionId: String? = null,
    val messages: List<HistoryMessage> = emptyList(),
    val nextOffset: Int? = null,
    val error: String? = null,
)

/** A message as the watch keeps it: the parts received so far ([have] of [HistoryMessage.parts]), joined. */
@Serializable
data class SavedHistoryMessage(
    val id: String,
    val role: String,
    val text: String,
    val at: Double? = null,
    val parts: Int = 1,
    val have: Int = 1,
    /** The page offset it was read at, where the rest of it is looked for. */
    val offset: Int = 0,
    /** Messages before this one may be missing: pages read around it didn't meet. */
    val gapBefore: Boolean = false,
) {
    val complete: Boolean get() = have >= parts
}

/** What the watch keeps about one saved conversation besides its messages. */
@Serializable
data class SavedHistoryMeta(
    val title: String? = null,
    val scope: WatchScope? = null,
    val chatId: String? = null,
    val botProfile: String? = null,
    /**
     * Where the next older page starts; null once the oldest was read (or before anything was: see [savedAt]). Null
     * by default, since `WearJson` leaves nulls out.
     */
    val nextOffset: Int? = null,
    /** The oldest message of the pages read back from the newest so far: the next older page ends just before it. */
    val anchor: String? = null,
    val savedAt: Long = 0,
)

/** [text] in parts of at most [max] characters, never splitting a surrogate pair. */
fun historyParts(text: String, max: Int = HISTORY_PART_CHARS): List<String> {
    if (text.length <= max) return listOf(text)
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            add(text.substring(start, end))
            start = end
        }
    }
}
