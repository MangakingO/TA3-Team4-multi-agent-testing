package edu.uta.cse4392.ta3.tools;

import edu.uta.cse4392.ta3.Json;
import edu.uta.cse4392.ta3.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Change log of every modification an agent (or a person) makes to the code or the test cases.
 *
 * <pre>
 * changes.md     readable log with a unified diff per change
 * changes.jsonl  the same records as JSON lines
 * </pre>
 */
public final class ChangeLog {

    private final Path mdPath;
    private final Path jsonlPath;

    public ChangeLog(Path runDir) {
        this.mdPath = runDir.resolve("changes.md");
        this.jsonlPath = runDir.resolve("changes.jsonl");
        if (!Files.exists(mdPath)) {
            Text.write(mdPath, "# Change log\n\nEvery change the agents made to the code or the test cases, "
                    + "with the reason and a unified diff.\n");
        }
    }

    /** One change: phase, agent, kind (code, test case, test suite), target, action, reason, before/after text. */
    public synchronized Map<String, Object> record(String phase, String agent, String kind, String target, String action,
                                                   String reason, String before, String after, Integer round) {
        String diff = UnifiedDiff.diff(before, after, "a/" + target, "b/" + target);
        int number = entries().size() + 1;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("n", number);
        entry.put("time", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)));
        entry.put("phase", phase);
        entry.put("agent", agent);
        entry.put("kind", kind);
        entry.put("target", target);
        entry.put("action", action);
        entry.put("round", round);
        entry.put("reason", reason == null ? "" : reason.strip());
        entry.put("diff", diff);
        Text.append(jsonlPath, Json.line(entry) + "\n");
        StringBuilder md = new StringBuilder("\n## ").append(number).append(". [").append(phase).append("] ")
                .append(agent).append(": ").append(action).append(' ').append(kind).append(' ').append(target);
        if (round != null) {
            md.append(" (round ").append(round).append(')');
        }
        md.append("\n\n*").append(entry.get("time")).append("*\n\n**Reason:** ")
                .append(entry.get("reason").toString().isEmpty() ? "(none given)" : entry.get("reason")).append("\n\n");
        if (!diff.isEmpty()) {
            md.append("```diff\n").append(diff).append("\n```\n\n");
        }
        // the Python version wrote the parts joined by newlines without a final one; keep the file readable
        Text.append(mdPath, md.substring(0, md.length() - 1));
        return entry;
    }

    public List<Map<String, Object>> entries() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(jsonlPath)) {
            return out;
        }
        for (String line : Text.lines(Text.read(jsonlPath))) {
            if (!line.isBlank()) {
                out.add(Json.parseMap(line));
            }
        }
        return out;
    }
}
