"""Entry point for the DOORS importer.

Windows 11 only -- it needs a DOORS client (CLAUDE.md §1). Invoked via importers/win/*.bat,
never called with business logic living in the batch file itself.
"""
from __future__ import annotations

import argparse
import sys


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="sec-import-doors")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="parse and derive, write the report, touch nothing",
    )
    parser.add_argument("export_path", help="path to the DOORS export file")
    parser.parse_args(argv)

    # Parsing, derivation and the batched write happen here once the DOORS export shape
    # (docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md) is implemented.
    raise NotImplementedError("see docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md")


if __name__ == "__main__":
    sys.exit(main())
