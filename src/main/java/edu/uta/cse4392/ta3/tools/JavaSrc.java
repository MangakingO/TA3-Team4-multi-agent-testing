package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Facts about Java source text that the agents need (package, class, main method, line numbers). */
public final class JavaSrc {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*+\"");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^(?:[ \\t]*)((?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp)\\s+)*"
                    + "(class|interface|enum|record)\\s+([A-Za-z_]\\w*)", Pattern.MULTILINE);
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "\\bpublic\\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+([A-Za-z_]\\w*)");
    private static final Pattern MAIN = Pattern.compile(
            "\\bstatic\\s+(?:final\\s+)?void\\s+main\\s*\\(\\s*(?:final\\s+)?String");

    private JavaSrc() {
    }

    /** Removes comments (string literals are blanked first, so "//" inside a string survives). */
    public static String stripComments(String source) {
        String blanked = STRING.matcher(source).replaceAll("\"\"");
        String noBlocks = BLOCK_COMMENT.matcher(blanked).replaceAll("");
        return LINE_COMMENT.matcher(noBlocks).replaceAll("");
    }

    public static String packageOf(String source) {
        Matcher m = PACKAGE.matcher(stripComments(source));
        return m.find() ? m.group(1) : "";
    }

    public static List<String> typeNames(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = TYPE_DECL.matcher(stripComments(source));
        while (m.find()) {
            out.add(m.group(3));
        }
        return out;
    }

    /** The public top-level type (else the first type declared, else the fallback). */
    public static String primaryType(String source, String fallback) {
        Matcher m = PUBLIC_TYPE.matcher(stripComments(source));
        if (m.find()) {
            return m.group(1);
        }
        List<String> names = typeNames(source);
        return names.isEmpty() ? fallback : names.get(0);
    }

    public static String primaryType(String source) {
        return primaryType(source, "");
    }

    public static boolean hasMain(String source) {
        return MAIN.matcher(stripComments(source)).find();
    }

    public static String fqcn(String pkg, String name) {
        return pkg == null || pkg.isEmpty() ? name : pkg + "." + name;
    }

    /** cse4392 + SeasonSwitch.java -> cse4392/SeasonSwitch.java */
    public static Path sourceRelpath(String pkg, String fileName) {
        if (pkg == null || pkg.isEmpty()) {
            return Path.of(fileName);
        }
        return Path.of(pkg.replace('.', '/')).resolve(fileName);
    }

    /** Source with line numbers; marks adds a note after given lines (e.g. missed branches). */
    public static String numbered(String source, Map<Integer, String> marks) {
        int width = String.valueOf(source.chars().filter(c -> c == '\n').count() + 1).length();
        List<String> out = new ArrayList<>();
        List<String> lines = Text.lines(source);
        for (int i = 0; i < lines.size(); i++) {
            int nr = i + 1;
            String note = marks != null && marks.containsKey(nr) ? "    // <-- " + marks.get(nr) : "";
            out.add(String.format("%" + width + "d | %s%s", nr, lines.get(i), note));
        }
        return String.join("\n", out);
    }
}
