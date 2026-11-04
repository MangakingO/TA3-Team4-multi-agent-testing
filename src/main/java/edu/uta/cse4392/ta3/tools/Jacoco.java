package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Coverage tool: reads JaCoCo's jacoco.xml (written by `mvn test` into target/site/jacoco). */
public final class Jacoco {

    private Jacoco() {
    }

    // ---------------------------------------------------------------- reading jacoco.xml
    /** {type: {missed, covered}} from the direct counter children of an element. */
    private static Map<String, int[]> counters(Element element) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Element c : Xml.children(element, "counter")) {
            out.put(c.getAttribute("type"), new int[] {Xml.intAttr(c, "missed"), Xml.intAttr(c, "covered")});
        }
        return out;
    }

    /** {covered, total, pct} of one counter type. */
    static Map<String, Object> fraction(Map<String, int[]> counters, String kind) {
        int[] c = counters.getOrDefault(kind, new int[] {0, 0});
        int total = c[0] + c[1];
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("covered", c[1]);
        f.put("total", total);
        f.put("pct", total == 0 ? 100.0 : Math.round(100.0 * c[1] / total * 100) / 100.0);
        return f;
    }

    /** The parsed report: classes (with methods), per-line data of each source file, and the totals. */
    public record Report(List<ClassData> classes, Map<String, Map<Integer, int[]>> lines, Map<String, int[]> totals) {
    }

    public record ClassData(String name, String source, String pkg, Map<String, int[]> counters,
                            List<MethodData> methods) {
    }

    public record MethodData(String name, String desc, int line, Map<String, int[]> counters) {
    }

    public static Report parse(Path xml) {
        Element root;
        try {
            root = Xml.parse(xml).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + xml + ": " + e.getMessage(), e);
        }
        List<ClassData> classes = new ArrayList<>();
        Map<String, Map<Integer, int[]>> lines = new LinkedHashMap<>();
        for (Element pkg : Xml.descendants(root, "package")) {
            String pkgName = pkg.getAttribute("name");
            for (Element cls : Xml.children(pkg, "class")) {
                List<MethodData> methods = new ArrayList<>();
                for (Element m : Xml.children(cls, "method")) {
                    methods.add(new MethodData(m.getAttribute("name"), m.getAttribute("desc"), Xml.intAttr(m, "line"),
                            counters(m)));
                }
                classes.add(new ClassData(cls.getAttribute("name").replace('/', '.'), cls.getAttribute("sourcefilename"),
                        pkgName.replace('/', '.'), counters(cls), methods));
            }
            for (Element sf : Xml.children(pkg, "sourcefile")) {
                String key = (pkgName.isEmpty() ? "" : pkgName + "/") + sf.getAttribute("name");
                Map<Integer, int[]> byLine = new TreeMap<>();
                for (Element ln : Xml.children(sf, "line")) {
                    byLine.put(Xml.intAttr(ln, "nr"), new int[] {Xml.intAttr(ln, "mi"), Xml.intAttr(ln, "ci"),
                            Xml.intAttr(ln, "mb"), Xml.intAttr(ln, "cb")});
                }
                lines.put(key, byLine);
            }
        }
        return new Report(classes, lines, counters(root));
    }

    /** Branch, line, instruction, method and class coverage, per class and method, and what was never reached. */
    public static Map<String, Object> summarize(Report report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String kind : List.of("BRANCH", "LINE", "INSTRUCTION", "METHOD", "CLASS")) {
            summary.put(kind.toLowerCase(Locale.ROOT), fraction(report.totals(), kind));
        }
        summary.put("branch_vacuous", ((int) frac(summary, "branch").get("total")) == 0);
        List<Map<String, Object>> classes = new ArrayList<>();
        for (ClassData c : report.classes()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.name());
            cm.put("branch", fraction(c.counters(), "BRANCH"));
            cm.put("line", fraction(c.counters(), "LINE"));
            cm.put("method", fraction(c.counters(), "METHOD"));
            List<Map<String, Object>> methods = new ArrayList<>();
            for (MethodData m : c.methods()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", m.name());
                mm.put("line", m.line());
                mm.put("branch", fraction(m.counters(), "BRANCH"));
                mm.put("instruction", fraction(m.counters(), "INSTRUCTION"));
                methods.add(mm);
            }
            cm.put("methods", methods);
            classes.add(cm);
        }
        summary.put("classes", classes);
        List<Map<String, Object>> missedBranches = new ArrayList<>();
        List<Map<String, Object>> missedLines = new ArrayList<>();
        report.lines().forEach((source, byLine) -> byLine.forEach((nr, d) -> {
            if (d[2] > 0) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("file", source);
                b.put("line", nr);
                b.put("missed", d[2]);
                b.put("covered", d[3]);
                missedBranches.add(b);
            }
            if (d[0] > 0 && d[1] == 0) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("file", source);
                l.put("line", nr);
                missedLines.add(l);
            }
        }));
        summary.put("missed_branches", missedBranches);
        summary.put("missed_lines", missedLines);
        return summary;
    }

    // ---------------------------------------------------------------- using a summary
    @SuppressWarnings("unchecked")
    public static Map<String, Object> frac(Map<String, Object> summary, String kind) {
        return (Map<String, Object>) summary.get(kind);
    }

    public static double pct(Map<String, Object> fraction) {
        return ((Number) fraction.get("pct")).doubleValue();
    }

    public static int intOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? 0 : ((Number) v).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> summary, String key) {
        Object v = summary.get(key);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String fileName(Object path) {
        String p = String.valueOf(path).replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** Notes to print next to source lines: which branches/lines the tests never reached. */
    public static Map<Integer, String> marksForFile(Map<String, Object> summary, String fileName) {
        Map<Integer, String> marks = new LinkedHashMap<>();
        for (Map<String, Object> b : list(summary, "missed_branches")) {
            if (fileName(b.get("file")).equals(fileName)) {
                int total = intOf(b, "missed") + intOf(b, "covered");
                marks.put(intOf(b, "line"), intOf(b, "missed") + " of " + total + " branches never taken");
            }
        }
        for (Map<String, Object> ln : list(summary, "missed_lines")) {
            if (fileName(ln.get("file")).equals(fileName) && !marks.containsKey(intOf(ln, "line"))) {
                marks.put(intOf(ln, "line"), "never executed");
            }
        }
        return marks;
    }

    private static String cell(Map<String, Object> f) {
        return intOf(f, "total") > 0
                ? String.format(Locale.ROOT, "%d/%d (%.1f%%)", intOf(f, "covered"), intOf(f, "total"), pct(f))
                : "n/a (none)";
    }

    @SuppressWarnings("unchecked")
    public static String toMarkdown(Map<String, Object> summary, String title) {
        List<String> out = new ArrayList<>(List.of("# " + title, "",
                "**Branch coverage: " + cell(frac(summary, "branch")) + "**"
                        + (Boolean.TRUE.equals(summary.get("branch_vacuous"))
                        ? "  — the code has no branches, so branch coverage is vacuously 100%" : ""),
                "", "Line coverage: " + cell(frac(summary, "line")) + "  ",
                "Instruction coverage: " + cell(frac(summary, "instruction")) + "  ",
                "Method coverage: " + cell(frac(summary, "method")), "",
                "| Class | Branches | Lines | Methods |", "|---|---|---|---|"));
        for (Map<String, Object> c : list(summary, "classes")) {
            out.add("| " + c.get("name") + " | " + cell((Map<String, Object>) c.get("branch")) + " | "
                    + cell((Map<String, Object>) c.get("line")) + " | " + cell((Map<String, Object>) c.get("method")) + " |");
        }
        out.addAll(List.of("", "## Methods", "", "| Class | Method | Line | Branches | Instructions |",
                "|---|---|---|---|---|"));
        for (Map<String, Object> c : list(summary, "classes")) {
            for (Map<String, Object> m : (List<Map<String, Object>>) c.get("methods")) {
                String name = String.valueOf(m.get("name")).replace("<", "&lt;").replace(">", "&gt;");
                out.add("| " + c.get("name") + " | " + name + " | " + m.get("line") + " | "
                        + cell((Map<String, Object>) m.get("branch")) + " | "
                        + cell((Map<String, Object>) m.get("instruction")) + " |");
            }
        }
        List<Map<String, Object>> branches = list(summary, "missed_branches");
        if (!branches.isEmpty()) {
            out.addAll(List.of("", "## Branches never taken", ""));
            for (Map<String, Object> b : branches) {
                out.add("- " + b.get("file") + " line " + b.get("line") + ": " + b.get("missed") + " of "
                        + (intOf(b, "missed") + intOf(b, "covered")));
            }
        }
        List<Map<String, Object>> lines = list(summary, "missed_lines");
        if (!lines.isEmpty()) {
            out.addAll(List.of("", "## Lines never executed", ""));
            for (Map<String, Object> l : lines) {
                out.add("- " + l.get("file") + " line " + l.get("line"));
            }
        }
        return String.join("\n", out) + "\n";
    }

    /** "36/36 (100.0%)" style text used in messages. */
    public static String ratio(Map<String, Object> fraction) {
        return intOf(fraction, "covered") + "/" + intOf(fraction, "total") + " = " + Text.num(fraction.get("pct")) + "%";
    }
}
