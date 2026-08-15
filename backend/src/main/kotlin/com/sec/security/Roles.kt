package com.sec.security

/**
 * The four realm roles, exactly these strings, and nowhere else (`docs/KEYCLOAK_SETUP.md` §3,
 * `docs/features/access-control.md` §3). They arrive in the ID token's `realm_access.roles` claim.
 *
 * `requireRole(Role.X)` — the route-group plugin that checks these — arrives with the routes it
 * guards (phase 5 of the build order: the settings and access subtrees). Nothing in phase 1 needs
 * it, and a plugin with no route attached to it is untested scaffolding, so it is not built yet.
 */
public object Role {
    /** Every authenticated user. Read what their groups allow; write Tier 2 on what they can see. */
    public const val USER: String = "sec-user"

    /** The settings subtree: importer configuration, module settings, running an import. Not visibility. */
    public const val ADMIN: String = "sec-admin"

    /** The Access views: categories, grants, containers, defaults, reconcile. Not visibility. */
    public const val ACCESS_MANAGER: String = "sec-access-manager"

    /** Phase 7. Read the access configuration and the audit trail; change nothing. */
    public const val AUDITOR: String = "sec-auditor"
}
