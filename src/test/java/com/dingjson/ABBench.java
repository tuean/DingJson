package com.dingjson;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process A/B benchmark: DingJson 1.0.0 and 1.0.1 are loaded side by side in
 * separate class loaders and measured round-robin in one JVM, interleaved with
 * the mainstream libraries.
 *
 * <p>Why: separate processes on a laptop swing 30-120% between runs (scheduler,
 * frequency scaling, background indexing) — far more than the effect being
 * measured. Round-robin inside one process keeps CPU/GC/JIT state identical for
 * every candidate, so the ratios stay meaningful even when absolute numbers drift.
 *
 * <p>用法：{@code ./bench.sh ab <另一版本的 classes 目录> [轮数]}
 */
public final class ABBench {

    public static class BenchBean {
        public String name;
        public int age;
        public boolean active;
        public double rating;
        public List<String> tags;
        public Inner inner;
    }

    public static class Inner {
        public int x;
        public String y;
    }

    static String beanJson() {
        return "{\"name\":\"Ding\",\"age\":3,\"active\":true,\"rating\":4.75,"
                + "\"tags\":[\"json\",\"java\",\"fast\"],"
                + "\"inner\":{\"x\":42,\"y\":\"deep\"}}";
    }

    static String beanArrayJson() {
        String one = beanJson();
        StringBuilder sb = new StringBuilder(one.length() * 100 + 2);
        sb.append('[');
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(',');
            sb.append(one);
        }
        sb.append(']');
        return sb.toString();
    }

    static String complexJson() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"id\":12345,\"name\":\"DingJson Benchmark\",\"active\":true,")
          .append("\"rating\":4.75,\"created\":\"2024-01-15T10:30:00Z\",\"internal\":null,")
          .append("\"tags\":[\"json\",\"benchmark\",\"java\",\"fast\",\"lightweight\"],")
          .append("\"counts\":[1,2,3,4,5,6,7,8,9,10],")
          .append("\"items\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i + 1)
              .append(",\"name\":\"item-").append(i + 1)
              .append("\",\"price\":").append(10.5 + i * 0.5)
              .append(",\"stock\":").append(100 + i)
              .append(",\"available\":").append(i % 2 == 0)
              .append(",\"sku\":\"SKU-").append(1000 + i).append("\"}");
        }
        sb.append("],\"scores\":[98.5,87.2,76.8,65.4,54.3,43.2,32.1,21.0,19.9,18.8]}");
        return sb.toString();
    }

    static String largeArrayJson(int n) {
        StringBuilder sb = new StringBuilder(n * 6);
        sb.append('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(i);
        }
        sb.append(']');
        return sb.toString();
    }

    // ── candidate abstraction ────────────────────────────────────

    interface Op {
        Object call() throws Throwable;
    }

    /** Builds the Op for one (library, operation) pair; {@code tree} is that library's own parse result. */
    interface Factory {
        Op make(String op, Object tree) throws Throwable;
    }

    /** Reflective handle over a DingJson version loaded in its own class loader. */
    static Factory dingJson(Class<?> dj) {
        return (op, tree) -> {
            MethodHandles.Lookup lk = MethodHandles.publicLookup();
            // unreflect (not findStatic) so the declared return type may differ
            // from the erased one — parseArray returns JSONArray/List, not Object.
            switch (op) {
                case "parse":
                    return h(lk.unreflect(dj.getMethod("parse", String.class)), json);
                case "serialize":
                case "bean-serialize":
                    return h(lk.unreflect(dj.getMethod("toJSONString", Object.class)),
                            op.equals("serialize") ? tree : bean);
                case "parse-100k":
                    return h(lk.unreflect(dj.getMethod("parseArray", String.class)), large);
                case "bean-parse":
                    return h2(lk.unreflect(dj.getMethod("parseObject", String.class, Class.class)),
                            bean, BenchBean.class);
                case "bean-list":
                    return h2(lk.unreflect(dj.getMethod("parseArray", String.class, Class.class)),
                            beanArray, BenchBean.class);
                default:
                    throw new IllegalArgumentException(op);
            }
        };
    }

    private static String json;
    private static String large;
    private static String bean;
    private static String beanArray;

    static Op h(MethodHandle raw, Object a) throws Throwable {
        final MethodHandle m = raw.asType(MethodType.methodType(Object.class, Object.class));
        return new Op() {
            @Override
            public Object call() throws Throwable {
                return (Object) m.invokeExact(a);
            }
        };
    }

    static Op h2(MethodHandle raw, Object a, Object b) throws Throwable {
        final MethodHandle m = raw.asType(MethodType.methodType(Object.class, Object.class, Object.class));
        return new Op() {
            @Override
            public Object call() throws Throwable {
                return (Object) m.invokeExact(a, b);
            }
        };
    }

    private static Object sink;

    public static void main(String[] args) throws Throwable {
        URL dj100 = new File(args[0]).toURI().toURL();
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        json = complexJson();
        large = largeArrayJson(100_000);
        bean = beanJson();
        beanArray = beanArrayJson();

        final com.fasterxml.jackson.databind.ObjectMapper jackson =
                new com.fasterxml.jackson.databind.ObjectMapper();
        final com.google.gson.Gson gson = new com.google.gson.Gson();

        Map<String, Factory> c = new LinkedHashMap<>();
        // One loader for the whole run: a loader per operation would load and
        // JIT another copy of the library each time and skew everything.
        Class<?> dj100Class = new URLClassLoader(new URL[]{dj100},
                ClassLoader.getSystemClassLoader().getParent())
                .loadClass("com.dingjson.DingJson");
        c.put("DingJson-1.0.0", dingJson(dj100Class));
        c.put("DingJson-1.0.1", (op, tree) -> {
            switch (op) {
                case "parse": return () -> com.dingjson.DingJson.parse(json);
                case "serialize": return () -> com.dingjson.DingJson.toJSONString(tree);
                case "parse-100k": return () -> com.dingjson.DingJson.parseArray(large);
                case "bean-parse": return () -> com.dingjson.DingJson.parseObject(bean, BenchBean.class);
                case "bean-serialize": return () -> com.dingjson.DingJson.toJSONString(bean);
                case "bean-list": return () -> com.dingjson.DingJson.parseArray(beanArray, BenchBean.class);
                default: throw new IllegalArgumentException(op);
            }
        });
        c.put("fastjson2", (op, tree) -> {
            switch (op) {
                case "parse": return () -> com.alibaba.fastjson2.JSON.parse(json);
                case "serialize": return () -> com.alibaba.fastjson2.JSON.toJSONString(tree);
                case "parse-100k": return () -> com.alibaba.fastjson2.JSON.parseArray(large);
                case "bean-parse": return () -> com.alibaba.fastjson2.JSON.parseObject(bean, BenchBean.class);
                case "bean-serialize": return () -> com.alibaba.fastjson2.JSON.toJSONString(bean);
                case "bean-list": return () -> com.alibaba.fastjson2.JSON.parseArray(beanArray, BenchBean.class);
                default: throw new IllegalArgumentException(op);
            }
        });
        c.put("fastjson1", (op, tree) -> {
            switch (op) {
                case "parse": return () -> com.alibaba.fastjson.JSON.parse(json);
                case "serialize": return () -> com.alibaba.fastjson.JSON.toJSONString(tree);
                case "parse-100k": return () -> com.alibaba.fastjson.JSON.parseArray(large);
                case "bean-parse": return () -> com.alibaba.fastjson.JSON.parseObject(bean, BenchBean.class);
                case "bean-serialize": return () -> com.alibaba.fastjson.JSON.toJSONString(bean);
                case "bean-list": return () -> com.alibaba.fastjson.JSON.parseArray(beanArray, BenchBean.class);
                default: throw new IllegalArgumentException(op);
            }
        });
        c.put("Jackson", (op, tree) -> {
            switch (op) {
                case "parse": return () -> jackson.readTree(json);
                case "serialize": return () -> jackson.writeValueAsString(tree);
                case "parse-100k": return () -> jackson.readTree(large);
                case "bean-parse": return () -> jackson.readValue(bean, BenchBean.class);
                case "bean-serialize": return () -> jackson.writeValueAsString(bean);
                case "bean-list": return () -> jackson.readValue(beanArray, BenchBean[].class);
                default: throw new IllegalArgumentException(op);
            }
        });
        c.put("Gson", (op, tree) -> {
            switch (op) {
                case "parse": return () -> com.google.gson.JsonParser.parseString(json);
                case "serialize": return () -> gson.toJson(tree);
                case "parse-100k": return () -> com.google.gson.JsonParser.parseString(large);
                case "bean-parse": return () -> gson.fromJson(bean, BenchBean.class);
                case "bean-serialize": return () -> gson.toJson(bean);
                case "bean-list": return () -> gson.fromJson(beanArray, BenchBean[].class);
                default: throw new IllegalArgumentException(op);
            }
        });
        c.put("org.json", (op, tree) -> {
            switch (op) {
                case "parse": return () -> new org.json.JSONObject(json);
                case "serialize": return () -> tree.toString();
                case "parse-100k": return () -> new org.json.JSONArray(large);
                default: throw new IllegalArgumentException(op);
            }
        });

        // Each candidate parses once so the serialize ops use its own tree type.
        Map<String, Object> trees = new LinkedHashMap<>();
        for (Map.Entry<String, Factory> e : c.entrySet()) {
            trees.put(e.getKey(), e.getValue().make("parse", null).call());
        }

        String[] ops = {"parse", "serialize", "parse-100k", "bean-parse", "bean-serialize", "bean-list"};
        if (args.length > 2) {
            ops = args[2].split(",");
        }
        System.out.println("JDK " + System.getProperty("java.version")
                + " | rounds=" + rounds + " | 1.0.0=" + args[0]);

        for (String op : ops) {
            Map<String, Op> ready = new LinkedHashMap<>();
            for (Map.Entry<String, Factory> e : c.entrySet()) {
                try {
                    ready.put(e.getKey(), e.getValue().make(op, trees.get(e.getKey())));
                } catch (Throwable ignored) {
                    // library does not implement this op
                }
            }
            int iters = op.equals("parse-100k") ? 40 : 3000;
            int warm = op.equals("parse-100k") ? 15 : 1200;

            for (Op o : ready.values()) {
                for (int i = 0; i < warm; i++) {
                    sink = o.call();
                }
            }
            Map<String, double[]> samples = new LinkedHashMap<>();
            for (String n : ready.keySet()) {
                samples.put(n, new double[rounds]);
            }
            for (int r = 0; r < rounds; r++) {
                for (Map.Entry<String, Op> e : ready.entrySet()) {
                    Op o = e.getValue();
                    long t0 = System.nanoTime();
                    for (int i = 0; i < iters; i++) {
                        sink = o.call();
                    }
                    samples.get(e.getKey())[r] = (System.nanoTime() - t0) / (double) iters;
                }
            }
            for (Map.Entry<String, double[]> e : samples.entrySet()) {
                double[] v = e.getValue();
                Arrays.sort(v);
                System.out.printf("%s\t%s\t%.1f%n", op, e.getKey(), v[v.length / 2]);
            }
        }
        if (sink == null && System.nanoTime() == 0L) System.out.print("");
    }
}
