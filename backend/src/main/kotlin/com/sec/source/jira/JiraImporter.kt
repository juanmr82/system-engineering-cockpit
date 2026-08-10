package com.sec.source.jira

import com.sec.api.dto.JiraImportReportDto
import com.sec.config.JiraSettings
import com.sec.graph.WriteCounts
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The "Import JIRA issues" button, server side (design doc §5, ADR 0013).
 *
 * One run: catalogues, then issues, then the relationships between them, then reconciliation. The
 * user's click reaches here through exactly one endpoint, and this is the only caller of
 * [JiraGraphWriter].
 *
 * ## Full sync, not an incremental delta
 *
 * Every run re-fetches every issue in scope rather than asking for `updated >= lastSyncTime`. The
 * incremental query is cheaper and **cannot see a deletion**, so it would need the full key-set
 * reconciliation beside it anyway — at which point it has bought nothing except a second code path
 * and a `lastSyncTime` to store, which would be a derived value in the graph (R2 forbids those).
 * When import time against real project sizes says otherwise, the incremental filter is an
 * addition to step 3 and the reconciliation stays exactly as it is.
 *
 * ## A run that fails part way through does not reconcile that project
 *
 * Reconciliation deletes what this run did not re-stamp, so running it over a project whose fetch
 * died half way would delete the half that had not arrived yet. Projects are therefore reconciled
 * one at a time and only when their own fetch completed; a project that failed keeps everything it
 * had, and says so in the report. An authentication failure aborts the whole run instead, because
 * every remaining project would fail identically and twenty identical warnings tell nobody
 * anything.
 */
public class JiraImporter(
    private val api: JiraApi?,
    private val writer: JiraGraphWriter,
    private val projection: JiraProjection,
    private val settings: JiraSettings,
) {

    /**
     * One run at a time.
     *
     * Two concurrent runs would share a `__importedAt` namespace and reconcile against each
     * other's half-written state — the second one's stamp would make the first one's issues look
     * stale. A second click gets told the first is still going, which is also the more useful
     * answer: an import of twenty thousand issues takes long enough for somebody to press the
     * button twice.
     */
    private val runLock = Mutex()

    public suspend fun run(actor: String): JiraImportOutcome {
        if (!runLock.tryLock()) return JiraImportOutcome.AlreadyRunning
        return try {
            runExclusively(actor)
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runExclusively(actor: String): JiraImportOutcome {
        val api = api ?: return JiraImportOutcome.NotConfigured

        val started = Instant.now()
        val runStamp = started.toString()
        val warnings = mutableListOf<String>()

        writer.prepare(runStamp)

        val scopes = projection.enabledProjects()
        if (scopes.isEmpty()) return JiraImportOutcome.NoProjectsInScope

        // Catalogues first: an issue's type has to exist before the issue can be linked to it, and
        // the field catalogue is what words every column in the dialog.
        val issueTypes = try {
            api.issueTypes()
        } catch (e: JiraException) {
            return JiraImportOutcome.Failed(e.failure)
        }
        writer.upsertIssueTypes(issueTypes.map(JiraRows::issueTypeRow), runStamp)
        val knownTypeIds = issueTypes.mapTo(HashSet()) { it.id }

        val previousFieldIds = projection.catalogFieldIds()
        val fields = try {
            api.fieldCatalog()
        } catch (e: JiraException) {
            return JiraImportOutcome.Failed(e.failure)
        }
        writer.upsertFields(fields.map(JiraRows::fieldRow), runStamp)
        val currentFieldIds = fields.mapTo(HashSet()) { it.id }

        val state = RunState(runStamp)
        val completed = mutableListOf<String>()

        for (scope in scopes) {
            if (state.issuesSeen >= settings.maxIssues) {
                warnings += "Stopped at the ${settings.maxIssues}-issue ceiling; " +
                    "'${scope.key}' and any project after it were not fetched."
                break
            }
            when (val result = importProject(api, scope, knownTypeIds, state)) {
                is ProjectResult.Imported -> completed += scope.key
                is ProjectResult.Aborted -> return JiraImportOutcome.Failed(result.failure)
                is ProjectResult.Skipped -> warnings += result.message
            }
        }

        // Both passes are deferred to here rather than run per page, because a link's target and a
        // sub-task's parent can arrive in a later page than the issue that names them — and a
        // MATCH that finds nothing drops its row without saying so.
        val typeLinks = writer.linkIssueTypes(state.typeLinkRows(), runStamp)
        val hierarchy = writer.linkHierarchy(state.hierarchyRows(), runStamp)
        val links = writer.linkIssues(state.linkRows, runStamp)

        val reconciled = writer.reconcile(completed, runStamp)

        // Asked after the writes, so it reflects the catalogue this run just refreshed.
        warnings += projection.unknownSelectedPaths()

        val report = JiraImportReportDto(
            startedAt = runStamp,
            durationMs = Duration.between(started, Instant.now()).toMillis(),
            projects = completed,
            issuesSeen = state.issuesSeen,
            issuesCreated = state.issueWrites.nodesCreated,
            // Everything the run touched that was not created. A MERGE that matched is an update,
            // whether or not any property actually changed — the server counts nodes, not diffs.
            issuesUpdated = (state.issuesSeen - state.issueWrites.nodesCreated).coerceAtLeast(0),
            issuesDeleted = reconciled.issuesDeleted,
            issueTypes = issueTypes.size + typeLinks.nodesCreated,
            fieldsInCatalog = fields.size,
            fieldsAdded = (currentFieldIds - previousFieldIds).sorted(),
            fieldsRemoved = (previousFieldIds - currentFieldIds).sorted(),
            linksCreated = links.relationshipsCreated,
            linksPruned = reconciled.linksPruned,
            hierarchyPruned = reconciled.hierarchyPruned,
            // Every node LINK_ISSUES created is a placeholder: the real issues already existed by
            // the time it ran.
            placeholdersCreated = links.nodesCreated,
            placeholdersCollected = reconciled.placeholdersCollected,
            warnings = warnings,
        )

        logger.info {
            "JIRA import by $actor: ${report.projects.size} projects, ${report.issuesSeen} issues " +
                "(${report.issuesCreated} new, ${report.issuesDeleted} deleted), " +
                "${report.warnings.size} warnings, ${report.durationMs}ms"
        }
        // hierarchy is reported through hierarchyPruned only; the created count is not interesting
        // on its own — every issue gets exactly one parent edge.
        logger.debug { "JIRA hierarchy edges written: ${hierarchy.relationshipsCreated}" }

        return JiraImportOutcome.Completed(report)
    }

    private suspend fun importProject(
        api: JiraApi,
        scope: JiraProjection.ProjectScope,
        knownTypeIds: Set<String>,
        state: RunState,
    ): ProjectResult {
        if (!JiraJql.isValidProjectKey(scope.key)) {
            return ProjectResult.Skipped(
                "'${scope.key}' is not a valid project key and was not fetched.",
            )
        }

        val jql = JiraJql.forProject(scope.key, scope.jql)
        return try {
            api.searchIssues(jql, settings.maxIssues - state.issuesSeen) { page ->
                writePage(page, knownTypeIds, state)
            }
            ProjectResult.Imported
        } catch (e: JiraException) {
            when (e.failure) {
                // Every remaining project would fail the same way, so one clear failure beats
                // twenty identical warnings.
                is JiraFailure.Unauthorised -> ProjectResult.Aborted(e.failure)
                else -> ProjectResult.Skipped(
                    "JIRA did not answer for project '${scope.key}'; it was left unchanged.",
                )
            }
        }
    }

    private suspend fun writePage(
        page: List<JiraIssueDto>,
        knownTypeIds: Set<String>,
        state: RunState,
    ) {
        // An issue type defined for one project only is absent from GET /issuetype on some
        // instances, and an issue whose type node does not exist would silently lose its hasType
        // edge. The type travels inside the issue, so it is upserted from there.
        val embedded = page.mapNotNull { JiraRows.embeddedIssueType(it) }
            .filterNot { it.id in knownTypeIds || it.id in state.embeddedTypeIds }
            .distinctBy { it.id }
        if (embedded.isNotEmpty()) {
            writer.upsertIssueTypes(embedded.map(JiraRows::issueTypeRow), state.runStamp)
            state.embeddedTypeIds += embedded.map { it.id }
        }

        state.issueWrites += writer.upsertIssues(
            page.map { JiraRows.issueRow(it, settings.storeRawFields) },
            state.runStamp,
        )
        state.issuesSeen += page.size

        page.forEach { issue ->
            val issueId = JiraId.issue(issue.key)
            state.seenIssueIds += issueId
            JiraRows.issueTypeIdOf(issue)?.let { state.typeLinks[issueId] = JiraId.issueType(it) }
            state.hierarchy += HierarchyCandidate(
                childId = issueId,
                parentKey = JiraRows.parentKeyOf(issue),
                projectId = JiraId.project(JiraRows.projectKeyOf(issue)),
            )
            state.linkRows += JiraRows.linkRows(issue)
        }
    }

    /**
     * What one run accumulates between pages.
     *
     * Bounded by `jira.maxIssues`: two ids per issue for the hierarchy and a handful of small maps
     * per link. At the default ceiling that is single-digit megabytes, against the hundreds the
     * issues themselves would occupy if they were held rather than written page by page.
     */
    private class RunState(val runStamp: String) {
        var issuesSeen: Int = 0
        var issueWrites: WriteCounts = WriteCounts.NONE
        val seenIssueIds: MutableSet<String> = HashSet()
        val embeddedTypeIds: MutableSet<String> = HashSet()
        val typeLinks: MutableMap<String, String> = LinkedHashMap()
        val hierarchy: MutableList<HierarchyCandidate> = ArrayList()
        val linkRows: MutableList<Map<String, Any?>> = ArrayList()

        fun typeLinkRows(): List<Map<String, Any?>> =
            typeLinks.map { (issueId, typeId) -> mapOf("issueId" to issueId, "typeId" to typeId) }

        /**
         * A sub-task hangs off its parent issue; everything else hangs off its project.
         *
         * The parent is only used when this run actually imported it. A sub-task whose parent is
         * out of scope would otherwise get no `__child` edge at all and fall out of the tree — so
         * it falls back to the project, which is where a reader will look for it.
         */
        fun hierarchyRows(): List<Map<String, Any?>> = hierarchy.map { candidate ->
            val parentId = candidate.parentKey
                ?.let(JiraId::issue)
                ?.takeIf { it in seenIssueIds }
                ?: candidate.projectId
            mapOf("childId" to candidate.childId, "parentId" to parentId)
        }
    }

    private data class HierarchyCandidate(
        val childId: String,
        val parentKey: String?,
        val projectId: String,
    )

    private sealed interface ProjectResult {
        data object Imported : ProjectResult
        data class Skipped(val message: String) : ProjectResult
        data class Aborted(val failure: JiraFailure) : ProjectResult
    }
}

/** What a run ended as. Each maps to one status code and one sentence at the route boundary. */
public sealed interface JiraImportOutcome {
    public data object NotConfigured : JiraImportOutcome
    public data object NoProjectsInScope : JiraImportOutcome
    public data object AlreadyRunning : JiraImportOutcome
    public data class Failed(public val failure: JiraFailure) : JiraImportOutcome
    public data class Completed(public val report: JiraImportReportDto) : JiraImportOutcome
}

/**
 * JQL construction, kept pure and in one place.
 *
 * The project key is validated rather than escaped, because it is concatenated into a query string
 * and JIRA's own key rule — a letter followed by letters, digits and underscores — is narrow enough
 * to check exactly. The admin's extra clause is *their* query and is passed through: it goes to
 * JIRA, which is read-only to this application, and a filter language is what the field is for.
 */
public object JiraJql {
    private val PROJECT_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,60}$")

    public fun isValidProjectKey(key: String): Boolean = PROJECT_KEY.matches(key)

    /**
     * `ORDER BY key ASC` is not cosmetic: Data Center pages by offset, and an unordered query is
     * free to return rows in a different order between pages — which silently skips some issues
     * and imports others twice.
     */
    public fun forProject(key: String, extraJql: String): String {
        val extra = extraJql.trim()
        val filter = if (extra.isEmpty()) "project = \"$key\"" else "project = \"$key\" AND ($extra)"
        return "$filter ORDER BY key ASC"
    }
}
