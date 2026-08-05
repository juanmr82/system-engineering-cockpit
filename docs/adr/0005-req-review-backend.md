# ADR 0005: The Req review backend — settings intent, a second Shape-B kind, and comment scope

Status: accepted
Date: 2026-08-05

## Context

`docs/REQ_REVIEW.md` specifies the second dynamic-content view. Its backend reuses most of the
existing surface but adds a batch comment save, a paged object listing, and a settings dialog that
writes three per-attribute flags. Four decisions in that work are not obvious from the spec.

## Decision

### 1. A settings request distinguishes "absent" from "explicitly cleared"

`POST /modules/{ref}/settings` is now written by two dialogs. The Modules dialog owns system level
and clears it by sending `"systemLevel": null`. The review dialog (§6) does not show system level
at all and omits the field.

Decoded into `String?`, those two are the same value — so the review dialog would silently wipe a
module's classification on every save. `JsonNull` is an object rather than Kotlin `null`, so the
DTO field is `JsonElement?` and is read through a sealed `SystemLevelChange` (`Unchanged`, `Clear`,
`Set`). `Unchanged` emits no statement at all.

The alternative was splitting the endpoint in two, which §8 rules out — a dialog is one
transaction — or changing the existing wire format, which would have touched a working feature for
the benefit of a new one.

### 2. `visible` and `verification` are a new Shape-B kind; `mandatory` is not

`:__AttributeSetting` joins `:__Policy` as the second Shape-B kind, exactly as §9.2 specifies. The
reason it is not folded into `:__Policy` bears repeating because the shapes look similar: a policy
models a *rule about a value* (`mandatory` / `forbidden` / `pattern`) scoped by `appliesToLabels`;
`visible` and `verification` are *roles for an attribute*, with no value semantics and no label
scope. Widening `rule` would make `attribute-policy-checks.md` mean two things.

But `mandatory` submitted by the review dialog is routed to `:__Policy`, **not** stored on the
attribute-setting node. It is the same stored value the Modules dialog writes, so setting it in
either dialog is visible in the other (criterion 4). One policy shape, one write path, two
dialogs — a copy on the setting node would be a second source of truth that drifts on the first
save from either side.

A setting whose flags are all false is deleted rather than stored as a row of `false`, matching how
an emptied comment is handled: `MATCH (m:__Meta)` stays an inventory of decisions actually made.

### 3. The comment write path checks module membership

`POST /modules/{ref}/comments` decodes every `ref` and then verifies each one is an object *of that
module* before writing. Without that check, an arbitrary `__id` in the request body would attach a
`:__Note` to any node in the graph — including a `:DOORSModule` or another module's requirement.
"Comment on a row you loaded" is the actual contract, so it is enforced rather than assumed.

Refs that do not decode are rejected at the route as a `400`, separately from refs that decode but
are not in the module: a malformed handle and a wrong object are different mistakes and deserve
different messages.

### 4. References are pattern comprehensions, not `OPTIONAL MATCH` + `collect`

The obvious way to attach outgoing and incoming links to each row is two `OPTIONAL MATCH`es and two
`collect()`s. That has a trap: when nothing matches, `collect` over an optional-null row yields a
list containing one all-null map, not an empty list — so every unlinked object ships a phantom
reference. It also multiplies rows before aggregating them.

Pattern comprehensions (`[(o)-[:refersTo]->(t) | {…}]`) return a true empty list and never multiply
rows. Reference *module names* are then resolved once per page rather than joined per reference,
because a 984-row page carrying ~400 references would otherwise re-fetch the same handful of names
hundreds of times.

## Consequences

`saveModuleSettings` gained a parameter and changed one, so every call site had to be revisited —
which is the sealed-type discipline working as intended rather than a cost.

`requirementLike` is computed server-side and included in the row payload for the requirements-only
filter (§11 O4, confirmed in scope). It deliberately counts `DOORSTBD` as requirement-like: a
sanitised export blanks `Object Type` so every object imports as TBD, and excluding it would empty
the table on exactly the fixtures that get shared outside the work environment (CLAUDE.md §10).
It is derived per request and never stored — a stored classification would go stale on re-import.

**Open, and deliberately not built:** `incomingComplete` is hard-coded `false`. Incoming links are
incomplete until every referencing module has been imported, and nothing currently tracks which
modules those are. The field exists so the caveat travels with the data rather than living in UI
copy; when import coverage becomes knowable it becomes a real computation with no wire change.

The author of a comment is recorded (`__createdBy` / `__updatedBy`, required by R2) but is not
carried on the wire and is not shown anywhere — the answer to §11 O2. Any reviewer may edit any
comment (O3), which matches the fact that there is no auth layer yet and every write is
`CurrentUser.PLACEHOLDER`; the audit fields are what make an ownership rule addable later without a
migration.
