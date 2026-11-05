package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import com.dingjson.parser.JSONReader;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the streaming {@link JSONReader} API.
 */
public class JSONReaderTest {

    // ── Object streaming ──────────────────────────────────────────

    @Test
    public void streamSimpleObject() {
        JSONReader r = DingJson.createReader("{\"name\":\"Ding\",\"age\":3,\"active\":true,\"extra\":null}");
        r.readBeginObject();
        String name = null;
        Integer age = null;
        Boolean active = null;
        boolean sawNull = false;
        while (r.peek() != '}') {
            String key = r.readKey();
            switch (key) {
                case "name":  name = r.readString();  break;
                case "age":   age = r.readInt();      break;
                case "active": active = r.readBoolean(); break;
                case "extra": r.readNull(); sawNull = true; break;
                default:      r.skipValue();
            }
        }
        r.readEndObject();
        r.ensureEnded();

        Assert.assertEquals("Ding", name);
        Assert.assertEquals(Integer.valueOf(3), age);
        Assert.assertEquals(Boolean.TRUE, active);
        Assert.assertTrue(sawNull);
    }

    @Test
    public void streamNestedObject() {
        JSONReader r = DingJson.createReader("{\"user\":{\"id\":7,\"tags\":[\"a\",\"b\"]}}");
        r.readBeginObject();
        Assert.assertEquals("user", r.readKey());
        r.readBeginObject();
        Assert.assertEquals("id", r.readKey());
        Assert.assertEquals(7, r.readLong());
        Assert.assertEquals("tags", r.readKey());
        String[] tags = r.readStringArray();
        Assert.assertArrayEquals(new String[]{"a", "b"}, tags);
        r.readEndObject();
        r.readEndObject();
        r.ensureEnded();
    }

    // ── Typed arrays ──────────────────────────────────────────────

    @Test
    public void readIntArray() {
        JSONReader r = DingJson.createReader("[1,2,3,4,5]");
        int[] arr = r.readIntArray();
        Assert.assertArrayEquals(new int[]{1, 2, 3, 4, 5}, arr);
        r.ensureEnded();
    }

    @Test
    public void readIntArrayLargeGrowsDynamically() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(',');
            sb.append(i);
        }
        sb.append(']');
        int[] arr = DingJson.createReader(sb.toString()).readIntArray();
        Assert.assertEquals(1000, arr.length);
        Assert.assertEquals(0, arr[0]);
        Assert.assertEquals(999, arr[999]);
    }

    @Test
    public void readIntArrayEmpty() {
        int[] arr = DingJson.createReader("[]").readIntArray();
        Assert.assertEquals(0, arr.length);
    }

    @Test
    public void readIntArraySingleElement() {
        int[] arr = DingJson.createReader("[42]").readIntArray();
        Assert.assertArrayEquals(new int[]{42}, arr);
    }

    @Test
    public void readIntArrayNegativeValues() {
        int[] arr = DingJson.createReader("[-1,-2147483648,2147483647]").readIntArray();
        Assert.assertArrayEquals(new int[]{-1, Integer.MIN_VALUE, Integer.MAX_VALUE}, arr);
    }

    @Test
    public void readLongArray() {
        long[] arr = DingJson.createReader("[1,2147483648,9223372036854775807]").readLongArray();
        Assert.assertEquals(1L, arr[0]);
        Assert.assertEquals(2147483648L, arr[1]);
        Assert.assertEquals(Long.MAX_VALUE, arr[2]);
    }

    @Test
    public void readDoubleArray() {
        double[] arr = DingJson.createReader("[1.5,2e3,-0.25]").readDoubleArray();
        Assert.assertEquals(1.5, arr[0], 0.0);
        Assert.assertEquals(2000.0, arr[1], 0.0);
        Assert.assertEquals(-0.25, arr[2], 0.0);
    }

    @Test
    public void readBooleanArray() {
        boolean[] arr = DingJson.createReader("[true,false,true]").readBooleanArray();
        Assert.assertArrayEquals(new boolean[]{true, false, true}, arr);
    }

    @Test
    public void readStringArray() {
        String[] arr = DingJson.createReader("[\"x\",\"y z\",\"\"]").readStringArray();
        Assert.assertArrayEquals(new String[]{"x", "y z", ""}, arr);
    }

    @Test
    public void arraysWithWhitespace() {
        int[] arr = DingJson.createReader(" [ 1 , 2 , 3 ] ").readIntArray();
        Assert.assertArrayEquals(new int[]{1, 2, 3}, arr);
    }

    // ── Consistency with tree API ─────────────────────────────────

    @Test
    public void streamedTreeEqualsParsedTree() {
        String json = "{\"a\":[1,2,3],\"b\":{\"c\":\"x\"},\"d\":4.5}";
        JSONReader r = DingJson.createReader(json);
        JSONObject streamed = (JSONObject) r.readValue();
        r.ensureEnded();
        JSONObject parsed = DingJson.parseObject(json);
        Assert.assertEquals(parsed, streamed);
    }

    @Test
    public void typedArrayMatchesGenericParse() {
        String json = "[10,20,30,40]";
        int[] arr = DingJson.createReader(json).readIntArray();
        JSONArray generic = DingJson.parseArray(json);
        for (int i = 0; i < arr.length; i++) {
            Assert.assertEquals(Integer.valueOf(arr[i]), generic.getInteger(i));
        }
    }

    // ── Error paths ───────────────────────────────────────────────

    @Test(expected = JSONException.class)
    public void readStringOnNumberFails() {
        DingJson.createReader("42").readString();
    }

    @Test(expected = JSONException.class)
    public void readIntArrayWithDecimalFails() {
        // readIntArray requires integers
        DingJson.createReader("[1,2.5]").readIntArray();
    }

    @Test(expected = JSONException.class)
    public void readBooleanOnNumberFails() {
        DingJson.createReader("42").readBoolean();
    }

    @Test(expected = JSONException.class)
    public void readKeyOutsideObjectFails() {
        DingJson.createReader("[]").readKey();
    }

    @Test(expected = JSONException.class)
    public void missingColonFails() {
        JSONReader r = DingJson.createReader("{\"a\" 1}");
        r.readBeginObject();
        r.readKey(); // should throw — colon missing
    }

    @Test(expected = JSONException.class)
    public void ensureEndedRejectsTrailing() {
        JSONReader r = DingJson.createReader("{} extra");
        r.readValue();
        r.ensureEnded();
    }

    @Test(expected = JSONException.class)
    public void readNullOnStringFails() {
        DingJson.createReader("\"x\"").readNull();
    }

    @Test(expected = JSONException.class)
    public void emptyInputFails() {
        DingJson.createReader("").readValue();
    }

    // ── SkipValue ─────────────────────────────────────────────────

    @Test
    public void skipUnknownFields() {
        JSONReader r = DingJson.createReader(
                "{\"known\":1,\"blob\":{\"deep\":[1,{\"x\":2}]},\"tail\":\"ok\"}");
        r.readBeginObject();
        String known = null, tail = null;
        while (r.peek() != '}') {
            String key = r.readKey();
            switch (key) {
                case "known": known = String.valueOf(r.readInt()); break;
                case "tail":  tail = r.readString(); break;
                default:      r.skipValue();
            }
        }
        r.readEndObject();
        r.ensureEnded();
        Assert.assertEquals("1", known);
        Assert.assertEquals("ok", tail);
    }

    @Test
    public void skipHugeArray() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50_000; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"i\":").append(i).append('}');
        }
        sb.append(']');
        JSONReader r = DingJson.createReader(sb.toString());
        r.skipValue(); // must not build a tree / must not overflow
        r.ensureEnded();
    }

    // ── Streaming vs tree on a realistic doc ──────────────────────

    @Test
    public void streamRealisticDocument() {
        String json = "{\"total\":3,\"items\":["
                + "{\"id\":1,\"price\":10.5,\"tags\":[\"a\"]},"
                + "{\"id\":2,\"price\":11.5,\"tags\":[\"a\",\"b\"]},"
                + "{\"id\":3,\"price\":12.5,\"tags\":[]}]}";

        JSONReader r = DingJson.createReader(json);
        r.readBeginObject();
        long total = -1;
        List<long[]> idsAndPrices = new ArrayList<>();
        while (r.peek() != '}') {
            String key = r.readKey();
            switch (key) {
                case "total":
                    total = r.readLong();
                    break;
                case "items":
                    r.readBeginArray();
                    while (r.peek() != ']') {
                        r.readBeginObject();
                        long id = -1;
                        double price = -1;
                        while (r.peek() != '}') {
                            String k = r.readKey();
                            switch (k) {
                                case "id":    id = r.readLong(); break;
                                case "price": price = r.readDouble(); break;
                                default:      r.skipValue(); // tags
                            }
                        }
                        r.readEndObject();
                        idsAndPrices.add(new long[]{id, (long) price});
                    }
                    r.readEndArray();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.readEndObject();
        r.ensureEnded();

        Assert.assertEquals(3, total);
        Assert.assertEquals(3, idsAndPrices.size());
        Assert.assertEquals(2L, idsAndPrices.get(1)[0]);
        Assert.assertEquals(12L, idsAndPrices.get(2)[1]);
    }
}
