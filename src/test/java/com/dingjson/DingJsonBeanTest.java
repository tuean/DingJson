package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Bean serialization/deserialization tests for DingJson.
 * Patterns inspired by Gson and Jackson bean conversion tests.
 */
public class DingJsonBeanTest {

    // ── Simple POJOs ──────────────────────────────────────────────

    public static class SimpleBean {
        private String name;
        private int age;
        private boolean active;

        public SimpleBean() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Test
    public void deserializeSimpleBean() {
        String json = "{\"name\":\"Alice\",\"age\":30,\"active\":true}";
        SimpleBean bean = DingJson.parseObject(json, SimpleBean.class);
        Assert.assertEquals("Alice", bean.getName());
        Assert.assertEquals(30, bean.getAge());
        Assert.assertTrue(bean.isActive());
    }

    @Test
    public void deserializeBeanWithMissingField() {
        // Missing fields should be left as defaults
        SimpleBean bean = DingJson.parseObject("{\"name\":\"Bob\"}", SimpleBean.class);
        Assert.assertEquals("Bob", bean.getName());
        Assert.assertEquals(0, bean.getAge()); // default int
        Assert.assertFalse(bean.isActive()); // default boolean
    }

    @Test
    public void deserializeBeanWithExtraField() {
        // Extra fields in JSON should be ignored
        SimpleBean bean = DingJson.parseObject(
                "{\"name\":\"Charlie\",\"age\":25,\"active\":true,\"extra\":\"ignored\"}",
                SimpleBean.class);
        Assert.assertEquals("Charlie", bean.getName());
        Assert.assertEquals(25, bean.getAge());
    }

    // ── Null handling ─────────────────────────────────────────────

    public static class NullableBean {
        private String name;
        private Integer count;

        public NullableBean() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }

    @Test
    public void deserializeBeanWithNullField() {
        NullableBean bean = DingJson.parseObject(
                "{\"name\":null,\"count\":null}", NullableBean.class);
        Assert.assertNull(bean.getName());
        Assert.assertNull(bean.getCount());
    }

    @Test
    public void deserializeBeanWithMixedNull() {
        NullableBean bean = DingJson.parseObject(
                "{\"name\":\"test\",\"count\":null}", NullableBean.class);
        Assert.assertEquals("test", bean.getName());
        Assert.assertNull(bean.getCount());
    }

    // ── Nested beans ──────────────────────────────────────────────

    public static class Address {
        private String city;
        private String zip;

        public Address() {}

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getZip() { return zip; }
        public void setZip(String zip) { this.zip = zip; }
    }

    public static class Person {
        private String name;
        private Address address;

        public Person() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Address getAddress() { return address; }
        public void setAddress(Address address) { this.address = address; }
    }

    @Test
    public void deserializeNestedBean() {
        String json = "{\"name\":\"Alice\",\"address\":{\"city\":\"Shanghai\",\"zip\":\"200000\"}}";
        Person person = DingJson.parseObject(json, Person.class);
        Assert.assertEquals("Alice", person.getName());
        Assert.assertNotNull(person.getAddress());
        Assert.assertEquals("Shanghai", person.getAddress().getCity());
        Assert.assertEquals("200000", person.getAddress().getZip());
    }

    @Test
    public void deserializeNestedBeanWithNullAddress() {
        String json = "{\"name\":\"Bob\",\"address\":null}";
        Person person = DingJson.parseObject(json, Person.class);
        Assert.assertEquals("Bob", person.getName());
        Assert.assertNull(person.getAddress());
    }

    // ── List of beans ─────────────────────────────────────────────

    @Test
    public void deserializeListOfBeans() {
        String json = "[{\"name\":\"Alice\",\"age\":28},{\"name\":\"Bob\",\"age\":30}]";
        List<SimpleBean> users = DingJson.parseArray(json, SimpleBean.class);
        Assert.assertEquals(2, users.size());
        Assert.assertEquals("Alice", users.get(0).getName());
        Assert.assertEquals(28, users.get(0).getAge());
        Assert.assertEquals("Bob", users.get(1).getName());
        Assert.assertEquals(30, users.get(1).getAge());
    }

    @Test
    public void deserializeEmptyListOfBeans() {
        List<SimpleBean> users = DingJson.parseArray("[]", SimpleBean.class);
        Assert.assertTrue(users.isEmpty());
    }

    // ── Type conversion (inspired by Gson) ─────────────────────────

    public static class TypeConversionBean {
        private long longVal;
        private double doubleVal;
        private boolean boolVal;
        private String strVal;

        public TypeConversionBean() {}

        public long getLongVal() { return longVal; }
        public void setLongVal(long longVal) { this.longVal = longVal; }
        public double getDoubleVal() { return doubleVal; }
        public void setDoubleVal(double doubleVal) { this.doubleVal = doubleVal; }
        public boolean isBoolVal() { return boolVal; }
        public void setBoolVal(boolean boolVal) { this.boolVal = boolVal; }
        public String getStrVal() { return strVal; }
        public void setStrVal(String strVal) { this.strVal = strVal; }
    }

    @Test
    public void deserializeTypeConversion() {
        // Integer in JSON → long field (widening)
        // Integer in JSON → double field (widening)
        // Boolean in JSON → boolean field
        // Number in JSON → String field (toString)
        String json = "{\"longVal\":42,\"doubleVal\":3,\"boolVal\":true,\"strVal\":123}";
        TypeConversionBean bean = DingJson.parseObject(json, TypeConversionBean.class);
        Assert.assertEquals(42L, bean.getLongVal());
        Assert.assertEquals(3.0, bean.getDoubleVal(), 0.0001);
        Assert.assertTrue(bean.isBoolVal());
        Assert.assertEquals("123", bean.getStrVal());
    }

    // ── Double precision (inspired by Jackson) ─────────────────────

    public static class DoubleBean {
        private double value;
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
    }

    @Test
    public void deserializeDoublePrecision() {
        DoubleBean bean = DingJson.parseObject("{\"value\":3.14159265358979}", DoubleBean.class);
        Assert.assertEquals(3.14159265358979, bean.getValue(), 1e-14);
    }

    // ── Inheritance support (inspired by Gson) ─────────────────────

    public static class BaseBean {
        private String baseField;

        public String getBaseField() { return baseField; }
        public void setBaseField(String baseField) { this.baseField = baseField; }
    }

    public static class DerivedBean extends BaseBean {
        private String derivedField;

        public String getDerivedField() { return derivedField; }
        public void setDerivedField(String derivedField) { this.derivedField = derivedField; }
    }

    @Test
    public void deserializeBeanWithInheritance() {
        String json = "{\"baseField\":\"base\",\"derivedField\":\"derived\"}";
        DerivedBean bean = DingJson.parseObject(json, DerivedBean.class);
        Assert.assertEquals("base", bean.getBaseField());
        Assert.assertEquals("derived", bean.getDerivedField());
    }

    @Test
    public void serializeBeanWithInheritance() {
        DerivedBean bean = new DerivedBean();
        bean.setBaseField("base");
        bean.setDerivedField("derived");
        String json = DingJson.toJSONString(bean);
        Assert.assertTrue(json.contains("\"baseField\":\"base\""));
        Assert.assertTrue(json.contains("\"derivedField\":\"derived\""));
    }

    // ── JSONObject.getObject convenience (inspired by Gson) ────────

    @Test
    public void getObjectFromJSONObject() {
        String json = "{\"user\":{\"name\":\"Alice\",\"age\":30}}";
        JSONObject root = DingJson.parseObject(json);
        SimpleBean user = root.getObject("user", SimpleBean.class);
        Assert.assertNotNull(user);
        Assert.assertEquals("Alice", user.getName());
        Assert.assertEquals(30, user.getAge());
    }

    @Test
    public void getObjectReturnsNullForMissingKey() {
        JSONObject root = DingJson.parseObject("{}");
        Assert.assertNull(root.getObject("missing", SimpleBean.class));
    }

    @Test(expected = JSONException.class)
    public void getObjectFailsForNonObjectValue() {
        JSONObject root = DingJson.parseObject("{\"key\":\"string\"}");
        root.getObject("key", SimpleBean.class);
    }

    // ── Enum support ──────────────────────────────────────────────

    public enum Status {
        ACTIVE, INACTIVE, PENDING
    }

    public static class BeanWithEnum {
        private Status status;

        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }
    }

    @Test
    public void deserializeEnum() {
        BeanWithEnum bean = DingJson.parseObject("{\"status\":\"ACTIVE\"}", BeanWithEnum.class);
        Assert.assertEquals(Status.ACTIVE, bean.getStatus());
    }

    // ── Boolean handling (inspired by Gson) ────────────────────────

    public static class BooleanBean {
        private boolean flag;
        private Boolean boxedFlag;

        public boolean isFlag() { return flag; }
        public void setFlag(boolean flag) { this.flag = flag; }
        public Boolean getBoxedFlag() { return boxedFlag; }
        public void setBoxedFlag(Boolean boxedFlag) { this.boxedFlag = boxedFlag; }
    }

    @Test
    public void deserializeBooleanPrimitive() {
        BooleanBean bean = DingJson.parseObject("{\"flag\":true}", BooleanBean.class);
        Assert.assertTrue(bean.isFlag());
    }

    @Test
    public void deserializeBoxedBoolean() {
        BooleanBean bean = DingJson.parseObject("{\"boxedFlag\":false}", BooleanBean.class);
        Assert.assertEquals(Boolean.FALSE, bean.getBoxedFlag());
    }

    @Test
    public void deserializeBoxedBooleanNull() {
        BooleanBean bean = DingJson.parseObject("{\"boxedFlag\":null}", BooleanBean.class);
        Assert.assertNull(bean.getBoxedFlag());
    }

    // ── Override toJSON (inspired by Gson instance creators) ──────

    @Test
    public void toJSONPreservesJSONObject() {
        JSONObject obj = new JSONObject();
        obj.put("a", 1);
        // DingJson.toJSON returns the same instance
        Assert.assertSame(obj, DingJson.toJSON(obj));
    }

    @Test
    public void toJSONPreservesJSONArray() {
        JSONArray arr = new JSONArray();
        arr.add(1);
        // DingJson.toJSONArray returns the same instance
        Assert.assertSame(arr, DingJson.toJSONArray(arr));
    }

    // ── Round-trip with nulls (inspired by Gson) ───────────────────

    @Test
    public void roundTripWithNulls() {
        NullableBean original = new NullableBean();
        original.setName(null);
        original.setCount(42);

        String json = DingJson.toJSONString(original);
        NullableBean restored = DingJson.parseObject(json, NullableBean.class);

        Assert.assertNull(restored.getName());
        Assert.assertEquals(Integer.valueOf(42), restored.getCount());
    }
}
