package com.sec.source.jira

import com.sec.config.JiraAuthScheme
import com.sec.config.JiraPlatform
import com.sec.config.JiraSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * The one HTTP client in this backend, pointed at JIRA.
 *
 * ## Pagination is written for the cursor, and the offset is adapted to it
 *
 * Atlassian is removing the classic offset-paginated `/search` on Cloud in favour of
 * `/search/jql`, which returns an opaque `nextPageToken` and **no total at all** (design doc §1).
 * Writing the loop against a known total would therefore have to be unwritten later, so the loop
 * is a cursor loop and Data Center's `startAt` is fed into it as a cursor that happens to be a
 * number. Neither branch ever asks how many issues there are in total, because on one of the two
 * platforms nothing can answer.
 *
 * Which endpoint to call is [JiraSettings.platform], set once by an admin, rather than a probe of
 * `/search/jql` falling back on a 404. A fallback would put an extra round trip and a guess in
 * front of every import, and would read a genuine 404 from a misconfigured reverse proxy as
 * "this is Data Center".
 */
public class JiraHttpClient(
    private val settings: JiraSettings,
    engine: HttpClientEngine? = null,
) : JiraApi, AutoCloseable {

    private val client: HttpClient = build(engine)

    private fun build(engine: HttpClientEngine?): HttpClient {
        val configure: HttpClientConfig<*>.() -> Unit = {
            // ignoreUnknownKeys is not laxity here: JIRA adds response fields between versions and
            // an import that died on a new one would be broken by an upgrade nobody told us about.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }

            install(HttpRequestRetry) {
                maxRetries = settings.maxRetries
                // 429 is the one that matters. JIRA rate-limits aggressively, and the answer to a
                // rate limit is to wait exactly as long as it asked for — see delayMillis below.
                retryIf { _, response ->
                    response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500
                }
                retryOnExceptionIf { _, cause -> cause is IOException }
                // respectRetryAfterHeader is what turns the server's own instruction into the
                // delay. Without it the backoff below is used even when JIRA has said, in a
                // header, precisely how long to wait — which is how a client gets itself banned.
                delayMillis(respectRetryAfterHeader = true) { retry ->
                    BASE_RETRY_DELAY_MS shl (retry - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
                }
            }

            defaultRequest {
                header(HttpHeaders.Authorization, authorizationHeader())
                header(HttpHeaders.Accept, "application/json")
            }
        }

        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp) {
            configure()
            engine {
                config {
                    connectTimeout(settings.connectTimeout.seconds, TimeUnit.SECONDS)
                    readTimeout(settings.readTimeout.seconds, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    /**
     * Data Center issues a personal access token used as a bearer; Cloud pairs an API token with
     * the account email as basic auth. Both are "the token", and getting the pair the wrong way
     * round produces a 401 that says nothing about which half is wrong.
     */
    private fun authorizationHeader(): String = when (settings.authScheme) {
        JiraAuthScheme.BEARER -> "Bearer ${settings.token}"
        JiraAuthScheme.BASIC -> {
            val credential = "${settings.email}:${settings.token}"
            "Basic " + Base64.getEncoder().encodeToString(credential.toByteArray(Charsets.UTF_8))
        }
    }

    // Host and the API base are concatenated here and nowhere else, so no caller can pass a full
    // path and reach an endpoint this class does not know about (design doc §2, point 4).
    private fun url(segment: String): String = settings.host + JiraApiConstants.API_BASE + segment

    override suspend fun fieldCatalog(): List<JiraFieldDef> = getJson(JiraApiConstants.FIELD)

    override suspend fun issueTypes(): List<JiraIssueTypeDef> = getJson(JiraApiConstants.ISSUE_TYPE)

    override suspend fun projects(): List<JiraProjectDef> = getJson(JiraApiConstants.PROJECT)

    override suspend fun project(key: String): JiraProjectDef? {
        val response = client.get(url("${JiraApiConstants.PROJECT}/$key"))
        // A project key that does not exist is a legitimate answer to "is this a project?", asked
        // by the settings dialog on every key a user types. It is not a failure to report.
        if (response.status == HttpStatusCode.NotFound) return null
        ensureSuccess(response)
        return response.body()
    }

    override suspend fun searchIssues(
        jql: String,
        maxIssues: Int,
        onPage: suspend (List<JiraIssueDto>) -> Unit,
    ): Int {
        var cursor: String? = null
        var seen = 0

        while (true) {
            val page = fetchPage(jql, cursor, remaining = maxIssues - seen)
            if (page.issues.isNotEmpty()) {
                onPage(page.issues)
                seen += page.issues.size
            }

            if (seen >= maxIssues) {
                logger.warn {
                    "JIRA search stopped at the $maxIssues-issue ceiling for jql=[$jql]; " +
                        "raise jira.maxIssues or narrow the query"
                }
                return seen
            }

            cursor = nextCursor(page, seen) ?: return seen
        }
    }

    private suspend fun fetchPage(jql: String, cursor: String?, remaining: Int): JiraSearchResponse {
        val pageSize = minOf(settings.pageSize, remaining).coerceAtLeast(1)
        val segment = when (settings.platform) {
            JiraPlatform.CLOUD -> JiraApiConstants.SEARCH_JQL
            JiraPlatform.DATACENTER -> JiraApiConstants.SEARCH_CLASSIC
        }

        val response = client.get(url(segment)) {
            parameter("jql", jql)
            parameter("maxResults", pageSize)
            parameter("fields", JiraApiConstants.ALL_FIELDS)
            when (settings.platform) {
                JiraPlatform.CLOUD -> cursor?.let { parameter("nextPageToken", it) }
                JiraPlatform.DATACENTER -> parameter("startAt", cursor?.toIntOrNull() ?: 0)
            }
        }
        ensureSuccess(response)
        return response.body()
    }

    /**
     * The cursor for the next page, or null when there is no next page.
     *
     * The two platforms say "that was the last one" in three different ways and one of them says
     * it by omission, so every branch is spelled out rather than inferred from an empty page —
     * a page can legitimately come back empty when a permission filter removed all of its issues
     * while more pages remain.
     */
    private fun nextCursor(page: JiraSearchResponse, seen: Int): String? = when (settings.platform) {
        JiraPlatform.CLOUD -> when {
            page.isLast == true -> null
            page.nextPageToken.isNullOrBlank() -> null
            else -> page.nextPageToken
        }
        JiraPlatform.DATACENTER -> {
            val total = page.total
            when {
                page.issues.isEmpty() -> null
                total != null && seen >= total -> null
                else -> seen.toString()
            }
        }
    }

    private suspend inline fun <reified T> getJson(segment: String): T {
        val response = client.get(url(segment))
        ensureSuccess(response)
        return response.body()
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) return

        // JIRA's error bodies contain the JQL and sometimes field values, so they are logged and
        // never echoed — the same rule the rest of this backend applies to exception messages.
        val body = runCatching { response.bodyAsText().take(MAX_LOGGED_ERROR_CHARS) }.getOrElse { "" }
        logger.warn { "JIRA answered ${response.status.value} for ${response.request.url.encodedPath}: $body" }

        throw when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> JiraException(
                JiraFailure.Unauthorised(response.status.value),
                "JIRA rejected the credentials (${response.status.value})",
            )
            else -> JiraException(
                JiraFailure.Rejected(response.status.value, response.status.description),
                "JIRA answered ${response.status.value}",
            )
        }
    }

    override fun close(): Unit = client.close()

    private companion object {
        const val BASE_RETRY_DELAY_MS: Long = 500

        /** Caps the doubling at ~8s so a five-retry policy cannot wait minutes. */
        const val MAX_BACKOFF_SHIFT: Int = 4

        const val MAX_LOGGED_ERROR_CHARS: Int = 500
    }
}
