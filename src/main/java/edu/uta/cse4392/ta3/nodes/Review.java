package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.TestDesigners;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.Dedup;
import edu.uta.cse4392.ta3.tools.Jacoco;
import edu.uta.cse4392.ta3.tools.PatternCheck;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;
import static edu.uta.cse4392.ta3.nodes.Common.str;

/**
 * Human in the loop: the tester reviews the test cases (step 4), the team reviews the refactoring (step 9), and
 * a person decides when a loop does not converge. Each pause stops the graph at its last checkpoint, so the run
 * can be resumed later, even from another terminal.
 */
public final class Review {

    static final Map<String, List<String>> TEST_ACTIONS = actions(
            "accept", List.of("a", "accept", "ok", "yes", "y"),
            "revise", List.of("r", "revise", "changes", "change"),
            "edited", List.of("e", "edited", "edit"));
    static final Map<String, List<String>> REFACTOR_ACTIONS = actions(
            "accept", List.of("a", "accept", "ok", "yes", "y"),
            "feedback", List.of("f", "feedback", "revise", "r"),
            "prompt_edited", List.of("p", "prompt", "prompt_edited"),
            "code_edited", List.of("c", "code", "code_edited"));
    static final Map<String, List<String>> STUCK_ACTIONS = actions(
            "continue", List.of("c", "continue", "retry"),
            "stop", List.of("s", "stop", "halt", "q"));

    private Review() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> actions(Object... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], (List<String>) kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> pause(Object... kv) {
        return Dedup.mapOf(kv);
    }

    // ---------------------------------------------------------------- step 4
    @SuppressWarnings("unchecked")
    public static Map<String, Object> reviewTests(RunState state, Context ctx) {
        int n = state.num("review_round") + 1;
        List<Map<String, Object>> suite = state.cases("suite");
        Map<String, Object> curation = state.map("curation");
        Path jsonPath = Common.saveSuite(ctx, "original", suite);
        Path folder = Text.mkdirs(ctx.phaseDir("original").resolve("review"));
        Path md = folder.resolve("round-" + n + ".md");
        List<String> head = new ArrayList<>(List.of("# Test case review, round " + n, "",
                "Check that the test cases cover every equivalence class and boundary, that each expected "
                        + "result is right, and that nothing is missing. Request changes in plain words, or edit `"
                        + jsonPath + "` directly.", ""));
        List<Map<String, Object>> conflicts = curation.get("conflicts") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        if (!curation.isEmpty() && n == 1) {
            int dups = curation.get("duplicates") instanceof List<?> d ? d.size() : 0;
            head.add("EP agent " + curation.getOrDefault("ep_received", 0) + " + BVA agent "
                    + curation.getOrDefault("bva_received", 0) + " test cases; duplicates removed: " + dups
                    + " (see test-design/curation.md).");
            head.add("");
            for (Map<String, Object> c : conflicts) {
                head.add("- **Conflict:** " + c.get("kept") + " expects `" + c.get("expected_kept") + "`, " + c.get("new")
                        + " expects `" + c.get("expected_new") + "` for `" + c.get("input") + "`. Which is right?");
            }
            head.add("");
        }
        Text.write(md, String.join("\n", head)
                + Common.suiteMarkdown(suite, "Test cases", true).replaceFirst("# Test cases", "## Test cases")
                + Common.answerHint(state.str("run_id"), md, "a = accept, r = request changes, e = I edited test-cases.json"));

        Object answer = Common.answer(state, pause("kind", "review_tests", "round", n, "review_file", md.toString(),
                "suite_file", jsonPath.toString(), "count", suite.size(), "conflicts", n == 1 ? conflicts.size() : 0));
        String[] parsed = Common.parseAnswer(answer, TEST_ACTIONS);
        String action = parsed[0];
        String feedback = parsed[1];
        Map<String, Object> update = Common.update("review_round", n, "review_action", action,
                "review_feedback", feedback, "answer", null);
        int count = suite.size();
        if (action.equals("edited")) {
            List<Map<String, Object>> edited = Common.loadSuite(jsonPath);
            ctx.changes.record("original", "human tester", "test suite", "test-cases.json", "edit",
                    feedback.isEmpty() ? "edited by hand during review" : feedback,
                    Json.pretty(Common.ordered(suite)), Json.pretty(Common.ordered(edited)), n);
            update.put("suite", edited);
            count = edited.size();
        }
        String message = switch (action) {
            case "accept" -> "Tester accepted " + suite.size() + " test cases";
            case "revise" -> "Tester requested changes: " + feedback;
            default -> "Tester edited test-cases.json (" + count + " test cases)";
        };
        ctx.tracer.event("review_tests", "decision", message, Map.of("round", n));
        update.put("events", events(note("review_tests", message)));
        return update;
    }

    public static Map<String, Object> reviseTests(RunState state, Context ctx) {
        List<Map<String, Object>> suite = state.cases("suite");
        Map<String, String> files = Workspace.readSources(state.project("original"));
        String feedback = state.str("review_feedback");
        Schemas.SuiteRevision revision = TestDesigners.revise(ctx, state.className(), files, suite, feedback,
                Common.notesFor(state, "original"));
        Integer round = state.has("review_round") ? state.num("review_round") : null;
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> c : suite) {
            byId.put(str(c, "id"), c);
        }
        for (String testId : revision.delete()) {
            if (byId.containsKey(testId)) {
                ctx.changes.record("original", "reviser", "test case", testId, "delete", feedback,
                        Json.pretty(Common.ordered(byId.get(testId))), "", round);
            }
        }
        Set<String> deleted = new HashSet<>(revision.delete());
        suite.removeIf(c -> deleted.contains(str(c, "id")));
        List<Schemas.TestCase> toAdd = new ArrayList<>(revision.add());
        for (Schemas.TestCase c : revision.modify()) {
            Map<String, Object> data = c.toMap();
            boolean found = false;
            for (int i = 0; i < suite.size(); i++) {
                Map<String, Object> existing = suite.get(i);
                if (str(existing, "id").equals(c.id())) {
                    data.put("also", existing.get("also") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>());
                    ctx.changes.record("original", "reviser", "test case", c.id(), "modify", feedback,
                            Json.pretty(Common.ordered(existing)), Json.pretty(Common.ordered(data)), round);
                    suite.set(i, data);
                    found = true;
                    break;
                }
            }
            if (!found) {
                toAdd.add(c);
            }
        }
        List<Map<String, Object>> added = new ArrayList<>();
        for (Schemas.TestCase c : toAdd) {
            Map<String, Object> data = c.toMap();
            if (c.technique().isEmpty()) {
                data.put("technique", "REVIEW");
            }
            added.add(data);
        }
        Dedup.Merged merged = Dedup.merge(suite, added, "REVIEW");
        suite = merged.suite();
        for (Object testId : (List<?>) merged.report().get("added")) {
            for (Map<String, Object> c : suite) {
                if (str(c, "id").equals(testId)) {
                    ctx.changes.record("original", "reviser", "test case", testId.toString(), "add", feedback, "",
                            Json.pretty(Common.ordered(c)), round);
                }
            }
        }
        Common.saveSuite(ctx, "original", suite);
        String message = "Reviser: +" + ((List<?>) merged.report().get("added")).size() + " added, "
                + revision.modify().size() + " modified, " + revision.delete().size() + " deleted (" + suite.size()
                + " test cases)";
        ctx.tracer.event("revise_tests", "result", message, Map.of("summary", revision.summary()));
        return Common.update("suite", suite, "events", events(note("revise_tests", message)));
    }

    // ---------------------------------------------------------------- step 9
    @SuppressWarnings("unchecked")
    public static Map<String, Object> reviewRefactoring(RunState state, Context ctx) {
        int n = state.num("refactor_round") + 1;
        Path project = state.project("refactored");
        Path folder = Text.mkdirs(ctx.phaseDir("refactored").resolve("review"));
        Path md = folder.resolve("round-" + n + ".md");
        Map<String, Object> report = state.map("pattern_report");
        Map<String, Object> compiled = state.map("refactor_compile");
        Map<String, String> files = Workspace.readSources(project);
        List<String> body = new ArrayList<>(List.of("# Refactoring review, round " + n, "",
                "Prompt: `" + ctx.settings.refactorPrompt + "` (version " + Math.max(1, state.num("refactor_prompt_version"))
                        + "), attempt " + Math.max(1, state.num("refactor_attempt")) + ".", "",
                "Review the code against the State pattern: Subject with the original interface, an abstract "
                        + "State, one concrete class per state, delegation, transitions, no conditionals on the state.", "",
                "**Compiles:** " + (Boolean.TRUE.equals(compiled.get("ok")) ? "yes" : "NO"), ""));
        if (compiled.get("compile_errors") instanceof List<?> errors) {
            for (Map<String, Object> e : (List<Map<String, Object>>) errors) {
                body.add("- " + e.get("file") + " line " + e.get("line") + ": " + e.get("message"));
            }
        }
        String design = state.str("refactor_design").strip();
        body.addAll(List.of("", "## Automated checks", "", PatternCheck.toMarkdown(report), "",
                "## The agent's design notes", "", design.isEmpty() ? "(none)" : design, "", "## Files", ""));
        files.forEach((name, code) -> body.addAll(List.of("### " + name, "", "```java", Text.rstrip(code), "```", "")));
        Json.write(folder.resolve("round-" + n + ".files.json"), files);
        Text.write(md, String.join("\n", body) + Common.answerHint(state.str("run_id"), md,
                "a = accept, f = feedback (regenerate), p = I edited the prompt, c = I edited the code"));

        Object answer = Common.answer(state, pause("kind", "review_refactoring", "round", n, "review_file", md.toString(),
                "project", String.valueOf(project), "prompt_file", ctx.settings.refactorPrompt.toString(),
                "compiles", Boolean.TRUE.equals(compiled.get("ok")), "checks_passed", report.get("passed")));
        String[] parsed = Common.parseAnswer(answer, REFACTOR_ACTIONS);
        String action = parsed[0];
        String feedback = parsed[1];
        Map<String, Object> update = Common.update("refactor_round", n, "refactor_action", action,
                "refactor_feedback", feedback, "answer", null);
        if (action.equals("feedback") || action.equals("prompt_edited")) {
            update.put("refactor_attempt", 0);
            if (action.equals("prompt_edited")) {
                update.put("refactor_prompt_version", Math.max(1, state.num("refactor_prompt_version")) + 1);
            }
        }
        String message = switch (action) {
            case "accept" -> "Team accepted the refactored code";
            case "feedback" -> "Team asked for changes: " + feedback;
            case "prompt_edited" -> "Team revised the refactoring prompt; regenerating";
            default -> "Team edited the refactored code; re-checking";
        };
        ctx.tracer.event("review_refactoring", "decision", message, Map.of("round", n));
        update.put("events", events(note("review_refactoring", message)));
        return update;
    }

    // ---------------------------------------------------------------- loops that do not converge
    public static Map<String, Object> askHuman(RunState state, Context ctx) {
        String phase = state.phase();
        Map<String, Object> last = state.map("last_run");
        Map<String, Object> coverage = state.map("coverage");
        @SuppressWarnings("unchecked")
        Map<String, Object> cov = coverage.get(phase) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        String status = last.get("status") == null ? null : last.get("status").toString();
        String reason;
        if (status != null && !status.equals("passed")) {
            reason = "Tests on the " + phase + " code still " + status + " after " + state.num("repair_round")
                    + " repair round(s).";
            if (status.equals("build_error")) {
                reason += " Maven itself failed; see " + last.get("maven_log");
            }
        } else if (cov == null) {
            reason = "No JaCoCo report was produced (check the jacoco-maven-plugin in pom.xml).";
        } else {
            reason = "Branch coverage of the " + phase + " code is " + Text.num(Jacoco.frac(cov, "branch").get("pct"))
                    + "% after " + state.num("coverage_round") + " round(s) of extra tests. Some branches may be "
                    + "unreachable.";
        }
        Path md = ctx.runDir.resolve("decision-" + phase + ".md");
        Text.write(md, "# The agents need a decision\n\n" + reason + "\n\nYou can edit the code or `" + phase
                + "/test-cases.json` before continuing.\n"
                + Common.answerHint(state.str("run_id"), md, "c = continue for more rounds, s = stop and summarize"));
        Path suiteFile = ctx.phaseDir(phase).resolve("test-cases.json");
        Object answer = Common.answer(state, pause("kind", "stuck", "reason", reason, "phase", phase,
                "review_file", md.toString(), "suite_file", suiteFile.toString()));
        String action = Common.parseAnswer(answer, STUCK_ACTIONS)[0];
        Map<String, Object> update = Common.update("stuck_reason", reason, "stuck_action", action, "repair_round", 0,
                "idle_repairs", 0, "coverage_round", 0, "halted", action.equals("stop"), "answer", null);
        if (action.equals("continue") && Files.exists(suiteFile)) {
            update.put("suite", Common.loadSuite(suiteFile)); // picks up hand edits
        }
        String message = "Person chose to " + action + ": " + reason;
        ctx.tracer.event("ask_human", "decision", message);
        update.put("events", events(note("ask_human", message)));
        return update;
    }
}
