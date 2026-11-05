package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * Functional tests for DingJson core API.
 */
public class DingJsonTest {

    // ── Test POJO ──────────────────────────────────────────────────

    public static class User {
        private String name;
        private int age;
        private boolean active;
        private List<String> tags;

        public User() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    // ── parseObject ────────────────────────────────────────────────

    @Test
    public void testParseObject() {
        JSONObject obj = DingJson.parseObject("{\"name\":\"Ding\",\"age\":3}");
        Assert.assertEquals("Ding", obj.getString("name"));
        Assert.assertEquals(Integer.valueOf(3), obj.getInteger("age"));
    }

    @Test(expected = JSONException.class)
    public void testParseObjectWithArray() {
        DingJson.parseObject("[1,2,3]");
    }

    // ── parseObject with type ──────────────────────────────────────

    @Test
    public void testParseObjectTyped() {
        String json = "{\"name\":\"Alice\",\"age\":28,\"active\":true}";
        User user = DingJson.parseObject(json, User.class);
        Assert.assertEquals("Alice", user.getName());
        Assert.assertEquals(28, user.getAge());
        Assert.assertTrue(user.isActive());
    }

    // ── parseArray ─────────────────────────────────────────────────

    @Test
    public void testParseArray() {
        JSONArray arr = DingJson.parseArray("[1,2,3]");
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals(Integer.valueOf(1), arr.getInteger(0));
        Assert.assertEquals(Integer.valueOf(3), arr.getInteger(2));
    }

    @Test(expected = JSONException.class)
    public void testParseArrayWithObject() {
        DingJson.parseArray("{\"key\":\"val\"}");
    }

    // ── parseArray with type ───────────────────────────────────────

    @Test
    public void testParseArrayTyped() {
        String json = "[{\"name\":\"Alice\",\"age\":28},{\"name\":\"Bob\",\"age\":30}]";
        List<User> users = DingJson.parseArray(json, User.class);
        Assert.assertEquals(2, users.size());
        Assert.assertEquals("Alice", users.get(0).getName());
        Assert.assertEquals(30, users.get(1).getAge());
    }

    // ── parse (generic) ────────────────────────────────────────────

    @Test
    public void testParseNull() {
        Assert.assertNull(DingJson.parse("null"));
    }

    @Test
    public void testParseBoolean() {
        Assert.assertEquals(Boolean.TRUE, DingJson.parse("true"));
        Assert.assertEquals(Boolean.FALSE, DingJson.parse("false"));
    }

    @Test
    public void testParseNumber() {
        Assert.assertEquals(Integer.valueOf(42), DingJson.parse("42"));
        Assert.assertEquals(Long.valueOf(2147483648L), DingJson.parse("2147483648"));
        Assert.assertEquals(Double.valueOf(3.14), DingJson.parse("3.14"));
    }

    @Test
    public void testParseString() {
        Assert.assertEquals("hello", DingJson.parse("\"hello\""));
    }

    @Test
    public void testParseNested() {
        String json = "{\"list\":[1,2,{\"x\":\"y\"}],\"obj\":{\"a\":\"b\"}}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals(3, obj.getJSONArray("list").size());
        Assert.assertEquals("y", obj.getJSONArray("list").getJSONObject(2).getString("x"));
        Assert.assertEquals("b", obj.getJSONObject("obj").getString("a"));
    }

    // ── toJSONString ───────────────────────────────────────────────

    @Test
    public void testToJSONStringBasic() {
        JSONObject obj = new JSONObject();
        obj.put("name", "Ding");
        obj.put("age", 3);
        String json = DingJson.toJSONString(obj);
        Assert.assertTrue(json.contains("\"name\":\"Ding\""));
        Assert.assertTrue(json.contains("\"age\":3"));
    }

    @Test
    public void testToJSONStringNull() {
        Assert.assertEquals("null", DingJson.toJSONString(null));
    }

    @Test
    public void testToJSONStringArray() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        arr.add("two");
        arr.add(null);
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[1,\"two\",null]", json);
    }

    // ── toJSONString pretty ────────────────────────────────────────

    @Test
    public void testToJSONStringPretty() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        obj.put("b", "hello");
        String pretty = DingJson.toJSONString(obj, true);
        Assert.assertTrue(pretty.contains("\n"));
        Assert.assertTrue(pretty.contains("\"a\": 1"));
    }

    // ── toJSON ─────────────────────────────────────────────────────

    @Test
    public void testToJSONFromBean() {
        User user = new User();
        user.setName("Ding");
        user.setAge(3);
        JSONObject obj = DingJson.toJSON(user);
        Assert.assertEquals("Ding", obj.getString("name"));
        Assert.assertEquals(Integer.valueOf(3), obj.getInteger("age"));
    }

    @Test
    public void testToJSONFromMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", 10);
        map.put("y", "z");
        JSONObject obj = DingJson.toJSON(map);
        Assert.assertEquals(Integer.valueOf(10), obj.getInteger("x"));
        Assert.assertEquals("z", obj.getString("y"));
    }

    // ── String escaping ────────────────────────────────────────────

    @Test
    public void testStringEscaping() {
        String json = DingJson.toJSONString("hello\n\"world\"");
        Assert.assertEquals("\"hello\\n\\\"world\\\"\"", json);
    }

    @Test
    public void testUnicodeEscape() {
        String json = "{\"char\":\"\\u0048\"}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals("H", obj.getString("char"));
    }

    // ── Edge cases ─────────────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void testEmptyInput() {
        DingJson.parse("");
    }

    @Test(expected = JSONException.class)
    public void testNullInput() {
        DingJson.parse((String) null);
    }

    @Test(expected = JSONException.class)
    public void testTrailingContent() {
        DingJson.parse("{} extra");
    }

    @Test
    public void testEmptyObject() {
        JSONObject obj = DingJson.parseObject("{}");
        Assert.assertTrue(obj.isEmpty());
    }

    @Test
    public void testEmptyArray() {
        JSONArray arr = DingJson.parseArray("[]");
        Assert.assertTrue(arr.isEmpty());
    }

    @Test
    public void testWhitespaceTolerance() {
        JSONObject obj = DingJson.parseObject("  {  \"a\"  :  1  }  ");
        Assert.assertEquals(Integer.valueOf(1), obj.getInteger("a"));
    }
}
