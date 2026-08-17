package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.dto.NavGroupDto
import com.sec.api.dto.NavItemDto
import com.sec.api.dto.NavigationResponseDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.api.dto.SystemLevelsResponseDto
import com.sec.config.NavigationSettings
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
public fun Route.configRoutes(navigationSettings: NavigationSettings) {
    route(ApiPaths.CONFIG) {
        get("/system-levels") {
            call.response.header(HttpHeaders.CacheControl, "max-age=3600")
            call.respond(
                SystemLevelsResponseDto(
                    SystemLevel.entries.map { SystemLevelOptionDto(it.code, it.label) },
                ),
            )
        }

        // The sidenav's own structure (frontend/CLAUDE.md §9: "rendered from a typed NavGroup[]
        // fetched from GET /api/v1/config/navigation — never from hand-written markup, and never
        // from the graph"). Cacheable for the same reason /system-levels is: it changes only when
        // this backend is redeployed with a new application.yaml, never at runtime.
        get("/navigation") {
            call.response.header(HttpHeaders.CacheControl, "max-age=3600")
            call.respond(
                NavigationResponseDto(
                    navigationSettings.groups.map { group ->
                        NavGroupDto(
                            key = group.key,
                            label = group.label,
                            items = group.items.map { NavItemDto(it.key, it.label, it.route) },
                        )
                    },
                ),
            )
        }
    }
}
