package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.serializer.JSONWriter;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests for the P0–P3 audit fixes:
 * <ul>
 *   <li>P0: JSONWriter dangling-key output, pretty all-null corruption,
 *           char-field memory corruption / int-0 field loss</li>
 *   <li>P1: unbounded input (maxInputBytes), lone-surrogate round-trip,
 *           double-overflow numbers (BigDecimal preservation)</li>
 *   <li>P2: StackOverflowError → JSONException, non-String map keys</li>
 *   <li>P3: Character value serialization, direct Map/Collection iteration</li>
 * </ul>
 */
public class DingJsonHardeningTest {

    // ═══ P0-1: JSONWriter must never emit invalid JSON ═══

    @Test(expected = JSONException.class)
    public void writerEndObjectAfterKeyWithoutValueRejected() {
        JSONWriter.create().beginObject().key("a").endObject();
    }

    @Test
    public void writerValidKeyValueStillWorks() {
        JSONWriter w = JSONWriter.create();
        w.beginObject().key("a").value(1).endObject();
        Assert.assertEquals("{\"a\":1}", w.toString());
    }

    // ═══ P0-2: pretty serialization of all-null objects ═══

    @Test
    public void prettyAllNullObjectWithSkipNullsIsEmptyObject() {
        DingJsonConfig cfg = DingJsonConfig.builder().writeNullFields(false).build();
        JSONObject o = new JSONObject();
        o.put("a", (Object) null);
        o.put("b", (Object) null);
        Assert.assertEquals("{}", DingJson.toJSONString(o, cfg));
        Assert.assertEquals("{}", DingJson.toJSONString(o, true, cfg));
    }

    @Test
    public void prettyMixedNullObjectWithSkipNullsKeepsValues() {
        DingJsonConfig cfg = DingJsonConfig.builder().writeNullFields(false).build();
        JSONObject o = new JSONObject();
        o.put("a", (Object) null);
        o.put("b", "x");
        Assert.assertEquals("{\n  \"b\": \"x\"\n}", DingJson.toJSONString(o, true, cfg));
    }

    // ═══ P0-3: char fields and int 0 must never be corrupted ═══

    public static class CharBean {
        public char c = 'A';
        public int zero = 0;
        public String s = null;
    }

    @Test
    public void charFieldSerializesAsString() {
        Assert.assertEquals("{\"c\":\"A\",\"zero\":0,\"s\":null}",
                DingJson.toJSONString(new CharBean()));
    }

    @Test
    public void intZeroIsNotDroppedWhenSkippingNulls() {
        DingJsonConfig cfg = DingJsonConfig.builder().writeNullFields(false).build();
        String json = DingJson.toJSONString(new CharBean(), cfg);
        Assert.assertTrue("int 0 field must survive: " + json, json.contains("\"zero\":0"));
        Assert.assertTrue("char field must survive: " + json, json.contains("\"c\":\"A\""));
        Assert.assertFalse("null field must be skipped: " + json, json.contains("\"s\""));
    }

    @Test
    public void charFieldRoundTrips() {
        CharBean b = DingJson.parseObject("{\"c\":\"A\",\"zero\":0,\"s\":null}", CharBean.class);
        Assert.assertEquals('A', b.c);
        Assert.assertEquals(0, b.zero);
        Assert.assertNull(b.s);
    }

    @Test
    public void characterValueSerializesAsString() {
        Assert.assertEquals("\"Z\"", DingJson.toJSONString(Character.valueOf('Z')));
        Assert.assertEquals("\"Z\"", DingJson.toJSONString('Z'));
    }

    // ═══ P1-1: input size limits on stream/reader/file/bytes ═══

    private static byte[] bigBytes(int size) {
        StringBuilder sb = new StringBuilder(size);
        sb.append('[');
        for (int i = 0; i < size - 2; i++) {
            sb.append(' ');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void oversizedByteArrayRejected() {
        DingJsonConfig tiny = DingJsonConfig.builder().maxInputBytes(64).build();
        try {
            DingJson.mapper(tiny).parse(bigBytes(256));
            Assert.fail("expected JSONException");
        } catch (JSONException e) {
            Assert.assertTrue(e.getMessage().contains("maximum allowed"));
        }
    }

    @Test
    public void oversizedInputStreamRejectedMidRead() {
        DingJsonConfig tiny = DingJsonConfig.builder().maxInputBytes(64).build();
        try {
            DingJson.mapper(tiny).parseObject(new ByteArrayInputStream(bigBytes(4096)));
            Assert.fail("expected JSONException");
        } catch (JSONException e) {
            Assert.assertTrue(e.getMessage().contains("maximum allowed"));
        }
    }

    @Test
    public void oversizedReaderRejected() {
        DingJsonConfig tiny = DingJsonConfig.builder().maxInputBytes(64).build();
        try {
            DingJson.mapper(tiny).parseObject(new StringReader(new String(bigBytes(4096), StandardCharsets.UTF_8)));
            Assert.fail("expected JSONException");
        } catch (JSONException e) {
            Assert.assertTrue(e.getMessage().contains("maximum allowed"));
        }
    }

    @Test
    public void oversizedFileRejected() throws Exception {
        DingJsonConfig tiny = DingJsonConfig.builder().maxInputBytes(64).build();
        java.io.File f = java.io.File.createTempFile("dingjson-hardening", ".json");
        try {
            java.nio.file.Files.write(f.toPath(), bigBytes(4096));
            try {
                DingJson.mapper(tiny).parse(f);
                Assert.fail("expected JSONException");
            } catch (JSONException e) {
                Assert.assertTrue(e.getMessage().contains("maximum allowed"));
            }
        } finally {
            Assert.assertTrue(f.delete());
        }
    }

    @Test
    public void unlimitedInputStillWorks() {
        DingJsonConfig unlimited = DingJsonConfig.builder().maxInputBytes(0).build();
        byte[] in = bigBytes(4096);
        Object v = DingJson.mapper(unlimited).parse(in);
        Assert.assertTrue(v instanceof JSONArray);
    }

    @Test
    public void defaultConfigHasInputLimit() {
        Assert.assertTrue(DingJsonConfig.defaults().maxInputBytes() > 0);
    }

    // ═══ P1-2: lone surrogates round-trip through UTF-8 bytes ═══

    @Test
    public void loneSurrogateEscapedOnOutput() {
        String out = DingJson.toJSONString("\uD800");
        Assert.assertEquals("\"\\ud800\"", out);
    }

    @Test
    public void loneSurrogateRoundTripsThroughBytes() {
        Object s = DingJson.parse("\"\\uD800\"");
        byte[] bytes = DingJson.toJSONString(s).getBytes(StandardCharsets.UTF_8);
        Object back = DingJson.parse(bytes);
        Assert.assertEquals(s, back);
    }

    @Test
    public void validSurrogatePairStaysRaw() {
        String emoji = "a\uD83D\uDE00b";
        String out = DingJson.toJSONString(emoji);
        Assert.assertTrue("valid pair must stay raw: " + out, out.contains("\uD83D\uDE00"));
        Assert.assertEquals(emoji, DingJson.parse(out));
    }

    @Test
    public void loneLowSurrogateEscaped() {
        Assert.assertEquals("\"\\udc00\"", DingJson.toJSONString("\uDC00"));
    }

    // ═══ P1-3: double-overflow numbers ═══

    @Test
    public void hugeDecimalParsesAsBigDecimal() {
        Object v = DingJson.parse("1e999");
        Assert.assertTrue(v instanceof BigDecimal);
        Assert.assertEquals(new BigDecimal("1e999"), v);
    }

    @Test
    public void hugeDecimalRoundTrips() {
        Object v = DingJson.parse("1e999");
        Object back = DingJson.parse(DingJson.toJSONString(v));
        Assert.assertEquals(v, back);
    }

    @Test
    public void negativeHugeDecimalPreserved() {
        Assert.assertEquals(new BigDecimal("-1e999"), DingJson.parse("-1e999"));
    }

    @Test(expected = JSONException.class)
    public void readDoubleRejectsOutOfRangeNumber() {
        DingJson.createReader("1e999").readDouble();
    }

    @Test(expected = JSONException.class)
    public void doubleBeanFieldRejectsOutOfRangeNumber() {
        DingJson.parseObject("{\"d\":1e999}", DoubleBean.class);
    }

    public static class DoubleBean {
        public double d;
    }

    @Test(expected = JSONException.class)
    public void absurdExponentRejectedAsJSONException() {
        // exponent with too many digits even for BigDecimal (i_number_huge_exp)
        DingJson.parse("1e999999999999999999999999");
    }

    // ═══ P2-1: deep structures fail with JSONException, never an Error ═══

    private static JSONArray deepTree(int depth) {
        JSONArray arr = new JSONArray();
        JSONArray cur = arr;
        for (int i = 0; i < depth; i++) {
            JSONArray next = new JSONArray();
            cur.add(next);
            cur = next;
        }
        return arr;
    }

    private static void assertJSONExceptionNotError(String label, Runnable r) throws Exception {
        Thread t = new Thread(null, () -> {
            try {
                r.run();
                Assert.fail(label + ": expected JSONException");
            } catch (JSONException e) {
                // expected — never let an Error escape
            } catch (Throwable t2) {
                throw new AssertionError(label + " escaped as " + t2.getClass().getName(), t2);
            }
        }, label, 256 * 1024); // small stack, as in servlet containers
        t.start();
        t.join();
    }

    @Test
    public void deepSerializeOnSmallStackThrowsJSONException() throws Exception {
        JSONArray deep = deepTree(500);
        assertJSONExceptionNotError("compact", () -> DingJson.toJSONString(deep));
        assertJSONExceptionNotError("pretty", () -> DingJson.toJSONString(deep, true));
    }

    @Test
    public void deepCopyOnSmallStackThrowsJSONException() throws Exception {
        JSONArray deep = deepTree(500);
        assertJSONExceptionNotError("deepCopy", () -> DingJson.deepCopy(deep));
    }

    @Test
    public void moderateDepthStillSerializes() {
        Assert.assertEquals("[[[[]]]]", DingJson.toJSONString(deepTree(3)));
    }

    // ═══ P2-2: non-String map keys ═══

    @Test
    public void mapWithIntegerKeysSerializes() {
        Map<Object, Object> m = new HashMap<>();
        m.put(123, "x");
        String json = DingJson.toJSONString(m);
        Assert.assertTrue(json.contains("\"123\":\"x\""));
        // and it must be valid JSON
        DingJson.parse(json);
    }

    @Test
    public void nullMapKeyIsRejected() {
        Map<Object, Object> m = new HashMap<>();
        m.put(null, "y");
        // "null" as a key cannot be told apart from the string "null" on the way
        // back, so it is rejected instead of being silently corrupted.
        try {
            DingJson.toJSONString(m);
            Assert.fail("expected JSONException for a null map key");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage().contains("key"));
        }
    }

    @Test
    public void toJSONWithIntegerKeysSerializes() {
        Map<Object, Object> m = new HashMap<>();
        m.put(1, "one");
        JSONObject o = DingJson.toJSON(m);
        Assert.assertEquals("one", o.getString("1"));
    }

    // ═══ P3: direct Map/Collection iteration (no intermediate copies) ═══

    @Test
    public void collectionSerializesDirectly() {
        List<Object> list = new ArrayList<>();
        list.add("x");
        list.add(1);
        list.add(null);
        Assert.assertEquals("[\"x\",1,null]", DingJson.toJSONString(list));
        Assert.assertEquals("[\n  \"x\",\n  1,\n  null\n]", DingJson.toJSONString(list, true));
    }

    @Test
    public void mapSerializesDirectlyWithSkipNulls() {
        DingJsonConfig cfg = DingJsonConfig.builder().writeNullFields(false).build();
        Map<String, Object> m = new HashMap<>();
        m.put("a", (Object) null);
        m.put("b", 2);
        Assert.assertEquals("{\"b\":2}", DingJson.toJSONString(m, cfg));
        Assert.assertEquals("{\n  \"b\": 2\n}", DingJson.toJSONString(m, true, cfg));
    }
}
