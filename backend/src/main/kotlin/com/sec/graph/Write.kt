package com.sec.graph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.SessionConfig

// The only place a write session is opened. Writes use session.executeWrite. Every route that
// reaches here must go through the R2-guarded meta write path (com.sec.meta) for :__Meta data —
// this function itself has no opinion on what is written, that guard lives one layer up.
public suspend fun <T> GraphDriver.executeWrite(query: Query, transform: (List<Record>) -> T): T =
    withContext(Dispatchers.IO) {
        driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.executeWrite { tx -> transform(tx.run(query).list()) }
        }
    }
