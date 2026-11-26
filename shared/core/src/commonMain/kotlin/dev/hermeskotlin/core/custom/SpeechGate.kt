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
 * - A voice request (the phone's voice chat or Talk sending what was said, a watch's spoken ask) takes a [Ticket]
 *   when it's sent. Its reply may be read only while no text was sent after it ([allows]).
 * - Text sent from the phone's chat ([textInput]) stops what's being read automatically in that scope, at once
 *   ([onText]), and silences every reply to a voice request made before it, for good.
 * - A later voice request gets a newer ticket: only replies to it, and to later ones, are read. Nothing silenced is
 *   read afterwards. A reply finishing, failing or being cancelled changes nothing.
 *
 * Only real user input counts: opening or reading a chat, reconnecting, settings and the gateway's own events don't
 * call it. Read aloud, pressed by hand, isn't automatic and doesn't ask. A scope is a gateway and the account its
 * stored session is bound to (`SessionBindings.owner`), so one account's text never silences another's. Inputs are
 * ordered as the phone takes them, from the phone and the watch alike. Kept in memory: a reply that would be read
 * after the app restarts can't exist, since its turn went with the app.
 */
@OptIn(ExperimentalAtomicApi::class)
class SpeechGate(
    /** The gateway's verified account (`SessionBindings.owner`); null when not known, which is its own scope. */
    private val owner: suspend (GatewayUrl) -> String?,
) {
    /** A voice request's place among the inputs. */
    class Ticket internal constructor(val scope: String, internal val order: Long)

    private class State(val inputs: Long = 0, val lastText: Map<String, Long> = emptyMap())

    private val state = AtomicReference(State())
    private val observers = AtomicReference<List<(String) -> Unit>>(emptyList())

    /** The scope of [gateway]: the gateway and its current account. */
    suspend fun scope(gateway: GatewayUrl): String {
        val account = try { owner(gateway) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return gateway.value + "\u0000" + account.orEmpty()
    }

    /** A voice request in [gateway] is being sent: its replies are read unless text follows. */
    suspend fun voiceInput(gateway: GatewayUrl): Ticket {
        val scope = scope(gateway)
        return Ticket(scope, state.updateAndFetch { State(it.inputs + 1, it.lastText) }.inputs)
    }

    /** Text was sent from the phone in [gateway]: automatic speech there stops, and earlier voice replies stay silent. */
    suspend fun textInput(gateway: GatewayUrl) {
        val scope = scope(gateway)
        state.updateAndFetch { State(it.inputs + 1, it.lastText + (scope to it.inputs + 1)) }
        for (stop in observers.load()) runCatching { stop(scope) }
    }

    /** Whether the reply to [ticket]'s request may be read aloud now. */
    fun allows(ticket: Ticket?): Boolean = ticket != null && ticket.order > (state.load().lastText[ticket.scope] ?: 0L)

    /** [stop] is told the scope of each text input, at once; the returned action stops telling it. */
    fun onText(stop: (String) -> Unit): () -> Unit {
        observers.updateAndFetch { it + stop }
        return { observers.updateAndFetch { list -> list.filterNot { it === stop } } }
    }
}
