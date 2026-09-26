from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from typing import Any, Iterable

from .canonical_attributes import (
    canonical_website_domain,
    normalize_turkish_phone,
    validate_canonical_website,
)
from .matching import (
    LinkSignals,
    categories_compatible,
    classify_link,
    cross_provider_matches,
    duplicate_candidates,
    name_similarity,
)
from .models import NormalizedPlace
from .normalization import haversine_meters, normalize_name, valid_coordinate
from .production_categories import ProductionCategoryMapper
from .quality import assess_quality


CANONICALIZATION_METHOD_VERSION = "didim-canonicalization-v3"


@dataclass(frozen=True)
class ExistingCanonicalPlace:
    place_id: str
    name: str
    category: str
    latitude: float
    longitude: float
    address: str = ""
    city: str = ""
    region: str = ""
    country: str = ""
    phone: str | None = None
    website: str | None = None
    graph_protected: bool = True
    graph_reference_count: int | None = None
    external_refs: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class CanonicalCandidate:
    candidate_id: str
    classification: str
    proposed_name: str | None
    proposed_category: str | None
    latitude: float | None
    longitude: float | None
    address: str | None
    locality: str | None
    region: str | None
    country: str | None
    phone: str | None
    website: str | None
    overture_id: str | None
    fsq_id: str | None
    overture_name: str | None
    fsq_name: str | None
    existing_place_id: str | None
    existing_place_name: str | None
    match_score: int | None
    matching_reasons: tuple[str, ...]
    risk_flags: tuple[str, ...]
    cross_provider_classification: str
    source_hashes: tuple[str, ...]
    provider_categories: tuple[str, ...]

    def to_row(self) -> dict[str, Any]:
        row = asdict(self)
        for key in ("matching_reasons", "risk_flags", "source_hashes", "provider_categories"):
            row[key] = "; ".join(row[key])
        return row


@dataclass(frozen=True)
class CanonicalizationPlan:
    candidates: tuple[CanonicalCandidate, ...]
    rejected: tuple[dict[str, Any], ...]
    cross_provider_matches: tuple[dict[str, Any], ...]
    existing_place_matches: tuple[dict[str, Any], ...]
    source_records: tuple[dict[str, Any], ...]

    def by_classification(self, classification: str) -> list[CanonicalCandidate]:
        return [row for row in self.candidates if row.classification == classification]


def normalized_source_hash(place: NormalizedPlace) -> str:
    payload = json.dumps(
        place.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _candidate_id(sources: Iterable[NormalizedPlace]) -> str:
    identities = sorted(f"{row.provider}:{row.external_id}" for row in sources)
    return "didim-" + hashlib.sha256("\n".join(identities).encode("utf-8")).hexdigest()[:24]


def _phone_key(value: str | None) -> str | None:
    decision = normalize_turkish_phone(value)
    return str(decision.canonical_value) if decision.accepted else None


def _first(values: Iterable[str | None]) -> str | None:
    return next((value.strip() for value in values if value and value.strip()), None)


def _source_row(place: NormalizedPlace, category: str | None) -> dict[str, Any]:
    website_decision = validate_canonical_website(
        place.website,
        identity_names=(place.name, *place.name_variants),
    )
    return {
        **place.to_dict(),
        "provider": place.provider,
        "external_id": place.external_id,
        "source_release": place.source_release,
        "method_version": CANONICALIZATION_METHOD_VERSION,
        "source_hash": normalized_source_hash(place),
        "name": place.name,
        "latitude": place.latitude,
        "longitude": place.longitude,
        "address": place.address,
        "locality": place.locality,
        "region": place.region,
        "country_code": place.country_code,
        "provider_categories": list(place.categories),
        "proposed_place_category": category,
        "phone": place.phone,
        # The source website stays byte-for-byte visible for provenance even when it is
        # too weak or malformed to become a canonical proposal.
        "website": place.website,
        "canonical_website_eligible": bool(website_decision.canonical_value),
        "website_validation_reason": website_decision.reason,
        "operating_status": place.operating_status,
        "observed_at": place.refreshed_date or place.created_date,
        "provenance": place.source_metadata,
    }


def _proposal(
    sources: list[NormalizedPlace], mapper: ProductionCategoryMapper
) -> tuple[dict[str, Any], list[str]]:
    overture = next((row for row in sources if row.provider == "overture"), None)
    fsq = next((row for row in sources if row.provider == "fsq"), None)
    ordered = [row for row in (overture, fsq) if row is not None]
    category_decisions = [mapper.map(row.provider, row.categories) for row in ordered]
    mapped = [decision.category for decision in category_decisions if decision.category]
    risks: list[str] = []
    if any(len(mapper.mapped_categories(row.provider, row.categories)) > 1 for row in ordered):
        risks.append("CATEGORY_CONFLICT")
    if any(
        mapper.unmapped_non_ignored_values(row.provider, row.categories)
        for row in ordered
    ):
        risks.append("CATEGORY_SOURCE_UNMAPPED")
    if not mapped:
        risks.append("CATEGORY_UNMAPPED")
        category = None
    else:
        category = mapped[0]
        if len(set(mapped)) > 1:
            risks.append("CATEGORY_CONFLICT")
    if len(ordered) == 2:
        distance = haversine_meters(
            float(ordered[0].latitude), float(ordered[0].longitude),
            float(ordered[1].latitude), float(ordered[1].longitude),
        )
        if distance > 30:
            risks.append("COORDINATE_DISAGREEMENT")
    website_decisions = []
    for row in ordered:
        peer_websites = [peer.website for peer in ordered if peer is not row and peer.website]
        decision = validate_canonical_website(
            row.website,
            identity_names=(row.name, *row.name_variants),
            corroborating_values=peer_websites,
        )
        if row.website:
            website_decisions.append((row, decision))
            if not decision.canonical_value:
                if decision.reason == "social_profile_not_canonical_website":
                    risks.append("WEBSITE_SOCIAL_PROFILE")
                elif decision.reason == "identity_unverified":
                    risks.append("WEBSITE_IDENTITY_UNVERIFIED")
                else:
                    risks.append("WEBSITE_INVALID")
    accepted_websites = [
        decision for _, decision in website_decisions if decision.canonical_value
    ]
    if len({decision.domain for decision in accepted_websites}) > 1:
        risks.append("WEBSITE_PROVIDER_CONFLICT")

    primary = overture or fsq
    assert primary is not None
    return {
        "name": _first(row.name for row in ordered),
        "category": category,
        "latitude": primary.latitude,
        "longitude": primary.longitude,
        "address": _first(row.address for row in ordered),
        "locality": _first(row.locality for row in ordered) or "Didim",
        "region": _first(row.region for row in ordered) or "Aydın",
        "country": _first(row.country_code for row in ordered) or "TR",
        "phone": _first(row.phone for row in ordered),
        "website": _first(decision.canonical_value for decision in accepted_websites),
    }, risks


def _tourism_risks(sources: list[NormalizedPlace]) -> list[str]:
    phrases = tuple(
        normalize_name(value)
        for row in sources
        for value in (row.name, *row.categories)
        if value
    )

    def contains_term(phrase: str, term: str) -> bool:
        tokens = phrase.split()
        expected = normalize_name(term).split()
        return bool(expected) and any(
            tokens[index:index + len(expected)] == expected
            for index in range(0, len(tokens) - len(expected) + 1)
        )

    def has_any(tokens: tuple[str, ...]) -> bool:
        return any(
            contains_term(phrase, token) for phrase in phrases for token in tokens
        )

    child_business = (
        "bar", "cafe", "coffee", "lobby", "nightclub", "pub", "restaurant",
        "salon", "shop", "spa", "store",
    )
    risks: list[str] = []
    if has_any(("hotel", "resort", "pansiyon")) and has_any(child_business):
        risks.append("HOTEL_SUBVENUE_RISK")
    if has_any(("marina", "liman")) and has_any(child_business):
        risks.append("MARINA_SUB_BUSINESS_RISK")
    if has_any(("beach club", "plaj club", "plaj kulubu")):
        risks.append("BEACH_VS_BEACH_CLUB_RISK")
    if has_any(("mall", "avm", "shopping center")) and has_any(child_business):
        risks.append("BUILDING_BUSINESS_NESTING_RISK")
    return risks


def _existing_evidence(
    proposal: dict[str, Any], existing: ExistingCanonicalPlace
) -> tuple[LinkSignals, float]:
    distance = haversine_meters(
        float(proposal["latitude"]), float(proposal["longitude"]),
        existing.latitude, existing.longitude,
    )
    signals = LinkSignals(
        distance_meters=distance,
        name_similarity=name_similarity(proposal["name"], existing.name),
        phone_exact=bool(
            _phone_key(proposal.get("phone"))
            and _phone_key(proposal.get("phone")) == _phone_key(existing.phone)
        ),
        website_domain_exact=bool(
            canonical_website_domain(proposal.get("website"))
            and canonical_website_domain(proposal.get("website"))
            == canonical_website_domain(existing.website)
        ),
        address_similarity=name_similarity(proposal.get("address"), existing.address),
        category_compatible=proposal.get("category") == existing.category,
    )
    return signals, distance


def _match_existing(
    sources: list[NormalizedPlace],
    proposal: dict[str, Any],
    existing_places: list[ExistingCanonicalPlace],
) -> tuple[ExistingCanonicalPlace | None, str | None, int | None, tuple[str, ...], list[dict[str, Any]]]:
    source_refs = {(row.provider.upper(), row.external_id) for row in sources}
    evaluations: list[tuple[ExistingCanonicalPlace, Any, bool, float]] = []
    audit: list[dict[str, Any]] = []
    for existing in existing_places:
        exact_ref = bool(source_refs.intersection(set(existing.external_refs)))
        signals, distance = _existing_evidence(proposal, existing)
        signals = LinkSignals(**{**asdict(signals), "exact_external_ref": exact_ref})
        if not exact_ref and distance > 125:
            continue
        decision = classify_link(signals)
        strong = sum(
            (
                bool(signals.distance_meters is not None and signals.distance_meters <= 15),
                bool(signals.name_similarity == 1.0),
                signals.phone_exact,
                signals.website_domain_exact,
            )
        )
        if existing.graph_protected and not exact_ref and decision.classification == "AUTO_LINK":
            if decision.score < 12 or strong < 3:
                decision = type(decision)(
                    "REVIEW_REQUIRED", decision.score,
                    (*decision.contributions, "graph_protected_requires_stricter_evidence"),
                )
        evaluations.append((existing, decision, exact_ref, distance))
        audit.append({
            "candidate_sources": "; ".join(sorted(f"{p}:{e}" for p, e in source_refs)),
            "existing_place_id": existing.place_id,
            "existing_place_name": existing.name,
            "distance_meters": round(distance, 3),
            "classification": decision.classification,
            "score": decision.score,
            "reasons": "; ".join(decision.contributions),
            "graph_protected": existing.graph_protected,
            "graph_reference_count": existing.graph_reference_count,
        })
    credible = [row for row in evaluations if row[1].classification != "CREATE_NEW"]
    credible.sort(key=lambda row: (-row[1].score, row[3], row[0].place_id))
    if not credible:
        return None, None, None, (), audit
    winner = credible[0]
    if len(credible) > 1 and credible[1][1].score >= max(5, winner[1].score - 2):
        reasons = (*winner[1].contributions, "multiple_credible_existing_candidates")
        return winner[0], "REVIEW_REQUIRED", winner[1].score, reasons, audit
    return winner[0], winner[1].classification, winner[1].score, winner[1].contributions, audit


def build_canonicalization_plan(
    overture: list[NormalizedPlace],
    fsq: list[NormalizedPlace],
    existing_places: list[ExistingCanonicalPlace],
    category_mapper: ProductionCategoryMapper | None = None,
) -> CanonicalizationPlan:
    mapper = category_mapper or ProductionCategoryMapper()
    rejected: list[dict[str, Any]] = []
    usable: dict[str, list[NormalizedPlace]] = {"overture": [], "fsq": []}
    source_records: list[dict[str, Any]] = []
    for provider, rows in (("overture", overture), ("fsq", fsq)):
        for source_sequence, row in enumerate(rows):
            category = mapper.map(provider, row.categories).category
            source_record = _source_row(row, category)
            source_record["source_sequence"] = source_sequence
            source_records.append(source_record)
            decision = assess_quality(row)
            if decision.usable:
                usable[provider].append(row)
            else:
                rejected.append({
                    "classification": "REJECT",
                    "provider": provider,
                    "external_id": row.external_id,
                    "name": row.name,
                    "latitude": row.latitude,
                    "longitude": row.longitude,
                    "reasons": "; ".join(decision.reasons),
                    "source_hash": normalized_source_hash(row),
                })

    cross = cross_provider_matches("didim_core", usable["overture"], usable["fsq"])
    by_overture = {row.external_id: row for row in usable["overture"]}
    by_fsq = {row.external_id: row for row in usable["fsq"]}
    duplicate_ids: set[tuple[str, str]] = set()
    for provider in ("overture", "fsq"):
        for duplicate in duplicate_candidates(provider, "didim_core", usable[provider]):
            duplicate_ids.add((provider, duplicate.left_id))
            duplicate_ids.add((provider, duplicate.right_id))

    candidates: list[CanonicalCandidate] = []
    existing_audit: list[dict[str, Any]] = []
    cross_rows: list[dict[str, Any]] = []
    for match in cross:
        cross_row = asdict(match)
        cross_rows.append(cross_row)
        sources = [
            row for row in (
                by_overture.get(match.overture_id or ""),
                by_fsq.get(match.fsq_id or ""),
            ) if row is not None
        ]
        proposal, risks = _proposal(sources, mapper)
        risks.extend(_tourism_risks(sources))
        if any((row.provider, row.external_id) in duplicate_ids for row in sources):
            risks.append("SAME_PROVIDER_DUPLICATE_CANDIDATE")
        if match.classification == "POSSIBLE_MATCH":
            risks.append("CROSS_PROVIDER_UNCERTAIN")
        if match.category_compatible is False:
            risks.append("PROVIDER_CATEGORY_CONFLICT")

        existing, existing_classification, score, reasons, audit = _match_existing(
            sources, proposal, existing_places
        )
        existing_audit.extend({"candidate_id": _candidate_id(sources), **row} for row in audit)

        if existing_classification == "AUTO_LINK" and not risks:
            classification = "AUTO_LINK"
        elif existing_classification == "REVIEW_REQUIRED":
            classification = "REVIEW_REQUIRED"
        elif risks or match.classification == "POSSIBLE_MATCH":
            classification = "REVIEW_REQUIRED"
        else:
            classification = "CREATE_NEW"
        matching_reasons = list(reasons)
        if match.classification == "HIGH_CONFIDENCE_MATCH":
            matching_reasons.append("cross_provider_high_confidence")
        elif match.classification == "POSSIBLE_MATCH":
            matching_reasons.append("cross_provider_possible_match")
        else:
            matching_reasons.append(match.classification.casefold())
        if classification == "CREATE_NEW":
            matching_reasons.append("no_credible_existing_canonical_match")

        overture_row = next((row for row in sources if row.provider == "overture"), None)
        fsq_row = next((row for row in sources if row.provider == "fsq"), None)
        candidates.append(CanonicalCandidate(
            candidate_id=_candidate_id(sources),
            classification=classification,
            proposed_name=proposal["name"],
            proposed_category=proposal["category"],
            latitude=proposal["latitude"],
            longitude=proposal["longitude"],
            address=proposal["address"],
            locality=proposal["locality"],
            region=proposal["region"],
            country=proposal["country"],
            phone=proposal["phone"],
            website=proposal["website"],
            overture_id=overture_row.external_id if overture_row else None,
            fsq_id=fsq_row.external_id if fsq_row else None,
            overture_name=overture_row.name if overture_row else None,
            fsq_name=fsq_row.name if fsq_row else None,
            existing_place_id=existing.place_id if existing else None,
            existing_place_name=existing.name if existing else None,
            match_score=score,
            matching_reasons=tuple(dict.fromkeys(matching_reasons)),
            risk_flags=tuple(dict.fromkeys(risks)),
            cross_provider_classification=match.classification,
            source_hashes=tuple(normalized_source_hash(row) for row in sources),
            provider_categories=tuple(
                dict.fromkeys(f"{row.provider}:{value}" for row in sources for value in row.categories)
            ),
        ))

    candidates.sort(key=lambda row: (row.classification, row.candidate_id))
    rejected.sort(key=lambda row: (row["provider"], row["external_id"]))
    source_records.sort(key=lambda row: (row["provider"], row["external_id"]))
    return CanonicalizationPlan(
        tuple(candidates), tuple(rejected), tuple(cross_rows),
        tuple(existing_audit), tuple(source_records),
    )
