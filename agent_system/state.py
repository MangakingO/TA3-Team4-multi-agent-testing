"""The shared state that flows through the LangGraph workflow (checkpointed after every step)."""
from __future__ import annotations

import operator
from typing import Annotated, TypedDict


class AgentState(TypedDict, total=False):
    # the system under test
    run_id: str
    run_dir: str
    sut_file: str
    observe_args: list[list[str]]      # command-line arguments for each observation run
    observed: str                      # what main() printed
    class_name: str
    package: str
    has_main: bool
    original_api: list[str]
    testability: dict[str, dict]      # phase -> testability analysis (notes for the prompts)
    projects: dict[str, str]          # phase -> Maven project folder
    phase: str                         # "original" or "refactored"

    # test design (steps 1-4)
    ep_cases: list[dict]
    bva_cases: list[dict]
    ep_analysis: str
    bva_analysis: str
    suite: list[dict]                  # the distinct test cases in use
    curation: dict                     # duplicates removed, conflicts
    review_round: int
    review_action: str
    review_feedback: str

    # testing and repair (steps 5-6, 10)
    last_run: dict
    run_counter: dict[str, int]
    repair_round: int
    idle_repairs: int
    adapted: bool

    # coverage (steps 7, 11-12)
    coverage: dict[str, dict]          # phase -> latest summary
    coverage_round: int

    # refactoring (steps 8-9)
    refactor_attempt: int
    refactor_round: int                # team review rounds
    refactor_prompt_version: int
    refactor_feedback: str
    refactor_design: str
    refactor_compile: dict
    pattern_report: dict
    refactor_action: str

    # escalation and the end
    stuck_reason: str
    stuck_action: str
    halted: bool
    finished: bool
    events: Annotated[list[dict], operator.add]
