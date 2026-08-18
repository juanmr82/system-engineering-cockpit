package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.ID
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
 *
 * What is **not** here is the statement that loads the nodes: that is `RequirementCardCypher`,
 * shared with the dependency graph, because both draw the same card and one card shape may have
 * only one query behind it (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §5.1).
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
    public val EDGES_UP: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (s:$SE_ITEM {$ID: id})-[:$REFERS_TO]->(t:$SE_ITEM)
        WHERE ${AccessCypher.visible("s")} AND ${AccessCypher.visible("t")}
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
    public val EDGES_DOWN: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (t:$SE_ITEM {$ID: id})<-[:$REFERS_TO]-(s:$SE_ITEM)
        WHERE ${AccessCypher.visible("t")} AND ${AccessCypher.visible("s")}
        RETURN s.$ID         AS fromId,
               id            AS toId,
               s.$ID         AS nextId,
               NOT s:$UNDEFINED AS nextResolved
        ORDER BY fromId, toId
        LIMIT ${'$'}limit
    """
}
