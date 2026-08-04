package com.sec.api

import com.sec.api.dto.ProblemDetailDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// RFC 9457 problem details for the expected (sealed-result) failures of the Modules feature.
// No stack traces to the client, ever (CLAUDE.md §5).
public suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, title: String, detail: String) {
    respond(
        status,
        ProblemDetailDto(
            type = "about:blank",
            title = title,
            status = status.value,
            detail = detail,
        ),
    )
}
