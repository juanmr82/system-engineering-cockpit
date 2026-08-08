package com.sec.graph.cypher

// Cypher for docs/DOORS_TABLES.md §4.1 — reconstructing DOORS tables from the `__child` hierarchy.
// Every statement is CYPHER 25-prefixed and parameterised; the transaction timeout is applied to
// every session in graph/Read.kt, so nothing here can be issued without one.
//
// Two rules run through all of it:
//
//  - **Bracket access `c['Object Text']`, never a backtick and never string concatenation.** DOORS
//    attribute names carry spaces, dots, slashes, parentheses and umlauts — `REQ. Priorität`,
//    `RFD/RFW`, `DXL for Out-links (AKA)`. `Object Text` is the one such name this feature is
//    allowed to spell out at all (§9), and it is spelled out in exactly these two places.
//  - **Never `properties(c)`.** An object row is 88+ properties wide and one module holds hundreds
//    of cells; only what a table actually draws is fetched, which is `Object Text` and geometry.
public object TableCypher {

    // Selection is by **label**, not by the raw `__table*` properties. The importer already
    // classified the three structural roles as additive labels, and the properties are exactly what
    // §2.1 says not to trust: the export has a corrupt-key defect and the importer omits an index
    // it cannot parse, so `null` is normal. The indices come back only as a cross-check.
    //
    // Two OPTIONAL MATCHes rather than a variable-length path: a table is exactly two `__child`
    // levels deep, and stating that is what makes an unexpected third level visible as a nested
    // table instead of silently flattening into the parent's cells (§3.6).
    //
    // ORDER BY the three sort keys, never objectNumber: the outline number does not sort correctly
    // as a string, which is the entire reason `__sortKey` exists (R3). The keys stay server-side —
    // the geometry needs them to know what came before what, and the client is never handed one.
    public const val MODULE_TABLES: String = """
        CYPHER 25
        MATCH (t:DOORSTable {__moduleUrl: ${'$'}moduleUrl})
        OPTIONAL MATCH (t)-[:__child]->(r)
        OPTIONAL MATCH (r)-[:__child]->(c)
        RETURN t.__id                              AS tableItemId,
               t.id                                AS tableDoorsId,
               t.objectNumber                      AS tableObjectNumber,
               r.__id                              AS rowItemId,
               r.id                                AS rowDoorsId,
               r.objectNumber                      AS rowObjectNumber,
               labels(r)                           AS rowLabels,
               r['Object Text']                     AS rowText,
               r.__tableRowIndex                   AS rowExportedRowIndex,
               c.__id                              AS cellItemId,
               c.id                                AS cellDoorsId,
               c.objectNumber                      AS cellObjectNumber,
               labels(c)                           AS cellLabels,
               c['Object Text']                     AS cellText,
               c.__tableRowIndex                   AS cellExportedRowIndex,
               c.__tableColumnIndex                AS cellExportedColumnIndex
        ORDER BY t.__sortKey, r.__sortKey, c.__sortKey
    """

    /**
     * The `:DOORSTable` that owns an object, given the table, a row or any cell (§4.3).
     *
     * Prefers the graph — `(t:DOORSTable)-[:__child*0..2]->(i)` — because the hierarchy is what the
     * geometry is derived from, and falls back to the object's own `__tableURL` only when the walk
     * finds nothing, which is the case a re-import in progress can produce. Returning both lets the
     * caller tell "resolved structurally" from "resolved by property" and raise
     * `ORPHAN_TABLE_MEMBER` when neither answers.
     */
    public const val RESOLVE_TABLE: String = """
        CYPHER 25
        MATCH (i:SEItem {__id: ${'$'}itemId})
        OPTIONAL MATCH (t:DOORSTable)-[:__child*0..2]->(i)
        WITH i, t
        ORDER BY t.__sortKey
        RETURN i.__moduleUrl AS moduleUrl,
               t.__id        AS tableItemId,
               i.__tableURL  AS fallbackTableItemId
        LIMIT 1
    """
}
