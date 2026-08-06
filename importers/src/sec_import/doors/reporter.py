from __future__ import annotations
import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .parser import ReportEntry

logger = logging.getLogger(__name__)


@dataclass
class ImportCounters:
    module_name: str = ""
    module_version: str = ""
    objects_read: int = 0
    nodes_created: int = 0
    nodes_updated: int = 0
    placeholders_created: int = 0
    placeholders_upgraded: int = 0
    child_rels_created: int = 0
    refers_to_created: int = 0
    by_type_label: dict[str, int] = field(default_factory=dict)
    table_cells: int = 0
    tables: int = 0
    table_rows: int = 0


@dataclass
class ImportReport:
    started_at: str = ""
    finished_at: str = ""
    source_file: str = ""
    counters: ImportCounters = field(default_factory=ImportCounters)
    parse_entries: list[dict] = field(default_factory=list)
    anomalies: list[dict] = field(default_factory=list)
    validation: list[dict] = field(default_factory=list)

    def add_parse_entries(self, entries: list) -> None:
        for e in entries:
            self.parse_entries.append(asdict(e))

    def add_anomaly(self, level: str, category: str, message: str, **detail) -> None:
        self.anomalies.append({"level": level, "category": category, "message": message, **detail})

    def print_summary(self) -> None:
        c = self.counters
        print(f"\n{'='*60}")
        print(f"DOORS Import Report -- {c.module_name} v{c.module_version}")
        print(f"{'='*60}")
        print(f"  Objects read        : {c.objects_read}")
        print(f"  Nodes created       : {c.nodes_created}")
        print(f"  Nodes updated       : {c.nodes_updated}")
        print(f"  Placeholders created: {c.placeholders_created}")
        print(f"  Placeholders upgraded:{c.placeholders_upgraded}")
        print(f"  __child created     : {c.child_rels_created}")
        print(f"  refersTo created    : {c.refers_to_created}")
        print(f"  DOORSTable          : {c.tables}")
        print(f"  DOORSTableRow       : {c.table_rows}")
        print(f"  DOORSTableCell      : {c.table_cells}")
        if c.by_type_label:
            print("  By type label:")
            for lbl, cnt in sorted(c.by_type_label.items()):
                print(f"    {lbl}: {cnt}")
        errors = [e for e in self.parse_entries + self.anomalies if e.get("level") == "ERROR"]
        warns  = [e for e in self.parse_entries + self.anomalies if e.get("level") == "WARN"]
        print(f"  Errors              : {len(errors)}")
        print(f"  Warnings            : {len(warns)}")
        if self.validation:
            print("\nPost-import validation:")
            for v in self.validation:
                print(f"  {v}")
        print(f"{'='*60}\n")

    def write_json(self, path: Path) -> None:
        path.write_text(
            json.dumps(asdict(self), indent=2, default=str),
            encoding="utf-8",
        )
        logger.info("Report written to %s", path)
