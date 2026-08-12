package com.sec

import com.sec.api.configureProblemDetails
import com.sec.api.configureRouting
import com.sec.config.ConfigArgs
import com.sec.config.ImporterSettings
import com.sec.config.JiraSettings
import com.sec.config.loadAppConfig
import com.sec.graph.GraphDriver
import com.sec.importer.GraphImportRunStore
import com.sec.importer.ImportRunService
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.DoorsTableProjection
import com.sec.source.doors.RequirementCardProjection
import com.sec.source.doors.ReviewProjection
import com.sec.source.doors.StatisticsProjection
import com.sec.source.jira.JiraColumnStore
import com.sec.source.jira.JiraFieldsProjection
import com.sec.source.jira.JiraGraphWriter
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraSettingsStore
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
import io.ktor.server.sse.SSE
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

    val importRunStore = GraphImportRunStore(graphDriver)
    runBlocking {
        MetaSchema.apply(graphDriver)
        // The framework's own storage, so it is applied at startup like MetaSchema and unlike the
        // JIRA schema — that one belongs to a run, this one is present in every deployment.
        importRunStore.applySchema()
    }

    configureApp(graphDriver, appConfig.jira, importerSettings = appConfig.importer, importRunStore = importRunStore)
}

// Everything that does not need a live database, so the HTTP surface — plugins, error mapping,
// routing — can be exercised in a test without Docker. module() adds the startup steps that do.
//
// jiraSettings defaults to unconfigured so a test that only cares about the HTTP surface says
// nothing about JIRA and gets the same behaviour a deployment without a token gets.
internal fun Application.configureApp(
    graphDriver: GraphDriver,
    jiraSettings: JiraSettings = JiraSettings(host = "", token = ""),
    // Built from the settings unless a caller supplies one — which is how a test serves the
    // sample exports over a MockEngine without this file ever naming an engine type. One client
    // for the process lifetime, for the same reason there is one Driver: the OkHttp engine's
    // value is its connection pool, and a client per request discards it.
    jiraClient: JiraHttpClient? =
        if (jiraSettings.isConfigured) JiraHttpClient(jiraSettings) else null,
    importerSettings: ImporterSettings = ImporterSettings(),
    // Defaulted so a test of the HTTP surface gets a store that talks to the same driver every
    // other collaborator here does, and a test with no database can pass its own.
    importRunStore: com.sec.importer.ImportRunStore = GraphImportRunStore(graphDriver),
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
    // An import's live progress feed. The plugin itself is configuration-free; everything about
    // the stream — the heartbeat, the throttle, the terminal event — is in ImportRoutes.
    install(SSE)
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
    // Built whether or not JIRA is configured: the project list lives in the graph, so it is
    // readable and editable before a host exists, which lets an operator set the two up in either
    // order.
    val jiraSettingsStore = JiraSettingsStore(graphDriver)
    // Takes the host because a row's `browseUrl` is derived from it on every read — JIRA's stored
    // `self` is an API URL, and opening one shows raw JSON (spec §13.2).
    val jiraIssuesProjection = JiraIssuesProjection(graphDriver, jiraSettings.host)
    // The column choice and the catalogue it is chosen from. Both are read on every issues request,
    // so they are built once here rather than per call.
    val jiraColumnStore = JiraColumnStore(graphDriver)
    val jiraFieldsProjection = JiraFieldsProjection(graphDriver)

    // One service for every source. DOORS and Windchill register here too when their importers
    // move in-process; today JIRA is the only one, because it is the only one that can run inside
    // this JVM at all (ADR 0013).
    val importRunService = ImportRunService(importRunStore, importerSettings.runHistoryLimit)
    monitor.subscribe(ApplicationStopping) { importRunService.close() }

    if (jiraClient != null) {
        logger.info { "JIRA integration enabled for ${jiraSettings.host}" }
        monitor.subscribe(ApplicationStopping) { jiraClient.close() }
        importRunService.register(
            JiraImporter(
                jiraSettings,
                jiraClient,
                JiraGraphWriter(graphDriver, jiraSettings.host),
                // Its own collaborator rather than part of the writer: the writer is *only* allowed
                // to write imported JIRA data, and the configured project list is the one thing here
                // that no import could ever reproduce (ADR 0013, ADR 0014).
                jiraSettingsStore,
            ),
        )
    } else {
        logger.info { "JIRA integration is not configured; /api/v1/jira routes will report so" }
    }

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
        jiraClient,
        jiraSettingsStore,
        jiraIssuesProjection,
        jiraColumnStore,
        jiraFieldsProjection,
        importRunService,
    )
}
