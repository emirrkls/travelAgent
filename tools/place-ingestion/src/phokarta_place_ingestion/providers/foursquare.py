from __future__ import annotations

import json
import os
import re
import time
from datetime import datetime, timezone
from typing import Any, Iterable

from ..categories import CategoryMapper
from ..config import load_category_mappings
from ..environment import fsq_credential_available, load_local_environment
from ..models import FetchResult, FetchStats, LicenseMetadata, NormalizedPlace, PilotArea, ReleaseDescriptor
from ..normalization import circle_bbox, first_nonblank, haversine_meters
from .base import ExternalPlaceProvider, ProviderAccessError, ProviderSchemaError


DEFAULT_ENDPOINT = "https://catalog.h3-hub.foursquare.com/iceberg"
DEFAULT_WAREHOUSE = "places"
DEFAULT_TABLE = "places.datasets.places_os"
_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){2}$")


def _safe_error_category(exc: Exception) -> str:
    message = str(exc).casefold()
    if any(value in message for value in ("401", "unauthorized", "authentication", "invalid token")):
        return "UNAUTHORIZED"
    if any(value in message for value in ("403", "forbidden")):
        return "FORBIDDEN"
    if any(value in message for value in ("table not found", "dataset", "permission denied")):
        return "DATASET_NOT_GRANTED"
    if any(value in message for value in ("network", "dns", "socket", "timeout", "connection refused")):
        return "NETWORK_FAILURE"
    return "CATALOG_UNAVAILABLE"


def _safe_access_error(operation: str, exc: Exception) -> ProviderAccessError:
    return ProviderAccessError(f"FSQ {operation} failed: {_safe_error_category(exc)}")


def _sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            if isinstance(parsed, list):
                return [str(item).strip() for item in parsed if str(item).strip()]
        except json.JSONDecodeError:
            return [value.strip()] if value.strip() else []
    return []


class FoursquareOsPlaceProvider(ExternalPlaceProvider):
    key = "fsq"

    def __init__(self, category_mapper: CategoryMapper | None = None):
        load_local_environment()
        self.category_mapper = category_mapper or CategoryMapper(load_category_mappings())
        self.endpoint = os.environ.get("FSQ_ICEBERG_CATALOG_URI", DEFAULT_ENDPOINT)
        self.warehouse = os.environ.get("FSQ_ICEBERG_WAREHOUSE", DEFAULT_WAREHOUSE)
        self.table = os.environ.get("FSQ_PLACES_TABLE", DEFAULT_TABLE)
        if not _IDENTIFIER.fullmatch(self.table):
            raise ProviderAccessError("FSQ_PLACES_TABLE must be a catalog.schema.table identifier")

    @staticmethod
    def credential_available() -> bool:
        return fsq_credential_available()

    def _connect(self):
        token = os.environ.get("FSQ_PLACES_TOKEN", "").strip()
        if not token:
            raise ProviderAccessError(
                "FSQ DATA ACCESS REQUIRED: set FSQ_PLACES_TOKEN to a Places Portal access token"
            )
        try:
            import duckdb  # type: ignore
        except ImportError as exc:
            raise ProviderAccessError("duckdb 1.4.0 is required for FSQ Iceberg access") from exc
        connection = duckdb.connect(":memory:")
        try:
            connection.execute("INSTALL httpfs")
            connection.execute("LOAD httpfs")
            connection.execute("INSTALL iceberg")
            connection.execute("LOAD iceberg")
            # The token is interpolated only into the in-memory secret statement. The
            # statement is never returned, logged, or written to an artifact.
            connection.execute(
                f"CREATE SECRET fsq_places_secret (TYPE ICEBERG, TOKEN {_sql_string(token)})"
            )
            connection.execute(
                "ATTACH " + _sql_string(self.warehouse) + " AS places "
                "(TYPE iceberg, SECRET fsq_places_secret, ENDPOINT "
                + _sql_string(self.endpoint) + ")"
            )
            return connection
        except Exception as exc:
            connection.close()
            raise _safe_access_error("connection", exc) from None

    def describe_release(self, requested_release: str | None = None) -> ReleaseDescriptor:
        connection = self._connect()
        try:
            snapshot_id: str | None = None
            resolved = requested_release or "current-catalog-snapshot"
            try:
                row = connection.execute(
                    "SELECT snapshot_id, committed_at FROM iceberg_snapshots(?) "
                    "ORDER BY committed_at DESC LIMIT 1",
                    [self.table],
                ).fetchone()
                if row:
                    snapshot_id = str(row[0])
                    resolved = str(row[1])
            except Exception as exc:
                # Some REST catalogs do not expose metadata-table functions. The
                # benchmark remains blocked from claiming reproducibility unless a
                # snapshot identity can be recorded.
                if not requested_release:
                    raise _safe_access_error("snapshot discovery", exc) from None
            return ReleaseDescriptor(
                self.key, requested_release, resolved, "FSQ_OS_CURRENT", snapshot_id=snapshot_id
            )
        finally:
            connection.close()

    def fetch_scope(
        self, areas: Iterable[PilotArea], release: ReleaseDescriptor
    ) -> FetchResult:
        connection = self._connect()
        try:
            records: list[dict[str, Any]] = []
            stats: list[FetchStats] = []
            for area in areas:
                west, south, east, north = circle_bbox(
                    area.center_latitude, area.center_longitude, area.radius_meters
                )
                started = time.perf_counter()
                cursor = connection.execute(
                    f"""
                    SELECT fsq_place_id, name, latitude, longitude, address, locality,
                           region, postcode, country, date_created, date_refreshed,
                           date_closed, tel, website, CAST(fsq_category_ids AS JSON) AS fsq_category_ids,
                           CAST(fsq_category_labels AS JSON) AS fsq_category_labels,
                           CAST(unresolved_flags AS JSON) AS unresolved_flags
                    FROM {self.table}
                    WHERE longitude BETWEEN ? AND ? AND latitude BETWEEN ? AND ?
                    """,
                    [west, east, south, north],
                )
                columns = [column[0] for column in cursor.description]
                scoped: list[dict[str, Any]] = []
                for row in cursor.fetchall():
                    item = dict(zip(columns, row, strict=True))
                    if haversine_meters(
                        area.center_latitude,
                        area.center_longitude,
                        float(item["latitude"]),
                        float(item["longitude"]),
                    ) <= area.radius_meters:
                        item["_area"] = area.key
                        scoped.append(item)
                records.extend(scoped)
                stats.append(
                    FetchStats(
                        provider=self.key,
                        area=area.key,
                        query_seconds=round(time.perf_counter() - started, 6),
                        records_returned=len(scoped),
                        query_method="Places Portal Iceberg bbox predicate pushdown + exact Haversine circle",
                    )
                )
            return FetchResult(records, stats)
        except ProviderAccessError:
            raise
        except Exception as exc:
            raise _safe_access_error("scoped query", exc) from None
        finally:
            connection.close()

    def normalize(
        self, raw: dict[str, Any], release: ReleaseDescriptor
    ) -> NormalizedPlace:
        required = {"fsq_place_id", "name", "latitude", "longitude", "fsq_category_ids"}
        missing = sorted(required.difference(raw))
        if missing:
            raise ProviderSchemaError(f"FSQ OS row is missing columns: {', '.join(missing)}")
        external_id = str(raw.get("fsq_place_id") or "").strip()
        if not external_id:
            raise ProviderSchemaError("FSQ OS row is missing fsq_place_id")
        category_ids = _list(raw.get("fsq_category_ids"))
        category_labels = _list(raw.get("fsq_category_labels"))
        categories = [*category_ids, *category_labels]
        flags = _list(raw.get("unresolved_flags"))
        status = "CLOSED" if raw.get("date_closed") else None
        return NormalizedPlace(
            provider=self.key,
            external_id=external_id,
            source_release=release.resolved_release,
            name=first_nonblank([raw.get("name")]),
            latitude=float(raw["latitude"]) if raw.get("latitude") is not None else None,
            longitude=float(raw["longitude"]) if raw.get("longitude") is not None else None,
            address=first_nonblank([raw.get("address")]),
            locality=first_nonblank([raw.get("locality")]),
            region=first_nonblank([raw.get("region")]),
            country_code=first_nonblank([raw.get("country")]),
            postal_code=first_nonblank([raw.get("postcode")]),
            categories=tuple(categories),
            primary_category=category_labels[0] if category_labels else (category_ids[0] if category_ids else None),
            benchmark_category=self.category_mapper.map(self.key, categories),
            phone=first_nonblank([raw.get("tel")]),
            website=first_nonblank([raw.get("website")]),
            operating_status=status,
            confidence_or_quality="UNRESOLVED_FLAGS" if flags else None,
            created_date=str(raw["date_created"]) if raw.get("date_created") else None,
            refreshed_date=str(raw["date_refreshed"]) if raw.get("date_refreshed") else None,
            source_metadata={"area": raw.get("_area"), "unresolved_flags": flags},
        )

    def license_metadata(self, release: ReleaseDescriptor) -> LicenseMetadata:
        return LicenseMetadata(
            provider=self.key,
            dataset_name="Foursquare Open Source Places",
            license_identifier="Apache-2.0",
            source_url="https://docs.foursquare.com/data-products/docs/fsq-places-open-source",
            attribution_requirements="Retain the Apache-2.0 license and applicable notices.",
            release=release.resolved_release,
            retrieval_timestamp=datetime.now(timezone.utc).isoformat(),
            notes=(
                "This describes the Open Source Places dataset, not Pro/Premium attributes.",
                "Human legal review is required before production persistence or attribution UI decisions.",
            ),
        )
