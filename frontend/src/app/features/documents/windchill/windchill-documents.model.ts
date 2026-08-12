/** One Windchill document, exactly as `GET /api/v1/windchill/documents` sends it. */
export interface WindchillDocumentRow {
  /** Base64url over the internal id — the row key, and never the id itself (R5). */
  readonly ref: string;
  readonly folderLocation: string;
  readonly name: string;
  /** Shared by every version of one document. This is what the table groups on. */
  readonly number: string;
  readonly version: string;
  readonly state: string;
  /** Windchill's info page, derived by the server. Absent when no host is configured. */
  readonly browseUrl?: string | null;
}

/** The whole set, in one response — see `WindchillProjection` on why it is not paged. */
export interface WindchillDocuments {
  readonly rows: readonly WindchillDocumentRow[];
  readonly total: number;
  /** The server's row cap was reached. A cap hit silently is a table that is quietly wrong. */
  readonly truncated: boolean;
  /** Whether a Windchill host is configured — which is what decides if a row can link out. */
  readonly hostConfigured: boolean;
}

/**
 * A row of the grid, which is **not** the same thing as a document.
 *
 * ag-grid Community has no row grouping — that is an Enterprise feature — so the group headers are
 * ordinary rows the view synthesises and puts in the row array itself. A discriminated union is
 * what keeps that honest: every renderer and every value getter has to say which kind it is looking
 * at, and a group can never be mistaken for a document with missing fields.
 */
export type WindchillGridRow = WindchillGroupRow | WindchillDocumentGridRow;

/**
 * The header over the versions of one document.
 *
 * It carries the three fields every version of a document shares and **no version and no state**,
 * because those are the two things that differ between them — a header showing one version's state
 * would be a header claiming to speak for rows it disagrees with.
 */
export interface WindchillGroupRow {
  readonly kind: 'group';
  /**
   * Row identity for ag-grid. Prefixed, so a group and a document can never collide.
   *
   * Deliberately **stable across a toggle**. Folding the expanded state into it does make ag-grid
   * rebuild the header — that was the first attempt — and it also makes a row's identity change
   * while the row stays the same thing, which broke re-expanding outright. The redraw is asked for
   * explicitly instead; see `WindchillDocuments`.
   */
  readonly key: string;
  readonly number: string;
  readonly folderLocation: string;
  readonly name: string;
  /** How many versions this document has in the **whole** set, not in the current search. */
  readonly versions: number;
}

/**
 * One document.
 *
 * [grouped] is what draws the indent: a document whose number is unique has no header above it and
 * is not indented under anything.
 */
export interface WindchillDocumentGridRow {
  readonly kind: 'document';
  readonly key: string;
  readonly document: WindchillDocumentRow;
  readonly grouped: boolean;
}

/** Which column the table is ordered by. `null` is the server's own order (`__sortKey`). */
export type WindchillSortField = keyof Pick<
  WindchillDocumentRow,
  'folderLocation' | 'name' | 'number' | 'version' | 'state'
>;

/**
 * What the group cell needs from the view, handed to ag-grid through `gridOptions.context`.
 *
 * A renderer is built by ag-grid at runtime and is not a child of the component, so there is no
 * input to bind and no output to listen to. `context` is ag-grid's own answer to that, and it is
 * used here rather than putting a closure on each row object — a row is data, and rebuilding every
 * row on every toggle to carry a function would make the row array churn for no reason.
 */
export interface WindchillGridContext {
  /** Opens or shuts the group for one document number. */
  readonly toggleGroup: (number: string) => void;

  /**
   * Whether that group is open — **read live, never carried in row data**.
   *
   * This is the whole reason the disclosure works. ag-grid does a delta update against `getRowId`
   * and refreshes a cell only when its *value getter's output* changed; a header's folder, name and
   * number read the same whether it is open or shut, so a header whose state travelled in its row
   * data is a header ag-grid never redraws. The versions below it come and go, because those are
   * whole rows, and the arrow goes on pointing the way it was first drawn.
   *
   * Reading through this function puts the component's own signal inside the renderer's template
   * instead, so Angular redraws the arrow for the same reason it redraws anything else. ag-grid is
   * not involved, and there is no `refreshCells` to remember.
   */
  readonly isExpanded: (number: string) => boolean;
}
