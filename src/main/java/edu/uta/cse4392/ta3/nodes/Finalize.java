package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.JavaSrc;
import edu.uta.cse4392.ta3.tools.PatternCheck;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;
import static edu.uta.cse4392.ta3.nodes.Common.str;

/**
 * Last node: writes summary.md and copies the deliverables of each phase into final/. final/original and
 * final/refactored are each a complete Maven project (pom.xml, the code, the JUnit test class and its TestSupport
 * helper) plus the test cases, the last test run's results and the JaCoCo coverage report, so anyone can rerun a
 * phase with `mvn test`.
 */
public final class Finalize {

    private Finalize() {
    }

    @SuppressWarnings("unchecked")
    private static String pct(Object cov, String kind) {
        if (!(cov instanceof Map<?, ?> c)) {
            return "n/a";
        }
        Map<String, Object> f = (Map<String, Object>) ((Map<String, Object>) c).get(kind);
        int total = ((Number) f.get("total")).intValue();
        return total > 0 ? f.get("covered") + "/" + total + " (" + Text.num(f.get("pct")) + "%)" : "no branches (100%)";
    }

    /** The language model recorded when the run started (run.json), so a later `report` keeps it. */
    private static String llmUsed(Context ctx) {
        Path info = ctx.runDir.resolve("run.json");
        if (Files.exists(info)) {
            try {
                Object llm = Json.readMap(info).get("llm");
                if (llm != null && !llm.toString().isEmpty()) {
                    return llm.toString();
                }
            } catch (RuntimeException ignored) {
                // fall back to the current settings
            }
        }
        return ctx.settings.describe();
    }

    /**
     * A path saved in the run's state. If the run folder was moved (copied to another computer, unzipped
     * elsewhere), the same file is looked up inside the current run folder.
     */
    static Path inRun(Context ctx, String saved) {
        Path path = Path.of(saved);
        if (Files.exists(path)) {
            return path;
        }
        String runId = ctx.runDir.getFileName().toString();
        String[] parts = saved.replace('\\', '/').split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].equals(runId)) {
                Path p = ctx.runDir;
                for (int k = i + 1; k < parts.length; k++) {
                    p = p.resolve(parts[k]);
                }
                return p;
            }
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private static List<String> copyPhase(Context ctx, RunState state, String phase, Path dest) {
        Object saved = state.map("projects").get(phase);
        if (saved == null) {
            return new ArrayList<>();
        }
        Path project = inRun(ctx, saved.toString());
        Text.mkdirs(dest);
        Path pomSource = Files.exists(ctx.settings.deliveryPom) ? ctx.settings.deliveryPom : project.resolve("pom.xml");
        String fq = JavaSrc.fqcn(state.pkg(), state.className());
        Text.write(dest.resolve("pom.xml"), Workspace.withMainClass(Text.read(pomSource), fq));
        for (String sub : List.of("src/main/java", "src/test/java")) {
            if (Files.exists(project.resolve(sub))) {
                Text.copyTree(project.resolve(sub), dest.resolve(sub));
            }
        }
        List<String> copied = new ArrayList<>();
        copied.add(phase + "/pom.xml + src/main/java + src/test/java (a Maven project: run `mvn test` in " + phase + "/)");
        Path phaseDir = ctx.runDir.resolve(phase);
        for (String name : List.of("test-cases.json", "test-cases.md")) {
            if (Files.exists(phaseDir.resolve(name))) {
                Text.copyFile(phaseDir.resolve(name), dest.resolve(name));
                copied.add(phase + "/" + name);
            }
        }
        List<Path> runs = Text.list(phaseDir.resolve("test-results"), "run-*");
        if (!runs.isEmpty()) {
            Path lastRun = runs.get(runs.size() - 1);
            Text.copyTree(lastRun, dest.resolve("test-results"));
            copied.add(phase + "/test-results (last test run, " + lastRun.getFileName() + ")");
        }
        Object cov = state.map("coverage").get(phase);
        if (cov instanceof Map<?, ?> c && ((Map<String, Object>) c).get("report_dir") != null) {
            Path reportDir = inRun(ctx, ((Map<String, Object>) c).get("report_dir").toString());
            if (Files.exists(reportDir)) {
                Text.copyTree(reportDir, dest.resolve("coverage"));
                copied.add(phase + "/coverage (JaCoCo HTML, XML and CSV; summary.md)");
            }
        }
        return copied;
    }

    private static Map<String, Object> fraction(Object f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f instanceof Map<?, ?> src) {
            for (String k : List.of("covered", "total", "pct")) {
                m.put(k, src.get(k));
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> finalize(RunState state, Context ctx) {
        // Build final/ next to the old one and swap at the end, so a failure never leaves final/ half-written.
        Path finalDir = ctx.runDir.resolve("final");
        Path building = ctx.runDir.resolve("final.new");
        Text.delete(building);
        Text.mkdirs(building);
        List<String> copied = new ArrayList<>(copyPhase(ctx, state, "original", building.resolve("original")));
        copied.addAll(copyPhase(ctx, state, "refactored", building.resolve("refactored")));
        for (String name : List.of("changes.md", "trace.log")) {
            if (Files.exists(ctx.runDir.resolve(name))) {
                Text.copyFile(ctx.runDir.resolve(name), building.resolve(name));
                copied.add(name);
            }
        }

        Map<String, Object> curation = state.map("curation");
        List<Map<String, Object>> changes = ctx.changes.entries();
        List<Map<String, Object>> suite = state.cases("suite");
        Path originalFile = ctx.runDir.resolve("original").resolve("test-cases.json");
        List<Map<String, Object>> originalSuite = Files.exists(originalFile) ? Common.loadSuite(originalFile) : List.of();
        Map<String, Object> cov = state.map("coverage");
        Map<String, Object> runs = state.map("run_counter");
        Map<String, Object> report = state.map("pattern_report");
        String status = state.flag("halted") ? "stopped by a person" : "completed";
        int dups = curation.get("duplicates") instanceof List<?> d ? d.size() : 0;
        List<String> lines = new ArrayList<>(List.of(
                "# Run " + state.str("run_id") + " summary", "",
                "| Item | Value |", "|---|---|",
                "| System under test | " + state.className() + " (" + (state.pkg().isEmpty() ? "default package" : state.pkg())
                        + ") |",
                "| Language model | " + llmUsed(ctx) + " |",
                "| Finished | " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now()) + " ("
                        + status + ") |", "",
                "## Test design (steps 1-4)", "",
                "- EP agent: " + curation.getOrDefault("ep_received", 0) + " test cases; BVA agent: "
                        + curation.getOrDefault("bva_received", 0) + " (the two agents ran in parallel)",
                "- Duplicates removed at the join: " + dups + "; distinct test cases: " + curation.getOrDefault("distinct", 0),
                "- Human review rounds: " + state.num("review_round") + "; test cases added by the review: "
                        + count(changes, "original", "test case", "reviser", "add"), "",
                "## Original code (steps 5-7)", "",
                "- Test cases: " + originalSuite.size() + " (" + Common.techniques(originalSuite) + ")",
                "- Test runs: " + runs.getOrDefault("original", 0),
                "- Repairs: " + count(changes, "original", "code", null, null) + " code change(s), "
                        + count(changes, "original", "test case", "repair agent", null) + " test change(s)",
                "- Test cases added for coverage: " + count(changes, "original", "test case", "coverage agent", null),
                "- Branch coverage: " + pct(cov.get("original"), "branch") + "; line coverage: "
                        + pct(cov.get("original"), "line"), "",
                "## Refactoring with the State pattern (steps 8-9)", "",
                "- Prompt: `" + ctx.settings.refactorPrompt.getFileName() + "` (version "
                        + Math.max(1, state.num("refactor_prompt_version")) + "); attempts: " + state.num("refactor_attempt")
                        + "; team review rounds: " + state.num("refactor_round"), "",
                report.isEmpty() ? "(not reached)" : PatternCheck.toMarkdown(report), "",
                "## Refactored code (steps 10-12)", "",
                "- Test cases reused from the original code: " + originalSuite.size() + "; adapted: "
                        + count(changes, "refactored", "test case", "test adapter", "modify") + "; removed as obsolete: "
                        + count(changes, "refactored", "test case", "test adapter", "delete"),
                "- Test cases: " + suite.size() + " (" + Common.techniques(suite) + ")",
                "- Test runs: " + runs.getOrDefault("refactored", 0),
                "- Repairs: " + count(changes, "refactored", "code", null, null) + " code change(s), "
                        + count(changes, "refactored", "test case", "repair agent", null) + " test change(s)",
                "- Test cases added for coverage: " + count(changes, "refactored", "test case", "coverage agent", null),
                "- Branch coverage: " + pct(cov.get("refactored"), "branch") + "; line coverage: "
                        + pct(cov.get("refactored"), "line"), "",
                "## Files in final/", ""));
        for (String c : copied) {
            lines.add("- " + c);
        }
        if (!state.str("stuck_reason").isEmpty()) {
            lines.add("");
            lines.add("Last escalation: " + state.str("stuck_reason"));
        }
        String text = String.join("\n", lines) + "\n";
        Text.write(building.resolve("summary.md"), text);
        Text.delete(finalDir);
        try {
            Files.move(building, finalDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Text.write(ctx.runDir.resolve("summary.md"), text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", state.str("run_id"));
        result.put("status", status);
        Map<String, Object> coverage = new LinkedHashMap<>();
        for (String phase : List.of("original", "refactored")) {
            if (cov.get(phase) instanceof Map<?, ?> c) {
                Map<String, Object> pc = new LinkedHashMap<>();
                pc.put("branch", fraction(c.get("branch")));
                pc.put("line", fraction(c.get("line")));
                coverage.put(phase, pc);
            }
        }
        result.put("coverage", coverage);
        Map<String, Object> testRuns = new LinkedHashMap<>();
        for (String phase : List.of("original", "refactored")) {
            if (runs.containsKey(phase)) {
                testRuns.put(phase, runs.get(phase));
            }
        }
        result.put("test_runs", testRuns);
        result.put("tests_original", originalSuite.size());
        result.put("tests_refactored", suite.size());
        result.put("pattern_checks_passed", report.get("passed"));
        Json.write(ctx.runDir.resolve("result.json"), result);
        String message = "Run " + status + ". Summary: " + ctx.runDir.resolve("summary.md");
        ctx.tracer.event("finalize", "result", message);
        return Common.update("finished", true, "events", events(note("finalize", message)));
    }

    private static int count(List<Map<String, Object>> changes, String phase, String kind, String agent, String action) {
        int n = 0;
        for (Map<String, Object> c : changes) {
            if (phase.equals(str(c, "phase")) && kind.equals(str(c, "kind")) && (agent == null || agent.equals(str(c, "agent")))
                    && (action == null || action.equals(str(c, "action")))) {
                n++;
            }
        }
        return n;
    }
}
