package edu.uta.cse4392.ta3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Small helpers for text and files (UTF-8 everywhere). */
public final class Text {

    private Text() {
    }

    // ---------------------------------------------------------------- files
    /** The file's text with \n line endings (files saved on Windows have \r\n). */
    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file's text, or "" if it does not exist. */
    public static String readOr(Path file, String fallback) {
        return Files.isRegularFile(file) ? read(file) : fallback;
    }

    public static void write(Path file, String text) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void append(Path file, String text) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path mkdirs(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes a file or a whole folder (nothing happens if it does not exist). */
    public static void delete(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies a folder into dest, merging with what is there (like shutil.copytree(dirs_exist_ok=True)). */
    public static void copyTree(Path source, Path dest) {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                Path target = dest.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void copyFile(Path source, Path dest) {
        try {
            Path parent = dest.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Files directly in dir whose names match a glob, sorted by name. */
    public static List<Path> list(Path dir, String glob) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.newDirectoryStream(dir, glob)) {
            stream.forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    // ---------------------------------------------------------------- strings
    /** Lines of a text, like Python's str.splitlines(): no trailing empty line, "" has none. */
    public static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(Arrays.asList(text.split("\r\n|\r|\n", -1)));
        if (out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** All whitespace runs replaced by one space, trimmed (Python's " ".join(text.split())). */
    public static String squash(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /** Whitespace-separated words (Python's str.split()). */
    public static List<String> words(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(t.split("\\s+")));
    }

    /** The last n lines. */
    public static String tail(String text, int n) {
        List<String> all = lines(text);
        return String.join("\n", all.subList(Math.max(0, all.size() - n), all.size()));
    }

    public static String head(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /** Removes the indentation all non-blank lines share (Python's textwrap.dedent). */
    public static String dedent(String text) {
        List<String> all = Arrays.asList(text.split("\n", -1));
        String margin = null;
        for (String line : all) {
            if (line.isBlank()) {
                continue;
            }
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            String indent = line.substring(0, i);
            if (margin == null) {
                margin = indent;
            } else {
                int k = 0;
                while (k < margin.length() && k < indent.length() && margin.charAt(k) == indent.charAt(k)) {
                    k++;
                }
                margin = margin.substring(0, k);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i);
            if (line.isBlank()) {
                line = "";
            } else if (margin != null && !margin.isEmpty()) {
                line = line.substring(margin.length());
            }
            out.append(line);
            if (i < all.size() - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** Strips trailing whitespace (Python's str.rstrip()). */
    public static String rstrip(String text) {
        return text == null ? "" : text.stripTrailing();
    }

    /** Strips the given characters from both ends (Python's str.strip(chars)). */
    public static String strip(String text, String chars) {
        int start = 0;
        int end = text.length();
        while (start < end && chars.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }

    /** "s" when n is not 1. */
    public static String plural(int n) {
        return n == 1 ? "" : "s";
    }

    /** Escapes | for Markdown table cells and keeps the cell on one line. */
    public static String cell(Object value) {
        return squash(String.valueOf(value == null ? "" : value)).replace("|", "\\|");
    }

    /** A number the way Python prints it: 100.0, 97.22, 4 (ints stay ints). */
    public static String num(Object value) {
        if (value instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.format(java.util.Locale.ROOT, "%.1f", d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    /** %g-like seconds: 4 -> "4", 2.5 -> "2.5". */
    public static String seconds(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
