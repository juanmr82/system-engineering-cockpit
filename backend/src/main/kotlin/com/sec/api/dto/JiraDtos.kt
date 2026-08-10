package com.sec.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire types for the JIRA views.
 *
 * Two R5 rules shape every one of them. **No `__` name crosses this boundary** — an issue is
 * identified by an opaque [JiraIssueRowDto.ref] and never by `__id`, and the property map a row
 * carries has already had the namespace filtered out of it by the discovery query. And **no label
 * is stored**: a column's wording is resolved from the JIRA field catalogue on every read, so a
 * field renamed in JIRA renames its column on the next import rather than needing a migration.
 */

/** What the settings view needs to know before it offers to import anything. */
@Serializable
public data class JiraConnectionDto(
    /** False when the host or the token is missing. Every other endpoint answers 503 until it is
     *  true, and the view says so rather than offering a button that cannot work. */
    public val configured: Boolean,
    /** Shown to the admin so they can see *which* JIRA this is pointed at. Empty when unset. */
    public val host: String,
    public val platform: String,
)

@Serializable
public data class JiraProjectRowDto(
    /** Base64url of `__id` (R5). What the scope endpoints take. */
    public val ref: String,
    public val key: String,
    public val name: String,
    public val projectType: String,
    /** True when this project has an import-scope node at all. */
    public val inScope: Boolean,
    /** In scope but switched off: kept, with its JQL, and not fetched. */
    public val enabled: Boolean,
    /** The admin's extra JQL clause for this project. Empty when there is none. */
    public val jql: String,
    /** Issues currently in the graph for it. Placeholders excluded — they are not its issues. */
    public val issueCount: Int,
)

@Serializable
public data class JiraProjectListDto(
    public val projects: List<JiraProjectRowDto>,
    /** Projects JIRA offers that are not in the graph yet. Only present when JIRA was reachable. */
    public val available: List<JiraAvailableProjectDto> = emptyList(),
)

@Serializable
public data class JiraAvailableProjectDto(
    public val key: String,
    public val name: String,
)

/** One column of the Issues table: what to read, and what to call it. */
@Serializable
public data class JiraColumnDto(
    /** The flattened field path — `status.name`. A column id, never a display string. */
    public val path: String,
    /** From the field catalogue, falling back to the path when the catalogue has never been
     *  fetched. Never stored on the setting node (R5). */
    public val label: String,
    /** `key` and `issuetype.name`: always first, never removable (design doc §6.3). */
    public val fixed: Boolean = false,
)

@Serializable
public data class JiraIssueRowDto(
    public val ref: String,
    public val key: String,
    public val issueType: String,
    /**
     * The selected columns' values for this row, keyed by path.
     *
     * A path missing from the map renders blank rather than erroring — JIRA schemas change between
     * imports, and a column whose field a project does not define is an absence, not a fault
     * (design doc §6.4).
     */
    public val values: Map<String, JsonElement>,
)

@Serializable
public data class JiraIssuesDto(
    public val columns: List<JiraColumnDto>,
    public val rows: List<JiraIssueRowDto>,
    public val total: Int,
    public val offset: Int,
    public val limit: Int,
)

/**
 * One node of the field-selection tree (design doc §6.2).
 *
 * A scalar field is a leaf and is selectable itself. A structured field is a parent whose sub-keys
 * are the leaves, and the parent is selectable only when the issues actually carry a scalar at
 * that path — `status` alone is an object and there is nothing to put in a cell.
 */
@Serializable
public data class JiraFieldNodeDto(
    public val path: String,
    public val label: String,
    /** The declared schema type from the catalogue, for the dialog to show. Empty when unknown. */
    public val type: String,
    /** A real value from a real issue. The catalogue states a type; this shows what it looks like. */
    public val sample: String,
    public val selectable: Boolean,
    /**
     * At least one imported issue carries a value for this field.
     *
     * False means JIRA defines the field and every imported issue leaves it empty — which is *not*
     * the same as the field not existing, and is why the tree is built from the catalogue as well
     * as from the data. A scalar field in this state is still selectable, because its schema states
     * the path exactly; an object one is not, because its sub-keys arrive with the first value.
     */
    public val hasValues: Boolean,
    public val selected: Boolean,
    public val fixed: Boolean,
    public val children: List<JiraFieldNodeDto> = emptyList(),
)

@Serializable
public data class JiraFieldTreeDto(
    public val fields: List<JiraFieldNodeDto>,
    /**
     * Columns the admin selected whose field no longer exists in JIRA.
     *
     * Surfaced rather than auto-removed: silently reshaping a saved view is worse than showing a
     * stale column, because the admin never finds out (design doc §6.4).
     */
    public val warnings: List<String> = emptyList(),
)

/** The absolute selected-column list, in order. Same shape as the review dialog's save (R7). */
@Serializable
public data class SaveJiraColumnsRequestDto(
    public val paths: List<String>,
)

/** Adding or updating one project's import scope. */
@Serializable
public data class SaveJiraProjectScopeRequestDto(
    public val key: String,
    public val enabled: Boolean = true,
    public val jql: String = "",
)

/**
 * What one import run did — the dialog the user gets back when it finishes (design doc §5 step 7).
 *
 * Every number is a count the server actually reported, not a length of something sent: created
 * against updated comes from Neo4j's own write counters, because a `MERGE` cannot tell you which
 * it did and a read-before-write would both cost a round trip and race.
 */
@Serializable
public data class JiraImportReportDto(
    public val startedAt: String,
    public val durationMs: Long,
    public val projects: List<String>,
    public val issuesSeen: Int,
    public val issuesCreated: Int,
    public val issuesUpdated: Int,
    public val issuesDeleted: Int,
    public val issueTypes: Int,
    public val fieldsInCatalog: Int,
    public val fieldsAdded: List<String>,
    public val fieldsRemoved: List<String>,
    public val linksCreated: Int,
    public val linksPruned: Int,
    public val hierarchyPruned: Int,
    /** Issues linked to but outside the import scope. They render as *Not yet imported*. */
    public val placeholdersCreated: Int,
    public val placeholdersCollected: Int,
    public val warnings: List<String>,
)
