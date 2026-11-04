package edu.uta.cse4392.ta3;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt templates live in prompts/*.md. Each file has a "### SYSTEM" and a "### USER" section. Placeholders
 * are $name or ${name}; unknown ones are left as they are, so Java braces and JSON in a template need no escaping.
 */
public final class Prompts {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$(?:(\\$)|([_a-zA-Z][_a-zA-Z0-9]*)|\\{([_a-zA-Z][_a-zA-Z0-9]*)})");

    private Prompts() {
    }

    public static Template load(String name, Path promptsDir) {
        return new Template(promptsDir.resolve(name + ".md"));
    }

    /** A system and a user message with placeholders. */
    public static final class Template {
        public final Path path;
        public final String header;  // version notes above ### SYSTEM, not sent to the model
        public final String system;
        public final String user;

        public Template(Path path) {
            this.path = path;
            String text = Text.read(path);
            int s = text.indexOf("### SYSTEM");
            int u = text.indexOf("### USER", Math.max(s, 0));
            if (s < 0 || u < 0) {
                throw new IllegalArgumentException(path + " needs a '### SYSTEM' and a '### USER' section");
            }
            this.header = text.substring(0, s).strip();
            this.system = text.substring(s + "### SYSTEM".length(), u).strip();
            this.user = text.substring(u + "### USER".length()).strip();
        }

        /** {system, user} with the placeholders filled in. */
        public String[] render(Map<String, ?> values) {
            return new String[] {substitute(system, values), substitute(user, values)};
        }
    }

    /** Like Python's string.Template.safe_substitute. */
    static String substitute(String template, Map<String, ?> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group(1) != null) {
                replacement = "$";
            } else {
                String name = m.group(2) != null ? m.group(2) : m.group(3);
                replacement = values.containsKey(name)
                        ? (values.get(name) == null ? "" : String.valueOf(values.get(name)))
                        : m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
