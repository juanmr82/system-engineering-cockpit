package com.sec.source.jira

import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import org.neo4j.driver.Query
import java.time.Instant

/**
 * The configured project keys — read by the importer, written by the settings screen (spec §10.1).
 *
 * ## Why this is not part of [JiraGraphWriter]
 *
 * That class is documented as *the only thing that writes imported JIRA data, and it writes nothing
 * else*, which is the structural guarantee standing in for R1 now that one importer runs in-process
 * (ADR 0013). Project keys are the opposite kind of data: nothing about them comes from JIRA, and no
 * re-import could reproduce them. Folding them into the writer would give it a second job and
 * dissolve the only sentence that makes the arrangement checkable.
 *
 * ## Why it is not `:__Meta` either
 *
 * `:__JiraSettings` hangs off nothing in the imported graph, and every `:__Meta` node by definition
 * annotates something that was imported. It is application configuration held in the graph because
 * a user edits it during normal work — the middle row of CLAUDE.md's state table — so it gets its
 * own label, exactly as R2 directs for saved queries and view layouts (ADR 0014).
 *
 * The consequence, stated where somebody will meet it: `MATCH (m:__Meta) DETACH DELETE m` does not
 * remove this node.
 */
public class JiraSettingsStore(private val graphDriver: GraphDriver) {

    /**
     * The configured keys, or empty when nothing has been configured yet.
     *
     * Empty is a legitimate answer and not an error — a deployment that has connected to JIRA but
     * not chosen projects is in a normal state. The importer is what refuses to run on it, and it
     * refuses with the spec's own words rather than with an empty result nobody notices.
     */
    public suspend fun projectKeys(): List<String> =
        graphDriver.executeRead(
            Query(JiraCypher.LOAD_SETTINGS, mapOf("id" to JiraId.SETTINGS)),
        ) { records ->
            records.firstOrNull()
                ?.get("projectKeys")
                ?.takeIf { !it.isNull }
                ?.asList { it.asString() }
                .orEmpty()
        }

    /**
     * Replace the configured keys.
     *
     * Validated here as well as at the API boundary, and the duplication is deliberate: a key that
     * fails validation must never reach the graph, because from there it would break *every* future
     * import rather than the one request that introduced it. Storing is the point of no return, so
     * it is the last place worth checking.
     *
     * The whole list is replaced rather than merged — the user's order is part of the value (it is
     * what the JQL preview reads back), and a merge would have to invent a rule for where a new key
     * goes.
     */
    public suspend fun saveProjectKeys(keys: List<String>, updatedBy: String): Result<List<String>> {
        val valid = JiraJql.validate(keys).getOrElse { return Result.failure(it) }

        graphDriver.executeWrite(
            Query(
                JiraCypher.SAVE_SETTINGS,
                mapOf(
                    "id" to JiraId.SETTINGS,
                    "projectKeys" to valid,
                    "updatedAt" to Instant.now().toString(),
                    "updatedBy" to updatedBy,
                ),
            ),
        ) { }

        return Result.success(valid)
    }
}
