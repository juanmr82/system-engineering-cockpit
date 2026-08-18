package com.sec.config

import io.ktor.server.config.ApplicationConfig

/**
 * Everything the backend needs to be the OIDC client (ADR 0017).
 *
 * Unlike [JiraSettings] / [WindchillSettings], this is not an optional integration: with
 * authorization on, every route but the declared exceptions requires a session, and a session can
 * only be created through this. A deployment with no [clientSecret] cannot serve anything, so —
 * like [Neo4jSettings] — the secret fails to load rather than defaulting to "not configured".
 */
public data class AuthSettings(
    /** The realm's issuer URL, e.g. `http://localhost:8081/realms/sec`. No trailing slash. */
    public val issuer: String,
    public val clientId: String,
    /**
     * Write-only from the application's point of view: read once at startup, used only to
     * authenticate the token exchange and the refresh call. Never logged, never returned by an
     * endpoint, never written to the graph. [toString] is overridden for the same reason
     * [JiraSettings.toString] is.
     */
    public val clientSecret: String,
    /**
     * The exact, fully-qualified `redirect_uri` this deployment registered in Keycloak
     * (`docs/KEYCLOAK_SETUP.md` §2). Built from configuration rather than from the inbound
     * request's `Host` header, so it cannot be steered by a spoofed header and always matches
     * what Keycloak was told to expect.
     */
    public val callbackUrl: String,
    /**
     * Where to send the browser after a successful callback and after logout, when that is a
     * *different* origin than this backend's own.
     *
     * Empty (the default) means "same origin as this backend" — the packaged deployment, where
     * `UiRoutes` serves the built Angular app from this same jar (R... CLAUDE.md §5 "The built
     * frontend ships inside the backend jar"). In development the Angular app runs on `ng serve`'s
     * own origin, which Keycloak never redirects to directly — only this backend's registered
     * callback URI — so the backend has to hand the browser onward itself. Set to
     * `http://localhost:4200` for a local `ng serve`.
     */
    public val frontendBaseUrl: String = "",
    /**
     * The `azp` a DOORS push bearer token must carry (ADR 0020) — a second, machine-only Keycloak
     * client (`docs/KEYCLOAK_SETUP.md` §2b), distinct from [clientId]. Blank means the feature is
     * off for this deployment: no secret to fail hard on, because unlike [clientId] the whole
     * application does not depend on it existing — the same optional shape [WindchillSettings] has,
     * not the all-or-nothing shape the rest of this class has. The backend never calls this
     * client's token endpoint itself, so it holds no secret for it, only the id it checks tokens
     * against.
     */
    public val doorsPushClientId: String = "",
) {
    /** `POST /doors/import/push` answers `503` rather than authenticating anyone when this is false. */
    public val isDoorsPushConfigured: Boolean get() = doorsPushClientId.isNotBlank()

    override fun toString(): String =
        "AuthSettings(issuer=$issuer, clientId=$clientId, callbackUrl=$callbackUrl, " +
            "frontendBaseUrl=$frontendBaseUrl, doorsPushClientId=$doorsPushClientId, clientSecret=<redacted>)"
}

/**
 * Reads the `auth` block. Deliberately total for [AuthSettings.issuer] / [AuthSettings.clientId] /
 * [AuthSettings.callbackUrl] — sensible dev-Keycloak defaults so a fresh clone boots against
 * `docs/KEYCLOAK_SETUP.md`'s realm with nothing but the one secret set — and deliberately NOT
 * total for [AuthSettings.clientSecret], which has no default and fails to load when unset, the
 * same deliberate behaviour [Neo4jSettings] has and [JiraSettings] does not: this is
 * infrastructure every deployment needs, not an optional source.
 */
public fun loadAuthSettings(config: ApplicationConfig): AuthSettings =
    AuthSettings(
        issuer = config.stringOr("auth.issuer", "http://localhost:8081/realms/sec"),
        clientId = config.stringOr("auth.clientId", "sec-backend"),
        clientSecret = config.property("auth.clientSecret").getString(),
        callbackUrl = config.stringOr("auth.callbackUrl", "http://localhost:8080/api/v1/auth/callback"),
        frontendBaseUrl = config.stringOr("auth.frontendBaseUrl", "").trimEnd('/'),
        doorsPushClientId = config.stringOr("auth.doorsPushClientId", ""),
    )

private fun ApplicationConfig.stringOr(path: String, fallback: String): String =
    propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: fallback
