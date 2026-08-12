package com.sec.api.routes

import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase
import com.sec.importer.ImportRun
import com.sec.importer.ImportRunService
import com.sec.importer.ImportRunStore
import com.sec.importer.StartResult
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import framework's HTTP surface (spec §11.4).
 *
 * The lifecycle rules are tested at the service in `ImportRunServiceTest`; what is tested here is
 * the part only HTTP can get wrong — the status codes, the SSE framing, and the guarantee that a
 * stream **always ends with `event: status` and then closes**. That last one is what lets a client
 * tell a finished import from a dropped connection, and it is not visible from either side alone.
 */
class ImportRoutesTest {

    // -- starting ---------------------------------------------------------------------------------

    @Test
    fun `starting an importer answers 202 with the run id`() = testApplication {
        val service = serviceWith(FinishingJob())
        app(service)

        val response = client.post("/api/v1/import/test/runs")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("\"runId\":\"run-"), response.bodyAsText())
    }

    @Test
    fun `starting an importer this server does not have is a 404, not a conflict`() = testApplication {
        app(serviceWith(FinishingJob()))

        val response = client.post("/api/v1/import/windchill/runs")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /**
     * `409` **naming the active run**, because a bare refusal leaves the console with nothing to do
     * with the click: the user asked to see an import, and one is happening.
     */
    @Test
    fun `a second start while one is running answers 409 and names the run`() = testApplication {
        val gate = CompletableDeferred<Unit>()
        val service = serviceWith(GatedJob(gate))
        app(service)

        val first = Json.parseToJsonElement(client.post("/api/v1/import/test/runs").bodyAsText())
        val second = client.post("/api/v1/import/test/runs")

        assertEquals(HttpStatusCode.Conflict, second.status)
        val runId = first.toString().substringAfter("\"runId\":\"").substringBefore('"')
        assertTrue(second.bodyAsText().contains(runId), second.bodyAsText())

        gate.complete(Unit)
        service.awaitIdle()
    }

    // -- reading ----------------------------------------------------------------------------------

    @Test
    fun `the importer list carries the declared phases so the console can draw a stepper`() =
        testApplication {
            app(serviceWith(FinishingJob()))

            val body = client.get("/api/v1/import/importers").bodyAsText()

            assertTrue(body.contains("\"importerId\":\"test\""), body)
            assertTrue(body.contains("\"label\":\"One\""), body)
            assertTrue(body.contains("\"weight\":2"), body)
        }

    @Test
    fun `the run resource reports the finished run`() = testApplication {
        val service = serviceWith(FinishingJob())
        app(service)

        val runId = service.startAndFinish()
        val body = client.get("/api/v1/import/runs/$runId").bodyAsText()

        assertTrue(body.contains("\"status\":\"SUCCEEDED\""), body)
        assertTrue(body.contains("\"percent\":100"), body)
        assertTrue(body.contains("\"counters\":{\"things\":3}"), body)
    }

    @Test
    fun `an unknown run is a 404`() = testApplication {
        app(serviceWith(FinishingJob()))

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/import/runs/run-does-not-exist").status,
        )
    }

    @Test
    fun `history is newest first and honours the limit`() = testApplication {
        val service = serviceWith(FinishingJob())
        app(service)

        service.startAndFinish()
        service.startAndFinish()

        val body = client.get("/api/v1/import/runs?importerId=test&limit=1").bodyAsText()

        assertEquals(1, body.split("\"runId\"").size - 1, body)
    }

    // -- cancelling -------------------------------------------------------------------------------

    @Test
    fun `cancelling an active run answers 202`() = testApplication {
        val gate = CompletableDeferred<Unit>()
        val service = serviceWith(GatedJob(gate))
        app(service)

        val started = client.post("/api/v1/import/test/runs").bodyAsText()
        val runId = started.substringAfter("\"runId\":\"").substringBefore('"')

        // 202, not 200: cancellation is a request. Phases stop at their next checkpoint and
        // whatever they already committed stays committed.
        assertEquals(HttpStatusCode.Accepted, client.delete("/api/v1/import/runs/$runId").status)
        service.awaitIdle()
    }

    @Test
    fun `cancelling a run that is over is a 404`() = testApplication {
        val service = serviceWith(FinishingJob())
        app(service)

        val runId = service.startAndFinish()

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/import/runs/$runId").status)
    }

    // -- the stream --------------------------------------------------------------------------------

    /**
     * The late-join case, and the one that would hang forever if it were wrong.
     *
     * A run that finished before the client connected has no live stream. The handler must answer
     * with its terminal state and close, rather than holding a connection open on a run that will
     * never speak again.
     */
    @Test
    fun `a stream opened after the run finished sends status and closes`() = testApplication {
        val service = serviceWith(FinishingJob())
        app(service)

        val runId = service.startAndFinish()
        val body = withTimeout(TIMEOUT_MS) {
            client.get("/api/v1/import/runs/$runId/events").bodyAsText()
        }

        assertTrue(body.contains("event: status"), body)
        assertTrue(body.contains("\"status\":\"SUCCEEDED\""), body)
    }

    /**
     * A live stream, encoded.
     *
     * The gate is released 300 ms after the request goes out, which is the one timing assumption in
     * this file: the subscription registers in microseconds, so the margin is four orders of
     * magnitude. It buys determinism for the thing actually being checked — that the frames carry
     * the right `event:` names and that `status` is last.
     */
    @Test
    fun `a live stream carries named events and ends with status`() = testApplication {
        val gate = CompletableDeferred<Unit>()
        val service = serviceWith(GatedJob(gate))
        app(service)

        val runId = service.startOrFail()

        val body = coroutineScope {
            launch {
                delay(300)
                gate.complete(Unit)
            }
            withTimeout(TIMEOUT_MS) {
                client.get("/api/v1/import/runs/$runId/events").bodyAsText()
            }
        }

        assertTrue(body.contains("event: log"), body)
        assertTrue(body.contains("event: status"), body)
        assertTrue(
            body.trimEnd().substringAfterLast("event: ").startsWith("status"),
            "status was not the last event:\n$body",
        )
    }

    @Test
    fun `a stream for a run nobody has heard of closes instead of hanging`() = testApplication {
        app(serviceWith(FinishingJob()))

        val body = withTimeout(TIMEOUT_MS) {
            client.get("/api/v1/import/runs/run-nope/events").bodyAsText()
        }

        assertTrue(body.isBlank() || !body.contains("event: log"), body)
    }

    // -- harness -----------------------------------------------------------------------------------

    private fun ApplicationTestBuilder.app(service: ImportRunService) {
        application { importTestModule(service) }
    }

    private fun serviceWith(job: ImportJob) = ImportRunService(MemoryStore()).apply { register(job) }

    private suspend fun ImportRunService.startOrFail(): String {
        val result = start(IMPORTER)
        assertTrue(result is StartResult.Started, "start refused: $result")
        return result.runId
    }

    private suspend fun ImportRunService.startAndFinish(): String {
        val runId = startOrFail()
        withTimeout(TIMEOUT_MS) {
            while (run(runId)?.status?.isFinished != true) delay(5)
        }
        return runId
    }

    private suspend fun ImportRunService.awaitIdle() {
        withTimeout(TIMEOUT_MS) {
            while (activeRunId(IMPORTER) != null) delay(5)
        }
    }

    // -- doubles ------------------------------------------------------------------------------------

    /** Runs straight through, so a test can have a finished run without waiting on anything. */
    private class FinishingJob : ImportJob {
        override val importerId = IMPORTER
        override val displayName = "Test importer"
        override val phases = listOf(ImportPhase("one", "One", weight = 2))

        override suspend fun run(context: ImportContext) {
            context.phase("one")
            context.count("things", 3)
            context.progress(1, 1)
        }
    }

    /** Parks until the test lets it go, so there is something for a live stream to carry. */
    private class GatedJob(private val gate: CompletableDeferred<Unit>) : ImportJob {
        override val importerId = IMPORTER
        override val displayName = "Test importer"
        override val phases = listOf(ImportPhase("one", "One", weight = 2))

        override suspend fun run(context: ImportContext) {
            context.phase("one")
            gate.await()
            context.log("the gate opened")
            context.progress(1, 1)
        }
    }

    private class MemoryStore : ImportRunStore {
        private val runs = ConcurrentHashMap<String, ImportRun>()

        override suspend fun save(run: ImportRun) { runs[run.runId] = run }
        override suspend fun load(runId: String): ImportRun? = runs[runId]

        override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
            runs.values
                .filter { importerId == null || it.importerId == importerId }
                .sortedByDescending { it.startedAt }
                .take(limit)

        override suspend fun prune(importerId: String, keep: Int) {
            runs.values
                .filter { it.importerId == importerId && it.status.isFinished }
                .sortedByDescending { it.startedAt }
                .drop(keep)
                .forEach { runs.remove(it.runId) }
        }
    }

    private companion object {
        const val IMPORTER = "test"
        const val TIMEOUT_MS = 15_000L
    }
}

/**
 * The module under test, as a top-level function.
 *
 * Not inlined into `application { }`: inside that block both `Application` and
 * `ApplicationTestBuilder` are receivers and both declare `install`, so the call is ambiguous. An
 * extension on `Application` says which one is meant.
 *
 * The routes are mounted directly rather than through `configureApp` — nothing here needs a graph
 * driver, and a test that built one would be exercising the wiring rather than the routes.
 */
private fun Application.importTestModule(service: ImportRunService) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(SSE)
    routing { importRoutes(service) }
}
