package com.dingjson.parser;

import com.dingjson.DingJsonConfig;
import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;

/**
 * Recursive-descent JSON parser.
 * Converts a JSON string into a tree of {@link JSONObject}, {@link JSONArray},
 * String, Number, Boolean, or null.
 * <p>
 * Performance notes: single non-whitespace read per token (no double
 * whitespace skipping between {@code peek} and {@code next}), hand-rolled
 * integer parsing, and string fast-path slicing — see {@link JSONTokener}.
 */
public final class JSONParser {

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
