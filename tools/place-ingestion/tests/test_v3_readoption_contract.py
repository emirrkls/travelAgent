import copy
import unittest
from test_canary_manifest import _envelope
from phokarta_place_ingestion.canary_manifest import (
    CONTAINED_V2_RUN, V3_VALIDATION_METHOD, _sha256_json, validate_canary_manifest_contract,
)


class V3ReadoptionContractTest(unittest.TestCase):
    def setUp(self):
        self.old = _envelope(71)
        self.old["manifest"]["run_id"] = CONTAINED_V2_RUN
        self.old["manifest_hash"] = _sha256_json(self.old["manifest"])
        self.old_snapshot = copy.deepcopy(self.old)
        self.new = copy.deepcopy(self.old)
        self.new["manifest"].update(
            run_id="11111111-2222-4333-8444-555555555555",
            method_version=V3_VALIDATION_METHOD,
            authorization_reference="test-only-v3-approval",
            reauthorizes_run_id=CONTAINED_V2_RUN,
            predecessor_manifest_hash=self.old["manifest_hash"],
            canonical_identity_method_version="didim-autonomous-validation-v2",
            performance_policy="ADVISORY_ONLY",
        )

    def validate(self):
        self.new["manifest_hash"] = _sha256_json(self.new["manifest"])
        return validate_canary_manifest_contract(self.new, predecessor=self.old)

    def test_same_71_uuid_source_decisions_and_old_envelope_immutable(self):
        self.assertEqual(self.validate(), {"source_records": 142, "candidates": 71, "eligible": 71, "selected": 71})
        self.assertEqual(self.old, self.old_snapshot)
        self.assertEqual(self.old["manifest"]["candidates"], self.new["manifest"]["candidates"])
        self.assertEqual(self.old["manifest"]["source_records"], self.new["manifest"]["source_records"])
        self.assertNotEqual(self.old["manifest_hash"], self.new["manifest_hash"])

    def test_no_predecessor_is_fail_closed(self):
        self.new["manifest_hash"] = _sha256_json(self.new["manifest"])
        with self.assertRaisesRegex(ValueError, "sealed v2 predecessor"):
            validate_canary_manifest_contract(self.new)

    def test_no_replacement_uuid_source_mutation_or_decision_change(self):
        for mutate in (
            lambda m: m["candidates"][0].update(canonical_place_id="22222222-2222-4222-8222-222222222222"),
            lambda m: m["source_records"][0].update(normalized_name="changed"),
            lambda m: m["candidates"][0].update(decision="QUARANTINE"),
            lambda m: m["scope"].update(radius_meters=12000),
        ):
            original = copy.deepcopy(self.new)
            mutate(self.new["manifest"])
            with self.assertRaises(ValueError): self.validate()
            self.new = original

    def test_locked_envelope_fields(self):
        for field, value in (
            ("run_id", CONTAINED_V2_RUN), ("authorization_reference", self.old["manifest"]["authorization_reference"]),
            ("reauthorizes_run_id", "22222222-2222-4222-8222-222222222222"),
            ("predecessor_manifest_hash", "0" * 64), ("canary_stage", "STAGE_2"),
            ("canonical_identity_method_version", V3_VALIDATION_METHOD), ("performance_policy", "HARD_FAIL"),
        ):
            original = copy.deepcopy(self.new)
            self.new["manifest"][field] = value
            with self.assertRaises(ValueError): self.validate()
            self.new = original

    def test_exact_71_not_nominal_100(self):
        self.new["manifest"]["candidates"][0]["selected_for_stage"] = False
        with self.assertRaisesRegex(ValueError, "exactly 71"): self.validate()


if __name__ == "__main__":
    unittest.main()
