"""Test-runner tool: runs Maven and reads what it produced (Surefire XML reports, compiler errors)."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

from ..config import Settings


class MavenNotFound(RuntimeError):
    pass


@dataclass
class MavenResult:
    command: list[str]
    exit_code: int
    output: str
    seconds: float
    timed_out: bool = False


def resolve_mvn(settings: Settings) -> str:
    candidates = [settings.mvn]
    if os.name == "nt" and not settings.mvn.lower().endswith((".cmd", ".bat", ".exe")):
        candidates.insert(0, settings.mvn + ".cmd")
    for name in candidates:
        found = shutil.which(name)
        if found:
            return found
    raise MavenNotFound(
        f"Maven ('{settings.mvn}') is not on the PATH. Install Maven and check with `mvn -v`, "
        "or set MVN in .env to the full path of mvn / mvn.cmd.")


def run(project: Path, goals: list[str], settings: Settings, log_file: Path, *,
        extra: tuple[str, ...] = (), stdin: str | None = None, timeout: int | None = None) -> MavenResult:
    command = [resolve_mvn(settings), "-B", *settings.maven_args, *extra, *goals]
    start = time.perf_counter()
    timed_out = False
    try:
        proc = subprocess.run(command, cwd=project, input=stdin if stdin is not None else "",
                              capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=timeout or settings.maven_timeout)
        output, code = (proc.stdout or "") + (proc.stderr or ""), proc.returncode
    except subprocess.TimeoutExpired as exc:
        output = "".join(_decode(x) for x in (exc.stdout, exc.stderr)) + "\n[agent] Maven timed out"
        code, timed_out = -1, True
    seconds = time.perf_counter() - start
    log_file.parent.mkdir(parents=True, exist_ok=True)
    log_file.write_text("$ " + " ".join(command) + f"\n# exit code {code}, {seconds:.1f}s\n\n" + output,
                        encoding="utf-8")
    return MavenResult(command, code, output, seconds, timed_out)


def _decode(data) -> str:
    if data is None:
        return ""
    return data.decode("utf-8", "replace") if isinstance(data, bytes) else str(data)


_ERROR_LINE = re.compile(r"^\[ERROR\]\s+(?P<file>.+?\.java):\[(?P<line>\d+),(?P<col>\d+)\]\s+(?P<msg>.*)$")


def parse_compile_errors(output: str, limit: int = 100) -> list[dict]:
    errors: list[dict] = []
    lines = output.splitlines()
    for i, line in enumerate(lines):
        m = _ERROR_LINE.match(line.strip())
        if not m:
            continue
        detail = []
        for nxt in lines[i + 1:i + 4]:
            s = nxt.strip()
            if s.startswith("[ERROR]   ") or s.startswith("[ERROR] symbol") or s.startswith("[ERROR] location"):
                detail.append(s.replace("[ERROR]", "").strip())
            else:
                break
        path = m.group("file").replace("\\", "/")
        errors.append({
            "file": Path(path).name,
            "scope": "test" if "/src/test/" in path else "main",
            "line": int(m.group("line")),
            "message": (m.group("msg") + ("; " + "; ".join(detail) if detail else "")).strip(),
        })
        if len(errors) >= limit:
            break
    # de-duplicate (javac and Maven both print each error)
    seen, unique = set(), []
    for e in errors:
        key = (e["file"], e["line"], e["message"])
        if key not in seen:
            seen.add(key)
            unique.append(e)
    return unique


def parse_surefire(reports_dir: Path) -> list[dict]:
    tests = []
    for xml_file in sorted(Path(reports_dir).glob("TEST-*.xml")):
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            name = re.sub(r"\(.*$", "", case.get("name", ""))
            status, message, details, kind = "passed", "", "", ""
            for tag in ("failure", "error"):
                el = case.find(tag)
                if el is not None:
                    status = "failed" if tag == "failure" else "error"
                    message = el.get("message") or ""
                    kind = el.get("type") or ""
                    details = (el.text or "").strip()
                    break
            if case.find("skipped") is not None:
                status = "skipped"
            tests.append({
                "method": name,
                "class": case.get("classname", ""),
                "status": status,
                "time": float(case.get("time") or 0),
                "type": kind,
                "message": message[:1000],
                "trace": "\n".join(details.splitlines()[:12]),
            })
    return tests


def clean_outputs(project: Path) -> None:
    target = Path(project) / "target"
    for sub in ("classes", "test-classes", "surefire-reports", "site", "jacoco.exec"):
        p = target / sub
        if p.is_dir():
            shutil.rmtree(p, ignore_errors=True)
        elif p.exists():
            p.unlink()


def run_tests(project: Path, settings: Settings, log_file: Path) -> dict:
    """mvn test (failures don't stop the build, so JaCoCo still writes its report)."""
    clean_outputs(project)
    res = run(project, ["test"], settings, log_file, extra=("-Dmaven.test.failure.ignore=true",))
    compile_errors = parse_compile_errors(res.output)
    summary = {
        "exit_code": res.exit_code,
        "seconds": round(res.seconds, 1),
        "maven_log": str(log_file),
        "tests": [],
        "compile_errors": compile_errors,
    }
    if compile_errors or "COMPILATION ERROR" in res.output:
        summary["status"] = "compile_error"
        return summary
    tests = parse_surefire(Path(project) / "target" / "surefire-reports")
    summary["tests"] = tests
    counts = {k: sum(1 for t in tests if t["status"] == k) for k in ("passed", "failed", "error", "skipped")}
    summary["counts"] = counts
    if res.timed_out:
        summary["status"] = "timeout"
    elif res.exit_code != 0 and not tests:
        summary["status"] = "build_error"
        summary["log_tail"] = "\n".join(res.output.splitlines()[-40:])
    elif counts["failed"] or counts["error"]:
        summary["status"] = "failed"
    else:
        summary["status"] = "passed" if tests else "no_tests"
    return summary


def compile_only(project: Path, settings: Settings, log_file: Path, *, with_tests: bool = False) -> dict:
    clean_outputs(project)
    res = run(project, ["test-compile" if with_tests else "compile"], settings, log_file)
    errors = parse_compile_errors(res.output)
    ok = res.exit_code == 0 and not errors
    return {"ok": ok, "compile_errors": errors, "exit_code": res.exit_code,
            "log_tail": "" if ok else "\n".join(res.output.splitlines()[-30:])}


def exec_main(project: Path, settings: Settings, log_file: Path, stdin: str = "", timeout: int = 120) -> dict:
    """mvn compile exec:java -- runs the program's main() to observe its behaviour."""
    res = run(project, ["-q", "compile", "exec:java"], settings, log_file, stdin=stdin, timeout=timeout)
    return {"exit_code": res.exit_code, "timed_out": res.timed_out, "output": res.output[-6000:]}
