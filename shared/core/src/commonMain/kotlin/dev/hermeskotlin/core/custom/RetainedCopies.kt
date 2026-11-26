package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.random.Random

/**
 * Fork customization: what the device has received stays on it. A newer read of a chat or of the chat list adds to
 * the saved copy instead of replacing it, so nothing goes because of a count, an age, its size, or the gateway no
 * longer having it. Only the person deleting a chat, or a bot with its chats, drops a copy.
 *
 * Kept apart from the stock cache so the fork's change stays a few calls in [dev.hermeskotlin.core.cache.OfflineCache].
 */
object RetainedCopies {
    /**
     * [saved] with [received] laid over it: a row the gateway sent again replaces the saved one with the same id; rows
     * it no longer sends stay. Rows are in id order; a row without an id keeps its place after the row before it.
     */
    fun mergeRows(saved: List<SessionMessage>, received: List<SessionMessage>): List<SessionMessage> {
        if (saved.isEmpty()) return received
        val receivedIds = received.mapNotNullTo(HashSet()) { it.id }
        val receivedRows = received.toHashSet()
        val kept = saved.filter { row -> row.id?.let { it !in receivedIds } ?: (row !in receivedRows) }
        return (ordered(received, 0) + ordered(kept, 1)).sortedWith(
            compareBy<Pair<Key, SessionMessage>>({ it.first.anchor }, { it.first.after }, { it.first.source }, { it.first.index }),
        ).map { it.second }
    }

    private data class Key(val anchor: Long, val after: Int, val source: Int, val index: Int)

    private fun ordered(rows: List<SessionMessage>, source: Int): List<Pair<Key, SessionMessage>> {
        var anchor = Long.MIN_VALUE
        var after = 0
        return rows.mapIndexed { index, row ->
            val id = row.id
            if (id != null) {
                anchor = id
                after = 0
            } else {
                after++
            }
            Key(anchor, after, source, index) to row
        }
    }

    /** [received] in its order, then the saved chats it no longer has, in theirs. */
    fun mergeList(saved: List<SessionSummary>, received: List<SessionSummary>): List<SessionSummary> {
        val receivedIds = received.mapTo(HashSet()) { it.id }
        return (received + saved.filter { it.id !in receivedIds }).distinctBy { it.id }
    }

    /**
     * Where a signed-out or removed gateway's copies go: no gateway address can be this key, so no account signed in
     * later, to the same address or another, reads them as its own. They stay on the device. [nonce] keeps two
     * sign-outs in the same millisecond apart, so the second never meets the first's rows.
     */
    fun detachedKey(gateway: String, at: Long, nonce: Long = Random.nextLong()): String = "$DETACHED$at:${nonce.toULong()}:$gateway"

    /** Whether [key] holds copies put away by [detachedKey] (from [gateway], when given). */
    fun isDetached(key: String, gateway: String? = null): Boolean =
        key.startsWith(DETACHED) && (gateway == null || key.endsWith(":$gateway"))

    private const val DETACHED = "detached:"

    /**
     * A row's text split into pieces short enough for one database row each ([maxChars] UTF-16 units), for a row
     * too big to keep whole. A piece never splits a surrogate pair. One piece is the text itself.
     */
    fun split(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            pieces += text.substring(start, end)
            start = end
        }
        return pieces.mapIndexed { index, piece -> "$PART${index + 1}/${pieces.size}\n$piece" }
    }

    /** [stored] texts in order, with split rows put back together; null when a piece is missing or out of order. */
    fun join(stored: List<String>): List<String>? {
        val rows = mutableListOf<String>()
        var building: StringBuilder? = null
        var expected = 0
        var total = 0
        for (text in stored) {
            if (!text.startsWith(PART)) {
                if (building != null) return null
                rows += text
                continue
            }
            val header = text.substring(PART.length, text.indexOf('\n').takeIf { it > 0 } ?: return null)
            val (part, of) = header.split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
            if (part == 1) {
                if (building != null) return null
                building = StringBuilder()
                total = of
                expected = 1
            }
            if (building == null || part != expected || of != total) return null
            building.append(text, PART.length + header.length + 1, text.length)
            expected++
            if (part == total) {
                rows += building.toString()
                building = null
            }
        }
        return if (building == null) rows else null
    }

    /** Marks a piece of a split row; a whole row is JSON and never starts with it. */
    private const val PART = "\u0001part:"
}
