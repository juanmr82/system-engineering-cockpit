package com.sec.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sec.config.AuthSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.ApplicationTestBuilder
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

/**
 * Installing the route-tree guard (`security/Session.kt`) means every existing HTTP-surface test
 * now needs a session to reach anything but `/health`, `/ready` and `/auth/login|callback` — this
 * is that in one place, so fixing a test is "attach [authenticatedClient]", not "hand-roll a
 * cookie" at every call site.
 */
public val TEST_PRINCIPAL: SecPrincipal = SecPrincipal(
    sub = "test-sub",
    username = "test.user",
    name = "Test User",
    email = "test.user@example.com",
    roles = setOf(Role.USER),
    groups = listOf("/SEC/Test"),
    csrfToken = "test-csrf-token",
)

/**
 * A [SessionStorage][io.ktor.server.sessions.SessionStorage] pre-seeded with one authenticated
 * session, and the [HttpClient] within [this] `testApplication` block that presents it — pass the
 * storage to `configureApp(sessionStorage = ...)` so the app under test and this client agree on
 * where the session lives, then use the returned client instead of the ambient `client`.
 */
public fun ApplicationTestBuilder.authenticatedClient(
    sessionStorage: SessionStorageMemory,
    principal: SecPrincipal = TEST_PRINCIPAL,
): HttpClient {
    val sessionId = generateOpaqueToken()
    val session = UserSession(
        sub = principal.sub,
        username = principal.username,
        name = principal.name,
        email = principal.email,
        roles = principal.roles,
        groups = principal.groups,
        csrfToken = principal.csrfToken,
        accessToken = "test-access-token",
        refreshToken = null,
        idToken = "test-id-token",
        // Far enough out that the session validator's refresh-on-near-expiry branch never fires
        // against a fake Keycloak these tests do not stand up.
        accessTokenExpiresAtEpochMs = System.currentTimeMillis() + Duration.ofHours(1).toMillis(),
    )
    // The default session serializer round-trips through kotlinx.serialization the same way the
    // real cookie plugin does, so this pins the same string SessionTrackerById would store.
    runBlocking { sessionStorage.write(sessionId, sessionJsonFormat.encodeToString(UserSession.serializer(), session)) }

    return createClient {
        defaultRequest {
            header(HttpHeaders.Cookie, "${SessionNames.COOKIE}=$sessionId")
            header(SessionNames.CSRF_HEADER, principal.csrfToken)
        }
    }
}

private val sessionJsonFormat = Json { ignoreUnknownKeys = true }

/**
 * A minimal, real Keycloak stand-in — discovery, JWKS, a token endpoint (PKCE-checked, for
 * [OidcFlowTest]'s login round trip), and an [signedAccessToken] minter for a DOORS push token
 * (ADR 0020) — shared by [OidcFlowTest] (unit-level: [Oidc.validatePushAccessToken] directly) and
 * `AuthGuardTest` (HTTP-level: the same rejection through the real routing tree). Runs on an
 * actual loopback port, not a [io.ktor.client.engine.mock.MockEngine], because `jwks-rsa`'s
 * `JwkProviderBuilder` fetches the JWKS document with its own `java.net.URL` connection, entirely
 * outside whatever [HttpClient] the [Oidc] under test was handed.
 */
public class FakeKeycloak(
    private val signWithAnUntrustedKey: Boolean = false,
    private val audienceOverride: String? = null,
    private val expiresInThePast: Boolean = false,
) {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val untrustedKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    public var nextRoles: Set<String> = setOf(Role.USER, Role.ACCESS_MANAGER)
    public var nextGroups: List<String> = listOf("/SEC/Thermal", "/SEC/Avionics")
    public var currentNonce: String? = null
    public var tokenRequests: Int = 0
    public var lastAcceptedChallenge: String? = null

    private var boundPort = 0
    private lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>

    public fun start() {
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

    public fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 200)
    }

    public fun authSettings(): AuthSettings = AuthSettings(
        issuer = "http://localhost:$boundPort/realms/test",
        clientId = "sec-backend",
        clientSecret = "test-secret",
        callbackUrl = "http://localhost:9999/api/v1/auth/callback",
        doorsPushClientId = "sec-doors-push",
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

    /** A DOORS-push-shaped access token (ADR 0020) — no `nonce`, `azp` instead of `aud`. */
    public fun signedAccessToken(azp: String? = "sec-doors-push"): String {
        val now = Instant.now()
        val exp = if (expiresInThePast) now.minus(Duration.ofHours(1)) else now.plus(Duration.ofMinutes(5))
        val signingKeyPair = if (signWithAnUntrustedKey) untrustedKeyPair else keyPair
        val builder = JWT.create()
            .withKeyId("test-key")
            .withIssuer("http://localhost:$boundPort/realms/test")
            .withSubject("push-account-1")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(exp))
            .withClaim("preferred_username", "svc-doors-push")
            .withClaim("realm_access", mapOf("roles" to emptyList<String>()))
            .withClaim("groups", listOf("/SEC/Importers"))
        if (azp != null) {
            builder.withClaim("azp", azp)
        }
        return builder.sign(Algorithm.RSA256(signingKeyPair.public as RSAPublicKey, signingKeyPair.private as RSAPrivateKey))
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
