package com.sec.graph.cypher

import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.CODE
import com.sec.domain.MetaProp.SCHEME
import com.sec.domain.MetaProp.VERIFICATION
import com.sec.domain.MetaValue.SYSTEM_LEVEL_SCHEME
import com.sec.domain.NodeLabel.ATTRIBUTE_SETTING
import com.sec.domain.NodeLabel.CLASSIFICATION
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME
import com.sec.domain.Rel.ATTRIBUTE_SETTING_FOR
import com.sec.domain.Rel.CLASSIFIED_AS
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsRel.REFERS_TO

/**
 * Cypher for docs/requirement-breakdown-tree.md — the Breakdown tab's DAG walk.
 *
 * The traversal is **one query per level**, driven from Kotlin, rather than one variable-length
 * pattern. Neo4j does not accept a parameter as a variable-length bound (a parameterised `*1..n`
 * is a syntax error), so a single-statement version would have to bake a literal upper bound in —
 * and then a client asking for `maxDepth=2` would still pay for the deeper walk, which is exactly
 * the guarantee §9 criterion 10 asks for. Level-at-a-time makes both bounds real: the loop stops
 * at `maxDepth`, and every statement below carries a `LIMIT` besides.
 *
 * Every statement is CYPHER 25-prefixed and parameterised, and every graph name is interpolated
 * from a constant (ADR 0010). The transaction timeout is applied to every session in graph/Read.kt,
 * so nothing here can be issued without one (CLAUDE.md §5, §7).
 */
public object BreakdownCypher {

    /**
     * One level of the climb: every outgoing `refersTo` from the current frontier.
     *
     * `from refines to` (§2) — an outgoing edge is read as "this requirement refines its target",
     * so the target is the parent in the rendered tree.
     *
     * `nextResolved` is what stops the walk at a placeholder. An unresolved node stands for an
     * object no import has reached, so it has no ancestry to climb: it is a legitimate leaf and its
     * further ancestry is *unknown*, not empty (§7).
     */
    public const val EDGES_UP: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (:$SE_ITEM {$ID: id})-[:$REFERS_TO]->(t:$SE_ITEM)
        RETURN id            AS fromId,
               t.$ID         AS toId,
               t.$ID         AS nextId,
               NOT t:$UNDEFINED AS nextResolved
        ORDER BY fromId, toId
        LIMIT ${'$'}limit
    """

    /**
     * One level of the descent: every incoming `refersTo`, i.e. everything that refines these.
     *
     * Both statements are ordered before their `LIMIT`. The client's primary-parent rule breaks a
     * tie by taking "the first parent in the order the API returned its outgoing edges" (§3), and
     * an unordered result makes that order a property of the planner rather than of the data —
     * the same tree would then draw differently between two identical requests.
     */
    public const val EDGES_DOWN: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (:$SE_ITEM {$ID: id})<-[:$REFERS_TO]-(s:$SE_ITEM)
        RETURN s.$ID         AS fromId,
               id            AS toId,
               s.$ID         AS nextId,
               NOT s:$UNDEFINED AS nextResolved
        ORDER BY fromId, toId
        LIMIT ${'$'}limit
    """

    /**
     * Every node of the closure, fetched once at the end rather than level by level.
     *
     * The system-level badge is resolved from the node's **owning module**, not from the node —
     * a classification is anchored on the module node (CLAUDE.md §2, Shape A). A module with no
     * classification yields a null code and the row simply renders no chip (§2).
     */
    public const val NODES: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (n:$SE_ITEM {$ID: id})
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: n.$MODULE_URL})
        OPTIONAL MATCH (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        RETURN n            AS node,
               labels(n)    AS labels,
               m.$ID        AS moduleId,
               m.$NAME      AS moduleName,
               c.$CODE      AS levelCode
    """

    /**
     * Which attributes each module in the closure has flagged as verification attributes
     * (`REQ_REVIEW.md` §9.2).
     *
     * Read fresh on every call and never stored: a module reconfigured between two clicks answers
     * correctly on the second click with no migration (§6). Scoped to the modules the closure
     * actually touches, which is a handful even for a wide tree.
     */
    public const val VERIFICATION_ATTRIBUTES: String = """
        CYPHER 25
        UNWIND ${'$'}moduleIds AS moduleId
        MATCH (:$DOORS_MODULE {$ID: moduleId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE s.$VERIFICATION = true
        RETURN moduleId          AS moduleId,
               s.$ATTRIBUTE_NAME AS name
        ORDER BY name
    """
}
