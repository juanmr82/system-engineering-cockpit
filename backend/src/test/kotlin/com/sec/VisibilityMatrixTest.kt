package com.sec

import com.sec.config.Neo4jSettings
import com.sec.config.WindchillSettings
import com.sec.domain.GraphDirection
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.security.AccessResolver
import com.sec.security.AccessSet
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.DoorsTableProjection
import com.sec.source.doors.RequirementCardProjection
import com.sec.source.doors.ReviewProjection
import com.sec.source.doors.StatisticsProjection
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraLinkGraphProjection
import com.sec.source.windchill.WindchillProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4's acceptance gate (`docs/features/access-control.md` §14.1): **every** read path, over one
 * fixture, as four different callers.
 *
 * The shape the spec asks for: two modules, two categories, three groups — one granted A, one
 * granted A and B, one with `seesAll` — and a user in no group at all. Every assertion below is the
 * same question asked four ways, which is what makes a filter that was forgotten on one statement
 * visible as a row, a count, an edge or a badge that does not move when the caller changes.
 *
 * **Projections, not HTTP.** `AccessControlFeatureTest` established that, and it is the right layer:
 * the 404-vs-403 rule is decided *here*, by a read that comes back empty, and the routes' mapping of
 * an empty read to a `404` is already covered by their own tests. What this proves is the half that
 * cannot be faked — that an unauthorized object and a nonexistent one are indistinguishable to the
 * layer that answers, so no route above it has anything to leak. `403` is never in reach: not one
 * read path in this file can tell the two cases apart, which is the point.
 *
 * ## The fixture, and why each piece is in it
 *
 * ```
 *   module A  (category A)            module B  (category B)
 *     a1 ──────────refersTo──────────►  b1 ──refersTo──►  b2
 *     a2 ──refersTo──► a1                    table B (one row, one cell)
 *     a-deleted  (:__DELETED, links to a1)
 *     a-ghost    (:__UNDEFINED, in an unimported module, links from a1)
 * ```
 *
 * - **`a1 → b1 → b2` crosses the category boundary twice**, which is what makes the `+n` badge
 *   testable: for a caller granted A **and** B, seeding at `a1` with one hop admits `{a1, b1}` and
 *   `b2` falls outside it, so `b1` carries `+1`. For a caller granted A alone, `b1` is not a row at
 *   all, so nothing is admitted beyond `a1` and **no badge appears anywhere**. Filter the cards
 *   without the neighbour statements and that second case reports `+1` on `a1` instead — the leak
 *   the plan calls the sharpest, and the one assertion here worth reading twice.
 * - **`a-deleted`** pins §16.1a's first row: a `:__DELETED` object keeps `:DOORSObject` and
 *   `__moduleUrl`, so the existing DOORS containment already reaches it. Nothing proved that before.
 * - **`a-ghost`** pins §16.1a's third row: a placeholder whose module was never imported has no
 *   container that resolves and is therefore invisible to everyone — `seesAll` excepted, which is
 *   what `seesAll` means.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class VisibilityMatrixTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var review: ReviewProjection
    private lateinit var doors: DoorsProjection
    private lateinit var breakdown: BreakdownProjection
    private lateinit var graph: DependencyGraphProjection
    private lateinit var statistics: StatisticsProjection
    private lateinit var tables: DoorsTableProjection
    private lateinit var windchill: WindchillProjection
    private lateinit var jiraIssues: JiraIssuesProjection
    private lateinit var jiraGraph: JiraLinkGraphProjection

    /** The four callers of §14.1, resolved once from the graph the fixture set up. */
    private lateinit var onlyA: AccessSet
    private lateinit var bothAB: AccessSet
    private lateinit var everything: AccessSet
    private val noGroup = AccessSet.NONE

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()

        val cards = RequirementCardProjection(graphDriver)
        review = ReviewProjection(graphDriver)
        doors = DoorsProjection(graphDriver)
        breakdown = BreakdownProjection(graphDriver, cards)
        graph = DependencyGraphProjection(graphDriver, cards)
        statistics = StatisticsProjection(graphDriver)
        tables = DoorsTableProjection(graphDriver)
        windchill = WindchillProjection(graphDriver, WindchillSettings(host = ""))
        jiraIssues = JiraIssuesProjection(graphDriver, "https://jira.example.com")
        jiraGraph = JiraLinkGraphProjection(graphDriver)

        runBlocking {
            MetaSchema.apply(graphDriver)
            graphDriver.executeWrite(Query(FIXTURE, emptyMap())) { }

            val resolver = AccessResolver(graphDriver)
            onlyA = resolver.resolve(listOf(GROUP_A))
            bothAB = resolver.resolve(listOf(GROUP_AB))
            everything = resolver.resolve(listOf(GROUP_ALL))
        }
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    // -- the resolver itself ---------------------------------------------------------------------

    @Test
    fun `the four callers resolve to the sets the matrix assumes`() {
        assertEquals(1, onlyA.categoryIds.size, "the A group should hold exactly one grant")
        assertEquals(2, bothAB.categoryIds.size, "the A+B group should hold exactly two grants")
        assertTrue(everything.seesAll, "the seesAll group should short-circuit the predicate")
        assertTrue(noGroup.categoryIds.isEmpty() && !noGroup.seesAll)
    }

    // -- rows and counts -------------------------------------------------------------------------

    @Test
    fun `module rows and their total move with the caller`() = runBlocking {
        // Module A lists a1 and a2. The ghost belongs to an *unimported* module, so it was never
        // part of A's listing; the deleted object is out of every listing by ADR 0012.
        assertEquals(2, review.getModuleObjects(MODULE_A, onlyA).total)
        assertEquals(2, review.getModuleObjects(MODULE_A, bothAB).total)
        assertEquals(2, review.getModuleObjects(MODULE_A, everything).total)
        assertEquals(0, review.getModuleObjects(MODULE_A, noGroup).total)

        // Module B lists five: b1, b2 and the table's three objects. A DOORS table is made of
        // ordinary objects, which is exactly why it needs no visibility rule of its own.
        assertEquals(0, review.getModuleObjects(MODULE_B, onlyA).total)
        assertEquals(5, review.getModuleObjects(MODULE_B, bothAB).total)
        assertEquals(5, review.getModuleObjects(MODULE_B, everything).total)
        assertEquals(0, review.getModuleObjects(MODULE_B, noGroup).total)
    }

    @Test
    fun `the count and the page it paginates carry the identical filter`() = runBlocking {
        // A count taken over a wider set than its own rows is a paginator promising pages it will
        // then answer empty (spec §7, "LIMIT and page totals anywhere").
        for (access in listOf(onlyA, bothAB, everything, noGroup)) {
            for (module in listOf(MODULE_A, MODULE_B)) {
                val page = review.getModuleObjects(module, access, skip = 0, limit = 1_000)
                assertEquals(page.rows.size, page.total, "count and rows disagree for $module")
            }
        }
    }

    @Test
    fun `the Modules listing names only modules the caller may read`() = runBlocking {
        assertEquals(listOf(MODULE_A), doors.listModules(onlyA).map { it.name })
        assertEquals(listOf(MODULE_A, MODULE_B), doors.listModules(bothAB).map { it.name }.sorted())
        assertEquals(listOf(MODULE_A, MODULE_B), doors.listModules(everything).map { it.name }.sorted())
        assertEquals(emptyList(), doors.listModules(noGroup).map { it.name })
    }

    @Test
    fun `an unauthorized module is indistinguishable from one that does not exist`() = runBlocking {
        // The 404-vs-403 rule, at the layer that decides it: both answers are the same answer, so
        // no route above this can respond with anything but the same status for both.
        assertEquals(
            doors.moduleExists("no-such-module-at-all", onlyA),
            doors.moduleExists(MODULE_B, onlyA),
        )
        assertNull(doors.getModuleDetail(MODULE_B, onlyA))
        assertNull(doors.getModuleDetail("no-such-module-at-all", onlyA))
        assertNotNull(doors.getModuleDetail(MODULE_B, bothAB))
    }

    @Test
    fun `attribute discovery reads only objects the caller may read`() = runBlocking {
        // `Object Text` is carried by module B's objects alone in this fixture, so a caller who
        // cannot see them must not be offered it as a column.
        assertTrue("Object Text" !in doors.discoverAttributeNames(MODULE_B, onlyA))
        assertTrue("Object Text" in doors.discoverAttributeNames(MODULE_B, bothAB))
        assertTrue(doors.discoverAttributeNames(MODULE_B, noGroup).isEmpty())
    }

    // -- edges: both endpoints, or the edge is not there ------------------------------------------

    @Test
    fun `an edge is absent unless both of its endpoints are visible`() = runBlocking {
        // a1 -> b1 crosses the boundary. For the A-only caller the far end is invisible, so the
        // reference is *gone* — not struck through, not "unresolved", not counted (spec §7).
        val rowForA = review.getModuleObjects(MODULE_A, onlyA).rows.single { it.id == "A-1" }
        assertEquals(emptyList(), rowForA.references.outgoing.mapNotNull { it.id })

        val rowForAB = review.getModuleObjects(MODULE_A, bothAB).rows.single { it.id == "A-1" }
        assertEquals(listOf("B-1"), rowForAB.references.outgoing.mapNotNull { it.id })

        // The same edge from the traces endpoint, which is a different statement and could drift.
        assertEquals(0, review.getTraces(ITEM_A1, incoming = false, access = onlyA).references.size)
        assertEquals(1, review.getTraces(ITEM_A1, incoming = false, access = bothAB).references.size)
    }

    @Test
    fun `an incoming edge is filtered the same way an outgoing one is`() = runBlocking {
        // b1's incoming edge comes from a1, in module A. The B-only direction is covered by the
        // A-only caller above; here the A+B caller must see it and nobody else's view may.
        assertEquals(1, review.getTraces(ITEM_B1, incoming = true, access = bothAB).references.size)
        assertEquals(0, review.getTraces(ITEM_B1, incoming = true, access = onlyA).references.size)
        assertEquals(0, review.getTraces(ITEM_B1, incoming = true, access = noGroup).references.size)
    }

    @Test
    fun `an unauthorized item detail is indistinguishable from a missing one`() = runBlocking {
        assertNull(review.getItemDetail(ITEM_B1, onlyA))
        assertNull(review.getItemDetail("no-such-item-at-all", onlyA))
        assertNotNull(review.getItemDetail(ITEM_B1, bothAB))
        assertNull(review.getItemDetail(ITEM_A1, noGroup))
    }

    // -- the dependency graph, and the badge that is easiest to get wrong -------------------------

    @Test
    fun `the plus-n badge counts only neighbours the caller can see`() = runBlocking {
        // A+B, one hop from a1: {a1, b1} admitted, b2 outside it, so b1 reports exactly one
        // neighbour that the picture does not draw.
        val forAB = assertNotNull(graph.getGraph(listOf(ITEM_A1), bothAB, depth = 1))
        // Direction BOTH, so a2 and the deleted object come in on the incoming hop as well.
        val badgesAB = forAB.nodes.associate { it.card.id.orEmpty() to it.truncatedNeighbours }
        assertEquals(mapOf("A-1" to 0, "A-2" to 0, "B-1" to 1, "B-GONE" to 0), badgesAB)

        // A alone, same seed and depth: b1 is not a row, so nothing is admitted past a1 and no
        // badge anywhere counts it. A +1 here would be the disclosure this whole group exists to
        // prevent — the number would grow by exactly what the reader may not see.
        // A alone, same seed and depth: b1 is not a row, so it is neither admitted nor counted,
        // and neither is the deleted object that points at a1 from category B. What remains is the
        // one edge this caller may see, with every badge at zero. A +1 anywhere here would be the
        // disclosure this group exists to prevent — the number would grow by exactly what the
        // reader may not see.
        val forA = assertNotNull(graph.getGraph(listOf(ITEM_A1), onlyA, depth = 1))
        assertEquals(
            mapOf("A-1" to 0, "A-2" to 0),
            forA.nodes.associate { it.card.id.orEmpty() to it.truncatedNeighbours },
        )
        assertEquals(1, forA.edges.size, "the a2 -> a1 edge is inside category A and stays")
    }

    /**
     * The badge inflation itself, isolated — and it needs a seed one hop *further out* than the
     * obvious one.
     *
     * Seeding at a1 does not expose it: an invisible neighbour would be admitted by an unfiltered
     * walk and then dropped again for having no card, so it lands inside the set and is never
     * counted as cut. The leak appears when the hidden object is **beyond** the admitted set, which
     * is what seeding at a2 with one hop arranges: the picture is `{a2, a1}`, and a1's own links to
     * b1 and to the ghost are exactly the neighbours the badge counts.
     *
     * So: a1 reports 2 for a caller who may see b1's category, and **0** for one who may not. With
     * the predicate removed from the two neighbour statements — cards still filtered, which is the
     * half-filtered state the plan warns about — this assertion reads 2 in both rows. That was
     * confirmed by deliberately removing it, exactly as `GraphNamesTest`'s guard was verified.
     */
    @Test
    fun `a badge never counts a neighbour beyond the picture that the caller may not see`() = runBlocking {
        val forAB = assertNotNull(graph.getGraph(listOf(ITEM_A2), bothAB, depth = 1))
        assertEquals(
            mapOf("A-2" to 0, "A-1" to 2),
            forAB.nodes.associate { it.card.id.orEmpty() to it.truncatedNeighbours },
            "a1 links out to b1 and to the deleted object, neither of which is in this picture",
        )

        val forA = assertNotNull(graph.getGraph(listOf(ITEM_A2), onlyA, depth = 1))
        assertEquals(
            mapOf("A-2" to 0, "A-1" to 0),
            forA.nodes.associate { it.card.id.orEmpty() to it.truncatedNeighbours },
            "every neighbour beyond this picture is in category B, so this caller has no badge",
        )
    }

    @Test
    fun `a graph seeded on an invisible object is a 404, not an empty picture`() = runBlocking {
        assertNull(graph.getGraph(listOf(ITEM_B1), onlyA, depth = 2))
        assertNull(graph.getGraph(listOf("no-such-item-at-all"), everything, depth = 2))
        assertNotNull(graph.getGraph(listOf(ITEM_B1), bothAB, depth = 2))
        assertNull(graph.getGraph(listOf(ITEM_A1), noGroup, depth = 2))
    }

    @Test
    fun `the unresolved-modules banner names nothing the caller cannot see`() = runBlocking {
        // a-ghost is a placeholder in an unimported module, so it has no container that resolves
        // and is invisible to every group (§16.1a, third row). It therefore cannot reach the
        // banner, whose whole content is drawn from the placeholders in the picture.
        val forA = assertNotNull(graph.getGraph(listOf(ITEM_A1), onlyA, depth = 2))
        assertEquals(emptyList(), forA.unresolvedModules.map { it.name })

        // seesAll is the exception, and it is what the word means: it sees the ghost and the
        // banner names its module honestly.
        val forAll = assertNotNull(
            graph.getGraph(listOf(ITEM_A1), everything, depth = 2, direction = GraphDirection.BOTH),
        )
        assertTrue(forAll.nodes.any { !it.card.resolved }, "seesAll should reach the placeholder")
    }

    // -- the breakdown tree ----------------------------------------------------------------------

    @Test
    fun `the breakdown branch simply ends at an invisible parent`() = runBlocking {
        // a2 refines a1 refines b1. For the A-only caller the climb stops at a1 with no marker of
        // any kind: no "loops back to", no ellipsis, no truncated flag (spec §7).
        val forA = assertNotNull(breakdown.getBreakdown(ITEM_A2, onlyA))
        assertEquals(setOf("A-1", "A-2"), forA.nodes.mapNotNull { it.id }.toSet())
        assertEquals(false, forA.truncated, "an invisible parent is not truncation")

        // With both categories the climb continues into B, and the descent picks up the deleted
        // object that still points at a1 — it is a real imported object and renders as one.
        val forAB = assertNotNull(breakdown.getBreakdown(ITEM_A2, bothAB))
        assertEquals(
            setOf("A-1", "A-2", "B-1", "B-2", "B-GONE"),
            forAB.nodes.mapNotNull { it.id }.toSet(),
        )
    }

    @Test
    fun `a breakdown seeded on an invisible object is a 404`() = runBlocking {
        assertNull(breakdown.getBreakdown(ITEM_B1, onlyA))
        assertNotNull(breakdown.getBreakdown(ITEM_B1, bothAB))
        assertNull(breakdown.getBreakdown(ITEM_A1, noGroup))
    }

    // -- statistics: every number over the visible subgraph ---------------------------------------

    @Test
    fun `every statistic is computed over the graph the caller can see`() = runBlocking {
        val forA = assertNotNull(statistics.getStatistics(null, onlyA))
        assertEquals(1, forA.census.modules)
        assertEquals(2, forA.census.items)
        // a1's only outgoing link points into module B, so for this caller it is not a link.
        assertEquals(1, forA.census.links, "only the a2 -> a1 link is inside A")

        val forAB = assertNotNull(statistics.getStatistics(null, bothAB))
        assertEquals(2, forAB.census.modules)
        // Seven: a1, a2 and module B's five, the table's three objects included.
        assertEquals(7, forAB.census.items)
        // a2 -> a1, a1 -> b1 and b1 -> b2. a1 -> ghost is not a link this caller has, because the
        // ghost has no container that resolves and is invisible to every group (§16.1a).
        assertEquals(3, forAB.census.links)

        val forNobody = assertNotNull(statistics.getStatistics(null, noGroup))
        assertEquals(0, forNobody.census.modules)
        assertEquals(0, forNobody.census.items)
        assertEquals(0, forNobody.census.links)
    }

    @Test
    fun `modulesWithoutSystemLevel is a list of names and names only visible ones`() = runBlocking {
        // The one value on this page that puts module *names* on the wire rather than a number.
        assertEquals(listOf(MODULE_A), assertNotNull(statistics.getStatistics(null, onlyA)).modulesWithoutSystemLevel)
        assertEquals(
            listOf(MODULE_A, MODULE_B),
            assertNotNull(statistics.getStatistics(null, bothAB)).modulesWithoutSystemLevel.sorted(),
        )
        assertEquals(emptyList(), assertNotNull(statistics.getStatistics(null, noGroup)).modulesWithoutSystemLevel)
    }

    @Test
    fun `statistics scoped to an invisible module is a 404 rather than a page of zeroes`() = runBlocking {
        assertNull(statistics.getStatistics(MODULE_B, onlyA))
        assertNotNull(statistics.getStatistics(MODULE_B, bothAB))
    }

    @Test
    fun `the cycle scan examines only edges the caller can see`() = runBlocking {
        // A alone sees one edge, a2 -> a1. A+B sees four: that one, a1 -> b1, b1 -> b2, and the
        // deleted object's leftover link into a1. a1 -> ghost is in nobody's scan.
        assertEquals(1, statistics.getCycles(null, onlyA).edgesExamined)
        assertEquals(4, statistics.getCycles(null, bothAB).edgesExamined)
        assertEquals(0, statistics.getCycles(null, noGroup).edgesExamined)
    }

    // -- tables, which inherit their module's categories through the reconciler --------------------

    @Test
    fun `DOORS tables follow their module rather than needing a rule of their own`() = runBlocking {
        // Spec §7's last row says to assert this rather than assume it.
        assertEquals(0, tables.getModuleTables(MODULE_B, onlyA).size)
        assertEquals(1, tables.getModuleTables(MODULE_B, bothAB).size)
        assertEquals(0, tables.getModuleTables(MODULE_B, noGroup).size)

        assertNull(tables.getTableFor(ITEM_B_CELL, onlyA))
        assertNotNull(tables.getTableFor(ITEM_B_CELL, bothAB))
    }

    // -- §16.1a: what has no container that resolves ----------------------------------------------

    @Test
    fun `a DOORS-deleted object inherits its module the way any other object does`() = runBlocking {
        // §16.1a's first row, which had no test: a :__DELETED object keeps :DOORSObject and
        // __moduleUrl, so AccessContainment.doors already reaches it and nothing new was needed.
        // It is out of every module listing (ADR 0012) but still reachable as a link's far end.
        val forAB = review.getTraces(ITEM_A1, incoming = true, access = bothAB).references
        assertTrue(forAB.any { it.deletedInSource }, "the deleted object should still be a far end")

        // And it is filtered like anything else: tag it into B's category only, and A loses it.
        assertTrue(
            review.getTraces(ITEM_A1, incoming = true, access = onlyA).references.none { it.deletedInSource },
            "a deleted object in another category must not appear as a far end",
        )
    }

    @Test
    fun `a placeholder whose module was never imported is invisible to every group`() = runBlocking {
        // §16.1a's third row, and the cost it accepts in writing: the incoming-arrow evidence is
        // shown to nobody until the module it names is imported and categorised.
        for (access in listOf(onlyA, bothAB, noGroup)) {
            assertNull(review.getItemDetail(ITEM_GHOST, access))
        }
        assertNotNull(review.getItemDetail(ITEM_GHOST, everything))
    }

    // -- the other two sources ---------------------------------------------------------------------

    @Test
    fun `Windchill documents are filtered, and the cap is counted after filtering`() = runBlocking {
        assertEquals(listOf("Doc A"), windchill.listDocuments(onlyA).rows.map { it.name })
        assertEquals(listOf("Doc A", "Doc B"), windchill.listDocuments(bothAB).rows.map { it.name }.sorted())
        assertEquals(emptyList(), windchill.listDocuments(noGroup).rows.map { it.name })
        // `total` is the row count of what came back, so it moves with the caller by construction.
        assertEquals(1, windchill.listDocuments(onlyA).total)
    }

    @Test
    fun `JIRA issues and their totals are filtered together`() = runBlocking {
        val forA = jiraIssues.listIssues(page = 0, size = 50, sort = SORT, direction = DIRECTION, access = onlyA)
        assertEquals(listOf("AAA-1"), forA.rows.map { it.key })
        assertEquals(1, forA.total)

        val forAB = jiraIssues.listIssues(page = 0, size = 50, sort = SORT, direction = DIRECTION, access = bothAB)
        assertEquals(listOf("AAA-1", "BBB-1"), forAB.rows.map { it.key }.sorted())
        assertEquals(2, forAB.total)

        val forNobody = jiraIssues.listIssues(
            page = 0, size = 50, sort = SORT, direction = DIRECTION, access = noGroup,
        )
        assertEquals(emptyList(), forNobody.rows.map { it.key })
        assertEquals(0, forNobody.total)
    }

    @Test
    fun `a JIRA link to an invisible issue is neither an edge nor a badge`() = runBlocking {
        val forA = assertNotNull(jiraGraph.graphOf(JIRA_A1, depth = 2, access = onlyA))
        assertEquals(listOf("AAA-1"), forA.nodes.map { it.key })
        assertEquals(emptyList(), forA.edges.map { it.source })
        assertEquals(listOf(0), forA.nodes.map { it.truncatedNeighbours })

        val forAB = assertNotNull(jiraGraph.graphOf(JIRA_A1, depth = 2, access = bothAB))
        assertEquals(listOf("AAA-1", "BBB-1"), forAB.nodes.map { it.key }.sorted())
        assertEquals(1, forAB.edges.size)

        assertNull(jiraGraph.graphOf(JIRA_A1, depth = 2, access = noGroup))
    }

    private companion object {
        const val MODULE_A = "module-a"
        const val MODULE_B = "module-b"
        const val ITEM_A1 = "item-a1"
        const val ITEM_A2 = "item-a2"
        const val ITEM_B1 = "item-b1"
        const val ITEM_B_CELL = "item-b-cell"
        const val ITEM_GHOST = "item-a-ghost"
        const val JIRA_A1 = "jira-issue-a1"

        const val GROUP_A = "/SEC/A"
        const val GROUP_AB = "/SEC/AB"
        const val GROUP_ALL = "/SEC/All"

        val SORT = JiraIssuesProjection.SortField.KEY
        val DIRECTION = JiraIssuesProjection.SortDirection.ASC

        /**
         * Literals throughout, deliberately: a fixture built from the same constants the projection
         * reads would let a wrong constant pass on both sides (backend/CLAUDE.md, "Test fixtures
         * deliberately keep the literals").
         *
         * Every object is tagged directly rather than through the reconciler. `AccessReconcilerTest`
         * owns propagation; what this file tests is what the read paths do once the tags exist, and
         * running the reconciler here would make a filtering bug look like a propagation bug.
         */
        val FIXTURE = """
            CYPHER 25
            CREATE (catA:__Meta:__AccessCategory {
              __metaId: 'cat-a', __metaKind: 'accessCategory', __schemaVersion: 1,
              __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z',
              key: 'cat-a', name: 'Category A', everyGroup: false })
            CREATE (catB:__Meta:__AccessCategory {
              __metaId: 'cat-b', __metaKind: 'accessCategory', __schemaVersion: 1,
              __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z',
              key: 'cat-b', name: 'Category B', everyGroup: false })

            CREATE (gA:__Group    { key: '/SEC/A',   name: 'A',       seesAll: false })
            CREATE (gAB:__Group   { key: '/SEC/AB',  name: 'A and B', seesAll: false })
            CREATE (gAll:__Group  { key: '/SEC/All', name: 'Everything', seesAll: true })
            CREATE (gA)-[:__mayRead  {__createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'}]->(catA)
            CREATE (gAB)-[:__mayRead {__createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'}]->(catA)
            CREATE (gAB)-[:__mayRead {__createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'}]->(catB)

            CREATE (mA:DOORSModule:DOORSObject:SEItem {
              __id: 'module-a', __name: 'module-a', __version: 'current' })
            CREATE (mB:DOORSModule:DOORSObject:SEItem {
              __id: 'module-b', __name: 'module-b', __version: 'current' })

            CREATE (a1:DOORSObject:DOORSRequirement:SEItem {
              __id: 'item-a1', __moduleUrl: 'module-a', __name: 'A-1', __version: 'current',
              __sortKey: '000001', id: 'A-1', objectNumber: '1', objectLevel: 1 })
            CREATE (a2:DOORSObject:DOORSRequirement:SEItem {
              __id: 'item-a2', __moduleUrl: 'module-a', __name: 'A-2', __version: 'current',
              __sortKey: '000002', id: 'A-2', objectNumber: '2', objectLevel: 1 })
            CREATE (b1:DOORSObject:DOORSRequirement:SEItem {
              __id: 'item-b1', __moduleUrl: 'module-b', __name: 'B-1', __version: 'current',
              __sortKey: '000001', id: 'B-1', objectNumber: '1', objectLevel: 1,
              `Object Text`: 'The first B requirement' })
            CREATE (b2:DOORSObject:DOORSRequirement:SEItem {
              __id: 'item-b2', __moduleUrl: 'module-b', __name: 'B-2', __version: 'current',
              __sortKey: '000002', id: 'B-2', objectNumber: '2', objectLevel: 1,
              `Object Text`: 'The second B requirement' })

            CREATE (aDel:DOORSObject:DOORSRequirement:SEItem:`__DELETED` {
              __id: 'item-a-deleted', __moduleUrl: 'module-b', __name: 'B-GONE', __version: 'current',
              __sortKey: '000009', id: 'B-GONE', objectNumber: '9', objectLevel: 1 })
            CREATE (ghost:SEItem:`__UNDEFINED` {
              __id: 'item-a-ghost', __moduleUrl: 'module-never-imported',
              __name: 'item-a-ghost', __version: 'unresolved' })

            CREATE (a2)-[:refersTo]->(a1)
            CREATE (a1)-[:refersTo]->(b1)
            CREATE (b1)-[:refersTo]->(b2)
            CREATE (aDel)-[:refersTo]->(a1)
            CREATE (a1)-[:refersTo]->(ghost)

            CREATE (tb:DOORSObject:DOORSTable:SEItem {
              __id: 'item-b-table', __moduleUrl: 'module-b', __name: 'B-T', __version: 'current',
              __sortKey: '000003', id: 'B-T', objectNumber: '3', objectLevel: 1 })
            CREATE (trow:DOORSObject:DOORSTableRow:SEItem {
              __id: 'item-b-row', __moduleUrl: 'module-b', __name: 'B-R', __version: 'current',
              __sortKey: '000004', id: 'B-R', objectNumber: '4', objectLevel: 2 })
            CREATE (tcell:DOORSObject:DOORSTableCell:SEItem {
              __id: 'item-b-cell', __moduleUrl: 'module-b', __name: 'B-C', __version: 'current',
              __sortKey: '000005', id: 'B-C', objectNumber: '5', objectLevel: 3,
              `Object Text`: 'a cell' })
            CREATE (tb)-[:__child]->(trow)
            CREATE (trow)-[:__child]->(tcell)

            CREATE (dA:WindchillDocument:SEItem {
              __id: 'doc-a', __name: 'Doc A', __version: 'current', __sortKey: 'A',
              ID: 'OR:wt.doc.WTDocument:1', Name: 'Doc A', Number: 'DOC-A',
              Version: '01 [1]', StateDisplay: 'Released', FolderLocation: '/a' })
            CREATE (dB:WindchillDocument:SEItem {
              __id: 'doc-b', __name: 'Doc B', __version: 'current', __sortKey: 'B',
              ID: 'OR:wt.doc.WTDocument:2', Name: 'Doc B', Number: 'DOC-B',
              Version: '01 [1]', StateDisplay: 'Released', FolderLocation: '/b' })

            CREATE (jA:SEItem:JiraIssue {
              __id: 'jira-issue-a1', __name: 'AAA-1: In A', __version: 'v1',
              __sortKey: 'AAA-000000001', __projectKey: 'AAA',
              key: 'AAA-1', id: '1', summary: 'In A' })
            CREATE (jB:SEItem:JiraIssue {
              __id: 'jira-issue-b1', __name: 'BBB-1: In B', __version: 'v1',
              __sortKey: 'BBB-000000001', __projectKey: 'BBB',
              key: 'BBB-1', id: '2', summary: 'In B' })
            CREATE (jA)-[:linkedTo {linkId: 'l1', typeName: 'Relates'}]->(jB)

            WITH catA, catB, mA, mB, a1, a2, b1, b2, aDel, tb, trow, tcell, dA, dB, jA, jB
            UNWIND [
              {n: mA, c: catA}, {n: a1, c: catA}, {n: a2, c: catA}, {n: dA, c: catA}, {n: jA, c: catA},
              {n: mB, c: catB}, {n: b1, c: catB}, {n: b2, c: catB}, {n: aDel, c: catB},
              {n: tb, c: catB}, {n: trow, c: catB}, {n: tcell, c: catB},
              {n: dB, c: catB}, {n: jB, c: catB}
            ] AS row
            WITH row.n AS node, row.c AS category
            CREATE (node)-[:__inAccessCategory {
              origin: 'direct', __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'
            }]->(category)
        """
    }
}
