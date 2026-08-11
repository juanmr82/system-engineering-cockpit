package com.sec.source.jira

import com.sec.config.JiraSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.io.IOException

/** The importer's own log. */
private val logger = KotlinLogging.logger {}

/**
 * Ktor's client logging, kept apart from [logger] on purpose.
 *
 * It is wire chatter at a different granularity from the importer's own narration, and giving it
 * its own logger name is what lets an operator turn one up without drowning in the other.
 */
private val wireLogger = KotlinLogging.logger("com.sec.source.jira.wire")

private val SERVER_ERRORS = 500..599

/** `fields=*all`. A constant because it is a protocol token, not a tuning choice. */
private const val ALL_FIELDS = "*all"

/**
 * The backstop on the paging loop.
 *
 * Generous on purpose — 10 000 pages at the smallest stride JIRA realistically clamps to is far
 * more than any instance this will meet — because this is not a limit on legitimate data. It is
 * the difference between a misbehaving server producing an error and producing an infinite loop.
 */
private const val MAX_PAGES = 10_000

/** What a completed search reports back, beyond the pages themselves. */
public data class JiraSearchSummary(
    public val issuesSeen: Int,
    public val pages: Int,
    /**
     * `warningMessages` from any page. Logged and attached to the run report, never fatal: JIRA
     * uses them for things like a JQL clause that matched nothing, which is information rather
     * than failure (spec §3.5).
     */
    public val warnings: List<String>,
)

/**
 * The one way this backend talks to JIRA.
 *
 * Everything about the transport lives here — authentication, retry policy, timeouts, and the
 * mapping from an HTTP status to a [JiraFailure]. Callers above this line see a `Result` and never
 * a status code, which is what keeps `if (status == 401)` out of the importer's six phases.
 *
 * **The token reaches exactly two places**: the default `Authorization` header set below, and the
 * redaction list of the logging plugin. It is not in a `toString`, not in a log line, not in an
 * error message, and never in the graph.
 *
 * Construct one per application, not per request. The reason for the OkHttp engine here is its
 * connection pool, and a client per call throws that away.
 */
public class JiraHttpClient private constructor(
    private val settings: JiraSettings,
    private val client: HttpClient,
) : AutoCloseable {

    public constructor(settings: JiraSettings) :
        this(settings, HttpClient(OkHttp) { configureForJira(settings) })

    /**
     * For tests: the same configuration over a `MockEngine` serving the sample exports.
     *
     * The configuration is shared rather than rebuilt, so a test exercises the real retry policy,
     * the real parser and the real redaction. A test that configures its own client tests its own
     * client.
     */
    public constructor(settings: JiraSettings, engine: HttpClientEngine) :
        this(settings, HttpClient(engine) { configureForJira(settings) })

    /**
     * Who the token belongs to, and the server's time zone.
     *
     * The connectivity check and the first step of every import are the same call, deliberately: a
     * "Test connection" button that exercises a different path from the import is a button that
     * can go green while the import fails.
     */
    public suspend fun myself(): Result<JiraMyself> = fetch(JiraApi.MYSELF)

    /** Projects the token's user may browse — the settings picker's list. */
    public suspend fun projects(): Result<List<JiraProjectSummary>> = fetch(JiraApi.PROJECT)

    /** Every issue type on the instance. Tens of rows; no paging. */
    public suspend fun issueTypes(): Result<List<JiraIssueTypeDefinition>> = fetch(JiraApi.ISSUE_TYPE)

    /** The whole field catalogue — 1 171 definitions on the reference instance, in one response. */
    public suspend fun fieldDefinitions(): Result<List<JiraFieldDefinition>> = fetch(JiraApi.FIELD)

    /** One page of `/search`. Public for tests and for callers that want to drive paging themselves. */
    public suspend fun searchPage(jql: String, startAt: Int, maxResults: Int): Result<JiraSearchPage> =
        fetch(
            JiraApi.SEARCH,
            listOf(
                "jql" to jql,
                "startAt" to startAt.toString(),
                "maxResults" to maxResults.toString(),
                // Every field, because the column picker offers every field. The cost is real -
                // roughly 86% of each payload is nulls - and it is paid once here rather than as a
                // second request per issue when a user adds a column (spec §3.4).
                "fields" to ALL_FIELDS,
            ),
        )

    /**
     * Every page of a search, in order, handed to [onPage] as it arrives.
     *
     * A callback rather than a returned list: a full import is ~7 MB per page and holding all of
     * them would be the largest allocation in the process for no reason. The caller writes each
     * page and forgets it, which is also what lets it emit progress and stay cancellable.
     *
     * ## The stride is the response's, never the request's
     *
     * `maxResults` is **advisory**. The server silently clamps it to
     * `jira.search.views.default.max` — commonly 1 000, often much lower — and answers with the
     * value it actually used. Paging by the value we *asked* for then skips every issue between
     * the real page end and the assumed one, with no error anywhere: the run reports success and
     * the graph is missing three quarters of the issues. Spec §3.3 calls this the single most
     * common cause of skipped pages, and it is the reason [JiraSearchPage.maxResults] exists.
     *
     * ## Termination
     *
     * An empty `issues` array wins over `total`, because `total` is an estimate under concurrent
     * modification. [maxPages] is the backstop against a server that ignores `startAt` — without
     * it, one misbehaving instance is an infinite loop holding a run open.
     */
    public suspend fun searchAll(
        jql: String,
        maxPages: Int = MAX_PAGES,
        onPage: suspend (JiraSearchPage) -> Unit,
    ): Result<JiraSearchSummary> {
        var startAt = 0
        var pages = 0
        var issuesSeen = 0
        val warnings = mutableListOf<String>()

        while (true) {
            if (pages >= maxPages) return Result.failure(JiraFailure.TooManyPages(pages))

            val page = searchPage(jql, startAt, settings.pageSize)
                .getOrElse { return Result.failure(it) }

            // Trusted over `total`: an empty page is a fact, and `total` is an estimate.
            if (page.issues.isEmpty()) break

            onPage(page)

            pages++
            issuesSeen += page.issues.size
            page.warningMessages?.let { warnings += it }

            // The whole point of this loop. `takeIf { it > 0 }` is not defensive noise: a server
            // reporting maxResults: 0 would make the stride zero, and a zero stride re-reads page
            // one forever - the one failure here worse than skipping issues.
            val stride = page.maxResults.takeIf { it > 0 } ?: page.issues.size
            startAt += stride

            if (startAt >= page.total) break
        }

        return Result.success(JiraSearchSummary(issuesSeen = issuesSeen, pages = pages, warnings = warnings))
    }

    override fun close(): Unit = client.close()

    /**
     * One GET, one typed body, every failure turned into a [JiraFailure].
     *
     * The body is read as text and decoded explicitly rather than through content negotiation.
     * That is not a style preference: the failure this guards against is a host that is not JIRA —
     * an SSO portal answering `200 text/html` — and reading it as text makes that a
     * [JiraFailure.MalformedResponse] naming the endpoint, instead of a transformation exception
     * about a content type.
     */
    private suspend inline fun <reified T> fetch(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): Result<T> {
        if (!settings.isConfigured) return Result.failure(JiraFailure.NotConfigured())

        val url = settings.url(path)
        val response = try {
            client.get(url) { params.forEach { (name, value) -> parameter(name, value) } }
        } catch (cause: IOException) {
            // Connection refused, reset, DNS, or a timeout that outlived every retry.
            logger.warn(cause) { "JIRA request failed: $path" }
            return Result.failure(JiraFailure.Unreachable(path, cause))
        }

        failureFor(response, path)?.let { failure ->
            logger.warn { "JIRA answered ${response.status} for $path" }
            return Result.failure(failure)
        }

        return try {
            Result.success(jiraJson.decodeFromString<T>(response.bodyAsText()))
        } catch (cause: SerializationException) {
            logger.warn(cause) { "JIRA returned a body $path is not supposed to return" }
            Result.failure(JiraFailure.MalformedResponse(path, cause))
        }
    }

    /** The status-to-failure table of spec §3.5, in one place so every endpoint agrees. */
    private suspend fun failureFor(response: HttpResponse, path: String): JiraFailure? = when {
        response.status.isSuccess() -> null

        // Never retried, and never retried as Basic auth (spec §3.2). The body may name the
        // reason; it may equally be an HTML login page, so it is logged and not echoed.
        response.status == HttpStatusCode.Unauthorized ->
            JiraFailure.Unauthorized("the credential was not accepted at $path")

        response.status == HttpStatusCode.Forbidden ->
            JiraFailure.Forbidden("the token's user lacks permission for $path")

        // JIRA knows which clause of the JQL it disliked and we do not, so its own words go
        // through to the user unaltered.
        response.status == HttpStatusCode.BadRequest ->
            JiraFailure.BadRequest(jiraErrorMessages(response))

        // 429 and 5xx reach here only after HttpRequestRetry has already given up on them.
        else -> JiraFailure.Unreachable("$path answered ${response.status}")
    }

    private suspend fun jiraErrorMessages(response: HttpResponse): List<String> =
        runCatching {
            jiraJson.decodeFromString<JiraErrorResponse>(response.bodyAsText()).errorMessages
        }.getOrElse { emptyList() }
            .ifEmpty { listOf("JIRA rejected the request and gave no reason.") }
}

/**
 * The client configuration, shared by the production constructor and the test one.
 *
 * Top-level rather than a companion member because both constructors use it while delegating,
 * where there is no instance yet to reach a member through.
 */
private fun HttpClientConfig<*>.configureForJira(settings: JiraSettings) {
    // Do not throw on 4xx/5xx: `failureFor` reads the status, and for a 400 it reads the body.
    expectSuccess = false

    install(HttpTimeout) {
        requestTimeoutMillis = settings.requestTimeout.toMillis()
        socketTimeoutMillis = settings.socketTimeout.toMillis()
        connectTimeoutMillis = settings.connectTimeout.toMillis()
    }

    install(HttpRequestRetry) {
        maxRetries = settings.maxRetries

        // 401 and 403 are deliberately absent. A rejected token is not a transient condition, and
        // retrying it five times turns one clear failure into the same failure, later.
        retryIf { _, response ->
            response.status == HttpStatusCode.TooManyRequests ||
                response.status.value in SERVER_ERRORS
        }
        // A reset mid-page is the ordinary failure on a loaded instance. The page is the unit of
        // work, so retrying it is cheap: nothing outside it has been written yet.
        retryOnExceptionIf { _, cause -> cause is IOException }

        // respectRetryAfterHeader defaults to true, and that is what makes a 429 wait the time
        // JIRA asked for rather than the time our backoff curve happens to produce.
        exponentialDelay()
    }

    install(Logging) {
        // Ktor's default logger writes to stdout, outside the structured log and outside the
        // CallId MDC. Route it through the same Logback everything else uses.
        logger = object : Logger {
            override fun log(message: String) {
                wireLogger.debug { message }
            }
        }
        level = LogLevel.INFO
        // The one line keeping the token out of the log. Without it, INFO logging of a request
        // prints every header it sent, Authorization included.
        sanitizeHeader { header -> header == HttpHeaders.Authorization }
    }

    defaultRequest {
        // Built by the settings, not here: Data Center wants `Bearer <PAT>` and Cloud answers 403
        // to exactly that, wanting `Basic base64(email:apiToken)` instead. Which one is a
        // *configured* fact, and it is decided in one place (JiraSettings.authorizationHeader).
        header(HttpHeaders.Authorization, settings.authorizationHeader())
        header(HttpHeaders.Accept, "application/json")
    }
}
