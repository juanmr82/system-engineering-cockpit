package com.sec.api

import com.sec.api.routes.accessRoutes
import com.sec.api.routes.authRoutes
import com.sec.api.routes.configRoutes
import com.sec.api.routes.healthRoutes
import com.sec.api.routes.importRoutes
import com.sec.api.routes.jiraRoutes
import com.sec.api.routes.moduleRoutes
import com.sec.api.routes.reviewRoutes
import com.sec.api.routes.statisticsRoutes
import com.sec.api.routes.tableRoutes
import com.sec.api.routes.windchillRoutes
import com.sec.config.JiraSettings
import com.sec.config.WindchillSettings
import com.sec.graph.GraphDriver
import com.sec.importer.ImportRunService
import com.sec.importer.ImportScheduler
import com.sec.meta.MetaWriter
import com.sec.security.AccessReconciler
import com.sec.security.AccessResolver
import com.sec.security.Oidc
import com.sec.security.requireSecSession
import com.sec.source.jira.JiraColumnStore
import com.sec.source.jira.JiraFieldsProjection
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraLinkGraphProjection
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.DoorsTableProjection
import com.sec.source.doors.ReviewProjection
import com.sec.source.doors.StatisticsProjection
import com.sec.source.windchill.WindchillProjection
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

// The table of contents for the HTTP surface. Each feature owns a file under api/routes/;
// nothing but registration lives here, and collaborators are constructed in Application.module()
// and passed in, so routing owns no object lifecycles.
//
// Still to come (CLAUDE.md §5 "API shape"):
//   GET  /api/v1/tree
//   GET  /api/v1/items/{ref}/children, /annotations
//   POST /api/v1/items/{ref}/annotations, PATCH|DELETE /api/v1/annotations/{ref}
//   GET  /api/v1/config/navigation
//   GET  /api/v1/modules/{ref}/checks/attribute-policy
//   POST /api/v1/cypher/explain, /api/v1/cypher/run   (docs/CYPHER_API_DESIGN.md)
public fun Application.configureRouting(
    graphDriver: GraphDriver,
    doorsProjection: DoorsProjection,
    reviewProjection: ReviewProjection,
    breakdownProjection: BreakdownProjection,
    dependencyGraphProjection: DependencyGraphProjection,
    metaWriter: MetaWriter,
    statisticsProjection: StatisticsProjection,
    tableProjection: DoorsTableProjection,
    jiraSettings: JiraSettings,
    // Null when JIRA is not configured on this deployment, which is a normal state: the routes
    // answer 503 and /jira/health reports why. See api/routes/JiraRoutes.kt.
    jiraClient: JiraHttpClient?,
    // Never null, and for a stronger reason than the client's: the issues it reads are in this
    // graph, so the table works on a deployment whose JIRA credentials have expired. A table that
    // went blank because a token did would be reporting a connection problem as an absence of data.
    jiraIssuesProjection: JiraIssuesProjection,
    // The related-issues picture. Reads links this graph already holds; never reaches JIRA.
    jiraLinkGraphProjection: JiraLinkGraphProjection,
    // The chosen columns and the catalogue they are chosen from. Neither reaches JIRA, so both
    // answer on a deployment whose token has expired — a column choice is ours, not JIRA's.
    jiraColumnStore: JiraColumnStore,
    jiraFieldsProjection: JiraFieldsProjection,
    // Carries the host and nothing else — this source has no credential, because it is fed by an
    // uploaded export rather than connected to. Unconfigured only removes the link out.
    windchillSettings: WindchillSettings,
    // Reads documents this graph already holds, so it answers whether or not a host is configured.
    windchillProjection: WindchillProjection,
    // Source-agnostic: it holds whichever importers were registered, and answers the same five
    // endpoints for each of them.
    importRunService: ImportRunService,
    // ADR 0017. authRoutes() is the one file allowed to register anything unauthenticated besides
    // health/ready — it draws that line itself, in one place (security/Session.kt's doc comment).
    oidc: Oidc,
    // access-control.md §5/§6.3. One instance for the process, so its cache is actually shared
    // across requests rather than reset per route.
    accessResolver: AccessResolver,
    // §8.3. The same instance the import-pipeline hook and the startup pass use, so a manual
    // reconcile and an automatic one are never racing two independent views of "already seeded".
    accessReconciler: AccessReconciler,
    // ADR 0018. Source-agnostic: whichever importers are on a schedule, keyed by their own id.
    // Empty when nothing is scheduled.
    importSchedulers: Map<String, ImportScheduler> = emptyMap(),
) {
    routing {
        // The declared exceptions (docs/features/access-control.md §9 "Guarding, once"):
        // /health, /ready, and the two of /auth/* that create a session rather than needing one.
        healthRoutes(graphDriver)
        authRoutes(oidc)

        // Every other route needs a session (ADR 0017 §5) and the CSRF check on every non-GET
        // (§11). One wrapper, so a feature route file registered here is guarded whether or not
        // whoever adds it remembers to ask for that — requireSecSession() is `Session.kt`'s single
        // declaration of both rules, the same discipline GraphNamesTest holds Cypher names to.
        requireSecSession {
            jiraRoutes(
                jiraSettings,
                jiraClient,
                jiraIssuesProjection,
                jiraLinkGraphProjection,
                jiraColumnStore,
                jiraFieldsProjection,
            )
            windchillRoutes(windchillSettings, windchillProjection, importRunService)
            importRoutes(importRunService, importSchedulers)
            moduleRoutes(doorsProjection, metaWriter)
            reviewRoutes(
                doorsProjection,
                reviewProjection,
                breakdownProjection,
                dependencyGraphProjection,
                metaWriter,
                accessResolver,
            )
            statisticsRoutes(statisticsProjection)
            tableRoutes(doorsProjection, tableProjection)
            configRoutes()
            accessRoutes(accessReconciler)
        }

        // Outside the session guard on purpose: an unmatched path is not an object and not a
        // capability (R8's 404-vs-403 split does not apply to "nothing matched"), and this is also
        // what serves the packaged Angular shell to a browser that has no session yet — the app
        // loads, then its first API call gets the 401 that sends it to Keycloak. Registered last so
        // it reads as the fallback it is; Ktor scores it lowest regardless.
        notFoundFallback()
    }
}
