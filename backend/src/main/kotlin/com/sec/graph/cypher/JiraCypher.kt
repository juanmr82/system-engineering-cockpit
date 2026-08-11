package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.ID
import com.sec.source.jira.JiraAppProp.PROJECT_KEYS
import com.sec.source.jira.JiraAppProp.UPDATED_AT
import com.sec.source.jira.JiraAppProp.UPDATED_BY
import com.sec.source.jira.JiraLabel.FIELD as JIRA_FIELD
import com.sec.source.jira.JiraLabel.ISSUE as JIRA_ISSUE
import com.sec.source.jira.JiraLabel.ISSUE_TYPE as JIRA_ISSUE_TYPE
import com.sec.source.jira.JiraLabel.PROJECT as JIRA_PROJECT
import com.sec.source.jira.JiraLabel.PROJECTION as JIRA_PROJECTION
import com.sec.source.jira.JiraLabel.SETTINGS as JIRA_SETTINGS
import com.sec.source.jira.JiraProp.KEY
import com.sec.source.jira.JiraProp.NAME
import com.sec.source.jira.JiraProp.PROJECT_KEY
import com.sec.source.jira.JiraRel.HAS_ISSUE_TYPE
import com.sec.source.jira.JiraRel.PROJECTION

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
        // The projection is not a :SEItem, so the constraint above does not reach it — and without
        // one, a MERGE that ever ran on an unbound pattern would silently make a second projection
        // for an issue. Phase 3's statements are written so that cannot happen; this is what makes
        // it true rather than intended.
        """
        CYPHER 25
        CREATE CONSTRAINT jira_projection_id_unique IF NOT EXISTS
        FOR (p:$JIRA_PROJECTION) REQUIRE p.$ID IS UNIQUE
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

    /**
     * Phase 3 — the shared nodes an issue points at: project, type, status, user, component…
     *
     * Written before the issues on purpose, so the relationship statement can `MATCH` both ends
     * instead of merging one of them into existence and hiding a mapping bug as a bare node.
     *
     * **`SET n:${'$'}(row.label)` is a Cypher 25 dynamic label**, and it is the one thing here worth
     * pausing on. It is not user input reaching the graph's schema: the value comes from
     * [com.sec.source.jira.JiraLabel], a closed set of compile-time constants, and it arrives as a
     * *parameter* rather than by string-building a statement — so R10 holds in the way that matters,
     * that no attacker-influenced text is ever parsed as Cypher. What it buys is one statement
     * instead of nine near-identical ones that would drift apart the first time one is edited.
     */
    public const val UPSERT_ENTITIES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (n:$SE_ITEM {$ID: row.id})
        SET n:${'$'}(row.label), n += row.props
    """

    /**
     * Phase 3 — the issues themselves, **including the removal of properties JIRA no longer sends**.
     *
     * ## Why the second half exists
     *
     * `SET i += row.props` only adds and overwrites. A field a user *cleared* in JIRA arrives as
     * `null`, which the mapper skips, so without the removal the node keeps last import's value
     * forever — and a review table shows a value that no longer exists in the source. Spec §12
     * calls this the trickiest part of the importer, and it is.
     *
     * ## How it works, and the one subtlety
     *
     * `row.presentKeys` is every key the issue currently has a value for, so the list comprehension
     * is "what is on the node that should not be". `REMOVE i[staleKey]` is Cypher 25's dynamic
     * property removal — simpler than the `CALL (i, staleKey) { SET i[staleKey] = null }` the spec
     * proposes, and verified against the pinned 2026.06 image.
     *
     * The subtlety: **`UNWIND` of an empty list produces no rows**, so an issue with nothing stale
     * disappears from the stream at that point. Its `MERGE` and `SET` have already committed — side
     * effects happen as the row flows through — so this is correct, but it does mean nothing may be
     * appended after the `REMOVE`. Anything that needs every issue goes in its own statement.
     *
     * `NOT k STARTS WITH '__'` is what keeps `__id`, `__name`, `__version` and `__projectKey` out of
     * the sweep: they are ours, they are not JIRA fields, and no `presentKeys` list would name them.
     */
    public const val UPSERT_ISSUES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (i:$SE_ITEM {$ID: row.id})
        SET i:$JIRA_ISSUE, i += row.props
        WITH i, row
        UNWIND [k IN keys(i) WHERE NOT k STARTS WITH '__' AND NOT k IN row.presentKeys] AS staleKey
        REMOVE i[staleKey]
    """

    /**
     * Phase 3 — one display-string companion per issue (spec §7.4).
     *
     * The node is merged **before** the edge rather than as one pattern, and that is not a style
     * choice: `MERGE (i)-[:__projection]->(p:__JiraProjection {__id: …})` matches the *whole*
     * pattern, so an existing projection with no edge yet would make Cypher create a second
     * projection node rather than reuse it. Bind first, then connect.
     *
     * The same stale-key removal as the issues, for the same reason: a field that stops being
     * complex — an option replaced by a plain string — must lose its projection entry, or a column
     * would resolve `coalesce(i[k], p[k])` to a value that is no longer derived from anything.
     *
     * A projection is written for **every** issue, including one with nothing to project. An issue
     * with no companion and an issue whose companion is empty would otherwise be the same shape to
     * a reader, and the acceptance criterion is one per issue (spec §16.2).
     */
    public const val UPSERT_PROJECTIONS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (i:$SE_ITEM {$ID: row.issueId})
        MERGE (p:$JIRA_PROJECTION {$ID: row.id})
        MERGE (i)-[:$PROJECTION]->(p)
        SET p += row.props
        WITH p, row
        UNWIND [k IN keys(p) WHERE NOT k STARTS WITH '__' AND NOT k IN row.presentKeys] AS staleKey
        REMOVE p[staleKey]
    """

    /**
     * Phase 3 — the promoted edges, by dynamic relationship type (see [UPSERT_ENTITIES] on why).
     *
     * Both ends are `MATCH`ed, never merged. The entity statement has already run, so a miss here
     * means the mapper produced an edge to something it did not also produce a node for — and the
     * right outcome for that is a missing edge somebody notices, not a labelless node that makes
     * the graph look fine and reads as an empty row three views away.
     */
    public const val MERGE_PROMOTED: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (i:$SE_ITEM {$ID: row.issueId})
        MATCH (e:$SE_ITEM {$ID: row.entityId})
        MERGE (i)-[:${'$'}(row.type)]->(e)
    """

    /**
     * Phase 3 — promoted edges this import no longer asserts.
     *
     * Re-assigning an issue is the case that makes this necessary: `MERGE` adds the new
     * `assignedTo` and leaves the old, so without a prune the issue is assigned to two people and
     * every query about either of them is wrong. It is the stale-property bug one level up, and it
     * is easier to miss because nothing about the node looks damaged.
     *
     * Scoped by `${'$'}promotedTypes` — [com.sec.source.jira.JiraRel.promoted] — so it can only ever
     * remove edges phase 3 owns. `linkedTo` and `subTaskOf` belong to phase 4, which diffs them
     * against the whole run; deleting them here would drop every link seen on page one.
     */
    public const val PRUNE_PROMOTED: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (i:$SE_ITEM {$ID: row.issueId})-[r]->(e)
        WHERE type(r) IN ${'$'}promotedTypes
          AND NOT any(keep IN row.keep WHERE keep.type = type(r) AND keep.id = e.$ID)
        DELETE r
    """

    /** Issue and projection counts, for the run report and the phase 6 validation. */
    public const val COUNT_ISSUES: String = """
        CYPHER 25
        OPTIONAL MATCH (i:$JIRA_ISSUE)
        WITH count(i) AS issues
        OPTIONAL MATCH (:$JIRA_ISSUE)-[r:$PROJECTION]->(:$JIRA_PROJECTION)
        RETURN issues, count(r) AS projections
    """

    /**
     * The configured project keys (spec §10.1).
     *
     * A singleton, and **application configuration rather than imported data** — which is why it is
     * read and written by its own store and not by the importer's writer. Nothing about it is
     * regenerable from JIRA: it is the question, not the answer.
     */
    public const val LOAD_SETTINGS: String = """
        CYPHER 25
        MATCH (s:$JIRA_SETTINGS {$ID: ${'$'}id})
        RETURN s.$PROJECT_KEYS AS projectKeys
    """

    public const val SAVE_SETTINGS: String = """
        CYPHER 25
        MERGE (s:$JIRA_SETTINGS {$ID: ${'$'}id})
        SET s.$PROJECT_KEYS = ${'$'}projectKeys,
            s.$UPDATED_AT = ${'$'}updatedAt,
            s.$UPDATED_BY = ${'$'}updatedBy
    """
}
