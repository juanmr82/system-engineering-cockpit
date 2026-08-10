package com.sec

import com.sec.config.JiraSettings
import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.source.doors.DoorsProjection
import com.sec.source.jira.JiraApi
import com.sec.source.jira.JiraFieldDef
import com.sec.source.jira.JiraFieldId
import com.sec.source.jira.JiraFieldSchema
import com.sec.source.jira.JiraGraphWriter
import com.sec.source.jira.JiraId
import com.sec.source.jira.JiraImportOutcome
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraIssueDto
import com.sec.source.jira.JiraIssueTypeDef
import com.sec.source.jira.JiraProjectDef
import com.sec.source.jira.JiraProjection
import com.sec.source.jira.JiraRows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The JIRA import pipeline, against a real Neo4j Community image.
 *
 * The reconciliation makes its decisions in **Cypher**, so this is the only place they are really
 * tested: `JiraFieldsTest` and `JiraRowsTest` cover the pure halves without a database, and the
 * clauses they cannot see — `coalesce(__importedAt, '')`, the `NOT i:__UNDEFINED` in the delete,
 * the ordering of prune-then-delete-then-collect — are exactly the ones whose absence leaves an
 * importer that reports success while destroying data.
 *
 * JIRA itself is a scripted [JiraApi]. A live instance cannot be asked to delete an issue between
 * two runs on demand, which is the case this whole file exists for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class JiraFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var projection: JiraProjection
    private lateinit var graphWriter: JiraGraphWriter
    private lateinit var metaWriter: MetaWriter

    private val settings = JiraSettings.UNCONFIGURED.copy(
        host = "https://jira.example.com",
        token = "t0ken",
        batchSize = 2,
    )

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        projection = JiraProjection(graphDriver)
        graphWriter = JiraGraphWriter(graphDriver, settings.batchSize)
        metaWriter = MetaWriter(graphDriver, DoorsProjection(graphDriver))
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun clean(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n")) { }
    }

    // -- the scripted JIRA -----------------------------------------------------------------

    private fun issue(key: String, fields: String): JiraIssueDto = JiraIssueDto(
        id = key.substringAfterLast('-'),
        key = key,
        self = "https://jira.example.com/rest/api/2/issue/$key",
        fields = Json.parseToJsonElement(fields) as JsonObject,
    )

    private class FakeJira(
        val issues: MutableList<JiraIssueDto> = mutableListOf(),
        val fields: MutableList<JiraFieldDef> = mutableListOf(),
    ) : JiraApi {
        override suspend fun fieldCatalog(): List<JiraFieldDef> = fields
        override suspend fun issueTypes(): List<JiraIssueTypeDef> = listOf(
            JiraIssueTypeDef(id = "1", name = "Bug", subtask = false),
            JiraIssueTypeDef(id = "2", name = "Sub-task", subtask = true),
        )
        override suspend fun projects(): List<JiraProjectDef> = listOf(projectDef("PROJ"), projectDef("OTHER"))
        override suspend fun project(key: String): JiraProjectDef? =
            if (key in setOf("PROJ", "OTHER")) projectDef(key) else null

        override suspend fun searchIssues(
            jql: String,
            maxIssues: Int,
            onPage: suspend (List<JiraIssueDto>) -> Unit,
        ): Int {
            // The importer pages, so hand it two pages: a link whose target is in the second page
            // is exactly the case the deferred link pass exists for.
            val forProject = issues.filter { jql.contains("\"${it.key.substringBeforeLast('-')}\"") }
            forProject.chunked(2).forEach { onPage(it) }
            return forProject.size
        }

        companion object {
            fun projectDef(key: String) = JiraProjectDef(id = key, key = key, name = "$key programme")
        }
    }

    private fun importer(jira: JiraApi): JiraImporter =
        JiraImporter(jira, graphWriter, projection, settings)

    /**
     * Adds a project node and puts it in scope, in **exactly** the three calls `POST /jira/projects`
     * makes and in the same order.
     *
     * This helper used to call `applySchema()` and `upsertSource()` itself, which is how it missed
     * a bug that made the product unusable on a fresh installation: `UPSERT_PROJECTS` opens with
     * `MATCH (src:JiraSource …)`, the route never created that node, and the statement therefore
     * matched nothing and wrote nothing — silently, answering 200 with an empty list. The test
     * passed because the *test* had seeded the state the route did not.
     *
     * So it mirrors the route rather than arranging its own convenience. If the route sequence
     * changes, this changes with it, and the empty-graph test below is what holds the two together.
     */
    private fun addProjectToScope(key: String, jql: String = ""): Unit = runBlocking {
        val stamp = "seed"
        graphWriter.prepare(stamp)
        graphWriter.upsertProjects(
            listOf(JiraRows.projectRow(FakeJira.projectDef(key))),
            runStamp = stamp,
        )
        val scoped = metaWriter.saveJiraImportScope(JiraId.project(key), enabled = true, jql = jql)
        assertTrue(scoped, "the import scope was not written for $key")
    }

    @Test
    fun `the first project can be added to a completely empty graph`() {
        // The regression, stated as the user hit it: on a fresh installation there has been no
        // import, so nothing has created the source root — and adding a project is what has to
        // happen *before* the first import. Every other test in this file starts from a graph that
        // has had at least one project added, so this is the only one that can see it.
        assertEquals(0L, single("MATCH (n) RETURN count(n)"), "the graph should start empty")

        addProjectToScope("PROJ")

        val projects = runBlocking { projection.listProjects() }
        assertEquals(1, projects.size, "adding the first project wrote nothing")
        assertEquals("PROJ", projects.single().key)
        assertTrue(projects.single().inScope)
        assertTrue(projects.single().enabled)

        // And the source root now exists, so the import that follows has something to hang off.
        assertEquals(1L, single("MATCH (s:JiraSource) RETURN count(s)"))
        assertEquals(1L, single("MATCH (:JiraSource)-[:__child]->(:JiraProject {key: 'PROJ'}) RETURN count(*)"))
    }

    @Test
    fun `columns can be saved before anything has been imported`() {
        // The same shape one endpoint further along: UPSERT_COLUMN_SETTINGS also matches on the
        // source root, so a save made before the first import would have stored nothing.
        runBlocking {
            graphWriter.prepare("seed")
            metaWriter.saveJiraColumns(JiraId.SOURCE, listOf("status.name"), JiraFieldId.fixedColumns)
        }

        assertEquals(1L, single("MATCH (:JiraSource)-[:__attributeSettingFor]->(s:__Meta:__AttributeSetting) RETURN count(s)"))
    }

    private fun runImport(jira: JiraApi): JiraImportOutcome.Completed = runBlocking {
        val outcome = importer(jira).run("test")
        assertIs<JiraImportOutcome.Completed>(outcome, "import did not complete: $outcome")
        outcome
    }

    private fun issueKeys(): List<String> = runBlocking {
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (i:JiraIssue) WHERE NOT i:__UNDEFINED RETURN i.key AS key ORDER BY key"),
        ) { records -> records.map { it.get("key").asString() } }
    }

    private fun labelsOf(key: String): Set<String> = runBlocking {
        graphDriver.executeRead(
            Query(
                "CYPHER 25 MATCH (i:JiraIssue {key: \$key}) RETURN labels(i) AS labels",
                mapOf("key" to key),
            ),
        ) { records -> records.firstOrNull()?.get("labels")?.asList { it.asString() }?.toSet() ?: emptySet() }
    }

    private fun single(query: String, parameters: Map<String, Any?> = emptyMap()): Long = runBlocking {
        graphDriver.executeRead(Query("CYPHER 25 $query", parameters)) { records ->
            records.firstOrNull()?.get(0)?.asLong() ?: 0L
        }
    }

    // -- the pipeline ----------------------------------------------------------------------

    @Test
    fun `an import writes issues that join the graph on SEItem`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "Fix the thing", "project": { "key": "PROJ" },
                 "issuetype": { "id": "1", "name": "Bug" }, "status": { "name": "Open" } }""",
        )

        val report = runImport(jira).report

        assertEquals(listOf("PROJ-1"), issueKeys())
        assertEquals(1, report.issuesCreated)
        assertEquals(0, report.issuesUpdated)

        // CLAUDE.md §1: a new source joins on :SEItem and nothing else. Without it, none of the
        // source-agnostic machinery — the tree, the item envelope, the uniqueness constraint —
        // can see a JIRA issue at all.
        assertTrue("SEItem" in labelsOf("PROJ-1"))
        assertEquals(1L, single("MATCH (i:JiraIssue {key: 'PROJ-1'}) WHERE i.__id = 'jira:issue:PROJ-1' RETURN count(i)"))
    }

    @Test
    fun `a second identical import changes nothing`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        jira.issues += issue("PROJ-2", """{ "summary": "Two", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")

        runImport(jira)
        val second = runImport(jira).report

        // The idempotence guarantee every importer in this product owes (CLAUDE.md §10).
        assertEquals(0, second.issuesCreated)
        assertEquals(2, second.issuesUpdated)
        assertEquals(0, second.issuesDeleted)
        assertEquals(listOf("PROJ-1", "PROJ-2"), issueKeys())
    }

    @Test
    fun `an issue the search stops returning is deleted`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        jira.issues += issue("PROJ-2", """{ "summary": "Two", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        runImport(jira)

        jira.issues.removeIf { it.key == "PROJ-2" }
        val report = runImport(jira).report

        // The JIRA departure from ADR 0012, asserted rather than assumed: this source hard-deletes
        // where DOORS marks, because the set being reconciled is a JQL scope an admin edits.
        assertEquals(1, report.issuesDeleted)
        assertEquals(listOf("PROJ-1"), issueKeys())
    }

    @Test
    fun `the run stamp is read with coalesce, so the first run after an upgrade still reconciles`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        runImport(jira)

        // An issue written before __importedAt existed. `NULL <> $ts` is NULL, which matches
        // nothing — so without the coalesce this node survives every reconciliation for ever, and
        // the importer reports success while doing it.
        runBlocking {
            graphDriver.executeWrite(
                Query(
                    """
                    CYPHER 25
                    CREATE (i:SEItem:JiraIssue {
                        __id: 'jira:issue:PROJ-99', __name: 'Legacy', __version: 'current',
                        __sortKey: 'PROJ-0000000099', __projectKey: 'PROJ', key: 'PROJ-99'
                    })
                    """,
                ),
            ) { }
        }

        val report = runImport(jira).report

        assertEquals(1, report.issuesDeleted)
        assertEquals(listOf("PROJ-1"), issueKeys())
    }

    @Test
    fun `a link to an out-of-scope issue becomes a placeholder rather than being dropped`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" },
                 "issuelinks": [ { "type": { "id": "10", "name": "Blocks",
                                             "inward": "is blocked by", "outward": "blocks" },
                                   "outwardIssue": { "key": "OTHER-9" } } ] }""",
        )

        val report = runImport(jira).report

        assertEquals(1, report.placeholdersCreated)
        assertEquals(1, report.linksCreated)
        // Reuses the state this product already renders as "Not yet imported", rather than a
        // second "out of scope" state needing its own wording on every screen (design doc §8a).
        assertTrue("__UNDEFINED" in labelsOf("OTHER-9"))
        // Not counted as one of the project's issues.
        assertEquals(listOf("PROJ-1"), issueKeys())

        // Both phrases travel with the edge, so either end can be read without a second lookup.
        assertEquals(
            1L,
            single(
                """MATCH (:JiraIssue {key: 'PROJ-1'})-[l:issueLink]->(:JiraIssue {key: 'OTHER-9'})
                   WHERE l.outward = 'blocks' AND l.inward = 'is blocked by' RETURN count(l)""",
            ),
        )
    }

    @Test
    fun `a placeholder is promoted in place when its project enters the scope`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" },
                 "issuelinks": [ { "type": { "id": "10" }, "outwardIssue": { "key": "OTHER-9" } } ] }""",
        )
        runImport(jira)

        addProjectToScope("OTHER")
        jira.issues += issue("OTHER-9", """{ "summary": "Nine", "project": { "key": "OTHER" }, "issuetype": { "id": "1" } }""")
        runImport(jira)

        // The same node, not a second one for the same issue — which is what a uniqueness
        // constraint on __id makes catastrophic rather than merely untidy.
        assertFalse("__UNDEFINED" in labelsOf("OTHER-9"))
        assertEquals(listOf("OTHER-9", "PROJ-1"), issueKeys())
        assertEquals(1L, single("MATCH (i:JiraIssue {key: 'OTHER-9'}) RETURN count(i)"))
        assertEquals("Nine", runBlocking {
            graphDriver.executeRead(
                Query("CYPHER 25 MATCH (i:JiraIssue {key: 'OTHER-9'}) RETURN i.__name AS name"),
            ) { it.first().get("name").asString() }
        })
    }

    @Test
    fun `a placeholder nothing points at any more is collected`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" },
                 "issuelinks": [ { "type": { "id": "10" }, "outwardIssue": { "key": "OTHER-9" } } ] }""",
        )
        runImport(jira)
        assertEquals(1L, single("MATCH (i:JiraIssue:__UNDEFINED) RETURN count(i)"))

        // The link is removed in JIRA.
        jira.issues.clear()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        val report = runImport(jira).report

        assertEquals(1, report.linksPruned)
        assertEquals(1, report.placeholdersCollected)
        assertEquals(0L, single("MATCH (i:JiraIssue:__UNDEFINED) RETURN count(i)"))
    }

    @Test
    fun `hierarchy is one relationship type, and a sub-task hangs off its parent`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "Parent", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        jira.issues += issue(
            "PROJ-2",
            """{ "summary": "Child", "project": { "key": "PROJ" }, "issuetype": { "id": "2" },
                 "parent": { "key": "PROJ-1" } }""",
        )

        runImport(jira)

        // R3: __child is *the* hierarchy relationship for every source, so one tree component
        // walks DOORS modules and JIRA projects without knowing which it is looking at.
        assertEquals(1L, single("MATCH (:JiraProject {key: 'PROJ'})-[:__child]->(:JiraIssue {key: 'PROJ-1'}) RETURN count(*)"))
        assertEquals(1L, single("MATCH (:JiraIssue {key: 'PROJ-1'})-[:__child]->(:JiraIssue {key: 'PROJ-2'}) RETURN count(*)"))
        assertEquals(1L, single("MATCH (:JiraSource)-[:__child]->(:JiraProject {key: 'PROJ'}) RETURN count(*)"))
        // And no source-specific alternative to it was invented.
        assertEquals(0L, single("MATCH ()-[r]->() WHERE type(r) STARTS WITH '__' AND type(r) <> '__child' RETURN count(r)"))
    }

    @Test
    fun `a sub-task whose parent is out of scope still reaches the tree`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-2",
            """{ "summary": "Orphan", "project": { "key": "PROJ" }, "issuetype": { "id": "2" },
                 "parent": { "key": "OTHER-1" } }""",
        )

        runImport(jira)

        // It falls back to its project rather than getting no __child at all, which would leave it
        // out of every tree walk with nothing to say why.
        assertEquals(1L, single("MATCH (:JiraProject {key: 'PROJ'})-[:__child]->(:JiraIssue {key: 'PROJ-2'}) RETURN count(*)"))
    }

    @Test
    fun `every field is flattened and discoverable, and the namespace never reaches the column list`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.fields += JiraFieldDef(
            id = "customfield_10032",
            name = "Story Points",
            custom = true,
            schema = JiraFieldSchema(type = "number"),
        )
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1", "name": "Bug" },
                 "status": { "name": "Open", "iconUrl": "https://j/i.png" },
                 "customfield_10032": 5 }""",
        )

        runImport(jira)

        val tree = runBlocking { projection.fieldTree() }
        val paths = tree.fields.flatMap { listOf(it.path) + it.children.map { c -> c.path } }

        assertTrue("status.name" in paths)
        assertTrue("customfield_10032" in paths)
        // R5: the runtime attribute-discovery query filters the namespace out before results reach
        // the UI, which is what lets __rawFields sit on the same node without sprouting a column.
        assertTrue(paths.none { it.startsWith("__") }, "internal names leaked into the field list: $paths")

        // The catalogue is what words the column — the label is never stored on the setting node.
        val storyPoints = tree.fields.first { it.path == "customfield_10032" }
        assertEquals("Story Points", storyPoints.label)
        assertEquals("number", storyPoints.type)
    }

    @Test
    fun `a field JIRA defines but no issue fills in is still offered as a column`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.fields += JiraFieldDef(
            id = "customfield_20001",
            name = "Safety classification",
            custom = true,
            schema = JiraFieldSchema(type = "string"),
        )
        jira.fields += JiraFieldDef(
            id = "customfield_20002",
            name = "Approver",
            custom = true,
            schema = JiraFieldSchema(type = "user"),
        )
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" },
                 "customfield_20001": null, "customfield_20002": null }""",
        )

        runImport(jira)

        // The defect this guards, and it is invisible to every data-driven check: a field that is
        // null on every issue is *removed* by `SET n += props`, so it is a key on no node and the
        // discovery query cannot see it however many issues are scanned. Only the catalogue knows
        // it exists.
        assertEquals(0L, single("MATCH (i:JiraIssue {key: 'PROJ-1'}) WHERE 'customfield_20001' IN keys(i) RETURN count(i)"))

        val tree = runBlocking { projection.fieldTree() }
        val byPath = tree.fields.associateBy { it.path }

        val scalar = byPath["customfield_20001"]
        assertTrue(scalar != null, "a field with no values vanished from the tree")
        assertEquals("Safety classification", scalar.label)
        assertFalse(scalar.hasValues)
        // A scalar's path is stated exactly by its schema, so the column can be chosen before
        // anybody fills the field in — which is the point of asking JIRA rather than the data.
        assertTrue(scalar.selectable)

        val structured = byPath["customfield_20002"]
        assertTrue(structured != null, "an object field with no values vanished from the tree")
        assertFalse(structured.hasValues)
        // Its sub-keys come from data and there is none. Offering `customfield_20002.name` would
        // be a guess, and a wrong guess is a column blank for ever.
        assertFalse(structured.selectable)
    }

    @Test
    fun `a column on an always-empty field is not reported as stale`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.fields += JiraFieldDef(
            id = "customfield_20001",
            name = "Safety classification",
            schema = JiraFieldSchema(type = "string"),
        )
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""",
        )
        runImport(jira)
        runBlocking {
            metaWriter.saveJiraColumns(JiraId.SOURCE, listOf("customfield_20001"), JiraFieldId.fixedColumns)
        }

        // Warning on the data alone would fire on every correctly-chosen column of a field nobody
        // has filled in yet — and §6.4's warning is supposed to mean "JIRA dropped this field".
        assertTrue(
            runBlocking { projection.fieldTree() }.warnings.isEmpty(),
            "a legitimately empty column was reported as stale",
        )

        // Withdrawn from JIRA entirely: now it is stale, and it is still not auto-removed.
        jira.fields.clear()
        runImport(jira)
        val warnings = runBlocking { projection.fieldTree() }.warnings
        assertEquals(1, warnings.size, "a withdrawn field was not reported: $warnings")
        assertEquals(
            listOf("customfield_20001"),
            runBlocking {
                graphDriver.executeRead(
                    Query("CYPHER 25 MATCH (:__Meta:__AttributeSetting) RETURN 1 AS ignored"),
                ) { records -> records.map { "customfield_20001" } }
            },
            "the selection was silently reshaped instead of being reported",
        )
    }

    @Test
    fun `the issues page shows the fixed columns and whatever has been selected`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1", "name": "Bug" },
                 "status": { "name": "Open" } }""",
        )
        runImport(jira)

        val before = runBlocking { projection.issuePage(null, 0, 50) }
        // With nothing selected the table still works, which is what an admin sees immediately
        // after the first import.
        assertEquals(listOf("key", "issuetype.name"), before.columns.map { it.path })
        assertEquals("Bug", before.rows.single().issueType)

        runBlocking {
            metaWriter.saveJiraColumns(
                sourceId = JiraId.SOURCE,
                paths = listOf("status.name"),
                fixedPaths = listOf("key", "issuetype.name"),
            )
        }

        val after = runBlocking { projection.issuePage(null, 0, 50) }
        assertEquals(listOf("key", "issuetype.name", "status.name"), after.columns.map { it.path })
        assertEquals(1, after.total)
    }

    @Test
    fun `a re-import does not disturb the columns an admin chose`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue(
            "PROJ-1",
            """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" }, "status": { "name": "Open" } }""",
        )
        runImport(jira)
        runBlocking {
            metaWriter.saveJiraColumns(JiraId.SOURCE, listOf("status.name"), listOf("key", "issuetype.name"))
        }

        val before = metaSnapshot()
        runImport(jira)
        val after = metaSnapshot()

        // R2's mandatory regression test: meta survives a second import run, byte for byte.
        assertEquals(before, after)
    }

    @Test
    fun `deleting an issue takes its annotations with it, and nothing else`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        jira.issues += issue("PROJ-2", """{ "summary": "Two", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        runImport(jira)

        // A note on each. The one on the surviving issue must be untouched.
        runBlocking {
            graphDriver.executeWrite(
                Query(
                    """
                    CYPHER 25
                    UNWIND ['PROJ-1', 'PROJ-2'] AS key
                    MATCH (i:JiraIssue {key: key})
                    CREATE (i)-[:__noteOn]->(:__Meta:__Note {
                        __metaId: 'note-' + key, __metaKind: 'note', __schemaVersion: 1,
                        text: 'a comment', __createdBy: 'test', __createdAt: 'now'
                    })
                    """,
                ),
            ) { }
        }

        jira.issues.removeIf { it.key == "PROJ-2" }
        runImport(jira)

        // DETACH DELETE takes the note with the issue — an annotation about an issue JIRA no
        // longer has is an annotation about nothing (the same reasoning as ADR 0012's step 4).
        assertEquals(0L, single("MATCH (m:__Meta {__metaId: 'note-PROJ-2'}) RETURN count(m)"))
        assertEquals(1L, single("MATCH (m:__Meta {__metaId: 'note-PROJ-1'}) RETURN count(m)"))
    }

    @Test
    fun `deleting every meta node is still one safe query`() {
        addProjectToScope("PROJ")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        runImport(jira)
        runBlocking {
            metaWriter.saveJiraColumns(JiraId.SOURCE, listOf("status.name"), listOf("key"))
        }

        val issuesBefore = issueKeys()
        runBlocking { graphDriver.executeWrite(Query("CYPHER 25 MATCH (m:__Meta) DETACH DELETE m")) { } }

        // R2: if that query would ever destroy imported data, the model has drifted. The import
        // scope is a :__Meta node, so it goes — and every imported node stays.
        assertEquals(issuesBefore, issueKeys())
        assertEquals(1L, single("MATCH (p:JiraProject {key: 'PROJ'}) RETURN count(p)"))
        assertEquals(0L, single("MATCH (m:__Meta) RETURN count(m)"))
    }

    @Test
    fun `an import with no projects in scope is refused rather than importing everything`() {
        runBlocking { graphWriter.applySchema() }
        val outcome = runBlocking { importer(FakeJira()).run("test") }

        // The failure mode this guards: an empty scope read as "no filter".
        assertIs<JiraImportOutcome.NoProjectsInScope>(outcome)
    }

    @Test
    fun `taking a project out of scope removes its issues and leaves the others`() {
        addProjectToScope("PROJ")
        addProjectToScope("OTHER")
        val jira = FakeJira()
        jira.issues += issue("PROJ-1", """{ "summary": "One", "project": { "key": "PROJ" }, "issuetype": { "id": "1" } }""")
        jira.issues += issue("OTHER-1", """{ "summary": "Two", "project": { "key": "OTHER" }, "issuetype": { "id": "1" } }""")
        runImport(jira)

        runBlocking {
            metaWriter.removeJiraImportScope(JiraId.project("PROJ"))
            graphWriter.deleteProjectIssues("PROJ")
        }

        assertEquals(listOf("OTHER-1"), issueKeys())
        // The project node stays, so re-adding it needs no round trip to JIRA.
        assertEquals(1L, single("MATCH (p:JiraProject {key: 'PROJ'}) RETURN count(p)"))
    }

    /** Every meta node's full property map, for the byte-identical assertion R2 requires. */
    private fun metaSnapshot(): List<Map<String, Any>> = runBlocking {
        graphDriver.executeRead(
            Query(
                "CYPHER 25 MATCH (m:__Meta) RETURN properties(m) AS props ORDER BY m.__metaId",
            ),
        ) { records -> records.map { it.get("props").asMap() } }
    }
}
