package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/** Maven project folders for a run: runs/&lt;run&gt;/&lt;phase&gt;/project with the standard layout. */
public final class Workspace {

    private Workspace() {
    }

    public static Path createProject(Path dest, Path pomTemplate, String mainClass) {
        Text.mkdirs(dest.resolve("src/main/java"));
        Text.mkdirs(dest.resolve("src/test/java"));
        Text.write(dest.resolve("pom.xml"), withMainClass(Text.read(pomTemplate), mainClass));
        return dest;
    }

    /** The pom with &lt;exec.mainClass&gt; set to the class under test. */
    public static String withMainClass(String pom, String mainClass) {
        return pom.replaceAll("<exec\\.mainClass>.*?</exec\\.mainClass>",
                Matcher.quoteReplacement("<exec.mainClass>" + mainClass + "</exec.mainClass>"));
    }

    public static Path mainDir(Path project) {
        return project.resolve("src").resolve("main").resolve("java");
    }

    public static Path testDir(Path project) {
        return project.resolve("src").resolve("test").resolve("java");
    }

    /** Writes {file name: content} into src/main/java/&lt;package dirs&gt;/ (replace clears old sources first). */
    public static List<Path> writeSources(Path project, String pkg, Map<String, String> files, boolean replace) {
        Path root = mainDir(project);
        if (replace) {
            Text.delete(root);
        }
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String safe = Path.of(e.getKey()).getFileName().toString(); // never write outside the source folder
            if (!safe.endsWith(".java")) {
                continue;
            }
            Path path = root.resolve(JavaSrc.sourceRelpath(pkg, safe).toString());
            Text.write(path, Text.rstrip(e.getValue()) + "\n");
            written.add(path);
        }
        return written;
    }

    /** {file name: content} for every .java file under src/main/java, sorted by path. */
    public static Map<String, String> readSources(Path project) {
        Map<String, String> out = new LinkedHashMap<>();
        Path root = mainDir(project);
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .sorted((a, b) -> root.relativize(a).toString().replace('\\', '/')
                            .compareTo(root.relativize(b).toString().replace('\\', '/')))
                    .toList()) {
                out.put(p.getFileName().toString(), Text.read(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** TestSupport.java for the package of the class under test. */
    public static String testSupportSource(String pkg) {
        try (InputStream in = Workspace.class.getResourceAsStream("/templates/TestSupport.java")) {
            if (in == null) {
                throw new IllegalStateException("templates/TestSupport.java is missing from the classpath");
            }
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("$package_line", pkg == null || pkg.isEmpty() ? "" : "package " + pkg + ";\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes &lt;Class&gt;Test.java and the TestSupport.java helper it uses. */
    public static Path writeTestClass(Path project, String pkg, String className, String source) {
        Path path = testDir(project).resolve(JavaSrc.sourceRelpath(pkg, className + "Test.java").toString());
        Text.write(path, source);
        Text.write(path.resolveSibling("TestSupport.java"), testSupportSource(pkg));
        return path;
    }
}
