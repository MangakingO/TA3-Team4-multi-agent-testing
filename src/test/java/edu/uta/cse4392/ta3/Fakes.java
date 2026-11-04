package edu.uta.cse4392.ta3;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted stand-in for the language model, so the whole workflow can be tested without Ollama. The replies are
 * written for the FanSwitch fixture (src/test/resources/fixtures/FanSwitch.java). They are canned answers, not
 * model output.
 */
public final class Fakes {

    private Fakes() {
    }

    public static String fixture(String name) {
        try (InputStream in = Fakes.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> testCase(String id, String technique, String method, String condition,
                                               String input, String expected, String junit) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("technique", technique);
        c.put("method", method);
        c.put("condition", condition);
        c.put("input", input);
        c.put("expected", expected);
        c.put("junit", junit);
        return c;
    }

    private static Map<String, Object> draft(String analysis, List<Map<String, Object>> cases) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysis", analysis);
        m.put("test_cases", cases);
        return m;
    }

    public static final Map<String, Object> EP = draft(
            "pull(): state OFF, LOW, HIGH (valid). setSpeed(s): 0..2 valid; s<0 invalid; s>2 invalid.", List.of(
                    testCase("EP-01", "EP", "FanSwitch()", "new fan", "new FanSwitch()", "OFF",
                            "FanSwitch fan = new FanSwitch();\nassertEquals(\"OFF\", fan.getSetting());"),
                    testCase("EP-02", "EP", "pull()", "state OFF (valid)", "pull once", "LOW",
                            "FanSwitch fan = new FanSwitch();\nfan.pull();\nassertEquals(\"LOW\", fan.getSetting());"),
                    testCase("EP-03", "EP", "setSpeed(int)", "0 <= s <= 2 (valid)", "setSpeed(1)", "LOW",
                            "FanSwitch fan = new FanSwitch();\nfan.setSpeed(1);\nassertEquals(\"LOW\", fan.getSetting());"),
                    testCase("EP-04", "EP", "setSpeed(int)", "s < 0 (invalid)", "setSpeed(-5)", "IllegalArgumentException",
                            "FanSwitch fan = new FanSwitch();\n"
                                    + "assertThrows(IllegalArgumentException.class, () -> fan.setSpeed(-5));"),
                    testCase("EP-05", "EP", "setSpeed(int)", "s > 2 (invalid)", "setSpeed(9)", "IllegalArgumentException",
                            "FanSwitch fan = new FanSwitch();\n"
                                    + "assertThrows(IllegalArgumentException.class, () -> fan.setSpeed(9));")));

    public static final Map<String, Object> BVA = draft(
            "setSpeed: -1, 0, 2, 3. pull: 1, 2, 3 events (wrap-around).", List.of(
                    testCase("BVA-01", "BVA", "setSpeed(int)", "s = -1 (below min)", "setSpeed(-1)",
                            "IllegalArgumentException", "FanSwitch f = new FanSwitch();\n"
                                    + "assertThrows(IllegalArgumentException.class, () -> f.setSpeed(-1));"),
                    testCase("BVA-02", "BVA", "setSpeed(int)", "s = 0 (min)", "setSpeed(0)", "OFF",
                            "FanSwitch f = new FanSwitch();\nf.setSpeed(0);\nassertEquals(\"OFF\", f.getSetting());"),
                    testCase("BVA-03", "BVA", "setSpeed(int)", "s = 2 (max)", "setSpeed(2)", "HIGH",
                            "FanSwitch f = new FanSwitch();\nf.setSpeed(2);\nassertEquals(\"HIGH\", f.getSetting());"),
                    testCase("BVA-04", "BVA", "setSpeed(int)", "s = 3 (above max)", "setSpeed(3)",
                            "IllegalArgumentException", "FanSwitch f = new FanSwitch();\n"
                                    + "assertThrows(IllegalArgumentException.class, () -> f.setSpeed(3));"),
                    // same code as EP-02 except the variable name: the curator must drop it
                    testCase("BVA-05", "BVA", "pull()", "1 event", "pull once", "LOW",
                            "FanSwitch f = new FanSwitch();\nf.pull();\nassertEquals(\"LOW\", f.getSetting());"),
                    testCase("BVA-06", "BVA", "pull()", "3 events: wrap-around HIGH -> OFF", "pull 3 times", "OFF",
                            "FanSwitch f = new FanSwitch();\nf.pull();\nf.pull();\nf.pull();\n"
                                    + "assertEquals(\"OFF\", f.getSetting());")));

    public static final Map<String, Object> REVISION = Map.of(
            "summary", "added the test the reviewer asked for",
            "add", List.of(testCase("REVIEW-01", "REVIEW", "pull()", "HIGH then pull", "setSpeed(2); pull()", "OFF",
                    "FanSwitch fan = new FanSwitch();\nfan.setSpeed(2);\nfan.pull();\nassertEquals(\"OFF\", fan.getSetting());")),
            "modify", List.of(),
            "delete", List.of());

    public static final String FIXED = fixture("FanSwitch.java").replace(
            "            speed = 1;\n        } else", "            speed = 0;\n        } else");

    public static final Map<String, Object> REPAIR = Map.of(
            "diagnosis", "BVA-06 and REVIEW-01 expect OFF after HIGH, as the documentation says; pull() goes to LOW. "
                    + "The code is wrong.",
            "test_edits", List.of(),
            "code_edits", List.of(Map.of("file", "FanSwitch.java", "content", FIXED,
                    "reason", "pull() at HIGH must wrap around to OFF (documented cycle OFF -> LOW -> HIGH -> OFF)")));

    public static final Map<String, Object> COVERAGE = draft("main() never ran", List.of(
            testCase("COV-01", "COVERAGE", "main(String[])", "line 39: loop of main()", "main(new String[0])",
                    "prints OFF, LOW, HIGH, OFF",
                    "PrintStream old = System.out;\nByteArrayOutputStream out = new ByteArrayOutputStream();\n"
                            + "System.setOut(new PrintStream(out));\ntry {\n    FanSwitch.main(new String[0]);\n} finally {\n"
                            + "    System.setOut(old);\n}\nString[] lines = out.toString().trim().split(\"\\\\R\");\n"
                            + "assertArrayEquals(new String[] {\"OFF\", \"LOW\", \"HIGH\", \"OFF\"}, lines);")));

    public static final String REFACTOR = """
            Design

            | current state | event | next state | action |
            |---|---|---|---|
            | Off | pull | Low | - |
            | Low | pull | High | - |
            | High | pull | Off | - |

            ```java
            // File: FanState.java
            abstract class FanState {
                abstract void pull(FanSwitch fan);
                abstract String name();
            }
            ```

            ```java
            // File: Off.java
            class Off extends FanState {
                void pull(FanSwitch fan) { fan.setState(FanSwitch.LOW); }
                String name() { return "OFF"; }
            }
            ```

            ```java
            // File: Low.java
            class Low extends FanState {
                void pull(FanSwitch fan) { fan.setState(FanSwitch.HIGH); }
                String name() { return "LOW"; }
            }
            ```

            ```java
            // File: High.java
            class High extends FanState {
                void pull(FanSwitch fan) { fan.setState(FanSwitch.OFF); }
                String name() { return "HIGH"; }
            }
            ```

            ```java
            // File: FanSwitch.java
            public class FanSwitch {
                static final FanState OFF = new Off();
                static final FanState LOW = new Low();
                static final FanState HIGH = new High();
                private static final FanState[] BY_SPEED = {OFF, LOW, HIGH};

                private FanState state = OFF;

                void setState(FanState next) { state = next; }

                public void pull() { state.pull(this); }

                public void setSpeed(int s) {
                    if (s < 0 || s > 2) {
                        throw new IllegalArgumentException("speed must be 0, 1 or 2: " + s);
                    }
                    state = BY_SPEED[s];
                }

                public String getSetting() { return state.name(); }

                public static void main(String[] args) {
                    FanSwitch fan = new FanSwitch();
                    for (int i = 0; i < 4; i++) {
                        System.out.println(fan.getSetting());
                        fan.pull();
                    }
                }
            }
            ```
            """;

    /** The scripted replies: role -> replies in order. */
    public static Map<String, List<String>> script() {
        Map<String, List<String>> s = new HashMap<>();
        s.put("ep_agent", List.of(Json.compact(EP)));
        s.put("bva_agent", List.of(Json.compact(BVA)));
        s.put("reviser", List.of(Json.compact(REVISION)));
        s.put("repair_agent", List.of(Json.compact(REPAIR)));
        s.put("coverage_agent", List.of(Json.compact(COVERAGE)));
        s.put("refactor_agent", List.of(REFACTOR));
        return s;
    }

    /** Returns the scripted reply for each role, in order; records every prompt it was given. */
    public static final class ScriptedLlm extends Llm {
        private final Map<String, Deque<String>> script = new HashMap<>();
        public final List<String[]> calls = new ArrayList<>();

        public ScriptedLlm(Settings settings, Tracer tracer, Map<String, List<String>> replies) {
            super(settings, tracer);
            replies.forEach((role, list) -> script.put(role, new ArrayDeque<>(list)));
        }

        @Override
        protected synchronized String complete(String role, String modelName, String system, String user,
                                               Schemas.Spec<?> spec, Path promptFile, Path responseFile) {
            calls.add(new String[] {role, user});
            Deque<String> replies = script.get(role);
            if (replies == null || replies.isEmpty()) {
                throw new AssertionError("no scripted reply left for " + role);
            }
            return replies.pop();
        }
    }
}
