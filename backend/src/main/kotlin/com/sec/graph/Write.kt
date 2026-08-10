package com.sec.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.SessionConfig

/**
 * The only place a write session is opened. Writes use `session.executeWrite` and carry the
 * transaction timeout from [GraphDriver.writeTx]. Every route that reaches here must go through
 * the R2-guarded meta write path (`com.sec.meta`) for `:__Meta` data — this function itself has
 * no opinion on what is written, that guard lives one layer up.
 *
 * As with reads, [transform] runs inside the retryable transaction and must stay pure.
 */
public suspend fun <T> GraphDriver.executeWrite(query: Query, transform: (List<Record>) -> T): T =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeWrite({ tx -> transform(tx.run(query).list()) }, writeTx)
        }
    }

// A dialog that spans two tabs still saves once, atomically (CLAUDE.md §2 R7): running every
// statement inside one executeWrite lambda keeps them all in a single server-side transaction.
public suspend fun GraphDriver.executeWrite(queries: List<Query>): Unit =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeWrite({ tx -> queries.forEach { tx.run(it) } }, writeTx)
        }
    }

/**
 * What a write actually changed, as the server counted it.
 *
 * An importer has to report how many nodes it created as against updated, and the honest source
 * for that is the server's own counters — not a `count(*)` of the rows sent, which cannot tell a
 * MERGE that matched from one that created, and not a read-before-write, which doubles the round
 * trips and still races. Only the fields something reports on are surfaced.
 */
public data class WriteCounts(
    public val nodesCreated: Int = 0,
    public val nodesDeleted: Int = 0,
    public val relationshipsCreated: Int = 0,
    public val relationshipsDeleted: Int = 0,
) {
    public operator fun plus(other: WriteCounts): WriteCounts = WriteCounts(
        nodesCreated + other.nodesCreated,
        nodesDeleted + other.nodesDeleted,
        relationshipsCreated + other.relationshipsCreated,
        relationshipsDeleted + other.relationshipsDeleted,
    )

    public companion object {
        public val NONE: WriteCounts = WriteCounts()
    }
}

/**
 * A write that reports what it changed. Same session discipline as [executeWrite] — this is not a
 * second write path, it is the same one asked a further question.
 *
 * `consume()` is what makes the counters available, and it must be called inside the transaction
 * lambda: the result is streamed, and reading its summary after the transaction has closed throws.
 */
public suspend fun GraphDriver.executeWriteCounting(query: Query): WriteCounts =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeWrite({ tx ->
                val counters = tx.run(query).consume().counters()
                WriteCounts(
                    nodesCreated = counters.nodesCreated(),
                    nodesDeleted = counters.nodesDeleted(),
                    relationshipsCreated = counters.relationshipsCreated(),
                    relationshipsDeleted = counters.relationshipsDeleted(),
                )
            }, writeTx)
        }
    }
