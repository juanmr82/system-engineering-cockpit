package com.sec.source.jira

import com.sec.domain.Prop
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flattener, against a **real** JIRA search response rather than a hand-written fixture.
 *
 * `docs/TEST_JIRA_DATA.json` is one page of a live Data Center instance: 50 issues across five
 * projects, hundreds of custom fields, German attribute values, and every awkward JSON shape a
 * hand-written fixture would never think to contain — an array of checklist objects with a nested
 * array inside each, option objects that appear as `null` on most issues, numbers that are integral
 * on one issue and decimal on another.
 *
 * **What this file asserts is storability, not values.** The graph is never involved: a property
 * map Neo4j cannot accept fails the whole `UNWIND` batch with an error naming the batch rather than
 * the field, and finding that out for the first time against a customer's 784 issues is the
 * expensive way. Each test here is one thing the driver would reject.
 *
 * The export is not committed, so this skips cleanly without it. That is a deliberate trade: it is
 * 3.4 MB of one company's real issue data, which does not belong in a repository, and the tests in
 * `JiraFieldsTest` are the ones that must always run.
 */
class JiraRealExportTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Surefire runs with the module directory as the working directory, so this climbs out of it.
    private val exportPath: Path = Path.of("..", "docs", "TEST_JIRA_DATA.json")

    private fun issues(): List<JiraIssueDto> {
        assumeTrue(
            exportPath.exists(),
            "docs/TEST_JIRA_DATA.json is absent — the real-export checks are skipped. " +
                "Drop a JIRA search response there to run them.",
        )
        val response = json.decodeFromString<JiraSearchResponse>(exportPath.readText(Charsets.UTF_8))
        assumeTrue(response.issues.isNotEmpty(), "the export contains no issues")
        return response.issues
    }

    /** Everything Neo4j will accept as a property value. */
    private fun isStorableScalar(value: Any?): Boolean =
        value == null || value is String || value is Long || value is Double || value is Boolean

    @Test
    fun `every flattened value is something Neo4j can store`() {
        val offenders = mutableListOf<String>()

        issues().forEach { issue ->
            JiraFields.flatten(issue.fields).forEach { (path, value) ->
                when {
                    isStorableScalar(value) -> Unit
                    value is List<*> -> {
                        // A list of maps or of lists is the failure this catches: JIRA returns both,
                        // and the driver rejects a nested collection outright.
                        val bad = value.filterNot { isStorableScalar(it) }
                        if (bad.isNotEmpty()) {
                            offenders += "${issue.key}.$path holds ${bad.first()?.javaClass?.simpleName}"
                        }
                    }
                    else -> offenders += "${issue.key}.$path is a ${value?.javaClass?.simpleName}"
                }
            }
        }

        assertTrue(offenders.isEmpty(), "unstorable property values: ${offenders.take(10)}")
    }

    @Test
    fun `every list property is homogeneous`() {
        val offenders = mutableListOf<String>()

        issues().forEach { issue ->
            JiraFields.flatten(issue.fields).forEach { (path, value) ->
                if (value is List<*>) {
                    val kinds = value.mapNotNull { it?.javaClass }.distinct()
                    if (kinds.size > 1) {
                        offenders += "${issue.key}.$path mixes ${kinds.map { it.simpleName }}"
                    }
                }
            }
        }

        // A mixed list fails the write for the whole batch, so `homogeneous()` stringifies rather
        // than letting one field lose five hundred issues.
        assertTrue(offenders.isEmpty(), "mixed-type lists: ${offenders.take(10)}")
    }

    @Test
    fun `no JIRA field flattens onto a name in the application namespace`() {
        // R1: everything starting with `__` is ours. A source field that landed on one would be
        // silently overwritten by the Tier-1 assignment, or worse, overwrite it.
        val collisions = issues()
            .flatMap { issue -> JiraFields.flatten(issue.fields).keys.map { issue.key to it } }
            .filter { (_, path) -> path.startsWith(Prop.NAMESPACE) }

        assertTrue(collisions.isEmpty(), "fields landing in the `__` namespace: ${collisions.take(5)}")
    }

    @Test
    fun `every issue produces the Tier-1 four`() {
        val rows = issues().map { JiraRows.issueRow(it, storeRawFields = true) }

        rows.forEach { row ->
            @Suppress("UNCHECKED_CAST")
            val props = row["props"] as Map<String, Any?>
            val id = row["id"] as String

            assertTrue(id.startsWith("jira:issue:"), "malformed __id: $id")
            assertTrue((props[Prop.NAME] as? String)?.isNotBlank() == true, "blank __name on $id")
            assertEquals("current", props[Prop.VERSION])
            assertTrue((props[Prop.SORT_KEY] as? String)?.isNotBlank() == true, "blank __sortKey on $id")
            assertTrue((props[JiraProp.PROJECT_KEY] as? String)?.isNotBlank() == true, "no project on $id")
        }

        // R6: __id is globally unique, and it owns a uniqueness constraint. Two issues colliding on
        // it would fail the import at the database rather than here.
        val ids = rows.map { it["id"] }
        assertEquals(ids.size, ids.toSet().size, "two issues derived the same __id")
    }

    @Test
    fun `sort keys reproduce JIRA's own issue order`() {
        val byProject = issues().groupBy { JiraRows.projectKeyOf(it) }

        byProject.forEach { (project, projectIssues) ->
            val byNumber = projectIssues.sortedBy { it.key.substringAfterLast('-').toLong() }
            val bySortKey = projectIssues.sortedBy { JiraFields.deriveSortKey(it.key) }

            // R3's contract, on real keys: a plain string sort on __sortKey is the source tool's
            // own display order. Real project keys here run to five digits, which is where an
            // unpadded string sort visibly diverges.
            assertEquals(
                byNumber.map { it.key },
                bySortKey.map { it.key },
                "__sortKey does not reproduce issue order for $project",
            )
        }
    }

    @Test
    fun `project keys from real data are accepted by the JQL builder`() {
        val projects = issues().map { JiraRows.projectKeyOf(it) }.distinct()

        assertTrue(projects.isNotEmpty())
        projects.forEach { key ->
            // The key is concatenated into a query string, so anything the validator refuses is a
            // project this importer simply cannot fetch. Real keys must pass.
            assertTrue(JiraJql.isValidProjectKey(key), "real project key rejected: '$key'")
        }
    }

    @Test
    fun `links resolve to real targets and never duplicate an edge`() {
        val issues = issues()
        val rows = issues.flatMap { JiraRows.linkRows(it) }

        assumeTrue(rows.isNotEmpty(), "this export page carries no issue links")

        rows.forEach { row ->
            assertTrue((row["toId"] as String).startsWith("jira:issue:"), "malformed link target")
            assertTrue((row["fromId"] as String).startsWith("jira:issue:"), "malformed link source")
        }

        // Only the outward half is read, so an edge appears once even when both of its issues are
        // on this page and both state the link. Taking both halves would draw every dependency
        // twice, in opposite directions.
        val edges = rows.map { it["fromId"] to it["toId"] to it["linkTypeId"] }
        assertEquals(edges.size, edges.toSet().size, "the same edge was emitted twice")
    }

    @Test
    fun `the whole page flattens to a bounded number of distinct columns`() {
        val paths = issues().flatMap { JiraFields.flatten(it.fields).keys }.toSet()

        // Not a performance assertion — a sanity one. This instance defines hundreds of custom
        // fields, and the field-selection dialog reads this list: a flattener that recursed without
        // a depth limit would turn one checklist field into thousands of paths and make the dialog
        // unusable without failing anything.
        assertTrue(paths.isNotEmpty(), "nothing flattened at all")
        assertTrue(
            paths.size < MAX_REASONABLE_PATHS,
            "${paths.size} distinct field paths from 50 issues — the depth limit is not holding",
        )
    }

    private companion object {
        const val MAX_REASONABLE_PATHS: Int = 2000
    }
}
