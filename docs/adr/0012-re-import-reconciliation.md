# ADR 0012: An object deleted in DOORS is labelled, not removed

Status: accepted
Date: 2026-08-09

## Context

Until now every DOORS importer statement was a `MERGE`. Re-importing a module therefore added and
updated and never removed: a requirement deleted in DOORS stayed in the graph as though it still
existed, a traceability link a user removed stayed drawn, and an object renumbered into a different
part of the outline acquired a **second** `__child` parent and appeared twice in the tree.

An export is the authoritative statement of what a module contains *now*, so a re-import has to
remove what the export no longer mentions. One thing makes that harder than a set difference, and
it is the only thing that does.

### DOORS deletes an object and keeps the links to it

A requirement in one module refines a requirement in another. Somebody deletes the second one.
DOORS removes the object and leaves the link, so the first requirement goes on asserting that it
refines something that does not exist.

That is a real defect in the requirements data, and it is invisible in DOORS itself — which is
exactly the kind of thing this application exists to surface. It also decides the whole design,
because the obvious implementation destroys the evidence: delete the object here too, and the
referencing module looks correct again. The graph would have quietly agreed with DOORS that
nothing is wrong.

There is a second reason not to delete, and it is about other modules rather than this one.
`DETACH DELETE` on an object takes with it every `refersTo` edge pointing at it — edges asserted by
a *different* module's export, which only that module's re-import can restore. Deleting one
module's objects would therefore silently degrade every module that links to them, and the damage
would not be visible from the module that caused it.

## Decision

**An object that an export stops mentioning keeps everything it has and gains one label:
`:__DELETED`.** It is not stripped, not demoted, not replaced by a placeholder. It is still a
`:SEItem:DOORSObject:DOORSRequirement` with its `id`, its `Object Text` and its attributes, and
every projection that could describe it before can still describe it — which is what lets the
review table, the breakdown tree and the statistics say *which* requirement went away rather than
just that one did.

What it loses is its place in the module. It leaves the tree, it stops being listed as part of the
module, and it keeps only the links a reviewer can act on.

The decision is made in Cypher, in seven set-based statements, on the back of a **run stamp**.
`__importedAt` is written on every object, every `__child` and every `refersTo` this run confirms,
and anything still carrying an older stamp when the merge phases are done is something this export
did not mention. They are numbered 1 to 6 below because step 4 is one decision taken in two
statements — delete the annotations, then strip what is left.

1. **Mark.** Objects of this module not stamped by this run get `:__DELETED`. Re-merging an object
   removes the label again, so an object undeleted in DOORS — or restored from a baseline — comes
   back with its identity, its attributes and the links it still had. Not its annotations: those
   were deleted by step 4 of the run that first marked it, and nothing can bring them back.
2. **Prune the hierarchy.** Every `__child` into this module's objects that this run did not
   re-stamp is deleted. That is one statement for two cases: an object the export re-parented, and
   a ghost, which leaves the tree without being named.
3. **Prune traceability**, for objects the export still describes. A ghost is excluded by label,
   and that exclusion is the design rather than an optimisation: a deleted object reports no links,
   so none of its links are ever re-stamped, and without the clause the first thing deleted would
   be the stale links the ghost exists to expose.
4. **Delete the annotations, then strip what a ghost may not keep.** A note, a review verdict or
   a hand-drawn link is *about a requirement*; when DOORS no longer has the requirement it is
   about nothing, so it is deleted rather than kept alive on a node that is itself on its way out.
   What survives is `refersTo` to and from other DOORS objects, and nothing else — those are the
   edges a reviewer can act on, because both ends are DOORS and the fix is a link to remove there.
   An edge to a placeholder, or to a future Windchill document, cannot be corroborated by any DOORS
   export and cannot be phrased as that instruction.
5. **Collect the ghosts nothing points at** — no edges at all, in either direction. A deleted
   object is kept for exactly one reason: something still links to it, and that link is the
   finding. Once the last edge is gone it stands for nothing and no view can reach it.
6. **Collect placeholders that lost their last edge**, for the same reason.

Statements 5 and 6 are **not scoped to the module being imported**, deliberately: re-importing one
module is exactly what removes the last live link to a ghost belonging to a different one.

Ghosts are excluded from every module listing — the review table, the statistics scan, attribute
discovery, table reconstruction. A module listing that still contained them would be showing a
document DOORS does not have. They are reached only from the links that point at them.

### Both link lists are imported

`__outputLinks` is what a module asserts. `__inputLinks` is what other modules assert about it,
and it is imported too, in a phase between the outgoing links and the reconciliation so its edges
carry this run's stamp.

It is not redundant with the outgoing side, because the two are visible at different times. A graph
grows one module at a time, so for most of a project's life the module that refines a requirement
has not been imported when that requirement is read. Without the target side, the reviewer sees
silence and cannot tell it from "nothing refines this" — opposite conclusions, and the wrong one is
expensive. With it, the link is drawn and its source is a placeholder reading *Not yet imported*.

The source module stays authoritative for its own outgoing links: when it is eventually imported,
the placeholder becomes a real object and step 3 prunes anything that module does not assert. No
arbitration rule is needed for that, because the pruning is scoped by `__moduleUrl` and a
placeholder belongs to no imported module.

**This closes `incomingComplete`.** A module's export states every link pointing at it, so the
incoming list is as complete as that export. The standing "only outgoing links are imported"
caveat has been removed from the review table's header tooltip and from the dependency graph —
leaving it would tell a reviewer to distrust an emptiness that now carries real information.

### What the user sees

| Where | What it says |
|---|---|
| References column | the target's id, struck through, in error red, not a link |
| Issues column | *n links to or from objects deleted in DOORS* |
| Req review filter | **Links to deleted objects** |
| Requirement card | **Deleted in DOORS**, beside the id, with the card otherwise complete |
| Statistics, Traceability band | how many such links exist, next to the dangling-link count and never merged into it |

Every one of those says the fix is in DOORS, because it is: this application has no copy of the
link to remove.

## Consequences

**The graph keeps a growing population of ghosts, and that is the point.** These links exist in
DOORS. Identifying them and naming them is the deliverable, not a side effect, and rule 5 is what
stops the population growing without bound — a ghost nothing points at is collected on the next run
that touches either end.

**`:__DELETED` is the one Tier-1 name that is not a function of a single export.** It is a function
of two: the export in hand and whatever the graph already held. That is a real departure from R1's
"delete it, re-run the import, get byte-identical results", and it is unavoidable — the fact being
recorded is a *difference between two imports*, and nothing computable from one export can express
it. It stays Tier 1 rather than becoming `:__Meta` because the importer owns it, the application
never writes it, and a re-import that finds the object again removes it.

**A truncated or view-filtered export costs annotations, and nothing else.** It marks a large part
of the module deleted and prunes the hierarchy; re-importing the complete export puts the source
data back in full, because every object it lists is re-merged, un-labelled and re-parented, and the
objects statement 5 collected are recreated from the export's own data. What does not come back is
the Tier 2 attached to those objects, which step 4 deleted — the one thing no export can rebuild.

That is the real cost of having no prune guard, and it is accepted with open eyes rather than
overlooked: a guard would trade it for a `--force` flag people learn to always pass, which is worse
than no guard because it trains the reflex that defeats it. The mitigation that matters is the
report — `objects_newly_deleted` is printed on every run, and a sudden large one is exactly what a
truncated export looks like.

**Re-import stays one round trip per statement.** No parameter grows with the module, so a module
of 977 objects and one of 97 700 cost the same to reconcile. The price is one property written per
node and per relationship per run, on writes that were happening anyway.

**An annotation cannot outlive its subject.** This is the one circumstance in which an importer
deletes Tier-2 data, and root `CLAUDE.md` R2 names it as such rather than leaving it as an
undocumented exception. The alternative — an orphaned note anchored to a node no export will ever
mention again — is worse in both directions: it breaks R2's invariant that every meta node hangs
off the imported graph, and it keeps a ghost alive that nothing else has a reason to keep.

**Two deleted objects that link only to each other are kept.** Step 5 asks whether a ghost has any
edge at all, not whether it has one to something that still exists, so such a pair survives and
nobody is ever shown it. A deliberate call: the stronger rule was tried and rejected, because
"unlinked" is a fact about the graph while "unreachable from a live object" is a judgement about
what is worth showing, and the first is the one an importer should be making.

## Rejected alternatives

**Deleting the object outright.** Simplest, and truest to "it is gone in DOORS" — and it erases the
finding. The referencing module goes back to looking correct, and the defect is undetectable from
either side.

**Demoting it to a `:__UNDEFINED` placeholder** (the first version of this ADR). It keeps the ghost
but throws away what makes it useful: the type labels, the attributes, the statement. Worse, it
overloads a state that means the opposite thing — "not yet imported" invites an import, and this
object is in a module already imported — so every view had to carry a second flag to tell two
placeholders apart, and the wording had to be chosen twice on every screen. Keeping the labels and
adding one is strictly less machinery and strictly more information.

**Reading the module's ids and edges before writing, and diffing them in Python.** What the first
version did. It makes the decision unit-testable without a database and lets `--dry-run` print
exactly what a run would destroy, which is genuinely worth something. It also sends every id and
every edge of the module over the wire on every import, puts a read round trip in front of every
run, and duplicates in Python a set difference the database can do in one indexed statement. What
it was really protecting was the irreversibility of the old design's deletions; marking is
reversible for everything except annotations, and the run report names how many objects were newly
marked, which is the number a dry run would have been read for.

**A prune guard refusing to remove more than *n*% of a module.** It exists to stop a truncated
export emptying a module silently, and under the previous design it was necessary because the
damage was irreversible. Marking very nearly is not: the objects keep their data and the next
complete export restores them, with annotations the one exception (see Consequences). A guard would
buy a confirmation prompt at the cost of a `--force` flag people learn to always pass — worse than
no guard, because it trains the reflex that defeats it. If the annotation loss ever bites in
practice, the proportionate answer is to refuse to *delete Tier 2* above a threshold, not to refuse
the import.

**Keeping a ghost's annotations.** The first version of this decision spared `:__Meta` from the
strip and treated an annotated ghost as worth keeping, reading R2 as forbidding the deletion of
Tier 2 under any circumstance. R2 forbids the *application* writing to imported nodes, and forbids
a meta node hanging off nothing; it does not require an annotation to outlive its subject. The
sparing rule produced the orphan it was meant to avoid — a note anchored to an object no export
will mention again, kept alive solely by the note.

**Storing why a placeholder is unresolved.** R2 forbids stored derivations, and this one is a hop
from data already in the graph.
