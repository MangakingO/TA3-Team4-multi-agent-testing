// Test support code written by the TA3 agent system (template: src/main/resources/templates/TestSupport.java).
// It makes classes with console output, Swing timers, static state and System.exit testable,
// without changing the code under test.
$package_line
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class TestSupport {
    private TestSupport() {
    }

    /** Code that may throw (used by captureOut). */
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ console output

    /** Runs the action with System.out redirected and returns what it printed ("\n" line endings). */
    static String captureOut(Action action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, Charset.defaultCharset());
        System.setOut(capture);
        try {
            action.run();
        } finally {
            capture.flush();
            System.setOut(original);
        }
        return normalize(buffer.toString(Charset.defaultCharset()));
    }

    /** The lines of a printed text, without line terminators (no trailing empty line). */
    static List<String> lines(String text) {
        String t = normalize(text);
        if (t.isEmpty()) {
            return List.of();
        }
        if (t.endsWith("\n")) {
            t = t.substring(0, t.length() - 1);
        }
        return List.of(t.split("\n", -1));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ------------------------------------------------------------------ objects and their timers

    private static final List<Object> TRACKED = new ArrayList<>();

    /** Registers an object created by a test; its timers are stopped after the test. Returns the object. */
    static synchronized <T> T track(T object) {
        TRACKED.add(object);
        return object;
    }

    /** Stops every javax.swing.Timer and java.util.Timer reachable from the tracked objects, then forgets them. */
    static synchronized void cleanUp() {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object o : TRACKED) {
            stopTimers(o, 0, seen);
        }
        TRACKED.clear();
    }

    private static void stopTimers(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 5 || !seen.add(o)) {
            return;
        }
        if (o instanceof javax.swing.Timer) {
            ((javax.swing.Timer) o).stop();
            return;
        }
        if (o instanceof java.util.Timer) {
            ((java.util.Timer) o).cancel();
            return;
        }
        if (o instanceof Object[]) {
            for (Object e : (Object[]) o) {
                stopTimers(e, depth + 1, seen);
            }
            return;
        }
        if (o instanceof Collection) {
            for (Object e : (Collection<?>) o) {
                stopTimers(e, depth + 1, seen);
            }
            return;
        }
        if (o instanceof Map) {
            for (Object e : ((Map<?, ?>) o).values()) {
                stopTimers(e, depth + 1, seen);
            }
            return;
        }
        String name = o.getClass().getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")) {
            return;
        }
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().isPrimitive() || !f.trySetAccessible()) {
                    continue;
                }
                try {
                    stopTimers(f.get(Modifier.isStatic(f.getModifiers()) ? null : o), depth + 1, seen);
                } catch (IllegalAccessException ignored) {
                    // not reachable after trySetAccessible
                }
            }
        }
    }

    // ------------------------------------------------------------------ static fields

    private static final Map<Field, Object> INITIAL = new HashMap<>();
    private static final Set<Class<?>> SNAPSHOT = new HashSet<>();

    /**
     * First call: remembers the static, non-final fields of the classes (and their nested classes).
     * Later calls: puts those fields back to the remembered values, so tests cannot affect each other.
     */
    static synchronized void restoreStatics(Class<?>... classes) throws IllegalAccessException {
        for (Class<?> c : classes) {
            for (Class<?> k : withNested(c)) {
                boolean restore = SNAPSHOT.contains(k);
                for (Field f : k.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (!Modifier.isStatic(m) || Modifier.isFinal(m) || f.isSynthetic() || !f.trySetAccessible()) {
                        continue;
                    }
                    if (!restore) {
                        INITIAL.put(f, f.get(null));
                    } else if (INITIAL.containsKey(f)) {
                        f.set(null, INITIAL.get(f));
                    }
                }
                SNAPSHOT.add(k);
            }
        }
    }

    private static List<Class<?>> withNested(Class<?> c) {
        List<Class<?>> all = new ArrayList<>();
        all.add(c);
        for (Class<?> n : c.getDeclaredClasses()) {
            all.addAll(withNested(n));
        }
        return all;
    }

    // ------------------------------------------------------------------ main()

    /** The result of runMain. */
    static final class MainRun {
        /** The code main passed to System.exit (0 if main returned by itself or was stopped). */
        final int exitCode;
        /** True if main had not finished after the time limit and was interrupted. */
        final boolean stillRunning;
        /** What the program printed to System.out ("\n" line endings). */
        final String out;
        /** What the program printed to System.err. */
        final String err;

        MainRun(int exitCode, boolean stillRunning, String out, String err) {
            this.exitCode = exitCode;
            this.stillRunning = stillRunning;
            this.out = out;
            this.err = err;
        }

        List<String> lines() {
            return TestSupport.lines(out);
        }

        @Override
        public String toString() {
            return "MainRun[exitCode=" + exitCode + ", stillRunning=" + stillRunning + ", out=" + out
                    + (err.isEmpty() ? "" : ", err=" + err) + "]";
        }
    }

    /**
     * Runs mainClass.main(args) in a separate JVM, so System.exit and endless waits cannot stop the tests.
     * If main has not finished after {@code millis}, it is interrupted (stillRunning = true) and the JVM ends.
     * The child JVM gets the same JaCoCo agent as the test JVM, so the code it runs counts for coverage.
     */
    static MainRun runMain(Class<?> mainClass, long millis, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-javaagent:") && a.toLowerCase(Locale.ROOT).contains("jacoco")) {
                cmd.add(a);
            }
        }
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(location(TestSupport.class) + File.pathSeparator + location(mainClass));
        cmd.add(Launcher.class.getName());
        cmd.add(mainClass.getName());
        cmd.add(Long.toString(millis));
        cmd.addAll(Arrays.asList(args));
        File out = File.createTempFile("main-out", ".txt");
        File err = File.createTempFile("main-err", ".txt");
        try {
            Process p = new ProcessBuilder(cmd).redirectOutput(out).redirectError(err).start();
            p.getOutputStream().close(); // no console input
            if (!p.waitFor(millis + 30_000, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new AssertionError("the program did not stop: " + String.join(" ", args));
            }
            String o = normalize(new String(Files.readAllBytes(out.toPath()), Charset.defaultCharset()));
            String e = normalize(new String(Files.readAllBytes(err.toPath()), Charset.defaultCharset()));
            boolean stopped = e.contains(Launcher.STOPPED);
            e = e.replace(Launcher.STOPPED + "\n", "").replace(Launcher.STOPPED, "");
            return new MainRun(p.exitValue(), stopped, o, e);
        } finally {
            out.delete();
            err.delete();
        }
    }

    private static String location(Class<?> c) throws URISyntaxException {
        return Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** Started in the child JVM: runs the target main and interrupts it when the time is up. */
    static final class Launcher {
        static final String STOPPED = "[TestSupport] main was still running and was interrupted";

        public static void main(String[] a) {
            int code = 0;
            try {
                Class<?> target = Class.forName(a[0]);
                long millis = Long.parseLong(a[1]);
                String[] args = Arrays.copyOfRange(a, 2, a.length);
                Thread mainThread = Thread.currentThread();
                Thread watchdog = new Thread(() -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        return;
                    }
                    System.err.println(STOPPED);
                    mainThread.interrupt();
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                        // exiting anyway
                    }
                    System.exit(0); // main ignored the interrupt
                });
                watchdog.setDaemon(true);
                watchdog.start();
                Method m = target.getDeclaredMethod("main", String[].class);
                m.setAccessible(true);
                m.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                e.getCause().printStackTrace();
                code = 1;
            } catch (Exception e) {
                e.printStackTrace();
                code = 1;
            }
            System.out.flush();
            System.exit(code);
        }
    }
}
