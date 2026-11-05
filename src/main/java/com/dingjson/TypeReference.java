package com.dingjson;

import com.dingjson.exception.JSONException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures a generic type at compile time via an anonymous subclass.
 * <p>
 * Usage mirrors Gson's {@code TypeToken}:
 * <pre>{@code
 * List<Person> people = DingJson.parseObject(json, new TypeReference<List<Person>>() {});
 * Map<String, Address> contacts = DingJson.parseObject(json, new TypeReference<Map<String, Address>>() {});
 * }</pre>
 * <p>
 * The subclass must supply a concrete parameterized supertype — a bare
 * {@code new TypeReference<>() {}} is not usable.
 *
 * @param <T> the target type
 */
public abstract class TypeReference<T> {

    private final Type type;

    protected TypeReference() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType)) {
            throw new JSONException("TypeReference must be created with a concrete generic type, "
                    + "e.g. new TypeReference<List<String>>() {}");
        }
        this.type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
    }

    /**
     * Returns the captured {@link Type}.
     */
    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "TypeReference<" + type + ">";
    }
}
