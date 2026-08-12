package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.NAME as ITEM_NAME
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Prop.ID
import com.sec.source.jira.JiraAppProp.FIELD_IDS
import com.sec.source.jira.JiraAppProp.PROJECT_KEYS
import com.sec.source.jira.JiraAppProp.UPDATED_AT
import com.sec.source.jira.JiraAppProp.UPDATED_BY
import com.sec.source.jira.JiraLabel.COLUMN_CONFIG as JIRA_COLUMN_CONFIG
import com.sec.source.jira.JiraLabel.FIELD as JIRA_FIELD
import com.sec.source.jira.JiraLabel.ISSUE as JIRA_ISSUE
import com.sec.source.jira.JiraLabel.ISSUE_TYPE as JIRA_ISSUE_TYPE
import com.sec.source.jira.JiraLabel.PROJECT as JIRA_PROJECT
import com.sec.source.jira.JiraLabel.PROJECTION as JIRA_PROJECTION
import com.sec.source.jira.JiraLabel.SETTINGS as JIRA_SETTINGS
import com.sec.source.jira.JiraLinkProp.LINK_ID
import com.sec.source.jira.JiraProp.CUSTOM
import com.sec.source.jira.JiraProp.ID as JIRA_ID
import com.sec.source.jira.JiraProp.KEY
import com.sec.source.jira.JiraProp.NAME
import com.sec.source.jira.JiraProp.PROJECT_KEY
import com.sec.source.jira.JiraProp.SCHEMA_ITEMS
import com.sec.source.jira.JiraProp.SCHEMA_TYPE
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
        // The issues table's default order, and the reason it is an index rather than a sort: the
        // table reads one page of 50 out of a set that is 784 today and tens of thousands later,
        // and `ORDER BY … SKIP … LIMIT` without an index sorts the whole set to answer for fifty
        // rows of it. The DOORS importer creates the same index over the same property.
        """
        CYPHER 25
        CREATE INDEX jira_issue_sortkey IF NOT EXISTS
        FOR (n:$JIRA_ISSUE) ON (n.$SORT_KEY)
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

    // -- the read path (spec §14.4) --------------------------------------------------------------

    /**
     * The `WHERE` every issues query shares, so the page and its total can never disagree.
     *
     * Written once and interpolated into both statements rather than repeated, because a filter
     * that is applied to the rows and not to the count produces a table whose paginator promises
     * pages that are empty — and each of the three clauses is independently easy to add to one and
     * forget in the other.
     *
     * Every filter is **null-permissive**: a parameter that is null means "no filter", so one
     * statement serves the unfiltered table, the search box, and the project filter, without any
     * of them being assembled from fragments (R10).
     *
     * The search is deliberately narrow — the key and the name, which is `<key>: <summary>`. Spec
     * §13.2 puts the escape hatch in the Cypher console rather than offering JQL here, and a
     * `CONTAINS` across 145 arbitrary properties per issue is a table scan a user can trigger by
     * typing.
     */
    private const val ISSUE_FILTER: String = """
        WHERE (${'$'}q IS NULL OR toLower(i.$KEY) CONTAINS ${'$'}q OR toLower(i.$ITEM_NAME) CONTAINS ${'$'}q)
          AND (${'$'}projectKeys IS NULL OR i.$PROJECT_KEY IN ${'$'}projectKeys)
    """

    /**
     * The columns of the issues table, for one page (spec §14.4).
     *
     * ## The dynamic part, and why it is not string-built Cypher
     *
     * `[k IN ${'$'}fieldIds | coalesce(i[k], p[k])]` reads a runtime-chosen set of properties with
     * a **parameter**, not with a statement assembled per request. That is the whole reason the
     * storage design keys properties by JIRA field id (§7.2): a configurable column set costs one
     * list parameter instead of a Cypher builder, and R10 is intact by construction rather than by
     * review.
     *
     * `coalesce(i[k], p[k])` is §7.4's rule — the issue's own value, or the display scalar its
     * projection derived for a value too complex to sort on. The order matters: a projection entry
     * only exists where the issue's value is a JSON blob, so the issue always wins where it has
     * anything to say.
     *
     * ## Ordering
     *
     * `${'$'}sortField` is a property name, so it cannot be a parameter of `ORDER BY` — but it can
     * be one of a dynamic *property access*, which is what this uses. `coalesce(…, '')` puts an
     * issue that lacks the sorted property at the start of the ascending order rather than dropping
     * it: a row missing from a table because a cell is empty is the worst available answer.
     *
     * The direction is the one thing here that is not a parameter, because Cypher has no way to
     * make it one. Two statements, [LIST_ISSUES_DESC] being this one with `DESC`, and the choice
     * made in Kotlin from a validated enum — never by interpolating a string that arrived over
     * HTTP.
     *
     * `__sortKey` is the tie-break and the default, so a page is stable: without a total order,
     * `SKIP`/`LIMIT` over rows that compare equal can show one issue on two pages and another on
     * none.
     *
     * The issue type's *id* is deliberately not returned. The only thing that wants it is the icon
     * proxy, which does not exist yet, and returning `__id` under the name `issueTypeId` would hand
     * whoever builds it the resource URL where JIRA's own numeric id was meant to be.
     */
    public const val LIST_ISSUES_ASC: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        $ISSUE_FILTER
        OPTIONAL MATCH (i)-[:$PROJECTION]->(p:$JIRA_PROJECTION)
        WITH i, p
        ORDER BY coalesce(i[${'$'}sortField], p[${'$'}sortField], '') ASC, i.$SORT_KEY ASC
        SKIP ${'$'}skip LIMIT ${'$'}limit
        OPTIONAL MATCH (i)-[:$HAS_ISSUE_TYPE]->(t:$JIRA_ISSUE_TYPE)
        RETURN i.$ID                             AS id,
               i.$KEY                            AS key,
               i.$ITEM_NAME                      AS name,
               (i:$UNDEFINED)                    AS unresolved,
               t.$ITEM_NAME                      AS issueTypeName,
               [k IN ${'$'}fieldIds | coalesce(i[k], p[k])] AS values
    """

    /** [LIST_ISSUES_ASC] with the direction reversed — see its note on why this is two statements. */
    public const val LIST_ISSUES_DESC: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        $ISSUE_FILTER
        OPTIONAL MATCH (i)-[:$PROJECTION]->(p:$JIRA_PROJECTION)
        WITH i, p
        ORDER BY coalesce(i[${'$'}sortField], p[${'$'}sortField], '') DESC, i.$SORT_KEY DESC
        SKIP ${'$'}skip LIMIT ${'$'}limit
        OPTIONAL MATCH (i)-[:$HAS_ISSUE_TYPE]->(t:$JIRA_ISSUE_TYPE)
        RETURN i.$ID                             AS id,
               i.$KEY                            AS key,
               i.$ITEM_NAME                      AS name,
               (i:$UNDEFINED)                    AS unresolved,
               t.$ITEM_NAME                      AS issueTypeName,
               [k IN ${'$'}fieldIds | coalesce(i[k], p[k])] AS values
    """

    /**
     * How many issues the same filter matches — the paginator's denominator.
     *
     * A separate cheap count rather than `collect()`-ing the rows to size them, which would read
     * every issue in the database to tell a user there are 784 of them.
     */
    public const val COUNT_ISSUES_MATCHING: String = """
        CYPHER 25
        MATCH (i:$JIRA_ISSUE)
        $ISSUE_FILTER
        RETURN count(i) AS total
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

    // -- the field catalogue and the chosen columns (spec §13.3, §13.4) ----------------------------

    /**
     * Every field the picker may offer (spec §9.2, §13.3).
     *
     * **Fields with no schema are excluded here rather than in Kotlin**, and the two the reference
     * instance has are the reason: `issuekey` duplicates the fixed Key column and `thumbnail` is
     * not a data field. Neither can be rendered as a column, so a picker that listed them would be
     * offering a choice that cannot be honoured — and the filter belongs where the absence is
     * legible, next to the write that deliberately omits the key (`fieldRow`).
     *
     * Ordered by name so the dialog opens on a list a person can scan; the ambiguity marker the
     * dialog appends comes from the ids, which the API layer resolves, not from this.
     *
     * **`fieldId` is JIRA's own `id`, not `__id`.** A field's `__id` is the synthesised resource URL
     * this application gives every node (§6.2); the thing a column is keyed by, and the thing that
     * is also the issue's property key, is `summary` or `customfield_18201`. The two are one
     * character apart in a statement and nothing downstream can tell them apart — a column keyed by
     * a URL simply reads every cell as null.
     */
    public const val LIST_FIELDS: String = """
        CYPHER 25
        MATCH (f:$JIRA_FIELD)
        WHERE f.$SCHEMA_TYPE IS NOT NULL
        RETURN f.$JIRA_ID AS fieldId,
               f.$NAME AS name,
               f.$CUSTOM AS custom,
               f.$SCHEMA_TYPE AS schemaType,
               f.$SCHEMA_ITEMS AS schemaItems
        ORDER BY name ASC, fieldId ASC
    """

    /**
     * The catalogue entries for one list of ids — the configured columns' names and types.
     *
     * A field the catalogue no longer has simply does not come back, and **that absence is the
     * whole point**: it is what the API layer turns into `stale: true` rather than into a column
     * that quietly disappeared (spec §13.4). So this is deliberately not an `IN` list that would
     * be reported as an error when short — a short answer is the answer.
     *
     * Order is not preserved and must not be relied on: the user's column order lives in
     * `:$JIRA_COLUMN_CONFIG` and the caller re-imposes it.
     */
    public const val FIND_FIELDS: String = """
        CYPHER 25
        MATCH (f:$JIRA_FIELD)
        WHERE f.$JIRA_ID IN ${'$'}fieldIds
        RETURN f.$JIRA_ID AS fieldId,
               f.$NAME AS name,
               f.$CUSTOM AS custom,
               f.$SCHEMA_TYPE AS schemaType,
               f.$SCHEMA_ITEMS AS schemaItems
    """

    /** The chosen columns, in the user's order (spec §10.2). Empty until the picker is used. */
    public const val LOAD_COLUMNS: String = """
        CYPHER 25
        MATCH (c:$JIRA_COLUMN_CONFIG {$ID: ${'$'}id})
        RETURN c.$FIELD_IDS AS fieldIds
    """

    /**
     * Replace the chosen columns.
     *
     * The whole list, never a merge: the order is part of the value, and a merge would have to
     * invent a rule for where a newly ticked column goes — the same argument [SAVE_SETTINGS] makes
     * about project keys. The fixed columns are never in it (spec §10.2); they are a backend
     * constant precisely so a bad write cannot remove them.
     */
    public const val SAVE_COLUMNS: String = """
        CYPHER 25
        MERGE (c:$JIRA_COLUMN_CONFIG {$ID: ${'$'}id})
        SET c.$FIELD_IDS = ${'$'}fieldIds,
            c.$UPDATED_AT = ${'$'}updatedAt,
            c.$UPDATED_BY = ${'$'}updatedBy
    """
}
