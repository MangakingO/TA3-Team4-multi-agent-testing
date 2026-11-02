"""Refactoring agent (LLM): applies the State pattern using the team's final prompt (step 8)."""
from __future__ import annotations

from pathlib import Path

from ..prompts import PromptTemplate
from .common import package_text, sources_block


def feedback_block(team_feedback: str = "", compile_errors: list[dict] | None = None,
                   previous_files: dict[str, str] | None = None) -> str:
    parts = []
    if team_feedback.strip():
        parts.append("The team reviewed your previous refactoring and asks for these changes:\n"
                     + team_feedback.strip())
    if compile_errors:
        parts.append("Your previous refactoring did not compile. Fix these errors and return all files again:\n"
                     + "\n".join(f"- {e['file']} line {e['line']}: {e['message']}" for e in compile_errors))
    if parts and previous_files:
        parts.append("Your previous refactoring:\n" + sources_block(previous_files, numbered=True))
    return ("\n" + "\n\n".join(parts) + "\n") if parts else ""


def refactor(ctx, prompt_path: Path, class_name: str, package: str, original_files: dict[str, str],
             api: list[str], feedback: str, members: str = "", notes: str = "") -> str:
    template = PromptTemplate(prompt_path)
    source = "\n\n".join(code.rstrip() for code in original_files.values())
    system, user = template.render(
        class_name=class_name,
        package_text=package_text(package),
        source=source,
        api="\n".join(f"- {m}" for m in api) or "- (see the code)",
        feedback=feedback,
        members=members or "(see the code)",
        notes=notes,
    )
    return ctx.llm.text("refactor_agent", system, user)
