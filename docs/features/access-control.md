# Access control — authentication, roles, and object-level visibility

**Status:** specified, not built. **Decisions:** ADR 0016 (the model), ADR 0017 (the session).
Read both before this; they hold the *why* and this holds the *what*. Where they disagree, they
do not — say so and stop.

This is the largest cross-cutting change the backend has taken. It touches every read path, every
route, the shell, and the import pipeline. Build it in the order of §15 and nothing else.

---

## 1. What this feature is

Three separable things that arrive together and must not be conflated in code:

| | Question it answers | Where it is decided | Wire failure |
|---|---|---|---|
| **Authentication** | Who is calling? | Keycloak, via a server-side session (ADR 0017) | `401` |
| **Capability** | May this user *administer* things? | A realm role claim | `403` |
| **Visibility** | Which objects may this user read? | Neo4j, per request, as a `WHERE` clause | `404`, never `403` |

**The last row is the whole feature.** The first two are plumbing that many applications have; the
third is the one that has to be right on every query, forever.

### What it is not

- Not row-level *write* control. Tier-2 writes are gated by capability plus visibility of the anchor;
  there is no per-object write permission and no concept of an owner.
- Not deny rules. Grants are **additive only** — the union of what your groups may read. There is no
  negative permission, because a deny rule turns an index lookup into an evaluation and turns "why
  can't I see this" into a debugging session. If a future requirement needs one, it needs an ADR.
- Not field-level or attribute-level masking. An object is visible or it is not.

---

## 2. The split — what lives where

| Kind of state | Owner | Read by SEC as |
|---|---|---|
| User identity, credentials, MFA, password policy | Keycloak — later the company IdP, brokered | `sub`, `preferred_username` claims |
| Group membership | **Keycloak, in our own realm — and it stays there after brokering** (ADR 0016 §3.1) | a `groups` claim: a list of strings |
| Capability roles | Keycloak realm roles | a `realm_access.roles` claim |
| Access categories, and which objects are in them | **SEC / Neo4j** | the graph |
| Which group may read which category | **SEC / Neo4j** | the graph |
| Import-time default categories | **SEC / Neo4j** | the graph |

**SEC never calls the Keycloak Admin API.** No client credentials, no service account, no group sync
job. The entire integration surface is *the claims in one token*.

**Brokering moves authentication and the user id outward, and nothing else.** Groups continue to be
administered in our own realm and assigned to accounts the broker links to on first login, so the
issuer SEC talks to, and the three claims it reads, are identical before and after (ADR 0016 §3.1).
The one operational consequence is that a brokered user arrives in **no** group and sees an empty
application until someone assigns them — fail-closed, and the empty state has to say so in words
(`docs/KEYCLOAK_SETUP.md` §6).

A group the token names and the graph has never seen is created on sight as `(:__Group {key})` with
**no grants** — so a new group appears in the Access view for an administrator to grant, and its
members see nothing in the meantime. Fail-closed and self-populating.

---

## 3. Roles — four, closed, and named once

Realm roles in Keycloak, `Role` constants in `security/Roles.kt`, and nowhere else.

| Role | May | May not |
|---|---|---|
| `sec-user` | read what their groups allow; write Tier 2 (comments, reviews, tags) on objects they can see | reach `/settings/*` or any access administration |
| `sec-admin` | everything under `/settings/*`: importer configuration, JIRA connection, column sets, module settings, mandatory attributes, running an import | grant access, or see one object more than their groups allow |
| `sec-access-manager` | the Access views: create categories, assign categories to containers, grant categories to groups, set import defaults, run reconcile | change importer or application settings |
| `sec-auditor` *(optional, phase 7)* | read the access configuration and the audit trail | change anything |

**`sec-admin` does not imply visibility, and `sec-access-manager` does not imply visibility.** Both
axes are independent, deliberately (ADR 0016 §7). An access manager who is in no group administers
an application that shows them a grants matrix and no data. Say this in the UI copy of the Access
view so it is not filed as a bug.

Every user needs `sec-user`; it is the default realm role. The others are additive.

---

## 4. The graph model

### 4.1 Nodes and relationships

```cypher
// The category: a Tier-2 meta node, Shape D (ADR 0016 §6.1)
(:__Meta:__AccessCategory {
   __metaId, __metaKind: 'accessCategory', __schemaVersion: 1,
   __createdBy, __createdAt, __updatedBy, __updatedAt,
   key,           // stable, human-typed, unique: 'doors-srd', 'jira-avionics', 'documents-all'
   name,          // what a user sees
   description,
   everyGroup     // bool, default false — see §8.4
})

// Membership: a BARE relationship. No node per pair. This is the hot path.
(:SEItem)-[:__inAccessCategory {
   origin,        // 'direct' | 'inherited'
   via,           // __id of the container it was inherited from; null when direct
   __createdBy, __createdAt
}]->(:__AccessCategory)

// The group mirror: NOT :__Meta (ADR 0016 §6.2)
(:__Group { key, name, seesAll, firstSeenAt, lastSeenAt })
   //  key = the exact string from the token's groups claim, e.g. '/SEC/Thermal'

// The grant
(:__Group)-[:__mayRead { __createdBy, __createdAt }]->(:__AccessCategory)

// Import defaults: NOT :__Meta (ADR 0016 §6.2)
(:__AccessDefault { sourceId, containerLabel })-[:__assigns]->(:__AccessCategory)
```

**Read `(:__Group)-[:__mayRead]->(:__AccessCategory)<-[:__inAccessCategory]-(:SEItem)` as a sentence.**
That sentence is the entire authorization model; if an explanation needs more than it, the
explanation is of an implementation detail.

### 4.2 Names

All of these are source-agnostic and therefore belong in **`domain/GraphNames.kt`** — `NodeLabel`,
`Rel`, `MetaKind`, `MetaProp` — per ADR 0010. **Do not create `security/AccessNames.kt`**; a second
declaration of a graph name is the thing that file exists to prevent. `GraphNamesTest` must be
extended to cover the new statements, and it fails the build if it is not.

### 4.3 Schema — added to `meta/MetaSchema.kt`, applied at startup

```cypher
CYPHER 25
CREATE CONSTRAINT access_category_key IF NOT EXISTS
FOR (c:__AccessCategory) REQUIRE c.key IS UNIQUE;

CYPHER 25
CREATE CONSTRAINT group_key IF NOT EXISTS
FOR (g:__Group) REQUIRE g.key IS UNIQUE;
```

`:__AccessCategory` also carries `:__Meta`, whose `__metaId` uniqueness constraint already exists —
do not add a second (§7: a constraint creates its own backing index and a duplicate errors).

No index on `__inAccessCategory` properties. The relationship is traversed from a bound node on one
side and a pinned category on the other; there is nothing to index.

### 4.4 What must never be true

- **No property or label is ever written to an imported node.** Tagging creates a *relationship from*
  the item. R1 is intact and the existing byte-identical-anchor test is extended to cover it.
- **No `:__AccessCategory` is ever the target of `refersTo` or `__child`, and it never carries
  `:SEItem`.**
- **`MATCH (m:__Meta) DETACH DELETE m` still removes all of it**, leaving an application in which
  nobody can see anything. That is the correct direction to fail.

---

## 5. The resolver — token to a set of category ids

`security/AccessResolver.kt`. One class. It is the only thing that reads the `groups` claim.

```kotlin
public data class AccessSet(
    val seesAll: Boolean,
    val categoryIds: List<String>,   // __metaId values, sorted — the sort makes it a cache key
)
```

Resolution, in order:

1. Take the `groups` claim. Empty or absent → `AccessSet(false, emptyList())`. **Stop.** No group, no
   grants, no `everyGroup` categories, nothing. This is the "user logs in without being a member of
   any group" case and it is answered here, once.
2. `MERGE (g:__Group {key: …}) ON CREATE SET …` for each claimed group, `SET g.lastSeenAt`.
3. If any of those groups has `seesAll = true` → `AccessSet(true, emptyList())` and skip the rest.
4. Otherwise the category set is the union of
   - every category reachable by `__mayRead` from those groups, and
   - every category with `everyGroup = true`.

### Caching, because this runs on every request

- Key: the **sorted group-key set** — not the user. Two hundred users in the same four groups share
  one entry.
- Invalidation: a single `AtomicLong` version, bumped by **every** write in `AccessAdminService`.
  A cache entry carries the version it was computed at and is discarded when it does not match.
  This is exact, and it is thirty lines. Do not add a cache library for it (§4: prefer fewer
  libraries) — and do not add a TTL as well, because two invalidation mechanisms means the one that
  is wrong is invisible.
- The `MERGE` in step 2 must not run on a cache hit. Move `lastSeenAt` to a throttled write (once per
  group per hour) or drop it; a write on every request to a hot node is a lock convoy waiting to
  happen.
- Measured cost of a cold resolve is recorded in the ADR at the end of phase 2. It is one query.

**Until phase 6, `invalidate()` has no caller, and that has an operational consequence.**
`AccessAdminService` is what bumps the version, and it does not exist yet — so a grant edited by hand
in Cypher against a running backend behaves differently depending on *what* was edited:

| Hand edit | Effect |
|---|---|
| The user joins a group whose key this process has not resolved before | Different cache key → cache miss → takes effect immediately |
| `seesAll` flipped, or a `__mayRead` added, on a group already resolved once | Same cache key, no version bump → **stale until the backend restarts** |

**Signing out and back in does not clear it**, which is the counter-intuitive half: the cache is keyed
on the group set, not on the session or the user. Restart the backend, or grant through a group name
this process has not seen. There is no TTL to wait out, by design (see above). This disappears the
moment phase 6 lands, because every write there calls `invalidate()`.

---

## 6. The filter — one predicate, one function, one guard test

### 6.1 The predicate

`graph/cypher/AccessCypher.kt`:

```kotlin
/** The visibility predicate for a bound node alias. The ONLY way authorization
 *  reaches a query. The marker comment is what AccessGuardTest looks for. */
public fun visible(alias: String): String =
    "/*ACL*/ ($alias)-[:${Rel.IN_ACCESS_CATEGORY}]->(:${NodeLabel.ACCESS_CATEGORY} " +
    "WHERE ${'$'}seesAll OR …)"
```

**Its subquery variable is `aclCat`, and the name is load-bearing.** A Cypher `EXISTS { }` imports
every variable bound outside it, so declaring one that shadows an outer binding is an error rather
than a shadowing warning — and `c` is exactly what several statements already bind for the
system-level `:__Classification` (`RequirementCardCypher`, `ModuleCypher`, `StatisticsCypher`). A
predicate that cannot be dropped into an arbitrary statement is a predicate that will be reproduced
by hand at the one call site it does not fit, which is the thing this function exists to prevent.

The exact Cypher was settled by measurement in phase 2, not by preference — **ADR 0016 §8 has the
db-hit numbers and form A won**, against the a-priori guess below. Two candidate forms were
compared, both correct:

```cypher
-- A: property comparison per candidate node (ships, every filtered statement)
WHERE $seesAll OR EXISTS {
  (n)-[:__inAccessCategory]->(c:__AccessCategory) WHERE c.__metaId IN $acl
}

-- B: categories pinned once, then a pure degree check (measured, not taken)
MATCH (c:__AccessCategory) WHERE c.__metaId IN $acl
WITH collect(c) AS acl
MATCH (n:DOORSObject { __moduleUrl: $u })
WHERE $seesAll OR any(c IN acl WHERE EXISTS { (n)-[:__inAccessCategory]->(c) })
```

B was expected to win — it reads no property in the inner loop — but its `any(...)` re-probes once
per entry in `$acl`, so its cost scales with how many categories a caller's groups collectively
grant; A's does not, since it expands each node's own category edges once regardless of `$acl`'s
size. B only wins at a single granted category. Use form A everywhere; mixed forms are how a filter
gets forgotten.

Parameters are always both supplied, always by the same helper: `$seesAll: Boolean`, `$acl: List<String>`.

### 6.2 The guard test — `AccessGuardTest`, modelled on `GraphNamesTest`

Reads every statement in `graph/cypher/` and every projection under `source/`. For each statement
that matches `:SEItem` or any type label, assert it contains the `/*ACL*/` marker. A statement that
legitimately must not filter — the importers' own writes, `MetaSchema`, health checks — is named in a
declared exemption list **with a reason string**, in one file.

This test is the reason the feature stays correct in six months. `GraphNamesTest` was verified by
breaking it deliberately; do the same here and record it.

### 6.3 Where the parameters come from

Never from a route handler by hand. `graph/Read.kt` — already the only place a session is opened —
takes the `AccessSet` from the call's principal and binds both parameters. A read path that wants to
run unfiltered has to say so in the exemption list, in code review.

---

## 7. Leaks — the section to re-read before every pull request

A filter that returns the right rows can still tell a user about rows they cannot see. Every item
below is a real, existing behaviour of this application that becomes a leak the day filtering is on.

| Behaviour today | Rule |
|---|---|
| `GET /items/{ref}` for an object the user may not see | **`404`, never `403`.** `403` confirms it exists. Unauthorized *object* is always `404`; unauthorized *capability* is always `403` |
| `refersTo` in either direction (`/items/{ref}/traces`) | **An edge is visible only if both endpoints are.** Filter both ends, in the same query. Never render a hidden endpoint as "unresolved", "external", or a struck-through id |
| The dependency graph's **`+n` badge** ("links outside this graph") | Count **after** filtering. A `+3` that includes two invisible neighbours is a disclosure with a number attached |
| The Breakdown tree hitting an invisible child | The branch simply ends. No "loops back to ‹id›", no ellipsis, no count |
| The **unresolved-modules banner** | Names only modules the user can see. If all of them are invisible, no banner |
| `:__UNDEFINED` placeholders | Invisible to everyone unless categorised (§16.1 is the open question). A placeholder is evidence that an object exists elsewhere |
| `deletedInSource` targets, struck through in the References column | Same rule as any other endpoint: hidden if the target is not visible |
| **Statistics** — every count, every bar, TBD/TBC totals, cycle detection | Computed over the visible subgraph. These are computed on read already (R2), so this is a parameter change, not a redesign — but every one of them must be checked |
| The Issues search, which scans every property | Filter first, scan second. Never the other way round |
| Windchill's 20 000-row server cap | Applied **after** filtering, or the cap message leaks a total |
| `LIMIT` and page totals anywhere | Same: after filtering |
| Error and problem-detail messages | Never name an object, module, project or attribute the caller cannot see. `instance` carries the `CallId`; that is enough to find it in the log |
| The autocomplete / picker in any dialog | Same filter as the view it feeds |
| DOORS tables and cells | Inherit the module's categories through §8, so they follow automatically — assert it in a test rather than assuming it |

**The general rule, and the one to quote in review:** *anything computed from the graph and shown to
a user — a row, an edge, a count, a badge, a message, an absence — is computed from the graph the
user can see.* There is no second graph.

---

## 8. Tagging and inheritance

### 8.1 Humans tag containers; machines tag everything else

| | Who writes it | `origin` | Removed by reconcile |
|---|---|---|---|
| A category on a **container** (module, project) | an access manager, in the UI | `direct` | no |
| A category on a **contained item** | the reconciler | `inherited` | yes, when the container loses it |
| A category on a **single item**, as an exception | an access manager, in the UI | `direct` | **no** — the escape hatch survives reconcile |

This is what makes "set it on the module, all 984 objects follow" true, and it is also what makes it
*reversible*: removing the category from the module removes it from every object that inherited it
and leaves every deliberate exception standing.

### 8.2 Containment is declared per source, in one place

`security/AccessContainment.kt`, source-agnostic, an entry per source contributed by that source's
package:

```kotlin
public data class Containment(
    val sourceId: String,
    val containerLabel: String,        // NodeLabel / DoorsLabel constant, never a literal
    val memberMatch: String,           // Cypher fragment binding `o` given a bound `c`
    val containerless: Boolean = false // sources with no container — see 8.3
)
```

| Source | Container | Members |
|---|---|---|
| DOORS | `:DOORSModule` | `(o:DOORSObject { __moduleUrl: c.__moduleUrl })` — the indexed path; note §7 of the root file about per-label indexes and add one for any type label used here |
| JIRA | `:JiraProject` | the project→issue relationship the importer already writes; read `JiraNames.kt`, do not invent one |
| Windchill | none yet — `containerless = true` | every `:WindchillDocument` gets the source default (§8.3). When folder nodes arrive (`__child`, per R3), the folder becomes the container and this entry changes; nothing else does |
| Cameo | not yet imported — add the entry with the source | |

**Nothing in the reconciler names a source.** Same seam as `importer/`.

### 8.3 The reconciler

`security/AccessReconciler.kt`. Idempotent, restartable, batched, and it does exactly three things.

```cypher
-- 1. propagate: members missing a category their container has
MATCH (c:DOORSModule)-[:__inAccessCategory {origin:'direct'}]->(cat:__AccessCategory)
CALL (c, cat) {
  MATCH (o:DOORSObject { __moduleUrl: c.__moduleUrl })
  WHERE NOT EXISTS { (o)-[:__inAccessCategory]->(cat) }
  CREATE (o)-[:__inAccessCategory {
      origin:'inherited', via: c.__id, __createdBy:'system', __createdAt: $now }]->(cat)
} IN TRANSACTIONS OF 10000 ROWS

-- 2. retract: inherited tags whose container no longer carries them
MATCH (o:DOORSObject)-[r:__inAccessCategory {origin:'inherited'}]->(cat)
WHERE NOT EXISTS {
  MATCH (c:DOORSModule { __id: r.via })-[:__inAccessCategory {origin:'direct'}]->(cat) }
CALL (r) { DELETE r } IN TRANSACTIONS OF 10000 ROWS

-- 3. seed: containers that have never been categorised get the source default
MATCH (c:DOORSModule) WHERE NOT EXISTS { (c)-[:__inAccessCategory {origin:'direct'}]->() }
  AND NOT EXISTS { (c)-[:__accessSeeded]->() }          // seeded once, never re-seeded
MATCH (:__AccessDefault { sourceId:'doors', containerLabel:'DOORSModule' })-[:__assigns]->(cat)
…
```

Rules that are not obvious:

- **Seeding happens once per container, ever.** A container an access manager deliberately emptied
  must not be re-filled on the next import. Mark it (`__accessSeeded`) rather than inferring.
- **With no default configured, a new container gets nothing and its contents are invisible.** That
  is the specified behaviour for "objects created in the source and imported for the first time".
- **Scope it.** After an import, reconcile only the containers that run touched — the pipeline
  already knows them. The unscoped full pass is the manual button and the startup pass.
- **It is a phase of the import pipeline**, source-agnostic, running last, reporting counts to the
  existing SSE console. For DOORS and Cameo, which run out-of-process on a box that may not have the
  backend running, `POST /api/v1/access/reconcile` is called by `sec-import-doors.ps1` after a
  successful run, and a failure there is a **warning, not an error** — the objects stay invisible,
  which is safe, and the startup pass will catch them.
- Cypher's `CALL … IN TRANSACTIONS` cannot run inside an explicit transaction. Use an
  autocommit/implicit session, and note that `Read.kt`/`Write.kt` may need one narrow addition for
  it — one addition, in those files, not a new session-opening site.

### 8.4 "Visible to everyone" — a category, never a bypass

Windchill documents are readable by all groups. Model this as a category with `everyGroup = true`,
resolved in §5 step 4 **after** the empty-groups check in step 1. Consequences, both wanted:

- A user in any group sees them.
- A user in **no** group sees nothing at all, documents included.

There is no `if (isPublic) return true` anywhere in the codebase. One code path decides visibility.

---

## 9. API

Everything is `/api/v1`, `{ref}` is base64url of `__id` / `__metaId` (R5, R6), and every write is one
request and one transaction (R7).

```
GET    /auth/login?redirect=<app path>     302 → Keycloak. No session required
GET    /auth/callback                      302 → the app. Sets the session cookie
POST   /auth/logout                        clears the session, returns the end-session URL
GET    /auth/me                            { userId, displayName, email, roles[], groups[],
                                             seesAll, categoryCount, csrfToken }

GET    /access/categories                  sec-access-manager
POST   /access/categories
PATCH  /access/categories/{ref}
DELETE /access/categories/{ref}            409 if any object or grant still references it
GET    /access/groups                      every :__Group ever seen, with its grants and seesAll
PUT    /access/groups/{ref}/grants         the WHOLE grant set for one group, one txn (R7)
PATCH  /access/groups/{ref}                seesAll only. Audited loudly
GET    /access/containers?state=unassigned&source=&q=
                                           the queue of §10.2: containers with no direct category
PUT    /access/containers/{ref}/categories the WHOLE set for one container, one txn (R7)
PUT    /access/items/{ref}/categories      the single-item escape hatch (§8.1)
GET    /access/defaults
PUT    /access/defaults                    per (sourceId, containerLabel)
POST   /access/reconcile?scope=all|source  returns a run id; progress on the existing SSE stream
GET    /access/summary                     counts for the dashboard, computed on read (R2)
```

`/auth/me` is the frontend's only source of identity. It is **not cached** by the browser and it is
re-fetched on every full page load.

### Guarding, once

Authentication is installed on the route tree, not on individual routes, so a new route is guarded
by default. `/health`, `/ready` and `/auth/login|callback` are the declared exceptions and live in
`ApiPaths.kt` beside the paths themselves.

Role checks are one Ktor plugin — `requireRole(Role.ADMIN)` — applied to the `/settings`-shaped
routes and `/access/*`. There is a matching frontend guard, and **the frontend guard is a
convenience, never the enforcement**. A test asserts each administrative route rejects a `sec-user`
session; the absence of such a test is how this regresses.

---

## 10. Frontend

### 10.1 Shell

- `core/auth/` — an `AuthStore` holding the `/auth/me` result as a signal. `httpResource`, guarded
  with `hasValue()` (trap 12 in the handover).
- Every request goes out with credentials. One provider change in `app.config.ts`
  (`withFetch()` is already there); the CSRF token from `/auth/me` is attached to every non-`GET` by
  one interceptor.
- A `401` from anywhere → full navigation to `/api/v1/auth/login?redirect=<current route>`. Not a
  router navigation: the browser must follow a redirect to Keycloak.
- A `403` → an in-app refusal panel naming the capability required. Never a redirect (ADR 0017 §5).
- The toolbar user menu already exists and already shows display name, email and roles (§9). It now
  shows **real ones**, plus the user's groups, and a sign-out that calls `POST /auth/logout` and then
  navigates to the returned end-session URL.
- A `functionalGuard` per route family: `canRead` (any session), `canAdminister`, `canManageAccess`.
  Sidenav items the user cannot reach are **hidden, not disabled** — a disabled item is an
  advertisement.

### 10.2 The Access views — `/access`, a new sidenav group visible only to `sec-access-manager`

Four screens. All Material, ag-grid for the tables (§6, ADR 0006), Signal Forms for the inputs.

1. **Categories** — a table of categories: name, key, `everyGroup`, object count, group count.
   Create, rename, delete. Deleting a category in use is a `409` with the counts in the message.
2. **Grants** — the matrix. Groups down the side, categories across the top, checkboxes in the cells.
   `seesAll` is a separate, visually distinct column with a confirmation dialog, because it is the
   one control that turns the whole feature off for a group. Saving is per row: one group, one
   request, one transaction (R7) — not a global save button.
3. **Unassigned** — the queue. Every container with no direct category, newest first, with source,
   name, and how many items are invisible because of it. Multi-select → assign categories to all
   selected. This screen is the one an access manager lives in after an import, so it opens with the
   count in the sidenav badge.
4. **Import defaults** — per source and container type: "new DOORS modules are visible to …".
   Empty is a legitimate, and the default, answer; the screen says out loud what empty means.

Copy rules: R5 holds. **No `__`-prefixed name appears in any of these templates** — the eslint rule
`sec/no-internal-namespace` will fail the build, which is the point. The user-facing words are
**Access category**, **Group**, **May read**, **Sees everything**, **Not yet assigned**,
**Assigned by**, and they are declared in `Aliases.kt` like every other displayed name.

---

## 11. The session (ADR 0017)

- `security/Oidc.kt` — discovery document at startup, JWKS cached with the library's own refresh, the
  Authorization Code flow **with PKCE**, `state` and `nonce` validated. Verify what Ktor 3.5.x's
  OAuth provider exposes before writing it; if PKCE is not first-class, `extraAuthParameters` carries
  `code_challenge` and the callback sends the verifier. Do not skip PKCE because the client is
  confidential.
- ID token validated against JWKS: signature, `iss`, `aud`, `exp`, `nbf`, `azp`. Never trust a claim
  from an unvalidated token, and never decode a token in the frontend — it does not have one.
- Session: `ktor-server-sessions`, an opaque id in the cookie, an in-memory server-side store holding
  the claims and the refresh token. Cookie: `HttpOnly`, `Secure`, `SameSite=Lax`, `path=/`,
  no `Max-Age` (a browser-session cookie). **`Secure` is not conditional on the environment** —
  develop over `https` or over `localhost`, which browsers exempt.
- **Restarting the backend signs everyone out.** Stated here so it is not diagnosed twice. One
  instance is the deployment (§7: single database, single service); a second instance needs this
  decision reopened first.
- CSRF: `SameSite=Lax` **and** a double-submit token — issued in `/auth/me`, sent as `X-SEC-CSRF`,
  required on every `POST`/`PUT`/`PATCH`/`DELETE`. Missing or mismatched is a `403` problem detail.
- Token refresh happens server-side, on use, with a small skew. A refresh failure invalidates the
  session and the next request is a `401`.
- **Group membership is re-read on every token refresh, not only at login.** A user removed from a
  group keeps their access until the access token expires; keep the access-token lifetime short
  (5 minutes is the Keycloak default and is right) and say so in `docs/KEYCLOAK_SETUP.md`.
- Config: `sec.auth.*` in `application.yaml`, secrets as `$SEC_OIDC_CLIENT_SECRET`-style environment
  lookups that fail to load when unset — the same deliberate behaviour the Neo4j credentials have.

---

## 12. Failure modes — all of them close

| Situation | Behaviour |
|---|---|
| Keycloak unreachable at login | `503` problem detail with a plain sentence. `/health` still passes; `/ready` **does not** consider Keycloak, because a restart loop must not follow an IdP outage (§5's reasoning for the database applies identically) |
| A session exists but Keycloak is down at refresh | The session serves until the access token expires, then `401` |
| `groups` claim missing entirely | Treated as empty. Empty application, no error. Logged once per user per session at `WARN` with the claim names actually present — this is the single most likely misconfiguration |
| The resolver's query fails | The request fails. **It does not fall back to unfiltered.** There is no code path in which an exception widens visibility |
| `$acl` is empty and `seesAll` false | The predicate matches nothing. Views render their existing empty states |
| Reconcile interrupted halfway | Objects carry a subset of their tags — under-visible, never over-visible. Re-running completes it |
| An access manager deletes a category still in use | `409`, with the object and grant counts |
| Enforcement misconfigured | There is no `permissive` mode and no bypass flag. Adding one is a decision that needs an ADR — a switch that turns authorization off is a switch that will be found on in production |

---

## 13. Performance

Budget: **the authorization predicate adds under 10 ms to the p95 of every existing endpoint** at
current data volumes, and the resolver adds under 1 ms on a cache hit.

How it is proved, in phase 2 and again at the end:

- `PROFILE` the three hot queries — `/modules/{ref}/objects` (984 rows), `/jira/issues` (784),
  `/windchill/documents` (~1 500 unpaged) — before and after, and record **db hits**, not wall clock.
  Wall clock on a laptop measures the page cache.
- The numbers go in ADR 0016. A budget with no recorded measurement is a hope.
- If form B (§6.1) is not clearly better, use form A and say so — the simpler query that is
  fast enough beats the faster query that is threaded through forty call sites.
- **The thing to watch is not the predicate; it is a category node's degree.** A category covering
  200 000 objects is a dense node. That is fine when it is traversed *from the item* (degree 1–3) and
  bad when traversed *from the category*. No read path may start from a category node. The Access
  view's object counts are the exception: compute them in the background or accept them as slow, and
  never on a page a normal user loads.

---

## 14. Tests

Non-negotiable, and each one exists because it catches a specific way this feature fails silently.

1. **The visibility matrix** — a Testcontainers test against Neo4j **Community** with a fixture of two
   modules, two categories, three groups (one granted A, one granted A+B, one with `seesAll`) and a
   user with no group. A table-driven assertion over every read endpoint: rows, counts, edges, and
   the `404`-vs-`403` rule. This is the test that would fail if any of §7 regressed.
2. **`AccessGuardTest`** (§6.2) — verified by deliberately breaking it, per the `GraphNamesTest`
   precedent.
3. **Both-endpoints** — a link from a visible object to an invisible one appears in **no** response,
   in either direction, and produces no placeholder, no count and no badge.
4. **Reconcile is idempotent** — run twice, assert the relationship count is identical; run after
   removing a container's category, assert inherited tags are gone and direct exceptions remain.
5. **R2's byte-identical anchor test, extended** — tagging and untagging leave every property map on
   every imported node unchanged. This is the R1 guarantee for the new write path.
6. **Re-import survives tagging** — the existing "meta survives a second import" test extended to
   `__inAccessCategory`. `MERGE … SET n += props` leaves relationships alone; pin it.
7. **Role enforcement per route** — a `sec-user` session gets `403` from every administrative route.
   Parameterised over the route table so a new route is covered when it is added, not when someone
   remembers.
8. **No session gets `401` from every route except the declared exceptions** — same shape.
9. **Frontend**: the guards, the `401`-navigates / `403`-renders split, and the CSRF interceptor.
   `whenStable()` does not resolve with an `httpResource` in flight (handover trap 15) — use the
   established pattern in the existing specs.

Container tests stay tagged `docker` and out of `mvn verify`, per §11.

---

## 15. Build order

Each phase ends green — `mvn verify`, `mvn -Pdocker test`, and from `frontend/`:
`npm run lint && npm test && npm run build` — and each has one acceptance question that must be
answered by running the application, not by reading the tests.

| # | Phase | Done when |
|---|---|---|
| 1 | **Keycloak realm + the session.** `docs/KEYCLOAK_SETUP.md` applied, `/auth/*`, the route-tree guard, `/auth/me`, the frontend shell, the user menu, the `401`/`403` split. **No data filtering yet** | *Signing out and back in as two different users shows two different names and role sets, and every `/api/v1` call without a session is a `401`* |
| 2 | **Model, schema, resolver, predicate.** `GraphNames` additions, `MetaSchema`, `AccessResolver`, `AccessCypher.visible()`, `AccessGuardTest`, the `PROFILE` measurement and the ADR update. **Applied to exactly one endpoint** — `/modules/{ref}/objects` | *One module tagged by hand in Cypher is visible to one group and invisible to another, and the db-hit numbers are in ADR 0016* |
| 3 | **Containment and the reconciler.** `AccessContainment`, `AccessReconciler`, `POST /access/reconcile`, the startup pass, the import-pipeline phase, `sec-import-doors.ps1` calling it | *Tagging a module propagates to 984 objects and untagging retracts them, twice, with the same counts* |
| 4 | **Every read path.** Item, tree, children, traces, breakdown, dependency graph, modules, objects, tables, JIRA issues and link graph, Windchill documents, statistics, search. **And every item in §7** — **done, except the on-screen half of its acceptance question, see §15.1** | *The visibility matrix test is green and the `+n` badge, the banner and every statistic have been read on screen as two different users* |
| 5 | **Write guards.** `requireRole` on `/settings/*`, imports, meta writes; anchor visibility on Tier-2 writes — **done**, see §15.3 | *A `sec-user` cannot reach the settings gear, and cannot comment on an object they cannot see even with a hand-made request* |
| 6 | **The Access views.** Categories, grants matrix, unassigned queue, import defaults, the sidenav badge | *An access manager can take a freshly imported module from invisible to visible without touching Cypher* |
| 7 | **Hardening.** `sec-auditor`, the audit trail, IdP brokering rehearsal, the final `PROFILE`, `docs/RUNNING.md` and the handover updated | *A second identity provider is configured in Keycloak and a brokered login lands in the right groups* |

Phases 1–2 are safe to merge on their own. **Phase 4 is the one that must not be split across a
release**, because a half-filtered API is worse than an unfiltered one: it looks guarded.

### 15.1 What phase 4 settled, and the one thing it did not

Every statement that carried a `phase 4 read path` exemption is filtered; `AccessGuardTest` has none
left. Four things learned in the building that are not re-derivable from the code:

- **A statement's second predicate site is the one that gets missed.** `MODULE_OBJECTS` shipped in
  phase 2 filtering its row and *not* the two pattern comprehensions that build its References
  column, so the one endpoint called finished was disclosing hidden objects' DOORS ids. A `COUNT{}`
  subquery is the same trap: it counts neighbours, and an outer `WHERE` does not reach it
  (`StatisticsCypher.MODULE_OBJECTS` has three, `JiraCypher`'s `linkCount` one).
- **The lockstep rule earns its place, and the badge is where.** With the cards filtered and the two
  neighbour statements not, `truncatedNeighbours` counts every hidden object beyond the picture —
  confirmed by removing the predicate and watching a badge read `3` where it should read `2`. The
  test that catches it has to seed **one hop further out** than the obvious node: seeding on the
  object that owns the hidden link admits the hidden neighbour and then drops it for having no card,
  so it lands *inside* the set and is never counted. `VisibilityMatrixTest` carries both cases.
- **The Kotlin-derived values needed no arithmetic of their own** once their groups moved together —
  `truncated`, `cyclic`, `module.truncated`, `edgesExamined`, `modulesWithoutSystemLevel`. That is
  the payoff of the lockstep grouping rather than a happy accident: each of them compares two numbers
  that now carry the identical filter.
- **A `:ref` is not opaque enough to be a hiding place.** It is base64url of `__id`, reversible
  without server state, so a reference carrying its module's ref hands over that module's DOORS url
  to anyone reading the response — no view had to render it. R8 draws no line between what is shown
  and what is sent, so both reference paths now drop the handle wherever the module's name did not
  resolve, and they do not distinguish "invisible" from "never imported", because distinguishing
  them is the disclosure. Reachable only through §8.1's escape hatch, which is why it survived the
  §7 list.
- **`seesAll` sees uncategorised objects**, and that is what the words *Sees everything* mean. R8's
  "invisible to everyone, administrators included" is about the *other* axis: `sec-admin` and
  `sec-access-manager` are capabilities and grant no visibility. A group is how visibility is
  granted, and `seesAll` is a group granted all of it — which is why §10.2 puts it behind a
  confirmation and calls it the one control that turns the feature off for a group.

### 15.2 Answering phase 4's acceptance question on screen

Nothing in the product can seed access yet — `AccessAdminService` is phase 6 — so the graph side is
`deploy/dev-access-seed.cypher`, committed for this. It pairs with the dev realm's three users, which
are already exactly the three callers `VisibilityMatrixTest` asserts over:

| User | Group | Sees |
|---|---|---|
| `sec-dev-user` | `/SEC/Thermal` | one category — about half of everything |
| `sec-dev-admin` | `/SEC/All-Read` | `seesAll` — all of it, uncategorised objects included |
| `sec-dev-nogroup` | none | nothing, and the empty states have to say so in words |

```
docker compose -f deploy/docker-compose.dev.yml up
# import something first — the seed tags what is there, and tags nothing if nothing is
docker compose -f deploy/docker-compose.dev.yml exec -T neo4j \
  cypher-shell -u neo4j -p "$SEC_NEO4J_PASSWORD" < deploy/dev-access-seed.cypher
# then restart the backend — see below, it is not optional
```

**The restart is load-bearing, for two independent reasons**, and skipping it looks exactly like the
seed having failed:

- The seed tags **containers only**, which is all a human is ever meant to tag (§8.1). Propagation to
  the objects inside is the reconciler's, and its startup pass is the only trigger reachable without
  a session — `POST /access/reconcile` sits behind the session guard and a script has no cookie.
- The resolver caches on the sorted group-key set and nothing invalidates it until phase 6 (§5), so
  a grant on an already-resolved group does not take effect. Signing out and back in does not help.

What to look at, hardest first: the **`+n` badge** on the dependency graph, the
**unresolved-modules banner**, and the **statistics** page — read each as `sec-dev-user` and then as
`sec-dev-admin`. The badge is the value most likely to be quietly wrong, because it is the one
computed from what was left *out*.

**Not done: the on-screen half of the acceptance question.** Nobody has read the `+n` badge, the
unresolved-modules banner and the statistics in a browser as two different users. The matrix test
asserts all three at the projection layer, against a real Neo4j; what it cannot prove is that the
frontend renders the filtered answer without adding a claim of its own. Do that before merging.

---

### 15.3 What phase 5 settled

`requireRole(Role.X) { }` guards route groups; `AccessGuardTest` has no deferred entries left at all,
because every Tier-2 write is now filtered on its anchor as well.

Four things worth not rediscovering:

- **`createChild` reuses a child whose selector compares equal.** A shared `object` selector made
  every `requireRole` in the application mount onto one node, so two guards meant one node carrying
  both — `sec-admin` demanded of `/access/reconcile`, and a holder of the right role refused its own
  route. A fresh selector instance per call is what makes them separate subtrees.
- **A raw `intercept` on a route runs for everything resolved through it**, which is why this is a
  route-scoped plugin. `authenticate` is not built on a bare interceptor either.
- **`AuthenticationChecked` fires whether or not authentication succeeded.** A request with no
  session reaches it with a null principal and its own `401` already challenged, so the guard must
  return rather than throw or respond — the same trap the CSRF check documents, from the other side.
- **The write statements are filtered too, not only their callers.** `MetaWriter` already checked the
  module and the item ids through filtered reads, so the Cypher predicate is redundant *today*; it is
  there because a filter that survives its caller being reordered is worth more than one that does
  not. That made `Write.kt` need the access-binding overload `Read.kt` already had — otherwise every
  Tier-2 write fails on a missing parameter, which is the safe direction but not a shippable one.

**`sec-user` keeps `POST /modules/{ref}/comments`.** Both it and module settings are `:__Meta`, and
the split is deliberate: a comment is one reviewer's note on one object, while a mandatory-attribute
policy governs every object in a module and changes what the Issues column says about all of them.
Only one of the two is an opinion.

**What phase 5 does not do:** there is still no audit trail, and `sec-auditor` remains unbuilt —
both are phase 7. `POST /access/reconcile` is now `sec-access-manager`-gated, which sharpens rather
than solves the standing machine-auth gap: `sec-import-doors.ps1` has neither a session cookie nor a
role, so it still degrades to the warning §8.3 specifies and the startup pass is what closes it.

---

## 16. Open questions — answer before phase 3, they change the model

1. ~~**`:__UNDEFINED` placeholders.**~~ **ANSWERED 2026-08-16 — see §16.1a below.** The original
   question read: a link target outside the import has no container and can never be tagged, so
   under this spec it is invisible to everyone and every link to it disappears — including the
   incoming-arrow behaviour R5 deliberately preserved. The alternative is to let a placeholder
   inherit the categories of whatever referenced it, which makes it visible to anyone who can see any
   referrer. **Recommended: invisible** (fail-closed), with the dependency-graph copy adjusted.
   This one is a real product decision, not a technical one.
2. **Do access managers need to see the objects they are assigning?** The unassigned queue lists
   containers by name. A module named `SRD_ProjectX_Confidential` is itself information.
   **Recommended: yes, the queue is exempt** — someone has to be able to do the job — and the
   exemption is declared in the §6.2 list where it can be seen.
3. **One `seesAll` group, or a per-source "everything in this source"?** Start with `seesAll`.
   A per-source variant is a category with a default that catches every container of that source, so
   it needs no new mechanism if it turns out to be wanted.
4. **Does `sec-admin` need `seesAll` in practice?** Keep them separate (§3), give the administrators
   a group, and see whether the separation survives a month of use before weakening it.
5. **Group keys — mostly settled, one thing to confirm.** The claim carries full paths
   (`/SEC/Thermal`), and groups stay in our own realm permanently (ADR 0016 §3.1), so the key is
   under our control and the path is a fine identifier. A rename still creates a new `:__Group` with
   no grants and silently drops that group's access — the rule is therefore *name a group once*.
   **Confirm before phase 3**: is a rename likely enough to be worth keying on the Keycloak group
   UUID instead? Doing so costs the one thing this design otherwise avoids — an Admin API call, since
   the Group Membership mapper emits paths and not ids. Recommended: keep paths, and write the
   naming rule into `docs/KEYCLOAK_SETUP.md` §4 where it already is.

### 16.1a `:__UNDEFINED` and `:__DELETED` visibility — answered, 2026-08-16

**A node is visible only through a container that actually resolves. Where none does, it is invisible
to everyone.** Fail-closed, per R8: *"the correct state after any failure. No code path may widen
visibility on error."* Three cases, in the order they should be implemented:

| Node | Container | Outcome |
|---|---|---|
| `:DOORSObject:__DELETED` — an object a later export no longer contains (ADR 0012) | Keeps `:DOORSObject` **and** `__moduleUrl`, so `AccessContainment.doors`'s existing `(o:DOORSObject { __moduleUrl: c.__id })` already matches it | **Already correct today.** No code change; add a test asserting it, because nothing currently proves it |
| `:SEItem:__UNDEFINED` whose `__moduleUrl` names an **imported** `:DOORSModule` | That module | Inherits its categories. **Needs a containment change**: a placeholder carries `:SEItem:__UNDEFINED` and *not* `:DOORSObject`, so today's pattern misses it |
| Anything else — a placeholder whose module is not imported, a JIRA placeholder, any node with no resolvable container | none | **Invisible to everyone**, administrators included |

**The third row is the common case, and that is the cost being accepted.** A DOORS placeholder exists
*because* its module was not imported — once it is, `importer.py`'s `REMOVE n:__UNDEFINED` resolves
the placeholder into a real object — so a *standing* placeholder almost always names a module with no
`:DOORSModule` node to inherit from. The consequence, stated plainly so nobody reads it as a bug:

> The incoming-arrow evidence R5 preserved — *something refines this requirement, from a module this
> cockpit has not imported yet* — **is not shown to anyone** until that module is imported and
> categorised. An unimported module's object names and ids are that module's content, and a
> placeholder carries both.

The `__moduleUrl` on a placeholder is a **stored property, not a derivation**: `importer.py` sets
`t.__moduleUrl = row.target_module_url` when it creates one (and the mirror for `__inputLinks`). So
"compute the container from the URL" is a lookup, not URL parsing — do not write a parser for it.
