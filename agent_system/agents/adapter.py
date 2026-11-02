"""Test adapter agent (LLM): makes the existing test cases compile against the refactored code (step 10)."""
from __future__ import annotations

from ..prompts import load, schema_text
from ..schemas import RepairPlan
from .common import sources_block


def adapt(ctx, class_name: str, files: dict[str, str], problems: list[tuple[dict, list[dict]]],
          notes: str = "") -> RepairPlan:
    text = []
    for case, errors in problems:
        text += [f"### {case['id']}", "```java", case.get("junit", "").strip(), "```"]
        text += [f"- line {e['line']}: {e['message']}" for e in errors]
    system, user = load("adapt_tests", ctx.settings.prompts_dir).render(
        class_name=class_name,
        sources=sources_block(files),
        problems="\n".join(text),
        notes=notes,
        schema=schema_text(RepairPlan),
    )
    return ctx.llm.json("test_adapter", system, user, RepairPlan)
