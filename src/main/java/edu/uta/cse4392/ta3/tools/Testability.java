package edu.uta.cse4392.ta3.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Testability analysis: what makes the class under test hard to test, and how the tests cope with it. The
 * findings become a "facts" section in every prompt that writes or fixes tests, and the JUnit writer adds the
 * matching fixture (restore static fields, stop timers) to the test class. Works on the source text and on the
 * compiled classes (javap), so it is not specific to SeasonSwitch.
 */
public final class Testability {

    private static final List<String> TYPE_PREFIXES = List.of("java.lang.", "java.util.", "java.awt.event.", "java.io.");
    private static final Pattern NESTED = Pattern.compile("\\b[A-Za-z_]\\w*\\$(\\w+)");
    private static final Pattern FIELD = Pattern.compile(
            "^(?:(?:public|protected|static|final|volatile|transient)\\s+)*([\\w.$<>\\[\\], ]+?)\\s+(\\w+)(?:\\s*=.*)?$");
    private static final Pattern PRIVATE_FIELD = Pattern.compile(
            "^private\\s+(?:(?:static|final|volatile|transient)\\s+)*([\\w.$<>\\[\\], ]+?)\\s+(\\w+)(?:\\s*=.*)?$");
    private static final Pattern SUBJECT_FIELD = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*([\\w.$]+)\\s+(\\w+)$");
    private static final Pattern STATIC_INIT = Pattern.compile("\\bstatic\\s*\\{\\s*}");
    private static final String[][] BLOCKING = {
            {"\\.await\\s*\\(", "await()"},
            {"\\bThread\\.sleep\\s*\\(", "Thread.sleep()"},
            {"\\.join\\s*\\(\\s*\\)", "join()"},
            {"\\.wait\\s*\\(\\s*\\)", "wait()"},
            {"\\bwhile\\s*\\(\\s*true\\s*\\)", "while (true)"},
            {"\\bfor\\s*\\(\\s*;\\s*;\\s*\\)", "for (;;)"},
    };

    public String className;
    public String pkg;
    public Map<String, List<String>> members = new LinkedHashMap<>(); // class -> non-private members
    public List<String> staticFields = new ArrayList<>();
    public List<String> timers = new ArrayList<>();                  // "SeasonSwitch.timer"
    public List<String> listeners = new ArrayList<>();               // "`s.room.actionPerformed(null)`"
    public boolean prints;
    public boolean hasMain;
    public boolean exits;
    public List<String> blocks = new ArrayList<>();
    public boolean readsStdin;

    public Testability(String className, String pkg) {
        this.className = className;
        this.pkg = pkg == null ? "" : pkg;
    }

    /** 'cse4392.SeasonSwitch$AC ac' -> 'AC ac'; 'java.lang.String[]' -> 'String[]'. */
    public static String simplify(String member, String pkg) {
        String text = member;
        if (pkg != null && !pkg.isEmpty()) {
            text = text.replaceAll("\\b" + Pattern.quote(pkg) + "\\.", "");
        }
        for (String prefix : TYPE_PREFIXES) {
            text = text.replace(prefix, "");
        }
        return NESTED.matcher(text).replaceAll("$1"); // Outer$Inner -> Inner
    }

    // ---------------------------------------------------------------- notes for the prompts
    public String notes() {
        String c = className;
        List<String> out = new ArrayList<>();
        out.add("Facts about " + c + " that every test must respect (from the testability analysis):");
        int n = 1;
        String where = pkg.isEmpty() ? "the default package" : "package " + pkg;
        out.add(n + ". The test class is in " + where + ", so tests may use package-private members. "
                + "Non-private members:");
        members.forEach((cls, list) -> {
            if (!list.isEmpty()) {
                out.add("   - " + cls + ": " + String.join("; ", list));
            }
        });
        n++;
        if (!staticFields.isEmpty()) {
            out.add(n + ". Static state: " + String.join(", ", staticFields) + " "
                    + (staticFields.size() == 1 ? "is" : "are") + " static, so shared by all " + c + " objects. "
                    + "The test class puts static fields back to their initial values before each test.");
            n++;
        }
        if (!timers.isEmpty()) {
            String who = listeners.isEmpty() ? "the listener method" : String.join(", ", listeners);
            out.add(n + ". Timers: " + String.join(", ", timers) + ". Never wait for a timer (no Thread.sleep). "
                    + "Simulate one timer event by calling the listener directly: " + who + ". "
                    + "Every " + c + " created in a test is tracked and its timers are stopped after the test.");
            n++;
        }
        if (prints) {
            out.add(n + ". Console output: " + c + " prints with System.out. Check it with "
                    + "`String out = TestSupport.captureOut(() -> s.someMethod());` which returns the printed "
                    + "text with \"\\n\" line endings; `TestSupport.lines(out)` splits it into lines.");
            n++;
        }
        if (hasMain && (exits || !blocks.isEmpty() || readsStdin)) {
            List<String> why = new ArrayList<>();
            if (exits) {
                why.add("calls System.exit");
            }
            if (!blocks.isEmpty()) {
                why.add("may never return (" + String.join(", ", blocks) + ")");
            }
            if (readsStdin) {
                why.add("reads console input");
            }
            out.add(n + ". main(): it " + String.join(" and ", why) + ". Never call " + c + ".main directly. Use "
                    + "`TestSupport.MainRun r = TestSupport.runMain(" + c + ".class, 3000, \"arg\");` which runs "
                    + "main in a separate JVM for at most 3000 ms and gives r.exitCode (the System.exit code, "
                    + "0 if main returned or was stopped), r.stillRunning (true if main was still running and "
                    + "was stopped), r.out and r.lines(). Each call takes about a second, so use it only "
                    + "for main().");
        }
        return String.join("\n", out);
    }

    public String toMarkdown() {
        return "# Testability analysis\n\n" + notes() + "\n";
    }

    /** The analysis as a map for the graph state. */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class_name", className);
        m.put("package", pkg);
        Map<String, Object> mem = new LinkedHashMap<>();
        members.forEach((k, v) -> mem.put(k, new ArrayList<>(v)));
        m.put("members", mem);
        m.put("static_fields", new ArrayList<>(staticFields));
        m.put("timers", new ArrayList<>(timers));
        m.put("listeners", new ArrayList<>(listeners));
        m.put("prints", prints);
        m.put("has_main", hasMain);
        m.put("exits", exits);
        m.put("blocks", new ArrayList<>(blocks));
        m.put("reads_stdin", readsStdin);
        return m;
    }

    /** Back from the state (members keep their order: the class under test first, then its nested classes). */
    @SuppressWarnings("unchecked")
    public static Testability fromMap(Map<String, Object> data) {
        Testability t = new Testability(String.valueOf(data.getOrDefault("class_name", "")),
                String.valueOf(data.getOrDefault("package", "")));
        if (data.get("members") instanceof Map<?, ?> mem) {
            List<String> keys = new ArrayList<>((java.util.Collection<String>) mem.keySet());
            keys.sort((a, b) -> a.equals(t.className) ? -1 : b.equals(t.className) ? 1 : a.compareTo(b));
            for (String k : keys) {
                t.members.put(k, new ArrayList<>((List<String>) mem.get(k)));
            }
        }
        t.staticFields = strings(data.get("static_fields"));
        t.timers = strings(data.get("timers"));
        t.listeners = strings(data.get("listeners"));
        t.prints = Boolean.TRUE.equals(data.get("prints"));
        t.hasMain = Boolean.TRUE.equals(data.get("has_main"));
        t.exits = Boolean.TRUE.equals(data.get("exits"));
        t.blocks = strings(data.get("blocks"));
        t.readsStdin = Boolean.TRUE.equals(data.get("reads_stdin"));
        return t;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the analysis
    private static String lastWord(String text) {
        String[] parts = text.strip().split("\\s+");
        return parts[parts.length - 1];
    }

    private static boolean has(String member, String word) {
        return (" " + member + " ").contains(" " + word + " ");
    }

    public static Testability analyze(String source, Path classesDir, String className, String pkg) {
        String code = JavaSrc.stripComments(source);
        Testability t = new Testability(className, pkg);
        t.prints = Pattern.compile("\\bSystem\\.out\\.print").matcher(code).find();
        t.hasMain = JavaSrc.hasMain(source);
        t.exits = Pattern.compile("\\bSystem\\.exit\\s*\\(").matcher(code).find();
        for (String[] b : BLOCKING) {
            if (Pattern.compile(b[0]).matcher(code).find()) {
                t.blocks.add(b[1]);
            }
        }
        t.readsStdin = Pattern.compile("System\\.in\\b").matcher(code).find();

        String fq = JavaSrc.fqcn(pkg, className);
        Map<String, Javap.ClassInfo> infos;
        try {
            infos = Javap.inspect(classesDir);
        } catch (RuntimeException e) {
            infos = new LinkedHashMap<>();
        }
        List<String> family = new ArrayList<>();
        for (String n : infos.keySet()) {
            if (n.equals(fq) || n.startsWith(fq + "$")) {
                family.add(n);
            }
        }
        family.sort((a, b) -> a.equals(fq) ? -1 : b.equals(fq) ? 1 : a.compareTo(b));
        for (String name : family) {
            Javap.ClassInfo info = infos.get(name);
            String display = simplify(name, pkg).replace("$", ".");
            if (!name.equals(fq)) {
                display = className + "." + display.substring(display.lastIndexOf('.') + 1);
            }
            List<String> list = new ArrayList<>();
            for (String m : info.members) {
                String head = m.contains("(") ? m.substring(0, m.indexOf('(')) : m.split(" = ")[0];
                if (m.startsWith("private ") || lastWord(head).contains("$")) { // synthetic: this$0, lambda$..
                    continue;
                }
                if (STATIC_INIT.matcher(m).find() || m.strip().equals("static {}")) {
                    continue;
                }
                list.add(simplify(m, pkg));
                Matcher fld = FIELD.matcher(m);
                if (!m.contains("(") && fld.find()) {
                    if (has(m, "static") && !has(m, "final")) {
                        t.staticFields.add(display + "." + fld.group(2));
                    }
                    if (fld.group(1).equals("javax.swing.Timer") || fld.group(1).equals("java.util.Timer")) {
                        t.timers.add(display + "." + fld.group(2));
                    }
                }
            }
            // private fields can be static or timers too
            for (String m : info.members) {
                if (!m.startsWith("private ") || m.contains("(")) {
                    continue;
                }
                Matcher fld = PRIVATE_FIELD.matcher(m);
                if (fld.find()) {
                    if (has(m, "static") && !has(m, "final")) {
                        t.staticFields.add(display + "." + fld.group(2));
                    }
                    if (fld.group(1).equals("javax.swing.Timer") || fld.group(1).equals("java.util.Timer")) {
                        t.timers.add(display + "." + fld.group(2));
                    }
                }
            }
            t.members.put(display, list);
        }
        // how a test triggers one timer event: call the ActionListener of the object that owns the timer
        Map<String, String> subjectFields = new LinkedHashMap<>();
        if (infos.containsKey(fq)) {
            for (String m : infos.get(fq).members) {
                Matcher fld = SUBJECT_FIELD.matcher(m);
                if (!m.contains("(") && fld.find()) {
                    subjectFields.put(fld.group(1), fld.group(2));
                }
            }
        }
        String var = className.isEmpty() ? "s" : className.substring(0, 1).toLowerCase(java.util.Locale.ROOT);
        for (String name : family) {
            if (!infos.get(name).implementsTypes.contains("java.awt.event.ActionListener")) {
                continue;
            }
            if (name.equals(fq)) {
                t.listeners.add(var + ".actionPerformed(null)");
            } else if (subjectFields.containsKey(name)) {
                t.listeners.add(var + "." + subjectFields.get(name) + ".actionPerformed(null)");
            } else {
                t.listeners.add("actionPerformed(null) on its " + name.substring(name.lastIndexOf('$') + 1) + " object");
            }
        }
        if (!t.listeners.isEmpty()) {
            List<String> quoted = new ArrayList<>();
            for (String x : t.listeners) {
                quoted.add(x.startsWith(var + ".") ? "`" + x + "`" : x);
            }
            quoted.set(0, quoted.get(0) + " (where " + var + " is the " + className + " under test)");
            t.listeners = quoted;
        }
        return t;
    }
}
