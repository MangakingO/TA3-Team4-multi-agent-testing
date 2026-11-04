package edu.uta.cse4392.ta3.agents;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Prompts;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.tools.Jacoco;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static edu.uta.cse4392.ta3.agents.AgentText.str;
import static edu.uta.cse4392.ta3.agents.AgentText.values;

/**
 * Test-design agents (LLM):
 * <ul>
 *   <li>EP agent: equivalence partitioning, and BVA agent: boundary value analysis, run in parallel (steps 1-2)</li>
 *   <li>Reviser: applies the human tester's requested changes (step 4)</li>
 *   <li>Coverage agent: adds tests for branches JaCoCo reports as never taken (steps 7 and 12)</li>
 * </ul>
 */
public final class TestDesigners {

    private static final Map<String, String> TEMPLATES = Map.of("EP", "ep_agent", "BVA", "bva_agent");

    private TestDesigners() {
    }

    public static Schemas.TestSuiteDraft design(Context ctx, String technique, String className, String pkg,
                                                Map<String, String> files, String observed, String notes) {
        String name = TEMPLATES.get(technique);
        String[] prompt = Prompts.load(name, ctx.settings.promptsDir).render(values(
                "class_name", className,
                "package_text", AgentText.packageText(pkg),
                "source", AgentText.plain(files),
                "observed", AgentText.observedBlock(observed),
                "notes", notes,
                "schema", Schemas.TEST_SUITE_DRAFT.text()));
        return ctx.llm.json(name, prompt[0], prompt[1], Schemas.TEST_SUITE_DRAFT);
    }

    public static Schemas.SuiteRevision revise(Context ctx, String className, Map<String, String> files,
                                               List<Map<String, Object>> suite, String feedback, String notes) {
        String[] prompt = Prompts.load("revise_tests", ctx.settings.promptsDir).render(values(
                "class_name", className,
                "source", AgentText.plain(files),
                "suite", AgentText.suiteLines(suite),
                "feedback", feedback == null || feedback.isBlank() ? "(no feedback given)" : feedback.strip(),
                "notes", notes,
                "schema", Schemas.SUITE_REVISION.text()));
        return ctx.llm.json("reviser", prompt[0], prompt[1], Schemas.SUITE_REVISION);
    }

    @SuppressWarnings("unchecked")
    public static Schemas.TestSuiteDraft coverageTests(Context ctx, String className, String phase,
                                                       Map<String, String> files, Map<String, Object> summary,
                                                       List<Map<String, Object>> suite, String notes) {
        Map<String, Map<Integer, String>> marks = new LinkedHashMap<>();
        for (String name : files.keySet()) {
            marks.put(name, Jacoco.marksForFile(summary, name));
        }
        List<String> missed = new ArrayList<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) summary.getOrDefault("missed_branches", List.of())) {
            missed.add("- " + b.get("file") + " line " + b.get("line") + ": " + b.get("missed") + " of "
                    + (Jacoco.intOf(b, "missed") + Jacoco.intOf(b, "covered")) + " branches never taken");
        }
        List<Map<String, Object>> lines = (List<Map<String, Object>>) summary.getOrDefault("missed_lines", List.of());
        for (Map<String, Object> ln : lines.subList(0, Math.min(30, lines.size()))) {
            missed.add("- " + ln.get("file") + " line " + ln.get("line") + ": never executed");
        }
        List<String> existing = new ArrayList<>();
        for (Map<String, Object> c : suite) {
            existing.add("- " + c.get("id") + ": " + str(c, "condition") + " | " + str(c, "input"));
        }
        String[] prompt = Prompts.load("coverage_tests", ctx.settings.promptsDir).render(values(
                "class_name", className,
                "phase", phase,
                "sources", AgentText.sourcesBlock(files, marks, true),
                "missed", missed.isEmpty() ? "(none listed)" : String.join("\n", missed),
                "existing", existing.isEmpty() ? "(none)" : String.join("\n", existing),
                "notes", notes,
                "schema", Schemas.TEST_SUITE_DRAFT.text()));
        return ctx.llm.json("coverage_agent", prompt[0], prompt[1], Schemas.TEST_SUITE_DRAFT);
    }
}
