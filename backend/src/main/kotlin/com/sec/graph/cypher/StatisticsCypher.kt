package com.sec.graph.cypher

import com.sec.domain.MetaProp.CODE
import com.sec.domain.MetaProp.SCHEME
import com.sec.domain.MetaValue.SYSTEM_LEVEL_SCHEME
import com.sec.domain.NodeLabel.CLASSIFICATION
import com.sec.domain.NodeLabel.DELETED
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Rel.CLASSIFIED_AS
import com.sec.source.doors.DoorsAttr.ID as DOORS_ID
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT
import com.sec.source.doors.DoorsRel.REFERS_TO

/**
 * Cypher for docs/features/requirements-statistics.md.
 *
 * Every statement is CYPHER 25-prefixed, parameterised, carries a `LIMIT`, and interpolates every
 * graph name from a constant (ADR 0010); the transaction timeout is applied to every session in
 * graph/Read.kt, so nothing here can be issued without one (CLAUDE.md §5, §7).
 *
 * Two statements this file deliberately does **not** contain: the mandatory-attribute policies and
 * the per-module attribute settings. Those already exist as `ReviewCypher.MANDATORY_POLICIES` and
 * `ReviewCypher.EXISTING_ATTRIBUTE_SETTINGS` and are reused verbatim — a second copy would be a
 * second definition of what a mandatory attribute is (§3.2).
 */
public object StatisticsCypher {

    /**
     * The modules in scope, with the system level the orphan metric is read from.
     *
     * A null `moduleId` means every module. Passing the filter as a null-tolerant parameter rather
     * than building two statements keeps one query plan and one place to change the projection.
     */
    public const val MODULES_IN_SCOPE: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE)
        WHERE ${'$'}moduleId IS NULL OR m.$ID = ${'$'}moduleId
        OPTIONAL MATCH (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        RETURN m.$ID   AS id,
               m.$NAME AS name,
               c.$CODE AS levelCode
        ORDER BY m.$NAME
        LIMIT ${'$'}limit
    """

    /**
     * One pass over a module's objects, carrying everything Bands 1–3 need.
     *
     * The whole property map comes back because the completeness checks run in Kotlin against the
     * shared rule rather than as Cypher aggregates (§3.2) — that is the cost of the two views
     * never disagreeing, and it is paid knowingly.
     *
     * The two parent counts are computed here rather than by returning the edges, because the
     * orphan metric only needs to know *whether* a resolved parent exists and *whether* every
     * parent is a placeholder. Returning ~2 600 edges to answer a three-way split would be the
     * expensive way to compute a boolean.
     *
     * Ordered by the sort key so that a truncated scan is the first N objects in document order
     * rather than an arbitrary N — truncation is reported, and it should also be reproducible.
     */
    public const val MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DOORS_MODULE AND NOT o:$DELETED
        WITH o
        ORDER BY o.$SORT_KEY
        LIMIT ${'$'}limit
        RETURN o         AS object,
               labels(o) AS labels,
               COUNT { (o)-[:$REFERS_TO]->(t:$SE_ITEM) WHERE NOT t:$UNDEFINED } AS resolvedParents,
               COUNT { (o)-[:$REFERS_TO]->(t:$SE_ITEM) WHERE t:$UNDEFINED }     AS placeholderParents,
               COUNT { (o)-[:$REFERS_TO]-(t:$SE_ITEM) WHERE t:$DELETED }        AS deletedLinks
    """

    /** Counted separately so a truncated object scan still reports an honest total. */
    public const val COUNT_MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DOORS_MODULE AND NOT o:$DELETED
        RETURN count(o) AS total
    """

    /**
     * Which modules this one points into that have not been imported (§6.2).
     *
     * A placeholder carries the module url of the module it belongs to, so the target module can
     * be named even when its node does not exist yet — in which case `name` is null and the view
     * says so rather than inventing one.
     */
    public const val DANGLING_TARGET_MODULES: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})-[:$REFERS_TO]->(t:$SE_ITEM)
        WHERE t:$UNDEFINED AND NOT o:$DOORS_MODULE AND NOT o:$DELETED
        WITH DISTINCT t.$MODULE_URL AS moduleUrl
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: moduleUrl})
        RETURN moduleUrl AS id,
               m.$NAME   AS name
        ORDER BY name, id
        LIMIT ${'$'}limit
    """

    /**
     * The whole `refersTo` edge set, for loop detection.
     *
     * Unfiltered by module **on purpose** (§7.2). A cycle that leaves a module and comes back is
     * the most likely kind and the hardest to see by hand; filtering the edge set to the selected
     * module would hide exactly those. The module filter is applied to the *findings* afterwards.
     *
     * Ordered so the SCC input is stable, and therefore so is the rendered finding list.
     */
    public const val ALL_TRACE_EDGES: String = """
        CYPHER 25
        MATCH (a:$SE_ITEM)-[:$REFERS_TO]->(b:$SE_ITEM)
        RETURN a.$ID AS fromId,
               b.$ID AS toId
        ORDER BY fromId, toId
        LIMIT ${'$'}limit
    """

    /**
     * The display detail for the members of the loops actually found — never for the whole graph.
     *
     * The system-level badge is resolved from the node's **owning module**, not from the node: a
     * classification is anchored on the module node (CLAUDE.md §2, Shape A). Same shape as
     * `BreakdownCypher.NODES`, which the finding list links into.
     */
    public const val LOOP_MEMBERS: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (n:$SE_ITEM {$ID: id})
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: n.$MODULE_URL})
        OPTIONAL MATCH (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        RETURN n.$ID       AS id,
               n.$DOORS_ID AS sourceId,
               n.$NAME     AS name,
               labels(n)   AS labels,
               m.$ID       AS moduleId,
               m.$NAME     AS moduleName,
               c.$CODE     AS levelCode
    """
}
