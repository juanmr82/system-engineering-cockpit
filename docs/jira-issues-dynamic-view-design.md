# JIRA → Issues Dynamic View — Technical Design

Project: System Engineering Cockpit
Scope: importing JIRA issues into Neo4j and exposing them as a configurable table in the Angular frontend
Stack: Ktor (backend, OkHTTP engine) · Angular + Angular Material (frontend) · Neo4j Community v2026.x

## 1. Context and API version notes

The spec calls this "JIRA Server Cloud API v2." `/rest/api/2/` is shared by both Jira Server/Data Center and Jira Cloud, but the two platforms are diverging on issue search. Atlassian has marked the classic `GET/POST /rest/api/2/search` (offset pagination via `startAt`/`total`) as **"currently being removed"** on Cloud, in favor of `GET/POST /rest/api/2/search/jql`, which uses opaque `nextPageToken` cursor pagination and drops the `total` count entirely. Data Center does not have this replacement endpoint (as of Jira DC 10.x) and keeps classic `/search`.

Because the app doesn't know in advance which flavor it's talking to, the JIRA client should target `/rest/api/2/search/jql` when available and fall back to `/rest/api/2/search` on a 404/deprecation response, or simply take a config flag (`jira.platform: cloud|datacenter`) set once by the admin. Either way, the importer must be written against cursor pagination (loop until `isLast: true` / no `nextPageToken`), not against a known total, since Cloud no longer reliably reports one.

All three endpoints confirmed to work without global admin rights:

- `GET /rest/api/2/search/jql` (or `/search` on DC) — "Permissions required: Issues are included... where the user has Browse projects permission." No admin needed.
- `GET /rest/api/2/field` — "Permissions required: None." Anonymous-accessible even.
- `GET /rest/api/2/issuetype` — standard browse-level permission.

This confirms point 3 in the spec: a JIRA token belonging to a regular user with Browse Project access on the configured projects is sufficient.

## 2. Configuration

```yaml
jira:
  host: "https://jira.example.com"       # no trailing slash
  token: "${JIRA_API_TOKEN}"              # env var, never literal in yaml
  platform: cloud                          # cloud | datacenter — picks search endpoint
```

`JiraApiConstants.API_BASE = "/rest/api/2/"` as a hardcoded constant in the client module, per point 4. Concatenate host + constant + endpoint path when building requests; never let callers pass a full path.

### Ktor client

```kotlin
val jiraClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            retryOnConnectionFailure(true)
        }
    }
    install(ContentNegotiation) { json(looseJson) }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        exponentialDelay()
    }
    defaultRequest {
        url(jiraConfig.host)
        header(HttpHeaders.Authorization, "Bearer ${jiraConfig.token}")
        // or Basic auth with email:token if the instance uses API tokens the old way
    }
}
```

Use OkHttp's connection pool and Ktor's built-in retry/backoff rather than hand-rolling retries — JIRA rate-limits aggressively (HTTP 429 with `Retry-After`), so `HttpRequestRetry` with respect for that header is worth adding explicitly since the default policy doesn't honor `Retry-After` out of the box; wrap it with a custom `shouldRetry`/`delayMillis` that reads the header when present.

## 3. Handling schema-looseness (point 9)

JIRA custom fields make a fully typed Kotlin data class per issue type impractical — two projects can define `customfield_10047` as a string in one and a user-picker object in another. Recommended approach:

**Serialization**: use `kotlinx.serialization` with `JsonObject`/`JsonElement` for the `fields` block of each issue, not a typed data class. Only the handful of fields the app actually reasons about structurally — `issuetype`, `key`, `status` for linking, `issuelinks` — get typed wrapper classes; everything else is parsed as a generic `JsonObject` and stored as-is.

**Field discovery**: call `GET /rest/api/2/field` once per import run (cheap, no pagination) to get the authoritative id → name → schema map for every field, system and custom. This is what makes the field-selection dialog in section 6 possible — it's the only place that reliably tells you a field's declared type (`schema.type`, `schema.custom`, `schema.items`) independent of any single issue's data.

**Storage shape in Neo4j**: don't try to model every custom field as a first-class graph property with a fixed name. Store each issue's raw `fields` JSON as a single stringified property (e.g. `raw_fields_json`) on the `Issue` node, *and* separately flatten the fields the admin has selected for display (section 6) into individually named properties (`customfield_10047`, etc.) so Cypher queries and the table view don't need to parse JSON at query time. This gives you the "data shall be kept as imported" guarantee from your project convention (the raw blob is the source of truth) while still making selected fields queryable/indexable.

**Libraries**: `kotlinx-serialization-json` for parsing, `neo4j-java-driver` (reactive or the new routing driver) for writes, structured concurrency (`kotlinx.coroutines`) to page through `/search/jql` while a second coroutine batches writes — don't block one on the other. For batch upserts, use `UNWIND $batch AS row MERGE (i:Issue {key: row.key}) SET i += row.props` in chunks of ~500–1000 to keep transactions bounded.

## 4. Data model (Neo4j, meta-relationship convention)

Per your project convention: imported data is kept unmodified on the imported nodes; anything the app itself adds is expressed as a relationship whose type is prefixed with `__` (double underscore), never as a bolted-on attribute on the imported node.

```
(:Project {key, name, id})
(:IssueType {id, name, description, subtask, avatarId, iconLocalRef})
(:Issue {key, id, self, raw_fields_json, <selected flattened fields...>})
(:Status {id, name, statusCategory, iconLocalRef})   -- optional, for shared status nodes across issues

(:Issue)-[:HAS_TYPE]->(:IssueType)
(:Issue)-[:IN_PROJECT]->(:Project)
(:Issue)-[:<LINK_TYPE_OUTWARD_NAME>]->(:Issue)   -- e.g. :BLOCKS, :RELATES_TO — see section 6

(:Issue)-[:__DISPLAY_METADATA]->(:__FieldDisplayConfig)   -- app-owned config, not JIRA data
(:IssueType)-[:__ICON_CACHE]->(:__CachedIcon {url, localPath, hash})
```

Everything under a `__` relationship or a node whose label starts with `__` is app-owned and safe to regenerate/delete without touching imported data. This cleanly separates "what JIRA said" from "what the Cockpit decided to do with it," which also makes re-import idempotent: the importer only ever touches `Issue`, `IssueType`, `Project`, `Status` nodes and their direct JIRA-defined relationships, never the `__`-prefixed ones.

## 5. Import pipeline (point 9)

Recommended sequence for the "Import JIRA Issues" button:

1. `GET /rest/api/2/issuetype` → upsert `IssueType` nodes (id, name, description, subtask, avatarId). For each, resolve and cache `iconUrl` (section 7).
2. `GET /rest/api/2/field` → refresh the field catalog used by the field-selection dialog. Diff against the last-known catalog; if fields were added/removed/retyped since last import, flag it (see section 6.4) rather than silently dropping display config.
3. For each configured project (point 8), run the search loop against `search/jql` (or classic `search`) with `jql = "project in (PROJ1,PROJ2) AND <admin-configured extra clause>"`, paging until exhausted, requesting `fields=*all` (or a scoped subset if you want to bound import size) and `expand=` nothing extra — you don't need `renderedFields`, only raw values.
4. Collect the full set of issue keys returned. Upsert each as an `Issue` node in batched `UNWIND` transactions.
5. **Deletion detection**: since JIRA gives you no delete-webhook in this design, compute the set difference between `{keys currently in Neo4j for these projects}` and `{keys just returned}`. Anything in Neo4j but not returned by the current JQL was deleted (or moved out of scope) — remove those `Issue` nodes and their relationships in one pass at the end of the run.
6. **Issue links**: after all issues in scope exist as nodes, do a second pass creating link relationships (section 6), since a link's target issue may not have been imported yet in step 4's iteration order.
7. Return an `ImportSummary` DTO: `{created, updated, deleted, issueTypesUpdated, fieldsAdded, fieldsRemoved, durationMs, warnings[]}` and render it in a Material snackbar or a small results dialog (point 9's "message telling the summary").

This "full sync + reconcile by diffing key sets" strategy is simpler and more robust than trying to track incremental deltas via `updated >= lastSyncTime` JQL, at the cost of re-fetching unchanged issues every run. Given you're on Community Neo4j and likely dealing with a bounded number of projects/issues (SE projects, not a SaaS-scale tracker), full reconciliation per import is the pragmatic choice — add the incremental `updated >=` JQL filter later as an optimization if import time becomes a problem, but keep the deletion-reconciliation pass regardless, since incremental sync alone can't detect deletions.

## 6. Dynamic field selection (points 10, 11)

### 6.1 Common-fields computation

When the admin clicks "Select fields to display": for each known `IssueType`, query 5 `Issue` nodes of that type from Neo4j (`MATCH (i:Issue)-[:HAS_TYPE]->(:IssueType {id:$id}) RETURN i LIMIT 5`), parse their `raw_fields_json`, and collect the union of field ids that appear with a non-null value across the sample. Intersect (or union, with per-type nullability noted — see 6.3) across all issue types to build the candidate list. Enrich each candidate with its declared name/type/schema from the `GET /field` catalog fetched in the import step, so the dialog can show human names ("Story Points") instead of raw ids ("customfield_10032").

### 6.2 Structured fields

For fields whose JIRA schema type is `object` (Status, Priority, Assignee, custom objects, etc.), inspect the sampled values to enumerate sub-keys (`description`, `iconUrl`, `id`, `name`, `self`, `statusCategory`, ...) and offer each as a separately selectable checkbox nested under the parent field, exactly as you described for `Status`. Represent a selection as a JSON-path-like string, e.g. `status.name`, `status.iconUrl`, `customfield_10032` (scalar, no subpath). Angular Material's `mat-tree` with `MatCheckbox` per node is the natural fit for this — parent field as the tree root, subkeys as leaves, tri-state parent checkbox if partially selected.

### 6.3 Fixed columns

`Key` (always present, string) and `Issue Type` (`issuetype.name`) are the two non-removable leading columns per point 11.6. Model them as always-selected, non-deselectable tree nodes rather than special-casing them elsewhere in the rendering code — keeps the table-rendering logic uniform (it just iterates the selected-field list, with these two always first).

### 6.4 Persistence and schema drift

Persist the selection as a `__FieldDisplayConfig` node (or a small ordered list of `__DisplayColumn` nodes) attached via `__DISPLAY_METADATA`, storing `{jsonPath, label, order}` per column. Because JIRA schemas can change between imports (a custom field renamed, retyped, or removed), the frontend table must render every selected column as nullable/optional — a missing path on a given issue just renders blank, it's not an error. On each import, diff the new field catalog against the persisted display config: if a selected field id no longer exists in `GET /field`, don't auto-delete the column (that would silently reshape the admin's saved view) — instead surface it as a warning in the import summary ("field customfield_10032 selected in display config no longer exists in JIRA") so the admin can fix the config deliberately. This matches your point 11.5 instruction to tolerate nullable/stale fields and correct on re-import, without making re-import destructive to admin configuration.

## 7. Icon caching (point 12)

Issue-type icons, status icons, and priority icons are all served from JIRA as `iconUrl` values pointing back at the JIRA host, and the same icon is reused across hundreds of issues. Fetching it per-row from the browser would mean hundreds of authenticated cross-origin requests per page load (and JIRA icon endpoints typically require the same auth as the API).

Recommended approach — **backend-side download-once, disk cache, own serving endpoint**:

1. During import (issue types, and optionally statuses/priorities the first time they're seen), the backend downloads each unique `iconUrl` exactly once, keyed by a hash of the URL (or of `(entityType, entityId)` if URLs are unstable). Store the bytes on local disk under a cache directory (not as a Neo4j property — Neo4j Community isn't built for blob storage, and you'd bloat the graph store for no query benefit), and record `{sourceUrl, localPath, contentHash, contentType}` on a small `__CachedIcon` node linked from the owning `IssueType`/`Status` node via `__ICON_CACHE`.
2. Expose `GET /api/icons/{hash}` from the Ktor backend, serving the cached bytes with `Cache-Control: public, max-age=31536000, immutable` and an `ETag`. Since the hash is content-derived, the URL never changes for the same icon, so this is safe to cache aggressively both server- and browser-side.
3. The Angular table/dialog references `/api/icons/{hash}` in `<img src>`, never the original JIRA URL. This means: one authenticated fetch per unique icon per import run (not per issue, not per page load), the browser's own HTTP cache handles repeat views for free, and you never leak the JIRA token to the frontend or to JIRA on every table render.
4. Skip re-downloading on subsequent imports if the URL is unchanged (track `lastSeen`/`sourceUrl` and only fetch if new or the URL changed) — icons rarely change, so this keeps import time down.

## 8. Issue links (point 13)

Straightforward to model. Confirmed from the API: an issue's `fields.issuelinks[]` array contains entries shaped as:

```json
{
  "id": "10001",
  "type": { "id": "10000", "name": "Dependent", "inward": "depends on", "outward": "is depended by" },
  "outwardIssue": { "id": "...", "key": "PR-2", "self": "..." }
}
```
(or `inwardIssue` instead of `outwardIssue`, depending on which side of the relationship this issue is on). There's also a dedicated `GET/POST/DELETE /rest/api/2/issueLink/{linkId}` resource if you ever need to fetch or manage a single link directly, but for import purposes the `issuelinks` array embedded in each issue's `fields` is sufficient and avoids N extra requests.

**Modeling**: create a directed Neo4j relationship per link, typed by a sanitized version of the link type's `name` (or `outward`/`inward` phrase) — e.g. `:BLOCKS`, `:RELATES_TO`, `:DUPLICATES` — from the issue holding the `outwardIssue` reference to the target, matching JIRA's own outward/inward semantics. Store the JIRA link type's `id` and both phrase variants (`inward`, `outward`) as relationship properties so the UI can render "PROJ-1 blocks PROJ-2" and "PROJ-2 is blocked by PROJ-1" correctly from either endpoint without a second lookup.

**Two real wrinkles worth flagging**:

- **Sub-tasks arrive in a separate `sub-tasks` field**, not `issuelinks`, with the same `{id, outwardIssue, type}` shape but `type.name` typically empty and `type.outward = "Sub-task"` / `type.inward = "Parent"`. Handle it as its own relationship type (`:HAS_SUBTASK` or reuse the generic link-type mechanism) rather than assuming all links come from `issuelinks`.
- **Cross-scope links**: a link can point to an issue in a project you haven't configured for import (point 8's project list), so the target key may never exist as a node. Two options: (a) create the relationship anyway pointing at a lightweight stub `Issue` node containing just the key (no fields), flagged so the UI can render it distinctly as "out of scope," or (b) defer link creation and silently drop links whose target was never imported. (a) is more useful for a systems-engineering traceability tool where knowing *that* a dependency exists outside your import scope is itself valuable information — recommended over silently dropping it.

## 9. Settings menu and navigation (point 6)

Modern Angular Material convention: an icon button (gear/`settings`) in the top toolbar (`mat-toolbar`), right-aligned near a user/account menu if you have one, opening either a dropdown `mat-menu` for quick links or — better, given how much lives under it (JIRA config, project list, import trigger, field selection, future RBAC) — routing to a dedicated `/settings` feature module with its own side-nav (`mat-nav-list` inside a secondary `mat-sidenav`) or a tabbed layout (`mat-tab-group`) for "General," "JIRA Integration," "Display Fields," etc. Given the settings surface will only grow (RBAC is explicitly coming later), the routed-module-with-subnav approach scales better than a flat menu and gives you a natural place to gate individual tabs/routes behind role checks once RBAC lands — for now, guard the whole `/settings` route with a simple `isAdmin` flag/guard that can be swapped for real RBAC later without restructuring the UI.

The JIRA-specific settings (host/token are backend-config-only per points 1–2, not editable from the UI) live under a "JIRA Integration" tab: the project list editor (point 8, a simple add/remove chip list or table bound to the `__ProjectImportConfig` list), the "Import JIRA Issues" button with its progress/summary feedback (point 9), and the "Select fields to display" button opening the tree-checkbox dialog (point 11).

## Open items for a follow-up pass

- Whether to persist `raw_fields_json` per issue or only the flattened selected fields, once real issue volumes are known (storage/query tradeoff).
- Whether `updated >=` incremental JQL is worth adding once full-reimport time is measured against real project sizes.
- RBAC design itself is explicitly deferred per point 6 — this doc only leaves the `isAdmin` guard point where RBAC will plug in later.

---

# 10. What was built — and the eight places this document was overruled

**§1–§9 above are the original design and are not all implemented as written.** This section is the
record of what shipped on 2026-08-10. **Read `docs/adr/0013-jira-import-in-the-backend.md` before
changing any of it** — it argues every departure below, including the ones it did not take.

The reason there are so many is that §1–§9 was written outside this repository's own rules. Six of
the eight conflicts are with `CLAUDE.md` R1–R6 or with ADR 0010 and ADR 0012, all of which predate
it.

| # | This document says | What was built | Why |
|---|---|---|---|
| 1 | §4: `(:Issue {key, id, self, raw_fields_json})`, `MERGE` on `key` | `(:SEItem:JiraIssue {__id: 'jira:issue:PROJ-42', …})` | R6 — a source's own id is never a key; §1 — a new source joins on `:SEItem` and nothing else |
| 2 | §4: `:HAS_TYPE`, `:IN_PROJECT`, containment per source | `__child` for project→issue **and** issue→sub-task; `hasType` for the type | R3 — one hierarchy relationship for every source, so one tree component walks DOORS and JIRA alike |
| 3 | §8: a relationship type per link type — `:BLOCKS`, `:RELATES_TO` | one `issueLink` type carrying `linkTypeId`, `linkTypeName`, `inward`, `outward` | ADR 0010 — link types are admin-defined, so these would be graph names invented from source data at runtime, undeclarable and unrenamable |
| 4 | §5 step 5: delete issues the search no longer returns | **kept** — but as a deliberate departure from ADR 0012, argued rather than inherited | JIRA removes an issue's links with the issue, so there is no dangling evidence to preserve, and the reconciled set is a JQL scope an admin edits |
| 5 | §3: flatten only the admin-selected fields | flatten **everything** to depth 3; select on read | Otherwise imported data is a function of app config: a column added in the dialog would be blank until somebody re-imported |
| 6 | §4: `__DISPLAY_METADATA` → `:__FieldDisplayConfig` | `:__Meta:__AttributeSetting` on a `:JiraSource` node, plus a new `importScope` kind on `:JiraProject` | R2 — a meta node carries `__metaId`, `__metaKind` from a closed enum, `__schemaVersion` and audit fields, and hangs off the imported graph |
| 7 | §6.4: store `{jsonPath, label, order}` | `{attributeName, visible, order}` — **no label** | R5 — the wording is resolved from the JIRA field catalogue on read, so a field renamed in JIRA renames its column without migrating live user data |
| 8 | §9: `/settings` guarded by an `isAdmin` flag | `/settings` with no guard, reached from a toolbar gear | There is no authentication at all yet, so the guard would read a constant `true` — a thing to delete when RBAC lands, which meanwhile looks like access control exists |

Three smaller ones, for completeness: **§6.2's `mat-tree`** is a two-level list, because a path is
split at its first dot so the tree is never deeper than two and a tree component would cost the
direct control of the tri-state parent; **§5 step 7's snackbar** is a dialog, because an import
reports a dozen numbers and some of them mean somebody has to go and do something; and **§6.1's
intersection across issue types** is a *union*, because an intersection hides a field only one issue
type carries and that is exactly the column somebody wants.

### §6.1 cannot work as written, and the catalogue is why

**A field that is unset on every sampled issue is `null` in the JSON.** The flattener emits that
null deliberately — `SET n += props` removes a property whose value is null, which is what clears a
field a user cleared in JIRA — so the key exists on **no node at all**, and the discovery query,
which is `UNWIND keys(i)`, cannot see it however many issues it scans. §6.1's "query 5 issues of
that type and collect the fields that appear with a non-null value" has the same blind spot and no
sample size fixes it.

The field is nevertheless *defined in JIRA*, and `GET /rest/api/2/field` lists it. So the selection
tree is built from the **union of the catalogue and the data**, and the catalogue is what makes the
list authoritative rather than merely observed. The import already fetched it — §5 step 2 — but it
was only being used to put a human name on a discovered path, which is the smaller half of what it
is for.

What the catalogue can promise depends on the declared schema, and the tree says which:

| Field's state | Offered as |
|---|---|
| paths found in the data | as before — the field, or its sub-keys, with real sample values |
| catalogue only, scalar type (`string`, `number`, `date`, `datetime`) | **selectable now.** The schema states the exact path the flattener will write, so the column can be chosen before anybody fills the field in, and is blank until they do |
| catalogue only, `array` of scalars | selectable, same reasoning — it is one list property at the field's own id |
| catalogue only, object or array-of-object (`user`, `option`, `status`, …) | shown with its name and type, **not selectable**. Its sub-keys come from data and there is none, so `customfield_20002.name` would be a guess — and a wrong guess is a column blank for ever |

**Nothing here invents a path.** `JiraFields.flattensToOwnPath` is the one rule, it is pure, and
`JiraFieldsTest` asserts it against what the flattener actually does so the prediction and the
writer cannot drift apart.

One consequence worth stating: **the §6.4 stale-column warning had to be narrowed at the same time.**
It used to fire when a selected path was not in the discovered set, which would now flag every
correctly-chosen column of a field nobody has filled in yet. It fires when *neither* source knows the
path — no issue carries it **and** JIRA's catalogue no longer lists its field — which is what "JIRA
dropped this field" actually means.

### Not built: §7, icons

Deferred with the user's agreement. It needs bytes on disk, which is a second persistence mechanism
(`CLAUDE.md` §11 forbids one without asking) and a cache directory that has to work under the user
profile on the offline Windows workstation. Issue type renders as text. If it is picked up, the
proportionate first version is a bounded in-process cache in front of a proxy endpoint — that keeps
the JIRA token off the frontend, which is §7's actual point, without introducing a store.

### Measured, on a real export

`docs/TEST_JIRA_DATA.json` (untracked — one page of a live Data Center search, 50 issues, five
projects) is what `JiraRealExportTest` runs the flattener over. Two numbers out of it are now
load-bearing and both are recorded next to the code they constrain:

- **1 041 raw fields per issue → 1 729 flattened paths, 735 of them non-null.** Only the non-null
  ones become properties, because `SET n += props` removes a property whose value is null — so 735
  is the length of the list the selection dialog renders, which is why it has a search box. Field
  discovery scans **500** issues (this `UNWIND keys(i)` is one row per issue per property) and may
  return up to 2 500 paths.
- **`__rawFields` is ~68 kB per issue** — ~1.4 GB at the default 20 000-issue ceiling, and the larger
  half of what this source stores. `jira.storeRawFields: false` is the first switch to reach for,
  which settles this document's own first open item with a number rather than a guess.

### What §1 got right, and it mattered

The platform split. `/search/jql` really is cursor-paginated with no `total`, and writing the loop
against a known total would have had to be unwritten. The loop is a cursor loop and Data Center's
`startAt` is fed into it as a cursor that happens to be a number — neither branch ever asks how many
issues there are, because on one of the two platforms nothing can answer. `JiraHttpClientTest`
covers both, including the two ways each platform says "that was the last page" and the one that
says it by omission.

One thing §1 does **not** mention that cost a real decision: `ORDER BY key ASC` is appended to every
search. Data Center pages by offset, and an unordered query may return rows in a different order
between pages — which silently skips some issues and imports others twice.

### Where the code is

```
backend/src/main/kotlin/com/sec/
  source/jira/JiraNames.kt          ← every JIRA name, one declaration each (ADR 0010)
  source/jira/JiraFields.kt         ← the flattener and the Tier-1 derivations. Pure, 17 unit tests
                                      + 8 more in JiraRealExportTest, over a real export
  source/jira/JiraRows.kt           ← wire object → graph row. Pure, 14 unit tests
  source/jira/JiraApi.kt            ← the read-only interface, and the wire types
  source/jira/JiraHttpClient.kt     ← the one HTTP client. Paging, auth, Retry-After. 12 tests
  source/jira/JiraImporter.kt       ← the pipeline, and JiraJql
  source/jira/JiraGraphWriter.kt    ← the ONLY thing that writes an imported node in this process
  source/jira/JiraProjection.kt     ← reads: the issues page, the field tree, the projects
  graph/cypher/JiraCypher.kt        ← schema, import, reconciliation, reads, Tier-2 writes
  api/dto/JiraDtos.kt, api/routes/JiraRoutes.kt
backend/src/test/kotlin/com/sec/JiraFeatureTest.kt   ← 16 tests, @Tag("docker")
frontend/src/app/features/jira/                      ← the table, and both dialogs
frontend/src/app/features/settings/                  ← the routed tab
```
