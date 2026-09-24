from __future__ import annotations

from dataclasses import dataclass

from .models import NormalizedPlace
from .normalization import valid_coordinate


@dataclass(frozen=True)
class QualityDecision:
    usable: bool
    junk: bool
    reasons: tuple[str, ...]


def assess_quality(place: NormalizedPlace) -> QualityDecision:
    reasons: list[str] = []
    severe_junk = False
    if not valid_coordinate(place.latitude, place.longitude):
        reasons.append("invalid_coordinate")
    if not place.name or not place.name.strip():
        reasons.append("missing_name")
    status = (place.operating_status or "").casefold()
    if status in {"closed", "permanently_closed"}:
        reasons.append("explicitly_closed")

    if place.provider == "overture":
        confidence = place.confidence_or_quality
        if isinstance(confidence, (float, int)) and float(confidence) < 0.30:
            reasons.append("severe_low_confidence")
            severe_junk = True
    elif place.provider == "fsq":
        flags = {str(value).strip().casefold() for value in place.source_metadata.get("unresolved_flags", [])}
        severe = flags.intersection(
            {"closed", "duplicate", "delete", "privatevenue", "inappropriate", "doesnt_exist"}
        )
        if severe:
            reasons.extend(f"unresolved_{flag}" for flag in sorted(severe))
            severe_junk = True

    return QualityDecision(usable=not reasons, junk=severe_junk, reasons=tuple(reasons))
