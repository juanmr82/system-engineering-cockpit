package com.sec.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

public data class Neo4jSettings(
    public val uri: String,
    public val database: String,
    public val user: String,
    public val password: String,
    // Community has no query governor (CLAUDE.md §7), so the application-side transaction
    // timeout is the only thing standing between one bad query and an exhausted instance.
    public val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    public val writeTimeout: Duration = DEFAULT_WRITE_TIMEOUT,
) {
    public companion object {
        public val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        public val DEFAULT_WRITE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

public data class AppConfig(
    public val neo4j: Neo4jSettings,
)

// Typed config with exactly one source: the Ktor ApplicationConfig. Credentials are still absent
// from the yaml file — `user: "$SEC_NEO4J_USER"` is resolved from the environment by Ktor itself,
// which fails fast when the variable is unset. Nothing here reads System.getenv directly, so a
// test overrides credentials the same way it overrides every other setting.
public fun loadAppConfig(config: ApplicationConfig): AppConfig {
    val neo4j = Neo4jSettings(
        uri = config.property("neo4j.uri").getString(),
        database = config.property("neo4j.database").getString(),
        user = config.property("neo4j.user").getString(),
        password = config.property("neo4j.password").getString(),
        readTimeout = config.durationSeconds("neo4j.readTimeoutSeconds", Neo4jSettings.DEFAULT_READ_TIMEOUT),
        writeTimeout = config.durationSeconds("neo4j.writeTimeoutSeconds", Neo4jSettings.DEFAULT_WRITE_TIMEOUT),
    )
    return AppConfig(neo4j = neo4j)
}

private fun ApplicationConfig.durationSeconds(path: String, fallback: Duration): Duration =
    propertyOrNull(path)?.getString()?.toLongOrNull()?.let(Duration::ofSeconds) ?: fallback
