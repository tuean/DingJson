package com.dingjson.serializer;

import com.dingjson.DingJsonConfig;

import java.lang.reflect.Type;
import java.util.Date;

/**
 * Runtime support for compile-time generated codecs.
 * <p>
 * <strong>Internal API</strong> — call it only from generated code. Every method
 * mirrors exactly one branch of the reflective path in {@link JSONSerializer}
 * (same escaping, same number formatting, same null handling), so generated and
 * reflective output cannot diverge.
 */
public final class CodecSupport {

    private CodecSupport() {
    }

    // ── write side ────────────────────────────────────────────────

    /** Pre-quoted {@code "name":} key, built once per field by generated code. */
    public static String key(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        JSONSerializer.appendQuoted(sb, name);
        return sb.append(':').toString();
    }

    public static void writeInt(StringBuilder out, String key, int value) {
        sep(out);
        out.append(key).append(value);
    }

    public static void writeLong(StringBuilder out, String key, long value) {
        sep(out);
        out.append(key).append(value);
    }

    public static void writeShort(StringBuilder out, String key, short value) {
        sep(out);
        out.append(key).append(value);
    }

    public static void writeByte(StringBuilder out, String key, byte value) {
        sep(out);
        out.append(key).append(value);
    }

    public static void writeBoolean(StringBuilder out, String key, boolean value) {
        sep(out);
        out.append(key).append(value);
    }

    public static void writeDouble(StringBuilder out, String key, double value) {
        sep(out);
        out.append(key);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append("null"); // JSON has no NaN/Infinity
        } else {
            out.append(value);
        }
    }

    public static void writeFloat(StringBuilder out, String key, float value) {
        sep(out);
        out.append(key);
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            out.append("null");
        } else {
            out.append(value);
        }
    }

    public static void writeChar(StringBuilder out, String key, char value) {
        sep(out);
        out.append(key).append(JSONSerializer.quote(String.valueOf(value)));
    }

    /** Writes a reference field; skipped entirely when nulls are not written. */
    public static void writeRef(StringBuilder out, String key, Object value, int depth, DingJsonConfig config) {
        if (value == null && config != null && !config.writeNullFields()) {
            return;
        }
        sep(out);
        out.append(key);
        JSONSerializer.serializeValue(value, out, depth, config);
    }

    /** Writes a {@link Date} honouring {@code @JSONField(format)} then the config default. */
    public static void writeDate(StringBuilder out, String key, Date value, String pattern,
                                 int depth, DingJsonConfig config) {
        if (value == null && config != null && !config.writeNullFields()) {
            return;
        }
        String p = pattern.length() > 0 ? pattern : (config != null ? config.dateFormat() : null);
        sep(out);
        out.append(key);
        if (p == null) {
            JSONSerializer.serializeValue(value, out, depth, config);
        } else if (value == null) {
            out.append("null");
        } else {
            JSONSerializer.appendQuoted(out,
                    JSONSerializer.dateFormatter(p).format(value.toInstant()));
        }
    }

    // ── read side ─────────────────────────────────────────────────

    /** Converts a raw tree value to {@code type}, exactly like the reflective reader. */
    /** Reads a {@link Date} honouring {@code @JSONField(format)} then the config default. */
    public static Date toDate(Object raw, String pattern, DingJsonConfig config) {
        String p = pattern.length() > 0 ? pattern : (config != null ? config.dateFormat() : null);
        if (p != null && raw instanceof String) {
            return JSONSerializer.parseDate((String) raw, JSONSerializer.dateFormatter(p));
        }
        return (Date) JSONSerializer.deserialize(raw, Date.class, config);
    }

    public static Object convert(Object raw, Type type, DingJsonConfig config) {
        return JSONSerializer.deserialize(raw, type, config);
    }

    public static int toInt(Object raw, DingJsonConfig config) {
        Object v = convert(raw, int.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to an int field");
        }
        return ((Number) v).intValue();
    }

    public static long toLong(Object raw, DingJsonConfig config) {
        Object v = convert(raw, long.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a long field");
        }
        return ((Number) v).longValue();
    }

    public static double toDouble(Object raw, DingJsonConfig config) {
        Object v = convert(raw, double.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a double field");
        }
        return ((Number) v).doubleValue();
    }

    public static float toFloat(Object raw, DingJsonConfig config) {
        Object v = convert(raw, float.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a float field");
        }
        return ((Number) v).floatValue();
    }

    public static short toShort(Object raw, DingJsonConfig config) {
        Object v = convert(raw, short.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a short field");
        }
        return ((Number) v).shortValue();
    }

    public static byte toByte(Object raw, DingJsonConfig config) {
        Object v = convert(raw, byte.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a byte field");
        }
        return ((Number) v).byteValue();
    }

    public static boolean toBoolean(Object raw, DingJsonConfig config) {
        Object v = convert(raw, boolean.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a boolean field");
        }
        return ((Boolean) v);
    }

    public static char toChar(Object raw, DingJsonConfig config) {
        Object v = convert(raw, char.class, config);
        if (v == null) {
            throw new IllegalArgumentException("Cannot assign JSON null to a char field");
        }
        return ((Character) v);
    }

    /** Separator: the enclosing object buffer either ends with '{' (first field) or needs a comma. */
    private static void sep(StringBuilder out) {
        if (out.charAt(out.length() - 1) != '{') {
            out.append(',');
        }
    }
}
