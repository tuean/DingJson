package com.dingjson.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures how a bean field is serialized / deserialized.
 * <p>
 * Usage:
 * <pre>{@code
 * public class User {
 *     @JSONField(name = "user_name")          // rename in JSON
 *     public String userName;
 *
 *     @JSONField(serialize = false)           // write-only field
 *     public String passwordHash;
 *
 *     @JSONField(deserialize = false)         // read-only field
 *     public long createdAt;
 *
 *     @JSONField(format = "yyyy-MM-dd HH:mm:ss")
 *     public Date updatedAt;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface JSONField {

    /**
     * The JSON property name (overrides the Java field name).
     */
    String name() default "";

    /**
     * Whether the field is included in JSON output. Default {@code true}.
     */
    boolean serialize() default true;

    /**
     * Whether the field is read from JSON input. Default {@code true}.
     */
    boolean deserialize() default true;

    /**
     * Date/time pattern applied to {@link java.util.Date} (and subclass)
     * fields, e.g. {@code "yyyy-MM-dd HH:mm:ss"}. When empty (default), dates
     * use epoch millis.
     */
    String format() default "";
}
