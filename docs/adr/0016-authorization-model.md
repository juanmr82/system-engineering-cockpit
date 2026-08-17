# ADR 0016 — Authorization: Keycloak decides *who you are*, SEC decides *what you see*

**Status:** accepted, 2026-08-15 · **Supersedes:** nothing · **Closes:** ADR 0014 point 9, the
standing "there is no authorization anywhere" gap
**Implemented by:** `docs/features/access-control.md`

---

## 1. The question

Users belong to company groups. Objects in the graph are visible only to some of those groups.
Keycloak is the IdP today and will be a **broker to the company IdP** later. The proposal was to
delegate the whole thing — identity, groups, *and* per-object permissions — to Keycloak, tagging
elements on import with the group that may see them.

Half of that is right. The half that is wrong would not have shown up until the graph was large.

---

## 2. Decision

**Two systems, one line between them, and the line is drawn at the object.**

| Concern | Owner | Why |
|---|---|---|
| Who the user is (`sub`, company user id) | **Keycloak**, and later the company IdP behind it | it is an IdP. Brokering moves *only this row* outward, and changes nothing SEC reads |
| Which groups the user is in | **Keycloak**, in our own realm, carried as a `groups` claim | this is the one thing we administer in the IdP and keep there. **It stays in our realm after brokering** — the company IdP authenticates a person; it does not decide what they may see in this tool. SEC never edits it |
| What the user may *do* — read, administer settings, administer access | **Keycloak realm roles** | a closed set of ~4 capabilities, changing once a year. Exactly what a role claim is for |
| Which **objects** a group may read | **SEC, in Neo4j** | see §3 |
| Which objects belong to which access category | **SEC, in Neo4j** | it is a property of the data, and it changes on every import |

The user's idea — *tag on import, set the right on the container, let it be transitive* — is kept
whole. It simply lives in the graph rather than in the IdP.

---

## 3. Why per-object permissions do not go in Keycloak

Four reasons, in the order they would have bitten.

1. **A policy engine answers questions; it cannot filter a result set.** Keycloak Authorization
   Services can answer *"may J read B?"*. Every screen in this application asks the other question:
   *"of the 984 objects in this module, which may J read?"* — and only the query that reads them can
   answer that, because the answer must become a `WHERE` clause. Any external decision point turns
   one Cypher query into one query plus N remote calls, or into a fetch-then-discard that leaks
   counts and pages wrongly.
2. **Resource-scoped authorization does not size to a graph.** Keycloak's model wants a resource per
   protected thing. That is hundreds of thousands of rows today and unbounded as sources are added,
   with an RPT that grows with the permission set and a token that would have to be re-issued after
   every import.
3. **A grant is a statement about data, and only the tool holding the data can check it.** Grants
   could be written as Keycloak group attributes — a list of category keys per group, mapped into the
   token. Nothing stops it, and it was considered seriously, because group membership *is* staying in
   our own realm (§3.1). It loses on integrity, not on architecture: a category is created when a
   module is imported, so the attribute would be a hand-typed string with no validation, no
   referential integrity, and no way for the IdP to know the category was deleted. A typo grants
   nothing and says nothing. In the graph, a grant is a relationship — it cannot point at a category
   that does not exist, and deleting a category in use is a `409` naming the counts.
4. **Permissions change on import; identity does not.** Import is a data-lifecycle event that runs
   nightly on a Windows box with no internet. Categories appear when modules and projects appear.
   Coupling that to an IdP write path couples data ingest to IdP availability, and puts an access
   manager in the admin console instead of in front of the queue of things needing assignment.
   Grants in a token also only take effect on the next refresh, and grow it.

### 3.1 Brokering is authentication only — and that is what makes this split stable

When the company IdP arrives, it is brokered for **authentication and the user identity, and nothing
else**. Groups stay where they are: administered by us, in our own realm, assigned to local accounts
that the broker links to on first login.

This is the right call and it makes the boundary cleaner rather than more complicated:

- **The claim contract never changes.** Before and after brokering, SEC receives `sub`, `groups`,
  `realm_access.roles` from one issuer. There is no migration, no dual-mode code, and no day on
  which SEC has to understand two token shapes.
- **Group administration stays in the tool that has a UI for it and a team that knows the project.**
  A DOORS module owner does not become a ticket to the corporate directory team.
- **The company IdP is not asked to model anything about this project.** It answers one question —
  is this person who they say they are — which is the question it is run to answer.

The consequence to plan for: a brokered user is **auto-created on first login and lands in no
group**, so they authenticate successfully and see an empty application until someone assigns them.
That is fail-closed and correct, but it is a workflow, not an accident — `../KEYCLOAK_SETUP.md` §6
says who does it and how they find out there is someone waiting.

**What Keycloak *does* get** is everything it is good at, including the piece that is easy to
under-use: the `groups` claim. SEC never asks Keycloak *who is in this group*; it reads the claim,
and that is the entire integration surface. See ADR 0017 for how the token reaches the backend.

---

## 4. What this is, in industry terms

**ReBAC — relationship-based access control, Zanzibar-shaped**, with permission inheritance down a
containment hierarchy and the decision **materialised as an index** rather than evaluated per read.
Google's Zanzibar paper, OpenFGA and SpiceDB all model exactly this pattern; in OpenFGA it would be
written `define viewer: viewer from parent`.

**We are not adding OpenFGA or SpiceDB.** The relationship store they provide is a graph database
with a membership index, and this project already runs one with the objects in it. Adding a second
service would mean a second datastore (§11 forbids one), a second thing to deploy on a RHEL box and
a no-admin Windows workstation, and a network hop on the hottest path in the application.

**The exit is kept open and is cheap**: the model below is a 1:1 translation of an OpenFGA type
definition. If the day comes that permissions must be shared with another tool, `__inAccessCategory`
becomes a tuple and `__mayRead` becomes a tuple, and nothing else moves.

---

## 5. The model, in one paragraph

An **access category** is a named set of objects. An object is in a category via a
`__`-prefixed relationship. A **group** is granted read on a category. A user's visible set is the
union of the categories granted to their groups. Categories are attached **by hand to containers
only** — a DOORS module, a JIRA project — and propagated to contained items by a machine-owned
reconciliation pass, so setting one right covers a whole module and the propagation is auditable and
reversible. **No relationship, no visibility**: an object nobody has categorised is invisible to
everyone, including administrators, which is the correct behaviour for a newly imported object and
also the correct behaviour after any failure.

Full schema, Cypher, and the reconciliation rules: `docs/features/access-control.md`.

---

## 6. The two departures from CLAUDE.md, stated so they are not read as drift

### 6.1 An access tag is a *bare* `__`-prefixed relationship, not a reified `:__Meta` node

R2 says Tier-2 knowledge is a separate node reached by a `__` relationship. An access category **is**
such a node — `(:__Meta:__AccessCategory)`, with `__metaId`, `__metaKind`, `__schemaVersion` and the
audit quartet, exactly per the contract. What is **not** reified is the *membership*:
`(:SEItem)-[:__inAccessCategory]->(:__AccessCategory)` is a direct relationship, and the audit
properties ride on the relationship.

Reifying membership as its own meta node would mean **one node per (item, category) pair** —
millions, against a few dozen categories — and would turn the authorization predicate, which runs on
every read of every view, from a one-hop degree check into a two-hop traversal. R2's reification
exists to carry *payload*; membership in a set has no payload. This is a new anchor shape and it is
registered as one — **Shape D, membership in a shared set: many anchors, one meta node** — so the
next feature that needs it has a precedent rather than an exception.

The invariants R2 actually protects all still hold: the node carries `:__Meta`, it never carries
`:SEItem`, it is never the target of `refersTo` or `__child`, and
`MATCH (m:__Meta) DETACH DELETE m` still removes every trace of it — **and doing so leaves the
application fail-closed rather than wide open**, which is the right way round.

### 6.2 Two access nodes are *not* `:__Meta`, for R2's own stated reason

`:__Group` and `:__AccessDefault` do not hang off the imported graph — a group anchors to a user
directory, an import default anchors to a source. R2 already names this case and answers it ("saved
queries anchor to a *user* … give them their own label"). They get their own labels and their own
delete query, documented beside the meta one. The `:__Meta` wipe's contract — *it must never destroy
imported data* — is untouched.

---

## 7. Consequences, including the unwelcome one

- **Every read path in the backend changes.** There is one predicate, produced by one function, and a
  guard test that fails the build when a Cypher statement touching `:SEItem` does not carry it. This
  is the same shape as `GraphNamesTest` and for the same reason: the rule has to be enforced by
  something other than memory.
- **A count is a leak.** "+3 links outside this graph", "n links to deleted objects", every number on
  the Statistics view: computed *after* filtering, or it tells a user about objects they cannot see.
  This is the single easiest way to get this feature wrong and it is spelled out in the spec §7.
- **`sec-admin` does not imply seeing data.** Capability and visibility are separate axes;
  an administrator with no group memberships administers an application showing them nothing. That is
  separation of duties working, not a bug — and it will be reported as a bug at least once.
- **Import gets a reconciliation step**, in-process for JIRA and Windchill, and an explicit
  `POST /api/v1/access/reconcile` for the out-of-process DOORS and Cameo importers, which cannot call
  back into a backend that may not be running.
- **A user with no group memberships sees an empty application.** By design, including for Windchill
  documents, which are "visible to everyone" by way of a category granted to every group — not by way
  of a bypass.

---

## 8. Phase 2 — the predicate form, measured

Phase 2 (`docs/features/access-control.md` §15) built the model (`:__AccessCategory`, `:__Group`,
`__inAccessCategory`, `__mayRead`), `AccessResolver` (§5), and `AccessCypher.visible()` (§6.1),
and applied the predicate to exactly one endpoint, `/modules/{ref}/objects`, per the phase's own
scope. `AccessGuardTest` names every other statement touching `:SEItem` or a type label with a
reason — phase 4 for reads, phase 5 for writes — so the boundary is enforced rather than
remembered.

**Form A, measured, and it stays.** §6.1 asked for both candidate forms to be `PROFILE`d against a
module shaped like the reference export (984 objects) before the choice was settled. Session 24 had
no Docker/Podman daemon reachable and shipped form A provisionally; session 25's environment did,
and this paragraph is that measurement, not the deferral of it.

```cypher
-- A (ships)
WHERE $seesAll OR EXISTS {
  (n)-[:__inAccessCategory]->(c:__AccessCategory) WHERE c.__metaId IN $acl
}

-- B (measured, not taken)
MATCH (c:__AccessCategory) WHERE c.__metaId IN $acl
WITH collect(c) AS acl
...
WHERE $seesAll OR any(cat IN acl WHERE EXISTS { (n)-[:__inAccessCategory]->(cat) })
```

**Method.** No DOORS export was reachable from this machine either (the importer is Windows-only,
CLAUDE.md §1), so the reference module was reproduced synthetically rather than imported: 984
`:DOORSObject:DOORSRequirement:SEItem` nodes on one `__moduleUrl`, tagged one-to-one across 5
`:__AccessCategory` nodes, alongside 4 000 decoy objects spread over 6 other module URLs so
`__moduleUrl` carries the selectivity it would in a multi-module deployment — without the decoys the
planner answers a single-module database with a full label scan instead of the `doors_object_module`
index seek a real one would use, which would have measured an artifact of the fixture rather than
the predicate. Same schema as production (`importers/src/sec_import/doors/schema.py`,
`meta/MetaSchema.kt`), a disposable `neo4j:2026.06.0-community` container, `cypher-shell PROFILE`,
`$seesAll = false` throughout — `true` short-circuits both forms identically and measures nothing.

**Results** — `COUNT_MODULE_OBJECTS`, total db hits, by size of `$acl` (the count of categories the
caller's groups collectively grant, not the count of categories on any one object):

| `$acl` size | rows visible | form A | form B | delta |
|---|---|---|---|---|
| 1 category | 197 (20%) | 4 525 | 4 340 | B −4% |
| 2 categories | 393 (40%) | 5 116 | 7 098 | A −28% |
| 4 categories | 787 (80%) | 6 298 | 10 841 | A −42% |

`MODULE_OBJECTS` (the full statement — document order, references, the comment join, `LIMIT 50`) at
2 categories: **A 6 509, B 8 491 — A −23%**, the same direction as the isolated count.

**Why, and why the a-priori guess was wrong.** §6.1 expected B to win because it reads no property
in the inner loop. It doesn't — but `any(cat IN acl WHERE EXISTS {...})` opens one relationship-
existence subquery **per entry in `$acl`**, per candidate row: the `Apply` plan literally repeats the
probe once per granted category. Form A's `EXISTS { ... WHERE c.__metaId IN $acl }` expands each
node's own category edges exactly once regardless of how many categories `$acl` holds, and pays a
property read only for the edges that actually exist. Cost in A tracks *edges per object* (fixed
here at 1); cost in B tracks *edges per object × size of `$acl`*. A caller's `$acl` is every category
granted to every group they belong to (§5) — plausibly several in a real deployment — so B's
crossover win at a single category is the narrow case, not the common one.

**Decision: form A ships unchanged.** No call site changes; `AccessCypher.visible()` is not edited.

**What phase 2 did verify, and is now also confirmed live**: `AccessGuardTest`'s deliberate-break
check (`GraphNamesTest`'s verification pattern) held under static analysis in session 24; session 25
ran `mvn -Pdocker test` against a real `neo4j:2026.06.0-community` — **521 docker-tagged tests, 0
failures**, `AccessControlFeatureTest` among them. The visibility-matrix acceptance line — *a module
tagged by hand in Cypher is visible to one group and invisible to another* — is no longer only
written and compiling; it has been watched to pass against a real database.
