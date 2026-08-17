# ADR 0017 — The browser never holds a token: the backend is the OIDC client

**Status:** accepted, 2026-08-15 · **Depends on:** ADR 0016
**Implemented by:** `docs/features/access-control.md` §11, `../KEYCLOAK_SETUP.md`

---

## 1. Decision

The **backend** is the OIDC client. It runs the Authorization Code flow with PKCE against Keycloak,
keeps the tokens server-side, and gives the browser one opaque, `HttpOnly` session cookie. The
Angular application has **no OIDC library, no token, no refresh timer, and no `Authorization`
header** — it makes ordinary same-origin requests with credentials and reacts to `401`.

This is the pattern the OAuth working group calls **BFF (Backend for Frontend)**, and it is the
current recommendation for browser applications.

## 2. Why, here specifically

- **The deployment is already same-origin.** `mvn -Pui package` puts the built frontend inside the
  backend jar and `UiRoutes.kt` serves it. A cookie session is therefore the *simpler* option, not
  the more complex one: no CORS, no `Access-Control-Allow-Credentials`, no preflight on every call.
  In development, `ng serve` proxies `/api` to `:8080` (`../../frontend/proxy.conf.json`), so same-origin
  holds there too — **this proxy is now load-bearing, not a convenience.**
- **A token in the browser is a token XSS can take.** This application renders `Object Text` from
  DOORS and summaries from JIRA — attacker-adjacent content in an internal tool is still content we
  did not write. An `HttpOnly` cookie is not reachable from script.
- **Refresh becomes invisible.** Token lifetime, rotation and clock skew are a server concern with a
  server clock. No silent-renew iframe, no third-party-cookie problem, nothing that breaks when the
  company brokers its IdP behind a proxy that dislikes iframes.
- **The frontend stays boring.** No auth library in `package.json` (§4 keeps that list short), and
  `httpResource()` calls need one global change — `withCredentials` — not a token interceptor.

## 3. What this costs, and the shape of each cost

| Cost | Answer |
|---|---|
| CSRF is now possible (cookies are sent automatically) | `SameSite=Lax` **plus** a double-submit token required on every non-`GET`. Both, not either. §11.4 of the spec |
| Sessions live in backend memory | One instance today. A restart signs everyone out — acceptable, and it is *stated* in the spec so it is not diagnosed twice. If a second instance ever exists, this decision is revisited before, not after |
| Ktor's OAuth provider is thinner than a dedicated library | The flow is ~150 lines: authorize redirect, callback, JWKS-validated ID token, session. Verify PKCE support against the pinned Ktor 3.5.x API and pass `code_challenge` through `extraAuthParameters` if the provider does not expose it directly. **PKCE is not optional even for a confidential client** |
| Logout must reach Keycloak | RP-initiated logout: drop the session, then redirect to the end-session endpoint with `id_token_hint` |

## 4. Rejected

- **Public client + `angular-auth-oidc-client`, bearer tokens.** Fewer backend lines, one more
  dependency, and the token lands in browser memory. Would have been the answer if the SPA were
  served from a different origin than the API. It is not.
- **`resource server only`, token minted by something else.** There is nothing else.
- **Basic auth or a home-made login.** Not with a corporate IdP six months away.

## 5. Non-negotiable consequences

- **No endpoint under `/api/v1` is reachable without a session**, except `/health`, `/ready`, and the
  three `/auth/*` endpoints that establish one. Enforced by installing the authentication on the
  route tree, not per route — a route that forgets is a route that is guarded anyway.
- **`401` means "no session", `403` means "session, wrong capability".** The frontend treats them
  differently: `401` navigates to login, `403` renders a refusal. Conflating them produces a redirect
  loop on a permission error, and that loop is very hard to read from a screenshot.
- **The session cookie name, the CSRF header name and the cookie flags are declared once**, next to
  `ApiPaths.kt`, for the same reason every graph name is (ADR 0010).
