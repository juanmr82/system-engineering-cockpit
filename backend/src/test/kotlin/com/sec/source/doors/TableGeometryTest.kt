package com.sec.source.doors

import com.sec.api.dto.TableAnomalyKind
import com.sec.api.dto.TableAnomalySeverity
import com.sec.domain.Ref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/DOORS_TABLES.md §8, the pure half: no database, no Ktor, no container.
 *
 * The point of every case below is the same one — **a defect in the export never costs a cell.**
 * A missing index, a corrupt key, a duplicate ordinal and an unparseable outline number each
 * produce an anomaly *and* a complete matrix, never one without the other.
 */
class TableGeometryTest {

    // --- §3.1 outlineOrdinal ----------------------------------------------------------------------

    @Test
    fun `outline ordinal is the trailing number of the last dot-segment`() {
        assertEquals(1, TableGeometry.outlineOrdinal("2.1.0-1"))
        assertEquals(3, TableGeometry.outlineOrdinal("2.1.0-1.0-3"))
        assertEquals(12, TableGeometry.outlineOrdinal("2.1.0-1.0-12"))
        assertEquals(2, TableGeometry.outlineOrdinal("7.2"))
    }

    // A versionId-style segment carries extra hyphens; only the trailing number counts.
    @Test
    fun `outline ordinal reads only past the last hyphen of the last segment`() {
        assertEquals(4, TableGeometry.outlineOrdinal("2.1.0-1.0-2-4"))
    }

    @Test
    fun `a malformed outline number is null rather than an exception`() {
        assertNull(TableGeometry.outlineOrdinal(""))
        assertNull(TableGeometry.outlineOrdinal("2.1.x"))
        assertNull(TableGeometry.outlineOrdinal("2.1.0-"))
        // 0 is not a 1-based ordinal, so it cannot place a cell.
        assertNull(TableGeometry.outlineOrdinal("2.1.0-0"))
    }

    // --- §3.3 matrix assembly ---------------------------------------------------------------------

    @Test
    fun `a rectangular table becomes a dense matrix in outline order`() {
        val view = TableGeometry.assemble(table(rows = 2, columns = 3))

        assertEquals(2, view.rowCount)
        assertEquals(3, view.columnCount)
        assertEquals(listOf(1, 2), view.rows.map { it.rowNumber })
        assertTrue(view.rows.all { it.cells.size == 3 })
        assertTrue(view.rows.all { row -> row.cells.all { it.present } })
        assertEquals("r1c1", view.rows[0].cells[0].text)
        assertEquals("r2c3", view.rows[1].cells[2].text)
        assertTrue(view.anomalies.isEmpty(), view.anomalies.toString())
    }

    @Test
    fun `the first row is the header row and the count is data, not a constant`() {
        val view = TableGeometry.assemble(table(rows = 3, columns = 2))

        assertEquals(1, view.headerRowCount)
        assertTrue(view.rows[0].isHeader)
        assertFalse(view.rows[1].isHeader)
        assertFalse(view.rows[2].isHeader)
    }

    // rowCount == 1 — a header with no body still renders, and headerRowCount does not exceed it.
    @Test
    fun `a header-only table renders`() {
        val view = TableGeometry.assemble(table(rows = 1, columns = 2))

        assertEquals(1, view.rowCount)
        assertEquals(1, view.headerRowCount)
        assertTrue(view.rows.single().isHeader)
    }

    @Test
    fun `a single-cell table renders`() {
        val view = TableGeometry.assemble(table(rows = 1, columns = 1))

        assertEquals(1, view.rowCount)
        assertEquals(1, view.columnCount)
        assertEquals("r1c1", view.rows.single().cells.single().text)
    }

    @Test
    fun `a table with no cells is reported empty rather than left silent`() {
        val view = TableGeometry.assemble(
            TableGeometry.SourceTable(table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL)),
        )

        assertEquals(0, view.rowCount)
        assertEquals(0, view.columnCount)
        assertEquals(0, view.headerRowCount)
        assertTrue(view.rows.isEmpty())
        assertEquals(listOf(TableAnomalyKind.EMPTY_TABLE), view.anomalies.map { it.kind })
    }

    @Test
    fun `a short row is drawn full width and reported non-rectangular, never as a merged cell`() {
        val source = tableOf(
            row(1, "a1", "a2", "a3"),
            row(2, "b1"),
        )

        val view = TableGeometry.assemble(source)

        assertEquals(3, view.columnCount)
        assertEquals(3, view.rows[1].cells.size)
        assertTrue(view.rows[1].cells[0].present)
        assertFalse(view.rows[1].cells[1].present)
        assertFalse(view.rows[1].cells[2].present)
        assertEquals("", view.rows[1].cells[1].text)
        assertEquals(1, view.anomalies.count { it.kind == TableAnomalyKind.NON_RECTANGULAR })
        // A row that stops early is one finding, not one per trailing column.
        assertEquals(0, view.anomalies.count { it.kind == TableAnomalyKind.MISSING_CELL })
    }

    @Test
    fun `a hole in the middle of a row is a missing cell, and the cells after it do not shift left`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(
                        cell("C1", "2.1.0-1.0-1.0-1", "a1"),
                        // no ordinal 2 — the cell was deleted in DOORS
                        cell("C3", "2.1.0-1.0-1.0-3", "a3"),
                    ),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals(3, view.columnCount)
        assertEquals("a1", view.rows[0].cells[0].text)
        assertFalse(view.rows[0].cells[1].present)
        assertEquals("a3", view.rows[0].cells[2].text)
        assertEquals(1, view.anomalies.count { it.kind == TableAnomalyKind.MISSING_CELL })
    }

    @Test
    fun `a row ordinal nobody claims leaves an absent band rather than pulling later rows up`() {
        val source = tableOf(row(1, "a1"), row(3, "c1"))

        val view = TableGeometry.assemble(source)

        assertEquals(3, view.rowCount)
        assertTrue(view.rows[0].present)
        assertFalse(view.rows[1].present)
        assertNull(view.rows[1].ref)
        assertTrue(view.rows[2].present)
        assertEquals("c1", view.rows[2].cells[0].text)
    }

    @Test
    fun `two cells claiming one column keep both, and the collision is an error`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(
                        cell("C1", "2.1.0-1.0-1.0-1", "first"),
                        cell("C2", "2.1.0-1.0-1.0-1", "second"),
                    ),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals(2, view.columnCount)
        assertEquals("first", view.rows[0].cells[0].text)
        assertEquals("second", view.rows[0].cells[1].text)
        val duplicate = view.anomalies.single { it.kind == TableAnomalyKind.DUPLICATE_COLUMN_ORDINAL }
        assertEquals(TableAnomalySeverity.ERROR, duplicate.severity)
    }

    @Test
    fun `a cell whose outline number cannot be parsed keeps its arrival position`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(
                        cell("C1", "2.1.0-1.0-1.0-1", "a1"),
                        cell("C2", "nonsense", "a2"),
                    ),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals(2, view.columnCount)
        assertEquals("a2", view.rows[0].cells[1].text)
        val malformed = view.anomalies.single { it.kind == TableAnomalyKind.MALFORMED_OBJECT_NUMBER }
        assertEquals(TableAnomalySeverity.ERROR, malformed.severity)
        assertEquals("nonsense", malformed.objectNumber)
    }

    // --- §2.1 the exported indices are a cross-check and nothing more -----------------------------

    @Test
    fun `an agreeing exported index raises nothing`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL, exportedRowIndex = 0),
                    children = listOf(
                        cell("C1", "2.1.0-1.0-1.0-1", "a1", exportedRowIndex = 0, exportedColumnIndex = 0),
                        cell("C2", "2.1.0-1.0-1.0-2", "a2", exportedRowIndex = 0, exportedColumnIndex = 1),
                    ),
                ),
            ),
        )

        assertTrue(TableGeometry.assemble(source).anomalies.isEmpty())
    }

    @Test
    fun `a disagreeing exported index is reported, and the outline number still decides`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(cell("C1", "2.1.0-1.0-1.0-1", "a1", exportedColumnIndex = 4)),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals("a1", view.rows[0].cells[0].text)
        assertEquals(1, view.anomalies.count { it.kind == TableAnomalyKind.INDEX_MISMATCH })
    }

    // The `__taSbleRowIndex` corrupt-key defect: the correct key is simply absent, which the
    // importer turns into no property at all. That must be ordinary, not a finding.
    @Test
    fun `an absent exported index is normal and produces a correct matrix`() {
        val view = TableGeometry.assemble(table(rows = 2, columns = 2))

        assertTrue(view.rows.all { row -> row.cells.all { it.present } })
        assertEquals(0, view.anomalies.count { it.kind == TableAnomalyKind.INDEX_MISMATCH })
    }

    @Test
    fun `cells arriving out of ordinal order are placed by ordinal and the disagreement is reported`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(
                        cell("C2", "2.1.0-1.0-1.0-2", "second"),
                        cell("C1", "2.1.0-1.0-1.0-1", "first"),
                    ),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals("first", view.rows[0].cells[0].text)
        assertEquals("second", view.rows[0].cells[1].text)
        assertEquals(1, view.anomalies.count { it.kind == TableAnomalyKind.SORTKEY_ORDINAL_DISAGREEMENT })
    }

    // --- §3.5 cell text ---------------------------------------------------------------------------

    @Test
    fun `an empty Object Text stays empty and never falls back to a name or an id`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(
                        cell("C1", "2.1.0-1.0-1.0-1", ""),
                        cell("C2", "2.1.0-1.0-1.0-2", null),
                    ),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals("", view.rows[0].cells[0].text)
        assertEquals("", view.rows[0].cells[1].text)
        // Both cells exist — an empty value is not an absent cell.
        assertTrue(view.rows[0].cells.all { it.present })
        assertEquals("SRD-C1", view.rows[0].cells[0].id)
    }

    @Test
    fun `multi-line cell text keeps its newlines`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(cell("C1", "2.1.0-1.0-1.0-1", "line one\nline two")),
                ),
            ),
        )

        assertEquals("line one\nline two", TableGeometry.assemble(source).rows[0].cells[0].text)
    }

    // --- §3.6 unexpected children -----------------------------------------------------------------

    @Test
    fun `a child of the table that is not a row becomes a band in its document-order position`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(cell("C1", "2.1.0-1.0-1.0-1", "a1")),
                ),
                TableGeometry.SourceChild(
                    node = obj("CAP", "2.1.0-1.0-2", "DOORSInformation", text = "Table 1: masses"),
                ),
            ),
        )

        val view = TableGeometry.assemble(source)

        assertEquals(1, view.rowCount)
        val band = view.extraBands.single()
        assertEquals("Table 1: masses", band.text)
        assertEquals(1, band.after)
        assertEquals(1, view.anomalies.count { it.kind == TableAnomalyKind.UNEXPECTED_TABLE_CHILD })
    }

    @Test
    fun `a nested table is reported rather than flattened`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(
                        obj(
                            "C1",
                            "2.1.0-1.0-1.0-1",
                            TableGeometry.TABLE_CELL_LABEL,
                            TableGeometry.TABLE_LABEL,
                        ),
                    ),
                ),
            ),
        )

        val nested = TableGeometry.assemble(source).anomalies.single { it.kind == TableAnomalyKind.NESTED_TABLE }
        assertEquals(TableAnomalySeverity.INFO, nested.severity)
    }

    // --- §6.6 column weights ------------------------------------------------------------------------

    @Test
    fun `column weights are relative, bounded, and one per column`() {
        val source = tableOf(
            row(1, "ab", "a".repeat(200)),
            row(2, "cd", "b".repeat(200)),
        )

        val view = TableGeometry.assemble(source)

        assertEquals(2, view.columnWeights.size)
        assertEquals(1.0, view.columnWeights[0])
        // 200 / 2 is 100, clamped to the [1, 6] ratio the track list is allowed to span.
        assertEquals(6.0, view.columnWeights[1])
    }

    @Test
    fun `equal content gives equal fractions`() {
        val view = TableGeometry.assemble(table(rows = 2, columns = 3))

        assertEquals(listOf(1.0, 1.0, 1.0), view.columnWeights)
    }

    @Test
    fun `a table whose every cell is empty falls back to equal fractions rather than dividing by zero`() {
        val source = TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = listOf(
                TableGeometry.SourceChild(
                    node = obj("R1", "2.1.0-1.0-1", TableGeometry.TABLE_ROW_LABEL),
                    children = listOf(cell("C1", "2.1.0-1.0-1.0-1", ""), cell("C2", "2.1.0-1.0-1.0-2", "")),
                ),
            ),
        )

        assertEquals(listOf(1.0, 1.0), TableGeometry.assemble(source).columnWeights)
    }

    // --- R5 / R6 ------------------------------------------------------------------------------------

    @Test
    fun `every identifier on the wire is the opaque handle, never the internal id`() {
        val view = TableGeometry.assemble(table(rows = 1, columns = 1))

        assertEquals(Ref.encode("doors://T"), view.ref)
        assertEquals(Ref.encode("doors://R1"), view.rows[0].ref)
        assertEquals(Ref.encode("doors://C1"), view.rows[0].cells[0].ref)
        assertEquals("SRD-T", view.id)
    }

    @Test
    fun `assembling the same source twice produces an identical view`() {
        val source = tableOf(row(1, "a1", "a2"), row(2, "b1"))

        assertEquals(TableGeometry.assemble(source), TableGeometry.assemble(source))
    }

    @Test
    fun `an anomaly names the object it is about, so it can be looked up in DOORS`() {
        val source = tableOf(row(1, "a1", "a2"), row(2, "b1"))

        val anomaly = TableGeometry.assemble(source).anomalies.single()
        assertNotNull(anomaly.id)
        assertNotNull(anomaly.objectNumber)
        assertNotNull(anomaly.ref)
        assertFalse(anomaly.message.contains("__"), anomaly.message)
    }

    // --- Fixtures -------------------------------------------------------------------------------------

    private fun obj(
        id: String,
        objectNumber: String,
        vararg labels: String,
        text: String? = null,
        exportedRowIndex: Int? = null,
        exportedColumnIndex: Int? = null,
    ) = TableGeometry.SourceObject(
        itemId = "doors://$id",
        doorsId = "SRD-$id",
        objectNumber = objectNumber,
        labels = labels.toSet(),
        text = text,
        exportedRowIndex = exportedRowIndex,
        exportedColumnIndex = exportedColumnIndex,
    )

    private fun cell(
        id: String,
        objectNumber: String,
        text: String?,
        exportedRowIndex: Int? = null,
        exportedColumnIndex: Int? = null,
    ) = obj(
        id,
        objectNumber,
        TableGeometry.TABLE_CELL_LABEL,
        text = text,
        exportedRowIndex = exportedRowIndex,
        exportedColumnIndex = exportedColumnIndex,
    )

    /** One row at [rowNumber] whose cells hold [texts], numbered from column 1. */
    private fun row(rowNumber: Int, vararg texts: String) =
        TableGeometry.SourceChild(
            node = obj("R$rowNumber", "2.1.0-1.0-$rowNumber", TableGeometry.TABLE_ROW_LABEL),
            children = texts.mapIndexed { index, text ->
                cell("R${rowNumber}C${index + 1}", "2.1.0-1.0-$rowNumber.0-${index + 1}", text)
            },
        )

    private fun tableOf(vararg rows: TableGeometry.SourceChild) =
        TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = rows.toList(),
        )

    /** A rectangular [rows] × [columns] table whose cells read "r1c1", "r1c2", … */
    private fun table(rows: Int, columns: Int): TableGeometry.SourceTable {
        val children = (1..rows).map { r ->
            TableGeometry.SourceChild(
                node = obj("R$r", "2.1.0-1.0-$r", TableGeometry.TABLE_ROW_LABEL),
                children = (1..columns).map { c ->
                    cell(if (rows == 1 && columns == 1) "C1" else "R${r}C$c", "2.1.0-1.0-$r.0-$c", "r${r}c$c")
                },
            )
        }
        return TableGeometry.SourceTable(
            table = obj("T", "2.1.0-1", TableGeometry.TABLE_LABEL),
            children = children,
        )
    }
}
