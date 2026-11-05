package com.dingjson.element;

import com.dingjson.DingJson;
import com.dingjson.exception.JSONException;
import com.dingjson.serializer.JSONSerializer;

import java.util.*;

/**
 * A JSON object backed by a {@link LinkedHashMap}, preserving insertion order.
 * Provides typed accessor methods for convenient data retrieval.
 */
public class JSONObject extends JsonElement implements Map<String, Object> {

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
