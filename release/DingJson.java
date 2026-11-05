package com.dingjson;

import sun.misc.Unsafe;
import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.*;
import java.lang.annotation.*;

/**
 * DingJson — single-file edition.
 * <p>
 * All types are nested inside this class; usage differs slightly from the
 * multi-file artifact:
 * <pre>{@code
 * DingJson.JSONObject obj = DingJson.parseObject("...");
 * User u = DingJson.parseObject(json, User.class);
 * DingJson.JSONArray arr = DingJson.parseArray("[1,2]");
 * List<User> users = DingJson.parseObject(json, new DingJson.TypeReference<List<User>>() {});
 * // annotations: @DingJson.JSONField(name = "user_name")
 * // exceptions:   catch (DingJson.JSONException e)
 * // config:       DingJson.mapper(DingJson.DingJsonConfig.builder()...build())
 * // streaming:    DingJson.JSONReader r = DingJson.createReader(json);
 * //               DingJson.JSONWriter w = DingJson.JSONWriter.create();
 * //               DingJson.JSONPath.eval(tree, "$.a[0]")
 * }</pre>
 * <p>
 * Generated from the multi-file source; keep them in sync.
 */
public final class DingJson {

    // ── JSONException.java ──────────────────────────────

/**
 * Runtime exception thrown by DingJson when JSON parsing or serialization fails.
 * Unchecked to keep the API clean — callers can catch if they wish.
 */
    public static class JSONException extends RuntimeException {

    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }
}

    // ── JsonElement.java ──────────────────────────────


/**
 * Abstract base class for all JSON element types in DingJson.
 * <p>
 * Provides type-checking methods and type-specific accessors
 * so callers can navigate the JSON tree without resorting to
 * {@code instanceof} checks.
 * <p>
 * Currently the only concrete subclasses are {@link JSONObject}
 * and {@link JSONArray}. Future versions may add {@code JsonPrimitive}
 * and {@code JsonNull} for fully typed tree representation.
 *
 * @see JSONObject
 * @see JSONArray
 */
    public static abstract class JsonElement {

    // ── Type checks ──────────────────────────────────────────────

    /**
     * Returns {@code true} if this element is a {@link JSONObject}.
     */
    public boolean isJsonObject() {
        return false;
    }

    /**
     * Returns {@code true} if this element is a {@link JSONArray}.
     */
    public boolean isJsonArray() {
        return false;
    }

    // ── Type-specific accessors ──────────────────────────────────

    /**
     * Convenience cast to {@link JSONObject}.
     *
     * @throws JSONException if this is not a JSON object
     */
    public JSONObject getAsJsonObject() {
        throw new JSONException("Not a JSON object: " + getClass().getName());
    }

    /**
     * Convenience cast to {@link JSONArray}.
     *
     * @throws JSONException if this is not a JSON array
     */
    public JSONArray getAsJsonArray() {
        throw new JSONException("Not a JSON array: " + getClass().getName());
    }

    // ── Serialization ────────────────────────────────────────────

    /**
     * Returns the compact JSON string representation of this element.
     * <p>
     * Subclasses must implement this to delegate to
     * {@link DingJson#toJSONString(Object)}.
     */
    @Override
    public abstract String toString();

    /**
     * Returns the pretty-printed JSON string representation of this element
     * (2-space indent).
     */
    public String toPrettyString() {
        return DingJson.toJSONString(this, true);
    }

    // ── Equality ─────────────────────────────────────────────────

    @Override
    public abstract boolean equals(Object o);

    @Override
    public abstract int hashCode();
}

    // ── JSONObject.java ──────────────────────────────



/**
 * A JSON object backed by a {@link LinkedHashMap}, preserving insertion order.
 * Provides typed accessor methods for convenient data retrieval.
 */
    public static class JSONObject extends JsonElement implements Map<String, Object> {

    private final LinkedHashMap<String, Object> map;

    public JSONObject() {
        this.map = new LinkedHashMap<>();
    }

    public JSONObject(Map<String, Object> map) {
        this.map = new LinkedHashMap<>(Math.max(16, map.size() * 2));
        // Copy entry-by-entry so non-String keys are stringified instead of
        // triggering a ClassCastException (JSON object keys must be strings).
        for (Map.Entry<String, Object> e : map.entrySet()) {
            this.map.put(e.getKey() == null ? "null" : String.valueOf(e.getKey()), e.getValue());
        }
    }

    /**
     * Creates a JSON object from alternating key/value pairs:
     * <pre>{@code
     * JSONObject o = JSONObject.of("name", "Ding", "age", 3);
     * }</pre>
     *
     * @throws JSONException if the number of arguments is odd or a key is not a String
     */
    public static JSONObject of(Object... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new JSONException("of() requires an even number of arguments (key/value pairs)");
        }
        JSONObject obj = new JSONObject();
        for (int i = 0; i < kvPairs.length; i += 2) {
            Object key = kvPairs[i];
            if (!(key instanceof String)) {
                throw new JSONException("of() keys must be strings but got "
                        + (key == null ? "null" : key.getClass().getSimpleName()));
            }
            obj.put((String) key, kvPairs[i + 1]);
        }
        return obj;
    }

    // ── Type checks (JsonElement overrides) ────────────────────────

    @Override
    public boolean isJsonObject() {
        return true;
    }

    @Override
    public JSONObject getAsJsonObject() {
        return this;
    }

    // ── Typed getters ──────────────────────────────────────────────

    public String getString(String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    public Integer getInteger(String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }

    public Long getLong(String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }

    public Double getDouble(String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Double) return (Double) v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }

    public Boolean getBoolean(String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    public JSONObject getJSONObject(String key) {
        Object v = map.get(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray getJSONArray(String key) {
        Object v = map.get(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getObject(String key, Class<T> clazz) {
        Object v = map.get(key);
        if (v == null) return null;
        if (clazz.isInstance(v)) return clazz.cast(v);
        if (v instanceof JSONObject) {
            return JSONSerializer.deserialize((JSONObject) v, clazz);
        }
        throw new JSONException("Cannot convert " + v.getClass() + " to " + clazz);
    }

    // ── Map interface ──────────────────────────────────────────────

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return map.get(key);
    }

    @Override
    public Object put(String key, Object value) {
        return map.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return map.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        map.putAll(m);
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public Set<String> keySet() {
        return map.keySet();
    }

    @Override
    public Collection<Object> values() {
        return map.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return map.entrySet();
    }

    @Override
    public String toString() {
        return DingJson.toJSONString(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JSONObject)) return false;
        JSONObject that = (JSONObject) o;
        return map.equals(that.map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }
}

    // ── JSONArray.java ──────────────────────────────



/**
 * A JSON array backed by an {@link ArrayList}, preserving insertion order.
 * Provides typed accessor methods for convenient data retrieval.
 */
    public static class JSONArray extends JsonElement implements List<Object> {

    private final List<Object> list;

    public JSONArray() {
        this.list = new ArrayList<>();
    }

    public JSONArray(int initialCapacity) {
        this.list = new ArrayList<>(initialCapacity);
    }

    public JSONArray(List<?> list) {
        this.list = new ArrayList<>(list);
    }

    /** Creates a JSON array from the given values. */
    public static JSONArray of(Object... values) {
        JSONArray arr = new JSONArray();
        for (Object v : values) {
            arr.add(v);
        }
        return arr;
    }

    // ── Type checks (JsonElement overrides) ────────────────────────

    @Override
    public boolean isJsonArray() {
        return true;
    }

    @Override
    public JSONArray getAsJsonArray() {
        return this;
    }

    // ── Typed getters ──────────────────────────────────────────────

    public String getString(int index) {
        Object v = list.get(index);
        return v != null ? v.toString() : null;
    }

    public Integer getInteger(int index) {
        Object v = list.get(index);
        if (v == null) return null;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(v.toString());
    }

    public Long getLong(int index) {
        Object v = list.get(index);
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }

    public Double getDouble(int index) {
        Object v = list.get(index);
        if (v == null) return null;
        if (v instanceof Double) return (Double) v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }

    public Boolean getBoolean(int index) {
        Object v = list.get(index);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    public JSONObject getJSONObject(int index) {
        Object v = list.get(index);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray getJSONArray(int index) {
        Object v = list.get(index);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    // ── List interface ─────────────────────────────────────────────

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return list.contains(o);
    }

    @Override
    public Iterator<Object> iterator() {
        return list.iterator();
    }

    @Override
    public Object[] toArray() {
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }

    @Override
    public boolean add(Object o) {
        return list.add(o);
    }

    @Override
    public boolean remove(Object o) {
        return list.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<?> c) {
        return list.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<?> c) {
        return list.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return list.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return list.retainAll(c);
    }

    @Override
    public void clear() {
        list.clear();
    }

    @Override
    public Object get(int index) {
        return list.get(index);
    }

    @Override
    public Object set(int index, Object element) {
        return list.set(index, element);
    }

    @Override
    public void add(int index, Object element) {
        list.add(index, element);
    }

    @Override
    public Object remove(int index) {
        return list.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return list.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return list.lastIndexOf(o);
    }

    @Override
    public ListIterator<Object> listIterator() {
        return list.listIterator();
    }

    @Override
    public ListIterator<Object> listIterator(int index) {
        return list.listIterator(index);
    }

    @Override
    public List<Object> subList(int fromIndex, int toIndex) {
        return list.subList(fromIndex, toIndex);
    }

    @Override
    public String toString() {
        return DingJson.toJSONString(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JSONArray)) return false;
        JSONArray that = (JSONArray) o;
        return list.equals(that.list);
    }

    @Override
    public int hashCode() {
        return list.hashCode();
    }
}

    // ── JSONField.java ──────────────────────────────


/**
 * Configures how a bean field is serialized / deserialized.
 * <p>
 * Usage:
 * <pre>{@code
 * public class User {
 *     @JSONField(name = "user_name")          // rename in JSON
 *     public String userName;
 *
 *     @JSONField(serialize = false)           // write-only field
 *     public String passwordHash;
 *
 *     @JSONField(deserialize = false)         // read-only field
 *     public long createdAt;
 *
 *     @JSONField(format = "yyyy-MM-dd HH:mm:ss")
 *     public Date updatedAt;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
    public @interface JSONField {

    /**
     * The JSON property name (overrides the Java field name).
     */
    String name() default "";

    /**
     * Whether the field is included in JSON output. Default {@code true}.
     */
    boolean serialize() default true;

    /**
     * Whether the field is read from JSON input. Default {@code true}.
     */
    boolean deserialize() default true;

    /**
     * Date/time pattern applied to {@link java.util.Date} (and subclass)
     * fields, e.g. {@code "yyyy-MM-dd HH:mm:ss"}. When empty (default), dates
     * use epoch millis.
     */
    String format() default "";
}

    // ── TypeReference.java ──────────────────────────────



/**
 * Captures a generic type at compile time via an anonymous subclass.
 * <p>
 * Usage mirrors Gson's {@code TypeToken}:
 * <pre>{@code
 * List<Person> people = DingJson.parseObject(json, new TypeReference<List<Person>>() {});
 * Map<String, Address> contacts = DingJson.parseObject(json, new TypeReference<Map<String, Address>>() {});
 * }</pre>
 * <p>
 * The subclass must supply a concrete parameterized supertype — a bare
 * {@code new TypeReference<>() {}} is not usable.
 *
 * @param <T> the target type
 */
    public static abstract class TypeReference<T> {

    private final Type type;

    protected TypeReference() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType)) {
            throw new JSONException("TypeReference must be created with a concrete generic type, "
                    + "e.g. new TypeReference<List<String>>() {}");
        }
        this.type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
    }

    /**
     * Returns the captured {@link Type}.
     */
    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "TypeReference<" + type + ">";
    }
}

    // ── DingJsonConfig.java ──────────────────────────────

/**
 * Immutable configuration for a {@link DingJsonMapper}.
 * <p>
 * Build with {@link #builder()} or {@link #builder(DingJsonConfig)} to inherit
 * defaults, then hand the result to {@link DingJson#mapper(DingJsonConfig)}.
 * The static {@code DingJson} API always uses the defaults.
 */
    public static final class DingJsonConfig {

    /** Emit fields whose value is {@code null}. Default {@code true}. */
    private final boolean writeNullFields;

    /** Global date pattern (applies to {@code Date} fields without a {@code @JSONField(format)}). */
    private final String dateFormat;

    /** Throw instead of emitting {@code null} for NaN/±Infinity. Default {@code false}. */
    private final boolean failOnNonFinite;

    /** Match bean field names case-insensitively on input. Default {@code false}. */
    private final boolean caseInsensitiveFields;

    /** Maximum length of a single JSON string value (0 = unlimited). */
    private final int maxStringLength;

    /** Maximum number of elements in a JSON array (0 = unlimited). */
    private final int maxArraySize;

    /** Maximum number of key-value pairs in a JSON object (0 = unlimited). */
    private final int maxObjectSize;

    /** Maximum number of bytes read from byte[]/InputStream/Reader/File inputs (0 = unlimited). */
    private final int maxInputBytes;

    private DingJsonConfig(boolean writeNullFields, String dateFormat,
                           boolean failOnNonFinite, boolean caseInsensitiveFields,
                           int maxStringLength, int maxArraySize, int maxObjectSize,
                           int maxInputBytes) {
        this.writeNullFields = writeNullFields;
        this.dateFormat = dateFormat;
        this.failOnNonFinite = failOnNonFinite;
        this.caseInsensitiveFields = caseInsensitiveFields;
        this.maxStringLength = maxStringLength;
        this.maxArraySize = maxArraySize;
        this.maxObjectSize = maxObjectSize;
        this.maxInputBytes = maxInputBytes;
    }

    /**
     * Defaults: write nulls, epoch-millis dates, NaN→null, exact field names,
     * 10 MB max string, 10M max array elements, 1M max object keys,
     * 10 MB max input size for the byte[]/stream/reader/file entry points.
     */
    public static DingJsonConfig defaults() {
        return new DingJsonConfig(true, null, false, false,
                10_000_000, 10_000_000, 1_000_000, 10_000_000);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public static Builder builder(DingJsonConfig base) {
        return new Builder(base);
    }

    public boolean writeNullFields() {
        return writeNullFields;
    }

    public String dateFormat() {
        return dateFormat;
    }

    public boolean failOnNonFinite() {
        return failOnNonFinite;
    }

    public boolean caseInsensitiveFields() {
        return caseInsensitiveFields;
    }

    public int maxStringLength() {
        return maxStringLength;
    }

    public int maxArraySize() {
        return maxArraySize;
    }

    public int maxObjectSize() {
        return maxObjectSize;
    }

    public int maxInputBytes() {
        return maxInputBytes;
    }

    /** Builder for {@link DingJsonConfig}. */
    public static final class Builder {
        private boolean writeNullFields;
        private String dateFormat;
        private boolean failOnNonFinite;
        private boolean caseInsensitiveFields;
        private int maxStringLength;
        private int maxArraySize;
        private int maxObjectSize;
        private int maxInputBytes;

        private Builder(DingJsonConfig base) {
            this.writeNullFields = base.writeNullFields;
            this.dateFormat = base.dateFormat;
            this.failOnNonFinite = base.failOnNonFinite;
            this.caseInsensitiveFields = base.caseInsensitiveFields;
            this.maxStringLength = base.maxStringLength;
            this.maxArraySize = base.maxArraySize;
            this.maxObjectSize = base.maxObjectSize;
            this.maxInputBytes = base.maxInputBytes;
        }

        /** Omit {@code null} fields from output (like Jackson's {@code @JsonInclude(NON_NULL)}). */
        public Builder writeNullFields(boolean value) {
            this.writeNullFields = value;
            return this;
        }

        /** Global date pattern, e.g. {@code "yyyy-MM-dd HH:mm:ss"}. */
        public Builder dateFormat(String pattern) {
            this.dateFormat = pattern;
            return this;
        }

        /** Throw {@link JSONException} on NaN/±Infinity instead of writing {@code null}. */
        public Builder failOnNonFinite(boolean value) {
            this.failOnNonFinite = value;
            return this;
        }

        /** Match bean field names case-insensitively when reading JSON. */
        public Builder caseInsensitiveFields(boolean value) {
            this.caseInsensitiveFields = value;
            return this;
        }

        /**
         * Maximum length of a single JSON string value in characters.
         * Default 10 000 000 (10 MB). Set to 0 for unlimited.
         */
        public Builder maxStringLength(int value) {
            this.maxStringLength = value;
            return this;
        }

        /**
         * Maximum number of elements in a JSON array.
         * Default 10 000 000. Set to 0 for unlimited.
         */
        public Builder maxArraySize(int value) {
            this.maxArraySize = value;
            return this;
        }

        /**
         * Maximum number of key-value pairs in a JSON object.
         * Default 1 000 000. Set to 0 for unlimited.
         */
        public Builder maxObjectSize(int value) {
            this.maxObjectSize = value;
            return this;
        }

        /**
         * Maximum number of bytes read by the byte[]/InputStream/Reader/File
         * entry points (the Reader path counts characters as a proxy).
         * Guards against unbounded input — the size limits above only apply
         * after the input has been fully read into memory.
         * Default 10 000 000 (10 MB). Set to 0 for unlimited.
         */
        public Builder maxInputBytes(int value) {
            this.maxInputBytes = value;
            return this;
        }

        public DingJsonConfig build() {
            return new DingJsonConfig(writeNullFields, dateFormat, failOnNonFinite,
                    caseInsensitiveFields, maxStringLength, maxArraySize, maxObjectSize,
                    maxInputBytes);
        }
    }
}

    // ── JSONPath.java ──────────────────────────────



/**
 * JSONPath query support — a small, safe subset of the common JSONPath syntax:
 * <pre>{@code
 * Object v = JSONPath.eval(tree, "$.items[0].price");
 * List<Object> all = JSONPath.eval(tree, "$.items[*].name");
 * String name = JSONPath.eval(tree, "$.user.name", String.class);
 * }</pre>
 * Supported operators:
 * <ul>
 *   <li>{@code $} — root</li>
 *   <li>{@code .field} — object member</li>
 *   <li>{@code [n]} — array index (negative = from the end)</li>
 *   <li>{@code [*]} — all array elements (or all object values)</li>
 *   <li>{@code ..field} / {@code ..[n]} — recursive descent (search whole subtree)</li>
 * </ul>
 */
    public static final class JSONPath {

    private JSONPath() {
    }

    /** Evaluates a path against a parsed JSON tree. Returns {@code null} on no match. */
    public static Object eval(Object root, String path) {
        try {
            return evalInternal(root, path);
        } catch (StackOverflowError e) {
            // Deeply nested user-built trees could exhaust the stack before
            // any other bound — surface a catchable JSONException instead.
            throw new JSONException("JSONPath evaluation too deep", e);
        }
    }

    private static Object evalInternal(Object root, String path) {
        if (path == null || path.isEmpty()) {
            throw new JSONException("Empty JSONPath");
        }
        int i = 0;
        if (path.charAt(0) == '$') {
            i = 1;
        }
        Object current = root;
        while (i < path.length()) {
            // Wildcard-expanded list with remaining path: map over each element
            if (current instanceof List && !(current instanceof JSONArray)) {
                List<Object> results = new ArrayList<>();
                for (Object item : (List<?>) current) {
                    Object r = eval(item, path.substring(i));
                    if (r != null) {
                        results.add(r);
                    }
                }
                return results.isEmpty() ? null : results;
            }
            char c = path.charAt(i);
            if (c == '.') {
                if (i + 1 < path.length() && path.charAt(i + 1) == '.') {
                    // recursive descent
                    i += 2;
                    Segment seg = parseSegment(path, i, true);
                    i = seg.next;
                    current = recursiveSearch(current, seg);
                } else {
                    i++;
                    Segment seg = parseSegment(path, i, false);
                    i = seg.next;
                    current = applySegment(current, seg);
                }
            } else if (c == '[') {
                Segment seg = parseSegment(path, i, false);
                i = seg.next;
                current = applySegment(current, seg);
            } else {
                throw new JSONException("Unexpected character '" + c + "' in JSONPath at " + i);
            }
            if (current == null) {
                return null; // no match
            }
        }
        return current;
    }

    /** Evaluates and casts to a target type (numbers/strings via the normal coercion). */
    @SuppressWarnings("unchecked")
    public static <T> T eval(Object root, String path, Class<T> clazz) {
        Object v = eval(root, path);
        if (v == null) {
            return null;
        }
        if (clazz.isInstance(v)) {
            return clazz.cast(v);
        }
        if (clazz == String.class) {
            return (T) v.toString();
        }
        if (clazz == int.class || clazz == Integer.class) {
            return (T) Integer.valueOf(((Number) v).intValue());
        }
        if (clazz == long.class || clazz == Long.class) {
            return (T) Long.valueOf(((Number) v).longValue());
        }
        if (clazz == double.class || clazz == Double.class) {
            return (T) Double.valueOf(((Number) v).doubleValue());
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            return (T) Boolean.valueOf(v.toString());
        }
        throw new JSONException("Cannot convert " + v.getClass() + " to " + clazz);
    }

    // ── Internals ─────────────────────────────────────────────────

    private static final class Segment {
        final String name;    // field name, or "*", or null for index
        final int index;      // array index (when name == null and !wildcard)
        final int next;       // next position in the path string
        final boolean wildcard;

        Segment(String name, int index, int next, boolean wildcard) {
            this.name = name;
            this.index = index;
            this.next = next;
            this.wildcard = wildcard;
        }
    }

    /** Parses {@code .name} or {@code [n]} / {@code [*]} starting at {@code i}. */
    private static Segment parseSegment(String path, int i, boolean recursive) {
        char c = path.charAt(i);
        if (c == '[') {
            int end = path.indexOf(']', i + 1);
            if (end < 0) {
                throw new JSONException("Unclosed '[' in JSONPath at " + i);
            }
            String inner = path.substring(i + 1, end).trim();
            if (inner.equals("*")) {
                return new Segment(null, 0, end + 1, true);
            }
            try {
                int idx = Integer.parseInt(inner);
                return new Segment(null, idx, end + 1, false);
            } catch (NumberFormatException e) {
                // quoted member name: ["field"]
                if (inner.length() >= 2 && inner.charAt(0) == '"' && inner.charAt(inner.length() - 1) == '"') {
                    return new Segment(inner.substring(1, inner.length() - 1), 0, end + 1, false);
                }
                throw new JSONException("Invalid array index '" + inner + "' in JSONPath");
            }
        }
        // .field
        int end = i;
        while (end < path.length()) {
            char pc = path.charAt(end);
            if (pc == '.' || pc == '[') break;
            end++;
        }
        if (end == i) {
            throw new JSONException("Missing field name in JSONPath at " + i);
        }
        String name = path.substring(i, end);
        return new Segment(name, 0, end, false);
    }

    private static Object applySegment(Object current, Segment seg) {
        if (current instanceof JSONObject) {
            JSONObject obj = (JSONObject) current;
            if (seg.wildcard) {
                return new ArrayList<>(obj.values());
            }
            if (seg.name != null) {
                return obj.get(seg.name);
            }
            return null; // index on an object → no match
        }
        if (current instanceof JSONArray) {
            JSONArray arr = (JSONArray) current;
            if (seg.wildcard) {
                return new ArrayList<>(arr);
            }
            if (seg.name != null) {
                return null; // field name on an array → no match
            }
            int idx = seg.index;
            if (idx < 0) idx += arr.size();
            if (idx < 0 || idx >= arr.size()) {
                return null; // out of bounds → no match
            }
            return arr.get(idx);
        }
        return null; // leaf reached before the path ended
    }

    /** Recursive descent: search the whole subtree for the segment. */
    private static Object recursiveSearch(Object current, Segment seg) {
        Object direct = applySegment(current, seg);
        if (direct != null) {
            return direct;
        }
        if (current instanceof JSONObject) {
            for (Object v : ((JSONObject) current).values()) {
                Object found = recursiveSearch(v, seg);
                if (found != null) return found;
            }
        } else if (current instanceof JSONArray) {
            for (Object v : (JSONArray) current) {
                Object found = recursiveSearch(v, seg);
                if (found != null) return found;
            }
        }
        return null;
    }
}

    // ── JSONTokener.java ──────────────────────────────


/**
 * Low-level character source for the JSON parser.
 * <p>
 * Performance-oriented implementation:
 * <ul>
 *   <li>the constructor copies the input once and, in that same pass, validates
 *       that no raw control character (0x00-0x1F) appears inside a string
 *       (RFC 8259 §7) — so the hot {@link #readString()} path needs no checks</li>
 *   <li>{@link #readString()} locates the closing quote / first escape with the
 *       SIMD-optimized {@code String.indexOf} intrinsic and slices directly;
 *       the escape-slow-path only runs when a backslash actually exists</li>
 *   <li>{@link #nextNonWhitespace()} merges whitespace skipping and reading
 *       into a single hot loop with a {@code c > ' '} fast branch</li>
 *   <li>numbers are scanned by hand into a {@code long} (zero allocation);
 *       {@link #readIntScalar()} exposes the same scan without boxing</li>
 * </ul>
 */
    public static final class JSONTokener {

    private final String sourceString;
    private final char[] source;
    private final int length;
    private int pos;
    private final DingJsonConfig config;

    // Scratch fields no longer used (readNumber/readIntScalar are fully local)
    public JSONTokener(String source) {
        this(source, null);
    }

    /**
     * Creates a tokeniser with configurable limits. When {@code config} is
     * {@code null}, all limits are disabled (backward-compatible behaviour).
     */
    public JSONTokener(String source, DingJsonConfig config) {
        if (source == null) {
            throw new JSONException("Source string must not be null");
        }
        this.config = config;
        this.sourceString = source;
        // Copy into a buffer with a trailing sentinel (0 is never a digit), so
        // the number-scan loops can drop the per-iteration length check.
        int len = source.length();
        char[] buf = new char[len + 1];
        source.getChars(0, len, buf, 0);
        buf[len] = 0;
        this.source = buf;
        this.length = len;
        this.pos = 0;
        validateControlChars();
    }

    /**
     * Rejects raw control characters (0x00-0x1F) inside strings, as required by
     * RFC 8259 §7. Only the inside of strings needs this check — control
     * characters outside strings are rejected later by
     * {@link #nextNonWhitespace()}. String interiors are located with the
     * SIMD-optimized {@code String.indexOf}, so inputs with few strings (e.g.
     * large numeric arrays) cost almost nothing here. Escaped forms (\" \\
     * \\uXXXX ...) never trip the check because the escaped char is skipped
     * right after the backslash.
     */
    private void validateControlChars() {
        final int len = this.length;
        int i = 0;
        while (i < len) {
            int q = this.sourceString.indexOf('"', i);
            if (q < 0) {
                return; // no more strings
            }
            int p = q + 1; // first char after the opening quote
            while (p < len) {
                char c = this.sourceString.charAt(p);
                if (c == '"') {
                    break; // string ends
                }
                if (c == '\\') {
                    p += 2; // skip the escape sequence's escaped character
                    continue;
                }
                if (c < 0x20) {
                    throw new JSONException("Unescaped control character in string at position " + p);
                }
                p++;
            }
            i = p + 1;
        }
    }

    int getPosition() {
        return pos;
    }

    /**
     * Rewinds the read position (used when a lookahead character needs to be
     * handed back to the number/keyword parser).
     */
    void backup(int n) {
        pos -= n;
    }

    /**
     * Checks that the string about to be returned does not exceed
     * {@link DingJsonConfig#maxStringLength()}. Called from every
     * return path inside {@link #readString()} and {@link #decodeEscapes}.
     */
    private void checkStringLength(int len) {
        if (config != null && config.maxStringLength() > 0 && len > config.maxStringLength()) {
            throw new JSONException("String length " + len
                    + " exceeds maximum allowed " + config.maxStringLength());
        }
    }

    /**
     * Skips whitespace and returns the next non-whitespace character.
     * Whitespace per JSON spec: space, tab, LF, CR. Everything above 0x20
     * is returned immediately (fast path); control chars are returned too
     * so the caller can report them as unexpected.
     *
     * @throws JSONException on end of input
     */
    char nextNonWhitespace() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;
        while (p < len) {
            char c = src[p++];
            if (c > ' ') {
                this.pos = p;
                return c;
            }
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                this.pos = p;
                return c;
            }
        }
        throw new JSONException("Unexpected end of input");
    }

    /**
     * Reads a JSON string starting after the opening {@code "}.
     * The caller must have already consumed the opening quote.
     * <p>
     * Hybrid scanning: the first 16 chars are walked by hand (covers short
     * strings with zero intrinsic overhead), then {@code String.indexOf} — a
     * SIMD-optimized JDK intrinsic — jumps over the rest of a long string.
     * When no escape exists, the source is sliced directly with a single
     * allocation; the escape-slow-path only runs when a backslash exists.
     */
    String readString() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;

        // Small-step scan (short strings exit here with minimal overhead)
        int limit = Math.min(len, p + 16);
        while (p < limit) {
            char c = src[p];
            if (c == '"') {
                String result = this.sourceString.substring(this.pos, p);
                checkStringLength(p - this.pos);
                this.pos = p + 1;
                return result;
            }
            if (c == '\\') {
                return decodeEscapes(src, len, this.pos, p);
            }
            p++;
        }
        if (p >= len) {
            throw new JSONException("Unterminated string");
        }

        // Long string: SIMD jump to the closing quote / first escape
        int q = this.sourceString.indexOf('"', p);
        int b = this.sourceString.indexOf('\\', p);
        if (b < 0 || (q >= 0 && q < b)) {
            if (q < 0) {
                throw new JSONException("Unterminated string");
            }
            String result = this.sourceString.substring(this.pos, q);
            checkStringLength(q - this.pos);
            this.pos = q + 1;
            return result;
        }
        return decodeEscapes(src, len, this.pos, b);
    }

    /** Escape-decoding slow path, starting at the first backslash {@code b}. */
    private String decodeEscapes(char[] src, int len, int start, int b) {
        StringBuilder sb = new StringBuilder(b - start + 16);
        sb.append(src, start, b - start);
        int p = b;
        while (p < len) {
            char c = src[p++];
            if (c == '"') {
                this.pos = p;
                String result = sb.toString();
                checkStringLength(result.length());
                return result;
            }
            if (c == '\\') {
                if (p >= len) {
                    throw new JSONException("Unexpected end of input in string escape");
                }
                char escaped = src[p++];
                switch (escaped) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (p + 4 > len) {
                            throw new JSONException("Truncated unicode escape");
                        }
                        sb.append((char) ((hexValue(src[p]) << 12)
                                | (hexValue(src[p + 1]) << 8)
                                | (hexValue(src[p + 2]) << 4)
                                | hexValue(src[p + 3])));
                        p += 4;
                        break;
                    default:
                        throw new JSONException("Invalid escape character: \\" + escaped);
                }
            } else {
                // control characters were already validated in the constructor
                sb.append(c);
            }
        }
        throw new JSONException("Unterminated string");
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new JSONException("Invalid hex digit in unicode escape: " + c);
    }

    // ── Number scanning ───────────────────────────────────────────

    /**
     * Reads a JSON number (integer or decimal, possibly negative, possibly
     * scientific notation).
     * <p>
     * Integers (no fraction, no exponent) are accumulated into a {@code long}
     * by hand — zero String allocation. Decimals/exponents use a single
     * {@code Double.parseDouble} on the sliced source.
     * <p>
     * Kept fully local-variable (no field traffic) so the JIT can keep the
     * accumulator in registers — this is the hottest path in the parser.
     */
    Number readNumber() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;
        final int start = p;

        boolean negative = false;
        if (p < len && src[p] == '-') {
            negative = true;
            p++;
        }

        // Integer part — accumulate into a long by hand.
        // Negative numbers accumulate downward so that Long.MIN_VALUE
        // (-9223372036854775808, whose magnitude overflows long) parses correctly.
        long intVal = 0;
        boolean overflow = false;
        if (p < len && src[p] == '0') {
            p++;
        } else {
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number at position " + this.pos);
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                int digit = src[p] - '0';
                if (negative) {
                    if (intVal < (Long.MIN_VALUE + digit) / 10) {
                        overflow = true; // keep scanning; reported below
                    } else {
                        intVal = intVal * 10 - digit;
                    }
                } else {
                    if (intVal > (Long.MAX_VALUE - digit) / 10) {
                        overflow = true; // keep scanning; reported below
                    } else {
                        intVal = intVal * 10 + digit;
                    }
                }
                p++;
            }
        }

        boolean isDecimal = false;

        // Fractional part
        if (p < len && src[p] == '.') {
            isDecimal = true;
            p++;
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number: expected digit after decimal point");
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                p++;
            }
        }

        // Exponent part
        if (p < len && (src[p] == 'e' || src[p] == 'E')) {
            isDecimal = true;
            p++;
            if (p < len && (src[p] == '+' || src[p] == '-')) {
                p++;
            }
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number: expected digit in exponent");
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                p++;
            }
        }

        if (overflow) {
            throw new JSONException("Invalid number: value out of range");
        }

        this.pos = p;
        if (!isDecimal) {
            // intVal already carries the correct sign (accumulated downward for negatives)
            if (intVal >= Integer.MIN_VALUE && intVal <= Integer.MAX_VALUE) {
                return (int) intVal;
            }
            return intVal;
        }

        // Decimal / scientific — single allocation, JDK-optimized parser.
        // Values that overflow double come back as ±Infinity and would then
        // serialize as null (data loss) — keep full precision in a BigDecimal
        // instead (Gson-style). The strict scalar double path (readDouble)
        // rejects these explicitly.
        String text = new String(src, start, p - start);
        double d = Double.parseDouble(text);
        if (Double.isInfinite(d)) {
            try {
                return new java.math.BigDecimal(text);
            } catch (NumberFormatException e) {
                // absurd exponents (i_number_huge_exp.json) exceed even BigDecimal
                throw new JSONException("Invalid number: exponent out of range at position " + start);
            }
        }
        return d;
    }

    /**
     * Reads a JSON number that must be an integer, returning a raw {@code long}
     * with zero boxing — used by the streaming {@link JSONReader} array paths.
     * Independent implementation (all locals) so the hot loop stays register-bound.
     *
     * @throws JSONException if the number is decimal/scientific or out of long range
     */
    long readIntScalar() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;

        boolean negative = false;
        if (p < len && src[p] == '-') {
            negative = true;
            p++;
        }

        long intVal = 0;
        boolean overflow = false;
        if (p < len && src[p] == '0') {
            p++;
        } else {
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number at position " + this.pos);
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                int digit = src[p] - '0';
                if (negative) {
                    if (intVal < (Long.MIN_VALUE + digit) / 10) {
                        overflow = true;
                    } else {
                        intVal = intVal * 10 - digit;
                    }
                } else {
                    if (intVal > (Long.MAX_VALUE - digit) / 10) {
                        overflow = true;
                    } else {
                        intVal = intVal * 10 + digit;
                    }
                }
                p++;
            }
        }

        if (p < len && (src[p] == '.' || src[p] == 'e' || src[p] == 'E')) {
            throw new JSONException("Expected integer but got a decimal number");
        }

        if (overflow) {
            throw new JSONException("Invalid number: value out of range");
        }
        this.pos = p;
        return intVal;
    }

    /**
     * Scans a JSON number's grammar without building a value and returns its
     * raw source text. Used by the {@code BigDecimal}/{@code BigInteger} paths
     * where full precision matters — unlike {@link #readNumber()} this accepts
     * exponents that overflow {@code double} (e.g. {@code 1e999} is a valid
     * {@code BigDecimal} but an invalid {@code double}).
     */
    String readNumberRaw() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;
        final int start = p;

        if (p < len && src[p] == '-') {
            p++;
        }
        if (p < len && src[p] == '0') {
            p++;
        } else {
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number at position " + this.pos);
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                p++;
            }
        }
        if (p < len && src[p] == '.') {
            p++;
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number: expected digit after decimal point");
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                p++;
            }
        }
        if (p < len && (src[p] == 'e' || src[p] == 'E')) {
            p++;
            if (p < len && (src[p] == '+' || src[p] == '-')) {
                p++;
            }
            if (p >= len || src[p] < '0' || src[p] > '9') {
                throw new JSONException("Invalid number: expected digit in exponent");
            }
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                p++;
            }
        }

        String raw = new String(src, start, p - start);
        this.pos = p;
        return raw;
    }

    /**
     * Reads a keyword token ({@code true}, {@code false}, or {@code null})
     * and returns its parsed value.
     */
    Object readKeyword(String expected, Object value) {
        final char[] src = this.source;
        final int len = this.length;
        int end = this.pos + expected.length();
        if (end > len) {
            throw new JSONException("Expected '" + expected + "' at position " + this.pos);
        }
        for (int i = 0; i < expected.length(); i++) {
            if (src[this.pos + i] != expected.charAt(i)) {
                throw new JSONException("Expected '" + expected + "' at position " + this.pos);
            }
        }
        if (end < len) {
            char after = src[end];
            if (Character.isLetter(after) || Character.isDigit(after)) {
                throw new JSONException("Unexpected character after '" + expected + "' at position " + end);
            }
        }
        this.pos = end;
        return value;
    }

    boolean hasMore() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;
        while (p < len) {
            char c = src[p];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                p++;
            } else {
                this.pos = p;
                return true;
            }
        }
        return false;
    }
}

    // ── JSONParser.java ──────────────────────────────


/**
 * Recursive-descent JSON parser.
 * Converts a JSON string into a tree of {@link JSONObject}, {@link JSONArray},
 * String, Number, Boolean, or null.
 * <p>
 * Performance notes: single non-whitespace read per token (no double
 * whitespace skipping between {@code peek} and {@code next}), hand-rolled
 * integer parsing, and string fast-path slicing — see {@link JSONTokener}.
 */
    public static final class JSONParser {

    /**
     * Maximum nesting depth for objects/arrays. Guards against stack-overflow
     * denial-of-service via deeply nested payloads (Billion-Laughs style).
     * 512 matches the order of magnitude used by Jackson (1000) and org.json (500).
     */
    public static final int MAX_DEPTH = 512;

    private final JSONTokener tokener;
    private final DingJsonConfig config;

    public JSONParser(JSONTokener tokener) {
        this(tokener, null);
    }

    public JSONParser(JSONTokener tokener, DingJsonConfig config) {
        this.tokener = tokener;
        this.config = config;
    }

    /**
     * Parse the top-level JSON value.
     */
    public Object parse() {
        if (!tokener.hasMore()) {
            throw new JSONException("Empty input");
        }
        Object value = readValue(0);
        if (tokener.hasMore()) {
            throw new JSONException("Unexpected trailing content at position " + tokener.getPosition());
        }
        return value;
    }

    /**
     * Reads a single JSON value from the current position (tree-building path),
     * used by {@link JSONReader#readValue()}.
     */
    Object readNextValue() {
        return readValue(0);
    }

    /**
     * Validates and consumes a single JSON value at the current position without
     * building a tree (constant memory). Applies the same nesting-depth and size
     * limits as the tree path — used by {@code DingJson.isValid} and
     * {@link JSONReader#skipValue()}.
     */
    public void skipValue() {
        skipValue(0);
    }

    private void skipValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JSONException("Exceeded maximum nesting depth of " + MAX_DEPTH);
        }
        char c = tokener.nextNonWhitespace();
        switch (c) {
            case '"':
                tokener.readString();
                return;
            case '{':
                skipObject(depth);
                return;
            case '[':
                skipArray(depth);
                return;
            case 't':
                tokener.backup(1);
                tokener.readKeyword("true", Boolean.TRUE);
                return;
            case 'f':
                tokener.backup(1);
                tokener.readKeyword("false", Boolean.FALSE);
                return;
            case 'n':
                tokener.backup(1);
                tokener.readKeyword("null", null);
                return;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    tokener.backup(1); // hand the lookahead digit back to readNumber
                    tokener.readNumber();
                    return;
                }
                throw new JSONException("Unexpected character '" + c + "' at position "
                        + (tokener.getPosition() - 1));
        }
    }

    private void skipObject(int depth) {
        int keys = 0;
        int maxKeys = config != null ? config.maxObjectSize() : 0;
        char c = tokener.nextNonWhitespace();
        if (c == '}') {
            return;
        }
        if (c != '"') {
            throw new JSONException("Expected string key in object at position " + (tokener.getPosition() - 1));
        }
        while (true) {
            if (maxKeys > 0 && keys++ >= maxKeys) {
                throw new JSONException("Object size exceeds maximum allowed " + maxKeys);
            }
            tokener.readString();
            if (tokener.nextNonWhitespace() != ':') {
                throw new JSONException("Expected ':' after key in object at position " + tokener.getPosition());
            }
            skipValue(depth + 1);
            c = tokener.nextNonWhitespace();
            if (c == '}') {
                return;
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or '}' in object at position " + (tokener.getPosition() - 1));
            }
            if (tokener.nextNonWhitespace() != '"') {
                throw new JSONException("Expected string key in object at position " + tokener.getPosition());
            }
        }
    }

    private void skipArray(int depth) {
        int elems = 0;
        int maxElems = config != null ? config.maxArraySize() : 0;
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return;
        }
        tokener.backup(1); // hand the first element's start back to skipValue
        while (true) {
            if (maxElems > 0 && elems++ >= maxElems) {
                throw new JSONException("Array size exceeds maximum allowed " + maxElems);
            }
            skipValue(depth + 1);
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return;
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position " + (tokener.getPosition() - 1));
            }
        }
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JSONException("Exceeded maximum nesting depth of " + MAX_DEPTH);
        }
        char c = tokener.nextNonWhitespace();
        switch (c) {
            case '"':
                return tokener.readString();
            case '{':
                return readObject(depth + 1);
            case '[':
                return readArray(depth + 1);
            case 't':
                tokener.backup(1);
                return tokener.readKeyword("true", Boolean.TRUE);
            case 'f':
                tokener.backup(1);
                return tokener.readKeyword("false", Boolean.FALSE);
            case 'n':
                tokener.backup(1);
                return tokener.readKeyword("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    tokener.backup(1); // hand the lookahead digit back to readNumber
                    return tokener.readNumber();
                }
                throw new JSONException("Unexpected character '" + c + "' at position "
                        + (tokener.getPosition() - 1));
        }
    }

    private JSONObject readObject(int depth) {
        JSONObject obj = new JSONObject();
        char c = tokener.nextNonWhitespace();
        if (c == '}') {
            return obj;
        }
        if (c != '"') {
            throw new JSONException("Expected string key in object at position " + (tokener.getPosition() - 1));
        }
        while (true) {
            String key = tokener.readString();

            if (tokener.nextNonWhitespace() != ':') {
                throw new JSONException("Expected ':' after key in object at position " + tokener.getPosition());
            }

            if (config != null && config.maxObjectSize() > 0
                    && obj.size() >= config.maxObjectSize()) {
                throw new JSONException("Object size exceeds maximum allowed "
                        + config.maxObjectSize());
            }
            obj.put(key, readValue(depth));

            c = tokener.nextNonWhitespace();
            if (c == '}') {
                return obj;
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or '}' in object at position " + (tokener.getPosition() - 1));
            }
            if (tokener.nextNonWhitespace() != '"') {
                throw new JSONException("Expected string key in object at position " + tokener.getPosition());
            }
        }
    }

    private JSONArray readArray(int depth) {
        JSONArray arr = new JSONArray();
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return arr;
        }
        tokener.backup(1); // hand the first element's start back to readValue
        while (true) {
            if (config != null && config.maxArraySize() > 0
                    && arr.size() >= config.maxArraySize()) {
                throw new JSONException("Array size exceeds maximum allowed "
                        + config.maxArraySize());
            }
            arr.add(readValue(depth));
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return arr;
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position " + (tokener.getPosition() - 1));
            }
        }
    }
}

    // ── JSONReader.java ──────────────────────────────



/**
 * Streaming JSON reader: consumes a JSON document token by token, letting the
 * caller drive the structure ("predictive parsing") and skip parts it does not
 * need.
 * <p>
 * Typical usage:
 * <pre>{@code
 * JSONReader r = DingJson.createReader(text);
 * r.readBeginObject();
 * while (r.hasNext()) {
 *     String key = r.readKey();
 *     switch (key) {
 *         case "ids":  int[] ids = r.readIntArray(); break;   // zero boxing
 *         case "name": String name = r.readString(); break;
 *         default:     r.skipValue();
 *     }
 * }
 * r.readEndObject();
 * }</pre>
 * <p>
 * The {@code readXxxArray()} methods fill primitive arrays directly from the
 * input — no element boxing, no intermediate tree — which is dramatically
 * faster than {@code parseArray} for numeric payloads.
 * <p>
 * All reads validate the current token type and throw {@link JSONException}
 * on mismatch. Nesting depth is bounded by {@link JSONParser#MAX_DEPTH}.
 */
    public static final class JSONReader {

    private final JSONTokener tokener;
    private final JSONParser parser;
    private final DingJsonConfig config;

    public JSONReader(String text) {
        this(text, null);
    }

    public JSONReader(String text, DingJsonConfig config) {
        this.config = config;
        this.tokener = new JSONTokener(text, config);
        this.parser = new JSONParser(tokener, config);
    }

    // ── Structure ─────────────────────────────────────────────────

    /** Returns true if there is at least one more token (non-whitespace). */
    public boolean hasNext() {
        return tokener.hasMore();
    }

    /**
     * Returns the next non-whitespace character without consuming it.
     * Typical use: {@code while (r.peek() != '}') { ... }} to iterate object
     * members, or {@code while (r.peek() != ']') { ... }} for array elements.
     */
    public char peek() {
        char c = tokener.nextNonWhitespace();
        tokener.backup(1);
        return c;
    }

    /** Consumes the next character and requires it to be {@code c}. */
    private void expect(char c) {
        char got = tokener.nextNonWhitespace();
        if (got != c) {
            throw new JSONException("Expected '" + c + "' but got '" + got + "' at position "
                    + (tokener.getPosition() - 1));
        }
    }

    /** Consumes {@code {} and returns {@code this} (chainable). A member/element separator comma is skipped. */
    public JSONReader readBeginObject() {
        char c = tokener.nextNonWhitespace();
        if (c == ',') {
            c = tokener.nextNonWhitespace(); // skip separator
        }
        if (c != '{') {
            throw new JSONException("Expected '{' but got '" + c + "' at position "
                    + (tokener.getPosition() - 1));
        }
        return this;
    }

    /** Consumes {@code }}. */
    public JSONReader readEndObject() {
        expect('}');
        return this;
    }

    /** Consumes {@code [} and returns {@code this} (chainable). A member/element separator comma is skipped. */
    public JSONReader readBeginArray() {
        char c = tokener.nextNonWhitespace();
        if (c == ',') {
            c = tokener.nextNonWhitespace(); // skip separator
        }
        if (c != '[') {
            throw new JSONException("Expected '[' but got '" + c + "' at position "
                    + (tokener.getPosition() - 1));
        }
        return this;
    }

    /** Consumes {@code ]}. */
    public JSONReader readEndArray() {
        expect(']');
        return this;
    }

    /**
     * Consumes a member/element separator comma if the next token is one.
     * No-op otherwise — safe to call unconditionally between elements.
     */
    public JSONReader skipComma() {
        char c = tokener.nextNonWhitespace();
        if (c != ',') {
            tokener.backup(1);
        }
        return this;
    }

    /**
     * Reads a member key and its colon ({@code "key" :}). Requires the current
     * token to be a string. A member separator comma is skipped automatically.
     */
    public String readKey() {
        char c = tokener.nextNonWhitespace();
        if (c == ',') {
            c = tokener.nextNonWhitespace(); // skip member separator
        }
        if (c != '"') {
            throw new JSONException("Expected string key but got '" + c + "' at position "
                    + (tokener.getPosition() - 1));
        }
        String key = tokener.readString();
        char after = tokener.nextNonWhitespace();
        if (after != ':') {
            throw new JSONException("Expected ':' after key but got '" + after + "' at position "
                    + (tokener.getPosition() - 1));
        }
        return key;
    }

    /** Reads the value at the current position as a full JSON tree node. */
    public Object readValue() {
        return parser.readNextValue();
    }

    /** Reads the value at the current position, requiring it to be an object. */
    public JSONObject readJSONObject() {
        Object v = parser.readNextValue();
        if (!(v instanceof JSONObject)) {
            throw new JSONException("Expected JSON object but got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (JSONObject) v;
    }

    /** Reads the value at the current position, requiring it to be an array. */
    public JSONArray readJSONArray() {
        Object v = parser.readNextValue();
        if (!(v instanceof JSONArray)) {
            throw new JSONException("Expected JSON array but got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (JSONArray) v;
    }

    /** Skips the value at the current position without building a tree. */
    public void skipValue() {
        parser.skipValue();
    }

    /**
     * Fails if any trailing non-whitespace content remains after the current
     * position (i.e. the document was fully consumed).
     */
    public void ensureEnded() {
        if (tokener.hasMore()) {
            throw new JSONException("Unexpected trailing content at position " + tokener.getPosition());
        }
    }

    // ── Scalars ───────────────────────────────────────────────────

    /** Positions at the next token, then reads an integer scalar (no boxing). */
    private long nextIntScalar() {
        tokener.nextNonWhitespace();
        tokener.backup(1);
        return tokener.readIntScalar();
    }

    /** Positions at the next token, then reads a number. */
    private Number nextNumber() {
        tokener.nextNonWhitespace();
        tokener.backup(1);
        return tokener.readNumber();
    }

    /**
     * Reads a numeric value and returns its raw source text without parsing
     * it (preserves full precision — used for {@code BigDecimal}). Unlike the
     * parsed number paths, exponents beyond the {@code double} range are
     * accepted here (they are valid {@code BigDecimal} values).
     */
    public String readNumberRaw() {
        tokener.nextNonWhitespace();
        tokener.backup(1);
        return tokener.readNumberRaw();
    }

    /** Reads a string value. */
    public String readString() {
        char c = tokener.nextNonWhitespace();
        if (c != '"') {
            throw new JSONException("Expected string but got '" + c + "' at position "
                    + (tokener.getPosition() - 1));
        }
        return tokener.readString();
    }

    /** Reads a numeric value as {@code int} (fractional values truncate). */
    public int readInt() {
        return (int) readLong();
    }

    /** Reads an integer value as {@code long}. */
    public long readLong() {
        return nextIntScalar();
    }

    /** Reads a numeric value as {@code double}. */
    public double readDouble() {
        Number n = nextNumber();
        // Out-of-double-range decimals parse to BigDecimal in the tree path;
        // a double target cannot represent them — reject instead of Infinity.
        if (n instanceof java.math.BigDecimal) {
            throw new JSONException("Number out of range for double at position "
                    + (tokener.getPosition() - 1));
        }
        return n.doubleValue();
    }

    /** Reads {@code true} or {@code false}. */
    public boolean readBoolean() {
        char c = tokener.nextNonWhitespace();
        if (c == 't') {
            tokener.backup(1);
            return (Boolean) tokener.readKeyword("true", Boolean.TRUE);
        }
        if (c == 'f') {
            tokener.backup(1);
            return (Boolean) tokener.readKeyword("false", Boolean.FALSE);
        }
        throw new JSONException("Expected boolean but got '" + c + "' at position "
                + (tokener.getPosition() - 1));
    }

    /** Reads and discards a {@code null} literal. */
    public void readNull() {
        char c = tokener.nextNonWhitespace();
        if (c != 'n') {
            throw new JSONException("Expected null but got '" + c + "' at position "
                    + (tokener.getPosition() - 1));
        }
        tokener.backup(1);
        tokener.readKeyword("null", null);
    }

    // ── Typed arrays (zero boxing) ────────────────────────────────

    /** Doubles a growth size with overflow protection (only reachable with unlimited size config). */
    private static int grow(int n) {
        if (n <= 0 || n > (Integer.MAX_VALUE - 8) / 2) {
            throw new JSONException("Array too large");
        }
        return n * 2;
    }

    /**
     * Reads an array of integers directly into a freshly allocated {@code int[]}.
     * No boxing, no intermediate tree. Elements must be integers
     * (decimal/scientific notation is rejected).
     */
    public int[] readIntArray() {
        expect('[');
        int[] arr = new int[16];
        int n = 0;
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return new int[0];
        }
        tokener.backup(1);
        while (true) {
            long v = nextIntScalar();
            if (config != null && config.maxArraySize() > 0 && n >= config.maxArraySize()) {
                throw new JSONException("Array size exceeds maximum allowed " + config.maxArraySize());
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = (int) v;
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return n == arr.length ? arr : Arrays.copyOf(arr, n);
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position "
                        + (tokener.getPosition() - 1));
            }
        }
    }

    /** Reads an array of longs directly into a freshly allocated {@code long[]}. */
    public long[] readLongArray() {
        expect('[');
        long[] arr = new long[16];
        int n = 0;
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return new long[0];
        }
        tokener.backup(1);
        while (true) {
            long v = nextIntScalar();
            if (config != null && config.maxArraySize() > 0 && n >= config.maxArraySize()) {
                throw new JSONException("Array size exceeds maximum allowed " + config.maxArraySize());
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = v;
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return n == arr.length ? arr : Arrays.copyOf(arr, n);
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position "
                        + (tokener.getPosition() - 1));
            }
        }
    }

    /** Reads an array of doubles directly into a freshly allocated {@code double[]}. */
    public double[] readDoubleArray() {
        expect('[');
        double[] arr = new double[16];
        int n = 0;
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return new double[0];
        }
        tokener.backup(1);
        while (true) {
            Number num = nextNumber();
            if (num instanceof java.math.BigDecimal) {
                throw new JSONException("Number out of range for double at position "
                        + (tokener.getPosition() - 1));
            }
            double v = num.doubleValue();
            if (config != null && config.maxArraySize() > 0 && n >= config.maxArraySize()) {
                throw new JSONException("Array size exceeds maximum allowed " + config.maxArraySize());
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = v;
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return n == arr.length ? arr : Arrays.copyOf(arr, n);
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position "
                        + (tokener.getPosition() - 1));
            }
        }
    }

    /** Reads an array of booleans directly into a freshly allocated {@code boolean[]}. */
    public boolean[] readBooleanArray() {
        expect('[');
        boolean[] arr = new boolean[16];
        int n = 0;
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return new boolean[0];
        }
        tokener.backup(1);
        while (true) {
            boolean v = readBoolean();
            if (config != null && config.maxArraySize() > 0 && n >= config.maxArraySize()) {
                throw new JSONException("Array size exceeds maximum allowed " + config.maxArraySize());
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = v;
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return n == arr.length ? arr : Arrays.copyOf(arr, n);
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position "
                        + (tokener.getPosition() - 1));
            }
        }
    }

    /** Reads an array of strings directly into a freshly allocated {@code String[]}. */
    public String[] readStringArray() {
        expect('[');
        String[] arr = new String[16];
        int n = 0;
        char c = tokener.nextNonWhitespace();
        if (c == ']') {
            return new String[0];
        }
        tokener.backup(1);
        while (true) {
            String v = readString();
            if (config != null && config.maxArraySize() > 0 && n >= config.maxArraySize()) {
                throw new JSONException("Array size exceeds maximum allowed " + config.maxArraySize());
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = v;
            c = tokener.nextNonWhitespace();
            if (c == ']') {
                return n == arr.length ? arr : Arrays.copyOf(arr, n);
            }
            if (c != ',') {
                throw new JSONException("Expected ',' or ']' in array at position "
                        + (tokener.getPosition() - 1));
            }
        }
    }
}

    // ── UnsafeFields.java ──────────────────────────────



/**
 * Access to {@link Unsafe} object-field operations, used to reach bean fields
 * at direct-access speed (~0.25 ns/op vs ~3.4 ns via reflection — parity with
 * a hand-written bytecode {@code GETFIELD}, which is what fastjson2 generates
 * with ASM).
 * <p>
 * Falls back gracefully: if {@code Unsafe} is unavailable (future JDKs may
 * restrict it), {@link #AVAILABLE} is {@code false} and callers keep using
 * plain reflection — behavior is identical either way.
 */
    static final class UnsafeFields {

    private static final Unsafe UNSAFE;
    static final boolean AVAILABLE;

    static {
        Unsafe u = null;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (Throwable ignored) {
            // Unsafe unavailable — callers fall back to reflection
        }
        UNSAFE = u;
        AVAILABLE = u != null;
    }

    private UnsafeFields() {
    }

    static long offsetOf(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    static int getInt(Object bean, long offset) {
        return UNSAFE.getInt(bean, offset);
    }

    static long getLong(Object bean, long offset) {
        return UNSAFE.getLong(bean, offset);
    }

    static double getDouble(Object bean, long offset) {
        return UNSAFE.getDouble(bean, offset);
    }

    static float getFloat(Object bean, long offset) {
        return UNSAFE.getFloat(bean, offset);
    }

    static boolean getBoolean(Object bean, long offset) {
        return UNSAFE.getBoolean(bean, offset);
    }

    static short getShort(Object bean, long offset) {
        return UNSAFE.getShort(bean, offset);
    }

    static byte getByte(Object bean, long offset) {
        return UNSAFE.getByte(bean, offset);
    }

    static char getChar(Object bean, long offset) {
        return UNSAFE.getChar(bean, offset);
    }

    static Object getObject(Object bean, long offset) {
        return UNSAFE.getObject(bean, offset);
    }

    static void putInt(Object bean, long offset, int value) {
        UNSAFE.putInt(bean, offset, value);
    }

    static void putLong(Object bean, long offset, long value) {
        UNSAFE.putLong(bean, offset, value);
    }

    static void putDouble(Object bean, long offset, double value) {
        UNSAFE.putDouble(bean, offset, value);
    }

    static void putFloat(Object bean, long offset, float value) {
        UNSAFE.putFloat(bean, offset, value);
    }

    static void putBoolean(Object bean, long offset, boolean value) {
        UNSAFE.putBoolean(bean, offset, value);
    }

    static void putShort(Object bean, long offset, short value) {
        UNSAFE.putShort(bean, offset, value);
    }

    static void putByte(Object bean, long offset, byte value) {
        UNSAFE.putByte(bean, offset, value);
    }

    static void putChar(Object bean, long offset, char value) {
        UNSAFE.putChar(bean, offset, value);
    }

    static void putObject(Object bean, long offset, Object value) {
        UNSAFE.putObject(bean, offset, value);
    }
}

    // ── JSONWriter.java ──────────────────────────────


/**
 * Streaming JSON writer — the build-side counterpart of
 * {@link JSONReader}. Lets callers emit large documents
 * without building an intermediate tree:
 * <pre>{@code
 * JSONWriter w = JSONWriter.create();
 * w.beginObject();
 * w.key("name").value("Ding");
 * w.key("ids").beginArray().value(1).value(2).endArray();
 * w.endObject();
 * String json = w.toString();   // {"name":"Ding","ids":[1,2]}
 * }</pre>
 * <p>
 * Not thread-safe (single-threaded use, like {@code StringBuilder}).
 */
    public static final class JSONWriter {

    private final StringBuilder sb;
    private final int[] state;      // 0 = root, 1 = object, 2 = array
    private final boolean[] first;  // per-level "no element written yet" flag
    private int depth;

    private JSONWriter(int capacity) {
        this.sb = new StringBuilder(capacity);
        this.state = new int[64];
        this.first = new boolean[64];
        this.state[0] = 0;
        this.first[0] = true;
    }

    public static JSONWriter create() {
        return new JSONWriter(128);
    }

    public static JSONWriter create(int initialCapacity) {
        return new JSONWriter(initialCapacity);
    }

    // ── Structure ─────────────────────────────────────────────────

    public JSONWriter beginObject() {
        beforeValue(1);
        push(1);
        return this;
    }

    public JSONWriter endObject() {
        if (state[depth] == 3) {
            // key() was called but no value written — would emit invalid JSON like {"a":}
            throw new JSONException("endObject() after key() without a value");
        }
        if (state[depth] != 1) {
            throw new JSONException("endObject() without matching beginObject()");
        }
        sb.append('}');
        pop();
        return this;
    }

    public JSONWriter beginArray() {
        beforeValue(2);
        push(2);
        return this;
    }

    public JSONWriter endArray() {
        if (state[depth] != 2) {
            throw new JSONException("endArray() without matching beginArray()");
        }
        sb.append(']');
        pop();
        return this;
    }

    /** Writes an object member key (only valid directly inside an object). */
    public JSONWriter key(String key) {
        if (state[depth] != 1) {
            throw new JSONException("key() outside of an object");
        }
        if (!first[depth]) {
            sb.append(',');
        }
        sb.append(JSONSerializer.quote(key));
        sb.append(':');
        first[depth] = false;
        state[depth] = 3; // awaiting the value for this key (no extra stack level)
        return this;
    }

    // ── Values ────────────────────────────────────────────────────

    public JSONWriter value(String v) {
        beforeValue(0);
        sb.append(JSONSerializer.quote(v));
        return this;
    }

    public JSONWriter value(int v) {
        beforeValue(0);
        sb.append(v);
        return this;
    }

    public JSONWriter value(long v) {
        beforeValue(0);
        sb.append(v);
        return this;
    }

    public JSONWriter value(double v) {
        beforeValue(0);
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("null");
        } else {
            sb.append(v);
        }
        return this;
    }

    public JSONWriter value(boolean v) {
        beforeValue(0);
        sb.append(v);
        return this;
    }

    public JSONWriter valueNull() {
        beforeValue(0);
        sb.append("null");
        return this;
    }

    /** Writes any value (delegates to the serializer for complex objects). */
    public JSONWriter value(Object v) {
        if (v == null) {
            return valueNull();
        }
        if (v instanceof String) return value((String) v);
        if (v instanceof Integer) return value((int) (Integer) v);
        if (v instanceof Long) return value((long) (Long) v);
        if (v instanceof Double) return value((double) (Double) v);
        if (v instanceof Boolean) return value((boolean) (Boolean) v);
        beforeValue(0);
        JSONSerializer.serializeTo(v, sb);
        return this;
    }

    // ── Finish ────────────────────────────────────────────────────

    @Override
    public String toString() {
        if (depth != 0) {
            throw new JSONException("Unclosed structure: " + depth + " level(s) remaining");
        }
        return sb.toString();
    }

    // ── Internals ─────────────────────────────────────────────────

    private void push(int s) {
        if (depth + 1 >= state.length) {
            throw new JSONException("JSONWriter nesting too deep");
        }
        depth++;
        state[depth] = s;
        first[depth] = true;
    }

    private void pop() {
        depth--;
    }

    /**
     * Emits a separator if needed, then the structural character.
     * {@code marker} 0 = plain value, 1 = object start, 2 = array start.
     */
    private void beforeValue(int marker) {
        switch (state[depth]) {
            case 3: // awaiting value after key — no separator needed
                state[depth] = 1;
                if (marker != 0) sb.append(marker == 1 ? '{' : '[');
                return;
            case 1:
                throw new JSONException("Value written where a key was expected");
            case 2:
                if (!first[depth]) {
                    sb.append(',');
                }
                first[depth] = false;
                if (marker != 0) sb.append(marker == 1 ? '{' : '[');
                return;
            default: // root level
                if (!first[0]) {
                    throw new JSONException("Multiple root values");
                }
                first[0] = false;
                if (marker != 0) sb.append(marker == 1 ? '{' : '[');
        }
    }
}

    // ── JSONSerializer.java ──────────────────────────────



/**
 * Serializes Java objects to JSON strings and deserializes JSON trees into Java beans.
 * <p>
 * Performance notes:
 * <ul>
 *   <li>{@link #quote(String)} uses a hand-rolled hex table instead of {@code String.format}</li>
 *   <li>compact serialization pre-allocates the {@link StringBuilder} from the input size</li>
 *   <li>primitive and String arrays are serialized with typed loops (no reflection)</li>
 *   <li>bean fields are discovered once per class and cached (reflection-set once)</li>
 *   <li>pretty printing appends indentation characters directly (no intermediate Strings)</li>
 * </ul>
 */
    public static final class JSONSerializer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Serialization recursion bound: cycles recurse forever and trip this. */
    private static final int MAX_SERIALIZE_DEPTH = 512;

    /** 1 = character needs JSON escaping (128 ASCII entries; 0x80+ never needs it). */
    private static final boolean[] ESCAPE = new boolean[128];

    static {
        for (char c = 0; c < 0x20; c++) ESCAPE[c] = true; // control chars
        ESCAPE['"'] = true;
        ESCAPE['\\'] = true;
        // HTML-sensitive characters (XSS defense, mirrors Gson defaults)
        ESCAPE['<'] = true;
        ESCAPE['>'] = true;
        ESCAPE['&'] = true;
        ESCAPE['='] = true;
        ESCAPE['\''] = true;
    }

    /** Cached declared fields (non-static, non-transient), subclass-first order. */
    private static final Map<Class<?>, FieldInfo[]> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * A cached bean field with its {@link Unsafe} offset for direct access
     * (bytecode-level speed without generating classes).
     */
    private static final class FieldInfo {
        final String name;          // java field name
        final String jsonName;      // @JSONField(name) override, else field name
        final Class<?> type;
        final boolean serializable;   // @JSONField(serialize=false) skips output
        final boolean deserializable; // @JSONField(deserialize=false) skips input
        final String format;          // @JSONField(format) date pattern
        final Field field;          // fallback accessor
        final long offset;          // unsafe offset (valid when UnsafeFields.AVAILABLE)

        FieldInfo(Field field) {
            this.field = field;
            this.name = field.getName();
            this.type = field.getType();
            JSONField jf = field.getAnnotation(JSONField.class);
            this.jsonName = (jf != null && !jf.name().isEmpty()) ? jf.name() : field.getName();
            this.serializable = jf == null || jf.serialize();
            this.deserializable = jf == null || jf.deserialize();
            this.format = jf != null ? jf.format() : "";
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                // JPMS strong encapsulation (e.g. java.util.UUID internals):
                // public fields stay reachable via Field.get; private ones
                // degrade to null instead of crashing the serializer.
            }
            this.offset = UnsafeFields.AVAILABLE ? UnsafeFields.offsetOf(field) : 0;
        }
    }

    /** Kind of a primitive field, for typed Unsafe access without boxing. */
    private static final int KIND_REF = 0, KIND_INT = 1, KIND_LONG = 2, KIND_DOUBLE = 3,
            KIND_FLOAT = 4, KIND_BOOLEAN = 5, KIND_SHORT = 6, KIND_BYTE = 7, KIND_CHAR = 8;

    private static int kindOf(Class<?> t) {
        if (t == int.class) return KIND_INT;
        if (t == long.class) return KIND_LONG;
        if (t == double.class) return KIND_DOUBLE;
        if (t == float.class) return KIND_FLOAT;
        if (t == boolean.class) return KIND_BOOLEAN;
        if (t == short.class) return KIND_SHORT;
        if (t == byte.class) return KIND_BYTE;
        if (t == char.class) return KIND_CHAR;
        return KIND_REF;
    }

    private JSONSerializer() {
    }

    // ── Serialization ──────────────────────────────────────────────

    public static String toJSONString(Object value) {
        return toJSONString(value, (DingJsonConfig) null);
    }

    public static String toJSONString(Object value, DingJsonConfig config) {
        try {
            StringBuilder sb = new StringBuilder(estimateCapacity(value));
            serialize(value, sb, 0, config);
            return sb.toString();
        } catch (StackOverflowError e) {
            // The depth guard throws before the stack runs out on large stacks,
            // but small-stack threads (servlet containers, virtual threads) can
            // overflow first — surface a catchable JSONException instead of an Error.
            throw new JSONException("Circular reference or nesting too deep", e);
        }
    }

    public static String toJSONString(Object value, boolean pretty) {
        return toJSONString(value, pretty, null);
    }

    public static String toJSONString(Object value, boolean pretty, DingJsonConfig config) {
        if (!pretty) return toJSONString(value, config);
        try {
            StringBuilder sb = new StringBuilder(estimateCapacity(value));
            prettySerialize(value, sb, 0, 0, config);
            return sb.toString();
        } catch (StackOverflowError e) {
            throw new JSONException("Circular reference or nesting too deep", e);
        }
    }

    /** Rough size estimate to avoid StringBuilder reallocation on the hot path. */
    private static int estimateCapacity(Object value) {
        long est = 64;
        if (value instanceof JSONObject) est = ((JSONObject) value).size() * 24L + 16;
        else if (value instanceof JSONArray) est = ((JSONArray) value).size() * 16L + 16;
        else if (value instanceof String) est = ((String) value).length() + 16L;
        else if (value instanceof Map) est = ((Map<?, ?>) value).size() * 24L + 16;
        else if (value instanceof Collection) est = ((Collection<?>) value).size() * 16L + 16;
        return est > Integer.MAX_VALUE - 8 ? Integer.MAX_VALUE - 8 : (int) est;
    }

    /** Serializes a value into an existing buffer (used by JSONWriter). */
    static void serializeTo(Object value, StringBuilder sb) {
        try {
            serialize(value, sb, 0, null);
        } catch (StackOverflowError e) {
            throw new JSONException("Circular reference or nesting too deep", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void serialize(Object value, StringBuilder sb, DingJsonConfig config) {
        serialize(value, sb, 0, config);
    }

    /**
     * Serializes {@code value}. Cycle protection uses a recursion-depth bound
     * (aligned with the parser's {@link JSONParser#MAX_DEPTH}) instead of an
     * identity set: a genuine cycle recurses forever and trips the bound,
     * while re-sharing the same object on different branches (a DAG) stays
     * legal. This removes a per-container hash-set add/remove from the hot
     * path — the same strategy fastjson2 and simdjson rely on.
     */
    @SuppressWarnings("unchecked")
    private static void serialize(Object value, StringBuilder sb, int depth, DingJsonConfig config) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append(quote((String) value));
        } else if (value instanceof Number) {
            appendNumber((Number) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Character) {
            // char is a 1-char string in JSON (not a bean — Character's
            // internal fields are not serializable data)
            sb.append(quote(String.valueOf(value)));
        } else if (value instanceof Date) {
            // java.util.Date (and subclasses) → epoch millis (valid JSON number)
            sb.append(((Date) value).getTime());
        } else if (value instanceof Calendar) {
            sb.append(((Calendar) value).getTimeInMillis());
        } else if (value instanceof UUID) {
            sb.append(quote(value.toString()));
        } else if (value instanceof java.time.temporal.TemporalAccessor) {
            // LocalDate / LocalDateTime / Instant / OffsetDateTime → ISO-8601 string
            sb.append(quote(value.toString()));
        } else {
            if (depth > MAX_SERIALIZE_DEPTH) {
                throw new JSONException("Circular reference or nesting too deep: "
                        + value.getClass().getName());
            }
            if (value instanceof JSONObject) {
                serializeJSONObject((JSONObject) value, sb, depth + 1, config);
            } else if (value instanceof JSONArray) {
                serializeJSONArray((JSONArray) value, sb, depth + 1, config);
            } else if (value instanceof Map) {
                serializeMap((Map<?, ?>) value, sb, depth + 1, config);
            } else if (value instanceof Collection) {
                serializeCollection((Collection<?>) value, sb, depth + 1, config);
            } else if (value.getClass().isArray()) {
                serializeArray(value, sb, depth + 1, config);
            } else {
                // Java bean — reflect fields
                serializeBean(value, sb, depth + 1, config);
            }
        }
    }

    private static void serializeJSONObject(JSONObject obj, StringBuilder sb, int depth, DingJsonConfig config) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            if (config != null && !config.writeNullFields() && entry.getValue() == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(jsonKey(entry.getKey())));
            sb.append(':');
            serialize(entry.getValue(), sb, depth, config);
        }
        sb.append('}');
    }

    private static void serializeJSONArray(JSONArray arr, StringBuilder sb, int depth, DingJsonConfig config) {
        sb.append('[');
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(',');
            serialize(arr.get(i), sb, depth, config);
        }
        sb.append(']');
    }

    /**
     * Serializes a {@link Map} directly (no intermediate JSONObject copy).
     * Non-String keys are stringified like Jackson does — JSON object keys
     * must be strings, but a ClassCastException on a {@code Map<Integer,...>}
     * would be far worse.
     */
    private static void serializeMap(Map<?, ?> map, StringBuilder sb, int depth, DingJsonConfig config) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object v = entry.getValue();
            if (config != null && !config.writeNullFields() && v == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(jsonKey(entry.getKey())));
            sb.append(':');
            serialize(v, sb, depth, config);
        }
        sb.append('}');
    }

    /** Serializes a {@link Collection} directly (no intermediate JSONArray copy). */
    private static void serializeCollection(Collection<?> coll, StringBuilder sb, int depth, DingJsonConfig config) {
        sb.append('[');
        boolean first = true;
        for (Object v : coll) {
            if (!first) sb.append(',');
            first = false;
            serialize(v, sb, depth, config);
        }
        sb.append(']');
    }

    /** Converts a map key to a JSON object key string (never throws). */
    private static String jsonKey(Object key) {
        return key == null ? "null" : key.toString();
    }

    private static void serializeArray(Object array, StringBuilder sb, int depth, DingJsonConfig config) {
        Class<?> type = array.getClass().getComponentType();
        if (type == int.class) {
            int[] a = (int[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == long.class) {
            long[] a = (long[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == double.class) {
            double[] a = (double[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == boolean.class) {
            boolean[] a = (boolean[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == char.class) {
            char[] a = (char[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append((int) a[i]);
            }
            sb.append(']');
        } else if (type == float.class) {
            float[] a = (float[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == short.class) {
            short[] a = (short[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == byte.class) {
            byte[] a = (byte[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
        } else if (type == String.class) {
            String[] a = (String[]) array;
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(quote(a[i]));
            }
            sb.append(']');
        } else {
            sb.append('[');
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(',');
                serialize(Array.get(array, i), sb, depth, config);
            }
            sb.append(']');
        }
    }

    private static void serializeBean(Object bean, StringBuilder sb, int depth, DingJsonConfig config) {
        sb.append('{');
        boolean first = true;
        boolean skipNulls = config != null && !config.writeNullFields();
        for (FieldInfo f : fieldsOf(bean.getClass())) {
            if (!f.serializable) continue;
            Object preRead = null;
            boolean preReadValid = false;
            if (skipNulls && kindOf(f.type) == KIND_REF) {
                // Read reference fields exactly once: skip when null, reuse the
                // value below so the field is not read twice. Primitives can
                // never be null, so they stay on the no-boxing typed path.
                preRead = getFieldValue(bean, f);
                preReadValid = true;
                if (preRead == null) continue;
            }
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(f.jsonName));
            sb.append(':');
            writeFieldValue(bean, f, preRead, preReadValid, sb, depth, config);
        }
        sb.append('}');
    }

    /**
     * Writes one bean field: primitives go through typed Unsafe reads straight
     * into the output (no boxing); reference fields recurse into serialize.
     * Falls back to reflection when Unsafe is unavailable.
     * <p>
     * {@code preRead}/{@code preReadValid} carry a value already read by the
     * caller (null-skip check) so reference fields are not read twice.
     */
    private static void writeFieldValue(Object bean, FieldInfo f, Object preRead, boolean preReadValid,
                                        StringBuilder sb, int depth, DingJsonConfig config) {
        if (f.type == Date.class && (f.format.length() > 0
                || (config != null && config.dateFormat() != null))) {
            String pattern = f.format.length() > 0 ? f.format : config.dateFormat();
            Object v = preReadValid ? preRead : getFieldValue(bean, f);
            if (v == null) {
                sb.append("null");
            } else {
                sb.append(quote(dateFormatter(pattern).format(((Date) v).toInstant())));
            }
            return;
        }
        if (UnsafeFields.AVAILABLE) {
            try {
                switch (kindOf(f.type)) {
                    case KIND_INT:     sb.append(UnsafeFields.getInt(bean, f.offset)); return;
                    case KIND_LONG:    sb.append(UnsafeFields.getLong(bean, f.offset)); return;
                    case KIND_DOUBLE: {
                        double d = UnsafeFields.getDouble(bean, f.offset);
                        if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
                        else sb.append(d);
                        return;
                    }
                    case KIND_FLOAT: {
                        float fv = UnsafeFields.getFloat(bean, f.offset);
                        if (Float.isNaN(fv) || Float.isInfinite(fv)) sb.append("null");
                        else sb.append(fv);
                        return;
                    }
                    case KIND_BOOLEAN: sb.append(UnsafeFields.getBoolean(bean, f.offset)); return;
                    case KIND_SHORT:   sb.append(UnsafeFields.getShort(bean, f.offset)); return;
                    case KIND_BYTE:    sb.append(UnsafeFields.getByte(bean, f.offset)); return;
                    case KIND_CHAR: {
                        sb.append(quote(String.valueOf(UnsafeFields.getChar(bean, f.offset))));
                        return;
                    }
                    default:
                        serialize(preReadValid ? preRead : UnsafeFields.getObject(bean, f.offset),
                                sb, depth, config);
                        return;
                }
            } catch (Throwable t) {
                if (t instanceof JSONException || t instanceof Error) {
                    throw t; // real failures must propagate (e.g. cycle guard)
                }
                // Unsafe hiccup — fall through to reflection
            }
        }
        try {
            serialize(preReadValid ? preRead : f.field.get(bean), sb, depth, config);
        } catch (IllegalAccessException e) {
            serialize(null, sb, depth, config);
        }
    }

    /** Reads a field value (kind-aware Unsafe, boxing only when asked; reflection fallback). */
    private static Object getFieldValue(Object bean, FieldInfo f) {
        if (UnsafeFields.AVAILABLE) {
            try {
                switch (kindOf(f.type)) {
                    case KIND_INT:     return UnsafeFields.getInt(bean, f.offset);
                    case KIND_LONG:    return UnsafeFields.getLong(bean, f.offset);
                    case KIND_DOUBLE:  return UnsafeFields.getDouble(bean, f.offset);
                    case KIND_FLOAT:   return UnsafeFields.getFloat(bean, f.offset);
                    case KIND_BOOLEAN: return UnsafeFields.getBoolean(bean, f.offset);
                    case KIND_SHORT:   return UnsafeFields.getShort(bean, f.offset);
                    case KIND_BYTE:    return UnsafeFields.getByte(bean, f.offset);
                    case KIND_CHAR:    return UnsafeFields.getChar(bean, f.offset);
                    default:           return UnsafeFields.getObject(bean, f.offset);
                }
            } catch (Throwable t) {
                // fall through
            }
        }
        try {
            return f.field.get(bean);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static final Map<String, java.time.format.DateTimeFormatter> DATE_FORMATS =
            new ConcurrentHashMap<>();

    /** Thread-safe per-pattern formatter (unlike SimpleDateFormat, which needs a ThreadLocal). */
    private static java.time.format.DateTimeFormatter dateFormatter(String pattern) {
        return DATE_FORMATS.computeIfAbsent(pattern,
                p -> java.time.format.DateTimeFormatter.ofPattern(p)
                        .withZone(java.time.ZoneId.systemDefault()));
    }

    /**
     * Parses a date string with a formatter, mirroring SimpleDateFormat
     * semantics: date-only patterns resolve to start-of-day in the system
     * default zone, date-time patterns to that instant, offsets as given.
     * Unlike SimpleDateFormat this is strict (no 2023-02-30 → March rollover).
     */
    private static java.util.Date parseDate(String text, java.time.format.DateTimeFormatter fmt) {
        java.time.temporal.TemporalAccessor ta = fmt.parse(text);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.Instant instant;
        try {
            instant = java.time.ZonedDateTime.from(ta).toInstant();
        } catch (java.time.DateTimeException e1) {
            try {
                instant = java.time.LocalDateTime.from(ta).atZone(zone).toInstant();
            } catch (java.time.DateTimeException e2) {
                instant = java.time.LocalDate.from(ta).atStartOfDay(zone).toInstant();
            }
        }
        return java.util.Date.from(instant);
    }

    /**
     * Appends a number, mapping NaN/±Infinity to {@code null} — JSON has no
     * representation for them and emitting the literal would produce invalid
     * JSON that breaks downstream parsers (Gson-compatible strategy; a strict
     * throwing mode is available via configuration).
     */
    private static void appendNumber(Number n, StringBuilder sb) {
        if (n instanceof Double) {
            double d = (Double) n;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
                return;
            }
        } else if (n instanceof Float) {
            float f = (Float) n;
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                sb.append("null");
                return;
            }
        }
        sb.append(n);
    }

    // ── Pretty-print (2-space indent) ──────────────────────────────

    @SuppressWarnings("unchecked")
    private static void prettySerialize(Object value, StringBuilder sb, int indent) {
        prettySerialize(value, sb, indent, 0, null);
    }

    private static void prettySerialize(Object value, StringBuilder sb, int indent, int depth, DingJsonConfig config) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append(quote((String) value));
        } else if (value instanceof Number) {
            appendNumber((Number) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Character) {
            sb.append(quote(String.valueOf(value)));
        } else if (value instanceof Date) {
            // java.util.Date (and subclasses) → epoch millis (valid JSON number)
            sb.append(((Date) value).getTime());
        } else if (value instanceof Calendar) {
            sb.append(((Calendar) value).getTimeInMillis());
        } else if (value instanceof UUID) {
            sb.append(quote(value.toString()));
        } else if (value instanceof java.time.temporal.TemporalAccessor) {
            // LocalDate / LocalDateTime / Instant / OffsetDateTime → ISO-8601 string
            sb.append(quote(value.toString()));
        } else {
            if (depth > MAX_SERIALIZE_DEPTH) {
                throw new JSONException("Circular reference or nesting too deep: "
                        + value.getClass().getName());
            }
            if (value instanceof JSONObject) {
                prettySerializeJSONObject((JSONObject) value, sb, indent, depth + 1, config);
            } else if (value instanceof JSONArray) {
                prettySerializeJSONArray((JSONArray) value, sb, indent, depth + 1, config);
            } else if (value instanceof Map) {
                prettySerializeMap((Map<?, ?>) value, sb, indent, depth + 1, config);
            } else if (value instanceof Collection) {
                prettySerializeCollection((Collection<?>) value, sb, indent, depth + 1, config);
            } else if (value.getClass().isArray()) {
                    sb.append('[');
                    int len = Array.getLength(value);
                    for (int i = 0; i < len; i++) {
                        if (i > 0) sb.append(',');
                        sb.append('\n');
                        appendIndent(sb, indent + 2);
                        prettySerialize(Array.get(value, i), sb, indent + 2, depth, config);
                    }
                    if (len > 0) {
                        sb.append('\n');
                        appendIndent(sb, indent);
                    }
                    sb.append(']');
                } else {
                    prettySerializeBean(value, sb, indent, depth + 1, config);
                }
        }
    }

    private static void prettySerializeJSONObject(JSONObject obj, StringBuilder sb, int indent, int depth, DingJsonConfig config) {
        sb.append('{');
        if (obj.isEmpty()) {
            sb.append('}');
            return;
        }
        boolean hasField = false;
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            if (config != null && !config.writeNullFields() && entry.getValue() == null) continue;
            hasField = true;
            sb.append('\n');
            appendIndent(sb, indent + 2);
            sb.append(quote(jsonKey(entry.getKey()))).append(": ");
            prettySerialize(entry.getValue(), sb, indent + 2, depth, config);
            sb.append(',');
        }
        if (hasField) {
            sb.setCharAt(sb.length() - 1, '\n'); // replace trailing comma
            appendIndent(sb, indent);
        }
        sb.append('}');
    }

    private static void prettySerializeJSONArray(JSONArray arr, StringBuilder sb, int indent, int depth, DingJsonConfig config) {
        sb.append('[');
        if (arr.isEmpty()) {
            sb.append(']');
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            sb.append('\n');
            appendIndent(sb, indent + 2);
            prettySerialize(arr.get(i), sb, indent + 2, depth, config);
            sb.append(',');
        }
        sb.setCharAt(sb.length() - 1, '\n');
        appendIndent(sb, indent);
        sb.append(']');
    }

    /** Pretty-prints a {@link Map} directly (no intermediate copy). */
    private static void prettySerializeMap(Map<?, ?> map, StringBuilder sb, int indent, int depth, DingJsonConfig config) {
        sb.append('{');
        if (map.isEmpty()) {
            sb.append('}');
            return;
        }
        boolean hasField = false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (config != null && !config.writeNullFields() && entry.getValue() == null) continue;
            hasField = true;
            sb.append('\n');
            appendIndent(sb, indent + 2);
            sb.append(quote(jsonKey(entry.getKey()))).append(": ");
            prettySerialize(entry.getValue(), sb, indent + 2, depth, config);
            sb.append(',');
        }
        if (hasField) {
            sb.setCharAt(sb.length() - 1, '\n');
            appendIndent(sb, indent);
        }
        sb.append('}');
    }

    /** Pretty-prints a {@link Collection} directly (no intermediate copy). */
    private static void prettySerializeCollection(Collection<?> coll, StringBuilder sb, int indent, int depth, DingJsonConfig config) {
        sb.append('[');
        if (coll.isEmpty()) {
            sb.append(']');
            return;
        }
        for (Object v : coll) {
            sb.append('\n');
            appendIndent(sb, indent + 2);
            prettySerialize(v, sb, indent + 2, depth, config);
            sb.append(',');
        }
        sb.setCharAt(sb.length() - 1, '\n');
        appendIndent(sb, indent);
        sb.append(']');
    }

    private static void prettySerializeBean(Object bean, StringBuilder sb, int indent, int depth, DingJsonConfig config) {
        sb.append('{');
        boolean hasField = false;
        boolean skipNulls = config != null && !config.writeNullFields();
        for (FieldInfo f : fieldsOf(bean.getClass())) {
            if (!f.serializable) continue;
            Object preRead = null;
            boolean preReadValid = false;
            if (skipNulls && kindOf(f.type) == KIND_REF) {
                preRead = getFieldValue(bean, f);
                preReadValid = true;
                if (preRead == null) continue;
            }
            hasField = true;
            sb.append('\n');
            appendIndent(sb, indent + 2);
            sb.append(quote(f.jsonName)).append(": ");
            writeFieldValue(bean, f, preRead, preReadValid, sb, depth, config);
            sb.append(',');
        }
        if (hasField) {
            sb.setCharAt(sb.length() - 1, '\n');
            appendIndent(sb, indent);
        }
        sb.append('}');
    }

    private static void appendIndent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append(' ');
        }
    }

    // ── String quoting ─────────────────────────────────────────────

    /**
     * Quotes a string for JSON output.
     * <p>
     * Uses a 128-entry escape lookup table: safe runs of characters are copied
     * in bulk segments while only the (rare) characters that need escaping pay
     * the switch cost. No {@code String.format} allocation on the hot path.
     */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        final int len = s.length();
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            // Unpaired surrogates must be escaped: writing them raw would make
            // UTF-8 encoders silently replace them with '?', corrupting the
            // value on round-trip. Valid pairs stay raw (2 chars, one append).
            boolean loneSurrogate = Character.isSurrogate(c)
                    && !(Character.isHighSurrogate(c) && i + 1 < len
                         && Character.isLowSurrogate(s.charAt(i + 1)));
            if ((c < 0x80 && ESCAPE[c]) || loneSurrogate) {
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b");  break;
                    case '\f': sb.append("\\f");  break;
                    case '\n': sb.append("\\n");  break;
                    case '\r': sb.append("\\r");  break;
                    case '\t': sb.append("\\t");  break;
                    default:
                        sb.append("\\u");
                        sb.append(HEX[(c >> 12) & 0xF]);
                        sb.append(HEX[(c >> 8) & 0xF]);
                        sb.append(HEX[(c >> 4) & 0xF]);
                        sb.append(HEX[c & 0xF]);
                }
                i++;
            } else {
                // Copy the whole safe run in one bulk append.
                // Surrogate-aware: a valid pair is kept whole; a lone surrogate
                // ends the run so it gets escaped by the branch above.
                int j = i + 1;
                while (j < len) {
                    c = s.charAt(j);
                    if (c < 0x80 && ESCAPE[c]) break;
                    if (Character.isHighSurrogate(c)) {
                        if (j + 1 < len && Character.isLowSurrogate(s.charAt(j + 1))) {
                            j += 2;
                            continue;
                        }
                        break; // lone high surrogate
                    }
                    if (Character.isLowSurrogate(c)) break; // lone low surrogate
                    j++;
                }
                sb.append(s, i, j);
                i = j;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ── Bean field discovery (cached) ──────────────────────────────

    /**
     * Returns the serializable fields of a class (non-static, non-transient),
     * ordered subclass-first to match the original walk-up-the-hierarchy
     * behavior. Fields are cached per class and made accessible once.
     */
    private static FieldInfo[] fieldsOf(Class<?> clazz) {
        FieldInfo[] cached = FIELD_CACHE.get(clazz);
        if (cached != null) {
            return cached;
        }
        // computeIfAbsent keeps the discovery (including setAccessible) atomic
        // per class — concurrent callers can no longer build FieldInfo twice.
        return FIELD_CACHE.computeIfAbsent(clazz, JSONSerializer::discoverFields);
    }

    private static FieldInfo[] discoverFields(Class<?> clazz) {
        List<FieldInfo> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                if (field.isSynthetic()) continue; // anonymous/lambda class artifacts
                fields.add(new FieldInfo(field));
            }
            c = c.getSuperclass();
        }
        return fields.toArray(new FieldInfo[0]);
    }

    // ── Streaming deserialization (JSONReader → bean, no tree) ────

    private static final Map<Class<?>, Map<String, FieldInfo>> FIELD_MAP_CACHE = new ConcurrentHashMap<>();

    /**
     * Deserializes a JSON value from a streaming {@link JSONReader} into the
     * given target type, without building an intermediate JSON tree.
     * Scalar fields are read with the typed reader paths (no boxing on the
     * hot paths); nested beans recurse; generic/collection fields fall back
     * to the tree path so element types stay fully resolved.
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserializeFromReader(JSONReader reader, Class<T> targetType) {
        return deserializeFromReader(reader, targetType, null);
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserializeFromReader(JSONReader reader, Class<T> targetType, DingJsonConfig config) {
        try {
            return (T) deserializeFromReader(reader, targetType, 0, config);
        } catch (StackOverflowError e) {
            throw new JSONException("Nesting too deep to deserialize", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object deserializeFromReader(JSONReader reader, Class<?> targetType, int depth, DingJsonConfig config) {
        if (depth > JSONParser.MAX_DEPTH) {
            throw new JSONException("Exceeded maximum nesting depth of " + JSONParser.MAX_DEPTH);
        }
        if (reader.peek() == 'n') {
            reader.readNull();
            return null;
        }
        if (targetType == JSONObject.class) {
            return reader.readJSONObject();
        }
        if (targetType == JSONArray.class) {
            return reader.readJSONArray();
        }
        if (isScalar(targetType)) {
            return readScalar(reader, targetType);
        }
        return deserializeBean(reader, targetType, depth, config);
    }

    private static boolean isScalar(Class<?> type) {
        return type == String.class
                || type.isPrimitive()
                || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class
                || type == Boolean.class || type == Short.class
                || type == Byte.class || type == Character.class
                || type == Number.class
                || type == Date.class || type == java.sql.Date.class
                || type == Calendar.class || type == UUID.class
                || type == BigDecimal.class || type == BigInteger.class
                || type == LocalDate.class || type == LocalDateTime.class
                || type == Instant.class
                || type.isEnum();
    }

    private static Object readScalar(JSONReader reader, Class<?> type) {
        if (reader.peek() == 'n') {
            reader.readNull();
            return null;
        }
        char c = reader.peek();
        if (type == String.class) {
            // Loose coercion mirrors the tree path: any value becomes its toString
            if (c == '"') return reader.readString();
            return String.valueOf(reader.readValue());
        }
        if (type == boolean.class || type == Boolean.class) {
            if (c == 't' || c == 'f') return reader.readBoolean();
            return Boolean.parseBoolean(reader.readString());
        }
        if (type == int.class || type == Integer.class) {
            if (c == '-' || (c >= '0' && c <= '9')) return reader.readInt();
            return Integer.parseInt(reader.readString());
        }
        if (type == long.class || type == Long.class) {
            if (c == '-' || (c >= '0' && c <= '9')) return reader.readLong();
            return Long.parseLong(reader.readString());
        }
        if (type == double.class || type == Double.class) {
            if (c == '-' || (c >= '0' && c <= '9')) return reader.readDouble();
            return Double.parseDouble(reader.readString());
        }
        if (type == float.class || type == Float.class) {
            if (c == '-' || (c >= '0' && c <= '9')) return (float) reader.readDouble();
            return Float.parseFloat(reader.readString());
        }
        if (type == short.class || type == Short.class) {
            if (c == '-' || (c >= '0' && c <= '9')) return (short) reader.readInt();
            return Short.parseShort(reader.readString());
        }
        if (type == byte.class || type == Byte.class) {
            if (c == '-' || (c >= '0' && c <= '9')) return (byte) reader.readInt();
            return Byte.parseByte(reader.readString());
        }
        if (type == char.class || type == Character.class) {
            String s = reader.readString();
            if (s.length() != 1) {
                throw new JSONException("Cannot convert string to char: " + s);
            }
            return s.charAt(0);
        }
        if (type == Number.class) return reader.readValue();
        if (type == Date.class || type == java.sql.Date.class) {
            long millis = (c == '-' || (c >= '0' && c <= '9')) ? reader.readLong()
                    : Long.parseLong(reader.readString());
            return new Date(millis);
        }
        if (type == Calendar.class) {
            long millis = (c == '-' || (c >= '0' && c <= '9')) ? reader.readLong()
                    : Long.parseLong(reader.readString());
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(millis);
            return cal;
        }
        if (type == UUID.class) {
            return UUID.fromString(reader.readString());
        }
        if (type == BigDecimal.class) {
            // read the raw text — parsing to Double would lose precision
            String s = (c == '-' || (c >= '0' && c <= '9'))
                    ? reader.readNumberRaw() : reader.readString();
            return new BigDecimal(s);
        }
        if (type == BigInteger.class) {
            String s = (c == '-' || (c >= '0' && c <= '9'))
                    ? reader.readNumberRaw() : reader.readString();
            return new BigInteger(s);
        }
        if (type == LocalDate.class) return LocalDate.parse(reader.readString());
        if (type == LocalDateTime.class) return LocalDateTime.parse(reader.readString());
        if (type == Instant.class) return Instant.parse(reader.readString());
        if (type.isEnum()) {
            return Enum.valueOf((Class<Enum>) type, reader.readString());
        }
        return reader.readValue();
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializeBean(JSONReader reader, Class<T> targetType, int depth, DingJsonConfig config) {
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
            // Cannot instantiate — keep the raw value (best effort)
            return (T) reader.readValue();
        }
        try {
            reader.readBeginObject();
            T instance = targetType.getDeclaredConstructor().newInstance();
            Map<String, FieldInfo> fields = fieldMap(targetType);
            boolean caseInsensitive = config != null && config.caseInsensitiveFields();
            int maxKeys = config != null ? config.maxObjectSize() : 0;
            int keyCount = 0;
            while (reader.peek() != '}') {
                if (maxKeys > 0 && keyCount++ >= maxKeys) {
                    throw new JSONException("Object size exceeds maximum allowed " + maxKeys);
                }
                String key = reader.readKey();
                FieldInfo field = fields.get(key);
                if (field == null && caseInsensitive) {
                    field = fields.get(key.toLowerCase(Locale.ROOT));
                }
                if (field == null || !field.deserializable) {
                    reader.skipValue();
                } else {
                    putField(instance, field, readFieldValue(reader, field, depth + 1, config));
                }
            }
            reader.readEndObject();
            return instance;
        } catch (JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new JSONException("Cannot deserialize to " + targetType, e);
        }
    }

    private static Object readFieldValue(JSONReader reader, FieldInfo f, int depth, DingJsonConfig config) {
        Class<?> type = f.type;
        // Date with an explicit (annotation or global) format → parse the string
        if (type == Date.class && (f.format.length() > 0
                || (config != null && config.dateFormat() != null))) {
            String pattern = f.format.length() > 0 ? f.format : config.dateFormat();
            if (reader.peek() == 'n') {
                reader.readNull();
                return null;
            }
                try {
                    return parseDate(reader.readString(), dateFormatter(pattern));
                } catch (java.time.format.DateTimeParseException e) {
                    throw new JSONException("Cannot parse date with pattern '" + pattern + "'", e);
                }
        }
        // Generic/collection fields: tree path keeps element types resolved
        if (type == Object.class
                || type == List.class || type == Collection.class
                || type == Set.class || type == Map.class
                || f.field.getGenericType() instanceof ParameterizedType) {
            return deserialize(reader.readValue(), f.field.getGenericType());
        }
        if (type == JSONObject.class) return reader.readJSONObject();
        if (type == JSONArray.class) return reader.readJSONArray();
        if (isScalar(type)) {
            return readScalar(reader, type);
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return reader.readValue();
        }
        return deserializeFromReader(reader, type, depth, config); // nested bean (null-safe)
    }

    private static Map<String, FieldInfo> fieldMap(Class<?> clazz) {
        return FIELD_MAP_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, FieldInfo> map = new HashMap<>();
            for (FieldInfo f : fieldsOf(c)) {
                map.put(f.jsonName, f);
                map.put(f.jsonName.toLowerCase(Locale.ROOT), f); // case-insensitive fallback
            }
            return map;
        });
    }

    /**
     * Writes a typed value into a bean field via Unsafe (boxing-free for
     * primitives), falling back to reflection when Unsafe is unavailable.
     */
    @SuppressWarnings("unchecked")
    private static void putField(Object bean, FieldInfo f, Object value) {
        if (UnsafeFields.AVAILABLE) {
            try {
                switch (kindOf(f.type)) {
                    case KIND_INT:     UnsafeFields.putInt(bean, f.offset, (Integer) value); return;
                    case KIND_LONG:    UnsafeFields.putLong(bean, f.offset, (Long) value); return;
                    case KIND_DOUBLE:  UnsafeFields.putDouble(bean, f.offset, (Double) value); return;
                    case KIND_FLOAT:   UnsafeFields.putFloat(bean, f.offset, (Float) value); return;
                    case KIND_BOOLEAN: UnsafeFields.putBoolean(bean, f.offset, (Boolean) value); return;
                    case KIND_SHORT:   UnsafeFields.putShort(bean, f.offset, (Short) value); return;
                    case KIND_BYTE:    UnsafeFields.putByte(bean, f.offset, (Byte) value); return;
                    case KIND_CHAR:    UnsafeFields.putChar(bean, f.offset, (Character) value); return;
                    default:
                        UnsafeFields.putObject(bean, f.offset, value);
                        return;
                }
            } catch (Throwable t) {
                if (t instanceof JSONException || t instanceof Error) {
                    throw t; // real failures must propagate (e.g. cycle guard)
                }
                // Unsafe hiccup, or null into a primitive — fall through to reflection
            }
        }
        try {
            f.field.set(bean, value);
        } catch (IllegalAccessException e) {
            throw new JSONException("Cannot set field " + f.name, e);
        }
    }

    // ── Deserialization (JSON tree → Java bean) ────────────────────

    /**
     * Deserializes a raw JSON value (any node of a parsed tree) into the
     * given target type. Handles plain classes, parameterized types
     * ({@code List<E>}, {@code Map<K,V>}, nested arbitrarily), and falls
     * back to best-effort identity for unresolvable type variables.
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object source, Type targetType) {
        return deserialize(source, targetType, null);
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object source, Type targetType, DingJsonConfig config) {
        try {
            return deserializeInternal(source, targetType, config);
        } catch (StackOverflowError e) {
            throw new JSONException("Nesting too deep to deserialize", e);
        }
    }

    private static <T> T deserializeInternal(Object source, Type targetType, DingJsonConfig config) {
        if (source == null) {
            return null;
        }
        if (targetType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) targetType;
            Type raw = pt.getRawType();
            Type[] args = pt.getActualTypeArguments();

            if (raw == List.class || raw == Collection.class || raw == Iterable.class) {
                if (!(source instanceof JSONArray)) {
                    throw new JSONException("Expected JSON array but got "
                            + typeName(source) + " for " + targetType);
                }
                JSONArray arr = (JSONArray) source;
                List<Object> list = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    list.add(deserialize(arr.get(i), args[0]));
                }
                return (T) list;
            }

            if (raw == Map.class) {
                if (!(source instanceof JSONObject)) {
                    throw new JSONException("Expected JSON object but got "
                            + typeName(source) + " for " + targetType);
                }
                JSONObject obj = (JSONObject) source;
                Map<Object, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : obj.entrySet()) {
                    map.put(deserialize(entry.getKey(), args[0]),
                            deserialize(entry.getValue(), args[1]));
                }
                return (T) map;
            }

            // Custom parameterized class — fall back to its raw type
            return (T) convert(source, (Class<?>) raw);
        }

        if (targetType instanceof Class) {
            Class<?> clazz = (Class<?>) targetType;
            if (clazz.isArray()) {
                // array class (e.g. Order[].class via TypeReference<Order[]>)
                Class<?> comp = clazz.getComponentType();
                return (T) deserializeArray(source, comp, comp, comp);
            }
            return (T) convert(source, clazz);
        }

        if (targetType instanceof GenericArrayType) {
            // e.g. TypeReference<Order[]> — build a real array
            GenericArrayType gat = (GenericArrayType) targetType;
            Type compType = gat.getGenericComponentType();
            return (T) deserializeArray(source, compType, componentClass(compType), compType);
        }

        // TypeVariable / WildcardType — cannot resolve at runtime;
        // keep the raw JSON value (best effort)
        return (T) source;
    }

    /** Builds a typed array from a JSON array source. */
    @SuppressWarnings("unchecked")
    private static Object deserializeArray(Object source, Type compType, Class<?> compClass, Type runtimeComp) {
        if (!(source instanceof JSONArray)) {
            throw new JSONException("Expected JSON array but got " + typeName(source));
        }
        JSONArray arr = (JSONArray) source;
        Object array = Array.newInstance(compClass, arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Array.set(array, i, deserialize(arr.get(i), runtimeComp));
        }
        return array;
    }

    private static Class<?> componentClass(Type t) {
        if (t instanceof Class) return (Class<?>) t;
        if (t instanceof ParameterizedType) return (Class<?>) ((ParameterizedType) t).getRawType();
        if (t instanceof GenericArrayType) {
            return Array.newInstance(componentClass(((GenericArrayType) t).getGenericComponentType()), 0)
                    .getClass();
        }
        return Object.class;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /**
     * Legacy entry point: deserialize a JSON object into a plain class.
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(JSONObject source, Class<T> targetType) {
        try {
            if (targetType == JSONObject.class) {
                return (T) source;
            }
            return deserializeBean(source, targetType);
        } catch (StackOverflowError e) {
            throw new JSONException("Nesting too deep to deserialize", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> deserializeList(JSONArray source, Class<T> elementType) {
        return (List<T>) deserialize(source, listOf(elementType));
    }

    /**
     * Deserialize a JSON array into a list with a generic element type
     * (used by the {@code TypeReference} entry points).
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> deserializeList(JSONArray source, Type elementType) {
        return (List<T>) deserialize(source, listOf(elementType));
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> deserializeList(JSONArray source, Type elementType, DingJsonConfig config) {
        return (List<T>) deserialize(source, listOf(elementType), config);
    }

    /** Instantiates the target bean and maps fields (generic-aware). */
    private static <T> T deserializeBean(JSONObject source, Class<T> targetType) {
        try {
            T instance = targetType.getDeclaredConstructor().newInstance();
            for (FieldInfo f : fieldsOf(targetType)) {
                if (!f.deserializable) continue;
                if (!source.containsKey(f.jsonName)) continue;
                Object raw = source.get(f.jsonName);
                Object typed = deserialize(raw, f.field.getGenericType());
                if (typed != null || raw == null) {
                    putField(instance, f, typed);
                }
            }
            return instance;
        } catch (JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new JSONException("Cannot deserialize to " + targetType, e);
        }
    }

    /** A {@link ParameterizedType} for {@code List<T>}. */
    private static final class ListType implements ParameterizedType {
        private final Type elementType;

        ListType(Type elementType) {
            this.elementType = elementType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return new Type[]{elementType};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }

    private static ParameterizedType listOf(Type elementType) {
        return new ListType(elementType);
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isInstance(value)) return value;

        // JSONObject → target bean
        if (value instanceof JSONObject && !targetType.isAssignableFrom(JSONObject.class)) {
            return deserializeBean((JSONObject) value, targetType);
        }

        // Number conversions
        if (value instanceof Number) {
            Number n = (Number) value;
            if (targetType == int.class || targetType == Integer.class) return n.intValue();
            if (targetType == long.class || targetType == Long.class) return n.longValue();
            if (targetType == double.class || targetType == Double.class) return n.doubleValue();
            if (targetType == float.class || targetType == Float.class) return n.floatValue();
            if (targetType == short.class || targetType == Short.class) return n.shortValue();
            if (targetType == byte.class || targetType == Byte.class) return n.byteValue();
        }

        // String conversions
        if (value instanceof String) {
            String s = (String) value;
            if (targetType == char.class || targetType == Character.class) {
                if (s.length() != 1) throw new JSONException("Cannot convert string to char: " + s);
                return s.charAt(0);
            }
            if (targetType.isEnum()) {
                return Enum.valueOf((Class<Enum>) targetType, s);
            }
            // String → numeric / boolean coercion (mirrors JSONObject typed getters)
            if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(s);
            if (targetType == long.class || targetType == Long.class) return Long.parseLong(s);
            if (targetType == double.class || targetType == Double.class) return Double.parseDouble(s);
            if (targetType == float.class || targetType == Float.class) return Float.parseFloat(s);
            if (targetType == short.class || targetType == Short.class) return Short.parseShort(s);
            if (targetType == byte.class || targetType == Byte.class) return Byte.parseByte(s);
            if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(s);
        }

        // JDK common types (enterprise: dates, UUIDs, money)
        if (targetType == Date.class || targetType == java.sql.Date.class) {
            if (value instanceof Number) return new Date(((Number) value).longValue());
            if (value instanceof String) return new Date(Long.parseLong((String) value));
        }
        if (targetType == Calendar.class) {
            if (value instanceof Number) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(((Number) value).longValue());
                return cal;
            }
        }
        if (targetType == UUID.class) {
            if (value instanceof String) return UUID.fromString((String) value);
        }
        if (targetType == BigDecimal.class) {
            if (value instanceof Number) return new BigDecimal(value.toString());
            if (value instanceof String) return new BigDecimal((String) value);
        }
        if (targetType == BigInteger.class) {
            if (value instanceof Number) return BigInteger.valueOf(((Number) value).longValue());
            if (value instanceof String) return new BigInteger((String) value);
        }
        if (targetType == LocalDate.class && value instanceof String) return LocalDate.parse((String) value);
        if (targetType == LocalDateTime.class && value instanceof String) return LocalDateTime.parse((String) value);
        if (targetType == Instant.class && value instanceof String) return Instant.parse((String) value);

        // JSONArray → List
        if (value instanceof JSONArray) {
            if (targetType == JSONArray.class) return value;
            if (List.class.isAssignableFrom(targetType)) {
                return new ArrayList<>((JSONArray) value);
            }
        }

        if (targetType == String.class) return value.toString();
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean) return value;
            return Boolean.parseBoolean(value.toString());
        }

        return value;
    }
}

    // ── DingJsonMapper.java ──────────────────────────────



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
    public static final class DingJsonMapper {

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
