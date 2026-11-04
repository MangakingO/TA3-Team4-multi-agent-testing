package edu.uta.cse4392.ta3;

import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.graph.Workflow;
import edu.uta.cse4392.ta3.lab.PromptLab;
import edu.uta.cse4392.ta3.nodes.Finalize;
import edu.uta.cse4392.ta3.tools.Proc;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests of the LangGraph4j workflow with a scripted model and real Maven, JUnit and JaCoCo. */
class WorkflowTest {

    @TempDir
    Path tmp;

    Settings settings;
    Context ctx;
    CompiledGraph<RunState> app;
    Map<String, Object> first;
    Path runDir;

    @BeforeEach
    void needsMaven() {
        Assumptions.assumeTrue(Proc.which(Settings.fromEnv().mvn) != null, "Maven is not installed");
    }

    void setup(String provider) throws Exception {
        settings = Settings.fromEnv();
        settings.runsDir = tmp.resolve("runs");
        settings.llmProvider = provider;
        runDir = Text.mkdirs(settings.runsDir.resolve("test-run"));
        ctx = Context.create(settings, runDir);
        if (!provider.equals("manual")) {
            ctx.llm = new Fakes.ScriptedLlm(settings, ctx.tracer, Fakes.script());
        }
        app = Workflow.build(ctx, Workflow.sqliteSaver(runDir));
        Path sut = tmp.resolve("FanSwitch.java");
        Files.writeString(sut, Fakes.fixture("FanSwitch.java"));
        first = new HashMap<>();
        first.put("run_id", "test-run");
        first.put("run_dir", runDir.toString());
        first.put("sut_file", sut.toString());
        first.put("events", new ArrayList<>());
    }

    /** Runs the graph once; returns the pause it stopped at (empty map if it ended). */
    Map<String, Object> stream(GraphInput input) throws Exception {
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "parallel-" + n.incrementAndGet()));
        try {
            AsyncGenerator<NodeOutput<RunState>> gen = app.stream(input, Workflow.config("test-run", pool));
            for (NodeOutput<RunState> ignored : gen) {
                // run to the end or the next pause
            }
            GraphResult result = GraphResult.from(gen);
            return result.isInterruptionMetadata()
                    ? Json.parseMap(result.asInterruptionMetadata().reason().orElse("{}")) : Map.of();
        } finally {
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
    }

    /** Runs to the end, answering the pauses in order; returns the kinds of the pauses. */
    List<String> drive(Deque<Object> answers) throws Exception {
        List<String> seen = new ArrayList<>();
        Map<String, Object> pause = stream(GraphInput.args(first));
        while (!pause.isEmpty()) {
            seen.add(pause.get("kind").toString());
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("kind", pause.get("kind"));
            answer.put("value", answers.pop());
            pause = stream(GraphInput.resume(Map.of("answer", answer)));
        }
        return seen;
    }

    RunState lastState() {
        return app.lastStateOf(Workflow.config("test-run", null)).orElseThrow().state();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullWorkflow() throws Exception {
        setup("ollama");
        List<String> seen = drive(new ArrayDeque<>(List.of(
                "revise: add a test that pulls the chain when the fan is at HIGH", "accept", "accept")));
        RunState v = lastState();

        // human in the loop: one round of requested changes, then accept; then the refactoring review
        assertEquals(List.of("review_tests", "review_tests", "review_refactoring"), seen);

        // the EP and BVA agents ran as one parallel step, on two threads, and the join waited for both
        RunnableConfig config = Workflow.config("test-run", null);
        assertTrue(app.getStateHistory(config).stream()
                .map(StateSnapshot::nextNodeId).anyMatch("__PARALLEL__(setup)"::equals));
        Map<String, String> threads = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (String line : Text.lines(Text.read(runDir.resolve("trace.jsonl")))) {
            Map<String, Object> e = Json.parseMap(line);
            String key = e.get("node") + " " + e.get("kind");
            order.add(key);
            if (e.get("kind").equals("node_start")) {
                threads.put(e.get("node").toString(), e.get("thread").toString());
            }
        }
        assertTrue(threads.get("ep_agent").startsWith("parallel-") && threads.get("bva_agent").startsWith("parallel-"));
        assertNotEquals(threads.get("ep_agent"), threads.get("bva_agent"));
        assertTrue(order.indexOf("curate node_start") > order.indexOf("ep_agent node_end"));
        assertTrue(order.indexOf("curate node_start") > order.indexOf("bva_agent node_end"));

        // the curator removed the BVA test that duplicates EP-02 (same code, other variable name)
        Map<String, Object> curation = v.map("curation");
        assertEquals(5, curation.get("ep_received"));
        assertEquals(6, curation.get("bva_received"));
        assertTrue(((List<Map<String, Object>>) curation.get("duplicates"))
                .contains(Map.of("kept", "EP-02", "removed", "BVA-05", "rule", "identical test code")));

        List<String> ids = new ArrayList<>();
        for (Map<String, Object> c : v.cases("suite")) {
            ids.add(c.get("id").toString());
        }
        assertTrue(ids.contains("REVIEW-01") && ids.contains("COV-01") && !ids.contains("BVA-05"));

        // the seeded fault was found by BVA-06/REVIEW-01 and fixed in the code, with a change-log entry
        assertTrue(ctx.changes.entries().stream().anyMatch(c -> c.get("kind").equals("code")
                && c.get("agent").equals("repair agent") && c.get("phase").equals("original")));
        assertTrue(Text.read(runDir.resolve("original/project/src/main/java/FanSwitch.java")).contains("speed = 0;"));

        // 100% branch coverage on both versions (main() needed the coverage agent's extra test)
        Map<String, Object> coverage = v.map("coverage");
        assertEquals(100.0, ((Map<String, Object>) ((Map<String, Object>) coverage.get("original")).get("branch")).get("pct"));
        assertEquals(100.0, ((Map<String, Object>) ((Map<String, Object>) coverage.get("refactored")).get("branch")).get("pct"));
        assertEquals(3, v.map("run_counter").get("original")); // failing run, run after the repair, run with COV-01

        // the refactoring passed the State pattern checks and the reused tests
        Map<String, Object> report = v.map("pattern_report");
        assertEquals(true, report.get("passed"));
        assertEquals("FanState", report.get("state_type"));
        assertEquals(List.of("High", "Low", "Off"), report.get("concrete_states"));
        assertEquals("refactored", v.str("phase"));
        assertEquals("passed", v.map("last_run").get("status"));
        assertEquals(v.cases("suite").size(), ((Map<String, Object>) v.map("last_run").get("counts")).get("passed"));

        // deliverables
        Path fin = runDir.resolve("final");
        for (String path : List.of("summary.md", "changes.md", "original/test-cases.json",
                "original/src/test/java/FanSwitchTest.java", "original/coverage/jacoco/jacoco.xml",
                "refactored/src/main/java/FanState.java", "refactored/test-results/summary.md",
                "refactored/coverage/summary.md", "original/pom.xml", "refactored/src/test/java/TestSupport.java")) {
            assertTrue(Files.exists(fin.resolve(path)), path);
        }
        assertTrue(Text.read(fin.resolve("original/coverage/jacoco/jacoco.xml")).contains("BRANCH"));
        String trace = Text.read(runDir.resolve("trace.log"));
        assertTrue(trace.contains("[ep_agent] llm_call") && trace.contains("[bva_agent] llm_call"));

        // prompt lab (step 8): score a reply to P3 on the tested original code with the accepted tests
        settings.llmProvider = "manual";
        Path lab = settings.runsDir.getParent().resolve("prompt-lab").resolve("fan-lab");
        Text.write(lab.resolve("P3").resolve("reply.md"), Fakes.REFACTOR);
        assertEquals(0, PromptLab.runLab(settings, "test-run", List.of("P3", "P0"), "fan-lab", line -> { }));
        List<Map<String, Object>> results = (List<Map<String, Object>>) Json.readMap(lab.resolve("results.json")).get("results");
        Map<String, Object> p3 = results.get(0);
        assertEquals(true, p3.get("compiles"));
        assertEquals(8, p3.get("checks_passed"));
        assertEquals(8, p3.get("checks_total"));
        assertEquals(12, p3.get("tests_total"));
        assertEquals(12, p3.get("tests_compile"));
        assertEquals(12, p3.get("tests_passed"));
        assertEquals(Map.of("version", "P0", "pending", true), results.get(1));
        assertTrue(Files.exists(lab.resolve("P0/prompt.md")));

        // a run folder copied to another computer still rebuilds final/ (report) from its own files
        Path moved = tmp.resolve("elsewhere/runs/test-run");
        Text.copyTree(runDir, moved);
        Text.delete(runDir);
        Settings movedSettings = Settings.fromEnv();
        movedSettings.runsDir = moved.getParent();
        Finalize.finalize(v, Context.create(movedSettings, moved));
        for (String path : List.of("summary.md", "original/src/main/java/FanSwitch.java",
                "refactored/src/test/java/FanSwitchTest.java", "refactored/coverage/jacoco/jacoco.xml",
                "original/test-results/summary.md")) {
            assertTrue(Files.exists(moved.resolve("final").resolve(path)), path);
        }
        assertFalse(Files.exists(moved.resolve("final.new")));
    }

    @Test
    void manualProviderPausesForBothParallelAgents() throws Exception {
        // LLM_PROVIDER=manual: each prompt becomes a pause; the reply is read from its file on resume
        setup("manual");
        Map<String, Object> pause = stream(GraphInput.args(first));
        assertEquals("llm", pause.get("kind"));
        List<Map<String, Object>> pending = Main.pending(ctx, pause);
        Set<String> roles = new HashSet<>();
        for (Map<String, Object> p : pending) {
            roles.add(p.get("role").toString());
            Path prompt = Path.of(p.get("prompt_file").toString());
            assertTrue(Files.exists(prompt));
            assertTrue(Text.read(prompt).contains("FanSwitch"));
            Text.write(Path.of(p.get("response_file").toString()), Json.compact(p.get("role").equals("ep_agent")
                    ? Fakes.EP : Fakes.BVA));
        }
        assertEquals(Set.of("ep_agent", "bva_agent"), roles);
        pause = stream(GraphInput.resume());
        assertEquals("review_tests", pause.get("kind"));
        assertEquals(10, lastState().map("curation").get("distinct"));
        // the resumed nodes reused their prompt files instead of writing new ones
        assertEquals(2, Text.list(runDir.resolve("llm"), "*.prompt.md").size());
        assertEquals(2, Text.list(runDir.resolve("llm"), "*.response.md").size());
    }
}
