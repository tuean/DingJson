package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * Complex-scenario tests: string → typed lists, nested generics,
 * lists of beans, maps of beans, mixed trees.
 * These cover the cases that flat {@code Class}-based deserialization
 * cannot express — they need {@link TypeReference}.
 */
public class DingJsonComplexTest {

    // ── Nested POJOs ──────────────────────────────────────────────

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
        private List<Address> addresses;          // list of beans inside a bean
        private Map<String, Address> contacts;    // map of beans inside a bean

        public Person() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<Address> getAddresses() { return addresses; }
        public void setAddresses(List<Address> addresses) { this.addresses = addresses; }
        public Map<String, Address> getContacts() { return contacts; }
        public void setContacts(Map<String, Address> contacts) { this.contacts = contacts; }
    }

    public static class Order {
        private int id;
        private List<List<Double>> matrix;        // nested list of lists
        private Map<String, List<String>> groups; // map of lists
        private Map<String, List<Address>> teams; // map of lists of beans

        public Order() {}

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public List<List<Double>> getMatrix() { return matrix; }
        public void setMatrix(List<List<Double>> matrix) { this.matrix = matrix; }
        public Map<String, List<String>> getGroups() { return groups; }
        public void setGroups(Map<String, List<String>> groups) { this.groups = groups; }
        public Map<String, List<Address>> getTeams() { return teams; }
        public void setTeams(Map<String, List<Address>> teams) { this.teams = teams; }
    }

    // ══════════════════════════════════════════════════════════════
    // String → List<Bean> with nested generics
    // ══════════════════════════════════════════════════════════════

    @Test
    public void stringToListOfBeansWithNestedListField() {
        String json = "{\"name\":\"Alice\",\"addresses\":[" +
                "{\"city\":\"Shanghai\",\"zip\":\"200000\"}," +
                "{\"city\":\"Beijing\",\"zip\":\"100000\"}]}";

        Person p = DingJson.parseObject(json, new TypeReference<Person>() {});
        Assert.assertEquals("Alice", p.getName());
        Assert.assertNotNull(p.getAddresses());
        Assert.assertEquals(2, p.getAddresses().size());
        // Each element must be a real Address bean, not a raw JSONObject
        Assert.assertTrue(p.getAddresses().get(0) instanceof Address);
        Assert.assertEquals("Shanghai", p.getAddresses().get(0).getCity());
        Assert.assertEquals("100000", p.getAddresses().get(1).getZip());
    }

    @Test
    public void stringToBeanWithMapOfBeans() {
        String json = "{\"name\":\"Bob\",\"contacts\":{" +
                "\"home\":{\"city\":\"Guangzhou\",\"zip\":\"510000\"}," +
                "\"work\":{\"city\":\"Shenzhen\",\"zip\":\"518000\"}}}";

        Person p = DingJson.parseObject(json, new TypeReference<Person>() {});
        Assert.assertNotNull(p.getContacts());
        Assert.assertEquals(2, p.getContacts().size());
        Assert.assertTrue(p.getContacts().get("home") instanceof Address);
        Assert.assertEquals("Guangzhou", p.getContacts().get("home").getCity());
        Assert.assertEquals("Shenzhen", p.getContacts().get("work").getCity());
    }

    @Test
    public void stringToListOfLists() {
        String json = "[[1,2,3],[4,5,6],[7,8,9]]";

        List<List<Integer>> matrix = DingJson.parseObject(json, new TypeReference<List<List<Integer>>>() {});
        Assert.assertEquals(3, matrix.size());
        Assert.assertEquals(Integer.valueOf(5), matrix.get(1).get(1));
        Assert.assertEquals(Integer.valueOf(9), matrix.get(2).get(2));

        // Alternative entry point: parseArray with element type List<Integer>
        List<List<Integer>> viaArray = DingJson.parseArray(json, new TypeReference<List<Integer>>() {});
        Assert.assertEquals(Integer.valueOf(3), viaArray.get(0).get(2));
    }

    @Test
    public void stringToBeanWithMatrixField() {
        String json = "{\"id\":7,\"matrix\":[[1.5,2.5],[3.5,4.5]],\"groups\":{\"a\":[\"x\",\"y\"],\"b\":[\"z\"]}}";

        Order order = DingJson.parseObject(json, new TypeReference<Order>() {});
        Assert.assertEquals(7, order.getId());
        Assert.assertNotNull(order.getMatrix());
        Assert.assertEquals(Double.valueOf(4.5), order.getMatrix().get(1).get(1));
        Assert.assertEquals(Arrays.asList("x", "y"), order.getGroups().get("a"));
        Assert.assertEquals(Collections.singletonList("z"), order.getGroups().get("b"));
    }

    @Test
    public void stringToBeanWithMapOfListsOfBeans() {
        // Deepest nesting: Map<String, List<Address>>
        String json = "{\"teams\":{" +
                "\"teamA\":[{\"city\":\"A\",\"zip\":\"1\"},{\"city\":\"B\",\"zip\":\"2\"}]," +
                "\"teamB\":[]}}";

        Order order = DingJson.parseObject(json, new TypeReference<Order>() {});
        Assert.assertNotNull(order.getTeams());
        Assert.assertEquals(2, order.getTeams().size());
        Assert.assertTrue(order.getTeams().get("teamA").get(0) instanceof Address);
        Assert.assertEquals("A", order.getTeams().get("teamA").get(0).getCity());
        Assert.assertEquals("B", order.getTeams().get("teamA").get(1).getCity());
        Assert.assertTrue(order.getTeams().get("teamB").isEmpty());
    }

    @Test
    public void stringToComplexTypeRefWithNestedBeanAndList() {
        // List<Person> where Person itself has List<Address>
        String json = "[" +
                "{\"name\":\"A\",\"addresses\":[{\"city\":\"S\",\"zip\":\"1\"}]}," +
                "{\"name\":\"B\",\"addresses\":[{\"city\":\"B\",\"zip\":\"2\"},{\"city\":\"G\",\"zip\":\"3\"}]}" +
                "]";

        List<Person> people = DingJson.parseObject(json, new TypeReference<List<Person>>() {});
        Assert.assertEquals(2, people.size());
        Assert.assertEquals("A", people.get(0).getName());
        Assert.assertEquals("S", people.get(0).getAddresses().get(0).getCity());
        Assert.assertEquals(2, people.get(1).getAddresses().size());
        Assert.assertTrue(people.get(1).getAddresses().get(1) instanceof Address);
    }

    // ══════════════════════════════════════════════════════════════
    // Mixed / heterogeneous trees
    // ══════════════════════════════════════════════════════════════

    @Test
    public void stringToMixedList() {
        // Mixed types are fine when the target is Object
        String json = "[1,\"two\",true,null,{\"a\":1},[2,3]]";
        List<Object> list = DingJson.parseObject(json, new TypeReference<List<Object>>() {});
        Assert.assertEquals(6, list.size());
        Assert.assertEquals(Integer.valueOf(1), list.get(0));
        Assert.assertEquals("two", list.get(1));
        Assert.assertEquals(Boolean.TRUE, list.get(2));
        Assert.assertNull(list.get(3));
        Assert.assertTrue(list.get(4) instanceof JSONObject);
        Assert.assertTrue(list.get(5) instanceof JSONArray);
    }

    @Test
    public void stringToMapOfStringObject() {
        String json = "{\"a\":1,\"b\":\"two\",\"c\":[true,false]}";
        Map<String, Object> map = DingJson.parseObject(json, new TypeReference<Map<String, Object>>() {});
        Assert.assertEquals(Integer.valueOf(1), map.get("a"));
        Assert.assertEquals("two", map.get("b"));
        Assert.assertTrue(map.get("c") instanceof JSONArray);
    }

    @Test
    public void stringToMapOfListsOfBeans() {
        String json = "{\"admins\":[{\"name\":\"A\"}],\"users\":[{\"name\":\"B\"},{\"name\":\"C\"}]}";
        Map<String, List<Person>> map = DingJson.parseObject(json, new TypeReference<Map<String, List<Person>>>() {});
        Assert.assertEquals(1, map.get("admins").size());
        Assert.assertEquals("A", map.get("admins").get(0).getName());
        Assert.assertEquals(2, map.get("users").size());
        Assert.assertEquals("C", map.get("users").get(1).getName());
    }

    // ══════════════════════════════════════════════════════════════
    // Round trips
    // ══════════════════════════════════════════════════════════════

    @Test
    public void roundTripBeanWithNestedCollections() {
        Person p = new Person();
        p.setName("Round");
        Address a1 = new Address(); a1.setCity("S"); a1.setZip("1");
        Address a2 = new Address(); a2.setCity("B"); a2.setZip("2");
        p.setAddresses(Arrays.asList(a1, a2));
        Map<String, Address> contacts = new LinkedHashMap<>();
        contacts.put("home", a1);
        p.setContacts(contacts);

        String json = DingJson.toJSONString(p);
        Person restored = DingJson.parseObject(json, new TypeReference<Person>() {});
        Assert.assertEquals("Round", restored.getName());
        Assert.assertEquals(2, restored.getAddresses().size());
        Assert.assertEquals("B", restored.getAddresses().get(1).getCity());
        Assert.assertEquals("S", restored.getContacts().get("home").getCity());
    }

    @Test
    public void roundTripDeepGenerics() {
        List<List<Map<String, List<Integer>>>> data = new ArrayList<>();
        Map<String, List<Integer>> inner = new LinkedHashMap<>();
        inner.put("scores", Arrays.asList(1, 2, 3));
        data.add(Collections.singletonList(inner));

        String json = DingJson.toJSONString(data);
        List<List<Map<String, List<Integer>>>> restored =
                DingJson.parseObject(json, new TypeReference<List<List<Map<String, List<Integer>>>>>() {});
        Assert.assertEquals(Integer.valueOf(1), restored.get(0).get(0).get("scores").get(0));
        Assert.assertEquals(Integer.valueOf(3), restored.get(0).get(0).get("scores").get(2));
    }

    // ══════════════════════════════════════════════════════════════
    // Null and edge handling inside generics
    // ══════════════════════════════════════════════════════════════

    @Test
    public void nullElementsInsideList() {
        String json = "[1,null,3]";
        List<Integer> list = DingJson.parseObject(json, new TypeReference<List<Integer>>() {});
        Assert.assertEquals(Integer.valueOf(1), list.get(0));
        Assert.assertNull(list.get(1));
        Assert.assertEquals(Integer.valueOf(3), list.get(2));
    }

    @Test
    public void nullValuesInsideMap() {
        String json = "{\"a\":null,\"b\":1}";
        Map<String, Integer> map = DingJson.parseObject(json, new TypeReference<Map<String, Integer>>() {});
        Assert.assertNull(map.get("a"));
        Assert.assertEquals(Integer.valueOf(1), map.get("b"));
    }

    @Test
    public void emptyNestedCollections() {
        String json = "{\"matrix\":[],\"groups\":{}}";
        Order order = DingJson.parseObject(json, new TypeReference<Order>() {});
        Assert.assertNotNull(order.getMatrix());
        Assert.assertTrue(order.getMatrix().isEmpty());
        Assert.assertNotNull(order.getGroups());
        Assert.assertTrue(order.getGroups().isEmpty());
    }

    @Test
    public void parseArrayWithTypeRefOfBean() {
        String json = "[{\"name\":\"A\"},{\"name\":\"B\"}]";
        List<Person> people = DingJson.parseArray(json, new TypeReference<Person>() {});
        Assert.assertEquals(2, people.size());
        Assert.assertEquals("B", people.get(1).getName());
    }

    @Test
    public void stringToSimpleBeanViaTypeRef() {
        // TypeReference must also work for plain classes (no generics)
        String json = "{\"name\":\"Plain\",\"addresses\":null}";
        Person p = DingJson.parseObject(json, new TypeReference<Person>() {});
        Assert.assertEquals("Plain", p.getName());
    }

    // ══════════════════════════════════════════════════════════════
    // Number/type coercion inside generics
    // ══════════════════════════════════════════════════════════════

    @Test
    public void listElementsCoerceToLong() {
        // ints in JSON become Longs in the typed list
        List<Long> list = DingJson.parseObject("[1,2,3]", new TypeReference<List<Long>>() {});
        Assert.assertEquals(Long.valueOf(1L), list.get(0));
        Assert.assertEquals(Long.valueOf(3L), list.get(2));
    }

    @Test
    public void listElementsCoerceToDouble() {
        List<Double> list = DingJson.parseObject("[1,2.5]", new TypeReference<List<Double>>() {});
        Assert.assertEquals(Double.valueOf(1.0), list.get(0));
        Assert.assertEquals(Double.valueOf(2.5), list.get(1));
    }

    @Test
    public void listElementsCoerceToString() {
        List<String> list = DingJson.parseObject("[1,\"two\",true]", new TypeReference<List<String>>() {});
        Assert.assertEquals("1", list.get(0));
        Assert.assertEquals("two", list.get(1));
        Assert.assertEquals("true", list.get(2));
    }

    @Test
    public void tripleNestedList() {
        String json = "[[[1],[2]],[[3],[4]]]";
        List<List<List<Integer>>> cube =
                DingJson.parseObject(json, new TypeReference<List<List<List<Integer>>>>() {});
        Assert.assertEquals(Integer.valueOf(1), cube.get(0).get(0).get(0));
        Assert.assertEquals(Integer.valueOf(4), cube.get(1).get(1).get(0));
    }

    @Test
    public void mapValuesCoerceToInt() {
        Map<String, Integer> map =
                DingJson.parseObject("{\"a\":\"42\",\"b\":2.9}", new TypeReference<Map<String, Integer>>() {});
        Assert.assertEquals(Integer.valueOf(42), map.get("a"));
        Assert.assertEquals(Integer.valueOf(2), map.get("b"));
    }

    // ══════════════════════════════════════════════════════════════
    // TypeReference misuse
    // ══════════════════════════════════════════════════════════════

    @SuppressWarnings("rawtypes")
    @Test(expected = com.dingjson.exception.JSONException.class)
    public void bareTypeReferenceIsRejected() {
        // Raw TypeReference (no type argument at all) cannot capture a type
        new TypeReference() {
        };
    }

    // ══════════════════════════════════════════════════════════════
    // Serialization of complex trees must stay lossless
    // ══════════════════════════════════════════════════════════════

    @Test
    public void serializeNestedBeanTree() {
        Person p = new Person();
        p.setName("Tree");
        Address a = new Address(); a.setCity("X"); a.setZip("9");
        p.setAddresses(Collections.singletonList(a));
        p.setContacts(Collections.singletonMap("k", a));

        String json = DingJson.toJSONString(p);
        Assert.assertTrue(json.contains("\"city\":\"X\""));
        Assert.assertTrue(json.contains("\"contacts\":{\"k\":{"));
    }
}
