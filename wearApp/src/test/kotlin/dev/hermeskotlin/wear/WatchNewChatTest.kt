package dev.hermeskotlin.wear

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork: New chat stays the watch's explicit choice only until a chat or bot is chosen or the new chat gets its id. */
class WatchNewChatTest {
    @Test
    fun onlyAChoiceWithoutAChatOrBotIsNewChat() {
        assertTrue(explicitNewChat(picked = true, id = null, botProfile = null))
        assertFalse(explicitNewChat(picked = false, id = null, botProfile = null))
        assertFalse(explicitNewChat(picked = true, id = "adopted", botProfile = null))
        assertFalse(explicitNewChat(picked = true, id = null, botProfile = "assistant"))
    }

    @Test
    fun talkLiveStartsOff() {
        assertFalse(WATCH_LIVE_BY_DEFAULT)
    }

    @Test
    fun theChoiceDefaultsToNothingChosen() {
        assertFalse(WatchChatChoice(null, null).newChat)
    }
}
