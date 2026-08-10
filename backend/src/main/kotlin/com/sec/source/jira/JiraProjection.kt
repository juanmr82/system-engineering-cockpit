package com.sec.source.jira

import com.sec.api.dto.JiraColumnDto
import com.sec.api.dto.JiraFieldNodeDto
import com.sec.api.dto.JiraFieldTreeDto
import com.sec.api.dto.JiraIssueRowDto
import com.sec.api.dto.JiraIssuesDto
import com.sec.api.dto.JiraProjectRowDto
import com.sec.domain.Prop
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.neo4j.driver.Query
import org.neo4j.driver.types.Node

/**
 * JIRA-specific read projections. Reads only — every write goes through [JiraGraphWriter] (imported
 * data) or [MetaWriter][com.sec.meta.MetaWriter] (Tier 2), and nothing here opens a write session.
 *
 * Nothing JIRA-specific may exist outside this package and the JIRA-specific API routes
 * (CLAUDE.md §1).
 */
public class JiraProjection(private val graphDriver: GraphDriver) {

    /** Which projects an import run fetches, with each one's extra JQL clause. */
    public suspend fun enabledProjects(): List<ProjectScope> =
        graphDriver.executeRead(
            Query(JiraCypher.ENABLED_PROJECTS, mapOf("limit" to MAX_PROJECTS)),
        ) { records ->
            records.map {
                ProjectScope(
                    key = it.get("projectKey").asString(""),
                    jql = it.get("jql").asString(""),
                )
            }.filter { it.key.isNotBlank() }
        }

    public suspend fun listProjects(): List<JiraProjectRowDto> =
        graphDriver.executeRead(
            Query(JiraCypher.LIST_PROJECTS, mapOf("limit" to MAX_PROJECTS)),
        ) { records ->
            records.map { record ->
                val node = record.get("project").asNode()
                // enabled is null when there is no scope node at all, which is a different thing
                // from a scope node switched off — the first has never been added, the second was
                // added and paused, and the settings view says so differently.
                val enabled = record.get("enabled")
                JiraProjectRowDto(
                    ref = Ref.encode(node.stringProp(Prop.ID)),
                    key = node.stringProp(JiraProjectAttr.KEY),
                    name = node.stringProp(Prop.NAME),
                    projectType = node.stringProp(JiraProjectAttr.PROJECT_TYPE_KEY),
                    inScope = !enabled.isNull,
                    enabled = !enabled.isNull && enabled.asBoolean(true),
                    jql = record.get("jql").asString(""),
                    issueCount = record.get("issueCount").asInt(0),
                )
            }
        }

    /**
     * One page of the Issues table.
     *
     * The columns are resolved first, and the row values are then picked out of each node's
     * property map by path — never by a Cypher projection, because a flattened path carries dots
     * and `i.status.name` does not mean what it looks like (see [JiraCypher]).
     */
    public suspend fun issuePage(projectKeys: List<String>?, offset: Int, limit: Int): JiraIssuesDto {
        val columns = resolveColumns()
        val scope = projectKeys?.takeIf { it.isNotEmpty() }

        val total = graphDriver.executeRead(
            Query(JiraCypher.COUNT_ISSUES, mapOf("projectKeys" to scope)),
        ) { records -> records.firstOrNull()?.get("total")?.asInt(0) ?: 0 }

        val bounded = limit.coerceIn(1, MAX_PAGE_SIZE)
        val rows = graphDriver.executeRead(
            Query(
                JiraCypher.ISSUE_PAGE,
                mapOf(
                    "projectKeys" to scope,
                    "offset" to offset.coerceAtLeast(0),
                    "limit" to bounded,
                ),
            ),
        ) { records ->
            records.map { record ->
                val node = record.get("issue").asNode()
                JiraIssueRowDto(
                    ref = Ref.encode(node.stringProp(Prop.ID)),
                    key = node.stringProp(JiraFieldId.KEY),
                    issueType = record.get("issueType").asString(""),
                    values = columns.associate { column ->
                        column.path to node.jsonValue(column.path)
                    },
                )
            }
        }

        return JiraIssuesDto(
            columns = columns,
            rows = rows,
            total = total,
            offset = offset.coerceAtLeast(0),
            limit = bounded,
        )
    }

    /**
     * The columns the table shows: the two fixed ones first, then the admin's selection in order.
     *
     * With nothing selected the table still works — it shows the fixed pair — which is what an
     * admin sees immediately after the first import, before they have opened the dialog.
     */
    public suspend fun resolveColumns(): List<JiraColumnDto> {
        val labels = fieldLabels()
        val selected = graphDriver.executeRead(
            Query(JiraCypher.SELECTED_COLUMNS, mapOf("sourceId" to JiraId.SOURCE)),
        ) { records -> records.map { it.get("path").asString("") }.filter { it.isNotBlank() } }

        val fixed = JiraFieldId.fixedColumns.map {
            JiraColumnDto(path = it, label = labels.labelFor(it), fixed = true)
        }
        val chosen = selected
            .filterNot { it in JiraFieldId.fixedColumns }
            .map { JiraColumnDto(path = it, label = labels.labelFor(it), fixed = false) }

        return fixed + chosen
    }

    /**
     * The selection tree (design doc §6.2): every discovered path, grouped under its field, with
     * the catalogue's wording and a real sample value.
     *
     * The design doc proposed sampling five issues per issue type and intersecting. This scans a
     * bounded sample of issues and takes the *union*, for the reason the DOORS attribute-discovery
     * query gives: an intersection hides a field that only one issue type carries, and a field the
     * Bug type has and the Story type does not is exactly the kind of column somebody wants. A
     * path that is absent from a given row renders blank, which §6.4 already requires.
     */
    public suspend fun fieldTree(): JiraFieldTreeDto {
        val catalogue = fieldCatalogue()
        val labels = FieldLabels(catalogue)
        val selected = graphDriver.executeRead(
            Query(JiraCypher.SELECTED_COLUMNS, mapOf("sourceId" to JiraId.SOURCE)),
        ) { records -> records.map { it.get("path").asString("") }.toSet() }

        val discovered = graphDriver.executeRead(
            Query(
                JiraCypher.DISCOVER_FIELD_PATHS,
                mapOf("scanLimit" to DISCOVERY_SCAN_LIMIT, "limit" to MAX_DISCOVERED_PATHS),
            ),
        ) { records ->
            records.map {
                DiscoveredPath(
                    path = it.get("path").asString(""),
                    sample = it.get("sample").asString(""),
                )
            }.filter { it.path.isNotBlank() }
        }

        val byField = discovered.groupBy { JiraFields.splitPath(it.path).first }

        // The union, and the union is the point. Discovery reads `keys(i)`, and a field that is
        // unset on every issue is null in the JSON, which `SET n += props` *removes* — so it is a
        // key on no node and discovery is blind to it, however many issues are scanned. The
        // catalogue is the only thing that knows such a field exists.
        //
        // Structural fields are dropped from both sides: the flattener never writes `issuelinks`
        // or `comment`, so offering them would be offering a column that can never have a value.
        val fieldIds = LinkedHashSet<String>()
        fieldIds += catalogue.map { it.id }
        fieldIds += byField.keys
        fieldIds.removeAll(JiraFieldId.structural)

        val entries = catalogue.associateBy { it.id }
        val fields = fieldIds
            .sortedBy { labels.labelFor(it).lowercase() }
            .map { fieldId -> fieldNode(fieldId, byField[fieldId].orEmpty(), entries[fieldId], labels, selected) }

        // A selected path is stale only when *neither* source knows it: no imported issue carries
        // it and JIRA's own catalogue no longer lists its field. Warning on the data alone would
        // fire on every correctly-selected column of an always-empty field, which is exactly the
        // case this whole method was rewritten for.
        val knownPaths = discovered.mapTo(HashSet()) { it.path } + catalogue.map { it.id }
        val warnings = selected
            .filterNot { it in knownPaths || it in JiraFieldId.fixedColumns }
            .sorted()
            .map { "The column '${labels.labelFor(it)}' is selected but JIRA no longer has that field." }

        return JiraFieldTreeDto(fields = fields, warnings = warnings)
    }

    /**
     * One field of the tree: what the data found under it, what the catalogue says about it, or
     * both.
     *
     * Three states, and the difference between the last two is the whole of this change:
     *
     *  - **paths found** — as before; the field, or its sub-keys, are offered with real samples.
     *  - **catalogue only, scalar type** — no issue has a value, but the schema states the path the
     *    flattener will write, so it is offered now and its column is blank until one does.
     *  - **catalogue only, object or array-of-object type** — offered as a name and a type, and
     *    *not selectable*, because its sub-keys come from data and there is none. The alternative
     *    is guessing `name` and handing somebody a permanently blank column.
     */
    private fun fieldNode(
        fieldId: String,
        paths: List<DiscoveredPath>,
        entry: CatalogEntry?,
        labels: FieldLabels,
        selected: Set<String>,
    ): JiraFieldNodeDto {
        val own = paths.firstOrNull { it.path == fieldId }
        val children = paths
            .filterNot { it.path == fieldId }
            .sortedBy { it.path }
            .map { child ->
                JiraFieldNodeDto(
                    path = child.path,
                    // The sub-path relative to its field, so the tree reads "Status › name"
                    // rather than repeating the field id on every leaf.
                    label = JiraFields.splitPath(child.path).second ?: child.path,
                    type = labels.typeFor(fieldId),
                    sample = child.sample,
                    selectable = true,
                    hasValues = true,
                    selected = child.path in selected || child.path in JiraFieldId.fixedColumns,
                    fixed = child.path in JiraFieldId.fixedColumns,
                    children = emptyList(),
                )
            }

        val predictedScalar = entry != null &&
            JiraFields.flattensToOwnPath(entry.schemaType, entry.schemaItems)

        return JiraFieldNodeDto(
            path = fieldId,
            label = labels.labelFor(fieldId),
            type = labels.typeFor(fieldId),
            sample = own?.sample.orEmpty(),
            // A field with children and no scalar of its own is a heading in the tree, not a
            // column: there is nothing to put in a cell for `status` alone. A field with neither
            // is selectable only when the catalogue states its shape.
            selectable = own != null || (paths.isEmpty() && predictedScalar),
            hasValues = paths.isNotEmpty(),
            selected = fieldId in selected || fieldId in JiraFieldId.fixedColumns,
            fixed = fieldId in JiraFieldId.fixedColumns,
            children = children,
        )
    }

    /**
     * The field catalogue, as imported from `GET /rest/api/2/field` on every run.
     *
     * Pure metadata, and cheap — one unpaginated request needing no permission at all. It earns its
     * place three times over: it is the only thing that states a field's declared **type**
     * independently of any issue's data, the only thing that knows a field **exists** when every
     * issue leaves it null, and what the import diffs between runs to report a field JIRA has added
     * or withdrawn (design doc §6.4).
     */
    public suspend fun fieldCatalogue(): List<CatalogEntry> =
        graphDriver.executeRead(
            Query(JiraCypher.FIELD_CATALOG, mapOf("limit" to MAX_CATALOG_FIELDS)),
        ) { records ->
            records.mapNotNull { record ->
                val node = record.get("field").asNode()
                val id = node.stringProp(JiraProjectAttr.ID)
                if (id.isBlank()) {
                    null
                } else {
                    CatalogEntry(
                        id = id,
                        name = node.stringProp(Prop.NAME).ifBlank { id },
                        schemaType = node.stringProp(JiraProjectAttr.SCHEMA_TYPE),
                        schemaItems = node.stringProp(JiraProjectAttr.SCHEMA_ITEMS),
                    )
                }
            }
        }

    /** The catalogue's id → name and id → declared type, for wording every path. */
    public suspend fun fieldLabels(): FieldLabels = FieldLabels(fieldCatalogue())

    /** One entry of `GET /rest/api/2/field`, as stored. */
    public data class CatalogEntry(
        public val id: String,
        public val name: String,
        public val schemaType: String,
        public val schemaItems: String,
    )

    /** Every field id the catalogue currently holds — what the import diffs against (§6.4). */
    public suspend fun catalogFieldIds(): Set<String> =
        graphDriver.executeRead(
            Query(JiraCypher.FIELD_CATALOG, mapOf("limit" to MAX_CATALOG_FIELDS)),
        ) { records ->
            records.mapNotNull { it.get("field").asNode().stringProp(JiraProjectAttr.ID).takeIf(String::isNotBlank) }
                .toSet()
        }

    /** Which of these paths no imported issue carries — the import report's stale-column warning. */
    public suspend fun unknownSelectedPaths(): List<String> = fieldTree().warnings

    public data class ProjectScope(public val key: String, public val jql: String)

    private data class DiscoveredPath(val path: String, val sample: String)

    /**
     * The catalogue, indexed. A path is worded by its *field's* name — `status.name` reads as
     * **Status**, with the sub-key shown separately in the tree — because JIRA names the field and
     * has no name at all for a sub-key.
     */
    public class FieldLabels(catalogue: List<CatalogEntry>) {
        private val names: Map<String, String> = catalogue.associate { it.id to it.name }
        private val types: Map<String, String> = catalogue.associate { it.id to it.schemaType }

        public fun labelFor(path: String): String {
            val (field, sub) = JiraFields.splitPath(path)
            val base = names[field] ?: field
            return if (sub == null) base else "$base ${SUB_KEY_SEPARATOR} $sub"
        }

        public fun typeFor(path: String): String = types[JiraFields.splitPath(path).first].orEmpty()

        private companion object {
            /** Reads as a path in a column header without repeating the word "field". */
            const val SUB_KEY_SEPARATOR: String = "›"
        }
    }

    private companion object {
        const val MAX_PROJECTS: Int = 500
        const val MAX_PAGE_SIZE: Int = 500
        const val MAX_CATALOG_FIELDS: Int = 2000

        /**
         * How many issues field discovery reads, and how many paths it may return.
         *
         * The DOORS equivalent reads the whole module, because attribute sets are not uniform
         * within one and a missed name is a column the dialog cannot offer. JIRA is the opposite
         * case: a field is defined per project and per issue type rather than per issue, so a
         * bounded sample finds every path that more than a handful of issues carry — and reading
         * every issue's whole property map on a dialog open is what the Statistics view already
         * learned not to do.
         *
         * **Both numbers are measured against a real instance, not guessed** (`docs/TEST_JIRA_DATA.json`,
         * one page of a live Data Center search): 1 041 raw fields per issue flatten to 1 729
         * distinct paths, of which **735 are non-null on at least one issue**. Only those 735 ever
         * become node properties, because `SET n += props` removes a property whose value is null —
         * so the number the dialog actually shows is the smaller one.
         *
         * The scan limit is 500 rather than a few thousand because this `UNWIND keys(i)` produces
         * one row per issue per property: at 500 issues that is ~350 000 rows to aggregate, and at
         * 5 000 it would be three and a half million on a dialog open. The path ceiling is well
         * clear of 735 so a busier instance is not silently truncated alphabetically — and the
         * dialog has a search box, which is what makes a list this long usable at all.
         */
        const val DISCOVERY_SCAN_LIMIT: Int = 500
        const val MAX_DISCOVERED_PATHS: Int = 2500
    }
}

private fun Node.stringProp(name: String): String =
    if (containsKey(name)) get(name).asString("") else ""

/**
 * One property as JSON, for the wire.
 *
 * A JIRA field can be a string, a number, a boolean or a list of any of those, and the frontend
 * renders whatever it is given — so the value keeps its type rather than being stringified into a
 * cell. An absent path is `null`, which is the "renders blank, is not an error" case §6.4 asks for.
 */
private fun Node.jsonValue(path: String): JsonElement {
    if (!containsKey(path)) return JsonNull
    val value = get(path)
    return when {
        value.isNull -> JsonNull
        else -> runCatching { value.asObject().toJson() }.getOrElse { JsonPrimitive(value.toString()) }
    }
}

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}
