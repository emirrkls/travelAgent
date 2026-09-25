from __future__ import annotations

import csv
import hashlib
import json
import urllib.request
from collections import Counter, defaultdict
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from .canonicalization import (
    CANONICALIZATION_METHOD_VERSION,
    CanonicalCandidate,
    ExistingCanonicalPlace,
    build_canonicalization_plan,
)
from .config import PACKAGE_ROOT, read_json
from .models import PilotArea
from .providers import FoursquareOsPlaceProvider, OverturePlaceProvider
from .normalization import haversine_meters
from .production_categories import ProductionCategoryMapper
from .quality import assess_quality
from .reporting import write_csv, write_json


REPOSITORY_ROOT = PACKAGE_ROOT.parents[1]
DEFAULT_EXISTING_PLACES_URL = (
    "https://api.phokarta.com/api/v1/places/nearby"
    "?lat=37.3751&lon=27.2678&radiusMeters=6000&limit=200"
)


def _prepare_output(output: Path) -> Path:
    resolved = output.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("Didim pilot output must be outside the Git repository")
    if resolved.exists() and any(resolved.iterdir()):
        raise ValueError(f"Didim pilot output directory is not empty: {resolved}")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def _didim_scope() -> tuple[PilotArea, dict[str, Any]]:
    payload = read_json(PACKAGE_ROOT / "config" / "didim_core.json")
    if float(payload["radius_meters"]) != 6000:
        raise ValueError("M5.5B Didim Core radius must remain exactly 6000 meters")
    return PilotArea(
        key=payload["key"],
        display_name=payload["display_name"],
        center_latitude=float(payload["center"]["latitude"]),
        center_longitude=float(payload["center"]["longitude"]),
        radius_meters=float(payload["radius_meters"]),
        reasoning=payload["policy"],
    ), payload


def _read_existing_places(url: str | None, file: Path | None) -> list[ExistingCanonicalPlace]:
    if file:
        payload = json.loads(file.read_text(encoding="utf-8"))
    elif url:
        request = urllib.request.Request(url, headers={
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0 (compatible; Phokarta-M5.5B-DryRun/1.0)",
        })
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    else:
        payload = []
    if isinstance(payload, dict) and "content" in payload:
        payload = payload["content"]
    result: list[ExistingCanonicalPlace] = []
    for raw in payload:
        row = raw.get("place", raw)
        refs = tuple(
            (str(ref["provider"]).upper(), str(ref["external_id"]))
            for ref in row.get("external_refs", [])
        )
        result.append(ExistingCanonicalPlace(
            place_id=str(row["id"]),
            name=str(row["name"]),
            category=str(row["category"]),
            latitude=float(row["latitude"]),
            longitude=float(row["longitude"]),
            address=str(row.get("address") or ""),
            city=str(row.get("city") or ""),
            region=str(row.get("region") or ""),
            country=str(row.get("country") or ""),
            phone=row.get("phone"),
            website=row.get("website"),
            # Public API cannot prove absence of saves/Collections. Fail closed.
            graph_protected=bool(row.get("graph_protected", True)),
            graph_reference_count=row.get("graph_reference_count") or row.get("ratingCount"),
            external_refs=refs,
        ))
    return result


def _geojson(candidates: Iterable[CanonicalCandidate], sample: bool = False) -> dict[str, Any]:
    features: list[dict[str, Any]] = []
    for candidate in candidates:
        if candidate.latitude is None or candidate.longitude is None:
            continue
        properties = {
            "review_id" if sample else "candidate_id": candidate.candidate_id,
            "classification": candidate.classification,
            "name": candidate.proposed_name,
            "category": candidate.proposed_category,
            "risk_flags": list(candidate.risk_flags),
            "overture_name": candidate.overture_name,
            "fsq_name": candidate.fsq_name,
            "existing_phokarta_name": candidate.existing_place_name,
        }
        features.append({
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [candidate.longitude, candidate.latitude],
            },
            "properties": properties,
        })
    return {"type": "FeatureCollection", "features": features}


def _diverse(rows: list[CanonicalCandidate], limit: int) -> list[CanonicalCandidate]:
    if len(rows) <= limit:
        return sorted(rows, key=lambda row: row.candidate_id)
    remaining = sorted(rows, key=lambda row: row.candidate_id)
    selected = [min(
        remaining,
        key=lambda row: (
            (float(row.latitude) - 37.3751) ** 2 + (float(row.longitude) - 27.2678) ** 2,
            row.candidate_id,
        ),
    )]
    remaining.remove(selected[0])
    while remaining and len(selected) < limit:
        next_row = max(
            remaining,
            key=lambda row: (
                min(
                    (float(row.latitude) - float(chosen.latitude)) ** 2
                    + (float(row.longitude) - float(chosen.longitude)) ** 2
                    for chosen in selected
                ),
                row.candidate_id,
            ),
        )
        selected.append(next_row)
        remaining.remove(next_row)
    return selected


def _balanced_review(rows: list[CanonicalCandidate], limit: int) -> list[CanonicalCandidate]:
    groups: dict[str, list[CanonicalCandidate]] = defaultdict(list)
    for row in rows:
        key = next(iter(row.risk_flags), "NO_RISK_FLAG")
        groups[key].append(row)
    for key, values in groups.items():
        groups[key] = _diverse(values, min(limit, len(values)))
    selected: list[CanonicalCandidate] = []
    while groups and len(selected) < limit:
        for key in sorted(list(groups)):
            if len(selected) >= limit:
                break
            values = groups[key]
            selected.append(values.pop(0))
            if not values:
                del groups[key]
    return selected


def _is_conflict(candidate: CanonicalCandidate) -> bool:
    conflict_flags = {
        "CATEGORY_CONFLICT", "PROVIDER_CATEGORY_CONFLICT", "COORDINATE_DISAGREEMENT",
        "SAME_PROVIDER_DUPLICATE_CANDIDATE", "CROSS_PROVIDER_UNCERTAIN",
        "HOTEL_SUBVENUE_RISK", "MARINA_SUB_BUSINESS_RISK",
        "BEACH_VS_BEACH_CLUB_RISK", "BUILDING_BUSINESS_NESTING_RISK",
    }
    return bool(conflict_flags.intersection(candidate.risk_flags))


def _physical_sample(candidates: list[CanonicalCandidate]) -> tuple[list[dict[str, Any]], list[CanonicalCandidate]]:
    conflicts = _diverse([row for row in candidates if _is_conflict(row)], 5)
    conflict_ids = {row.candidate_id for row in conflicts}
    selected = [
        *_diverse([row for row in candidates if row.classification == "AUTO_LINK"], 10),
        *_balanced_review([
            row for row in candidates
            if row.classification == "REVIEW_REQUIRED" and row.candidate_id not in conflict_ids
        ], 15),
        *_diverse([row for row in candidates if row.classification == "CREATE_NEW"], 10),
        *conflicts,
    ]
    unique: list[CanonicalCandidate] = []
    seen: set[str] = set()
    for row in selected:
        if row.candidate_id not in seen:
            seen.add(row.candidate_id)
            unique.append(row)
    output: list[dict[str, Any]] = []
    for index, row in enumerate(unique, start=1):
        display_classification = "CONFLICT" if row.candidate_id in conflict_ids else row.classification
        output.append({
            "review_id": f"DIDIM-{index:03d}",
            "classification": display_classification,
            "candidate_id": row.candidate_id,
            "proposed canonical name": row.proposed_name,
            "Overture name": row.overture_name,
            "FSQ name": row.fsq_name,
            "existing Phokarta name if any": row.existing_place_name,
            "latitude": row.latitude,
            "longitude": row.longitude,
            "distance/conflict notes": "; ".join(row.risk_flags),
            "proposed Place category": row.proposed_category,
            "provider categories": "; ".join(row.provider_categories),
            "phone if present": row.phone,
            "website if present": row.website,
            "matching reasons": "; ".join(row.matching_reasons),
            "risk flags": "; ".join(row.risk_flags),
            "review_decision": "",
            "review_notes": "",
        })
    return output, unique


def _candidate_from_csv(row: dict[str, str]) -> CanonicalCandidate:
    def optional(value: str | None) -> str | None:
        return value if value else None

    def tuple_field(name: str) -> tuple[str, ...]:
        return tuple(value for value in row.get(name, "").split("; ") if value)

    return CanonicalCandidate(
        candidate_id=row["candidate_id"],
        classification=row["classification"],
        proposed_name=optional(row.get("proposed_name")),
        proposed_category=optional(row.get("proposed_category")),
        latitude=float(row["latitude"]) if row.get("latitude") else None,
        longitude=float(row["longitude"]) if row.get("longitude") else None,
        address=optional(row.get("address")),
        locality=optional(row.get("locality")),
        region=optional(row.get("region")),
        country=optional(row.get("country")),
        phone=optional(row.get("phone")),
        website=optional(row.get("website")),
        overture_id=optional(row.get("overture_id")),
        fsq_id=optional(row.get("fsq_id")),
        overture_name=optional(row.get("overture_name")),
        fsq_name=optional(row.get("fsq_name")),
        existing_place_id=optional(row.get("existing_place_id")),
        existing_place_name=optional(row.get("existing_place_name")),
        match_score=int(row["match_score"]) if row.get("match_score") else None,
        matching_reasons=tuple_field("matching_reasons"),
        risk_flags=tuple_field("risk_flags"),
        cross_provider_classification=row["cross_provider_classification"],
        source_hashes=tuple_field("source_hashes"),
        provider_categories=tuple_field("provider_categories"),
    )


def rebuild_physical_validation_sample(package: Path) -> int:
    resolved = package.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("physical review package must be outside the Git repository")
    candidate_path = resolved / "didim_core_canonical_candidates.csv"
    with candidate_path.open("r", encoding="utf-8-sig", newline="") as handle:
        candidates = [_candidate_from_csv(row) for row in csv.DictReader(handle)]
    sample_rows, sample_candidates = _physical_sample(candidates)
    write_csv(resolved / "PHYSICAL_VALIDATION_SAMPLE.csv", sample_rows, [
        "review_id", "classification", "candidate_id", "proposed canonical name",
        "Overture name", "FSQ name", "existing Phokarta name if any", "latitude",
        "longitude", "distance/conflict notes", "proposed Place category",
        "provider categories", "phone if present", "website if present",
        "matching reasons", "risk flags", "review_decision", "review_notes",
    ])
    _write_sample_geojson(
        resolved / "PHYSICAL_VALIDATION_SAMPLE.geojson", sample_rows, sample_candidates
    )
    return len(sample_rows)


def restore_source_records_from_benchmark(package: Path, benchmark: Path) -> dict[str, int]:
    """Copy the exact same pinned provider observations from M5.5A into the pilot package.

    This recovery path is useful if report generation failed after live provider retrieval. It
    accepts the prior normalized files only when their release/snapshot exactly matches the live
    Didim run recorded in the package.
    """
    package = package.expanduser().resolve()
    benchmark = benchmark.expanduser().resolve()
    if package == REPOSITORY_ROOT or REPOSITORY_ROOT in package.parents:
        raise ValueError("pilot package must be outside the Git repository")
    summary = json.loads((package / "didim_core_summary.json").read_text(encoding="utf-8"))
    benchmark_metadata = json.loads((benchmark / "provider_metadata.json").read_text(encoding="utf-8"))
    expected = summary["providers"]
    actual = benchmark_metadata["providers"]
    for provider in ("overture", "fsq"):
        if str(actual[provider].get("actual_release")) != str(expected[provider]["resolved_release"]):
            raise ValueError(f"{provider} release does not match the live pilot run")
        if str(actual[provider].get("snapshot_id")) != str(expected[provider].get("snapshot_id")):
            raise ValueError(f"{provider} snapshot does not match the live pilot run")
    mapper = ProductionCategoryMapper()
    retrieved_at = summary["completed_at"]
    counts: dict[str, int] = {}
    target = package / "didim_core_source_records.jsonl"
    with target.open("w", encoding="utf-8") as destination:
        for provider in ("overture", "fsq"):
            count = 0
            source_path = benchmark / f"normalized_{provider}.jsonl"
            with source_path.open("r", encoding="utf-8") as source_file:
                for line in source_file:
                    row = json.loads(line)
                    if row.get("source_metadata", {}).get("area") != "didim":
                        continue
                    if haversine_meters(
                        37.3751, 27.2678, float(row["latitude"]), float(row["longitude"])
                    ) > 6000:
                        continue
                    # The provider observation is identical; only the frozen query-scope label
                    # changed from the M5.5A benchmark name to the M5.5B pilot name.
                    row["source_metadata"]["area"] = "didim_core"
                    source_hash = hashlib.sha256(json.dumps(
                        row, ensure_ascii=False, sort_keys=True, separators=(",", ":")
                    ).encode("utf-8")).hexdigest()
                    record = {
                        **row,
                        "source_hash": source_hash,
                        "method_version": CANONICALIZATION_METHOD_VERSION,
                        "snapshot_id": expected[provider].get("snapshot_id"),
                        "schema_version": expected[provider].get("schema_version"),
                        "proposed_place_category": mapper.map(
                            provider, row.get("categories", [])
                        ).category,
                        "license_identifier": actual[provider]["license"]["license_identifier"],
                        "observed_at": row.get("refreshed_date") or row.get("created_date") or retrieved_at,
                        "retrieved_at": retrieved_at,
                    }
                    destination.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
                    count += 1
            counts[provider] = count
            if count != int(summary["source_counts"][provider]):
                raise ValueError(
                    f"{provider} scoped record count {count} does not match live run "
                    f"{summary['source_counts'][provider]}"
                )
    return counts


def _write_sample_geojson(
    path: Path, rows: list[dict[str, Any]], candidates: list[CanonicalCandidate]
) -> None:
    by_id = {row.candidate_id: row for row in candidates}
    features: list[dict[str, Any]] = []
    for row in rows:
        candidate = by_id[row["candidate_id"]]
        properties = {key: value for key, value in row.items() if key not in {"latitude", "longitude"}}
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [candidate.longitude, candidate.latitude]},
            "properties": properties,
        })
    write_json(path, {"type": "FeatureCollection", "features": features})


def run_didim_dry_run(
    output: Path,
    overture_release: str = "latest",
    fsq_release: str | None = None,
    existing_places_url: str | None = DEFAULT_EXISTING_PLACES_URL,
    existing_places_file: Path | None = None,
) -> dict[str, Any]:
    output = _prepare_output(output)
    started_at = datetime.now(timezone.utc)
    scope, scope_config = _didim_scope()
    providers = {
        "overture": OverturePlaceProvider(),
        "fsq": FoursquareOsPlaceProvider(),
    }
    releases: dict[str, Any] = {}
    normalized: dict[str, list[Any]] = {}
    fetch_stats: list[dict[str, Any]] = []
    licenses: dict[str, Any] = {}
    for key, provider in providers.items():
        requested = overture_release if key == "overture" else fsq_release
        release = provider.describe_release(requested)
        fetched = provider.fetch_scope([scope], release)
        normalized[key] = [provider.normalize(row, release) for row in fetched.records]
        releases[key] = asdict(release)
        licenses[key] = asdict(provider.license_metadata(release))
        fetch_stats.extend(asdict(row) for row in fetched.stats)

    existing = _read_existing_places(existing_places_url, existing_places_file)
    plan = build_canonicalization_plan(normalized["overture"], normalized["fsq"], existing)
    candidates = list(plan.candidates)
    counts = Counter(row.classification for row in candidates)
    counts["REJECT"] = len(plan.rejected)
    completed_at = datetime.now(timezone.utc)

    metrics: list[dict[str, Any]] = []
    for provider, rows in normalized.items():
        usable_count = sum(assess_quality(row).usable for row in rows)
        stat = next(item for item in fetch_stats if item["provider"] == provider)
        metrics.append({
            "provider": provider,
            "release": releases[provider]["resolved_release"],
            "snapshot_id": releases[provider].get("snapshot_id"),
            "source_records": len(rows),
            "usable_records": usable_count,
            "rejected_records": len(rows) - usable_count,
            "query_seconds": stat["query_seconds"],
            "query_method": stat["query_method"],
            "queried_at": releases[provider]["discovered_at"],
        })

    category_counts: Counter[tuple[str, str, str | None]] = Counter()
    for row in plan.source_records:
        values = row["provider_categories"] or ["<NO_CATEGORY>"]
        for value in values:
            category_counts[(row["provider"], str(value), row["proposed_place_category"])] += 1
    category_rows = [{
        "provider": provider,
        "provider_category": source_category,
        "proposed_place_category": proposed or "REVIEW_REQUIRED",
        "record_count": count,
    } for (provider, source_category, proposed), count in sorted(
        category_counts.items(),
        key=lambda item: (item[0][0], item[0][1], item[0][2] or ""),
    )]

    provenance_rows = [{
        "provider": key,
        "dataset_name": value["dataset_name"],
        "resolved_release": releases[key]["resolved_release"],
        "snapshot_id": releases[key].get("snapshot_id"),
        "schema_version": releases[key].get("schema_version"),
        "license_identifier": value["license_identifier"],
        "source_url": value["source_url"],
        "retrieval_timestamp": value["retrieval_timestamp"],
        "method_version": CANONICALIZATION_METHOD_VERSION,
        "credential_recorded": False,
    } for key, value in sorted(licenses.items())]

    summary = {
        "milestone": "Phokarta V2 M5.5B Didim Canonical Place Pilot — Phase A",
        "status": "WAITING FOR PHYSICAL / HUMAN REVIEW",
        "started_at": started_at.isoformat(),
        "completed_at": completed_at.isoformat(),
        "scope": scope_config,
        "method_version": CANONICALIZATION_METHOD_VERSION,
        "providers": releases,
        "source_counts": {key: len(value) for key, value in normalized.items()},
        "canonical_candidate_groups": len(candidates),
        "classifications": {key: counts.get(key, 0) for key in (
            "AUTO_LINK", "REVIEW_REQUIRED", "CREATE_NEW", "REJECT"
        )},
        "existing_phokarta_places_in_scope": len(existing),
        "existing_phokarta_matches": sum(bool(row.existing_place_id) for row in candidates),
        "canonical_beta_writes": False,
        "beta_migration_deployed": False,
    }
    candidate_rows = [row.to_row() for row in candidates]
    write_json(output / "didim_core_summary.json", summary)
    write_csv(output / "didim_core_source_metrics.csv", metrics, ["provider", "source_records"])
    write_csv(output / "didim_core_canonical_candidates.csv", candidate_rows, ["candidate_id", "classification"])
    for filename, classification in (
        ("didim_core_auto_link.csv", "AUTO_LINK"),
        ("didim_core_review_required.csv", "REVIEW_REQUIRED"),
        ("didim_core_create_new.csv", "CREATE_NEW"),
    ):
        write_csv(output / filename, [row.to_row() for row in plan.by_classification(classification)],
                  ["candidate_id", "classification"])
    write_csv(output / "didim_core_rejected.csv", plan.rejected, ["classification", "provider", "external_id"])
    write_csv(output / "didim_core_cross_provider_matches.csv", plan.cross_provider_matches,
              ["area", "classification"])
    write_csv(output / "didim_core_existing_place_matches.csv", plan.existing_place_matches,
              ["candidate_id", "existing_place_id", "classification"])
    write_csv(output / "didim_core_category_mapping.csv", category_rows,
              ["provider", "provider_category", "proposed_place_category", "record_count"])
    write_csv(output / "didim_core_provenance.csv", provenance_rows,
              ["provider", "resolved_release", "license_identifier"])
    with (output / "didim_core_source_records.jsonl").open("w", encoding="utf-8") as handle:
        for row in plan.source_records:
            provider = row["provider"]
            handle.write(json.dumps({
                **row,
                "snapshot_id": releases[provider].get("snapshot_id"),
                "schema_version": releases[provider].get("schema_version"),
                "license_identifier": licenses[provider]["license_identifier"],
                "observed_at": row.get("observed_at") or completed_at.isoformat(),
                "retrieved_at": completed_at.isoformat(),
            }, ensure_ascii=False, sort_keys=True) + "\n")
    write_json(output / "didim_core.geojson", _geojson(candidates))
    sample_rows, sample_candidates = _physical_sample(candidates)
    write_csv(output / "PHYSICAL_VALIDATION_SAMPLE.csv", sample_rows, [
        "review_id", "classification", "candidate_id", "proposed canonical name",
        "Overture name", "FSQ name", "existing Phokarta name if any", "latitude",
        "longitude", "distance/conflict notes", "proposed Place category",
        "provider categories", "phone if present", "website if present",
        "matching reasons", "risk flags", "review_decision", "review_notes",
    ])
    _write_sample_geojson(output / "PHYSICAL_VALIDATION_SAMPLE.geojson", sample_rows, sample_candidates)
    _write_report(output / "DRY_RUN_REPORT.md", summary, metrics, sample_rows)
    return {"output": str(output), "summary": summary}


def _write_report(
    path: Path, summary: dict[str, Any], metrics: list[dict[str, Any]], sample: list[dict[str, Any]]
) -> None:
    classifications = summary["classifications"]
    lines = [
        "# M5.5B Didim Core Dry-Run Report\n\n",
        "## Status\n\nWAITING FOR PHYSICAL / HUMAN REVIEW\n\n",
        "This package is a read-only canonicalization plan. It performed no canonical beta writes and did not deploy V17.\n\n",
        "## Frozen scope\n\n",
        "- Center: `37.3751, 27.2678`\n",
        "- Radius: `6,000 m`\n",
        "- The earlier 12 km Didim benchmark circle is not part of this pilot.\n\n",
        "## Provider inputs\n\n",
    ]
    for row in metrics:
        lines.append(
            f"- {row['provider']}: {row['source_records']} source / {row['usable_records']} usable / "
            f"{row['rejected_records']} rejected; release `{row['release']}`; "
            f"snapshot `{row['snapshot_id'] or 'N/A'}`.\n"
        )
    lines.extend([
        "\n## Canonicalization\n\n",
        f"- Candidate groups: {summary['canonical_candidate_groups']}\n",
        f"- AUTO_LINK: {classifications['AUTO_LINK']}\n",
        f"- REVIEW_REQUIRED: {classifications['REVIEW_REQUIRED']}\n",
        f"- CREATE_NEW: {classifications['CREATE_NEW']}\n",
        f"- REJECT: {classifications['REJECT']}\n",
        f"- Existing Phokarta Places in scope: {summary['existing_phokarta_places_in_scope']}\n",
        f"- Existing canonical matches: {summary['existing_phokarta_matches']}\n\n",
        "A zero AUTO_LINK count is expected when the current beta catalog has no canonical Place in Didim Core. Cross-provider high-confidence groups are still CREATE_NEW candidates because no Phokarta UUID exists yet.\n\n",
        "## Safety policy\n\n",
        "External IDs remain aliases. Overture is the preferred attribute proposal and FSQ fills missing fields. Coordinates are never averaged. Category disagreements, ambiguous proximity, provider conflicts, same-provider duplicate candidates, and tourism nesting risks remain REVIEW_REQUIRED. Existing canonical fields are never overwritten by this dry run.\n\n",
        "## Physical validation\n\n",
        f"The sample contains {len(sample)} geographically distributed rows. Open `PHYSICAL_VALIDATION_SAMPLE.geojson` in any GeoJSON-capable map and record decisions in `PHYSICAL_VALIDATION_SAMPLE.csv`. The review columns are intentionally blank.\n\n",
        "Check physical existence, coordinate accuracy, provider same-place identity, canonical name, category, permanent closure, branch/sub-venue ambiguity, and any proposed existing-Place match.\n\n",
        "## Legal and security\n\n",
        "Provider provenance is retained. Human legal review remains recommended before broader public or national rollout. No FSQ token, database credential, provider authentication material, or public import endpoint is present in this package.\n",
    ])
    path.write_text("".join(lines), encoding="utf-8")
