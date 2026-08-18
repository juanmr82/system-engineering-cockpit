package com.sec.source.doors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Mirrors `importers/src/sec_import/doors/tests/test_derivations.py`, case for case (ADR 0019 §7) —
 * every case there has one here, so the two stay provably in step.
 */
class DoorsDerivationsTest {

    companion object {
        const val PREFIX = "doors://doors.company.corp:9601/?version=2&prodID=0&urn=urn:telelogic::1-0000000000000000"
        const val MOD_ID = "000969a2"
        val CURRENT_MOD_URL = "$PREFIX-M-$MOD_ID"
        val BASELINE_MOD_URL = "$PREFIX-B-$MOD_ID-4.0"
    }

    // -- targetObjectUrl -------------------------------------------------------------------

    @Test
    fun `target object url, current module`() {
        assertEquals("$PREFIX-O-95-$MOD_ID", DoorsDerivations.targetObjectUrl(CURRENT_MOD_URL, "95"))
    }

    @Test
    fun `target object url, baseline module`() {
        assertEquals(
            "$PREFIX-V-95-$MOD_ID-4.0",
            DoorsDerivations.targetObjectUrl(BASELINE_MOD_URL, "95"),
        )
    }

    @Test
    fun `target object url strips whitespace`() {
        assertTrue("-O-" in DoorsDerivations.targetObjectUrl("  $CURRENT_MOD_URL  ", "1"))
    }

    @Test
    fun `target object url, baseline version id with dashes`() {
        val url = "$PREFIX-B-$MOD_ID-1.0-draft"
        assertEquals("$PREFIX-V-10-$MOD_ID-1.0-draft", DoorsDerivations.targetObjectUrl(url, "10"))
    }

    @Test
    fun `target object url throws on a malformed url`() {
        assertFailsWith<MalformedUrlError> { DoorsDerivations.targetObjectUrl("not-a-doors-url", "1") }
    }

    // -- targetVersion -----------------------------------------------------------------------

    @Test
    fun `target version, current`() {
        assertEquals("current", DoorsDerivations.targetVersion(CURRENT_MOD_URL))
    }

    @Test
    fun `target version, baseline`() {
        assertEquals("4.0", DoorsDerivations.targetVersion(BASELINE_MOD_URL))
    }

    @Test
    fun `target version, baseline with dashes`() {
        val url = "$PREFIX-B-$MOD_ID-1.0-draft"
        assertEquals("1.0-draft", DoorsDerivations.targetVersion(url))
    }

    @Test
    fun `target version throws on a malformed url`() {
        assertFailsWith<MalformedUrlError> { DoorsDerivations.targetVersion("garbage") }
    }

    // -- parentNumber ------------------------------------------------------------------------

    @Test
    fun `parent number, root returns null`() {
        assertEquals(null, DoorsDerivations.parentNumber("1"))
        assertEquals(null, DoorsDerivations.parentNumber("10"))
    }

    @Test
    fun `parent number, level 2`() {
        assertEquals("1", DoorsDerivations.parentNumber("1.2"))
    }

    @Test
    fun `parent number, level 3`() {
        assertEquals("1.2", DoorsDerivations.parentNumber("1.2.3"))
    }

    @Test
    fun `parent number, non-heading segments`() {
        assertEquals("7.2.0-4.0-1", DoorsDerivations.parentNumber("7.2.0-4.0-1.0-1"))
        assertEquals("7.2.0-4.0-1.0-10", DoorsDerivations.parentNumber("7.2.0-4.0-1.0-10.0-1"))
    }

    @Test
    fun `parent number is not confused by a shared prefix`() {
        assertEquals("7.2.0-4.0-1.0-10", DoorsDerivations.parentNumber("7.2.0-4.0-1.0-10.0-1"))
        assertEquals("7.2.0-4.0-1.0-1", DoorsDerivations.parentNumber("7.2.0-4.0-1.0-1.0-1"))
        assertTrue(
            DoorsDerivations.parentNumber("7.2.0-4.0-1.0-10.0-1") !=
                DoorsDerivations.parentNumber("7.2.0-4.0-1.0-1.0-1"),
        )
    }

    // -- sortKey -------------------------------------------------------------------------------

    @Test
    fun `sort key, simple`() {
        assertEquals("000001", DoorsDerivations.sortKey("1"))
    }

    @Test
    fun `sort key, two segments`() {
        assertEquals("000001.000002", DoorsDerivations.sortKey("1.2"))
    }

    /**
     * A dash is a level separator like a dot, and the key normalises it to one (ADR 0022). Keeping
     * both characters is what made `6.2.1-1` sort ahead of `6.2.1.0-7`, because `-` is 0x2D and
     * `.` is 0x2E.
     */
    @Test
    fun `sort key, non-heading — a dash is a level separator like a dot`() {
        assertEquals("000007.000002.000000.000004", DoorsDerivations.sortKey("7.2.0-4"))
    }

    /**
     * The case the separator bug produced, pinned as a unit test as well as against real data: a
     * deeper number under the same parent sorts before a shallower one with a higher index.
     */
    @Test
    fun `sort key orders a deeper branch before a later sibling`() {
        assertEquals(
            listOf("6.2.1.0-7", "6.2.1-1"),
            listOf("6.2.1-1", "6.2.1.0-7").sortedBy { DoorsDerivations.sortKey(it) },
        )
    }

    @Test
    fun `sort key reproduces document order`() {
        val nums = listOf("10", "9", "2.1", "2.10", "2.2")
        assertEquals(
            listOf("2.1", "2.2", "2.10", "9", "10"),
            nums.sortedBy { DoorsDerivations.sortKey(it) },
        )
    }

    // -- deriveTypeLabel -------------------------------------------------------------------

    @Test
    fun `derive type label, known types`() {
        assertEquals(DoorsLabel.HEADING to false, DoorsDerivations.deriveTypeLabel("Heading"))
        assertEquals(DoorsLabel.REQUIREMENT to false, DoorsDerivations.deriveTypeLabel("Requirement"))
        assertEquals(DoorsLabel.INFORMATION to false, DoorsDerivations.deriveTypeLabel("Information"))
        assertEquals(DoorsLabel.APP_MATRIX to false, DoorsDerivations.deriveTypeLabel("AppMatrix"))
        assertEquals(
            DoorsLabel.APP_MATRIX_HEADING to false,
            DoorsDerivations.deriveTypeLabel("AppMatrixHeading"),
        )
        assertEquals(DoorsLabel.TBD to false, DoorsDerivations.deriveTypeLabel("TBD"))
    }

    @Test
    fun `derive type label, empty is tbd`() {
        assertEquals(DoorsLabel.TBD to false, DoorsDerivations.deriveTypeLabel(""))
    }

    @Test
    fun `derive type label, unknown is tbd and flagged`() {
        val (label, isUnknown) = DoorsDerivations.deriveTypeLabel("SomethingWeird")
        assertEquals(DoorsLabel.TBD, label)
        assertTrue(isUnknown)
    }

    // -- deriveName --------------------------------------------------------------------------

    @Test
    fun `derive name, heading uses Object Heading`() {
        val obj = mapOf(
            "Object Type" to "Heading",
            "Object Heading" to "My Heading",
            "Object Short Text" to "ignored",
        )
        assertEquals("My Heading", DoorsDerivations.deriveName(obj))
    }

    @Test
    fun `derive name, non-heading uses Object Short Text`() {
        val obj = mapOf(
            "Object Type" to "Requirement",
            "Object Short Text" to "Short",
            "Object Heading" to "ignored",
        )
        assertEquals("Short", DoorsDerivations.deriveName(obj))
    }

    @Test
    fun `derive name falls back to Object Text`() {
        val obj = mapOf("Object Type" to "Requirement", "Object Short Text" to "", "Object Text" to "Long text")
        assertEquals("Long text", DoorsDerivations.deriveName(obj))
    }

    @Test
    fun `derive name truncates Object Text at 120 characters with an ellipsis`() {
        val obj = mapOf("Object Type" to "Requirement", "Object Short Text" to "", "Object Text" to "x".repeat(150))
        val name = DoorsDerivations.deriveName(obj)
        assertEquals(121, name.length)
        assertTrue(name.endsWith("…"))
        assertEquals("x".repeat(120), name.take(120))
    }

    @Test
    fun `derive name falls back to id`() {
        val obj = mapOf("Object Type" to "Requirement", "Object Short Text" to "", "Object Text" to "", "id" to "SRD-1")
        assertEquals("SRD-1", DoorsDerivations.deriveName(obj))
    }

    @Test
    fun `derive name, empty Object Type uses Object Short Text`() {
        val obj = mapOf("Object Type" to "", "Object Short Text" to "ST", "id" to "X")
        assertEquals("ST", DoorsDerivations.deriveName(obj))
    }

    @Test
    fun `derive name is never empty`() {
        val name = DoorsDerivations.deriveName(mapOf("id" to "SRD-999"))
        assertTrue(name.isNotEmpty())
    }

    // -- computeTableSets ----------------------------------------------------------------------

    private fun tableObj(id: String, num: String, tableObject: String = "false", tableId: String = "") =
        mapOf("id" to id, "objectNumber" to num, "__tableObject" to tableObject, "__tableID" to tableId)

    @Test
    fun `compute table sets, no tables`() {
        val (tableIds, rowIds) = DoorsDerivations.computeTableSets(
            listOf(tableObj("A", "1"), tableObj("B", "1.1")),
        )
        assertTrue(tableIds.isEmpty())
        assertTrue(rowIds.isEmpty())
    }

    @Test
    fun `compute table sets identifies a table and its row`() {
        val (tableIds, rowIds) = DoorsDerivations.computeTableSets(
            listOf(
                tableObj("T1", "2"),
                tableObj("R1", "2.1"),
                tableObj("C1", "2.1.1", "true", "T1"),
            ),
        )
        assertTrue("T1" in tableIds)
        assertTrue("R1" in rowIds)
    }

    @Test
    fun `a table row must have a cell child of its own`() {
        val (tableIds, rowIds) = DoorsDerivations.computeTableSets(
            listOf(
                tableObj("T1", "2"),
                tableObj("R1", "2.1"),
                tableObj("C1", "2.2", "true", "T1"),
            ),
        )
        assertTrue("T1" in tableIds)
        assertFalse("R1" in rowIds)
    }

    // -- deriveLabels ------------------------------------------------------------------------

    @Test
    fun `derive labels, basic requirement`() {
        val obj = mapOf("Object Type" to "Requirement", "id" to "X", "__tableObject" to "false")
        val labels = DoorsDerivations.deriveLabels(obj, emptySet(), emptySet())
        assertTrue("SEItem" in labels)
        assertTrue(DoorsLabel.OBJECT in labels)
        assertTrue(DoorsLabel.REQUIREMENT in labels)
    }

    @Test
    fun `derive labels, a cell gets the cell label`() {
        val obj = mapOf("Object Type" to "TBD", "id" to "C1", "__tableObject" to "true")
        assertTrue(DoorsLabel.TABLE_CELL in DoorsDerivations.deriveLabels(obj, emptySet(), emptySet()))
    }

    @Test
    fun `derive labels, a table gets the table label`() {
        val obj = mapOf("Object Type" to "TBD", "id" to "T1", "__tableObject" to "false")
        assertTrue(DoorsLabel.TABLE in DoorsDerivations.deriveLabels(obj, setOf("T1"), emptySet()))
    }

    @Test
    fun `derive labels, a row gets the row label`() {
        val obj = mapOf("Object Type" to "TBD", "id" to "R1", "__tableObject" to "false")
        assertTrue(DoorsLabel.TABLE_ROW in DoorsDerivations.deriveLabels(obj, emptySet(), setOf("R1")))
    }

    @Test
    fun `derive labels, empty type gets tbd`() {
        val obj = mapOf("Object Type" to "", "id" to "X", "__tableObject" to "false")
        assertTrue(DoorsLabel.TBD in DoorsDerivations.deriveLabels(obj, emptySet(), emptySet()))
    }
}
