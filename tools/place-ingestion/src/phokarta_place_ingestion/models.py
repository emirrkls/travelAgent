from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import StrEnum
from typing import Any


class BenchmarkCategory(StrEnum):
    RESTAURANT = "RESTAURANT"
    CAFE = "CAFE"
    BAR_NIGHTLIFE = "BAR_NIGHTLIFE"
    HOTEL_LODGING = "HOTEL_LODGING"
    BEACH = "BEACH"
    MUSEUM = "MUSEUM"
    HISTORIC_PLACE = "HISTORIC_PLACE"
    PARK = "PARK"
    VIEWPOINT = "VIEWPOINT"
    MARKET = "MARKET"
    UNMAPPED = "UNMAPPED"


@dataclass(frozen=True)
class PilotArea:
    key: str
    display_name: str
    center_latitude: float
    center_longitude: float
    radius_meters: float
    reasoning: str


@dataclass(frozen=True)
class ReleaseDescriptor:
    provider: str
    requested_release: str | None
    resolved_release: str
    schema_version: str | None
    snapshot_id: str | None = None
    discovered_at: str = field(
        default_factory=lambda: datetime.now(timezone.utc).isoformat()
    )


@dataclass(frozen=True)
class LicenseMetadata:
    provider: str
    dataset_name: str
    license_identifier: str
    source_url: str
    attribution_requirements: str
    release: str
    retrieval_timestamp: str
    notes: tuple[str, ...] = ()


@dataclass(frozen=True)
class FetchStats:
    provider: str
    area: str
    query_seconds: float
    records_returned: int
    bytes_scanned: int | None = None
    bytes_downloaded: int | None = None
    query_method: str = "unknown"


@dataclass(frozen=True)
class FetchResult:
    records: list[dict[str, Any]]
    stats: list[FetchStats]


@dataclass(frozen=True)
class NormalizedPlace:
    provider: str
    external_id: str
    source_release: str
    name: str | None
    latitude: float | None
    longitude: float | None
    address: str | None
    locality: str | None
    region: str | None
    country_code: str | None
    postal_code: str | None
    categories: tuple[str, ...]
    primary_category: str | None
    benchmark_category: BenchmarkCategory
    phone: str | None
    website: str | None
    operating_status: str | None
    confidence_or_quality: float | str | None
    name_variants: tuple[str, ...] = ()
    created_date: str | None = None
    refreshed_date: str | None = None
    source_metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["benchmark_category"] = self.benchmark_category.value
        return value


@dataclass(frozen=True)
class GoldPlace:
    area: str
    name: str
    latitude: float
    longitude: float
    category: BenchmarkCategory
    source_reference: str
    name_variants: tuple[str, ...] = ()


@dataclass(frozen=True)
class DuplicateCandidate:
    provider: str
    area: str
    left_id: str
    right_id: str
    left_name: str
    right_name: str
    distance_meters: float
    name_similarity: float
    classification: str

@dataclass(frozen=True)
class CrossProviderMatch:
    area: str
    overture_id: str | None
    fsq_id: str | None
    overture_name: str | None
    fsq_name: str | None
    distance_meters: float | None
    name_similarity: float | None
    category_compatible: bool | None
    classification: str
