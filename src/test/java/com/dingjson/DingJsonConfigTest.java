package com.dingjson;

import com.dingjson.annotation.JSONField;
import com.dingjson.element.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.Date;

/**
 * Wave-2 tests: configurable mapper ({@link DingJsonMapper} / {@link DingJsonConfig})
 * and {@link JSONField} annotation support.
 */
public class DingJsonConfigTest {

    // ── Annotated bean ────────────────────────────────────────────

    public static class User {
        @JSONField(name = "user_name")
        public String userName;

        @JSONField(serialize = false)
        public String passwordHash;

        @JSONField(deserialize = false)
        public long createdAt;

        @JSONField(name = "updated", format = "yyyy-MM-dd")
        public Date updatedAt;

        public String nickname; // plain field, no annotation
    }

    private User sample() {
        User u = new User();
        u.userName = "ding";
        u.passwordHash = "s3cret";
        u.createdAt = 123456789L;
        u.updatedAt = new Date(1700000000000L);
        u.nickname = "Ding";
        return u;
    }

    // ══════════════════════════════════════════════════════════════
    // @JSONField name / serialize / deserialize / format
    // ══════════════════════════════════════════════════════════════

    @Test
    public void annotationRenamesField() {
        User u = sample();
        String json = DingJson.toJSONString(u);
        Assert.assertTrue(json.contains("\"user_name\":\"ding\""));
        Assert.assertFalse(json.contains("userName"));
    }

    @Test
    public void annotationSkipsSerialization() {
        User u = sample();
        String json = DingJson.toJSONString(u);
        Assert.assertFalse("passwordHash must be skipped", json.contains("s3cret"));
    }

    @Test
    public void annotationSkipsDeserialization() {
        // createdAt has deserialize=false — input value must be ignored
        String json = "{\"createdAt\":999,\"user_name\":\"x\",\"nickname\":\"y\"}";
        User u = DingJson.parseObject(json, User.class);
        Assert.assertEquals(0, u.createdAt); // untouched default
        Assert.assertEquals("x", u.userName);
    }

    @Test
    public void annotationFormatsDate() {
        User u = sample();
        String json = DingJson.toJSONString(u);
        // format applies: date becomes the formatted string, not epoch millis
        String expected = new java.text.SimpleDateFormat("yyyy-MM-dd").format(u.updatedAt);
        Assert.assertTrue(json.contains("\"updated\":\"" + expected + "\""));
    }

    @Test
    public void annotationRoundTrip() {
        User u = sample();
        String json = DingJson.toJSONString(u);
        User back = DingJson.parseObject(json, User.class);
        Assert.assertEquals("ding", back.userName);
        Assert.assertNull(back.passwordHash); // never serialized
        Assert.assertEquals(0, back.createdAt); // deserialize=false
        Assert.assertNotNull(back.updatedAt); // formatted date parses back
        Assert.assertEquals("Ding", back.nickname);
    }

    // ══════════════════════════════════════════════════════════════
    // DingJsonConfig.writeNullFields
    // ══════════════════════════════════════════════════════════════

    @Test
    public void skipNullFieldsInBean() {
        User u = sample();
        u.nickname = null;
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder().writeNullFields(false).build());
        String json = mapper.toJSONString(u);
        Assert.assertFalse("null fields omitted", json.contains("\"nickname\":null"));
        Assert.assertTrue(json.contains("\"user_name\":\"ding\""));
    }

    @Test
    public void skipNullFieldsInJSONObject() {
        JSONObject obj = new JSONObject();
        obj.put("a", null);
        obj.put("b", 1);
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder().writeNullFields(false).build());
        Assert.assertEquals("{\"b\":1}", mapper.toJSONString(obj));
    }

    @Test
    public void skipNullFieldsPretty() {
        JSONObject obj = new JSONObject();
        obj.put("a", null);
        obj.put("b", 1);
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder().writeNullFields(false).build());
        Assert.assertFalse(mapper.toJSONString(obj, true).contains("null"));
    }

    @Test
    public void defaultConfigStillWritesNulls() {
        JSONObject obj = new JSONObject();
        obj.put("a", null);
        Assert.assertEquals("{\"a\":null}", DingJson.toJSONString(obj)); // static API unchanged
    }

    // ══════════════════════════════════════════════════════════════
    // DingJsonConfig.dateFormat (global)
    // ══════════════════════════════════════════════════════════════

    public static class Event {
        public Date when;
        public String name;
    }

    @Test
    public void globalDateFormat() {
        Event e = new Event();
        e.when = new Date(1700000000000L);
        e.name = "x";
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
                .dateFormat("yyyy-MM-dd HH:mm:ss").build());
        String json = mapper.toJSONString(e);
        String expected = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(e.when);
        Assert.assertTrue(json.contains("\"when\":\"" + expected + "\""));
    }

    @Test
    public void globalDateFormatRoundTrip() {
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
                .dateFormat("yyyy-MM-dd HH:mm:ss").build());
        Event e = new Event();
        e.when = new Date(1700000000000L);
        e.name = "x";
        String json = mapper.toJSONString(e);
        Event back = mapper.parseObject(json, Event.class);
        Assert.assertEquals(e.when.getTime(), back.when.getTime());
    }

    @Test
    public void annotationFormatBeatsGlobal() {
        User u = sample();
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
                .dateFormat("yyyy-MM-dd HH:mm:ss").build());
        String json = mapper.toJSONString(u);
        // updatedAt has @JSONField(format="yyyy-MM-dd") — annotation wins
        String expected = new java.text.SimpleDateFormat("yyyy-MM-dd").format(u.updatedAt);
        Assert.assertTrue(json.contains("\"updated\":\"" + expected + "\""));
    }

    // ══════════════════════════════════════════════════════════════
    // DingJsonConfig.caseInsensitiveFields
    // ══════════════════════════════════════════════════════════════

    @Test
    public void caseInsensitiveFieldMatching() {
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
                .caseInsensitiveFields(true).build());
        User u = mapper.parseObject("{\"USER_NAME\":\"ci\",\"NickName\":\"n\"}", User.class);
        Assert.assertEquals("ci", u.userName);
        Assert.assertEquals("n", u.nickname);
    }

    @Test
    public void defaultIsCaseSensitive() {
        User u = DingJson.parseObject("{\"USER_NAME\":\"ci\"}", User.class);
        Assert.assertNull(u.userName); // exact match required by default
    }

    // ══════════════════════════════════════════════════════════════
    // Mapper covers the full surface
    // ══════════════════════════════════════════════════════════════

    @Test
    public void mapperParseAndSerialize() {
        DingJsonMapper mapper = DingJson.mapper();
        User u = sample();
        String json = mapper.toJSONString(u);
        User back = mapper.parseObject(json, User.class);
        Assert.assertEquals("ding", back.userName);
        Assert.assertEquals("Ding", back.nickname);
    }

    @Test
    public void mapperTypeReference() {
        DingJsonMapper mapper = DingJson.mapper();
        String json = "[{\"user_name\":\"a\"},{\"user_name\":\"b\"}]";
        java.util.List<User> users = mapper.parseObject(json, new TypeReference<java.util.List<User>>() {
        });
        Assert.assertEquals(2, users.size());
        Assert.assertEquals("b", users.get(1).userName);
    }

    @Test
    public void mapperParseArray() {
        DingJsonMapper mapper = DingJson.mapper();
        java.util.List<User> users = mapper.parseArray("[{\"user_name\":\"a\"}]", User.class);
        Assert.assertEquals("a", users.get(0).userName);
    }

    @Test
    public void mapperConfigIsImmutable() {
        DingJsonConfig base = DingJsonConfig.defaults();
        DingJsonConfig modified = DingJsonConfig.builder(base).writeNullFields(false).build();
        Assert.assertTrue(base.writeNullFields()); // original untouched
        Assert.assertFalse(modified.writeNullFields());
    }
}
