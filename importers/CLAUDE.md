# CLAUDE.md — System Engineering Cockpit importers

Guidance for Claude Code working under `importers/`. This file loads **in addition to** the
root `CLAUDE.md`, which stays always-loaded and holds the rules that bind every stack: the `__`
namespace and Tier 1 / Tier 2 model (R1–R3, R5–R7), where each kind of state lives, the pinned
versions, the Neo4j Community limits, and the working agreements. Read that first; nothing here
overrides it.

Section numbers below are the root file's own and are deliberately unchanged — code comments and
`docs/` reference them as "CLAUDE.md §6", and those references still resolve.

## 10. Importers

- One Python package, `sec_import`, with a shared `core/` holding identity derivation,
  the batched graph writer, config and reporting. **Per-source packages contain only
  parsing and mapping.** If you write a second batched-`UNWIND` writer, you have
  duplicated `core/`.
- Derivation rules are **pure functions**, isolated and unit-tested, separate from driver
  code: `derive_id`, `derive_name`, `derive_labels`, `derive_parent`, `derive_sort_key`,
  and per-source helpers like `target_object_url`. A change to the identity scheme should
  touch `derive_id` and nothing else.
- **Every source implements the same Tier-1 derivation interface** (R3): identity triple,
  parent, sort key, labels. `core/` defines the protocol and the writer; the source
  package supplies the implementations. A new source is a new module implementing that
  protocol — not a new graph shape.
- Every importer is **idempotent**: `MERGE` on `__id`, and a second identical run creates
  zero nodes and zero relationships. This is the acceptance test, not a nice property.
- **A re-import reconciles; it does not only merge** (ADR 0012, phase 6 of `doors/importer.py`).
  The export is authoritative for what the module contains *now*, so what it no longer mentions
  stops being part of it — but **an object is never deleted for having disappeared.** DOORS
  deletes objects and keeps the links to them, and that stale link is a real defect in the
  requirements data that nothing else in the toolchain will show. Deleting the object here too
  would destroy the evidence and make the referencing module look correct.

  So it is **labelled**: `:__DELETED`, *alongside* every label it already had. It stays a
  `:DOORSObject` with its id, its attributes and its type label, which is what lets a view name
  the requirement that went away. Re-merging the object removes the label again.
- **The decision is a run stamp, not a diff sent over the wire.** `__importedAt` is written on
  every object, `__child` and `refersTo` a run confirms; anything still carrying an older stamp
  is something the export did not mention. Six set-based statements follow, and **no parameter
  grows with the module** — that is the performance contract, and
  `tests/test_reconcile_cypher.py` asserts it. Four clauses in them are load-bearing:
  1. `coalesce(n.__importedAt, '')` — `NULL <> $ts` is NULL, which matches nothing;
  2. the stale-`refersTo` prune **excludes `:__DELETED`**, or the first thing deleted would be
     the very links a ghost exists to expose;
  3. the ghost keeps `refersTo` only to other `:DOORSObject`s, **and `:__Meta`** — R2 forbids
     leaving a note hanging off nothing;
  4. the two collection statements are **global, not module-scoped**: re-importing one module is
     what strands a ghost belonging to another.
- **Ghosts are out of every module listing** — the review table, the statistics scan, attribute
  discovery, table reconstruction. They are reached only from the links pointing at them.
- **Both link lists are imported, and they answer different questions.** `__outputLinks` is what
  a module asserts; `__inputLinks` is what other modules assert about it. The second is the only
  way an incoming link is visible before the referencing module has been imported — which is most
  of the time in a graph that grows one module at a time — and the placeholder it creates is what
  puts *Not yet imported* on screen instead of silence. Because of it `incomingComplete` is now
  **true**: a module's export states every link pointing at it, so an empty incoming list means
  something and the old "only outgoing links are imported" caveat has been removed everywhere.
- **The annotations go with the object.** When an export stops mentioning an object, the `:__Meta`
  nodes hanging off it are deleted, not preserved. This is the one place an importer touches Tier 2
  and root `CLAUDE.md` R2 names it as such; a second such place needs its own ADR.
- **"Unlinked" is literal.** After the annotations and the non-DOORS edges are gone, a ghost with
  no edges at all in either direction is deleted. Two deleted objects linking only to each other
  are therefore kept — that was a deliberate call, not an oversight.
- Every run writes a report (console summary + JSON) with counts and anomalies. Never
  silently swallow a malformed record.
- `--dry-run` performs parsing and derivation, writes the report, touches nothing.
- Batch 1 000–5 000 rows per transaction via `UNWIND $rows`, driver-side. Not `LOAD CSV`.
- The `.bat` files in `importers/win/` are **thin wrappers only** — resolve the Python
  interpreter, set encoding to UTF-8, call the module entry point, propagate the exit
  code. No business logic in batch, ever.
- The importers own the schema for imported labels: constraints and indexes on `:SEItem`,
  `:DOORSObject`, `:DOORSRequirement` are created in their schema phase, not by the
  backend. The backend owns only the `:__Meta` schema.
- DOORS specifics are fully described in `docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md`, including
  seven known export defects that the importer must survive. Do not re-derive that design.
- **Exports sanitised for sharing outside the work environment blank every user
  attribute**, including `Object Type`, so every object imports as `DOORSTBD` and nothing
  carries a real type label. Real exports do not have this problem. Keep test fixtures
  realistic, or type-dependent tests silently assert nothing.

---
