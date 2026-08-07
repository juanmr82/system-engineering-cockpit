package com.sec

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What happens when the Angular build is inside the jar (`mvn -Pui package`).
//
// src/test/resources/static/index.html stands in for it, which is what makes these tests
// exercise the packaged shape rather than the development one. Test resources are never
// packaged, so it cannot leak into a real artifact.
//
// The point of the file is the boundary: serving a UI must not move the API even slightly. A
// mistyped endpoint returning 200 and a page instead of a problem detail would be a silent,
// confusing regression, and it is exactly what a careless static-route registration causes.
class PackagedUiTest {

    private fun ApplicationTestBuilder.appWithoutGraph() {
        application {
            configureApp(GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")))
        }
    }

    @Test
    fun `the root serves the packaged index`() = testApplication {
        appWithoutGraph()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("packaged-ui-test-marker"), response.bodyAsText())
    }

    // /requirements/modules is an Angular route. The server has no handler for it, and a user who
    // reloads that page or opens a bookmark must still get the application.
    @Test
    fun `a client-side route serves the index so a reload works`() = testApplication {
        appWithoutGraph()

        val response = client.get("/requirements/modules")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("packaged-ui-test-marker"), response.bodyAsText())
    }

    // The regression that matters. With static content mounted at "/", an unknown API path must
    // still be an RFC 9457 problem detail - not the index page with a 200.
    @Test
    fun `an unknown api path is still a problem detail, never the index`() = testApplication {
        appWithoutGraph()

        val response = client.get("/api/v1/nope")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(body.contains("\"status\":404"), body)
        assertFalse(body.contains("packaged-ui-test-marker"), body)
    }

    @Test
    fun `a real endpoint is not shadowed by the static content`() = testApplication {
        appWithoutGraph()

        val response = client.get("/api/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.bodyAsText().contains("packaged-ui-test-marker"))
    }

    // A request for a FILE that is not there is a 404, not the index page. A naive SPA fallback
    // returns index.html for everything it cannot find, so a browser asking for a stale
    // main-A1B2C3.js after a redeploy gets an HTML document with status 200 and reports a syntax
    // error in it. This application emits no favicon, so /favicon.ico is the everyday case.
    @Test
    fun `a missing asset is a 404, not the index page`() = testApplication {
        appWithoutGraph()

        val response = client.get("/favicon.ico")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertFalse(body.contains("packaged-ui-test-marker"), body)
    }

    @Test
    fun `a stale hashed bundle is a 404, not the index page`() = testApplication {
        appWithoutGraph()

        val response = client.get("/main-DEADBEEF.js")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertFalse(response.bodyAsText().contains("packaged-ui-test-marker"))
    }

    // Only GET gets the page. A POST to a path that does not exist is a client error, and
    // answering it with an HTML document would hide that.
    @Test
    fun `a non-GET to an unknown path is a problem detail`() = testApplication {
        appWithoutGraph()

        val response = client.post("/requirements/modules")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertFalse(body.contains("packaged-ui-test-marker"), body)
    }
}
