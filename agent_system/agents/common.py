"""Helpers shared by the LLM agents: how code and test cases are shown to the model."""
from __future__ import annotations

import json

from ..tools import javasrc


def package_text(package: str) -> str:
    return f"package {package}" if package else "default package"


def sources_block(files: dict[str, str], marks: dict[str, dict[int, str]] | None = None,
                  numbered: bool = True) -> str:
    marks = marks or {}
    parts = []
    for name, content in files.items():
        body = javasrc.numbered(content, marks.get(name)) if numbered else content.rstrip()
        parts.append(f"// ===== {name} =====\n```java\n{body}\n```")
    return "\n\n".join(parts)


def suite_lines(suite: list[dict],
                fields=("id", "technique", "method", "condition", "input", "expected", "junit")) -> str:
    return "\n".join(json.dumps({k: c.get(k, "") for k in fields}, ensure_ascii=False) for c in suite)


def observed_block(observed: str) -> str:
    text = (observed or "").strip()
    if not text:
        return ""
    if len(text) > 3000:
        text = text[:3000] + "\n... (truncated)"
    return f"Observed behaviour (the program run from the command line):\n```\n{text}\n```"
