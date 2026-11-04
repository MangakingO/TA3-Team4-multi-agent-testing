package edu.uta.cse4392.ta3.agents;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Prompts;
import edu.uta.cse4392.ta3.Schemas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static edu.uta.cse4392.ta3.agents.AgentText.str;
import static edu.uta.cse4392.ta3.agents.AgentText.values;

/** Test adapter agent (LLM): makes the existing test cases compile against the refactored code (step 10). */
public final class Adapter {

    /** A test case that no longer compiles, with its compiler errors. */
    public record Problem(Map<String, Object> testCase, List<Map<String, Object>> errors) {
    }

    private Adapter() {
    }

    public static Schemas.RepairPlan adapt(Context ctx, String className, Map<String, String> files,
                                           List<Problem> problems, String notes) {
        List<String> text = new ArrayList<>();
        for (Problem p : problems) {
            text.add("### " + p.testCase().get("id"));
            text.add("```java");
            text.add(str(p.testCase(), "junit").strip());
            text.add("```");
            for (Map<String, Object> e : p.errors()) {
                text.add("- line " + e.get("line") + ": " + e.get("message"));
            }
        }
        String[] prompt = Prompts.load("adapt_tests", ctx.settings.promptsDir).render(values(
                "class_name", className,
                "sources", AgentText.sourcesBlock(files),
                "problems", String.join("\n", text),
                "notes", notes,
                "schema", Schemas.REPAIR_PLAN.text()));
        return ctx.llm.json("test_adapter", prompt[0], prompt[1], Schemas.REPAIR_PLAN);
    }
}
