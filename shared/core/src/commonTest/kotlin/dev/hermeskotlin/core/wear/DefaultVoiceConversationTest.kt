package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.custom.DefaultVoiceConversation
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.CallTarget
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour: a watch voice request (an ask, or a live call) with no conversation chosen goes to the Default
 * Call Target's existing session. Driven through the phone's real [WatchRelay] entry points against a fake gateway.
 */
class DefaultVoiceConversationTest {
    private val roster = """{"profiles":[{"name":"assistant","display_name":"Assistant","canonical_session":{"id":"root","resolved_id":"tip"}}]}"""
    private val url = FakeGateway.URL.value

    private class Outbox : WatchOutbox {
        override suspend fun send(node: String, path: String, data: ByteArray) = true
        override suspend fun stream(node: String, path: String, data: ByteArray) = true
    }

    private data class Setup(val relay: WatchRelay, val gateway: FakeGateway, val settings: SettingsStore)

    private suspend fun TestScope.setup(store: KeyValueStore = InMemoryKeyValueStore()): Setup {
        val gateway = FakeGateway(backgroundScope).apply {
            ready = { FakeGateway.QUIET_READY }
            http = { request ->
                if (request.url.encodedPath == "/api/audio/voice-live/status") json("""{"mode":"gpt-live","available":true}""")
                else if (request.url.encodedPath == "/api/sessions") json("""{"sessions":[{"id":"ordinary","title":"Plans"}]}""")
                else json("""{"session_id":"x","messages":[]}""")
            }
            answer = { call -> when (call.method) {
                "profiles.list" -> roster
                "session.resume" -> """{"session_id":"runtime","running":false}"""
                else -> "{}"
            } }
        }
        gateway.start()
        val tokens = NativeTokens(store).apply { set(FakeGateway.URL, NativeSession("test-access", "test-refresh")) }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens)
        val gateways = GatewayRepository(store).apply { save(SavedGateway(url)) }
        val sessions = SessionsApi(gateway.client)
        val bots = BotsApi(gateway.connection)
        val settings = SettingsStore(store, backgroundScope)
        settings.settings.filterNotNull().first()
        val relay = WatchRelay(gateways, auth, ProfileStore(store), gateway.connection, sessions,
            AudioApi(gateway.client), ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, Outbox(), bots, BotChats(bots), settings)
        return Setup(relay, gateway, settings)
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    private fun Setup.defaultTo(target: CallTarget?) = settings.update { it.copy(callTargets = target?.let { t -> mapOf(url to t) } ?: emptyMap()) }

    private fun Setup.resumed() = gateway.sent("session.resume").map { it.param("session_id") }
    private fun Setup.created() = gateway.sent("session.create").size

    @Test
    fun aWatchAskWithNoConversationGoesToTheDefaultSession() = runTest {
        val s = setup()
        s.defaultTo(CallTarget(sessionId = "ordinary", label = "Plans"))
        s.relay.ask("watch", WatchAsk("a1", "hello", chatId = null, speak = false))
        until { s.resumed().isNotEmpty() }
        assertEquals(listOf("ordinary"), s.resumed().distinct())
        assertEquals(0, s.created())
    }

    @Test
    fun aChosenConversationStillWinsOverTheDefault() = runTest {
        val s = setup()
        s.defaultTo(CallTarget(sessionId = "ordinary"))
        s.relay.ask("watch", WatchAsk("a1", "hello", chatId = "chosen", speak = false))
        until { s.resumed().isNotEmpty() }
        assertEquals(listOf("chosen"), s.resumed().distinct())
    }

    @Test
    fun withoutASavedSessionTheAskStartsANewChatAsStockDoes() = runTest {
        for (target in listOf(null, CallTarget(), CallTarget(profile = "assistant"))) {
            val s = setup()
            s.defaultTo(target)
            s.relay.ask("watch", WatchAsk("a-$target", "hello", chatId = null, speak = false))
            until { s.created() > 0 }
            assertTrue(s.resumed().isEmpty(), "resumed ${s.resumed()} for $target")
        }
    }

    @Test
    fun anAskInAnotherProfileDoesNotCrossIntoTheLaunchProfilesDefault() = runTest {
        val store = InMemoryKeyValueStore()
        ProfileStore(store).set(FakeGateway.URL, "work")
        val s = setup(store)
        s.defaultTo(CallTarget(sessionId = "ordinary"))
        s.relay.ask("watch", WatchAsk("a1", "hello", chatId = null, speak = false, scope = WatchScope(url, "work")))
        until { s.created() > 0 }
        assertTrue(s.resumed().isEmpty())
    }

    @Test
    fun aWatchCallWithNoConversationOpensTheDefaultSessionButAPhoneRingDoesNot() = runTest {
        val s = setup()
        s.defaultTo(CallTarget(sessionId = "ordinary"))
        val watchCall = assertIs<CallChat.Ready>(s.relay.chatForCall(null, node = "watch", voiceDefault = true))
        assertEquals("ordinary", watchCall.session.state.value.storedSessionId)
        assertNull(watchCall.profile)
        // The phone's own call path (an answered ring) passes no voiceDefault and is unchanged.
        val phoneCall = assertIs<CallChat.Ready>(s.relay.chatForCall(null))
        assertNull(phoneCall.session.state.value.storedSessionId)
        // A chosen conversation wins.
        val chosen = assertIs<CallChat.Ready>(s.relay.chatForCall("chosen", node = "watch", voiceDefault = true))
        assertEquals("chosen", chosen.session.state.value.storedSessionId)
    }

    @Test
    fun thePolicyKeepsStockBehaviourUnlessAnExistingLaunchProfileSessionIsSaved() {
        val saved = CallTarget(sessionId = "ordinary")
        assertEquals("ordinary", DefaultVoiceConversation.storedSessionFor(saved, null, null, null))
        assertNull(DefaultVoiceConversation.storedSessionFor(saved, "chosen", null, null))
        assertNull(DefaultVoiceConversation.storedSessionFor(saved, null, "assistant", null))
        assertNull(DefaultVoiceConversation.storedSessionFor(saved, null, null, "work"))
        assertNull(DefaultVoiceConversation.storedSessionFor(null, null, null, null))
        assertNull(DefaultVoiceConversation.storedSessionFor(CallTarget(), null, null, null))
        assertNull(DefaultVoiceConversation.storedSessionFor(CallTarget(profile = "assistant"), null, null, null))
        assertNull(DefaultVoiceConversation.storedSessionFor(CallTarget(sessionId = " "), null, null, null))
    }
}
