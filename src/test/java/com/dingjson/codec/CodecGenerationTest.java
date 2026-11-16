package com.dingjson.codec;

import com.dingjson.DingJson;
import com.dingjson.DingJsonConfig;
import com.dingjson.TypeReference;
import com.dingjson.element.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Date;

/**
 * A generated codec is a second implementation of the same thing the reflective
 * path does, so every assertion here compares the two implementations on
 * identical inputs — output must be byte-identical, or the codec is wrong.
 */
public class CodecGenerationTest {

    private static final long STAMP = 1735689600000L; // 2025-01-01T00:00:00Z

    private static CodecBean codecBean() {
        CodecBean bean = new CodecBean();
        bean.id = 42;
        bean.big = 9007199254740993L;
        bean.score = 98.5;
        bean.ratio = 0.25f;
        bean.flag = true;
        bean.letter = '"';
        bean.tiny = (byte) -7;
        bean.small = (short) 300;
        bean.name = "Ding <Json> & \"quoted\" \\ tail\nline";
        bean.created = new Date(STAMP);
        bean.birthday = new Date(STAMP);
        bean.tags = Arrays.asList("a", "b<b>");
        CodecChild child = new CodecChild();
        child.n = 1;
        child.label = "kid";
        bean.child = child;
        bean.original = "renamed value";
        bean.skippedOnWrite = "not written";
        return bean;
    }

    private static ReflectBean reflectBean() {
        ReflectBean bean = new ReflectBean();
        bean.id = 42;
        bean.big = 9007199254740993L;
        bean.score = 98.5;
        bean.ratio = 0.25f;
        bean.flag = true;
        bean.letter = '"';
        bean.tiny = (byte) -7;
        bean.small = (short) 300;
        bean.name = "Ding <Json> & \"quoted\" \\ tail\nline";
        bean.created = new Date(STAMP);
        bean.birthday = new Date(STAMP);
        bean.tags = Arrays.asList("a", "b<b>");
        ReflectChild child = new ReflectChild();
        child.n = 1;
        child.label = "kid";
        bean.child = child;
        bean.original = "renamed value";
        bean.skippedOnWrite = "not written";
        return bean;
    }

    @Test
    public void processorGeneratedTheCodec() throws Exception {
        Class.forName("com.dingjson.codec.CodecBean$$DingJsonCodec");
        Class.forName("com.dingjson.codec.CodecChild$$DingJsonCodec");
        Assert.assertNull("unannotated classes must not get a codec",
                maybeCodec("com.dingjson.codec.ReflectBean$$DingJsonCodec"));
    }

    private static Class<?> maybeCodec(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Test
    public void writeMatchesReflection() {
        Assert.assertEquals(DingJson.toJSONString(reflectBean()), DingJson.toJSONString(codecBean()));
    }

    @Test
    public void writeMatchesReflectionWithNullsAndSpecialValues() {
        CodecBean codec = codecBean();
        codec.name = null;
        codec.tags = null;
        codec.child = null;
        codec.score = Double.NaN;
        codec.ratio = Float.POSITIVE_INFINITY;
        ReflectBean reflect = reflectBean();
        reflect.name = null;
        reflect.tags = null;
        reflect.child = null;
        reflect.score = Double.NaN;
        reflect.ratio = Float.POSITIVE_INFINITY;
        Assert.assertEquals(DingJson.toJSONString(reflect), DingJson.toJSONString(codec));
    }

    @Test
    public void writeHonoursWriteNullFields() {
        DingJsonConfig config = DingJsonConfig.builder().writeNullFields(false).build();
        CodecBean codec = codecBean();
        codec.name = null;
        ReflectBean reflect = reflectBean();
        reflect.name = null;
        Assert.assertEquals(DingJson.toJSONString(reflect, config), DingJson.toJSONString(codec, config));
    }

    @Test
    public void writeInsideContainersMatchesReflection() {
        Assert.assertEquals(
                DingJson.toJSONString(Arrays.asList(reflectBean(), reflectBean())),
                DingJson.toJSONString(Arrays.asList(codecBean(), codecBean())));
    }

    @Test
    public void readThroughTreePathMatchesReflection() {
        String json = DingJson.toJSONString(reflectBean());
        CodecBean codec = DingJson.parseObject(json, new TypeReference<CodecBean>() { });
        ReflectBean reflect = DingJson.parseObject(json, new TypeReference<ReflectBean>() { });
        Assert.assertEquals(DingJson.toJSONString(reflect), DingJson.toJSONString(codec));
        Assert.assertEquals(42, codec.id);
        Assert.assertEquals('"', codec.letter);
        Assert.assertEquals("Ding <Json> & \"quoted\" \\ tail\nline", codec.name);
        Assert.assertEquals(2, codec.tags.size());
        Assert.assertEquals(1, codec.child.n);
        Assert.assertEquals("renamed value", codec.original);
        // @JSONField(format) dates survive the round trip on both paths
        Assert.assertEquals("2025-01-01", DingJson.toJSONString(codec).replaceAll(
                ".*\"birthday\":(\"[^\"]*\").*", "$1").replace("\"", ""));
        Assert.assertEquals(codec.birthday, reflect.birthday);
    }

    @Test
    public void readFillsMissingFieldsWithDefaults() {
        CodecBean codec = DingJson.parseObject("{\"id\":7}", new TypeReference<CodecBean>() { });
        Assert.assertEquals(7, codec.id);
        Assert.assertNull(codec.name);
        Assert.assertEquals(0, codec.big);
    }

    @Test
    public void generatedCodecIsAlsoUsedForJsonObjectConversion() {
        JSONObject obj = new JSONObject();
        obj.put("id", 5);
        obj.put("name", "x");
        CodecBean bean = com.dingjson.serializer.JSONSerializer.deserialize(obj, CodecBean.class);
        Assert.assertEquals(5, bean.id);
        Assert.assertEquals("x", bean.name);
    }
}
