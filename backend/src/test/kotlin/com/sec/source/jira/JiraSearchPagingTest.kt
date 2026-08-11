package com.sec.source.jira

import com.sec.config.JiraSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The paging loop of spec §3.3 — the part of this client most able to lose data quietly.
 *
 * Every failure it guards against reports **success**: a run that skipped two thirds of the issues
 * finishes green, and the only symptom is a graph missing things nobody has looked for yet. So the
 * assertions here are about the requests actually issued, not about the result.
 */
class JiraSearchPagingTest {

    /**
     * The trap the spec calls the single most common cause of skipped pages, and the whole reason
     * [JiraSearchPage.maxResults] is read at all.
     *
     * The client asks for 100. The server answers `maxResults: 50`, silently clamped to its own
     * `jira.search.views.default.max`. Striding by the *requested* 100 would fetch startAt 0, 100,
     * 200 … and skip every other page: 8 pages instead of 16, 400 issues of 784, and no error
     * anywhere.
     */
    @Test
    fun `the loop strides by the maxResults the server returned, not the one requested`() {
        val requested = mutableListOf<Int>()
        val client = pagingClient(serverMaxResults = 50, total = 784, requestedStartAt = requested)

        val summary = runBlocking { client.searchAll(JQL) { } }.getOrThrow()

        assertEquals((0 until 784 step 50).toList(), requested)
        assertEquals(16, summary.pages)
        assertEquals(784, summary.issuesSeen)
    }

    /** The straightforward case, so the test above is known to be measuring the clamp. */
    @Test
    fun `an unclamped server pages by the requested size`() {
        val requested = mutableListOf<Int>()
        val client = pagingClient(serverMaxResults = 100, total = 784, requestedStartAt = requested)

        val summary = runBlocking { client.searchAll(JQL) { } }.getOrThrow()

        assertEquals(listOf(0, 100, 200, 300, 400, 500, 600, 700), requested)
        assertEquals(8, summary.pages)
        assertEquals(784, summary.issuesSeen)
    }

    /**
     * `total` is an estimate under concurrent modification, so an empty page ends the loop even
     * when `total` claims there is more. Without this the loop keeps asking for pages that will
     * never come.
     */
    @Test
    fun `an empty page ends the loop even when total says otherwise`() {
        val client = pagingClient(
            serverMaxResults = 50,
            total = 10_000,
            issuesPerPage = { startAt -> if (startAt >= 100) 0 else 50 },
        )

        val summary = runBlocking { client.searchAll(JQL) { } }.getOrThrow()

        assertEquals(2, summary.pages)
        assertEquals(100, summary.issuesSeen)
    }

    /**
     * A zero stride re-reads page one forever. It is the one failure in this loop worse than
     * skipping issues, because it never ends — and holds a run open while it does not end.
     */
    @Test
    fun `a server reporting maxResults zero does not produce an infinite loop`() {
        val requested = mutableListOf<Int>()
        val client = pagingClient(serverMaxResults = 0, total = 784, requestedStartAt = requested)

        val summary = runBlocking { client.searchAll(JQL) { } }.getOrThrow()

        // Fell back to striding by the page's own size rather than standing still.
        assertTrue(requested.size < 100, "the loop did not advance: ${requested.take(5)}")
        assertEquals(784, summary.issuesSeen)
    }

    /** The backstop against a server that ignores `startAt` entirely. */
    @Test
    fun `a server that never terminates is stopped by the page cap`() {
        val client = pagingClient(serverMaxResults = 50, total = Int.MAX_VALUE)

        val result = runBlocking { client.searchAll(JQL, maxPages = 5) { } }

        assertEquals(5, assertIs<JiraFailure.TooManyPages>(result.exceptionOrNull()).pages)
    }

    /** Pages are handed over as they arrive, in order, and never accumulated. */
    @Test
    fun `each page reaches the callback once, in order`() {
        val seenStartAt = mutableListOf<Int>()
        val client = pagingClient(serverMaxResults = 50, total = 200)

        runBlocking { client.searchAll(JQL) { page -> seenStartAt += page.startAt } }.getOrThrow()

        assertEquals(listOf(0, 50, 100, 150), seenStartAt)
    }

    /**
     * A failed page ends the run rather than being passed over. Skipping one would be a silent
     * deletion later: phase 5 sweeps every issue it did not see this run.
     */
    @Test
    fun `a page that fails ends the search instead of being passed over`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 2) respond("", HttpStatusCode.Unauthorized)
            else respondJson(page(startAt = 0, maxResults = 50, total = 500, issues = 50))
        }
        val client = JiraHttpClient(settings(), engine)

        val pagesSeen = mutableListOf<Int>()
        val result = runBlocking { client.searchAll(JQL) { pagesSeen += it.startAt } }

        assertIs<JiraFailure.Unauthorized>(result.exceptionOrNull())
        assertEquals(1, pagesSeen.size, "the run continued past a failed page")
    }

    /** JIRA uses `warningMessages` for things like a clause that matched nothing: information. */
    @Test
    fun `warnings are collected rather than treated as failures`() {
        val engine = MockEngine {
            respondJson(
                """{"startAt":0,"maxResults":50,"total":1,"issues":[${issue(0)}],""" +
                    """"warningMessages":["The value 'x' does not exist for the field 'y'."]}""",
            )
        }

        val summary = runBlocking { JiraHttpClient(settings(), engine).searchAll(JQL) { } }.getOrThrow()

        assertEquals(1, summary.warnings.size)
        assertEquals(1, summary.issuesSeen)
    }

    /**
     * `fields=*all` is what lets the column picker offer any field without a re-import.
     *
     * Asserted on the parsed parameters, not on the URL text: Ktor percent-encodes `*` to `%2A`
     * and the JQL to a long `%22…%3C%3D` string, both of which the server decodes back. Matching
     * the raw URL would be testing Ktor's encoder and would fail on a correct request.
     */
    @Test
    fun `every search request asks for all fields and carries the paging parameters`() {
        val parameters = mutableListOf<Parameters>()
        val engine = MockEngine { request ->
            parameters += request.url.parameters
            respondJson(page(startAt = 0, maxResults = 50, total = 1, issues = 1))
        }

        runBlocking { JiraHttpClient(settings(), engine).searchAll(JQL) { } }.getOrThrow()

        val sent = parameters.single()
        assertEquals("*all", sent["fields"])
        assertEquals(JQL, sent["jql"])
        assertEquals("0", sent["startAt"])
        // The size *requested*. What the server answers with is the stride, which is the subject
        // of every other test in this file.
        assertEquals("100", sent["maxResults"])
    }

    // -- harness ------------------------------------------------------------------------------

    private fun pagingClient(
        serverMaxResults: Int,
        total: Int,
        issuesPerPage: (Int) -> Int = { startAt ->
            val stride = if (serverMaxResults > 0) serverMaxResults else 50
            (total - startAt).coerceIn(0, stride)
        },
        requestedStartAt: MutableList<Int> = mutableListOf(),
    ): JiraHttpClient {
        val engine = MockEngine { request ->
            val startAt = request.url.parameters["startAt"]?.toInt() ?: 0
            requestedStartAt += startAt
            respondJson(page(startAt, serverMaxResults, total, issuesPerPage(startAt)))
        }
        return JiraHttpClient(settings(), engine)
    }

    private fun settings() =
        JiraSettings(host = "https://jira.example.com", token = "t", pageSize = 100)

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun page(startAt: Int, maxResults: Int, total: Int, issues: Int): String =
        """{"startAt":$startAt,"maxResults":$maxResults,"total":$total,""" +
            """"issues":[${(0 until issues).joinToString(",") { issue(startAt + it) }}]}"""

    private fun issue(n: Int): String =
        """{"id":"$n","key":"PROJ-$n","self":"https://jira.example.com/rest/api/2/issue/$n",""" +
            """"fields":{"summary":"Issue $n"}}"""

    private companion object {
        const val JQL = """project in ("PROJ") AND created <= "2026/08/11 14:32" ORDER BY key ASC"""
    }
}
