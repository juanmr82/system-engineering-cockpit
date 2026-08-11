package com.sec.importer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/** What starting a run can produce. Sealed so a caller cannot forget the conflict case. */
public sealed interface StartResult {
    public data class Started(public val runId: String) : StartResult

    /**
     * One run at a time per importer (spec §11.1). Carries the **active** run's id rather than just
     * refusing, because the console's right response to this is to open that run's panel — the user
     * asked to see an import happen and one is happening.
     */
    public data class AlreadyRunning(public val runId: String) : StartResult

    public data object UnknownImporter : StartResult
}

/**
 * Runs imports, one at a time per importer, and tells everyone watching what is happening.
 *
 * ## Where a run lives
 *
 * On a scope owned by this service — `SupervisorJob` on `Dispatchers.IO` — and deliberately **not**
 * on the HTTP call that started it. A user who starts a 784-issue import and closes the tab has
 * started a 784-issue import; tying it to the request would make navigation a cancel button nobody
 * pressed. `SupervisorJob` so one importer failing cannot take another down with it.
 *
 * ## One at a time, per importer, not globally
 *
 * DOORS and JIRA must be able to run at once — they touch different labels and there is no reason
 * to serialise them. The [startLocks] map is one mutex per importer id, and it is held only across
 * the *check and register*, never across the run: a lock spanning a run is a lock that has to be
 * released by whichever coroutine happens to finish it, which is exactly where cancellation goes
 * wrong.
 */
public class ImportRunService(
    store: ImportRunStore,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
    private val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {

    // Every write here is best-effort: an import that succeeded and could not write its own
    // receipt is a successful import (see ForgivingImportRunStore).
    private val store: ImportRunStore = ForgivingImportRunStore(store)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("import-run"))

    private val jobs = ConcurrentHashMap<String, ImportJob>()
    private val startLocks = ConcurrentHashMap<String, Mutex>()
    private val activeByImporter = ConcurrentHashMap<String, ActiveRun>()
    private val activeByRunId = ConcurrentHashMap<String, ActiveRun>()

    /** Makes an importer startable. Called once per importer at wiring time. */
    public fun register(job: ImportJob) {
        jobs[job.importerId] = job
        logger.info { "Registered importer '${job.importerId}' with ${job.phases.size} phases" }
    }

    /** Every registered importer, for the console's list. */
    public fun importers(): List<ImportJob> = jobs.values.sortedBy { it.importerId }

    public fun importer(importerId: String): ImportJob? = jobs[importerId]

    /** The run this importer is executing right now, if any. Null is the ordinary state. */
    public fun activeRunId(importerId: String): String? = activeByImporter[importerId]?.runId

    /**
     * Starts [importerId], or reports that it is already running.
     *
     * Returns as soon as the run is registered — the work happens on this service's scope, and the
     * caller gets a run id to watch rather than a result to wait for.
     */
    public suspend fun start(importerId: String): StartResult {
        val job = jobs[importerId] ?: return StartResult.UnknownImporter

        val active = startLocks.computeIfAbsent(importerId) { Mutex() }.withLock {
            activeByImporter[importerId]?.let { return StartResult.AlreadyRunning(it.snapshot.runId) }

            ActiveRun(job).also {
                activeByImporter[importerId] = it
                activeByRunId[it.runId] = it
            }
        }

        store.save(active.snapshot)
        active.begin()
        return StartResult.Started(active.runId)
    }

    /**
     * The run resource — the reconnect and late-join source of truth (spec §11.4).
     *
     * Live state wins over the stored copy: the graph is written at phase boundaries, so mid-phase
     * it is behind by design, and a client that read the node instead would see a stale `current`.
     */
    public suspend fun run(runId: String): ImportRun? =
        activeByRunId[runId]?.snapshot ?: store.load(runId)?.withPhases()

    public suspend fun history(importerId: String?, limit: Int): List<ImportRun> {
        val stored = store.history(importerId, limit).map { it.withPhases() }
        // An active run is not in the store's history yet at the point its own phase 0 is running,
        // and a history list that omits the run currently happening reads as "nothing happened".
        val live = activeByImporter.values
            .filter { importerId == null || it.snapshot.importerId == importerId }
            .map { it.snapshot }
            .filter { run -> stored.none { it.runId == run.runId } }
        return (live + stored).sortedByDescending { it.startedAt }.take(limit)
    }

    /** The live event stream, or null once the run is over and its state has been handed to the store. */
    public fun events(runId: String): SharedFlow<ImportEvent>? = activeByRunId[runId]?.events

    /** The live log's ring buffer. Empty for a finished run — it was never persisted (spec §11.2). */
    public fun logLines(runId: String): List<ImportLogLine> =
        activeByRunId[runId]?.log?.snapshot() ?: emptyList()

    /**
     * Requests cancellation. Returns false if the run is not active — already finished, or never
     * existed, and the caller cannot tell those apart because from here they are the same fact.
     *
     * **Committed work stays committed.** A cancelled import leaves partial data, which the run's
     * own status is what says out loud.
     */
    public fun cancel(runId: String): Boolean {
        val active = activeByRunId[runId] ?: return false
        logger.info { "Cancellation requested for import run $runId" }
        active.cancel()
        return true
    }

    /** Cancels everything in flight. Called on `ApplicationStopping`. */
    override fun close() {
        scope.cancel("The application is stopping")
    }

    /**
     * Puts the importer's phase declaration back on a run read from the store.
     *
     * The phases are deliberately **not persisted** — a finished run's phase list is its importer's,
     * and a stored copy would let the two disagree after a release that renamed a phase. That is
     * only true if something restores it on the way out, which is this: without it, every run in the
     * history renders with an empty stepper and no percentage, which is exactly what the first live
     * import produced.
     *
     * An importer this build no longer has leaves the list empty rather than inventing one. The run
     * still reads; it just cannot be drawn as steps, which is the honest rendering of a run this
     * version of the software does not know how to describe.
     */
    private fun ImportRun.withPhases(): ImportRun =
        if (phases.isNotEmpty()) this else copy(phases = jobs[importerId]?.phases.orEmpty())

    // -- one run ---------------------------------------------------------------------------------

    /**
     * The mutable half of a run: its state, its subscribers, its log, and the [ImportContext] its
     * job writes through.
     *
     * Everything a job calls lands here, and none of it suspends on a subscriber. The shared flow is
     * `DROP_OLDEST` with no replay for exactly that reason: a console on a slow connection may miss
     * events, and missing events is the correct outcome — the run resource is the source of truth
     * and a subscriber that has fallen behind re-reads it. A buffer that blocked instead would make
     * one stalled browser tab able to stop an import.
     */
    private inner class ActiveRun(private val job: ImportJob) {

        val runId: String = "run-${UUID.randomUUID()}"

        private val state = MutableStateFlow(
            ImportRun(
                runId = runId,
                importerId = job.importerId,
                status = ImportStatus.QUEUED,
                startedAt = now(),
                phases = job.phases,
            ),
        )

        private val _events = MutableSharedFlow<ImportEvent>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val events: SharedFlow<ImportEvent> = _events.asSharedFlow()
        val log = ImportRunLog()

        /** Every warning, uncapped. The cap is applied when the list is read — see [cappedWarnings]. */
        private val warnings = CopyOnWriteArrayList<String>()
        private val counters = ConcurrentHashMap<String, Long>()

        /**
         * When a `progress` event was last let through, in nanos.
         *
         * The throttle is here rather than in the route because it protects the *emitter*: a phase
         * counting to 784 would otherwise put 784 events through the flow whether or not anybody is
         * subscribed, and the cost of an event nobody wanted is paid before the subscriber count is
         * consulted.
         */
        private val lastProgressAt = AtomicLong(Long.MIN_VALUE / 2)

        val snapshot: ImportRun get() = state.value

        private lateinit var coroutine: Job

        fun begin() {
            coroutine = scope.launch(start = CoroutineStart.LAZY) { execute() }
            coroutine.start()
        }

        fun cancel() {
            if (::coroutine.isInitialized) coroutine.cancel("Cancelled by request")
        }

        private suspend fun execute() {
            state.update { it.copy(status = ImportStatus.RUNNING) }
            logger.info { "Import run $runId started for '${job.importerId}'" }

            val outcome = try {
                job.run(context)
                if (warnings.isEmpty()) ImportStatus.SUCCEEDED to null
                else ImportStatus.SUCCEEDED_WITH_WARNINGS to null
            } catch (cause: CancellationException) {
                ImportStatus.CANCELLED to null
            } catch (cause: Exception) {
                logger.error(cause) { "Import run $runId failed" }
                ImportStatus.FAILED to describe(cause)
            }

            // NonCancellable, because the cancelled case reaches here with a cancelled context and
            // every suspending call in the finalisation - the store write, the last emit - would
            // fail immediately. A cancelled run that cannot record that it was cancelled looks
            // exactly like a run that vanished.
            withContext(NonCancellable) { finish(outcome.first, outcome.second) }
        }

        private suspend fun finish(status: ImportStatus, error: String?) {
            state.update {
                it.copy(
                    status = status,
                    finishedAt = now(),
                    counters = counters.toMap(),
                    warnings = cappedWarnings(warnings.toList()),
                    error = error,
                )
            }
            val finished = state.value
            store.save(finished)
            store.prune(job.importerId, historyLimit)

            // The state is set *before* this is emitted, which is what makes the SSE route's
            // late-join check sound: a subscriber that re-reads the run after subscribing either
            // sees a finished run (and says so itself) or is guaranteed to receive this.
            emit(
                ImportEvent.Status(
                    runId = runId,
                    status = status,
                    finishedAt = finished.finishedAt,
                    warnings = warnings.size,
                    error = error,
                ),
            )

            activeByImporter.remove(job.importerId, this)
            activeByRunId.remove(runId, this)
            logger.info { "Import run $runId finished: $status" }
        }

        private fun emit(event: ImportEvent) {
            // tryEmit, never emit: DROP_OLDEST guarantees it succeeds without suspending, and an
            // importer that could block on a subscriber is an importer a browser tab can stall.
            _events.tryEmit(event)
        }

        private fun now(): String = Instant.now(clock).toString()

        val context: ImportContext = object : ImportContext {
            override val runId: String get() = this@ActiveRun.runId

            override suspend fun phase(phaseId: String) {
                val index = job.phases.indexOfFirst { it.id == phaseId }
                require(index >= 0) {
                    "Importer '${job.importerId}' entered undeclared phase '$phaseId'. " +
                        "Declared: ${job.phases.joinToString { it.id }}"
                }
                val phase = job.phases[index]
                state.update { it.copy(phase = phaseId, current = 0, total = 0) }
                // A phase boundary is the one moment worth writing to the graph mid-run: it is
                // rare, and it is what a run resource read after a crash should say.
                store.save(state.value)
                emit(
                    ImportEvent.Phase(
                        runId = runId,
                        phase = phaseId,
                        label = phase.label,
                        index = index + 1,
                        of = job.phases.size,
                    ),
                )
                emitCounters()
                log(phase.label)
            }

            override suspend fun progress(current: Int, total: Int) {
                state.update { it.copy(current = current, total = total) }

                // The completing value is never throttled away. Dropping it would leave a bar
                // stopped at 96% on a phase that finished, which reads as a hung import.
                val complete = total > 0 && current >= total
                val elapsed = System.nanoTime() - lastProgressAt.get()
                if (!complete && elapsed < MIN_PROGRESS_INTERVAL_NANOS) return
                lastProgressAt.set(System.nanoTime())

                val snapshot = state.value
                emit(
                    ImportEvent.Progress(
                        runId = runId,
                        phase = snapshot.phase.orEmpty(),
                        current = current,
                        total = total,
                        percent = snapshot.percent,
                    ),
                )
                // Piggy-backed rather than emitted per count(): counters are a running summary, not
                // a stream, and every event carries the whole map so a dropped one costs nothing.
                emitCounters()
            }

            override suspend fun log(message: String, level: ImportLogLevel) {
                val line = ImportLogLine(level, message, now())
                this@ActiveRun.log.add(line)
                emit(ImportEvent.Log(runId, line))
            }

            override suspend fun warn(message: String) {
                warnings += message
                log(message, ImportLogLevel.WARN)
            }

            override suspend fun count(name: String, delta: Long) {
                counters.merge(name, delta, Long::plus)
            }

            override suspend fun setCount(name: String, value: Long) {
                counters[name] = value
            }

            override suspend fun params(params: Map<String, String>) {
                state.update { it.copy(params = params) }
            }

            override suspend fun ensureActive() {
                currentCoroutineContext().ensureActive()
            }
        }

        private fun emitCounters() {
            val snapshot = counters.toMap()
            state.update { it.copy(counters = snapshot) }
            emit(ImportEvent.Counters(runId, snapshot))
        }
    }

    public companion object {
        /**
         * How many finished runs of one importer survive.
         *
         * A history, not an audit log: the questions it answers are "did last night's import work"
         * and "when did this start failing", and both are answered by the last few dozen.
         */
        public const val DEFAULT_HISTORY_LIMIT: Int = 25

        /** Four progress events a second, at most (spec §11.4). */
        private const val MIN_PROGRESS_INTERVAL_NANOS: Long = 250_000_000

        /**
         * A failure as one line for the run record: the message, and the exception class that
         * produced it.
         *
         * The class is included because a message alone is often "null" or a bare file name, and
         * the reader of an `:__ImportRun` has no stack trace — **that stays in the log** (spec
         * §11.2), which is also why nothing here concatenates causes.
         */
        internal fun describe(cause: Throwable): String {
            val message = cause.message?.takeIf { it.isNotBlank() } ?: "no message"
            return "$message (${cause::class.simpleName})"
        }
    }
}
