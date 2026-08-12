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

/** One project this JIRA offers. Fetched live and never stored — the *keys* are what we keep. */
export interface JiraProject {
  readonly key: string;
  readonly name: string;
}

/**
 * The configured projects, and the query they produce.
 *
 * The JQL preview is derived by the server on every read and is the single best debugging aid in
 * the feature: it is exactly what the next import will send.
 */
export interface JiraProjectSettings {
  readonly projectKeys: readonly string[];
  readonly jql: string | null;
}
