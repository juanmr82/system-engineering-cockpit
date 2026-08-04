"""Tier-1 derivation for DOORS records, implementing core.identity.TierOneDeriver.

DOORS-specific logic lives only here and in importers/win/ (CLAUDE.md §1). Full derivation --
including the outline-number sort key and the seven known export defects -- is specified in
docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md and lands with the DOORS importer itself.
"""
from __future__ import annotations

from typing import Any

SOURCE_KEY = "doors"


def derive_id(record: Any) -> str:
    raise NotImplementedError("see docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md")


def derive_name(record: Any) -> str:
    raise NotImplementedError("see docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md")


def derive_labels(record: Any) -> list[str]:
    raise NotImplementedError("see docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md")


def derive_parent(record: Any) -> str | None:
    raise NotImplementedError("see docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md")


def derive_sort_key(record: Any) -> str:
    raise NotImplementedError("see docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md")
