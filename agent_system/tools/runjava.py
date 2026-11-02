"""Runs a compiled Java program directly with `java` to observe its behaviour.

The program is stopped after a time limit, because programs such as SeasonSwitch never end on
their own. `java` is started directly (not through mvn exec:java) so that stopping it also works
on Windows, where killing mvn.cmd would leave the Java process running.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path


def find_java() -> str:
    exe = "java.exe" if os.name == "nt" else "java"
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / exe).exists():
        return str(Path(home) / "bin" / exe)
    found = shutil.which("java")
    if not found:
        raise FileNotFoundError("java is not on the PATH (install a JDK or set JAVA_HOME)")
    return found


def _text(data) -> str:
    if data is None:
        return ""
    text = data.decode("utf-8", "replace") if isinstance(data, bytes) else str(data)
    lines = [ln for ln in text.replace("\r\n", "\n").split("\n") if not ln.startswith("Picked up JAVA_TOOL_OPTIONS")]
    return "\n".join(lines).strip("\n")


def run(classes_dir: Path, main_class: str, args: list[str], seconds: float) -> dict:
    cmd = [find_java(), "-Djava.awt.headless=true", "-cp", str(classes_dir), main_class, *args]
    try:
        proc = subprocess.run(cmd, input=b"", capture_output=True, timeout=seconds)
        return {"args": args, "exit_code": proc.returncode, "stopped": False,
                "out": _text(proc.stdout), "err": _text(proc.stderr)}
    except subprocess.TimeoutExpired as exc:
        return {"args": args, "exit_code": None, "stopped": True,
                "out": _text(exc.stdout), "err": _text(exc.stderr)}


def describe(runs: list[dict], main_class: str, seconds: float) -> str:
    """The observations as one text block for the prompts and for observe.md."""
    simple = main_class.rsplit(".", 1)[-1]
    parts = []
    for r in runs:
        shown = " ".join(r["args"]) if r["args"] else "(no arguments)"
        how = (f"still running after {seconds:g} s, stopped" if r["stopped"]
               else f"exit code {r['exit_code']}")
        block = [f"$ java {simple} {shown}   -> {how}"]
        if r["out"]:
            block.append(r["out"])
        if r["err"]:
            block.append("[stderr] " + r["err"].replace("\n", "\n[stderr] "))
        parts.append("\n".join(block))
    return "\n\n".join(parts)
