"""Human in the loop: the tester reviews the test cases (step 4), the team reviews the refactoring
(step 9), and a person decides when a loop does not converge. Each pause is a LangGraph interrupt;
the run is checkpointed, so it can be resumed later, even from another terminal."""
from __future__ import annotations

import json
from pathlib import Path

from langgraph.types import interrupt

from ..agents import test_designers
from ..tools import dedup, pattern_check, workspace
from .common import load_suite, note, notes_for, save_suite, suite_markdown

TEST_ACTIONS = {"accept": ("a", "accept", "ok", "yes", "y"), "revise": ("r", "revise", "changes", "change"),
                "edited": ("e", "edited", "edit")}
REFACTOR_ACTIONS = {"accept": ("a", "accept", "ok", "yes", "y"), "feedback": ("f", "feedback", "revise", "r"),
                    "prompt_edited": ("p", "prompt", "prompt_edited"), "code_edited": ("c", "code", "code_edited")}
STUCK_ACTIONS = {"continue": ("c", "continue", "retry"), "stop": ("s", "stop", "halt", "q")}


def parse_answer(answer, actions: dict[str, tuple[str, ...]]) -> tuple[str, str]:
    """Accepts {"action": ..., "feedback": ...} or text like "revise: add a test for null"."""
    if isinstance(answer, dict):
        word, feedback = str(answer.get("action", "")), str(answer.get("feedback", ""))
    else:
        text = str(answer or "").strip()
        word, _, feedback = text.partition(":")
        if not feedback and " " in word:
            word, _, feedback = word.partition(" ")
    word = word.strip().lower()
    for action, aliases in actions.items():
        if word in aliases:
            return action, feedback.strip()
    return next(iter(actions)), ""  # unknown answer: the first (safest) action


def _answer_hint(run_id: str, md: Path, options: str) -> str:
    return (f"\n---\nAnswer in the console ({options}). Without the console, write your answer into "
            f"`{md.name}.answer.txt` next to this file, then run `python -m agent_system resume {run_id}`.\n")


# ---------------------------------------------------------------- step 4
def review_tests(state: dict, ctx) -> dict:
    n = state.get("review_round", 0) + 1
    suite = state["suite"]
    curation = state.get("curation", {})
    json_path = save_suite(ctx, "original", suite)
    folder = ctx.phase_dir("original") / "review"
    folder.mkdir(exist_ok=True)
    md = folder / f"round-{n}.md"
    head = [f"# Test case review, round {n}", "",
            "Check that the test cases cover every equivalence class and boundary, that each expected "
            "result is right, and that nothing is missing. Request changes in plain words, or edit "
            f"`{json_path}` directly.", ""]
    if curation and n == 1:
        head += [f"EP agent {curation.get('ep_received', 0)} + BVA agent {curation.get('bva_received', 0)} "
                 f"test cases; duplicates removed: {len(curation.get('duplicates', []))} "
                 "(see test-design/curation.md).", ""]
        for c in curation.get("conflicts", []):
            head.append(f"- **Conflict:** {c['kept']} expects `{c['expected_kept']}`, {c['new']} expects "
                        f"`{c['expected_new']}` for `{c['input']}`. Which is right?")
        head.append("")
    md.write_text("\n".join(head) + suite_markdown(suite, "Test cases").replace("# Test cases", "## Test cases", 1)
                  + _answer_hint(state["run_id"], md, "a = accept, r = request changes, e = I edited test-cases.json"),
                  encoding="utf-8")

    answer = interrupt({"kind": "review_tests", "round": n, "review_file": str(md), "suite_file": str(json_path),
                        "count": len(suite), "conflicts": len(curation.get("conflicts", [])) if n == 1 else 0})
    action, feedback = parse_answer(answer, TEST_ACTIONS)
    update = {"review_round": n, "review_action": action, "review_feedback": feedback}
    if action == "edited":
        edited = load_suite(json_path)
        ctx.changes.record(phase="original", agent="human tester", kind="test suite", target="test-cases.json",
                           action="edit", reason=feedback or "edited by hand during review",
                           before=json.dumps(suite, indent=1, ensure_ascii=False),
                           after=json.dumps(edited, indent=1, ensure_ascii=False), round_no=n)
        update["suite"] = edited
    message = {"accept": f"Tester accepted {len(suite)} test cases",
               "revise": f"Tester requested changes: {feedback}",
               "edited": f"Tester edited test-cases.json ({len(update.get('suite', suite))} test cases)"}[action]
    ctx.tracer.event("review_tests", "decision", message, round=n)
    update["events"] = [note("review_tests", message)]
    return update


def revise_tests(state: dict, ctx) -> dict:
    suite = [dict(c) for c in state["suite"]]
    files = workspace.read_sources(Path(state["projects"]["original"]))
    revision = test_designers.revise(ctx, state["class_name"], files, suite, state.get("review_feedback", ""),
                                     notes_for(state, "original"))
    round_no = state.get("review_round")
    by_id = {c["id"]: c for c in suite}
    for test_id in revision.delete:
        if test_id in by_id:
            ctx.changes.record(phase="original", agent="reviser", kind="test case", target=test_id, action="delete",
                               reason=state.get("review_feedback", ""), before=json.dumps(by_id[test_id], indent=1),
                               round_no=round_no)
    suite = [c for c in suite if c["id"] not in set(revision.delete)]
    for case in revision.modify:
        data = case.model_dump()
        for i, existing in enumerate(suite):
            if existing["id"] == data["id"]:
                data["also"] = existing.get("also", [])
                ctx.changes.record(phase="original", agent="reviser", kind="test case", target=data["id"],
                                   action="modify", reason=state.get("review_feedback", ""),
                                   before=json.dumps(existing, indent=1), after=json.dumps(data, indent=1),
                                   round_no=round_no)
                suite[i] = data
                break
        else:
            revision.add.append(case)
    added = [dict(c.model_dump(), technique=c.technique or "REVIEW") for c in revision.add]
    suite, report = dedup.merge(suite, added, "REVIEW")
    for test_id in report["added"]:
        case = next(c for c in suite if c["id"] == test_id)
        ctx.changes.record(phase="original", agent="reviser", kind="test case", target=test_id, action="add",
                           reason=state.get("review_feedback", ""), after=json.dumps(case, indent=1),
                           round_no=round_no)
    save_suite(ctx, "original", suite)
    message = (f"Reviser: +{len(report['added'])} added, {len(revision.modify)} modified, "
               f"{len(revision.delete)} deleted ({len(suite)} test cases)")
    ctx.tracer.event("revise_tests", "result", message, summary=revision.summary)
    return {"suite": suite, "events": [note("revise_tests", message)]}


# ---------------------------------------------------------------- step 9
def review_refactoring(state: dict, ctx) -> dict:
    n = state.get("refactor_round", 0) + 1
    project = Path(state["projects"]["refactored"])
    folder = ctx.phase_dir("refactored") / "review"
    folder.mkdir(exist_ok=True)
    md = folder / f"round-{n}.md"
    report = state.get("pattern_report", {})
    compiled = state.get("refactor_compile", {})
    files = workspace.read_sources(project)
    body = [f"# Refactoring review, round {n}", "",
            f"Prompt: `{ctx.settings.refactor_prompt}` (version {state.get('refactor_prompt_version', 1)}), "
            f"attempt {state.get('refactor_attempt', 1)}.", "",
            "Review the code against the State pattern: Subject with the original interface, an abstract "
            "State, one concrete class per state, delegation, transitions, no conditionals on the state.", "",
            f"**Compiles:** {'yes' if compiled.get('ok') else 'NO'}", ""]
    for e in compiled.get("compile_errors", []):
        body.append(f"- {e['file']} line {e['line']}: {e['message']}")
    body += ["", "## Automated checks", "", pattern_check.to_markdown(report), "",
             "## The agent's design notes", "", state.get("refactor_design", "").strip() or "(none)", "",
             "## Files", ""]
    for name, code in files.items():
        body += [f"### {name}", "", "```java", code.rstrip(), "```", ""]
    (folder / f"round-{n}.files.json").write_text(json.dumps(files, indent=1) + "\n", encoding="utf-8")
    md.write_text("\n".join(body) + _answer_hint(
        state["run_id"], md, "a = accept, f = feedback (regenerate), p = I edited the prompt, "
                             "c = I edited the code"), encoding="utf-8")

    answer = interrupt({"kind": "review_refactoring", "round": n, "review_file": str(md),
                        "project": str(project), "prompt_file": str(ctx.settings.refactor_prompt),
                        "compiles": bool(compiled.get("ok")), "checks_passed": report.get("passed")})
    action, feedback = parse_answer(answer, REFACTOR_ACTIONS)
    update: dict = {"refactor_round": n, "refactor_action": action, "refactor_feedback": feedback}
    if action in ("feedback", "prompt_edited"):
        update["refactor_attempt"] = 0
        if action == "prompt_edited":
            update["refactor_prompt_version"] = state.get("refactor_prompt_version", 1) + 1
    message = {"accept": "Team accepted the refactored code",
               "feedback": f"Team asked for changes: {feedback}",
               "prompt_edited": "Team revised the refactoring prompt; regenerating",
               "code_edited": "Team edited the refactored code; re-checking"}[action]
    ctx.tracer.event("review_refactoring", "decision", message, round=n)
    update["events"] = [note("review_refactoring", message)]
    return update


# ---------------------------------------------------------------- loops that do not converge
def ask_human(state: dict, ctx) -> dict:
    phase = state.get("phase", "original")
    last = state.get("last_run", {})
    cov = state.get("coverage", {}).get(phase)
    if last.get("status") not in (None, "passed"):
        reason = (f"Tests on the {phase} code still {last.get('status')} after "
                  f"{state.get('repair_round', 0)} repair round(s).")
        if last.get("status") == "build_error":
            reason += " Maven itself failed; see " + str(last.get("maven_log"))
    elif cov is None:
        reason = "No JaCoCo report was produced (check the jacoco-maven-plugin in pom.xml)."
    else:
        reason = (f"Branch coverage of the {phase} code is {cov['branch']['pct']}% after "
                  f"{state.get('coverage_round', 0)} round(s) of extra tests. Some branches may be unreachable.")
    md = ctx.run_dir / f"decision-{phase}.md"
    md.write_text(f"# The agents need a decision\n\n{reason}\n\nYou can edit the code or "
                  f"`{phase}/test-cases.json` before continuing.\n"
                  + _answer_hint(state["run_id"], md, "c = continue for more rounds, s = stop and summarize"),
                  encoding="utf-8")
    answer = interrupt({"kind": "stuck", "reason": reason, "phase": phase, "review_file": str(md),
                        "suite_file": str(ctx.phase_dir(phase) / "test-cases.json")})
    action, feedback = parse_answer(answer, STUCK_ACTIONS)
    update = {"stuck_reason": reason, "stuck_action": action, "repair_round": 0, "idle_repairs": 0,
              "coverage_round": 0, "halted": action == "stop"}
    suite_file = ctx.phase_dir(phase) / "test-cases.json"
    if action == "continue" and suite_file.exists():
        update["suite"] = load_suite(suite_file)  # picks up hand edits
    message = f"Person chose to {action}: {reason}"
    ctx.tracer.event("ask_human", "decision", message)
    update["events"] = [note("ask_human", message)]
    return update
