# Running the cockpit on a locked-down Windows 11 workstation

Written for a work PC where:

- **you are not an administrator**, and no UAC prompt you raise will ever be approved;
- the internet is reachable **only through a proxy**;
- **pip sees a company mirror**, not pypi.org;
- **there is no Docker**;
- **Neo4j runs from the console**, not as a Windows service;
- **`JAVA_HOME` may be unset at every login**.

Everything here is driven by the scripts in `scripts\win\`. They are PowerShell 5.1 —
the shell Windows 11 gives you without installing anything — and they change nothing
outside the repository except when you explicitly ask them to (`sec-env.ps1 -Persist`),
and even then only inside **your** user environment.

Nothing in the running application reaches the internet. There is no CDN font, no
telemetry, no external API. The proxy matters only while *installing* dependencies.

---

## 1. Without administrator rights

This is the constraint that shapes everything below, so read it before installing anything.

**The good news is that nothing this project does at run time needs elevation.** Every
process runs as you, in your own profile, on high-numbered ports. What administrator rights
buy you is the *installers* — and each of the four tools has a route that skips them.

### 1.1 First: let PowerShell run the scripts

Windows 11 ships client machines with the execution policy `Restricted`, which refuses every
`.ps1` file including the ones in this repository. The fix is one line and **needs no
administrator rights**, because it writes to your own registry hive rather than the machine's:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
Get-ExecutionPolicy -List      # CurrentUser should now read RemoteSigned
```

`RemoteSigned` runs local files freely and demands a signature only on files carrying the
mark of the web. A repository you cloned with `git` is local; a `scripts\win` folder someone
mailed you as a zip is not, and each file in it needs `Unblock-File` once:

```powershell
Get-ChildItem <repo>\scripts\win\*.ps1 | Unblock-File
```

If even `CurrentUser` is locked by group policy — `Get-ExecutionPolicy -List` shows a value
under `MachinePolicy` or `UserPolicy` — you cannot override it, and the way through is to
launch each script explicitly:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File <repo>\scripts\win\sec-doctor.ps1
```

That works for every script here **except `sec-env.ps1`**, which must be dot-sourced into the
session it is configuring. For that one, start the shell with the policy relaxed and work
inside it:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass
. <repo>\scripts\win\sec-env.ps1
```

### 1.2 Where the four tools have to live

The rule is the same for all of them: **anywhere you can write.** None of these needs to be
in `C:\Program Files`, and none needs a `PATH` entry set machine-wide.

| Tool | The no-admin route | Where it lands |
|---|---|---|
| **JDK 21** | The Adoptium **`.zip`**, not the `.msi`. Or, if you have IntelliJ, let it download one: *Project Structure → SDKs → Add → Download JDK*. | `%LOCALAPPDATA%\Programs\…`, or `%USERPROFILE%\.jdks\…` for IntelliJ's |
| **Neo4j Community** | The **Windows `.zip`** from the Neo4j download centre. Unzip it; there is no installer to run. | `%USERPROFILE%\neo4j\neo4j-community-2026.x.y` |
| **Node 22+** | The **`.zip`** build from nodejs.org, not the `.msi`. Add its directory to your *user* `PATH` (`setx PATH …`, or the "Edit environment variables for your account" dialog — the top half of it, never the bottom). | anywhere; `%LOCALAPPDATA%\Programs\nodejs` is tidy |
| **Python 3.11+** | The python.org installer with **"Install for all users" left unchecked** — that is its default, and it is a per-user install that raises no UAC prompt. | `%LOCALAPPDATA%\Programs\Python\Python3xx` |

`sec-env.ps1` searches all of these locations, so a JDK unzipped into any of them is found
without configuration. It looks under `%ProgramFiles%` **and** under `%LOCALAPPDATA%\Programs`,
`%USERPROFILE%\.jdks`, `%USERPROFILE%\scoop\apps`, `%USERPROFILE%\tools` and the JetBrains
Toolbox directory; for Neo4j it prefers the user-profile roots over `%ProgramFiles%`. When it
still picks the wrong one, `$SecJavaHome` / `$SecNeo4jHome` in `sec-env.local.ps1` end the
argument — an explicit choice is never second-guessed.

Two details worth knowing rather than discovering:

- **Unzip Neo4j into your profile even if you *could* write to `C:\Program Files`.** Neo4j
  writes to `data\`, `logs\`, `run\` and `conf\` under its own install directory every time it
  starts. Under `%ProgramFiles%` those writes need elevation and the database will not start.
- **Prefer a real JDK 21 over a bundled runtime.** `backend/build.gradle.kts` pins
  `jvmToolchain(21)`, so Gradle needs a 21 installed whatever it is itself running on. If it
  cannot find one it tries to *download* one, which is one more thing for the proxy to break.
  `sec-env.ps1` already prefers 21 over anything newer for exactly this reason.

### 1.3 What you genuinely cannot do, and why none of it matters here

| Needs administrator rights | Why you do not need it |
|---|---|
| Installing Neo4j as a Windows **service** | `sec-neo4j.ps1` runs it in the console. The window is the database. |
| **Docker Desktop** (installer, Hyper-V, WSL2) | Only the Testcontainers tests want it, and they are excluded from `check` by design — §6. |
| Binding a port below 1024 | Nothing here does. Bolt 7687, HTTP 7474, API 8080, dev server 4200 — all unprivileged. |
| Approving a **Windows Firewall** prompt | Neo4j's shipped `conf\neo4j.conf` leaves `server.default_listen_address` commented out, so it binds to `localhost` and no prompt appears. Do not uncomment it: exposing the database to the network is the one change here that would raise a prompt you cannot answer. |
| Adding a certificate to the **LocalMachine** store | Your company's CA is already there via policy. What is missing is the *toolchains'* private trust stores — see §2.5, all of which are fixed with user-scoped settings. |
| Enabling **long path** support (`HKLM`) | Keep the clone somewhere short — `C:\src\sec`, not a deep OneDrive path — and `git config --global core.longpaths true`. `node_modules` is what pushes against `MAX_PATH`. |
| Writing to `C:\Program Files` | Nothing this project produces goes there. Builds write to `build\`, `node_modules\`, `.venv\` and `~\.gradle`, all inside your profile. |
| Machine-wide environment variables | `sec-env.ps1 -Persist` writes to the **user** environment (`[Environment]::SetEnvironmentVariable(…, 'User')`), which is why it works. |

---

## 2. One-time setup

### 2.1 Tell the scripts about this machine

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

**Setting the database password in the first place** is also a no-admin operation, as long as
Neo4j lives in your profile (§1.2). With the database stopped:

```powershell
& "$env:NEO4J_HOME\bin\neo4j-admin.bat" dbms set-initial-password <password>
```

That works only before the first start. Afterwards, change it in the browser at
<http://localhost:7474>, or delete `data\dbms\auth` and set it again.

### 2.2 Make `JAVA_HOME` stop disappearing

```powershell
. .\sec-env.ps1 -Persist
```

`-Persist` writes the JDK and Neo4j paths into your Windows **user** environment, so every
future terminal has them without running anything. Do it once. It raises no UAC prompt — the
user environment is yours, and this is the only thing in the runbook that writes outside the
repository at all.

The JDK it picks is not the newest one it finds — it prefers **21**, because
`backend/build.gradle.kts` pins `jvmToolchain(21)` and a machine holding only 25 fails the
build asking for a JDK that is installed three directories away. An explicit `$SecJavaHome`
always wins over the search.

### 2.3 Check the machine before installing anything

```powershell
. .\sec-env.ps1
.\sec-doctor.ps1
```

One line per prerequisite, and each failure says what to run. It reads state and opens two
TCP connections; it changes nothing. Docker is deliberately not among the checks — see §6.

A `[FAIL]` against JDK, Node or Python is the point at which §1.2 applies: it means the tool
is missing, not that you need somebody with rights to install it.

### 2.4 Install the dependencies

Three toolchains, three package sources, and each is the step most likely to fail behind a
proxy. Run them in any order. All three write only inside your profile.

```powershell
.\sec-frontend.ps1 -Install     # npm ci        -> frontend\node_modules
.\sec-importers-setup.ps1       # python venv   -> importers\.venv
.\sec-backend.ps1 -Check        # gradle        -> ~\.gradle
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

### 2.5 If the proxy inspects TLS

A proxy that terminates TLS presents its own certificate, and every toolchain here carries its
own trust store that has never heard of it. Windows trusting the CA is not enough — that is
what makes this look like an administrator problem when it is not. Each fix is user-scoped:

| Toolchain | Setting | Where to put it |
|---|---|---|
| Node / npm | `NODE_EXTRA_CA_CERTS=C:\Users\<you>\certs\corp-ca.pem` | `sec-env.local.ps1`, as `$env:NODE_EXTRA_CA_CERTS = …` |
| pip | `$env:PIP_CERT = 'C:\Users\<you>\certs\corp-ca.pem'` | same file |
| Gradle / the JVM | add the CA to a **copy** of the JDK's `cacerts`, then point at it | see below |

Export the CA from your browser, or from the Windows store:
`Get-ChildItem Cert:\LocalMachine\Root | Where-Object Subject -match '<company>'`, then
`Export-Certificate`.

The JVM is the awkward one, because its trust store is a file inside the JDK. **A JDK unzipped
under your profile is writable**, which is a second reason to prefer §1.2's route over a
`%ProgramFiles%` install:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -importcert -trustcacerts `
    -keystore "$env:JAVA_HOME\lib\security\cacerts" -storepass changeit `
    -alias corp-ca -file C:\Users\<you>\certs\corp-ca.pem
```

If the JDK is somewhere you cannot write, copy `cacerts` into your profile, import there, and
add `-Djavax.net.ssl.trustStore=<path>` to `$env:GRADLE_OPTS` in `sec-env.local.ps1`.

pip's blunter escape hatch is `$SecPipTrustedHost`, which skips verification for the mirror
host. It gets you unblocked; it is not the fix.

---

## 3. Every day

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

Three things that will otherwise cost you an afternoon:

- **The backend serves the code it started with.** There is no reload. Restart window 2
  after every backend change.
- **`sec-neo4j.ps1` refuses to start a second instance** when 7687 is already listening. A
  duplicate start otherwise fails on a port bind several screens into the log, which reads
  as a broken installation rather than as "it is already running".
- **Do not open these windows as administrator**, even if you can. Gradle and npm would write
  their caches as a different user, and the next ordinary session would fail on files it does
  not own. Everything here is built to run as you.

Before calling any work done:

```powershell
.\sec-backend.ps1 -Check      # ./gradlew check
.\sec-frontend.ps1 -Gate      # npm run lint && npm test && npm run build
```

---

## 4. The DOORS importer

The importer you brought across lives at `importers\src\sec_import\doors\` and is the one
that produced the graph the application currently reads — SRD and Segment are both in there
and both render in the Modules view.

### 4.1 Running it

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
PowerShell — a scheduled task, or a DXL script shelling out after an export. A scheduled task
that runs as **you**, at logon or on a schedule, needs no rights beyond your own; one that
runs "whether the user is logged on or not" wants your password stored, which is a different
conversation.

**The acceptance test is a second run.** Import the same file twice: the second run must
create zero nodes and zero relationships. That is what makes re-importing a module safe,
and it is the property everything else in the graph depends on.

`--dry-run` reports the parse and the derivation, and its `__child` / `refersTo` counters
read 0 — those are incremented on write. A dry run tells you the file is sound and the
labels are right; it does not tell you how many links you are about to create.

### 4.2 What had to change to make it run here

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

### 4.3 One divergence left alone

`CLAUDE.md` §10 says the batched graph writer lives in `importers/src/sec_import/core/` and
that per-source packages hold only parsing and mapping. Your `doors` package brings its own
`importer.py`, `schema.py` and `validator.py` and does not use `core/`.

That is a real divergence and it is **deliberately not fixed here** — it is working, tested
code that produced the graph in use, and rewriting it to sit on `core/` is a refactor with
its own risk, not part of making it run. It matters when the Windchill or Cameo importers
stop being stubs, because that is when the second copy of the writer starts drifting from
the first. Worth an ADR at that point.

---

## 5. If pip cannot install the package

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

If you are installing outside the venv on a Python somebody else installed for all users,
`pip install` into `site-packages` will fail on permissions. Add `--user`; it writes to
`%APPDATA%\Python`. Inside `importers\.venv` the question never arises, which is why the
setup script creates one.

---

## 6. What does not work on this machine, and why that is fine

| | |
|---|---|
| `.\gradlew :backend:integrationTest` | Testcontainers, so it needs Docker — whose installer, Hyper-V and WSL2 all need administrator rights. **Excluded from `check` by design** (`backend/build.gradle.kts` says why): not every machine that builds this has Docker, and a gate that cannot pass locally is a gate that gets skipped, taking the tests that *could* have run with it. `check` is complete and honest without it. Run it on a machine that has Docker, or in CI, before merging anything that touches a graph query. |
| `deploy\docker-compose.dev.yml` | The container path to a dev Neo4j. Irrelevant here — you have a real one. Kept for the RHEL deployment target. |
| `.run\Neo4j (docker compose).run.xml` | The IntelliJ run configuration for the above. Use `sec-neo4j.ps1` instead. |
| Neo4j as a Windows service (`neo4j install-service`) | Needs administrator rights. `sec-neo4j.ps1` gives you the same database in a console window; the only thing you lose is starting at boot. |
| Reaching the app from another machine | Would mean binding to `0.0.0.0` and approving a firewall rule. Both are out of reach, and neither is wanted for a single-user development instance. |

---

## 7. When something is wrong

| Symptom | Cause |
|---|---|
| `… cannot be loaded because running scripts is disabled on this system` | The execution policy. §1.1 — one line, no administrator rights. |
| `… is not digitally signed` on a script that used to work | The file came from a zip or an email attachment and carries the mark of the web. `Unblock-File` it. |
| `JAVA_HOME is not set` | You did not dot-source `sec-env.ps1` **with the leading dot**. `.\sec-env.ps1` runs it in a child scope and every variable it sets dies with it. |
| `sec-env.ps1` reports `JAVA_HOME NOT FOUND` with a JDK installed | It is somewhere the search does not look. Set `$SecJavaHome` in `sec-env.local.ps1`; §1.2 lists the roots that are searched. |
| Backend exits at startup complaining about config | `SEC_NEO4J_USER` / `SEC_NEO4J_PASSWORD` are unset. `application.yaml` resolves them from the environment and fails loudly rather than starting with a default — that is intentional. |
| Every view is empty, console shows failed requests | The backend is not running, or is not on 8080. The dev server proxies `/api` there. |
| `GET /api/v1/config/navigation` 404s in the console | **Expected.** The endpoint is not built yet; the sidenav has a hardcoded fallback. It is the one standing console error. |
| Gradle hangs resolving dependencies | The proxy is not reaching Gradle. Check `$env:GRADLE_OPTS` is populated after dot-sourcing. `-Offline` builds from the cache once it is warm. |
| `PKIX path building failed`, `SELF_SIGNED_CERT_IN_CHAIN`, `CERTIFICATE_VERIFY_FAILED` | The proxy is inspecting TLS and that toolchain does not trust its CA. §2.5 — a Windows-level trust does not reach Java, Node or pip. |
| `Access is denied` writing to the Neo4j directory | Neo4j is unzipped somewhere you cannot write, usually `C:\Program Files`. Move it under your profile (§1.2); it needs no installation, only unzipping. |
| Umlauts corrupted in imported attribute names | Something ran Python without UTF-8. The wrappers set `PYTHONUTF8` and `PYTHONIOENCODING`; a bare `python -m sec_import...` does not. |
| Neo4j will not start, complains about the JDK | Neo4j 2026.x needs JDK 21+. `sec-neo4j.ps1` passes `JAVA_HOME` through, so this means `JAVA_HOME` points at something older. |
| `npm ci` fails with `ENAMETOOLONG` or a path error deep in `node_modules` | `MAX_PATH`. The clone is too deep — long-path support is an `HKLM` setting you cannot change. Clone to something short like `C:\src\sec`. |
| An `.exe` under your profile is blocked from running | AppLocker or the endpoint agent, not a bug here. It needs a policy exception; there is no local workaround. |
| `pytest` collects nothing | You installed with `-NoDev`. |
| A successful import reports `NativeCommandError` | You piped it through `2>&1`. The importer logs to stderr, and Windows PowerShell 5.1 wraps every native stderr line in an ErrorRecord — turning a clean run into a failure. Redirect to a file with `--report` instead, or do not redirect. |
