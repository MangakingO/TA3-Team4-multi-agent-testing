"""Test-design agents (LLM):

* EP agent  - equivalence partitioning      } run in parallel (steps 1-2)
* BVA agent - boundary value analysis       }
* Reviser   - applies the human tester's requested changes (step 4)
* Coverage agent - adds tests for branches JaCoCo reports as never taken (steps 7 and 12)
"""
from __future__ import annotations

from ..prompts import load, schema_text
from ..schemas import SuiteRevision, TestSuiteDraft
from ..tools import jacoco
from .common import observed_block, package_text, sources_block, suite_lines

TEMPLATES = {"EP": "ep_agent", "BVA": "bva_agent"}


def _plain(files: dict[str, str]) -> str:
    if len(files) == 1:
        return next(iter(files.values())).rstrip()
    return "\n\n".join(f"// ===== {name} =====\n{code.rstrip()}" for name, code in files.items())


def design(ctx, technique: str, class_name: str, package: str, files: dict[str, str], observed: str,
           notes: str = "") -> TestSuiteDraft:
    name = TEMPLATES[technique]
    system, user = load(name, ctx.settings.prompts_dir).render(
        class_name=class_name,
        package_text=package_text(package),
        source=_plain(files),
        observed=observed_block(observed),
        notes=notes,
        schema=schema_text(TestSuiteDraft),
    )
    return ctx.llm.json(name, system, user, TestSuiteDraft)


def revise(ctx, class_name: str, files: dict[str, str], suite: list[dict], feedback: str,
           notes: str = "") -> SuiteRevision:
    system, user = load("revise_tests", ctx.settings.prompts_dir).render(
        class_name=class_name,
        source=_plain(files),
        suite=suite_lines(suite),
        feedback=feedback.strip() or "(no feedback given)",
        notes=notes,
        schema=schema_text(SuiteRevision),
    )
    return ctx.llm.json("reviser", system, user, SuiteRevision)


def coverage_tests(ctx, class_name: str, phase: str, files: dict[str, str], summary: dict,
                   suite: list[dict], notes: str = "") -> TestSuiteDraft:
    marks = {name: jacoco.marks_for_file(summary, name) for name in files}
    missed = [f"- {b['file']} line {b['line']}: {b['missed']} of {b['missed'] + b['covered']} branches never taken"
              for b in summary.get("missed_branches", [])]
    missed += [f"- {ln['file']} line {ln['line']}: never executed" for ln in summary.get("missed_lines", [])[:30]]
    existing = "\n".join(f"- {c['id']}: {c.get('condition', '')} | {c.get('input', '')}" for c in suite)
    system, user = load("coverage_tests", ctx.settings.prompts_dir).render(
        class_name=class_name,
        phase=phase,
        sources=sources_block(files, marks),
        missed="\n".join(missed) or "(none listed)",
        existing=existing or "(none)",
        notes=notes,
        schema=schema_text(TestSuiteDraft),
    )
    return ctx.llm.json("coverage_agent", system, user, TestSuiteDraft)
