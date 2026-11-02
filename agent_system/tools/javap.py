"""Reads compiled classes with the JDK's javap: public API, class structure, branch instructions.

Used to check that the refactored Subject class keeps the original interface and to measure
how much conditional logic is left in each class.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

BRANCH_OPCODES = {
    "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle", "if_icmpeq", "if_icmpne", "if_icmplt",
    "if_icmpge", "if_icmpgt", "if_icmple", "if_acmpeq", "if_acmpne", "ifnull", "ifnonnull",
    "tableswitch", "lookupswitch",
}


@dataclass
class ClassInfo:
    name: str
    kind: str  # class | interface | enum
    modifiers: list[str] = field(default_factory=list)
    extends: list[str] = field(default_factory=list)
    implements: list[str] = field(default_factory=list)
    members: list[str] = field(default_factory=list)
    branch_ops: int = 0
    switch_ops: int = 0
    instanceof_ops: int = 0

    @property
    def abstract(self) -> bool:
        return self.kind == "interface" or "abstract" in self.modifiers

    @property
    def public_members(self) -> list[str]:
        return sorted(m for m in self.members if m.startswith("public ") and "synthetic" not in m)

    @property
    def simple_name(self) -> str:
        return self.name.rsplit(".", 1)[-1]


def find_javap() -> str | None:
    found = shutil.which("javap")
    if found:
        return found
    exe = "javap.exe" if os.name == "nt" else "javap"
    java_home = os.environ.get("JAVA_HOME")
    if java_home and (Path(java_home) / "bin" / exe).exists():
        return str(Path(java_home) / "bin" / exe)
    java = shutil.which("java")
    if java:
        candidate = Path(os.path.realpath(java)).parent / exe
        if candidate.exists():
            return str(candidate)
    return None


def class_names(classes_dir: Path) -> list[str]:
    root = Path(classes_dir)
    names = []
    for p in sorted(root.rglob("*.class")):
        rel = p.relative_to(root).with_suffix("")
        names.append(".".join(rel.parts))
    return names


_HEADER = re.compile(
    r"^(?P<mods>(?:[\w-]+\s+)*?)(?P<kind>class|interface|enum)\s+(?P<name>[\w.$]+)(?:<[^{]*?>)?"
    r"(?:\s+extends\s+(?P<ext>[^{]+?))?(?:\s+implements\s+(?P<impl>[^{]+?))?\s*\{\s*$")


def _split_types(text: str | None) -> list[str]:
    if not text:
        return []
    depth, cur, out = 0, "", []
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return [re.sub(r"<.*>", "", t).strip() for t in out]


def inspect(classes_dir: Path, names: list[str] | None = None) -> dict[str, ClassInfo]:
    javap = find_javap()
    if javap is None:
        raise FileNotFoundError("javap not found (it ships with the JDK; set JAVA_HOME)")
    names = names or class_names(classes_dir)
    if not names:
        return {}
    proc = subprocess.run([javap, "-p", "-c", "-constants", "-cp", str(classes_dir), *names],
                          capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    infos: dict[str, ClassInfo] = {}
    current: ClassInfo | None = None
    for line in proc.stdout.splitlines():
        if line.startswith("Compiled from"):
            current = None
            continue
        if current is None:
            m = _HEADER.match(line.strip())
            if m:
                mods = m.group("mods").split()
                ext, impl = _split_types(m.group("ext")), _split_types(m.group("impl"))
                if m.group("kind") == "interface":
                    impl, ext = ext, []
                current = ClassInfo(m.group("name"), m.group("kind"), mods, ext, impl)
                infos[current.name] = current
            continue
        if re.match(r"^  \S.*;\s*$", line):
            current.members.append(" ".join(line.strip().rstrip(";").split()))
            continue
        op = re.match(r"^\s+\d+:\s+(\w+)", line)
        if op:
            opcode = op.group(1)
            if opcode in BRANCH_OPCODES:
                current.branch_ops += 1
                if opcode.endswith("switch"):
                    current.switch_ops += 1
            elif opcode == "instanceof":
                current.instanceof_ops += 1
    return infos


def public_api(classes_dir: Path, fq_name: str) -> list[str]:
    info = inspect(classes_dir, [fq_name]).get(fq_name)
    return info.public_members if info else []
