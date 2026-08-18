package com.sec.api.routes

import com.sec.config.Neo4jSettings
import com.sec.security.AccessResolver
import com.sec.graph.GraphDriver
import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase
import com.sec.importer.ImportRun
import com.sec.importer.ImportRunService
import com.sec.importer.ImportRunStore
import com.sec.security.AccessSet
import com.sec.source.doors.DoorsExport
import com.sec.source.doors.DoorsImportGate
import com.sec.source.doors.DoorsImporter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DOORS upload endpoint — what is tested here is what only HTTP can get wrong, the same scope
 * `WindchillRoutesTest` has: **a file that will not import never becomes a run.**
 *
 * Deliberately scoped to the parse-failure paths. The gate (does the module exist, is it visible,
 * has this checksum already been imported) needs a real `AccessSet` — `call.accessSet` throws
 * without one (`Principal.kt`), and producing one needs the full session plugin — and a real graph
 * to answer against, so that behaviour has its own coverage in `DoorsImportTest` (`@Tag("docker")`)
 * instead of a hand-rolled auth harness here. What *is* tested without either: a broken upload is
 * refused at the door before a run, a gateway, or an importer is ever consulted.
 */
class DoorsRoutesTest {

    @Test
    fun `a file that is not JSON is a 400 and never becomes a run`() = testApplication {
        val service = app()

        val response = client.post("/api/v1/doors/import") {
            contentType(ContentType.Application.Json)
            setBody("not json at all")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("not valid JSON"), response.bodyAsText())
        assertEquals(0, service.history(DoorsImporter.ID, 10).size)
    }

    @Test
    fun `a JSON array at the top level is a 400 naming what an export looks like`() = testApplication {
        app()

        val response = client.post("/api/v1/doors/import") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("DOORS export"), response.bodyAsText())
    }

    @Test
    fun `a module missing a required key is a 400 naming it`() = testApplication {
        val service = app()

        val response = client.post("/api/v1/doors/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"__name":"x","__version":"current","url":"doors://x-M-1","__contents":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("__objectId"), response.bodyAsText())
        assertEquals(0, service.history(DoorsImporter.ID, 10).size)
    }

    // The payload-too-large guard is a plain size comparison ahead of the parser and is not
    // exercised here — a test proving it would have to allocate and POST a 128 MB body, which
    // costs far more than the branch is worth confirming in a fast unit test.

    // -- the harness --------------------------------------------------------------------------

    /** Never reached in these tests — every case here fails before the route asks the gateway
     *  anything — but the route still requires one to be wired. */
    private val unusedGateway = DoorsModuleGateway { _, _ ->
        error("not reached — every case here fails before the gate is consulted")
    }

    private fun ApplicationTestBuilder.app(job: ImportJob = RecordingJob()): ImportRunService {
        val service = ImportRunService(MemoryStore()).apply { register(job) }
        application { doorsTestModule(service) }
        return service
    }

    private fun Application.doorsTestModule(service: ImportRunService) {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        routing {
            doorsRoutes(
                unusedGateway,
                service,
                // Never asked to resolve anything in these tests — no case here reaches
                // call.accessSet(); constructed only because the route signature needs one.
                AccessResolver(GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test"))),
            )
        }
    }

    private open class RecordingJob : ImportJob {
        var received: DoorsExport? = null

        override val importerId = DoorsImporter.ID
        override val displayName = "DOORS"
        override val phases = listOf(ImportPhase("one", "One", weight = 1))

        override suspend fun run(context: ImportContext) {
            received = context.request as? DoorsExport
            context.phase("one")
            context.progress(1, 1)
        }
    }

    private class MemoryStore : ImportRunStore {
        private val runs = ConcurrentHashMap<String, ImportRun>()

        override suspend fun save(run: ImportRun) {
            runs[run.runId] = run
        }

        override suspend fun load(runId: String): ImportRun? = runs[runId]

        override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
            runs.values
                .filter { importerId == null || it.importerId == importerId }
                .sortedByDescending { it.startedAt }
                .take(limit)

        override suspend fun prune(importerId: String, keep: Int) = Unit
    }
}
