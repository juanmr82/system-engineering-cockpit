/**
 * The wire types of `GET /api/v1/jira/issues`, mirroring `api/dto/JiraDtos.kt`.
 *
 * Hand-written rather than generated, matching every other feature in this application until the
 * OpenAPI client exists (CLAUDE.md §5). The comments here say what a field *means*; the DTO says
 * what it is.
 */

/**
 * One configurable column: a field the user chose, in a position.
 *
 * The three fixed columns — type, key and the link out — are never in this list. They are the
 * client's own and the server deliberately does not describe them, so no client can hide one.
 */
export interface JiraColumn {
  readonly fieldId: string;
  readonly name: string;
  /** JIRA's declared type. Null on a stale column, which no longer has one. */
  readonly schemaType: string | null;
  /** False for an array or a shape with no single display value — the header shows no sort. */
  readonly sortable: boolean;
  /** The user chose this field and JIRA no longer has it. It still renders, empty. */
  readonly stale: boolean;
}

export interface JiraIssueRow {
  /** The opaque handle over the issue's identity. The row key, and never a raw id (R5). */
  readonly ref: string;
  readonly key: string;
  /** `<key>: <summary>`, or the key alone when a permission hid the summary. */
  readonly name: string;
  readonly issueTypeName: string | null;
  /**
   * The page a person opens.
   *
   * Not the issue's stored `self`, which is an API URL that answers with raw JSON. Null when the
   * server has no configured JIRA host, in which case there is nowhere to link to.
   */
  readonly browseUrl: string | null;
  /**
   * A stub standing in for an issue outside the configured projects.
   *
   * A state channel, not display text: this view renders the words (R5), the server never does.
   */
  readonly unresolved: boolean;
  /** The configured columns' values, keyed by field id. Empty until step 9 gives it entries. */
  readonly values: Readonly<Record<string, unknown>>;
}

export interface JiraIssuesPage {
  readonly page: number;
  readonly size: number;
  /** Issues matching the same filter — the paginator's denominator, not the number of rows. */
  readonly total: number;
  readonly columns: readonly JiraColumn[];
  readonly rows: readonly JiraIssueRow[];
}

/** What the table asks the server for. Every field is part of the request's identity. */
export interface JiraIssuesQuery {
  readonly page: number;
  readonly size: number;
  readonly sort: string;
  readonly dir: 'asc' | 'desc';
  readonly q: string;
}
