"""Steps 1-3: the EP and BVA agents run in parallel, then the curator joins and de-duplicates."""
from __future__ import annotations

import json
from pathlib import Path

from ..agents import test_designers
from ..tools import dedup, workspace
from .common import note, notes_for, save_suite


def _design(state: dict, ctx, technique: str) -> dict:
    node = f"{technique.lower()}_agent"
    files = workspace.read_sources(Path(state["projects"]["original"]))
    draft = test_designers.design(ctx, technique, state["class_name"], state.get("package", ""), files,
                                  state.get("observed", ""), notes_for(state, "original"))
    cases = []
    for case in draft.test_cases:
        data = case.model_dump()
        data["technique"] = technique  # the agent's technique, whatever the model wrote
        cases.append(data)
    folder = ctx.phase_dir("original") / "test-design"
    folder.mkdir(exist_ok=True)
    (folder / f"{technique.lower()}-cases.json").write_text(json.dumps(cases, indent=2, ensure_ascii=False) + "\n",
                                                            encoding="utf-8")
    (folder / f"{technique.lower()}-analysis.md").write_text(
        f"# {technique} analysis\n\n{draft.analysis.strip()}\n", encoding="utf-8")
    ctx.tracer.event(node, "result", f"{len(cases)} {technique} test cases")
    return {
        f"{technique.lower()}_cases": cases,
        f"{technique.lower()}_analysis": draft.analysis,
        "events": [note(node, f"{technique} agent designed {len(cases)} test cases")],
    }


def ep_agent(state: dict, ctx) -> dict:
    return _design(state, ctx, "EP")


def bva_agent(state: dict, ctx) -> dict:
    return _design(state, ctx, "BVA")


def curate(state: dict, ctx) -> dict:
    """Join point of the two parallel agents: one set of distinct test cases."""
    suite, report = dedup.curate(state.get("ep_cases", []), state.get("bva_cases", []))
    folder = ctx.phase_dir("original") / "test-design"
    folder.mkdir(exist_ok=True)
    lines = ["# Curation (join + duplicate elimination)", "",
             f"- EP agent: {report['ep_received']} test cases", f"- BVA agent: {report['bva_received']} test cases",
             f"- duplicates removed: {len(report['duplicates'])}", f"- distinct test cases: {report['distinct']}",
             f"- conflicts for the reviewer: {len(report['conflicts'])}", ""]
    if report["duplicates"]:
        lines += ["## Duplicates removed", "", "| Kept | Removed | Rule |", "|---|---|---|"]
        lines += [f"| {d['kept']} | {d['removed']} | {d['rule']} |" for d in report["duplicates"]]
    if report["conflicts"]:
        lines += ["", "## Conflicts (same input, different expected result)", ""]
        lines += [f"- {c['kept']} expects `{c['expected_kept']}` but {c['new']} expects `{c['expected_new']}` "
                  f"for input `{c['input']}`" for c in report["conflicts"]]
    if report["dropped"]:
        lines += ["", "## Dropped", ""] + [f"- {d['id']}: {d['reason']}" for d in report["dropped"]]
    (folder / "curation.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (folder / "curation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    save_suite(ctx, "original", suite)
    message = (f"Curator joined {report['ep_received']} EP + {report['bva_received']} BVA test cases: "
               f"{len(report['duplicates'])} duplicate{'s' if len(report['duplicates']) != 1 else ''} removed, "
               f"{report['distinct']} distinct"
               + (f", {len(report['conflicts'])} conflicts for review" if report["conflicts"] else ""))
    ctx.tracer.event("curate", "result", message)
    return {"suite": suite, "curation": report, "events": [note("curate", message)]}
