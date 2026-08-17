package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.ProblemType
import com.sec.api.decodeRef
import com.sec.api.dto.AccessCategoryDto
import com.sec.api.dto.AccessCategoryListResponseDto
import com.sec.api.dto.AccessReconcileResponseDto
import com.sec.api.dto.AccessReconcileSourceDto
import com.sec.api.dto.CreateAccessCategoryRequestDto
import com.sec.api.dto.AccessDefaultDto
import com.sec.api.dto.AccessDefaultsResponseDto
import com.sec.api.dto.AccessSummaryDto
import com.sec.api.dto.GroupListResponseDto
import com.sec.api.dto.GroupWithGrantsDto
import com.sec.api.dto.SaveAccessDefaultsRequestDto
import com.sec.api.dto.SaveDirectCategoriesRequestDto
import com.sec.api.dto.SaveDirectCategoriesResponseDto
import com.sec.api.dto.SaveGrantsRequestDto
import com.sec.api.dto.SetSeesAllRequestDto
import com.sec.api.dto.UnassignedContainerDto
import com.sec.api.dto.UnassignedContainersResponseDto
import com.sec.api.dto.UpdateAccessCategoryRequestDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.domain.AccessCategorySummary
import com.sec.domain.AccessDefaultEntry
import com.sec.domain.CreateCategoryOutcome
import com.sec.domain.DeleteCategoryOutcome
import com.sec.domain.GroupWithGrants
import com.sec.domain.Ref
import com.sec.domain.SaveDefaultsOutcome
import com.sec.domain.SaveDirectCategoriesOutcome
import com.sec.domain.SaveGrantsOutcome
import com.sec.domain.SetSeesAllOutcome
import com.sec.domain.UnassignedContainer
import com.sec.domain.UpdateCategoryOutcome
import com.sec.security.AccessAdminService
import com.sec.security.AccessContainment
import com.sec.security.AccessReconciler
import com.sec.security.SecPrincipal
import com.sec.security.auditName
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * The Access views' HTTP surface (spec §9, §10.2). Built one screen at a time —
 * `AccessAdminService` today only knows categories; groups/grants, containers and defaults follow
 * as later steps of the same phase.
 *
 * The whole group already sits inside `requireRole(Role.ACCESS_MANAGER) { accessRoutes(...) }` in
 * `Routes.kt`, so nothing registered here needs its own guard.
 */
public fun Route.accessRoutes(reconciler: AccessReconciler, adminService: AccessAdminService) {

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

        route("/categories") {
            get {
                call.respond(AccessCategoryListResponseDto(adminService.listCategories().map { it.toDto() }))
            }

            post {
                val principal = call.principal<SecPrincipal>()
                    ?: error("${ApiPaths.ACCESS_CATEGORIES} ran without a principal despite the session guard")
                val body = call.receive<CreateAccessCategoryRequestDto>()

                when (
                    val outcome = adminService.createCategory(
                        key = body.key,
                        name = body.name,
                        description = body.description ?: "",
                        everyGroup = body.everyGroup,
                        user = principal.auditName,
                    )
                ) {
                    is CreateCategoryOutcome.KeyInUse ->
                        call.respondProblem(
                            HttpStatusCode.Conflict,
                            "Category key already in use",
                            "A category with the key '${body.key}' already exists. Choose a different key.",
                            ProblemType.ACCESS_CATEGORY_KEY_IN_USE,
                        )

                    is CreateCategoryOutcome.Created ->
                        call.respond(HttpStatusCode.Created, outcome.category.toDto())
                }
            }

            patch("/{ref}") {
                val metaId = call.decodeRef() ?: return@patch call.respondInvalidRef()
                val principal = call.principal<SecPrincipal>()
                    ?: error("${ApiPaths.ACCESS_CATEGORIES}/{ref} ran without a principal despite the session guard")
                val body = call.receive<UpdateAccessCategoryRequestDto>()

                when (
                    val outcome = adminService.renameCategory(
                        metaId = metaId,
                        name = body.name,
                        description = body.description,
                        everyGroup = body.everyGroup,
                        user = principal.auditName,
                    )
                ) {
                    is UpdateCategoryOutcome.NotFound -> call.respondCategoryNotFound()
                    is UpdateCategoryOutcome.Updated -> call.respond(outcome.category.toDto())
                }
            }

            delete("/{ref}") {
                val metaId = call.decodeRef() ?: return@delete call.respondInvalidRef()

                when (val outcome = adminService.deleteCategory(metaId)) {
                    is DeleteCategoryOutcome.NotFound -> call.respondCategoryNotFound()

                    is DeleteCategoryOutcome.InUse ->
                        call.respondProblem(
                            HttpStatusCode.Conflict,
                            "Category still in use",
                            "This category is still granted to ${outcome.groupCount} group(s) and " +
                                "assigned to ${outcome.objectCount} object(s).",
                            ProblemType.ACCESS_CATEGORY_IN_USE,
                        )

                    is DeleteCategoryOutcome.Deleted -> call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        route("/groups") {
            get {
                call.respond(GroupListResponseDto(adminService.listGroups().map { it.toDto() }))
            }

            // The whole grant set for this group, one transaction (R7) — never a delta.
            put("/{ref}/grants") {
                val groupKey = call.decodeRef() ?: return@put call.respondInvalidRef()
                val principal = call.principal<SecPrincipal>()
                    ?: error("${ApiPaths.ACCESS_GROUPS}/{ref}/grants ran without a principal despite the session guard")
                val body = call.receive<SaveGrantsRequestDto>()

                val malformed = body.categoryRefs.filter { Ref.decodeOrNull(it) == null }
                if (malformed.isNotEmpty()) {
                    return@put call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid reference",
                        "Some category references in this request are not readable. Reload and try again.",
                        ProblemType.VALIDATION,
                    )
                }
                val categoryIds = body.categoryRefs.mapNotNull { Ref.decodeOrNull(it) }

                when (
                    val outcome = adminService.saveGrants(groupKey, categoryIds, user = principal.auditName)
                ) {
                    is SaveGrantsOutcome.GroupNotFound -> call.respondGroupNotFound()

                    is SaveGrantsOutcome.UnknownCategories ->
                        call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Unknown category",
                            "${outcome.categoryIds.size} of the categories in this request no longer exist. " +
                                "Reload and try again.",
                            ProblemType.VALIDATION,
                        )

                    is SaveGrantsOutcome.Saved -> call.respond(outcome.group.toDto())
                }
            }

            // seesAll only — "audited loudly" (spec §9); AccessAdminService is what logs it.
            patch("/{ref}") {
                val groupKey = call.decodeRef() ?: return@patch call.respondInvalidRef()
                val principal = call.principal<SecPrincipal>()
                    ?: error("${ApiPaths.ACCESS_GROUPS}/{ref} ran without a principal despite the session guard")
                val body = call.receive<SetSeesAllRequestDto>()

                when (
                    val outcome = adminService.setSeesAll(groupKey, body.seesAll, user = principal.auditName)
                ) {
                    is SetSeesAllOutcome.GroupNotFound -> call.respondGroupNotFound()
                    is SetSeesAllOutcome.Updated -> call.respond(outcome.group.toDto())
                }
            }
        }

        route("/containers") {
            // ?state=unassigned is the only state this screen has today (spec §10.2 screen 3);
            // named as a query parameter rather than assumed, so a second state has somewhere to
            // go without a route change.
            get {
                val state = call.request.queryParameters["state"] ?: "unassigned"
                if (state != "unassigned") {
                    return@get call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Unknown state",
                        "'state' must be 'unassigned', not '$state'.",
                        ProblemType.VALIDATION,
                    )
                }
                val source = call.request.queryParameters["source"]
                val q = call.request.queryParameters["q"]
                call.respond(
                    UnassignedContainersResponseDto(
                        adminService.listUnassignedContainers(source, q).map { it.toDto() },
                    ),
                )
            }

            put("/{ref}/categories") {
                call.handleSaveDirectCategories(adminService::saveContainerCategories)
            }
        }

        route("/items") {
            // The single-item escape hatch (spec §8.1) — the exact same write as the containers
            // route above, on an item instead of a container.
            put("/{ref}/categories") {
                call.handleSaveDirectCategories(adminService::saveItemCategories)
            }
        }

        route("/defaults") {
            get {
                call.respond(AccessDefaultsResponseDto(adminService.listDefaults().map { it.toDto() }))
            }

            put {
                val principal = call.principal<SecPrincipal>()
                    ?: error("${ApiPaths.ACCESS_DEFAULTS} ran without a principal despite the session guard")
                val body = call.receive<SaveAccessDefaultsRequestDto>()

                val malformed = body.defaults.mapNotNull { it.categoryRef }.filter { Ref.decodeOrNull(it) == null }
                if (malformed.isNotEmpty()) {
                    return@put call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid reference",
                        "Some category references in this request are not readable. Reload and try again.",
                        ProblemType.VALIDATION,
                    )
                }
                val entries = body.defaults.map {
                    AccessDefaultEntry(it.sourceId, it.containerLabel, it.categoryRef?.let(Ref::decodeOrNull))
                }

                when (val outcome = adminService.saveDefaults(entries, user = principal.auditName)) {
                    is SaveDefaultsOutcome.UnknownSourceContainerPair ->
                        call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Unknown source or container type",
                            "'${outcome.pairs.joinToString { (s, c) -> "$s/$c" }}' is not a registered " +
                                "source and container type.",
                            ProblemType.VALIDATION,
                        )

                    is SaveDefaultsOutcome.UnknownCategories ->
                        call.respondProblem(
                            HttpStatusCode.BadRequest,
                            "Unknown category",
                            "${outcome.categoryIds.size} of the categories in this request no longer exist. " +
                                "Reload and try again.",
                            ProblemType.VALIDATION,
                        )

                    is SaveDefaultsOutcome.Saved ->
                        call.respond(AccessDefaultsResponseDto(outcome.defaults.map { it.toDto() }))
                }
            }
        }

        get("/summary") {
            val summary = adminService.summary()
            call.respond(
                AccessSummaryDto(
                    categoryCount = summary.categoryCount,
                    groupCount = summary.groupCount,
                    unassignedContainerCount = summary.unassignedContainerCount,
                ),
            )
        }
    }
}

private suspend fun ApplicationCall.handleSaveDirectCategories(
    write: suspend (anchorId: String, categoryIds: List<String>, user: String) -> SaveDirectCategoriesOutcome,
) {
    val anchorId = decodeRef() ?: return respondInvalidRef()
    val principal = principal<SecPrincipal>()
        ?: error("${request.local.uri} ran without a principal despite the session guard")
    val body = receive<SaveDirectCategoriesRequestDto>()

    val malformed = body.categoryRefs.filter { Ref.decodeOrNull(it) == null }
    if (malformed.isNotEmpty()) {
        return respondProblem(
            HttpStatusCode.BadRequest,
            "Invalid reference",
            "Some category references in this request are not readable. Reload and try again.",
            ProblemType.VALIDATION,
        )
    }
    val categoryIds = body.categoryRefs.mapNotNull { Ref.decodeOrNull(it) }

    when (val outcome = write(anchorId, categoryIds, principal.auditName)) {
        is SaveDirectCategoriesOutcome.AnchorNotFound ->
            respondProblem(HttpStatusCode.NotFound, "Not found", "No object or container for this reference.")

        is SaveDirectCategoriesOutcome.UnknownCategories ->
            respondProblem(
                HttpStatusCode.BadRequest,
                "Unknown category",
                "${outcome.categoryIds.size} of the categories in this request no longer exist. " +
                    "Reload and try again.",
                ProblemType.VALIDATION,
            )

        is SaveDirectCategoriesOutcome.Saved ->
            respond(SaveDirectCategoriesResponseDto(outcome.categoryIds.map { Ref.encode(it) }))
    }
}

private fun AccessCategorySummary.toDto(): AccessCategoryDto = AccessCategoryDto(
    ref = Ref.encode(metaId),
    key = key,
    name = name,
    description = description,
    everyGroup = everyGroup,
    objectCount = objectCount,
    groupCount = groupCount,
)

private fun AccessDefaultEntry.toDto(): AccessDefaultDto = AccessDefaultDto(
    sourceId = sourceId,
    containerLabel = containerLabel,
    categoryRef = categoryId?.let { Ref.encode(it) },
)

private fun UnassignedContainer.toDto(): UnassignedContainerDto = UnassignedContainerDto(
    ref = Ref.encode(containerId),
    sourceId = sourceId,
    name = name,
    invisibleItemCount = invisibleItemCount,
)

private fun GroupWithGrants.toDto(): GroupWithGrantsDto = GroupWithGrantsDto(
    ref = Ref.encode(key),
    key = key,
    name = name,
    seesAll = seesAll,
    categoryRefs = categoryIds.map { Ref.encode(it) },
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

private suspend fun ApplicationCall.respondCategoryNotFound(): Unit =
    respondProblem(HttpStatusCode.NotFound, "Category not found", "No access category for this reference.")

private suspend fun ApplicationCall.respondGroupNotFound(): Unit =
    respondProblem(
        HttpStatusCode.NotFound,
        "Group not found",
        "No group for this reference — it may not have signed in yet.",
    )
