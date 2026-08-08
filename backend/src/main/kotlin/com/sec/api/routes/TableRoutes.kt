package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.decodeRef
import com.sec.api.dto.ModuleTablesResponseDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.DoorsTableProjection
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * DOORS table rendering (docs/DOORS_TABLES.md §4.3).
 *
 * ```
 * GET /api/v1/modules/{ref}/tables
 * GET /api/v1/items/{ref}/table      # ref may be the table, a row, or any cell
 * ```
 *
 * **No parameters, deliberately.** The spec's `?attrs=` carried the view's display columns so that
 * an attribute value sitting on a cell object could be shown beside the table (§6.3). A table shows
 * its cells' `Object Text` and nothing else, so there is nothing for the caller to ask for and no
 * per-request variation to bound.
 */
public fun Route.tableRoutes(
    doorsProjection: DoorsProjection,
    tableProjection: DoorsTableProjection,
) {
    route("${ApiPaths.MODULES}/${ApiPaths.REF}/tables") {
        get {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            if (!doorsProjection.moduleExists(moduleId)) {
                return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Module not found",
                    "No module for this reference.",
                )
            }
            call.respond(ModuleTablesResponseDto(tableProjection.getModuleTables(moduleId)))
        }
    }

    route("${ApiPaths.ITEMS}/${ApiPaths.REF}/table") {
        get {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val table = tableProjection.getTableFor(itemId)
                ?: return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Object not found",
                    "No object for this reference.",
                )
            call.respond(table)
        }
    }
}
