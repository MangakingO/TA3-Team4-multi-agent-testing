package edu.uta.cse4392.ta3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language model behind the LLM agents (LangChain4j). Every agent calls one of two methods:
 * <ul>
 *   <li>{@link #json} returns a validated record (test cases, a repair plan, ...)</li>
 *   <li>{@link #text} returns free text (the refactored Java files)</li>
 * </ul>
 * Every prompt and reply is written to runs/&lt;run&gt;/llm/ and logged in the trace. With Ollama, JSON replies
 * are constrained to the schema (Ollama's structured outputs), so even a small local model returns usable JSON.
 */
public class Llm {

    static final String JSON_RETRY = "\n\nYour previous reply could not be used: %s\n"
            + "Reply again with only one JSON object that follows the schema. No prose and no code fences.";

    private static final List<String> CASE_KEYS =
            List.of("id", "technique", "method", "condition", "input", "expected", "junit");

    /** Model replies may contain raw line breaks inside strings (Python's json.loads(strict=False) allows them). */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

    protected final Settings settings;
    protected final Tracer tracer;
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    public Llm(Settings settings, Tracer tracer) {
        this.settings = settings;
        this.tracer = tracer;
    }

    /** Thrown when no usable reply comes back. */
    public static final class LlmException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---------------------------------------------------------------- public
    public <T> T json(String role, String system, String user, Schemas.Spec<T> spec) {
        String error = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String prompt = error == null ? user : user + String.format(JSON_RETRY, error);
            String raw = call(role, system, prompt, spec);
            try {
                return parse(raw, spec);
            } catch (IllegalArgumentException e) {
                error = shortError(e);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("error", error);
                tracer.event(role, "warning", "reply is not a valid " + spec.name() + " (attempt " + attempt + ")", data);
            }
        }
        throw new LlmException(role + ": no valid " + spec.name() + " after 2 attempts: " + error);
    }

    public String text(String role, String system, String user) {
        return call(role, system, user, null);
    }

    // ---------------------------------------------------------------- one call
    private String call(String role, String system, String user, Schemas.Spec<?> spec) {
        String modelName = settings.modelFor(role);
        Path[] files = promptFiles(role, modelName, system, user);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prompt_chars", system.length() + user.length());
        data.put("schema", spec == null ? null : spec.name());
        tracer.event(role, "llm_call", settings.llmProvider + ":" + modelName + " <- " + files[0].getFileName(), data);
        long t0 = System.nanoTime();
        String raw = complete(role, modelName, system, user, spec, files[0], files[1]);
        Text.write(files[1], raw);
        tracer.event(role, "llm_reply", String.format(java.util.Locale.ROOT, "%.1fs -> %s",
                (System.nanoTime() - t0) / 1e9, files[1].getFileName()), Map.of("reply_chars", raw.length()));
        return raw;
    }

    private Path[] promptFiles(String role, String modelName, String system, String user) {
        if (settings.llmProvider.equals("manual")) {
            // A resumed node runs again from its start: reuse the prompt file it wrote before pausing.
            List<Path> existing = new ArrayList<>(Text.list(tracer.llmDir(), "*-" + role + ".prompt.md"));
            java.util.Collections.reverse(existing);
            for (Path file : existing) {
                String body = Text.read(file);
                if (body.endsWith(user.strip() + "\n") && body.contains(system.strip())) {
                    String name = file.getFileName().toString().replace(".prompt.md", ".response.md");
                    return new Path[] {file, file.resolveSibling(name)};
                }
            }
        }
        Path[] files = tracer.nextLlmFiles(role);
        Tracer.writePrompt(files[0], role, modelName, system, user);
        return files;
    }

    /** The model's reply. Tests replace this with scripted replies. */
    protected String complete(String role, String modelName, String system, String user, Schemas.Spec<?> spec,
                              Path promptFile, Path responseFile) {
        if (settings.llmProvider.equals("manual")) {
            if (Files.isRegularFile(responseFile) && !Text.read(responseFile).isBlank()) {
                return Text.read(responseFile);
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("kind", "llm");
            info.put("role", role);
            info.put("prompt_file", promptFile.toString());
            info.put("response_file", responseFile.toString());
            info.put("format", spec != null ? "json" : "text");
            throw new Pause(info);
        }
        ChatRequest.Builder request = ChatRequest.builder()
                .messages(SystemMessage.from(system), UserMessage.from(user));
        if (spec != null) {
            request.responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder().name(spec.name()).rootElement(spec.schema()).build()).build());
        }
        try {
            return chatModel(modelName).chat(request.build()).aiMessage().text();
        } catch (RuntimeException e) {
            throw new LlmException(role + ": the model call failed: " + e.getMessage(), e);
        }
    }

    private ChatModel chatModel(String modelName) {
        return models.computeIfAbsent(modelName, name -> {
            if (!settings.llmProvider.equals("ollama")) {
                throw new LlmException("Unknown LLM_PROVIDER '" + settings.llmProvider + "': use ollama or manual");
            }
            return OllamaChatModel.builder()
                    .baseUrl(settings.ollamaBaseUrl)
                    .modelName(name)
                    .temperature(settings.temperature)
                    .numCtx(settings.numCtx)
                    .numPredict(settings.numPredict)
                    .timeout(Duration.ofMillis((long) (settings.llmTimeout * 1000)))
                    .maxRetries(0)
                    .build();
        });
    }

    // ---------------------------------------------------------------- parsing replies
    /** Parse the JSON object in a model reply (tolerates code fences and text around it). */
    public static Object extractJson(String text) {
        String t = text.strip();
        Matcher fence = FENCE.matcher(t);
        if (fence.find() && !t.startsWith("{")) {
            t = fence.group(1).strip();
        }
        if (!t.startsWith("{") && !t.startsWith("[")) {
            int start = t.indexOf('{');
            int end = t.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("no JSON object found in the reply");
            }
            t = t.substring(start, end + 1);
        }
        try {
            return LENIENT.readValue(t, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** A reply as the output record (raises IllegalArgumentException with the reason if it does not fit). */
    public static <T> T parse(String raw, Schemas.Spec<T> spec) {
        Object data = normalize(extractJson(raw), spec);
        try {
            return Json.MAPPER.convertValue(data, spec.type());
        } catch (IllegalArgumentException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            throw new IllegalArgumentException(cause.getMessage(), e);
        }
    }

    /** Small models sometimes wrap the object, return a bare list, or leave out a text field. */
    @SuppressWarnings("unchecked")
    static Object normalize(Object data, Schemas.Spec<?> spec) {
        if (data instanceof List<?> list && spec.has("test_cases")) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("test_cases", list);
            data = wrapped;
        }
        if (data instanceof Map<?, ?> map && map.size() == 1) {
            Map.Entry<?, ?> only = map.entrySet().iterator().next();
            if (!spec.has(String.valueOf(only.getKey())) && only.getValue() instanceof Map<?, ?>) {
                data = only.getValue();
            }
        }
        if (data instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
            for (String key : List.of("test_cases", "add", "modify")) {
                if (map.get(key) instanceof List<?> cases) {
                    List<Object> clean = new ArrayList<>();
                    for (Object c : cases) {
                        if (c instanceof Map<?, ?> m) {
                            clean.add(normalizeCase((Map<String, Object>) m));
                        }
                    }
                    map.put(key, clean);
                }
            }
            for (String key : List.of("analysis", "summary", "diagnosis")) {
                if (spec.has(key)) {
                    map.putIfAbsent(key, "");
                }
            }
            return map;
        }
        return data;
    }

    private static Map<String, Object> normalizeCase(Map<String, Object> raw) {
        Map<String, Object> c = new LinkedHashMap<>(raw);
        for (String key : CASE_KEYS) {
            Object value = c.get(key);
            c.put(key, value == null ? "" : value instanceof String ? value : Json.compact(value));
        }
        return c;
    }

    private static String shortError(Exception e) {
        String text = String.valueOf(e.getMessage()).replace("\n", " ");
        return text.length() <= 800 ? text : text.substring(0, 800);
    }

    // ---------------------------------------------------------------- preflight
    /** A problem description, or null if Ollama is up and the models are pulled. */
    public static String checkOllama(Settings settings) {
        if (!settings.llmProvider.equals("ollama")) {
            return null;
        }
        String url = settings.ollamaBaseUrl.replaceAll("/+$", "") + "/api/tags";
        Map<String, Object> tags;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            tags = Json.parseMap(response.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Ollama is not reachable at " + settings.ollamaBaseUrl
                    + ". Start it (open the Ollama app, or run `ollama serve`) and try again.";
        }
        Set<String> have = new HashSet<>();
        if (tags.get("models") instanceof List<?> models) {
            for (Object m : models) {
                if (m instanceof Map<?, ?> model && model.get("name") != null) {
                    String name = model.get("name").toString();
                    have.add(name);
                    if (name.endsWith(":latest")) {
                        have.add(name.substring(0, name.indexOf(':')));
                    }
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String m : List.of(settings.llmModel, settings.refactorModel)) {
            if (!m.isBlank() && !have.contains(m)) {
                missing.add(m);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        List<String> pulls = new ArrayList<>();
        for (String m : missing) {
            pulls.add("ollama pull " + m);
        }
        return "Model(s) not pulled yet: " + String.join(", ", missing) + ". Run: " + String.join("; ", pulls);
    }
}
