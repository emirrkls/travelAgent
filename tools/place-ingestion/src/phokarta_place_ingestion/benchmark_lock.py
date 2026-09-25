from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from .config import PACKAGE_ROOT


DEFAULT_LOCK_PATH = PACKAGE_ROOT / "config" / "benchmark_lock.json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_benchmark_lock(path: Path | None = None) -> dict[str, Any]:
    target = path or DEFAULT_LOCK_PATH
    with target.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def verify_benchmark_lock(path: Path | None = None) -> dict[str, Any]:
    lock = load_benchmark_lock(path)
    failures: list[str] = []
    for relative, expected in sorted(lock.get("files", {}).items()):
        target = PACKAGE_ROOT / relative
        if not target.is_file():
            failures.append(f"missing:{relative}")
        elif sha256_file(target) != expected:
            failures.append(f"changed:{relative}")
    if failures:
        raise ValueError("benchmark lock verification failed: " + ", ".join(failures))
    return lock
