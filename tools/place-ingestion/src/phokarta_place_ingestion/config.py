from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .models import BenchmarkCategory, GoldPlace, PilotArea


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG_DIR = PACKAGE_ROOT / "config"
DEFAULT_FIXTURE_DIR = PACKAGE_ROOT / "fixtures"


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def load_pilot_areas(path: Path | None = None) -> list[PilotArea]:
    payload = read_json(path or DEFAULT_CONFIG_DIR / "pilot_areas.json")
    areas = [
        PilotArea(
            key=row["key"],
            display_name=row["display_name"],
            center_latitude=float(row["center"]["latitude"]),
            center_longitude=float(row["center"]["longitude"]),
            radius_meters=float(row["radius_meters"]),
            reasoning=row["reasoning"],
        )
        for row in payload["areas"]
    ]
    keys = [area.key for area in areas]
    if len(areas) != 6 or len(set(keys)) != len(keys):
        raise ValueError("pilot configuration must contain six uniquely keyed areas")
    return areas


def load_category_mappings(path: Path | None = None) -> dict[str, Any]:
    return read_json(path or DEFAULT_CONFIG_DIR / "category_mappings.json")


def load_provider_registry(path: Path | None = None) -> dict[str, Any]:
    payload = read_json(path or DEFAULT_CONFIG_DIR / "providers.json")
    if {row["key"] for row in payload["providers"]} != {"overture", "fsq"}:
        raise ValueError("provider registry must describe overture and fsq")
    return payload


def load_gold_places(path: Path | None = None) -> list[GoldPlace]:
    payload = read_json(path or DEFAULT_FIXTURE_DIR / "known_places.json")
    return [
        GoldPlace(
            area=row["area"],
            name=row["name"],
            latitude=float(row["latitude"]),
            longitude=float(row["longitude"]),
            category=BenchmarkCategory(row["category"]),
            source_reference=row["source_reference"],
        )
        for row in payload["places"]
    ]
