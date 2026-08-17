package com.sec.importer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Re-runs one importer on a fixed interval (ADR 0018).
 *
 * Source-agnostic, like every other class in this package (`importerId` is a string chosen by the
 * caller, never named here) — built for JIRA first, because removing its project picker removed the
 * only thing that ever re-triggered an import of newly-relevant data, but nothing here assumes a
 * source.
 *
 * ## Where a tick lives, and why no extra "already running" guard is needed
 *
 * A tick calls [ImportRunService.start], which already returns [StartResult.AlreadyRunning] rather
 * than throwing when a run is in flight — a manual "run now" click, say. So a tick landing mid-run
 * is a silent no-op by construction; this class does not need to ask first.
 *
 * ## Why the first tick waits a full interval rather than firing immediately
 *
 * A backend restart happens often in development — every recompile — and ticking on startup would
 * mean every restart fires a real import against a real JIRA host. The existing manual "Import JIRA
 * issues" trigger already covers "I want data right now"; this class only ever covers "keep it
 * fresh in the background."
 *
 * Mirrors [ImportRunService]'s own coroutine shape: a `SupervisorJob` scope owned by this class, not
 * borrowed from a caller, closed on `ApplicationStopping`.
 */
public class ImportScheduler(
    public val importerId: String,
    public val interval: Duration,
    private val service: ImportRunService,
    private val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("import-scheduler-$importerId"))
    private val next = MutableStateFlow(clock.instant().plus(interval))
    private var job: Job? = null

    /** When the next tick is due. Advances after every tick, whether or not it actually started a run. */
    public fun nextRunAt(): Instant = next.value

    /** Begins the loop. Called once, at wiring time — never automatically from the constructor, so a test can build one without starting it. */
    public fun start() {
        job = scope.launch {
            while (isActive) {
                delay(interval.toMillis())
                // Caught rather than left to the SupervisorJob: an uncaught exception here would
                // end this one coroutine silently, and a scheduler that stops ticking after its
                // first failure is worse than one that logs and tries again next interval.
                runCatching { service.start(importerId) }
                    .onFailure { logger.warn(it) { "Scheduled import of '$importerId' could not start" } }
                next.value = clock.instant().plus(interval)
            }
        }
    }

    override fun close() {
        scope.cancel("The application is stopping")
    }
}
