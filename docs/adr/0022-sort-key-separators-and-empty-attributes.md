# 22. Two Tier-1 corrections: sort-key separators, and empty attributes

Date: 2026-08-18

## Status

Accepted. **Requires a re-import of every DOORS module** — see Consequences.

## Context

Both defects were found by the same thing: a test added against the three committed **real** DOORS
exports (`DoorsRealExportTest`). Neither was reachable from a hand-written fixture, and both had
been in the importers since they were written, in Kotlin and in Python alike.

### 1. `__sortKey` did not reproduce document order

R3's contract is exact: *"a plain string sort on `__sortKey` reproduces the source tool's own
display order."* It did not.

The key padded every numeric part to six digits but **kept both separators**:

```
6.2.1.0-7  ->  000006.000002.000001.000000-000007
6.2.1-1    ->  000006.000002.000001-000001
```

`-` is 0x2D and `.` is 0x2E, so `-` sorts first and `6.2.1-1` came out ahead of `6.2.1.0-7` — the
reverse of the order DOORS lists them in. One inversion in 2 446 real objects.

It needs two numbers of *different depth* under one parent to appear at all, which is why every
hand-written fixture missed it and why it survived two implementations and their unit tests.

### 2. An attribute exported as `""` never reached the graph

CLAUDE.md §11 says `""` from DOORS means *"attribute exists and is empty"*, not *absent*, and the
alias map renders it as **Empty** in `--sec-ink-3` — explicitly *"never italic… the row belongs in
the list, because `""` means 'exists and is empty'; leaving the value blank reads as the panel
having failed to show something"*.

Both importers ended their property builder with a filter dropping every empty value. So the rule
was stated in three places and implemented in none: the *Empty* state was unreachable for DOORS
data, and "absent" and "present but empty" were indistinguishable downstream.

## Decision

### `.` and `-` are one level separator

```kotlin
n.split('.', '-').joinToString(".") { it.padStart(6, '0') }
```

DOORS renders them differently — `-` marks a non-heading child — but they play the same role in the
outline, so `6.2.1-1` is the same depth as `6.2.1.1` and compares as such.

Measured over the three real exports: **zero inversions, and no two distinct `objectNumber`s
colliding on one key.** Both are asserted, because normalising separators could in principle
introduce a collision and a collision would make the order between two objects arbitrary.

### Empty *source* attributes are kept; empty *derived* ones are still dropped

The filter now runs **before** the source attributes are added rather than over everything.

That split is the substance of the decision. `""` on a `__`-prefixed derived property means "we had
nothing to put here" — an object with no `__tableID` is not in a table — which is a genuine absence
and worth not storing. `""` on a DOORS attribute is a value the user can see and act on.

Measured over the three real exports: empties are **2 % of all attribute values**, and **no
attribute is empty across a whole module**. So the cost is negligible, and — the part that decided
it — **no view gains a column**, because attribute discovery already finds each of these attributes
through the objects that do populate them. The only change on screen is that a cell which was blank
now says *Empty*, which is what the alias map always specified.

### Both importers change together, and a test enforces it

`__sortKey` is Tier-1, so R1 requires either importer to regenerate byte-identical values.
Previously nothing checked that; the two implementations were written separately, tested separately
and would have drifted the moment one was fixed — which is exactly what this ADR would otherwise
have done.

`CrossLanguageSortKeyCheck` reads `importers/tests/fixtures/sort_key_table.tsv` — every one of the
2 013 distinct `objectNumber`s in the committed exports, with the key the Python implementation
produces — and asserts Kotlin agrees. It skips when the table is absent, so it never breaks a
machine without Python. Regenerate it with:

```bash
python3 - <<'PY'
import json, sys, io
sys.path.insert(0, 'importers/src')
from sec_import.doors.derivations import sort_key
nums = set()
for f in ('SRD_000969a2_current', 'Something_0009f361_current', 'Something-Something_0009630f_current'):
    d = json.load(open(f'backend/src/test/resources/fixtures/doors/{f}.json'))
    nums |= {o.get('objectNumber', '') for o in d['__contents']}
io.open('importers/tests/fixtures/sort_key_table.tsv', 'w', newline='\n').write(
    "\n".join(f"{n}\t{sort_key(n)}" for n in sorted(nums)) + "\n")
PY
```

## Consequences

- **Every DOORS module must be re-imported.** `__sortKey` changes value on every object with a `-`
  in its number, and empty attributes appear where they did not. Both importers `MERGE` on `__id`
  and `SET n += props`, so a re-import corrects them in place — and **Tier 2 survives it**, which
  is the property R1 exists to guarantee and which `JiraIssueImportTest`'s annotation test already
  covers for the equivalent path.
- **Stale `__sortKey` values are not detectable by eye.** A module imported before this change and
  not since will order one pair of objects wrongly and look completely normal. Re-import all of
  them rather than the ones somebody remembers.
- **The graph grows by roughly 2 %** in stored attribute values. Nothing else about sizing changes.
- **A third importer must reuse `sort_key`, not reimplement it.** The cross-language check covers
  two languages; a third would need adding to it, and the completeness of that table is now part of
  what R1 means here.
- **`derive_sort_key` for a future source is still free to differ.** R3 fixes the *contract*, not
  the algorithm: another source pads something else. What must not differ is two implementations of
  the *same* source's derivation.
