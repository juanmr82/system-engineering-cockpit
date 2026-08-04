package com.sec.api

import com.sec.api.dto.ModuleAttributesResponseDto
import com.sec.api.dto.ModuleListResponseDto
import com.sec.api.dto.ModuleSettingsRequestDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.api.dto.SystemLevelsResponseDto
import com.sec.domain.Ref
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SystemLevel
import com.sec.graph.GraphDriver
import com.sec.meta.MetaWriter
import com.sec.source.doors.DoorsProjection
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

// Route registration only — handlers delegate to domain/source/meta, they don't hold logic.
// {ref} is the base64url encoding of __id (R5), decoded once via decodeRef() below, never
// inline in a handler.
public fun Application.configureRouting(graphDriver: GraphDriver) {
    val doorsProjection = DoorsProjection(graphDriver)
    val metaWriter = MetaWriter(graphDriver, doorsProjection)

    routing {
        get("/api/v1/health") {
            call.respondText("ok")
        }

        // GET  /api/v1/tree
        // GET  /api/v1/items/{ref}
        // GET  /api/v1/items/{ref}/children
        // GET  /api/v1/items/{ref}/traces
        // GET  /api/v1/items/{ref}/annotations
        // POST /api/v1/items/{ref}/annotations
        // PATCH|DELETE /api/v1/annotations/{ref}
        // GET  /api/v1/config/navigation
        // POST /api/v1/cypher/explain
        // POST /api/v1/cypher/run
        // See CLAUDE.md §5 "API shape" and docs/CYPHER_API_DESIGN.md.

        get("/api/v1/modules") {
            val rows = doorsProjection.listModules()
            call.respond(ModuleListResponseDto(rows))
        }

        get("/api/v1/modules/{ref}") {
            val moduleId = call.decodeRef() ?: return@get call.respondMissingRef()
            val detail = doorsProjection.getModuleDetail(moduleId)
                ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
            call.respond(detail)
        }

        get("/api/v1/modules/{ref}/attributes") {
            val moduleId = call.decodeRef() ?: return@get call.respondMissingRef()
            if (!doorsProjection.moduleExists(moduleId)) {
                return@get call.respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
            }
            val attributes = doorsProjection.getModuleAttributes(moduleId)
            call.respond(ModuleAttributesResponseDto(attributes))
        }

        post("/api/v1/modules/{ref}/settings") {
            val moduleId = call.decodeRef() ?: return@post call.respondMissingRef()
            val body = call.receive<ModuleSettingsRequestDto>()

            when (
                val outcome = metaWriter.saveModuleSettings(
                    moduleId = moduleId,
                    systemLevelCode = body.systemLevel,
                    addAttributes = body.mandatoryAttributes.add,
                    removeAttributes = body.mandatoryAttributes.remove,
                )
            ) {
                is SaveModuleSettingsOutcome.ModuleNotFound ->
                    call.respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")

                is SaveModuleSettingsOutcome.InvalidSystemLevel ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Unknown system level",
                        "'${outcome.code}' is not a recognised system level.",
                    )

                is SaveModuleSettingsOutcome.UnknownAttributes ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Unknown attribute",
                        "Not present on this module's objects: ${outcome.names.joinToString(", ")}.",
                    )

                is SaveModuleSettingsOutcome.Saved -> {
                    val detail = doorsProjection.getModuleDetail(moduleId)
                        ?: return@post call.respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
                    call.respond(detail)
                }
            }
        }

        get("/api/v1/config/system-levels") {
            call.response.header(HttpHeaders.CacheControl, "max-age=3600")
            call.respond(
                SystemLevelsResponseDto(
                    SystemLevel.entries.map { SystemLevelOptionDto(it.code, it.label) },
                ),
            )
        }
    }
}

private fun ApplicationCall.decodeRef(): String? = parameters["ref"]?.let(Ref::decode)

private suspend fun ApplicationCall.respondMissingRef(): Unit =
    respondProblem(HttpStatusCode.BadRequest, "Missing reference", "The :ref path segment is required.")
