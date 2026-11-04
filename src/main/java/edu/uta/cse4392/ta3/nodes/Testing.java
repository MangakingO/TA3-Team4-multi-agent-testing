package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.RepairAgent;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.JUnitWriter;
import edu.uta.cse4392.ta3.tools.Maven;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;
import static edu.uta.cse4392.ta3.nodes.Common.str;

/** Steps 5-6 and 10: run the test cases with Maven and, when tests fail, repair the code or the tests. */
public final class Testing {

    private Testing() {
    }

    @SuppressWarnings("unchecked")
    private static String resultsMarkdown(Map<String, Object> result, String phase, int n,
                                          List<Map<String, Object>> suite) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> c : suite) {
            byId.put(str(c, "id"), c);
        }
        List<String> out = new ArrayList<>(List.of("# Test run " + n + " (" + phase + " code)", "",
                "Status: **" + result.get("status") + "**  ",
                "Maven exit code " + result.get("exit_code") + ", " + result.get("seconds") + " s. Full log: `maven.log`.",
                ""));
        if (result.get("counts") instanceof Map<?, ?> counts) {
            Map<String, Object> c = (Map<String, Object>) counts;
            int total = 0;
            for (Object v : c.values()) {
                total += ((Number) v).intValue();
            }
            out.add("Tests run: " + total + ", passed " + c.get("passed") + ", failed " + c.get("failed") + ", errors "
                    + c.get("error") + ", skipped " + c.get("skipped"));
            out.add("");
        }
        List<Map<String, Object>> tests = (List<Map<String, Object>>) result.get("tests");
        if (!tests.isEmpty()) {
            out.add("| Test | Technique | Result | Message |");
            out.add("|---|---|---|---|");
            for (Map<String, Object> t : tests) {
                String tech = str(byId.getOrDefault(str(t, "id"), Map.of()), "technique");
                String msg = Text.head(Text.squash(str(t, "message")).replace("|", "\\|"), 160);
                out.add("| " + t.get("id") + " | " + tech + " | " + t.get("status") + " | " + msg + " |");
            }
        }
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("compile_errors");
        if (!errors.isEmpty()) {
            out.addAll(List.of("", "## Compile errors", ""));
            for (Map<String, Object> e : errors) {
                String where = e.get("test_id") != null ? " (test " + e.get("test_id") + ")" : "";
                out.add("- [" + e.get("scope") + "] " + e.get("file") + " line " + e.get("line") + where + ": "
                        + e.get("message"));
            }
        }
        if (result.get("log_tail") != null && !str(result, "log_tail").isEmpty()) {
            out.addAll(List.of("", "## End of the Maven log", "", "```", str(result, "log_tail"), "```"));
        }
        return String.join("\n", out) + "\n";
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> runTests(RunState state, Context ctx) {
        String phase = state.phase();
        Path project = state.project(phase);
        List<Map<String, Object>> suite = state.cases("suite");
        JUnitWriter.Rendered rendered = JUnitWriter.render(state.className(), state.pkg(), suite,
                ctx.settings.testTimeoutSeconds);
        Workspace.writeTestClass(project, state.pkg(), state.className(), rendered.source());

        Map<String, Object> counter = state.map("run_counter");
        int n = (counter.get(phase) instanceof Number x ? x.intValue() : 0) + 1;
        counter.put(phase, n);
        Path outDir = Text.mkdirs(ctx.phaseDir(phase).resolve("test-results").resolve(String.format("run-%02d", n)));
        Map<String, Object> result = Maven.runTests(project, ctx.settings, outDir.resolve("maven.log"));

        Map<String, String> ids = new HashMap<>();
        for (Map<String, Object> c : suite) {
            ids.put(JUnitWriter.methodName(str(c, "id")), str(c, "id"));
        }
        for (Map<String, Object> t : (List<Map<String, Object>>) result.get("tests")) {
            t.put("id", ids.getOrDefault(str(t, "method"), str(t, "method")));
        }
        for (Map<String, Object> e : (List<Map<String, Object>>) result.get("compile_errors")) {
            if ("test".equals(e.get("scope"))) {
                e.put("test_id", JUnitWriter.blame(rendered.lineMap(), ((Number) e.get("line")).intValue()));
            }
        }
        Path reports = project.resolve("target").resolve("surefire-reports");
        if (Files.isDirectory(reports)) {
            Text.copyTree(reports, outDir.resolve("surefire-reports"));
        }
        Text.write(outDir.resolve(state.className() + "Test.java"), rendered.source());
        Text.write(outDir.resolve("summary.md"), resultsMarkdown(result, phase, n, suite));
        Json.write(outDir.resolve("result.json"), result);
        Common.saveSuite(ctx, phase, suite);

        String message;
        String status = str(result, "status");
        if (status.equals("compile_error")) {
            message = "Test run " + n + " (" + phase + "): compile errors (" + ((List<?>) result.get("compile_errors")).size() + ")";
        } else if (result.get("counts") instanceof Map<?, ?> counts) {
            message = "Test run " + n + " (" + phase + "): " + counts.get("passed") + " passed, " + counts.get("failed")
                    + " failed, " + counts.get("error") + " errors" + (status.equals("passed") ? " -- all tests pass" : "");
        } else {
            message = "Test run " + n + " (" + phase + "): " + status;
        }
        result.put("run", n);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("results", outDir.toString());
        ctx.tracer.event("run_tests", "result", message, data);
        return Common.update("last_run", result, "run_counter", counter, "events", events(note("run_tests", message)));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> repair(RunState state, Context ctx) {
        String phase = state.phase();
        Path project = state.project(phase);
        int round = state.num("repair_round") + 1;
        Map<String, Object> last = state.map("last_run");
        List<Map<String, Object>> tests = last.get("tests") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> failing = new ArrayList<>();
        int passing = 0;
        for (Map<String, Object> t : tests) {
            String status = str(t, "status");
            if (status.equals("failed") || status.equals("error")) {
                failing.add(t);
            } else if (status.equals("passed")) {
                passing++;
            }
        }
        List<Map<String, Object>> compileErrors = last.get("compile_errors") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        Map<String, String> files = Workspace.readSources(project);
        Schemas.RepairPlan plan = RepairAgent.propose(ctx, phase, state.className(), files, failing, compileErrors,
                state.cases("suite"), passing, Common.notesFor(state, phase));
        String agent = "repair agent";
        Common.EditResult edited = Common.applyTestEdits(ctx, phase, agent, state.cases("suite"), plan.testEdits(), round);
        int codeChanges = Common.applyCodeEdits(ctx, phase, agent, project, state.pkg(), plan.codeEdits(), round,
                phase.equals("refactored"));
        Common.saveSuite(ctx, phase, edited.suite());
        Path folder = Text.mkdirs(ctx.phaseDir(phase).resolve("repairs"));
        Json.write(folder.resolve(String.format("round-%02d.json", round)), plan);
        int idle = state.num("idle_repairs") + (edited.changed() + codeChanges == 0 ? 1 : 0);
        String diagnosis = Text.head(Text.squash(plan.diagnosis()), 300);
        String message = "Repair agent (round " + round + "): " + codeChanges + " code edit(s), " + edited.changed()
                + " test edit(s). Diagnosis: " + diagnosis;
        ctx.tracer.event("repair", "decision", message);
        return Common.update("suite", edited.suite(), "repair_round", round, "idle_repairs", idle,
                "events", events(note("repair", message, "code_edits", codeChanges, "test_edits", edited.changed())));
    }
}
