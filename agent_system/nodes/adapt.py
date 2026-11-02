"""Step 10: reuse the accepted test cases on the refactored code. Tests that no longer compile are
adapted (same behaviour checked) or, when they only checked an implementation detail the
refactoring removed, deleted; every change is logged with its reason."""
from __future__ import annotations

import json
from pathlib import Path

from ..agents import adapter
from ..tools import junit, maven, testability, workspace
from .common import apply_test_edits, note, notes_for, save_suite


def adapt_tests(state: dict, ctx) -> dict:
    project = Path(state["projects"]["refactored"])
    package, class_name = state.get("package", ""), state["class_name"]
    suite = [dict(c) for c in state["suite"]]
    # the final suite of the original code, kept as the baseline for the refactored code
    (ctx.phase_dir("refactored") / "test-cases-from-original.json").write_text(
        json.dumps(suite, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    analyses = dict(state.get("testability") or {})

    changed_total, deleted_total = 0, 0
    for attempt in range(1, 4):
        source, line_map = junit.render(class_name, package, suite, ctx.settings.test_timeout_seconds)
        workspace.write_test_class(project, package, class_name, source)
        check = maven.compile_only(project, ctx.settings,
                                   ctx.run_dir / "logs" / f"maven-adapt-test-compile-{attempt}.log", with_tests=True)
        classes = project / "target" / "classes"
        if attempt == 1 and classes.exists():
            sources = workspace.read_sources(project)
            found = testability.analyze("\n\n".join(sources.values()), classes, class_name, package)
            analyses["refactored"] = found.as_dict()
            (ctx.phase_dir("refactored") / "testability.md").write_text(found.to_markdown(), encoding="utf-8")
        test_errors = [e for e in check["compile_errors"] if e["scope"] == "test"]
        if check["ok"] or not test_errors:
            break  # compiles (main-code errors, if any, go to the repair agent after the test run)
        problems: dict[str, list[dict]] = {}
        for e in test_errors:
            test_id = junit.blame(line_map, e["line"])
            if test_id:
                problems.setdefault(test_id, []).append(e)
        if not problems:
            break
        by_id = {c["id"]: c for c in suite}
        plan = adapter.adapt(ctx, class_name, workspace.read_sources(project),
                             [(by_id[t], errs) for t, errs in problems.items() if t in by_id],
                             notes_for({"testability": analyses}, "refactored"))
        before = len(suite)
        suite, changed = apply_test_edits(ctx, "refactored", "test adapter", suite, plan.test_edits, attempt)
        deleted_total += before - len(suite)
        changed_total += changed
        if changed == 0:
            break

    save_suite(ctx, "refactored", suite)
    if changed_total:
        message = (f"Test adapter: reused {len(suite)} test cases on the refactored code; "
                   f"{changed_total - deleted_total} adapted, {deleted_total} removed as obsolete")
    else:
        message = f"Test adapter: all {len(suite)} test cases compile unchanged against the refactored code"
    ctx.tracer.event("adapt_tests", "result", message)
    return {"phase": "refactored", "suite": suite, "adapted": True, "repair_round": 0, "idle_repairs": 0,
            "coverage_round": 0, "testability": analyses, "events": [note("adapt_tests", message)]}
