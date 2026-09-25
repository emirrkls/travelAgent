from __future__ import annotations

import unittest

from phokarta_place_ingestion.benchmark_lock import verify_benchmark_lock
from phokarta_place_ingestion.categories import CategoryMapper
from phokarta_place_ingestion.config import (
    DEFAULT_CONFIG_DIR,
    load_category_mappings,
    load_gold_places,
    load_pilot_areas,
    load_provider_registry,
    read_json,
)
from phokarta_place_ingestion.models import BenchmarkCategory


class ConfigurationTest(unittest.TestCase):
    def test_benchmark_lock_matches_current_inputs(self):
        lock = verify_benchmark_lock()
        self.assertEqual(lock["benchmark_method_version"], "1.0.2")
        self.assertEqual(lock["sampling_seed"], 5501)

    def test_configuration_schema_is_versioned(self):
        schema = read_json(DEFAULT_CONFIG_DIR / "config.schema.json")
        self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
        self.assertEqual(
            set(schema["$defs"]),
            {"pilotAreas", "providerRegistry", "categoryMappings", "providerMapping"},
        )

    def test_six_equal_scope_definitions_exist(self):
        areas = load_pilot_areas()
        self.assertEqual(len(areas), 6)
        self.assertTrue(all(area.radius_meters > 0 for area in areas))

    def test_gold_set_has_ten_per_area(self):
        gold = load_gold_places()
        for area in load_pilot_areas():
            self.assertEqual(sum(item.area == area.key for item in gold), 10)

    def test_provider_registry_is_complete(self):
        providers = load_provider_registry()["providers"]
        self.assertEqual({item["key"] for item in providers}, {"overture", "fsq"})
        self.assertTrue(all(item["license_identifier"] for item in providers))
        self.assertTrue(all(item["documented_release"] for item in providers))
        self.assertTrue(all(item["documented_schema"] for item in providers))

    def test_category_mapping_keeps_uncertain_unmapped(self):
        mapper = CategoryMapper(load_category_mappings())
        self.assertEqual(mapper.map("overture", ["unfamiliar_service"]), BenchmarkCategory.UNMAPPED)

    def test_category_mapping_uses_neutral_bucket(self):
        mapper = CategoryMapper(load_category_mappings())
        self.assertEqual(mapper.map("overture", ["italian_restaurant"]), BenchmarkCategory.RESTAURANT)
        self.assertEqual(mapper.map("fsq", ["Café"]), BenchmarkCategory.CAFE)


if __name__ == "__main__":
    unittest.main()
