package com.sec.source.jira

import com.sec.config.JiraSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The transport rules of `docs/JIRA_ISSUES_FEATURE_SPEC.md` §3.2 and §3.5, over a `MockEngine`.
 *
 * These are the rules that are invisible in normal operation and expensive when wrong: a token
 * sent the wrong way, a rejected credential retried five times, a 5xx that ends a run instead of
 * a page. Each one is asserted on the requests the engine actually received, not on the client's
 * configuration — configuration can be right and still not take effect.
 */
class JiraHttpClientTest {

    // -- authentication ---------------------------------------------------------------------

    /**
     * Bearer, and only Bearer. Basic auth is not merely unused; falling back to it on a 401 would
     * send the credential a second way to a server that has already refused it (spec §3.2).
     */
    @Test
    fun `the token travels as a bearer header`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = client(json = MYSELF_JSON, record = seen)

        runBlocking { client.myself() }

        assertEquals("Bearer test-token", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `the context path in the host is part of every request URL`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = client(host = "https://jira.example.com/jira", json = MYSELF_JSON, record = seen)

        runBlocking { client.myself() }

        assertEquals("https://jira.example.com/jira/rest/api/2/myself", seen.single().url.toString())
    }

    // -- the short circuit ------------------------------------------------------------------

    /**
     * An unconfigured deployment must not produce a request at all. Reaching the network to
     * discover there is no token would mean a DNS lookup of the empty string, and a failure that
     * reads as "JIRA is down" rather than "JIRA was never set up".
     */
    @Test
    fun `an unconfigured client makes no request`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = client(token = "", json = MYSELF_JSON, record = seen)

        val result = runBlocking { client.myself() }

        assertTrue(seen.isEmpty(), "an unconfigured client reached the network")
        assertIs<JiraFailure.NotConfigured>(result.exceptionOrNull())
    }

    // -- the status table -------------------------------------------------------------------

    /**
     * The highest-value assertion in this file. A retried 401 costs five round trips and the
     * user's patience to reach the same answer, and — with a lockout policy on the JIRA side —
     * can turn a wrong token into a locked account.
     */
    @Test
    fun `a rejected token fails immediately and is never retried`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = client(status = HttpStatusCode.Unauthorized, record = seen)

        val result = runBlocking { client.myself() }

        assertEquals(1, seen.size, "a 401 was retried; it must not be")
        assertIs<JiraFailure.Unauthorized>(result.exceptionOrNull())
    }

    @Test
    fun `a 403 fails immediately and is never retried`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = client(status = HttpStatusCode.Forbidden, record = seen)

        val result = runBlocking { client.myself() }

        assertEquals(1, seen.size, "a 403 was retried; it must not be")
        assertIs<JiraFailure.Forbidden>(result.exceptionOrNull())
    }

    /**
     * The inverse of the two above: a 5xx is transient and the page is the unit of work, so it is
     * retried rather than ending the run.
     *
     * `maxRetries = 1` deliberately — the retry policy uses `exponentialDelay`, so proving the
     * behaviour with the production value of 5 would mean a test that sleeps for about half a
     * minute. One retry proves the branch; the count is configuration.
     */
    @Test
    fun `a server error is retried`() {
        val seen = mutableListOf<HttpRequestData>()
        val client = client(status = HttpStatusCode.BadGateway, maxRetries = 1, record = seen)

        val result = runBlocking { client.myself() }

        assertEquals(2, seen.size, "a 502 was not retried")
        assertIs<JiraFailure.Unreachable>(result.exceptionOrNull())
    }

    /**
     * JIRA knows which clause of a JQL it disliked; we do not. Its own sentence is worth more than
     * any paraphrase, so it travels through unaltered (spec §3.5).
     */
    @Test
    fun `a 400 carries JIRA's own error message through`() {
        val body = """{"errorMessages":["The value 'GONE' does not exist for the field 'project'."],"errors":{}}"""
        val client = client(status = HttpStatusCode.BadRequest, json = body)

        val result = runBlocking { client.myself() }

        val failure = assertIs<JiraFailure.BadRequest>(result.exceptionOrNull())
        assertEquals(
            "The value 'GONE' does not exist for the field 'project'.",
            failure.jiraMessages.single(),
        )
    }

    @Test
    fun `a 400 with no message still produces a sentence`() {
        val client = client(status = HttpStatusCode.BadRequest, json = "{}")

        val failure = assertIs<JiraFailure.BadRequest>(runBlocking { client.myself() }.exceptionOrNull())

        assertTrue(failure.jiraMessages.single().isNotBlank())
    }

    /**
     * The misconfiguration this is really about: a host pointing at an SSO portal, which answers
     * `200 text/html` to everything. Content negotiation would report a transformation failure
     * about a content type; naming it a malformed response is what lets the settings page say
     * "check the host, including any context path".
     */
    @Test
    fun `a 200 that is not JSON is a malformed response, not a crash`() {
        val client = client(json = "<html><body>Please sign in</body></html>")

        val result = runBlocking { client.myself() }

        assertIs<JiraFailure.MalformedResponse>(result.exceptionOrNull())
    }

    // -- parsing ------------------------------------------------------------------------------

    /**
     * R8, at the smallest scale: a JIRA instance with a plugin returns keys we have never heard
     * of, on endpoints we do type. None of them are our business, and none may break a run.
     */
    @Test
    fun `unknown keys in a typed response are ignored`() {
        val client = client(
            json = """{"displayName":"Ada Lovelace","timeZone":"Europe/Berlin","somePluginField":{"a":1}}""",
        )

        val me = runBlocking { client.myself() }.getOrThrow()

        assertEquals("Ada Lovelace", me.displayName)
        assertEquals("Europe/Berlin", me.timeZone)
    }

    /** `/myself` is where the JQL bound's time zone comes from, so losing it is not cosmetic. */
    @Test
    fun `the server time zone survives the round trip`() {
        val client = client(json = MYSELF_JSON)

        assertEquals("Europe/Berlin", runBlocking { client.myself() }.getOrThrow().timeZone)
    }

    private fun client(
        host: String = "https://jira.example.com",
        token: String = "test-token",
        status: HttpStatusCode = HttpStatusCode.OK,
        json: String = "{}",
        maxRetries: Int = 0,
        record: MutableList<HttpRequestData> = mutableListOf(),
    ): JiraHttpClient {
        val engine = MockEngine { request ->
            record += request
            if (status.value >= 400) {
                // respondError carries no body, so the 400 cases pass their JSON explicitly.
                if (json == "{}") respondError(status)
                else respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val settings = JiraSettings(
            host = JiraSettings.normaliseHost(host),
            token = token,
            maxRetries = maxRetries,
        )
        return JiraHttpClient(settings, engine)
    }

    private companion object {
        const val MYSELF_JSON =
            """{"name":"alovelace","displayName":"Ada Lovelace","timeZone":"Europe/Berlin","active":true}"""
    }
}
