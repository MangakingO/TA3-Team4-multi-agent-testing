"""Test curator: merges test cases from several agents and removes duplicates.

Two test cases are duplicates when
  * their JUnit code is the same after removing comments and whitespace and renaming
    local variables (v0, v1, ...), or
  * they exercise the same method with the same input and expect the same result.
Same method and input but a different expected result is a conflict: both are kept and
the human reviewer is asked to decide.
"""
from __future__ import annotations

import re

from .junit import body_of

_DECL = re.compile(
    r"\b(?:final\s+)?(?:var|int|long|short|byte|char|float|double|boolean|"
    r"[A-Z][\w.]*(?:<[^<>;]*>)?)(?:\[\])*\s+([a-z_]\w*)\s*(?:=|:)")


_SEGMENTS = re.compile(r'(?P<str>"(?:\\.|[^"\\\n])*")|(?P<chr>\'(?:\\.|[^\'\\\n])*\')'
                       r"|(?P<line>//[^\n]*)|(?P<block>/\*.*?\*/)", re.S)


def _segments(code: str):
    """('code', text) and ('lit', text) pieces of Java code; comments dropped, literals kept exactly."""
    pos = 0
    for m in _SEGMENTS.finditer(code):
        if m.start() > pos:
            yield "code", code[pos:m.start()]
        if m.lastgroup in ("str", "chr"):
            yield "lit", m.group(0)
        pos = m.end()
    if pos < len(code):
        yield "code", code[pos:]


def canonical_code(junit: str) -> str:
    """Test code without comments and whitespace, local variables renamed v0, v1, ...;
    string and character literals are kept exactly, because they usually are the test data."""
    parts = list(_segments(body_of(junit)))
    names = dict.fromkeys(_DECL.findall(" ".join(t for k, t in parts if k == "code")))
    out = []
    for kind, text in parts:
        if kind == "code":
            for i, name in enumerate(names):
                text = re.sub(rf"\b{re.escape(name)}\b", f"v{i}", text)
            text = re.sub(r"\s+", "", text)
        out.append(text)
    return "".join(out)


def identifiers(code: str) -> set[str]:
    """Identifiers used in Java code, ignoring string literals and comments."""
    return set(re.findall(r"[A-Za-z_]\w*", " ".join(t for k, t in _segments(code or "") if k == "code")))


def _norm(text: str) -> str:
    """Conservative: case and spacing only, so different inputs are never merged by accident."""
    return " ".join((text or "").lower().split())


def _unique_id(wanted: str, taken: set[str], prefix: str) -> str:
    base = (wanted or "").strip() or f"{prefix}-01"
    base = re.sub(r"[^\w-]+", "-", base).strip("-") or f"{prefix}-01"
    if base not in taken:
        return base
    n = 2
    while f"{base}-{n}" in taken:
        n += 1
    return f"{base}-{n}"


def merge(existing: list[dict], incoming: list[dict], default_technique: str = "EP") -> tuple[list[dict], dict]:
    """Add incoming cases to existing ones, dropping duplicates. Returns (suite, report)."""
    suite = [dict(c, also=list(c.get("also", []))) for c in existing]
    by_code = {canonical_code(c["junit"]): c for c in suite}
    by_io = {(_norm(c["method"]), _norm(c["input"])): c for c in suite}
    taken = {c["id"] for c in suite}
    report = {"received": len(incoming), "added": [], "duplicates": [], "conflicts": [], "dropped": []}

    for raw in incoming:
        case = {k: (raw.get(k) or "") for k in ("id", "technique", "method", "condition", "input", "expected", "junit")}
        case["technique"] = (case["technique"] or default_technique).upper()
        case["also"] = []
        if not body_of(case["junit"]).strip():
            report["dropped"].append({"id": case["id"], "reason": "no JUnit code"})
            continue
        code_key = canonical_code(case["junit"])
        io_key = (_norm(case["method"]), _norm(case["input"]))
        twin, rule = None, ""
        if code_key in by_code:
            twin, rule = by_code[code_key], "identical test code"
        elif io_key in by_io and io_key != ("", ""):
            other = by_io[io_key]
            if _norm(other["expected"]) == _norm(case["expected"]):
                twin, rule = other, "same input and expected result"
            else:
                report["conflicts"].append({
                    "kept": other["id"], "new": case["id"] or "?", "input": case["input"],
                    "expected_kept": other["expected"], "expected_new": case["expected"]})
        if twin is not None:
            twin["also"].append(case["id"] or "?")
            techniques = set(twin["technique"].split("+")) | {case["technique"]}
            twin["technique"] = "+".join(t for t in ("EP", "BVA", "COVERAGE", "REVIEW") if t in techniques) or twin["technique"]
            report["duplicates"].append({"kept": twin["id"], "removed": case["id"] or "?", "rule": rule})
            continue
        case["id"] = _unique_id(case["id"], taken, case["technique"])
        taken.add(case["id"])
        suite.append(case)
        by_code[code_key] = case
        by_io.setdefault(io_key, case)
        report["added"].append(case["id"])
    report["total"] = len(suite)
    return suite, report


def curate(ep_cases: list[dict], bva_cases: list[dict]) -> tuple[list[dict], dict]:
    """The join step: one distinct suite from the EP and BVA agents' test cases."""
    suite, ep_report = merge([], ep_cases, "EP")
    suite, bva_report = merge(suite, bva_cases, "BVA")
    report = {
        "ep_received": ep_report["received"],
        "bva_received": bva_report["received"],
        "duplicates": ep_report["duplicates"] + bva_report["duplicates"],
        "conflicts": ep_report["conflicts"] + bva_report["conflicts"],
        "dropped": ep_report["dropped"] + bva_report["dropped"],
        "distinct": len(suite),
    }
    return suite, report
