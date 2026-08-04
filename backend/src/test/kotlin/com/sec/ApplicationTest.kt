package com.sec

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    // testApplication builds a bare environment, unlike EngineMain (Application.kt), so
    // neo4j.* is supplied here directly. SEC_NEO4J_USER/PASSWORD come from the Gradle test
    // task's environment (backend/build.gradle.kts) — module() requires them unconditionally.
    @Test
    fun `health endpoint responds ok`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "neo4j.uri" to "bolt://localhost:7687",
                "neo4j.database" to "neo4j",
            )
        }
        application { module() }

        val response = client.get("/api/v1/health")

        assertEquals("ok", response.bodyAsText())
    }
}
