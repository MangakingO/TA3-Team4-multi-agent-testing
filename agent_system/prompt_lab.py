"""Prompt lab for step 8: score versions of the State-pattern refactoring prompt on the real code.

    python -m agent_system lab <run-id> [--versions P0 P1 ...] [--lab-id NAME]

Uses the run's tested original code and its accepted test suite. For every prompt version in
agent_system/prompts/refactor_versions it writes prompt-lab/<lab-id>/<version>/prompt.md, gets a
reply, and scores it:

  * does the refactored code compile?
  * does it pass the State pattern checks (javap)?
  * how many of the existing test cases compile UNCHANGED against it, and how many of those pass?
  * branch coverage of the refactored code by those tests.

Getting the reply: with LLM_PROVIDER=manual (the assignment's "web-based LLM"), paste prompt.md
into the web chat and save the answer as reply.md next to it, then run the lab again.
With LLM_PROVIDER=ollama (or another provider) the lab asks the model itself.
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from .agents.common import package_text
from .config import Settings
from .context import Context
from .nodes.common import load_suite, members_text
from .prompts import PromptTemplate
from .tools import jacoco, javap, javasrc, junit, maven, pattern_check, refactor_parse, testability, workspace

VERSIONS_DIR = Path(__file__).resolve().parent / "prompts" / "refactor_versions"


def _render(version_file: Path, class_name: str, package: str, source: str, api: list[str], members: str) -> tuple[str, str]:
    return PromptTemplate(version_file).render(
        class_name=class_name, package_text=package_text(package), source=source,
        api="\n".join(f"- {m}" for m in api) or "- (see the code)", members=members or "(see the code)", feedback="")


def _score(reply: str, out: Path, settings: Settings, class_name: str, package: str, original_classes: Path,
           suite: list[dict]) -> dict:
    fq = javasrc.fqcn(package, class_name)
    files = refactor_parse.parse_files(reply)
    result: dict = {"files": sorted(files), "compiles": False, "checks_passed": 0, "checks_total": 0,
                    "tests_total": len(suite), "tests_compile": 0, "tests_passed": 0, "branch": None, "problems": []}
    if f"{class_name}.java" not in files:
        result["problems"].append("no file for the Subject class" if files else "no Java files in the reply")
        return result
    project = workspace.create_project(out / "project", settings.pom_template, fq)
    workspace.write_sources(project, package, files, replace=True)
    compiled = maven.compile_only(project, settings, out / "maven-compile.log")
    result["compiles"] = compiled["ok"]
    if not compiled["ok"]:
        result["problems"] += [f"compile error {e['file']}:{e['line']} {e['message']}" for e in compiled["compile_errors"][:5]]
        return result

    report = pattern_check.check(original_classes, fq, project / "target" / "classes", fq,
                                 "\n".join(c.get("junit", "") for c in suite))
    (out / "pattern-check.md").write_text(pattern_check.to_markdown(report), encoding="utf-8")
    graded = [c for c in report["checks"] if c["result"] != "INFO"]
    result["checks_passed"] = sum(c["result"] == "PASS" for c in graded)
    result["checks_total"] = len(graded)
    result["checks"] = report["checks"]
    result["problems"] += [f"pattern: {c['check']} ({c['detail']})" for c in graded if c["result"] == "FAIL"]

    # reuse the accepted tests unchanged; drop only the ones that do not compile
    usable, dropped = list(suite), []
    check = {"ok": False}
    for attempt in range(1, 31):
        source, line_map = junit.render(class_name, package, usable, settings.test_timeout_seconds)
        workspace.write_test_class(project, package, class_name, source)
        check = maven.compile_only(project, settings, out / f"maven-test-compile-{attempt}.log", with_tests=True)
        bad = {junit.blame(line_map, e["line"]) for e in check["compile_errors"] if e["scope"] == "test"} - {None}
        if check["ok"] or not bad:
            break
        dropped += sorted(bad)
        usable = [c for c in usable if c["id"] not in bad]
    if not check["ok"]:
        result["tests_compile"] = 0
        result["not_compiling"] = [c["id"] for c in suite]
        result["problems"].append("the test class does not compile (errors outside the test methods)")
        return result
    result["tests_compile"] = len(usable)
    result["not_compiling"] = dropped
    if dropped:
        result["problems"].append(f"{len(dropped)} test(s) no longer compile: {', '.join(dropped)}")
    run = maven.run_tests(project, settings, out / "maven-test.log")
    ids = {junit.method_name(c["id"]): c["id"] for c in usable}
    failed = [ids.get(t["method"], t["method"]) for t in run["tests"] if t["status"] in ("failed", "error")]
    result["tests_passed"] = sum(1 for t in run["tests"] if t["status"] == "passed")
    result["failed"] = failed
    if failed:
        result["problems"].append(f"{len(failed)} test(s) fail: {', '.join(failed)}")
    xml = project / "target" / "site" / "jacoco" / "jacoco.xml"
    if xml.exists():
        result["branch"] = jacoco.summarize(jacoco.parse(xml))["branch"]
    return result


def _pct(f) -> str:
    return f"{f['covered']}/{f['total']} ({f['pct']:.0f}%)" if f and f["total"] else "-"


def report_markdown(results: list[dict], lab: Path, run_id: str) -> str:
    out = [f"# Prompt lab {lab.name}", "",
           f"Refactoring prompt versions scored on the tested original code of run `{run_id}`.", "",
           "| Version | Files | Compiles | State pattern checks | Tests compile unchanged | Tests pass | Branch coverage |",
           "|---|---|---|---|---|---|---|"]
    for r in results:
        if r.get("pending"):
            out.append(f"| {r['version']} | waiting for reply.md | | | | | |")
            continue
        out.append(f"| {r['version']} | {len(r['files'])} | {'yes' if r['compiles'] else 'NO'} | "
                   f"{r['checks_passed']}/{r['checks_total']} | {r['tests_compile']}/{r['tests_total']} | "
                   f"{r['tests_passed']}/{r['tests_compile']} | {_pct(r['branch'])} |")
    out += ["", "## Problems found per version", ""]
    for r in results:
        if not r.get("pending"):
            out.append(f"- **{r['version']}**: " + ("; ".join(r["problems"]) or "none"))
    return "\n".join(out) + "\n"


def run_lab(settings: Settings, run_id: str, versions: list[str] | None, lab_id: str | None, echo=print) -> int:
    run_dir = settings.runs_dir / run_id
    original_project = run_dir / "original" / "project"
    suite_file = run_dir / "original" / "test-cases.json"
    if not suite_file.exists() or not original_project.exists():
        echo(f"Run {run_id} has no tested original code yet (finish steps 1-7 first).")
        return 1
    sources = workspace.read_sources(original_project)
    subject_file = next((n for n in sources if javasrc.has_main(sources[n]) or len(sources) == 1), next(iter(sources)))
    source = sources[subject_file]
    package, class_name = javasrc.package_of(source), javasrc.primary_type(source)
    fq = javasrc.fqcn(package, class_name)
    compiled = maven.compile_only(original_project, settings, run_dir / "logs" / "maven-lab-original-compile.log")
    if not compiled["ok"]:
        echo("The run's original code does not compile.")
        return 1
    classes = original_project / "target" / "classes"
    api = javap.public_api(classes, fq)
    analysis = testability.analyze(source, classes, class_name, package)
    suite = load_suite(suite_file)
    members = members_text({"testability": {"original": analysis.as_dict()}}, "\n".join(c.get("junit", "") for c in suite))

    lab = settings.runs_dir.parent / "prompt-lab" / (lab_id or run_id)
    lab.mkdir(parents=True, exist_ok=True)
    ctx = Context.create(settings, lab / "_trace")
    names = versions or sorted(p.stem for p in VERSIONS_DIR.glob("P*.md"))
    results = []
    for version in names:
        vfile = VERSIONS_DIR / f"{version}.md"
        out = lab / version
        out.mkdir(exist_ok=True)
        system, user = _render(vfile, class_name, package, source.rstrip(), api, members)
        (out / "prompt.md").write_text(f"<!-- {version}: paste the System part (if your chat has one) and the User "
                                       f"part into the LLM; save its whole answer as reply.md -->\n\n"
                                       f"# System\n\n{system}\n\n# User\n\n{user}\n", encoding="utf-8")
        reply_file = out / "reply.md"
        if not reply_file.exists() or not reply_file.read_text(encoding="utf-8").strip():
            if settings.llm_provider == "manual":
                echo(f"{version}: paste {out / 'prompt.md'} into the web LLM and save its answer as {reply_file}")
                results.append({"version": version, "pending": True})
                continue
            echo(f"{version}: asking {settings.describe()} ...")
            reply_file.write_text(ctx.llm.text(f"lab_{version}", system, user), encoding="utf-8")
        echo(f"{version}: scoring the reply ...")
        result = _score(reply_file.read_text(encoding="utf-8"), out, settings, class_name, package, classes, suite)
        result["version"] = version
        (out / "score.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        results.append(result)
        echo(f"  {version}: compiles={result['compiles']} checks={result['checks_passed']}/{result['checks_total']} "
             f"tests compile {result['tests_compile']}/{result['tests_total']}, pass {result['tests_passed']}")
    text = report_markdown(results, lab, run_id)
    (lab / "results.md").write_text(text, encoding="utf-8")
    (lab / "results.json").write_text(json.dumps({"run": run_id, "time": datetime.now().isoformat(timespec="seconds"),
                                                  "llm": settings.describe(), "results": results}, indent=2) + "\n",
                                      encoding="utf-8")
    echo(f"Results: {lab / 'results.md'}")
    return 0
