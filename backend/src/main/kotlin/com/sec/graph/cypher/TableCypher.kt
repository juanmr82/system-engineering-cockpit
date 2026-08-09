package com.sec.graph.cypher

import com.sec.domain.NodeLabel.DELETED
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Rel.CHILD
import com.sec.source.doors.DoorsAttr.ID as DOORS_ID
import com.sec.source.doors.DoorsAttr.OBJECT_NUMBER
import com.sec.source.doors.DoorsAttr.OBJECT_TEXT
import com.sec.source.doors.DoorsLabel.TABLE as DOORS_TABLE
import com.sec.source.doors.DoorsProp.TABLE_COLUMN_INDEX
import com.sec.source.doors.DoorsProp.TABLE_ROW_INDEX
import com.sec.source.doors.DoorsProp.TABLE_URL

// Cypher for docs/DOORS_TABLES.md §4.1 — reconstructing DOORS tables from the derived hierarchy.
// Every statement is CYPHER 25-prefixed and parameterised; the transaction timeout is applied to
// every session in graph/Read.kt, so nothing here can be issued without one.
//
// Two rules run through all of it:
//
//  - **Bracket access `c['...']`, never a backtick and never string concatenation.** DOORS
//    attribute names carry spaces, dots, slashes, parentheses and umlauts — `REQ. Priorität`,
//    `RFD/RFW`, `DXL for Out-links (AKA)`. The name itself is interpolated from `DoorsAttr`, so
//    it is not spelled out here at all: a DOORS administrator renaming the attribute is one edit
//    in `source/doors/DoorsNames.kt` (ADR 0010). Every other graph name is interpolated too.
//  - **Never `properties(c)`.** An object row is 88+ properties wide and one module holds hundreds
//    of cells; only what a table actually draws is fetched, which is `Object Text` and geometry.
public object TableCypher {

    // Selection is by **label**, not by the raw exported table properties. The importer already
    // classified the three structural roles as additive labels, and the properties are exactly what
    // §2.1 says not to trust: the export has a corrupt-key defect and the importer omits an index
    // it cannot parse, so `null` is normal. The indices come back only as a cross-check.
    //
    // Two OPTIONAL MATCHes rather than a variable-length path: a table is exactly two hierarchy
    // levels deep, and stating that is what makes an unexpected third level visible as a nested
    // table instead of silently flattening into the parent's cells (§3.6).
    //
    // ORDER BY the three sort keys, never objectNumber: the outline number does not sort correctly
    // as a string, which is the entire reason the sort key exists (R3). The keys stay server-side —
    // the geometry needs them to know what came before what, and the client is never handed one.
    public const val MODULE_TABLES: String = """
        CYPHER 25
        MATCH (t:$DOORS_TABLE {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT t:$DELETED
        OPTIONAL MATCH (t)-[:$CHILD]->(r)
        OPTIONAL MATCH (r)-[:$CHILD]->(c)
        RETURN t.$ID                  AS tableItemId,
               t['$DOORS_ID']         AS tableDoorsId,
               t['$OBJECT_NUMBER']    AS tableObjectNumber,
               r.$ID                  AS rowItemId,
               r['$DOORS_ID']         AS rowDoorsId,
               r['$OBJECT_NUMBER']    AS rowObjectNumber,
               labels(r)              AS rowLabels,
               r['$OBJECT_TEXT']      AS rowText,
               r.$TABLE_ROW_INDEX     AS rowExportedRowIndex,
               c.$ID                  AS cellItemId,
               c['$DOORS_ID']         AS cellDoorsId,
               c['$OBJECT_NUMBER']    AS cellObjectNumber,
               labels(c)              AS cellLabels,
               c['$OBJECT_TEXT']      AS cellText,
               c.$TABLE_ROW_INDEX     AS cellExportedRowIndex,
               c.$TABLE_COLUMN_INDEX  AS cellExportedColumnIndex
        ORDER BY t.$SORT_KEY, r.$SORT_KEY, c.$SORT_KEY
    """

    /**
     * The table that owns an object, given the table, a row or any cell (§4.3).
     *
     * Prefers the graph — a bounded walk down the derived hierarchy — because the hierarchy is what
     * the geometry is derived from, and falls back to the object's own exported table url only when
     * the walk finds nothing, which is the case a re-import in progress can produce. Returning both
     * lets the caller tell "resolved structurally" from "resolved by property" and raise
     * `ORPHAN_TABLE_MEMBER` when neither answers.
     */
    public const val RESOLVE_TABLE: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})
        OPTIONAL MATCH (t:$DOORS_TABLE)-[:$CHILD*0..2]->(i)
        WITH i, t
        ORDER BY t.$SORT_KEY
        RETURN i.$MODULE_URL AS moduleUrl,
               t.$ID         AS tableItemId,
               i.$TABLE_URL  AS fallbackTableItemId
        LIMIT 1
    """
}
