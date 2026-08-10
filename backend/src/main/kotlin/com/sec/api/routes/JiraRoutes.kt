package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.decodeRef
import com.sec.api.dto.JiraAvailableProjectDto
import com.sec.api.dto.JiraConnectionDto
import com.sec.api.dto.JiraProjectListDto
import com.sec.api.dto.SaveJiraColumnsRequestDto
import com.sec.api.dto.SaveJiraProjectScopeRequestDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.config.JiraSettings
import com.sec.meta.MetaWriter
import com.sec.security.CurrentUser
import com.sec.source.jira.JiraApi
import com.sec.source.jira.JiraException
import com.sec.source.jira.JiraFailure
import com.sec.source.jira.JiraFieldId
import com.sec.source.jira.JiraGraphWriter
import com.sec.source.jira.JiraId
import com.sec.source.jira.JiraImportOutcome
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraJql
import com.sec.source.jira.JiraProjection
import com.sec.source.jira.JiraRows
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant

/**
 * The JIRA-specific HTTP surface (design doc §5 and §6, ADR 0013).
 *
 * Handlers hold no logic beyond turning a sealed outcome into a status code, the same as the DOORS
 * routes. Two collaborators do the work and the split between them is R1's: [JiraImporter] and
 * [JiraGraphWriter] write imported nodes, [MetaWriter] writes Tier 2, and no handler writes
 * anything itself.
 *
 * **Nothing here writes to JIRA.** Every call out is a GET, and [JiraApi] has no method that
 * could be anything else.
 */
public fun Route.jiraRoutes(
    settings: JiraSettings,
    api: JiraApi?,
    projection: JiraProjection,
    importer: JiraImporter,
    graphWriter: JiraGraphWriter,
    metaWriter: MetaWriter,
) {
    route(ApiPaths.JIRA) {

        // Answered whether or not JIRA is configured — it is how the view finds out which.
        get("/connection") {
            call.respond(
                JiraConnectionDto(
                    configured = settings.isConfigured,
                    host = settings.host,
                    platform = settings.platform.name.lowercase(),
                ),
            )
        }

        /**
         * The projects in the graph, and — when JIRA can be reached — the ones it offers that are
         * not there yet.
         *
         * A JIRA that is down does not make this endpoint fail: the projects already in scope are
         * graph data and are still worth showing. `available` is simply empty, and the view says
         * it could not reach JIRA rather than showing nothing at all.
         */
        get("/projects") {
            val known = projection.listProjects()
            val available = if (api == null) {
                emptyList()
            } else {
                val existing = known.mapTo(HashSet()) { it.key }
                runCatching { api.projects() }
                    .getOrElse { emptyList() }
                    .filterNot { it.key in existing }
                    .map { JiraAvailableProjectDto(key = it.key, name = it.name) }
            }
            call.respond(JiraProjectListDto(projects = known, available = available))
        }

        /**
         * Add a project to the import scope, or change its clause.
         *
         * Two writes in a deliberate order: the project is fetched from JIRA and upserted as an
         * imported node *first*, then the Tier-2 scope node is attached to it. That is what makes
         * the scope node legal under R2 — it hangs off the imported graph — and it means a typo
         * is a 404 from JIRA rather than a scope entry naming a project that does not exist.
         */
        post("/projects") {
            val body = call.receive<SaveJiraProjectScopeRequestDto>()
            val key = body.key.trim()

            if (!JiraJql.isValidProjectKey(key)) {
                return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "Invalid project key",
                    "A project key starts with a letter and contains only letters, digits and underscores.",
                )
            }
            if (api == null) return@post call.respondNotConfigured()

            val project = try {
                api.project(key)
            } catch (e: JiraException) {
                return@post call.respondJiraFailure(e.failure)
            } ?: return@post call.respondProblem(
                HttpStatusCode.NotFound,
                "Project not found",
                "JIRA has no project '$key', or this account cannot browse it.",
            )

            // Stamped with now rather than an import's run stamp: nothing reconciles project
            // nodes, and a blank stamp would read as "an import saw this and skipped it".
            graphWriter.upsertProjects(
                listOf(JiraRows.projectRow(project)),
                runStamp = Instant.now().toString(),
            )
            metaWriter.saveJiraImportScope(
                projectId = JiraId.project(project.key),
                enabled = body.enabled,
                jql = body.jql.trim(),
            )
            call.respond(JiraProjectListDto(projects = projection.listProjects()))
        }

        /**
         * Take a project out of scope, and remove what it contributed.
         *
         * The issues go, which is the answer to "I added the wrong project" — leaving twelve
         * thousand issues in the table with no project governing them would be a state nothing in
         * the UI could explain. The project node itself stays, so it can be re-added without a
         * round trip to JIRA, and its `:__ImportScope` node is deleted rather than switched off.
         */
        delete("/projects/{ref}") {
            val projectId = call.decodeRef() ?: return@delete call.respondInvalidRef()
            val row = projection.listProjects().firstOrNull { JiraId.project(it.key) == projectId }
                ?: return@delete call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Project not found",
                    "No JIRA project for this reference.",
                )

            metaWriter.removeJiraImportScope(projectId)
            graphWriter.deleteProjectIssues(row.key)
            call.respond(JiraProjectListDto(projects = projection.listProjects()))
        }

        /**
         * The button. Synchronous: the user waits, and gets the run's report as the response.
         *
         * That is the flow this was asked for, and it is the right one at this size — an import of
         * a few thousand issues is seconds, and a job id the client polls would need a job store,
         * a progress channel and a way to show a report for a run the user has since navigated
         * away from. ADR 0013 records the point at which that stops being true.
         */
        post("/import") {
            when (val outcome = importer.run(actor = CurrentUser.PLACEHOLDER)) {
                is JiraImportOutcome.NotConfigured -> call.respondNotConfigured()

                is JiraImportOutcome.NoProjectsInScope -> call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "No projects selected",
                    "Add at least one JIRA project before importing.",
                )

                is JiraImportOutcome.AlreadyRunning -> call.respondProblem(
                    HttpStatusCode.Conflict,
                    "Import already running",
                    "An import is already in progress. Wait for it to finish before starting another.",
                )

                is JiraImportOutcome.Failed -> call.respondJiraFailure(outcome.failure)

                is JiraImportOutcome.Completed -> call.respond(outcome.report)
            }
        }

        /** One page of the Issues table. Reads the graph only — it never calls JIRA. */
        get("/issues") {
            val projects = call.request.queryParameters.getAll("project")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
            call.respond(projection.issuePage(projects, offset, limit))
        }

        /** The selection tree, with the current selection marked. */
        get("/fields") {
            call.respond(projection.fieldTree())
        }

        // One dialog, one request, one transaction (R7). The tree comes back so the dialog renders
        // the state the server actually holds rather than the one it hoped for.
        post("/fields") {
            val body = call.receive<SaveJiraColumnsRequestDto>()
            metaWriter.saveJiraColumns(
                sourceId = JiraId.SOURCE,
                paths = body.paths,
                fixedPaths = JiraFieldId.fixedColumns,
            )
            call.respond(projection.fieldTree())
        }
    }
}

private const val DEFAULT_PAGE_SIZE: Int = 100

private suspend fun ApplicationCall.respondNotConfigured(): Unit = respondProblem(
    HttpStatusCode.ServiceUnavailable,
    "JIRA is not configured",
    "This installation has no JIRA host or token set. Ask an administrator to configure it.",
)

/**
 * JIRA's failures, in sentences a reader can act on.
 *
 * The status codes JIRA returned are never passed straight through — a 401 from JIRA is not a 401
 * from this API, and answering with one would tell the browser that *this* session had expired.
 */
private suspend fun ApplicationCall.respondJiraFailure(failure: JiraFailure): Unit = when (failure) {
    is JiraFailure.NotConfigured -> respondNotConfigured()

    is JiraFailure.Unauthorised -> respondProblem(
        HttpStatusCode.BadGateway,
        "JIRA rejected the credentials",
        "The configured JIRA token is not accepted, or it lacks Browse Projects permission.",
    )

    is JiraFailure.Rejected -> respondProblem(
        HttpStatusCode.BadGateway,
        "JIRA rejected the request",
        "JIRA answered ${failure.status}. Check the project keys and the extra JQL clause.",
    )

    is JiraFailure.Unreachable -> respondProblem(
        HttpStatusCode.BadGateway,
        "JIRA could not be reached",
        "No answer from the configured JIRA host. It may be down, or blocked by the network.",
    )
}
