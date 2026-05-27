package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

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
        assertPrimitiveArrayPayloadsEncrypted(outputJar);
        assertConstantLiveWordConsumesDataDigest(outputJar);
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
            boolean generatedHelper = method.name.startsWith("__neko_");
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
                        && ("rotateLeft".equals(call.name) || "rotateRight".equals(call.name))
                        && !generatedHelper) {
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

    private void assertPrimitiveArrayPayloadsEncrypted(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("ConstantShapes");
        for (var method : clazz.asmNode().methods) {
            if (!"arrays".equals(method.name) || method.instructions == null) continue;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                Long plaintext = storeLiteralBits(insn.getOpcode(), previousReal(insn.getPrevious()));
                assertFalse(
                    plaintext != null && fixtureArrayPayload(insn.getOpcode(), plaintext),
                    "primitive array store retained plaintext payload before opcode " + insn.getOpcode()
                );
            }
        }
    }

    private void assertConstantLiveWordConsumesDataDigest(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("ConstantShapes");
        int checkedBaseLoads = 0;
        int checkedCompactCalls = 0;
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            if (method.name.startsWith("__neko_")) continue;
            AbstractInsnNode[] insns = method.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (isCompactConstantDecodeCall(insns[i])) {
                    assertTrue(
                        compactCallReceivesDataBoundBase(insns, i),
                        "compact constant decode call did not receive data-bound encoded base and multiplier in " +
                            method.name + method.desc
                    );
                    checkedCompactCalls++;
                }
                if (!(insns[i] instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ILOAD) {
                    continue;
                }
                int dataLocal = constantDecodeDataLocal(insns, i, load.var);
                if (dataLocal >= 0) {
                    checkedBaseLoads++;
                }
            }
        }
        assertTrue(
            checkedBaseLoads > 0,
            "constant fixture did not expose protected numeric decode base loads"
        );
        assertTrue(
            checkedCompactCalls > 0,
            "constant fixture did not expose compact protected numeric decode calls"
        );
    }

    private boolean fixtureArrayPayload(int storeOpcode, long bits) {
        return switch (storeOpcode) {
            case Opcodes.BASTORE -> bits == -128L || bits == 0L || bits == 1L || bits == 127L;
            case Opcodes.SASTORE -> bits == -30000L || bits == 12345L;
            case Opcodes.CASTORE -> bits == 65L || bits == 0x1234L;
            case Opcodes.IASTORE -> bits == -7L || bits == 0L || bits == 42L || bits == 123456789L;
            case Opcodes.LASTORE -> bits == 0x1020304050607080L || bits == -5L || bits == 9L;
            case Opcodes.FASTORE -> bits == (long) Float.floatToRawIntBits(-1.5f) ||
                bits == (long) Float.floatToRawIntBits(0.25f) ||
                bits == (long) Float.floatToRawIntBits(3.75f);
            case Opcodes.DASTORE -> bits == Double.doubleToRawLongBits(-2.5d) ||
                bits == Double.doubleToRawLongBits(6.5d);
            default -> false;
        };
    }

    private Long storeLiteralBits(int storeOpcode, AbstractInsnNode valueInsn) {
        if (valueInsn == null) return null;
        return switch (storeOpcode) {
            case Opcodes.BASTORE, Opcodes.SASTORE, Opcodes.CASTORE, Opcodes.IASTORE -> intLiteral(valueInsn);
            case Opcodes.LASTORE -> longLiteral(valueInsn);
            case Opcodes.FASTORE -> floatLiteralBits(valueInsn);
            case Opcodes.DASTORE -> doubleLiteralBits(valueInsn);
            default -> null;
        };
    }

    private Long intLiteral(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return (long) (opcode - Opcodes.ICONST_0);
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return (long) ((IntInsnNode) insn).operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
            return value.longValue();
        }
        return null;
    }

    private Long longLiteral(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long value) {
            return value;
        }
        return null;
    }

    private Long floatLiteralBits(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) {
            return (long) Float.floatToRawIntBits((float) (opcode - Opcodes.FCONST_0));
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Float value) {
            return (long) Float.floatToRawIntBits(value);
        }
        return null;
    }

    private Long doubleLiteralBits(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.DCONST_0) return Double.doubleToRawLongBits(0.0d);
        if (opcode == Opcodes.DCONST_1) return Double.doubleToRawLongBits(1.0d);
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double value) {
            return Double.doubleToRawLongBits(value);
        }
        return null;
    }

    private AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn;
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private int constantDecodeDataLocal(AbstractInsnNode[] insns, int loadIndex, int baseLocal) {
        int limit = Math.min(insns.length, loadIndex + 320);
        for (int i = loadIndex + 1; i + 2 < limit; i++) {
            if (!(insns[i] instanceof VarInsnNode baseStore)
                || baseStore.getOpcode() != Opcodes.ISTORE
                || baseStore.var != baseLocal) {
                continue;
            }
            if (!(insns[i + 1] instanceof VarInsnNode dataLoad)
                || dataLoad.getOpcode() != Opcodes.ILOAD
                || dataLoad.var == baseLocal) {
                continue;
            }
            if (!(insns[i + 2] instanceof VarInsnNode dataStore)
                || dataStore.getOpcode() != Opcodes.ISTORE
                || dataStore.var == baseLocal) {
                continue;
            }
            int firstDataLoad = firstVarLoadIndex(
                insns,
                Opcodes.ILOAD,
                dataLoad.var,
                loadIndex + 1,
                i + 1
            );
            if (firstDataLoad < 0 || !hasNonlinearMixAfter(insns, firstDataLoad + 1, i + 1)) {
                continue;
            }
            if (!hasDecodeBoundary(insns, i + 3, limit)) {
                continue;
            }
            return dataLoad.var;
        }
        return -1;
    }

    private boolean isConstantDecodeBoundary(AbstractInsnNode insn) {
        if (isCompactConstantDecodeCall(insn)) {
            return true;
        }
        return insn.getOpcode() == Opcodes.IXOR;
    }

    private boolean isCompactConstantDecodeCall(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call
            && call.name.startsWith("__neko_num_i")
            && "(IIII)I".equals(call.desc);
    }

    private boolean compactCallReceivesDataBoundBase(AbstractInsnNode[] insns, int callIndex) {
        AbstractInsnNode multiplierLoadInsn = previousReal(insns[callIndex].getPrevious());
        if (!(multiplierLoadInsn instanceof VarInsnNode multiplierLoad)
            || multiplierLoad.getOpcode() != Opcodes.ILOAD) {
            return false;
        }
        int start = Math.max(0, callIndex - 400);
        for (int i = callIndex - 1; i >= start; i--) {
            if (!(insns[i] instanceof VarInsnNode baseLoad)
                || baseLoad.getOpcode() != Opcodes.ILOAD) {
                continue;
            }
            int dataLocal = constantDecodeDataLocal(insns, i, baseLoad.var);
            if (dataLocal < 0) continue;
            if (localStoredAfterDataMix(insns, multiplierLoad.var, dataLocal, i + 1, callIndex)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDecodeBoundary(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (isConstantDecodeBoundary(insns[i])) {
                return true;
            }
        }
        return false;
    }

    private int firstVarLoadIndex(
        AbstractInsnNode[] insns,
        int opcode,
        int local,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode var
                && var.getOpcode() == opcode
                && var.var == local) {
                return i;
            }
        }
        return -1;
    }

    private boolean localStoredAfterDataMix(
        AbstractInsnNode[] insns,
        int storedLocal,
        int dataLocal,
        int startInclusive,
        int limitExclusive
    ) {
        boolean sawData = false;
        boolean sawNonlinearAfterData = false;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ILOAD
                && load.var == dataLocal) {
                sawData = true;
            }
            if (sawData && isNonlinearIntMixOpcode(insns[i].getOpcode())) {
                sawNonlinearAfterData = true;
            }
            if (sawData
                && sawNonlinearAfterData
                && insns[i] instanceof VarInsnNode store
                && store.getOpcode() == Opcodes.ISTORE
                && store.var == storedLocal) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNonlinearMixAfter(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (isNonlinearIntMixOpcode(insns[i].getOpcode())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNonlinearIntMixOpcode(int opcode) {
        return opcode == Opcodes.IMUL
            || opcode == Opcodes.IUSHR
            || opcode == Opcodes.IXOR;
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
                    if (!out.equals("123486954:81985529216486894:11.0:14.0:-1234567:81985529216486895:-3.5:6.25:" + shapes.arrays())) {
                        throw new AssertionError(out);
                    }
                    System.out.println("CONSTANT SHAPES OK");
                }

                String all() {
                    return ints() + ":" + longs() + ":" + floats() + ":" + doubles()
                        + ":" + STATIC_INT + ":" + STATIC_LONG + ":" + STATIC_FLOAT + ":" + STATIC_DOUBLE
                        + ":" + arrays();
                }

                long arrays() {
                    long total = 0L;
                    boolean[] flags = new boolean[] {true, false, true};
                    byte[] bytes = new byte[] {(byte) 0x80, 0, 127};
                    short[] shorts = new short[] {-30000, 12345};
                    char[] chars = new char[] {'A', 0x1234};
                    int[] ints = new int[] {-7, 0, 123456789, 42};
                    long[] longs = new long[] {0x1020304050607080L, -5L, 9L};
                    float[] floats = new float[] {-1.5f, 0.25f, 3.75f};
                    double[] doubles = new double[] {-2.5d, 6.5d};
                    int[] empty = new int[] {};
                    for (boolean v : flags) total = total * 31L + (v ? 1L : 0L);
                    for (byte v : bytes) total = total * 31L + v;
                    for (short v : shorts) total = total * 31L + v;
                    for (char v : chars) total = total * 31L + v;
                    for (int v : ints) total = total * 31L + v;
                    for (long v : longs) total = total * 31L + v;
                    for (float v : floats) total = total * 31L + Float.floatToRawIntBits(v);
                    for (double v : doubles) total = total * 31L + Double.doubleToRawLongBits(v);
                    return total + empty.length;
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
