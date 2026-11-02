"""Prompt templates live in agent_system/prompts/*.md.

Each file has a "### SYSTEM" section and a "### USER" section. Placeholders use $name
(string.Template), so Java braces and JSON examples in a template need no escaping.
"""
from __future__ import annotations

import json
from pathlib import Path
from string import Template

from pydantic import BaseModel

PROMPTS_DIR = Path(__file__).resolve().parent / "prompts"


class PromptTemplate:
    def __init__(self, path: Path):
        self.path = Path(path)
        text = self.path.read_text(encoding="utf-8")
        if "### SYSTEM" not in text or "### USER" not in text:
            raise ValueError(f"{self.path} needs a '### SYSTEM' and a '### USER' section")
        head, _, rest = text.partition("### SYSTEM")
        system, _, user = rest.partition("### USER")
        self.header = head.strip()  # version notes, ignored by the model
        self.system = system.strip()
        self.user = user.strip()

    def render(self, **values: object) -> tuple[str, str]:
        clean = {k: ("" if v is None else str(v)) for k, v in values.items()}
        return (Template(self.system).safe_substitute(clean), Template(self.user).safe_substitute(clean))


def load(name: str, prompts_dir: Path | None = None) -> PromptTemplate:
    return PromptTemplate((prompts_dir or PROMPTS_DIR) / f"{name}.md")


def schema_text(schema: type[BaseModel]) -> str:
    """The JSON Schema of an output model, shown to the model in the prompt."""
    return json.dumps(schema.model_json_schema(), indent=1)
