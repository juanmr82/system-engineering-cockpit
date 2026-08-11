package com.sec.source.jira

/**
 * Every name the backend uses to address JIRA data in the graph, in one place (ADR 0010).
 *
 * The DOORS equivalent is `source/doors/DoorsNames.kt`, and the rule that keeps sources
 * independent is that **a source's names file never edits another's** (R3). Source-agnostic names —
 * `__id`, `:SEItem`, `__child`, the whole `:__Meta` catalogue, `:__ImportRun` — stay in
 * `domain/GraphNames.kt` and are imported from there.
 *
 * ## A naming rule that is not style
 *
 * **No object, class or enum in this package may share its name with a value in [JiraLabel.all].**
 * `GraphNamesTest`'s inverse check reads the *source text* of every file in `graph/cypher/`,
 * including its import lines, and fails on any graph name written out. So a Kotlin object called
 * `JiraField` would fail the build the moment a Cypher file wrote
 * `import com.sec.source.jira.JiraField.KEY` — because `JiraLabel.FIELD` has the value
 * `"JiraField"`, and the word-boundary search finds it in the import. DOORS never met this by
 * luck: `DoorsLabel` and `DOORSModule` differ in casing. Here they would not, so the wire types
 * carry suffixes ([JiraProjectSummary], [JiraIssueTypeDefinition], [JiraFieldDefinition]) and
 * [JiraFieldId] is not called `JiraField`.
 */

/**
 * Node labels for imported JIRA data.
 *
 * Every one of these also carries `:SEItem`, which is the only thing a future cross-source link
 * joins on (R6). The shared entities — project, issue type, user, status, priority — are one node
 * each, `MERGE`d on `__id` and pointed at by many issues. That is where the graph earns its keep:
 * "every open issue assigned to X across all projects" is one traversal rather than a scan.
 */
public object JiraLabel {
    public const val ISSUE: String = "JiraIssue"
    public const val PROJECT: String = "JiraProject"
    public const val ISSUE_TYPE: String = "JiraIssueType"

    /**
     * One per `/field` definition — a **catalogue**, not a per-issue relationship.
     *
     * There is deliberately no `(:JiraIssue)-[:hasField]->(:JiraField)` edge: 784 issues times ~145
     * populated fields is ~114 000 edges encoding exactly what the issue's own property keys
     * already say, and every one of them would have to be diffed on each import. "Which fields
     * does this issue populate?" is `keys(i)` (spec §7.5).
     */
    public const val FIELD: String = "JiraField"

    public const val STATUS: String = "JiraStatus"
    public const val PRIORITY: String = "JiraPriority"
    public const val RESOLUTION: String = "JiraResolution"
    public const val USER: String = "JiraUser"
    public const val COMPONENT: String = "JiraComponent"
    public const val VERSION: String = "JiraVersion"

    /**
     * The display-string companion of one issue (spec §7.4).
     *
     * `__`-prefixed and application-owned: sorting a column whose stored value is
     * `{"self":"…","value":"WSS"}` needs a scalar, that scalar is *derived*, and R2 forbids derived
     * data on an imported node. It is disposable by design — every one of these can be rebuilt
     * from the issue nodes alone, which is what makes a change to the derivation rules a
     * re-projection rather than a re-import from JIRA.
     *
     * It is **not** `:__Meta`: meta is user knowledge that no import can reproduce, and this is the
     * opposite — machine-derived, regenerable, and worthless to preserve. See ADR 0014.
     */
    public const val PROJECTION: String = "__JiraProjection"

    /** The configured project keys — a singleton. Application configuration, not annotation. */
    public const val SETTINGS: String = "__JiraSettings"

    /** The chosen columns and their order — a singleton. Becomes per-user when RBAC lands. */
    public const val COLUMN_CONFIG: String = "__JiraColumnConfig"

    /** Every label this source declares. Read by `GraphNamesTest`, which is why it is exhaustive. */
    public val all: Set<String> = setOf(
        ISSUE, PROJECT, ISSUE_TYPE, FIELD, STATUS, PRIORITY, RESOLUTION, USER, COMPONENT, VERSION,
        PROJECTION, SETTINGS, COLUMN_CONFIG,
    )

    /**
     * The labels an import writes, as opposed to the ones the application owns.
     *
     * The split matters to the sweep: an import may delete an issue it no longer sees, and must
     * never touch a settings or column-config node while doing it.
     */
    public val imported: Set<String> = setOf(
        ISSUE, PROJECT, ISSUE_TYPE, FIELD, STATUS, PRIORITY, RESOLUTION, USER, COMPONENT, VERSION,
    )
}

/**
 * Relationship types.
 *
 * The un-prefixed ones are **JIRA's own assertions** and keep JIRA's own vocabulary, exactly as
 * DOORS traceability keeps `refersTo` (R3). The `__`-prefixed one is ours.
 */
public object JiraRel {
    public const val IN_PROJECT: String = "inProject"
    public const val HAS_ISSUE_TYPE: String = "hasIssueType"
    public const val HAS_STATUS: String = "hasStatus"
    public const val HAS_PRIORITY: String = "hasPriority"
    public const val HAS_RESOLUTION: String = "hasResolution"
    public const val ASSIGNED_TO: String = "assignedTo"
    public const val REPORTED_BY: String = "reportedBy"
    public const val CREATED_BY: String = "createdBy"
    public const val HAS_COMPONENT: String = "hasComponent"
    public const val AFFECTS_VERSION: String = "affectsVersion"
    public const val FIX_VERSION: String = "fixVersion"

    /**
     * One edge per JIRA issue link, always stored in JIRA's **outward** direction.
     *
     * Both ends of a link report it, so storing it as JIRA states it is what collapses
     * `(A outward→ B)` and `(B inward→ A)` into a single edge. `MERGE` on [JiraLinkProp.LINK_ID]
     * for the same reason.
     */
    public const val LINKED_TO: String = "linkedTo"

    /**
     * `fields.parent`, and only that.
     *
     * `fields.subtasks` is the inverse of the same fact, and importing both directions creates two
     * sources of truth that can disagree after a partial run (spec §9.5).
     */
    public const val SUB_TASK_OF: String = "subTaskOf"

    /** Issue to its display-string companion. Application-owned, hence the prefix. */
    public const val PROJECTION: String = "__projection"

    public val all: Set<String> = setOf(
        IN_PROJECT, HAS_ISSUE_TYPE, HAS_STATUS, HAS_PRIORITY, HAS_RESOLUTION,
        ASSIGNED_TO, REPORTED_BY, CREATED_BY, HAS_COMPONENT, AFFECTS_VERSION, FIX_VERSION,
        LINKED_TO, SUB_TASK_OF, PROJECTION,
    )
}

/**
 * JIRA field ids used **structurally** — the handful this code reasons about rather than merely
 * stores.
 *
 * Every other field id reaches the graph as data, keyed by whatever JIRA called it, and is never
 * named in Kotlin at all (R8). Naming one here is a statement that the importer depends on its
 * meaning, so the list is short on purpose and every addition is a new coupling to JIRA's schema.
 *
 * Called `JiraFieldId` and not `JiraField`: see the file note.
 */
public object JiraFieldId {
    public const val SUMMARY: String = "summary"
    public const val UPDATED: String = "updated"
    public const val PROJECT: String = "project"
    public const val ISSUE_TYPE: String = "issuetype"
    public const val STATUS: String = "status"
    public const val PRIORITY: String = "priority"
    public const val RESOLUTION: String = "resolution"
    public const val ASSIGNEE: String = "assignee"
    public const val REPORTER: String = "reporter"
    public const val CREATOR: String = "creator"
    public const val COMPONENTS: String = "components"
    public const val VERSIONS: String = "versions"
    public const val FIX_VERSIONS: String = "fixVersions"
    public const val PARENT: String = "parent"
    public const val SUBTASKS: String = "subtasks"
    public const val ISSUE_LINKS: String = "issuelinks"

    /**
     * The 13 fields that become graph edges as well as verbatim properties (spec §7.3).
     *
     * The duplication is deliberate: R1 keeps the raw copy so nothing is lost, and the edge gives
     * traversal. **Custom fields are never promoted**, even when their declared type is `user` or
     * `option` — there are 1 129 of them and the set changes without notice, so promoting by type
     * would make the graph's shape a function of somebody else's admin screen.
     */
    public val promoted: Set<String> = setOf(
        PROJECT, ISSUE_TYPE, STATUS, PRIORITY, RESOLUTION, ASSIGNEE, REPORTER, CREATOR,
        COMPONENTS, VERSIONS, FIX_VERSIONS, PARENT, ISSUE_LINKS,
    )
}

/**
 * Property names on imported JIRA nodes that this code writes or reads by name.
 *
 * The un-prefixed ones are JIRA's own and are stored verbatim (R1). [PROJECT_KEY] is the single
 * exception in the whole design and is explained on it.
 */
public object JiraProp {
    public const val ID: String = "id"
    public const val KEY: String = "key"
    public const val SELF: String = "self"
    public const val NAME: String = "name"
    public const val DESCRIPTION: String = "description"
    public const val ICON_URL: String = "iconUrl"
    public const val SUBTASK: String = "subtask"
    public const val AVATAR_ID: String = "avatarId"

    // -- /field catalogue -----------------------------------------------------------------------

    public const val CUSTOM: String = "custom"
    public const val ORDERABLE: String = "orderable"
    public const val NAVIGABLE: String = "navigable"
    public const val SEARCHABLE: String = "searchable"
    public const val CLAUSE_NAMES: String = "clauseNames"

    /**
     * `schema` flattened to four keys, values untouched.
     *
     * A *structural* flattening, which R1 permits because no value is altered — and it is done
     * because the column picker filters and sorts on the type on every keystroke, which a
     * JSON-text blob would turn into a parse per row (spec §9.2).
     */
    public const val SCHEMA_TYPE: String = "schemaType"
    public const val SCHEMA_ITEMS: String = "schemaItems"
    public const val SCHEMA_CUSTOM: String = "schemaCustom"
    public const val SCHEMA_CUSTOM_ID: String = "schemaCustomId"

    /**
     * The project key, copied onto the issue.
     *
     * This looks like it violates R2 and does not: it is a **denormalised copy of imported data**
     * (`fields.project.key`), not derived information — a re-import reproduces it exactly, which
     * is the Tier-1 test. It exists because the sweep in phase 5 must scope by project without a
     * traversal per issue.
     *
     * **It is the only denormalisation this design allows.** Adding a second one needs a reason
     * written down somewhere, because each one is a value that can disagree with its source.
     */
    public const val PROJECT_KEY: String = "__projectKey"

    /** Names in the `__` namespace this source declares. Read by `GraphNamesTest`. */
    public val namespaced: Set<String> = setOf(PROJECT_KEY)
}

/** Properties carried on a [JiraRel.LINKED_TO] edge. */
public object JiraLinkProp {
    /**
     * JIRA's own link id, and the dedup key.
     *
     * Both issues report the same link with the same id, so `MERGE` on this is what makes one
     * edge instead of two.
     */
    public const val LINK_ID: String = "linkId"

    public const val TYPE_ID: String = "typeId"
    public const val TYPE_NAME: String = "typeName"

    /**
     * The link type's two phrases.
     *
     * Both are stored and the UI renders the phrase rather than inferring direction from it —
     * `IsRelated` has identical inward and outward text, so direction is not recoverable from the
     * words (spec §9.4).
     */
    public const val INWARD: String = "inward"
    public const val OUTWARD: String = "outward"
}

/**
 * Property names on the application-owned JIRA nodes.
 *
 * Un-prefixed inside a `__`-labelled node, the same way a `:__Meta` payload is: the label already
 * says whose the node is, and prefixing the payload as well would say it twice.
 */
public object JiraAppProp {
    /** `:__JiraSettings` — the configured project keys, in the user's own order. */
    public const val PROJECT_KEYS: String = "projectKeys"

    /** `:__JiraColumnConfig` — the chosen field ids, in column order. Optional columns only. */
    public const val FIELD_IDS: String = "fieldIds"

    public const val UPDATED_AT: String = "updatedAt"
    public const val UPDATED_BY: String = "updatedBy"
}

/**
 * The `__id` of each singleton, and the one place a JIRA identity is synthesised.
 *
 * `/field` returns no `self`, so a field's `__id` has to be built — and it is built to look exactly
 * like the URL JIRA would have given it, so that identity stays "the resource URL" for every node
 * in the graph without exception (spec §6.2).
 */
public object JiraId {
    public const val SETTINGS: String = "jira-settings"
    public const val COLUMN_CONFIG: String = "jira-columns"

    /** `<host>/rest/api/2/field/<id>`. */
    public fun field(host: String, fieldId: String): String = "$host${JiraApi.FIELD}/$fieldId"

    /** The projection's identity is its issue's, suffixed — one per issue, and findable from it. */
    public fun projection(issueSelf: String): String = "$issueSelf#projection"
}
