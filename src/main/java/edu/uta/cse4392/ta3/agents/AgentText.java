package edu.uta.cse4392.ta3.agents;

import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.tools.JavaSrc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How code and test cases are shown to the model (shared by the LLM agents). */
public final class AgentText {

    public static final List<String> CASE_FIELDS =
            List.of("id", "technique", "method", "condition", "input", "expected", "junit");

    private AgentText() {
    }

    public static String packageText(String pkg) {
        return pkg == null || pkg.isEmpty() ? "default package" : "package " + pkg;
    }

    /** Each file in a ```java block, with line numbers and optional notes after some lines. */
    public static String sourcesBlock(Map<String, String> files, Map<String, Map<Integer, String>> marks, boolean numbered) {
        List<String> parts = new ArrayList<>();
        files.forEach((name, content) -> {
            String body = numbered ? JavaSrc.numbered(content, marks == null ? null : marks.get(name))
                    : content.stripTrailing();
            parts.add("// ===== " + name + " =====\n```java\n" + body + "\n```");
        });
        return String.join("\n\n", parts);
    }

    public static String sourcesBlock(Map<String, String> files) {
        return sourcesBlock(files, null, true);
    }

    /** The source as plain text: one file as it is, several files with a header line each. */
    public static String plain(Map<String, String> files) {
        if (files.size() == 1) {
            return files.values().iterator().next().stripTrailing();
        }
        List<String> parts = new ArrayList<>();
        files.forEach((name, code) -> parts.add("// ===== " + name + " =====\n" + code.stripTrailing()));
        return String.join("\n\n", parts);
    }

    /** One JSON line per test case. */
    public static String suiteLines(List<Map<String, Object>> suite) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> c : suite) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : CASE_FIELDS) {
                m.put(k, c.getOrDefault(k, ""));
            }
            lines.add(Json.line(m));
        }
        return String.join("\n", lines);
    }

    public static String observedBlock(String observed) {
        String text = observed == null ? "" : observed.strip();
        if (text.isEmpty()) {
            return "";
        }
        if (text.length() > 3000) {
            text = text.substring(0, 3000) + "\n... (truncated)";
        }
        return "Observed behaviour (the program run from the command line):\n```\n" + text + "\n```";
    }

    static String str(Map<String, Object> c, String key) {
        Object v = c == null ? null : c.get(key);
        return v == null ? "" : v.toString();
    }

    /** Values for a prompt template. */
    static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
