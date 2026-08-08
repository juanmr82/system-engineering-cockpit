# ADR 0010: Graph names are constants, and every one of them is interpolated into Cypher

Status: accepted, **amended 2026-08-08** — see *Amendment* at the foot. The original decision
interpolated only the DOORS attribute names and left our own spelled out; the amendment
interpolates all of them. Everything above the amendment is the reasoning as it stood, kept because
the amendment is a change of weighting rather than a discovery that it was wrong.
Date: 2026-08-08

## Context

`docs/REFACTOR_BACKEND.md` items 1 and 2: *"a lot of the attribute names are used as textual
strings in several places. If I have to do a theoretical change of the attribute name, I need to
change it in a bunch of places of the code."*

That was accurate. Before this change the backend spelled `__id` out 81 times, `objectNumber` 29,
`Object Text` 18 across 11 files, `Object Type` 11. Worse than the count, the partial fixes had
started to collide:

- `__UNDEFINED` was declared twice, as `DoorsChecks.UNRESOLVED_LABEL` and as a private
  `BreakdownProjection.UNRESOLVED_LABEL`.
- `DOORSTable` / `DOORSTableRow` / `DOORSTableCell` were declared in `TableGeometry` **and**
  written out again as a set in `DoorsChecks.structuralTypes`.
- The structural-attribute exclusion list `['id', 'objectNumber', 'objectLevel']` existed three
  times: in `ModuleCypher.DISCOVER_ATTRIBUTES`, in `ReviewProjection.RESERVED_KEYS`, and in a
  comment asserting the two were "kept identical on purpose" — which is what you write when
  nothing enforces it.

So the problem was not only "a rename is expensive". It was that two spellings of one name had
already drifted into the codebase and the only thing keeping them equal was attention.

## The distinction that decides everything

The names fall into two groups that look identical and are not equally volatile.

**Source attribute names — `Object Text`, `Object Heading`, `Object Type`, `objectNumber`.** These
belong to DOORS. A DOORS administrator can rename an attribute in a module's attribute definitions,
and **the importer needs no change at all**, because it copies attributes verbatim (R1). The rename
is genuinely cheap at the source, so it has to be cheap here or the backend becomes the reason it
cannot be done.

**Our own names — `__id`, `__child`, `__sortKey`, `:SEItem`, `:DOORSRequirement`, `:__Meta`.**
These are written by the importers. Renaming one means changing Python, re-importing every module,
and amending `SE_ITEM_SCHEMA.md` or `DOORS_TO_NEO4J_IMPORTER_SPEC.md`. Against that, the Kotlin
edit is a rounding error — the importer is the gating cost, and no amount of indirection in the
backend moves it.

## Decision

**Every name has exactly one declaration.** Two files, split along R1's own line so a second source
adds a file rather than editing one:

| File | Holds |
|---|---|
| `domain/GraphNames.kt` | `Prop`, `Rel`, `NodeLabel`, `MetaKind`, `MetaProp`, `MetaValue` — the `__` namespace, `:SEItem`, `:__UNDEFINED`, and the whole Tier-2 vocabulary. Source-agnostic; imports nothing |
| `source/doors/DoorsNames.kt` | `DoorsAttr`, `DoorsModuleAttr`, `DoorsProp`, `DoorsRel`, `DoorsLabel` — DOORS attribute names, its `__table*` derivations, `refersTo`, and every `DOORS*` label |

**Kotlin code addresses the graph only through these.** No `props["__id"]`, no
`labels.contains("DOORSTBD")`.

**Cypher interpolates `DoorsAttr` and not the rest.**

```kotlin
// interpolated — the volatile half
r['${DoorsAttr.OBJECT_TEXT}']  AS rowText
WHERE NOT k IN ['${DoorsAttr.ID}', '${DoorsAttr.OBJECT_NUMBER}', '${DoorsAttr.OBJECT_LEVEL}']

// left spelled out — ours, and gated on a re-import anyway
MATCH (o:DOORSObject {__moduleUrl: $moduleUrl})
```

`const val` initialisers may interpolate other `const val`s, so this stays compile-time constant
and the statements remain `const`.

**`GraphNamesTest` is what makes the second half safe.** It reads every compiled statement in
`graph/cypher/` plus `MetaSchema.statements`, extracts every `:Label`, every relationship type,
every `__`-prefixed token and every label carried as a Cypher string, and fails on one the
constants do not declare. A fifth test asserts the file list it reads is complete, so a new Cypher
file cannot slip past, and a sixth asserts the regexes still match something, so a broken
extraction cannot make the suite pass vacuously.

This was verified by breaking it on purpose: `:SEItem` → `:SEItm` and `__id` → `__idd` in
`ItemCypher` produced two named failures pointing at the statement.

**`api/ApiPaths.kt`** does the same for the `/api/v1` prefix, which was written out in seven route
files plus the SPA fallback's `startsWith("/api/")` guard. Those two must agree — a route under a
prefix the fallback does not recognise stops being a 404 and becomes an HTML page with status 200.

## Consequences

- Renaming a DOORS attribute is one edit in `DoorsNames.kt`, plus the test fixtures that stand in
  for a DOORS export.
- Renaming one of our own names is still a multi-file edit in the Cypher — but a **failing build**
  rather than a query that silently matches nothing, which is the failure mode that actually costs
  a day.
- The duplicate declarations are gone. `DoorsChecks.structuralTypes` *is* `DoorsLabel.tableStructure`
  and `ReviewProjection`'s exclusion list *is* `DoorsAttr.structural` — the same object, so the
  comment claiming they were "kept identical on purpose" could be replaced by the fact.
- `domain/Aliases.kt` now imports from `source/doors/`, the only file in `domain/` that does. This
  is inherent, not a layering slip: the alias map is where every source's vocabulary meets by R5's
  design, and it will import Windchill's and CAMEO's names as those arrive. What it must not do is
  spell one out — a literal there is a second source of truth.
- **Test fixtures deliberately keep the literals.** A fixture that writes `"Object Text"` and then
  asserts the projection read it is an independent check that the constant carries the right value;
  building the fixture from the constant too would let a wrong constant pass. Production code uses
  the constants, fixtures spell them out.

## Rejected

**Interpolating everything, including labels and `__` names.** It works and it was tried on
`TableCypher` first. `MATCH (o:${DoorsLabel.OBJECT} {${Prop.MODULE_URL}: $moduleUrl})` costs the
readability of the most carefully commented code in the backend, and buys a rename that a Python
change and a full re-import already dominate. The guard test recovers the only part that mattered.

> **This is what the amendment reversed.** The form it was rejected in — fully qualified
> `${Prop.MODULE_URL}` inside the string — is genuinely as unreadable as this says. Single-name
> imports were the option not considered, and they change the answer.

**A single `Constants.kt`.** It would put `Object Text` and `:__Meta` in one file, which is exactly
the distinction this ADR exists to draw, and it would make a second source edit a shared file.

**Kotlin `enum class` instead of `const val`.** An enum cannot be interpolated into a `const val`
Cypher statement, and these names are map keys read out of a driver `Record` — a `String` is what
they are. `MetaKind` carries an `isKnown` check for the API boundary instead.

---

## Amendment, 2026-08-08 — every graph name is interpolated

### What changed

`graph/cypher/` and `meta/MetaSchema.kt` now interpolate **every** graph name: labels, relationship
types, the `__` namespace, the meta payload keys and the `__metaKind` values, alongside the DOORS
attribute names that were already interpolated. Nothing addresses the graph by literal any more.

```cypher
MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
WHERE NOT o:$DOORS_MODULE
ORDER BY o.$SORT_KEY
```

### Why the original weighting was wrong

The original decision priced the *rename* and ignored the price of *finding* it. `__id` appeared 58
times across eight statement files, `__Meta` 27, `__moduleUrl` 20 — 234 occurrences in all. The
argument that "a Python change and a re-import dominate the Kotlin edit" is true and does not help
the person doing the edit, who has to be sure they found every one and cannot tell by reading
whether two occurrences are the same decision or two decisions that happen to agree.

The guard test made a *missed* occurrence loud. It did nothing about the work.

### Why it is readable now, when the rejected form was not

**Single-name imports**, which the original write-up did not consider. `import
com.sec.domain.Prop.MODULE_URL` makes the template `$MODULE_URL` rather than `${Prop.MODULE_URL}` —
short enough that the Cypher still reads as Cypher. Each constant's simple name is the graph name in
SCREAMING_SNAKE (`SE_ITEM` → `SEItem`, `MODULE_URL` → `__moduleUrl`, `NOTE_ON` → `__noteOn`), so the
mapping needs no lookup. Where two objects collide the import is aliased to say which vocabulary it
is from — `DoorsAttr.ID as DOORS_ID`, `DoorsLabel.OBJECT as DOORS_OBJECT`, `MetaKind.NOTE as
NOTE_KIND`.

`const val` initialisers may interpolate other `const val`s, so the statements are still
compile-time constants. Nothing is built at runtime.

### The guard test changed direction

`GraphNamesTest` keeps the forward check — every name in a compiled statement is declared — and adds
the inverse one, over the statement **source**: no label, relationship type or `__` name appears as
a literal outside a comment. That is the check that matters now. A hand-written `__id` compiles to
the identical string, so the forward check cannot see it; without the inverse check the
interpolation would erode one statement at a time.

Comments are stripped before the scan, deliberately — the comments are where these names are
explained, at length, and that is where they belong. A companion test asserts the stripping did not
simply blank the file, so "nothing matched" cannot pass vacuously.

Both checks are limited to labels, relationship types and the `__` namespace. The meta payload keys
are interpolated too, but they are ordinary words — `text`, `code`, `visible` — and several are also
the *result column* names the statements return, which are wire names rather than graph names. A
literal check over those would fail on correct code.

Found in the first run, before the check was even finished: `MetaSchema` had `__Meta` written out in
a log message.

### What it did not change

- `MetaSchema`'s constraint and index **names** (`meta_id_unique`, …) stay literal. Those are its
  own database objects, not vocabulary anything else addresses.
- **Query parameter names** — `${'$'}moduleUrl`, `row.attributeName` — stay literal. They are a
  contract between one statement and its one call site, not names the graph knows.
- **Test fixtures still spell everything out**, for the reason given above: a fixture built from the
  constant could not catch a wrong constant.
