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
}

// Label strings are a state channel, never display text (CLAUDE.md §5). They are read here and
// mapped to behaviour; nothing below reaches a template.
const HEADING_LABEL = 'DOORSHeading';
const TABLE_LABELS = ['DOORSTable', 'DOORSTableRow', 'DOORSTableCell'];

export function isHeading(row: ReviewRow): boolean {
  return row.labels.includes(HEADING_LABEL);
}

/**
 * Table structure — the cells, rows and wrappers DOORS emits for an embedded table.
 *
 * Hidden from the review table for now (REQ_REVIEW.md §5). They are 273 of Segment's 903 objects
 * and each one carries a fragment of a table that only means anything laid out as a table, so in a
 * flat list they are noise between the requirements. They are still imported, still in the graph
 * and still reachable — this is a view filter, not a data decision.
 */
export function isTableElement(row: ReviewRow): boolean {
  return row.labels.some((label) => TABLE_LABELS.includes(label));
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
 * One direction of the References cell, split at load.
 *
 * Unresolved targets are counted rather than listed. Each one would otherwise render the same
 * sentence — "Not yet imported", with no id to tell them apart, because a placeholder has none —
 * and against the reference module that is three identical phrases in a 46px row, clipped. The
 * count says the same thing in the space available, and the tooltip names the modules to import.
 */
export interface RefGroup {
  readonly resolved: Reference[];
  readonly unresolvedCount: number;
  readonly unresolvedTooltip: string;
}

export function refGroup(references: Reference[]): RefGroup {
  const resolved = references.filter((reference) => reference.resolved);
  const unresolved = references.filter((reference) => !reference.resolved);
  const modules = [
    ...new Set(unresolved.map((reference) => reference.moduleName).filter((name) => !!name)),
  ];

  return {
    resolved,
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
 * Narrow on purpose. A renderer can open the detail panel and read or write one object's comment;
 * it cannot reach the module selection, the search or the save. Handing the component itself to
 * ag-grid would work and would make every one of those reachable from a cell.
 */
export interface ReviewCellContext {
  readonly openDetail: (ref: string) => void;
  readonly commentText: (row: ReviewRow) => string;
  readonly isDirty: (row: ReviewRow) => boolean;
  readonly editComment: (row: ReviewRow, text: string) => void;
}
