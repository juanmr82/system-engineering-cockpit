"""Tier-1 identity derivation (CLAUDE.md R1, R3).

Every source implements the same protocol: identity triple, parent, sort key, labels.
core/ defines the protocol and the writer; the source package supplies the implementations.
A change to the identity scheme touches derive_id and nothing else.
"""
from __future__ import annotations

from typing import Any, Protocol


def derive_id(source_key: str, native_id: str) -> str:
    """Globally unique __id. Namespaced by source so two sources can never collide on it."""
    return f"{source_key}:{native_id}"


class TierOneDeriver(Protocol):
    """The Tier-1 derivation interface every source package implements (CLAUDE.md R3).

    A new source is a new module implementing this protocol, not a new graph shape.
    """

    def derive_id(self, record: Any) -> str: ...

    def derive_name(self, record: Any) -> str: ...

    def derive_labels(self, record: Any) -> list[str]: ...

    def derive_parent(self, record: Any) -> str | None: ...

    def derive_sort_key(self, record: Any) -> str: ...
