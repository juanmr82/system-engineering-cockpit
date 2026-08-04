"""The one batched, idempotent Neo4j writer, shared by every importer (CLAUDE.md §10).

MERGE on __id, and SET n += props -- a second identical run creates zero nodes and zero
relationships, and never touches Tier-2 :__Meta relationships hanging off the same node.
Batches 1,000-5,000 rows per transaction via UNWIND $rows, driver-side -- never LOAD CSV.
If a second source ever needs its own batched UNWIND writer, that is a sign this module
should have grown a parameter instead.
"""
from __future__ import annotations

from collections.abc import Iterable, Iterator, Mapping
from typing import Any

from neo4j import Driver

DEFAULT_BATCH_SIZE = 2000

_MERGE_ITEMS_QUERY = """
CYPHER 25
UNWIND $rows AS row
MERGE (n:SEItem { __id: row.id })
SET n += row.props
"""


def batched(
    rows: Iterable[Mapping[str, Any]], size: int = DEFAULT_BATCH_SIZE
) -> Iterator[list[Mapping[str, Any]]]:
    batch: list[Mapping[str, Any]] = []
    for row in rows:
        batch.append(row)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def write_items(driver: Driver, database: str, rows: Iterable[Mapping[str, Any]]) -> None:
    with driver.session(database=database) as session:
        for batch in batched(rows):
            session.execute_write(lambda tx, b=batch: tx.run(_MERGE_ITEMS_QUERY, rows=b))
