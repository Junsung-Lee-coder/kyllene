package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.settings.CallTarget

/**
 * Fork customization: a voice request that names no conversation goes to the conversation saved as the gateway's
 * Default Call Target (Settings → Voice), instead of starting a new chat.
 *
 * - A conversation or bot chosen for the request always wins.
 * - Only an existing-session target is used. "New session" (or nothing saved) keeps stock behaviour: a new chat.
 * - A saved existing session belongs to the gateway's launch profile (as for the stock Default Call Target), so a
 *   request made in another profile keeps stock behaviour rather than cross profiles.
 *
 * Kept apart from the stock code so the fork's change stays one call at each voice entry point.
 */
object DefaultVoiceConversation {
    /**
     * The stored session a voice request should go to, or null to keep what stock Kyllene does with it.
     * [chatId] and [botProfile] are what the request itself chose; [profile] is the profile it runs in.
     */
    fun storedSessionFor(target: CallTarget?, chatId: String?, botProfile: String?, profile: String?): String? {
        if (chatId != null || botProfile != null || profile != null) return null
        val saved = target?.takeIf { it.valid && it.profile == null } ?: return null
        return saved.sessionId
    }
}
