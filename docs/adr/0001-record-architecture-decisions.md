# ADR 0001: Record architecture decisions as ADRs

Status: accepted
Date: 2026-08-04

## Context

`CLAUDE.md` §11 requires a short ADR for any decision that took real thought, so the reasoning
behind a non-obvious choice survives past the pull request that made it.

## Decision

Use lightweight Architecture Decision Records, one file per decision, numbered sequentially in
`docs/adr/`. Start from `docs/adr/TEMPLATE.md`.

## Consequences

Future contributors — human or Claude Code — can find *why* a decision was made without
reconstructing it from commit history or guessing from the code.
