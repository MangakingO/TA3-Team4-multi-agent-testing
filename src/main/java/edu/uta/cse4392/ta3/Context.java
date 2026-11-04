package edu.uta.cse4392.ta3;

import edu.uta.cse4392.ta3.tools.ChangeLog;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Everything a node needs besides the graph state: settings, the model, the trace and the change log. */
public final class Context {

    public final Settings settings;
    public Llm llm;
    public final Tracer tracer;
    public final ChangeLog changes;
    public final Path runDir;

    private Context(Settings settings, Llm llm, Tracer tracer, ChangeLog changes, Path runDir) {
        this.settings = settings;
        this.llm = llm;
        this.tracer = tracer;
        this.changes = changes;
        this.runDir = runDir;
    }

    public static Context create(Settings settings, Path runDir) {
        return create(settings, runDir, null);
    }

    /** echo prints the progress (null: silent). */
    public static Context create(Settings settings, Path runDir, Consumer<String> echo) {
        Tracer tracer = new Tracer(runDir, echo);
        return new Context(settings, new Llm(settings, tracer), tracer, new ChangeLog(runDir), runDir);
    }

    /** runs/&lt;run&gt;/&lt;phase&gt;, created if needed. */
    public Path phaseDir(String phase) {
        return Text.mkdirs(runDir.resolve(phase));
    }
}
