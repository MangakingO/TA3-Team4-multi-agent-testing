"""Repair agent (LLM): decides whether the code or the test is wrong and proposes the fix (steps 6 and 10)."""
from __future__ import annotations

from ..prompts import load, schema_text
from ..schemas import RepairPlan
from .common import sources_block

POLICIES = {
    "original": (
        "Phase policy (original code): the test cases were designed from the intended behaviour of the "
        "class. If the code does not do what its comments, names and observed behaviour say it should, "
        "the CODE is wrong: fix it with the smallest change. If a test expects the wrong result, sets up "
        "the object wrongly, or has a Java mistake, the TEST is wrong: fix the test."
    ),
    "refactored": (
        "Phase policy (refactored code): these same tests passed on the original code, so they define the "
        "required behaviour. A failing test means the refactoring changed the behaviour: fix the refactored "
        "CODE and keep the State pattern intact. Change a test only if it cannot compile because it used "
        "something that no longer exists, and keep what it checks."
    ),
}


def _failure_text(case: dict | None, result: dict) -> str:
    lines = [f"### {result.get('id')} ({result['status']})"]
    if case:
        lines += [f"technique: {case.get('technique', '')}", f"condition: {case.get('condition', '')}",
                  f"input: {case.get('input', '')}", f"expected: {case.get('expected', '')}",
                  "junit:", "```java", case.get("junit", "").strip(), "```"]
    lines.append(f"failure: {result.get('type', '')} {result.get('message', '')}".strip())
    if result.get("trace"):
        lines += ["trace:", result["trace"]]
    return "\n".join(lines)


def _compile_text(errors: list[dict], suite_by_id: dict[str, dict]) -> str:
    if not errors:
        return "(none)"
    out = []
    for e in errors:
        where = f"{e['file']} line {e['line']}"
        if e.get("test_id"):
            where += f" (inside test {e['test_id']})"
        out.append(f"- [{e['scope']}] {where}: {e['message']}")
    blamed = sorted({e["test_id"] for e in errors if e.get("test_id")})
    for test_id in blamed:
        case = suite_by_id.get(test_id)
        if case:
            out += [f"\n{test_id} code:", "```java", case.get("junit", "").strip(), "```"]
    return "\n".join(out)


def propose(ctx, phase: str, class_name: str, files: dict[str, str], failing: list[dict],
            compile_errors: list[dict], suite: list[dict], passing: int, notes: str = "") -> RepairPlan:
    by_id = {c["id"]: c for c in suite}
    failures = "\n\n".join(_failure_text(by_id.get(r.get("id")), r) for r in failing) or "(none)"
    system, user = load("repair_agent", ctx.settings.prompts_dir).render(
        class_name=class_name,
        phase=phase,
        policy=POLICIES[phase],
        sources=sources_block(files),
        failures=failures,
        compile_errors=_compile_text(compile_errors, by_id),
        passing=str(passing),
        notes=notes,
        schema=schema_text(RepairPlan),
    )
    return ctx.llm.json("repair_agent", system, user, RepairPlan)
