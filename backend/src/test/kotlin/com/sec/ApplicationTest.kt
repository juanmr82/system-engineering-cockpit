package com.sec

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `health endpoint responds ok`() = testApplication {
        application { module() }

        val response = client.get("/api/v1/health")

        assertEquals("ok", response.bodyAsText())
    }
}
