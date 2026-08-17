package com.sec.source.jira

/**
 * Why a call to JIRA did not produce data.
 *
 * A sealed hierarchy rather than one exception with a status code, because the caller's *response*
 * differs per case and a status code invites `if (code == 401 || code == 403)` at every call site:
 * [Unauthorized] and [Forbidden] end a run immediately, [Unreachable] is worth retrying, and
 * [BadRequest] carries a message from JIRA that is better than anything this code could write
 * (spec §3.5).
 *
 * These are expected failures — an expired token is normal operations, not a defect — so they
 * travel in a `Result`, not by being thrown past the importer (CLAUDE.md §11). They extend
 * `Exception` only because that is what `Result.failure` accepts.
 */
public sealed class JiraFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** No host or no token on this deployment. Never reaches JIRA; nothing to retry. */
    public class NotConfigured : JiraFailure(
        "JIRA is not configured: set jira.host and jira.token (or SEC_JIRA_HOST / SEC_JIRA_TOKEN).",
    )

    /**
     * 401. The token is wrong, expired, or revoked.
     *
     * **Never retried, and never retried as Basic auth** (spec §3.2). A fallback to Basic on 401
     * turns one clear failure into two unclear ones and sends the credential a second way.
     */
    public class Unauthorized(detail: String) : JiraFailure("JIRA rejected the token: $detail")

    /** 403. The token is valid; its user may not see what was asked for. Names what, when known. */
    public class Forbidden(detail: String) : JiraFailure("JIRA refused access: $detail")

    /**
     * 400 with `errorMessages` — in practice a project key that no longer exists.
     *
     * [jiraMessages] is surfaced to the user verbatim: JIRA knows which clause of the JQL it did
     * not like and we do not, so paraphrasing it loses the only useful part.
     */
    public class BadRequest(public val jiraMessages: List<String>) : JiraFailure(
        jiraMessages.firstOrNull() ?: "JIRA rejected the request.",
    )

    /** Connection refused, reset, timed out, or 5xx after every retry. Worth trying again later. */
    public class Unreachable(detail: String, cause: Throwable? = null) :
        JiraFailure("JIRA is not answering: $detail", cause)

    /** A 2xx whose body is not what the endpoint is documented to return. */
    public class MalformedResponse(detail: String, cause: Throwable? = null) :
        JiraFailure("JIRA returned an unreadable response: $detail", cause)

    /**
     * A chosen column names something that is not a JIRA field id.
     *
     * The second injection boundary of this feature, and the sharper one: a field id becomes a
     * Cypher **property key** through dynamic access, where a project key only becomes JQL text.
     * The bad ones are named, because the picker has to say which entry to drop.
     */
    public class InvalidFieldId(public val fieldIds: List<String>) : JiraFailure(
        "Not valid JIRA field ids: ${fieldIds.joinToString(", ")}",
    )

    /**
     * The paging loop ran further than any real result set could justify.
     *
     * A server that keeps answering with a full page and a `startAt` it ignores would otherwise
     * loop forever, holding a run open and growing the graph. Community Neo4j has no query
     * governor and this importer has no run timeout, so this bound is the only thing that ends it.
     */
    public class TooManyPages(public val pages: Int) : JiraFailure(
        "JIRA search did not terminate after $pages pages; stopping rather than looping.",
    )
}
