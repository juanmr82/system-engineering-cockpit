package com.sec.security

import com.sec.api.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * The four realm roles, exactly these strings, and nowhere else (`docs/KEYCLOAK_SETUP.md` §3,
 * `docs/features/access-control.md` §3). They arrive in the ID token's `realm_access.roles` claim.
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

/**
 * Everything under [build] additionally requires [role] (`docs/features/access-control.md` §9,
 * "Guarding, once").
 *
 * **A capability, so a `403` — never a `404`.** That asymmetry is the whole of §7's rule and it is
 * not an oversight here: an *object* the caller may not read must be indistinguishable from one
 * that does not exist, because a `403` on it would confirm it exists. A *capability* has nothing to
 * conceal — "you are not an administrator" tells an attacker what they already know about
 * themselves — and answering `404` instead would leave a `sec-user` unable to tell a route they may
 * not use from one this deployment does not have.
 *
 * Applied to a route group, never as an `if` in a handler. Nesting is what makes it composable:
 * this must sit *inside* [requireSecSession], because a caller with no session has no roles to
 * check and has already been answered with the `401` that authentication owns. A missing principal
 * here therefore means the wrapper was mounted outside the session guard, which is a wiring defect
 * rather than a request to refuse — hence the explicit failure rather than a quiet `403`.
 *
 * The child route is [RouteSelectorEvaluation.Transparent] so it adds a *check* and not a path
 * segment: `requireRole(Role.ADMIN) { post("/settings") { } }` still answers `POST /settings`.
 *
 * Roles are read from the token on every refresh (§11), so revoking one takes effect within the
 * access token's lifetime rather than at the next sign-in.
 */
public fun Route.requireRole(role: String, build: Route.() -> Unit): Route {
    // A **route-scoped plugin**, not a bare `intercept`, and a **fresh selector instance** per call.
    // Both were learned the hard way and both are load-bearing:
    //
    //  - `createChild` *reuses* an existing child whose selector compares equal, so a shared
    //    `object` selector made every `requireRole` in the application mount onto one node. Two
    //    guards then meant one node carrying both: `sec-admin` was demanded of `/access/reconcile`,
    //    and a holder of the right role was refused its own route. A per-call instance (identity
    //    equality) is what makes these genuinely separate subtrees.
    //  - A raw `intercept` on that child runs for everything resolved through it. `authenticate`
    //    uses a plugin rather than an interceptor for the same reason; this follows it.
    //
    // `AuthenticationChecked` is the hook that runs once the principal exists.
    // `ApplicationCallPipeline.Plugins` is too early — authentication itself runs inside it, so the
    // principal is still null and every guarded route answers 500 on the `error()` below.
    val guarded = createChild(RoleSelector(role))
    guarded.install(
        createRouteScopedPlugin("RequireRole($role)#${guardsInstalled.incrementAndGet()}") {
            on(AuthenticationChecked) { call ->
                // This hook runs after authentication whether or not it *succeeded*, so a request
                // with no session arrives here with a null principal and its own 401 already
                // challenged. Returning is the only correct move: throwing turns that 401 into a
                // 500, and responding here would emit a second, competing answer. It is the same
                // trap `requireSecSession`'s CSRF check documents, met from the other direction.
                //
                // That does mean this guard is inert if it is ever mounted *outside* the session
                // guard, where no principal is ever set. `RoleGuardTest` is what rules that out —
                // it asserts each administrative route actually refuses a `sec-user`, so an inert
                // guard fails the build rather than passing quietly.
                val principal = call.principal<SecPrincipal>() ?: return@on
                if (!principal.hasRole(role)) {
                    call.respondProblem(
                        HttpStatusCode.Forbidden,
                        "Not permitted",
                        // Names the capability required, per spec §10.1: the frontend renders this
                        // as an in-app refusal panel rather than redirecting, so the sentence has to
                        // carry the whole explanation. It names a role the caller lacks and nothing
                        // about the object they were reaching for.
                        "This action needs the '$role' role, which this account does not have.",
                    )
                }
            }
        },
    )
    guarded.build()
    return guarded
}

/**
 * Makes each guard its own subtree.
 *
 * Deliberately **not** an `object` and deliberately without `equals`: identity equality is the whole
 * point, because `createChild` hands back an existing child whose selector compares equal.
 */
private class RoleSelector(private val role: String) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(role:$role)"
}

/** Route-scoped plugin names must be unique per pipeline; the same role guards several subtrees. */
private val guardsInstalled = AtomicInteger(0)
