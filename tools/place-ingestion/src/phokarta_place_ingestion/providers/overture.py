from __future__ import annotations

import json
import re
import time
import urllib.request
from datetime import datetime, timezone
from typing import Any, Iterable

from ..categories import CategoryMapper
from ..config import load_category_mappings
from ..models import (
    FetchResult,
    FetchStats,
    LicenseMetadata,
    NormalizedPlace,
    PilotArea,
    ReleaseDescriptor,
)
from ..normalization import circle_bbox, first_nonblank, haversine_meters
from .base import ExternalPlaceProvider, ProviderAccessError, ProviderSchemaError


STAC_URL = "https://stac.overturemaps.org/catalog.json"
S3_TEMPLATE = "s3://overturemaps-us-west-2/release/{release}/theme=places/type=place/*"
KNOWN_SCHEMA_BY_RELEASE = {"2026-09-23.0": "v2.0.0"}
RELEASE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}\.\d+$")


def _json_value(value: Any, fallback: Any) -> Any:
    if value is None:
        return fallback
    if isinstance(value, (dict, list)):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return fallback
    return fallback


def _string_list(value: Any) -> list[str]:
    parsed = _json_value(value, value)
    if parsed is None:
        return []
    if isinstance(parsed, str):
        return [parsed] if parsed.strip() else []
    if isinstance(parsed, list):
        result: list[str] = []
        for item in parsed:
            if isinstance(item, str) and item.strip():
                result.append(item.strip())
            elif isinstance(item, dict):
                candidate = first_nonblank(item.values())
                if candidate:
                    result.append(candidate)
        return result
    return []


def _name_variants(names: Any) -> tuple[str, ...]:
    if not isinstance(names, dict):
        return ()
    primary = str(names.get("primary") or "").strip()
    values: list[str] = []
    common = names.get("common")
    if isinstance(common, dict):
        values.extend(str(value).strip() for value in common.values() if str(value).strip())
    rules = names.get("rules")
    if isinstance(rules, list):
        values.extend(
            str(rule.get("value")).strip()
            for rule in rules
            if isinstance(rule, dict) and str(rule.get("value") or "").strip()
        )
    return tuple(dict.fromkeys(value for value in values if value != primary))


class OverturePlaceProvider(ExternalPlaceProvider):
    key = "overture"

    def __init__(self, category_mapper: CategoryMapper | None = None):
        self.category_mapper = category_mapper or CategoryMapper(load_category_mappings())

    def describe_release(self, requested_release: str | None = None) -> ReleaseDescriptor:
        requested = requested_release or "latest"
        schema_version: str | None = None
        if requested == "latest":
            try:
                with urllib.request.urlopen(STAC_URL, timeout=30) as response:
                    root = json.load(response)
                resolved = str(root["latest"])
                release_href = next(
                    (link["href"] for link in root.get("links", [])
                     if link.get("rel") == "child" and resolved in link.get("href", "")),
                    f"{resolved}/catalog.json",
                )
                release_url = urllib.request.urljoin(STAC_URL, release_href)
                with urllib.request.urlopen(release_url, timeout=30) as response:
                    release_catalog = json.load(response)
                schema_version = release_catalog.get("schema:version")
            except (OSError, KeyError, ValueError, json.JSONDecodeError) as exc:
                raise ProviderAccessError(f"unable to resolve Overture STAC release: {exc}") from exc
        else:
            resolved = requested
        if not RELEASE_PATTERN.fullmatch(resolved):
            raise ProviderSchemaError(
                f"invalid Overture release identifier {resolved!r}; expected YYYY-MM-DD.N"
            )
        schema_version = schema_version or KNOWN_SCHEMA_BY_RELEASE.get(resolved)
        if not schema_version:
            raise ProviderSchemaError(
                f"schema version is unknown for Overture release {resolved}; "
                "pin a verified release or update the release metadata mapping"
            )
        if schema_version.lstrip("v").split(".", 1)[0] != "2":
            raise ProviderSchemaError(
                f"Overture schema {schema_version} is incompatible; schema v2 is required"
            )
        return ReleaseDescriptor(self.key, requested, resolved, schema_version)

    def fetch_scope(
        self, areas: Iterable[PilotArea], release: ReleaseDescriptor
    ) -> FetchResult:
        try:
            import duckdb  # type: ignore
        except ImportError as exc:
            raise ProviderAccessError("duckdb 1.4.0 is required for live Overture access") from exc

        connection = duckdb.connect(":memory:")
        try:
            connection.execute("INSTALL httpfs")
            connection.execute("LOAD httpfs")
            connection.execute("SET s3_region='us-west-2'")
            source = S3_TEMPLATE.format(release=release.resolved_release)
            records: list[dict[str, Any]] = []
            stats: list[FetchStats] = []
            for area in areas:
                west, south, east, north = circle_bbox(
                    area.center_latitude, area.center_longitude, area.radius_meters
                )
                started = time.perf_counter()
                cursor = connection.execute(
                    """
                    SELECT id, version, CAST(names AS JSON) AS names,
                           basic_category, CAST(taxonomy AS JSON) AS taxonomy,
                           confidence, operating_status,
                           CAST(websites AS JSON) AS websites,
                           CAST(phones AS JSON) AS phones,
                           CAST(addresses AS JSON) AS addresses,
                           CAST(sources AS JSON) AS sources,
                           bbox.xmin AS longitude, bbox.ymin AS latitude
                    FROM read_parquet(?, filename=true, hive_partitioning=true)
                    WHERE bbox.xmin BETWEEN ? AND ? AND bbox.ymin BETWEEN ? AND ?
                    """,
                    [source, west, east, south, north],
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
                        query_method="DuckDB GeoParquet bbox predicate pushdown + exact Haversine circle",
                    )
                )
            return FetchResult(records, stats)
        except Exception as exc:
            if isinstance(exc, (ProviderAccessError, ProviderSchemaError)):
                raise
            raise ProviderAccessError(f"Overture scoped query failed: {exc}") from exc
        finally:
            connection.close()

    def normalize(
        self, raw: dict[str, Any], release: ReleaseDescriptor
    ) -> NormalizedPlace:
        if "categories" in raw:
            raise ProviderSchemaError(
                "legacy Overture categories field is not accepted by the schema-v2 adapter"
            )
        if "taxonomy" not in raw or "basic_category" not in raw:
            raise ProviderSchemaError(
                "Overture v2 row must expose taxonomy and basic_category columns"
            )
        external_id = str(raw.get("id") or "").strip()
        if not external_id:
            raise ProviderSchemaError("Overture v2 row is missing id")

        names = _json_value(raw.get("names"), {})
        name = names.get("primary") if isinstance(names, dict) else None
        taxonomy = _json_value(raw.get("taxonomy"), {})
        taxonomy = taxonomy if isinstance(taxonomy, dict) else {}
        primary = taxonomy.get("primary")
        categories: list[str] = []
        for value in [raw.get("basic_category"), primary]:
            if value and str(value) not in categories:
                categories.append(str(value))
        for key in ("hierarchy", "alternates"):
            for value in taxonomy.get(key) or []:
                candidate = value.get("id") if isinstance(value, dict) else value
                if candidate and str(candidate) not in categories:
                    categories.append(str(candidate))

        addresses = _json_value(raw.get("addresses"), [])
        address = next((row for row in addresses if isinstance(row, dict)), {})
        phones = _string_list(raw.get("phones"))
        websites = _string_list(raw.get("websites"))
        sources = _json_value(raw.get("sources"), [])
        # Overture adds a release-time provenance row for its calculated confidence.
        # That timestamp is pipeline metadata, not evidence that the underlying Place
        # was recently refreshed. Freshness uses upstream source observations only.
        source_times = [
            str(row.get("update_time"))
            for row in sources
            if isinstance(row, dict)
            and row.get("update_time")
            and str(row.get("provider") or row.get("dataset") or "").casefold() != "overture"
            and str(row.get("property") or "") != "/properties/confidence"
        ]
        category_values = [*categories]
        return NormalizedPlace(
            provider=self.key,
            external_id=external_id,
            source_release=release.resolved_release,
            name=str(name).strip() if name else None,
            latitude=float(raw["latitude"]) if raw.get("latitude") is not None else None,
            longitude=float(raw["longitude"]) if raw.get("longitude") is not None else None,
            address=first_nonblank([address.get("freeform"), address.get("street")]),
            locality=first_nonblank([address.get("locality")]),
            region=first_nonblank([address.get("region")]),
            country_code=first_nonblank([address.get("country")]),
            postal_code=first_nonblank([address.get("postcode")]),
            categories=tuple(category_values),
            primary_category=str(primary or raw.get("basic_category")).strip()
            if primary or raw.get("basic_category") else None,
            benchmark_category=self.category_mapper.map(self.key, category_values),
            phone=phones[0] if phones else None,
            website=websites[0] if websites else None,
            operating_status=first_nonblank([raw.get("operating_status")]),
            confidence_or_quality=float(raw["confidence"])
            if raw.get("confidence") is not None else None,
            name_variants=_name_variants(names),
            refreshed_date=max(source_times) if source_times else None,
            source_metadata={
                "area": raw.get("_area"),
                "feature_version": raw.get("version"),
                "basic_category": raw.get("basic_category"),
                "taxonomy": taxonomy,
                "sources": sources,
            },
        )

    def license_metadata(self, release: ReleaseDescriptor) -> LicenseMetadata:
        return LicenseMetadata(
            provider=self.key,
            dataset_name="Overture Maps Places",
            license_identifier=(
                "CDLA-Permissive-2.0 OR Apache-2.0 OR CC0-1.0 (source-dependent)"
            ),
            source_url="https://docs.overturemaps.org/attribution/",
            attribution_requirements=(
                "Preserve each record's sources/license metadata and review the official "
                "attribution page for source-specific requirements."
            ),
            release=release.resolved_release,
            retrieval_timestamp=datetime.now(timezone.utc).isoformat(),
            notes=(
                "Places does not have one false universal source license.",
                "Meta, Microsoft, PinMeTo, Krick, RenderSEO, DAC and BrightQuery are listed as CDLA-Permissive-2.0.",
                "Foursquare-derived Places are listed as Apache-2.0; AllThePlaces is CC0-1.0.",
                "Human legal review is required before production persistence or attribution UI decisions.",
            ),
        )
