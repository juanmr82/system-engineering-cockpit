package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.domain.SaveCommentsOutcome
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SystemLevelChange
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.ReviewProjection
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Acceptance criteria from docs/REQ_REVIEW.md §10, exercised against a real Neo4j Community image
// (CLAUDE.md §7). Container lifecycle owned explicitly — see ModulesFeatureTest for why.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class ReviewFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var doorsProjection: DoorsProjection
    private lateinit var reviewProjection: ReviewProjection
    private lateinit var metaWriter: MetaWriter

    private val moduleId = "review-module"

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking {
            MetaSchema.apply(graphDriver)
            seed()
        }
        doorsProjection = DoorsProjection(graphDriver)
        reviewProjection = ReviewProjection(graphDriver)
        metaWriter = MetaWriter(graphDriver, doorsProjection)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    /**
     * Every test starts from "nothing has been annotated yet".
     *
     * The container and its imported data are shared across the class (PER_CLASS) because seeding
     * a module per test would dominate the runtime, but Tier-2 data is what these tests write, so
     * leaving it behind would couple them to JUnit's method order. That this reset is a single
     * query is itself the R2 invariant under test.
     */
    @BeforeEach
    fun clearMeta(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (m:__Meta) DETACH DELETE m", emptyMap())) { }
    }

    /**
     * A module shaped like the real reference export: objects out of document order in creation
     * order (so ORDER BY __sortKey is actually doing something), a heading, a table cell, an
     * outgoing link to a sibling and one to an object no import has reached.
     *
     * `Object Type` is populated — a sanitised export blanks it and every type-dependent
     * assertion would then silently assert nothing (CLAUDE.md §10).
     */
    private suspend fun seed() {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (m:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}mid, __name: 'SRD', __version: 'current',
                    moduleFullPath: '/Level 1/SRD', url: ${'$'}mid
                })
                CREATE (r2:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'obj-2', __moduleUrl: ${'$'}mid, __name: 'SRD-2', __version: 'current',
                    __sortKey: '000002', id: 'SRD-2', objectNumber: '2', objectLevel: 2,
                    `Object Text`: 'The system shall do Y', `REQ. Priorität`: ''
                })
                CREATE (r1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'obj-1', __moduleUrl: ${'$'}mid, __name: 'SRD-1', __version: 'current',
                    __sortKey: '000001', id: 'SRD-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'The system shall do X', `REQ. Priorität`: 'High'
                })
                CREATE (h:DOORSObject:DOORSHeading:SEItem {
                    __id: 'obj-3', __moduleUrl: ${'$'}mid, __name: 'Scope', __version: 'current',
                    __sortKey: '000003', id: 'SRD-3', objectNumber: '3', objectLevel: 1
                })
                CREATE (c:DOORSObject:DOORSRequirement:DOORSTableCell:SEItem {
                    __id: 'obj-4', __moduleUrl: ${'$'}mid, __name: 'cell', __version: 'current',
                    __sortKey: '000004', id: 'SRD-4', objectNumber: '4', objectLevel: 3
                })
                CREATE (u:SEItem:__UNDEFINED {
                    __id: 'missing-1', __name: '<unresolved missing-1>', __version: 'current',
                    __moduleUrl: 'other-module'
                })
                CREATE (r1)-[:refersTo]->(r2)
                CREATE (r1)-[:refersTo]->(u)
                """.trimIndent(),
                mapOf("mid" to moduleId),
            ),
        ) { }
    }

    private fun rawProperties(itemId: String): Map<String, Any> = runBlocking {
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (n:SEItem {__id: \$id}) RETURN n", mapOf("id" to itemId)),
        ) { records -> records.single().get("n").asNode().asMap() }
    }

    // Criterion 1: document order comes from __sortKey, not from creation order or objectNumber.
    @Test
    fun `objects load in document order with their attributes and type wording`() = runBlocking {
        val page = reviewProjection.getModuleObjects(moduleId)

        assertEquals(4, page.total)
        assertFalse(page.truncated)
        assertEquals(listOf("SRD-1", "SRD-2", "SRD-3", "SRD-4"), page.rows.map { it.id })

        val first = page.rows.first()
        assertEquals("Requirement", first.type)
        assertEquals("The system shall do X", first.attributes.getValue("Object Text").toString().trim('"'))
        assertEquals("Heading", page.rows[2].type)

        // R5: the namespace is filtered out server-side, so no __ key can reach a column header.
        assertTrue(page.rows.all { row -> row.attributes.keys.none { it.startsWith("__") } })
        // id/objectNumber/objectLevel have dedicated columns and never double as attributes.
        assertTrue(page.rows.all { row -> row.attributes.keys.none { it in setOf("id", "objectNumber", "objectLevel") } })
    }

    // "" from DOORS means "attribute exists and is empty", which is not the same as absent.
    @Test
    fun `an empty attribute value survives as empty rather than being dropped`() = runBlocking {
        val row = reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-2" }

        assertTrue(row.attributes.containsKey("REQ. Priorität"))
        assertEquals("", row.attributes.getValue("REQ. Priorität").toString().trim('"'))
    }

    // The requirements-only filter (§11 O4): a heading is not a requirement, and neither is a
    // requirement that is also a table cell (attribute-policy-checks.md §1 uses the same scope).
    @Test
    fun `requirementLike excludes headings and table structure`() = runBlocking {
        val byId = reviewProjection.getModuleObjects(moduleId).rows.associateBy { it.id }

        assertTrue(byId.getValue("SRD-1").requirementLike)
        assertFalse(byId.getValue("SRD-3").requirementLike)
        assertFalse(byId.getValue("SRD-4").requirementLike)
    }

    // Criterion 11: an unresolved target is marked, not hidden, and never presented as a real
    // requirement. Incoming links are flagged incomplete because importers ingest out-links only.
    @Test
    fun `references separate resolved from not-yet-imported, and declare incoming incomplete`() = runBlocking {
        val rows = reviewProjection.getModuleObjects(moduleId).rows.associateBy { it.id }
        val outgoing = rows.getValue("SRD-1").references.outgoing

        assertEquals(2, outgoing.size)
        assertTrue(outgoing.single { it.id == "SRD-2" }.resolved)

        // R5: a placeholder's __name is its __id spelled out, so it must not arrive as the `id`
        // the References column displays. An unresolved target has no display id at all — the
        // wording and the module name are all the UI can honestly show.
        val unresolved = outgoing.single { !it.resolved }
        assertNull(unresolved.id)
        assertEquals(Ref.encode("missing-1"), unresolved.ref)

        assertFalse(rows.getValue("SRD-1").references.incomingComplete)
        assertEquals(listOf("SRD-1"), rows.getValue("SRD-2").references.incoming.map { it.id })

        val traces = reviewProjection.getTraces("obj-1", incoming = false)
        assertTrue(traces.complete)
        assertFalse(reviewProjection.getTraces("obj-2", incoming = true).complete)
    }

    // Criteria 5 and 7: one transaction, and the anchor node is byte-identical across the write.
    @Test
    fun `saving comments writes notes without touching the objects they hang off`() = runBlocking {
        val before = rawProperties("obj-1")

        val outcome = metaWriter.saveComments(
            moduleId,
            listOf(
                MetaWriter.CommentEditInput("obj-1", "Needs a rationale"),
                MetaWriter.CommentEditInput("obj-2", "Agreed at review"),
            ),
        )
        val saved = assertIs<SaveCommentsOutcome.Saved>(outcome)
        assertEquals(2, saved.comments.count { it.metaId != null })

        assertEquals(before, rawProperties("obj-1"))

        val row = reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-1" }
        assertEquals("Needs a rationale", row.comment?.text)
        assertNotNull(row.comment?.metaId)
    }

    // §5.2: exactly one comment per object. Editing must update the node, never add a second.
    @Test
    fun `editing a comment updates the same node and keeps its identity`() = runBlocking {
        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-3", "First")))
        val original = reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-3" }.comment

        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-3", "Second")))
        val edited = reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-3" }.comment

        assertEquals("Second", edited?.text)
        assertEquals(original?.metaId, edited?.metaId)

        val noteCount = graphDriver.executeRead(
            Query(
                "CYPHER 25 MATCH (:SEItem {__id: 'obj-3'})-[:__noteOn]->(n:__Note) RETURN count(n) AS c",
                emptyMap(),
            ),
        ) { it.single().get("c").asInt() }
        assertEquals(1, noteCount)
    }

    // Criterion 8: clearing a comment removes the node rather than storing an empty string.
    @Test
    fun `clearing a comment deletes its node`() = runBlocking {
        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-4", "Temporary")))
        assertNotNull(reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-4" }.comment)

        val outcome = metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-4", "   ")))

        assertNull(assertIs<SaveCommentsOutcome.Saved>(outcome).comments.single().metaId)
        assertNull(reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-4" }.comment)
    }

    // An arbitrary __id in the body must not be able to attach a note to any node in the graph.
    @Test
    fun `a comment on an object outside the module is refused, and nothing is written`() = runBlocking {
        val outcome = metaWriter.saveComments(
            moduleId,
            listOf(MetaWriter.CommentEditInput("missing-1", "Should not be stored")),
        )

        assertEquals(SaveCommentsOutcome.UnknownItems(listOf("missing-1")), outcome)
        val notes = graphDriver.executeRead(
            Query("CYPHER 25 MATCH (:SEItem {__id: 'missing-1'})-[:__noteOn]->(n) RETURN count(n) AS c", emptyMap()),
        ) { it.single().get("c").asInt() }
        assertEquals(0, notes)
    }

    // Criterion 4: mandatory set through the review dialog is the *same* :__Policy the Modules
    // dialog writes — one shape, one write path, visible from both.
    @Test
    fun `review settings write policies and attribute settings in one transaction`() = runBlocking {
        val outcome = metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = true, visible = true, verification = false),
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = false, visible = true, verification = true),
            ),
        )
        assertEquals(SaveModuleSettingsOutcome.Saved, outcome)

        val attributes = doorsProjection.getModuleAttributes(moduleId).associateBy { it.name }
        assertTrue(attributes.getValue("Object Text").mandatory)
        assertTrue(attributes.getValue("Object Text").visible)
        assertFalse(attributes.getValue("Object Text").verification)
        assertFalse(attributes.getValue("REQ. Priorität").mandatory)
        assertTrue(attributes.getValue("REQ. Priorität").verification)

        // The Modules dialog reads mandatory from :__Policy — the same node, not a copy.
        assertEquals(setOf("Object Text"), doorsProjection.getExistingMandatoryAttributes(moduleId))
    }

    // A dialog that does not show system level must not be able to clear it (SystemLevelChange).
    @Test
    fun `an attribute-settings save leaves the system level alone`() = runBlocking {
        metaWriter.saveModuleSettings(moduleId, SystemLevelChange.Set("L2"))
        assertEquals("L2", doorsProjection.getModuleDetail(moduleId)?.systemLevel)

        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = false, visible = true, verification = false),
            ),
        )

        assertEquals("L2", doorsProjection.getModuleDetail(moduleId)?.systemLevel)
    }

    // Criterion 8, second half: one query removes everything the app knows and nothing else.
    @Test
    fun `deleting all meta removes comments and attribute settings, leaving imported data intact`() = runBlocking {
        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-1", "A comment")))
        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = true, visible = true, verification = true),
            ),
        )
        val before = rawProperties("obj-1")

        graphDriver.executeWrite(Query("CYPHER 25 MATCH (m:__Meta) DETACH DELETE m", emptyMap())) { }

        assertEquals(before, rawProperties("obj-1"))
        assertEquals(4, reviewProjection.getModuleObjects(moduleId).total)
        assertNull(reviewProjection.getModuleObjects(moduleId).rows.first { it.id == "SRD-1" }.comment)
        val attributes = doorsProjection.getModuleAttributes(moduleId).associateBy { it.name }
        assertFalse(attributes.getValue("Object Text").visible)
        assertFalse(attributes.getValue("Object Text").mandatory)
    }

    // §7: the detail panel renders __moduleUrl as the module's name, per the R5 alias map.
    @Test
    fun `item detail names the module rather than exposing its url`() = runBlocking {
        val detail = reviewProjection.getItemDetail("obj-1")

        assertNotNull(detail)
        assertEquals("SRD", detail.moduleName)
        assertEquals(Ref.encode(moduleId), detail.moduleRef)
        assertEquals("Requirement", detail.type)
        assertTrue(detail.attributes.keys.none { it.startsWith("__") })
    }
}
