Refactor time! In the backend:
1. I noticed a lot of the attribute names are used as textual strings in several places. If I have to do a theoretical change of the attribute name, I need to change it in a bunch of places of the code. (change of attribute in the acutal database will be handled appart). Use the best practices for JAVA/Kotlin to make sure this theoretical change is as cheap as possible
2. If we have global constants, make sure we use the best practices in industry to declare them and use them all around the backend
3. I want to add in the future a Keycloak client, a Windchill client showing me the documents in windchill using the rest services of it, the CAMEO client will be a REST client probably too. Please create either a module
   or a submodule in the backend to put these clients later. And refactor the code for best ktor practices
4. And for 3, do you recommend using injection here? And also, in general in the backend? Kodein would be desired to use, unless you have something against this
5. At the moment we have a lot of hard coded things: Backend host & port, front end port, neo4j host and port. In the future we will also add hosts for the configuration of tje rest clients. Lets have a config file in form of JSON
   ``` 
   {
    server : {
      host: "",
      port: 0000,
      ...
    }
    neo4 : {
      host : "",
      port: "",
      user: "",
      password: ""
    },
    windchill : {
      host: "",
      port: 0000
      ...
    }
    ...
   }
   ```
6. Config files goes in ROOT of the project and when running the backend executable jar , it shall be specified with a flag (-c) the path to this file
7. Creating the executable jars to run the front and backend shall be managed from maven. So when running maven in my IDE or in console,The executable  jars are ready to be uploaded into a repository
8. The jars shall run stand alone. If there are a lot of dependencies and it is better to pack the dependencies appart, suggest the ebst approach. The idea is to ease the deployment of backend and frontend executables jars
9. The frontend running as a jar is just my idea. Choose the standard in industry and the easies to deploy
10. Backend and neo4j as the front end may run in linux too. There we may use docker for both frontends and backend (RHEL)

---

# Answers

**Items 1, 2 and 6 are done and in the tree** — item 1 twice, see below. Items 4, 5a and 5b are
decided and unimplemented.
**Items 3, 7, 8, 9 and 10 are still open**, and several of them contradict things `CLAUDE.md`
currently fixes — §4's dependency table and §5's package structure. Those want deciding before they
want coding, and whichever way they go, `CLAUDE.md` has to be updated in the same change.

---

## 1 and 2 — one declaration per name — **done**

Full reasoning in **ADR 0010**. The short version:

Before: `__id` written out 81 times, `objectNumber` 29, `Object Text` 18 across 11 files. Two names
had already drifted into double declarations — `__UNDEFINED` existed as both
`DoorsChecks.UNRESOLVED_LABEL` and a private `BreakdownProjection.UNRESOLVED_LABEL`, and the
structural-attribute exclusion list existed three times with a comment claiming they were "kept
identical on purpose", which is what you write when nothing enforces it.

After, two files split along R1's own line so a second source adds a file rather than edits one:

| File | Holds |
|---|---|
| `domain/GraphNames.kt` | `Prop`, `Rel`, `NodeLabel`, `MetaKind`, `MetaProp`, `MetaValue` — the `__` namespace, `:SEItem`, `:__UNDEFINED`, all of Tier 2. Imports nothing |
| `source/doors/DoorsNames.kt` | `DoorsAttr`, `DoorsModuleAttr`, `DoorsProp`, `DoorsRel`, `DoorsLabel` |
| `api/ApiPaths.kt` | the `/api/v1` prefix, which was written out in seven route files plus the SPA fallback's guard — and those two have to agree |

**The first pass interpolated only the DOORS attribute names into the Cypher and left ours spelled
out**, on the argument that renaming `__id` is gated on a Python change and a full re-import anyway.

**You asked again, about the `__` names specifically, and you were right.** That argument priced
the rename and ignored the price of *finding* it: `__id` appeared 58 times across eight statement
files, `__Meta` 27, `__moduleUrl` 20 — 234 occurrences. A guard test made a missed one loud; it did
nothing about the work, and it could not tell a reader whether two occurrences were one decision.

**Now nothing addresses the graph by literal.** Labels, relationship types, the `__` namespace, the
meta payload keys and the `__metaKind` values are all interpolated. What made it readable is the
option the first pass did not consider — **single-name imports**:

```kotlin
import com.sec.domain.Prop.MODULE_URL                       // -> __moduleUrl
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT

MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
```

`$MODULE_URL` rather than `${Prop.MODULE_URL}` is the whole difference, and the rejected version in
ADR 0010 was the qualified one. Each simple name is the graph name in SCREAMING_SNAKE, so it needs
no lookup; where two vocabularies collide the *import* is aliased (`DoorsAttr.ID as DOORS_ID`), never
the string. `const val` may interpolate other `const val`s, so the statements are still compile-time
constants — nothing is assembled at runtime.

**`GraphNamesTest` changed direction.** It keeps the forward check and adds the inverse one, over the
statement *source*: no graph name written out as a literal, comments stripped first. That is now the
one that matters — a hand-written `__id` compiles to the identical string, so the forward check
cannot see it, and without the inverse check the interpolation would erode one statement at a time.
It found a real one on its first run: `__Meta` in a `MetaSchema` log message.

Two things stay literal on purpose: `MetaSchema`'s constraint and index **names** (its own database
objects) and query **parameter** names (a contract between one statement and its one call site).

Full reasoning, including why the original weighting was defensible and still wrong, is in the
**amendment at the foot of ADR 0010**.

---

## 6 — the `-c` flag — **not needed; `-config=` does it, and now merges** — **done**

Ktor 3.5.1's `EngineMain` already accepts `-config=<path>`, so there is no `-c` flag, no argument
parsing and no hand-rolled loading. Everything below was verified by running the shaded jar, not
read out of documentation.

### The catch, and the fix

Stock `-config=` **replaces** the packaged `application.yaml` rather than merging with it. A
deployment file omitting the `ktor:` block dies with *"Neither port nor sslPort specified"* — which
would force every operator's file to carry `com.sec.ApplicationKt.module`, a fully-qualified Kotlin
function name that nobody deploying this should have to know and that goes stale the day the file
is renamed.

Ktor *does* merge when `-config=` is **repeated**: the paths go to `ConfigLoader.loadAll`, which
merges them key by key with the **last one winning**. So the whole fix is to put the packaged file
first, and `-config=application.yaml` resolves it from the classpath — verified not to be shadowed
by a file of that name in the working directory.

`config/ConfigArgs.kt` does that insertion. Six lines, a pure function on the argument array, so
`EngineMain` keeps ownership of every other flag it understands.

**The result — a deployment file states only what its environment changes:**

```
java -jar backend-0.1.0-all.jar -config=/etc/sec/sec.yaml
```
```yaml
# /etc/sec/sec.yaml — no ktor: block, no module names
neo4j:
  uri: "bolt://db.internal:7687"
```

Everything unstated — the port, the module list, `database`, the timeouts — comes from the packaged
file. That exact file failed to start before this change and starts now.

### The three mechanisms, all stock

| Mechanism | Behaviour | Use |
|---|---|---|
| `-config=deploy.yaml` | packaged file as base, this one overlaid on top (via `ConfigArgs`) | **the normal deployment** |
| `-config=a.yaml -config=b.yaml` | deep merge, last wins; naming `application.yaml` explicitly is honoured and not duplicated | staging layered over a shared base |
| `-P:neo4j.uri=bolt://…` | per-key override on top of whatever config loaded | containers, and secrets that must not be in a file |

`$SEC_NEO4J_USER` / `$SEC_NEO4J_PASSWORD` keep working through all three: Ktor resolves them from
the environment when the file is parsed, and **fails to load at all if they are unset** — the
fail-fast property 5a relies on, deliberately not weakened with a default.

### Pinned by tests

`ConfigArgsTest` (7 cases) covers the transform *and* the two Ktor behaviours it depends on but does
not control — that repeated paths merge deep with the last winning, and that `application.yaml`
resolves from the classpath. Those were verified once by hand; the tests are what keep them true
across a Ktor upgrade. They assert against the **real** packaged `application.yaml`, which is why
surefire now supplies placeholder `SEC_NEO4J_*` values: the file cannot be loaded without them, by
design.

---

## 5 — configuration

### 5a. The backend keeps `application.yaml` — decided

**Your call, and it drops the JSON file from item 5 for the backend.** Ktor reads
`application.yaml` natively through `ktor-server-config-yaml`, it already resolves
`$SEC_NEO4J_USER` / `$SEC_NEO4J_PASSWORD` from the environment, and `config/AppConfig.kt` already
types it. A JSON file would mean hand-rolling the loading, the environment-variable substitution and
the typing that all currently come for free.

Adding the future REST clients is then just more sections in the file — `windchill:`, `cameo:`,
`keycloak:` — read by the same typed loader.

> **This is what settled item 6**, above: `EngineMain` already accepts `-config=…`, so no flag of
> our own was needed, and `ConfigArgs` turned it into a merge — so a deployment file names hosts and
> credentials and nothing else. Adding `windchill:` / `cameo:` / `keycloak:` sections needs no new
> mechanism: they go in the packaged file with defaults and are overridden per environment.

### 5b. The frontend is told nothing — decided

**Today the frontend does not know where the backend is, and that is deliberate rather than
accidental.** Three facts about the code as it stands:

- No TypeScript file contains a host, a port or a base URL. Every call is root-relative —
  `/api/v1/modules/…`. A grep for `http://` across `frontend/src/app` returns nothing.
- Development: `frontend/proxy.conf.json` maps `/api` → `localhost:8080`, so `ng serve` on 4200
  reaches the backend on 8080.
- Deployment: `mvn -Pui package` copies the SPA into the backend jar and `api/routes/UiRoutes.kt`
  serves it. One process, one port, same origin.

So the question only becomes real when the frontend is served from a **different origin** than the
API — which is what item 10 implies. Ranked:

| Approach | When to use it | Cost |
|---|---|---|
| **Reverse proxy** — nginx or Traefik in front, `/api` routed to the backend | The Docker/RHEL case in item 10. **Recommended.** | Frontend stays zero-config, no CORS; the proxy config *is* the deployment artifact |
| **The packaged jar as it is now** | Single-host installs, and today's development loop | Already built, already tested (`PackagedUiTest`) |
| **Runtime `config.json`**, fetched at startup by an app initializer | Only if a proxy genuinely cannot be put in front | A real, if small, new mechanism |
| **Build-time `environment.ts` / `fileReplacements`** | — | **Rejected.** It contradicts items 7–8: a jar built once could not be repointed without a rebuild, so "ready to upload to a repository" stops being true |

Two consequences of going cross-origin, both easy to discover late:

- The backend then needs a **CORS configuration**. That is a security decision, not a config line.
- A runtime `config.json` must be served **next to the frontend**, never fetched from the backend —
  fetching it from the backend requires the backend's address, which is the thing being looked up.

If `config.json` is ever adopted, note in `CLAUDE.md` §2 that it is **not** a fifth state store: it
is deployment configuration, the same tier as `application.yaml`, and not application configuration.

---

## 4 — dependency injection, and Kodein

**Nothing against Kodein. Do not add it yet.** Three reasons:

1. **The object graph is not big enough.** `Application.kt` wires six collaborators in about ten
   lines and the compiler checks every one of them. A container trades those compile-time errors for
   runtime ones in exchange for indirection there is currently no use for.
2. **It would make the tests worse.** `ApplicationTest` calls `configureApp(graphDriver)` directly,
   and every feature test constructs its projections by hand. That works *because* the wiring is
   manual. A container adds a test-module setup step and buys nothing back.
3. **Ktor ships its own DI now** — `ktor-server-di`, introduced in the 3.2 line. If a container is
   wanted, reach for the framework's before a third-party one: one fewer dependency (§4) and one
   fewer set of idioms. **Verify the artifact and the API against the pinned 3.5.1 before betting on
   it** — it was experimental when introduced.

### What item 3 actually needs is structure, not injection

A home for the Keycloak / Windchill / CAMEO clients is a package-and-lifecycle question. Injection
does not answer it, and adding a container first would disguise it as answered.

**The cheap step that gets most of the benefit:** move construction and shutdown out of
`Application.kt` into one `Dependencies` (or `AppComponents`) class. `Application.kt` goes back to
being pure wiring, there is a single place to look, and if a container is adopted later it is a
mechanical change inside one file rather than a change across the codebase.

### Revisit when one of these is true

- **Conditional wiring is needed** — a Windchill client that simply is not there when
  `windchill:` is absent from the config. Item 5a makes this the likely first trigger.
- The graph passes roughly **fifteen objects**.
- An implementation has to be **swapped at runtime** rather than at compile time.
