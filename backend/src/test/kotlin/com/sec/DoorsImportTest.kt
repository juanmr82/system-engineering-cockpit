package com.sec

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.importer.ImportContext
import com.sec.importer.ImportLogLevel
import com.sec.importer.ImportRequest
import com.sec.security.AccessSet
import com.sec.source.doors.DoorsDerivations
import com.sec.source.doors.DoorsExport
import com.sec.source.doors.DoorsExportParser
import com.sec.source.doors.DoorsGraphWriter
import com.sec.source.doors.DoorsImporter
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
 * The DOORS-from-an-upload importer against a real Neo4j Community image (ADR 0019).
 *
 * The whole importer is run rather than the writer alone — the same reasoning
 * `WindchillImportTest` gives: the phase order and the reconciliation's dependence on the run stamp
 * every write phase threads through are not visible to a unit test, and `WITH n, NOT n:$DELETED ...`
 * is exactly the kind of Cypher 25 shape only a real server can confirm.
 *
 * Graph names are written out in the assertions on purpose, the same convention
 * `WindchillImportTest` follows — a fixture that used the constants too could let a wrong constant
 * pass in both directions at once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DoorsImportTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var writer: DoorsGraphWriter

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        writer = DoorsGraphWriter(graphDriver)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n")) { }
    }

    // -- a fresh import --------------------------------------------------------------------------

    @Test
    fun `a fresh import writes the module and every object as an SEItem`() = runBlocking {
        val context = import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))

        assertEquals(2L, context.counters["objectsSeen"])
        assertEquals(1, count("MATCH (m:DOORSModule) RETURN count(m) AS n"))
        assertEquals(2, count("MATCH (o:DOORSObject) WHERE NOT o:DOORSModule RETURN count(o) AS n"))
        assertEquals(3, count("MATCH (n:SEItem) RETURN count(n) AS n"))

        val heading = propertiesOf(objectUrl("1"))
        assertEquals("Heading 1", heading["__name"])
        assertEquals("000001", heading["__sortKey"])
        assertTrue(labelsOf(objectUrl("1")).contains("DOORSHeading"))

        val requirement = propertiesOf(objectUrl("1.1"))
        assertEquals("Requirement 1.1", requirement["__name"])
        assertTrue(labelsOf(objectUrl("1.1")).contains("DOORSRequirement"))
    }

    @Test
    fun `the hierarchy is built from objectNumber, root objects hanging off the module`() = runBlocking {
        import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))

        assertEquals(
            1,
            count(
                "MATCH (m:DOORSModule)-[:__child]->(o:SEItem {__id: \$id}) RETURN count(*) AS n",
                mapOf("id" to objectUrl("1")),
            ),
        )
        assertEquals(
            1,
            count(
                "MATCH (p:SEItem {__id: \$p})-[:__child]->(c:SEItem {__id: \$c}) RETURN count(*) AS n",
                mapOf("p" to objectUrl("1"), "c" to objectUrl("1.1")),
            ),
        )
    }

    @Test
    fun `an outgoing link to an object no import has reached creates a placeholder`() = runBlocking {
        val obj = requirement("1", 1, outputLinks = listOf(OTHER_MODULE_URL to "42"))
        import(module(obj))

        val targetId = objectUrl("42", moduleUrl = OTHER_MODULE_URL)
        assertEquals(1, count("MATCH (t:`__UNDEFINED` {__id: \$id}) RETURN count(t) AS n", mapOf("id" to targetId)))
        assertEquals(
            1,
            count(
                "MATCH (s:SEItem {__id: \$s})-[:refersTo]->(t:SEItem {__id: \$t}) RETURN count(*) AS n",
                mapOf("s" to objectUrl("1"), "t" to targetId),
            ),
        )
    }

    @Test
    fun `an incoming link is visible before the module asserting it has been imported`() = runBlocking {
        val obj = requirement("1", 1, inputLinks = listOf(OTHER_MODULE_URL to "7"))
        import(module(obj))

        val sourceId = objectUrl("7", moduleUrl = OTHER_MODULE_URL)
        assertEquals(1, count("MATCH (s:`__UNDEFINED` {__id: \$id}) RETURN count(s) AS n", mapOf("id" to sourceId)))
        assertEquals(
            1,
            count(
                "MATCH (s:SEItem {__id: \$s})-[:refersTo]->(t:SEItem {__id: \$t}) RETURN count(*) AS n",
                mapOf("s" to sourceId, "t" to objectUrl("1")),
            ),
        )
    }

    // -- re-import ---------------------------------------------------------------------------------

    @Test
    fun `a second import of the same export changes nothing`() = runBlocking {
        import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))
        val before = propertiesOf(objectUrl("1.1"))

        import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))

        assertEquals(2, count("MATCH (o:DOORSObject) WHERE NOT o:DOORSModule RETURN count(o) AS n"))
        // __importedAt is expected to move — it is the run stamp reconciliation compares against,
        // not part of the object's own content — so it is excluded from the "changes nothing" claim.
        assertEquals(before - "__importedAt", propertiesOf(objectUrl("1.1")) - "__importedAt")
    }

    @Test
    fun `an object the export no longer contains is marked deleted, not erased`() = runBlocking {
        // "1.2" needs a surviving reason to still be there to inspect — an object with no edges at
        // all is collected outright the same run it is marked (ADR 0012, "unlinked is literal"),
        // which is exactly what a separate test below covers. This one is about the object that
        // *does* survive: still present, still a DOORSRequirement, still carrying its id.
        val linking = requirement("1.1", 2, parent = "1", outputLinks = listOf(MODULE_URL to "2"))
        val target = requirement("1.2", 2, parent = "1").copy(absoluteNumber = "2")
        import(module(heading("1", 1), linking, target))

        val context = import(module(heading("1", 1), linking))

        assertEquals(1L, context.counters["objectsNewlyDeleted"])
        val ghostUrl = objectUrl("2")
        val ghost = propertiesOf(ghostUrl)
        assertEquals("Requirement 1.2", ghost["__name"])
        assertTrue(labelsOf(ghostUrl).containsAll(setOf("DOORSRequirement", "__DELETED")))
    }

    @Test
    fun `a ghost's __child edge is pruned, taking it out of the tree`() = runBlocking {
        import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))
        import(module(heading("1", 1)))

        assertEquals(
            0,
            count(
                "MATCH ()-[:__child]->(o:SEItem {__id: \$id}) RETURN count(*) AS n",
                mapOf("id" to objectUrl("1.1")),
            ),
        )
    }

    @Test
    fun `a ghost with no edges at all is collected`() = runBlocking {
        import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))
        val context = import(module(heading("1", 1)))

        assertEquals(1L, context.counters["ghostsCollected"])
        assertEquals(0, count("MATCH (n {__id: \$id}) RETURN count(n) AS n", mapOf("id" to objectUrl("1.1"))))
    }

    @Test
    fun `a ghost still linked from a surviving object is kept`() = runBlocking {
        val linking = requirement("1.1", 2, parent = "1", outputLinks = listOf(MODULE_URL to "99"))
        // Absolute Number 99 belongs to the object at "1.2" below, so the outgoing link resolves to
        // a real object in this module rather than a placeholder — see targetObjectUrl derivation.
        val target = requirement("1.2", 2, parent = "1").copy(absoluteNumber = "99")
        import(module(heading("1", 1), linking, target))

        // Removing "1.2" from the export leaves the link's target a ghost, not erased — it is still
        // referenced by "1.1"'s surviving refersTo edge.
        val context = import(module(heading("1", 1), linking))

        assertEquals(0L, context.counters["ghostsCollected"])
        val targetUrl = objectUrl("99")
        assertEquals(1, count("MATCH (n:`__DELETED` {__id: \$id}) RETURN count(n) AS n", mapOf("id" to targetUrl)))
    }

    // -- Tier 2 --------------------------------------------------------------------------------

    /** The mandatory re-import test (CLAUDE.md R2): an annotation survives a second run. */
    @Test
    fun `an annotation on an object survives a re-import`() = runBlocking {
        import(module(heading("1", 1)))
        annotate(objectUrl("1"))

        import(module(heading("1", 1)))

        assertEquals(
            1,
            count(
                "MATCH (o:SEItem {__id: \$id})-[:__noteOn]->(n:__Meta:__Note) RETURN count(n) AS n",
                mapOf("id" to objectUrl("1")),
            ),
        )
    }

    /** …and it goes the moment its object becomes a ghost (ADR 0012), whether or not that ghost is
     *  later collected. */
    @Test
    fun `an annotation is deleted the moment its object becomes a ghost`() = runBlocking {
        import(module(heading("1", 1), requirement("1.1", 2, parent = "1")))
        annotate(objectUrl("1.1"))

        import(module(heading("1", 1)))

        assertEquals(0, count("MATCH (n:__Meta) RETURN count(n) AS n"))
    }

    // -- the checksum gate (ADR 0019 §3, §4) ----------------------------------------------------

    @Test
    fun `the gate reports a module that does not exist yet`() = runBlocking {
        val gate = writer.gate(MODULE_URL, AccessSet.SEES_ALL)
        assertFalse(gate.exists)
        assertFalse(gate.visible)
        assertNull(gate.storedChecksum)
    }

    @Test
    fun `the checksum is stamped only after a run succeeds, and the gate then reports it`() = runBlocking {
        val export = parse(module(heading("1", 1)))
        import(export)

        val gate = writer.gate(MODULE_URL, AccessSet.SEES_ALL)
        assertTrue(gate.exists)
        assertEquals(export.checksum, gate.storedChecksum)
    }

    @Test
    fun `a caller with no matching access category cannot see an existing module`() = runBlocking {
        import(module(heading("1", 1)))
        grantCategory(MODULE_URL, "doors-srd")

        val noAccess = writer.gate(MODULE_URL, AccessSet(seesAll = false, categoryIds = listOf("other-category")))
        assertTrue(noAccess.exists)
        assertFalse(noAccess.visible)

        val withAccess = writer.gate(MODULE_URL, AccessSet(seesAll = false, categoryIds = listOf("doors-srd")))
        assertTrue(withAccess.visible)

        val seesAll = writer.gate(MODULE_URL, AccessSet.SEES_ALL)
        assertTrue(seesAll.visible)
    }

    // -- helpers ----------------------------------------------------------------------------------

    private suspend fun import(export: DoorsExport): RecordingContext {
        val context = RecordingContext(export)
        DoorsImporter(writer).run(context)
        return context
    }

    private suspend fun import(json: String): RecordingContext = import(parse(json))

    private fun parse(json: String): DoorsExport = DoorsExportParser.parse(json.toByteArray()).getOrThrow()

    private suspend fun count(cypher: String, params: Map<String, Any> = emptyMap()): Int =
        graphDriver.executeRead(Query("CYPHER 25 $cypher", params)) { it.single()["n"].asInt() }

    private suspend fun propertiesOf(id: String): Map<String, Any?> =
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (n:SEItem {__id: \$id}) RETURN properties(n) AS p", mapOf("id" to id)),
        ) { records -> records.single()["p"].asMap() }

    private suspend fun labelsOf(id: String): Set<String> =
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (n:SEItem {__id: \$id}) RETURN labels(n) AS l", mapOf("id" to id)),
        ) { records -> records.single()["l"].asList { it.asString() }.toSet() }

    /** A note written the way the meta writer will, so the re-import test has something to protect. */
    private suspend fun annotate(id: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (o:SEItem {__id: ${'$'}id})
                CREATE (o)-[:__noteOn]->(:__Meta:__Note {
                    __metaId: ${'$'}id + '#note',
                    __metaKind: 'note',
                    __schemaVersion: 1,
                    text: 'keep me'
                })
                """,
                mapOf("id" to id),
            ),
        ) { }
    }

    /** Grants [moduleId] a direct access category the way `AccessAdminService`'s own write would,
     *  without pulling in the whole Access feature just to seed one relationship. */
    private suspend fun grantCategory(moduleId: String, categoryId: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (m:SEItem {__id: ${'$'}moduleId})
                MERGE (c:__Meta:__AccessCategory {__metaId: ${'$'}categoryId})
                  ON CREATE SET c.__metaKind = 'accessCategory', c.__schemaVersion = 1,
                    c.key = ${'$'}categoryId, c.name = ${'$'}categoryId, c.everyGroup = false
                MERGE (m)-[:__inAccessCategory {origin: 'direct'}]->(c)
                """,
                mapOf("moduleId" to moduleId, "categoryId" to categoryId),
            ),
        ) { }
    }

    // -- fixtures: a small, hand-written DOORS export ---------------------------------------------

    private fun module(vararg objects: Obj): String = """
        {
          "__objectId": "mod1", "__name": "Test module", "__version": "current",
          "description": "", "moduleFullPath": "/T/Test module",
          "url": "$MODULE_URL",
          "__contents": [${objects.joinToString(",") { it.json() }}]
        }
    """.trimIndent()

    /** The same derivation `refersTo` resolution uses (`DoorsDerivations.targetObjectUrl`), so a
     *  fixture object's own identity and a link's resolved target are computed by one function
     *  instead of two independently hand-rolled formulas that can silently drift apart. */
    private fun objectUrl(absoluteNumber: String, moduleUrl: String = MODULE_URL) =
        DoorsDerivations.targetObjectUrl(moduleUrl, absoluteNumber)

    private fun heading(num: String, level: Int, parent: String? = null): Obj = Obj(
        num = num, level = level, type = "Heading", heading = "Heading $num", parent = parent,
    )

    private fun requirement(
        num: String,
        level: Int,
        parent: String? = null,
        outputLinks: List<Pair<String, String>> = emptyList(),
        inputLinks: List<Pair<String, String>> = emptyList(),
    ): Obj = Obj(
        num = num, level = level, type = "Requirement", text = "Requirement text $num",
        shortText = "Requirement $num", parent = parent,
        outputLinks = outputLinks, inputLinks = inputLinks,
    )

    /** One `__contents` entry. [absoluteNumber] defaults to [num] — real DOORS numbers are unrelated
     *  to `objectNumber`, but nothing here needs them to differ except the tests that resolve a link
     *  by Absolute Number, which set it explicitly via [copy]. */
    private data class Obj(
        val num: String,
        val level: Int,
        val type: String,
        val text: String = "",
        val shortText: String = "",
        val heading: String = "",
        val parent: String? = null,
        val absoluteNumber: String = num,
        val outputLinks: List<Pair<String, String>> = emptyList(),
        val inputLinks: List<Pair<String, String>> = emptyList(),
    ) {
        fun json(): String {
            fun links(entries: List<Pair<String, String>>) = entries.joinToString(",") { (url, abs) ->
                """{"reqDocumentURL":"$url","absoluteNumber":"$abs"}"""
            }
            return """
                {
                  "id": "OBJ-$num", "objectNumber": "$num", "objectLevel": "$level",
                  "__moduleUrl": "$MODULE_URL",
                  "Object Heading": "$heading", "Object Text": "$text", "Object Short Text": "$shortText",
                  "Object Type": "$type", "Absolute Number": "$absoluteNumber",
                  "__objectUrl": "${DoorsDerivations.targetObjectUrl(MODULE_URL, absoluteNumber)}",
                  "__tableObject": "false", "__tableID": "", "__tableURL": "",
                  "__tableRowIndex": "", "__tableColumnIndex": "",
                  "__outputLinks": [${links(outputLinks)}], "__inputLinks": [${links(inputLinks)}]
                }
            """.trimIndent()
        }
    }

    /** An [ImportContext] that records instead of publishing — the framework's own behaviour has its
     *  own tests in `ImportRunServiceTest`. */
    private class RecordingContext(private val export: DoorsExport) : ImportContext {
        val counters = mutableMapOf<String, Long>()
        val warnings = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var params: Map<String, String> = emptyMap()

        override val runId: String = "test-run"
        override val request: ImportRequest = export

        override suspend fun phase(phaseId: String) = Unit
        override suspend fun progress(current: Int, total: Int) = Unit

        override suspend fun log(message: String, level: ImportLogLevel) {
            logs += message
        }

        override suspend fun warn(message: String) {
            warnings += message
        }

        override suspend fun count(name: String, delta: Long) {
            counters[name] = (counters[name] ?: 0) + delta
        }

        override suspend fun setCount(name: String, value: Long) {
            counters[name] = value
        }

        override suspend fun params(params: Map<String, String>) {
            this.params = this.params + params
        }

        override suspend fun ensureActive() = Unit
    }

    private companion object {
        const val MODULE_URL = "doors://d:9601/?version=2&prodID=0&urn=urn:telelogic::1-0-M-mod1"
        const val OTHER_MODULE_URL = "doors://d:9601/?version=2&prodID=0&urn=urn:telelogic::1-0-M-mod2"
    }
}
