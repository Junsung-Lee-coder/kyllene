package dev.hermeskotlin.core.custom

import dev.hermeskotlin.core.custom.VoiceDevice.Phone
import dev.hermeskotlin.core.custom.VoiceDevice.Watch
import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fork (requirements §6): which device reads an automatic reply, and the latest voice request each scope sees. */
class ReplyOutputTest {
    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private val other = GatewayUrl.parse("https://other.example.ts.net")

    @Test
    fun earphonesFirstThenTheLatestVoiceRequestsDevice() {
        // Earphones on one device: that one, wherever it was asked or last spoken.
        assertEquals(Phone, ReplyOutput.choose(Watch, Watch, phoneEarphones = true, watchEarphones = false))
        assertEquals(Watch, ReplyOutput.choose(Phone, Phone, phoneEarphones = false, watchEarphones = true))
        // None: the latest voice request's device; not known, where it was asked.
        assertEquals(Phone, ReplyOutput.choose(Watch, Phone, phoneEarphones = false, watchEarphones = false))
        assertEquals(Watch, ReplyOutput.choose(Phone, Watch, phoneEarphones = false, watchEarphones = false))
        assertEquals(Watch, ReplyOutput.choose(Watch, null, phoneEarphones = false, watchEarphones = false))
        // Both: no order agreed, so where it was asked, as before.
        assertEquals(Watch, ReplyOutput.choose(Watch, Phone, phoneEarphones = true, watchEarphones = true))
        assertEquals(Phone, ReplyOutput.choose(Phone, Watch, phoneEarphones = true, watchEarphones = true))
    }

    @Test
    fun theLatestVoiceRequestIsTheNewestInTheSameAccount() = runTest {
        var owner: String? = "acct-A"
        val gate = SpeechGate { owner }
        val watchAsk = gate.voiceInput(home, Watch)
        assertEquals(Watch, watchAsk.device)
        assertEquals(Watch, gate.latestVoice(watchAsk))
        gate.voiceInput(home, Phone)
        assertEquals(Phone, gate.latestVoice(watchAsk))
        // Text doesn't change which device spoke last.
        gate.textInput(home)
        assertEquals(Phone, gate.latestVoice(watchAsk))
        // Another account's, or another gateway's, voice request isn't this one's.
        owner = "acct-B"
        gate.voiceInput(home, Watch)
        gate.voiceInput(other, Watch)
        assertEquals(Phone, gate.latestVoice(watchAsk))
        // An account not known yet fails toward the newest.
        owner = null
        gate.voiceInput(home, Watch)
        assertEquals(Watch, gate.latestVoice(watchAsk))
        assertNull(gate.latestVoice(null))
    }
}
