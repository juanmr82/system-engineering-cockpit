# Keycloak — the realm SEC expects

The operator-facing half of ADR 0016 and ADR 0017. If a setting is not here, SEC does not depend on
it. If SEC stops working after a Keycloak change, the change was to something on this page.

**SEC's entire dependency on Keycloak is three claims in one token: `sub`, `groups`, and
`realm_access.roles`.** Everything else — password policy, MFA, session limits, brokering, user
federation — is yours to configure freely.

---

## 1. Realm

One realm, `sec`. Nothing about the model needs a second one.

| Setting | Value | Why |
|---|---|---|
| Access token lifespan | **5 minutes** (the default) | group membership is re-read on refresh (spec §11). A long access token is a long window in which a removed user keeps their access |
| SSO session idle / max | your corporate norm | SEC holds its own server-side session and refreshes against these |
| Login theme | whatever you like | SEC never renders a login form |

---

## 2. Client — one, confidential

| Setting | Value |
|---|---|
| Client ID | `sec-backend` |
| Client authentication | **On** (confidential) |
| Standard flow | **On** |
| Direct access grants | **Off** — there is no password endpoint in SEC and there must not be |
| Implicit flow | **Off** |
| Service accounts | **Off** — SEC never calls the Admin API (spec §2) |
| PKCE method | **S256**, required |
| Valid redirect URIs | `https://<host>/api/v1/auth/callback` and, for development, `http://localhost:8080/api/v1/auth/callback` |
| Valid post-logout redirect URIs | `https://<host>/` (+ the localhost equivalent) |
| Web origins | `+` (matches the redirect URIs). No wildcard |

The secret goes in the environment as `SEC_OIDC_CLIENT_SECRET`, read by `application.yaml`, which
**fails to load when it is unset** — the same deliberate behaviour as the Neo4j credentials. It is
never written to a file in the repository, and `scripts/win/sec-env.local.ps1` (git-ignored) is where
it lives on a workstation.

There is **no frontend client.** The browser is not an OAuth client (ADR 0017).

---

## 3. Roles — realm roles, four, exactly these strings

```
sec-user               ← make this a Default role of the realm
sec-admin
sec-access-manager
sec-auditor            ← phase 7, create it now so the string is settled
```

Realm roles reach the token in `realm_access.roles` with no mapper needed. **Do not use client
roles**; they arrive under `resource_access.<client>.roles` and would make the parsing depend on the
client id.

Assign `sec-admin` and `sec-access-manager` to *individuals*, not to groups, unless your directory
already models them — a role that arrives with a data-access group is a role that spreads with it.

---

## 4. Groups and the `groups` claim

Groups are the data-access axis. Their names are yours; SEC treats each as an opaque key.

Suggested shape, since paths are what SEC stores:

```
/SEC/Thermal
/SEC/Avionics
/SEC/Programme-Management
/SEC/All-Read            ← the "sees everything" group, if you want one
```

**Add the group membership mapper**, or the claim will not be there and every user will see an
empty application (spec §12 logs exactly this at `WARN`):

- Client scopes → `sec-backend-dedicated` → Add mapper → By configuration → **Group Membership**
- Name: `groups` · Token Claim Name: `groups` · **Full group path: On**
- **Add to ID token: On** · Add to access token: On · Add to userinfo: On

*Full group path on* is deliberate: `/SEC/Thermal` cannot collide with a `Thermal` group somewhere
else in the tree, and a bare name can.

**Renaming a group breaks its grants.** SEC keys `:__Group` on the claim string, so a rename looks
like a new group with no grants — members silently lose access until an access manager re-grants.
Because groups are administered here and stay here (§6), this is entirely within your control:
**treat a group path as a permanent identifier and change the display name instead.** Keycloak group
names *are* the path, so the practical rule is: name a group once, and if it must be renamed, do it
as a coordinated change with a re-grant in SEC on the other side.

`seesAll` is **not** a Keycloak concept. It is a flag an access manager sets on a `:__Group` inside
SEC, so that "this group sees everything" is a decision recorded and audited where the data is.

---

## 5. User identity

SEC displays `preferred_username`, `name` and `email`, and keys nothing on them —
`sub` is the identity. Using the company user id as `preferred_username` from day one, as intended,
is exactly right and costs nothing later: when brokering arrives, map the company id onto
`preferred_username` and every display in SEC continues to read the same.

**Nothing in the graph stores a user id except `__createdBy` / `__updatedBy` on Tier-2 nodes**, which
were already `preferred_username`-shaped strings.

---

## 6. Later: brokering the company IdP — authentication only

**Groups stay in this realm.** The company IdP is brokered to answer one question — is this person
who they say they are — and to supply the company user id. It is not asked to know anything about
this project, and group membership continues to be administered here, in the `sec` realm, exactly as
it is today.

That is what makes the cutover a non-event: **before and after, SEC receives the same three claims
from the same issuer.** No code changes, no dual-mode token handling, no day on which SEC has to
understand two shapes.

The checklist:

1. Identity providers → OIDC/SAML → the company IdP. Realm `sec` stays the issuer SEC talks to.
2. **First broker login flow: `Automatically link existing account`** on username or email, so a user
   created locally today is the *same* user once brokered — and keeps their group memberships and
   roles through the cutover. **Decide this before the first real user exists**; retro-linking
   accounts is manual work, one user at a time.
3. Map the company user id onto `preferred_username` (Attribute Importer mapper). Since you are
   already using the company user id locally, existing accounts match and step 2 does the rest.
4. **Do not add a group mapper on the identity provider.** No `Advanced Claim to Group`, no
   hardcoded group per IdP. Groups are assigned here; an IdP mapper would fight the local assignment
   on every login and the winner would depend on the sync mode.
5. Consider setting the IdP's sync mode to `Import` (attributes applied at first login only) rather
   than `Force`, so nothing the company sends can overwrite what you administer locally. Confirm this
   against the mappers you actually configure.
6. Rehearse against a test IdP in phase 7, and check **the link, not the login**: sign in as a user
   who already exists locally and confirm they arrive with their groups intact rather than as a new,
   empty account.

### The one workflow this creates: new users land in no group

A brokered user is created on first login and belongs to no group, so they authenticate perfectly
and see an application with nothing in it. That is fail-closed and correct, but somebody has to
notice.

- SEC's `/access/groups` view lists every `:__Group` it has ever seen; it does **not** list users
  waiting for one, because SEC never enumerates the directory (spec §2).
- So the trigger is the person, not the tool: the empty state on first login must say, in plain
  words, that access is granted per group and name who to ask. Copy it as *"Your account is active
  but has not been given access to any data yet — contact ‹the access manager›."*
- Do **not** solve this by giving new users a default group. A default group is a grant that nobody
  decided to make.

---

## 7. Development

`deploy/docker-compose.dev.yml` gains a Keycloak service beside Neo4j, in dev-mode, with an imported
realm file (`deploy/keycloak/sec-realm.json`) so the realm, client, roles and three test groups come
up with the container and are the same for everyone. Commit the realm export; **strip the client
secret from it** and inject a fixed dev secret through the environment.

Three test users, committed in the realm export, because the visibility matrix is much easier to
believe on screen than in a test report:

| User | Roles | Groups |
|---|---|---|
| `sec-dev-user` | `sec-user` | `/SEC/Thermal` |
| `sec-dev-admin` | `sec-user`, `sec-admin`, `sec-access-manager` | `/SEC/All-Read` |
| `sec-dev-nogroup` | `sec-user` | *(none)* — the empty-application case |

### The workstation that has no Docker

`docs/RUNNING.md` describes a Windows box with no admin rights, no Docker and proxy-only internet —
the only machine that can talk to DOORS. Keycloak runs there as **an unzipped distribution started
from the console**, exactly as Neo4j already does (`sec-neo4j.ps1` is the pattern to copy into
`sec-keycloak.ps1`), on a port above 1024, with `KC_HTTP_ENABLED=true` bound to localhost.

Add it to `sec-up.ps1` as a fourth service and to `sec-doctor.ps1` as one more line. **`sec-up.ps1`
must start Keycloak before the backend**, since the backend fetches the discovery document at
startup — and the backend must survive it being absent by failing its readiness probe rather than
its liveness probe (spec §12).

---

## 8. The five things that will go wrong, in order of likelihood

1. **No `groups` claim.** Everyone sees an empty application and nothing looks broken. §4's mapper.
   SEC logs the claim names it *did* receive — read that log line first.
2. **Redirect URI mismatch.** Keycloak refuses at the callback with its own error page, which is not
   an SEC error page. Check the trailing path exactly: `/api/v1/auth/callback`.
3. **A dev login over `http://` on something that is not `localhost`.** The session cookie is
   `Secure` unconditionally (ADR 0017) so the browser drops it and the user bounces back to login
   forever. Use `localhost`, not the machine name or `127.0.0.1`-vs-`localhost` mixed.
4. **`ng serve` bypassing the proxy.** The frontend must reach `/api` on its own origin
   (`frontend/proxy.conf.json`). A hardcoded `http://localhost:8080` in a service makes every request
   cross-origin and cookieless.
5. **Group renamed in Keycloak.** Access disappears for its members with no error anywhere. §4.
