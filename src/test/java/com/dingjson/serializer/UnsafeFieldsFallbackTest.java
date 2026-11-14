package com.dingjson.serializer;

import org.junit.Assert;
import org.junit.Test;

/**
 * Guards the surefire {@code reflection-fallback} execution: if the system
 * property stops reaching the forked JVM, the fallback suite would silently test
 * the fast path a second time and the two implementations could drift apart.
 */
public class UnsafeFieldsFallbackTest {

    @Test
    public void propertyDisablesUnsafeAccess() {
        if ("false".equals(System.getProperty("dingjson.unsafe"))) {
            Assert.assertFalse("dingjson.unsafe=false must disable Unsafe field access",
                    UnsafeFields.AVAILABLE);
        }
    }
}
