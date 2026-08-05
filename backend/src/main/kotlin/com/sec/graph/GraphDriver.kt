package com.sec.graph

import com.sec.config.Neo4jSettings
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.TransactionConfig

// One Driver for the process lifetime, created in Application.kt, closed on ApplicationStopping.
// Never construct a second one (CLAUDE.md §5).
public class GraphDriver(settings: Neo4jSettings) : AutoCloseable {
    public val database: String = settings.database

    internal val driver: Driver = GraphDatabase.driver(
        settings.uri,
        AuthTokens.basic(settings.user, settings.password),
    )

    // Carried here rather than at the call sites so no query can be issued without a timeout:
    // Read.kt and Write.kt are the only places a session is opened, and they apply these.
    internal val readTx: TransactionConfig =
        TransactionConfig.builder().withTimeout(settings.readTimeout).build()

    internal val writeTx: TransactionConfig =
        TransactionConfig.builder().withTimeout(settings.writeTimeout).build()

    // The driver is lazy: without this, a misconfigured or unreachable database still lets the
    // process start and report healthy, and the first user request is what discovers the problem.
    public fun verifyConnectivity() {
        driver.verifyConnectivity()
    }

    override fun close() {
        driver.close()
    }
}
