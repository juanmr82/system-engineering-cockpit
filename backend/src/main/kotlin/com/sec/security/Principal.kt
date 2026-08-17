package com.sec.security

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal

/**
 * The authenticated caller, as every route handler and every future access-control read path sees
 * it. `call.principal<SecPrincipal>()` after the session-authentication plugin has run.
 *
 * Deliberately not `io.ktor.server.auth.Principal` — that interface is deprecated in Ktor 3.5
 * ("Principal is not required anymore. Remove this interface or replace it with Any"), and a
 * plain class is what the session-auth provider's `validate` block expects back.
 *
 * Deliberately **not** carrying the raw OAuth tokens: those exist only inside [UserSession], read
 * by the session-refresh logic and nothing else. A principal that is passed around the application
 * — into a meta writer for `__createdBy`, eventually into [com.sec.security] AccessResolver for the
 * `groups` claim — should not be a way for a bug three call sites away to leak a refresh token into
 * a log line.
 */
public data class SecPrincipal(
    /** Keycloak's `sub` — the one identity that never changes, brokering included (ADR 0016 §3.1). */
    public val sub: String,
    /** `preferred_username`. The company user id (`docs/KEYCLOAK_SETUP.md` §5), never used as a key. */
    public val username: String,
    public val name: String,
    public val email: String,
    /** From `realm_access.roles`. Checked with `in`, never assumed non-empty. */
    public val roles: Set<String>,
    /** From the `groups` claim, full paths (`/SEC/Thermal`). Empty means "no access" (R8). */
    public val groups: List<String>,
    /** The double-submit token this request's session was issued (ADR 0017 §11). */
    public val csrfToken: String,
) {
    public fun hasRole(role: String): Boolean = role in roles
}

/**
 * The human-readable identity a route handler passes as `user` into a meta writer, for
 * `__createdBy` / `__updatedBy` (R2) — resolved once, here, so every write path and
 * `AuthRoutes`'s own `displayName` agree on the same fallback rather than each re-deriving it.
 */
public val SecPrincipal.auditName: String get() = name.ifBlank { username }

/**
 * What this caller may see, for the read path about to run
 * (`docs/features/access-control.md` §6.3).
 *
 * One declaration rather than the same four lines in every handler: phase 4 threads an [AccessSet]
 * through roughly twenty of them, and a handler that resolved it its own way — or defaulted it when
 * the principal was missing — is exactly the drift §6.3 rules out.
 *
 * **Throws rather than falling back to [AccessSet.NONE]** when there is no principal. Both are
 * fail-closed in what they return, but only one is honest: no principal here means the session guard
 * did not run, which is a wiring defect in `Routes.kt` and not a user in no group. Returning an empty
 * set would hide that behind an application that merely looks empty (R8: "no code path may widen
 * visibility on error" — and none may quietly narrow it either, because a silent narrowing is a bug
 * report about missing data rather than about missing authentication).
 */
public suspend fun ApplicationCall.accessSet(accessResolver: AccessResolver): AccessSet {
    val principal = principal<SecPrincipal>()
        ?: error("${request.local.uri} ran without a principal despite the session guard")
    return accessResolver.resolve(principal.groups)
}
