"""Entry point for the Cameo importer. Cross-platform (CLAUDE.md §1)."""
from __future__ import annotations

import argparse
import sys


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="sec-import-cameo")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="parse and derive, write the report, touch nothing",
    )
    parser.add_argument("export_path", help="path to the Cameo export")
    parser.parse_args(argv)

    raise NotImplementedError


if __name__ == "__main__":
    sys.exit(main())
