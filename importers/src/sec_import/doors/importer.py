from __future__ import annotations
import logging
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

from neo4j import Driver, ManagedTransaction

from .derivations import (
    OBJECT_META_KEYS,
    compute_table_sets,
    derive_labels,
    derive_name,
    derive_type_label,
    parent_number,
    sort_key,
    target_object_url,
    target_version,
)
from .exceptions import ImportValidationError, MalformedUrlError
from .reporter import ImportCounters, ImportReport

logger = logging.getLogger(__name__)

# Closed set of all valid labels -- nothing else may appear in generated Cypher
_VALID_LABELS: frozenset[str] = frozenset({
    "SEItem", "DOORSModule", "DOORSObject",
    "DOORSHeading", "DOORSAppMatrixHeading", "DOORSRequirement",
    "DOORSInformation", "DOORSAppMatrix", "DOORSTBD",
    "__UNDEFINED", "DOORSTableCell", "DOORSTable", "DOORSTableRow",
})

# Not one of the labels above, because derive_labels can never produce it: it is set by the
# reconciliation phase on an object the export stopped mentioning, and removed again if that
# object comes back. It sits *alongside* the labels the object already had -- :DOORSObject and
# its type label included -- rather than replacing them, and that is the whole design: a ghost is
# still recognisably the DOORS requirement it was, so every view that can read one can say what
# went away and which link a reviewer has to fix in DOORS (ADR 0012).
_DELETED_LABEL = "__DELETED"

# The Tier-2 label. The importers never write it and never delete it -- it is named here only so
# the ghost sweep leaves a user's annotations, and the edges carrying them, alone (R2).
_META_LABEL = "__Meta"


def _batches(items: list, size: int):
    for i in range(0, len(items), size):
        yield items[i : i + size]


def _label_str(label_set: frozenset[str]) -> str:
    """Build a ':'-joined label string excluding SEItem (already in MERGE)."""
    extra = sorted(label_set - {"SEItem"})
    assert all(lbl in _VALID_LABELS for lbl in extra), f"Invalid label(s): {extra}"
    return ":".join(extra)


def _coerce_int(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (ValueError, TypeError):
        return None


def _prepare_module_props(data: dict) -> dict:
    props: dict[str, Any] = {}
    for key, value in data.items():
        if key == "__contents":
            continue
        props[key] = value
    props["__id"] = data["url"].strip()
    props["__name"] = data["__name"]
    props["__version"] = data["__version"]
    return props


def _prepare_object_props(obj: dict, module_version: str) -> dict:
    props: dict[str, Any] = {}

    # Universal metadata
    props["__id"] = obj["__objectUrl"]
    props["__name"] = derive_name(obj)
    props["__version"] = module_version

    # Source-native metadata
    props["id"] = obj.get("id") or ""
    props["objectNumber"] = obj.get("objectNumber") or ""
    props["__moduleUrl"] = obj.get("__moduleUrl") or ""
    props["__objectUrl"] = obj.get("__objectUrl") or ""

    # Type coercions
    ol = _coerce_int(obj.get("objectLevel"))
    if ol is not None:
        props["objectLevel"] = ol

    table_obj_str = obj.get("__tableObject") or "false"
    props["__tableObject"] = table_obj_str == "true"
    props["__tableID"] = obj.get("__tableID") or ""
    props["__tableURL"] = obj.get("__tableURL") or ""

    ri = _coerce_int(obj.get("__tableRowIndex"))
    if ri is not None:
        props["__tableRowIndex"] = ri

    ci = _coerce_int(obj.get("__tableColumnIndex"))
    if ci is not None:
        props["__tableColumnIndex"] = ci

    # Importer-added
    props["__sortKey"] = sort_key(obj.get("objectNumber") or "")

    # Unknown Object Type -> store raw value
    object_type = obj.get("Object Type") or ""
    _, is_unknown = derive_type_label(object_type)
    if is_unknown:
        props["__typeRaw"] = object_type

    # All DOORS attributes (keys not in metadata set, not links)
    skip = OBJECT_META_KEYS
    for key, value in obj.items():
        if key in skip:
            continue
        if key == "Absolute Number":
            coerced = _coerce_int(value)
            props[key] = coerced if coerced is not None else value
        else:
            props[key] = value

    # Remove empty-string values and None that add no information (keep False, 0)
    return {k: v for k, v in props.items() if v != "" and v is not None}


# --------------------------------------------------------------------------- #
# Cypher statements
# --------------------------------------------------------------------------- #

_MERGE_MODULE = """\
CYPHER 25
MERGE (n:SEItem {__id: $__id})
SET n:DOORSModule
SET n += $props"""

# {label_str} and {deleted} are the only interpolated parts; Cypher map literals use {{ }}.
#
# __importedAt is the run stamp, and it is what lets the reconciliation phase below be a property
# comparison the database makes by itself rather than a thousand ids sent over the wire and
# diffed. It reads as "last seen in an export": an object still carrying an older stamp when this
# run is finished is one this export no longer contains.
#
# REMOVE takes :__DELETED off as well. An object that reappears in DOORS -- undeleted, or put
# back from a baseline -- stops being a ghost the moment an export mentions it again, and it
# still has the identity, the annotations and the links it never lost.
_MERGE_OBJECTS_TPL = """\
CYPHER 25
UNWIND $rows AS row
MERGE (n:SEItem {{__id: row.__id}})
REMOVE n:`__UNDEFINED`:`{deleted}`
SET n:{label_str}
SET n += row.props
SET n.__importedAt = $imported_at"""

# MATCH (not MERGE) for both ends -- they must exist from earlier phases
_MERGE_CHILD = """\
CYPHER 25
UNWIND $rows AS row
MATCH (p:SEItem {__id: row.parent_id})
MATCH (c:SEItem {__id: row.child_id})
MERGE (p)-[r:__child]->(c)
SET r.__importedAt = $imported_at"""

_MERGE_REFERS_TO = """\
CYPHER 25
UNWIND $rows AS row
MATCH (s:SEItem {__id: row.source_id})
MERGE (t:SEItem {__id: row.target_id})
ON CREATE SET
    t:`__UNDEFINED`,
    t.__objectUrl    = row.target_id,
    t.__moduleUrl    = row.target_module_url,
    t.absoluteNumber = row.absolute_number,
    t.__name         = row.target_name,
    t.__version      = row.target_version
MERGE (s)-[r:refersTo]->(t)
ON CREATE SET r.__sourceModuleUrl = row.source_module_url
SET r.__importedAt = row.imported_at"""

# An edge the *target* asserts, from its own __inputLinks. The mirror of _MERGE_REFERS_TO with the
# ends swapped: the target is MATCHed because this run has just written it, and the source is
# MERGEd because it may be an object no import has ever reached.
#
# This is what makes an incoming link visible at all when the referencing module has not been
# imported. Without it a reviewer reading a requirement cannot tell "nothing refines this" from
# "the module that refines this has not been imported yet", and those are opposite conclusions.
# The placeholder it leaves behind renders as *Not yet imported*, which is exactly that
# distinction on screen.
#
# Stamped like every other relationship, so that the source module's own import governs it from
# then on: once that module arrives, its export is authoritative for its outgoing links and
# phase 6 prunes anything it does not assert.
_MERGE_INCOMING = """\
CYPHER 25
UNWIND $rows AS row
MATCH (t:SEItem {__id: row.target_id})
MERGE (s:SEItem {__id: row.source_id})
ON CREATE SET
    s:`__UNDEFINED`,
    s.__objectUrl    = row.source_id,
    s.__moduleUrl    = row.source_module_url,
    s.absoluteNumber = row.absolute_number,
    s.__name         = row.source_name,
    s.__version      = row.source_version
MERGE (s)-[r:refersTo]->(t)
ON CREATE SET r.__sourceModuleUrl = row.source_module_url
SET r.__importedAt = row.imported_at"""


# --------------------------------------------------------------------------- #
# Phase 6: reconciliation (ADR 0012)
#
# The export says what the module contains now; the run stamp says what this run confirmed.
# Everything below is the difference between those two, expressed as six set-based statements.
#
# The one thing none of them does is make a deleted object go away. DOORS deletes an object and
# keeps the links pointing at it, so a requirement that no longer exists is still referenced by
# requirements that do -- and that stale link is a defect in the source data, not in the graph.
# Erasing the object would erase the evidence and leave the referencing module looking correct.
# So the object stays, labelled, with its DOORS labels and its source attributes intact, until
# nothing points at it any more.
# --------------------------------------------------------------------------- #

# 1. Mark. `coalesce` rather than a bare `<>` because a node written before the run stamp existed
#    has NULL there, and `NULL <> $ts` is NULL, which no WHERE clause ever matches -- the whole
#    module would silently survive its first reconciliation. The module node itself is excluded:
#    it carries :DOORSObject too, and an export always contains the module it is an export of.
_MARK_DELETED = f"""\
CYPHER 25
MATCH (n:DOORSObject {{__moduleUrl: $module_url}})
WHERE NOT n:DOORSModule AND coalesce(n.__importedAt, '') <> $imported_at
SET n:`{_DELETED_LABEL}`
RETURN count(*) AS ghosts"""

# 2. Stale hierarchy, for the module in one statement. Scoped to __child edges arriving *at* this
#    module's objects, which is every __child a re-import of it can invalidate -- an object's
#    parent is always another object of the same module, or the module node.
#
#    That scope covers the ghosts too, in both directions and without naming them: the edge to a
#    ghost's old parent was not re-stamped, and neither was the edge from a ghost to a child that
#    survived, because the export re-attached that child to a parent it does still contain. A
#    ghost therefore leaves the tree here. It has to: it has no place in a document order the
#    source no longer gives it, and a tree that still contains it would show DOORS a structure
#    DOORS does not have.
_DELETE_STALE_CHILD = """\
CYPHER 25
MATCH ()-[r:__child]->(c:DOORSObject {__moduleUrl: $module_url})
WHERE coalesce(r.__importedAt, '') <> $imported_at
DELETE r"""

# 3. Stale traceability, for the objects the export still describes. A link the export stopped
#    asserting is a link a user removed in DOORS, and it goes.
#
#    Ghosts are excluded by label, and that exclusion is the point of the whole design rather
#    than an optimisation: a ghost's links were not re-stamped either, because a deleted object
#    reports no links at all, so without this clause the very links worth keeping would be the
#    first thing deleted.
_DELETE_STALE_REFERS_TO = f"""\
CYPHER 25
MATCH (s:DOORSObject {{__moduleUrl: $module_url}})-[r:refersTo]->()
WHERE NOT s:`{_DELETED_LABEL}` AND coalesce(r.__importedAt, '') <> $imported_at
DELETE r"""

# 4a. The annotations go with the object. A note, a review verdict or a hand-drawn link is about
#     a requirement, and when DOORS no longer has that requirement the annotation is about
#     nothing -- so it is deleted outright rather than left hanging off a ghost that is itself on
#     its way out.
#
#     This is the **one** circumstance in which an importer deletes Tier-2 data, and CLAUDE.md R2
#     names it as such. Everywhere else the rule stands unchanged: a re-import merges, and the
#     `:__Meta` nodes hanging off the objects it touches are not its business.
#
#     DETACH because the meta node owns the edge that reached it, and because a `:__Link` reaches
#     two items and only one of them is going.
_DELETE_GHOST_META = f"""\
CYPHER 25
MATCH (n:DOORSObject:`{_DELETED_LABEL}` {{__moduleUrl: $module_url}})--(m:`{_META_LABEL}`)
DETACH DELETE m"""

# 4b. What a ghost is allowed to keep: `refersTo` to and from other DOORS objects, and nothing
#     else. Those are the edges a reviewer can act on -- both ends are DOORS, so the fix is a link
#     to remove in DOORS. An edge to anything else cannot be corroborated by any DOORS export and
#     cannot be phrased as that instruction: a placeholder for a module nobody has imported, or,
#     once a second source arrives, a Windchill document or a Cameo function.
_STRIP_GHOST_EDGES = f"""\
CYPHER 25
MATCH (n:DOORSObject:`{_DELETED_LABEL}` {{__moduleUrl: $module_url}})-[r]-(o)
WHERE NOT (type(r) = 'refersTo' AND o:DOORSObject)
DELETE r"""

# 5. Collect the ghosts nothing points at any more. A deleted object is kept for exactly one
#    reason -- some object still links to it, and that link is the finding -- so once the last
#    edge is gone it stands for nothing and the graph stops carrying it.
#
#    "Unlinked" is meant literally: no edges at all, in either direction. By this point statement
#    4a has taken its annotations and 4b everything but its links to other DOORS objects, so what
#    remains is the honest question.
#
#    Not scoped to this module, on purpose. Re-importing one module is exactly what removes the
#    last link to a ghost belonging to a different one, and only a global statement sees it. It
#    is a scan of the two labels that only these objects carry, so it costs the size of the
#    problem rather than the size of the graph.
_COLLECT_GHOSTS = f"""\
CYPHER 25
MATCH (n:DOORSObject:`{_DELETED_LABEL}`)
WHERE COUNT {{ (n)--() }} = 0
DELETE n"""

# 6. The same for placeholders, which are the other way a node can be left standing for nothing:
#    the link that created it was the last one, and this run removed it. A placeholder that is
#    still linked stays exactly as it was and still reads as "not yet imported" -- including when
#    the module it names has since been imported without it, which is a case an import cannot
#    tell from a module still waiting to be imported, and does not try to.
_COLLECT_PLACEHOLDERS = """\
CYPHER 25
MATCH (n:`__UNDEFINED`)
WHERE COUNT { (n)--() } = 0
DELETE n"""


def run_import(
    driver: Driver,
    database: str,
    data: dict,
    parse_entries: list,
    batch_size: int,
    dry_run: bool,
    report: ImportReport,
) -> None:
    """Execute phases 0 (pre-validated by parser) through 6."""
    counters = report.counters
    module_name = data["__name"]
    module_version = data["__version"]
    module_url = data["url"].strip()
    contents: list[dict] = data["__contents"]
    import_ts = datetime.now(timezone.utc).isoformat()

    counters.module_name = module_name
    counters.module_version = module_version
    counters.objects_read = len(contents)

    # ------------------------------------------------------------------ #
    # Phase 0 addendum: in-memory index and hierarchy / level checks
    # ------------------------------------------------------------------ #
    num_to_obj: dict[str, dict] = {}
    for obj in contents:
        num = obj.get("objectNumber") or ""
        if num in num_to_obj:
            report.add_anomaly(
                "ERROR", "duplicate_object_number",
                f"Duplicate objectNumber {num!r}",
                object_id=obj.get("id"),
            )
        num_to_obj[num] = obj

    empty_abs_count = 0
    for obj in contents:
        num = obj.get("objectNumber") or ""

        # objectLevel vs dot-segment count
        expected_level = len(num.split(".")) if num else 0
        actual_level = _coerce_int(obj.get("objectLevel")) or 0
        if expected_level != actual_level:
            report.add_anomaly(
                "WARN", "level_mismatch",
                f"objectLevel={actual_level} but objectNumber has {expected_level} segments",
                object_id=obj.get("id"), object_number=num,
            )

        p = parent_number(num)
        if p and p not in num_to_obj:
            report.add_anomaly(
                "WARN", "missing_parent",
                f"Computed parent objectNumber {p!r} not found in module",
                object_id=obj.get("id"), object_number=num,
            )

        if not (obj.get("Absolute Number") or "").strip():
            empty_abs_count += 1

    if empty_abs_count:
        report.add_anomaly(
            "WARN", "empty_absolute_number",
            f"{empty_abs_count} object(s) have an empty Absolute Number attribute",
            count=empty_abs_count,
        )

    # Unknown Object Type warnings
    for obj in contents:
        object_type = obj.get("Object Type") or ""
        _lbl, is_unknown = derive_type_label(object_type)
        if is_unknown:
            report.add_anomaly(
                "WARN", "unknown_object_type",
                f"Unrecognised Object Type {object_type!r} -> DOORSTBD",
                object_id=obj.get("id"), object_number=obj.get("objectNumber"),
            )

    # ------------------------------------------------------------------ #
    # Table sets
    # ------------------------------------------------------------------ #
    table_ids, table_row_ids = compute_table_sets(contents)
    counters.tables = len(table_ids)
    counters.table_rows = len(table_row_ids)
    counters.table_cells = sum(1 for o in contents if o.get("__tableObject") == "true")

    # ------------------------------------------------------------------ #
    # Group objects by label set (one Cypher statement per unique label set)
    # ------------------------------------------------------------------ #
    groups: dict[frozenset[str], list[dict]] = defaultdict(list)
    for obj in contents:
        lbl_set = derive_labels(obj, table_ids, table_row_ids)
        groups[lbl_set].append(obj)

    # Type-label breakdown for the report
    _type_label_set = {
        "DOORSHeading", "DOORSAppMatrixHeading", "DOORSRequirement",
        "DOORSInformation", "DOORSAppMatrix", "DOORSTBD",
    }
    for lbl_set, objs in groups.items():
        for tl in lbl_set & _type_label_set:
            counters.by_type_label[tl] = counters.by_type_label.get(tl, 0) + len(objs)

    # ------------------------------------------------------------------ #
    # Build __child pairs
    # ------------------------------------------------------------------ #
    num_to_url: dict[str, str] = {
        obj["objectNumber"]: obj["__objectUrl"]
        for obj in contents
        if obj.get("objectNumber") and obj.get("__objectUrl")
    }
    child_pairs: list[dict] = []
    for obj in contents:
        num = obj.get("objectNumber") or ""
        child_url = obj.get("__objectUrl") or ""
        if not child_url:
            continue
        p = parent_number(num)
        if p is None:
            # Root object: parent is the module
            child_pairs.append({"parent_id": module_url, "child_id": child_url})
        else:
            parent_url = num_to_url.get(p)
            if parent_url:
                child_pairs.append({"parent_id": parent_url, "child_id": child_url})

    # ------------------------------------------------------------------ #
    # Build refersTo rows, outgoing and incoming
    #
    # Both link lists are read, because they answer different questions and neither substitutes
    # for the other. __outputLinks is what this module asserts. __inputLinks is what other modules
    # assert about it -- and it is the only way an incoming link is visible at all before the
    # referencing module has been imported, which is most of the time in a graph that grows one
    # module at a time.
    #
    # target_object_url derives an object URL from a module URL and an Absolute Number. For an
    # incoming link the object it names is the link's *source*, which is why the same helper is
    # called with the arguments reading the other way round.
    # ------------------------------------------------------------------ #
    refers_rows: list[dict] = []
    for obj in contents:
        source_id = obj.get("__objectUrl") or ""
        for link in obj.get("__outputLinks") or []:
            req_doc_url = (link.get("reqDocumentURL") or "").strip()
            abs_num = (link.get("absoluteNumber") or "").strip()
            if not req_doc_url or not abs_num:
                report.add_anomaly(
                    "WARN", "malformed_link",
                    "Link with empty reqDocumentURL or absoluteNumber",
                    object_id=obj.get("id"),
                )
                continue
            try:
                t_url = target_object_url(req_doc_url, abs_num)
                t_ver = target_version(req_doc_url)
            except MalformedUrlError as e:
                report.add_anomaly(
                    "WARN", "malformed_link_url", str(e),
                    object_id=obj.get("id"),
                )
                continue
            refers_rows.append({
                "source_id": source_id,
                "target_id": t_url,
                "target_module_url": req_doc_url,
                "target_version": t_ver,
                "absolute_number": _coerce_int(abs_num),
                "target_name": f"<unresolved {t_url}>",
                "source_module_url": module_url,
                "imported_at": import_ts,
            })

    incoming_rows: list[dict] = []
    for obj in contents:
        target_id = obj.get("__objectUrl") or ""
        if not target_id:
            continue
        for link in obj.get("__inputLinks") or []:
            req_doc_url = (link.get("reqDocumentURL") or "").strip()
            abs_num = (link.get("absoluteNumber") or "").strip()
            if not req_doc_url or not abs_num:
                report.add_anomaly(
                    "WARN", "malformed_incoming_link",
                    "Incoming link with empty reqDocumentURL or absoluteNumber",
                    object_id=obj.get("id"),
                )
                continue
            try:
                s_url = target_object_url(req_doc_url, abs_num)
                s_ver = target_version(req_doc_url)
            except MalformedUrlError as e:
                report.add_anomaly(
                    "WARN", "malformed_incoming_link_url", str(e),
                    object_id=obj.get("id"),
                )
                continue
            incoming_rows.append({
                "source_id": s_url,
                "target_id": target_id,
                "source_module_url": req_doc_url,
                "source_version": s_ver,
                "absolute_number": _coerce_int(abs_num),
                # Only ever reached ON CREATE, so a source that has been imported keeps the name
                # its own export gave it. R5 keeps this string off the wire -- the API sends null
                # rather than an internal id spelled out.
                "source_name": f"<unresolved {s_url}>",
                "imported_at": import_ts,
            })

    counters.incoming_links_read = len(incoming_rows)

    if dry_run:
        logger.info("Dry run -- skipping all database writes")
        return

    # ------------------------------------------------------------------ #
    # Phase 2: Module node
    # ------------------------------------------------------------------ #
    module_props = _prepare_module_props(data)
    with driver.session(database=database) as session:
        def _write_module(tx: ManagedTransaction):
            result = tx.run(_MERGE_MODULE, __id=module_url, props=module_props)
            return result.consume().counters

        c = session.execute_write(_write_module)
        counters.nodes_created += c.nodes_created
        counters.nodes_updated += max(0, 1 - c.nodes_created)
        logger.info("Phase 2 complete -- module node (%d created)", c.nodes_created)

    # ------------------------------------------------------------------ #
    # Phase 3: Object nodes
    # ------------------------------------------------------------------ #
    with driver.session(database=database) as session:
        for lbl_set, group_objs in groups.items():
            lbl = _label_str(lbl_set)
            cypher = _MERGE_OBJECTS_TPL.format(label_str=lbl, deleted=_DELETED_LABEL)
            rows = [
                {
                    "__id": obj["__objectUrl"],
                    "props": _prepare_object_props(obj, module_version),
                }
                for obj in group_objs
                if obj.get("__objectUrl")
            ]
            for batch in _batches(rows, batch_size):
                def _write_obj(tx: ManagedTransaction, batch=batch, cypher=cypher):
                    result = tx.run(cypher, rows=batch, imported_at=import_ts)
                    return result.consume().counters

                c = session.execute_write(_write_obj)
                counters.nodes_created += c.nodes_created
                counters.nodes_updated += len(batch) - c.nodes_created
                counters.placeholders_upgraded += c.labels_removed
        logger.info("Phase 3 complete -- %d object nodes", len(contents))

    # ------------------------------------------------------------------ #
    # Phase 4: __child relationships
    # ------------------------------------------------------------------ #
    with driver.session(database=database) as session:
        for batch in _batches(child_pairs, batch_size):
            def _write_child(tx: ManagedTransaction, batch=batch):
                result = tx.run(_MERGE_CHILD, rows=batch, imported_at=import_ts)
                return result.consume().counters

            c = session.execute_write(_write_child)
            counters.child_rels_created += c.relationships_created
    logger.info("Phase 4 complete -- %d __child rels", len(child_pairs))

    # ------------------------------------------------------------------ #
    # Phase 5: refersTo + placeholders
    # ------------------------------------------------------------------ #
    with driver.session(database=database) as session:
        for batch in _batches(refers_rows, batch_size):
            def _write_refers(tx: ManagedTransaction, batch=batch):
                result = tx.run(_MERGE_REFERS_TO, rows=batch)
                return result.consume().counters

            c = session.execute_write(_write_refers)
            counters.refers_to_created += c.relationships_created
            counters.placeholders_created += c.nodes_created
    logger.info(
        "Phase 5 complete -- %d refersTo rels, %d placeholders",
        len(refers_rows), counters.placeholders_created,
    )

    # ------------------------------------------------------------------ #
    # Phase 5b: incoming links
    #
    # After phase 3, because the target has to exist to be MATCHed; before phase 6, so that the
    # edges this run creates carry this run's stamp and are not pruned by the reconciliation
    # immediately following them.
    # ------------------------------------------------------------------ #
    with driver.session(database=database) as session:
        for batch in _batches(incoming_rows, batch_size):
            def _write_incoming(tx: ManagedTransaction, batch=batch):
                result = tx.run(_MERGE_INCOMING, rows=batch)
                return result.consume().counters

            c = session.execute_write(_write_incoming)
            counters.incoming_links_created += c.relationships_created
            counters.placeholders_created += c.nodes_created
    logger.info(
        "Phase 5b complete -- %d incoming links read, %d relationships created",
        counters.incoming_links_read, counters.incoming_links_created,
    )

    # ------------------------------------------------------------------ #
    # Phase 6: reconciliation
    # ------------------------------------------------------------------ #
    _reconcile(driver, database, module_url, import_ts, counters)
    logger.info(
        "Phase 6 complete -- %d objects deleted in DOORS (%d newly), "
        "%d __child and %d refersTo pruned, %d annotations and %d ghost edges removed, "
        "%d ghosts and %d placeholders collected",
        counters.objects_deleted_in_source, counters.objects_newly_deleted,
        counters.child_rels_deleted, counters.refers_to_deleted,
        counters.ghost_meta_deleted, counters.ghost_edges_stripped,
        counters.ghosts_collected, counters.placeholders_removed,
    )


def _reconcile(
    driver: Driver,
    database: str,
    module_url: str,
    import_ts: str,
    counters: ImportCounters,
) -> None:
    """Phase 6: reconcile the module against the export that has just been merged.

    A DOORS export is the authoritative statement of what a module contains *now*, so an object
    the export no longer mentions has stopped being part of it. What it must **not** do is
    disappear: DOORS deletes objects and leaves the links to them behind, and those stale links
    are a real defect in the requirements data that only this application is in a position to
    show. So the object is labelled ``:__DELETED`` and keeps everything else it had.

    Six statements, in an order that matters. None of them takes a parameter that grows with the
    module -- the diff is the run-stamp comparison the database makes for itself -- and every one
    is scoped by a label, and by ``__moduleUrl`` wherever the question is about one module.
    """
    scope = {"module_url": module_url, "imported_at": import_ts}

    with driver.session(database=database) as session:

        def _run(cypher: str, **params) -> tuple[list, Any]:
            def _tx(tx: ManagedTransaction):
                result = tx.run(cypher, **params)
                records = [record.data() for record in result]
                return records, result.consume().counters

            return session.execute_write(_tx)

        # 1. Mark. `labels_added` counts only the objects this run marked, while the RETURN
        #    counts every ghost the module now has -- an object deleted three imports ago is
        #    still matched here, and setting a label it already carries is a no-op.
        records, c = _run(_MARK_DELETED, **scope)
        counters.objects_deleted_in_source = records[0]["ghosts"] if records else 0
        counters.objects_newly_deleted = c.labels_added

        # 2. The hierarchy, for the whole module at once. This is also what takes a ghost out of
        #    the tree, so nothing below has to name it.
        _, c = _run(_DELETE_STALE_CHILD, **scope)
        counters.child_rels_deleted = c.relationships_deleted

        # 3. Traceability, for the objects the export still describes. Must run after step 1:
        #    a ghost is excluded by the label, and its links are the ones worth keeping.
        _, c = _run(_DELETE_STALE_REFERS_TO, **scope)
        counters.refers_to_deleted = c.relationships_deleted

        # 4a. The annotations go with the object -- the one place an importer deletes Tier 2.
        _, c = _run(_DELETE_GHOST_META, **scope)
        counters.ghost_meta_deleted = c.nodes_deleted

        # 4b. What a ghost is allowed to keep.
        _, c = _run(_STRIP_GHOST_EDGES, **scope)
        counters.ghost_edges_stripped = c.relationships_deleted

        # 5 and 6. Collection, deliberately not scoped to this module: re-importing one module
        #    is exactly what strands a ghost or a placeholder belonging to another. Both are
        #    label scans over labels only these two states carry, so the cost is the size of the
        #    problem rather than the size of the graph. Both mean "unlinked" literally: no edges
        #    at all, in either direction.
        _, c = _run(_COLLECT_GHOSTS)
        counters.ghosts_collected = c.nodes_deleted

        _, c = _run(_COLLECT_PLACEHOLDERS)
        counters.placeholders_removed = c.nodes_deleted
