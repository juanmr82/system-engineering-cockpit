"""Tier-1 derivation for Cameo (MBSE) records, implementing core.identity.TierOneDeriver.

Reuses the source-agnostic vocabulary (__child, __sortKey) -- CLAUDE.md R3. Cross-platform;
no Windows-only assumptions belong in this package.
"""
from __future__ import annotations

from typing import Any

SOURCE_KEY = "cameo"


def derive_id(record: Any) -> str:
    raise NotImplementedError


def derive_name(record: Any) -> str:
    raise NotImplementedError


def derive_labels(record: Any) -> list[str]:
    raise NotImplementedError


def derive_parent(record: Any) -> str | None:
    raise NotImplementedError


def derive_sort_key(record: Any) -> str:
    raise NotImplementedError
