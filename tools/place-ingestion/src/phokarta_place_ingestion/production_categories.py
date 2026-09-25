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
            tokens = frozenset(token for token in value.split("_") if token)
            for row in provider_map.get("token_sets", []):
                required = frozenset(
                    normalize_name(token).replace(" ", "_")
                    for token in row.get("all", [])
                )
                forbidden = frozenset(
                    normalize_name(token).replace(" ", "_")
                    for token in row.get("none", [])
                )
                if required and required.issubset(tokens) and not forbidden.intersection(tokens):
                    category = row["category"]
                    self._validate(category)
                    return ProductionCategoryDecision(
                        category, value, f"token_set:{'+'.join(sorted(required))}"
                    )

        for value in normalized:
            for row in provider_map.get("taxonomy_prefixes", []):
                prefix = normalize_name(row["prefix"]).replace(" ", "_")
                if prefix and (value == prefix or value.startswith(prefix + "_")):
                    category = row["category"]
                    self._validate(category)
                    return ProductionCategoryDecision(
                        category, value, f"taxonomy_prefix:{prefix}"
                    )
        return ProductionCategoryDecision(None, None, "unmapped")

    @staticmethod
    def _validate(category: str) -> None:
        if category not in PLACE_CATEGORIES:
            raise ValueError(f"unsupported Phokarta Place category mapping: {category}")
