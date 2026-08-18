package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.security.FakeKeycloak
import com.sec.security.Oidc
import com.sec.security.authenticatedClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
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

        // POST /api/v1/doors/import/push is deliberately NOT in this table (ADR 0020). It sits
        // outside requireSecSession entirely — bearer-authenticated, not session-authenticated —
        // so "no session" says nothing about whether it is reachable; see the dedicated
        // `DOORS push` tests below for what actually guards it.
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

    // -- DOORS push (ADR 0020): a second, independent authentication mechanism ------------------
    //
    // The interesting regression these guard against is the two mechanisms silently merging —
    // either the push route starting to accept a session cookie, or a session-authenticated route
    // starting to accept a push bearer token. Neither should ever happen; both are proven false
    // here rather than left to be noticed later.

    private val pushPath = "/api/v1/doors/import/push"

    @Test
    fun `the push route refuses a request with no Authorization header at all`() = testApplication {
        appWithoutGraph()

        assertEquals(HttpStatusCode.Unauthorized, client.post(pushPath).status)
    }

    @Test
    fun `a valid session cookie alone does not authorize the push route`() = testApplication {
        val sessionStorage = SessionStorageMemory()
        application {
            configureApp(
                GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                sessionStorage = sessionStorage,
            )
        }
        val client = authenticatedClient(sessionStorage)

        // The cookie that satisfies requireSecSession everywhere else carries no
        // Authorization header, so PushAuthNames.PROVIDER sees no credential at all.
        assertEquals(HttpStatusCode.Unauthorized, client.post(pushPath).status)
    }

    @Test
    fun `a bearer token minted for the browser client is rejected on the push route`() = testApplication {
        val keycloak = FakeKeycloak()
        keycloak.start()
        val oidcHttpClient = HttpClient(OkHttp)
        try {
            application {
                configureApp(
                    GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                    oidc = Oidc(keycloak.authSettings(), oidcHttpClient),
                )
            }

            val response = client.post(pushPath) {
                header(HttpHeaders.Authorization, "Bearer ${keycloak.signedAccessToken(azp = "sec-backend")}")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        } finally {
            oidcHttpClient.close()
            keycloak.stop()
        }
    }

    @Test
    fun `a bearer token minted for the push client is not enough alone to reach a session-guarded route`() =
        testApplication {
            val keycloak = FakeKeycloak()
            keycloak.start()
            val oidcHttpClient = HttpClient(OkHttp)
            try {
                application {
                    configureApp(
                        GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                        oidc = Oidc(keycloak.authSettings(), oidcHttpClient),
                    )
                }

                val response = client.get("/api/v1/modules") {
                    header(HttpHeaders.Authorization, "Bearer ${keycloak.signedAccessToken()}")
                }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            } finally {
                oidcHttpClient.close()
                keycloak.stop()
            }
        }
}
