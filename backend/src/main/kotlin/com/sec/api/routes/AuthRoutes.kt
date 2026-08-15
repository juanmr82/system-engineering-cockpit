package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.dto.AuthMeDto
import com.sec.api.dto.LogoutResponseDto
import com.sec.api.respondProblem
import com.sec.security.Oidc
import com.sec.security.OidcLoginResult
import com.sec.security.SecPrincipal
import com.sec.security.UserSession
import com.sec.security.requireSecSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * The session (ADR 0017). [ApiPaths.AUTH_LOGIN] and [ApiPaths.AUTH_CALLBACK] are the two routes
 * reachable with no session at all — plain, unauthenticated routes, not wrapped the way every
 * other feature route is in `Routes.kt`. [ApiPaths.AUTH_ME] and [ApiPaths.AUTH_LOGOUT] need an
 * existing one, so they sit in their own nested [requireSecSession] here rather than relying on
 * the route tree's outer one — this file is where the "session required" line is actually drawn,
 * and it should be readable in one place.
 */
public fun Route.authRoutes(oidc: Oidc) {
    get(ApiPaths.AUTH_LOGIN) {
        val redirectTarget = call.request.queryParameters["redirect"]
        call.respondRedirect(oidc.authorizationRedirect(redirectTarget))
    }

    get(ApiPaths.AUTH_CALLBACK) {
        val params = call.request.queryParameters
        val error = params["error"]
        if (error != null) {
            call.respondProblem(
                HttpStatusCode.BadGateway,
                "Sign-in failed",
                params["error_description"] ?: "Keycloak reported an error during sign-in.",
            )
            return@get
        }

        val code = params["code"]
        val state = params["state"]
        if (code == null || state == null) {
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "Sign-in failed",
                "The sign-in callback was incomplete. Try signing in again.",
            )
            return@get
        }

        when (val result = oidc.completeLogin(code, state)) {
            is OidcLoginResult.Failed ->
                call.respondProblem(HttpStatusCode.BadRequest, "Sign-in failed", result.reason)

            is OidcLoginResult.Success -> {
                call.sessions.set(result.session)
                call.respondRedirect(oidc.frontendUrl(result.redirectTarget))
            }
        }
    }

    requireSecSession {
        // The frontend's only source of identity (ADR 0017). Never cached by the browser and
        // re-fetched on every full page load — the frontend enforces that, this just answers.
        get(ApiPaths.AUTH_ME) {
            val principal = call.principal<SecPrincipal>()
                ?: error("${ApiPaths.AUTH_ME} ran without a principal despite the session guard")

            call.respond(
                AuthMeDto(
                    userId = principal.sub,
                    displayName = principal.name.ifBlank { principal.username },
                    email = principal.email,
                    roles = principal.roles.sorted(),
                    groups = principal.groups,
                    csrfToken = principal.csrfToken,
                ),
            )
        }

        // Drops the local session first, so a client that never follows the returned URL is still
        // signed out here. RP-initiated logout (ending the Keycloak SSO session too) is what the
        // URL is for; a backend with no reachable Keycloak still signs the caller out locally.
        post(ApiPaths.AUTH_LOGOUT) {
            val session = call.sessions.get<UserSession>()
            call.sessions.clear<UserSession>()
            call.respond(LogoutResponseDto(session?.let { oidc.endSessionUrl(it) } ?: oidc.frontendUrl("/")))
        }
    }
}
