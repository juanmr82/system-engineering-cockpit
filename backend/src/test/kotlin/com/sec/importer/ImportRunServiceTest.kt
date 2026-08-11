package com.sec.importer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import framework's lifecycle rules (`docs/JIRA_ISSUES_FEATURE_SPEC.md` §11), without a
 * database and without HTTP.
 *
 * This is where the framework's real complexity is, and none of it is about Neo4j: one run at a
 * time per importer, a phase sequence, a throttle, a cancel that leaves committed work committed,
 * and a failure that becomes a sentence rather than a stack trace. Container tests are excluded
 * from `mvn verify` on purpose (CLAUDE.md §11), so rules that could only be checked with Docker
 * running would be rules that go unchecked on most machines — which is why [ImportRunStore] is an
 * interface and why [RecordingStore] below exists.
 */
class ImportRunServiceTest {

    // -- the happy path ---------------------------------------------------------------------------

    @Test
    fun `a run walks its phases in order and finishes SUCCEEDED`() = runBlocking {
        val store = RecordingStore()
        val service = ImportRunService(store)
        service.register(
            TestJob(phases = threePhases) { context ->
                context.phase("one")
                context.phase("two")
                context.phase("three")
            },
        )

        val runId = service.startOrFail()
        val run = service.awaitFinish(runId)

        assertEquals(ImportStatus.SUCCEEDED, run.status)
        assertEquals("three", run.phase)
        assertNotNull(run.finishedAt)
        assertNull(run.error)
        service.close()
    }

    /**
     * The run resource is the late-join source of truth (spec §11.4), so it has to carry the phase
     * list — a client that arrives mid-run reads this and draws the whole stepper from it, rather
     * than asking the stream to replay what it missed.
     */
    @Test
    fun `a live run resource carries the declared phases`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val service = ImportRunService(RecordingStore())
        service.register(TestJob(phases = threePhases) { gate.await() })

        val runId = service.startOrFail()
        val live = assertNotNull(service.run(runId))

        assertEquals(listOf("one", "two", "three"), live.phases.map { it.id })
        assertEquals(listOf(2, 3, 5), live.phases.map { it.weight })

        gate.complete(Unit)
        service.awaitFinish(runId)
        service.close()
    }

    /**
     * The same, for a run read back **from the store** — and this is the case that was broken.
     *
     * Phases are not persisted on purpose (a stored copy can disagree with the importer after a
     * release renames one), which is only safe if something puts them back on the way out. The
     * first live import against a real instance rendered its finished run with an empty stepper and
     * a null percentage, because nothing did.
     */
    @Test
    fun `a finished run read from the store still carries its phases and reads as complete`() =
        runBlocking {
            val service = ImportRunService(RecordingStore())
            service.register(
                TestJob(phases = threePhases) { context ->
                    context.phase("one")
                    context.phase("two")
                },
            )

            val runId = service.startOrFail()
            service.awaitFinish(runId)
            // Now definitely out of memory and answered by the store.
            val stored = assertNotNull(service.run(runId))

            assertEquals(listOf("one", "two", "three"), stored.phases.map { it.id })
            // 100, not the 20 % the run's last phase started at: a completed import drawn as a
            // fifth done is the one reading a progress bar must never support.
            assertEquals(100, stored.percent)
            assertEquals(100, service.history(IMPORTER, 10).single().percent)
            service.close()
        }

    /** A run that stopped keeps the real number: where it stopped is what a reader wants. */
    @Test
    fun `a failed run reports the percentage it reached, not a hundred`() = runBlocking {
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob(phases = threePhases) { context ->
                context.phase("one")
                context.progress(1, 1)
                context.phase("two")
                error("JIRA stopped answering")
            },
        )

        val run = service.awaitFinish(service.startOrFail())

        assertEquals(ImportStatus.FAILED, run.status)
        assertEquals(20, run.percent)
        service.close()
    }

    // -- one at a time, per importer ---------------------------------------------------------------

    @Test
    fun `a second start while one is running names the run that is already going`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val service = ImportRunService(RecordingStore())
        service.register(TestJob(phases = onePhase) { gate.await() })

        val first = service.startOrFail()
        val second = service.start(IMPORTER)

        assertTrue(second is StartResult.AlreadyRunning, "a concurrent start was allowed: $second")
        // The id matters as much as the refusal: the console's right reaction is to open that run,
        // because an import is what the user asked for and one is happening.
        assertEquals(first, (second as StartResult.AlreadyRunning).runId)

        gate.complete(Unit)
        service.awaitFinish(first)
        service.close()
    }

    @Test
    fun `the same importer can be started again once the first run is over`() = runBlocking {
        val service = ImportRunService(RecordingStore())
        service.register(TestJob(phases = onePhase) { it.phase("one") })

        val first = service.startOrFail()
        service.awaitFinish(first)
        val second = service.startOrFail()

        assertTrue(first != second, "the second run reused the first run's id")
        service.awaitFinish(second)
        service.close()
    }

    /**
     * The reason the lock is per importer id and not global (spec §11.1). DOORS and JIRA touch
     * different labels; serialising them would be a limit with no cause.
     */
    @Test
    fun `two importers run at the same time`() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        val started = CopyOnWriteArrayList<String>()
        val service = ImportRunService(RecordingStore())

        listOf("alpha", "beta").forEach { id ->
            service.register(
                TestJob(importerId = id, phases = onePhase) {
                    started += id
                    if (started.size == 2) bothStarted.complete(Unit)
                    bothStarted.await()
                },
            )
        }

        val alpha = (service.start("alpha") as StartResult.Started).runId
        val beta = (service.start("beta") as StartResult.Started).runId

        withTimeout(TIMEOUT_MS) { bothStarted.await() }
        assertEquals(ImportStatus.SUCCEEDED, service.awaitFinish(alpha).status)
        assertEquals(ImportStatus.SUCCEEDED, service.awaitFinish(beta).status)
        service.close()
    }

    @Test
    fun `starting an importer nobody registered is not a conflict`() = runBlocking {
        val service = ImportRunService(RecordingStore())

        assertEquals(StartResult.UnknownImporter, service.start("windchill"))
        service.close()
    }

    // -- outcomes ---------------------------------------------------------------------------------

    @Test
    fun `one warning turns a success into SUCCEEDED_WITH_WARNINGS`() = runBlocking {
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob(phases = onePhase) {
                it.phase("one")
                it.warn("two field names collide")
            },
        )

        val run = service.awaitFinish(service.startOrFail())

        assertEquals(ImportStatus.SUCCEEDED_WITH_WARNINGS, run.status)
        assertEquals(listOf("two field names collide"), run.warnings)
        service.close()
    }

    /**
     * A failure becomes one line: the message, and the class that produced it.
     *
     * The class is there because a message alone is often blank or a bare file name. The stack
     * trace is **not** there, and this asserts its absence: the run record is read by people who
     * cannot act on a trace, and `:__ImportRun` is not a log (spec §11.2).
     */
    @Test
    fun `a thrown exception ends the run FAILED with a sentence, never a stack trace`() = runBlocking {
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob(phases = onePhase) {
                it.phase("one")
                error("JIRA rejected the token")
            },
        )

        val run = service.awaitFinish(service.startOrFail())

        assertEquals(ImportStatus.FAILED, run.status)
        assertEquals("JIRA rejected the token (IllegalStateException)", run.error)
        assertTrue(run.error?.contains("\n") != true, "a stack trace reached the run record")
        service.close()
    }

    @Test
    fun `an undeclared phase fails the run rather than emitting a step nothing can place`() =
        runBlocking {
            val service = ImportRunService(RecordingStore())
            service.register(TestJob(phases = onePhase) { it.phase("nowhere") })

            val run = service.awaitFinish(service.startOrFail())

            assertEquals(ImportStatus.FAILED, run.status)
            assertTrue(
                run.error?.contains("nowhere") == true,
                "the failure does not name the phase: ${run.error}",
            )
            service.close()
        }

    @Test
    fun `cancelling ends the run CANCELLED`() = runBlocking {
        val running = CompletableDeferred<Unit>()
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob(phases = onePhase) { context ->
                context.phase("one")
                running.complete(Unit)
                // Cancellation-cooperative, exactly as ImportJob.run requires: without an
                // ensureActive between units of work, a cancel is a request nothing reads.
                repeat(1_000) {
                    delay(5)
                    context.ensureActive()
                }
            },
        )

        val runId = service.startOrFail()
        withTimeout(TIMEOUT_MS) { running.await() }
        assertTrue(service.cancel(runId), "cancel found no active run")

        assertEquals(ImportStatus.CANCELLED, service.awaitFinish(runId).status)
        service.close()
    }

    @Test
    fun `cancelling a run that has already finished reports that it is not running`() = runBlocking {
        val service = ImportRunService(RecordingStore())
        service.register(TestJob(phases = onePhase) { it.phase("one") })

        val runId = service.startOrFail()
        service.awaitFinish(runId)

        assertTrue(!service.cancel(runId))
        service.close()
    }

    // -- the event stream --------------------------------------------------------------------------

    @Test
    fun `the stream carries phase, log and counter events and ends with exactly one status`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val service = ImportRunService(RecordingStore())
            service.register(
                TestJob(phases = onePhase) { context ->
                    gate.await()
                    context.phase("one")
                    context.log("reading the catalogue")
                    context.count("fieldsSeen", 1_171)
                    context.progress(1, 1)
                },
            )

            val runId = service.startOrFail()
            val events = subscribe(service, runId, gate)

            assertEquals(1, events.count { it is ImportEvent.Phase })
            assertTrue(events.any { it is ImportEvent.Log })
            assertEquals(
                1_171L,
                events.filterIsInstance<ImportEvent.Counters>().last().counters["fieldsSeen"],
            )
            // Exactly one, and last. A stream that ends without one is a dropped connection, and
            // that distinction is only available because this is guaranteed.
            assertEquals(1, events.count { it is ImportEvent.Status })
            assertTrue(events.last() is ImportEvent.Status)
            service.close()
        }

    /**
     * At most four progress events a second — and **the completing one is never among the
     * casualties**.
     *
     * A bar frozen at 96 % on a phase that finished reads as a hung import, which is the failure
     * the throttle would otherwise introduce while fixing a different one.
     */
    @Test
    fun `progress is throttled, but the value that completes a phase always gets through`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val service = ImportRunService(RecordingStore())
            service.register(
                TestJob(phases = onePhase) { context ->
                    gate.await()
                    context.phase("one")
                    (1..50).forEach { context.progress(it, 50) }
                },
            )

            val runId = service.startOrFail()
            val progress = subscribe(service, runId, gate).filterIsInstance<ImportEvent.Progress>()

            assertTrue(
                progress.size < 10,
                "50 progress calls produced ${progress.size} events; the throttle is not working",
            )
            assertEquals(50, progress.last().current, "the completing value was thrown away")
            assertEquals(100, progress.last().percent)
            service.close()
        }

    @Test
    fun `the aggregate percentage is weighted, not one phase per equal share`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob(phases = threePhases) { context ->
                gate.await()
                // Weights 2 / 3 / 5. Finishing the first two is 50 %, not 66 %.
                context.phase("one")
                context.progress(1, 1)
                context.phase("two")
                context.progress(1, 1)
            },
        )

        val runId = service.startOrFail()
        val progress = subscribe(service, runId, gate).filterIsInstance<ImportEvent.Progress>()

        assertEquals(listOf(20, 50), progress.map { it.percent })
        service.close()
    }

    // -- caps and storage --------------------------------------------------------------------------

    @Test
    fun `warnings past the cap are counted, not listed`() = runBlocking {
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob(phases = onePhase) { context ->
                context.phase("one")
                repeat(ImportRun.WARNING_CAP + 7) { context.warn("warning $it") }
            },
        )

        val run = service.awaitFinish(service.startOrFail())

        assertEquals(ImportRun.WARNING_CAP + 1, run.warnings.size)
        // Not silently truncated: a shortened list that does not say so is read as a complete one.
        assertEquals("+7 more", run.warnings.last())
        service.close()
    }

    @Test
    fun `the run is written at the start, at every phase boundary and at the end`() = runBlocking {
        val store = RecordingStore()
        val service = ImportRunService(store)
        service.register(
            TestJob(phases = threePhases) { context ->
                context.phase("one")
                context.phase("two")
                context.phase("three")
            },
        )

        service.awaitFinish(service.startOrFail())

        // start + three phases + finish. Mid-phase progress deliberately does not write: it happens
        // hundreds of times and the live stream already carries it.
        assertEquals(5, store.saved.size, "writes: ${store.saved.map { it.status to it.phase }}")
        assertEquals(ImportStatus.QUEUED, store.saved.first().status)
        assertEquals(ImportStatus.SUCCEEDED, store.saved.last().status)
        service.close()
    }

    @Test
    fun `finishing prunes the history to the configured limit`() = runBlocking {
        val store = RecordingStore()
        val service = ImportRunService(store, historyLimit = 7)
        service.register(TestJob(phases = onePhase) { it.phase("one") })

        service.awaitFinish(service.startOrFail())

        assertEquals(listOf(IMPORTER to 7), store.pruned)
        service.close()
    }

    /**
     * A storage failure must cost the record of the run, never the run.
     *
     * Asserted over the *stream* rather than the run resource, deliberately: with a store that
     * cannot answer, the resource is exactly what is unavailable once the run leaves memory, and
     * asking it would test the fallback rather than the outcome.
     */
    @Test
    fun `a store that throws does not fail the import`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val service = ImportRunService(ExplodingStore())
        service.register(
            TestJob(phases = onePhase) {
                gate.await()
                it.phase("one")
            },
        )

        val runId = service.startOrFail()
        val status = subscribe(service, runId, gate).filterIsInstance<ImportEvent.Status>().single()

        assertEquals(ImportStatus.SUCCEEDED, status.status)
        assertNull(status.error)
        service.close()
    }

    // -- the pure part -----------------------------------------------------------------------------

    @Test
    fun `percentComplete normalises by the declared weights`() {
        assertEquals(0, threePhases.percentComplete("one", 0.0))
        assertEquals(10, threePhases.percentComplete("one", 0.5))
        assertEquals(20, threePhases.percentComplete("two", 0.0))
        assertEquals(100, threePhases.percentComplete("three", 1.0))
    }

    /**
     * An unknown phase gets no number rather than a guess: a bar that is confidently wrong is worse
     * than a bar that is missing, because only one of them is visible as a defect.
     */
    @Test
    fun `percentComplete refuses to guess at a phase it does not know`() {
        assertNull(threePhases.percentComplete("nowhere", 0.5))
        assertNull(emptyList<ImportPhase>().percentComplete("one", 0.5))
    }

    @Test
    fun `the live log keeps the last lines and drops the oldest`() {
        val log = ImportRunLog(capacity = 3)
        (1..5).forEach { log.add(ImportLogLine(ImportLogLevel.INFO, "line $it", "now")) }

        assertEquals(listOf("line 3", "line 4", "line 5"), log.snapshot().map { it.message })
    }

    // -- helpers -------------------------------------------------------------------------------------

    private suspend fun ImportRunService.startOrFail(importerId: String = IMPORTER): String {
        val result = start(importerId)
        assertTrue(result is StartResult.Started, "start refused: $result")
        return result.runId
    }

    /**
     * Polls rather than awaiting a signal, because the thing being tested is what the *service*
     * reports — a helper that hooked into the run's own completion would be testing the hook.
     */
    private suspend fun ImportRunService.awaitFinish(runId: String): ImportRun =
        withTimeout(TIMEOUT_MS) {
            while (true) {
                run(runId)?.takeIf { it.status.isFinished }?.let { return@withTimeout it }
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /**
     * Subscribes, releases [gate], and collects every event up to and including `status`.
     *
     * The gate is what makes this deterministic: `start` launches the job immediately, so a test
     * that subscribed afterwards would race the first phase. `onSubscription` fires once the
     * subscription is registered, which is the only point at which releasing the job is safe — and
     * it is the same mechanism `ImportRoutes` uses to close the late-join race for real clients.
     */
    private suspend fun subscribe(
        service: ImportRunService,
        runId: String,
        gate: CompletableDeferred<Unit>,
    ): List<ImportEvent> = coroutineScope {
        val stream = assertNotNull(service.events(runId), "no event stream for $runId")
        val subscribed = CompletableDeferred<Unit>()

        val collected: Deferred<List<ImportEvent>> = async {
            stream.onSubscription { subscribed.complete(Unit) }
                .transformWhile { event ->
                    emit(event)
                    event !is ImportEvent.Status
                }
                .toList()
        }

        withTimeout(TIMEOUT_MS) {
            subscribed.await()
            gate.complete(Unit)
            collected.await()
        }
    }

    // -- doubles ---------------------------------------------------------------------------------

    private class TestJob(
        override val importerId: String = IMPORTER,
        override val phases: List<ImportPhase>,
        private val body: suspend (ImportContext) -> Unit,
    ) : ImportJob {
        override val displayName: String = importerId
        override suspend fun run(context: ImportContext) = body(context)
    }

    /** Remembers every write, which is how the "written at start, phase and end" rule is checked. */
    private class RecordingStore : ImportRunStore {
        val saved = CopyOnWriteArrayList<ImportRun>()
        val pruned = CopyOnWriteArrayList<Pair<String, Int>>()

        override suspend fun save(run: ImportRun) { saved += run }

        override suspend fun load(runId: String): ImportRun? = saved.lastOrNull { it.runId == runId }

        override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
            saved.reversed()
                .filter { importerId == null || it.importerId == importerId }
                .distinctBy { it.runId }
                .take(limit)

        override suspend fun prune(importerId: String, keep: Int) { pruned += importerId to keep }
    }

    private class ExplodingStore : ImportRunStore {
        override suspend fun save(run: ImportRun): Unit = error("the database is gone")
        override suspend fun load(runId: String): ImportRun = error("the database is gone")
        override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
            error("the database is gone")
        override suspend fun prune(importerId: String, keep: Int): Unit = error("the database is gone")
    }

    private companion object {
        const val IMPORTER = "test"
        const val TIMEOUT_MS = 10_000L

        val onePhase = listOf(ImportPhase("one", "One", weight = 1))

        /** Deliberately unequal weights, so a percentage that ignored them would be visible. */
        val threePhases = listOf(
            ImportPhase("one", "One", weight = 2),
            ImportPhase("two", "Two", weight = 3),
            ImportPhase("three", "Three", weight = 5),
        )
    }
}
