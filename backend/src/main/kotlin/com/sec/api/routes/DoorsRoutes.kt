package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.ProblemType
import com.sec.api.dto.DoorsImportResultDto
import com.sec.api.respondProblem
import com.sec.domain.Ref
import com.sec.importer.ImportRunService
import com.sec.importer.StartResult
import com.sec.security.AccessResolver
import com.sec.security.AccessSet
import com.sec.security.PushAuthNames
import com.sec.security.Role
import com.sec.security.accessSet
import com.sec.security.requireRole
import com.sec.source.doors.DoorsExportFailure
import com.sec.source.doors.DoorsExportParser
import com.sec.source.doors.DoorsExportProblem
import com.sec.source.doors.DoorsImportGate
import com.sec.source.doors.DoorsImporter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Asked before a run starts (ADR 0019 §3, §4): does this file's module already exist, may the
 * caller see it, and has this exact file already been imported.
 *
 * A seam of its own — rather than the route calling
 * [com.sec.source.doors.DoorsGraphWriter.gate] directly — for the same reason `/windchill/import`
 * takes an [ImportRunService] rather than constructing a run itself: [DoorsRoutesTest] can fake this
 * without a database, the same way it fakes the whole import with a [com.sec.importer.ImportJob]
 * double.
 */
public fun interface DoorsModuleGateway {
    public suspend fun gate(moduleId: String, access: AccessSet): DoorsImportGate
}

/**
 * The DOORS-from-an-upload HTTP surface (ADR 0019), the browser/admin front door.
 *
 * | Method | Path | |
 * |---|---|---|
 * | `POST` | `/doors/import` | upload an export and import it → `202`, `200`, or `404` |
 *
 * **Admin-guarded the same way `/windchill/import` is**: `requireRole(Role.ADMIN)` wraps only this
 * one route, not this file, because there is nothing else here yet for a blanket wrapper to take
 * with it. The frontend adds no matching guard — `/settings/doors` is reachable by every signed-in
 * user, same as `/settings/jira` and `/settings/windchill` — so this `403` is what actually decides
 * who may import (ADR 0019 §5).
 *
 * The second front door — a technical account pushing an export with no browser at all — is
 * [doorsPushRoutes], a separate function because it needs to sit outside `requireSecSession` in
 * `Routes.kt` (ADR 0020): a bearer-authenticated route cannot be nested inside one that demands a
 * session cookie. Both call [handleDoorsImport], which is the whole of ADR 0019 §3's point — one
 * gate, two doors.
 */
public fun Route.doorsRoutes(
    gateway: DoorsModuleGateway,
    importRunService: ImportRunService,
    accessResolver: AccessResolver,
) {
    route(ApiPaths.DOORS) {
        requireRole(Role.ADMIN) {
            post("/import") {
                handleDoorsImport(call, gateway, importRunService, accessResolver)
            }
        }
    }
}

/**
 * The DOORS push HTTP surface (ADR 0020), the technical-account front door.
 *
 * | Method | Path | |
 * |---|---|---|
 * | `POST` | `/doors/import/push` | push an export and import it → `202`, `200`, `404`, or `401` |
 *
 * Authenticated by [PushAuthNames.PROVIDER] — a bearer access token from the `sec-doors-push`
 * Keycloak client (`docs/KEYCLOAK_SETUP.md` §2b) — rather than the session cookie
 * [doorsRoutes] uses, and carries **no `requireRole`**: reaching this route at all already proves
 * the caller holds a token only a technical import account can obtain, which is this route's whole
 * capability check (ADR 0020 §2 — deliberately not a realm role). No CSRF check either, for the
 * same reason: CSRF defends against a browser riding an ambient cookie, and there is no cookie
 * here.
 *
 * `call.accessSet(accessResolver)` reads `principal.groups` exactly as [doorsRoutes] does — the
 * principal is a [com.sec.security.SecPrincipal] regardless of which provider built it, so the
 * pushing account's `/SEC/Importers` (or whichever group it carries) resolves through the same
 * `AccessResolver` path a human login does, including the on-sight `:__Group` creation
 * (`docs/features/access-control.md` §5) that is what makes the group show up under **Access →
 * Groups**, ready for a `sec-access-manager` to grant it categories, with no new access-control
 * code at all.
 */
public fun Route.doorsPushRoutes(
    gateway: DoorsModuleGateway,
    importRunService: ImportRunService,
    accessResolver: AccessResolver,
) {
    route(ApiPaths.DOORS) {
        authenticate(PushAuthNames.PROVIDER) {
            post("/import/push") {
                handleDoorsImport(call, gateway, importRunService, accessResolver)
            }
        }
    }
}

/**
 * ADR 0019 §3's one gate, shared by both front doors ([doorsRoutes], [doorsPushRoutes]).
 *
 * Parsed and gated **before** a run is started, so a broken file, a file with nothing new in it,
 * or a module this caller cannot currently act on never becomes a run — the same discipline
 * `/windchill/import` already has for a broken file, extended here to the two things only this
 * source's gate can know.
 *
 * The file's text is decoded as UTF-8 and re-encoded to compute the checksum, rather than reading
 * raw bytes off the wire — the same assumption the settings page's own `File.text()` already makes
 * about what a DOORS export is, so both sides of the upload agree on what "this file" means.
 *
 * Three answers on success, not one, and they are kept apart because a person — or a scheduled
 * pusher's own log — reads them differently: `202` is a run to go watch, `200` is "nothing to do,
 * you already have this", and `404` is "you cannot act on this module right now" — never silence,
 * and never a run that quietly does nothing.
 *
 * Takes an [AccessResolver], not an already-resolved [AccessSet] — `call.accessSet(accessResolver)`
 * runs **after** the parse succeeds, in the position below, not as an eager argument at the call
 * site. `call.accessSet` throws when there is no principal (`Principal.kt`), and a caller-side
 * `handleDoorsImport(call, gateway, importRunService, call.accessSet(accessResolver))` would
 * evaluate that argument before this function's body — and therefore before the parse — runs at
 * all, which is exactly backwards: [DoorsRoutesTest] pins "a broken upload is refused at the door
 * before a run, a gateway, **or an access resolution** is ever consulted" with a fake harness that
 * has no working `AccessResolver` to consult.
 */
private suspend fun handleDoorsImport(
    call: ApplicationCall,
    gateway: DoorsModuleGateway,
    importRunService: ImportRunService,
    accessResolver: AccessResolver,
) {
    val text = call.receiveText()
    val bytes = text.toByteArray(Charsets.UTF_8)

    if (bytes.size > MAX_UPLOAD_BYTES) {
        call.respondProblem(
            HttpStatusCode.PayloadTooLarge,
            "That export is too large",
            "The file is larger than this server accepts in one upload. Export fewer " +
                "objects at a time, or ask for the limit to be raised.",
            ProblemType.VALIDATION,
        )
        return
    }

    val export = DoorsExportParser.parse(bytes).getOrElse { cause ->
        val problem = (cause as? DoorsExportFailure)?.problem
        call.respondProblem(
            HttpStatusCode.BadRequest,
            "That file is not a DOORS export",
            describe(problem),
            ProblemType.VALIDATION,
        )
        return
    }

    val access = call.accessSet(accessResolver)
    val gate = gateway.gate(export.moduleId, access)

    if (gate.exists && !gate.visible) {
        call.respondProblem(
            HttpStatusCode.NotFound,
            "This module cannot be imported",
            "This module has already been imported and is not currently visible to " +
                "your account. Ask an access manager to assign it a category — or add " +
                "your group to the one it already has — before re-importing.",
            ProblemType.DOORS_MODULE_NOT_VISIBLE,
        )
        return
    }

    if (gate.exists && gate.storedChecksum == export.checksum) {
        call.respond(
            HttpStatusCode.OK,
            DoorsImportResultDto(
                status = "skipped",
                moduleRef = Ref.encode(export.moduleId),
                moduleName = export.moduleName,
                objects = export.objects.size,
                checksum = export.checksum,
                warnings = export.warnings,
            ),
        )
        return
    }

    when (val result = importRunService.start(DoorsImporter.ID, export)) {
        is StartResult.Started -> call.respond(
            HttpStatusCode.Accepted,
            DoorsImportResultDto(
                status = "started",
                runId = result.runId,
                moduleRef = Ref.encode(export.moduleId),
                moduleName = export.moduleName,
                objects = export.objects.size,
                checksum = export.checksum,
                warnings = export.warnings,
            ),
        )

        is StartResult.AlreadyRunning -> call.respondProblem(
            HttpStatusCode.Conflict,
            "An import is already running",
            "DOORS is already importing as ${result.runId}. Wait for it to finish, or " +
                "cancel it, before uploading another export.",
        )

        StartResult.UnknownImporter -> call.respondProblem(
            HttpStatusCode.ServiceUnavailable,
            "The DOORS importer is not registered",
            "This server was started without the DOORS importer, so an export cannot " +
                "be imported.",
        )
    }
}

/** A sentence for a person, never the parser's own message verbatim beyond the position it names. */
private fun describe(problem: DoorsExportProblem?): String = when (problem) {
    is DoorsExportProblem.NotJson ->
        "The file is not valid JSON. ${problem.detail.ifBlank { "It could not be parsed." }}"

    is DoorsExportProblem.NotAnExport ->
        "${problem.detail} A DOORS export is the JSON document one module's DXL export step " +
            "produces, with an object list in '__contents'."

    is DoorsExportProblem.Invalid -> problem.detail

    null -> "The file could not be read."
}

/**
 * The largest export this accepts, in bytes.
 *
 * DOORS modules run wider than a Windchill document — 78+ attributes on the reference module — and
 * the DXL export caps a module at 12 000 objects, so this is set above what Windchill's own 64 MB
 * text limit allows rather than reusing it: a stated `413` is a better answer than an
 * `OutOfMemoryError` that takes the process with it.
 *
 * **Ops note — a reverse proxy in front of this backend must be told about this limit, or this
 * constant never gets a chance to run.** nginx's own default `client_max_body_size` is 1 MB, so a
 * production deployment fronted by nginx (`docs/REFACTOR_BACKEND.md`'s recommended topology) rejects
 * anything past 1 MB with its own `413`, before the request reaches Ktor at all — a real DOORS
 * export (a few MB is typical) never gets near this route. The fix is one directive on whatever
 * `location` proxies `/api` to this service: `client_max_body_size 128m;` (or higher — never lower
 * than this constant, or the two limits disagree about where a file is refused). The same is true of
 * [com.sec.api.routes.WindchillRoutes]'s own `MAX_UPLOAD_CHARS`, which carries a matching note. A
 * full deployment/nginx guide is tracked separately; this is the pointer to it until that exists.
 */
private const val MAX_UPLOAD_BYTES = 128 * 1024 * 1024
