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

    # Reconciliation (phase 6, ADR 0012).
    #
    # `objects_deleted_in_source` is the module's whole ghost population, not this run's delta:
    # an object deleted three imports ago is still gone, and a report that only counted the
    # newly-marked would show 0 on every run after the one that noticed. `objects_newly_deleted`
    # is the delta, and it is the number worth reacting to -- a sudden large one is what a
    # truncated or view-filtered export looks like.
    objects_deleted_in_source: int = 0
    objects_newly_deleted: int = 0
    child_rels_deleted: int = 0
    refers_to_deleted: int = 0
    ghost_edges_stripped: int = 0
    ghosts_collected: int = 0
    placeholders_removed: int = 0

    # Annotations removed with the object they were written on. The only Tier-2 data an importer
    # ever deletes, so it is reported rather than left to be inferred from a node count: a user
    # who wrote a comment on a requirement that has since been deleted should be able to find out
    # from the run that took it.
    ghost_meta_deleted: int = 0

    # Incoming links, from __inputLinks. `read` counts what the export asserts about links into
    # this module; `created` counts the ones the graph did not already have, which after the first
    # import of a stable pair of modules is normally zero.
    incoming_links_read: int = 0
    incoming_links_created: int = 0


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
        print(f"  Incoming links read : {c.incoming_links_read}")
        print(f"  Incoming created    : {c.incoming_links_created}")
        print("  -- deleted in DOORS --")
        print(f"  Objects gone        : {c.objects_deleted_in_source} "
              f"({c.objects_newly_deleted} newly, this run)")
        print(f"  __child pruned      : {c.child_rels_deleted}")
        print(f"  refersTo pruned     : {c.refers_to_deleted}")
        print(f"  Annotations removed : {c.ghost_meta_deleted}")
        print(f"  Ghost edges stripped: {c.ghost_edges_stripped}")
        print(f"  Ghosts collected    : {c.ghosts_collected}")
        print(f"  Placeholders removed: {c.placeholders_removed}")
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
