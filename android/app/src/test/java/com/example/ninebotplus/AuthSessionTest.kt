package com.example.ninebotplus

import com.example.ninebotplus.data.AuthAssembler
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.ServerConfiguration
import com.example.ninebotplus.network.NinePlusApiClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * P0 auth regression tests.
 *
 * Contract under test (matches iOS NinebotViewModel.currentConfiguration):
 * - session token lives in LoginResult, not in the server-URL config blob
 * - every authenticated request carries X-NinePlus-Session
 * - bearer and session coexist
 * - logout / cleared login means no session header
 * - a recreated configuration (fresh process) still sends the session
 */
class AuthSessionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `login then dashboard sends session header`() = runBlocking {
        server.enqueue(json("""{"ok":true,"data":{"session_token":"sess-abc","phone":"13800000000"}}"""))
        server.enqueue(json("""{"ok":true,"data":{"vehicles":[]}}"""))

        val bare = ServerConfiguration(baseUrlString = baseUrl(), bearerToken = "app-token")
        val client = NinePlusApiClient(bare)
        val login = client.login("13800000000", "secret")

        assertThat(login.sessionToken).isEqualTo("sess-abc")

        // Effective config must be rebuilt from LoginResult.
        val effective = AuthAssembler.effectiveConfiguration(
            baseUrlString = baseUrl(),
            bearerToken = "app-token",
            loginResult = login.copy(phone = "13800000000"),
        )
        assertThat(effective.appSessionToken).isEqualTo("sess-abc")

        NinePlusApiClient(effective).fetchDashboard()

        server.takeRequest().let { loginRequest ->
            assertThat(loginRequest.path).contains("/accounts/login")
            // Login itself does not yet have a session.
            assertThat(loginRequest.getHeader("X-NinePlus-Session")).isNull()
            assertThat(loginRequest.getHeader("Authorization")).isEqualTo("Bearer app-token")
        }

        val dashboardRequest = server.takeRequest()
        assertThat(dashboardRequest.path).contains("/vehicles")
        assertThat(dashboardRequest.getHeader("X-NinePlus-Session")).isEqualTo("sess-abc")
        assertThat(dashboardRequest.getHeader("Authorization")).isEqualTo("Bearer app-token")
    }

    @Test
    fun `process recreation keeps session via login result`() = runBlocking {
        // Simulate: login persisted LoginResult, process restarted, new repository
        // rebuilds configuration from the same LoginResult.
        val loginResult = LoginResult(
            uuid = "u",
            phone = "138",
            sessionToken = "persisted-session",
        )
        val rebuilt = AuthAssembler.effectiveConfiguration(
            baseUrlString = baseUrl(),
            bearerToken = "tok",
            loginResult = loginResult,
        )

        server.enqueue(json("""{"ok":true,"data":{"vehicles":[]}}"""))
        NinePlusApiClient(rebuilt).fetchDashboard()

        val request = server.takeRequest()
        assertThat(request.getHeader("X-NinePlus-Session")).isEqualTo("persisted-session")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok")
    }

    @Test
    fun `logout drops session header`() = runBlocking {
        // After logout LoginResult is null, so AuthAssembler must not emit a session.
        val loggedOut = AuthAssembler.effectiveConfiguration(
            baseUrlString = baseUrl(),
            bearerToken = "tok",
            loginResult = null,
        )
        assertThat(loggedOut.appSessionToken).isNull()

        server.enqueue(json("""{"ok":true,"data":{"vehicles":[]}}"""))
        NinePlusApiClient(loggedOut).fetchDashboard()

        val request = server.takeRequest()
        assertThat(request.getHeader("X-NinePlus-Session")).isNull()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok")
    }

    @Test
    fun `bearer and session coexist on authenticated requests`() = runBlocking {
        val config = AuthAssembler.effectiveConfiguration(
            baseUrlString = baseUrl(),
            bearerToken = "my-bearer",
            loginResult = LoginResult(sessionToken = "my-session"),
        )
        server.enqueue(json("""{"ok":true,"data":{"vehicles":[]}}"""))
        NinePlusApiClient(config).fetchDashboard()

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer my-bearer")
        assertThat(request.getHeader("X-NinePlus-Session")).isEqualTo("my-session")
    }

    @Test
    fun `blank session is not sent`() = runBlocking {
        val config = AuthAssembler.effectiveConfiguration(
            baseUrlString = baseUrl(),
            bearerToken = "",
            loginResult = LoginResult(sessionToken = "   "),
        )
        assertThat(config.appSessionToken).isNull()

        server.enqueue(json("""{"ok":true,"data":{"vehicles":[]}}"""))
        NinePlusApiClient(config).fetchDashboard()

        val request = server.takeRequest()
        assertThat(request.getHeader("X-NinePlus-Session")).isNull()
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test
    fun `http error surfaces status and body message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"unauthorized"}}"""))
        try {
            NinePlusApiClient(
                AuthAssembler.effectiveConfiguration(baseUrl(), "", null),
            ).healthCheck()
            throw AssertionError("expected failure")
        } catch (e: Exception) {
            assertThat(e.message).contains("401")
            assertThat(e.message).contains("unauthorized")
        }
    }

    @Test
    fun `endpoint url construction joins path segments`() = runBlocking {
        val config = AuthAssembler.effectiveConfiguration(
            baseUrlString = baseUrl(),
            bearerToken = "",
            loginResult = null,
        )
        server.enqueue(json("""{"ok":true,"data":{}}"""))
        NinePlusApiClient(config).ringBell("SN-1")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/vehicles/SN-1/bell")
    }

    @Test
    fun `envelope error throws server message`() = runBlocking {
        server.enqueue(json("""{"ok":false,"error":{"message":"vehicle offline"}}"""))
        try {
            NinePlusApiClient(
                AuthAssembler.effectiveConfiguration(baseUrl(), "", null),
            ).openBucket("SN-1")
            throw AssertionError("expected failure")
        } catch (e: Exception) {
            assertThat(e.message).contains("vehicle offline")
        }
    }
}
