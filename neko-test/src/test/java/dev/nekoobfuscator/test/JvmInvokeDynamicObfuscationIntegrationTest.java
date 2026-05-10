package dev.nekoobfuscator.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

public class JvmInvokeDynamicObfuscationIntegrationTest {
    @Test
    void invokeDynamicObfuscatesCffKeyedMethodAndFieldReferences() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-indy-reference"));
        Path source = work.resolve("IndyReferenceShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("indy-reference-shapes.jar");
        writeJar(inputJar, classes, "IndyReferenceShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("indy-reference-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("INDY REF OK"), obfuscated);
        assertReferenceSitesUseInvokeDynamic(outputJar);
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("invokeDynamic", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x1E0D1E0DCAFEL);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void assertReferenceSitesUseInvokeDynamic(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        var clazz = input.classMap().get("IndyReferenceShapes");
        boolean sawIndy = false;
        boolean sawLongLongDescriptor = false;
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null || method.name.startsWith("__neko_")) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy
                    && indy.bsm != null
                    && "IndyReferenceShapes".equals(indy.bsm.getOwner())) {
                    sawIndy = true;
                    sawLongLongDescriptor |= indy.desc.contains("JJ)");
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof String value) {
                            assertFalse(value.equals("staticAdd"), "plaintext method name survived in bootstrap args");
                            assertFalse(value.equals("privateMix"), "plaintext method name survived in bootstrap args");
                            assertFalse(value.equals("staticValue"), "plaintext field name survived in bootstrap args");
                            assertFalse(value.equals("value"), "plaintext field name survived in bootstrap args");
                        }
                    }
                }
                if (insn instanceof MethodInsnNode call
                    && "IndyReferenceShapes".equals(call.owner)
                    && ("staticAdd".equals(call.name) || "privateMix".equals(call.name))) {
                    throw new AssertionError("direct method reference survived: " + call.name + call.desc);
                }
                if (insn instanceof FieldInsnNode field
                    && "IndyReferenceShapes".equals(field.owner)
                    && ("staticValue".equals(field.name) || "value".equals(field.name))
                    && !"<init>".equals(method.name)
                    && !"<clinit>".equals(method.name)) {
                    throw new AssertionError("direct field reference survived: " + field.name + field.desc);
                }
            }
        }
        assertTrue(sawIndy, "no invokeDynamic reference sites found");
        assertTrue(sawLongLongDescriptor, "indy reference sites should carry keyed long arguments");
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
        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "command timed out: " + command);
        assertEquals(0, process.exitValue(), "command failed: " + command + "\n" + output);
        return output;
    }

    private String sourceText() {
        return """
            public class IndyReferenceShapes {
                interface Worker {
                    int work(int value);
                }

                static class Impl implements Worker {
                    public int work(int value) {
                        return value + 11;
                    }
                }

                static int staticValue = 7;
                int value = 5;

                static int staticAdd(int left, int right) {
                    return left + right + staticValue;
                }

                private int privateMix(int input) {
                    return input + value;
                }

                int run() {
                    Worker worker = new Impl();
                    int total = staticAdd(1, 2);
                    total += worker.work(3);
                    total += privateMix(4);
                    total += staticValue;
                    staticValue = 9;
                    value = 6;
                    total += value;
                    System.out.println(new StringBuilder().append("INDY REF OK ").append(total).toString());
                    return total;
                }

                public static void main(String[] args) {
                    new IndyReferenceShapes().run();
                }
            }
            """;
    }
}
