package com.sec.graph.cypher

import com.sec.domain.ImportRunProp.COUNTERS
import com.sec.domain.ImportRunProp.ERROR
import com.sec.domain.ImportRunProp.FINISHED_AT
import com.sec.domain.ImportRunProp.IMPORTER_ID
import com.sec.domain.ImportRunProp.PARAMS
import com.sec.domain.ImportRunProp.PHASE
import com.sec.domain.ImportRunProp.STARTED_AT
import com.sec.domain.ImportRunProp.STATUS
import com.sec.domain.ImportRunProp.WARNINGS
import com.sec.domain.NodeLabel.IMPORT_RUN
import com.sec.domain.Prop.ID

/**
 * Cypher for the import-run history (spec §11.2).
 *
 * A `:__ImportRun` is **not** a `:SEItem` and carries no `:SEItem` label: it is not an artifact of
 * any source, nothing links to it from the knowledge tree, and giving it the label would put
 * operational records into every query that walks items. It keeps `__id` all the same, because
 * `__id` is this application's identity property and a second identity convention for one node kind
 * is how the next reader gets it wrong.
 *
 * Source-agnostic throughout: no statement here mentions a source, and [UPSERT] takes the whole
 * property set as a map so a new importer needs no change in this file.
 */
public object ImportRunCypher {

    /**
     * Uniqueness on the run id, and the index the history query sorts on.
     *
     * Applied at **startup**, unlike the JIRA schema, and the difference is deliberate: this is the
     * framework's own storage, present in every deployment, so it belongs with the other schema the
     * backend owns rather than inside a run that may never happen.
     */
    public val SCHEMA: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT import_run_id_unique IF NOT EXISTS
        FOR (r:$IMPORT_RUN) REQUIRE r.$ID IS UNIQUE
        """,
        // History is always "this importer's runs, newest first", so the index carries both.
        """
        CYPHER 25
        CREATE INDEX import_run_importer IF NOT EXISTS
        FOR (r:$IMPORT_RUN) ON (r.$IMPORTER_ID, r.$STARTED_AT)
        """,
    )

    /**
     * Written at start, at every phase transition, and at the end.
     *
     * One statement for all three, because a run's row is small and rewriting it whole removes the
     * question of which write set which property. `props` is a map so this file never learns an
     * importer's counter names.
     */
    public const val UPSERT: String = """
        CYPHER 25
        MERGE (r:$IMPORT_RUN {$ID: ${'$'}id})
        SET r += ${'$'}props
    """

    public const val LOAD: String = """
        CYPHER 25
        MATCH (r:$IMPORT_RUN {$ID: ${'$'}id})
        RETURN r.$ID AS id, r.$IMPORTER_ID AS importerId, r.$STATUS AS status,
               r.$STARTED_AT AS startedAt, r.$FINISHED_AT AS finishedAt, r.$PHASE AS phase,
               r.$PARAMS AS params, r.$COUNTERS AS counters, r.$WARNINGS AS warnings,
               r.$ERROR AS error
    """

    /**
     * Run history, newest first.
     *
     * `$importerId IS NULL OR …` rather than two statements: the console shows one importer's runs
     * and the dashboard shows everyone's, and a second near-identical statement is a second place
     * for the ordering to drift.
     */
    public const val HISTORY: String = """
        CYPHER 25
        MATCH (r:$IMPORT_RUN)
        WHERE ${'$'}importerId IS NULL OR r.$IMPORTER_ID = ${'$'}importerId
        RETURN r.$ID AS id, r.$IMPORTER_ID AS importerId, r.$STATUS AS status,
               r.$STARTED_AT AS startedAt, r.$FINISHED_AT AS finishedAt, r.$PHASE AS phase,
               r.$PARAMS AS params, r.$COUNTERS AS counters, r.$WARNINGS AS warnings,
               r.$ERROR AS error
        ORDER BY r.$STARTED_AT DESC
        LIMIT ${'$'}limit
    """

    /**
     * Keeps the newest `$keep` **finished** runs of one importer and deletes the rest.
     *
     * Finished only, and that is the guard that matters: an unfinished run is the one currently
     * writing, and pruning it would delete the record a live console is reading. `SKIP` does the
     * selecting so the statement never materialises the whole history to drop from it.
     */
    public const val PRUNE: String = """
        CYPHER 25
        MATCH (r:$IMPORT_RUN {$IMPORTER_ID: ${'$'}importerId})
        WHERE r.$FINISHED_AT IS NOT NULL
        WITH r ORDER BY r.$STARTED_AT DESC
        SKIP ${'$'}keep
        DETACH DELETE r
    """
}
