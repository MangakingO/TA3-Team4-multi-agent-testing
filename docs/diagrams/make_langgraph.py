"""Draws the compiled LangGraph graph: its nodes and edges are read from the code, not typed by hand.

    python docs/diagrams/make_langgraph.py

Writes langgraph.mmd (Mermaid). With the Mermaid CLI installed (npm install -g @mermaid-js/mermaid-cli),
it also writes langgraph.svg and langgraph.png. Solid edges are fixed; dashed edges are conditional,
labelled with the condition the routing function in graph.py checks.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(OUT.parents[1]))

from agent_system import graph as G  # noqa: E402
from agent_system.config import Settings  # noqa: E402
from agent_system.context import Context  # noqa: E402

# What each node is (its colour) and which step it does
NODES = {
    "setup": ("tool", "compile, observe, analyze"),
    "ep_agent": ("agent", "step 1: EP test cases"),
    "bva_agent": ("agent", "step 1: BVA test cases"),
    "curate": ("tool", "steps 2-3: join, remove duplicates"),
    "review_tests": ("person", "step 4: the tester reviews"),
    "revise_tests": ("agent", "step 4: the reviser applies changes"),
    "run_tests": ("tool", "steps 5, 10: mvn test"),
    "repair": ("agent", "step 6: fix the code or the test, log it"),
    "measure_coverage": ("tool", "steps 7, 11: JaCoCo branch coverage"),
    "coverage_gap": ("agent", "steps 7, 12: tests for missed branches"),
    "refactor": ("agent", "step 8: State pattern, final prompt P3"),
    "review_refactoring": ("person", "step 9: the team reviews"),
    "recheck_refactoring": ("tool", "step 9: compile, javap checks"),
    "adapt_tests": ("agent", "step 10: reuse, adapt or remove tests"),
    "ask_human": ("person", "a loop reached its limit"),
    "finalize": ("tool", "save results, summary.md, final/"),
}

# Conditional edges: the condition each routing function in graph.py checks
LABELS = {
    ("review_tests", "revise_tests"): "changes requested",
    ("review_tests", "run_tests"): "accepted",
    ("run_tests", "measure_coverage"): "all pass",
    ("run_tests", "repair"): "failures",
    ("run_tests", "ask_human"): "repair limit or build error",
    ("measure_coverage", "coverage_gap"): "branch coverage < 100%",
    ("measure_coverage", "refactor"): "100%, original code",
    ("measure_coverage", "finalize"): "100%, refactored code",
    ("measure_coverage", "ask_human"): "coverage limit",
    ("refactor", "refactor"): "compile errors: retry",
    ("refactor", "review_refactoring"): "compiles",
    ("review_refactoring", "adapt_tests"): "accepted",
    ("review_refactoring", "refactor"): "feedback or edited prompt",
    ("review_refactoring", "recheck_refactoring"): "code edited",
    ("ask_human", "run_tests"): "continue",
    ("ask_human", "finalize"): "stop",
}

GROUPS = [
    ("design", "Steps 1-4: design the test cases", ["setup", "ep_agent", "bva_agent", "curate", "review_tests", "revise_tests"]),
    ("test", "Steps 5-7 and 10-12: test, repair, cover (original, then refactored code)",
     ["run_tests", "repair", "measure_coverage", "coverage_gap", "ask_human"]),
    ("refactoring", "Steps 8-10: refactor and reuse the tests", ["refactor", "review_refactoring", "recheck_refactoring",
                                                                 "adapt_tests"]),
]

STYLE = {
    "agent": "fill:#EDE9FE,stroke:#7C3AED,stroke-width:1.5px,color:#0F172A",
    "tool": "fill:#E2E8F0,stroke:#475569,stroke-width:1.5px,color:#0F172A",
    "person": "fill:#FEF3C7,stroke:#D97706,stroke-width:1.5px,color:#0F172A",
}


def compiled_graph():
    with tempfile.TemporaryDirectory() as tmp:
        settings = Settings.from_env()
        settings.runs_dir = Path(tmp)
        run_dir = Path(tmp) / "graph"
        run_dir.mkdir()
        app = G.build(Context.create(settings, run_dir))
        return app.get_graph()


def mermaid(graph) -> str:
    names = [n for n in graph.nodes if not n.startswith("__")]
    missing = set(names) ^ set(NODES)
    if missing:
        raise SystemExit(f"NODES does not match the graph: {sorted(missing)}")
    # ELK lays out the groups and routes every edge from node to node (the default layout does not)
    lines = ["---", "config:", "  layout: elk", "---", "flowchart TB", '    START(( )):::start']
    for key, title, members in GROUPS:
        lines.append(f'    subgraph {key}["{title}"]')
        lines.append("        direction TB")
        for name in members:
            kind, what = NODES[name]
            lines.append(f'        {name}["<b>{name}</b><br/>{what}"]:::{kind}')
        lines.append("    end")
    grouped = {m for _, _, members in GROUPS for m in members}
    for name in names:
        if name not in grouped:
            kind, what = NODES[name]
            lines.append(f'    {name}["<b>{name}</b><br/>{what}"]:::{kind}')
    lines.append("    END((( ))):::stop")
    for edge in graph.edges:
        source = "START" if edge.source == "__start__" else edge.source
        target = "END" if edge.target == "__end__" else edge.target
        if edge.conditional:
            label = LABELS.get((edge.source, edge.target))
            if not label:
                raise SystemExit(f"no label for the conditional edge {edge.source} -> {edge.target}")
            lines.append(f'    {source} -. "{label}" .-> {target}')
        else:
            lines.append(f"    {source} --> {target}")
    used = {(e.source, e.target) for e in graph.edges if e.conditional}
    stale = set(LABELS) - used
    if stale:
        raise SystemExit(f"LABELS has edges the graph does not have: {sorted(stale)}")
    for kind, style in STYLE.items():
        lines.append(f"    classDef {kind} {style}")
    lines.append("    classDef start fill:#0F172A,stroke:#0F172A")
    lines.append("    classDef stop fill:#0F172A,stroke:#0F172A")
    for key, _, _ in GROUPS:
        lines.append(f"    style {key} fill:#F8FAFC,stroke:#CBD5E1,stroke-width:1.4px,color:#0F172A")
    return "\n".join(lines) + "\n"


def main() -> None:
    graph = compiled_graph()
    source = mermaid(graph)
    mmd = OUT / "langgraph.mmd"
    mmd.write_text(source, encoding="utf-8")
    print(f"wrote {mmd.name}: {len([n for n in graph.nodes if not n.startswith('__')])} nodes, {len(graph.edges)} edges")
    mmdc = shutil.which("mmdc")
    if not mmdc:
        print("Mermaid CLI (mmdc) not found: paste langgraph.mmd into https://mermaid.live to draw it")
        return
    config = {"theme": "base", "themeVariables": {
        "fontFamily": "Inter, Segoe UI, Helvetica, Arial, sans-serif", "fontSize": "15px", "lineColor": "#334155",
        "primaryTextColor": "#0F172A", "edgeLabelBackground": "#FFFFFF", "clusterBkg": "#F8FAFC",
        "clusterBorder": "#CBD5E1"},
        "flowchart": {"curve": "basis", "htmlLabels": True, "nodeSpacing": 46, "rankSpacing": 54, "padding": 14}}
    with tempfile.TemporaryDirectory() as tmp:
        cfg = Path(tmp) / "config.json"
        cfg.write_text(json.dumps(config), encoding="utf-8")
        for ext, extra in (("svg", []), ("png", ["-s", "2"])):
            subprocess.run([mmdc, "-i", str(mmd), "-o", str(OUT / f"langgraph.{ext}"), "-c", str(cfg), "-b", "white",
                            *extra], check=True, capture_output=True)
    print("wrote langgraph.svg, langgraph.png")


if __name__ == "__main__":
    main()
