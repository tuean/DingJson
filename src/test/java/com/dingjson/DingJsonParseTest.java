package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Comprehensive parsing tests for DingJson.
 * Patterns inspired by org.json, Gson, and Jackson test suites.
 */
public class DingJsonParseTest {

    // ── Primitive values ──────────────────────────────────────────

    @Test
    public void parseNull() {
        Assert.assertNull(DingJson.parse("null"));
        Assert.assertNull(DingJson.parse("  null  "));
    }

    @Test
    public void parseBoolean() {
        Assert.assertEquals(Boolean.TRUE, DingJson.parse("true"));
        Assert.assertEquals(Boolean.FALSE, DingJson.parse("false"));
    }

    @Test
    public void parseStringSimple() {
        Assert.assertEquals("hello", DingJson.parse("\"hello\""));
        Assert.assertEquals("", DingJson.parse("\"\""));
    }

    @Test
    public void parseStringWithUnicode() {
        Assert.assertEquals("H", DingJson.parse("\"\\u0048\""));
        Assert.assertEquals("é", DingJson.parse("\"\\u00e9\""));
        Assert.assertEquals("中", DingJson.parse("\"\\u4e2d\""));
        Assert.assertEquals("Hello World", DingJson.parse("\"Hello\\u0020World\""));
    }

    @Test
    public void parseStringWithEscapes() {
        Assert.assertEquals("quote\"here", DingJson.parse("\"quote\\\"here\""));
        Assert.assertEquals("back\\slash", DingJson.parse("\"back\\\\slash\""));
        Assert.assertEquals("slash/here", DingJson.parse("\"slash/here\""));
        Assert.assertEquals("\b\f\n\r\t", DingJson.parse("\"\\b\\f\\n\\r\\t\""));
    }

    @Test
    public void parseStringWithControlChars() {
        // In JSON these must be escaped
        Assert.assertEquals("\0", DingJson.parse("\"\\u0000\""));
        Assert.assertEquals("\u001f", DingJson.parse("\"\\u001f\""));
    }

    @Test
    public void parseStringSurrogatePair() {
        // Emoji: U+1F600 (😀) encoded as surrogate pair \uD83D\uDE00
        String result = (String) DingJson.parse("\"\\uD83D\\uDE00\"");
        Assert.assertEquals("😀", result);
        Assert.assertEquals(2, result.length()); // surrogate pair = 2 Java chars
    }

    // ── Number parsing ────────────────────────────────────────────

    @Test
    public void parseInteger() {
        Assert.assertEquals(Integer.valueOf(0), DingJson.parse("0"));
        Assert.assertEquals(Integer.valueOf(42), DingJson.parse("42"));
        Assert.assertEquals(Integer.valueOf(-1), DingJson.parse("-1"));
        Assert.assertEquals(Integer.valueOf(Integer.MAX_VALUE), DingJson.parse(String.valueOf(Integer.MAX_VALUE)));
        Assert.assertEquals(Integer.valueOf(Integer.MIN_VALUE), DingJson.parse(String.valueOf(Integer.MIN_VALUE)));
    }

    @Test
    public void parseLong() {
        Assert.assertEquals(Long.valueOf(Integer.MAX_VALUE + 1L), DingJson.parse("2147483648"));
        Assert.assertEquals(Long.valueOf(Integer.MIN_VALUE - 1L), DingJson.parse("-2147483649"));
        Assert.assertEquals(Long.valueOf(Long.MAX_VALUE), DingJson.parse(String.valueOf(Long.MAX_VALUE)));
        Assert.assertEquals(Long.valueOf(Long.MIN_VALUE), DingJson.parse(String.valueOf(Long.MIN_VALUE)));
    }

    @Test
    public void parseDouble() {
        Assert.assertEquals(Double.valueOf(3.14), DingJson.parse("3.14"));
        Assert.assertEquals(Double.valueOf(-2.5), DingJson.parse("-2.5"));
        Assert.assertEquals(Double.valueOf(0.0), DingJson.parse("0.0"));
        Assert.assertEquals(Double.valueOf(-0.0), DingJson.parse("-0.0"));
    }

    @Test
    public void parseDoubleScientific() {
        Assert.assertEquals(Double.valueOf(1e10), DingJson.parse("1e10"));
        Assert.assertEquals(Double.valueOf(1E-10), DingJson.parse("1E-10"));
        Assert.assertEquals(Double.valueOf(1.5e20), DingJson.parse("1.5e20"));
        Assert.assertEquals(Double.valueOf(1.5E-20), DingJson.parse("1.5E-20"));
        Assert.assertEquals(Double.valueOf(1e+20), DingJson.parse("1e+20"));
    }

    @Test
    public void parseNumberLeadingZero() {
        // JSON allows just "0" and "0.x", not "0123"
        Assert.assertEquals(Integer.valueOf(0), DingJson.parse("0"));
        Assert.assertEquals(Double.valueOf(0.5), DingJson.parse("0.5"));
    }

    // ── Object parsing ────────────────────────────────────────────

    @Test
    public void parseEmptyObject() {
        JSONObject obj = DingJson.parseObject("{}");
        Assert.assertTrue(obj.isEmpty());
    }

    @Test
    public void parseObjectWithVariousTypes() {
        String json = "{" +
                "\"str\":\"hello\"," +
                "\"int\":42," +
                "\"double\":3.14," +
                "\"bool\":true," +
                "\"null\":null," +
                "\"obj\":{}," +
                "\"arr\":[]" +
                "}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals("hello", obj.getString("str"));
        Assert.assertEquals(Integer.valueOf(42), obj.getInteger("int"));
        Assert.assertEquals(Double.valueOf(3.14), obj.getDouble("double"));
        Assert.assertEquals(Boolean.TRUE, obj.getBoolean("bool"));
        Assert.assertNull(obj.get("null"));
        Assert.assertTrue(obj.getJSONObject("obj").isEmpty());
        Assert.assertTrue(obj.getJSONArray("arr").isEmpty());
    }

    @Test
    public void parseObjectDuplicateKeys() {
        // Last value wins (standard JSON library behavior)
        JSONObject obj = DingJson.parseObject("{\"a\":1,\"a\":2}");
        Assert.assertEquals(Integer.valueOf(2), obj.getInteger("a"));
        Assert.assertEquals(1, obj.size()); // only one key remains
    }

    @Test
    public void parseNestedObject() {
        String json = "{\"a\":{\"b\":{\"c\":\"deep\"}}}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals("deep", obj.getJSONObject("a").getJSONObject("b").getString("c"));
    }

    @Test
    public void parseObjectWithNumberKeys() {
        // JSON spec allows quoted strings as keys
        JSONObject obj = DingJson.parseObject("{\"123\":\"value\"}");
        Assert.assertEquals("value", obj.getString("123"));
    }

    // ── Array parsing ─────────────────────────────────────────────

    @Test
    public void parseEmptyArray() {
        JSONArray arr = DingJson.parseArray("[]");
        Assert.assertTrue(arr.isEmpty());
    }

    @Test
    public void parseArrayWithVariousTypes() {
        String json = "[\"hello\",42,3.14,true,null,{},[]]";
        JSONArray arr = DingJson.parseArray(json);
        Assert.assertEquals(7, arr.size());
        Assert.assertEquals("hello", arr.getString(0));
        Assert.assertEquals(Integer.valueOf(42), arr.getInteger(1));
        Assert.assertEquals(Double.valueOf(3.14), arr.getDouble(2));
        Assert.assertEquals(Boolean.TRUE, arr.getBoolean(3));
        Assert.assertNull(arr.get(4));
        Assert.assertTrue(arr.getJSONObject(5).isEmpty());
        Assert.assertTrue(arr.getJSONArray(6).isEmpty());
    }

    @Test
    public void parseNestedArray() {
        String json = "[[[1,2],[3,4]],[[5,6]]]";
        JSONArray arr = DingJson.parseArray(json);
        Assert.assertEquals(Integer.valueOf(1), arr.getJSONArray(0).getJSONArray(0).getInteger(0));
        Assert.assertEquals(Integer.valueOf(6), arr.getJSONArray(1).getJSONArray(0).getInteger(1));
    }

    @Test
    public void parseArrayTrailingComma() {
        // JSON does NOT allow trailing commas — expect exception
        // (org.json is lenient, but strict parsing should reject)
        try {
            DingJson.parseArray("[1,2,]");
            Assert.fail("Expected JSONException for trailing comma in array");
        } catch (JSONException e) {
            // expected
        }
    }

    @Test
    public void parseObjectTrailingComma() {
        try {
            DingJson.parseObject("{\"a\":1,}");
            Assert.fail("Expected JSONException for trailing comma in object");
        } catch (JSONException e) {
            // expected
        }
    }

    // ── Whitespace handling ───────────────────────────────────────

    @Test
    public void parseWithWhitespace() {
        JSONObject obj = DingJson.parseObject("  {  \"a\"  :  1  }  ");
        Assert.assertEquals(Integer.valueOf(1), obj.getInteger("a"));
    }

    @Test
    public void parseWithTabsAndNewlines() {
        String json = "\n\t{\n\t\t\"a\": 1,\n\t\t\"b\": 2\n\t}\n";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals(Integer.valueOf(1), obj.getInteger("a"));
        Assert.assertEquals(Integer.valueOf(2), obj.getInteger("b"));
    }

    @Test
    public void parseArrayWithWhitespace() {
        JSONArray arr = DingJson.parseArray("  [  1  ,  2  ,  3  ]  ");
        Assert.assertEquals(3, arr.size());
        Assert.assertEquals(Integer.valueOf(1), arr.getInteger(0));
    }

    @Test
    public void parseMixedWhitespace() {
        // Mix of space, tab, carriage return, newline
        String json = "{\r\n\t\"a\": 1,\r \"b\": 2\n}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals(Integer.valueOf(1), obj.getInteger("a"));
        Assert.assertEquals(Integer.valueOf(2), obj.getInteger("b"));
    }

    // ── Deep nesting ──────────────────────────────────────────────

    @Test
    public void parseDeeplyNestedObject() {
        // Build deeply nested: {"a":{"a":{...{"leaf":true}...}}}
        int depth = 100;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("{\"a\":");
        }
        sb.append("{\"leaf\":true}");
        for (int i = 0; i < depth; i++) {
            sb.append("}");
        }
        JSONObject obj = DingJson.parseObject(sb.toString());
        JSONObject cursor = obj;
        for (int i = 0; i < depth; i++) {
            cursor = cursor.getJSONObject("a");
        }
        Assert.assertTrue((Boolean) cursor.get("leaf"));
    }

    @Test
    public void parseDeeplyNestedArray() {
        // Build deeply nested: [[[...[42]...]]]
        int depth = 100;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("[");
        }
        sb.append("42");
        for (int i = 0; i < depth; i++) {
            sb.append("]");
        }
        JSONArray arr = DingJson.parseArray(sb.toString());
        JSONArray cursor = arr;
        for (int i = 0; i < depth - 1; i++) {
            cursor = cursor.getJSONArray(0);
        }
        Assert.assertEquals(Integer.valueOf(42), cursor.getInteger(0));
    }

    // ── Large data (inspired by Jackson stress tests) ──────────────

    @Test
    public void parseLargeArray() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10000; i++) {
            if (i > 0) sb.append(",");
            sb.append(i);
        }
        sb.append("]");
        JSONArray arr = DingJson.parseArray(sb.toString());
        Assert.assertEquals(10000, arr.size());
        Assert.assertEquals(Integer.valueOf(0), arr.getInteger(0));
        Assert.assertEquals(Integer.valueOf(9999), arr.getInteger(9999));
    }

    @Test
    public void parseLargeObject() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"key").append(i).append("\":").append(i);
        }
        sb.append("}");
        JSONObject obj = DingJson.parseObject(sb.toString());
        Assert.assertEquals(1000, obj.size());
        Assert.assertEquals(Integer.valueOf(999), obj.getInteger("key999"));
    }

    // ── Unicode in strings (inspired by org.json encoding tests) ──

    @Test
    public void parseUnicodeCharacters() {
        String json = "{\"text\":\"\\u00e9\\u00e0\\u00fc\\u00f1\"}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals("éàüñ", obj.getString("text"));
    }

    @Test
    public void parseUnicodeRawString() {
        // Direct UTF-8 in JSON string (parser should pass through)
        String json = "{\"char\":\"中\"}";
        JSONObject obj = DingJson.parseObject(json);
        Assert.assertEquals("中", obj.getString("char"));
    }

    // ── Round-trip consistency (inspired by Jackson) ───────────────

    @Test
    public void parseAndSerializeRoundTrip() {
        String original = "{\"name\":\"Ding\",\"version\":3,\"tags\":[\"json\",\"java\"],\"active\":true,\"data\":{\"x\":1.5}}";
        JSONObject obj = DingJson.parseObject(original);
        String serialized = DingJson.toJSONString(obj);
        JSONObject parsedAgain = DingJson.parseObject(serialized);
        Assert.assertEquals(obj, parsedAgain);
    }

    @Test
    public void parseAndSerializeArrayRoundTrip() {
        String original = "[1,\"two\",null,true,[],{}]";
        JSONArray arr = DingJson.parseArray(original);
        String serialized = DingJson.toJSONString(arr);
        JSONArray parsedAgain = DingJson.parseArray(serialized);
        Assert.assertEquals(arr, parsedAgain);
    }

    // ── Scientific notation edge cases ────────────────────────────

    @Test
    public void parseNumberScientificExact() {
        // These should parse to exactly known Double values
        Assert.assertEquals(Double.valueOf(1.23e4), DingJson.parse("1.23e4"));
        Assert.assertEquals(Double.valueOf(1.23E4), DingJson.parse("1.23E4"));
        Assert.assertEquals(Double.valueOf(1.23e-4), DingJson.parse("1.23e-4"));
    }

    @Test
    public void parseNumberNegativeExponent() {
        Assert.assertEquals(Double.valueOf(1.5e-10), DingJson.parse("1.5e-10"));
    }

    @Test
    public void parseNumberZeroExponent() {
        Assert.assertEquals(Double.valueOf(1.0e0), DingJson.parse("1.0e0"));
        Assert.assertEquals(Double.valueOf(1e0), DingJson.parse("1e0"));
    }

    // ── Number serialization roundtrip (inspired by Gson) ──────────

    @Test
    public void parseIntegerRoundtrip() {
        Object parsed = DingJson.parse("42");
        Assert.assertTrue(parsed instanceof Integer);
        Assert.assertEquals("42", DingJson.toJSONString(parsed));
    }

    @Test
    public void parseLongRoundtrip() {
        Object parsed = DingJson.parse("2147483648");
        Assert.assertTrue(parsed instanceof Long);
        Assert.assertEquals("2147483648", DingJson.toJSONString(parsed));
    }

    @Test
    public void parseDoubleRoundtrip() {
        Object parsed = DingJson.parse("3.14");
        Assert.assertTrue(parsed instanceof Double);
        // 3.14 serialized back: Double.toString(3.14) = "3.14"
        // JSONSerializer uses sb.append(value) for Number
        Assert.assertEquals("3.14", DingJson.toJSONString(parsed));
    }

    @Test
    public void parseLargeIntegerDoesNotLosePrecision() {
        // Long.MAX_VALUE = 9223372036854775807
        String longStr = "9223372036854775807";
        Object parsed = DingJson.parse(longStr);
        Assert.assertTrue("Should be a Long, got " + parsed.getClass(), parsed instanceof Long);
        Assert.assertEquals(Long.valueOf(Long.MAX_VALUE), parsed);
    }

    // ── String escaping edge cases ────────────────────────────────

    @Test
    public void parseStringAllEscapeChars() {
        // Test all JSON escape sequences in one string
        String json = "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"";
        String expected = "\"\\/\b\f\n\r\t";
        Assert.assertEquals(expected, DingJson.parse(json));
    }

    @Test
    public void parseStringWithForwardSlash() {
        // Forward slash may be escaped but doesn't have to be
        Assert.assertEquals("/", DingJson.parse("\"\\/\""));
        Assert.assertEquals("/", DingJson.parse("\"/\""));
    }

    @Test
    public void parseStringWithMultipleUnicodeEscapes() {
        String json = "\"\\u0048\\u0065\\u006c\\u006c\\u006f\"";
        Assert.assertEquals("Hello", DingJson.parse(json));
    }
}
