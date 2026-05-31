package dev.nekoobfuscator.transforms.jvm.cff;

import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeKind.ALIAS_HUB;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeKind.DIRECT_ISLAND;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeKind.HUB;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeRole.CONDITIONAL_FALSE;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeRole.FAKE;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeRole.FALLTHROUGH;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeRole.HANDLER;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.EdgeRole.POISON;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import dev.nekoobfuscator.transforms.jvm.internal.JvmCodeSizeEstimator;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class CffTransitionOutlinerPolicyTest {

    @Test
    void budgetedInlineOnlyAcceptsRealDirectIslandEdges() {
        assertTrue(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                DIRECT_ISLAND,
                FALLTHROUGH,
                1
            )
        );
        assertTrue(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                DIRECT_ISLAND,
                CONDITIONAL_FALSE,
                128
            )
        );

        assertFalse(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                DIRECT_ISLAND,
                FALLTHROUGH,
                0
            )
        );
        assertFalse(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                DIRECT_ISLAND,
                HANDLER,
                128
            )
        );
        assertFalse(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                DIRECT_ISLAND,
                FAKE,
                128
            )
        );
        assertFalse(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                DIRECT_ISLAND,
                POISON,
                128
            )
        );
        assertFalse(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                HUB,
                FALLTHROUGH,
                128
            )
        );
        assertFalse(
            CffTransitionOutliner.isBudgetedDirectTransitionEligible(
                ALIAS_HUB,
                FALLTHROUGH,
                128
            )
        );
    }

    @Test
    void cyclicJitBudgetEmitsBudgetedInlineAndKeepsOutlinedRoutes()
        throws Exception {
        Path projectRoot = Path.of(
            System.getProperty("neko.test.projectRoot", System.getProperty("user.dir"))
        );
        Path work = recreateWork(projectRoot.resolve("build/tmp/neko-test-cff-budgeted-direct-inline"));
        Path source = work.resolve("CffBudgetedDirectInlineShape.java");
        Files.writeString(source, cyclicJitBudgetSourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("cff-budgeted-direct-inline.jar");
        writeJar(inputJar, classes, "CffBudgetedDirectInlineShape");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("cff-budgeted-direct-inline-obf.jar");
        String oldProperty = System.getProperty("neko.cff.recordBudgetedDirectInlineStats");
        System.setProperty("neko.cff.recordBudgetedDirectInlineStats", "true");
        ControlFlowFlatteningPass.clearBudgetedDirectInlineStatsForTesting();
        try {
            runCffOnlyObfuscation(inputJar, outputJar);
        } finally {
            if (oldProperty == null) {
                System.clearProperty("neko.cff.recordBudgetedDirectInlineStats");
            } else {
                System.setProperty("neko.cff.recordBudgetedDirectInlineStats", oldProperty);
            }
        }
        assertEquals(original, runJar(outputJar));

        ControlFlowFlatteningPass.BudgetedDirectInlineStats stats =
            ControlFlowFlatteningPass.budgetedDirectInlineStatsForTesting()
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey().endsWith(".hot(I)I"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing budgeted direct-inline stats"));
        assertTrue(stats.candidates() > 0, "no eligible budgeted direct-island transitions");
        assertTrue(stats.accepted() > 0, "no budgeted direct-island transition was inlined");

        MethodNode hot = method(outputJar, "CffBudgetedDirectInlineShape", "hot");
        assertTrue(
            JvmCodeSizeEstimator.estimateMethodBytes(hot) < 8_000,
            "budgeted inline CFF method exceeded JIT budget"
        );
        assertTrue(
            cffOutlinerHelperCallCount(hot) > 0,
            "budgeted direct inline removed all outlined helper routes"
        );
    }

    private static void runCffOnlyObfuscation(Path input, Path output)
        throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x5A6C0FFEE11L);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private static MethodNode method(Path jar, String owner, String name)
        throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get(owner);
        assertTrue(clazz != null, "missing class " + owner);
        for (MethodNode method : clazz.asmNode().methods) {
            if (name.equals(method.name)
                && method.instructions != null
                && method.instructions.size() > 0) {
                return method;
            }
        }
        throw new AssertionError("missing transformed method " + owner + "." + name);
    }

    private static int cffOutlinerHelperCallCount(MethodNode method) {
        int calls = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if ("(JIIIIII[J)J".equals(call.desc)) {
                calls++;
            } else if ("(JIII[Ljava/lang/Object;II[J)J".equals(call.desc)) {
                calls++;
            } else if ("(JIIII[J)J".equals(call.desc)) {
                calls++;
            } else if ("(JIIII[J[I)J".equals(call.desc)) {
                calls++;
            }
        }
        return calls;
    }

    private static Path recreateWork(Path work)
        throws Exception {
        if (Files.exists(work)) {
            try (var stream = Files.walk(work)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        return Files.createDirectories(work);
    }

    private static void writeJar(Path jar, Path classes, String mainClass)
        throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest);
             var stream = Files.walk(classes)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                out.putNextEntry(new JarEntry(name));
                Files.copy(path, out);
                out.closeEntry();
            }
        }
    }

    private static String runJar(Path jar)
        throws Exception {
        return run(
            List.of("java", "-XX:-UsePerfData", "-jar", jar.toString()),
            Duration.ofSeconds(30)
        );
    }

    private static String run(List<String> command, Duration timeout)
        throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "command timed out: " + command);
        assertEquals(0, process.exitValue(), "command failed: " + command + "\n" + output);
        return output;
    }

    private static String cyclicJitBudgetSourceText() {
        return """
            public class CffBudgetedDirectInlineShape {
                public static void main(String[] args) {
                    int out = hot(41);
                    System.out.println("CFF BUDGETED DIRECT INLINE OK " + out);
                    if (out == 0x12345678) {
                        throw new AssertionError(out);
                    }
                }

                static int hot(int seed) {
                    int v = seed;
                    int a = 3;
                    int b = 5;
                    int c = 7;
                    int d = 11;
                    for (int i = 0; i < 96; i++) {
                        v = (v * 1103515245 + 12345) ^ i;
                        if ((v & 1) == 0) {
                            a += v ^ i;
                        } else {
                            b ^= v + i;
                        }
                        if ((v & 2) == 0) {
                            c += a ^ b;
                        } else {
                            d ^= c + i;
                        }
                        if ((v & 4) == 0) {
                            b += d ^ (v >>> 3);
                        } else {
                            c ^= b + (v << 1);
                        }
                        if ((v & 8) == 0) {
                            d += a ^ (c >>> 1);
                        } else {
                            a ^= d + (b << 2);
                        }
                        v ^= a + b + c + d;
                    }
                    return v ^ a ^ b ^ c ^ d;
                }
            }
            """;
    }
}
