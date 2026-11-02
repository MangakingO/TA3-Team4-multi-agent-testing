"""Setup node: reads SeasonSwitch.java, builds the Maven project, compiles it, runs the program to
observe its behaviour (What To Do, item 2) and analyses what makes it hard to test."""
from __future__ import annotations

import json
import re
from pathlib import Path

from ..tools import javap, javasrc, maven, runjava, testability, workspace
from .common import note


def _api_from_source(source: str) -> list[str]:
    """Fallback when javap is unavailable: public members as written in the source."""
    code = javasrc.strip_comments(source)
    found = re.findall(r"^\s*(public\s+[^;{=]*?\([^)]*\))\s*(?:throws[^{]*)?\{", code, re.M)
    return sorted({" ".join(sig.split()) for sig in found})


def setup_sut(state: dict, ctx) -> dict:
    s = ctx.settings
    sut = Path(state["sut_file"])
    source = sut.read_text(encoding="utf-8")
    package = javasrc.package_of(source)
    class_name = javasrc.primary_type(source, sut.stem)
    fq = javasrc.fqcn(package, class_name)
    has_main = javasrc.has_main(source)

    original = ctx.phase_dir("original")
    project = workspace.create_project(original / "project", s.pom_template, fq)
    workspace.write_sources(project, package, {f"{class_name}.java": source}, replace=True)

    compiled = maven.compile_only(project, s, ctx.run_dir / "logs" / "maven-original-compile.log")
    if not compiled["ok"]:
        raise RuntimeError(f"{sut.name} does not compile with Maven:\n" + (
            "\n".join(f"{e['file']}:{e['line']} {e['message']}" for e in compiled["compile_errors"])
            or compiled["log_tail"]))
    classes = project / "target" / "classes"

    try:
        api = javap.public_api(classes, fq)
    except (FileNotFoundError, OSError):
        api = _api_from_source(source)

    analysis = testability.analyze(source, classes, class_name, package)
    (original / "testability.md").write_text(analysis.to_markdown(), encoding="utf-8")

    observed, runs = "", []
    if has_main:
        arg_sets = state.get("observe_args") or s.observe_args or [[]]
        runs = [runjava.run(classes, fq, list(args), s.observe_seconds) for args in arg_sets]
        observed = runjava.describe(runs, fq, s.observe_seconds)
        (original / "observe.md").write_text(
            "# Observed behaviour\n\nThe program compiled with `mvn compile` and run with `java` "
            f"(each run stopped after {s.observe_seconds:g} s if it was still running).\n\n```\n"
            + observed + "\n```\n", encoding="utf-8")
        (original / "observe.json").write_text(json.dumps(runs, indent=2) + "\n", encoding="utf-8")

    info = {"class": fq, "package": package or "(default)", "has_main": has_main, "public_api": api}
    (original / "sut.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    findings = [x for x, on in (("static state", analysis.static_fields), ("timers", analysis.timers),
                                ("console output", analysis.prints), ("System.exit", analysis.exits),
                                ("endless wait in main", analysis.blocks)) if on]
    ctx.tracer.event("setup", "result", f"{fq}: {len(api)} public members; testability: "
                     + (", ".join(findings) or "nothing special"), api=api)
    return {
        "class_name": class_name,
        "package": package,
        "has_main": has_main,
        "original_api": api,
        "observed": observed,
        "testability": {"original": analysis.as_dict()},
        "projects": {"original": str(project)},
        "phase": "original",
        "run_counter": {},
        "review_round": 0,
        "repair_round": 0,
        "idle_repairs": 0,
        "coverage_round": 0,
        "refactor_attempt": 0,
        "refactor_round": 0,
        "refactor_prompt_version": 1,
        "events": [note("setup", f"Loaded {fq}; compiled with Maven"
                         + (f"; ran it {len(runs)} times to observe it (original/observe.md)" if runs else "")
                         + f"; testability: {', '.join(findings) or 'nothing special'}")],
    }
