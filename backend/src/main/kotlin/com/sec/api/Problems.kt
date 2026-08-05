package com.sec.api

import com.sec.api.dto.ProblemDetailDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond

// RFC 9457 problem details — the single response shape for every failure the API reports.
// No stack traces to the client, ever, and no internal type names either: `detail` is a sentence
// written for a person, never an exception message echoed back (CLAUDE.md §5).
//
// `instance` carries the CallId, which is also in the MDC of every log line for the request
// (Application.kt installs callIdMdc), so a user-reported failure is traceable in the logs.
public suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, title: String, detail: String) {
    respond(
        status,
        ProblemDetailDto(
            type = "about:blank",
            title = title,
            status = status.value,
            detail = detail,
            instance = callId,
        ),
    )
}
