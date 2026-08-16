package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.decodeRef
import com.sec.api.dto.ModuleAttributesResponseDto
import com.sec.api.dto.ModuleListResponseDto
import com.sec.api.dto.ModuleSettingsRequestDto
import com.sec.api.dto.SaveSystemLevelsRequestDto
import com.sec.api.dto.SaveSystemLevelsResponseDto
import com.sec.api.dto.SavedSystemLevelDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.api.respondInvalidRef
import com.sec.api.respondProblem
import com.sec.domain.Ref
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SaveSystemLevelsOutcome
import com.sec.domain.SystemLevel
import com.sec.domain.SystemLevelChange
import com.sec.meta.MetaWriter
import com.sec.security.AccessResolver
import com.sec.security.SecPrincipal
import com.sec.security.accessSet
import com.sec.security.auditName
import com.sec.source.doors.DoorsProjection
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// The DOORS-specific module projection (CLAUDE.md §5 "API shape"). Handlers delegate to
// source/ and meta/ — they hold no logic beyond turning a sealed outcome into a status code.
public fun Route.moduleRoutes(
    doorsProjection: DoorsProjection,
    metaWriter: MetaWriter,
    accessResolver: AccessResolver,
) {
    route(ApiPaths.MODULES) {
        get {
            val access = call.accessSet(accessResolver)
            call.respond(ModuleListResponseDto(doorsProjection.listModules(access)))
        }

        get("/{ref}") {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val access = call.accessSet(accessResolver)
            // A module this caller may not see is a 404 and never a 403 — a 403 would confirm it
            // exists, which is the whole of what a module name discloses (spec §7).
            val detail = doorsProjection.getModuleDetail(moduleId, access)
                ?: return@get call.respondModuleNotFound()
            call.respond(detail)
        }

        get("/{ref}/attributes") {
            val moduleId = call.decodeRef() ?: return@get call.respondInvalidRef()
            val access = call.accessSet(accessResolver)
            if (!doorsProjection.moduleExists(moduleId, access)) {
                return@get call.respondModuleNotFound()
            }
            call.respond(
                ModuleAttributesResponseDto(doorsProjection.getModuleAttributes(moduleId, access)),
            )
        }

        /**
         * The Modules table's save icon: every changed system level, one transaction.
         *
         * Not module-scoped, because the batch spans modules — that is the whole difference from
         * `/{ref}/comments`, whose batch is the objects of one module. Registered before
         * `/{ref}/...` would be ambiguous only if it shared a shape with it; it does not.
         */
        post("/system-levels") {
            val principal = call.principal<SecPrincipal>()
                ?: error("${ApiPaths.MODULES}/system-levels ran without a principal despite the session guard")
            val body = call.receive<SaveSystemLevelsRequestDto>()

            // Decoded here, before the writer sees them, so a malformed handle is a 400 rather
            // than a module that mysteriously does not exist.
            val malformed = body.levels.filter { Ref.decodeOrNull(it.ref) == null }.map { it.ref }
            if (malformed.isNotEmpty()) {
                return@post call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "Invalid reference",
                    "Some references in this request are not readable. Reload the list and try again.",
                )
            }

            val edits = body.levels.mapNotNull { edit ->
                Ref.decodeOrNull(edit.ref)?.let {
                    MetaWriter.SystemLevelEditInput(moduleId = it, code = edit.code)
                }
            }

            val access = call.accessSet(accessResolver)
            when (
                val outcome = metaWriter.saveSystemLevels(edits, access, user = principal.auditName)
            ) {
                is SaveSystemLevelsOutcome.MalformedRefs ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Invalid reference",
                        "Some references in this request are not readable.",
                    )

                is SaveSystemLevelsOutcome.UnknownModules ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Unknown module",
                        "${outcome.refs.size} of the modules in this request no longer exist. " +
                            "Reload the list and try again.",
                    )

                is SaveSystemLevelsOutcome.InvalidSystemLevel ->
                    call.respondProblem(
                        HttpStatusCode.BadRequest,
                        "Malformed request",
                        "System level must be a level code or empty.",
                    )

                is SaveSystemLevelsOutcome.Saved ->
                    call.respond(
                        SaveSystemLevelsResponseDto(
                            saved = outcome.levels.map { saved ->
                                SavedSystemLevelDto(
                                    ref = Ref.encode(saved.moduleId),
                                    // Resolved to its wording here so the client never maps a
                                    // stored code to language of its own (R5).
                                    systemLevel = saved.code
                                        ?.let(SystemLevel::fromCode)
                                        ?.let { SystemLevelOptionDto(it.code, it.label) },
                                )
                            },
                        ),
                    )
            }
        }

        // One dialog, one request, one transaction (R7) — the system level and the
        // mandatory-attribute diff are saved together or not at all.
        post("/{ref}/settings") {
            val moduleId = call.decodeRef() ?: return@post call.respondInvalidRef()
            val principal = call.principal<SecPrincipal>()
                ?: error("${ApiPaths.MODULES}/{ref}/settings ran without a principal despite the session guard")
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
                    access = call.accessSet(accessResolver),
                    addAttributes = body.mandatoryAttributes.add,
                    removeAttributes = body.mandatoryAttributes.remove,
                    attributeSettings = body.attributeSettings?.map {
                        MetaWriter.AttributeSettingInput(
                            name = it.name,
                            mandatory = it.mandatory,
                            visible = it.visible,
                            verification = it.verification,
                            excludedFromOpenPoints = it.excludedFromOpenPoints,
                        )
                    },
                    user = principal.auditName,
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
                    val detail = doorsProjection.getModuleDetail(moduleId, call.accessSet(accessResolver))
                        ?: return@post call.respondModuleNotFound()
                    call.respond(detail)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondModuleNotFound(): Unit =
    respondProblem(HttpStatusCode.NotFound, "Module not found", "No module for this reference.")
