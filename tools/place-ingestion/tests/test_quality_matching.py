from __future__ import annotations

import unittest

from phokarta_place_ingestion.matching import (
    LinkSignals,
    classify_link,
    cross_provider_matches,
    duplicate_candidates,
    name_similarity,
)
from phokarta_place_ingestion.benchmark import known_place_recall
from phokarta_place_ingestion.models import BenchmarkCategory, GoldPlace, NormalizedPlace
from phokarta_place_ingestion.quality import assess_quality


def place(
    provider: str,
    external_id: str,
    name: str | None = "İnci Kafe",
    lat: float | None = 41.0,
    lon: float | None = 29.0,
    category: BenchmarkCategory = BenchmarkCategory.CAFE,
    status: str | None = "OPEN",
    quality=0.9,
    flags=(),
) -> NormalizedPlace:
    return NormalizedPlace(
        provider=provider,
        external_id=external_id,
        source_release="fixture",
        name=name,
        latitude=lat,
        longitude=lon,
        address="Moda Cad. 1",
        locality="Kadıköy",
        region="İstanbul",
        country_code="TR",
        postal_code="34710",
        categories=("cafe",),
        primary_category="cafe",
        benchmark_category=category,
        phone=None,
        website=None,
        operating_status=status,
        confidence_or_quality=quality,
        source_metadata={"area": "kadikoy", "unresolved_flags": list(flags)},
    )


class QualityTest(unittest.TestCase):
    def test_good_place_is_usable(self):
        self.assertTrue(assess_quality(place("overture", "a")).usable)

    def test_missing_name_is_not_usable(self):
        decision = assess_quality(place("overture", "a", name=None))
        self.assertFalse(decision.usable)
        self.assertIn("missing_name", decision.reasons)

    def test_overture_severe_low_confidence_is_junk(self):
        decision = assess_quality(place("overture", "a", quality=0.2))
        self.assertFalse(decision.usable)
        self.assertTrue(decision.junk)

    def test_fsq_severe_unresolved_flag_is_junk(self):
        decision = assess_quality(place("fsq", "a", quality=None, flags=("duplicate",)))
        self.assertFalse(decision.usable)
        self.assertTrue(decision.junk)

    def test_explicit_closed_is_filtered(self):
        self.assertFalse(assess_quality(place("fsq", "a", status="CLOSED", quality=None)).usable)


class MatchingTest(unittest.TestCase):
    def test_turkish_name_similarity(self):
        self.assertEqual(name_similarity("İNCİ KAFE", "İnci Kafe"), 1.0)

    def test_duplicate_candidate_generation(self):
        rows = [place("overture", "a"), place("overture", "b", lat=41.00005, lon=29.00005)]
        candidates = duplicate_candidates("overture", "kadikoy", rows)
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].classification, "HIGH_CONFIDENCE_DUPLICATE")

    def test_distant_rows_are_not_duplicate_candidates(self):
        rows = [place("overture", "a"), place("overture", "b", lat=41.01, lon=29.01)]
        self.assertEqual(duplicate_candidates("overture", "kadikoy", rows), [])

    def test_cross_provider_high_confidence(self):
        rows = cross_provider_matches(
            "kadikoy", [place("overture", "o")], [place("fsq", "f", lat=41.00003, lon=29.00003, quality=None)]
        )
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].classification, "HIGH_CONFIDENCE_MATCH")

    def test_cross_provider_only_classifications(self):
        rows = cross_provider_matches(
            "kadikoy", [place("overture", "o")], [place("fsq", "f", name="Başka", lat=41.01, lon=29.01, quality=None)]
        )
        self.assertEqual({row.classification for row in rows}, {"OVERTURE_ONLY", "FSQ_ONLY"})

    def test_cross_provider_grid_keeps_nearby_adjacent_cells(self):
        rows = cross_provider_matches(
            "kadikoy",
            [place("overture", "o", lat=41.0, lon=29.00034)],
            [place("fsq", "f", lat=41.0, lon=29.00080, quality=None)],
        )
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].classification, "POSSIBLE_MATCH")

    def test_exact_external_ref_auto_links(self):
        decision = classify_link(LinkSignals(exact_external_ref=True))
        self.assertEqual(decision.classification, "AUTO_LINK")

    def test_strong_corroboration_auto_links(self):
        decision = classify_link(LinkSignals(
            distance_meters=8, name_similarity=1.0, phone_exact=True, category_compatible=True
        ))
        self.assertEqual(decision.classification, "AUTO_LINK")

    def test_uncertain_match_requires_review(self):
        decision = classify_link(LinkSignals(
            distance_meters=25, name_similarity=0.90, category_compatible=True
        ))
        self.assertEqual(decision.classification, "REVIEW_REQUIRED")

    def test_weak_match_creates_new(self):
        decision = classify_link(LinkSignals(distance_meters=70, name_similarity=0.83))
        self.assertEqual(decision.classification, "CREATE_NEW")

    def test_known_place_recall_rejects_weak_false_name_match(self):
        gold = [GoldPlace(
            "kadikoy", "Hıdırlık Kulesi", 41.0, 29.0,
            BenchmarkCategory.HISTORIC_PLACE, "https://example.test/source",
        )]
        candidates = [place(
            "overture", "taxi", name="Hıdırlık Taksi", lat=41.00001, lon=29.00001,
            category=BenchmarkCategory.UNMAPPED,
        )]
        summary, detail = known_place_recall("overture", gold, candidates)
        self.assertEqual(summary[0]["matched"], 0)
        self.assertEqual(detail[0]["status"], "MISSING")


if __name__ == "__main__":
    unittest.main()
