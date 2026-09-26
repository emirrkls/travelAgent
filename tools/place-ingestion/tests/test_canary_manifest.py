from __future__ import annotations

import unittest
import uuid
import copy
import tempfile
import json
from dataclasses import replace
from datetime import date
from pathlib import Path
from unittest.mock import patch

from phokarta_place_ingestion.autonomous_validation import (
    AutonomousAction,
    AUTONOMOUS_VALIDATION_METHOD_VERSION,
    _decision_digest,
    decide_candidate,
    validate_decision_accounting,
)
from phokarta_place_ingestion.canary_manifest import (
    AUTHORIZATION_CONFIRMATION,
    EXPECTED_FSQ_RELEASE,
    EXPECTED_FSQ_SNAPSHOT,
    EXPECTED_OVERTURE_RELEASE,
    MAX_MANIFEST_BYTES,
    _assemble_manifest,
    _canonical_bytes,
    _finite_json_number,
    _load_verified_autonomy,
    _sha256_json,
    _source_uuid,
    build_canary_manifest,
    validate_canary_manifest_contract,
    write_canary_manifest,
)
from phokarta_place_ingestion.canonicalization import CanonicalCandidate
from phokarta_place_ingestion.canonicalization import CANONICALIZATION_METHOD_VERSION
from phokarta_place_ingestion.reporting import write_csv


def _fixture(count: int = 1):
    context = {}
    candidates = []
    decisions = []
    for index in range(count):
        candidate_id = f"didim-{index:024d}"
        overture_id = f"o-{index}"
        fsq_id = f"f-{index}"
        latitude = 37.3751 + index * 0.000001
        candidate = CanonicalCandidate(
            candidate_id=candidate_id,
            classification="CREATE_NEW",
            proposed_name=f"İnci Kafe {index}",
            proposed_category="CAFE",
            latitude=latitude,
            longitude=27.2678,
            address="Atatürk Bulvarı 1",
            locality="Didim",
            region="Aydın",
            country="TR",
            phone="0256 811 22 33",
            website=None,
            overture_id=overture_id,
            fsq_id=fsq_id,
            overture_name=f"İnci Kafe {index}",
            fsq_name=f"İnci Kafe {index}",
            existing_place_id=None,
            existing_place_name=None,
            match_score=None,
            matching_reasons=("cross_provider_high_confidence",),
            risk_flags=(),
            cross_provider_classification="HIGH_CONFIDENCE_MATCH",
            source_hashes=(f"{index + 1:064x}", f"{index + 1001:064x}"),
            provider_categories=(
                "overture:cafe", "fsq:4bf58dd8d48988d16d941735",
            ),
        )
        source_rows = []
        for provider, external_id, source_hash, categories in (
            ("overture", overture_id, f"{index + 1:064x}", ["cafe"]),
            (
                "fsq", fsq_id, f"{index + 1001:064x}",
                ["4bf58dd8d48988d16d941735"],
            ),
        ):
            row = {
                "provider": provider,
                "external_id": external_id,
                "source_release": (
                    EXPECTED_OVERTURE_RELEASE if provider == "overture"
                    else EXPECTED_FSQ_RELEASE
                ),
                "snapshot_id": EXPECTED_FSQ_SNAPSHOT if provider == "fsq" else None,
                "method_version": CANONICALIZATION_METHOD_VERSION,
                "name": candidate.proposed_name,
                "latitude": latitude,
                "longitude": candidate.longitude,
                "address": candidate.address,
                "locality": "Didim",
                "region": "Aydın",
                "country_code": "TR",
                "provider_categories": categories,
                "proposed_place_category": "CAFE",
                "phone": candidate.phone,
                "website": None,
                "operating_status": "OPEN",
                "source_hash": source_hash,
                "license_identifier": "provider-open-license",
                "provenance": {"area": "didim_core"},
                "observed_at": "2026-09-20T00:00:00Z",
                "retrieved_at": "2026-09-23T12:00:00Z",
            }
            context[(provider, external_id)] = row
            source_rows.append(row)
        candidates.append(candidate)
        decisions.append(decide_candidate(candidate, source_rows))
    validated = {
        "provenance": {
            "providers": {
                "overture": {
                    "resolved_release": EXPECTED_OVERTURE_RELEASE,
                    "snapshot_id": None,
                },
                "fsq": {
                    "resolved_release": EXPECTED_FSQ_RELEASE,
                    "snapshot_id": EXPECTED_FSQ_SNAPSHOT,
                },
            },
        },
        "reproducible": {"source_context": context},
        "candidates": tuple(candidates),
        "rejected": (),
    }
    order = tuple(row.candidate_id for row in decisions)
    return validated, tuple(decisions), order


def _envelope(count: int = 1, stage: str = "STAGE_1"):
    validated, decisions, order = _fixture(count)
    manifest = _assemble_manifest(
        validated,
        decisions,
        order,
        stage=stage,
        run_id=str(uuid.uuid4()),
        pilot_run_key="didim-canary-test",
        authorization_reference="test-authorization-reference",
    )
    return {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}


class CanaryManifestContractTest(unittest.TestCase):
    def test_backend_round_trip_shape_hash_and_source_coverage(self):
        envelope = _envelope(3)
        counts = validate_canary_manifest_contract(envelope)
        self.assertEqual(counts, {
            "source_records": 6, "candidates": 3, "eligible": 3, "selected": 3,
        })
        self.assertLess(len(_canonical_bytes(envelope)), MAX_MANIFEST_BYTES)
        self.assertEqual(
            {row["selection_rank"] for row in envelope["manifest"]["candidates"]},
            {1, 2, 3},
        )
        self.assertEqual(
            {row["method_version"] for row in envelope["manifest"]["source_records"]},
            {CANONICALIZATION_METHOD_VERSION},
        )

        tampered = copy.deepcopy(envelope)
        tampered["manifest"]["source_records"][0]["method_version"] = (
            "didim-canonicalization-v1"
        )
        tampered["manifest_hash"] = _sha256_json(tampered["manifest"])
        with self.assertRaisesRegex(ValueError, "canonicalization method"):
            validate_canary_manifest_contract(tampered)

    def test_manifest_preserves_frozen_v2_source_method_and_uuid(self):
        validated, decisions, order = _fixture(1)
        for row in validated["reproducible"]["source_context"].values():
            row["method_version"] = "didim-canonicalization-v2"
        manifest = _assemble_manifest(
            validated, decisions, order, stage="STAGE_1",
            run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-frozen-v2",
            authorization_reference="test-authorization-reference",
        )
        envelope = {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}

        validate_canary_manifest_contract(envelope)
        self.assertEqual(
            {row["method_version"] for row in manifest["source_records"]},
            {"didim-canonicalization-v2"},
        )
        for source in manifest["source_records"]:
            self.assertEqual(source["source_record_id"], _source_uuid(source))
            rewritten = dict(source, method_version=CANONICALIZATION_METHOD_VERSION)
            self.assertNotEqual(source["source_record_id"], _source_uuid(rewritten))

    def test_coordinates_must_be_finite_json_numbers_not_strings_or_booleans(self):
        envelope = _envelope(1)
        for value in ("37.3751", True):
            with self.subTest(location="source", value=value):
                tampered = copy.deepcopy(envelope)
                tampered["manifest"]["source_records"][0]["latitude"] = value
                tampered["manifest_hash"] = _sha256_json(tampered["manifest"])
                with self.assertRaisesRegex(ValueError, "finite JSON number"):
                    validate_canary_manifest_contract(tampered)

            with self.subTest(location="canonical", value=value):
                tampered = copy.deepcopy(envelope)
                candidate = tampered["manifest"]["candidates"][0]
                candidate["canonical"]["latitude"] = value
                candidate["candidate_hash"] = _sha256_json({
                    key: item for key, item in candidate.items()
                    if key not in {"candidate_hash", "selected_for_stage"}
                })
                tampered["manifest_hash"] = _sha256_json(tampered["manifest"])
                with self.assertRaisesRegex(ValueError, "finite JSON number"):
                    validate_canary_manifest_contract(tampered)

        for value in (float("nan"), float("inf"), float("-inf"), 10 ** 1000):
            with self.subTest(nonfinite=value):
                with self.assertRaisesRegex(ValueError, "finite JSON number"):
                    _finite_json_number(value, "test coordinate")

    def test_manifest_independently_rejects_shared_foursquare_lineage(self):
        validated, decisions, order = _fixture(1)
        validated["reproducible"]["source_context"][("overture", "o-0")][
            "provenance"
        ] = {"sources": [{
            "provider": "foursquare", "dataset": "Foursquare", "record_id": "f-0",
        }]}
        manifest = _assemble_manifest(
            validated, decisions, order, stage="STAGE_1",
            run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
            authorization_reference="test-authorization-reference",
        )
        envelope = {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}
        with self.assertRaisesRegex(ValueError, "safe independent provider evidence"):
            validate_canary_manifest_contract(envelope)

    def test_stage_one_is_exactly_first_hundred_and_stage_two_is_the_remainder(self):
        first = _envelope(101, "STAGE_1")
        second = _envelope(101, "STAGE_2")
        self.assertEqual(validate_canary_manifest_contract(first)["selected"], 100)
        self.assertEqual(validate_canary_manifest_contract(second)["selected"], 1)
        first_by_id = {
            row["candidate_id"]: row for row in first["manifest"]["candidates"]
        }
        second_by_id = {
            row["candidate_id"]: row for row in second["manifest"]["candidates"]
        }
        self.assertEqual(
            {key: row["candidate_hash"] for key, row in first_by_id.items()},
            {key: row["candidate_hash"] for key, row in second_by_id.items()},
        )
        self.assertEqual(
            [row["selection_rank"] for row in second_by_id.values() if row["selected_for_stage"]],
            [101],
        )
        plans = []
        for envelope in (first, second):
            plan = copy.deepcopy(envelope["manifest"])
            for field in (
                "run_id", "pilot_run_key", "canary_stage",
                "authorization_reference", "reauthorizes_run_id", "status",
            ):
                plan.pop(field, None)
            for row in plan["candidates"]:
                row.pop("selected_for_stage")
            plans.append(plan)
        self.assertEqual(plans[0], plans[1])

    def test_quarantine_can_never_be_selected_or_ranked(self):
        validated, decisions, order = _fixture(2)
        quarantined = replace(
            decisions[1],
            action=AutonomousAction.QUARANTINE,
            canary_eligible=False,
            reason_code="TEST_BLOCKER",
        )
        decisions = (decisions[0], quarantined)
        order = (decisions[0].candidate_id,)
        manifest = _assemble_manifest(
            validated, decisions, order, stage="STAGE_1",
            run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
            authorization_reference="test-authorization-reference",
        )
        envelope = {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}
        validate_canary_manifest_contract(envelope)
        row = next(item for item in manifest["candidates"] if item["decision"] == "QUARANTINE")
        self.assertFalse(row["selected_for_stage"])
        self.assertNotIn("selection_rank", row)

    def test_invalid_optional_phone_is_not_promoted_to_canonical(self):
        validated, decisions, order = _fixture(1)
        candidate = replace(validated["candidates"][0], phone="0256 000 00 00")
        source_rows = [
            validated["reproducible"]["source_context"][key]
            for key in (("overture", "o-0"), ("fsq", "f-0"))
        ]
        for source in source_rows:
            source["phone"] = "0256 000 00 00"
        decision = decide_candidate(candidate, source_rows)
        validated["candidates"] = (candidate,)
        manifest = _assemble_manifest(
            validated, (decision,), (candidate.candidate_id,), stage="STAGE_1",
            run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
            authorization_reference="test-authorization-reference",
        )
        row = manifest["candidates"][0]
        self.assertIsNone(row["canonical"]["phone"])
        self.assertFalse(row["field_proposals"]["phone"]["accepted"])

    def test_source_rejection_is_not_a_candidate_decision_and_raw_reason_is_preserved(self):
        validated, decisions, order = _fixture(1)
        raw_reason = "explicitly_closed; unresolved_doesnt_exist"
        rejected = {
            "provider": "fsq",
            "external_id": "rejected-1",
            "source_release": EXPECTED_FSQ_RELEASE,
            "snapshot_id": EXPECTED_FSQ_SNAPSHOT,
            "method_version": CANONICALIZATION_METHOD_VERSION,
            "name": "Kapalı Yer",
            "latitude": 37.3752,
            "longitude": 27.2679,
            "address": None,
            "locality": "Didim",
            "region": "Aydın",
            "country_code": "TR",
            "provider_categories": ["4bf58dd8d48988d16d941735"],
            "proposed_place_category": "CAFE",
            "phone": None,
            "website": None,
            "operating_status": "CLOSED",
            "source_hash": f"{9001:064x}",
            "license_identifier": "provider-open-license",
            "provenance": {"area": "didim_core", "unresolved_flags": ["doesnt_exist"]},
            "observed_at": "2026-09-20T00:00:00Z",
            "retrieved_at": "2026-09-23T12:00:00Z",
            "reasons": raw_reason,
        }
        validated["reproducible"]["source_context"][("fsq", "rejected-1")] = rejected
        validated["rejected"] = (rejected,)
        manifest = _assemble_manifest(
            validated, decisions, order, stage="STAGE_1",
            run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
            authorization_reference="test-authorization-reference",
        )
        envelope = {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}
        counts = validate_canary_manifest_contract(envelope)
        self.assertEqual(counts["source_records"], 3)
        self.assertEqual(counts["candidates"], 1)
        self.assertEqual(manifest["candidate_group_count"], 1)
        self.assertEqual(manifest["candidate_decisions"]["AUTO_REJECT"], 0)
        self.assertEqual(manifest["source_record_states"], {
            "total": 3, "usable": 2, "rejected_before_canonical_grouping": 1,
        })
        row = next(item for item in manifest["source_records"] if not item["usable"])
        self.assertEqual(
            row["provenance"]["source_rejection_reason"],
            raw_reason,
        )
        self.assertEqual(row["provenance"]["source_record_state"], "SOURCE_REJECTED")
        self.assertFalse(any(
            row["source_record_id"] in candidate["source_record_ids"]
            for candidate in manifest["candidates"]
        ))

        row["provenance"].pop("source_rejection_reason")
        envelope["manifest_hash"] = _sha256_json(manifest)
        with self.assertRaisesRegex(ValueError, "source-state provenance"):
            validate_canary_manifest_contract(envelope)

    def test_assembly_requires_exactly_one_decision_per_canonical_candidate(self):
        validated, decisions, order = _fixture(2)
        for invalid in ((decisions[0],), (decisions[0], decisions[0])):
            with self.subTest(decisions=invalid):
                with self.assertRaisesRegex(ValueError, "exactly cover|exactly one"):
                    _assemble_manifest(
                        validated, invalid, order, stage="STAGE_1",
                        run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
                        authorization_reference="test-authorization-reference",
                    )

    def test_manifest_rejects_legacy_or_mixed_population_accounting(self):
        for field in ("reporting_schema_version", "candidate_group_count", "source_record_states"):
            with self.subTest(missing=field):
                envelope = _envelope(1)
                envelope["manifest"].pop(field)
                envelope["manifest_hash"] = _sha256_json(envelope["manifest"])
                with self.assertRaisesRegex(ValueError, "accounting"):
                    validate_canary_manifest_contract(envelope)
        envelope = _envelope(1)
        envelope["manifest"]["candidate_decisions"]["AUTO_REJECT"] = 1294
        envelope["manifest_hash"] = _sha256_json(envelope["manifest"])
        with self.assertRaisesRegex(ValueError, "candidate decision accounting"):
            validate_canary_manifest_contract(envelope)

    def test_verified_package_rejects_source_counts_as_candidate_actions_and_typed_count_tampering(self):
        validated, decisions, order = _fixture(1)
        validated["rejected"] = ({"provider": "fsq", "external_id": "rejected"},)
        validated["reproducible"]["source_context"][("fsq", "rejected")] = {}
        counts = {action.value: int(action == AutonomousAction.AUTO_CREATE) for action in AutonomousAction}
        summary = {
            "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
            "reporting_schema_version": "didim-autonomy-accounting-v1",
            "external_evidence_provider": "NOOP", "candidate_groups": 1,
            "source_records": 3, "source_rejected": 1,
            "source_record_states": {
                "total": 3, "usable": 2, "rejected_before_canonical_grouping": 1,
            },
            "actions": counts, "candidate_decisions": counts,
            "accounting_invariants": validate_decision_accounting(
                [decisions[0].candidate_id], [decisions[0].to_row()],
            ),
            "decision_digest": _decision_digest(decisions),
            "canary_eligible": 1, "stage_1_planned": 1,
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_csv(root / "candidate_evidence.csv", [decisions[0].to_row()], [])
            write_csv(root / "canary_eligible.csv", [{
                "candidate_id": order[0], "selection_rank": 1, "stage_1_selected": True,
            }], [])
            with patch("phokarta_place_ingestion.canary_manifest._verify_artifacts"), patch(
                "phokarta_place_ingestion.canary_manifest.evaluate_candidates", return_value=decisions,
            ), patch(
                "phokarta_place_ingestion.canary_manifest._reference_date_from_provenance",
                return_value=date(2026, 9, 23),
            ):
                (root / "autonomous_summary.json").write_text(json.dumps(summary), encoding="utf-8")
                self.assertEqual(_load_verified_autonomy(root, validated), (decisions, order))
                mutated = copy.deepcopy(summary)
                mutated["actions"]["AUTO_REJECT"] = 1
                mutated["candidate_decisions"]["AUTO_REJECT"] = 1
                (root / "autonomous_summary.json").write_text(json.dumps(mutated), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "action counts"):
                    _load_verified_autonomy(root, validated)
                for field in ("candidate_groups", "source_records", "source_rejected", "canary_eligible", "stage_1_planned"):
                    for value in (float(summary[field]), str(summary[field]), True):
                        with self.subTest(field=field, value=value):
                            mutated = copy.deepcopy(summary)
                            mutated[field] = value
                            (root / "autonomous_summary.json").write_text(json.dumps(mutated), encoding="utf-8")
                            with self.assertRaises(ValueError):
                                _load_verified_autonomy(root, validated)
                mutated = copy.deepcopy(summary)
                mutated["accounting_invariants"]["decision_total"] = True
                (root / "autonomous_summary.json").write_text(json.dumps(mutated), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "action counts"):
                    _load_verified_autonomy(root, validated)

    def test_noncanonical_inputs_are_emitted_as_backend_compatible_values(self):
        validated, decisions, order = _fixture(1)
        canonical_run_id = str(uuid.uuid4())
        canonical_prior_id = str(uuid.uuid4())
        for source in validated["reproducible"]["source_context"].values():
            source["observed_at"] = "2026-09-20T03:00:00+03:00"
            source["retrieved_at"] = "2026-09-23"
        manifest = _assemble_manifest(
            validated, decisions, order, stage="STAGE_1",
            run_id=f"urn:uuid:{canonical_run_id}",
            reauthorizes_run_id=canonical_prior_id.replace("-", ""),
            pilot_run_key="didim-canary-test",
            authorization_reference=" test-authorization-reference ",
        )
        envelope = {"manifest": manifest, "manifest_hash": _sha256_json(manifest)}
        validate_canary_manifest_contract(envelope)
        self.assertEqual(manifest["run_id"], canonical_run_id)
        self.assertEqual(manifest["reauthorizes_run_id"], canonical_prior_id)
        self.assertEqual(manifest["authorization_reference"], "test-authorization-reference")
        for source in manifest["source_records"]:
            self.assertEqual(source["observed_at"], "2026-09-20T00:00:00Z")
            self.assertEqual(source["retrieved_at"], "2026-09-23T00:00:00Z")

    def test_run_cannot_reauthorize_itself_in_generator_or_validator(self):
        validated, decisions, order = _fixture(1)
        run_id = str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError, "must differ from run_id"):
            _assemble_manifest(
                validated,
                decisions,
                order,
                stage="STAGE_1",
                run_id=f"urn:uuid:{run_id}",
                reauthorizes_run_id=run_id.replace("-", ""),
                pilot_run_key="didim-canary-test",
                authorization_reference="test-authorization-reference",
            )

        envelope = _envelope(1)
        envelope["manifest"]["reauthorizes_run_id"] = envelope["manifest"]["run_id"]
        envelope["manifest_hash"] = _sha256_json(envelope["manifest"])
        with self.assertRaisesRegex(ValueError, "must differ from run_id"):
            validate_canary_manifest_contract(envelope)

    def test_authorization_reference_and_output_suffix_match_backend_bounds(self):
        validated, decisions, order = _fixture(1)
        with self.assertRaisesRegex(ValueError, "200 characters"):
            _assemble_manifest(
                validated, decisions, order, stage="STAGE_1",
                run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
                authorization_reference="a" * 201,
            )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "manifest.JSON"
            with patch(
                "phokarta_place_ingestion.canary_manifest.build_canary_manifest"
            ) as builder:
                with self.assertRaisesRegex(ValueError, "lowercase .json"):
                    write_canary_manifest(
                        Path("source"), Path("autonomy"), target,
                        stage="STAGE_1", run_id=str(uuid.uuid4()),
                        pilot_run_key="didim-canary-test",
                        authorization_reference="test-authorization-reference",
                        authorization_confirmation=AUTHORIZATION_CONFIRMATION,
                    )
                builder.assert_not_called()

    def test_content_or_selection_tampering_breaks_hash_or_exact_rank_gate(self):
        envelope = _envelope(101)
        envelope["manifest"]["candidates"][0]["decision_reason"] = "TAMPERED"
        with self.assertRaisesRegex(ValueError, "manifest hash"):
            validate_canary_manifest_contract(envelope)
        envelope["manifest_hash"] = _sha256_json(envelope["manifest"])
        with self.assertRaisesRegex(ValueError, "candidate hash"):
            validate_canary_manifest_contract(envelope)

    def test_builder_requires_exact_authorization_before_loading_or_writing(self):
        with patch(
            "phokarta_place_ingestion.canary_manifest.load_validated_replay_package"
        ) as loader:
            with self.assertRaisesRegex(PermissionError, AUTHORIZATION_CONFIRMATION):
                build_canary_manifest(
                    Path("source"), Path("autonomy"), stage="STAGE_1",
                    run_id=str(uuid.uuid4()), pilot_run_key="didim-canary-test",
                    authorization_reference="test-authorization-reference",
                    authorization_confirmation="not authorized",
                )
            loader.assert_not_called()


if __name__ == "__main__":
    unittest.main()
