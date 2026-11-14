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
     * Counts the elements that follow {@code p} (position just after the opening
     * bracket) so the typed-array readers can size their result exactly instead
     * of doubling and copying. Only a capacity <em>hint</em>: a miscount costs a
     * resize, never correctness — the growth path is still in place.
     */
    private static int countElements(char[] src, int len, int p) {
        int count = 0;
        int depth = 0;
        boolean expectValue = true;
        while (p < len) {
            char c = src[p];
            if (c == '"') {
                p++;
                while (p < len) {
                    char d = src[p];
                    if (d == '\\') {
                        p += 2;
                        continue;
                    }
                    if (d == '"') {
                        break;
                    }
                    p++;
                }
                if (depth == 0 && expectValue) {
                    count++;
                    expectValue = false;
                }
            } else if (c == '[' || c == '{') {
                if (depth == 0 && expectValue) {
                    count++;
                    expectValue = false;
                }
                depth++;
            } else if (c == ']' || c == '}') {
                if (depth == 0) {
                    return count;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                expectValue = true;
            } else if (depth == 0 && expectValue && c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                count++;
                expectValue = false;
            }
            p++;
        }
        return count;
    }

    /**
     * Reads an array of integers directly into a freshly allocated {@code int[]}.
     * No boxing, no intermediate tree. Elements must be integers
     * (decimal/scientific notation is rejected).
     * <p>
     * The whole loop runs on the tokener's backing buffer with no per-element
     * method calls: at ~5-7 chars per element, the call/whitespace overhead of
     * the generic {@code nextIntScalar()} path dominated the digit scan itself.
     */
    public int[] readIntArray() {
        expect('[');
        final char[] src = tokener.getBuffer();
        final int len = tokener.getLength();
        final int maxElems = config != null ? config.maxArraySize() : 0;
        int p = tokener.getPosition();
        int hint = countElements(src, len, p);
        int[] arr = new int[hint > 0 ? hint : 16];
        int n = 0;
        while (true) {
            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            final int tokenStart = p;
            boolean negative = false;
            if (src[p] == '-') {
                negative = true;
                p++;
            }
            long val = 0;
            if (p < len && src[p] == '0') {
                p++;
            } else {
                if (p >= len || src[p] < '0' || src[p] > '9') {
                    tokener.setPosition(tokenStart);
                    throw new JSONException("Invalid number at position " + tokenStart);
                }
                // Unchecked accumulation: 18 digits always fit in a long, so the
                // per-digit range test (a divide plus branch) is only paid by the
                // rare 19+ digit numbers, which rescan themselves below.
                int digits = 0;
                while (p < len && src[p] >= '0' && src[p] <= '9') {
                    val = val * 10 + (src[p] - '0');
                    p++;
                    digits++;
                }
                if (digits > 18) {
                    int end = p;
                    try {
                        val = checkedInteger(src, tokenStart, end);
                    } catch (JSONException e) {
                        tokener.setPosition(tokenStart);
                        throw e;
                    }
                } else if (negative) {
                    val = -val;
                }
            }
            if (p < len && (src[p] == '.' || src[p] == 'e' || src[p] == 'E')) {
                tokener.setPosition(tokenStart);
                throw new JSONException("Expected integer but got a decimal number");
            }
            if (maxElems > 0 && n >= maxElems) {
                tokener.setPosition(tokenStart);
                throw new JSONException("Array size exceeds maximum allowed " + maxElems);
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = (int) val;

            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            if (src[p] != ',') {
                tokener.setPosition(p);
                throw new JSONException("Expected ',' or ']' in array at position " + p);
            }
            p++;
        }
        tokener.setPosition(p);
        return n == arr.length ? arr : Arrays.copyOf(arr, n);
    }

    /**
     * Range-checked integer scan for values with 19+ digits (the only ones that
     * can leave the {@code long} range). Negatives accumulate downward so that
     * {@link Long#MIN_VALUE} — whose magnitude does not fit — stays representable.
     */
    private static long checkedInteger(char[] src, int from, int to) {
        int p = from;
        boolean negative = false;
        if (p < to && src[p] == '-') {
            negative = true;
            p++;
        }
        long val = 0;
        while (p < to) {
            int digit = src[p] - '0';
            if (negative) {
                if (val < (Long.MIN_VALUE + digit) / 10) {
                    throw new JSONException("Invalid number: value out of range");
                }
                val = val * 10 - digit;
            } else {
                if (val > (Long.MAX_VALUE - digit) / 10) {
                    throw new JSONException("Invalid number: value out of range");
                }
                val = val * 10 + digit;
            }
            p++;
        }
        return val;
    }

    /** Skips JSON whitespace (space, tab, LF, CR). Other control chars are left for the caller to reject. */
    private static int skipWs(char[] src, int len, int p) {
        while (p < len) {
            char c = src[p];
            if (c > ' ') {
                return p;
            }
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return p; // not JSON whitespace — caller reports it
            }
            p++;
        }
        return p;
    }

    /**
     * Reads an array of longs directly into a freshly allocated {@code long[]}.
     * No boxing, no intermediate tree. Elements must be integers
     * (decimal/scientific notation is rejected).
     * <p>
     * The whole loop runs on the tokener's backing buffer with no per-element
     * method calls: at ~5-7 chars per element, the call/whitespace overhead of
     * the generic {@code nextIntScalar()} path dominated the digit scan itself.
     */
    public long[] readLongArray() {
        expect('[');
        final char[] src = tokener.getBuffer();
        final int len = tokener.getLength();
        final int maxElems = config != null ? config.maxArraySize() : 0;
        int p = tokener.getPosition();
        int hint = countElements(src, len, p);
        long[] arr = new long[hint > 0 ? hint : 16];
        int n = 0;
        while (true) {
            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            final int tokenStart = p;
            boolean negative = false;
            if (src[p] == '-') {
                negative = true;
                p++;
            }
            long val = 0;
            if (p < len && src[p] == '0') {
                p++;
            } else {
                if (p >= len || src[p] < '0' || src[p] > '9') {
                    tokener.setPosition(tokenStart);
                    throw new JSONException("Invalid number at position " + tokenStart);
                }
                // Unchecked accumulation: 18 digits always fit in a long, so the
                // per-digit range test (a divide plus branch) is only paid by the
                // rare 19+ digit numbers, which rescan themselves below.
                int digits = 0;
                while (p < len && src[p] >= '0' && src[p] <= '9') {
                    val = val * 10 + (src[p] - '0');
                    p++;
                    digits++;
                }
                if (digits > 18) {
                    int end = p;
                    try {
                        val = checkedInteger(src, tokenStart, end);
                    } catch (JSONException e) {
                        tokener.setPosition(tokenStart);
                        throw e;
                    }
                } else if (negative) {
                    val = -val;
                }
            }
            if (p < len && (src[p] == '.' || src[p] == 'e' || src[p] == 'E')) {
                tokener.setPosition(tokenStart);
                throw new JSONException("Expected integer but got a decimal number");
            }
            if (maxElems > 0 && n >= maxElems) {
                tokener.setPosition(tokenStart);
                throw new JSONException("Array size exceeds maximum allowed " + maxElems);
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = val;

            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            if (src[p] != ',') {
                tokener.setPosition(p);
                throw new JSONException("Expected ',' or ']' in array at position " + p);
            }
            p++;
        }
        tokener.setPosition(p);
        return n == arr.length ? arr : Arrays.copyOf(arr, n);
    }

    /**
     * Reads an array of doubles directly into a freshly allocated
     * {@code double[]}. The grammar is validated by {@link JSONTokener#skipNumber}
     * and converted by the allocation-free {@link JSONTokener#fastDouble} fast
     * path; only long mantissas / big exponents fall back to
     * {@code Double.parseDouble}.
     */
    public double[] readDoubleArray() {
        expect('[');
        final char[] src = tokener.getBuffer();
        final int len = tokener.getLength();
        final int maxElems = config != null ? config.maxArraySize() : 0;
        int p = tokener.getPosition();
        int hint = countElements(src, len, p);
        double[] arr = new double[hint > 0 ? hint : 16];
        int n = 0;
        while (true) {
            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            final int tokenStart = p;
            tokener.setPosition(p);
            tokener.skipNumber();
            final int tokenEnd = tokener.getPosition();
            double v = JSONTokener.fastDouble(src, tokenStart, tokenEnd);
            if (Double.isNaN(v)) {
                v = Double.parseDouble(new String(src, tokenStart, tokenEnd - tokenStart));
                if (Double.isInfinite(v)) {
                    tokener.setPosition(tokenStart);
                    throw new JSONException("Number out of range for double at position " + tokenStart);
                }
            }
            if (maxElems > 0 && n >= maxElems) {
                tokener.setPosition(tokenStart);
                throw new JSONException("Array size exceeds maximum allowed " + maxElems);
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = v;

            p = skipWs(src, len, tokenEnd);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            if (src[p] != ',') {
                tokener.setPosition(p);
                throw new JSONException("Expected ',' or ']' in array at position " + p);
            }
            p++;
        }
        tokener.setPosition(p);
        return n == arr.length ? arr : Arrays.copyOf(arr, n);
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
        final char[] src = tokener.getBuffer();
        final int len = tokener.getLength();
        final int maxElems = config != null ? config.maxArraySize() : 0;
        int p = tokener.getPosition();
        int hint = countElements(src, len, p);
        String[] arr = new String[hint > 0 ? hint : 16];
        int n = 0;
        while (true) {
            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            if (src[p] != '"') {
                tokener.setPosition(p);
                throw new JSONException("Expected string but got '" + src[p] + "' at position " + p);
            }
            tokener.setPosition(p + 1);
            String v = tokener.readString();
            p = tokener.getPosition();
            if (maxElems > 0 && n >= maxElems) {
                tokener.setPosition(p);
                throw new JSONException("Array size exceeds maximum allowed " + maxElems);
            }
            if (n == arr.length) {
                arr = Arrays.copyOf(arr, grow(n));
            }
            arr[n++] = v;

            p = skipWs(src, len, p);
            if (p >= len) {
                tokener.setPosition(p);
                throw new JSONException("Unexpected end of input");
            }
            if (src[p] == ']') {
                p++;
                break;
            }
            if (src[p] != ',') {
                tokener.setPosition(p);
                throw new JSONException("Expected ',' or ']' in array at position " + p);
            }
            p++;
        }
        tokener.setPosition(p);
        return n == arr.length ? arr : Arrays.copyOf(arr, n);
    }
}
