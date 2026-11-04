package edu.uta.cse4392.ta3.graph;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Pause;
import edu.uta.cse4392.ta3.nodes.Adapt;
import edu.uta.cse4392.ta3.nodes.Coverage;
import edu.uta.cse4392.ta3.nodes.Design;
import edu.uta.cse4392.ta3.nodes.Finalize;
import edu.uta.cse4392.ta3.nodes.Refactoring;
import edu.uta.cse4392.ta3.nodes.Review;
import edu.uta.cse4392.ta3.nodes.Setup;
import edu.uta.cse4392.ta3.nodes.Testing;
import edu.uta.cse4392.ta3.tools.Jacoco;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInterruptException;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.SQLiteSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * The orchestrator: a LangGraph4j StateGraph that wires the agents into the 12-step process.
 *
 * <pre>
 * setup -&gt; (EP agent || BVA agent) -&gt; curate -&gt; review_tests &lt;-&gt; revise_tests
 *       -&gt; run_tests &lt;-&gt; repair -&gt; measure_coverage &lt;-&gt; coverage_gap        (original code)
 *       -&gt; refactor -&gt; review_refactoring &lt;-&gt; refactor / recheck_refactoring
 *       -&gt; adapt_tests -&gt; run_tests &lt;-&gt; repair -&gt; measure_coverage &lt;-&gt; coverage_gap (refactored code)
 *       -&gt; finalize
 * </pre>
 *
 * The process is fixed by the assignment, so the routing is deterministic (conditional edges on the state), and
 * only the creative work (designing tests, diagnosing failures, refactoring) is done by language models. Every
 * step is checkpointed in SQLite, so a run can pause for people and resume.
 */
public final class Workflow {

    /** A graph node: reads the state, returns the keys it changes. */
    @FunctionalInterface
    public interface Step {
        Map<String, Object> apply(RunState state, Context ctx) throws Exception;
    }

    /** The nodes, in the order of the process. */
    public static final Map<String, Step> NODES = nodes();

    /** Fixed edges (source -&gt; target). The fork setup -&gt; ep_agent/bva_agent and the join -&gt; curate are here. */
    public static final List<String[]> EDGES = List.of(
            new String[] {START, "setup"},
            new String[] {"setup", "ep_agent"},     // fork: the two test-design agents
            new String[] {"setup", "bva_agent"},    //       run in parallel
            new String[] {"ep_agent", "curate"},    // join: curate waits for both
            new String[] {"bva_agent", "curate"},
            new String[] {"curate", "review_tests"},
            new String[] {"revise_tests", "review_tests"},
            new String[] {"repair", "run_tests"},
            new String[] {"coverage_gap", "run_tests"},
            new String[] {"recheck_refactoring", "review_refactoring"},
            new String[] {"adapt_tests", "run_tests"},
            new String[] {"finalize", END});

    /** Conditional edges: source -&gt; the targets its routing function can choose. */
    public static final Map<String, List<String>> ROUTES = routes();

    private Workflow() {
    }

    private static Map<String, Step> nodes() {
        Map<String, Step> m = new LinkedHashMap<>();
        m.put("setup", Setup::setupSut);
        m.put("ep_agent", Design::epAgent);
        m.put("bva_agent", Design::bvaAgent);
        m.put("curate", Design::curate);
        m.put("review_tests", Review::reviewTests);
        m.put("revise_tests", Review::reviseTests);
        m.put("run_tests", Testing::runTests);
        m.put("repair", Testing::repair);
        m.put("measure_coverage", Coverage::measureCoverage);
        m.put("coverage_gap", Coverage::coverageGap);
        m.put("refactor", Refactoring::refactor);
        m.put("review_refactoring", Review::reviewRefactoring);
        m.put("recheck_refactoring", Refactoring::recheckRefactoring);
        m.put("adapt_tests", Adapt::adaptTests);
        m.put("ask_human", Review::askHuman);
        m.put("finalize", Finalize::finalize);
        return m;
    }

    private static Map<String, List<String>> routes() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("review_tests", List.of("run_tests", "revise_tests"));
        m.put("run_tests", List.of("measure_coverage", "repair", "ask_human"));
        m.put("measure_coverage", List.of("refactor", "finalize", "coverage_gap", "ask_human"));
        m.put("refactor", List.of("refactor", "review_refactoring"));
        m.put("review_refactoring", List.of("adapt_tests", "refactor", "recheck_refactoring"));
        m.put("ask_human", List.of("run_tests", "finalize"));
        return m;
    }

    // ---------------------------------------------------------------- routing (conditional edges)
    static String afterReview(RunState s) {
        return "revise".equals(s.str("review_action")) ? "revise_tests" : "run_tests";
    }

    static String afterRun(RunState s, Context ctx) {
        String status = s.map("last_run").getOrDefault("status", "").toString();
        if (status.equals("passed")) {
            return "measure_coverage";
        }
        if (List.of("build_error", "timeout", "no_tests").contains(status)) {
            return "ask_human";
        }
        if (s.num("repair_round") >= ctx.settings.maxRepairRounds || s.num("idle_repairs") >= 2) {
            return "ask_human";
        }
        return "repair";
    }

    @SuppressWarnings("unchecked")
    static String afterCoverage(RunState s, Context ctx) {
        String phase = s.phase();
        Object cov = s.map("coverage").get(phase);
        if (!(cov instanceof Map<?, ?> c)) {
            return "ask_human";
        }
        if (Jacoco.pct(Jacoco.frac((Map<String, Object>) c, "branch")) >= ctx.settings.targetBranchCoverage) {
            return phase.equals("original") ? "refactor" : "finalize";
        }
        if (s.num("coverage_round") >= ctx.settings.maxCoverageRounds) {
            return "ask_human";
        }
        return "coverage_gap";
    }

    static String afterRefactor(RunState s, Context ctx) {
        boolean ok = Boolean.TRUE.equals(s.map("refactor_compile").get("ok"));
        if (!ok && s.num("refactor_attempt") < ctx.settings.maxRefactorAttempts) {
            return "refactor"; // send the compiler errors back to the refactoring agent
        }
        return "review_refactoring";
    }

    static String afterRefactorReview(RunState s) {
        return switch (s.str("refactor_action")) {
            case "accept" -> "adapt_tests";
            case "code_edited" -> "recheck_refactoring";
            default -> "refactor";
        };
    }

    static String afterAskHuman(RunState s) {
        return s.flag("halted") ? "finalize" : "run_tests";
    }

    private static Map<String, Function<RunState, String>> routers(Context ctx) {
        Map<String, Function<RunState, String>> m = new LinkedHashMap<>();
        m.put("review_tests", Workflow::afterReview);
        m.put("run_tests", s -> afterRun(s, ctx));
        m.put("measure_coverage", s -> afterCoverage(s, ctx));
        m.put("refactor", s -> afterRefactor(s, ctx));
        m.put("review_refactoring", Workflow::afterRefactorReview);
        m.put("ask_human", Workflow::afterAskHuman);
        return m;
    }

    // ---------------------------------------------------------------- wiring
    /** The graph, not yet compiled. */
    public static StateGraph<RunState> graph(Context ctx) throws GraphStateException {
        StateGraph<RunState> graph = new StateGraph<>(RunState.SCHEMA, RunState::new);
        for (Map.Entry<String, Step> node : NODES.entrySet()) {
            graph.addNode(node.getKey(), traced(ctx, node.getKey(), node.getValue()));
        }
        for (String[] edge : EDGES) {
            graph.addEdge(edge[0], edge[1]);
        }
        Map<String, Function<RunState, String>> routers = routers(ctx);
        for (Map.Entry<String, List<String>> route : ROUTES.entrySet()) {
            Function<RunState, String> router = routers.get(route.getKey());
            Map<String, String> mappings = new LinkedHashMap<>();
            for (String target : route.getValue()) {
                mappings.put(target, target);
            }
            graph.addConditionalEdges(route.getKey(), edge_async(router::apply), mappings);
        }
        return graph;
    }

    /** The compiled graph with a checkpoint saver (SQLite for runs). */
    public static CompiledGraph<RunState> build(Context ctx, BaseCheckpointSaver saver) throws GraphStateException {
        CompileConfig.Builder config = CompileConfig.builder().releaseThread(false).recursionLimit(RECURSION_LIMIT);
        if (saver != null) {
            config.checkpointSaver(saver);
        }
        return graph(ctx).compile(config.build());
    }

    public static final int RECURSION_LIMIT = 200;

    /** runs/&lt;run&gt;/checkpoints.sqlite */
    public static BaseCheckpointSaver sqliteSaver(Path runDir) throws Exception {
        return SQLiteSaver.builder()
                .databasePath(runDir.resolve("checkpoints.sqlite").toString())
                .stateSerializer(new ObjectStreamStateSerializer<>(RunState::new))
                .createTables(true)
                .build();
    }

    /** The configuration of one run: its thread id, and the executor that runs the EP and BVA agents in parallel. */
    public static RunnableConfig config(String runId, Executor parallel) {
        RunnableConfig.Builder b = RunnableConfig.builder().threadId(runId).recursionLimit(RECURSION_LIMIT);
        if (parallel != null) {
            b.addParallelNodeExecutor("setup", parallel);
        }
        return b.build();
    }

    /** Runs a node with tracing; a Pause becomes a LangGraph4j interrupt (the run stops at the last checkpoint). */
    static AsyncNodeActionWithConfig<RunState> traced(Context ctx, String name, Step step) {
        return (state, config) -> {
            CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
            ctx.tracer.event(name, "node_start", "");
            long t0 = System.nanoTime();
            try {
                Map<String, Object> update = step.apply(state, ctx);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("updated", new ArrayList<>(new TreeSet<>(update.keySet())));
                ctx.tracer.event(name, "node_end", String.format(Locale.ROOT, "%.2fs", (System.nanoTime() - t0) / 1e9), data);
                result.complete(update);
            } catch (Pause pause) {
                ctx.tracer.event(name, "node_paused", "Pause: " + head(pause.getMessage(), 300));
                result.completeExceptionally(new GraphInterruptException(config, pause.getMessage()));
            } catch (Exception | Error e) {
                ctx.tracer.event(name, "node_error", e.getClass().getSimpleName() + ": " + head(String.valueOf(e.getMessage()), 300));
                result.completeExceptionally(e);
            }
            return result;
        };
    }

    private static String head(String text, int n) {
        return text.length() <= n ? text : text.substring(0, n);
    }
}
