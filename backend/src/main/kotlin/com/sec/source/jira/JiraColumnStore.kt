package com.sec.source.jira

import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import org.neo4j.driver.Query
import java.time.Instant

/**
 * The chosen columns and their order (spec §10.2), read by the Issues table and written by the
 * picker dialog.
 *
 * Application configuration a user edits during normal work, so it lives in the graph under its own
 * `__`-prefixed label rather than in `:__Meta` — nothing here annotates an imported node, and
 * `MATCH (m:__Meta) DETACH DELETE m` does not remove it (ADR 0014).
 *
 * **Only the optional columns are stored.** Type, key and the link out are fixed (spec §13.2), and
 * keeping them out of this list is what makes them impossible to remove by a bad write.
 */
public class JiraColumnStore(private val graphDriver: GraphDriver) {

    /**
     * The configured field ids, in column order.
     *
     * Empty means "the user has never chosen", which the API layer turns into [DEFAULTS] — the
     * distinction is kept here rather than resolved here, because a user who deliberately unticks
     * every column has chosen an empty table and must not be given six columns back.
     */
    public suspend fun fieldIds(): List<String>? =
        graphDriver.executeRead(
            Query(JiraCypher.LOAD_COLUMNS, mapOf("id" to JiraId.COLUMN_CONFIG)),
        ) { records ->
            records.firstOrNull()
                ?.get("fieldIds")
                ?.takeIf { !it.isNull }
                ?.asList { it.asString() }
        }

    /**
     * Replace the chosen columns.
     *
     * Validated before it is stored: a field id reaches Cypher as a *dynamic property key*, so one
     * carrying a backtick or a space is the one shape of input this design cannot treat as opaque.
     * JIRA's own ids are `summary` or `customfield_18201` and nothing else, which is what makes the
     * check a cheap statement of an existing fact rather than a guess at a format.
     *
     * Duplicates are removed rather than rejected: two of one column is a client bug, the user's
     * intent is unambiguous, and a 400 would strand a dialog whose visible state is already correct.
     */
    public suspend fun saveFieldIds(
        fieldIds: List<String>,
        updatedBy: String,
    ): Result<List<String>> {
        val cleaned = fieldIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val invalid = cleaned.filterNot { it.matches(FIELD_ID) }

        if (invalid.isNotEmpty()) return Result.failure(JiraFailure.InvalidFieldId(invalid))

        graphDriver.executeWrite(
            Query(
                JiraCypher.SAVE_COLUMNS,
                mapOf(
                    "id" to JiraId.COLUMN_CONFIG,
                    "fieldIds" to cleaned,
                    "updatedAt" to Instant.now().toString(),
                    "updatedBy" to updatedBy,
                ),
            ),
        ) { }

        return Result.success(cleaned)
    }

    public companion object {
        /**
         * What the table shows before anyone opens the picker (spec §13.3).
         *
         * Every one of them is a system field that exists on every JIRA instance, so the default
         * table is never empty for a reason a user cannot see. They are *defaults*, not a floor:
         * the moment the picker saves, this list stops applying.
         */
        public val DEFAULTS: List<String> = listOf(
            JiraFieldId.SUMMARY,
            JiraFieldId.STATUS,
            JiraFieldId.PRIORITY,
            JiraFieldId.ASSIGNEE,
            "created",
            JiraFieldId.UPDATED,
        )

        /**
         * What a JIRA field id may look like.
         *
         * Not a guess: `/field` returns `summary`, `issuetype`, `customfield_18201`. The check
         * exists because these ids become Cypher property keys, and it is deliberately stricter
         * than JIRA — a field id this rejects is one this application could not have imported.
         */
        private val FIELD_ID = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }
}
