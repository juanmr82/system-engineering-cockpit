package com.sec.config

import io.ktor.server.config.ApplicationConfig

/**
 * The sidenav's structure and order — application configuration, not graph data (root CLAUDE.md
 * §2, "Where a given piece of state lives"): the same for every user, and changed only when we
 * ship. Served read-only at `GET /api/v1/config/navigation`.
 *
 * **Deliberately carries no `role` field.** Which group a user may *see* is a frontend decision
 * (root CLAUDE.md §9's backend build order, step 9) — a per-item role here would be a second,
 * redundant encoding of [com.sec.security.Role.ACCESS_MANAGER] that could drift from the real one
 * enforced at the route. This file states order and labels; the frontend states who sees what.
 */
public data class NavItemConfig(
    public val key: String,
    public val label: String,
    public val route: String,
)

public data class NavGroupConfig(
    public val key: String,
    public val label: String,
    public val items: List<NavItemConfig>,
)

public data class NavigationSettings(
    public val groups: List<NavGroupConfig>,
)

/**
 * Reads the `navigation.groups` block. Absent means empty, not a startup failure — the same
 * contract [loadWindchillSettings] gives an absent `windchill` block, and what lets a test of the
 * HTTP surface construct [com.sec.configureApp] with no `application.yaml` in scope at all.
 *
 * `configList` itself throws on a missing path (unlike `property`, there is no `configListOrNull`
 * in Ktor's [ApplicationConfig]), so the presence check has to happen through `propertyOrNull`
 * first — checking a leaf, not the list node, is deliberate: `propertyOrNull` on a path that
 * resolves to a list or object still answers non-null, only a wholly absent path answers null.
 */
public fun loadNavigationSettings(config: ApplicationConfig): NavigationSettings {
    if (config.propertyOrNull("navigation.groups") == null) {
        return NavigationSettings(groups = emptyList())
    }
    return NavigationSettings(
        groups = config.configList("navigation.groups").map { group ->
            NavGroupConfig(
                key = group.property("key").getString(),
                label = group.property("label").getString(),
                items = group.configList("items").map { item ->
                    NavItemConfig(
                        key = item.property("key").getString(),
                        label = item.property("label").getString(),
                        route = item.property("route").getString(),
                    )
                },
            )
        },
    )
}
