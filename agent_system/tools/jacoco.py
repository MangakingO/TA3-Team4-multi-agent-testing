"""Coverage tool: reads JaCoCo's jacoco.xml (written by `mvn test` into target/site/jacoco)."""
from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

KINDS = ("INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS", "COMPLEXITY")


def _counters(element) -> dict[str, dict[str, int]]:
    out = {}
    for c in element.findall("counter"):
        out[c.get("type")] = {"missed": int(c.get("missed", 0)), "covered": int(c.get("covered", 0))}
    return out


def _fraction(counters: dict, kind: str) -> dict:
    c = counters.get(kind, {"missed": 0, "covered": 0})
    total = c["missed"] + c["covered"]
    return {
        "covered": c["covered"],
        "total": total,
        "pct": 100.0 if total == 0 else round(100.0 * c["covered"] / total, 2),
    }


def parse(xml_path: Path) -> dict:
    root = ET.parse(xml_path).getroot()
    classes, lines = [], {}
    for pkg in root.iter("package"):
        pkg_name = pkg.get("name") or ""
        for cls in pkg.findall("class"):
            methods = [{
                "name": m.get("name"),
                "desc": m.get("desc"),
                "line": int(m.get("line") or 0),
                "counters": _counters(m),
            } for m in cls.findall("method")]
            classes.append({
                "name": (cls.get("name") or "").replace("/", "."),
                "source": cls.get("sourcefilename"),
                "package": pkg_name.replace("/", "."),
                "counters": _counters(cls),
                "methods": methods,
            })
        for sf in pkg.findall("sourcefile"):
            key = (pkg_name + "/" if pkg_name else "") + (sf.get("name") or "")
            lines[key] = {
                int(ln.get("nr")): {k: int(ln.get(k, 0)) for k in ("mi", "ci", "mb", "cb")}
                for ln in sf.findall("line")
            }
    return {"classes": classes, "lines": lines, "totals": _counters(root)}


def summarize(report: dict) -> dict:
    totals = report["totals"]
    summary = {kind.lower(): _fraction(totals, kind) for kind in ("BRANCH", "LINE", "INSTRUCTION", "METHOD", "CLASS")}
    summary["branch_vacuous"] = summary["branch"]["total"] == 0
    summary["classes"] = [{
        "name": c["name"],
        "branch": _fraction(c["counters"], "BRANCH"),
        "line": _fraction(c["counters"], "LINE"),
        "method": _fraction(c["counters"], "METHOD"),
        "methods": [{
            "name": m["name"], "line": m["line"],
            "branch": _fraction(m["counters"], "BRANCH"),
            "instruction": _fraction(m["counters"], "INSTRUCTION"),
        } for m in c["methods"]],
    } for c in report["classes"]]
    missed_branches, missed_lines = [], []
    for source, line_map in report["lines"].items():
        for nr, d in sorted(line_map.items()):
            if d["mb"] > 0:
                missed_branches.append({"file": source, "line": nr, "missed": d["mb"], "covered": d["cb"]})
            if d["mi"] > 0 and d["ci"] == 0:
                missed_lines.append({"file": source, "line": nr})
    summary["missed_branches"] = missed_branches
    summary["missed_lines"] = missed_lines
    return summary


def marks_for_file(summary: dict, file_name: str) -> dict[int, str]:
    """Notes to print next to source lines: which branches/lines the tests never reached."""
    marks: dict[int, str] = {}
    for b in summary.get("missed_branches", []):
        if Path(b["file"]).name == file_name:
            total = b["missed"] + b["covered"]
            marks[b["line"]] = f"{b['missed']} of {total} branches never taken"
    for ln in summary.get("missed_lines", []):
        if Path(ln["file"]).name == file_name and ln["line"] not in marks:
            marks[ln["line"]] = "never executed"
    return marks


def to_markdown(summary: dict, title: str) -> str:
    def cell(f):
        return f"{f['covered']}/{f['total']} ({f['pct']:.1f}%)" if f["total"] else "n/a (none)"

    out = [f"# {title}", "",
           f"**Branch coverage: {cell(summary['branch'])}**"
           + ("  — the code has no branches, so branch coverage is vacuously 100%" if summary["branch_vacuous"] else ""),
           "", f"Line coverage: {cell(summary['line'])}  ",
           f"Instruction coverage: {cell(summary['instruction'])}  ",
           f"Method coverage: {cell(summary['method'])}", "",
           "| Class | Branches | Lines | Methods |", "|---|---|---|---|"]
    for c in summary["classes"]:
        out.append(f"| {c['name']} | {cell(c['branch'])} | {cell(c['line'])} | {cell(c['method'])} |")
    out += ["", "## Methods", "", "| Class | Method | Line | Branches | Instructions |", "|---|---|---|---|---|"]
    for c in summary["classes"]:
        for m in c["methods"]:
            name = m["name"].replace("<", "&lt;").replace(">", "&gt;")
            out.append(f"| {c['name']} | {name} | {m['line']} | {cell(m['branch'])} | {cell(m['instruction'])} |")
    if summary["missed_branches"]:
        out += ["", "## Branches never taken", ""]
        out += [f"- {b['file']} line {b['line']}: {b['missed']} of {b['missed'] + b['covered']}" for b in summary["missed_branches"]]
    if summary["missed_lines"]:
        out += ["", "## Lines never executed", ""]
        out += [f"- {ln['file']} line {ln['line']}" for ln in summary["missed_lines"]]
    return "\n".join(out) + "\n"
