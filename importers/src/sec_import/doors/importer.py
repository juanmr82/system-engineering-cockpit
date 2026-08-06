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

# {label_str} is the only interpolated part; all Cypher map literals use {{ }}
_MERGE_OBJECTS_TPL = """\
CYPHER 25
UNWIND $rows AS row
MERGE (n:SEItem {{__id: row.__id}})
REMOVE n:`__UNDEFINED`
SET n:{label_str}
SET n += row.props"""

# MATCH (not MERGE) for both ends -- they must exist from earlier phases
_MERGE_CHILD = """\
CYPHER 25
UNWIND $rows AS row
MATCH (p:SEItem {__id: row.parent_id})
MATCH (c:SEItem {__id: row.child_id})
MERGE (p)-[:__child]->(c)"""

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
ON CREATE SET
    r.__sourceModuleUrl = row.source_module_url,
    r.__importedAt      = row.imported_at"""


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
    # Build refersTo rows
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
            cypher = _MERGE_OBJECTS_TPL.format(label_str=lbl)
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
                    result = tx.run(cypher, rows=batch)
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
                result = tx.run(_MERGE_CHILD, rows=batch)
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
