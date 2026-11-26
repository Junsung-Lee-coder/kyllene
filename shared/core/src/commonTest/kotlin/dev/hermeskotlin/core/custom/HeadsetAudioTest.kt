package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.custom.HeadsetAudio.Ports
import dev.hermeskotlin.core.custom.HeadsetAudio.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork behaviour: which route the phone's chained voice chat takes for the earphones connected. */
class HeadsetAudioTest {
    private val bluetoothHeadset = Ports(headsetWithMic = true, privateOutput = true, earphonesWithoutUsableMic = true)
    private val musicOnlyEarbuds = Ports(headsetWithMic = false, privateOutput = true, earphonesWithoutUsableMic = true)
    private val carSpeaker = Ports(headsetWithMic = false, privateOutput = true, earphonesWithoutUsableMic = false)
    private val phoneOnly = Ports(headsetWithMic = false, privateOutput = false, earphonesWithoutUsableMic = false)

    @Test
    fun earphonesWithAMicrophoneTakeTheChatWithTalkOverOnOrOff() {
        assertEquals(Route.Headset, HeadsetAudio.route(bluetoothHeadset, talkOver = true))
        assertEquals(Route.Headset, HeadsetAudio.route(bluetoothHeadset, talkOver = false))
        assertFalse(HeadsetAudio.micUnavailable(bluetoothHeadset))
    }

    @Test
    fun withoutThemTheStockRoutesHold() {
        assertEquals(Route.Usual, HeadsetAudio.route(musicOnlyEarbuds, talkOver = true))
        assertEquals(Route.Usual, HeadsetAudio.route(carSpeaker, talkOver = true))
        assertEquals(Route.Speaker, HeadsetAudio.route(phoneOnly, talkOver = true))
        assertEquals(Route.Usual, HeadsetAudio.route(phoneOnly, talkOver = false))
    }

    @Test
    fun earphonesWhoseMicrophoneCantBeUsedAreSaidButOtherOutputsArent() {
        assertTrue(HeadsetAudio.micUnavailable(musicOnlyEarbuds))
        assertFalse(HeadsetAudio.micUnavailable(carSpeaker))
        assertFalse(HeadsetAudio.micUnavailable(phoneOnly))
    }

    @Test
    fun earphonesThatGoAwayFromAHeadsetRouteAreLost() {
        assertTrue(HeadsetAudio.headsetLost(Route.Headset, phoneOnly))
        assertTrue(HeadsetAudio.headsetLost(Route.Headset, musicOnlyEarbuds))
        assertFalse(HeadsetAudio.headsetLost(Route.Headset, bluetoothHeadset))
        // Only a chat on earphones loses them: one on the speaker or usual route is rerouted as stock.
        assertFalse(HeadsetAudio.headsetLost(Route.Speaker, phoneOnly))
        assertFalse(HeadsetAudio.headsetLost(Route.Usual, phoneOnly))
    }
}
