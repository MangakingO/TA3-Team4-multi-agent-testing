package edu.uta.cse4392.ta3.lab;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Prompts;
import edu.uta.cse4392.ta3.Settings;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.agents.AgentText;
import edu.uta.cse4392.ta3.nodes.Common;
import edu.uta.cse4392.ta3.tools.JUnitWriter;
import edu.uta.cse4392.ta3.tools.Jacoco;
import edu.uta.cse4392.ta3.tools.JavaSrc;
import edu.uta.cse4392.ta3.tools.Javap;
import edu.uta.cse4392.ta3.tools.Maven;
import edu.uta.cse4392.ta3.tools.PatternCheck;
import edu.uta.cse4392.ta3.tools.RefactorParse;
import edu.uta.cse4392.ta3.tools.Testability;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Prompt lab for step 8: scores versions of the State-pattern refactoring prompt on the real code.
 *
 * <pre>
 * java -jar target/ta3-agents.jar lab &lt;run-id&gt; [--versions P0 P1 ...] [--lab-id NAME]
 * </pre>
 *
 * Uses the run's tested original code and its accepted test suite. For every prompt version in
 * prompts/refactor_versions it writes prompt-lab/&lt;lab-id&gt;/&lt;version&gt;/prompt.md, gets a reply, and scores it:
 * does the refactored code compile? does it pass the State pattern checks (javap)? how many of the existing test
 * cases compile UNCHANGED against it, and how many of those pass? and the branch coverage of the refactored code
 * by those tests. With LLM_PROVIDER=manual (the assignment's "web-based LLM"), paste prompt.md into the web chat
 * and save the answer as reply.md next to it, then run the lab again; with Ollama the lab asks the model itself.
 */
public final class PromptLab {

    private PromptLab() {
    }

    private static String[] render(Path versionFile, String className, String pkg, String source, List<String> api,
                                   String members) {
        List<String> apiLines = new ArrayList<>();
        for (String m : api) {
            apiLines.add("- " + m);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("class_name", className);
        values.put("package_text", AgentText.packageText(pkg));
        values.put("source", source);
        values.put("api", apiLines.isEmpty() ? "- (see the code)" : String.join("\n", apiLines));
        values.put("members", members == null || members.isEmpty() ? "(see the code)" : members);
        values.put("feedback", "");
        return new Prompts.Template(versionFile).render(values);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> score(String reply, Path out, Settings settings, String className, String pkg,
                                     Path originalClasses, List<Map<String, Object>> suite) {
        String fq = JavaSrc.fqcn(pkg, className);
        Map<String, String> files = RefactorParse.parseFiles(reply);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        result.put("files", new ArrayList<>(new TreeSet<>(files.keySet())));
        result.put("compiles", false);
        result.put("checks_passed", 0);
        result.put("checks_total", 0);
        result.put("tests_total", suite.size());
        result.put("tests_compile", 0);
        result.put("tests_passed", 0);
        result.put("branch", null);
        result.put("problems", problems);
        if (!files.containsKey(className + ".java")) {
            problems.add(files.isEmpty() ? "no Java files in the reply" : "no file for the Subject class");
            return result;
        }
        Path project = Workspace.createProject(out.resolve("project"), settings.pomTemplate, fq);
        Workspace.writeSources(project, pkg, files, true);
        Map<String, Object> compiled = Maven.compileOnly(project, settings, out.resolve("maven-compile.log"), false);
        result.put("compiles", compiled.get("ok"));
        if (!Boolean.TRUE.equals(compiled.get("ok"))) {
            List<Map<String, Object>> errors = (List<Map<String, Object>>) compiled.get("compile_errors");
            for (Map<String, Object> e : errors.subList(0, Math.min(5, errors.size()))) {
                problems.add("compile error " + e.get("file") + ":" + e.get("line") + " " + e.get("message"));
            }
            return result;
        }

        StringBuilder testCode = new StringBuilder();
        for (Map<String, Object> c : suite) {
            testCode.append(c.getOrDefault("junit", "")).append('\n');
        }
        Map<String, Object> report = PatternCheck.check(originalClasses, fq, project.resolve("target").resolve("classes"),
                fq, testCode.toString());
        Text.write(out.resolve("pattern-check.md"), PatternCheck.toMarkdown(report));
        List<Map<String, Object>> graded = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) report.get("checks")) {
            if (!"INFO".equals(c.get("result"))) {
                graded.add(c);
            }
        }
        result.put("checks_passed", (int) graded.stream().filter(c -> "PASS".equals(c.get("result"))).count());
        result.put("checks_total", graded.size());
        result.put("checks", report.get("checks"));
        for (Map<String, Object> c : graded) {
            if ("FAIL".equals(c.get("result"))) {
                problems.add("pattern: " + c.get("check") + " (" + c.get("detail") + ")");
            }
        }

        // reuse the accepted tests unchanged; drop only the ones that do not compile
        List<Map<String, Object>> usable = new ArrayList<>(suite);
        List<String> dropped = new ArrayList<>();
        Map<String, Object> check = Map.of("ok", false);
        for (int attempt = 1; attempt <= 30; attempt++) {
            JUnitWriter.Rendered rendered = JUnitWriter.render(className, pkg, usable, settings.testTimeoutSeconds);
            Workspace.writeTestClass(project, pkg, className, rendered.source());
            check = Maven.compileOnly(project, settings, out.resolve("maven-test-compile-" + attempt + ".log"), true);
            TreeSet<String> bad = new TreeSet<>();
            for (Map<String, Object> e : (List<Map<String, Object>>) check.get("compile_errors")) {
                if ("test".equals(e.get("scope"))) {
                    String id = JUnitWriter.blame(rendered.lineMap(), ((Number) e.get("line")).intValue());
                    if (id != null) {
                        bad.add(id);
                    }
                }
            }
            if (Boolean.TRUE.equals(check.get("ok")) || bad.isEmpty()) {
                break;
            }
            dropped.addAll(bad);
            usable.removeIf(c -> bad.contains(String.valueOf(c.get("id"))));
        }
        if (!Boolean.TRUE.equals(check.get("ok"))) {
            result.put("tests_compile", 0);
            List<Object> all = new ArrayList<>();
            for (Map<String, Object> c : suite) {
                all.add(c.get("id"));
            }
            result.put("not_compiling", all);
            problems.add("the test class does not compile (errors outside the test methods)");
            return result;
        }
        result.put("tests_compile", usable.size());
        result.put("not_compiling", dropped);
        if (!dropped.isEmpty()) {
            problems.add(dropped.size() + " test(s) no longer compile: " + String.join(", ", dropped));
        }
        Map<String, Object> run = Maven.runTests(project, settings, out.resolve("maven-test.log"));
        Map<String, String> ids = new LinkedHashMap<>();
        for (Map<String, Object> c : usable) {
            ids.put(JUnitWriter.methodName(String.valueOf(c.get("id"))), String.valueOf(c.get("id")));
        }
        List<String> failed = new ArrayList<>();
        int passed = 0;
        for (Map<String, Object> t : (List<Map<String, Object>>) run.get("tests")) {
            String status = String.valueOf(t.get("status"));
            if (status.equals("failed") || status.equals("error")) {
                failed.add(ids.getOrDefault(String.valueOf(t.get("method")), String.valueOf(t.get("method"))));
            } else if (status.equals("passed")) {
                passed++;
            }
        }
        result.put("tests_passed", passed);
        result.put("failed", failed);
        if (!failed.isEmpty()) {
            problems.add(failed.size() + " test(s) fail: " + String.join(", ", failed));
        }
        Path xml = project.resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.xml");
        if (Files.exists(xml)) {
            result.put("branch", Jacoco.summarize(Jacoco.parse(xml)).get("branch"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String pct(Object f) {
        if (!(f instanceof Map<?, ?> m) || Jacoco.intOf((Map<String, Object>) m, "total") == 0) {
            return "-";
        }
        Map<String, Object> fr = (Map<String, Object>) m;
        return String.format(Locale.ROOT, "%d/%d (%.0f%%)", Jacoco.intOf(fr, "covered"), Jacoco.intOf(fr, "total"),
                Jacoco.pct(fr));
    }

    @SuppressWarnings("unchecked")
    static String reportMarkdown(List<Map<String, Object>> results, Path lab, String runId) {
        List<String> out = new ArrayList<>(List.of("# Prompt lab " + lab.getFileName(), "",
                "Refactoring prompt versions scored on the tested original code of run `" + runId + "`.", "",
                "| Version | Files | Compiles | State pattern checks | Tests compile unchanged | Tests pass | Branch coverage |",
                "|---|---|---|---|---|---|---|"));
        for (Map<String, Object> r : results) {
            if (Boolean.TRUE.equals(r.get("pending"))) {
                out.add("| " + r.get("version") + " | waiting for reply.md | | | | | |");
                continue;
            }
            out.add("| " + r.get("version") + " | " + ((List<?>) r.get("files")).size() + " | "
                    + (Boolean.TRUE.equals(r.get("compiles")) ? "yes" : "NO") + " | " + r.get("checks_passed") + "/"
                    + r.get("checks_total") + " | " + r.get("tests_compile") + "/" + r.get("tests_total") + " | "
                    + r.get("tests_passed") + "/" + r.get("tests_compile") + " | " + pct(r.get("branch")) + " |");
        }
        out.addAll(List.of("", "## Problems found per version", ""));
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("pending"))) {
                List<String> problems = (List<String>) r.get("problems");
                out.add("- **" + r.get("version") + "**: " + (problems.isEmpty() ? "none" : String.join("; ", problems)));
            }
        }
        return String.join("\n", out) + "\n";
    }

    public static int runLab(Settings settings, String runId, List<String> versions, String labId, Consumer<String> echo) {
        Path runDir = settings.runsDir.resolve(runId);
        Path originalProject = runDir.resolve("original").resolve("project");
        Path suiteFile = runDir.resolve("original").resolve("test-cases.json");
        if (!Files.exists(suiteFile) || !Files.exists(originalProject)) {
            echo.accept("Run " + runId + " has no tested original code yet (finish steps 1-7 first).");
            return 1;
        }
        Map<String, String> sources = Workspace.readSources(originalProject);
        String subjectFile = null;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (JavaSrc.hasMain(e.getValue()) || sources.size() == 1) {
                subjectFile = e.getKey();
                break;
            }
        }
        if (subjectFile == null) {
            subjectFile = sources.keySet().iterator().next();
        }
        String source = sources.get(subjectFile);
        String pkg = JavaSrc.packageOf(source);
        String className = JavaSrc.primaryType(source);
        String fq = JavaSrc.fqcn(pkg, className);
        Map<String, Object> compiled = Maven.compileOnly(originalProject, settings,
                runDir.resolve("logs").resolve("maven-lab-original-compile.log"), false);
        if (!Boolean.TRUE.equals(compiled.get("ok"))) {
            echo.accept("The run's original code does not compile.");
            return 1;
        }
        Path classes = originalProject.resolve("target").resolve("classes");
        List<String> api = Javap.publicApi(classes, fq);
        Testability analysis = Testability.analyze(source, classes, className, pkg);
        List<Map<String, Object>> suite = Common.loadSuite(suiteFile);
        StringBuilder testCode = new StringBuilder();
        for (Map<String, Object> c : suite) {
            if (testCode.length() > 0) {
                testCode.append('\n');
            }
            testCode.append(c.getOrDefault("junit", ""));
        }
        Map<String, Object> analyses = new LinkedHashMap<>();
        analyses.put("original", analysis.asMap());
        String members = Common.membersText(analyses, testCode.toString());

        Path versionsDir = settings.promptsDir.resolve("refactor_versions");
        Path lab = Text.mkdirs(settings.runsDir.toAbsolutePath().getParent().resolve("prompt-lab")
                .resolve(labId == null || labId.isEmpty() ? runId : labId));
        Context ctx = Context.create(settings, lab.resolve("_trace"));
        List<String> names = new ArrayList<>();
        if (versions != null && !versions.isEmpty()) {
            names.addAll(versions);
        } else {
            for (Path p : Text.list(versionsDir, "P*.md")) {
                names.add(p.getFileName().toString().replace(".md", ""));
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (String version : names) {
            Path out = Text.mkdirs(lab.resolve(version));
            String[] prompt = render(versionsDir.resolve(version + ".md"), className, pkg, source.stripTrailing(), api, members);
            Text.write(out.resolve("prompt.md"), "<!-- " + version + ": paste the System part (if your chat has one) and "
                    + "the User part into the LLM; save its whole answer as reply.md -->\n\n# System\n\n" + prompt[0]
                    + "\n\n# User\n\n" + prompt[1] + "\n");
            Path replyFile = out.resolve("reply.md");
            if (!Files.exists(replyFile) || Text.read(replyFile).isBlank()) {
                if (settings.llmProvider.equals("manual")) {
                    echo.accept(version + ": paste " + out.resolve("prompt.md") + " into the web LLM and save its answer as "
                            + replyFile);
                    Map<String, Object> pending = new LinkedHashMap<>();
                    pending.put("version", version);
                    pending.put("pending", true);
                    results.add(pending);
                    continue;
                }
                echo.accept(version + ": asking " + settings.describe() + " ...");
                Text.write(replyFile, ctx.llm.text("lab_" + version, prompt[0], prompt[1]));
            }
            echo.accept(version + ": scoring the reply ...");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", version);
            result.putAll(score(Text.read(replyFile), out, settings, className, pkg, classes, suite));
            Json.write(out.resolve("score.json"), result);
            results.add(result);
            echo.accept("  " + version + ": compiles=" + result.get("compiles") + " checks=" + result.get("checks_passed")
                    + "/" + result.get("checks_total") + " tests compile " + result.get("tests_compile") + "/"
                    + result.get("tests_total") + ", pass " + result.get("tests_passed"));
        }
        Text.write(lab.resolve("results.md"), reportMarkdown(results, lab, runId));
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("run", runId);
        all.put("time", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)));
        all.put("llm", settings.describe());
        all.put("results", results);
        Json.write(lab.resolve("results.json"), all);
        echo.accept("Results: " + lab.resolve("results.md"));
        return 0;
    }
}
