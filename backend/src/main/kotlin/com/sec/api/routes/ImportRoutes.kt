package com.sec.api.routes

import com.sec.api.ApiPaths
import com.sec.api.dto.ImportScheduleDto
import com.sec.api.dto.ImportStartedDto
import com.sec.api.dto.toDto
import com.sec.api.dto.toSse
import com.sec.api.respondProblem
import com.sec.importer.ImportEvent
import com.sec.importer.ImportRun
import com.sec.importer.ImportRunService
import com.sec.importer.ImportScheduler
import com.sec.importer.StartResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration.Companion.seconds

/**
 * The import framework's HTTP surface (spec §11.4), source-agnostic throughout.
 *
 * | Method | Path | |
 * |---|---|---|
 * | `GET` | `/import/importers` | what can be run, and whether it is running |
 * | `POST` | `/import/{importerId}/runs` | start → `202`, or `409` naming the active run |
 * | `GET` | `/import/runs?importerId=&limit=` | history, newest first |
 * | `GET` | `/import/runs/{runId}` | the run resource — reconnect and late-join read this |
 * | `DELETE` | `/import/runs/{runId}` | request cancellation → `202` |
 * | `GET` | `/import/runs/{runId}/events` | the SSE stream |
 * | `GET` | `/import/{importerId}/schedule` | when the next scheduled run is, if there is one |
 *
 * Nothing here is admin-guarded yet, and that is a gap with a name: spec §14.1 wants every
 * admin-only route wrapped from day one, and this backend has no `security/Authorization.kt`. It is
 * one seam to add across all routes at once rather than a JIRA-shaped one added here — see ADR 0014.
 */
public fun Route.importRoutes(
    service: ImportRunService,
    // Source-agnostic, like the rest of this file: which importers are on a schedule at all is a
    // wiring-time decision made in Application.kt, not something this route file knows about any
    // one source (ADR 0018). Empty when nothing is scheduled.
    schedulers: Map<String, ImportScheduler> = emptyMap(),
) {

    route(ApiPaths.IMPORT) {

        get("/importers") {
            call.respond(
                service.importers().map { job -> job.toDto(service.activeRunId(job.importerId)) },
            )
        }

        /**
         * Whether [importerId] re-runs itself, and when it next will.
         *
         * `scheduled: false` for an importer with no scheduler is the ordinary answer, not a `404`
         * — most importers do not have one, and that is a fact about this deployment's
         * configuration, not a missing resource.
         */
        get("/{importerId}/schedule") {
            val importerId = call.parameters["importerId"].orEmpty()
            val scheduler = schedulers[importerId]

            call.respond(
                if (scheduler == null) {
                    ImportScheduleDto(scheduled = false)
                } else {
                    ImportScheduleDto(
                        scheduled = true,
                        nextRunAt = scheduler.nextRunAt().toString(),
                        intervalMinutes = scheduler.interval.toMinutes().toInt(),
                    )
                },
            )
        }

        post("/{importerId}/runs") {
            val importerId = call.parameters["importerId"].orEmpty()

            when (val result = service.start(importerId)) {
                is StartResult.Started ->
                    call.respond(HttpStatusCode.Accepted, ImportStartedDto(result.runId))

                // 409 with the active run's id, not a bare refusal: the console's correct reaction
                // is to show that run, because an import is exactly what the user asked for.
                is StartResult.AlreadyRunning -> call.respondProblem(
                    HttpStatusCode.Conflict,
                    "An import is already running",
                    "This importer is already running as ${result.runId}. Wait for it to finish, " +
                        "or cancel it, before starting another.",
                )

                StartResult.UnknownImporter -> call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Unknown importer",
                    "This server has no importer called '$importerId'.",
                )
            }
        }

        get("/runs") {
            val importerId = call.request.queryParameters["importerId"]?.takeIf { it.isNotBlank() }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?.coerceIn(1, MAX_HISTORY) ?: DEFAULT_HISTORY

            call.respond(service.history(importerId, limit).map { it.toDto() })
        }

        get("/runs/{runId}") {
            val runId = call.parameters["runId"].orEmpty()
            val run = service.run(runId)

            if (run == null) {
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    "Unknown import run",
                    "No import run with that id is running, and none is in the history this " +
                        "server keeps.",
                )
                return@get
            }
            // The live log only exists while the run does. A finished run reports an empty one
            // rather than a missing field, because "no lines kept" is the true answer (spec §11.2).
            call.respond(run.toDto(service.logLines(runId)))
        }

        delete("/runs/{runId}") {
            val runId = call.parameters["runId"].orEmpty()

            if (service.cancel(runId)) {
                // 202: cancellation is a request, not an act. Phases stop at their next checkpoint
                // and whatever they already committed stays committed.
                call.respond(HttpStatusCode.Accepted, ImportStartedDto(runId))
            } else {
                call.respondProblem(
                    HttpStatusCode.NotFound,
                    "That import is not running",
                    "The run has already finished, or this server has never had one with that id.",
                )
            }
        }

        /**
         * The live stream.
         *
         * Three things here are load-bearing and none of them is obvious:
         *
         *  - **`onSubscription` closes the late-join race.** A run that finishes between the client
         *    reading the run resource and its subscription taking effect would leave the stream open
         *    forever, waiting for a `status` that was emitted before anyone was listening. The
         *    service sets the finished state *before* emitting `status`, so re-reading the run from
         *    inside `onSubscription` — after the subscription is registered — either sees a finished
         *    run, and says so, or is guaranteed to receive the real event.
         *  - **`transformWhile` ends the stream at `status`**, which is what makes "always the last
         *    event, then the server closes" true rather than aspirational.
         *  - **The heartbeat is a comment, not an event.** Proxies close an idle connection after a
         *    minute or so, and a comment keeps it open without a client having to filter out
         *    keep-alives it never asked for.
         */
        sse("/runs/{runId}/events") {
            val runId = call.parameters["runId"].orEmpty()
            heartbeat {
                period = HEARTBEAT.seconds
                event = ServerSentEvent(comments = "ping")
            }

            val stream = service.events(runId)
            val run = service.run(runId)

            if (stream == null) {
                // Either finished before this connected, or never existed. Both are answered the
                // same way: send the terminal state if there is one and close, rather than holding
                // a stream open on a run that will never speak again.
                run?.let { terminal(it) }?.let { frame -> send(frame.data, frame.event) }
                close()
                return@sse
            }

            stream
                .onSubscription {
                    service.run(runId)?.takeIf { it.status.isFinished }?.let { finished ->
                        emit(
                            ImportEvent.Status(
                                runId = finished.runId,
                                status = finished.status,
                                finishedAt = finished.finishedAt,
                                warnings = finished.warnings.size,
                                error = finished.error,
                            ),
                        )
                    }
                }
                .transformWhile { event ->
                    emit(event)
                    event !is ImportEvent.Status
                }
                .collect { event ->
                    val frame = event.toSse()
                    send(frame.data, frame.event)
                }

            close()
        }
    }
}

/** The `status` frame for a run that was already over when a client arrived. */
private fun terminal(run: ImportRun) = ImportEvent.Status(
    runId = run.runId,
    status = run.status,
    finishedAt = run.finishedAt,
    warnings = run.warnings.size,
    error = run.error,
).toSse()

private const val DEFAULT_HISTORY = 20

/** Server-controlled, like every other list endpoint: Community has no query governor (§7). */
private const val MAX_HISTORY = 100

/** 15 s, short enough for the proxies that close an idle stream at 30 (spec §11.4). */
private const val HEARTBEAT = 15
