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
