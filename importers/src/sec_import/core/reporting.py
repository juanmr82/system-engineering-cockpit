"""Import run reporting (CLAUDE.md §10): console summary + JSON on every run.

Never silently swallow a malformed record -- record it as an anomaly instead.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path


@dataclass
class ImportReport:
    source: str
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    created: int = 0
    updated: int = 0
    anomalies: list[str] = field(default_factory=list)

    def anomaly(self, message: str) -> None:
        self.anomalies.append(message)

    def as_dict(self) -> dict:
        return {
            "source": self.source,
            "startedAt": self.started_at.isoformat(),
            "created": self.created,
            "updated": self.updated,
            "anomalies": self.anomalies,
        }

    def summary(self) -> str:
        return (
            f"[{self.source}] created={self.created} updated={self.updated} "
            f"anomalies={len(self.anomalies)}"
        )

    def write(self, reports_dir: Path) -> Path:
        reports_dir.mkdir(parents=True, exist_ok=True)
        path = reports_dir / f"{self.source}-{self.started_at:%Y%m%dT%H%M%SZ}.json"
        path.write_text(json.dumps(self.as_dict(), indent=2), encoding="utf-8")
        print(self.summary())
        return path
