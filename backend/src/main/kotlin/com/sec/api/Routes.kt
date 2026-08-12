package com.sec.api

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
import com.sec.meta.MetaWriter
import com.sec.source.jira.JiraColumnStore
import com.sec.source.jira.JiraFieldsProjection
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraLinkGraphProjection
import com.sec.source.jira.JiraIssuesProjection
import com.sec.source.jira.JiraSettingsStore
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
    // Never null, unlike the client: the configured project list lives in the graph and is readable
    // and editable whether or not this deployment has a JIRA host, which is what lets an operator
    // set the two up in either order.
    jiraSettingsStore: JiraSettingsStore,
    // Also never null, and for a stronger reason than the store's: the issues it reads are in this
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
) {
    routing {
        healthRoutes(graphDriver)
        jiraRoutes(
            jiraSettings,
            jiraClient,
            jiraSettingsStore,
            jiraIssuesProjection,
            jiraLinkGraphProjection,
            jiraColumnStore,
            jiraFieldsProjection,
        )
        windchillRoutes(windchillSettings, windchillProjection, importRunService)
        importRoutes(importRunService)
        moduleRoutes(doorsProjection, metaWriter)
        reviewRoutes(
            doorsProjection,
            reviewProjection,
            breakdownProjection,
            dependencyGraphProjection,
            metaWriter,
        )
        statisticsRoutes(statisticsProjection)
        tableRoutes(doorsProjection, tableProjection)
        configRoutes()

        // Registered last so it reads as the fallback it is; Ktor scores it lowest regardless.
        notFoundFallback()
    }
}
