package com.dingjson.serializer;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Access to {@link Unsafe} object-field operations, used to reach bean fields
 * at direct-access speed (~0.25 ns/op vs ~3.4 ns via reflection — parity with
 * a hand-written bytecode {@code GETFIELD}, which is what fastjson2 generates
 * with ASM).
 * <p>
 * Falls back gracefully: if {@code Unsafe} is unavailable (future JDKs may
 * restrict it), {@link #AVAILABLE} is {@code false} and callers keep using
 * plain reflection — behavior is identical either way.
 */
final class UnsafeFields {

    private static final Unsafe UNSAFE;
    static final boolean AVAILABLE;

    static {
        Unsafe u = null;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (Throwable ignored) {
            // Unsafe unavailable — callers fall back to reflection
        }
        UNSAFE = u;
        AVAILABLE = u != null;
    }

    private UnsafeFields() {
    }

    static long offsetOf(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }

    static int getInt(Object bean, long offset) {
        return UNSAFE.getInt(bean, offset);
    }

    static long getLong(Object bean, long offset) {
        return UNSAFE.getLong(bean, offset);
    }

    static double getDouble(Object bean, long offset) {
        return UNSAFE.getDouble(bean, offset);
    }

    static float getFloat(Object bean, long offset) {
        return UNSAFE.getFloat(bean, offset);
    }

    static boolean getBoolean(Object bean, long offset) {
        return UNSAFE.getBoolean(bean, offset);
    }

    static short getShort(Object bean, long offset) {
        return UNSAFE.getShort(bean, offset);
    }

    static byte getByte(Object bean, long offset) {
        return UNSAFE.getByte(bean, offset);
    }

    static char getChar(Object bean, long offset) {
        return UNSAFE.getChar(bean, offset);
    }

    static Object getObject(Object bean, long offset) {
        return UNSAFE.getObject(bean, offset);
    }

    static void putInt(Object bean, long offset, int value) {
        UNSAFE.putInt(bean, offset, value);
    }

    static void putLong(Object bean, long offset, long value) {
        UNSAFE.putLong(bean, offset, value);
    }

    static void putDouble(Object bean, long offset, double value) {
        UNSAFE.putDouble(bean, offset, value);
    }

    static void putFloat(Object bean, long offset, float value) {
        UNSAFE.putFloat(bean, offset, value);
    }

    static void putBoolean(Object bean, long offset, boolean value) {
        UNSAFE.putBoolean(bean, offset, value);
    }

    static void putShort(Object bean, long offset, short value) {
        UNSAFE.putShort(bean, offset, value);
    }

    static void putByte(Object bean, long offset, byte value) {
        UNSAFE.putByte(bean, offset, value);
    }

    static void putChar(Object bean, long offset, char value) {
        UNSAFE.putChar(bean, offset, value);
    }

    static void putObject(Object bean, long offset, Object value) {
        UNSAFE.putObject(bean, offset, value);
    }
}
