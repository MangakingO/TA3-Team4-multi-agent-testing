package edu.uta.cse4392.ta3.agents;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Prompts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static edu.uta.cse4392.ta3.agents.AgentText.values;

/** Refactoring agent (LLM): applies the State pattern using the team's final prompt (step 8). */
public final class Refactorer {

    private Refactorer() {
    }

    /** The team's feedback and the compiler errors of the previous attempt, for the prompt's $feedback slot. */
    public static String feedbackBlock(String teamFeedback, List<Map<String, Object>> compileErrors,
                                       Map<String, String> previousFiles) {
        List<String> parts = new ArrayList<>();
        if (teamFeedback != null && !teamFeedback.isBlank()) {
            parts.add("The team reviewed your previous refactoring and asks for these changes:\n" + teamFeedback.strip());
        }
        if (compileErrors != null && !compileErrors.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> e : compileErrors) {
                lines.add("- " + e.get("file") + " line " + e.get("line") + ": " + e.get("message"));
            }
            parts.add("Your previous refactoring did not compile. Fix these errors and return all files again:\n"
                    + String.join("\n", lines));
        }
        if (!parts.isEmpty() && previousFiles != null && !previousFiles.isEmpty()) {
            parts.add("Your previous refactoring:\n" + AgentText.sourcesBlock(previousFiles, null, true));
        }
        return parts.isEmpty() ? "" : "\n" + String.join("\n\n", parts) + "\n";
    }

    public static String refactor(Context ctx, Path promptPath, String className, String pkg,
                                  Map<String, String> originalFiles, List<String> api, String feedback, String members,
                                  String notes) {
        List<String> sources = new ArrayList<>();
        for (String code : originalFiles.values()) {
            sources.add(code.stripTrailing());
        }
        List<String> apiLines = new ArrayList<>();
        for (String m : api) {
            apiLines.add("- " + m);
        }
        String[] prompt = new Prompts.Template(promptPath).render(values(
                "class_name", className,
                "package_text", AgentText.packageText(pkg),
                "source", String.join("\n\n", sources),
                "api", apiLines.isEmpty() ? "- (see the code)" : String.join("\n", apiLines),
                "feedback", feedback,
                "members", members == null || members.isBlank() ? "(see the code)" : members,
                "notes", notes));
        return ctx.llm.text("refactor_agent", prompt[0], prompt[1]);
    }
}
