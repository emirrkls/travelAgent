from __future__ import annotations

import math
import re
import unicodedata
from collections.abc import Iterable


_PUNCTUATION = re.compile(r"[^\w\s]", re.UNICODE)
_WHITESPACE = re.compile(r"\s+", re.UNICODE)


def normalize_name(value: str | None) -> str:
    """Normalize for matching without applying English-locale casing rules.

    Unicode casefold preserves the four Turkish I forms as distinct code-point
    sequences where appropriate. Removing combining marks after NFKD makes dotted
    capital I compare with ordinary i while dotless ı remains dotless.
    """
    if not value:
        return ""
    folded = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(ch for ch in folded if not unicodedata.combining(ch))
    punctuation_as_space = _PUNCTUATION.sub(" ", without_marks)
    return _WHITESPACE.sub(" ", punctuation_as_space).strip()


def valid_coordinate(latitude: float | None, longitude: float | None) -> bool:
    return (
        isinstance(latitude, (float, int))
        and not isinstance(latitude, bool)
        and isinstance(longitude, (float, int))
        and not isinstance(longitude, bool)
        and math.isfinite(float(latitude))
        and math.isfinite(float(longitude))
        and -90.0 <= float(latitude) <= 90.0
        and -180.0 <= float(longitude) <= 180.0
    )


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6_371_008.8
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return radius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def circle_bbox(latitude: float, longitude: float, radius_meters: float) -> tuple[float, float, float, float]:
    lat_delta = radius_meters / 111_320.0
    lon_delta = radius_meters / (111_320.0 * max(math.cos(math.radians(latitude)), 0.01))
    return longitude - lon_delta, latitude - lat_delta, longitude + lon_delta, latitude + lat_delta


def first_nonblank(values: Iterable[object]) -> str | None:
    for value in values:
        if value is not None and str(value).strip():
            return str(value).strip()
    return None
