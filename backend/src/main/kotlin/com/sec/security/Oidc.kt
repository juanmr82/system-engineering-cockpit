package com.sec.security

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sec.config.AuthSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}
private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Keycloak did not answer the discovery document (startup, or first login since). Not a 500: it
 * maps to a `503` problem detail so a caller reads "try again shortly" rather than an internal
 * error (`docs/features/access-control.md` §12).
 */
public class KeycloakUnavailableException(cause: Throwable? = null) :
    Exception("Keycloak is not reachable right now", cause)

private data class OidcDiscovery(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val endSessionEndpoint: String?,
)

@Serializable
private data class OidcDiscoveryDocument(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("jwks_uri") val jwksUri: String,
    @SerialName("end_session_endpoint") val endSessionEndpoint: String? = null,
)

@Serializable
private data class OidcTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0L,
)

@Serializable
private data class OidcErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/** One PKCE + OIDC attempt in flight, keyed by the `state` handed to Keycloak. Single-use. */
private data class PendingAuthorization(
    val codeVerifier: String,
    val nonce: String,
    val redirectTarget: String,
    val createdAt: Instant,
)

private data class OidcIdentity(
    val sub: String,
    val username: String,
    val name: String,
    val email: String,
    val roles: Set<String>,
    val groups: List<String>,
)

public sealed class OidcLoginResult {
    public data class Success(public val session: UserSession, public val redirectTarget: String) : OidcLoginResult()

    /** [reason] is a sentence safe to put on the wire — never an exception message (R5). */
    public data class Failed(public val reason: String) : OidcLoginResult()
}

/**
 * The OIDC client half of ADR 0017: discovery, the Authorization Code flow with PKCE, JWKS-backed
 * ID token validation, and server-side refresh.
 *
 * Hand-rolled rather than driven through `ktor-server-auth`'s `oauth {}` provider, which is shaped
 * for a single static `OAuthServerSettings` (or a `providerLookup` invoked independently on the
 * login request and the callback request, with no supported way to correlate the two beyond its
 * own `state` nonce) — awkward for a per-attempt PKCE `code_verifier` that must survive exactly
 * one round trip. `ktor-server-auth-jwt` is still the dependency that brings JWKS validation
 * in (`com.auth0:java-jwt`, `com.auth0:jwks-rsa`, CLAUDE.md §4); it is used directly here instead
 * of through that artifact's `jwt {}` route-authentication DSL, which validates a bearer token on
 * every request rather than a one-time `id_token` at the callback.
 *
 * One instance for the process lifetime, holding the discovery document and the JWKS provider
 * (which does its own caching and rate limiting) once fetched.
 */
public class Oidc(
    private val settings: AuthSettings,
    private val client: HttpClient,
) {
    private val discoveryRef = AtomicReference<OidcDiscovery?>(null)
    private val jwkProviderRef = AtomicReference<JwkProvider?>(null)
    private val pending = ConcurrentHashMap<String, PendingAuthorization>()

    /**
     * Best-effort at startup. Keycloak being unreachable must not fail the backend's own startup —
     * `/ready` deliberately does not depend on it either (`docs/KEYCLOAK_SETUP.md` §7) — so a
     * failure here is only logged, and the next call to [authorizationRedirect] retries.
     */
    public suspend fun warmUp() {
        runCatching { ensureDiscovery() }
            .onFailure { logger.warn(it) { "Could not reach Keycloak (${settings.issuer}) at startup; will retry on first sign-in" } }
    }

    /** The `/auth/login` redirect target, and where the `code_verifier`/`nonce`/`state` are minted. */
    public suspend fun authorizationRedirect(redirectTarget: String?): String {
        val discovery = discoveryOrNull() ?: throw KeycloakUnavailableException()
        sweepExpired()

        val verifier = generateOpaqueToken(48)
        val nonce = generateOpaqueToken(24)
        val state = generateOpaqueToken(24)
        pending[state] = PendingAuthorization(verifier, nonce, sanitizeRedirectTarget(redirectTarget), Instant.now())

        val url = URLBuilder()
        url.takeFrom(discovery.authorizationEndpoint)
        url.parameters.apply {
            append("client_id", settings.clientId)
            append("redirect_uri", settings.callbackUrl)
            append("response_type", "code")
            append("scope", "openid profile email")
            append("state", state)
            append("nonce", nonce)
            append("code_challenge", codeChallengeS256(verifier))
            append("code_challenge_method", "S256")
        }
        return url.buildString()
    }

    /** `/auth/callback`. [state] is consumed here — a replay of the same callback fails cleanly. */
    public suspend fun completeLogin(code: String, state: String): OidcLoginResult {
        val discovery = discoveryOrNull() ?: throw KeycloakUnavailableException()
        sweepExpired()
        val attempt = pending.remove(state)
            ?: return OidcLoginResult.Failed("This sign-in attempt is unrecognised or has expired. Try signing in again.")

        val tokens = requestTokens(
            discovery.tokenEndpoint,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to settings.callbackUrl,
                "code_verifier" to attempt.codeVerifier,
            ),
        ).getOrElse { cause ->
            logger.warn(cause) { "OIDC token exchange failed" }
            return OidcLoginResult.Failed("Keycloak rejected the sign-in attempt.")
        }
        val idToken = tokens.idToken
            ?: return OidcLoginResult.Failed("Keycloak did not return an identity token.")

        val identity = try {
            validateIdToken(idToken, expectedNonce = attempt.nonce)
        } catch (cause: Exception) {
            logger.warn(cause) { "ID token validation failed at callback" }
            return OidcLoginResult.Failed("The identity token could not be validated.")
        }

        val session = UserSession(
            sub = identity.sub,
            username = identity.username,
            name = identity.name,
            email = identity.email,
            roles = identity.roles,
            groups = identity.groups,
            csrfToken = generateOpaqueToken(),
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            idToken = idToken,
            accessTokenExpiresAtEpochMs = System.currentTimeMillis() + tokens.expiresIn * 1000,
        )
        return OidcLoginResult.Success(session, attempt.redirectTarget)
    }

    /**
     * Server-side, on use, with a small skew — called from the session validator when
     * [UserSession.accessTokenExpiresAtEpochMs] is close. Re-validates the freshly returned
     * `id_token`, so [UserSession.roles] / [UserSession.groups] are re-read on every refresh, not
     * only at login (`docs/features/access-control.md` §11). `null` means the session is dead: no
     * refresh token, Keycloak refused it, or the refreshed token failed validation — the caller
     * clears the session and the next request is a `401`, never a silent widening.
     */
    public suspend fun refresh(session: UserSession): UserSession? {
        val refreshToken = session.refreshToken ?: return null
        val discovery = discoveryOrNull() ?: return null

        val tokens = requestTokens(
            discovery.tokenEndpoint,
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        ).getOrElse { cause ->
            logger.warn(cause) { "Token refresh failed for ${session.username}" }
            return null
        }
        val idToken = tokens.idToken ?: session.idToken
        val identity = try {
            // No nonce to check: this is not a fresh authentication, it is the same one continuing.
            validateIdToken(idToken, expectedNonce = null)
        } catch (cause: Exception) {
            logger.warn(cause) { "Refreshed ID token failed validation for ${session.username}" }
            return null
        }

        return session.copy(
            roles = identity.roles,
            groups = identity.groups,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: session.refreshToken,
            idToken = idToken,
            accessTokenExpiresAtEpochMs = System.currentTimeMillis() + tokens.expiresIn * 1000,
        )
    }

    /** RP-initiated logout target, or `null` when Keycloak has not been reachable at all yet. */
    public suspend fun endSessionUrl(session: UserSession): String? {
        val endpoint = discoveryOrNull()?.endSessionEndpoint ?: return null
        val url = URLBuilder()
        url.takeFrom(endpoint)
        url.parameters.apply {
            append("id_token_hint", session.idToken)
            append("post_logout_redirect_uri", frontendUrl("/"))
        }
        return url.buildString()
    }

    /**
     * [path] (always leading-`/`) resolved against where the browser should land — this backend's
     * own origin when [AuthSettings.frontendBaseUrl] is blank (the packaged deployment, same
     * origin as the API), or that origin in development, where `ng serve` is a different one
     * Keycloak was never told to redirect to directly.
     */
    public fun frontendUrl(path: String): String = "${settings.frontendBaseUrl}$path"

    // -- discovery ------------------------------------------------------------------------------

    private suspend fun discoveryOrNull(): OidcDiscovery? = runCatching { ensureDiscovery() }.getOrNull()

    private suspend fun ensureDiscovery(): OidcDiscovery {
        discoveryRef.get()?.let { return it }

        val response = client.get("${settings.issuer}/.well-known/openid-configuration")
        check(response.status.isSuccess()) { "Discovery document request failed: HTTP ${response.status}" }
        val doc = JSON.decodeFromString(OidcDiscoveryDocument.serializer(), response.bodyAsText())
        val discovery = OidcDiscovery(
            issuer = doc.issuer,
            authorizationEndpoint = doc.authorizationEndpoint,
            tokenEndpoint = doc.tokenEndpoint,
            jwksUri = doc.jwksUri,
            endSessionEndpoint = doc.endSessionEndpoint,
        )
        jwkProviderRef.set(withContext(Dispatchers.IO) { JwkProviderBuilder(URI(discovery.jwksUri).toURL()).build() })
        discoveryRef.set(discovery)
        return discovery
    }

    // -- token endpoint ---------------------------------------------------------------------------

    /**
     * `client_secret_post` (the secret as a form field) rather than HTTP Basic — one fewer encoding
     * step, and Keycloak's confidential-client default accepts either.
     */
    private suspend fun requestTokens(tokenEndpoint: String, grantParams: Map<String, String>): Result<OidcTokenResponse> =
        runCatching {
            val body = ParametersBuilder().apply {
                grantParams.forEach { (key, value) -> append(key, value) }
                append("client_id", settings.clientId)
                append("client_secret", settings.clientSecret)
            }.build().formUrlEncode()

            val response = client.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val problem = runCatching { JSON.decodeFromString(OidcErrorResponse.serializer(), text) }.getOrNull()
                error(problem?.errorDescription ?: problem?.error ?: "HTTP ${response.status}")
            }
            JSON.decodeFromString(OidcTokenResponse.serializer(), text)
        }

    // -- ID token validation ----------------------------------------------------------------------

    /**
     * Signature (via JWKS, matched by `kid`), `iss`, `aud`, `exp`, `nbf` — the last two are
     * standard claims `java-jwt`'s [com.auth0.jwt.interfaces.JWTVerifier.verify] checks whenever
     * present, no extra configuration needed. `azp`, when the token carries one, must also name
     * this client. [expectedNonce] is `null` on a refresh, where there is no fresh `nonce` to
     * compare against — the token is still fully signature/`iss`/`aud`/`exp`/`nbf` validated.
     */
    private suspend fun validateIdToken(idToken: String, expectedNonce: String?): OidcIdentity {
        val discovery = discoveryRef.get() ?: throw KeycloakUnavailableException()
        val jwkProvider = jwkProviderRef.get() ?: throw KeycloakUnavailableException()

        val keyId = JWT.decode(idToken).keyId ?: error("ID token has no key id")
        val jwk = withContext(Dispatchers.IO) { jwkProvider.get(keyId) }
        val publicKey = jwk.publicKey as? RSAPublicKey ?: error("Unsupported JWK key type")
        val verifier = JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(discovery.issuer)
            .withAudience(settings.clientId)
            .build()
        val decoded = verifier.verify(idToken)

        if (expectedNonce != null) {
            require(decoded.getClaim("nonce").asString() == expectedNonce) { "nonce mismatch" }
        }
        decoded.getClaim("azp").asString()?.let { azp ->
            require(azp == settings.clientId) { "azp mismatch" }
        }

        @Suppress("UNCHECKED_CAST")
        val roles = (decoded.getClaim("realm_access").asMap()?.get("roles") as? List<*>)
            ?.filterIsInstance<String>()?.toSet().orEmpty()
        val groups = decoded.getClaim("groups").asList(String::class.java).orEmpty()
        val sub = decoded.subject ?: error("ID token has no sub")

        return OidcIdentity(
            sub = sub,
            username = decoded.getClaim("preferred_username").asString() ?: sub,
            name = decoded.getClaim("name").asString().orEmpty(),
            email = decoded.getClaim("email").asString().orEmpty(),
            roles = roles,
            groups = groups,
        )
    }

    // -- pending PKCE attempts ----------------------------------------------------------------------

    private fun sweepExpired() {
        val cutoff = Instant.now().minus(PENDING_TTL)
        pending.entries.removeIf { it.value.createdAt.isBefore(cutoff) }
    }

    private companion object {
        val PENDING_TTL: Duration = Duration.ofMinutes(10)
    }
}

/**
 * A same-origin, single-leading-slash path, never anything an attacker's `?redirect=` could turn
 * into an open redirect: `//evil.com` (protocol-relative) and `/\evil.com` (a backslash some
 * browsers still treat as a path separator) both fall back to the root.
 */
internal fun sanitizeRedirectTarget(raw: String?): String {
    val candidate = raw?.trim().orEmpty()
    return if (candidate.startsWith("/") && !candidate.startsWith("//") && '\\' !in candidate) candidate else "/"
}

internal fun codeChallengeS256(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
