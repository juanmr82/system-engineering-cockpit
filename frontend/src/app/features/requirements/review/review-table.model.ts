import type { DoorsTableView } from '../../../shared/doors-table/doors-table.model';
import type { Reference, ReviewRow } from './review.model';

// The row shape the grid actually renders, and the small amount of work done once at load rather
// than per keystroke or per scroll. Lives in its own file because the cell renderers need it too.

/**
 * A row plus everything the view derives from it once, at load: the search haystack and the
 * visible attribute values in column order.
 *
 * `cells` is positional on purpose. A DOORS attribute name contains spaces, dots, slashes and
 * umlauts, so it is a display label and never a key (CLAUDE.md §11) — the grid addresses an
 * attribute by its index, and the name is carried only in `headerName`.
 */
export interface TableRow {
  readonly row: ReviewRow;
  readonly cells: string[];
  readonly searchText: string;
  readonly outgoing: RefGroup;
  readonly incoming: RefGroup;
  /** What the Description column shows — see {@link describe}. */
  readonly description: string;
  /** Outline depth, 1-based, and 0 when this row is not a heading. Drives the heading styling. */
  readonly headingLevel: number;
  /**
   * Position in the order the server sent, which is `__sortKey` order — the zero-padded
   * segment-wise expansion of `objectNumber`, so `4.3.2` precedes `4.3.2-0` precedes `4.3.2-1`.
   *
   * This is what the Description column sorts on. Sorting the outline number *as a string* is
   * exactly the mistake `__sortKey` exists to prevent (CLAUDE.md §11), and re-deriving the numeric
   * comparison here would be a second implementation of it that could drift from the first.
   */
  readonly order: number;
  /**
   * The reconstructed table this row *is*, for a `DOORSTable` object — null for everything else.
   *
   * A table is drawn inside the Description column, which is exactly where DOORS draws it: in the
   * main text column, at its full width, with the surrounding columns continuing on either side
   * (`docs/DOORS_TABLES.md` §1). Its rows and cells are not rows of this table — they are inside
   * the drawing.
   *
   * Null is also what a *failed* tables request leaves behind, and the row then falls back to its
   * ordinary Description text rather than to a hole. A table that cannot be drawn must not take
   * the module's requirements down with it.
   */
  readonly table: DoorsTableView | null;
}

// Label strings are a state channel, never display text (CLAUDE.md §5). They are read here and
// mapped to behaviour; nothing below reaches a template.
const HEADING_LABEL = 'DOORSHeading';
const TABLE_LABEL = 'DOORSTable';
const TABLE_PART_LABELS = ['DOORSTableRow', 'DOORSTableCell'];

export function isHeading(row: ReviewRow): boolean {
  return row.labels.includes(HEADING_LABEL);
}

/** The container object of an embedded DOORS table — the row the table itself is drawn on. */
export function isTable(row: ReviewRow): boolean {
  return row.labels.includes(TABLE_LABEL);
}

/**
 * The rows and cells of an embedded table.
 *
 * These stay hidden from the flat list, and now for a sharper reason than before: each one is a
 * fragment that only means anything laid out as a table, and the table they belong to is drawn on
 * its container's row. Showing them as well would print every cell twice.
 *
 * Still a **view filter, not a data decision** — they are imported, in the graph, reachable, and
 * the "n in module" readout still counts them (REQ_REVIEW.md §5).
 */
export function isTablePart(row: ReviewRow): boolean {
  return row.labels.some((label) => TABLE_PART_LABELS.includes(label));
}

/**
 * The Description column's text (REQ_REVIEW.md §5).
 *
 * A heading reads as its outline number and its heading text, which is how it appears in DOORS and
 * in the Word export; everything else reads as its requirement statement. The `Object Text`
 * fallback to `__name` covers the objects that carry no `Object Text` key at all — 203 of SRD's
 * 977 — which would otherwise be blank rows. An `Object Text` that is present but `""` renders
 * empty, because from DOORS that means "the attribute exists and is empty" (CLAUDE.md §11).
 */
export function describe(row: ReviewRow): string {
  if (isHeading(row)) {
    return [row.objectNumber, renderValue(row.attributes['Object Heading'])]
      .filter(Boolean)
      .join(' ');
  }
  const text = row.attributes['Object Text'];
  return text === undefined ? row.name : renderValue(text);
}

/**
 * One direction of the References cell, split at load into three states rather than two.
 *
 * Never-imported targets are counted rather than listed. Each one would otherwise render the same
 * sentence — "Not yet imported", with no id to tell them apart, because a placeholder has none —
 * and against the reference module that is three identical phrases in a 46px row, clipped. The
 * count says the same thing in the space available, and the tooltip names the modules to import.
 *
 * Objects deleted in DOORS are **listed**, by id, and the difference is not cosmetic. They are
 * resolved targets: the object was really imported, and its id is the whole of what a reviewer
 * needs to go and find the link in DOORS and remove it. Counting them would replace the one
 * actionable thing about them with a number.
 *
 * They are also kept out of `resolved`, which is what the cell renders as a navigable control.
 * There is nothing to navigate to — the object is not in its module's table any more — so a
 * deleted target is text, not a button.
 */
export interface RefGroup {
  readonly resolved: Reference[];
  readonly deleted: Reference[];
  readonly deletedTooltip: string;
  readonly unresolvedCount: number;
  readonly unresolvedTooltip: string;
}

export function refGroup(references: Reference[]): RefGroup {
  const deleted = references.filter((reference) => reference.deletedInSource);
  const resolved = references.filter(
    (reference) => reference.resolved && !reference.deletedInSource,
  );
  const unresolved = references.filter(
    (reference) => !reference.resolved && !reference.deletedInSource,
  );
  const modules = [
    ...new Set(unresolved.map((reference) => reference.moduleName).filter((name) => !!name)),
  ];

  return {
    resolved,
    deleted,
    deletedTooltip:
      deleted.length === 1
        ? 'This object was deleted in DOORS and the link to it was left behind. The link has to be removed in DOORS.'
        : 'These objects were deleted in DOORS and the links to them were left behind. The links have to be removed in DOORS.',
    unresolvedCount: unresolved.length,
    unresolvedTooltip: modules.length
      ? `Not yet imported. Import ${modules.join(', ')} to see ${unresolved.length === 1 ? 'it' : 'them'}.`
      : 'Not yet imported, and neither is the module these objects belong to.',
  };
}

export function renderValue(value: unknown): string {
  // "" from DOORS means the attribute exists and is empty, which is not the same as absent: it
  // renders as an empty cell, never as a fallback (CLAUDE.md §11).
  return value === null || value === undefined ? '' : String(value);
}

/**
 * What a cell renderer is allowed to ask the view for, passed as the grid's `context`.
 *
 * Narrow on purpose. A renderer can open the detail panel or a row's thread; it cannot reach the
 * module selection or the search. Handing the component itself to ag-grid would work and would
 * make every one of those reachable from a cell.
 */
export interface ReviewCellContext {
  readonly openDetail: (ref: string) => void;
  readonly openThread: (row: ReviewRow) => void;
}
