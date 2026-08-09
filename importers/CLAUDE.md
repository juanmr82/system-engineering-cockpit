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
