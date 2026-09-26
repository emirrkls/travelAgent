from __future__ import annotations

import ipaddress
import math
import re
from dataclasses import dataclass
from typing import Any, Iterable
from urllib.parse import SplitResult, urlsplit, urlunsplit

from .delegated_tlds import DELEGATED_TOP_LEVEL_DOMAINS
from .normalization import normalize_name


_HOST_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$", re.IGNORECASE)
_GENERIC_IDENTITY_TOKENS = frozenset({
    "and", "bar", "beach", "cafe", "coffee", "didim", "hotel", "kafe",
    "lokali", "otel", "restaurant", "resort", "the", "turkiye", "turkey",
})
_SOCIAL_DOMAINS = frozenset({
    "facebook.com", "fb.com", "instagram.com", "linkedin.com", "tiktok.com",
    "twitter.com", "x.com", "youtube.com",
})
_SECOND_LEVEL_PUBLIC_SUFFIXES = frozenset({
    "ac", "co", "com", "edu", "gov", "net", "org",
})
_PLACEHOLDER_DOMAINS = frozenset({
    "example.com", "example.net", "example.org", "localhost",
})
_RESERVED_SUFFIXES = (".example", ".invalid", ".local", ".localhost", ".test")
_NON_WEB_TOP_LEVEL_DOMAINS = frozenset({"arpa"})


@dataclass(frozen=True)
class WebsiteDecision:
    canonical_value: str | None
    domain: str | None
    reason: str


@dataclass(frozen=True)
class AttributeDecision:
    """A canonical-field decision which never discards the raw provider value."""

    raw_value: Any
    canonical_value: Any | None
    accepted: bool
    reason: str


_PHONE_NON_DIGITS = re.compile(r"\D+")
_CONTROL_CHARACTER = re.compile(r"[\x00-\x1f\x7f]")
_URL_LIKE = re.compile(r"(?:https?://|www\.)", re.IGNORECASE)
_GENERIC_NAMES = frozenset({
    "bar", "beach", "cafe", "hotel", "kafe", "market", "otel", "park",
    "restaurant", "restoran", "shop", "store",
})
_NON_DISTINCTIVE_NAME_TOKENS = frozenset({
    "akbuk", "altinkum", "aydin", "bar", "beach", "cafe", "center", "centre",
    "didim", "hotel", "kafe", "marina", "market", "mavisehir", "merkez",
    "otel", "park", "restaurant", "restoran", "resort", "shop", "store",
    "yenihisar", "yesilkent",
})
_EMBEDDED_TURKISH_PHONE = re.compile(
    r"(?<!\d)(?:(?:(?:\+|00)?90)[\s().-]*)?0?[25](?:[\s().-]*\d){9}(?!\d)"
)
_PRICE_OR_CURRENCY = re.compile(
    r"(?:\b(?:tl|try|usd|eur)\s*\d|\d[\d.,]*\s*(?:tl|try|usd|eur)\b|"
    r"[₺€$]\s*\d|\d[\d.,]*\s*[₺€$])",
    re.IGNORECASE,
)
_PROMOTIONAL_NAME_TOKENS = frozenset({
    "cesit", "çeşit", "fiyat", "indirim", "kampanya", "kisi", "kişi",
    "menu", "menü", "paket", "reservation", "rezervasyon", "rzv",
    "sinirsiz", "sınırsız", "ucretsiz", "ücretsiz",
})
_RESERVATION_NAME_TOKENS = frozenset({
    "reservation", "rezervasyon", "rzv",
})


def _parse_public_http_url(value: str | None) -> tuple[SplitResult | None, str | None, str]:
    raw = (value or "").strip()
    if not raw:
        return None, None, "missing"
    if raw.startswith("@") or any(character.isspace() for character in raw):
        return None, None, "invalid_or_handle"
    candidate = raw if "://" in raw else f"https://{raw}"
    try:
        parsed = urlsplit(candidate)
        port = parsed.port
    except ValueError:
        return None, None, "invalid_url"
    if parsed.scheme.casefold() not in {"http", "https"}:
        return None, None, "unsupported_scheme"
    if parsed.username is not None or parsed.password is not None or port is not None:
        return None, None, "userinfo_or_port_not_allowed"
    host = (parsed.hostname or "").strip(".").casefold()
    if not host or "." not in host:
        return None, None, "non_public_host"
    try:
        ipaddress.ip_address(host)
        return None, None, "ip_literal_not_allowed"
    except ValueError:
        pass
    try:
        ascii_host = host.encode("idna").decode("ascii")
    except UnicodeError:
        return None, None, "invalid_host"
    labels = ascii_host.split(".")
    bare_host = ascii_host.removeprefix("www.")
    if (
        any(
            bare_host == domain or bare_host.endswith("." + domain)
            for domain in _PLACEHOLDER_DOMAINS
        )
        or bare_host.endswith(_RESERVED_SUFFIXES)
    ):
        return None, bare_host, "placeholder_or_reserved_domain"
    if any(not _HOST_LABEL.fullmatch(label) for label in labels):
        return None, None, "invalid_host"
    if (
        labels[-1] not in DELEGATED_TOP_LEVEL_DOMAINS
        or labels[-1] in _NON_WEB_TOP_LEVEL_DOMAINS
    ):
        return None, None, "invalid_public_suffix"
    normalized = urlunsplit((
        parsed.scheme.casefold(), ascii_host, parsed.path or "", parsed.query or "", ""
    ))
    return urlsplit(normalized), ascii_host.removeprefix("www."), "valid_syntax"


def canonical_website_domain(value: str | None) -> str | None:
    _, domain, _ = _parse_public_http_url(value)
    return domain


def _identity_tokens(names: Iterable[str | None]) -> frozenset[str]:
    return frozenset(
        token
        for name in names
        for token in normalize_name(name).split()
        if len(token) >= 4
        and token not in _GENERIC_IDENTITY_TOKENS
        and token not in _NON_DISTINCTIVE_NAME_TOKENS
    )


def _host_identity_labels(domain: str) -> frozenset[str]:
    labels = domain.split(".")
    if len(labels) >= 3 and len(labels[-1]) == 2 and labels[-2] in _SECOND_LEVEL_PUBLIC_SUFFIXES:
        identity_labels = labels[:-2]
    else:
        identity_labels = labels[:-1]
    keys: set[str] = set()
    for label in identity_labels:
        normalized = normalize_name(label)
        keys.update(token for token in normalized.split() if token)
        compact = normalized.replace(" ", "")
        if compact:
            keys.add(compact)
    return frozenset(keys)


def _identity_brand_keys(names: Iterable[str | None]) -> frozenset[str]:
    values = tuple(names)
    keys = set(_identity_tokens(values))
    for name in values:
        tokens = normalize_name(name).split()
        if not any(
            len(token) >= 4
            and token not in _GENERIC_IDENTITY_TOKENS
            and token not in _NON_DISTINCTIVE_NAME_TOKENS
            for token in tokens
        ):
            continue
        compact = "".join(token for token in tokens if token not in {"and", "the"})
        if len(compact) >= 4:
            keys.add(compact)
    return frozenset(keys)


def validate_canonical_website(
    value: str | None,
    *,
    identity_names: Iterable[str | None],
    corroborating_values: Iterable[str | None] = (),
) -> WebsiteDecision:
    parsed, domain, reason = _parse_public_http_url(value)
    if not parsed or not domain:
        return WebsiteDecision(None, None, reason)
    if domain in _SOCIAL_DOMAINS or any(domain.endswith("." + item) for item in _SOCIAL_DOMAINS):
        return WebsiteDecision(None, domain, "social_profile_not_canonical_website")

    corroborating_domains = {
        candidate_domain
        for candidate in corroborating_values
        if (candidate_domain := canonical_website_domain(candidate))
    }
    host_keys = _host_identity_labels(domain)
    brand_keys = _identity_brand_keys(identity_names)
    if brand_keys.intersection(host_keys):
        reason = (
            "identity_and_cross_provider_domain_match"
            if domain in corroborating_domains
            else "identity_token_match"
        )
        return WebsiteDecision(urlunsplit(parsed), domain, reason)
    return WebsiteDecision(None, domain, "identity_unverified")


def validate_canonical_name(value: str | None) -> AttributeDecision:
    raw = value
    candidate = " ".join((value or "").split())
    normalized = normalize_name(candidate)
    if not candidate:
        return AttributeDecision(raw, None, False, "missing")
    if _CONTROL_CHARACTER.search(candidate) or _URL_LIKE.search(candidate):
        return AttributeDecision(raw, None, False, "invalid_name_syntax")
    if candidate.startswith("@") or len(normalized) < 2:
        return AttributeDecision(raw, None, False, "handle_or_too_short")
    if not any(character.isalpha() for character in candidate):
        return AttributeDecision(raw, None, False, "name_has_no_letters")
    normalized_tokens = normalized.split()
    promotional_tokens = {
        token for token in normalized_tokens if token in _PROMOTIONAL_NAME_TOKENS
    }
    if _EMBEDDED_TURKISH_PHONE.search(candidate):
        return AttributeDecision(raw, None, False, "contact_text_in_name")
    if _PRICE_OR_CURRENCY.search(candidate):
        return AttributeDecision(raw, None, False, "price_or_currency_in_name")
    if (
        promotional_tokens.intersection(_RESERVATION_NAME_TOKENS)
        or len(promotional_tokens) >= 2
        or (len(candidate) >= 90 and len(normalized_tokens) >= 12)
    ):
        return AttributeDecision(raw, None, False, "promotional_text_in_name")
    if normalized in _GENERIC_NAMES or set(normalized.split()).issubset(
        _NON_DISTINCTIVE_NAME_TOKENS
    ):
        return AttributeDecision(raw, None, False, "generic_name")
    if len(candidate) > 160:
        return AttributeDecision(raw, None, False, "name_too_long")
    return AttributeDecision(raw, candidate, True, "valid_name")


def normalize_turkish_phone(value: str | None) -> AttributeDecision:
    """Normalize Turkish geographic/mobile numbers to compact E.164 form.

    Extensions and non-TR country codes intentionally fail closed. The raw value is
    returned on every path so provenance writers can retain it unchanged.
    """

    raw = value
    text = (value or "").strip()
    if not text:
        return AttributeDecision(raw, None, False, "missing")
    lowered = text.casefold()
    if any(marker in lowered for marker in ("ext", "dahili", " x")):
        return AttributeDecision(raw, None, False, "extension_not_supported")
    if any(character.isalpha() for character in text):
        return AttributeDecision(raw, None, False, "invalid_turkish_phone_syntax")
    if text.startswith("+") and not text.startswith("+90"):
        return AttributeDecision(raw, None, False, "non_turkish_country_code")
    if text.startswith("00") and not text.startswith("0090"):
        return AttributeDecision(raw, None, False, "non_turkish_country_code")
    if "+" in text[1:]:
        return AttributeDecision(raw, None, False, "invalid_turkish_phone_syntax")
    digits = _PHONE_NON_DIGITS.sub("", text)
    if digits.startswith("0090"):
        digits = digits[2:]
    if digits.startswith("90") and len(digits) == 12:
        national = digits[2:]
    elif digits.startswith("0") and len(digits) == 11:
        national = digits[1:]
    elif len(digits) == 10:
        national = digits
    else:
        return AttributeDecision(raw, None, False, "invalid_turkish_phone_length")
    # Turkish geographic numbers start with 2 and mobile numbers start with 5.
    if not national or national[0] not in "25" or national == national[0] * 10:
        return AttributeDecision(raw, None, False, "invalid_turkish_phone_prefix")
    subscriber = national[3:]
    if not subscriber or len(set(subscriber)) == 1:
        return AttributeDecision(raw, None, False, "placeholder_turkish_phone")
    return AttributeDecision(raw, f"+90{national}", True, "valid_turkish_e164")


def validate_canonical_address(value: str | None) -> AttributeDecision:
    raw = value
    candidate = " ".join((value or "").split())
    if not candidate:
        return AttributeDecision(raw, None, False, "missing")
    if _CONTROL_CHARACTER.search(candidate) or _URL_LIKE.search(candidate):
        return AttributeDecision(raw, None, False, "invalid_address_syntax")
    if candidate.startswith("@") or len(candidate) < 4:
        return AttributeDecision(raw, None, False, "address_too_short")
    if len(candidate) > 300:
        return AttributeDecision(raw, None, False, "address_too_long")
    return AttributeDecision(raw, candidate, True, "valid_address")


def validate_canonical_coordinates(
    latitude: float | None,
    longitude: float | None,
    *,
    center: tuple[float, float] | None = None,
    max_distance_meters: float | None = None,
) -> AttributeDecision:
    raw = {"latitude": latitude, "longitude": longitude}
    if (
        isinstance(latitude, bool) or isinstance(longitude, bool)
        or not isinstance(latitude, (int, float))
        or not isinstance(longitude, (int, float))
        or not math.isfinite(float(latitude))
        or not math.isfinite(float(longitude))
        or not -90 <= float(latitude) <= 90
        or not -180 <= float(longitude) <= 180
    ):
        return AttributeDecision(raw, None, False, "invalid_coordinates")
    if center is not None and max_distance_meters is not None:
        # Local import avoids a canonical-attributes/normalization import cycle.
        from .normalization import haversine_meters

        distance = haversine_meters(
            center[0], center[1], float(latitude), float(longitude)
        )
        if distance > max_distance_meters:
            return AttributeDecision(raw, None, False, "coordinates_outside_scope")
    return AttributeDecision(
        raw,
        {"latitude": float(latitude), "longitude": float(longitude)},
        True,
        "valid_coordinates",
    )


def validate_canonical_category(
    value: str | None,
    *,
    allowed_categories: Iterable[str],
) -> AttributeDecision:
    raw = value
    candidate = (value or "").strip().upper()
    allowed = frozenset(str(item).upper() for item in allowed_categories)
    if not candidate:
        return AttributeDecision(raw, None, False, "missing")
    if candidate not in allowed:
        return AttributeDecision(raw, None, False, "unmapped_or_unsupported_category")
    return AttributeDecision(raw, candidate, True, "valid_exact_category")
