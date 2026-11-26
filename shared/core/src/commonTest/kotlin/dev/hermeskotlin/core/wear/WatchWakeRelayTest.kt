package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.WakeClaim
import dev.hermeskotlin.wearlink.WakeEndSilence
import dev.hermeskotlin.wearlink.WatchWakeSettings
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fork (requirements §5): the phone's side of the watch's wake word, through the real [WatchRelay]. */
class WatchWakeRelayTest {
    private class Outbox : WatchOutbox {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun send(node: String, path: String, data: ByteArray): Boolean { sent += path to data; return true }
        override suspend fun stream(node: String, path: String, data: ByteArray) = true
    }

    private suspend fun TestScope.setup(): Triple<WatchRelay, Outbox, SettingsStore> {
        val store = InMemoryKeyValueStore()
        val gateway = FakeGateway(backgroundScope).apply {
            ready = { FakeGateway.QUIET_READY }
            http = { json("{}") }
        }
        gateway.start()
        val tokens = NativeTokens(store).apply { set(FakeGateway.URL, NativeSession("test-access", "test-refresh")) }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens)
        val gateways = GatewayRepository(store).apply { save(SavedGateway(FakeGateway.URL.value)) }
        val sessions = SessionsApi(gateway.client)
        val bots = BotsApi(gateway.connection)
        val settings = SettingsStore(store, backgroundScope)
        settings.settings.filterNotNull().first()
        val outbox = Outbox()
        val relay = WatchRelay(gateways, auth, ProfileStore(store), gateway.connection, sessions, AudioApi(gateway.client),
            ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), settings)
        return Triple(relay, outbox, settings)
    }

    private suspend fun Outbox.last(path: String): ByteArray {
        withTimeout(10_000) { while (sent.none { it.first == path }) delay(10) }
        return sent.last { it.first == path }.second
    }

    @Test
    fun theStatusCarriesTheWatchsWakeSettingsAndNeverDefaultsThemOff() = runTest {
        val (relay, outbox, settings) = setup()
        settings.update { it.copy(watchWake = true, wakePhrases = "hermes 루미", wakeEndSilenceMs = 3_500, watchWakeBackground = true, watchWakeCharging = true, phoneWake = true) }
        settings.settings.first { it?.watchWake == true }
        relay.wakeSettings("watch")
        val status = WearJson.decode<PhoneStatus>(outbox.last(WearPaths.STATUS))!!
        assertEquals(WatchWakeSettings(enabled = true, phrases = "hermes 루미", endSilenceMs = 3_500, background = true, screenOff = false, charging = true), status.wake)
        // The phone's own wake word isn't the watch's: only the watch's rows go.
        assertEquals(watchWakeSettings(AppSettings(phoneWake = true)).enabled, false)
        assertEquals(WakeEndSilence.MAX_MS, watchWakeSettings(AppSettings(wakeEndSilenceMs = 99_000)).endSilenceMs)
    }

    @Test
    fun savedSettingsFromBeforeStayOff() {
        val old = HermesJson.decodeFromString(AppSettings.serializer(), """{"phoneLive":true}""")
        assertFalse(old.phoneWake)
        assertFalse(old.watchWake)
        assertFalse(old.phoneWakeBackground || old.phoneWakeScreenOff || old.phoneWakeCharging)
        assertFalse(old.watchWakeBackground || old.watchWakeScreenOff || old.watchWakeCharging)
        assertEquals("", old.wakePhrases)
        assertEquals(2_000, old.wakeEndSilenceMs)
    }

    @Test
    fun theFirstClaimStartsAndTheOtherDeviceIsTurnedDown() = runTest {
        val (relay, outbox, _) = setup()
        relay.wakeClaim("watch", WakeClaim("watch-1"))
        val first = WearJson.decode<WakeClaim>(outbox.last(WearPaths.WAKE_CLAIM))!!
        assertEquals(WakeClaim("watch-1", true), first)
        // The phone heard the same words: turned down, so the request goes once.
        assertFalse(relay.wakeClaims.claim("phone-1"))
        // The watch's claim asked again (a retry of the same message) gets the same answer, not a second start.
        outbox.sent.clear()
        relay.wakeClaim("watch", WakeClaim("watch-1"))
        assertTrue(WearJson.decode<WakeClaim>(outbox.last(WearPaths.WAKE_CLAIM))!!.granted == true)
        outbox.sent.clear()
        relay.wakeClaim("watch", WakeClaim("watch-2"))
        assertEquals(false, WearJson.decode<WakeClaim>(outbox.last(WearPaths.WAKE_CLAIM))!!.granted)
        assertNull(WakeClaim("x").granted)
    }
}
