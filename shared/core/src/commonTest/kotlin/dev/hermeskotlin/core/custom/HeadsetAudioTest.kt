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

    // The transitions the Android player runs (HeadsetAudio.Tracker), output change by output change.

    private val wiredHeadphones = Ports(headsetWithMic = false, privateOutput = true, earphonesWithoutUsableMic = true)
    private val twoOutputs = Ports(headsetWithMic = false, privateOutput = true, earphonesWithoutUsableMic = true)

    /** The player's loop: apply each step, and hold the route it took. */
    private fun HeadsetAudio.Tracker.step(open: Boolean, talkOver: Boolean, ports: Ports): HeadsetAudio.Step =
        change(open, talkOver, ports).also { if (it is HeadsetAudio.Step.Use) held(it.route) }

    private fun use(route: Route) = HeadsetAudio.Step.Use(route)

    @Test
    fun musicOnlyEarbudsGoneOnTheUsualRouteSilenceTheChatInsteadOfTheSpeaker() {
        val tracker = HeadsetAudio.Tracker()
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = true, musicOnlyEarbuds))
        // Stock would take speaker call routing here, for talk-over.
        assertEquals(HeadsetAudio.Step.Silence, tracker.step(open = true, talkOver = true, phoneOnly))
        // Silenced: nothing is routed for these chats again, the speaker least of all, until they've closed.
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = true, phoneOnly))
        assertEquals(use(Route.Usual), tracker.step(open = false, talkOver = false, phoneOnly))
        // A new chat on the phone alone gets the stock speaker route again.
        assertEquals(use(Route.Speaker), tracker.step(open = true, talkOver = true, phoneOnly))
    }

    @Test
    fun wiredHeadphonesUnpluggedSilenceTheChatWithTalkOverOff() {
        val tracker = HeadsetAudio.Tracker()
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = false, wiredHeadphones))
        assertEquals(HeadsetAudio.Step.Silence, tracker.step(open = true, talkOver = false, phoneOnly))
    }

    @Test
    fun aHeadsetTheChatTalkedThroughGoneSilencesIt() {
        val tracker = HeadsetAudio.Tracker()
        assertEquals(use(Route.Headset), tracker.step(open = true, talkOver = false, bluetoothHeadset))
        // Its call profile gone while music-only earbuds remain: lost all the same.
        assertEquals(HeadsetAudio.Step.Silence, tracker.step(open = true, talkOver = false, musicOnlyEarbuds))
    }

    @Test
    fun beforeAndroid12BluetoothEarphonesAreAPrivateOutputAndTheirLossSilencesTheChat() {
        // No communication device to pick there: the earphones count as a private output only.
        val tracker = HeadsetAudio.Tracker()
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = true, musicOnlyEarbuds))
        assertEquals(HeadsetAudio.Step.Silence, tracker.step(open = true, talkOver = true, phoneOnly))
    }

    @Test
    fun earphonesConnectedMidChatAreTheChatsFromThen() {
        val tracker = HeadsetAudio.Tracker()
        assertEquals(use(Route.Speaker), tracker.step(open = true, talkOver = true, phoneOnly))
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = true, musicOnlyEarbuds))
        assertEquals(HeadsetAudio.Step.Silence, tracker.step(open = true, talkOver = true, phoneOnly))
    }

    @Test
    fun oneOfTwoOutputsGoingAwayIsntALoss() {
        val tracker = HeadsetAudio.Tracker()
        tracker.step(open = true, talkOver = true, twoOutputs)
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = true, carSpeaker))
    }

    @Test
    fun androidsBecomingNoisyWarningSilencesOnceAndOnlyAnOpenChat() {
        val tracker = HeadsetAudio.Tracker()
        assertEquals(null, tracker.becomingNoisy(open = false))
        tracker.step(open = true, talkOver = false, musicOnlyEarbuds)
        assertEquals(HeadsetAudio.Step.Silence, tracker.becomingNoisy(open = true))
        assertEquals(null, tracker.becomingNoisy(open = true))
        // The device change that follows doesn't move it anywhere either.
        assertEquals(use(Route.Usual), tracker.step(open = true, talkOver = true, phoneOnly))
    }
}
