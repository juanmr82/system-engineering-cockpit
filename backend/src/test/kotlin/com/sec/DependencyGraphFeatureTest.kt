package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.GraphDirection
import com.sec.domain.GraphLevelStrategy
import com.sec.domain.Ref
import com.sec.domain.SystemLevelChange
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.security.AccessSet
import com.sec.source.doors.DependencyGraphProjection
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
 * Acceptance criteria from docs/REQ_BREAKDOWN_GRAPH_VIEW.md §7, against a real Neo4j **Community**
 * image (CLAUDE.md §7, §11). Container lifecycle owned explicitly — see ModulesFeatureTest for why.
 *
 * The fixture carries the four shapes that break a naive implementation: a sibling edge that only
 * the *induced* subgraph contains, a two-node cycle, a placeholder pointing into a module nobody
 * has imported, and a hub with more neighbours than a small cap admits.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DependencyGraphFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var projection: DependencyGraphProjection
    private lateinit var metaWriter: MetaWriter

    private val systemModule = "module-system"
    private val segmentModule = "module-segment"
    private val componentModule = "module-component"

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        val doorsProjection = DoorsProjection(graphDriver)
        projection = DependencyGraphProjection(graphDriver, RequirementCardProjection(graphDriver))
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
     * Three modules, one deliberately awkward neighbourhood.
     *
     * ```
     *   SYS-1 (L1)                       SYS-9 (L1)
     *     ↑        ↑                         ↑
     *   SEG-1 →→→ SEG-2  (a sibling edge)    |
     *     ↑          ↑                       |
     *   CMP-1 ───────┴───────────────────────┘   CMP-1 has three parents
     *
     *   CMP-2 → SEG-1,  CMP-2 → <not imported>
     *   LOOP-1 ⇄ LOOP-2                      a cycle with nothing above it
     *   HUB    ← FAN-1 … FAN-8               more neighbours than a small cap admits
     * ```
     *
     * `SEG-1 → SEG-2` is the edge that matters most: it is never traversed on the way from CMP-1 to
     * a root, so an implementation returning only the walk's own edges omits it — and the picture
     * then hides a real dependency between two siblings (§3.2).
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
                CREATE (hub:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'hub-1', __moduleUrl: ${'$'}cmp, __name: 'HUB-1', __version: 'current',
                    __sortKey: '000100', id: 'HUB-1', objectNumber: '9.1', objectLevel: 2,
                    `Object Text`: 'Everything hangs off this'
                })

                CREATE (seg1)-[:refersTo]->(sys1)
                CREATE (seg2)-[:refersTo]->(sys1)
                CREATE (seg1)-[:refersTo]->(seg2)
                CREATE (cmp1)-[:refersTo]->(seg1)
                CREATE (cmp1)-[:refersTo]->(seg2)
                CREATE (cmp1)-[:refersTo]->(sys9)
                CREATE (cmp2)-[:refersTo]->(seg1)
                CREATE (cmp2)-[:refersTo]->(gone)
                CREATE (loop1)-[:refersTo]->(loop2)
                CREATE (loop2)-[:refersTo]->(loop1)

                WITH hub
                UNWIND range(1, 8) AS n
                CREATE (f:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'fan-' + n, __moduleUrl: ${'$'}cmp, __name: 'FAN-' + n, __version: 'current',
                    __sortKey: '0002' + toString(n), id: 'FAN-' + n,
                    objectNumber: '9.1.' + n, objectLevel: 3,
                    `Object Text`: 'A leaf of the hub'
                })
                CREATE (f)-[:refersTo]->(hub)
                """.trimIndent(),
                mapOf("sys" to systemModule, "seg" to segmentModule, "cmp" to componentModule),
            ),
        ) { }
    }

    /** The Tier-2 configuration the bands read: system levels on two of the three modules. */
    private suspend fun configure() {
        metaWriter.saveModuleSettings(systemModule, SystemLevelChange.Set("L1"), access = AccessSet.SEES_ALL)
        metaWriter.saveModuleSettings(segmentModule, SystemLevelChange.Set("L2"), access = AccessSet.SEES_ALL)
        // The component module is deliberately left unclassified: everything in it has to land in
        // the "No system level set" band, and never be folded into a real level (§4.1).
    }

    private fun graph(
        seed: String,
        depth: Int = 2,
        direction: GraphDirection = GraphDirection.BOTH,
        levels: GraphLevelStrategy = GraphLevelStrategy.MODULE_SYSTEM_LEVEL,
        maxNodes: Int = 300,
    ) = runBlocking {
        assertNotNull(
            projection.getGraph(
                listOf(seed),
                AccessSet.SEES_ALL,
                depth = depth,
                direction = direction,
                levelStrategy = levels,
                maxNodes = maxNodes,
            ),
            "no graph for $seed",
        )
    }

    private fun ids(seed: String, vararg rest: String) = (listOf(seed) + rest).map(Ref::encode).toSet()

    /**
     * §7: "The induced subgraph contains edges between neighbours, not only traversal edges."
     *
     * `SEG-1 → SEG-2` is never walked on any path out of CMP-1 — both are reached in the same hop,
     * from below — so an implementation that returns the walk's own edges drops it, and the picture
     * silently hides a dependency between two siblings.
     */
    @Test
    fun `the induced subgraph carries edges between neighbours, not only traversal edges`() {
        val result = graph("cmp-1")

        // CMP-2 belongs here and is easy to forget: two hops in BOTH directions reaches it by
        // going *up* to SEG-1 and then *down* again, which is exactly the sibling branch a
        // dependency picture exists to show.
        assertEquals(
            ids("cmp-1", "seg-1", "seg-2", "sys-1", "sys-9", "cmp-2"),
            result.nodes.map { it.card.ref }.toSet(),
        )
        assertTrue(
            result.edges.any { it.source == Ref.encode("seg-1") && it.target == Ref.encode("seg-2") },
            "the sibling edge SEG-1 → SEG-2 is missing: only traversal edges were returned",
        )
        assertFalse(result.truncated)
    }

    /** The seed says so on itself, so the canvas can mark it without being told separately. */
    @Test
    fun `the seed is marked, and only the seed`() {
        val result = graph("cmp-1")

        assertEquals(listOf(Ref.encode("cmp-1")), result.seedRefs)
        assertEquals(listOf(Ref.encode("cmp-1")), result.nodes.filter { it.seed }.map { it.card.ref })
    }

    /**
     * Direction is a scope control, never a re-drawing of the data: the edge list stays directed
     * whichever way the walk went (§3.2).
     */
    @Test
    fun `direction scopes the walk but never reverses an edge`() {
        val outgoing = graph("cmp-1", direction = GraphDirection.OUTGOING)
        // Only what CMP-1 refines, and what those refine: never CMP-2, which refines SEG-1.
        assertEquals(ids("cmp-1", "seg-1", "seg-2", "sys-1", "sys-9"), outgoing.nodes.map { it.card.ref }.toSet())

        val incoming = graph("seg-1", direction = GraphDirection.INCOMING)
        assertEquals(ids("seg-1", "cmp-1", "cmp-2"), incoming.nodes.map { it.card.ref }.toSet())
        // Drawn source → target as the data states it, not source → seed as the walk found it.
        assertTrue(
            incoming.edges.any { it.source == Ref.encode("cmp-2") && it.target == Ref.encode("seg-1") },
        )
        assertTrue(incoming.edges.none { it.source == Ref.encode("seg-1") })
    }

    /** §7: dangling links surface as placeholders and populate the banner's module list (§1.1). */
    @Test
    fun `a dangling link surfaces as a placeholder and names the module to import`() {
        val result = graph("cmp-2")

        val placeholder = result.nodes.single { !it.card.resolved }
        assertEquals(Ref.encode("missing-1"), placeholder.card.ref)
        // R5: a placeholder carries no DOORS id and its internal name never reaches the wire.
        assertNull(placeholder.card.id)
        assertEquals("", placeholder.card.description)

        // The module node was never imported either, so there is nothing to name and nothing to
        // link — and __moduleUrl, which the placeholder does carry, is not ours to expose.
        val unresolved = result.unresolvedModules.single()
        assertEquals(1, unresolved.count)
        assertNull(unresolved.ref)
        assertFalse(unresolved.name.startsWith("__"))
        assertFalse(unresolved.name.contains("module-not-imported"))
    }

    /**
     * §7: "The 300-node cap sets `truncated` and populates `truncatedNeighbours` on boundary nodes."
     *
     * Run at a small cap so the fixture does not need 300 objects. What matters is the shape: the
     * response says it was cut, and the node that was cut says how much of it is missing — a graph
     * that simply stops, unmarked, is read as a graph that ended (§1.1).
     */
    @Test
    fun `the node cap truncates and every boundary node says how much was cut`() {
        val result = graph("hub-1", direction = GraphDirection.INCOMING, maxNodes = 4)

        assertTrue(result.truncated, "the cap was hit and the response does not say so")
        assertEquals(4, result.nodes.size)

        val hub = result.nodes.single { it.card.ref == Ref.encode("hub-1") }
        // Eight fans refine the hub, three were admitted, so five are outside the picture.
        assertEquals(5, hub.truncatedNeighbours)
    }

    /**
     * A neighbour beyond the depth bound is not truncation — the user asked for two hops and got
     * two — but the node at the boundary still has to say the graph continues past it.
     */
    @Test
    fun `a node at the depth bound reports its neighbours outside the picture`() {
        val result = graph("cmp-1", depth = 1, direction = GraphDirection.OUTGOING)

        assertEquals(ids("cmp-1", "seg-1", "seg-2", "sys-9"), result.nodes.map { it.card.ref }.toSet())
        assertFalse(result.truncated, "the depth bound is what the user asked for, not truncation")

        // Counted in both directions, whichever way the walk went: SEG-1 refines SYS-1 (outside)
        // and SEG-2 (inside), and is refined by CMP-1 (inside) and CMP-2 (outside). Two neighbours
        // are missing from the picture, and an outgoing-only walk must still say so — the whole
        // point of §1.1 is that a missing incoming arrow is the one a reviewer over-reads.
        val seg1 = result.nodes.single { it.card.ref == Ref.encode("seg-1") }
        assertEquals(2, seg1.truncatedNeighbours)
    }

    /** A cycle produces a picture rather than a hang, and both of its edges survive (§4.5). */
    @Test
    fun `a cycle is returned whole, both edges intact`() {
        val result = graph("loop-1")

        assertEquals(ids("loop-1", "loop-2"), result.nodes.map { it.card.ref }.toSet())
        assertEquals(2, result.edges.size)
        assertTrue(result.edges.any { it.source == Ref.encode("loop-1") && it.target == Ref.encode("loop-2") })
        assertTrue(result.edges.any { it.source == Ref.encode("loop-2") && it.target == Ref.encode("loop-1") })
    }

    /**
     * The default strategy is the module's L0–L4 classification, and an unclassified module lands
     * in its own band at the bottom rather than being folded into a real level (§4.1, ADR 0011).
     */
    @Test
    fun `system levels come from the module classification, and unclassified gets its own band`() {
        val result = graph("cmp-1")

        val byRef = result.nodes.associateBy { it.card.ref }
        assertEquals(1, byRef.getValue(Ref.encode("sys-1")).level)
        assertEquals(2, byRef.getValue(Ref.encode("seg-1")).level)
        // The component module carries no classification, so its level is unknown — never 0.
        assertNull(byRef.getValue(Ref.encode("cmp-1")).level)

        // The bands the client draws: only the levels that occur, unknown always last.
        assertEquals(listOf(1, 2, null), result.levels.map { it.level })
        assertEquals("L1 – System of Systems", result.levels[0].label)
        assertEquals("L2 – Segment", result.levels[1].label)
        assertEquals("No system level set", result.levels[2].label)
        assertTrue(result.levels.none { it.label.contains("__") }, "an internal name reached a band label")
    }

    /** The fallback for a graph with no classification anywhere: every node still gets a band. */
    @Test
    fun `graph rank places every node, counting down from what refines nothing`() {
        val result = graph("cmp-1", levels = GraphLevelStrategy.GRAPH_RANK)

        val byRef = result.nodes.associateBy { it.card.ref }
        // SYS-1 and SYS-9 refine nothing inside the picture, so they are its top.
        assertEquals(0, byRef.getValue(Ref.encode("sys-1")).level)
        assertEquals(0, byRef.getValue(Ref.encode("sys-9")).level)
        // SEG-2 refines SYS-1; SEG-1 refines SEG-2, so it sits one lower still — the longest path,
        // not the shortest, which is what keeps an edge from ever pointing sideways-up.
        assertEquals(1, byRef.getValue(Ref.encode("seg-2")).level)
        assertEquals(2, byRef.getValue(Ref.encode("seg-1")).level)
        assertEquals(3, byRef.getValue(Ref.encode("cmp-1")).level)

        assertTrue(result.nodes.all { it.level != null }, "graph rank left a node unplaced")
        assertTrue(result.levels.none { it.level == null }, "an unplaced band was drawn with nothing in it")
    }

    /** A cycle has no longest path, and must still terminate and place both of its nodes. */
    @Test
    fun `graph rank terminates on a cycle`() {
        val result = graph("loop-1", levels = GraphLevelStrategy.GRAPH_RANK)

        assertTrue(result.nodes.all { it.level != null })
    }

    /** The outline strategy reads the object's own depth, which is a different number entirely. */
    @Test
    fun `outline level reads the object's own outline depth`() {
        val result = graph("cmp-1", levels = GraphLevelStrategy.OUTLINE_LEVEL)

        val byRef = result.nodes.associateBy { it.card.ref }
        assertEquals(2, byRef.getValue(Ref.encode("cmp-1")).level)
        assertEquals(1, byRef.getValue(Ref.encode("seg-1")).level)
        assertEquals(listOf("Outline level 1", "Outline level 2"), result.levels.map { it.label })
    }

    /** §7: "The same request twice returns byte-identical JSON." */
    @Test
    fun `the same scope twice returns the same picture, in the same order`() {
        val first = graph("cmp-1")
        val second = graph("cmp-1")

        assertEquals(first, second)
        // Stated separately, because data-class equality on lists is order-sensitive and that is
        // exactly the property the layout depends on — an order that is a function of the planner
        // makes the same scope draw differently between two opens (§4.6).
        assertEquals(first.nodes.map { it.card.ref }, second.nodes.map { it.card.ref })
        assertEquals(first.edges, second.edges)
    }

    /** The card is the shared one: the graph node carries what the breakdown row carries. */
    @Test
    fun `a node carries the shared requirement card, verification attributes and all`() {
        val result = graph("cmp-1")

        val seg1 = result.nodes.single { it.card.ref == Ref.encode("seg-1") }.card
        assertEquals("SEG-1", seg1.id)
        assertEquals("The wing shall generate lift", seg1.description)
        assertEquals("L2", seg1.level?.code)
        assertEquals("Segment requirements", seg1.moduleName)
        assertEquals(Ref.encode(segmentModule), seg1.moduleRef)
    }

    /** A hand-edited reference is a 404, not an empty picture presented as an answer. */
    @Test
    fun `an unknown seed has no graph`() {
        val result = runBlocking { projection.getGraph(listOf("no-such-object"), access = AccessSet.SEES_ALL) }

        assertNull(result)
    }
}
