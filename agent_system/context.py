"""Everything a node needs besides the graph state: settings, the model, the trace and change log."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .config import Settings
from .llm import LLM
from .tools.changelog import ChangeLog
from .tracing import Tracer


@dataclass
class Context:
    settings: Settings
    llm: LLM
    tracer: Tracer
    changes: ChangeLog
    run_dir: Path

    @classmethod
    def create(cls, settings: Settings, run_dir: Path, llm: LLM | None = None, echo=None) -> "Context":
        run_dir = Path(run_dir)
        tracer = Tracer(run_dir, echo=echo)
        return cls(settings, llm or LLM(settings, tracer), tracer, ChangeLog(run_dir), run_dir)

    def phase_dir(self, phase: str) -> Path:
        path = self.run_dir / phase
        path.mkdir(parents=True, exist_ok=True)
        return path
