from __future__ import annotations

import random
from collections import Counter, defaultdict
from dataclasses import asdict
from datetime import date, datetime
from typing import Any, Iterable

from .matching import duplicate_candidates, haversine_meters, name_similarity
from .models import BenchmarkCategory, GoldPlace, NormalizedPlace, PilotArea
from .normalization import valid_coordinate
from .quality import assess_quality


FIXED_SAMPLE_SEED = 5501


def group_by_area(places: Iterable[NormalizedPlace]) -> dict[str, list[NormalizedPlace]]:
    grouped: dict[str, list[NormalizedPlace]] = defaultdict(list)
    for place in places:
        area = str(place.source_metadata.get("area") or "UNKNOWN")
        grouped[area].append(place)
    return dict(grouped)


def _percent(numerator: int, denominator: int) -> float | None:
    return round(numerator * 100.0 / denominator, 3) if denominator else None


def _complete(places: list[NormalizedPlace], field: str) -> float | None:
    return _percent(sum(bool(getattr(place, field)) for place in places), len(places))


def _fresh_within_days(value: str | None, days: int = 365) -> bool:
    if not value:
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00")).date()
    except ValueError:
        try:
            parsed = date.fromisoformat(value[:10])
        except ValueError:
            return False
    return (date.today() - parsed).days <= days


def calculate_metrics(
    provider: str,
    areas: list[PilotArea],
    normalized: list[NormalizedPlace],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    grouped = group_by_area(normalized)
    metric_rows: list[dict[str, Any]] = []
    category_rows: list[dict[str, Any]] = []
    duplicate_rows: list[dict[str, Any]] = []
    for area in areas:
        raw = grouped.get(area.key, [])
        decisions = [assess_quality(place) for place in raw]
        usable = [place for place, decision in zip(raw, decisions, strict=True) if decision.usable]
        duplicates = duplicate_candidates(provider, area.key, usable)
        duplicate_rows.extend(asdict(row) for row in duplicates)
        mapped = [place for place in raw if place.benchmark_category != BenchmarkCategory.UNMAPPED]
        source_categories = [category for place in raw for category in place.categories]
        unmapped_source = Counter(
            category
            for place in raw
            if place.benchmark_category == BenchmarkCategory.UNMAPPED
            for category in (place.categories or ("<NO_CATEGORY>",))
        )
        distribution = Counter(place.benchmark_category.value for place in usable)
        status_supported = provider in {"overture", "fsq"}
        freshness_supported = provider == "fsq" or any(place.refreshed_date for place in raw)
        metric_rows.append({
            "provider": provider,
            "area": area.key,
            "total_raw_records": len(raw),
            "usable_records": len(usable),
            "name_completeness_pct": _complete(raw, "name"),
            "coordinate_validity_pct": _percent(
                sum(valid_coordinate(p.latitude, p.longitude) for p in raw), len(raw)
            ),
            "address_completeness_pct": _complete(raw, "address"),
            "locality_completeness_pct": _complete(raw, "locality"),
            "category_completeness_pct": _percent(sum(bool(p.categories) for p in raw), len(raw)),
            "phone_completeness_pct": _complete(raw, "phone"),
            "website_completeness_pct": _complete(raw, "website"),
            "operating_status_coverage_pct": _complete(raw, "operating_status")
            if status_supported else "NOT AVAILABLE",
            "freshness_coverage_pct": _complete(raw, "refreshed_date")
            if freshness_supported else "NOT AVAILABLE",
            "refreshed_within_365_days_pct": _percent(
                sum(_fresh_within_days(p.refreshed_date) for p in raw), len(raw)
            ) if freshness_supported else "NOT AVAILABLE",
            "duplicate_candidate_pairs": len(duplicates),
            "duplicate_candidate_rate_per_100_usable": round(len(duplicates) * 100 / len(usable), 3)
            if usable else None,
            "high_confidence_duplicate_pairs": sum(
                row.classification == "HIGH_CONFIDENCE_DUPLICATE" for row in duplicates
            ),
            "ambiguous_duplicate_pairs": sum(row.classification == "AMBIGUOUS_PAIR" for row in duplicates),
            "obvious_junk_records": sum(decision.junk for decision in decisions),
            "obvious_junk_rate_pct": _percent(sum(decision.junk for decision in decisions), len(raw)),
            "category_distribution": dict(sorted(distribution.items())),
        })
        category_rows.append({
            "provider": provider,
            "area": area.key,
            "raw_records": len(raw),
            "records_with_source_category": sum(bool(place.categories) for place in raw),
            "raw_category_values": len(source_categories),
            "mapped_records": len(mapped),
            "unmapped_records": len(raw) - len(mapped),
            "mapping_percentage": _percent(len(mapped), len(raw)),
            "top_unmapped_categories": "; ".join(
                f"{value}:{count}" for value, count in unmapped_source.most_common(10)
            ),
        })
    return metric_rows, category_rows, duplicate_rows


def known_place_recall(
    provider: str,
    gold: list[GoldPlace],
    normalized: list[NormalizedPlace],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    grouped = group_by_area(normalized)
    detail: list[dict[str, Any]] = []
    summary: list[dict[str, Any]] = []
    for area in sorted({item.area for item in gold}):
        area_gold = [item for item in gold if item.area == area]
        candidates = [place for place in grouped.get(area, []) if assess_quality(place).usable]
        matched = missing = ambiguous = 0
        for known in area_gold:
            possible: list[tuple[float, float, NormalizedPlace]] = []
            for place in candidates:
                if not valid_coordinate(place.latitude, place.longitude):
                    continue
                distance = haversine_meters(
                    known.latitude, known.longitude, float(place.latitude), float(place.longitude)
                )
                if distance > 250.0:
                    continue
                similarity = name_similarity(known.name, place.name)
                category_ok = place.benchmark_category == known.category
                # Exact/near-exact names may recover a Place whose provider taxonomy
                # is missing or wrong. Looser aliases require compatible mapped
                # categories; this prevents pairs such as "Hıdırlık Kulesi" and
                # "Hıdırlık Taksi" from becoming false recall matches.
                if similarity >= 0.95 or (similarity >= 0.88 and category_ok):
                    possible.append((similarity, distance, place))
            possible.sort(key=lambda row: (-row[0], row[1]))
            if not possible:
                status = "MISSING"; missing += 1; chosen = None
            elif len(possible) > 1 and possible[1][0] >= possible[0][0] - 0.03:
                status = "AMBIGUOUS"; ambiguous += 1; chosen = possible[0]
            else:
                status = "MATCHED"; matched += 1; chosen = possible[0]
            detail.append({
                "provider": provider,
                "area": area,
                "known_name": known.name,
                "known_category": known.category.value,
                "status": status,
                "matched_external_id": chosen[2].external_id if chosen else "",
                "matched_name": chosen[2].name if chosen else "",
                "distance_meters": round(chosen[1], 3) if chosen else "",
                "name_similarity": round(chosen[0], 6) if chosen else "",
                "source_reference": known.source_reference,
            })
        summary.append({
            "provider": provider,
            "area": area,
            "gold_set_total": len(area_gold),
            "matched": matched,
            "missing": missing,
            "ambiguous": ambiguous,
            "recall_pct": _percent(matched, len(area_gold)),
        })
    return summary, detail


def review_sample(
    providers: dict[str, list[NormalizedPlace]], per_stratum: int = 2
) -> list[dict[str, Any]]:
    rng = random.Random(FIXED_SAMPLE_SEED)
    result: list[dict[str, Any]] = []
    for provider, places in sorted(providers.items()):
        strata: dict[tuple[str, str], list[NormalizedPlace]] = defaultdict(list)
        for place in places:
            if assess_quality(place).usable:
                strata[(str(place.source_metadata.get("area")), place.benchmark_category.value)].append(place)
        for (area, category), values in sorted(strata.items()):
            selected = rng.sample(values, min(per_stratum, len(values)))
            for place in selected:
                result.append({
                    "provider": provider,
                    "area": area,
                    "external_id": place.external_id,
                    "name": place.name,
                    "category": category,
                    "latitude": place.latitude,
                    "longitude": place.longitude,
                    "address": place.address,
                    "website": place.website,
                    "quality_or_confidence": place.confidence_or_quality,
                    "match_notes": "",
                    "sample_seed": FIXED_SAMPLE_SEED,
                })
    return result
