package edu.uta.cse4392.ta3;

import edu.uta.cse4392.ta3.graph.GraphDiagram;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.graph.Workflow;
import edu.uta.cse4392.ta3.lab.PromptLab;
import edu.uta.cse4392.ta3.nodes.Finalize;
import edu.uta.cse4392.ta3.tools.Maven;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command line for the multi-agent testing and refactoring system.
 *
 * <pre>
 * java -jar target/ta3-agents.jar run path/to/SeasonSwitch.java   start a run (interactive reviews)
 * java -jar target/ta3-agents.jar resume &lt;run-id&gt;                  continue a paused run
 * java -jar target/ta3-agents.jar status &lt;run-id&gt;                  where a run is and what it waits for
 * java -jar target/ta3-agents.jar report &lt;run-id&gt;                  rewrite summary.md and final/ of a run
 * java -jar target/ta3-agents.jar list                             all runs
 * java -jar target/ta3-agents.jar lab &lt;run-id&gt;                     score the refactoring prompt versions (step 8)
 * java -jar target/ta3-agents.jar graph                            the workflow graph as Mermaid
 * </pre>
 */
public final class Main {

    static final String USAGE = """
            usage: java -jar target/ta3-agents.jar <command> ...

              run <file.java> [options]      start a run on the Java file under test
              resume <run-id> [options]      continue a paused run
              status <run-id>                where a run is and what it waits for
              report <run-id>                rewrite summary.md and final/ of a run from its last saved state
              list                           all runs
              lab <run-id> [--versions P0 P1 ...] [--lab-id NAME]
                                             score the refactoring prompt versions on a run's tested code (step 8)
              graph                          print the compiled LangGraph4j workflow as Mermaid

            options for run and resume:
              --run-id ID         name of the run folder (run only; default: the date and time)
              --observe ARGS      command-line arguments for one observation run of main(); repeat it for more
                                  runs, e.g. --observe "" --observe cool --observe heat (run only)
              --auto-approve      accept the test cases and the refactoring without asking (unattended demo)
              --no-wait           never prompt: use answer files if present, otherwise pause and exit
              --answer TEXT       answer for the pending review, e.g. "accept" or "revise: add a test for ..."
              --skip-checks       skip the Maven and Ollama checks
            """;

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static BufferedReader stdin;

    /** Parsed command line. */
    static final class Args {
        String command;
        final List<String> positional = new ArrayList<>();
        String runId;
        final List<String> observe = new ArrayList<>();
        boolean autoApprove;
        boolean noWait;
        String answer;
        boolean skipChecks;
        final List<String> versions = new ArrayList<>();
        String labId;
    }

    private Main() {
    }

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    static void print(String text) {
        OUT.println(text);
    }

    public static int run(String[] argv) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            print(e.getMessage());
            print(USAGE);
            return 2;
        }
        if (args == null) {
            print(USAGE);
            return 0;
        }
        Settings settings = Settings.fromEnv();
        try {
            return switch (args.command) {
                case "run" -> cmdRun(args, settings);
                case "resume" -> cmdResume(args, settings);
                case "status" -> cmdStatus(args, settings);
                case "report" -> cmdReport(args, settings);
                case "list" -> cmdList(settings);
                case "lab" -> PromptLab.runLab(settings, args.positional.get(0), args.versions, args.labId, Main::print);
                case "graph" -> {
                    print(GraphDiagram.mermaid(settings));
                    yield 0;
                }
                default -> {
                    print("Unknown command: " + args.command);
                    print(USAGE);
                    yield 2;
                }
            };
        } catch (Exception e) {
            print("Error: " + rootMessage(e));
            return 1;
        }
    }

    static Args parse(String[] argv) {
        if (argv.length == 0 || argv[0].equals("-h") || argv[0].equals("--help")) {
            return null;
        }
        Args a = new Args();
        a.command = argv[0];
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "--run-id" -> a.runId = value(argv, ++i, arg);
                case "--observe" -> a.observe.add(value(argv, ++i, arg));
                case "--auto-approve" -> a.autoApprove = true;
                case "--no-wait" -> a.noWait = true;
                case "--answer" -> a.answer = value(argv, ++i, arg);
                case "--skip-checks" -> a.skipChecks = true;
                case "--lab-id" -> a.labId = value(argv, ++i, arg);
                case "--versions" -> {
                    while (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                        a.versions.add(argv[++i]);
                    }
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    a.positional.add(arg);
                }
            }
        }
        int needed = switch (a.command) {
            case "run", "resume", "status", "report", "lab" -> 1;
            default -> 0;
        };
        if (a.positional.size() < needed) {
            throw new IllegalArgumentException(a.command + ": " + (a.command.equals("run") ? "the Java file under test"
                    : "the run id") + " is missing");
        }
        return a;
    }

    private static String value(String[] argv, int i, String option) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return argv[i];
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ---------------------------------------------------------------- answering pauses
    private static String readLine(String prompt) {
        if (prompt != null) {
            OUT.print(prompt);
            OUT.flush();
        }
        try {
            if (stdin == null) {
                stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            }
            return stdin.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readBlock(String prompt) {
        print(prompt + " (finish with an empty line)");
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = readLine(null);
            if (line == null || line.isBlank()) {
                break;
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    private static Path answerFile(Map<String, Object> pause) {
        Object review = pause.get("review_file");
        return review == null ? null : Path.of(review + ".answer.txt");
    }

    private static boolean hasText(Path file) {
        return file != null && Files.isRegularFile(file) && !Text.read(file).isBlank();
    }

    /** A person's answer to a pause, or null to leave the run paused. */
    static Object answer(Map<String, Object> pause, Args args, List<Path> used) {
        String kind = String.valueOf(pause.get("kind"));
        if (kind.equals("llm")) {
            Path response = Path.of(pause.get("response_file").toString());
            if (hasText(response)) {
                return Text.read(response);
            }
            if (args.noWait) {
                return null;
            }
            print("\nThe " + pause.get("role") + " needs a model reply (LLM_PROVIDER=manual).\n  1. Open   "
                    + pause.get("prompt_file") + "\n  2. Paste it into your LLM (any web chat)\n  3. Save the reply as "
                    + response + "\n  then press Enter (or q to pause).");
            while (true) {
                String line = readLine("> ");
                if (line == null || line.strip().equalsIgnoreCase("q")) {
                    return null;
                }
                if (hasText(response)) {
                    return Text.read(response);
                }
                print("Still no reply in " + response + ".");
            }
        }
        if (args.answer != null) {
            String a = args.answer;
            args.answer = null;
            return a;
        }
        if (args.autoApprove) {
            return kind.equals("stuck") ? "stop" : "accept";
        }
        Path file = answerFile(pause);
        if (hasText(file)) {
            used.add(file);
            return Text.read(file).strip();
        }
        if (args.noWait) {
            return null;
        }
        switch (kind) {
            case "review_tests" -> {
                print("\n=== Review the test cases (round " + pause.get("round") + ") ===\n" + pause.get("count")
                        + " distinct test cases" + (num(pause.get("conflicts")) > 0
                        ? ", " + pause.get("conflicts") + " conflicts to decide" : "")
                        + ".\nRead " + pause.get("review_file") + "\nThe suite is in " + pause.get("suite_file"));
                String choice = lower(readLine("[a]ccept, [r]equest changes, [e]dited the JSON file, [q]uit and resume later: "));
                if (choice == null || choice.startsWith("q")) {
                    return null;
                }
                if (choice.startsWith("r")) {
                    return map("action", "revise", "feedback", readBlock("Describe the changes"));
                }
                if (choice.startsWith("e")) {
                    String what = readLine("What did you change? ");
                    return map("action", "edited", "feedback", what == null ? "" : what);
                }
                return map("action", "accept");
            }
            case "review_refactoring" -> {
                print("\n=== Review the refactored code (round " + pause.get("round") + ") ===\nCompiles: "
                        + (Boolean.TRUE.equals(pause.get("compiles")) ? "yes" : "NO") + "; pattern checks: "
                        + (Boolean.TRUE.equals(pause.get("checks_passed")) ? "pass" : "see the report") + "\nRead "
                        + pause.get("review_file") + "\nCode: " + pause.get("project") + "/src/main/java\nPrompt: "
                        + pause.get("prompt_file"));
                String choice = lower(readLine("[a]ccept, [f]eedback and regenerate, [p]rompt edited - regenerate, "
                        + "[c]ode edited - re-check, [q]uit: "));
                if (choice == null || choice.startsWith("q")) {
                    return null;
                }
                if (choice.startsWith("f")) {
                    return map("action", "feedback", "feedback", readBlock("What should change"));
                }
                if (choice.startsWith("p")) {
                    return map("action", "prompt_edited");
                }
                if (choice.startsWith("c")) {
                    return map("action", "code_edited");
                }
                return map("action", "accept");
            }
            case "stuck" -> {
                print("\n=== A decision is needed ===\n" + pause.get("reason") + "\nYou may edit "
                        + pause.get("suite_file") + " or the code first.");
                String choice = lower(readLine("[c]ontinue for more rounds, [s]top and summarize, [q]uit: "));
                if (choice == null || choice.startsWith("q")) {
                    return null;
                }
                return choice.startsWith("c") ? "continue" : "stop";
            }
            default -> {
                return null;
            }
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    static String pendingText(Map<String, Object> pause) {
        if ("llm".equals(pause.get("kind"))) {
            return "- " + pause.get("role") + " waits for a model reply: put it in " + pause.get("response_file")
                    + " (prompt: " + pause.get("prompt_file") + ")";
        }
        return "- " + pause.get("kind") + ": read " + pause.get("review_file") + " and write your answer into "
                + answerFile(pause);
    }

    /**
     * Everything the run waits for. The interrupt names one pause; with LLM_PROVIDER=manual both parallel test
     * designers may wait for a reply, so every prompt file without a reply is listed too.
     */
    static List<Map<String, Object>> pending(Context ctx, Map<String, Object> reason) {
        List<Map<String, Object>> out = new ArrayList<>();
        if ("llm".equals(reason.get("kind")) || ctx.settings.llmProvider.equals("manual")) {
            for (Path prompt : Text.list(ctx.tracer.llmDir(), "*.prompt.md")) {
                String name = prompt.getFileName().toString();
                Path response = prompt.resolveSibling(name.replace(".prompt.md", ".response.md"));
                if (!hasText(response)) {
                    String role = name.substring(name.indexOf('-') + 1, name.length() - ".prompt.md".length());
                    out.add(map("kind", "llm", "role", role, "prompt_file", prompt.toString(),
                            "response_file", response.toString()));
                }
            }
        }
        if (!"llm".equals(reason.get("kind")) || out.isEmpty()) {
            out.add(reason);
        }
        return out;
    }

    // ---------------------------------------------------------------- driving the graph
    private static ExecutorService parallelPool() {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "parallel-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs the graph until it ends or pauses; answers pauses as they come. Returns the exit code. */
    static int drive(Context ctx, CompiledGraph<RunState> app, String runId, GraphInput first, Args args) {
        GraphInput input = first;
        Path pendingFile = ctx.runDir.resolve("pending.json");
        while (true) {
            ExecutorService pool = parallelPool();
            RunnableConfig config = Workflow.config(runId, pool);
            GraphResult result;
            try {
                AsyncGenerator<NodeOutput<RunState>> stream = app.stream(input, config);
                for (NodeOutput<RunState> ignored : stream) {
                    // the tracer prints the progress of each node
                }
                result = GraphResult.from(stream);
            } catch (RuntimeException e) {
                print("\nThe run stopped with an error: " + rootMessage(e));
                print("The last finished step is saved. Fix the problem, then continue with: "
                        + "java -jar target/ta3-agents.jar resume " + runId);
                return 1;
            } finally {
                pool.shutdown();
                try {
                    pool.awaitTermination(10, TimeUnit.MINUTES); // a parallel branch may still be writing its prompt
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!result.isInterruptionMetadata()) {
                Text.delete(pendingFile);
                Optional<StateSnapshot<RunState>> last = app.lastStateOf(Workflow.config(runId, null));
                if (last.isPresent() && !StateGraph.END.equals(last.get().nextNodeId())) {
                    print("Stopped before " + last.get().nextNodeId() + "; run resume to continue.");
                    return 1;
                }
                return 0;
            }
            Map<String, Object> reason = Json.parseMap(result.asInterruptionMetadata().reason().orElse("{}"));
            List<Map<String, Object>> pauses = pending(ctx, reason);
            Json.write(pendingFile, pauses);
            Map<String, Object> resume = new HashMap<>();
            List<Path> used = new ArrayList<>();
            for (Map<String, Object> pause : pauses) {
                Object answer = answer(pause, args, used); // the console, or round-N.md.answer.txt
                if (answer == null) {
                    print("\nPaused. Waiting for:");
                    for (Map<String, Object> p : pauses) {
                        print(pendingText(p));
                    }
                    print("Continue with: java -jar target/ta3-agents.jar resume " + runId);
                    return 2;
                }
                if (!"llm".equals(pause.get("kind"))) {
                    resume.put("answer", map("kind", pause.get("kind"), "value", answer));
                }
            }
            for (Path f : used) {
                try {
                    Files.move(f, f.resolveSibling(f.getFileName().toString().replace(".answer.txt", ".answer.used.txt")),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    print("Could not rename " + f + ": " + e.getMessage());
                }
            }
            input = GraphInput.resume(resume);
        }
    }

    /** The context and the compiled graph of a run (checkpoints in runs/&lt;run&gt;/checkpoints.sqlite). */
    static CompiledGraph<RunState> open(Context ctx) throws Exception {
        BaseCheckpointSaver saver = Workflow.sqliteSaver(ctx.runDir);
        return Workflow.build(ctx, saver);
    }

    private static String preflight(Settings settings) {
        try {
            Maven.resolveMvn(settings);
        } catch (Maven.MavenNotFound e) {
            return e.getMessage();
        }
        return Llm.checkOllama(settings);
    }

    static int cmdRun(Args args, Settings settings) throws Exception {
        Path sut = Path.of(args.positional.get(0)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(sut)) {
            print("No such file: " + sut);
            return 1;
        }
        String problem = args.skipChecks ? null : preflight(settings);
        if (problem != null) {
            print(problem);
            return 1;
        }
        String runId = args.runId != null ? args.runId
                : DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path runDir = settings.runsDir.resolve(runId);
        if (Files.exists(runDir.resolve("checkpoints.sqlite"))) {
            print("Run " + runId + " already exists; use resume.");
            return 1;
        }
        Text.mkdirs(runDir);
        List<String> observe = new ArrayList<>(args.observe);
        if (observe.isEmpty()) {
            for (List<String> a : settings.observeArgs) {
                observe.add(String.join(" ", a));
            }
        }
        Json.write(runDir.resolve("run.json"), map(
                "run_id", runId, "sut", sut.toString(),
                "started", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)),
                "llm", settings.describe(), "refactor_prompt", settings.refactorPrompt.toString(),
                "maven_args", settings.mavenArgs, "pom_template", settings.pomTemplate.toString(),
                "agent_system", "Java " + Runtime.version().feature() + ", LangGraph4j, LangChain4j",
                "observe", observe));
        print("Run " + runId + ": " + sut.getFileName() + " with " + settings.describe() + "\nOutputs: " + runDir);
        Context ctx = Context.create(settings, runDir, Main::print);
        CompiledGraph<RunState> app = open(ctx);
        Map<String, Object> first = new HashMap<>();
        first.put("run_id", runId);
        first.put("run_dir", runDir.toString());
        first.put("sut_file", sut.toString());
        first.put("events", new ArrayList<>());
        if (!args.observe.isEmpty()) {
            List<List<String>> observeArgs = new ArrayList<>();
            for (String a : args.observe) {
                observeArgs.add(Settings.splitArgs(a));
            }
            first.put("observe_args", observeArgs);
        }
        int code = drive(ctx, app, runId, GraphInput.args(first), args);
        if (code == 0) {
            print("\nDone. Summary: " + runDir.resolve("summary.md"));
        }
        return code;
    }

    static int cmdResume(Args args, Settings settings) throws Exception {
        String runId = args.positional.get(0);
        Path runDir = settings.runsDir.resolve(runId);
        if (!Files.exists(runDir.resolve("checkpoints.sqlite"))) {
            print("No run " + runId + " in " + settings.runsDir);
            return 1;
        }
        String problem = args.skipChecks ? null : preflight(settings);
        if (problem != null) {
            print(problem);
            return 1;
        }
        Context ctx = Context.create(settings, runDir, Main::print);
        CompiledGraph<RunState> app = open(ctx);
        Optional<StateSnapshot<RunState>> last = app.lastStateOf(Workflow.config(runId, null));
        if (last.isEmpty()) {
            print("Run " + runId + " has no saved step yet; start it again with run.");
            return 1;
        }
        if (StateGraph.END.equals(last.get().nextNodeId())) {
            print("Run " + runId + " has finished. Summary: " + runDir.resolve("summary.md"));
            return 0;
        }
        // answers come from the answer files or the console; with nothing to answer the pending step runs again
        int code = drive(ctx, app, runId, GraphInput.resume(), args);
        if (code == 0) {
            print("\nDone. Summary: " + runDir.resolve("summary.md"));
        }
        return code;
    }

    @SuppressWarnings("unchecked")
    static int cmdStatus(Args args, Settings settings) throws Exception {
        String runId = args.positional.get(0);
        Path runDir = settings.runsDir.resolve(runId);
        if (!Files.exists(runDir.resolve("checkpoints.sqlite"))) {
            print("No run " + runId);
            return 1;
        }
        Context ctx = Context.create(settings, runDir, null);
        Optional<StateSnapshot<RunState>> last = open(ctx).lastStateOf(Workflow.config(runId, null));
        if (last.isEmpty()) {
            print("Run " + runId + " has no saved step yet.");
            return 0;
        }
        RunState v = last.get().state();
        print("Run " + runId + ": phase " + v.str("phase") + ", " + v.cases("suite").size() + " test cases");
        Map<String, Object> lastRun = v.map("last_run");
        if (!lastRun.isEmpty()) {
            String counts = "";
            if (lastRun.get("counts") instanceof Map<?, ?> c) {
                counts = " (passed " + c.get("passed") + ", failed " + c.get("failed") + ", errors " + c.get("error")
                        + ", skipped " + c.get("skipped") + ")";
            }
            print("Last test run: " + lastRun.get("status") + counts);
        }
        Map<String, Object> coverage = v.map("coverage");
        for (String phase : List.of("original", "refactored")) {
            if (coverage.get(phase) instanceof Map<?, ?> cov) {
                Object branch = ((Map<String, Object>) cov).get("branch");
                print("Branch coverage (" + phase + "): " + Text.num(((Map<String, Object>) branch).get("pct")) + "%");
            }
        }
        String next = last.get().nextNodeId();
        print("Next step: " + (StateGraph.END.equals(next) ? "none (finished)"
                : next.equals("__PARALLEL__(setup)") ? "ep_agent, bva_agent (in parallel)" : next));
        Path pendingFile = runDir.resolve("pending.json");
        if (!StateGraph.END.equals(next) && Files.exists(pendingFile) && Json.read(pendingFile) instanceof List<?> list) {
            for (Object p : list) {
                print(pendingText((Map<String, Object>) p));
            }
        }
        List<Map<String, Object>> events = v.cases("events");
        for (Map<String, Object> e : events.subList(Math.max(0, events.size() - 8), events.size())) {
            print("  " + e.get("node") + ": " + e.get("message"));
        }
        return 0;
    }

    static int cmdReport(Args args, Settings settings) throws Exception {
        String runId = args.positional.get(0);
        Path runDir = settings.runsDir.resolve(runId);
        if (!Files.exists(runDir.resolve("checkpoints.sqlite"))) {
            print("No run " + runId);
            return 1;
        }
        Context ctx = Context.create(settings, runDir, null);
        Optional<StateSnapshot<RunState>> last = open(ctx).lastStateOf(Workflow.config(runId, null));
        if (last.isEmpty()) {
            print("Run " + runId + " has no saved step yet.");
            return 1;
        }
        Finalize.finalize(last.get().state(), ctx);
        print("Wrote " + runDir.resolve("summary.md") + " and " + runDir.resolve("final"));
        return 0;
    }

    static int cmdList(Settings settings) {
        if (!Files.isDirectory(settings.runsDir)) {
            print("No runs yet.");
            return 0;
        }
        for (Path run : Text.list(settings.runsDir, "*")) {
            Path info = run.resolve("run.json");
            if (!Files.exists(info)) {
                continue;
            }
            Map<String, Object> data = Json.readMap(info);
            String done = Files.exists(run.resolve("summary.md")) ? "finished" : "in progress";
            print(run.getFileName() + "  " + Path.of(String.valueOf(data.get("sut"))).getFileName() + "  "
                    + data.get("llm") + "  " + done);
        }
        return 0;
    }
}
