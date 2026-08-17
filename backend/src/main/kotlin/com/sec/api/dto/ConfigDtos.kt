package com.sec.api.dto

import kotlinx.serialization.Serializable

/** `GET /api/v1/config/navigation` — the sidenav's structure, read-only (`config/NavigationSettings.kt`). */
@Serializable
public data class NavItemDto(
    public val key: String,
    public val label: String,
    public val route: String,
)

@Serializable
public data class NavGroupDto(
    public val key: String,
    public val label: String,
    public val items: List<NavItemDto>,
)

@Serializable
public data class NavigationResponseDto(
    public val groups: List<NavGroupDto>,
)
