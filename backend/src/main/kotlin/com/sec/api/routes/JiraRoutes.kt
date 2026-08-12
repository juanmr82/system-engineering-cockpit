package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.ProblemType
import com.sec.api.dto.JiraHealthDto
import com.sec.api.dto.JiraProjectSettingsDto
import com.sec.api.dto.JiraProjectSettingsRequest
import com.sec.api.respondProblem
import com.sec.config.JiraSettings
import com.sec.source.jira.JiraFailure
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraJql
import com.sec.source.jira.JiraSettingsStore
import com.sec.security.CurrentUser
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

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
public fun Route.jiraRoutes(
    settings: JiraSettings,
    client: JiraHttpClient?,
    settingsStore: JiraSettingsStore,
    issuesProjection: JiraIssuesProjection,
) {
    /**
     * The Issues table (spec §14.4).
     *
     * Not guarded by [requireConfigured], and that is the point rather than an oversight: these
     * issues are in *our* graph. A deployment that has lost its JIRA credentials, or never had
     * them, can still read everything the last import brought in — and a table that went blank
     * because a token expired would be reporting a connection problem as an absence of data.
     *
     * Every parameter is validated before it reaches a statement. `sort` matters most: it becomes a
     * dynamic property access, so an unknown value is a 400 rather than a silent fall back to the
     * default, which would leave a user looking at a table that ignored them.
     */
    get(ApiPaths.JIRA_ISSUES) {
        val page = call.intParam("page", default = 0, min = 0)
            ?: return@get call.respondBadPaging()
        // Clamped, not rejected: `size` is what the client would like, and the ceiling is the
        // server's business (spec §14.4). A cap that 400s teaches a client to send the cap.
        val size = call.intParam("size", default = JiraIssuesProjection.DEFAULT_SIZE, min = 1)
            ?.coerceAtMost(JiraIssuesProjection.MAX_SIZE)
            ?: return@get call.respondBadPaging()

        val sort = JiraIssuesProjection.SortField.of(call.request.queryParameters["sort"])
            ?: return@get call.respondProblem(
                HttpStatusCode.BadRequest,
                "Cannot sort by that",
                "This table cannot be sorted by the requested column.",
                ProblemType.VALIDATION,
            )
        val direction = JiraIssuesProjection.SortDirection.of(call.request.queryParameters["dir"])
            ?: return@get call.respondProblem(
                HttpStatusCode.BadRequest,
                "Invalid sort direction",
                "Sort direction must be 'asc' or 'desc'.",
                ProblemType.VALIDATION,
            )

        call.respond(
            issuesProjection.listIssues(
                page = page,
                size = size,
                sort = sort,
                direction = direction,
                query = call.request.queryParameters["q"],
                // Repeatable rather than comma-separated: a project key cannot contain a comma, but
                // a splitter that assumes so is one more rule the client has to know.
                projectKeys = call.request.queryParameters.getAll("projectKey"),
            ),
        )
    }

    /**
     * The configured projects, with the query they produce.
     *
     * Not guarded by [requireConfigured]: the project list lives in the graph and is readable and
     * editable whether or not this deployment has a JIRA host, which is what lets an operator set
     * it up in either order. What needs a host is *running* an import, and that is where the 503 is.
     */
    get(ApiPaths.JIRA_SETTINGS) {
        val keys = settingsStore.projectKeys()
        call.respond(
            JiraProjectSettingsDto(projectKeys = keys, jql = JiraJql.preview(keys).getOrNull()),
        )
    }

    put(ApiPaths.JIRA_SETTINGS) {
        val request = call.receive<JiraProjectSettingsRequest>()

        // The same placeholder every other write path uses until the §14.1 authorization seam
        // exists (ADR 0014). One placeholder, in one place, so there is one thing to replace.
        settingsStore.saveProjectKeys(request.projectKeys, updatedBy = CurrentUser.PLACEHOLDER).fold(
            onSuccess = { saved ->
                call.respond(
                    JiraProjectSettingsDto(
                        projectKeys = saved,
                        jql = JiraJql.preview(saved).getOrNull(),
                    ),
                )
            },
            onFailure = { cause ->
                // A 400 and not a 500: every failure this can produce is the caller's, and both
                // carry a sentence naming what was wrong with which key.
                call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "Those project keys cannot be used",
                    humanReason(cause),
                    ProblemType.VALIDATION,
                )
            },
        )
    }

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
 * A whole, non-negative query parameter, or null when it is neither.
 *
 * The same shape `ReviewRoutes` uses, and for the same reason: Community has no query governor
 * (CLAUDE.md §7), so a paging value is validated rather than passed through — a negative `SKIP` is
 * a Cypher error and an unbounded `LIMIT` is the failure the transaction timeout exists to catch.
 */
private fun ApplicationCall.intParam(name: String, default: Int, min: Int): Int? {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: return null
    return if (value < min) null else value
}

// The value is deliberately not echoed: a query string is user input, and reflecting it puts
// whatever was sent into an error page (CLAUDE.md §5).
private suspend fun ApplicationCall.respondBadPaging(): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid paging",
        "page must be zero or more and size at least one.",
        ProblemType.VALIDATION,
    )

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
    is JiraFailure.NoProjectsConfigured ->
        "Choose at least one project. An import is never run across a whole JIRA instance."
    // JIRA's own rule is stricter than this one; the job here is to exclude quotes, spaces and JQL
    // operators, so the message names the keys rather than restating a rule we do not enforce.
    is JiraFailure.InvalidProjectKey ->
        "These are not usable project keys: ${cause.keys.joinToString(", ")}. A key starts with a " +
            "letter and contains only letters, digits and underscores."
    else ->
        "JIRA did not answer. Check that the host is reachable from this server."
}
