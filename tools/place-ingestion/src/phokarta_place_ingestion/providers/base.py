from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Iterable

from ..models import FetchResult, LicenseMetadata, NormalizedPlace, PilotArea, ReleaseDescriptor


class ProviderError(RuntimeError):
    pass


class ProviderAccessError(ProviderError):
    pass


class ProviderSchemaError(ProviderError):
    pass


class ExternalPlaceProvider(ABC):
    key: str

    @abstractmethod
    def describe_release(self, requested_release: str | None = None) -> ReleaseDescriptor:
        """Resolve and describe a reproducible provider release or snapshot."""

    @abstractmethod
    def fetch_scope(
        self, areas: Iterable[PilotArea], release: ReleaseDescriptor
    ) -> FetchResult:
        """Fetch only records needed for the supplied public benchmark geometries."""

    @abstractmethod
    def normalize(
        self, raw: dict[str, Any], release: ReleaseDescriptor
    ) -> NormalizedPlace:
        """Map a provider row to the provider-independent benchmark model."""

    @abstractmethod
    def license_metadata(self, release: ReleaseDescriptor) -> LicenseMetadata:
        """Return machine-readable dataset provenance without legal guarantees."""

    def fetch_delta(self, checkpoint: str) -> FetchResult:
        raise NotImplementedError("Delta ingestion is intentionally deferred to M5.5B")
