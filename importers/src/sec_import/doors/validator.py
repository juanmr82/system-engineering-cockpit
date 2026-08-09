from __future__ import annotations
import logging
from neo4j import Driver

logger = logging.getLogger(__name__)

_VALIDATION_QUERIES: list[tuple[str, str]] = [
    (
        "metadata_contract",
        """\
CYPHER 25
MATCH (n:SEItem)
WHERE n.__id IS NULL OR n.__name IS NULL OR n.__name = '' OR n.__version IS NULL
RETURN labels(n) AS labels, count(*) AS violations""",
    ),
    (
        "unresolved_placeholders",
        """\
CYPHER 25
MATCH (n:`__UNDEFINED`)
RETURN n.__moduleUrl AS module, count(*) AS unresolved
ORDER BY unresolved DESC""",
    ),
    (
        # An object deleted in DOORS is deliberately out of the tree (ADR 0012), so it is not
        # an orphan -- it is a ghost, and the check below is the one that counts it.
        "orphan_objects",
        """\
CYPHER 25
MATCH (n:DOORSObject)
WHERE n.objectLevel > 1 AND NOT n:`__DELETED` AND NOT ()-[:__child]->(n)
RETURN n.id AS id, n.objectNumber AS objectNumber""",
    ),
    (
        # The stale links DOORS left behind, per module that still asserts one. This is the
        # number the Statistics view shows and the review table flags as an issue; having it in
        # the import report too means the operator who ran the import sees it without opening
        # the application.
        "links_to_deleted_objects",
        """\
CYPHER 25
MATCH (s:DOORSObject)-[:refersTo]-(g:DOORSObject)
WHERE g:`__DELETED` AND NOT s:`__DELETED`
RETURN s.__moduleUrl AS module, count(*) AS links
ORDER BY links DESC""",
    ),
    (
        "hierarchy_step_violations",
        """\
CYPHER 25
MATCH (p:DOORSObject)-[:__child]->(c:DOORSObject)
WHERE c.objectLevel <> p.objectLevel + 1
RETURN p.objectNumber AS parent, c.objectNumber AS child""",
    ),
]


def run_validation(driver: Driver, database: str) -> list[dict]:
    """Run post-import validation queries. Returns a list of result dicts."""
    results: list[dict] = []
    with driver.session(database=database) as session:
        for name, cypher in _VALIDATION_QUERIES:
            records = session.run(cypher).data()
            results.append({"check": name, "rows": records})
            if records:
                logger.warning("Validation %s: %d rows", name, len(records))
            else:
                logger.info("Validation %s: OK", name)
    return results
