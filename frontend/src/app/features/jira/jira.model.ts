// Wire types for the JIRA views, mirroring backend api/dto/JiraDtos.kt.
//
// Two things are deliberately absent, and both are R5: there is no `__id` — an issue and a project
// are addressed by their opaque `ref` — and no column carries a stored label. A column's wording
// arrives from the server, resolved from the JIRA field catalogue on every read, so a field
// renamed in JIRA renames its column here without a migration and without a second alias map.

export interface JiraConnection {
  readonly configured: boolean;
  readonly host: string;
  readonly platform: string;
}

export interface JiraProjectRow {
  readonly ref: string;
  readonly key: string;
  readonly name: string;
  readonly projectType: string;
  readonly inScope: boolean;
  readonly enabled: boolean;
  readonly jql: string;
  readonly issueCount: number;
}

export interface JiraAvailableProject {
  readonly key: string;
  readonly name: string;
}

export interface JiraProjectList {
  readonly projects: JiraProjectRow[];
  readonly available: JiraAvailableProject[];
}

export interface JiraColumn {
  /** The flattened field path — `status.name`. A column id, never shown to a user. */
  readonly path: string;
  readonly label: string;
  /** Key and Issue type: always first, never removable. */
  readonly fixed: boolean;
}

/**
 * One row.
 *
 * `values` is keyed by column path and every value is `unknown`: a JIRA field can be a string, a
 * number, a boolean or a list of any of those, and narrowing happens at the point of use — the
 * same rule the DOORS attribute bag follows (CLAUDE.md §11).
 */
export interface JiraIssueRow {
  readonly ref: string;
  readonly key: string;
  readonly issueType: string;
  readonly values: Record<string, unknown>;
}

export interface JiraIssues {
  readonly columns: JiraColumn[];
  readonly rows: JiraIssueRow[];
  readonly total: number;
  readonly offset: number;
  readonly limit: number;
}

export interface JiraFieldNode {
  readonly path: string;
  readonly label: string;
  readonly type: string;
  readonly sample: string;
  readonly selectable: boolean;
  /**
   * At least one imported issue carries a value.
   *
   * False means JIRA defines the field and every imported issue leaves it empty — a real state,
   * not an absence, and the reason the list is built from JIRA's field catalogue as well as from
   * the data. A field like that is still worth showing: somebody about to start filling it in
   * wants its column ready.
   */
  readonly hasValues: boolean;
  readonly selected: boolean;
  readonly fixed: boolean;
  readonly children: JiraFieldNode[];
}

export interface JiraFieldTree {
  readonly fields: JiraFieldNode[];
  readonly warnings: string[];
}

export interface SaveJiraColumnsRequest {
  readonly paths: string[];
}

export interface SaveJiraProjectScopeRequest {
  readonly key: string;
  readonly enabled: boolean;
  readonly jql: string;
}

/** What one import run did. Every field is rendered by the report dialog. */
export interface JiraImportReport {
  readonly startedAt: string;
  readonly durationMs: number;
  readonly projects: string[];
  readonly issuesSeen: number;
  readonly issuesCreated: number;
  readonly issuesUpdated: number;
  readonly issuesDeleted: number;
  readonly issueTypes: number;
  readonly fieldsInCatalog: number;
  readonly fieldsAdded: string[];
  readonly fieldsRemoved: string[];
  readonly linksCreated: number;
  readonly linksPruned: number;
  readonly hierarchyPruned: number;
  readonly placeholdersCreated: number;
  readonly placeholdersCollected: number;
  readonly warnings: string[];
}
