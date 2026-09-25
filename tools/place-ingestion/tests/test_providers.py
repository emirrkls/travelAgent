from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from phokarta_place_ingestion.config import DEFAULT_FIXTURE_DIR
from phokarta_place_ingestion.models import BenchmarkCategory, ReleaseDescriptor
from phokarta_place_ingestion.providers import (
    FoursquareOsPlaceProvider,
    OverturePlaceProvider,
    ProviderAccessError,
    ProviderSchemaError,
)
from phokarta_place_ingestion.providers.foursquare import _safe_access_error


def fixture(name: str):
    return json.loads((DEFAULT_FIXTURE_DIR / name).read_text(encoding="utf-8"))


class OvertureAdapterTest(unittest.TestCase):
    def setUp(self):
        self.provider = OverturePlaceProvider()
        self.release = ReleaseDescriptor("overture", "2026-09-23.0", "2026-09-23.0", "v2.0.0")

    def test_normalizes_schema_v2_taxonomy(self):
        place = self.provider.normalize(fixture("overture_v2_place.json"), self.release)
        self.assertEqual(place.external_id, "08f2a107-7b19-4d3c-a386-example000001")
        self.assertEqual(place.name, "İnci Kafe")
        self.assertEqual(place.primary_category, "coffee_shop")
        self.assertEqual(place.benchmark_category, BenchmarkCategory.CAFE)
        self.assertIn("cafe", place.categories)
        self.assertEqual(place.name_variants, ("Inci Cafe", "İnci Kahvesi"))

    def test_normalizes_address_contacts_and_provenance(self):
        place = self.provider.normalize(fixture("overture_v2_place.json"), self.release)
        self.assertEqual(place.locality, "Kadıköy")
        self.assertEqual(place.country_code, "TR")
        self.assertEqual(place.website, "https://example.test")
        self.assertEqual(place.refreshed_date, "2026-09-01T00:00:00Z")
        self.assertEqual(place.source_metadata["sources"][0]["dataset"], "example")

    def test_freshness_ignores_confidence_pipeline_timestamp(self):
        row = fixture("overture_v2_place.json")
        row["sources"].append({
            "provider": "overture",
            "property": "/properties/confidence",
            "update_time": "2026-09-20T00:00:00Z",
        })
        place = self.provider.normalize(row, self.release)
        self.assertEqual(place.refreshed_date, "2026-09-01T00:00:00Z")

    def test_rejects_legacy_categories_fixture(self):
        with self.assertRaisesRegex(ProviderSchemaError, "legacy Overture categories"):
            self.provider.normalize(fixture("overture_legacy_place.json"), self.release)

    def test_rejects_missing_v2_columns(self):
        row = fixture("overture_v2_place.json")
        row.pop("taxonomy")
        with self.assertRaisesRegex(ProviderSchemaError, "taxonomy and basic_category"):
            self.provider.normalize(row, self.release)

    def test_pinned_release_records_schema(self):
        release = self.provider.describe_release("2026-09-23.0")
        self.assertEqual(release.resolved_release, "2026-09-23.0")
        self.assertEqual(release.schema_version, "v2.0.0")

    def test_unknown_pinned_release_fails_closed(self):
        with self.assertRaisesRegex(ProviderSchemaError, "schema version is unknown"):
            self.provider.describe_release("2099-01-01.0")

    def test_invalid_release_identifier_fails_before_path_construction(self):
        with self.assertRaisesRegex(ProviderSchemaError, "invalid Overture release identifier"):
            self.provider.describe_release("../../unexpected")

    def test_license_is_source_aware(self):
        metadata = self.provider.license_metadata(self.release)
        self.assertIn("source-dependent", metadata.license_identifier)
        self.assertTrue(any("universal" in note for note in metadata.notes))


class FoursquareAdapterTest(unittest.TestCase):
    def setUp(self):
        self.provider = FoursquareOsPlaceProvider()
        self.release = ReleaseDescriptor("fsq", None, "2026-09-snapshot", "FSQ_OS_CURRENT", "123")

    def test_normalizes_current_os_schema(self):
        place = self.provider.normalize(fixture("fsq_os_place.json"), self.release)
        self.assertEqual(place.external_id, "4examplefsqid")
        self.assertEqual(place.benchmark_category, BenchmarkCategory.CAFE)
        self.assertIsNone(place.operating_status)
        self.assertEqual(place.refreshed_date, "2026-08-20")

    def test_closed_date_maps_to_closed(self):
        row = fixture("fsq_os_place.json")
        row["date_closed"] = "2026-01-02"
        self.assertEqual(self.provider.normalize(row, self.release).operating_status, "CLOSED")

    def test_missing_required_schema_fails(self):
        row = fixture("fsq_os_place.json")
        row.pop("fsq_category_ids")
        with self.assertRaisesRegex(ProviderSchemaError, "missing columns"):
            self.provider.normalize(row, self.release)

    def test_credential_gate_fails_without_token(self):
        with patch(
            "phokarta_place_ingestion.providers.foursquare.load_local_environment",
            return_value={},
        ), patch(
            "phokarta_place_ingestion.providers.foursquare.fsq_credential_available",
            return_value=False,
        ):
            with patch.dict(os.environ, {}, clear=True):
                provider = FoursquareOsPlaceProvider()
                self.assertFalse(provider.credential_available())
                with self.assertRaisesRegex(ProviderAccessError, "FSQ DATA ACCESS REQUIRED"):
                    provider.describe_release()

    def test_license_is_apache(self):
        self.assertEqual(self.provider.license_metadata(self.release).license_identifier, "Apache-2.0")

    def test_discovers_attached_catalog_snapshot_with_current_duckdb_syntax(self):
        class FakeConnection:
            def __init__(self):
                self.sql = ""

            def execute(self, sql):
                self.sql = sql
                return self

            def fetchone(self):
                return (987654321, "2026-09-24 12:34:56+00:00")

            def close(self):
                pass

        connection = FakeConnection()
        with patch.object(self.provider, "_connect", return_value=connection):
            release = self.provider.describe_release()

        self.assertIn("iceberg_snapshots(places.datasets.places_os)", connection.sql)
        self.assertIn("timestamp_ms", connection.sql)
        self.assertNotIn("?", connection.sql)
        self.assertEqual(release.snapshot_id, "987654321")
        self.assertEqual(release.resolved_release, "2026-09-24 12:34:56+00:00")

    def test_access_errors_are_categorized_without_secret_context(self):
        marker = "super-secret-token-value"
        error = _safe_access_error(
            "connection",
            RuntimeError(f"401 Authorization: Bearer {marker}; SQL CREATE SECRET"),
        )
        self.assertEqual(str(error), "FSQ connection failed: UNAUTHORIZED")
        self.assertNotIn(marker, str(error))
        self.assertNotIn("Bearer", str(error))
        self.assertNotIn("SQL", str(error))


if __name__ == "__main__":
    unittest.main()
