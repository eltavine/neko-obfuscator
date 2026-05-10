package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises flow-keyed numeric constant obfuscation with CFF enabled. The
 * fixture covers bytecode push forms, {@code iinc}, static ConstantValue fields,
 * and all primitive numeric widths.
 */
public class JvmConstantObfuscationIntegrationTest {
    @Test
    void constantObfuscationCoversJvmNumericShapesWithCff() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-constants"));
        Path source = work.resolve("ConstantShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("constant-shapes.jar");
        writeJar(inputJar, classes, "ConstantShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("constant-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("CONSTANT SHAPES OK"), obfuscated);
        assertNumericConstantValuesMovedToClinit(outputJar);
        assertFlowKeyDecodeUsed(outputJar);
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("constantObfuscation", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x1234ABCDL);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void assertNumericConstantValuesMovedToClinit(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("ConstantShapes");
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.name.startsWith("STATIC_")) {
                assertEquals(null, field.value, "numeric ConstantValue remained on field " + field.name);
            }
        }
    }

    private void assertFlowKeyDecodeUsed(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("ConstantShapes");
        boolean sawFloatDecode = false;
        boolean sawDoubleDecode = false;
        boolean sawEncryptedNumericLdc = false;
        boolean sawIntegerRotateDecode = false;
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                        && "java/lang/Float".equals(call.owner)
                        && "intBitsToFloat".equals(call.name)) {
                    sawFloatDecode = true;
                }
                if (insn instanceof MethodInsnNode call
                        && "java/lang/Double".equals(call.owner)
                        && "longBitsToDouble".equals(call.name)) {
                    sawDoubleDecode = true;
                }
                if (insn instanceof MethodInsnNode call
                        && "java/lang/Integer".equals(call.owner)
                        && ("rotateLeft".equals(call.name) || "rotateRight".equals(call.name))) {
                    sawIntegerRotateDecode = true;
                }
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number) {
                    sawEncryptedNumericLdc = true;
                }
            }
        }
        assertTrue(sawFloatDecode, "float constants should decode from integer bits");
        assertTrue(sawDoubleDecode, "double constants should decode from long bits");
        assertTrue(sawEncryptedNumericLdc, "numeric literals should be replaced by encrypted numeric material");
        assertFalse(sawIntegerRotateDecode, "constant decode must not use rotateLeft/rotateRight self-cancelling masks");
    }

    private void writeJar(Path jar, Path classes, String mainClass) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
            List<Path> classFiles = new ArrayList<>();
            try (var stream = Files.walk(classes)) {
                stream.filter(path -> path.toString().endsWith(".class")).forEach(classFiles::add);
            }
            for (Path classFile : classFiles) {
                String name = classes.relativize(classFile).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(name));
                jos.write(Files.readAllBytes(classFile));
                jos.closeEntry();
            }
        }
    }

    private String runJar(Path jar) throws Exception {
        return run(List.of("java", "-XX:-UsePerfData", "-jar", jar.toString()), Duration.ofSeconds(30));
    }

    private String run(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "command timed out: " + command);
        assertEquals(0, process.exitValue(), "command failed: " + command + "\n" + output);
        return output;
    }

    private String sourceText() {
        return """
            public class ConstantShapes {
                static final int STATIC_INT = -1234567;
                static final long STATIC_LONG = 0x123456789ABCDEFL;
                static final float STATIC_FLOAT = -3.5f;
                static final double STATIC_DOUBLE = 6.25d;

                public static void main(String[] args) {
                    ConstantShapes shapes = new ConstantShapes();
                    String out = shapes.all();
                    System.out.println(out);
                    if (!out.equals("123486954:81985529216486894:11.0:14.0:-1234567:81985529216486895:-3.5:6.25")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("CONSTANT SHAPES OK");
                }

                String all() {
                    return ints() + ":" + longs() + ":" + floats() + ":" + doubles()
                        + ":" + STATIC_INT + ":" + STATIC_LONG + ":" + STATIC_FLOAT + ":" + STATIC_DOUBLE;
                }

                int ints() {
                    int v = -1 + 0 + 1 + 2 + 3 + 4 + 5;
                    v += 127;
                    v += 30000;
                    v += 123456789;
                    v++;
                    v += 7;
                    v -= 3;
                    for (int i = 0; i < 4; i++) {
                        v += i;
                    }
                    switch (v & 3) {
                        case 0:
                            return v + 11;
                        case 1:
                            return v + 13;
                        case 2:
                            return v + 17;
                        default:
                            return v + 19;
                    }
                }

                long longs() {
                    long a = 0L;
                    long b = 1L;
                    long c = 0x123456789ABCDEFL;
                    long d = -2L;
                    return a + b + c + d;
                }

                float floats() {
                    float a = 0.0f;
                    float b = 1.0f;
                    float c = 2.0f;
                    float d = 8.0f;
                    return a + b + c + d;
                }

                double doubles() {
                    double a = 0.0d;
                    double b = 1.0d;
                    double c = 2.5d;
                    double d = 10.5d;
                    return a + b + c + d;
                }
            }
            """;
    }
}
