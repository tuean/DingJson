package com.dingjson;

import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * The allocation-free double fast path must be bit-identical to
 * {@link Double#parseDouble} — a "close enough" parse would silently change
 * data on round-trip. Every assertion here compares raw bits, not values.
 */
public class DingJsonNumberParseTest {

    private static long bits(double v) {
        return Double.doubleToLongBits(v);
    }

    private static void assertBits(String literal) {
        double expected = Double.parseDouble(literal);
        Object parsed = DingJson.parse(literal);
        // Whole numbers come back as Integer/Long and -0 as Integer 0 (documented),
        // so only compare the tree path when it produced a double.
        if (parsed instanceof Double) {
            Assert.assertEquals("tree parse of " + literal, bits(expected), bits((Double) parsed));
        }
        if (parsed instanceof java.math.BigDecimal) {
            return; // integer literal beyond long: the streaming path rejects it
        }
        double[] viaArray = DingJson.createReader("[" + literal + "]").readDoubleArray();
        Assert.assertEquals("readDoubleArray of " + literal, bits(expected), bits(viaArray[0]));
    }

    @Test
    public void edgeCases() {
        String[] literals = {
                "0", "-0", "0.0", "-0.0", "1", "-1", "1.5", "-1.5",
                "0.1", "0.2", "0.3", "1.0000000000000002", "3.141592653589793",
                "1e10", "-1e10", "1E-10", "1.5e20", "1.5E-20", "1e+20", "1e-20",
                "1e22", "1e23", "1e-22", "1e-23", "9.999999999999999e22",
                "123456789012345", "1234567890123456", "12345678901234567",
                "1e308", "1e-308", "5e-324", "1.23456789012345e30",
                "2.2250738585072014e-308", "1.7976931348623157e308",
                "0.000001", "0.0000001", "-0.0000001", "999999999999999999",
        };
        for (String literal : literals) {
            assertBits(literal);
        }
    }

    @Test
    public void randomLiteralsMatchJdk() {
        Random random = new Random(20260806L);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.setLength(0);
            if (random.nextBoolean()) {
                sb.append('-');
            }
            int intDigits = 1 + random.nextInt(12);
            for (int d = 0; d < intDigits; d++) {
                // no leading zeros: "01" is not valid JSON
                sb.append((char) ('0' + (d == 0 ? 1 + random.nextInt(9) : random.nextInt(10))));
            }
            if (random.nextBoolean()) {
                sb.append('.');
                int fracDigits = 1 + random.nextInt(8);
                for (int d = 0; d < fracDigits; d++) {
                    sb.append((char) ('0' + random.nextInt(10)));
                }
            }
            if (random.nextBoolean()) {
                sb.append(random.nextBoolean() ? 'e' : 'E');
                int exp = random.nextInt(60) - 30;
                sb.append(exp);
            }
            String literal = sb.toString();
            // Skip literals whose integer part alone exceeds the long range:
            // the tree path rejects those (documented), so they have no double.
            try {
                Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                continue;
            }
            if (literal.matches("-?\\d{19,}.*")) {
                continue;
            }
            assertBits(literal);
        }
    }

    @Test
    public void arraysOfDecimals() {
        String json = "[0.5,-0.5,1.25,2.5e3,1e-3,123.456,0,-0.0]";
        double[] actual = DingJson.createReader(json).readDoubleArray();
        double[] expected = {0.5, -0.5, 1.25, 2500.0, 0.001, 123.456, 0, -0.0};
        Assert.assertArrayEquals(expected, actual, 0.0);
        Assert.assertEquals(Double.doubleToLongBits(-0.0),
                Double.doubleToLongBits(actual[7]));
    }
}
