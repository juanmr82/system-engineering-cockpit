package com.sec.config

import io.ktor.server.config.ApplicationConfig

public data class Neo4jSettings(
    public val uri: String,
    public val database: String,
    public val user: String,
    public val password: String,
)

public data class AppConfig(
    public val neo4j: Neo4jSettings,
)

// Typed config from application.yaml + environment. Credentials never live in the yaml file.
public fun loadAppConfig(config: ApplicationConfig): AppConfig {
    val neo4j = Neo4jSettings(
        uri = config.property("neo4j.uri").getString(),
        database = config.property("neo4j.database").getString(),
        user = System.getenv("SEC_NEO4J_USER") ?: error("SEC_NEO4J_USER is not set"),
        password = System.getenv("SEC_NEO4J_PASSWORD") ?: error("SEC_NEO4J_PASSWORD is not set"),
    )
    return AppConfig(neo4j = neo4j)
}
