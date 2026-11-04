package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Settings;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.JavaSrc;
import edu.uta.cse4392.ta3.tools.Javap;
import edu.uta.cse4392.ta3.tools.Maven;
import edu.uta.cse4392.ta3.tools.RunJava;
import edu.uta.cse4392.ta3.tools.Testability;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static edu.uta.cse4392.ta3.nodes.Common.events;
import static edu.uta.cse4392.ta3.nodes.Common.note;

/**
 * Setup node: reads SeasonSwitch.java, builds the Maven project, compiles it, runs the program to observe its
 * behaviour (What To Do, item 2) and analyses what makes it hard to test.
 */
public final class Setup {

    private static final Pattern PUBLIC_SIGNATURE = Pattern.compile(
            "^\\s*(public\\s+[^;{=]*?\\([^)]*\\))\\s*(?:throws[^{]*)?\\{", Pattern.MULTILINE);

    private Setup() {
    }

    /** Fallback when javap is unavailable: public members as written in the source. */
    private static List<String> apiFromSource(String source) {
        TreeSet<String> found = new TreeSet<>();
        Matcher m = PUBLIC_SIGNATURE.matcher(JavaSrc.stripComments(source));
        while (m.find()) {
            found.add(Text.squash(m.group(1)));
        }
        return new ArrayList<>(found);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> setupSut(RunState state, Context ctx) {
        Settings s = ctx.settings;
        Path sut = Path.of(state.str("sut_file"));
        String source = Text.read(sut);
        String pkg = JavaSrc.packageOf(source);
        String stem = sut.getFileName().toString().replaceAll("\\.java$", "");
        String className = JavaSrc.primaryType(source, stem);
        String fq = JavaSrc.fqcn(pkg, className);
        boolean hasMain = JavaSrc.hasMain(source);

        Path original = ctx.phaseDir("original");
        Path project = Workspace.createProject(original.resolve("project"), s.pomTemplate, fq);
        Workspace.writeSources(project, pkg, Map.of(className + ".java", source), true);

        Map<String, Object> compiled = Maven.compileOnly(project, s, ctx.runDir.resolve("logs")
                .resolve("maven-original-compile.log"), false);
        if (!Boolean.TRUE.equals(compiled.get("ok"))) {
            List<String> errors = new ArrayList<>();
            for (Map<String, Object> e : (List<Map<String, Object>>) compiled.get("compile_errors")) {
                errors.add(e.get("file") + ":" + e.get("line") + " " + e.get("message"));
            }
            throw new IllegalStateException(sut.getFileName() + " does not compile with Maven:\n"
                    + (errors.isEmpty() ? compiled.get("log_tail") : String.join("\n", errors)));
        }
        Path classes = project.resolve("target").resolve("classes");

        List<String> api;
        try {
            api = Javap.publicApi(classes, fq);
        } catch (RuntimeException e) {
            api = apiFromSource(source);
        }

        Testability analysis = Testability.analyze(source, classes, className, pkg);
        Text.write(original.resolve("testability.md"), analysis.toMarkdown());

        String observed = "";
        List<Map<String, Object>> runs = new ArrayList<>();
        if (hasMain) {
            List<List<String>> argSets = new ArrayList<>();
            if (state.get("observe_args") instanceof List<?> given && !given.isEmpty()) {
                for (Object args : given) {
                    argSets.add(new ArrayList<>((List<String>) args));
                }
            } else if (!s.observeArgs.isEmpty()) {
                argSets.addAll(s.observeArgs);
            } else {
                argSets.add(new ArrayList<>());
            }
            for (List<String> args : argSets) {
                runs.add(RunJava.run(classes, fq, args, s.observeSeconds));
            }
            observed = RunJava.describe(runs, fq, s.observeSeconds);
            Text.write(original.resolve("observe.md"), "# Observed behaviour\n\nThe program compiled with `mvn compile` "
                    + "and run with `java` (each run stopped after " + Text.seconds(s.observeSeconds)
                    + " s if it was still running).\n\n```\n" + observed + "\n```\n");
            Json.write(original.resolve("observe.json"), runs);
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("class", fq);
        info.put("package", pkg.isEmpty() ? "(default)" : pkg);
        info.put("has_main", hasMain);
        info.put("public_api", api);
        Json.write(original.resolve("sut.json"), info);

        List<String> findings = new ArrayList<>();
        if (!analysis.staticFields.isEmpty()) {
            findings.add("static state");
        }
        if (!analysis.timers.isEmpty()) {
            findings.add("timers");
        }
        if (analysis.prints) {
            findings.add("console output");
        }
        if (analysis.exits) {
            findings.add("System.exit");
        }
        if (!analysis.blocks.isEmpty()) {
            findings.add("endless wait in main");
        }
        String found = findings.isEmpty() ? "nothing special" : String.join(", ", findings);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("api", api);
        ctx.tracer.event("setup", "result", fq + ": " + api.size() + " public members; testability: " + found, data);

        Map<String, Object> testability = new LinkedHashMap<>();
        testability.put("original", analysis.asMap());
        Map<String, Object> projects = new LinkedHashMap<>();
        projects.put("original", project.toString());
        return Common.update(
                "class_name", className,
                "package", pkg,
                "has_main", hasMain,
                "original_api", new ArrayList<>(api),
                "observed", observed,
                "testability", testability,
                "projects", projects,
                "phase", "original",
                "run_counter", new LinkedHashMap<String, Object>(),
                "review_round", 0,
                "repair_round", 0,
                "idle_repairs", 0,
                "coverage_round", 0,
                "refactor_attempt", 0,
                "refactor_round", 0,
                "refactor_prompt_version", 1,
                "events", events(note("setup", "Loaded " + fq + "; compiled with Maven"
                        + (runs.isEmpty() ? "" : "; ran it " + runs.size() + " times to observe it (original/observe.md)")
                        + "; testability: " + found)));
    }
}
