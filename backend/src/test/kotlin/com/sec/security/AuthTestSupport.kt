package com.sec.security

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Duration

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
