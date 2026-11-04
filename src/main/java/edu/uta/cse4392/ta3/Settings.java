package edu.uta.cse4392.ta3;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Settings for the agent system, read from environment variables and an optional .env file. */
public final class Settings {

    /** The project folder: the one with prompts/ (and season-switch/). */
    public static final Path ROOT = findRoot();

    // Language model. "ollama" runs locally; "manual" hands every prompt to a person, who pastes it
    // into any web LLM and saves the reply (the run pauses until the reply is there).
    public String llmProvider = "ollama";
    public String llmModel = "qwen2.5-coder:7b";
    public String refactorModel = "";   // optional larger model just for the refactoring agent
    public String manualModel = "";     // with LLM_PROVIDER=manual: which LLM the person relays (for the records)
    public String ollamaBaseUrl = "http://localhost:11434";
    public double temperature = 0.0;
    public int numCtx = 32768;
    public int numPredict = 16384;
    public double llmTimeout = 3600.0;

    // Java build of the code under test
    public String mvn = "mvn";
    public List<String> mavenArgs = new ArrayList<>();
    public int mavenTimeout = 900;
    public Path pomTemplate = ROOT.resolve("season-switch").resolve("pom.xml");
    public Path deliveryPom = ROOT.resolve("season-switch").resolve("pom.xml"); // pom.xml copied into final/
    public int testTimeoutSeconds = 10;

    // Observing the program (setup): one run per argument list, each stopped after observeSeconds
    public List<List<String>> observeArgs = new ArrayList<>(List.of(new ArrayList<>()));
    public double observeSeconds = 4.0;

    // Where runs are written and where the prompts are
    public Path runsDir = ROOT.resolve("runs");
    public Path promptsDir = ROOT.resolve("prompts");
    public Path refactorPrompt = ROOT.resolve("prompts").resolve("refactor_state_pattern.md");

    // Loop limits (a person is asked what to do when one is reached)
    public int maxRepairRounds = 6;
    public int maxCoverageRounds = 4;
    public int maxRefactorAttempts = 3;
    public double targetBranchCoverage = 100.0;

    public static Settings fromEnv() {
        return fromEnv(environment());
    }

    public static Settings fromEnv(Map<String, String> env) {
        Settings s = new Settings();
        s.llmProvider = get(env, "LLM_PROVIDER", s.llmProvider).toLowerCase();
        s.llmModel = get(env, "LLM_MODEL", s.llmModel);
        s.refactorModel = get(env, "REFACTOR_MODEL", s.refactorModel);
        s.manualModel = get(env, "MANUAL_MODEL", s.manualModel);
        s.ollamaBaseUrl = get(env, "OLLAMA_BASE_URL", s.ollamaBaseUrl);
        s.temperature = Double.parseDouble(get(env, "LLM_TEMPERATURE", String.valueOf(s.temperature)));
        s.numCtx = Integer.parseInt(get(env, "LLM_NUM_CTX", String.valueOf(s.numCtx)));
        s.numPredict = Integer.parseInt(get(env, "LLM_NUM_PREDICT", String.valueOf(s.numPredict)));
        s.llmTimeout = Double.parseDouble(get(env, "LLM_TIMEOUT", String.valueOf(s.llmTimeout)));
        s.mvn = get(env, "MVN", s.mvn);
        if (s.mvn.isBlank()) {
            s.mvn = "mvn";
        }
        s.mavenArgs = splitArgs(get(env, "MAVEN_ARGS", ""));
        s.mavenTimeout = Integer.parseInt(get(env, "MAVEN_TIMEOUT", String.valueOf(s.mavenTimeout)));
        s.pomTemplate = path(get(env, "POM_TEMPLATE", ""), s.pomTemplate);
        s.deliveryPom = path(get(env, "DELIVERY_POM", ""), s.deliveryPom);
        s.testTimeoutSeconds = Integer.parseInt(get(env, "TEST_TIMEOUT_SECONDS", String.valueOf(s.testTimeoutSeconds)));
        if (env.containsKey("OBSERVE_ARGS")) { // e.g. ";cool;heat;off;warm" (an empty item = no arguments)
            s.observeArgs = new ArrayList<>();
            for (String item : env.get("OBSERVE_ARGS").split(";", -1)) {
                s.observeArgs.add(splitArgs(item));
            }
        }
        s.observeSeconds = Double.parseDouble(get(env, "OBSERVE_SECONDS", String.valueOf(s.observeSeconds)));
        s.runsDir = path(get(env, "RUNS_DIR", ""), s.runsDir);
        s.promptsDir = path(get(env, "PROMPTS_DIR", ""), s.promptsDir);
        s.refactorPrompt = path(get(env, "REFACTOR_PROMPT", ""), s.promptsDir.resolve("refactor_state_pattern.md"));
        s.maxRepairRounds = Integer.parseInt(get(env, "MAX_REPAIR_ROUNDS", String.valueOf(s.maxRepairRounds)));
        s.maxCoverageRounds = Integer.parseInt(get(env, "MAX_COVERAGE_ROUNDS", String.valueOf(s.maxCoverageRounds)));
        s.maxRefactorAttempts = Integer.parseInt(get(env, "MAX_REFACTOR_ATTEMPTS", String.valueOf(s.maxRefactorAttempts)));
        s.targetBranchCoverage = Double.parseDouble(get(env, "TARGET_BRANCH_COVERAGE",
                String.valueOf(s.targetBranchCoverage)));
        return s;
    }

    /** The model a role uses (the refactoring agent may use a bigger one). */
    public String modelFor(String role) {
        if (llmProvider.equals("manual")) {
            return manualModel.isBlank() ? "web LLM" : manualModel;
        }
        if (role.startsWith("refactor") && !refactorModel.isBlank()) {
            return refactorModel;
        }
        return llmModel;
    }

    public String describe() {
        String model = !llmProvider.equals("manual") ? llmModel
                : manualModel.isBlank() ? "a person relaying a web LLM" : manualModel + " (replies relayed by hand)";
        String extra = refactorModel.isBlank() ? "" : ", refactoring with " + refactorModel;
        return llmProvider + ": " + model + extra;
    }

    // ---------------------------------------------------------------- environment
    /** The process environment over the values in ROOT/.env (a real environment variable wins). */
    public static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(dotenv(ROOT.resolve(".env")));
        env.putAll(System.getenv());
        return env;
    }

    static Map<String, String> dotenv(Path file) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        for (String raw : Text.lines(Text.read(file))) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('=')).strip();
            String value = line.substring(line.indexOf('=') + 1);
            for (String marker : new String[] {" #", "\t#"}) { // inline comment
                int i = value.indexOf(marker);
                if (i >= 0) {
                    value = value.substring(0, i);
                }
            }
            value = Text.strip(value.strip(), "\"'");
            out.putIfAbsent(key, value);
        }
        return out;
    }

    private static String get(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null ? fallback : value.strip();
    }

    private static Path path(String value, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.startsWith("~") ? System.getProperty("user.home") + value.substring(1) : value;
        Path p = Path.of(v);
        return p.isAbsolute() ? p : ROOT.resolve(p).normalize();
    }

    /** Splits command-line arguments: spaces separate, quotes group, backslashes are kept (Windows paths). */
    public static List<String> splitArgs(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (char c : (text == null ? "" : text).toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }

    private static Path findRoot() {
        String home = System.getProperty("ta3.home", System.getenv("TA3_HOME"));
        if (home != null && !home.isBlank()) {
            return Path.of(home).toAbsolutePath().normalize();
        }
        // the code's location: <root>/target/classes or <root>/target/ta3-agents.jar
        try {
            Path code = Path.of(Settings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path p = code; p != null; p = p.getParent()) {
                if (Files.isDirectory(p.resolve("prompts")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                    return p;
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // fall back to the working directory
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("prompts"))) {
                return p;
            }
        }
        return cwd;
    }
}
