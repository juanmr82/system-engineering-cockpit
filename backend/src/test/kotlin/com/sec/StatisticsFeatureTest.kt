package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.domain.SystemLevelChange
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.security.AccessSet
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.ReviewProjection
import com.sec.source.doors.StatisticsProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance criteria from docs/features/requirements-statistics.md §13, against a real Neo4j
 * **Community** image (CLAUDE.md §7, §11). Container lifecycle owned explicitly — see
 * ModulesFeatureTest for why.
 *
 * The fixture is a **seeded scratch graph**, never a live module (`HANDOVER.md` §1: verifying
 * against live data is what caused the one confirmed data loss in this project).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class StatisticsFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var doorsProjection: DoorsProjection
    private lateinit var reviewProjection: ReviewProjection
    private lateinit var statistics: StatisticsProjection
    private lateinit var metaWriter: MetaWriter

    private val levelledModule = "module-l1"
    private val topModule = "module-l0"
    private val unlevelledModule = "module-none"

    // Cross-checked against the review table below, which is not itself under test here.
    private val seesAll = AccessSet(seesAll = true, categoryIds = emptyList())

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        doorsProjection = DoorsProjection(graphDriver)
        reviewProjection = ReviewProjection(graphDriver)
        statistics = StatisticsProjection(graphDriver)
        metaWriter = MetaWriter(graphDriver, doorsProjection)
        runBlocking {
            MetaSchema.apply(graphDriver)
            seed()
            configure()
        }
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    /**
     * Three modules covering every state the view distinguishes.
     *
     * ```
     *  module-l0  (L0)   L0-1                          ← nothing above it: parentage not asked
     *  module-l1  (L1)   L1-1 → L0-1                   ← has a parent
     *                    L1-2 → <not imported>         ← parent not imported
     *                    L1-3                          ← orphan, blank Rationale, "TBD" in text
     *                    L1-4 ⇄ L1-5                   ← a two-node loop
     *                    L1-6 ⇄ HEAD-2                 ← a loop through a non-requirement
     *                    HEAD-1, CELL-1                ← heading, and a table cell DOORS left
     *                                                    untyped: `Object Type` reads TBD
     *  module-none       NONE-1                        ← no system level: excluded from the ratio
     * ```
     */
    private suspend fun seed() {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}l1, __name: 'Segment requirements', __version: 'current', url: ${'$'}l1
                })
                CREATE (:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}l0, __name: 'Customer requirements', __version: 'current', url: ${'$'}l0
                })
                CREATE (:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}none, __name: 'Unclassified requirements', __version: 'current', url: ${'$'}none
                })

                CREATE (l01:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l0-req-1', __moduleUrl: ${'$'}l0, __name: 'L0-1', __version: 'current',
                    __sortKey: '000001', id: 'L0-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'The customer shall be satisfied'
                })

                CREATE (r1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l1-req-1', __moduleUrl: ${'$'}l1, __name: 'L1-1', __version: 'current',
                    __sortKey: '000001', id: 'L1-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'The segment shall be complete',
                    Rationale: 'Because it must be', `Verification Method`: 'Test'
                })
                CREATE (r2:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l1-req-2', __moduleUrl: ${'$'}l1, __name: 'L1-2', __version: 'current',
                    __sortKey: '000002', id: 'L1-2', objectNumber: '2', objectLevel: 1,
                    `Object Text`: 'The segment shall interface upwards',
                    Rationale: 'Interface control', `Verification Method`: 'Review'
                })
                CREATE (r3:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l1-req-3', __moduleUrl: ${'$'}l1, __name: 'L1-3', __version: 'current',
                    __sortKey: '000003', id: 'L1-3', objectNumber: '3', objectLevel: 1,
                    `Object Text`: 'The mass shall be TBD kg',
                    Rationale: ''
                })
                CREATE (r4:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l1-req-4', __moduleUrl: ${'$'}l1, __name: 'L1-4', __version: 'current',
                    __sortKey: '000004', id: 'L1-4', objectNumber: '4', objectLevel: 1,
                    `Object Text`: 'A refines B', Rationale: 'Loop', `Verification Method`: 'Analysis'
                })
                CREATE (r5:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l1-req-5', __moduleUrl: ${'$'}l1, __name: 'L1-5', __version: 'current',
                    __sortKey: '000005', id: 'L1-5', objectNumber: '5', objectLevel: 1,
                    `Object Text`: 'B refines A', Rationale: 'Loop', `Verification Method`: 'Analysis'
                })
                CREATE (r6:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'l1-req-6', __moduleUrl: ${'$'}l1, __name: 'L1-6', __version: 'current',
                    __sortKey: '000006', id: 'L1-6', objectNumber: '6', objectLevel: 1,
                    `Object Text`: 'Refines a heading', Rationale: 'Odd', `Verification Method`: 'Test'
                })
                CREATE (h1:DOORSObject:DOORSHeading:SEItem {
                    __id: 'l1-head-1', __moduleUrl: ${'$'}l1, __name: 'Scope', __version: 'current',
                    __sortKey: '000007', id: 'L1-H1', objectNumber: '7', objectLevel: 1,
                    `Object Heading`: 'Scope'
                })
                CREATE (h2:DOORSObject:DOORSHeading:SEItem {
                    __id: 'l1-head-2', __moduleUrl: ${'$'}l1, __name: 'Interfaces', __version: 'current',
                    __sortKey: '000008', id: 'L1-H2', objectNumber: '8', objectLevel: 1,
                    `Object Heading`: 'Interfaces'
                })
                CREATE (c1:DOORSObject:DOORSTableCell:DOORSTBD:SEItem {
                    __id: 'l1-cell-1', __moduleUrl: ${'$'}l1, __name: 'cell', __version: 'current',
                    __sortKey: '000009', id: 'L1-C1', objectNumber: '9', objectLevel: 2,
                    `Object Text`: 'a value', `Object Type`: 'TBD'
                })

                CREATE (n1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'none-req-1', __moduleUrl: ${'$'}none, __name: 'NONE-1', __version: 'current',
                    __sortKey: '000001', id: 'NONE-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'Unclassified'
                })

                CREATE (gone:SEItem:__UNDEFINED {
                    __id: 'missing-1', __name: '<unresolved missing-1>', __version: 'current',
                    __moduleUrl: 'module-not-imported'
                })

                CREATE (r1)-[:refersTo]->(l01)
                CREATE (r2)-[:refersTo]->(gone)
                CREATE (r4)-[:refersTo]->(r5)
                CREATE (r5)-[:refersTo]->(r4)
                CREATE (r6)-[:refersTo]->(h2)
                CREATE (h2)-[:refersTo]->(r6)
                """.trimIndent(),
                mapOf("l1" to levelledModule, "l0" to topModule, "none" to unlevelledModule),
            ),
        ) { }
    }

    private suspend fun configure() {
        metaWriter.saveModuleSettings(
            levelledModule,
            SystemLevelChange.Set("L1"),
            addAttributes = listOf("Rationale"),
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput(
                    name = "Verification Method",
                    mandatory = false,
                    visible = false,
                    verification = true,
                    excludedFromOpenPoints = false,
                ),
            ),
        )
        metaWriter.saveModuleSettings(topModule, SystemLevelChange.Set("L0"))
        // module-none is deliberately left unclassified.
    }

    private fun statistics(moduleId: String? = null) = runBlocking {
        assertNotNull(statistics.getStatistics(moduleId))
    }

    private fun cycles(moduleId: String? = null) = runBlocking { statistics.getCycles(moduleId) }

    private fun moduleOf(moduleId: String) =
        statistics().modules.single { it.ref == Ref.encode(moduleId) }

    // --- Census and scope ---------------------------------------------------------------------

    @Test
    fun `the census counts every module, item and requirement in scope`() {
        val census = statistics().census
        assertEquals(3, census.modules)
        // 9 in module-l1 (6 requirements, 2 headings, 1 table cell) + 1 + 1.
        assertEquals(11, census.items)
        // Headings and table structure are not requirements — every view agrees on that (§3.1).
        assertEquals(8, census.requirements)
        assertEquals(6, census.links)
    }

    // Criterion 6.
    @Test
    fun `a not-yet-imported placeholder is never counted as an item or a requirement`() {
        val all = statistics()
        assertEquals(11, all.census.items, "the :__UNDEFINED node must not inflate the item count")
        assertEquals(1, moduleOf(levelledModule).danglingLinks)
        assertEquals(
            listOf("module-not-imported"),
            all.danglingTargets.map { Ref.decodeOrNull(it.ref) },
        )
        assertNull(all.danglingTargets.single().name, "an unimported module has no name to show")
    }

    @Test
    fun `module scope reports only that module`() {
        val scoped = statistics(levelledModule)
        assertEquals(1, scoped.census.modules)
        assertEquals(9, scoped.census.items)
        assertEquals(6, scoped.census.requirements)
    }

    @Test
    fun `an unknown module is absent rather than an empty page of zeroes`(): Unit = runBlocking {
        assertNull(statistics.getStatistics("module-that-never-existed"))
    }

    // --- Completeness -------------------------------------------------------------------------

    // Criterion 5: the same shared rule, so the two views cannot disagree (§3.2).
    @Test
    fun `mandatory violations match what the review table reports for the same module`() =
        runBlocking {
            val fromStatistics = moduleOf(levelledModule).completeness.itemsMissingMandatory
            val fromReviewTable = reviewProjection.getModuleObjects(levelledModule, seesAll).rows
                .count { row -> row.issues.any { it.startsWith("Rationale") || it == "Rationale" } }

            assertEquals(1, fromStatistics)
            assertEquals(fromReviewTable, fromStatistics)
        }

    // Criterion 4.
    @Test
    fun `the item count equals the review table's own total for the same module`() = runBlocking {
        assertEquals(
            reviewProjection.getModuleObjects(levelledModule, seesAll).total,
            moduleOf(levelledModule).completeness.items,
        )
    }

    @Test
    fun `a mandatory policy aimed at requirements does not flag headings or table structure`() {
        // The heading has no Rationale either; appliesToLabels is read, never assumed (R2).
        assertEquals(1, moduleOf(levelledModule).completeness.itemsMissingMandatory)
        assertEquals(
            listOf("Rationale"),
            moduleOf(levelledModule).mandatoryByAttribute.map { it.attribute },
        )
    }

    @Test
    fun `verification is asked of requirements only`() {
        // Two headings also lack `Verification Method`. A heading has nothing to verify, so
        // counting one would put every section title in the finding.
        assertEquals(1, moduleOf(levelledModule).completeness.itemsMissingVerification)
    }

    @Test
    fun `open points are found in requirement text and attributed to the attribute carrying them`() {
        val module = moduleOf(levelledModule)
        assertEquals(1, module.completeness.itemsWithOpenPoints)
        assertEquals(listOf("Object Text"), module.openPointsByAttribute.map { it.attribute })
    }

    /**
     * The configured exclusion, end to end: the settings dialogs write it, and the scan honours it.
     *
     * `Object Text` is the fixture's one open-point carrier, so excluding it must take the module
     * from one item with open points to none — and it has to do so through the *stored* setting,
     * written the way either dialog writes it, rather than through anything this test hands the
     * check directly. `DoorsChecksTest` covers the filter itself; what is proved here is that the
     * flag survives the round trip and reaches the scan.
     */
    @Test
    fun `an attribute excluded in the settings dialog leaves the TBD scan`() = runBlocking {
        assertEquals(1, moduleOf(levelledModule).completeness.itemsWithOpenPoints)

        // This class seeds once in @BeforeAll and never resets, so the exclusion is put back in a
        // `finally` — every other test here asserts against the module in its seeded state, and a
        // leaked setting would make them pass or fail on execution order.
        try {
            exclude("Object Text", excluded = true)

            val module = moduleOf(levelledModule)
            assertEquals(0, module.completeness.itemsWithOpenPoints)
            assertTrue(module.openPointsByAttribute.isEmpty(), module.openPointsByAttribute.toString())
        } finally {
            // Every flag false, which deletes the node outright — so this restores the seeded
            // state exactly rather than leaving a row of `false` behind.
            exclude("Object Text", excluded = false)
        }

        assertEquals(1, moduleOf(levelledModule).completeness.itemsWithOpenPoints)
    }

    /** Writes the exclusion the way either settings dialog writes it: one absolute row, one save. */
    private suspend fun exclude(attribute: String, excluded: Boolean) {
        metaWriter.saveModuleSettings(
            levelledModule,
            SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput(
                    name = attribute,
                    mandatory = false,
                    visible = false,
                    verification = false,
                    excludedFromOpenPoints = excluded,
                ),
            ),
        )
    }

    /**
     * DOORS does not type the parts of an embedded table, so every cell and row arrives with
     * `Object Type` reading "TBD". The fixed check already excuses that (`DoorsChecks`
     * `tbdCheckExclusions`); scanning the attribute's *value* let it back in, and on the reference
     * module it was the entire metric — 425 of 425 open points were a table cell's own type.
     *
     * The cell in the fixture carries exactly that. It must not reach either number.
     */
    @Test
    fun `a table cell's own Object Type is not an open point`() {
        val module = moduleOf(levelledModule)
        assertFalse(module.openPointsByAttribute.any { it.attribute == "Object Type" })
        assertEquals(1, module.completeness.itemsWithOpenPoints)
    }

    // Criterion 7 — the distinction the whole flag exists for.
    @Test
    fun `a module with no policies reads as not configured, never as clean`() {
        val unconfigured = moduleOf(unlevelledModule).completeness
        assertFalse(unconfigured.mandatoryConfigured)
        assertFalse(unconfigured.verificationConfigured)
        assertEquals(0, unconfigured.mandatoryViolations)

        val configured = moduleOf(levelledModule).completeness
        assertTrue(configured.mandatoryConfigured)
        assertTrue(configured.verificationConfigured)
    }

    @Test
    fun `clean items are those with no finding of any kind`() {
        // Only L1-3 carries findings, so the other eight objects of the module are clean.
        assertEquals(8, moduleOf(levelledModule).completeness.itemsClean)
    }

    // --- Parentage ----------------------------------------------------------------------------

    // Criterion for §6.1 — the three-way split, which is the whole point of §12.4.
    @Test
    fun `requirements above L0 split three ways by parent state`() {
        val parentage = moduleOf(levelledModule).parentage
        assertTrue(parentage.applicable)
        assertEquals(4, parentage.hasParent)
        assertEquals(1, parentage.parentNotImported, "a placeholder parent is its own bucket")
        assertEquals(1, parentage.orphans)
    }

    @Test
    fun `an L0 module is not asked for parents`() {
        // L0-1 has no outgoing link and is still not an orphan: there is nothing above L0 to
        // refine, so the question does not apply.
        val parentage = moduleOf(topModule).parentage
        assertFalse(parentage.applicable)
        assertEquals(0, parentage.orphans)
    }

    // Criterion 8.
    @Test
    fun `a module with no system level is excluded from the ratio and named`() {
        val all = statistics()
        assertFalse(moduleOf(unlevelledModule).parentage.applicable)
        assertEquals(listOf("Unclassified requirements"), all.modulesWithoutSystemLevel)
    }

    // --- Cycles -------------------------------------------------------------------------------

    // Criterion 9, against the database rather than against an edge list.
    @Test
    fun `both loops are found, each reported once`() {
        val loops = cycles().loops
        assertEquals(2, loops.size)
        // Ordered by the smallest __id in each loop, so the reading is stable between two
        // identical requests — `l1-head-2` sorts before `l1-req-4`. The displayed ids are DOORS's
        // own, which is why a heading shows `L1-H2` rather than nothing: only a placeholder has
        // no source id.
        assertEquals(
            listOf(setOf("L1-H2", "L1-6"), setOf("L1-4", "L1-5")),
            loops.map { loop -> loop.ring.map { it.id }.toSet() },
        )
    }

    // Criterion 12.3 — a loop through a non-requirement is still a loop.
    @Test
    fun `a loop passing through a heading is found`() {
        val throughHeading = cycles().loops.single { loop ->
            loop.ring.any { it.name == "Interfaces" }
        }
        assertEquals(2, throughHeading.ring.size)
        assertTrue(throughHeading.others.isEmpty())
    }

    @Test
    fun `a loop member carries its module and system level for the finding list`() {
        val member = cycles().loops.first().ring.first()
        assertEquals("Segment requirements", member.moduleName)
        assertEquals("L1", member.systemLevel?.code)
        // Resolved server-side from the closed vocabulary, never stored and never mapped by the
        // client (R5). The badge is read from the member's owning *module*, not from the member.
        assertEquals("L1 – System of Systems", member.systemLevel?.label)
    }

    // Criterion 10.
    @Test
    fun `scoping to a module keeps the loops that touch it`() {
        assertEquals(2, cycles(levelledModule).loops.size)
    }

    @Test
    fun `scoping to a module with no loops reports none`() {
        val scoped = cycles(topModule)
        assertTrue(scoped.loops.isEmpty())
        // The edge count is still the whole graph's: the SCC runs over everything and only the
        // findings are filtered (§7.2), and saying so is what makes "none" trustworthy.
        assertEquals(6, scoped.edgesExamined)
        assertFalse(scoped.truncated)
    }

    // --- The read-only guarantee ---------------------------------------------------------------

    // Criterion 14. Statistics is entirely derived, so this is the one assertion that keeps R1's
    // read-only promise true as the view grows.
    @Test
    fun `neither endpoint writes anything to the graph`() = runBlocking {
        val before = census()
        statistics.getStatistics(null)
        statistics.getStatistics(levelledModule)
        statistics.getCycles(null)
        statistics.getCycles(levelledModule)
        assertEquals(before, census())
    }

    private suspend fun census(): Map<String, Any> =
        graphDriver.executeRead(
            Query(
                """
                CYPHER 25
                MATCH (n)
                WITH count(n) AS nodes, sum(size(keys(n))) AS properties
                MATCH ()-[r]->()
                RETURN nodes AS nodes, properties AS properties, count(r) AS relationships
                """.trimIndent(),
            ),
        ) { records ->
            records.single().asMap()
        }
}
