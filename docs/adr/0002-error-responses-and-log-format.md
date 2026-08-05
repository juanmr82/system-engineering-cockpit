# ADR 0002: Error responses and log format

Status: accepted
Date: 2026-08-05

## Context

`docs/BACKEND_REVIEW.md` §3.1 found `StatusPages` installed but empty: a malformed `:ref` returned
`500` with the JDK's `Illegal base64 character 21`, a malformed body returned the fully-qualified
name of an internal DTO, and an unmatched path returned `404` with no body at all. Two of those
leak internals, and the first reports a client error as a server error.

Fixing it raised two questions whose obvious answers are both wrong, which is why they are recorded
here rather than left to be rediscovered.

**Catching 404s.** The documented Ktor idiom is `install(StatusPages) { status(NotFound) { … } }`.
But a StatusPages *status* handler fires for every response carrying that status — including the
`404` a route deliberately wrote — and replaces its body. The Modules feature already responds
`404` with "Module not found. No module for this reference."; a status handler would silently
flatten that into a generic sentence, and nothing in the type system would notice.

**JSON logging.** `CLAUDE.md` §5 asks for a JSON encoder in production and a readable one in
development. Selecting between them inside one `logback.xml` needs `<if>`, which needs Janino — a
dependency, and therefore a §11 approval, for a conditional.

## Decision

**Unmatched paths are handled by a tail-card fallback route, not a status handler.**
`Route.notFoundFallback()` registers `route("{...}") { handle { … } }` last in `configureRouting`.
Ktor scores constant and parameter segments above a tail card, so every real route still wins —
including a method mismatch on a real path, which correctly stays `405`. No `status()` handler is
registered for any code. Exception handlers stay, because an exception means nothing was sent yet.

**Production logging is a second file, `logback-production.xml`,** selected at launch with
`-Dlogback.configurationFile=logback-production.xml`. It uses `ch.qos.logback.classic.encoder.JsonEncoder`,
which ships inside logback-classic itself, so no dependency is added.

Supporting both: `Ref.decodeOrNull` makes decoding total, every problem detail carries the `CallId`
in `instance`, and exception causes go to the log only.

## Consequences

Route-specific `404`s keep their wording, and the generic body is reachable only where nothing
matched — which is what a client can actually act on. The cost is that the fallback is a route and
must stay registered last for readability, and that anyone reaching for `status(NotFound)` later
will re-break the specific messages; `ApplicationTest."a route's own 404 keeps its specific message"`
is the regression test that catches it.

The logging split means the production format is not exercised by simply running the app locally —
a bad `logback-production.xml` is found at deploy time, not at `./gradlew run`. Accepted: the
alternative was a dependency for a conditional. If Janino arrives later for another reason, the two
files can collapse into one without changing any code.

`instance` on the problem detail was already modelled by the frontend
(`frontend/src/app/core/error/problem-details.ts`), so nothing on that side changed.
