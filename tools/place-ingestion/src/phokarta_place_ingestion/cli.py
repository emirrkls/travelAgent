from __future__ import annotations

import argparse
import json
from pathlib import Path

from .benchmark_lock import verify_benchmark_lock
from .config import load_category_mappings, load_gold_places, load_pilot_areas, load_provider_registry
from .environment import fsq_credential_available
from .pipeline import run_benchmark


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="phokarta-place-benchmark")
    subparsers = parser.add_subparsers(dest="command", required=True)
    benchmark = subparsers.add_parser("benchmark", help="run scoped provider benchmark")
    benchmark.add_argument("--providers", default="overture,fsq")
    benchmark.add_argument("--release", default="latest", help="Overture release or latest")
    benchmark.add_argument("--fsq-release", default=None, help="verified FSQ release label if snapshot APIs are unavailable")
    benchmark.add_argument("--output", required=True, type=Path)
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
    verify_benchmark_lock()
    providers = [value.strip().casefold() for value in args.providers.split(",") if value.strip()]
    result = run_benchmark(args.output, providers, args.release, args.fsq_release)
    print(json.dumps({
        "assessment": result["summary"]["assessment"],
        "output": result["output"],
        "providers_completed": result["summary"]["providers_completed"],
    }, ensure_ascii=False, sort_keys=True))
    return 0
