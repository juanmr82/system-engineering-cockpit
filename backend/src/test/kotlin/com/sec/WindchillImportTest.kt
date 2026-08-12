package com.sec

import com.sec.config.Neo4jSettings
import com.sec.config.WindchillSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.importer.ImportContext
import com.sec.importer.ImportLogLevel
import com.sec.importer.ImportRequest
import com.sec.source.windchill.WindchillExport
import com.sec.source.windchill.WindchillExportParser
import com.sec.source.windchill.WindchillGraphWriter
import com.sec.source.windchill.WindchillImporter
import com.sec.source.windchill.WindchillProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.testcontainers.containers.Neo4jContainer

/**
 * The Windchill importer against a real Neo4j Community image.
 *
 * The whole importer is run rather than the writer alone, because two of the things most able to
 * break are not in the Cypher: the phase order, and the sweep's dependence on the set the document
 * phase produced. Both of the riskiest statements here also use Cypher 25 features that no unit
 * test can check — `REMOVE d[key]` most of all — because whether the server supports them is a
 * property of the server (CLAUDE.md §7: Community, never Enterprise).
 *
 * Graph names are written out in the assertions on purpose. Building them from the constants too
 * would let a wrong constant pass in both directions at once (backend/CLAUDE.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class WindchillImportTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var writer: WindchillGraphWriter

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        writer = WindchillGraphWriter(graphDriver)
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

    // -- a fresh import -------------------------------------------------------------------------

    @Test
    fun `a fresh import writes every document as an SEItem with its source fields intact`() =
        runBlocking {
            val context = import(export(DOC_A1, DOC_A2, DOC_B1))

            assertEquals(3L, context.counters["documentsSeen"])
            assertEquals(3, count("MATCH (d:WindchillDocument) RETURN count(d) AS n"))

            // Every document is an SEItem, which is the only thing a cross-source link joins on (R6).
            assertEquals(
                3,
                count("MATCH (d:WindchillDocument) WHERE d:SEItem RETURN count(d) AS n"),
            )

            val props = propertiesOf(DOC_A1.id)
            assertEquals("/XXX/Documents/XXX", props["FolderLocation"])
            assertEquals("Quartalsbericht", props["Name"])
            assertEquals("N-A", props["Number"])
            assertEquals("01 [2]", props["Version"])
            // The one nested value, flattened to two scalars, neither of them altered.
            assertEquals("RELEASED", props["StateValue"])
            assertEquals("Released", props["StateDisplay"])
            // Windchill's own id is stored — the link out needs it — and is never the identity.
            assertEquals("OR:wt.doc.WTDocument:1", props["ID"])
        }

    /** Tier 1 is written for every document, and it is what the view's order rests on (R3). */
    @Test
    fun `every document carries the derived name, version and sort key`() = runBlocking {
        import(export(DOC_A1))

        val props = propertiesOf(DOC_A1.id)
        assertEquals("Quartalsbericht", props["__name"])
        // Windchill's own revision is source data and stays there. `__version` is the application's
        // word for "as the source holds it now", and it is `current` for every node in the graph.
        assertEquals("current", props["__version"])
        // Asserted as "begins with the number", not as an exact string: the separator is U+0001
        // and the version part is a complement, both of which are the derivation's business.
        // What the view depends on — number groups, newest version first — is asserted by reading
        // the documents back in `reading the documents back gives numbers in order`.
        assertTrue(props["__sortKey"].toString().startsWith("N-A"), props["__sortKey"].toString())
    }

    /** The order the view depends on: numbers ascending, versions newest first. */
    @Test
    fun `reading the documents back gives numbers in order and newest versions first`() =
        runBlocking {
            import(export(DOC_B1, DOC_A1, DOC_A2))

            val rows = WindchillProjection(graphDriver, WindchillSettings(host = "")).listDocuments()

            assertEquals(
                listOf("N-A 02 [1]", "N-A 01 [2]", "N-B 01 [1]"),
                rows.rows.map { "${it.number} ${it.version}" },
            )
        }

    /** The link out is derived on every read and never stored (R2). */
    @Test
    fun `the info page link is built from the host and the object id`() = runBlocking {
        import(export(DOC_A1))

        val configured = WindchillProjection(
            graphDriver,
            WindchillSettings(host = "https://windchill.example.com/Windchill"),
        ).listDocuments()

        assertEquals(
            "https://windchill.example.com/Windchill/app/#ptc1/tcomp/infoPage?oid=OR:wt.doc.WTDocument:1",
            configured.rows.single().browseUrl,
        )

        // …and no host means an absent link rather than one that goes nowhere.
        val unconfigured = WindchillProjection(graphDriver, WindchillSettings(host = "")).listDocuments()
        assertNull(unconfigured.rows.single().browseUrl)
        assertTrue(!unconfigured.hostConfigured)
    }

    // -- re-import ------------------------------------------------------------------------------

    @Test
    fun `a second import of the same export changes nothing and deletes nothing`() = runBlocking {
        import(export(DOC_A1, DOC_A2))
        val before = propertiesOf(DOC_A1.id)

        val context = import(export(DOC_A1, DOC_A2))

        assertEquals(0L, context.counters["deleted"])
        assertEquals(2, count("MATCH (d:WindchillDocument) RETURN count(d) AS n"))
        assertEquals(before, propertiesOf(DOC_A1.id))
    }

    /**
     * A field the export stops carrying is **removed**, not left at last import's value.
     *
     * This is `REMOVE d[key]` doing its job, and it is the statement most able to be silently wrong:
     * without it the table shows a state Windchill no longer asserts, and nothing anywhere looks
     * broken.
     */
    @Test
    fun `a field the export no longer carries is removed from the node`() = runBlocking {
        import(export(DOC_A1))
        assertEquals("Released", propertiesOf(DOC_A1.id)["StateDisplay"])

        import(export(DOC_A1.copy(state = null)))

        val props = propertiesOf(DOC_A1.id)
        assertNull(props["StateDisplay"])
        assertNull(props["StateValue"])
        // The rest of the document is untouched — this removes what left, not what stayed.
        assertEquals("N-A", props["Number"])
    }

    // -- the sweep ------------------------------------------------------------------------------

    @Test
    fun `a document the export no longer contains is deleted`() = runBlocking {
        import(export(DOC_A1, DOC_A2, DOC_B1))

        val context = import(export(DOC_A1, DOC_B1))

        assertEquals(1L, context.counters["deleted"])
        assertEquals(2, count("MATCH (d:WindchillDocument) RETURN count(d) AS n"))
        assertEquals(
            0,
            count("MATCH (d:SEItem {__id: ${'$'}id}) RETURN count(d) AS n", mapOf("id" to DOC_A2.id)),
        )
    }

    /**
     * The mass-deletion report, which is what stands in for a narrower sweep scope.
     *
     * A file covering one folder removes every document it does not mention, by decision — so the
     * guard is that the run says how much went, loudly, and ends amber rather than green.
     */
    @Test
    fun `removing more than a fifth of the documents ends the run with a warning`() = runBlocking {
        import(export(DOC_A1, DOC_A2, DOC_B1))

        val context = import(export(DOC_A1))

        assertTrue(
            context.warnings.any { it.contains("removed 2 of 3") },
            "the mass deletion was silent: ${context.warnings}",
        )
    }

    /** A file that admits to being one page is imported, and says so before anything is written. */
    @Test
    fun `a paged export warns and imports anyway`() = runBlocking {
        val context = import(
            export(DOC_A1).copy(nextLink = "https://example.com/Documents?skiptoken=2"),
        )

        assertEquals(1, count("MATCH (d:WindchillDocument) RETURN count(d) AS n"))
        assertTrue(
            context.warnings.any { it.contains("one page of several") },
            "the paged export was silent: ${context.warnings}",
        )
    }

    // -- Tier 2 ---------------------------------------------------------------------------------

    /**
     * **The mandatory re-import test** (CLAUDE.md R2): an annotation survives a second run.
     *
     * Windchill writes no Tier 2 of its own yet, so the note is placed by hand — which is the point.
     * What is being tested is that the importer's `MERGE … SET` leaves relationships alone, and that
     * has to keep being true as this source grows, whether or not anything writes a note today.
     */
    @Test
    fun `an annotation on a document survives a re-import`() = runBlocking {
        import(export(DOC_A1))
        annotate(DOC_A1.id)

        import(export(DOC_A1))

        assertEquals(
            1,
            count(
                """
                MATCH (d:SEItem {__id: ${'$'}id})-[:__noteOn]->(n:__Meta:__Note)
                RETURN count(n) AS n
                """,
                mapOf("id" to DOC_A1.id),
            ),
        )
    }

    /**
     * …and it goes when its document does (ADR 0012).
     *
     * A note about a document Windchill no longer has is a note about nothing, and leaving one
     * behind anchors Tier 2 to a node no export will mention again.
     */
    @Test
    fun `an annotation is deleted with the document it hangs off`() = runBlocking {
        import(export(DOC_A1, DOC_A2))
        annotate(DOC_A2.id)

        import(export(DOC_A1))

        assertEquals(0, count("MATCH (n:__Meta) RETURN count(n) AS n"))
    }

    // -- helpers --------------------------------------------------------------------------------

    private suspend fun import(export: WindchillExport): RecordingContext {
        val context = RecordingContext(export)
        WindchillImporter(writer).run(context)
        return context
    }

    private suspend fun count(cypher: String, params: Map<String, Any> = emptyMap()): Int =
        graphDriver.executeRead(Query("CYPHER 25 $cypher", params)) { it.single()["n"].asInt() }

    private suspend fun propertiesOf(id: String): Map<String, Any?> =
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (d:SEItem {__id: \$id}) RETURN properties(d) AS p", mapOf("id" to id)),
        ) { records -> records.single()["p"].asMap() }

    /** A note written the way the meta writer will, so the re-import test has something to protect. */
    private suspend fun annotate(id: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (d:SEItem {__id: ${'$'}id})
                CREATE (d)-[:__noteOn]->(:__Meta:__Note {
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

    /** One document as the export writes it, so a test reads as the file it stands for. */
    private data class Doc(
        val oid: String,
        val folder: String,
        val name: String,
        val number: String,
        val version: String,
        val state: Pair<String, String>? = "RELEASED" to "Released",
    ) {
        val id: String get() = "https://company.com/Windchill/servlet/odata/v7/DocMgmt/Documents('$oid')"

        fun json(): String = buildString {
            append("""{"@odata.id":"$id","ID":"$oid","FolderLocation":"$folder",""")
            append(""""Name":"$name","Number":"$number","Version":"$version"""")
            state?.let { (value, display) ->
                append(""","State":{"Value":"$value","Display":"$display"}""")
            }
            append("}")
        }
    }

    private fun export(vararg docs: Doc): WindchillExport =
        WindchillExportParser.parse("""{"value":[${docs.joinToString(",") { it.json() }}]}""")
            .getOrThrow()

    /**
     * An [ImportContext] that records instead of publishing.
     *
     * The framework's own behaviour has its own tests in `ImportRunServiceTest`; re-exercising it
     * here would make every failure ambiguous about which half it came from.
     */
    private class RecordingContext(private val export: WindchillExport) : ImportContext {
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
        val DOC_A1 = Doc("OR:wt.doc.WTDocument:1", "/XXX/Documents/XXX", "Quartalsbericht", "N-A", "01 [2]")
        val DOC_A2 = Doc("OR:wt.doc.WTDocument:2", "/XXX/Documents/XXX", "Quartalsbericht", "N-A", "02 [1]")
        val DOC_B1 = Doc("OR:wt.doc.WTDocument:3", "/XXX/Documents/YYY", "Unterauftragnehmer", "N-B", "01 [1]")
    }
}
