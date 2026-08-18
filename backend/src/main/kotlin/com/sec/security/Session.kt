package com.sec.security

import com.sec.api.respondProblem
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.SessionStorage
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/**
 * The cookie, the CSRF header, and the session-authentication provider — declared once, the same
 * discipline `ApiPaths.kt` and `domain/GraphNames.kt` apply to their own names (ADR 0010, ADR 0017
 * §5).
 */
public object SessionNames {
    public const val COOKIE: String = "SEC_SESSION"
    public const val CSRF_HEADER: String = "X-SEC-CSRF"

    /** The name the session-authentication provider is registered and looked up under. */
    public const val PROVIDER: String = "sec-session"
}

/**
 * The bearer-token authentication provider a DOORS push request authenticates through instead of
 * [SessionNames] (ADR 0020) — a second, independent provider, not a variant of the session one:
 * there is no cookie, no CSRF token and no `SecPrincipal.csrfToken` to check on this path. Declared
 * once, beside [SessionNames], for the same reason that object is.
 */
public object PushAuthNames {
    /** The name the DOORS-push bearer-authentication provider is registered and looked up under. */
    public const val PROVIDER: String = "sec-doors-push"
}

/**
 * The full server-side truth for one signed-in user — everything [UserSession.toPrincipal] does
 * *not* expose, plus what it does. Lives only in [SessionStorage] (one process, in memory; ADR
 * 0017 §3 — a restart signs everyone out, and that is accepted, not a bug to fix here).
 *
 * `accessTokenExpiresAt` is a millisecond epoch rather than `java.time.Instant`: kotlinx.serialization
 * has no built-in serializer for JDK time types, and a second dependency (`kotlinx-datetime`) or a
 * hand-written contextual serializer both cost more than a `Long` does (CLAUDE.md §4, "prefer fewer
 * libraries").
 */
@Serializable
public data class UserSession(
    public val sub: String,
    public val username: String,
    public val name: String,
    public val email: String,
    public val roles: Set<String>,
    public val groups: List<String>,
    public val csrfToken: String,
    public val accessToken: String,
    public val refreshToken: String?,
    public val idToken: String,
    public val accessTokenExpiresAtEpochMs: Long,
) {
    public fun toPrincipal(): SecPrincipal =
        SecPrincipal(sub, username, name, email, roles, groups, csrfToken)
}

/**
 * Installs the cookie session exactly as ADR 0017 §11 specifies: `HttpOnly`, `Secure`
 * unconditionally (developing over plain `http://localhost` is exempted by the browser, not by
 * this flag), `SameSite=Lax`, `path=/`, and no `Max-Age` — a browser-session cookie, cleared when
 * the browser closes rather than on a timer this application controls.
 *
 * [storage] is a parameter, not a `SessionStorageMemory()` constructed here, for the same reason
 * every other collaborator in `Application.kt` is: a test substitutes its own and can seed it with
 * an authenticated session without going through a real Keycloak.
 */
public fun Application.installSecSessions(storage: SessionStorage) {
    install(Sessions) {
        cookie<UserSession>(SessionNames.COOKIE, storage) {
            cookie.httpOnly = true
            cookie.secure = true
            cookie.path = "/"
            cookie.extensions["SameSite"] = "Lax"
            cookie.maxAgeInSeconds = null
            serializer = KotlinxSessionSerializer(Json { ignoreUnknownKeys = true })
        }
    }
}

/**
 * An opaque, unguessable token: the session id the cookie carries (via the plugin's own default
 * generator) and, built the same way here, the CSRF double-submit token, the OAuth `state`, the
 * PKCE `code_verifier` and the OIDC `nonce`. 32 bytes is 256 bits, comfortably beyond brute force.
 */
public fun generateOpaqueToken(byteLength: Int = 32): String {
    val bytes = ByteArray(byteLength)
    SECURE_RANDOM.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private val SECURE_RANDOM = SecureRandom()

private val CSRF_EXEMPT_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * The route-tree guard, in one place (`docs/features/access-control.md` §9 "Guarding, once") —
 * every route registered inside [build] requires [SessionNames.PROVIDER] **and** carries the
 * `X-SEC-CSRF` double-submit check on every non-`GET` (ADR 0017 §11: `SameSite=Lax` is necessary
 * but not sufficient). `Routes.kt` wraps every feature route file in this exactly once;
 * `AuthRoutes.kt` wraps its own `/auth/me` and `/auth/logout` in a second, independent call to it,
 * because those two need a session and the other two — `/auth/login`, `/auth/callback` — are what
 * *creates* one and cannot require one to exist yet.
 *
 * The CSRF check is a plain `Call`-phase intercept nested inside the `authenticate` route, which
 * Ktor's own `AuthenticatePhase` runs before (`AuthenticationInterceptors.kt` inserts it right
 * after `ApplicationCallPipeline.Plugins`) — so `call.principal<SecPrincipal>()` is always
 * populated by the time this reads it.
 */
public fun Route.requireSecSession(build: Route.() -> Unit): Route =
    authenticate(SessionNames.PROVIDER) {
        intercept(ApplicationCallPipeline.Call) {
            if (call.request.httpMethod !in CSRF_EXEMPT_METHODS) {
                // No principal means authentication itself already failed and challenged with its
                // own 401 (the `challenge { }` above) — do nothing here, or this would send a
                // second, competing response and turn a missing session into a 403 instead.
                val principal = call.principal<SecPrincipal>() ?: return@intercept
                val header = call.request.header(SessionNames.CSRF_HEADER)
                if (header.isNullOrEmpty() || header != principal.csrfToken) {
                    call.respondProblem(
                        HttpStatusCode.Forbidden,
                        "Missing or invalid CSRF token",
                        "This request could not be verified as coming from the application. Reload the page and try again.",
                    )
                    finish()
                }
            }
        }
        build()
    }
