# Feature spec — Attribute policy checks

**Path in repo:** `docs/features/attribute-policy-checks.md`
**Companion to:** `docs/features/requirements-modules.md` (which *defines* the policies)
**Read first:** `CLAUDE.md` §2 (R1, R2 — especially "explicitly not `:__Meta`"), §7 (Community limits)

The Modules dialog lets a user mark DOORS attributes as **mandatory**. Those marks are not
DOORS data and have no meaning inside DOORS — they exist so the Cockpit can run sanity and
consistency checks over the imported requirements. This document covers what is checked,
how the policy is stored so the checks are fast, and how the checks are run.

---

## 1. What is checked, and on what

A **violation** is: a requirement in scope that does not carry a value for an attribute the
module's policy marks mandatory.

**Scope is requirements only.** Headings, information objects, applicability-matrix rows,
TBD objects and table structure are not requirements and are never reported. Concretely, an
object is in scope when it carries the `DOORSRequirement` type label — the label the
importer derives from `Object Type` — and does **not** carry a table label.

| In scope | Out of scope |
|---|---|
| `DOORSRequirement` | `DOORSHeading`, `DOORSAppMatrixHeading` |
| | `DOORSInformation`, `DOORSAppMatrix`, `DOORSTBD` |
| | anything also labelled `DOORSTable`, `DOORSTableRow`, `DOORSTableCell` |

Scope is stored on the policy node as `appliesToLabels` — a field `CLAUDE.md` already
allows on `:__Policy` — not hardcoded in the query. Default `['DOORSRequirement']`. Storing
it means the same machinery covers "every information object needs a Rationale" later
without a schema change, and it makes the intent readable in the graph.

**A value is missing when the property is absent, or is a string that is empty or
whitespace-only.** DOORS `""` means "attribute exists and is empty" (schema doc §5.1) — for
this check both are equally a violation, and the distinction is not surfaced to the user.

> ### Note on redacted fixtures
>
> Real exports carry a populated `Object Type`, so requirements are labelled
> `DOORSRequirement` and the check works normally. But **any export sanitised for sharing
> outside the work environment blanks the user attributes**, including `Object Type`, so
> every object imports as `DOORSTBD` and none is in scope.
>
> Two consequences: test fixtures must keep `Object Type` populated or the check tests
> assert nothing; and the UI must distinguish **"0 requirements in scope"** from
> **"0 violations"** in words, because that is also what a mis-scoped policy or a
> not-yet-classified module looks like.

---

## 2. How the policy is stored

Unchanged from `requirements-modules.md` §5.2, plus the scope field:

```cypher
(:DOORSModule)-[:__policyFor]->(:__Meta:__Policy {
  __metaId: '01924f…', __metaKind: 'policy', __schemaVersion: 1,
  __createdBy: …, __createdAt: …, __updatedBy: …, __updatedAt: …,
  attributeName:   'Object Text',
  rule:            'mandatory',
  appliesToLabels: ['DOORSRequirement']
})
```

One node per `(module, attributeName, rule)`. Ticked ⇒ exists; unticked ⇒ deleted.

**Why not denormalise the whole list onto one node per module.** A single
`:__Policy {mandatoryAttributes: [...]}` node would be one read instead of ten. But a module
carries on the order of ten mandatory attributes, so the traversal is ten relationship hops
from a node already in memory — unmeasurable — and the list form destroys per-attribute
audit fields, makes "which modules require Rationale" a full scan with list containment,
and collapses the `mandatory` / `forbidden` / `pattern` rules into one property. Keep one
node per rule.

**Why the check result is not stored anywhere.** `CLAUDE.md` R2 excludes derived data from
`:__Meta` explicitly — "stored derivations go stale silently". A violation list is a pure
function of (imported data × policy), both of which are already in the graph, and it goes
stale on every import and every checkbox change. It is computed on read. §5 covers what to
do if that ever gets slow, and it is not "write it back".

---

## 3. Indexes — this is where the performance actually comes from

Two additions. Both are one-line, both are `IF NOT EXISTS`, both must be created before the
first check runs.

```cypher
CYPHER 25
CREATE INDEX doors_requirement_module IF NOT EXISTS
FOR (n:DOORSRequirement) ON (n.__moduleUrl);

CYPHER 25
CREATE INDEX meta_policy_attribute IF NOT EXISTS
FOR (p:__Policy) ON (p.attributeName);
```

The first is the one that matters, and the reason is not obvious: **Neo4j label-property
indexes are per-label.** The importer already creates
`FOR (n:DOORSObject) ON (n.__moduleUrl)`, but the planner will not use it for
`MATCH (r:DOORSRequirement {__moduleUrl: $u})` — it has no knowledge that every
`DOORSRequirement` is also a `DOORSObject`. Without the new index that pattern degrades to a
label scan over every requirement in the database plus a property filter. With it, the check
touches only the requirements of one module.

- Add `doors_requirement_module` to the importer's schema phase (`DOORS_TO_NEO4J_IMPORTER_SPEC.md`
  §7.3), next to the existing indexes — it indexes an imported label, so it belongs with
  them, and it must exist even if the backend has never started.
- Add `meta_policy_attribute` to the backend's meta-schema migration. It serves the inverse
  question ("which modules mark this attribute mandatory") that the Statistics view will ask.
- Do **not** try to index the attribute values themselves. Attribute names differ per module
  (78 in the reference module), so value indexes would mean dozens of indexes per module,
  created dynamically from user data, on properties whose names contain spaces and umlauts.
  That is a maintenance disaster in exchange for a scan that is already fast.

`:__Meta(__metaId)` uniqueness is already covered by `CLAUDE.md` §7.

---

## 4. Running the check

Three steps, one read transaction, `CYPHER 25` prefix and a `timeout` on the transaction
(§7 of `CLAUDE.md`).

**Step 1 — read the policy.** A relationship traversal from a single node; no index needed.

```cypher
CYPHER 25
MATCH (:DOORSModule {__id: $moduleId})-[:__policyFor]->(p:__Meta:__Policy)
WHERE p.rule = 'mandatory'
RETURN p.attributeName AS attributeName,
       coalesce(p.appliesToLabels, ['DOORSRequirement']) AS appliesToLabels;
```

Group the results by `appliesToLabels` in Kotlin and run steps 2–3 once per group. In
practice there is exactly one group. **This is the same trick the importer uses in its Phase
3** — group by label set, then run a statement whose labels are static — and it exists for
the same reason: every statement stays statically analysable and no label is ever
interpolated from user data. Cypher 25 does support dynamic labels; do not use them here.

**Step 2 — count the population.** Served entirely from the new index; milliseconds
regardless of module size.

```cypher
CYPHER 25
MATCH (r:DOORSRequirement {__moduleUrl: $moduleUrl})
WHERE NOT r:DOORSTableCell AND NOT r:DOORSTableRow AND NOT r:DOORSTable
RETURN count(r) AS requirementsInScope;
```

**Step 3 — find the violators, and only the violators.**

```cypher
CYPHER 25
MATCH (r:DOORSRequirement {__moduleUrl: $moduleUrl})
WHERE NOT r:DOORSTableCell AND NOT r:DOORSTableRow AND NOT r:DOORSTable
WITH r, [a IN $required
         WHERE r[a] IS NULL
            OR (r[a] IS :: STRING AND trim(r[a]) = '')] AS missing
WHERE size(missing) > 0
RETURN r.__id      AS id,
       r.id        AS doorsId,
       r.__name    AS name,
       r.__sortKey AS sortKey,
       missing
ORDER BY sortKey
LIMIT $cap;
```

Notes on that query, each of which is a trap avoided:

- `r[a]` is **dynamic property access by name** — the only way to read attributes whose names
  are data. It is a hash lookup on a property map already in memory, not a scan.
- The `IS :: STRING` type predicate matters because a handful of properties are coerced to
  integers by the importer (`Absolute Number`, `objectLevel`). Comparing an integer to `''`
  would not throw, it would quietly evaluate false — correct by accident today, wrong the
  day someone adds a numeric coercion. Be explicit.
- **Aggregate per-attribute counts in Kotlin from these rows**, not in a second Cypher
  statement. A per-attribute `UNWIND … count(*)` would repeat the whole scan to produce
  numbers derivable from a result set you already hold, and violators are a small subset of
  the population.
- `$cap` (default 5 000) with a `truncated` flag in the response. A module where every
  requirement violates every rule must not return a million rows into a single JSON
  document.

The whole thing is one indexed scan over the requirements of one module, doing
`|requirements| × |mandatory attributes|` map lookups. For the reference module that is
under a thousand nodes; at a hundred thousand requirements it is still a sub-second scan.

---

## 5. If it ever does get slow

In order, and stop as soon as it is fast enough:

1. **Check the plan, not the model.** `PROFILE` the step-3 query. If `db hits` scale with the
   whole database rather than the module, `doors_requirement_module` is missing or the
   planner is not using it — that is the entire problem 95% of the time.
2. **Cache in the application, keyed by invalidation inputs.** An in-process cache keyed by
   `(moduleId, lastPolicyWriteAt, lastImportRunAt)` for the aggregate counts. It is a
   derivation living where derivations belong — in memory, cheap to throw away — and it does
   not violate R2. Serve it with an `ETag` so the frontend re-renders only on change.
3. **Only then** consider persistence, and treat it as a schema change with an ADR, an
   invalidation story and a "recompute all" command — not as a quick fix.

Never write violation counts onto the imported nodes. That is R1 as well as R2.

---

## 6. API and where the results surface

The Modules dialog **defines** policy. It does not run checks — a settings dialog that also
reports findings does two jobs badly.

```
GET /api/v1/modules/{ref}/checks/attribute-policy
→ {
    "requirementsInScope": 512,
    "violatingRequirements": 37,
    "byAttribute": [ { "attribute": "Rationale", "missing": 31 },
                     { "attribute": "Verification method", "missing": 12 } ],
    "violations": [ { "ref": "…", "doorsId": "SRD-42", "name": "…",
                      "missing": ["Rationale"] } ],
    "truncated": false
  }
```

- **`/requirements/review`** — the primary consumer, and it does **not** use this endpoint.
  **Implemented**: the check is evaluated per row inside `GET /modules/{ref}/objects`, which
  already returns every property of every object, and arrives as `issues: string[]` on each row —
  a list shared with the **fixed** checks that view also runs, which are not policy-driven and are
  specified in `REQ_REVIEW.md` §5.3. The table needs the verdict *per row*, and joining two
  separately paged responses in the client to get it would be fragile for no gain — the
  server-side cost is one extra small query for the policies, once per page.
  This endpoint remains the right shape for the aggregate report and is still unimplemented.
- **`/requirements/statistics`** — the aggregate only: compliance per module, per attribute.
- **Modules list** — optionally a compliance figure per row later. Do not add it in the first
  pass; it turns a cheap list query into one check per module.

`byAttribute` is ordered by `missing` descending — the actionable order.

Per R5, `missing` carries raw DOORS attribute names, which is correct: those are *content*,
the names the user chose in DOORS and ticked in the dialog. No `__`-prefixed name appears in
this payload.

---

## 7. Design for the other two rules now, build one

`rule` is already the closed enum `mandatory | forbidden | pattern`. Build the check runner
so a rule is an interface — scope predicate plus per-value predicate — with `mandatory` as
the first implementation. `forbidden` (attribute must be empty) and `pattern` (value must
match a regex, e.g. a requirement text starting with "shall") then slot in without touching
the scan, the indexes or the endpoint.

Do **not** build `forbidden` or `pattern` yet. Leave the seam.

One extension worth noting and not building: policy currently anchors to a module. Once
system-level classification exists, "every L2 module requires Rationale" becomes expressible
by anchoring a policy to the classification instead. That is a genuinely different anchor
shape and needs its own ADR — flag it if the request arrives, do not pre-empt it.

---

## 8. Relationship to the Modules spec

`docs/features/requirements-modules.md` already reflects this: tab 2 states the scope in its
supporting text, and the policy node it writes carries
`appliesToLabels: ['DOORSRequirement']`.

The dialog does not gain a scope selector. Scope is a stored field so the model can grow, not
a knob for the user; a per-attribute scope picker in a settings dialog is a feature nobody
has asked for.

---

## 9. Acceptance criteria

1. A requirement missing a mandatory attribute is reported; the same object as a
   `DOORSHeading`, `DOORSInformation` or `DOORSTBD` is not.
2. An object labelled `DOORSRequirement` **and** `DOORSTableCell` is not reported.
3. An attribute present but `""` or whitespace-only counts as missing.
4. An attribute coerced to an integer by the importer is never falsely reported missing.
5. A module with no `DOORSRequirement` objects reports "no requirements in scope", not
   "no violations". Test fixtures used for the check tests have `Object Type` populated.
6. `PROFILE` of the step-3 query shows db hits proportional to the module's requirement
   count, not to the database size — asserted with a test on a two-module fixture.
7. Removing a checkbox in the Modules dialog changes the next check result immediately, with
   no cache flush or restart.
8. Re-running the DOORS importer does not change any policy node; check results change only
   because the imported data changed.
9. No check result is written to the graph anywhere — `MATCH (m:__Meta) DETACH DELETE m`
   followed by a re-run reproduces identical results.
10. A module with more violations than `$cap` returns `truncated: true` and the UI says so.