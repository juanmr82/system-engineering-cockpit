package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
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
import com.sec.source.jira.JiraLinkProp.LINK_ID
import com.sec.source.jira.JiraProp.KEY
import com.sec.source.jira.JiraProp.NAME
import com.sec.source.jira.JiraProp.PROJECT_KEY
import com.sec.source.jira.JiraRel.HAS_ISSUE_TYPE
import com.sec.source.jira.JiraRel.LINKED_TO
import com.sec.source.jira.JiraRel.PROJECTION
import com.sec.source.jira.JiraRel.SUB_TASK_OF

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
     *
     * ## The placeholder loses its label here
     *
     * `REMOVE i:__UNDEFINED` is a no-op for an issue that was never a stub, and for one that was it
     * is the moment the stub becomes real. Spec §12 makes this step 5 of phase 4; doing it in the
     * statement that writes the data means a placeholder cannot outlive its own import even if
     * phase 4 never runs — and it costs one `REMOVE` instead of a second pass carrying every id
     * seen this run (ADR 0014). The DOORS importer removes the same label in the same place, for
     * the same reason.
     */
    public const val UPSERT_ISSUES: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (i:$SE_ITEM {$ID: row.id})
        SET i:$JIRA_ISSUE, i += row.props
        REMOVE i:$UNDEFINED
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

    // -- Phase 4: links -----------------------------------------------------------------------

    /**
     * Phase 4 — a stub for a link target this import never saw (spec §9.4).
     *
     * ## What it is for
     *
     * A JIRA link can point anywhere on the instance, and the configured projects are a slice of it.
     * Without a stub the edge has nowhere to land, so the reviewer sees "no links" — which reads as
     * *there is nothing linked to this*, the opposite of the truth. The stub carries the key and the
     * summary JIRA embedded in the link itself, so it names the issue it stands for, and the "open
     * in JIRA" link still works because we have its `self`.
     *
     * ## `ON CREATE` is the safety property, not an optimisation
     *
     * The `__id` here is the target's `self`, identical to the value the node will carry once the
     * issue is really imported — that is what makes phase 3 fill this stub in rather than create a
     * second node beside it. Which also means this statement can address a **real** issue, if the
     * seen set were ever incomplete. `ON CREATE` is what makes that harmless: an existing node is
     * matched and left exactly as it was, so the worst case is a missing stub rather than a real
     * issue overwritten with four properties from a link payload.
     */
    public const val MERGE_PLACEHOLDERS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (o:$SE_ITEM {$ID: row.id})
        ON CREATE SET o:$JIRA_ISSUE:$UNDEFINED, o += row.props
    """

    /**
     * Phase 4 — one edge per JIRA link, `MERGE`d on JIRA's own link id.
     *
     * Both ends of a link report it with the same id, and the mapper has already normalised the
     * direction to JIRA's outward one, so merging on the link id is what collapses two reports into
     * one edge instead of two that disagree about which way round they are.
     *
     * Both ends are `MATCH`ed. [MERGE_PLACEHOLDERS] has already run, so a miss here means an end that
     * is neither imported nor stubbed — a mapping fault, and the right outcome for it is a missing
     * edge somebody notices rather than a bare node that makes the graph look complete.
     */
    public const val MERGE_LINKS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (a:$SE_ITEM {$ID: row.fromId})
        MATCH (b:$SE_ITEM {$ID: row.toId})
        MERGE (a)-[r:$LINKED_TO {$LINK_ID: row.linkId}]->(b)
        SET r += row.props
    """

    /**
     * Phase 4 — links removed in JIRA.
     *
     * ## Why "either end was seen" and not "both"
     *
     * Spec §12 deletes only edges whose **both** endpoints were imported, to avoid deleting a link
     * asserted by an issue outside this run's scope. That is safe, and it is also too narrow: it can
     * never remove a link between an imported issue and a stub, so a link deleted in JIRA whose
     * other end lives outside the configured projects would stay in the graph for good.
     *
     * One end is enough, and the reason is JIRA's own symmetry: **both** issues report a link, so if
     * either end was imported this run and the link still existed, its link id would be in
     * `${'$'}seenLinkIds`. It is not, therefore the link is gone. The seen-set condition is still
     * doing the work it was there for — it is what stops this deleting links between two issues this
     * run never looked at (ADR 0014).
     *
     * Scanning by relationship type rather than per issue: the edges are the small side — ~550 for
     * 784 issues on the reference instance — and diffing the small side is cheaper than a lookup per
     * issue whether or not it has any links at all.
     */
    public const val DELETE_STALE_LINKS: String = """
        CYPHER 25
        MATCH (a:$JIRA_ISSUE)-[r:$LINKED_TO]->(b)
        WHERE NOT r.$LINK_ID IN ${'$'}seenLinkIds
          AND (a.$ID IN ${'$'}seenIds OR b.$ID IN ${'$'}seenIds)
        DELETE r
    """

    /**
     * Phase 4 — `fields.parent` as an edge, using the same stub rule as links (spec §9.5).
     *
     * `fields.subtasks` is deliberately not imported: it is the inverse of this same fact, and
     * writing both directions creates two sources of truth that disagree the first time a run stops
     * halfway.
     */
    public const val MERGE_SUB_TASKS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (c:$SE_ITEM {$ID: row.childId})
        MATCH (p:$SE_ITEM {$ID: row.parentId})
        MERGE (c)-[:$SUB_TASK_OF]->(p)
    """

    /**
     * Phase 4 — a sub-task moved to another parent, or promoted out of one.
     *
     * The same failure as a re-assigned issue: `MERGE` adds the new parent and leaves the old, and
     * an issue with two parents answers "where does this sit" twice. `${'$'}keep` carries only the
     * issues that still have a parent, so an issue that lost one is covered by having no entry
     * rather than by a row saying so.
     */
    public const val DELETE_STALE_SUB_TASKS: String = """
        CYPHER 25
        MATCH (c:$JIRA_ISSUE)-[r:$SUB_TASK_OF]->(p)
        WHERE c.$ID IN ${'$'}seenIds
          AND NOT any(keep IN ${'$'}keep WHERE keep.childId = c.$ID AND keep.parentId = p.$ID)
        DELETE r
    """

    // -- Phase 5: the sweep -------------------------------------------------------------------

    /**
     * Phase 5 — issues deleted in JIRA.
     *
     * **The highest-consequence statement in this feature**, because a seen set that is wrong by
     * omission is indistinguishable from a project that has been emptied. The caller must refuse to
     * run this at all unless phase 3 completed; see [com.sec.source.jira.JiraImporter].
     *
     * Scoped by `__projectKey`, the one denormalisation in the design, which exists precisely so
     * this is an index lookup rather than a traversal per issue. The stub exclusion is redundant —
     * a stub has no `__projectKey` to match — and stated anyway: a stub can carry a user's
     * annotation, and deleting one is not recoverable by re-running the import.
     *
     * The projection goes with the issue. It is derived data owned by this importer, so leaving one
     * behind would leave a companion no query can reach.
     */
    public const val SWEEP_DELETED: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        WHERE NOT i:$UNDEFINED
          AND i.$PROJECT_KEY IN ${'$'}configuredKeys
          AND NOT i.$ID IN ${'$'}seenIds
        OPTIONAL MATCH (i)-[:$PROJECTION]->(p:$JIRA_PROJECTION)
        DETACH DELETE i, p
        RETURN count(*) AS deleted
    """

    /**
     * Phase 5 — issues of a project that is no longer configured (spec §12, R4).
     *
     * A separate statement from [SWEEP_DELETED] rather than a widened one, because the two answer
     * different questions and the run summary has to be able to say which happened: an issue that
     * vanished from JIRA is news, and an issue that left because somebody unticked its project is
     * not. Same shape, two counters, two sentences.
     *
     * `IS NOT NULL` is what keeps a stub out of it — and, less obviously, an issue that arrived
     * without a project key at all. Deleting that one because it cannot prove where it belongs
     * would be losing data for being unreadable.
     */
    public const val SWEEP_DECONFIGURED: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        WHERE NOT i:$UNDEFINED
          AND i.$PROJECT_KEY IS NOT NULL
          AND NOT i.$PROJECT_KEY IN ${'$'}configuredKeys
        OPTIONAL MATCH (i)-[:$PROJECTION]->(p:$JIRA_PROJECTION)
        DETACH DELETE i, p
        RETURN count(*) AS deleted
    """

    /**
     * Phase 5 — the shared nodes and stubs nothing points at any more.
     *
     * A user, component or version exists only to be pointed at; once the last issue naming it is
     * gone it is a node no view can reach and no query will ever return.
     *
     * **Projects are deliberately not in the label list.** They are cheap, they are what the
     * settings screen lists, and a project emptied by a de-configuration is exactly the one a user
     * is about to re-tick.
     *
     * `COUNT { (n)--() } = 0` counts *every* relationship, in both directions, which is what makes
     * this safe for Tier 2: a stub somebody annotated has a `__noteOn` edge and stays. The scan is
     * over `:SEItem` because the deletable labels have no common one of their own; it runs once per
     * import, against a degree lookup per node.
     */
    public const val DELETE_ORPHANED_ENTITIES: String = """
        CYPHER 25
        MATCH (n:$SE_ITEM)
        WHERE any(label IN labels(n) WHERE label IN ${'$'}labels)
          AND COUNT { (n)--() } = 0
        DELETE n
        RETURN count(*) AS deleted
    """

    /**
     * Phase 5 — a placeholder whose last link was deleted.
     *
     * Its own statement rather than a label in [DELETE_ORPHANED_ENTITIES]'s list, and the reason is
     * the shared label: `:__UNDEFINED` is source-agnostic (ADR 0014), so a statement keyed on it
     * alone would have a JIRA import deleting DOORS placeholders. The pair `:JiraIssue:__UNDEFINED`
     * is what makes this JIRA's own.
     *
     * A stub stood for a link; once no link points at it there is nothing left for it to stand for.
     * `COUNT { (n)--() } = 0` counts every relationship in both directions, so a stub somebody
     * annotated has a `__noteOn` edge and stays — which is R2 holding at the one place an importer
     * is allowed to delete.
     */
    public const val DELETE_ORPHANED_PLACEHOLDERS: String = """
        CYPHER 25
        MATCH (n:$JIRA_ISSUE:$UNDEFINED)
        WHERE COUNT { (n)--() } = 0
        DELETE n
        RETURN count(*) AS deleted
    """

    /** How many stubs are standing — the difference across a run is what the counters report. */
    public const val COUNT_PLACEHOLDERS: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE:$UNDEFINED)
        RETURN count(i) AS placeholders
    """

    /**
     * Issue and projection counts, for the run report and the phase 6 validation.
     *
     * **Placeholders are excluded, and that exclusion is the whole correctness of this statement.**
     * A stub carries `:JiraIssue` deliberately — it is reached by every JIRA query — but it has no
     * projection, because there is nothing to project until the issue itself is imported. Counting
     * stubs as issues makes the two numbers differ by the number of stubs, and the caller reads a
     * difference as "some issues have no projection". A graph with placeholders in it would report
     * that on every run, for ever, while being entirely correct.
     */
    public const val COUNT_ISSUES: String = """
        CYPHER 25
        OPTIONAL MATCH (i:$JIRA_ISSUE) WHERE NOT i:$UNDEFINED
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
