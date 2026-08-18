package com.sec

import com.sec.config.Neo4jSettings
import com.sec.api.dto.JiraColumnDto
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.security.AccessSet
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraIssuesProjection.SortDirection
import com.sec.source.jira.JiraIssuesProjection.SortField
import com.sec.source.jira.JiraLinkGraphProjection
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
import kotlin.test.assertFalse
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
    private lateinit var graphs: JiraLinkGraphProjection

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        issues = JiraIssuesProjection(graphDriver, HOST)
        graphs = JiraLinkGraphProjection(graphDriver)
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
     * are empty. The two statements share one `WHERE` fragment for exactly this reason — and since
     * the search widened to every field, they also share the fragment that walks those fields.
     */
    @Test
    fun `a text search filters the rows and the total together`() = runBlocking {
        val page = list(query = "margins")

        assertEquals(listOf("SCRUM-2"), page.rows.map { it.key })
        assertEquals(1, page.total)
    }

    /**
     * Two issues match `thermal`, and they match it in different fields.
     *
     * SCRUM-2 has it in its summary and SCRUM-1 carries it as a *label* — which is the whole point
     * of the widened search, and the assertion that would have failed under the old one.
     */
    @Test
    fun `a term is found wherever it lives`() = runBlocking {
        assertEquals(listOf("SCRUM-1", "SCRUM-2"), list(query = "thermal").rows.map { it.key })
    }

    /** Case-insensitive, over every field rather than §13.2's two. */
    /**
     * The search reads every field, not the key and the summary (ADR 0014 point 22).
     *
     * `safety` is a **label** here, and labels are the case that decides the shape of the whole
     * predicate: they are stored as a Neo4j list, and `toString()` errors on a list rather than
     * returning something useless — so a statement that did not branch on the type would not
     * narrow the results, it would fail the request.
     */
    @Test
    fun `a search matches a value in any field, including a list`() = runBlocking {
        assertEquals(listOf("SCRUM-1"), list(query = "safety").rows.map { it.key })
        assertEquals(1, list(query = "safety").total)
    }

    /** A date, a number and a plain string are all searchable, and none of them is text on the node. */
    @Test
    fun `a search matches a non-string field`() = runBlocking {
        assertEquals(listOf("SCRUM-1"), list(query = "2026-09-01").rows.map { it.key })
    }

    /**
     * The projection is searched too, and this is the case that makes it worth doing.
     *
     * The issue stores a status as `{"self":"…","name":"Idea"}`; the projection stores `Idea`. A
     * user searches for the word they can see.
     */
    @Test
    fun `a search matches the display string a projection derived`() = runBlocking {
        assertEquals(listOf("SCRUM-1"), list(query = "WSS").rows.map { it.key })
    }

    /**
     * `__`-prefixed properties are ours, and are never searched (R5).
     *
     * `__sortKey` is the one that would bite: every issue carries a zero-padded key, so a search
     * for `000` would match the entire table on a name no user has ever been shown.
     */
    @Test
    fun `a search never matches an internal property`() = runBlocking {
        assertEquals(emptyList(), list(query = "000000").rows.map { it.key })
        assertEquals(emptyList(), list(query = "unresolved").rows.map { it.key })
    }

    @Test
    fun `a search matches the key and the summary, whatever the case`() = runBlocking {
        assertEquals(listOf("OTS-3"), list(query = "ots").rows.map { it.key })
        assertEquals(listOf("SCRUM-2"), list(query = "MARGINS").rows.map { it.key })
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

    // -- the related-issues graph ----------------------------------------------------------------

    /**
     * One hop: the seed and what it is directly linked to.
     *
     * The stub is in the picture. A link to an issue outside the configured projects is a fact
     * about the issue that was asked for, and leaving it out would make the diagram claim there is
     * nothing there — the same argument the References column makes for DOORS.
     */
    @Test
    fun `depth one draws the seed and its immediate links`() = runBlocking {
        val graph = graphs.graphOf(issueId(1), depth = 1, access = AccessSet.SEES_ALL)!!

        assertEquals(setOf("SCRUM-1", "SCRUM-2", "SCRUM-100"), graph.nodes.map { it.key }.toSet())
        assertTrue(graph.nodes.single { it.key == "SCRUM-1" }.seed)
        assertTrue(graph.nodes.single { it.key == "SCRUM-100" }.unresolved)
    }

    /** A second hop reaches what the neighbours are linked to, and no further. */
    @Test
    fun `depth two reaches the neighbours of the neighbours`() = runBlocking {
        val keys = graphs.graphOf(issueId(1), depth = 2, access = AccessSet.SEES_ALL)!!.nodes.map { it.key }.toSet()

        assertEquals(setOf("SCRUM-1", "SCRUM-2", "SCRUM-10", "SCRUM-100"), keys)
    }

    /** The four things a node shows (§13.2), each from the place that holds a word rather than a blob. */
    @Test
    fun `a node carries its type, status, key and summary`() = runBlocking {
        val seed = graphs.graphOf(issueId(1), depth = 1, access = AccessSet.SEES_ALL)!!.nodes.single { it.key == "SCRUM-1" }

        assertEquals("Task", seed.typeName)
        assertEquals("In Progress", seed.statusName)
        assertEquals("A first issue", seed.summary)
    }

    /**
     * An edge keeps JIRA's own direction and its own name for the relationship.
     *
     * Unlike DOORS's `refersTo`, this source says what the link *is*, so the picture can label it.
     * A sub-task edge has no type name and says so with its own flag instead.
     */
    @Test
    fun `edges keep their direction and their type name`() = runBlocking {
        val graph = graphs.graphOf(issueId(1), depth = 1, access = AccessSet.SEES_ALL)!!
        val seed = Ref.encode(issueId(1))

        val relates = graph.edges.single { it.typeName == "Relates" }
        assertEquals(seed, relates.source)
        assertFalse(relates.subTask)

        val subTask = graph.edges.single { it.subTask }
        assertEquals(seed, subTask.source)
        assertNull(subTask.typeName)
    }

    /**
     * A link the depth cut off is counted, not silently dropped.
     *
     * A diagram that stops with nothing to say it stopped is read as a diagram that ended, which is
     * the failure the badge exists to prevent (`REQ_BREAKDOWN_GRAPH_VIEW.md` §1.1).
     */
    @Test
    fun `a link outside the picture is counted on the node it belongs to`() = runBlocking {
        val graph = graphs.graphOf(issueId(1), depth = 1, access = AccessSet.SEES_ALL)!!

        assertEquals(1, graph.nodes.single { it.key == "SCRUM-2" }.truncatedNeighbours)
        assertTrue(graph.truncated)
    }

    /** An issue with no links is one node and no edges — not an error, and not an empty answer. */
    @Test
    fun `an issue with no links is still a graph`() = runBlocking {
        val graph = graphs.graphOf(issueId(3), depth = 2, access = AccessSet.SEES_ALL)!!

        assertEquals(listOf("OTS-3"), graph.nodes.map { it.key })
        assertEquals(emptyList(), graph.edges)
        assertFalse(graph.truncated)
    }

    /** A hand-edited handle is a 404, never an empty picture presented as an answer. */
    @Test
    fun `an unknown issue has no graph at all`() = runBlocking {
        assertNull(graphs.graphOf("https://jira.example.com/rest/api/2/issue/9999", depth = 1, access = AccessSet.SEES_ALL))
    }

    /** The table's control is offered from this count, so it has to be the count of both directions. */
    @Test
    fun `a row carries how many issues it is linked to`() = runBlocking {
        val rows = list().rows.associateBy { it.key }

        assertEquals(2, rows.getValue("SCRUM-1").linkCount)
        assertEquals(2, rows.getValue("SCRUM-2").linkCount)
        assertEquals(0, rows.getValue("OTS-3").linkCount)
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
        access = AccessSet.SEES_ALL,
    )

    /** The fixture's issues are `<host>/rest/api/2/issue/<n>`, which is what a `self` looks like. */
    private fun issueId(number: Int): String = "$HOST/rest/api/2/issue/$number"

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
              key: 'SCRUM-1', id: '1', summary: 'A first issue',
              status: 'In Progress', duedate: '2026-09-01',
              labels: ['thermal', 'safety'],
              customfield_1: '{"self":"https://jira.example.com/rest/api/2/customFieldOption/38303","value":"WSS","id":"38303"}' })
            CREATE (i2:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/2', __name: 'SCRUM-2: Thermal margins',
              __version: '2026-08-02', __sortKey: 'SCRUM-000000002', __projectKey: 'SCRUM',
              key: 'SCRUM-2', id: '2', summary: 'Thermal margins', status: 'Done' })
            CREATE (i10:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/10', __name: 'SCRUM-10: Ten',
              __version: '2026-08-03', __sortKey: 'SCRUM-000000010', __projectKey: 'SCRUM',
              key: 'SCRUM-10', id: '10', summary: 'Ten' })
            CREATE (o3:SEItem:JiraIssue {
              __id: 'https://jira.example.com/rest/api/2/issue/3', __name: 'OTS-3: Another project',
              __version: '2026-08-04', __sortKey: 'OTS-000000003', __projectKey: 'OTS',
              key: 'OTS-3', id: '3', summary: 'Another project' })
            CREATE (stub:SEItem:JiraIssue:`__UNDEFINED` {
              __id: 'https://jira.example.com/rest/api/2/issue/100', __name: '<unresolved SCRUM-100>',
              __version: 'unresolved', __sortKey: 'SCRUM-000000100',
              key: 'SCRUM-100', id: '100', summary: 'Outside the configured projects' })
            CREATE (i1)-[:hasIssueType]->(t)
            CREATE (i2)-[:hasIssueType]->(t)
            CREATE (st:SEItem:JiraStatus {__id: 'https://jira.example.com/rest/api/2/status/1',
                                          __name: 'In Progress', __version: 'current',
                                          id: '1', name: 'In Progress'})
            CREATE (i1)-[:hasStatus]->(st)
            // SCRUM-1 -> SCRUM-2 -> SCRUM-10, so depth is observable, plus a link to the stub so
            // the walk has to draw an issue that was never imported.
            CREATE (i1)-[:linkedTo {linkId: 'l1', typeName: 'Relates'}]->(i2)
            CREATE (i2)-[:linkedTo {linkId: 'l2', typeName: 'Blocks'}]->(i10)
            CREATE (i1)-[:subTaskOf]->(stub)
            CREATE (i1)-[:__projection]->(:`__JiraProjection` {
              __id: 'https://jira.example.com/rest/api/2/issue/1#projection', customfield_1: 'WSS' })
            CREATE (i2)-[:__projection]->(:`__JiraProjection` {
              __id: 'https://jira.example.com/rest/api/2/issue/2#projection' })
        """.trimIndent()
    }
}
