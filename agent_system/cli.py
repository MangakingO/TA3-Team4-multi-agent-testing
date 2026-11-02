"""Command line for the multi-agent testing and refactoring system.

  python -m agent_system run path/to/SeasonSwitch.java      start a run (interactive reviews)
  python -m agent_system resume <run-id>                     continue a paused run
  python -m agent_system status <run-id>                     where a run is and what it waits for
  python -m agent_system list                                all runs

Options for run/resume:
  --auto-approve      accept the test cases and the refactoring without asking (unattended demo)
  --no-wait           never prompt: use answer files if present, otherwise pause and exit
  --answer TEXT       answer for the pending review, e.g. "accept" or "revise: add a test for ..."
  --observe ARGS      command-line arguments for one observation run of main(); repeat it for more runs,
                      e.g. --observe "" --observe cool --observe heat --observe off --observe warm
"""
from __future__ import annotations

import argparse
import json
import shlex
import sys
from datetime import datetime
from pathlib import Path

from langgraph.types import Command

from . import graph as graph_module
from .config import Settings
from .context import Context
from .llm import check_ollama
from .tools.maven import MavenNotFound, resolve_mvn


def _print(text: str) -> None:
    print(text, flush=True)


# ------------------------------------------------------------------ answering pauses
def _read_block(prompt: str) -> str:
    _print(prompt + " (finish with an empty line)")
    lines = []
    while True:
        try:
            line = input()
        except EOFError:
            break
        if not line.strip():
            break
        lines.append(line)
    return "\n".join(lines)


def _answer_file(value: dict) -> Path | None:
    review = value.get("review_file")
    return Path(review + ".answer.txt") if review else None


def _answer(value: dict, args, used: list[Path]) -> object | None:
    kind = value.get("kind")
    if kind == "llm":
        response = Path(value["response_file"])
        if response.exists() and response.read_text(encoding="utf-8").strip():
            return response.read_text(encoding="utf-8")
        if args.no_wait:
            return None
        _print(f"\nThe {value['role']} needs a model reply (LLM_PROVIDER=manual).\n"
               f"  1. Open   {value['prompt_file']}\n  2. Paste it into your LLM (any web chat)\n"
               f"  3. Save the reply as {response}\n  then press Enter (or q to pause).")
        while True:
            if input("> ").strip().lower() == "q":
                return None
            if response.exists() and response.read_text(encoding="utf-8").strip():
                return response.read_text(encoding="utf-8")
            _print(f"Still no reply in {response}.")

    if args.answer:
        answer, args.answer = args.answer, None
        return answer
    if args.auto_approve:
        return "stop" if kind == "stuck" else "accept"
    file = _answer_file(value)
    if file and file.exists() and file.read_text(encoding="utf-8").strip():
        used.append(file)
        return file.read_text(encoding="utf-8").strip()
    if args.no_wait:
        return None

    if kind == "review_tests":
        _print(f"\n=== Review the test cases (round {value['round']}) ===\n"
               f"{value['count']} distinct test cases"
               + (f", {value['conflicts']} conflicts to decide" if value.get("conflicts") else "")
               + f".\nRead {value['review_file']}\nThe suite is in {value['suite_file']}")
        choice = input("[a]ccept, [r]equest changes, [e]dited the JSON file, [q]uit and resume later: ").strip().lower()
        if choice.startswith("r"):
            return {"action": "revise", "feedback": _read_block("Describe the changes")}
        if choice.startswith("e"):
            return {"action": "edited", "feedback": input("What did you change? ")}
        if choice.startswith("q"):
            return None
        return {"action": "accept"}
    if kind == "review_refactoring":
        _print(f"\n=== Review the refactored code (round {value['round']}) ===\n"
               f"Compiles: {'yes' if value['compiles'] else 'NO'}; pattern checks: "
               f"{'pass' if value.get('checks_passed') else 'see the report'}\n"
               f"Read {value['review_file']}\nCode: {value['project']}/src/main/java\n"
               f"Prompt: {value['prompt_file']}")
        choice = input("[a]ccept, [f]eedback and regenerate, [p]rompt edited - regenerate, "
                       "[c]ode edited - re-check, [q]uit: ").strip().lower()
        if choice.startswith("f"):
            return {"action": "feedback", "feedback": _read_block("What should change")}
        if choice.startswith("p"):
            return {"action": "prompt_edited"}
        if choice.startswith("c"):
            return {"action": "code_edited"}
        if choice.startswith("q"):
            return None
        return {"action": "accept"}
    if kind == "stuck":
        _print(f"\n=== A decision is needed ===\n{value['reason']}\n"
               f"You may edit {value.get('suite_file')} or the code first.")
        choice = input("[c]ontinue for more rounds, [s]top and summarize, [q]uit: ").strip().lower()
        if choice.startswith("q"):
            return None
        return "continue" if choice.startswith("c") else "stop"
    return None


def _pending_text(value: dict, run_id: str) -> str:
    if value.get("kind") == "llm":
        return (f"- {value['role']} waits for a model reply: put it in {value['response_file']} "
                f"(prompt: {value['prompt_file']})")
    file = _answer_file(value)
    return f"- {value.get('kind')}: read {value.get('review_file')} and write your answer into {file}"


# ------------------------------------------------------------------ driving the graph
def drive(app, config: dict, first_input, args) -> int:
    pending = first_input
    while True:
        try:
            for _ in app.stream(pending, config, stream_mode="updates"):
                pass
        except KeyboardInterrupt:
            _print("\nInterrupted. The last finished step is saved; continue with: "
                   f"python -m agent_system resume {config['configurable']['thread_id']}")
            return 130
        snapshot = app.get_state(config)
        if not snapshot.interrupts:
            if snapshot.next:
                _print(f"Stopped before {snapshot.next}; run resume to continue.")
                return 1
            return 0
        answers, used = {}, []
        for intr in snapshot.interrupts:
            answer = _answer(intr.value, args, used)
            if answer is None:
                run_id = config["configurable"]["thread_id"]
                _print("\nPaused. Waiting for:")
                for i in snapshot.interrupts:
                    _print(_pending_text(i.value, run_id))
                _print(f"Continue with: python -m agent_system resume {run_id}")
                return 2
            answers[intr.id] = answer
        for f in used:
            f.rename(f.with_name(f.name.replace(".answer.txt", ".answer.used.txt")))
        pending = Command(resume=answers)


def _open(settings: Settings, run_dir: Path, echo=True):
    ctx = Context.create(settings, run_dir, echo=_print if echo else None)
    app = graph_module.build(ctx, graph_module.sqlite_checkpointer(run_dir))
    return ctx, app


def _preflight(settings: Settings) -> str | None:
    try:
        resolve_mvn(settings)
    except MavenNotFound as exc:
        return str(exc)
    return check_ollama(settings)


def cmd_run(args, settings: Settings) -> int:
    sut = Path(args.sut).resolve()
    if not sut.exists():
        _print(f"No such file: {sut}")
        return 1
    problem = None if args.skip_checks else _preflight(settings)
    if problem:
        _print(problem)
        return 1
    run_id = args.run_id or datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = settings.runs_dir / run_id
    if (run_dir / "checkpoints.sqlite").exists():
        _print(f"Run {run_id} already exists; use resume.")
        return 1
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "run.json").write_text(json.dumps({
        "run_id": run_id, "sut": str(sut), "started": datetime.now().isoformat(timespec="seconds"),
        "llm": settings.describe(), "refactor_prompt": str(settings.refactor_prompt),
        "maven_args": settings.maven_args, "pom_template": str(settings.pom_template),
        "observe": args.observe or [" ".join(a) for a in settings.observe_args]}, indent=2) + "\n",
        encoding="utf-8")
    _print(f"Run {run_id}: {sut.name} with {settings.describe()}\nOutputs: {run_dir}")
    _ctx, app = _open(settings, run_dir)
    config = {"configurable": {"thread_id": run_id}, "recursion_limit": 200}
    first = {"run_id": run_id, "run_dir": str(run_dir), "sut_file": str(sut),
             "observe_args": [shlex.split(a) for a in args.observe] if args.observe else None, "events": []}
    code = drive(app, config, first, args)
    if code == 0:
        _print(f"\nDone. Summary: {run_dir / 'summary.md'}")
    return code


def cmd_resume(args, settings: Settings) -> int:
    run_dir = settings.runs_dir / args.run_id
    if not (run_dir / "checkpoints.sqlite").exists():
        _print(f"No run {args.run_id} in {settings.runs_dir}")
        return 1
    problem = None if args.skip_checks else _preflight(settings)
    if problem:
        _print(problem)
        return 1
    _ctx, app = _open(settings, run_dir)
    config = {"configurable": {"thread_id": args.run_id}, "recursion_limit": 200}
    snapshot = app.get_state(config)
    if not snapshot.next and not snapshot.interrupts:
        _print(f"Run {args.run_id} has finished. Summary: {run_dir / 'summary.md'}")
        return 0
    # nothing to answer (stopped by an error or Ctrl+C): re-run the pending step
    code = drive(app, config, None, args)
    if code == 0:
        _print(f"\nDone. Summary: {run_dir / 'summary.md'}")
    return code


def cmd_status(args, settings: Settings) -> int:
    run_dir = settings.runs_dir / args.run_id
    if not (run_dir / "checkpoints.sqlite").exists():
        _print(f"No run {args.run_id}")
        return 1
    _ctx, app = _open(settings, run_dir, echo=False)
    snapshot = app.get_state({"configurable": {"thread_id": args.run_id}})
    v = snapshot.values
    _print(f"Run {args.run_id}: phase {v.get('phase')}, {len(v.get('suite', []))} test cases")
    if v.get("last_run"):
        _print(f"Last test run: {v['last_run'].get('status')} {v['last_run'].get('counts', '')}")
    for phase, cov in (v.get("coverage") or {}).items():
        if cov:
            _print(f"Branch coverage ({phase}): {cov['branch']['pct']}%")
    _print("Next step: " + (", ".join(snapshot.next) if snapshot.next else "none (finished)"))
    for intr in snapshot.interrupts:
        _print(_pending_text(intr.value, args.run_id))
    for event in v.get("events", [])[-8:]:
        _print(f"  {event.get('node')}: {event.get('message')}")
    return 0


def cmd_report(args, settings: Settings) -> int:
    """Rewrite summary.md and final/ from the run's last saved state (for example after editing files)."""
    run_dir = settings.runs_dir / args.run_id
    if not (run_dir / "checkpoints.sqlite").exists():
        _print(f"No run {args.run_id}")
        return 1
    ctx, app = _open(settings, run_dir, echo=False)
    from .nodes.finalize import finalize

    finalize(app.get_state({"configurable": {"thread_id": args.run_id}}).values, ctx)
    _print(f"Wrote {run_dir / 'summary.md'} and {run_dir / 'final'}")
    return 0


def cmd_list(args, settings: Settings) -> int:
    if not settings.runs_dir.exists():
        _print("No runs yet.")
        return 0
    for run in sorted(p for p in settings.runs_dir.iterdir() if (p / "run.json").exists()):
        info = json.loads((run / "run.json").read_text(encoding="utf-8"))
        done = "finished" if (run / "summary.md").exists() else "in progress"
        _print(f"{run.name}  {Path(info['sut']).name}  {info['llm']}  {done}")
    return 0


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(prog="python -m agent_system", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("run", "resume"):
        p = sub.add_parser(name)
        if name == "run":
            p.add_argument("sut", help="path to the Java file under test, e.g. season-switch/src/main/java/cse4392/SeasonSwitch.java")
            p.add_argument("--run-id")
            p.add_argument("--observe", action="append", metavar="ARGS",
                           help="arguments for one observation run of main() (repeatable; \"\" = no arguments)")
        else:
            p.add_argument("run_id")
        p.add_argument("--auto-approve", action="store_true")
        p.add_argument("--no-wait", action="store_true")
        p.add_argument("--answer")
        p.add_argument("--skip-checks", action="store_true", help="skip the Maven/Ollama checks")
    sub.add_parser("status").add_argument("run_id")
    sub.add_parser("report", help="rewrite summary.md and final/ of a run").add_argument("run_id")
    sub.add_parser("list")
    lab = sub.add_parser("lab", help="score the refactoring prompt versions on a run's tested code (step 8)")
    lab.add_argument("run_id")
    lab.add_argument("--versions", nargs="+", help="e.g. P0 P1 P2 (default: all in prompts/refactor_versions)")
    lab.add_argument("--lab-id", help="folder name under prompt-lab/ (default: the run id)")
    args = parser.parse_args(argv)
    settings = Settings.from_env()
    if args.command == "lab":
        from .prompt_lab import run_lab

        return run_lab(settings, args.run_id, args.versions, args.lab_id, _print)
    return {"run": cmd_run, "resume": cmd_resume, "status": cmd_status, "list": cmd_list,
            "report": cmd_report}[args.command](args, settings)
