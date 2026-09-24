from __future__ import annotations

import math
import unittest

from phokarta_place_ingestion.normalization import (
    circle_bbox,
    haversine_meters,
    normalize_name,
    valid_coordinate,
)


class NameNormalizationTest(unittest.TestCase):
    def test_turkish_dotted_capital_i_matches_i(self):
        self.assertEqual(normalize_name("İNCİ"), "inci")

    def test_turkish_dotless_i_is_not_corrupted(self):
        self.assertEqual(normalize_name("IĞDIR"), "igdir")
        self.assertEqual(normalize_name("ığdır"), "ıgdır")
        self.assertNotEqual(normalize_name("I"), normalize_name("ı"))

    def test_unicode_and_punctuation_are_deterministic(self):
        self.assertEqual(normalize_name("  Café—Müze!!!  "), "cafe muze")

    def test_whitespace_collapses(self):
        self.assertEqual(normalize_name("Ada\t  Kafe\n"), "ada kafe")


class CoordinateTest(unittest.TestCase):
    def test_valid_coordinate_boundaries(self):
        self.assertTrue(valid_coordinate(-90.0, 180.0))
        self.assertTrue(valid_coordinate(90.0, -180.0))

    def test_invalid_coordinate_values(self):
        self.assertFalse(valid_coordinate(91.0, 0.0))
        self.assertFalse(valid_coordinate(0.0, 181.0))
        self.assertFalse(valid_coordinate(math.nan, 0.0))
        self.assertFalse(valid_coordinate(True, 1.0))

    def test_haversine_is_symmetric(self):
        left = haversine_meters(41.0, 29.0, 41.001, 29.001)
        right = haversine_meters(41.001, 29.001, 41.0, 29.0)
        self.assertAlmostEqual(left, right, places=8)
        self.assertGreater(left, 0)

    def test_circle_bbox_contains_center(self):
        west, south, east, north = circle_bbox(41.0, 29.0, 1000)
        self.assertLess(west, 29.0)
        self.assertGreater(east, 29.0)
        self.assertLess(south, 41.0)
        self.assertGreater(north, 41.0)


if __name__ == "__main__":
    unittest.main()
