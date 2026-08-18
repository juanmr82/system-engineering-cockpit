package com.sec

import com.sec.api.configureProblemDetails
import com.sec.api.configureRouting
import com.sec.api.respondProblem
import com.sec.config.AuthSettings
import com.sec.config.ConfigArgs
import com.sec.config.ImporterSettings
import com.sec.config.JiraSettings
import com.sec.config.NavigationSettings
import com.sec.config.ServerSettings
import com.sec.config.WindchillSettings
import com.sec.config.loadAppConfig
import com.sec.config.loadAuthSettings
import com.sec.graph.GraphDriver
import com.sec.importer.GraphImportRunStore
import com.sec.importer.ImportRunService
import com.sec.importer.ImportScheduler
import com.sec.meta.MetaSchema
import com.sec.meta.MetaWriter
import com.sec.security.AccessAdminService
import com.sec.security.AccessReconciler
import com.sec.security.AccessResolver
import com.sec.security.DoorsPushNotConfiguredException
import com.sec.security.KeycloakUnavailableException
import com.sec.security.Oidc
import com.sec.security.PushAuthNames
import com.sec.security.SessionNames
import com.sec.security.UserDirectory
import com.sec.security.UserSession
import com.sec.security.installSecSessions
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsGraphWriter
import com.sec.source.doors.DoorsImporter
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
import io.ktor.server.auth.bearer
import io.ktor.server.auth.session
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.sessions.SessionStorage
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Duration
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

    // access-control.md §8.3, the startup pass: catches a category change made while this process
    // was down, and — for DOORS and Cameo, which run out-of-process — a run whose own call to
    // POST /access/reconcile never landed. Best-effort like oidc.warmUp() below: a failure here
    // leaves objects under-visible rather than over-visible (R8's correct failure direction), so it
    // must not be allowed to fail the boot a working database and a broken reconcile would both
    // otherwise still deserve.
    val accessReconciler = AccessReconciler(graphDriver)
    runCatching { runBlocking { accessReconciler.reconcileAll() } }
        .onFailure { logger.warn(it) { "Startup access reconcile failed; retrying at the next import or manual call" } }

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
        serverSettings = appConfig.server,
        windchillSettings = appConfig.windchill,
        importerSettings = appConfig.importer,
        navigationSettings = appConfig.navigation,
        importRunStore = importRunStore,
        oidc = oidc,
        accessReconciler = accessReconciler,
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
    // Off by default, exactly as the packaged application.yaml has it — a test of the HTTP surface
    // gets the posture of a process nobody has put behind a proxy (ADR 0021).
    serverSettings: ServerSettings = ServerSettings(),
    // Unconfigured by default. Unlike JIRA's, an absent host does not switch the source off: the
    // importer is fed by an upload, so it runs regardless, and the host decides only whether a
    // document row can link back into Windchill.
    windchillSettings: WindchillSettings = WindchillSettings(host = ""),
    importerSettings: ImporterSettings = ImporterSettings(),
    // The sidenav's structure. Empty by default so a test of the HTTP surface gets a working
    // (if navless) app rather than needing application.yaml's full navigation block in scope.
    navigationSettings: NavigationSettings = NavigationSettings(groups = emptyList()),
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
    // §8.3. module() builds its own and runs the startup pass against it before this is called;
    // defaulted here so a test of the HTTP surface gets one wired to the same driver everything
    // else in this function is, without needing to run a reconcile pass of its own first.
    accessReconciler: AccessReconciler = AccessReconciler(graphDriver),
) {
    // Before everything else, so CallId, CallLogging and every handler below see the caller's own
    // address rather than the proxy's (ADR 0021). Installed only when the deployment says it is
    // proxied: the plugin trusts X-Forwarded-For from whoever sent it, so on a directly reachable
    // port it would let a caller choose what this application logs about it.
    if (serverSettings.behindProxy) {
        install(XForwardedHeaders)
        logger.info { "Trusting X-Forwarded-* headers; this port must not be reachable except through the proxy" }
    }

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

        // The DOORS push front door (ADR 0020): a bearer access token from the sec-doors-push
        // Keycloak client, not a session cookie — a second, independent provider, never composed
        // with SessionNames.PROVIDER on the same route. Unlike `session<UserSession>` above,
        // BearerAuthenticationProvider has no `challenge { }` hook to override in this Ktor
        // version: every rejection (no header, wrong scheme, or `authenticate` returning null)
        // answers Ktor's own bare 401 with a `WWW-Authenticate: Bearer` header, not this
        // application's usual RFC 9457 problem-detail body. Accepted as a deliberate, narrow
        // exception for a route with no browser or human reader on the other end — the standard
        // challenge header is exactly the machine-readable signal a scripted caller needs, and
        // hand-rolling this provider the way `requireSecSession`/`requireRole` are hand-rolled
        // would trade a cosmetic gain for custom authentication-state plumbing this codebase
        // otherwise avoids. `oidc.validatePushAccessToken`'s own service-level failures
        // (Keycloak unreachable, the feature unconfigured) are deliberately not swallowed here —
        // they propagate to StatusPages, which already answers both as RFC 9457 problem details.
        bearer(PushAuthNames.PROVIDER) {
            authenticate { credential ->
                try {
                    oidc.validatePushAccessToken(credential.token)
                } catch (cause: KeycloakUnavailableException) {
                    throw cause
                } catch (cause: DoorsPushNotConfiguredException) {
                    throw cause
                } catch (cause: Exception) {
                    null
                }
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
        // The caller's address, as a structured field beside callId rather than inside the
        // message — the production encoder emits the MDC as JSON (logback-production.xml), so a
        // log search can filter on it, which it cannot do with text interpolated into a sentence.
        //
        // This is the half of ADR 0021 that makes `ktor-server-forwarded-header` worth having:
        // the plugin corrects `origin`, but CallLogging's default format logs no address at all,
        // so on its own it changes nothing anybody can see. With behindProxy off this is the
        // socket's own peer — correct, just less interesting.
        //
        // remoteAddress, not remoteHost: the latter may be a name, and a name in an audit log is
        // a name that needed resolving at some point.
        mdc("clientIp") { call -> call.request.origin.remoteAddress }
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
    // read requirement cards from here (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §5.1).
    val cardProjection = RequirementCardProjection(graphDriver)
    val breakdownProjection = BreakdownProjection(graphDriver, cardProjection)
    val dependencyGraphProjection = DependencyGraphProjection(graphDriver, cardProjection)
    val metaWriter = MetaWriter(graphDriver, doorsProjection)
    // The :User display-name cache (docs/req-review-comment-threads.md §2.2) — not :__Meta, so it
    // is written by its own small class rather than through metaWriter.
    val userDirectory = UserDirectory(graphDriver)
    val statisticsProjection = StatisticsProjection(graphDriver)
    val tableProjection = DoorsTableProjection(graphDriver)
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
    // Phase 6, §9/§10.2. Shares accessResolver so its invalidate() calls land on the same cache
    // accessSet(accessResolver) reads from on every filtered request.
    val accessAdminService = AccessAdminService(graphDriver, accessResolver)

    // One service for every source. DOORS and Windchill register here too when their importers
    // move in-process; today JIRA is the only one, because it is the only one that can run inside
    // this JVM at all (ADR 0013).
    val importRunService = ImportRunService(
        importRunStore,
        importerSettings.runHistoryLimit,
        accessReconciler = accessReconciler,
    )
    monitor.subscribe(ApplicationStopping) { importRunService.close() }

    // ADR 0018: keyed by importerId so ImportRoutes stays source-agnostic. Empty when JIRA is not
    // configured, or its schedule is off (jira.scheduleMinutes: 0).
    var jiraScheduler: ImportScheduler? = null

    if (jiraClient != null) {
        logger.info { "JIRA integration enabled for ${jiraSettings.host}" }
        monitor.subscribe(ApplicationStopping) { jiraClient.close() }
        importRunService.register(
            JiraImporter(jiraSettings, jiraClient, JiraGraphWriter(graphDriver, jiraSettings.host)),
        )

        if (jiraSettings.scheduleMinutes > 0) {
            jiraScheduler = ImportScheduler(
                JiraImporter.ID,
                Duration.ofMinutes(jiraSettings.scheduleMinutes.toLong()),
                importRunService,
            ).also { it.start() }
            monitor.subscribe(ApplicationStopping) { jiraScheduler?.close() }
            logger.info { "JIRA re-imports every ${jiraSettings.scheduleMinutes} minute(s)" }
        }
    } else {
        logger.info { "JIRA integration is not configured; /api/v1/jira routes will report so" }
    }
    val importSchedulers: Map<String, ImportScheduler> =
        listOfNotNull(jiraScheduler).associateBy { it.importerId }

    // Registered unconditionally, and that is the difference between a fed importer and a connected
    // one: there is no host to be missing and no credential to be absent, so there is no state in
    // which uploading an export should not work.
    importRunService.register(WindchillImporter(WindchillGraphWriter(graphDriver)))

    // ADR 0019: a second DOORS importer, fed by an upload — the Python one is unaffected and keeps
    // running out-of-process. One writer instance, shared between the importer and the route's own
    // pre-run gate (DoorsGraphWriter.gate), so both read the same graph through the same collaborator.
    val doorsGraphWriter = DoorsGraphWriter(graphDriver)
    importRunService.register(DoorsImporter(doorsGraphWriter))

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
        jiraIssuesProjection,
        jiraLinkGraphProjection,
        jiraColumnStore,
        jiraFieldsProjection,
        windchillSettings,
        windchillProjection,
        doorsGraphWriter::gate,
        navigationSettings,
        importRunService,
        oidc,
        userDirectory,
        accessResolver,
        accessReconciler,
        accessAdminService,
        importSchedulers,
    )
}
