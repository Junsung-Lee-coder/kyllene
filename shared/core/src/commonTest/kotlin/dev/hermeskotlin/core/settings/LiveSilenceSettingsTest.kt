package dev.hermeskotlin.core.settings

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.wearlink.liveSilenceSeconds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fork (requirements §3.3): the Phone and Watch Live silence settings, apart, 10 seconds by default. */
class LiveSilenceSettingsTest {
    @Test
    fun savedSettingsFromBeforeTakeTenForBoth() {
        val old = HermesJson.decodeFromString(AppSettings.serializer(), """{"phoneLive":true}""")
        assertEquals(10, old.phoneLiveSilenceSeconds)
        assertEquals(10, old.watchLiveSilenceSeconds)
        assertEquals(true, old.phoneLive)
    }

    @Test
    fun theyAreKeptApartAndReadBackInRange() {
        val saved = HermesJson.encodeToString(AppSettings.serializer(), AppSettings(phoneLiveSilenceSeconds = 45, watchLiveSilenceSeconds = 7))
        val back = HermesJson.decodeFromString(AppSettings.serializer(), saved)
        assertEquals(45, back.phoneLiveSilenceSeconds)
        assertEquals(7, back.watchLiveSilenceSeconds)
        // A value from elsewhere out of range is used in range; nothing else changes.
        val odd = HermesJson.decodeFromString(AppSettings.serializer(), """{"phoneLiveSilenceSeconds":0,"watchLiveSilenceSeconds":900}""")
        assertEquals(1, liveSilenceSeconds(odd.phoneLiveSilenceSeconds))
        assertEquals(60, liveSilenceSeconds(odd.watchLiveSilenceSeconds))
    }
}
