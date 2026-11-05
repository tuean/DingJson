package com.dingjson;

import com.dingjson.exception.JSONException;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the JSONTestSuite (https://github.com/nst/JSONTestSuite) compliance
 * corpus against the DingJson parser.
 * <p>
 * Semantics (matching the suite's README):
 * <ul>
 *   <li>{@code y_} — valid JSON, MUST parse successfully</li>
 *   <li>{@code n_} — invalid JSON, MUST be rejected</li>
 *   <li>{@code i_} — implementation-defined; behavior is recorded but not asserted</li>
 * </ul>
 * Every violation of the y_/n_ contract is reported in the test failure message,
 * so a failing run doubles as a conformance report.
 */
public class JSONTestSuiteTest {

    private static final File DIR = new File("src/test/resources/jsontestsuite");

    private static List<File> filesWithPrefix(String prefix) {
        File[] all = DIR.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".json"));
        List<File> files = new ArrayList<>();
        if (all != null) {
            java.util.Arrays.sort(all);
            for (File f : all) files.add(f);
        }
        return files;
    }

    private static String read(File f) throws IOException {
        byte[] bytes = Files.readAllBytes(f.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── y_: valid JSON must parse ─────────────────────────────────

    @Test
    public void allValidJsonMustParse() throws IOException {
        List<File> files = filesWithPrefix("y_");
        Assert.assertFalse("y_ corpus missing", files.isEmpty());
        StringBuilder failures = new StringBuilder();
        for (File f : files) {
            try {
                DingJson.parse(read(f));
            } catch (Exception e) {
                failures.append('\n').append(f.getName())
                        .append(" -> ").append(e.getClass().getSimpleName())
                        .append(": ").append(e.getMessage());
            }
        }
        Assert.assertTrue("VALID JSON rejected by DingJson:" + failures, failures.length() == 0);
    }

    // ── n_: invalid JSON must be rejected ─────────────────────────

    @Test
    public void allInvalidJsonMustBeRejected() throws IOException {
        List<File> files = filesWithPrefix("n_");
        Assert.assertFalse("n_ corpus missing", files.isEmpty());
        StringBuilder leaks = new StringBuilder();
        for (File f : files) {
            try {
                Object result = DingJson.parse(read(f));
                leaks.append('\n').append(f.getName())
                        .append(" -> ACCEPTED as ").append(result == null ? "null" : result.getClass().getSimpleName());
            } catch (JSONException e) {
                // expected rejection
            }
        }
        Assert.assertTrue("INVALID JSON accepted by DingJson (lenient):" + leaks, leaks.length() == 0);
    }

    // ── i_: implementation-defined behavior (recorded, not asserted) ──

    @Test
    public void implementationDefinedBehaviorIsRecorded() throws IOException {
        List<File> files = filesWithPrefix("i_");
        Assert.assertFalse("i_ corpus missing", files.isEmpty());
        StringBuilder report = new StringBuilder("\nImplementation-defined behavior (accepted):");
        for (File f : files) {
            try {
                DingJson.parse(read(f));
                report.append('\n').append(f.getName()).append(" -> accepted");
            } catch (JSONException e) {
                report.append('\n').append(f.getName()).append(" -> rejected: ").append(e.getMessage());
            }
        }
        // Not a pass/fail assertion — print the behavior map so it can be reviewed.
        System.out.println(report);
    }

    // ── y_/n_/i_ corpus integrity ─────────────────────────────────

    @Test
    public void corpusIntegrity() {
        Assert.assertEquals(95, filesWithPrefix("y_").size());
        Assert.assertEquals(188, filesWithPrefix("n_").size());
        Assert.assertEquals(35, filesWithPrefix("i_").size());
    }
}
