package com.sec.domain

/**
 * Closed loops in a directed graph, by Tarjan's strongly-connected-components algorithm.
 *
 * Source-agnostic on purpose (R3): this knows nothing about DOORS, `refersTo`, or requirements —
 * it takes an edge list of opaque ids. The day Cameo asserts its own links, this is the code that
 * finds loops in them.
 *
 * **Why not a variable-length Cypher pattern.** `MATCH path=(r)-[:refersTo*1..6]->(r)` is the
 * obvious implementation and it is wrong three ways: it needs a depth bound, so a loop of seven is
 * invisible; it reports the same loop once per member, so a six-node loop looks like six findings;
 * and it is expensive in exactly the way `CLAUDE.md` §7 warns about, on a database with no query
 * governor. Pulling the edge set — a few thousand rows on the reference data — and running SCC in
 * memory is exact, bounded, and reports every loop once.
 *
 * The traversal is **iterative**, not recursive. DOORS trace chains are long and a recursive
 * Tarjan over a deep chain overflows the stack; that failure would arrive as a `StackOverflowError`
 * in a request thread, which is not a diagnosable error message.
 */
public object Cycles {

    /** One directed edge. [from] refines [to], in the Breakdown tab's reading of `refersTo`. */
    public data class Edge(val from: String, val to: String)

    /**
     * One closed loop.
     *
     * @property ring one concrete cycle through the component, in order, so it can be *drawn* as
     *   a loop. The last member links back to the first.
     * @property others the remaining members of the same strongly-connected component. A component
     *   larger than its ring means several interlocking loops; they are one finding, because they
     *   are one knot and fixing it is one conversation.
     */
    public data class Loop(val ring: List<String>, val others: List<String>) {
        public val size: Int get() = ring.size + others.size
        public val members: List<String> get() = ring + others
    }

    private class Frame(val node: String, var next: Int)

    /**
     * Every loop in [edges], each reported exactly once.
     *
     * A component of more than one node is a loop. A single node is a loop only when it carries a
     * self-edge — a requirement that refines itself, which is real, and which a depth-bounded walk
     * finds by accident at best.
     *
     * Output is deterministic: adjacency is built in sorted id order and loops come back ordered
     * by their smallest member, so the same graph always produces the same reading. Without that
     * the rendered finding list would reshuffle between two identical requests, which reads as
     * data changing when nothing has.
     */
    public fun find(edges: Collection<Edge>): List<Loop> {
        if (edges.isEmpty()) {
            return emptyList()
        }

        val adjacency = buildAdjacency(edges)
        val selfLooped = edges.filter { it.from == it.to }.mapTo(HashSet()) { it.from }

        return components(adjacency)
            .filter { it.size > 1 || it.first() in selfLooped }
            .map { component ->
                val ring = ringWithin(component.toHashSet(), adjacency)
                Loop(ring = ring, others = (component - ring.toSet()).sorted())
            }
            .sortedBy { it.members.min() }
    }

    private fun buildAdjacency(edges: Collection<Edge>): Map<String, List<String>> {
        val adjacency = sortedMapOf<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            // A sink still needs an entry, so every lookup below is total.
            adjacency.getOrPut(edge.to) { mutableListOf() }
        }
        return adjacency.mapValues { (_, targets) -> targets.distinct().sorted() }
    }

    /** Tarjan proper. Every node ends up in exactly one component, singletons included. */
    private fun components(adjacency: Map<String, List<String>>): List<List<String>> {
        val index = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val pending = ArrayDeque<String>()
        val found = mutableListOf<List<String>>()
        var counter = 0

        for (root in adjacency.keys) {
            if (root in index) {
                continue
            }

            val work = ArrayDeque<Frame>()
            index[root] = counter
            low[root] = counter
            counter++
            pending.addLast(root)
            onStack.add(root)
            work.addLast(Frame(root, 0))

            while (work.isNotEmpty()) {
                val frame = work.last()
                val neighbours = adjacency.getValue(frame.node)

                if (frame.next < neighbours.size) {
                    val next = neighbours[frame.next]
                    frame.next++
                    when {
                        next !in index -> {
                            index[next] = counter
                            low[next] = counter
                            counter++
                            pending.addLast(next)
                            onStack.add(next)
                            work.addLast(Frame(next, 0))
                        }

                        next in onStack ->
                            low[frame.node] = minOf(low.getValue(frame.node), index.getValue(next))
                    }
                    continue
                }

                work.removeLast()
                work.lastOrNull()?.let { parent ->
                    low[parent.node] = minOf(low.getValue(parent.node), low.getValue(frame.node))
                }

                if (low.getValue(frame.node) == index.getValue(frame.node)) {
                    val component = mutableListOf<String>()
                    while (true) {
                        val member = pending.removeLast()
                        onStack.remove(member)
                        component.add(member)
                        if (member == frame.node) {
                            break
                        }
                    }
                    found.add(component)
                }
            }
        }

        return found
    }

    /**
     * One concrete cycle inside a component, so the finding can be *drawn* as a ring.
     *
     * Tarjan yields a set, not a path, and a set of six ids does not read as a loop to anyone. A
     * depth-first walk restricted to the component finds a cycle on its first repeat; inside a
     * strongly-connected component that always terminates, because every member lies on one.
     *
     * A self-edge produces a ring of one, which is the honest shape for "this refines itself".
     */
    private fun ringWithin(component: Set<String>, adjacency: Map<String, List<String>>): List<String> {
        val start = component.min()
        val path = mutableListOf(start)
        val positionOnPath = hashMapOf(start to 0)
        val cursor = hashMapOf(start to 0)

        while (path.isNotEmpty()) {
            val node = path.last()
            val neighbours = adjacency.getValue(node)
            val at = cursor.getValue(node)

            if (at >= neighbours.size) {
                positionOnPath.remove(node)
                path.removeLast()
                continue
            }
            cursor[node] = at + 1

            val next = neighbours[at]
            if (next !in component) {
                continue
            }

            val revisited = positionOnPath[next]
            if (revisited != null) {
                return path.subList(revisited, path.size).toList()
            }

            path.add(next)
            positionOnPath[next] = path.size - 1
            cursor[next] = 0
        }

        // Unreachable for a real component; a total return beats an exception on a pure function.
        return component.sorted()
    }
}
