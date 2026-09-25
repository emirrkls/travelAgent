from __future__ import annotations

import argparse
import json
from pathlib import Path

from .benchmark_lock import verify_benchmark_lock
from .config import load_category_mappings, load_gold_places, load_pilot_areas, load_provider_registry
from .environment import fsq_credential_available
from .pipeline import run_benchmark
from .didim_pilot import (
    DEFAULT_EXISTING_PLACES_URL,
    rebuild_physical_validation_sample,
    restore_source_records_from_benchmark,
    run_didim_dry_run,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="phokarta-place-benchmark")
    subparsers = parser.add_subparsers(dest="command", required=True)
    benchmark = subparsers.add_parser("benchmark", help="run scoped provider benchmark")
    benchmark.add_argument("--providers", default="overture,fsq")
    benchmark.add_argument("--release", default="latest", help="Overture release or latest")
    benchmark.add_argument("--fsq-release", default=None, help="verified FSQ release label if snapshot APIs are unavailable")
    benchmark.add_argument("--output", required=True, type=Path)
    pilot = subparsers.add_parser("didim-dry-run", help="run the M5.5B Didim Core canonicalization plan")
    pilot.add_argument("--release", default="latest", help="Overture release or latest")
    pilot.add_argument("--fsq-release", default=None)
    pilot.add_argument("--output", required=True, type=Path)
    pilot.add_argument("--existing-places-url", default=DEFAULT_EXISTING_PLACES_URL)
    pilot.add_argument("--existing-places-file", type=Path)
    pilot.add_argument(
        "--source-package",
        type=Path,
        help="replay the exact source records and provider versions from a prior Didim package",
    )
    sample = subparsers.add_parser(
        "rebuild-didim-review-sample",
        help="rebuild only the field-review sample from an existing dry-run package",
    )
    sample.add_argument("--package", required=True, type=Path)
    restore = subparsers.add_parser(
        "restore-didim-source-records",
        help="restore exact pinned source observations from a matching M5.5A package",
    )
    restore.add_argument("--package", required=True, type=Path)
    restore.add_argument("--benchmark", required=True, type=Path)
    subparsers.add_parser("validate-config", help="validate repository fixtures")
    subparsers.add_parser("doctor", help="report only whether an FSQ credential is configured")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.command == "doctor":
        print(f"FSQ credential configured: {'YES' if fsq_credential_available() else 'NO'}")
        return 0
    if args.command == "validate-config":
        areas = load_pilot_areas()
        mappings = load_category_mappings()
        registry = load_provider_registry()
        lock = verify_benchmark_lock()
        gold = load_gold_places()
        area_keys = {area.key for area in areas}
        if any(place.area not in area_keys for place in gold):
            raise ValueError("gold fixture references an unknown pilot area")
        counts = {key: sum(place.area == key for place in gold) for key in sorted(area_keys)}
        if any(count < 10 or count > 20 for count in counts.values()):
            raise ValueError("gold fixture must contain 10–20 Places per pilot area")
        print(json.dumps({
            "status": "valid",
            "pilot_areas": len(areas),
            "gold_places": len(gold),
            "gold_by_area": counts,
            "mapping_version": mappings["version"],
            "providers": [item["key"] for item in registry["providers"]],
            "benchmark_method_version": lock["benchmark_method_version"],
        }, ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "didim-dry-run":
        verify_benchmark_lock()
        result = run_didim_dry_run(
            args.output,
            overture_release=args.release,
            fsq_release=args.fsq_release,
            existing_places_url=None if args.existing_places_file else args.existing_places_url,
            existing_places_file=args.existing_places_file,
            source_package=args.source_package,
        )
        print(json.dumps({
            "status": result["summary"]["status"],
            "output": result["output"],
            "classifications": result["summary"]["classifications"],
        }, ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "rebuild-didim-review-sample":
        count = rebuild_physical_validation_sample(args.package)
        print(json.dumps({"sample_rows": count, "package": str(args.package)}, sort_keys=True))
        return 0
    if args.command == "restore-didim-source-records":
        counts = restore_source_records_from_benchmark(args.package, args.benchmark)
        print(json.dumps({"source_counts": counts, "package": str(args.package)}, sort_keys=True))
        return 0
    verify_benchmark_lock()
    providers = [value.strip().casefold() for value in args.providers.split(",") if value.strip()]
    result = run_benchmark(args.output, providers, args.release, args.fsq_release)
    print(json.dumps({
        "assessment": result["summary"]["assessment"],
        "output": result["output"],
        "providers_completed": result["summary"]["providers_completed"],
    }, ensure_ascii=False, sort_keys=True))
    return 0
