package com.sec.api.routes

import com.sec.config.Neo4jSettings
import com.sec.config.WindchillSettings
import com.sec.graph.GraphDriver
import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase
import com.sec.importer.ImportRun
import com.sec.importer.ImportRunService
import com.sec.importer.ImportRunStore
import com.sec.source.windchill.WindchillExport
import com.sec.source.windchill.WindchillImporter
import com.sec.source.windchill.WindchillProjection
import io.ktor.client.request.get
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Windchill upload endpoint.
 *
 * What is tested here is the part only HTTP can get wrong: which failures are refused at the door
 * and with which status. The parse itself has its own tests, and the writing has a container test —
 * this is about the promise that **a file that will not import never becomes a run**.
 *
 * The importer is a double, so no test here reaches a database. That is the point: the real
 * importer's first act is to apply schema, and a route test that needed Docker to prove a `400`
 * would be a route test nobody runs.
 */
class WindchillRoutesTest {

    // -- health -------------------------------------------------------------------------------

    @Test
    fun `health reports an unconfigured host without inventing one`() = testApplication {
        app(settings = WindchillSettings(host = ""))

        val body = client.get("/api/v1/windchill/health").bodyAsText()

        assertTrue(body.contains("\"configured\":false"), body)
        assertTrue(body.contains("\"host\":\"\""), body)
    }

    @Test
    fun `health reports the configured host, which is not a secret`() = testApplication {
        app(settings = WindchillSettings(host = "https://windchill.example.com/Windchill"))

        val body = client.get("/api/v1/windchill/health").bodyAsText()

        assertTrue(body.contains("\"configured\":true"), body)
        assertTrue(body.contains("windchill.example.com"), body)
    }

    // -- uploading ----------------------------------------------------------------------------

    @Test
    fun `a valid export is accepted and answers with the run to watch`() = testApplication {
        app()

        val response = client.post("/api/v1/windchill/import") {
            contentType(ContentType.Application.Json)
            setBody(TWO_DOCUMENTS)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"runId\":\"run-"), body)
        assertTrue(body.contains("\"documents\":2"), body)
    }

    /**
     * The upload says the file is a page **before** the run does.
     *
     * The run warns too, over the event stream; this is what lets the page say so at the moment the
     * user pressed the button, which is when they are still looking at it.
     */
    @Test
    fun `an export carrying a next link is accepted and reported as paged`() = testApplication {
        app()

        val body = client.post("/api/v1/windchill/import") {
            contentType(ContentType.Application.Json)
            setBody(PAGED_DOCUMENT)
        }.bodyAsText()

        assertTrue(body.contains("\"paged\":true"), body)
    }

    @Test
    fun `a file that is not JSON is a 400 and never becomes a run`() = testApplication {
        val service = app()

        val response = client.post("/api/v1/windchill/import") {
            contentType(ContentType.Application.Json)
            setBody("{'value': []}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("not valid JSON"), response.bodyAsText())
        assertEquals(0, service.history(WindchillImporter.ID, 10).size)
    }

    @Test
    fun `JSON that is not an export is a 400 naming what an export looks like`() = testApplication {
        app()

        val response = client.post("/api/v1/windchill/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"documents":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("'value' array"), response.bodyAsText())
    }

    /**
     * The refusal that matters most: an empty export would delete every document in the graph, and
     * an export that failed produces exactly this file.
     */
    @Test
    fun `an export with no documents is refused rather than run`() = testApplication {
        val service = app()

        val response = client.post("/api/v1/windchill/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"value":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("would remove every"), response.bodyAsText())
        assertEquals(0, service.history(WindchillImporter.ID, 10).size)
    }

    /** A second upload while one is running is a 409 naming the run, not a silent second import. */
    @Test
    fun `a second upload while an import is running is refused and names the active run`() =
        testApplication {
            val job = BlockingJob()
            app(job = job)

            val first = client.post("/api/v1/windchill/import") {
                contentType(ContentType.Application.Json)
                setBody(TWO_DOCUMENTS)
            }
            assertEquals(HttpStatusCode.Accepted, first.status)

            job.started.await()

            val second = client.post("/api/v1/windchill/import") {
                contentType(ContentType.Application.Json)
                setBody(TWO_DOCUMENTS)
            }

            assertEquals(HttpStatusCode.Conflict, second.status)
            assertTrue(second.bodyAsText().contains("run-"), second.bodyAsText())
            job.release.complete(Unit)
        }

    // -- the harness --------------------------------------------------------------------------

    private fun ApplicationTestBuilder.app(
        settings: WindchillSettings = WindchillSettings(host = ""),
        job: ImportJob = RecordingJob(),
    ): ImportRunService {
        val service = ImportRunService(MemoryStore()).apply { register(job) }
        application { windchillTestModule(settings, service) }
        return service
    }

    private fun Application.windchillTestModule(
        settings: WindchillSettings,
        service: ImportRunService,
    ) {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        routing {
            windchillRoutes(
                settings,
                // Constructed and never used: no test here reads documents, and the driver connects
                // lazily, so nothing reaches a query. The same arrangement `ApplicationTest` uses.
                WindchillProjection(
                    GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                    settings,
                ),
                service,
            )
        }
    }

    /** Stands in for the real importer so a route test never needs a database. */
    private open class RecordingJob : ImportJob {
        var received: WindchillExport? = null

        override val importerId = WindchillImporter.ID
        override val displayName = "Windchill"
        override val phases = listOf(ImportPhase("one", "One", weight = 1))

        override suspend fun run(context: ImportContext) {
            received = context.request as? WindchillExport
            context.phase("one")
            context.progress(1, 1)
        }
    }

    /** Holds the importer open so a second upload meets a run that is genuinely in flight. */
    private class BlockingJob : RecordingJob() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun run(context: ImportContext) {
            context.phase("one")
            started.complete(Unit)
            release.await()
        }
    }

    /** The run store, in memory: this test is about HTTP, not about persistence. */
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

    private companion object {
        val TWO_DOCUMENTS = """
            {"value":[
              {"@odata.id":"u/1","ID":"OR:1","FolderLocation":"/f","Name":"One","Number":"N-1","Version":"01 [1]","State":{"Value":"RELEASED","Display":"Released"}},
              {"@odata.id":"u/2","ID":"OR:2","FolderLocation":"/f","Name":"Two","Number":"N-1","Version":"02 [1]","State":{"Value":"RELEASED","Display":"Released"}}
            ]}
        """.trimIndent()

        val PAGED_DOCUMENT = """
            {"value":[{"@odata.id":"u/1","ID":"OR:1","Number":"N-1","Version":"01 [1]"}],
             "@odata.nextLink":"https://example.com/next?${'$'}skiptoken=2"}
        """.trimIndent()
    }
}
