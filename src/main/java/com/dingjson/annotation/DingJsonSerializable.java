package com.dingjson.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asks DingJson's annotation processor to generate a direct codec for the
 * annotated class at compile time.
 * <p>
 * The generated class is {@code <BinaryName>$$DingJsonCodec}, a normal Java
 * source file compiled alongside the bean. DingJson finds it automatically at
 * runtime (via {@code Class.forName}, cached per class) and uses it instead of
 * the reflective field path; if the processor did not run, or declined to
 * generate (see the compiler note it prints), everything keeps working through
 * reflection — annotation processing is a pure speed-up, never a requirement.
 *
 * <pre>{@code
 * @DingJsonSerializable
 * public class Order {
 *     public int id;
 *     public List<Item> items;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface DingJsonSerializable {
}
