package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fork (requirements §6.1): the latest actual input decides whether a reply is read aloud automatically. */
class SpeechGateTest {
    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val other = GatewayUrl.parse("https://other.example.ts.net")

    @Test
    fun textAfterAVoiceRequestSilencesItsReplyForGoodAndOnlyLaterVoiceIsRead() = runTest {
        val gate = SpeechGate { "acct-A" }
        val first = gate.voiceInput(home)
        assertTrue(gate.allows(first))
        gate.textInput(home)
        assertFalse(gate.allows(first))
        // A new voice request is read; the old reply stays silent, however late it comes.
        val second = gate.voiceInput(home)
        assertTrue(gate.allows(second))
        assertFalse(gate.allows(first))
        // Text, then voice, then voice: both later requests are read.
        val third = gate.voiceInput(home)
        assertTrue(gate.allows(second))
        assertTrue(gate.allows(third))
        // Nothing but input changes it: asking again, or a reply finishing, doesn't.
        repeat(3) { assertFalse(gate.allows(first)) }
        assertFalse(gate.allows(null))
    }

    @Test
    fun oneAccountsOrGatewaysTextNeverSilencesAnother() = runTest {
        var owner: String? = "acct-A"
        val gate = SpeechGate { owner }
        val a = gate.voiceInput(home)
        val elsewhere = gate.voiceInput(other)
        owner = "acct-B"
        gate.textInput(home)
        owner = "acct-A"
        gate.textInput(other)
        // Account B's text in the same gateway left A's reply alone; the other gateway's text silenced only its own.
        assertTrue(gate.allows(a))
        assertFalse(gate.allows(elsewhere))
        // An unknown account is a scope of its own.
        owner = null
        val unknown = gate.voiceInput(home)
        owner = "acct-A"
        gate.textInput(home)
        assertTrue(gate.allows(unknown))
        assertFalse(gate.allows(a))
    }

    @Test
    fun textTellsWhatsReadingInItsScopeAtOnceUntilItStopsListening() = runTest {
        val gate = SpeechGate { "acct-A" }
        val told = mutableListOf<String>()
        val stop = gate.onText { told += it }
        gate.textInput(home)
        gate.textInput(other)
        assertEquals(listOf(gate.scope(home), gate.scope(other)), told)
        stop()
        gate.textInput(home)
        assertEquals(2, told.size)
    }
}
