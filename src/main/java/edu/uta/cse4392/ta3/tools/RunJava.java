package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a compiled Java program directly with `java` to observe its behaviour. The program is stopped after a time
 * limit, because programs such as SeasonSwitch never end on their own. `java` is started directly (not through
 * mvn exec:java) so that stopping it also works on Windows, where killing mvn.cmd would leave Java running.
 */
public final class RunJava {

    private RunJava() {
    }

    public static String findJava() {
        String exe = Proc.windows() ? "java.exe" : "java";
        String home = System.getenv("JAVA_HOME");
        if (home != null && Files.isRegularFile(Path.of(home, "bin", exe))) {
            return Path.of(home, "bin", exe).toString();
        }
        String found = Proc.which("java");
        if (found != null) {
            return found;
        }
        Path current = Path.of(System.getProperty("java.home"), "bin", exe);
        if (Files.isRegularFile(current)) {
            return current.toString();
        }
        throw new IllegalStateException("java is not on the PATH (install a JDK or set JAVA_HOME)");
    }

    private static String clean(String text) {
        List<String> keep = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (!line.startsWith("Picked up JAVA_TOOL_OPTIONS")) {
                keep.add(line);
            }
        }
        return Text.strip(String.join("\n", keep), "\n");
    }

    /** One run: args, exit_code (null if stopped), stopped, out, err. */
    public static Map<String, Object> run(Path classesDir, String mainClass, List<String> args, double seconds) {
        List<String> cmd = new ArrayList<>(List.of(findJava(), "-Djava.awt.headless=true", "-cp", classesDir.toString(),
                mainClass));
        cmd.addAll(args);
        Proc.Result r = Proc.run(cmd, null, seconds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("args", new ArrayList<>(args));
        out.put("exit_code", r.exitCode());
        out.put("stopped", r.timedOut());
        out.put("out", clean(r.out()));
        out.put("err", clean(r.err()));
        return out;
    }

    /** The observations as one text block for the prompts and for observe.md. */
    @SuppressWarnings("unchecked")
    public static String describe(List<Map<String, Object>> runs, String mainClass, double seconds) {
        String simple = mainClass.substring(mainClass.lastIndexOf('.') + 1);
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> r : runs) {
            List<String> args = (List<String>) r.get("args");
            String shown = args.isEmpty() ? "(no arguments)" : String.join(" ", args);
            String how = Boolean.TRUE.equals(r.get("stopped"))
                    ? "still running after " + Text.seconds(seconds) + " s, stopped"
                    : "exit code " + r.get("exit_code");
            List<String> block = new ArrayList<>();
            block.add("$ java " + simple + " " + shown + "   -> " + how);
            if (!String.valueOf(r.get("out")).isEmpty()) {
                block.add(String.valueOf(r.get("out")));
            }
            if (!String.valueOf(r.get("err")).isEmpty()) {
                block.add("[stderr] " + String.valueOf(r.get("err")).replace("\n", "\n[stderr] "));
            }
            parts.add(String.join("\n", block));
        }
        return String.join("\n\n", parts);
    }
}
