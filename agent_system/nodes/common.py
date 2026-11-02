"""Helpers used by several nodes: saving the suite, applying edits with a change-log entry."""
from __future__ import annotations

import json
from pathlib import Path

from ..schemas import CodeEdit, TestEdit
from ..tools import javasrc, workspace
from ..tools.testability import Testability
from ..tools.junit import body_of


def note(node: str, message: str, **data) -> dict:
    """An event for the run's history (the CLI prints the message)."""
    return {"node": node, "message": message, **data}


def notes_for(state: dict, phase: str) -> str:
    """The testability facts for the prompts of this phase (falls back to the original code's)."""
    data = (state.get("testability") or {})
    found = data.get(phase) or data.get("original")
    return Testability.from_dict(found).notes() if found else ""


def members_text(state: dict, test_code: str | None = None) -> str:
    """Non-private members of the original class and its nested classes, one class per line;
    with test_code, only the members the tests reference (the state field never counts)."""
    import re

    found = (state.get("testability") or {}).get("original") or {}
    from ..tools.dedup import identifiers

    names = identifiers(test_code) if test_code is not None else None
    lines = []
    for cls, members in (found.get("members") or {}).items():
        keep = []
        for m in members:
            name = (m.split("(")[0] if "(" in m else m.split(" = ")[0]).split()[-1]
            if "(" not in m and re.search("state", name, re.I):
                continue
            if names is None or name in names:
                keep.append(m)
        if keep:
            lines.append(f"- {cls}: " + "; ".join(keep))
    return "\n".join(lines)


def save_suite(ctx, phase: str, suite: list[dict]) -> Path:
    folder = ctx.phase_dir(phase)
    path = folder / "test-cases.json"
    path.write_text(json.dumps(suite, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (folder / "test-cases.md").write_text(suite_markdown(suite, f"Test cases ({phase} code)"), encoding="utf-8")
    return path


def _cell(text: str) -> str:
    return " ".join(str(text).split()).replace("|", "\\|")


def suite_markdown(suite: list[dict], title: str, with_code: bool = True) -> str:
    counts: dict[str, int] = {}
    for c in suite:
        counts[c.get("technique", "?")] = counts.get(c.get("technique", "?"), 0) + 1
    out = [f"# {title}", "", f"{len(suite)} distinct test cases: "
           + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())), "",
           "| Id | Technique | Method | Condition | Input | Expected |", "|---|---|---|---|---|---|"]
    for c in suite:
        tid = c["id"] + (f" (also {', '.join(c['also'])})" if c.get("also") else "")
        out.append(f"| {_cell(tid)} | {_cell(c.get('technique', ''))} | {_cell(c.get('method', ''))} | "
                   f"{_cell(c.get('condition', ''))} | {_cell(c.get('input', ''))} | {_cell(c.get('expected', ''))} |")
    if with_code:
        out += ["", "## JUnit code", ""]
        for c in suite:
            out += [f"### {c['id']}", "", "```java", body_of(c.get("junit", "")), "```", ""]
    return "\n".join(out) + "\n"


def load_suite(path: Path) -> list[dict]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict):
        data = data.get("test_cases", [])
    suite = []
    for c in data:
        if not isinstance(c, dict) or not c.get("id") or not c.get("junit"):
            continue
        suite.append({k: c.get(k, "") for k in ("id", "technique", "method", "condition", "input", "expected", "junit")}
                     | {"also": list(c.get("also", []))})
    return suite


def apply_test_edits(ctx, phase: str, agent: str, suite: list[dict], edits: list[TestEdit],
                     round_no: int | None) -> tuple[list[dict], int]:
    by_id = {c["id"]: c for c in suite}
    changed = 0
    for edit in edits:
        case = by_id.get(edit.id)
        if case is None:
            ctx.tracer.event(agent, "warning", f"edit for unknown test id {edit.id} ignored")
            continue
        before = json.dumps(case, indent=1, ensure_ascii=False)
        if edit.action == "delete":
            suite = [c for c in suite if c["id"] != edit.id]
            by_id.pop(edit.id, None)
            after = ""
        else:
            if not body_of(edit.junit).strip():
                ctx.tracer.event(agent, "warning", f"modify for {edit.id} has no code; ignored")
                continue
            case["junit"] = body_of(edit.junit)
            if edit.expected.strip():
                case["expected"] = edit.expected.strip()
            after = json.dumps(case, indent=1, ensure_ascii=False)
        ctx.changes.record(phase=phase, agent=agent, kind="test case", target=edit.id, action=edit.action,
                           reason=edit.reason, before=before, after=after, round_no=round_no)
        changed += 1
    return suite, changed


def apply_code_edits(ctx, phase: str, agent: str, project: Path, package: str, edits: list[CodeEdit],
                     round_no: int | None, allow_new_files: bool) -> int:
    current = workspace.read_sources(project)
    changed = 0
    for edit in edits:
        name = Path(edit.file).name
        if not name.endswith(".java"):
            name = f"{name}.java"
        declared = javasrc.primary_type(edit.content)
        if declared and f"{declared}.java" != name and f"{declared}.java" in current:
            name = f"{declared}.java"
        if name not in current and not allow_new_files:
            ctx.tracer.event(agent, "warning", f"code edit for unknown file {name} ignored")
            continue
        before = current.get(name, "")
        after = edit.content.rstrip() + "\n"
        if before.strip() == after.strip():
            continue
        workspace.write_sources(project, package, {name: after})
        ctx.changes.record(phase=phase, agent=agent, kind="code", target=name,
                           action="modify" if before else "add", reason=edit.reason, before=before, after=after,
                           round_no=round_no)
        current[name] = after
        changed += 1
    return changed
