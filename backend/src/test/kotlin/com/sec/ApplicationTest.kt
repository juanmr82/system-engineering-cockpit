package com.sec

import com.sec.api.respondProblem
import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.security.authenticatedClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The HTTP surface without a database. configureApp() (Application.kt) is the half of module()
// that needs no live graph, so error mapping and route resolution are testable without Docker.
// The driver constructed here is never used: the Neo4j driver connects lazily, and none of these
// requests reaches a query.
class ApplicationTest {

    private fun ApplicationTestBuilder.appWithoutGraph() {
        application {
            configureApp(GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")))
        }
    }

    // Two of the routes below sit behind the session guard (ADR 0017) now that it wraps every
    // feature route. `/api/v1/nope` (the 404 tests) and the ad-hoc /api/v1/probe route both stay
    // reachable with no session — see Routes.kt and AuthRoutes.kt for exactly which paths do.
    private fun ApplicationTestBuilder.appWithSession(): HttpClient {
        val sessionStorage = SessionStorageMemory()
        application {
            configureApp(
                GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                sessionStorage = sessionStorage,
            )
        }
        return authenticatedClient(sessionStorage)
    }

    @Test
    fun `health endpoint responds ok`() = testApplication {
        appWithoutGraph()

        val response = client.get("/api/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `an unknown path is a problem detail, not an empty 404`() = testApplication {
        appWithoutGraph()

        val response = client.get("/api/v1/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":404"), body)
        assertTrue(body.contains("Not found"), body)
    }

    // Was a 500 with the JDK's "Illegal base64 character 21" in the body (BACKEND_REVIEW §3.1).
    @Test
    fun `a malformed ref is a 400 and leaks nothing`() = testApplication {
        val client = appWithSession()

        val response = client.get("/api/v1/modules/!!!not-base64!!!")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid reference"), body)
        assertFalse(body.contains("base64", ignoreCase = true), body)
    }

    // Was a 400 naming the internal DTO class in the body (BACKEND_REVIEW §3.1).
    @Test
    fun `a malformed request body names no internal type`() = testApplication {
        val client = appWithSession()

        val response = client.post("/api/v1/modules/${Ref.encode("module-1")}/settings") {
            contentType(ContentType.Application.Json)
            setBody("{bad json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Malformed request"), body)
        assertFalse(body.contains("com.sec"), body)
    }

    @Test
    fun `every problem detail carries the call id so a report is traceable`() = testApplication {
        appWithoutGraph()

        val response = client.get("/api/v1/nope")

        assertTrue(response.bodyAsText().contains("\"instance\":"), response.bodyAsText())
    }

    // Guards the reason there is no status(NotFound) handler in StatusPages: such a handler fires
    // for every 404 response and would replace this route's specific body with the generic one.
    @Test
    fun `a route's own 404 keeps its specific message`() = testApplication {
        appWithoutGraph()
        application {
            routing {
                get("/api/v1/probe") {
                    call.respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
                }
            }
        }

        val response = client.get("/api/v1/probe")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Module not found"), response.bodyAsText())
    }
}
