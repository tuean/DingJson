package com.dingjson;

import com.dingjson.element.JSONArray;
import com.dingjson.element.JSONObject;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark comparing DingJson vs fastjson1, fastjson2, Jackson, Gson, org.json.
 * <p>
 * Measures: wall-clock parse/serialize throughput, CPU time, and retained heap
 * per parse. Run with:
 * <pre>{@code
 * mvn test-compile
 * mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
 * java -cp "target/test-classes:target/classes:$(cat cp.txt)" com.dingjson.Benchmark
 * }</pre>
 */
public class Benchmark {

    private static final ThreadMXBean THREAD = ManagementFactory.getThreadMXBean();

    // ── Sample data ───────────────────────────────────────────────

    /** Bean used by the bean-binding benchmarks (public fields, no-arg ctor). */
    public static class BenchBean {
        public String name;
        public int age;
        public boolean active;
        public double rating;
        public java.util.List<String> tags;
        public Inner inner;
    }

    public static class Inner {
        public int x;
        public String y;
    }

    /** A single nested bean document (mirrors the tree document's shape). */
    static String buildBeanJson() {
        return "{\"name\":\"Ding\",\"age\":3,\"active\":true,\"rating\":4.75,"
                + "\"tags\":[\"json\",\"java\",\"fast\"],"
                + "\"inner\":{\"x\":42,\"y\":\"deep\"}}";
    }

    /** An array of 100 beans (repeated document) for list-binding throughput. */
    static String buildBeanArrayJson() {
        String one = buildBeanJson();
        StringBuilder sb = new StringBuilder(one.length() * 100 + 2);
        sb.append('[');
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(',');
            sb.append(one);
        }
        sb.append(']');
        return sb.toString();
    }

    /** A realistic complex JSON document shared by every library. */
    static String buildComplexJson() {
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

    /** Large flat array (100k integers) for memory/throughput scaling. */
    static String buildLargeArrayJson(int n) {
        StringBuilder sb = new StringBuilder(n * 6);
        sb.append('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(i);
        }
        sb.append(']');
        return sb.toString();
    }

    // ── Timing harness ────────────────────────────────────────────

    interface BenchAction {
        Object run(); // returns a value that must not be optimized away
    }

    static final class Result {
        final String name;
        final String op;
        final double msPerOp;       // wall clock
        final double cpuMsPerOp;    // CPU time
        final long bytesPerOp;      // retained heap (0 = not measured)

        Result(String name, String op, double msPerOp, double cpuMsPerOp, long bytesPerOp) {
            this.name = name; this.op = op; this.msPerOp = msPerOp;
            this.cpuMsPerOp = cpuMsPerOp; this.bytesPerOp = bytesPerOp;
        }

        double opsPerSec() {
            return msPerOp > 0 ? 1000.0 / msPerOp : 0;
        }
    }

    private static Result measure(String name, String op, int warmup, int iterations, BenchAction action) {
        Object sink = null;
        for (int i = 0; i < warmup; i++) {
            sink = action.run();
        }
        if (sink == null) throw new IllegalStateException("benchmark sink");

        long wallStart = System.nanoTime();
        long cpuStart = THREAD.isCurrentThreadCpuTimeSupported() ? THREAD.getCurrentThreadCpuTime() : 0;
        for (int i = 0; i < iterations; i++) {
            sink = action.run();
        }
        long wallEnd = System.nanoTime();
        long cpuEnd = THREAD.isCurrentThreadCpuTimeSupported() ? THREAD.getCurrentThreadCpuTime() : 0;
        // keep sink alive to defeat dead-code elimination
        if (System.nanoTime() == 0L) System.out.println(sink);

        double wallMs = (wallEnd - wallStart) / 1_000_000.0 / iterations;
        double cpuMs = THREAD.isCurrentThreadCpuTimeSupported()
                ? (cpuEnd - cpuStart) / 1_000_000.0 / iterations : 0;
        return new Result(name, op, wallMs, cpuMs, 0);
    }

    // ── Memory harness ────────────────────────────────────────────

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * Measures average retained heap per call: GC, baseline, run N allocations,
     * GC again, diff / N. Multiple rounds, keeps the minimum (GC noise is only
     * ever over-reported, so the min is the least-noisy estimate).
     */
    private static long measureHeapPerOp(int n, BenchAction action) {
        long best = Long.MAX_VALUE;
        List<Object> keepAlive = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            // pre-warm so static caches are already allocated
            for (int i = 0; i < 2000; i++) action.run();
            keepAlive.clear();
            System.gc();
            System.gc();
            long before = usedHeap();
            for (int i = 0; i < n; i++) {
                keepAlive.add(action.run()); // keep every object alive
            }
            System.gc();
            System.gc();
            long after = usedHeap();
            long perOp = (after - before) / n;
            if (perOp < best) best = perOp;
        }
        return best;
    }

    // ── Per-library adapters ──────────────────────────────────────

    interface ParseFn { Object parse(String s) throws Exception; }
    interface SerializeFn { String serialize(Object tree) throws Exception; }

    // Reused instances: production code never re-creates mappers per call
    private static final com.fasterxml.jackson.databind.ObjectMapper JACKSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private static Result benchParse(String name, String json, int warmup, int iters, ParseFn fn) throws Exception {
        return benchParseOp(name, "parse", json, warmup, iters, fn);
    }

    private static Result benchParseOp(String name, String op, String json, int warmup, int iters, ParseFn fn) throws Exception {
        return measure(name, op, warmup, iters, () -> {
            try { return fn.parse(json); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static Result benchSerialize(String name, Object tree, int warmup, int iters, SerializeFn fn) throws Exception {
        return benchSerializeOp(name, "serialize", tree, warmup, iters, fn);
    }

    private static Result benchSerializeOp(String name, String op, Object tree, int warmup, int iters, SerializeFn fn) throws Exception {
        return measure(name, op, warmup, iters, () -> {
            try { return fn.serialize(tree); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    // ── Report ────────────────────────────────────────────────────

    private static void printHeader() {
        System.out.printf("%n%-12s %-9s %14s %12s %10s %12s%n", "Library", "Operation", "ms/op", "ops/sec", "cpu ms", "bytes/op");
        System.out.println("------------ ---------- -------------- ------------ ---------- ------------");
    }

    private static void printRow(Result r) {
        String bytes = r.bytesPerOp > 0 ? String.format("%,d", r.bytesPerOp) : "-";
        System.out.printf("%-12s %-9s %14.4f %12.0f %10.3f %12s%n",
                r.name, r.op, r.msPerOp, r.opsPerSec(), r.cpuMsPerOp, bytes);
    }

    // ── Main ──────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        final String json = buildComplexJson();
        final String largeJson = buildLargeArrayJson(100_000);
        final int WARMUP = 10_000;
        final int ITERS = 50_000;

        System.out.println("== DingJson vs fastjson1 / fastjson2 / Jackson / Gson / org.json ==");
        System.out.println("JDK: " + System.getProperty("java.version")
                + " | heap: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
        System.out.println("Input: complex document (" + json.length() + " chars) + 100k-int array ("
                + largeJson.length() + " chars)");

        List<Result> results = new ArrayList<>();

        // ── Parse complex document ────────────────────────────────
        results.add(benchParse("DingJson", json, WARMUP, ITERS,
                s -> com.dingjson.DingJson.parse(s)));
        results.add(benchParse("fastjson1", json, WARMUP, ITERS,
                s -> com.alibaba.fastjson.JSON.parse(s)));
        results.add(benchParse("fastjson2", json, WARMUP, ITERS,
                s -> com.alibaba.fastjson2.JSON.parse(s)));
        results.add(benchParse("Jackson", json, WARMUP, ITERS,
                s -> JACKSON.readTree(s)));
        results.add(benchParse("Gson", json, WARMUP, ITERS,
                s -> com.google.gson.JsonParser.parseString(s)));
        results.add(benchParse("org.json", json, WARMUP, ITERS,
                s -> new org.json.JSONObject(s)));

        // ── Serialize complex document ────────────────────────────
        Object dingTree = com.dingjson.DingJson.parse(json);
        Object fj1Tree = com.alibaba.fastjson.JSON.parse(json);
        Object fj2Tree = com.alibaba.fastjson2.JSON.parse(json);
        Object jacksonTree = JACKSON.readTree(json);
        Object gsonTree = com.google.gson.JsonParser.parseString(json);
        Object orgJsonTree = new org.json.JSONObject(json);

        results.add(benchSerialize("DingJson", dingTree, WARMUP, ITERS,
                t -> com.dingjson.DingJson.toJSONString(t)));
        results.add(benchSerialize("fastjson1", fj1Tree, WARMUP, ITERS,
                t -> com.alibaba.fastjson.JSON.toJSONString(t)));
        results.add(benchSerialize("fastjson2", fj2Tree, WARMUP, ITERS,
                t -> com.alibaba.fastjson2.JSON.toJSONString(t)));
        results.add(benchSerialize("Jackson", jacksonTree, WARMUP, ITERS,
                t -> JACKSON.writeValueAsString(t)));
        results.add(benchSerialize("Gson", gsonTree, WARMUP, ITERS,
                t -> GSON.toJson(t)));
        results.add(benchSerialize("org.json", orgJsonTree, WARMUP, ITERS,
                t -> t.toString()));

        // ── Parse large array + heap measurement ──────────────────
        System.out.println();
        System.out.println("== Large array (100k ints): throughput ==");
        results.add(benchParseOp("DingJson", "parse-100k", largeJson, 2_000, 5_000,
                s -> com.dingjson.DingJson.parseArray(s)));
        results.add(benchParseOp("fastjson1", "parse-100k", largeJson, 2_000, 5_000,
                s -> com.alibaba.fastjson.JSON.parseArray(s)));
        results.add(benchParseOp("fastjson2", "parse-100k", largeJson, 2_000, 5_000,
                s -> com.alibaba.fastjson2.JSON.parseArray(s)));
        results.add(benchParseOp("Jackson", "parse-100k", largeJson, 2_000, 5_000,
                s -> JACKSON.readTree(s)));
        results.add(benchParseOp("Gson", "parse-100k", largeJson, 2_000, 5_000,
                s -> com.google.gson.JsonParser.parseString(s)));
        results.add(benchParseOp("org.json", "parse-100k", largeJson, 2_000, 5_000,
                s -> new org.json.JSONArray(s)));

        // ── Streaming typed array (zero boxing) ───────────────────
        System.out.println();
        System.out.println("== 100k-int array via streaming readIntArray (zero boxing) ==");
        results.add(benchParseOp("DingJson", "readIntArray", largeJson, 2_000, 5_000,
                s -> com.dingjson.DingJson.createReader(s).readIntArray()));

        // ── Bean binding (parseObject / toJSONString on a bean) ────
        final String beanJson = buildBeanJson();
        final String beanArrayJson = buildBeanArrayJson();
        System.out.println();
        System.out.println("== Bean binding: parse single bean / parse 100 beans / serialize bean ==");
        results.add(benchParseOp("DingJson", "bean-parse", beanJson, 10_000, 50_000,
                s -> com.dingjson.DingJson.parseObject(s, BenchBean.class)));
        results.add(benchParseOp("fastjson1", "bean-parse", beanJson, 10_000, 50_000,
                s -> com.alibaba.fastjson.JSON.parseObject(s, BenchBean.class)));
        results.add(benchParseOp("fastjson2", "bean-parse", beanJson, 10_000, 50_000,
                s -> com.alibaba.fastjson2.JSON.parseObject(s, BenchBean.class)));
        results.add(benchParseOp("Jackson", "bean-parse", beanJson, 10_000, 50_000,
                s -> JACKSON.readValue(s, BenchBean.class)));
        results.add(benchParseOp("Gson", "bean-parse", beanJson, 10_000, 50_000,
                s -> GSON.fromJson(s, BenchBean.class)));

        results.add(benchParseOp("DingJson", "bean-list", beanArrayJson, 2_000, 10_000,
                s -> com.dingjson.DingJson.parseArray(s, BenchBean.class)));
        results.add(benchParseOp("fastjson1", "bean-list", beanArrayJson, 2_000, 10_000,
                s -> com.alibaba.fastjson.JSON.parseArray(s, BenchBean.class)));
        results.add(benchParseOp("fastjson2", "bean-list", beanArrayJson, 2_000, 10_000,
                s -> com.alibaba.fastjson2.JSON.parseArray(s, BenchBean.class)));
        results.add(benchParseOp("Jackson", "bean-list", beanArrayJson, 2_000, 10_000,
                s -> JACKSON.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<BenchBean>>() {})));
        results.add(benchParseOp("Gson", "bean-list", beanArrayJson, 2_000, 10_000,
                s -> GSON.fromJson(s, new com.google.gson.reflect.TypeToken<java.util.List<BenchBean>>() {}.getType())));

        BenchBean benchBean = new BenchBean();
        benchBean.name = "Ding"; benchBean.age = 3; benchBean.active = true; benchBean.rating = 4.75;
        benchBean.tags = java.util.Arrays.asList("json", "java", "fast");
        Inner inner = new Inner(); inner.x = 42; inner.y = "deep";
        benchBean.inner = inner;
        results.add(benchSerializeOp("DingJson", "bean-serialize", benchBean, 10_000, 50_000,
                t -> com.dingjson.DingJson.toJSONString(t)));
        results.add(benchSerializeOp("fastjson1", "bean-serialize", benchBean, 10_000, 50_000,
                t -> com.alibaba.fastjson.JSON.toJSONString(t)));
        results.add(benchSerializeOp("fastjson2", "bean-serialize", benchBean, 10_000, 50_000,
                t -> com.alibaba.fastjson2.JSON.toJSONString(t)));
        results.add(benchSerializeOp("Jackson", "bean-serialize", benchBean, 10_000, 50_000,
                t -> JACKSON.writeValueAsString(t)));
        results.add(benchSerializeOp("Gson", "bean-serialize", benchBean, 10_000, 50_000,
                t -> GSON.toJson(t)));

        // ── Retained heap per parse ───────────────────────────────
        System.out.println();
        System.out.println("== Retained heap per parse (complex doc, smaller = better) ==");
        results.add(heapResult("DingJson", measureHeapPerOp(50_000,
                () -> com.dingjson.DingJson.parse(json))));
        results.add(heapResult("fastjson1", measureHeapPerOp(50_000,
                () -> com.alibaba.fastjson.JSON.parse(json))));
        results.add(heapResult("fastjson2", measureHeapPerOp(50_000,
                () -> com.alibaba.fastjson2.JSON.parse(json))));
        results.add(heapResult("Jackson", measureHeapPerOp(50_000,
                () -> {
                    try {
                        return JACKSON.readTree(json);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })));
        results.add(heapResult("Gson", measureHeapPerOp(50_000,
                () -> com.google.gson.JsonParser.parseString(json))));
        results.add(heapResult("org.json", measureHeapPerOp(50_000,
                () -> new org.json.JSONObject(json))));

        // Retained heap per parse of the 100k-int array (allocations scale with element count)
        System.out.println();
        System.out.println("== Retained heap per parse (100k-int array, smaller = better) ==");
        results.add(heapResult("DingJson-100k", measureHeapPerOp(20,
                () -> com.dingjson.DingJson.parseArray(largeJson))));
        results.add(heapResult("fastjson1-100k", measureHeapPerOp(20,
                () -> com.alibaba.fastjson.JSON.parseArray(largeJson))));
        results.add(heapResult("fastjson2-100k", measureHeapPerOp(20,
                () -> com.alibaba.fastjson2.JSON.parseArray(largeJson))));
        results.add(heapResult("Jackson-100k", measureHeapPerOp(20,
                () -> {
                    try {
                        return JACKSON.readTree(largeJson);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })));
        results.add(heapResult("Gson-100k", measureHeapPerOp(20,
                () -> com.google.gson.JsonParser.parseString(largeJson))));
        results.add(heapResult("org.json-100k", measureHeapPerOp(20,
                () -> new org.json.JSONArray(largeJson))));

        // ── Print everything grouped by operation ─────────────────
        String[] ops = {"parse", "serialize", "parse-100k", "readIntArray", "bean-parse", "bean-list", "bean-serialize"};
        System.out.println();
        for (String op : ops) {
            printHeader();
            for (Result r : results) {
                if (r.op.equals(op)) printRow(r);
            }
            System.out.println();
        }
        printHeader();
        for (Result r : results) {
            if (r.op.equals("heap")) printRow(r);
        }
    }

    private static Result heapResult(String name, long bytes) {
        return new Result(name, "heap", 0, 0, bytes);
    }
}
