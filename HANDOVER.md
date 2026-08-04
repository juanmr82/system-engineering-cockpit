# Handover

Transient session-to-session note — not project documentation. Delete once its
content is absorbed into commits or superseded.

## State as of 2026-08-05

Everything below is **uncommitted** on `master`, on top of the initial scaffold
(`56cf81d`). Nothing has been committed since. `git status` shows ~55 changed/new paths;
that is expected, not drift.

The **Requirements → Modules** feature (`docs/features/requirements-modules.md`) is
implemented end to end and was verified by hand in the browser against the real local
Neo4j. The frontend was then refactored onto a new component standard, now written into
`CLAUDE.md` §6.

### What was built

**Backend** (scaffolded in a prior session, verified compiling and exercised manually):
`Ref`, `SystemLevel`, `UuidV7`, `SaveModuleSettingsOutcome`, `Problems`, `CurrentUser`,
`ModuleCypher`, `ModuleDtos`, plus `DoorsProjection` / `MetaWriter` / `Routes` changes.
Endpoints live: `GET /modules`, `GET /modules/{ref}`, `GET /modules/{ref}/attributes`,
`POST /modules/{ref}/settings`, `GET /config/system-levels`.

**Frontend**: the modules list (search, sort, empty/loading/error states) and the
two-tab settings dialog (Signal Forms, sticky headers, R7 save model).

**Frontend standard** — new, documented in `CLAUDE.md` §6, applied to *all* components:

- Three files per component (`.ts`/`.html`/`.scss`) via `templateUrl`/`styleUrl`.
  **Zero inline `styles:` blocks remain in `src/app`** — keep it that way.
- `src/styles/_mixins.scss` for recurring patterns; `src/styles` is on the Sass load
  path via `stylePreprocessorOptions` in `angular.json`, so use `@use 'mixins' as sec;`.
- Dialogs get a static `open()`; `shared/dialog/modal-dialog.config.ts` holds the R7
  modal contract.
- Custom icons are `.svg` files in `public/icons/`, registered in
  `core/icons/sec-icons.ts`. Paths there must be **root-absolute**.
- `logo.component.ts` → `logo.ts`, `LogoComponent` → `Logo` (repo uses no
  `.component` suffix; the feature specs' `*.component.ts` spelling is stale on that
  point only — the file *split* they ask for is real).

### Verified by hand, in the browser

Save writes `:__Meta:__Classification` + `:__Meta:__Policy` with all audit fields; the
`DOORSModule` node's property map is byte-identical before/after (acceptance criterion
14); `MATCH (m:__Meta) DETACH DELETE m` removes exactly this feature's data; Cancel and
ESC persist nothing (`disableClose` holds); no `__`-prefixed string reaches the DOM or
the URL. Test data seeded for this was removed afterwards — the graph is back to the
1303 pre-existing nodes.

## Environment

- **No Docker on this machine.** Neo4j runs natively from
  `C:\Users\juanm\neo4j\neo4j-community-2026.06.0`, creds `neo4j` / `admin123`.
  No Windows service is installed, so `neo4j.bat start` fails — use
  `./bin/neo4j.bat console`.
- Backend: `SEC_NEO4J_USER=neo4j SEC_NEO4J_PASSWORD=admin123 ./gradlew.bat :backend:run`
  → :8080. Frontend: `npm start` → :4200 (proxy.conf.json already forwards `/api`).
- `TaskStop` on a backgrounded `ng serve` / `:backend:run` does not always kill the
  child process; check `netstat -ano | grep -E "4200|8080"` and `taskkill //PID <pid> //F`.

### Two traps that cost time

1. **`angular.json` changes need a dev-server restart.** It is build configuration, not
   watched source. A running `ng serve` silently keeps the old Sass load path, and the
   symptom is *all component CSS silently missing* with no error anywhere — it looks
   exactly like a broken refactor.
2. **The `Write` tool mangles raw Unicode combining characters.** The accent-stripping
   regex in `modules.ts` must read `/[\u0300-\u036f]/g` (escaped). Writing the literal
   characters corrupts them. Fix via a `cat <<'EOF'` heredoc + `sed`, and verify with
   `grep -n "replace(" modules.ts`.

## Known gaps — read before picking up work

- **`ModulesFeatureTest` has never been executed.** It is Testcontainers-based and there
  is no Docker here. It compiles, and its scenarios were reproduced by hand, but it is
  unproven. Run it wherever Docker exists before trusting it.
- **`npm run lint` and `npm test` do not work.** No ESLint config exists (`ng lint` has
  no target); Vitest needs `jsdom` or `happy-dom` installed. So `CLAUDE.md` §11's
  "before saying you are done" gate is currently unenforceable on the frontend, and
  none of the frontend work above has an automated guard.
- **No `:__Meta` schema migration exists in the backend.** `CLAUDE.md` §7 requires the
  backend to own the `:__Meta` schema — the `:__Meta(__metaId)` uniqueness constraint and
  the `meta_policy_attribute` index (`attribute-policy-checks.md` §3) are **not created
  anywhere**. Nothing has enforced meta uniqueness so far.
- **`GET /api/v1/config/navigation` is still a TODO** in `Routes.kt` and 404s on every
  page load. The sidenav's hardcoded fallback masks it, so the app looks fine — this is
  the one recurring console error and it is expected, not a regression.
- **The Material icon *font* is still not self-hosted** (`CLAUDE.md` §8 forbids the CDN).
  Only `gearbox` and `account-circle` exist as SVGs; any new `<mat-icon>ligature</mat-icon>`
  will render as raw text. Add an SVG to `public/icons/` instead.
- **Inter is not shipped either.** `styles.scss` has the `@font-face` contract but
  `public/fonts/` holds only `.gitkeep`, so the app renders in the fallback sans-serif.
- **The real imported SRD module has no user attributes.** All 984 objects carry only
  `id`, `objectNumber`, `objectLevel` plus `__`-prefixed keys — the sanitised-export case
  in `CLAUDE.md` §10. The dialog's Object attributes tab therefore shows "No requirement
  attributes", which is correct. **The mandatory-attribute flow cannot be exercised
  against this data**; it was only proven against a seeded fixture. An unsanitised export
  is needed for realistic testing.
- `docs/features/attribute-policy-checks.md` is **not implemented at all** —
  `GET /modules/{ref}/checks/attribute-policy` does not exist. It is the natural next
  feature and its spec is complete.

## Suggested next steps

1. **Commit this.** It is a large, coherent, verified change sitting entirely untracked.
2. Make the frontend quality gate real: ESLint config + `happy-dom`, then a test for the
   modules list filter and the "no `__` in the DOM" assertion (acceptance criterion 11).
3. Add the backend `:__Meta` schema migration (constraint + `meta_policy_attribute`).
4. Implement `GET /api/v1/config/navigation` — it is small and removes the standing 404.
5. Then either the attribute-policy checks feature, or self-host Inter + the icon font.

No architectural decisions are pending. The one decision taken this session — the
frontend component standard — is already in `CLAUDE.md` §6 rather than here; if anything
in it proves wrong, change it there, not in this file.
