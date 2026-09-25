from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean
from typing import Any

from .benchmark import calculate_metrics, known_place_recall, review_sample
from .config import (
    PACKAGE_ROOT,
    load_gold_places,
    load_pilot_areas,
    load_provider_registry,
)
from .matching import cross_provider_matches
from .models import NormalizedPlace
from .providers import FoursquareOsPlaceProvider, OverturePlaceProvider, ProviderAccessError
from .reporting import write_bar_chart, write_csv, write_json


REPOSITORY_ROOT = PACKAGE_ROOT.parents[1]


def _mean_numeric(rows: list[dict[str, Any]], key: str) -> float | None:
    values = [float(row[key]) for row in rows if isinstance(row.get(key), (int, float))]
    return round(mean(values), 3) if values else None


def _scorecard(
    metrics: list[dict[str, Any]],
    recall: list[dict[str, Any]],
    categories: list[dict[str, Any]],
    provider_metadata: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    providers = sorted({row["provider"] for row in metrics})
    rows: list[dict[str, Any]] = []
    for provider in providers:
        p_metrics = [row for row in metrics if row["provider"] == provider]
        p_recall = [row for row in recall if row["provider"] == provider]
        p_categories = [row for row in categories if row["provider"] == provider]
        usable = sum(row["usable_records"] for row in p_metrics)
        raw = sum(row["total_raw_records"] for row in p_metrics)
        duplicate_pairs = sum(row["duplicate_candidate_pairs"] for row in p_metrics)
        junk = sum(row["obvious_junk_records"] for row in p_metrics)
        rows.append({
            "provider": provider,
            "raw_records": raw,
            "usable_records": usable,
            "usable_rate_pct": round(usable * 100 / raw, 3) if raw else None,
            "known_place_recall_pct": _mean_numeric(p_recall, "recall_pct"),
            "category_mapping_pct": _mean_numeric(p_categories, "mapping_percentage"),
            "name_completeness_pct": _mean_numeric(p_metrics, "name_completeness_pct"),
            "address_completeness_pct": _mean_numeric(p_metrics, "address_completeness_pct"),
            "phone_completeness_pct": _mean_numeric(p_metrics, "phone_completeness_pct"),
            "website_completeness_pct": _mean_numeric(p_metrics, "website_completeness_pct"),
            "freshness_coverage_pct": _mean_numeric(p_metrics, "freshness_coverage_pct"),
            "duplicate_pairs_per_100_usable": round(duplicate_pairs * 100 / usable, 3) if usable else None,
            "junk_rate_pct": round(junk * 100 / raw, 3) if raw else None,
            "operational_access": provider_metadata[provider]["operational_access"],
            "incremental_update_support": provider_metadata[provider]["delta_support"],
            "stable_id_behavior": provider_metadata[provider]["stable_id_behavior"],
            "license_provenance": provider_metadata[provider]["license"]["license_identifier"],
        })
    if set(providers) != {"fsq", "overture"}:
        return rows, {"primary": "UNDECIDED", "secondary": "UNDECIDED", "reason": "FSQ DATA ACCESS REQUIRED"}

    # Transparent, bounded comparison. Coverage and independent recall carry most
    # weight; raw values remain in the scorecard and are never replaced by this view.
    max_usable = max(row["usable_records"] for row in rows) or 1
    for row in rows:
        completeness = mean(
            float(row[key] or 0)
            for key in ("name_completeness_pct", "address_completeness_pct", "phone_completeness_pct", "website_completeness_pct")
        ) / 100
        row["transparent_subscore"] = round(
            0.30 * row["usable_records"] / max_usable
            + 0.30 * float(row["known_place_recall_pct"] or 0) / 100
            + 0.15 * float(row["category_mapping_pct"] or 0) / 100
            + 0.15 * completeness
            + 0.05 * (1 - min(float(row["duplicate_pairs_per_100_usable"] or 0) / 20, 1))
            + 0.05 * (1 - min(float(row["junk_rate_pct"] or 0) / 20, 1)),
            4,
        )
    ordered = sorted(rows, key=lambda row: row["transparent_subscore"], reverse=True)
    if ordered[0]["transparent_subscore"] - ordered[1]["transparent_subscore"] < 0.05:
        decision = {"primary": "NO CLEAR WINNER", "secondary": "NONE", "reason": "measured subscore difference below 0.05"}
    else:
        decision = {
            "primary": ordered[0]["provider"].upper(),
            "secondary": ordered[1]["provider"].upper(),
            "reason": "higher transparent coverage/recall/category/quality subscore; human approval still required",
        }
    return rows, decision


def _prepare_output(output: Path) -> Path:
    resolved = output.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("benchmark output must be outside the Git repository")
    if resolved.exists() and any(resolved.iterdir()):
        raise ValueError(f"benchmark output directory is not empty: {resolved}")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def run_benchmark(
    output: Path,
    providers_requested: list[str],
    overture_release: str = "latest",
    fsq_release: str | None = None,
) -> dict[str, Any]:
    output = _prepare_output(output)
    started_at = datetime.now(timezone.utc).isoformat()
    areas = load_pilot_areas()
    gold = load_gold_places()
    registry = {row["key"]: row for row in load_provider_registry()["providers"]}
    provider_instances = {
        "overture": OverturePlaceProvider(),
        "fsq": FoursquareOsPlaceProvider(),
    }
    normalized_by_provider: dict[str, list[NormalizedPlace]] = {}
    provider_metadata: dict[str, Any] = {}
    all_fetch_stats: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []

    for key in providers_requested:
        if key not in provider_instances:
            raise ValueError(f"unknown provider: {key}")
        provider = provider_instances[key]
        if key == "fsq" and not provider.credential_available():
            provider_metadata[key] = {
                "provider": key,
                "access_status": "PARTIAL — FSQ DATA ACCESS REQUIRED",
                "reason": "FSQ_PLACES_TOKEN is not configured",
                "actual_release": None,
                "snapshot_id": None,
                "records_scanned": "NOT RUN",
                "places_returned": "NOT RUN",
                "operational_access": "Places Portal token + Iceberg catalog",
                "delta_support": registry[key]["delta_support"],
                "stable_id_behavior": registry[key]["stable_id_behavior"],
                "license": {
                    "license_identifier": registry[key]["license_identifier"],
                    "source_url": registry[key]["license_documentation"],
                },
            }
            errors.append({"provider": key, "status": "FSQ DATA ACCESS REQUIRED"})
            continue
        requested = overture_release if key == "overture" else fsq_release
        try:
            release = provider.describe_release(requested)
            fetched = provider.fetch_scope(areas, release)
            normalized = [provider.normalize(row, release) for row in fetched.records]
            license_metadata = provider.license_metadata(release)
            normalized_by_provider[key] = normalized
            all_fetch_stats.extend(asdict(stat) for stat in fetched.stats)
            provider_metadata[key] = {
                "provider": key,
                "access_status": "SUCCESS",
                "requested_release": release.requested_release,
                "actual_release": release.resolved_release,
                "schema_version": release.schema_version,
                "snapshot_id": release.snapshot_id,
                "records_scanned": "NOT AVAILABLE FROM QUERY ENGINE",
                "places_returned": len(normalized),
                "operational_access": registry[key]["authentication"] + "; " + (
                    "DuckDB GeoParquet" if key == "overture" else "DuckDB Iceberg REST catalog"
                ),
                "delta_support": registry[key]["delta_support"],
                "stable_id_behavior": registry[key]["stable_id_behavior"],
                "license": asdict(license_metadata),
                "retrieval_timestamp": datetime.now(timezone.utc).isoformat(),
            }
            normalized_path = output / f"normalized_{key}.jsonl"
            with normalized_path.open("w", encoding="utf-8") as handle:
                for place in normalized:
                    handle.write(json.dumps(place.to_dict(), ensure_ascii=False, sort_keys=True) + "\n")
        except ProviderAccessError as exc:
            provider_metadata[key] = {
                "provider": key,
                "access_status": "FAILED",
                "reason": str(exc),
                "actual_release": None,
                "places_returned": "NOT RUN",
                "operational_access": registry[key]["authentication"],
                "delta_support": registry[key]["delta_support"],
                "stable_id_behavior": registry[key]["stable_id_behavior"],
                "license": {
                    "license_identifier": registry[key]["license_identifier"],
                    "source_url": registry[key]["license_documentation"],
                },
            }
            errors.append({"provider": key, "status": str(exc)})

    metrics: list[dict[str, Any]] = []
    category_rows: list[dict[str, Any]] = []
    duplicate_rows: list[dict[str, Any]] = []
    recall_summary: list[dict[str, Any]] = []
    recall_detail: list[dict[str, Any]] = []
    for provider, normalized in normalized_by_provider.items():
        p_metrics, p_categories, p_duplicates = calculate_metrics(provider, areas, normalized)
        p_recall, p_recall_detail = known_place_recall(provider, gold, normalized)
        metrics.extend(p_metrics)
        category_rows.extend(p_categories)
        duplicate_rows.extend(p_duplicates)
        recall_summary.extend(p_recall)
        recall_detail.extend(p_recall_detail)

    cross_rows: list[dict[str, Any]] = []
    if {"overture", "fsq"}.issubset(normalized_by_provider):
        overture_by_area = {key: [] for key in (area.key for area in areas)}
        fsq_by_area = {key: [] for key in (area.key for area in areas)}
        for place in normalized_by_provider["overture"]:
            overture_by_area[str(place.source_metadata.get("area"))].append(place)
        for place in normalized_by_provider["fsq"]:
            fsq_by_area[str(place.source_metadata.get("area"))].append(place)
        for area in areas:
            cross_rows.extend(
                asdict(row) for row in cross_provider_matches(
                    area.key, overture_by_area[area.key], fsq_by_area[area.key]
                )
            )
    else:
        cross_rows.append({
            "status": "PARTIAL — FSQ DATA ACCESS REQUIRED",
            "area": "ALL",
            "classification": "NOT RUN",
        })

    cross_classifications: dict[tuple[str, str], str] = {}
    for row in cross_rows:
        classification = str(row.get("classification") or "NOT AVAILABLE")
        if row.get("overture_id"):
            cross_classifications[("overture", str(row["overture_id"]))] = classification
        if row.get("fsq_id"):
            cross_classifications[("fsq", str(row["fsq_id"]))] = classification

    scorecard, decision = _scorecard(metrics, recall_summary, category_rows, provider_metadata)
    sample = review_sample(normalized_by_provider, cross_classifications)
    completed_at = datetime.now(timezone.utc).isoformat()
    partial = not {"overture", "fsq"}.issubset(normalized_by_provider)
    assessment = "B. PARTIAL" if partial else "C. COMPLETE"
    summary = {
        "milestone": "Phokarta V2 Milestone 5.5A",
        "assessment": assessment,
        "started_at": started_at,
        "completed_at": completed_at,
        "pilot_geometry": "fixed WGS84 center + exact geodesic radius",
        "providers_requested": providers_requested,
        "providers_completed": sorted(normalized_by_provider),
        "errors": errors,
        "decision": decision,
        "scorecard": scorecard,
        "known_place_fixture_count": len(gold),
        "sample_seed": 5501,
    }

    write_json(output / "benchmark_summary.json", summary)
    write_json(output / "provider_metadata.json", {
        "generated_at": completed_at,
        "providers": provider_metadata,
        "fetch_stats": all_fetch_stats,
        "credential_values_recorded": False,
    })
    write_csv(output / "benchmark_metrics.csv", metrics, ["provider", "area", "status"])
    write_csv(output / "category_coverage.csv", category_rows, ["provider", "area", "status"])
    write_csv(output / "duplicate_candidates.csv", duplicate_rows, ["provider", "area", "status"])
    write_csv(output / "cross_provider_matches.csv", cross_rows, ["status", "area", "classification"])
    write_csv(output / "known_place_recall.csv", recall_detail, ["provider", "area", "status"])
    write_csv(output / "review_sample.csv", sample, ["provider", "area", "status"])
    _write_license_report(output / "LICENSE_PROVENANCE.md", provider_metadata)
    _write_benchmark_report(
        output / "BENCHMARK_REPORT.md", summary, areas, metrics, category_rows,
        recall_summary, duplicate_rows, cross_rows, provider_metadata, all_fetch_stats,
    )
    write_bar_chart(
        output / "chart_usable_places_by_area.svg",
        "Usable Places by provider and pilot area",
        [(f"{row['provider']} · {row['area']}", float(row["usable_records"])) for row in metrics],
    )
    write_bar_chart(
        output / "chart_known_place_recall.svg",
        "Known-place recall (%)",
        [(f"{row['provider']} · {row['area']}", float(row["recall_pct"] or 0)) for row in recall_summary],
    )
    return {"output": str(output), "summary": summary, "provider_metadata": provider_metadata}


def _write_license_report(path: Path, metadata: dict[str, Any]) -> None:
    lines = [
        "# License & Provenance\n",
        "This is architecture metadata, not legal advice. Human legal review is required before production use.\n",
    ]
    for provider, item in sorted(metadata.items()):
        license_item = item.get("license", {})
        lines.extend([
            f"## {provider}\n",
            f"- Access status: {item.get('access_status')}\n",
            f"- Actual release/snapshot: {item.get('actual_release') or 'NOT AVAILABLE'}\n",
            f"- License identifier: {license_item.get('license_identifier', 'NOT AVAILABLE')}\n",
            f"- Official source: {license_item.get('source_url', 'NOT AVAILABLE')}\n",
        ])
        if license_item.get("notes"):
            lines.append("- Notes:\n")
            lines.extend(f"  - {note}\n" for note in license_item["notes"])
    path.write_text("".join(lines), encoding="utf-8")

def _markdown_table(rows: list[dict[str, Any]], fields: list[str]) -> str:
    if not rows:
        return "No applicable rows.\n"
    lines = ["| " + " | ".join(fields) + " |", "|" + "|".join("---" for _ in fields) + "|"]
    for row in rows:
        lines.append("| " + " | ".join(str(row.get(field, "")).replace("|", "\\|") for field in fields) + " |")
    return "\n".join(lines) + "\n"


def _write_benchmark_report(
    path: Path,
    summary: dict[str, Any],
    areas: list[Any],
    metrics: list[dict[str, Any]],
    category_rows: list[dict[str, Any]],
    recall: list[dict[str, Any]],
    duplicate_rows: list[dict[str, Any]],
    cross_rows: list[dict[str, Any]],
    provider_metadata: dict[str, Any],
    fetch_stats: list[dict[str, Any]],
) -> None:
    geometry_rows = [{
        "area": area.display_name,
        "latitude": area.center_latitude,
        "longitude": area.center_longitude,
        "radius_m": int(area.radius_meters),
    } for area in areas]
    lines = [
        "# Phokarta V2 Milestone 5.5A — Place Provider Benchmark & Ingestion Foundation\n\n",
        f"## Assessment\n\n{summary['assessment']}\n\n",
        "No production import, database migration, mobile integration, deployment, or canonical merge was performed.\n\n",
        "## Provider decision\n\n",
        f"- Primary: {summary['decision']['primary']}\n",
        f"- Secondary/enrichment: {summary['decision']['secondary']}\n",
        f"- Reason: {summary['decision']['reason']}\n\n",
        "Raw measurements below remain authoritative; no decision should rely on the optional subscore alone.\n\n",
        "## Pilot geometry\n\n",
        _markdown_table(geometry_rows, ["area", "latitude", "longitude", "radius_m"]),
        "\n## Coverage and quality\n\n",
        _markdown_table(metrics, [
            "provider", "area", "total_raw_records", "usable_records",
            "name_completeness_pct", "address_completeness_pct",
            "category_completeness_pct", "phone_completeness_pct",
            "website_completeness_pct", "operating_status_coverage_pct",
            "freshness_coverage_pct", "duplicate_candidate_pairs", "obvious_junk_rate_pct",
        ]),
        "\n## Category coverage\n\n",
        _markdown_table(category_rows, [
            "provider", "area", "raw_records", "mapped_records", "unmapped_records",
            "mapping_percentage", "top_unmapped_categories",
        ]),
        "\n## Known-place recall\n\n",
        _markdown_table(recall, ["provider", "area", "gold_set_total", "matched", "missing", "ambiguous", "recall_pct"]),
        "\n## Duplicate analysis\n\n",
        f"Candidate rows: {len(duplicate_rows)}. Candidates are not automatic duplicate declarations.\n\n",
        "Thresholds: at most 30 m and normalized-name similarity at least 0.82; high-confidence requires exact normalized name or at most 15 m and similarity at least 0.94.\n\n",
        "## Cross-provider overlap\n\n",
        f"Rows: {len(cross_rows)}. ",
        "PARTIAL — FSQ DATA ACCESS REQUIRED.\n\n" if summary["assessment"] == "B. PARTIAL" else "See cross_provider_matches.csv for classifications and samples.\n\n",
        "## Operational access\n\n",
        _markdown_table(fetch_stats, ["provider", "area", "query_seconds", "records_returned", "bytes_scanned", "query_method"]),
        "\nBytes scanned/downloaded are marked unavailable when DuckDB/the remote catalog does not expose them.\n\n",
        "## Quality filter\n\n",
        "USABLE requires a valid coordinate, a non-empty name, no explicit reliable closed state, and no severe provider-specific exclusion. Overture confidence below 0.30 is a severe exclusion. FSQ unresolved closed/duplicate/delete/privatevenue/inappropriate/doesnt_exist flags are severe exclusions. These rules are fixed across all six pilot areas.\n\n",
        "## Canonical identity safety\n\n",
        "External IDs remain aliases and provenance. Future ingestion flows external raw → normalized source record → exact external-ref/crosswalk/name-geo-category matching → AUTO_LINK, REVIEW_REQUIRED, or CREATE_NEW → stable Phokarta Place UUID. False merges are more dangerous than duplicates. No canonical UUID was changed.\n\n",
        "## Limitations\n\n",
    ]
    if summary["assessment"] == "B. PARTIAL":
        lines.append("FSQ results, overlap, comparative score and provider recommendation are not available because a Places Portal token was not configured. Do not infer that Overture is the winner.\n")
    else:
        lines.append("Automated matching remains an estimate and the fixed human-review sample must be reviewed before provider approval.\n")
    path.write_text("".join(lines), encoding="utf-8")
