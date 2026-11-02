"""End-to-end tests of the LangGraph workflow with a scripted model and real Maven, JUnit and JaCoCo."""
from __future__ import annotations

import os
import shutil
from pathlib import Path

import pytest
from langgraph.types import Command

from agent_system import graph as G
from agent_system.config import Settings
from agent_system.context import Context
from tests.fakes import SCRIPT, ScriptedLLM

FIXTURE = Path(__file__).parent / "fixtures" / "FanSwitch.java"
needs_maven = pytest.mark.skipif(shutil.which(os.environ.get("MVN", "mvn")) is None, reason="Maven is not installed")


def _setup(tmp_path, provider="ollama", script=None):
    settings = Settings.from_env()
    settings.runs_dir = tmp_path / "runs"
    settings.llm_provider = provider
    run_dir = settings.runs_dir / "test-run"
    run_dir.mkdir(parents=True)
    ctx = Context.create(settings, run_dir)
    if provider != "manual":
        ctx.llm = ScriptedLLM(settings, ctx.tracer, script)
    app = G.build(ctx, G.sqlite_checkpointer(run_dir))
    sut = tmp_path / "FanSwitch.java"
    shutil.copy(FIXTURE, sut)
    config = {"configurable": {"thread_id": "test-run"}, "recursion_limit": 200}
    first = {"run_id": "test-run", "run_dir": str(run_dir), "sut_file": str(sut), "events": []}
    return settings, ctx, app, config, first, run_dir


def _drive(app, config, first, answers):
    pending, seen = first, []
    while True:
        for _ in app.stream(pending, config, stream_mode="updates"):
            pass
        snapshot = app.get_state(config)
        if not snapshot.interrupts:
            return snapshot, seen
        resume = {}
        for intr in snapshot.interrupts:
            seen.append(intr.value["kind"])
            resume[intr.id] = answers.pop(0)
        pending = Command(resume=resume)


@needs_maven
def test_full_workflow(tmp_path):
    settings, ctx, app, config, first, run_dir = _setup(tmp_path)
    snapshot, seen = _drive(app, config, first,
                            ["revise: add a test that pulls the chain when the fan is at HIGH", "accept", "accept"])
    v = snapshot.values

    # human in the loop: one round of requested changes, then accept; then the refactoring review
    assert seen == ["review_tests", "review_tests", "review_refactoring"]

    # the EP and BVA agents ran in the same step (parallel), and the join waited for both
    assert any(set(s.next) == {"ep_agent", "bva_agent"} for s in app.get_state_history(config))

    # the curator removed the BVA test that duplicates EP-02 (same code, other variable name)
    assert v["curation"]["ep_received"] == 5 and v["curation"]["bva_received"] == 6
    assert {"kept": "EP-02", "removed": "BVA-05", "rule": "identical test code"} in v["curation"]["duplicates"]

    ids = [c["id"] for c in v["suite"]]
    assert "REVIEW-01" in ids and "COV-01" in ids and "BVA-05" not in ids

    # the seeded fault was found by BVA-06/REVIEW-01 and fixed in the code, with a change-log entry
    changes = ctx.changes.entries()
    assert any(c["kind"] == "code" and c["agent"] == "repair agent" and c["phase"] == "original" for c in changes)
    assert "speed = 0;" in (run_dir / "original" / "project" / "src" / "main" / "java" / "FanSwitch.java").read_text()

    # 100% branch coverage on both versions (main() needed the coverage agent's extra test)
    assert v["coverage"]["original"]["branch"]["pct"] == 100.0
    assert v["coverage"]["refactored"]["branch"]["pct"] == 100.0
    assert v["run_counter"]["original"] == 3  # failing run, run after the repair, run with COV-01

    # the refactoring passed the State pattern checks and the reused tests
    report = v["pattern_report"]
    assert report["passed"] is True
    assert report["state_type"] == "FanState" and report["concrete_states"] == ["High", "Low", "Off"]
    assert v["phase"] == "refactored" and v["last_run"]["status"] == "passed"
    assert v["last_run"]["counts"]["passed"] == len(v["suite"])

    # deliverables
    final = run_dir / "final"
    for path in ["summary.md", "changes.md", "original/test-cases.json", "original/src/test/java/FanSwitchTest.java",
                 "original/coverage/jacoco/jacoco.xml", "refactored/src/main/java/FanState.java",
                 "refactored/test-results/summary.md", "refactored/coverage/summary.md"]:
        assert (final / path).exists(), path
    assert "BRANCH" in (final / "original" / "coverage" / "jacoco" / "jacoco.xml").read_text()
    trace = (run_dir / "trace.log").read_text()
    assert "[ep_agent] llm_call" in trace and "[bva_agent] llm_call" in trace
    assert (final / "original" / "pom.xml").exists() and (final / "refactored" / "src" / "test" / "java" / "TestSupport.java").exists()

    # prompt lab (step 8): score a reply to P3 on the tested original code with the accepted tests
    from agent_system.prompt_lab import run_lab
    settings.llm_provider = "manual"
    lab = settings.runs_dir.parent / "prompt-lab" / "fan-lab"
    (lab / "P3").mkdir(parents=True)
    (lab / "P3" / "reply.md").write_text(SCRIPT["refactor_agent"][0])
    assert run_lab(settings, "test-run", ["P3", "P0"], "fan-lab", echo=lambda *_: None) == 0
    results = __import__("json").loads((lab / "results.json").read_text())["results"]
    p3, p0 = results
    assert p3["compiles"] and p3["checks_passed"] == p3["checks_total"] == 8
    assert p3["tests_compile"] == p3["tests_total"] == p3["tests_passed"] == 12
    assert p0 == {"version": "P0", "pending": True} and (lab / "P0" / "prompt.md").exists()

    # a run folder copied to another computer still rebuilds final/ (report) from its own files
    from agent_system.nodes.finalize import finalize
    moved = tmp_path / "elsewhere" / "runs" / "test-run"
    shutil.copytree(run_dir, moved)
    shutil.rmtree(run_dir)
    moved_settings = Settings.from_env()
    moved_settings.runs_dir = moved.parent
    finalize(v, Context.create(moved_settings, moved))
    for path in ["summary.md", "original/src/main/java/FanSwitch.java", "refactored/src/test/java/FanSwitchTest.java",
                 "refactored/coverage/jacoco/jacoco.xml", "original/test-results/summary.md"]:
        assert (moved / "final" / path).exists(), path
    assert not (moved / "final.new").exists()


@needs_maven
def test_manual_provider_pauses_for_both_parallel_agents(tmp_path):
    """LLM_PROVIDER=manual: each prompt becomes a pause; the reply is given back on resume."""
    settings, ctx, app, config, first, run_dir = _setup(tmp_path, provider="manual")
    for _ in app.stream(first, config, stream_mode="updates"):
        pass
    pending = app.get_state(config).interrupts
    assert sorted(i.value["role"] for i in pending) == ["bva_agent", "ep_agent"]
    for intr in pending:
        assert Path(intr.value["prompt_file"]).exists()
        assert "FanSwitch" in Path(intr.value["prompt_file"]).read_text()
    replies = {i.id: SCRIPT[i.value["role"]][0] for i in pending}
    for _ in app.stream(Command(resume=replies), config, stream_mode="updates"):
        pass
    snapshot = app.get_state(config)
    assert [i.value["kind"] for i in snapshot.interrupts] == ["review_tests"]
    assert snapshot.values["curation"]["distinct"] == 10
    # the resumed nodes reused their prompt files instead of writing new ones
    assert len(list((run_dir / "llm").glob("*.prompt.md"))) == 2
    assert len(list((run_dir / "llm").glob("*.response.md"))) == 2
