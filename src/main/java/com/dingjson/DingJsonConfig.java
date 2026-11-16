package com.dingjson;

/**
 * Immutable configuration for a {@link DingJsonMapper}.
 * <p>
 * Build with {@link #builder()} or {@link #builder(DingJsonConfig)} to inherit
 * defaults, then hand the result to {@link DingJson#mapper(DingJsonConfig)}.
 * The static {@code DingJson} API always uses the defaults.
 */
public final class DingJsonConfig {

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

    /**
     * Maximum nesting depth for objects/arrays. Default 512.
     * Lower it on small thread stacks (containers, virtual threads): 512 levels
     * of object nesting needs roughly 512 frames and can overflow a 256 KB stack.
     */
    private final int maxDepth;

    private DingJsonConfig(boolean writeNullFields, String dateFormat,
                           boolean failOnNonFinite, boolean caseInsensitiveFields,
                           int maxStringLength, int maxArraySize, int maxObjectSize,
                           int maxInputBytes, int maxDepth) {
        this.writeNullFields = writeNullFields;
        this.dateFormat = dateFormat;
        this.failOnNonFinite = failOnNonFinite;
        this.caseInsensitiveFields = caseInsensitiveFields;
        this.maxStringLength = maxStringLength;
        this.maxArraySize = maxArraySize;
        this.maxObjectSize = maxObjectSize;
        this.maxInputBytes = maxInputBytes;
        this.maxDepth = maxDepth;
    }

    /**
     * Defaults: write nulls, epoch-millis dates, NaN→null, exact field names,
     * 10 MB max string, 10M max array elements, 1M max object keys,
     * 10 MB max input size for the byte[]/stream/reader/file entry points,
     * 512 max nesting depth.
     */
    public static DingJsonConfig defaults() {
        return new DingJsonConfig(true, null, false, false,
                10_000_000, 10_000_000, 1_000_000, 10_000_000, 512);
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

    public int maxDepth() {
        return maxDepth;
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
        private int maxDepth;

        private Builder(DingJsonConfig base) {
            this.writeNullFields = base.writeNullFields;
            this.dateFormat = base.dateFormat;
            this.failOnNonFinite = base.failOnNonFinite;
            this.caseInsensitiveFields = base.caseInsensitiveFields;
            this.maxStringLength = base.maxStringLength;
            this.maxArraySize = base.maxArraySize;
            this.maxObjectSize = base.maxObjectSize;
            this.maxInputBytes = base.maxInputBytes;
            this.maxDepth = base.maxDepth;
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

        /** Throw {@link com.dingjson.exception.JSONException} on NaN/±Infinity instead of writing {@code null}. */
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

        /**
         * Maximum nesting depth for objects/arrays. Default 512.
         * Drop to 64-128 on 256 KB stacks (containers, virtual threads) — a deep
         * payload that the default allows can overflow a small stack and the
         * resulting {@link StackOverflowError} is far harder to handle than a
         * {@link com.dingjson.exception.JSONException}. Must be at least 1.
         */
        public Builder maxDepth(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxDepth must be >= 1, got " + value);
            }
            this.maxDepth = value;
            return this;
        }

        public DingJsonConfig build() {
            return new DingJsonConfig(writeNullFields, dateFormat, failOnNonFinite,
                    caseInsensitiveFields, maxStringLength, maxArraySize, maxObjectSize,
                    maxInputBytes, maxDepth);
        }
    }
}
