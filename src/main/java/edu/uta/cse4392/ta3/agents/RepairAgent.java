package edu.uta.cse4392.ta3.agents;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Prompts;
import edu.uta.cse4392.ta3.Schemas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static edu.uta.cse4392.ta3.agents.AgentText.str;
import static edu.uta.cse4392.ta3.agents.AgentText.values;

/** Repair agent (LLM): decides whether the code or the test is wrong and proposes the fix (steps 6 and 10). */
public final class RepairAgent {

    public static final Map<String, String> POLICIES = Map.of(
            "original",
            "Phase policy (original code): the test cases were designed from the intended behaviour of the "
                    + "class. If the code does not do what its comments, names and observed behaviour say it should, "
                    + "the CODE is wrong: fix it with the smallest change. If a test expects the wrong result, sets up "
                    + "the object wrongly, or has a Java mistake, the TEST is wrong: fix the test.",
            "refactored",
            "Phase policy (refactored code): these same tests passed on the original code, so they define the "
                    + "required behaviour. A failing test means the refactoring changed the behaviour: fix the refactored "
                    + "CODE and keep the State pattern intact. Change a test only if it cannot compile because it used "
                    + "something that no longer exists, and keep what it checks.");

    private RepairAgent() {
    }

    private static String failureText(Map<String, Object> c, Map<String, Object> result) {
        List<String> lines = new ArrayList<>();
        lines.add("### " + result.get("id") + " (" + result.get("status") + ")");
        if (c != null) {
            lines.add("technique: " + str(c, "technique"));
            lines.add("condition: " + str(c, "condition"));
            lines.add("input: " + str(c, "input"));
            lines.add("expected: " + str(c, "expected"));
            lines.add("junit:");
            lines.add("```java");
            lines.add(str(c, "junit").strip());
            lines.add("```");
        }
        lines.add(("failure: " + str(result, "type") + " " + str(result, "message")).strip());
        if (!str(result, "trace").isEmpty()) {
            lines.add("trace:");
            lines.add(str(result, "trace"));
        }
        return String.join("\n", lines);
    }

    private static String compileText(List<Map<String, Object>> errors, Map<String, Map<String, Object>> suiteById) {
        if (errors.isEmpty()) {
            return "(none)";
        }
        List<String> out = new ArrayList<>();
        TreeSet<String> blamed = new TreeSet<>();
        for (Map<String, Object> e : errors) {
            String where = e.get("file") + " line " + e.get("line");
            if (e.get("test_id") != null) {
                where += " (inside test " + e.get("test_id") + ")";
                blamed.add(e.get("test_id").toString());
            }
            out.add("- [" + e.get("scope") + "] " + where + ": " + e.get("message"));
        }
        for (String testId : blamed) {
            Map<String, Object> c = suiteById.get(testId);
            if (c != null) {
                out.add("\n" + testId + " code:");
                out.add("```java");
                out.add(str(c, "junit").strip());
                out.add("```");
            }
        }
        return String.join("\n", out);
    }

    public static Schemas.RepairPlan propose(Context ctx, String phase, String className, Map<String, String> files,
                                             List<Map<String, Object>> failing, List<Map<String, Object>> compileErrors,
                                             List<Map<String, Object>> suite, int passing, String notes) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> c : suite) {
            byId.put(str(c, "id"), c);
        }
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> r : failing) {
            failures.add(failureText(byId.get(str(r, "id")), r));
        }
        String[] prompt = Prompts.load("repair_agent", ctx.settings.promptsDir).render(values(
                "class_name", className,
                "phase", phase,
                "policy", POLICIES.get(phase),
                "sources", AgentText.sourcesBlock(files),
                "failures", failures.isEmpty() ? "(none)" : String.join("\n\n", failures),
                "compile_errors", compileText(compileErrors, byId),
                "passing", String.valueOf(passing),
                "notes", notes,
                "schema", Schemas.REPAIR_PLAN.text()));
        return ctx.llm.json("repair_agent", prompt[0], prompt[1], Schemas.REPAIR_PLAN);
    }
}
