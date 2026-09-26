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
from .autonomous_validation import run_autonomous_didim, run_quarantine_re_evaluation
from .canary_manifest import write_canary_manifest


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
    autonomous = subparsers.add_parser(
        "didim-autonomous",
        help=(
            "build deterministic autonomous-validation artifacts pending the full release gate"
        ),
    )
    autonomous.add_argument("--source-package", required=True, type=Path)
    autonomous.add_argument("--output", required=True, type=Path)
    reevaluate = subparsers.add_parser(
        "re-evaluate-quarantine",
        help="deterministically re-evaluate quarantine after a rule/source version change",
    )
    reevaluate.add_argument("--source-package", required=True, type=Path)
    reevaluate.add_argument("--prior-autonomy-package", required=True, type=Path)
    reevaluate.add_argument("--output", required=True, type=Path)
    manifest = subparsers.add_parser(
        "build-canary-manifest",
        help="build a backend-compatible manifest after explicit operational authorization",
    )
    manifest.add_argument("--source-package", required=True, type=Path)
    manifest.add_argument("--autonomy-package", required=True, type=Path)
    manifest.add_argument("--output", required=True, type=Path)
    manifest.add_argument(
        "--stage", required=True, choices=("STAGE_1", "STAGE_2", "STAGE_3")
    )
    manifest.add_argument("--run-id", required=True)
    manifest.add_argument("--pilot-run-key", required=True)
    manifest.add_argument("--authorization-reference", required=True)
    manifest.add_argument("--authorization-confirmation", required=True)
    manifest.add_argument("--reauthorizes-run-id")
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
    if args.command == "didim-autonomous":
        result = run_autonomous_didim(
            args.source_package,
            args.output,
        )
        print(json.dumps({
            "status": result["summary"]["status"],
            "output": result["output"],
            "method_version": result["summary"]["method_version"],
            "actions": result["summary"]["actions"],
            "canary_eligible": result["summary"]["canary_eligible"],
            "stage_1_planned": result["summary"]["stage_1_planned"],
        }, ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "re-evaluate-quarantine":
        result = run_quarantine_re_evaluation(
            args.source_package,
            args.prior_autonomy_package,
            args.output,
        )
        print(json.dumps({
            "status": "RE_EVALUATED",
            "output": result["output"],
            **result["summary"],
        }, ensure_ascii=False, sort_keys=True))
        return 0
    if args.command == "build-canary-manifest":
        result = write_canary_manifest(
            args.source_package,
            args.autonomy_package,
            args.output,
            stage=args.stage,
            run_id=args.run_id,
            pilot_run_key=args.pilot_run_key,
            authorization_reference=args.authorization_reference,
            authorization_confirmation=args.authorization_confirmation,
            reauthorizes_run_id=args.reauthorizes_run_id,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
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
