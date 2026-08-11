package com.sec.source.jira

/**
 * The JIRA REST surface this backend uses, and the only place any of it is spelled out.
 *
 * **[BASE] is a compile-time constant and never configuration** (spec §1, R6). Configuration holds
 * the host, which is a deployment fact; the API version is a fact about the code that parses the
 * responses, and letting an operator change it would let them point a v2 parser at a v3 payload.
 *
 * `/rest/api/2/` is shared by Data Center and Cloud, but the two have diverged on search: Cloud is
 * removing the offset-paginated `/search` in favour of a cursor-paginated `/search/jql` with no
 * `total`. **This client targets Data Center**, which keeps classic `/search` (spec §3.3). If a
 * Cloud instance ever has to be supported, that is a second client behind the same interface, not
 * a flag threaded through this one.
 *
 * Everything here is a path fragment beginning with `/`, so
 * [com.sec.config.JiraSettings.url] is a plain concatenation onto the normalised host.
 */
public object JiraApi {

    /** Never configurable. See the class note. */
    public const val BASE: String = "/rest/api/2/"

    /** Who the token belongs to, and — the reason it runs first — the server's own time zone. */
    public const val MYSELF: String = "${BASE}myself"

    /** Projects the token's user may browse. Not an admin endpoint; it returns only what they see. */
    public const val PROJECT: String = "${BASE}project"

    /** Every issue type on the instance. Tens of rows, one request, no paging. */
    public const val ISSUE_TYPE: String = "${BASE}issuetype"

    /**
     * Every system and custom field definition — 1 171 on the reference instance.
     *
     * This is the *only* source of field metadata. A search response carries `names` and `schema`
     * in principle, and the reference instance returns `null` for both even when asked (spec §3.4),
     * which is precisely why the field import runs before the issue import rather than after it.
     */
    public const val FIELD: String = "${BASE}field"

    /** Offset-paginated issue search. Data Center semantics; see the class note. */
    public const val SEARCH: String = "${BASE}search"

    /**
     * Where a human reads an issue.
     *
     * An issue's `self` is an API URL and shows raw JSON in a browser, so it is identity and never
     * a link (spec §13.2). This is derived at read time from the configured host and the key, and
     * is never stored — a stored copy would be a second place the host has to be right.
     */
    public fun browsePath(issueKey: String): String = "/browse/$issueKey"

    // Deliberately never called, listed so the absence reads as a decision rather than an
    // oversight: anything under `.../admin`, the whole `/rest/auth/` family, `/field/search`,
    // `/issuetype/{id}/alternatives`, and `/serverInfo` for licensing. No JIRA endpoint requiring
    // global admin rights may be used (spec §1, R7) — a read-only integration that demands an
    // admin token is one no administrator will grant.
}
