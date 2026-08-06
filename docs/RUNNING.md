# Running the cockpit on a locked-down Windows 11 workstation

Written for a work PC where:

- the internet is reachable **only through a proxy**;
- **pip sees a company mirror**, not pypi.org;
- **there is no Docker**;
- **Neo4j runs from the console**, not as a Windows service;
- **`JAVA_HOME` may be unset at every login**.

Everything here is driven by the scripts in `scripts\win\`. They are PowerShell 5.1 —
the shell Windows 11 gives you without installing anything — and they change nothing
outside the repository except when you explicitly ask them to (`sec-env.ps1 -Persist`).

Nothing in the running application reaches the internet. There is no CDN font, no
telemetry, no external API. The proxy matters only while *installing* dependencies.

---

## 1. One-time setup

### 1.1 Tell the scripts about this machine

```powershell
cd <repo>\scripts\win
Copy-Item sec-env.local.ps1.example sec-env.local.ps1
notepad sec-env.local.ps1
```

Fill in what applies. Every entry is optional except the Neo4j password:

| Setting | When you need it |
|---|---|
| `$SecNeo4jUser` / `$SecNeo4jPassword` | **always** — the backend refuses to start without them |
| `$SecProxy` | when the machine reaches the internet through a proxy |
| `$SecPipIndexUrl` | when pip must use the company mirror |
| `$SecNpmRegistry` | when npm must use a company registry |
| `$SecJavaHome` / `$SecNeo4jHome` | only when auto-detection picks the wrong one, or finds nothing |

`sec-env.local.ps1` is git-ignored. It holds the database password in plain text, which is
fine for a local development database and nothing else — the file's own comments say so and
give you the alternative.

### 1.2 Make `JAVA_HOME` stop disappearing

```powershell
. .\sec-env.ps1 -Persist
```

`-Persist` writes the JDK and Neo4j paths into your Windows **user** environment, so every
future terminal has them without running anything. Do it once.

The JDK it picks is not the newest one it finds — it prefers **21**, because
`backend/build.gradle.kts` pins `jvmToolchain(21)` and a machine holding only 25 fails the
build asking for a JDK that is installed three directories away. An explicit `$SecJavaHome`
always wins over the search.

### 1.3 Check the machine before installing anything

```powershell
. .\sec-env.ps1
.\sec-doctor.ps1
```

One line per prerequisite, and each failure says what to run. It reads state and opens two
TCP connections; it changes nothing. Docker is deliberately not among the checks — see §5.

### 1.4 Install the dependencies

Three toolchains, three package sources, and each is the step most likely to fail behind a
proxy. Run them in any order.

```powershell
.\sec-frontend.ps1 -Install     # npm ci
.\sec-importers-setup.ps1       # python venv + the importer package
.\sec-backend.ps1 -Check        # first run downloads the Gradle dependencies
```

`sec-env.ps1` has already translated `$SecProxy` into what each of the three expects:
`GRADLE_OPTS` system properties for Gradle, `http_proxy`/`https_proxy` for npm and pip. You
do not configure the proxy three times.

**If Maven Central itself is blocked** and your company mirrors it, add the mirror to
`build.gradle.kts`:

```kotlin
allprojects {
    repositories {
        maven { url = uri("https://artifactory.company.corp/api/maven/maven-remote/") }
        mavenCentral()   // keep as a fallback for a machine that has direct access
    }
}
```

That is a committed change affecting everyone, so make it deliberately.

---

## 2. Every day

Three windows, started in this order. Each one blocks — that is the point; the window *is*
the process.

```powershell
# window 1 - the database. This window IS Neo4j. Ctrl+C stops it.
. <repo>\scripts\win\sec-env.ps1 -Quiet
<repo>\scripts\win\sec-neo4j.ps1

# window 2 - the API on :8080
. <repo>\scripts\win\sec-env.ps1 -Quiet
<repo>\scripts\win\sec-backend.ps1

# window 3 - the UI on :4200
. <repo>\scripts\win\sec-env.ps1 -Quiet
<repo>\scripts\win\sec-frontend.ps1
```

Then open <http://localhost:4200>.

Two things that will otherwise cost you an afternoon:

- **The backend serves the code it started with.** There is no reload. Restart window 2
  after every backend change.
- **`sec-neo4j.ps1` refuses to start a second instance** when 7687 is already listening. A
  duplicate start otherwise fails on a port bind several screens into the log, which reads
  as a broken installation rather than as "it is already running".

Before calling any work done:

```powershell
.\sec-backend.ps1 -Check      # ./gradlew check
.\sec-frontend.ps1 -Gate      # npm run lint && npm test && npm run build
```

---

## 3. The DOORS importer

The importer you brought across lives at `importers\src\sec_import\doors\` and is the one
that produced the graph the application currently reads — SRD and Segment are both in there
and both render in the Modules view.

### 3.1 Running it

```powershell
# Prove the environment works. Parses a bundled 6-object fixture, writes nothing.
.\sec-import-doors.ps1 -Smoke

# The importer's own tests - 49 of them.
.\sec-import-doors.ps1 -Test

# Parse and derive a real export without touching the database.
.\sec-import-doors.ps1 import C:\exports\SRD_000969a2_current.json --dry-run

# The real thing, with a JSON report written next to it.
.\sec-import-doors.ps1 import C:\exports\SRD_000969a2_current.json --report C:\exports\srd-run.json

# Schema only, and the post-import validation queries on their own.
.\sec-import-doors.ps1 init-schema
.\sec-import-doors.ps1 validate
```

Anything that is not `-Smoke` or `-Test` is handed to the importer's own CLI untouched, so
every flag it grows works here without this script changing.

Credentials come from `NEO4J_URI` / `NEO4J_USER` / `NEO4J_PASSWORD`, which `sec-env.ps1`
sets from the same values the backend uses. Without them the CLI prompts for a password
rather than failing.

`importers\win\run_doors_import.bat` does the same job for a caller that is not in
PowerShell — a scheduled task, or a DXL script shelling out after an export.

**The acceptance test is a second run.** Import the same file twice: the second run must
create zero nodes and zero relationships. That is what makes re-importing a module safe,
and it is the property everything else in the graph depends on.

`--dry-run` reports the parse and the derivation, and its `__child` / `refersTo` counters
read 0 — those are incremented on write. A dry run tells you the file is sound and the
labels are right; it does not tell you how many links you are about to create.

### 3.2 What had to change to make it run here

It arrived from a standalone project called `doors_importer` and needed five things. All
five are done; this is the record, not a to-do list.

| Change | Why |
|---|---|
| `importers\src\sec_import\doors\tests\*` now import `sec_import.doors.*` | They still imported `doors_importer.*`, so **every test errored on collection**. A UTF-8 BOM on both files went with it. |
| `pyproject.toml` `testpaths` gained `src/sec_import/doors/tests` | The tests sit next to the code, and `testpaths = ["tests"]` never looked there. A suite you must remember a path to is a suite that stops running. |
| `neo4j>=5.24,<6` relaxed to `neo4j>=5.24` | The upper bound is not real — the importer uses `GraphDatabase.driver`, `session`, `execute_write` and `ManagedTransaction`, none of which changed in 6.0. It is verified running on 6.2. Leaving the cap in means a mirror carrying only the 6.x line cannot resolve the package at all. |
| `schema.py` gained the `doors_requirement_module` index | Required by `CLAUDE.md` §7. Label-property indexes are per label: the planner will not use `doors_object_module` for `MATCH (r:DOORSRequirement {__moduleUrl: $u})`, because it does not know every `DOORSRequirement` is also a `DOORSObject`, and that query degrades to scanning every requirement in the database. It belongs in the importer's schema phase so it exists even if the backend has never started. |
| All three `.bat` wrappers resolve paths from `%~dp0` and prefer `.venv` | They ran `python -m sec_import.doors.cli` and only worked from one directory, with the package installed. Now they work from anywhere, installed or not. |

Added, not changed: `importers\tests\fixtures\smoke_module_current.json` — six objects
covering a heading, two requirements, a table with a row and a cell, a same-module link, a
cross-module link and a dangling one. It exists so `-Smoke` can prove Python, the package
layout and the parser without a real export on the machine.

### 3.3 One divergence left alone

`CLAUDE.md` §10 says the batched graph writer lives in `importers/src/sec_import/core/` and
that per-source packages hold only parsing and mapping. Your `doors` package brings its own
`importer.py`, `schema.py` and `validator.py` and does not use `core/`.

That is a real divergence and it is **deliberately not fixed here** — it is working, tested
code that produced the graph in use, and rewriting it to sit on `core/` is a refactor with
its own risk, not part of making it run. It matters when the Windchill or Cameo importers
stop being stubs, because that is when the second copy of the writer starts drifting from
the first. Worth an ADR at that point.

---

## 4. If pip cannot install the package

Mirrors that curate rather than proxy sometimes refuse `setuptools`, and then the editable
install fails before it starts. You are not blocked.

The importer needs exactly **one** third-party package — the Neo4j driver:

```powershell
python -m pip install --index-url <mirror> neo4j
```

`sec-import-doors.ps1` and all three `.bat` wrappers put `importers\src` on `PYTHONPATH`
themselves, so the importer runs straight from source with nothing installed. The only thing
you lose is `pytest`, and with it `-Test`.

`sec-importers-setup.ps1 -NoDev` is the middle option: the package installed, the test tools
skipped.

---

## 5. What does not work on this machine, and why that is fine

| | |
|---|---|
| `.\gradlew :backend:integrationTest` | Testcontainers, so it needs Docker. **Excluded from `check` by design** (`backend/build.gradle.kts` says why): not every machine that builds this has Docker, and a gate that cannot pass locally is a gate that gets skipped, taking the tests that *could* have run with it. `check` is complete and honest without it. Run it on a machine that has Docker, or in CI, before merging anything that touches a graph query. |
| `deploy\docker-compose.dev.yml` | The container path to a dev Neo4j. Irrelevant here — you have a real one. Kept for the RHEL deployment target. |
| `.run\Neo4j (docker compose).run.xml` | The IntelliJ run configuration for the above. Use `sec-neo4j.ps1` instead. |

---

## 6. When something is wrong

| Symptom | Cause |
|---|---|
| `JAVA_HOME is not set` | You did not dot-source `sec-env.ps1` **with the leading dot**. `.\sec-env.ps1` runs it in a child scope and every variable it sets dies with it. |
| Backend exits at startup complaining about config | `SEC_NEO4J_USER` / `SEC_NEO4J_PASSWORD` are unset. `application.yaml` resolves them from the environment and fails loudly rather than starting with a default — that is intentional. |
| Every view is empty, console shows failed requests | The backend is not running, or is not on 8080. The dev server proxies `/api` there. |
| `GET /api/v1/config/navigation` 404s in the console | **Expected.** The endpoint is not built yet; the sidenav has a hardcoded fallback. It is the one standing console error. |
| Gradle hangs resolving dependencies | The proxy is not reaching Gradle. Check `$env:GRADLE_OPTS` is populated after dot-sourcing. `-Offline` builds from the cache once it is warm. |
| Umlauts corrupted in imported attribute names | Something ran Python without UTF-8. The wrappers set `PYTHONUTF8` and `PYTHONIOENCODING`; a bare `python -m sec_import...` does not. |
| Neo4j will not start, complains about the JDK | Neo4j 2026.x needs JDK 21+. `sec-neo4j.ps1` passes `JAVA_HOME` through, so this means `JAVA_HOME` points at something older. |
| `pytest` collects nothing | You installed with `-NoDev`. |
| A successful import reports `NativeCommandError` | You piped it through `2>&1`. The importer logs to stderr, and Windows PowerShell 5.1 wraps every native stderr line in an ErrorRecord — turning a clean run into a failure. Redirect to a file with `--report` instead, or do not redirect. |
