package edu.uta.cse4392.ta3.nodes;

import edu.uta.cse4392.ta3.Context;
import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Pause;
import edu.uta.cse4392.ta3.Schemas;
import edu.uta.cse4392.ta3.Text;
import edu.uta.cse4392.ta3.graph.RunState;
import edu.uta.cse4392.ta3.tools.Dedup;
import edu.uta.cse4392.ta3.tools.JUnitWriter;
import edu.uta.cse4392.ta3.tools.JavaSrc;
import edu.uta.cse4392.ta3.tools.Testability;
import edu.uta.cse4392.ta3.tools.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Helpers used by several nodes: saving the suite, applying edits with a change-log entry, people's answers. */
public final class Common {

    private static final Pattern STATE_WORD = Pattern.compile("(?i)state");

    private Common() {
    }

    /** An event for the run's history (the CLI shows the last ones in `status`). */
    public static Map<String, Object> note(String node, String message, Object... extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", node);
        m.put("message", message);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            m.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return m;
    }

    /** The update a node returns: key/value pairs (a null value removes the key from the state). */
    public static Map<String, Object> update(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static List<Map<String, Object>> events(Map<String, Object> note) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(note);
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> testabilityFor(Map<String, Object> analyses, String phase) {
        Object found = analyses.get(phase);
        if (found == null) {
            found = analyses.get("original");
        }
        return found instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** The testability facts for the prompts of this phase (falls back to the original code's). */
    public static String notesFor(Map<String, Object> analyses, String phase) {
        Map<String, Object> found = testabilityFor(analyses, phase);
        return found == null ? "" : Testability.fromMap(found).notes();
    }

    public static String notesFor(RunState state, String phase) {
        return notesFor(state.map("testability"), phase);
    }

    private static String memberName(String m) {
        String head = m.contains("(") ? m.substring(0, m.indexOf('(')) : m.split(" = ")[0];
        String[] words = head.strip().split("\\s+");
        return words[words.length - 1];
    }

    /**
     * Non-private members of the original class and its nested classes, one class per line; with testCode, only
     * the members the tests reference (the state field never counts).
     */
    @SuppressWarnings("unchecked")
    public static String membersText(Map<String, Object> analyses, String testCode) {
        if (!(analyses.get("original") instanceof Map<?, ?> original)) {
            return "";
        }
        Map<String, Object> found = (Map<String, Object>) original;
        Set<String> names = testCode == null ? null : Dedup.identifiers(testCode);
        List<String> lines = new ArrayList<>();
        Testability.fromMap(found).members.forEach((cls, members) -> {
            List<String> keep = new ArrayList<>();
            for (String m : members) {
                String name = memberName(m);
                if (!m.contains("(") && STATE_WORD.matcher(name).find()) {
                    continue;
                }
                if (names == null || names.contains(name)) {
                    keep.add(m);
                }
            }
            if (!keep.isEmpty()) {
                lines.add("- " + cls + ": " + String.join("; ", keep));
            }
        });
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------- the suite
    /** A test case with its keys in the usual order (id, technique, ..., junit, also), for files people read. */
    public static Map<String, Object> ordered(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : Dedup.CASE_KEYS) {
            m.put(k, c.getOrDefault(k, ""));
        }
        m.put("also", c.get("also") instanceof List<?> l ? l : new ArrayList<>());
        c.forEach((k, v) -> m.putIfAbsent(k, v));
        return m;
    }

    public static List<Map<String, Object>> ordered(List<Map<String, Object>> suite) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : suite) {
            out.add(ordered(c));
        }
        return out;
    }

    public static Path saveSuite(Context ctx, String phase, List<Map<String, Object>> suite) {
        Path folder = ctx.phaseDir(phase);
        Path path = folder.resolve("test-cases.json");
        Json.write(path, ordered(suite));
        Text.write(folder.resolve("test-cases.md"), suiteMarkdown(suite, "Test cases (" + phase + " code)", true));
        return path;
    }

    static String str(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    static List<String> also(Map<String, Object> c) {
        List<String> out = new ArrayList<>();
        if (c.get("also") instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    public static String techniques(List<Map<String, Object>> suite) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> c : suite) {
            counts.merge(c.get("technique") == null ? "?" : str(c, "technique"), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(k + " " + v));
        return String.join(", ", parts);
    }

    public static String suiteMarkdown(List<Map<String, Object>> suite, String title, boolean withCode) {
        List<String> out = new ArrayList<>(List.of("# " + title, "",
                suite.size() + " distinct test cases: " + techniques(suite), "",
                "| Id | Technique | Method | Condition | Input | Expected |", "|---|---|---|---|---|---|"));
        for (Map<String, Object> c : suite) {
            List<String> also = also(c);
            String tid = str(c, "id") + (also.isEmpty() ? "" : " (also " + String.join(", ", also) + ")");
            out.add("| " + Text.cell(tid) + " | " + Text.cell(c.get("technique")) + " | " + Text.cell(c.get("method"))
                    + " | " + Text.cell(c.get("condition")) + " | " + Text.cell(c.get("input")) + " | "
                    + Text.cell(c.get("expected")) + " |");
        }
        if (withCode) {
            out.addAll(List.of("", "## JUnit code", ""));
            for (Map<String, Object> c : suite) {
                out.addAll(List.of("### " + str(c, "id"), "", "```java", JUnitWriter.bodyOf(str(c, "junit")), "```", ""));
            }
        }
        return String.join("\n", out) + "\n";
    }

    /** The test cases in a test-cases.json file (people may edit it during a review). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> loadSuite(Path path) {
        Object data = Json.read(path);
        if (data instanceof Map<?, ?> m) {
            data = ((Map<String, Object>) m).getOrDefault("test_cases", List.of());
        }
        List<Map<String, Object>> suite = new ArrayList<>();
        if (!(data instanceof List<?> list)) {
            return suite;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> c = (Map<String, Object>) raw;
            if (str(c, "id").isEmpty() || str(c, "junit").isEmpty()) {
                continue;
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String k : Dedup.CASE_KEYS) {
                clean.put(k, c.get(k) == null ? "" : c.get(k));
            }
            clean.put("also", new ArrayList<>(also(c)));
            suite.add(clean);
        }
        return suite;
    }

    // ---------------------------------------------------------------- applying edits
    /** The suite after the edits, and how many edits were applied. */
    public record EditResult(List<Map<String, Object>> suite, int changed) {
    }

    public static EditResult applyTestEdits(Context ctx, String phase, String agent, List<Map<String, Object>> suite,
                                            List<Schemas.TestEdit> edits, Integer round) {
        List<Map<String, Object>> current = new ArrayList<>(suite);
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> c : current) {
            byId.put(str(c, "id"), c);
        }
        int changed = 0;
        for (Schemas.TestEdit edit : edits) {
            Map<String, Object> c = byId.get(edit.id());
            if (c == null) {
                ctx.tracer.event(agent, "warning", "edit for unknown test id " + edit.id() + " ignored");
                continue;
            }
            String before = Json.pretty(ordered(c));
            String after;
            if (edit.action().equals("delete")) {
                current.removeIf(x -> str(x, "id").equals(edit.id()));
                byId.remove(edit.id());
                after = "";
            } else {
                if (JUnitWriter.bodyOf(edit.junit()).isBlank()) {
                    ctx.tracer.event(agent, "warning", "modify for " + edit.id() + " has no code; ignored");
                    continue;
                }
                c.put("junit", JUnitWriter.bodyOf(edit.junit()));
                if (!edit.expected().isBlank()) {
                    c.put("expected", edit.expected().strip());
                }
                after = Json.pretty(ordered(c));
            }
            ctx.changes.record(phase, agent, "test case", edit.id(), edit.action(), edit.reason(), before, after, round);
            changed++;
        }
        return new EditResult(current, changed);
    }

    public static int applyCodeEdits(Context ctx, String phase, String agent, Path project, String pkg,
                                     List<Schemas.CodeEdit> edits, Integer round, boolean allowNewFiles) {
        Map<String, String> current = Workspace.readSources(project);
        int changed = 0;
        for (Schemas.CodeEdit edit : edits) {
            String name = Path.of(edit.file().replace('\\', '/')).getFileName().toString();
            if (!name.endsWith(".java")) {
                name = name + ".java";
            }
            String declared = JavaSrc.primaryType(edit.content());
            if (!declared.isEmpty() && !(declared + ".java").equals(name) && current.containsKey(declared + ".java")) {
                name = declared + ".java";
            }
            if (!current.containsKey(name) && !allowNewFiles) {
                ctx.tracer.event(agent, "warning", "code edit for unknown file " + name + " ignored");
                continue;
            }
            String before = current.getOrDefault(name, "");
            String after = Text.rstrip(edit.content()) + "\n";
            if (before.strip().equals(after.strip())) {
                continue;
            }
            Workspace.writeSources(project, pkg, Map.of(name, after), false);
            ctx.changes.record(phase, agent, "code", name, before.isEmpty() ? "add" : "modify", edit.reason(), before,
                    after, round);
            current.put(name, after);
            changed++;
        }
        return changed;
    }

    // ---------------------------------------------------------------- people's answers
    /**
     * The answer a person gave to this pause (the CLI puts it into the state when the run resumes), or a Pause
     * that stops the run until someone answers.
     */
    @SuppressWarnings("unchecked")
    public static Object answer(RunState state, Map<String, Object> pause) {
        Object given = state.get("answer"); // set by the command line when the run resumes
        if (given instanceof Map<?, ?> a && pause.get("kind").equals(a.get("kind"))) {
            return ((Map<String, Object>) a).get("value");
        }
        throw new Pause(pause); // stops the graph until someone answers
    }

    /** The hint at the end of a review file: how to answer without the console. */
    public static String answerHint(String runId, Path md, String options) {
        return "\n---\nAnswer in the console (" + options + "). Without the console, write your answer into `"
                + md.getFileName() + ".answer.txt` next to this file, then run `java -jar target/ta3-agents.jar resume "
                + runId + "`.\n";
    }

    /** (action, feedback) from {"action": ..., "feedback": ...} or text like "revise: add a test for null". */
    @SuppressWarnings("unchecked")
    public static String[] parseAnswer(Object answer, Map<String, List<String>> actions) {
        String word;
        String feedback;
        if (answer instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            word = String.valueOf(map.getOrDefault("action", ""));
            feedback = String.valueOf(map.getOrDefault("feedback", ""));
        } else {
            String text = answer == null ? "" : answer.toString().strip();
            int cut = text.length();
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == ':' || Character.isWhitespace(text.charAt(i))) {
                    cut = i;
                    break;
                }
            }
            word = text.substring(0, cut);
            String rest = text.substring(cut);
            int k = 0;
            while (k < rest.length() && (rest.charAt(k) == ':' || Character.isWhitespace(rest.charAt(k)))) {
                k++;
            }
            feedback = rest.substring(k);
        }
        String w = word.strip().toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, List<String>> e : actions.entrySet()) {
            if (e.getValue().contains(w)) {
                return new String[] {e.getKey(), feedback.strip()};
            }
        }
        return new String[] {actions.keySet().iterator().next(), ""}; // unknown answer: the first (safest) action
    }

    static boolean exists(Path p) {
        return p != null && Files.exists(p);
    }
}
