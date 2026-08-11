package com.sec.source.jira

import com.sec.config.JiraDeployment
import com.sec.config.JiraSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloud's cursor paging — the second implementation behind `searchAll` (ADR 0014).
 *
 * Every shape asserted here was read off a live Cloud instance rather than a document: the response
 * carries `issues`, `isLast` and a `nextPageToken` that is **absent on the last page**, and there is
 * no `total`, no `maxResults` and no `startAt` anywhere in it.
 *
 * The sibling file `JiraSearchPagingTest` covers Data Center. Both exist because the two products
 * fail in opposite ways: Data Center's loop can skip pages while reporting success, and Cloud's can
 * spin forever on a cursor that does not advance.
 */
class JiraCloudSearchTest {

    @Test
    fun `it walks pages by cursor until isLast`() {
        val tokensSent = mutableListOf<String?>()
        val client = cursorClient(pages = 3, tokensSent = tokensSent)

        val seen = mutableListOf<JiraIssuePage>()
        val summary = runBlocking { client.searchAll(JQL) { seen += it } }.getOrThrow()

        // First request carries no cursor; each one after it carries the previous page's token.
        assertEquals(listOf(null, "cursor-1", "cursor-2"), tokensSent)
        assertEquals(3, summary.pages)
        assertEquals(6, summary.issuesSeen)
        assertEquals(listOf(0, 2, 4), seen.map { it.startAt })
    }

    /**
     * `startAt` is synthesised on Cloud, and it has to be: nothing in the response says how far in
     * a page begins. It is a running count, which is the same thing Data Center's offset means —
     * which is what lets everything downstream of the loop ignore the difference entirely.
     */
    @Test
    fun `startAt is the running count, because Cloud never sends one`() {
        val client = cursorClient(pages = 2, issuesPerPage = 5)

        val seen = mutableListOf<JiraIssuePage>()
        runBlocking { client.searchAll(JQL) { seen += it } }.getOrThrow()

        assertEquals(listOf(0, 5), seen.map { it.startAt })
    }

    /**
     * The last page of a real Cloud response has no `nextPageToken` **key at all**, so the loop must
     * stop on either signal alone. A page claiming `isLast: false` while offering no way forward is
     * malformed, and guessing at a cursor is not a recovery.
     */
    @Test
    fun `a missing cursor ends the walk even when the page does not say it is last`() {
        val engine = MockEngine {
            respondJson("""{"isLast":false,"issues":[${issue(1)}]}""")
        }

        val summary = runBlocking { cloudClient(engine).searchAll(JQL) { } }.getOrThrow()

        assertEquals(1, summary.pages)
        assertEquals(1, summary.issuesSeen)
    }

    /**
     * The failure mode a cursor loop has and an offset loop does not: a server that keeps handing
     * back the cursor it was given re-reads page one forever. The page cap would eventually stop it,
     * after ten thousand identical requests; standing still is worth naming immediately.
     */
    @Test
    fun `a cursor that never advances is a failure, not an infinite loop`() {
        var searches = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("approximate-count")) {
                respondJson("""{"count":9}""")
            } else {
                searches++
                respondJson("""{"isLast":false,"nextPageToken":"stuck","issues":[${issue(searches)}]}""")
            }
        }

        val result = runBlocking { cloudClient(engine).searchAll(JQL) { } }

        assertIs<JiraFailure.TooManyPages>(result.exceptionOrNull())
        // Two: the first page establishes the cursor, the second proves it did not move.
        assertEquals(2, searches)
    }

    /** The other hang: fresh cursors forever. Counted on every response, so empty pages count too. */
    @Test
    fun `endlessly fresh cursors are stopped by the page cap`() {
        var requests = 0
        val engine = MockEngine {
            requests++
            respondJson("""{"isLast":false,"nextPageToken":"cursor-$requests","issues":[]}""")
        }

        val result = runBlocking { cloudClient(engine).searchAll(JQL, maxPages = 4) { } }

        assertEquals(4, assertIs<JiraFailure.TooManyPages>(result.exceptionOrNull()).pages)
    }

    /**
     * The denominator comes from `approximate-count`, once, before the first page — the only way a
     * Cloud run can have one at all.
     */
    @Test
    fun `the total comes from approximate-count and reaches every page`() {
        var counts = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("approximate-count")) {
                counts++
                assertEquals(HttpMethod.Post, request.method)
                respondJson("""{"count":42}""")
            } else {
                respondJson("""{"isLast":true,"issues":[${issue(1)}]}""")
            }
        }

        val seen = mutableListOf<JiraIssuePage>()
        runBlocking { cloudClient(engine).searchAll(JQL) { seen += it } }.getOrThrow()

        assertEquals(1, counts, "the count was fetched per page rather than once")
        assertEquals(42, seen.single().estimatedTotal)
    }

    /**
     * A progress bar is not worth an import.
     *
     * `approximate-count` is newer than the search endpoint and is exactly the sort of thing a proxy
     * or a future version breaks first. Failing the run over it would trade all of the data for the
     * denominator of a progress display.
     */
    @Test
    fun `a failing approximate-count costs the total and nothing else`() {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("approximate-count")) {
                respond("nope", HttpStatusCode.NotFound)
            } else {
                respondJson("""{"isLast":true,"issues":[${issue(1)}]}""")
            }
        }

        val seen = mutableListOf<JiraIssuePage>()
        val summary = runBlocking { cloudClient(engine).searchAll(JQL) { seen += it } }.getOrThrow()

        assertEquals(1, summary.issuesSeen)
        assertNull(seen.single().estimatedTotal)
    }

    /** Cloud refuses unbounded JQL, so the query still has to arrive intact, with every field asked for. */
    @Test
    fun `the request asks for all fields and carries the query`() {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("approximate-count")) {
                respondJson("""{"count":1}""")
            } else {
                assertEquals("*all", request.url.parameters["fields"])
                assertEquals(JQL, request.url.parameters["jql"])
                assertNull(request.url.parameters["startAt"], "an offset was sent to a cursor endpoint")
                respondJson("""{"isLast":true,"issues":[${issue(1)}]}""")
            }
        }

        runBlocking { cloudClient(engine).searchAll(JQL) { } }.getOrThrow()
    }

    /** The endpoint is chosen by configuration, and this is the assertion that says so. */
    @Test
    fun `the deployment setting decides which endpoint is called`() {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("approximate-count")) {
                respondJson("""{"count":0}""")
            } else {
                respondJson("""{"isLast":true,"issues":[],"startAt":0,"maxResults":50,"total":0}""")
            }
        }

        runBlocking { cloudClient(engine).searchAll(JQL) { } }.getOrThrow()
        assertTrue(paths.any { it.endsWith("/search/jql") }, "Cloud did not use /search/jql: $paths")
        assertTrue(paths.none { it.endsWith("/rest/api/2/search") }, "Cloud called Data Center's /search")

        paths.clear()
        runBlocking { JiraHttpClient(settings(JiraDeployment.DATA_CENTER), engine).searchAll(JQL) { } }
            .getOrThrow()
        assertEquals(listOf("/rest/api/2/search"), paths)
    }

    /** An error page ends the walk where it happened, exactly as the offset loop does. */
    @Test
    fun `a rejected page stops the walk`() {
        var requests = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("approximate-count") -> respondJson("""{"count":4}""")
                requests++ == 0 ->
                    respondJson("""{"isLast":false,"nextPageToken":"cursor-1","issues":[${issue(1)}]}""")
                else -> respond("nope", HttpStatusCode.Unauthorized)
            }
        }

        val seen = mutableListOf<JiraIssuePage>()
        val result = runBlocking { cloudClient(engine).searchAll(JQL) { seen += it } }

        assertIs<JiraFailure.Unauthorized>(result.exceptionOrNull())
        assertEquals(1, seen.size, "the run continued past a failed page")
    }

    // -- harness ------------------------------------------------------------------------------

    private fun cursorClient(
        pages: Int,
        issuesPerPage: Int = 2,
        tokensSent: MutableList<String?> = mutableListOf(),
    ): JiraHttpClient {
        var page = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("approximate-count")) {
                respondJson("""{"count":${pages * issuesPerPage}}""")
            } else {
                tokensSent += request.url.parameters["nextPageToken"]
                page++
                val issues = (0 until issuesPerPage).joinToString(",") { issue(page * 10 + it) }
                val last = page >= pages
                // The real shape: no token at all on the final page, rather than a null one.
                val cursor = if (last) "" else ""","nextPageToken":"cursor-$page""""
                respondJson("""{"isLast":$last$cursor,"issues":[$issues]}""")
            }
        }
        return cloudClient(engine)
    }

    private fun cloudClient(engine: MockEngine) = JiraHttpClient(settings(JiraDeployment.CLOUD), engine)

    private fun settings(deployment: JiraDeployment) = JiraSettings(
        host = "https://example.atlassian.net",
        token = "t",
        deployment = deployment,
        pageSize = 100,
    )

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun issue(n: Int): String =
        """{"id":"$n","key":"SCRUM-$n","self":"https://example.atlassian.net/rest/api/2/issue/$n",""" +
            """"fields":{"summary":"Issue $n"}}"""

    private companion object {
        const val JQL = """project in ("SCRUM") AND created <= "2026/08/11 14:32" ORDER BY key ASC"""
    }
}
