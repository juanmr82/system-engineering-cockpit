from __future__ import annotations
import re
from .exceptions import MalformedUrlError

OBJECT_TYPE_TO_LABEL: dict[str, str] = {
    "Heading": "DOORSHeading",
    "AppMatrixHeading": "DOORSAppMatrixHeading",
    "Requirement": "DOORSRequirement",
    "Information": "DOORSInformation",
    "AppMatrix": "DOORSAppMatrix",
    "TBD": "DOORSTBD",
    "": "DOORSTBD",
}

# All keys that are importer/metadata keys (not DOORS user attributes)
OBJECT_META_KEYS: frozenset[str] = frozenset({
    "__tableObject", "__tableID", "__tableURL", "__tableRowIndex",
    "__tableColumnIndex", "id", "objectNumber", "objectLevel",
    "__moduleUrl", "__objectUrl", "__outputLinks", "__inputLinks",
})

MODULE_META_KEYS: frozenset[str] = frozenset({
    "__objectId", "__name", "__version", "description",
    "moduleFullPath", "url", "__contents",
})


def target_object_url(req_document_url: str, absolute_number: str) -> str:
    u = req_document_url.strip()
    if "-M-" in u:
        head, mod_id = u.rsplit("-M-", 1)
        return f"{head}-O-{absolute_number}-{mod_id}"
    if "-B-" in u:
        head, rest = u.rsplit("-B-", 1)          # rest = "<modId>-<versionId>"
        return f"{head}-V-{absolute_number}-{rest}"
    raise MalformedUrlError(f"Cannot derive object URL from: {u!r}")


def target_version(req_document_url: str) -> str:
    u = req_document_url.strip()
    if "-M-" in u:
        return "current"
    if "-B-" in u:
        _head, rest = u.rsplit("-B-", 1)
        # rest = "<modId>-<versionId>"; modId is hex (no dashes), versionId may contain dashes
        _mod_id, version_id = rest.split("-", 1)
        return version_id
    raise MalformedUrlError(f"Cannot derive version from: {u!r}")


def parent_number(n: str) -> str | None:
    """Return the parent objectNumber by dropping the last dot-segment, or None for roots."""
    return n.rsplit(".", 1)[0] if "." in n else None


def sort_key(n: str) -> str:
    """Zero-pad every numeric part to 6 digits for correct document-order sorting."""
    return ".".join(
        "-".join(p.zfill(6) for p in seg.split("-"))
        for seg in n.split(".")
    )


def derive_type_label(object_type: str) -> tuple[str, bool]:
    """Return (label, is_unknown). is_unknown=True when object_type is unrecognised."""
    label = OBJECT_TYPE_TO_LABEL.get(object_type)
    if label is None:
        return "DOORSTBD", True
    return label, False


def derive_name(obj: dict) -> str:
    """Derive __name for an object using the three-level fallback chain."""
    object_type = obj.get("Object Type") or ""
    if object_type in {"Heading", "AppMatrixHeading"}:
        base = obj.get("Object Heading") or ""
    else:
        base = obj.get("Object Short Text") or ""

    if base:
        return base

    text = obj.get("Object Text") or ""
    if text:
        return text[:120] + ("…" if len(text) > 120 else "")

    return obj.get("id") or "<unknown>"


def compute_table_sets(objects: list[dict]) -> tuple[set[str], set[str]]:
    """
    Returns (table_ids, table_row_ids) -- sets of 'id' field values.

    table_ids    : objects whose id is referenced as __tableID by at least one cell.
    table_row_ids: objects that are a __child of a DOORSTable and a __child-parent of a DOORSTableCell.
    """
    # Pass 1: collect table IDs from cells
    table_ids: set[str] = set()
    for obj in objects:
        if obj.get("__tableObject") == "true":
            tid = obj.get("__tableID") or ""
            if tid:
                table_ids.add(tid)

    # Build bidirectional objectNumber <-> id maps
    num_to_id: dict[str, str] = {}
    id_to_num: dict[str, str] = {}
    for obj in objects:
        oid = obj.get("id") or ""
        num = obj.get("objectNumber") or ""
        if oid and num:
            num_to_id[num] = oid
            id_to_num[oid] = num

    # Build parent-objectNumber -> list-of-child-objectNumbers
    parent_to_children: dict[str, list[str]] = {}
    for obj in objects:
        num = obj.get("objectNumber") or ""
        p = parent_number(num)
        if p:
            parent_to_children.setdefault(p, []).append(num)

    # Map objectNumber -> is_cell
    num_is_cell: dict[str, bool] = {
        obj["objectNumber"]: (obj.get("__tableObject") == "true")
        for obj in objects
        if obj.get("objectNumber")
    }

    # Pass 2: for each table, find its row children
    table_nums: set[str] = {id_to_num[tid] for tid in table_ids if tid in id_to_num}
    table_row_ids: set[str] = set()
    for table_num in table_nums:
        for child_num in parent_to_children.get(table_num, []):
            if any(
                num_is_cell.get(gc_num)
                for gc_num in parent_to_children.get(child_num, [])
            ):
                child_id = num_to_id.get(child_num)
                if child_id:
                    table_row_ids.add(child_id)

    return table_ids, table_row_ids


def derive_labels(obj: dict, table_ids: set[str], table_row_ids: set[str]) -> frozenset[str]:
    """Compute the full label set for an object node."""
    labels: set[str] = {"SEItem", "DOORSObject"}
    object_type = obj.get("Object Type") or ""
    type_label, _ = derive_type_label(object_type)
    labels.add(type_label)

    obj_id = obj.get("id") or ""
    if obj.get("__tableObject") == "true":
        labels.add("DOORSTableCell")
    if obj_id in table_ids:
        labels.add("DOORSTable")
    if obj_id in table_row_ids:
        labels.add("DOORSTableRow")

    return frozenset(labels)
