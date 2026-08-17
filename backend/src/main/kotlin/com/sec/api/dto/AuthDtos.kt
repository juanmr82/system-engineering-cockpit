package com.sec.api.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/auth/me` — the frontend's only source of identity (ADR 0017). Never
 * browser-cached, re-fetched on every full page load.
 *
 * `seesAll` and `categoryCount` come from [com.sec.security.AccessResolver.resolve] over the
 * caller's own groups (`docs/features/access-control.md` §9). **`categoryCount` reads `0` for a
 * `seesAll` caller even though they see everything** — [com.sec.security.AccessSet.SEES_ALL]
 * carries an empty `categoryIds` by design (the visibility predicate never reads `$acl` when
 * `seesAll` is true, so there is nothing to populate), and this field is a count of *explicit
 * category grants*, not of visible categories. Do not "fix" this into populating the full
 * category list for a seesAll user — that would require a second query this endpoint has no
 * other reason to run.
 */
@Serializable
public data class AuthMeDto(
    public val userId: String,
    public val displayName: String,
    public val email: String,
    public val roles: List<String>,
    public val groups: List<String>,
    public val csrfToken: String,
    public val seesAll: Boolean,
    public val categoryCount: Int,
)

/** `POST /api/v1/auth/logout` — where the frontend must navigate next to finish RP-initiated logout. */
@Serializable
public data class LogoutResponseDto(
    public val endSessionUrl: String,
)
