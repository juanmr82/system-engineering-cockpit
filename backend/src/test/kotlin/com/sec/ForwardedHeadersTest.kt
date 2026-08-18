package com.sec

import com.sec.config.Neo4jSettings
import com.sec.config.ServerSettings
import com.sec.graph.GraphDriver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `server.behindProxy` and what it actually changes (ADR 0021).
 *
 * The subject is `call.request.origin.remoteAddress`, because that is the value
 * `CallLogging`'s `clientIp` MDC field reads — and an audit log that names the proxy on every line
 * is the thing the flag exists to prevent. Asserted through a probe route rather than by reading
 * log output: the log format is not a contract, and the value under it is.
 *
 * The security half is the more important half. `XForwardedHeaders` believes the header from
 * whoever sent it, so with the flag off — the packaged default — a caller must not be able to
 * choose what this application records about it.
 */
class ForwardedHeadersTest {

    private val spoofed = "203.0.113.77"

    /** A route that answers with whatever the application currently believes the caller's address is. */
    private fun ApplicationTestBuilder.appReporting(behindProxy: Boolean) {
        application {
            configureApp(
                GraphDriver(Neo4jSettings("bolt://localhost:7687", "neo4j", "test", "test")),
                serverSettings = ServerSettings(behindProxy = behindProxy),
            )
            routing {
                get("/probe-origin") { call.respondText(call.request.origin.remoteAddress) }
            }
        }
    }

    @Test
    fun `behind a proxy, the forwarded address is what the application sees`() = testApplication {
        appReporting(behindProxy = true)

        val seen = client.get("/probe-origin") { header("X-Forwarded-For", spoofed) }.bodyAsText()

        assertEquals(spoofed, seen, "X-Forwarded-For was ignored; the audit log would name the proxy")
    }

    /**
     * The default, and the one that matters. A process reachable directly must not let a caller
     * write its own address into the log — so the header is ignored and the socket's own peer
     * stands, whatever the request claims.
     */
    @Test
    fun `not behind a proxy, a forwarded address is ignored`() = testApplication {
        appReporting(behindProxy = false)

        val seen = client.get("/probe-origin") { header("X-Forwarded-For", spoofed) }.bodyAsText()

        assertEquals(
            "localhost",
            seen,
            "X-Forwarded-For was trusted without server.behindProxy; a caller can forge its own address",
        )
    }

    /**
     * **Which entry of a multi-value `X-Forwarded-For` wins**, and therefore whether nginx may
     * append to the header or must overwrite it.
     *
     * `$proxy_add_x_forwarded_for` APPENDS the peer to whatever the client sent, so a request
     * arriving with a forged `X-Forwarded-For: 1.2.3.4` reaches this application as
     * `1.2.3.4, <real peer>`. If the first entry wins, the caller has just chosen what the audit
     * log records about it — through the proxy, with the loopback bind doing nothing to stop it,
     * because the bind prevents *bypassing* nginx and not *injecting a header through* it.
     *
     * Keycloak's own reverse-proxy guide names this exactly: "Ensure the proxy overwrites (not
     * just appends to) forwarded headers to prevent clients from injecting false values."
     *
     * This test pins the behaviour so the nginx side cannot regress to `$proxy_add_x_forwarded_for`
     * unnoticed.
     */
    @Test
    fun `the FIRST forwarded entry wins, which is why nginx must overwrite and not append`() = testApplication {
        appReporting(behindProxy = true)

        val seen = client.get("/probe-origin") {
            header("X-Forwarded-For", "$spoofed, 10.0.0.9")
        }.bodyAsText()

        assertEquals(
            spoofed,
            seen,
            "the leftmost entry is the one this application believes; nginx must send exactly one",
        )
    }

    /** The header being absent is the ordinary case and must not change anything either way. */
    @Test
    fun `with no forwarded header the socket's own peer stands, proxied or not`() = testApplication {
        appReporting(behindProxy = true)

        assertEquals("localhost", client.get("/probe-origin").bodyAsText())
    }
}
