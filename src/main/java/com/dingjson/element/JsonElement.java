package com.dingjson.element;

import com.dingjson.DingJson;
import com.dingjson.exception.JSONException;

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
public abstract class JsonElement {

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
