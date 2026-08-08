package com.sec.source.doors

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The open-point scan's one DOORS-specific exemption (`requirements-statistics.md` §3.3).
 *
 * A unit test rather than a case in `StatisticsFeatureTest`, deliberately: the container tests are
 * tagged `docker` and are not part of `mvn verify` (CLAUDE.md §11), and this rule decides what a
 * headline number on the Statistics view means. It should be checked on every machine that builds
 * the project, not only on the ones that can start Neo4j.
 */
class DoorsChecksTest {

    private val cell = listOf("SEItem", "DOORSObject", "DOORSTableCell", "DOORSTBD")
    private val requirement = listOf("SEItem", "DOORSObject", "DOORSRequirement")

    /**
     * DOORS does not type the cells, rows and tables of an embedded table, so `Object Type` on
     * every one of them reads "TBD" verbatim. On the reference module that was 425 objects — and
     * 425 of the 425 open points the view reported. The metric measured DOORS's own table
     * scaffolding and nothing else.
     */
    @Test
    fun `a table cell's own Object Type is not an open point`() {
        val props = mapOf("Object Type" to "TBD", "Object Text" to "a value")
        assertEquals(emptyList(), DoorsChecks.openPointAttributes(cell, props))
    }

    /** The exemption is the attribute, not the object: a cell's prose is scanned like any other. */
    @Test
    fun `every other attribute on a table cell is still scanned`() {
        val props = mapOf("Object Type" to "TBD", "Object Text" to "width TBC")
        assertEquals(listOf("Object Text"), DoorsChecks.openPointAttributes(cell, props))
    }

    /**
     * And the exemption is table structure, not everything: a requirement DOORS never typed is a
     * real open point on a real requirement, which is a different fact from a cell DOORS never
     * types because it never types cells.
     */
    @Test
    fun `Object Type is still scanned on a requirement`() {
        val props = mapOf("Object Type" to "TBD", "Object Text" to "The mass shall be defined")
        assertEquals(listOf("Object Type"), DoorsChecks.openPointAttributes(requirement, props))
    }

    @Test
    fun `an object carrying markers in several attributes reports each of them, sorted`() {
        val props = mapOf("Rationale" to "TBC", "Object Text" to "mass TBD kg")
        assertEquals(
            listOf("Object Text", "Rationale"),
            DoorsChecks.openPointAttributes(requirement, props),
        )
    }
}
