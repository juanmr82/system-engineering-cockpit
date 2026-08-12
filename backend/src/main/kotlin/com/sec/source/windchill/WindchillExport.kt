package com.sec.source.windchill

import com.sec.importer.ImportRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One Windchill document, as one row of an export file becomes it.
 *
 * A value, produced by [WindchillExportParser] and consumed by [WindchillGraphWriter] — nothing
 * between them talks to the graph or to OData, so every rule this source has about identity and
 * ordering is a pure function with a test rather than a statement with a fixture.
 *
 * @property id the node's `__id`: the document's OData resource URL. See [WindchillExportParser].
 * @property oid Windchill's own object id, which the info-page link is built from and which is
 *   never shown (the user's instruction, and [WindchillProp.OID]).
 * @property properties everything that reaches the node verbatim, keyed by [WindchillProp]. Only
 *   keys the file actually carried are present — an absent field is absent, not empty, because
 *   `""` already means "exists and is empty" everywhere else in this application.
 */
public data class WindchillRecord(
    public val id: String,
    public val oid: String,
    public val number: String,
    public val version: String,
    public val name: String,
    public val properties: Map<String, String>,
) {
    /** The Tier-1 order key. Derived here so the writer has nothing left to decide. */
    public val sortKey: String get() = WindchillSortKey.derive(number, version)
}

/**
 * A parsed export file, and everything the importer needs to know about how it read.
 *
 * It is the run's [ImportRequest]: the file arrives with the request that starts the import and
 * cannot be fetched again, so it travels to the run rather than being left in a slot on the
 * importer.
 *
 * @property nextLink the `@odata.nextLink` the file carried, if any. **Not followed** — this
 *   importer is fed a file, not a connection — but reported, because a file that says there is
 *   another page is a file that is not the whole truth, and the sweep is about to treat it as the
 *   whole truth. The user is still allowed to import it; they are told.
 * @property warnings findings from the parse itself: rows skipped, ids repeated, versions this code
 *   could not order. Carried rather than logged, so they land on the run where they are read.
 */
public data class WindchillExport(
    public val records: List<WindchillRecord>,
    public val nextLink: String? = null,
    public val warnings: List<String> = emptyList(),
) : ImportRequest

/** Why a file could not be read at all, as opposed to one row inside it being unusable. */
public sealed interface WindchillExportProblem {
    /** The bytes are not JSON. [detail] is the parser's own message, which names the position. */
    public data class NotJson(public val detail: String) : WindchillExportProblem

    /** JSON, but not an OData collection: no top-level object, or no `value` array in it. */
    public data class NotAnExport(public val detail: String) : WindchillExportProblem

    /** Read cleanly and held no usable document. See [WindchillExportParser] on why that is fatal. */
    public data class NoDocuments(public val skipped: Int) : WindchillExportProblem
}

/** Carries a [WindchillExportProblem] through `Result`. Never thrown out of the parse. */
public class WindchillExportFailure(
    public val problem: WindchillExportProblem,
) : Exception(problem.toString())

/**
 * Reads a Windchill OData `Documents` export.
 *
 * ## Standard JSON only
 *
 * The exporter producing these files is a script driving a browser session, and an earlier version
 * of it emitted Python dict syntax. That is not accepted and is not worked around: a lenient reader
 * for a format nobody intends to produce is a permanent liability taken on for a transitional
 * mistake, and what it prevents is a `400` naming the exact offset — which is a better bug report
 * than a silent success.
 *
 * ## Why an empty file is refused rather than imported
 *
 * The sweep treats this file as the truth, so importing a file with no documents means deleting
 * every Windchill document in the graph. That is a legitimate thing to want and a terrible thing to
 * do by accident — an export that failed produces exactly the same file — so it is refused at the
 * door, with a count of what was skipped. Emptying the source is done by emptying it in Windchill
 * and importing a file that proves it.
 */
public object WindchillExportParser {

    /** OData's own keys. Not graph names — none of them reaches a node — so they live here. */
    private const val VALUE = "value"
    private const val ODATA_ID = "@odata.id"
    private const val ODATA_NEXT_LINK = "@odata.nextLink"
    private const val STATE = "State"
    private const val STATE_VALUE = "Value"
    private const val STATE_DISPLAY = "Display"

    /** How many of a repeated finding a warning names before it counts instead of listing. */
    private const val LISTED = 10

    private val json = Json { ignoreUnknownKeys = true }

    public fun parse(text: String): Result<WindchillExport> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse { cause ->
            return failure(WindchillExportProblem.NotJson(cause.message.orEmpty()))
        }

        val obj = root as? JsonObject ?: return failure(
            WindchillExportProblem.NotAnExport("The file's top level is not a JSON object."),
        )

        val rows = obj[VALUE] as? JsonArray ?: return failure(
            WindchillExportProblem.NotAnExport(
                "The file has no 'value' array. An OData collection response carries its rows in one.",
            ),
        )

        val records = LinkedHashMap<String, WindchillRecord>()
        val skippedNoOid = mutableListOf<Int>()
        val synthesisedIds = mutableListOf<String>()
        val repeated = mutableListOf<String>()
        val unreadableVersions = mutableListOf<String>()
        var skippedNotAnObject = 0

        rows.forEachIndexed { index, row ->
            val item = row as? JsonObject
            if (item == null) {
                skippedNotAnObject++
                return@forEachIndexed
            }

            val oid = item.string(WindchillProp.OID).orEmpty()
            if (oid.isBlank()) {
                // Without it there is no info-page link and, worse, no fallback identity.
                skippedNoOid += index
                return@forEachIndexed
            }

            // Identity is the resource URL, the rule every other source follows (R6). The fallback
            // is the oid, which is unique within a Windchill instance — but it is a *different*
            // identity, so a file omitting @odata.id re-keys every document it carries. Worth a
            // warning; not worth refusing the file over.
            val id = item.string(ODATA_ID)?.takeIf { it.isNotBlank() }
                ?: oid.also { synthesisedIds += oid }

            val number = item.string(WindchillProp.NUMBER).orEmpty()
            val version = item.string(WindchillProp.VERSION).orEmpty()
            if (version.isNotBlank() && !WindchillSortKey.isReadable(version)) {
                unreadableVersions += version
            }

            val properties = buildMap {
                put(WindchillProp.OID, oid)
                item.string(WindchillProp.FOLDER_LOCATION)?.let { put(WindchillProp.FOLDER_LOCATION, it) }
                item.string(WindchillProp.NAME)?.let { put(WindchillProp.NAME, it) }
                item.string(WindchillProp.NUMBER)?.let { put(WindchillProp.NUMBER, it) }
                item.string(WindchillProp.VERSION)?.let { put(WindchillProp.VERSION, it) }
                // The one nested value, flattened to two scalars with neither of them touched —
                // see WindchillProp.STATE_VALUE.
                (item[STATE] as? JsonObject)?.let { state ->
                    state.string(STATE_VALUE)?.let { put(WindchillProp.STATE_VALUE, it) }
                    state.string(STATE_DISPLAY)?.let { put(WindchillProp.STATE_DISPLAY, it) }
                }
            }

            val record = WindchillRecord(
                id = id,
                oid = oid,
                number = number,
                version = version,
                // Falls back rather than being blank: a row with no Name still has to be findable,
                // and its number is what a person would call it.
                name = item.string(WindchillProp.NAME)?.takeIf { it.isNotBlank() }
                    ?: number.takeIf { it.isNotBlank() }
                    ?: oid,
                properties = properties,
            )

            // First wins. A file reporting one document twice is reporting one document.
            if (records.putIfAbsent(id, record) != null) repeated += id
        }

        val warnings = buildList {
            if (skippedNotAnObject > 0) {
                add("$skippedNotAnObject row(s) were not objects and were skipped.")
            }
            if (skippedNoOid.isNotEmpty()) {
                add(
                    "${skippedNoOid.size} row(s) had no ID and were skipped " +
                        "(row ${skippedNoOid.joinToString(", ", limit = LISTED)}).",
                )
            }
            if (synthesisedIds.isNotEmpty()) {
                add(
                    "${synthesisedIds.size} row(s) had no @odata.id, so their Windchill ID was used " +
                        "as identity instead. An export that does carry @odata.id will import them " +
                        "as new documents and remove these.",
                )
            }
            if (repeated.isNotEmpty()) {
                add(
                    "${repeated.distinct().size} document(s) appeared more than once and were read " +
                        "once (${repeated.distinct().joinToString(", ", limit = LISTED)}).",
                )
            }
            if (unreadableVersions.isNotEmpty()) {
                add(
                    "${unreadableVersions.distinct().size} version string(s) carry no number and " +
                        "could not be ordered newest-first " +
                        "(${unreadableVersions.distinct().joinToString(", ", limit = LISTED)}).",
                )
            }
        }

        if (records.isEmpty()) {
            return failure(
                WindchillExportProblem.NoDocuments(skipped = skippedNoOid.size + skippedNotAnObject),
            )
        }

        return Result.success(
            WindchillExport(
                records = records.values.toList(),
                nextLink = obj.string(ODATA_NEXT_LINK)?.takeIf { it.isNotBlank() },
                warnings = warnings,
            ),
        )
    }

    private fun failure(problem: WindchillExportProblem): Result<WindchillExport> =
        Result.failure(WindchillExportFailure(problem))

    /** A string field, or null when absent or not a string. Numbers and booleans are not coerced. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
