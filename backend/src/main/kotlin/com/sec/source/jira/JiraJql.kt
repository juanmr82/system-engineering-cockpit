package com.sec.source.jira

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the one JQL query an import runs (spec §8, ADR 0018).
 *
 * There is no project allow-list any more: RBAC is the gate (ADR 0016/R8), so the importer brings
 * in everything the configured token can see and access categories decide who may read it. That
 * makes this query fixed — nothing user-supplied ever reaches it, so there is no injection boundary
 * left to guard here.
 *
 * The fixed clauses are not decoration. Offset pagination over a result set that is changing
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
     * @param snapshotAt the run's start.
     * @param zone **the JIRA server's** time zone, taken from `/myself` at run start. Not the
     *   JVM's: the literal is compared against JIRA's own clock, so a service in a different zone
     *   would silently move the snapshot boundary by hours and either miss recent issues or admit
     *   ones created after the run began.
     */
    public fun build(snapshotAt: Instant, zone: ZoneId): String {
        val bound = JQL_INSTANT.format(snapshotAt.atZone(zone))
        return "created <= \"$bound\" ORDER BY key ASC"
    }
}
