package com.dingjson.serializer;

import com.dingjson.exception.JSONException;

/**
 * Streaming JSON writer — the build-side counterpart of
 * {@link com.dingjson.parser.JSONReader}. Lets callers emit large documents
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
public final class JSONWriter {

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
        JSONSerializer.appendQuoted(sb, key);
        sb.append(':');
        first[depth] = false;
        state[depth] = 3; // awaiting the value for this key (no extra stack level)
        return this;
    }

    // ── Values ────────────────────────────────────────────────────

    public JSONWriter value(String v) {
        beforeValue(0);
        JSONSerializer.appendQuoted(sb, v);
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
