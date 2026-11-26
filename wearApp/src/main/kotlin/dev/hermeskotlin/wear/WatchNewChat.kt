package dev.hermeskotlin.wear

/**
 * Fork: whether the watch's chat choice is New chat picked here, which the phone keeps apart from "nothing chosen"
 * (that one goes to the default conversation; see the phone's DefaultVoiceConversation). Only a choice with no chat
 * id and no bot can be it: picking a chat or a bot, or a new chat getting its id, ends it.
 */
internal fun explicitNewChat(picked: Boolean, id: String?, botProfile: String?): Boolean = picked && id == null && botProfile == null
