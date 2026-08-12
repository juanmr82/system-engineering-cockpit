package com.sec.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What `GET /api/v1/jira/health` reports, and what the settings page's *Test connection* button
 * renders.
 *
 * Two booleans rather than one status word, because the two failures they separate need different
 * actions from different people: [configured] is false when this deployment has no host or token,
 * which an operator fixes in a file; [reachable] is false when it has both and JIRA still did not
 * answer, which is a network or a token problem. Collapsing them into "not working" is how a
 * support call starts.
 *
 * **The token is not here, in any form** — not masked, not a prefix, not its length. [configured]
 * is the only thing about it that crosses the wire.
 */
@Serializable
public data class JiraHealthDto(
    /** A non-blank host and a non-blank token are present in configuration. */
    public val configured: Boolean,
    /** JIRA answered `/myself`. Always false when [configured] is false — nothing was called. */
    public val reachable: Boolean,
    /** The display name JIRA resolved the token to, when it answered. Never the account id. */
    public val user: String? = null,
    /** One sentence, written for a person, safe to render as-is. */
    public val message: String,
    /** The configured host, so the page can show *which* JIRA. Never a secret; blank when unset. */
    public val host: String,
)

/**
 * The configured project keys, and the query they produce.
 *
 * [jql] is a **preview** with the snapshot bound left as a placeholder, not the query any run
 * executed — inventing a timestamp here would read as a promise about *when*. Spec §13.5 calls the
 * preview the best debugging aid in the feature, because when an import returns something
 * unexpected the first question is always what was actually asked for.
 *
 * Null when no projects are configured: there is no query to preview, and an empty string would
 * render as one.
 */
@Serializable
public data class JiraProjectSettingsDto(
    public val projectKeys: List<String>,
    public val jql: String? = null,
)

/**
 * A replacement project list.
 *
 * Whole-list replacement rather than add/remove, because the order is part of the value and a merge
 * would need a rule for where a new key lands. **This is the injection boundary** (spec §8): the
 * keys are user-editable text on their way into a query language, so they are validated against a
 * closed pattern and rejected — never escaped, never quoted into safety.
 */
@Serializable
public data class JiraProjectSettingsRequest(public val projectKeys: List<String> = emptyList())

/**
 * One page of the Issues table (spec §14.3).
 *
 * [columns] travels with the rows rather than being fetched separately, and that is what keeps a
 * table from rendering last request's headers over this request's cells: the two are one answer to
 * one question. It is empty until the column picker exists — the three fixed columns are the
 * client's own and are never described here, because they are not configurable and a server that
 * announced them would be inviting a client to hide one.
 */
@Serializable
public data class JiraIssuesPageDto(
    public val page: Int,
    public val size: Int,
    /** Issues matching the same filter, for the paginator. Not the number of [rows]. */
    public val total: Int,
    public val columns: List<JiraColumnDto>,
    public val rows: List<JiraIssueRowDto>,
)

/**
 * One configurable column.
 *
 * [name] is the field's display name from the catalogue; it falls back to the field id, which is
 * also what a **stale** column shows — one the user chose and JIRA has since removed (spec §13.4).
 * Nothing here is `__`-prefixed: a JIRA field id is source data and a legitimate thing to show (R5).
 */
@Serializable
public data class JiraColumnDto(
    public val fieldId: String,
    public val name: String,
    /** JIRA's declared type, for the picker's chip and for the client's rendering choice. */
    public val schemaType: String? = null,
    public val sortable: Boolean = true,
    /** True when the field is no longer in the catalogue. The column still renders, empty (§13.4). */
    public val stale: Boolean = false,
)

/**
 * One offerable field, for the "Select fields to display" dialog (spec §13.3).
 *
 * [ambiguousName] is not a property of the field — it is a property of the *catalogue*: fifteen
 * names cover thirty-three fields on the reference instance, and a dialog listing three rows called
 * "Classification" is asking a user to choose between things it has not distinguished. The server
 * computes it because only the server sees the whole list at once; the client appends the id.
 *
 * A field with no schema never appears here at all. `issuekey` duplicates the fixed Key column and
 * `thumbnail` is not a data field, and neither can be rendered as a column.
 */
@Serializable
public data class JiraFieldDto(
    public val fieldId: String,
    public val name: String,
    /** `true` for `customfield_*`. The dialog's System / Custom filter, and nothing more. */
    public val custom: Boolean = false,
    public val schemaType: String? = null,
    /** The element type of an `array` field — `string`, `option`, `user`. */
    public val schemaItems: String? = null,
    public val ambiguousName: Boolean = false,
)

/** What the picker saves: the optional columns, in the order they are to be shown. */
@Serializable
public data class JiraColumnsRequest(public val fieldIds: List<String> = emptyList())

/**
 * A JIRA project as the settings page offers it (spec §13.5).
 *
 * Fetched live from JIRA on every read and **never stored** — the configured *keys* are the
 * application's data, and the list of what exists is JIRA's. A project deleted there simply stops
 * appearing here, and a configured key that no longer matches one is shown as a stale chip rather
 * than quietly dropped.
 */
@Serializable
public data class JiraProjectDto(
    public val key: String,
    public val name: String,
)

/**
 * One row of the Issues table.
 *
 * ## Three things here are derived on read and never stored
 *
 * [ref] is the base64url handle over `__id`, so no internal id reaches a URL or a DOM attribute
 * (R5). [browseUrl] is the page a person opens — JIRA's stored `self` is an API URL that answers
 * with raw JSON, which spec §13.2 names as a trap in the requirement as written. [unresolved] is a
 * label test, not a property.
 *
 * [issueTypeName] is the type's own name and is nullable, because an issue can point at a type this
 * import has not seen. The icon it will eventually carry needs the icon proxy (§9.1) and is not
 * here yet: a `<img>` to JIRA's own `iconUrl` would send the browser to a host it cannot
 * authenticate against.
 */
@Serializable
public data class JiraIssueRowDto(
    public val ref: String,
    /** `SCRUM-7`. Source data, shown as-is, and the one column a reader identifies a row by. */
    public val key: String,
    /** `<key>: <summary>`, or the key alone when a permission hides the summary. */
    public val name: String,
    public val issueTypeName: String? = null,
    public val browseUrl: String? = null,
    /**
     * A stub for an issue outside the configured projects, standing in for a link target.
     *
     * A state channel, not display text: the client renders *Not yet imported* from it (R5, and the
     * same treatment `labels` gets on a DOORS row).
     */
    public val unresolved: Boolean = false,
    /**
     * The configured columns' values, keyed by field id — **only** the configured ones.
     *
     * `Map<String, JsonElement>` for the same reason a DOORS attribute bag is: the set differs per
     * deployment and per user, so a typed DTO per column set is not a thing that can exist. A list
     * stays a list, because the table renders those as chips.
     */
    public val values: Map<String, JsonElement> = emptyMap(),
)
