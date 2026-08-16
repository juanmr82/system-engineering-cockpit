package com.sec.graph

import com.sec.security.AccessSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.SessionConfig

/**
 * The only place a read session is opened. Reads use `session.executeRead` — on Community,
 * per-transaction access mode is the only server-side write protection that exists, and the
 * transaction timeout carried by [GraphDriver.readTx] is the only query governor
 * (CLAUDE.md §5, §7).
 *
 * [transform] runs **inside** the retryable transaction, so `executeRead` re-invokes it on a
 * transient failure. Keep it pure: a transform that increments a metric or fills a cache would
 * double-count invisibly under retry.
 */
public suspend fun <T> GraphDriver.executeRead(query: Query, transform: (List<Record>) -> T): T =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeRead({ tx -> transform(tx.run(query).list()) }, readTx)
        }
    }

/**
 * The one place `$seesAll`/`$acl` are bound (`docs/features/access-control.md` §6.3). A statement
 * built with [com.sec.graph.cypher.AccessCypher.visible] takes these two parameters and no others
 * for authorization, so every caller goes through this overload instead of assembling them by
 * hand — a route handler that did so by itself is exactly the drift §6.3 rules out.
 */
public suspend fun <T> GraphDriver.executeRead(
    statement: String,
    params: Map<String, Any?>,
    access: AccessSet,
    transform: (List<Record>) -> T,
): T = executeRead(Query(statement, params + access.parameters()), transform)

/**
 * The two authorization parameters, named once.
 *
 * `$seesAll` and `$acl` are a contract between [com.sec.graph.cypher.AccessCypher.visible] and the
 * session-opening functions that bind them, and this is the only place either name is spelled on
 * the binding side — reads and writes both come through here, so they cannot drift apart. A third
 * spelling in `Write.kt` is exactly the kind of second declaration ADR 0010 exists to prevent.
 */
internal fun AccessSet.parameters(): Map<String, Any> =
    mapOf("seesAll" to seesAll, "acl" to categoryIds)
