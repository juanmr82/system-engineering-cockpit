package com.sec.graph.cypher

import com.sec.domain.AccessDefaultProp.CONTAINER_LABEL
import com.sec.domain.AccessDefaultProp.SOURCE_ID
import com.sec.domain.AccessOrigin.DIRECT
import com.sec.domain.AccessOrigin.INHERITED
import com.sec.domain.AccessRelProp.ORIGIN
import com.sec.domain.AccessRelProp.VIA
import com.sec.domain.GroupProp.FIRST_SEEN_AT
import com.sec.domain.GroupProp.KEY as GROUP_KEY
import com.sec.domain.GroupProp.LAST_SEEN_AT
import com.sec.domain.GroupProp.NAME as GROUP_NAME
import com.sec.domain.GroupProp.SEES_ALL
import com.sec.domain.MetaKind.ACCESS_CATEGORY as ACCESS_CATEGORY_KIND
import com.sec.domain.MetaProp.DESCRIPTION
import com.sec.domain.MetaProp.EVERY_GROUP
import com.sec.domain.MetaProp.KEY as ACCESS_CATEGORY_KEY
import com.sec.domain.MetaProp.NAME as ACCESS_CATEGORY_NAME
import com.sec.domain.MetaValue.CURRENT_SCHEMA_VERSION
import com.sec.domain.NodeLabel.ACCESS_CATEGORY
import com.sec.domain.NodeLabel.ACCESS_DEFAULT
import com.sec.domain.NodeLabel.GROUP
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.CREATED_AT
import com.sec.domain.Prop.CREATED_BY
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.META_ID
import com.sec.domain.Prop.META_KIND
import com.sec.domain.Prop.NAME
import com.sec.domain.Prop.SCHEMA_VERSION
import com.sec.domain.Prop.UPDATED_AT
import com.sec.domain.Prop.UPDATED_BY
import com.sec.domain.Rel.ACCESS_SEEDED
import com.sec.domain.Rel.ASSIGNS
import com.sec.domain.Rel.IN_ACCESS_CATEGORY
import com.sec.domain.Rel.MAY_READ
import com.sec.security.Containment

// Cypher for docs/features/access-control.md §5 (the resolver) and §6 (the predicate). Every
// statement is CYPHER 25-prefixed and parameterised, per CLAUDE.md §5.
public object AccessCypher {

    /**
     * Token groups to an [com.sec.security.AccessSet], in one query (§5).
     *
     * `$groupKeys` is never empty here — [com.sec.security.AccessResolver] answers the empty-groups
     * case itself, without a query, so a user in no group never runs this statement at all.
     *
     * The `:__Group` mirror is written on every cache miss, not on every request (§5 "Caching");
     * `ON CREATE` seeds [FIRST_SEEN_AT] and defaults the display name to the key, so an access
     * manager sees the group at all before ever renaming it.
     */
    public const val RESOLVE_GROUPS: String = """
        CYPHER 25
        UNWIND ${'$'}groupKeys AS key
        MERGE (g:$GROUP {$GROUP_KEY: key})
          ON CREATE SET g.$GROUP_NAME = key, g.$SEES_ALL = false, g.$FIRST_SEEN_AT = ${'$'}now
        SET g.$LAST_SEEN_AT = ${'$'}now
        WITH collect(g) AS groups
        UNWIND groups AS g
        OPTIONAL MATCH (g)-[:$MAY_READ]->(granted:$ACCESS_CATEGORY)
        WITH groups, collect(granted.$META_ID) AS grantedIds
        OPTIONAL MATCH (everyone:$ACCESS_CATEGORY {$EVERY_GROUP: true})
        WITH groups, grantedIds, collect(everyone.$META_ID) AS everyoneIds
        RETURN any(g IN groups WHERE g.$SEES_ALL) AS seesAll,
               grantedIds + everyoneIds          AS categoryIds
    """

    /**
     * The visibility predicate for a bound node alias — the **only** way authorization reaches a
     * query (§6.1). [com.sec.security.AccessGuardTest] looks for the literal `/*ACL*/` marker this
     * emits, so nothing may reproduce this WHERE clause by hand.
     *
     * Form A (a property comparison per candidate node), not form B (categories pinned once via a
     * threaded `WITH`): measured per ADR 0016 §8 against a 984-object module at several `$acl`
     * sizes. Form B's `any(cat IN acl WHERE EXISTS {...})` re-probes once per entry in `$acl`, so
     * its cost scales with the number of categories a caller's groups collectively grant, not just
     * with module size; form A's cost does not. B only wins at a single granted category, which is
     * the narrow case rather than the common one. Form A ships for every filtered statement.
     *
     * Being an expression rather than a clause is what lets it go anywhere a predicate can — a
     * `WHERE` on the primary `MATCH`, both ends of an edge statement, an `OPTIONAL MATCH`, and the
     * `WHERE` of a pattern comprehension, which is the one place a filter is easiest to forget
     * (`ReviewCypher.MODULE_OBJECTS`'s reference lists are built that way).
     *
     * The subquery's own variable is **`aclCat`, not `c`**, and the name is load-bearing. An
     * `EXISTS { }` imports every variable bound outside it, so declaring one that shadows an outer
     * binding is a Cypher error rather than a shadowing warning — and `c` is exactly what several
     * statements already bind for the system-level `:__Classification` (`RequirementCardCypher`,
     * `ModuleCypher`, `StatisticsCypher`). A predicate that cannot be dropped into an arbitrary
     * statement is a predicate that will be reproduced by hand at the one call site it does not fit.
     */
    public fun visible(alias: String): String =
        "/*ACL*/ (${'$'}seesAll OR EXISTS { " +
            "($alias)-[:$IN_ACCESS_CATEGORY]->(aclCat:$ACCESS_CATEGORY) " +
            "WHERE aclCat.$META_ID IN ${'$'}acl" +
            " })"

    /**
     * [AccessReconciler]'s propagate step (§8.3): every member a [containment]'s container directly
     * carries a category on, and does not yet carry itself, gets it as [INHERITED].
     *
     * Not called for a [Containment.containerless] source — there is no container to propagate
     * *from*; [seed] alone answers "does this item get a category" for one of those.
     *
     * A **returning** `CALL … IN TRANSACTIONS`, not a unit subquery: [Containment.memberMatch] can
     * multiply one `(c, cat)` input row into hundreds of `o` matches, and a unit subquery collapses
     * that back to one output row per input regardless — batching by 10 000 members, and counting
     * them for the caller, both need the inner `RETURN`.
     *
     * Binds `${'$'}user` and `${'$'}now`; every name below is a single interpolated constant
     * except [Containment.containerLabel] and [Containment.memberMatch] themselves, which are
     * already built from constants at their own declaration site (`AccessContainment.kt`).
     */
    public fun propagate(containment: Containment): String {
        check(!containment.containerless) { "${containment.sourceId} has no container to propagate from" }
        return """
            CYPHER 25
            MATCH (c:${containment.containerLabel})-[:$IN_ACCESS_CATEGORY {$ORIGIN: '$DIRECT'}]->(cat:$ACCESS_CATEGORY)
            CALL (c, cat) {
              MATCH ${containment.memberMatch}
              WHERE NOT EXISTS { (o)-[:$IN_ACCESS_CATEGORY]->(cat) }
              CREATE (o)-[:$IN_ACCESS_CATEGORY {
                $ORIGIN: '$INHERITED', $VIA: c.$ID, $CREATED_BY: ${'$'}user, $CREATED_AT: ${'$'}now
              }]->(cat)
              RETURN count(*) AS created
            } IN TRANSACTIONS OF 10000 ROWS
            RETURN coalesce(sum(created), 0) AS n
            """.trimIndent()
    }

    /**
     * [AccessReconciler]'s retract step (§8.3): an [INHERITED] tag whose container no longer
     * carries the matching [DIRECT] one is removed. Scoped by container, like [propagate] — the
     * pipeline hook reconciles only the containers one import run touched (§8.3 "Scope it").
     */
    public fun retract(containment: Containment): String {
        check(!containment.containerless) { "${containment.sourceId} has no container to retract from" }
        return """
            CYPHER 25
            MATCH (c:${containment.containerLabel})
            CALL (c) {
              MATCH ${containment.memberMatch}
              MATCH (o)-[r:$IN_ACCESS_CATEGORY {$ORIGIN: '$INHERITED'}]->(cat)
              WHERE r.$VIA = c.$ID AND NOT EXISTS { (c)-[:$IN_ACCESS_CATEGORY {$ORIGIN: '$DIRECT'}]->(cat) }
              DELETE r
              RETURN count(*) AS retracted
            } IN TRANSACTIONS OF 10000 ROWS
            RETURN coalesce(sum(retracted), 0) AS n
            """.trimIndent()
    }

    /**
     * [AccessReconciler]'s seed step (§8.3): a container — or, [Containment.containerless], an
     * item — that has never carried a direct category and has never been seeded before gets its
     * source's default. With no `:__AccessDefault` configured for
     * `(${'$'}sourceId, ${'$'}containerLabel)`, the first `MATCH` finds nothing and the whole
     * statement is a no-op — "empty is the default answer" (spec §10.2).
     *
     * [$sourceId][com.sec.domain.AccessDefaultProp.SOURCE_ID] and
     * [$containerLabel][com.sec.domain.AccessDefaultProp.CONTAINER_LABEL] are bound parameters,
     * not interpolated — they are *data* the default node is matched against, never a label token.
     */
    public fun seed(containment: Containment): String {
        // The thing being seeded: a bound container `(c:Label)`, or — containerless — the item
        // pattern itself, which already binds `o` and never mentions `c` (AccessContainment.kt).
        val (target, seeded) = if (containment.containerless) {
            containment.memberMatch to "o"
        } else {
            "(c:${containment.containerLabel})" to "c"
        }
        return """
            CYPHER 25
            MATCH (def:$ACCESS_DEFAULT { $SOURCE_ID: ${'$'}sourceId, $CONTAINER_LABEL: ${'$'}containerLabel })
                  -[:$ASSIGNS]->(cat:$ACCESS_CATEGORY)
            WITH cat
            MATCH $target
            WHERE NOT EXISTS { ($seeded)-[:$IN_ACCESS_CATEGORY {$ORIGIN: '$DIRECT'}]->() }
              AND NOT EXISTS { ($seeded)-[:$ACCESS_SEEDED]->() }
            CALL ($seeded, cat) {
              CREATE ($seeded)-[:$IN_ACCESS_CATEGORY {
                $ORIGIN: '$DIRECT', $CREATED_BY: ${'$'}user, $CREATED_AT: ${'$'}now
              }]->(cat)
              CREATE ($seeded)-[:$ACCESS_SEEDED]->(cat)
              RETURN count(*) AS n
            } IN TRANSACTIONS OF 10000 ROWS
            RETURN coalesce(sum(n), 0) AS n
            """.trimIndent()
    }

    // -- Categories (spec §9, §10.2 screen 1) — AccessAdminService, phase 6 --------------------

    /**
     * Every category, with how many objects carry it and how many groups may read it — the
     * Categories screen's table.
     *
     * Touches `:SEItem` from the category side, which [security.AccessGuardTest] would otherwise
     * refuse: exempted per §13 ("no read path may start from a category node... the Access view's
     * object counts are the exception — compute them in the background or accept them as slow, and
     * never on a page a normal user loads"). This is exactly that page.
     */
    public val CATEGORIES_WITH_COUNTS: String = """
        CYPHER 25
        MATCH (c:$ACCESS_CATEGORY)
        OPTIONAL MATCH (c)<-[:$IN_ACCESS_CATEGORY]-(o:$SE_ITEM)
        WITH c, count(DISTINCT o) AS objectCount
        OPTIONAL MATCH (c)<-[:$MAY_READ]-(g:$GROUP)
        RETURN c.$META_ID AS metaId, c.$ACCESS_CATEGORY_KEY AS key, c.$ACCESS_CATEGORY_NAME AS name,
               c.$DESCRIPTION AS description, c.$EVERY_GROUP AS everyGroup,
               objectCount, count(DISTINCT g) AS groupCount
        ORDER BY c.$ACCESS_CATEGORY_NAME
    """

    /**
     * Pre-check before [CREATE_CATEGORY]: a 409 with a stated reason (spec §9), not the raw
     * constraint violation `access_category_key` would otherwise throw.
     */
    public val CATEGORY_KEY_EXISTS: String = """
        CYPHER 25
        MATCH (c:$ACCESS_CATEGORY {$ACCESS_CATEGORY_KEY: ${'$'}key})
        RETURN count(c) AS n
    """

    public val CREATE_CATEGORY: String = """
        CYPHER 25
        CREATE (c:$META:$ACCESS_CATEGORY {
            $META_ID: ${'$'}metaId,
            $META_KIND: '$ACCESS_CATEGORY_KIND',
            $SCHEMA_VERSION: $CURRENT_SCHEMA_VERSION,
            $ACCESS_CATEGORY_KEY: ${'$'}key,
            $ACCESS_CATEGORY_NAME: ${'$'}name,
            $DESCRIPTION: ${'$'}description,
            $EVERY_GROUP: ${'$'}everyGroup,
            $CREATED_BY: ${'$'}user, $CREATED_AT: ${'$'}now,
            $UPDATED_BY: ${'$'}user, $UPDATED_AT: ${'$'}now
        })
        RETURN c.$META_ID AS metaId
    """

    /**
     * `key` is never in this statement — stable once created; "rename" (spec §10.2) means
     * [ACCESS_CATEGORY_NAME]. `coalesce` against the existing value is what makes every field
     * optional in the request without a separate read-modify-write.
     *
     * Returns the full row [CATEGORIES_WITH_COUNTS] would, so the dialog echoes back the stored
     * state — including counts, unaffected by a rename but read fresh rather than assumed — without
     * a second round trip. No rows means `$metaId` matched nothing: the caller reads that as
     * `NotFound`.
     */
    public val UPDATE_CATEGORY: String = """
        CYPHER 25
        MATCH (c:$ACCESS_CATEGORY {$META_ID: ${'$'}metaId})
        SET c.$ACCESS_CATEGORY_NAME = coalesce(${'$'}name, c.$ACCESS_CATEGORY_NAME),
            c.$DESCRIPTION = coalesce(${'$'}description, c.$DESCRIPTION),
            c.$EVERY_GROUP = coalesce(${'$'}everyGroup, c.$EVERY_GROUP),
            c.$UPDATED_BY = ${'$'}user,
            c.$UPDATED_AT = ${'$'}now
        WITH c
        OPTIONAL MATCH (c)<-[:$IN_ACCESS_CATEGORY]-(o:$SE_ITEM)
        WITH c, count(DISTINCT o) AS objectCount
        OPTIONAL MATCH (c)<-[:$MAY_READ]-(g:$GROUP)
        RETURN c.$META_ID AS metaId, c.$ACCESS_CATEGORY_KEY AS key, c.$ACCESS_CATEGORY_NAME AS name,
               c.$DESCRIPTION AS description, c.$EVERY_GROUP AS everyGroup,
               objectCount, count(DISTINCT g) AS groupCount
    """

    /**
     * The 409 message's counts (spec §9: "409 if any object or grant still references it"). Same
     * §13 exemption class as [CATEGORIES_WITH_COUNTS] — read once, before attempting the delete, so
     * the frontend's pre-empt (decided in the phase-6 plan, §6.2) has real numbers to show.
     */
    public val CATEGORY_USAGE_COUNTS: String = """
        CYPHER 25
        MATCH (c:$ACCESS_CATEGORY {$META_ID: ${'$'}metaId})
        OPTIONAL MATCH (c)<-[:$IN_ACCESS_CATEGORY]-(o:$SE_ITEM)
        WITH c, count(DISTINCT o) AS objectCount
        OPTIONAL MATCH (c)<-[:$MAY_READ]-(g:$GROUP)
        RETURN objectCount, count(DISTINCT g) AS groupCount
    """

    /**
     * Deletes only if nothing still references the category. The frontend pre-empts this with
     * [CATEGORY_USAGE_COUNTS], so this is the defensive backstop against the window between that
     * read and this write, not the primary UX.
     *
     * Unlabeled `EXISTS` — an existence guard, not a data read, so it carries no `/*ACL*/` marker.
     */
    public val DELETE_CATEGORY_IF_UNUSED: String = """
        CYPHER 25
        MATCH (c:$ACCESS_CATEGORY {$META_ID: ${'$'}metaId})
        WHERE NOT EXISTS { (c)<-[:$IN_ACCESS_CATEGORY]-() }
          AND NOT EXISTS { (c)<-[:$MAY_READ]-() }
        DETACH DELETE c
        RETURN count(c) AS deleted
    """

    // -- Groups & Grants (spec §9, §10.2 screen 2) — AccessAdminService, phase 6 ---------------

    /**
     * Every group ever seen, with its granted category ids and its `seesAll` flag — the Grants
     * screen's rows (spec §9: "every `:__Group` ever seen, with its grants and seesAll"). The
     * matrix itself is built client-side from this plus [CATEGORIES_WITH_COUNTS]; "saving is per
     * row" (§10.2) is exactly why there is no server-built matrix shape to maintain.
     *
     * Neither `:__Group` nor `:__AccessCategory` is a filtered label, so this needs no exemption.
     */
    public val GROUPS_WITH_GRANTS: String = """
        CYPHER 25
        MATCH (g:$GROUP)
        OPTIONAL MATCH (g)-[:$MAY_READ]->(cat:$ACCESS_CATEGORY)
        RETURN g.$GROUP_KEY AS key, g.$GROUP_NAME AS name, g.$SEES_ALL AS seesAll,
               g.$FIRST_SEEN_AT AS firstSeenAt, g.$LAST_SEEN_AT AS lastSeenAt,
               collect(cat.$META_ID) AS categoryIds
        ORDER BY g.$GROUP_NAME
    """

    /** One group, in the same shape [GROUPS_WITH_GRANTS] returns every row in — read back after
     *  [REPLACE_GRANTS] so the caller echoes stored state rather than assumed state. */
    public val GROUP_WITH_GRANTS: String = """
        CYPHER 25
        MATCH (g:$GROUP {$GROUP_KEY: ${'$'}groupKey})
        OPTIONAL MATCH (g)-[:$MAY_READ]->(cat:$ACCESS_CATEGORY)
        RETURN g.$GROUP_KEY AS key, g.$GROUP_NAME AS name, g.$SEES_ALL AS seesAll,
               g.$FIRST_SEEN_AT AS firstSeenAt, g.$LAST_SEEN_AT AS lastSeenAt,
               collect(cat.$META_ID) AS categoryIds
    """

    /**
     * A group's `:__Group` node exists only once the resolver has seen it sign in at least once
     * (§5 "Caching" — `RESOLVE_GROUPS`'s own `MERGE`), so this is the pre-check that turns "a group
     * name typed by hand that nobody has signed in with yet" into a stated `GroupNotFound` rather
     * than a write that silently touches nothing.
     */
    public val GROUP_EXISTS: String = """
        CYPHER 25
        MATCH (g:$GROUP {$GROUP_KEY: ${'$'}groupKey})
        RETURN count(g) AS n
    """

    /**
     * Which of the requested category ids do not exist — the same "may only reference what
     * actually exists" discipline `MetaWriter`'s own writes apply to an arbitrary id in a request
     * body. An empty `${'$'}categoryIds` (revoking every grant) still returns one row, `unknown: []`
     * — `collect` is an aggregate and produces exactly one row over zero input rows.
     */
    public val UNKNOWN_CATEGORY_IDS: String = """
        CYPHER 25
        UNWIND ${'$'}categoryIds AS wanted
        OPTIONAL MATCH (c:$ACCESS_CATEGORY {$META_ID: wanted})
        WITH wanted, c
        WHERE c IS NULL
        RETURN collect(wanted) AS unknown
    """

    /**
     * The whole grant set for one group, replaced in one transaction (R7): every grant not in
     * `${'$'}categoryIds` is removed, every category in it is granted, and a grant already present
     * is left untouched — `ON CREATE` only, so its own `__createdAt` never moves on a resave.
     *
     * Write-only, no `RETURN`: [GROUP_WITH_GRANTS] reads the result back afterward, the same
     * write-then-read-back shape `MetaWriter.saveComments` already uses.
     */
    public val REPLACE_GRANTS: String = """
        CYPHER 25
        MATCH (g:$GROUP {$GROUP_KEY: ${'$'}groupKey})
        OPTIONAL MATCH (g)-[r:$MAY_READ]->(cat:$ACCESS_CATEGORY)
        WHERE NOT cat.$META_ID IN ${'$'}categoryIds
        DELETE r
        WITH DISTINCT g
        UNWIND ${'$'}categoryIds AS wanted
        MATCH (cat:$ACCESS_CATEGORY {$META_ID: wanted})
        MERGE (g)-[r:$MAY_READ]->(cat)
          ON CREATE SET r.$CREATED_BY = ${'$'}user, r.$CREATED_AT = ${'$'}now
    """

    /**
     * `seesAll` only — "audited loudly" (spec §9), a deliberately separate write from
     * [REPLACE_GRANTS] so the one control that bypasses grants entirely is never folded into an
     * ordinary grant edit. Returns the group in [GROUPS_WITH_GRANTS]'s shape, same reasoning as
     * [GROUP_WITH_GRANTS].
     */
    public val SET_SEES_ALL: String = """
        CYPHER 25
        MATCH (g:$GROUP {$GROUP_KEY: ${'$'}groupKey})
        SET g.$SEES_ALL = ${'$'}seesAll
        WITH g
        OPTIONAL MATCH (g)-[:$MAY_READ]->(cat:$ACCESS_CATEGORY)
        RETURN g.$GROUP_KEY AS key, g.$GROUP_NAME AS name, g.$SEES_ALL AS seesAll,
               g.$FIRST_SEEN_AT AS firstSeenAt, g.$LAST_SEEN_AT AS lastSeenAt,
               collect(cat.$META_ID) AS categoryIds
    """

    // -- Unassigned containers & direct categories (spec §9, §10.2 screen 3) — phase 6 ---------

    /**
     * Every container of [containerLabel] with no direct category, and how many of its members
     * carry no category at all — the Unassigned queue's rows for one containment group.
     *
     * **Deliberately exempt from [visible]**, per §16.2a: an access manager who cannot yet grant
     * themselves a category could otherwise never find the container to grant one to. It returns
     * container-level metadata only — never a contained item — which is what keeps the exemption
     * narrow.
     *
     * [memberMatches] is every [Containment.memberMatch] that shares [containerLabel] — DOORS
     * modules are both `doors.objects` and `doors.placeholders` (§16.1a), and counting only one
     * would either double-list the module or drop its placeholders from the count. Each match gets
     * its own `CALL (c) { }` unit subquery, summed in the final `RETURN`, rather than a single
     * pattern with an `OR` — the match patterns are shaped differently per source (a property match
     * vs. a traversed edge) and a union of counts is simpler than a union of patterns.
     *
     * `${'$'}sourceId` is bound rather than interpolated even though it is the same literal for
     * every row a call returns: every containment sharing one [containerLabel] shares one
     * `sourceId` today (DOORS is the only source with more than one containment, and both of its
     * containments are `sourceId: "doors"`), so this is data the caller already knows, not
     * something the query decides.
     */
    public fun unassignedContainers(containerLabel: String, memberMatches: List<String>): String {
        val calls = memberMatches.mapIndexed { i, match ->
            """
            CALL (c) {
              MATCH $match
              WHERE NOT EXISTS { (o)-[:$IN_ACCESS_CATEGORY]->() }
              RETURN count(o) AS n$i
            }
            """.trimIndent()
        }.joinToString("\n")
        val sum = memberMatches.indices.joinToString(" + ") { "n$it" }

        return """
            CYPHER 25
            MATCH (c:$containerLabel)
            WHERE NOT EXISTS { (c)-[:$IN_ACCESS_CATEGORY {$ORIGIN: '$DIRECT'}]->() }
            $calls
            RETURN ${'$'}sourceId AS sourceId, c.$ID AS containerId, c.$NAME AS name,
                   ($sum) AS invisibleItemCount
            ORDER BY c.$NAME
            """.trimIndent()
    }

    /**
     * Whether *any* node — of any label — carries this `__id`. Unlabeled and unfiltered on
     * purpose: the container/item category writes below check only that the anchor exists, never
     * the caller's own visibility, for the same reason [unassignedContainers] is exempt from
     * [visible] — the caller found this id through the unassigned queue precisely because they
     * cannot yet see it any other way.
     */
    public val EXISTS_BY_ID: String = """
        CYPHER 25
        MATCH (n {$ID: ${'$'}id})
        RETURN count(n) AS n
    """

    /**
     * The whole *direct* category set for one anchor — a container or, via the same statement, the
     * single-item escape hatch (spec §8.1) — replaced in one transaction (R7).
     *
     * A container's outgoing `__inAccessCategory` edges are always `origin: 'direct'` by
     * construction (inheritance only ever flows container → member, never onto the container
     * itself), so this one unlabeled-anchor pair serves `PUT /access/containers/{ref}/categories`
     * and `PUT /access/items/{ref}/categories` verbatim — there is no second version for items.
     *
     * `ON MATCH SET` promotes an existing `inherited` edge to `direct` when the caller re-requests
     * the same category on an item that already carries it by inheritance — the escape hatch
     * surviving reconcile (§8.1), not a duplicate edge. Its audit fields move to this write too:
     * once a human has made the tag deliberate, its provenance is the grant, not the propagation
     * that happened to create the edge first.
     */
    public val REPLACE_DIRECT_CATEGORIES: String = """
        CYPHER 25
        MATCH (n {$ID: ${'$'}anchorId})
        OPTIONAL MATCH (n)-[r:$IN_ACCESS_CATEGORY {$ORIGIN: '$DIRECT'}]->(cat:$ACCESS_CATEGORY)
        WHERE NOT cat.$META_ID IN ${'$'}categoryIds
        DELETE r
        WITH DISTINCT n
        UNWIND ${'$'}categoryIds AS wanted
        MATCH (cat:$ACCESS_CATEGORY {$META_ID: wanted})
        MERGE (n)-[r:$IN_ACCESS_CATEGORY]->(cat)
          ON CREATE SET r.$ORIGIN = '$DIRECT', r.$CREATED_BY = ${'$'}user, r.$CREATED_AT = ${'$'}now
          ON MATCH SET r.$ORIGIN = '$DIRECT', r.$CREATED_BY = ${'$'}user, r.$CREATED_AT = ${'$'}now
    """

    /** Read back after [REPLACE_DIRECT_CATEGORIES] — the anchor's direct categories only, same
     *  "echo stored state" reasoning as [GROUP_WITH_GRANTS]. */
    public val DIRECT_CATEGORIES_OF: String = """
        CYPHER 25
        MATCH (n {$ID: ${'$'}anchorId})
        OPTIONAL MATCH (n)-[:$IN_ACCESS_CATEGORY {$ORIGIN: '$DIRECT'}]->(cat:$ACCESS_CATEGORY)
        RETURN collect(cat.$META_ID) AS categoryIds
    """

    // -- Import defaults (spec §9, §10.2 screen 4) — AccessAdminService, phase 6 ---------------

    /**
     * Every `:__AccessDefault` that has ever been set, with the category it assigns if any. The
     * service merges this against the full `(sourceId, containerLabel)` set from
     * [com.sec.security.AccessContainment.all] so a pair nobody has configured yet still appears,
     * `categoryId: null` — "empty is the default answer" (spec §10.2), not an absent row.
     */
    public val DEFAULTS_LIST: String = """
        CYPHER 25
        MATCH (def:$ACCESS_DEFAULT)
        OPTIONAL MATCH (def)-[:$ASSIGNS]->(cat:$ACCESS_CATEGORY)
        RETURN def.$SOURCE_ID AS sourceId, def.$CONTAINER_LABEL AS containerLabel, cat.$META_ID AS categoryId
    """

    /**
     * "New ‹source› ‹containers› are visible to nobody" — the null default, chosen by clearing
     * whatever `:__assigns` edge exists. `:__AccessDefault` has no uniqueness constraint of its
     * own (composite key, Community has nothing to lean on — same reasoning [MetaSchema] already
     * states), so `MERGE` on both properties is the only way to address one.
     */
    public val CLEAR_DEFAULT: String = """
        CYPHER 25
        MERGE (def:$ACCESS_DEFAULT {$SOURCE_ID: ${'$'}sourceId, $CONTAINER_LABEL: ${'$'}containerLabel})
        WITH def
        OPTIONAL MATCH (def)-[r:$ASSIGNS]->()
        DELETE r
    """

    /** The chosen default category, replacing whatever was assigned before. */
    public val SET_DEFAULT: String = """
        CYPHER 25
        MERGE (def:$ACCESS_DEFAULT {$SOURCE_ID: ${'$'}sourceId, $CONTAINER_LABEL: ${'$'}containerLabel})
        WITH def
        OPTIONAL MATCH (def)-[r:$ASSIGNS]->()
        DELETE r
        WITH def
        MATCH (cat:$ACCESS_CATEGORY {$META_ID: ${'$'}categoryId})
        MERGE (def)-[:$ASSIGNS]->(cat)
    """

    // -- Summary (spec §9) — the Access dashboard, computed on read (R2) -----------------------

    /**
     * Category and group counts for the Access summary. Written as two `COUNT {}` subquery
     * expressions rather than `MATCH … WITH count() … MATCH … RETURN count()`: chaining two
     * `MATCH`es through a `WITH` makes every non-aggregated column an implicit `GROUP BY` key, so
     * zero groups in the graph would make the whole query return zero rows instead of one row
     * reading `0` — exactly the shape a caller doing `records.single()` cannot survive. Each
     * `COUNT {}` here is a standalone scalar subquery with no such row dependency between them.
     *
     * The unassigned-container count is not here: [AccessAdminService.summary] reuses
     * [AccessAdminService.listUnassignedContainers] directly rather than duplicating its
     * per-`containerLabel` aggregation in a second statement.
     */
    public val SUMMARY_COUNTS: String = """
        CYPHER 25
        RETURN COUNT { MATCH (c:$ACCESS_CATEGORY) } AS categoryCount,
               COUNT { MATCH (g:$GROUP) } AS groupCount
    """
}
