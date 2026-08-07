# ADR 0007: Maven over Gradle for the JVM build

Status: accepted
Date: 2026-08-07

Supersedes the Gradle entry in `CLAUDE.md` §4 and the build layout in §3.

## Context

The build was a Gradle 9 multi-project reactor with a version catalogue: `settings.gradle.kts`,
`build.gradle.kts`, `backend/build.gradle.kts`, `gradle/libs.versions.toml`, and the committed
wrapper. It worked, and nothing about it was wrong as a build.

It could not be made to run on the workstation that matters. `docs/RUNNING.md` describes that
machine — no administrator rights, proxy-only internet, a company mirror instead of the public
repositories — and it is **the only machine that can talk to DOORS**, so it is not a machine we
get to declare out of scope.

Gradle failed there three times over, and each failure was a different shape of the same
problem: **Gradle has to download Gradle before it can build anything.**

| What happened | Why |
|---|---|
| `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | The committed 45 KB `gradle-wrapper.jar` went missing from a clone. A jar sitting in a user profile is a standard endpoint-security heuristic, and the quarantine is silent. |
| `NoClassDefFoundError: org/gradle/internal/classloader/ClassLoaderFactory` | The unpacked 144 MB distribution had jars removed from it after unpacking. The wrapper never re-fetches, because the `.ok` marker beside it records that the download succeeded. |
| The distribution could not be downloaded at all | `services.gradle.org` unreachable through the proxy. |

The third is the one that decided it. The distribution download happens *before* Gradle exists,
so it is unaffected by any Maven-mirror or repository configuration, and the URL it uses lives
in `gradle-wrapper.properties` — read from that file only, with no environment variable or
system property override. Pointing it at a company-hosted distribution is therefore a
**committed change to a shared file**, which every developer on a different network then has to
work around.

Maven is not immune to a blocked network, and moving does not conjure connectivity. What it
changes is where the configuration lives and how much has to be downloaded before the build can
start.

## Decision

**Build the JVM side with Maven 3.9.x.** One aggregator `pom.xml` at the root holding every
version and all plugin configuration, one `backend/pom.xml` declaring dependencies without
versions. The Gradle files are deleted; there is exactly one build system.

Specifics worth recording, because each one replaced something Gradle did implicitly:

- **`gradle/libs.versions.toml` → the root pom's `<properties>` + `<dependencyManagement>`.**
  Same guarantee: a module cannot name a version, so no two modules can drift.
- **`explicitApi()` → `-Xexplicit-api=strict`, on the `compile` execution only.** Gradle applied
  it to production source sets alone. Configured on the plugin instead of the execution it would
  demand an explicit visibility modifier and declared return type on every test function too.
- **`tasks.register<Test>("integrationTest")` → the `docker` profile.** Surefire carries
  `<excludedGroups>docker</excludedGroups>` by default; the profile inverts it to
  `<groups>docker</groups>`. Same classes, opposite filter, and `mvn verify` still passes on a
  machine with no Docker — the property `CLAUDE.md` §11 requires.
- **The Ktor Gradle plugin's fat jar → `maven-shade-plugin`** with `ServicesResourceTransformer`.
  Not optional: Ktor discovers plugins through `META-INF/services`, and without the transformer
  the shaded jar keeps only the last copy of each service file.
- **The wrapper is committed as `distributionType=only-script`** — `mvnw`, `mvnw.cmd` and a
  properties file, and **no jar at all**. The first failure in the table above cannot recur,
  because there is no wrapper jar to quarantine.

**Multiplatform artifacts must use the `-jvm` suffix.** Ktor and kotlinx publish Gradle module
metadata that Maven does not read; `io.ktor:ktor-server-core` resolves to a metadata jar with no
classes in it, while `ktor-server-core-jvm` is the real one. The failure is a compile error
about a missing package, not a resolution error.

## Consequences

**Easier.** The bootstrap is a 9 MB Maven zip instead of a 144 MB Gradle distribution, and Maven
is a zip with no installer — so on a machine with no administrator rights it is unzipped once
and never downloaded again. IntelliJ bundles a complete Maven, so many machines already have
one. Above all, the mirror, the proxy and any repository credentials all live in
`%USERPROFILE%\.m2\settings.xml`: user-scoped, no elevation, and **not a committed file**, so
each machine points at whatever it can reach without anyone editing a build file. That is the
concrete thing Gradle could not offer, since its distribution URL had to be committed.

**Harder.** Maven is more verbose and its lifecycle is less flexible; anything genuinely
task-shaped now wants a plugin rather than ten lines of Kotlin. Incremental builds are weaker —
there is no build cache and no daemon, so `mvn verify` re-runs work Gradle would have skipped.
For a 44-file module that is seconds, and it is the honest cost.

**The trap this exposed, and it will happen again.** Gradle resolves a version conflict by
taking the **highest** version; Maven takes the **nearest** declaration. The catalogue pinned
`kotlinx-coroutines` at 1.10.1 while Ktor 3.5.1 required 1.11.0, so Gradle had been silently
upgrading it and the build was running 1.11.0 all along. Under Maven the 1.10.1 pin stuck and
six tests failed with a `NoSuchMethodError`. The pin is now 1.11.0 — what was actually in use —
and the root pom says why. **Any pinned version may have been a lie under Gradle**; a
`NoSuchMethodError` or `NoClassDefFoundError` after this migration should be read as another
instance of it before anything else is suspected.

**Not changed.** The frontend build is untouched — it was never driven by Gradle and is not
driven by Maven. `npm run lint && npm test && npm run build` from `frontend/` remains the
frontend gate, and the JVM gate is now `mvn verify` where it was `./gradlew check`.
