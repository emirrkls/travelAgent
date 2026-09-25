from __future__ import annotations

import unittest

from phokarta_place_ingestion.canonicalization import (
    ExistingCanonicalPlace,
    build_canonicalization_plan,
    normalized_source_hash,
)
from phokarta_place_ingestion.models import BenchmarkCategory, NormalizedPlace
from phokarta_place_ingestion.production_categories import ProductionCategoryMapper


def source(
    provider: str,
    external_id: str,
    *,
    name: str | None = "İnci Kafe",
    latitude: float = 37.3751,
    longitude: float = 27.2678,
    categories: tuple[str, ...] = ("cafe",),
    category: BenchmarkCategory = BenchmarkCategory.CAFE,
    address: str | None = "Atatürk Bulvarı 1",
    phone: str | None = None,
    website: str | None = None,
    status: str | None = "OPEN",
) -> NormalizedPlace:
    return NormalizedPlace(
        provider=provider,
        external_id=external_id,
        source_release="fixture",
        name=name,
        latitude=latitude,
        longitude=longitude,
        address=address,
        locality="Didim",
        region="Aydın",
        country_code="TR",
        postal_code="09270",
        categories=categories,
        primary_category=categories[0] if categories else None,
        benchmark_category=category,
        phone=phone,
        website=website,
        operating_status=status,
        confidence_or_quality=0.9 if provider == "overture" else None,
        source_metadata={"area": "didim_core", "unresolved_flags": []},
    )


class ProductionCategoryTest(unittest.TestCase):
    def test_maps_directly_to_existing_place_category(self):
        mapper = ProductionCategoryMapper()
        self.assertEqual(mapper.map("overture", ["coffee_shop"]).category, "CAFE")
        self.assertEqual(mapper.map("fsq", ["4bf58dd8d48988d1fa931735"]).category, "HOTEL")

    def test_uncertain_category_stays_unmapped(self):
        self.assertIsNone(ProductionCategoryMapper().map("overture", ["unknown_thing"]).category)


class CanonicalizationTest(unittest.TestCase):
    def test_exact_external_ref_auto_links_without_overwriting_canonical(self):
        existing = ExistingCanonicalPlace(
            "20000000-0000-0000-0000-000000000001", "Trusted Community Name", "CAFE",
            37.3751, 27.2678, external_refs=(("OVERTURE", "o-1"),), graph_protected=True,
        )
        plan = build_canonicalization_plan([source("overture", "o-1")], [], [existing])
        self.assertEqual(len(plan.candidates), 1)
        self.assertEqual(plan.candidates[0].classification, "AUTO_LINK")
        self.assertEqual(plan.candidates[0].existing_place_name, "Trusted Community Name")
        self.assertEqual(plan.candidates[0].proposed_name, "İnci Kafe")

    def test_high_confidence_overture_and_fsq_form_one_create_group(self):
        plan = build_canonicalization_plan(
            [source("overture", "o-1")],
            [source("fsq", "f-1", latitude=37.37511, longitude=27.26781)],
            [],
        )
        self.assertEqual(len(plan.candidates), 1)
        candidate = plan.candidates[0]
        self.assertEqual(candidate.classification, "CREATE_NEW")
        self.assertEqual(candidate.cross_provider_classification, "HIGH_CONFIDENCE_MATCH")

    def test_ambiguous_multiple_existing_candidates_require_review(self):
        existing = [
            ExistingCanonicalPlace("p-1", "İnci Kafe", "CAFE", 37.3751, 27.2678,
                                   address="Atatürk Bulvarı 1", graph_protected=False),
            ExistingCanonicalPlace("p-2", "İnci Kafe", "CAFE", 37.37511, 27.26781,
                                   address="Atatürk Bulvarı 1", graph_protected=False),
        ]
        plan = build_canonicalization_plan([source("overture", "o-1")], [], existing)
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")
        self.assertIn("multiple_credible_existing_candidates", plan.candidates[0].matching_reasons)

    def test_same_name_nearby_provider_rows_remain_separate_and_reviewed(self):
        plan = build_canonicalization_plan([
            source("overture", "o-1"),
            source("overture", "o-2", latitude=37.37515, longitude=27.26785),
        ], [], [])
        self.assertEqual(len(plan.candidates), 2)
        self.assertTrue(all(row.classification == "REVIEW_REQUIRED" for row in plan.candidates))
        self.assertTrue(all("SAME_PROVIDER_DUPLICATE_CANDIDATE" in row.risk_flags for row in plan.candidates))

    def test_graph_protected_place_uses_stricter_auto_link_gate(self):
        existing = ExistingCanonicalPlace(
            "p-1", "İnci Kafe", "CAFE", 37.3751, 27.2678,
            address="Atatürk Bulvarı 1", graph_protected=True,
        )
        plan = build_canonicalization_plan([source("overture", "o-1")], [], [existing])
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")
        self.assertIn("graph_protected_requires_stricter_evidence", plan.candidates[0].matching_reasons)

    def test_provider_only_usable_record_creates_new(self):
        plan = build_canonicalization_plan([source("overture", "o-1")], [], [])
        self.assertEqual(plan.candidates[0].classification, "CREATE_NEW")

    def test_unmapped_required_category_requires_review(self):
        row = source(
            "overture", "o-1", categories=("unfamiliar_service",),
            category=BenchmarkCategory.UNMAPPED,
        )
        plan = build_canonicalization_plan([row], [], [])
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")
        self.assertIn("CATEGORY_UNMAPPED", plan.candidates[0].risk_flags)

    def test_unusable_record_is_rejected(self):
        plan = build_canonicalization_plan([source("overture", "o-1", name=None)], [], [])
        self.assertEqual(plan.candidates, ())
        self.assertEqual(plan.rejected[0]["classification"], "REJECT")
        self.assertIn("missing_name", plan.rejected[0]["reasons"])

    def test_overture_precedence_with_fsq_field_fallback(self):
        overture = source("overture", "o-1", name="Overture Name", phone=None, website=None)
        fsq = source(
            "fsq", "f-1", name="Overture Name", latitude=37.37511, longitude=27.26781,
            phone="+90 256 000 00 00", website="https://example.test",
        )
        candidate = build_canonicalization_plan([overture], [fsq], []).candidates[0]
        self.assertEqual(candidate.proposed_name, "Overture Name")
        self.assertEqual(candidate.phone, "+90 256 000 00 00")
        self.assertEqual(candidate.website, "https://example.test")
        self.assertEqual(candidate.latitude, overture.latitude)

    def test_source_hash_and_candidate_identity_are_deterministic(self):
        row = source("overture", "o-1")
        first = build_canonicalization_plan([row], [], []).candidates[0]
        second = build_canonicalization_plan([row], [], []).candidates[0]
        self.assertEqual(first.candidate_id, second.candidate_id)
        self.assertEqual(normalized_source_hash(row), normalized_source_hash(row))


if __name__ == "__main__":
    unittest.main()
