package edu.uta.cse4392.ta3;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Execution trace of a run: trace.log (readable), trace.jsonl (one JSON event per line), and llm/ (every
 * prompt sent to the model and every reply, one file each).
 */
public final class Tracer {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final Path runDir;
    private final Path logPath;
    private final Path jsonlPath;
    private final Path llmDir;
    private final Consumer<String> echo;
    private final Set<String> paused = Collections.synchronizedSet(new HashSet<>());
    private int llmSeq;

    public Tracer(Path runDir, Consumer<String> echo) {
        this.runDir = Text.mkdirs(runDir);
        this.logPath = runDir.resolve("trace.log");
        this.jsonlPath = runDir.resolve("trace.jsonl");
        this.llmDir = Text.mkdirs(runDir.resolve("llm"));
        this.echo = echo;
        this.llmSeq = Text.list(llmDir, "*.prompt.md").size();
    }

    public Path runDir() {
        return runDir;
    }

    public Path llmDir() {
        return llmDir;
    }

    // ---------------------------------------------------------------- events
    public void event(String node, String kind, String message) {
        event(node, kind, message, null);
    }

    public void event(String node, String kind, String message, Map<String, ?> data) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", ISO_TIME.format(now));
        record.put("node", node);
        record.put("kind", kind);
        record.put("thread", Thread.currentThread().getName());
        record.put("message", message == null ? "" : message);
        if (data != null && !data.isEmpty()) {
            record.put("data", data);
        }
        StringBuilder line = new StringBuilder(LOG_TIME.format(now)).append(" [").append(node).append("] ")
                .append(kind).append(": ").append(message == null ? "" : message);
        if (data != null && !data.isEmpty()) {
            String compact = Json.line(data);
            line.append("  ").append(compact.length() <= 600 ? compact : compact.substring(0, 600) + "...");
        }
        synchronized (this) {
            Text.append(logPath, line + "\n");
            Text.append(jsonlPath, Json.line(record) + "\n");
        }
        if (echo == null) {
            return;
        }
        switch (kind) {
            case "decision", "result", "warning" -> echo.accept("  [" + node + "] " + message);
            case "node_start" -> echo.accept(paused.remove(node) ? "> " + node + " (resumed)" : "> " + node);
            case "node_paused" -> paused.add(node);
            case "llm_call" -> echo.accept("  [" + node + "] waiting for the model (" + message.split(" <- ")[0] + ")");
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- LLM
    /** The next prompt and reply files: llm/NNN-role.prompt.md and llm/NNN-role.response.md. */
    public synchronized Path[] nextLlmFiles(String role) {
        llmSeq++;
        String stem = String.format("%03d-%s", llmSeq, role);
        return new Path[] {llmDir.resolve(stem + ".prompt.md"), llmDir.resolve(stem + ".response.md")};
    }

    public static void writePrompt(Path path, String role, String model, String system, String user) {
        Text.write(path, "<!-- role: " + role + " | model: " + model + " -->\n\n# System\n\n" + system.strip()
                + "\n\n# User\n\n" + user.strip() + "\n");
    }
}
