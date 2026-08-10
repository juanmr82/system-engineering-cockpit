package com.sec.source.jira

import com.sec.domain.Prop
import com.sec.domain.PropValue
import com.sec.graph.GraphDriver
import com.sec.graph.WriteCounts
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeWrite
import com.sec.graph.executeWriteCounting
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

/**
 * The JIRA importer's write path — and the **only** thing in this backend that writes an imported
 * node.
 *
 * ## Why this is not the meta writer, and must never become it
 *
 * R1 says the application is read-only on imported data, and R2 says the API layer's write
 * endpoints touch `:__Meta` and nothing else. Putting an importer inside the backend (ADR 0013)
 * puts something in this process that must write imported nodes, and the way both rules stay true
 * is that the two paths are different objects with different names and no shared helper:
 *
 *  - [MetaWriter][com.sec.meta.MetaWriter] writes `:__Meta` and its `__` relationships. Every
 *    route that saves a dialog reaches it.
 *  - this class writes `:SEItem:Jira*` and their source relationships. Exactly one caller —
 *    [JiraImporter] — reaches it, and it is reached from exactly one endpoint.
 *
 * A route that wanted to "just set one field on an issue" would have to come through here, and
 * finding itself in a file whose name says *importer* is the point. Nothing here is generic:
 * there is no `setProperty`, no `update`, no map parameter a caller chooses the keys of.
 *
 * Every statement is batched with `UNWIND` and bounded by `jira.batchSize`, and every one of them
 * stamps [Prop.IMPORTED_AT] so the reconciliation at the end of the run has something to ask.
 */
public class JiraGraphWriter(
    private val graphDriver: GraphDriver,
    private val batchSize: Int,
) {

    /**
     * Everything that must exist before anything else can be written: the schema, and the one
     * source-root node.
     *
     * **Call this from every write path, not just the import.** `UPSERT_PROJECTS` opens with
     * `MATCH (src:JiraSource …)`, so on a graph without that node the whole statement matches
     * nothing and does nothing — silently, with no error for a route to report. That is not a
     * hypothetical: adding the first project happens *before* the first import by definition, so a
     * fresh installation could never get started. The route returned 200 and an empty list.
     *
     * Both halves are idempotent — the schema statements are `IF NOT EXISTS` and the source is a
     * `MERGE` — so calling this on every write is a few cheap round trips, and much less expensive
     * than a statement that quietly declines to run.
     */
    public suspend fun prepare(runStamp: String) {
        applySchema()
        upsertSource(runStamp)
    }

    /** Constraints and indexes, `IF NOT EXISTS`. Each runs alone — schema cannot share a
     *  transaction with anything else. */
    public suspend fun applySchema() {
        JiraCypher.SCHEMA.forEach { statement ->
            graphDriver.executeWrite(Query(statement)) { }
        }
        logger.info { "Applied JIRA schema (${JiraCypher.SCHEMA.size} statements)" }
    }

    public suspend fun upsertSource(runStamp: String) {
        graphDriver.executeWrite(
            Query(JiraCypher.UPSERT_SOURCE)
                .withParameters(
                    mapOf(
                        "sourceId" to JiraId.SOURCE,
                        "name" to SOURCE_DISPLAY_NAME,
                        "version" to PropValue.CURRENT_VERSION,
                        // One node, so its sort position among siblings is not a question yet;
                        // stating it keeps the R3 contract true for the JIRA branch as it grows.
                        "sortKey" to SOURCE_DISPLAY_NAME,
                        "ts" to runStamp,
                    ),
                ),
        ) { }
    }

    public suspend fun upsertProjects(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.UPSERT_PROJECTS, rows, runStamp) {
            it["sourceId"] = JiraId.SOURCE
        }

    public suspend fun upsertIssueTypes(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.UPSERT_ISSUE_TYPES, rows, runStamp)

    public suspend fun upsertFields(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.UPSERT_FIELDS, rows, runStamp)

    public suspend fun upsertIssues(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.UPSERT_ISSUES, rows, runStamp)

    public suspend fun linkIssueTypes(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.LINK_ISSUE_TYPES, rows, runStamp)

    public suspend fun linkHierarchy(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.LINK_HIERARCHY, rows, runStamp)

    public suspend fun linkIssues(rows: List<Map<String, Any?>>, runStamp: String): WriteCounts =
        writeBatched(JiraCypher.LINK_ISSUES, rows, runStamp)

    /**
     * The reconciliation, in the one order that works.
     *
     * Edges first, then stale issues, then orphaned placeholders — because deleting an issue is
     * what orphans a placeholder, so asking about placeholders before the deletions would miss
     * exactly the ones this run created work for.
     */
    public suspend fun reconcile(projectKeys: List<String>, runStamp: String): JiraReconcileCounts {
        if (projectKeys.isEmpty()) return JiraReconcileCounts()

        val scope = mapOf("projectKeys" to projectKeys, "ts" to runStamp)
        val hierarchy = count(JiraCypher.PRUNE_HIERARCHY, scope)
        val links = count(JiraCypher.PRUNE_ISSUE_LINKS, scope)
        val issues = count(JiraCypher.DELETE_STALE_ISSUES, scope)
        // Unscoped on purpose: importing one project is exactly what removes the last link to a
        // placeholder standing in for an issue of another.
        val stubs = count(JiraCypher.DELETE_ORPHAN_STUBS, emptyMap())

        return JiraReconcileCounts(
            hierarchyPruned = hierarchy,
            linksPruned = links,
            issuesDeleted = issues,
            placeholdersCollected = stubs,
        )
    }

    /** Everything a project contributed, when an admin takes it out of scope. */
    public suspend fun deleteProjectIssues(projectKey: String): Int =
        count(JiraCypher.DELETE_PROJECT_ISSUES, mapOf("projectKey" to projectKey))

    private suspend fun count(statement: String, parameters: Map<String, Any?>): Int =
        graphDriver.executeWrite(Query(statement).withParameters(parameters)) { records ->
            records.firstOrNull()?.get(0)?.asInt() ?: 0
        }

    private suspend fun writeBatched(
        statement: String,
        rows: List<Map<String, Any?>>,
        runStamp: String,
        extraParameters: (MutableMap<String, Any?>) -> Unit = {},
    ): WriteCounts {
        if (rows.isEmpty()) return WriteCounts.NONE

        var total = WriteCounts.NONE
        rows.chunked(batchSize).forEach { batch ->
            val parameters = mutableMapOf<String, Any?>("rows" to batch, "ts" to runStamp)
            extraParameters(parameters)
            total += graphDriver.executeWriteCounting(Query(statement).withParameters(parameters))
        }
        return total
    }

    private companion object {
        /**
         * `__name` of the source root. It is content, so it is what a user would read — and the
         * only `__`-prefixed *value* in this file that is not derived from JIRA.
         */
        const val SOURCE_DISPLAY_NAME: String = "JIRA"
    }
}

/** What the reconciliation removed. Every number reaches the import report. */
public data class JiraReconcileCounts(
    public val hierarchyPruned: Int = 0,
    public val linksPruned: Int = 0,
    public val issuesDeleted: Int = 0,
    public val placeholdersCollected: Int = 0,
)
