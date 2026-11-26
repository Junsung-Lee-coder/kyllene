package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.HistoryMessage
import dev.hermeskotlin.wearlink.SavedHistoryMessage
import dev.hermeskotlin.wearlink.SavedHistoryMeta
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WearJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Fork customization (requirements §9.1, §9.2): the conversation pages the phone sent the watch's reader, kept on the
 * watch so they read again offline. Nothing is trimmed, aged out or dropped for space; a write that fails leaves the
 * last copy whole and is said ([IOException]).
 *
 * Laid out under [root] (`files/history`, the reader's alone) as `v1/<account>/<conversation>/messages.jsonl` and
 * `meta.json`. `<account>` is the phone's opaque account key, checked to be one ([isAccountKey]); `<conversation>`
 * a SHA-256 of the gateway, profile and chat or bot. No id or name the gateway chose is ever a path. Content the
 * watch can't place under a confirmed account goes to `quarantine/`, kept, never shown.
 *
 * Lines that don't read are kept where they were, as they were, whenever the file is written again.
 */
class WatchHistoryStore(
    private val root: File,
    /** Writes a whole file; a test makes it fail. */
    private val writeFile: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) },
) {
    /** A conversation's saved copy: its messages (oldest first), how many lines didn't read, and its meta. */
    data class Saved(val messages: List<SavedHistoryMessage>, val unreadable: Int, val meta: SavedHistoryMeta)

    /** Which conversation: [chatId] or the bot [botProfile], in [scope]. */
    data class Conversation(val scope: WatchScope, val chatId: String?, val botProfile: String?)

    private sealed interface Line {
        data class Message(val message: SavedHistoryMessage) : Line
        data class Raw(val text: String) : Line
    }

    fun read(account: String, conversation: Conversation): Saved {
        val dir = dir(account, conversation) ?: return Saved(emptyList(), 0, SavedHistoryMeta())
        val lines = lines(dir)
        val meta = runCatching { recovered(File(dir, META)).takeIf { it.exists() }?.readText()?.let { WearJson.json.decodeFromString(SavedHistoryMeta.serializer(), it) } }.getOrNull()
        return Saved(lines.filterIsInstance<Line.Message>().map { it.message }, lines.count { it is Line.Raw }, meta ?: SavedHistoryMeta())
    }

    /**
     * Merges a page read at [offset] into [account]'s copy of [conversation]: messages already kept stay where they
     * are (a later, fuller copy of one replaces it), new ones go where the page puts them, and [nextOffset] says where
     * older ones are. Returns the copy as saved.
     */
    @Throws(IOException::class)
    fun savePage(account: String, conversation: Conversation, offset: Int, page: List<HistoryMessage>, nextOffset: Int?, title: String?, now: Long): Saved {
        val dir = dir(account, conversation) ?: throw IOException("Not a confirmed account.")
        val lines = lines(dir).toMutableList()
        val incoming = page.map { SavedHistoryMessage(it.id, it.role, it.text, it.at, it.parts, 1, offset) }
        val oldMeta = read(account, conversation).meta
        val older = offset > 0 && oldMeta.savedAt > 0
        val restart = merge(lines, incoming, older, anchor = oldMeta.anchor)
        val meta = SavedHistoryMeta(
            title = title ?: oldMeta.title, scope = conversation.scope, chatId = conversation.chatId, botProfile = conversation.botProfile,
            // An older page, or a newest one that didn't meet the saved copy (or the first), starts where to read
            // older ones from next; a newest page that met the copy doesn't move it: what it overlaps only dedupes.
            nextOffset = if (older || restart || oldMeta.savedAt == 0L) nextOffset else oldMeta.nextOffset,
            anchor = if (older || restart || oldMeta.savedAt == 0L) incoming.firstOrNull()?.id ?: oldMeta.anchor else oldMeta.anchor,
            savedAt = now,
        )
        write(dir, lines, meta)
        return read(account, conversation)
    }

    /** Adds part [part] to a kept message, in order; one out of order, or for a message not kept, is refused. */
    @Throws(IOException::class)
    fun savePart(account: String, conversation: Conversation, part: HistoryMessage): Saved {
        val dir = dir(account, conversation) ?: throw IOException("Not a confirmed account.")
        val lines = lines(dir).toMutableList()
        val at = lines.indexOfFirst { it is Line.Message && it.message.id == part.id }
        if (at < 0) throw IOException("That message isn't saved here.")
        val kept = (lines[at] as Line.Message).message
        if (part.part != kept.have || part.parts != kept.parts) throw IOException("That part doesn't follow what's saved.")
        lines[at] = Line.Message(kept.copy(text = kept.text + part.text, have = kept.have + 1))
        write(dir, lines, read(account, conversation).meta)
        return read(account, conversation)
    }

    /** Keeps [bytes] the watch received but can't place under a confirmed account: never shown, never thrown away. */
    @Throws(IOException::class)
    fun quarantine(bytes: ByteArray, now: Long) {
        val dir = File(root, QUARANTINE).apply { mkdirs() }
        val file = File(dir, "$now-${(bytes.contentHashCode().toLong() and 0xffffffffL).toString(16)}.json")
        atomically(file) { writeFile(it, bytes) }
    }

    /** How many conversations are saved (every account's) and how many bytes everything here takes. */
    fun summary(): Pair<Int, Long> {
        val accounts = File(root, VERSION).listFiles().orEmpty().filter { it.isDirectory }
        val chats = accounts.sumOf { a -> a.listFiles().orEmpty().count { it.isDirectory } }
        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return chats to bytes
    }

    /** Deletes everything the reader saved on this watch, every account's: nothing else of the app's. */
    fun deleteAll(): Boolean = !root.exists() || root.deleteRecursively()

    private fun dir(account: String, conversation: Conversation): File? {
        if (!isAccountKey(account)) return null
        val key = listOf(conversation.scope.gateway, conversation.scope.profile.orEmpty(), conversation.chatId?.let { "chat:$it" } ?: "bot:${conversation.botProfile}")
            .joinToString("\u0000")
        return File(File(File(root, VERSION), account), sha256(key))
    }

    private fun lines(dir: File): List<Line> {
        val file = recovered(File(dir, MESSAGES))
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotEmpty() }.map { text ->
            runCatching { Line.Message(WearJson.json.decodeFromString(SavedHistoryMessage.serializer(), text)) }.getOrElse { Line.Raw(text) }
        }
    }

    private fun write(dir: File, lines: List<Line>, meta: SavedHistoryMeta) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't make the folder for this conversation.")
        // A meta file that doesn't read is kept beside, not written over.
        val metaFile = File(dir, META)
        if (metaFile.exists() && runCatching { WearJson.json.decodeFromString(SavedHistoryMeta.serializer(), metaFile.readText()) }.isFailure) {
            if (!metaFile.renameTo(File(dir, "$META.unreadable-${System.currentTimeMillis()}"))) throw IOException("Couldn't keep the unreadable meta aside.")
        }
        atomically(File(dir, MESSAGES)) { file ->
            writeFile(file, lines.joinToString("\n", postfix = "\n") {
                when (it) {
                    is Line.Message -> WearJson.json.encodeToString(SavedHistoryMessage.serializer(), it.message)
                    is Line.Raw -> it.text
                }
            }.encodeToByteArray())
        }
        atomically(metaFile) { writeFile(it, WearJson.json.encodeToString(SavedHistoryMeta.serializer(), meta).encodeToByteArray()) }
    }

    /** [file], or its last whole copy when a replace stopped between its two renames. */
    private fun recovered(file: File): File {
        val old = File(file.parentFile, "${file.name}.old")
        if (!file.exists() && old.exists()) old.renameTo(file)
        return file
    }

    /** Writes [target] through a temporary file renamed over it: a failed write leaves the old one whole. */
    private fun atomically(target: File, write: (File) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            write(temp)
            if (!temp.renameTo(target)) {
                // Some file systems won't rename over an existing file: the old one goes only once the new is whole.
                val old = File(target.parentFile, "${target.name}.old")
                // A copy left by a replace that stopped half way: [target] is whole and newer.
                if (old.exists() && target.exists()) old.delete()
                if (target.exists() && !target.renameTo(old)) throw IOException("Couldn't replace ${target.name}.")
                if (!temp.renameTo(target)) {
                    old.renameTo(target)
                    throw IOException("Couldn't replace ${target.name}.")
                }
                old.delete()
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw IOException(e.message ?: "Couldn't write ${target.name}.", e)
        }
    }

    companion object {
        private const val VERSION = "v1"
        private const val QUARANTINE = "quarantine"
        private const val MESSAGES = "messages.jsonl"
        private const val META = "meta.json"
        private val ACCOUNT_KEY = Regex("a-[0-9a-f]{32}")

        /** The phone's opaque account key, and nothing else, can name a folder. */
        fun isAccountKey(value: String?): Boolean = value != null && ACCOUNT_KEY.matches(value)

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * [incoming] (a page, oldest first) into [lines]. One already kept stays where it is, updated to what the page
         * says (its offset; its text when it changed, never a shorter copy of the same). New ones go just before the
         * first kept one the page meets and after each kept one they follow. A page that meets nothing kept goes at
         * the end (newest) or just before [anchor] (older: it was read right before it, so they join); its first
         * message is marked when what's before it may not join on. Returns whether a newest page didn't meet the
         * saved copy, so reading back starts again from it.
         */
        private fun merge(lines: MutableList<Line>, incoming: List<SavedHistoryMessage>, older: Boolean, anchor: String?): Boolean {
            fun indexOf(id: String) = lines.indexOfFirst { it is Line.Message && it.message.id == id }
            fun mark(id: String, gap: Boolean) {
                val at = indexOf(id).takeIf { it >= 0 } ?: return
                lines[at] = Line.Message((lines[at] as Line.Message).message.copy(gapBefore = gap))
            }
            if (incoming.isEmpty()) return false
            val hadMessages = lines.any { it is Line.Message }
            val firstKept = incoming.firstOrNull { indexOf(it.id) >= 0 }
            val anchorAt = anchor?.let(::indexOf)?.takeIf { it >= 0 }
            var cursor = when {
                firstKept != null -> indexOf(firstKept.id)
                older -> anchorAt ?: 0
                else -> lines.size
            }
            incoming.forEachIndexed { index, message ->
                val at = indexOf(message.id)
                if (at >= 0) {
                    val kept = (lines[at] as Line.Message).message
                    val same = message.parts == kept.parts && if (kept.parts == 1) kept.text == message.text else kept.text.startsWith(message.text)
                    val updated = if (same) kept.copy(offset = message.offset, at = message.at ?: kept.at) else message.copy(gapBefore = kept.gapBefore)
                    // A message read with the one before it has no gap before it any more.
                    lines[at] = Line.Message(if (index > 0) updated.copy(gapBefore = false) else updated)
                    cursor = at + 1
                } else {
                    lines.add(cursor, Line.Message(message.copy(gapBefore = false)))
                    cursor++
                }
            }
            val first = incoming.first().id
            val firstAt = indexOf(first)
            val before = lines.subList(0, firstAt).any { it is Line.Message }
            if (older) {
                // Read just before the anchor: nothing is missing between them.
                if (anchor != null && anchor !in incoming.map { it.id }) mark(anchor, false)
                if (firstKept == null || firstKept.id != first) mark(first, before)
                return false
            }
            if (firstKept == null) {
                mark(first, hadMessages)
                return hadMessages
            }
            return false
        }
    }
}
