package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.Adapter;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.JUnitWriter;
import edu.uta.cse4392.ta3.tools.Maven;
import edu.uta.cse4392.ta3.tools.Testability;
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

/**
 * Step 10: reuse the accepted test cases on the refactored code. Tests that no longer compile are adapted (same
 * behaviour checked) or, when they only checked an implementation detail the refactoring removed, deleted; every
 * change is logged with its reason.
 */
public final class Adapt {

    private Adapt() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> adaptTests(RunState state, Context ctx) {
        Path project = state.project("refactored");
        String pkg = state.pkg();
        String className = state.className();
        List<Map<String, Object>> suite = state.cases("suite");
        // the final suite of the original code, kept as the baseline for the refactored code
        Json.write(ctx.phaseDir("refactored").resolve("test-cases-from-original.json"), Common.ordered(suite));
        Map<String, Object> analyses = state.map("testability");

        int changedTotal = 0;
        int deletedTotal = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            JUnitWriter.Rendered rendered = JUnitWriter.render(className, pkg, suite, ctx.settings.testTimeoutSeconds);
            Workspace.writeTestClass(project, pkg, className, rendered.source());
            Map<String, Object> check = Maven.compileOnly(project, ctx.settings,
                    ctx.runDir.resolve("logs").resolve("maven-adapt-test-compile-" + attempt + ".log"), true);
            Path classes = project.resolve("target").resolve("classes");
            if (attempt == 1 && Files.isDirectory(classes)) {
                Map<String, String> sources = Workspace.readSources(project);
                Testability found = Testability.analyze(String.join("\n\n", sources.values()), classes, className, pkg);
                analyses.put("refactored", found.asMap());
                Text.write(ctx.phaseDir("refactored").resolve("testability.md"), found.toMarkdown());
            }
            List<Map<String, Object>> testErrors = new ArrayList<>();
            for (Map<String, Object> e : (List<Map<String, Object>>) check.get("compile_errors")) {
                if ("test".equals(e.get("scope"))) {
                    testErrors.add(e);
                }
            }
            if (Boolean.TRUE.equals(check.get("ok")) || testErrors.isEmpty()) {
                break; // compiles (main-code errors, if any, go to the repair agent after the test run)
            }
            Map<String, List<Map<String, Object>>> problems = new LinkedHashMap<>();
            for (Map<String, Object> e : testErrors) {
                String testId = JUnitWriter.blame(rendered.lineMap(), ((Number) e.get("line")).intValue());
                if (testId != null) {
                    problems.computeIfAbsent(testId, k -> new ArrayList<>()).add(e);
                }
            }
            if (problems.isEmpty()) {
                break;
            }
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> c : suite) {
                byId.put(str(c, "id"), c);
            }
            List<Adapter.Problem> list = new ArrayList<>();
            problems.forEach((testId, errs) -> {
                if (byId.containsKey(testId)) {
                    list.add(new Adapter.Problem(byId.get(testId), errs));
                }
            });
            Schemas.RepairPlan plan = Adapter.adapt(ctx, className, Workspace.readSources(project), list,
                    Common.notesFor(analyses, "refactored"));
            int before = suite.size();
            Common.EditResult edited = Common.applyTestEdits(ctx, "refactored", "test adapter", suite, plan.testEdits(),
                    attempt);
            suite = edited.suite();
            deletedTotal += before - suite.size();
            changedTotal += edited.changed();
            if (edited.changed() == 0) {
                break;
            }
        }

        Common.saveSuite(ctx, "refactored", suite);
        String message = changedTotal > 0
                ? "Test adapter: reused " + suite.size() + " test cases on the refactored code; "
                + (changedTotal - deletedTotal) + " adapted, " + deletedTotal + " removed as obsolete"
                : "Test adapter: all " + suite.size() + " test cases compile unchanged against the refactored code";
        ctx.tracer.event("adapt_tests", "result", message);
        return Common.update("phase", "refactored", "suite", suite, "adapted", true, "repair_round", 0,
                "idle_repairs", 0, "coverage_round", 0, "testability", analyses,
                "events", events(note("adapt_tests", message)));
    }
}
