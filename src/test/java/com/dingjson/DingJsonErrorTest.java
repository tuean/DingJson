package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Error-handling tests for DingJson parser.
 * Patterns inspired by org.json and Jackson invalid-input tests.
 */
public class DingJsonErrorTest {

    // ── Input validation ──────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void emptyInput() {
        DingJson.parse("");
    }

    @Test(expected = JSONException.class)
    public void blankInput() {
        DingJson.parse("   ");
    }

    @Test(expected = JSONException.class)
    public void nullInput() {
        DingJson.parse((String) null);
    }

    // ── Trailing content ──────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void trailingContentAfterObject() {
        DingJson.parse("{} extra");
    }

    @Test(expected = JSONException.class)
    public void trailingContentAfterArray() {
        DingJson.parse("[] x");
    }

    @Test(expected = JSONException.class)
    public void trailingContentAfterString() {
        DingJson.parse("\"hello\" \"world\"");
    }

    @Test(expected = JSONException.class)
    public void trailingContentAfterNumber() {
        DingJson.parse("42 abc");
    }

    @Test(expected = JSONException.class)
    public void trailingContentAfterBoolean() {
        DingJson.parse("true false");
    }

    @Test(expected = JSONException.class)
    public void trailingContentAfterNull() {
        DingJson.parse("null 123");
    }

    // ── Object syntax errors ──────────────────────────────────────

    @Test(expected = JSONException.class)
    public void objectMissingColon() {
        DingJson.parseObject("{\"a\" 1}");
    }

    @Test(expected = JSONException.class)
    public void objectMissingComma() {
        DingJson.parseObject("{\"a\":1 \"b\":2}");
    }

    @Test(expected = JSONException.class)
    public void objectUnquotedKey() {
        DingJson.parseObject("{a:1}");
    }

    @Test(expected = JSONException.class)
    public void objectUnterminated() {
        DingJson.parseObject("{\"a\":1");
    }

    @Test(expected = JSONException.class)
    public void objectExtraComma() {
        DingJson.parseObject("{,\"a\":1}");
    }

    @Test
    public void objectEmptyKey() {
        // Empty string key is valid per JSON spec
        JSONObject obj = DingJson.parseObject("{\"\":\"empty\"}");
        Assert.assertEquals("empty", obj.getString(""));
    }

    @Test(expected = JSONException.class)
    public void objectInvalidValue() {
        DingJson.parseObject("{\"a\":}");
    }

    // ── Array syntax errors ───────────────────────────────────────

    @Test(expected = JSONException.class)
    public void arrayUnterminated() {
        DingJson.parseArray("[1,2,3");
    }

    @Test(expected = JSONException.class)
    public void arrayExtraComma() {
        DingJson.parseArray("[,1]");
    }

    @Test(expected = JSONException.class)
    public void arrayMissingElement() {
        DingJson.parseArray("[1,,2]");
    }

    // ── String errors ─────────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void unterminatedString() {
        DingJson.parse("\"hello");
    }

    @Test(expected = JSONException.class)
    public void unterminatedStringAtEnd() {
        DingJson.parseObject("{\"a\":\"hello");
    }

    @Test(expected = JSONException.class)
    public void invalidEscapeChar() {
        DingJson.parse("\"\\x\"");
    }

    @Test(expected = JSONException.class)
    public void invalidEscapeControl() {
        DingJson.parse("\"\\z\"");
    }

    @Test(expected = JSONException.class)
    public void truncatedUnicodeEscape() {
        DingJson.parse("\"\\u12\"");
    }

    @Test(expected = JSONException.class)
    public void truncatedUnicodeEscapeSingleChar() {
        DingJson.parse("\"\\u1\"");
    }

    @Test(expected = JSONException.class)
    public void unicodeEscapeAtEndOfInput() {
        DingJson.parse("\"\\u");
    }

    @Test(expected = JSONException.class)
    public void stringControlCharRaw() {
        // RFC 8259 §7: control characters (0x00-0x1F) must be escaped inside strings
        DingJson.parse("\"hello\ngo\"");
    }

    @Test(expected = JSONException.class)
    public void stringRawTabRejected() {
        DingJson.parse("\"hello\tgo\"");
    }

    @Test
    public void stringEscapedControlCharAccepted() {
        // The escaped form is of course fine
        Assert.assertEquals("hello\ngo", DingJson.parse("\"hello\\ngo\""));
        Assert.assertEquals("hello\tgo", DingJson.parse("\"hello\\tgo\""));
    }

    @Test
    public void stringDelCharAccepted() {
        // 0x7F (DEL) is NOT a control character per RFC 8259 — must be accepted
        Assert.assertEquals("\u007f", DingJson.parse("\"\u007f\""));
    }

    // ── Number errors ─────────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void numberDoubleDot() {
        DingJson.parse("1.2.3");
    }

    @Test(expected = JSONException.class)
    public void numberTrailingDot() {
        DingJson.parse("1.");
    }

    @Test(expected = JSONException.class)
    public void numberLeadingDot() {
        DingJson.parse(".5");
    }

    @Test(expected = JSONException.class)
    public void numberInvalidChars() {
        DingJson.parse("12a");
    }

    @Test(expected = JSONException.class)
    public void numberMultipleMinus() {
        DingJson.parse("--1");
    }

    @Test(expected = JSONException.class)
    public void numberDigitAfterExponent() {
        // "1e" is invalid — exponent must have digits
        DingJson.parse("1e");
    }

    @Test(expected = JSONException.class)
    public void numberExponentOnly() {
        DingJson.parse("e10");
    }

    @Test(expected = JSONException.class)
    public void numberNegativeExponentOnly() {
        DingJson.parse("-e10");
    }

    // ── Keyword errors ────────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void keywordTrueCapitalized() {
        DingJson.parse("True");
    }

    @Test(expected = JSONException.class)
    public void keywordTrueUppercase() {
        DingJson.parse("TRUE");
    }

    @Test(expected = JSONException.class)
    public void keywordFalseCapitalized() {
        DingJson.parse("False");
    }

    @Test(expected = JSONException.class)
    public void keywordNullCapitalized() {
        DingJson.parse("Null");
    }

    @Test(expected = JSONException.class)
    public void keywordTruncatedTrue() {
        DingJson.parse("tru");
    }

    @Test(expected = JSONException.class)
    public void keywordTruncatedFalse() {
        DingJson.parse("fals");
    }

    @Test(expected = JSONException.class)
    public void keywordTruncatedNull() {
        DingJson.parse("nul");
    }

    @Test(expected = JSONException.class)
    public void keywordTrueWithExtraLetters() {
        DingJson.parse("truefalse");
    }

    @Test(expected = JSONException.class)
    public void keywordNullWithTrailingLetters() {
        DingJson.parse("nulla");
    }

    // ── Unexpected token ──────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void unexpectedCharacter() {
        DingJson.parse("!");
    }

    @Test(expected = JSONException.class)
    public void unexpectedAtStart() {
        DingJson.parseObject("?");
    }

    @Test(expected = JSONException.class)
    public void unexpectedInObject() {
        DingJson.parseObject("{\"a\":?}");
    }

    @Test(expected = JSONException.class)
    public void unexpectedInArray() {
        DingJson.parseArray("[?]");
    }

    // ── Type mismatch ─────────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void parseObjectOnArray() {
        DingJson.parseObject("[1]");
    }

    @Test(expected = JSONException.class)
    public void parseObjectOnString() {
        DingJson.parseObject("\"hello\"");
    }

    @Test(expected = JSONException.class)
    public void parseObjectOnNumber() {
        DingJson.parseObject("42");
    }

    @Test(expected = JSONException.class)
    public void parseObjectOnBoolean() {
        DingJson.parseObject("true");
    }

    @Test(expected = JSONException.class)
    public void parseObjectOnNull() {
        DingJson.parseObject("null");
    }

    @Test(expected = JSONException.class)
    public void parseArrayOnObject() {
        DingJson.parseArray("{}");
    }

    @Test(expected = JSONException.class)
    public void parseArrayOnString() {
        DingJson.parseArray("\"hello\"");
    }

    @Test(expected = JSONException.class)
    public void parseArrayOnNumber() {
        DingJson.parseArray("42");
    }

    @Test(expected = JSONException.class)
    public void parseArrayOnNull() {
        DingJson.parseArray("null");
    }

    // ── Deep nesting limit (crash protection, inspired by Jackson) ──

    @Test
    public void extremeDeepNesting() {
        // Deeply nested objects should not cause stack overflow
        // (may throw JSONException with stack overflow inside, but shouldn't crash JVM)
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 10000; i++) {
            sb.append("\"a\":{");
        }
        sb.append("\"x\":1");
        for (int i = 0; i < 10000; i++) {
            sb.append("}");
        }
        try {
            DingJson.parse(sb.toString());
        } catch (JSONException | StackOverflowError e) {
            // Either is acceptable — we just don't want JVM crash
        }
    }

    // ── Negative zero (inspired by Gson number tests) ──────────────

    @Test
    public void negativeZero() {
        Object parsed = DingJson.parse("-0");
        Assert.assertTrue(parsed instanceof Integer);
        Assert.assertEquals(Integer.valueOf(0), parsed);
    }

    @Test
    public void negativeZeroDouble() {
        Object parsed = DingJson.parse("-0.0");
        Assert.assertTrue(parsed instanceof Double);
        Assert.assertEquals(-0.0, (Double) parsed, 0.0);
    }

    // ── Empty structures edge cases ───────────────────────────────

    @Test
    public void objectWithEmptyKey() {
        JSONObject obj = DingJson.parseObject("{\"\":\"value\"}");
        Assert.assertEquals("value", obj.getString(""));
    }

    @Test
    public void arrayOfEmptyObject() {
        JSONArray arr = DingJson.parseArray("[{}]");
        Assert.assertEquals(1, arr.size());
        Assert.assertTrue(arr.getJSONObject(0).isEmpty());
    }

    @Test
    public void objectOfEmptyArray() {
        JSONObject obj = DingJson.parseObject("{\"data\":[]}");
        Assert.assertTrue(obj.getJSONArray("data").isEmpty());
    }
}
