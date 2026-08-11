package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.ID
import com.sec.source.jira.JiraLabel.FIELD as JIRA_FIELD
import com.sec.source.jira.JiraLabel.ISSUE as JIRA_ISSUE
import com.sec.source.jira.JiraLabel.ISSUE_TYPE as JIRA_ISSUE_TYPE
import com.sec.source.jira.JiraLabel.PROJECT as JIRA_PROJECT
import com.sec.source.jira.JiraProp.KEY
import com.sec.source.jira.JiraProp.NAME
import com.sec.source.jira.JiraProp.PROJECT_KEY
import com.sec.source.jira.JiraRel.HAS_ISSUE_TYPE

/**
 * Cypher for the JIRA importer (`docs/JIRA_ISSUES_FEATURE_SPEC.md` §6 and §12).
 *
 * Every graph name is interpolated from a constant, never spelled out — `GraphNamesTest` reads
 * this file's *source* and fails on a literal, because a hand-written `__id` compiles to the same
 * string as the constant and no other check can see the difference (ADR 0010).
 *
 * Two rules run through all of it:
 *
 *  - **Every write is `UNWIND $rows` with map parameters** (spec §1, R10). No label or property
 *    name is ever built by concatenation. JIRA field ids are safe Neo4j property keys by
 *    construction — letters, digits and underscore — so `SET n += row.props` needs no quoting at
 *    all, which is the whole reason the storage design keys properties by field id (§7.2).
 *  - **`MERGE` on `__id`, which is the resource URL.** Not the issue key: keys change when an
 *    issue moves between projects, and the numeric id inside `self` never does (§6.2).
 */
public object JiraCypher {

    /**
     * Constraints and indexes, applied at the start of every run (spec §12 phase 0, §6.3).
     *
     * At run start rather than at boot, and this is deliberate: the backend owns `:__Meta` schema
     * and applies it in `MetaSchema` on every start, but schema for *imported* labels belongs to
     * whatever imports them. JIRA's importer happening to live in this process (ADR 0013) does not
     * move that ownership — a deployment with no JIRA configured should not be creating JIRA
     * indexes.
     *
     * The `:SEItem` uniqueness constraint is created here too, even though the DOORS importer also
     * creates it. `IF NOT EXISTS` matches on an equivalent constraint as well as on the name, so
     * the second creation is a no-op rather than a conflict — and a graph that has only ever seen
     * JIRA still gets the constraint that identity depends on.
     *
     * Each statement runs on its own: schema changes cannot share a transaction with anything else.
     */
    public val SCHEMA: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT se_item_id_unique IF NOT EXISTS
        FOR (n:$SE_ITEM) REQUIRE n.$ID IS UNIQUE
        """,
        // Label-property indexes are per-label, and the planner will not use a :SEItem index for a
        // :JiraIssue pattern - it has no knowledge that every JiraIssue is one (CLAUDE.md §7). So
        // the lookups the read path actually makes each need their own.
        """
        CYPHER 25
        CREATE INDEX jira_issue_key IF NOT EXISTS
        FOR (n:$JIRA_ISSUE) ON (n.$KEY)
        """,
        // Scopes the phase 5 sweep by project without a traversal per issue, which is the entire
        // reason __projectKey is denormalised onto the issue at all.
        """
        CYPHER 25
        CREATE INDEX jira_issue_project IF NOT EXISTS
        FOR (n:$JIRA_ISSUE) ON (n.$PROJECT_KEY)
        """,
        """
        CYPHER 25
        CREATE INDEX jira_project_key IF NOT EXISTS
        FOR (n:$JIRA_PROJECT) ON (n.$KEY)
        """,
        """
        CYPHER 25
        CREATE INDEX jira_issuetype_name IF NOT EXISTS
        FOR (n:$JIRA_ISSUE_TYPE) ON (n.$NAME)
        """,
        // The column picker searches 1 171 rows by name on every keystroke.
        """
        CYPHER 25
        CREATE INDEX jira_field_name IF NOT EXISTS
        FOR (n:$JIRA_FIELD) ON (n.$NAME)
        """,
        // Deliberately absent: an index on __id. The uniqueness constraint above already creates a
        // backing range index, and a duplicate CREATE INDEX errors (CLAUDE.md §7).
    )

    /**
     * Phase 1 — issue types.
     *
     * `SET t:$JIRA_ISSUE_TYPE` unconditionally rather than `ON CREATE`: a node can already exist as
     * a bare `:SEItem` — a placeholder another source reached first — and `ON CREATE` would leave
     * it without the label forever. Re-applying a label a node already has costs nothing.
     */
    public const val UPSERT_ISSUE_TYPES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (t:$SE_ITEM {$ID: row.id})
        SET t:$JIRA_ISSUE_TYPE, t += row.props
    """

    /**
     * An issue type that has left JIRA is removed **only if nothing uses it** (spec §9.1).
     *
     * The guard is the point. Issue types are imported before issues, so mid-run the graph still
     * holds last run's issues; deleting an unreferenced-looking type would strip the
     * `hasIssueType` edge off every one of them and leave the phase 6 validation failing for a
     * reason nowhere near the cause.
     */
    public const val DELETE_UNUSED_ISSUE_TYPES: String = """
        CYPHER 25
        MATCH (t:$JIRA_ISSUE_TYPE)
        WHERE NOT t.$ID IN ${'$'}seenIds
          AND NOT (t)<-[:$HAS_ISSUE_TYPE]-()
        DETACH DELETE t
    """

    /**
     * Phase 2 — the field catalogue.
     *
     * A catalogue and nothing more: no edge ties an issue to the fields it populates, because the
     * issue's own property keys already say so and ~114 000 edges saying it again would have to be
     * diffed on every run (spec §7.5).
     */
    public const val UPSERT_FIELDS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (f:$SE_ITEM {$ID: row.id})
        SET f:$JIRA_FIELD, f += row.props
    """

    /**
     * A field definition removed from JIRA leaves the catalogue.
     *
     * Unguarded, unlike issue types, and safe for the same reason issue types are not: nothing
     * points at a `:$JIRA_FIELD`. A user's *column choice* may still name it, and that choice is
     * deliberately left alone — the column renders as stale rather than silently disappearing,
     * which is a decision made in the API layer, not here (spec §9.2, §13.4).
     */
    public const val DELETE_STALE_FIELDS: String = """
        CYPHER 25
        MATCH (f:$JIRA_FIELD)
        WHERE NOT f.$ID IN ${'$'}seenIds
        DETACH DELETE f
    """

    /** How many issue types and field definitions the catalogue currently holds. */
    public const val COUNT_CATALOGUE: String = """
        CYPHER 25
        OPTIONAL MATCH (t:$JIRA_ISSUE_TYPE)
        WITH count(t) AS issueTypes
        OPTIONAL MATCH (f:$JIRA_FIELD)
        RETURN issueTypes, count(f) AS fields
    """
}
