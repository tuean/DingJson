package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.parser.JSONParser;
import com.dingjson.parser.JSONReader;
import com.dingjson.parser.JSONTokener;
import com.dingjson.serializer.JSONSerializer;
import com.dingjson.serializer.JSONWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DingJson — the main static API facade.
 * <p>
 * Usage mirrors Fastjson's static convenience methods:
 * <pre>{@code
 * // Parse
 * JSONObject obj = DingJson.parseObject("{\"name\":\"Ding\"}");
 * MyBean bean = DingJson.parseObject(jsonString, MyBean.class);
 * JSONArray arr = DingJson.parseArray("[1,2,3]");
 *
 * // Serialize
 * String json = DingJson.toJSONString(obj);
 * String pretty = DingJson.toJSONString(obj, true);
 *
 * // Convert
 * JSONObject jsonObj = DingJson.toJSON(bean);
 * }</pre>
 * <p>
 * DingJson has zero runtime dependencies and is designed for
 * security and simplicity over raw parsing speed.
 *
 * @see JSONObject
 * @see JSONArray
 */
public final class DingJson {

    private DingJson() {
        // static-only
    }

    // ── Parse ──────────────────────────────────────────────────────

    /** Shared default config used by the static API (10 MB string, 10M array, 1M object limits). */
    private static final DingJsonConfig DEFAULT_CONFIG = DingJsonConfig.defaults();

    /** Normalizes a null config to the defaults (the static API never silently disables limits). */
    private static DingJsonConfig normalize(DingJsonConfig config) {
        return config == null ? DEFAULT_CONFIG : config;
    }

    /**
     * Parse a JSON string into a {@link JSONObject}.
     *
     * @throws JSONException if the input is not a valid JSON object
     */
    public static JSONObject parseObject(String text) {
        return parseObject(text, DEFAULT_CONFIG);
    }

    /**
     * Parse a JSON string into a {@link JSONObject} with explicit configuration.
     *
     * @throws JSONException if the input is not a valid JSON object
     */
    public static JSONObject parseObject(String text, DingJsonConfig config) {
        return asObject(parse(text, config));
    }

    /**
     * Parse a JSON string into a typed Java bean.
     * <p>
     * Uses the streaming reader (no intermediate JSON tree) for scalar/nested
     * bean fields; generic fields fall back to the tree path.
     *
     * @throws JSONException if parsing fails or the root is not a JSON object
     */
    public static <T> T parseObject(String text, Class<T> clazz) {
        return parseObject(text, clazz, DEFAULT_CONFIG);
    }

    /**
     * Parse a JSON string into a typed Java bean with explicit configuration.
     *
     * @throws JSONException if parsing fails or the root is not a JSON object
     */
    public static <T> T parseObject(String text, Class<T> clazz, DingJsonConfig config) {
        config = normalize(config);
        if (clazz == JSONObject.class) {
            return clazz.cast(parseObject(text, config));
        }
        JSONReader reader = new JSONReader(text, config);
        T result = JSONSerializer.deserializeFromReader(reader, clazz, config);
        reader.ensureEnded();
        return result;
    }

    /**
     * Parse a JSON string into an arbitrary type described by a {@link TypeReference}.
     * <p>
     * Unlike {@link #parseObject(String, Class)}, the root may be any JSON value —
     * object, array, or primitive — matching the captured generic type:
     * <pre>{@code
     * List<Person> people = DingJson.parseObject(json, new TypeReference<List<Person>>() {});
     * Map<String, Address> map = DingJson.parseObject(json, new TypeReference<Map<String, Address>>() {});
     * }</pre>
     *
     * @throws JSONException if parsing fails or the value cannot be converted to T
     */
    public static <T> T parseObject(String text, TypeReference<T> typeRef) {
        return parseObject(text, typeRef, DEFAULT_CONFIG);
    }

    /**
     * Parse a JSON string into an arbitrary type with explicit configuration.
     *
     * @throws JSONException if parsing fails or the value cannot be converted to T
     */
    public static <T> T parseObject(String text, TypeReference<T> typeRef, DingJsonConfig config) {
        Object result = parse(text, config);
        return JSONSerializer.deserialize(result, typeRef.getType(), config);
    }

    /**
     * Parse a JSON string into a {@link JSONArray}.
     *
     * @throws JSONException if the input is not a valid JSON array
     */
    public static JSONArray parseArray(String text) {
        return parseArray(text, DEFAULT_CONFIG);
    }

    /**
     * Parse a JSON string into a {@link JSONArray} with explicit configuration.
     *
     * @throws JSONException if the input is not a valid JSON array
     */
    public static JSONArray parseArray(String text, DingJsonConfig config) {
        return asArray(parse(text, config));
    }

    /**
     * Parse a JSON string into a typed list.
     * <p>
     * Uses the streaming reader (no intermediate JSON tree).
     *
     * @throws JSONException if parsing fails or the root is not a JSON array
     */
    public static <T> List<T> parseArray(String text, Class<T> elementType) {
        return parseArray(text, elementType, DEFAULT_CONFIG);
    }

    /**
     * Parse a JSON string into a typed list with explicit configuration.
     *
     * @throws JSONException if parsing fails or the root is not a JSON array
     */
    public static <T> List<T> parseArray(String text, Class<T> elementType, DingJsonConfig config) {
        return parseTypedArray(text, elementType, config);
    }

    /**
     * Parse a JSON array into a list whose element type is captured by a
     * {@link TypeReference} — including nested generics:
     * <pre>{@code
     * List<List<Integer>> m = DingJson.parseArray(json, new TypeReference<List<Integer>>() {});
     * }</pre>
     *
     * @throws JSONException if parsing fails or the root is not a JSON array
     */
    public static <T> List<T> parseArray(String text, TypeReference<T> elementType) {
        return parseArray(text, elementType, DEFAULT_CONFIG);
    }

    /**
     * Parse a JSON array into a list with a generic element type and explicit configuration.
     *
     * @throws JSONException if parsing fails or the root is not a JSON array
     */
    public static <T> List<T> parseArray(String text, TypeReference<T> elementType, DingJsonConfig config) {
        JSONArray arr = parseArray(text, config);
        return JSONSerializer.deserializeList(arr, elementType.getType(), config);
    }

    /**
     * Parse any JSON value (object, array, string, number, boolean, null).
     *
     * @return {@link JSONObject}, {@link JSONArray}, String, Number, Boolean, or null
     */
    public static Object parse(String text) {
        return parse(text, DEFAULT_CONFIG);
    }

    /**
     * Parse any JSON value with explicit configuration.
     *
     * @return {@link JSONObject}, {@link JSONArray}, String, Number, Boolean, or null
     */
    public static Object parse(String text, DingJsonConfig config) {
        if (text == null || text.trim().isEmpty()) {
            throw new JSONException("Input must not be null or empty");
        }
        config = normalize(config);
        JSONTokener tokener = new JSONTokener(text, config);
        JSONParser parser = new JSONParser(tokener, config);
        return parser.parse();
    }

    // ── Serialize ──────────────────────────────────────────────────

    /**
     * Serialize an object to a compact JSON string.
     */
    public static String toJSONString(Object object) {
        return JSONSerializer.toJSONString(object);
    }

    /**
     * Serialize an object to a compact JSON string with explicit configuration.
     */
    public static String toJSONString(Object object, DingJsonConfig config) {
        return JSONSerializer.toJSONString(object, config);
    }

    /**
     * Serialize an object to a pretty-printed JSON string (2-space indent).
     */
    public static String toJSONString(Object object, boolean pretty) {
        return JSONSerializer.toJSONString(object, pretty);
    }

    /**
     * Serialize an object to a pretty-printed JSON string (2-space indent),
     * with explicit configuration.
     */
    public static String toJSONString(Object object, boolean pretty, DingJsonConfig config) {
        return JSONSerializer.toJSONString(object, pretty, config);
    }

    /**
     * Serialize an object to UTF-8 bytes (HTTP-ready, avoids String→byte[]
     * round-trip in the caller).
     */
    public static byte[] toJSONBytes(Object object) {
        return toJSONString(object).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Serialize an object to UTF-8 bytes with explicit configuration.
     */
    public static byte[] toJSONBytes(Object object, DingJsonConfig config) {
        return toJSONString(object, config).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Configurable mapper ──────────────────────────────────────

    /**
     * Create a configurable mapper (Jackson's {@code ObjectMapper} analogue):
     * <pre>{@code
     * DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
     *         .writeNullFields(false)
     *         .dateFormat("yyyy-MM-dd HH:mm:ss")
     *         .build());
     * String json = mapper.toJSONString(bean);
     * }</pre>
     * The static methods on {@link DingJson} always use default configuration.
     */
    public static DingJsonMapper mapper(DingJsonConfig config) {
        return new DingJsonMapper(config);
    }

    /** A mapper with default configuration (convenience for builder chains). */
    public static DingJsonMapper mapper() {
        return new DingJsonMapper(DingJsonConfig.defaults());
    }

    // ── Codecs ───────────────────────────────────────────────────

    /**
     * Registers the (usually generated) codec for {@code type} explicitly.
     * <p>
     * Annotated beans are found automatically on first use, which costs one
     * {@code Class.forName} per class. Registering up front removes even that:
     * the bean path then contains no reflection at all, which matters where
     * lookup by name is unavailable or unwanted (GraalVM native-image,
     * restricted class loaders). Must be called before the type is first
     * (de)serialized, normally from a static initialiser.
     *
     * <pre>{@code
     * DingJson.registerCodec(User.class, new User$$DingJsonCodec());
     * }</pre>
     */
    public static <T> void registerCodec(Class<T> type,
            com.dingjson.codec.DingJsonCodec<T> codec) {
        JSONSerializer.registerCodec(type, codec);
    }

    // ── Streaming ────────────────────────────────────────────────

    /**
     * Create a streaming {@link JSONReader} over a JSON text.
     * <p>
     * Lets the caller drive the structure token by token and read typed
     * primitive arrays with zero boxing:
     * <pre>{@code
     * JSONReader r = DingJson.createReader(json);
     * r.readBeginObject();
     * while (r.hasNext()) {
     *     String key = r.readKey();
     *     switch (key) {
     *         case "ids": int[] ids = r.readIntArray(); break;
     *         default:    r.skipValue();
     *     }
     * }
     * r.readEndObject();
     * }</pre>
     */
    public static JSONReader createReader(String text) {
        return new JSONReader(text, DEFAULT_CONFIG);
    }

    /**
     * Create a streaming {@link JSONReader} over a JSON text with explicit
     * configuration (size limits, case-insensitive fields, ...).
     */
    public static JSONReader createReader(String text, DingJsonConfig config) {
        return new JSONReader(text, normalize(config));
    }

    // ── Convert ────────────────────────────────────────────────────

    /**
     * Convert a Java object to a {@link JSONObject}.
     * <p>
     * If the object is already a JSONObject it is returned directly.
     * Otherwise its fields are reflected and mapped into a new JSONObject.
     */
    public static JSONObject toJSON(Object object) {
        if (object == null) return null;
        if (object instanceof JSONObject) return (JSONObject) object;
        if (object instanceof Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) object;
            return new JSONObject(map);
        }
        String json = toJSONString(object);
        return parseObject(json);
    }

    /**
     * Convert a Java object to a {@link JSONArray}.
     * <p>
     * Accepts arrays, collections, and JSONArray instances.
     */
    public static JSONArray toJSONArray(Object object) {
        if (object == null) return null;
        if (object instanceof JSONArray) return (JSONArray) object;
        if (object instanceof java.util.Collection) {
            return new JSONArray((java.util.List<?>) object);
        }
        if (object.getClass().isArray()) {
            JSONArray arr = new JSONArray();
            int len = java.lang.reflect.Array.getLength(object);
            for (int i = 0; i < len; i++) {
                arr.add(java.lang.reflect.Array.get(object, i));
            }
            return arr;
        }
        throw new JSONException("Cannot convert " + object.getClass() + " to JSONArray");
    }

    // ── IO ─────────────────────────────────────────────────────────

    /** Strict UTF-8 decode: malformed input raises {@link JSONException} instead of silently replacing. */
    private static String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = decoder.decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException e) {
            throw new JSONException("Invalid UTF-8 input", e);
        }
    }

    /** Enforces {@link DingJsonConfig#maxInputBytes()} (no-op when 0). */
    private static void checkInputSize(long size, DingJsonConfig config) {
        int max = config.maxInputBytes();
        if (max > 0 && size > max) {
            throw new JSONException("Input size " + size
                    + " exceeds maximum allowed " + max + " bytes");
        }
    }

    /** Reads all bytes and decodes as strict UTF-8 (input-size limited). */
    static String readAll(byte[] bytes) {
        return readAll(bytes, DEFAULT_CONFIG);
    }

    /** Reads all bytes and decodes as strict UTF-8, honoring the configured input limit. */
    static String readAll(byte[] bytes, DingJsonConfig config) {
        if (bytes == null) {
            throw new JSONException("Input must not be null");
        }
        config = normalize(config);
        checkInputSize(bytes.length, config);
        return decodeUtf8(bytes);
    }

    /** Reads an {@link InputStream} to the end and decodes as strict UTF-8. */
    static String readAll(InputStream in) {
        return readAll(in, DEFAULT_CONFIG);
    }

    /**
     * Reads an {@link InputStream} to the end and decodes as strict UTF-8,
     * aborting as soon as the configured input limit is exceeded (the stream
     * itself may be unbounded — this keeps an attacker from feeding a
     * bottomless stream until the process runs out of memory).
     */
    static String readAll(InputStream in, DingJsonConfig config) {
        if (in == null) {
            throw new JSONException("Input stream must not be null");
        }
        config = normalize(config);
        int max = config.maxInputBytes();
        int initial = max > 0 ? (int) Math.min(max, 65536L) : 65536;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(initial);
        byte[] buf = new byte[8192];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (max > 0 && total > max) {
                    throw new JSONException("Input size exceeds maximum allowed " + max + " bytes");
                }
                bos.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new JSONException("Failed to read input stream", e);
        }
        return decodeUtf8(bos.toByteArray());
    }

    /** Reads a {@link Reader} to the end. */
    static String readAll(Reader reader) {
        return readAll(reader, DEFAULT_CONFIG);
    }

    /** Reads a {@link Reader} to the end (character count is used as a byte proxy for the limit). */
    static String readAll(Reader reader, DingJsonConfig config) {
        if (reader == null) {
            throw new JSONException("Reader must not be null");
        }
        config = normalize(config);
        int max = config.maxInputBytes();
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        long total = 0;
        try {
            int n;
            while ((n = reader.read(buf)) != -1) {
                total += n;
                if (max > 0 && total > max) {
                    throw new JSONException("Input size exceeds maximum allowed " + max + " characters");
                }
                sb.append(buf, 0, n);
            }
        } catch (IOException e) {
            throw new JSONException("Failed to read reader", e);
        }
        return sb.toString();
    }

    /** Reads a file and decodes as strict UTF-8. */
    static String readAll(File file) {
        return readAll(file, DEFAULT_CONFIG);
    }

    /** Reads a file and decodes as strict UTF-8 (size-checked before and after the read). */
    static String readAll(File file, DingJsonConfig config) {
        if (file == null) {
            throw new JSONException("File must not be null");
        }
        config = normalize(config);
        try {
            checkInputSize(Files.size(file.toPath()), config); // cheap pre-check
            byte[] bytes = Files.readAllBytes(file.toPath());
            checkInputSize(bytes.length, config); // belt-and-braces (TOCTOU)
            return decodeUtf8(bytes);
        } catch (IOException e) {
            throw new JSONException("Failed to read file " + file, e);
        }
    }

    /** Parse UTF-8 bytes into a {@link JSONObject}. */
    public static JSONObject parseObject(byte[] bytes) {
        return parseObject(readAll(bytes));
    }

    /** Parse UTF-8 bytes into a typed bean. */
    public static <T> T parseObject(byte[] bytes, Class<T> clazz) {
        return parseObject(readAll(bytes), clazz);
    }

    /** Parse UTF-8 bytes into an arbitrary generic type. */
    public static <T> T parseObject(byte[] bytes, TypeReference<T> typeRef) {
        return parseObject(readAll(bytes), typeRef);
    }

    /** Parse UTF-8 bytes into a {@link JSONArray}. */
    public static JSONArray parseArray(byte[] bytes) {
        return parseArray(readAll(bytes));
    }

    /** Parse UTF-8 bytes into a typed list. */
    public static <T> List<T> parseArray(byte[] bytes, Class<T> elementType) {
        return parseArray(readAll(bytes), elementType);
    }

    /** Parse UTF-8 bytes into a list with a generic element type. */
    public static <T> List<T> parseArray(byte[] bytes, TypeReference<T> elementType) {
        return parseArray(readAll(bytes), elementType);
    }

    /** Parse any JSON value from UTF-8 bytes. */
    public static Object parse(byte[] bytes) {
        return parse(readAll(bytes));
    }

    /** Parse an {@link InputStream} into a {@link JSONObject}. */
    public static JSONObject parseObject(InputStream in) {
        return parseObject(readAll(in));
    }

    /** Parse an {@link InputStream} into a typed bean. */
    public static <T> T parseObject(InputStream in, Class<T> clazz) {
        return parseObject(readAll(in), clazz);
    }

    /** Parse an {@link InputStream} into an arbitrary generic type. */
    public static <T> T parseObject(InputStream in, TypeReference<T> typeRef) {
        return parseObject(readAll(in), typeRef);
    }

    /** Parse an {@link InputStream} into a {@link JSONArray}. */
    public static JSONArray parseArray(InputStream in) {
        return parseArray(readAll(in));
    }

    /** Parse an {@link InputStream} into a typed list. */
    public static <T> List<T> parseArray(InputStream in, Class<T> elementType) {
        return parseArray(readAll(in), elementType);
    }

    /** Parse an {@link InputStream} into a list with a generic element type. */
    public static <T> List<T> parseArray(InputStream in, TypeReference<T> elementType) {
        return parseArray(readAll(in), elementType);
    }

    /** Parse any JSON value from an {@link InputStream}. */
    public static Object parse(InputStream in) {
        return parse(readAll(in));
    }

    /** Parse a {@link Reader} into a {@link JSONObject}. */
    public static JSONObject parseObject(Reader reader) {
        return parseObject(readAll(reader));
    }

    /** Parse a {@link Reader} into a typed bean. */
    public static <T> T parseObject(Reader reader, Class<T> clazz) {
        return parseObject(readAll(reader), clazz);
    }

    /** Parse a {@link Reader} into an arbitrary generic type. */
    public static <T> T parseObject(Reader reader, TypeReference<T> typeRef) {
        return parseObject(readAll(reader), typeRef);
    }

    /** Parse a {@link Reader} into a {@link JSONArray}. */
    public static JSONArray parseArray(Reader reader) {
        return parseArray(readAll(reader));
    }

    /** Parse a {@link Reader} into a typed list. */
    public static <T> List<T> parseArray(Reader reader, Class<T> elementType) {
        return parseArray(readAll(reader), elementType);
    }

    /** Parse a {@link Reader} into a list with a generic element type. */
    public static <T> List<T> parseArray(Reader reader, TypeReference<T> elementType) {
        return parseArray(readAll(reader), elementType);
    }

    /** Parse any JSON value from a {@link Reader}. */
    public static Object parse(Reader reader) {
        return parse(readAll(reader));
    }

    /** Parse a UTF-8 file into a {@link JSONObject}. */
    public static JSONObject parseObject(File file) {
        return parseObject(readAll(file));
    }

    /** Parse a UTF-8 file into a typed bean. */
    public static <T> T parseObject(File file, Class<T> clazz) {
        return parseObject(readAll(file), clazz);
    }

    /** Parse a UTF-8 file into an arbitrary generic type. */
    public static <T> T parseObject(File file, TypeReference<T> typeRef) {
        return parseObject(readAll(file), typeRef);
    }

    /** Parse a UTF-8 file into a {@link JSONArray}. */
    public static JSONArray parseArray(File file) {
        return parseArray(readAll(file));
    }

    /** Parse a UTF-8 file into a typed list. */
    public static <T> List<T> parseArray(File file, Class<T> elementType) {
        return parseArray(readAll(file), elementType);
    }

    /** Parse a UTF-8 file into a list with a generic element type. */
    public static <T> List<T> parseArray(File file, TypeReference<T> elementType) {
        return parseArray(readAll(file), elementType);
    }

    /** Parse any JSON value from a UTF-8 file. */
    public static Object parse(File file) {
        return parse(readAll(file));
    }

    /** Serialize to a file (UTF-8, compact). */
    public static void writeTo(File file, Object object) {
        writeTo(file, object, false);
    }

    /** Serialize to a file (UTF-8), optionally pretty-printed. */
    public static void writeTo(File file, Object object, boolean pretty) {
        if (file == null) {
            throw new JSONException("File must not be null");
        }
        try {
            Files.write(file.toPath(), toJSONString(object, pretty).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JSONException("Failed to write file " + file, e);
        }
    }

    /** Serialize to an output stream (UTF-8, compact). */
    public static void writeTo(OutputStream out, Object object) {
        writeTo(out, object, false);
    }

    /** Serialize to an output stream (UTF-8), optionally pretty-printed. */
    public static void writeTo(OutputStream out, Object object, boolean pretty) {
        if (out == null) {
            throw new JSONException("Output stream must not be null");
        }
        try {
            out.write(toJSONString(object, pretty).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JSONException("Failed to write output stream", e);
        }
    }

    // ── Convenience ────────────────────────────────────────────────

    /**
     * Parse a JSON document and evaluate a {@link JSONPath} in one step:
     * <pre>{@code Object price = DingJson.eval(json, "$.items[0].price");}</pre>
     * Returns {@code null} when the path does not match.
     */
    public static Object eval(String json, String path) {
        return JSONPath.eval(parse(json), path);
    }

    /** Parse a JSON document, evaluate a {@link JSONPath}, and coerce the result. */
    public static <T> T eval(String json, String path, Class<T> clazz) {
        return JSONPath.eval(parse(json), path, clazz);
    }

    /**
     * Validate that {@code text} is a well-formed JSON document without
     * building a tree (constant memory — no OOM risk on huge payloads).
     * Uses the default size limits, so documents exceeding them are invalid.
     */
    public static boolean isValid(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            JSONReader reader = new JSONReader(text, DEFAULT_CONFIG);
            reader.skipValue();
            reader.ensureEnded();
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Create a streaming {@link JSONWriter} (alias for {@link JSONWriter#create()}). */
    public static JSONWriter createWriter() {
        return JSONWriter.create();
    }

    /** Create a streaming {@link JSONWriter} with an initial capacity hint. */
    public static JSONWriter createWriter(int initialCapacity) {
        return JSONWriter.create(initialCapacity);
    }

    // ── Shared helpers (also used by DingJsonMapper) ────────────

    /** Returns {@code result} as a {@link JSONObject} or throws a descriptive error. */
    static JSONObject asObject(Object result) {
        if (!(result instanceof JSONObject)) {
            throw new JSONException("Expected JSON object but got "
                    + (result == null ? "null" : result.getClass().getSimpleName()));
        }
        return (JSONObject) result;
    }

    /** Returns {@code result} as a {@link JSONArray} or throws a descriptive error. */
    static JSONArray asArray(Object result) {
        if (!(result instanceof JSONArray)) {
            throw new JSONException("Expected JSON array but got "
                    + (result == null ? "null" : result.getClass().getSimpleName()));
        }
        return (JSONArray) result;
    }

    /** Shared streaming typed-list parse (single implementation for the facade and the mapper). */
    static <T> List<T> parseTypedArray(String text, Class<T> elementType, DingJsonConfig config) {
        config = normalize(config);
        JSONReader reader = new JSONReader(text, config);
        reader.readBeginArray();
        List<T> list = new ArrayList<>();
        int maxArray = config.maxArraySize();
        boolean first = true;
        while (reader.peek() != ']') {
            if (!first) {
                reader.skipComma(); // consume the element separator
            }
            first = false;
            if (maxArray > 0 && list.size() >= maxArray) {
                throw new JSONException("Array size exceeds maximum allowed " + maxArray);
            }
            list.add(JSONSerializer.deserializeFromReader(reader, elementType, config));
        }
        reader.readEndArray();
        reader.ensureEnded();
        return list;
    }

    // ── Deep copy ──────────────────────────────────────────────────

    /** Recursively copies a JSON object tree (no serialization round-trip). */
    public static JSONObject deepCopy(JSONObject obj) {
        try {
            return deepCopyObject(obj);
        } catch (StackOverflowError e) {
            throw new JSONException("Object tree too deep to copy", e);
        }
    }

    private static JSONObject deepCopyObject(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        JSONObject copy = new JSONObject();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            copy.put(e.getKey(), deepCopyNode(e.getValue()));
        }
        return copy;
    }

    /** Recursively copies a JSON array tree (no serialization round-trip). */
    public static JSONArray deepCopy(JSONArray arr) {
        try {
            return deepCopyArray(arr);
        } catch (StackOverflowError e) {
            throw new JSONException("Array tree too deep to copy", e);
        }
    }

    private static JSONArray deepCopyArray(JSONArray arr) {
        if (arr == null) {
            return null;
        }
        JSONArray copy = new JSONArray(arr.size());
        for (Object v : arr) {
            copy.add(deepCopyNode(v));
        }
        return copy;
    }

    private static Object deepCopyNode(Object value) {
        if (value instanceof JSONObject) {
            return deepCopyObject((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            return deepCopyArray((JSONArray) value);
        }
        return value; // String / Number / Boolean / null are immutable
    }

    /**
     * Best-effort deep copy. Tree nodes are copied recursively; lists, maps and
     * plain beans go through a serialize/parse round-trip. Generic collections
     * without a {@link TypeReference} keep their elements as tree nodes
     * (documented behaviour) — use {@link #deepCopy(Object, TypeReference)} to
     * preserve element types.
     */
    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject) {
            return (T) deepCopy((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            return (T) deepCopy((JSONArray) value);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value; // immutable — no copy needed
        }
        if (value instanceof List) {
            JSONArray arr = (JSONArray) parse(toJSONString(value));
            return (T) new ArrayList<>(arr);
        }
        if (value instanceof Map) {
            return (T) parse(toJSONString(value)); // JSONObject — itself a Map
        }
        // Plain bean: serialize round-trip.
        return JSONSerializer.deserialize(parse(toJSONString(value)), value.getClass());
    }

    /**
     * Deep copy that preserves generic element types via a {@link TypeReference}:
     * <pre>{@code
     * List<Address> copy = DingJson.deepCopy(original, new TypeReference<List<Address>>() {});
     * }</pre>
     * Tree inputs whose target type is {@code JSONObject}/{@code JSONArray} are
     * copied recursively without a round-trip.
     */
    public static <T> T deepCopy(T value, TypeReference<T> typeRef) {
        if (value == null) {
            return null;
        }
        if (typeRef == null) {
            return deepCopy(value);
        }
        Type type = typeRef.getType();
        if (value instanceof JSONObject && type == JSONObject.class) {
            return (T) deepCopy((JSONObject) value);
        }
        if (value instanceof JSONArray && type == JSONArray.class) {
            return (T) deepCopy((JSONArray) value);
        }
        return parseObject(toJSONString(value), typeRef);
    }
}
