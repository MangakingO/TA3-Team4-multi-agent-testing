package edu.uta.cse4392.ta3.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs a program with a time limit and collects what it prints (the process tree is killed on timeout). */
public final class Proc {

    /** What a finished (or stopped) program did. exitCode is null when it was stopped. */
    public record Result(List<String> command, Integer exitCode, String out, String err, double seconds,
                         boolean timedOut) {
    }

    private Proc() {
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Runs command in dir with empty standard input; stops it after timeoutSeconds. */
    public static Result run(List<String> command, Path dir, double timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        long t0 = System.nanoTime();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + command.get(0) + ": " + e.getMessage(), e);
        }
        try {
            process.getOutputStream().close(); // empty standard input
        } catch (IOException ignored) {
            // the program may have ended already
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = pump(process.getInputStream(), out);
        Thread errReader = pump(process.getErrorStream(), err);
        boolean finished;
        try {
            finished = process.waitFor((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        join(outReader);
        join(errReader);
        double seconds = (System.nanoTime() - t0) / 1e9;
        return new Result(command, finished ? process.exitValue() : null, decode(out), decode(err), seconds, !finished);
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try (in) {
                while ((n = in.read(buf)) >= 0) {
                    synchronized (sink) {
                        sink.write(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // the stream closes when the process is killed
            }
        }, "proc-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String decode(ByteArrayOutputStream bytes) {
        synchronized (bytes) {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    /** The full path of a program on the PATH (with .cmd/.bat/.exe on Windows), or null. */
    public static String which(String name) {
        List<String> names = new ArrayList<>();
        if (windows() && !name.toLowerCase(Locale.ROOT).matches(".*\\.(cmd|bat|exe)$")) {
            String exts = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            for (String ext : exts.split(";")) {
                if (!ext.isBlank()) {
                    names.add(name + ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        names.add(name);
        if (name.contains("/") || name.contains("\\")) {
            for (String n : names) {
                Path p = Path.of(n);
                if (Files.isRegularFile(p) && (windows() || Files.isExecutable(p))) {
                    return p.toAbsolutePath().toString();
                }
            }
            return null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String n : names) {
                Path p = Path.of(dir, n);
                if (Files.isRegularFile(p) && (windows() || Files.isExecutable(p))) {
                    return p.toString();
                }
            }
        }
        return null;
    }
}
