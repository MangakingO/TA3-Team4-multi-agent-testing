"""Step 8: the refactoring agent applies the State pattern with the team's final prompt;
the result is compiled and checked against the pattern before the team reviews it (step 9)."""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from ..agents import refactorer
from ..tools import javasrc, maven, pattern_check, refactor_parse, testability, workspace
from .common import members_text, note


def _check(state: dict, ctx, project: Path) -> tuple[dict, dict]:
    log = ctx.run_dir / "logs" / f"maven-refactored-compile-{state.get('refactor_attempt', 0)}.log"
    compiled = maven.compile_only(project, ctx.settings, log)
    report: dict = {"checks": [], "passed": False, "classes": []}
    if compiled["ok"]:
        fq = javasrc.fqcn(state.get("package", ""), state["class_name"])
        report = pattern_check.check(Path(state["projects"]["original"]) / "target" / "classes", fq,
                                     project / "target" / "classes", fq,
                                     "\n".join(c.get("junit", "") for c in state.get("suite", [])))
    folder = ctx.phase_dir("refactored")
    (folder / "pattern-check.md").write_text(
        "# State pattern checks\n\n" + ("Compiles: yes\n\n" if compiled["ok"] else "Compiles: NO\n\n")
        + pattern_check.to_markdown(report), encoding="utf-8")
    (folder / "pattern-check.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return compiled, report


def refactor(state: dict, ctx) -> dict:
    s = ctx.settings
    attempt = state.get("refactor_attempt", 0) + 1
    original = Path(state["projects"]["original"])
    original_files = workspace.read_sources(original)  # the version that passed every test
    compile_errors, previous = None, None
    if attempt > 1 and not state.get("refactor_compile", {}).get("ok", True) and state.get("projects", {}).get("refactored"):
        compile_errors = state["refactor_compile"].get("compile_errors") or [
            {"file": "?", "line": 0, "message": state["refactor_compile"].get("log_tail", "")[-800:]}]
        previous = workspace.read_sources(Path(state["projects"]["refactored"]))
    team_feedback = state.get("refactor_feedback", "") if state.get("refactor_action") == "feedback" else ""
    if team_feedback and previous is None and state.get("projects", {}).get("refactored"):
        previous = workspace.read_sources(Path(state["projects"]["refactored"]))
    feedback = refactorer.feedback_block(team_feedback, compile_errors, previous)

    # the members to keep come from the code as it is now (repairs may have changed it since setup)
    analyses = dict(state.get("testability") or {})
    classes = original / "target" / "classes"
    if classes.exists():
        analyses["original"] = testability.analyze("\n\n".join(original_files.values()), classes,
                                                  state["class_name"], state.get("package", "")).as_dict()
    reply = refactorer.refactor(ctx, s.refactor_prompt, state["class_name"], state.get("package", ""),
                                original_files, state.get("original_api", []), feedback,
                                members_text({"testability": analyses}, "\n".join(c.get("junit", "") for c in state.get("suite", []))))
    files = refactor_parse.parse_files(reply)
    design = refactor_parse.design_notes(reply)

    folder = ctx.phase_dir("refactored")
    version = state.get("refactor_prompt_version", 1)
    shutil.copyfile(s.refactor_prompt, folder / f"prompt-v{version}.md")
    attempt_dir = folder / "attempts" / f"v{version}-attempt-{attempt}"
    attempt_dir.mkdir(parents=True, exist_ok=True)
    (attempt_dir / "reply.md").write_text(reply, encoding="utf-8")
    (folder / "design.md").write_text(f"# Design notes from the refactoring agent\n\n{design}\n", encoding="utf-8")

    fq = javasrc.fqcn(state.get("package", ""), state["class_name"])
    project = workspace.create_project(folder / "project", s.pom_template, fq)
    if files:
        workspace.write_sources(project, state.get("package", ""), files, replace=True)
    for name, code in files.items():
        (attempt_dir / name).write_text(code, encoding="utf-8")

    if f"{state['class_name']}.java" not in files:
        compiled = {"ok": False, "compile_errors": [{
            "file": f"{state['class_name']}.java", "line": 0, "scope": "main",
            "message": "the reply has no file for the Subject class" if files else "the reply has no Java files"}],
            "log_tail": ""}
        report = {"checks": [], "passed": False, "classes": []}
    else:
        compiled, report = _check(state, ctx, project)

    projects = dict(state["projects"], refactored=str(project))
    status = "compiles" if compiled["ok"] else f"does not compile ({len(compiled['compile_errors'])} errors)"
    checks = "all State pattern checks pass" if report.get("passed") else "some pattern checks fail"
    message = f"Refactoring agent (prompt v{version}, attempt {attempt}): {len(files)} files, {status}"
    if compiled["ok"]:
        message += f", {checks}"
    ctx.tracer.event("refactor", "result", message, files=sorted(files))
    return {"projects": projects, "refactor_attempt": attempt, "refactor_design": design, "testability": analyses,
            "refactor_compile": compiled, "pattern_report": report, "events": [note("refactor", message)]}


def recheck_refactoring(state: dict, ctx) -> dict:
    """After the team edited the refactored code by hand: compile and check again."""
    project = Path(state["projects"]["refactored"])
    snapshot = ctx.phase_dir("refactored") / "review" / f"round-{state.get('refactor_round', 1)}.files.json"
    before = json.loads(snapshot.read_text(encoding="utf-8")) if snapshot.exists() else {}
    for name, code in workspace.read_sources(project).items():
        if before.get(name, "").strip() != code.strip():
            ctx.changes.record(phase="refactored", agent="team", kind="code", target=name,
                               action="modify" if name in before else "add",
                               reason="edited by hand during the refactoring review",
                               before=before.get(name, ""), after=code, round_no=state.get("refactor_round"))
    compiled, report = _check(state, ctx, project)
    message = ("Re-checked the hand-edited refactoring: "
               + ("compiles" if compiled["ok"] else "does not compile")
               + (", pattern checks pass" if report.get("passed") else ""))
    return {"refactor_compile": compiled, "pattern_report": report, "events": [note("recheck_refactoring", message)]}
