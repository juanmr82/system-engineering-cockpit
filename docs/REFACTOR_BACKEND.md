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

Two and a half of the ten are settled below. **Items 1, 2, 3, 7, 8, 9 and 10 are still open**, and
several of them contradict things `CLAUDE.md` currently fixes — §4's dependency table, §5's package
structure, and `application.yaml` as *the* configuration mechanism. Those want deciding before they
want coding, and whichever way they go, `CLAUDE.md` has to be updated in the same change.

Nothing below has been implemented. This is a record of decisions, not a description of the code.

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

> **Consequence for item 6, worth checking rather than assuming.** Ktor's `EngineMain` accepts a
> config path on the command line (`-config=…`), so "point the jar at a config file at startup" may
> already exist and need no `-c` flag of our own. Verify that against the pinned Ktor 3.5.1 before
> designing anything; if it holds, item 6 is nearly free, and the only decision left is where the
> file lives beside the jar.

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
