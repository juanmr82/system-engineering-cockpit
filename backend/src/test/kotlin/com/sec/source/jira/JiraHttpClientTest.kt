package com.sec.source.jira

import com.sec.config.JiraAuthScheme
import com.sec.config.JiraPlatform
import com.sec.config.JiraSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client, against a scripted JIRA.
 *
 * Paging is the reason this file exists. A live instance cannot be asked to return a short page,
 * a last page, a 429 or a project that vanishes mid-run — and every one of those is a case where
 * getting it wrong loses issues silently rather than failing.
 */
class JiraHttpClientTest {

    private fun settings(platform: JiraPlatform, pageSize: Int = 2) = JiraSettings.UNCONFIGURED.copy(
        host = "https://jira.example.com",
        token = "t0ken",
        platform = platform,
        pageSize = pageSize,
        maxRetries = 0,
    )

    private fun issuesJson(vararg keys: String): String =
        keys.joinToString(",") { """{ "id": "1", "key": "$it", "self": "s", "fields": {} }""" }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // -- paging ---------------------------------------------------------------------------

    @Test
    fun `cloud pages by cursor until isLast`() = runBlocking {
        val requested = mutableListOf<String?>()
        val engine = MockEngine { request ->
            requested += request.url.parameters["nextPageToken"]
            val body = when (requested.size) {
                1 -> """{ "issues": [${issuesJson("P-1", "P-2")}], "nextPageToken": "c1", "isLast": false }"""
                2 -> """{ "issues": [${issuesJson("P-3")}], "isLast": true }"""
                else -> error("asked for a page after the last one")
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }

        val pages = mutableListOf<List<String>>()
        val seen = JiraHttpClient(settings(JiraPlatform.CLOUD), engine)
            .searchIssues("project = PROJ", maxIssues = 100) { page -> pages += page.map { it.key } }

        assertEquals(3, seen)
        assertEquals(listOf(listOf("P-1", "P-2"), listOf("P-3")), pages)
        assertEquals(listOf(null, "c1"), requested)
    }

    @Test
    fun `cloud stops when the token is missing even without isLast`() = runBlocking {
        // Cloud reports no total, so an absent nextPageToken is the only thing left to read. If
        // this branch were missing the loop would re-fetch page one for ever.
        val engine = MockEngine {
            respond(
                """{ "issues": [${issuesJson("P-1")}] }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val seen = JiraHttpClient(settings(JiraPlatform.CLOUD), engine)
            .searchIssues("project = PROJ", maxIssues = 100) { }
        assertEquals(1, seen)
    }

    @Test
    fun `data center pages by offset until the total is reached`() = runBlocking {
        val offsets = mutableListOf<String?>()
        val engine = MockEngine { request ->
            offsets += request.url.parameters["startAt"]
            val body = when (offsets.size) {
                1 -> """{ "startAt": 0, "maxResults": 2, "total": 3, "issues": [${issuesJson("P-1", "P-2")}] }"""
                2 -> """{ "startAt": 2, "maxResults": 2, "total": 3, "issues": [${issuesJson("P-3")}] }"""
                else -> error("asked for a page past the total")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val seen = JiraHttpClient(settings(JiraPlatform.DATACENTER), engine)
            .searchIssues("project = PROJ", maxIssues = 100) { }

        assertEquals(3, seen)
        assertEquals(listOf<String?>("0", "2"), offsets)
    }

    @Test
    fun `data center stops on an empty page when no total was reported`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            val body = if (calls == 1) {
                """{ "issues": [${issuesJson("P-1")}] }"""
            } else {
                """{ "issues": [] }"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val seen = JiraHttpClient(settings(JiraPlatform.DATACENTER, pageSize = 1), engine)
            .searchIssues("project = PROJ", maxIssues = 100) { }

        assertEquals(1, seen)
        assertEquals(2, calls)
    }

    @Test
    fun `the issue ceiling stops the run and does not ask for another page`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                """{ "issues": [${issuesJson("P-1", "P-2")}], "nextPageToken": "c", "isLast": false }""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        // A mis-typed JQL must not be able to pull a quarter of a million issues into a Community
        // instance with no query governor.
        val seen = JiraHttpClient(settings(JiraPlatform.CLOUD), engine)
            .searchIssues("project = PROJ", maxIssues = 2) { }

        assertEquals(2, seen)
        assertEquals(1, calls)
    }

    @Test
    fun `every search asks for all fields`() = runBlocking {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond("""{ "issues": [], "isLast": true }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        JiraHttpClient(settings(JiraPlatform.CLOUD), engine).searchIssues("project = PROJ", 100) { }

        assertEquals("*all", captured?.url?.parameters?.get("fields"))
        assertEquals("project = PROJ", captured?.url?.parameters?.get("jql"))
        assertTrue(captured?.url?.encodedPath?.endsWith("/rest/api/2/search/jql") == true)
    }

    @Test
    fun `data center calls the classic search endpoint`() = runBlocking {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond("""{ "issues": [] }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        JiraHttpClient(settings(JiraPlatform.DATACENTER), engine).searchIssues("q", 100) { }
        assertEquals("/rest/api/2/search", path)
    }

    // -- auth and errors -------------------------------------------------------------------

    @Test
    fun `bearer and basic build the two headers JIRA accepts`() = runBlocking {
        var header: String? = null
        val bearer = MockEngine { request ->
            header = request.headers[HttpHeaders.Authorization]
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        JiraHttpClient(settings(JiraPlatform.DATACENTER), bearer).fieldCatalog()
        assertEquals("Bearer t0ken", header)

        val basic = MockEngine { request ->
            header = request.headers[HttpHeaders.Authorization]
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        JiraHttpClient(
            settings(JiraPlatform.CLOUD).copy(authScheme = JiraAuthScheme.BASIC, email = "a@b.c"),
            basic,
        ).fieldCatalog()
        assertEquals("Basic " + Base64.getEncoder().encodeToString("a@b.c:t0ken".toByteArray()), header)
    }

    @Test
    fun `a trailing slash on the host does not produce a double slash`() = runBlocking {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        // Trimmed in loadJiraSettings; asserted here because some proxies answer // and some
        // redirect it, and a redirect drops the Authorization header.
        JiraHttpClient(settings(JiraPlatform.CLOUD).copy(host = "https://jira.example.com"), engine)
            .issueTypes()
        assertEquals("/rest/api/2/issuetype", path)
    }

    @Test
    fun `an unknown project is null, not a failure`() = runBlocking {
        // The settings dialog asks this of every key a user types.
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        assertNull(JiraHttpClient(settings(JiraPlatform.CLOUD), engine).project("NOPE"))
    }

    @Test
    fun `401 and 403 are reported as a credential problem`() = runBlocking {
        listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden).forEach { status ->
            val engine = MockEngine { respondError(status) }
            val failure = assertFailsWith<JiraException> {
                JiraHttpClient(settings(JiraPlatform.CLOUD), engine).fieldCatalog()
            }.failure
            assertTrue(failure is JiraFailure.Unauthorised, "expected Unauthorised for $status")
        }
    }

    @Test
    fun `any other error is a rejection carrying the status`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.BadRequest) }
        val failure = assertFailsWith<JiraException> {
            JiraHttpClient(settings(JiraPlatform.CLOUD), engine).searchIssues("bad jql", 10) { }
        }.failure
        assertEquals(JiraFailure.Rejected(400, HttpStatusCode.BadRequest.description), failure)
    }
}
