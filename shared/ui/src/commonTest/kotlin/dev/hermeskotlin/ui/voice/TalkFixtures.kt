package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fork test fixtures for Talk (Phone Live off): a connected chat, a gateway, a recorder and a player. */
internal object TalkFixtures {
    val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The gateway's socket: creates the chat, takes prompts, and records every method asked of it. */
    class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val submits = MutableStateFlow<List<String>>(emptyList())
        val methods = mutableListOf<String>()
        override val incoming: Flow<String> = inbound.receiveAsFlow()
        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val method = message["method"]?.jsonPrimitive?.contentOrNull
            method?.let(methods::add)
            val id = message["id"] ?: return
            val result = when (method) {
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}""".also { submits.update { it + text } }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit

        fun event(type: String, payload: String = "{}") {
            inbound.trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt9","payload":$payload}}""")
        }

        /** Hermes answers the prompt in full. */
        fun reply(text: String) {
            event("message.start")
            event("message.delta", """{"text":"$text"}""")
            event("message.complete")
        }
    }

    /**
     * Records until it's told to finish (Send), whatever the [VoiceActivity] says: so a Talk recording that a pause or a
     * limit ended would show as the test's own finish never being needed. [hears] says whether recording n hears you.
     */
    class Recorder(
        private val hears: (Int) -> Boolean = { true },
        /** Whether a recording finished by Send has anything in it (the real recorder: a quarter second or more). */
        private val keeps: Boolean = true,
    ) : VoiceRecorder {
        val finishes = MutableStateFlow(0)
        val activities = mutableListOf<VoiceActivity>()
        val recordings = MutableStateFlow(0)
        val dropped = MutableStateFlow(0)
        private var finished: CompletableDeferred<Unit>? = null

        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            activities += activity
            val done = CompletableDeferred<Unit>()
            finished = done
            val n = recordings.value + 1
            recordings.value = n
            if (hears(n)) onSpeech()
            // As the real recorders do from their first frame on.
            onLevel(0f)
            try {
                done.await()
            } catch (e: CancellationException) {
                dropped.update { it + 1 }
                throw e
            }
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = keeps)
        }

        override fun finish() {
            finishes.update { it + 1 }
            finished?.complete(Unit)
        }
    }

    /** Plays at once; counts what it played, and how each request asked for, and let go of, its audio. */
    class Player : SpeechPlayer {
        val played = MutableStateFlow(0)
        val routes = mutableListOf<Boolean>()
        var closed = 0
        override suspend fun play(audio: SpokenAudio) {
            played.update { it + 1 }
        }

        override fun chainedChatAudio(talkOver: Boolean, onHeadsetLost: () -> Unit): () -> Unit {
            routes += talkOver
            return { closed++ }
        }
    }

    /** Holds the chat up, as the call service does once it's started from the screen. */
    class KeepAlive : VoiceKeepAlive {
        var holds = 0
        var releases = 0
        override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
            holds++
            return true
        }

        override fun release() {
            releases++
        }
    }

    /** What the gateway was asked over REST, by path. */
    class Gateway(val live: Boolean = false, transcript: String = "Turn the lights down") {
        val paths = mutableListOf<String>()
        val client = createHttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                when (path) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    // Talk-over on: Talk listens over nothing all the same.
                    "/api/config" -> respond("""{"voice":{"barge_in":true}}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"$transcript"}""", HttpStatusCode.OK, json)
                    "/api/audio/voice-live/status" ->
                        respond(if (live) """{"mode":"gpt-live","available":true}""" else """{"mode":"chained","available":false}""", HttpStatusCode.OK, json)
                    "/api/audio/speak" -> respond("""{"data_url":"data:audio/mpeg;base64,AAAA","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val audio = AudioApi(client)
    }
}

/** A chat connected to [socket] through [gateway]'s client. */
internal suspend fun TestScope.talkSession(gateway: TalkFixtures.Gateway, socket: TalkFixtures.Socket): ChatSession {
    val connection = GatewayConnection(AuthApi(gateway.client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
    connection.start(TalkFixtures.gateway)
    connection.state.first { it is ConnectionState.Connected }
    val session = ChatSession(TalkFixtures.gateway, null, null, connection, SessionsApi(gateway.client), backgroundScope)
    session.start()
    return session
}
