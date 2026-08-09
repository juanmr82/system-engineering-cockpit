"""The reconciliation statements, read as text.

Phase 6 makes its decisions in Cypher rather than in Python, which is what keeps a re-import to
one round trip per statement and no parameter that grows with the module (ADR 0012). The cost of
that choice is that the decisions cannot be exercised without a database, and there is no Neo4j
in this test suite -- so what follows asserts the clauses those decisions *are*, one test per
clause, each named after the thing that breaks if the clause goes missing.

These are not a substitute for running an import. They are a substitute for nothing at all: every
clause below was deliberate, several are one word long, and a plausible-looking edit that drops
one leaves an importer that still runs, still reports success, and quietly destroys the links
this whole design exists to keep.
"""
from __future__ import annotations

import re

from sec_import.doors.importer import (
    _COLLECT_GHOSTS,
    _COLLECT_PLACEHOLDERS,
    _DELETE_GHOST_META,
    _DELETE_STALE_CHILD,
    _DELETE_STALE_REFERS_TO,
    _MARK_DELETED,
    _MERGE_CHILD,
    _MERGE_INCOMING,
    _MERGE_OBJECTS_TPL,
    _MERGE_REFERS_TO,
    _STRIP_GHOST_EDGES,
)

RECONCILE_STATEMENTS = [
    _MARK_DELETED,
    _DELETE_STALE_CHILD,
    _DELETE_STALE_REFERS_TO,
    _DELETE_GHOST_META,
    _STRIP_GHOST_EDGES,
    _COLLECT_GHOSTS,
    _COLLECT_PLACEHOLDERS,
]


def test_every_statement_pins_the_language_version() -> None:
    """CLAUDE.md section 5: the server default depends on how the database was created."""
    for statement in RECONCILE_STATEMENTS:
        assert statement.startswith("CYPHER 25")


def test_reconciliation_sends_no_parameter_that_grows_with_the_module() -> None:
    """The performance contract, stated as a test.

    The design this replaced read every id and every edge of the module before writing anything,
    then sent the difference back. The reason phase 6 is worth having is that it does not: the
    only parameters it may take are the module it is about and the stamp of the run in hand, so a
    module of 977 objects and one of 97 700 cost the same to reconcile.
    """
    allowed = {"module_url", "imported_at"}
    for statement in RECONCILE_STATEMENTS:
        assert set(re.findall(r"[$](\w+)", statement)) <= allowed


def test_marking_survives_a_node_written_before_the_run_stamp_existed() -> None:
    """`NULL <> $ts` is NULL, and NULL matches no WHERE clause.

    Without the coalesce, the first re-import after this feature ships would decide that every
    object in the module was still current -- and, worse, would keep deciding it for any object
    whose stamp had somehow gone missing.
    """
    assert "coalesce(n.__importedAt, '')" in _MARK_DELETED


def test_marking_leaves_the_module_node_alone() -> None:
    """A module node carries :DOORSObject too, and an export always contains its own module."""
    assert "NOT n:DOORSModule" in _MARK_DELETED


def test_only_real_doors_objects_are_ever_marked_or_swept() -> None:
    """`:__DELETED` belongs on `:SEItem:DOORSObject:<type>` nodes and on nothing else.

    A placeholder is excluded by the label rather than by a clause -- it is created as
    `:SEItem:__UNDEFINED` and never carries `:DOORSObject` -- and one carrying this module's
    `__moduleUrl` is some *other* module's assertion that a link points here, so marking it would
    be this import passing judgement on data it does not own.
    """
    for statement in (_MARK_DELETED, _DELETE_GHOST_META, _STRIP_GHOST_EDGES, _COLLECT_GHOSTS):
        assert "DOORSObject" in statement


def test_marking_adds_a_label_and_removes_none() -> None:
    """A ghost keeps every label it had -- :DOORSObject above all.

    That is what lets the review table, the breakdown tree and the statistics read a deleted
    object with the queries they already have, and say which requirement went away.
    """
    assert "SET n:`__DELETED`" in _MARK_DELETED
    assert "REMOVE" not in _MARK_DELETED


def test_stale_link_pruning_spares_the_ghosts() -> None:
    """The clause the whole design rests on.

    A deleted object reports no links, so none of its links are ever re-stamped, so without this
    exclusion the pruning step would delete precisely the stale links the ghost exists to expose
    -- and the graph would then agree with DOORS that nothing is wrong.
    """
    assert "NOT s:`__DELETED`" in _DELETE_STALE_REFERS_TO


def test_ghosts_keep_only_their_links_to_other_doors_objects() -> None:
    """Anything else cannot be phrased as "a link to fix in DOORS", which is the only offer made."""
    assert "NOT (type(r) = 'refersTo' AND o:DOORSObject)" in _STRIP_GHOST_EDGES


def test_annotations_are_deleted_with_the_object_they_were_written_on() -> None:
    """The one circumstance in which an importer deletes Tier-2 data (CLAUDE.md R2).

    A note is about a requirement. When DOORS no longer has the requirement, the note is about
    nothing, so it goes rather than being kept alive on a ghost. DETACH because the meta node owns
    the edge that reached it, and because a `:__Link` reaches two items of which only one is going.
    """
    assert "(m:`__Meta`)" in _DELETE_GHOST_META
    assert "DETACH DELETE m" in _DELETE_GHOST_META
    # And nothing anywhere else exempts them, which is what the previous design did.
    assert "__Meta" not in _STRIP_GHOST_EDGES
    assert "__Meta" not in _COLLECT_GHOSTS


def test_an_unlinked_ghost_is_deleted_and_unlinked_means_no_edges_at_all() -> None:
    """A deleted object is kept for one reason: something still links to it.

    Once the last edge is gone it stands for nothing, and no view can ever reach it. "Unlinked" is
    meant literally here -- an undirected count of every edge, not a count of edges to objects
    that still exist -- so a pair of deleted objects linking only to each other is kept.
    """
    assert "COUNT { (n)--() } = 0" in _COLLECT_GHOSTS
    assert "DELETE n" in _COLLECT_GHOSTS


def test_collection_is_not_scoped_to_one_module() -> None:
    """Re-importing one module is what strands a ghost belonging to another.

    Both collection statements are label scans rather than module-scoped matches, deliberately:
    the last live link to a ghost in module B is removed by an import of module A, and a
    B-scoped statement would never see it.
    """
    for statement in (_COLLECT_GHOSTS, _COLLECT_PLACEHOLDERS):
        assert "__moduleUrl" not in statement


def test_an_object_that_comes_back_stops_being_a_ghost() -> None:
    """Undeleted in DOORS, or restored from a baseline: the export mentioning it is enough."""
    merge = _MERGE_OBJECTS_TPL.format(label_str="DOORSObject:DOORSRequirement", deleted="__DELETED")
    assert "REMOVE n:`__UNDEFINED`:`__DELETED`" in merge
    assert "SET n.__importedAt = $imported_at" in merge


def test_relationships_are_stamped_on_every_run_not_only_when_created() -> None:
    """The stamp is what dates a relationship, so an ON CREATE stamp would date it once.

    Every `refersTo` in the module would then look stale on the second import and be deleted --
    the failure is total, immediate, and invisible in a first-import test.
    """
    assert "SET r.__importedAt = $imported_at" in _MERGE_CHILD
    assert "SET r.__importedAt = row.imported_at" in _MERGE_REFERS_TO
    on_create, _, after = _MERGE_REFERS_TO.rpartition("ON CREATE SET")
    assert "__importedAt" not in after.split("SET r.__importedAt")[0]
    assert on_create  # the sourceModuleUrl stamp is still write-once


def test_an_incoming_link_creates_the_source_it_names() -> None:
    """`__inputLinks` is the only witness to a link before its own module is imported.

    A graph grows one module at a time, so for most of a project's life the module that refines a
    requirement has not been imported when that requirement is read. Without this, the reviewer
    sees silence and cannot tell it from "nothing refines this" -- opposite conclusions, and the
    wrong one is expensive in a requirements tool.

    The target is MATCHed (this run has just written it) and the source is MERGEd, because it may
    be an object no import has ever reached. It is the ON CREATE branch that makes the placeholder,
    so a source that *has* been imported keeps everything its own export gave it -- including its
    name, and including a `:__DELETED` label if DOORS has since deleted it.
    """
    assert "MATCH (t:SEItem {__id: row.target_id})" in _MERGE_INCOMING
    assert "MERGE (s:SEItem {__id: row.source_id})" in _MERGE_INCOMING
    assert "s:`__UNDEFINED`" in _MERGE_INCOMING


def test_an_incoming_link_is_stamped_like_any_other_relationship() -> None:
    """So the source module governs it from the moment that module arrives.

    The edge carries this run's stamp, so the reconciliation immediately following does not prune
    it. When the source module is eventually imported the placeholder becomes a real object, and
    its own export becomes authoritative for its outgoing links -- an unstamped edge is then one it
    no longer asserts, and it goes. No arbitration rule is needed for that, and none exists.
    """
    assert "SET r.__importedAt = row.imported_at" in _MERGE_INCOMING
