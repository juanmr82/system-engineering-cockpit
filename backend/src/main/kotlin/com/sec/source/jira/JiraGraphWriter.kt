package com.sec.source.jira

import com.sec.domain.ItemVersion
import com.sec.domain.Prop
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query

private val logger = KotlinLogging.logger {}

/**
 * The only thing that writes imported JIRA data, and it writes nothing else.
 *
 * That restriction is what keeps R1 true **by structure rather than by convention** (ADR 0013).
 * Every other importer in this product is a separate process in a separate language, which makes
 * "the application never writes imported data" easy to guarantee; JIRA's runs in-process, so the
 * guarantee has to come from somewhere else. It comes from here: this class is reached only by the
 * JIRA importer and touches only JIRA-imported labels, while `meta/MetaWriter` touches only
 * `:__Meta` and cannot reach these statements. Neither has a general-purpose method that takes a
 * caller-chosen label or key — that would dissolve the whole arrangement into a convention again.
 *
 * @param host the normalised JIRA host, needed because `/field` returns no `self` and a field's
 *   identity has to be synthesised to look like the URL JIRA would have given it (§6.2).
 */
public class JiraGraphWriter(
    private val graphDriver: GraphDriver,
    private val host: String,
) {

    /** Constraints and indexes. Idempotent, and run at the start of every import (spec §12 phase 0). */
    public suspend fun applySchema() {
        // One statement per transaction: schema changes cannot share one with anything else.
        JiraCypher.SCHEMA.forEach { statement ->
            graphDriver.executeWrite(Query(statement)) { }
        }
        logger.info { "Applied JIRA schema (${JiraCypher.SCHEMA.size} statements)" }
    }

    /**
     * Phase 1 — issue types.
     *
     * Tens of rows on any instance, so this is one batch and the chunking below never splits it.
     * It is written the same way as the field catalogue anyway, because a phase that is small
     * today and unbatched is a phase that breaks on the first instance where it is not.
     */
    public suspend fun upsertIssueTypes(types: List<JiraIssueTypeDefinition>): Int {
        val rows = types.map(::issueTypeRow)
        writeBatched(JiraCypher.UPSERT_ISSUE_TYPES, rows)
        return rows.size
    }

    /** Issue types JIRA no longer returns, removed only where nothing still uses them (§9.1). */
    public suspend fun deleteUnusedIssueTypes(seenIds: Collection<String>) {
        graphDriver.executeWrite(
            Query(JiraCypher.DELETE_UNUSED_ISSUE_TYPES, mapOf("seenIds" to seenIds.toList())),
        ) { }
    }

    /**
     * Phase 2 — the field catalogue.
     *
     * `schema` is flattened to four keys rather than stored as JSON text, because the column
     * picker filters and sorts on the type constantly (§9.2). The values are JIRA's own, untouched,
     * so R1 holds: this is a structural flattening, not a transformation.
     */
    public suspend fun upsertFieldDefinitions(fields: List<JiraFieldDefinition>): Int {
        val rows = fields.map { fieldRow(host, it) }
        writeBatched(JiraCypher.UPSERT_FIELDS, rows)
        return rows.size
    }

    /** Field definitions JIRA no longer returns. A user's column choice naming one is left alone. */
    public suspend fun deleteStaleFields(seenIds: Collection<String>) {
        graphDriver.executeWrite(
            Query(JiraCypher.DELETE_STALE_FIELDS, mapOf("seenIds" to seenIds.toList())),
        ) { }
    }

    /**
     * What the catalogue holds now — for the run report and the read path's empty states.
     *
     * `executeRead`, not `executeWrite`: on Community the per-transaction access mode is the only
     * server-side write protection there is (CLAUDE.md §5), so a read issued on a write
     * transaction gives that up for nothing.
     */
    public suspend fun catalogueCounts(): CatalogueCounts =
        graphDriver.executeRead(Query(JiraCypher.COUNT_CATALOGUE)) { records ->
            records.firstOrNull()?.let {
                CatalogueCounts(
                    issueTypes = it["issueTypes"].asInt(),
                    fields = it["fields"].asInt(),
                )
            } ?: CatalogueCounts(0, 0)
        }

    /**
     * One transaction per [BATCH_SIZE] rows.
     *
     * Bounded on purpose. Community has no query governor (CLAUDE.md §7), so an unbounded write is
     * limited only by heap, and a single transaction holding 1 171 nodes' worth of locks is a
     * transaction every read waits behind.
     */
    private suspend fun writeBatched(statement: String, rows: List<Map<String, Any?>>) {
        rows.chunked(BATCH_SIZE).forEach { chunk ->
            graphDriver.executeWrite(Query(statement, mapOf("rows" to chunk))) { }
        }
    }

    /** Sizes of the two catalogues, for the run report. */
    public data class CatalogueCounts(public val issueTypes: Int, public val fields: Int)

    private companion object {
        /**
         * Rows per transaction (spec §15).
         *
         * A constant rather than configuration, unlike the spec's `neo4j.batchSize`: nothing about
         * a deployment changes the right answer here, and a knob nobody turns is a knob that goes
         * untested at every value but its default.
         */
        const val BATCH_SIZE = 1000
    }
}

/**
 * `__id` is the row's key and travels beside the property map, never inside it.
 *
 * Keeping it out of `props` is what lets the statement say `MERGE (n {__id: row.id}) SET n +=
 * row.props` — if the id were also in the map, `+=` would rewrite the very property the `MERGE`
 * matched on, which works right up until an id is ever corrected.
 */
internal fun row(id: String, props: Map<String, Any?>): Map<String, Any?> =
    mapOf("id" to id, "props" to props)

/**
 * One issue type as a write row.
 *
 * Pure and separate from the writer so the shaping decisions — which properties, which identity,
 * what an absent value becomes — are testable without a database. That is where the mistakes are;
 * the `UNWIND` around them is not.
 */
internal fun issueTypeRow(type: JiraIssueTypeDefinition): Map<String, Any?> = row(
    // Identity is the resource URL, exactly as it is for a DOORS object (§6.2). Never the name:
    // two instances can call two different types "Task".
    id = type.self,
    props = mapOf(
        Prop.NAME to type.name,
        Prop.VERSION to ItemVersion.CURRENT,
        JiraProp.ID to type.id,
        JiraProp.NAME to type.name,
        JiraProp.SELF to type.self,
        JiraProp.DESCRIPTION to type.description,
        JiraProp.ICON_URL to type.iconUrl,
        JiraProp.SUBTASK to type.subtask,
        // Null on purpose when absent. `SET n += {avatarId: null}` *removes* the property, which
        // is exactly right for a type that no longer has an avatar - and is why the DTO makes this
        // nullable rather than defaulting it to 0.
        JiraProp.AVATAR_ID to type.avatarId,
    ),
)

/**
 * One field definition as a write row.
 *
 * `__displayable` is deliberately **not** here. It is derived, so R2 keeps it off an imported node,
 * and recomputing it in the API layer is both cheaper than a stored copy and incapable of going
 * stale (§9.2).
 */
internal fun fieldRow(host: String, field: JiraFieldDefinition): Map<String, Any?> = row(
    // `/field` returns no `self`, so identity is synthesised to look like the URL JIRA would have
    // given it — identity stays "the resource URL" for every node in the graph, with no exception
    // a later reader has to know about.
    id = JiraId.field(host, field.id),
    props = mapOf(
        Prop.NAME to field.name,
        Prop.VERSION to ItemVersion.CURRENT,
        JiraProp.ID to field.id,
        JiraProp.NAME to field.name,
        JiraProp.CUSTOM to field.custom,
        JiraProp.ORDERABLE to field.orderable,
        JiraProp.NAVIGABLE to field.navigable,
        JiraProp.SEARCHABLE to field.searchable,
        JiraProp.CLAUSE_NAMES to field.clauseNames,
        // Flattened from `schema`, values untouched. Null for the two pseudo-fields that have no
        // schema at all, which removes the keys rather than writing empty strings — "no schema"
        // and "a schema whose type is blank" must not become the same thing.
        JiraProp.SCHEMA_TYPE to field.schema?.type,
        JiraProp.SCHEMA_ITEMS to field.schema?.items,
        JiraProp.SCHEMA_CUSTOM to field.schema?.custom,
        JiraProp.SCHEMA_CUSTOM_ID to field.schema?.customId,
    ),
)
