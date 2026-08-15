package com.sec.security

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
