package com.sec

import com.sec.config.JiraDeployment
import com.sec.config.JiraSettings
import com.sec.config.Neo4jSettings
import com.sec.domain.CreateCategoryOutcome
import com.sec.graph.GraphDriver
import com.sec.importer.ImportContext
import com.sec.importer.ImportLogLevel
import com.sec.importer.ImportRequest
import com.sec.meta.MetaSchema
import com.sec.security.AccessAdminService
import com.sec.security.AccessContainment
import com.sec.security.AccessReconciler
import com.sec.security.AccessResolver
import com.sec.security.AccessSet
import com.sec.source.jira.JiraGraphWriter
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.jiraJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.Neo4jContainer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6's own acceptance line (`docs/features/access-control.md` §15, session 34's handover):
 * a `sec-dev-user`-shaped caller's visibility flips from **zero rows to every row**, driven
 * end to end through `AccessAdminService` and `AccessReconciler` — create category, grant, assign,
 * reconcile — with **no hand-written Cypher seeding the data it operates on**. Everything the
 * caller ends up seeing was written by a real importer reading a real export.
 *
 * **Why JIRA rather than a DOORS module** (the literal wording of "a module" in casual prose):
 * DOORS' importer is a separate Python process outside the backend, so a JVM test cannot run it
 * without shelling out — the exact question session 32 parked, unresolved, as its own future
 * feature (`access-control.md` §15.3's "direction favoured" paragraph). JIRA's importer runs
 * in-process (ADR 0013) and is the only source both true to "the real import fixtures" and usable
 * from here without new machinery. Windchill also runs in-process, but its containment is
 * `containerless` (`AccessContainment.windchill`) — a Windchill document never appears in the
 * Unassigned queue at all, so it cannot exercise the "assign" step this test is about. A JIRA
 * project can, because `AccessContainment.jira` is a real container.
 *
 * The export is the committed `docs/JIRA.json` sample — the same file `JiraIssueImportTest` reads —
 * scoped to one project so the "every row" half of the assertion is a small, known number.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class AccessAcceptanceTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @Test
    fun `an access manager takes a freshly imported project from invisible to visible, using only the Access API`() =
        runBlocking {
            val issues = issuesIn(PROJECT)
            assertTrue(issues.isNotEmpty(), "the fixture no longer carries $PROJECT; this test would pass vacuously")

            // -- seed: a real import, not hand Cypher -------------------------------------------
            val writer = JiraGraphWriter(graphDriver, HOST)
            val settings = JiraSettings(
                host = HOST, token = "t", deployment = JiraDeployment.DATA_CENTER, pageSize = 100, maxRetries = 0,
            )
            val importer = JiraImporter(settings, JiraHttpClient(settings, stubJira(issues)), writer)
            importer.run(RecordingContext())

            val accessResolver = AccessResolver(graphDriver)
            val service = AccessAdminService(graphDriver, accessResolver)
            val jiraIssues = JiraIssuesProjection(graphDriver, HOST)
            val groupKey = "/SEC/AcceptanceReviewer"

            // -- a freshly imported project lands invisible by the ordinary default (§8.3) ------
            // resolve() also registers the group in the graph (AccessResolver's own MERGE), which
            // is what lets saveGrants find it below without a hand-written seed of its own.
            val beforeAccess = accessResolver.resolve(listOf(groupKey))
            assertTrue(beforeAccess.categoryIds.isEmpty() && !beforeAccess.seesAll)
            assertEquals(0, projectVisibility(jiraIssues, beforeAccess).total, "nothing is granted yet")

            // -- create category, grant it to the caller's group --------------------------------
            val category = assertIs<CreateCategoryOutcome.Created>(
                service.createCategory("jira-acceptance", "Acceptance", "", everyGroup = false, user = "test-admin"),
            ).category
            service.saveGrants(groupKey, listOf(category.metaId), user = "test-admin")

            val grantedAccess = accessResolver.resolve(listOf(groupKey))
            assertEquals(
                listOf(category.metaId), grantedAccess.categoryIds,
                "the grant resolves immediately, no restart needed",
            )
            assertEquals(
                0, projectVisibility(jiraIssues, grantedAccess).total,
                "granted, but the project has not been assigned the category yet",
            )

            // -- assign, from the Unassigned queue, the way the screen itself works -------------
            val unassigned = service.listUnassignedContainers(source = "jira", q = null)
            val container = unassigned.single()
            service.saveContainerCategories(container.containerId, listOf(category.metaId), user = "test-admin")

            assertEquals(
                0, projectVisibility(jiraIssues, grantedAccess).total,
                "the project itself carries the category now, but reconcile has not propagated it to its issues",
            )

            // -- reconcile: the confirm button's own follow-up action (session 34's step 10 note) --
            AccessReconciler(graphDriver).reconcile(AccessContainment.all.single { it.name == "jira.issues" })

            // -- zero rows to every row, with the SAME already-resolved access set --------------
            val after = projectVisibility(jiraIssues, grantedAccess)
            assertEquals(issues.size, after.total, "every issue in the project is now visible")
            assertEquals(issues.size, after.rows.size)
        }

    // -- harness --------------------------------------------------------------------------------

    private suspend fun projectVisibility(projection: JiraIssuesProjection, access: AccessSet) =
        projection.listIssues(
            page = 0,
            size = PAGE_SIZE,
            sort = JiraIssuesProjection.SortField.KEY,
            direction = JiraIssuesProjection.SortDirection.ASC,
            access = access,
        )

    private fun issuesIn(vararg keys: String): List<JsonObject> =
        (jiraJson.parseToJsonElement(sample(SEARCH)).jsonObject["issues"] as JsonArray)
            .map { it.jsonObject }
            .filter { it["fields"]!!.jsonObject["project"]?.jsonObject?.get("key")?.jsonPrimitive?.content in keys }

    private fun stubJira(issues: List<JsonObject>) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/myself") -> respondJson(
                """{"name":"tester","key":"tester","displayName":"Tester","timeZone":"Europe/Berlin"}""",
            )
            path.endsWith("/issuetype") -> respondJson(sample(ISSUE_TYPES))
            path.endsWith("/field") -> respondJson(sample(FIELDS))
            else -> {
                val startAt = request.url.parameters["startAt"]?.toIntOrNull() ?: 0
                respondJson(
                    """{"startAt":$startAt,"maxResults":${issues.size},"total":${issues.size},""" +
                        """"issues":${JsonArray(issues.drop(startAt))}}""",
                )
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun sample(name: String): String {
        val path: Path = Path.of("..", "docs", name)
        assertTrue(path.exists(), "sample export $name is missing; this test would pass vacuously.")
        return path.readText()
    }

    /** The JIRA importer is self-driving (no upload), so a run of it never carries a request. */
    private class RecordingContext : ImportContext {
        override val runId: String = "test-run"
        override val request: ImportRequest? = null
        override suspend fun phase(phaseId: String) = Unit
        override suspend fun progress(current: Int, total: Int) = Unit
        override suspend fun log(message: String, level: ImportLogLevel) = Unit
        override suspend fun warn(message: String) = Unit
        override suspend fun count(name: String, delta: Long) = Unit
        override suspend fun setCount(name: String, value: Long) = Unit
        override suspend fun params(params: Map<String, String>) = Unit
        override suspend fun ensureActive() = Unit
    }

    private companion object {
        const val HOST = "https://jira.example.com"
        const val SEARCH = "JIRA.json"
        const val FIELDS = "JIRA_FIELDS.json"
        const val ISSUE_TYPES = "JIRA_ISSUE_TYPES_DTO_EXAMPLE.md"

        /** The project most tests in `JiraIssueImportTest` also scope to. Nine issues carry it. */
        const val PROJECT = "ProjectCRPT"

        /** Comfortably larger than any one project in the fixture, so "every row" fits one page. */
        const val PAGE_SIZE = 1_000
    }
}
