package com.sec.api.routes

import com.sec.api.decodeRef
import com.sec.api.dto.CommentDto
import com.sec.api.dto.SaveCommentsRequestDto
import com.sec.api.dto.SaveCommentsResponseDto
import com.sec.api.dto.SavedCommentDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.domain.Ref
import com.sec.domain.SaveCommentsOutcome
import com.sec.meta.MetaWriter
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
    metaWriter: MetaWriter,
) {
    route("/api/v1/modules/{ref}") {
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

    route("/api/v1/items/{ref}") {
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

private suspend fun ApplicationCall.respondModuleNotFound(): Unit =
    respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
