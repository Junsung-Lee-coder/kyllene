package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.http.Url
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Fork customization: which account a gateway's stored session was confirmed to be, so the device's saved chats can be
 * given to it before anything reads them, offline and after the app was killed mid-sign-in.
 *
 * - A pending mark is written before a new sign-in stores its credentials, for every gateway those credentials reach.
 *   Until [bound] follows, the session is treated as no known account's: the saved chats are put away, never shown.
 * - [bound] is written only once the saved chats were given to the owner (see OfflineCache.bindOwner).
 * - Renewing a session's tokens doesn't touch it: it's the same account. Signing out [clear]s it.
 *
 * Every gateway's state is one entry of one stored map, read from the store every time, so a mark over several
 * gateways is one write and what a killed process wrote last is what counts.
 */
class SessionBindings(private val store: KeyValueStore) {
    /** The account [gateway]'s stored session was confirmed to be; null when it's pending, or never was. */
    suspend fun owner(gateway: GatewayUrl): String? = read()[gateway.value]?.takeIf { it.startsWith(BOUND) }?.removePrefix(BOUND)

    /** New credentials for [gateway] alone (a browser sign-in's tokens, kept per gateway) are about to be stored. */
    suspend fun pending(gateway: GatewayUrl) = write { it + (gateway.value to PENDING) }

    /**
     * New session cookies from [gateway] are about to be stored. A gateway's cookies are its host's, whatever the
     * port, path or scheme, and a `Domain` cookie reaches the hosts under that domain, so every gateway on the same
     * host or under the same domain ([sameCookieDomain]) goes to pending with it.
     */
    suspend fun pendingForCookies(gateway: GatewayUrl) = write { all ->
        val host = hostOf(gateway.value)
        all.mapValues { (url, state) -> if (host != null && hostOf(url)?.let { sameCookieDomain(it, host) } == true) PENDING else state } +
            (gateway.value to PENDING)
    }

    /** Cookies scoped to [domain] were stored: every gateway on it, or on a host under it, goes to pending. */
    suspend fun pendingWithin(domain: String) = write { all ->
        val scope = domain.trimStart('.').lowercase()
        all.mapValues { (url, state) -> if (hostOf(url)?.let { it == scope || it.endsWith(".$scope") } == true) PENDING else state }
    }

    /** [gateway]'s stored session is [owner]'s, and its saved chats were given to it. */
    suspend fun bound(gateway: GatewayUrl, owner: String) = write { it + (gateway.value to BOUND + owner) }

    suspend fun clear(gateway: GatewayUrl) = write { it - gateway.value }

    // A map that doesn't read counts as empty: every gateway is then no known account's, and its chats are put away.
    private suspend fun read(): Map<String, String> =
        store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap()

    private suspend fun write(change: (Map<String, String>) -> Map<String, String>) {
        val next = change(read())
        if (next.isEmpty()) store.remove(KEY) else store.put(KEY, HermesJson.encodeToString(SERIALIZER, next))
    }

    companion object {
        private const val KEY = "fork.session-owner.v2"
        private const val PENDING = "pending"
        private const val BOUND = "bound:"
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        private fun hostOf(url: String): String? = runCatching { Url(url).host.lowercase().trimEnd('.') }.getOrNull()?.takeIf { it.isNotEmpty() }

        /**
         * Whether cookies set by [a] could reach [b]: the same host, or, for names, the same last two labels (a
         * `Domain` cookie is scoped to the host or a domain above it). More than the gateway ever sends: it sets no
         * `Domain`. Addresses only ever match themselves.
         */
        internal fun sameCookieDomain(a: String, b: String): Boolean {
            if (a == b) return true
            if (isAddress(a) || isAddress(b)) return false
            val suffix = { host: String -> host.split('.').takeLast(2).joinToString(".") }
            return a.count { it == '.' } >= 1 && b.count { it == '.' } >= 1 && suffix(a) == suffix(b)
        }

        private fun isAddress(host: String) = ':' in host || host.trim('[', ']').split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() != null } }
    }
}
