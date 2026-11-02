"""Last node: writes summary.md and copies the deliverables of each phase into final/.

final/original and final/refactored are each a complete Maven project (pom.xml, the code, the JUnit
test class and its TestSupport helper) plus the test cases, the last test run's results and the
JaCoCo coverage report, so anyone can rerun a phase with `mvn test`.
"""
from __future__ import annotations

import json
import re
import shutil
from datetime import datetime
from pathlib import Path

from ..tools import javasrc, pattern_check
from .common import load_suite, note


def _pct(cov: dict | None, kind: str) -> str:
    if not cov:
        return "n/a"
    f = cov[kind]
    return f"{f['covered']}/{f['total']} ({f['pct']}%)" if f["total"] else "no branches (100%)"


def _techniques(suite: list[dict]) -> str:
    counts: dict[str, int] = {}
    for c in suite:
        counts[c.get("technique", "?")] = counts.get(c.get("technique", "?"), 0) + 1
    return ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))


def _llm_used(ctx) -> str:
    """The language model recorded when the run started (run.json), so a later `report` keeps it."""
    info = ctx.run_dir / "run.json"
    if info.exists():
        try:
            return json.loads(info.read_text(encoding="utf-8")).get("llm") or ctx.settings.describe()
        except ValueError:
            pass
    return ctx.settings.describe()


def _in_run(ctx, saved: str) -> Path:
    """A path saved in the run's state. If the run folder was moved (copied to another computer, unzipped
    elsewhere), the same file is looked up inside the current run folder."""
    path = Path(saved)
    if path.exists():
        return path
    parts = path.parts
    run_id = ctx.run_dir.name
    if run_id in parts:
        i = len(parts) - 1 - parts[::-1].index(run_id)
        return ctx.run_dir.joinpath(*parts[i + 1:])
    return path


def _copy_phase(ctx, state: dict, phase: str, dest: Path) -> list[str]:
    project = state.get("projects", {}).get(phase)
    if not project:
        return []
    project = _in_run(ctx, project)
    dest.mkdir(parents=True, exist_ok=True)
    pom_source = ctx.settings.delivery_pom if ctx.settings.delivery_pom.exists() else project / "pom.xml"
    fq = javasrc.fqcn(state.get("package", ""), state["class_name"])
    pom = re.sub(r"<exec\.mainClass>.*?</exec\.mainClass>", f"<exec.mainClass>{fq}</exec.mainClass>",
                 pom_source.read_text(encoding="utf-8"))
    (dest / "pom.xml").write_text(pom, encoding="utf-8")
    for sub in ("src/main/java", "src/test/java"):
        if (project / sub).exists():
            shutil.copytree(project / sub, dest / sub, dirs_exist_ok=True)
    copied = [f"{phase}/pom.xml + src/main/java + src/test/java (a Maven project: run `mvn test` in {phase}/)"]
    phase_dir = ctx.run_dir / phase
    for name in ("test-cases.json", "test-cases.md"):
        if (phase_dir / name).exists():
            shutil.copyfile(phase_dir / name, dest / name)
            copied.append(f"{phase}/{name}")
    runs = sorted((phase_dir / "test-results").glob("run-*")) if (phase_dir / "test-results").exists() else []
    if runs:
        shutil.copytree(runs[-1], dest / "test-results", dirs_exist_ok=True)
        copied.append(f"{phase}/test-results (last test run, {runs[-1].name})")
    cov = (state.get("coverage") or {}).get(phase)
    report_dir = _in_run(ctx, cov["report_dir"]) if cov and cov.get("report_dir") else None
    if report_dir and report_dir.exists():
        shutil.copytree(report_dir, dest / "coverage", dirs_exist_ok=True)
        copied.append(f"{phase}/coverage (JaCoCo HTML, XML and CSV; summary.md)")
    return copied


def finalize(state: dict, ctx) -> dict:
    # Build final/ next to the old one and swap at the end, so a failure never leaves final/ half-written.
    final = ctx.run_dir / "final"
    building = ctx.run_dir / "final.new"
    if building.exists():
        shutil.rmtree(building)
    building.mkdir()
    copied = _copy_phase(ctx, state, "original", building / "original")
    copied += _copy_phase(ctx, state, "refactored", building / "refactored")
    for name in ("changes.md", "trace.log"):
        if (ctx.run_dir / name).exists():
            shutil.copyfile(ctx.run_dir / name, building / name)
            copied.append(name)

    curation = state.get("curation", {})
    changes = ctx.changes.entries()

    def count(phase, kind, agent=None, action=None):
        return sum(1 for c in changes if c["phase"] == phase and c["kind"] == kind
                   and (agent is None or c["agent"] == agent) and (action is None or c["action"] == action))

    suite = state.get("suite", [])
    original_file = ctx.run_dir / "original" / "test-cases.json"
    original_suite = load_suite(original_file) if original_file.exists() else []
    cov = state.get("coverage") or {}
    runs = state.get("run_counter", {})
    report = state.get("pattern_report") or {}
    status = "stopped by a person" if state.get("halted") else "completed"
    lines = [
        f"# Run {state.get('run_id')} summary", "",
        "| Item | Value |", "|---|---|",
        f"| System under test | {state.get('class_name')} ({state.get('package') or 'default package'}) |",
        f"| Language model | {_llm_used(ctx)} |",
        f"| Finished | {datetime.now():%Y-%m-%d %H:%M} ({status}) |", "",
        "## Test design (steps 1-4)", "",
        f"- EP agent: {curation.get('ep_received', 0)} test cases; BVA agent: {curation.get('bva_received', 0)} "
        "(the two agents ran in parallel)",
        f"- Duplicates removed at the join: {len(curation.get('duplicates', []))}; distinct test cases: "
        f"{curation.get('distinct', 0)}",
        f"- Human review rounds: {state.get('review_round', 0)}; test cases added by the review: "
        f"{count('original', 'test case', 'reviser', 'add')}", "",
        "## Original code (steps 5-7)", "",
        f"- Test cases: {len(original_suite)} ({_techniques(original_suite)})",
        f"- Test runs: {runs.get('original', 0)}",
        f"- Repairs: {count('original', 'code')} code change(s), {count('original', 'test case', 'repair agent')} "
        "test change(s)",
        f"- Test cases added for coverage: {count('original', 'test case', 'coverage agent')}",
        f"- Branch coverage: {_pct(cov.get('original'), 'branch')}; line coverage: {_pct(cov.get('original'), 'line')}",
        "",
        "## Refactoring with the State pattern (steps 8-9)", "",
        f"- Prompt: `{ctx.settings.refactor_prompt.name}` (version {state.get('refactor_prompt_version', 1)}); "
        f"attempts: {state.get('refactor_attempt', 0)}; team review rounds: {state.get('refactor_round', 0)}",
        "", pattern_check.to_markdown(report) if report else "(not reached)", "",
        "## Refactored code (steps 10-12)", "",
        f"- Test cases reused from the original code: {len(original_suite)}; adapted: "
        f"{count('refactored', 'test case', 'test adapter', 'modify')}; removed as obsolete: "
        f"{count('refactored', 'test case', 'test adapter', 'delete')}",
        f"- Test cases: {len(suite)} ({_techniques(suite)})",
        f"- Test runs: {runs.get('refactored', 0)}",
        f"- Repairs: {count('refactored', 'code')} code change(s), "
        f"{count('refactored', 'test case', 'repair agent')} test change(s)",
        f"- Test cases added for coverage: {count('refactored', 'test case', 'coverage agent')}",
        f"- Branch coverage: {_pct(cov.get('refactored'), 'branch')}; line coverage: "
        f"{_pct(cov.get('refactored'), 'line')}", "",
        "## Files in final/", "",
    ] + [f"- {c}" for c in copied]
    if state.get("stuck_reason"):
        lines += ["", f"Last escalation: {state['stuck_reason']}"]
    text = "\n".join(lines) + "\n"
    (building / "summary.md").write_text(text, encoding="utf-8")
    if final.exists():
        shutil.rmtree(final)
    building.rename(final)
    (ctx.run_dir / "summary.md").write_text(text, encoding="utf-8")
    (ctx.run_dir / "result.json").write_text(json.dumps({
        "run_id": state.get("run_id"), "status": status, "coverage": {
            k: {"branch": v["branch"], "line": v["line"]} for k, v in cov.items() if v},
        "test_runs": runs, "tests_original": len(original_suite), "tests_refactored": len(suite),
        "pattern_checks_passed": report.get("passed")}, indent=2) + "\n", encoding="utf-8")
    message = f"Run {status}. Summary: {ctx.run_dir / 'summary.md'}"
    ctx.tracer.event("finalize", "result", message)
    return {"finished": True, "events": [note("finalize", message)]}
