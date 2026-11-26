package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.custom.SpeechGate
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork (requirements §6.1): a watch's spoken ask and text sent on the phone, through the real [WatchRelay] and a
 * fake gateway, in the order the phone takes them. The turns and clips are fixtures, not a real watch.
 */
class WatchSpeechGateTest {
    private class Outbox : WatchOutbox {
        val messages = mutableListOf<Pair<String, JsonObject>>()
        val clips = mutableListOf<String>()
        override suspend fun send(node: String, path: String, data: ByteArray): Boolean {
            messages += path to WearJson.decode<JsonObject>(data)!!
            return true
        }

        override suspend fun stream(node: String, path: String, data: ByteArray): Boolean {
            clips += path
            return true
        }
    }

    private class Setup(val relay: WatchRelay, val gateway: FakeGateway, val outbox: Outbox, val gate: SpeechGate) {
        val speakRequests = mutableListOf<String>()
        /** Set: each clip waits for it, so the test can act while the reply is being voiced. */
        var holdClips: CompletableDeferred<Unit>? = null
        val clipAsked = CompletableDeferred<Unit>()
        /** Runs between the prompt going out and Hermes's reply. */
        var beforeReply: suspend () -> Unit = {}
        var replyText = "Done."
    }

    private suspend fun TestScope.setup(owner: () -> String? = { "acct-A" }): Setup {
        val store = InMemoryKeyValueStore()
        val gateway = FakeGateway(backgroundScope)
        val gate = SpeechGate { owner() }
        lateinit var s: Setup
        gateway.apply {
            ready = { FakeGateway.QUIET_READY }
            http = { request ->
                if (request.url.encodedPath == "/api/audio/speak") {
                    s.speakRequests += request.url.encodedPath
                    s.clipAsked.complete(Unit)
                    s.holdClips?.await()
                    json("""{"data_url":"data:audio/mpeg;base64,AAAA","mime_type":"audio/mpeg"}""")
                } else json("""{"session_id":"tip","messages":[]}""")
            }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"runtime","running":false}"""
                    "prompt.submit" -> {
                        backgroundScope.launch {
                            s.beforeReply()
                            call.socket.push(event("message.start", "runtime"))
                            call.socket.push(event("message.complete", "runtime", """{"text":"${s.replyText}","status":"complete"}"""))
                        }
                        """{"status":"streaming"}"""
                    }
                    else -> "{}"
                }
            }
        }
        gateway.start()
        val tokens = NativeTokens(store).apply { set(FakeGateway.URL, NativeSession("test-access", "test-refresh")) }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens)
        val gateways = GatewayRepository(store).apply { save(SavedGateway(FakeGateway.URL.value)) }
        val sessions = SessionsApi(gateway.client)
        val outbox = Outbox()
        val bots = BotsApi(gateway.connection)
        val relay = WatchRelay(gateways, auth, ProfileStore(store), gateway.connection, sessions,
            AudioApi(gateway.client), ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), SettingsStore(store, backgroundScope),
            speechGate = gate)
        s = Setup(relay, gateway, outbox, gate)
        return s
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    private fun Setup.updates(askId: String) =
        outbox.messages.filter { it.first == WearPaths.REPLY && it.second["askId"]?.jsonPrimitive?.content == askId }.map { it.second }

    private fun JsonObject.speech() = this["speech"]?.jsonPrimitive?.content ?: "Off"
    private fun JsonObject.phase() = this["phase"]?.jsonPrimitive?.content

    private fun ask(id: String) = WatchAsk(id, "remind me", "ordinary", speak = true, scope = WatchScope(FakeGateway.URL.value))

    @Test
    fun textOnThePhoneAfterAWatchAskSilencesItsLateReply() = runTest {
        val s = setup()
        s.beforeReply = { s.gate.textInput(FakeGateway.URL) }
        s.relay.ask("watch", ask("a1"))
        until { s.updates("a1").any { it.phase() == "Done" } }
        assertEquals("Off", s.updates("a1").last().speech())
        assertTrue(s.speakRequests.isEmpty())
        assertTrue(s.outbox.clips.isEmpty())
    }

    @Test
    fun aWatchAskAfterTextOnThePhoneIsVoicedAndTheSilencedOneIsntReplayed() = runTest {
        val s = setup()
        s.beforeReply = { s.gate.textInput(FakeGateway.URL) }
        s.relay.ask("watch", ask("old"))
        until { s.updates("old").any { it.phase() == "Done" } }
        s.beforeReply = {}
        s.replyText = "New."
        s.relay.ask("watch", ask("new"))
        until { s.updates("new").any { it.phase() == "Done" } && s.outbox.clips.isNotEmpty() }
        assertEquals("Coming", s.updates("new").last { it.phase() == "Done" }.speech())
        // Only the new reply's clip: the silenced one is never voiced afterwards.
        assertTrue(s.outbox.clips.all { "/new/" in it }, "clips: ${s.outbox.clips}")
        assertEquals(1, s.speakRequests.size)
    }

    @Test
    fun textOnThePhoneStopsAReplyBeingVoicedOnTheWatch() = runTest {
        val s = setup()
        // Long enough for several clips (the watch's are up to 400 characters).
        s.replyText = (1..40).joinToString(" ") { "Sentence number $it is here." }
        s.holdClips = CompletableDeferred()
        s.relay.ask("watch", ask("v1"))
        s.clipAsked.await()
        until { s.updates("v1").any { it.speech() == "Coming" } }
        s.gate.textInput(FakeGateway.URL)
        s.holdClips!!.complete(Unit)
        // The watch is told the reply has no voice after all, and no further clip is made.
        until { s.updates("v1").last().speech() == "Off" }
        delay(200)
        assertEquals(1, s.speakRequests.size)
        assertTrue(s.updates("v1").none { it.speech() == "Unavailable" })
    }

    @Test
    fun anotherAccountsTextDoesntSilenceTheWatch() = runTest {
        var owner = "acct-A"
        val s = setup { owner }
        s.beforeReply = {
            owner = "acct-B"
            s.gate.textInput(FakeGateway.URL)
            owner = "acct-A"
        }
        s.relay.ask("watch", ask("a2"))
        until { s.updates("a2").any { it.phase() == "Done" } && s.outbox.clips.isNotEmpty() }
        assertEquals("Coming", s.updates("a2").last { it.phase() == "Done" }.speech())
    }
}
