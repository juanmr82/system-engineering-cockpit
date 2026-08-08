package com.sec.source.doors

import com.sec.api.dto.DoorsTableBandDto
import com.sec.api.dto.DoorsTableCellDto
import com.sec.api.dto.DoorsTableRowDto
import com.sec.api.dto.DoorsTableViewDto
import com.sec.api.dto.TableAnomalyDto
import com.sec.api.dto.TableAnomalyKind
import com.sec.api.dto.TableAnomalySeverity
import com.sec.domain.Ref
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry derivation for DOORS tables (docs/DOORS_TABLES.md §3), and the only place the rules in
 * that document live.
 *
 * **Pure.** No Neo4j driver, no Ktor, no I/O — [DoorsTableProjection] fetches, this assembles.
 * Everything here is derivation at read time: nothing is written back to a `DOORSObject`, and
 * nothing derived here is stored (§9, and R2, which excludes derived data from `:__Meta` for
 * exactly this reason).
 *
 * The one rule worth restating, because it is counter-intuitive: **row and column come from
 * `objectNumber`, not from `__tableRowIndex` / `__tableColumnIndex`.** Those two exist and are
 * 0-based, but the export has a known corrupt-key defect (`__taSbleRowIndex`) and the importer
 * omits them where they do not parse, so `null` is normal. They are a cross-check that raises
 * [TableAnomalyKind.INDEX_MISMATCH]; they never decide where a cell goes and can never make one
 * disappear (§2.1).
 */
public object TableGeometry {

    /** The header row is the first one (§3.4). Bold in DOORS. */
    private const val HEADER_ROW_NUMBER = 1

    // Column-weight bounds from §6.6. A weight is a *fraction* in a CSS track list, so the ratio
    // between the narrowest and the widest column is what these clamp — six to one is already an
    // extreme table, and past it the narrow columns stop being readable at all.
    private const val MIN_WEIGHT = 1.0
    private const val MAX_WEIGHT = 6.0
    private const val WEIGHT_PERCENTILE = 0.9
    private const val WEIGHT_PRECISION = 100.0

    // --- Input ------------------------------------------------------------------------------------

    /**
     * One imported object, reduced to what geometry needs.
     *
     * `text` is `Object Text` and distinguishes absent (`null`) from present-and-empty (`""`).
     * Both render as an empty cell; the distinction is kept because it is a real one in DOORS and
     * a future caller should not have to reintroduce it.
     */
    public data class SourceObject(
        val itemId: String,
        val doorsId: String?,
        val objectNumber: String,
        val labels: Set<String>,
        val text: String? = null,
        val exportedRowIndex: Int? = null,
        val exportedColumnIndex: Int? = null,
    )

    /**
     * A `__child` of the table with its own `__child`ren, both in `__sortKey` order.
     *
     * Deliberately *not* named "row": a child of a table need not be one (§3.6), and calling it a
     * row here is what would make the unexpected case invisible.
     */
    public data class SourceChild(
        val node: SourceObject,
        val children: List<SourceObject> = emptyList(),
    )

    public data class SourceTable(
        val table: SourceObject,
        val children: List<SourceChild> = emptyList(),
    )

    // --- §3.1 Outline ordinal ---------------------------------------------------------------------

    /**
     * 1-based ordinal of an object among its siblings, taken from the last dot-segment (§3.1).
     *
     * ```
     * "2.1.0-1"      -> 1
     * "2.1.0-1.0-3"  -> 3
     * "2.1.0-1.0-12" -> 12
     * "7.2"          -> 2
     * ```
     *
     * Splitting on `.` is mandatory: prefix matching relates the wrong objects, which is why the
     * importer spec forbids it (R7 there — 457 of 984 reference object numbers are ambiguous under
     * it).
     *
     * Returns null rather than throwing. A malformed `objectNumber` is an **expected** failure —
     * the export has defects the importer is required to survive — so it becomes a
     * [TableAnomalyKind.MALFORMED_OBJECT_NUMBER] and the object keeps its place, rather than an
     * exception that would take the whole module's tables down with it (CLAUDE.md §11).
     */
    public fun outlineOrdinal(objectNumber: String): Int? =
        objectNumber
            .substringAfterLast('.')
            .substringAfterLast('-')
            .toIntOrNull()
            ?.takeIf { it >= 1 }

    // --- Assembly ---------------------------------------------------------------------------------

    /**
     * The rendering-ready view for one table.
     *
     * Assembled server-side on purpose: it keeps the geometry rules in one testable place instead
     * of in TypeScript, where the same "is this cell missing or is this row short" question would
     * be answered a second time and eventually differently (§4.2).
     */
    public fun assemble(source: SourceTable): DoorsTableViewDto {
        val anomalies = mutableListOf<TableAnomalyDto>()

        val (rowSources, extraBands) = splitChildren(source, anomalies)
        val placedRows = placeRows(rowSources, anomalies)
        val cellsByRow = placedRows.associate { it.rowNumber to placeCells(it, anomalies) }

        val rowCount = placedRows.maxOfOrNull { it.rowNumber } ?: 0
        val columnCount = cellsByRow.values.flatMap { it.keys }.maxOrNull() ?: 0

        if (columnCount == 0) {
            anomalies += anomaly(
                TableAnomalyKind.EMPTY_TABLE,
                TableAnomalySeverity.WARN,
                "This table has no cells, so there is nothing to lay out.",
                source.table,
            )
        }

        val byRowNumber = placedRows.associateBy { it.rowNumber }
        val rows = (1..rowCount).map { rowNumber ->
            buildRow(
                rowNumber = rowNumber,
                placed = byRowNumber[rowNumber],
                cells = cellsByRow[rowNumber].orEmpty(),
                columnCount = columnCount,
                table = source.table,
                anomalies = anomalies,
            )
        }

        return DoorsTableViewDto(
            ref = Ref.encode(source.table.itemId),
            id = source.table.doorsId,
            objectNumber = source.table.objectNumber,
            rowCount = rowCount,
            columnCount = columnCount,
            headerRowCount = if (rowCount > 0) 1 else 0,
            columnWeights = columnWeights(rows, columnCount),
            rows = rows,
            extraBands = extraBands,
            // Most fundamental first, so a panel that shows three of nine shows the three worth
            // acting on. Within a severity the order is the order they were found, which is
            // document order.
            anomalies = anomalies.sortedByDescending { it.severity.ordinal },
        )
    }

    // --- §3.6 Unexpected children -----------------------------------------------------------------

    private fun splitChildren(
        source: SourceTable,
        anomalies: MutableList<TableAnomalyDto>,
    ): Pair<List<SourceChild>, List<DoorsTableBandDto>> {
        val rows = mutableListOf<SourceChild>()
        val bands = mutableListOf<DoorsTableBandDto>()
        // Children arrive in __sortKey order, so "how many rows have been seen" is the band's
        // position. The client never sees __sortKey itself (R5).
        var rowsSoFar = 0

        for (child in source.children) {
            if (DoorsLabel.TABLE_ROW in child.node.labels) {
                rows += child
                rowsSoFar++
                continue
            }

            bands += DoorsTableBandDto(
                ref = Ref.encode(child.node.itemId),
                id = child.node.doorsId,
                after = rowsSoFar,
                text = child.node.text.orEmpty(),
            )
            anomalies += anomaly(
                TableAnomalyKind.UNEXPECTED_TABLE_CHILD,
                TableAnomalySeverity.WARN,
                "This object sits inside the table but is not one of its rows. " +
                    "It is drawn as a full-width band in its document-order position.",
                child.node,
            )
            if (DoorsLabel.TABLE in child.node.labels) {
                anomalies += nestedTable(child.node)
            }
        }

        // A nested table hangs off a cell, so it is found on the way down rather than here.
        source.children
            .flatMap { it.children }
            .filter { DoorsLabel.TABLE in it.labels }
            .forEach { anomalies += nestedTable(it) }

        return rows to bands
    }

    /**
     * A table inside a table (§3.6). Reported, never flattened silently.
     *
     * The nested table's own rows are *not* reconstructed here: the read query descends exactly two
     * `__child` levels, which is what a table is, so a third level is not in hand. §3.6's depth cap
     * of 3 therefore has nothing to cap yet — when a real nested table turns up, the query grows
     * and the cap becomes load-bearing. Until then INFO is honest and a silent flatten would not be.
     */
    private fun nestedTable(node: SourceObject): TableAnomalyDto =
        anomaly(
            TableAnomalyKind.NESTED_TABLE,
            TableAnomalySeverity.INFO,
            "This object is itself a table. Its own rows are not drawn here — open it to see them.",
            node,
        )

    // --- Row placement ----------------------------------------------------------------------------

    private class PlacedRow(val rowNumber: Int, val source: SourceChild)

    /**
     * Rows placed by ordinal, so a deleted row leaves a **gap** rather than pulling every later row
     * up one band (§3.2).
     *
     * Two orders are in play and they are different things: `__sortKey` decides what comes before
     * what, the ordinal decides which band an object occupies. They should agree; when they do not,
     * the ordinal still decides placement — a dense matrix has no other way to be built — and
     * [TableAnomalyKind.SORTKEY_ORDINAL_DISAGREEMENT] says so.
     */
    private fun placeRows(
        rowSources: List<SourceChild>,
        anomalies: MutableList<TableAnomalyDto>,
    ): List<PlacedRow> {
        val taken = mutableSetOf<Int>()
        val placed = mutableListOf<PlacedRow>()
        var previousOrdinal = 0
        var disagreementReported = false

        rowSources.forEachIndexed { index, child ->
            val ordinal = ordinalOf(child.node, fallback = index + 1, anomalies = anomalies)

            if (!disagreementReported && ordinal <= previousOrdinal) {
                anomalies += anomaly(
                    TableAnomalyKind.SORTKEY_ORDINAL_DISAGREEMENT,
                    TableAnomalySeverity.WARN,
                    "This row's outline number does not follow the one before it in document order. " +
                        "The rows are drawn in outline-number order.",
                    child.node,
                )
                disagreementReported = true
            }
            previousOrdinal = ordinal

            val free = nextFree(ordinal, taken)
            if (free != ordinal) {
                anomalies += anomaly(
                    TableAnomalyKind.DUPLICATE_ROW_ORDINAL,
                    TableAnomalySeverity.ERROR,
                    "Two rows of this table claim row $ordinal. This one is drawn as row $free " +
                        "so that neither is lost.",
                    child.node,
                )
            }
            taken += free
            placed += PlacedRow(free, child)

            val exported = child.node.exportedRowIndex
            if (exported != null && exported + 1 != free) {
                anomalies += indexMismatch(child.node, "row", exported + 1, free)
            }
        }

        return placed
    }

    // --- Cell placement ---------------------------------------------------------------------------

    private fun placeCells(
        row: PlacedRow,
        anomalies: MutableList<TableAnomalyDto>,
    ): Map<Int, SourceObject> {
        val placed = linkedMapOf<Int, SourceObject>()
        var previousOrdinal = 0
        var disagreementReported = false

        row.source.children.forEachIndexed { index, cell ->
            // A nested table is reported, not laid out as a cell of its parent.
            if (DoorsLabel.TABLE_CELL !in cell.labels) {
                anomalies += anomaly(
                    TableAnomalyKind.UNEXPECTED_TABLE_CHILD,
                    TableAnomalySeverity.WARN,
                    "This object sits inside a table row but is not one of its cells. " +
                        "It is still placed by its outline number.",
                    cell,
                )
            }

            val ordinal = ordinalOf(cell, fallback = index + 1, anomalies = anomalies)

            if (!disagreementReported && ordinal <= previousOrdinal) {
                anomalies += anomaly(
                    TableAnomalyKind.SORTKEY_ORDINAL_DISAGREEMENT,
                    TableAnomalySeverity.WARN,
                    "This cell's outline number does not follow the one before it in document order. " +
                        "The cells are drawn in outline-number order.",
                    cell,
                )
                disagreementReported = true
            }
            previousOrdinal = ordinal

            val free = nextFree(ordinal, placed.keys)
            if (free != ordinal) {
                anomalies += anomaly(
                    TableAnomalyKind.DUPLICATE_COLUMN_ORDINAL,
                    TableAnomalySeverity.ERROR,
                    "Two cells of this row claim column $ordinal. This one is drawn in column $free " +
                        "so that neither is lost.",
                    cell,
                )
            }
            placed[free] = cell

            val exportedColumn = cell.exportedColumnIndex
            if (exportedColumn != null && exportedColumn + 1 != free) {
                anomalies += indexMismatch(cell, "column", exportedColumn + 1, free)
            }
            val exportedRow = cell.exportedRowIndex
            if (exportedRow != null && exportedRow + 1 != row.rowNumber) {
                anomalies += indexMismatch(cell, "row", exportedRow + 1, row.rowNumber)
            }
        }

        return placed
    }

    // --- One band ---------------------------------------------------------------------------------

    private fun buildRow(
        rowNumber: Int,
        placed: PlacedRow?,
        cells: Map<Int, SourceObject>,
        columnCount: Int,
        table: SourceObject,
        anomalies: MutableList<TableAnomalyDto>,
    ): DoorsTableRowDto {
        val rowNode = placed?.source?.node

        // The rightmost column this row actually reaches. Gaps *before* it are holes in the middle
        // of a row, which is a different finding from a row that simply stops early — reporting a
        // short row as N missing cells buries the one thing worth knowing under N-1 repetitions.
        val lastPresent = cells.keys.maxOrNull() ?: 0
        for (column in 1 until lastPresent) {
            if (column !in cells) {
                anomalies += anomaly(
                    TableAnomalyKind.MISSING_CELL,
                    TableAnomalySeverity.WARN,
                    "No object sits at row $rowNumber, column $column of this table. " +
                        "The cell is drawn empty.",
                    rowNode ?: table,
                )
            }
        }
        if (columnCount > 0 && lastPresent < columnCount) {
            anomalies += anomaly(
                TableAnomalyKind.NON_RECTANGULAR,
                TableAnomalySeverity.WARN,
                "Row $rowNumber has $lastPresent of the table's $columnCount columns. " +
                    "The rest are drawn empty — merged cells are never guessed.",
                rowNode ?: table,
            )
        }

        return DoorsTableRowDto(
            rowNumber = rowNumber,
            isHeader = rowNumber == HEADER_ROW_NUMBER,
            present = rowNode != null,
            ref = rowNode?.let { Ref.encode(it.itemId) },
            id = rowNode?.doorsId,
            cells = (1..columnCount).map { column ->
                val cell = cells[column]
                DoorsTableCellDto(
                    columnNumber = column,
                    present = cell != null,
                    ref = cell?.let { Ref.encode(it.itemId) },
                    id = cell?.doorsId,
                    // Object Text verbatim, never a fallback to __name (§3.5). "" is a real value.
                    text = cell?.text.orEmpty(),
                )
            },
        )
    }

    // --- §6.6 Column weights ----------------------------------------------------------------------

    /**
     * One relative track weight per column, from the 90th-percentile character count of that
     * column's `Object Text` (§6.6).
     *
     * The spec says "clamped to `[1, 6]`", which cannot mean the raw character count — every column
     * past six characters would weigh the same. It is a **ratio**: each column's percentile divided
     * by the narrowest column's, so the narrowest is always `1fr` and nothing can exceed `6fr`. The
     * result approximates the screenshots — a narrow Version column beside a wide description — and
     * it is honest about being derived, because DOORS's real widths are not in the export at all.
     *
     * Degenerate input (no cells, or every column empty) returns equal fractions, which is §6.6's
     * stated fallback rather than a special case.
     */
    private fun columnWeights(rows: List<DoorsTableRowDto>, columnCount: Int): List<Double> {
        if (columnCount == 0) {
            return emptyList()
        }

        val percentiles = (1..columnCount).map { column ->
            val lengths = rows
                .mapNotNull { row -> row.cells.getOrNull(column - 1) }
                .filter { it.present }
                .map { it.text.length }
                .sorted()
            percentile(lengths, WEIGHT_PERCENTILE)
        }

        val narrowest = percentiles.filter { it > 0 }.minOrNull() ?: return List(columnCount) { 1.0 }
        return percentiles.map { p ->
            val ratio = if (p <= 0) MIN_WEIGHT else p.toDouble() / narrowest
            round2(min(MAX_WEIGHT, max(MIN_WEIGHT, ratio)))
        }
    }

    private fun percentile(sorted: List<Int>, fraction: Double): Int {
        if (sorted.isEmpty()) {
            return 0
        }
        val index = ((sorted.size - 1) * fraction).roundToInt()
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }

    private fun round2(value: Double): Double = (value * WEIGHT_PRECISION).roundToInt() / WEIGHT_PRECISION

    // --- Shared -----------------------------------------------------------------------------------

    private fun ordinalOf(
        node: SourceObject,
        fallback: Int,
        anomalies: MutableList<TableAnomalyDto>,
    ): Int {
        val ordinal = outlineOrdinal(node.objectNumber)
        if (ordinal != null) {
            return ordinal
        }
        anomalies += anomaly(
            TableAnomalyKind.MALFORMED_OBJECT_NUMBER,
            TableAnomalySeverity.ERROR,
            "This object's outline number cannot be read, so its position in the table is a guess: " +
                "it is drawn at position $fallback, where it arrived in document order.",
            node,
        )
        return fallback
    }

    /** The next free slot at or after [wanted], so a duplicate ordinal shifts rather than replaces. */
    private fun nextFree(wanted: Int, taken: Set<Int>): Int {
        var candidate = wanted
        while (candidate in taken) {
            candidate++
        }
        return candidate
    }

    private fun indexMismatch(node: SourceObject, axis: String, exported: Int, derived: Int): TableAnomalyDto =
        anomaly(
            TableAnomalyKind.INDEX_MISMATCH,
            TableAnomalySeverity.WARN,
            "The export puts this object in $axis $exported; its outline number puts it in " +
                "$axis $derived. The outline number decides.",
            node,
        )

    private fun anomaly(
        kind: TableAnomalyKind,
        severity: TableAnomalySeverity,
        message: String,
        node: SourceObject,
    ): TableAnomalyDto =
        TableAnomalyDto(
            kind = kind,
            severity = severity,
            message = message,
            ref = Ref.encode(node.itemId),
            id = node.doorsId,
            objectNumber = node.objectNumber,
        )
}
