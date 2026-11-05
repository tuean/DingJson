package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.element.JsonElement;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * API tests for JsonElement, JSONObject, and JSONArray.
 * Patterns inspired by org.json JSONObjectTest / JSONArrayTest and Gson JsonElement tests.
 */
public class DingJsonApiTest {

    // ================================================================
    // JsonElement
    // ================================================================

    @Test
    public void jsonObjectIsJsonObject() {
        JsonElement el = new JSONObject();
        Assert.assertTrue(el.isJsonObject());
        Assert.assertFalse(el.isJsonArray());
    }

    @Test
    public void jsonArrayIsJsonArray() {
        JsonElement el = new JSONArray();
        Assert.assertTrue(el.isJsonArray());
        Assert.assertFalse(el.isJsonObject());
    }

    @Test
    public void jsonObjectGetAsJsonObject() {
        JSONObject obj = new JSONObject();
        Assert.assertSame(obj, ((JsonElement) obj).getAsJsonObject());
    }

    @Test
    public void jsonArrayGetAsJsonArray() {
        JSONArray arr = new JSONArray();
        Assert.assertSame(arr, ((JsonElement) arr).getAsJsonArray());
    }

    @Test(expected = JSONException.class)
    public void jsonObjectGetAsJsonArrayThrows() {
        JsonElement el = new JSONObject();
        el.getAsJsonArray();
    }

    @Test(expected = JSONException.class)
    public void jsonArrayGetAsJsonObjectThrows() {
        JsonElement el = new JSONArray();
        el.getAsJsonObject();
    }

    @Test
    public void jsonObjectToPrettyString() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        String pretty = ((JsonElement) obj).toPrettyString();
        Assert.assertTrue(pretty.contains("\n"));
        Assert.assertTrue(pretty.contains("\"a\": 1"));
    }

    @Test
    public void jsonArrayToPrettyString() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        arr.add(2);
        String pretty = ((JsonElement) arr).toPrettyString();
        Assert.assertTrue(pretty.startsWith("["));
        Assert.assertTrue(pretty.contains("\n"));
    }

    @Test
    public void jsonObjectToString() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        String s = obj.toString();
        Assert.assertEquals("{\"a\":1}", s);
    }

    @Test
    public void jsonArrayToString() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        arr.add("two");
        String s = arr.toString();
        Assert.assertEquals("[1,\"two\"]", s);
    }

    // ================================================================
    // JSONObject — typed getters
    // ================================================================

    private JSONObject sampleObject() {
        JSONObject obj = new JSONObject();
        obj.put("str", "hello");
        obj.put("int", 42);
        obj.put("long", 2147483648L);
        obj.put("double", 3.14);
        obj.put("bool", true);
        obj.put("obj", new JSONObject());
        obj.put("arr", new JSONArray());
        obj.put("null", null);
        return obj;
    }

    @Test
    public void getString() {
        JSONObject obj = sampleObject();
        Assert.assertEquals("hello", obj.getString("str"));
        Assert.assertNull(obj.getString("missing"));
        Assert.assertEquals("42", obj.getString("int")); // Number → toString
    }

    @Test
    public void getInteger() {
        JSONObject obj = sampleObject();
        Assert.assertEquals(Integer.valueOf(42), obj.getInteger("int"));
        Assert.assertEquals(Integer.valueOf(3), obj.getInteger("double")); // 3.14 → 3 (truncation)
        Assert.assertNull(obj.getInteger("missing"));
    }

    @Test
    public void getLong() {
        JSONObject obj = sampleObject();
        Assert.assertEquals(Long.valueOf(2147483648L), obj.getLong("long"));
        Assert.assertEquals(Long.valueOf(42L), obj.getLong("int")); // int → long upcast
        Assert.assertNull(obj.getLong("missing"));
    }

    @Test
    public void getDouble() {
        JSONObject obj = sampleObject();
        Assert.assertEquals(Double.valueOf(3.14), obj.getDouble("double"));
        Assert.assertEquals(Double.valueOf(42.0), obj.getDouble("int")); // int → double
        Assert.assertNull(obj.getDouble("missing"));
    }

    @Test
    public void getBoolean() {
        JSONObject obj = sampleObject();
        Assert.assertEquals(Boolean.TRUE, obj.getBoolean("bool"));
        Assert.assertNull(obj.getBoolean("missing"));
    }

    @Test
    public void getJSONObject() {
        JSONObject obj = sampleObject();
        Assert.assertNotNull(obj.getJSONObject("obj"));
        Assert.assertNull(obj.getJSONObject("arr")); // type mismatch → null
        Assert.assertNull(obj.getJSONObject("missing"));
    }

    @Test
    public void getJSONArray() {
        JSONObject obj = sampleObject();
        Assert.assertNotNull(obj.getJSONArray("arr"));
        Assert.assertNull(obj.getJSONArray("obj")); // type mismatch → null
        Assert.assertNull(obj.getJSONArray("missing"));
    }

    @Test
    public void getObject() {
        JSONObject obj = new JSONObject();
        obj.put("value", "hello");
        String result = obj.getObject("value", String.class);
        Assert.assertEquals("hello", result);
    }

    // ── Boolean parsing from string ───────────────────────────────

    @Test
    public void getBooleanFromString() {
        JSONObject obj = new JSONObject();
        obj.put("a", "true");
        obj.put("b", "false");
        obj.put("c", "TRUE");
        Assert.assertEquals(Boolean.TRUE, obj.getBoolean("a"));
        Assert.assertEquals(Boolean.FALSE, obj.getBoolean("b"));
        Assert.assertEquals(Boolean.TRUE, obj.getBoolean("c")); // Boolean.parseBoolean is case-insensitive → true
        Assert.assertNull(obj.getBoolean("missing"));
    }

    // ── Integer parsing from string ───────────────────────────────

    @Test
    public void getIntegerFromString() {
        JSONObject obj = new JSONObject();
        obj.put("a", "123");
        Assert.assertEquals(Integer.valueOf(123), obj.getInteger("a"));
    }

    @Test(expected = NumberFormatException.class)
    public void getIntegerFromInvalidString() {
        JSONObject obj = new JSONObject();
        obj.put("a", "not-a-number");
        obj.getInteger("a");
    }

    // ================================================================
    // JSONObject — Map interface
    // ================================================================

    @Test
    public void mapPutAndGet() {
        JSONObject obj = new JSONObject();
        Assert.assertNull(obj.put("key", "value"));
        Assert.assertEquals("value", obj.get("key"));
    }

    @Test
    public void mapPutReturnsOldValue() {
        JSONObject obj = new JSONObject();
        obj.put("key", "old");
        Assert.assertEquals("old", obj.put("key", "new"));
    }

    @Test
    public void mapRemove() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", 2);
        Assert.assertEquals(Integer.valueOf(1), obj.remove("a"));
        Assert.assertNull(obj.get("a"));
        Assert.assertEquals(1, obj.size());
    }

    @Test
    public void mapClear() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", 2);
        obj.clear();
        Assert.assertTrue(obj.isEmpty());
    }

    @Test
    public void mapKeySet() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", 2);
        obj.put("c", 3);
        Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("a", "b", "c")), obj.keySet());
    }

    @Test
    public void mapValues() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", 2);
        Collection<Object> values = obj.values();
        Assert.assertTrue(values.contains(1));
        Assert.assertTrue(values.contains(2));
        Assert.assertEquals(2, values.size());
    }

    @Test
    public void mapContainsKey() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        Assert.assertTrue(obj.containsKey("a"));
        Assert.assertFalse(obj.containsKey("missing"));
    }

    @Test
    public void mapContainsValue() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        Assert.assertTrue(obj.containsValue(1));
        Assert.assertFalse(obj.containsValue(999));
    }

    @Test
    public void mapPutAll() {
        JSONObject obj = new JSONObject();
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("x", 10);
        other.put("y", 20);
        obj.putAll(other);
        Assert.assertEquals(2, obj.size());
        Assert.assertEquals(Integer.valueOf(10), obj.getInteger("x"));
    }

    @Test
    public void mapEntrySet() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", 2);
        Set<Map.Entry<String, Object>> entries = obj.entrySet();
        Assert.assertEquals(2, entries.size());
    }

    @Test
    public void mapSize() {
        JSONObject obj = new JSONObject();
        Assert.assertEquals(0, obj.size());
        obj.put("a", 1);
        Assert.assertEquals(1, obj.size());
    }

    @Test
    public void mapIsEmpty() {
        JSONObject obj = new JSONObject();
        Assert.assertTrue(obj.isEmpty());
        obj.put("a", 1);
        Assert.assertFalse(obj.isEmpty());
    }

    // ================================================================
    // JSONObject — equals and hashCode
    // ================================================================

    @Test
    public void jsonObjectEquals() {
        JSONObject a = new JSONObject();
        a.put("a", 1);
        a.put("b", 2);

        JSONObject b = new JSONObject();
        b.put("a", 1);
        b.put("b", 2);

        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void jsonObjectNotEquals() {
        JSONObject a = new JSONObject();
        a.put("a", 1);

        JSONObject b = new JSONObject();
        b.put("a", 2);

        Assert.assertNotEquals(a, b);
    }

    @Test
    public void jsonObjectEqualsSelf() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        Assert.assertEquals(obj, obj);
    }

    @Test
    public void jsonObjectNotEqualsDifferentType() {
        JSONObject obj = new JSONObject();
        Assert.assertNotEquals(obj, "string");
        Assert.assertNotEquals(obj, null);
    }

    @Test
    public void jsonObjectEqualsEmpty() {
        Assert.assertEquals(new JSONObject(), new JSONObject());
    }

    // ================================================================
    // JSONObject — constructors
    // ================================================================

    @Test
    public void constructorFromMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        JSONObject obj = new JSONObject(map);
        Assert.assertEquals(Integer.valueOf(1), obj.getInteger("a"));
        Assert.assertEquals(Integer.valueOf(2), obj.getInteger("b"));
        Assert.assertEquals(2, obj.size());
    }

    // ================================================================
    // JSONArray — typed getters
    // ================================================================

    private JSONArray sampleArray() {
        JSONArray arr = new JSONArray();
        arr.add("hello");
        arr.add(42);
        arr.add(2147483648L);
        arr.add(3.14);
        arr.add(true);
        arr.add(new JSONObject());
        arr.add(new JSONArray());
        arr.add(null);
        return arr;
    }

    @Test
    public void arrayGetString() {
        JSONArray arr = sampleArray();
        Assert.assertEquals("hello", arr.getString(0));
        Assert.assertEquals("42", arr.getString(1)); // Number → toString
    }

    @Test
    public void arrayGetInteger() {
        JSONArray arr = sampleArray();
        Assert.assertEquals(Integer.valueOf(42), arr.getInteger(1));
        Assert.assertNull(arr.getInteger(7)); // null element
    }

    @Test
    public void arrayGetLong() {
        JSONArray arr = sampleArray();
        Assert.assertEquals(Long.valueOf(2147483648L), arr.getLong(2));
        Assert.assertEquals(Long.valueOf(42L), arr.getLong(1)); // int → long
    }

    @Test
    public void arrayGetDouble() {
        JSONArray arr = sampleArray();
        Assert.assertEquals(Double.valueOf(3.14), arr.getDouble(3));
        Assert.assertEquals(Double.valueOf(42.0), arr.getDouble(1)); // int → double
    }

    @Test
    public void arrayGetBoolean() {
        JSONArray arr = sampleArray();
        Assert.assertEquals(Boolean.TRUE, arr.getBoolean(4));
    }

    @Test
    public void arrayGetJSONObject() {
        JSONArray arr = sampleArray();
        Assert.assertNotNull(arr.getJSONObject(5));
        Assert.assertNull(arr.getJSONObject(0)); // string ≠ object
    }

    @Test
    public void arrayGetJSONArray() {
        JSONArray arr = sampleArray();
        Assert.assertNotNull(arr.getJSONArray(6));
        Assert.assertNull(arr.getJSONArray(0)); // string ≠ array
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void arrayGetOutOfBounds() {
        JSONArray arr = new JSONArray();
        arr.getString(0);
    }

    // ================================================================
    // JSONArray — List interface
    // ================================================================

    @Test
    public void listAddAndGet() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        Assert.assertEquals("a", arr.get(0));
        Assert.assertEquals("b", arr.get(1));
    }

    @Test
    public void listAddAtIndex() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("c");
        arr.add(1, "b");
        Assert.assertEquals("a", arr.get(0));
        Assert.assertEquals("b", arr.get(1));
        Assert.assertEquals("c", arr.get(2));
        Assert.assertEquals(3, arr.size());
    }

    @Test
    public void listSet() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        Assert.assertEquals("a", arr.set(0, "b"));
        Assert.assertEquals("b", arr.get(0));
    }

    @Test
    public void listRemoveByIndex() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.add("c");
        Assert.assertEquals("b", arr.remove(1));
        Assert.assertEquals(2, arr.size());
        Assert.assertEquals("c", arr.get(1));
    }

    @Test
    public void listRemoveByObject() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        Assert.assertTrue(arr.remove("a"));
        Assert.assertEquals(1, arr.size());
        Assert.assertEquals("b", arr.get(0));
    }

    @Test
    public void listIndexOf() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.add("a");
        Assert.assertEquals(0, arr.indexOf("a"));
        Assert.assertEquals(1, arr.indexOf("b"));
        Assert.assertEquals(-1, arr.indexOf("z"));
    }

    @Test
    public void listLastIndexOf() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.add("a");
        Assert.assertEquals(2, arr.lastIndexOf("a"));
    }

    @Test
    public void listContains() {
        JSONArray arr = new JSONArray();
        arr.add("hello");
        Assert.assertTrue(arr.contains("hello"));
        Assert.assertFalse(arr.contains("world"));
    }

    @Test
    public void listContainsAll() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.add("c");
        Assert.assertTrue(arr.containsAll(Arrays.asList("a", "c")));
        Assert.assertFalse(arr.containsAll(Arrays.asList("a", "z")));
    }

    @Test
    public void listAddAll() {
        JSONArray arr = new JSONArray();
        arr.addAll(Arrays.asList("a", "b", "c"));
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals("a", arr.get(0));
        Assert.assertEquals("c", arr.get(2));
    }

    @Test
    public void listAddAllAtIndex() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("d");
        arr.addAll(1, Arrays.asList("b", "c"));
        Assert.assertEquals(4, arr.size());
        Assert.assertEquals("b", arr.get(1));
        Assert.assertEquals("c", arr.get(2));
    }

    @Test
    public void listRemoveAll() {
        JSONArray arr = new JSONArray();
        arr.addAll(Arrays.asList("a", "b", "c", "a"));
        arr.removeAll(Arrays.asList("a", "c"));
        Assert.assertEquals(1, arr.size());
        Assert.assertEquals("b", arr.get(0));
    }

    @Test
    public void listRetainAll() {
        JSONArray arr = new JSONArray();
        arr.addAll(Arrays.asList("a", "b", "c"));
        arr.retainAll(Arrays.asList("a", "b"));
        Assert.assertEquals(2, arr.size());
        Assert.assertEquals("a", arr.get(0));
        Assert.assertEquals("b", arr.get(1));
    }

    @Test
    public void listClear() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.clear();
        Assert.assertTrue(arr.isEmpty());
    }

    @Test
    public void listIsEmpty() {
        JSONArray arr = new JSONArray();
        Assert.assertTrue(arr.isEmpty());
        arr.add("a");
        Assert.assertFalse(arr.isEmpty());
    }

    @Test
    public void listSize() {
        JSONArray arr = new JSONArray();
        Assert.assertEquals(0, arr.size());
        arr.add("a");
        Assert.assertEquals(1, arr.size());
    }

    @Test
    public void listToArray() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        Object[] array = arr.toArray();
        Assert.assertEquals(2, array.length);
        Assert.assertEquals("a", array[0]);
    }

    @Test
    public void listToArrayTyped() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        String[] array = arr.toArray(new String[0]);
        Assert.assertEquals(2, array.length);
        Assert.assertEquals("a", array[0]);
    }

    @Test
    public void listIterator() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        Iterator<Object> it = arr.iterator();
        Assert.assertTrue(it.hasNext());
        Assert.assertEquals("a", it.next());
        Assert.assertTrue(it.hasNext());
        Assert.assertEquals("b", it.next());
        Assert.assertFalse(it.hasNext());
    }

    @Test
    public void listListIterator() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        ListIterator<Object> it = arr.listIterator();
        Assert.assertTrue(it.hasNext());
        Assert.assertEquals("a", it.next());
        Assert.assertEquals("b", it.next());
        Assert.assertFalse(it.hasNext());
    }

    @Test
    public void listListIteratorAtIndex() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.add("c");
        ListIterator<Object> it = arr.listIterator(1);
        Assert.assertEquals("b", it.next());
    }

    @Test
    public void listSubList() {
        JSONArray arr = new JSONArray();
        arr.add("a");
        arr.add("b");
        arr.add("c");
        arr.add("d");
        List<Object> sub = arr.subList(1, 3);
        Assert.assertEquals(2, sub.size());
        Assert.assertEquals("b", sub.get(0));
        Assert.assertEquals("c", sub.get(1));
    }

    // ================================================================
    // JSONArray — equals and hashCode
    // ================================================================

    @Test
    public void jsonArrayEquals() {
        JSONArray a = new JSONArray();
        a.add(1);
        a.add(2);

        JSONArray b = new JSONArray();
        b.add(1);
        b.add(2);

        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void jsonArrayNotEquals() {
        JSONArray a = new JSONArray();
        a.add(1);

        JSONArray b = new JSONArray();
        b.add(2);

        Assert.assertNotEquals(a, b);
    }

    @Test
    public void jsonArrayEqualsSelf() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        Assert.assertEquals(arr, arr);
    }

    @Test
    public void jsonArrayNotEqualsDifferentType() {
        JSONArray arr = new JSONArray();
        Assert.assertNotEquals(arr, "string");
        Assert.assertNotEquals(arr, null);
    }

    @Test
    public void jsonArrayEqualsEmpty() {
        Assert.assertEquals(new JSONArray(), new JSONArray());
    }

    // ================================================================
    // JSONArray — constructors
    // ================================================================

    @Test
    public void constructorWithCapacity() {
        JSONArray arr = new JSONArray(10);
        Assert.assertTrue(arr.isEmpty());
    }

    @Test
    public void constructorFromList() {
        List<Object> list = Arrays.asList(1, 2, 3);
        JSONArray arr = new JSONArray(list);
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals(Integer.valueOf(1), arr.getInteger(0));
    }

    @Test
    public void constructorFromListIsDefensiveCopy() {
        List<Object> original = new ArrayList<>(Arrays.asList(1, 2, 3));
        JSONArray arr = new JSONArray(original);
        original.add(4); // modify original
        Assert.assertEquals(3, arr.size()); // should not affect JSONArray
    }
}
