package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.TestDesigners;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.Dedup;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;

/** Steps 1-3: the EP and BVA agents run in parallel, then the curator joins and de-duplicates. */
public final class Design {

    private Design() {
    }

    private static Map<String, Object> design(RunState state, Context ctx, String technique) {
        String lower = technique.toLowerCase(Locale.ROOT);
        String node = lower + "_agent";
        Map<String, String> files = Workspace.readSources(state.project("original"));
        Schemas.TestSuiteDraft draft = TestDesigners.design(ctx, technique, state.className(), state.pkg(), files,
                state.str("observed"), Common.notesFor(state, "original"));
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Schemas.TestCase c : draft.testCases()) {
            Map<String, Object> data = c.toMap();
            data.put("technique", technique); // the agent's technique, whatever the model wrote
            cases.add(data);
        }
        Path folder = Text.mkdirs(ctx.phaseDir("original").resolve("test-design"));
        Json.write(folder.resolve(lower + "-cases.json"), cases);
        Text.write(folder.resolve(lower + "-analysis.md"), "# " + technique + " analysis\n\n" + draft.analysis().strip() + "\n");
        ctx.tracer.event(node, "result", cases.size() + " " + technique + " test cases");
        return Common.update(
                lower + "_cases", cases,
                lower + "_analysis", draft.analysis(),
                "events", events(note(node, technique + " agent designed " + cases.size() + " test cases")));
    }

    public static Map<String, Object> epAgent(RunState state, Context ctx) {
        return design(state, ctx, "EP");
    }

    public static Map<String, Object> bvaAgent(RunState state, Context ctx) {
        return design(state, ctx, "BVA");
    }

    /** Join point of the two parallel agents: one set of distinct test cases. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> curate(RunState state, Context ctx) {
        Dedup.Merged merged = Dedup.curate(state.cases("ep_cases"), state.cases("bva_cases"));
        Map<String, Object> report = merged.report();
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) report.get("duplicates");
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) report.get("conflicts");
        List<Map<String, Object>> dropped = (List<Map<String, Object>>) report.get("dropped");
        Path folder = Text.mkdirs(ctx.phaseDir("original").resolve("test-design"));
        List<String> lines = new ArrayList<>(List.of("# Curation (join + duplicate elimination)", "",
                "- EP agent: " + report.get("ep_received") + " test cases",
                "- BVA agent: " + report.get("bva_received") + " test cases",
                "- duplicates removed: " + duplicates.size(),
                "- distinct test cases: " + report.get("distinct"),
                "- conflicts for the reviewer: " + conflicts.size(), ""));
        if (!duplicates.isEmpty()) {
            lines.addAll(List.of("## Duplicates removed", "", "| Kept | Removed | Rule |", "|---|---|---|"));
            for (Map<String, Object> d : duplicates) {
                lines.add("| " + d.get("kept") + " | " + d.get("removed") + " | " + d.get("rule") + " |");
            }
        }
        if (!conflicts.isEmpty()) {
            lines.addAll(List.of("", "## Conflicts (same input, different expected result)", ""));
            for (Map<String, Object> c : conflicts) {
                lines.add("- " + c.get("kept") + " expects `" + c.get("expected_kept") + "` but " + c.get("new")
                        + " expects `" + c.get("expected_new") + "` for input `" + c.get("input") + "`");
            }
        }
        if (!dropped.isEmpty()) {
            lines.addAll(List.of("", "## Dropped", ""));
            for (Map<String, Object> d : dropped) {
                lines.add("- " + d.get("id") + ": " + d.get("reason"));
            }
        }
        Text.write(folder.resolve("curation.md"), String.join("\n", lines) + "\n");
        Json.write(folder.resolve("curation.json"), report);
        Common.saveSuite(ctx, "original", merged.suite());
        String message = "Curator joined " + report.get("ep_received") + " EP + " + report.get("bva_received")
                + " BVA test cases: " + duplicates.size() + " duplicate" + Text.plural(duplicates.size()) + " removed, "
                + report.get("distinct") + " distinct"
                + (conflicts.isEmpty() ? "" : ", " + conflicts.size() + " conflicts for review");
        ctx.tracer.event("curate", "result", message);
        return Common.update("suite", merged.suite(), "curation", report, "events", events(note("curate", message)));
    }
}
