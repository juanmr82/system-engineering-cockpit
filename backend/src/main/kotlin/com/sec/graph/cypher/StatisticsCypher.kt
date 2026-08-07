package com.sec.graph.cypher

/**
 * Cypher for docs/features/requirements-statistics.md.
 *
 * Every statement is CYPHER 25-prefixed, parameterised, and carries a `LIMIT`; the transaction
 * timeout is applied to every session in graph/Read.kt, so nothing here can be issued without one
 * (CLAUDE.md §5, §7).
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
        MATCH (m:DOORSModule)
        WHERE ${'$'}moduleId IS NULL OR m.__id = ${'$'}moduleId
        OPTIONAL MATCH (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
        RETURN m.__id   AS id,
               m.__name AS name,
               c.code   AS levelCode
        ORDER BY m.__name
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
     * Ordered by `__sortKey` so that a truncated scan is the first N objects in document order
     * rather than an arbitrary N — truncation is reported, and it should also be reproducible.
     */
    public const val MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})
        WHERE NOT o:DOORSModule
        WITH o
        ORDER BY o.__sortKey
        LIMIT ${'$'}limit
        RETURN o         AS object,
               labels(o) AS labels,
               COUNT { (o)-[:refersTo]->(t:SEItem) WHERE NOT t:__UNDEFINED } AS resolvedParents,
               COUNT { (o)-[:refersTo]->(t:SEItem) WHERE t:__UNDEFINED }     AS placeholderParents
    """

    /** Counted separately so a truncated object scan still reports an honest total. */
    public const val COUNT_MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})
        WHERE NOT o:DOORSModule
        RETURN count(o) AS total
    """

    /**
     * Which modules this one points into that have not been imported (§6.2).
     *
     * A placeholder carries the `__moduleUrl` of the module it belongs to, so the target module can
     * be named even when its node does not exist yet — in which case `name` is null and the view
     * says so rather than inventing one.
     */
    public const val DANGLING_TARGET_MODULES: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})-[:refersTo]->(t:SEItem)
        WHERE t:__UNDEFINED AND NOT o:DOORSModule
        WITH DISTINCT t.__moduleUrl AS moduleUrl
        OPTIONAL MATCH (m:DOORSModule {__id: moduleUrl})
        RETURN moduleUrl AS id,
               m.__name  AS name
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
        MATCH (a:SEItem)-[:refersTo]->(b:SEItem)
        RETURN a.__id AS fromId,
               b.__id AS toId
        ORDER BY fromId, toId
        LIMIT ${'$'}limit
    """

    /**
     * The display detail for the members of the loops actually found — never for the whole graph.
     *
     * The system-level badge is resolved from the node's **owning module**, not from the node: a
     * classification is anchored on the `:DOORSModule` (CLAUDE.md §2, Shape A). Same shape as
     * `BreakdownCypher.NODES`, which the finding list links into.
     */
    public const val LOOP_MEMBERS: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (n:SEItem {__id: id})
        OPTIONAL MATCH (m:DOORSModule {__id: n.__moduleUrl})
        OPTIONAL MATCH (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
        RETURN n.__id      AS id,
               n.id        AS sourceId,
               n.__name    AS name,
               labels(n)   AS labels,
               m.__id      AS moduleId,
               m.__name    AS moduleName,
               c.code      AS levelCode
    """
}
