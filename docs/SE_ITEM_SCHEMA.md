# SE Item schema

> Status: not yet authored. This file is referenced throughout `CLAUDE.md` as the canonical
> definition of the graph schema — write it before the first importer or API route that
> depends on a detail not already stated in `CLAUDE.md` itself.

## What belongs here

Per `CLAUDE.md` §1–§2 and §6, this document is the source of truth for:

- The `:SEItem` label and the identity triple every node carries: `__id`, `__name`, `__version`
  (R6). How `__id` is namespaced per source, and why it must never be a source-native id.
- The full Tier-1 property and relationship set (R1, R3): `__sortKey`, `__moduleUrl`,
  `__objectUrl`, `__child`, `__typeRaw`, `__tableObject` / `__tableRowIndex` /
  `__tableColumnIndex`, and any source adds to that list.
- The label model per source: which labels a DOORS object, a Windchill document, and a Cameo
  element carry, and which labels are shared (`:SEItem`) vs. source-specific.
- The `:__UNDEFINED` placeholder shape — when it is created, what it means, and the invariant
  that it must always be present in `labels` when applicable (§5 "API shape").
- Cross-source join points: how a Cameo element, a DOORS requirement and a Windchill document
  agree on being the same `:SEItem` versus being linked via a Tier-2 `:__Link` (§2 Shape C).

## What does not belong here

The Tier-2 meta model (`:__Meta` shapes and the `__metaKind` catalogue) is fully specified in
`CLAUDE.md` §2 R2 already — do not duplicate it here, link to it instead.
