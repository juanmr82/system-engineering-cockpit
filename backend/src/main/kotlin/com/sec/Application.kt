package com.sec

import com.sec.api.configureRouting
import com.sec.config.loadAppConfig
import com.sec.graph.GraphDriver
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.serialization.json.Json
import java.util.UUID

// EngineMain reads ktor.application.modules / ktor.deployment.* from application.yaml,
// which is what makes environment.config see neo4j.* and navigation.* below.
public fun main(args: Array<String>): Unit = EngineMain.main(args)

// Module wiring only — no business logic here (CLAUDE.md §5).
public fun Application.module() {
    val appConfig = loadAppConfig(environment.config)
    val graphDriver = GraphDriver(appConfig.neo4j)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }
    install(CallLogging) {
        callIdMdc("callId")
    }
    install(StatusPages) {
        // Domain exceptions -> RFC 9457 problem details. No stack traces to the client, ever.
        // The ad-hoc Cypher endpoint has its own error shapes; see docs/CYPHER_API_DESIGN.md.
    }

    configureRouting(graphDriver)

    monitor.subscribe(ApplicationStopping) {
        graphDriver.close()
    }
}
