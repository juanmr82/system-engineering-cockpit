package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.security.authenticatedClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `docs/features/access-control.md` §14 item 8: "No session gets 401 from every route except the
 * declared exceptions — same shape [as role enforcement]." This is the one test that would fail
 * if a new feature route file were ever registered outside `requireSecSession { }` in `Routes.kt`.
 *
 * Deliberately over the real routing tree (`configureApp`/`Routes.kt`), unlike
 * `WindchillRoutesTest` / `ImportRoutesTest`, which build their own bespoke harness around a
 * single route file with no `Authentication` plugin at all — this is exactly the difference that
 * matters here.
 */
class AuthGuardTest {

    private fun ApplicationTestBuilder.appWithoutGraph() {
        application {
            configureApp(GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")))
        }
    }

    @Test
    fun `the declared exceptions answer with no session at all`() = testApplication {
        appWithoutGraph()

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health").status)
        // /ready touches the graph and this test's driver is never connected to a live one, but
        // the point here is what it is NOT: never a 401, whatever it reports about readiness.
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ready").status)
    }

    @Test
    fun `every other route is a 401 with no session`() = testApplication {
        appWithoutGraph()

        val protectedGets = listOf(
            "/api/v1/modules",
            "/api/v1/modules/${Ref.encode("module-1")}",
            "/api/v1/jira/health",
            "/api/v1/windchill/health",
            "/api/v1/windchill/documents",
            "/api/v1/config/system-levels",
            "/api/v1/config/navigation",
            "/api/v1/statistics/requirements",
        )
        protectedGets.forEach { path ->
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, "GET $path should require a session")
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/modules/system-levels").status,
            "POST /api/v1/modules/system-levels should require a session",
        )
    }

    @Test
    fun `the same routes answer past the guard with a session`() = testApplication {
        val sessionStorage = SessionStorageMemory()
        application {
            configureApp(
                GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                sessionStorage = sessionStorage,
            )
        }
        val client = authenticatedClient(sessionStorage)

        // Never a live graph here, so these may still fail downstream (a query against a driver
        // that never connects) — the assertion is narrowly about the guard, not the handler.
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/jira/health").status)
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/windchill/health").status)
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/config/system-levels").status)
    }
}
