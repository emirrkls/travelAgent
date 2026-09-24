from __future__ import annotations

from typing import Any, Iterable

from .models import BenchmarkCategory
from .normalization import normalize_name


class CategoryMapper:
    def __init__(self, mappings: dict[str, Any]):
        self._mappings = mappings

    def map(self, provider: str, values: Iterable[str]) -> BenchmarkCategory:
        provider_map = self._mappings["providers"].get(provider, {})
        exact = {
            normalize_name(key): BenchmarkCategory(value)
            for key, value in provider_map.get("exact", {}).items()
        }
        contains = [
            (normalize_name(row["token"]), BenchmarkCategory(row["category"]))
            for row in provider_map.get("contains", [])
        ]
        normalized = [normalize_name(value).replace(" ", "_") for value in values if value]
        for value in normalized:
            key = value.replace("_", " ")
            if key in exact:
                return exact[key]
            if value in exact:
                return exact[value]
        for value in normalized:
            for token, category in contains:
                token = token.replace(" ", "_")
                if token and token in value:
                    return category
        return BenchmarkCategory.UNMAPPED
