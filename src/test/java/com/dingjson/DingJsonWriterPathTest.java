package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.serializer.JSONWriter;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Wave-3 tests: JSONWriter streaming builder, JSONPath queries, toJSONBytes.
 */
public class DingJsonWriterPathTest {

    // ══════════════════════════════════════════════════════════════
    // JSONWriter
    // ══════════════════════════════════════════════════════════════

    @Test
    public void writeSimpleObject() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("name").value("Ding");
        w.key("age").value(3);
        w.endObject();
        Assert.assertEquals("{\"name\":\"Ding\",\"age\":3}", w.toString());
    }

    @Test
    public void writeNested() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("user").beginObject();
        w.key("id").value(7L);
        w.endObject();
        w.key("tags").beginArray().value("a").value("b").endArray();
        w.endObject();
        Assert.assertEquals("{\"user\":{\"id\":7},\"tags\":[\"a\",\"b\"]}", w.toString());
    }

    @Test
    public void writeEmptyContainers() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("a").beginObject().endObject();
        w.key("b").beginArray().endArray();
        w.endObject();
        Assert.assertEquals("{\"a\":{},\"b\":[]}", w.toString());
    }

    @Test
    public void writeArrayOfObjects() {
        JSONWriter w = JSONWriter.create();
        w.beginArray();
        w.beginObject().key("x").value(1).endObject();
        w.beginObject().key("x").value(2).endObject();
        w.endArray();
        Assert.assertEquals("[{\"x\":1},{\"x\":2}]", w.toString());
    }

    @Test
    public void writeAllValueTypes() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("s").value("str");
        w.key("i").value(1);
        w.key("l").value(2147483648L);
        w.key("d").value(1.5);
        w.key("b").value(true);
        w.key("n").valueNull();
        w.key("o").value(new JSONObject()); // delegate
        w.endObject();
        Assert.assertEquals("{\"s\":\"str\",\"i\":1,\"l\":2147483648,\"d\":1.5,\"b\":true,\"n\":null,\"o\":{}}",
                w.toString());
    }

    @Test
    public void writeQuoting() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("k\"ey").value("v<al>ue");
        w.endObject();
        Assert.assertEquals("{\"k\\\"ey\":\"v\\u003cal\\u003eue\"}", w.toString());
    }

    @Test
    public void writeNaNAsNull() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("x").value(Double.NaN);
        w.endObject();
        Assert.assertEquals("{\"x\":null}", w.toString());
    }

    @Test
    public void writerRoundTripWithParser() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.key("items").beginArray();
        for (int i = 0; i < 3; i++) {
            w.beginObject().key("id").value(i).key("name").value("item-" + i).endObject();
        }
        w.endArray();
        w.endObject();

        JSONObject parsed = DingJson.parseObject(w.toString());
        Assert.assertEquals(3, parsed.getJSONArray("items").size());
        Assert.assertEquals("item-2", parsed.getJSONArray("items").getJSONObject(2).getString("name"));
    }

    @Test(expected = JSONException.class)
    public void unclosedStructureFailsOnToString() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.toString(); // depth != 0
    }

    @Test(expected = JSONException.class)
    public void keyOutsideObjectFails() {
        JSONWriter.create().key("x");
    }

    @Test(expected = JSONException.class)
    public void valueWhereKeyExpectedFails() {
        JSONWriter w = JSONWriter.create();
        w.beginObject();
        w.value(1); // object expects a key
    }

    @Test(expected = JSONException.class)
    public void multipleRootsFails() {
        JSONWriter w = JSONWriter.create();
        w.beginObject().endObject();
        w.beginObject(); // second root
    }

    // ══════════════════════════════════════════════════════════════
    // JSONPath
    // ══════════════════════════════════════════════════════════════

    private Object sampleTree() {
        return DingJson.parseObject(
                "{\"user\":{\"name\":\"Ding\",\"age\":3,\"tags\":[\"json\",\"java\"]},"
                        + "\"items\":[{\"id\":1,\"price\":10.5},{\"id\":2,\"price\":20.0}],"
                        + "\"meta\":{\"nested\":{\"deep\":{\"leaf\":\"found\"}}}}");
    }

    @Test
    public void pathRootField() {
        Assert.assertEquals("Ding", JSONPath.eval(sampleTree(), "$.user.name"));
    }

    @Test
    public void pathArrayIndex() {
        Assert.assertEquals(Integer.valueOf(2), JSONPath.eval(sampleTree(), "$.items[1].id"));
    }

    @Test
    public void pathNegativeIndex() {
        Assert.assertEquals("java", JSONPath.eval(sampleTree(), "$.user.tags[-1]"));
    }

    @Test
    public void pathWildcard() {
        @SuppressWarnings("unchecked")
        List<Object> names = (List<Object>) JSONPath.eval(sampleTree(), "$.items[*].id");
        Assert.assertEquals(Arrays.asList(1, 2), names);
    }

    @Test
    public void pathWildcardAllValues() {
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) JSONPath.eval(sampleTree(), "$.user.tags[*]");
        Assert.assertEquals(Arrays.asList("json", "java"), tags);
    }

    @Test
    public void pathRecursiveDescent() {
        Assert.assertEquals("found", JSONPath.eval(sampleTree(), "$..leaf"));
    }

    @Test
    public void pathRootItself() {
        Assert.assertNotNull(JSONPath.eval(sampleTree(), "$"));
    }

    @Test
    public void pathMissingReturnsNull() {
        Assert.assertNull(JSONPath.eval(sampleTree(), "$.user.missing"));
        Assert.assertNull(JSONPath.eval(sampleTree(), "$.items[99]"));
    }

    @Test
    public void pathTyped() {
        Assert.assertEquals("Ding", JSONPath.eval(sampleTree(), "$.user.name", String.class));
        Assert.assertEquals(Integer.valueOf(3), JSONPath.eval(sampleTree(), "$.user.age", Integer.class));
        Assert.assertEquals(Double.valueOf(10.5), JSONPath.eval(sampleTree(), "$.items[0].price", Double.class));
        Assert.assertEquals("3", JSONPath.eval(sampleTree(), "$.user.age", String.class)); // coercion
    }

    @Test
    public void pathWithQuotedBracketName() {
        Object tree = DingJson.parseObject("{\"weird key\":42}");
        Assert.assertEquals(Integer.valueOf(42), JSONPath.eval(tree, "$[\"weird key\"]"));
    }

    @Test(expected = JSONException.class)
    public void invalidPathThrows() {
        JSONPath.eval(sampleTree(), "$.user[");
    }

    // ══════════════════════════════════════════════════════════════
    // toJSONBytes
    // ══════════════════════════════════════════════════════════════

    @Test
    public void bytesAreUtf8() {
        JSONObject obj = new JSONObject();
        obj.put("中文", "值");
        byte[] bytes = DingJson.toJSONBytes(obj);
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        Assert.assertEquals("{\"中文\":\"值\"}", decoded);
    }

    @Test
    public void bytesMatchString() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        Assert.assertArrayEquals(
                DingJson.toJSONString(obj).getBytes(StandardCharsets.UTF_8),
                DingJson.toJSONBytes(obj));
    }

    @Test
    public void mapperBytes() {
        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder().writeNullFields(false).build());
        JSONObject obj = new JSONObject();
        obj.put("a", null);
        obj.put("b", 2);
        Assert.assertEquals("{\"b\":2}", new String(mapper.toJSONBytes(obj), StandardCharsets.UTF_8));
    }
}
