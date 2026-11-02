"""The orchestrator: a LangGraph StateGraph that wires the agents into the 12-step process.

    setup -> (EP agent || BVA agent) -> curate -> review_tests <-> revise_tests
          -> run_tests <-> repair -> measure_coverage <-> coverage_gap      (original code)
          -> refactor -> review_refactoring <-> refactor / recheck_refactoring
          -> adapt_tests -> run_tests <-> repair -> measure_coverage <-> coverage_gap (refactored code)
          -> finalize

The process is fixed by the assignment, so the routing is deterministic (conditional edges on
the state), and only the creative work (designing tests, diagnosing failures, refactoring)
is done by language models. Every node is checkpointed, so a run can pause for people and resume.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph

from .context import Context
from .nodes import adapt, coverage, design, finalize, refactoring, review, setup, testing
from .state import AgentState

NODES = {
    "setup": setup.setup_sut,
    "ep_agent": design.ep_agent,
    "bva_agent": design.bva_agent,
    "curate": design.curate,
    "review_tests": review.review_tests,
    "revise_tests": review.revise_tests,
    "run_tests": testing.run_tests,
    "repair": testing.repair,
    "measure_coverage": coverage.measure_coverage,
    "coverage_gap": coverage.coverage_gap,
    "refactor": refactoring.refactor,
    "review_refactoring": review.review_refactoring,
    "recheck_refactoring": refactoring.recheck_refactoring,
    "adapt_tests": adapt.adapt_tests,
    "ask_human": review.ask_human,
    "finalize": finalize.finalize,
}


# ------------------------------------------------------------------ routing (conditional edges)
def after_review(state: AgentState) -> str:
    return "revise_tests" if state.get("review_action") == "revise" else "run_tests"


def make_after_run(ctx: Context):
    def after_run(state: AgentState) -> str:
        status = state["last_run"]["status"]
        if status == "passed":
            return "measure_coverage"
        if status in ("build_error", "timeout", "no_tests"):
            return "ask_human"
        if state.get("repair_round", 0) >= ctx.settings.max_repair_rounds or state.get("idle_repairs", 0) >= 2:
            return "ask_human"
        return "repair"

    return after_run


def make_after_coverage(ctx: Context):
    def after_coverage(state: AgentState) -> str:
        phase = state["phase"]
        cov = (state.get("coverage") or {}).get(phase)
        if cov is None:
            return "ask_human"
        if cov["branch"]["pct"] >= ctx.settings.target_branch_coverage:
            return "refactor" if phase == "original" else "finalize"
        if state.get("coverage_round", 0) >= ctx.settings.max_coverage_rounds:
            return "ask_human"
        return "coverage_gap"

    return after_coverage


def make_after_refactor(ctx: Context):
    def after_refactor(state: AgentState) -> str:
        if not state.get("refactor_compile", {}).get("ok") and state.get("refactor_attempt", 0) < ctx.settings.max_refactor_attempts:
            return "refactor"  # send the compiler errors back to the refactoring agent
        return "review_refactoring"

    return after_refactor


def after_refactor_review(state: AgentState) -> str:
    return {"accept": "adapt_tests", "code_edited": "recheck_refactoring"}.get(state.get("refactor_action"), "refactor")


def after_ask_human(state: AgentState) -> str:
    return "finalize" if state.get("halted") else "run_tests"


# ------------------------------------------------------------------ wiring
def build(ctx: Context, checkpointer=None):
    graph = StateGraph(AgentState)
    for name, fn in NODES.items():
        graph.add_node(name, _bind(ctx, name, fn))

    graph.add_edge(START, "setup")
    graph.add_edge("setup", "ep_agent")             # fork: the two test-design agents
    graph.add_edge("setup", "bva_agent")            #       run in parallel
    graph.add_edge(["ep_agent", "bva_agent"], "curate")  # join: waits for both
    graph.add_edge("curate", "review_tests")
    graph.add_conditional_edges("review_tests", after_review, ["run_tests", "revise_tests"])
    graph.add_edge("revise_tests", "review_tests")
    graph.add_conditional_edges("run_tests", make_after_run(ctx), ["measure_coverage", "repair", "ask_human"])
    graph.add_edge("repair", "run_tests")
    graph.add_conditional_edges("measure_coverage", make_after_coverage(ctx),
                                ["refactor", "finalize", "coverage_gap", "ask_human"])
    graph.add_edge("coverage_gap", "run_tests")
    graph.add_conditional_edges("refactor", make_after_refactor(ctx), ["refactor", "review_refactoring"])
    graph.add_conditional_edges("review_refactoring", after_refactor_review,
                                ["adapt_tests", "refactor", "recheck_refactoring"])
    graph.add_edge("recheck_refactoring", "review_refactoring")
    graph.add_edge("adapt_tests", "run_tests")
    graph.add_conditional_edges("ask_human", after_ask_human, ["run_tests", "finalize"])
    graph.add_edge("finalize", END)
    return graph.compile(checkpointer=checkpointer)


def _bind(ctx: Context, name: str, fn):
    traced = ctx.tracer.node(name)

    @traced
    def node(state: AgentState) -> dict:
        return fn(state, ctx)

    node.__name__ = name
    return node


def sqlite_checkpointer(run_dir: Path) -> SqliteSaver:
    conn = sqlite3.connect(str(Path(run_dir) / "checkpoints.sqlite"), check_same_thread=False)
    return SqliteSaver(conn)
