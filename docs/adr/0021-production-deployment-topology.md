# 21. Production deployment topology on RHEL 9

Date: 2026-08-18

## Status

Accepted.

## Context

The application is going to production on a RHEL 9 server with no direct internet, company mirrors
for dnf and the container registry, **certificates issued by the company PKI**, and no build
toolchain — artifacts
are built elsewhere and uploaded by hand until there is a company repository to publish to.

Everything about running this system was written for two other machines: `docs/RUNNING.md` targets
the locked-down Windows workstation that talks to DOORS, and `deploy/docker-compose.dev.yml` targets
a developer laptop. Neither is a starting point for a server, and reading one as a variant of the
other is how a deployment inherits a decision that was made for a different reason.

Three constraints came out of the existing design rather than from the environment, and they narrow
the choices more than the environment does:

- **The session cookie is `Secure` unconditionally** (ADR 0017). TLS is not a hardening step to do
  later; without it nobody can hold a session at all.
- **Authorization is per-object and lives in the graph** (ADR 0016). An audit log that cannot say
  who called is a real gap, not a cosmetic one.
- **The backend already serves the UI** from inside the jar, and `UiRoutes.kt` gets right the two
  rules a static file server gets wrong (`/api/**` is never answered with a page; a missing *file*
  is a 404, not `index.html`), both covered by `PackagedUiTest`.

## Decision

### One host, one hostname, TLS terminating once at nginx

nginx holds the certificate and proxies `/`, `/api/` and `/auth/` to the backend and Keycloak over
loopback. The UI, the API and Keycloak share an origin, which keeps the `SameSite=Lax` login
redirect same-site throughout.

nginx is **not** given a `root` for the Angular bundle. The jar serves it, so there is one
implementation of those two rules and one test covering them.

### Everything except nginx binds to 127.0.0.1

An actual loopback bind, not a firewall rule, so it holds even where firewalld is off. This is what
makes the next decision safe.

### `XForwardedHeaders`, behind a config gate that defaults to off

On an application whose whole authorization story is *who is asking*, an access log that cannot say
where a request came from is a real gap. That is worth one Ktor artifact
(`ktor-server-forwarded-header`, at `${ktor.version}` like every other Ktor dependency).

**The plugin alone changes nothing anybody can see, and that was discovered by testing it rather
than by reasoning about it.** `CallLogging`'s default format logs no address at all, so before this
change the log named neither the proxy nor the caller. The plugin corrects `call.request.origin`;
what makes that visible is a second half — `mdc("clientIp") { it.request.origin.remoteAddress }` on
`CallLogging`, emitted as a structured JSON field beside `callId` by `logback-production.xml`. A
field, not text in the message, because a log search can filter on the first and not the second.

`remoteAddress` rather than `remoteHost`: the latter may be a name, and a name in an audit log is a
name that needed resolving at some point.

The plugin believes `X-Forwarded-For` from whoever sent it, so it is gated on
`server.behindProxy`, **default false**. Loopback binding and this flag are one decision, and the
guide says so in those words. `AppConfigTest` pins that every spelling but `true` — including `1`,
`yes`, `on`, and a blank value — reads as off, because the risk is a new falsy spelling being read
as true rather than any particular one.

This is the only change to application code the deployment required.

### Two modes, deliberately equivalent

Containers (compose) and native (systemd), same ports, same `/etc/sec/sec.yaml`, same
`/etc/sec/sec.env`, same certificates. The choice is what operations would rather carry, and it is one variable (`sec_deployment_mode`).

**nginx runs on the host in both**, rather than as a fifth container. The certificate then lives in
exactly one place on the machine whichever mode was chosen, and `systemctl reload nginx` behaves the
way a RHEL administrator expects.

### The Keycloak admin console is restricted by source address, not blocked

Keycloak's reverse-proxy guide asks for the admin paths to be restricted. The first attempt blocked
them outright and pointed operators at an ssh tunnel — **which does not work**, and the reason is
worth recording because it is not obvious: `hostname-strict` makes Keycloak build every URL from
the configured `hostname`, so the console reached at `127.0.0.1:8180` serves its HTML and then
sends the browser to `https://<host>/auth/...`, straight into the block. The console loads and
dead-ends.

So it is an allowlist (`sec_admin_allow_cidrs`), **empty by default**, which resolves to loopback
only and therefore to nobody. The application does not need the console to run, so failing closed
here costs nothing and the correct value is a fact about the deployment's network that this
repository cannot know. `/auth/health` and `/auth/metrics` stay blocked outright — the probes that
need them use the management port, which is not proxied at all.

### PostgreSQL for Keycloak, not the dev-file store

Production Keycloak refuses the dev store, and the realm holds every group membership — the whole
authorization model. It is a fourth service and it is not negotiable.

### Secrets in a root-owned file read by the supervisor

`EnvironmentFile=/etc/sec/sec.env` (0640 root:sec) for systemd; `env_file:` for compose.

Rejected: **`.bashrc`**, which is per-person, is not read by systemd at boot, exports to every child
of an interactive shell, and drifts across a team. Rejected: **`Environment=` lines in the unit**,
because `systemctl show` prints them to any user. Rejected for now: **docker secrets and systemd
`LoadCredential=`**, which mount *files* — the application reads environment variables, so adopting
them means teaching the config loader a `_FILE` convention. That is a good next step and a separate
change; it is written down here so it is not re-derived from scratch.

Topology and image tags live in a second file (`/opt/sec/compose/.env`) because compose interpolates
them itself. Different reader, different lifetime.

### Certificates come from the company PKI, and the deployment's job is to REFUSE a wrong one

Nothing in this repository generates a certificate. A deployer requests one and receives a cert, a
key and a chain.

That moves the risk: the failures with a supplied bundle are not typos but a key from a different
CSR, a SAN that does not cover the hostname, a chain missing an intermediate, or DER where PEM was
wanted. **Every one of those looks fine on inspection and fails after deployment** — the chain case
most insidiously, since it works for any client that has the intermediate cached, which is why it
gets reported as "works on my machine".

So the certificate role validates before it installs, and fails the play rather than leaving a
half-configured nginx. `sec-check-certs.sh` runs the same checks standalone, for the conversation
with the PKI team.

On RHEL, `update-ca-trust extract` regenerates `/etc/pki/java/cacerts`, so installing the chain
system-wide covers the JVM too — which matters because the backend fetches Keycloak's discovery
document through nginx and a `PKIX path building failed` at startup means nobody can log in. A JDK
from a tarball does not use that file; the guide says so and gives the `keytool` command.

### Ansible is the deployment; the shell scripts are what it does not replace

The first cut was a set of shell scripts. They worked and were the wrong shape: imperative, so a
deployer had to know the order, notice a failure, and get file modes right by reading. Ansible
gives ordering, idempotence, `--check --diff`, and — the part that matters most for a team —
**one encrypted file for every secret** instead of one per engineer's shell profile.

What survives as a script is what Ansible is a poor fit for: `sec-check-certs.sh` (a diagnostic to
quote in a PKI ticket), `sec-preflight.sh` (looking at a server from the inside, later),
`sec-build-image.sh` (called by the compose role, and useful by hand).

**The playbook templates the same files the by-hand section tells you to copy** — one nginx vhost,
one pair of systemd units, one compose file. That is what stops the two routes drifting; there is
no second implementation of anything.

`sec_verify` runs last and checks from **off the host**: liveness, readiness, that the UI is in the
jar, that an Angular route survives a reload *and* a missing asset still 404s, that `/api/**`
answers a problem detail, that Keycloak's advertised issuer matches the backend's configured one,
and that 8080/8180/7687 are unreachable from outside. That last check is the one nobody runs by
hand and the one that catches a firewall rule added during an incident and never removed.

### The image is built from the uploaded jar

No multi-stage build. The server has no toolchain and no source tree, so a multi-stage build could
not run there — and building on a developer machine while deploying a jar from another is how a
deployment stops being reproducible.

## Consequences

- **TLS is mandatory.** There is no working http deployment, and that follows from ADR 0017 rather
  than from this decision.
- **One new runtime dependency**, `ktor-server-forwarded-header`, one new configuration key, and one
  new field in every log line (`clientIp`). CLAUDE.md §4 gains a row.
- **A fourth service to operate and back up.** Backup now means the graph *and* Keycloak's database;
  losing the second leaves every object intact and invisible.
- **The manual upload stays unprovenanced** until §13 of the guide is done. The sha256 comparison is
  a person comparing two strings, which is the weakest link in the chain and is named as such.
- **A `_FILE`-style secret convention is left on the table**, as above.
- **Neither mode is the "real" one.** Two modes mean two things to keep working; they stay
  equivalent by sharing every configuration file, so the divergence is confined to how the three
  services are started.
- **Ansible is now a deployment-time dependency**, along with three Galaxy collections that an
  air-gapped site has to mirror. The by-hand section exists partly so that is never a hard block.
- **The vault passphrase becomes the thing to protect.** It is one secret instead of many, which is
  the point, and also a single point of failure — it belongs wherever the company already keeps
  shared credentials, not in the repository.
