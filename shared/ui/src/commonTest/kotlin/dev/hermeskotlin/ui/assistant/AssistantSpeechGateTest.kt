package dev.hermeskotlin.ui.assistant

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.custom.SpeechGate
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork (requirements §6.1, review repair): what's typed in the assistant panel and sent is text input in its chat's
 * gateway, through the real [AssistantPanelModel]. Its voice is dictation only, so it reads nothing aloud itself.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistantSpeechGateTest {
    private val home = SavedGateway("https://hermes.example.ts.net")
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val gateways = GatewayRepository(store)
    private val client = createHttpClient(
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("ws-ticket")) awaitCancellation()
            respond("", HttpStatusCode.ServiceUnavailable)
        },
        cookies,
    )
    private val auth = AuthApi(client, cookies)

    private object Silent : VoiceRecorder, SpeechPlayer {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) =
            Recording(byteArrayOf(), "audio/wav", false)

        override fun finish() = Unit

        override suspend fun play(audio: SpokenAudio) = Unit
    }

    @Test
    fun aQuestionTypedInThePanelIsTextInputAndOpeningItIsnt() = runTest {
        gateways.save(home)
        cookies.addCookie(Url(home.url + "/"), Cookie("hermes_session_at", "token", path = "/"))
        val gate = SpeechGate { "acct-A" }
        val connection = GatewayConnection(auth, openSocket = { _, _ -> error("no socket in tests") }, scope = backgroundScope)
        val beforeOpen = gate.voiceInput(home.gatewayUrl)
        val model = AssistantPanelModel(
            gateways, auth, ProfileStore(store), connection, SessionsApi(client), AudioApi(client), Silent, Silent, backgroundScope,
            speechGate = gate,
        )
        model.session.first { it != null }
        testScheduler.runCurrent()
        assertTrue(gate.allows(beforeOpen), "opening the panel counted as input")

        val asked = gate.voiceInput(home.gatewayUrl)
        model.composer.setTextAndPlaceCursorAtEnd("what's on my screen?")
        model.send()
        testScheduler.runCurrent()
        assertFalse(gate.allows(asked), "a question typed in the panel didn't count as text")
    }
}
