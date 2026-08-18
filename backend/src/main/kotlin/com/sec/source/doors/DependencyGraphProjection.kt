package com.sec.source.doors

import com.sec.api.dto.DependencyGraphDto
import com.sec.api.dto.GraphEdgeDto
import com.sec.api.dto.GraphNodeDto
import com.sec.api.dto.LevelBandDto
import com.sec.api.dto.UnresolvedModuleDto
import com.sec.domain.Aliases
import com.sec.domain.GraphDirection
import com.sec.domain.GraphLevelStrategy
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.DependencyGraphCypher
import com.sec.graph.executeRead
import com.sec.security.AccessSet

/**
 * The dependency graph's read model (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §3).
 *
 * A bounded neighbourhood of `refersTo` around one or more seeds, returned as the **induced**
 * subgraph: every `refersTo` between two nodes in the picture, not only the edges the walk itself
 * traversed (§3.2). A tree-only edge set would hide real dependencies between siblings, which is
 * the one thing a dependency picture exists to show.
 *
 * The nodes are the shared requirement card, built by [RequirementCardProjection] — the same shape,
 * from the same query, that the Breakdown tab draws as a row (§5.1).
 *
 * DOORS-specific because `refersTo` is DOORS's own relationship, so it lives here (CLAUDE.md §1).
 *
 * **Nothing computed here is stored** — not the levels, not the ranks, not the cut counts. All of
 * it is a function of the imported graph and of the requested scope, and a stored derivation goes
 * stale silently (R2).
 */
public class DependencyGraphProjection(
    private val graphDriver: GraphDriver,
    private val cardProjection: RequirementCardProjection,
) {

    /**
     * The picture for one scope, or null when none of the seeds names an object.
     *
     * [depth] and [maxNodes] are the only thing standing between one click and an unbounded graph
     * walk — Community has no query governor (CLAUDE.md §7) — so they bound the loop itself, not
     * just the statements inside it. **Never opens unscoped** (§8): there is no "whole module" mode
     * and the seeds are required.
     */
    public suspend fun getGraph(
        seedIds: List<String>,
        access: AccessSet,
        depth: Int = DEFAULT_DEPTH,
        direction: GraphDirection = GraphDirection.BOTH,
        levelStrategy: GraphLevelStrategy = GraphLevelStrategy.MODULE_SYSTEM_LEVEL,
        maxNodes: Int = MAX_NODES,
    ): DependencyGraphDto? {
        val seeds = readSeeds(seedIds, access)
        if (seeds.isEmpty()) {
            return null
        }

        val walk = walk(seeds, access, depth, direction, maxNodes)
        val neighbourhood = readNeighbourhood(walk.admitted, access, maxNodes)

        val loaded = cardProjection.load(walk.admitted, access)
        // Only nodes we could actually build a card for: a link into a module mid-re-import can
        // name an id that is gone by the time the second statement runs, and a node with no card
        // is one the client cannot draw and would have to defend against.
        val ids = walk.admitted.filter { it in loaded.cards }

        val edges = neighbourhood.edges.filter { it.from in loaded.cards && it.to in loaded.cards }
        val levels = levelsOf(levelStrategy, ids, loaded, edges)

        return DependencyGraphDto(
            seedRefs = seeds.map(Ref::encode),
            depth = depth,
            direction = direction.name,
            levelStrategy = levelStrategy.name,
            nodes = ids.map { id ->
                GraphNodeDto(
                    card = loaded.cards.getValue(id),
                    level = levels[id],
                    seed = id in seeds,
                    truncatedNeighbours = neighbourhood.cutNeighbours[id]?.size ?: 0,
                )
            },
            edges = edges.map { GraphEdgeDto(source = Ref.encode(it.from), target = Ref.encode(it.to)) },
            levels = bands(levelStrategy, ids, levels),
            truncated = walk.truncated || neighbourhood.truncated,
            unresolvedModules = unresolvedModules(ids, loaded),
        )
    }

    // --- The walk -----------------------------------------------------------------------------

    private data class Edge(val from: String, val to: String)

    private class Walk(val admitted: List<String>, val truncated: Boolean)

    private class Neighbourhood(
        val edges: List<Edge>,
        val cutNeighbours: Map<String, Set<String>>,
        val truncated: Boolean,
    )

    private class NeighbourRow(val from: String, val to: String, val sortKey: String)

    /**
     * Breadth-first from the seeds, one hop at a time, admitting in `__sortKey` order.
     *
     * Breadth-first is what makes the cap mean "the nodes closest to the seeds" (§3.1) rather than
     * "whichever 300 the planner reached first", and `__sortKey` order is what makes two identical
     * requests return the same 300 — the determinism §4.6 depends on, decided here rather than
     * hoped for downstream.
     */
    private suspend fun walk(
        seeds: List<String>,
        access: AccessSet,
        depth: Int,
        direction: GraphDirection,
        maxNodes: Int,
    ): Walk {
        val admitted = LinkedHashSet(seeds)
        var truncated = false
        val statementLimit = maxNodes.toLong() * EDGES_PER_NODE

        var frontier = seeds
        var hop = 0
        while (frontier.isNotEmpty() && hop < depth && admitted.size < maxNodes) {
            val rows = readNeighbours(frontier, direction, statementLimit, access)
            if (rows.size.toLong() >= statementLimit) {
                truncated = true
            }

            val next = LinkedHashSet<String>()
            // Sorted across the whole hop, not per statement: with direction BOTH the two
            // statements are each ordered but their concatenation is not, and admitting in
            // concatenation order would make the cap depend on which direction ran first.
            for (row in rows.sortedWith(compareBy({ it.sortKey }, { it.neighbour }))) {
                val id = row.neighbour
                if (id in admitted) {
                    continue
                }
                if (admitted.size >= maxNodes) {
                    truncated = true
                    break
                }
                admitted.add(id)
                next.add(id)
            }

            frontier = next.toList()
            hop++
        }
        // Neighbours still unvisited when the depth bound stopped the walk are not truncation:
        // the user asked for this many hops and got them. What lies beyond is reported per node,
        // as a cut-neighbour count, which is the honest place for it (§5.7).

        return Walk(admitted.toList(), truncated)
    }

    private class HopRow(val neighbour: String, val sortKey: String)

    private suspend fun readNeighbours(
        ids: List<String>,
        direction: GraphDirection,
        limit: Long,
        access: AccessSet,
    ): List<HopRow> {
        val rows = mutableListOf<HopRow>()
        if (direction != GraphDirection.INCOMING) {
            rows += readHop(DependencyGraphCypher.OUT_NEIGHBOURS, ids, limit, access) { it.to }
        }
        if (direction != GraphDirection.OUTGOING) {
            rows += readHop(DependencyGraphCypher.IN_NEIGHBOURS, ids, limit, access) { it.from }
        }
        return rows
    }

    private suspend fun readHop(
        statement: String,
        ids: List<String>,
        limit: Long,
        access: AccessSet,
        neighbour: (NeighbourRow) -> String,
    ): List<HopRow> =
        readNeighbourRows(statement, ids, limit, access).map { HopRow(neighbour(it), it.sortKey) }

    private suspend fun readNeighbourRows(
        statement: String,
        ids: List<String>,
        limit: Long,
        access: AccessSet,
    ): List<NeighbourRow> =
        graphDriver.executeRead(statement, mapOf("ids" to ids, "limit" to limit), access) { records ->
            records.map { record ->
                NeighbourRow(
                    from = record.get("fromId").asString(),
                    to = record.get("toId").asString(),
                    sortKey = record.get("sortKey").asString(""),
                )
            }
        }

    /**
     * Every `refersTo` touching the admitted set, split into the ones inside it and the ones that
     * leave it.
     *
     * One pass answers both questions the picture needs: the induced edge set (§3.2), and where the
     * cap and the depth bound cut the picture off, which each boundary node reports as a badge so
     * nobody reads the edge of the scope as the edge of the data (§1.1, §5.7).
     *
     * **The `+n` badge counts only neighbours this caller can see**, and that is load-bearing rather
     * than incidental (R8, spec §7: *"a `+3` that includes two invisible neighbours is a disclosure
     * with a number attached"*). Both statements filter **both** endpoints, so an invisible neighbour
     * is not a row here, is never put in [Neighbourhood.cutNeighbours], and is therefore not counted.
     *
     * This is the reason these two statements and `RequirementCardCypher.NODES` may only ever be
     * filtered together: filtering the cards alone would leave invisible objects admitted by the walk
     * and dropped from the node list, moving each one from *invisible* into the badge — a number that
     * grows by exactly the count of what the reader may not see, which is worse than not filtering.
     */
    private suspend fun readNeighbourhood(
        admitted: List<String>,
        access: AccessSet,
        maxNodes: Int,
    ): Neighbourhood {
        val inside = admitted.toSet()
        val limit = maxNodes.toLong() * EDGES_PER_NODE

        val out = readNeighbourRows(DependencyGraphCypher.OUT_NEIGHBOURS, admitted, limit, access)
        val incoming = readNeighbourRows(DependencyGraphCypher.IN_NEIGHBOURS, admitted, limit, access)
        val truncated = out.size.toLong() >= limit || incoming.size.toLong() >= limit

        // Deduplicated: parallel `refersTo` pairs are MERGEd at import and so should not exist, but
        // the importer spec says not to rely on that, and two identical arrows are indistinguishable
        // from one (§4.5).
        val edges = LinkedHashSet<Edge>()
        val cut = mutableMapOf<String, MutableSet<String>>()

        for (row in out) {
            if (row.to in inside) {
                edges.add(Edge(row.from, row.to))
            } else {
                cut.getOrPut(row.from) { mutableSetOf() }.add(row.to)
            }
        }
        for (row in incoming) {
            // An incoming edge from inside the set was already recorded by the outgoing pass, from
            // its own source. Only the ones arriving from outside are new information.
            if (row.from !in inside) {
                cut.getOrPut(row.to) { mutableSetOf() }.add(row.from)
            }
        }

        // A self-loop is kept, deliberately: it is almost always an authoring error and it is worth
        // seeing (§4.5). It survives above because source and target are both inside the set.
        return Neighbourhood(edges.toList(), cut, truncated)
    }

    private suspend fun readSeeds(seedIds: List<String>, access: AccessSet): List<String> =
        graphDriver.executeRead(
            DependencyGraphCypher.SEEDS, mapOf("ids" to seedIds.distinct()), access,
        ) { records -> records.map { it.get("id").asString() } }

    // --- Levels -------------------------------------------------------------------------------

    /**
     * A level per node, or no entry at all when this strategy cannot place it.
     *
     * Absent means **unknown**, and unknown gets its own band at the bottom (§4.1). It is never
     * quietly folded into level 0 or level 1: a requirement in the wrong band is a wrong statement
     * about the system, made silently.
     */
    private fun levelsOf(
        strategy: GraphLevelStrategy,
        ids: List<String>,
        loaded: RequirementCardProjection.Cards,
        edges: List<Edge>,
    ): Map<String, Int> = when (strategy) {
        GraphLevelStrategy.MODULE_SYSTEM_LEVEL ->
            ids.mapNotNull { id -> loaded.rows[id]?.systemLevelOrdinal()?.let { id to it } }.toMap()

        GraphLevelStrategy.OUTLINE_LEVEL ->
            ids.mapNotNull { id ->
                (loaded.rows[id]?.property(DoorsAttr.OBJECT_LEVEL) as? Number)?.let { id to it.toInt() }
            }.toMap()

        GraphLevelStrategy.GRAPH_RANK -> graphRank(ids, edges)
    }

    /**
     * Longest path down the `refersTo` DAG, over this subgraph only.
     *
     * An outgoing `refersTo` is read as "refines", so a node with none inside the picture is at the
     * top of it, and every other node sits one below the deepest thing it refines. **Cycles are
     * expected** — `refersTo` is not guaranteed acyclic and nothing in Community's schema prevents
     * it (CLAUDE.md §7) — so an edge back onto the current path contributes nothing rather than
     * recursing forever. Every node still gets a rank, which is what makes this the fallback
     * strategy for a graph with no classification anywhere in it.
     */
    private fun graphRank(ids: List<String>, edges: List<Edge>): Map<String, Int> {
        val parents = edges.groupBy({ it.from }, { it.to })
        val rank = HashMap<String, Int>(ids.size)
        val onPath = LinkedHashSet<String>()

        fun visit(id: String): Int {
            rank[id]?.let { return it }
            if (!onPath.add(id)) {
                return 0
            }
            val depth = parents[id].orEmpty()
                .filter { it != id }
                .maxOfOrNull { visit(it) + 1 }
                ?: 0
            onPath.remove(id)
            rank[id] = depth
            return depth
        }

        ids.forEach { visit(it) }
        return rank
    }

    /**
     * The bands, top to bottom, with the unknown band last (§4.1, §4.4).
     *
     * Only the levels that actually occur. An empty band between two occupied ones would be a lane
     * with a label and nothing in it, which reads as a rendering fault; the client's dense
     * renumbering (§4.2) is what keeps ELK's partition indices contiguous either way.
     */
    private fun bands(
        strategy: GraphLevelStrategy,
        ids: List<String>,
        levels: Map<String, Int>,
    ): List<LevelBandDto> {
        val occupied = levels.values.distinct().sorted()
            .map { LevelBandDto(level = it, label = Aliases.graphBandLabel(strategy, it)) }
        // One explicit band at the bottom for everything this strategy could not place, and only
        // when something landed in it. Never folded into the lowest real level (§4.1).
        return if (ids.any { it !in levels }) {
            occupied + LevelBandDto(level = null, label = Aliases.graphUnplacedBandLabel(strategy))
        } else {
            occupied
        }
    }

    /**
     * The modules named by the picture's placeholders, with how many placeholders each accounts for.
     *
     * Named rather than counted, because the action this prompts is "import that module" and a count
     * does not say which (§1.1). A placeholder whose module has not been imported either has no
     * module node to name, so those group under one honest sentence instead of exposing the
     * internal identifier the placeholder does carry (R5).
     */
    private fun unresolvedModules(
        ids: List<String>,
        loaded: RequirementCardProjection.Cards,
    ): List<UnresolvedModuleDto> =
        ids.mapNotNull { loaded.cards[it] }
            .filterNot { it.resolved }
            .groupBy { it.moduleRef to (it.moduleName ?: Aliases.UNNAMED_UNRESOLVED_MODULE) }
            .map { (key, cards) ->
                UnresolvedModuleDto(ref = key.first, name = key.second, count = cards.size)
            }
            .sortedWith(compareByDescending<UnresolvedModuleDto> { it.count }.thenBy { it.name })

    public companion object {
        public const val DEFAULT_DEPTH: Int = 2

        /**
         * The depth range the dialog offers and the API accepts (§3.1).
         *
         * A ceiling rather than a default: above five hops a `refersTo` neighbourhood is the whole
         * module, and the node cap becomes the real bound — which makes the picture a function of
         * the cap rather than of what the user asked for.
         */
        public const val MIN_DEPTH: Int = 1
        public const val MAX_DEPTH: Int = 5

        /**
         * The hard node cap (§3.1). Not configurable: it is the number the rendering was designed
         * around — fixed-width cards, one `<path>` per edge, ELK in a worker — and raising it moves
         * the failure from "the picture is capped, and says so" to "the dialog is unusable".
         */
        public const val MAX_NODES: Int = 300

        // Per-statement cap, expressed per admitted node rather than as a flat number so it scales
        // with the node cap instead of silently becoming the real bound.
        private const val EDGES_PER_NODE = 12L
    }
}
