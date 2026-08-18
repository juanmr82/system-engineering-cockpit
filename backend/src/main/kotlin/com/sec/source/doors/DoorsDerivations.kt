package com.sec.source.doors

import com.sec.domain.NodeLabel.SE_ITEM

/** A DOORS object or module URL matched neither of DOORS's two shapes (`-M-` current, `-B-` baseline). */
public class MalformedUrlError(message: String) : Exception(message)

/**
 * Pure derivation functions, ported from `importers/src/sec_import/doors/derivations.py`
 * (ADR 0019 §7) — no Neo4j, no Ktor, no JSON parsing. [DoorsExportParser] is the only caller, and
 * every case here has a matching case in `DoorsDerivationsTest.kt`, mirrored one-for-one from
 * `doors/tests/test_derivations.py` so the two stay provably in step.
 *
 * Every function takes plain `Map<String, String>` rather than a typed object, the same way the
 * Python functions take a plain `dict` — an object's raw attribute bag has no fixed shape (a module
 * carries whatever attributes it was configured with), and inventing a struct here would just be a
 * second, narrower copy of what [DoorsExportParser] already reads off the JSON.
 */
public object DoorsDerivations {

    private val OBJECT_TYPE_TO_LABEL: Map<String, String> = mapOf(
        "Heading" to DoorsLabel.HEADING,
        "AppMatrixHeading" to DoorsLabel.APP_MATRIX_HEADING,
        "Requirement" to DoorsLabel.REQUIREMENT,
        "Information" to DoorsLabel.INFORMATION,
        "AppMatrix" to DoorsLabel.APP_MATRIX,
        "TBD" to DoorsLabel.TBD,
        "" to DoorsLabel.TBD,
    )

    /** An object's DOORS resource URL, derived from its module's URL and an Absolute Number (R6). */
    public fun targetObjectUrl(reqDocumentUrl: String, absoluteNumber: String): String {
        val u = reqDocumentUrl.trim()
        if (u.contains("-M-")) {
            val head = u.substringBeforeLast("-M-")
            val modId = u.substringAfterLast("-M-")
            return "$head-O-$absoluteNumber-$modId"
        }
        if (u.contains("-B-")) {
            val head = u.substringBeforeLast("-B-")
            val rest = u.substringAfterLast("-B-") // "<modId>-<versionId>"
            return "$head-V-$absoluteNumber-$rest"
        }
        throw MalformedUrlError("Cannot derive object URL from: '$u'")
    }

    /** `"current"` for a `-M-` (current) module URL, or the baseline's version id for a `-B-` one. */
    public fun targetVersion(reqDocumentUrl: String): String {
        val u = reqDocumentUrl.trim()
        if (u.contains("-M-")) return "current"
        if (u.contains("-B-")) {
            val rest = u.substringAfterLast("-B-") // "<modId>-<versionId>", versionId may hold dashes
            return rest.substringAfter("-")
        }
        throw MalformedUrlError("Cannot derive version from: '$u'")
    }

    /** The parent's `objectNumber` by dropping the last dot-segment, or null for a root object. */
    public fun parentNumber(n: String): String? =
        if (n.contains('.')) n.substringBeforeLast(".") else null

    /** Zero-pads every dash-separated part of every dot-segment to 6 digits, for document order (R3). */
    public fun sortKey(n: String): String =
        n.split(".").joinToString(".") { segment ->
            segment.split("-").joinToString("-") { it.padStart(SORT_KEY_WIDTH, '0') }
        }

    /** `(label, isUnknown)` — a blank or unrecognised `Object Type` becomes [DoorsLabel.TBD]. */
    public fun deriveTypeLabel(objectType: String): Pair<String, Boolean> {
        val label = OBJECT_TYPE_TO_LABEL[objectType] ?: return DoorsLabel.TBD to true
        return label to false
    }

    /** The three-level fallback chain: heading/short text, then `Object Text` (truncated), then id. */
    public fun deriveName(obj: Map<String, String>): String {
        val objectType = obj["Object Type"].orEmpty()
        val base = if (objectType == "Heading" || objectType == "AppMatrixHeading") {
            obj["Object Heading"].orEmpty()
        } else {
            obj["Object Short Text"].orEmpty()
        }
        if (base.isNotEmpty()) return base

        val text = obj["Object Text"].orEmpty()
        if (text.isNotEmpty()) {
            return if (text.length > NAME_TRUNCATE_AT) text.take(NAME_TRUNCATE_AT) + "…" else text
        }

        return obj["id"]?.takeIf { it.isNotEmpty() } ?: "<unknown>"
    }

    /**
     * `(tableIds, tableRowIds)`, both sets of `id` values: [DoorsLabel.TABLE] is every `id` a cell's
     * `__tableID` names; [DoorsLabel.TABLE_ROW] is every object that is both a `__child` of one of
     * those and a `__child`-parent of a cell.
     */
    public fun computeTableSets(objects: List<Map<String, String>>): Pair<Set<String>, Set<String>> {
        val tableIds = objects
            .filter { it["__tableObject"] == "true" }
            .mapNotNull { it["__tableID"]?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        val numToId = mutableMapOf<String, String>()
        val idToNum = mutableMapOf<String, String>()
        for (obj in objects) {
            val id = obj["id"].orEmpty()
            val num = obj["objectNumber"].orEmpty()
            if (id.isNotEmpty() && num.isNotEmpty()) {
                numToId[num] = id
                idToNum[id] = num
            }
        }

        val parentToChildren = mutableMapOf<String, MutableList<String>>()
        for (obj in objects) {
            val num = obj["objectNumber"].orEmpty()
            val parent = parentNumber(num) ?: continue
            parentToChildren.getOrPut(parent) { mutableListOf() }.add(num)
        }

        val numIsCell: Map<String, Boolean> = objects
            .filter { it["objectNumber"]?.isNotEmpty() == true }
            .associate { it["objectNumber"]!! to (it["__tableObject"] == "true") }

        val tableNums = tableIds.mapNotNull { idToNum[it] }.toSet()
        val tableRowIds = mutableSetOf<String>()
        for (tableNum in tableNums) {
            for (childNum in parentToChildren[tableNum].orEmpty()) {
                val hasCellChild = parentToChildren[childNum].orEmpty().any { numIsCell[it] == true }
                if (hasCellChild) numToId[childNum]?.let { tableRowIds += it }
            }
        }

        return tableIds to tableRowIds
    }

    /** The full label set for one object — [SE_ITEM] and [DoorsLabel.OBJECT] are always in it. */
    public fun deriveLabels(
        obj: Map<String, String>,
        tableIds: Set<String>,
        tableRowIds: Set<String>,
    ): Set<String> {
        val labels = mutableSetOf(SE_ITEM, DoorsLabel.OBJECT)
        val (typeLabel, _) = deriveTypeLabel(obj["Object Type"].orEmpty())
        labels += typeLabel

        val id = obj["id"].orEmpty()
        if (obj["__tableObject"] == "true") labels += DoorsLabel.TABLE_CELL
        if (id in tableIds) labels += DoorsLabel.TABLE
        if (id in tableRowIds) labels += DoorsLabel.TABLE_ROW

        return labels
    }

    private const val SORT_KEY_WIDTH = 6
    private const val NAME_TRUNCATE_AT = 120
}
