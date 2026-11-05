package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.parser.JSONReader;
import com.dingjson.serializer.JSONWriter;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Wave-3 tests: top-level API extension — IO overloads (byte[]/InputStream/
 * Reader/File), static config overloads, eval / isValid / of / createWriter,
 * the skip parser, and deepCopy.
 */
public class DingJsonIoTest {

    public static class Address {
        public String city;
        public int zip;
    }

    public static class User {
        public String name;
        public int age;
        public Address address;
    }

    private User sample() {
        User u = new User();
        u.name = "丁丁";
        u.age = 3;
        u.address = new Address();
        u.address.city = "深圳";
        u.address.zip = 518000;
        return u;
    }

    // ── byte[] / InputStream / Reader / File parse families ─────────

    @Test
    public void parseBytesFamily() {
        JSONObject obj = DingJson.parseObject("{\"name\":\"丁丁\",\"age\":3}".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals("丁丁", obj.getString("name"));
        Assert.assertEquals(3, (int) obj.getInteger("age"));

        User u = DingJson.parseObject("{\"name\":\"丁丁\",\"age\":3}".getBytes(StandardCharsets.UTF_8), User.class);
        Assert.assertEquals("丁丁", u.name);
        Assert.assertEquals(3, u.age);

        User typed = DingJson.parseObject("{\"name\":\"b\"}".getBytes(StandardCharsets.UTF_8),
                new TypeReference<User>() {});
        Assert.assertEquals("b", typed.name);

        JSONArray arr = DingJson.parseArray("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals(2, (int) arr.getInteger(1));

        List<User> users = DingJson.parseArray("[{\"name\":\"a\"},{\"name\":\"b\"}]".getBytes(StandardCharsets.UTF_8), User.class);
        Assert.assertEquals(2, users.size());
        Assert.assertEquals("b", users.get(1).name);

        List<String> names = DingJson.parseArray("[\"x\",\"y\"]".getBytes(StandardCharsets.UTF_8),
                new TypeReference<String>() {});
        Assert.assertEquals(2, names.size());
        Assert.assertEquals("y", names.get(1));

        List<List<Integer>> matrix = DingJson.parseObject("[[1,2],[3]]".getBytes(StandardCharsets.UTF_8),
                new TypeReference<List<List<Integer>>>() {});
        Assert.assertEquals(Integer.valueOf(3), matrix.get(1).get(0));

        Object v = DingJson.parse("[42]".getBytes(StandardCharsets.UTF_8));
        Assert.assertTrue(v instanceof JSONArray);
    }

    @Test
    public void parseStreamFamily() {
        String json = "{\"name\":\"流\",\"age\":7,\"address\":{\"city\":\"上海\",\"zip\":200000}}";
        User fromStream = DingJson.parseObject(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), User.class);
        Assert.assertEquals("流", fromStream.name);
        Assert.assertEquals("上海", fromStream.address.city);

        JSONObject obj = DingJson.parseObject(new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(1, (int) obj.getInteger("a"));

        JSONArray arr = DingJson.parseArray(new ByteArrayInputStream("[true,false]".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(Boolean.TRUE, arr.getBoolean(0));
        Assert.assertEquals(Boolean.FALSE, arr.getBoolean(1));

        List<Integer> ints = DingJson.parseArray(new ByteArrayInputStream("[1,2]".getBytes(StandardCharsets.UTF_8)), Integer.class);
        Assert.assertEquals(2, ints.size());

        Object v = DingJson.parse(new ByteArrayInputStream("\"str\"".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals("str", v);
    }

    @Test
    public void parseReaderFamily() {
        String json = "{\"name\":\"读\",\"age\":1}";
        User u = DingJson.parseObject(new java.io.StringReader(json), User.class);
        Assert.assertEquals("读", u.name);

        JSONObject obj = DingJson.parseObject(new java.io.StringReader("{\"a\":2}"));
        Assert.assertEquals(2, (int) obj.getInteger("a"));

        JSONArray arr = DingJson.parseArray(new java.io.StringReader("[1,2,3]"));
        Assert.assertEquals(3, arr.size());

        List<User> users = DingJson.parseArray(new java.io.StringReader("[{\"name\":\"a\"}]"), User.class);
        Assert.assertEquals(1, users.size());

        Object v = DingJson.parse(new java.io.StringReader("null"));
        Assert.assertNull(v);
    }

    @Test
    public void parseFileFamily() throws IOException {
        File f = File.createTempFile("dingjson-io", ".json");
        try {
            User u = sample();
            DingJson.writeTo(f, u);
            Assert.assertTrue(f.length() > 0);

            User fromFile = DingJson.parseObject(f, User.class);
            Assert.assertEquals(u.name, fromFile.name);
            Assert.assertEquals(u.address.city, fromFile.address.city);
            Assert.assertEquals(u.address.zip, fromFile.address.zip);

            JSONObject obj = DingJson.parseObject(f);
            Assert.assertEquals(u.name, obj.getString("name"));

            // An object document is not a valid array
            try {
                DingJson.parseArray(f);
                Assert.fail("expected JSONException");
            } catch (JSONException expected) {
                // ok
            }

            // Typed array from a file
            File arrFile = File.createTempFile("dingjson-arr", ".json");
            try {
                DingJson.writeTo(arrFile, JSONArray.of(1, 2, 3));
                List<Integer> ints = DingJson.parseArray(arrFile, Integer.class);
                Assert.assertEquals(3, ints.size());
            } finally {
                arrFile.delete();
            }
        } finally {
            f.delete();
        }
    }

    @Test
    public void missingFileThrows() {
        try {
            DingJson.parseObject(new File("/nonexistent/dingjson/nope.json"));
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
    }

    @Test
    public void invalidUtf8Rejected() {
        byte[] bad = {(byte) 0xC3, 0x28}; // malformed 2-byte sequence
        try {
            DingJson.parse(bad);
            Assert.fail("expected JSONException");
        } catch (JSONException e) {
            Assert.assertTrue("message should mention UTF-8: " + e.getMessage(),
                    e.getMessage().contains("UTF-8"));
        }
        try {
            DingJson.parse(new ByteArrayInputStream(bad));
            Assert.fail("expected JSONException");
        } catch (JSONException e) {
            Assert.assertTrue(e.getMessage().contains("UTF-8"));
        }
    }

    @Test
    public void nullInputsRejected() {
        try {
            DingJson.parse((byte[]) null);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        try {
            DingJson.parseObject((java.io.InputStream) null);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        try {
            DingJson.writeTo((File) null, new Object());
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
    }

    // ── writeTo ─────────────────────────────────────────────────────

    @Test
    public void writeToOutputStream() {
        JSONObject obj = JSONObject.of("name", "Ding", "nested", JSONObject.of("x", 1));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DingJson.writeTo(bos, obj);
        Assert.assertEquals(obj.toString(), new String(bos.toByteArray(), StandardCharsets.UTF_8));

        ByteArrayOutputStream prettyBos = new ByteArrayOutputStream();
        DingJson.writeTo(prettyBos, obj, true);
        String pretty = new String(prettyBos.toByteArray(), StandardCharsets.UTF_8);
        Assert.assertTrue(pretty.contains("\n"));
    }

    @Test
    public void writeToFilePrettyRoundTrip() throws IOException {
        JSONObject obj = JSONObject.of("name", "Ding");
        File f = File.createTempFile("dingjson-pretty", ".json");
        try {
            DingJson.writeTo(f, obj, true);
            String pretty = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertTrue(pretty.contains("\n"));
            JSONObject readBack = DingJson.parseObject(f);
            Assert.assertEquals(obj, readBack);
        } finally {
            f.delete();
        }
    }

    @Test
    public void mapperIoUsesItsConfig() {
        DingJsonMapper strict = DingJson.mapper(DingJsonConfig.builder().maxObjectSize(1).build());
        try {
            strict.parseObject("{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8));
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        // The limit applies but well-formed small input still works
        JSONObject ok = strict.parseObject(new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(1, (int) ok.getInteger("a"));
    }

    @Test
    public void mapperWriteToUsesConfig() throws IOException {
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder().writeNullFields(false).build());
        JSONObject obj = JSONObject.of("a", 1, "n", null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        mapper.writeTo(bos, obj);
        Assert.assertEquals("{\"a\":1}", new String(bos.toByteArray(), StandardCharsets.UTF_8));

        File f = File.createTempFile("dingjson-mapper", ".json");
        try {
            mapper.writeTo(f, obj);
            Assert.assertEquals("{\"a\":1}", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } finally {
            f.delete();
        }
    }

    // ── Static config overloads ─────────────────────────────────────

    @Test
    public void configOverloads() {
        DingJsonConfig strict = DingJsonConfig.builder().maxObjectSize(1).build();

        try {
            DingJson.parseObject("{\"a\":1,\"b\":2}", strict);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        JSONObject ok = DingJson.parseObject("{\"a\":1}", strict);
        Assert.assertEquals(1, (int) ok.getInteger("a"));

        // Typed / list / generic / scalar entries honour the config too
        try {
            DingJson.parseObject("{\"a\":1,\"b\":2}", User.class, strict);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        try {
            DingJson.parseObject("{\"a\":1,\"b\":2}", new TypeReference<User>() {}, strict);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }

        DingJsonConfig arrCfg = DingJsonConfig.builder().maxArraySize(2).build();
        try {
            DingJson.parseArray("[1,2,3]", arrCfg);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
        List<Integer> two = DingJson.parseArray("[1,2]", Integer.class, arrCfg);
        Assert.assertEquals(2, two.size());
        try {
            DingJson.parseArray("[1,2,3]", new TypeReference<Integer>() {}, arrCfg);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }

        // A null config behaves like the defaults
        JSONObject def = DingJson.parseObject("{\"a\":1}", (DingJsonConfig) null);
        Assert.assertEquals(1, (int) def.getInteger("a"));
    }

    @Test
    public void configOverloadReader() {
        JSONReader r = DingJson.createReader("{\"a\":1}", DingJsonConfig.builder().maxObjectSize(1).build());
        JSONObject obj = r.readJSONObject();
        Assert.assertEquals(1, (int) obj.getInteger("a"));
        r.ensureEnded();
    }

    // ── eval ────────────────────────────────────────────────────────

    @Test
    public void evalOneStep() {
        String json = "{\"items\":[{\"price\":9.9},{\"price\":12.5}],\"count\":7}";
        Object price = DingJson.eval(json, "$.items[1].price");
        Assert.assertEquals(12.5, ((Number) price).doubleValue(), 0.0001);

        Double d = DingJson.eval(json, "$.items[0].price", Double.class);
        Assert.assertEquals(9.9, d, 0.0001);

        int count = DingJson.eval(json, "$.count", int.class);
        Assert.assertEquals(7, count);

        Assert.assertNull(DingJson.eval(json, "$.missing"));
        Assert.assertNull(DingJson.eval(json, "$.items[9]"));
    }

    // ── isValid (skip parser) ───────────────────────────────────────

    @Test
    public void isValid() {
        Assert.assertTrue(DingJson.isValid("{\"a\":[1,2,{\"b\":null}],\"c\":\"x\"}"));
        Assert.assertTrue(DingJson.isValid("\"str\""));
        Assert.assertTrue(DingJson.isValid("42"));
        Assert.assertTrue(DingJson.isValid("-1.5e3"));
        Assert.assertTrue(DingJson.isValid("null"));
        Assert.assertTrue(DingJson.isValid("[]"));
        Assert.assertTrue(DingJson.isValid("{}"));

        Assert.assertFalse(DingJson.isValid("{"));
        Assert.assertFalse(DingJson.isValid("{\"a\":1,}"));
        Assert.assertFalse(DingJson.isValid("{\"a\":1} extra"));
        Assert.assertFalse(DingJson.isValid("[1,2"));
        Assert.assertFalse(DingJson.isValid("abc"));
        Assert.assertFalse(DingJson.isValid(""));
        Assert.assertFalse(DingJson.isValid("   "));
        Assert.assertFalse(DingJson.isValid(null));
    }

    @Test
    public void isValidDeepNestingNoTree() {
        // 1000 nested arrays exceed MAX_DEPTH — rejected without stack overflow
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append('[');
        }
        for (int i = 0; i < 1000; i++) {
            sb.append(']');
        }
        Assert.assertFalse(DingJson.isValid(sb.toString()));
    }

    @Test
    public void skipValueConsumesExactlyOneValue() {
        JSONReader r = DingJson.createReader("{\"skip\":{\"a\":[1,2,{\"b\":3}]},\"keep\":42}");
        r.readBeginObject();
        Assert.assertEquals("skip", r.readKey());
        r.skipValue();
        Assert.assertEquals("keep", r.readKey());
        Assert.assertEquals(42, r.readInt());
        r.readEndObject();
        r.ensureEnded();

        // Invalid value inside a skipped region still fails
        JSONReader bad = DingJson.createReader("{\"a\":}");
        bad.readBeginObject();
        bad.readKey();
        try {
            bad.skipValue();
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
    }

    // ── Factory methods ─────────────────────────────────────────────

    @Test
    public void ofFactories() {
        JSONObject obj = JSONObject.of("name", "Ding", "age", 3);
        Assert.assertEquals(2, obj.size());
        Assert.assertEquals("Ding", obj.getString("name"));
        Assert.assertEquals(3, (int) obj.getInteger("age"));

        JSONObject nested = JSONObject.of("a", JSONArray.of(1, 2));
        Assert.assertEquals(2, nested.getJSONArray("a").size());

        try {
            JSONObject.of("name");
            Assert.fail("expected JSONException for odd arguments");
        } catch (JSONException expected) {
            // ok
        }
        try {
            JSONObject.of(42, "v");
            Assert.fail("expected JSONException for non-string key");
        } catch (JSONException expected) {
            // ok
        }

        JSONArray arr = JSONArray.of(1, "two", null);
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals("two", arr.getString(1));
        Assert.assertNull(arr.get(2));
    }

    @Test
    public void createWriterFacade() {
        JSONWriter w = DingJson.createWriter();
        w.beginObject().key("a").value(1).endObject();
        Assert.assertEquals("{\"a\":1}", w.toString());

        JSONWriter w2 = DingJson.createWriter(64);
        w2.beginArray().value(1).value(2).endArray();
        Assert.assertEquals("[1,2]", w2.toString());
    }

    // ── deepCopy ────────────────────────────────────────────────────

    @Test
    public void deepCopyTree() {
        JSONObject src = JSONObject.of("a", 1, "n", JSONObject.of("x", JSONArray.of(1, 2)));
        JSONObject copy = DingJson.deepCopy(src);

        Assert.assertNotSame(src, copy);
        Assert.assertNotSame(src.getJSONObject("n"), copy.getJSONObject("n"));
        Assert.assertNotSame(src.getJSONObject("n").getJSONArray("x"), copy.getJSONObject("n").getJSONArray("x"));
        Assert.assertEquals(src, copy);

        // Mutating the copy leaves the original untouched
        copy.put("a", 999);
        copy.getJSONObject("n").put("y", true);
        copy.getJSONObject("n").getJSONArray("x").add(3);
        Assert.assertEquals(1, (int) src.getInteger("a"));
        Assert.assertFalse(src.getJSONObject("n").containsKey("y"));
        Assert.assertEquals(2, src.getJSONObject("n").getJSONArray("x").size());
    }

    @Test
    public void deepCopyArray() {
        JSONArray arr = JSONArray.of(JSONObject.of("k", JSONArray.of(1)));
        JSONArray copy = DingJson.deepCopy(arr);

        Assert.assertNotSame(arr, copy);
        Assert.assertNotSame(arr.getJSONObject(0), copy.getJSONObject(0));
        Assert.assertNotSame(arr.getJSONObject(0).getJSONArray("k"), copy.getJSONObject(0).getJSONArray("k"));
        Assert.assertEquals(arr, copy);

        copy.getJSONObject(0).put("k2", true);
        Assert.assertFalse(arr.getJSONObject(0).containsKey("k2"));
    }

    @Test
    public void deepCopyBean() {
        User u = sample();
        User copy = DingJson.deepCopy(u);

        Assert.assertNotSame(u, copy);
        Assert.assertNotSame(u.address, copy.address);
        Assert.assertEquals(u.name, copy.name);
        Assert.assertEquals(u.age, copy.age);
        Assert.assertEquals("深圳", copy.address.city);
        Assert.assertEquals(518000, copy.address.zip);

        copy.name = "changed";
        copy.address.city = "广州";
        Assert.assertEquals("丁丁", u.name);
        Assert.assertEquals("深圳", u.address.city);
    }

    @Test
    public void deepCopyGenericList() {
        User a = new User();
        a.name = "a";
        a.address = new Address();
        a.address.city = "BJ";
        List<User> users = new ArrayList<>();
        users.add(a);

        List<User> copy = DingJson.deepCopy(users, new TypeReference<List<User>>() {});
        Assert.assertNotSame(users, copy);
        Assert.assertEquals(1, copy.size());
        Assert.assertNotSame(users.get(0), copy.get(0));
        Assert.assertEquals("a", copy.get(0).name);
        Assert.assertEquals("BJ", copy.get(0).address.city);

        // Without a TypeReference, elements degrade to tree nodes (documented)
        Object looseObj = DingJson.deepCopy((Object) users);
        Assert.assertTrue(looseObj instanceof List);
        List<?> loose = (List<?>) looseObj;
        Assert.assertEquals(1, loose.size());
        Assert.assertTrue(loose.get(0) instanceof JSONObject);
    }

    @Test
    public void deepCopyNullAndScalar() {
        Assert.assertNull(DingJson.deepCopy((JSONObject) null));
        Assert.assertNull(DingJson.deepCopy((Object) null));
        Assert.assertSame("s", DingJson.deepCopy("s"));
        Assert.assertEquals(Integer.valueOf(5), DingJson.deepCopy(5));
    }
}
