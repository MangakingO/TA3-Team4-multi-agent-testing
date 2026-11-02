"""Steps 7, 11 and 12: measure branch coverage with JaCoCo; if it is below 100 %, add test cases."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from ..agents import test_designers
from ..tools import dedup, jacoco, workspace
from .common import note, notes_for, save_suite


def measure_coverage(state: dict, ctx) -> dict:
    phase = state["phase"]
    project = Path(state["projects"][phase])
    n = state.get("run_counter", {}).get(phase, 0)
    xml = project / "target" / "site" / "jacoco" / "jacoco.xml"
    coverage = dict(state.get("coverage", {}))
    if not xml.exists():
        coverage[phase] = None
        ctx.tracer.event("measure_coverage", "warning", f"no JaCoCo report at {xml}")
        return {"coverage": coverage, "events": [note("measure_coverage", "No JaCoCo report was produced")]}

    summary = jacoco.summarize(jacoco.parse(xml))
    out_dir = ctx.phase_dir(phase) / "coverage" / f"run-{n:02d}"
    shutil.copytree(xml.parent, out_dir / "jacoco", dirs_exist_ok=True)
    title = f"Coverage of the {phase} code (test run {n})"
    (out_dir / "summary.md").write_text(jacoco.to_markdown(summary, title), encoding="utf-8")
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    summary["run"] = n
    summary["report_dir"] = str(out_dir)
    coverage[phase] = summary

    b, ln = summary["branch"], summary["line"]
    message = (f"Branch coverage ({phase}): {b['covered']}/{b['total']} = {b['pct']}%"
               + (" (no branches in the code)" if summary["branch_vacuous"] else "")
               + f"; line coverage {ln['pct']}%")
    ctx.tracer.event("measure_coverage", "result", message, report=str(out_dir))
    # tests are green here, so the repair budget starts again
    return {"coverage": coverage, "repair_round": 0, "idle_repairs": 0,
            "events": [note("measure_coverage", message)]}


def coverage_gap(state: dict, ctx) -> dict:
    phase = state["phase"]
    project = Path(state["projects"][phase])
    round_no = state.get("coverage_round", 0) + 1
    summary = state["coverage"][phase]
    files = workspace.read_sources(project)
    draft = test_designers.coverage_tests(ctx, state["class_name"], phase, files, summary, state["suite"],
                                          notes_for(state, phase))
    incoming = [dict(c.model_dump(), technique="COVERAGE") for c in draft.test_cases]
    suite, report = dedup.merge(state["suite"], incoming, "COVERAGE")
    for test_id in report["added"]:
        case = next(c for c in suite if c["id"] == test_id)
        ctx.changes.record(phase=phase, agent="coverage agent", kind="test case", target=test_id, action="add",
                           reason=f"targets a branch JaCoCo reported as never taken: {case.get('condition', '')}",
                           after=json.dumps(case, indent=1, ensure_ascii=False), round_no=round_no)
    save_suite(ctx, phase, suite)
    message = (f"Coverage agent (round {round_no}): {len(report['added'])} new test case(s)"
               f"{', ' + str(len(report['duplicates'])) + ' duplicate(s) dropped' if report['duplicates'] else ''}")
    ctx.tracer.event("coverage_gap", "result", message)
    return {"suite": suite, "coverage_round": round_no, "events": [note("coverage_gap", message)]}
