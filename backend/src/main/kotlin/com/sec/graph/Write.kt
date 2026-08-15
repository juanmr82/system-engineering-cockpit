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
 * The one narrow exception to "every write is `session.executeWrite`" (backend/CLAUDE.md §5):
 * Cypher's `CALL … IN TRANSACTIONS` cannot run inside an explicit transaction, so this runs
 * [query] autocommit instead — [com.sec.security.AccessReconciler] is the only caller. No
 * [GraphDriver.writeTx] timeout applies for the same reason a batched statement has its own
 * `IN TRANSACTIONS OF … ROWS` clause rather than one surrounding transaction: an import-sized
 * reconcile pass is expected to run longer than an ordinary write.
 */
public suspend fun <T> GraphDriver.executeAutocommit(query: Query, transform: (List<Record>) -> T): T =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            transform(session.run(query).list())
        }
    }
