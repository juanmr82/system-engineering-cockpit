package com.sec.source.jira

import com.sec.api.dto.JiraColumnDto
import com.sec.api.dto.JiraFieldDto
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import org.neo4j.driver.Query
import org.neo4j.driver.Record

/**
 * The field catalogue, as the picker and the table need it (spec §9.2, §13.3, §13.4).
 *
 * Reads only, like every other projection in this package. Two questions are answered here and
 * they are not the same question: *what may a user choose* ([list]), and *what did they choose*
 * ([describe]) — the second has to survive naming a field JIRA no longer has, which is exactly the
 * case the first can never produce.
 */
public class JiraFieldsProjection(private val graphDriver: GraphDriver) {

    /** Every offerable field, ordered by name. The picker's whole source of truth. */
    public suspend fun list(): List<JiraFieldDto> {
        val fields = graphDriver.executeRead(Query(JiraCypher.LIST_FIELDS)) { records ->
            records.map { it.toField() }
        }

        // A name is ambiguous when more than one field carries it — 15 names cover 33 fields on the
        // reference instance. The dialog appends the id to those, and it is told *which* rather
        // than left to work it out from a list it renders one row at a time.
        val duplicated = fields.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys

        return fields.map { it.copy(ambiguousName = it.name in duplicated) }
    }

    /**
     * The configured columns, in the user's order, with the ones JIRA has dropped marked stale.
     *
     * **A stale column is returned, not removed** (spec §13.4). The user chose it; a column that
     * silently disappeared reads as a bug, and the table renders it empty with a header that says
     * what happened. Its `name` falls back to the field id, which is the only name left.
     */
    public suspend fun describe(fieldIds: List<String>): List<JiraColumnDto> {
        if (fieldIds.isEmpty()) return emptyList()

        val known = graphDriver.executeRead(
            Query(JiraCypher.FIND_FIELDS, mapOf("fieldIds" to fieldIds)),
        ) { records -> records.map { it.toField() }.associateBy { it.fieldId } }

        return fieldIds.map { fieldId ->
            val field = known[fieldId]
            JiraColumnDto(
                fieldId = fieldId,
                name = field?.name ?: fieldId,
                schemaType = field?.schemaType,
                sortable = field != null && isSortable(field.schemaType),
                stale = field == null,
            )
        }
    }

    private fun Record.toField(): JiraFieldDto = JiraFieldDto(
        fieldId = get("fieldId").asString(),
        name = get("name").asString(),
        custom = get("custom").asBoolean(false),
        schemaType = get("schemaType").takeIf { !it.isNull }?.asString(),
        schemaItems = get("schemaItems").takeIf { !it.isNull }?.asString(),
    )

    public companion object {
        /**
         * Whether a column of this declared type can be ordered by (spec §13.2).
         *
         * The rule is one sentence: **a column is sortable when one row of it is one value.** A
         * scalar is stored on the issue and a complex value has a display string on its projection,
         * and `coalesce(i[k], p[k])` reads whichever exists — so both sort. An array does not: its
         * projection is a *list* of strings, and ordering by a list orders by an accident of
         * element order that no reader can predict.
         *
         * The named types are the complex ones whose projection is `null` by design (§7.4 ends with
         * "do not guess"), so ordering by them would sort every row equally and present the result
         * as if it had been sorted. Unknown types are treated as sortable: a new JIRA type is far
         * more likely to be a scalar than not, and the failure mode of the wrong guess is a strange
         * order rather than a broken table.
         *
         * A `null` type never reaches here from [list] — those fields are excluded from the
         * catalogue entirely — but it can arrive through [describe], and it means "not offerable",
         * so it is not sortable either.
         */
        internal fun isSortable(schemaType: String?): Boolean = when {
            schemaType == null -> false
            schemaType.startsWith(ARRAY) -> false
            else -> schemaType !in UNSORTABLE
        }

        /** `array`, `array<string>` — whatever spelling an instance uses for "more than one". */
        private const val ARRAY = "array"

        /**
         * Types with no single display value, so nothing to order by.
         *
         * Kept as a closed list rather than inferred, because each of these is a *decision* about
         * how the projection treats that shape, and the two must agree.
         */
        private val UNSORTABLE = setOf(
            "timetracking",
            "attachment",
            "comments-page",
            "worklog",
            "issuelinks",
            "any",
        )
    }
}
