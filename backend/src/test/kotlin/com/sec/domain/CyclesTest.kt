package com.sec.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// docs/features/requirements-statistics.md §13 criterion 9. No database: SCC over an edge list is
// a pure function, which is why it lives in domain/ and why these are the fastest tests in the
// feature (§14 step 2).
class CyclesTest {

    private fun edges(vararg pairs: Pair<String, String>) =
        pairs.map { Cycles.Edge(it.first, it.second) }

    @Test
    fun `a graph with no edges has no loops`() {
        assertEquals(emptyList(), Cycles.find(emptyList()))
    }

    @Test
    fun `a plain chain has no loops`() {
        assertEquals(emptyList(), Cycles.find(edges("a" to "b", "b" to "c", "c" to "d")))
    }

    @Test
    fun `a diamond is not a loop`() {
        // The case a naive "have I seen this node before" walk gets wrong: `d` is reachable twice
        // from `a`, by two different paths, and nothing about that is circular.
        val loops = Cycles.find(edges("a" to "b", "a" to "c", "b" to "d", "c" to "d"))
        assertEquals(emptyList(), loops)
    }

    @Test
    fun `a self reference is a loop of one`() {
        val loops = Cycles.find(edges("a" to "a"))
        assertEquals(1, loops.size)
        assertEquals(listOf("a"), loops.single().ring)
        assertEquals(emptyList(), loops.single().others)
    }

    @Test
    fun `a node with an edge to itself and to elsewhere is still a loop of one`() {
        val loops = Cycles.find(edges("a" to "a", "a" to "b", "b" to "c"))
        assertEquals(listOf(listOf("a")), loops.map { it.ring })
    }

    @Test
    fun `two requirements referring to each other are one loop, not two`() {
        val loops = Cycles.find(edges("a" to "b", "b" to "a"))
        assertEquals(1, loops.size)
        assertEquals(setOf("a", "b"), loops.single().members.toSet())
    }

    @Test
    fun `a six node loop is reported once, as a ring in order`() {
        val ring = listOf("r1", "r2", "r3", "r4", "r5", "r6")
        val loops = Cycles.find(edges(*ring.zip(ring.drop(1) + ring.first()).toTypedArray()))

        assertEquals(1, loops.size, "a six-node loop is one finding, not six")
        assertEquals(6, loops.single().ring.size)
        assertEquals(emptyList(), loops.single().others)

        // The ring must read as a walk: every consecutive pair is a real edge, and it closes.
        val drawn = loops.single().ring
        val expected = ring.zip(ring.drop(1) + ring.first()).toSet()
        drawn.zip(drawn.drop(1) + drawn.first()).forEach { hop ->
            assertTrue(hop in expected, "$hop is not an edge of the graph")
        }
    }

    @Test
    fun `two disjoint loops in one graph are two findings`() {
        val loops = Cycles.find(
            edges("a" to "b", "b" to "a", "x" to "y", "y" to "z", "z" to "x", "b" to "x"),
        )
        assertEquals(2, loops.size)
        assertEquals(listOf(setOf("a", "b"), setOf("x", "y", "z")), loops.map { it.members.toSet() })
    }

    @Test
    fun `a loop through a node that is not a requirement is still found`() {
        // §12.3: the SCC runs over every refersTo edge, so a loop routed through a heading or an
        // information object is visible. This function never sees labels — that is the point.
        val loops = Cycles.find(edges("req-1" to "heading-9", "heading-9" to "req-2", "req-2" to "req-1"))
        assertEquals(setOf("req-1", "req-2", "heading-9"), loops.single().members.toSet())
    }

    @Test
    fun `interlocking loops sharing nodes are one knot`() {
        // a→b→c→a and a→c→a share nodes, so they are one strongly-connected component and one
        // finding. Reporting two would double-count a single tangle.
        val loops = Cycles.find(edges("a" to "b", "b" to "c", "c" to "a", "a" to "c"))
        assertEquals(1, loops.size)
        assertEquals(setOf("a", "b", "c"), loops.single().members.toSet())
    }

    @Test
    fun `a loop with a tail reports only the loop`() {
        val loops = Cycles.find(edges("tail" to "a", "a" to "b", "b" to "a", "b" to "leaf"))
        assertEquals(setOf("a", "b"), loops.single().members.toSet())
    }

    @Test
    fun `the same graph always reads the same way`() {
        val forwards = edges("m" to "n", "n" to "m", "p" to "q", "q" to "p")
        assertEquals(Cycles.find(forwards), Cycles.find(forwards.reversed()))
    }

    @Test
    fun `a long chain does not overflow the stack`() {
        // The reason the traversal is iterative. A recursive Tarjan over this depth dies with a
        // StackOverflowError in a request thread, which is not a diagnosable error message.
        val chain = (1..50_000).map { Cycles.Edge("n$it", "n${it + 1}") }
        assertEquals(emptyList(), Cycles.find(chain))
    }
}
