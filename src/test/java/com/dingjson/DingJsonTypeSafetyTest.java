package com.dingjson;

import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests for the type-confusion / range / consistency findings in
 * {@code docs/security-audit.md} (V1-V13). Every case here used to either
 * corrupt data silently or crash the JVM on the next field read.
 */
public class DingJsonTypeSafetyTest {

    // ── V1: a JSON node must never land in an unrelated field ──────────

    public interface Marker { }

    public abstract static class AbstractHolder { }

    public static class IfaceHolder {
        public Marker m;
    }

    public static class AbstractHolderBean {
        public AbstractHolder a;
    }

    public static class ClassHolder {
        public Class<?> c;
    }

    public static class ArrayHolder {
        public String[] s;
        public int[] n;
    }

    @Test
    public void interfaceFieldRejectsJsonObject() {
        try {
            DingJson.parseObject("{\"m\":{\"x\":1}}", IfaceHolder.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("abstract"));
        }
    }

    @Test
    public void abstractFieldRejectsJsonObject() {
        try {
            DingJson.parseObject("{\"a\":{\"x\":1}}", AbstractHolderBean.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("abstract"));
        }
    }

    @Test
    public void classFieldRejectsString() {
        // A String used to be written into a Class<?> field via Unsafe; the next
        // read of that field segfaulted inside String.valueOf(Object).
        try {
            DingJson.parseObject("{\"c\":\"java.lang.Runtime\"}", ClassHolder.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("convert")
                    || expected.getMessage().contains("c"));
        }
    }

    @Test
    public void arrayFieldAcceptsJsonArray() {
        ArrayHolder h = DingJson.parseObject("{\"s\":[\"a\",\"b\"],\"n\":[1,2]}", ArrayHolder.class);
        Assert.assertArrayEquals(new String[]{"a", "b"}, h.s);
        Assert.assertArrayEquals(new int[]{1, 2}, h.n);
    }

    @Test
    public void arrayFieldRejectsScalar() {
        // The raw JSONArray used to be written into a String[] field (the JVM then
        // read the ArrayList header as the array length and died with SIGBUS).
        try {
            DingJson.parseObject("{\"s\":1}", ArrayHolder.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("s"));
        }
    }

    // ── V2: narrowing must not wrap ───────────────────────────────────

    public static class Narrow {
        public int i;
        public short sh;
        public byte b;
        public long l;
    }

    @Test
    public void intOverflowIsRejected() {
        assertRejected("{\"i\":2147483648}", Narrow.class);
        assertRejected("{\"i\":-2147483649}", Narrow.class);
    }

    @Test
    public void shortAndByteOverflowAreRejected() {
        assertRejected("{\"sh\":70000}", Narrow.class);
        assertRejected("{\"b\":300}", Narrow.class);
    }

    @Test
    public void inRangeValuesStillConvert() {
        Narrow n = DingJson.parseObject("{\"i\":2147483647,\"sh\":-32768,\"b\":127}", Narrow.class);
        Assert.assertEquals(2147483647, n.i);
        Assert.assertEquals(-32768, n.sh);
        Assert.assertEquals(127, n.b);
    }

    // ── V3: typed getters must not saturate ───────────────────────────

    @Test
    public void getLongRejectsOutOfRangeNumber() {
        JSONObject o = DingJson.parseObject("{\"v\":1e40}");
        try {
            o.getLong("v");
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("range"));
        }
    }

    @Test
    public void getIntegerStillCoercesStrings() {
        JSONObject o = DingJson.parseObject("{\"v\":\"42\",\"d\":1.9}");
        Assert.assertEquals(Integer.valueOf(42), o.getInteger("v"));
        Assert.assertEquals(Integer.valueOf(1), o.getInteger("d"));
    }

    // ── V4: generic fields honour their type argument ─────────────────

    public static class Box<T> {
        public T value;
    }

    @Test
    public void genericFieldIsConvertedToItsTypeArgument() {
        Box<Integer> box = DingJson.parseObject("{\"value\":7}",
                new TypeReference<Box<Integer>>() { });
        Assert.assertEquals(Integer.valueOf(7), box.value);
    }

    @Test
    public void genericFieldRejectsValueOfTheWrongType() {
        try {
            DingJson.parseObject("{\"value\":\"abc\"}",
                    new TypeReference<Box<Integer>>() { });
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("value"));
        }
    }

    // ── V5: array elements are converted, not crammed in ──────────────

    @Test
    public void arrayElementsAreConverted() {
        ArrayHolder h = DingJson.parseObject("{\"s\":[1,2]}", ArrayHolder.class);
        Assert.assertArrayEquals(new String[]{"1", "2"}, h.s);
    }

    @Test
    public void arrayElementsThatCannotConvertAreRejected() {
        try {
            DingJson.parseObject("{\"n\":[\"a\"]}", ArrayHolder.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("n"));
        }
    }

    // ── V6: absurd exponents are rejected before they become a bomb ───

    @Test
    public void absurdExponentIsRejected() {
        try {
            DingJson.parse("{\"v\":1e2147483648}");
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("exponent"));
        }
    }

    @Test
    public void reasonableScientificNotationStillWorks() {
        JSONObject o = DingJson.parseObject("{\"v\":1e400}");
        Assert.assertNotNull(o.get("v"));
    }

    // ── V7/V8: depth is a catchable JSONException, and configurable ───

    @Test
    public void deeplyNestedInputThrowsJsonExceptionNotError() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append('[');
        try {
            DingJson.parse(sb.toString());
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("depth")
                    || expected.getMessage().contains("Nesting"));
        }
    }

    @Test
    public void maxDepthIsConfigurable() {
        DingJsonConfig shallow = DingJsonConfig.builder().maxDepth(4).build();
        String sixDeep = "[[[[[[]]]]]]";
        try {
            DingJson.parse(sixDeep, shallow);
            Assert.fail("expected JSONException for depth > 4");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("depth"));
        }
        Assert.assertNotNull(DingJson.parse("[[[[]]]]", shallow));
    }

    // ── V9: exact field names win over the case-insensitive fallback ──

    public static class NameCase {
        public String name;
        public String NAME;
    }

    @Test
    public void exactFieldNameIsNotShadowedByTheLowerCasedOne() {
        NameCase bean = DingJson.parseObject("{\"name\":\"x\"}", NameCase.class);
        Assert.assertEquals("x", bean.name);
        Assert.assertNull(bean.NAME);
    }

    // ── V10: JSONPath coercion throws JSONException, not ClassCast ────

    @Test
    public void jsonPathCoercionFailureIsAJsonException() {
        Object root = DingJson.parse("{\"a\":{\"b\":1}}");
        try {
            JSONPath.eval(root, "$.a", Integer.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void jsonPathStillCoercesScalars() {
        Object root = DingJson.parse("{\"a\":\"42\"}");
        Assert.assertEquals(Integer.valueOf(42), JSONPath.eval(root, "$.a", Integer.class));
    }

    // ── V11: conversion errors name the offending field ───────────────

    public static class Person {
        public String name;
        public int age;
    }

    @Test
    public void conversionErrorNamesTheField() {
        try {
            DingJson.parseObject("{\"name\":\"x\",\"age\":\"abc\"}", Person.class);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue("message was: " + expected.getMessage(),
                    expected.getMessage().contains("age"));
        }
    }

    // ── V12: U+2028/U+2029 are escaped ────────────────────────────────

    @Test
    public void lineSeparatorsAreEscaped() {
        String json = DingJson.toJSONString("a\u2028b\u2029c");
        Assert.assertFalse(json.indexOf('\u2028') >= 0);
        Assert.assertFalse(json.indexOf('\u2029') >= 0);
        Assert.assertEquals("a\u2028b\u2029c", DingJson.parse(json));
    }

    // ── V13: null map keys are rejected (see DingJsonHardeningTest) ───

    @Test
    public void nullKeyRejectedForNestedMapToo() {
        Map<String, Object> inner = new HashMap<>();
        inner.put(null, 1);
        Map<String, Object> outer = new HashMap<>();
        outer.put("k", inner);
        try {
            DingJson.toJSONString(outer);
            Assert.fail("expected JSONException");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("key"));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static void assertRejected(String json, Class<?> type) {
        try {
            DingJson.parseObject(json, type);
            Assert.fail("expected JSONException for " + json);
        } catch (JSONException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    /** Kept so a future change cannot quietly drop List support from generics. */
    public static class ListBox<T> {
        public List<T> items;
    }

    @Test
    public void genericListFieldIsConverted() {
        ListBox<Integer> box = DingJson.parseObject("{\"items\":[1,2]}",
                new TypeReference<ListBox<Integer>>() { });
        Assert.assertEquals(2, box.items.size());
        Assert.assertEquals(Integer.valueOf(1), box.items.get(0));
    }
}
