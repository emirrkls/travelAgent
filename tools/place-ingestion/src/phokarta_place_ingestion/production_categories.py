from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

from .config import DEFAULT_CONFIG_DIR, read_json
from .normalization import normalize_name


PLACE_CATEGORIES = frozenset({
    "BEACH", "RESTAURANT", "CAFE", "HOTEL", "BAR",
    "NIGHTLIFE", "ATTRACTION", "ACTIVITY", "NATURE",
})


@dataclass(frozen=True)
class ProductionCategoryDecision:
    category: str | None
    matched_value: str | None
    rule: str


class ProductionCategoryMapper:
    def __init__(self, mappings: dict[str, Any] | None = None):
        self._mappings = mappings or read_json(
            DEFAULT_CONFIG_DIR / "production_category_mappings.json"
        )

    def map(self, provider: str, values: Iterable[str]) -> ProductionCategoryDecision:
        provider_map = self._mappings["providers"].get(provider, {})
        exact = {
            normalize_name(key).replace(" ", "_"): value
            for key, value in provider_map.get("exact", {}).items()
        }
        normalized = [
            normalize_name(value).replace(" ", "_")
            for value in values
            if value and normalize_name(value)
        ]
        for value in normalized:
            category = exact.get(value)
            if category:
                self._validate(category)
                return ProductionCategoryDecision(category, value, "exact")
        for value in normalized:
            for row in provider_map.get("contains", []):
                token = normalize_name(row["token"]).replace(" ", "_")
                if token and token in value:
                    category = row["category"]
                    self._validate(category)
                    return ProductionCategoryDecision(category, value, f"contains:{token}")
        return ProductionCategoryDecision(None, None, "unmapped")

    @staticmethod
    def _validate(category: str) -> None:
        if category not in PLACE_CATEGORIES:
            raise ValueError(f"unsupported Phokarta Place category mapping: {category}")
