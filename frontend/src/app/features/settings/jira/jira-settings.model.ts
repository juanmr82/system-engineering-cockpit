/**
 * The wire types of the JIRA settings page (`api/dto/JiraDtos.kt`, spec §13.5).
 *
 * **No token appears in any of these, in any form.** The credential lives in `application.yaml` and
 * is never editable from, or readable by, the browser — [JiraHealth] is how the page reports that
 * it works, which is the only thing about it a user needs.
 */

export interface JiraHealth {
  /** Whether this deployment has a host and a token at all. False is a normal state, not an error. */
  readonly configured: boolean;
  readonly reachable: boolean;
  /** The JIRA account the token belongs to, resolved by the server on a successful test. */
  readonly user?: string;
  /** One sentence, already written for a person to read (`humanReason` on the server). */
  readonly message: string;
  readonly host: string;
}

/**
 * One project the configured token can currently see (ADR 0018) — a read-only diagnostic, fetched
 * live and never stored. There is no configured subset any more: the importer brings in all of them.
 */
export interface JiraProject {
  readonly key: string;
  readonly name: string;
}
