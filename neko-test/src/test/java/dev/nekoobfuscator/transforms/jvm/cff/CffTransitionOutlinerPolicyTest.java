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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class CffTransitionOutlinerPolicyTest {
    private static final String CFF_SHARED_GROUP_DISPATCH_DESC = "(JIIIIII[J)J";
    private static final String CFF_TRANSITION_MATERIAL_DESC =
        "(JIII[Ljava/lang/Object;II[J)J";
    private static final String CFF_COMPACT_STATE_TRANSITION_DESC =
        "(JIIII[J[I)J";
    private static final String CFF_ISLAND_MATERIAL_DESC =
        "(JIII[Ljava/lang/Object;III)I";

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
    void denseResultRoutesReloadTransitionPcBeforeRouteMask() {
        assertTrue(
            CffTransitionOutliner.shouldReloadPcForGroupResultRoute(false, true, false),
            "dense group result routing must reload pc even without a hub result"
        );
        assertTrue(
            CffTransitionOutliner.shouldReloadPcForGroupResultRoute(false, false, true),
            "hub result routing must keep the previous pc reload behavior"
        );
        assertFalse(
            CffTransitionOutliner.shouldReloadPcForGroupResultRoute(true, true, false),
            "inlined single-result fallthrough should not emit result routing loads"
        );
        assertFalse(
            CffTransitionOutliner.shouldReloadPcForGroupResultRoute(false, false, false),
            "sparse non-hub result routing does not consume the pc route mask"
        );

        assertTrue(
            CffTransitionOutliner.shouldReloadPcForIslandResultRoute(true, 0),
            "dense island result routing must reload pc even without fake routes"
        );
        assertTrue(
            CffTransitionOutliner.shouldReloadPcForIslandResultRoute(false, 1),
            "fake island routes must keep the previous pc reload behavior"
        );
        assertFalse(
            CffTransitionOutliner.shouldReloadPcForIslandResultRoute(false, 0),
            "sparse island result routing without fake routes does not consume the pc route mask"
        );
    }

    @Test
    void generatedCffStaticHelpersUseInterfaceCompatibleAccess() {
        int interfaceAccess = CffTransitionOutliner.generatedStaticHelperAccess(true);
        assertTrue((interfaceAccess & Opcodes.ACC_PUBLIC) != 0);
        assertFalse((interfaceAccess & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((interfaceAccess & Opcodes.ACC_STATIC) != 0);
        assertTrue((interfaceAccess & Opcodes.ACC_SYNTHETIC) != 0);

        int classAccess = CffTransitionOutliner.generatedStaticHelperAccess(false);
        assertTrue((classAccess & Opcodes.ACC_PRIVATE) != 0);
        assertFalse((classAccess & Opcodes.ACC_PUBLIC) != 0);
        assertTrue((classAccess & Opcodes.ACC_STATIC) != 0);
        assertTrue((classAccess & Opcodes.ACC_SYNTHETIC) != 0);
    }

    @Test
    void adaptiveReserveTracksFuturePostCffSitePressure() {
        MethodNode lowPressure = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "lowPressure",
            "()V",
            null,
            null
        );
        lowPressure.instructions.add(new InsnNode(Opcodes.ICONST_0));
        lowPressure.instructions.add(new InsnNode(Opcodes.POP));
        lowPressure.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode highPressure = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "highPressure",
            "()V",
            null,
            null
        );
        for (int i = 0; i < 5; i++) {
            highPressure.instructions.add(stringConcatIndy());
            highPressure.instructions.add(new InsnNode(Opcodes.POP));
            highPressure.instructions.add(new LdcInsnNode("protected literal " + i));
            highPressure.instructions.add(new InsnNode(Opcodes.POP));
        }
        highPressure.instructions.add(new InsnNode(Opcodes.RETURN));

        int lowReserve = CffBlockBuilder.adaptivePostCffReserveBytes(lowPressure);
        int highReserve = CffBlockBuilder.adaptivePostCffReserveBytes(highPressure);
        assertTrue(lowReserve < highReserve, "low-pressure methods should spend more inline budget");
        assertEquals(1_500, highReserve, "high-pressure methods should keep the fixed reserve cap");
    }

    @Test
    void adaptiveReserveStillRejectsAlreadyOversizedProjectedMethods() {
        MethodNode lowPressure = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "lowPressure",
            "()V",
            null,
            null
        );
        lowPressure.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(
            0,
            CffBlockBuilder.inlineDirectTransitionBudgetBytesForProjectedSize(
                lowPressure,
                8_000
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
        assertTrue(
            compactStateTransitionCallCount(hot) > 0,
            "JIT-budget CFF method did not route direct transitions through compact state wrappers"
        );
        assertIslandDispatchMissUsesColdHelper(outputJar, "CffBudgetedDirectInlineShape");
        assertCompactTransitionWrappersPreserveMaterialCalls(
            outputJar,
            "CffBudgetedDirectInlineShape"
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
            if (CFF_SHARED_GROUP_DISPATCH_DESC.equals(call.desc)) {
                calls++;
            } else if (CFF_TRANSITION_MATERIAL_DESC.equals(call.desc)) {
                calls++;
            } else if ("(JIIII[J)J".equals(call.desc)) {
                calls++;
            } else if (CFF_COMPACT_STATE_TRANSITION_DESC.equals(call.desc)) {
                calls++;
            }
        }
        return calls;
    }

    private static int compactStateTransitionCallCount(MethodNode method) {
        int calls = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                && CFF_COMPACT_STATE_TRANSITION_DESC.equals(call.desc)) {
                calls++;
            }
        }
        return calls;
    }

    private static void assertCompactTransitionWrappersPreserveMaterialCalls(Path jar, String owner)
        throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get(owner);
        assertTrue(clazz != null, "missing class " + owner);
        int wrappers = 0;
        for (MethodNode method : clazz.asmNode().methods) {
            if (!CFF_COMPACT_STATE_TRANSITION_DESC.equals(method.desc)
                || method.instructions == null
                || method.instructions.size() == 0) {
                continue;
            }
            wrappers++;
            assertEquals(
                1,
                transitionMaterialCallCount(method),
                "compact transition wrapper lost transition material helper coverage"
            );
        }
        assertTrue(wrappers > 0, "no compact state transition wrappers were generated");
    }

    private static int transitionMaterialCallCount(MethodNode method) {
        int calls = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                && CFF_TRANSITION_MATERIAL_DESC.equals(call.desc)) {
                calls++;
            }
        }
        return calls;
    }

    private static void assertIslandDispatchMissUsesColdHelper(Path jar, String owner)
        throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get(owner);
        assertTrue(clazz != null, "missing class " + owner);
        Set<String> sharedHelpers = new HashSet<>();
        for (MethodNode method : clazz.asmNode().methods) {
            if (CFF_SHARED_GROUP_DISPATCH_DESC.equals(method.desc)) {
                sharedHelpers.add(method.name);
            }
        }

        for (MethodNode method : clazz.asmNode().methods) {
            if (!CFF_SHARED_GROUP_DISPATCH_DESC.equals(method.desc)
                || method.instructions == null
                || method.instructions.size() == 0) {
                continue;
            }
            boolean decodesRealResult = false;
            boolean returnedRealResultBeforeColdMiss = false;
            boolean callsColdMissHelper = false;
            for (
                AbstractInsnNode insn = method.instructions.getFirst();
                insn != null;
                insn = insn.getNext()
            ) {
                if (insn.getOpcode() == Opcodes.LRETURN && !callsColdMissHelper) {
                    returnedRealResultBeforeColdMiss = true;
                }
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (CFF_ISLAND_MATERIAL_DESC.equals(call.desc)) {
                    decodesRealResult = true;
                    continue;
                }
                if (!CFF_SHARED_GROUP_DISPATCH_DESC.equals(call.desc)
                    || !owner.equals(call.owner)
                    || !sharedHelpers.contains(call.name)
                    || call.name.equals(method.name)) {
                    continue;
                }
                AbstractInsnNode next = nextOpcodeInsn(call.getNext());
                if (next != null && next.getOpcode() == Opcodes.LRETURN) {
                    callsColdMissHelper = true;
                }
            }
            if (decodesRealResult && returnedRealResultBeforeColdMiss && callsColdMissHelper) {
                return;
            }
        }
        throw new AssertionError(
            "missing island dispatch helper with hot real-result returns and cold miss helper call"
        );
    }

    private static AbstractInsnNode nextOpcodeInsn(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        return insn;
    }

    private static InvokeDynamicInsnNode stringConcatIndy() {
        return new InvokeDynamicInsnNode(
            "makeConcatWithConstants",
            "()Ljava/lang/String;",
            new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)" +
                    "Ljava/lang/invoke/CallSite;",
                false
            ),
            "prefix\u0001suffix"
        );
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
