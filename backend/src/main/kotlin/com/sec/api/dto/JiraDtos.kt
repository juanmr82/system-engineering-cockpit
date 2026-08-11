package com.sec.api.dto

import kotlinx.serialization.Serializable

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
