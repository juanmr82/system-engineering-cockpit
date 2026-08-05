package com.sec.api.routes

import com.sec.api.decodeRef
import com.sec.api.dto.ModuleAttributesResponseDto
import com.sec.api.dto.ModuleListResponseDto
import com.sec.api.dto.ModuleSettingsRequestDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SystemLevelChange
import com.sec.meta.MetaWriter
import com.sec.source.doors.DoorsProjection
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// The DOORS-specific module projection (CLAUDE.md §5 "API shape"). Handlers delegate to
// source/ and meta/ — they hold no logic beyond turning a sealed outcome into a status code.
public fun Route.moduleRoutes(doorsProjection: DoorsProjection, metaWriter: MetaWriter) {
    route("/api/v1/modules") {
        get {
            call.respond(ModuleListResponseDto(doorsProjection.listModules()))
        }

        get("/{ref}") {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val detail = doorsProjection.getModuleDetail(moduleId)
                ?: return@get call.respondModuleNotFound()
            call.respond(detail)
        }

        get("/{ref}/attributes") {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            if (!doorsProjection.moduleExists(moduleId)) {
                return@get call.respondModuleNotFound()
            }
            call.respond(ModuleAttributesResponseDto(doorsProjection.getModuleAttributes(moduleId)))
        }

        // One dialog, one request, one transaction (R7) — the system level and the
        // mandatory-attribute diff are saved together or not at all.
        post("/{ref}/settings") {
            val moduleId = call.decodeRef() ?: return@post call.respondInvalidRef()
            val body = call.receive<ModuleSettingsRequestDto>()

            // A non-string, non-null systemLevel is a malformed request, not a silent no-op.
            val systemLevel = SystemLevelChange.from(body.systemLevel)
                ?: return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "Malformed request",
                    "System level must be a level code or empty.",
                )

            when (
                val outcome = metaWriter.saveModuleSettings(
                    moduleId = moduleId,
                    systemLevel = systemLevel,
                    addAttributes = body.mandatoryAttributes.add,
                    removeAttributes = body.mandatoryAttributes.remove,
                    attributeSettings = body.attributeSettings?.map {
                        MetaWriter.AttributeSettingInput(
                            name = it.name,
                            mandatory = it.mandatory,
                            visible = it.visible,
                            verification = it.verification,
                        )
                    },
                )
            ) {
                is SaveModuleSettingsOutcome.ModuleNotFound ->
                    call.respondModuleNotFound()

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
                        ?: return@post call.respondModuleNotFound()
                    call.respond(detail)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondModuleNotFound(): Unit =
    respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
