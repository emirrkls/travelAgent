from __future__ import annotations

import hashlib
import unittest
import json
import tempfile
import csv
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import phokarta_place_ingestion.autonomous_validation as autonomous_validation

from phokarta_place_ingestion.autonomous_validation import (
    AUTONOMY_ARTIFACT_STATUS,
    AUTONOMY_REPORTING_SCHEMA_VERSION,
    AUTONOMOUS_VALIDATION_METHOD_VERSION,
    AutonomousAction,
    EvidenceDimension,
    EvidenceSignal,
    EvidenceStrength,
    ExistenceConfidence,
    HardBlocker,
    NoopExternalEvidenceProvider,
    SourceRecordState,
    canary_eligible_breakdown,
    catalog_anomaly_baseline,
    decide_source_rejection,
    decide_candidate,
    detect_entity_hierarchy,
    evaluate_candidates,
    load_validated_replay_package,
    quarantine_transition_rows,
    run_quarantine_re_evaluation,
    select_stage_one,
    source_record_accounting,
    validate_decision_accounting,
    verify_corrected_source_package,
    _validated_package_provenance,
)
from phokarta_place_ingestion.canonical_attributes import (
    normalize_turkish_phone,
    validate_canonical_address,
    validate_canonical_category,
    validate_canonical_coordinates,
    validate_canonical_name,
    validate_canonical_website,
)
from phokarta_place_ingestion.canonicalization import (
    CANONICALIZATION_METHOD_VERSION,
    CanonicalCandidate,
)
from phokarta_place_ingestion.didim_pilot import (
    _candidate_from_csv,
    _replay_integrity_hash,
)


def candidate(
    candidate_id: str = "didim-1",
    *,
    classification: str = "CREATE_NEW",
    cross: str = "HIGH_CONFIDENCE_MATCH",
    risks: tuple[str, ...] = (),
    name: str = "İnci Kafe",
    category: str | None = "CAFE",
    latitude: float = 37.3751,
    longitude: float = 27.2678,
    overture_id: str | None = "o-1",
    fsq_id: str | None = "f-1",
    website: str | None = None,
    existing_place_id: str | None = None,
) -> CanonicalCandidate:
    return CanonicalCandidate(
        candidate_id=candidate_id,
        classification=classification,
        proposed_name=name,
        proposed_category=category,
        latitude=latitude,
        longitude=longitude,
        address="Atatürk Bulvarı 1",
        locality="Didim",
        region="Aydın",
        country="TR",
        phone="(0256) 811 22 33",
        website=website,
        overture_id=overture_id,
        fsq_id=fsq_id,
        overture_name=name if overture_id else None,
        fsq_name=name if fsq_id else None,
        existing_place_id=existing_place_id,
        existing_place_name="Existing" if existing_place_id else None,
        match_score=14 if existing_place_id else None,
        matching_reasons=("cross_provider_high_confidence",),
        risk_flags=risks,
        cross_provider_classification=cross,
        source_hashes=tuple(value for value in ("hash-o" if overture_id else None, "hash-f" if fsq_id else None) if value),
        provider_categories=("overture:cafe", "fsq:Coffee Shop"),
    )


def sources(row: CanonicalCandidate) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    if row.overture_id:
        result.append({
            "provider": "overture", "external_id": row.overture_id,
            "name": row.proposed_name, "latitude": row.latitude,
            "longitude": row.longitude, "address": row.address,
            "phone": row.phone, "website": row.website,
            "proposed_place_category": row.proposed_category,
            "provider_categories": ["cafe"],
            "observed_at": "2026-09-20T00:00:00Z",
        })
    if row.fsq_id:
        result.append({
            "provider": "fsq", "external_id": row.fsq_id,
            "name": row.proposed_name, "latitude": row.latitude + 0.00001,
            "longitude": row.longitude + 0.00001, "address": row.address,
            "phone": row.phone, "website": row.website,
            "proposed_place_category": row.proposed_category,
            "provider_categories": ["4bf58dd8d48988d16d941735"],
            "observed_at": "2026-09-20T00:00:00Z",
        })
    return result


class CanonicalAttributeValidationTest(unittest.TestCase):
    def test_turkish_phone_variants_have_one_e164_value(self):
        expected = "+902568112233"
        for value in (
            "+90 256 811 22 33", "0090 (256) 811-22-33", "0 256 811 22 33",
            "2568112233",
        ):
            with self.subTest(value=value):
                decision = normalize_turkish_phone(value)
                self.assertTrue(decision.accepted)
                self.assertEqual(decision.canonical_value, expected)

    def test_phone_invalid_country_or_extension_fails_closed(self):
        self.assertFalse(normalize_turkish_phone("+44 20 1234 5678").accepted)
        self.assertFalse(normalize_turkish_phone("+49 30 123456").accepted)
        self.assertFalse(normalize_turkish_phone("+33 1 23 45 67 8").accepted)
        self.assertFalse(normalize_turkish_phone("0049 30 123456").accepted)
        self.assertFalse(normalize_turkish_phone("0256 811 22 33 ext 4").accepted)
        self.assertFalse(normalize_turkish_phone("call 2568112233").accepted)
        self.assertFalse(normalize_turkish_phone("4441234567").accepted)
        self.assertFalse(normalize_turkish_phone("0256 000 00 00").accepted)
        self.assertFalse(normalize_turkish_phone("0555 555 55 55").accepted)

    def test_required_attribute_validators_fail_closed(self):
        self.assertFalse(validate_canonical_name("@handle").accepted)
        self.assertFalse(validate_canonical_name("Cafe").accepted)
        self.assertFalse(validate_canonical_name("Didim Merkez").accepted)
        self.assertFalse(validate_canonical_address("https://example.com").accepted)
        self.assertFalse(validate_canonical_coordinates(91, 27).accepted)
        self.assertFalse(validate_canonical_coordinates(
            38.0, 27.0, center=(37.3751, 27.2678), max_distance_meters=6000
        ).accepted)
        self.assertFalse(validate_canonical_category(
            "SUPERMARKET", allowed_categories={"CAFE", "BAR"}
        ).accepted)

    def test_reserved_and_placeholder_websites_fail_closed(self):
        for value in (
            "https://example.com", "https://incikafe.example.com",
            "https://foo.invalid", "http://thing.test",
        ):
            with self.subTest(value=value):
                result = validate_canonical_website(value, identity_names=("Foo",))
                self.assertIsNone(result.canonical_value)
                self.assertEqual(result.reason, "placeholder_or_reserved_domain")

    def test_undelegated_and_private_suffixes_fail_closed(self):
        for value in (
            "https://incikafe.zz", "https://incikafe.internal", "http://Lolo.kunefe/",
            "https://incikafe.arpa", "https://incikafe.home.arpa",
        ):
            with self.subTest(value=value):
                result = validate_canonical_website(value, identity_names=("Lolo Kunefe",))
                self.assertIsNone(result.canonical_value)
                self.assertEqual(result.reason, "invalid_public_suffix")

    def test_hostname_identity_uses_bounded_brand_labels(self):
        rejected = validate_canonical_website(
            "https://operation.com", identity_names=("Opera Cafe",)
        )
        self.assertIsNone(rejected.canonical_value)
        self.assertEqual(rejected.reason, "identity_unverified")
        for value in ("https://opera.com", "https://opera-cafe.com", "https://operacafe.com"):
            with self.subTest(value=value):
                self.assertIsNotNone(validate_canonical_website(
                    value, identity_names=("Opera Cafe",)
                ).canonical_value)

    def test_promotional_contact_text_is_not_a_canonical_name(self):
        actual = (
            "Yılbaşı!Munzur'a Türkü Evi 15Cesit Meze Hindi Pilav Cigkofte "
            "Mevsim Salata Sigara Böreği Sosis Sınırsız Alkol 1 Kişi: 135Tl "
            "RZV:05304221480"
        )
        self.assertFalse(validate_canonical_name(actual).accepted)
        self.assertFalse(validate_canonical_name(
            "İnci Restoran rezervasyon 0530 422 14 80"
        ).accepted)
        self.assertFalse(validate_canonical_name("İnci Menü 2 kişi 950 TL").accepted)
        self.assertTrue(validate_canonical_name("Munzur Türkü Evi").accepted)


class AutonomousDecisionTest(unittest.TestCase):
    def test_explicit_source_quality_failure_is_source_rejected_without_candidate_action(self):
        decision = decide_source_rejection({
            "provider": "fsq", "external_id": "junk-1",
            "reasons": "invalid_coordinate; unresolved_duplicate", "source_hash": "abc",
        })
        self.assertEqual(decision.state, SourceRecordState.SOURCE_REJECTED)
        self.assertEqual(decision.to_row()["source_state"], "SOURCE_REJECTED")
        self.assertNotIn("autonomous_decision", decision.to_row())
        self.assertIsNone(decision.to_row()["catalog_lifecycle"])

    def test_candidate_accounting_all_five_actions_form_one_exact_partition(self):
        base = decide_candidate(candidate(), sources(candidate()))
        decisions = [
            replace(base, candidate_id=f"candidate-{index}", action=action,
                    canary_eligible=action == AutonomousAction.AUTO_CREATE)
            for index, action in enumerate(AutonomousAction)
        ]
        result = validate_decision_accounting(
            [row.candidate_id for row in decisions], [row.to_row() for row in decisions]
        )
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["decision_total"], 5)
        self.assertEqual(result["candidate_group_count"], 5)
        self.assertEqual(result["unique_candidate_count"], 5)
        self.assertEqual(result["canary_eligible_count"], 1)

    def test_candidate_accounting_rejects_duplicate_missing_and_substituted_ids(self):
        payload = decide_candidate(candidate(), sources(candidate())).to_row()
        for candidate_ids, rows, reason in (
            (["didim-1"], [payload, payload], "exactly one final decision"),
            (["didim-1", "missing"], [payload], "exactly cover candidate IDs"),
            (["didim-1"], [dict(payload, candidate_id="substitute")], "exactly cover candidate IDs"),
            (["didim-1", "didim-1"], [payload], "duplicate candidate IDs"),
            (["didim-1"], [dict(payload, candidate_id="")], "missing candidate ID"),
            (["didim-1"], [dict(payload, autonomous_decision=["AUTO_CREATE", "QUARANTINE"])], "one supported final"),
            (["didim-1"], [dict(payload, autonomous_decision="UNKNOWN")], "one supported final"),
        ):
            with self.subTest(reason=reason, candidate_ids=candidate_ids):
                with self.assertRaisesRegex(ValueError, reason):
                    validate_decision_accounting(candidate_ids, rows)

    def test_candidate_accounting_rejects_eligible_non_create_or_blocked_candidate(self):
        payload = decide_candidate(candidate(), sources(candidate())).to_row()
        for altered, reason in (
            (dict(payload, autonomous_decision="QUARANTINE"), "subset of AUTO_CREATE"),
            (dict(payload, hard_blockers=["UNVERIFIED_IDENTITY"]), "hard blocker"),
            (dict(payload, canary_eligible="false"), "must be a boolean"),
        ):
            with self.subTest(reason=reason):
                with self.assertRaisesRegex(ValueError, reason):
                    validate_decision_accounting(["didim-1"], [altered])

    def test_eligible_breakdown_uses_only_eligible_accepted_source_evidence(self):
        first = decide_candidate(candidate(), sources(candidate())).to_row()
        second = decide_candidate(candidate("other"), sources(candidate("other"))).to_row()
        second["canary_eligible"] = False
        first["field_proposals"] = [
            dict(proposal, accepted=False) if proposal["field"] == "phone" else proposal
            for proposal in first["field_proposals"]
        ]
        result = canary_eligible_breakdown([first, second])
        self.assertEqual(result["count"], 1)
        self.assertEqual(result["category_counts"], {"CAFE": 1})
        self.assertEqual(result["provider_composition"]["OVERTURE_AND_FSQ"], 1)
        self.assertEqual(result["provider_composition"]["OVERTURE_ONLY"], 0)
        self.assertEqual(result["accepted_evidence_counts"], {"phone": 0, "website": 0, "address": 1})
        self.assertEqual(result["geographic_distribution"]["distance_bands"]["0_TO_2_KM"], 1)
        self.assertEqual(result["hard_blocker_count"], 0)
        with self.assertRaisesRegex(ValueError, "outside Didim Core"):
            canary_eligible_breakdown([dict(first, latitude=38.0)])

    def test_source_record_accounting_is_a_separate_exhaustive_partition(self):
        self.assertEqual(source_record_accounting(18924, 1294), {
            "total": 18924, "usable": 17630, "rejected_before_canonical_grouping": 1294,
        })
        with self.assertRaisesRegex(ValueError, "invalid source-record accounting"):
            source_record_accounting(1, 2)

    def test_safe_independent_pair_is_auto_create_and_canary_eligible(self):
        row = candidate()
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.AUTO_CREATE)
        self.assertEqual(decision.existence_confidence, ExistenceConfidence.HIGH)
        self.assertTrue(decision.canary_eligible)
        self.assertEqual(decision.method_version, AUTONOMOUS_VALIDATION_METHOD_VERSION)
        self.assertEqual(decision.lifecycle.value, "ACTIVE")
        self.assertTrue(all(not item.can_overwrite_existing for item in decision.field_proposals))

    def test_foursquare_derived_overture_row_is_not_independent_evidence(self):
        row = candidate()
        raw_sources = sources(row)
        raw_sources[0]["provenance"] = {"sources": [{
            "provider": "foursquare",
            "dataset": "Foursquare",
            "record_id": "f-1",
        }]}
        decision = decide_candidate(row, raw_sources)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertEqual(decision.existence_confidence, ExistenceConfidence.MEDIUM)
        self.assertIn(HardBlocker.SOURCE_LINEAGE_DEPENDENCY, decision.hard_blockers)
        self.assertFalse(decision.canary_eligible)
        self.assertTrue({
            "PROVIDER_PAIR_SHARED_SOURCE_LINEAGE",
            "NON_INDEPENDENT_PROVIDER_LINEAGE",
        }.issubset({item.reason_code for item in decision.evidence}))

    def test_single_source_field_strength_is_not_inflated_by_candidate_provider_count(self):
        row = candidate(cross="OVERTURE_ONLY", fsq_id=None)
        decision = decide_candidate(row, sources(row))
        proposals = {item.field: item for item in decision.field_proposals}
        self.assertEqual(proposals["phone"].evidence_strength, EvidenceStrength.MODERATE)
        self.assertEqual(proposals["address"].evidence_strength, EvidenceStrength.MODERATE)
        self.assertEqual(len(proposals["phone"].source_observations), 1)

    def test_single_provider_website_is_not_described_as_cross_provider(self):
        row = candidate(
            cross="OVERTURE_ONLY", fsq_id=None, website="https://incikafe.com"
        )
        decision = decide_candidate(row, sources(row))
        website = next(item for item in decision.field_proposals if item.field == "website")
        self.assertEqual(website.reason_code, "identity_token_match")
        self.assertEqual(website.evidence_strength, EvidenceStrength.MODERATE)

    def test_agreeing_field_observations_are_strong_and_retain_source_identity(self):
        row = candidate()
        decision = decide_candidate(row, sources(row))
        phone = next(item for item in decision.field_proposals if item.field == "phone")
        self.assertEqual(phone.evidence_strength, EvidenceStrength.VERY_STRONG)
        self.assertEqual(
            [(item["provider"], item["external_id"]) for item in phone.source_observations],
            [("overture", "o-1"), ("fsq", "f-1")],
        )
        self.assertEqual(
            [item["raw_value"] for item in phone.source_observations],
            ["(0256) 811 22 33", "(0256) 811 22 33"],
        )
        self.assertTrue(all(item["accepted"] for item in phone.source_observations))

    def test_conflicting_valid_field_observations_are_weak(self):
        row = candidate()
        raw_sources = sources(row)
        raw_sources[1]["phone"] = "(0256) 811 22 34"
        decision = decide_candidate(row, raw_sources)
        phone = next(item for item in decision.field_proposals if item.field == "phone")
        self.assertEqual(phone.evidence_strength, EvidenceStrength.WEAK)
        self.assertEqual(
            {item["normalized_value"] for item in phone.source_observations},
            {"+902568112233", "+902568112234"},
        )

    def test_single_provider_is_quarantined_not_forced_to_create(self):
        row = candidate(cross="OVERTURE_ONLY", fsq_id=None)
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertEqual(decision.reason_code, "INSUFFICIENT_INDEPENDENT_CORROBORATION")
        self.assertFalse(decision.canary_eligible)
        self.assertIsNone(decision.lifecycle)

    def test_old_risks_map_to_explicit_hard_blockers(self):
        row = candidate(risks=(
            "CATEGORY_CONFLICT", "COORDINATE_DISAGREEMENT",
            "SAME_PROVIDER_DUPLICATE_CANDIDATE", "WEBSITE_IDENTITY_UNVERIFIED",
        ))
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertTrue({
            HardBlocker.CATEGORY_CONFLICT,
            HardBlocker.PROVIDER_GEOMETRY_CONFLICT,
            HardBlocker.SAME_PROVIDER_DUPLICATE_CONFLICT,
            HardBlocker.SUSPICIOUS_WEBSITE,
        }.issubset(decision.hard_blockers))

    def test_possible_match_has_unverified_identity_blocker(self):
        row = candidate(cross="POSSIBLE_MATCH", risks=("CROSS_PROVIDER_UNCERTAIN",))
        decision = decide_candidate(row, sources(row))
        self.assertIn(HardBlocker.UNVERIFIED_IDENTITY, decision.hard_blockers)
        self.assertEqual(decision.existence_confidence, ExistenceConfidence.LOW)

    def test_generic_or_unsupported_required_fields_quarantine(self):
        row = candidate(name="Cafe", category=None)
        decision = decide_candidate(row, sources(row))
        self.assertIn(HardBlocker.UNSAFE_CANONICAL_ATTRIBUTE, decision.hard_blockers)
        self.assertIn(HardBlocker.UNSUPPORTED_CATEGORY, decision.hard_blockers)

    def test_invalid_raw_website_is_blocker_and_raw_is_retained(self):
        row = candidate(website="http://@vetturcafebar", risks=("WEBSITE_INVALID",))
        decision = decide_candidate(row, sources(row))
        website = next(item for item in decision.field_proposals if item.field == "website")
        self.assertIn("http://@vetturcafebar", website.raw_values)
        self.assertFalse(website.accepted)
        self.assertIn(HardBlocker.SUSPICIOUS_WEBSITE, decision.hard_blockers)

    def test_external_evidence_cannot_elevate_single_provider(self):
        class External:
            provider_name = "FIXTURE"

            def collect(self, row, source_rows):
                del row, source_rows
                return (EvidenceSignal(
                    EvidenceDimension.EXTERNAL,
                    EvidenceStrength.VERY_STRONG,
                    "EXTERNAL_CLAIM",
                    "Untrusted fixture claim",
                ),)

        row = candidate(cross="FSQ_ONLY", overture_id=None)
        decision = decide_candidate(row, sources(row), external_evidence=External())
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertTrue(any(item.dimension == EvidenceDimension.EXTERNAL for item in decision.evidence))

    def test_noop_external_evidence_is_empty(self):
        self.assertEqual(NoopExternalEvidenceProvider().collect(candidate(), []), ())

    def test_legacy_auto_link_label_without_trusted_evidence_quarantines(self):
        row = candidate(
            classification="AUTO_LINK", existing_place_id="place-1",
            cross="OVERTURE_ONLY", fsq_id=None,
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.UNVERIFIED_IDENTITY, decision.hard_blockers)

    def test_exact_existing_reference_can_auto_enrich_without_overwrite(self):
        row = replace(
            candidate(
                classification="AUTO_ENRICH",
                existing_place_id="place-1",
                cross="OVERTURE_ONLY",
                fsq_id=None,
            ),
            matching_reasons=("exact_external_ref",),
            match_score=100,
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.AUTO_ENRICH)
        self.assertNotIn(HardBlocker.UNVERIFIED_IDENTITY, decision.hard_blockers)
        self.assertTrue(all(
            not proposal.can_overwrite_existing for proposal in decision.field_proposals
        ))

    def test_exact_existing_reference_can_auto_link(self):
        row = replace(
            candidate(
                classification="AUTO_LINK",
                existing_place_id="place-1",
                cross="OVERTURE_ONLY",
                fsq_id=None,
            ),
            matching_reasons=("exact_external_ref",),
            match_score=100,
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.AUTO_LINK)
        self.assertNotIn(HardBlocker.UNVERIFIED_IDENTITY, decision.hard_blockers)

    def test_weak_existing_match_cannot_auto_enrich(self):
        row = replace(
            candidate(
                classification="REVIEW_REQUIRED",
                existing_place_id="place-1",
            ),
            matching_reasons=("distance<=30m:+3", "name>=0.82:+1"),
            match_score=5,
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.UNVERIFIED_IDENTITY, decision.hard_blockers)

    def test_ambiguous_existing_targets_cannot_auto_enrich(self):
        row = replace(
            candidate(
                classification="REVIEW_REQUIRED",
                existing_place_id="place-1",
            ),
            matching_reasons=(
                "exact_external_ref", "multiple_credible_existing_candidates",
            ),
            match_score=100,
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.UNVERIFIED_IDENTITY, decision.hard_blockers)

    def test_valid_exact_provider_phone_and_domain_are_very_strong(self):
        row = candidate(website="https://incikafe.com")
        decision = decide_candidate(row, sources(row))
        codes = {item.reason_code: item.strength for item in decision.evidence}
        self.assertEqual(codes["INDEPENDENT_EXACT_PHONE"], EvidenceStrength.VERY_STRONG)
        self.assertEqual(
            codes["INDEPENDENT_EXACT_WEBSITE_DOMAIN"], EvidenceStrength.VERY_STRONG
        )
        self.assertEqual(codes["PROVIDER_NAME_EXACT"], EvidenceStrength.VERY_STRONG)
        self.assertEqual(codes["PROVIDER_COORDINATE_AGREEMENT"], EvidenceStrength.STRONG)

    def test_malformed_provider_values_never_form_exact_evidence(self):
        row = candidate(website="http://@handle")
        raw_sources = sources(row)
        for source_row in raw_sources:
            source_row["phone"] = "not a phone"
        decision = decide_candidate(row, raw_sources)
        codes = {item.reason_code for item in decision.evidence}
        self.assertNotIn("INDEPENDENT_EXACT_PHONE", codes)
        self.assertNotIn("INDEPENDENT_EXACT_WEBSITE_DOMAIN", codes)

    def test_closed_or_does_not_exist_source_can_never_auto_create(self):
        row = candidate()
        raw_sources = sources(row)
        raw_sources[0]["operating_status"] = "CLOSED"
        raw_sources[1]["provenance"] = {"unresolved_flags": ["doesnt_exist"]}
        decision = decide_candidate(row, raw_sources)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.OPERATIONAL_STATUS_CONFLICT, decision.hard_blockers)
        self.assertFalse(decision.canary_eligible)
        self.assertNotEqual(decision.existence_confidence, ExistenceConfidence.HIGH)

    def test_severe_unresolved_and_closed_variants_can_never_auto_create(self):
        for signal in (
            "duplicate", "privatevenue", "private_venue", "inappropriate",
            "not_a_place", "unresolved_duplicate", "temporarily_closed",
            "closed_for_season",
        ):
            row = candidate()
            raw_sources = sources(row)
            if "closed" in signal:
                raw_sources[0]["operating_status"] = signal
            else:
                raw_sources[0]["provenance"] = {"unresolved_flags": [signal]}
            with self.subTest(signal=signal):
                decision = decide_candidate(row, raw_sources)
                self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
                self.assertIn(
                    HardBlocker.OPERATIONAL_STATUS_CONFLICT, decision.hard_blockers
                )

    def test_stale_provider_pair_cannot_confer_high_existence_confidence(self):
        row = candidate()
        raw_sources = sources(row)
        for source_row in raw_sources:
            source_row["observed_at"] = "2020-01-01T00:00:00Z"
        decision = decide_candidate(row, raw_sources)
        self.assertEqual(decision.existence_confidence, ExistenceConfidence.MEDIUM)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertFalse(decision.canary_eligible)
        self.assertIn(
            "PROVIDER_PAIR_STALE_OR_UNDATED",
            {item.reason_code for item in decision.evidence},
        )

    def test_one_stale_source_prevents_high_existence_confidence(self):
        row = candidate()
        raw_sources = sources(row)
        raw_sources[0]["observed_at"] = "2020-01-01T00:00:00Z"
        decision = decide_candidate(row, raw_sources)
        self.assertEqual(decision.existence_confidence, ExistenceConfidence.MEDIUM)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertFalse(decision.canary_eligible)

    def test_claimed_pair_with_missing_provider_observation_cannot_auto_create(self):
        row = candidate()
        decision = decide_candidate(row, sources(row)[:1])
        self.assertEqual(decision.existence_confidence, ExistenceConfidence.MEDIUM)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(
            "PROVIDER_PAIR_SOURCE_INCOMPLETE",
            {item.reason_code for item in decision.evidence},
        )

    def test_intra_provider_multi_category_conflict_quarantines(self):
        row = candidate()
        raw_sources = sources(row)
        raw_sources[0]["provider_categories"] = ["cafe", "restaurant"]
        decision = decide_candidate(row, raw_sources)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.CATEGORY_CONFLICT, decision.hard_blockers)

    def test_mapped_category_with_unknown_provider_sibling_quarantines(self):
        row = candidate()
        raw_sources = sources(row)
        raw_sources[0]["provider_categories"] = ["cafe", "unreviewed_provider_kind"]
        decision = decide_candidate(row, raw_sources)
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.UNSUPPORTED_CATEGORY, decision.hard_blockers)


class EntityAndReplaySafetyTest(unittest.TestCase):
    def test_material_rule_changes_have_auditable_method_versions(self):
        self.assertEqual(CANONICALIZATION_METHOD_VERSION, "didim-canonicalization-v3")
        self.assertEqual(
            AUTONOMOUS_VALIDATION_METHOD_VERSION,
            "didim-autonomous-validation-v2",
        )
        self.assertEqual(
            AUTONOMY_ARTIFACT_STATUS,
            "ARTIFACTS_VALIDATED_PENDING_FULL_RELEASE_GATE",
        )

    def test_autonomy_output_status_does_not_claim_release_readiness(self):
        row = candidate()
        raw_sources = sources(row)
        context = {
            (str(source["provider"]), str(source["external_id"])): source
            for source in raw_sources
        }
        validated = {
            "provenance": {
                "canonicalization_method_version": "didim-canonicalization-v2",
                "providers": {
                    "overture": {"resolved_release": "2026-09-23.0"},
                    "fsq": {"resolved_release": "2026-09-15 20:07:45.157000"},
                },
            },
            "reproducible": {
                "source_context": context,
                "summary": {
                    "scope": {"key": "didim_core"},
                    "source_counts": {"overture": 1, "fsq": 1},
                },
            },
            "candidates": (row,),
            "rejected": (),
        }
        validated["source_package_artifact_sha256"] = {
            "didim_core_summary.json": "e" * 64,
            "didim_core_source_records.jsonl": "a" * 64,
            "didim_core_canonical_candidates.csv": "b" * 64,
            "didim_core_rejected.csv": "c" * 64,
            "PHYSICAL_VALIDATION_SAMPLE.csv": "d" * 64,
        }
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.object(
                autonomous_validation,
                "load_validated_replay_package",
                return_value=validated,
            ):
                result = autonomous_validation.run_autonomous_didim(
                    root, root / "artifacts"
                )
        self.assertEqual(result["summary"]["status"], AUTONOMY_ARTIFACT_STATUS)
        self.assertNotIn("READY_FOR", result["summary"]["status"])
        self.assertEqual(
            result["summary"]["frozen_input_hashes"]["source_records_sha256"],
            "a" * 64,
        )

    def test_run_keeps_candidate_auto_reject_distinct_from_pre_grouping_source_rejects(self):
        create = candidate()
        rejected_candidate = candidate("rejected-candidate", overture_id="o-2", fsq_id=None)
        create_decision = decide_candidate(create, sources(create))
        reject_decision = replace(
            decide_candidate(rejected_candidate, sources(rejected_candidate)),
            action=AutonomousAction.AUTO_REJECT, canary_eligible=False,
            reason_code="EXPLICIT_CANDIDATE_REJECTION",
        )
        source_reject = {
            "provider": "fsq", "external_id": "f-rejected", "reasons": "invalid_coordinate",
            "source_hash": "rejected-hash",
        }
        context = {
            (str(source["provider"]), str(source["external_id"])): source
            for row in (create, rejected_candidate) for source in sources(row)
        }
        validated = {
            "provenance": {
                "canonicalization_method_version": "didim-canonicalization-v3",
                "scope": {"key": "didim_core"},
                "source_counts": {"overture": 2, "fsq": 3},
                "providers": {
                    "overture": {"resolved_release": "2026-09-23.0"},
                    "fsq": {"resolved_release": "2026-09-15 20:07:45.157000"},
                },
            },
            "source_package_artifact_sha256": {},
            "reproducible": {"source_context": context},
            "candidates": (create, rejected_candidate),
            "rejected": (source_reject, dict(source_reject, external_id="f-rejected-2")),
        }
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with (
                patch.object(autonomous_validation, "load_validated_replay_package", return_value=validated),
                patch.object(autonomous_validation, "evaluate_candidates", return_value=(create_decision, reject_decision)),
            ):
                result = autonomous_validation.run_autonomous_didim(root, root / "artifacts")
            summary = result["summary"]
            self.assertEqual(summary["reporting_schema_version"], AUTONOMY_REPORTING_SCHEMA_VERSION)
            self.assertEqual(summary["source_record_states"], {
                "total": 5, "usable": 3, "rejected_before_canonical_grouping": 2,
            })
            self.assertEqual(summary["source_rejected"], 2)
            self.assertEqual(summary["candidate_decisions"], {
                "AUTO_LINK": 0, "AUTO_CREATE": 1, "AUTO_ENRICH": 0, "AUTO_REJECT": 1, "QUARANTINE": 0,
            })
            self.assertEqual(summary["actions"], summary["candidate_decisions"])
            self.assertEqual(sum(summary["candidate_decisions"].values()), summary["candidate_groups"])
            self.assertEqual(summary["action_rates_over_candidate_groups"]["AUTO_REJECT"], 0.5)
            self.assertEqual(summary["accounting_invariants"]["status"], "PASS")
            with (root / "artifacts" / "auto_reject.csv").open(encoding="utf-8-sig", newline="") as handle:
                candidate_reject_rows = list(csv.DictReader(handle))
            with (root / "artifacts" / "source_rejected.csv").open(encoding="utf-8-sig", newline="") as handle:
                source_reject_rows = list(csv.DictReader(handle))
            self.assertEqual([row["candidate_id"] for row in candidate_reject_rows], ["rejected-candidate"])
            self.assertEqual([row["source_state"] for row in source_reject_rows], ["SOURCE_REJECTED", "SOURCE_REJECTED"])
            self.assertNotIn("autonomous_decision", source_reject_rows[0])
            self.assertIn("AUTO_REJECT: 1", (root / "artifacts" / "AUTONOMY_REPORT.md").read_text(encoding="utf-8"))
            for filename in ("candidate_decision_distribution.csv", "source_record_state_distribution.csv", "source_rejected.csv"):
                self.assertEqual(summary["artifact_sha256"][filename], autonomous_validation._file_sha256(root / "artifacts" / filename))

    def test_candidate_csv_reader_restores_formula_protected_phone(self):
        raw = candidate().to_row()
        raw["phone"] = "'+902568112233"
        restored = _candidate_from_csv(raw)
        self.assertEqual(restored.phone, "+902568112233")

    def test_bona_fide_hotel_is_not_a_subvenue(self):
        row = replace(
            candidate(name="Ege Resort Hotel", category="HOTEL"),
            provider_categories=("overture:hotel", "fsq:Hotel"),
        )
        self.assertEqual(detect_entity_hierarchy(row, sources(row)), ())

    def test_hotel_category_does_not_exempt_child_entity_name(self):
        row = replace(
            candidate(name="Ege Resort Lobby", category="HOTEL"),
            provider_categories=("overture:hotel", "fsq:Hotel"),
        )
        blockers = detect_entity_hierarchy(row, sources(row))
        self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, blockers)
        self.assertIn(HardBlocker.TOURISM_NESTING, blockers)

    def test_substring_collisions_do_not_create_hierarchy_signals(self):
        barber = replace(
            candidate(name="Ege Resort Barber", category="ACTIVITY"),
            provider_categories=("overture:barber",),
        )
        garden = replace(
            candidate(name="Ege Home and Garden", category="ACTIVITY"),
            provider_categories=("overture:hardware_home_and_garden_store",),
        )
        self.assertEqual(detect_entity_hierarchy(barber, sources(barber)), ())
        self.assertEqual(detect_entity_hierarchy(garden, sources(garden)), ())

    def test_parent_child_phrase_is_subvenue_and_tourism_nesting(self):
        row = candidate(name="Ege Resort Lobby Bar", category="BAR")
        blockers = detect_entity_hierarchy(row, sources(row))
        self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, blockers)
        self.assertIn(HardBlocker.TOURISM_NESTING, blockers)

    def test_hotel_domain_blocks_restaurant_subvenue_candidate(self):
        row = candidate(
            name="Sultans and Kings Restaurant",
            category="RESTAURANT",
            website="https://www.sultansandkingshotel.com/",
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, decision.hard_blockers)
        self.assertIn(HardBlocker.TOURISM_NESTING, decision.hard_blockers)

    def test_known_blue_point_beach_club_is_quarantined(self):
        row = candidate(
            "didim-b2402d17feec4cf8be2ddc3f",
            name="Blue Point Beach Club",
            category="BEACH",
            risks=("BEACH_VS_BEACH_CLUB_RISK",),
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertFalse(decision.canary_eligible)
        self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, decision.hard_blockers)
        self.assertIn(HardBlocker.TOURISM_NESTING, decision.hard_blockers)

    def test_common_beach_club_misspelling_is_still_hierarchy_ambiguous(self):
        for candidate_id, name, category in (
            ("didim-5069956eaca439521cdf7d6f", "Köy Hizmetleri Beach Clup", "BEACH"),
            ("didim-b1561b4a03707685f0cf8a17", "Tren Pera Beach Clup", "NIGHTLIFE"),
        ):
            with self.subTest(candidate_id=candidate_id):
                row = candidate(candidate_id, name=name, category=category)
                decision = decide_candidate(row, sources(row))
                self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
                self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, decision.hard_blockers)
                self.assertIn(HardBlocker.TOURISM_NESTING, decision.hard_blockers)

    def test_d_marin_tenant_is_tourism_nesting(self):
        row = candidate(
            "didim-9a289ddae18081e0922c8880",
            name="D-Marin Gemici Cafe",
            category="CAFE",
        )
        decision = decide_candidate(row, sources(row))
        self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
        self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, decision.hard_blockers)
        self.assertIn(HardBlocker.TOURISM_NESTING, decision.hard_blockers)

    def test_legacy_hierarchy_risk_flags_fail_closed(self):
        expected = {
            "HOTEL_SUBVENUE_RISK": HardBlocker.TOURISM_NESTING,
            "MARINA_SUB_BUSINESS_RISK": HardBlocker.TOURISM_NESTING,
            "BEACH_VS_BEACH_CLUB_RISK": HardBlocker.TOURISM_NESTING,
            "BUILDING_BUSINESS_NESTING_RISK": HardBlocker.POSSIBLE_SUBVENUE,
        }
        for risk, blocker in expected.items():
            with self.subTest(risk=risk):
                row = candidate(risks=(risk,))
                decision = decide_candidate(row, sources(row))
                self.assertEqual(decision.action, AutonomousAction.QUARANTINE)
                self.assertIn(blocker, decision.hard_blockers)

    def test_parent_name_and_subvenue_category_across_fields_are_blocked(self):
        row = candidate(name="Ege Resort", category="RESTAURANT")
        blockers = detect_entity_hierarchy(row, sources(row))
        self.assertIn(HardBlocker.POSSIBLE_SUBVENUE, blockers)

    def test_same_name_nearby_is_derived_globally_and_deterministically(self):
        first = candidate("didim-a", overture_id="o-a", fsq_id="f-a")
        second = candidate(
            "didim-b", overture_id="o-b", fsq_id="f-b",
            latitude=37.37515, longitude=27.26785,
        )
        context = {
            (str(row["provider"]), str(row["external_id"])): row
            for item in (first, second) for row in sources(item)
        }
        one = evaluate_candidates([second, first], context)
        two = evaluate_candidates([first, second], context)
        self.assertEqual(one, two)
        self.assertTrue(all(
            HardBlocker.SAME_NAME_MULTIPLE_NEARBY in row.hard_blockers for row in one
        ))

    def test_canary_selection_is_eligible_only_and_deterministic(self):
        rows = []
        for index in range(4):
            item = candidate(
                f"didim-{index}", overture_id=f"o-{index}", fsq_id=f"f-{index}",
                name=f"İnci Kafe {index}", latitude=37.37 + index * 0.001,
            )
            rows.append(decide_candidate(item, sources(item)))
        selected = select_stage_one(list(reversed(rows)), 2)
        self.assertEqual(selected, select_stage_one(rows, 2))
        self.assertEqual(len(selected), 2)
        self.assertTrue(set(selected).issubset({row.candidate_id for row in rows}))

    def test_canary_selection_spans_category_and_geo_buckets(self):
        inputs = [
            candidate("a", name="Alpha Cafe", category="CAFE", latitude=37.370, longitude=27.260),
            candidate("b", name="Beta Cafe", category="CAFE", latitude=37.371, longitude=27.261),
            candidate("c", name="Gamma Restaurant", category="RESTAURANT", latitude=37.380, longitude=27.280),
        ]
        decisions = [decide_candidate(row, sources(row)) for row in inputs]
        selected = set(select_stage_one(decisions, 2))
        self.assertIn("c", selected)
        self.assertEqual(len(selected), 2)

    def test_stage_one_size_cannot_exceed_locked_cap(self):
        with self.assertRaisesRegex(ValueError, r"min\(100, eligible\)"):
            autonomous_validation.run_autonomous_didim(
                Path("unused"), Path("unused-output"), stage_size=101
            )
        with self.assertRaisesRegex(ValueError, r"min\(100, eligible\)"):
            autonomous_validation.run_autonomous_didim(
                Path("unused"), Path("unused-output"), stage_size=99
            )

    def test_quarantine_transition_replay_is_sorted_and_deterministic(self):
        first = candidate("didim-b", cross="FSQ_ONLY", overture_id=None)
        second = candidate("didim-a", cross="OVERTURE_ONLY", fsq_id=None)
        decisions = [decide_candidate(row, sources(row)) for row in (first, second)]
        one = quarantine_transition_rows(["didim-b", "didim-a"], decisions)
        two = quarantine_transition_rows(["didim-a", "didim-b"], list(reversed(decisions)))
        self.assertEqual(one, two)
        self.assertEqual([row["candidate_id"] for row in one], ["didim-a", "didim-b"])

    def test_quarantine_transition_resolves_candidate_regrouping_by_provider_refs(self):
        successor = candidate("didim-successor", overture_id="o-1", fsq_id="f-1")
        decision = decide_candidate(successor, sources(successor))
        rows = quarantine_transition_rows([{
            "candidate_id": "didim-prior",
            "overture_id": "o-1",
            "fsq_id": None,
        }], [decision])
        self.assertEqual(rows[0]["prior_candidate_id"], "didim-prior")
        self.assertEqual(rows[0]["current_candidate_id"], "didim-successor")

    def test_quarantine_transition_preserves_missing_lineage_as_quarantine(self):
        rows = quarantine_transition_rows([{
            "candidate_id": "didim-prior",
            "overture_id": "o-missing",
            "fsq_id": None,
        }], [])
        self.assertEqual(rows[0]["current_decision"], AutonomousAction.QUARANTINE)
        self.assertEqual(rows[0]["current_reason"], "SOURCE_LINEAGE_MISSING")
        self.assertIsNone(rows[0]["current_candidate_id"])

    def test_quarantine_transition_keeps_ambiguous_provider_ref_lineage_quarantined(self):
        decisions = []
        for suffix in ("a", "b"):
            row = candidate(
                f"didim-{suffix}", overture_id="o-1", fsq_id=f"f-{suffix}"
            )
            decisions.append(decide_candidate(row, sources(row)))
        rows = quarantine_transition_rows([{
            "candidate_id": "didim-prior",
            "overture_id": "o-1",
            "fsq_id": None,
        }], decisions)
        self.assertEqual(rows[0]["current_decision"], AutonomousAction.QUARANTINE)
        self.assertEqual(rows[0]["current_reason"], "AMBIGUOUS_PROVIDER_REF_LINEAGE")
        self.assertEqual(rows[0]["lineage_candidates"], ["didim-a", "didim-b"])

    def test_frozen_guard_rejects_superseded_method_before_replay(self):
        with tempfile.TemporaryDirectory() as temp:
            package = Path(temp)
            (package / "didim_core_summary.json").write_text(json.dumps({
                "method_version": "didim-canonicalization-v1",
            }), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "didim-canonicalization-v2"):
                verify_corrected_source_package(package)

    def test_frozen_guard_hashes_rejected_and_calibration_files(self):
        with tempfile.TemporaryDirectory() as temp:
            package = Path(temp)
            (package / "didim_core_summary.json").write_text(json.dumps({
                "method_version": "didim-canonicalization-v2",
                "superseded_human_review_must_not_be_ingested": True,
                "source_counts": {"fsq": 15125, "overture": 3799},
                "providers": {
                    "overture": {"resolved_release": "2026-09-23.0"},
                    "fsq": {
                        "resolved_release": "2026-09-15 20:07:45.157000",
                        "snapshot_id": "2325979374271449319",
                    },
                },
            }), encoding="utf-8")
            original = {
                "source": b"source\n",
                "candidate": b"candidate\n",
                "rejected": b"rejected\n",
                "sample": b"sample\n",
            }
            paths = {
                "source": package / "didim_core_source_records.jsonl",
                "candidate": package / "didim_core_canonical_candidates.csv",
                "rejected": package / "didim_core_rejected.csv",
                "sample": package / "PHYSICAL_VALIDATION_SAMPLE.csv",
            }
            for key, path in paths.items():
                path.write_bytes(original[key])
            digest = {key: hashlib.sha256(value).hexdigest() for key, value in original.items()}
            with patch.multiple(
                autonomous_validation,
                _FROZEN_SOURCE_SHA256=digest["source"],
                _FROZEN_CANDIDATE_SHA256=digest["candidate"],
                _FROZEN_REJECTED_SHA256=digest["rejected"],
                _FROZEN_SAMPLE_SHA256=digest["sample"],
            ):
                verify_corrected_source_package(package)
                paths["rejected"].write_bytes(original["rejected"] + b"tampered")
                with self.assertRaisesRegex(ValueError, "rejected source records"):
                    verify_corrected_source_package(package)
                paths["rejected"].write_bytes(original["rejected"])
                paths["sample"].write_bytes(original["sample"] + b"tampered")
                with self.assertRaisesRegex(ValueError, "calibration sample"):
                    verify_corrected_source_package(package)

    def test_generic_replay_accepts_a_newer_explicit_validated_snapshot(self):
        summary = {
            "method_version": "didim-canonicalization-v3",
            "scope": {
                "key": "didim_core",
                "center": {"latitude": 37.3751, "longitude": 27.2678},
                "radius_meters": 6000,
            },
            "source_counts": {"overture": 1, "fsq": 1},
            "providers": {
                "overture": {
                    "resolved_release": "2026-10-01.0",
                    "schema_version": "2.1.0",
                    "snapshot_id": None,
                },
                "fsq": {
                    "resolved_release": "2026-10-01 12:00:00",
                    "schema_version": "FSQ_OS_NEXT",
                    "snapshot_id": "future-snapshot-1",
                },
            },
        }
        row = candidate()
        context = {}
        for source_row in sources(row):
            provider = str(source_row["provider"])
            provider_provenance = summary["providers"][provider]
            source_row.update({
                "method_version": summary["method_version"],
                "source_release": provider_provenance["resolved_release"],
                "schema_version": provider_provenance["schema_version"],
                "snapshot_id": provider_provenance["snapshot_id"],
                "source_hash": "hash-o" if provider == "overture" else "hash-f",
            })
            context[(provider, str(source_row["external_id"]))] = source_row
        artifact_payloads = {
            "didim_core_summary.json": json.dumps(summary).encode("utf-8"),
            "didim_core_source_records.jsonl": b"current-v3-source-records\n",
            "didim_core_canonical_candidates.csv": b"current-v3-candidates\n",
            "didim_core_rejected.csv": b"current-v3-rejected\n",
        }
        with tempfile.TemporaryDirectory() as temp:
            package = Path(temp)
            for filename, payload in artifact_payloads.items():
                (package / filename).write_bytes(payload)
            with self.assertRaisesRegex(ValueError, "didim-canonicalization-v2"):
                verify_corrected_source_package(package)
            with (
                patch.object(
                    autonomous_validation,
                    "_load_reproducible_inputs",
                    return_value={"summary": summary, "source_context": context},
                ),
                patch.object(autonomous_validation, "_read_candidates", return_value=[row]),
                patch.object(autonomous_validation, "_read_rejected", return_value=[]),
                patch.object(
                    autonomous_validation, "_verify_recomputed_semantics"
                ) as semantic_check,
                patch.object(
                    autonomous_validation,
                    "verify_corrected_source_package",
                    side_effect=AssertionError("v3 must not enter the frozen-v2 guard"),
                ),
            ):
                loaded = load_validated_replay_package(package)
                result = autonomous_validation.run_autonomous_didim(
                    package, package / "autonomous-output"
                )
                self.assertEqual(semantic_check.call_count, 2)
        self.assertEqual(
            loaded["provenance"]["canonicalization_method_version"],
            "didim-canonicalization-v3",
        )
        self.assertEqual(
            loaded["provenance"]["providers"]["fsq"]["snapshot_id"],
            "future-snapshot-1",
        )
        expected_hashes = {
            filename: hashlib.sha256(payload).hexdigest()
            for filename, payload in artifact_payloads.items()
        }
        self.assertEqual(
            result["summary"]["source_package_provenance"], loaded["provenance"]
        )
        self.assertEqual(result["summary"]["scope"], loaded["provenance"]["scope"])
        self.assertEqual(
            result["summary"]["source_counts"],
            loaded["provenance"]["source_counts"],
        )
        self.assertEqual(
            result["summary"]["source_package_artifact_sha256"], expected_hashes
        )
        self.assertNotIn("frozen_input_hashes", result["summary"])

    def test_current_replay_rejects_tampered_candidate_semantics(self):
        row = candidate()
        recomputed = replace(row, proposed_name="Recomputed Name")
        reproducible = {
            "normalized": {"overture": [], "fsq": []},
            "source_context": {},
        }
        with patch.object(
            autonomous_validation,
            "build_canonicalization_plan",
            return_value=SimpleNamespace(
                candidates=(recomputed,), rejected=(), source_records=()
            ),
        ):
            with self.assertRaisesRegex(ValueError, "candidate semantics"):
                autonomous_validation._verify_recomputed_semantics(
                    reproducible, [row], []
                )

    def test_replay_envelope_hash_binds_observation_sequence_and_provenance(self):
        source = {
            "provider": "overture", "external_id": "o-1",
            "source_sequence": 0, "observed_at": "2026-09-20T00:00:00Z",
            "retrieved_at": "2026-09-23T00:00:00Z",
            "provenance": {"area": "didim_core"},
        }
        digest = _replay_integrity_hash(source)
        for field, value in (
            ("source_sequence", 1),
            ("observed_at", "2020-01-01T00:00:00Z"),
            ("provenance", {"area": "elsewhere"}),
        ):
            tampered = dict(source, **{field: value}, replay_integrity_hash=digest)
            with self.subTest(field=field):
                self.assertNotEqual(_replay_integrity_hash(tampered), digest)

    def test_semantic_replay_recomputes_observation_time(self):
        row = candidate()
        expected = {
            "provider": "overture", "external_id": "o-1",
            "method_version": CANONICALIZATION_METHOD_VERSION,
            "source_hash": "hash-o", "provider_categories": ["cafe"],
            "proposed_place_category": "CAFE",
            "canonical_website_eligible": False,
            "website_validation_reason": "missing",
            "source_sequence": 0, "provenance": {"area": "didim_core"},
            "observed_at": "2026-09-20", "retrieved_at": "2026-09-23",
        }
        actual = dict(expected, observed_at="2020-01-01")
        reproducible = {
            "normalized": {"overture": [], "fsq": []},
            "source_context": {("overture", "o-1"): actual},
        }
        with patch.object(
            autonomous_validation,
            "build_canonicalization_plan",
            return_value=SimpleNamespace(
                candidates=(row,), rejected=(), source_records=(expected,),
            ),
        ):
            with self.assertRaisesRegex(ValueError, "observation time"):
                autonomous_validation._verify_recomputed_semantics(
                    reproducible, [row], []
                )

    def test_re_evaluation_quarantine_is_full_auditable_and_chainable(self):
        validated = {
            "provenance": {
                "providers": {
                    "overture": {"resolved_release": "2026-09-23.0"},
                    "fsq": {"resolved_release": "2026-09-15 20:07:45.157000"},
                },
            },
            "reproducible": {"source_context": {}},
            "candidates": (),
        }
        prior = [
            {
                "candidate_id": "didim-lost", "overture_id": "o-lost", "fsq_id": None,
            },
            {
                "candidate_id": "didim-ambiguous", "overture_id": "o-shared",
                "fsq_id": None,
            },
        ]
        successors = tuple(
            decide_candidate(row, sources(row))
            for row in (
                candidate("didim-next-a", overture_id="o-shared", fsq_id="f-a"),
                candidate("didim-next-b", overture_id="o-shared", fsq_id="f-b"),
            )
        )
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            prior_root = root / "prior"
            prior_root.mkdir()
            (prior_root / "autonomous_summary.json").write_text("{}", encoding="utf-8")
            output = root / "output"
            with (
                patch.object(
                    autonomous_validation,
                    "load_validated_replay_package",
                    return_value=validated,
                ),
                patch.object(
                    autonomous_validation, "evaluate_candidates", return_value=successors,
                ),
                patch.object(
                    autonomous_validation,
                    "_load_verified_prior_quarantine",
                    return_value=("prior-method", prior),
                ),
            ):
                result = run_quarantine_re_evaluation(root, prior_root, output)
            with (output / "quarantine.csv").open(
                "r", encoding="utf-8-sig", newline=""
            ) as handle:
                quarantine = list(csv.DictReader(handle))
            self.assertEqual(len(quarantine), 2)
            by_reason = {row["decision_reason"]: row for row in quarantine}
            self.assertEqual(
                set(by_reason),
                {"SOURCE_LINEAGE_MISSING", "AMBIGUOUS_PROVIDER_REF_LINEAGE"},
            )
            self.assertTrue(json.loads(by_reason["SOURCE_LINEAGE_MISSING"]["evidence"]))
            proposals = json.loads(
                by_reason["AMBIGUOUS_PROVIDER_REF_LINEAGE"]["field_proposals"]
            )
            self.assertEqual(
                {row["field"] for row in proposals},
                {"name", "category", "coordinates", "address", "phone", "website"},
            )
            self.assertTrue(all(not row["accepted"] for row in proposals))
            lineage = json.loads(
                (output / "re_evaluation_lineage.json").read_text(encoding="utf-8")
            )
            self.assertEqual(len(lineage["current_quarantine"]), 2)
            for filename, digest in result["summary"]["artifact_sha256"].items():
                self.assertEqual(
                    autonomous_validation._file_sha256(output / filename), digest
                )
            self.assertTrue((output / "autonomous_summary.json").is_file())

            second_output = root / "second-output"
            with (
                patch.object(
                    autonomous_validation,
                    "load_validated_replay_package",
                    return_value=validated,
                ),
                patch.object(
                    autonomous_validation, "evaluate_candidates", return_value=successors,
                ),
            ):
                second = run_quarantine_re_evaluation(root, output, second_output)
            self.assertEqual(second["summary"]["prior_quarantine_count"], 2)
            self.assertEqual(second["summary"]["missing_source_lineage_count"], 1)
            self.assertEqual(second["summary"]["ambiguous_source_lineage_count"], 1)
            with (second_output / "candidate_evidence.csv").open(
                "r", encoding="utf-8-sig", newline=""
            ) as handle:
                second_evidence = list(csv.DictReader(handle))
            self.assertEqual(len(second_evidence), 2)
            self.assertEqual(
                {row["decision_reason"] for row in second_evidence},
                {"SOURCE_LINEAGE_MISSING", "AMBIGUOUS_PROVIDER_REF_LINEAGE"},
            )

    def test_prior_autonomy_digest_and_quarantine_subset_are_verified(self):
        row = candidate(cross="OVERTURE_ONLY", fsq_id=None)
        decision = decide_candidate(row, sources(row))
        payload = decision.to_row()
        summary = {
            "method_version": AUTONOMOUS_VALIDATION_METHOD_VERSION,
            "decision_digest": autonomous_validation._decision_digest([decision]),
            "candidate_groups": 1,
            "actions": {
                action.value: int(action == AutonomousAction.QUARANTINE)
                for action in AutonomousAction
            },
        }
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            autonomous_validation.write_csv(
                root / "candidate_evidence.csv", [payload], ["candidate_id"]
            )
            autonomous_validation.write_csv(
                root / "quarantine.csv", [payload], ["candidate_id"]
            )
            summary["artifact_sha256"] = {
                filename: autonomous_validation._file_sha256(root / filename)
                for filename in ("candidate_evidence.csv", "quarantine.csv")
            }
            method, rows = autonomous_validation._load_verified_prior_quarantine(
                root, summary
            )
            self.assertEqual(method, AUTONOMOUS_VALIDATION_METHOD_VERSION)
            self.assertEqual(len(rows), 1)
            inconsistent = dict(summary, actions=dict(summary["actions"], AUTO_REJECT=1294))
            with self.assertRaisesRegex(ValueError, "action counts do not match"):
                autonomous_validation._load_verified_prior_quarantine(root, inconsistent)
            tampered = dict(payload, decision_reason="tampered")
            autonomous_validation.write_csv(
                root / "quarantine.csv", [tampered], ["candidate_id"]
            )
            with self.assertRaisesRegex(ValueError, "artifact hash mismatch"):
                autonomous_validation._load_verified_prior_quarantine(root, summary)
            summary["artifact_sha256"]["quarantine.csv"] = (
                autonomous_validation._file_sha256(root / "quarantine.csv")
            )
            with self.assertRaisesRegex(ValueError, "do not match candidate evidence"):
                autonomous_validation._load_verified_prior_quarantine(root, summary)

    def test_generic_replay_requires_explicit_method_release_and_snapshot_provenance(self):
        base = {
            "method_version": "didim-canonicalization-v3",
            "scope": {
                "key": "didim_core",
                "center": {"latitude": 37.3751, "longitude": 27.2678},
                "radius_meters": 6000,
            },
            "source_counts": {"overture": 1, "fsq": 1},
            "providers": {
                "overture": {
                    "resolved_release": "2026-10-01.0", "schema_version": "2.1.0",
                },
                "fsq": {
                    "resolved_release": "2026-10-01", "schema_version": "FSQ_OS_NEXT",
                },
            },
        }
        with self.assertRaisesRegex(ValueError, "FSQ snapshot provenance"):
            _validated_package_provenance(base)
        missing_method = dict(base, method_version="")
        with self.assertRaisesRegex(ValueError, "canonicalization method provenance"):
            _validated_package_provenance(missing_method)

    def test_anomaly_baseline_has_version_and_counts(self):
        row = candidate()
        decision = decide_candidate(row, sources(row))
        baseline = catalog_anomaly_baseline([decision], 3)
        self.assertEqual(baseline["method_version"], AUTONOMOUS_VALIDATION_METHOD_VERSION)
        self.assertEqual(baseline["candidate_count"], 1)
        self.assertEqual(baseline["source_reject_count"], 3)


if __name__ == "__main__":
    unittest.main()
