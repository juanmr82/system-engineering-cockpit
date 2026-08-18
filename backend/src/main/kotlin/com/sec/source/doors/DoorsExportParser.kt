package com.sec.source.doors

import com.sec.domain.ItemVersion
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a DOORS module export — the JSON the Python importer's DXL step produces, ported from
 * `importers/src/sec_import/doors/{parser,derivations,importer}.py` (ADR 0019 §1, §7).
 *
 * ## Everything is a string until proven otherwise
 *
 * A real DOORS export stringifies every value, `objectLevel` included — the fixture importers
 * already ship (`smoke_module_current.json`) shows `"objectLevel": "1"`, a JSON string, not a
 * number. This parser follows the Python importer's own choice: read every attribute as text, and
 * coerce only the handful of fields it explicitly names (`objectLevel`, the table row/column index,
 * `Absolute Number`). Inventing broader type inference here would be a second, undocumented set of
 * coercion rules that the Python importer does not have and a re-import through it would not agree
 * with.
 */
public object DoorsExportParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** How many of a repeated finding a warning names before it counts instead of listing (matches
     *  [com.sec.source.windchill.WindchillExportParser]'s own convention). */
    private const val LISTED = 10

    /** The DXL export caps a module at 12 000 objects; at or above that, it is probably a truncated
     *  file rather than a genuinely enormous module. */
    private const val TRUNCATION_THRESHOLD = 12_000

    private val REQUIRED_MODULE_KEYS = listOf("__objectId", "__name", "__version", "url", "__contents")

    /** Keys `parser.py`'s `OBJECT_META_KEYS` names — importer bookkeeping, never copied into `props`
     *  verbatim because each is already handled by name in [buildObjectProps]. */
    private val OBJECT_META_KEYS = setOf(
        "__tableObject", "__tableID", "__tableURL", "__tableRowIndex", "__tableColumnIndex",
        "id", "objectNumber", "objectLevel", "__moduleUrl", "__objectUrl",
        "__outputLinks", "__inputLinks",
    )

    /**
     * Parses [bytes] as a DOORS module export.
     *
     * The checksum is computed from [bytes] directly, before anything is decoded or interpreted —
     * it answers "is this the same file", not "did this parse to the same structure" (ADR 0019 §3).
     */
    public fun parse(bytes: ByteArray): Result<DoorsExport> {
        val checksum = sha256Hex(bytes)
        val text = bytes.toString(Charsets.UTF_8)

        val root = runCatching { json.parseToJsonElement(text) }.getOrElse { cause ->
            return failure(DoorsExportProblem.NotJson(cause.message.orEmpty()))
        }
        val module = root as? JsonObject ?: return failure(
            DoorsExportProblem.NotAnExport("The file's top level is not a JSON object."),
        )

        for (key in REQUIRED_MODULE_KEYS) {
            if (key !in module) {
                return failure(DoorsExportProblem.Invalid("Required module key '$key' is missing."))
            }
        }

        val url = module.string("url")?.trim().orEmpty()
        val version = module.string("__version").orEmpty()
        moduleUrlVersionMismatch(url, version)?.let { return failure(DoorsExportProblem.Invalid(it)) }

        val contents = module["__contents"] as? JsonArray ?: return failure(
            DoorsExportProblem.NotAnExport("The file has no '__contents' array."),
        )
        val rawObjects = contents.mapNotNull { it as? JsonObject }

        val warnings = buildList {
            findDuplicateKeys(text).let { duplicates ->
                if (duplicates.isNotEmpty()) {
                    add(
                        "${duplicates.size} duplicate JSON key(s) were found in the file " +
                            "(${duplicates.distinct().joinToString(", ", limit = LISTED)}). Each is " +
                            "kept once, with the *last* value in the file — unlike the Python " +
                            "importer, which keeps the first. Fix the duplicate at the source.",
                    )
                }
            }

            val mismatched = rawObjects.filter { obj ->
                val objUrl = obj.string("__moduleUrl")?.trim().orEmpty()
                objUrl.isNotEmpty() && objUrl != url
            }
            if (mismatched.isNotEmpty()) {
                add(
                    "${mismatched.size} object(s) carry a __moduleUrl that differs from this " +
                        "module's own url " +
                        "(${mismatched.mapNotNull { it.string("id") }.joinToString(", ", limit = LISTED)}).",
                )
            }

            if (rawObjects.size >= TRUNCATION_THRESHOLD) {
                add(
                    "This module has ${rawObjects.size} objects (>= $TRUNCATION_THRESHOLD) — the " +
                        "export is probably truncated.",
                )
            }
        }

        val flatObjects = rawObjects.map { it.toFlatStringMap() }
        val (tableIds, tableRowIds) = DoorsDerivations.computeTableSets(flatObjects)

        val objects = rawObjects.indices.mapNotNull { index ->
            buildObjectRow(rawObjects[index], flatObjects[index], version, tableIds, tableRowIds)
        }

        return Result.success(
            DoorsExport(
                moduleId = url,
                moduleName = module.string("__name").orEmpty(),
                moduleVersion = version,
                moduleProps = buildModuleProps(module, url, version),
                objects = objects,
                checksum = checksum,
                warnings = warnings,
            ),
        )
    }

    /** The URL/version consistency check `parser.py` runs before trusting either. */
    private fun moduleUrlVersionMismatch(url: String, version: String): String? {
        if (url.contains("-M-") && version != ItemVersion.CURRENT) {
            return "Module URL contains -M- (current) but __version is '$version' " +
                "(expected '${ItemVersion.CURRENT}')."
        }
        if (url.contains("-B-")) {
            val rest = url.substringAfterLast("-B-") // "<modId>-<versionId>"
            val versionId = rest.substringAfter("-")
            if (version != versionId) {
                return "Module URL version '$versionId' does not match __version '$version'."
            }
        }
        return null
    }

    private fun buildObjectRow(
        raw: JsonObject,
        flat: Map<String, String>,
        moduleVersion: String,
        tableIds: Set<String>,
        tableRowIds: Set<String>,
    ): DoorsObjectRow? {
        // Matches importer.py's own filter — an object with no __objectUrl has nothing to key a
        // node on and is silently excluded from every phase that follows, the same as the source.
        val objectUrl = flat["__objectUrl"]?.takeIf { it.isNotEmpty() } ?: return null

        return DoorsObjectRow(
            objectUrl = objectUrl,
            objectNumber = flat["objectNumber"].orEmpty(),
            labels = DoorsDerivations.deriveLabels(flat, tableIds, tableRowIds),
            props = buildObjectProps(raw, flat, objectUrl, moduleVersion),
            outputLinks = raw.linkArray("__outputLinks"),
            inputLinks = raw.linkArray("__inputLinks"),
        )
    }

    /** `_prepare_object_props`, ported: Tier-1 fields, type coercion, then every remaining DOORS
     *  attribute verbatim, with empty strings and nulls dropped (`""` elsewhere in the object stays,
     *  a dropped key here just means "this object has nothing under this attribute at all"). */
    private fun buildObjectProps(
        raw: JsonObject,
        flat: Map<String, String>,
        objectUrl: String,
        moduleVersion: String,
    ): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        props["__id"] = objectUrl
        props["__name"] = DoorsDerivations.deriveName(flat)
        props["__version"] = moduleVersion
        props["id"] = flat["id"].orEmpty()
        props["objectNumber"] = flat["objectNumber"].orEmpty()
        props["__moduleUrl"] = flat["__moduleUrl"].orEmpty()
        props["__objectUrl"] = objectUrl

        flat["objectLevel"]?.toIntOrNull()?.let { props["objectLevel"] = it }

        props["__tableObject"] = flat["__tableObject"] == "true"
        props["__tableID"] = flat["__tableID"].orEmpty()
        props["__tableURL"] = flat["__tableURL"].orEmpty()
        flat["__tableRowIndex"]?.toIntOrNull()?.let { props["__tableRowIndex"] = it }
        flat["__tableColumnIndex"]?.toIntOrNull()?.let { props["__tableColumnIndex"] = it }

        props["__sortKey"] = DoorsDerivations.sortKey(flat["objectNumber"].orEmpty())

        val objectType = flat["Object Type"].orEmpty()
        if (DoorsDerivations.deriveTypeLabel(objectType).second) props["__typeRaw"] = objectType

        // Drop the empty ones among the DERIVED properties above. "" there means "we had nothing
        // to put here" — an object with no table id is not in a table — so storing it adds no
        // information. Done before the source attributes are added, deliberately: the same is not
        // true of them.
        val out = LinkedHashMap<String, Any?>(props.filterValues { it != "" && it != null })

        // An empty SOURCE attribute is KEPT, and that is why the filter above is separate.
        //
        // `""` from DOORS means "this attribute exists on this object and has no value", which is
        // a different fact from "this object does not have this attribute" (CLAUDE.md §11). The
        // alias map renders the first as *Empty*, in `--sec-ink-3`, precisely because a blank cell
        // would read as the panel having failed to show something. Dropping them made the two
        // states indistinguishable downstream and left that *Empty* state unreachable for DOORS
        // data — the rule was stated and then not implemented.
        //
        // Measured over the three committed real exports: empties are 2 % of all attribute values,
        // and no attribute is empty across a whole module — so keeping them costs almost nothing
        // and adds no column to any view, because attribute discovery already finds each of these
        // through the objects that do populate them. See ADR 0022.
        //
        // A JSON null is still skipped by the `?: continue` above; the DXL export does not emit
        // one for an attribute an object carries.
        for ((key, value) in raw) {
            if (key in OBJECT_META_KEYS) continue
            val content = (value as? JsonPrimitive)?.content ?: continue
            out[key] = if (key == "Absolute Number") content.toIntOrNull() ?: content else content
        }

        return out
    }

    /** `_prepare_module_props`, ported: every top-level key except `__contents`, then `__id`,
     *  `__name` and `__version` are set from the derived values rather than left as whatever the
     *  export happened to carry under those names. */
    private fun buildModuleProps(module: JsonObject, url: String, version: String): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        for ((key, value) in module) {
            if (key == "__contents") continue
            val content = (value as? JsonPrimitive)?.content ?: continue
            props[key] = content
        }
        props["__id"] = url
        props["__name"] = module.string("__name").orEmpty()
        props["__version"] = version
        return props
    }

    private fun JsonObject.linkArray(key: String): List<DoorsLinkRef> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val reqDocumentUrl = obj.string("reqDocumentURL")?.trim().orEmpty()
            val absoluteNumber = obj.string("absoluteNumber")?.trim().orEmpty()
            if (reqDocumentUrl.isEmpty() || absoluteNumber.isEmpty()) null
            else DoorsLinkRef(reqDocumentUrl, absoluteNumber)
        }

    /** Every primitive top-level key, as text — what [DoorsDerivations] reads. Skips the two link
     *  arrays and anything else that is not a scalar, the same objects [buildObjectProps] copies
     *  verbatim by iterating [JsonObject] itself. */
    private fun JsonObject.toFlatStringMap(): Map<String, String> =
        entries.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }.toMap()

    private fun failure(problem: DoorsExportProblem): Result<DoorsExport> =
        Result.failure(DoorsExportFailure(problem))

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Finds repeated object keys anywhere in [text], at any nesting depth (ADR 0019 §6).
     *
     * `kotlinx.serialization`'s [Json] has no `object_pairs_hook` equivalent — the parse above
     * silently keeps the last value for a repeated key — so this walks the raw text once, tracking
     * only what it needs to: string boundaries (respecting `\"` and other escapes) and container
     * depth. A string immediately followed by `:` is a key by JSON grammar — that can never be true
     * of a value — so the object at the top of [stack] when that happens is unambiguously the one it
     * belongs to, with no need to otherwise distinguish "am I reading a key or a value" position.
     *
     * Only called after [json] has already accepted [text], so the input is known-valid JSON and
     * this never has to handle a malformed document itself.
     */
    private fun findDuplicateKeys(text: String): List<String> {
        val findings = mutableListOf<String>()
        // One entry per open container: a key set for an object, null for an array (arrays carry no
        // keys, but still have to occupy a stack slot to keep nesting depth correct).
        val stack = ArrayDeque<MutableSet<String>?>()
        var i = 0
        val n = text.length

        fun readString(): String {
            val start = i
            i++ // opening quote
            while (i < n) {
                when (text[i]) {
                    '\\' -> i += 2
                    '"' -> {
                        i++
                        return text.substring(start + 1, i - 1)
                    }
                    else -> i++
                }
            }
            return text.substring(start + 1)
        }

        while (i < n) {
            when (text[i]) {
                '"' -> {
                    val key = readString()
                    var j = i
                    while (j < n && text[j].isWhitespace()) j++
                    if (j < n && text[j] == ':') {
                        val currentObject = stack.lastOrNull()
                        if (currentObject != null && !currentObject.add(key)) findings += key
                    }
                }
                '{' -> {
                    stack.addLast(mutableSetOf())
                    i++
                }
                '[' -> {
                    stack.addLast(null)
                    i++
                }
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    i++
                }
                else -> i++
            }
        }
        return findings
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
