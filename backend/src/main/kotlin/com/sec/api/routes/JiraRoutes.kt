package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.ProblemType
import com.sec.api.dto.JiraHealthDto
import com.sec.api.respondProblem
import com.sec.config.JiraSettings
import com.sec.source.jira.JiraFailure
import com.sec.source.jira.JiraHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val logger = KotlinLogging.logger {}

/**
 * The JIRA integration's HTTP surface.
 *
 * **`/health` is the one route here that answers when JIRA is not configured**, and it has to be:
 * reporting that state is its whole purpose, so 503-ing it would leave the settings page to infer
 * "not configured" from a failure status — the one reading it must not have to guess. Every other
 * route under `/api/v1/jira` goes through [requireConfigured] and answers 503 with a problem type
 * the frontend can branch on.
 */
public fun Route.jiraRoutes(settings: JiraSettings, client: JiraHttpClient?) {
    get(ApiPaths.JIRA_HEALTH) {
        if (client == null || !settings.isConfigured) {
            call.respond(
                JiraHealthDto(
                    configured = false,
                    reachable = false,
                    message = "JIRA is not configured on this server. Set the host and an access " +
                        "token, then try again.",
                    host = settings.host,
                ),
            )
            return@get
        }

        // The same call the import's first phase makes, on purpose: a connection test that
        // exercises a different path from the import is one that can pass while the import fails.
        val result = client.myself()

        result.fold(
            onSuccess = { me ->
                call.respond(
                    JiraHealthDto(
                        configured = true,
                        reachable = true,
                        user = me.displayName.ifBlank { me.name },
                        message = "Connected to JIRA as ${me.displayName.ifBlank { me.name }}.",
                        host = settings.host,
                    ),
                )
            },
            onFailure = { cause ->
                logger.warn(cause) { "JIRA connection test failed" }
                call.respond(
                    JiraHealthDto(
                        configured = true,
                        reachable = false,
                        message = humanReason(cause),
                        host = settings.host,
                    ),
                )
            },
        )
    }
}

/**
 * Guards every JIRA route except `/health`.
 *
 * Returns true when the caller may proceed. On the false path it has already answered, so a
 * handler reads `if (!requireConfigured(...)) return@get`.
 */
internal suspend fun ApplicationCall.requireConfigured(
    settings: JiraSettings,
    client: JiraHttpClient?,
): Boolean {
    if (settings.isConfigured && client != null) return true

    respondProblem(
        HttpStatusCode.ServiceUnavailable,
        "JIRA is not configured",
        "This server has no JIRA host or access token configured, so JIRA data is unavailable.",
        ProblemType.JIRA_NOT_CONFIGURED,
    )
    return false
}

/**
 * A [JiraFailure] as one sentence a person can act on.
 *
 * `JiraFailure.message` is already written for people, which is the point of that hierarchy — but
 * this is the boundary where that stops being an internal invariant and becomes wire content, so
 * the mapping is explicit rather than an `it.message` that would leak whatever a future subclass
 * happens to say.
 */
private fun humanReason(cause: Throwable): String = when (cause) {
    is JiraFailure.Unauthorized ->
        "JIRA rejected the access token. It may have expired or been revoked."
    is JiraFailure.Forbidden ->
        "The access token is valid, but its user is not allowed to read this JIRA instance."
    is JiraFailure.MalformedResponse ->
        "The configured address answered, but not like a JIRA server. Check the host, including " +
            "any context path such as /jira."
    is JiraFailure.BadRequest -> cause.jiraMessages.firstOrNull()
        ?: "JIRA rejected the request."
    is JiraFailure.NotConfigured ->
        "JIRA is not configured on this server."
    else ->
        "JIRA did not answer. Check that the host is reachable from this server."
}
