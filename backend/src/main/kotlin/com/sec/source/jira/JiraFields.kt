package com.sec.source.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The pure half of the JIRA importer: an issue's `fields` block in, graph properties out.
 *
 * No driver, no Ktor, no HTTP. Everything decision-shaped about the shape of a JIRA issue is here
 * and is unit-tested without a database, for the reason the ELK layout code gives (ADR 0011): the
 * client and the transport are replaceable and this is not.
 *
 * ## Why flatten at all, when [JiraProp.RAW_FIELDS] keeps the block verbatim
 *
 * The design doc (§3) proposed flattening only the fields an admin had selected for display. That
 * makes imported data a function of application configuration — a column added in the dialog would
 * show blank until somebody re-imported, and the graph would hold a different set of properties on
 * Monday than on Friday for reasons no export explains.
 *
 * So everything is flattened, and the selection happens on read. That is exactly what the DOORS
 * review table already does: the importer copies every attribute, `DISCOVER_ATTRIBUTES` finds them
 * at runtime, and `:__AttributeSetting` decides which are shown. One mechanism, two sources.
 */
public object JiraFields {

    /**
     * How deep into a nested field the flattener goes.
     *
     * `status.statusCategory.name` is depth 3 and is a real thing a reviewer asks for. Below that
     * JIRA's own structures stop being field values and start being embedded documents — an ADF
     * description is nested a dozen levels and means nothing one scalar at a time. Those are what
     * [JiraProp.RAW_FIELDS] is for.
     */
    public const val MAX_DEPTH: Int = 3

    /** Separates a field id from a sub-key. A dot, because that is what §6.2 shows an admin. */
    public const val PATH_SEPARATOR: Char = '.'

    /**
     * Flatten `fields` into the property map an `UNWIND … SET n += row.props` will apply.
     *
     * **A null value is emitted, not skipped**, and that is load-bearing rather than tidy: Cypher's
     * `+=` removes a property whose new value is null, so a field a user cleared in JIRA is cleared
     * here on the next run. Skipping nulls would leave the old value behind for ever, and the graph
     * would go on reporting a resolution that was undone.
     */
    public fun flatten(fields: JsonObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        fields.forEach { (name, value) ->
            if (name !in JiraFieldId.structural) {
                flattenInto(out, name, value, depth = 1)
            }
        }
        return out
    }

    private fun flattenInto(out: MutableMap<String, Any?>, path: String, value: JsonElement, depth: Int) {
        when (value) {
            is JsonNull -> out[path] = null

            is JsonPrimitive -> out[path] = scalarOf(value)

            is JsonObject -> {
                // At the depth limit an object still has to say *something*, and its `name` is what
                // a JIRA object field means to a reader nine times in ten. Anything more detailed
                // than that is a raw-fields question.
                if (depth >= MAX_DEPTH) {
                    (value[JiraFieldId.NAME] as? JsonPrimitive)
                        ?.let { out[path] = scalarOf(it) }
                    return
                }
                if (value.isEmpty()) {
                    out[path] = null
                    return
                }
                value.forEach { (key, child) ->
                    flattenInto(out, "$path$PATH_SEPARATOR$key", child, depth + 1)
                }
            }

            is JsonArray -> flattenArrayInto(out, path, value, depth)
        }
    }

    /**
     * An array becomes a list property, or — when its elements are objects — one list property per
     * sub-key its elements carry.
     *
     * `components` arrives as `[{id, name, self}, …]`, and `components.name` reading
     * `["Avionics", "Power"]` is the column somebody wants. An element missing the sub-key
     * contributes nothing rather than a hole, because a Neo4j list cannot carry a null.
     */
    private fun flattenArrayInto(out: MutableMap<String, Any?>, path: String, array: JsonArray, depth: Int) {
        val values = array.filterNot { it is JsonNull }
        if (values.isEmpty()) {
            out[path] = null
            return
        }

        if (values.all { it is JsonPrimitive }) {
            out[path] = homogeneous(values.map { scalarOf(it as JsonPrimitive) })
            return
        }

        if (depth >= MAX_DEPTH) return

        val objects = values.filterIsInstance<JsonObject>()
        if (objects.isEmpty()) return

        // Ordered by first appearance so a column's position is a function of the data rather than
        // of hash iteration, which would reshuffle the field dialog between runs.
        val subKeys = LinkedHashSet<String>()
        objects.forEach { subKeys += it.keys }

        subKeys.forEach { key ->
            val collected = objects.mapNotNull { it[key] as? JsonPrimitive }
                .filterNot { it is JsonNull }
                .map { scalarOf(it) }
            if (collected.isNotEmpty()) {
                out["$path$PATH_SEPARATOR$key"] = homogeneous(collected)
            }
        }
    }

    /**
     * A Neo4j list property must be homogeneous, and the driver rejects a mixed one at write time
     * with an error naming the batch rather than the field. Two projects defining the same custom
     * field differently is precisely the case the design doc opens with (§3), so this is not
     * hypothetical: when the types disagree, everything becomes its string form and the column is
     * still readable.
     */
    private fun homogeneous(values: List<Any?>): List<Any?> {
        val kinds = values.mapNotNull { it?.javaClass }.distinct()
        return if (kinds.size <= 1) values else values.map { it?.toString() }
    }

    /**
     * JSON has one number type and Neo4j has two, so an integral value is stored as a long — which
     * is what makes `customfield_10032` sort and aggregate as story points rather than as text.
     */
    private fun scalarOf(primitive: JsonPrimitive): Any? = when {
        primitive is JsonNull -> null
        primitive.isString -> primitive.content
        primitive.content == "true" -> true
        primitive.content == "false" -> false
        else -> primitive.content.toLongOrNull()
            ?: primitive.content.toDoubleOrNull()
            ?: primitive.content
    }

    /**
     * The `__sortKey` contract for JIRA: a plain string sort reproduces JIRA's own issue order
     * (R3), which is by project and then by issue number.
     *
     * `PROJ-42` sorts after `PROJ-100` as a string and before it as an issue, which is the same
     * defect the DOORS outline number has and the same reason this property exists at all. The
     * number is zero-padded; the project prefix is left alone so projects group alphabetically.
     *
     * A key that is not `PREFIX-NUMBER` — which JIRA does not produce, but a hand-built fixture
     * might — sorts by itself rather than throwing. There is no correct padding for it, and an
     * import that failed on one malformed key would lose the other twenty thousand issues.
     */
    public fun deriveSortKey(issueKey: String): String {
        val dash = issueKey.lastIndexOf('-')
        if (dash <= 0 || dash == issueKey.lastIndex) return issueKey
        val number = issueKey.substring(dash + 1)
        if (number.any { !it.isDigit() }) return issueKey
        return issueKey.substring(0, dash + 1) + number.padStart(SORT_KEY_DIGITS, '0')
    }

    /** Ten digits covers JIRA's own issue-number ceiling with room to spare. */
    private const val SORT_KEY_DIGITS: Int = 10

    /**
     * `__name` for an issue: its summary, falling back to its key.
     *
     * The fallback is not defensive padding — `summary` is absent from a response whose `fields`
     * were scoped by the caller, and it is null on an issue whose summary a JIRA admin has
     * permission-restricted. An issue rendering as an empty row is worse than one rendering as its
     * key.
     */
    public fun deriveName(fields: JsonObject, issueKey: String): String {
        val summary = (fields[JiraFieldId.SUMMARY] as? JsonPrimitive)
            ?.takeIf { !it.isNullPrimitive() }
            ?.content
            ?.trim()
        return if (summary.isNullOrEmpty()) issueKey else summary
    }

    /** The project key of `PROJ-42`. Empty for a key with no prefix. */
    public fun projectKeyOf(issueKey: String): String {
        val dash = issueKey.lastIndexOf('-')
        return if (dash <= 0) "" else issueKey.substring(0, dash)
    }

    /**
     * Would a field of this declared type flatten to **its own id**, or to sub-keys under it?
     *
     * This exists for the case the data cannot answer. A field that is unset on every issue is
     * `null` in the JSON, and `SET n += props` removes a property whose value is null — so it never
     * becomes a key on any node, and the discovery query that reads `keys(i)` cannot see it. The
     * field is nevertheless *defined* in JIRA and `GET /rest/api/2/field` lists it.
     *
     * For those fields the catalogue's declared schema is the only thing that knows anything, and
     * this says how much: for a scalar it knows the exact path the flattener will write, so the
     * column can be offered before any issue fills it in. For an object or an array of objects it
     * knows only that sub-keys will appear — `status.name`, `status.iconUrl` — and **not which**,
     * because the sub-keys come from the data and there is none. Guessing `name` would hand
     * somebody a column that is blank for ever on a field JIRA calls something else.
     *
     * So: `true` means "offer it", and `false` means "say it exists and that its sub-fields arrive
     * with the first value". Nothing here invents a path.
     */
    public fun flattensToOwnPath(schemaType: String, itemsType: String = ""): Boolean = when {
        schemaType.isBlank() -> false
        // An array of scalars is one list property at the field's own id.
        schemaType == ARRAY_SCHEMA_TYPE -> itemsType in SCALAR_SCHEMA_TYPES
        else -> schemaType in SCALAR_SCHEMA_TYPES
    }

    private const val ARRAY_SCHEMA_TYPE: String = "array"

    /**
     * JIRA schema types whose value is a scalar rather than an object.
     *
     * `any` is deliberately absent: it is JIRA's "this could be anything", and a field the API
     * itself declines to describe is not one this code should claim to know the shape of.
     */
    private val SCALAR_SCHEMA_TYPES: Set<String> =
        setOf("string", "number", "date", "datetime", "boolean")

    /**
     * Split a flattened path into the field id and the sub-key trail, for the selection dialog's
     * tree: `status.statusCategory.name` is the field `status` under the sub-path
     * `statusCategory.name`.
     *
     * A custom field id contains an underscore and never a dot, so the first dot is always the
     * boundary.
     */
    public fun splitPath(path: String): Pair<String, String?> {
        val dot = path.indexOf(PATH_SEPARATOR)
        return if (dot < 0) path to null else path.substring(0, dot) to path.substring(dot + 1)
    }

    private fun JsonPrimitive.isNullPrimitive(): Boolean = this is JsonNull
}
