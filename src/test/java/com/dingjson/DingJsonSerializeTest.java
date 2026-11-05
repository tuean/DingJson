package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * Comprehensive serialization tests for DingJson.
 * Patterns inspired by Gson and Jackson serialization test suites.
 */
public class DingJsonSerializeTest {

    // ── Primitive values ──────────────────────────────────────────

    @Test
    public void serializeNull() {
        Assert.assertEquals("null", DingJson.toJSONString(null));
    }

    @Test
    public void serializeBoolean() {
        Assert.assertEquals("true", DingJson.toJSONString(Boolean.TRUE));
        Assert.assertEquals("false", DingJson.toJSONString(Boolean.FALSE));
    }

    @Test
    public void serializeNumber() {
        Assert.assertEquals("42", DingJson.toJSONString(42));
        Assert.assertEquals("3.14", DingJson.toJSONString(3.14));
        Assert.assertEquals("2147483648", DingJson.toJSONString(2147483648L));
        Assert.assertEquals("-1", DingJson.toJSONString(-1));
    }

    @Test
    public void serializeString() {
        Assert.assertEquals("\"hello\"", DingJson.toJSONString("hello"));
        Assert.assertEquals("\"\"", DingJson.toJSONString(""));
    }

    // ── JSONObject ────────────────────────────────────────────────

    @Test
    public void serializeEmptyObject() {
        Assert.assertEquals("{}", DingJson.toJSONString(new JSONObject()));
    }

    @Test
    public void serializeObject() {
        JSONObject obj = new JSONObject();
        obj.put("name", "Ding");
        obj.put("age", 3);
        obj.put("active", true);
        obj.put("data", new JSONObject());
        String json = DingJson.toJSONString(obj);
        Assert.assertTrue(json.startsWith("{"));
        Assert.assertTrue(json.endsWith("}"));
        Assert.assertTrue(json.contains("\"name\":\"Ding\""));
        Assert.assertTrue(json.contains("\"age\":3"));
        Assert.assertTrue(json.contains("\"active\":true"));
        Assert.assertTrue(json.contains("\"data\":{}"));
    }

    @Test
    public void serializeObjectWithNullValue() {
        JSONObject obj = new JSONObject();
        obj.put("a", null);
        obj.put("b", "keep");
        String json = DingJson.toJSONString(obj);
        Assert.assertTrue(json.contains("\"a\":null"));
        Assert.assertTrue(json.contains("\"b\":\"keep\""));
    }

    @Test
    public void serializeObjectPreservesOrder() {
        JSONObject obj = new JSONObject();
        obj.put("z", 1);
        obj.put("a", 2);
        obj.put("m", 3);
        String json = DingJson.toJSONString(obj);
        // LinkedHashMap preserves insertion order
        Assert.assertEquals("{\"z\":1,\"a\":2,\"m\":3}", json);
    }

    // ── JSONArray ─────────────────────────────────────────────────

    @Test
    public void serializeEmptyArray() {
        Assert.assertEquals("[]", DingJson.toJSONString(new JSONArray()));
    }

    @Test
    public void serializeArray() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        arr.add("two");
        arr.add(null);
        arr.add(true);
        arr.add(new JSONObject());
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[1,\"two\",null,true,{}]", json);
    }

    // ── Java Map ──────────────────────────────────────────────────

    @Test
    public void serializeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", 10);
        map.put("y", "hello");
        String json = DingJson.toJSONString(map);
        Assert.assertEquals("{\"x\":10,\"y\":\"hello\"}", json);
    }

    @Test
    public void serializeNestedMap() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", 2);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("a", inner);
        String json = DingJson.toJSONString(outer);
        Assert.assertEquals("{\"a\":{\"b\":2}}", json);
    }

    @Test
    public void serializeMapWithNullKey() {
        // HashMap allows null key; JSONObject wraps LinkedHashMap which doesn't
        // But generic Map handling goes through new JSONObject(map) which uses LinkedHashMap
        // so null keys will NPE — this is expected behavior
    }

    // ── Java Collection ───────────────────────────────────────────

    @Test
    public void serializeList() {
        List<Object> list = Arrays.asList(1, "two", null, true);
        String json = DingJson.toJSONString(list);
        Assert.assertEquals("[1,\"two\",null,true]", json);
    }

    @Test
    public void serializeListOfMaps() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("id", 1);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("id", 2);
        List<Object> list = Arrays.asList(m1, m2);
        String json = DingJson.toJSONString(list);
        Assert.assertEquals("[{\"id\":1},{\"id\":2}]", json);
    }

    @Test
    public void serializeSet() {
        Set<Object> set = new LinkedHashSet<>(Arrays.asList(1, 2, 3));
        String json = DingJson.toJSONString(set);
        Assert.assertEquals("[1,2,3]", json);
    }

    // ── Java array ────────────────────────────────────────────────

    @Test
    public void serializePrimitiveArray() {
        int[] arr = {1, 2, 3};
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[1,2,3]", json);
    }

    @Test
    public void serializeStringArray() {
        String[] arr = {"a", "b", "c"};
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[\"a\",\"b\",\"c\"]", json);
    }

    @Test
    public void serializeMixedArray() {
        Object[] arr = {1, "two", null, true};
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[1,\"two\",null,true]", json);
    }

    @Test
    public void serializeEmptyArray_() {
        int[] arr = {};
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[]", json);
    }

    @Test
    public void serializeBooleanArray() {
        boolean[] arr = {true, false, true};
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[true,false,true]", json);
    }

    @Test
    public void serializeDoubleArray() {
        double[] arr = {1.5, 2.5, 3.0};
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[1.5,2.5,3.0]", json);
    }

    // ── Pretty print ──────────────────────────────────────────────

    @Test
    public void prettyPrintSimpleObject() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", "hello");
        String pretty = DingJson.toJSONString(obj, true);
        // Expected:
        // {
        //   "a": 1,
        //   "b": "hello"
        // }
        Assert.assertTrue(pretty.startsWith("{"));
        Assert.assertTrue(pretty.endsWith("}"));
        Assert.assertTrue(pretty.contains("\n"));
        Assert.assertTrue(pretty.contains("\"a\": 1"));
        Assert.assertTrue(pretty.contains("\"b\": \"hello\""));
    }

    @Test
    public void prettyPrintEmptyObject() {
        Assert.assertEquals("{}", DingJson.toJSONString(new JSONObject(), true));
    }

    @Test
    public void prettyPrintArray() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        arr.add(2);
        String pretty = DingJson.toJSONString(arr, true);
        Assert.assertTrue(pretty.startsWith("["));
        Assert.assertTrue(pretty.endsWith("]"));
        Assert.assertTrue(pretty.contains("\n"));
    }

    @Test
    public void prettyPrintEmptyArray_() {
        Assert.assertEquals("[]", DingJson.toJSONString(new JSONArray(), true));
    }

    @Test
    public void prettyPrintNestedStructure() {
        JSONObject inner = new JSONObject();
        inner.put("x", 1);
        JSONObject outer = new JSONObject();
        outer.put("inner", inner);
        String pretty = DingJson.toJSONString(outer, true);
        Assert.assertTrue(pretty.contains("\"inner\": {"));
    }

    @Test
    public void prettyPrintNull() {
        Assert.assertEquals("null", DingJson.toJSONString(null, true));
    }

    @Test
    public void prettyPrintString() {
        Assert.assertEquals("\"hello\"", DingJson.toJSONString("hello", true));
    }

    // ── String escaping (inspired by org.json quoting tests) ──────

    @Test
    public void serializeStringWithQuotes() {
        Assert.assertEquals("\"\\\"hello\\\"\"", DingJson.toJSONString("\"hello\""));
    }

    @Test
    public void serializeStringWithBackslash() {
        Assert.assertEquals("\"a\\\\b\"", DingJson.toJSONString("a\\b"));
    }

    @Test
    public void serializeStringWithJsonEscapes() {
        Assert.assertEquals("\"a\\bb\\fc\\nd\\re\\tf\"",
                DingJson.toJSONString("a\bb\fc\nd\re\tf"));
    }

    @Test
    public void serializeStringWithControlChars() {
        StringBuilder sb = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            sb.append(c);
        }
        String json = DingJson.toJSONString(sb.toString());
        // Named JSON escapes use \\b \\f \\n \\r \\t; the rest use \\uXXXX
        Assert.assertTrue("tab", json.contains("\\t"));
        Assert.assertTrue("lf", json.contains("\\n"));
        Assert.assertTrue("cr", json.contains("\\r"));
        Assert.assertTrue("backspace", json.contains("\\b"));
        Assert.assertTrue("formfeed", json.contains("\\f"));
        Assert.assertTrue("null byte", json.contains("\\u0000"));
        Assert.assertTrue("0x01", json.contains("\\u0001"));
        Assert.assertTrue("0x1f", json.contains("\\u001f"));
    }

    @Test
    public void serializeStringUnicodePassThrough() {
        // Characters above 0x7F should NOT be escaped (per JSON spec)
        String json = DingJson.toJSONString("中文");
        Assert.assertEquals("\"中文\"", json);
    }

    @Test
    public void serializeStringWithSlash() {
        // Forward slash is not escaped by default (not required by JSON spec)
        String json = DingJson.toJSONString("/path/to/file");
        Assert.assertEquals("\"/path/to/file\"", json);
    }

    // ── Bean serialization ────────────────────────────────────────

    public static class SimpleBean {
        private String name = "test";
        private int value = 42;
        public String getName() { return name; }
        public int getValue() { return value; }
    }

    @Test
    public void serializeBean() {
        String json = DingJson.toJSONString(new SimpleBean());
        Assert.assertTrue(json.contains("\"name\":\"test\""));
        Assert.assertTrue(json.contains("\"value\":42"));
    }

    public static class BeanWithNull {
        private String name = "hello";
        private String nullable = null;
        public String getName() { return name; }
        public String getNullable() { return nullable; }
    }

    @Test
    public void serializeBeanWithNullField() {
        String json = DingJson.toJSONString(new BeanWithNull());
        Assert.assertTrue(json.contains("\"nullable\":null"));
    }

    public static class BeanWithTransient {
        private String keep = "keep";
        private transient String skip = "skip";
        public String getKeep() { return keep; }
        public String getSkip() { return skip; }
    }

    @Test
    public void serializeBeanSkipsTransient() {
        String json = DingJson.toJSONString(new BeanWithTransient());
        Assert.assertTrue(json.contains("\"keep\":\"keep\""));
        Assert.assertFalse(json.contains("skip"));
    }

    public static class BeanWithStatic {
        private static String STATIC = "static";
        private String normal = "normal";
        public String getNormal() { return normal; }
    }

    @Test
    public void serializeBeanSkipsStatic() {
        String json = DingJson.toJSONString(new BeanWithStatic());
        Assert.assertTrue(json.contains("\"normal\":\"normal\""));
        Assert.assertFalse(json.contains("STATIC"));
    }

    // ── Bean: toJSON convenience (inspired by Gson) ───────────────

    public static class Address {
        private String city = "Shanghai";
        private String zip = "200000";
        public String getCity() { return city; }
        public String getZip() { return zip; }
    }

    public static class Person {
        private String name = "Alice";
        private int age = 30;
        private Address address = new Address();
        public String getName() { return name; }
        public int getAge() { return age; }
        public Address getAddress() { return address; }
    }

    @Test
    public void toJSONFromNestedBean() {
        JSONObject obj = DingJson.toJSON(new Person());
        Assert.assertEquals("Alice", obj.getString("name"));
        Assert.assertEquals(Integer.valueOf(30), obj.getInteger("age"));
        JSONObject addr = obj.getJSONObject("address");
        Assert.assertEquals("Shanghai", addr.getString("city"));
    }

    @Test
    public void toJSONFromNull() {
        Assert.assertNull(DingJson.toJSON(null));
    }

    @Test
    public void toJSONFromJSONObject() {
        JSONObject original = new JSONObject();
        original.put("a", 1);
        JSONObject result = DingJson.toJSON(original);
        Assert.assertSame(original, result); // same instance
    }

    @Test
    public void toJSONArrayFromNull() {
        Assert.assertNull(DingJson.toJSONArray(null));
    }

    @Test
    public void toJSONArrayFromJSONArray() {
        JSONArray original = new JSONArray();
        original.add(1);
        JSONArray result = DingJson.toJSONArray(original);
        Assert.assertSame(original, result);
    }

    @Test
    public void toJSONArrayFromList() {
        List<Object> list = Arrays.asList(1, 2, 3);
        JSONArray arr = DingJson.toJSONArray(list);
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals(Integer.valueOf(1), arr.getInteger(0));
    }

    @Test
    public void toJSONArrayFromPrimitiveArray() {
        int[] input = {1, 2, 3};
        JSONArray arr = DingJson.toJSONArray(input);
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals(Integer.valueOf(1), arr.getInteger(0));
    }

    @Test(expected = JSONException.class)
    public void toJSONArrayFromInvalidType() {
        DingJson.toJSONArray("not an array");
    }

    // ── Round-trip with bean ──────────────────────────────────────

    @Test
    public void beanToJsonToBeanRoundTrip() {
        Person p = new Person();
        String json = DingJson.toJSONString(p);
        Person restored = DingJson.parseObject(json, Person.class);
        Assert.assertEquals(p.getName(), restored.getName());
        Assert.assertEquals(p.getAge(), restored.getAge());
        Assert.assertEquals(p.getAddress().getCity(), restored.getAddress().getCity());
    }
}
