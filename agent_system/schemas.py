"""Structured outputs the LLM agents must return (their JSON Schemas are given to the model)."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class TestCase(BaseModel):
    __test__ = False  # not a pytest test class

    id: str = Field(description="Unique id, e.g. EP-01, BVA-07 or COV-02")
    technique: str = Field(description="EP, BVA, COVERAGE or REVIEW")
    method: str = Field(description="Public method(s) or constructor under test, e.g. next()")
    condition: str = Field(description="The equivalence class or boundary this test exercises")
    input: str = Field(description="Concrete inputs: starting state plus the calls and argument values")
    expected: str = Field(description="Expected observable result (return value, state, output or exception)")
    junit: str = Field(description="Java statements for the body of one JUnit 5 test method")


class TestSuiteDraft(BaseModel):
    __test__ = False  # not a pytest test class

    analysis: str = Field(description="Short list of the equivalence classes or boundaries identified")
    test_cases: list[TestCase]


class SuiteRevision(BaseModel):
    summary: str = Field(description="What was changed and why")
    add: list[TestCase] = Field(default_factory=list, description="New test cases")
    modify: list[TestCase] = Field(default_factory=list, description="Replacements, same id as the test case they replace")
    delete: list[str] = Field(default_factory=list, description="Ids of test cases to remove")


class TestEdit(BaseModel):
    __test__ = False  # not a pytest test class

    id: str = Field(description="Id of the test case to change")
    action: Literal["modify", "delete"]
    expected: str = Field(default="", description="New expected result (modify only)")
    junit: str = Field(default="", description="New JUnit method body (modify only)")
    reason: str


class CodeEdit(BaseModel):
    file: str = Field(description="Source file name, e.g. SeasonSwitch.java")
    content: str = Field(description="The complete new content of that file")
    reason: str


class RepairPlan(BaseModel):
    diagnosis: str = Field(description="Root cause of each failure: is the code wrong or the test wrong, and why")
    test_edits: list[TestEdit] = Field(default_factory=list)
    code_edits: list[CodeEdit] = Field(default_factory=list)
