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

    def test_whole_token_rules_do_not_match_substrings(self):
        mapper = ProductionCategoryMapper()
        for provider, value in (
            ("overture", "barber"),
            ("overture", "hardware_home_and_garden_store"),
            ("overture", "public_plaza"),
            ("overture", "parking"),
            ("overture", "restaurant_equipment_and_supply"),
            ("overture", "market"),
            ("fsq", "Travel and Transportation > Parking"),
            ("fsq", "Retail > Food and Beverage Retail > Supermarket"),
            ("fsq", "Retail > Garden Center"),
            ("fsq", "50be8ee891d4fa8dcc7199a7"),
            ("fsq", "Retail > Flea Market"),
        ):
            with self.subTest(provider=provider, value=value):
                self.assertIsNone(mapper.map(provider, [value]).category)

    def test_provider_taxonomy_prefixes_are_bounded(self):
        mapper = ProductionCategoryMapper()
        self.assertEqual(
            mapper.map(
                "fsq",
                ["Dining and Drinking > Restaurant > Turkish Restaurant > Meyhane"],
            ).category,
            "RESTAURANT",
        )
        self.assertEqual(
            mapper.map("fsq", ["Landmarks and Outdoors > Park > National Park"]).category,
            "NATURE",
        )
        self.assertEqual(mapper.map("overture", ["beer_garden"]).category, "BAR")

    def test_all_applicable_production_rules_are_visible_to_conflict_checks(self):
        mapper = ProductionCategoryMapper()
        self.assertEqual(
            set(mapper.mapped_categories(
                "overture", ["nightlife_venue_beer_garden"]
            )),
            {"BAR", "NIGHTLIFE"},
        )

    def test_generic_parent_is_ignored_but_unknown_sibling_fails_closed(self):
        mapper = ProductionCategoryMapper()
        self.assertEqual(
            mapper.unmapped_non_ignored_values(
                "overture", ["food_and_drink", "restaurant"]
            ),
            (),
        )
        self.assertEqual(
            mapper.unmapped_non_ignored_values(
                "overture", ["restaurant", "unreviewed_provider_kind"]
            ),
            ("unreviewed_provider_kind",),
        )


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
            [source(
                "fsq", "f-1", latitude=37.37511, longitude=27.26781,
                categories=("4bf58dd8d48988d16d941735",),
            )],
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

    def test_one_unmapped_provider_category_keeps_group_in_review(self):
        plan = build_canonicalization_plan(
            [source("overture", "o-1", categories=("restaurant",))],
            [
                source(
                    "fsq", "f-1", categories=("unfamiliar_service",),
                    latitude=37.37511, longitude=27.26781,
                    category=BenchmarkCategory.UNMAPPED,
                )
            ],
            [],
        )
        self.assertEqual(plan.candidates[0].proposed_category, "RESTAURANT")
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")
        self.assertIn("CATEGORY_SOURCE_UNMAPPED", plan.candidates[0].risk_flags)

    def test_one_provider_with_multiple_mapped_categories_requires_review(self):
        row = source(
            "overture", "o-1", categories=("cafe", "restaurant"),
        )
        plan = build_canonicalization_plan([row], [], [])
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")
        self.assertIn("CATEGORY_CONFLICT", plan.candidates[0].risk_flags)

    def test_mapped_category_with_unknown_sibling_requires_review(self):
        row = source(
            "overture", "o-1",
            categories=("restaurant", "unreviewed_provider_kind"),
        )
        plan = build_canonicalization_plan([row], [], [])
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")
        self.assertIn("CATEGORY_SOURCE_UNMAPPED", plan.candidates[0].risk_flags)

    def test_mapped_category_with_explicit_generic_parent_remains_eligible(self):
        row = source(
            "overture", "o-1", categories=("food_and_drink", "restaurant"),
        )
        plan = build_canonicalization_plan([row], [], [])
        self.assertEqual(plan.candidates[0].classification, "CREATE_NEW")
        self.assertNotIn("CATEGORY_SOURCE_UNMAPPED", plan.candidates[0].risk_flags)

    def test_bona_fide_hotel_is_not_mislabeled_as_a_subvenue(self):
        row = source(
            "overture", "o-1", name="Ege Resort Hotel", categories=("hotel",),
        )
        plan = build_canonicalization_plan([row], [], [])
        self.assertNotIn("HOTEL_SUBVENUE_RISK", plan.candidates[0].risk_flags)

    def test_hotel_child_business_still_requires_review(self):
        row = source(
            "overture", "o-1", name="Ege Resort Lobby Bar", categories=("bar",),
        )
        plan = build_canonicalization_plan([row], [], [])
        self.assertIn("HOTEL_SUBVENUE_RISK", plan.candidates[0].risk_flags)
        self.assertEqual(plan.candidates[0].classification, "REVIEW_REQUIRED")

    def test_unusable_record_is_rejected(self):
        plan = build_canonicalization_plan([source("overture", "o-1", name=None)], [], [])
        self.assertEqual(plan.candidates, ())
        self.assertEqual(plan.rejected[0]["classification"], "REJECT")
        self.assertIn("missing_name", plan.rejected[0]["reasons"])

    def test_overture_precedence_with_fsq_field_fallback(self):
        overture = source("overture", "o-1", name="Overture Name", phone=None, website=None)
        fsq = source(
            "fsq", "f-1", name="Overture Name", latitude=37.37511, longitude=27.26781,
            phone="+90 256 000 00 00", website="https://overturename.com",
        )
        candidate = build_canonicalization_plan([overture], [fsq], []).candidates[0]
        self.assertEqual(candidate.proposed_name, "Overture Name")
        self.assertEqual(candidate.phone, "+90 256 000 00 00")
        self.assertEqual(candidate.website, "https://overturename.com")
        self.assertEqual(candidate.latitude, overture.latitude)

    def test_social_handle_like_website_is_excluded_but_preserved_in_provenance(self):
        raw_website = "http://@vetturcafebar"
        plan = build_canonicalization_plan([
            source(
                "overture", "o-1", name="Vettur Cafe and Bar",
                categories=("bar",), website=raw_website,
            )
        ], [], [])
        candidate = plan.candidates[0]
        self.assertIsNone(candidate.website)
        self.assertIn("WEBSITE_INVALID", candidate.risk_flags)
        self.assertEqual(candidate.classification, "REVIEW_REQUIRED")
        self.assertEqual(plan.source_records[0]["website"], raw_website)
        self.assertFalse(plan.source_records[0]["canonical_website_eligible"])

    def test_undelegated_website_suffix_is_excluded_but_preserved_in_provenance(self):
        raw_website = "http://Lolo.kunefe/"
        plan = build_canonicalization_plan([
            source(
                "overture", "o-1", name="Lolo Künefe",
                categories=("cafe",), website=raw_website,
            )
        ], [], [])
        candidate = plan.candidates[0]
        self.assertIsNone(candidate.website)
        self.assertIn("WEBSITE_INVALID", candidate.risk_flags)
        self.assertEqual(plan.source_records[0]["website"], raw_website)
        self.assertEqual(
            plan.source_records[0]["website_validation_reason"],
            "invalid_public_suffix",
        )

    def test_unrelated_valid_website_is_excluded_but_preserved_in_provenance(self):
        raw_website = "http://www.altinkumrentacar.com"
        plan = build_canonicalization_plan(
            [
                source(
                    "overture", "o-1", name="Didim Denizlililer Lokali",
                    categories=("restaurant",), website=raw_website,
                )
            ],
            [
                source(
                    "fsq", "f-1", name="Didim Denizlililer Lokali",
                    categories=("4bf58dd8d48988d16d941735",), website=raw_website,
                    latitude=37.37511, longitude=27.26781,
                )
            ],
            [],
        )
        candidate = plan.candidates[0]
        self.assertIsNone(candidate.website)
        self.assertIn("WEBSITE_IDENTITY_UNVERIFIED", candidate.risk_flags)
        self.assertEqual(candidate.classification, "REVIEW_REQUIRED")
        self.assertTrue(all(row["website"] == raw_website for row in plan.source_records))
        self.assertTrue(all(
            row["website_validation_reason"] == "identity_unverified"
            for row in plan.source_records
        ))

    def test_location_token_alone_cannot_validate_unrelated_website(self):
        for place_name, raw_website in (
            ("Altınkum Cafe", "http://www.altinkumrentacar.com"),
            ("Akbük Cafe", "https://akbukemlak.com"),
            ("Mavişehir Restaurant", "https://mavisehirrentacar.com"),
        ):
            with self.subTest(place_name=place_name):
                plan = build_canonicalization_plan([
                    source(
                        "overture", "o-1", name=place_name,
                        categories=("cafe",), website=raw_website,
                    )
                ], [], [])
                candidate = plan.candidates[0]
                self.assertIsNone(candidate.website)
                self.assertIn("WEBSITE_IDENTITY_UNVERIFIED", candidate.risk_flags)
                self.assertEqual(plan.source_records[0]["website"], raw_website)


    def test_source_hash_and_candidate_identity_are_deterministic(self):
        row = source("overture", "o-1")
        first = build_canonicalization_plan([row], [], []).candidates[0]
        second = build_canonicalization_plan([row], [], []).candidates[0]
        self.assertEqual(first.candidate_id, second.candidate_id)
        self.assertEqual(normalized_source_hash(row), normalized_source_hash(row))


if __name__ == "__main__":
    unittest.main()
