package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes the curated test cases (test-cases.json) as one JUnit 5 test class. */
public final class JUnitWriter {

    /** Lines first..last of the generated class hold the test case with this id. */
    public record LineRange(int first, int last, String id) {
    }

    /** The generated source and where each test case is in it. */
    public record Rendered(String source, List<LineRange> lineMap) {
    }

    private static final Pattern FENCE = Pattern.compile("```(?:java)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern METHOD = Pattern.compile(
            "@Test\\b|^\\s*(?:public\\s+|private\\s+)?void\\s+\\w+\\s*\\([^)]*\\)\\s*(?:throws[\\w\\s,.]+)?\\{",
            Pattern.MULTILINE);
    private static final Pattern WRAPPED = Pattern.compile("track\\(\\s*$");

    private JUnitWriter() {
    }

    public static String methodName(String testId) {
        String cleaned = Text.strip(testId.replaceAll("\\W+", "_"), "_");
        return "tc_" + (cleaned.isEmpty() ? "unnamed" : cleaned);
    }

    /** The model may return statements or a whole @Test method; keep only the statements. */
    public static String bodyOf(String junit) {
        String code = junit == null ? "" : junit.strip();
        Matcher fence = FENCE.matcher(code);
        if (fence.matches()) {
            code = fence.group(1).strip();
        }
        if (METHOD.matcher(code).find()) {
            int start = code.indexOf('{');
            int end = code.lastIndexOf('}');
            if (0 <= start && start < end) {
                code = code.substring(start + 1, end);
            }
        }
        return Text.rstrip(Text.strip(Text.dedent(code), "\n"));
    }

    private static String javaString(String text, int limit) {
        String t = Text.squash(text);
        if (t.length() > limit) {
            t = t.substring(0, limit - 3) + "...";
        }
        return t.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String displayName(Map<String, Object> c) {
        List<?> also = c.get("also") instanceof List<?> l ? l : List.of();
        String extra = also.isEmpty() ? "" : " (also " + String.join(", ", also.stream().map(String::valueOf).toList()) + ")";
        return c.get("id") + " [" + str(c, "technique") + "] " + str(c, "condition") + extra + " | " + str(c, "input")
                + " -> " + str(c, "expected");
    }

    private static String str(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return v == null ? "" : v.toString();
    }

    /** Wraps every `new ClassName(...)` in TestSupport.track(...), so its timers are stopped after the test. */
    public static String trackNew(String code, String className) {
        Pattern pattern = Pattern.compile("(?<![\\w.$])new\\s+" + Pattern.quote(className) + "\\s*\\(");
        StringBuilder out = new StringBuilder();
        int i = 0;
        Matcher m = pattern.matcher(code);
        while (true) {
            if (i > code.length() || !m.find(i)) {
                out.append(code.substring(Math.min(i, code.length())));
                return out.toString();
            }
            int start = m.start();
            int depth = 0;
            int j = m.end() - 1;
            while (j < code.length()) {
                char ch = code.charAt(j);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                j++;
            }
            int k = j + 1;
            while (k < code.length() && " \t\r\n".indexOf(code.charAt(k)) >= 0) {
                k++;
            }
            boolean wrapped = WRAPPED.matcher(code.substring(Math.max(0, start - 30), start)).find();
            boolean anonymous = k < code.length() && code.charAt(k) == '{';
            int stop = Math.min(j + 1, code.length());
            if (wrapped || anonymous || j >= code.length()) {
                out.append(code, i, stop);
            } else {
                out.append(code, i, start).append("TestSupport.track(").append(code, start, stop).append(')');
            }
            i = j + 1;
        }
    }

    private static final String FIXTURE = String.join("\n",
            "    @BeforeEach",
            "    void restoreStaticState() throws Exception {",
            "        TestSupport.restoreStatics(%s.class);",
            "    }",
            "",
            "    @AfterEach",
            "    void stopTimers() {",
            "        TestSupport.cleanUp();",
            "    }");

    /** The Java source of the test class and a map (first line, last line, test id) to blame compile errors. */
    public static Rendered render(String className, String pkg, List<Map<String, Object>> cases, int timeoutSeconds) {
        List<String> out = new ArrayList<>();
        out.add("// Generated by the TA3 multi-agent system from test-cases.json; edit that file, not this one.");
        out.add("// Every new " + className + "(...) is wrapped in TestSupport.track(...) so its timers are stopped "
                + "after the test.");
        if (pkg != null && !pkg.isEmpty()) {
            out.add("package " + pkg + ";");
            out.add("");
        }
        out.addAll(List.of(
                "import static org.junit.jupiter.api.Assertions.*;",
                "",
                "import java.io.*;",
                "import java.util.*;",
                "import org.junit.jupiter.api.*;",
                "",
                "@Timeout(" + timeoutSeconds + ")",
                "@DisplayName(\"" + className + ": equivalence partitioning, boundary value and coverage tests\")",
                "class " + className + "Test {",
                ""));
        out.addAll(Text.lines(String.format(FIXTURE, className)));
        List<LineRange> lineMap = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            out.add("");
            int first = out.size() + 1;
            out.add("    @Test");
            out.add("    @DisplayName(\"" + javaString(displayName(c), 240) + "\")");
            out.add("    void " + methodName(String.valueOf(c.get("id"))) + "() throws Exception {");
            String body = trackNew(bodyOf(str(c, "junit")), className);
            if (body.isEmpty()) {
                body = "fail(\"empty test body\");";
            }
            for (String line : Text.lines(body)) {
                out.add(line.isBlank() ? "" : "        " + line);
            }
            out.add("    }");
            lineMap.add(new LineRange(first, out.size(), String.valueOf(c.get("id"))));
        }
        out.add("}");
        return new Rendered(String.join("\n", out) + "\n", lineMap);
    }

    /** The id of the test case at this line of the generated class, or null. */
    public static String blame(List<LineRange> lineMap, int line) {
        for (LineRange r : lineMap) {
            if (r.first() <= line && line <= r.last()) {
                return r.id();
            }
        }
        return null;
    }
}
