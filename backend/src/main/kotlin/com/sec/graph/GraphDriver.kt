package com.sec.graph

import com.sec.config.Neo4jSettings
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

// One Driver for the process lifetime, created in Application.kt, closed on ApplicationStopping.
// Never construct a second one (CLAUDE.md §5).
public class GraphDriver(settings: Neo4jSettings) : AutoCloseable {
    public val database: String = settings.database

    internal val driver: Driver = GraphDatabase.driver(
        settings.uri,
        AuthTokens.basic(settings.user, settings.password),
    )

    override fun close() {
        driver.close()
    }
}
