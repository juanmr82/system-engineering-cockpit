package com.sec.source.windchill

import com.sec.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a Windchill OData export.
 *
 * The committed sample ([Fixtures.WINDCHILL_EXPORT]) is a real response with the company's
 * own strings replaced, and it is read here rather than reproduced, so a change in the exporter that
 * changes the file shows up as a failing test instead of as a fixture that has quietly stopped
 * resembling the thing it stands for.
 *
 * Field names are written out on purpose. A fixture built from `WindchillProp` would let a wrong
 * constant pass — the same reason the DOORS fixtures spell `"Object Text"` (backend/CLAUDE.md).
 */
class WindchillExportParserTest {

    @Test
    fun `the committed sample export reads as two documents`() {
        val export = WindchillExportParser.parse(sample()).getOrThrow()

        assertEquals(2, export.records.size)

        val first = export.records.first()
        // Identity is the OData resource URL, never Windchill's own id (R6).
        assertEquals(
            "https://company.com/Windchill/servlet/odata/v7/DocMgmt/Documents('OR:wt.doc.WTDocument:905344148')",
            first.id,
        )
        assertEquals("OR:wt.doc.WTDocument:905344148", first.oid)
        assertEquals("XXX-ADSF-RP-000823676", first.number)
        assertEquals("01 [2]", first.version)
        assertEquals("XXX Quartalsbericht Q4/23", first.name)
    }

    /** `State` is the one nested value, and both halves are kept, untouched. */
    @Test
    fun `State is flattened to its code and its wording, neither of them altered`() {
        val first = WindchillExportParser.parse(sample()).getOrThrow().records.first()

        assertEquals("RELEASED", first.properties["StateValue"])
        assertEquals("Released", first.properties["StateDisplay"])
        // The nested object itself never becomes a property: Neo4j has nowhere to put it.
        assertNull(first.properties["State"])
    }

    /** Windchill's `ID` is stored — the info-page link needs it — and it is not the node's identity. */
    @Test
    fun `the Windchill object id is stored as a property of its own`() {
        val first = WindchillExportParser.parse(sample()).getOrThrow().records.first()

        assertEquals("OR:wt.doc.WTDocument:905344148", first.properties["ID"])
    }

    /**
     * The sample carries an `@odata.nextLink`, which is exactly the case the importer must warn
     * about: the file is one page, and the sweep is about to treat it as everything.
     */
    @Test
    fun `a paged export reports its next link rather than following it`() {
        val export = WindchillExportParser.parse(sample()).getOrThrow()

        assertNotNull(export.nextLink)
        assertTrue(export.nextLink!!.contains("skiptoken"), export.nextLink!!)
    }

    @Test
    fun `an export with no next link reports none`() {
        val export = WindchillExportParser.parse(oneDocument()).getOrThrow()

        assertNull(export.nextLink)
    }

    // -- what a row may be missing ------------------------------------------------------------

    /** No `@odata.id` still imports, under a different identity, and says so. */
    @Test
    fun `a row without an OData id falls back to the Windchill id and warns`() {
        val text = """
            {"value":[{"ID":"OR:wt.doc.WTDocument:1","Number":"N-1","Version":"01 [1]","Name":"One"}]}
        """.trimIndent()

        val export = WindchillExportParser.parse(text).getOrThrow()

        assertEquals("OR:wt.doc.WTDocument:1", export.records.single().id)
        assertTrue(
            export.warnings.any { it.contains("@odata.id") },
            "the identity fallback was silent: ${export.warnings}",
        )
    }

    /** No `ID` at all cannot be identified or linked, so the row goes and the count is reported. */
    @Test
    fun `a row without a Windchill id is skipped and counted`() {
        val text = """
            {"value":[
              {"Number":"N-0","Version":"01 [1]"},
              {"ID":"OR:wt.doc.WTDocument:1","Number":"N-1","Version":"01 [1]"}
            ]}
        """.trimIndent()

        val export = WindchillExportParser.parse(text).getOrThrow()

        assertEquals(1, export.records.size)
        assertTrue(export.warnings.any { it.contains("no ID") }, export.warnings.toString())
    }

    /** A document reported twice is one document, and the first reading wins. */
    @Test
    fun `a repeated document is read once`() {
        val text = """
            {"value":[
              {"@odata.id":"u/1","ID":"OR:1","Number":"N-1","Version":"01 [1]","Name":"First"},
              {"@odata.id":"u/1","ID":"OR:1","Number":"N-1","Version":"01 [1]","Name":"Second"}
            ]}
        """.trimIndent()

        val export = WindchillExportParser.parse(text).getOrThrow()

        assertEquals(1, export.records.size)
        assertEquals("First", export.records.single().name)
        assertTrue(export.warnings.any { it.contains("more than once") }, export.warnings.toString())
    }

    /** An absent field stays absent: `""` already means "exists and is empty" everywhere else. */
    @Test
    fun `a field the row does not carry is absent rather than empty`() {
        val text = """{"value":[{"ID":"OR:1","Number":"N-1"}]}"""

        val record = WindchillExportParser.parse(text).getOrThrow().records.single()

        assertFalse(record.properties.containsKey("FolderLocation"))
        assertFalse(record.properties.containsKey("StateDisplay"))
        // …and the name falls back to something a person can read, rather than being blank.
        assertEquals("N-1", record.name)
    }

    /** A version this code cannot order is imported and reported, never dropped. */
    @Test
    fun `an unorderable version is imported with a warning`() {
        val text = """{"value":[{"ID":"OR:1","Number":"N-1","Version":"draft"}]}"""

        val export = WindchillExportParser.parse(text).getOrThrow()

        assertEquals(1, export.records.size)
        assertTrue(export.warnings.any { it.contains("draft") }, export.warnings.toString())
    }

    // -- what is refused ----------------------------------------------------------------------

    /**
     * Python dict syntax is the format the previous exporter emitted, and it is refused rather than
     * accommodated. This test is the statement of that decision.
     */
    @Test
    fun `a Python dict literal is not JSON and is refused`() {
        val text = "{'value': [{'ID': 'OR:1', 'Number': 'N-1'}]}"

        val problem = problemOf(text)

        assertIs<WindchillExportProblem.NotJson>(problem)
    }

    @Test
    fun `JSON that is not an OData collection is refused`() {
        val problem = problemOf("""{"documents":[]}""")

        assertIs<WindchillExportProblem.NotAnExport>(problem)
    }

    @Test
    fun `a JSON array at the top level is refused`() {
        val problem = problemOf("""[{"ID":"OR:1"}]""")

        assertIs<WindchillExportProblem.NotAnExport>(problem)
    }

    /**
     * The most consequential refusal in this file.
     *
     * An export with nothing in it is what a failed export produces, and importing one would delete
     * every Windchill document in the graph — the sweep treats the file as the whole truth.
     */
    @Test
    fun `an export with no usable document is refused rather than imported`() {
        val problem = problemOf("""{"value":[]}""")

        assertIs<WindchillExportProblem.NoDocuments>(problem)
    }

    @Test
    fun `an export whose every row was skipped is refused, and says how many`() {
        val problem = problemOf("""{"value":[{"Number":"N-1"},{"Number":"N-2"}]}""")

        assertEquals(2, assertIs<WindchillExportProblem.NoDocuments>(problem).skipped)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun problemOf(text: String): WindchillExportProblem {
        val cause = WindchillExportParser.parse(text).exceptionOrNull()
        return assertIs<WindchillExportFailure>(cause).problem
    }

    private fun oneDocument() =
        """{"value":[{"@odata.id":"u/1","ID":"OR:1","Number":"N-1","Version":"01 [1]"}]}"""

    private fun sample(): String = Fixtures.text(Fixtures.WINDCHILL_EXPORT)
}
