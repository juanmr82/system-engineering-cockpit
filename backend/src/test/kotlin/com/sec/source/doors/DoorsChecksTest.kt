package com.sec.source.doors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(emptyList(), DoorsChecks.openPointAttributes(cell, props, emptySet()))
    }

    /** The exemption is the attribute, not the object: a cell's prose is scanned like any other. */
    @Test
    fun `every other attribute on a table cell is still scanned`() {
        val props = mapOf("Object Type" to "TBD", "Object Text" to "width TBC")
        assertEquals(listOf("Object Text"), DoorsChecks.openPointAttributes(cell, props, emptySet()))
    }

    /**
     * And the exemption is table structure, not everything: a requirement DOORS never typed is a
     * real open point on a real requirement, which is a different fact from a cell DOORS never
     * types because it never types cells.
     */
    @Test
    fun `Object Type is still scanned on a requirement`() {
        val props = mapOf("Object Type" to "TBD", "Object Text" to "The mass shall be defined")
        assertEquals(listOf("Object Type"), DoorsChecks.openPointAttributes(requirement, props, emptySet()))
    }

    /**
     * A link whose far end DOORS deleted is a finding on the object that still asserts it, and it
     * comes first: an unfilled attribute is work not yet done, while this is a statement the
     * requirements data is making that it cannot support (ADR 0012).
     */
    @Test
    fun `a link to a deleted object is the first issue reported`() {
        val issues = DoorsChecks.issuesFor(
            policies = listOf(DoorsChecks.MandatoryPolicy("Rationale", setOf("DOORSRequirement"))),
            labels = requirement,
            props = mapOf("Rationale" to ""),
            deletedLinks = 2,
        )

        assertEquals(
            listOf("2 links to or from objects deleted in DOORS", "Rationale"),
            issues,
        )
    }

    /** Singular, because "1 links" is the sort of thing a reviewer stops trusting the tool over. */
    @Test
    fun `one deleted link reads as one`() {
        assertEquals("1 link to or from an object deleted in DOORS", DoorsChecks.deletedLinkIssue(1))
    }

    /**
     * The default matters: every caller that has nothing to say about links must not accidentally
     * report a finding, and `issuesFor` is called from two views.
     */
    @Test
    fun `no deleted links means no such issue`() {
        val issues = DoorsChecks.issuesFor(emptyList(), requirement, mapOf("Object Text" to "x"))

        assertEquals(emptyList(), issues)
    }

    /** R5: the label the finding is derived from never reaches the sentence a reviewer reads. */
    @Test
    fun `the deleted-link wording carries no internal name`() {
        assertTrue(!DoorsChecks.deletedLinkIssue(3).contains("__"))
    }

    @Test
    fun `an object carrying markers in several attributes reports each of them, sorted`() {
        val props = mapOf("Rationale" to "TBC", "Object Text" to "mass TBD kg")
        assertEquals(
            listOf("Object Text", "Rationale"),
            DoorsChecks.openPointAttributes(requirement, props, emptySet()),
        )
    }

    /**
     * An attribute the module has excluded is not scanned, and the others still are.
     *
     * The configured half of the table-structure exemption above: some attributes legitimately
     * carry the word TBD without that being an open point, and which ones is a decision about the
     * module rather than a fact about DOORS.
     */
    @Test
    fun `an excluded attribute is left out and the rest are still scanned`() {
        val props = mapOf("Rationale" to "TBC", "Object Text" to "mass TBD kg")

        assertEquals(
            listOf("Object Text"),
            DoorsChecks.openPointAttributes(requirement, props, setOf("Rationale")),
        )
    }

    /** Excluding every carrier leaves the object clean rather than leaving an empty finding. */
    @Test
    fun `excluding every carrying attribute reports no open point at all`() {
        val props = mapOf("Rationale" to "TBC", "Object Text" to "mass TBD kg")

        assertEquals(
            emptyList(),
            DoorsChecks.openPointAttributes(requirement, props, setOf("Rationale", "Object Text")),
        )
    }
}
