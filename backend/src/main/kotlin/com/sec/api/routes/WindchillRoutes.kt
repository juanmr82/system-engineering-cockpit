package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.ProblemType
import com.sec.api.dto.WindchillHealthDto
import com.sec.api.dto.WindchillImportStartedDto
import com.sec.api.respondProblem
import com.sec.config.WindchillSettings
import com.sec.importer.ImportRunService
import com.sec.importer.StartResult
import com.sec.source.windchill.WindchillExportFailure
import com.sec.source.windchill.WindchillExportParser
import com.sec.source.windchill.WindchillExportProblem
import com.sec.source.windchill.WindchillImporter
import com.sec.source.windchill.WindchillProjection
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * The Windchill integration's HTTP surface.
 *
 * | Method | Path | |
 * |---|---|---|
 * | `GET` | `/windchill/health` | whether a host is configured |
 * | `GET` | `/windchill/documents` | every imported document, unpaged |
 * | `POST` | `/windchill/import` | upload an export and import it → `202` |
 *
 * ## Why the upload is not multipart
 *
 * The payload is one JSON document and nothing else — no fields beside it, no second file — so a
 * multipart envelope would add a parser, a boundary and a part to look for in exchange for nothing.
 * The browser reads the file and posts its text; the body *is* the export.
 *
 * ## Why the upload starts the run
 *
 * One user gesture, one request, one outcome (R7). The alternative — stage the file, then start a
 * run against it — needs a staging resource, a handle, and an answer to what happens when nobody
 * ever starts the run. The file is parsed here so that a broken one is a `400` naming the problem,
 * and only a file that will import is allowed to become a run.
 *
 * Nothing here is admin-guarded, which is a gap with a name: `/windchill/import` deletes documents,
 * and this backend still has no `security/Authorization.kt`. It is one seam to add across every
 * route at once rather than a Windchill-shaped one added here — the same standing gap ADR 0014
 * records for JIRA.
 */
public fun Route.windchillRoutes(
    settings: WindchillSettings,
    projection: WindchillProjection,
    importRunService: ImportRunService,
) {

    route(ApiPaths.WINDCHILL) {

        // Never carries a credential, because this integration has none: a host and a boolean.
        get("/health") {
            call.respond(WindchillHealthDto(configured = settings.isConfigured, host = settings.host))
        }

        get("/documents") {
            call.respond(projection.listDocuments())
        }

        /**
         * Upload an export and import it.
         *
         * Three failures, three answers, and they are kept apart because the fix differs:
         * a file that is not JSON is the exporter's problem, a file that is JSON but not an OData
         * collection is the wrong file, and a file with no documents in it is an export that failed.
         * The last is refused rather than run — see [WindchillExportParser] on why importing it
         * would delete every document in the graph.
         */
        post("/import") {
            val body = call.receiveText()

            if (body.length > MAX_UPLOAD_CHARS) {
                call.respondProblem(
                    HttpStatusCode.PayloadTooLarge,
                    "That export is too large",
                    "The file is larger than this server accepts in one upload. Export fewer " +
                        "documents at a time, or ask for the limit to be raised.",
                    ProblemType.VALIDATION,
                )
                return@post
            }

            val export = WindchillExportParser.parse(body).getOrElse { cause ->
                val problem = (cause as? WindchillExportFailure)?.problem
                call.respondProblem(
                    HttpStatusCode.BadRequest,
                    "That file is not a Windchill export",
                    describe(problem),
                    ProblemType.VALIDATION,
                )
                return@post
            }

            when (val result = importRunService.start(WindchillImporter.ID, export)) {
                is StartResult.Started -> call.respond(
                    HttpStatusCode.Accepted,
                    WindchillImportStartedDto(
                        runId = result.runId,
                        documents = export.records.size,
                        paged = export.nextLink != null,
                        warnings = export.warnings,
                    ),
                )

                // The console's right reaction is to show that run: an import is exactly what was
                // asked for and one is happening. The uploaded file is dropped, deliberately —
                // holding it would be the staging layer this design does not have.
                is StartResult.AlreadyRunning -> call.respondProblem(
                    HttpStatusCode.Conflict,
                    "An import is already running",
                    "Windchill is already importing as ${result.runId}. Wait for it to finish, or " +
                        "cancel it, before uploading another export.",
                )

                StartResult.UnknownImporter -> call.respondProblem(
                    HttpStatusCode.ServiceUnavailable,
                    "The Windchill importer is not registered",
                    "This server was started without the Windchill importer, so an export cannot " +
                        "be imported.",
                )
            }
        }
    }
}

/** A sentence for a person, never the parser's own message verbatim beyond the position it names. */
private fun describe(problem: WindchillExportProblem?): String = when (problem) {
    is WindchillExportProblem.NotJson ->
        "The file is not valid JSON. ${problem.detail.ifBlank { "It could not be parsed." }}"

    is WindchillExportProblem.NotAnExport ->
        "${problem.detail} A Windchill export is the OData response for Documents, with the rows " +
            "in a 'value' array."

    is WindchillExportProblem.NoDocuments ->
        "The file was read and contains no documents" +
            (if (problem.skipped > 0) ", and ${problem.skipped} row(s) could not be used" else "") +
            ". It was not imported: an export is treated as the whole truth, so importing an empty " +
            "one would remove every Windchill document already here."

    null -> "The file could not be read."
}

/**
 * The largest export this accepts, in characters.
 *
 * ~64 MB of text, which is two orders of magnitude above the ~1 500-document export production
 * starts with and still small enough that parsing it cannot exhaust the heap. A limit stated here
 * is a `413` that says what to do; the same file without one is an `OutOfMemoryError` that takes the
 * process with it.
 */
private const val MAX_UPLOAD_CHARS = 64 * 1024 * 1024
