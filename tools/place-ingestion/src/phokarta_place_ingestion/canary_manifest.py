from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import re
import uuid
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Sequence

from .autonomous_validation import (
    AUTONOMOUS_VALIDATION_METHOD_VERSION,
    DIDIM_CENTER,
    DIDIM_RADIUS_METERS,
    AutonomousAction,
    AutonomousDecision,
    _decision_digest,
    _decision_payload_from_csv,
    _file_sha256,
    _has_dependent_source_lineage,
    _is_negative_operating_signal,
    _reference_date_from_provenance,
    canary_selection_order,
    evaluate_candidates,
    load_validated_replay_package,
    validate_decision_accounting,
)
from .canonicalization import CANONICALIZATION_METHOD_VERSION, CanonicalCandidate
from .didim_pilot import REPOSITORY_ROOT
from .normalization import haversine_meters
from .production_categories import PLACE_CATEGORIES


AUTHORIZATION_CONFIRMATION = "CONTINUE AUTONOMOUS CANARY"
AUTHORIZED_STATUS = "AUTONOMOUS_CANARY_AUTHORIZED"
MAX_MANIFEST_BYTES = 128 * 1024 * 1024
EXPECTED_OVERTURE_RELEASE = "2026-09-23.0"
EXPECTED_FSQ_RELEASE = "2026-09-15 20:07:45.157000"
EXPECTED_FSQ_SNAPSHOT = "2325979374271449319"
SUPPORTED_SOURCE_METHOD_VERSIONS = frozenset({
    "didim-canonicalization-v2",
    "didim-canonicalization-v3",
})
_SOURCE_NAMESPACE = uuid.UUID("c2d15a95-29f7-5c35-b852-d43aff1f3c81")
_PLACE_NAMESPACE = uuid.UUID("238147d6-fc89-5af3-8b78-559044fef2ad")
_VALID_STAGES = frozenset({"STAGE_1", "STAGE_2", "STAGE_3"})
_PILOT_RUN_KEY = re.compile(r"^[a-z0-9][a-z0-9-]{7,159}$")
_DECISION_REASON = re.compile(r"^[A-Z][A-Z0-9_]{1,119}$")
ACCOUNTING_SCHEMA_VERSION = "didim-autonomy-accounting-v1"


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _sha256_json(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _accounting_counts_match(value: Any, expected: dict[str, int]) -> bool:
    return (
        isinstance(value, dict) and set(value) == set(expected)
        and all(type(value[key]) is int and value[key] == count for key, count in expected.items())
    )


def _finite_json_number(value: Any, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be a finite JSON number")
    try:
        numeric = float(value)
    except OverflowError as error:
        raise ValueError(f"{field} must be a finite JSON number") from error
    if not math.isfinite(numeric):
        raise ValueError(f"{field} must be a finite JSON number")
    return numeric


def _validate_scoped_point(
    latitude_value: Any,
    longitude_value: Any,
    label: str,
) -> tuple[float, float]:
    latitude = _finite_json_number(latitude_value, f"{label} latitude")
    longitude = _finite_json_number(longitude_value, f"{label} longitude")
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise ValueError(f"{label} coordinates are out of range")
    if haversine_meters(
        DIDIM_CENTER[0], DIDIM_CENTER[1], latitude, longitude
    ) > DIDIM_RADIUS_METERS + 0.01:
        raise ValueError(f"{label} lies outside Didim Core")
    return latitude, longitude


def _source_method_version(row: dict[str, Any]) -> str:
    method_version = str(row.get("method_version") or "")
    if method_version not in SUPPORTED_SOURCE_METHOD_VERSIONS:
        raise ValueError("source row has an unsupported canonicalization method")
    return method_version


def _source_uuid(row: dict[str, Any]) -> str:
    identity = "\n".join((
        str(row.get("provider") or "").upper(),
        str(row.get("external_id") or ""),
        _source_method_version(row),
        str(row.get("source_hash") or ""),
    ))
    return str(uuid.uuid5(_SOURCE_NAMESPACE, identity))


def _place_uuid(candidate_id: str) -> str:
    return str(uuid.uuid5(
        _PLACE_NAMESPACE,
        f"{AUTONOMOUS_VALIDATION_METHOD_VERSION}\n{candidate_id}",
    ))


def _parse_timestamp(value: Any) -> datetime:
    text = str(value or "").strip()
    if not text:
        raise ValueError("source retrieval timestamp is required")
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.combine(date.fromisoformat(text), datetime.min.time())
        except ValueError as error:
            raise ValueError("source retrieval timestamp must be ISO formatted") from error
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _canonical_timestamp(value: Any) -> str:
    return _parse_timestamp(value).isoformat().replace("+00:00", "Z")


def _canonical_uuid(value: Any, field: str) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (AttributeError, ValueError) as error:
        raise ValueError(f"{field} must be a UUID string") from error


def _deterministic_decided_at(source_rows: Sequence[dict[str, Any]]) -> str:
    parsed = [_parse_timestamp(row.get("retrieved_at")) for row in source_rows]
    if not parsed:
        raise ValueError("manifest requires at least one source observation")
    return max(parsed).isoformat().replace("+00:00", "Z")


def _bool_csv(value: Any, field: str) -> bool:
    text = str(value or "").casefold()
    if text not in {"true", "false"}:
        raise ValueError(f"autonomy artifact has invalid {field}")
    return text == "true"


def _verify_artifacts(root: Path, summary: dict[str, Any]) -> None:
    hashes = summary.get("artifact_sha256")
    if not isinstance(hashes, dict) or not hashes:
        raise ValueError("autonomy package lacks artifact hashes")
    for filename, expected in sorted(hashes.items()):
        path = root / str(filename)
        if path.name != str(filename) or not path.is_file():
            raise ValueError(f"autonomy artifact is missing or unsafe: {filename}")
        if not isinstance(expected, str) or _file_sha256(path) != expected:
            raise ValueError(f"autonomy artifact hash mismatch: {filename}")


def _load_verified_autonomy(
    root: Path, validated: dict[str, Any]
) -> tuple[tuple[AutonomousDecision, ...], tuple[str, ...]]:
    root = root.expanduser().resolve()
    summary = json.loads(
        (root / "autonomous_summary.json").read_text(encoding="utf-8")
    )
    if summary.get("method_version") != AUTONOMOUS_VALIDATION_METHOD_VERSION:
        raise ValueError("autonomy package method version is not importable")
    if summary.get("reporting_schema_version") != ACCOUNTING_SCHEMA_VERSION:
        raise ValueError("autonomy package accounting schema is not importable")
    if summary.get("external_evidence_provider") != "NOOP":
        raise ValueError("manifest replay cannot reproduce an external evidence provider")
    _verify_artifacts(root, summary)

    with (root / "candidate_evidence.csv").open(
        "r", encoding="utf-8-sig", newline=""
    ) as handle:
        stored = [_decision_payload_from_csv(row) for row in csv.DictReader(handle)]
    stored_ids = [str(row["candidate_id"]) for row in stored]
    if stored_ids != sorted(stored_ids) or len(stored_ids) != len(set(stored_ids)):
        raise ValueError("autonomy evidence is not in unique deterministic order")
    if type(summary.get("candidate_groups")) is not int or len(stored) != summary["candidate_groups"]:
        raise ValueError("autonomy evidence count differs from its summary")

    reproducible = validated["reproducible"]
    decisions = evaluate_candidates(
        validated["candidates"],
        reproducible["source_context"],
        reference_date=_reference_date_from_provenance(validated["provenance"]),
    )
    recomputed = [row.to_row() for row in decisions]
    # CSV JSON arrays become lists while immutable in-memory evidence uses tuples.
    # Compare their canonical JSON content without relaxing values or array order.
    if _canonical_bytes(stored) != _canonical_bytes(recomputed):
        raise ValueError("autonomy evidence differs from deterministic replay")
    if _decision_digest(decisions) != str(summary.get("decision_digest") or ""):
        raise ValueError("autonomy decision digest differs from deterministic replay")
    actions = Counter(row.action.value for row in decisions)
    action_counts = {action.value: actions[action.value] for action in AutonomousAction}
    invariant = validate_decision_accounting(
        [candidate.candidate_id for candidate in validated["candidates"]], recomputed,
    )
    source_count = len(reproducible["source_context"])
    expected_sources = {
        "total": source_count,
        "usable": source_count - len(validated["rejected"]),
        "rejected_before_canonical_grouping": len(validated["rejected"]),
    }
    if (
        not _accounting_counts_match(summary.get("source_record_states"), expected_sources)
        or type(summary.get("source_records")) is not int
        or summary["source_records"] != source_count
        or type(summary.get("source_rejected")) is not int
        or summary["source_rejected"] != len(validated["rejected"])
    ):
        raise ValueError("autonomy source record states differ from deterministic replay")
    if (
        not _accounting_counts_match(summary.get("actions"), action_counts)
        or not _accounting_counts_match(summary.get("candidate_decisions"), action_counts)
        or not isinstance(summary.get("accounting_invariants"), dict)
        or _canonical_bytes(summary["accounting_invariants"]) != _canonical_bytes(invariant)
    ):
        raise ValueError("autonomy action counts differ from deterministic replay")

    order = canary_selection_order(decisions)
    with (root / "canary_eligible.csv").open(
        "r", encoding="utf-8-sig", newline=""
    ) as handle:
        ranked_rows = list(csv.DictReader(handle))
    rank_by_id: dict[str, int] = {}
    selected_ids: set[str] = set()
    for row in ranked_rows:
        candidate_id = str(row.get("candidate_id") or "")
        try:
            rank = int(str(row.get("selection_rank") or ""))
        except ValueError as error:
            raise ValueError("autonomy candidate has an invalid selection rank") from error
        if not candidate_id or candidate_id in rank_by_id or rank <= 0:
            raise ValueError("autonomy candidate ranks are missing or duplicated")
        rank_by_id[candidate_id] = rank
        if _bool_csv(row.get("stage_1_selected"), "stage_1_selected"):
            selected_ids.add(candidate_id)
    expected_ranks = {candidate_id: rank for rank, candidate_id in enumerate(order, 1)}
    if rank_by_id != expected_ranks:
        raise ValueError("autonomy selection ranks differ from deterministic replay")
    expected_stage_one = set(order[:min(100, len(order))])
    if selected_ids != expected_stage_one:
        raise ValueError("autonomy Stage 1 selection is not exactly min(100, eligible)")
    if type(summary.get("canary_eligible")) is not int or summary["canary_eligible"] != len(order):
        raise ValueError("autonomy eligible count differs from deterministic replay")
    if type(summary.get("stage_1_planned")) is not int or summary["stage_1_planned"] != len(expected_stage_one):
        raise ValueError("autonomy Stage 1 count differs from deterministic replay")
    return decisions, order


def _manifest_source(
    row: dict[str, Any], *, usable: bool, rejection_reason: str | None = None,
) -> dict[str, Any]:
    source_hash = str(row.get("source_hash") or "")
    if len(source_hash) != 64 or any(character not in "0123456789abcdef" for character in source_hash):
        raise ValueError("source row lacks a lowercase SHA-256 digest")
    observed_at = _canonical_timestamp(row.get("observed_at"))
    retrieved_at = _canonical_timestamp(row.get("retrieved_at"))
    provider = str(row.get("provider") or "").upper()
    result = {
        "source_record_id": _source_uuid(row),
        "provider": provider,
        "external_id": str(row.get("external_id") or ""),
        "source_release": str(row.get("source_release") or ""),
        "snapshot_id": row.get("snapshot_id"),
        "method_version": _source_method_version(row),
        "normalized_name": row.get("name"),
        "latitude": row.get("latitude"),
        "longitude": row.get("longitude"),
        "address": row.get("address"),
        "locality": row.get("locality"),
        "region": row.get("region"),
        "country_code": row.get("country_code"),
        "provider_categories": list(row.get("provider_categories") or row.get("categories") or ()),
        "proposed_place_category": row.get("proposed_place_category"),
        "phone": row.get("phone"),
        "website": row.get("website"),
        "operating_status": row.get("operating_status"),
        "source_hash": source_hash,
        "license_identifier": str(row.get("license_identifier") or ""),
        "provenance": dict(row.get("provenance") or row.get("source_metadata") or {}),
        "observed_at": observed_at,
        "retrieved_at": retrieved_at,
        "usable": usable,
    }
    if not usable:
        if not rejection_reason:
            raise ValueError("rejected source must retain its raw rejection reason")
        result["provenance"]["source_record_state"] = "SOURCE_REJECTED"
        result["provenance"]["source_rejection_reason"] = rejection_reason
    if not result["external_id"] or not result["license_identifier"]:
        raise ValueError("source row lacks import provenance")
    return result


def _candidate_payload_hash(candidate: dict[str, Any]) -> str:
    payload = dict(candidate)
    payload.pop("candidate_hash", None)
    payload.pop("selected_for_stage", None)
    return _sha256_json(payload)


def _canonical_payload(
    candidate: CanonicalCandidate, proposals: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    def accepted(field: str, fallback: Any = None) -> Any:
        proposal = proposals.get(field, {})
        return proposal.get("canonical_value") if proposal.get("accepted") else fallback

    coordinates = accepted("coordinates", {})
    return {
        "name": accepted("name"),
        "category": accepted("category"),
        "latitude": coordinates.get("latitude"),
        "longitude": coordinates.get("longitude"),
        "city": candidate.locality,
        "region": candidate.region,
        "country": candidate.country,
        "address": accepted("address", ""),
        "phone": accepted("phone"),
        "website": accepted("website"),
    }


def _stage_rank_bounds(stage: str, eligible_count: int) -> tuple[int, int]:
    if stage == "STAGE_1":
        bounds = (1, min(100, eligible_count))
    elif stage == "STAGE_2":
        bounds = (101, min(500, eligible_count))
    elif stage == "STAGE_3":
        bounds = (501, eligible_count)
    else:
        raise ValueError("canary stage must be STAGE_1, STAGE_2 or STAGE_3")
    if bounds[1] < bounds[0]:
        raise ValueError(f"{stage} has no remaining eligible candidates")
    return bounds


def _assemble_manifest(
    validated: dict[str, Any],
    decisions: Sequence[AutonomousDecision],
    selection_order: Sequence[str],
    *,
    stage: str,
    run_id: str,
    pilot_run_key: str,
    authorization_reference: str,
    reauthorizes_run_id: str | None = None,
) -> dict[str, Any]:
    canonical_run_id = _canonical_uuid(run_id, "run_id")
    canonical_reauthorizes_run_id = (
        _canonical_uuid(reauthorizes_run_id, "reauthorizes_run_id")
        if reauthorizes_run_id else None
    )
    if canonical_reauthorizes_run_id == canonical_run_id:
        raise ValueError("reauthorizes_run_id must differ from run_id")
    if stage not in _VALID_STAGES:
        raise ValueError("canary stage must be STAGE_1, STAGE_2 or STAGE_3")
    authorization_reference = authorization_reference.strip()
    if not authorization_reference:
        raise ValueError("authorization reference is required")
    if len(authorization_reference) > 200:
        raise ValueError("authorization reference must not exceed 200 characters")
    if not _PILOT_RUN_KEY.fullmatch(pilot_run_key):
        raise ValueError("pilot run key is invalid")

    context = validated["reproducible"]["source_context"]
    validate_decision_accounting(
        [candidate.candidate_id for candidate in validated["candidates"]],
        [decision.to_row() for decision in decisions],
    )
    rejected_by_key = {
        (str(row.get("provider") or ""), str(row.get("external_id") or "")): row
        for row in validated["rejected"]
    }
    if len(rejected_by_key) != len(validated["rejected"]) or not set(rejected_by_key) <= set(context):
        raise ValueError("rejected source identities are duplicated or lack provenance")
    ordered_source_rows = [context[key] for key in sorted(context)]
    source_records = [
        _manifest_source(
            row,
            usable=(str(row.get("provider")), str(row.get("external_id"))) not in rejected_by_key,
            rejection_reason=str(rejected_by_key.get(
                (str(row.get("provider")), str(row.get("external_id"))), {}
            ).get("reasons") or "SOURCE_QUALITY_REJECTED"),
        )
        for row in ordered_source_rows
    ]
    source_id_by_key = {
        (str(row.get("provider")), str(row.get("external_id"))): record["source_record_id"]
        for row, record in zip(ordered_source_rows, source_records, strict=True)
    }
    decided_at = _deterministic_decided_at(ordered_source_rows)
    rank_by_id = {
        candidate_id: rank for rank, candidate_id in enumerate(selection_order, 1)
    }
    first_rank, last_rank = _stage_rank_bounds(stage, len(selection_order))
    canonical_by_id = {
        candidate.candidate_id: candidate for candidate in validated["candidates"]
    }
    manifest_candidates: list[dict[str, Any]] = []
    for decision in sorted(decisions, key=lambda row: row.candidate_id):
        candidate = canonical_by_id.get(decision.candidate_id)
        if candidate is None:
            raise ValueError("autonomy decision lacks canonical candidate lineage")
        source_keys = [
            key for key in (
                ("overture", decision.overture_id or ""),
                ("fsq", decision.fsq_id or ""),
            ) if key[1]
        ]
        proposals = {row.field: row.to_dict() for row in decision.field_proposals}
        required_valid = all(
            proposals.get(field, {}).get("accepted")
            for field in ("name", "category", "coordinates")
        )
        row: dict[str, Any] = {
            "candidate_id": decision.candidate_id,
            "decision": decision.action.value,
            "decision_reason": decision.reason_code,
            "existence_assessment": decision.existence_confidence.value,
            "evidence": {"signals": [item.to_dict() for item in decision.evidence]},
            "hard_blockers": [item.value for item in decision.hard_blockers],
            "field_proposals": proposals,
            "source_record_ids": [source_id_by_key[key] for key in source_keys],
            "canary_eligible": decision.canary_eligible,
            "selected_for_stage": False,
            "canonical_fields_valid": required_valid,
            "decided_at": decided_at,
            "overrides": [],
        }
        if decision.canary_eligible:
            rank = rank_by_id.get(decision.candidate_id)
            if rank is None:
                raise ValueError("eligible decision lacks deterministic selection rank")
            row["selection_rank"] = rank
            row["selected_for_stage"] = first_rank <= rank <= last_rank
            row["canonical_place_id"] = _place_uuid(decision.candidate_id)
            row["canonical"] = _canonical_payload(candidate, proposals)
        row["candidate_hash"] = _candidate_payload_hash(row)
        manifest_candidates.append(row)

    manifest_candidates.sort(key=lambda row: str(row["candidate_id"]))

    providers = validated["provenance"]["providers"]
    manifest: dict[str, Any] = {
        "run_id": canonical_run_id,
        "pilot_run_key": pilot_run_key,
        "canary_stage": stage,
        "authorization_reference": authorization_reference,
        "status": AUTHORIZED_STATUS,
        "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
        "reporting_schema_version": ACCOUNTING_SCHEMA_VERSION,
        "source_record_states": {
            "total": len(source_records),
            "usable": len(source_records) - len(rejected_by_key),
            "rejected_before_canonical_grouping": len(rejected_by_key),
        },
        "candidate_group_count": len(validated["candidates"]),
        "candidate_decisions": {
            action.value: sum(row.action == action for row in decisions)
            for action in AutonomousAction
        },
        "scope": {
            "name": "didim_core",
            "center_latitude": DIDIM_CENTER[0],
            "center_longitude": DIDIM_CENTER[1],
            "radius_meters": int(DIDIM_RADIUS_METERS),
        },
        "providers": {
            "overture": {"release": providers["overture"]["resolved_release"]},
            "fsq": {
                "release": providers["fsq"]["resolved_release"],
                "snapshot_id": providers["fsq"]["snapshot_id"],
            },
        },
        "source_records": source_records,
        "candidates": manifest_candidates,
    }
    if canonical_reauthorizes_run_id:
        manifest["reauthorizes_run_id"] = canonical_reauthorizes_run_id
    return manifest


def validate_canary_manifest_contract(envelope: dict[str, Any]) -> dict[str, int]:
    """Mirror the backend's structural/cardinality gate for round-trip tests."""

    if not isinstance(envelope, dict) or not isinstance(envelope.get("manifest"), dict):
        raise ValueError("manifest envelope is invalid")
    manifest = envelope["manifest"]
    if envelope.get("manifest_hash") != _sha256_json(manifest):
        raise ValueError("manifest hash does not match canonical content")
    if manifest.get("status") != AUTHORIZED_STATUS:
        raise ValueError("manifest is not operationally authorized")
    if manifest.get("method_version") != AUTONOMOUS_VALIDATION_METHOD_VERSION:
        raise ValueError("manifest method version is invalid")
    if manifest.get("reporting_schema_version") != ACCOUNTING_SCHEMA_VERSION:
        raise ValueError("manifest accounting schema is invalid")
    if manifest.get("canary_stage") not in _VALID_STAGES:
        raise ValueError("manifest stage is invalid")
    run_id = str(manifest.get("run_id") or "")
    if _canonical_uuid(run_id, "run_id") != run_id:
        raise ValueError("manifest run_id is not a canonical UUID")
    if manifest.get("reauthorizes_run_id") is not None:
        reauthorizes_run_id = str(manifest["reauthorizes_run_id"])
        if _canonical_uuid(reauthorizes_run_id, "reauthorizes_run_id") != reauthorizes_run_id:
            raise ValueError("manifest reauthorizes_run_id is not a canonical UUID")
        if reauthorizes_run_id == run_id:
            raise ValueError("manifest reauthorizes_run_id must differ from run_id")
    if not _PILOT_RUN_KEY.fullmatch(str(manifest.get("pilot_run_key") or "")):
        raise ValueError("manifest pilot run key is invalid")
    authorization_reference = str(manifest.get("authorization_reference") or "")
    if not authorization_reference.strip():
        raise ValueError("manifest authorization reference is required")
    if len(authorization_reference) > 200:
        raise ValueError("manifest authorization reference exceeds 200 characters")
    if manifest.get("scope") != {
        "name": "didim_core", "center_latitude": DIDIM_CENTER[0],
        "center_longitude": DIDIM_CENTER[1], "radius_meters": int(DIDIM_RADIUS_METERS),
    }:
        raise ValueError("manifest scope is invalid")
    providers = manifest.get("providers")
    if providers != {
        "overture": {"release": EXPECTED_OVERTURE_RELEASE},
        "fsq": {"release": EXPECTED_FSQ_RELEASE, "snapshot_id": EXPECTED_FSQ_SNAPSHOT},
    }:
        raise ValueError("manifest provider versions are not backend-compatible")

    sources = manifest.get("source_records")
    candidates = manifest.get("candidates")
    if not isinstance(sources, list) or not isinstance(candidates, list):
        raise ValueError("manifest records must be arrays")
    source_ids: set[str] = set()
    source_by_id: dict[str, dict[str, Any]] = {}
    source_fresh: dict[str, bool] = {}
    source_identities: set[tuple[str, str]] = set()
    for source in sources:
        source_id = str(source.get("source_record_id") or "")
        uuid.UUID(source_id)
        if source_id in source_ids or not isinstance(source.get("usable"), bool):
            raise ValueError("manifest source identity or usability is invalid")
        source_ids.add(source_id)
        provider = str(source.get("provider") or "").upper()
        external_id = str(source.get("external_id") or "")
        if provider not in {"OVERTURE", "FSQ"} or not external_id:
            raise ValueError("manifest source provider identity is invalid")
        if (provider, external_id) in source_identities:
            raise ValueError("manifest provider external identity is duplicated")
        source_identities.add((provider, external_id))
        expected_release = (
            EXPECTED_OVERTURE_RELEASE if provider == "OVERTURE" else EXPECTED_FSQ_RELEASE
        )
        if source.get("source_release") != expected_release:
            raise ValueError("manifest source release is invalid")
        if source.get("method_version") not in SUPPORTED_SOURCE_METHOD_VERSIONS:
            raise ValueError("manifest source canonicalization method is invalid")
        if provider == "FSQ" and source.get("snapshot_id") != EXPECTED_FSQ_SNAPSHOT:
            raise ValueError("manifest FSQ source snapshot is invalid")
        source_hash = str(source.get("source_hash") or "")
        if not re.fullmatch(r"[0-9a-f]{64}", source_hash):
            raise ValueError("manifest source hash is invalid")
        if source_id != _source_uuid(source):
            raise ValueError("manifest source UUID is not deterministic")
        if not str(source.get("license_identifier") or "").strip():
            raise ValueError("manifest source license provenance is missing")
        if not isinstance(source.get("provenance"), dict):
            raise ValueError("manifest source provenance must be an object")
        observed_text = str(source.get("observed_at") or "")
        retrieved_text = str(source.get("retrieved_at") or "")
        observed = _parse_timestamp(observed_text)
        if _canonical_timestamp(observed_text) != observed_text:
            raise ValueError("manifest source observation timestamp is not canonical UTC")
        if _canonical_timestamp(retrieved_text) != retrieved_text:
            raise ValueError("manifest source retrieval timestamp is not canonical UTC")
        if (
            source.get("latitude") is not None
            or source.get("longitude") is not None
        ):
            _validate_scoped_point(
                source.get("latitude"), source.get("longitude"), "manifest source"
            )
        source_fresh[source_id] = (
            date(2026, 9, 23) >= observed.date()
            and (date(2026, 9, 23) - observed.date()).days <= 730
        )
        source_by_id[source_id] = source
        if not source["usable"] and (
            source["provenance"].get("source_record_state") != "SOURCE_REJECTED"
            or not str(source["provenance"].get("source_rejection_reason") or "").strip()
        ):
            raise ValueError("rejected source lacks source-state provenance")
    usable_source_ids = {
        source_id for source_id, source in source_by_id.items() if source["usable"]
    }
    if not _accounting_counts_match(manifest.get("source_record_states"), {
        "total": len(sources), "usable": len(usable_source_ids),
        "rejected_before_canonical_grouping": len(sources) - len(usable_source_ids),
    }):
        raise ValueError("manifest source record accounting is inconsistent")
    if type(manifest.get("candidate_group_count")) is not int or manifest["candidate_group_count"] != len(candidates):
        raise ValueError("manifest candidate group accounting is inconsistent")
    assigned: set[str] = set()
    ranks: set[int] = set()
    selected: set[int] = set()
    eligible = 0
    candidate_ids: set[str] = set()
    for candidate in candidates:
        candidate_id = str(candidate.get("candidate_id") or "")
        if not candidate_id or candidate_id in candidate_ids:
            raise ValueError("manifest candidate identity is missing or duplicated")
        candidate_ids.add(candidate_id)
        if candidate.get("decision") not in {
            "AUTO_LINK", "AUTO_CREATE", "AUTO_ENRICH", "AUTO_REJECT", "QUARANTINE",
        }:
            raise ValueError("manifest candidate decision is invalid")
        if not _DECISION_REASON.fullmatch(str(candidate.get("decision_reason") or "")):
            raise ValueError("manifest candidate decision reason is invalid")
        if candidate.get("existence_assessment") not in {"HIGH", "MEDIUM", "LOW", "UNKNOWN"}:
            raise ValueError("manifest existence assessment is invalid")
        _parse_timestamp(candidate.get("decided_at"))
        if not isinstance(candidate.get("canary_eligible"), bool) or not isinstance(
            candidate.get("selected_for_stage"), bool
        ):
            raise ValueError("manifest eligibility and selection must be booleans")
        if candidate.get("candidate_hash") != _candidate_payload_hash(candidate):
            raise ValueError("candidate hash does not match canonical decision content")
        if not isinstance(candidate.get("evidence"), dict):
            raise ValueError("candidate evidence must be an object")
        if not isinstance(candidate.get("field_proposals"), dict):
            raise ValueError("candidate field proposals must be an object")
        if not isinstance(candidate.get("hard_blockers"), list):
            raise ValueError("candidate hard blockers must be an array")
        candidate_sources = candidate.get("source_record_ids")
        if not isinstance(candidate_sources, list) or not candidate_sources:
            raise ValueError("candidate must have source lineage")
        for source_id in candidate_sources:
            if source_id not in source_ids or source_id in assigned:
                raise ValueError("candidate source lineage is missing or duplicated")
            if source_id not in usable_source_ids:
                raise ValueError("rejected source cannot be assigned a candidate decision")
            assigned.add(source_id)
        if candidate.get("canary_eligible"):
            eligible += 1
            rank = candidate.get("selection_rank")
            if (
                candidate.get("decision") != AutonomousAction.AUTO_CREATE.value
                or candidate.get("existence_assessment") != "HIGH"
                or candidate.get("hard_blockers")
                or candidate.get("canonical_fields_valid") is not True
                or not isinstance(rank, int) or isinstance(rank, bool) or rank <= 0
                or rank in ranks
            ):
                raise ValueError("eligible candidate violates the canary contract")
            place_id = str(candidate.get("canonical_place_id") or "")
            uuid.UUID(place_id)
            if place_id != _place_uuid(candidate_id):
                raise ValueError("eligible candidate Place UUID is not deterministic")
            canonical = candidate.get("canonical")
            if not isinstance(canonical, dict):
                raise ValueError("eligible candidate lacks canonical fields")
            if not all(str(canonical.get(field) or "").strip() for field in (
                "name", "category", "city", "region", "country",
            )):
                raise ValueError("eligible candidate canonical fields are incomplete")
            if canonical.get("category") not in PLACE_CATEGORIES:
                raise ValueError("eligible candidate category is invalid")
            _validate_scoped_point(
                canonical.get("latitude"),
                canonical.get("longitude"),
                "eligible candidate",
            )
            candidate_sources_data = [source_by_id[source_id] for source_id in candidate_sources]
            source_providers = {source["provider"] for source in candidate_sources_data}
            source_hashes = {source["source_hash"] for source in candidate_sources_data}
            def unresolved_flags(source: dict[str, Any]) -> tuple[Any, ...]:
                raw = source.get("provenance", {}).get("unresolved_flags", ())
                return (raw,) if isinstance(raw, str) else tuple(raw)

            negative_signal = any(
                _is_negative_operating_signal(source.get("operating_status"))
                or any(
                    _is_negative_operating_signal(flag)
                    for flag in unresolved_flags(source)
                )
                for source in candidate_sources_data
            )
            if (
                source_providers != {"OVERTURE", "FSQ"}
                or len(source_hashes) < 2
                or _has_dependent_source_lineage(candidate_sources_data)
                or not all(source["usable"] for source in candidate_sources_data)
                or not all(
                    source_fresh[str(source["source_record_id"])]
                    for source in candidate_sources_data
                )
                or negative_signal
            ):
                raise ValueError("eligible candidate lacks safe independent provider evidence")
            ranks.add(rank)
            if candidate.get("selected_for_stage") is True:
                selected.add(rank)
        elif candidate.get("selection_rank") is not None:
            raise ValueError("noneligible candidate has a selection rank")
        elif candidate.get("selected_for_stage") is not False:
            raise ValueError("noneligible candidate is selected")
    expected_actions = Counter(candidate["decision"] for candidate in candidates)
    if not _accounting_counts_match(manifest.get("candidate_decisions"), {
        action.value: expected_actions[action.value] for action in AutonomousAction
    }):
        raise ValueError("manifest candidate decision accounting is inconsistent")
    if assigned != usable_source_ids or ranks != set(range(1, eligible + 1)):
        raise ValueError("manifest source coverage or selection ranks are incomplete")
    first, last = _stage_rank_bounds(str(manifest["canary_stage"]), eligible)
    if selected != set(range(first, last + 1)):
        raise ValueError("manifest does not select the exact stage rank range")
    return {
        "source_records": len(sources),
        "candidates": len(candidates),
        "eligible": eligible,
        "selected": len(selected),
    }


def build_canary_manifest(
    source_package: Path,
    autonomy_package: Path,
    *,
    stage: str,
    run_id: str,
    pilot_run_key: str,
    authorization_reference: str,
    authorization_confirmation: str,
    reauthorizes_run_id: str | None = None,
) -> dict[str, Any]:
    """Build, but do not import, a manifest after the explicit authorization gate."""

    if authorization_confirmation != AUTHORIZATION_CONFIRMATION:
        raise PermissionError(
            f"manifest generation requires exact confirmation: {AUTHORIZATION_CONFIRMATION}"
        )
    validated = load_validated_replay_package(source_package)
    decisions, order = _load_verified_autonomy(autonomy_package, validated)
    manifest = _assemble_manifest(
        validated,
        decisions,
        order,
        stage=stage,
        run_id=run_id,
        pilot_run_key=pilot_run_key,
        authorization_reference=authorization_reference,
        reauthorizes_run_id=reauthorizes_run_id,
    )
    envelope = {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}
    validate_canary_manifest_contract(envelope)
    if len(_canonical_bytes(envelope)) > MAX_MANIFEST_BYTES:
        raise ValueError("canary manifest exceeds the backend 128 MiB limit")
    return envelope


def write_canary_manifest(
    source_package: Path,
    autonomy_package: Path,
    output: Path,
    **kwargs: Any,
) -> dict[str, Any]:
    """Exclusively create one authorized envelope; never overwrite an existing file."""

    target = output.expanduser().resolve()
    if target == REPOSITORY_ROOT or REPOSITORY_ROOT in target.parents:
        raise ValueError("authorized manifests must be written outside the Git repository")
    if target.suffix != ".json":
        raise ValueError("authorized manifest output must use an exact lowercase .json filename")
    if target.exists():
        raise FileExistsError(f"authorized manifest output already exists: {target}")
    envelope = build_canary_manifest(source_package, autonomy_package, **kwargs)
    encoded = _canonical_bytes(envelope)
    target.parent.mkdir(parents=True, exist_ok=True)
    created = False
    try:
        with target.open("xb") as handle:
            created = True
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise
    counts = validate_canary_manifest_contract(envelope)
    return {
        "output": str(target),
        "manifest_hash": envelope["manifest_hash"],
        "size_bytes": len(encoded),
        **counts,
    }
