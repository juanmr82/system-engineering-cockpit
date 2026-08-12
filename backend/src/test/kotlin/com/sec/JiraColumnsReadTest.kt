package com.sec

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.source.jira.JiraColumnStore
import com.sec.source.jira.JiraFieldsProjection
import kotlinx.coroutines.runBlocking
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
 * The field catalogue and the chosen columns, against a real Neo4j Community image (spec §13.3,
 * §13.4).
 *
 * The two are tested together because the interesting cases are exactly where they meet: a column
 * naming a field the catalogue no longer has, and a catalogue entry that cannot be a column at all.
 * Neither is reachable from either half on its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class JiraColumnsReadTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var fields: JiraFieldsProjection
    private lateinit var columns: JiraColumnStore

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        fields = JiraFieldsProjection(graphDriver)
        columns = JiraColumnStore(graphDriver)
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

    // -- the catalogue -------------------------------------------------------------------------

    /**
     * A field with no schema is not offerable, and `issuekey` is the reason the rule exists.
     *
     * It duplicates the fixed Key column, `thumbnail` is not a data field, and neither can be
     * rendered — so a picker that listed them would offer a choice it cannot honour.
     */
    @Test
    fun `the catalogue omits fields that cannot be a column`() = runBlocking {
        val offered = fields.list().map { it.fieldId }

        assertFalse("issuekey" in offered)
        assertEquals(
            listOf("assignee", "customfield_1", "customfield_2", "labels", "summary"),
            offered.sorted(),
        )
    }

    /** Ordered by name, because the dialog is a list a person scans rather than queries. */
    @Test
    fun `the catalogue comes back in name order`() = runBlocking {
        assertEquals(
            listOf("Assignee", "Classification", "Classification", "Labels", "Summary"),
            fields.list().map { it.name },
        )
    }

    /**
     * Fifteen names cover thirty-three fields on the reference instance.
     *
     * The server marks the collision because only the server sees the whole list at once; a dialog
     * rendering one row at a time cannot tell that another row shares its name.
     */
    @Test
    fun `a name shared by two fields is marked ambiguous`() = runBlocking {
        val byId = fields.list().associateBy { it.fieldId }

        assertTrue(byId.getValue("customfield_1").ambiguousName)
        assertTrue(byId.getValue("customfield_2").ambiguousName)
        assertFalse(byId.getValue("summary").ambiguousName)
    }

    @Test
    fun `a custom field says so, and carries its element type`() = runBlocking {
        val byId = fields.list().associateBy { it.fieldId }

        assertTrue(byId.getValue("customfield_1").custom)
        assertFalse(byId.getValue("summary").custom)
        assertEquals("string", byId.getValue("labels").schemaItems)
    }

    // -- the chosen columns --------------------------------------------------------------------

    @Test
    fun `columns come back in the order they were chosen, not the catalogue's`() = runBlocking {
        val described = fields.describe(listOf("summary", "assignee", "labels"))

        assertEquals(listOf("summary", "assignee", "labels"), described.map { it.fieldId })
        assertEquals(listOf("Summary", "Assignee", "Labels"), described.map { it.name })
    }

    /** The rule the client renders from: an array is not orderable, a scalar is. */
    @Test
    fun `a column says whether it can be sorted by`() = runBlocking {
        val byId = fields.describe(listOf("summary", "labels")).associateBy { it.fieldId }

        assertTrue(byId.getValue("summary").sortable)
        assertFalse(byId.getValue("labels").sortable)
    }

    /**
     * The case §13.4 exists for: a column naming a field JIRA has dropped.
     *
     * It is returned rather than removed. The user chose it, and a column that vanished on its own
     * reads as a bug — so it renders with the id as its name, marked stale and never sortable.
     */
    @Test
    fun `a column whose field is gone is returned, marked stale`() = runBlocking {
        val described = fields.describe(listOf("summary", "customfield_999"))

        assertEquals(listOf("summary", "customfield_999"), described.map { it.fieldId })
        assertFalse(described[0].stale)
        assertTrue(described[1].stale)
        // The id is the only name left, and it is a legitimate thing to show: it is source data.
        assertEquals("customfield_999", described[1].name)
        assertFalse(described[1].sortable)
    }

    // -- the store -----------------------------------------------------------------------------

    /**
     * Nothing stored and nothing chosen are different answers.
     *
     * Null means the picker has never been used, which the API turns into the defaults; an empty
     * list means somebody unticked everything, and giving them six columns back would be the
     * application overruling a choice it asked for.
     */
    @Test
    fun `an unconfigured store answers null, an emptied one answers empty`() = runBlocking {
        assertNull(columns.fieldIds())

        columns.saveFieldIds(emptyList(), updatedBy = "test").getOrThrow()

        assertEquals(emptyList(), columns.fieldIds())
    }

    @Test
    fun `saving replaces the whole list, order included`() = runBlocking {
        columns.saveFieldIds(listOf("summary", "assignee"), updatedBy = "test").getOrThrow()
        assertEquals(listOf("summary", "assignee"), columns.fieldIds())

        columns.saveFieldIds(listOf("assignee", "labels"), updatedBy = "test").getOrThrow()
        assertEquals(listOf("assignee", "labels"), columns.fieldIds())
    }

    /** A client bug, not a user error: the intent is unambiguous, so it is cleaned rather than 400ed. */
    @Test
    fun `a repeated column is stored once`() = runBlocking {
        columns.saveFieldIds(listOf("summary", "summary", " "), updatedBy = "test").getOrThrow()

        assertEquals(listOf("summary"), columns.fieldIds())
    }

    /**
     * The injection boundary. A field id becomes a Cypher property key through dynamic access, so
     * anything that is not one is refused before it can be stored — from where it would break every
     * later request rather than the one that introduced it.
     */
    @Test
    fun `an id that is not a field id never reaches the graph`() = runBlocking {
        val result = columns.saveFieldIds(listOf("summary", "not an id"), updatedBy = "test")

        assertTrue(result.isFailure)
        assertNull(columns.fieldIds())
    }

    private companion object {
        /**
         * Five offerable fields and one that is not, written by hand.
         *
         * The two `Classification` entries are the ambiguity case, `labels` the array case, and
         * `issuekey` the no-schema case — the three shapes the reference instance actually has.
         * Literals on purpose: a fixture built from the constants under test would let a wrong
         * constant pass (backend/CLAUDE.md).
         */
        val FIXTURE = """
            CYPHER 25
            CREATE (:SEItem:JiraField {__id: 'https://jira.example.com/rest/api/2/field/summary',
                                       __name: 'Summary', __version: 'current',
                                       id: 'summary', name: 'Summary', custom: false,
                                       schemaType: 'string'})
            CREATE (:SEItem:JiraField {__id: 'https://jira.example.com/rest/api/2/field/assignee',
                                       __name: 'Assignee', __version: 'current',
                                       id: 'assignee', name: 'Assignee', custom: false,
                                       schemaType: 'user'})
            CREATE (:SEItem:JiraField {__id: 'https://jira.example.com/rest/api/2/field/labels',
                                       __name: 'Labels', __version: 'current',
                                       id: 'labels', name: 'Labels', custom: false,
                                       schemaType: 'array', schemaItems: 'string'})
            CREATE (:SEItem:JiraField {__id: 'https://jira.example.com/rest/api/2/field/customfield_1',
                                       __name: 'Classification', __version: 'current',
                                       id: 'customfield_1', name: 'Classification', custom: true,
                                       schemaType: 'option'})
            CREATE (:SEItem:JiraField {__id: 'https://jira.example.com/rest/api/2/field/customfield_2',
                                       __name: 'Classification', __version: 'current',
                                       id: 'customfield_2', name: 'Classification', custom: true,
                                       schemaType: 'option'})
            CREATE (:SEItem:JiraField {__id: 'https://jira.example.com/rest/api/2/field/issuekey',
                                       __name: 'Key', __version: 'current',
                                       id: 'issuekey', name: 'Key', custom: false})
        """
    }
}
