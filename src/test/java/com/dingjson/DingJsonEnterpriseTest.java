package com.dingjson;

import com.dingjson.element.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

/**
 * Enterprise-readiness regression tests (wave 1: P0 fixes).
 * Covers the failures that break real production code:
 * NaN/Infinity must not leak invalid JSON; JDK types (Date/UUID/money)
 * must survive serialization despite JPMS strong encapsulation.
 */
public class DingJsonEnterpriseTest {

    public static class Order {
        public String id;
        public Date createdAt;
        public BigDecimal amount;
        public BigInteger count;
        public UUID uuid;
        public LocalDate day;
        public LocalDateTime at;
        public Instant instant;
        public Calendar calendar;
    }

    private Order sample() {
        Order o = new Order();
        o.id = "A1";
        o.createdAt = new Date(1700000000000L);
        o.amount = new BigDecimal("123.45");
        o.count = new BigInteger("9223372036854775807");
        o.uuid = UUID.fromString("6dc6ce8d-4cee-430b-bf9c-aa4cf80b3c20");
        o.day = LocalDate.of(2024, 1, 15);
        o.at = LocalDateTime.of(2024, 1, 15, 10, 30);
        o.instant = Instant.ofEpochMilli(1700000000000L);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(1700000000000L);
        o.calendar = cal;
        return o;
    }

    // ══════════════════════════════════════════════════════════════
    // NaN / Infinity — must never leak invalid JSON
    // ══════════════════════════════════════════════════════════════

    @Test
    public void nanSerializesAsNull() {
        JSONObject obj = new JSONObject();
        obj.put("nan", Double.NaN);
        String json = DingJson.toJSONString(obj);
        Assert.assertEquals("{\"nan\":null}", json);
        // and the output is valid JSON
        Assert.assertNull(DingJson.parseObject(json).get("nan"));
    }

    @Test
    public void infinitySerializesAsNull() {
        JSONObject obj = new JSONObject();
        obj.put("pos", Double.POSITIVE_INFINITY);
        obj.put("neg", Double.NEGATIVE_INFINITY);
        String json = DingJson.toJSONString(obj);
        Assert.assertEquals("{\"pos\":null,\"neg\":null}", json);
    }

    @Test
    public void floatNanSerializesAsNull() {
        JSONObject obj = new JSONObject();
        obj.put("f", Float.NaN);
        Assert.assertEquals("{\"f\":null}", DingJson.toJSONString(obj));
    }

    @Test
    public void nanInArraySerializesAsNull() {
        String json = DingJson.toJSONString(new Object[]{Double.NaN, 1.0});
        Assert.assertEquals("[null,1.0]", json);
    }

    @Test
    public void nanInBeanFieldSerializesAsNull() {
        Order o = new Order();
        o.amount = null;
        o.createdAt = new Date();
        // NaN via a double field
        String json = DingJson.toJSONString(new Object() {
            public double d = Double.NaN;
        });
        Assert.assertEquals("{\"d\":null}", json);
    }

    @Test
    public void nanPrettyPrint() {
        JSONObject obj = new JSONObject();
        obj.put("nan", Double.NaN);
        String json = DingJson.toJSONString(obj, true);
        Assert.assertTrue(json.contains("null"));
    }

    @Test
    public void normalNumbersUnaffected() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1.5);
        obj.put("b", -0.0);
        obj.put("c", 1e308);
        String json = DingJson.toJSONString(obj);
        Assert.assertTrue(json.contains("\"a\":1.5"));
        Assert.assertTrue(json.contains("\"c\":1.0E308"));
    }

    // ══════════════════════════════════════════════════════════════
    // JDK types — serialization + roundtrip (JPMS safe)
    // ══════════════════════════════════════════════════════════════

    @Test
    public void dateSerializesAsEpochMillis() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        Assert.assertTrue(json.contains("\"createdAt\":1700000000000"));
    }

    @Test
    public void dateRoundTrip() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertEquals(o.createdAt, back.createdAt);
    }

    @Test
    public void uuidRoundTrip() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertEquals(o.uuid, back.uuid);
    }

    @Test
    public void bigDecimalRoundTrip() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertEquals(o.amount, back.amount);
        Assert.assertEquals(o.count, back.count);
    }

    @Test
    public void bigDecimalPrecisionPreserved() {
        Order o = new Order();
        o.amount = new BigDecimal("0.1000000000000000000001");
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertEquals(o.amount, back.amount); // no floating-point loss
    }

    @Test
    public void javaTimeRoundTrip() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertEquals(o.day, back.day);
        Assert.assertEquals(o.at, back.at);
        Assert.assertEquals(o.instant, back.instant);
    }

    @Test
    public void calendarRoundTrip() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertNotNull(back.calendar);
        Assert.assertEquals(o.calendar.getTimeInMillis(), back.calendar.getTimeInMillis());
    }

    @Test
    public void jdkTypesInList() {
        Order o = sample();
        String json = DingJson.toJSONString(new Order[]{o, o});
        Order[] back = DingJson.parseObject(json, new TypeReference<Order[]>() {
        });
        Assert.assertEquals(2, back.length);
        Assert.assertEquals(o.uuid, back[0].uuid);
    }

    @Test
    public void jdkTypeNullFields() {
        Order o = new Order(); // all fields null
        String json = DingJson.toJSONString(o);
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertNull(back.createdAt);
        Assert.assertNull(back.uuid);
        Assert.assertNull(back.amount);
    }

    // ══════════════════════════════════════════════════════════════
    // Streaming path parity
    // ══════════════════════════════════════════════════════════════

    @Test
    public void streamingJdkTypes() {
        Order o = sample();
        String json = DingJson.toJSONString(o);
        // parseObject(Class) uses the streaming reader — must hit the same JDK-type reads
        Order back = DingJson.parseObject(json, Order.class);
        Assert.assertEquals(o.createdAt, back.createdAt);
        Assert.assertEquals(o.uuid, back.uuid);
        Assert.assertEquals(o.amount, back.amount);
        Assert.assertEquals(o.day, back.day);
    }

    @Test
    public void nullJsonRootReturnsNull() {
        // Gson-compatible: fromJson("null", X.class) → null
        Assert.assertNull(DingJson.parseObject("null", Order.class));
    }
}
