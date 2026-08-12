package com.sec

import com.sec.config.JiraSettings
import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.source.jira.JiraHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/v1/jira/health` — what the settings page's *Test connection* button reads.
 *
 * The endpoint's job is to distinguish two failures that need different people to fix them, so
 * these tests are mostly about that distinction surviving: **not configured** is an operator
 * editing a file, **configured but unreachable** is a token or a network. A single "not working"
 * would collapse them, and that is what most of the assertions below are guarding.
 *
 * Note this is the one JIRA route that answers when the integration is off — reporting that state
 * is its entire purpose, so a 503 here would leave the caller inferring it from a status code.
 */
class JiraHealthRouteTest {

    @Test
    fun `an unconfigured server reports not configured, and still answers 200`() = testApplication {
        app(settings = JiraSettings(host = "", token = ""), jiraClient = null)

        val response = client.get("/api/v1/jira/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"configured\":false"), body)
        assertTrue(body.contains("\"reachable\":false"), body)
    }

    @Test
    fun `a working instance reports the resolved user`() = testApplication {
        app(jiraClient = jiraClient(MYSELF_JSON))

        val body = client.get("/api/v1/jira/health").bodyAsText()

        assertTrue(body.contains("\"configured\":true"), body)
        assertTrue(body.contains("\"reachable\":true"), body)
        assertTrue(body.contains("Ada Lovelace"), body)
    }

    /**
     * Configured **and** unreachable — the case a single boolean would lose. An operator reading
     * this must not go looking for a missing config value.
     */
    @Test
    fun `a rejected token reports configured but not reachable`() = testApplication {
        app(jiraClient = jiraClient(status = HttpStatusCode.Unauthorized))

        val body = client.get("/api/v1/jira/health").bodyAsText()

        assertTrue(body.contains("\"configured\":true"), body)
        assertTrue(body.contains("\"reachable\":false"), body)
        assertTrue(body.contains("token", ignoreCase = true), body)
    }

    /**
     * The endpoint reports on a credential, which makes it exactly the endpoint most likely to
     * leak one. Nothing about the token may cross the wire but the fact that one is set.
     */
    @Test
    fun `no response ever contains the token`() = testApplication {
        app(jiraClient = jiraClient(MYSELF_JSON))

        val body = client.get("/api/v1/jira/health").bodyAsText()

        assertFalse(body.contains(TOKEN), body)
    }

    @Test
    fun `the host is reported so the page can say which JIRA`() = testApplication {
        app(jiraClient = jiraClient(MYSELF_JSON))

        assertTrue(client.get("/api/v1/jira/health").bodyAsText().contains(HOST))
    }

    /** A host pointing at an SSO portal: it answers, so "unreachable" alone would mislead. */
    @Test
    fun `a non-JIRA host is reported as answering but not like JIRA`() = testApplication {
        app(jiraClient = jiraClient("<html>sign in</html>"))

        val body = client.get("/api/v1/jira/health").bodyAsText()

        assertTrue(body.contains("\"reachable\":false"), body)
        assertTrue(body.contains("context path"), body)
    }

    // -- harness --------------------------------------------------------------------------------

    // `jiraClient`, not `client`: inside testApplication, `client` is the test's own HTTP client
    // and shadowing it here would make every request in this file go somewhere surprising.
    private fun ApplicationTestBuilder.app(
        settings: JiraSettings = configured(),
        jiraClient: JiraHttpClient? = null,
    ) {
        application {
            // The driver is never used: the Neo4j driver connects lazily and no request here
            // reaches a query. Same shape as ApplicationTest.
            configureApp(
                GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                settings,
                jiraClient,
            )
        }
    }

    private fun jiraClient(json: String = "{}", status: HttpStatusCode = HttpStatusCode.OK) =
        JiraHttpClient(
            configured(),
            MockEngine {
                if (status.value >= 400) respondError(status)
                else respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    private fun configured() = JiraSettings(host = HOST, token = TOKEN)

    private companion object {
        const val HOST = "https://jira.example.com/jira"
        const val TOKEN = "s3cr3t-personal-access-token"
        const val MYSELF_JSON =
            """{"name":"alovelace","displayName":"Ada Lovelace","timeZone":"Europe/Berlin","active":true}"""
    }
}
