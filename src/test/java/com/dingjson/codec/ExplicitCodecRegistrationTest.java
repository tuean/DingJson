package com.dingjson.codec;

import com.dingjson.DingJson;
import com.dingjson.DingJsonConfig;
import com.dingjson.element.JSONObject;
import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Registering a codec by hand is the zero-reflection path: no
 * {@code Class.forName} at all. These tests pin the two properties that make it
 * worth having — the registered codec is really the one used, and a registration
 * that comes too late is rejected instead of silently ignored.
 */
public class ExplicitCodecRegistrationTest {

    /** Deliberately not annotated: without a registration this class uses reflection. */
    public static class Plain {
        public int a;
        public String b;
    }

    /** Same shape, never registered: shows the reflective default output. */
    public static class Raw {
        public int a;
        public String b;
    }

    /** Hand-written codec that is deliberately distinguishable from reflection. */
    private static final class UpperCaseCodec implements DingJsonCodec<Plain> {
        @Override
        public void write(Plain bean, StringBuilder out, int depth, DingJsonConfig config) {
            out.append("{\"A\":").append(bean.a).append(",\"B\":");
            if (bean.b == null) {
                out.append("null");
            } else {
                out.append('"').append(bean.b).append('"');
            }
            out.append('}');
        }

        @Override
        public Plain read(JSONObject src, DingJsonConfig config) {
            Plain bean = new Plain();
            bean.a = ((Number) src.get("A")).intValue();
            bean.b = src.getString("B");
            return bean;
        }
    }

    private static Plain sample() {
        Plain bean = new Plain();
        bean.a = 7;
        bean.b = "x";
        return bean;
    }

    @Test
    public void reflectivePathIsUsedWhenNothingIsRegistered() {
        Raw bean = new Raw();
        bean.a = 7;
        bean.b = "x";
        Assert.assertEquals("{\"a\":7,\"b\":\"x\"}", DingJson.toJSONString(bean));
    }

    @Test
    public void registeredCodecReplacesReflection() {
        DingJson.registerCodec(Plain.class, new UpperCaseCodec());
        Assert.assertEquals("{\"A\":7,\"B\":\"x\"}", DingJson.toJSONString(sample()));
    }

    @Test
    public void registrationAfterDiscoveryIsRejected() {
        // CodecBean is annotated, so the CodecGenerationTest suite already forced
        // name-based discovery for it in this JVM.
        Assert.assertNotNull(DingJson.toJSONString(new CodecBean()));
        try {
            DingJson.registerCodec(CodecBean.class, new DingJsonCodec<CodecBean>() {
                @Override
                public void write(CodecBean bean, StringBuilder out, int depth, DingJsonConfig config) {
                    out.append("{}");
                }

                @Override
                public CodecBean read(JSONObject src, DingJsonConfig config) {
                    return new CodecBean();
                }
            });
            Assert.fail("late registration must be rejected");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("before first use"));
        }
    }

    @Test
    public void nullCodecIsRejected() {
        try {
            DingJson.registerCodec(Plain.class, null);
            Assert.fail("null codec must be rejected");
        } catch (JSONException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("must not be null"));
        }
    }
}
