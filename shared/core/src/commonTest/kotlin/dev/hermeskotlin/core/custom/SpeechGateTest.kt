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
        // A request whose account isn't known (a binding still pending) is reached by any text in its gateway:
        // it fails toward silence.
        owner = null
        val unknown = gate.voiceInput(home)
        owner = "acct-B"
        gate.textInput(home)
        assertFalse(gate.allows(unknown))
        // Text whose account isn't known reaches every account of its gateway, and only it.
        owner = "acct-A"
        val known = gate.voiceInput(home)
        val there = gate.voiceInput(other)
        owner = null
        gate.textInput(home)
        assertFalse(gate.allows(known))
        assertTrue(gate.allows(there))
        // Text whose gateway isn't known reaches every gateway.
        owner = "acct-A"
        val anywhere = gate.voiceInput(other)
        gate.textInput(null)
        assertFalse(gate.allows(anywhere))
    }

    @Test
    fun aReplyIsNeverReadOnceItsGatewaysAccountIsntTheOneThatAsked() = runTest {
        var owner: String? = "acct-A"
        val gate = SpeechGate { owner }
        val asked = gate.voiceInput(home)
        assertTrue(gate.allowsNow(asked))
        owner = "acct-B"
        assertFalse(gate.allowsNow(asked))
        owner = null
        assertFalse(gate.allowsNow(asked))
        owner = "acct-A"
        assertTrue(gate.allowsNow(asked))
    }

    @Test
    fun typedTextFromOutsideAChatCountsBeforeItGoes() = runTest {
        val gate = SpeechGate { "acct-A" }
        val asked = gate.voiceInput(home)
        val seenWhileSending = gate.typed(home) { gate.allows(asked) }
        assertFalse(seenWhileSending, "the typed send went before it was counted")
        assertFalse(gate.allows(asked))
        // Without a gate (a platform with none), it just sends.
        assertEquals("sent", (null as SpeechGate?).typed(home) { "sent" })
    }

    @Test
    fun textTellsWhatsReadingInItsScopeAtOnceUntilItStopsListening() = runTest {
        val gate = SpeechGate { "acct-A" }
        val told = mutableListOf<SpeechGate.Scope?>()
        val stop = gate.onText { told += it }
        gate.textInput(home)
        gate.textInput(other)
        assertEquals<List<SpeechGate.Scope?>>(listOf(gate.scope(home), gate.scope(other)), told)
        stop()
        gate.textInput(home)
        assertEquals(2, told.size)
    }
}
