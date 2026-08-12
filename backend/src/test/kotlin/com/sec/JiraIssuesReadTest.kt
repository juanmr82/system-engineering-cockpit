package com.sec

import com.sec.config.Neo4jSettings
import com.sec.api.dto.JiraColumnDto
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraIssuesProjection.SortDirection
import com.sec.source.jira.JiraIssuesProjection.SortField
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Issues table's read path against a real Neo4j Community image (spec §14.4).
 *
 * ## Why this seeds with Cypher instead of running an import
 *
 * `JiraIssueImportTest` runs the importer, which is the right shape for testing the importer. A
 * read path wants the opposite: a fixture written by hand, so a failure means the query is wrong
 * rather than that something upstream produced different data than expected. It also lets the
 * fixture hold shapes the committed export does not — an issue with no type, a stub, a list-valued
 * field, and issue numbers that expose the string-sort trap.
 *
 * Everything here would pass against an in-memory fake except the two things that matter: dynamic
 * property access with a parameterised key, and `ORDER BY` over a property chosen at runtime.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class JiraIssuesReadTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var issues: JiraIssuesProjection

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        issues = JiraIssuesProjection(graphDriver, HOST)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun seed(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n")) { }
        graphDriver.executeWrite(Query(FIXTURE)) { }
    }

    // -- order ---------------------------------------------------------------------------------

    /**
     * The reason `__sortKey` is written at all (R3).
     *
     * `SCRUM-10` precedes `SCRUM-2` as text and follows it in every JIRA screen. The table's default
     * order is JIRA's own, and the only thing that makes that true is sorting on the derived key
     * rather than on `key` itself.
     */
    @Test
    fun `the default order is JIRA's own, not the alphabet's`() = runBlocking {
        val page = list()

        assertEquals(
            listOf("OTS-3", "SCRUM-1", "SCRUM-2", "SCRUM-10", "SCRUM-100"),
            page.rows.map { it.key },
        )
    }

    @Test
    fun `descending is the same order reversed`() = runBlocking {
        val page = list(direction = SortDirection.DESC)

        assertEquals(
            listOf("SCRUM-100", "SCRUM-10", "SCRUM-2", "SCRUM-1", "OTS-3"),
            page.rows.map { it.key },
        )
    }

    // -- paging --------------------------------------------------------------------------------

    /**
     * A page reports the size of the whole result, not of itself.
     *
     * The distinction is the paginator: `total` is what tells a reader there are five issues while
     * showing them two, and computing it from the rows in hand would make every page the last one.
     */
    @Test
    fun `a page carries the total of the whole result`() = runBlocking {
        val page = list(page = 1, size = 2)

        assertEquals(listOf("SCRUM-2", "SCRUM-10"), page.rows.map { it.key })
        assertEquals(FIXTURE_ISSUES, page.total)
        assertEquals(1, page.page)
        assertEquals(2, page.size)
    }

    @Test
    fun `a page past the end is empty rather than an error`() = runBlocking {
        val page = list(page = 99, size = 10)

        assertEquals(emptyList(), page.rows)
        assertEquals(FIXTURE_ISSUES, page.total)
    }

    // -- filtering -----------------------------------------------------------------------------

    /**
     * The search box, and the half of it that is easy to get wrong: the total.
     *
     * A filter applied to the rows and not to the count produces a paginator promising pages that
     * are empty. The two statements share one `WHERE` fragment for exactly this reason.
     */
    @Test
    fun `a text search filters the rows and the total together`() = runBlocking {
        val page = list(query = "thermal")

        assertEquals(listOf("SCRUM-2"), page.rows.map { it.key })
        assertEquals(1, page.total)
    }

    /** Case-insensitive, and matching the key as well as the name — spec §13.2's two fields. */
    @Test
    fun `a search matches the key and the summary, whatever the case`() = runBlocking {
        assertEquals(listOf("OTS-3"), list(query = "ots").rows.map { it.key })
        assertEquals(listOf("SCRUM-2"), list(query = "THERMAL").rows.map { it.key })
        assertEquals(emptyList(), list(query = "nothing matches this").rows.map { it.key })
    }

    @Test
    fun `a project filter narrows to that project`() = runBlocking {
        val page = list(projectKeys = listOf("OTS"))

        assertEquals(listOf("OTS-3"), page.rows.map { it.key })
        assertEquals(1, page.total)
    }

    // -- what a row carries ----------------------------------------------------------------------

    /**
     * The three things this endpoint derives, all of which would be wrong if taken from the graph.
     *
     * `ref` is the handle, never `__id` (R5). `browseUrl` is the page a person opens — the stored
     * `self` is an API URL that answers with JSON, which spec §13.2 flags as a trap in the
     * requirement as written. `unresolved` is a label test.
     */
    @Test
    fun `a row carries a handle and a browse URL, never the stored identity`() = runBlocking {
        val row = list().rows.first { it.key == "SCRUM-1" }

        assertEquals("$HOST/browse/SCRUM-1", row.browseUrl)
        assertEquals("$HOST/rest/api/2/issue/1", Ref.decodeOrNull(row.ref))
        assertTrue("/rest/" !in row.ref, "the raw resource URL is in the row's handle")
        assertEquals("Task", row.issueTypeName)
        assertEquals(false, row.unresolved)
    }

    /**
     * A stub is a row like any other, and says so.
     *
     * It has no issue type and no projection, which is exactly the shape that makes an unguarded
     * `OPTIONAL MATCH` drop it from the page — the failure would be an issue that is in the graph,
     * has a link pointing at it, and cannot be found in the table.
     */
    @Test
    fun `a stub is returned, flagged, and still linkable`() = runBlocking {
        val row = list().rows.first { it.key == "SCRUM-100" }

        assertEquals(true, row.unresolved)
        assertNull(row.issueTypeName)
        assertEquals("$HOST/browse/SCRUM-100", row.browseUrl, "a stub's JIRA link still works")
    }

    // -- the configurable columns (spec §14.4) -----------------------------------------------------

    /**
     * The mechanism the whole endpoint is shaped around: a runtime-chosen set of properties read
     * with a **parameter**, not with Cypher assembled per request (R10).
     *
     * Until the column picker exists nothing asks for a field, so this is the only thing that
     * exercises it — and it is the part that cannot be added later without changing the response.
     */
    @Test
    fun `requested fields are read by name, in the order they were asked for`() = runBlocking {
        val row = list(fieldIds = listOf("status", "duedate")).rows.first { it.key == "SCRUM-1" }

        assertEquals(
            mapOf("status" to JsonPrimitive("In Progress"), "duedate" to JsonPrimitive("2026-09-01")),
            row.values,
        )
    }

    /**
     * §7.4, and the order of the two arguments is the whole test (ADR 0014 point 21).
     *
     * The importer stores a complex value **both** ways: verbatim as JSON text on the issue, because
     * R1 keeps the source untouched, and as a derived display string on the projection, because a
     * table cannot sort or show a blob. So both properties exist under the same key, and reading the
     * issue first means the blob always wins — which rendered a live Status column as
     * `{"self":"…","description":"","iconUrl":"…"}`.
     *
     * The original fixture put the value on the projection alone, which no import produces, and the
     * test passed for a case that cannot happen.
     */
    @Test
    fun `a complex field shows the string its projection derived, not the stored JSON`() = runBlocking {
        val row = list(fieldIds = listOf("customfield_1")).rows.first { it.key == "SCRUM-1" }

        assertEquals(mapOf("customfield_1" to JsonPrimitive("WSS")), row.values)
    }

    /** The fallback half of the same rule: a scalar has no projection entry, so the issue answers. */
    @Test
    fun `a scalar is read from the issue, which has no projection entry for it`() = runBlocking {
        val row = list(fieldIds = listOf("duedate")).rows.first { it.key == "SCRUM-1" }

        assertEquals(mapOf("duedate" to JsonPrimitive("2026-09-01")), row.values)
    }

    /** A list stays a list: the table renders those as chips, and `[a, b]` as text is unrecoverable. */
    @Test
    fun `a list-valued field keeps its elements`() = runBlocking {
        val row = list(fieldIds = listOf("labels")).rows.first { it.key == "SCRUM-1" }

        assertEquals(
            JsonArray(listOf(JsonPrimitive("thermal"), JsonPrimitive("safety"))),
            row.values["labels"],
        )
    }

    /**
     * A field this issue does not have is null, and the key is still present.
     *
     * Dropping the key instead would make "no value" and "not requested" the same thing to the
     * client, and the client's answer to those differs — an em-dash against a missing column.
     */
    @Test
    fun `a field the issue does not carry comes back null`() = runBlocking {
        val row = list(fieldIds = listOf("duedate")).rows.first { it.key == "SCRUM-2" }

        assertTrue("duedate" in row.values)
        assertEquals(null, row.values["duedate"]?.takeIf { it != kotlinx.serialization.json.JsonNull })
    }

    /** The columns travel with the rows, so headers and cells are always one answer to one question. */
    @Test
    fun `the page describes the columns it returned`() = runBlocking {
        val page = list(fieldIds = listOf("status", "labels"))

        assertEquals(listOf("status", "labels"), page.columns.map { it.fieldId })
    }

    // -- harness -----------------------------------------------------------------------------------

    private suspend fun list(
        page: Int = 0,
        size: Int = 50,
        direction: SortDirection = SortDirection.ASC,
        query: String? = null,
        projectKeys: List<String>? = null,
        fieldIds: List<String> = emptyList(),
        sort: SortField = SortField.KEY,
    ) = issues.listIssues(
        page = page,
        size = size,
        sort = sort,
        direction = direction,
        query = query,
        projectKeys = projectKeys,
        // The route resolves these from the stored choice and the catalogue; here they are stated,
        // so a failure means the read path is wrong rather than that the catalogue is.
        columns = fieldIds.map { JiraColumnDto(fieldId = it, name = it) },
    )

    private companion object {
        const val HOST = "https://jira.example.com"

        /** Four real issues and one stub. */
        const val FIXTURE_ISSUES = 5

        /**
         * The graph as an import would have left it, written by hand.
         *
         * The literals are deliberate: a fixture built from the same constants as the code under
         * test would let a wrong constant pass (backend/CLAUDE.md). The issue numbers are 1, 2, 10
         * and 100 because that is the smallest set that fails under a string sort.
         */
        val FIXTURE = """
            CYPHER 25
            CREATE (t:SEItem:JiraIssueType {__id: 'https://jira.example.com/rest/api/2/issuetype/1',
                                            __name: 'Task', __version: 'current', id: '1', name: 'Task'})
            CREATE (i1:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/1', __name: 'SCRUM-1: A first issue',
              __version: '2026-08-01', __sortKey: 'SCRUM-000000001', __projectKey: 'SCRUM',
              key: 'SCRUM-1', id: '1', status: 'In Progress', duedate: '2026-09-01',
              labels: ['thermal', 'safety'],
              customfield_1: '{"self":"https://jira.example.com/rest/api/2/customFieldOption/38303","value":"WSS","id":"38303"}' })
            CREATE (i2:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/2', __name: 'SCRUM-2: Thermal margins',
              __version: '2026-08-02', __sortKey: 'SCRUM-000000002', __projectKey: 'SCRUM',
              key: 'SCRUM-2', id: '2', status: 'Done' })
            CREATE (i10:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/10', __name: 'SCRUM-10: Ten',
              __version: '2026-08-03', __sortKey: 'SCRUM-000000010', __projectKey: 'SCRUM',
              key: 'SCRUM-10', id: '10' })
            CREATE (o3:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/3', __name: 'OTS-3: Another project',
              __version: '2026-08-04', __sortKey: 'OTS-000000003', __projectKey: 'OTS',
              key: 'OTS-3', id: '3' })
            CREATE (stub:SEItem:JiraIssue:`__UNDEFINED` {
              __id: 'https://jira.example.com/rest/api/2/issue/100', __name: '<unresolved SCRUM-100>',
              __version: 'unresolved', __sortKey: 'SCRUM-000000100',
              key: 'SCRUM-100', id: '100', summary: 'Outside the configured projects' })
            CREATE (i1)-[:hasIssueType]->(t)
            CREATE (i2)-[:hasIssueType]->(t)
            CREATE (i1)-[:__projection]->(:`__JiraProjection` {
              __id: 'https://jira.example.com/rest/api/2/issue/1#projection', customfield_1: 'WSS' })
            CREATE (i2)-[:__projection]->(:`__JiraProjection` {
              __id: 'https://jira.example.com/rest/api/2/issue/2#projection' })
        """.trimIndent()
    }
}
