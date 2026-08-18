# ADR 0004: The frontend quality gate, and enforcing R5 in a lint rule

Status: accepted
Date: 2026-08-05

## Context

CLAUDE.md §11 makes `npm run lint` and `npm test` preconditions for calling work done, and
neither could run: `angular.json` had no `lint` target at all, and the `test` target was
configured for `@angular/build:unit-test` with the Vitest runner but no DOM package, so `ng test`
exited with *"A DOM environment is required… install jsdom or happy-dom"*. There were also no spec
files, so even a working runner would have proved nothing.

Separately, §11 has carried a standing note since the project started: *"A lint rule catching `__`
inside `.html` templates is worth the ten minutes."* R5 — the internal namespace never reaches the
user — is the rule most likely to be broken by accident, because a `__` name is exactly what a
developer has in their head while wiring an endpoint. It had in fact already been broken:
`/requirements/review` shipped the string `:__Meta` in its user-visible description.

## Decision

**ESLint 10 flat config with `angular-eslint` 22**, wired as a `lint` architect target using
`@angular-eslint/builder:lint`. The config turns on the Angular idiom rules CLAUDE.md §6 already
requires — `prefer-standalone`, `prefer-signals`, `prefer-inject`, `prefer-control-flow` — so those
stop being conventions maintained by review and become build failures. `jsdom` supplies the DOM the
Vitest runner already expected.

**R5 is enforced by a local rule, `sec/no-internal-namespace`.** The hard part is not finding `__`;
it is not flagging BEM, which this codebase uses everywhere (`sec-modules__header`). The
distinguisher is what precedes the underscores: a BEM element always has a block name in front of
it, an internal name never does. So the pattern is `__` **not preceded by a word character**, which
separates the two exactly and needs no walk over class attributes.

It runs against:

- `.html` — the whole template as raw text;
- `.ts` — string and template literals only, so an inline `template:` and any user-facing string
  are checked while a comment explaining `__updatedAt` is not. Comments are where these names
  *should* appear.

Inline `@Component({ template: … })` literals are skipped in the TS pass, because
`processInlineTemplates` extracts them into a virtual `.html` file that this same rule then lints —
without the skip, every finding in an inline template is reported twice.

**Tests cover behaviour, not rendering.** Seven specs: the `EmptyState` contract, and the Modules
view's search, which encodes `requirements-modules.md` §3's "what the user sees is what gets
searched" — including the accent-insensitive case, since DOORS module names carry umlauts and a
user may be typing on a keyboard without them.

## Consequences

`npm run lint`, `npm test` and `npm run build` all pass, so §11's gate means something for the
first time. R5 is now structurally enforced rather than reviewed for, which matters more as views
multiply — the rule catches the exact class of bug that had already shipped once.

The rule is a regex over text rather than an AST walk, which is why it is ten minutes rather than a
day. The known limit is that it cannot see a `__` name assembled at runtime from parts, and it will
flag a legitimate `__`-leading string if one is ever genuinely needed in a literal — the ad-hoc
Cypher console (R5's one deliberate exception) will need a disable comment when it lands. That is
the right trade: the exception is a single documented view, and the default is safe.

**The backend still has no static analysis.** ktlint or detekt was flagged in a 2026 backend review
§5 as needing its own decision and is not taken here; `explicitApi()` carries part of the weight
already.
