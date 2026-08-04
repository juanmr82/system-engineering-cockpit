"""Typed config for importer runs: graph connection + report output location.

Credentials come from the environment, never from a committed file (mirrors backend/config).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Neo4jSettings:
    uri: str
    database: str
    user: str
    password: str


@dataclass(frozen=True)
class ImportConfig:
    neo4j: Neo4jSettings
    reports_dir: Path


def load_config() -> ImportConfig:
    neo4j = Neo4jSettings(
        uri=os.environ.get("SEC_NEO4J_URI", "bolt://localhost:7687"),
        database=os.environ.get("SEC_NEO4J_DATABASE", "neo4j"),
        user=os.environ["SEC_NEO4J_USER"],
        password=os.environ["SEC_NEO4J_PASSWORD"],
    )
    reports_dir = Path(os.environ.get("SEC_IMPORT_REPORTS_DIR", "reports"))
    return ImportConfig(neo4j=neo4j, reports_dir=reports_dir)
