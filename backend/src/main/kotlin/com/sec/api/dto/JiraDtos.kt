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
 * A JIRA project as the settings page's diagnostic shows it (ADR 0018).
 *
 * Fetched live from JIRA on every read and **never stored** — there is no configured key list any
 * more to keep it beside. This is simply what the token can see right now.
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
     * How many issues this one is linked to, in either direction, sub-tasks included.
     *
     * A count rather than a boolean because the number is worth showing on the control, and because
     * "has links" is a question the client can answer from it while the reverse is not.
     */
    public val linkCount: Int = 0,
    /**
     * The configured columns' values, keyed by field id — **only** the configured ones.
     *
     * `Map<String, JsonElement>` for the same reason a DOORS attribute bag is: the set differs per
     * deployment and per user, so a typed DTO per column set is not a thing that can exist. A list
     * stays a list, because the table renders those as chips.
     */
    public val values: Map<String, JsonElement> = emptyMap(),
)

/**
 * The related-issues graph of one issue: the links it has, and the links those have.
 *
 * The same shape as the DOORS dependency graph's response and deliberately not the same type: the
 * two share a *picture*, not a payload. A requirement card carries a module, an outline level and a
 * system level; a JIRA node carries a type, a status and a summary. Merging them would produce a
 * DTO where half the fields are null for either source.
 */
@Serializable
public data class JiraLinkGraphDto(
    public val seedRef: String,
    public val depth: Int,
    public val nodes: List<JiraGraphNodeDto>,
    public val edges: List<JiraGraphEdgeDto>,
    /** True when the cap or the depth cut the picture short, so the view can say so. */
    public val truncated: Boolean = false,
)

/**
 * One issue in that picture: the four things §13.2 asks a node to show, plus where it sits.
 *
 * [statusName] and [typeName] come from the promoted `:JiraStatus` and `:JiraIssueType` nodes rather
 * than from the issue's stored JSON — that promotion is what makes them words instead of blobs.
 * Both are nullable, because an issue can point at neither, and a **placeholder points at both of
 * nothing**: it is drawn anyway, with `unresolved` true, because a link to an issue outside the
 * configured projects is a fact about the issue that was asked for.
 */
@Serializable
public data class JiraGraphNodeDto(
    public val ref: String,
    public val key: String,
    public val typeName: String? = null,
    public val statusName: String? = null,
    public val summary: String? = null,
    public val unresolved: Boolean = false,
    /** The issue the graph was opened for. Exactly one node carries it. */
    public val seed: Boolean = false,
    /** Links this node has that the picture does not contain — cut by the cap or the depth. */
    public val truncatedNeighbours: Int = 0,
)

/**
 * One link, in the direction JIRA asserts it.
 *
 * [typeName] is JIRA's own name for the link type — *Relates*, *Blocks*, *Duplicates* — and it is
 * shown, because unlike DOORS's `refersTo` this source really does say what the relationship is
 * (§9.4). A sub-task edge carries no type name and says so with [subTask] instead: the relationship
 * is the label.
 */
@Serializable
public data class JiraGraphEdgeDto(
    public val source: String,
    public val target: String,
    public val typeName: String? = null,
    public val subTask: Boolean = false,
)
