package com.dingjson.codec;

import com.dingjson.DingJsonConfig;
import com.dingjson.element.JSONObject;

/**
 * Direct codec for a bean class, generated at compile time by
 * {@code DingJsonCodecProcessor} for {@link com.dingjson.annotation.DingJsonSerializable}
 * classes.
 * <p>
 * A generated codec is discovered automatically at runtime: no registration,
 * no configuration. When one exists for a class, {@code DingJson} uses it
 * instead of the reflective field path, so field access is plain {@code GETFIELD}
 * / {@code PUTFIELD} in a straight line — the same shape a bytecode generator
 * would produce, but as ordinary Java source (readable, debuggable, verified by
 * javac, and no dependency on a bytecode library).
 *
 * @param <T> the bean type
 */
public interface DingJsonCodec<T> {

    /**
     * Writes {@code bean} as a JSON object into {@code out}. Must append the
     * surrounding braces itself and leave no trailing comma.
     *
     * @param depth current nesting depth, for the circular-reference guard
     */
    void write(T bean, StringBuilder out, int depth, DingJsonConfig config);

    /**
     * Creates a bean from an already parsed object. Returns the new instance.
     */
    T read(JSONObject source, DingJsonConfig config);
}
