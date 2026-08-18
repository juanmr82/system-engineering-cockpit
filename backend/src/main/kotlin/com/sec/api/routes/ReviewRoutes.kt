package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.decodeRef
import com.sec.api.dto.AnnotationsResponseDto
import com.sec.api.dto.NoteDto
import com.sec.api.dto.PostNoteRequestDto
import com.sec.api.dto.ResolveThreadRequestDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.domain.DeleteThreadOutcome
import com.sec.domain.GraphDirection
import com.sec.domain.GraphLevelStrategy
import com.sec.domain.PostNoteOutcome
import com.sec.domain.Ref
import com.sec.domain.ResolveThreadOutcome
import com.sec.domain.ThreadNote
import com.sec.meta.MetaWriter
import com.sec.security.AccessResolver
import com.sec.security.SecPrincipal
import com.sec.security.accessSet
import com.sec.source.doors.BreakdownProjection
import com.sec.source.doors.DependencyGraphProjection
import com.sec.source.doors.DoorsProjection
import com.sec.source.doors.ReviewProjection
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
import io.ktor.server.routing.route

// The Req review table (docs/REQ_REVIEW.md §8) and its comment threads
// (docs/req-review-comment-threads.md). The objects endpoint is paged and capped; every thread
// write is its own request through the same guarded meta writer — R7's ordinary rule, not a batch.
public fun Route.reviewRoutes(
    doorsProjection: DoorsProjection,
    reviewProjection: ReviewProjection,
    breakdownProjection: BreakdownProjection,
    dependencyGraphProjection: DependencyGraphProjection,
    metaWriter: MetaWriter,
    accessResolver: AccessResolver,
) {
    route("${ApiPaths.MODULES}/${ApiPaths.REF}") {
        get("/objects") {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val access = call.accessSet(accessResolver)
            if (!doorsProjection.moduleExists(moduleId, access)) {
                return@get call.respondModuleNotFound()
            }

            val skip = call.intParam("skip", default = 0, min = 0) ?: return@get call.respondBadPaging()
            val limit = call.intParam("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)
                ?: return@get call.respondBadPaging()

            call.respond(reviewProjection.getModuleObjects(moduleId, access, skip = skip, limit = limit))
        }
    }

    route("${ApiPaths.ITEMS}/${ApiPaths.REF}") {
        get {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()
            // 404, never 403: an object this caller may not see must be indistinguishable from one
            // that does not exist, because a 403 confirms it exists (spec §7).
            val detail = reviewProjection.getItemDetail(itemId, call.accessSet(accessResolver))
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
            // Both endpoints are filtered in the statement, so a link to an object this caller
            // cannot see is simply absent — never struck through, never "unresolved" (spec §7).
            call.respond(
                reviewProjection.getTraces(itemId, incoming = incoming, access = call.accessSet(accessResolver)),
            )
        }

        // The thread panel's own load — every note on this item, root first
        // (docs/req-review-comment-threads.md §4). 404 rather than an empty list when the item
        // itself does not resolve, so "no thread yet" and "no such object" stay distinguishable.
        get("/annotations") {
            val itemId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val access = call.accessSet(accessResolver)
            if (reviewProjection.getItemDetail(itemId, access) == null) {
                return@get call.respondProblem(
                    HttpStatusCode.NotFound, "Object not found", "No object for this reference.",
                )
            }
            call.respond(AnnotationsResponseDto(metaWriter.listAnnotations(itemId, access).map { it.toDto() }))
        }

        // Each reply is its own request, its own transaction — R7's ordinary rule now that the
        // batch-comment exception (REQ_REVIEW.md §9.1) is retired. The server decides root vs.
        // reply; the client only ever posts text (§4).
        post("/annotations") {
            val itemId = call.decodeRef() ?: return@post call.respondInvalidRef()
            val principal = call.principal<SecPrincipal>()
                ?: error("$itemId/annotations ran without a principal despite the session guard")
            val body = call.receive<PostNoteRequestDto>()
            if (body.text.isBlank()) {
                return@post call.respondProblem(
                    HttpStatusCode.BadRequest, "Empty comment", "A comment cannot be empty.",
                )
            }
            val access = call.accessSet(accessResolver)

            when (val outcome = metaWriter.postNote(itemId, body.text, access, authorSub = principal.sub)) {
                is PostNoteOutcome.ItemNotFound ->
                    call.respondProblem(
                        HttpStatusCode.NotFound, "Object not found", "No object for this reference.",
                    )

                is PostNoteOutcome.Posted ->
                    call.respond(HttpStatusCode.Created, outcome.note.toDto())
            }
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

            val breakdown = breakdownProjection.getBreakdown(
                itemId,
                access = call.accessSet(accessResolver),
                maxDepth = maxDepth,
                maxNodes = maxNodes,
            )
                ?: return@get call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Object not found",
                    "No object for this reference.",
                )
            call.respond(breakdown)
        }

        /**
         * The dependency graph (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §3).
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
                access = call.accessSet(accessResolver),
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

    // {ref} here is a note's own __metaId, not the item's — resolving or deleting a thread needs
    // no item context, which is exactly what ReviewCypher.RESOLVE_NOTE/DELETE_NOTE rely on
    // (docs/req-review-comment-threads.md §4).
    route("${ApiPaths.ANNOTATIONS}/${ApiPaths.REF}") {
        patch {
            val metaId = call.decodeRef() ?: return@patch call.respondInvalidRef()
            val principal = call.principal<SecPrincipal>()
                ?: error("$metaId ran without a principal despite the session guard")
            val body = call.receive<ResolveThreadRequestDto>()
            val access = call.accessSet(accessResolver)

            when (
                val outcome = metaWriter.resolveThread(metaId, body.resolved, access, authorSub = principal.sub)
            ) {
                is ResolveThreadOutcome.NotFound -> call.respondAnnotationNotFound()
                is ResolveThreadOutcome.Resolved -> call.respond(outcome.note.toDto())
            }
        }

        delete {
            val metaId = call.decodeRef() ?: return@delete call.respondInvalidRef()
            when (val outcome = metaWriter.deleteThread(metaId, call.accessSet(accessResolver))) {
                is DeleteThreadOutcome.NotFound -> call.respondAnnotationNotFound()
                is DeleteThreadOutcome.Deleted -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// Covers "no such note", "not visible", and — for PATCH — "this ref names a reply, not a root":
// all three are the same "nothing to act on" a 404 already means (R8), and none is distinguished
// from the others on the wire (a 403 would confirm the note exists, spec §7).
private suspend fun ApplicationCall.respondAnnotationNotFound(): Unit =
    respondProblem(HttpStatusCode.NotFound, "Comment not found", "No comment for this reference.")

private fun ThreadNote.toDto(): NoteDto =
    NoteDto(
        ref = Ref.encode(metaId),
        text = text,
        replyTo = replyTo?.let(Ref::encode),
        resolved = resolved,
        authorName = authorName,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

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
