package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.TestDesigners;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.Dedup;
import edu.uta.cse4392.ta3.tools.Jacoco;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;
import static edu.uta.cse4392.ta3.nodes.Common.str;

/** Steps 7, 11 and 12: measure branch coverage with JaCoCo; if it is below 100 %, add test cases. */
public final class Coverage {

    private Coverage() {
    }

    public static Map<String, Object> measureCoverage(RunState state, Context ctx) {
        String phase = state.phase();
        Path project = state.project(phase);
        Object runs = state.map("run_counter").get(phase);
        int n = runs instanceof Number x ? x.intValue() : 0;
        Path xml = project.resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.xml");
        Map<String, Object> coverage = state.map("coverage");
        if (!Files.exists(xml)) {
            coverage.put(phase, null);
            ctx.tracer.event("measure_coverage", "warning", "no JaCoCo report at " + xml);
            return Common.update("coverage", coverage, "events",
                    events(note("measure_coverage", "No JaCoCo report was produced")));
        }
        Map<String, Object> summary = Jacoco.summarize(Jacoco.parse(xml));
        Path outDir = ctx.phaseDir(phase).resolve("coverage").resolve(String.format("run-%02d", n));
        Text.copyTree(xml.getParent(), outDir.resolve("jacoco"));
        String title = "Coverage of the " + phase + " code (test run " + n + ")";
        Text.write(outDir.resolve("summary.md"), Jacoco.toMarkdown(summary, title));
        Json.write(outDir.resolve("summary.json"), summary);
        summary.put("run", n);
        summary.put("report_dir", outDir.toString());
        coverage.put(phase, summary);

        Map<String, Object> b = Jacoco.frac(summary, "branch");
        Map<String, Object> ln = Jacoco.frac(summary, "line");
        String message = "Branch coverage (" + phase + "): " + Jacoco.ratio(b)
                + (Boolean.TRUE.equals(summary.get("branch_vacuous")) ? " (no branches in the code)" : "")
                + "; line coverage " + Text.num(ln.get("pct")) + "%";
        ctx.tracer.event("measure_coverage", "result", message, Map.of("report", outDir.toString()));
        // tests are green here, so the repair budget starts again
        return Common.update("coverage", coverage, "repair_round", 0, "idle_repairs", 0,
                "events", events(note("measure_coverage", message)));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> coverageGap(RunState state, Context ctx) {
        String phase = state.phase();
        Path project = state.project(phase);
        int round = state.num("coverage_round") + 1;
        Map<String, Object> summary = (Map<String, Object>) state.map("coverage").get(phase);
        Map<String, String> files = Workspace.readSources(project);
        Schemas.TestSuiteDraft draft = TestDesigners.coverageTests(ctx, state.className(), phase, files, summary,
                state.cases("suite"), Common.notesFor(state, phase));
        List<Map<String, Object>> incoming = new ArrayList<>();
        for (Schemas.TestCase c : draft.testCases()) {
            Map<String, Object> data = c.toMap();
            data.put("technique", "COVERAGE");
            incoming.add(data);
        }
        Dedup.Merged merged = Dedup.merge(state.cases("suite"), incoming, "COVERAGE");
        List<Map<String, Object>> suite = merged.suite();
        List<?> added = (List<?>) merged.report().get("added");
        for (Object testId : added) {
            for (Map<String, Object> c : suite) {
                if (str(c, "id").equals(testId)) {
                    ctx.changes.record(phase, "coverage agent", "test case", testId.toString(), "add",
                            "targets a branch JaCoCo reported as never taken: " + str(c, "condition"), "",
                            Json.pretty(Common.ordered(c)), round);
                }
            }
        }
        Common.saveSuite(ctx, phase, suite);
        int dups = ((List<?>) merged.report().get("duplicates")).size();
        String message = "Coverage agent (round " + round + "): " + added.size() + " new test case(s)"
                + (dups > 0 ? ", " + dups + " duplicate(s) dropped" : "");
        ctx.tracer.event("coverage_gap", "result", message);
        return Common.update("suite", suite, "coverage_round", round, "events", events(note("coverage_gap", message)));
    }
}
