package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls Java files out of the refactoring agent's reply (one fenced ```java block per file). */
public final class RefactorParse {

    private static final Pattern FENCE = Pattern.compile("```[ \\t]*([\\w+-]*)[ \\t]*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("([A-Za-z_][\\w$]*\\.java)");
    private static final Pattern FIRST_LINE = Pattern.compile(
            "^\\s*//\\s*(?:File(?:name)?\\s*:\\s*)?([A-Za-z_][\\w$]*\\.java)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+\\w+");

    private RefactorParse() {
    }

    /** {file name: content} for every Java file in the reply. */
    public static Map<String, String> parseFiles(String reply) {
        Map<String, String> files = new LinkedHashMap<>();
        Matcher m = FENCE.matcher(reply);
        while (m.find()) {
            String lang = m.group(1).toLowerCase(Locale.ROOT);
            String code = m.group(2);
            if (!lang.isEmpty() && !lang.equals("java")) {
                continue;
            }
            List<String> lines = new ArrayList<>(Text.lines(Text.strip(code, "\n")));
            String name = null;
            if (!lines.isEmpty()) {
                Matcher first = FIRST_LINE.matcher(lines.get(0));
                if (first.find()) {
                    name = first.group(1);
                    lines.remove(0);
                }
            }
            if (name == null) {
                List<String> before = Text.lines(Text.rstrip(reply.substring(0, m.start())));
                List<String> lastThree = before.subList(Math.max(0, before.size() - 3), before.size());
                for (int i = lastThree.size() - 1; i >= 0; i--) {
                    String prev = lastThree.get(i);
                    if (!prev.isBlank()) {
                        Matcher found = NAME.matcher(prev);
                        String last = null;
                        while (found.find()) {
                            last = found.group(1);
                        }
                        if (last != null) {
                            name = last;
                        }
                        break;
                    }
                }
            }
            String body = Text.strip(String.join("\n", lines), "\n");
            if (!TYPE.matcher(body).find()) {
                continue;
            }
            String declared = JavaSrc.primaryType(body);
            if (!declared.isEmpty() && (name == null || !name.substring(0, name.length() - 5).equals(declared))) {
                name = declared + ".java"; // the file name must match the public type
            }
            if (name != null) {
                files.put(name, body + "\n");
            }
        }
        return files;
    }

    /** The reply without its code blocks: the design table and notes. */
    public static String designNotes(String reply) {
        String text = FENCE.matcher(reply).replaceAll("");
        text = text.replaceAll("\n{3,}", "\n\n").strip();
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }
}
