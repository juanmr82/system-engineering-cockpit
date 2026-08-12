package com.sec.importer

import com.sec.domain.ImportRunProp
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ImportRunCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.Value

private val logger = KotlinLogging.logger {}

/**
 * Where finished runs are remembered.
 *
 * An interface with one real implementation, and the reason is testability rather than
 * substitutability: the lifecycle rules in [ImportRunService] — one run per importer, the phase
 * sequence, the throttle, what a cancelled run reports — are where the bugs live, and none of them
 * is about Neo4j. Container tests are excluded from `mvn verify` on purpose (CLAUDE.md §11), so a
 * framework that could only be tested with Docker running is a framework whose rules go unchecked
 * on most machines.
 */
public interface ImportRunStore {
    public suspend fun save(run: ImportRun)
    public suspend fun load(runId: String): ImportRun?

    /** Newest first. A null [importerId] means every importer. */
    public suspend fun history(importerId: String?, limit: Int): List<ImportRun>

    /** Drops all but the newest [keep] **finished** runs of one importer. */
    public suspend fun prune(importerId: String, keep: Int)
}

/**
 * The `:__ImportRun` implementation.
 *
 * Every method here is best-effort from the run's point of view: [ImportRunService] logs a storage
 * failure and carries on, because losing the record of an import is bad and losing the import
 * itself because its record could not be written is worse.
 */
public class GraphImportRunStore(private val graphDriver: GraphDriver) : ImportRunStore {

    /** Constraints and the history index. Applied at startup — see [ImportRunCypher.SCHEMA]. */
    public suspend fun applySchema() {
        ImportRunCypher.SCHEMA.forEach { statement ->
            graphDriver.executeWrite(Query(statement)) { }
        }
    }

    override suspend fun save(run: ImportRun) {
        graphDriver.executeWrite(
            Query(
                ImportRunCypher.UPSERT,
                mapOf("id" to run.runId, "props" to properties(run)),
            ),
        ) { }
    }

    override suspend fun load(runId: String): ImportRun? =
        graphDriver.executeRead(Query(ImportRunCypher.LOAD, mapOf("id" to runId))) { records ->
            records.firstOrNull()?.let(::toRun)
        }

    override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
        graphDriver.executeRead(
            Query(
                ImportRunCypher.HISTORY,
                mapOf("importerId" to importerId, "limit" to limit.toLong()),
            ),
        ) { records -> records.map(::toRun) }

    override suspend fun prune(importerId: String, keep: Int) {
        graphDriver.executeWrite(
            Query(
                ImportRunCypher.PRUNE,
                mapOf("importerId" to importerId, "keep" to keep.toLong()),
            ),
        ) { }
    }

    /**
     * The run as a Neo4j property map.
     *
     * `params` and `counters` become JSON text: their keys belong to the importer, and a property
     * per counter would make the node's shape a function of which importer wrote it — queryable by
     * nobody, and a different set of keys on every row of the history table.
     */
    private fun properties(run: ImportRun): Map<String, Any?> = mapOf(
        ImportRunProp.IMPORTER_ID to run.importerId,
        ImportRunProp.STATUS to run.status.name,
        ImportRunProp.STARTED_AT to run.startedAt,
        ImportRunProp.FINISHED_AT to run.finishedAt,
        ImportRunProp.PHASE to run.phase,
        ImportRunProp.PARAMS to json.encodeToString(run.params),
        ImportRunProp.COUNTERS to json.encodeToString(run.counters),
        ImportRunProp.WARNINGS to run.warnings,
        ImportRunProp.ERROR to run.error,
    )

    private fun toRun(record: Record): ImportRun = ImportRun(
        runId = record["id"].asString(""),
        importerId = record["importerId"].asString(""),
        // A status this build does not know means a run written by a newer one. Reporting it as
        // FAILED would be a lie about what happened; the history simply cannot draw it, so the
        // closest true statement is that it is over.
        status = runCatching { ImportStatus.valueOf(record["status"].asString("")) }
            .getOrElse { ImportStatus.SUCCEEDED },
        startedAt = record["startedAt"].asString(""),
        finishedAt = record["finishedAt"].asStringOrNull(),
        phase = record["phase"].asStringOrNull(),
        params = decode<String>(record["params"].asStringOrNull()),
        counters = decode<Long>(record["counters"].asStringOrNull()),
        warnings = record["warnings"].takeIf { !it.isNull }?.asList { it.asString() } ?: emptyList(),
        error = record["error"].asStringOrNull(),
    )

    /**
     * Reified rather than one `Map<String, String>` decoder for both: counters are written as JSON
     * numbers, and asking the string deserializer to read `{"pages":16}` fails outright — which
     * would turn every counter into an empty map at exactly the moment somebody looked.
     *
     * Unreadable text loses the map rather than the run. It happens when a build changes what it
     * writes here, and a history row missing its counters is worth more than one that will not load.
     */
    private inline fun <reified V> decode(text: String?): Map<String, V> =
        text?.let { runCatching { json.decodeFromString<Map<String, V>>(it) }.getOrNull() }
            ?: emptyMap()

    private fun Value.asStringOrNull(): String? = if (isNull) null else asString()

    private companion object {
        /**
         * Its own instance rather than the API's: this text goes into the graph, so what matters is
         * that it round-trips, not that it matches a wire contract. `encodeDefaults` is irrelevant
         * for a map and is left at its default deliberately.
         */
        val json = Json
    }
}

/**
 * Wraps a store so a storage failure warns instead of ending the run.
 *
 * The alternative — letting the write throw — means a Neo4j hiccup at the moment a run finishes
 * turns a completed import into a `FAILED` one, and the data it wrote is already committed. The
 * record of the run is the least important thing the run produced.
 */
internal class ForgivingImportRunStore(private val delegate: ImportRunStore) : ImportRunStore {
    override suspend fun save(run: ImportRun) {
        forgiving("record import run ${run.runId}") { delegate.save(run) }
    }

    override suspend fun load(runId: String): ImportRun? =
        forgiving("read import run $runId") { delegate.load(runId) }

    override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
        forgiving("read import run history") { delegate.history(importerId, limit) } ?: emptyList()

    override suspend fun prune(importerId: String, keep: Int) {
        forgiving("prune import run history for $importerId") { delegate.prune(importerId, keep) }
    }

    /**
     * `CancellationException` is rethrown, and that exception is the reason this is a function
     * rather than four `runCatching`s. `runCatching` catches `Throwable`, cancellation included —
     * so a run cancelled while its record was being written would swallow the cancellation here
     * and carry on importing, which is the one failure mode a cancel button must not have.
     */
    private suspend fun <T> forgiving(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (cause: kotlinx.coroutines.CancellationException) {
        throw cause
    } catch (cause: Exception) {
        logger.warn(cause) { "Could not $what" }
        null
    }
}
