package com.sec

import com.sec.api.configureProblemDetails
import com.sec.api.configureRouting
import com.sec.api.respondProblem
import com.sec.config.AuthSettings
import com.sec.config.ConfigArgs
import com.sec.config.ImporterSettings
import com.sec.config.JiraSettings
import com.sec.config.WindchillSettings
import com.sec.config.loadAppConfig
import com.sec.config.loadAuthSettings
import com.sec.graph.GraphDriver
import com.sec.importer.GraphImportRunStore
import com.sec.importer.ImportRunService
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.security.AccessResolver
import com.sec.security.Oidc
import com.sec.security.SessionNames
import com.sec.security.UserSession
import com.sec.security.installSecSessions
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
import com.sec.source.jira.JiraLinkGraphProjection
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraSettingsStore
import com.sec.source.windchill.WindchillGraphWriter
import com.sec.source.windchill.WindchillImporter
import com.sec.source.windchill.WindchillProjection
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.session
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sessions.SessionStorage
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.UUID

private val logger = KotlinLogging.logger {}

// ADR 0017 §11: token refresh happens server-side, on use, with a small skew, so a request does
// not race an access token that expires mid-flight. 30s comfortably covers one request's latency
// against Keycloak's 5-minute default access-token lifespan without refreshing needlessly often.
private const val ACCESS_TOKEN_REFRESH_SKEW_MILLIS = 30_000L

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

    // The OIDC client (ADR 0017). Built here, not inside configureApp(), so warmUp() below and
    // the routes configureApp() wires up share the same Oidc instance — a second one constructed
    // from the same settings would warm a discovery document and JWKS cache that routing then
    // never sees.
    val authSettings = loadAuthSettings(environment.config)
    val oidcClient = HttpClient(OkHttp)
    monitor.subscribe(ApplicationStopping) { oidcClient.close() }
    val oidc = Oidc(authSettings, oidcClient)
    // Best-effort: Keycloak being unreachable must not fail this service's own startup
    // (docs/KEYCLOAK_SETUP.md §7 — /ready deliberately does not depend on it either).
    runBlocking { oidc.warmUp() }

    configureApp(
        graphDriver,
        appConfig.jira,
        windchillSettings = appConfig.windchill,
        importerSettings = appConfig.importer,
        importRunStore = importRunStore,
        oidc = oidc,
    )
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
    // Unconfigured by default. Unlike JIRA's, an absent host does not switch the source off: the
    // importer is fed by an upload, so it runs regardless, and the host decides only whether a
    // document row can link back into Windchill.
    windchillSettings: WindchillSettings = WindchillSettings(host = ""),
    importerSettings: ImporterSettings = ImporterSettings(),
    // Defaulted so a test of the HTTP surface gets a store that talks to the same driver every
    // other collaborator here does, and a test with no database can pass its own.
    importRunStore: com.sec.importer.ImportRunStore = GraphImportRunStore(graphDriver),
    // ADR 0017. Defaulted to an unconfigured instance (blank issuer, so discovery simply fails
    // and is retried, never crashes) plus a throwaway OkHttp client — fine for a test that never
    // exercises /auth/login or /auth/callback. module() always passes its own, warmed instance.
    oidc: Oidc = Oidc(AuthSettings(issuer = "", clientId = "", clientSecret = "", callbackUrl = ""), HttpClient(OkHttp)),
    // The session store (ADR 0017 §3: one process, in memory, a restart signs everyone out). A
    // test that needs to seed an authenticated request constructs its own and passes it in, the
    // same shape every other collaborator here takes.
    sessionStorage: SessionStorage = SessionStorageMemory(),
) {
    installSecSessions(sessionStorage)
    install(Authentication) {
        session<UserSession>(SessionNames.PROVIDER) {
            // Refreshed server-side, on use, with a small skew (spec §11) — never a silent
            // widening: a refresh failure clears the session and this returns null, which is a
            // 401 on this and every subsequent request until the user signs in again.
            validate { session ->
                if (System.currentTimeMillis() < session.accessTokenExpiresAtEpochMs - ACCESS_TOKEN_REFRESH_SKEW_MILLIS) {
                    return@validate session.toPrincipal()
                }
                val refreshed = oidc.refresh(session)
                if (refreshed == null) {
                    sessions.clear<UserSession>()
                    return@validate null
                }
                sessions.set(refreshed)
                refreshed.toPrincipal()
            }
            challenge {
                call.respondProblem(
                    HttpStatusCode.Unauthorized,
                    "Sign-in required",
                    "Your session has ended. Sign in again to continue.",
                )
            }
        }
    }

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
    val jiraLinkGraphProjection = JiraLinkGraphProjection(graphDriver)
    // Takes the settings because a row's link into Windchill is derived from the host on every read
    // — the export's own URL is an OData resource, and opening one shows raw JSON.
    val windchillProjection = WindchillProjection(graphDriver, windchillSettings)
    // access-control.md §5. One instance for the process so its cache is actually shared.
    val accessResolver = AccessResolver(graphDriver)

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

    // Registered unconditionally, and that is the difference between a fed importer and a connected
    // one: there is no host to be missing and no credential to be absent, so there is no state in
    // which uploading an export should not work.
    importRunService.register(WindchillImporter(WindchillGraphWriter(graphDriver)))

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
        jiraLinkGraphProjection,
        jiraColumnStore,
        jiraFieldsProjection,
        windchillSettings,
        windchillProjection,
        importRunService,
        oidc,
        accessResolver,
    )
}
