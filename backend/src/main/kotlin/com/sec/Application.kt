package com.sec

import com.sec.api.configureProblemDetails
import com.sec.api.configureRouting
import com.sec.config.ConfigArgs
import com.sec.config.JiraSettings
import com.sec.config.loadAppConfig
import com.sec.graph.GraphDriver
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.DoorsTableProjection
import com.sec.source.doors.RequirementCardProjection
import com.sec.source.doors.ReviewProjection
import com.sec.source.doors.StatisticsProjection
import com.sec.source.jira.JiraGraphWriter
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraProjection
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
//
// ConfigArgs turns `-config=<path>` into an overlay on the packaged application.yaml instead of a
// replacement for it, so a deployment file states only what its environment changes. Every other
// flag stays EngineMain's — `-port=`, `-host=`, and the per-key `-P:neo4j.uri=…` override.
public fun main(args: Array<String>): Unit = EngineMain.main(ConfigArgs.withPackagedDefaults(args))

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

    // One HttpClient for the process lifetime, for the same reason there is one Driver: it owns a
    // connection pool, and building one per import would throw that away on every click. Absent
    // rather than empty when JIRA is unconfigured, so there is no half-built client to call by
    // accident — the routes check for null and answer 503.
    val jiraApi = if (appConfig.jira.isConfigured) {
        JiraHttpClient(appConfig.jira).also { client ->
            monitor.subscribe(ApplicationStopping) { client.close() }
            logger.info { "JIRA client configured for ${appConfig.jira.host} (${appConfig.jira.platform})" }
        }
    } else {
        logger.info { "JIRA is not configured; the JIRA views will say so" }
        null
    }

    configureApp(graphDriver, appConfig.jira, jiraApi)
}

// Everything that does not need a live database, so the HTTP surface — plugins, error mapping,
// routing — can be exercised in a test without Docker. module() adds the startup steps that do.
//
// JIRA defaults to unconfigured so a test that only cares about the DOORS surface says nothing
// about it, and gets the same "not configured" answers a real backend without JIRA would give.
internal fun Application.configureApp(
    graphDriver: GraphDriver,
    jiraSettings: JiraSettings = JiraSettings.UNCONFIGURED,
    jiraApi: com.sec.source.jira.JiraApi? = null,
) {
    install(ContentNegotiation) {
        // encodeDefaults: a field whose value equals its declared default is still part of the
        // contract, and kotlinx omits it unless told otherwise. That silently dropped
        // `incomingComplete` — the one field REQ_REVIEW.md §5.1 requires to travel *with* the
        // data, because whether an empty incoming list means anything is not something a consumer
        // may assume — and turned every empty list into an absent key the client would have to
        // defend against.
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
    // One card shape, one thing that builds it: the Breakdown tab and the dependency graph both
    // read requirement cards from here (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1).
    val cardProjection = RequirementCardProjection(graphDriver)
    val breakdownProjection = BreakdownProjection(graphDriver, cardProjection)
    val dependencyGraphProjection = DependencyGraphProjection(graphDriver, cardProjection)
    val metaWriter = MetaWriter(graphDriver, doorsProjection)
    val statisticsProjection = StatisticsProjection(graphDriver)
    val tableProjection = DoorsTableProjection(graphDriver)

    // The JIRA source. Two writers on purpose (ADR 0013): JiraGraphWriter writes imported nodes
    // and MetaWriter writes Tier 2, and neither can reach the other's statements.
    val jiraProjection = JiraProjection(graphDriver)
    val jiraGraphWriter = JiraGraphWriter(graphDriver, jiraSettings.batchSize)
    val jiraImporter = JiraImporter(jiraApi, jiraGraphWriter, jiraProjection, jiraSettings)

    configureRouting(
        graphDriver,
        doorsProjection,
        reviewProjection,
        breakdownProjection,
        dependencyGraphProjection,
        metaWriter,
        statisticsProjection,
        tableProjection,
        jiraSettings,
        jiraApi,
        jiraProjection,
        jiraImporter,
        jiraGraphWriter,
    )
}
