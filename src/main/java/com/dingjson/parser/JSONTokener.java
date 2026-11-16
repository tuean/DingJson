package com.dingjson.parser;

import com.dingjson.DingJsonConfig;
import com.dingjson.exception.JSONException;

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
public final class JSONTokener {

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
    }

    /**
     * Rejects raw control characters (0x00-0x1F) inside a string, as required by
     * RFC 8259 §7. Checked while the string is scanned rather than in a separate
     * pass over the whole document: the pre-pass cost a second scan of the input
     * (~25% of parse time on string-dense input) to protect an invariant whose
     * check is a single branch on a character already in hand. Control
     * characters outside strings are still rejected — {@link #nextNonWhitespace()}
     * hands them back and the parser reports them.
     */
    private void checkControlChars(int from, int to) {
        final char[] src = this.source;
        for (int i = from; i < to; i++) {
            if (src[i] < 0x20) {
                throw new JSONException("Unescaped control character in string at position " + i);
            }
        }
    }

    int getPosition() {
        return pos;
    }

    /** Input length (the backing buffer carries one extra sentinel char). */
    int getLength() {
        return length;
    }

    /** Backing buffer, for callers that scan it directly (streaming array paths). */
    char[] getBuffer() {
        return source;
    }

    /** Sets the read position after a caller-driven scan of {@link #getBuffer()}. */
    void setPosition(int p) {
        this.pos = p;
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
            if (c < 0x20) {
                throw new JSONException("Unescaped control character in string at position " + p);
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
            checkControlChars(p, q);
            String result = this.sourceString.substring(this.pos, q);
            checkStringLength(q - this.pos);
            this.pos = q + 1;
            return result;
        }
        return decodeEscapes(src, len, this.pos, b);
    }

    /**
     * Skips a string without materializing it — the validation counterpart of
     * {@link #readString()}, used by {@link JSONParser#skipValue()} and
     * {@code DingJson.isValid}. Same scan, same escape validation, same length
     * limit; the (discarded) String is only built when the string actually
     * contains a backslash, so escape-free documents allocate nothing.
     */
    void skipString() {
        final char[] src = this.source;
        final int len = this.length;
        int p = this.pos;

        int limit = Math.min(len, p + 16);
        while (p < limit) {
            char c = src[p];
            if (c == '"') {
                checkStringLength(p - this.pos);
                this.pos = p + 1;
                return;
            }
            if (c == '\\') {
                decodeEscapes(src, len, this.pos, p); // validates; result discarded
                return;
            }
            if (c < 0x20) {
                throw new JSONException("Unescaped control character in string at position " + p);
            }
            p++;
        }
        if (p >= len) {
            throw new JSONException("Unterminated string");
        }

        int q = this.sourceString.indexOf('"', p);
        int b = this.sourceString.indexOf('\\', p);
        if (b < 0 || (q >= 0 && q < b)) {
            if (q < 0) {
                throw new JSONException("Unterminated string");
            }
            checkControlChars(p, q);
            checkStringLength(q - this.pos);
            this.pos = q + 1;
            return;
        }
        decodeEscapes(src, len, this.pos, b);
    }

    /** Escape-decoding slow path, starting at the first backslash {@code b}. */
    private String decodeEscapes(char[] src, int len, int start, int b) {
        checkControlChars(start, b);
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
                if (c < 0x20) {
                    throw new JSONException("Unescaped control character in string at position " + (p - 1));
                }
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

    /**
     * Range-checked integer scan for values with 19+ digits (the only ones that
     * can leave the {@code long} range). Negatives accumulate downward so that
     * {@link Long#MIN_VALUE} — whose magnitude does not fit — stays representable.
     */
    private long checkedInteger(int from, int to) {
        final char[] src = this.source;
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

    // ── Number scanning ───────────────────────────────────────────

    /** Powers of ten that are exactly representable as a double. */
    private static final double[] POW10 = new double[23];

    static {
        double v = 1;
        for (int i = 0; i < POW10.length; i++) {
            POW10[i] = v;
            v *= 10;
        }
    }

    /**
     * Converts an already grammar-validated number literal to a double without
     * allocating the intermediate String that {@code Double.parseDouble} needs.
     * <p>
     * Correctly rounded, not merely "close": when the mantissa has at most 15
     * significant digits (exactly representable as a long) and the decimal
     * exponent is within ±22 (where 10^n is exact), a single multiply or divide
     * rounds the exact decimal value once — which is exactly what the JDK's
     * correctly-rounded parser produces. Anything else (longer mantissas,
     * larger exponents) returns {@link Double#NaN} so the caller falls back to
     * {@code Double.parseDouble}. NaN is a safe sentinel because JSON has no
     * NaN literal, and the grammar is validated before this runs.
     */
    static double fastDouble(char[] src, int from, int to) {
        int p = from;
        boolean negative = false;
        if (p < to && src[p] == '-') {
            negative = true;
            p++;
        }
        long mantissa = 0;
        int digits = 0;
        int fracDigits = 0;
        boolean seenDot = false;
        while (p < to) {
            char c = src[p];
            if (c == '.') {
                seenDot = true;
                p++;
                continue;
            }
            if (c == 'e' || c == 'E') {
                break;
            }
            if (digits >= 15) {
                return Double.NaN; // mantissa no longer exactly representable
            }
            mantissa = mantissa * 10 + (c - '0');
            digits++;
            if (seenDot) {
                fracDigits++;
            }
            p++;
        }

        int exp = 0;
        if (p < to) { // e / E
            p++;
            boolean expNegative = false;
            if (p < to && src[p] == '+') {
                p++;
            } else if (p < to && src[p] == '-') {
                expNegative = true;
                p++;
            }
            for (; p < to; p++) {
                exp = exp * 10 + (src[p] - '0');
                if (exp > 30) {
                    return Double.NaN; // far outside the exact power-of-ten range
                }
            }
            if (expNegative) {
                exp = -exp;
            }
        }

        int scale = exp - fracDigits;
        if (mantissa == 0) {
            return negative ? -0.0 : 0.0;
        }
        double result;
        if (scale == 0) {
            result = (double) mantissa;
        } else if (scale > 0) {
            if (scale > 22) {
                return Double.NaN;
            }
            result = (double) mantissa * POW10[scale];
        } else {
            if (scale < -22) {
                return Double.NaN;
            }
            result = (double) mantissa / POW10[-scale];
        }
        return negative ? -result : result;
    }


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
            // Unchecked accumulation; 18 digits always fit in a long. Only the
            // rare 19+ digit integer parts rescan with a per-digit range test.
            int digits = 0;
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                intVal = intVal * 10 + (src[p] - '0');
                p++;
                digits++;
            }
            if (digits > 18) {
                int end = p;
                try {
                    intVal = checkedInteger(start, end);
                } catch (JSONException e) {
                    overflow = true; // reported below with the usual message
                }
            } else if (negative) {
                intVal = -intVal;
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

        // Decimal / scientific — fast path first (no String allocation),
        // falling back to the JDK parser for long mantissas / big exponents.
        double d = fastDouble(src, start, p);
        if (!Double.isNaN(d)) {
            return d;
        }
        String text = new String(src, start, p - start);
        d = Double.parseDouble(text);
        // Values that overflow double come back as ±Infinity and would then
        // serialize as null (data loss) — keep full precision in a BigDecimal
        // instead (Gson-style). The strict scalar double path (readDouble)
        // rejects these explicitly.
        if (Double.isInfinite(d)) {
            java.math.BigDecimal bd;
            try {
                bd = new java.math.BigDecimal(text);
            } catch (NumberFormatException e) {
                // absurd exponents (i_number_huge_exp.json) exceed even BigDecimal
                throw new JSONException("Invalid number: exponent out of range at position " + start);
            }
            // A huge exponent parses instantly but is a bomb downstream:
            // toPlainString() on scale -2147483648 allocates 100M+ characters.
            if (bd.scale() < -MAX_BIG_SCALE || bd.scale() > MAX_BIG_SCALE) {
                throw new JSONException("Number exponent out of range at position " + start);
            }
            return bd;
        }
        return d;
    }

    /** Largest {@code |scale|} accepted for a {@code BigDecimal}. */
    private static final int MAX_BIG_SCALE = 10_000;

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
            int digits = 0;
            while (p < len && src[p] >= '0' && src[p] <= '9') {
                intVal = intVal * 10 + (src[p] - '0');
                p++;
                digits++;
            }
            if (digits > 18) {
                int end = p;
                try {
                    intVal = checkedInteger(this.pos, end);
                } catch (JSONException e) {
                    overflow = true;
                }
            } else if (negative) {
                intVal = -intVal;
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
     * Validates and skips a JSON number without boxing it — the validation
     * counterpart of {@link #readNumber()}. Grammar and range rules are
     * identical (integer digits that overflow {@code long} are still rejected,
     * decimal/scientific values that overflow {@code double} stay valid).
     */
    void skipNumber() {
        final char[] src = this.source;
        final int len = this.length;
        final int start = this.pos;
        int p = start;

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
                throw new JSONException("Invalid number at position " + start);
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

        if (overflow) {
            throw new JSONException("Invalid number: value out of range");
        }
        this.pos = p;
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
