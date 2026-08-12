package com.sec.source.jira.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a complex JIRA value into the one scalar a table can sort and filter on (spec §7.4).
 *
 * ## Why this exists at all
 *
 * A column whose stored value is `{"self":"…","value":"WSS","id":"38303"}` cannot be sorted. The
 * sortable string is `WSS` — and that string is **derived**, so R2 forbids putting it on the
 * imported node. It goes on a `:__JiraProjection` companion, one per issue, and the read path
 * resolves a column with `coalesce(i[$fieldId], p[$fieldId])`.
 *
 * The projection is **disposable by design**: every one of these can be recomputed from the issue
 * nodes alone, which is what makes a change to the rules below a re-projection rather than a full
 * re-import from JIRA.
 *
 * ## Shape, not type — and first match wins
 *
 * Same argument as [ValueClassifier]: the declared type is open-ended and cannot be switched on.
 * The order of the checks is load-bearing in one place and it is called out where it happens.
 *
 * **An unrecognised shape projects to `null`.** Not the raw JSON, not a `toString()`, not a guess.
 * A table cell containing `{"self":"https://…","id":"38303"}` is worse than an empty one: it is
 * wide, unsortable, and it tells a reader the application understood something it did not.
 */
public object DisplayProjector {

    /**
     * The display scalar, or null when there is no honest one.
     *
     * Returns `String` for a single value and `List<String>` for an array of them, because a
     * multi-valued field's display *is* several strings and joining them here would decide a
     * separator on the frontend's behalf.
     */
    public fun project(value: JsonElement): Any? = when (value) {
        is JsonObject -> projectObject(value)
        is JsonArray -> projectArray(value)
        else -> null
    }

    private fun projectObject(value: JsonObject): String? {
        // `option-with-child` BEFORE `option`, and this ordering is the one thing here that would
        // be a silent bug the other way round. Both carry `value`; only the child case carries
        // `child`. Spec §7.4 lists plain `option` first, which taken literally as "first match
        // wins" would make every option-with-child project to just its parent and leave that row of
        // the table permanently unreachable.
        val childValue = value["child"]?.asObjectOrNull()?.string("value")
        val ownValue = value.string("value")
        if (childValue != null && ownValue != null) return "$ownValue - $childValue"
        if (ownValue != null) return ownValue

        // A user, BEFORE the `name` rule below, and the order is not cosmetic: a JIRA user object
        // carries *both*, and its `name` is the login. Checking `name` first projects `alovelace`
        // into a column that should read `Ada Lovelace` — a login shown where a person's name
        // belongs, which is the kind of leak R5 exists to stop. Spec §7.4 lists `name` above
        // `displayName`; taken literally with first-match-wins, every user column would be logins.
        value.string("displayName")?.let { return it }

        // status, priority, issuetype, resolution, project, component, version — all `name`.
        value.string("name")?.let { return it }

        // progress: "0/0" is a real and common value, so an all-zero pair is projected, not skipped.
        val progress = value.number("progress")
        val total = value.number("total")
        if (progress != null && total != null) return "$progress/$total"

        // votes / watches — the count is the whole point of the object.
        value.number("votes")?.let { return it }
        value.number("watchCount")?.let { return it }

        return null
    }

    private fun projectArray(value: JsonArray): Any? {
        if (value.isEmpty()) return null

        // A checklist array is counted, not listed: 1 673 of them in the committed export, up to
        // nine properties per item, and what a reviewer wants in a column is "3/7". The item names
        // are not lost — they stay in the raw JSON on the issue node (§7.2).
        //
        // Checked before the element-wise branch below, because a checklist item also carries
        // `name` and would otherwise render as a list of every item's text.
        if (value.all { it.asObjectOrNull()?.containsKey(CHECKED) == true }) {
            val checked = value.count { it.jsonObject[CHECKED]?.jsonPrimitive?.booleanOrNull == true }
            return "$checked/${value.size}"
        }

        val projected = value.map { element ->
            when (element) {
                is JsonObject -> projectObject(element)
                // An array of plain strings needs no projection — it is already storable and
                // already displayable on the issue node — but projecting it costs nothing and
                // keeps `coalesce(i[k], p[k])` true for every array.
                is JsonPrimitive -> element.content.takeIf { element.isString }
                else -> null
            }
        }

        // All or nothing. A list with holes in it invites a reader to think the blanks are empty
        // values rather than shapes this did not understand.
        return if (projected.any { it == null }) null else projected.filterNotNull()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** A number rendered as text — never a float when JIRA sent an integer. */
    private fun JsonObject.number(key: String): String? {
        val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        return primitive.content.takeIf { it.toDoubleOrNull() != null }
    }

    private const val CHECKED = "checked"
}
