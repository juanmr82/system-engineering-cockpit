package com.sec

import com.sec.api.configureProblemDetails
import com.sec.api.configureRouting
import com.sec.config.loadAppConfig
import com.sec.graph.GraphDriver
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.ReviewProjection
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.UUID

private val logger = KotlinLogging.logger {}

// EngineMain reads ktor.application.modules / ktor.deployment.* from application.yaml,
// which is what makes environment.config see neo4j.* and navigation.* below.
public fun main(args: Array<String>): Unit = EngineMain.main(args)

// Module wiring only — no business logic here (CLAUDE.md §5).
public fun Application.module() {
    val appConfig = loadAppConfig(environment.config)
    val graphDriver = GraphDriver(appConfig.neo4j)
    monitor.subscribe(ApplicationStopping) { graphDriver.close() }

    // Fail fast: a deployment pointed at an unreachable database should die at startup with a
    // clear cause, not start, report healthy, and serve 500s.
    graphDriver.verifyConnectivity()
    logger.info { "Connected to ${appConfig.neo4j.uri}, database '${appConfig.neo4j.database}'" }

    runBlocking { MetaSchema.apply(graphDriver) }

    configureApp(graphDriver)
}

// Everything that does not need a live database, so the HTTP surface — plugins, error mapping,
// routing — can be exercised in a test without Docker. module() adds the startup steps that do.
internal fun Application.configureApp(graphDriver: GraphDriver) {
    install(ContentNegotiation) {
        // encodeDefaults: a field whose value equals its declared default is still part of the
        // contract, and kotlinx omits it unless told otherwise. That silently dropped
        // `incomingComplete: false` — the one field REQ_REVIEW.md §5.1 requires to travel *with*
        // the data so no consumer can read an empty incoming list as "orphan requirement" — and
        // turned every empty list into an absent key the client would have to defend against.
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }
    install(CallLogging) {
        callIdMdc("callId")
    }
    configureProblemDetails()

    // Collaborators are constructed once here, not inside routing, so routing owns no object
    // lifecycles and a test can substitute its own.
    val doorsProjection = DoorsProjection(graphDriver)
    val reviewProjection = ReviewProjection(graphDriver)
    val breakdownProjection = BreakdownProjection(graphDriver)
    val metaWriter = MetaWriter(graphDriver, doorsProjection)

    configureRouting(graphDriver, doorsProjection, reviewProjection, breakdownProjection, metaWriter)
}
