// Wire shapes for docs/DOORS_TABLES.md §4.2, mirroring backend api/dto/TableDtos.kt. `ref` is
// always the opaque route handle (R5) — never a raw internal id, and no internal name appears in a
// field name or a value.
//
// The geometry is assembled **server-side**: this is a rendering-ready view model, not a bag of
// nodes. Nothing here re-derives which column a cell is in — asking that question a second time in
// TypeScript is how the table and its own anomaly list would eventually disagree.
//
// **A table shows its cells' `Object Text` and nothing else.** §6.3's outer display columns — an
// attribute value that happens to sit on a cell or row object, carried out beside the table — are
// deliberately not implemented, so there is no `outerColumnValues` here and no `attrs` on the
// request.

/**
 * What a table's geometry turned out not to be (§7).
 *
 * A **state channel**, like an item's labels: the client switches on `kind` and shows `message`,
 * which the server already worded. It never composes language of its own from a code.
 */
export type TableAnomalyKind =
  | 'MISSING_CELL'
  | 'NON_RECTANGULAR'
  | 'DUPLICATE_COLUMN_ORDINAL'
  | 'DUPLICATE_ROW_ORDINAL'
  | 'INDEX_MISMATCH'
  | 'SORTKEY_ORDINAL_DISAGREEMENT'
  | 'UNEXPECTED_TABLE_CHILD'
  | 'NESTED_TABLE'
  | 'ORPHAN_TABLE_MEMBER'
  | 'EMPTY_TABLE'
  | 'MALFORMED_OBJECT_NUMBER';

export type TableAnomalySeverity = 'INFO' | 'WARN' | 'ERROR';

export interface TableAnomaly {
  readonly kind: TableAnomalyKind;
  readonly severity: TableAnomalySeverity;
  readonly message: string;
  readonly ref: string | null;
  readonly id: string | null;
  readonly objectNumber: string | null;
}

/**
 * One visual cell. `present` false is a structural gap — no object exists at this position — which
 * is not the same as a cell whose text is empty.
 *
 * `text` is `Object Text` verbatim and may legitimately be `''`: from DOORS that means "the
 * attribute exists and is empty". It is **plain text, never HTML** — rendered with interpolation,
 * never `[innerHTML]` (§3.5).
 */
export interface DoorsTableCell {
  readonly columnNumber: number;
  readonly present: boolean;
  readonly ref: string | null;
  readonly id: string | null;
  readonly text: string;
}

/** One row band. `cells` is dense: `length === columnCount`, index 0 is column 1. */
export interface DoorsTableRow {
  readonly rowNumber: number;
  readonly isHeader: boolean;
  readonly present: boolean;
  readonly ref: string | null;
  readonly id: string | null;
  readonly cells: DoorsTableCell[];
}

/**
 * A child of the table that is not a row, or a child of a row that is not a cell (§3.6) — a
 * caption, an orphan left by a deleted cell, or a nested table. Drawn as a full-width band in its
 * document-order position rather than dropped.
 *
 * `after` is the row number it follows, 0 when it precedes every row.
 */
export interface DoorsTableBand {
  readonly ref: string;
  readonly after: number;
  readonly id: string | null;
  readonly text: string;
}

/**
 * One reconstructed DOORS table, ready to render.
 *
 * `columnWeights` are relative track weights, `length === columnCount`. DOORS stores per-column
 * widths on the table object but the exporter does not emit them, so these are derived from the
 * content and never claim to be the author's widths (§6.6).
 */
export interface DoorsTableView {
  readonly ref: string;
  readonly objectNumber: string;
  readonly rowCount: number;
  readonly columnCount: number;
  readonly id: string | null;
  readonly headerRowCount: number;
  readonly columnWeights: number[];
  readonly rows: DoorsTableRow[];
  readonly extraBands: DoorsTableBand[];
  readonly anomalies: TableAnomaly[];
}

export interface ModuleTablesResponse {
  readonly tables: DoorsTableView[];
}

/**
 * Rows and extra bands interleaved into the single sequence the template walks.
 *
 * A discriminated union rather than two loops, because a band's whole point is that it sits
 * *between* rows: rendering the rows and then the bands would put a caption at the bottom of every
 * table it belongs in the middle of.
 */
export type DoorsTableEntry =
  | { readonly kind: 'row'; readonly key: string; readonly row: DoorsTableRow }
  | { readonly kind: 'band'; readonly key: string; readonly band: DoorsTableBand };

export function tableEntries(table: DoorsTableView): DoorsTableEntry[] {
  const entries: DoorsTableEntry[] = [];
  const bandsAfter = (rowNumber: number): void => {
    for (const band of table.extraBands.filter((b) => b.after === rowNumber)) {
      entries.push({ kind: 'band', key: `b${band.ref}`, band });
    }
  };

  bandsAfter(0);
  for (const row of table.rows) {
    entries.push({ kind: 'row', key: `r${row.rowNumber}`, row });
    bandsAfter(row.rowNumber);
  }
  return entries;
}

/**
 * The CSS track list, from the server's weights (§6.6).
 *
 * `minmax(0, Nfr)` on every track is half of what makes shrinking work; `min-inline-size: 0` on
 * every cell is the other half, and it lives in the stylesheet. Omitting either gives a table that
 * grows past its container and never shrinks back — the classic CSS Grid overflow trap.
 *
 * No pixel width is computed here, and none may be: the table's width follows its container and
 * the browser does the reflow. Measuring in TypeScript is what turns a free resize into a
 * measure/write cycle.
 */
export function trackList(table: DoorsTableView): string {
  const weights =
    table.columnWeights.length === table.columnCount && table.columnCount > 0
      ? table.columnWeights
      : Array.from({ length: table.columnCount }, () => 1);
  return weights.map((weight) => `minmax(0, ${weight}fr)`).join(' ');
}
