from __future__ import annotations
import json
import logging
from dataclasses import dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)

OBJECT_META_KEYS: frozenset[str] = frozenset({
    "__tableObject", "__tableID", "__tableURL", "__tableRowIndex",
    "__tableColumnIndex", "id", "objectNumber", "objectLevel",
    "__moduleUrl", "__objectUrl", "__outputLinks", "__inputLinks",
})

MODULE_META_KEYS: frozenset[str] = frozenset({
    "__objectId", "__name", "__version", "description",
    "moduleFullPath", "url", "__contents",
})

ALL_META_KEYS: frozenset[str] = OBJECT_META_KEYS | MODULE_META_KEYS

REQUIRED_MODULE_KEYS: tuple[str, ...] = (
    "__objectId", "__name", "__version", "url", "__contents",
)


@dataclass
class ReportEntry:
    level: str          # ERROR | WARN | INFO
    category: str
    message: str
    detail: dict = field(default_factory=dict)


@dataclass
class ParseResult:
    module: dict
    entries: list[ReportEntry] = field(default_factory=list)


def _make_pairs_hook(entries: list[ReportEntry]):
    """Return an object_pairs_hook that detects duplicate JSON keys."""
    def hook(pairs: list[tuple[str, object]]) -> dict:
        result: dict = {}
        for key, value in pairs:
            if key in result:
                attr_key = f"attr::{key}"
                result[attr_key] = value
                entries.append(ReportEntry(
                    level="ERROR",
                    category="duplicate_key",
                    message=(
                        f"Duplicate JSON key {key!r}: kept first value, "
                        f"duplicate stored as {attr_key!r}"
                    ),
                    detail={"key": key, "attr_key": attr_key},
                ))
            else:
                result[key] = value
        return result
    return hook


def _check_unknown_meta_keys(obj: dict, entries: list[ReportEntry]) -> None:
    """Detect __-prefixed keys that are not in the known metadata set."""
    for key in obj:
        if key.startswith("__") and key not in OBJECT_META_KEYS:
            entries.append(ReportEntry(
                level="WARN",
                category="unknown_meta_key",
                message=(
                    f"Unknown __-prefixed key {key!r} "
                    f"on object id={obj.get('id')!r} objectNumber={obj.get('objectNumber')!r}"
                ),
                detail={
                    "key": key,
                    "object_id": obj.get("id"),
                    "object_number": obj.get("objectNumber"),
                },
            ))


def parse_module(path: Path) -> ParseResult:
    """
    Parse a DOORS module JSON export with full defect detection.

    Raises json.JSONDecodeError (enriched with byte offset and context)
    on parse failure. Raises ImportValidationError on missing required keys
    or URL/version mismatch.
    """
    from .exceptions import ImportValidationError

    entries: list[ReportEntry] = []
    raw = path.read_bytes()

    try:
        data: dict = json.loads(raw, object_pairs_hook=_make_pairs_hook(entries))
    except json.JSONDecodeError as e:
        start = max(0, e.pos - 80)
        end = min(len(raw), e.pos + 80)
        context = raw[start:end].decode("utf-8", errors="replace")
        raise json.JSONDecodeError(
            f"{e.msg} at byte offset {e.pos}. Context (+-80 bytes): ...{context}...",
            e.doc,
            e.pos,
        ) from None

    # Check required keys
    for key in REQUIRED_MODULE_KEYS:
        if key not in data:
            raise ImportValidationError(f"Required module key {key!r} is missing")

    # Assert URL/version consistency
    url: str = data["url"].strip()
    version: str = data["__version"]
    if "-M-" in url and version != "current":
        raise ImportValidationError(
            f"Module URL contains -M- (current) but __version={version!r} (expected 'current')"
        )
    if "-B-" in url:
        # Parse manually: -B-<modId>-<versionId>
        _head, rest = url.rsplit("-B-", 1)
        _mod_id, version_id = rest.split("-", 1)
        if version != version_id:
            raise ImportValidationError(
                f"Module URL version {version_id!r} does not match __version={version!r}"
            )

    # Check object __moduleUrl consistency
    module_url = url
    contents: list[dict] = data["__contents"]
    for obj in contents:
        obj_module_url = (obj.get("__moduleUrl") or "").strip()
        if obj_module_url and obj_module_url != module_url:
            entries.append(ReportEntry(
                level="WARN",
                category="module_url_mismatch",
                message=(
                    f"Object id={obj.get('id')!r} __moduleUrl {obj_module_url!r} "
                    f"differs from module url {module_url!r}"
                ),
                detail={
                    "object_id": obj.get("id"),
                    "object_module_url": obj_module_url,
                    "module_url": module_url,
                },
            ))

    # Truncation warning
    if len(contents) >= 12000:
        entries.append(ReportEntry(
            level="WARN",
            category="probable_truncation",
            message=f"Module has {len(contents)} objects (>=12000) -- export is probably truncated",
            detail={"count": len(contents)},
        ))

    # Detect unknown __-prefixed keys on each object
    for obj in contents:
        _check_unknown_meta_keys(obj, entries)

    return ParseResult(module=data, entries=entries)
