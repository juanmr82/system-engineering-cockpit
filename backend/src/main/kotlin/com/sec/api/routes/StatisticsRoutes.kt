package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.respondProblem
import com.sec.domain.Ref
import com.sec.source.doors.StatisticsProjection
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * The Statistics view's read surface (`docs/features/requirements-statistics.md` §9).
 *
 * Two endpoints, not one: Band 4 scans the whole `refersTo` edge set, and the other three bands
 * must paint without waiting on it (§7.4). Splitting them also means a timeout in loop detection
 * degrades one band instead of the page.
 *
 * Both are pure reads. Nothing in this feature writes to the graph, which is asserted directly
 * rather than assumed (§13 criterion 14).
 */
public fun Route.statisticsRoutes(statisticsProjection: StatisticsProjection) {
    route("${ApiPaths.STATISTICS}/requirements") {
        get {
            when (val scope = call.moduleScope()) {
                is ModuleScope.Malformed -> call.respondMalformedModule()

                is ModuleScope.All ->
                    call.respond(requireNotNull(statisticsProjection.getStatistics(null)))

                is ModuleScope.One -> {
                    val statistics = statisticsProjection.getStatistics(scope.moduleId)
                        ?: return@get call.respondModuleGone()
                    call.respond(statistics)
                }
            }
        }

        get("/cycles") {
            when (val scope = call.moduleScope()) {
                is ModuleScope.Malformed -> call.respondMalformedModule()
                is ModuleScope.All -> call.respond(statisticsProjection.getCycles(null))
                is ModuleScope.One -> call.respond(statisticsProjection.getCycles(scope.moduleId))
            }
        }
    }
}

/**
 * The scope the request asks for.
 *
 * `module` is a **query** parameter here rather than a path segment, because "all modules" is a
 * first-class scope and not a missing resource — `/statistics/requirements` is a real answer, and
 * a path-scoped shape would have had to invent a sentinel segment to say so.
 *
 * Decoding is total: a hand-edited address bar is a 400, never an uncaught exception reported as
 * a 500 (R5, and the same contract `RefParam.kt` holds for path refs).
 */
private sealed interface ModuleScope {
    data object All : ModuleScope
    data object Malformed : ModuleScope
    data class One(val moduleId: String) : ModuleScope
}

private fun ApplicationCall.moduleScope(): ModuleScope {
    val raw = request.queryParameters["module"]?.takeIf { it.isNotBlank() } ?: return ModuleScope.All
    return Ref.decodeOrNull(raw)?.let(ModuleScope::One) ?: ModuleScope.Malformed
}

private suspend fun ApplicationCall.respondMalformedModule(): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid reference",
        "The module reference in this address is not readable. Choose the module from the list instead.",
    )

// Distinct wording from the malformed case on purpose: a readable handle for a module that is not
// there means the module was removed or never imported, and telling the two apart is what stops a
// reader retyping a URL that was never wrong.
private suspend fun ApplicationCall.respondModuleGone(): Unit =
    respondProblem(
        HttpStatusCode.NotFound,
        "Module not found",
        "No module for this reference. It may not have been imported yet.",
    )
