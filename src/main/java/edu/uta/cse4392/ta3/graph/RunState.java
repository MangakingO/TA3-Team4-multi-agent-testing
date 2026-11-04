package edu.uta.cse4392.ta3.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared state that flows through the LangGraph4j workflow (checkpointed after every step). Values are plain
 * JSON-like data (strings, numbers, booleans, lists, maps), so a checkpoint can be saved and resumed anywhere.
 *
 * <pre>
 * system under test: run_id, run_dir, sut_file, observe_args, observed, class_name, package, has_main,
 *                    original_api, testability (phase -&gt; analysis), projects (phase -&gt; Maven folder), phase
 * test design (1-4): ep_cases, bva_cases, ep_analysis, bva_analysis, suite, curation,
 *                    review_round, review_action, review_feedback
 * testing (5-6, 10): last_run, run_counter, repair_round, idle_repairs, adapted
 * coverage (7, 11-12): coverage (phase -&gt; summary), coverage_round
 * refactoring (8-9): refactor_attempt, refactor_round, refactor_prompt_version, refactor_feedback,
 *                    refactor_design, refactor_compile, pattern_report, refactor_action
 * people and the end: answer (a person's answer to a pause), stuck_reason, stuck_action, halted, finished,
 *                    events (appended by every node)
 * </pre>
 */
public final class RunState extends AgentState {

    /** "events" is appended to (every node adds what it did); every other key is replaced by the newest value. */
    public static final Map<String, Channel<?>> SCHEMA = Map.of("events", Channels.appenderWithDuplicate(ArrayList::new));

    public RunState(Map<String, Object> data) {
        super(data);
    }

    public Object get(String key) {
        return data().get(key);
    }

    public boolean has(String key) {
        return data().get(key) != null;
    }

    public String str(String key) {
        Object v = data().get(key);
        return v == null ? "" : v.toString();
    }

    public int num(String key) {
        Object v = data().get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    public boolean flag(String key) {
        return Boolean.TRUE.equals(data().get(key));
    }

    /** A map value (a copy, so changing it does not change the state). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = data().get(key);
        return v instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }

    /** A list of maps (test cases, events): copies of the maps. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> cases(String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object v = data().get(key);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        Object v = data().get(key);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** The Maven project folder of a phase ("original" or "refactored"), or null. */
    public Path project(String phase) {
        Object p = map("projects").get(phase);
        return p == null ? null : Path.of(p.toString());
    }

    public String pkg() {
        return str("package");
    }

    public String className() {
        return str("class_name");
    }

    public String phase() {
        return data().get("phase") == null ? "original" : str("phase");
    }
}
