package com.sec.source.doors

import com.sec.api.dto.DoorsTableViewDto
import com.sec.api.dto.TableAnomalyDto
import com.sec.api.dto.TableAnomalyKind
import com.sec.api.dto.TableAnomalySeverity
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.TableCypher
import com.sec.graph.executeRead
import org.neo4j.driver.Query
import org.neo4j.driver.Record

/**
 * The read projection for DOORS tables (docs/DOORS_TABLES.md §4).
 *
 * One round trip per module, flattened into `(table, row, cell)` triples by the query and folded
 * back into a hierarchy here, then handed to [TableGeometry] — which owns every rule about where a
 * cell goes and holds no driver import at all. This class is the I/O half and nothing more.
 *
 * DOORS-specific, so it lives here and nowhere else (CLAUDE.md §1). **Nothing it computes is
 * stored** (§9): a table's geometry is a function of the imported graph, and a stored derivation
 * goes stale silently the moment a re-import moves a cell.
 */
public class DoorsTableProjection(private val graphDriver: GraphDriver) {

    /** Every table of one module, in document order. */
    public suspend fun getModuleTables(moduleId: String): List<DoorsTableViewDto> {
        val sources = graphDriver.executeRead(
            Query(TableCypher.MODULE_TABLES, mapOf("moduleUrl" to moduleId)),
        ) { records -> fold(records) }

        return sources.map(TableGeometry::assemble)
    }

    /**
     * The table one object belongs to — given the table itself, one of its rows, or any cell (§4.3).
     *
     * Returns null when the object does not exist. When it exists but no `:DOORSTable` can be
     * reached from it and it carries no `__tableURL` either, the answer is not null but an
     * [TableAnomalyKind.ORPHAN_TABLE_MEMBER] view: an object that is visibly part of a table and
     * has lost its table is a finding, and reporting it as "not found" would hide it.
     */
    public suspend fun getTableFor(itemId: String): DoorsTableViewDto? {
        val resolved = graphDriver.executeRead(
            Query(TableCypher.RESOLVE_TABLE, mapOf("itemId" to itemId)),
        ) { records ->
            records.firstOrNull()?.let { record ->
                Resolution(
                    moduleUrl = record.string("moduleUrl"),
                    tableItemId = record.string("tableItemId"),
                    // The importer writes "" rather than omitting the property, so blank is absent.
                    fallbackTableItemId = record.string("fallbackTableItemId")?.takeIf { it.isNotBlank() },
                )
            }
        } ?: return null

        val tableId = resolved.tableItemId ?: resolved.fallbackTableItemId ?: return orphan(itemId)
        val moduleUrl = resolved.moduleUrl ?: return orphan(itemId)

        return getModuleTables(moduleUrl).firstOrNull { it.ref == Ref.encode(tableId) }
            ?: orphan(itemId)
    }

    private class Resolution(
        val moduleUrl: String?,
        val tableItemId: String?,
        val fallbackTableItemId: String?,
    )

    private fun orphan(itemId: String): DoorsTableViewDto =
        DoorsTableViewDto(
            ref = Ref.encode(itemId),
            objectNumber = "",
            rowCount = 0,
            columnCount = 0,
            headerRowCount = 0,
            anomalies = listOf(
                TableAnomalyDto(
                    kind = TableAnomalyKind.ORPHAN_TABLE_MEMBER,
                    severity = TableAnomalySeverity.ERROR,
                    message = "This object belongs to a table that is not in the graph, so the table " +
                        "cannot be drawn. Re-import the module it came from.",
                    ref = Ref.encode(itemId),
                ),
            ),
        )

    // --- Folding the flat result back into a hierarchy ---------------------------------------------

    /**
     * `(table, row, cell)` triples to [TableGeometry.SourceTable]s.
     *
     * `LinkedHashMap` throughout, because the query's `ORDER BY __sortKey` is the whole of what the
     * geometry knows about document order — the client is never handed a sort key (R5), so losing
     * the order here would lose it for good. The `OPTIONAL MATCH`es mean a childless table arrives
     * as one row with null row and cell columns, which is how an empty table stays visible instead
     * of vanishing from the result set.
     */
    private fun fold(records: List<Record>): List<TableGeometry.SourceTable> {
        val tables = LinkedHashMap<String, TableGeometry.SourceObject>()
        val rows = LinkedHashMap<String, LinkedHashMap<String, TableGeometry.SourceObject>>()
        val cells = LinkedHashMap<String, LinkedHashMap<String, TableGeometry.SourceObject>>()

        for (record in records) {
            val tableId = record.string("tableItemId") ?: continue
            tables.getOrPut(tableId) {
                TableGeometry.SourceObject(
                    itemId = tableId,
                    doorsId = record.string("tableDoorsId"),
                    objectNumber = record.string("tableObjectNumber").orEmpty(),
                    labels = setOf(DoorsLabel.TABLE),
                )
            }

            val rowId = record.string("rowItemId") ?: continue
            rows.getOrPut(tableId) { LinkedHashMap() }.getOrPut(rowId) {
                TableGeometry.SourceObject(
                    itemId = rowId,
                    doorsId = record.string("rowDoorsId"),
                    objectNumber = record.string("rowObjectNumber").orEmpty(),
                    labels = record.labels("rowLabels"),
                    text = record.string("rowText"),
                    exportedRowIndex = record.int("rowExportedRowIndex"),
                )
            }

            val cellId = record.string("cellItemId") ?: continue
            cells.getOrPut(rowId) { LinkedHashMap() }.getOrPut(cellId) {
                TableGeometry.SourceObject(
                    itemId = cellId,
                    doorsId = record.string("cellDoorsId"),
                    objectNumber = record.string("cellObjectNumber").orEmpty(),
                    labels = record.labels("cellLabels"),
                    text = record.string("cellText"),
                    exportedRowIndex = record.int("cellExportedRowIndex"),
                    exportedColumnIndex = record.int("cellExportedColumnIndex"),
                )
            }
        }

        return tables.map { (tableId, table) ->
            TableGeometry.SourceTable(
                table = table,
                children = rows[tableId].orEmpty().map { (rowId, row) ->
                    TableGeometry.SourceChild(node = row, children = cells[rowId].orEmpty().values.toList())
                },
            )
        }
    }

    // --- Driver value helpers ----------------------------------------------------------------------

    private fun Record.string(key: String): String? = get(key).takeUnless { it.isNull() }?.asString()

    private fun Record.int(key: String): Int? = get(key).takeUnless { it.isNull() }?.asInt()

    private fun Record.labels(key: String): Set<String> =
        get(key).takeUnless { it.isNull() }?.asList { it.asString() }?.toSet().orEmpty()

}
