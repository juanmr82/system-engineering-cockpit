package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.ProblemType
import com.sec.api.dto.AccessReconcileResponseDto
import com.sec.api.dto.AccessReconcileSourceDto
import com.sec.api.respondProblem
import com.sec.security.AccessContainment
import com.sec.security.AccessReconciler
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * The Access views' HTTP surface (spec §9). Only reconcile exists before phase 6 —
 * `AccessAdminService` (categories, grants, containers, defaults) is not built yet, so there is
 * nothing for the rest of this route group to read or write.
 *
 * Not yet `requireRole(Role.ACCESS_MANAGER)`-guarded: that plugin arrives with phase 5's write
 * guards, the same standing gap every other route in this backend has today (ADR 0014 point 9).
 * `sec-import-doors.ps1`'s own call to this endpoint is the reason it must eventually accept a
 * machine credential rather than only a browser session — an open question for that phase, not
 * this one, and named rather than worked around here.
 */
public fun Route.accessRoutes(reconciler: AccessReconciler) {

    route(ApiPaths.ACCESS) {

        /**
         * `?scope=all` (default) reconciles every registered source; `?scope=source&source=<id>`
         * reconciles one — what the import-pipeline hook and `sec-import-doors.ps1` both ask for,
         * scoped to the source that just ran (§8.3 "Scope it").
         */
        post("/reconcile") {
            val scope = call.request.queryParameters["scope"] ?: "all"

            val containments = when (scope) {
                "all" -> AccessContainment.all

                "source" -> {
                    val sourceId = call.request.queryParameters["source"]
                    val containment = AccessContainment.all.find { it.sourceId == sourceId }
                    if (containment == null) {
                        call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Unknown source",
                            "'$sourceId' is not a registered source. Known sources: " +
                                AccessContainment.all.joinToString { it.sourceId } + ".",
                            ProblemType.VALIDATION,
                        )
                        return@post
                    }
                    listOf(containment)
                }

                else -> {
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Unknown scope",
                        "'scope' must be 'all' or 'source', not '$scope'.",
                        ProblemType.VALIDATION,
                    )
                    return@post
                }
            }

            val results = reconciler.reconcileAll(containments)
            call.respond(
                AccessReconcileResponseDto(
                    results.map {
                        AccessReconcileSourceDto(it.sourceId, it.propagated, it.retracted, it.seeded)
                    },
                ),
            )
        }
    }
}
