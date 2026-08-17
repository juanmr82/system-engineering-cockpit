# JIRA → System Engineering Cockpit — Importer & Issues Dynamic View

**Status:** implementation specification, ready to build.
**Audience:** Claude Code working in the System Engineering Cockpit IntelliJ project.
**Companion documents (read them first, they are normative):**

- `DOORS Importer.md` — establishes the importer conventions this spec follows.
- `System Engineering DOORS Schema.md` — establishes the `SEItem` base contract.
- `User-Facing Cypher Access — API Layer Design.md` — establishes that the frontend
  never talks to Neo4j directly, and why Community Edition forces all authorization
  into the API layer.

If this document and the DOORS importer spec ever disagree on a *convention*
(labels, `__` prefixes, batching, validation reporting), the DOORS spec wins and this
file is stale. Where this document disagrees on *JIRA-specific behaviour*, this file
wins.

---

## 0. Scope

Build, end to end:

1. A **JIRA importer** in the Ktor backend (Ktor HTTP client + OkHttp engine) that pulls
   issue types, field definitions and issues from a JIRA **Data Center** instance and
   upserts them into Neo4j, treating JIRA as the sole source of truth.
2. A **generic import-pipeline framework** (run lifecycle + live status feed) that the
   JIRA importer is the first consumer of, and that DOORS / Windchill / CAMEO importers
   will reuse.
3. A **Settings area** in the Angular 22 frontend holding the JIRA configuration
   (project selection, column selection) and the "Import JIRA Issues" trigger.
4. A **JIRA Issues dynamic view**: a table whose columns are chosen at runtime from the
   ~1 171 field definitions the JIRA instance exposes.

Out of scope (explicit seams left in the design, build nothing):

- RBAC. Every user is an ADMIN for now — see §14.1 for the single seam that makes this
  a one-file change later.
- Editing / writing back to JIRA. The integration is strictly read-only against JIRA.
- Linking JIRA issues to DOORS/CAMEO/Windchill items. The `SEItem` base contract makes
  this possible later; do not implement it now.

---

## 1. Ground rules

These are invariants. Violating one is a bug even if the feature appears to work.

| # | Rule |
|---|---|
| **R1** | **JIRA data is stored verbatim.** No renaming, no normalisation, no unit conversion, no re-typing of JIRA values. If JIRA sends `"Nein"`, the graph stores `"Nein"`. |
| **R2** | **Anything the application derives or adds is not a property of an imported node.** It lives on a separate node reached through a relationship whose type starts with `__`. The three `SEItem` identity properties (`__id`, `__name`, `__version`) are the *only* exception, and they are exempt because the base contract in `System Engineering DOORS Schema.md` §2 already requires them on every node. |
| **R3** | **The `__` prefix is a safe namespace separator.** JIRA field ids are `summary`, `duedate`, `customfield_23700`, … — JIRA never emits an identifier starting with `__`. Therefore `n.__id` can never collide with an imported field, and no escaping scheme is needed. Rely on this; do not invent a second prefix. |
| **R4** | **JIRA is the source of truth.** An issue present in Neo4j but absent from the current JQL result set is deleted, not archived. Removing a project from the JQL project list therefore deletes its issues on the next run (this is the documented, intended behaviour — the user re-adds the project key to get them back). |
| **R5** | **The frontend never sees the JIRA token and never calls JIRA directly.** All JIRA traffic — including issue-type icon images — is proxied by the backend. |
| **R6** | **The REST base path `/rest/api/2/` is a compile-time constant in the JIRA client**, never configuration, never part of the configured host. Config holds the host only. |
| **R7** | **No JIRA endpoint requiring global admin rights may be used.** See §3.1 for the permitted set and the permission each one actually needs. |
| **R8** | **Loose schema.** Never deserialize a JIRA issue into a typed Kotlin data class beyond the envelope (`expand`, `id`, `self`, `key`, `fields`, `renderedFields`). Field values are `JsonElement`. A new custom field in JIRA must never break the importer. |
| **R9** | **The importer is idempotent and restartable.** Running it twice back-to-back must produce zero net change on the second run (verified by the acceptance criteria in §16). |
| **R10** | **Every write to Neo4j is a batched `UNWIND $rows` statement with map parameters.** No dynamic label/property-name construction by string concatenation, no `LOAD CSV`. (`DOORS Importer.md` §7.4, §7.5.) |

---

## 2. Technology baseline

Pin these in `gradle/libs.versions.toml` and `package.json`; do not float versions.

| Component | Version | Notes |
|---|---|---|
| Neo4j | 2026.01.4 **Community** | No RBAC, no property-existence constraints, no query governor. See `User-Facing Cypher Access` §1. |
| Kotlin | 2.4.x | Ktor 3.5.1+ is compiler-compatible with 2.4. |
| Ktor (server + client) | **3.5.2** (2026-08-04) | Latest stable. Server: Netty engine, `ContentNegotiation`, **`SSE`** plugin, `CallLogging`, `StatusPages`, `Resources`. Client: **OkHttp** engine, `ContentNegotiation`, `HttpRequestRetry`, `HttpTimeout`, `Logging`. |
| Ktor config | `application.yaml` | Ktor ≥ 3.2 supports typed configuration deserialization — use it (§4) rather than hand-reading `ApplicationConfig` keys. |
| kotlinx.serialization | latest matching Kotlin 2.4 | `Json { ignoreUnknownKeys = true; explicitNulls = false }`. |
| Neo4j Java driver | latest 5.x/2026.x aligned with the server | Use `executeWrite` / `executeRead` with the driver's built-in retry. |
| JVM target | 21 LTS | 25 acceptable if the rest of the project is already on it. |
| Angular | **22** (2026-06-03) | Signal-first, **zoneless by default**, Signal Forms stable, `httpResource`/`rxResource` stable. Write new code signal-first: `signal`, `computed`, `resource`, `input()`, `output()`, `model()`. No `NgModule`s — standalone components only. |
| Angular Material | 22, matching Angular | `MatTable` + `MatSort` + `MatPaginator`, `MatDialog`, `MatMenu`, `MatToolbar`, `MatStepper` (import pipeline), `MatChipGrid` (project keys), CDK `ScrollingModule` for virtual scroll. |

> **Zoneless implication:** anything that mutates state from outside Angular's awareness
> (the SSE `EventSource` in §11.4) must write into a `signal`, not into a plain field.
> There is no Zone.js to trigger change detection for you.

---

## 3. The JIRA REST surface

Base URL construction, in exactly one place:

```kotlin
// jira/JiraApi.kt
internal const val JIRA_API_BASE = "/rest/api/2/"   // R6: constant, never configurable
```

Full URL = `config.jira.host` + `JIRA_API_BASE` + `<endpoint>`. Normalise the configured
host by stripping a trailing `/` at startup so `https://jira.company.com/jira` and
`https://jira.company.com/jira/` behave identically. Note from the sample data that the
instance is served under a **context path** (`https://jira.company.com/jira/rest/api/2/…`)
— the host value therefore legitimately contains a path segment. Never assume it is a
bare origin.

### 3.1 Endpoints used (and the permission each requires)

| Purpose | Call | Permission |
|---|---|---|
| List projects for the JQL picker | `GET /project` | *Browse Projects* on each project. Returns only projects the token's user can see. **Not** an admin endpoint. |
| Import issue types (§12 Phase 1) | `GET /issuetype` | Permission to access JIRA. Returns every issue type on the instance. **Not** an admin endpoint. |
| Import field definitions (§12 Phase 2) | `GET /field` | Permission to access JIRA. Returns all system + custom field definitions. **Not** an admin endpoint. |
| Import issues (§12 Phase 3) | `GET /search?jql=…&startAt=…&maxResults=…&fields=*all` | *Browse Projects*. Results are already filtered to what the token's user may see. |
| Connectivity / token check | `GET /myself` | Any authenticated user. Used by the "Test connection" button. |
| Issue-type icon proxy | `GET <issuetype.iconUrl>` (absolute URL, outside `/rest/api/2/`) | Session/PAT auth. Fetched server-side only (R5). |

Deliberately **not** used: `/rest/api/2/field/search`, `/issuetype/{id}/alternatives`,
anything under `/rest/api/2/…/admin`, `/serverInfo` for licensing, and the whole
`/rest/auth/` family.

### 3.2 Authentication

Data Center Personal Access Token, sent as a bearer token:

```
Authorization: Bearer <token>
```

Do **not** use Basic auth, and do not fall back to it on 401. Configure it once as a
default request header on the `HttpClient`, and add the token to the redaction list of
the Ktor `Logging` plugin (`sanitizeHeader { it == HttpHeaders.Authorization }`).

### 3.3 Pagination — Data Center semantics

The Cloud platform has migrated to token-based pagination (`/rest/api/3/search/jql` with
`nextPageToken`, no `total`). **Data Center has not** — it still uses classic offset
pagination, which is what the sample response shows:

```json
{ "expand": "schema,names", "startAt": 0, "maxResults": 50, "total": 784,
  "issues": [ … ], "warningMessages": null, "names": null, "schema": null }
```

Rules:

- Loop `startAt += maxResults` until `startAt >= total` **or** an empty `issues` array is
  returned. Trust the empty array over `total`; `total` is an estimate under
  concurrent modification.
- **`maxResults` is advisory.** The server silently clamps it to
  `jira.search.views.default.max` (commonly 1 000, often lower). Always re-read the
  `maxResults` value **from the response** and use that as the stride — never assume the
  requested value was honoured. This is the single most common cause of skipped pages.
- Default request size: `100`. Configurable, hard-coded default in the backend (§4).
- **Offset pagination over a changing result set skips and duplicates rows.** Two
  mandatory mitigations, both applied by the JQL builder (§8):
  1. a deterministic total order — `ORDER BY key ASC`;
  2. a snapshot bound — `AND created <= "<import start time>"`, formatted
     `yyyy/MM/dd HH:mm`, so issues created during the run cannot shift page boundaries.
  Issues *updated* during the run may still be read in either state; that is acceptable
  and self-corrects on the next import.

### 3.4 `fields=*all`, `names` and `schema`

Request `fields=*all`. In the sample this returns **1 029–1 041 keys per issue**, with a
union of **1 047 distinct keys** across the 50 issues (the instance defines 1 171; the
difference is fields with no context in the queried projects, plus `issuekey`/`thumbnail`
which are navigable-only pseudo-fields).

**The key set is not constant between issues, not even within one project** — 47 issues
carried 1 041 keys, one carried 1 030, two carried 1 029, and `ProjectCRPT` produced both
1 041- and 1 030-key issues. Field contexts are per project *and* per issue type. Never
cache "the shape of an issue" from the first row of a page, and never treat an absent key
differently from a null one: both mean "no value" (§7.1).

The sample also confirms that even with `expand=schema,names`, this Data Center instance
returns `"names": null, "schema": null`. **Do not depend on `names`/`schema` in the search
response.** All field metadata comes from `GET /field` (§9.2, imported in §12 Phase 2). This is precisely why the
field-definition import runs *before* the issue import.

### 3.5 Failure handling

| Condition | Behaviour |
|---|---|
| `401` / `403` | Fail the whole run immediately with a clear message ("token rejected" vs "token lacks Browse Projects on <key>"). Never retry. |
| `429` | Respect `Retry-After` when present, otherwise exponential backoff. `HttpRequestRetry` with `retryOnServerErrors(maxRetries = 5)` + `exponentialDelay()`. |
| `5xx`, connection reset, timeout | Retry the **page**, not the run. Batches are page-scoped so a retry is cheap. |
| `400` with `errorMessages` | JQL is malformed (usually a project key that no longer exists). Surface `errorMessages[0]` verbatim to the UI — JIRA's message is better than anything we can synthesise. |
| `warningMessages` non-null in a successful response | Log at WARN, attach to the run report, do not fail. |

Timeouts: request 120 s, socket 60 s, connect 15 s. A `*all` page of 100 issues is
multi-megabyte (the 50-issue sample is 3.4 MB) and slow instances are normal.

---

## 4. Configuration — `application.yaml`

```yaml
ktor:
  application:
    modules:
      - com.sec.cockpit.ApplicationKt.module
  deployment:
    port: 8080

jira:
  host: "https://jira.company.com/jira"      # scheme + host + optional context path, no trailing slash
  token: "$JIRA_TOKEN:"                       # env var override; empty default = feature disabled
  # Everything below is a tuning knob with a sane default; not user-facing.
  pageSize: 100
  requestTimeoutMs: 120000
  maxRetries: 5

neo4j:
  uri: "bolt://localhost:7687"
  user: "neo4j"
  password: "$NEO4J_PASSWORD:"
  database: "neo4j"
  batchSize: 1000

importer:
  runHistoryLimit: 50        # how many finished runs to keep in the graph
```

Typed binding (Ktor ≥ 3.2 config deserialization):

```kotlin
@Serializable
data class JiraConfig(
    val host: String,
    val token: String,
    val pageSize: Int = 100,
    val requestTimeoutMs: Long = 120_000,
    val maxRetries: Int = 5,
)
val jiraConfig = environment.config.property("jira").getAs<JiraConfig>()
```

**Rules:**

- The token is read once at startup and never logged, never returned by any endpoint,
  never written to Neo4j.
- If `jira.host` or `jira.token` is blank, the backend starts normally but every
  `/api/jira/**` route returns `503` with `{"error":"JIRA integration not configured"}`,
  and the frontend disables the JIRA settings section with that reason shown. Do not
  crash at startup — the cockpit has non-JIRA features.
- A `GET /api/jira/health` endpoint calls `/myself` and reports
  `{configured, reachable, user, message}`. This backs the "Test connection" button.

---

## 5. What the sample data actually looks like

Verified against the three sample files. Build against these numbers.

**`JIRA.json`** — one page of `/search`:

- `total = 784`, `maxResults = 50`, 5 distinct projects in page 1
  (`ProjectITSEC`, `ProjectITIND`, `ProjectCRPT`, `ProjectSomethingSW`, `ProjectSomething`).
- Envelope per issue is `{expand, id, self, key, fields, renderedFields}`;
  `renderedFields` is `null` on all 50 (it only populates with `expand=renderedFields`)
  and is ignored by this design.
- **1 029–1 041 keys in `fields`** (1 047 distinct across the sample, §3.4), of which only
  **145 on average** (min 29, max 162) are non-null / non-empty. **Roughly 86 % of every
  payload is nulls** — dropping them is the single biggest storage and query win
  available.
- Longest string value observed: 2 981 chars (`customfield_10000`, a comment/description
  blob). Plan for tens of KB, not unbounded.
- `parent` and `subtasks` are absent from every issue in the sample — sub-task
  hierarchy exists in JIRA but is not represented in this data set. Handle it if present
  (§9.5), do not assume it.

**`JIRA_FIELDS.json`** — `/field`:

- **1 171** definitions: 42 system fields (`custom: false`), 1 129 custom.
- 1 169 carry a `schema` object; **2 do not** (`issuekey`, `thumbnail`) — these are
  navigable pseudo-fields and must be **excluded** from the column picker.
- **15 display names are duplicated across 33 different fields** (`Work Package` ×2,
  `DOORS-ID` ×2, `Classification` ×3, `Team`, `End Date`, `Department`, `Progress`,
  `Complexity`, …). **The `name` is not unique and must never be used as a key.** The
  column picker must show the field id alongside any ambiguous name (§13.3).

**`JIRA_ISSUE_TYPE_EXAMPLE_DTO.json`** — `/issuetype`:

- Flat array of `{self, id, description, iconUrl, name, subtask, avatarId?}`.
  `avatarId` is absent for some types (e.g. `Epic`, which uses a static SVG icon URL).
  `description` is frequently `""`.

### 5.1 Observed value shapes, by declared schema type

This table is the **normative input** to the storage rules in §7. Counts are occurrences
across the 50-issue sample; `schema.type` comes from `/field`.

| `schema.type` | `schema.items` | Observed JSON | Count |
|---|---|---|---|
| `option` | — | object `{self,value,id,disabled}` | 2 845 |
| `array` | `checklist-item` | array of object `{name,checked,mandatory,id,globalItemId,rank,assigneeIds,isHeader,statusId}` | 1 673 |
| `array` | `option` | array of option objects | 557 |
| `string` | — | string | 465 |
| `array` | `string` | array of string | 232 |
| `number` | — | number | 232 |
| `any` | — | string (sometimes empty array) | 216 |
| `date` | — | string `"2026-08-09"` | 176 |
| `user` | — | object `{self,name,key,emailAddress,avatarUrls,displayName,active,timeZone}` | 150 |
| `datetime` | — | string `"2026-08-09T11:38:00.697+0200"` | 137 |
| `option-with-child` | — | object `{self,value,id,child:{…}}` | 135 |
| `progress` | — | object `{progress,total}` | 100 |
| `issuetype` / `status` / `priority` / `project` / `votes` / `watches` | — | object | 50 each |
| `resolution` | — | object `{self,id,description,name}` | 26 |
| `array` | `issuelinks` | array of link objects | 23 |
| `array` | `component` | array of `{self,id,name,description}` | 4 |
| `array` | `version` / `user` / `group` / `sd-*` | arrays (all empty in this sample) | — |
| `timetracking`, `sd-servicelevelagreement`, `issuelinks`, `group` | — | object | — |

Note `any` and `sd-servicelevelagreement`: their shape is genuinely unconstrained.
They are the reason for R8.

---

## 6. Graph data model

### 6.1 Label map

```
SEItem                          ← base contract, every node
├── JiraIssue
│   └── __UNRESOLVED            ← additive: link target not yet imported
├── JiraProject
├── JiraIssueType
├── JiraField                   ← one per /field definition
├── JiraStatus
├── JiraPriority
├── JiraResolution
├── JiraUser
├── JiraComponent
└── JiraVersion

__JiraProjection                ← app-derived, one per JiraIssue (§7.4)
__JiraSettings                  ← app-derived, singleton (§10)
__JiraColumnConfig              ← app-derived, singleton (§10)
__ImportRun                     ← app-derived, one per importer execution (§11)
```

Nodes whose label starts with `__` are **application-owned**, never imported, and are
always reached from imported nodes through a `__`-prefixed relationship (R2).

### 6.2 `SEItem` compliance

Every imported node carries the three universal properties.

| Label | `__id` | `__name` | `__version` |
|---|---|---|---|
| `JiraIssue` | the issue's `self` URL, verbatim | `key` + `": "` + `fields.summary`, truncated to 200 chars; falls back to `key` if summary is missing | `fields.updated`, verbatim; `"unknown"` if absent |
| `JiraProject` | project `self` URL | project `name` | `"current"` |
| `JiraIssueType` | issue type `self` URL | issue type `name` | `"current"` |
| `JiraField` | `<host>/rest/api/2/field/<id>` (synthesised — `/field` returns no `self`) | field `name` (**not unique**, §5) | `"current"` |
| `JiraStatus` / `JiraPriority` / `JiraResolution` / `JiraComponent` / `JiraVersion` | that object's `self` URL | its `name` | `"current"` |
| `JiraUser` | user `self` URL | `displayName` | `"current"` |
| `JiraIssue:__UNRESOLVED` | the link target's `self` URL (identical to the value it will carry once really imported) | `"<unresolved " + key + ">"` | `"unresolved"` |

`__id` is the URL in every case, exactly as the DOORS importer uses the DOORS resource
URL. This is what makes cross-source joins possible later, and it is why the uniqueness
constraint lives on `SEItem.__id` and covers JIRA for free.

> **Why not the issue key as `__id`?** Keys change when an issue is moved between
> projects; the numeric id embedded in `self` never changes. `key` is stored as a
> property and indexed, but identity is the URL.

### 6.3 Constraints and indexes

Community Edition supports uniqueness constraints only (no existence/key/type
constraints — `DOORS Importer.md` §7.2). Run these every import, they are idempotent:

```cypher
CYPHER 25
CREATE CONSTRAINT se_item_id_unique IF NOT EXISTS
FOR (n:SEItem) REQUIRE n.__id IS UNIQUE;

CREATE CONSTRAINT jira_field_id_unique IF NOT EXISTS
FOR (n:JiraField) REQUIRE n.id IS UNIQUE;

CREATE INDEX jira_issue_key      IF NOT EXISTS FOR (n:JiraIssue)     ON (n.key);
CREATE INDEX jira_issue_project  IF NOT EXISTS FOR (n:JiraIssue)     ON (n.__projectKey);
CREATE INDEX jira_issue_updated  IF NOT EXISTS FOR (n:JiraIssue)     ON (n.updated);
CREATE INDEX jira_project_key    IF NOT EXISTS FOR (n:JiraProject)   ON (n.key);
CREATE INDEX jira_issuetype_name IF NOT EXISTS FOR (n:JiraIssueType) ON (n.name);
CREATE INDEX jira_field_name     IF NOT EXISTS FOR (n:JiraField)     ON (n.name);
```

Do **not** index `__id` — the uniqueness constraint already creates a backing range
index and a duplicate `CREATE INDEX` errors.

> `__projectKey` looks like it violates R2. It does not: it is a **denormalised copy of
> imported data** (`fields.project.key`), not derived information, and it exists because
> the delete-sweep in §12 Phase 5 must scope by project without a traversal on every issue.
> It is the one denormalisation this spec allows; do not add others.

### 6.4 Relationships

Imported relationships have plain names; app-derived relationships are `__`-prefixed.

| Type | From → To | Properties | Meaning |
|---|---|---|---|
| `inProject` | `JiraIssue` → `JiraProject` | — | `fields.project` |
| `hasIssueType` | `JiraIssue` → `JiraIssueType` | — | `fields.issuetype` |
| `hasStatus` | `JiraIssue` → `JiraStatus` | — | `fields.status` |
| `hasPriority` | `JiraIssue` → `JiraPriority` | — | `fields.priority` |
| `hasResolution` | `JiraIssue` → `JiraResolution` | — | `fields.resolution`, absent when unresolved |
| `assignedTo` / `reportedBy` / `createdBy` | `JiraIssue` → `JiraUser` | — | `assignee` / `reporter` / `creator` |
| `hasComponent` | `JiraIssue` → `JiraComponent` | — | `fields.components[]` |
| `affectsVersion` / `fixVersion` | `JiraIssue` → `JiraVersion` | — | `versions[]` / `fixVersions[]` |
| `linkedTo` | `JiraIssue` → `JiraIssue` | `linkId`, `typeId`, `typeName`, `inward`, `outward`, `direction` | one edge per JIRA issue link, §9.4 |
| `subTaskOf` | `JiraIssue` → `JiraIssue` | — | `fields.parent`, only if present |
| `__projection` | `JiraIssue` → `__JiraProjection` | — | app-derived display strings, §7.4 |
| `__importedIn` | `JiraIssue` → `__ImportRun` | `action` ∈ `created`/`updated` | provenance; only for the **latest** run (previous edge is replaced) |
| `__describedBy` | `JiraIssue` → `JiraField` | — | *not implemented* — see §7.5 for why the field↔issue relationship is deliberately absent |

`JiraProject`, `JiraIssueType`, `JiraUser`, `JiraStatus`, … are **shared nodes**: one
node per distinct entity, `MERGE`d on `__id`, referenced by many issues. This is where
the graph earns its keep — "all open issues assigned to X across projects" is one
traversal.

---

## 7. Field storage — the core design decision

1 171 field definitions, ~1 040 keys on every issue, values ranging from `null` to a
nine-property array of checklist objects. This section defines exactly where each one
lands. Implement it as a single pure function with a table-driven test per row of §5.1.

### 7.1 What is skipped

Skip — write nothing at all:

- `null` values (≈ 86 % of every payload),
- empty arrays `[]`,
- empty strings `""` — *except* when the property already exists on the node, in which
  case it must be **set to `""`**, not removed (an emptied field is information).

Rationale: a `JiraIssue` node ends up with ~145 properties instead of ~1 040. Absence of a
property means "null in JIRA"; the UI renders it as empty either way.

### 7.2 Verbatim values on the `JiraIssue` node (R1)

Every surviving field is written as **one property named exactly after its JIRA field
id** — `summary`, `duedate`, `workratio`, `customfield_23700`. These are already valid
Neo4j property keys (letters, digits, underscore); no backticks, no mangling, no
collision with `__`-prefixed metadata (R3).

| Value kind | Stored as |
|---|---|
| string, number, boolean | native Neo4j scalar, verbatim |
| `array` of `string` | Neo4j list of strings, order preserved |
| `array` of number | Neo4j list of numbers |
| **object** | **the raw JSON text of that object**, serialized from the received `JsonElement` |
| **array of objects** | **the raw JSON text of the array** |

Neo4j cannot store nested maps or heterogeneous lists as properties, so JSON text is the
only way to honour R1 for complex values. It round-trips losslessly: the API layer
parses it back to `JsonElement` when the frontend asks for a full issue.

`created`, `updated`, `duedate`, `resolutiondate` and every `date`/`datetime` custom
field are stored **as the strings JIRA sent them** (`"2026-08-09T11:38:00.697+0200"`).
Do not convert to Neo4j temporal types — that would violate R1 and lose the original
offset. Sorting still works: ISO-8601 with a fixed-width offset sorts correctly as text
within one timezone, and the display layer parses on demand.

### 7.3 Promoted fields — the ones that also become graph edges

These 13 fields are additionally decomposed into shared nodes + relationships (§6.4),
*in addition to* their verbatim JSON property. The duplication is intentional: R1 keeps
the raw copy, the graph gives traversal.

`project`, `issuetype`, `status`, `priority`, `resolution`, `assignee`, `reporter`,
`creator`, `components`, `versions`, `fixVersions`, `parent`, `issuelinks`.

`labels` is *not* promoted — it is already an `array<string>` and lives fine as a list
property; promoting it would add a node per label for no traversal benefit.

Custom fields are **never** promoted, even when their `schema.type` is `user` or
`option`. There are 1 129 of them and the set changes without notice; promoting them
would make the model unstable. If a specific custom field later needs traversal, add it
to an explicit allow-list in config — do not generalise by schema type.

### 7.4 The display projection — where derived data goes (R2)

Sorting and filtering a column whose value is `{"self":"…","value":"WSS","id":"38303"}`
requires a scalar. That scalar is **derived**, so by R2 it must not touch the imported
node. It goes on a companion node:

```
(:JiraIssue)-[:__projection]->(:__JiraProjection)
```

One projection node per issue, created/updated in the same batch as the issue. It holds
**only complex-valued fields** — scalars are already directly usable on the issue node.
Property keys match the field ids exactly, so the API layer resolves a column with
`coalesce(i[$fieldId], p[$fieldId])`.

Display-string derivation, by shape (first match wins):

| Shape | Projection value |
|---|---|
| `{value, …}` (option) | `value` |
| `{value, child:{value}}` (option-with-child) | `"<parent> - <child>"` |
| `{name, …}` (status, priority, issuetype, resolution, component, version, project) | `name` |
| `{displayName, …}` (user) | `displayName` |
| `{progress, total}` | `"<progress>/<total>"` |
| `{votes}` / `{watchCount}` | the number, as a string |
| array of the above | list of strings, order preserved |
| array of checklist items | `"<checked count>/<total count>"` — e.g. `"3/7"`; the item names stay in the raw JSON |
| anything unrecognised | `null` — do **not** guess, do **not** dump JSON into the table |

The projection node is disposable: it can be rebuilt from the issue nodes alone, and
`DETACH DELETE`ing all `__JiraProjection` nodes must never lose imported data. Add a
maintenance endpoint that does exactly that and rebuilds, so a change to the derivation
rules does not require a full re-import from JIRA.

### 7.5 Why there is no `(:JiraIssue)-[:hasField]->(:JiraField)` edge

Tempting, and wrong at this scale: 784 issues × 145 non-null fields = ~114 000 edges
that encode information already present in the issue's property keys, and that must be
diffed on every import. The `JiraField` nodes exist as a **catalogue** (to drive the
column picker and to give each field a display name and schema type); the link between
an issue and its fields is the property key itself. Query "which fields does this issue
populate?" with `keys(i)`, exactly as the DOORS schema does in its §5.1.

---

## 8. The JQL query

> **Superseded 2026-08-16 by ADR 0018.** There is no persisted project list any more — RBAC is the
> gate (R8): the importer brings in everything the configured token can see, and access categories
> decide who may read it. The query below is fixed, with no user-editable clause and therefore no
> injection boundary left to guard in this section. Kept here as the historical record of the
> pre-ADR-0018 design; read the ADR for the current shape.

Built server-side, from the persisted project list (§10.1) plus fixed parts. The frontend
never sends JQL.

```
project in ("KEY1","KEY2","KEY3") AND created <= "2026/08/11 14:32" ORDER BY key ASC
```

Rules:

- Project keys are **always quoted** — JIRA keys may collide with JQL reserved words, and
  an unquoted key containing a reserved word produces a cryptic 400.
- Validate every key against `^[A-Za-z][A-Za-z0-9_]*$` before interpolating. Reject
  anything else with a 400 from our own API. This is the injection boundary: the key list
  comes from a user-editable settings screen.
- Empty project list ⇒ the importer refuses to start (`409`, "no projects configured").
  Never fall back to an unbounded `ORDER BY key ASC` over the whole instance.
- `created <=` bound uses the run's start timestamp in the JIRA server's timezone,
  formatted `yyyy/MM/dd HH:mm`. Take the timezone from `/myself` (`timeZone`) at run
  start, not from the JVM default.
- `ORDER BY key ASC` is mandatory (§3.3). Do not make it configurable.
- The final JQL string is stored on the `__ImportRun` node and shown in the run detail
  panel — when an import returns unexpected results, the first question is always "what
  did we actually ask for?"

---

## 9. Mapping the three payloads

### 9.1 Issue types (`GET /issuetype`) → `:JiraIssueType`

```
__id = self, __name = name, __version = "current"
id, name, description, iconUrl, subtask, avatarId   ← verbatim, avatarId may be absent
```

`iconUrl` is stored verbatim but is **not** usable by the browser directly (R5: it needs
the token, and it is cross-origin). The frontend renders
`/api/jira/icon?issueTypeId=<id>` instead; the backend resolves the id to its stored
`iconUrl`, fetches with auth, and streams the bytes back with `Cache-Control:
public, max-age=86400` and a strong `ETag` derived from the URL. Never accept an
arbitrary URL as a proxy parameter — that is an SSRF hole; accept only the issue-type id
and look the URL up in the graph.

Issue types absent from the new response are deleted **only if unused**:
`MATCH (t:JiraIssueType) WHERE NOT (t)<-[:hasIssueType]-() AND NOT t.__id IN $seen DETACH DELETE t`.

### 9.2 Field definitions (`GET /field`) → `:JiraField`

```
__id = "<host>/rest/api/2/field/<id>"     ← synthesised, /field has no self
__name = name
__version = "current"
id, name, custom, orderable, navigable, searchable   ← verbatim
clauseNames                                          ← list of strings, verbatim
schemaType, schemaItems, schemaCustom, schemaCustomId ← flattened from schema{}, verbatim values
__displayable                                        ← see below
```

`schema` is flattened rather than JSON-stringified because the column picker filters and
sorts on `schemaType` constantly. This is a **structural** flattening with verbatim
values — it does not violate R1 (no value is altered), and the four keys are stable
across every JIRA version that speaks API v2.

`__displayable` is derived (a boolean the picker uses to grey out unusable fields):
`false` when `schema` is absent (`issuekey`, `thumbnail`) or `schemaType == "any"`,
`true` otherwise. Because it is derived, it must **not** be a property of the imported
node — store it on the `__JiraColumnConfig` singleton as a list of excluded ids, or
recompute it in the API layer. Recomputing is cheaper and always correct: prefer that,
and treat the row above as documentation of intent rather than a schema entry.

**Deletion:** fields removed from JIRA are deleted from the catalogue. If a deleted field
is referenced by the persisted column config, it stays in the config and is marked stale
(§13.4) — do not silently drop the user's column choice.

### 9.3 Issues (`GET /search`) → `:JiraIssue`

```
__id = self, __name = "<key>: <summary>", __version = updated
id, key, self          ← verbatim from the envelope
__projectKey           ← denormalised, §6.3
<fieldId> …            ← §7.2
```

`expand` and `renderedFields` from the envelope are discarded — the first describes what
*could* be expanded, the second is always `null` here (§5).

### 9.4 Issue links — yes, they are easy to link

Confirmed from the sample: each entry in `fields.issuelinks` carries the **full identity
of the other issue**, so no extra API call is ever needed:

```json
{ "id": "2484985",
  "self": "…/issueLink/2484985",
  "inwardIssue": { "id": "2613239", "key": "ProjectCRPT-186", "self": "…/issue/2613239",
                   "fields": { "summary": "…", "status": {…}, "priority": {…}, "issuetype": {…} } },
  "type": { "id": "11957", "name": "IsRelated",
            "inward": "is related to", "outward": "is related to", "self": "…/issueLinkType/11957" } }
```

Link types seen in the sample: `IsRelated`, `Related`, `Cloners`, `Issue split`,
`Affected`, `ASomethingILSDependency`. Note `IsRelated` has identical `inward` and
`outward` text — the UI must render the *phrase*, not infer direction from it.

**Modelling.** One `linkedTo` edge per JIRA link, always stored in JIRA's own outward
direction so the pair `(A outward→ B)` and `(B inward→ A)` collapses to a single edge:

- entry has `outwardIssue` ⇒ `(this)-[:linkedTo]->(other)`
- entry has `inwardIssue`  ⇒ `(other)-[:linkedTo]->(this)`

Edge properties: `linkId` (JIRA's link id — the dedup key), `typeId`, `typeName`,
`inward`, `outward`. `MERGE` on `linkId` so both sides of the same link produce one edge.

**Unresolved targets (point 15).** When the other end is not in the graph:

```cypher
MERGE (o:SEItem {__id: $targetSelf})
ON CREATE SET o:JiraIssue:__UNRESOLVED,
              o.__name = '<unresolved ' + $targetKey + '>',
              o.__version = 'unresolved',
              o.key = $targetKey,
              o.id  = $targetId,
              o.self = $targetSelf,
              o.summary = $targetSummary          // the link payload gives us this much
```

The `__UNRESOLVED` label sits **alongside** `JiraIssue` (never instead of it), so a
placeholder is still reachable by every JIRA query and is obviously a JIRA-sourced stub —
which is what "attached to the JIRA labels" means. It is **removed** the moment the real
issue is imported:

```cypher
MATCH (i:JiraIssue:__UNRESOLVED) WHERE i.__id IN $importedSelfs REMOVE i:__UNRESOLVED
```

This runs as step 5 of Phase 4 of every import (§12), which is the "later stage of the import"
that resolves them. A placeholder whose issue lives outside the configured projects stays
`__UNRESOLVED` forever — that is correct and the UI must render it distinctly (muted, no
navigation into the cockpit, but the "open in JIRA" link still works because we have its
`self`).

### 9.5 Sub-tasks

Absent from the sample but real in JIRA. If `fields.parent` is present, create
`(child)-[:subTaskOf]->(parent)` using the same unresolved-placeholder rule. If
`fields.subtasks` is a non-empty array, ignore it — it is the inverse of `parent` and
importing both directions creates duplicate truth.

---

## 10. Persisted application settings

All app-owned, all `__`-prefixed, all singletons for now (R2 + the RBAC seam in §14.1).

### 10.1 `__JiraSettings`

> **Superseded 2026-08-16 by ADR 0018 — this node is deleted, not merely unused.** There is no
> persisted project list any more; RBAC is the gate. Kept here as the historical record.

```cypher
MERGE (s:__JiraSettings {__id: 'jira-settings'})
SET s.projectKeys = $keys,            // list of strings, order = user's order
    s.updatedAt   = $now,             // ISO-8601 UTC
    s.updatedBy   = $user             // 'system' until RBAC exists
```

The project **keys** are stored, not ids or names: keys are what JQL consumes, and a
project renamed in JIRA keeps its key. The picker's display list (`GET /project`) is
fetched live and never persisted — a project deleted in JIRA simply stops appearing, and
if it is still in `projectKeys` the settings screen shows it as an unknown/stale chip
with a "remove" action.

### 10.2 `__JiraColumnConfig`

```cypher
MERGE (c:__JiraColumnConfig {__id: 'jira-columns'})
SET c.fieldIds = $orderedFieldIds,    // list of strings, order = column order
    c.updatedAt = $now, c.updatedBy = $user
```

`fieldIds` contains **only the optional columns**. The fixed columns (§13.2) are never
stored — they are a backend constant, so they cannot be accidentally removed by a bad
write and cannot drift between clients.

### 10.3 `__ImportRun`

See §11.2 for the full property set.

---

## 11. The import pipeline framework (generic)

Built once, reused by DOORS / Windchill / CAMEO. Nothing in this section may mention
JIRA outside of the `importerId` string.

### 11.1 Run lifecycle

```
QUEUED ──► RUNNING ──┬──► SUCCEEDED
                     ├──► SUCCEEDED_WITH_WARNINGS
                     ├──► FAILED
                     └──► CANCELLED
```

- **One run at a time per `importerId`.** A second start request while a run is active
  returns `409 Conflict` with the active `runId` — the UI then just opens that run's
  status panel instead of erroring. Enforce with a `Mutex` per importer id, not a global
  lock: DOORS and JIRA must be able to run concurrently.
- The run executes on `Dispatchers.IO` in a `SupervisorJob` scope tied to the application,
  **not** to the HTTP call that started it. The client disconnecting must not kill it.
- `CANCELLED` is reached by cancelling that coroutine; each phase must be
  cancellation-cooperative (check `ensureActive()` between batches). Work already
  committed stays committed — document that a cancelled import leaves partial data and
  the UI must say so.

### 11.2 `__ImportRun` node

```
__id        : "run-<uuid>"
importerId  : "jira" | "doors" | …
status      : QUEUED | RUNNING | SUCCEEDED | SUCCEEDED_WITH_WARNINGS | FAILED | CANCELLED
startedAt   : ISO-8601 UTC
finishedAt  : ISO-8601 UTC | null
phase       : current/last phase id
params      : JSON text — for JIRA: the exact JQL and page size used (§8)
counters    : JSON text — {issuesSeen, created, updated, unchanged, deleted, unresolvedCreated, unresolvedResolved, fieldsSeen, issueTypesSeen, pages}
warnings    : list of strings (capped at 200, then "+N more")
error       : string | null — message + exception class, never a raw stack trace
```

Runs are written at start, on every phase transition, and at the end. Prune to
`importer.runHistoryLimit` finished runs per importer at the end of each run.

The live log is **not** persisted — it lives in a bounded in-memory ring buffer
(1 000 lines) per active run. Persisting a per-line log to Neo4j is the wrong storage for
it; if durable logs are needed later, write them to a file, not the graph.

### 11.3 Phases

A phase is `{id, label, weight}` declared up front by the importer, so the UI can render
a stepper and a meaningful aggregate percentage before anything runs. JIRA's phases and
weights are in §12.

### 11.4 API + SSE contract

| Method | Path | Body / Result |
|---|---|---|
| `POST` | `/api/import/{importerId}/runs` | starts a run → `202` `{runId}`, or `409` `{activeRunId}` |
| `GET` | `/api/import/runs?importerId=&limit=` | run history (newest first) |
| `GET` | `/api/import/runs/{runId}` | full run state — this is the reconnect/late-join source of truth |
| `DELETE` | `/api/import/runs/{runId}` | request cancellation → `202` |
| `GET` | `/api/import/runs/{runId}/events` | **SSE stream** |

SSE events (Ktor `SSE` server plugin; `event:` name + JSON `data:`):

```
event: phase     data: {"runId":"…","phase":"issues","label":"Importing issues","index":3,"of":6}
event: progress  data: {"runId":"…","phase":"issues","current":450,"total":784,"percent":57}
event: log       data: {"level":"INFO","message":"page 5/16 (100 issues)","at":"…"}
event: counters  data: {"created":12,"updated":740,"deleted":3,…}
event: status    data: {"status":"SUCCEEDED_WITH_WARNINGS","finishedAt":"…","warnings":2}
```

Implementation notes:

- Emit a comment heartbeat (`: ping`) every 15 s so proxies do not close an idle stream.
- Throttle `progress` to at most 4 events/second — a 784-issue import can otherwise emit
  thousands of events and the zoneless change detection will still be doing work.
- Always send a `status` event as the last event, then close the stream server-side.
- A client connecting mid-run first `GET`s the run resource for the current state, *then*
  subscribes. Do not try to replay history over SSE.
- Fan-out to multiple subscribers via a `MutableSharedFlow(replay = 0, extraBufferCapacity
  = 256, onBufferOverflow = DROP_OLDEST)` per run. A slow client must never back-pressure
  the importer.

---

## 12. The JIRA import algorithm

Six phases. Weights are for the aggregate progress bar.

| # | Phase id | Label | Weight |
|---|---|---|---|
| 0 | `preflight` | Checking configuration and connectivity | 2 |
| 1 | `issuetypes` | Importing issue types | 3 |
| 2 | `fields` | Importing field definitions | 5 |
| 3 | `issues` | Importing issues | 70 |
| 4 | `links` | Linking issues | 12 |
| 5 | `sweep` | Removing deleted issues | 8 |

### Phase 0 — Preflight

1. Config present (`host`, `token`) → else `FAILED` "JIRA integration not configured".
2. `__JiraSettings.projectKeys` non-empty → else `FAILED` "no projects configured" (§8).
3. `GET /myself` → capture `timeZone` for the JQL bound; a 401 here fails fast with
   "token rejected" before any data is touched.
4. Apply constraints/indexes (§6.3) — idempotent, every run (`DOORS Importer.md` §7.1).
5. Build and record the JQL (§8).

### Phase 1 — Issue types

`GET /issuetype` → `UNWIND $rows` `MERGE (t:SEItem {__id: row.self}) SET t:JiraIssueType, t += row.props`.
One batch; there are only tens of these. Then the unused-type sweep from §9.1.

### Phase 2 — Field definitions

`GET /field` → same shape, batched at `neo4j.batchSize`. 1 171 rows = 2 batches.
Record `fieldsSeen`. Delete catalogue entries no longer returned (§9.2).

**Field metadata must be in memory for Phase 3** — build a `Map<String, FieldMeta>`
(`id → {name, custom, schemaType, schemaItems}`) from this phase's response and pass it
to the issue mapper. Never re-query Neo4j per issue for it.

### Phase 3 — Issues

Per page (`startAt`, stride = the response's own `maxResults`):

1. Fetch `/search?jql=…&fields=*all&startAt=…&maxResults=…`.
2. For each issue, run the mapper (§7): produce
   `{__id, props}` for the issue node, `{__id, props}` for its projection node,
   and the promoted-entity rows (project, issuetype, status, priority, resolution, users,
   components, versions).
3. Write in this order, one transaction per batch:
   - `MERGE` the shared entity nodes (project/type/status/priority/resolution/user/
     component/version) — deduplicate them **in memory across the whole page** first, or
     the same project node is merged 100 times per page.
   - `MERGE` the issue nodes with `SET i += row.props`.
   - `MERGE` the projection nodes + `__projection` edges.
   - `MERGE` the promoted relationships.
4. Accumulate the set of every `self` seen — this drives Phases 4 and 5.
5. Emit `progress` (`current = startAt + issues.size`, `total`).

**Property removal.** `SET i += $props` only adds and overwrites; a field that became
`null` in JIRA keeps its stale value. Fix it in the same statement, without dynamic
Cypher:

```cypher
UNWIND $rows AS row
MERGE (i:SEItem {__id: row.id})
  ON CREATE SET i:JiraIssue
SET i += row.props
WITH i, row
UNWIND [k IN keys(i) WHERE NOT k STARTS WITH '__'
        AND NOT k IN row.presentKeys] AS staleKey
CALL (i, staleKey) {
  SET i[staleKey] = null       // Cypher 25 dynamic property removal
}
```

`row.presentKeys` is the list of field ids the issue currently has a value for.
`SET i[k] = null` removes the property. If the target Neo4j build rejects dynamic
property assignment, fall back to `apoc.create.removeProperties` **only if** APOC is
already a dependency of this project; otherwise compute the stale keys in Kotlin by
reading `keys(i)` in the same batch and emit an explicit removal list. Do not build
Cypher strings by concatenation (R10). **This is the single trickiest part of the
importer — cover it with a dedicated integration test** (import an issue with a value,
re-import it with that field nulled, assert the property is gone).

### Phase 4 — Links

Runs after **all** pages are in, never per page — a link's target may live on page 16.

1. Collect every `issuelinks` entry seen in Phase 3 (keep them in memory keyed by
   `linkId`; the sample has 35 link entries across 23 of 50 issues, which extrapolates to
   ~550 for 784 issues — trivially memory-safe).
2. `MERGE` unresolved placeholders for targets whose `self` is not in the imported set
   (§9.4).
3. `MERGE` the `linkedTo` edges on `linkId`.
4. Delete `linkedTo` edges whose `linkId` was not seen this run **and** whose endpoints
   are both in the imported set (a link removed in JIRA). Leave edges touching
   `__UNRESOLVED` nodes alone.
5. `REMOVE i:__UNRESOLVED` for every placeholder that got imported for real this run.
6. Same treatment for `subTaskOf` if `parent` was present (§9.5).

### Phase 5 — Sweep

> **Superseded 2026-08-16 by ADR 0018.** With no project allow-list, the `__projectKey IN
> $configuredKeys` scope below no longer applies and the `deletedByConfig` counter is gone — one
> statement, `NOT i.__id IN $seenIds`, covers both what this section called "deleted-in-JIRA" and
> "de-configured": under RBAC-is-the-gate the importer cannot and need not tell them apart.

```cypher
MATCH (i:JiraIssue)
WHERE i.__projectKey IN $configuredKeys
  AND NOT i.__id IN $seenIds
OPTIONAL MATCH (i)-[:__projection]->(p:__JiraProjection)
DETACH DELETE i, p
```

- Scoped by `__projectKey`: issues belonging to a project **no longer in the JQL list**
  are handled by the same statement with `$configuredKeys` replaced by the full set of
  project keys present in the graph — i.e. run the sweep twice, once scoped to configured
  keys (deleted-in-JIRA issues) and once for keys that vanished from the config
  (de-configured projects, R4). Both are deletions; the counters distinguish them
  (`deleted` vs `deletedByConfig`) so the run summary can explain what happened.
- **Refuse to sweep if Phase 3 failed or was cancelled.** A partial `seenIds` set would
  delete the entire database. Guard this explicitly — it is the highest-consequence bug
  available in this feature.
- **Warn before mass deletion:** if the sweep would delete more than 20 % of existing
  issues, and the run was started interactively, mark the run
  `SUCCEEDED_WITH_WARNINGS` and log the count prominently. (A confirm-before-delete
  dialog is a natural extension; not required now.)
- Orphan cleanup afterwards: users, components, versions, statuses and priorities with no
  remaining relationships are deleted. Projects are kept — they are cheap and the settings
  screen references them.

### Phase 6 — Report (not a progress phase)

Write final counters + warnings to `__ImportRun`, emit the closing `status` event, prune
old runs. Post-import validation, mirroring `DOORS Importer.md` §6 Phase 6:

- every `JiraIssue` has non-empty `__id`, `__name`, `__version`, `key`;
- no `JiraIssue` has both `__UNRESOLVED` and a `summary` written by Phase 3;
- `count(JiraIssue) - count(JiraIssue:__UNRESOLVED)` equals `issuesSeen`;
- every `JiraIssue` has exactly one `__projection` edge;
- every `JiraIssue` has exactly one `inProject` and one `hasIssueType` edge.

Any failure ⇒ `SUCCEEDED_WITH_WARNINGS` with the offending `__id`s listed (capped).

---

## 13. Frontend — Angular 22

### 13.1 Where Settings lives (point 6)

**Recommendation: a persistent icon button on the right side of the top toolbar that
opens a `MatMenu`, whose entries route into a dedicated `/settings` page with a left
nav-rail.** Concretely:

```
<mat-toolbar>
  [☰]  System Engineering Cockpit        … nav …        [⚙ Settings ▾]  [avatar]
                                                          │
                                                          ├─ JIRA
                                                          ├─ Importers & runs
                                                          └─ (future) Users & roles
</mat-toolbar>
```

Why this shape rather than a modal or a slide-over:

- Settings here is **not** a small preferences panel — it holds a project multi-select, a
  1 171-row column picker and an import console. Those need a route, a URL you can
  bookmark and link to from an error toast (`/settings/jira`), and browser-back.
- A separate top-level `/settings` route keeps admin-only surface in one subtree, which
  is exactly the granularity RBAC will need (§14.1) — one guard on one route, not a
  scattering of `*ngIf`s.
- The gear-menu → route pattern is what Material's own docs, GitLab, Jira itself and
  GitHub all use; users find it without being told.
- The menu (rather than a bare link) lets a future non-admin user see only the entries
  they may open, with no layout change.

Routes:

```
/settings                      → redirect to /settings/jira
/settings/jira                 → JiraSettingsPage      (canActivate: adminGuard)
/settings/importers            → ImportRunsPage        (canActivate: adminGuard)
/issues                        → JiraIssuesPage        (all users)
```

The import status is *also* surfaced globally: a small chip in the toolbar appears
whenever any run is active (spinner + phase label), clicking it opens
`/settings/importers` focused on that run. That is the "button to open the status"
from point 9, and it works from any page.

### 13.2 The Issues view

`/issues`, a `MatTable` with **server-side** pagination, sorting and filtering — never
load 784 (eventually 50 000) issues into the browser.

Column order, left to right:

1. **Issue type** — icon only (`/api/jira/icon?issueTypeId=…`), `matTooltip` = type name.
   Fixed, not removable (point 14.3).
2. **Key** — e.g. `ProjectCRPT-252`, monospace, links to the in-app issue detail.
   Fixed, not removable.
3. *…user-selected columns, in the configured order…*
4. **Open in JIRA** — header-less last column, `open_in_new` icon, `href` built from the
   issue's `self`, `target="_blank" rel="noopener noreferrer"`, `aria-label="Open
   <key> in JIRA"`. Fixed (point 14.6).

> `self` is an API URL (`…/rest/api/2/issue/2626007`), **not** a browse URL. Opening it
> shows raw JSON. The backend must return a `browseUrl` alongside each row:
> `<host>/browse/<key>`, derived from the configured host and the key. Derived data ⇒ it
> is computed in the API layer at read time, never stored (R2). This is a real trap in
> the requirement as written — the column must use `browseUrl`, and `self` stays as the
> stored identity.

Behaviour:

- Cell values come from `coalesce(issue[fieldId], projection[fieldId])` (§7.4); `null`
  renders as an em-dash, list values as `MatChip`s (max 3 + "+N"), long strings are
  truncated at 120 chars with the full text in a tooltip.
- Sorting: only on columns whose `schemaType` is scalar or whose projection is a scalar;
  the rest render with sorting disabled. Sending an unsortable column to the backend must
  be rejected, not silently ignored.
- Free-text search box → backend `CONTAINS` on `key`, `summary` and `__name` only.
  Do not offer full JQL here; the Cypher console is the escape hatch for power users.
- Empty state: if no issues are imported, show an illustration + "No JIRA issues
  imported yet" + a button that deep-links to `/settings/jira` (admins only).
- Row click opens a detail drawer showing **all** non-null fields of that issue, grouped
  system-fields-first, using the `JiraField` catalogue for display names.

### 13.3 "Select fields to display" dialog (points 10, 11, 13, 14)

Opened from a toolbar button on the Issues page **and** from `/settings/jira`.
**Disabled when zero issues are imported** (point 13) — the disabled tooltip must say
why ("Import JIRA issues first"), never just be dead.

`MatDialog`, `width: 900px`, containing a virtual-scrolled table over the 1 171-row
field catalogue:

| Column | Content |
|---|---|
| ☑ | checkbox — selected for display |
| **Field name** | `field.name`; when the name is ambiguous (15 names cover 33 fields, §5) append the id as a muted suffix: `Classification ` `customfield_18201` |
| Type | `schemaType` chip (`option`, `string`, `array<option>`, …) |
| Source | `System` / `Custom` chip |
| ⇅ | drag handle (CDK drag-drop) to order the selected columns |

Above the table: a search box (matches name **and** id — searching `customfield_23700`
must work), filter chips for `System` / `Custom` / `Selected only`, a live
"`n` of 1 171 selected" counter, and a "Reset to defaults" action.

Defaults on first ever open: `summary`, `status`, `priority`, `assignee`, `created`,
`updated`. Sensible, and every one of them is a system field guaranteed to exist.

Fields with no `schema` (`issuekey`, `thumbnail`) are **excluded from the list entirely**
— `issuekey` duplicates the fixed Key column and `thumbnail` is not a data field.

Save → `PUT /api/jira/columns` → the table reloads with the new column set. Cancel
discards. The dialog must not write on every checkbox click.

### 13.4 Schema drift (point 14.5)

The persisted `fieldIds` may reference fields that no longer exist in JIRA.

- The backend returns column descriptors as
  `{fieldId, name, schemaType, stale: boolean}`; `stale = true` when the id is absent
  from the current `JiraField` catalogue.
- A stale column **still renders**, with the header showing the raw field id, a warning
  icon and the tooltip "This field no longer exists in JIRA — it will disappear after
  the next import." All its cells are empty. Nullable-by-design, exactly as specified.
- The picker dialog lists stale entries in a separate "No longer in JIRA" section at the
  bottom with a one-click remove.
- Never auto-remove a stale column. The user chose it; a silent disappearance looks
  like a bug.

### 13.5 JIRA settings page (`/settings/jira`)

> **Point 2 superseded 2026-08-16 by ADR 0018.** There is no project picker any more — RBAC is the
> gate. Point 2 below is the historical record of the pre-ADR-0018 design. The page's current shape:
> Connection (now also listing what the token can see, as a diagnostic, per ADR 0018), Columns,
> Import (now showing the next scheduled run alongside the manual trigger, and never disabled for
> lack of configured projects).

Sections, top to bottom:

1. **Connection** — read-only host, masked token indicator, "Test connection" button
   hitting `/api/jira/health`, showing the resolved JIRA user on success. The token is
   never editable from the UI; it lives in `application.yaml` (points 1–2).
2. **Projects in the query** (point 8) — a `MatChipGrid` of the configured keys, an
   autocomplete-backed picker fed by `GET /api/jira/projects` (proxied `/project`,
   showing `KEY — Name`, filtered to exclude already-selected), an **Add** button, and a
   remove `×` on each chip. Below it, a read-only preview of the JQL that will run — this
   is the single best debugging aid in the whole feature, and it costs one line.
   Removing a chip shows an inline warning: *"Issues from KEY will be deleted from the
   cockpit on the next import. Re-add the key at any time to import them again."* (R4,
   point 8.2.)
3. **Columns** — "Select fields to display" button (§13.3) + a summary of the current
   selection.
4. **Import** — the **Import JIRA Issues** button (point 9). Disabled while a run is
   active (with the active phase shown instead), disabled when no projects are
   configured. Below it, the last run's outcome (status chip, timestamp, counters) and a
   "View details" link into `/settings/importers`.

### 13.6 Import console (`/settings/importers`)

Generic — driven entirely by the framework in §11, so DOORS/Windchill/CAMEO appear here
for free.

- A `MatStepper` (horizontal, non-linear, read-only) with one step per declared phase;
  the active step shows a determinate `MatProgressBar` and `current/total`.
- A live log pane (monospace, auto-scroll with a "pause on scroll-up" behaviour, level
  filter).
- Counters as a compact stat row: created / updated / unchanged / deleted / unresolved.
- Cancel button while `RUNNING`.
- A history table of previous runs (status, importer, started, duration, counters),
  expandable to show the JQL and warnings.

State handling under zoneless Angular:

```ts
// ImportRunStore — one instance, provided in root
readonly run = signal<ImportRun | null>(null);
readonly logs = signal<LogLine[]>([]);
readonly isRunning = computed(() => this.run()?.status === 'RUNNING');

subscribe(runId: string) {
  const es = new EventSource(`/api/import/runs/${runId}/events`);
  es.addEventListener('progress', e => this.run.update(r => ({ ...r!, progress: JSON.parse(e.data) })));
  es.addEventListener('status',   e => { this.run.update(/*…*/); es.close(); });
  // every handler writes to a signal — nothing else triggers change detection
}
```

Reconnect: on `error`, close, `GET` the run resource once, and if it is still `RUNNING`
resubscribe with exponential backoff (1 s → 30 s). Stop retrying once the run is
terminal.

---

## 14. Backend — structure, API surface, and the authorization seam

### 14.1 The RBAC seam

Today: every request is an admin. Build it so that stops being true by editing one file.

```kotlin
// security/Authorization.kt
enum class Role { ADMIN, USER }

data class Principal(val id: String, val displayName: String, val roles: Set<Role>)

// THE seam. Today it fabricates an admin. Tomorrow it reads a JWT / SSO header / session.
fun ApplicationCall.principal(): Principal =
    Principal("system", "System", setOf(Role.ADMIN, Role.USER))

fun Route.requireAdmin(build: Route.() -> Unit) = createChild(...).apply {
    install(createRouteScopedPlugin("RequireAdmin") {
        onCall { call -> if (Role.ADMIN !in call.principal().roles) throw ForbiddenException() }
    })
    build()
}
```

Every admin-only route is wrapped in `requireAdmin { }` **from day one**, even though the
check always passes. Retro-fitting guards onto a live route tree is how endpoints get
missed. Mirror it in Angular with an `adminGuard` reading a `me()` signal fed by
`GET /api/me` (which today returns the fabricated admin) — the frontend guard is UX, the
backend guard is security; both must exist.

`GET /api/me` → `{id, displayName, roles: ["ADMIN","USER"]}`.

### 14.2 Package layout

```
backend/src/main/kotlin/com/sec/cockpit/
├── Application.kt                 // module wiring, plugin install
├── config/            AppConfig.kt, JiraConfig.kt, Neo4jConfig.kt
├── security/          Authorization.kt          // §14.1
├── graph/             Neo4jClient.kt            // driver, batching helpers, Cypher constants
│                      Schema.kt                 // constraints + indexes
├── importer/                                    // §11, importer-agnostic
│   ├── ImportRun.kt   ImportRunStore.kt  ImportRunService.kt  ImportEvents.kt
│   └── routes/        ImportRoutes.kt           // /api/import/**
├── jira/
│   ├── JiraApi.kt                               // JIRA_API_BASE constant, endpoint paths
│   ├── JiraClient.kt                            // Ktor client + OkHttp engine, auth, retry, paging
│   ├── JiraDto.kt                               // the issue envelope + loose JsonElement types
│   ├── JiraFieldMeta.kt                         // /field model
│   ├── JiraJqlBuilder.kt                        // §8
│   ├── mapping/       IssueMapper.kt            // §7 — pure, heavily unit-tested
│   │                  ValueClassifier.kt        // §7.2 shape rules
│   │                  DisplayProjector.kt       // §7.4 derivation
│   ├── graph/         JiraWriter.kt             // all Cypher for §12
│   ├── JiraImporter.kt                          // orchestrates the 6 phases
│   ├── JiraQueryService.kt                      // read path for the issues table, §14.4
│   └── routes/        JiraRoutes.kt             // /api/jira/**
└── common/            Errors.kt, Json.kt, Paging.kt
```

`IssueMapper`, `ValueClassifier` and `DisplayProjector` are pure functions over
`JsonElement` with no Neo4j and no HTTP. They are where the complexity is and where the
tests must be — the sample files in `docs/` are the fixtures.

### 14.3 REST API surface

All JSON. All admin-only routes wrapped per §14.1.

| Method | Path | Admin | Purpose |
|---|---|---|---|
| `GET` | `/api/me` | — | current principal + roles |
| `GET` | `/api/jira/health` | ✔ | `{configured, reachable, user, message}` |
| `GET` | `/api/jira/projects` | ✔ | live `/project` proxy → `[{key, name, id, avatarUrl}]` |
| `GET` | `/api/jira/settings` | ✔ | `{projectKeys, jqlPreview, updatedAt}` |
| `PUT` | `/api/jira/settings` | ✔ | `{projectKeys}` — validates keys (§8), returns the new `jqlPreview` |
| `GET` | `/api/jira/fields` | ✔ | catalogue for the picker → `[{id, name, custom, schemaType, schemaItems, ambiguousName}]` |
| `GET` | `/api/jira/columns` | — | `[{fieldId, name, schemaType, sortable, stale}]`, in order |
| `PUT` | `/api/jira/columns` | ✔ | `{fieldIds: []}` ordered |
| `GET` | `/api/jira/issues` | — | the table: `?page=&size=&sort=&dir=&q=&projectKey=&issueType=&status=` |
| `GET` | `/api/jira/issues/{key}` | — | one issue, all non-null fields + links, for the detail drawer |
| `GET` | `/api/jira/icon` | — | `?issueTypeId=` — proxied, cached icon bytes (§9.1) |
| `GET` | `/api/jira/stats` | — | `{issueCount, projectCounts, lastRun}` — drives empty states and disabled buttons |
| `POST` | `/api/import/jira/runs` | ✔ | start an import |
| `GET` | `/api/import/runs…` | — | §11.4 |

`GET /api/jira/issues` response:

```json
{ "page": 0, "size": 50, "total": 784,
  "columns": [ { "fieldId": "status", "name": "Status", "sortable": true, "stale": false } ],
  "rows": [
    { "key": "ProjectCRPT-252",
      "issueType": { "name": "Task", "iconUrl": "/api/jira/icon?issueTypeId=10002" },
      "browseUrl": "https://jira.company.com/jira/browse/ProjectCRPT-252",
      "unresolved": false,
      "values": { "status": "In Progress", "customfield_24805": "WSS", "duedate": null } } ] }
```

`values` is keyed by field id and contains **only the requested columns**. Never ship all
145 fields to the table — that is a 3 MB response for one page.

### 14.4 Read path — dynamic columns without building Cypher strings

Cypher supports dynamic property access with a variable key (`n[$key]`), which is exactly
what a runtime-configured column set needs — no string concatenation, R10 intact:

```cypher
CYPHER 25
MATCH (i:JiraIssue)
OPTIONAL MATCH (i)-[:__projection]->(p:__JiraProjection)
WHERE ($q IS NULL OR toLower(i.__name) CONTAINS toLower($q))
  AND ($projectKeys IS NULL OR i.__projectKey IN $projectKeys)
WITH i, p
ORDER BY coalesce(i[$sortField], p[$sortField], '') ASC   // dir chosen by two prepared variants
SKIP $skip LIMIT $limit
OPTIONAL MATCH (i)-[:hasIssueType]->(t:JiraIssueType)
RETURN i.key                                AS key,
       i.__id                               AS id,
       (i:__UNRESOLVED)                     AS unresolved,
       t.id                                 AS issueTypeId,
       t.name                               AS issueTypeName,
       [k IN $fieldIds | coalesce(i[k], p[k])] AS values
```

- Two prepared statements (ASC / DESC) rather than interpolating the direction.
- `$sortField` is validated against the current column set before it reaches Cypher.
- `browseUrl` is assembled in Kotlin from `jira.host` + `/browse/` + `key` (§13.2).
- The `total` for pagination comes from a separate cheap `count(i)` with the same
  `WHERE`; do not `collect()` everything to count it.
- Row cap: this endpoint is server-controlled, so `size` is clamped to 200 regardless of
  what the client asks — the same defensive posture as the Cypher console
  (`User-Facing Cypher Access` §6).

### 14.5 Error contract

One shape everywhere, so the frontend has one error renderer:

```json
{ "error": "JIRA_NOT_CONFIGURED", "message": "jira.host is not set in application.yaml", "detail": null }
```

`error` is a stable machine code (`JIRA_NOT_CONFIGURED`, `JIRA_UNAUTHORIZED`,
`JIRA_JQL_INVALID`, `NO_PROJECTS_CONFIGURED`, `IMPORT_ALREADY_RUNNING`,
`INVALID_SORT_FIELD`, `FORBIDDEN`). `message` is human-readable and safe to show.
`detail` carries JIRA's own `errorMessages` when relevant. Never leak a stack trace or
the token; install `StatusPages` to guarantee it.

---

## 15. Performance budget and known traps

| Concern | Target / mitigation |
|---|---|
| Full import, 784 issues | < 90 s wall clock. Dominated by JIRA's `*all` response time, not by Neo4j. |
| Page payload | ~3.4 MB per 50 issues ⇒ ~7 MB at `pageSize=100`. Stream-parse with `ignoreUnknownKeys`; do not hold more than two pages in memory. |
| Neo4j write batching | `neo4j.batchSize = 1000` rows/transaction (`DOORS Importer.md` §7.5). One page = one batch. |
| Shared-node MERGE storm | Deduplicate projects/users/statuses **in memory per page** before writing — otherwise 100 issues cause 100 merges of the same project node and lock contention. |
| Property count per node | ~145 on `JiraIssue`, ~100 on `__JiraProjection`. Both comfortably fine; the ~1 040-key raw payload is never stored. |
| Table query | < 200 ms for page 0 of 50 with 10 columns. If it is not, the missing index is on the sort field — dynamic property sorts cannot use an index, so accept a scan or add an explicit index for the handful of popular sort fields. |
| Issue-type icons | 9 distinct types ⇒ cache aggressively server-side (in-memory, keyed by type id, 24 h TTL). Never fetch per row. |
| Concurrent imports | One per importer id (§11.1). A second JIRA run is a `409`, not a queue. |
| Long-running SSE behind a proxy | 15 s heartbeat; if the app is deployed behind IIS/Apache on RH, verify response buffering is disabled for `text/event-stream`. |

Cross-platform (the app runs on RHEL **and** Windows 11):

- No shelling out, no path separators in code — the JIRA importer is pure JVM, unlike the
  DOORS one. Keep it that way.
- Line endings and file encodings never enter this feature; everything is UTF-8 over HTTP.
- If any DOORS-style script integration is added later, that is where OS branching lives —
  not here.

---

## 16. Testing and acceptance criteria

### 16.1 Unit tests (no network, no database)

Fixtures are the real sample files in `docs/`: `JIRA.json`, `JIRA_FIELDS.json`,
`JIRA_ISSUE_TYPE_EXAMPLE_DTO.json`.

- `ValueClassifier`: one case per distinct `(schema.type, schema.items, observed JSON
  shape)` triple — there are exactly **28** in the sample. Include the pathological ones:
  `any`-typed field holding a string; `any`-typed field holding `[]`; a field present in
  the issue but absent from `/field` (must not crash, must be stored with `schemaType`
  unknown); `checklist-item` arrays.
- `DisplayProjector`: option → `value`; option-with-child → `"A - B"`; user →
  `displayName`; progress → `"0/0"`; checklist → `"3/7"`; unknown → `null`.
- `IssueMapper` over the full 50-issue fixture: 1 029–1 041 keys in, ≤ 162 properties out
  per issue (145 mean), zero `null` values written, `__name` = `"<key>: <summary>"`, and
  the three different key-set sizes all map without error.
- `JqlBuilder`: quoting, key validation rejects `PROJ; DROP`, empty list throws, the
  `created <=` bound is formatted `yyyy/MM/dd HH:mm`.
- Pagination loop: server returns `maxResults: 50` when 100 was requested ⇒ the loop must
  stride by 50 and fetch all 16 pages (this is §3.3's trap; test it explicitly).

### 16.2 Integration tests (Testcontainers Neo4j + a stubbed JIRA)

Stub JIRA with Ktor's `MockEngine` serving the sample files.

1. **Fresh import**: 784 issues (fixture repeated/extended), assert node counts,
   relationship counts, one `__projection` per issue.
2. **Idempotence (R9)**: run twice; second run reports `created=0, deleted=0`, and the
   graph is byte-identical apart from `__ImportRun` nodes.
3. **Update**: change a summary and null a custom field; re-import; assert the summary
   changed **and the nulled property is gone** (§12 Phase 3 property removal).
4. **Delete**: remove an issue from the stub; re-import; assert it and its projection are
   gone and no dangling `linkedTo` edges remain.
5. **De-configure a project (R4, point 8.2)**: drop a key from `__JiraSettings`;
   re-import; assert exactly that project's issues are deleted and `deletedByConfig`
   counts them. Re-add the key; re-import; assert they come back.
6. **Unresolved links**: import a project whose issues link outside it; assert
   `:JiraIssue:__UNRESOLVED` placeholders exist with the right `__id`; then widen the
   project list, re-import, assert the label is removed and no duplicate node was created.
7. **Failure mid-run**: make page 9 return 500 permanently; assert the run ends `FAILED`,
   **the sweep did not run**, and no issues were deleted.
8. **Cancellation**: cancel during Phase 3; assert `CANCELLED`, sweep skipped.

### 16.3 Frontend tests

- Column picker: 1 171 rows render virtualized; searching `customfield_23700` finds it by
  id; ambiguous names show their id suffix; save emits the ordered id list.
- Stale column renders with the warning header and empty cells.
- Import button disabled with no projects configured, and while a run is active.
- SSE store: a `progress` event updates the signal and the DOM without Zone.js.

### 16.4 Acceptance criteria

The feature is done when, against the real instance:

1. An admin adds two project keys, sees the JQL preview update, and imports.
2. The pipeline shows six phases advancing, with per-phase progress, and finishes
   `SUCCEEDED` with counters that match `total` from JIRA.
3. The issues table shows Type icon, Key, the default six columns and the open-in-JIRA
   icon, paginated and sortable.
4. "Select fields to display" opens, offers all catalogued fields, and a saved selection
   survives a page reload and a backend restart.
5. Removing a project key and re-importing removes exactly that project's issues; re-adding
   it restores them.
6. An issue linked to an unimported issue shows the link, visually marked unresolved, and
   the marking disappears once the other project is imported.
7. Re-running the import immediately changes nothing (`created=0, updated=0, deleted=0`).

---

## 17. Decisions taken, and what was deliberately left out

**Taken (do not relitigate without a reason):**

- Hybrid storage (§7): verbatim on the issue node, JSON text for complex values, a
  `__projection` companion node for display strings, 13 promoted fields as real edges.
  Rejected: full graph decomposition (~100 k extra nodes, slow, no benefit for a table
  view) and raw-JSON-only (no Cypher filtering, no graph value).
- SSE + a REST run resource (§11.4). Rejected: polling (coarse), WebSocket (heavier than
  a one-way feed needs to be).
- `__id` = the JIRA `self` URL, not the key (§6.2).
- Sweep-based deletion rather than soft-delete (R4).
- Settings as a routed page behind a toolbar gear menu (§13.1).

**Left out on purpose:**

- Comments, worklogs, attachments, changelog/history. All are separate `expand`s or
  endpoints and multiply the payload; add them as a later phase if needed.
- Incremental import (`AND updated > lastRunTime`). Tempting, but it cannot detect
  deletions, so it must always be paired with a periodic full run. Revisit when issue
  counts pass ~20 k; the phase structure already accommodates it. **The periodic full run
  itself arrived in ADR 0018 (2026-08-16)** — `ImportScheduler` re-runs the (now
  project-unfiltered) import on an interval; the incremental variant considered here is
  still not built, and remains the thing to revisit at ~20 k issues.
- Sprint/board (Agile) data — that is `/rest/agile/1.0`, a different API surface.
- Per-user column configurations. The `__JiraColumnConfig` singleton becomes
  `(:__JiraColumnConfig)<-[:__hasColumnConfig]-(:__User)` when RBAC lands; nothing else
  changes.
- Linking JIRA issues to DOORS requirements (the `DOORS-ID` custom fields visible in the
  field catalogue are an obvious future join key — note it, build nothing).

---

## 18. Build order

Each step should compile, pass tests and be demoable on its own.

1. `JiraConfig` + `application.yaml` + `GET /api/jira/health` + "Test connection" in a
   stub settings page. Proves auth, host normalisation and the context path.
2. `JiraClient`: `/myself`, `/project`, `/issuetype`, `/field` with retry and redacted
   logging. Unit-tested against the sample files via `MockEngine`.
3. Graph schema (§6.3) + `JiraWriter` for issue types and field definitions +
   Phases 1–2 of the importer, run from a test, no UI.
4. Import pipeline framework (§11) with the SSE endpoint; wire Phases 1–2 to it and
   build the import console (§13.6). Now progress is visible for everything that follows.
5. `IssueMapper` / `ValueClassifier` / `DisplayProjector` with the full §16.1 unit suite.
   No I/O — get this right before it touches the database.
6. Phase 3 (issues), including property removal, with integration tests 1–3.
7. Phase 4 (links + unresolved) and Phase 5 (sweep), with integration tests 4–8.
8. `GET /api/jira/issues` read path (§14.4) + the Issues table with the three fixed
   columns only.
9. Column config: `__JiraColumnConfig`, the picker dialog, stale handling.
10. JIRA settings page: project chips, JQL preview, import button, last-run summary.
11. Polish: icon proxy + caching, detail drawer, empty states, the global running-import
    chip in the toolbar.

---

## 19. Note for CLAUDE.md

Add a pointer, not a copy:

```markdown
## JIRA integration
See `docs/JIRA_ISSUES_FEATURE_SPEC.md` for the JIRA importer and Issues dynamic view.
Non-negotiables: JIRA data is stored verbatim; app-derived data hangs off `__`-prefixed
relationships; `/rest/api/2/` is a constant in `JiraApi.kt`; the frontend never sees the
JIRA token.
```
