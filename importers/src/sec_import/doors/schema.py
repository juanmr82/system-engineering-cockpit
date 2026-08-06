from __future__ import annotations
import logging
from neo4j import Driver

logger = logging.getLogger(__name__)

_SCHEMA_STATEMENTS: list[str] = [
    # Uniqueness constraints (also create backing range indexes)
    """\
CYPHER 25
CREATE CONSTRAINT se_item_id_unique IF NOT EXISTS
FOR (n:SEItem) REQUIRE n.__id IS UNIQUE""",

    """\
CYPHER 25
CREATE CONSTRAINT doors_object_url_unique IF NOT EXISTS
FOR (n:DOORSObject) REQUIRE n.__objectUrl IS UNIQUE""",

    # Additional indexes (not on __id or __objectUrl -- covered by constraints above)
    "CYPHER 25 CREATE INDEX doors_object_id      IF NOT EXISTS FOR (n:DOORSObject) ON (n.id)",
    "CYPHER 25 CREATE INDEX doors_object_module  IF NOT EXISTS FOR (n:DOORSObject) ON (n.__moduleUrl)",
    "CYPHER 25 CREATE INDEX doors_object_sortkey IF NOT EXISTS FOR (n:DOORSObject) ON (n.__sortKey)",
    "CYPHER 25 CREATE INDEX se_item_name         IF NOT EXISTS FOR (n:SEItem)      ON (n.__name)",

    # Label-property indexes are per label: the planner will not use doors_object_module for
    # MATCH (r:DOORSRequirement {__moduleUrl: $u}) -- it does not know every DOORSRequirement is
    # also a DOORSObject -- and that pattern then degrades to scanning every requirement in the
    # database. Required by CLAUDE.md section 7, and it belongs here rather than in the backend's
    # meta schema so it exists even if the backend has never started.
    "CYPHER 25 CREATE INDEX doors_requirement_module IF NOT EXISTS FOR (n:DOORSRequirement) ON (n.__moduleUrl)",
]


def init_schema(driver: Driver, database: str) -> None:
    """Phase 1: create constraints and indexes idempotently."""
    with driver.session(database=database) as session:
        for stmt in _SCHEMA_STATEMENTS:
            session.run(stmt)
            logger.debug("Schema: %s", stmt.split("\n")[0])
    logger.info("Schema initialised (all IF NOT EXISTS -- safe to re-run)")
