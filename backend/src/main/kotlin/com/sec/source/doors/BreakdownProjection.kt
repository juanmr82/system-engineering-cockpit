package com.sec.source.doors

import com.sec.api.dto.BreakdownEdgeDto
import com.sec.api.dto.BreakdownResponseDto
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.BreakdownCypher
import com.sec.graph.executeRead
import org.neo4j.driver.Query

/**
 * The Breakdown tab's read model (docs/requirement-breakdown-tree.md).
 *
 * Walks `refersTo` in both directions from one requirement — up to every root it traces to, then
 * down from each root to every requirement that decomposes it — and returns the closure as flat
 * node and edge lists. **Every outgoing `refersTo` edge is read as "this requirement refines its
 * target"** (§2): a display convention for this tab only, never a claim about DOORS semantics and
 * never an authored `:__Meta:__Link`.
 *
 * The nodes themselves are built by [RequirementCardProjection], which the dependency graph also
 * reads (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1) — one card shape, one query behind it. What is left
 * here is the walk: which requirements belong in the forest, and how they hang off each other.
 *
 * **Nothing computed here is stored.** The tree is a function of the imported graph and of
 * `:__AttributeSetting` configuration, both read fresh on every call. Storing either would be
 * storing a derivation, which R2 excludes from `:__Meta` for exactly this reason.
 */
public class BreakdownProjection(
    private val graphDriver: GraphDriver,
    private val cardProjection: RequirementCardProjection,
) {

    /**
     * The forest for one requirement, or null when no object carries this id.
     *
     * [maxDepth] and [maxNodes] are the only thing standing between one click and an unbounded
     * graph walk — Community has no query governor (CLAUDE.md §7) — so they bound the loop itself,
     * not just the statements inside it.
     */
    public suspend fun getBreakdown(
        itemId: String,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): BreakdownResponseDto? {
        val walk = walk(itemId, maxDepth, maxNodes)

        val nodes = cardProjection.loadCards(walk.known)
        // The walk always admits the starting id, so its absence here means no such object rather
        // than an empty neighbourhood — which is a 404, not an empty tree.
        if (itemId !in nodes) {
            return null
        }

        val cyclic = findCyclicEdges(walk.roots, walk.edges)

        return BreakdownResponseDto(
            selectedRef = Ref.encode(itemId),
            roots = walk.roots.map(Ref::encode),
            truncated = walk.truncated,
            nodes = nodes.values.toList(),
            edges = walk.edges.map { edge ->
                BreakdownEdgeDto(
                    from = Ref.encode(edge.from),
                    to = Ref.encode(edge.to),
                    cyclic = edge in cyclic,
                )
            },
        )
    }

    // --- The walk ---------------------------------------------------------------------------------

    /** `from` refines `to` (§2), so `to` is `from`'s parent in the rendered tree. */
    private data class Edge(val from: String, val to: String)

    private class Walk(
        val known: LinkedHashSet<String>,
        val edges: LinkedHashSet<Edge>,
        val roots: List<String>,
        val truncated: Boolean,
    )

    private data class EdgeRow(
        val from: String,
        val to: String,
        val next: String,
        val nextResolved: Boolean,
    )

    /**
     * Climb to the roots, then descend from every root.
     *
     * The two phases are not symmetric and cannot be merged. Climbing answers "where does this
     * requirement come from" and terminates at nodes with no outgoing edge; descending answers
     * "what decomposes it" and has to start from those roots, not from the selected item — which
     * is what makes the response the full forest rather than the selected item's own neighbourhood,
     * so the panel never needs a second request to expand a sibling branch (§6).
     */
    private suspend fun walk(itemId: String, maxDepth: Int, maxNodes: Int): Walk {
        val known = linkedSetOf(itemId)
        val edges = LinkedHashSet<Edge>()
        var truncated = false
        val edgeLimit = maxNodes.toLong() * EDGES_PER_NODE

        // Admission is what enforces maxNodes, and it enforces it on *nodes and edges together*:
        // an edge whose far end was refused would otherwise point at a node the response does not
        // carry, which the client cannot render and would have to defend against.
        fun admit(id: String): Boolean {
            if (id in known) {
                return true
            }
            if (known.size >= maxNodes) {
                truncated = true
                return false
            }
            known.add(id)
            return true
        }

        // --- up ---
        val roots = LinkedHashSet<String>()
        val climbed = hashSetOf(itemId)
        var frontier = listOf(itemId)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val rows = readEdges(BreakdownCypher.EDGES_UP, frontier, edgeLimit)
            if (rows.size.toLong() >= edgeLimit) {
                truncated = true
            }

            val next = LinkedHashSet<String>()
            val hasParent = HashSet<String>()
            for (row in rows) {
                if (!admit(row.next)) {
                    continue
                }
                edges.add(Edge(row.from, row.to))
                hasParent.add(row.from)
                // `climbed` is what stops a cycle from re-expanding a node level after level. The
                // depth bound would stop it eventually, but only by declaring the result truncated
                // when nothing was actually left to find.
                if (row.nextResolved) {
                    if (climbed.add(row.next)) {
                        next.add(row.next)
                    }
                } else {
                    // A placeholder stands for an object no import has reached, so its own
                    // ancestry is unknown rather than absent (§7). It is a legitimate leaf of the
                    // climb, which makes it a root of what we can draw.
                    roots.add(row.next)
                }
            }

            // A frontier node nothing led out of is terminal: that is the definition of a root.
            roots.addAll(frontier.filterNot { it in hasParent })
            frontier = next.toList()
            depth++
        }
        if (frontier.isNotEmpty()) {
            // The depth bound stopped the climb rather than the data. The topmost nodes reached
            // become the display roots so nothing hangs off a parent the response does not carry.
            truncated = true
            roots.addAll(frontier)
        }
        if (roots.isEmpty()) {
            // Only reachable when every path out of the selected item closes back into a cycle.
            // Rooting the forest at the item the reviewer clicked is the one answer that is always
            // renderable, and the cycle is still marked on its own edge below.
            roots.add(itemId)
        }

        // --- down ---
        val expanded = HashSet(roots)
        var downFrontier = roots.toList()
        depth = 0
        while (downFrontier.isNotEmpty() && depth < maxDepth) {
            val rows = readEdges(BreakdownCypher.EDGES_DOWN, downFrontier, edgeLimit)
            if (rows.size.toLong() >= edgeLimit) {
                truncated = true
            }

            val next = LinkedHashSet<String>()
            for (row in rows) {
                if (!admit(row.next)) {
                    continue
                }
                edges.add(Edge(row.from, row.to))
                if (row.nextResolved && expanded.add(row.next)) {
                    next.add(row.next)
                }
            }
            downFrontier = next.toList()
            depth++
        }
        if (downFrontier.isNotEmpty()) {
            truncated = true
        }

        return Walk(known = known, edges = edges, roots = roots.toList(), truncated = truncated)
    }

    private suspend fun readEdges(statement: String, ids: List<String>, limit: Long): List<EdgeRow> =
        graphDriver.executeRead(Query(statement, mapOf("ids" to ids, "limit" to limit))) { records ->
            records.map { record ->
                EdgeRow(
                    from = record.get("fromId").asString(),
                    to = record.get("toId").asString(),
                    next = record.get("nextId").asString(),
                    nextResolved = record.get("nextResolved").asBoolean(true),
                )
            }
        }

    /**
     * The edges that close a `refersTo` cycle, found by a depth-first walk *down* the tree.
     *
     * `refersTo` is not supposed to cycle and nothing in Community's schema prevents it
     * (CLAUDE.md §7), so this is a guard, not a feature: a marked edge renders as a chip and the
     * branch stops there instead of recursing forever (§3, criterion 8).
     *
     * A node is explored once. That can miss a back edge reachable only through a second path into
     * the same subtree, and the trade is deliberate: exploring every path is exponential on a dense
     * DAG, and what this has to guarantee is that the panel terminates, which it does either way.
     */
    private fun findCyclicEdges(roots: List<String>, edges: Set<Edge>): Set<Edge> {
        val children = edges.groupBy({ it.to }, { it })
        val cyclic = HashSet<Edge>()
        val onPath = LinkedHashSet<String>()
        val explored = HashSet<String>()

        fun visit(id: String) {
            onPath.add(id)
            for (edge in children[id].orEmpty()) {
                when {
                    edge.from in onPath -> cyclic.add(edge)
                    edge.from !in explored -> visit(edge.from)
                }
            }
            onPath.remove(id)
            explored.add(id)
        }

        for (root in roots) {
            if (root !in explored) {
                visit(root)
            }
        }
        // A cycle with no root above it is unreachable from the roots and would otherwise go
        // unmarked, leaving the client to discover it the hard way.
        for (edge in edges) {
            if (edge.from !in explored) {
                visit(edge.from)
            }
        }
        return cyclic
    }

    public companion object {
        public const val DEFAULT_MAX_DEPTH: Int = 6
        public const val DEFAULT_MAX_NODES: Int = 200

        /**
         * Ceilings on what a client may ask for. They are not the same number as the defaults: the
         * defaults are what the panel is comfortable rendering, these are what the database is
         * willing to be asked, and only the second is a safety property.
         */
        public const val MAX_MAX_DEPTH: Int = 12
        public const val MAX_MAX_NODES: Int = 1_000

        // Per-level statement cap, expressed per admitted node rather than as a flat number so it
        // scales with maxNodes instead of silently becoming the real bound at large ones.
        private const val EDGES_PER_NODE = 8L
    }
}
