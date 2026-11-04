package edu.uta.cse4392.ta3.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pattern checker: does the refactored code apply the State pattern, and keep the Subject's interface? Works on
 * compiled classes (javap), so it judges what the code really is, not what comments say. The team still reviews
 * the code (human in the loop); these checks tell them where to look. "Subject family" = the Subject class plus
 * its nested classes (for SeasonSwitch: AC, Heater, Room), because state-dependent code can hide in a nested
 * class too.
 */
public final class PatternCheck {

    private static final Pattern STATE_WORD = Pattern.compile("(?i)state");

    private PatternCheck() {
    }

    private static List<Javap.ClassInfo> family(Map<String, Javap.ClassInfo> infos, String fq, Set<String> exclude) {
        List<Javap.ClassInfo> out = new ArrayList<>();
        infos.forEach((n, i) -> {
            if ((n.equals(fq) || n.startsWith(fq + "$")) && !exclude.contains(n)) {
                out.add(i);
            }
        });
        return out;
    }

    private static String memberName(String member) {
        String head = member.contains("(") ? member.substring(0, member.indexOf('(')) : member.split(" = ")[0];
        String[] words = head.strip().split("\\s+");
        return words[words.length - 1];
    }

    /** Non-private, non-synthetic members, simplified: 'AC ac', 'public void on()'. */
    private static Set<String> keptMembers(Javap.ClassInfo info, String pkg) {
        Set<String> out = new LinkedHashSet<>();
        for (String m : info.members) {
            if (m.startsWith("private ") || memberName(m).contains("$")) {
                continue;
            }
            if (!m.contains("(") && STATE_WORD.matcher(memberName(m)).find()) {
                continue; // the state field is the one thing the refactoring must replace
            }
            out.add(Testability.simplify(m.split(" = ")[0], pkg));
        }
        return out;
    }

    private static String kind(Javap.ClassInfo info) {
        return info.kind.equals("class") && info.isAbstract() ? "abstract class" : info.kind;
    }

    private static List<Map<String, Object>> classes(Map<String, Javap.ClassInfo> infos) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Javap.ClassInfo i : infos.values()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", i.name);
            c.put("kind", kind(i));
            c.put("extends", new ArrayList<>(i.extendsTypes));
            c.put("implements", new ArrayList<>(i.implementsTypes));
            c.put("branch_ops", i.branchOps);
            c.put("public_members", new ArrayList<>(i.publicMembers()));
            out.add(c);
        }
        return out;
    }

    public static Map<String, Object> check(Path originalClasses, String originalFqcn, Path refactoredClasses,
                                            String subjectFqcn, String testCode) {
        List<Map<String, Object>> checks = new ArrayList<>();
        String pkg = originalFqcn.contains(".") ? originalFqcn.substring(0, originalFqcn.lastIndexOf('.')) : "";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checks", checks);

        Map<String, Javap.ClassInfo> before;
        Map<String, Javap.ClassInfo> after;
        try {
            before = Javap.inspect(originalClasses);
            after = Javap.inspect(refactoredClasses);
        } catch (RuntimeException e) {
            add(checks, "javap", "Structure checks", null, "skipped: " + e.getMessage());
            report.put("passed", null);
            report.put("classes", new ArrayList<>());
            return report;
        }

        Javap.ClassInfo original = before.get(originalFqcn);
        Javap.ClassInfo subject = after.get(subjectFqcn);
        add(checks, "subject", "Subject class keeps the original name", subject != null && subject.kind.equals("class"),
                subject != null ? subjectFqcn : "no class " + subjectFqcn + " in the refactored code");
        if (subject == null || original == null) {
            report.put("passed", false);
            report.put("classes", classes(after));
            return report;
        }

        List<String> missing = original.publicMembers().stream().filter(m -> !subject.publicMembers().contains(m)).toList();
        List<String> added = subject.publicMembers().stream().filter(m -> !original.publicMembers().contains(m)).toList();
        add(checks, "interface", "Subject keeps exactly the original public interface", missing.isEmpty() && added.isEmpty(),
                (missing.isEmpty() ? "all original public members present" : "missing: " + String.join("; ", missing))
                        + (added.isEmpty() ? "" : "; added: " + String.join("; ", added)));

        // State type: an interface/abstract class with 2+ implementations
        Map<String, Javap.ClassInfo> others = new LinkedHashMap<>(after);
        others.remove(subjectFqcn);
        Map<String, List<String>> children = new LinkedHashMap<>();
        others.forEach((name, info) -> {
            List<String> parents = new ArrayList<>(info.extendsTypes);
            parents.addAll(info.implementsTypes);
            for (String parent : parents) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(name);
            }
        });
        List<String> candidates = new ArrayList<>();
        others.forEach((n, i) -> {
            if (i.isAbstract() && children.getOrDefault(n, List.of()).size() >= 2) {
                candidates.add(n);
            }
        });
        candidates.sort((a, b) -> Integer.compare(children.get(b).size(), children.get(a).size()));
        String stateType = candidates.isEmpty() ? null : candidates.get(0);
        add(checks, "state_type", "An abstract State type (interface or abstract class)", stateType != null,
                stateType != null ? stateType + " (" + kind(after.get(stateType)) + ")"
                        : "no interface/abstract class with 2+ subclasses");
        List<String> concrete = new ArrayList<>();
        if (stateType != null) {
            for (String n : children.getOrDefault(stateType, List.of())) {
                if (!after.get(n).isAbstract()) {
                    concrete.add(n);
                }
            }
        }
        TreeSet<String> concreteNames = new TreeSet<>();
        for (String n : concrete) {
            concreteNames.add(after.get(n).simpleName());
        }
        add(checks, "concrete_states", "One concrete class per state", concrete.size() >= 2,
                concreteNames.isEmpty() ? "none" : String.join(", ", concreteNames));

        // members the tests use (package-private fields and nested classes) must still be there
        Set<String> stateClasses = new LinkedHashSet<>(concrete);
        if (stateType != null) {
            stateClasses.add(stateType);
        }
        List<Javap.ClassInfo> oldFamily = family(before, originalFqcn, Set.of());
        Set<String> names = testCode == null ? null : Dedup.identifiers(testCode);
        Map<String, Javap.ClassInfo> newBySimple = new LinkedHashMap<>();
        for (Javap.ClassInfo i : after.values()) {
            String simple = i.simpleName();
            newBySimple.put(simple.substring(simple.lastIndexOf('$') + 1), i);
        }
        List<String> lost = new ArrayList<>();
        for (Javap.ClassInfo info : oldFamily) {
            String simple = info.simpleName().substring(info.simpleName().lastIndexOf('$') + 1);
            Javap.ClassInfo twin = info.name.equals(originalFqcn) ? subject : newBySimple.get(simple);
            if (twin == null) {
                lost.add("class " + simple);
                continue;
            }
            TreeSet<String> gone = new TreeSet<>(keptMembers(info, pkg));
            gone.removeAll(keptMembers(twin, pkg));
            if (names != null) {
                gone.removeIf(m -> !names.contains(memberName(m)));
            }
            for (String m : gone) {
                lost.add(simple + ": " + m);
            }
        }
        add(checks, "members", "Members the tests use are still there (non-private fields, methods, nested classes)",
                lost.isEmpty(), lost.isEmpty() ? "all present" : "missing: " + String.join("; ", lost));

        List<Javap.ClassInfo> newFamily = family(after, subjectFqcn, stateClasses);
        List<String> typed = new ArrayList<>();
        if (stateType != null) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(stateType) + "\\b");
            for (String m : subject.members) {
                if (!m.contains("(") && p.matcher(m).find()) {
                    typed.add(m);
                }
            }
        }
        List<String> holds = typed.stream().filter(m -> !(" " + m + " ").contains(" static ")).toList();
        if (holds.isEmpty()) {
            holds = typed;
        }
        add(checks, "delegation", "Subject holds a reference to its current State", !holds.isEmpty(),
                holds.isEmpty() ? "no field of type " + stateType
                        : String.join("; ", holds.stream().map(h -> Testability.simplify(h, pkg)).toList()));

        int instanceofs = newFamily.stream().mapToInt(i -> i.instanceofOps).sum();
        String familyNames = String.join(", ", newFamily.stream().map(Javap.ClassInfo::simpleName).toList());
        add(checks, "no_type_tests", "No instanceof tests on states in the Subject family", instanceofs == 0,
                instanceofs + " instanceof instruction(s) in " + familyNames);

        int oldSwitch = oldFamily.stream().mapToInt(i -> i.switchOps).sum();
        int oldBranch = oldFamily.stream().mapToInt(i -> i.branchOps).sum();
        int newSwitch = newFamily.stream().mapToInt(i -> i.switchOps).sum();
        int newBranch = newFamily.stream().mapToInt(i -> i.branchOps).sum();
        int stateBranches = concrete.stream().mapToInt(n -> after.get(n).branchOps).sum();
        add(checks, "conditionals", "No switch on the state left in the Subject family",
                newSwitch == 0 && newBranch < oldBranch,
                "switch statements " + oldSwitch + " -> " + newSwitch + "; conditional branch instructions " + oldBranch
                        + " -> " + newBranch + " in " + familyNames + "; all concrete states together " + stateBranches);

        List<String> deciders = new ArrayList<>();
        for (String n : concrete) {
            if (after.get(n).members.stream().anyMatch(m -> m.contains(subjectFqcn))) {
                deciders.add(after.get(n).simpleName());
            }
        }
        add(checks, "transitions", "Who decides the next state", null, deciders.isEmpty()
                ? "the Subject (states do not reference the Subject)"
                : "the State classes (" + String.join(", ", deciders) + ") receive the Subject and set its next state");

        boolean passed = checks.stream().filter(c -> !"INFO".equals(c.get("result")))
                .allMatch(c -> "PASS".equals(c.get("result")));
        report.put("passed", passed);
        report.put("state_type", stateType);
        report.put("concrete_states", new ArrayList<>(concreteNames));
        report.put("classes", classes(after));
        report.put("original_branch_ops", oldBranch);
        report.put("subject_branch_ops", newBranch);
        report.put("original_switch_ops", oldSwitch);
        report.put("subject_switch_ops", newSwitch);
        return report;
    }

    private static void add(List<Map<String, Object>> checks, String key, String title, Boolean ok, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", key);
        c.put("check", title);
        c.put("result", ok == null ? "INFO" : ok ? "PASS" : "FAIL");
        c.put("detail", detail);
        checks.add(c);
    }

    @SuppressWarnings("unchecked")
    public static String toMarkdown(Map<String, Object> report) {
        List<String> out = new ArrayList<>(List.of("| Check | Result | Detail |", "|---|---|---|"));
        Object checks = report == null ? null : report.get("checks");
        if (checks instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> c = (Map<String, Object>) o;
                out.add("| " + c.get("check") + " | " + c.get("result") + " | "
                        + String.valueOf(c.get("detail")).replace("|", "/") + " |");
            }
        }
        Object classes = report == null ? null : report.get("classes");
        if (classes instanceof List<?> list && !list.isEmpty()) {
            out.addAll(List.of("", "| Class | Kind | Extends / implements | Branch instructions |", "|---|---|---|---|"));
            for (Object o : list) {
                Map<String, Object> c = (Map<String, Object>) o;
                List<Object> parents = new ArrayList<>((List<Object>) c.get("extends"));
                parents.addAll((List<Object>) c.get("implements"));
                out.add("| " + c.get("name") + " | " + c.get("kind") + " | "
                        + (parents.isEmpty() ? "-" : String.join(", ", parents.stream().map(String::valueOf).toList()))
                        + " | " + c.get("branch_ops") + " |");
            }
        }
        return String.join("\n", out) + "\n";
    }
}
