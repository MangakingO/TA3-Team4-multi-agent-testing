package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.util.ArrayList;
import java.util.List;

/** Unified diff of two texts, line by line, with 3 lines of context (the format of `diff -u` and git). */
public final class UnifiedDiff {

    private record Op(String tag, int i1, int i2, int j1, int j2) {
    }

    private UnifiedDiff() {
    }

    public static String diff(String before, String after, String fromFile, String toFile) {
        List<String> a = Text.lines(before == null ? "" : before);
        List<String> b = Text.lines(after == null ? "" : after);
        List<String> out = new ArrayList<>();
        boolean started = false;
        for (List<Op> group : groups(opcodes(a, b), 3)) {
            if (!started) {
                started = true;
                out.add("--- " + fromFile);
                out.add("+++ " + toFile);
            }
            Op first = group.get(0);
            Op last = group.get(group.size() - 1);
            out.add("@@ -" + range(first.i1, last.i2) + " +" + range(first.j1, last.j2) + " @@");
            for (Op op : group) {
                if (op.tag.equals("equal")) {
                    for (String line : a.subList(op.i1, op.i2)) {
                        out.add(" " + line);
                    }
                    continue;
                }
                if (op.tag.equals("replace") || op.tag.equals("delete")) {
                    for (String line : a.subList(op.i1, op.i2)) {
                        out.add("-" + line);
                    }
                }
                if (op.tag.equals("replace") || op.tag.equals("insert")) {
                    for (String line : b.subList(op.j1, op.j2)) {
                        out.add("+" + line);
                    }
                }
            }
        }
        return String.join("\n", out);
    }

    private static String range(int start, int stop) {
        int beginning = start + 1;
        int length = stop - start;
        if (length == 1) {
            return String.valueOf(beginning);
        }
        if (length == 0) {
            beginning -= 1;
        }
        return beginning + "," + length;
    }

    /** Edit operations from a longest common subsequence of the lines. */
    private static List<Op> opcodes(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        int n = a.size() - prefix - suffix;
        int m = b.size() - prefix - suffix;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(prefix + i).equals(b.get(prefix + j)) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // per line: 'e' equal, 'd' delete, 'i' insert
        List<Character> steps = new ArrayList<>();
        for (int k = 0; k < prefix; k++) {
            steps.add('e');
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(prefix + i).equals(b.get(prefix + j))) {
                steps.add('e');
                i++;
                j++;
            } else if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) {
                steps.add('d');
                i++;
            } else {
                steps.add('i');
                j++;
            }
        }
        for (int k = 0; k < suffix; k++) {
            steps.add('e');
        }
        List<Op> ops = new ArrayList<>();
        int ai = 0;
        int bj = 0;
        int k = 0;
        while (k < steps.size()) {
            int i1 = ai;
            int j1 = bj;
            if (steps.get(k) == 'e') {
                while (k < steps.size() && steps.get(k) == 'e') {
                    ai++;
                    bj++;
                    k++;
                }
                ops.add(new Op("equal", i1, ai, j1, bj));
            } else {
                while (k < steps.size() && steps.get(k) != 'e') {
                    if (steps.get(k) == 'd') {
                        ai++;
                    } else {
                        bj++;
                    }
                    k++;
                }
                String tag = ai > i1 && bj > j1 ? "replace" : ai > i1 ? "delete" : "insert";
                ops.add(new Op(tag, i1, ai, j1, bj));
            }
        }
        return ops;
    }

    /** Hunks: changes with n lines of context, split where n+n unchanged lines separate them (difflib). */
    private static List<List<Op>> groups(List<Op> codes, int n) {
        List<Op> ops = new ArrayList<>(codes);
        if (ops.isEmpty()) {
            ops.add(new Op("equal", 0, 1, 0, 1));
        }
        Op head = ops.get(0);
        if (head.tag.equals("equal")) {
            ops.set(0, new Op("equal", Math.max(head.i1, head.i2 - n), head.i2, Math.max(head.j1, head.j2 - n), head.j2));
        }
        Op tail = ops.get(ops.size() - 1);
        if (tail.tag.equals("equal")) {
            ops.set(ops.size() - 1, new Op("equal", tail.i1, Math.min(tail.i2, tail.i1 + n), tail.j1,
                    Math.min(tail.j2, tail.j1 + n)));
        }
        List<List<Op>> out = new ArrayList<>();
        List<Op> group = new ArrayList<>();
        for (Op op : ops) {
            int i1 = op.i1;
            int j1 = op.j1;
            if (op.tag.equals("equal") && op.i2 - op.i1 > n + n) {
                group.add(new Op("equal", i1, Math.min(op.i2, i1 + n), j1, Math.min(op.j2, j1 + n)));
                out.add(group);
                group = new ArrayList<>();
                i1 = Math.max(i1, op.i2 - n);
                j1 = Math.max(j1, op.j2 - n);
            }
            group.add(new Op(op.tag, i1, op.i2, j1, op.j2));
        }
        if (!group.isEmpty() && !(group.size() == 1 && group.get(0).tag.equals("equal"))) {
            out.add(group);
        }
        // a first group that is only context (when the first change is far down) carries no change
        out.removeIf(g -> g.stream().allMatch(op -> op.tag.equals("equal")));
        return out;
    }
}
