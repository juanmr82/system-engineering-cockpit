package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.api.dto.SystemLevelsResponseDto
import com.sec.domain.SystemLevel
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

// Read-only configuration served to the frontend. These are vocabularies and structure that are
// the same for every user and change only when we ship (CLAUDE.md §2, "Where state lives"), so
// they are cacheable and never come from the graph.
public fun Route.configRoutes() {
    route(ApiPaths.CONFIG) {
        get("/system-levels") {
            call.response.header(HttpHeaders.CacheControl, "max-age=3600")
            call.respond(
                SystemLevelsResponseDto(
                    SystemLevel.entries.map { SystemLevelOptionDto(it.code, it.label) },
                ),
            )
        }

        // GET /navigation — sidenav structure from application.yaml, still to be wired.
    }
}
