from __future__ import annotations

import ipaddress
import re
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import SplitResult, urlsplit, urlunsplit

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


@dataclass(frozen=True)
class WebsiteDecision:
    canonical_value: str | None
    domain: str | None
    reason: str


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
    if any(not _HOST_LABEL.fullmatch(label) for label in labels):
        return None, None, "invalid_host"
    if len(labels[-1]) < 2 or labels[-1].isdigit():
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
        if len(token) >= 4 and token not in _GENERIC_IDENTITY_TOKENS
    )


def _host_identity_text(domain: str) -> str:
    labels = domain.split(".")
    if len(labels) >= 3 and len(labels[-1]) == 2 and labels[-2] in _SECOND_LEVEL_PUBLIC_SUFFIXES:
        identity_labels = labels[:-2]
    else:
        identity_labels = labels[:-1]
    return normalize_name(" ".join(identity_labels)).replace(" ", "")


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
    host_identity = _host_identity_text(domain)
    tokens = _identity_tokens(identity_names)
    if tokens and any(token.replace(" ", "") in host_identity for token in tokens):
        reason = (
            "identity_and_cross_provider_domain_match"
            if domain in corroborating_domains
            else "identity_token_match"
        )
        return WebsiteDecision(urlunsplit(parsed), domain, reason)
    return WebsiteDecision(None, domain, "identity_unverified")
