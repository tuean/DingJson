package com.dingjson.parser;

import com.dingjson.DingJsonConfig;
import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;

import java.util.Arrays;

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
public final class JSONReader {

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
