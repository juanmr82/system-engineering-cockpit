package com.sec.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a settings request asks to happen to a module's system-level classification.
 *
 * Three intents, and the distinction between the first two is the whole reason this type exists:
 * a dialog that does not show system level must be able to save without touching it, while the
 * dialog that does show it must be able to clear it. On the wire those are "field absent" and
 * "field explicitly null" — indistinguishable once decoded into a `String?`.
 */
public sealed interface SystemLevelChange {
    /** The request said nothing about system level. Leave whatever is stored alone. */
    public data object Unchanged : SystemLevelChange

    /** The request sent an explicit null — the user picked Empty. Remove the classification. */
    public data object Clear : SystemLevelChange

    public data class Set(public val code: String) : SystemLevelChange

    public companion object {
        /**
         * Reads the intent out of the raw JSON value.
         *
         * Returns null for a value that is neither absent, null, nor a string — a number or an
         * object here is a malformed request, not a silently ignored one.
         */
        public fun from(raw: JsonElement?): SystemLevelChange? =
            when {
                raw == null -> Unchanged
                raw is JsonNull -> Clear
                raw is JsonPrimitive && raw.isString -> Set(raw.content)
                else -> null
            }
    }
}
