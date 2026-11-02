"""Execution trace for a run: trace.log (readable), trace.jsonl (one JSON event per line),
and llm/ (every prompt sent to the model and every reply, one file each)."""
from __future__ import annotations

import functools
import json
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable


class Tracer:
    def __init__(self, run_dir: Path, echo: Callable[[str], None] | None = None):
        self.run_dir = Path(run_dir)
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.log_path = self.run_dir / "trace.log"
        self.jsonl_path = self.run_dir / "trace.jsonl"
        self.llm_dir = self.run_dir / "llm"
        self.llm_dir.mkdir(exist_ok=True)
        self._lock = threading.Lock()
        self._echo = echo
        self._llm_seq = len(list(self.llm_dir.glob("*.prompt.md")))
        self._paused: set[str] = set()

    # ------------------------------------------------------------------ events
    def event(self, node: str, kind: str, message: str = "", **data: Any) -> None:
        now = datetime.now()
        record = {
            "time": now.isoformat(timespec="milliseconds"),
            "node": node,
            "kind": kind,
            "thread": threading.current_thread().name,
            "message": message,
            **({"data": data} if data else {}),
        }
        line = f"{now:%Y-%m-%d %H:%M:%S.%f}"[:-3] + f" [{node}] {kind}: {message}"
        if data:
            compact = json.dumps(data, default=str, ensure_ascii=False)
            line += f"  {compact if len(compact) <= 600 else compact[:600] + '...'}"
        with self._lock:
            with self.log_path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")
            with self.jsonl_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record, default=str, ensure_ascii=False) + "\n")
        if self._echo:
            if kind in {"decision", "result", "warning"}:
                self._echo(f"  [{node}] {message}")
            elif kind == "node_start":
                if node in self._paused:
                    self._paused.discard(node)
                    self._echo(f"> {node} (resumed)")
                else:
                    self._echo(f"> {node}")
            elif kind == "node_paused":
                self._paused.add(node)
            elif kind == "llm_call":
                self._echo(f"  [{node}] waiting for the model ({message.split(' <- ')[0]})")

    # ------------------------------------------------------------------ LLM
    def next_llm_files(self, role: str) -> tuple[Path, Path]:
        with self._lock:
            self._llm_seq += 1
            seq = self._llm_seq
        stem = f"{seq:03d}-{role}"
        return self.llm_dir / f"{stem}.prompt.md", self.llm_dir / f"{stem}.response.md"

    @staticmethod
    def write_prompt(path: Path, role: str, model: str, system: str, user: str) -> None:
        path.write_text(
            f"<!-- role: {role} | model: {model} -->\n\n# System\n\n{system.strip()}\n\n# User\n\n{user.strip()}\n",
            encoding="utf-8",
        )

    # ------------------------------------------------------------------ nodes
    def node(self, name: str) -> Callable:
        """Decorator: log start, end, duration and thread of a graph node."""

        def wrap(fn: Callable) -> Callable:
            @functools.wraps(fn)
            def inner(state):
                self.event(name, "node_start")
                t0 = time.perf_counter()
                try:
                    result = fn(state)
                except Exception as exc:  # GraphInterrupt is an Exception subclass too
                    kind = "node_paused" if type(exc).__name__ in {"GraphInterrupt", "Interrupt"} else "node_error"
                    self.event(name, kind, f"{type(exc).__name__}: {str(exc)[:300]}")
                    raise
                self.event(name, "node_end", f"{time.perf_counter() - t0:.2f}s",
                           updated=sorted((result or {}).keys()) if isinstance(result, dict) else None)
                return result

            return inner

        return wrap
