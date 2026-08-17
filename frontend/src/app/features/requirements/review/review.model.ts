// Wire shapes for docs/REQ_REVIEW.md §8. `ref` is always the opaque route handle (R5) — never a
// raw internal id. The module selector, the attribute list and the settings request are the
// Modules feature's types, imported rather than copied: both dialogs write the same stored values.

// One linked object in the References column (§5.1). `id` is null when the target has not been
// imported: a placeholder has no DOORS id, so there is nothing the column can honestly show
// beyond the wording and the owning module's name.
//
// `deletedInSource` is not another way to be unresolved. The target is a real imported object with
// a real `id`, and `resolved` is true — what it says is that a later export of the target's own
// module no longer contained it. DOORS deleted the object and left this link behind.
//
// The two prompt opposite actions, which is why they are separate fields. Unresolved asks for a
// module to be imported. Deleted asks for a link to be removed, in DOORS, where the only copy of
// it lives.
export interface Reference {
  readonly ref: string;
  readonly id: string | null;
  readonly resolved: boolean;
  readonly deletedInSource: boolean;
  readonly moduleRef: string | null;
  readonly moduleName: string | null;
}

// `incomingComplete` says whether an empty incoming list means anything. It is true now that the
// importer reads `__inputLinks` — a module's own export states every link pointing at it — so an
// empty list really does mean nothing refers to this object, and a referencing module that has not
// been imported shows up as an unresolved reference rather than as silence. Still carried per
// response rather than assumed: a future source that cannot report its inbound links would say so
// here.
export interface References {
  readonly outgoing: Reference[];
  readonly incoming: Reference[];
  readonly incomingComplete: boolean;
}

// A thread's own summary, carried on the row so the grid can draw its indicator without loading
// every message for every row (docs/req-review-comment-threads.md §4). The full thread — every
// note, its author, its text — is a second request, made when a reviewer opens the panel.
export interface ThreadSummary {
  readonly rootRef: string;
  readonly count: number;
  readonly resolved: boolean;
  readonly lastActivityAt: string | null;
  /** Up to 3 distinct authors, display-name resolved — who is in the thread, for the Comment
   *  column's compact chip, without loading it. */
  readonly participants: readonly string[];
}

// One message in a thread, root or reply. `authorName` is resolved server-side from the `:User`
// cache — a raw Keycloak `sub` never reaches the client (R5).
export interface ThreadNote {
  readonly ref: string;
  readonly text: string;
  /** The root's `:ref`; null for the root itself. */
  readonly replyTo: string | null;
  /** Root only; always null on a reply. */
  readonly resolved: boolean | null;
  readonly authorName: string;
  readonly createdAt: string;
  readonly updatedAt: string;
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
  readonly thread: ThreadSummary | null;
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

export interface AnnotationsResponse {
  readonly notes: ThreadNote[];
}
