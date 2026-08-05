package com.sec.config

import io.ktor.server.config.ApplicationConfigurationException
import io.ktor.server.config.MapApplicationConfig
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Config has exactly one source (the ApplicationConfig), so a test overrides credentials the
// same way it overrides everything else — no System.getenv, and no environment() in the build file.
class AppConfigTest {

    private fun config(vararg extra: Pair<String, String>) = MapApplicationConfig(
        "neo4j.uri" to "bolt://localhost:7687",
        "neo4j.database" to "neo4j",
        "neo4j.user" to "someone",
        "neo4j.password" to "secret",
        *extra,
    )

    @Test
    fun `reads credentials and timeouts from the application config`() {
        val appConfig = loadAppConfig(
            config("neo4j.readTimeoutSeconds" to "5", "neo4j.writeTimeoutSeconds" to "15"),
        )

        assertEquals("someone", appConfig.neo4j.user)
        assertEquals("secret", appConfig.neo4j.password)
        assertEquals(Duration.ofSeconds(5), appConfig.neo4j.readTimeout)
        assertEquals(Duration.ofSeconds(15), appConfig.neo4j.writeTimeout)
    }

    @Test
    fun `falls back to the default timeouts when they are absent`() {
        val appConfig = loadAppConfig(config())

        assertEquals(Neo4jSettings.DEFAULT_READ_TIMEOUT, appConfig.neo4j.readTimeout)
        assertEquals(Neo4jSettings.DEFAULT_WRITE_TIMEOUT, appConfig.neo4j.writeTimeout)
    }

    // Fail fast on a missing credential — the point of the env-var indirection, not a nicety.
    @Test
    fun `a missing credential is a startup failure`() {
        val incomplete = MapApplicationConfig(
            "neo4j.uri" to "bolt://localhost:7687",
            "neo4j.database" to "neo4j",
        )

        assertFailsWith<ApplicationConfigurationException> { loadAppConfig(incomplete) }
    }
}
