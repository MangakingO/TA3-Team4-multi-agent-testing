package edu.uta.cse4392.ta3.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads compiled classes with the JDK's javap: public API, class structure, branch instructions. Used to check
 * that the refactored Subject class keeps the original interface and to measure how much conditional logic is
 * left in each class.
 */
public final class Javap {

    static final Set<String> BRANCH_OPCODES = Set.of(
            "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle", "if_icmpeq", "if_icmpne", "if_icmplt",
            "if_icmpge", "if_icmpgt", "if_icmple", "if_acmpeq", "if_acmpne", "ifnull", "ifnonnull",
            "tableswitch", "lookupswitch");

    private static final Pattern HEADER = Pattern.compile(
            "^(?<mods>(?:[\\w-]+\\s+)*?)(?<kind>class|interface|enum)\\s+(?<name>[\\w.$]+)(?:<[^{]*?>)?"
                    + "(?:\\s+extends\\s+(?<ext>[^{]+?))?(?:\\s+implements\\s+(?<impl>[^{]+?))?\\s*\\{\\s*$");
    private static final Pattern MEMBER = Pattern.compile("^  \\S.*;\\s*$");
    private static final Pattern OPCODE = Pattern.compile("^\\s+\\d+:\\s+(\\w+)");

    /** javap is not available (it comes with the JDK). */
    public static final class JavapNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JavapNotFound(String message) {
            super(message);
        }
    }

    /** One compiled class. */
    public static final class ClassInfo {
        public final String name;
        public final String kind; // class | interface | enum
        public final List<String> modifiers;
        public final List<String> extendsTypes;
        public final List<String> implementsTypes;
        public final List<String> members = new ArrayList<>();
        public int branchOps;
        public int switchOps;
        public int instanceofOps;

        ClassInfo(String name, String kind, List<String> modifiers, List<String> ext, List<String> impl) {
            this.name = name;
            this.kind = kind;
            this.modifiers = modifiers;
            this.extendsTypes = ext;
            this.implementsTypes = impl;
        }

        public boolean isAbstract() {
            return kind.equals("interface") || modifiers.contains("abstract");
        }

        public List<String> publicMembers() {
            return members.stream().filter(m -> m.startsWith("public ") && !m.contains("synthetic")).sorted().toList();
        }

        public String simpleName() {
            return name.substring(name.lastIndexOf('.') + 1);
        }
    }

    private Javap() {
    }

    public static String findJavap() {
        String exe = Proc.windows() ? "javap.exe" : "javap";
        String found = Proc.which("javap");
        if (found != null) {
            return found;
        }
        String home = System.getenv("JAVA_HOME");
        if (home != null && Files.isRegularFile(Path.of(home, "bin", exe))) {
            return Path.of(home, "bin", exe).toString();
        }
        Path current = Path.of(System.getProperty("java.home"), "bin", exe); // the JDK running the agents
        if (Files.isRegularFile(current)) {
            return current.toString();
        }
        String java = Proc.which("java");
        if (java != null) {
            try {
                Path candidate = Path.of(java).toRealPath().getParent().resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            } catch (IOException ignored) {
                // not found this way
            }
        }
        return null;
    }

    /** Fully qualified names of all classes under a classes folder. */
    public static List<String> classNames(Path classesDir) {
        if (!Files.isDirectory(classesDir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> walk = Files.walk(classesDir)) {
            return walk.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classesDir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .map(rel -> rel.substring(0, rel.length() - ".class".length()).replace('/', '.'))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> splitTypes(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        List<String> clean = new ArrayList<>();
        for (String t : out) {
            clean.add(t.replaceAll("<.*>", "").strip());
        }
        return clean;
    }

    /** All classes in a classes folder (or the named ones): structure, members and instruction counts. */
    public static Map<String, ClassInfo> inspect(Path classesDir, List<String> names) {
        String javap = findJavap();
        if (javap == null) {
            throw new JavapNotFound("javap not found (it ships with the JDK; set JAVA_HOME)");
        }
        List<String> classes = names == null || names.isEmpty() ? classNames(classesDir) : names;
        Map<String, ClassInfo> infos = new LinkedHashMap<>();
        if (classes.isEmpty()) {
            return infos;
        }
        List<String> command = new ArrayList<>(List.of(javap, "-p", "-c", "-constants", "-cp", classesDir.toString()));
        command.addAll(classes);
        Proc.Result r = Proc.run(command, null, 120);
        ClassInfo current = null;
        for (String line : r.out().split("\r?\n")) {
            if (line.startsWith("Compiled from")) {
                current = null;
                continue;
            }
            if (current == null) {
                Matcher m = HEADER.matcher(line.strip());
                if (m.find()) {
                    List<String> mods = new ArrayList<>(List.of(m.group("mods").strip().split("\\s+")));
                    mods.removeIf(String::isEmpty);
                    List<String> ext = splitTypes(m.group("ext"));
                    List<String> impl = splitTypes(m.group("impl"));
                    if (m.group("kind").equals("interface")) {
                        impl = ext;
                        ext = new ArrayList<>();
                    }
                    current = new ClassInfo(m.group("name"), m.group("kind"), mods, ext, impl);
                    infos.put(current.name, current);
                }
                continue;
            }
            if (MEMBER.matcher(line).find()) {
                String member = line.strip();
                while (member.endsWith(";")) {
                    member = member.substring(0, member.length() - 1);
                }
                current.members.add(String.join(" ", member.strip().split("\\s+")));
                continue;
            }
            Matcher op = OPCODE.matcher(line);
            if (op.find()) {
                String opcode = op.group(1);
                if (BRANCH_OPCODES.contains(opcode)) {
                    current.branchOps++;
                    if (opcode.endsWith("switch")) {
                        current.switchOps++;
                    }
                } else if (opcode.equals("instanceof")) {
                    current.instanceofOps++;
                }
            }
        }
        return infos;
    }

    public static Map<String, ClassInfo> inspect(Path classesDir) {
        return inspect(classesDir, null);
    }

    /** The public members of one class, as javap prints them. */
    public static List<String> publicApi(Path classesDir, String fqName) {
        ClassInfo info = inspect(classesDir, List.of(fqName)).get(fqName);
        return info == null ? new ArrayList<>() : new ArrayList<>(info.publicMembers());
    }
}
