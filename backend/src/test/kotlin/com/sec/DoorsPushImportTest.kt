package com.sec

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.security.FakeKeycloak
import com.sec.security.Oidc
import com.sec.security.Role
import com.sec.security.TEST_PRINCIPAL
import com.sec.security.authenticatedClient
import com.sec.source.doors.DoorsDerivations
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DOORS push front door end to end (ADR 0020): a real HTTP round trip, through the real
 * routing tree ([configureApp]), against a real Neo4j Community image and a real (fake) Keycloak —
 * the one combination no other test file exercises together. `DoorsImportTest` proves the writer
 * and the gate against a database with no HTTP or auth layer at all; `AuthGuardTest` proves the
 * bearer-vs-session separation with no database. This proves the three outcomes ADR 0019 §3
 * promises are reachable through the second front door specifically, and that ADR 0020 §5's claim
 * — "assign categories to the group" needs no new code — is true of the real access-control
 * pipeline, not just the routing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DoorsPushImportTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private val keycloak = FakeKeycloak()
    private val oidcHttpClient = HttpClient(OkHttp)

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        keycloak.start()
    }

    @AfterAll
    fun tearDown() {
        oidcHttpClient.close()
        keycloak.stop()
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n")) { }
    }

    private fun oidc(): Oidc = Oidc(keycloak.authSettings(), oidcHttpClient)

    @Test
    fun `a fresh push starts a run, and the pushing group becomes visible under Access`() = testApplication {
        val sessionStorage = SessionStorageMemory()
        application { configureApp(graphDriver = graphDriver, oidc = oidc(), sessionStorage = sessionStorage) }
        val pushClient = createPushClient()

        val response = pushClient.post("/api/v1/doors/import/push") {
            contentType(ContentType.Application.Json)
            setBody(exportJson())
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"started\""), response.bodyAsText())

        val accessManager = authenticatedClient(
            sessionStorage,
            principal = TEST_PRINCIPAL.copy(roles = setOf(Role.ACCESS_MANAGER)),
        )
        val groups = accessManager.get("/api/v1/access/groups").bodyAsText()
        assertTrue(groups.contains("/SEC/Importers"), groups)
    }

    @Test
    fun `a module nobody has categorised yet cannot be re-pushed, even by the account that created it`() =
        testApplication {
            application { configureApp(graphDriver = graphDriver, oidc = oidc()) }
            val pushClient = createPushClient()
            val body = exportJson()

            val first = pushClient.post("/api/v1/doors/import/push") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Accepted, first.status)
            awaitChecksum(MODULE_URL)

            // No category was ever assigned (no :__AccessDefault configured for this test) — the
            // ordinary "not yet assigned" state (R8), and /SEC/Importers has no grant to fall back
            // on either, so the very account that created the module cannot act on it again yet.
            val second = pushClient.post("/api/v1/doors/import/push") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NotFound, second.status)
        }

    @Test
    fun `once the pushing group is granted a category the module is tagged with, a checksum-identical re-push is skipped`() =
        testApplication {
            application { configureApp(graphDriver = graphDriver, oidc = oidc()) }
            val pushClient = createPushClient()
            val body = exportJson()

            // Granted *before* the first push, deliberately: AccessResolver caches an AccessSet per
            // group set and is only invalidated by AccessAdminService's own write path
            // (docs/features/access-control.md §5 "Caching") — a write seeded directly into the
            // graph, as this one is, never calls that invalidation. The first push is what
            // populates the cache for "/SEC/Importers" (its `call.accessSet(accessResolver)` runs
            // before the gate even knows whether the module exists), so the grant has to be in
            // place before that first read for this test to see it, exactly as it would need to be
            // in a real deployment if the same server process had never resolved that group before.
            grantGroupCategory(groupKey = "/SEC/Importers", categoryId = "cat-push-test")

            assertEquals(
                HttpStatusCode.Accepted,
                pushClient.post("/api/v1/doors/import/push") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status,
            )
            awaitChecksum(MODULE_URL)

            // The exact write PUT /api/v1/access/containers/{ref}/categories performs, seeded
            // directly — this test is about the push route's gate, not the Access admin screens,
            // which have their own coverage. Tagging the module is unaffected by the caching
            // concern above: the resolver caches what a *group* may read, not what any one object
            // carries, so this write needs no invalidation to be seen on the next gate read.
            tagModuleCategory(moduleId = MODULE_URL, categoryId = "cat-push-test")

            val response = pushClient.post("/api/v1/doors/import/push") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"status\":\"skipped\""), response.bodyAsText())
        }

    // -- helpers ----------------------------------------------------------------------------------

    /**
     * A `202` answers as soon as the run is accepted, not once it finishes (SSE progress is the
     * mechanism for that in the real product) — so a test that pushes twice in a row must wait for
     * the first run's write phase to actually land before the second push's gate reads anything
     * meaningful. `__exportChecksum` is stamped **last**, after reconciliation succeeds (ADR 0019
     * §3), so waiting for it rather than for bare module existence also waits past the module
     * having been merely created but not yet fully reconciled.
     */
    private suspend fun awaitChecksum(moduleId: String): String {
        repeat(100) {
            val checksum = graphDriver.executeRead(
                Query(
                    "CYPHER 25 MATCH (m:SEItem {__id: \$id}) RETURN m.__exportChecksum AS c",
                    mapOf("id" to moduleId),
                ),
            ) { records -> records.singleOrNull()?.get("c")?.takeUnless { it.isNull }?.asString() }
            if (checksum != null) return checksum
            delay(50)
        }
        error("$moduleId never finished importing (no __exportChecksum stamped) within the timeout")
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.createPushClient() = createClient {
        defaultRequest { header(HttpHeaders.Authorization, "Bearer ${keycloak.signedAccessToken()}") }
    }

    private suspend fun grantGroupCategory(groupKey: String, categoryId: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MERGE (g:__Group {key: ${'$'}groupKey})
                  ON CREATE SET g.name = ${'$'}groupKey, g.seesAll = false
                MERGE (c:__Meta:__AccessCategory {__metaId: ${'$'}categoryId})
                  ON CREATE SET c.__metaKind = 'accessCategory', c.__schemaVersion = 1,
                    c.key = ${'$'}categoryId, c.name = ${'$'}categoryId, c.everyGroup = false
                MERGE (g)-[:__mayRead]->(c)
                """,
                mapOf("groupKey" to groupKey, "categoryId" to categoryId),
            ),
        ) { }
    }

    private suspend fun tagModuleCategory(moduleId: String, categoryId: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (m:SEItem {__id: ${'$'}moduleId})
                MATCH (c:__Meta:__AccessCategory {__metaId: ${'$'}categoryId})
                MERGE (m)-[:__inAccessCategory {origin: 'direct'}]->(c)
                """,
                mapOf("moduleId" to moduleId, "categoryId" to categoryId),
            ),
        ) { }
    }

    /** One heading, the smallest valid DOORS export — same shape `DoorsImportTest`'s own fixture
     *  builds, hand-written here rather than shared across files since neither is public. */
    private fun exportJson(): String {
        val objectUrl = DoorsDerivations.targetObjectUrl(MODULE_URL, "1")
        return """
            {
              "__objectId": "mod-push-1", "__name": "Push test module", "__version": "current",
              "description": "", "moduleFullPath": "/T/Push test module",
              "url": "$MODULE_URL",
              "__contents": [
                {
                  "id": "OBJ-1", "objectNumber": "1", "objectLevel": "1",
                  "__moduleUrl": "$MODULE_URL",
                  "Object Heading": "Heading 1", "Object Text": "", "Object Short Text": "",
                  "Object Type": "Heading", "Absolute Number": "1",
                  "__objectUrl": "$objectUrl",
                  "__tableObject": "false", "__tableID": "", "__tableURL": "",
                  "__tableRowIndex": "", "__tableColumnIndex": "",
                  "__outputLinks": [], "__inputLinks": []
                }
              ]
            }
        """.trimIndent()
    }

    private companion object {
        const val MODULE_URL = "doors://d:9601/?version=2&prodID=0&urn=urn:telelogic::1-0-M-push1"
    }
}
