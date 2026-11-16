package com.dingjson.processor;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The processor runs inside every user's build, so its two duties are tested
 * from the outside: generate compilable code when it can, and stay silent
 * (successful compile, no codec) when it cannot.
 */
public class DingJsonCodecProcessorTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String HEADER = "package probe;\n"
            + "import com.dingjson.annotation.DingJsonSerializable;\n"
            + "@DingJsonSerializable\n";

    @Test
    public void generatesCodecForAccessibleClass() throws Exception {
        Compile result = compile("public class Ok { public int a; String b; }");
        Assert.assertTrue(result.output, result.ok);
        Assert.assertTrue("codec source must be generated; gen="
                        + java.util.Arrays.toString(new File(result.generatedDir, "probe").list())
                        + " out=" + result.output,
                new File(result.generatedDir, "probe/Ok$$DingJsonCodec.java").isFile());
        // the generated class is compiled too, i.e. it is valid Java
        Assert.assertTrue(new File(result.classesDir, "probe/Ok$$DingJsonCodec.class").isFile());
    }

    @Test
    public void declinesPrivateFieldWithoutBreakingTheBuild() throws Exception {
        Compile result = compile("public class Priv { private String p; public int a; }");
        Assert.assertTrue(result.output, result.ok);
        Assert.assertFalse(new File(result.generatedDir, "probe/Priv$$DingJsonCodec.java").exists());
    }

    @Test
    public void declinesAbstractGenericAndFinalFieldClasses() throws Exception {
        for (String body : new String[]{
                "public abstract class Abs { public int a; }",
                "public class Gen<T> { public T t; }",
                "public class Fin { public final int f = 1; }"}) {
            Compile result = compile(body);
            Assert.assertTrue(result.output, result.ok);
            String name = classNameOf(body);
            Assert.assertFalse("no codec for " + name,
                    new File(result.generatedDir, "probe/" + name + "$$DingJsonCodec.java").exists());
        }
    }

    @Test
    public void rejectsAnnotationOnNonClass() throws Exception {
        Compile result = compile("public interface Iface { }");
        Assert.assertFalse("misuse must be a compile error", result.ok);
        Assert.assertTrue(result.output, result.output.contains("applies to classes only"));
    }

    // ── harness ───────────────────────────────────────────────────

    private static final class Compile {
        boolean ok;
        String output;
        File generatedDir;
        File classesDir;
    }

    /**
     * Classpath for the in-test compilation: the two output directories this
     * class and DingJson were loaded from. Derived from code sources instead of
     * {@code java.class.path} so the test does not depend on how Surefire
     * assembles its booter classpath.
     */
    private static String processingClasspath() {
        StringBuilder cp = new StringBuilder();
        for (Class<?> anchor : new Class<?>[]{DingJsonCodecProcessorTest.class,
                com.dingjson.DingJson.class,
                com.dingjson.annotation.DingJsonSerializable.class}) {
            try {
                File dir = new File(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (cp.indexOf(dir.getAbsolutePath()) < 0) {
                    if (cp.length() > 0) {
                        cp.append(File.pathSeparator);
                    }
                    cp.append(dir.getAbsolutePath());
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot locate " + anchor, e);
            }
        }
        return cp.toString();
    }

    /** Name of the type declared in {@code body}, e.g. {@code Fin} for {@code class Fin { }}. */
    private static String classNameOf(String body) {
        String[] words = body.split("\\s+");
        for (int i = 0; i + 1 < words.length; i++) {
            if ("class".equals(words[i]) || "interface".equals(words[i]) || "enum".equals(words[i])) {
                return words[i + 1].replaceAll("[^A-Za-z0-9_].*$", "");
            }
        }
        return words[words.length - 1].replaceAll("[^A-Za-z0-9_].*$", "");
    }

    private Compile compile(String body) throws IOException {
        File srcDir = tmp.newFolder();
        File genDir = tmp.newFolder();
        File outDir = tmp.newFolder();
        String className = classNameOf(body);
        File src = new File(srcDir, "probe" + File.separator + className + ".java");
        Files.createDirectories(src.getParentFile().toPath());
        Files.write(src.toPath(), (HEADER + body + "\n").getBytes(StandardCharsets.UTF_8));

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assert.assertNotNull("tests must run on a JDK", javac);
        List<String> args = new ArrayList<>();
        // Explicit processor path/name: the test must not depend on how javac
        // discovers processors on a classpath it did not assemble.
        args.add("-proc:full");
        args.add("-processor");
        args.add(DingJsonCodecProcessor.class.getName());
        args.add("-classpath");
        args.add(processingClasspath());
        args.add("-processorpath");
        args.add(processingClasspath());
        args.add("-s");
        args.add(genDir.getAbsolutePath());
        args.add("-d");
        args.add(outDir.getAbsolutePath());
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager files = javac.getStandardFileManager(diags, null, StandardCharsets.UTF_8);
        Compile result = new Compile();
        StringWriter javacOut = new StringWriter();
        result.ok = javac.getTask(javacOut, files, diags, args, null,
                files.getJavaFileObjects(src)).call();
        StringBuilder log = new StringBuilder(javacOut.toString());
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            log.append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n');
        }
        result.output = log.toString();
        result.generatedDir = genDir;
        result.classesDir = outDir;
        return result;
    }
}
