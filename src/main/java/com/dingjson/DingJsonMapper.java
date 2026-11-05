package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.parser.JSONReader;
import com.dingjson.serializer.JSONSerializer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * A configurable DingJson engine (Jackson's {@code ObjectMapper} / Gson's
 * {@code Gson} analogue). Create one per configuration and reuse it:
 * <pre>{@code
 * DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
 *         .writeNullFields(false)
 *         .dateFormat("yyyy-MM-dd HH:mm:ss")
 *         .build());
 *
 * String json = mapper.toJSONString(user);   // configured output
 * User u = mapper.parseObject(json, User.class);
 * }</pre>
 * The static {@link DingJson} facade always uses default configuration.
 * <p>
 * Instances are thread-safe once created (all caches are concurrent).
 */
public final class DingJsonMapper {

    private final DingJsonConfig config;

    public DingJsonMapper(DingJsonConfig config) {
        this.config = config == null ? DingJsonConfig.defaults() : config;
    }

    public DingJsonConfig config() {
        return config;
    }

    // ── Parse ─────────────────────────────────────────────────────

    /** Parse a JSON string into a typed bean (or scalar), configured. */
    public <T> T parseObject(String text, Class<T> clazz) {
        if (clazz == JSONObject.class) {
            return clazz.cast(parseObject(text));
        }
        return DingJson.parseObject(text, clazz, config);
    }

    /** Parse a JSON string into an arbitrary type via {@link TypeReference}. */
    public <T> T parseObject(String text, TypeReference<T> typeRef) {
        return JSONSerializer.deserialize(parse(text), typeRef.getType(), config);
    }

    /** Parse a JSON string into a {@link JSONObject} tree. */
    public JSONObject parseObject(String text) {
        return DingJson.asObject(parse(text));
    }

    /** Parse a JSON string into a {@link JSONArray} tree. */
    public JSONArray parseArray(String text) {
        return DingJson.asArray(parse(text));
    }

    /** Parse a JSON array into a typed list, configured. */
    public <T> List<T> parseArray(String text, Class<T> elementType) {
        return DingJson.parseTypedArray(text, elementType, config);
    }

    /** Parse a JSON array into a list with a generic element type. */
    public <T> List<T> parseArray(String text, TypeReference<T> elementType) {
        JSONArray arr = parseArray(text);
        return JSONSerializer.deserializeList(arr, elementType.getType(), config);
    }

    /** Parse any JSON value. */
    public Object parse(String text) {
        return DingJson.parse(text, config);
    }

    // ── Serialize ─────────────────────────────────────────────────

    /** Serialize to a compact JSON string, configured. */
    public String toJSONString(Object object) {
        return JSONSerializer.toJSONString(object, config);
    }

    /** Serialize to a pretty-printed JSON string (2-space indent), configured. */
    public String toJSONString(Object object, boolean pretty) {
        return JSONSerializer.toJSONString(object, pretty, config);
    }

    /** Serialize to UTF-8 bytes, configured. */
    public byte[] toJSONBytes(Object object) {
        return JSONSerializer.toJSONString(object, config)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Streaming ─────────────────────────────────────────────────

    /** Create a streaming reader. */
    public JSONReader createReader(String text) {
        return new JSONReader(text, config);
    }

    // ── Parse (byte[] / stream / reader / file) ────────────────────

    /** Parse UTF-8 bytes into a {@link JSONObject}, configured. */
    public JSONObject parseObject(byte[] bytes) {
        return parseObject(DingJson.readAll(bytes, config));
    }

    /** Parse UTF-8 bytes into a typed bean, configured. */
    public <T> T parseObject(byte[] bytes, Class<T> clazz) {
        return parseObject(DingJson.readAll(bytes, config), clazz);
    }

    /** Parse UTF-8 bytes into an arbitrary generic type, configured. */
    public <T> T parseObject(byte[] bytes, TypeReference<T> typeRef) {
        return parseObject(DingJson.readAll(bytes, config), typeRef);
    }

    /** Parse UTF-8 bytes into a {@link JSONArray}, configured. */
    public JSONArray parseArray(byte[] bytes) {
        return parseArray(DingJson.readAll(bytes, config));
    }

    /** Parse UTF-8 bytes into a typed list, configured. */
    public <T> List<T> parseArray(byte[] bytes, Class<T> elementType) {
        return parseArray(DingJson.readAll(bytes, config), elementType);
    }

    /** Parse UTF-8 bytes into a list with a generic element type, configured. */
    public <T> List<T> parseArray(byte[] bytes, TypeReference<T> elementType) {
        return parseArray(DingJson.readAll(bytes, config), elementType);
    }

    /** Parse any JSON value from UTF-8 bytes, configured. */
    public Object parse(byte[] bytes) {
        return parse(DingJson.readAll(bytes, config));
    }

    /** Parse an {@link InputStream} into a {@link JSONObject}, configured. */
    public JSONObject parseObject(InputStream in) {
        return parseObject(DingJson.readAll(in, config));
    }

    /** Parse an {@link InputStream} into a typed bean, configured. */
    public <T> T parseObject(InputStream in, Class<T> clazz) {
        return parseObject(DingJson.readAll(in, config), clazz);
    }

    /** Parse an {@link InputStream} into an arbitrary generic type, configured. */
    public <T> T parseObject(InputStream in, TypeReference<T> typeRef) {
        return parseObject(DingJson.readAll(in, config), typeRef);
    }

    /** Parse an {@link InputStream} into a {@link JSONArray}, configured. */
    public JSONArray parseArray(InputStream in) {
        return parseArray(DingJson.readAll(in, config));
    }

    /** Parse an {@link InputStream} into a typed list, configured. */
    public <T> List<T> parseArray(InputStream in, Class<T> elementType) {
        return parseArray(DingJson.readAll(in, config), elementType);
    }

    /** Parse an {@link InputStream} into a list with a generic element type, configured. */
    public <T> List<T> parseArray(InputStream in, TypeReference<T> elementType) {
        return parseArray(DingJson.readAll(in, config), elementType);
    }

    /** Parse any JSON value from an {@link InputStream}, configured. */
    public Object parse(InputStream in) {
        return parse(DingJson.readAll(in, config));
    }

    /** Parse a {@link Reader} into a {@link JSONObject}, configured. */
    public JSONObject parseObject(Reader reader) {
        return parseObject(DingJson.readAll(reader, config));
    }

    /** Parse a {@link Reader} into a typed bean, configured. */
    public <T> T parseObject(Reader reader, Class<T> clazz) {
        return parseObject(DingJson.readAll(reader, config), clazz);
    }

    /** Parse a {@link Reader} into an arbitrary generic type, configured. */
    public <T> T parseObject(Reader reader, TypeReference<T> typeRef) {
        return parseObject(DingJson.readAll(reader, config), typeRef);
    }

    /** Parse a {@link Reader} into a {@link JSONArray}, configured. */
    public JSONArray parseArray(Reader reader) {
        return parseArray(DingJson.readAll(reader, config));
    }

    /** Parse a {@link Reader} into a typed list, configured. */
    public <T> List<T> parseArray(Reader reader, Class<T> elementType) {
        return parseArray(DingJson.readAll(reader, config), elementType);
    }

    /** Parse a {@link Reader} into a list with a generic element type, configured. */
    public <T> List<T> parseArray(Reader reader, TypeReference<T> elementType) {
        return parseArray(DingJson.readAll(reader, config), elementType);
    }

    /** Parse any JSON value from a {@link Reader}, configured. */
    public Object parse(Reader reader) {
        return parse(DingJson.readAll(reader, config));
    }

    /** Parse a UTF-8 file into a {@link JSONObject}, configured. */
    public JSONObject parseObject(File file) {
        return parseObject(DingJson.readAll(file, config));
    }

    /** Parse a UTF-8 file into a typed bean, configured. */
    public <T> T parseObject(File file, Class<T> clazz) {
        return parseObject(DingJson.readAll(file, config), clazz);
    }

    /** Parse a UTF-8 file into an arbitrary generic type, configured. */
    public <T> T parseObject(File file, TypeReference<T> typeRef) {
        return parseObject(DingJson.readAll(file, config), typeRef);
    }

    /** Parse a UTF-8 file into a {@link JSONArray}, configured. */
    public JSONArray parseArray(File file) {
        return parseArray(DingJson.readAll(file, config));
    }

    /** Parse a UTF-8 file into a typed list, configured. */
    public <T> List<T> parseArray(File file, Class<T> elementType) {
        return parseArray(DingJson.readAll(file, config), elementType);
    }

    /** Parse a UTF-8 file into a list with a generic element type, configured. */
    public <T> List<T> parseArray(File file, TypeReference<T> elementType) {
        return parseArray(DingJson.readAll(file, config), elementType);
    }

    /** Parse any JSON value from a UTF-8 file, configured. */
    public Object parse(File file) {
        return parse(DingJson.readAll(file, config));
    }

    // ── Write (file / stream), configured ──────────────────────────

    /** Serialize to a file (UTF-8, compact), configured. */
    public void writeTo(File file, Object object) {
        writeTo(file, object, false);
    }

    /** Serialize to a file (UTF-8), optionally pretty-printed, configured. */
    public void writeTo(File file, Object object, boolean pretty) {
        if (file == null) {
            throw new JSONException("File must not be null");
        }
        try {
            Files.write(file.toPath(), toJSONString(object, pretty).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JSONException("Failed to write file " + file, e);
        }
    }

    /** Serialize to an output stream (UTF-8, compact), configured. */
    public void writeTo(OutputStream out, Object object) {
        writeTo(out, object, false);
    }

    /** Serialize to an output stream (UTF-8), optionally pretty-printed, configured. */
    public void writeTo(OutputStream out, Object object, boolean pretty) {
        if (out == null) {
            throw new JSONException("Output stream must not be null");
        }
        try {
            out.write(toJSONString(object, pretty).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JSONException("Failed to write output stream", e);
        }
    }
}
