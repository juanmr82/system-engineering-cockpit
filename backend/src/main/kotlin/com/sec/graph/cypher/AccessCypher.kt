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
import com.sec.domain.MetaProp.EVERY_GROUP
import com.sec.domain.NodeLabel.ACCESS_CATEGORY
import com.sec.domain.NodeLabel.ACCESS_DEFAULT
import com.sec.domain.NodeLabel.GROUP
import com.sec.domain.Prop.CREATED_AT
import com.sec.domain.Prop.CREATED_BY
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.META_ID
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
     */
    public fun visible(alias: String): String =
        "/*ACL*/ (${'$'}seesAll OR EXISTS { " +
            "($alias)-[:$IN_ACCESS_CATEGORY]->(c:$ACCESS_CATEGORY) WHERE c.$META_ID IN ${'$'}acl" +
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
}
