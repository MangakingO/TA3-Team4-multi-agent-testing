"""Pulls Java files out of the refactoring agent's reply (one fenced ```java block per file)."""
from __future__ import annotations

import re

from .javasrc import primary_type

_FENCE = re.compile(r"```[ \t]*([\w+-]*)[ \t]*\n(.*?)```", re.S)
_NAME = re.compile(r"([A-Za-z_][\w$]*\.java)")


def parse_files(reply: str) -> dict[str, str]:
    files: dict[str, str] = {}
    for m in _FENCE.finditer(reply):
        lang, code = m.group(1).lower(), m.group(2)
        if lang not in ("", "java"):
            continue
        lines = code.strip("\n").splitlines()
        name = None
        if lines:
            first = re.match(r"^\s*//\s*(?:File(?:name)?\s*:\s*)?([A-Za-z_][\w$]*\.java)\s*$", lines[0], re.I)
            if first:
                name = first.group(1)
                lines = lines[1:]
        if name is None:
            before = reply[:m.start()].rstrip().splitlines()
            for prev in reversed(before[-3:]):
                if prev.strip():
                    found = _NAME.findall(prev)
                    if found:
                        name = found[-1]
                    break
        body = "\n".join(lines).strip("\n")
        if not re.search(r"\b(class|interface|enum|record)\s+\w+", body):
            continue
        declared = primary_type(body)
        if declared and (name is None or name[:-5] != declared):
            name = f"{declared}.java"  # the file name must match the public type
        if name:
            files[name] = body + "\n"
    return files


def design_notes(reply: str, limit: int = 4000) -> str:
    text = _FENCE.sub("", reply)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text[:limit]
