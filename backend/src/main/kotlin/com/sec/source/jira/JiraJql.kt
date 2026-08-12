package com.sec.source.jira

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the one JQL query an import runs (spec §8).
 *
 * **The frontend never sends JQL.** It sends a list of project keys, which a settings screen
 * maintains; everything else here is fixed. That is what makes this the injection boundary — the
 * keys are user-editable text on their way into a query language — and why [PROJECT_KEY] rejects
 * anything it does not recognise rather than escaping it.
 *
 * The two fixed clauses are not decoration. Offset pagination over a result set that is changing
 * underneath it skips and duplicates rows, and both clauses exist to stop that (spec §3.3):
 *
 *  - `ORDER BY key ASC` gives the result set a deterministic total order. Without one, "page 4" is
 *    not a stable idea and JIRA is free to return the same issue twice and another one never.
 *  - `created <= <run start>` bounds the set to a snapshot, so issues created *during* a run
 *    cannot shift the page boundaries under it.
 *
 * Issues *updated* mid-run may still be read in either state. That is accepted and self-corrects
 * on the next import; there is no equivalent trick for it, and inventing one would mean holding a
 * transaction open across sixteen HTTP round trips.
 */
public object JiraJql {

    /**
     * JIRA's own date-time literal format. Not ISO-8601, and not negotiable — JQL rejects the
     * `T` separator, so `Instant.toString()` produces a 400 that reads like a JQL syntax error.
     */
    private val JQL_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

    /**
     * What a project key may look like.
     *
     * JIRA's own rule is stricter than this (uppercase, 2–10 characters), and this deliberately is
     * not: a validator that is stricter than the system it guards rejects legitimate data, and the
     * job here is to exclude quotes, spaces and JQL operators, not to re-implement JIRA.
     */
    private val PROJECT_KEY = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /**
     * The query, or the reason there is not one.
     *
     * @param projectKeys in the user's own order, which is preserved so the stored settings and
     *   the previewed JQL read the same way round.
     * @param snapshotAt the run's start.
     * @param zone **the JIRA server's** time zone, taken from `/myself` at run start. Not the
     *   JVM's: the literal is compared against JIRA's own clock, so a service in a different zone
     *   would silently move the snapshot boundary by hours and either miss recent issues or admit
     *   ones created after the run began.
     */
    /**
     * The injection boundary, in one place so every caller crosses it the same way.
     *
     * Public because the settings write path has to reject a bad key *before* it is stored, not
     * only before it is interpolated — otherwise a key that fails validation lives in the graph and
     * breaks every future import instead of the one request that introduced it.
     */
    public fun validate(projectKeys: List<String>): Result<List<String>> {
        // Never fall back to an unbounded query over the whole instance. On a real instance that
        // is hundreds of thousands of issues, and the sweep in phase 5 would then treat every
        // project as configured.
        if (projectKeys.isEmpty()) return Result.failure(JiraFailure.NoProjectsConfigured())

        val invalid = projectKeys.filterNot { PROJECT_KEY.matches(it) }
        if (invalid.isNotEmpty()) return Result.failure(JiraFailure.InvalidProjectKey(invalid))

        return Result.success(projectKeys)
    }

    public fun build(
        projectKeys: List<String>,
        snapshotAt: Instant,
        zone: ZoneId,
    ): Result<String> {
        validate(projectKeys).getOrElse { return Result.failure(it) }

        val keys = projectKeys.joinToString(",") { "\"$it\"" }
        val bound = JQL_INSTANT.format(snapshotAt.atZone(zone))

        return Result.success("project in ($keys) AND created <= \"$bound\" ORDER BY key ASC")
    }

    /**
     * A preview for the settings page, which has no run and therefore no snapshot.
     *
     * Shows the query shape with the bound left as a placeholder rather than inventing a
     * timestamp, so nobody reads the preview as a promise about *when*. Spec §13.5 calls the
     * preview the best debugging aid in the feature, and it is: when an import returns something
     * unexpected, the first question is always what was actually asked for.
     */
    public fun preview(projectKeys: List<String>): Result<String> {
        validate(projectKeys).getOrElse { return Result.failure(it) }

        val keys = projectKeys.joinToString(",") { "\"$it\"" }
        return Result.success(
            "project in ($keys) AND created <= \"<import start time>\" ORDER BY key ASC",
        )
    }
}
