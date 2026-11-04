package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Settings;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.Refactorer;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.JavaSrc;
import edu.uta.cse4392.ta3.tools.Maven;
import edu.uta.cse4392.ta3.tools.PatternCheck;
import edu.uta.cse4392.ta3.tools.RefactorParse;
import edu.uta.cse4392.ta3.tools.Testability;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;
import static edu.uta.cse4392.ta3.nodes.Common.str;

/**
 * Step 8: the refactoring agent applies the State pattern with the team's final prompt; the result is compiled
 * and checked against the pattern before the team reviews it (step 9).
 */
public final class Refactoring {

    private Refactoring() {
    }

    /** The test code of the whole suite (to find the members the tests use). */
    static String testCode(RunState state) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> c : state.cases("suite")) {
            parts.add(str(c, "junit"));
        }
        return String.join("\n", parts);
    }

    private record Checked(Map<String, Object> compiled, Map<String, Object> report) {
    }

    private static Checked check(RunState state, Context ctx, Path project) {
        Path log = ctx.runDir.resolve("logs").resolve("maven-refactored-compile-" + state.num("refactor_attempt") + ".log");
        Map<String, Object> compiled = Maven.compileOnly(project, ctx.settings, log, false);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checks", new ArrayList<>());
        report.put("passed", false);
        report.put("classes", new ArrayList<>());
        if (Boolean.TRUE.equals(compiled.get("ok"))) {
            String fq = JavaSrc.fqcn(state.pkg(), state.className());
            report = PatternCheck.check(state.project("original").resolve("target").resolve("classes"), fq,
                    project.resolve("target").resolve("classes"), fq, testCode(state));
        }
        Path folder = ctx.phaseDir("refactored");
        Text.write(folder.resolve("pattern-check.md"), "# State pattern checks\n\n"
                + (Boolean.TRUE.equals(compiled.get("ok")) ? "Compiles: yes\n\n" : "Compiles: NO\n\n")
                + PatternCheck.toMarkdown(report));
        Json.write(folder.resolve("pattern-check.json"), report);
        return new Checked(compiled, report);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> refactor(RunState state, Context ctx) {
        Settings s = ctx.settings;
        int attempt = state.num("refactor_attempt") + 1;
        Path original = state.project("original");
        Map<String, String> originalFiles = Workspace.readSources(original); // the version that passed every test
        Map<String, Object> lastCompile = state.map("refactor_compile");
        Path previousProject = state.project("refactored");
        List<Map<String, Object>> compileErrors = null;
        Map<String, String> previous = null;
        boolean lastOk = !lastCompile.containsKey("ok") || Boolean.TRUE.equals(lastCompile.get("ok"));
        if (attempt > 1 && !lastOk && previousProject != null) {
            compileErrors = lastCompile.get("compile_errors") instanceof List<?> l && !l.isEmpty()
                    ? (List<Map<String, Object>>) l
                    : List.of(Map.of("file", "?", "line", 0, "message",
                    tail(str(lastCompile, "log_tail"), 800)));
            previous = Workspace.readSources(previousProject);
        }
        String teamFeedback = "feedback".equals(state.str("refactor_action")) ? state.str("refactor_feedback") : "";
        if (!teamFeedback.isEmpty() && previous == null && previousProject != null) {
            previous = Workspace.readSources(previousProject);
        }
        String feedback = Refactorer.feedbackBlock(teamFeedback, compileErrors, previous);

        // the members to keep come from the code as it is now (repairs may have changed it since setup)
        Map<String, Object> analyses = state.map("testability");
        Path classes = original.resolve("target").resolve("classes");
        if (Files.isDirectory(classes)) {
            analyses.put("original", Testability.analyze(String.join("\n\n", originalFiles.values()), classes,
                    state.className(), state.pkg()).asMap());
        }
        String reply = Refactorer.refactor(ctx, s.refactorPrompt, state.className(), state.pkg(), originalFiles,
                state.strings("original_api"), feedback, Common.membersText(analyses, testCode(state)), "");
        Map<String, String> files = RefactorParse.parseFiles(reply);
        String design = RefactorParse.designNotes(reply);

        Path folder = ctx.phaseDir("refactored");
        int version = Math.max(1, state.num("refactor_prompt_version"));
        Text.copyFile(s.refactorPrompt, folder.resolve("prompt-v" + version + ".md"));
        Path attemptDir = Text.mkdirs(folder.resolve("attempts").resolve("v" + version + "-attempt-" + attempt));
        Text.write(attemptDir.resolve("reply.md"), reply);
        Text.write(folder.resolve("design.md"), "# Design notes from the refactoring agent\n\n" + design + "\n");

        String fq = JavaSrc.fqcn(state.pkg(), state.className());
        Path project = Workspace.createProject(folder.resolve("project"), s.pomTemplate, fq);
        if (!files.isEmpty()) {
            Workspace.writeSources(project, state.pkg(), files, true);
        }
        files.forEach((name, code) -> Text.write(attemptDir.resolve(name), code));

        Map<String, Object> compiled;
        Map<String, Object> report;
        if (!files.containsKey(state.className() + ".java")) {
            compiled = new LinkedHashMap<>();
            compiled.put("ok", false);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("file", state.className() + ".java");
            e.put("line", 0);
            e.put("scope", "main");
            e.put("message", files.isEmpty() ? "the reply has no Java files" : "the reply has no file for the Subject class");
            compiled.put("compile_errors", new ArrayList<>(List.of(e)));
            compiled.put("log_tail", "");
            report = new LinkedHashMap<>();
            report.put("checks", new ArrayList<>());
            report.put("passed", false);
            report.put("classes", new ArrayList<>());
        } else {
            Checked checked = check(state, ctx, project);
            compiled = checked.compiled();
            report = checked.report();
        }

        Map<String, Object> projects = state.map("projects");
        projects.put("refactored", project.toString());
        boolean ok = Boolean.TRUE.equals(compiled.get("ok"));
        String status = ok ? "compiles" : "does not compile (" + ((List<?>) compiled.get("compile_errors")).size() + " errors)";
        String message = "Refactoring agent (prompt v" + version + ", attempt " + attempt + "): " + files.size()
                + " files, " + status;
        if (ok) {
            message += ", " + (Boolean.TRUE.equals(report.get("passed")) ? "all State pattern checks pass"
                    : "some pattern checks fail");
        }
        ctx.tracer.event("refactor", "result", message, Map.of("files", new ArrayList<>(new TreeSet<>(files.keySet()))));
        return Common.update("projects", projects, "refactor_attempt", attempt, "refactor_design", design,
                "testability", analyses, "refactor_compile", compiled, "pattern_report", report,
                "events", events(note("refactor", message)));
    }

    private static String tail(String text, int chars) {
        return text.length() <= chars ? text : text.substring(text.length() - chars);
    }

    /** After the team edited the refactored code by hand: compile and check again. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> recheckRefactoring(RunState state, Context ctx) {
        Path project = state.project("refactored");
        int round = Math.max(1, state.num("refactor_round"));
        Path snapshot = ctx.phaseDir("refactored").resolve("review").resolve("round-" + round + ".files.json");
        Map<String, Object> before = Files.exists(snapshot) ? Json.readMap(snapshot) : new LinkedHashMap<>();
        Workspace.readSources(project).forEach((name, code) -> {
            String old = before.get(name) == null ? "" : before.get(name).toString();
            if (!old.strip().equals(code.strip())) {
                ctx.changes.record("refactored", "team", "code", name, before.containsKey(name) ? "modify" : "add",
                        "edited by hand during the refactoring review", old, code,
                        state.has("refactor_round") ? state.num("refactor_round") : null);
            }
        });
        Checked checked = check(state, ctx, project);
        String message = "Re-checked the hand-edited refactoring: "
                + (Boolean.TRUE.equals(checked.compiled().get("ok")) ? "compiles" : "does not compile")
                + (Boolean.TRUE.equals(checked.report().get("passed")) ? ", pattern checks pass" : "");
        return Common.update("refactor_compile", checked.compiled(), "pattern_report", checked.report(),
                "events", events(note("recheck_refactoring", message)));
    }
}
