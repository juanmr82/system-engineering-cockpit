package com.sec.graph.cypher

import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.SORT_KEY
import com.sec.source.doors.DoorsRel.REFERS_TO

/**
 * Cypher for docs/REQ_BREAKDOWN_GRAPH_VIEW — the dependency graph's scope query.
 *
 * ## Two statements, used for three things
 *
 * The spec sketches one variable-length pattern with the depth baked into the string (§3.2). This
 * is a hop-at-a-time walk instead, driven from Kotlin, for three reasons that the sketch cannot
 * meet at once:
 *
 *  - **Neo4j does not accept a parameter as a variable-length bound**, so the sketch has to
 *    interpolate the depth into the statement. Interpolating a validated integer is legal but it is
 *    also unnecessary: nothing here is string-built, which is one fewer place to get wrong.
 *  - **The 300-node cap is breadth-first from the seeds** (§3.1). A variable-length match returns
 *    the closure in whatever order the planner produces it, so "the nodes closest to the seeds"
 *    would have to be recovered afterwards from path lengths the query did not return.
 *  - **The same two statements answer the induced-subgraph question and the cut-neighbour count.**
 *    Run over the *admitted* set rather than over a frontier, [OUT_NEIGHBOURS] returns every
 *    outgoing `refersTo` of every node in the picture: the ones landing inside it are the induced
 *    edges (§3.2), the ones landing outside it are what the cap and the depth bound cut off, which
 *    is what a node's `truncatedNeighbours` badge counts (§5.7).
 *
 * Both are ordered by `__sortKey` before their `LIMIT`, which is what makes the layout input
 * deterministic (§4.6): an unordered result makes the picture a property of the planner rather than
 * of the data, and the same scope would then draw differently between two identical requests.
 *
 * Every statement is CYPHER 25-prefixed and parameterised, and every graph name is interpolated
 * from a constant (ADR 0010). The transaction timeout is applied to every session in graph/Read.kt.
 */
public object DependencyGraphCypher {

    /**
     * Everything these items refer to — read, by this product's convention, as what they refine.
     *
     * `resolved` is false for a placeholder the importer created for an object no import has
     * reached. Those are drawn, as ghost cards, and they are the reason §1.1's banner exists.
     */
    public const val OUT_NEIGHBOURS: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (:$SE_ITEM {$ID: id})-[:$REFERS_TO]->(t:$SE_ITEM)
        RETURN id                 AS fromId,
               t.$ID              AS toId,
               t.$SORT_KEY        AS sortKey,
               NOT t:$UNDEFINED   AS resolved
        ORDER BY sortKey, toId
        LIMIT ${'$'}limit
    """

    /** Everything that refers to these items — what refines them. The mirror of [OUT_NEIGHBOURS]. */
    public const val IN_NEIGHBOURS: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (:$SE_ITEM {$ID: id})<-[:$REFERS_TO]-(s:$SE_ITEM)
        RETURN id                 AS toId,
               s.$ID              AS fromId,
               s.$SORT_KEY        AS sortKey,
               NOT s:$UNDEFINED   AS resolved
        ORDER BY sortKey, fromId
        LIMIT ${'$'}limit
    """

    /**
     * The seeds themselves, ordered, so the node list starts deterministically even before a hop.
     *
     * A seed that carries no node is simply absent, which is what turns a hand-edited ref into a
     * 404 rather than an empty picture presented as an answer.
     */
    public const val SEEDS: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (n:$SE_ITEM {$ID: id})
        RETURN n.$ID       AS id,
               n.$SORT_KEY AS sortKey
        ORDER BY sortKey, id
    """
}
