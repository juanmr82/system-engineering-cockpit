package com.sec.api.dto

import kotlinx.serialization.Serializable

// Wire shapes for docs/DOORS_TABLES.md §4.2. `ref` is always the base64url encoding of __id (R5) —
// never __id itself, and no __-prefixed name appears in a field name or a value.
//
// Two deliberate departures from the shapes written in that document, both to keep R5 true:
//
//  - **No `sortKey` anywhere.** The spec carries `__sortKey` on the view and on each extra band so
//    the client can position them. `__sortKey` is "never shown; silently drives the tree" in the
//    alias map, and shipping it would put an internal ordering key in a payload a browser can read
//    — so the server does the positioning instead and a band carries [DoorsTableBandDto.after].
//  - **`ref` / `id` rather than `itemId` / `doorsId`.** The same pair every other DTO in this
//    application speaks: `ref` is the opaque handle, `id` is DOORS's own module-local identifier,
//    display only and never a key (R6).
//
// **§6.3 is not implemented, and not by omission.** That section carries an attribute value that
// happens to sit on a cell or a row object out into the display column beside the table. A table
// shows its cells' `Object Text` and nothing else — decided against the reference module, where all
// 205 cells of one table carried the same value for the same attribute, 247 times over. There is
// therefore no `outerColumnValues` on a row and no `attrs` parameter on either endpoint.

/**
 * What a table's geometry turned out not to be. Carried in the payload rather than logged, because
 * a systems engineer reading a reconstructed table needs to know it may not match DOORS (§7).
 *
 * This is a **state channel**, like `labels` on an item: the client switches on [kind] and shows
 * [TableAnomalyDto.message], never the enum name itself.
 *
 * `DUPLICATE_ROW_ORDINAL` extends §7's table, which lists the cell case only: silently dropping a
 * row whose ordinal is already taken would break "never throw away a cell because of an anomaly".
 */
@Serializable
public enum class TableAnomalyKind {
    MISSING_CELL,
    NON_RECTANGULAR,
    DUPLICATE_COLUMN_ORDINAL,
    DUPLICATE_ROW_ORDINAL,
    INDEX_MISMATCH,
    SORTKEY_ORDINAL_DISAGREEMENT,
    UNEXPECTED_TABLE_CHILD,
    NESTED_TABLE,
    ORPHAN_TABLE_MEMBER,
    EMPTY_TABLE,
    MALFORMED_OBJECT_NUMBER,
}

@Serializable
public enum class TableAnomalySeverity { INFO, WARN, ERROR }

/**
 * One finding, already worded (§7).
 *
 * `message` is the sentence the UI shows. It is composed server-side for the same reason
 * `ReviewRowDto.issues` is: the client must never assemble language of its own from a code.
 */
@Serializable
public data class TableAnomalyDto(
    public val kind: TableAnomalyKind,
    public val severity: TableAnomalySeverity,
    public val message: String,
    public val ref: String? = null,
    public val id: String? = null,
    public val objectNumber: String? = null,
)

/**
 * One visual cell.
 *
 * `present` false is a structural gap — no object exists at this (row, column) — rather than a cell
 * whose text is empty. The matrix is dense so a renderer never has to think about holes (§3.3).
 *
 * `text` is `Object Text` verbatim and may legitimately be `""`: from DOORS that means "the
 * attribute exists and is empty". It never falls back to `__name`, which for a cell is derived and
 * would print a DOORS id into a table cell (§3.5).
 */
@Serializable
public data class DoorsTableCellDto(
    public val columnNumber: Int,
    public val present: Boolean = true,
    public val ref: String? = null,
    public val id: String? = null,
    public val text: String = "",
)

/**
 * One row band. `ref` is null and `present` false for a row ordinal no object claims — the matrix
 * is dense in both directions, not only across.
 *
 * `cells` is dense: `size == columnCount`, index 0 is column 1.
 */
@Serializable
public data class DoorsTableRowDto(
    public val rowNumber: Int,
    public val isHeader: Boolean = false,
    public val present: Boolean = true,
    public val ref: String? = null,
    public val id: String? = null,
    public val cells: List<DoorsTableCellDto> = emptyList(),
)

/**
 * A child of the table that is not a row, or a child of a row that is not a cell (§3.6) — a
 * caption object, an orphan left by a deleted cell, or a nested table. Rendered as a full-width
 * band in its document-order position rather than dropped.
 *
 * `after` is the row number it follows, 0 when it precedes every row. It replaces the spec's
 * `sortKey`: the position is what the client needs, and `__sortKey` is not something the client
 * may be handed (R5).
 */
@Serializable
public data class DoorsTableBandDto(
    public val ref: String,
    public val after: Int,
    public val id: String? = null,
    public val text: String = "",
)

/**
 * One reconstructed DOORS table, ready to render.
 *
 * `headerRowCount` is data rather than a constant in the template so that "this table has no
 * header" becomes a data change, not a component change (§3.4).
 *
 * `columnWeights` are relative track weights for fluid column widths, `size == columnCount`. DOORS
 * stores per-column widths on the table object but the DXL exporter does not emit them, so these
 * are derived from the content and never claim to be the author's widths (§6.6).
 */
@Serializable
public data class DoorsTableViewDto(
    public val ref: String,
    public val objectNumber: String,
    public val rowCount: Int,
    public val columnCount: Int,
    public val id: String? = null,
    public val headerRowCount: Int = 1,
    public val columnWeights: List<Double> = emptyList(),
    public val rows: List<DoorsTableRowDto> = emptyList(),
    public val extraBands: List<DoorsTableBandDto> = emptyList(),
    public val anomalies: List<TableAnomalyDto> = emptyList(),
)

@Serializable
public data class ModuleTablesResponseDto(
    public val tables: List<DoorsTableViewDto> = emptyList(),
)
