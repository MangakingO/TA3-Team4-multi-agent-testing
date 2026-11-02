"""Steps 5-6 and 10: run the test cases with Maven and, when tests fail, repair the code or the tests."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from ..agents import repair as repair_agent
from ..tools import junit, maven, workspace
from .common import apply_code_edits, apply_test_edits, note, notes_for, save_suite


def _results_markdown(result: dict, phase: str, n: int, suite: list[dict]) -> str:
    by_id = {c["id"]: c for c in suite}
    out = [f"# Test run {n} ({phase} code)", "", f"Status: **{result['status']}**  ",
           f"Maven exit code {result['exit_code']}, {result['seconds']} s. Full log: `maven.log`.", ""]
    if result.get("counts"):
        c = result["counts"]
        out += [f"Tests run: {sum(c.values())}, passed {c['passed']}, failed {c['failed']}, "
                f"errors {c['error']}, skipped {c['skipped']}", ""]
    if result["tests"]:
        out += ["| Test | Technique | Result | Message |", "|---|---|---|---|"]
        for t in result["tests"]:
            tech = by_id.get(t["id"], {}).get("technique", "")
            msg = " ".join(t.get("message", "").split()).replace("|", "\\|")[:160]
            out.append(f"| {t['id']} | {tech} | {t['status']} | {msg} |")
    if result["compile_errors"]:
        out += ["", "## Compile errors", ""]
        for e in result["compile_errors"]:
            where = f" (test {e['test_id']})" if e.get("test_id") else ""
            out.append(f"- [{e['scope']}] {e['file']} line {e['line']}{where}: {e['message']}")
    if result.get("log_tail"):
        out += ["", "## End of the Maven log", "", "```", result["log_tail"], "```"]
    return "\n".join(out) + "\n"


def run_tests(state: dict, ctx) -> dict:
    phase = state["phase"]
    project = Path(state["projects"][phase])
    suite = state["suite"]
    source, line_map = junit.render(state["class_name"], state.get("package", ""), suite,
                                    ctx.settings.test_timeout_seconds)
    workspace.write_test_class(project, state.get("package", ""), state["class_name"], source)

    counter = dict(state.get("run_counter", {}))
    counter[phase] = counter.get(phase, 0) + 1
    n = counter[phase]
    out_dir = ctx.phase_dir(phase) / "test-results" / f"run-{n:02d}"
    out_dir.mkdir(parents=True, exist_ok=True)
    result = maven.run_tests(project, ctx.settings, out_dir / "maven.log")

    ids = {junit.method_name(c["id"]): c["id"] for c in suite}
    for t in result["tests"]:
        t["id"] = ids.get(t["method"], t["method"])
    for e in result["compile_errors"]:
        if e["scope"] == "test":
            e["test_id"] = junit.blame(line_map, e["line"])
    reports = project / "target" / "surefire-reports"
    if reports.exists():
        shutil.copytree(reports, out_dir / "surefire-reports", dirs_exist_ok=True)
    (out_dir / f"{state['class_name']}Test.java").write_text(source, encoding="utf-8")
    (out_dir / "summary.md").write_text(_results_markdown(result, phase, n, suite), encoding="utf-8")
    (out_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    save_suite(ctx, phase, suite)

    if result["status"] == "compile_error":
        message = f"Test run {n} ({phase}): compile errors ({len(result['compile_errors'])})"
    elif result.get("counts"):
        c = result["counts"]
        message = (f"Test run {n} ({phase}): {c['passed']} passed, {c['failed']} failed, {c['error']} errors"
                   + (" -- all tests pass" if result["status"] == "passed" else ""))
    else:
        message = f"Test run {n} ({phase}): {result['status']}"
    result["run"] = n
    ctx.tracer.event("run_tests", "result", message, status=result["status"], results=str(out_dir))
    return {"last_run": result, "run_counter": counter, "events": [note("run_tests", message)]}


def repair(state: dict, ctx) -> dict:
    phase = state["phase"]
    project = Path(state["projects"][phase])
    round_no = state.get("repair_round", 0) + 1
    last = state["last_run"]
    failing = [t for t in last.get("tests", []) if t["status"] in ("failed", "error")]
    passing = sum(1 for t in last.get("tests", []) if t["status"] == "passed")
    files = workspace.read_sources(project)
    plan = repair_agent.propose(ctx, phase, state["class_name"], files, failing, last.get("compile_errors", []),
                                state["suite"], passing, notes_for(state, phase))
    agent = "repair agent"
    suite, test_changes = apply_test_edits(ctx, phase, agent, [dict(c) for c in state["suite"]], plan.test_edits,
                                           round_no)
    code_changes = apply_code_edits(ctx, phase, agent, project, state.get("package", ""), plan.code_edits,
                                    round_no, allow_new_files=(phase == "refactored"))
    save_suite(ctx, phase, suite)
    folder = ctx.phase_dir(phase) / "repairs"
    folder.mkdir(exist_ok=True)
    (folder / f"round-{round_no:02d}.json").write_text(plan.model_dump_json(indent=2), encoding="utf-8")
    idle = state.get("idle_repairs", 0) + (1 if test_changes + code_changes == 0 else 0)
    message = (f"Repair agent (round {round_no}): {code_changes} code edit(s), {test_changes} test edit(s). "
               f"Diagnosis: {' '.join(plan.diagnosis.split())[:300]}")
    ctx.tracer.event("repair", "decision", message)
    return {"suite": suite, "repair_round": round_no, "idle_repairs": idle,
            "events": [note("repair", message, code_edits=code_changes, test_edits=test_changes)]}
