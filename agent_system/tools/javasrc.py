"""Facts about Java source text that the agents need (package, class, main method, line numbers)."""
from __future__ import annotations

import re
from pathlib import PurePosixPath

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//[^\n]*")
_STRING = re.compile(r'"(?:\\.|[^"\\])*"')


def strip_comments(source: str) -> str:
    """Remove comments (string literals are blanked first so '//' inside a string survives)."""
    blanked = _STRING.sub('""', source)
    return _LINE_COMMENT.sub("", _BLOCK_COMMENT.sub("", blanked))


def package_of(source: str) -> str:
    m = re.search(r"^\s*package\s+([\w.]+)\s*;", strip_comments(source), re.M)
    return m.group(1) if m else ""


_TYPE_DECL = re.compile(
    r"^(?:[ \t]*)((?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp)\s+)*"
    r"(class|interface|enum|record)\s+([A-Za-z_]\w*)",
    re.M,
)


def type_names(source: str) -> list[str]:
    return [m.group(3) for m in _TYPE_DECL.finditer(strip_comments(source))]


def primary_type(source: str, fallback: str = "") -> str:
    code = strip_comments(source)
    m = re.search(r"\bpublic\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\s+)*"
                  r"(?:class|interface|enum|record)\s+([A-Za-z_]\w*)", code)
    if m:
        return m.group(1)
    names = type_names(source)
    return names[0] if names else fallback


def has_main(source: str) -> bool:
    return re.search(r"\bstatic\s+(?:final\s+)?void\s+main\s*\(\s*(?:final\s+)?String", strip_comments(source)) is not None


def fqcn(package: str, name: str) -> str:
    return f"{package}.{name}" if package else name


def source_relpath(package: str, file_name: str) -> PurePosixPath:
    parts = package.split(".") if package else []
    return PurePosixPath(*parts, file_name) if parts else PurePosixPath(file_name)


def numbered(source: str, marks: dict[int, str] | None = None) -> str:
    """Source with line numbers; marks adds a note after given lines (e.g. missed branches)."""
    marks = marks or {}
    width = len(str(source.count("\n") + 1))
    out = []
    for nr, line in enumerate(source.splitlines(), start=1):
        note = f"    // <-- {marks[nr]}" if nr in marks else ""
        out.append(f"{nr:>{width}} | {line}{note}")
    return "\n".join(out)
