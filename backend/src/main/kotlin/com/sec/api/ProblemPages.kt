package com.sec.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

private val logger = KotlinLogging.logger {}

// Every failure leaves the service as an RFC 9457 problem detail (CLAUDE.md §5). The rule that
// shapes this file: the client is told what it can act on, and nothing else. Exception messages
// carry internal type names ("Failed to convert request body to class com.sec.api.dto...") and
// JDK decoder text ("Illegal base64 character 21"), so the cause goes to the log — where the
// CallId in the MDC ties it back to the `instance` the client was given — and never to the wire.
public fun Application.configureProblemDetails() {
    install(StatusPages) {
        // Thrown by ContentNegotiation when a request body will not deserialize.
        exception<BadRequestException> { call, cause ->
            logger.debug(cause) { "Rejected a malformed request" }
            call.respondProblem(
                HttpStatusCode.BadRequest,
                "Malformed request",
                "The request could not be read. Check the request body against the API contract.",
            )
        }

        exception<NotFoundException> { call, _ ->
            call.respondProblem(
                HttpStatusCode.NotFound,
                "Not found",
                "The requested resource does not exist.",
            )
        }

        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled failure" }
            call.respondProblem(
                HttpStatusCode.InternalServerError,
                "Internal error",
                "Something went wrong. Quote reference ${call.callId} when reporting this.",
            )
        }

        // Deliberately no status(HttpStatusCode.NotFound) handler: a StatusPages status handler
        // fires for *every* response carrying that status, including the specific "Module not
        // found" a route already wrote, and replaces its body with the generic one. Unmatched
        // paths are handled by the tail-card fallback route below, which cannot do that.
    }
}

// Unmatched paths would otherwise return 404 with an empty body. A tail-card route matches
// anything, and Ktor scores constant and parameter segments above it, so every real route still
// wins — including a method mismatch on a real path, which stays a 405 rather than becoming this.
public fun Route.notFoundFallback() {
    route("{...}") {
        handle {
            call.respondProblem(
                HttpStatusCode.NotFound,
                "Not found",
                "No endpoint matches this path.",
            )
        }
    }
}
