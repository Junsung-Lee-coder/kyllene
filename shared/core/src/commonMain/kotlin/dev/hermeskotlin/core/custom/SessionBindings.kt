package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.KeyValueStore

/**
 * Fork customization: which account a gateway's stored session was confirmed to be, so the device's saved chats can be
 * given to it before anything reads them, offline and after the app was killed mid-sign-in.
 *
 * - [pending] is written before a new sign-in stores its credentials. Until [bound] follows, the session is treated
 *   as no known account's: the saved chats are put away, never shown to it.
 * - [bound] is written only once the saved chats were given to [owner] (see OfflineCache.bindOwner).
 * - Renewing a session's tokens doesn't touch it: it's the same account. Signing out [clear]s it.
 *
 * Read from the store every time, so what a killed process wrote last is what counts.
 */
class SessionBindings(private val store: KeyValueStore) {
    /** The account [gateway]'s stored session was confirmed to be; null when it's pending, or never was. */
    suspend fun owner(gateway: GatewayUrl): String? = store.get(key(gateway))?.takeIf { it.startsWith(BOUND) }?.removePrefix(BOUND)

    /** New credentials for [gateway] are about to be stored: until [bound], they're no known account's. */
    suspend fun pending(gateway: GatewayUrl) = store.put(key(gateway), PENDING)

    /** [gateway]'s stored session is [owner]'s, and its saved chats were given to it. */
    suspend fun bound(gateway: GatewayUrl, owner: String) = store.put(key(gateway), BOUND + owner)

    suspend fun clear(gateway: GatewayUrl) = store.remove(key(gateway))

    private fun key(gateway: GatewayUrl) = "$PREFIX${gateway.value}"

    private companion object {
        const val PREFIX = "fork.session-owner.v1."
        const val PENDING = "pending"
        const val BOUND = "bound:"
    }
}
