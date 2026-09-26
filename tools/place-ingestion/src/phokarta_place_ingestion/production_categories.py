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

    def unmapped_non_ignored_values(
        self, provider: str, values: Iterable[str]
    ) -> tuple[str, ...]:
        """Return taxonomy values which are neither mapped nor explicitly neutral.

        Provider records commonly repeat a specific category together with a generic
        taxonomy parent.  Those parents are harmless only when the checked-in mapping
        explicitly identifies them as neutral.  Every other unknown sibling fails
        closed so a first matching category cannot conceal an unsupported one.
        """

        provider_map = self._mappings["providers"].get(provider, {})
        ignored = {
            normalize_name(value).replace(" ", "_")
            for value in provider_map.get("ignored", [])
        }
        result: list[str] = []
        for raw_value in values:
            if not raw_value or not normalize_name(raw_value):
                continue
            normalized = normalize_name(raw_value).replace(" ", "_")
            if normalized in ignored:
                continue
            if self.mapped_categories(provider, (str(raw_value),)):
                continue
            if normalized not in result:
                result.append(normalized)
        return tuple(result)

    def mapped_categories(self, provider: str, values: Iterable[str]) -> tuple[str, ...]:
        """Return every distinct category represented by a provider's taxonomy values.

        ``map`` intentionally retains deterministic first-match behavior for canonical
        proposals.  Safety checks use this method so a multi-valued provider record
        cannot hide an internal category conflict behind that first match.
        """

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
        categories: list[str] = []

        def append(category: str | None) -> None:
            if category:
                self._validate(category)
                if category not in categories:
                    categories.append(category)

        for value in normalized:
            append(exact.get(value))
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
                    append(row["category"])
        for value in normalized:
            for row in provider_map.get("taxonomy_prefixes", []):
                prefix = normalize_name(row["prefix"]).replace(" ", "_")
                if prefix and (value == prefix or value.startswith(prefix + "_")):
                    append(row["category"])
        return tuple(categories)

    @staticmethod
    def _validate(category: str) -> None:
        if category not in PLACE_CATEGORIES:
            raise ValueError(f"unsupported Phokarta Place category mapping: {category}")
