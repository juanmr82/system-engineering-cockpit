package com.sec.security

import com.sec.config.AuthSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [Oidc] end to end against a fake Keycloak — a real embedded server, not a
 * [io.ktor.client.engine.mock.MockEngine], because `jwks-rsa`'s `JwkProviderBuilder` fetches the
 * JWKS document with its own `java.net.URL` connection, entirely outside the [HttpClient] this
 * class is handed. A mocked client would leave the one part of this flow that is actually hard to
 * get right — signature verification against a real JWKS response — untested.
 *
 * Covers the pure functions (the PKCE challenge, redirect sanitisation), the happy path (PKCE end
 * to end, claims mapped into [UserSession], the CSRF token minted, roles/groups re-read on
 * [Oidc.refresh]), and the rejections that matter most: a tampered signature, the wrong audience,
 * an expired token, an unknown `state`.
 */
class OidcFlowTest {

    // -- pure functions -----------------------------------------------------------------------

    // RFC 7636 §A appendix test vector.
    @Test
    fun `the PKCE challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `a redirect target must be a same-origin path, or it falls back to root`() {
        assertEquals("/requirements/modules", sanitizeRedirectTarget("/requirements/modules"))
        assertEquals("/", sanitizeRedirectTarget(null))
        assertEquals("/", sanitizeRedirectTarget(""))
        assertEquals("/", sanitizeRedirectTarget("//evil.example.com"))
        assertEquals("/", sanitizeRedirectTarget("/\\evil.example.com"))
        assertEquals("/", sanitizeRedirectTarget("https://evil.example.com"))
        assertEquals("/", sanitizeRedirectTarget("relative/path"))
    }

    // -- the full round trip --------------------------------------------------------------------

    @Test
    fun `a full login round trip validates the id token and maps its claims`() = runBlocking {
        withFakeKeycloak { keycloak, oidc ->
            val redirectUrl = Url(oidc.authorizationRedirect("/requirements/modules"))
            assertEquals("S256", redirectUrl.parameters["code_challenge_method"])
            keycloak.currentNonce = redirectUrl.parameters["nonce"]
            val state = requireNotNull(redirectUrl.parameters["state"])
            val challenge = requireNotNull(redirectUrl.parameters["code_challenge"])

            val result = oidc.completeLogin(code = "test-authorization-code", state = state)

            val success = assertIs<OidcLoginResult.Success>(result, "expected a successful login: $result")
            assertEquals("/requirements/modules", success.redirectTarget)

            val session = success.session
            assertEquals("user-42", session.sub)
            assertEquals("ada.lovelace", session.username)
            assertEquals("Ada Lovelace", session.name)
            assertEquals("ada@example.com", session.email)
            assertEquals(setOf(Role.USER, Role.ACCESS_MANAGER), session.roles)
            assertEquals(listOf("/SEC/Thermal", "/SEC/Avionics"), session.groups)
            assertTrue(session.csrfToken.isNotBlank())

            // Proves the callback actually sent the code_verifier this test never saw directly —
            // the fake token endpoint only accepts a grant whose SHA-256 matches the challenge above.
            assertEquals(challenge, keycloak.lastAcceptedChallenge)
        }
    }

    @Test
    fun `refresh re-reads roles and groups from the newly issued id token`() = runBlocking {
        withFakeKeycloak { keycloak, oidc ->
            val redirectUrl = Url(oidc.authorizationRedirect(null))
            keycloak.currentNonce = redirectUrl.parameters["nonce"]
            val original = (oidc.completeLogin("code", redirectUrl.parameters["state"]!!) as OidcLoginResult.Success).session

            keycloak.nextRoles = setOf(Role.USER)
            keycloak.nextGroups = listOf("/SEC/Thermal")
            val refreshed = oidc.refresh(original)

            assertEquals(setOf(Role.USER), refreshed?.roles)
            assertEquals(listOf("/SEC/Thermal"), refreshed?.groups)
        }
    }

    // -- rejections -----------------------------------------------------------------------------

    @Test
    fun `an unrecognised state is rejected without ever calling the token endpoint`() = runBlocking {
        withFakeKeycloak { keycloak, oidc ->
            val result = oidc.completeLogin("code", "a-state-nobody-issued")

            assertIs<OidcLoginResult.Failed>(result)
            assertEquals(0, keycloak.tokenRequests)
        }
    }

    @Test
    fun `a signature from a key not in the JWKS is rejected`() = runBlocking {
        withFakeKeycloak(signWithAnUntrustedKey = true) { keycloak, oidc ->
            val redirectUrl = Url(oidc.authorizationRedirect(null))
            keycloak.currentNonce = redirectUrl.parameters["nonce"]

            val result = oidc.completeLogin("code", redirectUrl.parameters["state"]!!)

            assertIs<OidcLoginResult.Failed>(result)
        }
    }

    @Test
    fun `an id token for the wrong audience is rejected`() = runBlocking {
        withFakeKeycloak(audienceOverride = "some-other-client") { keycloak, oidc ->
            val redirectUrl = Url(oidc.authorizationRedirect(null))
            keycloak.currentNonce = redirectUrl.parameters["nonce"]

            val result = oidc.completeLogin("code", redirectUrl.parameters["state"]!!)

            assertIs<OidcLoginResult.Failed>(result)
        }
    }

    @Test
    fun `an already-expired id token is rejected`() = runBlocking {
        withFakeKeycloak(expiresInThePast = true) { keycloak, oidc ->
            val redirectUrl = Url(oidc.authorizationRedirect(null))
            keycloak.currentNonce = redirectUrl.parameters["nonce"]

            val result = oidc.completeLogin("code", redirectUrl.parameters["state"]!!)

            assertIs<OidcLoginResult.Failed>(result)
        }
    }

    // -- DOORS push access token (ADR 0020) ------------------------------------------------------

    @Test
    fun `a push access token with the right azp resolves to a principal carrying its claims`() = runBlocking {
        withFakeKeycloak { keycloak, oidc ->
            val principal = oidc.validatePushAccessToken(keycloak.signedAccessToken())

            assertEquals("push-account-1", principal.sub)
            assertEquals("svc-doors-push", principal.username)
            assertEquals(emptySet(), principal.roles)
            assertEquals(listOf("/SEC/Importers"), principal.groups)
            assertEquals("", principal.csrfToken)
        }
    }

    @Test
    fun `a push access token minted for the browser client is rejected, not just any client`() = runBlocking {
        withFakeKeycloak { keycloak, oidc ->
            assertFailsWith<IllegalArgumentException> {
                oidc.validatePushAccessToken(keycloak.signedAccessToken(azp = "sec-backend"))
            }
        }
    }

    @Test
    fun `a push access token with no azp claim at all is rejected`() = runBlocking {
        withFakeKeycloak { keycloak, oidc ->
            assertFailsWith<IllegalArgumentException> {
                oidc.validatePushAccessToken(keycloak.signedAccessToken(azp = null))
            }
        }
    }

    @Test
    fun `a push access token signed by a key not in the JWKS is rejected`() = runBlocking {
        withFakeKeycloak(signWithAnUntrustedKey = true) { keycloak, oidc ->
            assertFails { oidc.validatePushAccessToken(keycloak.signedAccessToken()) }
        }
    }

    @Test
    fun `an already-expired push access token is rejected`() = runBlocking {
        withFakeKeycloak(expiresInThePast = true) { keycloak, oidc ->
            assertFails { oidc.validatePushAccessToken(keycloak.signedAccessToken()) }
        }
    }

    @Test
    fun `validatePushAccessToken refuses to run at all when no push client is configured`() = runBlocking {
        val unconfigured = Oidc(
            AuthSettings(issuer = "", clientId = "sec-backend", clientSecret = "x", callbackUrl = "http://x"),
            HttpClient(OkHttp),
        )

        assertFailsWith<DoorsPushNotConfiguredException> {
            unconfigured.validatePushAccessToken("irrelevant-token")
        }
    }

    // -- harness ----------------------------------------------------------------------------------

    // FakeKeycloak itself lives in AuthTestSupport.kt (same package) — AuthGuardTest needs it too,
    // for the HTTP-level proof that a wrong-azp push token is rejected the same way at the routing
    // layer that this file already proves it is at the Oidc.validatePushAccessToken layer.
    private suspend fun withFakeKeycloak(
        signWithAnUntrustedKey: Boolean = false,
        audienceOverride: String? = null,
        expiresInThePast: Boolean = false,
        block: suspend (FakeKeycloak, Oidc) -> Unit,
    ) {
        val keycloak = FakeKeycloak(signWithAnUntrustedKey, audienceOverride, expiresInThePast)
        keycloak.start()
        val client = HttpClient(OkHttp)
        try {
            block(keycloak, Oidc(keycloak.authSettings(), client))
        } finally {
            client.close()
            keycloak.stop()
        }
    }
}
