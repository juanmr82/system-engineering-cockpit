# Handover

Transient session-to-session note — not project documentation. Delete once its content is
absorbed into commits or superseded.

## State as of 2026-08-05 (end of session 3)

Branch `master`, last commit `c26ae99 Refactor frontend`. Note `master` is **not** the repo's
main branch and nothing has been pushed.

Three of this session's four blocks are already committed:

- `f427c4c Refactor backend` — backend review items 1–7
- `c26ae99 Refactor frontend` — the paper visual style and the frontend quality gate

**Uncommitted in the working tree: the Req review backend only** (~11 files: `ReviewCypher`,
`ReviewProjection`, `ReviewRoutes`, `ReviewDtos`, `SystemLevelChange`, `ReviewFeatureTest`, ADR
0005, plus edits to `MetaWriter`, `DoorsProjection`, `MetaSchema`, `Aliases`, `ModuleCypher`,
`ModuleDtos`, `Routes`, `Application`, `ModulesFeatureTest`, `CLAUDE.md`, `REQ_REVIEW.md`).

That block is finished but only partly verified, and that is where to pick up.

`docs/proposed_new_style.md` has been deleted — its content is now `CLAUDE.md` §8 (surfaces and
neutrals), `styles/_tokens.scss`, `_mixins.scss` and `_document.scss`, and ADR 0003.

---

## Resume here

**Prove the Req review backend works against real data, then build its Angular view.** Nothing
else is half-done. Hold off committing the review backend until one of the two options below
passes — it is the one block that might still need reshaping.

The backend for `docs/REQ_REVIEW.md` is written and compiles, but **no review endpoint has ever
been called and `ReviewFeatureTest` has never run.** Do not start the frontend until one of these
passes — the whole point of doing the backend first was to build the view on a proven API.

**Option A — the fixture tests (thorough).** Needs the Docker *daemon* running; the client is
installed (29.4.0) but the daemon was not up.

```
./gradlew :backend:integrationTest
```

Runs `ReviewFeatureTest` (12 tests) and `ModulesFeatureTest` against real Neo4j Community.

**Option B — the live 984-object module (more interesting data).**

```
SEC_NEO4J_USER=neo4j SEC_NEO4J_PASSWORD=admin123 ./gradlew :backend:run
curl -s "http://localhost:8080/api/v1/modules/ZG9vcnM6Ly9kb29ycy5jb21wYW55LmNvcnA6OTYwMS8_dmVyc2lvbj0yJnByb2RJRD0wJnVybj11cm46dGVsZWxvZ2ljOjoxLTAwMDAwMDAwMDAwMDAwMDAtTS0wMDA5NjlhMg/objects?limit=2"
```

A `404` there means the running process predates the review backend — `/objects` is the only
endpoint unique to the uncommitted work, so it is its own build check. (`/api/v1/ready` is *not*
a discriminator: it shipped in `f427c4c`.) If the ref has gone stale,
`curl -s localhost:8080/api/v1/modules` gives the current one.

A backend was left running at the end of the session, port unconfirmed — check `:8080` and `:8081`,
and restart it either way so it is definitely built from this tree.

What the `/objects` response must show, against the real module:

| Expect | Why it matters |
|---|---|
| rows ordered `SRD-1`, `SRD-2`, … | `ORDER BY __sortKey`, not creation order or `objectNumber` |
| `"total": 984`, `truncated: true` at `limit=2` | paging + the cap |
| `attributes: {}` | correct for this sanitised export, and proves the `__` filter strips `__sortKey`/`__moduleUrl` instead of leaking them as columns |
| `type: "TBD"` | `DOORSTBD` mapped through `Aliases`, never a raw label string |
| some `references.outgoing` with `resolved: false` | the 318 `:__UNDEFINED` placeholders |
| `incomingComplete: false` everywhere | hard-coded; see O5 below |

**Then build the view** (`docs/REQ_REVIEW.md` §1–§7): module selector, action bar (gear / save /
search), dynamic-column table with CDK virtual scroll, References and Comment columns, settings
dialog, detail panel, exit guard, requirements-only filter. §11's questions are all answered in
that file — read them first, they change the UI.

---

## What was built this session

### 1. Backend review items 1–7 (`docs/BACKEND_REVIEW.md` §6)

Item 8 (OpenAPI) was out of scope and still is.

- `StatusPages` maps everything to RFC 9457 with the `CallId` in `instance`; `Ref.decodeOrNull`
  makes decoding total, so a hand-edited URL is a 400 not a 500.
- Container tests tagged `docker`, excluded from `check`, with a `:backend:integrationTest` task;
  `ModulesFeatureTest` owns its container lifecycle explicitly.
- `meta/MetaSchema.kt` applies the `:__Meta(__metaId)` constraint and the policy/attribute-setting
  indexes at startup, idempotently.
- Transaction timeouts on every session, from config, carried on `GraphDriver`.
- Credentials via Ktor `$VAR` substitution — `System.getenv` and the build-file test hack are gone.
- `verifyConnectivity()` at startup; `/api/v1/health` (liveness) and `/api/v1/ready` (readiness)
  are now separate; loggers in use; `logback-production.xml` for JSON.
- Routes split into `api/routes/*`, collaborators constructed in `module()`.

### 2. The paper visual style (committed in `c26ae99`; source doc since deleted)

Adopted app-wide and extended to everything the proposal did not cover — tables, dialogs, tabs,
nav, forms, chips. Tokens in `_tokens.scss`, patterns in `_mixins.scss`, Material reached only
through M3 token overrides in `_theme.scss` (no `::ng-deep`, no `.mat-mdc-*` selectors).
`_document.scss` holds the requirement-tree vocabulary for the view being built next — nothing
includes it yet, and Sass mixins emit nothing until included, so it costs no bytes.

Verified by eye in the browser. Two defects found and fixed while doing so: Material's drawer
defaults to a 360px container against §9's 280px nav (an 80px band of white), and
`/requirements/review` shipped the literal string `:__Meta` in user-visible copy.

### 3. The frontend quality gate

`npm run lint` and `npm test` now exist and pass. ESLint 10 flat config + `angular-eslint` 22,
`jsdom` for the Vitest runner, and 7 specs (`EmptyState`, and the Modules search including the
accent-insensitive case).

**`sec/no-internal-namespace`** (`frontend/tools/eslint/sec-rules.mjs`) enforces R5. It tells an
internal name from BEM by what precedes the underscores — `sec-modules__header` has a block name in
front, `:__Meta` does not. Checks `.html` wholesale and `.ts` string/template literals only, so
comments about `__updatedAt` are fine. Verified against all four cases.

### 4. The Req review backend (`docs/REQ_REVIEW.md`)

New: `ReviewCypher`, `ReviewProjection`, `ReviewRoutes`, `ReviewDtos`, `SystemLevelChange`,
`ReviewFeatureTest`. Extended: `MetaWriter`, `DoorsProjection`, `MetaSchema`, `Aliases`,
`ModuleCypher`, `ModuleDtos`.

Endpoints added: `GET /modules/{ref}/objects`, `POST /modules/{ref}/comments`,
`GET /items/{ref}`, `GET /items/{ref}/traces[?direction=in]`. `POST /modules/{ref}/settings` now
also takes the three per-attribute flags.

---

## Verified / not verified

| | Status |
|---|---|
| `./gradlew :backend:check` | **green** (compiles, 4 Docker-free test classes pass) |
| `npm run lint` / `npm test` / `npm run build` (from `frontend/`) | **green** — 7 tests |
| Modules view + settings dialog in the browser | **verified by hand** against live Neo4j |
| Paper style across shell, sidenav, table, dialog, empty states | **verified by eye** |
| `ReviewFeatureTest` (12 tests) | **never run** — no Docker daemon |
| `ModulesFeatureTest` | **never run** — same |
| Any review endpoint at runtime | **never called** |

---

## Environment

- **Docker client 29.4.0 is installed but its daemon was not running.** Start Docker Desktop and
  `integrationTest` becomes available — that was not true in earlier sessions.
- Neo4j runs natively from `C:\Users\juanm\neo4j\neo4j-community-2026.06.0`, creds
  `neo4j` / `admin123`. No Windows service, so use `./bin/neo4j.bat console`.
- Backend `:8080`; frontend `npm start` → `:4200` (`proxy.conf.json` forwards `/api`).
- Neo4j HTTP API on `:7474` is the quickest way to inspect the graph:
  `POST /db/neo4j/tx/commit` with basic auth and `{"statements":[{"statement":"..."}]}`.

### The live graph, as measured

984 `DOORSObject` (487 plain `DOORSTBD`, 399 `DOORSTableCell`, 92 `DOORSTableRow`, 6 `DOORSTable`),
318 `:__UNDEFINED` placeholders, 1 `DOORSModule`, 984 `__child`, 409 `refersTo`,
1 `:__Classification`.

**No user attributes at all** — objects carry only `id`, `objectNumber`, `objectLevel` plus
`__`-prefixed keys. This is the sanitised-export case (`CLAUDE.md` §10), so attribute discovery
legitimately returns `[]`, every object is `DOORSTBD`, and the mandatory/visible/verification flows
cannot be exercised against this data. An unsanitised export is needed for that.

Also noticed: some objects carry a property literally named **`__taSbleRowIndex`** — a corrupted
`__tableRowIndex`. Importer-owned; not touched. Worth raising with whoever produced the export.

### Traps that cost time

1. **`angular.json` changes need a dev-server restart.** It is build config, not watched source.
   The symptom is *all component CSS silently missing*, which looks exactly like a broken refactor.
2. **The `Write` tool mangles raw Unicode combining characters.** The accent-stripping regex in
   `modules.ts` must read `/[\u0300-\u036f]/g` escaped.
3. **Never run npm as `npm --prefix frontend …` from the repo root.** It also changes where
   `install` writes: it silently created `frontend/frontend/node_modules` and left `package.json`
   untouched, so packages appeared installed but were not recorded. Run npm from `frontend/`.
   This is now in `CLAUDE.md` §11.
4. Both shells were intermittently unavailable at the end of this session (a harness classifier
   outage, nothing to do with the project). If it recurs, `! <command>` typed into the prompt runs
   in the user's shell and the output lands in the conversation.

---

## Known gaps

- **`GET /api/v1/config/navigation` is still a TODO** and 404s on every page load. The sidenav's
  hardcoded fallback masks it — this is the one standing console error and it is expected.
- **Inter is not shipped**; `public/fonts/` holds only `.gitkeep`, so the app renders in the
  Segoe UI fallback. The `@font-face` contract is in `styles.scss`.
- **The Material icon *font* is not self-hosted** (§8 forbids the CDN). Only `gearbox` and
  `account-circle` exist as SVGs; a new `<mat-icon>ligature</mat-icon>` renders as raw text.
- **`docs/features/attribute-policy-checks.md` is not implemented** —
  `GET /modules/{ref}/checks/attribute-policy` does not exist. Its spec is complete.
- **No backend static analysis** (ktlint/detekt). Flagged in `BACKEND_REVIEW.md` §5 as needing its
  own decision; `explicitApi()` carries part of the weight.
- **O5, new:** `incomingComplete` is hard-coded `false`. Incoming links stay incomplete until every
  referencing module is imported and nothing tracks which those are. The field exists so the caveat
  travels with the data; making it real needs import-coverage tracking and no wire change.
- `SE_ITEM_SCHEMA.md` and `DOORS_TO_NEO4J_IMPORTER_SPEC.md` are still stubs, and the DOORS importer
  is still `NotImplementedError`. The live data was loaded by some other means.

---

## Decisions taken, and where they live

All in `docs/adr/` — not here, and not to be re-litigated without changing the ADR:

- **0002** — error responses and log format. Includes *why there is no `status(NotFound)` handler*
  in StatusPages (it would overwrite route-specific 404 bodies).
- **0003** — the paper visual style: `--sec-*` naming kept, the cooled neutral ramp, the four
  extrapolation rules, and the two proposal rules deliberately not adopted.
- **0004** — the frontend quality gate and the R5 lint rule's BEM-vs-internal-name test.
- **0005** — the Req review backend: `SystemLevelChange` (absent vs explicitly cleared),
  `:__AttributeSetting` as a second Shape-B kind with `mandatory` still routed to `:__Policy`,
  module-membership checks on comment writes, and pattern comprehensions over
  `OPTIONAL MATCH` + `collect`.

`CLAUDE.md` was amended for: the R7 exit-guard wording (required by `REQ_REVIEW.md` §9.1), the new
`attributeSetting` meta kind, the alias-map additions, the surfaces/neutrals section in §8, the
`api/routes/` structure, the new endpoints in §5, the frontend quality-gate dependencies in §4, and
the enforced R5 lint rule in §11.

No architectural decisions are pending.
