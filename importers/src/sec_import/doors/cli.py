from __future__ import annotations
import argparse
import getpass
import json
import logging
import os
import sys
from pathlib import Path

from neo4j import GraphDatabase

from .importer import run_import
from .parser import parse_module
from .reporter import ImportReport
from .schema import init_schema
from .validator import run_validation

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)s -- %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


def _add_db_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--uri",      default=os.getenv("NEO4J_URI",  "neo4j://localhost:7687"))
    p.add_argument("--user",     default=os.getenv("NEO4J_USER", "neo4j"))
    p.add_argument("--password", default=None,
                   help="Neo4j password (or set NEO4J_PASSWORD env var; prompted if absent)")
    p.add_argument("--database", default="neo4j")


def _get_password(args: argparse.Namespace) -> str:
    if args.password:
        return args.password
    pw = os.getenv("NEO4J_PASSWORD")
    if pw:
        return pw
    return getpass.getpass(f"Neo4j password for {args.user}@{args.uri}: ")


def _driver(args: argparse.Namespace):
    pw = _get_password(args)
    return GraphDatabase.driver(args.uri, auth=(args.user, pw))


def cmd_import(args: argparse.Namespace) -> int:
    path = Path(args.file)
    if not path.exists():
        logger.error("File not found: %s", path)
        return 1

    report = ImportReport(source_file=str(path))

    try:
        parse_result = parse_module(path)
    except json.JSONDecodeError as e:
        logger.error("JSON parse error: %s", e)
        return 1
    except Exception as e:
        logger.error("Parse failed: %s", e)
        return 1

    report.add_parse_entries(parse_result.entries)

    data = parse_result.module
    logger.info(
        "Parsed %s v%s -- %d objects",
        data["__name"], data["__version"], len(data["__contents"]),
    )

    if args.dry_run:
        logger.info("Dry run: derivation complete, no database writes")
        run_import(
            driver=None,
            database=args.database,
            data=data,
            parse_entries=parse_result.entries,
            batch_size=args.batch_size,
            dry_run=True,
            report=report,
        )
        report.print_summary()
        if args.report:
            report.write_json(Path(args.report))
        return 0

    driver = _driver(args)
    try:
        init_schema(driver, args.database)
        run_import(
            driver=driver,
            database=args.database,
            data=data,
            parse_entries=parse_result.entries,
            batch_size=args.batch_size,
            dry_run=False,
            report=report,
        )
        validation = run_validation(driver, args.database)
        report.validation = validation
    finally:
        driver.close()

    report.print_summary()
    if args.report:
        report.write_json(Path(args.report))

    return 0


def cmd_init_schema(args: argparse.Namespace) -> int:
    driver = _driver(args)
    try:
        init_schema(driver, args.database)
    finally:
        driver.close()
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    driver = _driver(args)
    try:
        results = run_validation(driver, args.database)
    finally:
        driver.close()
    print(json.dumps(results, indent=2))
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(prog="doors-importer")
    sub = parser.add_subparsers(dest="command", required=True)

    # import subcommand
    p_import = sub.add_parser("import", help="Import a DOORS module JSON file")
    p_import.add_argument("file", help="Path to the exported module JSON file")
    _add_db_args(p_import)
    p_import.add_argument("--batch-size", type=int, default=1000,
                          help="Rows per transaction (default: 1000)")
    p_import.add_argument("--dry-run", action="store_true",
                          help="Parse and derive without writing to the database")
    p_import.add_argument("--report", metavar="FILE",
                          help="Write JSON report to FILE")

    # init-schema subcommand
    p_schema = sub.add_parser("init-schema", help="Create constraints and indexes")
    _add_db_args(p_schema)

    # validate subcommand
    p_validate = sub.add_parser("validate", help="Run post-import validation queries")
    _add_db_args(p_validate)

    args = parser.parse_args()
    dispatch = {
        "import": cmd_import,
        "init-schema": cmd_init_schema,
        "validate": cmd_validate,
    }
    sys.exit(dispatch[args.command](args))


if __name__ == "__main__":
    main()
