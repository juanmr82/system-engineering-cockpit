package com.sec.source.windchill

import com.sec.domain.ItemVersion
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.WindchillCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

/**
 * The only thing that writes imported Windchill data, and it writes nothing else.
 *
 * The same structural guarantee `JiraGraphWriter` carries, for the same reason (ADR 0013): an
 * importer running inside this process cannot inherit R1's "the application never writes imported
 * data" from being a separate program, so it has to get it from confinement. This class is reached
 * only by [WindchillImporter], touches only [WindchillLabel.imported], and has no general-purpose
 * method taking a caller-chosen label or key — which is what would dissolve the arrangement back
 * into a convention.
 *
 * It is also the only place `__name`, `__version` and `__sortKey` are set for this source. The
 * derivations themselves are pure functions ([WindchillSortKey], [WindchillRecord]); this just
 * writes what they produced.
 */
public class WindchillGraphWriter(
    private val graphDriver: GraphDriver,
) {

    /** Constraints and indexes. Idempotent, and run at the start of every import. */
    public suspend fun applySchema() {
        // One statement per transaction: schema changes cannot share one with anything else.
        WindchillCypher.SCHEMA.forEach { statement ->
            graphDriver.executeWrite(Query(statement)) { }
        }
        logger.info { "Applied Windchill schema (${WindchillCypher.SCHEMA.size} statements)" }
    }

    /**
     * Writes the documents, in batches, and returns how many rows were sent.
     *
     * Each batch is its own transaction. On Community that is not a compromise but the right shape:
     * there is no query governor, and one transaction holding every row of a growing export is the
     * single query that can exhaust the instance. A run cancelled mid-way leaves whole batches
     * committed, which the run's own `CANCELLED` status is what says out loud.
     */
    public suspend fun upsertDocuments(records: List<WindchillRecord>): Int {
        records.map(::documentRow).chunked(BATCH_SIZE).forEach { chunk ->
            graphDriver.executeWrite(
                Query(
                    WindchillCypher.UPSERT_DOCUMENTS,
                    mapOf("rows" to chunk, "itemVersion" to ItemVersion.CURRENT),
                ),
            ) { }
        }
        return records.size
    }

    /** How many documents stand right now. Read either side of the sweep, for its warning. */
    public suspend fun documentCount(): Int =
        graphDriver.executeRead(Query(WindchillCypher.COUNT_DOCUMENTS)) { records ->
            records.firstOrNull()?.get("documents")?.asInt() ?: 0
        }

    /**
     * Removes every document the export did not contain, and the annotations hanging off them.
     *
     * **The caller must have read the whole file first.** A seen set that is short by omission is
     * indistinguishable here from a Windchill that lost those documents — see [WindchillImporter],
     * which is where that guard lives, because only the importer knows whether the parse completed.
     */
    public suspend fun sweep(seenIds: Set<String>): Int =
        graphDriver.executeWrite(
            Query(WindchillCypher.SWEEP_DELETED, mapOf("seenIds" to seenIds.toList())),
        ) { records -> records.firstOrNull()?.get("deleted")?.asInt() ?: 0 }

    /**
     * One document as the statement's parameters want it.
     *
     * `presentKeys` is what makes a field disappearing from the export remove the property rather
     * than leave last import's value behind — see `WindchillCypher.UPSERT_DOCUMENTS`. It is the
     * row's own key set, computed here so the statement never has to guess at Windchill's schema.
     */
    private fun documentRow(record: WindchillRecord): Map<String, Any?> = mapOf(
        "id" to record.id,
        "name" to record.name,
        "sortKey" to record.sortKey,
        "props" to record.properties,
        "presentKeys" to record.properties.keys.toList(),
    )

    private companion object {
        /**
         * Rows per transaction.
         *
         * A constant, not configuration: nothing about a deployment changes the right answer, and
         * the whole reference export fits inside two of these anyway. It exists for the export that
         * does not.
         */
        const val BATCH_SIZE = 500
    }
}
