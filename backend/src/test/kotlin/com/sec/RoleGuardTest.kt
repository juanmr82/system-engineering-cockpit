package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.security.Role
import com.sec.security.SecPrincipal
import com.sec.security.TEST_PRINCIPAL
import com.sec.security.authenticatedClient
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `docs/features/access-control.md` §14 item 7: a `sec-user` session gets `403` from every
 * administrative route.
 *
 * **Parameterised over a route table, so a new route is covered when it is added rather than when
 * someone remembers** — that is the spec's own wording and the reason this is a table and not a
 * method per route. The table below is the honest inventory of what phase 5 gates; adding a route to
 * an already-guarded subtree needs no entry here, because [everyWriteRouteIsAccountedFor] fails when
 * the backend grows a non-`GET` route that neither list mentions.
 *
 * Runs against the **real** routing tree (`configureApp`/`Routes.kt`), like [AuthGuardTest] and
 * unlike the per-file harnesses in `WindchillRoutesTest` / `ImportRoutesTest`, which build their own
 * routing with no `Authentication` plugin at all. Guarding is a property of how `Routes.kt` composes
 * the tree, so it can only be tested where that composition is real.
 *
 * The graph driver never connects. That is deliberate and costs nothing: a `403` is decided before
 * any handler runs, so an administrative route must answer `403` *without* a database — and for the
 * routes a `sec-user` may reach, the assertion is only that they are **not** `403`, whatever they go
 * on to fail with downstream.
 */
class RoleGuardTest {

    private fun ApplicationTestBuilder.appWith(sessionStorage: SessionStorageMemory) {
        application {
            configureApp(
                GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                sessionStorage = sessionStorage,
            )
        }
    }

    private fun ApplicationTestBuilder.clientAs(
        sessionStorage: SessionStorageMemory,
        vararg roles: String,
    ): HttpClient = authenticatedClient(
        sessionStorage,
        TEST_PRINCIPAL.copy(roles = roles.toSet()),
    )

    // -- the tables --------------------------------------------------------------------------

    private data class Call(val method: String, val path: String, val role: String)

    /**
     * Every route that requires a capability, and which one.
     *
     * `GET`s appear here too — the JIRA column picker and the import console are reads, and reads
     * are exactly where "administrative" is easiest to forget. What decides membership is not the
     * verb but whether the route belongs to a `/settings`-shaped screen (spec §3).
     */
    private val administrative = listOf(
        // The import console: source-agnostic, and every route in it is the console's.
        Call("GET", "/api/v1/import/importers", Role.ADMIN),
        Call("GET", "/api/v1/import/runs", Role.ADMIN),
        Call("GET", "/api/v1/import/runs/run-1", Role.ADMIN),
        Call("GET", "/api/v1/import/jira/schedule", Role.ADMIN),
        Call("POST", "/api/v1/import/jira/runs", Role.ADMIN),
        Call("DELETE", "/api/v1/import/runs/run-1", Role.ADMIN),

        // The JIRA column picker and the connection diagnostic.
        Call("GET", "/api/v1/jira/fields", Role.ADMIN),
        Call("GET", "/api/v1/jira/columns", Role.ADMIN),
        Call("GET", "/api/v1/jira/columns/defaults", Role.ADMIN),
        Call("PUT", "/api/v1/jira/columns", Role.ADMIN),
        Call("GET", "/api/v1/jira/projects", Role.ADMIN),

        // Module configuration — a policy over every object in a module, not one reviewer's note.
        Call("POST", "/api/v1/modules/system-levels", Role.ADMIN),
        Call("POST", "/api/v1/modules/${Ref.encode("module-1")}/settings", Role.ADMIN),

        // The upload that deletes every document the file does not mention (ADR 0015 §7).
        Call("POST", "/api/v1/windchill/import", Role.ADMIN),

        // The Access views' one built route.
        Call("POST", "/api/v1/access/reconcile", Role.ACCESS_MANAGER),
    )

    /**
     * Routes a plain `sec-user` must keep, and the list is as load-bearing as the one above.
     *
     * A guard that is too wide fails closed and so passes every test that only checks for `403`s —
     * it shows up as an application that has quietly stopped working for ordinary users. The
     * comment save is the one to watch: it is a `:__Meta` write, and the temptation is to gate every
     * write the same way.
     */
    private val ordinary = listOf(
        Call("GET", "/api/v1/modules", Role.USER),
        Call("GET", "/api/v1/modules/${Ref.encode("module-1")}", Role.USER),
        Call("GET", "/api/v1/modules/${Ref.encode("module-1")}/attributes", Role.USER),
        Call("GET", "/api/v1/modules/${Ref.encode("module-1")}/objects", Role.USER),
        Call("GET", "/api/v1/modules/${Ref.encode("module-1")}/tables", Role.USER),
        Call("GET", "/api/v1/items/${Ref.encode("item-1")}", Role.USER),
        Call("GET", "/api/v1/items/${Ref.encode("item-1")}/traces", Role.USER),
        Call("GET", "/api/v1/items/${Ref.encode("item-1")}/breakdown", Role.USER),
        Call("GET", "/api/v1/items/${Ref.encode("item-1")}/graph", Role.USER),
        Call("GET", "/api/v1/statistics/requirements", Role.USER),
        Call("GET", "/api/v1/statistics/requirements/cycles", Role.USER),
        Call("GET", "/api/v1/jira/issues", Role.USER),
        Call("GET", "/api/v1/jira/health", Role.USER),
        Call("GET", "/api/v1/windchill/health", Role.USER),
        Call("GET", "/api/v1/windchill/documents", Role.USER),
        Call("GET", "/api/v1/config/system-levels", Role.USER),
        // Tier 2 by an ordinary reviewer, on an object they can see — spec §3's `sec-user` row.
        Call("POST", "/api/v1/modules/${Ref.encode("module-1")}/comments", Role.USER),
        // Signing out is not a capability, and a user whose roles were revoked mid-session must
        // still be able to leave.
        Call("POST", "/api/v1/auth/logout", Role.USER),
    )

    // -- the checks --------------------------------------------------------------------------

    @Test
    fun `a sec-user is refused every administrative route`() = testApplication {
        val storage = SessionStorageMemory()
        appWith(storage)
        val user = clientAs(storage, Role.USER)

        administrative.forEach { call ->
            assertEquals(
                HttpStatusCode.Forbidden,
                user.call(call).status,
                "${call.method} ${call.path} should refuse a sec-user",
            )
        }
    }

    @Test
    fun `holding the role gets past the guard`() = testApplication {
        val storage = SessionStorageMemory()
        appWith(storage)
        // Both administrative roles at once: the point here is the guard, and splitting this into
        // two clients would only re-test that `hasRole` reads a set.
        val admin = clientAs(storage, Role.USER, Role.ADMIN, Role.ACCESS_MANAGER)

        administrative.forEach { call ->
            assertNotEquals(
                HttpStatusCode.Forbidden,
                admin.call(call).status,
                "${call.method} ${call.path} should admit a caller holding ${call.role}",
            )
        }
    }

    /**
     * The inverse, and the one that catches a guard drawn too wide.
     *
     * Over-guarding fails closed, so it passes every "is it a 403" assertion while breaking the
     * application for every ordinary user. Only this direction notices.
     */
    @Test
    fun `a sec-user keeps every route that is not administrative`() = testApplication {
        val storage = SessionStorageMemory()
        appWith(storage)
        val user = clientAs(storage, Role.USER)

        ordinary.forEach { call ->
            assertNotEquals(
                HttpStatusCode.Forbidden,
                user.call(call).status,
                "${call.method} ${call.path} must stay open to a sec-user",
            )
        }
    }

    @Test
    fun `the refusal names the capability and nothing else`() = testApplication {
        val storage = SessionStorageMemory()
        appWith(storage)
        val user = clientAs(storage, Role.USER)

        val body = user.post("/api/v1/access/reconcile").bodyAsText()

        // Spec §10.1: the frontend renders a 403 as an in-app refusal panel rather than redirecting,
        // so the sentence has to be the whole explanation.
        assertTrue(Role.ACCESS_MANAGER in body, "the refusal should name the role required: $body")
        // And nothing about what was being reached for — a capability refusal is not a place to
        // start describing objects (§7, "Error and problem-detail messages").
        assertFalse("reconcile" in body.substringAfter("\"detail\""), "the refusal should not describe the target")
    }

    /**
     * The completeness check that makes the tables above worth having.
     *
     * Every non-`GET` route the backend registers is either administrative or deliberately open, and
     * a new one is neither until someone says so. Without this the tables go stale silently — the
     * same premise `GraphNamesTest` and `AccessGuardTest` rest on, applied to routes.
     *
     * `GET`s are not enumerated: they are the majority, they are already covered by the visibility
     * predicate rather than by a role, and listing them would make this a second copy of the routing
     * table rather than a guard on it.
     */
    @Test
    fun everyWriteRouteIsAccountedFor() {
        val declared = (administrative + ordinary)
            .filter { it.method != "GET" }
            .map { it.method to it.path.substringBefore("?") }
            .toSet()

        // Registered write routes, read from the route files the same way AccessGuardTest reads
        // statements — by their own source, so a route added tomorrow is seen without a registry.
        val registered = WRITE_ROUTES.toSet()

        val unaccounted = registered.filterNot { (method, path) ->
            declared.any { (dm, dp) -> dm == method && dp.matchesTemplate(path) }
        }

        assertTrue(
            unaccounted.isEmpty(),
            "A write route is neither guarded nor declared open — decide which, and add it to " +
                "RoleGuardTest's tables: ${unaccounted.joinToString { "${it.first} ${it.second}" }}",
        )
    }

    /** `/api/v1/modules/{ref}/settings` matches the concrete path a table entry uses. */
    private fun String.matchesTemplate(template: String): Boolean {
        val actual = split("/").filter { it.isNotEmpty() }
        val expected = template.split("/").filter { it.isNotEmpty() }
        if (actual.size != expected.size) return false
        return actual.zip(expected).all { (a, e) -> e.startsWith("{") || a == e }
    }

    private suspend fun HttpClient.call(call: Call): HttpResponse = when (call.method) {
        "GET" -> get(call.path)
        "POST" -> post(call.path)
        "PUT" -> put(call.path)
        "DELETE" -> delete(call.path)
        else -> error("unhandled method ${call.method}")
    }

    private companion object {
        /**
         * Every non-`GET` route `Routes.kt` mounts, as (method, path template).
         *
         * Written out rather than discovered from Ktor's own tree, which would be the obvious move
         * and is the wrong one: reading the registered routes to decide what to check would make the
         * test agree with the application by construction, including when the application is wrong.
         * This list is the *independent* statement of what exists, and
         * [everyWriteRouteIsAccountedFor] is where the two are compared.
         */
        val WRITE_ROUTES = listOf(
            "POST" to "/api/v1/access/reconcile",
            "POST" to "/api/v1/import/{importerId}/runs",
            "DELETE" to "/api/v1/import/runs/{runId}",
            "PUT" to "/api/v1/jira/columns",
            "POST" to "/api/v1/modules/system-levels",
            "POST" to "/api/v1/modules/{ref}/settings",
            "POST" to "/api/v1/modules/{ref}/comments",
            "POST" to "/api/v1/windchill/import",
            // /auth/logout is outside every role guard on purpose: signing out is not a capability,
            // and a user whose roles were revoked mid-session must still be able to leave.
            "POST" to "/api/v1/auth/logout",
        )
    }
}
