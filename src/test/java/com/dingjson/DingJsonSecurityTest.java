package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.parser.JSONParser;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

/**
 * Security regression tests.
 * <p>
 * Guards validated here:
 * <ul>
 *   <li>nesting depth limit → no StackOverflowError DoS</li>
 *   <li>HTML escaping → JSON safe to embed in {@code <script>} blocks</li>
 *   <li>circular-reference detection → no infinite recursion crash</li>
 *   <li>no AutoType: {@code @type} in JSON is a plain key, never instantiated</li>
 *   <li>number overflow rejected</li>
 * </ul>
 */
public class DingJsonSecurityTest {

    // ══════════════════════════════════════════════════════════════
    // Nesting depth limit (DoS)
    // ══════════════════════════════════════════════════════════════

    private static String nestedArray(int depth) {
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) sb.append('[');
        sb.append('1');
        for (int i = 0; i < depth; i++) sb.append(']');
        return sb.toString();
    }

    private static String nestedObject(int depth) {
        StringBuilder sb = new StringBuilder(depth * 8);
        for (int i = 0; i < depth; i++) sb.append("{\"a\":");
        sb.append("1");
        for (int i = 0; i < depth; i++) sb.append('}');
        return sb.toString();
    }

    @Test
    public void deeplyNestedArrayRejectedWithException() {
        // 100k nesting would previously blow the stack
        try {
            DingJson.parse(nestedArray(100_000));
            Assert.fail("Expected JSONException for excessive nesting");
        } catch (JSONException e) {
            Assert.assertTrue(e.getMessage().contains("nesting depth"));
        }
    }

    @Test
    public void deeplyNestedObjectRejectedWithException() {
        try {
            DingJson.parse(nestedObject(100_000));
            Assert.fail("Expected JSONException for excessive nesting");
        } catch (JSONException e) {
            Assert.assertTrue(e.getMessage().contains("nesting depth"));
        }
    }

    @Test
    public void mixedNestingNearLimitRejected() {
        // Objects and arrays interleaved, way past the limit
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60_000; i++) {
            sb.append(i % 2 == 0 ? "{\"a\":" : "[");
        }
        sb.append("null");
        for (int i = 0; i < 60_000; i++) {
            sb.append(i % 2 == 0 ? "}" : "]");
        }
        try {
            DingJson.parse(sb.toString());
            Assert.fail("Expected JSONException for excessive nesting");
        } catch (JSONException e) {
            // expected
        }
    }

    @Test
    public void nestingWithinLimitParsesFine() {
        // Depth 500 < MAX_DEPTH 512 — must still work
        JSONArray arr = DingJson.parseArray(nestedArray(500));
        JSONArray cursor = arr;
        for (int i = 0; i < 499; i++) cursor = cursor.getJSONArray(0);
        Assert.assertEquals(Integer.valueOf(1), cursor.getInteger(0));

        JSONObject obj = DingJson.parseObject(nestedObject(500));
        JSONObject oc = obj;
        for (int i = 0; i < 499; i++) oc = oc.getJSONObject("a");
        Assert.assertEquals(Integer.valueOf(1), oc.getInteger("a"));
    }

    @Test
    public void depthLimitConstantExposed() {
        Assert.assertEquals(512, JSONParser.MAX_DEPTH);
    }

    // ══════════════════════════════════════════════════════════════
    // HTML escaping (XSS)
    // ══════════════════════════════════════════════════════════════

    @Test
    public void htmlSensitiveCharsEscaped() {
        String json = DingJson.toJSONString("</script><script>alert(1)</script>");
        Assert.assertFalse("raw </script> must not appear in output", json.contains("</script>"));
        Assert.assertTrue(json.contains("\\u003c/script\\u003e"));
        Assert.assertTrue(json.contains("\\u003cscript\\u003e"));
    }

    @Test
    public void htmlEscapingCoversGsonSet() {
        // Gson escapes < > & = ' by default; verify the same set
        String json = DingJson.toJSONString("<>&='");
        Assert.assertEquals("\"\\u003c\\u003e\\u0026\\u003d\\u0027\"", json);
    }

    @Test
    public void htmlEscapedOutputRoundTrips() {
        String original = "a < b && c > d = 'e'";
        String json = DingJson.toJSONString(original);
        Assert.assertEquals(original, DingJson.parse(json)); // \u003c parses back to <
    }

    @Test
    public void normalStringsUnchanged() {
        // Escaping must not affect ordinary text
        Assert.assertEquals("\"hello world\"", DingJson.toJSONString("hello world"));
        Assert.assertEquals("\"中文\"", DingJson.toJSONString("中文"));
    }

    @Test
    public void htmlEscapingInKeysAndPrettyOutput() {
        JSONObject obj = new JSONObject();
        obj.put("k<e>y", "v&v");
        String compact = DingJson.toJSONString(obj);
        Assert.assertTrue(compact.contains("\\u003c"));
        String pretty = DingJson.toJSONString(obj, true);
        Assert.assertTrue(pretty.contains("\\u003c"));
    }

    // ══════════════════════════════════════════════════════════════
    // Circular references
    // ══════════════════════════════════════════════════════════════

    public static class SelfRef {
        public SelfRef self;
        public SelfRef() {}
    }

    public static class MutualA {
        public MutualB b;
        public MutualA() {}
    }

    public static class MutualB {
        public MutualA a;
        public MutualB() {}
    }

    @Test(expected = JSONException.class)
    public void selfReferencingBeanRejected() {
        SelfRef s = new SelfRef();
        s.self = s;
        DingJson.toJSONString(s);
    }

    @Test(expected = JSONException.class)
    public void mutuallyReferencingBeansRejected() {
        MutualA a = new MutualA();
        MutualB b = new MutualB();
        a.b = b;
        b.a = a;
        DingJson.toJSONString(a);
    }

    @Test(expected = JSONException.class)
    public void selfReferencingJSONObjectRejected() {
        JSONObject obj = new JSONObject();
        obj.put("self", obj);
        DingJson.toJSONString(obj);
    }

    @Test(expected = JSONException.class)
    public void selfReferencingJSONArrayRejected() {
        JSONArray arr = new JSONArray();
        arr.add(arr);
        DingJson.toJSONString(arr);
    }

    @Test(expected = JSONException.class)
    public void objectArrayCycleRejected() {
        JSONObject obj = new JSONObject();
        JSONArray arr = new JSONArray();
        obj.put("arr", arr);
        arr.add(obj);
        DingJson.toJSONString(obj);
    }

    @Test(expected = JSONException.class)
    public void cycleRejectedInPrettyMode() {
        JSONObject obj = new JSONObject();
        obj.put("self", obj);
        DingJson.toJSONString(obj, true);
    }

    @Test
    public void sharedReferenceOnDifferentBranchesAllowed() {
        // DAG (not a cycle): the same object referenced twice must serialize fine
        JSONObject shared = new JSONObject();
        shared.put("x", 1);
        JSONArray arr = new JSONArray();
        arr.add(shared);
        arr.add(shared); // same instance twice — legal
        String json = DingJson.toJSONString(arr);
        Assert.assertEquals("[{\"x\":1},{\"x\":1}]", json);
    }

    @Test
    public void deepButAcyclicTreeStillWorks() {
        JSONArray a1 = new JSONArray();
        JSONArray a2 = new JSONArray();
        a1.add(a2);
        JSONArray a3 = new JSONArray();
        a2.add(a3);
        // No cycle — just nested
        String json = DingJson.toJSONString(a1);
        Assert.assertEquals("[[[]]]", json);
    }

    // ══════════════════════════════════════════════════════════════
    // No AutoType / deserialization gadget surface
    // ══════════════════════════════════════════════════════════════

    @Test
    public void typeFieldIsPlainKeyNotInstantiated() {
        // Fastjson-style @type payload: must NOT instantiate anything
        String json = "{\"@type\":\"java.lang.Runtime\",\"cmd\":\"rm -rf /\"}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals("java.lang.Runtime", obj.getString("@type"));
        Assert.assertEquals(2, obj.size());
    }

    @Test
    public void classFieldInBeanIsJustAString() {
        // A bean with a class-named field is not a gadget unless the app maps it
        String json = "{\"name\":\"x\",\"@type\":\"java.lang.System\"}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals(2, obj.size());
    }

    @Test(expected = JSONException.class)
    public void deserializingIntoNonBeanClassFails() {
        // Cannot force-instantiate classes without a no-arg constructor
        DingJson.parseObject("{}", java.lang.Runtime.class);
    }

    // ══════════════════════════════════════════════════════════════
    // Number safety
    // ══════════════════════════════════════════════════════════════

    @Test(expected = JSONException.class)
    public void overflowingIntegerRejected() {
        DingJson.parse("99999999999999999999999999999999");
    }

    @Test(expected = JSONException.class)
    public void overflowingNegativeIntegerRejected() {
        DingJson.parse("-99999999999999999999999999999999");
    }

    @Test
    public void boundaryValuesAccepted() {
        Assert.assertEquals(Long.valueOf(Long.MAX_VALUE), DingJson.parse(String.valueOf(Long.MAX_VALUE)));
        Assert.assertEquals(Long.valueOf(Long.MIN_VALUE), DingJson.parse(String.valueOf(Long.MIN_VALUE)));
    }

    // ══════════════════════════════════════════════════════════════
    // Map/bean binding safety
    // ══════════════════════════════════════════════════════════════

    public static class SafeBean {
        private String value;
        private int count;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }

    @Test
    public void hugeJsonWithManyKeysParsesToBoundedObject() {
        // Wide but shallow input is fine; only depth is capped
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 100_000; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"k").append(i).append("\":").append(i);
        }
        sb.append('}');
        JSONObject obj = DingJson.parseObject(sb.toString());
        Assert.assertEquals(100_000, obj.size());
    }

    @Test
    public void duplicateKeysLastWinsNoBlowup() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 10_000; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"same\":").append(i);
        }
        sb.append('}');
        JSONObject obj = DingJson.parseObject(sb.toString());
        Assert.assertEquals(1, obj.size());
        Assert.assertEquals(Integer.valueOf(9999), obj.getInteger("same"));
    }

    // ══════════════════════════════════════════════════════════════
    // Type coercion strictness (no silent surprises)
    // ══════════════════════════════════════════════════════════════

    @Test
    public void beanBindingIsFieldNameExact() {
        // No case-insensitive or fuzzy matching: exact names only
        SafeBean bean = DingJson.parseObject(
                "{\"Value\":\"x\",\"VALUE\":\"y\",\"count\":1}", SafeBean.class);
        Assert.assertNull(bean.getValue()); // "value" field untouched by "Value"/"VALUE"
        Assert.assertEquals(1, bean.getCount());
    }
}
