package com.sec.source.doors

import com.sec.graph.GraphDriver
import com.sec.graph.cypher.DoorsImportCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.security.AccessSet
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

/** What the seven-statement ADR-0012 reconciliation found for one module's re-import. */
public data class DoorsReconcileCounts(
    /** The module's whole ghost population, not this run's delta — a ghost from three imports ago
     *  is still counted here every time. */
    public val ghosts: Int,
    /** The delta: objects this run newly marked `:__DELETED`. */
    public val newlyDeleted: Int,
    public val childRelsDeleted: Int,
    public val refersToDeleted: Int,
    /** The one Tier-2 deletion an importer ever performs (R2) — annotations on an object DOORS no
     *  longer has. */
    public val ghostMetaDeleted: Int,
    public val ghostEdgesStripped: Int,
    public val ghostsCollected: Int,
    public val placeholdersRemoved: Int,
)

/**
 * The only thing that writes imported DOORS data from the upload path, and it writes nothing else —
 * the same structural stand-in for R1 that [com.sec.source.windchill.WindchillGraphWriter] and
 * `JiraGraphWriter` already are (ADR 0013, ADR 0015, ADR 0019 §1). Reached only by [DoorsImporter],
 * touches only [DoorsLabel.all] plus [com.sec.domain.NodeLabel.SE_ITEM], and every statement it runs
 * is a named constant in [DoorsImportCypher] — nothing here builds Cypher from a caller-chosen label
 * or key.
 *
 * This is a second writer of DOORS data, alongside the Python importer's own — both `MERGE` on the
 * same `__id` scheme and neither reconciles against the other (ADR 0019, Consequences).
 */
public class DoorsGraphWriter(
    private val graphDriver: GraphDriver,
) {

    /** Constraints and indexes. Idempotent, and run at the start of every import — a deployment
     *  with no DOORS import via this path should not carry its indexes until one runs. */
    public suspend fun applySchema() {
        DoorsImportCypher.SCHEMA.forEach { statement -> graphDriver.executeWrite(Query(statement)) { } }
        logger.info { "Applied DOORS schema (${DoorsImportCypher.SCHEMA.size} statements)" }
    }

    /**
     * Whether [moduleId] already exists, whether [access] can see it, and its current checksum —
     * read before a run is ever started (ADR 0019 §3, §4). The one query the checksum-skip and the
     * visibility refusal both decide from.
     */
    public suspend fun gate(moduleId: String, access: AccessSet): DoorsImportGate =
        graphDriver.executeRead(
            DoorsImportCypher.MODULE_GATE,
            mapOf("moduleId" to moduleId),
            access,
        ) { records ->
            val record = records.single()
            DoorsImportGate(
                exists = record["exists"].asBoolean(),
                visible = record["visible"].asBoolean(),
                storedChecksum = record["storedChecksum"].takeUnless { it.isNull }?.asString(),
            )
        }

    public suspend fun upsertModule(moduleId: String, props: Map<String, Any?>) {
        graphDriver.executeWrite(
            Query(DoorsImportCypher.MERGE_MODULE, mapOf("id" to moduleId, "props" to props)),
        ) { }
    }

    /** Writes every object, grouped by its exact label set — one statement per group, the same
     *  shape `importer.py`'s own `groups` dict drives. Returns how many were sent. */
    public suspend fun upsertObjects(objects: List<DoorsObjectRow>, importedAt: String): Int {
        objects.groupBy { it.labels }.forEach { (labels, rows) ->
            val statement = DoorsImportCypher.mergeObjects(labels)
            rows.map { row -> mapOf("id" to row.objectUrl, "props" to row.props) }
                .chunked(BATCH_SIZE)
                .forEach { chunk ->
                    graphDriver.executeWrite(
                        Query(statement, mapOf("rows" to chunk, "importedAt" to importedAt)),
                    ) { }
                }
        }
        return objects.size
    }

    /** `(parentId, childId)` pairs — both ends must already exist from [upsertObjects]. */
    public suspend fun upsertChild(pairs: List<Pair<String, String>>, importedAt: String): Int {
        pairs.map { (parentId, childId) -> mapOf("parentId" to parentId, "childId" to childId) }
            .chunked(BATCH_SIZE)
            .forEach { chunk ->
                graphDriver.executeWrite(
                    Query(DoorsImportCypher.MERGE_CHILD, mapOf("rows" to chunk, "importedAt" to importedAt)),
                ) { }
            }
        return pairs.size
    }

    /** Rows shaped for [DoorsImportCypher.MERGE_REFERS_TO] — see [DoorsImporter] for how they are
     *  built from `__outputLinks`. */
    public suspend fun upsertRefersTo(rows: List<Map<String, Any?>>): Int {
        rows.chunked(BATCH_SIZE).forEach { chunk ->
            graphDriver.executeWrite(Query(DoorsImportCypher.MERGE_REFERS_TO, mapOf("rows" to chunk))) { }
        }
        return rows.size
    }

    /** Rows shaped for [DoorsImportCypher.MERGE_INCOMING] — from `__inputLinks`, the only way an
     *  incoming link is visible before the module asserting it has been imported. */
    public suspend fun upsertIncoming(rows: List<Map<String, Any?>>): Int {
        rows.chunked(BATCH_SIZE).forEach { chunk ->
            graphDriver.executeWrite(Query(DoorsImportCypher.MERGE_INCOMING, mapOf("rows" to chunk))) { }
        }
        return rows.size
    }

    /**
     * Phase 6 (ADR 0012): reconciles [moduleUrl] against the run stamped [importedAt]. Seven
     * statements, in the order that matters — see [DoorsImportCypher]'s own comments on each.
     */
    public suspend fun reconcile(moduleUrl: String, importedAt: String): DoorsReconcileCounts {
        val scope = mapOf("moduleUrl" to moduleUrl, "importedAt" to importedAt)

        val (ghosts, newlyDeleted) = graphDriver.executeWrite(
            Query(DoorsImportCypher.MARK_DELETED, scope),
        ) { records ->
            val record = records.single()
            record["ghosts"].asInt() to record["newlyDeleted"].asInt()
        }

        val childRelsDeleted = countDeleted(DoorsImportCypher.DELETE_STALE_CHILD, scope)
        val refersToDeleted = countDeleted(DoorsImportCypher.DELETE_STALE_REFERS_TO, scope)
        val ghostMetaDeleted = countDeleted(DoorsImportCypher.DELETE_GHOST_META, scope)
        val ghostEdgesStripped = countDeleted(DoorsImportCypher.STRIP_GHOST_EDGES, scope)
        // Global, not module-scoped — re-importing one module is exactly what can strand a ghost
        // or a placeholder belonging to another (ADR 0012).
        val ghostsCollected = countDeleted(DoorsImportCypher.COLLECT_GHOSTS, emptyMap())
        val placeholdersRemoved = countDeleted(DoorsImportCypher.COLLECT_PLACEHOLDERS, emptyMap())

        return DoorsReconcileCounts(
            ghosts = ghosts,
            newlyDeleted = newlyDeleted,
            childRelsDeleted = childRelsDeleted,
            refersToDeleted = refersToDeleted,
            ghostMetaDeleted = ghostMetaDeleted,
            ghostEdgesStripped = ghostEdgesStripped,
            ghostsCollected = ghostsCollected,
            placeholdersRemoved = placeholdersRemoved,
        )
    }

    /**
     * Written last, only once every earlier phase has succeeded (ADR 0019 §3) — [DoorsImporter]
     * calls this after [reconcile] returns, never before, so a run that fails partway leaves no
     * checksum a retry would mistake for "nothing changed".
     */
    public suspend fun stampChecksum(moduleId: String, checksum: String) {
        graphDriver.executeWrite(
            Query(DoorsImportCypher.STAMP_CHECKSUM, mapOf("moduleId" to moduleId, "checksum" to checksum)),
        ) { }
    }

    private suspend fun countDeleted(statement: String, params: Map<String, Any?>): Int =
        graphDriver.executeWrite(Query(statement, params)) { records ->
            records.firstOrNull()?.get("deleted")?.asInt() ?: 0
        }

    private companion object {
        /** Rows per transaction — matches [com.sec.source.windchill.WindchillGraphWriter]'s own
         *  constant: the same absence of a query governor (CLAUDE.md §7) is the same constraint on
         *  both writers, so there is no reason for this number to be independently chosen. */
        const val BATCH_SIZE = 500
    }
}
