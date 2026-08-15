package com.sec.api.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/auth/me` — the frontend's only source of identity (ADR 0017). Never
 * browser-cached, re-fetched on every full page load.
 *
 * `seesAll` and `categoryCount` are in `docs/features/access-control.md` §9's shape but are not
 * here yet: they come from `AccessResolver`, which does not exist until phase 2 ("no data
 * filtering yet" — this phase's own build-order entry). Adding them later is an additive,
 * backward-compatible change to this DTO, not a breaking one.
 */
@Serializable
public data class AuthMeDto(
    public val userId: String,
    public val displayName: String,
    public val email: String,
    public val roles: List<String>,
    public val groups: List<String>,
    public val csrfToken: String,
)

/** `POST /api/v1/auth/logout` — where the frontend must navigate next to finish RP-initiated logout. */
@Serializable
public data class LogoutResponseDto(
    public val endSessionUrl: String,
)
