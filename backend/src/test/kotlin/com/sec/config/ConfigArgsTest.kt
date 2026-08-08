package com.sec.config

import io.ktor.server.config.ConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two halves, and both are needed.
 *
 * The first is [ConfigArgs.withPackagedDefaults] itself — a pure function, trivially testable.
 *
 * The second matters more: our argument transform is only useful because of a **Ktor behaviour we
 * do not control** — that repeated `-config=` paths are merged key by key with the last one
 * winning, and that `application.yaml` resolves from the classpath. Both were verified by running
 * the shaded jar, but a verification that happened once on a laptop is not a guarantee across a
 * Ktor upgrade. These tests exercise `ConfigLoader.loadAll`, which is precisely what
 * `CommandLineConfig` hands the collected `-config=` paths to, so an upgrade that changed either
 * behaviour fails here instead of in a deployment.
 */
class ConfigArgsTest {

    // -- the pure function ------------------------------------------------------------------

    @Test
    fun `inserts the packaged config ahead of the first -config`() {
        assertContentEquals(
            arrayOf("-config=application.yaml", "-config=/etc/sec/sec.yaml"),
            ConfigArgs.withPackagedDefaults(arrayOf("-config=/etc/sec/sec.yaml")),
        )
    }

    @Test
    fun `leaves arguments alone when no -config is given`() {
        // EngineMain already loads the packaged file in this case; prepending would load it twice.
        val args = arrayOf("-port=9090")
        assertContentEquals(args, ConfigArgs.withPackagedDefaults(args))
    }

    @Test
    fun `does not insert a duplicate when the caller named the packaged file`() {
        val args = arrayOf("-config=application.yaml", "-config=/etc/sec/sec.yaml")
        assertContentEquals(args, ConfigArgs.withPackagedDefaults(args))
    }

    @Test
    fun `preserves other flags and their order`() {
        assertContentEquals(
            arrayOf("-port=9090", "-config=application.yaml", "-config=a.yaml", "-P:neo4j.uri=x"),
            ConfigArgs.withPackagedDefaults(arrayOf("-port=9090", "-config=a.yaml", "-P:neo4j.uri=x")),
        )
    }

    // -- the Ktor behaviour the transform depends on ----------------------------------------

    @Test
    fun `an overlay wins key by key and inherits everything it does not state`() {
        val overlay = tempConfig(
            """
            neo4j:
              uri: "bolt://db.internal:7687"
            """.trimIndent(),
        )
        try {
            val merged = ConfigLoader.loadAll(ConfigArgs.PACKAGED, overlay.toString())

            // Stated in the overlay — the overlay wins.
            assertEquals("bolt://db.internal:7687", merged.property("neo4j.uri").getString())

            // Not stated in the overlay — a deep merge, not a section replacement. This is the
            // whole point: an operator's file names a host, not a schema.
            assertEquals("neo4j", merged.property("neo4j.database").getString())
            assertEquals("10", merged.property("neo4j.readTimeoutSeconds").getString())

            // The plumbing an operator must never have to write, still present.
            assertEquals(
                listOf("com.sec.ApplicationKt.module"),
                merged.property("ktor.application.modules").getList(),
            )
            assertEquals("8080", merged.property("ktor.deployment.port").getString())
        } finally {
            overlay.deleteIfExists()
        }
    }

    @Test
    fun `the last -config wins, which is why the packaged file goes first`() {
        val first = tempConfig("neo4j:\n  database: \"from-first\"")
        val second = tempConfig("neo4j:\n  database: \"from-second\"")
        try {
            assertEquals(
                "from-second",
                ConfigLoader.loadAll(first.toString(), second.toString())
                    .property("neo4j.database").getString(),
            )
        } finally {
            first.deleteIfExists()
            second.deleteIfExists()
        }
    }

    /**
     * `application.yaml` names the file inside the jar, not one in the working directory.
     *
     * Surefire runs with the module directory as its working directory and there is no
     * `application.yaml` there, so if this resolves at all it resolved from the classpath.
     */
    @Test
    fun `the packaged config resolves from the classpath`() {
        assertNull(
            Path.of(ConfigArgs.PACKAGED).toAbsolutePath().takeIf { Files.exists(it) },
            "A working-directory ${ConfigArgs.PACKAGED} would make this test prove nothing",
        )
        assertEquals(
            "8080",
            ConfigLoader.load(ConfigArgs.PACKAGED).property("ktor.deployment.port").getString(),
        )
    }

    private fun tempConfig(body: String): Path =
        Files.createTempFile("sec-overlay", ".yaml").apply { writeText(body, Charsets.UTF_8) }
}
