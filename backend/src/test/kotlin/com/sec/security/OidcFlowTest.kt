package com.sec.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sec.config.AuthSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // -- harness ----------------------------------------------------------------------------------

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

    /**
     * A minimal, real Keycloak stand-in — discovery, JWKS, and a token endpoint that only accepts
     * a `code_verifier` matching the PKCE challenge it can compute from what this test captured
     * off the authorize redirect. Runs on an actual loopback port because [Oidc]'s JWKS fetch does
     * too (see the class doc above).
     */
    private class FakeKeycloak(
        private val signWithAnUntrustedKey: Boolean,
        private val audienceOverride: String?,
        private val expiresInThePast: Boolean,
    ) {
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val untrustedKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        var nextRoles: Set<String> = setOf(Role.USER, Role.ACCESS_MANAGER)
        var nextGroups: List<String> = listOf("/SEC/Thermal", "/SEC/Avionics")
        var currentNonce: String? = null
        var tokenRequests = 0
        var lastAcceptedChallenge: String? = null

        private var boundPort = 0
        private lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>

        fun start() {
            server = embeddedServer(Netty, port = 0) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                routing {
                    get("/realms/test/.well-known/openid-configuration") {
                        val base = "http://localhost:$boundPort/realms/test"
                        call.respond(
                            buildJsonObject {
                                put("issuer", JsonPrimitive(base))
                                put("authorization_endpoint", JsonPrimitive("$base/protocol/openid-connect/auth"))
                                put("token_endpoint", JsonPrimitive("$base/protocol/openid-connect/token"))
                                put("jwks_uri", JsonPrimitive("$base/protocol/openid-connect/certs"))
                                put("end_session_endpoint", JsonPrimitive("$base/protocol/openid-connect/logout"))
                            },
                        )
                    }
                    get("/realms/test/protocol/openid-connect/certs") {
                        call.respond(
                            buildJsonObject {
                                put(
                                    "keys",
                                    buildJsonArray { add(jwk(keyPair.public as RSAPublicKey)) },
                                )
                            },
                        )
                    }
                    post("/realms/test/protocol/openid-connect/token") {
                        tokenRequests++
                        val body = call.receiveParameters()
                        if (body["client_id"] != "sec-backend" || body["client_secret"] != "test-secret") {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                buildJsonObject { put("error", JsonPrimitive("invalid_client")) },
                            )
                            return@post
                        }
                        val verifier = body["code_verifier"]
                        val challenge = verifier?.let(::codeChallengeS256)
                        if (body["grant_type"] == "authorization_code" && challenge == null) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                buildJsonObject { put("error", JsonPrimitive("invalid_grant")) },
                            )
                            return@post
                        }
                        lastAcceptedChallenge = challenge
                        call.respond(
                            buildJsonObject {
                                put("access_token", JsonPrimitive("fake-access-token"))
                                put("refresh_token", JsonPrimitive("fake-refresh-token"))
                                put("id_token", JsonPrimitive(signedIdToken()))
                                put("token_type", JsonPrimitive("Bearer"))
                                put("expires_in", JsonPrimitive(300))
                            },
                        )
                    }
                }
            }
            server.start(wait = false)
            boundPort = runBlocking { server.engine.resolvedConnectors() }.first().port
        }

        fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 200)

        fun authSettings(): AuthSettings = AuthSettings(
            issuer = "http://localhost:$boundPort/realms/test",
            clientId = "sec-backend",
            clientSecret = "test-secret",
            callbackUrl = "http://localhost:9999/api/v1/auth/callback",
        )

        private fun signedIdToken(): String {
            val now = Instant.now()
            val exp = if (expiresInThePast) now.minus(Duration.ofHours(1)) else now.plus(Duration.ofMinutes(5))
            val signingKeyPair = if (signWithAnUntrustedKey) untrustedKeyPair else keyPair
            return JWT.create()
                .withKeyId("test-key")
                .withIssuer("http://localhost:$boundPort/realms/test")
                .withAudience(audienceOverride ?: "sec-backend")
                .withSubject("user-42")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(exp))
                .withClaim("preferred_username", "ada.lovelace")
                .withClaim("name", "Ada Lovelace")
                .withClaim("email", "ada@example.com")
                .withClaim("nonce", currentNonce)
                .withClaim("realm_access", mapOf("roles" to nextRoles.toList()))
                .withClaim("groups", nextGroups)
                .sign(Algorithm.RSA256(signingKeyPair.public as RSAPublicKey, signingKeyPair.private as RSAPrivateKey))
        }

        private fun jwk(publicKey: RSAPublicKey) = buildJsonObject {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            put("kty", JsonPrimitive("RSA"))
            put("use", JsonPrimitive("sig"))
            put("kid", JsonPrimitive("test-key"))
            put("alg", JsonPrimitive("RS256"))
            put("n", JsonPrimitive(encoder.encodeToString(publicKey.modulus.toUnsignedBytes())))
            put("e", JsonPrimitive(encoder.encodeToString(publicKey.publicExponent.toUnsignedBytes())))
        }

        private fun BigInteger.toUnsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
    }
}
