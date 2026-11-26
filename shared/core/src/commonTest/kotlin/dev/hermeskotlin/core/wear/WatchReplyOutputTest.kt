package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.custom.PhoneReplyOutput
import dev.hermeskotlin.core.custom.SpeechGate
import dev.hermeskotlin.core.custom.VoiceDevice
import dev.hermeskotlin.core.voice.SpokenAudio
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §6): which device reads a watch ask's reply aloud, through the real [WatchRelay] and [SpeechGate]
 * over a fake gateway, with the phone's side faked: once, on one device; earphones first; else the device of the latest
 * voice request; text silencing it everywhere; an account switch, a busy phone and lost earphones.
 */
class WatchReplyOutputTest {
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

    /** The phone's side: what it would read aloud, clip by clip, with its earphones and media as set. */
    private class Phone : PhoneReplyOutput {
        var earphones = false
        var free = true
        /** Set: each clip "plays" until it's completed (or the reading is cancelled). */
        var holdPlay: CompletableDeferred<Unit>? = null
        /** The earphones go away after this many clips: what plays stops, and nothing more is made. */
        var loseAfter: Int? = null
        val played = mutableListOf<Int>()
        var readings = 0
        var cancelled = 0
        /** Readings holding the phone's media now: none once each ends, however. */
        var holding = 0
        val earphonesOnly = mutableListOf<Boolean>()
        override fun earphones() = earphones
        override fun free() = free && holding == 0
        override suspend fun play(earphonesOnly: Boolean, next: suspend (Int) -> SpokenAudio?): Boolean {
            readings++
            this.earphonesOnly += earphonesOnly
            holding++
            try {
                var index = 0
                while (true) {
                    if (loseAfter == index) return true
                    next(index) ?: break
                    played += index
                    holdPlay?.await()
                    index++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            } finally {
                holding--
            }
            return true
        }
    }

    private class Setup(val relay: WatchRelay, val gateway: FakeGateway, val outbox: Outbox, val gate: SpeechGate, val phone: Phone) {
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
        val phone = Phone()
        val bots = BotsApi(gateway.connection)
        val relay = WatchRelay(gateways, auth, ProfileStore(store), gateway.connection, sessions,
            AudioApi(gateway.client), ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), SettingsStore(store, backgroundScope),
            speechGate = gate, phoneOutput = phone)
        s = Setup(relay, gateway, outbox, gate, phone)
        return s
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    private fun Setup.updates(askId: String) =
        outbox.messages.filter { it.first == WearPaths.REPLY && it.second["askId"]?.jsonPrimitive?.content == askId }.map { it.second }

    private fun JsonObject.speech() = this["speech"]?.jsonPrimitive?.content ?: "Off"
    private fun JsonObject.phase() = this["phase"]?.jsonPrimitive?.content
    private fun JsonObject.earphonesOnly() = this["earphonesOnly"]?.jsonPrimitive?.content == "true"

    private fun ask(id: String, earphones: Boolean? = false) =
        WatchAsk(id, "remind me", "ordinary", speak = true, scope = WatchScope(FakeGateway.URL.value), earphones = earphones)

    private suspend fun Setup.done(id: String) = until { updates(id).any { it.phase() == "Done" } }

    @Test
    fun earphonesOnThePhoneOnlyReadTheWatchsReplyThereOnceAndNotOnTheWatch() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.relay.ask("watch", ask("p1"))
        s.done("p1")
        until { s.phone.played.isNotEmpty() }
        assertEquals("Off", s.updates("p1").last().speech(), "the watch doesn't voice it")
        assertTrue(s.outbox.clips.isEmpty(), "no clip went to the watch")
        assertEquals(1, s.phone.readings)
        assertEquals(listOf(0), s.phone.played)
        assertEquals(1, s.speakRequests.size)
    }

    @Test
    fun earphonesOnTheWatchKeepItThere() = runTest {
        val s = setup()
        s.relay.ask("watch", ask("w1", earphones = true))
        s.done("w1")
        until { s.outbox.clips.isNotEmpty() }
        assertEquals("Coming", s.updates("w1").last { it.phase() == "Done" }.speech())
        assertEquals(0, s.phone.readings)
    }

    @Test
    fun earphonesOnBothKeepItWhereItWasAskedAsBefore() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.relay.ask("watch", ask("b1", earphones = true))
        s.done("b1")
        until { s.outbox.clips.isNotEmpty() }
        assertEquals(0, s.phone.readings)
    }

    @Test
    fun noEarphonesTheLatestVoiceRequestsDeviceReadsIt() = runTest {
        val s = setup()
        // Asked on the watch, then a newer voice request on the phone before the reply: the phone reads it.
        s.beforeReply = { s.gate.voiceInput(FakeGateway.URL, VoiceDevice.Phone) }
        s.relay.ask("watch", ask("late"))
        s.done("late")
        until { s.phone.played.isNotEmpty() }
        assertTrue(s.outbox.clips.isEmpty())
        // The watch's own ask the latest: the watch reads it, as before.
        s.beforeReply = {}
        s.relay.ask("watch", ask("own"))
        s.done("own")
        until { s.outbox.clips.isNotEmpty() }
        assertEquals(1, s.phone.readings)
        assertTrue(s.outbox.clips.all { "/own/" in it })
    }

    @Test
    fun anotherAccountsVoiceRequestDoesntMoveIt() = runTest {
        var owner = "acct-A"
        val s = setup { owner }
        s.beforeReply = {
            owner = "acct-B"
            s.gate.voiceInput(FakeGateway.URL, VoiceDevice.Phone)
            owner = "acct-A"
        }
        s.relay.ask("watch", ask("iso"))
        s.done("iso")
        until { s.outbox.clips.isNotEmpty() }
        assertEquals(0, s.phone.readings)
    }

    @Test
    fun anOlderWatchThatDoesntSayKeepsItsVoice() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.relay.ask("watch", ask("old", earphones = null))
        s.done("old")
        until { s.outbox.clips.isNotEmpty() }
        assertEquals(0, s.phone.readings)
    }

    @Test
    fun aPhoneWhoseVoiceIsTakenLeavesItOnTheWatch() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.phone.free = false
        s.relay.ask("watch", ask("busy"))
        s.done("busy")
        until { s.outbox.clips.isNotEmpty() }
        assertEquals(0, s.phone.readings)
    }

    @Test
    fun textStopsTheReadingOnThePhoneAndItIsNeverHeardAfter() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.replyText = (1..40).joinToString(" ") { "Sentence number $it is here." }
        s.phone.holdPlay = CompletableDeferred()
        s.relay.ask("watch", ask("t1"))
        until { s.phone.played.isNotEmpty() }
        s.gate.textInput(FakeGateway.URL)
        until { s.phone.cancelled == 1 }
        s.phone.holdPlay!!.complete(Unit)
        delay(200)
        assertEquals(listOf(0), s.phone.played)
        assertTrue(s.outbox.clips.isEmpty())
        assertTrue(s.updates("t1").none { it.speech() == "Coming" || it.speech() == "Unavailable" })
    }

    @Test
    fun textBeforeTheReplySilencesItEverywhere() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.beforeReply = { s.gate.textInput(FakeGateway.URL) }
        s.relay.ask("watch", ask("t2"))
        s.done("t2")
        delay(200)
        assertEquals(0, s.phone.readings)
        assertTrue(s.outbox.clips.isEmpty())
        assertTrue(s.speakRequests.isEmpty())
    }

    @Test
    fun anAccountSwitchBeforeTheReplyReadsNothingOnThePhone() = runTest {
        var owner = "acct-A"
        val s = setup { owner }
        s.phone.earphones = true
        s.beforeReply = { owner = "acct-B" }
        s.relay.ask("watch", ask("sw"))
        s.done("sw")
        delay(200)
        assertTrue(s.phone.played.isEmpty())
        assertTrue(s.outbox.clips.isEmpty())
    }

    @Test
    fun earphonesGoneOnThePhoneStopItThereAndNothingGoesToTheWatch() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.phone.loseAfter = 1
        s.replyText = (1..40).joinToString(" ") { "Sentence number $it is here." }
        s.relay.ask("watch", ask("lost"))
        s.done("lost")
        until { s.phone.readings == 1 && s.phone.played.size == 1 }
        delay(200)
        assertEquals(listOf(0), s.phone.played)
        assertTrue(s.outbox.clips.isEmpty(), "never moved on to the watch")
        assertTrue(s.updates("lost").none { it.speech() == "Unavailable" })
        assertEquals(1, s.speakRequests.size)
    }

    // Repair (review B1): the phone's reading is the turn's: a new ask, Stop and later text all end it.

    private val long = (1..40).joinToString(" ") { "Sentence number $it is here." }

    @Test
    fun aNewWatchAskMidReadingStopsTheOldOneAndOnlyTheNewIsRead() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.replyText = long
        s.phone.holdPlay = CompletableDeferred()
        s.relay.ask("watch", ask("first"))
        until { s.phone.played.isNotEmpty() }
        // A new spoken ask while the first reply is read: the reading stops at once, mid-clip, and lets the media go.
        s.replyText = "New."
        s.relay.ask("watch", ask("second"))
        until { s.phone.cancelled == 1 }
        s.phone.holdPlay!!.complete(Unit)
        s.done("second")
        // Read on the phone again: the old reading let the media go (a reading still holding it would send this
        // reply to the watch instead).
        until { s.phone.readings == 2 && s.phone.holding == 0 }
        // The old reply's first clip only, then the new reply's own; nothing ever to the watch.
        assertEquals(listOf(0, 0), s.phone.played)
        assertTrue(s.outbox.clips.isEmpty())
        // Text later stops the new reading's voice too: its listener is the current one.
        s.gate.textInput(FakeGateway.URL)
        delay(200)
        assertEquals(0, s.phone.holding)
    }

    @Test
    fun theWatchsStopMidReadingStopsItAndLaterTextFindsNothingToStop() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.replyText = long
        s.phone.holdPlay = CompletableDeferred()
        s.relay.ask("watch", ask("st"))
        until { s.phone.played.isNotEmpty() }
        s.relay.stop("st")
        until { s.phone.cancelled == 1 }
        assertEquals(0, s.phone.holding, "the media is let go")
        s.phone.holdPlay!!.complete(Unit)
        delay(200)
        assertEquals(listOf(0), s.phone.played, "no clip after Stop")
        s.gate.textInput(FakeGateway.URL)
        delay(200)
        assertEquals(1, s.phone.cancelled)
        assertEquals(listOf(0), s.phone.played)
        assertTrue(s.outbox.clips.isEmpty())
    }

    @Test
    fun textAfterASecondAskStopsTheSecondReading() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.relay.ask("watch", ask("one"))
        s.done("one")
        until { s.phone.readings == 1 && s.phone.holding == 0 }
        s.replyText = long
        s.phone.holdPlay = CompletableDeferred()
        s.relay.ask("watch", ask("two"))
        until { s.phone.readings == 2 && s.phone.played.size == 2 }
        s.gate.textInput(FakeGateway.URL)
        until { s.phone.cancelled == 1 }
        assertEquals(0, s.phone.holding)
        s.phone.holdPlay!!.complete(Unit)
        delay(200)
        assertEquals(2, s.phone.played.size)
    }

    // Repair (privacy): a device picked for its earphones plays only through earphones still there.

    @Test
    fun thePhonePickedForItsEarphonesSaysSoWhenItReads() = runTest {
        val s = setup()
        s.phone.earphones = true
        s.relay.ask("watch", ask("pe"))
        s.done("pe")
        until { s.phone.readings == 1 }
        assertEquals(listOf(true), s.phone.earphonesOnly)
        // Picked with no earphones anywhere (the latest voice request was on the phone): its speaker may play it.
        s.phone.earphones = false
        s.beforeReply = { s.gate.voiceInput(FakeGateway.URL, VoiceDevice.Phone) }
        s.relay.ask("watch", ask("none"))
        s.done("none")
        until { s.phone.readings == 2 }
        assertEquals(listOf(true, false), s.phone.earphonesOnly)
    }

    @Test
    fun theWatchPickedForItsEarphonesIsToldSo() = runTest {
        val s = setup()
        s.relay.ask("watch", ask("we", earphones = true))
        s.done("we")
        assertTrue(s.updates("we").last { it.phase() == "Done" }.earphonesOnly())
        // None anywhere, or on both: not for earphones, as before.
        s.relay.ask("watch", ask("wn", earphones = false))
        s.done("wn")
        assertFalse(s.updates("wn").last { it.phase() == "Done" }.earphonesOnly())
        s.phone.earphones = true
        s.relay.ask("watch", ask("wb", earphones = true))
        s.done("wb")
        assertFalse(s.updates("wb").last { it.phase() == "Done" }.earphonesOnly())
        // A reply read on the phone doesn't tell the watch to need anything.
        s.relay.ask("watch", ask("wp", earphones = false))
        s.done("wp")
        assertFalse(s.updates("wp").last { it.phase() == "Done" }.earphonesOnly())
    }
}
