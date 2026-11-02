"""Testability analysis: what makes the class under test hard to test, and how the tests cope with it.

The findings become a "facts" section in every prompt that writes or fixes tests, and the
JUnit renderer adds the matching fixture (restore static fields, stop timers) to the test class.
Works on the source text and on the compiled classes (javap), so it is not specific to SeasonSwitch.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from . import javap, javasrc

_TYPE_PREFIXES = ("java.lang.", "java.util.", "java.awt.event.", "java.io.")


def simplify(member: str, package: str) -> str:
    """'cse4392.SeasonSwitch$AC ac' -> 'AC ac'; 'java.lang.String[]' -> 'String[]'."""
    text = member
    if package:
        text = re.sub(rf"\b{re.escape(package)}\.", "", text)
    for prefix in _TYPE_PREFIXES:
        text = text.replace(prefix, "")
    text = re.sub(r"\b[A-Za-z_]\w*\$(\w+)", r"\1", text)  # Outer$Inner -> Inner
    return text


@dataclass
class Testability:
    class_name: str
    package: str
    members: dict[str, list[str]] = field(default_factory=dict)  # class -> non-private members
    static_fields: list[str] = field(default_factory=list)
    timers: list[str] = field(default_factory=list)              # "SeasonSwitch.timer"
    listeners: list[str] = field(default_factory=list)           # "SeasonSwitch.Room.actionPerformed(null)"
    prints: bool = False
    has_main: bool = False
    exits: bool = False
    blocks: list[str] = field(default_factory=list)
    reads_stdin: bool = False

    # -------------------------------------------------------------- notes for the prompts
    def notes(self) -> str:
        c = self.class_name
        out = [f"Facts about {c} that every test must respect (from the testability analysis):"]
        n = 1
        where = f"package {self.package}" if self.package else "the default package"
        out.append(f"{n}. The test class is in {where}, so tests may use package-private members. "
                   "Non-private members:")
        for cls, members in self.members.items():
            if members:
                out.append(f"   - {cls}: " + "; ".join(members))
        n += 1
        if self.static_fields:
            out.append(f"{n}. Static state: {', '.join(self.static_fields)} "
                       f"{'is' if len(self.static_fields) == 1 else 'are'} static, so shared by all {c} objects. "
                       "The test class puts static fields back to their initial values before each test.")
            n += 1
        if self.timers:
            listeners = ", ".join(self.listeners) or "the listener method"
            out.append(f"{n}. Timers: {', '.join(self.timers)}. Never wait for a timer (no Thread.sleep). "
                       f"Simulate one timer event by calling the listener directly: {listeners}. "
                       f"Every {c} created in a test is tracked and its timers are stopped after the test.")
            n += 1
        if self.prints:
            out.append(f"{n}. Console output: {c} prints with System.out. Check it with "
                       "`String out = TestSupport.captureOut(() -> s.someMethod());` which returns the printed "
                       "text with \"\\n\" line endings; `TestSupport.lines(out)` splits it into lines.")
            n += 1
        if self.has_main and (self.exits or self.blocks or self.reads_stdin):
            why = []
            if self.exits:
                why.append("calls System.exit")
            if self.blocks:
                why.append("may never return (" + ", ".join(self.blocks) + ")")
            if self.reads_stdin:
                why.append("reads console input")
            out.append(f"{n}. main(): it {' and '.join(why)}. Never call {c}.main directly. Use "
                       f"`TestSupport.MainRun r = TestSupport.runMain({c}.class, 3000, \"arg\");` which runs "
                       "main in a separate JVM for at most 3000 ms and gives r.exitCode (the System.exit code, "
                       "0 if main returned or was stopped), r.stillRunning (true if main was still running and "
                       "was stopped), r.out and r.lines(). Each call takes about a second, so use it only "
                       "for main().")
            n += 1
        return "\n".join(out)

    def to_markdown(self) -> str:
        return "# Testability analysis\n\n" + self.notes() + "\n"

    def as_dict(self) -> dict:
        return {k: getattr(self, k) for k in self.__dataclass_fields__}

    @classmethod
    def from_dict(cls, data: dict) -> "Testability":
        return cls(**{k: data[k] for k in cls.__dataclass_fields__ if k in data})


_BLOCKING = [
    (r"\.await\s*\(", "await()"),
    (r"\bThread\.sleep\s*\(", "Thread.sleep()"),
    (r"\.join\s*\(\s*\)", "join()"),
    (r"\.wait\s*\(\s*\)", "wait()"),
    (r"\bwhile\s*\(\s*true\s*\)", "while (true)"),
    (r"\bfor\s*\(\s*;\s*;\s*\)", "for (;;)"),
]


def analyze(source: str, classes_dir: Path, class_name: str, package: str) -> Testability:
    code = javasrc.strip_comments(source)
    t = Testability(class_name=class_name, package=package)
    t.prints = bool(re.search(r"\bSystem\.out\.print", code))
    t.has_main = javasrc.has_main(source)
    t.exits = bool(re.search(r"\bSystem\.exit\s*\(", code))
    t.blocks = [label for pattern, label in _BLOCKING if re.search(pattern, code)]
    t.reads_stdin = bool(re.search(r"System\.in\b", code))

    fq = javasrc.fqcn(package, class_name)
    try:
        infos = javap.inspect(Path(classes_dir))
    except (FileNotFoundError, OSError):
        infos = {}
    family = sorted((n for n in infos if n == fq or n.startswith(fq + "$")), key=lambda n: (n != fq, n))
    for name in family:
        info = infos[name]
        display = simplify(name, package).replace("$", ".")
        if name != fq:
            display = f"{class_name}." + display.split(".")[-1]
        members = []
        for m in info.members:
            head = m.split("(")[0] if "(" in m else m.split(" = ")[0]
            if m.startswith("private ") or "$" in head.split()[-1]:  # synthetic: this$0, lambda$..., Outer$Inner()
                continue
            if re.search(r"\bstatic\s*\{\s*\}", m) or m.strip() in ("static {}",):
                continue
            members.append(simplify(m, package))
            fld = re.match(r"^(?:(?:public|protected|static|final|volatile|transient)\s+)*([\w.$<>\[\], ]+?)\s+(\w+)(?:\s*=.*)?$", m)
            if "(" not in m and fld:
                ftype, fname = fld.group(1), fld.group(2)
                if " static " in f" {m} " and " final " not in f" {m} ":
                    t.static_fields.append(f"{display}.{fname}")
                if ftype in ("javax.swing.Timer", "java.util.Timer"):
                    t.timers.append(f"{display}.{fname}")
        # private fields can be static or timers too
        for m in info.members:
            if not m.startswith("private ") or "(" in m:
                continue
            fld = re.match(r"^private\s+(?:(?:static|final|volatile|transient)\s+)*([\w.$<>\[\], ]+?)\s+(\w+)(?:\s*=.*)?$", m)
            if fld:
                if " static " in f" {m} " and " final " not in f" {m} ":
                    t.static_fields.append(f"{display}.{fld.group(2)}")
                if fld.group(1) in ("javax.swing.Timer", "java.util.Timer"):
                    t.timers.append(f"{display}.{fld.group(2)}")
        t.members[display] = members
    # how a test triggers one timer event: call the ActionListener of the object that owns the timer
    subject_fields = {}
    if fq in infos:
        for m in infos[fq].members:
            fld = re.match(r"^(?:(?:public|protected|private|static|final|volatile|transient)\s+)*([\w.$]+)\s+(\w+)$", m)
            if fld and "(" not in m:
                subject_fields[fld.group(1)] = fld.group(2)
    var = class_name[:1].lower() or "s"
    for name in family:
        if "java.awt.event.ActionListener" not in infos[name].implements:
            continue
        if name == fq:
            t.listeners.append(f"{var}.actionPerformed(null)")
        elif name in subject_fields:
            t.listeners.append(f"{var}.{subject_fields[name]}.actionPerformed(null)")
        else:
            t.listeners.append(f"actionPerformed(null) on its {name.split('$')[-1]} object")
    if t.listeners:
        t.listeners = [f"`{x}`" if x.startswith(var + ".") else x for x in t.listeners]
        t.listeners[0] += f" (where {var} is the {class_name} under test)"
    return t
