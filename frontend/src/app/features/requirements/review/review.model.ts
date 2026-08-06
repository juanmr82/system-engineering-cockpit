// Wire shapes for docs/REQ_REVIEW.md §8. `ref` is always the opaque route handle (R5) — never a
// raw internal id. The module selector, the attribute list and the settings request are the
// Modules feature's types, imported rather than copied: both dialogs write the same stored values.

// One linked object in the References column (§5.1). `id` is null when the target has not been
// imported: a placeholder has no DOORS id, so there is nothing the column can honestly show
// beyond the wording and the owning module's name.
export interface Reference {
  readonly ref: string;
  readonly id: string | null;
  readonly resolved: boolean;
  readonly moduleRef: string | null;
  readonly moduleName: string | null;
}

// `incomingComplete` is false by construction: importers ingest out-links only, so an incoming
// link exists only where the referencing module has itself been imported. An empty incoming list
// is never evidence that a requirement is unreferenced.
export interface References {
  readonly outgoing: Reference[];
  readonly incoming: Reference[];
  readonly incomingComplete: boolean;
}

// Exactly one comment per object — a value, never a thread (§5.2).
export interface ReviewComment {
  readonly metaId: string;
  readonly text: string;
  readonly updatedAt: string | null;
}

// `attributes` is the dynamic DOORS attribute bag: Record<string, unknown>, narrowed at the point
// of use. Attribute sets differ per module by design, so there is no per-module row type.
export interface ReviewRow {
  readonly ref: string;
  readonly id: string;
  readonly name: string;
  // DOORS's outline number, e.g. "4.3.2-1". Display data — it is the first half of a heading's
  // Description (§5). Never a sort key: it does not order correctly as a string, which is why rows
  // arrive in document order and the table keeps that order.
  readonly objectNumber: string;
  readonly type: string | null;
  readonly labels: string[];
  readonly level: number;
  readonly requirementLike: boolean;
  // Everything the consistency checks found wrong with this object, as the text to show (§5.3):
  // fixed rules that always run ("Object Type shall not be TBD") followed by the names of
  // mandatory attributes carrying no value. Computed on read by the server, never stored — the
  // mandatory half depends on user-editable configuration, so it is not a property of the
  // import (R2).
  readonly issues: string[];
  readonly attributes: Record<string, unknown>;
  readonly references: References;
  readonly comment: ReviewComment | null;
}

export interface ModuleObjectsResponse {
  readonly rows: ReviewRow[];
  readonly total: number;
  readonly truncated: boolean;
}

export interface ItemProperty {
  readonly label: string;
  readonly value: string;
}

export interface ItemDetail {
  readonly ref: string;
  /**
   * DOORS's own module-local identifier, which the panel leads with.
   *
   * Display only, never a key (R6), and null where there is none — a placeholder, or a module.
   * The panel needs it because `name` for a requirement is its `Object Text`, and on a sanitised
   * export that is the same sentence for every object.
   */
  readonly id: string | null;
  readonly name: string;
  readonly type: string | null;
  readonly labels: string[];
  readonly moduleRef: string | null;
  readonly moduleName: string | null;
  readonly properties: ItemProperty[];
  readonly attributes: Record<string, unknown>;
}

// An empty `text` means delete: the reviewer cleared the box, so the node goes rather than being
// stored as "" (§5.2).
export interface CommentEdit {
  readonly ref: string;
  readonly text: string;
}

export interface SaveCommentsRequest {
  readonly comments: CommentEdit[];
}

export interface SavedComment {
  readonly ref: string;
  readonly comment: ReviewComment | null;
}

export interface SaveCommentsResponse {
  readonly saved: SavedComment[];
}
