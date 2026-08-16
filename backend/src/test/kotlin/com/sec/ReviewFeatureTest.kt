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
import com.sec.security.AccessSet
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

    // This feature's own tests are not about access control (that is AccessControlFeatureTest) —
    // every call here stands in for a caller the phase-2 predicate lets straight through.
    private val seesAll = AccessSet(seesAll = true, categoryIds = emptyList())

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
        val page = reviewProjection.getModuleObjects(moduleId, seesAll)

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
        val row = reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-2" }

        assertTrue(row.attributes.containsKey("REQ. Priorität"))
        assertEquals("", row.attributes.getValue("REQ. Priorität").toString().trim('"'))
    }

    // The requirements-only filter (§11 O4): a heading is not a requirement, and neither is a
    // requirement that is also a table cell (attribute-policy-checks.md §1 uses the same scope).
    @Test
    fun `requirementLike excludes headings and table structure`() = runBlocking {
        val byId = reviewProjection.getModuleObjects(moduleId, seesAll).rows.associateBy { it.id }

        assertTrue(byId.getValue("SRD-1").requirementLike)
        assertFalse(byId.getValue("SRD-3").requirementLike)
        assertFalse(byId.getValue("SRD-4").requirementLike)
    }

    // Criterion 11: an unresolved target is marked, not hidden, and never presented as a real
    // requirement. Incoming links are flagged incomplete because importers ingest out-links only.
    @Test
    fun `references separate resolved from not-yet-imported, and declare incoming incomplete`() = runBlocking {
        val rows = reviewProjection.getModuleObjects(moduleId, seesAll).rows.associateBy { it.id }
        val outgoing = rows.getValue("SRD-1").references.outgoing

        assertEquals(2, outgoing.size)
        assertTrue(outgoing.single { it.id == "SRD-2" }.resolved)

        // R5: a placeholder's __name is its __id spelled out, so it must not arrive as the `id`
        // the References column displays. An unresolved target has no display id at all — the
        // wording and the module name are all the UI can honestly show.
        val unresolved = outgoing.single { !it.resolved }
        assertNull(unresolved.id)
        assertEquals(Ref.encode("missing-1"), unresolved.ref)

        assertTrue(rows.getValue("SRD-1").references.incomingComplete)
        assertEquals(listOf("SRD-1"), rows.getValue("SRD-2").references.incoming.map { it.id })

        val traces = reviewProjection.getTraces("obj-1", incoming = false, access = seesAll)
        assertTrue(traces.complete)
        assertFalse(reviewProjection.getTraces("obj-2", incoming = true, access = seesAll).complete)
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
            access = seesAll,
        )
        val saved = assertIs<SaveCommentsOutcome.Saved>(outcome)
        assertEquals(2, saved.comments.count { it.metaId != null })

        assertEquals(before, rawProperties("obj-1"))

        val row = reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-1" }
        assertEquals("Needs a rationale", row.comment?.text)
        assertNotNull(row.comment?.metaId)
    }

    // §5.2: exactly one comment per object. Editing must update the node, never add a second.
    @Test
    fun `editing a comment updates the same node and keeps its identity`() = runBlocking {
        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-3", "First")), access = seesAll)
        val original = reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-3" }.comment

        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-3", "Second")), access = seesAll)
        val edited = reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-3" }.comment

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
        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-4", "Temporary")), access = seesAll)
        assertNotNull(reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-4" }.comment)

        val outcome = metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-4", "   ")), access = seesAll)

        assertNull(assertIs<SaveCommentsOutcome.Saved>(outcome).comments.single().metaId)
        assertNull(reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-4" }.comment)
    }

    // An arbitrary __id in the body must not be able to attach a note to any node in the graph.
    @Test
    fun `a comment on an object outside the module is refused, and nothing is written`() = runBlocking {
        val outcome = metaWriter.saveComments(
            moduleId,
            listOf(MetaWriter.CommentEditInput("missing-1", "Should not be stored")),
            access = seesAll,
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
                MetaWriter.AttributeSettingInput("Object Text", mandatory = true, visible = true, verification = false, excludedFromOpenPoints = false),
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = false, visible = true, verification = true, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )
        assertEquals(SaveModuleSettingsOutcome.Saved, outcome)

        val attributes = doorsProjection.getModuleAttributes(moduleId, access = seesAll).associateBy { it.name }
        assertTrue(attributes.getValue("Object Text").mandatory)
        assertTrue(attributes.getValue("Object Text").visible)
        assertFalse(attributes.getValue("Object Text").verification)
        assertFalse(attributes.getValue("REQ. Priorität").mandatory)
        assertTrue(attributes.getValue("REQ. Priorität").verification)

        // The Modules dialog reads mandatory from :__Policy — the same node, not a copy.
        assertEquals(setOf("Object Text"), doorsProjection.getExistingMandatoryAttributes(moduleId, access = seesAll))
    }

    /**
     * The **fixed** check: an object that never got a real `Object Type` carries `DOORSTBD`, and
     * that is a defect in the export rather than a state to live with (`REQ_REVIEW.md` §5.3).
     *
     * It is not configurable, so unlike the mandatory rules it reports on a module nobody has set
     * up — which is the whole reason it exists. The exclusions are the interesting part and are
     * what this test is really for: DOORS does not type the cells and rows of an embedded table,
     * and an `:__UNDEFINED` placeholder has no `Object Type` to be wrong, so reporting either
     * would be reporting on the importer's own bookkeeping.
     */
    @Test
    fun `an untyped object is reported, unless it is table structure or a placeholder`() = runBlocking {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (m:DOORSModule {__id: ${'$'}mid})
                CREATE (t:DOORSObject:DOORSTBD:SEItem {
                    __id: 'tbd-1', __moduleUrl: ${'$'}mid, __name: 'Untyped', __version: 'current',
                    __sortKey: '000005', id: 'SRD-5', objectNumber: '5', objectLevel: 1
                })
                CREATE (tc:DOORSObject:DOORSTBD:DOORSTableCell:SEItem {
                    __id: 'tbd-2', __moduleUrl: ${'$'}mid, __name: 'Untyped cell', __version: 'current',
                    __sortKey: '000006', id: 'SRD-6', objectNumber: '6', objectLevel: 1
                })
                CREATE (u:DOORSObject:DOORSTBD:__UNDEFINED:SEItem {
                    __id: 'tbd-3', __moduleUrl: ${'$'}mid, __name: 'Placeholder', __version: 'current',
                    __sortKey: '000007', id: 'SRD-7', objectNumber: '7', objectLevel: 1
                })
                """.trimIndent(),
                mapOf("mid" to moduleId),
            ),
        ) { }

        val rows = reviewProjection.getModuleObjects(moduleId, seesAll).rows.associateBy { it.id }
        assertEquals(listOf("Object Type shall not be TBD"), rows.getValue("SRD-5").issues)
        assertEquals(emptyList(), rows.getValue("SRD-6").issues)
        assertEquals(emptyList(), rows.getValue("SRD-7").issues)
        // A typed requirement is untouched by the fixed rule.
        assertEquals(emptyList(), rows.getValue("SRD-1").issues)

        graphDriver.executeWrite(
            Query(
                "CYPHER 25 MATCH (n:SEItem) WHERE n.__id IN ['tbd-1','tbd-2','tbd-3'] DETACH DELETE n",
                emptyMap(),
            ),
        ) { }
    }

    /**
     * The mandatory-attribute check, computed on read (`attribute-policy-checks.md`).
     *
     * The seed is built for exactly this: `REQ. Priorität` is `'High'` on SRD-1, `''` on SRD-2,
     * absent on the heading SRD-3, and absent on the table cell SRD-4. So one policy exercises
     * every branch at once — filled, blank, out of scope by label, and excluded as table
     * structure.
     *
     * **The live modules cannot test this.** A sanitised export fills every attribute on every
     * requirement, so the reference module reports 0 violations over 437 requirements in scope
     * (CLAUDE.md §10). This test is the only thing that holds the behaviour up.
     */
    @Test
    fun `missing mandatory values are reported per row, in scope only`() = runBlocking {
        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = true, visible = true, verification = false, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )

        val rows = reviewProjection.getModuleObjects(moduleId, seesAll).rows.associateBy { it.id }

        // Blank counts as missing: DOORS "" means "exists and is empty", which the table renders
        // as an empty cell but the check treats as a violation.
        assertEquals(listOf("REQ. Priorität"), rows.getValue("SRD-2").issues)
        // Filled, so nothing to report.
        assertEquals(emptyList(), rows.getValue("SRD-1").issues)
        // A heading is not a requirement and is never reported, though the attribute is absent.
        assertEquals(emptyList(), rows.getValue("SRD-3").issues)
        // Table structure is excluded whatever the policy's scope says — a cell is a fragment of
        // a requirement's layout, not a requirement.
        assertEquals(emptyList(), rows.getValue("SRD-4").issues)
    }

    /**
     * The property that decides where this check runs: the verdict follows the *policy*, which is
     * user-editable configuration, not the import.
     *
     * Nothing is re-imported between the two reads here and every answer changes — which is why
     * the result is computed on read and never stored, and why there is no backfill to run for
     * data that is already in the graph (R2).
     */
    @Test
    fun `the verdict changes when the policy changes, with no re-import`() = runBlocking {
        assertEquals(
            emptyList(),
            reviewProjection.getModuleObjects(moduleId, seesAll).rows.single { it.id == "SRD-2" }.issues,
        )

        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = true, visible = true, verification = false, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )
        assertEquals(
            listOf("REQ. Priorität"),
            reviewProjection.getModuleObjects(moduleId, seesAll).rows.single { it.id == "SRD-2" }.issues,
        )

        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = false, visible = true, verification = false, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )
        assertEquals(
            emptyList(),
            reviewProjection.getModuleObjects(moduleId, seesAll).rows.single { it.id == "SRD-2" }.issues,
        )
    }

    /**
     * Un-ticking one mandatory attribute must not clear the others.
     *
     * The review dialog posts the **absolute state of every attribute**, so a save that turns one
     * row off arrives as one `false` among many `true`s. If the writer treated that list as
     * "replace everything" — or lost the `true`s on the way — a reviewer clearing a single
     * checkbox would silently wipe the module's whole policy, and the only sign would be an
     * Issues column that quietly went empty.
     */
    @Test
    fun `turning one mandatory attribute off leaves the others alone`() = runBlocking {
        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = true, visible = true, verification = false, excludedFromOpenPoints = false),
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = true, visible = true, verification = false, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )
        assertEquals(
            setOf("Object Text", "REQ. Priorität"),
            doorsProjection.getExistingMandatoryAttributes(moduleId, access = seesAll),
        )

        // One row flipped off, every other row resent unchanged — exactly what the dialog sends.
        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = true, visible = true, verification = false, excludedFromOpenPoints = false),
                MetaWriter.AttributeSettingInput("REQ. Priorität", mandatory = false, visible = true, verification = false, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )

        assertEquals(setOf("Object Text"), doorsProjection.getExistingMandatoryAttributes(moduleId, access = seesAll))
        // The visible flags of both rows survive the mandatory change untouched.
        val attributes = doorsProjection.getModuleAttributes(moduleId, access = seesAll).associateBy { it.name }
        assertTrue(attributes.getValue("Object Text").visible)
        assertTrue(attributes.getValue("REQ. Priorität").visible)
    }

    // A dialog that does not show system level must not be able to clear it (SystemLevelChange).
    @Test
    fun `an attribute-settings save leaves the system level alone`() = runBlocking {
        metaWriter.saveModuleSettings(moduleId, SystemLevelChange.Set("L2"), access = seesAll)
        assertEquals("L2", doorsProjection.getModuleDetail(moduleId, access = seesAll)?.systemLevel)

        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = false, visible = true, verification = false, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )

        assertEquals("L2", doorsProjection.getModuleDetail(moduleId, access = seesAll)?.systemLevel)
    }

    // Criterion 8, second half: one query removes everything the app knows and nothing else.
    @Test
    fun `deleting all meta removes comments and attribute settings, leaving imported data intact`() = runBlocking {
        metaWriter.saveComments(moduleId, listOf(MetaWriter.CommentEditInput("obj-1", "A comment")), access = seesAll)
        metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevel = SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput("Object Text", mandatory = true, visible = true, verification = true, excludedFromOpenPoints = false),
            ),
            access = seesAll,
        )
        val before = rawProperties("obj-1")

        graphDriver.executeWrite(Query("CYPHER 25 MATCH (m:__Meta) DETACH DELETE m", emptyMap())) { }

        assertEquals(before, rawProperties("obj-1"))
        assertEquals(4, reviewProjection.getModuleObjects(moduleId, seesAll).total)
        assertNull(reviewProjection.getModuleObjects(moduleId, seesAll).rows.first { it.id == "SRD-1" }.comment)
        val attributes = doorsProjection.getModuleAttributes(moduleId, access = seesAll).associateBy { it.name }
        assertFalse(attributes.getValue("Object Text").visible)
        assertFalse(attributes.getValue("Object Text").mandatory)
    }

    // §7: the detail panel renders __moduleUrl as the module's name, per the R5 alias map.
    @Test
    fun `item detail names the module rather than exposing its url`() = runBlocking {
        val detail = reviewProjection.getItemDetail("obj-1", access = seesAll)

        assertNotNull(detail)
        assertEquals("SRD", detail.moduleName)
        assertEquals(Ref.encode(moduleId), detail.moduleRef)
        assertEquals("Requirement", detail.type)
        assertTrue(detail.attributes.keys.none { it.startsWith("__") })
    }

    /**
     * The panel leads with the object's DOORS id, so it carries one.
     *
     * `__name` for a requirement is its `Object Text`, which on a sanitised export is the same
     * sentence on every object — a panel headed by it cannot say which requirement is open.
     * A placeholder has no id and must not fall back to its `__name`, which is its `__id` spelled
     * out (R5).
     */
    @Test
    fun `item detail carries the DOORS id, and never invents one for a placeholder`() = runBlocking {
        assertEquals("SRD-1", assertNotNull(reviewProjection.getItemDetail("obj-1", access = seesAll)).id)

        val placeholder = assertNotNull(reviewProjection.getItemDetail("missing-1", access = seesAll))
        assertNull(placeholder.id)
    }

    /**
     * An empty attribute reaches the panel as an empty *value*, never as a missing key.
     *
     * This is what the panel renders as *Empty*, and it is the whole of what the panel needs: the
     * attributes **this object carries**. It deliberately does not carry the module's full
     * attribute set — that meant a module-wide scan for attributes the object does not have, and
     * measured against the running service it turned an 8ms panel open into 26ms.
     *
     * `""` and an absent key stay different things (CLAUDE.md §11): `obj-2` carries
     * `REQ. Priorität` empty, while `obj-3` does not carry it at all.
     */
    @Test
    fun `item detail keeps an empty attribute as a value, and omits one the object lacks`() = runBlocking {
        val withEmpty = assertNotNull(reviewProjection.getItemDetail("obj-2", access = seesAll))
        assertTrue(withEmpty.attributes.containsKey("REQ. Priorität"))
        assertEquals("", withEmpty.attributes.getValue("REQ. Priorität").toString().trim('\"'))

        val heading = assertNotNull(reviewProjection.getItemDetail("obj-3", access = seesAll))
        assertFalse(heading.attributes.containsKey("REQ. Priorität"))
    }
}
