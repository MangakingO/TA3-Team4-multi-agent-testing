package edu.uta.cse4392.ta3.graph;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Settings;
import edu.uta.cse4392.ta3.Text;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.StateGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the compiled LangGraph4j workflow as Mermaid: the nodes and edges come from {@link Workflow} (the same
 * tables the graph is built from), checked against the compiled graph. Solid edges are fixed; dashed edges are
 * conditional, labelled with the condition the routing function checks.
 *
 * <pre>
 * java -jar target/ta3-agents.jar graph &gt; docs/diagrams/langgraph.mmd
 * mmdc -i docs/diagrams/langgraph.mmd -o docs/diagrams/langgraph.svg      (Mermaid CLI)
 * </pre>
 */
public final class GraphDiagram {

    /** What each node is (its colour) and which step it does. */
    static final Map<String, String[]> NODES = table(
            "setup", "tool", "compile, observe, analyze",
            "ep_agent", "agent", "step 1: EP test cases",
            "bva_agent", "agent", "step 1: BVA test cases",
            "curate", "tool", "steps 2-3: join, remove duplicates",
            "review_tests", "person", "step 4: the tester reviews",
            "revise_tests", "agent", "step 4: the reviser applies changes",
            "run_tests", "tool", "steps 5, 10: mvn test",
            "repair", "agent", "step 6: fix the code or the test, log it",
            "measure_coverage", "tool", "steps 7, 11: JaCoCo branch coverage",
            "coverage_gap", "agent", "steps 7, 12: tests for missed branches",
            "refactor", "agent", "step 8: State pattern, final prompt P3",
            "review_refactoring", "person", "step 9: the team reviews",
            "recheck_refactoring", "tool", "step 9: compile, javap checks",
            "adapt_tests", "agent", "step 10: reuse, adapt or remove tests",
            "ask_human", "person", "a loop reached its limit",
            "finalize", "tool", "save results, summary.md, final/");

    /** Conditional edges: the condition each routing function in Workflow checks. */
    static final Map<String, String> LABELS = labels(
            "review_tests", "revise_tests", "changes requested",
            "review_tests", "run_tests", "accepted",
            "run_tests", "measure_coverage", "all pass",
            "run_tests", "repair", "failures",
            "run_tests", "ask_human", "repair limit or build error",
            "measure_coverage", "coverage_gap", "branch coverage < 100%",
            "measure_coverage", "refactor", "100%, original code",
            "measure_coverage", "finalize", "100%, refactored code",
            "measure_coverage", "ask_human", "coverage limit",
            "refactor", "refactor", "compile errors: retry",
            "refactor", "review_refactoring", "compiles",
            "review_refactoring", "adapt_tests", "accepted",
            "review_refactoring", "refactor", "feedback or edited prompt",
            "review_refactoring", "recheck_refactoring", "code edited",
            "ask_human", "run_tests", "continue",
            "ask_human", "finalize", "stop");

    private record Group(String key, String title, List<String> members) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("design", "Steps 1-4: design the test cases",
                    List.of("setup", "ep_agent", "bva_agent", "curate", "review_tests", "revise_tests")),
            new Group("test", "Steps 5-7 and 10-12: test, repair, cover (original, then refactored code)",
                    List.of("run_tests", "repair", "measure_coverage", "coverage_gap", "ask_human")),
            new Group("refactoring", "Steps 8-10: refactor and reuse the tests",
                    List.of("refactor", "review_refactoring", "recheck_refactoring", "adapt_tests")));

    private static final Map<String, String> STYLE = Map.of(
            "agent", "fill:#EDE9FE,stroke:#7C3AED,stroke-width:1.5px,color:#0F172A",
            "tool", "fill:#E2E8F0,stroke:#475569,stroke-width:1.5px,color:#0F172A",
            "person", "fill:#FEF3C7,stroke:#D97706,stroke-width:1.5px,color:#0F172A");

    private GraphDiagram() {
    }

    private static Map<String, String[]> table(String... v) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 < v.length; i += 3) {
            m.put(v[i], new String[] {v[i + 1], v[i + 2]});
        }
        return m;
    }

    private static Map<String, String> labels(String... v) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 < v.length; i += 3) {
            m.put(v[i] + " -> " + v[i + 1], v[i + 2]);
        }
        return m;
    }

    /** The workflow as Mermaid (ELK layout, grouped by the steps of the assignment). */
    public static String mermaid(Settings settings) throws Exception {
        Path tmp = Files.createTempDirectory("ta3-graph");
        String builtIn;
        try {
            Context ctx = Context.create(settings, tmp);
            CompiledGraph<RunState> app = Workflow.build(ctx, null);
            builtIn = app.getGraph(GraphRepresentation.Type.MERMAID, "TA3", true).content();
        } finally {
            Text.delete(tmp);
        }
        Set<String> missing = new HashSet<>(Workflow.NODES.keySet());
        missing.removeAll(NODES.keySet());
        if (!missing.isEmpty() || NODES.size() != Workflow.NODES.size()) {
            throw new IllegalStateException("NODES does not match the workflow: " + missing);
        }
        for (String node : Workflow.NODES.keySet()) {
            if (!builtIn.contains(node)) {
                throw new IllegalStateException("the compiled graph has no node " + node);
            }
        }
        List<String> lines = new ArrayList<>(List.of("---", "config:", "  layout: elk", "---", "flowchart TB",
                "    START(( )):::start"));
        Set<String> grouped = new HashSet<>();
        for (Group g : GROUPS) {
            lines.add("    subgraph " + g.key() + "[\"" + g.title() + "\"]");
            lines.add("        direction TB");
            for (String name : g.members()) {
                lines.add("        " + node(name));
                grouped.add(name);
            }
            lines.add("    end");
        }
        for (String name : Workflow.NODES.keySet()) {
            if (!grouped.contains(name)) {
                lines.add("    " + node(name));
            }
        }
        lines.add("    END((( ))):::stop");
        for (String[] e : Workflow.EDGES) {
            lines.add("    " + id(e[0]) + " --> " + id(e[1]));
        }
        Set<String> used = new HashSet<>();
        for (Map.Entry<String, List<String>> route : Workflow.ROUTES.entrySet()) {
            for (String target : route.getValue()) {
                String key = route.getKey() + " -> " + target;
                String label = LABELS.get(key);
                if (label == null) {
                    throw new IllegalStateException("no label for the conditional edge " + key);
                }
                used.add(key);
                lines.add("    " + route.getKey() + " -. \"" + label + "\" .-> " + target);
            }
        }
        Set<String> stale = new HashSet<>(LABELS.keySet());
        stale.removeAll(used);
        if (!stale.isEmpty()) {
            throw new IllegalStateException("LABELS has edges the workflow does not have: " + stale);
        }
        STYLE.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(s -> lines.add("    classDef " + s.getKey() + " " + s.getValue()));
        lines.add("    classDef start fill:#0F172A,stroke:#0F172A");
        lines.add("    classDef stop fill:#0F172A,stroke:#0F172A");
        for (Group g : GROUPS) {
            lines.add("    style " + g.key() + " fill:#F8FAFC,stroke:#CBD5E1,stroke-width:1.4px,color:#0F172A");
        }
        return String.join("\n", lines) + "\n";
    }

    private static String node(String name) {
        String[] info = NODES.get(name);
        return name + "[\"<b>" + name + "</b><br/>" + info[1] + "\"]:::" + info[0];
    }

    private static String id(String node) {
        if (node.equals(StateGraph.START)) {
            return "START";
        }
        return node.equals(StateGraph.END) ? "END" : node;
    }
}
