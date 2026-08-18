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

    /**
     * `server.behindProxy` fails **closed** (ADR 0021). The plugin it gates trusts `X-Forwarded-For`
     * from whoever sent it, so every way of not-saying-yes has to mean no: an absent key, a blank
     * value, a typo, and the string `"1"` that a `.env` file invites. Only `true` is true.
     *
     * Parameterised over the wrong answers rather than written as one case per line, because the
     * risk here is a *new* falsy spelling being read as true, not any particular one of them.
     */
    @Test
    fun `behindProxy is off unless the config says true, however it says something else`() {
        assertEquals(false, loadAppConfig(config()).server.behindProxy, "absent")

        listOf("", "  ", "false", "FALSE", "no", "0", "1", "yes", "on", "trueish").forEach { value ->
            assertEquals(
                false,
                loadAppConfig(config("server.behindProxy" to value)).server.behindProxy,
                "\"$value\" was read as behind-a-proxy",
            )
        }
    }

    @Test
    fun `behindProxy is on when the config says true, in any casing`() {
        listOf("true", "TRUE", "True", " true ").forEach { value ->
            assertEquals(
                true,
                loadAppConfig(config("server.behindProxy" to value)).server.behindProxy,
                "\"$value\" was not read as behind-a-proxy",
            )
        }
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
