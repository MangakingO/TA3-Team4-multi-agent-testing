"""Change log for every modification an agent (or a person) makes to code or test cases.

changes.md   readable log with a unified diff per change
changes.jsonl the same records as JSON lines
"""
from __future__ import annotations

import difflib
import json
import threading
from datetime import datetime
from pathlib import Path


class ChangeLog:
    def __init__(self, run_dir: Path):
        self.md_path = Path(run_dir) / "changes.md"
        self.jsonl_path = Path(run_dir) / "changes.jsonl"
        self._lock = threading.Lock()
        if not self.md_path.exists():
            self.md_path.write_text("# Change log\n\nEvery change the agents made to the code or the test cases, "
                                    "with the reason and a unified diff.\n", encoding="utf-8")

    def _next_number(self) -> int:
        if not self.jsonl_path.exists():
            return 1
        return sum(1 for line in self.jsonl_path.read_text(encoding="utf-8").splitlines() if line.strip()) + 1

    def record(self, *, phase: str, agent: str, kind: str, target: str, action: str, reason: str,
               before: str = "", after: str = "", round_no: int | None = None) -> dict:
        diff = "\n".join(difflib.unified_diff(
            before.splitlines(), after.splitlines(), fromfile=f"a/{target}", tofile=f"b/{target}", lineterm=""))
        with self._lock:
            number = self._next_number()
            entry = {
                "n": number,
                "time": datetime.now().isoformat(timespec="seconds"),
                "phase": phase,
                "agent": agent,
                "kind": kind,
                "target": target,
                "action": action,
                "round": round_no,
                "reason": reason.strip(),
                "diff": diff,
            }
            with self.jsonl_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            title = f"\n## {number}. [{phase}] {agent}: {action} {kind} {target}"
            if round_no is not None:
                title += f" (round {round_no})"
            body = [title, "", f"*{entry['time']}*", "", f"**Reason:** {entry['reason'] or '(none given)'}", ""]
            if diff:
                body += ["```diff", diff, "```", ""]
            with self.md_path.open("a", encoding="utf-8") as f:
                f.write("\n".join(body))
        return entry

    def entries(self) -> list[dict]:
        if not self.jsonl_path.exists():
            return []
        return [json.loads(line) for line in self.jsonl_path.read_text(encoding="utf-8").splitlines() if line.strip()]
