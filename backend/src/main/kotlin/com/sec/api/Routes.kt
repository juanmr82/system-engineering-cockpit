package com.sec.api

import com.sec.api.routes.configRoutes
import com.sec.api.routes.healthRoutes
import com.sec.api.routes.moduleRoutes
import com.sec.api.routes.reviewRoutes
import com.sec.api.routes.statisticsRoutes
import com.sec.graph.GraphDriver
import com.sec.meta.MetaWriter
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.ReviewProjection
import com.sec.source.doors.StatisticsProjection
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
    metaWriter: MetaWriter,
    statisticsProjection: StatisticsProjection,
) {
    routing {
        healthRoutes(graphDriver)
        moduleRoutes(doorsProjection, metaWriter)
        reviewRoutes(doorsProjection, reviewProjection, breakdownProjection, metaWriter)
        statisticsRoutes(statisticsProjection)
        configRoutes()

        // Registered last so it reads as the fallback it is; Ktor scores it lowest regardless.
        notFoundFallback()
    }
}
