package com.sec.importer

/**
 * What an importer implements to be runnable by [ImportRunService].
 *
 * The whole interface between the framework and a source is these four members plus [ImportContext].
 * A source contributes phases and a body; it never touches run state, the event stream, the
 * `:__ImportRun` node or the SSE encoding, which is what stops the second importer re-implementing
 * three quarters of the first one.
 */
public interface ImportJob {
    /**
     * The stable id this importer is addressed by — `"jira"`, `"doors"`. It is a URL segment and a
     * `:__ImportRun` property, so it is lower-case and never renamed once shipped.
     */
    public val importerId: String

    /** What a person calls it. The only member of this interface that reaches a user (R5). */
    public val displayName: String

    /** Declared before anything runs, so the console can draw the stepper from the first event. */
    public val phases: List<ImportPhase>

    /**
     * Do the import.
     *
     * Runs on `Dispatchers.IO` inside the application's scope, **not the HTTP call's** — a client
     * that navigates away must not kill an import halfway through (spec §11.1).
     *
     * Throwing ends the run `FAILED` with the exception's message and class; the framework catches
     * it and neither the message nor a stack trace reaches the client. Returning normally ends it
     * `SUCCEEDED`, or `SUCCEEDED_WITH_WARNINGS` if [ImportContext.warn] was called.
     *
     * **Be cancellation-cooperative.** Call [ImportContext.ensureActive] between batches: work
     * already committed stays committed, so the sooner a cancelled run stops, the less partial data
     * it leaves.
     */
    public suspend fun run(context: ImportContext)
}

/**
 * What a running job may do to its own run.
 *
 * Everything here is safe to call from any coroutine of the job. Nothing here blocks on a
 * subscriber: a console nobody is watching must cost the import nothing, and a slow console must
 * not be able to slow it down (spec §11.4).
 */
public interface ImportContext {
    /** This run's id — for log lines that outlive the run. */
    public val runId: String

    /**
     * Enter [phaseId], which must be one of the declared [ImportJob.phases].
     *
     * Resets progress to zero and emits a `phase` event. Calling it with an undeclared id is a
     * programming error and throws, rather than emitting a phase the stepper cannot place.
     */
    public suspend fun phase(phaseId: String)

    /**
     * Progress within the current phase.
     *
     * Throttled on the way out — at most four events a second — so an import that counts to 784
     * does not emit 784 events into a zoneless change-detection loop. The value that completes a
     * phase is never throttled away.
     */
    public suspend fun progress(current: Int, total: Int)

    /** A line for the live console. Bounded ring buffer; never persisted (spec §11.2). */
    public suspend fun log(message: String, level: ImportLogLevel = ImportLogLevel.INFO)

    /**
     * Something the run survived but a person should read. Also logs at WARN.
     *
     * One call to this is what turns a `SUCCEEDED` into a `SUCCEEDED_WITH_WARNINGS`, so it is for
     * findings, not for narration — the narration is [log].
     */
    public suspend fun warn(message: String)

    /** Adds to a named counter. Counter names belong to the importer; this package never reads one. */
    public suspend fun count(name: String, delta: Long = 1)

    /** Sets a named counter outright, for a total that is known rather than accumulated. */
    public suspend fun setCount(name: String, value: Long)

    /**
     * Records what this run was asked to do — for JIRA the JQL and page size (spec §11.2).
     *
     * Recorded rather than passed in, because the parameters are often only known after preflight
     * has talked to the source, and a run resource that cannot say what it queried is one nobody
     * can reproduce.
     */
    public suspend fun params(params: Map<String, String>)

    /**
     * Cooperate with cancellation: throws `CancellationException` if the run was cancelled.
     *
     * Call it between batches. The framework cannot insert this for you — only the job knows where
     * a stopping point leaves the least partial data behind.
     */
    public suspend fun ensureActive()
}
