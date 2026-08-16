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
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.RequirementCardProjection
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
 * Acceptance criteria from docs/requirement-breakdown-tree.md §9, against a real Neo4j **Community**
 * image (CLAUDE.md §7, §11). Container lifecycle owned explicitly — see ModulesFeatureTest for why.
 *
 * The fixture carries a genuine multi-parent node and a genuine multi-root closure, which criterion
 * 12 asks for by name, plus a two-node cycle and a not-yet-imported placeholder — the two shapes
 * that turn a tree walk into a hang if they are not handled.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class BreakdownFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var doorsProjection: DoorsProjection
    private lateinit var breakdownProjection: BreakdownProjection
    private lateinit var metaWriter: MetaWriter

    private val systemModule = "module-system"
    private val segmentModule = "module-segment"
    private val componentModule = "module-component"

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        doorsProjection = DoorsProjection(graphDriver)
        breakdownProjection = BreakdownProjection(graphDriver, RequirementCardProjection(graphDriver))
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
     * Three modules and one deliberately awkward decomposition.
     *
     * ```
     *   SYS-1 (L1)          SYS-9 (L1)        ← two independent roots
     *     ↑        ↑            ↑
     *   SEG-1    SEG-2          |             ← both refine SYS-1
     *     ↑  ↖      ↑           |
     *   CMP-2   \  CMP-1 -------+             ← CMP-1 has three parents and reaches both roots
     * ```
     *
     * plus `CMP-2 → <not imported>` on its own branch, and a `LOOP-1 ⇄ LOOP-2` pair with nothing
     * above either of them.
     */
    private suspend fun seed() {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}sys, __name: 'System requirements', __version: 'current', url: ${'$'}sys
                })
                CREATE (:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}seg, __name: 'Segment requirements', __version: 'current', url: ${'$'}seg
                })
                CREATE (:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}cmp, __name: 'Component requirements', __version: 'current', url: ${'$'}cmp
                })

                CREATE (sys1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'sys-1', __moduleUrl: ${'$'}sys, __name: 'SYS-1', __version: 'current',
                    __sortKey: '000001', id: 'SYS-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'The aircraft shall fly'
                })
                CREATE (sys9:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'sys-9', __moduleUrl: ${'$'}sys, __name: 'SYS-9', __version: 'current',
                    __sortKey: '000009', id: 'SYS-9', objectNumber: '9', objectLevel: 1,
                    `Object Text`: 'The aircraft shall be maintainable'
                })
                CREATE (seg1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'seg-1', __moduleUrl: ${'$'}seg, __name: 'SEG-1', __version: 'current',
                    __sortKey: '000001', id: 'SEG-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'The wing shall generate lift',
                    `Verification Method`: 'Test'
                })
                CREATE (seg2:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'seg-2', __moduleUrl: ${'$'}seg, __name: 'SEG-2', __version: 'current',
                    __sortKey: '000002', id: 'SEG-2', objectNumber: '2', objectLevel: 1,
                    `Object Text`: 'The wing shall carry fuel',
                    `Verification Method`: ''
                })
                CREATE (head:DOORSObject:DOORSHeading:SEItem {
                    __id: 'cmp-0', __moduleUrl: ${'$'}cmp, __name: 'Structure', __version: 'current',
                    __sortKey: '000001', id: 'CMP-0', objectNumber: '2.1', objectLevel: 2,
                    `Object Heading`: 'Structure'
                })
                CREATE (cmp1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'cmp-1', __moduleUrl: ${'$'}cmp, __name: 'CMP-1', __version: 'current',
                    __sortKey: '000002', id: 'CMP-1', objectNumber: '2.2', objectLevel: 2,
                    `Object Text`: 'The spar shall withstand 3g'
                })
                CREATE (cmp2:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'cmp-2', __moduleUrl: ${'$'}cmp, __name: 'CMP-2', __version: 'current',
                    __sortKey: '000003', id: 'CMP-2', objectNumber: '2.3', objectLevel: 2,
                    `Object Text`: 'The rib shall be bonded'
                })
                CREATE (gone:SEItem:__UNDEFINED {
                    __id: 'missing-1', __name: '<unresolved missing-1>', __version: 'current',
                    __moduleUrl: 'module-not-imported'
                })
                CREATE (loop1:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'loop-1', __moduleUrl: ${'$'}cmp, __name: 'LOOP-1', __version: 'current',
                    __sortKey: '000004', id: 'LOOP-1', objectNumber: '3.1', objectLevel: 2,
                    `Object Text`: 'A refers to B'
                })
                CREATE (loop2:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'loop-2', __moduleUrl: ${'$'}cmp, __name: 'LOOP-2', __version: 'current',
                    __sortKey: '000005', id: 'LOOP-2', objectNumber: '3.2', objectLevel: 2,
                    `Object Text`: 'B refers to A'
                })

                CREATE (seg1)-[:refersTo]->(sys1)
                CREATE (seg2)-[:refersTo]->(sys1)
                CREATE (cmp1)-[:refersTo]->(seg1)
                CREATE (cmp1)-[:refersTo]->(seg2)
                CREATE (cmp1)-[:refersTo]->(sys9)
                CREATE (cmp2)-[:refersTo]->(seg1)
                CREATE (cmp2)-[:refersTo]->(gone)
                CREATE (loop1)-[:refersTo]->(loop2)
                CREATE (loop2)-[:refersTo]->(loop1)
                """.trimIndent(),
                mapOf("sys" to systemModule, "seg" to segmentModule, "cmp" to componentModule),
            ),
        ) { }
    }

    /** The Tier-2 configuration the tab reads: system levels, and one verification attribute. */
    private suspend fun configure() {
        metaWriter.saveModuleSettings(systemModule, SystemLevelChange.Set("L1"), access = AccessSet.SEES_ALL)
        metaWriter.saveModuleSettings(segmentModule, SystemLevelChange.Set("L2"), access = AccessSet.SEES_ALL)
        metaWriter.saveModuleSettings(
            segmentModule,
            SystemLevelChange.Unchanged,
            attributeSettings = listOf(
                MetaWriter.AttributeSettingInput(
                    name = "Verification Method",
                    mandatory = false,
                    visible = false,
                    verification = true,
                    excludedFromOpenPoints = false,
                ),
            ),
            access = AccessSet.SEES_ALL,
        )
    }

    private fun breakdown(itemId: String, maxDepth: Int = 6, maxNodes: Int = 200) = runBlocking {
        assertNotNull(breakdownProjection.getBreakdown(itemId, maxDepth = maxDepth, maxNodes = maxNodes, access = AccessSet.SEES_ALL))
    }

    // Criterion 1: every root, and the full decomposition down from each — not just the selected
    // item's own neighbourhood, so the panel never needs a second request to open a sibling branch.
    @Test
    fun `the closure reaches every root and every descendant of every root`() {
        val result = breakdown("cmp-1")

        assertEquals(Ref.encode("cmp-1"), result.selectedRef)
        assertEquals(setOf(Ref.encode("sys-1"), Ref.encode("sys-9")), result.roots.toSet())
        assertEquals(
            setOf("SYS-1", "SYS-9", "SEG-1", "SEG-2", "CMP-1", "CMP-2"),
            result.nodes.mapNotNull { it.id }.toSet(),
        )
        assertFalse(result.truncated)

        // CMP-2 is a *sibling* branch — reached only by descending from SYS-1, never by climbing
        // from CMP-1. Its presence is what makes the response a forest rather than one path.
        assertTrue(result.nodes.any { it.id == "CMP-2" })

        // from refines to: the response says CMP-1 refines all three of its targets, and nothing
        // in it collapses that to one.
        val fromCmp1 = result.edges.filter { it.from == Ref.encode("cmp-1") }.map { it.to }.toSet()
        assertEquals(
            setOf(Ref.encode("seg-1"), Ref.encode("seg-2"), Ref.encode("sys-9")),
            fromCmp1,
        )
        assertTrue(result.edges.none { it.cyclic })
    }

    // Criterion 12's multi-root half, from the other end: opening a *root* still returns the whole
    // tree beneath it, and reports itself as the only root.
    @Test
    fun `opening a root returns its own decomposition`() {
        val result = breakdown("sys-1")

        assertEquals(listOf(Ref.encode("sys-1")), result.roots)
        assertEquals(
            setOf("SYS-1", "SEG-1", "SEG-2", "CMP-1", "CMP-2"),
            result.nodes.mapNotNull { it.id }.toSet(),
        )
        // SYS-9 is above CMP-1 but is not below SYS-1, so it is not part of this tree.
        assertTrue(result.nodes.none { it.id == "SYS-9" })
    }

    // The level badge comes from the *owning module's* classification, and a module with none
    // yields no badge rather than a blank chip or an invented code (§2).
    @Test
    fun `the level badge is the owning module's classification, resolved to its wording`() {
        val byId = breakdown("cmp-1").nodes.associateBy { it.id }

        assertEquals("L1", byId.getValue("SYS-1").level?.code)
        // R5: the client never maps a stored code to wording of its own.
        assertEquals("L1 – System of Systems", byId.getValue("SYS-1").level?.label)
        assertEquals("L2", byId.getValue("SEG-1").level?.code)
        assertNull(byId.getValue("CMP-1").level)
    }

    /**
     * Criterion 6: **every** attribute flagged `verification` for that node's module, not just one,
     * and resolved per node's own module rather than the selected item's.
     *
     * `""` travels as an empty value rather than being dropped — from DOORS that means "the
     * attribute exists and is empty" (CLAUDE.md §11), and the panel says "Not filled in" for it.
     */
    @Test
    fun `verification attributes come from each node's own module, values and all`() {
        val byId = breakdown("cmp-1").nodes.associateBy { it.id }

        assertEquals(
            listOf("Verification Method" to "Test"),
            byId.getValue("SEG-1").verificationAttributes.map { it.name to it.value },
        )
        assertEquals(
            listOf("Verification Method" to ""),
            byId.getValue("SEG-2").verificationAttributes.map { it.name to it.value },
        )
        // Criterion 7: the component module has flagged nothing, which is an absence of
        // configuration and reaches the panel as an empty list, never as a fabricated row.
        assertTrue(byId.getValue("CMP-1").verificationAttributes.isEmpty())
    }

    // Criterion 11 in the only place it can be checked from here: reading the tab writes nothing.
    // Every meta node in the graph is one this test's own configure() step created.
    @Test
    fun `reading a breakdown writes nothing to the graph`() {
        val before = metaNodeIds()
        breakdown("cmp-1")
        breakdown("loop-1")
        assertEquals(before, metaNodeIds())
    }

    /**
     * The same Description rule the review table uses: a heading reads as its outline number plus
     * its heading text, everything else as its requirement statement (§4).
     */
    @Test
    fun `a heading describes itself by outline number and heading text`() = runBlocking {
        // CMP-0 is not linked to anything, so its own breakdown is a forest of one.
        val result = assertNotNull(breakdownProjection.getBreakdown("cmp-0", access = AccessSet.SEES_ALL))

        assertEquals("2.1 Structure", result.nodes.single().description)
        assertEquals(listOf(Ref.encode("cmp-0")), result.roots)
    }

    /**
     * Criterion 8: a cycle does not hang, and the closing edge is marked rather than silently
     * dropped — the client draws it as a chip in a warning tone.
     */
    @Test
    fun `a cyclic refersTo chain terminates and marks the closing edge`() {
        val result = breakdown("loop-1")

        assertEquals(setOf("LOOP-1", "LOOP-2"), result.nodes.mapNotNull { it.id }.toSet())
        assertEquals(2, result.edges.size)
        assertEquals(1, result.edges.count { it.cyclic })
        // Rooting the forest at the item the reviewer clicked is the one answer that is always
        // renderable when nothing in the closure is terminal.
        assertEquals(listOf(Ref.encode("loop-1")), result.roots)
    }

    /**
     * A placeholder is a legitimate leaf, not an error and not a hidden node (§7).
     *
     * R5: its `__name` is its `__id` spelled out, so no id and no description reach the wire — the
     * wording and the owning module are the whole of what the panel can honestly show.
     */
    @Test
    fun `a not-yet-imported target is a marked leaf with no id and no description`() {
        val result = breakdown("cmp-2")

        val placeholder = result.nodes.single { !it.resolved }
        assertNull(placeholder.id)
        assertEquals("", placeholder.description)
        assertEquals(Ref.encode("missing-1"), placeholder.ref)
        // Terminal, so it is one of the roots — and nothing was queried above it.
        assertTrue(placeholder.ref in result.roots)
        assertTrue(result.nodes.none { it.id == "SYS-9" })
    }

    /**
     * Criterion 10: the bounds are real, and hitting one is reported rather than presented as a
     * complete forest.
     *
     * `maxNodes = 2` cannot hold the six-node closure, so the response says so — which is what the
     * panel's footer reads. Community has no query governor, so this is the only thing standing
     * between one click and an unbounded walk (CLAUDE.md §7).
     */
    @Test
    fun `hitting a bound truncates and says so`() {
        val capped = breakdown("cmp-1", maxNodes = 2)
        assertTrue(capped.truncated)
        assertTrue(capped.nodes.size <= 2)
        // Every edge still points at a node the response carries: a bound may shrink the forest,
        // never leave it referring to something that is not there.
        val refs = capped.nodes.map { it.ref }.toSet()
        assertTrue(capped.edges.all { it.from in refs && it.to in refs })

        val shallow = breakdown("cmp-1", maxDepth = 1)
        assertTrue(shallow.truncated)
    }

    @Test
    fun `an unknown item is absent rather than an empty tree`() = runBlocking {
        assertNull(breakdownProjection.getBreakdown("no-such-object", access = AccessSet.SEES_ALL))
    }

    private fun metaNodeIds(): Set<String> = runBlocking {
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (m:__Meta) RETURN m.__metaId AS id", emptyMap()),
        ) { records -> records.map { it.get("id").asString("") }.toSet() }
    }
}
