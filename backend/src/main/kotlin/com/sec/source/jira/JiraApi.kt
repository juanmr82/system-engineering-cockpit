package com.sec.source.jira

/**
 * The JIRA REST surface this backend uses, and the only place any of it is spelled out.
 *
 * **[BASE] is a compile-time constant and never configuration** (spec §1, R6). Configuration holds
 * the host, which is a deployment fact; the API version is a fact about the code that parses the
 * responses, and letting an operator change it would let them point a v2 parser at a v3 payload.
 *
 * `/rest/api/2/` is shared by Data Center and Cloud, but the two have diverged on search: Cloud has
 * **removed** the offset-paginated `/search` — it answers `410 Gone` — in favour of a
 * cursor-paginated `/search/jql` with no `total` at all. Both are declared below and
 * [com.sec.config.JiraDeployment] picks one.
 *
 * That is a second *search implementation*, not a flag threaded through the transport: everything
 * about authentication, retry, timeouts and error mapping is identical on the two products, and
 * `/myself`, `/project`, `/issuetype` and `/field` are identical responses. Duplicating a whole
 * client to change a paging loop would mean two retry policies to keep in step (ADR 0014).
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

    /** Offset-paginated issue search. **Data Center only** — Cloud answers this `410 Gone`. */
    public const val SEARCH: String = "${BASE}search"

    /**
     * Cursor-paginated issue search. **Cloud only** — Data Center has no such path.
     *
     * Verified against a live Cloud instance: the response carries `issues`, `isLast`, and a
     * `nextPageToken` that is **absent on the final page**. There is no `total`, no `maxResults`
     * and no `startAt`, which is why progress for a Cloud run needs [APPROXIMATE_COUNT].
     *
     * It also **refuses unbounded JQL** with a 400. Harmless here — spec §8 has always required
     * project keys — but worth knowing before testing a query by hand.
     */
    public const val SEARCH_JQL: String = "${BASE}search/jql"

    /**
     * How many issues a JQL query matches, roughly. **Cloud only**, and a `POST`.
     *
     * Its whole purpose is the progress bar. Cloud's search pages report no total, so without this
     * the longest phase of the import could only ever count upwards with no denominator. "Roughly"
     * is fine for that and would not be fine for anything else: it is never used as a termination
     * condition, which stays `isLast`.
     */
    public const val APPROXIMATE_COUNT: String = "${BASE}search/approximate-count"

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
