package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.custom.SessionBindings
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fork behaviour: a gateway's session binding (whose account its saved chats are) goes to pending before a new
 * sign-in stores credentials, stays through a token renewal, and goes with signing out.
 */
class SessionBindingsTest {
    private val url = GatewayUrl.parse("http://100.64.0.1:9119")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val tokens = NativeTokens(store)
    private val bindings = SessionBindings(store)
    private val alice = "password\u0000alice"

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): AuthApi {
        val client = createHttpClient(MockEngine(handler), cookies).config { install(nativeBearer(tokens) { 0L }) }
        return AuthApi(client, cookies, tokens, bindings)
    }

    /** [gateway]'s raw state in the stored map: "pending", "bound:…", or null. */
    private suspend fun raw(gateway: GatewayUrl = url) = store.get("fork.session-owner.v2")
        ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
        ?.get(gateway.value)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

    @Test
    fun aPasswordSignInMarksTheBindingPendingBeforeItsCookiesAreStored() = runTest {
        bindings.bound(url, alice)
        var stateAtLogin: String? = "unset"
        val api = api { request ->
            if (request.url.encodedPath != "/auth/password-login") error("unexpected ${request.url}")
            stateAtLogin = raw()
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(
                HttpHeaders.SetCookie to listOf("hermes_session_at=AT-bob; Path=/; HttpOnly"),
                HttpHeaders.ContentType to listOf("application/json"),
            ))
        }

        assertIs<ApiResult.Success<Unit>>(api.signIn(url, "password", "bob", "pw"))

        assertEquals("pending", stateAtLogin)
        assertTrue(cookies.hasCookies(Url(url.value)))
        assertNull(bindings.owner(url))
        assertEquals("pending", raw())
    }

    @Test
    fun aBrowserSignInMarksTheBindingPendingBeforeItsTokensAreStored() = runTest {
        bindings.bound(url, alice)
        var stateAtLogin: String? = "unset"
        val api = api { request ->
            if (request.url.encodedPath != "/auth/native/token") error("unexpected ${request.url}")
            stateAtLogin = raw()
            respond("""{"access_token":"AT","refresh_token":"RT","expires_at":1900000000,"provider":"oidc","user_id":"bob"}""", HttpStatusCode.OK, json)
        }

        assertIs<ApiResult.Success<Unit>>(api.redeemNativeCode(url, "code", "verifier"))

        assertEquals("pending", stateAtLogin)
        assertEquals("AT", tokens.get(url)?.accessToken)
        assertNull(bindings.owner(url))
    }

    @Test
    fun renewingTheSessionsTokensKeepsItsBinding() = runTest {
        tokens.set(url, NativeSession("AT1", "RT1", 1900000000, provider = "self-hosted"))
        bindings.bound(url, alice)
        val api = api { request ->
            when (request.url.encodedPath) {
                "/auth/native/refresh" -> respond(
                    """{"access_token":"AT2","refresh_token":"RT2","expires_at":1900000000,"provider":"self-hosted","user_id":"alice"}""",
                    HttpStatusCode.OK, json)
                else -> if (request.headers[HttpHeaders.Authorization] == "Bearer AT1") {
                    respond("""{"error":"session_expired"}""", HttpStatusCode.Unauthorized, json)
                } else respond("""{"user_id":"alice","provider":"password"}""", HttpStatusCode.OK, json)
            }
        }

        assertIs<ApiResult.Success<AuthUser>>(api.me(url))

        assertEquals("AT2", tokens.get(url)?.accessToken)
        assertEquals(alice, bindings.owner(url))
    }

    @Test
    fun signingOutClearsTheBinding() = runTest {
        bindings.bound(url, alice)
        val api = api { respond("", HttpStatusCode.OK) }
        api.signOut(url)
        assertNull(raw())
    }

    @Test
    fun aSignInWhoseBindingCantBeMarkedDoesntHappen() = runTest {
        val full = object : KeyValueStore by store {
            override suspend fun put(key: String, value: String) {
                if (key.startsWith("fork.session-owner")) throw IllegalStateException("disk full")
                store.put(key, value)
            }
        }
        var asked = false
        val client = createHttpClient(MockEngine { asked = true; respond("""{"ok":true}""", HttpStatusCode.OK, json) }, cookies)
        val api = AuthApi(client, cookies, tokens, SessionBindings(full))

        assertIs<ApiResult.Failed>(api.signIn(url, "password", "bob", "pw"))
        assertIs<ApiResult.Failed>(api.redeemNativeCode(url, "code", "verifier"))

        assertFalse(asked)
        assertFalse(api.hasStoredSession(url))
    }

    // Fork: a sign-in's pending mark covers every gateway its credentials reach.

    private val sibling = GatewayUrl.parse("http://100.64.0.1:9120")
    private val sameHostTls = GatewayUrl.parse("https://100.64.0.1/hermes")
    private val elsewhere = GatewayUrl.parse("http://100.64.0.2:9119")

    @Test
    fun aPasswordSignInMarksEveryGatewayOnItsHostPending() = runTest {
        for (gateway in listOf(url, sameHostTls, elsewhere)) bindings.bound(gateway, alice)
        val api = api { request ->
            assertEquals("pending", raw(url), "marked before the login is sent")
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(
                HttpHeaders.SetCookie to listOf("hermes_session_at=AT-bob; Path=/; HttpOnly"),
                HttpHeaders.ContentType to listOf("application/json"),
            ))
        }

        assertIs<ApiResult.Success<Unit>>(api.signIn(sibling, "password", "bob", "pw"))

        // The cookie is the host's: it's a stored session for the gateway beside it too.
        assertTrue(api.hasStoredSession(url))
        assertEquals("pending", raw(sibling))
        assertNull(bindings.owner(url))
        assertNull(bindings.owner(sameHostTls))
        assertEquals(alice, bindings.owner(elsewhere))
    }

    @Test
    fun aBrowserSignInMarksOnlyItsGateway() = runTest {
        bindings.bound(url, alice)
        val api = api { respond("""{"access_token":"AT","refresh_token":"RT","expires_at":1900000000,"provider":"oidc","user_id":"bob"}""", HttpStatusCode.OK, json) }

        assertIs<ApiResult.Success<Unit>>(api.redeemNativeCode(sibling, "code", "verifier"))

        assertEquals(alice, bindings.owner(url))
        assertFalse(tokens.get(url) != null)
    }

    @Test
    fun aCookieScopedToADomainMarksTheGatewaysUnderIt() = runTest {
        val gw1 = GatewayUrl.parse("http://gw1.lan:9119")
        val gw2 = GatewayUrl.parse("http://gw2.lan:9119")
        val other = GatewayUrl.parse("http://gw3.home:9119")
        for (gateway in listOf(gw2, other)) bindings.bound(gateway, alice)
        val api = api {
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(
                HttpHeaders.SetCookie to listOf("hermes_session_at=AT-bob; Domain=lan; Path=/; HttpOnly"),
                HttpHeaders.ContentType to listOf("application/json"),
            ))
        }

        assertIs<ApiResult.Success<Unit>>(api.signIn(gw1, "password", "bob", "pw"))

        assertTrue(api.hasStoredSession(gw2))
        assertNull(bindings.owner(gw2))
        assertEquals(alice, bindings.owner(other))
    }

    @Test
    fun cookieDomainsMatchHostsAndNamesUnderOneDomain() {
        assertTrue(SessionBindings.sameCookieDomain("100.64.0.1", "100.64.0.1"))
        assertFalse(SessionBindings.sameCookieDomain("100.64.0.1", "100.64.0.2"))
        assertTrue(SessionBindings.sameCookieDomain("a.example.com", "b.example.com"))
        assertTrue(SessionBindings.sameCookieDomain("hermes.example.com", "example.com"))
        assertFalse(SessionBindings.sameCookieDomain("a.example.com", "a.example.org"))
        assertFalse(SessionBindings.sameCookieDomain("localhost", "otherhost"))
        assertFalse(SessionBindings.sameCookieDomain("::1", "fe80::1"))
    }

    @Test
    fun aStoredMapThatDoesntReadCountsAsNoAccount() = runTest {
        store.put("fork.session-owner.v2", "not json")
        assertNull(bindings.owner(url))
        bindings.pending(url)
        assertEquals("pending", raw(url))
    }
}
