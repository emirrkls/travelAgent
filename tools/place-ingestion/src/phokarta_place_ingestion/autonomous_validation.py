from __future__ import annotations

import csv
import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from datetime import date, datetime
from enum import StrEnum
from pathlib import Path
from typing import Any, Iterable, Protocol, Sequence

from .canonical_attributes import (
    AttributeDecision,
    canonical_website_domain,
    normalize_turkish_phone,
    validate_canonical_address,
    validate_canonical_category,
    validate_canonical_coordinates,
    validate_canonical_name,
    validate_canonical_website,
)
from .canonicalization import (
    CANONICALIZATION_METHOD_VERSION,
    CanonicalCandidate,
    build_canonicalization_plan,
)
from .didim_pilot import (
    REPOSITORY_ROOT,
    _candidate_from_csv,
    _load_reproducible_inputs,
)
from .normalization import haversine_meters, normalize_name
from .matching import name_similarity
from .production_categories import PLACE_CATEGORIES, ProductionCategoryMapper
from .reporting import write_csv, write_json


AUTONOMOUS_VALIDATION_METHOD_VERSION = "didim-autonomous-validation-v2"
AUTONOMY_REPORTING_SCHEMA_VERSION = "didim-autonomy-accounting-v1"
AUTONOMY_ARTIFACT_STATUS = "ARTIFACTS_VALIDATED_PENDING_FULL_RELEASE_GATE"
DIDIM_CENTER = (37.3751, 27.2678)
DIDIM_RADIUS_METERS = 6000.0
DEFAULT_REFERENCE_DATE = date(2026, 9, 23)
MAX_FRESHNESS_AGE_DAYS = 730
_FROZEN_SOURCE_SHA256 = "80da83671aa45726bde9703415999af36cc980bf5440d268f85dfb20417ccb7b"
_FROZEN_CANDIDATE_SHA256 = "718da802801d69c0c54d639943782a18e5789b61d43dec39b979493624745c20"
_FROZEN_REJECTED_SHA256 = "97d50c8bce46784ebb3cce48ebd8a4f8678d2a00150a70ed1a6e6665338bb2da"
_FROZEN_SAMPLE_SHA256 = "6f24540e4847ebb574625a5fec99bc3d3bed0820e4971ea6d8f917b8246a52c7"
_FROZEN_SOURCE_COUNTS = {"fsq": 15125, "overture": 3799}
_PRODUCTION_CATEGORY_MAPPER = ProductionCategoryMapper()


class AutonomousAction(StrEnum):
    AUTO_LINK = "AUTO_LINK"
    AUTO_CREATE = "AUTO_CREATE"
    AUTO_ENRICH = "AUTO_ENRICH"
    AUTO_REJECT = "AUTO_REJECT"
    QUARANTINE = "QUARANTINE"


class SourceRecordState(StrEnum):
    SOURCE_REJECTED = "SOURCE_REJECTED"


class CatalogLifecycle(StrEnum):
    ACTIVE = "ACTIVE"
    PROVISIONAL = "PROVISIONAL"
    RETIRED = "RETIRED"


class EvidenceDimension(StrEnum):
    EXISTENCE = "EXISTENCE"
    PROVIDER_AGREEMENT = "PROVIDER_AGREEMENT"
    IDENTITY = "IDENTITY"
    LOCATION = "LOCATION"
    NAME = "NAME"
    CATEGORY = "CATEGORY"
    PHONE = "PHONE"
    WEBSITE_DOMAIN = "WEBSITE_DOMAIN"
    ADDRESS = "ADDRESS"
    FRESHNESS = "FRESHNESS"
    OPERATING_STATUS = "OPERATING_STATUS"
    DUPLICATE_RISK = "DUPLICATE_RISK"
    ENTITY_HIERARCHY = "ENTITY_HIERARCHY"
    SOURCE_QUALITY = "SOURCE_QUALITY"
    EXISTING_CATALOG = "EXISTING_CATALOG"
    EXTERNAL = "EXTERNAL"


class EvidenceStrength(StrEnum):
    NONE = "NONE"
    WEAK = "WEAK"
    MODERATE = "MODERATE"
    STRONG = "STRONG"
    VERY_STRONG = "VERY_STRONG"


class ExistenceConfidence(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class HardBlocker(StrEnum):
    POSSIBLE_SUBVENUE = "POSSIBLE_SUBVENUE"
    SAME_NAME_MULTIPLE_NEARBY = "SAME_NAME_MULTIPLE_NEARBY"
    CATEGORY_CONFLICT = "CATEGORY_CONFLICT"
    PROVIDER_GEOMETRY_CONFLICT = "PROVIDER_GEOMETRY_CONFLICT"
    SAME_PROVIDER_DUPLICATE_CONFLICT = "SAME_PROVIDER_DUPLICATE_CONFLICT"
    POSSIBLE_BRANCH_CONFUSION = "POSSIBLE_BRANCH_CONFUSION"
    TOURISM_NESTING = "TOURISM_NESTING"
    UNVERIFIED_IDENTITY = "UNVERIFIED_IDENTITY"
    SUSPICIOUS_WEBSITE = "SUSPICIOUS_WEBSITE"
    LARGE_COORDINATE_DISAGREEMENT = "LARGE_COORDINATE_DISAGREEMENT"
    UNSUPPORTED_CATEGORY = "UNSUPPORTED_CATEGORY"
    UNSAFE_CANONICAL_ATTRIBUTE = "UNSAFE_CANONICAL_ATTRIBUTE"
    OPERATIONAL_STATUS_CONFLICT = "OPERATIONAL_STATUS_CONFLICT"
    SOURCE_LINEAGE_DEPENDENCY = "SOURCE_LINEAGE_DEPENDENCY"


@dataclass(frozen=True)
class EvidenceSignal:
    dimension: EvidenceDimension
    strength: EvidenceStrength
    reason_code: str
    detail: str
    sources: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["dimension"] = self.dimension.value
        value["strength"] = self.strength.value
        return value


@dataclass(frozen=True)
class FieldProposal:
    field: str
    raw_values: tuple[Any, ...]
    canonical_value: Any | None
    accepted: bool
    reason_code: str
    evidence_strength: EvidenceStrength
    source_observations: tuple[dict[str, Any], ...] = ()
    can_overwrite_existing: bool = False

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["evidence_strength"] = self.evidence_strength.value
        return value


@dataclass(frozen=True)
class AutonomousDecision:
    candidate_id: str
    old_classification: str
    action: AutonomousAction
    lifecycle: CatalogLifecycle | None
    reason_code: str
    existence_confidence: ExistenceConfidence
    hard_blockers: tuple[HardBlocker, ...]
    evidence: tuple[EvidenceSignal, ...]
    field_proposals: tuple[FieldProposal, ...]
    canary_eligible: bool
    overture_id: str | None
    fsq_id: str | None
    existing_place_id: str | None
    name: str | None
    category: str | None
    latitude: float | None
    longitude: float | None
    method_version: str = AUTONOMOUS_VALIDATION_METHOD_VERSION

    def to_row(self) -> dict[str, Any]:
        return {
            "method_version": self.method_version,
            "candidate_id": self.candidate_id,
            "old_classification": self.old_classification,
            "autonomous_decision": self.action.value,
            "catalog_lifecycle": self.lifecycle.value if self.lifecycle else None,
            "decision_reason": self.reason_code,
            "existence_confidence": self.existence_confidence.value,
            "hard_blockers": [item.value for item in self.hard_blockers],
            "canary_eligible": self.canary_eligible,
            "overture_id": self.overture_id,
            "fsq_id": self.fsq_id,
            "existing_place_id": self.existing_place_id,
            "name": self.name,
            "category": self.category,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "evidence": [item.to_dict() for item in self.evidence],
            "field_proposals": [item.to_dict() for item in self.field_proposals],
        }


@dataclass(frozen=True)
class SourceRejectionDecision:
    provider: str
    external_id: str
    reason_codes: tuple[str, ...]
    source_hash: str | None
    state: SourceRecordState = SourceRecordState.SOURCE_REJECTED
    method_version: str = AUTONOMOUS_VALIDATION_METHOD_VERSION

    def to_row(self) -> dict[str, Any]:
        return {
            "method_version": self.method_version,
            "source_state": self.state.value,
            "provider": self.provider,
            "external_id": self.external_id,
            "decision_reason": "; ".join(self.reason_codes),
            "source_hash": self.source_hash,
            "catalog_lifecycle": None,
        }


def decide_source_rejection(row: dict[str, Any]) -> SourceRejectionDecision:
    """Convert an explicit source-quality failure into a non-catalog rejection."""

    reasons = tuple(
        reason.strip()
        for reason in str(row.get("reasons") or "").split(";")
        if reason.strip()
    )
    if not reasons:
        raise ValueError("SOURCE_REJECTED requires an explicit source-quality reason")
    return SourceRejectionDecision(
        provider=str(row.get("provider") or ""),
        external_id=str(row.get("external_id") or ""),
        reason_codes=reasons,
        source_hash=row.get("source_hash"),
    )


class ExternalEvidenceProvider(Protocol):
    """Extension point only; external evidence is never authoritative by itself."""

    provider_name: str

    def collect(
        self, candidate: CanonicalCandidate, source_rows: Sequence[dict[str, Any]]
    ) -> tuple[EvidenceSignal, ...]: ...


class NoopExternalEvidenceProvider:
    provider_name = "NOOP"

    def collect(
        self, candidate: CanonicalCandidate, source_rows: Sequence[dict[str, Any]]
    ) -> tuple[EvidenceSignal, ...]:
        del candidate, source_rows
        return ()


_RISK_BLOCKERS: dict[str, tuple[HardBlocker, ...]] = {
    "CATEGORY_CONFLICT": (HardBlocker.CATEGORY_CONFLICT,),
    "PROVIDER_CATEGORY_CONFLICT": (HardBlocker.CATEGORY_CONFLICT,),
    "COORDINATE_DISAGREEMENT": (HardBlocker.PROVIDER_GEOMETRY_CONFLICT,),
    "SAME_PROVIDER_DUPLICATE_CANDIDATE": (
        HardBlocker.SAME_PROVIDER_DUPLICATE_CONFLICT,
    ),
    "CROSS_PROVIDER_UNCERTAIN": (HardBlocker.UNVERIFIED_IDENTITY,),
    "WEBSITE_INVALID": (HardBlocker.SUSPICIOUS_WEBSITE,),
    "WEBSITE_SOCIAL_PROFILE": (HardBlocker.SUSPICIOUS_WEBSITE,),
    "WEBSITE_IDENTITY_UNVERIFIED": (HardBlocker.SUSPICIOUS_WEBSITE,),
    "WEBSITE_PROVIDER_CONFLICT": (HardBlocker.SUSPICIOUS_WEBSITE,),
    "CATEGORY_UNMAPPED": (HardBlocker.UNSUPPORTED_CATEGORY,),
    "CATEGORY_SOURCE_UNMAPPED": (HardBlocker.UNSUPPORTED_CATEGORY,),
    # These flags were persisted by the corrected canonicalization replay.  They
    # must remain fail-closed even when the autonomous hierarchy detector cannot
    # reconstruct the original provider-taxonomy context from a CSV row alone.
    "HOTEL_SUBVENUE_RISK": (
        HardBlocker.POSSIBLE_SUBVENUE,
        HardBlocker.TOURISM_NESTING,
    ),
    "MARINA_SUB_BUSINESS_RISK": (
        HardBlocker.POSSIBLE_SUBVENUE,
        HardBlocker.TOURISM_NESTING,
    ),
    "BEACH_VS_BEACH_CLUB_RISK": (
        HardBlocker.POSSIBLE_SUBVENUE,
        HardBlocker.TOURISM_NESTING,
    ),
    "BUILDING_BUSINESS_NESTING_RISK": (HardBlocker.POSSIBLE_SUBVENUE,),
}

_PARENT_TOKENS = frozenset({
    "avm", "hotel", "marin", "marina", "mall", "otel", "resort", "shopping center",
    "tatil koyu",
})
_SUBVENUE_TOKENS = frozenset({
    "bar", "beach", "beach club", "beach clup", "cafe", "coffee", "kafe",
    "lobby", "plaj club", "plaj clup", "plaj kulubu", "pool", "pool bar",
    "restaurant", "restoran", "shop", "spa", "store", "yacht club",
})
_BEACH_CLUB_TOKENS = frozenset({
    "beach club", "beach clup", "plaj club", "plaj clup", "plaj kulubu",
})
_DOMAIN_PARENT_TERMS = frozenset({"hotel", "otel", "resort", "marin", "marina", "mall"})


def _contains_term(phrase: str, term: str) -> bool:
    """Whole-token/whole-phrase matching; raw substrings are forbidden."""

    tokens = normalize_name(phrase).split()
    expected = normalize_name(term).split()
    return bool(expected) and any(
        tokens[index:index + len(expected)] == expected
        for index in range(0, len(tokens) - len(expected) + 1)
    )


def _website_parent_terms(values: Iterable[str | None]) -> tuple[str, ...]:
    """Extract only bounded parent-entity markers from syntactically valid host labels."""

    found: set[str] = set()
    for value in values:
        domain = canonical_website_domain(value)
        if not domain:
            continue
        for raw_label in domain.split(".")[:-1]:
            label = normalize_name(raw_label.replace("-", " ")).replace(" ", "")
            for term in _DOMAIN_PARENT_TERMS:
                if label == term or label.startswith(term) or label.endswith(term):
                    found.add(term)
    return tuple(sorted(found))


def detect_entity_hierarchy(
    candidate: CanonicalCandidate,
    source_rows: Sequence[dict[str, Any]],
) -> tuple[HardBlocker, ...]:
    """Detect actual parent/child ambiguity without flagging a bona fide hotel/marina."""

    phrases = [
        normalize_name(str(value))
        for value in (
            candidate.proposed_name,
            *(candidate.provider_categories or ()),
            *(row.get("name") for row in source_rows),
        )
        if value
    ]
    phrases.extend(_website_parent_terms((
        candidate.website,
        *(row.get("website") for row in source_rows),
    )))
    parent_evidence = any(
        _contains_term(phrase, parent)
        for phrase in phrases for parent in _PARENT_TOKENS
    )
    child_evidence = (
        candidate.proposed_category in {"BAR", "BEACH", "CAFE", "RESTAURANT"}
        or any(
            _contains_term(phrase, child)
            for phrase in phrases for child in _SUBVENUE_TOKENS
        )
    )
    # A beach-club source has no unambiguous representation in the current broad
    # category set.  It may be a standalone business, a resort subvenue, or a
    # provider-mapped beach/nightlife entity, so it remains quarantined even when
    # the parent resort is absent from the provider row.
    beach_club_ambiguity = any(
        _contains_term(phrase, token)
        for phrase in phrases for token in _BEACH_CLUB_TOKENS
    )
    ambiguous = (parent_evidence and child_evidence) or beach_club_ambiguity
    if not ambiguous:
        return ()
    blockers = [HardBlocker.POSSIBLE_SUBVENUE]
    if beach_club_ambiguity or any(
        any(
            _contains_term(phrase, token)
            for token in ("hotel", "otel", "resort", "marin", "marina", "tatil koyu")
        )
        for phrase in phrases
    ):
        blockers.append(HardBlocker.TOURISM_NESTING)
    return tuple(blockers)


def _field(
    name: str,
    decision: AttributeDecision,
    source_observations: Iterable[dict[str, Any]],
    strength: EvidenceStrength,
) -> FieldProposal:
    observations = tuple(source_observations)
    return FieldProposal(
        field=name,
        raw_values=tuple(
            observation["raw_value"] for observation in observations
            if observation.get("raw_value") not in (None, "")
        ),
        canonical_value=decision.canonical_value,
        accepted=decision.accepted,
        reason_code=decision.reason,
        evidence_strength=strength if decision.accepted else EvidenceStrength.NONE,
        source_observations=observations,
        # Existing canonical fields are never overwritten by provider proposals.
        can_overwrite_existing=False,
    )


def _source_observation(
    row: dict[str, Any],
    raw_value: Any,
    decision: AttributeDecision,
) -> dict[str, Any]:
    return {
        "provider": str(row.get("provider") or ""),
        "external_id": str(row.get("external_id") or ""),
        "raw_value": raw_value,
        "accepted": decision.accepted,
        "normalized_value": decision.canonical_value,
        "validation_reason": decision.reason,
    }


def _field_strength(
    field: str,
    observations: Sequence[dict[str, Any]],
) -> EvidenceStrength:
    valid = [row["normalized_value"] for row in observations if row["accepted"]]
    if not valid:
        return EvidenceStrength.NONE
    if len(valid) == 1:
        return EvidenceStrength.MODERATE
    if field == "name":
        agrees = all(
            name_similarity(str(valid[0]), str(value)) >= 0.93 for value in valid[1:]
        )
    elif field == "coordinates":
        origin = valid[0]
        agrees = all(
            haversine_meters(
                float(origin["latitude"]), float(origin["longitude"]),
                float(value["latitude"]), float(value["longitude"]),
            ) <= 30
            for value in valid[1:]
        )
    elif field == "address":
        agrees = all(
            name_similarity(str(valid[0]), str(value)) >= 0.90 for value in valid[1:]
        )
    elif field == "website":
        domains = [canonical_website_domain(str(value)) for value in valid]
        agrees = bool(domains[0]) and len(set(domains)) == 1
    else:
        agrees = len({
            json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            for value in valid
        }) == 1
    if not agrees:
        return EvidenceStrength.WEAK
    if field in {"phone", "website"}:
        return EvidenceStrength.VERY_STRONG
    return EvidenceStrength.STRONG


def validate_candidate_fields(
    candidate: CanonicalCandidate,
    source_rows: Sequence[dict[str, Any]],
) -> tuple[FieldProposal, ...]:
    names = tuple(row.get("name") for row in source_rows)
    name = validate_canonical_name(candidate.proposed_name)
    category = validate_canonical_category(
        candidate.proposed_category, allowed_categories=PLACE_CATEGORIES
    )
    coordinates = validate_canonical_coordinates(
        candidate.latitude,
        candidate.longitude,
        center=DIDIM_CENTER,
        max_distance_meters=DIDIM_RADIUS_METERS,
    )
    address = validate_canonical_address(candidate.address)
    phone = normalize_turkish_phone(candidate.phone)
    website = validate_canonical_website(
        candidate.website,
        identity_names=(candidate.proposed_name, *names),
    )
    website_attribute = AttributeDecision(
        candidate.website,
        website.canonical_value,
        bool(website.canonical_value),
        website.reason,
    )
    observations: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in source_rows:
        raw_coordinates = {
            "latitude": row.get("latitude"), "longitude": row.get("longitude")
        }
        source_website = validate_canonical_website(
            row.get("website"), identity_names=(candidate.proposed_name, *names)
        )
        source_decisions = {
            "name": validate_canonical_name(row.get("name")),
            "category": validate_canonical_category(
                row.get("proposed_place_category"), allowed_categories=PLACE_CATEGORIES
            ),
            "coordinates": validate_canonical_coordinates(
                row.get("latitude"), row.get("longitude"),
                center=DIDIM_CENTER, max_distance_meters=DIDIM_RADIUS_METERS,
            ),
            "address": validate_canonical_address(row.get("address")),
            "phone": normalize_turkish_phone(row.get("phone")),
            "website": AttributeDecision(
                row.get("website"), source_website.canonical_value,
                bool(source_website.canonical_value), source_website.reason,
            ),
        }
        raw_by_field = {
            "name": row.get("name"),
            "category": row.get("proposed_place_category"),
            "coordinates": raw_coordinates,
            "address": row.get("address"),
            "phone": row.get("phone"),
            "website": row.get("website"),
        }
        for field, source_decision in source_decisions.items():
            observations[field].append(_source_observation(
                row, raw_by_field[field], source_decision
            ))
    candidate_decisions = {
        "name": name,
        "category": category,
        "coordinates": coordinates,
        "address": address,
        "phone": phone,
        "website": website_attribute,
    }
    return (
        *(
            _field(
                field, candidate_decisions[field], observations[field],
                _field_strength(field, observations[field]),
            )
            for field in (
                "name", "category", "coordinates", "address", "phone", "website"
            )
        ),
    )


def _source_distance(source_rows: Sequence[dict[str, Any]]) -> float | None:
    positioned = [
        row for row in source_rows
        if isinstance(row.get("latitude"), (int, float))
        and isinstance(row.get("longitude"), (int, float))
    ]
    if len(positioned) != 2:
        return None
    return haversine_meters(
        float(positioned[0]["latitude"]), float(positioned[0]["longitude"]),
        float(positioned[1]["latitude"]), float(positioned[1]["longitude"]),
    )


def _is_negative_operating_signal(value: Any) -> bool:
    normalized = normalize_name(str(value or "")).replace(" ", "_")
    negative = {
        "closed", "delete", "does_not_exist", "doesnt_exist", "duplicate",
        "inappropriate", "moved", "not_a_place", "permanently_closed",
        "private_venue", "privatevenue", "relocated", "unresolved_closed",
        "unresolved_delete", "unresolved_does_not_exist",
        "unresolved_doesnt_exist", "unresolved_duplicate",
        "unresolved_inappropriate", "unresolved_not_a_place",
        "unresolved_private_venue", "unresolved_privatevenue",
    }
    return bool(
        normalized in negative
        or normalized.startswith("closed_")
        or normalized.endswith("_closed")
    )


def _has_negative_operational_signal(source_rows: Sequence[dict[str, Any]]) -> bool:
    for row in source_rows:
        metadata = row.get("provenance") or row.get("source_metadata") or {}
        flags = metadata.get("unresolved_flags", ()) if isinstance(metadata, dict) else ()
        if isinstance(flags, str):
            flags = (flags,)
        if _is_negative_operating_signal(row.get("operating_status")) or any(
            _is_negative_operating_signal(flag) for flag in flags
        ):
            return True
    return False


def _dependent_source_lineage_refs(
    source_rows: Sequence[dict[str, Any]],
) -> tuple[str, ...]:
    """Return Overture observations derived from the same upstream FSQ dataset.

    Provider wrappers are not independent evidence when Overture provenance names
    Foursquare as an upstream source. Exact FSQ record IDs are retained in the reason
    detail, but an omitted/different record ID still cannot turn one provider's data
    into two independent observations.
    """

    fsq_ids = {
        str(row.get("external_id") or "").strip().casefold()
        for row in source_rows
        if str(row.get("provider") or "").casefold() == "fsq"
        and str(row.get("external_id") or "").strip()
    }
    if not fsq_ids:
        return ()
    dependencies: set[str] = set()
    for row in source_rows:
        if str(row.get("provider") or "").casefold() != "overture":
            continue
        metadata = row.get("provenance") or row.get("source_metadata") or {}
        raw_sources = metadata.get("sources", ()) if isinstance(metadata, dict) else ()
        if isinstance(raw_sources, dict):
            raw_sources = (raw_sources,)
        if not isinstance(raw_sources, (list, tuple)):
            continue
        for upstream in raw_sources:
            if not isinstance(upstream, dict):
                continue
            identities = {
                normalize_name(str(upstream.get(field) or "")).replace(" ", "_")
                for field in ("provider", "dataset", "resource")
            }
            if not any(
                value == "fsq" or "foursquare" in value for value in identities
            ):
                continue
            record_id = str(upstream.get("record_id") or "").strip()
            relationship = "same_record" if record_id.casefold() in fsq_ids else "same_provider"
            dependencies.add(
                f"overture:{row.get('external_id')}->fsq:{record_id or 'UNSPECIFIED'}:{relationship}"
            )
    return tuple(sorted(dependencies))


def _has_dependent_source_lineage(source_rows: Sequence[dict[str, Any]]) -> bool:
    return bool(_dependent_source_lineage_refs(source_rows))


def _provider_category_values(row: dict[str, Any]) -> tuple[str, ...]:
    raw = row.get("provider_categories")
    if raw is None:
        raw = row.get("categories")
    if isinstance(raw, str):
        stripped = raw.strip()
        if not stripped:
            return ()
        if stripped.startswith("["):
            try:
                decoded = json.loads(stripped)
            except json.JSONDecodeError:
                decoded = None
            if isinstance(decoded, list):
                return tuple(str(value) for value in decoded if str(value).strip())
        return tuple(value.strip() for value in stripped.split("; ") if value.strip())
    if isinstance(raw, (list, tuple)):
        return tuple(str(value) for value in raw if str(value).strip())
    return ()


def _has_intra_provider_category_conflict(
    source_rows: Sequence[dict[str, Any]],
) -> bool:
    for row in source_rows:
        provider = str(row.get("provider") or "").casefold()
        values = _provider_category_values(row)
        if len(_PRODUCTION_CATEGORY_MAPPER.mapped_categories(provider, values)) > 1:
            return True
    return False


def _has_unknown_provider_category(
    source_rows: Sequence[dict[str, Any]],
) -> bool:
    return any(
        _PRODUCTION_CATEGORY_MAPPER.unmapped_non_ignored_values(
            str(row.get("provider") or "").casefold(),
            _provider_category_values(row),
        )
        for row in source_rows
    )


def _parse_date(value: Any) -> date | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return date.fromisoformat(text[:10])
    except ValueError:
        try:
            return datetime.fromisoformat(text.replace("Z", "+00:00")).date()
        except ValueError:
            return None


def _observation_date(row: dict[str, Any]) -> date | None:
    for field in ("observed_at", "refreshed_date", "created_date"):
        if parsed := _parse_date(row.get(field)):
            return parsed
    return None


def _freshness_counts(
    source_rows: Sequence[dict[str, Any]], reference_date: date,
) -> tuple[int, int, int]:
    recent = stale = undated = 0
    for row in source_rows:
        observed = _observation_date(row)
        if observed is None:
            undated += 1
            continue
        age = (reference_date - observed).days
        if 0 <= age <= MAX_FRESHNESS_AGE_DAYS:
            recent += 1
        else:
            stale += 1
    return recent, stale, undated


def _reference_date_from_provenance(provenance: dict[str, Any]) -> date:
    releases = [
        _parse_date(value.get("resolved_release"))
        for value in provenance.get("providers", {}).values()
        if isinstance(value, dict)
    ]
    valid = [value for value in releases if value is not None]
    if not valid:
        raise ValueError("source package releases do not provide a deterministic reference date")
    return max(valid)


def _trusted_existing_identity(candidate: CanonicalCandidate) -> bool:
    """Require deterministic same-place proof independently of existence evidence."""

    if not candidate.existing_place_id:
        return False
    if "multiple_credible_existing_candidates" in candidate.matching_reasons:
        return False
    if "exact_external_ref" in candidate.matching_reasons:
        return True
    strong_reasons = sum(
        any(marker in reason for marker in (
            "distance<=15m", "name_exact", "phone_exact", "website_domain_exact"
        ))
        for reason in candidate.matching_reasons
    )
    return bool((candidate.match_score or 0) >= 12 and strong_reasons >= 3)


def _base_evidence(
    candidate: CanonicalCandidate,
    source_rows: Sequence[dict[str, Any]],
    *,
    reference_date: date,
    negative_operational_signal: bool,
) -> tuple[ExistenceConfidence, list[EvidenceSignal]]:
    sources = tuple(sorted(
        f"{row.get('provider')}:{row.get('external_id')}" for row in source_rows
    ))
    signals: list[EvidenceSignal] = []
    lineage_dependencies = _dependent_source_lineage_refs(source_rows)
    trusted_existing = _trusted_existing_identity(candidate)
    if candidate.existing_place_id:
        confidence = ExistenceConfidence.HIGH if trusted_existing else ExistenceConfidence.LOW
        signals.append(EvidenceSignal(
            EvidenceDimension.EXISTING_CATALOG,
            EvidenceStrength.VERY_STRONG if trusted_existing else EvidenceStrength.WEAK,
            (
                "EXACT_OR_STRICT_EXISTING_MATCH"
                if trusted_existing else "EXISTING_IDENTITY_UNPROVEN"
            ),
            (
                "Existing identity has an exact trusted reference or three independent "
                "strong matching signals."
                if trusted_existing else
                "Existing-place identity lacks independently provable trusted evidence or "
                "has multiple credible canonical targets."
            ),
            sources,
        ))
    elif candidate.cross_provider_classification == "HIGH_CONFIDENCE_MATCH":
        recent, _, _ = _freshness_counts(source_rows, reference_date)
        providers = {
            str(row.get("provider") or "").casefold() for row in source_rows
        }
        independent_pair = {"overture", "fsq"}.issubset(providers)
        high_confidence = bool(
            independent_pair
            and not lineage_dependencies
            and recent == len(source_rows)
            and not negative_operational_signal
        )
        confidence = (
            ExistenceConfidence.HIGH if high_confidence else ExistenceConfidence.MEDIUM
        )
        signals.append(EvidenceSignal(
            EvidenceDimension.PROVIDER_AGREEMENT,
            EvidenceStrength.STRONG if high_confidence
            else EvidenceStrength.WEAK if lineage_dependencies
            else EvidenceStrength.MODERATE,
            (
                "INDEPENDENT_PROVIDER_PAIR"
                if high_confidence
                else "PROVIDER_PAIR_SHARED_SOURCE_LINEAGE"
                if lineage_dependencies
                else "PROVIDER_PAIR_SOURCE_INCOMPLETE"
                if not independent_pair
                else "PROVIDER_PAIR_NEGATIVE_OPERATING_SIGNAL"
                if negative_operational_signal
                else "PROVIDER_PAIR_STALE_OR_UNDATED"
            ),
            (
                "Overture and FSQ independently identify one entity, with at least one "
                "recent observation each and no negative operating signal."
                if high_confidence
                else "Overture provenance identifies Foursquare upstream data, so the two "
                f"rows are not independent: {', '.join(lineage_dependencies)}."
                if lineage_dependencies
                else "Provider agreement cannot confer high existence confidence without "
                "both provider observations, recent evidence, and a clean operating-status "
                "record."
            ),
            sources,
        ))
    elif candidate.cross_provider_classification == "POSSIBLE_MATCH":
        confidence = ExistenceConfidence.LOW
        signals.append(EvidenceSignal(
            EvidenceDimension.IDENTITY,
            EvidenceStrength.WEAK,
            "AMBIGUOUS_PROVIDER_PAIR",
            "Cross-provider identity is possible but not sufficiently discriminating.",
            sources,
        ))
    else:
        confidence = ExistenceConfidence.MEDIUM
        signals.append(EvidenceSignal(
            EvidenceDimension.PROVIDER_AGREEMENT,
            EvidenceStrength.MODERATE,
            "SINGLE_PROVIDER_OBSERVATION",
            "One provider supports existence; independent corroboration is absent.",
            sources,
        ))
    distance = _source_distance(source_rows)
    if distance is not None:
        strength = EvidenceStrength.STRONG if distance <= 30 else EvidenceStrength.WEAK
        signals.append(EvidenceSignal(
            EvidenceDimension.LOCATION,
            strength,
            "PROVIDER_COORDINATE_AGREEMENT" if distance <= 30 else "PROVIDER_COORDINATE_CONFLICT",
            f"Provider coordinates differ by {distance:.3f} meters.",
            sources,
        ))
    source_names = [str(row.get("name") or "") for row in source_rows if row.get("name")]
    if len(source_names) >= 2:
        similarity = name_similarity(source_names[0], source_names[1])
        signals.append(EvidenceSignal(
            EvidenceDimension.NAME,
            (
                EvidenceStrength.VERY_STRONG if similarity == 1.0
                else EvidenceStrength.STRONG if similarity >= 0.93
                else EvidenceStrength.WEAK
            ),
            (
                "PROVIDER_NAME_EXACT" if similarity == 1.0
                else "PROVIDER_NAME_NEAR" if similarity >= 0.93
                else "PROVIDER_NAME_WEAK"
            ),
            f"Normalized provider-name similarity is {similarity:.6f}.",
            sources,
        ))
    confidence_strength = {
        ExistenceConfidence.HIGH: EvidenceStrength.STRONG,
        ExistenceConfidence.MEDIUM: EvidenceStrength.MODERATE,
        ExistenceConfidence.LOW: EvidenceStrength.WEAK,
    }[confidence]
    signals.append(EvidenceSignal(
        EvidenceDimension.EXISTENCE,
        confidence_strength,
        f"EXISTENCE_{confidence.value}",
        "Existence confidence is derived from independent provider/catalog evidence, not a scalar score alone.",
        sources,
    ))
    return confidence, signals


def _attribute_evidence(
    candidate: CanonicalCandidate,
    source_rows: Sequence[dict[str, Any]],
    fields: Sequence[FieldProposal],
    blockers: set[HardBlocker],
    *,
    reference_date: date,
) -> list[EvidenceSignal]:
    by_field = {row.field: row for row in fields}
    source_ids = tuple(sorted(
        f"{row.get('provider')}:{row.get('external_id')}" for row in source_rows
    ))
    dimension_by_field = {
        "name": EvidenceDimension.NAME,
        "category": EvidenceDimension.CATEGORY,
        "coordinates": EvidenceDimension.LOCATION,
        "address": EvidenceDimension.ADDRESS,
        "phone": EvidenceDimension.PHONE,
        "website": EvidenceDimension.WEBSITE_DOMAIN,
    }
    signals = [
        EvidenceSignal(
            dimension_by_field[field],
            proposal.evidence_strength if proposal.accepted else EvidenceStrength.NONE,
            proposal.reason_code.upper(),
            f"Canonical {field} {'accepted' if proposal.accepted else 'excluded'}.",
            source_ids,
        )
        for field, proposal in by_field.items()
    ]
    lineage_dependencies = _dependent_source_lineage_refs(source_rows)

    valid_phones = [
        result.canonical_value
        for row in source_rows
        if (result := normalize_turkish_phone(row.get("phone"))).accepted
    ]
    if len(valid_phones) >= 2 and len(set(valid_phones)) == 1:
        signals.append(EvidenceSignal(
            EvidenceDimension.PHONE,
            EvidenceStrength.MODERATE if lineage_dependencies else EvidenceStrength.VERY_STRONG,
            "SHARED_LINEAGE_EXACT_PHONE" if lineage_dependencies else "INDEPENDENT_EXACT_PHONE",
            "Matching phone values share upstream provider lineage."
            if lineage_dependencies else
            "Two independently valid provider phone values normalize to the same E.164 number.",
            source_ids,
        ))

    valid_domains: list[str] = []
    names = tuple(row.get("name") for row in source_rows)
    for row in source_rows:
        website = validate_canonical_website(
            row.get("website"), identity_names=(candidate.proposed_name, *names)
        )
        if website.canonical_value and website.domain:
            valid_domains.append(website.domain)
    if len(valid_domains) >= 2 and len(set(valid_domains)) == 1:
        signals.append(EvidenceSignal(
            EvidenceDimension.WEBSITE_DOMAIN,
            EvidenceStrength.MODERATE if lineage_dependencies else EvidenceStrength.VERY_STRONG,
            "SHARED_LINEAGE_EXACT_WEBSITE_DOMAIN"
            if lineage_dependencies else "INDEPENDENT_EXACT_WEBSITE_DOMAIN",
            "Matching website domains share upstream provider lineage."
            if lineage_dependencies else
            "Two independently valid, identity-related provider websites share one domain.",
            source_ids,
        ))

    recent, stale, undated = _freshness_counts(source_rows, reference_date)
    all_recent = bool(source_rows) and recent == len(source_rows)
    signals.extend((
        EvidenceSignal(
            EvidenceDimension.FRESHNESS,
            EvidenceStrength.STRONG if all_recent else EvidenceStrength.WEAK
            if recent or stale else EvidenceStrength.NONE,
            "ALL_SOURCE_OBSERVATIONS_RECENT" if all_recent
            else "SOURCE_OBSERVATION_PARTIALLY_STALE_OR_UNDATED" if recent
            else "SOURCE_OBSERVATION_STALE" if stale else "SOURCE_DATE_MISSING",
            (
                f"Reference date {reference_date.isoformat()}; recent={recent}, "
                f"stale_or_future={stale}, undated={undated}, "
                f"freshness_window_days={MAX_FRESHNESS_AGE_DAYS}."
            ),
            source_ids,
        ),
        EvidenceSignal(
            EvidenceDimension.OPERATING_STATUS,
            EvidenceStrength.WEAK if HardBlocker.OPERATIONAL_STATUS_CONFLICT in blockers
            else EvidenceStrength.MODERATE if any(row.get("operating_status") for row in source_rows)
            else EvidenceStrength.NONE,
            "NEGATIVE_OPERATING_SIGNAL" if HardBlocker.OPERATIONAL_STATUS_CONFLICT in blockers
            else "OPERATING_STATUS_PRESENT" if any(row.get("operating_status") for row in source_rows)
            else "OPERATING_STATUS_MISSING",
            "Closure, moved, or does-not-exist signals fail closed; absence never implies open.",
            source_ids,
        ),
        EvidenceSignal(
            EvidenceDimension.DUPLICATE_RISK,
            EvidenceStrength.WEAK if any(
                blocker in blockers for blocker in (
                    HardBlocker.SAME_NAME_MULTIPLE_NEARBY,
                    HardBlocker.SAME_PROVIDER_DUPLICATE_CONFLICT,
                    HardBlocker.POSSIBLE_BRANCH_CONFUSION,
                )
            ) else EvidenceStrength.STRONG,
            "DUPLICATE_BLOCKER_PRESENT" if any(
                blocker in blockers for blocker in (
                    HardBlocker.SAME_NAME_MULTIPLE_NEARBY,
                    HardBlocker.SAME_PROVIDER_DUPLICATE_CONFLICT,
                    HardBlocker.POSSIBLE_BRANCH_CONFUSION,
                )
            ) else "NO_DERIVED_DUPLICATE_BLOCKER",
            "Duplicate and branch risks are derived from provider and spatial neighborhoods.",
            source_ids,
        ),
        EvidenceSignal(
            EvidenceDimension.ENTITY_HIERARCHY,
            EvidenceStrength.WEAK if any(
                blocker in blockers for blocker in (
                    HardBlocker.POSSIBLE_SUBVENUE, HardBlocker.TOURISM_NESTING
                )
            ) else EvidenceStrength.STRONG,
            "HIERARCHY_BLOCKER_PRESENT" if any(
                blocker in blockers for blocker in (
                    HardBlocker.POSSIBLE_SUBVENUE, HardBlocker.TOURISM_NESTING
                )
            ) else "NO_DERIVED_HIERARCHY_BLOCKER",
            "Parent/subvenue ambiguity requires whole-token and spatial evidence.",
            source_ids,
        ),
        EvidenceSignal(
            EvidenceDimension.SOURCE_QUALITY,
            EvidenceStrength.WEAK if lineage_dependencies else
            EvidenceStrength.STRONG if source_rows and all(row.get("source_hash") for row in source_rows)
            else EvidenceStrength.MODERATE,
            "NON_INDEPENDENT_PROVIDER_LINEAGE" if lineage_dependencies else
            "HASHED_PROVIDER_PROVENANCE" if source_rows and all(row.get("source_hash") for row in source_rows)
            else "SOURCE_HASH_CONTEXT_UNAVAILABLE",
            f"Overture and FSQ share upstream lineage: {', '.join(lineage_dependencies)}."
            if lineage_dependencies else
            "Provider provenance is preserved and source hashes are verified by frozen replay.",
            source_ids,
        ),
    ))
    return signals


def _nearby_name_blockers(
    candidates: Sequence[CanonicalCandidate],
) -> dict[str, set[HardBlocker]]:
    by_name: dict[str, list[CanonicalCandidate]] = defaultdict(list)
    for candidate in candidates:
        name = normalize_name(candidate.proposed_name)
        if name and candidate.latitude is not None and candidate.longitude is not None:
            by_name[name].append(candidate)
    result: dict[str, set[HardBlocker]] = defaultdict(set)
    for same_name in by_name.values():
        if len(same_name) < 2:
            continue
        ordered = sorted(same_name, key=lambda row: row.candidate_id)
        for index, left in enumerate(ordered):
            for right in ordered[index + 1:]:
                distance = haversine_meters(
                    float(left.latitude), float(left.longitude),
                    float(right.latitude), float(right.longitude),
                )
                if distance <= 150:
                    result[left.candidate_id].add(HardBlocker.SAME_NAME_MULTIPLE_NEARBY)
                    result[right.candidate_id].add(HardBlocker.SAME_NAME_MULTIPLE_NEARBY)
                elif distance <= 500:
                    result[left.candidate_id].add(HardBlocker.POSSIBLE_BRANCH_CONFUSION)
                    result[right.candidate_id].add(HardBlocker.POSSIBLE_BRANCH_CONFUSION)
    return result


def _distinctive_tokens(value: str | None) -> frozenset[str]:
    generic = {
        "bar", "cafe", "didim", "hotel", "kafe", "marina", "otel", "resort",
        "restaurant", "restoran", "the",
    }
    return frozenset(
        token for token in normalize_name(value).split()
        if len(token) >= 4 and token not in generic
    )


def _nearby_hierarchy_blockers(
    candidates: Sequence[CanonicalCandidate],
) -> dict[str, set[HardBlocker]]:
    """Use a bounded geo grid to find likely venue/subvenue pairs.

    Proximity alone is insufficient. A child must be close to a hotel/resort/marina
    parent *and* share a distinctive identity token, preventing blanket blocking of
    ordinary businesses near tourist accommodation.
    """

    cell_size = 0.002  # roughly 175-220 m at Didim latitude
    grid: dict[tuple[int, int], list[CanonicalCandidate]] = defaultdict(list)
    positioned = [
        row for row in candidates
        if row.latitude is not None and row.longitude is not None
    ]
    for row in positioned:
        grid[(int(float(row.latitude) / cell_size), int(float(row.longitude) / cell_size))].append(row)

    def is_parent(row: CanonicalCandidate) -> bool:
        text = normalize_name(row.proposed_name)
        return row.proposed_category == "HOTEL" or any(
            _contains_term(text, token) for token in _PARENT_TOKENS
        )

    def is_child(row: CanonicalCandidate) -> bool:
        text = normalize_name(row.proposed_name)
        return row.proposed_category in {
            "BAR", "BEACH", "CAFE", "RESTAURANT", "ACTIVITY"
        } or any(
            _contains_term(text, token) for token in _SUBVENUE_TOKENS
        )

    result: dict[str, set[HardBlocker]] = defaultdict(set)
    for parent in sorted((row for row in positioned if is_parent(row)), key=lambda row: row.candidate_id):
        parent_tokens = _distinctive_tokens(parent.proposed_name)
        if not parent_tokens:
            continue
        cell = (int(float(parent.latitude) / cell_size), int(float(parent.longitude) / cell_size))
        neighbors = (
            row
            for dx in (-1, 0, 1)
            for dy in (-1, 0, 1)
            for row in grid.get((cell[0] + dx, cell[1] + dy), ())
        )
        for child in neighbors:
            if child.candidate_id == parent.candidate_id or not is_child(child):
                continue
            if not parent_tokens.intersection(_distinctive_tokens(child.proposed_name)):
                continue
            distance = haversine_meters(
                float(parent.latitude), float(parent.longitude),
                float(child.latitude), float(child.longitude),
            )
            if distance <= 100:
                result[child.candidate_id].update((
                    HardBlocker.POSSIBLE_SUBVENUE,
                    HardBlocker.TOURISM_NESTING,
                ))
    return result


def decide_candidate(
    candidate: CanonicalCandidate,
    source_rows: Sequence[dict[str, Any]],
    *,
    nearby_name_blockers: Iterable[HardBlocker] = (),
    external_evidence: ExternalEvidenceProvider | None = None,
    reference_date: date = DEFAULT_REFERENCE_DATE,
) -> AutonomousDecision:
    negative_operational_signal = _has_negative_operational_signal(source_rows)
    confidence, evidence = _base_evidence(
        candidate,
        source_rows,
        reference_date=reference_date,
        negative_operational_signal=negative_operational_signal,
    )
    blockers: set[HardBlocker] = set(nearby_name_blockers)
    for risk in candidate.risk_flags:
        blockers.update(_RISK_BLOCKERS.get(risk, ()))
    blockers.update(detect_entity_hierarchy(candidate, source_rows))
    distance = _source_distance(source_rows)
    if distance is not None:
        if distance > 30:
            blockers.add(HardBlocker.PROVIDER_GEOMETRY_CONFLICT)
        if distance > 250:
            blockers.add(HardBlocker.LARGE_COORDINATE_DISAGREEMENT)
    trusted_existing = _trusted_existing_identity(candidate)
    if candidate.existing_place_id and not trusted_existing:
        blockers.add(HardBlocker.UNVERIFIED_IDENTITY)
    if negative_operational_signal:
        blockers.add(HardBlocker.OPERATIONAL_STATUS_CONFLICT)
    if _has_dependent_source_lineage(source_rows):
        blockers.add(HardBlocker.SOURCE_LINEAGE_DEPENDENCY)
    if _has_intra_provider_category_conflict(source_rows):
        blockers.add(HardBlocker.CATEGORY_CONFLICT)
    if _has_unknown_provider_category(source_rows):
        blockers.add(HardBlocker.UNSUPPORTED_CATEGORY)

    fields = validate_candidate_fields(candidate, source_rows)
    required = {item.field: item for item in fields}
    if not all(required[field].accepted for field in ("name", "category", "coordinates")):
        blockers.add(HardBlocker.UNSAFE_CANONICAL_ATTRIBUTE)
    if not required["category"].accepted:
        blockers.add(HardBlocker.UNSUPPORTED_CATEGORY)
    if candidate.website and not required["website"].accepted:
        blockers.add(HardBlocker.SUSPICIOUS_WEBSITE)

    evidence.extend(_attribute_evidence(
        candidate, source_rows, fields, blockers, reference_date=reference_date
    ))
    external = external_evidence or NoopExternalEvidenceProvider()
    external_signals = external.collect(candidate, source_rows)
    # Extension evidence is recorded but cannot remove blockers or independently elevate confidence.
    evidence.extend(external_signals)

    ordered_blockers = tuple(sorted(blockers, key=lambda item: item.value))
    required_safe = not any(
        blocker in blockers
        for blocker in (
            HardBlocker.UNSAFE_CANONICAL_ATTRIBUTE,
            HardBlocker.UNSUPPORTED_CATEGORY,
            HardBlocker.SUSPICIOUS_WEBSITE,
        )
    )
    if ordered_blockers:
        action = AutonomousAction.QUARANTINE
        reason = ordered_blockers[0].value
        lifecycle = None
    elif candidate.classification == "AUTO_LINK" and trusted_existing:
        action = AutonomousAction.AUTO_LINK
        reason = "STRICT_EXISTING_IDENTITY_CONFIRMED"
        lifecycle = CatalogLifecycle.ACTIVE
    elif candidate.existing_place_id and trusted_existing:
        action = AutonomousAction.AUTO_ENRICH
        reason = "SAFE_NON_OVERWRITING_ENRICHMENT"
        lifecycle = CatalogLifecycle.ACTIVE
    elif (
        confidence == ExistenceConfidence.HIGH
        and candidate.cross_provider_classification == "HIGH_CONFIDENCE_MATCH"
        and required_safe
    ):
        action = AutonomousAction.AUTO_CREATE
        reason = "INDEPENDENT_PROVIDERS_AND_SAFE_CANONICAL_FIELDS"
        lifecycle = CatalogLifecycle.ACTIVE
    else:
        action = AutonomousAction.QUARANTINE
        reason = (
            "INSUFFICIENT_INDEPENDENT_CORROBORATION"
            if confidence == ExistenceConfidence.MEDIUM
            else "EXISTENCE_OR_IDENTITY_NOT_CONFIRMED"
        )
        lifecycle = None

    canary_eligible = bool(
        action == AutonomousAction.AUTO_CREATE
        and confidence == ExistenceConfidence.HIGH
        and not ordered_blockers
        and required_safe
        and candidate.overture_id
        and candidate.fsq_id
        and len(candidate.source_hashes) >= 2
    )
    return AutonomousDecision(
        candidate_id=candidate.candidate_id,
        old_classification=candidate.classification,
        action=action,
        lifecycle=lifecycle,
        reason_code=reason,
        existence_confidence=confidence,
        hard_blockers=ordered_blockers,
        evidence=tuple(evidence),
        field_proposals=fields,
        canary_eligible=canary_eligible,
        overture_id=candidate.overture_id,
        fsq_id=candidate.fsq_id,
        existing_place_id=candidate.existing_place_id,
        name=candidate.proposed_name,
        category=candidate.proposed_category,
        latitude=candidate.latitude,
        longitude=candidate.longitude,
    )


def evaluate_candidates(
    candidates: Sequence[CanonicalCandidate],
    source_context: dict[tuple[str, str], dict[str, Any]],
    *,
    external_evidence: ExternalEvidenceProvider | None = None,
    reference_date: date = DEFAULT_REFERENCE_DATE,
) -> tuple[AutonomousDecision, ...]:
    nearby = _nearby_name_blockers(candidates)
    for candidate_id, blockers in _nearby_hierarchy_blockers(candidates).items():
        nearby[candidate_id].update(blockers)
    decisions: list[AutonomousDecision] = []
    for candidate in sorted(candidates, key=lambda item: item.candidate_id):
        source_rows = [
            source_context[key]
            for key in (
                ("overture", candidate.overture_id or ""),
                ("fsq", candidate.fsq_id or ""),
            )
            if key in source_context
        ]
        decisions.append(decide_candidate(
            candidate,
            source_rows,
            nearby_name_blockers=nearby.get(candidate.candidate_id, ()),
            external_evidence=external_evidence,
            reference_date=reference_date,
        ))
    validate_decision_accounting(
        [candidate.candidate_id for candidate in candidates],
        [decision.to_row() for decision in decisions],
    )
    return tuple(decisions)


def validate_decision_accounting(
    candidate_ids: Sequence[str], decision_rows: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    """Fail closed unless the candidate ledger is an exact, exclusive partition."""

    expected = list(candidate_ids)
    actual = [row.get("candidate_id") for row in decision_rows]
    if any(not isinstance(value, str) or not value.strip() for value in expected):
        raise ValueError("candidate population contains a missing candidate ID")
    if len(set(expected)) != len(expected):
        raise ValueError("candidate population contains duplicate candidate IDs")
    if any(not isinstance(value, str) or not value.strip() for value in actual):
        raise ValueError("decision ledger contains a missing candidate ID")
    if len(set(actual)) != len(actual):
        raise ValueError("candidate must have exactly one final decision: duplicate candidate ID")
    if set(actual) != set(expected):
        raise ValueError("decision ledger does not exactly cover candidate IDs")
    actions: Counter[str] = Counter()
    eligible_count = 0
    for row in decision_rows:
        try:
            action = AutonomousAction(row["autonomous_decision"])
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError("candidate must have one supported final autonomous decision") from error
        actions[action.value] += 1
        if not isinstance(row.get("canary_eligible"), bool):
            raise ValueError("candidate canary_eligible must be a boolean")
        if row["canary_eligible"]:
            eligible_count += 1
            if action != AutonomousAction.AUTO_CREATE:
                raise ValueError("CANARY_ELIGIBLE must be a subset of AUTO_CREATE")
            if not isinstance(row.get("hard_blockers"), (tuple, list)):
                raise ValueError("canary eligible candidate lacks a valid hard blocker ledger")
            if row["hard_blockers"]:
                raise ValueError("canary eligible candidate has a hard blocker")
    decision_total = sum(actions.get(action.value, 0) for action in AutonomousAction)
    if decision_total != len(expected):
        raise ValueError("candidate decision totals do not equal candidate group count")
    return {
        "status": "PASS",
        "candidate_group_count": len(expected),
        "decision_count": len(decision_rows),
        "decision_total": decision_total,
        "unique_candidate_count": len(set(actual)),
        "exact_candidate_id_coverage": True,
        "one_final_decision_per_candidate": True,
        "candidate_decisions_mutually_exclusive_and_exhaustive": True,
        "canary_eligible_count": eligible_count,
        "canary_eligible_subset_auto_create": True,
        "canary_eligible_hard_blocker_count": 0,
    }


def source_record_accounting(total: int, rejected_count: int) -> dict[str, int]:
    """Source observations have their own unit, before canonical grouping."""

    if total < 0 or rejected_count < 0 or rejected_count > total:
        raise ValueError("invalid source-record accounting")
    return {
        "total": total,
        "usable": total - rejected_count,
        "rejected_before_canonical_grouping": rejected_count,
    }


def canary_eligible_breakdown(decision_rows: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Describe only eligible candidates, counting accepted canonical field evidence."""

    eligible = [row for row in decision_rows if row["canary_eligible"]]
    categories = Counter(str(row.get("category") or "UNMAPPED") for row in eligible)
    providers = Counter(
        "OVERTURE_AND_FSQ" if row.get("overture_id") and row.get("fsq_id")
        else "OVERTURE_ONLY" if row.get("overture_id")
        else "FSQ_ONLY" if row.get("fsq_id") else "NO_PROVIDER_ID"
        for row in eligible
    )
    distance_bands: Counter[str] = Counter()
    directions: Counter[str] = Counter()
    distances: list[float] = []
    evidence_fields: Counter[str] = Counter()
    for row in eligible:
        if row.get("latitude") is None or row.get("longitude") is None:
            raise ValueError("eligible candidate lacks coordinates for geographic distribution")
        latitude, longitude = float(row["latitude"]), float(row["longitude"])
        distance = haversine_meters(*DIDIM_CENTER, latitude, longitude)
        if not 0 <= distance <= DIDIM_RADIUS_METERS:
            raise ValueError("eligible candidate is outside Didim Core 6 km")
        distances.append(distance)
        distance_bands[
            "0_TO_2_KM" if distance < 2000
            else "2_TO_4_KM" if distance < 4000 else "4_TO_6_KM"
        ] += 1
        directions[
            ("N" if latitude >= DIDIM_CENTER[0] else "S")
            + ("E" if longitude >= DIDIM_CENTER[1] else "W")
        ] += 1
        for field in ("phone", "website", "address"):
            proposals = [proposal for proposal in row["field_proposals"] if proposal["field"] == field]
            if any(
                proposal["accepted"] and proposal["canonical_value"] not in (None, "")
                and any(observation.get("accepted") for observation in proposal["source_observations"])
                for proposal in proposals
            ):
                evidence_fields[field] += 1
    return {
        "count": len(eligible),
        "category_counts": dict(sorted(categories.items())),
        "provider_composition": {
            key: providers.get(key, 0)
            for key in ("OVERTURE_AND_FSQ", "OVERTURE_ONLY", "FSQ_ONLY", "NO_PROVIDER_ID")
        },
        "geographic_distribution": {
            "center": {"latitude": DIDIM_CENTER[0], "longitude": DIDIM_CENTER[1]},
            "radius_meters": DIDIM_RADIUS_METERS,
            "distance_bands": {key: distance_bands.get(key, 0) for key in ("0_TO_2_KM", "2_TO_4_KM", "4_TO_6_KM")},
            "quadrants": {key: directions.get(key, 0) for key in ("NE", "NW", "SE", "SW")},
            "minimum_distance_meters": round(min(distances), 2) if distances else None,
            "maximum_distance_meters": round(max(distances), 2) if distances else None,
            "outside_scope_count": 0,
        },
        "accepted_evidence_counts": {field: evidence_fields.get(field, 0) for field in ("phone", "website", "address")},
        "hard_blocker_count": sum(len(row["hard_blockers"]) for row in eligible),
        "evidence_count_definition": "Accepted non-empty canonical field proposal with accepted provider source observation; counts overlap.",
    }


def canary_selection_order(
    decisions: Sequence[AutonomousDecision],
) -> tuple[str, ...]:
    """Return the complete deterministic category/geometry-diverse canary order."""

    eligible = sorted(
        (row for row in decisions if row.canary_eligible),
        key=lambda row: (
            *(-value for value in canary_safety_key(row)),
            row.category or "",
            row.candidate_id,
        ),
    )
    if not eligible:
        return ()
    buckets: dict[tuple[str, int, int], list[AutonomousDecision]] = defaultdict(list)
    for row in eligible:
        buckets[(
            row.category or "UNMAPPED",
            int(float(row.latitude or 0) / 0.002),
            int(float(row.longitude or 0) / 0.002),
        )].append(row)
    # Take the safest row from each category/geo bucket before taking a second row from
    # any bucket. Stable rotating category order prevents restaurant-heavy concentration.
    for bucket, rows in buckets.items():
        buckets[bucket] = sorted(
            rows,
            key=lambda row: (
                *(-value for value in canary_safety_key(row)),
                row.candidate_id,
            ),
        )
    keys_by_category: dict[str, list[tuple[str, int, int]]] = defaultdict(list)
    for bucket in sorted(buckets):
        keys_by_category[bucket[0]].append(bucket)
    bucket_order: list[tuple[str, int, int]] = []
    while keys_by_category:
        for category in sorted(tuple(keys_by_category)):
            bucket_order.append(keys_by_category[category].pop(0))
            if not keys_by_category[category]:
                del keys_by_category[category]
    selected: list[str] = []
    while buckets:
        for bucket in bucket_order:
            if bucket not in buckets:
                continue
            rows = buckets[bucket]
            selected.append(rows.pop(0).candidate_id)
            if not rows:
                del buckets[bucket]
    return tuple(selected)


def select_stage_one(
    decisions: Sequence[AutonomousDecision], limit: int = 100
) -> tuple[str, ...]:
    """Return the exact Stage 1 prefix, capped by the eligible population."""

    if limit < 0 or limit > 100:
        raise ValueError("Stage 1 limit must be between 0 and 100")
    return canary_selection_order(decisions)[:limit]


def canary_safety_key(decision: AutonomousDecision) -> tuple[int, int, int, int]:
    """Higher tuples mean stronger evidence/completeness; blockers are excluded upstream."""

    very_strong = sum(
        signal.strength == EvidenceStrength.VERY_STRONG for signal in decision.evidence
    )
    strong = sum(signal.strength == EvidenceStrength.STRONG for signal in decision.evidence)
    optional = sum(
        proposal.accepted and proposal.field in {"address", "phone", "website"}
        for proposal in decision.field_proposals
    )
    total = sum(proposal.accepted for proposal in decision.field_proposals)
    return very_strong, strong, optional, total


def catalog_anomaly_baseline(
    decisions: Sequence[AutonomousDecision],
    rejected_count: int,
    source_context: dict[tuple[str, str], dict[str, Any]] | None = None,
    rejected_source_keys: Iterable[tuple[str, str]] = (),
) -> dict[str, Any]:
    actions = Counter(row.action.value for row in decisions)
    categories = Counter(row.category or "UNMAPPED" for row in decisions)
    blockers = Counter(
        blocker.value for row in decisions for blocker in row.hard_blockers
    )
    names = Counter(normalize_name(row.name) for row in decisions if normalize_name(row.name))
    coordinates = Counter(
        (round(float(row.latitude), 7), round(float(row.longitude), 7))
        for row in decisions if row.latitude is not None and row.longitude is not None
    )
    geo_cells = Counter(
        (round(float(row.latitude) / 0.002), round(float(row.longitude) / 0.002))
        for row in decisions if row.latitude is not None and row.longitude is not None
    )
    same_name_category: dict[tuple[str, str], list[AutonomousDecision]] = defaultdict(list)
    for row in decisions:
        same_name_category[(normalize_name(row.name), row.category or "UNMAPPED")].append(row)
    near_duplicate_pairs = 0
    for group in same_name_category.values():
        positioned = [row for row in group if row.latitude is not None and row.longitude is not None]
        for index, left in enumerate(positioned):
            for right in positioned[index + 1:]:
                if haversine_meters(
                    float(left.latitude), float(left.longitude),
                    float(right.latitude), float(right.longitude),
                ) <= 150:
                    near_duplicate_pairs += 1
    provider_refs = [
        (provider, external_id)
        for row in decisions
        for provider, external_id in (
            ("overture", row.overture_id), ("fsq", row.fsq_id)
        )
        if external_id
    ]
    referenced = set(provider_refs)
    rejected_keys = set(rejected_source_keys)
    context = source_context or {}
    canonical_assignments = Counter(
        row.existing_place_id for row in decisions if row.existing_place_id
    )
    candidate_ids = Counter(row.candidate_id for row in decisions)
    category_total = max(sum(categories.values()), 1)
    return {
        "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
        "candidate_count": len(decisions),
        "source_reject_count": rejected_count,
        "action_counts": dict(sorted(actions.items())),
        "category_counts": dict(sorted(categories.items())),
        "hard_blocker_counts": dict(sorted(blockers.items())),
        "canary_eligible_count": sum(row.canary_eligible for row in decisions),
        "duplicate_normalized_name_groups": sum(count > 1 for count in names.values()),
        "same_coordinate_cluster_count": sum(count > 1 for count in coordinates.values()),
        "same_coordinate_cluster_max_size": max(coordinates.values(), default=0),
        "same_name_same_category_near_duplicate_pairs": near_duplicate_pairs,
        "dense_geo_cell_count_ge_20": sum(count >= 20 for count in geo_cells.values()),
        "dense_geo_cell_max_count": max(geo_cells.values(), default=0),
        "category_spike_baseline": {
            category: {
                "count": count,
                "share": round(count / category_total, 8),
                "above_30_percent": count / category_total > 0.30,
            }
            for category, count in sorted(categories.items())
        },
        "out_of_scope_coordinate_count": sum(
            row.latitude is None or row.longitude is None or haversine_meters(
                DIDIM_CENTER[0], DIDIM_CENTER[1],
                float(row.latitude or 0), float(row.longitude or 0),
            ) > DIDIM_RADIUS_METERS
            for row in decisions
        ),
        "invalid_or_missing_coordinate_count": sum(
            row.latitude is None or row.longitude is None for row in decisions
        ),
        "duplicate_provider_reference_count": sum(
            count - 1 for count in Counter(provider_refs).values() if count > 1
        ),
        "duplicate_candidate_id_count": sum(
            count - 1 for count in candidate_ids.values() if count > 1
        ),
        "duplicate_existing_canonical_assignment_count": sum(
            count - 1 for count in canonical_assignments.values() if count > 1
        ),
        "source_orphan_count": sum(
            key not in referenced and key not in rejected_keys for key in context
        ),
        "source_missing_provenance_or_hash_count": sum(
            not row.get("source_hash") or not (row.get("provenance") or row.get("source_metadata"))
            for row in context.values()
        ),
        "pre_import_canonical_catalog_metrics": {
            "scope": "not_applicable_no_beta_writes",
            "new_canonical_rows": 0,
            "search_visibility_changes": 0,
            "duplicate_canonical_assignments_written": 0,
        },
    }


def _prepare_output(output: Path) -> Path:
    resolved = output.expanduser().resolve()
    if resolved == REPOSITORY_ROOT or REPOSITORY_ROOT in resolved.parents:
        raise ValueError("autonomy package must be outside the Git repository")
    if resolved.exists() and any(resolved.iterdir()):
        raise ValueError(f"autonomy output directory is not empty: {resolved}")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _source_package_artifact_sha256(package: Path) -> dict[str, str]:
    """Hash every source-package artifact consumed by autonomous replay."""

    filenames = [
        "didim_core_summary.json",
        "didim_core_source_records.jsonl",
        "didim_core_canonical_candidates.csv",
        "didim_core_rejected.csv",
    ]
    sample = package / "PHYSICAL_VALIDATION_SAMPLE.csv"
    if sample.is_file():
        filenames.append(sample.name)
    return {filename: _file_sha256(package / filename) for filename in filenames}


def verify_corrected_source_package(package: Path) -> dict[str, Any]:
    """Reject the superseded v1 package and any mutation of the frozen 2145 inputs."""

    package = package.expanduser().resolve()
    summary = json.loads((package / "didim_core_summary.json").read_text(encoding="utf-8"))
    if summary.get("method_version") != "didim-canonicalization-v2":
        raise ValueError("autonomous validation requires corrected didim-canonicalization-v2")
    if summary.get("superseded_human_review_must_not_be_ingested") is not True:
        raise ValueError("source package does not supersede the unsafe human-review workflow")
    if summary.get("source_counts") != _FROZEN_SOURCE_COUNTS:
        raise ValueError("source package counts differ from the frozen Didim 2145 inputs")
    providers = summary.get("providers", {})
    if providers.get("overture", {}).get("resolved_release") != "2026-09-23.0":
        raise ValueError("unexpected Overture release")
    if providers.get("fsq", {}).get("resolved_release") != "2026-09-15 20:07:45.157000":
        raise ValueError("unexpected FSQ release")
    if str(providers.get("fsq", {}).get("snapshot_id")) != "2325979374271449319":
        raise ValueError("unexpected FSQ snapshot")
    hashes = {
        "source_records_sha256": _file_sha256(package / "didim_core_source_records.jsonl"),
        "canonical_candidates_sha256": _file_sha256(
            package / "didim_core_canonical_candidates.csv"
        ),
        "rejected_records_sha256": _file_sha256(package / "didim_core_rejected.csv"),
        "calibration_sample_sha256": _file_sha256(
            package / "PHYSICAL_VALIDATION_SAMPLE.csv"
        ),
    }
    if hashes["source_records_sha256"] != _FROZEN_SOURCE_SHA256:
        raise ValueError("source observations differ from the frozen corrected package")
    if hashes["canonical_candidates_sha256"] != _FROZEN_CANDIDATE_SHA256:
        raise ValueError("candidate groups differ from the frozen corrected package")
    if hashes["rejected_records_sha256"] != _FROZEN_REJECTED_SHA256:
        raise ValueError("rejected source records differ from the frozen corrected package")
    if hashes["calibration_sample_sha256"] != _FROZEN_SAMPLE_SHA256:
        raise ValueError("calibration sample differs from the frozen corrected package")
    return {"summary": summary, **hashes}


def _validated_package_provenance(summary: dict[str, Any]) -> dict[str, Any]:
    method_version = str(summary.get("method_version") or "")
    prefix = "didim-canonicalization-v"
    if not method_version.startswith(prefix) or not method_version.removeprefix(prefix).isdigit():
        raise ValueError("source package has no supported canonicalization method provenance")
    scope = summary.get("scope")
    if not isinstance(scope, dict) or scope.get("key") != "didim_core":
        raise ValueError("re-evaluation source package is not Didim Core")
    center = scope.get("center")
    if (
        not isinstance(center, dict)
        or float(center.get("latitude", float("nan"))) != DIDIM_CENTER[0]
        or float(center.get("longitude", float("nan"))) != DIDIM_CENTER[1]
        or float(scope.get("radius_meters", float("nan"))) != DIDIM_RADIUS_METERS
    ):
        raise ValueError("re-evaluation source package has unexpected Didim geometry")
    providers = summary.get("providers")
    if not isinstance(providers, dict):
        raise ValueError("source package provider provenance is required")
    normalized: dict[str, dict[str, str | None]] = {}
    for provider in ("overture", "fsq"):
        value = providers.get(provider)
        if not isinstance(value, dict):
            raise ValueError(f"source package lacks {provider} provenance")
        release = str(value.get("resolved_release") or "")
        schema_version = str(value.get("schema_version") or "")
        snapshot_id = value.get("snapshot_id")
        if not release or not schema_version:
            raise ValueError(f"source package lacks {provider} release/schema provenance")
        if provider == "fsq" and not str(snapshot_id or ""):
            raise ValueError("source package lacks FSQ snapshot provenance")
        normalized[provider] = {
            "resolved_release": release,
            "schema_version": schema_version,
            "snapshot_id": None if snapshot_id is None else str(snapshot_id),
        }
    source_counts = summary.get("source_counts")
    if (
        not isinstance(source_counts, dict)
        or set(source_counts) != {"overture", "fsq"}
        or any(int(source_counts[item]) < 0 for item in source_counts)
    ):
        raise ValueError("source package has invalid provider source counts")
    return {
        "canonicalization_method_version": method_version,
        "scope": {
            "key": "didim_core",
            "center": {"latitude": DIDIM_CENTER[0], "longitude": DIDIM_CENTER[1]},
            "radius_meters": DIDIM_RADIUS_METERS,
        },
        "providers": normalized,
        "source_counts": {key: int(value) for key, value in source_counts.items()},
    }


def _unescape_csv_value(value: Any) -> Any:
    if isinstance(value, str) and len(value) > 1 and value[0] == "'" and value[1] in "=+-@":
        return value[1:]
    return value


def _rejected_semantics(row: dict[str, Any]) -> tuple[Any, ...]:
    def optional_float(value: Any) -> float | None:
        return None if value in (None, "") else float(value)

    return (
        str(row.get("classification") or ""),
        str(row.get("provider") or ""),
        str(row.get("external_id") or ""),
        _unescape_csv_value(row.get("name")) or None,
        optional_float(row.get("latitude")),
        optional_float(row.get("longitude")),
        tuple(
            value.strip()
            for value in str(row.get("reasons") or "").split(";")
            if value.strip()
        ),
        str(row.get("source_hash") or ""),
    )


def _verify_recomputed_semantics(
    reproducible: dict[str, Any],
    candidates: Sequence[CanonicalCandidate],
    rejected: Sequence[dict[str, Any]],
) -> None:
    normalized = reproducible.get("normalized")
    if not isinstance(normalized, dict) or set(normalized) != {"overture", "fsq"}:
        raise ValueError("replay package lacks normalized inputs for semantic recomputation")
    recomputed = build_canonicalization_plan(
        list(normalized["overture"]), list(normalized["fsq"]), []
    )
    if tuple(candidates) != recomputed.candidates:
        raise ValueError("candidate semantics differ from canonicalization recomputation")
    actual_rejected = tuple(sorted(_rejected_semantics(row) for row in rejected))
    expected_rejected = tuple(sorted(_rejected_semantics(row) for row in recomputed.rejected))
    if actual_rejected != expected_rejected:
        raise ValueError("rejected-record semantics differ from canonicalization recomputation")

    expected_sources = {
        (str(row["provider"]), str(row["external_id"])): row
        for row in recomputed.source_records
    }
    semantic_fields = (
        "method_version", "source_hash", "provider_categories",
        "proposed_place_category", "canonical_website_eligible",
        "website_validation_reason", "source_sequence", "provenance",
    )
    for key, actual in reproducible["source_context"].items():
        expected = expected_sources.get(key)
        if expected is None:
            raise ValueError(f"source semantics missing after recomputation: {key[0]}:{key[1]}")
        if any(actual.get(field) != expected.get(field) for field in semantic_fields):
            raise ValueError(
                f"source semantics differ from canonicalization recomputation: {key[0]}:{key[1]}"
            )
        expected_observed_at = expected.get("observed_at") or actual.get("retrieved_at")
        if actual.get("observed_at") != expected_observed_at:
            raise ValueError(
                f"source observation time differs from recomputation: {key[0]}:{key[1]}"
            )
        if not _parse_date(actual.get("retrieved_at")):
            raise ValueError(
                f"source retrieval time is missing or invalid: {key[0]}:{key[1]}"
            )


def load_validated_replay_package(package: Path) -> dict[str, Any]:
    """Load any explicit, internally consistent Didim snapshot for rule replay.

    The first autonomous dry run remains protected by its additional frozen-file
    hashes.  This generic path permits later release/snapshot packages only after
    their row hashes, grouping coverage and release/method provenance validate.
    """

    package = package.expanduser().resolve()
    summary = json.loads((package / "didim_core_summary.json").read_text(encoding="utf-8"))
    provenance = _validated_package_provenance(summary)
    method_version = provenance["canonicalization_method_version"]
    if method_version == "didim-canonicalization-v2":
        # The byte-for-byte frozen correction package is the sole historical
        # exception. Its semantics intentionally predate the v3 rules.
        verify_corrected_source_package(package)
    elif method_version != CANONICALIZATION_METHOD_VERSION:
        raise ValueError(
            "generic replay requires current canonicalization method "
            f"{CANONICALIZATION_METHOD_VERSION}"
        )
    reproducible = _load_reproducible_inputs(package)
    context = reproducible["source_context"]
    if len(context) != sum(provenance["source_counts"].values()):
        raise ValueError("source package repeats a provider external identity")
    for (provider, external_id), row in context.items():
        expected = provenance["providers"][provider]
        if row.get("method_version") != method_version:
            raise ValueError(
                f"source method provenance mismatch for {provider}:{external_id}"
            )
        if str(row.get("source_release") or "") != expected["resolved_release"]:
            raise ValueError(
                f"source release provenance mismatch for {provider}:{external_id}"
            )
        if str(row.get("schema_version") or "") != expected["schema_version"]:
            raise ValueError(
                f"source schema provenance mismatch for {provider}:{external_id}"
            )
        actual_snapshot = row.get("snapshot_id")
        actual_snapshot = None if actual_snapshot is None else str(actual_snapshot)
        if actual_snapshot != expected["snapshot_id"]:
            raise ValueError(
                f"source snapshot provenance mismatch for {provider}:{external_id}"
            )

    candidates = _read_candidates(package)
    rejected = _read_rejected(package)
    assigned: set[tuple[str, str]] = set()
    candidate_ids: set[str] = set()
    for candidate in candidates:
        if candidate.candidate_id in candidate_ids:
            raise ValueError(f"duplicate candidate ID in replay package: {candidate.candidate_id}")
        candidate_ids.add(candidate.candidate_id)
        keys = tuple(
            key for key in (
                ("overture", candidate.overture_id or ""),
                ("fsq", candidate.fsq_id or ""),
            ) if key[1]
        )
        if not keys or any(key not in context for key in keys):
            raise ValueError(f"candidate source linkage is incomplete: {candidate.candidate_id}")
        if any(key in assigned for key in keys):
            raise ValueError(f"provider source assigned to multiple candidates: {candidate.candidate_id}")
        expected_hashes = tuple(context[key]["source_hash"] for key in keys)
        if candidate.source_hashes != expected_hashes:
            raise ValueError(f"candidate source hashes are inconsistent: {candidate.candidate_id}")
        assigned.update(keys)
    for row in rejected:
        key = (str(row.get("provider") or ""), str(row.get("external_id") or ""))
        if key not in context or key in assigned:
            raise ValueError(f"rejected source linkage is inconsistent: {key[0]}:{key[1]}")
        if row.get("source_hash") != context[key].get("source_hash"):
            raise ValueError(f"rejected source hash is inconsistent: {key[0]}:{key[1]}")
        if not str(row.get("reasons") or "").strip():
            raise ValueError(f"rejected source lacks a reason: {key[0]}:{key[1]}")
        assigned.add(key)
    if assigned != set(context):
        raise ValueError("replay package has unassigned source observations")
    if method_version == CANONICALIZATION_METHOD_VERSION:
        _verify_recomputed_semantics(reproducible, candidates, rejected)
    return {
        "package": package,
        "summary": summary,
        "provenance": provenance,
        "source_package_artifact_sha256": _source_package_artifact_sha256(package),
        "reproducible": reproducible,
        "candidates": candidates,
        "rejected": rejected,
    }


def _read_candidates(package: Path) -> list[CanonicalCandidate]:
    with (package / "didim_core_canonical_candidates.csv").open(
        "r", encoding="utf-8-sig", newline=""
    ) as handle:
        return [_candidate_from_csv(row) for row in csv.DictReader(handle)]


def _read_rejected(package: Path) -> list[dict[str, Any]]:
    with (package / "didim_core_rejected.csv").open(
        "r", encoding="utf-8-sig", newline=""
    ) as handle:
        return list(csv.DictReader(handle))


def _distribution_rows(counter: Counter[str], key: str) -> list[dict[str, Any]]:
    return [
        {
            "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
            key: value,
            "count": count,
        }
        for value, count in sorted(counter.items(), key=lambda item: (-item[1], item[0]))
    ]


def _decision_digest(decisions: Sequence[AutonomousDecision]) -> str:
    payload = json.dumps(
        [row.to_row() for row in decisions],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _decision_payload_digest(rows: Sequence[dict[str, Any]]) -> str:
    payload = json.dumps(
        list(rows), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _decision_payload_from_csv(row: dict[str, str]) -> dict[str, Any]:
    def optional(name: str) -> str | None:
        value = _unescape_csv_value(row.get(name) or "")
        return str(value) if value != "" else None

    def json_list(name: str) -> list[Any]:
        raw = row.get(name) or "[]"
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ValueError(f"prior decision has invalid {name} JSON") from error
        if not isinstance(value, list):
            raise ValueError(f"prior decision {name} must be a JSON list")
        return value

    boolean = str(row.get("canary_eligible") or "").casefold()
    if boolean not in {"true", "false"}:
        raise ValueError("prior decision has invalid canary_eligible value")
    latitude = row.get("latitude") or ""
    longitude = row.get("longitude") or ""
    return {
        "method_version": str(row.get("method_version") or ""),
        "candidate_id": str(row.get("candidate_id") or ""),
        "old_classification": str(row.get("old_classification") or ""),
        "autonomous_decision": str(row.get("autonomous_decision") or ""),
        "catalog_lifecycle": optional("catalog_lifecycle"),
        "decision_reason": str(row.get("decision_reason") or ""),
        "existence_confidence": str(row.get("existence_confidence") or ""),
        "hard_blockers": json_list("hard_blockers"),
        "canary_eligible": boolean == "true",
        "overture_id": optional("overture_id"),
        "fsq_id": optional("fsq_id"),
        "existing_place_id": optional("existing_place_id"),
        "name": optional("name"),
        "category": optional("category"),
        "latitude": float(latitude) if latitude else None,
        "longitude": float(longitude) if longitude else None,
        "evidence": json_list("evidence"),
        "field_proposals": json_list("field_proposals"),
    }


def _load_verified_prior_quarantine(
    prior_root: Path, prior_summary: dict[str, Any]
) -> tuple[str, list[dict[str, Any]]]:
    prior_method = str(prior_summary.get("method_version") or "")
    expected_digest = str(prior_summary.get("decision_digest") or "")
    if not prior_method or not expected_digest:
        raise ValueError("prior autonomy package lacks method/digest provenance")

    evidence_path = prior_root / "candidate_evidence.csv"
    quarantine_path = prior_root / "quarantine.csv"
    artifact_hashes = prior_summary.get("artifact_sha256")
    if not isinstance(artifact_hashes, dict):
        raise ValueError("prior autonomy package lacks artifact hashes")
    for path in (evidence_path, quarantine_path):
        expected_hash = str(artifact_hashes.get(path.name) or "")
        if not expected_hash or _file_sha256(path) != expected_hash:
            raise ValueError(f"prior autonomy artifact hash mismatch: {path.name}")
    with evidence_path.open("r", encoding="utf-8-sig", newline="") as handle:
        evidence = [_decision_payload_from_csv(row) for row in csv.DictReader(handle)]
    candidate_ids = [str(row["candidate_id"]) for row in evidence]
    if not all(candidate_ids) or len(candidate_ids) != len(set(candidate_ids)):
        raise ValueError("prior evidence contains missing or duplicate candidate IDs")
    if candidate_ids != sorted(candidate_ids):
        raise ValueError("prior evidence rows are not in deterministic candidate order")
    if any(row["method_version"] != prior_method for row in evidence):
        raise ValueError("prior evidence rows do not match method provenance")
    try:
        for row in evidence:
            AutonomousAction(str(row["autonomous_decision"]))
            ExistenceConfidence(str(row["existence_confidence"]))
            if row["catalog_lifecycle"] is not None:
                CatalogLifecycle(str(row["catalog_lifecycle"]))
    except ValueError as error:
        raise ValueError("prior evidence contains an unsupported decision enum") from error
    if _decision_payload_digest(evidence) != expected_digest:
        raise ValueError("prior evidence digest does not match autonomy summary")
    expected_candidates = int(prior_summary.get("candidate_groups", -1))
    if expected_candidates != len(evidence):
        raise ValueError("prior evidence count does not match autonomy summary")
    actual_actions = Counter(str(row["autonomous_decision"]) for row in evidence)
    for action in AutonomousAction:
        if actual_actions[action.value] != int(
            prior_summary.get("actions", {}).get(action.value, 0)
        ):
            raise ValueError("prior evidence action counts do not match autonomy summary")
    accounting = validate_decision_accounting(candidate_ids, evidence)
    if prior_summary.get("reporting_schema_version"):
        if prior_summary.get("reporting_schema_version") != AUTONOMY_REPORTING_SCHEMA_VERSION:
            raise ValueError("prior autonomy package has unsupported reporting schema")
        if prior_summary.get("accounting_invariants") != accounting:
            raise ValueError("prior accounting invariants do not match candidate evidence")
        if prior_summary.get("candidate_decisions") != {
            action.value: actual_actions.get(action.value, 0) for action in AutonomousAction
        }:
            raise ValueError("prior candidate decision counts do not match candidate evidence")

    with quarantine_path.open("r", encoding="utf-8-sig", newline="") as handle:
        quarantine = [_decision_payload_from_csv(row) for row in csv.DictReader(handle)]
    quarantine_ids = [str(row["candidate_id"]) for row in quarantine]
    if len(quarantine_ids) != len(set(quarantine_ids)):
        raise ValueError("prior quarantine package contains duplicate candidate IDs")
    evidence_by_id = {str(row["candidate_id"]): row for row in evidence}
    expected_quarantine = [
        row for row in evidence
        if row["autonomous_decision"] == AutonomousAction.QUARANTINE.value
    ]
    if quarantine != expected_quarantine or any(
        evidence_by_id.get(str(row["candidate_id"])) != row for row in quarantine
    ):
        raise ValueError("prior quarantine rows do not match candidate evidence")
    expected_count = int(
        prior_summary.get("actions", {}).get(AutonomousAction.QUARANTINE.value, -1)
    )
    if expected_count != len(quarantine):
        raise ValueError("prior quarantine count does not match autonomy summary")
    return prior_method, quarantine


def _write_sample_decisions(
    source_package: Path,
    output: Path,
    decisions: dict[str, AutonomousDecision],
) -> None:
    sample_path = source_package / "PHYSICAL_VALIDATION_SAMPLE.csv"
    rows: list[dict[str, Any]] = []
    if sample_path.exists():
        with sample_path.open("r", encoding="utf-8-sig", newline="") as handle:
            sample = list(csv.DictReader(handle))
        if len(sample) != 30:
            raise ValueError(
                f"corrected calibration sample must contain exactly 30 rows, got {len(sample)}"
            )
        for original in sample:
            decision = decisions.get(original.get("candidate_id", ""))
            if not decision:
                raise ValueError(
                    f"calibration candidate missing from autonomous decisions: "
                    f"{original.get('candidate_id')}"
                )
            identity_evidence = [
                item.to_dict() for item in decision.evidence
                if item.dimension in {
                    EvidenceDimension.EXISTENCE,
                    EvidenceDimension.PROVIDER_AGREEMENT,
                    EvidenceDimension.IDENTITY,
                    EvidenceDimension.EXISTING_CATALOG,
                }
            ]
            rows.append({
                "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
                "review_id": original.get("review_id"),
                "candidate_id": decision.candidate_id,
                "old_classification": original.get("classification") or decision.old_classification,
                "autonomous_decision": decision.action.value,
                "existence_confidence": decision.existence_confidence.value,
                "identity_evidence": identity_evidence,
                "evidence": [item.to_dict() for item in decision.evidence],
                "hard_blockers": [item.value for item in decision.hard_blockers],
                "canonical_field_proposals": [
                    item.to_dict() for item in decision.field_proposals
                ],
                "field_decisions": [item.to_dict() for item in decision.field_proposals],
                "decision_reason": decision.reason_code,
                "human_completion_required": False,
            })
    write_csv(
        output / "AUTONOMOUS_SAMPLE_DECISIONS.csv",
        rows,
        ["method_version", "review_id", "candidate_id", "autonomous_decision"],
    )


def _write_report(path: Path, summary: dict[str, Any]) -> None:
    actions = summary["actions"]
    blockers = summary["hard_blockers"]
    quarantine = summary["quarantine_reasons"]
    action_rates = summary["action_rates_over_candidate_groups"]
    lines = [
        "# M5.5B Didim Autonomous Validation Report\n\n",
        f"Validation method: `{AUTONOMOUS_VALIDATION_METHOD_VERSION}`.\n\n",
        "This package deterministically replays the corrected Didim Core 6 km source "
        "package. It performs no beta canonical writes, does not deploy Flyway V17, and "
        "does not start Stage 1. The former row-by-row review sample is calibration output "
        "only.\n\n",
        f"Reporting schema: `{summary['reporting_schema_version']}`. Decision rules are unchanged.\n\n",
        "Accounting correction: the prior report mislabeled source observations rejected "
        "before grouping as candidate AUTO_REJECT decisions. Candidate decisions and the "
        "decision digest are unchanged. `source_rejected.csv` contains only source rejections; "
        "`auto_reject.csv` contains only canonical candidates whose final action is AUTO_REJECT.\n\n",
        "## Source record states\n\n",
        f"- Total: {summary['source_record_states']['total']}\n",
        f"- Usable for canonical grouping: {summary['source_record_states']['usable']}\n",
        f"- SOURCE_REJECTED before canonical grouping: {summary['source_record_states']['rejected_before_canonical_grouping']}\n\n",
        "## Canonical candidate decisions\n\n",
        f"- Candidate groups: {summary['candidate_groups']}\n",
        *(
            f"- {action}: {actions.get(action, 0)}"
            + f" ({action_rates[action] * 100:.3f}% of candidate groups)\n"
            for action in (
                "AUTO_LINK", "AUTO_CREATE", "AUTO_ENRICH", "AUTO_REJECT", "QUARANTINE"
            )
        ),
        f"- Canary eligible: {summary['canary_eligible']}\n",
        f"- Stage 1 planned: {summary['stage_1_planned']}\n\n",
        f"Accounting invariant: **{summary['accounting_invariants']['status']}**. "
        f"The five candidate decisions total {summary['accounting_invariants']['decision_total']}; "
        "every canonical candidate ID has exactly one final decision. SOURCE_REJECTED is a "
        "separate source-record state and is excluded from candidate decision totals. "
        "CANARY_ELIGIBLE is a subset of AUTO_CREATE with zero hard blockers. "
        "Quarantine is a candidate/evidence state, has no catalog "
        "lifecycle, and is invisible to the public catalog.\n\n",
        "## Canary eligible breakdown\n\n",
        *(
            f"- category {category}: {count}\n"
            for category, count in summary["canary_eligible_breakdown"]["category_counts"].items()
        ),
        *(
            f"- provider {provider}: {count}\n"
            for provider, count in summary["canary_eligible_breakdown"]["provider_composition"].items()
        ),
        *(
            f"- distance from Didim center {band}: {count}\n"
            for band, count in summary["canary_eligible_breakdown"]["geographic_distribution"]["distance_bands"].items()
        ),
        *(
            f"- geographic quadrant {quadrant}: {count}\n"
            for quadrant, count in summary["canary_eligible_breakdown"]["geographic_distribution"]["quadrants"].items()
        ),
        *(
            f"- accepted {field} evidence: {count}\n"
            for field, count in summary["canary_eligible_breakdown"]["accepted_evidence_counts"].items()
        ),
        f"- eligible hard blocker count: {summary['canary_eligible_breakdown']['hard_blocker_count']}\n\n",
        summary["canary_eligible_breakdown"]["evidence_count_definition"] + "\n\n",
        "## Safety policy\n\n",
        "Only blocker-free, high-confidence Overture/FSQ pairs with safe required fields "
        "are AUTO_CREATE and canary eligible. Single-provider observations remain quarantined "
        "for insufficient independent corroboration. External evidence uses a NOOP extension "
        "in this run and can never independently authorize a write. Provider values excluded "
        "from canonical proposals remain preserved in the source package. Existing canonical "
        "values are never overwritten by a provider proposal.\n\n",
        "Canary ranking first excludes every blocker, then orders by VERY_STRONG/STRONG "
        "evidence and accepted optional-field completeness, and finally round-robins across "
        "category plus coarse geo-cell buckets. Selection is deterministic.\n\n",
        "## Provider composition\n\n",
        *(
            f"- {name}: {count}\n"
            for name, count in sorted(summary["provider_group_counts"].items())
        ),
        "\n## Category distribution\n\n",
        *(
            f"- {name}: {count}\n"
            for name, count in sorted(
                summary["category_counts"].items(), key=lambda item: (-item[1], item[0])
            )
        ),
        "\n## Existence and evidence distribution\n\n",
        *(
            f"- existence {name}: {count}\n"
            for name, count in sorted(summary["existence_confidence"].items())
        ),
        *(
            f"- evidence strength {name}: {count}\n"
            for name, count in sorted(summary["evidence_strengths"].items())
        ),
        "## Hard blockers\n\n",
        *(
            f"- {reason}: {count}\n"
            for reason, count in sorted(blockers.items(), key=lambda item: (-item[1], item[0]))
        ),
        "\n## Top quarantine reasons\n\n",
        *(
            f"- {reason}: {count}\n"
            for reason, count in sorted(quarantine.items(), key=lambda item: (-item[1], item[0]))[:12]
        ),
        "\n## Decision reason distribution\n\n",
        *(
            f"- {reason}: {count}\n"
            for reason, count in sorted(
                summary["decision_reasons"].items(), key=lambda item: (-item[1], item[0])
            )
        ),
        "\n## Source-quality rejection reasons\n\n",
        *(
            f"- {reason}: {count}\n"
            for reason, count in sorted(
                summary["source_quality_reasons"].items(), key=lambda item: (-item[1], item[0])
            )
        ),
        "\n## Safety metrics\n\n",
        *(
            f"- {name}: {'unavailable without ground truth' if value is None else value}\n"
            for name, value in sorted(summary["safety_metrics"].items())
        ),
        "\n## Interpretation limits\n\n",
        "These are deterministic safety classifications, not measured precision or recall. "
        "No ground-truth set supports an invented accuracy claim. Stage 1 still requires the "
        "explicit `CONTINUE AUTONOMOUS CANARY` authorization.\n",
    ]
    path.write_text("".join(lines), encoding="utf-8")


def _write_v17_review(path: Path) -> None:
    path.write_text(
        "# Flyway V17 autonomous-pilot schema review\n\n"
        f"Required validation version: `{AUTONOMOUS_VALIDATION_METHOD_VERSION}`.\n\n"
        "The prepared migration was audited against this package contract:\n\n"
        "- `place_pilot_authorization_bindings` binds one authorization reference to one pilot key and "
        "one server-derived frozen plan digest. `place_provider_sync_runs` carries that binding "
        "plus stage, scope, method/source identity, manifest hash, per-action counts, and a "
        "five-minute claim lease for bounded crash recovery.\n"
        "- Stage claims are serialized per pilot, single-use and monotonic, enforce cumulative "
        "100/500 caps, require every prior gate to remain green, and require later-stage "
        "candidates to have an immutable prior eligible decision under the same plan.\n"
        "- `place_validation_decisions` is append-only by trigger and stores immutable method "
        "version, decision/existence state, reason, blockers, evidence, field proposals, source "
        "record IDs, candidate hash, canary eligibility, stage selection, and supersession. "
        "Its constraints prohibit canary "
        "eligibility for quarantine/reject states.\n"
        "- `place_validation_recheck_queue` models bounded quarantine re-evaluation with "
        "pending/leased/resolved state and lease consistency.\n"
        "- `place_pilot_catalog_writes` is the exact per-stage write/rollback journal; "
        "`place_pilot_canary_gates` persists automated progression gates. The backend derives "
        "relative performance change itself and rejects regressions above the locked 10% "
        "ceiling. Rollback is serialized with imports/gates and retires graph-connected Places "
        "instead of hard-deleting them.\n"
        "- `place_canonical_overrides` protects field-level community/operator decisions; "
        "external-ref event/history tables retain identity changes and rollback provenance. "
        "Source UUID reuse requires a full immutable-row match, and external-reference "
        "mutations take a transaction-scoped advisory lock before deferred redirect-cycle "
        "validation.\n"
        "- `places.catalog_status` is constrained to ACTIVE/PROVISIONAL/RETIRED, while public "
        "visibility remains ACTIVE-only. Quarantine is not a Place lifecycle value and is never "
        "inserted into `places`.\n\n"
        "Package state: TEST TARGET PREPARED; V17 NOT DEPLOYED; NO BETA WRITES. The final "
        "checkpoint records the authoritative Flyway/PostGIS/Testcontainers gate result after "
        "package generation.\n",
        encoding="utf-8",
    )


def _write_canary_runbook(path: Path, eligible_count: int, stage_one_count: int) -> None:
    path.write_text(
        "# Autonomous Didim canary runbook\n\n"
        "Scope is frozen to Didim Core 6 km. The 12 km expansion is prohibited. This "
        "package is planning evidence only and performs no live write.\n\n"
        "## Authorization boundary\n\n"
        "No stage may start until the operator supplies the exact authorization "
        "`CONTINUE AUTONOMOUS CANARY`. This is the one operational authorization gate. "
        "Without it, V17 remains undeployed and beta writes remain zero.\n\n"
        "## Deterministic stages\n\n"
        f"1. Stage 1: {stage_one_count} safest blocker-free rows (100 or all available), "
        "round-robin across category and geo-cell buckets.\n"
        f"2. Stage 2: the next {min(max(500 - stage_one_count, 0), max(eligible_count - stage_one_count, 0))} rows "
        "from the identical frozen ranking, only if every Stage 1 automated gate passes.\n"
        "3. Stage 3: remaining eligible rows from the same ranking, only if every Stage 2 "
        "gate passes. New sources or method versions require a new run, never an in-place "
        "ranking mutation.\n\n"
        "## Before the first import\n\n"
        "Capture reproducible beta search, nearby-map, and Place-detail responses, status "
        "codes, result counts, and latency percentiles for fixed Didim queries. Record the "
        "catalog anomaly baseline and database row counts. Verify import manifest digest, "
        "validation method, source hashes, authorization token, rollback journal readiness, "
        "and that only Stage 1 selected IDs are present.\n\n"
        "## Automated post-stage gates\n\n"
        "Re-run the same search/map/detail probes; verify expected row-count deltas, no "
        "candidate/provider-ref collisions, no blocker-bearing imports, no public visibility "
        "outside ACTIVE rows, no unresolved write-journal entries, no 5xx responses, and no "
        "material latency regression. Re-run catalog anomaly checks and stop progression on "
        "any new integrity anomaly.\n\n"
        "## Stop and rollback\n\n"
        "Stop immediately on import mismatch, duplicate identity, coordinate/category spike, "
        "broken search/map/detail behavior, elevated application/database errors, or rollback "
        "journal inconsistency. Roll back only rows and aliases named in the stage write "
        "journal by retiring the AUTO_CREATE catalog rows and removing their public source/ref "
        "exposure, then re-run probes and leave the affected evidence quarantined for "
        "re-evaluation. Any future AUTO_ENRICH rollback must restore immutable field "
        "before-images recorded by that future write path. Never deploy or widen "
        "scope while a stop condition is open.\n",
        encoding="utf-8",
    )


def quarantine_transition_rows(
    prior_candidates: Iterable[str | dict[str, Any]],
    current_decisions: Sequence[AutonomousDecision],
    *,
    prior_method_version: str | None = None,
) -> list[dict[str, Any]]:
    current = {row.candidate_id: row for row in current_decisions}
    current_refs = {
        row.candidate_id: frozenset(
            (provider, external_id)
            for provider, external_id in (
                ("overture", row.overture_id), ("fsq", row.fsq_id)
            )
            if external_id
        )
        for row in current_decisions
    }
    prior_rows = [
        {
            "candidate_id": value,
            "overture_id": None,
            "fsq_id": None,
        } if isinstance(value, str) else value
        for value in prior_candidates
    ]
    prior_ids = [str(row.get("candidate_id") or "") for row in prior_rows]
    if not all(prior_ids) or len(prior_ids) != len(set(prior_ids)):
        raise ValueError("prior quarantine candidates contain missing or duplicate IDs")
    rows: list[dict[str, Any]] = []
    for prior in sorted(prior_rows, key=lambda row: str(row.get("candidate_id") or "")):
        candidate_id = str(prior["candidate_id"])
        prior_refs = frozenset(
            (provider, str(prior.get(field)))
            for provider, field in (("overture", "overture_id"), ("fsq", "fsq_id"))
            if prior.get(field)
        )
        decision = current.get(candidate_id)
        if decision is not None and prior_refs and not prior_refs.issubset(
            current_refs[candidate_id]
        ):
            raise ValueError(
                f"candidate ID/provider lineage mismatch in replay: {candidate_id}"
            )
        if decision is None and prior_refs:
            successors = [
                value for value in current_decisions
                if prior_refs.issubset(current_refs[value.candidate_id])
            ]
            if len(successors) > 1:
                rows.append({
                    "prior_method_version": prior_method_version,
                    "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
                    "candidate_id": candidate_id,
                    "prior_candidate_id": candidate_id,
                    "current_candidate_id": None,
                    "lineage_candidates": sorted(
                        row.candidate_id for row in successors
                    ),
                    "prior_decision": AutonomousAction.QUARANTINE.value,
                    "current_decision": AutonomousAction.QUARANTINE.value,
                    "transition": "QUARANTINE->QUARANTINE",
                    "current_reason": "AMBIGUOUS_PROVIDER_REF_LINEAGE",
                    "hard_blockers": [HardBlocker.UNVERIFIED_IDENTITY.value],
                    "canary_eligible": False,
                })
                continue
            decision = successors[0] if successors else None
        if decision is None:
            rows.append({
                "prior_method_version": prior_method_version,
                "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
                "candidate_id": candidate_id,
                "prior_candidate_id": candidate_id,
                "current_candidate_id": None,
                "prior_decision": AutonomousAction.QUARANTINE.value,
                "current_decision": AutonomousAction.QUARANTINE.value,
                "transition": "QUARANTINE->QUARANTINE",
                "current_reason": "SOURCE_LINEAGE_MISSING",
                "hard_blockers": [HardBlocker.UNVERIFIED_IDENTITY.value],
                "canary_eligible": False,
            })
            continue
        rows.append({
            "prior_method_version": prior_method_version,
            "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
            "candidate_id": candidate_id,
            "prior_candidate_id": candidate_id,
            "current_candidate_id": decision.candidate_id,
            "prior_decision": AutonomousAction.QUARANTINE.value,
            "current_decision": decision.action.value,
            "transition": f"QUARANTINE->{decision.action.value}",
            "current_reason": decision.reason_code,
            "hard_blockers": [item.value for item in decision.hard_blockers],
            "canary_eligible": decision.canary_eligible,
        })
    return rows


def _synthetic_lineage_decision(
    prior: dict[str, Any], transition: dict[str, Any],
) -> dict[str, Any]:
    """Create a complete, chainable quarantine decision for unresolved lineage."""

    reason = str(transition["current_reason"])
    source_refs = [
        f"{provider}:{prior[field]}"
        for provider, field in (("overture", "overture_id"), ("fsq", "fsq_id"))
        if prior.get(field)
    ]
    lineage_candidates = transition.get("lineage_candidates") or []
    detail = (
        "Prior provider references resolve to multiple current candidates: "
        + ", ".join(str(value) for value in lineage_candidates)
        if lineage_candidates else
        "No current candidate uniquely preserves the prior provider-reference lineage."
    )
    field_proposals = [{
        "field": field,
        "raw_values": [],
        "canonical_value": None,
        "accepted": False,
        "reason_code": reason,
        "evidence_strength": EvidenceStrength.NONE.value,
        "source_observations": [],
        "can_overwrite_existing": False,
    } for field in ("name", "category", "coordinates", "address", "phone", "website")]
    return {
        "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
        "candidate_id": str(prior["candidate_id"]),
        "old_classification": str(
            prior.get("old_classification") or "QUARANTINE_RE_EVALUATION"
        ),
        "autonomous_decision": AutonomousAction.QUARANTINE.value,
        "catalog_lifecycle": None,
        "decision_reason": reason,
        "existence_confidence": ExistenceConfidence.LOW.value,
        "hard_blockers": [HardBlocker.UNVERIFIED_IDENTITY.value],
        "canary_eligible": False,
        "overture_id": prior.get("overture_id"),
        "fsq_id": prior.get("fsq_id"),
        "existing_place_id": prior.get("existing_place_id"),
        "name": prior.get("name"),
        "category": prior.get("category"),
        "latitude": prior.get("latitude"),
        "longitude": prior.get("longitude"),
        "evidence": [{
            "dimension": EvidenceDimension.IDENTITY.value,
            "strength": EvidenceStrength.NONE.value,
            "reason_code": reason,
            "detail": detail,
            "sources": source_refs,
        }],
        "field_proposals": field_proposals,
    }


def run_quarantine_re_evaluation(
    source_package: Path,
    prior_autonomy_package: Path,
    output: Path,
) -> dict[str, Any]:
    """Replay only the prior quarantine population and report every transition."""

    source_package = source_package.expanduser().resolve()
    validated = load_validated_replay_package(source_package)
    reproducible = validated["reproducible"]
    reference_date = _reference_date_from_provenance(validated["provenance"])
    candidates = validated["candidates"]
    all_decisions = evaluate_candidates(
        candidates, reproducible["source_context"], reference_date=reference_date
    )
    prior_root = prior_autonomy_package.expanduser().resolve()
    prior_summary = json.loads(
        (prior_root / "autonomous_summary.json").read_text(encoding="utf-8")
    )
    prior_method, prior_rows = _load_verified_prior_quarantine(prior_root, prior_summary)
    transitions = quarantine_transition_rows(
        prior_rows, all_decisions, prior_method_version=prior_method
    )
    selected = {
        str(row["current_candidate_id"])
        for row in transitions if row.get("current_candidate_id")
    }
    reevaluated = [row for row in all_decisions if row.candidate_id in selected]
    current_by_id = {row.candidate_id: row for row in reevaluated}
    prior_by_id = {str(row["candidate_id"]): row for row in prior_rows}
    decision_payloads: dict[str, dict[str, Any]] = {}
    for transition in transitions:
        current_candidate_id = transition.get("current_candidate_id")
        if current_candidate_id:
            decision = current_by_id[str(current_candidate_id)]
            payload = decision.to_row()
        else:
            prior = prior_by_id[str(transition["prior_candidate_id"])]
            payload = _synthetic_lineage_decision(prior, transition)
        candidate_id = str(payload["candidate_id"])
        existing = decision_payloads.get(candidate_id)
        if existing is not None and existing != payload:
            raise ValueError("re-evaluation produced conflicting current decision lineage")
        decision_payloads[candidate_id] = payload
    decision_rows = [decision_payloads[key] for key in sorted(decision_payloads)]
    expected_decision_ids = sorted({
        str(row["current_candidate_id"] or row["prior_candidate_id"])
        for row in transitions
    })
    accounting = validate_decision_accounting(expected_decision_ids, decision_rows)
    quarantine_decisions = [
        row for row in decision_rows
        if row["autonomous_decision"] == AutonomousAction.QUARANTINE.value
    ]
    output = _prepare_output(output)
    write_csv(
        output / "quarantine_re_evaluation.csv",
        transitions,
        [
            "prior_method_version", "method_version", "candidate_id",
            "prior_candidate_id", "current_candidate_id",
            "prior_decision", "current_decision",
        ],
    )
    write_csv(
        output / "candidate_evidence.csv",
        decision_rows,
        ["method_version", "candidate_id", "autonomous_decision"],
    )
    write_csv(
        output / "quarantine.csv", quarantine_decisions,
        ["method_version", "candidate_id", "autonomous_decision"],
    )
    quarantine_transitions = [
        row for row in transitions
        if row["current_decision"] == AutonomousAction.QUARANTINE.value
    ]
    lineage_payload = {
        "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
        "prior_method_version": prior_method,
        "transitions": transitions,
        "quarantine_transitions": quarantine_transitions,
        "current_decisions": decision_rows,
        "current_quarantine": quarantine_decisions,
    }
    write_json(output / "re_evaluation_lineage.json", lineage_payload)
    transition_counts = Counter(row["transition"] for row in transitions)
    actions = Counter(str(row["autonomous_decision"]) for row in decision_rows)
    source_counts = validated["provenance"].get("source_counts", {})
    source_total = sum(int(count) for count in source_counts.values())
    rejected_count = len(validated.get("rejected", ()))
    summary = {
        "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
        "reporting_schema_version": AUTONOMY_REPORTING_SCHEMA_VERSION,
        "status": "RE_EVALUATION_ARTIFACTS_VALIDATED",
        "prior_method_version": prior_method,
        "source_package_provenance": validated["provenance"],
        "source_package": str(source_package),
        "prior_autonomy_package": str(prior_root),
        "prior_quarantine_count": len(prior_rows),
        "missing_source_lineage_count": sum(
            row["current_reason"] == "SOURCE_LINEAGE_MISSING" for row in transitions
        ),
        "ambiguous_source_lineage_count": sum(
            row["current_reason"] == "AMBIGUOUS_PROVIDER_REF_LINEAGE"
            for row in transitions
        ),
        "transition_counts": dict(sorted(transition_counts.items())),
        "source_records": source_total,
        "source_counts": source_counts,
        "source_rejected": rejected_count,
        "source_record_states": source_record_accounting(source_total, rejected_count),
        "source_record_accounting_scope": "ENTIRE_CURRENT_SOURCE_SNAPSHOT",
        "candidate_decision_accounting_scope": "PRIOR_QUARANTINE_RE_EVALUATION_LINEAGE",
        "candidate_groups": len(decision_rows),
        "actions": {
            action.value: int(actions.get(action.value, 0)) for action in AutonomousAction
        },
        "candidate_decisions": {
            action.value: int(actions.get(action.value, 0)) for action in AutonomousAction
        },
        "accounting_invariants": accounting,
        "canary_eligible_breakdown": canary_eligible_breakdown(decision_rows),
        "decision_digest": _decision_payload_digest(decision_rows),
        "transition_digest": _decision_payload_digest(transitions),
        "quarantine_digest": _decision_payload_digest(quarantine_decisions),
        "current_quarantine_count": len(quarantine_decisions),
        "canary_eligible": sum(bool(row["canary_eligible"]) for row in decision_rows),
        "stage_1_planned": 0,
        "external_evidence_provider": "NOOP",
        "canonical_beta_writes": False,
    }
    summary["artifact_sha256"] = {
        filename: _file_sha256(output / filename)
        for filename in (
            "quarantine_re_evaluation.csv", "candidate_evidence.csv", "quarantine.csv",
            "re_evaluation_lineage.json",
        )
    }
    write_json(output / "re_evaluation_summary.json", summary)
    write_json(output / "autonomous_summary.json", summary)
    return {"summary": summary, "output": str(output), "decisions": tuple(reevaluated)}


def run_autonomous_didim(
    source_package: Path,
    output: Path,
    *,
    stage_size: int = 100,
    external_evidence: ExternalEvidenceProvider | None = None,
) -> dict[str, Any]:
    if stage_size != 100:
        raise ValueError(
            "Stage 1 size is locked to min(100, eligible); stage_size must be 100"
        )
    source_package = source_package.expanduser().resolve()
    validated = load_validated_replay_package(source_package)
    reproducible = validated["reproducible"]
    source_package_provenance = validated["provenance"]
    source_package_artifact_sha256 = validated["source_package_artifact_sha256"]
    source_method_version = source_package_provenance[
        "canonicalization_method_version"
    ]
    if source_method_version == "didim-canonicalization-v2":
        summary_scope = reproducible["summary"]["scope"]
        summary_source_counts = reproducible["summary"]["source_counts"]
    else:
        summary_scope = source_package_provenance["scope"]
        summary_source_counts = source_package_provenance["source_counts"]
    reference_date = _reference_date_from_provenance(validated["provenance"])
    output = _prepare_output(output)
    candidates = validated["candidates"]
    rejected = validated["rejected"]
    decisions = evaluate_candidates(
        candidates,
        reproducible["source_context"],
        external_evidence=external_evidence,
        reference_date=reference_date,
    )
    selection_order = canary_selection_order(decisions)
    expected_stage_one_count = min(100, len(selection_order))
    selected = set(selection_order[:expected_stage_one_count])
    if len(selected) != expected_stage_one_count:
        raise ValueError("Stage 1 selection did not produce the exact required cardinality")
    decision_rows = [row.to_row() for row in decisions]
    accounting = validate_decision_accounting(
        [candidate.candidate_id for candidate in candidates], decision_rows
    )
    actions = Counter(row.action.value for row in decisions)
    reasons = Counter(row.reason_code for row in decisions)
    blockers = Counter(
        blocker.value for row in decisions for blocker in row.hard_blockers
    )
    quarantine_reasons = Counter(
        row.reason_code for row in decisions if row.action == AutonomousAction.QUARANTINE
    )
    evidence_strengths = Counter(
        signal.strength.value for row in decisions for signal in row.evidence
    )
    evidence_dimensions = Counter(
        f"{signal.dimension.value}:{signal.strength.value}"
        for row in decisions for signal in row.evidence
    )
    existence_counts = Counter(row.existence_confidence.value for row in decisions)
    category_counts = Counter(row.category or "UNMAPPED" for row in decisions)
    provider_groups = Counter(
        (
            "OVERTURE_AND_FSQ" if row.overture_id and row.fsq_id
            else "OVERTURE_ONLY" if row.overture_id
            else "FSQ_ONLY" if row.fsq_id
            else "NO_PROVIDER_ID"
        )
        for row in decisions
    )
    source_quality_reasons = Counter(
        reason.strip()
        for row in rejected
        for reason in str(row.get("reasons") or "SOURCE_QUALITY_REJECTED").split(";")
        if reason.strip()
    )
    digest = _decision_digest(decisions)
    candidate_total = max(len(decisions), 1)
    source_total = sum(int(value) for value in summary_source_counts.values())
    subvenue_count = sum(
        bool({HardBlocker.POSSIBLE_SUBVENUE, HardBlocker.TOURISM_NESTING}.intersection(
            row.hard_blockers
        ))
        for row in decisions
    )
    grouping_count = blockers.get(HardBlocker.UNVERIFIED_IDENTITY.value, 0)
    same_provider_count = blockers.get(HardBlocker.SAME_PROVIDER_DUPLICATE_CONFLICT.value, 0)
    category_conflict_count = blockers.get(HardBlocker.CATEGORY_CONFLICT.value, 0)
    coordinate_conflict_count = blockers.get(HardBlocker.PROVIDER_GEOMETRY_CONFLICT.value, 0)
    website_count = blockers.get(HardBlocker.SUSPICIOUS_WEBSITE.value, 0)
    unmapped_count = blockers.get(HardBlocker.UNSUPPORTED_CATEGORY.value, 0)
    operational_count = blockers.get(HardBlocker.OPERATIONAL_STATUS_CONFLICT.value, 0)
    safety_metrics = {
        "grouping_identity_conflict_count": grouping_count,
        "grouping_identity_conflict_rate": round(grouping_count / candidate_total, 8),
        "same_provider_duplicate_count": same_provider_count,
        "same_provider_duplicate_rate": round(same_provider_count / candidate_total, 8),
        "category_conflict_count": category_conflict_count,
        "category_conflict_rate": round(category_conflict_count / candidate_total, 8),
        "coordinate_conflict_count": coordinate_conflict_count,
        "coordinate_conflict_rate": round(coordinate_conflict_count / candidate_total, 8),
        "subvenue_or_nesting_count": subvenue_count,
        "subvenue_or_nesting_rate": round(subvenue_count / candidate_total, 8),
        "suspicious_website_count": website_count,
        "suspicious_website_rate": round(website_count / candidate_total, 8),
        "unmapped_category_count": unmapped_count,
        "unmapped_category_rate": round(unmapped_count / candidate_total, 8),
        "negative_operational_signal_count": operational_count,
        "negative_operational_signal_rate": round(operational_count / candidate_total, 8),
        "quarantine_rate": round(actions.get(AutonomousAction.QUARANTINE.value, 0) / candidate_total, 8),
        "high_existence_rate": round(existence_counts.get(ExistenceConfidence.HIGH.value, 0) / candidate_total, 8),
        "canary_evidence_sufficiency_rate": round(
            sum(row.canary_eligible for row in decisions) / candidate_total, 8
        ),
        "ground_truth_precision": None,
        "auto_link_precision": None,
        "auto_create_precision": None,
        "false_merge_count": None,
        "false_create_count": None,
    }
    summary = {
        "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
        "reporting_schema_version": AUTONOMY_REPORTING_SCHEMA_VERSION,
        "run_id": f"didim-autonomy-{digest[:16]}",
        "decision_digest": digest,
        "status": AUTONOMY_ARTIFACT_STATUS,
        "scope": summary_scope,
        "source_package": str(source_package),
        "source_package_provenance": source_package_provenance,
        "source_package_artifact_sha256": source_package_artifact_sha256,
        "freshness_reference_date": reference_date.isoformat(),
        "freshness_window_days": MAX_FRESHNESS_AGE_DAYS,
        "source_records": source_total,
        "source_rejected": len(rejected),
        "source_record_states": source_record_accounting(source_total, len(rejected)),
        "source_counts": summary_source_counts,
        "candidate_groups": len(decisions),
        "actions": {action.value: int(actions.get(action.value, 0)) for action in AutonomousAction},
        "candidate_decisions": {action.value: int(actions.get(action.value, 0)) for action in AutonomousAction},
        "accounting_invariants": accounting,
        "canary_eligible_breakdown": canary_eligible_breakdown(decision_rows),
        "action_rates_over_candidate_groups": {
            action.value: round(actions.get(action.value, 0) / candidate_total, 8)
            for action in AutonomousAction
        },
        "source_rejection_rate": round(len(rejected) / max(source_total, 1), 8),
        "canary_eligible": sum(row.canary_eligible for row in decisions),
        "stage_1_planned": len(selected),
        "hard_blockers": dict(sorted(blockers.items())),
        "quarantine_reasons": dict(sorted(quarantine_reasons.items())),
        "decision_reasons": dict(sorted(reasons.items())),
        "evidence_strengths": dict(sorted(evidence_strengths.items())),
        "evidence_dimensions": dict(sorted(evidence_dimensions.items())),
        "existence_confidence": dict(sorted(existence_counts.items())),
        "category_counts": dict(sorted(category_counts.items())),
        "provider_group_counts": dict(sorted(provider_groups.items())),
        "source_quality_reasons": dict(sorted(source_quality_reasons.items())),
        "safety_metrics": safety_metrics,
        "external_evidence_provider": (
            external_evidence.provider_name if external_evidence else "NOOP"
        ),
        "canonical_beta_writes": False,
        "flyway_v17_deployed": False,
        "physical_row_by_row_approval_required": False,
    }
    if source_method_version == "didim-canonicalization-v2":
        summary["frozen_input_hashes"] = {
            "source_records_sha256": source_package_artifact_sha256[
                "didim_core_source_records.jsonl"
            ],
            "canonical_candidates_sha256": source_package_artifact_sha256[
                "didim_core_canonical_candidates.csv"
            ],
            "rejected_records_sha256": source_package_artifact_sha256[
                "didim_core_rejected.csv"
            ],
            "calibration_sample_sha256": source_package_artifact_sha256[
                "PHYSICAL_VALIDATION_SAMPLE.csv"
            ],
        }

    write_csv(
        output / "candidate_evidence.csv",
        decision_rows,
        ["method_version", "candidate_id", "autonomous_decision"],
    )
    for filename, action in (
        ("quarantine.csv", AutonomousAction.QUARANTINE),
        ("auto_link.csv", AutonomousAction.AUTO_LINK),
        ("auto_create.csv", AutonomousAction.AUTO_CREATE),
        ("auto_enrich.csv", AutonomousAction.AUTO_ENRICH),
        ("auto_reject.csv", AutonomousAction.AUTO_REJECT),
    ):
        write_csv(
            output / filename,
            [row.to_row() for row in decisions if row.action == action],
            ["method_version", "candidate_id", "autonomous_decision"],
        )
    reject_rows = [decide_source_rejection(row).to_row() for row in rejected]
    write_csv(
        output / "source_rejected.csv",
        reject_rows,
        ["method_version", "source_state", "provider", "external_id"],
    )
    eligible_rows = []
    selection_rank = {
        candidate_id: index for index, candidate_id in enumerate(selection_order, 1)
    }
    ranked_eligible = sorted(
        (row for row in decisions if row.canary_eligible),
        key=lambda row: (
            *(-value for value in canary_safety_key(row)), row.candidate_id
        ),
    )
    safety_rank = {row.candidate_id: index for index, row in enumerate(ranked_eligible, 1)}
    for row in decisions:
        if row.canary_eligible:
            value = row.to_row()
            value["stage_1_selected"] = row.candidate_id in selected
            value["selection_rank"] = selection_rank[row.candidate_id]
            value["selection_method"] = (
                "blocker_free_high_confidence_then_evidence_completeness_"
                "with_category_and_geo_diversity_v1"
            )
            value["selection_safety_rank"] = safety_rank[row.candidate_id]
            value["selection_safety_tuple"] = canary_safety_key(row)
            eligible_rows.append(value)
    write_csv(
        output / "canary_eligible.csv",
        eligible_rows,
        ["method_version", "candidate_id", "selection_rank", "stage_1_selected"],
    )
    write_csv(
        output / "candidate_decision_distribution.csv",
        _distribution_rows(Counter(summary["candidate_decisions"]), "autonomous_decision"),
        ["method_version", "autonomous_decision", "count"],
    )
    write_csv(
        output / "source_record_state_distribution.csv",
        _distribution_rows(Counter({
            "USABLE_FOR_CANONICAL_GROUPING": source_total - len(rejected),
            "SOURCE_REJECTED": len(rejected),
        }), "source_state"),
        ["method_version", "source_state", "count"],
    )
    write_csv(
        output / "decision_reason_distribution.csv",
        _distribution_rows(reasons, "decision_reason"),
        ["method_version", "decision_reason", "count"],
    )
    write_csv(
        output / "quarantine_reason_distribution.csv",
        _distribution_rows(quarantine_reasons, "quarantine_reason"),
        ["method_version", "quarantine_reason", "count"],
    )
    write_json(
        output / "catalog_anomaly_baseline.json",
        catalog_anomaly_baseline(
            decisions,
            len(rejected),
            reproducible["source_context"],
            (
                (str(row.get("provider")), str(row.get("external_id")))
                for row in rejected
            ),
        ),
    )
    _write_sample_decisions(
        source_package, output, {row.candidate_id: row for row in decisions}
    )
    _write_report(output / "AUTONOMY_REPORT.md", summary)
    _write_v17_review(output / "V17_SCHEMA_REVIEW.md")
    _write_canary_runbook(
        output / "CANARY_RUNBOOK.md", summary["canary_eligible"], summary["stage_1_planned"]
    )
    summary["artifact_sha256"] = {
        filename: _file_sha256(output / filename)
        for filename in (
            "candidate_evidence.csv", "quarantine.csv", "auto_link.csv",
            "auto_create.csv", "auto_enrich.csv", "auto_reject.csv",
            "source_rejected.csv", "candidate_decision_distribution.csv",
            "source_record_state_distribution.csv",
            "canary_eligible.csv",
            "AUTONOMOUS_SAMPLE_DECISIONS.csv", "catalog_anomaly_baseline.json",
            "decision_reason_distribution.csv", "quarantine_reason_distribution.csv",
            "AUTONOMY_REPORT.md", "V17_SCHEMA_REVIEW.md", "CANARY_RUNBOOK.md",
        )
    }
    write_json(output / "autonomous_summary.json", summary)
    return {"summary": summary, "output": str(output), "decisions": decisions}
