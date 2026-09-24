from __future__ import annotations

import math
from dataclasses import dataclass
from difflib import SequenceMatcher
from urllib.parse import urlsplit

from .models import CrossProviderMatch, DuplicateCandidate, NormalizedPlace
from .normalization import haversine_meters, normalize_name, valid_coordinate


def name_similarity(left: str | None, right: str | None) -> float:
    left_normalized = normalize_name(left)
    right_normalized = normalize_name(right)
    if not left_normalized or not right_normalized:
        return 0.0
    if left_normalized == right_normalized:
        return 1.0
    return SequenceMatcher(None, left_normalized, right_normalized, autojunk=False).ratio()


PILOT_GRID_CELL_DEGREES = 0.0007


def _grid_key(
    place: NormalizedPlace, cell_degrees: float = PILOT_GRID_CELL_DEGREES
) -> tuple[int, int]:
    return (
        math.floor(float(place.latitude) / cell_degrees),
        math.floor(float(place.longitude) / cell_degrees),
    )


def duplicate_candidates(
    provider: str, area: str, places: list[NormalizedPlace]
) -> list[DuplicateCandidate]:
    candidates: list[DuplicateCandidate] = []
    grid: dict[tuple[int, int], list[int]] = {}
    for index, place in enumerate(places):
        if not valid_coordinate(place.latitude, place.longitude):
            continue
        key = _grid_key(place)
        for lat_offset in (-1, 0, 1):
            for lon_offset in (-1, 0, 1):
                for other_index in grid.get((key[0] + lat_offset, key[1] + lon_offset), []):
                    other = places[other_index]
                    distance = haversine_meters(
                        float(place.latitude), float(place.longitude),
                        float(other.latitude), float(other.longitude),
                    )
                    if distance > 30.0:
                        continue
                    similarity = name_similarity(place.name, other.name)
                    if similarity < 0.82:
                        continue
                    classification = (
                        "HIGH_CONFIDENCE_DUPLICATE"
                        if similarity == 1.0 or (distance <= 15.0 and similarity >= 0.94)
                        else "AMBIGUOUS_PAIR"
                    )
                    candidates.append(
                        DuplicateCandidate(
                            provider, area, other.external_id, place.external_id,
                            other.name or "", place.name or "", round(distance, 3),
                            round(similarity, 6), classification,
                        )
                    )
        grid.setdefault(key, []).append(index)
    return sorted(candidates, key=lambda row: (-row.name_similarity, row.distance_meters))


def categories_compatible(left: NormalizedPlace, right: NormalizedPlace) -> bool:
    if left.benchmark_category.value == "UNMAPPED" or right.benchmark_category.value == "UNMAPPED":
        return True
    return left.benchmark_category == right.benchmark_category


def cross_provider_matches(
    area: str,
    overture: list[NormalizedPlace],
    fsq: list[NormalizedPlace],
) -> list[CrossProviderMatch]:
    possible: list[tuple[float, float, int, int, bool]] = []
    # A grid keeps the dense urban comparison bounded. A 0.0007-degree cell is
    # wider than 50 m in longitude across the six Turkey pilot latitudes, so
    # the adjacent-cell neighborhood cannot omit an in-threshold pair.
    right_grid: dict[tuple[int, int], list[int]] = {}
    for right_index, right in enumerate(fsq):
        if valid_coordinate(right.latitude, right.longitude):
            right_grid.setdefault(_grid_key(right), []).append(right_index)
    for left_index, left in enumerate(overture):
        if not valid_coordinate(left.latitude, left.longitude):
            continue
        key = _grid_key(left)
        for lat_offset in (-1, 0, 1):
            for lon_offset in (-1, 0, 1):
                for right_index in right_grid.get(
                    (key[0] + lat_offset, key[1] + lon_offset), []
                ):
                    right = fsq[right_index]
                    distance = haversine_meters(
                        float(left.latitude), float(left.longitude),
                        float(right.latitude), float(right.longitude),
                    )
                    if distance > 50.0:
                        continue
                    similarity = name_similarity(left.name, right.name)
                    compatible = categories_compatible(left, right)
                    if similarity >= 0.80:
                        possible.append(
                            (similarity, distance, left_index, right_index, compatible)
                        )

    possible.sort(key=lambda row: (-row[0], row[1]))
    used_left: set[int] = set()
    used_right: set[int] = set()
    results: list[CrossProviderMatch] = []
    for similarity, distance, left_index, right_index, compatible in possible:
        if left_index in used_left or right_index in used_right:
            continue
        left, right = overture[left_index], fsq[right_index]
        classification = (
            "HIGH_CONFIDENCE_MATCH"
            if distance <= 25.0 and similarity >= 0.93 and compatible
            else "POSSIBLE_MATCH"
        )
        used_left.add(left_index)
        used_right.add(right_index)
        results.append(
            CrossProviderMatch(
                area, left.external_id, right.external_id, left.name, right.name,
                round(distance, 3), round(similarity, 6), compatible, classification,
            )
        )
    for index, place in enumerate(overture):
        if index not in used_left:
            results.append(CrossProviderMatch(
                area, place.external_id, None, place.name, None, None, None, None, "OVERTURE_ONLY"
            ))
    for index, place in enumerate(fsq):
        if index not in used_right:
            results.append(CrossProviderMatch(
                area, None, place.external_id, None, place.name, None, None, None, "FSQ_ONLY"
            ))
    return results


@dataclass(frozen=True)
class LinkSignals:
    exact_external_ref: bool = False
    known_crosswalk: bool = False
    distance_meters: float | None = None
    name_similarity: float | None = None
    phone_exact: bool = False
    website_domain_exact: bool = False
    address_similarity: float | None = None
    category_compatible: bool = False


@dataclass(frozen=True)
class LinkDecision:
    classification: str
    score: int
    contributions: tuple[str, ...]


def classify_link(signals: LinkSignals) -> LinkDecision:
    if signals.exact_external_ref:
        return LinkDecision("AUTO_LINK", 100, ("exact_external_ref",))
    if signals.known_crosswalk:
        return LinkDecision("AUTO_LINK", 100, ("known_crosswalk",))

    score = 0
    contributions: list[str] = []
    strong = 0
    if signals.distance_meters is not None:
        if signals.distance_meters <= 15:
            score += 4; strong += 1; contributions.append("distance<=15m:+4")
        elif signals.distance_meters <= 30:
            score += 3; contributions.append("distance<=30m:+3")
        elif signals.distance_meters <= 75:
            score += 1; contributions.append("distance<=75m:+1")
    if signals.name_similarity is not None:
        if signals.name_similarity == 1.0:
            score += 4; strong += 1; contributions.append("name_exact:+4")
        elif signals.name_similarity >= 0.94:
            score += 3; contributions.append("name>=0.94:+3")
        elif signals.name_similarity >= 0.82:
            score += 1; contributions.append("name>=0.82:+1")
    if signals.phone_exact:
        score += 4; strong += 1; contributions.append("phone_exact:+4")
    if signals.website_domain_exact:
        score += 4; strong += 1; contributions.append("website_domain_exact:+4")
    if signals.address_similarity is not None and signals.address_similarity >= 0.90:
        score += 1; contributions.append("address>=0.90:+1")
    if signals.category_compatible:
        score += 1; contributions.append("category_compatible:+1")

    if score >= 10 and strong >= 2:
        classification = "AUTO_LINK"
    elif score >= 5:
        classification = "REVIEW_REQUIRED"
    else:
        classification = "CREATE_NEW"
    return LinkDecision(classification, score, tuple(contributions))


def website_domain(value: str | None) -> str | None:
    if not value:
        return None
    candidate = value if "://" in value else f"https://{value}"
    domain = urlsplit(candidate).hostname
    return domain.casefold().removeprefix("www.") if domain else None
