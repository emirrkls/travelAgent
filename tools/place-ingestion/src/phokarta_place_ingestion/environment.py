from __future__ import annotations

import os
from collections.abc import MutableMapping
from pathlib import Path

from .config import PACKAGE_ROOT


LOCAL_ENV_PATH = PACKAGE_ROOT / ".env.local"
FSQ_TOKEN_VARIABLE = "FSQ_PLACES_TOKEN"


def _read_local_token(path: Path) -> str | None:
    if not path.is_file():
        return None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if separator and key.strip() == FSQ_TOKEN_VARIABLE:
            candidate = value.strip()
            if len(candidate) >= 2 and candidate[0] == candidate[-1] and candidate[0] in {"'", '"'}:
                candidate = candidate[1:-1]
            return candidate
    return None


def load_local_environment(
    path: Path | None = None,
    environ: MutableMapping[str, str] | None = None,
) -> None:
    """Load the local FSQ token without replacing an explicit process value."""
    target = os.environ if environ is None else environ
    if FSQ_TOKEN_VARIABLE in target:
        return
    token = _read_local_token(path or LOCAL_ENV_PATH)
    if token:
        target[FSQ_TOKEN_VARIABLE] = token


def fsq_credential_available(
    path: Path | None = None,
    environ: MutableMapping[str, str] | None = None,
) -> bool:
    target = os.environ if environ is None else environ
    load_local_environment(path=path, environ=target)
    return bool(target.get(FSQ_TOKEN_VARIABLE, "").strip())
