package dev.hermeskotlin.ui.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Fork: the headset buttons that start or end voice. Play, Pause, Play/Pause (and the hook) and Stop are all one. */
enum class VoiceKey { Play, Pause, PlayPause, Stop }

/** Fork: what one press of a [VoiceKey] does. */
enum class VoicePress {
    /** No voice on: talk, as the chat's Voice button does. */
    Start,
    /** The chat is listening and has heard you (Talk: is recording, heard or not): send it now, as Send now does. */
    Send,
    /** Any other voice on (a live call, a reply, the agent at work): end it, as End does. In Talk, End drops the request. */
    End,
    /** Talk is Ready: record the next request, as its Mic button does. */
    Talk,
    /** Not the chat's to take: the button goes where it would have gone. */
    None,
}

/** Fork: what a press does to [chat], where [canStart] says whether a chat on screen could start voice. */
internal fun voicePress(chat: VoiceChatState, canStart: Boolean): VoicePress = when {
    chat.phase == VoicePhase.Off -> if (canStart) VoicePress.Start else VoicePress.None
    chat.phase == VoicePhase.Ready -> VoicePress.Talk
    canSendNow(chat) -> VoicePress.Send
    else -> VoicePress.End
}

/**
 * Fork customization: the earphones' play/pause/stop button as one toggle for the chat's voice (requirements §4).
 *
 * - With no voice on, a press starts it, as the chat's Voice button does.
 * - A live call ends.
 * - A chained chat sends what it heard once it's listening and has heard you; otherwise it ends.
 * - Talk (Phone Live off): Ready records the next request; a recording is always ended and sent, heard or not (only
 *   End drops one); a request being written down, worked on or read is let go, and the chat is Ready again.
 *
 * A press is one press, however it's told: its down and up, repeats while held, or the same event from both the
 * activity and the media session. Each is known by its device and the time it went down, so the next press, which
 * went down later, is always its own. Read aloud keeps the button while it reads and no voice is on, and with no chat
 * on screen the button goes where it would have gone.
 *
 * The chat screen binds itself while it shows ([bind]). The platform's key and media-session events come in through
 * [press]. Main thread only.
 */
object VoiceButton {
    private class Target(val owner: Any, val voice: VoiceController, val start: () -> Unit, val wakeStart: ((Int) -> Unit)? = null)

    private val targets = MutableStateFlow<List<Target>>(emptyList())

    /** Presses acted on, by device and the time they went down; their ups and repeats are theirs too. */
    private val acted = ArrayDeque<Pair<Int, Long>>()

    /**
     * [voice] is the chat's on screen; [start] starts it as the chat's Voice button does. Fork: [wakeStart] starts it
     * for the wake word, its first recording ending on the given quiet spell; none: the wake word doesn't start it.
     */
    fun bind(owner: Any, voice: VoiceController, wakeStart: ((Int) -> Unit)? = null, start: () -> Unit) {
        targets.value = targets.value.filterNot { it.owner === owner } + Target(owner, voice, start, wakeStart)
    }

    fun unbind(owner: Any) {
        targets.value = targets.value.filterNot { it.owner === owner }
    }

    /** Owners whose chat screen is resumed: in front, taking input. */
    private val resumed = MutableStateFlow<List<Any>>(emptyList())

    /** Fork: [owner]'s chat screen was resumed ([on]) or paused. Holding the button starts nothing by itself. */
    fun resumed(owner: Any, on: Boolean) {
        resumed.value = resumed.value.filterNot { it === owner } + listOfNotNull(owner.takeIf { on })
    }

    private fun inFront(target: Target) = resumed.value.any { it === target.owner }

    /** Whether the bound chat has voice on: the media session holds the button meanwhile, the app out of sight too. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val voiceOn: Flow<Boolean> = targets.flatMapLatest { all ->
        all.lastOrNull()?.voice?.chat?.map { it.phase != VoicePhase.Off } ?: flowOf(false)
    }

    /**
     * Fork: whether the media session holds the button. While the bound chat's voice is on, in front or out of sight
     * (Talk only stays on out of sight at Ready with the background earphone button). And with voice off, while its
     * chat screen is resumed, so a press starts Talk rather than music (the activity's keys come first as well),
     * except while Read aloud reads, whose own session has the button then. Otherwise not: music keeps it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val holding: Flow<Boolean> = combine(targets, resumed) { all, _ -> all.lastOrNull() }.flatMapLatest { target ->
        if (target == null) {
            flowOf(false)
        } else {
            val front = inFront(target)
            combine(target.voice.chat, target.voice.reading) { chat, reading ->
                chat.phase != VoicePhase.Off || front && reading.key == null
            }
        }
    }

    /**
     * One [key] event: [down] or up, [repeat] while held, [downTime] when its press went down, from [device]. True
     * when it's the chat's (taken), false to let it go on where it would have gone.
     */
    fun press(key: VoiceKey, down: Boolean, repeat: Int, downTime: Long, device: Int): Boolean {
        val target = targets.value.lastOrNull() ?: return false
        val id = device to downTime
        // A press's up and its repeats follow its down: taken if that was.
        if (!down || repeat > 0 || id in acted) return id in acted
        val chat = target.voice.chat.value
        // Read aloud has the button while it reads and nothing else is on.
        if (chat.phase == VoicePhase.Off && target.voice.reading.value.key != null) return false
        // Fork: voice only starts from a chat in front; out of sight with voice off, the press goes where it would.
        val action = voicePress(chat, canStart = inFront(target))
        if (action == VoicePress.None) return false
        acted.addLast(id)
        while (acted.size > MAX_REMEMBERED) acted.removeFirst()
        when (action) {
            VoicePress.Start -> target.start()
            VoicePress.Send -> target.voice.sendNow()
            VoicePress.End -> target.voice.end()
            VoicePress.Talk -> target.voice.talk()
            VoicePress.None -> Unit
        }
        return true
    }

    /**
     * Fork (requirements §5): the wake word was heard with a chat in front. No voice on: start it, as the button does;
     * Talk at Ready: record the next request. That recording ends on [endMs] of quiet. False when there's no chat in
     * front to take it, or its voice is busy (the wake word doesn't listen then anyway).
     */
    fun wake(endMs: Int): Boolean {
        val target = targets.value.lastOrNull()?.takeIf(::inFront) ?: return false
        // Any voice with the microphone or the speaker, the composer's dictation too (the chat's phase stays Off then):
        // the wake word starts nothing over it.
        if (VoiceMedia.busyNow()) return false
        return when (target.voice.chat.value.phase) {
            VoicePhase.Off -> target.wakeStart?.let { it(endMs); true } ?: false
            VoicePhase.Ready -> target.voice.wakeTalk(endMs)
            else -> false
        }
    }

    /** Fork: whether a chat is in front to take a wake word. */
    val canWake: Boolean get() = targets.value.lastOrNull()?.let(::inFront) == true

    /** For tests: forgets every binding and press. */
    internal fun reset() {
        targets.value = emptyList()
        resumed.value = emptyList()
        acted.clear()
    }

    private const val MAX_REMEMBERED = 32
}
