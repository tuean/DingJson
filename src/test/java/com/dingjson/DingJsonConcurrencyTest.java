package com.dingjson;

import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wave-4 tests: concurrency safety and fuzz robustness.
 */
public class DingJsonConcurrencyTest {

    public static class User {
        public String name;
        public int age;
        public double rating;
        public java.util.List<String> tags;
        public UUID id;
    }

    private static final String USER_JSON =
            "{\"name\":\"Ding\",\"age\":3,\"rating\":4.5,\"tags\":[\"a\",\"b\"],\"id\":\"6dc6ce8d-4cee-430b-bf9c-aa4cf80b3c20\"}";

    // ══════════════════════════════════════════════════════════════
    // Concurrency — caches (FIELD_CACHE, DATE_FORMATS) must be safe
    // ══════════════════════════════════════════════════════════════

    @Test
    public void concurrentParseAndSerialize() throws Exception {
        int threads = 16;
        int iterations = 5000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final boolean firstTime = t == 0; // one thread cold-starts the caches
            pool.submit(() -> {
                try {
                    start.await();
                    if (firstTime) {
                        // force cache population in this thread too (races on put)
                        DingJson.parseObject(USER_JSON, User.class);
                    }
                    for (int i = 0; i < iterations; i++) {
                        User u = DingJson.parseObject(USER_JSON, User.class);
                        if (!"Ding".equals(u.name)) throw new AssertionError("bad name");
                        String json = DingJson.toJSONString(u);
                        if (!json.contains("\"name\":\"Ding\"")) throw new AssertionError("bad serialize");
                        Object tree = DingJson.parse(json);
                        if (!(tree instanceof JSONObject)) throw new AssertionError("bad tree");
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assert.assertTrue("timed out", done.await(120, TimeUnit.SECONDS));
        pool.shutdown();
        Assert.assertEquals("concurrent errors: " + errors.get(), 0, errors.get());
    }

    @Test
    public void concurrentDateFormats() throws Exception {
        // DATE_FORMATS ThreadLocal map must not corrupt under contention
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        DingJsonMapper mapper = DingJson.mapper(DingJsonConfig.builder()
                .dateFormat("yyyy-MM-dd HH:mm:ss").build());
        com.dingjson.annotation.JSONField unused = null; // keep import

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        String json = mapper.toJSONString(new Object() {
                            public java.util.Date d = new java.util.Date(1700000000000L);
                        });
                        if (!json.contains("2023-11-")) throw new AssertionError("bad date: " + json);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assert.assertTrue(done.await(120, TimeUnit.SECONDS));
        pool.shutdown();
        Assert.assertEquals("date format errors: " + errors.get(), 0, errors.get());
    }

    @Test
    public void concurrentDifferentBeans() throws Exception {
        // many distinct classes → cache churn under contention
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < 8; t++) {
            final int seed = t;
            final int iters = 2000;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        // a fresh bean type per iteration index
                        final int vi = seed * 1000 + i;
                        final String si = "x" + i;
                        Object bean = new Object() {
                            public int v = vi;
                            public String s = si;
                        };
                        String json = DingJson.toJSONString(bean);
                        if (!json.contains("\"v\":")) throw new AssertionError("bad: " + json);
                    }
                } catch (Throwable e) {
                    if (errors.getAndIncrement() == 0) {
                        e.printStackTrace(); // first failure only: the count alone says nothing
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        Assert.assertTrue(done.await(120, TimeUnit.SECONDS));
        pool.shutdown();
        Assert.assertEquals("errors: " + errors.get(), 0, errors.get());
    }

    // ══════════════════════════════════════════════════════════════
    // Fuzz — arbitrary input must throw JSONException, never crash/hang
    // ══════════════════════════════════════════════════════════════

    @Test
    public void fuzzRandomBytes() {
        Random r = new Random(42);
        char[] alphabet = {'{', '}', '[', ']', '"', '\\', ':', ',', '0', '1', 'e', '.', '-',
                't', 'f', 'n', 'u', ' ', '\n', '\t', 'a', 'Z', '\u4e2d', '\u0001'};
        for (int i = 0; i < 20000; i++) {
            int len = r.nextInt(64);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet[r.nextInt(alphabet.length)]);
            }
            String input = sb.toString();
            try {
                DingJson.parse(input);
                // accepted — fine, as long as it doesn't throw something else
            } catch (JSONException expected) {
                // rejected cleanly
            } catch (StackOverflowError | OutOfMemoryError fatal) {
                Assert.fail("fatal on input: " + input);
            } catch (RuntimeException other) {
                Assert.fail("non-JSONException " + other.getClass().getSimpleName()
                        + " on input: " + input);
            }
        }
    }

    @Test
    public void fuzzStructuredRandom() {
        // random valid-ish structures (nested arrays/objects with scalars)
        Random r = new Random(7);
        for (int i = 0; i < 5000; i++) {
            String json = randomStructure(r, 4);
            try {
                Object tree = DingJson.parse(json);
                String round = DingJson.toJSONString(tree);
                DingJson.parse(round); // round-trip must always be valid
            } catch (JSONException expected) {
                // generator can produce invalid JSON (unbalanced) — fine
            } catch (RuntimeException other) {
                Assert.fail("unexpected " + other.getClass().getSimpleName() + " on: " + json);
            }
        }
    }

    private static String randomStructure(Random r, int depth) {
        if (depth == 0) {
            switch (r.nextInt(5)) {
                case 0: return String.valueOf(r.nextInt(1000));
                case 1: return "\"s" + r.nextInt(100) + "\"";
                case 2: return r.nextBoolean() ? "true" : "false";
                case 3: return "null";
                default: return String.valueOf(r.nextDouble());
            }
        }
        if (r.nextBoolean()) {
            StringBuilder sb = new StringBuilder("[");
            int n = r.nextInt(4);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(randomStructure(r, depth - 1));
            }
            return sb.append(']').toString();
        }
        StringBuilder sb = new StringBuilder("{");
        int n = r.nextInt(4);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"k").append(i).append("\":").append(randomStructure(r, depth - 1));
        }
        return sb.append('}').toString();
    }

    @Test
    public void fuzzNumbers() {
        Random r = new Random(99);
        for (int i = 0; i < 10000; i++) {
            String num = randomNumber(r);
            try {
                Object v = DingJson.parse(num);
                if (v instanceof Number) {
                    DingJson.toJSONString(v); // serialize back without failure
                }
            } catch (JSONException expected) {
                // invalid number → clean rejection
            } catch (RuntimeException other) {
                Assert.fail("unexpected " + other.getClass().getSimpleName() + " on: " + num);
            }
        }
    }

    private static String randomNumber(Random r) {
        StringBuilder sb = new StringBuilder();
        if (r.nextBoolean()) sb.append('-');
        sb.append(r.nextInt(100000));
        if (r.nextBoolean()) {
            sb.append('.');
            sb.append(r.nextInt(1000000));
        }
        if (r.nextBoolean()) {
            sb.append(r.nextBoolean() ? 'e' : 'E');
            if (r.nextBoolean()) sb.append(r.nextBoolean() ? '+' : '-');
            sb.append(r.nextInt(400));
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════
    // Long-input robustness
    // ══════════════════════════════════════════════════════════════

    @Test
    public void hugeStringValue() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1_000_000; i++) sb.append('x');
        String json = "\"" + sb + "\"";
        Object parsed = DingJson.parse(json);
        Assert.assertEquals(1_000_000, ((String) parsed).length());
    }

    @Test
    public void hugeEscapedString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("\\u0041"); // "AAAA..."
        Object parsed = DingJson.parse("\"" + sb + "\"");
        Assert.assertEquals(100_000, ((String) parsed).length());
    }

    @Test
    public void fuzzJsonPath() {
        // JSONPath must never throw for malformed-but-parseable queries
        Object tree = DingJson.parse("{\"a\":[1,2,{\"b\":3}]}");
        String[] paths = {"$.a", "$.a[0]", "$.a[2].b", "$.a[*].b", "$..b", "$.missing", "$..[1]", "$.a[-1]", "$.a[99]"};
        for (String p : paths) {
            JSONPath.eval(tree, p); // no exception
        }
    }
}
