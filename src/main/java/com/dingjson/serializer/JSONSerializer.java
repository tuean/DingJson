package com.dingjson.serializer;

import com.dingjson.DingJsonConfig;
import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.parser.JSONParser;
import com.dingjson.parser.JSONReader;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
public final class JSONSerializer {

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
            com.dingjson.annotation.JSONField jf = field.getAnnotation(com.dingjson.annotation.JSONField.class);
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
