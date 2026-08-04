package com.sec.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.SessionConfig

// The only place a read session is opened. Reads use session.executeRead — on Community,
// per-transaction access mode is the only server-side write protection that exists (CLAUDE.md §5, §7).
public suspend fun <T> GraphDriver.executeRead(query: Query, transform: (List<Record>) -> T): T =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeRead { tx -> transform(tx.run(query).list()) }
        }
    }
