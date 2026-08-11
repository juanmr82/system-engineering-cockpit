package com.sec.source.jira.mapping

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Where one JIRA field value lands on a `:JiraIssue` node (spec §7.1, §7.2).
 *
 * The whole set, so a caller cannot forget the one that matters: [Skip] is not "no value", it is
 * "write nothing", and the two differ because a property that is absent and a property that is
 * present-and-empty are different facts about the issue.
 */
public sealed interface StoredValue {

    /**
     * Nothing is written, and if the node already carries this key, phase 3 removes it.
     *
     * Two cases reach here: `null` — roughly 86 % of every payload, 43 902 of 50 800 keys in the
     * committed export — and `[]`. Both mean the same thing for a property: absent. A node with
     * ~145 properties instead of ~1 040 is the single biggest storage and query win in this design,
     * and it is bought entirely here.
     */
    public data object Skip : StoredValue

    /** A Neo4j scalar: `String`, `Long`, `Double` or `Boolean`, verbatim. */
    public data class Scalar(public val value: Any) : StoredValue

    /**
     * A Neo4j list, **guaranteed homogeneous** — every element the same type.
     *
     * The guarantee is not cosmetic. Neo4j stores a list property as a typed array, so a list
     * mixing a string and a number cannot be stored at all; [classify] sends those to [JsonText]
     * rather than letting the driver fail mid-batch. Mixed integers and decimals are promoted to
     * `Double` for the same reason.
     */
    public data class ListOfScalars(public val values: List<Any>) : StoredValue

    /**
     * The raw JSON text of an object or an array of objects.
     *
     * Neo4j cannot store a nested map or a heterogeneous list as a property, so this is the only
     * way to honour R1 — the value is kept exactly as JIRA sent it, and parses back to the same
     * `JsonElement` when the API layer needs the whole issue. The *scalar* a table needs to sort on
     * is derived separately, onto a companion node, because a derived value may never sit on an
     * imported one (R2, [DisplayProjector]).
     */
    public data class JsonText(public val json: String) : StoredValue
}

/**
 * Decides a value's storage **from its observed shape, never from its declared type**.
 *
 * That is the central decision of §7 and it is worth stating why, because the declared type is
 * right there and looks authoritative. It is not sufficient:
 *
 *  - **The type vocabulary is open.** The committed export carries more than fifteen distinct
 *    `schema.type` values, including `securitylevel`, `comments-page` and `sd-approvals`, none of
 *    which the spec's own §5.1 table lists. A plugin adds more. A `when` over known types would
 *    drop a plugin's data silently — the worst possible failure, because the import still reports
 *    success.
 *  - **The declared type does not determine the shape.** An `any`-typed field holds a string in
 *    216 places in the export and an empty array in 48 others. Both are correct; only the value
 *    says which.
 *  - **A field may have no definition at all.** One added between the `/field` call and the
 *    `/search` call has no declared type to consult, and must still import (spec §16.1).
 *
 * So this function takes a [JsonElement] and nothing else. It cannot consult a catalogue because it
 * is not given one — which is the strongest available statement that it does not need one.
 */
public object ValueClassifier {

    public fun classify(value: JsonElement): StoredValue = when (value) {
        is JsonNull -> StoredValue.Skip
        is JsonPrimitive -> primitive(value)
        is JsonArray -> array(value)
        is JsonObject -> StoredValue.JsonText(value.toString())
    }

    /**
     * `""` is **stored**, and this is a deliberate departure from the letter of spec §7.1.
     *
     * That section says to skip empty strings *except* when the property already exists, in which
     * case it must be set to `""` rather than removed, "because an emptied field is information".
     * The exception cannot be implemented here — this function is pure and has never seen the node —
     * and it cannot be implemented downstream either: phase 3 removes every key that is not in
     * `presentKeys`, so a skipped `""` is a *removed* property, which is precisely the outcome the
     * exception forbids.
     *
     * Storing it satisfies the case the spec cares about and matches the rule the DOORS side has
     * lived by since the beginning — `""` means "exists and is empty", not "absent" (CLAUDE.md §11).
     * The cost is nil on real data: the 50-issue export contains 994 non-empty strings and **not one
     * empty one**.
     */
    private fun primitive(value: JsonPrimitive): StoredValue {
        if (value.isString) return StoredValue.Scalar(value.content)

        // Order matters: booleanOrNull first, because kotlinx will happily read "true" as a string
        // otherwise, and longOrNull before doubleOrNull so an integer stays an integer in the graph.
        value.booleanOrNull?.let { return StoredValue.Scalar(it) }
        value.longOrNull?.let { return StoredValue.Scalar(it) }
        value.doubleOrNull?.let { return StoredValue.Scalar(it) }

        // A primitive that is neither string, boolean nor number is not a shape this knows. Keeping
        // the text loses nothing and invents nothing.
        return StoredValue.Scalar(value.content)
    }

    private fun array(value: JsonArray): StoredValue {
        if (value.isEmpty()) return StoredValue.Skip

        // Anything nested makes the whole array JSON text: a list of maps is not a Neo4j property.
        if (value.any { it !is JsonPrimitive }) return StoredValue.JsonText(value.toString())

        val primitives = value.map { it as JsonPrimitive }

        // A JSON null inside an array cannot be represented in a Neo4j list either, and dropping it
        // would silently change the array's length — which for a positional list is a change of
        // meaning, not a tidy-up.
        if (primitives.any { it is JsonNull }) return StoredValue.JsonText(value.toString())

        if (primitives.all { it.isString }) {
            return StoredValue.ListOfScalars(primitives.map { it.content })
        }
        if (primitives.all { it.booleanOrNull != null }) {
            return StoredValue.ListOfScalars(primitives.mapNotNull { it.booleanOrNull })
        }
        if (primitives.all { it.longOrNull != null }) {
            return StoredValue.ListOfScalars(primitives.mapNotNull { it.longOrNull })
        }
        if (primitives.all { it.doubleOrNull != null }) {
            // Promoted, not mixed: `[1, 2.5]` is stored as `[1.0, 2.5]` because Neo4j's array
            // property is typed and cannot hold both.
            return StoredValue.ListOfScalars(primitives.mapNotNull { it.doubleOrNull })
        }

        // Mixed kinds — a string beside a number. Not storable as a list; kept whole as text.
        return StoredValue.JsonText(value.toString())
    }
}
