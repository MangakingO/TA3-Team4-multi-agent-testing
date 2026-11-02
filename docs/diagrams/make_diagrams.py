"""Draws the UML activity diagram and the architecture diagrams as SVG (and PNG if cairosvg is installed).

    python docs/diagrams/make_diagrams.py

Writes, each with a PNG next to it:
  activity.svg            UML activity diagram, all 12 steps (activity-part1.svg: steps 1-7, part2: 8-12)
  architecture.svg        components and connections
  architecture-flow.svg   runtime flow: what each part sends and gets back
  datamodel.svg           agent outputs, the LangGraph state, and the files a run saves
The compiled LangGraph graph itself is drawn by make_langgraph.py.
"""
from __future__ import annotations

from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parent
FONT = "Inter, 'Segoe UI', Helvetica, Arial, sans-serif"
INK = "#0F172A"
EDGE = "#334155"
KINDS = {
    "agent": ("#EDE9FE", "#7C3AED"),   # LLM agent
    "tool": ("#E2E8F0", "#475569"),    # deterministic tool
    "person": ("#FEF3C7", "#D97706"),  # human in the loop
}
LINE_H = 20
FONT_SIZE = 16
FONT_FILES = {400: "Inter-Regular.otf", 600: "Inter-SemiBold.otf", 700: "Inter-Bold.otf"}


def measure(text: str, size: int = FONT_SIZE, weight: int = 400) -> float:
    """Width of a text in px (Inter via Pillow when available, else an estimate)."""
    try:
        from PIL import ImageFont

        font = ImageFont.truetype(f"/usr/share/fonts/opentype/inter/{FONT_FILES[weight]}", size)
        return float(font.getlength(text))
    except (ImportError, OSError):
        return len(text) * size * (0.56 if weight < 600 else 0.6)


class Canvas:
    def __init__(self, width: int, height: int):
        self.w, self.h = width, height
        self.parts: list[str] = []

    # ------------------------------------------------------------- primitives
    def add(self, s: str) -> None:
        self.parts.append(s)

    def text(self, x, y, s, size=FONT_SIZE, weight=400, anchor="middle", fill=INK, italic=False):
        style = ' font-style="italic"' if italic else ""
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
                 f'text-anchor="{anchor}" fill="{fill}"{style}>{escape(s)}</text>')

    def vtext(self, x, y, s, size=FONT_SIZE, weight=400, fill=INK):
        """Text turned 90 degrees (reads bottom to top), centred on y; the glyphs sit left of x."""
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" transform="rotate(-90 {x:.1f} {y:.1f})" font-family="{FONT}" '
                 f'font-size="{size}" font-weight="{weight}" text-anchor="middle" fill="{fill}">{escape(s)}</text>')

    def lines(self, cx, cy, lines, size=FONT_SIZE, weight=400):
        top = cy - (len(lines) - 1) * LINE_H / 2
        for i, line in enumerate(lines):
            self.text(cx, top + i * LINE_H + size * 0.36, line, size, weight)

    def action(self, cx, cy, w, lines, kind):
        fill, stroke = KINDS[kind]
        widest = max(measure(line) for line in lines)
        if widest > w - 16:
            print(f"warning: '{max(lines, key=measure)}' is {widest:.0f}px wide in a {w}px box")
        h = 32 + LINE_H * (len(lines) - 1) + 12
        self.add(f'<rect x="{cx - w / 2:.1f}" y="{cy - h / 2:.1f}" width="{w}" height="{h}" rx="14" '
                 f'fill="{fill}" stroke="{stroke}" stroke-width="1.6"/>')
        self.lines(cx, cy, lines)
        return {"top": cy - h / 2, "bottom": cy + h / 2, "left": cx - w / 2, "right": cx + w / 2, "cx": cx, "cy": cy}

    def diamond(self, cx, cy, r=14):
        pts = f"{cx},{cy - r} {cx + r},{cy} {cx},{cy + r} {cx - r},{cy}"
        self.add(f'<polygon points="{pts}" fill="white" stroke="{INK}" stroke-width="1.6"/>')
        return {"top": cy - r, "bottom": cy + r, "left": cx - r, "right": cx + r, "cx": cx, "cy": cy}

    def bar(self, x1, x2, y, t=8):
        self.add(f'<rect x="{x1}" y="{y - t / 2}" width="{x2 - x1}" height="{t}" rx="2" fill="{INK}"/>')

    def start(self, cx, cy):
        self.add(f'<circle cx="{cx}" cy="{cy}" r="11" fill="{INK}"/>')
        return {"bottom": cy + 11, "cx": cx, "cy": cy}

    def end(self, cx, cy):
        self.add(f'<circle cx="{cx}" cy="{cy}" r="13" fill="white" stroke="{INK}" stroke-width="1.8"/>')
        self.add(f'<circle cx="{cx}" cy="{cy}" r="8" fill="{INK}"/>')
        return {"top": cy - 13, "cx": cx, "cy": cy}

    def connector(self, cx, cy, label):
        self.add(f'<circle cx="{cx}" cy="{cy}" r="17" fill="white" stroke="{INK}" stroke-width="1.6"/>')
        self.text(cx, cy + 6, label, 16, 600)
        return {"top": cy - 17, "bottom": cy + 17, "cx": cx, "cy": cy}

    def arrow(self, points, label=None, at=None, anchor="start", head=True):
        d = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
        self.add(f'<polyline points="{d}" fill="none" stroke="{EDGE}" stroke-width="1.6" stroke-linejoin="round"/>')
        if head:
            (x1, y1), (x2, y2) = points[-2], points[-1]
            if abs(x2 - x1) > abs(y2 - y1):
                s = 1 if x2 > x1 else -1
                tri = f"{x2},{y2} {x2 - 10 * s},{y2 - 5} {x2 - 10 * s},{y2 + 5}"
            else:
                s = 1 if y2 > y1 else -1
                tri = f"{x2},{y2} {x2 - 5},{y2 - 10 * s} {x2 + 5},{y2 - 10 * s}"
            self.add(f'<polygon points="{tri}" fill="{EDGE}"/>')
        if label:
            x, y = at
            self.text(x, y, label, 14, 400, anchor, EDGE)

    def panel(self, x, y, w, h, bold, rest):
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.4"/>')
        bw, rw = measure(bold, 19, 700), measure(rest, 19, 400)
        left = x + (w - (bw + 10 + rw)) / 2
        self.text(left, y + 34, bold, 19, 700, "start")
        self.text(left + bw + 10, y + 34, rest, 19, 400, "start")

    def legend(self, x, y, items, note=None):
        cx = x
        for kind, label in items:
            fill, stroke = KINDS[kind]
            self.add(f'<rect x="{cx}" y="{y - 13}" width="26" height="18" rx="5" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
            self.text(cx + 36, y + 1, label, 15, 400, "start")
            cx += 36 + measure(label, 15) + 34
        if note:
            self.text(cx + 10, y + 1, note, 15, 400, "start", "#475569", italic=True)

    def save(self, name: str) -> Path:
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
               f'viewBox="0 0 {self.w} {self.h}">\n<rect width="100%" height="100%" fill="white"/>\n'
               + "\n".join(self.parts) + "\n</svg>\n")
        path = OUT / f"{name}.svg"
        path.write_text(svg, encoding="utf-8")
        try:
            import cairosvg

            cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=str(OUT / f"{name}.png"), scale=2)
        except ImportError:
            pass
        return path


# ======================================================================= activity diagram
PANEL_W, PANEL_TOP, PANEL_H = 450, 16, 742
MAIN_W, SIDE_W = 232, 160


def down(c: Canvas, a: dict, b: dict, label=None):
    c.arrow([(a["cx"], a["bottom"]), (b["cx"], b["top"])], label,
            (a["cx"] + 10, (a["bottom"] + b["top"]) / 2 + 5) if label else None)


def column_a(c: Canvas, x0: int) -> None:
    """Steps 1-4: design the test cases."""
    c.panel(x0, PANEL_TOP, PANEL_W, PANEL_H, "Steps 1-4", "Design the test cases")
    cx = x0 + 250
    s = c.start(cx, 80)
    load = c.action(cx, 148, 268, ["Setup: compile with Maven,", "run the program, analyze", "its testability"], "tool")
    down(c, s, load)
    c.bar(x0 + 36, x0 + 432, 228)
    c.arrow([(cx, load["bottom"]), (cx, 224)], head=False)
    c.text(cx + 12, 216, "fork (parallel)", 14, 400, "start", "#475569", italic=True)
    ep = c.action(x0 + 132, 298, 196, ["EP agent:", "equivalence", "partitioning tests"], "agent")
    bva = c.action(x0 + 336, 298, 196, ["BVA agent:", "boundary value", "analysis tests"], "agent")
    for box in (ep, bva):
        c.arrow([(box["cx"], 232), (box["cx"], box["top"])])
        c.arrow([(box["cx"], box["bottom"]), (box["cx"], 364)])
    c.bar(x0 + 36, x0 + 432, 368)
    c.text(cx + 12, 392, "join (waits for both)", 14, 400, "start", "#475569", italic=True)
    cur = c.action(cx, 440, 268, ["Curator: merge the two sets", "and remove duplicates"], "tool")
    c.arrow([(cx, 372), (cx, cur["top"])])
    m1 = c.diamond(cx, 506)
    down(c, cur, m1)
    rev = c.action(cx, 568, 230, ["Tester reviews", "the test cases"], "person")
    down(c, m1, rev)
    d1 = c.diamond(cx, 652)
    down(c, rev, d1)
    fix = c.action(x0 + 88, 652, 140, ["Reviser applies", "the requested", "changes"], "agent")
    c.arrow([(d1["left"], 652), (fix["right"], 652)], "[changes]", (fix["right"] + 6, 644))
    c.arrow([(fix["cx"], fix["top"]), (fix["cx"], 506), (m1["left"], 506)])
    a = c.connector(cx, 728, "A")
    c.arrow([(cx, d1["bottom"]), (cx, a["top"])], "[accepted]", (cx + 10, 696))


def column_b(c: Canvas, x0: int) -> None:
    """Steps 5-7: test the original code until all tests pass and branch coverage is 100%."""
    c.panel(x0, PANEL_TOP, PANEL_W, PANEL_H, "Steps 5-7", "Test the original code")
    cx, sx, lane = x0 + 136, x0 + 340, x0 + 436
    a = c.connector(cx, 84, "A")
    m2 = c.diamond(cx, 146)
    m2b = c.diamond(cx, 196)
    down(c, a, m2)
    down(c, m2, m2b)
    run = c.action(cx, 262, MAIN_W, ["Run all test cases", "(mvn test, JUnit 5)"], "tool")
    down(c, m2b, run)
    d2 = c.diamond(cx, 336)
    down(c, run, d2)
    rep = c.action(sx, 336, SIDE_W, ["Repair agent:", "fix the code or", "the test; log it"], "agent")
    c.arrow([(d2["right"], 336), (rep["left"], 336)], "[failures]", (d2["right"] + 8, 327))
    c.arrow([(sx, rep["top"]), (sx, 196), (m2b["right"], 196)])
    cov = c.action(cx, 432, MAIN_W, ["Measure branch", "coverage (JaCoCo)"], "tool")
    c.arrow([(cx, d2["bottom"]), (cx, cov["top"])], "[all pass]", (cx + 10, 390))
    d3 = c.diamond(cx, 506)
    down(c, cov, d3)
    gap = c.action(sx, 506, SIDE_W, ["Coverage agent:", "tests for branches", "never taken"], "agent")
    c.arrow([(d3["right"], 506), (gap["left"], 506)], "[< 100%]", (d3["right"] + 8, 497))
    c.arrow([(gap["right"], 506), (lane, 506), (lane, 146), (m2["right"], 146)])
    b = c.connector(cx, 600, "B")
    c.arrow([(cx, d3["bottom"]), (cx, b["top"])], "[100%]", (cx + 10, 556))


def column_c(c: Canvas, x0: int) -> None:
    """Steps 8-9: refactor with the State pattern; the team reviews the result."""
    c.panel(x0, PANEL_TOP, PANEL_W, PANEL_H, "Steps 8-9", "Refactor with the State pattern")
    cx, sx, lane = x0 + 136, x0 + 340, x0 + 436
    b = c.connector(cx, 84, "B")
    m3 = c.diamond(cx, 146)
    m3b = c.diamond(cx, 196)
    down(c, b, m3)
    down(c, m3, m3b)
    ref = c.action(cx, 272, 250, ["Refactoring agent: apply", "the State pattern with", "the team's final prompt"], "agent")
    down(c, m3b, ref)
    chk = c.action(cx, 370, MAIN_W, ["Compile; check the", "pattern with javap"], "tool")
    down(c, ref, chk)
    d4 = c.diamond(cx, 442)
    down(c, chk, d4)
    c.arrow([(d4["right"], 442), (sx, 442), (sx, 196), (m3b["right"], 196)], "[compile", (sx + 8, 428))
    c.text(sx + 8, 446, "errors]", 14, 400, "start", EDGE)
    rev = c.action(cx, 516, MAIN_W, ["Team reviews the", "refactored code"], "person")
    c.arrow([(cx, d4["bottom"]), (cx, rev["top"])], "[compiles]", (cx + 10, 476))
    d5 = c.diamond(cx, 590)
    down(c, rev, d5)
    fix = c.action(sx, 590, SIDE_W, ["Team revises", "the prompt or", "gives feedback"], "person")
    c.arrow([(d5["right"], 590), (fix["left"], 590)], "[not yet]", (d5["right"] + 8, 581))
    c.arrow([(fix["right"], 590), (lane, 590), (lane, 146), (m3["right"], 146)])
    cc = c.connector(cx, 682, "C")
    c.arrow([(cx, d5["bottom"]), (cx, cc["top"])], "[accepted]", (cx + 10, 636))


def column_d(c: Canvas, x0: int) -> None:
    """Steps 10-12: adapt and rerun the tests on the refactored code until 100% branch coverage."""
    c.panel(x0, PANEL_TOP, PANEL_W, PANEL_H, "Steps 10-12", "Test the refactored code")
    cx, sx, lane = x0 + 136, x0 + 340, x0 + 436
    cc = c.connector(cx, 84, "C")
    ad = c.action(cx, 156, 250, ["Test adapter: reuse the", "tests; adapt or remove", "the ones that do not compile"], "agent")
    down(c, cc, ad)
    m4 = c.diamond(cx, 226)
    m4b = c.diamond(cx, 270)
    down(c, ad, m4)
    down(c, m4, m4b)
    run = c.action(cx, 332, MAIN_W, ["Run all test cases", "(mvn test, JUnit 5)"], "tool")
    down(c, m4b, run)
    d6 = c.diamond(cx, 402)
    down(c, run, d6)
    rep = c.action(sx, 402, SIDE_W, ["Repair agent:", "fix the refactored", "code; log it"], "agent")
    c.arrow([(d6["right"], 402), (rep["left"], 402)], "[failures]", (d6["right"] + 8, 393))
    c.arrow([(sx, rep["top"]), (sx, 270), (m4b["right"], 270)])
    cov = c.action(cx, 496, MAIN_W, ["Measure branch", "coverage (JaCoCo)"], "tool")
    c.arrow([(cx, d6["bottom"]), (cx, cov["top"])], "[all pass]", (cx + 10, 456))
    d7 = c.diamond(cx, 568)
    down(c, cov, d7)
    gap = c.action(sx, 568, SIDE_W, ["Coverage agent:", "more test cases"], "agent")
    c.arrow([(d7["right"], 568), (gap["left"], 568)], "[< 100%]", (d7["right"] + 8, 559))
    c.arrow([(gap["right"], 568), (lane, 568), (lane, 226), (m4["right"], 226)])
    save = c.action(cx, 650, 250, ["Save test cases, results", "and coverage; summary"], "tool")
    c.arrow([(cx, d7["bottom"]), (cx, save["top"])], "[100%]", (cx + 10, 606))
    e = c.end(cx, 716)
    c.arrow([(cx, save["bottom"]), (cx, e["top"])])


LEGEND = [("agent", "LLM agent (Ollama)"), ("tool", "Tool (Maven, JaCoCo, javap, Python)"),
          ("person", "Person (human in the loop)")]
NOTE = "Every loop has a limit; at the limit a person decides to continue or stop."


def activity() -> None:
    gap = 18
    width = 4 * PANEL_W + 3 * gap + 32
    c = Canvas(width, PANEL_TOP + PANEL_H + 52)
    for i, fn in enumerate((column_a, column_b, column_c, column_d)):
        fn(c, 16 + i * (PANEL_W + gap))
    c.legend(24, PANEL_TOP + PANEL_H + 32, LEGEND, NOTE)
    c.save("activity")

    for name, fns in (("activity-part1", (column_a, column_b)), ("activity-part2", (column_c, column_d))):
        c = Canvas(2 * PANEL_W + gap + 32, PANEL_TOP + PANEL_H + 52)
        for i, fn in enumerate(fns):
            fn(c, 16 + i * (PANEL_W + gap))
        c.legend(24, PANEL_TOP + PANEL_H + 32, LEGEND)
        c.save(name)




# ======================================================================= architecture diagram
def box(c: Canvas, x, y, w, h, title, lines=(), kind="tool", title_size=17, size=14.5, outline=False):
    fill, stroke = KINDS.get(kind, ("#FFFFFF", "#475569"))
    if outline:
        fill = "white"
    c.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{fill}" stroke="{stroke}" '
          f'stroke-width="{2.2 if outline else 1.5}"/>')
    ty = y + 26
    c.text(x + 14, ty, title, title_size, 700, "start")
    for i, line in enumerate(lines):
        if measure(line, int(size)) > w - 26:
            print(f"warning: '{line}' too wide for a {w}px box")
        c.text(x + 14, ty + 22 + i * 19.5, line, size, 400, "start", "#1E293B")
    return {"x": x, "y": y, "w": w, "h": h, "cx": x + w / 2, "cy": y + h / 2, "right": x + w, "bottom": y + h}


def group(c: Canvas, x, y, w, h, title, stroke="#CBD5E1", fill="#F8FAFC", size=18):
    c.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="{fill}" stroke="{stroke}" stroke-width="1.6"/>')
    c.text(x + 16, y + 30, title, size, 700, "start")


def two_way(c: Canvas, p1, p2, label=None, at=None, anchor="middle"):
    c.arrow([p1, p2], label, at, anchor)
    c.arrow([p2, p1])


def _h(n_lines: int) -> int:
    return int(48 + 19.5 * n_lines + 8)


def architecture() -> None:
    c = Canvas(1880, 920)

    # ---------------- inputs (left column, top)
    group(c, 16, 16, 300, 588, "Inputs")
    y = 62
    for title, lines in [
        ("SeasonSwitch.java", ["the class under test", "(package cse4392)"]),
        ("Prompt templates", ["prompts/*.md: one per agent", "(EP, BVA, reviser, repair,", "coverage, adapter)"]),
        ("Final refactoring prompt", ["refactor_state_pattern.md", "(P3, chosen in the", "prompt lab)"]),
        ("Settings (.env)", ["LLM_PROVIDER: ollama or", "manual; LLM_MODEL", "Maven command, pom.xml", "loop limits, observe", "arguments"]),
    ]:
        box(c, 32, y, 268, _h(len(lines)), title, lines, "tool", outline=True)
        y += _h(len(lines)) + 14

    # ---------------- people (left column, bottom)
    people_lines = ["Tester: reviews the test", "cases (step 4)", "Team: reviews the refactored",
                    "code (step 9)", "Anyone: decides when a loop", "does not converge",
                    "Answers: review files or", "the console"]
    people = box(c, 16, 624, 300, _h(len(people_lines)), "People (human in the loop)", people_lines, "person")

    # ---------------- agent system
    group(c, 340, 16, 1150, 740, "Agent system  (Python 3, LangGraph, LangChain)", stroke="#94A3B8", fill="#FFFFFF")
    orch = box(c, 368, 62, 1102, 128, "Orchestrator: LangGraph StateGraph  (graph.py)",
               ["16 nodes for the 12 steps; conditional edges route the loops (repair, coverage, review)",
                "fork / join: the EP and BVA agents run in parallel; interrupts pause the graph for people",
                "shared AgentState; every step saved in SQLite checkpoints, so a run can stop and resume"], "tool",
               title_size=18)
    c.add(f'<rect x="{orch["x"]}" y="{orch["y"]}" width="{orch["w"]}" height="{orch["h"]}" rx="12" fill="none" '
          f'stroke="#0F172A" stroke-width="2.2"/>')

    group(c, 368, 214, 532, 526, "LLM agents  (agents/, prompts/)", stroke="#C4B5FD", fill="#FAF5FF", size=17)
    agents = [
        ("EP agent", ["equivalence partitioning tests", "step 1, in parallel with BVA"]),
        ("BVA agent", ["boundary value analysis tests", "step 1, in parallel with EP"]),
        ("Reviser", ["applies the tester's requests", "step 4"]),
        ("Repair agent", ["code or test wrong? fixes it", "steps 6 and 10, logs changes"]),
        ("Coverage agent", ["tests for branches never taken", "steps 7 and 12"]),
        ("Refactoring agent", ["applies the State pattern", "step 8, final prompt P3"]),
        ("Test adapter", ["reuses tests on new code", "step 10"]),
    ]
    for i, (title, lines) in enumerate(agents):
        col, row = i % 2, i // 2
        box(c, 382 + col * 256, 258 + row * 118, 242, 100, title, lines, "agent")

    group(c, 930, 214, 540, 526, "Tools  (tools/, deterministic)", stroke="#94A3B8", fill="#F8FAFC", size=17)
    tools = [
        ("Setup and testability", ["compile, observe, analyze", "before step 1"]),
        ("Curator", ["joins EP + BVA, no duplicates", "steps 2 and 3"]),
        ("JUnit renderer", ["test class + TestSupport.java", "before every test run"]),
        ("Test runner", ["mvn test, Surefire results", "steps 5 and 10"]),
        ("Coverage reader", ["JaCoCo branch coverage", "steps 7 and 11"]),
        ("Pattern checker", ["javap: State pattern rules", "step 9, for the review"]),
        ("Change log and trace", ["every change with its reason", "all steps"]),
        ("Prompt lab", ["scores prompt versions", "step 8, before the agents"]),
    ]
    for i, (title, lines) in enumerate(tools):
        col, row = i % 2, i // 2
        box(c, 946 + col * 262, 258 + row * 118, 246, 100, title, lines, "tool")

    two_way(c, (634, 190), (634, 214))
    two_way(c, (1200, 190), (1200, 214))
    c.text(646, 207, "calls with state", 13, 400, "start", EDGE, italic=True)
    c.text(1212, 207, "calls with state", 13, 400, "start", EDGE, italic=True)

    # people <-> orchestrator (graph interrupts)
    py = people["y"] + 40
    c.arrow([(people["right"], py), (352, py), (352, 168), (368, 168)])
    c.arrow([(352, 168), (352, py), (people["right"], py)])

    # ---------------- outputs
    group(c, 1514, 16, 350, 740, "Outputs  (runs/<run-id>/)")
    oy = 62
    for title, lines in [
        ("test-cases.json / .md", ["the test cases of each phase"]),
        ("test-results/run-NN", ["Surefire XML, summary.md"]),
        ("coverage/run-NN", ["JaCoCo HTML, XML, CSV"]),
        ("refactored/", ["new code, pattern-check.md"]),
        ("changes.md", ["every code or test change:", "who, why, unified diff"]),
        ("trace.log, llm/", ["every step; every prompt", "and reply"]),
        ("final/ + summary.md", ["rebuildable Maven projects"]),
    ]:
        h = 70 if len(lines) == 1 else 90
        box(c, 1530, oy, 318, h, title, lines, "tool", title_size=16, size=14, outline=True)
        oy += h + 12

    c.arrow([(316, 120), (340, 120)])
    c.arrow([(1490, 120), (1514, 120)])

    # ---------------- external services
    box(c, 368, 800, 532, 104, "Language model  (LangChain)",
        ["Ollama: qwen2.5-coder:7b on the team's machine (default)", "or manual: a person relays a web LLM (used for this run)"],
        "agent", outline=True)
    box(c, 930, 800, 540, 104, "JDK + Maven",
        ["javac, JUnit 5 (Surefire), JaCoCo agent and report,", "javap; java runs the program"], "tool", outline=True)
    two_way(c, (634, 740), (634, 800))
    two_way(c, (1200, 740), (1200, 800))
    c.text(646, 776, "prompt -> JSON or Java reply", 13, 400, "start", EDGE, italic=True)
    c.text(1212, 776, "commands -> reports", 13, 400, "start", EDGE, italic=True)

    # legend (under the outputs)
    for i, (kind, label) in enumerate([("agent", "LLM agent"), ("tool", "Deterministic tool or file"), ("person", "Person")]):
        fill, stroke = KINDS[kind]
        y = 806 + i * 34
        c.add(f'<rect x="1530" y="{y - 13}" width="26" height="18" rx="5" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        c.text(1566, y + 1, label, 15, 400, "start")
    c.save("architecture")


# ======================================================================= runtime flow (who calls whom, with what)
def hub(c: Canvas, cx, cy, r, title, lines, kind):
    """A circle for a group of graph nodes (like the agent and service circles of an architecture sketch)."""
    fill, stroke = KINDS[kind]
    c.add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
    top = cy - (len(lines) * 19 + 26) / 2 + 14
    c.text(cx, top, title, 18, 700)
    for i, line in enumerate(lines):
        y = top + 26 + i * 19
        half = max(r * r - (y - 5 - cy) ** 2, 0) ** 0.5     # half the circle's width at that height
        if measure(line, 14) / 2 > half - 10:
            print(f"warning: '{line}' is too wide for the circle")
        c.text(cx, y, line, 14, 400, "middle", "#1E293B")
    return {"cx": cx, "cy": cy, "r": r, "left": cx - r, "right": cx + r, "top": cy - r, "bottom": cy + r}


def vbar(c: Canvas, x, y, w, h, title, sub, kind, strong=False):
    """A tall bar (a layer that everything passes through) with its name written vertically."""
    fill, stroke = KINDS[kind]
    c.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" '
          f'stroke="{INK if strong else stroke}" stroke-width="{2.2 if strong else 1.6}"/>')
    cx, cy = x + w / 2, y + h / 2
    for text, size, weight in ((title, 18, 700), (sub, 14, 400)):
        if text and measure(text, size, weight) > h - 40:
            print(f"warning: '{text}' is too long for a {h}px bar")
    if sub:
        c.vtext(cx - 4, cy, title, 18, 700)
        c.vtext(cx + 18, cy, sub, 14, 400, "#1E293B")
    else:
        c.vtext(cx + 6, cy, title, 18, 700)
    return {"x": x, "y": y, "w": w, "h": h, "cx": cx, "cy": cy, "right": x + w, "bottom": y + h}


def cylinder(c: Canvas, x, y, w, h, title, lines, fill="#FFFFFF", stroke="#475569"):
    ry = 16
    c.add(f'<path d="M{x},{y + ry} L{x},{y + h - ry} A{w / 2},{ry} 0 0 0 {x + w},{y + h - ry} L{x + w},{y + ry}" '
          f'fill="{fill}" stroke="{stroke}" stroke-width="1.8"/>')
    c.add(f'<ellipse cx="{x + w / 2}" cy="{y + ry}" rx="{w / 2}" ry="{ry}" fill="{fill}" stroke="{stroke}" stroke-width="1.8"/>')
    c.text(x + w / 2, y + ry + 34, title, 17, 700)
    for i, line in enumerate(lines):
        if measure(line, 14) > w - 30:
            print(f"warning: '{line}' too wide for a {w}px cylinder")
        c.text(x + w / 2, y + ry + 58 + i * 19, line, 14, 400, "middle", "#1E293B")
    return {"x": x, "y": y, "w": w, "h": h, "cx": x + w / 2, "right": x + w, "bottom": y + h}


def pair_h(c: Canvas, x1, x2, y, forward, back, gap=34):
    """Two horizontal arrows: x1 -> x2 above (label over it), x2 -> x1 below (label under it)."""
    mid = (x1 + x2) / 2
    for label in (forward, back):
        if measure(label, 14) > abs(x2 - x1) - 12:
            print(f"warning: label '{label}' is wider than its arrow ({abs(x2 - x1)}px)")
    c.arrow([(x1, y - gap / 2), (x2, y - gap / 2)], forward, (mid, y - gap / 2 - 8), "middle")
    c.arrow([(x2, y + gap / 2), (x1, y + gap / 2)], back, (mid, y + gap / 2 + 20), "middle")


def pair_v(c: Canvas, x, y1, y2, down_label, up_label, gap=60):
    """Two vertical arrows: down on the left (label left of it), up on the right (label right of it)."""
    c.arrow([(x - gap / 2, y1), (x - gap / 2, y2)], down_label, (x - gap / 2 - 10, (y1 + y2) / 2 + 5), "end")
    c.arrow([(x + gap / 2, y2), (x + gap / 2, y1)], up_label, (x + gap / 2 + 10, (y1 + y2) / 2 + 5), "start")


def architecture_flow() -> None:
    c = Canvas(1900, 960)
    c.text(950, 46, "Runtime flow: what each part sends and gets back", 22, 700)
    top, bottom = 110, 730                               # the two layers everything passes through

    # ---------------- people and inputs
    tester = box(c, 24, 220 - _h(3) // 2, 240, _h(3), "Tester  (step 4)",
                 ["reviews the test cases:", "accept, or ask for changes", "in plain words"], "person")
    team = box(c, 24, 440 - _h(5) // 2, 240, _h(5), "Team  (step 9)",
               ["reviews the refactored code:", "accept, give feedback, or", "edit the prompt or the code;",
                "also decides when a loop", "reaches its limit"], "person")
    inputs = box(c, 24, 660 - _h(3) // 2, 240, _h(3), "Inputs",
                 ["SeasonSwitch.java", ".env settings", "prompts/*.md (final: P3)"], "tool", outline=True)

    # ---------------- the two layers
    cli = vbar(c, 440, top, 60, bottom - top, "Command line  (cli.py)",
               "python -m agent_system   run · resume · status · report · lab", "tool")
    orch = vbar(c, 690, top, 80, bottom - top, "LangGraph orchestrator  (graph.py)",
                "StateGraph · 16 nodes · conditional edges · interrupt() for people", "tool", strong=True)

    # ---------------- the graph's nodes, grouped
    agents = hub(c, 1000, 270, 112, "LLM agents",
                 ["EP, BVA, reviser,", "repair, coverage,", "refactoring,", "test adapter"], "agent")
    tools = hub(c, 1000, 560, 112, "Tools",
                ["setup, curator,", "JUnit renderer,", "test runner, JaCoCo,", "pattern checker,", "finalize"], "tool")

    # ---------------- what the nodes use
    chat = box(c, 1270, 270 - _h(3) // 2, 260, _h(3), "LangChain ChatOllama",
               ["llm.py: one call per agent", "format = the JSON Schema", "reply validated (Pydantic)"], "agent",
               outline=True)
    ollama = box(c, 1650, 270 - _h(2) // 2, 230, _h(2), "Ollama server",
                 ["qwen2.5-coder:7b", "localhost:11434"], "agent", outline=True)
    c.add('<rect x="1650" y="352" width="230" height="96" rx="10" fill="white" stroke="#7C3AED" '
          'stroke-width="1.4" stroke-dasharray="6 5"/>')
    for i, line in enumerate(["LLM_PROVIDER=manual: the", "prompt goes to a file and a", "person pastes a web LLM's",
                              "reply (used for this run)"]):
        c.text(1765, 376 + i * 19, line, 13.5, 400, "middle", EDGE, italic=True)
    jdk = box(c, 1270, 560 - _h(5) // 2, 260, _h(5), "JDK + Maven",
              ["mvn test: javac, JUnit 5", "(Surefire), JaCoCo agent", "and report", "javap -c (pattern checks)",
               "java: observe main()"], "tool", outline=True)
    runs = cylinder(c, 1650, 470, 230, 232, "runs/<run-id>/",
                    ["test-cases.json / .md", "test-results/run-NN/", "coverage/run-NN/", "refactored code",
                     "changes.md, trace.log", "llm/ prompts, replies", "final/, summary.md"])
    ckpt = cylinder(c, orch["cx"] - 110, 800, 220, 140, "checkpoints.sqlite",
                    ["SqliteSaver: AgentState", "after every step"], fill="#F1F5F9")

    # ---------------- what each connection carries
    pair_h(c, tester["right"], cli["x"], 220, "run, answers", "test review")
    pair_h(c, team["right"], cli["x"], 440, "answers", "code review")
    c.arrow([(inputs["right"], 660), (cli["x"], 660)], "read at start", ((inputs["right"] + cli["x"]) / 2, 650), "middle")
    pair_h(c, cli["right"], orch["x"], 220, "stream(input)", "updates")
    pair_h(c, cli["right"], orch["x"], 440, "resume(answer)", "interrupt()")
    pair_h(c, orch["right"], agents["left"], agents["cy"], "AgentState", "update")
    pair_h(c, orch["right"], tools["left"], tools["cy"], "AgentState", "update")
    pair_h(c, agents["right"], chat["x"], agents["cy"], "prompt + schema", "JSON / Java")
    pair_h(c, chat["right"], ollama["x"], agents["cy"], "/api/chat", "reply")
    pair_h(c, tools["right"], jdk["x"], tools["cy"], "mvn, javap, java", "XML reports")
    c.arrow([(jdk["right"], tools["cy"]), (runs["x"], tools["cy"])], "target/", ((jdk["right"] + runs["x"]) / 2, tools["cy"] - 10),
            "middle")
    route = 770
    c.arrow([(tools["cx"], tools["bottom"]), (tools["cx"], route), (runs["cx"], route), (runs["cx"], runs["bottom"])],
            "writes test cases, results, coverage, changes.md, final/", ((tools["cx"] + runs["cx"]) / 2, route - 10),
            "middle")
    pair_v(c, orch["cx"], orch["bottom"], ckpt["y"] + 2, "save each step", "load on resume", gap=40)

    # legend
    for i, (kind, label) in enumerate([("agent", "LLM agent / model"), ("tool", "Deterministic part"), ("person", "Person")]):
        fill, stroke = KINDS[kind]
        y = 830 + i * 34
        c.add(f'<rect x="30" y="{y - 13}" width="26" height="18" rx="5" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        c.text(66, y + 1, label, 15, 400, "start")
    c.save("architecture-flow")


# ======================================================================= data model
def uml(c: Canvas, x, y, w, name, note, fields, kind="tool", size=14):
    """A UML-style class box: coloured header (name, italic note), then one field per line.
    A field that starts with '#' is a group heading."""
    fill, stroke = KINDS[kind]
    head = 36 + (18 if note else 0)
    h = head + 14 + 19 * len(fields)
    c.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="white" stroke="{stroke}" stroke-width="1.5"/>')
    c.add(f'<path d="M{x},{y + head} L{x},{y + 8} Q{x},{y} {x + 8},{y} L{x + w - 8},{y} Q{x + w},{y} {x + w},{y + 8} '
          f'L{x + w},{y + head} Z" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    c.text(x + w / 2, y + 24, name, 16.5, 700)
    if note:
        c.text(x + w / 2, y + 43, note, 13, 400, "middle", "#475569", italic=True)
    for i, field in enumerate(fields):
        heading = field.startswith("#")
        text = field.lstrip("# ")
        weight = 700 if heading else 400
        if measure(text, size, weight) > w - 26:
            print(f"warning: '{text}' too wide for a {w}px class box")
        c.text(x + 12, y + head + 22 + i * 19, text, size, weight, "start", stroke if heading else "#1E293B")
    return {"x": x, "y": y, "w": w, "h": h, "cx": x + w / 2, "cy": y + h / 2, "right": x + w, "bottom": y + h}


def composition(c: Canvas, points, label=None, at=None, anchor="start"):
    """UML composition: filled diamond at the owner (first point), plain line to the part."""
    (x1, y1), (x2, y2) = points[0], points[1]
    ux, uy = ((1 if x2 > x1 else -1), 0) if abs(x2 - x1) >= abs(y2 - y1) else (0, (1 if y2 > y1 else -1))
    mx, my = x1 + ux * 9, y1 + uy * 9
    far = (x1 + ux * 18, y1 + uy * 18)
    pts = f"{x1},{y1} {mx - uy * 6},{my + ux * 6} {far[0]},{far[1]} {mx + uy * 6},{my - ux * 6}"
    c.add(f'<polygon points="{pts}" fill="{EDGE}" stroke="{EDGE}" stroke-width="1"/>')
    c.arrow([far] + list(points[1:]), label, at, anchor, head=False)


def datamodel() -> None:
    c = Canvas(1900, 1040)
    c.text(950, 44, "Data model: what the agents return, what the graph carries, what a run saves", 22, 700)

    # ---------------- agent outputs (schemas.py)
    group(c, 16, 72, 640, 950, "Agent outputs  (schemas.py)")
    c.text(32, 124, "Pydantic models; the JSON Schema goes into each prompt and Ollama's format", 13.5, 400, "start",
           "#475569", italic=True)
    draft = uml(c, 36, 148, 290, "TestSuiteDraft", "EP, BVA and coverage agents",
                ["analysis: str", "test_cases: list[TestCase]"], "agent")
    revision = uml(c, 346, 148, 290, "SuiteRevision", "reviser (step 4)",
                   ["summary: str", "add: list[TestCase]", "modify: list[TestCase]", "delete: list[str]  (ids)"], "agent")
    case = uml(c, 156, 340, 360, "TestCase", "one JUnit 5 test",
               ["id: str  (EP-01, BVA-07, COV-02)", "technique: EP | BVA | COVERAGE | REVIEW", "method: str",
                "condition: str  (the class or boundary)", "input: str", "expected: str",
                "junit: str  (body of one @Test method)"], "agent")
    composition(c, [(draft["cx"], draft["bottom"]), (draft["cx"], case["y"])], "1..*", (draft["cx"] + 8, case["y"] - 10))
    composition(c, [(revision["cx"], revision["bottom"]), (revision["cx"], case["y"])], "0..*",
                (revision["cx"] + 8, case["y"] - 10))
    plan = uml(c, 36, 600, 290, "RepairPlan", "repair agent, test adapter",
               ["diagnosis: str", "test_edits: list[TestEdit]", "code_edits: list[CodeEdit]"], "agent")
    tedit = uml(c, 386, 590, 250, "TestEdit", None,
                ["id: str", "action: modify | delete", "expected: str", "junit: str", "reason: str"], "agent")
    cedit = uml(c, 386, 755, 250, "CodeEdit", None, ["file: str", "content: str  (whole file)", "reason: str"], "agent")
    composition(c, [(plan["right"], 650), (tedit["x"], 650)], "0..*", (tedit["x"] - 34, 642))
    composition(c, [(plan["cx"], plan["bottom"]), (plan["cx"], cedit["cy"]), (cedit["x"], cedit["cy"])], "0..*",
                (cedit["x"] - 34, cedit["cy"] - 8))
    uml(c, 36, 885, 600, "Refactoring reply  (text, no schema)", "refactoring agent (step 8)",
        ["a design section with the transition table", "then one ```java block per file, each starting // File: X.java",
         "split into files by tools/refactor_parse.py"], "agent")

    # ---------------- the LangGraph state (state.py)
    group(c, 676, 72, 520, 950, "Workflow state  (state.py)")
    state = uml(c, 696, 120, 480, "AgentState", "LangGraph TypedDict: each node reads it and returns an update", [
        "# System under test",
        "run_id, sut_file, class_name, package",
        "observed: str  (what main() printed)",
        "testability: phase → notes for the prompts",
        "projects: phase → Maven project folder",
        "phase: original | refactored",
        "# Test design (steps 1-4)",
        "ep_cases, bva_cases: list[TestCase]",
        "suite: list[TestCase]  (the cases in use)",
        "curation: duplicates, conflicts",
        "review_round, review_action, review_feedback",
        "# Testing and repair (steps 5-6, 10)",
        "last_run: result of the latest mvn test",
        "run_counter: phase → runs; repair_round",
        "# Coverage (steps 7, 11-12)",
        "coverage: phase → JaCoCo summary",
        "coverage_round",
        "# Refactoring (steps 8-9)",
        "refactor_attempt, refactor_round",
        "refactor_compile, pattern_report, refactor_action",
        "# Control",
        "stuck_reason, halted, finished",
        "events: list  (every node appends one)",
    ], "tool")
    ckpt = cylinder(c, 756, 800, 360, 160, "checkpoints.sqlite",
                    ["SqliteSaver: the AgentState after every", "step of a run (thread id = run id);",
                     "resume, status and report read it"], fill="#F1F5F9")
    c.arrow([(state["cx"], state["bottom"]), (state["cx"], ckpt["y"] + 2)], "saved after every step",
            (state["cx"] + 10, (state["bottom"] + ckpt["y"]) / 2 + 5))
    c.arrow([(case["right"], case["cy"]), (state["x"], case["cy"])], "merged into suite",
            ((case["right"] + state["x"]) / 2, case["cy"] - 10), "middle")

    # ---------------- the files a run saves
    group(c, 1216, 72, 668, 950, "Run artifacts  (runs/<run-id>/)")
    left = [
        ("<phase>/test-cases.json, .md", ["the TestCase list of each phase", "also: ids merged into a case"]),
        ("test-results/run-NN/result.json", ["status: passed, failed, compile_error", "counts: passed, failed, error, skipped",
                                             "tests[]: id, status, message", "compile_errors[]: test id, line, text"]),
        ("coverage/run-NN/summary.json", ["branch, line, instruction, method:", "covered, total, pct",
                                          "classes[]: per class and method", "missed_branches[], missed_lines[]"]),
        ("test-design/curation.json", ["ep_received, bva_received, distinct", "duplicates[], conflicts[]"]),
        ("<phase>/project/", ["the Maven project: src/main/java,", "src/test/java (JUnit + TestSupport),",
                              "target/ (Surefire and JaCoCo)"]),
    ]
    right = [
        ("refactored/pattern-check.json", ["checks[]: id, check, result, detail", "state_type, concrete_states",
                                           "switch and branch counts,", "before and after"]),
        ("changes.jsonl, changes.md", ["n, time, phase, agent, kind,", "target, action, round, reason,",
                                       "diff (unified); every TestEdit,", "CodeEdit and revision is one entry"]),
        ("llm/NNN-<agent>.prompt.md", ["and .response.md: every prompt", "and reply, in order"]),
        ("trace.log, trace.jsonl", ["every step: node, thread,", "timing, events"]),
        ("final/", ["original/ and refactored/: Maven", "projects with tests, results", "and coverage; summary.md"]),
    ]
    first = None
    for col, items in ((1236, left), (1560, right)):
        y = 120
        for title, lines in items:
            h = _h(len(lines))
            b = box(c, col, y, 304, h, title, lines, "tool", title_size=15.5, size=14, outline=True)
            first = first or b
            y += h + 14
    c.arrow([(state["right"], first["cy"]), (first["x"], first["cy"])], "write", ((state["right"] + first["x"]) / 2,
            first["cy"] - 10), "middle")
    c.save("datamodel")


if __name__ == "__main__":
    activity()
    architecture()
    architecture_flow()
    datamodel()
    print("wrote", ", ".join(sorted(p.name for p in OUT.glob("*.svg"))))
