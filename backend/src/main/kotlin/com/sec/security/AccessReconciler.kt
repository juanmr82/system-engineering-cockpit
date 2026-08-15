package com.sec.security

import com.sec.graph.GraphDriver
import com.sec.graph.cypher.AccessCypher
import com.sec.graph.executeAutocommit
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** One [Containment]'s outcome from one reconcile pass (`docs/features/access-control.md` §8.3). */
public data class ReconcileResult(
    public val sourceId: String,
    public val propagated: Long,
    public val retracted: Long,
    public val seeded: Long,
)

/**
 * Propagates a container's category to its members, retracts what a container no longer directly
 * carries, and seeds a never-categorised container (or, containerless, item) from its source's
 * default — the three things §8.3 names and nothing else. Idempotent and restartable: running
 * [reconcile] twice with nothing changed produces the same three counts both times, which is
 * phase 3's own acceptance line (`docs/features/access-control.md` §15).
 *
 * Every write goes through [com.sec.graph.executeAutocommit], not [com.sec.graph.executeWrite]:
 * `CALL … IN TRANSACTIONS` cannot run inside the explicit transaction that helper opens.
 *
 * The audit identity on every relationship this writes is [CurrentUser.PLACEHOLDER] — correctly,
 * not provisionally: propagate, retract and seed are the reconciler's own decisions, not a human's,
 * so `"system"` is the right, permanent value here, unlike the `:__Meta` write paths waiting to be
 * wired to a real principal.
 */
public class AccessReconciler(private val graphDriver: GraphDriver) {

    public suspend fun reconcile(containment: Containment): ReconcileResult {
        val now = Instant.now().toString()
        val user = CurrentUser.PLACEHOLDER

        val (propagated, retracted) = if (containment.containerless) {
            0L to 0L
        } else {
            count(AccessCypher.propagate(containment), mapOf("user" to user, "now" to now)) to
                count(AccessCypher.retract(containment), emptyMap())
        }
        val seeded = count(
            AccessCypher.seed(containment),
            mapOf(
                "sourceId" to containment.sourceId,
                "containerLabel" to containment.containerLabel,
                "user" to user,
                "now" to now,
            ),
        )

        val result = ReconcileResult(containment.sourceId, propagated, retracted, seeded)
        logger.info {
            "Reconciled '${containment.sourceId}': +$propagated propagated, -$retracted retracted, " +
                "$seeded seeded"
        }
        return result
    }

    /** Every registered containment, unscoped — the manual endpoint and the startup pass (§8.3). */
    public suspend fun reconcileAll(containments: List<Containment> = AccessContainment.all): List<ReconcileResult> =
        containments.map { reconcile(it) }

    private suspend fun count(statement: String, params: Map<String, Any?>): Long =
        graphDriver.executeAutocommit(Query(statement, params)) { records ->
            records.single().get("n").asLong()
        }
}
