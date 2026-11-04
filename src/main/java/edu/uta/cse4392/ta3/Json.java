package edu.uta.cse4392.ta3;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON with Jackson. Field names are snake_case (test_cases, code_edits, ...) in the agents' replies and
 * in every file a run writes. Files are indented like Python's json.dumps(indent=2): one item per line.
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private Json() {
    }

    /** Pretty-printed JSON: two spaces, "key": value, one array item per line. */
    public static String pretty(Object value) {
        try {
            return MAPPER.writer(new Printer()).writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One-line JSON without spaces. */
    public static String compact(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One-line JSON with a space after ':' and ',' (Python's json.dumps), easier to read in prompts and logs. */
    public static String line(Object value) {
        try {
            return MAPPER.writer(new LinePrinter()).writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static final class LinePrinter extends MinimalPrettyPrinter {
        private static final long serialVersionUID = 1L;

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }

        @Override
        public void writeObjectEntrySeparator(JsonGenerator g) throws IOException {
            g.writeRaw(", ");
        }

        @Override
        public void writeArrayValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(", ");
        }
    }

    public static void write(Path file, Object value) {
        Text.write(file, pretty(value) + "\n");
    }

    public static Map<String, Object> readMap(Path file) {
        return parseMap(Text.read(file));
    }

    public static Object read(Path file) {
        return parse(Text.read(file));
    }

    public static Object parse(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
    }

    public static Map<String, Object> parseMap(String text) {
        try {
            return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
    }

    /** A record or a JSON tree as plain maps and lists (the form the graph state keeps). */
    public static Map<String, Object> toMap(Object value) {
        return MAPPER.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public static List<Map<String, Object>> toMaps(List<?> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : values) {
            out.add(toMap(v));
        }
        return out;
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /** Python-style indentation (Jackson's default puts arrays on one line and writes "key" : value). */
    static final class Printer extends DefaultPrettyPrinter {
        private static final long serialVersionUID = 1L;

        Printer() {
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            indentArraysWith(indenter);
            indentObjectsWith(indenter);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new Printer();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }

        @Override
        public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
            if (!_objectIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfEntries > 0) {
                _objectIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw('}');
        }

        @Override
        public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
            if (!_arrayIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfValues > 0) {
                _arrayIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw(']');
        }
    }
}
