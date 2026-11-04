package edu.uta.cse4392.ta3;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured outputs the LLM agents must return. Each has a Java record (what the code uses) and a JSON
 * Schema (sent to Ollama, which constrains the reply to it, and shown to the model in the prompt).
 */
public final class Schemas {

    private Schemas() {
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? new ArrayList<>() : value;
    }

    // ---------------------------------------------------------------- records
    public record TestCase(String id, String technique, String method, String condition, String input,
                           String expected, String junit) {
        public TestCase {
            id = text(id);
            technique = text(technique);
            method = text(method);
            condition = text(condition);
            input = text(input);
            expected = text(expected);
            junit = text(junit);
        }

        /** The test case as the map the graph state keeps (with an empty "also" list). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("technique", technique);
            m.put("method", method);
            m.put("condition", condition);
            m.put("input", input);
            m.put("expected", expected);
            m.put("junit", junit);
            return m;
        }
    }

    public record TestSuiteDraft(String analysis, @JsonProperty("test_cases") List<TestCase> testCases) {
        public TestSuiteDraft {
            analysis = text(analysis);
            if (testCases == null) {
                throw new IllegalArgumentException("test_cases is missing");
            }
        }
    }

    public record SuiteRevision(String summary, List<TestCase> add, List<TestCase> modify, List<String> delete) {
        public SuiteRevision {
            summary = text(summary);
            add = list(add);
            modify = list(modify);
            delete = list(delete);
        }
    }

    public record TestEdit(String id, String action, String expected, String junit, String reason) {
        public TestEdit {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a test edit needs the id of the test case");
            }
            if (!"modify".equals(action) && !"delete".equals(action)) {
                throw new IllegalArgumentException("action must be \"modify\" or \"delete\", not " + Json.compact(action));
            }
            expected = text(expected);
            junit = text(junit);
            reason = text(reason);
        }
    }

    public record CodeEdit(String file, String content, String reason) {
        public CodeEdit {
            if (file == null || file.isBlank() || content == null) {
                throw new IllegalArgumentException("a code edit needs \"file\" and \"content\"");
            }
            reason = text(reason);
        }
    }

    public record RepairPlan(String diagnosis, @JsonProperty("test_edits") List<TestEdit> testEdits,
                             @JsonProperty("code_edits") List<CodeEdit> codeEdits) {
        public RepairPlan {
            diagnosis = text(diagnosis);
            testEdits = list(testEdits);
            codeEdits = list(codeEdits);
        }
    }

    // ---------------------------------------------------------------- JSON Schemas
    private static final JsonObjectSchema TEST_CASE = JsonObjectSchema.builder()
            .addStringProperty("id", "Unique id, e.g. EP-01, BVA-07 or COV-02")
            .addStringProperty("technique", "EP, BVA, COVERAGE or REVIEW")
            .addStringProperty("method", "Public method(s) or constructor under test, e.g. next()")
            .addStringProperty("condition", "The equivalence class or boundary this test exercises")
            .addStringProperty("input", "Concrete inputs: starting state plus the calls and argument values")
            .addStringProperty("expected", "Expected observable result (return value, state, output or exception)")
            .addStringProperty("junit", "Java statements for the body of one JUnit 5 test method")
            .required("id", "technique", "method", "condition", "input", "expected", "junit")
            .build();

    private static final JsonObjectSchema TEST_EDIT = JsonObjectSchema.builder()
            .addStringProperty("id", "Id of the test case to change")
            .addProperty("action", JsonEnumSchema.builder().enumValues("modify", "delete").build())
            .addStringProperty("expected", "New expected result (modify only)")
            .addStringProperty("junit", "New JUnit method body (modify only)")
            .addStringProperty("reason")
            .required("id", "action", "reason")
            .build();

    private static final JsonObjectSchema CODE_EDIT = JsonObjectSchema.builder()
            .addStringProperty("file", "Source file name, e.g. SeasonSwitch.java")
            .addStringProperty("content", "The complete new content of that file")
            .addStringProperty("reason")
            .required("file", "content", "reason")
            .build();

    private static JsonArraySchema arrayOf(JsonSchemaElement items, String description) {
        return JsonArraySchema.builder().items(items).description(description).build();
    }

    /** An output type: its record class, its JSON Schema, and the text fields a small model may leave out. */
    public record Spec<T>(String name, Class<T> type, JsonObjectSchema schema) {
        public boolean has(String field) {
            return schema.properties().containsKey(field);
        }

        /** The schema as JSON text for the prompt. */
        public String text() {
            return Json.pretty(toMap(schema));
        }
    }

    public static final Spec<TestSuiteDraft> TEST_SUITE_DRAFT = new Spec<>("TestSuiteDraft", TestSuiteDraft.class,
            JsonObjectSchema.builder()
                    .addStringProperty("analysis", "Short list of the equivalence classes or boundaries identified")
                    .addProperty("test_cases", arrayOf(TEST_CASE, null))
                    .required("analysis", "test_cases")
                    .build());

    public static final Spec<SuiteRevision> SUITE_REVISION = new Spec<>("SuiteRevision", SuiteRevision.class,
            JsonObjectSchema.builder()
                    .addStringProperty("summary", "What was changed and why")
                    .addProperty("add", arrayOf(TEST_CASE, "New test cases"))
                    .addProperty("modify", arrayOf(TEST_CASE, "Replacements, same id as the test case they replace"))
                    .addProperty("delete", arrayOf(new JsonStringSchema(), "Ids of test cases to remove"))
                    .required("summary")
                    .build());

    public static final Spec<RepairPlan> REPAIR_PLAN = new Spec<>("RepairPlan", RepairPlan.class,
            JsonObjectSchema.builder()
                    .addStringProperty("diagnosis",
                            "Root cause of each failure: is the code wrong or the test wrong, and why")
                    .addProperty("test_edits", arrayOf(TEST_EDIT, null))
                    .addProperty("code_edits", arrayOf(CODE_EDIT, null))
                    .required("diagnosis")
                    .build());

    /** A schema element as a JSON Schema map: type, description, properties, required, items, enum. */
    public static Map<String, Object> toMap(JsonSchemaElement element) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (element instanceof JsonObjectSchema o) {
            m.put("type", "object");
            if (o.description() != null) {
                m.put("description", o.description());
            }
            Map<String, Object> props = new LinkedHashMap<>();
            o.properties().forEach((k, v) -> props.put(k, toMap(v)));
            m.put("properties", props);
            if (o.required() != null && !o.required().isEmpty()) {
                m.put("required", o.required());
            }
        } else if (element instanceof JsonArraySchema a) {
            m.put("type", "array");
            if (a.description() != null) {
                m.put("description", a.description());
            }
            m.put("items", toMap(a.items()));
        } else if (element instanceof JsonEnumSchema e) {
            m.put("type", "string");
            m.put("enum", e.enumValues());
            if (e.description() != null) {
                m.put("description", e.description());
            }
        } else if (element instanceof JsonStringSchema s) {
            m.put("type", "string");
            if (s.description() != null) {
                m.put("description", s.description());
            }
        } else {
            m.put("type", element.getClass().getSimpleName());
        }
        return m;
    }
}
