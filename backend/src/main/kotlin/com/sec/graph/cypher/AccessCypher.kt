package com.sec.graph.cypher

import com.sec.domain.GroupProp.FIRST_SEEN_AT
import com.sec.domain.GroupProp.KEY as GROUP_KEY
import com.sec.domain.GroupProp.LAST_SEEN_AT
import com.sec.domain.GroupProp.NAME as GROUP_NAME
import com.sec.domain.GroupProp.SEES_ALL
import com.sec.domain.MetaProp.EVERY_GROUP
import com.sec.domain.NodeLabel.ACCESS_CATEGORY
import com.sec.domain.NodeLabel.GROUP
import com.sec.domain.Prop.META_ID
import com.sec.domain.Rel.IN_ACCESS_CATEGORY
import com.sec.domain.Rel.MAY_READ

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
}
