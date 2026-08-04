# DOORS to Neo4j importer spec

> Status: not yet authored. `CLAUDE.md` §10 references "seven known export defects that the
> importer must survive" and instructs future work to *not* re-derive this design — meaning
> this document must exist and be authoritative before `importers/src/sec_import/doors/` grows
> beyond the current `derive.py` / `cli.py` stubs. Do not invent the seven defects; they come
> from direct experience with real DOORS exports and belong here once documented by someone
> who has seen them.

## What belongs here

- The DOORS export format this importer consumes (DXL script output shape, encoding, delimiters).
- `derive_id`, `derive_name`, `derive_labels`, `derive_parent`, `derive_sort_key` for DOORS
  specifically: how the outline number (`objectNumber`) becomes a zero-padded, string-sortable
  `__sortKey`, and how the implicit parent/child structure becomes explicit `__child` edges.
- The closed `Object Type` enum and how an unrecognised type is handled.
- The seven known export defects and the specific handling for each (never silently dropped —
  every anomaly is reported per `CLAUDE.md` §10).
- How `refersTo` traceability links are extracted and what happens when the target has not
  been imported yet (`:__UNDEFINED`).
- The idempotency contract in DOORS-specific terms: which fields participate in `MERGE` vs.
  `SET n += props`, and the second-run-is-a-no-op acceptance test.

## What does not belong here

Generic importer architecture (the batched writer, the report shape, the Tier-1 derivation
protocol) lives in `importers/src/sec_import/core/` and is described in `CLAUDE.md` §10 — link
to it, don't restate it.
