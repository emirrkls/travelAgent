from __future__ import annotations

import csv
import html
import json
from dataclasses import asdict, is_dataclass
from pathlib import Path
from typing import Any, Iterable


def _json_default(value: Any) -> Any:
    if is_dataclass(value):
        return asdict(value)
    if hasattr(value, "value"):
        return value.value
    raise TypeError(f"cannot serialize {type(value)!r}")


def write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, default=_json_default) + "\n",
        encoding="utf-8",
    )


def _csv_safe(value: Any) -> Any:
    if value is None:
        return ""
    if isinstance(value, (dict, list, tuple)):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True)
    if isinstance(value, str) and value.startswith(("=", "+", "-", "@")):
        return "'" + value
    return value


def write_csv(path: Path, rows: Iterable[dict[str, Any]], fallback_fields: list[str]) -> None:
    values = list(rows)
    fields = list(dict.fromkeys(key for row in values for key in row)) or fallback_fields
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in values:
            writer.writerow({key: _csv_safe(row.get(key)) for key in fields})


def write_bar_chart(path: Path, title: str, rows: list[tuple[str, float]]) -> None:
    width, row_height = 900, 34
    height = max(160, 90 + row_height * len(rows))
    maximum = max((value for _, value in rows), default=1) or 1
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#f8fbfd"/>',
        f'<text x="24" y="36" font-family="sans-serif" font-size="20" fill="#263746">{html.escape(title)}</text>',
    ]
    for index, (label, value) in enumerate(rows):
        y = 66 + index * row_height
        bar_width = 620 * value / maximum
        parts.append(f'<text x="24" y="{y + 17}" font-family="sans-serif" font-size="13" fill="#405466">{html.escape(label)}</text>')
        parts.append(f'<rect x="220" y="{y}" width="{bar_width:.2f}" height="20" rx="4" fill="#69aee6"/>')
        parts.append(f'<text x="{230 + bar_width:.2f}" y="{y + 16}" font-family="sans-serif" font-size="12" fill="#263746">{value:g}</text>')
    parts.append("</svg>\n")
    path.write_text("".join(parts), encoding="utf-8")
