"""Pattern checker: does the refactored code apply the State pattern, and keep the Subject's interface?

Works on compiled classes (javap), so it judges what the code really is, not what comments say.
The team still reviews the code (human in the loop); these checks tell them where to look.
"Subject family" = the Subject class plus its nested classes (for SeasonSwitch: AC, Heater, Room),
because state-dependent code (Room's switch on the state) can hide in a nested class too.
"""
from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

from . import javap
from .testability import simplify


def _family(infos: dict, fq: str, exclude: set[str]) -> list:
    return [i for n, i in infos.items() if (n == fq or n.startswith(fq + "$")) and n not in exclude]


def _member_name(member: str) -> str:
    head = member.split("(")[0] if "(" in member else member.split(" = ")[0]
    return head.split()[-1]


def _kept_members(info, package: str) -> set[str]:
    """Non-private, non-synthetic members, simplified: 'AC ac', 'public void on()'."""
    out = set()
    for m in info.members:
        if m.startswith("private ") or "$" in _member_name(m):
            continue
        if "(" not in m and re.search(r"state", _member_name(m), re.I):
            continue  # the state field is the one thing the refactoring must replace
        out.add(simplify(m.split(" = ")[0], package))
    return out


def used_names(test_code: str) -> set[str]:
    """Identifiers that appear in the test code (to find the members the tests use)."""
    from .dedup import identifiers

    return identifiers(test_code)


def check(original_classes: Path, original_fqcn: str, refactored_classes: Path, subject_fqcn: str,
          test_code: str | None = None) -> dict:
    checks: list[dict] = []
    package = original_fqcn.rsplit(".", 1)[0] if "." in original_fqcn else ""

    def add(key: str, title: str, ok: bool | None, detail: str) -> None:
        checks.append({"id": key, "check": title, "result": "INFO" if ok is None else ("PASS" if ok else "FAIL"),
                       "detail": detail})

    try:
        before = javap.inspect(original_classes)
        after = javap.inspect(refactored_classes)
    except (FileNotFoundError, OSError) as exc:
        add("javap", "Structure checks", None, f"skipped: {exc}")
        return {"checks": checks, "passed": None, "classes": []}

    original = before.get(original_fqcn)
    subject = after.get(subject_fqcn)
    add("subject", "Subject class keeps the original name", subject is not None and subject.kind == "class",
        subject_fqcn if subject else f"no class {subject_fqcn} in the refactored code")
    if subject is None or original is None:
        return {"checks": checks, "passed": False, "classes": _classes(after)}

    missing = [m for m in original.public_members if m not in subject.public_members]
    added = [m for m in subject.public_members if m not in original.public_members]
    add("interface", "Subject keeps exactly the original public interface", not missing and not added,
        ("missing: " + "; ".join(missing) if missing else "all original public members present")
        + ("; added: " + "; ".join(added) if added else ""))

    # State type: an interface/abstract class with 2+ implementations
    others = {n: i for n, i in after.items() if n != subject_fqcn}
    children: dict[str, list[str]] = defaultdict(list)
    for name, info in others.items():
        for parent in info.extends + info.implements:
            children[parent].append(name)
    candidates = sorted((n for n, i in others.items() if i.abstract and len(children.get(n, [])) >= 2),
                        key=lambda n: -len(children[n]))
    state_type = candidates[0] if candidates else None
    add("state_type", "An abstract State type (interface or abstract class)", state_type is not None,
        f"{state_type} ({_kind(after[state_type])})" if state_type else "no interface/abstract class with 2+ subclasses")
    concrete = [n for n in children.get(state_type, []) if not after[n].abstract] if state_type else []
    add("concrete_states", "One concrete class per state", len(concrete) >= 2,
        ", ".join(sorted(after[n].simple_name for n in concrete)) or "none")

    # members the tests use (package-private fields and nested classes) must still be there
    state_classes = set(concrete) | ({state_type} if state_type else set())
    old_family = _family(before, original_fqcn, set())
    names = used_names(test_code) if test_code is not None else None
    new_by_simple = {i.simple_name.split("$")[-1]: i for i in after.values()}
    lost = []
    for info in old_family:
        simple = info.simple_name.split("$")[-1]
        twin = subject if info.name == original_fqcn else new_by_simple.get(simple)
        if twin is None:
            lost.append(f"class {simple}")
            continue
        gone = _kept_members(info, package) - _kept_members(twin, package)
        if names is not None:
            gone = {m for m in gone if _member_name(m) in names}
        lost += [f"{simple}: {m}" for m in sorted(gone)]
    add("members", "Members the tests use are still there (non-private fields, methods, nested classes)",
        not lost, "missing: " + "; ".join(lost) if lost else "all present")

    new_family = _family(after, subject_fqcn, state_classes)
    typed = [m for m in subject.members if "(" not in m and state_type and re.search(rf"\b{re.escape(state_type)}\b", m)]
    holds = [m for m in typed if " static " not in f" {m} "] or typed
    add("delegation", "Subject holds a reference to its current State", bool(holds),
        "; ".join(simplify(h, package) for h in holds) or f"no field of type {state_type}")

    instanceofs = sum(i.instanceof_ops for i in new_family)
    add("no_type_tests", "No instanceof tests on states in the Subject family", instanceofs == 0,
        f"{instanceofs} instanceof instruction(s) in {', '.join(i.simple_name for i in new_family)}")

    old_switch = sum(i.switch_ops for i in old_family)
    old_branch = sum(i.branch_ops for i in old_family)
    new_switch = sum(i.switch_ops for i in new_family)
    new_branch = sum(i.branch_ops for i in new_family)
    state_branches = sum(after[n].branch_ops for n in concrete)
    add("conditionals", "No switch on the state left in the Subject family", new_switch == 0 and new_branch < old_branch,
        f"switch statements {old_switch} -> {new_switch}; conditional branch instructions {old_branch} -> {new_branch} "
        f"in {', '.join(i.simple_name for i in new_family)}; all concrete states together {state_branches}")

    deciders = [after[n].simple_name for n in concrete if any(subject_fqcn in m for m in after[n].members)]
    add("transitions", "Who decides the next state", None,
        f"the State classes ({', '.join(deciders)}) receive the Subject and set its next state"
        if deciders else "the Subject (states do not reference the Subject)")

    passed = all(c["result"] == "PASS" for c in checks if c["result"] != "INFO")
    return {"checks": checks, "passed": passed, "state_type": state_type,
            "concrete_states": sorted(after[n].simple_name for n in concrete), "classes": _classes(after),
            "original_branch_ops": old_branch, "subject_branch_ops": new_branch,
            "original_switch_ops": old_switch, "subject_switch_ops": new_switch}


def _kind(info) -> str:
    return "abstract class" if (info.kind == "class" and info.abstract) else info.kind


def _classes(infos: dict) -> list[dict]:
    return [{
        "name": i.name, "kind": _kind(i),
        "extends": i.extends, "implements": i.implements, "branch_ops": i.branch_ops,
        "public_members": i.public_members,
    } for i in infos.values()]


def to_markdown(report: dict) -> str:
    out = ["| Check | Result | Detail |", "|---|---|---|"]
    for c in report.get("checks", []):
        out.append(f"| {c['check']} | {c['result']} | {c['detail'].replace('|', '/')} |")
    if report.get("classes"):
        out += ["", "| Class | Kind | Extends / implements | Branch instructions |", "|---|---|---|---|"]
        for c in report["classes"]:
            parents = ", ".join(c["extends"] + c["implements"]) or "-"
            out.append(f"| {c['name']} | {c['kind']} | {parents} | {c['branch_ops']} |")
    return "\n".join(out) + "\n"
