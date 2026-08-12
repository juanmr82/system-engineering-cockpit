package com.sec.importer

/**
 * The state of one importer execution, and the vocabulary the framework is built from.
 *
 * **Nothing in this package may mention a source** (`docs/JIRA_ISSUES_FEATURE_SPEC.md` §11). DOORS,
 * Windchill and CAMEO are meant to reuse this unchanged, and the only thing a run says about which
 * source it was is [ImportRun.importerId] — a string, chosen by the importer, opaque to everything
 * here. If a type in this package ever needs to know what a project key or a module is, the
 * abstraction has failed and the fix is in the importer, not here.
 */

/**
 * The run lifecycle (spec §11.1).
 *
 * ```
 * QUEUED ──► RUNNING ──┬──► SUCCEEDED
 *                      ├──► SUCCEEDED_WITH_WARNINGS
 *                      ├──► FAILED
 *                      └──► CANCELLED
 * ```
 *
 * [SUCCEEDED_WITH_WARNINGS] is a distinct outcome rather than a flag on [SUCCEEDED] because the
 * two need different words in the UI: one is "done", the other is "done, and read this". A run
 * that quietly folded its warnings into success would be a run whose warnings nobody reads.
 */
public enum class ImportStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    SUCCEEDED_WITH_WARNINGS,
    FAILED,
    CANCELLED,
    ;

    /** True once no further work will happen — the point at which the run resource stops moving. */
    public val isFinished: Boolean
        get() = this != QUEUED && this != RUNNING
}

/**
 * One declared step of an import.
 *
 * Declared **up front**, before anything runs, so the console can draw the whole stepper and a
 * meaningful aggregate percentage from the first event. An importer that discovered its phases as
 * it went would leave the UI unable to say how much is left, which is the one question a progress
 * display exists to answer.
 *
 * @property weight this phase's share of the aggregate bar, relative to the other phases. Absolute
 *   values do not matter — [percentComplete] normalises by the sum — so a phase list that grows as
 *   phases are built stays correct without anyone rebalancing the numbers.
 */
public data class ImportPhase(
    public val id: String,
    public val label: String,
    public val weight: Int,
)

/**
 * The aggregate percentage across a phase list: every earlier phase's weight in full, plus
 * [fraction] of this one's.
 *
 * Returns null for an unknown phase id rather than guessing, so a typo shows up as a missing bar
 * instead of a bar that is confidently wrong.
 */
public fun List<ImportPhase>.percentComplete(phaseId: String, fraction: Double): Int? {
    val index = indexOfFirst { it.id == phaseId }.takeIf { it >= 0 } ?: return null
    val total = sumOf { it.weight }.takeIf { it > 0 } ?: return null
    val done = take(index).sumOf { it.weight } + this[index].weight * fraction.coerceIn(0.0, 1.0)
    return ((done / total) * 100).toInt().coerceIn(0, 100)
}

/** Severity of one live log line. Deliberately three values: anything finer is noise in a console. */
public enum class ImportLogLevel { INFO, WARN, ERROR }

/** One line of an import's live log. Never persisted — see [ImportRunLog]. */
public data class ImportLogLine(
    public val level: ImportLogLevel,
    public val message: String,
    public val at: String,
)

/**
 * A run, as a value.
 *
 * Immutable and copied on every change, so a snapshot handed to a route can never be half-updated
 * while it is being serialised. The mutation lives in [ImportRunService], which holds the current
 * snapshot in a `MutableStateFlow`.
 *
 * @property phases the importer's declaration, carried on the run so a late-joining client gets the
 *   stepper from the run resource alone (spec §11.4). Not persisted: a finished run's phase list is
 *   its importer's, and storing a copy would let the two disagree after a release.
 * @property warnings capped — see [WARNING_CAP]. Uncapped, one bad module could put megabytes of
 *   repeated text into a node that exists to be read at a glance.
 */
public data class ImportRun(
    public val runId: String,
    public val importerId: String,
    public val status: ImportStatus,
    public val startedAt: String,
    public val finishedAt: String? = null,
    public val phase: String? = null,
    public val phases: List<ImportPhase> = emptyList(),
    public val params: Map<String, String> = emptyMap(),
    public val counters: Map<String, Long> = emptyMap(),
    public val warnings: List<String> = emptyList(),
    public val error: String? = null,
    /** Progress within [phase], for the run resource. The SSE stream carries the same numbers live. */
    public val current: Int = 0,
    public val total: Int = 0,
) {
    /**
     * The aggregate bar, or null while no phase has started or if [phase] is not a declared one.
     *
     * A run that **succeeded** is 100 %, whatever its last phase's weight says. Without that case
     * the history would render every finished run at the percentage its final phase *started* at —
     * a completed import drawn as half done, which is the one reading a progress bar must never
     * support. A failed or cancelled run keeps the real number, because where it stopped is exactly
     * what somebody reading it wants to know.
     */
    public val percent: Int?
        get() = when (status) {
            ImportStatus.SUCCEEDED, ImportStatus.SUCCEEDED_WITH_WARNINGS -> 100
            else -> phase?.let {
                phases.percentComplete(it, if (total > 0) current.toDouble() / total else 0.0)
            }
        }

    public companion object {
        /**
         * How many distinct warnings a run keeps before it starts counting instead of listing.
         *
         * The overflow is reported as one extra line — `+N more` — rather than dropped silently:
         * a truncated list that does not say it was truncated is read as a complete one.
         */
        public const val WARNING_CAP: Int = 200
    }
}

/**
 * A capped warning list: the first [ImportRun.WARNING_CAP] verbatim, then one line saying how many
 * were not kept.
 *
 * Separate from the accumulator so the cap is applied where the list is *read*, and the count of
 * dropped warnings stays exact however many arrive.
 */
internal fun cappedWarnings(all: List<String>): List<String> =
    if (all.size <= ImportRun.WARNING_CAP) all
    else all.take(ImportRun.WARNING_CAP) + "+${all.size - ImportRun.WARNING_CAP} more"
