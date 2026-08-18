# 0020 — DOORS push import: a technical Keycloak account, bearer-authenticated

Status: accepted
Date: 2026-08-18

## Context

ADR 0019 built the first front door for an already-produced DOORS export to reach this backend
without the Windows/DOORS-client pipeline: an admin uploads the file in a browser, session-cookie
and `sec-admin`-role authenticated, to `POST /api/v1/doors/import`. That ADR named the door it was
not yet building: *"an external exporter that pushes a module's export straight to this backend is
planned but not yet built... [the checksum gate] is deliberately the same gate the planned push
importer will call. It is not upload-page logic; it is import logic that happens to have two front
doors."*

This is that second door. The caller is a technical/machine account run by an external DOORS
exporter service — no browser, no human clicking through a Keycloak login page, and potentially
several such accounts over time (one per exporter instance or site). Every authentication mechanism
this backend has today (ADR 0017, the BFF pattern: `HttpOnly` cookie session, OIDC Authorization
Code + PKCE) is built entirely around a browser holding a cookie. A machine account needs a
credential it can present programmatically, on its own schedule, without a browser round trip —
and it needs one that does not compromise ADR 0017's own reasoning for the browser client, which
turned off Keycloak's password grant deliberately (`docs/KEYCLOAK_SETUP.md` §2: "there is no
password endpoint in SEC and there must not be").

## Decision

### 1. A second, dedicated Keycloak client — `sec-doors-push` — carries the password grant, not `sec-backend`

`sec-backend`'s own Direct Access Grants setting stays `Off`, unchanged, for the reason already on
record: a password reaching this application through a browser form is exactly the thing ADR 0017
ruled out. A **machine** account has no browser and no form — it calls Keycloak's token endpoint
directly, over a channel this application never sees or brokers. Confining Direct Access Grants to
a second, narrowly-scoped client (`docs/KEYCLOAK_SETUP.md` §2b) rather than turning the setting on
for `sec-backend` keeps that boundary exact: nothing about the browser-facing client's posture
changes, and a compromise of one credential type cannot be laundered through the other client's
configuration.

A technical account is an ordinary Keycloak **user** — `svc-doors-<name>` — with a generated
password, a member of a group (`/SEC/Importers`, or whichever an operator chooses) exactly like a
human user, and **no realm role** (§3). It is deliberately **never brokered** to the company IdP
(ADR 0016 §3.1): brokering exists to answer "is this person who they say they are," and a technical
account has no person behind it for that question to be about. It stays a local account in the
`sec` realm permanently — the one kind of Keycloak identity brokering was never meant to reach.

### 2. The backend gets a second authentication provider, bearer-token, parallel to the session one

`security/Oidc.kt` gains `validatePushAccessToken(token: String): SecPrincipal`, reusing the same
JWKS/discovery machinery `validateIdToken` already holds, with two departures from that method
worth stating because they are easy to get backwards:

- **No audience check.** An ID token is audience-bound to the requesting client by default; a
  Keycloak access token is not — its default audience is `account`, not the client id, absent a
  custom Audience mapper this deployment does not add. `azp` (authorized party) carries the whole
  check.
- **The `azp` check is unconditional.** `validateIdToken` treats `azp` as optional ("when the token
  carries one"), because an ID token's audience binding already does most of the work. A push
  access token with no `azp` at all is rejected outright — `azp == "sec-doors-push"` or nothing.

`Application.kt` installs this as a second Ktor `bearer {}` provider
(`security.PushAuthNames.PROVIDER`), beside the existing `session<UserSession>(SessionNames.PROVIDER)`
one, inside the same `install(Authentication) { }` block. It builds a `SecPrincipal` directly — the
same type the session path builds via `UserSession.toPrincipal()` — so every downstream consumer
(`call.accessSet(accessResolver)`, `AccessResolver.resolve`) works unchanged regardless of which
provider authenticated the call. `csrfToken` is empty on this principal; it is read only inside
`requireSecSession`, which this route never passes through.

**Ktor's built-in `bearer {}` provider has no `challenge { }` hook to override** (unlike
`session<UserSession>`'s own), so every rejection on this route — no header, wrong scheme, or
`authenticate` returning `null` — answers Ktor's own bare `401` with a `WWW-Authenticate: Bearer`
header, not this application's usual RFC 9457 problem-detail body. This is a deliberate, narrow,
accepted exception: there is no browser and no human reading `.detail` on the other end of this
route, so the standard `WWW-Authenticate` challenge is itself the correct machine-readable signal,
and hand-rolling a hand-written route-scoped auth plugin purely to get a JSON body — the way
`requireSecSession`/`requireRole` are hand-rolled for reasons that actually change behaviour —
would trade a cosmetic gain for authentication-state plumbing this codebase otherwise avoids.
`Oidc.validatePushAccessToken`'s own two service-level failures — Keycloak unreachable, or the
feature unconfigured on this deployment — are not swallowed by that provider, though: they are
allowed to propagate to `StatusPages`, which already answers both as ordinary RFC 9457 problem
details (a `503`, in both cases), the same as everywhere else.

### 3. No new realm role. Capability is "this token came from `sec-doors-push`"

`docs/KEYCLOAK_SETUP.md` §3's four realm roles are unchanged. The push route carries **no**
`requireRole` at all: reaching it already proves the caller holds a token minted by the
`sec-doors-push` client, and only a technical import account is ever given credentials against that
client. That fact *is* the capability check — narrower and simpler than a role, since a role would
have to be kept from ever being granted to a human account by policy alone, where an audience check
is kept by construction. `sec-admin`, the role `POST /doors/import` (the browser door) requires,
plays no part here; a technical account has no business holding it and never will.

### 4. One gate, two doors, made literal in code

`api/routes/DoorsRoutes.kt`'s existing `POST /import` handler body — parse, size-limit, the
`DoorsModuleGateway` visibility/checksum gate (ADR 0019 §3, §4), the three-outcome response — is
factored into one private `handleDoorsImport(call, gateway, importRunService, access)`, called by
both `doorsRoutes()` (session-cookie, `sec-admin`, unchanged) and the new `doorsPushRoutes()`
(bearer, no role). ADR 0019's own words — "two front doors," "not upload-page logic" — are now
provably true of the code, not just the write-up: there is exactly one place either door can start
a run.

`doorsPushRoutes()` is a separate function, not a branch inside `doorsRoutes()`, because it mounts
at a different point in the routing tree: `api/Routes.kt` registers it **outside**
`requireSecSession { }`, alongside `healthRoutes`/`authRoutes`, since nesting a
bearer-authenticated route inside a wrapper that demands a session cookie would make it
unreachable by the caller it exists for.

### 5. "Assign categories to the group" needs no new code

`/SEC/Importers` (or any group a technical account carries) is created on sight in the graph the
first time a token naming it is resolved — `AccessResolver.resolve(groups)`, called from
`call.accessSet(accessResolver)` exactly as every other route already calls it, `MERGE`s a
`:__Group` node regardless of which authentication provider populated `principal.groups`
(`docs/features/access-control.md` §5). It shows up under **Access → Groups** the moment the first
push happens, and a `sec-access-manager` grants it categories through the existing
`PUT /api/v1/access/groups/{ref}/grants` — unmodified. The access-control model was already
source-agnostic about *how* a group's members authenticate; this is the first time that generality
was load-bearing rather than incidental.

## Consequences

- **ADR 0017 §5's first "non-negotiable consequence" is no longer true verbatim.** It reads: "No
  endpoint under `/api/v1` is reachable without a session, except `/health`, `/ready`, and the
  three `/auth/*` endpoints." Read narrowly, `POST /doors/import/push` is now reachable without a
  session — but it is not reachable with *no credential*, the way the four named exceptions are; it
  is authenticated by a second, independent provider. The sentence should be read from here forward
  as "...without a session, or without the bearer-authenticated exception ADR 0020 adds."
- **`docs/KEYCLOAK_SETUP.md` §2's "Direct access grants — Off" is not reversed.** That row names
  `sec-backend` specifically, in a table titled "Client — one, confidential" that was already
  per-client in scope. It stays `Off` there; `sec-doors-push` is the one deliberate, narrow,
  second exception, restated in §2b.
- **A second credential type exists in this realm now, and it needs its own operational care.**
  Password rotation, and revoking a compromised technical account, are Keycloak-console operations
  on a user, same as any other — but unlike a human account there is no login screen that would
  make a forgotten rotation obvious. Worth a periodic-review note in whatever runbook already
  covers Keycloak administration, not a code change.
- **The 401 on this one route is shaped differently from every other 401 in the API** (§2) — a
  framework default, not an RFC 9457 body. Documented rather than hidden, and revisited only if a
  future Ktor version exposes a `challenge` hook on `bearer {}`, or if a consumer of this route
  turns out to need the structured body after all.
- **Two DOORS front doors now share one gate in code, not just in a document.** A future change to
  the visibility/checksum gate (ADR 0019 §3, §4) changes both doors by construction; there is no
  way to update one and silently leave the other on old behaviour.
