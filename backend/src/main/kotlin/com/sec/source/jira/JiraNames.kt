package com.sec.source.jira

/**
 * Every JIRA-specific name the backend uses, in one place.
 *
 * The same arrangement `source/doors/DoorsNames.kt` describes, for the second source: nothing
 * JIRA-specific exists outside this package and the JIRA-specific API routes (CLAUDE.md §1), and
 * adding this source touched no name belonging to DOORS. The source-agnostic half — the `__`
 * namespace, `:SEItem`, `__child`, Tier 2 — is read from `domain/GraphNames.kt` exactly as the
 * DOORS code reads it, so `__id` still has one spelling in this backend.
 *
 * ## Which of these are interpolated, and why it is the same line DOORS draws
 *
 *  - **[JiraFieldId] is JIRA's vocabulary.** A JIRA administrator renames a custom field without
 *    this application changing a line — the importer copies fields verbatim, and every field a
 *    project carries beyond the handful named here is discovered at runtime and never appears in
 *    code. So a rename must be cheap here: these are interpolated into the Cypher.
 *  - **[JiraLabel], [JiraProp] and [JiraRel] are ours.** The importer derives them, and renaming
 *    one means a re-import. They are interpolated too — ADR 0010's amendment settled that the cost
 *    of *finding* a name is what matters — and `GraphNamesTest` fails the build on any that is not
 *    declared here.
 */

/**
 * Field ids the backend has to reason about structurally.
 *
 * Everything else in an issue's `fields` block is flattened by [JiraFields] and discovered by the
 * same runtime query the DOORS review table uses. A JIRA field id is a safe identifier
 * (`customfield_10032`), unlike a DOORS attribute name — but a *flattened path* is not, because it
 * carries dots, so the ag-grid rule that a column is `colId` + `valueGetter` and never `field`
 * applies here for the same reason it applies there.
 */
public object JiraFieldId {
    /** The issue key, `PROJ-42`. Top-level on the issue, not inside `fields`. Module-local to a
     *  JIRA instance, so it is never the key (R6) — `__id` is. It is shown, as the Key column. */
    public const val KEY: String = "key"

    /** JIRA's numeric id, top-level. Stable across a project move, unlike [KEY]. */
    public const val ID: String = "id"

    /** The issue's own REST URL, top-level. Kept because it is the only thing that names the
     *  instance an issue came from; never shown, because it is a URL to a system the reader may
     *  not be able to reach. */
    public const val SELF: String = "self"

    /** The one-line statement. Becomes `__name` (R5 shows that as **Summary** for an issue). */
    public const val SUMMARY: String = "summary"

    public const val ISSUE_TYPE: String = "issuetype"
    public const val STATUS: String = "status"
    public const val PROJECT: String = "project"
    public const val PARENT: String = "parent"

    /** Sub-key of every structured field JIRA returns. The half of `status` a reader wants. */
    public const val NAME: String = "name"

    /** `fields.issuelinks[]` — modelled as relationships, so excluded from flattening. */
    public const val ISSUE_LINKS: String = "issuelinks"

    /** `fields.subtasks[]` — containment, so it becomes `__child` and is excluded from flattening. */
    public const val SUBTASKS: String = "subtasks"

    /**
     * Fields that are structure rather than values, and are never flattened into properties.
     *
     * The first two become relationships. The rest are unbounded append-only collections — a
     * ten-year-old issue's `comment` block is larger than every other field put together — and
     * nothing in this application reads them, so flattening them would buy a slow import and a
     * bloated store in exchange for columns nobody asked for.
     */
    public val structural: Set<String> = setOf(
        ISSUE_LINKS, SUBTASKS, "comment", "worklog", "attachment", "changelog",
    )

    /**
     * The two non-removable leading columns of the Issues table (design doc §6.3).
     *
     * Modelled as always-selected rather than special-cased in the renderer, so the table's
     * column logic stays one loop over the selected list.
     */
    public val fixedColumns: List<String> = listOf(KEY, "$ISSUE_TYPE.$NAME")
}

/**
 * `:JiraProject` and `:JiraIssueType` property names the backend reads by name. Source data, and
 * therefore displayed — the wording is in `domain/Aliases.kt`.
 */
public object JiraProjectAttr {
    public const val KEY: String = "key"
    public const val ID: String = "id"
    public const val NAME: String = "name"
    public const val PROJECT_TYPE_KEY: String = "projectTypeKey"

    /** `:JiraIssueType` — whether this type is a sub-task type. */
    public const val SUBTASK: String = "subtask"
    public const val DESCRIPTION: String = "description"

    /** `:JiraField` — the catalog entry's declared type, from `GET /rest/api/2/field`. */
    public const val SCHEMA_TYPE: String = "schemaType"

    /** `:JiraField` — set when the field is a custom field, naming its provider. */
    public const val SCHEMA_CUSTOM: String = "schemaCustom"

    /** `:JiraField` — the element type of an array field. */
    public const val SCHEMA_ITEMS: String = "schemaItems"

    /** `:JiraField` — false for a custom field, true for a system one. */
    public const val NAVIGABLE: String = "navigable"
}

/**
 * Tier-1 properties the JIRA importer derives that no other source has.
 *
 * The shared ones — `__id`, `__name`, `__version`, `__sortKey`, `__importedAt` — are not repeated
 * here, for the reason `DoorsProp` gives: JIRA code reads them from `com.sec.domain.Prop`, so a
 * second spelling of `__id` never comes into existence.
 */
public object JiraProp {
    /**
     * The project an issue belongs to, denormalised onto the issue.
     *
     * This is what scopes reconciliation, and it is a derived copy of `fields.project.key` rather
     * than a walk up `__child` on purpose: the run that deletes stale issues has to find them by
     * an indexed predicate, and an issue whose `__child` edge was already pruned would otherwise
     * be unreachable from its project at exactly the moment it needs finding.
     */
    public const val PROJECT_KEY: String = "__projectKey"

    /**
     * The whole `fields` block as JIRA returned it, serialised.
     *
     * The flattened properties beside it are a projection *of* this, not a replacement for it: a
     * JIRA field can be an arbitrarily nested object, and the flattener stops at scalars. Keeping
     * the block verbatim is what makes "the data is kept as imported" true for a source whose
     * schema no data class can describe (design doc §3).
     *
     * `__`-prefixed, so R5 keeps it off every screen and the runtime attribute-discovery query
     * filters it out of the column list without needing to know it exists. Switched off with
     * `jira.storeRawFields: false` where the store matters more than the fidelity.
     */
    public const val RAW_FIELDS: String = "__rawFields"
}

/**
 * Relationship types.
 *
 * Un-prefixed, because JIRA asserts both of them — the same test `refersTo` passes. Containment is
 * **not** here: a project contains its issues and an issue contains its sub-tasks, and both become
 * `__child` (R3), so one tree component walks DOORS modules and JIRA projects without knowing
 * which it is looking at.
 */
public object JiraRel {
    /**
     * `fields.issuelinks[]`, drawn from the issue holding the `outwardIssue` reference to it, so
     * the direction matches JIRA's own outward/inward semantics.
     *
     * **One type, not one per link type.** JIRA link types are administrator-defined, so
     * `:BLOCKS` and `:RELATES_TO` would be graph names invented from source data at runtime —
     * undeclarable by ADR 0010, unsearchable, and unrenamable. The link type travels as
     * properties instead, both phrases included, so a reader can be told *PROJ-1 blocks PROJ-2*
     * from either end without a second lookup.
     */
    public const val ISSUE_LINK: String = "issueLink"

    /** `fields.issuetype` — an assertion JIRA makes about the issue, so it keeps a plain name. */
    public const val HAS_TYPE: String = "hasType"
}

/** Properties on a [JiraRel.ISSUE_LINK] relationship. Source data. */
public object JiraLinkProp {
    public const val TYPE_ID: String = "linkTypeId"
    public const val TYPE_NAME: String = "linkTypeName"
    public const val INWARD: String = "inward"
    public const val OUTWARD: String = "outward"
}

/**
 * Node labels the JIRA importer writes.
 *
 * Every one of them also carries `:SEItem` (R6), including the catalogue nodes: a label that
 * skipped it would need its own uniqueness constraint, its own identity rule and its own answer to
 * "what is this thing's name", which is three copies of what `:SEItem` already settles.
 *
 * These cross the wire as `labels: string[]`, as a state channel and never as display text.
 */
public object JiraLabel {
    /**
     * The root of the JIRA branch of the knowledge tree, and the only node of its label.
     *
     * It exists for two jobs that would otherwise need separate machinery: it is what the projects
     * hang off by `__child`, so JIRA has one tree root rather than one per project, and it is the
     * anchor for the display-column configuration — which is global to the Issues table, and so
     * has no project to attach to (R2 Shape B needs a set-owner, and this is that set).
     */
    public const val SOURCE: String = "JiraSource"

    public const val PROJECT: String = "JiraProject"
    public const val ISSUE: String = "JiraIssue"
    public const val ISSUE_TYPE: String = "JiraIssueType"

    /** The field catalogue from `GET /rest/api/2/field` — id, name and declared schema. It is
     *  what lets the selection dialog say *Story Points* where the graph says
     *  `customfield_10032`. */
    public const val FIELD: String = "JiraField"

    /** Every label this importer writes. Used by the Cypher guard test. */
    public val all: Set<String> = setOf(SOURCE, PROJECT, ISSUE, ISSUE_TYPE, FIELD)
}

/** The one `__id` namespace for this source, so two sources can never collide (R6). */
public object JiraId {
    private const val SOURCE_KEY: String = "jira"

    /** The single [JiraLabel.SOURCE] node. */
    public const val SOURCE: String = "$SOURCE_KEY:source"

    public fun project(key: String): String = "$SOURCE_KEY:project:$key"
    public fun issue(key: String): String = "$SOURCE_KEY:issue:$key"
    public fun issueType(id: String): String = "$SOURCE_KEY:issuetype:$id"
    public fun field(id: String): String = "$SOURCE_KEY:field:$id"
}
