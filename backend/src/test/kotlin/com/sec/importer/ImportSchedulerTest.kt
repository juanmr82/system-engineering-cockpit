package com.sec.importer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ImportScheduler]'s lifecycle rules (ADR 0018), without a database.
 *
 * Same shape as `ImportRunServiceTest`: a `RecordingStore` in place of Neo4j, a tiny [ImportJob]
 * whose body is whatever the test needs, and polling with a timeout rather than a signal — the
 * thing under test is what a real clock and a real coroutine loop do, not a hook into either.
 */
class ImportSchedulerTest {

    @Test
    fun `nextRunAt reports a sane value before the first tick`() {
        val service = ImportRunService(RecordingStore())
        val interval = Duration.ofMinutes(60)
        val before = Instant.now()

        val scheduler = ImportScheduler(IMPORTER, interval, service)

        val next = scheduler.nextRunAt()
        assertTrue(
            next.isAfter(before.plus(interval).minusSeconds(5)) &&
                next.isBefore(before.plus(interval).plusSeconds(5)),
            "expected nextRunAt near now+interval, was $next",
        )
        service.close()
    }

    @Test
    fun `a tick starts a run after the interval elapses`() = runBlocking {
        val runCount = AtomicInteger(0)
        val service = ImportRunService(RecordingStore())
        service.register(TestJob { runCount.incrementAndGet() })

        val scheduler = ImportScheduler(IMPORTER, Duration.ofMillis(TICK_MS), service)
        scheduler.start()

        withTimeout(TIMEOUT_MS) {
            while (runCount.get() == 0) delay(5)
        }

        scheduler.close()
        service.close()
    }

    @Test
    fun `close stops the loop before it ever ticks`() = runBlocking {
        val runCount = AtomicInteger(0)
        val service = ImportRunService(RecordingStore())
        service.register(TestJob { runCount.incrementAndGet() })

        val scheduler = ImportScheduler(IMPORTER, Duration.ofMillis(TICK_MS), service)
        scheduler.start()
        scheduler.close()

        delay(TICK_MS * 3)
        assertEquals(0, runCount.get(), "a closed scheduler still started a run")
        service.close()
    }

    /**
     * The case [ImportRunService.start] exists to make harmless: a tick landing while a run from
     * elsewhere is still going returns `AlreadyRunning` rather than throwing, so the loop must carry
     * on and start the next one once that run finishes — never die silently on the no-op tick.
     */
    @Test
    fun `a tick that lands on an already-running import does not stop the scheduler`() = runBlocking {
        val runCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val service = ImportRunService(RecordingStore())
        service.register(
            TestJob {
                val n = runCount.incrementAndGet()
                if (n == 1) gate.await()
            },
        )

        // Run 1, started directly, held open by the gate — exactly what a manual "run now" click
        // looks like from the scheduler's point of view.
        val started = service.start(IMPORTER)
        assertTrue(started is StartResult.Started, "run 1 did not start: $started")

        val scheduler = ImportScheduler(IMPORTER, Duration.ofMillis(TICK_MS), service)
        scheduler.start()

        // Long enough for at least one tick to land on the still-running run 1.
        delay(TICK_MS * 3)
        assertEquals(1, runCount.get(), "a tick started a second run while the first was still going")

        gate.complete(Unit)

        // The next tick, after run 1 finished, must still fire — proving the earlier no-op tick
        // never threw out of the loop.
        withTimeout(TIMEOUT_MS) {
            while (runCount.get() < 2) delay(5)
        }

        scheduler.close()
        service.close()
    }

    private class TestJob(private val body: suspend () -> Unit) : ImportJob {
        override val importerId: String = IMPORTER
        override val displayName: String = "Test"
        override val phases: List<ImportPhase> = emptyList()
        override suspend fun run(context: ImportContext) = body()
    }

    private class RecordingStore : ImportRunStore {
        private val saved = mutableListOf<ImportRun>()
        override suspend fun save(run: ImportRun) { saved += run }
        override suspend fun load(runId: String): ImportRun? = saved.lastOrNull { it.runId == runId }
        override suspend fun history(importerId: String?, limit: Int): List<ImportRun> =
            saved.filter { importerId == null || it.importerId == importerId }.takeLast(limit)
        override suspend fun prune(importerId: String, keep: Int) { }
    }

    private companion object {
        const val IMPORTER = "test-importer"
        const val TICK_MS = 30L
        const val TIMEOUT_MS = 5_000L
    }
}
