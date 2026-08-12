package com.sec.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration
import java.util.Base64

/**
 * How the `Authorization` header is built. **A configured choice, never a negotiation.**
 *
 * Spec §3.2 mandates a Bearer personal access token and forbids falling back to Basic on a 401.
 * That rule stands and is not what this enum weakens: the forbidden thing is *retrying* a rejected
 * credential a second way, which sends it twice and turns one clear failure into two unclear ones.
 * Choosing the scheme up front, from configuration, is the opposite — one credential, sent one way,
 * decided before the first request.
 *
 * The enum exists because the two products genuinely differ. Data Center takes a PAT as
 * `Bearer <token>`; Cloud rejects that with a **403** and takes an API token as
 * `Basic base64(email:token)`. Verified against both.
 */
public enum class JiraAuthScheme {
    /** JIRA Data Center / Server: a personal access token. The default, and the spec's target. */
    BEARER,

    /**
     * JIRA Cloud: `email:apiToken`, base64-encoded. Requires [JiraSettings.email] — an API token
     * with no account to pair it with is not a credential Cloud will accept.
     */
    BASIC,
}

/**
 * Which JIRA product the host runs, because the two have removed each other's issue search.
 *
 * Data Center keeps the offset-paginated `/search`. Cloud answers it **410 Gone** and offers
 * `/search/jql`, which pages by opaque cursor and reports no total at all. That is not a difference
 * a parameter can paper over — it is a different pagination protocol — so it selects one of two
 * search implementations behind a single contract (ADR 0014).
 *
 * ## Why this is not derived from [JiraAuthScheme]
 *
 * ADR 0014 originally said the auth scheme would choose the search path, on the reasoning that
 * Cloud is the only thing wanting `basic`. That is wrong in the direction that matters: **Data
 * Center accepts Basic auth too**, so `auth: basic` on a Data Center host would silently select
 * Cloud's search and fail on every import with a 404 that names nothing. Two independent facts get
 * two settings; the ADR is corrected rather than followed.
 *
 * The two *do* covary in practice, so a mismatch is the likeliest misconfiguration here — which is
 * why preflight looks at what `/myself` returned and says so, instead of leaving it to a 410 in the
 * middle of the longest phase.
 */
public enum class JiraDeployment {
    /** Server / Data Center: `/search`, `startAt` + `total`. The default and the spec's target. */
    DATA_CENTER,

    /** Cloud: `/search/jql`, `nextPageToken` + `isLast`, no total. */
    CLOUD,
}

/**
 * Everything the backend needs to talk to one JIRA instance.
 *
 * JIRA is the only source whose importer runs inside this process (ADR 0013), so unlike DOORS —
 * whose connection details belong to a Python program on a Windows workstation — these settings
 * are the service's own.
 *
 * **The token is write-only from the application's point of view.** It is read once at startup,
 * and from there it reaches exactly one place: the `Authorization` header the client sets by
 * default. It is never logged (the client redacts that header), never returned by an endpoint,
 * and never written to the graph. [toString] is overridden for the same reason — a data class
 * prints every property, and a config object ends up in a log line eventually.
 */
public data class JiraSettings(
    /**
     * Scheme, host and — legitimately — a context path: the reference instance is served under
     * `https://jira.company.com/jira`, so this is not a bare origin and code that assumes it is
     * will build URLs that 404. Always normalised through [normaliseHost], never used raw.
     */
    public val host: String,
    public val token: String,
    /**
     * Which product this host is, expressed as how it wants the credential.
     *
     * Defaulted to [JiraAuthScheme.BEARER] because Data Center is what the spec targets and what
     * the reference instance runs. A deployment pointing at Cloud sets `jira.auth: basic` and an
     * email; nothing auto-detects, because auto-detection here means sending a credential to find
     * out whether it was the right kind.
     */
    public val authScheme: JiraAuthScheme = JiraAuthScheme.BEARER,
    /** The Atlassian account the API token belongs to. Used by [JiraAuthScheme.BASIC] only. */
    public val email: String = "",
    /**
     * Which product this host runs — it decides how issues are paged, and nothing else.
     *
     * Defaulted to [JiraDeployment.DATA_CENTER] for the same reason the auth scheme defaults to
     * Bearer: it is the spec's target and the reference instance. Deliberately independent of
     * [authScheme]; see [JiraDeployment].
     */
    public val deployment: JiraDeployment = JiraDeployment.DATA_CENTER,
    /**
     * What `/search` is *asked* for. What it *gives* is the stride, and the two differ: the server
     * silently clamps this to `jira.search.views.default.max`. Re-read `maxResults` from every
     * response (spec §3.3) — assuming the requested value was honoured is how pages get skipped.
     */
    public val pageSize: Int = DEFAULT_PAGE_SIZE,
    public val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    public val socketTimeout: Duration = DEFAULT_SOCKET_TIMEOUT,
    public val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    public val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    /**
     * Whether the integration can be used at all. Blank host or blank token means "not configured
     * on this deployment", which is a normal state and not an error: the cockpit has four other
     * sources and must start without JIRA. The routes answer 503 and the UI says why.
     */
    public val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank() &&
            (authScheme != JiraAuthScheme.BASIC || email.isNotBlank())

    /** Full URL for a path that already carries [com.sec.source.jira.JiraApi.BASE]. */
    public fun url(path: String): String = host + path

    /**
     * The `Authorization` header value — **the one place the token is turned into wire content.**
     *
     * Deliberately here rather than in the client: the client's job is transport, and a credential
     * assembled at the point of sending is a credential assembled in several places once a second
     * endpoint appears.
     */
    public fun authorizationHeader(): String = when (authScheme) {
        JiraAuthScheme.BEARER -> "Bearer $token"
        // ISO-8859-1, matching how HTTP Basic is specified; an email address is ASCII in practice
        // and the encoder must not depend on the platform default charset (CLAUDE.md §3).
        JiraAuthScheme.BASIC ->
            "Basic " + Base64.getEncoder().encodeToString("$email:$token".toByteArray(Charsets.ISO_8859_1))
    }

    // A data class prints every property, and a config object reaches a log line eventually.
    override fun toString(): String =
        "JiraSettings(host=$host, deployment=$deployment, auth=$authScheme, email=$email, " +
            "token=${if (token.isBlank()) "<unset>" else "<redacted>"}, " +
            "pageSize=$pageSize, maxRetries=$maxRetries)"

    public companion object {
        public const val DEFAULT_PAGE_SIZE: Int = 100
        public const val DEFAULT_MAX_RETRIES: Int = 5

        // A *all page of 100 issues is multi-megabyte - the 50-issue sample export is 3.4 MB - and
        // a loaded instance is slow rather than broken. These are deliberately far longer than the
        // graph timeouts next door (spec §3.5).
        public val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)
        public val DEFAULT_SOCKET_TIMEOUT: Duration = Duration.ofSeconds(60)
        public val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)

        /**
         * Makes `https://jira.company.com/jira` and `https://jira.company.com/jira/` the same
         * thing, so that concatenating a path that starts with `/` cannot produce a double slash.
         *
         * Trailing slashes are stripped repeatedly, not once: a hand-edited deployment file
         * ending `/jira//` is a typo, not a different instance.
         */
        public fun normaliseHost(raw: String): String = raw.trim().trimEnd('/')
    }
}

/**
 * Reads the `jira` block, or returns a blank-host settings object when there is none.
 *
 * Deliberately total. Neo4j's settings use `config.property(...)`, which throws when the key is
 * missing, because a cockpit without a graph is not a cockpit. JIRA is the opposite case: a
 * deployment that has not configured it should start, serve everything else, and say so at the
 * one endpoint that cares.
 */
public fun loadJiraSettings(config: ApplicationConfig): JiraSettings {
    val jira = config.configOrNull("jira") ?: return JiraSettings(host = "", token = "")

    return JiraSettings(
        host = JiraSettings.normaliseHost(jira.stringOrEmpty("host")),
        token = jira.stringOrEmpty("token"),
        // An unrecognised value falls back to BEARER rather than failing startup: this is the
        // spec's scheme and the one every Data Center deployment wants, so a typo costs a 401 with
        // a clear message rather than a service that will not boot.
        authScheme = when (jira.stringOrEmpty("auth").trim().lowercase()) {
            "basic" -> JiraAuthScheme.BASIC
            else -> JiraAuthScheme.BEARER
        },
        email = jira.stringOrEmpty("email").trim(),
        // Same forgiving parse as `auth`, and the same reason: a typo should cost a legible failure
        // on the first search, not a service that refuses to boot. `server` is accepted beside
        // `datacenter` because Atlassian renamed the product and deployment files outlive renames.
        deployment = when (jira.stringOrEmpty("deployment").trim().lowercase()) {
            "cloud" -> JiraDeployment.CLOUD
            else -> JiraDeployment.DATA_CENTER
        },
        pageSize = jira.intOr("pageSize", JiraSettings.DEFAULT_PAGE_SIZE),
        requestTimeout = jira.millisOr("requestTimeoutMs", JiraSettings.DEFAULT_REQUEST_TIMEOUT),
        socketTimeout = jira.millisOr("socketTimeoutMs", JiraSettings.DEFAULT_SOCKET_TIMEOUT),
        connectTimeout = jira.millisOr("connectTimeoutMs", JiraSettings.DEFAULT_CONNECT_TIMEOUT),
        maxRetries = jira.intOr("maxRetries", JiraSettings.DEFAULT_MAX_RETRIES),
    )
}

// `config("jira")` throws when the block is absent, and there is no configOrNull on the interface.
private fun ApplicationConfig.configOrNull(path: String): ApplicationConfig? =
    runCatching { config(path) }.getOrNull()?.takeIf { it.keys().isNotEmpty() }

// An unset `"$SEC_JIRA_TOKEN:"` resolves to the empty string rather than failing the way the Neo4j
// credentials do, which is what makes "not configured" a startup state instead of a crash.
private fun ApplicationConfig.stringOrEmpty(path: String): String =
    propertyOrNull(path)?.getString().orEmpty()

private fun ApplicationConfig.intOr(path: String, fallback: Int): Int =
    propertyOrNull(path)?.getString()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

private fun ApplicationConfig.millisOr(path: String, fallback: Duration): Duration =
    propertyOrNull(path)?.getString()?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofMillis)
        ?: fallback
