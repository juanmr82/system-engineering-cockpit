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
| **Maven 3.9.x** | The **binary `.zip`** from maven.apache.org. Unzip it; there is nothing to install. It is 9 MB, and IntelliJ already bundles a copy. | `%USERPROFILE%\tools\apache-maven-3.9.x` |
| **Neo4j Community** | The **Windows `.zip`** from the Neo4j download centre. Unzip it; there is no installer to run. | `%USERPROFILE%\neo4j\neo4j-community-2026.x.y` |
| **Node 22+** | The **`.zip`** build from nodejs.org, not the `.msi`. Add its directory to your *user* `PATH` (`setx PATH …`, or the "Edit environment variables for your account" dialog — the top half of it, never the bottom). | anywhere; `%LOCALAPPDATA%\Programs\nodejs` is tidy |
| **Python 3.11+** | The python.org installer with **"Install for all users" left unchecked** — that is its default, and it is a per-user install that raises no UAC prompt. | `%LOCALAPPDATA%\Programs\Python\Python3xx` |

`sec-env.ps1` searches all of these locations, so a JDK or a Maven unzipped into any of them is
found without configuration. It looks under `%ProgramFiles%` **and** under `%LOCALAPPDATA%\Programs`,
`%USERPROFILE%\.jdks`, `%USERPROFILE%\scoop\apps`, `%USERPROFILE%\tools` and the JetBrains
Toolbox directory; for Neo4j it prefers the user-profile roots over `%ProgramFiles%`. When it
still picks the wrong one, `$SecJavaHome` / `$SecNeo4jHome` in `sec-env.local.ps1` end the
argument — an explicit choice is never second-guessed.

Two details worth knowing rather than discovering:

- **Unzip Neo4j into your profile even if you *could* write to `C:\Program Files`.** Neo4j
  writes to `data\`, `logs\`, `run\` and `conf\` under its own install directory every time it
  starts. Under `%ProgramFiles%` those writes need elevation and the database will not start.
- **Prefer a real JDK 21 over a bundled runtime.** Maven has no toolchain of its own here: it
  compiles with whatever JDK it is itself running on, so `JAVA_HOME` *is* the build JDK. A
  newer JDK would compile against a newer class file version than `maven.compiler.release`
  claims. `sec-env.ps1` prefers 21 over anything newer for exactly this reason.
- **Prefer a real Maven over the wrapper.** `mvnw.cmd` works, but its first act is to download
  a Maven distribution - the one step this network is most likely to stop. Unzipping Maven once
  removes that dependency permanently.

### 1.3 What you genuinely cannot do, and why none of it matters here

| Needs administrator rights | Why you do not need it |
|---|---|
| Installing Neo4j as a Windows **service** | `sec-neo4j.ps1` runs it in the console. The window is the database. |
| **Docker Desktop** (installer, Hyper-V, WSL2) | Only the Testcontainers tests want it, and they are excluded from `mvn verify` by design — §6. |
| Binding a port below 1024 | Nothing here does. Bolt 7687, HTTP 7474, API 8080, dev server 4200 — all unprivileged. |
| Approving a **Windows Firewall** prompt | Neo4j's shipped `conf\neo4j.conf` leaves `server.default_listen_address` commented out, so it binds to `localhost` and no prompt appears. Do not uncomment it: exposing the database to the network is the one change here that would raise a prompt you cannot answer. |
| Adding a certificate to the **LocalMachine** store | Your company's CA is already there via policy. What is missing is the *toolchains'* private trust stores — see §2.5, all of which are fixed with user-scoped settings. |
| Enabling **long path** support (`HKLM`) | Keep the clone somewhere short — `C:\src\sec`, not a deep OneDrive path — and `git config --global core.longpaths true`. `node_modules` is what pushes against `MAX_PATH`. |
| Writing to `C:\Program Files` | Nothing this project produces goes there. Builds write to `target\`, `node_modules\`, `.venv\` and `~\.m2`, all inside your profile. |
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
| `$SecNoProxy` | hosts that must bypass it — **every internal mirror belongs here** (§2.6) |
| `$SecProxyUser` / `$SecProxyPassword` | when the proxy demands a login (§2.6) |
| `$SecPipIndexUrl` | when pip must use the company mirror |
| `$SecNpmRegistry` | when npm must use a company registry |
| `$SecMavenHome` | only when Maven is somewhere the search does not look (§2.6) |
| `$SecMavenOpts` | extra JVM flags for Maven — a trust store, most often (§2.5) |
| `$SecMavenSettings` | a `settings.xml` kept outside `~\.m2` (§2.6) |
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

The JDK it picks is not the newest one it finds — it prefers **21**, because Maven compiles
with the JDK it runs on and the build targets 21. An explicit `$SecJavaHome` always wins over
the search.

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
.\se
c-backend.ps1 -Check        # maven         -> ~\.m2\repository
```

`sec-env.ps1` has already translated `$SecProxy` into what each of the three expects:
`MAVEN_OPTS` system properties, `http_proxy`/`https_proxy` for npm and pip. **Maven is the
exception**: its dependency resolver reads the proxy from `settings.xml`, not from the
environment, so a proxy set only here will still fail to resolve. That, the company mirror and
proxy credentials are all section 2.6.

**If Maven Central itself is blocked** and your company mirrors it, that is a `settings.xml`
`<mirror>` — section 2.6. Unlike the old Gradle arrangement it is *not* a committed change:
`settings.xml` lives in your profile, so each machine points at whatever it can reach without
anyone editing a build file.

### 2.5 If the proxy inspects TLS

A proxy that terminates TLS presents its own certificate, and every toolchain here carries its
own trust store that has never heard of it. Windows trusting the CA is not enough — that is
what makes this look like an administrator problem when it is not. Each fix is user-scoped:

| Toolchain | Setting | Where to put it |
|---|---|---|
| Node / npm | `NODE_EXTRA_CA_CERTS=C:\Users\<you>\certs\corp-ca.pem` | `sec-env.local.ps1`, as `$env:NODE_EXTRA_CA_CERTS = …` |
| pip | `$env:PIP_CERT = 'C:\Users\<you>\certs\corp-ca.pem'` | same file |
| Maven / the JVM | add the CA to a **copy** of the JDK's `cacerts`, then point at it | see below |

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
set `$SecMavenOpts` in `sec-env.local.ps1`:

```powershell
$SecMavenOpts = '-Djavax.net.ssl.trustStore=C:\Users\<you>\certs\cacerts -Djavax.net.ssl.trustStorePassword=changeit'
```

Use `$SecMavenOpts`, not `$env:MAVEN_OPTS` — `sec-env.ps1` **appends** the proxy settings to
what you put there, and assigning the environment variable yourself worked only until it did.

pip's blunter escape hatch is `$SecPipTrustedHost`, which skips verification for the mirror
host. It gets you unblocked; it is not the fix.

### 2.6 Maven: the mirror, the proxy, and getting Maven itself

Maven puts all three in one file, `settings.xml`, and that file lives in **your** profile at
`%USERPROFILE%\.m2\settings.xml`. Nothing here needs administrator rights and nothing here is
a committed change — which is the practical reason this project builds with Maven at all
(ADR 0007).

Start from the annotated template:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.m2" | Out-Null
Copy-Item <repo>\scripts\win\maven-settings.xml.example "$env:USERPROFILE\.m2\settings.xml"
notepad "$env:USERPROFILE\.m2\settings.xml"
```

#### The company mirror

A `<mirror>` redirects Maven Central to whatever your company hosts:

```xml
<mirror>
  <id>company-central</id>
  <url>https://artifactory.company.corp/artifactory/maven-remote</url>
  <mirrorOf>central</mirrorOf>
</mirror>
```

`<mirrorOf>central</mirrorOf>` redirects only Central; `*` sends everything through the mirror,
which is what a network that blocks all outbound repository traffic needs.

If the mirror wants a login, add a `<server>` whose **`<id>` matches the mirror's `<id>`**.
That pairing is the whole mechanism, and a mismatch fails as an anonymous `401` rather than as
a configuration error. `mvn --encrypt-password` keeps the password out of clear text.

#### The proxy

**This is the part that differs from every other tool here.** Maven's dependency resolver does
not reliably read the JVM proxy properties, so `$SecProxy` — which is enough for npm and pip —
does *not* get Maven through. The proxy has to be in `settings.xml`:

```xml
<proxy>
  <id>company-proxy</id>
  <active>true</active>
  <protocol>http</protocol>
  <host>proxy.company.corp</host>
  <port>8080</port>
  <nonProxyHosts>localhost|127.0.0.1|*.company.corp</nonProxyHosts>
</proxy>
```

Two traps:

- **`<nonProxyHosts>` is pipe-separated**, unlike every other list in the file.
- **An internal mirror usually needs no proxy at all.** If your Artifactory is internal, delete
  the `<proxy>` block or list its host in `<nonProxyHosts>` — sending internal traffic to a
  proxy that will not answer for it fails in a way that reads exactly like the mirror being down.

#### Getting Maven itself

`mvnw.cmd` is committed and works, but it downloads a Maven distribution on first use, which is
the one step this network is most likely to stop. It is a **9 MB** download rather than Gradle's
144 MB, and the wrapper here is the `only-script` flavour, so there is no wrapper jar for an
endpoint agent to quarantine.

Better still, avoid the download: **Maven is a zip with no installer.** Unzip it anywhere you
can write and `sec-env.ps1` finds it — it looks on `PATH`, under `%USERPROFILE%\tools`, in
scoop, and in IntelliJ's bundled copy, which is a complete Maven already sitting on the disk of
anyone who opens this project in the IDE. `$SecMavenHome` ends the argument.

To point the wrapper at a company-hosted Maven instead, edit `distributionUrl` in
`.mvn\wrapper\maven-wrapper.properties`. That one *is* a committed file.

---

## 3. Every day

```powershell
<repo>\scripts\win\sec-up.ps1
```

That is the whole thing. It dot-sources the environment, starts Neo4j, waits for it, starts the
backend, waits for `/health`, starts the dev server, waits for it, and opens the browser.

```powershell
sec-up.ps1 -Status       # what is up, what is not - changes nothing
sec-up.ps1 -Stop         # stop all three
sec-up.ps1 -Jar          # run the built artifact instead of the sources (3.3)
sec-up.ps1 -NoFrontend   # database + API only, for backend work
sec-up.ps1 -NoBrowser
```

**Each service still runs in its own window**, because each window *is* its process: the log is
in it and Ctrl+C there stops that one. What `sec-up.ps1` removes is opening three terminals,
dot-sourcing the environment three times, and knowing the order.

Two behaviours worth relying on:

- **Everything is checked before anything is launched.** A missing password or an uninstalled
  JDK is reported with nothing started. Three windows that each die instantly is the worst way
  to find out.
- **Anything already listening is left strictly alone**, and reported as such. Starting a second
  Neo4j on a bound port fails several screens into a log and reads as a broken installation.

### 3.1 Starting them by hand

Still supported, and what `sec-up.ps1` does for you. Use it when you want one service in a
terminal you already have open.

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
- **Do not open these windows as administrator**, even if you can. Maven and npm would write
  their caches as a different user, and the next ordinary session would fail on files it does
  not own. Everything here is built to run as you.

### 3.2 Running it as one jar

Development runs from source: Maven compiles the backend, `ng serve` serves the UI on :4200 with
hot reload, and a dev-server proxy forwards `/api` to :8080. Three processes, and every one of
them needs the toolchain and the sources.

Deployment does not have to look like that. `sec-package.ps1` builds **one jar containing the API
and the user interface**, and running it needs a JDK and nothing else - no Maven, no Node, no
sources, no IDE.

```powershell
scripts\win\sec-package.ps1          # ng build, then the jar around it
scripts\win\sec-up.ps1 -Jar          # Neo4j + that jar
```

Then open <http://localhost:8080> — **:8080, not :4200**. The jar serves the pages and the API on
one port, so there is no dev server and no proxy: the frontend already asks for `/api/v1/...`
with root-relative URLs, and being served from the same origin is all that needs.

What comes out is `backend\target\backend-<version>-all.jar`, about 21 MB. To deploy it, copy
that one file to a machine with a JDK 21 and a reachable Neo4j:

```powershell
$env:SEC_NEO4J_USER = 'neo4j'
$env:SEC_NEO4J_PASSWORD = '...'
java -jar backend-0.1.0-all.jar
```

Three things about this mode:

- **The UI is a build artifact now.** A change to the frontend needs `sec-package.ps1` again;
  there is no hot reload. Develop against `ng serve`, deploy the jar.
- **`sec-package.ps1` verifies the UI really is inside**, by opening the jar and looking for
  `static/index.html`. A jar missing its pages is indistinguishable from a working one until
  somebody opens a browser, and Maven's copy step only warns when the directory is absent.
- **`mvn package` on its own leaves the UI out.** Including it is the `ui` profile
  (`mvn -Pui package`), which `sec-package.ps1` passes for you. A jar whose contents depend on
  whether somebody happened to have built the UI earlier is a jar nobody can reason about.

`sec-package.ps1 -NoUi` builds the API alone, and `-SkipTests` is faster and worth less.

### 3.3 Before calling any work done

```powershell
.\sec-backend.ps1 -Check      # mvn verify
.\sec-frontend.ps1 -Gate      # npm run lint && npm test && npm run build
```

`.\sec-backend.ps1 -Docker` runs the container tests (`mvn -Pdocker test`) on a machine that
has Docker. They are excluded from `-Check` by design — §6.

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
| `mvn -Pdocker test` (`.\sec-backend.ps1 -Docker`) | Testcontainers, so it needs Docker — whose installer, Hyper-V and WSL2 all need administrator rights. **Excluded from `mvn verify` by design** (the root `pom.xml` says why): not every machine that builds this has Docker, and a gate that cannot pass locally is a gate that gets skipped, taking the tests that *could* have run with it. `verify` is complete and honest without it. Run it on a machine that has Docker, or in CI, before merging anything that touches a graph query. |
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
| `Could not resolve dependencies ... Could not transfer artifact` | Maven cannot reach a repository. Almost always the proxy, and almost always because it is set in the environment but not in `settings.xml` — §2.6. `-Offline` builds from `~\.m2\repository` once it is warm. |
| `Could not transfer artifact ... ReasonPhrase: Proxy Authentication Required (407)` | The proxy wants credentials. They belong in the `<proxy>` block's `<username>`/`<password>` in `settings.xml`, not only in `$SecProxyUser` — §2.6. |
| `401 Unauthorized` from the company mirror | The `<server>` id does not match the `<mirror>` id. Maven pairs them by id alone and sends no credentials when they differ, so it reads as an anonymous request being refused. |
| `PKIX path building failed` during dependency resolution | The proxy is inspecting TLS and the JVM does not trust its CA — §2.5. Windows trusting it is not enough. |
| The wrapper hangs or fails at `Downloading ... apache-maven-3.9.x-bin.zip` | The Maven distribution download. Unzip Maven yourself instead and set `$SecMavenHome`; it is 9 MB and needs no installer — §2.6. |
| `mvn` works but `mvnw.cmd` does not, or vice versa | They are two different Mavens. `sec-backend.ps1` prefers the installed one (`$env:SEC_MVN`) and falls back to the wrapper; `sec-doctor.ps1` says which one is in play. |
| Dependencies re-download on every build | The local repository is somewhere Maven is not looking. Check `MAVEN_REPO_LOCAL` and any `<localRepository>` in `settings.xml`; `sec-doctor.ps1` reports the path it found and how many jars are in it. |
| A dependency resolves to the wrong version | Maven takes the **nearest** declaration, where Gradle took the **highest**. A transitive dependency that used to be silently upgraded now is not — pin it in the root `pom.xml` `<dependencyManagement>`. This is not theoretical: it is what the coroutines pin in that file records. |
| `PKIX path building failed`, `SELF_SIGNED_CERT_IN_CHAIN`, `CERTIFICATE_VERIFY_FAILED` | The proxy is inspecting TLS and that toolchain does not trust its CA. §2.5 — a Windows-level trust does not reach Java, Node or pip. |
| `Access is denied` writing to the Neo4j directory | Neo4j is unzipped somewhere you cannot write, usually `C:\Program Files`. Move it under your profile (§1.2); it needs no installation, only unzipping. |
| Umlauts corrupted in imported attribute names | Something ran Python without UTF-8. The wrappers set `PYTHONUTF8` and `PYTHONIOENCODING`; a bare `python -m sec_import...` does not. |
| Neo4j will not start, complains about the JDK | Neo4j 2026.x needs JDK 21+. `sec-neo4j.ps1` passes `JAVA_HOME` through, so this means `JAVA_HOME` points at something older. |
| `npm ci` fails with `ENAMETOOLONG` or a path error deep in `node_modules` | `MAX_PATH`. The clone is too deep — long-path support is an `HKLM` setting you cannot change. Clone to something short like `C:\src\sec`. |
| An `.exe` under your profile is blocked from running | AppLocker or the endpoint agent, not a bug here. It needs a policy exception; there is no local workaround. |
| `pytest` collects nothing | You installed with `-NoDev`. |
| A successful import reports `NativeCommandError` | You piped it through `2>&1`. The importer logs to stderr, and Windows PowerShell 5.1 wraps every native stderr line in an ErrorRecord — turning a clean run into a failure. Redirect to a file with `--report` instead, or do not redirect. |

### 7.1 Repairing the local repository

Maven caches every artifact it downloads in `%USERPROFILE%\.m2\repository`. Nothing in there is
your work, so anything in it can be deleted — the cost is a re-download, never lost code.

Two failures live here.

**A failed download is remembered.** When a repository is unreachable, Maven writes a
`*.lastUpdated` marker next to the missing artifact and then *refuses to retry it* for the rest
of the day, so a build that failed while the proxy was misconfigured keeps failing after you fix
it. Clear the markers:

```powershell
Get-ChildItem "$env:USERPROFILE\.m2\repository" -Recurse -Filter "*.lastUpdated" | Remove-Item -Force
```

`-U` on a single build does the same thing (`mvn -U verify`) and is the quicker check.

**A truncated or quarantined jar** produces a checksum failure, or a `NoClassDefFoundError` for
a class that is plainly on the classpath — the same shape as the Gradle distribution problem
this section used to describe, and the same cause on this kind of machine: an endpoint agent
removing jars from a directory under your profile. Delete the offending artifact's directory and
rebuild; if it keeps happening, the durable fix is an exclusion for `%USERPROFILE%\.m2`, which
only your IT can grant — ask for that path specifically.

To rule the cache out entirely, move it aside rather than deleting it, so you can put it back:

```powershell
Rename-Item "$env:USERPROFILE\.m2\repository" repository.bak
.\sec-backend.ps1 -Check
```

**Seeding it offline.** `~\.m2\repository` is a plain directory tree and copies between
machines — the equivalent of carrying the Gradle distribution in by hand, and the answer when
the proxy cannot be made to work at all:

```powershell
robocopy "$env:USERPROFILE\.m2\repository" "E:\m2-repository" /E      # on a machine that builds
robocopy "E:\m2-repository" "$env:USERPROFILE\.m2\repository" /E      # on the stranded one
```

Then build with `--offline` (`.\sec-backend.ps1 -Check -Offline`) so Maven never reaches for the
network at all.
