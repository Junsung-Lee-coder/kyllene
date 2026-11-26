package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fork customization (requirements §6.1): when the latest actual user input in an account's gateway is text, nothing
 * is read aloud automatically: not a reply still on its way to an earlier voice request, and not one being read now.
 *
 * - A voice request (the phone's voice chat or Talk sending what was said, a spoken answer, a watch's ask) takes a
 *   [Ticket] when it's sent. Its reply may be read only while no text reached it since ([allows], [allowsNow]).
 * - Text the person sends ([textInput]) stops what's being read automatically where it reaches, at once ([onText]):
 *   a reply, or a live call, which hangs up. It silences every reply to a voice request made before it, for good.
 * - A later voice request gets a newer ticket: only replies to it, and to later ones, are read. Nothing silenced is
 *   read afterwards. A reply finishing, failing or being cancelled changes nothing.
 *
 * Only real user input counts: opening or reading a chat, reconnecting, settings and the gateway's own events don't
 * call it. Read aloud, pressed by hand, isn't automatic and doesn't ask.
 *
 * A [Scope] is a gateway and the account its stored session is bound to (`SessionBindings.owner`). Two known accounts
 * never silence each other. Where an account isn't known yet (a binding still pending) or text's gateway isn't known,
 * it fails toward silence: such text reaches every account of the gateway (or every gateway), and such a request is
 * reached by any text in its gateway. A reply is never read once its gateway's account isn't the one that asked
 * ([allowsNow]). Inputs are ordered as the phone takes them, from the phone and the watch alike. Kept in memory: a
 * reply that would be read after the app restarts can't exist, since its turn went with the app.
 */
@OptIn(ExperimentalAtomicApi::class)
class SpeechGate(
    /** The gateway's verified account (`SessionBindings.owner`); null when not known. */
    private val owner: suspend (GatewayUrl) -> String?,
) {
    /** A gateway and its account; [owner] null when the account isn't known. */
    data class Scope(val gateway: String, val owner: String?)

    /** A voice request's place among the inputs. */
    class Ticket internal constructor(val scope: Scope, internal val order: Long, internal val gateway: GatewayUrl)

    /** [lastText]: by the text's scope (null: its gateway wasn't known), the order of its latest text input. */
    private class State(val inputs: Long = 0, val lastText: Map<Scope?, Long> = emptyMap())

    private val state = AtomicReference(State())
    private val observers = AtomicReference<List<(Scope?) -> Unit>>(emptyList())

    /** The scope of [gateway]: the gateway and its current account. */
    suspend fun scope(gateway: GatewayUrl): Scope = Scope(gateway.value, ownerOf(gateway))

    private suspend fun ownerOf(gateway: GatewayUrl): String? =
        try { owner(gateway) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** Whether text in [text] (null: its gateway wasn't known) reaches what was asked or is read in [scope]. */
    fun reaches(text: Scope?, scope: Scope): Boolean = when {
        text == null -> true
        text.gateway != scope.gateway -> false
        text.owner == null || scope.owner == null -> true
        else -> text.owner == scope.owner
    }

    /** A voice request in [gateway] is being sent: its replies are read unless text follows. */
    suspend fun voiceInput(gateway: GatewayUrl): Ticket {
        val scope = scope(gateway)
        return Ticket(scope, state.updateAndFetch { State(it.inputs + 1, it.lastText) }.inputs, gateway)
    }

    /**
     * Text was sent in [gateway] (null: where isn't known, so it reaches everything): automatic speech it reaches stops,
     * and earlier voice replies stay silent.
     */
    suspend fun textInput(gateway: GatewayUrl?) {
        val scope = gateway?.let { scope(it) }
        state.updateAndFetch { State(it.inputs + 1, it.lastText + (scope to it.inputs + 1)) }
        for (stop in observers.load()) runCatching { stop(scope) }
    }

    /** Whether no text reached [ticket]'s request since it was made. */
    fun allows(ticket: Ticket?): Boolean =
        ticket != null && state.load().lastText.none { (text, order) -> order > ticket.order && reaches(text, ticket.scope) }

    /** [allows], and its gateway's account is still the one that asked: never one account's reply to another. */
    suspend fun allowsNow(ticket: Ticket?): Boolean {
        if (!allows(ticket)) return false
        return ownerOf(ticket!!.gateway) == ticket.scope.owner
    }

    /** [stop] is told the scope of each text input (null: everywhere), at once; the returned action stops telling it. */
    fun onText(stop: (Scope?) -> Unit): () -> Unit {
        observers.updateAndFetch { it + stop }
        return { observers.updateAndFetch { list -> list.filterNot { it === stop } } }
    }
}

/**
 * Fork: typed text sent from outside a chat screen (a notification's inline reply, the assistant panel) is text input
 * in [gateway] (null when it isn't known: everywhere), counted before it goes, then [send].
 */
suspend fun <T> SpeechGate?.typed(gateway: GatewayUrl?, send: suspend () -> T): T {
    this?.textInput(gateway)
    return send()
}
