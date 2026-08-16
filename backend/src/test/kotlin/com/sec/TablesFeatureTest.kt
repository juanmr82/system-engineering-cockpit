package com.sec

import com.sec.api.dto.TableAnomalyKind
import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.security.AccessSet
import com.sec.source.doors.DoorsTableProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/DOORS_TABLES.md §8, the integration half: the query, the fold from flat records back into a
 * hierarchy, and the upward resolution from a cell — against a real Neo4j **Community** image
 * (CLAUDE.md §7, §11).
 *
 * The geometry rules themselves are covered without a database in `TableGeometryTest`; what is
 * being tested here is everything between Cypher and that pure function. The fixture is a **seeded
 * scratch graph**, never a live module (`HANDOVER.md` §1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class TablesFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var tables: DoorsTableProjection

    private val moduleId = "tables-module"
    private val tableId = "doors://t-1"
    private val emptyTableId = "doors://t-2"

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking {
            MetaSchema.apply(graphDriver)
            seed()
        }
        tables = DoorsTableProjection(graphDriver)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    /**
     * Two tables in one module, shaped like a real export.
     *
     * The first is a 2×3 table whose rows and cells are **created out of document order**, so that
     * `ORDER BY __sortKey` is actually doing something rather than accidentally agreeing with
     * creation order. One cell carries `__tableColumnIndex` disagreeing with its outline number
     * (§2.1). The second table has no children at all.
     *
     * Cells also carry an unrelated attribute, which the query must **not** fetch: a table draws
     * `Object Text` and nothing else, and `properties(c)` on an 88-property object row is exactly
     * what §4.1 forbids.
     *
     * A plain requirement sits alongside them so the module-scoped query has something to *not*
     * return.
     */
    private suspend fun seed() {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (m:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}mid, __name: 'SRD', __version: 'current', url: ${'$'}mid
                })
                CREATE (:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'doors://req-1', __moduleUrl: ${'$'}mid, __name: 'SRD-1', __version: 'current',
                    __sortKey: '000001', id: 'SRD-1', objectNumber: '1', objectLevel: 1,
                    `Object Text`: 'The system shall do X'
                })

                CREATE (t:DOORSObject:DOORSTBD:DOORSTable:SEItem {
                    __id: ${'$'}tid, __moduleUrl: ${'$'}mid, __name: 'SRD-998', __version: 'current',
                    __sortKey: '000002.000001.000000-000001', id: 'SRD-998', objectNumber: '2.1.0-1',
                    __tableObject: false, `AR-BS Method`: 'Analysis'
                })
                CREATE (r2:DOORSObject:DOORSTBD:DOORSTableRow:SEItem {
                    __id: 'doors://r-2', __moduleUrl: ${'$'}mid, __name: 'SRD-1181', __version: 'current',
                    __sortKey: '000002.000001.000000-000001.000000-000002', id: 'SRD-1181',
                    objectNumber: '2.1.0-1.0-2', __tableObject: false, __tableRowIndex: 1
                })
                CREATE (r1:DOORSObject:DOORSTBD:DOORSTableRow:SEItem {
                    __id: 'doors://r-1', __moduleUrl: ${'$'}mid, __name: 'SRD-1171', __version: 'current',
                    __sortKey: '000002.000001.000000-000001.000000-000001', id: 'SRD-1171',
                    objectNumber: '2.1.0-1.0-1', __tableObject: false, __tableRowIndex: 0
                })
                CREATE (t)-[:__child]->(r1)
                CREATE (t)-[:__child]->(r2)
                """.trimIndent(),
                mapOf("mid" to moduleId, "tid" to tableId),
            ),
        ) { }

        // Cells, created back-to-front so document order can only come from __sortKey.
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (r1:DOORSTableRow {__id: 'doors://r-1'})
                MATCH (r2:DOORSTableRow {__id: 'doors://r-2'})
                UNWIND ${'$'}cells AS cell
                CREATE (c:DOORSObject:DOORSTBD:DOORSTableCell:SEItem {
                    __id: cell.id, __moduleUrl: ${'$'}mid, __name: cell.name, __version: 'current',
                    __sortKey: cell.sortKey, id: cell.doorsId, objectNumber: cell.objectNumber,
                    __tableObject: true, __tableURL: ${'$'}tid,
                    __tableRowIndex: cell.rowIndex, __tableColumnIndex: cell.columnIndex,
                    `Object Text`: cell.text
                })
                WITH c, cell, r1, r2
                FOREACH (_ IN CASE WHEN cell.row = 1 THEN [1] ELSE [] END | CREATE (r1)-[:__child]->(c))
                FOREACH (_ IN CASE WHEN cell.row = 2 THEN [1] ELSE [] END | CREATE (r2)-[:__child]->(c))
                """.trimIndent(),
                mapOf(
                    "mid" to moduleId,
                    "tid" to tableId,
                    "cells" to (2 downTo 1).flatMap { row ->
                        (3 downTo 1).map { column ->
                            mapOf(
                                "id" to "doors://c-$row-$column",
                                "name" to "SRD-cell-$row-$column",
                                "doorsId" to "SRD-${1170 + row * 10 + column}",
                                "objectNumber" to "2.1.0-1.0-$row.0-$column",
                                "sortKey" to "000002.000001.000000-000001.000000-00000$row" +
                                    ".000000-00000$column",
                                "row" to row,
                                "rowIndex" to row - 1,
                                // The one deliberate defect: cell (1,3) claims column 1 in the
                                // export. The outline number must still decide.
                                "columnIndex" to if (row == 1 && column == 3) 0 else column - 1,
                                "text" to "r${row}c$column",
                            )
                        }
                    },
                ),
            ),
        ) { }

        // An attribute the table must not surface, and the empty table.
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (c:DOORSTableCell {__id: 'doors://c-2-2'})
                SET c.`AR-BS Method` = 'Inspection'
                CREATE (:DOORSObject:DOORSTBD:DOORSTable:SEItem {
                    __id: ${'$'}emptyId, __moduleUrl: ${'$'}mid, __name: 'SRD-999', __version: 'current',
                    __sortKey: '000003.000000-000001', id: 'SRD-999', objectNumber: '3.0-1',
                    __tableObject: false
                })
                """.trimIndent(),
                mapOf("mid" to moduleId, "emptyId" to emptyTableId),
            ),
        ) { }
    }

    // --- The module query ---------------------------------------------------------------------------

    @Test
    fun `a module's tables come back reconstructed, in document order, with no cell dropped`() = runBlocking {
        val views = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL)

        assertEquals(2, views.size)
        // __sortKey order: 2.1.0-1 before 3.0-1.
        assertEquals(listOf(Ref.encode(tableId), Ref.encode(emptyTableId)), views.map { it.ref })

        val table = views.first()
        assertEquals(2, table.rowCount)
        assertEquals(3, table.columnCount)
        assertEquals(6, table.rows.sumOf { row -> row.cells.count { it.present } })
        assertEquals(
            listOf("r1c1", "r1c2", "r1c3", "r2c1", "r2c2", "r2c3"),
            table.rows.flatMap { row -> row.cells.map { it.text } },
        )
    }

    // Creation order was deliberately reversed, so passing this means __sortKey — not insertion
    // order and not objectNumber, which does not sort correctly as a string — did the ordering.
    @Test
    fun `rows and cells arrive in sort-key order, not creation order`() = runBlocking {
        val table = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL).first()

        assertEquals(listOf(1, 2), table.rows.map { it.rowNumber })
        assertEquals("SRD-1171", table.rows[0].id)
        assertEquals("SRD-1181", table.rows[1].id)
    }

    @Test
    fun `the first row is the header row`() = runBlocking {
        val table = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL).first()

        assertEquals(1, table.headerRowCount)
        assertTrue(table.rows[0].isHeader)
        assertFalse(table.rows[1].isHeader)
    }

    // §2.1: the exported index is a cross-check that raises a finding, never a placement decision.
    // Cell (1,3) claims column 1 in the export; it must still be drawn in column 3.
    @Test
    fun `a disagreeing exported column index is reported and the outline number still wins`() = runBlocking {
        val table = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL).first()

        assertEquals("r1c3", table.rows[0].cells[2].text)
        val mismatch = table.anomalies.single { it.kind == TableAnomalyKind.INDEX_MISMATCH }
        assertEquals("SRD-1183", mismatch.id)
    }

    @Test
    fun `a table with no children is returned and reported empty rather than omitted`() = runBlocking {
        val empty = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL).single { it.ref == Ref.encode(emptyTableId) }

        assertEquals(0, empty.rowCount)
        assertEquals(0, empty.columnCount)
        assertTrue(empty.rows.isEmpty())
        assertEquals(listOf(TableAnomalyKind.EMPTY_TABLE), empty.anomalies.map { it.kind })
    }

    /**
     * A table draws its cells' `Object Text` and nothing else.
     *
     * The seed puts `AR-BS Method` on the table object and on one cell. Neither may reach the
     * payload: §6.3's outer display columns are deliberately not implemented, and this is what says
     * so — a rule nobody can test is a rule that comes back by accident.
     */
    @Test
    fun `no attribute but Object Text reaches the payload`() = runBlocking {
        val rendered = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL).toString()

        assertFalse(rendered.contains("Analysis"), rendered)
        assertFalse(rendered.contains("Inspection"), rendered)
        assertTrue(rendered.contains("r1c1"), rendered)
    }

    @Test
    fun `requirements outside a table are not part of any table`() = runBlocking {
        val refs = tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL)
            .flatMap { view -> view.rows.flatMap { row -> row.cells.mapNotNull { it.ref } } }

        assertFalse(refs.contains(Ref.encode("doors://req-1")))
    }

    // --- Upward resolution (§4.3) ---------------------------------------------------------------------

    @Test
    fun `a cell resolves upward to the table that owns it`() = runBlocking {
        val fromCell = tables.getTableFor("doors://c-2-3", access = AccessSet.SEES_ALL)

        assertNotNull(fromCell)
        assertEquals(Ref.encode(tableId), fromCell.ref)
        assertEquals(3, fromCell.columnCount)
    }

    @Test
    fun `a row and the table itself resolve to the same table`() = runBlocking {
        assertEquals(Ref.encode(tableId), tables.getTableFor("doors://r-2", access = AccessSet.SEES_ALL)?.ref)
        assertEquals(Ref.encode(tableId), tables.getTableFor(tableId, access = AccessSet.SEES_ALL)?.ref)
    }

    @Test
    fun `an object that does not exist is not found, and is not confused with an orphan`() = runBlocking {
        assertNull(tables.getTableFor("doors://nope", access = AccessSet.SEES_ALL))
    }

    /**
     * A cell whose table has gone: `ORPHAN_TABLE_MEMBER`, not a 404.
     *
     * An object that is visibly part of a table and has lost it is a finding a systems engineer
     * has to see. Reporting it as "not found" would hide exactly the case the anomaly exists for.
     */
    @Test
    fun `a cell with no reachable table is an orphan, not a missing object`() = runBlocking {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (:DOORSObject:DOORSTBD:DOORSTableCell:SEItem {
                    __id: 'doors://orphan-cell', __moduleUrl: ${'$'}mid, __name: 'orphan',
                    __version: 'current', __sortKey: '000009', id: 'SRD-9999',
                    objectNumber: '9.0-1', __tableObject: true, __tableURL: ''
                })
                """.trimIndent(),
                mapOf("mid" to moduleId),
            ),
        ) { }

        val view = tables.getTableFor("doors://orphan-cell", access = AccessSet.SEES_ALL)

        assertNotNull(view)
        assertEquals(
            listOf(TableAnomalyKind.ORPHAN_TABLE_MEMBER),
            view.anomalies.map { it.kind },
        )

        graphDriver.executeWrite(
            Query("CYPHER 25 MATCH (n:SEItem {__id: 'doors://orphan-cell'}) DETACH DELETE n", emptyMap()),
        ) { }
    }

    // --- The read-only guarantee (R1) -------------------------------------------------------------------

    /**
     * Rendering a table is derivation at read time and writes nothing (§9, R1).
     *
     * This is the byte-identical-anchor test applied to a read path: everything above has run
     * against these nodes, and the table object's property map must be exactly what the seed wrote.
     */
    @Test
    fun `rendering a table writes nothing back to the imported nodes`() = runBlocking {
        val before = properties(tableId)
        val cellBefore = properties("doors://c-1-3")

        tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL)
        tables.getTableFor("doors://c-1-3", access = AccessSet.SEES_ALL)

        assertEquals(before, properties(tableId))
        assertEquals(cellBefore, properties("doors://c-1-3"))
    }

    @Test
    fun `rendering the same module twice produces an identical payload`() = runBlocking {
        assertEquals(
            tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL),
            tables.getModuleTables(moduleId, access = AccessSet.SEES_ALL),
        )
    }

    private suspend fun properties(itemId: String): Map<String, Any> =
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (n:SEItem {__id: \$id}) RETURN n", mapOf("id" to itemId)),
        ) { records -> records.single().get("n").asNode().asMap() }
}
