package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Settings;
import edu.uta.cse4392.ta3.Text;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test-runner tool: runs Maven and reads what it produced (Surefire XML reports, compiler errors). */
public final class Maven {

    /** Maven is not installed (or not on the PATH). */
    public static final class MavenNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MavenNotFound(String message) {
            super(message);
        }
    }

    /** One Maven run. */
    public record MavenResult(List<String> command, int exitCode, String output, double seconds, boolean timedOut) {
    }

    private static final Pattern ERROR_LINE =
            Pattern.compile("^\\[ERROR\\]\\s+(?<file>.+?\\.java):\\[(?<line>\\d+),(?<col>\\d+)\\]\\s+(?<msg>.*)$");

    private Maven() {
    }

    public static String resolveMvn(Settings settings) {
        String found = Proc.which(settings.mvn);
        if (found != null) {
            return found;
        }
        throw new MavenNotFound("Maven ('" + settings.mvn + "') is not on the PATH. Install Maven and check with "
                + "`mvn -v`, or set MVN in .env to the full path of mvn / mvn.cmd.");
    }

    public static MavenResult run(Path project, List<String> goals, Settings settings, Path logFile, List<String> extra) {
        List<String> command = new ArrayList<>();
        command.add(resolveMvn(settings));
        command.add("-B");
        command.addAll(settings.mavenArgs);
        command.addAll(extra);
        command.addAll(goals);
        Proc.Result r = Proc.run(command, project, settings.mavenTimeout);
        String output = r.out() + r.err();
        int code = r.exitCode() == null ? -1 : r.exitCode();
        if (r.timedOut()) {
            output += "\n[agent] Maven timed out";
        }
        Text.write(logFile, "$ " + String.join(" ", command) + String.format(Locale.ROOT, "\n# exit code %d, %.1fs\n\n",
                code, r.seconds()) + output);
        return new MavenResult(command, code, output, r.seconds(), r.timedOut());
    }

    /** Compiler errors in Maven's output: file, scope (main or test), line, message. */
    public static List<Map<String, Object>> parseCompileErrors(String output) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> lines = Text.lines(output);
        for (int i = 0; i < lines.size() && errors.size() < 100; i++) {
            Matcher m = ERROR_LINE.matcher(lines.get(i).strip());
            if (!m.find()) {
                continue;
            }
            List<String> detail = new ArrayList<>();
            for (String next : lines.subList(i + 1, Math.min(lines.size(), i + 4))) {
                String s = next.strip();
                if (s.startsWith("[ERROR]   ") || s.startsWith("[ERROR] symbol") || s.startsWith("[ERROR] location")) {
                    detail.add(s.replace("[ERROR]", "").strip());
                } else {
                    break;
                }
            }
            String path = m.group("file").replace('\\', '/');
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("file", path.substring(path.lastIndexOf('/') + 1));
            e.put("scope", path.contains("/src/test/") ? "test" : "main");
            e.put("line", Integer.parseInt(m.group("line")));
            e.put("message", (m.group("msg") + (detail.isEmpty() ? "" : "; " + String.join("; ", detail))).strip());
            errors.add(e);
        }
        // de-duplicate (javac and Maven both print each error)
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> e : errors) {
            if (seen.add(e.get("file") + "\u0000" + e.get("line") + "\u0000" + e.get("message"))) {
                unique.add(e);
            }
        }
        return unique;
    }

    /** Test results from Surefire's TEST-*.xml reports. */
    public static List<Map<String, Object>> parseSurefire(Path reportsDir) {
        List<Map<String, Object>> tests = new ArrayList<>();
        for (Path xml : Text.list(reportsDir, "TEST-*.xml")) {
            Document doc;
            try {
                doc = Xml.parse(xml);
            } catch (Exception e) {
                continue;
            }
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                String status = "passed";
                String message = "";
                String details = "";
                String type = "";
                for (String tag : List.of("failure", "error")) {
                    Element el = Xml.child(tc, tag);
                    if (el != null) {
                        status = tag.equals("failure") ? "failed" : "error";
                        message = el.getAttribute("message");
                        type = el.getAttribute("type");
                        details = el.getTextContent() == null ? "" : el.getTextContent().strip();
                        break;
                    }
                }
                if (Xml.child(tc, "skipped") != null) {
                    status = "skipped";
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("method", tc.getAttribute("name").replaceAll("\\(.*$", ""));
                t.put("class", tc.getAttribute("classname"));
                t.put("status", status);
                String time = tc.getAttribute("time").replace(",", "");
                t.put("time", time.isEmpty() ? 0.0 : Double.parseDouble(time));
                t.put("type", type);
                t.put("message", Text.head(message, 1000));
                List<String> traceLines = Text.lines(details);
                t.put("trace", String.join("\n", traceLines.subList(0, Math.min(12, traceLines.size()))));
                tests.add(t);
            }
        }
        return tests;
    }

    private static void cleanOutputs(Path project) {
        Path target = project.resolve("target");
        for (String sub : List.of("classes", "test-classes", "surefire-reports", "site", "jacoco.exec")) {
            Text.delete(target.resolve(sub));
        }
    }

    /** mvn test (failures don't stop the build, so JaCoCo still writes its report). */
    public static Map<String, Object> runTests(Path project, Settings settings, Path logFile) {
        cleanOutputs(project);
        MavenResult res = run(project, List.of("test"), settings, logFile, List.of("-Dmaven.test.failure.ignore=true"));
        List<Map<String, Object>> compileErrors = parseCompileErrors(res.output());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("exit_code", res.exitCode());
        summary.put("seconds", Math.round(res.seconds() * 10) / 10.0);
        summary.put("maven_log", logFile.toString());
        summary.put("tests", new ArrayList<>());
        summary.put("compile_errors", compileErrors);
        if (!compileErrors.isEmpty() || res.output().contains("COMPILATION ERROR")) {
            summary.put("status", "compile_error");
            return summary;
        }
        List<Map<String, Object>> tests = parseSurefire(project.resolve("target").resolve("surefire-reports"));
        summary.put("tests", tests);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of("passed", "failed", "error", "skipped")) {
            counts.put(k, (int) tests.stream().filter(t -> k.equals(t.get("status"))).count());
        }
        summary.put("counts", counts);
        if (res.timedOut()) {
            summary.put("status", "timeout");
        } else if (res.exitCode() != 0 && tests.isEmpty()) {
            summary.put("status", "build_error");
            summary.put("log_tail", Text.tail(res.output(), 40));
        } else if ((int) counts.get("failed") > 0 || (int) counts.get("error") > 0) {
            summary.put("status", "failed");
        } else {
            summary.put("status", tests.isEmpty() ? "no_tests" : "passed");
        }
        return summary;
    }

    /** mvn compile (or test-compile): ok, compile_errors, exit_code, log_tail. */
    public static Map<String, Object> compileOnly(Path project, Settings settings, Path logFile, boolean withTests) {
        cleanOutputs(project);
        MavenResult res = run(project, List.of(withTests ? "test-compile" : "compile"), settings, logFile, List.of());
        List<Map<String, Object>> errors = parseCompileErrors(res.output());
        boolean ok = res.exitCode() == 0 && errors.isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("compile_errors", errors);
        out.put("exit_code", res.exitCode());
        out.put("log_tail", ok ? "" : Text.tail(res.output(), 30));
        return out;
    }
}
