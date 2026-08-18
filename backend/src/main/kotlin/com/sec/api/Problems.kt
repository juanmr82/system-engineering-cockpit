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
//
// `type` is how a client tells two failures apart without reading English. RFC 9457 makes it a URI
// and "about:blank" means "nothing beyond the status code" — which is right for most of these,
// because a 404 is a 404. Where the caller must *branch* on the reason, pass a [ProblemType]:
// docs/JIRA_ISSUES_FEATURE_SPEC.md §14.5 asks for a stable machine code, and `type` is the field
// RFC 9457 already provides for it. A second `error` key beside it would be two error envelopes.
public suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    detail: String,
    type: String = ProblemType.NONE,
) {
    respond(
        status,
        ProblemDetailDto(
            type = type,
            title = title,
            status = status.value,
            detail = detail,
            instance = callId,
        ),
    )
}

/**
 * The stable machine codes carried in a problem's `type`.
 *
 * A URN rather than a bare word so it is a URI, as RFC 9457 requires, and so it can never be
 * confused with a resolvable documentation link we do not host. These strings are API surface:
 * the frontend branches on them, so renaming one is a breaking change.
 */
public object ProblemType {
    /** The default: the status code says everything there is to say. */
    public const val NONE: String = "about:blank"

    private const val PREFIX: String = "urn:sec:problem:"

    /** No `jira.host` or no token on this deployment. Not an error in the graph — a 503. */
    public const val JIRA_NOT_CONFIGURED: String = "${PREFIX}jira-not-configured"

    /** JIRA rejected the token outright. Never retried, and never retried as Basic auth. */
    public const val JIRA_UNAUTHORIZED: String = "${PREFIX}jira-unauthorized"

    /** JIRA answered, but the token's user may not browse what was asked for. */
    public const val JIRA_FORBIDDEN: String = "${PREFIX}jira-forbidden"

    /** JIRA is configured and unreachable, or answered 5xx after every retry. */
    public const val JIRA_UNREACHABLE: String = "${PREFIX}jira-unreachable"

    /**
     * The request's own content was rejected — a project key that is not one, an empty list.
     *
     * Distinct from the JIRA types above because nothing was sent anywhere: the caller can fix this
     * without knowing anything about the state of a remote system.
     */
    public const val VALIDATION: String = "${PREFIX}validation"

    /** `POST /access/categories` — the pre-checked `key` is already taken (spec §9). */
    public const val ACCESS_CATEGORY_KEY_IN_USE: String = "${PREFIX}access-category-key-in-use"

    /** `DELETE /access/categories/{ref}` — an object or a grant still references it (spec §9). The
     *  frontend branches on this to render the count-bearing message rather than a generic error. */
    public const val ACCESS_CATEGORY_IN_USE: String = "${PREFIX}access-category-in-use"

    /**
     * `POST /doors/import` — the module already exists and is not visible to the caller's own
     * [com.sec.security.AccessSet] (ADR 0019 §4). Deliberately a `404`, not a `403`: this is R8's
     * object-level refusal applied to a write for the first time, not a capability check — the
     * caller already holds `sec-admin`, or this route would have answered `403` before the gate
     * ever ran.
     */
    public const val DOORS_MODULE_NOT_VISIBLE: String = "${PREFIX}doors-module-not-visible"
}
