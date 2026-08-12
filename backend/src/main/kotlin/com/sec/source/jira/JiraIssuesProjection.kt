package com.sec.source.jira

import com.sec.api.dto.JiraColumnDto
import com.sec.api.dto.JiraIssueRowDto
import com.sec.api.dto.JiraIssuesPageDto
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.Value
import org.neo4j.driver.types.TypeSystem

/**
 * The Issues table's read path (spec §14.4).
 *
 * JIRA-specific and confined to this package, exactly as the DOORS projections are (CLAUDE.md §1).
 * It reads and never writes: everything here is `executeRead`, which on Community is the only
 * server-side write protection there is.
 *
 * ## What it is responsible for that the Cypher is not
 *
 * Three things, and all three are the difference between a graph row and something a browser may
 * be handed:
 *
 *  - **`ref`, never `__id`.** The route parameter and the row key are the base64url handle, so no
 *    internal id reaches the address bar or a DOM attribute (R5).
 *  - **`browseUrl`.** JIRA's `self` is an API URL; opening it shows raw JSON. The link a person
 *    clicks is `<host>/browse/<key>`, which is *derived* — so it is computed here on every read
 *    and never stored (R2, and spec §13.2 calls it a real trap in the requirement as written).
 *  - **The sort field is validated here**, against the columns this endpoint actually offers,
 *    before it reaches a statement.
 *
 * @param host the normalised JIRA host, for [browseUrl]. Blank on a deployment with no JIRA
 *   configured, which makes the link absent rather than broken.
 */
public class JiraIssuesProjection(
    private val graphDriver: GraphDriver,
    private val host: String,
) {

    /**
     * One page of the issues table.
     *
     * [fieldIds] is the configured column set, which is empty until the column picker exists — the
     * query still asks for it, because "which properties does this row carry" is the one part of
     * this endpoint that cannot be added later without changing its shape.
     *
     * The count runs on its own connection-level read after the page rather than around it. Two
     * reads can disagree if an import commits between them, and the honest handling of that is a
     * total that is briefly stale rather than a transaction held open across both.
     */
    public suspend fun listIssues(
        page: Int,
        size: Int,
        sort: SortField,
        direction: SortDirection,
        query: String? = null,
        projectKeys: List<String>? = null,
        fieldIds: List<String> = emptyList(),
    ): JiraIssuesPageDto {
        val filters = mapOf(
            // Lower-cased once here rather than in the statement: `toLower($q)` on every row is
            // 784 conversions of a value that did not change.
            "q" to query?.takeIf { it.isNotBlank() }?.trim()?.lowercase(),
            "projectKeys" to projectKeys?.takeIf { it.isNotEmpty() },
        )

        val statement = when (direction) {
            SortDirection.ASC -> JiraCypher.LIST_ISSUES_ASC
            SortDirection.DESC -> JiraCypher.LIST_ISSUES_DESC
        }

        val rows = graphDriver.executeRead(
            Query(
                statement,
                filters + mapOf(
                    "sortField" to sort.property,
                    "skip" to page.toLong() * size,
                    "limit" to size,
                    "fieldIds" to fieldIds,
                ),
            ),
        ) { records -> records.map { it.toRowDto(fieldIds) } }

        val total = graphDriver.executeRead(Query(JiraCypher.COUNT_ISSUES_MATCHING, filters)) { records ->
            records.firstOrNull()?.get("total")?.asInt() ?: 0
        }

        return JiraIssuesPageDto(
            page = page,
            size = size,
            total = total,
            columns = fieldIds.map { JiraColumnDto(fieldId = it, name = it, sortable = true) },
            rows = rows,
        )
    }

    private fun Record.toRowDto(fieldIds: List<String>): JiraIssueRowDto {
        val key = get("key").asStringOrNull().orEmpty()

        return JiraIssueRowDto(
            ref = Ref.encode(get("id").asString()),
            key = key,
            name = get("name").asStringOrNull().orEmpty(),
            issueTypeName = get("issueTypeName").asStringOrNull(),
            browseUrl = browseUrl(key),
            unresolved = get("unresolved").asBoolean(false),
            // Zipped rather than read as a map, because a Cypher list comprehension preserves the
            // order of `$fieldIds` and nothing else about it: the value at index n belongs to
            // the field the caller asked for at index n.
            values = fieldIds.zip(get("values").asList(Value::toJson)).toMap(),
        )
    }

    /**
     * The URL a person opens, which is not the URL the graph stores.
     *
     * Absent rather than wrong when there is no configured host: a link to `/browse/SCRUM-1` with
     * no origin resolves against this application and lands on its own 404.
     */
    private fun browseUrl(key: String): String? =
        if (host.isBlank() || key.isBlank()) null else "$host/browse/$key"

    /**
     * A column this endpoint will sort by.
     *
     * A closed type rather than a string, because the value reaches a dynamic property access in
     * Cypher. Nothing user-supplied is ever put in [property]: the route resolves a query parameter
     * to one of these or answers 400 (spec §14.4, `INVALID_SORT_FIELD`).
     *
     * The field-id columns join this the moment the column picker does. They will be validated
     * against the configured set, which is why this is a type with a [property] rather than an enum
     * of everything sortable.
     */
    public class SortField private constructor(public val id: String, internal val property: String) {
        public companion object {
            /**
             * JIRA's own order — project, then issue number — and the table's default.
             *
             * It sorts on `__sortKey` rather than on `key`, which is the entire reason that property
             * is written: `SCRUM-10` precedes `SCRUM-2` as text and follows it in every JIRA screen
             * (R3). The id a client sends is `key`, because `__sortKey` is an internal name and
             * never crosses the wire (R5).
             */
            public val KEY: SortField = SortField(id = "key", property = com.sec.domain.Prop.SORT_KEY)

            /** Resolve a client's `sort` parameter, or null if it names nothing this offers. */
            public fun of(id: String?): SortField? = when (id) {
                null, "", KEY.id -> KEY
                else -> null
            }
        }
    }

    /** Which way [SortField] runs. An enum, because it selects a statement rather than a value. */
    public enum class SortDirection {
        ASC,
        DESC,
        ;

        public companion object {
            public fun of(value: String?): SortDirection? = when (value?.lowercase()) {
                null, "", "asc" -> ASC
                "desc" -> DESC
                else -> null
            }
        }
    }

    public companion object {
        /** The table's page size when the client does not ask for one. */
        public const val DEFAULT_SIZE: Int = 50

        /**
         * The largest page this endpoint will serve, whatever was asked for.
         *
         * Server-controlled, the same posture as the Cypher console's row cap: Community has no
         * query governor (CLAUDE.md §7), so the only thing between one request and the heap is
         * this number.
         */
        public const val MAX_SIZE: Int = 200
    }
}

/** `null` for a null property, rather than the driver's exception on `asString()`. */
private fun Value.asStringOrNull(): String? = if (isNull) null else asString()

/**
 * One stored property as JSON — the same dynamic-bag treatment `ReviewProjection` gives a DOORS
 * attribute, with one addition that matters here.
 *
 * **A list stays a list.** JIRA stores `array<string>` fields — labels, most notably — as a Neo4j
 * list, and the table renders those as chips (spec §13.2). Falling through to `toString()` would
 * put the literal text `[a, b]` in a cell and there would be no way back to the elements.
 *
 * Everything else follows the established rule: booleans and numbers keep their type so a client
 * can right-align a number, and anything else becomes its text. A complex value has already been
 * flattened to JSON text by the importer (§7.2), so "anything else" is a string in practice.
 */
private fun Value.toJson(): JsonElement = when {
    isNull -> JsonNull
    hasType(TypeSystem.getDefault().BOOLEAN()) -> JsonPrimitive(asBoolean())
    hasType(TypeSystem.getDefault().INTEGER()) -> JsonPrimitive(asLong())
    hasType(TypeSystem.getDefault().FLOAT()) -> JsonPrimitive(asDouble())
    hasType(TypeSystem.getDefault().LIST()) -> JsonArray(asList(Value::toJson))
    else -> JsonPrimitive(asString())
}
