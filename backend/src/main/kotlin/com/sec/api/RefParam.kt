package com.sec.api

import com.sec.domain.Ref
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

// {ref} is the base64url encoding of __id (R5). Decoding happens here and only here — never
// inline in a handler — and it is total: a hand-edited address bar is a 400, not a 500.
internal fun ApplicationCall.decodeRef(): String? = parameters["ref"]?.let(Ref::decodeOrNull)

internal suspend fun ApplicationCall.respondInvalidRef(): Unit =
    respondProblem(
        HttpStatusCode.BadRequest,
        "Invalid reference",
        "The reference in this address is not readable. Open the item from its list instead.",
    )
