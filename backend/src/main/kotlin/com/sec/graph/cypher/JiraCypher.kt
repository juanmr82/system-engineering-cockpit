package com.sec.graph.cypher

import com.sec.domain.MetaKind.ATTRIBUTE_SETTING as ATTRIBUTE_SETTING_KIND
import com.sec.domain.MetaKind.IMPORT_SCOPE as IMPORT_SCOPE_KIND
import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.ENABLED
import com.sec.domain.MetaProp.JQL
import com.sec.domain.MetaProp.ORDER
import com.sec.domain.MetaProp.VISIBLE
import com.sec.domain.MetaValue.CURRENT_SCHEMA_VERSION
import com.sec.domain.NodeLabel.ATTRIBUTE_SETTING
import com.sec.domain.NodeLabel.IMPORT_SCOPE
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.CREATED_AT
import com.sec.domain.Prop.CREATED_BY
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.IMPORTED_AT
import com.sec.domain.Prop.META_ID
import com.sec.domain.Prop.META_KIND
import com.sec.domain.Prop.NAME
import com.sec.domain.Prop.NAMESPACE
import com.sec.domain.Prop.SCHEMA_VERSION
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Prop.UPDATED_AT
import com.sec.domain.Prop.UPDATED_BY
import com.sec.domain.Prop.VERSION
import com.sec.domain.Rel.ATTRIBUTE_SETTING_FOR
import com.sec.domain.Rel.CHILD
import com.sec.domain.Rel.IMPORT_SCOPE_FOR
import com.sec.source.jira.JiraFieldId.KEY as ISSUE_KEY
import com.sec.source.jira.JiraLabel.FIELD as JIRA_FIELD
import com.sec.source.jira.JiraLabel.ISSUE as JIRA_ISSUE
import com.sec.source.jira.JiraLabel.ISSUE_TYPE as JIRA_ISSUE_TYPE
import com.sec.source.jira.JiraLabel.PROJECT as JIRA_PROJECT
import com.sec.source.jira.JiraLabel.SOURCE as JIRA_SOURCE
import com.sec.source.jira.JiraLinkProp.TYPE_ID as LINK_TYPE_ID
import com.sec.source.jira.JiraProp.PROJECT_KEY
import com.sec.source.jira.JiraRel.HAS_TYPE
import com.sec.source.jira.JiraRel.ISSUE_LINK

/**
 * Cypher for the JIRA source (`docs/jira-issues-dynamic-view-design.md`, ADR 0013).
 *
 * Two vocabularies meet in this file and the split is the whole point of ADR 0013: the statements
 * under **the importer** write imported nodes, and the statements under **Tier 2** write `:__Meta`
 * and nothing else. Only [JiraGraphWriter][com.sec.source.jira.JiraGraphWriter] issues the first
 * group, only [MetaWriter][com.sec.meta.MetaWriter] issues the second, and no route reaches either
 * except through them.
 *
 * Every graph name is interpolated from a constant (ADR 0010). A bare `$NAME` is a *name*; the
 * escaped form is a query *parameter*.
 *
 * ## Flattened field paths are never named in a statement
 *
 * `status.name` is a property whose name carries a dot, so `i.status.name` does not mean what it
 * looks like and `i.` + a name from JIRA would be Cypher built by concatenation from source data,
 * which CLAUDE.md §5 forbids outright. Reads therefore return the **node** and let Kotlin pick
 * paths out of its property map, exactly as the DOORS review table does with attribute names that
 * carry spaces and umlauts. Discovery uses `i[k]`, which is dynamic access by a bound variable and
 * not concatenation at all.
 */
public object JiraCypher {

    // ---------------------------------------------------------------- schema (importer-owned) --

    /**
     * Applied before the first write of a run, `IF NOT EXISTS` throughout.
     *
     * `se_item_id_unique` is deliberately repeated from the DOORS importer's schema phase rather
     * than assumed: this backend must work on an instance where no Python importer has ever run.
     * The definition is identical, so whichever gets there first wins and the other is a no-op —
     * and a *differing* definition would error loudly, which is the outcome to want.
     */
    public val SCHEMA: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT se_item_id_unique IF NOT EXISTS
        FOR (n:$SE_ITEM) REQUIRE n.$ID IS UNIQUE
        """,
        // The reconciliation predicate. Label-property indexes are per-label (CLAUDE.md §7), so
        // this has to name :JiraIssue — an index on :SEItem would not be used for it.
        "CYPHER 25 CREATE INDEX jira_issue_project IF NOT EXISTS FOR (n:$JIRA_ISSUE) ON (n.$PROJECT_KEY)",
        "CYPHER 25 CREATE INDEX jira_issue_key     IF NOT EXISTS FOR (n:$JIRA_ISSUE) ON (n.$ISSUE_KEY)",
        "CYPHER 25 CREATE INDEX jira_issue_sortkey IF NOT EXISTS FOR (n:$JIRA_ISSUE) ON (n.$SORT_KEY)",
        "CYPHER 25 CREATE INDEX jira_project_key   IF NOT EXISTS FOR (n:$JIRA_PROJECT) ON (n.$ISSUE_KEY)",
    )

    // -------------------------------------------------------------------------- the importer --

    /**
     * The one [JIRA_SOURCE] node: tree root for the JIRA branch, and the set-owner the display
     * column configuration anchors to.
     *
     * Written on every run so it exists before anything needs it, and so a graph whose JIRA data
     * was deleted by hand repairs itself on the next import.
     */
    public const val UPSERT_SOURCE: String = """
        CYPHER 25
        MERGE (s:$SE_ITEM:$JIRA_SOURCE {$ID: ${'$'}sourceId})
        SET s.$NAME        = ${'$'}name,
            s.$VERSION     = ${'$'}version,
            s.$SORT_KEY    = ${'$'}sortKey,
            s.$IMPORTED_AT = ${'$'}ts
    """

    public const val UPSERT_PROJECTS: String = """
        CYPHER 25
        MATCH (src:$JIRA_SOURCE {$ID: ${'$'}sourceId})
        UNWIND ${'$'}rows AS row
        MERGE (p:$SE_ITEM {$ID: row.id})
        SET p:$JIRA_PROJECT,
            p += row.props,
            p.$IMPORTED_AT = ${'$'}ts
        MERGE (src)-[c:$CHILD]->(p)
        SET c.$IMPORTED_AT = ${'$'}ts
    """

    public const val UPSERT_ISSUE_TYPES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (t:$SE_ITEM {$ID: row.id})
        SET t:$JIRA_ISSUE_TYPE,
            t += row.props,
            t.$IMPORTED_AT = ${'$'}ts
    """

    /**
     * The field catalogue from `GET /rest/api/2/field`.
     *
     * It is the only thing that reliably states a field's declared type independently of any one
     * issue's data (design doc §3), which is what lets the selection dialog show *Story Points*
     * where the graph says `customfield_10032`.
     */
    public const val UPSERT_FIELDS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (f:$SE_ITEM {$ID: row.id})
        SET f:$JIRA_FIELD,
            f += row.props,
            f.$IMPORTED_AT = ${'$'}ts
    """

    /**
     * The issues themselves, one batch per transaction.
     *
     * `REMOVE i:$UNDEFINED` is what promotes a stub: a link from an in-scope issue to an
     * out-of-scope one creates a placeholder, and the day that project enters the scope the same
     * node becomes the real issue rather than a duplicate of it. Every property arrives through
     * `+=` and a map parameter, so a field name carrying a dot needs no quoting and none of this
     * is ever built by concatenation.
     */
    public const val UPSERT_ISSUES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (i:$SE_ITEM {$ID: row.id})
        SET i:$JIRA_ISSUE,
            i += row.props,
            i.$IMPORTED_AT = ${'$'}ts
        REMOVE i:$UNDEFINED
    """

    public const val LINK_ISSUE_TYPES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (i:$JIRA_ISSUE {$ID: row.issueId})
        MATCH (t:$JIRA_ISSUE_TYPE {$ID: row.typeId})
        MERGE (i)-[r:$HAS_TYPE]->(t)
        SET r.$IMPORTED_AT = ${'$'}ts
    """

    /**
     * `__child` for both containment relationships JIRA asserts — project to issue, and issue to
     * sub-task — in one statement, because R3 has one hierarchy relationship and a sub-task's
     * parent is simply not its project.
     *
     * A second pass rather than part of [UPSERT_ISSUES]: a sub-task and its parent can arrive in
     * different batches, and a `MATCH` on a parent written by a later transaction finds nothing
     * and silently drops the row.
     */
    public const val LINK_HIERARCHY: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (parent:$SE_ITEM {$ID: row.parentId})
        MATCH (child:$JIRA_ISSUE {$ID: row.childId})
        MERGE (parent)-[c:$CHILD]->(child)
        SET c.$IMPORTED_AT = ${'$'}ts
    """

    /**
     * `fields.issuelinks[]`, drawn from the end holding the `outwardIssue` reference.
     *
     * A target outside the import scope becomes a placeholder rather than being dropped: that a
     * dependency crosses out of the imported scope is itself the information a traceability tool
     * exists to carry (design doc §8, option (a)). It reuses `:$UNDEFINED`, which this product
     * already renders as *Not yet imported* and already collects once nothing points at it — a
     * second "out of scope" state would have needed its own wording on every screen and its own
     * answer to the same question.
     *
     * The MERGE keys on the link type, so two issues related two ways keep two edges.
     */
    public const val LINK_ISSUES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (s:$JIRA_ISSUE {$ID: row.fromId})
        MERGE (t:$SE_ITEM {$ID: row.toId})
          ON CREATE SET t:$JIRA_ISSUE:$UNDEFINED,
                        t += row.stub
        MERGE (s)-[l:$ISSUE_LINK {$LINK_TYPE_ID: row.linkTypeId}]->(t)
        SET l += row.props,
            l.$IMPORTED_AT = ${'$'}ts
    """

    // ------------------------------------------------------- reconciliation (ADR 0013 §hard delete)

    /**
     * Everything this run did not re-stamp, deleted outright.
     *
     * **This is the one place the JIRA importer departs from ADR 0012**, which keeps a DOORS
     * object an export stopped mentioning and labels it `:__DELETED`. That decision exists because
     * DOORS deletes an object and *leaves the links pointing at it*, so the ghost is evidence of a
     * defect. JIRA removes an issue's links with the issue, and the set this reconciles against is
     * a JQL scope an admin edits — so the common case here is not a deletion at all but a change
     * of mind about what to import, and a graph that accumulated a ghost for every de-scoped issue
     * would be recording the admin's history rather than JIRA's data. ADR 0013 argues it in full.
     *
     * `coalesce(…, '')` is not decoration. `NULL <> ${'$'}ts` evaluates to NULL, which matches no
     * rows, so the un-coalesced form silently deletes nothing at all on the first run after an
     * upgrade — and reports success while doing it.
     *
     * Placeholders are excluded by label: one belongs to a project that is *not* in scope, so this
     * run never stamped it and never should have. [DELETE_ORPHAN_STUBS] is what collects those.
     */
    public const val DELETE_STALE_ISSUES: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        WHERE i.$PROJECT_KEY IN ${'$'}projectKeys
          AND NOT i:$UNDEFINED
          AND coalesce(i.$IMPORTED_AT, '') <> ${'$'}ts
        DETACH DELETE i
        RETURN count(*) AS deleted
    """

    /** A hierarchy edge this run did not re-assert — an issue moved to a different parent. */
    public const val PRUNE_HIERARCHY: String = """
        CYPHER 25
        MATCH (parent)-[c:$CHILD]->(i:$JIRA_ISSUE)
        WHERE i.$PROJECT_KEY IN ${'$'}projectKeys
          AND coalesce(c.$IMPORTED_AT, '') <> ${'$'}ts
        DELETE c
        RETURN count(*) AS pruned
    """

    /**
     * A link this run did not re-assert — removed in JIRA since the last import.
     *
     * Scoped to links going *out* of an in-scope, non-placeholder issue, because that is exactly
     * the set this run re-stated. A link out of a placeholder was asserted by a module nobody has
     * imported and no run of this importer has ever confirmed it.
     */
    public const val PRUNE_ISSUE_LINKS: String = """
        CYPHER 25
        MATCH (s:$JIRA_ISSUE)-[l:$ISSUE_LINK]->()
        WHERE s.$PROJECT_KEY IN ${'$'}projectKeys
          AND NOT s:$UNDEFINED
          AND coalesce(l.$IMPORTED_AT, '') <> ${'$'}ts
        DELETE l
        RETURN count(*) AS pruned
    """

    /**
     * Placeholders nothing points at any more, in either direction.
     *
     * Not scoped to the projects being imported, and deliberately so — the same reasoning ADR 0012
     * gives for its statements 5 and 6. Importing one project is exactly what removes the last
     * link to a placeholder standing in for an issue of a different one.
     */
    public const val DELETE_ORPHAN_STUBS: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE:$UNDEFINED)
        WHERE NOT (i)--()
        DELETE i
        RETURN count(*) AS deleted
    """

    /** Taking a project out of scope takes its issues with it; the project node itself stays. */
    public const val DELETE_PROJECT_ISSUES: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE {$PROJECT_KEY: ${'$'}projectKey})
        WHERE NOT i:$UNDEFINED
        DETACH DELETE i
        RETURN count(*) AS deleted
    """

    // -------------------------------------------------------------------------------- reading --

    /**
     * One page of the Issues table, in `__sortKey` order (R3), placeholders excluded.
     *
     * Returns the node rather than named columns: the properties a row carries are the flattened
     * field paths, whose names come from JIRA and carry dots. Kotlin picks the selected paths out
     * of the map — see this object's KDoc.
     */
    public const val ISSUE_PAGE: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        WHERE NOT i:$UNDEFINED
          AND (${'$'}projectKeys IS NULL OR i.$PROJECT_KEY IN ${'$'}projectKeys)
        WITH i ORDER BY i.$SORT_KEY
        SKIP ${'$'}offset LIMIT ${'$'}limit
        OPTIONAL MATCH (i)-[:$HAS_TYPE]->(t:$JIRA_ISSUE_TYPE)
        RETURN i AS issue, t.$NAME AS issueType
    """

    public const val COUNT_ISSUES: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        WHERE NOT i:$UNDEFINED
          AND (${'$'}projectKeys IS NULL OR i.$PROJECT_KEY IN ${'$'}projectKeys)
        RETURN count(i) AS total
    """

    /**
     * Runtime field discovery — the JIRA half of what `ModuleCypher.DISCOVER_ATTRIBUTES` does for
     * DOORS, and the thing that makes the selection dialog possible without a re-import.
     *
     * The namespace filter is R5's, and it is why the flattener may store `__rawFields` on the
     * same node without it ever reaching a column list. A sample value travels with each path so
     * the dialog can show what a field actually looks like on this instance, which the declared
     * schema in the catalogue does not tell you.
     */
    public const val DISCOVER_FIELD_PATHS: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        WHERE NOT i:$UNDEFINED
        WITH i LIMIT ${'$'}scanLimit
        UNWIND keys(i) AS path
        WITH path, i
        WHERE NOT path STARTS WITH '$NAMESPACE'
        WITH path, count(*) AS occurrences, head(collect(i[path])) AS sample
        RETURN path, occurrences, toString(sample) AS sample
        ORDER BY path
        LIMIT ${'$'}limit
    """

    public const val FIELD_CATALOG: String = """
        CYPHER 25
        MATCH (f:$JIRA_FIELD)
        RETURN f AS field
        ORDER BY f.$NAME
        LIMIT ${'$'}limit
    """

    /** Every known project with its scope state. `null` scope means "never added". */
    public const val LIST_PROJECTS: String = """
        CYPHER 25
        MATCH (p:$JIRA_PROJECT)
        OPTIONAL MATCH (p)-[:$IMPORT_SCOPE_FOR]->(s:$META:$IMPORT_SCOPE)
        OPTIONAL MATCH (i:$JIRA_ISSUE {$PROJECT_KEY: p.$ISSUE_KEY})
        WHERE NOT i:$UNDEFINED
        RETURN p AS project,
               s.$ENABLED AS enabled,
               s.$JQL     AS jql,
               count(i)   AS issueCount
        ORDER BY p.$NAME
        LIMIT ${'$'}limit
    """

    /** The projects an import run fetches: in scope and not switched off. */
    public const val ENABLED_PROJECTS: String = """
        CYPHER 25
        MATCH (p:$JIRA_PROJECT)-[:$IMPORT_SCOPE_FOR]->(s:$META:$IMPORT_SCOPE)
        WHERE coalesce(s.$ENABLED, true)
        RETURN p.$ISSUE_KEY AS projectKey, s.$JQL AS jql
        ORDER BY projectKey
        LIMIT ${'$'}limit
    """

    /** The chosen columns, in the order the admin put them. */
    public const val SELECTED_COLUMNS: String = """
        CYPHER 25
        MATCH (:$JIRA_SOURCE {$ID: ${'$'}sourceId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE coalesce(s.$VISIBLE, false)
        RETURN s.$ATTRIBUTE_NAME AS path, coalesce(s.$ORDER, 0) AS position
        ORDER BY position, path
    """

    // --------------------------------------------------------------- Tier 2 (MetaWriter-owned) --

    /**
     * One `:$IMPORT_SCOPE` per project, enforced by the MERGE because Community has no composite
     * constraint to lean on — the same arrangement `ReviewCypher.UPSERT_ATTRIBUTE_SETTINGS` uses.
     *
     * The project node must already exist, which is the point: adding a project fetches it from
     * JIRA first, so a scope entry can never name a typo.
     */
    public const val UPSERT_IMPORT_SCOPE: String = """
        CYPHER 25
        MATCH (p:$JIRA_PROJECT {$ID: ${'$'}projectId})
        MERGE (p)-[:$IMPORT_SCOPE_FOR]->(s:$META:$IMPORT_SCOPE)
          ON CREATE SET s.$META_ID    = ${'$'}metaId,
                        s.$CREATED_BY = ${'$'}user,
                        s.$CREATED_AT = ${'$'}now
        SET s.$META_KIND      = '$IMPORT_SCOPE_KIND',
            s.$SCHEMA_VERSION = $CURRENT_SCHEMA_VERSION,
            s.$ENABLED        = ${'$'}enabled,
            s.$JQL            = ${'$'}jql,
            s.$UPDATED_BY     = ${'$'}user,
            s.$UPDATED_AT     = ${'$'}now
        RETURN s.$META_ID AS metaId
    """

    public const val REMOVE_IMPORT_SCOPE: String = """
        CYPHER 25
        MATCH (:$JIRA_PROJECT {$ID: ${'$'}projectId})-[:$IMPORT_SCOPE_FOR]->(s:$META:$IMPORT_SCOPE)
        DETACH DELETE s
    """

    /**
     * The display column set, anchored to the source node because the Issues table is one table
     * across every project — there is no per-project column list to attach it to.
     *
     * `order` is carried so the admin's column sequence survives; the *label* is not, for the same
     * reason a `:__Classification` never stores "L2 – Segment" (R5). It is resolved from the field
     * catalogue on read, so a field renamed in JIRA renames its column on the next import.
     */
    public const val UPSERT_COLUMN_SETTINGS: String = """
        CYPHER 25
        MATCH (src:$JIRA_SOURCE {$ID: ${'$'}sourceId})
        UNWIND ${'$'}settings AS row
        MERGE (src)-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING {$ATTRIBUTE_NAME: row.path})
          ON CREATE SET s.$META_ID    = row.metaId,
                        s.$CREATED_BY = ${'$'}user,
                        s.$CREATED_AT = ${'$'}now
        SET s.$META_KIND      = '$ATTRIBUTE_SETTING_KIND',
            s.$SCHEMA_VERSION = $CURRENT_SCHEMA_VERSION,
            s.$VISIBLE        = row.visible,
            s.$ORDER          = row.position,
            s.$UPDATED_BY     = ${'$'}user,
            s.$UPDATED_AT     = ${'$'}now
    """

    /** A column the admin deselected. Same reasoning as an emptied comment: no node for a false. */
    public const val DELETE_COLUMN_SETTINGS: String = """
        CYPHER 25
        MATCH (:$JIRA_SOURCE {$ID: ${'$'}sourceId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE NOT s.$ATTRIBUTE_NAME IN ${'$'}keep
        DETACH DELETE s
    """
}
