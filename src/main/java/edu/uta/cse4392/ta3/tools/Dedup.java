package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test curator: merges test cases from several agents and removes duplicates. Two test cases are duplicates when
 * <ul>
 *   <li>their JUnit code is the same after removing comments and whitespace and renaming local variables
 *   (v0, v1, ...), or</li>
 *   <li>they exercise the same method with the same input and expect the same result.</li>
 * </ul>
 * Same method and input but a different expected result is a conflict: both are kept and the human reviewer is
 * asked to decide.
 */
public final class Dedup {

    public static final List<String> CASE_KEYS =
            List.of("id", "technique", "method", "condition", "input", "expected", "junit");

    private static final Pattern DECL = Pattern.compile(
            "\\b(?:final\\s+)?(?:var|int|long|short|byte|char|float|double|boolean|"
                    + "[A-Z][\\w.]*(?:<[^<>;]*>)?)(?:\\[\\])*\\s+([a-z_]\\w*)\\s*(?:=|:)");
    private static final Pattern SEGMENTS = Pattern.compile(
            "(?<str>\"(?:\\\\.|[^\"\\\\\\n])*+\")|(?<chr>'(?:\\\\.|[^'\\\\\\n])*+')"
                    + "|(?<line>//[^\\n]*)|(?<block>/\\*.*?\\*/)", Pattern.DOTALL);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    /** The result of a merge: the suite and a report (added, duplicates, conflicts, dropped). */
    public record Merged(List<Map<String, Object>> suite, Map<String, Object> report) {
    }

    private record Segment(boolean code, String text) {
    }

    private Dedup() {
    }

    /** Code and literal pieces of Java code; comments dropped, literals kept exactly. */
    private static List<Segment> segments(String code) {
        List<Segment> out = new ArrayList<>();
        Matcher m = SEGMENTS.matcher(code);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) {
                out.add(new Segment(true, code.substring(pos, m.start())));
            }
            if (m.group("str") != null || m.group("chr") != null) {
                out.add(new Segment(false, m.group()));
            }
            pos = m.end();
        }
        if (pos < code.length()) {
            out.add(new Segment(true, code.substring(pos)));
        }
        return out;
    }

    private static String codeOnly(List<Segment> parts) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : parts) {
            if (s.code()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.text());
            }
        }
        return sb.toString();
    }

    /**
     * Test code without comments and whitespace, local variables renamed v0, v1, ...; string and character
     * literals are kept exactly, because they usually are the test data.
     */
    public static String canonicalCode(String junit) {
        List<Segment> parts = segments(JUnitWriter.bodyOf(junit)); // code | literal
        Set<String> names = new LinkedHashSet<>();                  // local variables
        Matcher d = DECL.matcher(codeOnly(parts));
        while (d.find()) {
            names.add(d.group(1));
        }
        StringBuilder out = new StringBuilder();
        for (Segment s : parts) {
            String text = s.text();
            if (s.code()) {                // rename locals v0, v1, ...; drop spaces
                int i = 0;
                for (String name : names) {
                    text = text.replaceAll("\\b" + Pattern.quote(name) + "\\b", "v" + i);
                    i++;
                }
                text = text.replaceAll("\\s+", "");
            }
            out.append(text);              // literals are kept exactly
        }
        return out.toString();
    }

    /** Identifiers used in Java code, ignoring string literals and comments. */
    public static Set<String> identifiers(String code) {
        Set<String> out = new HashSet<>();
        Matcher m = IDENT.matcher(codeOnly(segments(code == null ? "" : code)));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Conservative: case and spacing only, so different inputs are never merged by accident. */
    private static String norm(Object text) {
        return Text.squash(text == null ? "" : text.toString()).toLowerCase(Locale.ROOT);
    }

    private static String uniqueId(String wanted, Set<String> taken, String prefix) {
        String base = wanted == null ? "" : wanted.strip();
        if (base.isEmpty()) {
            base = prefix + "-01";
        }
        base = Text.strip(base.replaceAll("[^\\w-]+", "-"), "-");
        if (base.isEmpty()) {
            base = prefix + "-01";
        }
        if (!taken.contains(base)) {
            return base;
        }
        int n = 2;
        while (taken.contains(base + "-" + n)) {
            n++;
        }
        return base + "-" + n;
    }

    private static String str(Map<String, Object> c, String key) {
        Object v = c.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> also(Map<String, Object> c) {
        return (List<Object>) c.get("also");
    }

    /** A copy of a test case with an "also" list of its own. */
    public static Map<String, Object> copyCase(Map<String, Object> c) {
        Map<String, Object> copy = new LinkedHashMap<>(c);
        copy.put("also", c.get("also") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>());
        return copy;
    }

    /** Adds incoming cases to existing ones, dropping duplicates. */
    public static Merged merge(List<Map<String, Object>> existing, List<Map<String, Object>> incoming,
                               String defaultTechnique) {
        List<Map<String, Object>> suite = new ArrayList<>();
        for (Map<String, Object> c : existing) {
            suite.add(copyCase(c));
        }
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        Map<String, Map<String, Object>> byIo = new HashMap<>();
        Set<String> taken = new HashSet<>();
        for (Map<String, Object> c : suite) {
            byCode.put(canonicalCode(str(c, "junit")), c);
            byIo.put(norm(c.get("method")) + "\u0000" + norm(c.get("input")), c);
            taken.add(str(c, "id"));
        }
        List<Object> added = new ArrayList<>();
        List<Object> duplicates = new ArrayList<>();
        List<Object> conflicts = new ArrayList<>();
        List<Object> dropped = new ArrayList<>();

        for (Map<String, Object> raw : incoming) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String k : CASE_KEYS) {
                c.put(k, str(raw, k));
            }
            String technique = str(c, "technique").isEmpty() ? defaultTechnique : str(c, "technique");
            c.put("technique", technique.toUpperCase(Locale.ROOT));
            c.put("also", new ArrayList<>());
            if (JUnitWriter.bodyOf(str(c, "junit")).isBlank()) {
                dropped.add(mapOf("id", c.get("id"), "reason", "no JUnit code"));
                continue;
            }
            String codeKey = canonicalCode(str(c, "junit"));
            String ioKey = norm(c.get("method")) + "\u0000" + norm(c.get("input"));
            Map<String, Object> twin = null;
            String rule = "";
            if (byCode.containsKey(codeKey)) {
                twin = byCode.get(codeKey);
                rule = "identical test code";
            } else if (byIo.containsKey(ioKey) && !ioKey.equals("\u0000")) {
                Map<String, Object> other = byIo.get(ioKey);
                if (norm(other.get("expected")).equals(norm(c.get("expected")))) {
                    twin = other;
                    rule = "same input and expected result";
                } else {
                    conflicts.add(mapOf("kept", other.get("id"), "new", str(c, "id").isEmpty() ? "?" : c.get("id"),
                            "input", c.get("input"), "expected_kept", other.get("expected"),
                            "expected_new", c.get("expected")));
                }
            }
            String id = str(c, "id").isEmpty() ? "?" : str(c, "id");
            if (twin != null) {
                also(twin).add(id);
                Set<String> techniques = new HashSet<>(List.of(str(twin, "technique").split("\\+")));
                techniques.add(str(c, "technique"));
                List<String> ordered = new ArrayList<>();
                for (String t : List.of("EP", "BVA", "COVERAGE", "REVIEW")) {
                    if (techniques.contains(t)) {
                        ordered.add(t);
                    }
                }
                if (!ordered.isEmpty()) {
                    twin.put("technique", String.join("+", ordered));
                }
                duplicates.add(mapOf("kept", twin.get("id"), "removed", id, "rule", rule));
                continue;
            }
            c.put("id", uniqueId(str(c, "id"), taken, str(c, "technique")));
            taken.add(str(c, "id"));
            suite.add(c);
            byCode.put(codeKey, c);
            byIo.putIfAbsent(ioKey, c);
            added.add(c.get("id"));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("received", incoming.size());
        report.put("added", added);
        report.put("duplicates", duplicates);
        report.put("conflicts", conflicts);
        report.put("dropped", dropped);
        report.put("total", suite.size());
        return new Merged(suite, report);
    }

    /** The join step: one distinct suite from the EP and BVA agents' test cases. */
    @SuppressWarnings("unchecked")
    public static Merged curate(List<Map<String, Object>> epCases, List<Map<String, Object>> bvaCases) {
        Merged ep = merge(List.of(), epCases, "EP");
        Merged bva = merge(ep.suite(), bvaCases, "BVA");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ep_received", ep.report().get("received"));
        report.put("bva_received", bva.report().get("received"));
        for (String key : List.of("duplicates", "conflicts", "dropped")) {
            List<Object> both = new ArrayList<>((List<Object>) ep.report().get(key));
            both.addAll((List<Object>) bva.report().get(key));
            report.put(key, both);
        }
        report.put("distinct", bva.suite().size());
        return new Merged(bva.suite(), report);
    }

    /** A LinkedHashMap from key/value pairs (values may be null). */
    public static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
