package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.decodeRef
import com.sec.api.dto.CommentDto
import com.sec.api.dto.SaveCommentsRequestDto
import com.sec.api.dto.SaveCommentsResponseDto
import com.sec.api.dto.SavedCommentDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.domain.GraphDirection
import com.sec.domain.GraphLevelStrategy
import com.sec.domain.Ref
import com.sec.domain.SaveCommentsOutcome
import com.sec.meta.MetaWriter
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.ReviewProjection
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// The Req review table (docs/REQ_REVIEW.md §8). The objects endpoint is paged and capped; the
// comment endpoint is feature-shaped for the reason CLAUDE.md §5 allows — a table save is one
// transaction, not N annotation calls — and routes through the same guarded meta writer.
public fun Route.reviewRoutes(
    doorsProjection: DoorsProjection,
    reviewProjection: ReviewProjection,
    breakdownProjection: BreakdownProjection,
    dependencyGraphProjection: DependencyGraphProjection,
    metaWriter: MetaWriter,
) {
    route("${ApiPaths.MODULES}/${ApiPaths.REF}") {
        get("/objects") {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            if (!doorsProjection.moduleExists(moduleId)) {
                return@get call.respondModuleNotFound()
            }

            val skip = call.intParam("skip", default = 0, min = 0) ?: return@get call.respondBadPaging()
            val limit = call.intParam("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)
                ?: return@get call.respondBadPaging()

            call.respond(reviewProjection.getModuleObjects(moduleId, skip = skip, limit = limit))
        }

        // The save icon: every dirty comment for this module, one request, one transaction (§5.2).
        post("/comments") {
            val moduleId = call.decodeRef() ?: return@post call.respondInvalidRef()
            val body = call.receive<SaveCommentsRequestDto>()

            // Refs are decoded here, before the writer sees them, so a malformed handle is a 400
            // rather than an item that mysteriously does not exist.
            val malformed = body.comments.filter { Ref.decodeOrNull(it.ref) == null }.map { it.ref }
            if (malformed.isNotEmpty()) {
                return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "Invalid reference",
                    "Some references in this request are not readable. Reload the module and try again.",
                )
            }

            val edits = body.comments.mapNotNull { edit ->
                Ref.decodeOrNull(edit.ref)?.let { MetaWriter.CommentEditInput(itemId = it, text = edit.text) }
            }

            when (val outcome = metaWriter.saveComments(moduleId, edits)) {
                is SaveCommentsOutcome.ModuleNotFound ->
                    call.respondModuleNotFound()

                is SaveCommentsOutcome.MalformedRefs ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid reference",
                        "Some references in this request are not readable.",
                    )

                is SaveCommentsOutcome.UnknownItems ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Unknown object",
                        "${outcome.refs.size} of the commented objects are not in this module. " +
                            "Reload the module and try again.",
                    )

                is SaveCommentsOutcome.Saved ->
                    call.respond(
                        SaveCommentsResponseDto(
                            saved = outcome.comments.map { saved ->
                                SavedCommentDto(
                                    ref = Ref.encode(saved.itemId),
                                    // A cleared comment comes back as null, which is what tells the
                                    // table its node was deleted rather than stored empty.
                                    comment = saved.metaId?.let {
                                        CommentDto(
                                            metaId = it,
                                            text = saved.text.orEmpty(),
                                            updatedAt = saved.updatedAt,
                                        )
                                    },
                                )
                            },
                        ),
                    )
            }
        }
    }

    route("${ApiPaths.ITEMS}/${ApiPaths.REF}") {
        get {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val detail = reviewProjection.getItemDetail(itemId)
                ?: return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Object not found",
                    "No object for this reference.",
                )
            call.respond(detail)
        }

        // ?direction=in returns incoming links, which are incomplete by design — the response
        // carries `complete: false` so no consumer can read an empty list as "orphan requirement".
        get("/traces") {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val incoming = call.request.queryParameters["direction"].equals("in", ignoreCase = true)
            call.respond(reviewProjection.getTraces(itemId, incoming = incoming))
        }

        /**
         * The Breakdown tab (docs/requirement-breakdown-tree.md §6).
         *
         * The traversal is server-side because assembling it from N calls to `/traces` would mean
         * an unbounded number of round trips for a tree that is legitimately dozens of nodes.
         *
         * Both bounds are validated rather than passed through, and both have a server-side
         * default, so a client that omits them — or sends nonsense — still cannot ask for an
         * unbounded walk. This is the one endpoint where a single click reaches an arbitrary
         * amount of the graph, and Community has no query governor (CLAUDE.md §7).
         */
        get("/breakdown") {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()

            val maxDepth = call.intParam(
                "maxDepth",
                default = BreakdownProjection.DEFAULT_MAX_DEPTH,
                min = 1,
                max = BreakdownProjection.MAX_MAX_DEPTH,
            ) ?: return@get call.respondBadBounds()
            val maxNodes = call.intParam(
                "maxNodes",
                default = BreakdownProjection.DEFAULT_MAX_NODES,
                min = 1,
                max = BreakdownProjection.MAX_MAX_NODES,
            ) ?: return@get call.respondBadBounds()

            val breakdown = breakdownProjection.getBreakdown(itemId, maxDepth = maxDepth, maxNodes = maxNodes)
                ?: return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Object not found",
                    "No object for this reference.",
                )
            call.respond(breakdown)
        }

        /**
         * The dependency graph (docs/REQ_BREAKDOWN_GRAPH_VIEW §3).
         *
         * Scope is always `seed + depth + direction` and there is no unscoped form (§8): a whole
         * module is 12 000 objects, which is an unreadable hairball and a rendering problem this
         * feature does not need to have. The seed is the `{ref}` in the path, so the URL *is* the
         * scope and the view is shareable in a review.
         *
         * Every option is validated against a closed set before a statement is built. `depth` is
         * the one that matters: it bounds a walk over a relationship with no schema constraint
         * against cycles, on a database with no query governor (CLAUDE.md §7).
         */
        get("/graph") {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()

            val depth = call.intParam(
                "depth",
                default = DependencyGraphProjection.DEFAULT_DEPTH,
                min = DependencyGraphProjection.MIN_DEPTH,
                max = DependencyGraphProjection.MAX_DEPTH,
            ) ?: return@get call.respondBadDepth()

            val direction = call.request.queryParameters["direction"]
                ?.let { GraphDirection.fromNameOrNull(it) ?: return@get call.respondBadOption("direction") }
                ?: GraphDirection.BOTH

            val levelStrategy = call.request.queryParameters["levels"]
                ?.let { GraphLevelStrategy.fromNameOrNull(it) ?: return@get call.respondBadOption("levels") }
                ?: GraphLevelStrategy.MODULE_SYSTEM_LEVEL

            val graph = dependencyGraphProjection.getGraph(
                seedIds = listOf(itemId),
                depth = depth,
                direction = direction,
                levelStrategy = levelStrategy,
            ) ?: return@get call.respondProblem(
                HttpStatusCode.NotFound,
                "Object not found",
                "No object for this reference.",
            )
            call.respond(graph)
        }
    }
}

private const val DEFAULT_LIMIT = 2_000
private const val MAX_LIMIT = 5_000

// Community has no query governor (CLAUDE.md §7), so a paging parameter is validated rather than
// passed through: a negative SKIP is a Cypher error and an unbounded LIMIT is the failure mode the
// timeout exists to catch. Returns null for anything unusable.
private fun ApplicationCall.intParam(name: String, default: Int, min: Int, max: Int? = null): Int? {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull() ?: return null
    if (value < min || (max != null && value > max)) {
        return null
    }
    return value
}

private suspend fun ApplicationCall.respondBadPaging(): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid paging",
        "skip must be zero or more and limit between 1 and $MAX_LIMIT.",
    )

private suspend fun ApplicationCall.respondBadBounds(): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid limits",
        "maxDepth must be between 1 and ${BreakdownProjection.MAX_MAX_DEPTH}, " +
            "and maxNodes between 1 and ${BreakdownProjection.MAX_MAX_NODES}.",
    )

private suspend fun ApplicationCall.respondModuleNotFound(): Unit =
    respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")

private suspend fun ApplicationCall.respondBadDepth(): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid depth",
        "depth must be between ${DependencyGraphProjection.MIN_DEPTH} and " +
            "${DependencyGraphProjection.MAX_DEPTH} hops.",
    )

// The value is deliberately not echoed. An unknown option is a client bug, and a query string is
// user input: reflecting it puts whatever was sent into an error page (CLAUDE.md §5).
private suspend fun ApplicationCall.respondBadOption(name: String): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid option",
        "The value given for '$name' is not one this view offers.",
    )
