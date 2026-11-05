package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * JSONPath query support — a small, safe subset of the common JSONPath syntax:
 * <pre>{@code
 * Object v = JSONPath.eval(tree, "$.items[0].price");
 * List<Object> all = JSONPath.eval(tree, "$.items[*].name");
 * String name = JSONPath.eval(tree, "$.user.name", String.class);
 * }</pre>
 * Supported operators:
 * <ul>
 *   <li>{@code $} — root</li>
 *   <li>{@code .field} — object member</li>
 *   <li>{@code [n]} — array index (negative = from the end)</li>
 *   <li>{@code [*]} — all array elements (or all object values)</li>
 *   <li>{@code ..field} / {@code ..[n]} — recursive descent (search whole subtree)</li>
 * </ul>
 */
public final class JSONPath {

    private JSONPath() {
    }

    /** Evaluates a path against a parsed JSON tree. Returns {@code null} on no match. */
    public static Object eval(Object root, String path) {
        try {
            return evalInternal(root, path);
        } catch (StackOverflowError e) {
            // Deeply nested user-built trees could exhaust the stack before
            // any other bound — surface a catchable JSONException instead.
            throw new JSONException("JSONPath evaluation too deep", e);
        }
    }

    private static Object evalInternal(Object root, String path) {
        if (path == null || path.isEmpty()) {
            throw new JSONException("Empty JSONPath");
        }
        int i = 0;
        if (path.charAt(0) == '$') {
            i = 1;
        }
        Object current = root;
        while (i < path.length()) {
            // Wildcard-expanded list with remaining path: map over each element
            if (current instanceof List && !(current instanceof JSONArray)) {
                List<Object> results = new ArrayList<>();
                for (Object item : (List<?>) current) {
                    Object r = eval(item, path.substring(i));
                    if (r != null) {
                        results.add(r);
                    }
                }
                return results.isEmpty() ? null : results;
            }
            char c = path.charAt(i);
            if (c == '.') {
                if (i + 1 < path.length() && path.charAt(i + 1) == '.') {
                    // recursive descent
                    i += 2;
                    Segment seg = parseSegment(path, i, true);
                    i = seg.next;
                    current = recursiveSearch(current, seg);
                } else {
                    i++;
                    Segment seg = parseSegment(path, i, false);
                    i = seg.next;
                    current = applySegment(current, seg);
                }
            } else if (c == '[') {
                Segment seg = parseSegment(path, i, false);
                i = seg.next;
                current = applySegment(current, seg);
            } else {
                throw new JSONException("Unexpected character '" + c + "' in JSONPath at " + i);
            }
            if (current == null) {
                return null; // no match
            }
        }
        return current;
    }

    /** Evaluates and casts to a target type (numbers/strings via the normal coercion). */
    @SuppressWarnings("unchecked")
    public static <T> T eval(Object root, String path, Class<T> clazz) {
        Object v = eval(root, path);
        if (v == null) {
            return null;
        }
        if (clazz.isInstance(v)) {
            return clazz.cast(v);
        }
        if (clazz == String.class) {
            return (T) v.toString();
        }
        if (clazz == int.class || clazz == Integer.class) {
            return (T) Integer.valueOf(((Number) v).intValue());
        }
        if (clazz == long.class || clazz == Long.class) {
            return (T) Long.valueOf(((Number) v).longValue());
        }
        if (clazz == double.class || clazz == Double.class) {
            return (T) Double.valueOf(((Number) v).doubleValue());
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            return (T) Boolean.valueOf(v.toString());
        }
        throw new JSONException("Cannot convert " + v.getClass() + " to " + clazz);
    }

    // ── Internals ─────────────────────────────────────────────────

    private static final class Segment {
        final String name;    // field name, or "*", or null for index
        final int index;      // array index (when name == null and !wildcard)
        final int next;       // next position in the path string
        final boolean wildcard;

        Segment(String name, int index, int next, boolean wildcard) {
            this.name = name;
            this.index = index;
            this.next = next;
            this.wildcard = wildcard;
        }
    }

    /** Parses {@code .name} or {@code [n]} / {@code [*]} starting at {@code i}. */
    private static Segment parseSegment(String path, int i, boolean recursive) {
        char c = path.charAt(i);
        if (c == '[') {
            int end = path.indexOf(']', i + 1);
            if (end < 0) {
                throw new JSONException("Unclosed '[' in JSONPath at " + i);
            }
            String inner = path.substring(i + 1, end).trim();
            if (inner.equals("*")) {
                return new Segment(null, 0, end + 1, true);
            }
            try {
                int idx = Integer.parseInt(inner);
                return new Segment(null, idx, end + 1, false);
            } catch (NumberFormatException e) {
                // quoted member name: ["field"]
                if (inner.length() >= 2 && inner.charAt(0) == '"' && inner.charAt(inner.length() - 1) == '"') {
                    return new Segment(inner.substring(1, inner.length() - 1), 0, end + 1, false);
                }
                throw new JSONException("Invalid array index '" + inner + "' in JSONPath");
            }
        }
        // .field
        int end = i;
        while (end < path.length()) {
            char pc = path.charAt(end);
            if (pc == '.' || pc == '[') break;
            end++;
        }
        if (end == i) {
            throw new JSONException("Missing field name in JSONPath at " + i);
        }
        String name = path.substring(i, end);
        return new Segment(name, 0, end, false);
    }

    private static Object applySegment(Object current, Segment seg) {
        if (current instanceof JSONObject) {
            JSONObject obj = (JSONObject) current;
            if (seg.wildcard) {
                return new ArrayList<>(obj.values());
            }
            if (seg.name != null) {
                return obj.get(seg.name);
            }
            return null; // index on an object → no match
        }
        if (current instanceof JSONArray) {
            JSONArray arr = (JSONArray) current;
            if (seg.wildcard) {
                return new ArrayList<>(arr);
            }
            if (seg.name != null) {
                return null; // field name on an array → no match
            }
            int idx = seg.index;
            if (idx < 0) idx += arr.size();
            if (idx < 0 || idx >= arr.size()) {
                return null; // out of bounds → no match
            }
            return arr.get(idx);
        }
        return null; // leaf reached before the path ended
    }

    /** Recursive descent: search the whole subtree for the segment. */
    private static Object recursiveSearch(Object current, Segment seg) {
        Object direct = applySegment(current, seg);
        if (direct != null) {
            return direct;
        }
        if (current instanceof JSONObject) {
            for (Object v : ((JSONObject) current).values()) {
                Object found = recursiveSearch(v, seg);
                if (found != null) return found;
            }
        } else if (current instanceof JSONArray) {
            for (Object v : (JSONArray) current) {
                Object found = recursiveSearch(v, seg);
                if (found != null) return found;
            }
        }
        return null;
    }
}
