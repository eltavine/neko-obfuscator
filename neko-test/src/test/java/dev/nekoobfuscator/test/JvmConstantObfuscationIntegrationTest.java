package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises flow-keyed numeric constant obfuscation with CFF enabled. The
 * fixture covers bytecode push forms, {@code iinc}, static ConstantValue fields,
 * and all primitive numeric widths.
 */
public class JvmConstantObfuscationIntegrationTest {
    @Test
    void derivedNumericMaterialClassifierRejectsDirectAndSelfCancelingShapes() {
        InsnList derivedInt = new InsnList();
        JvmPassBytecode.pushDerivedIntMaterial(derivedInt, 3, 0x434F4E53544A5345L, 0x4E554D494E543031L);
        AbstractInsnNode[] derivedIntInsns = derivedInt.toArray();
        assertTrue(isProtectedNumericMaterial(derivedIntInsns, 0, derivedIntInsns.length));
        assertNoDirectLargeProtectedNumericMaterial(derivedIntInsns, 0, derivedIntInsns.length);
        assertNoSelfCancelingDerivedNumericMaterial(derivedIntInsns, 0, derivedIntInsns.length);

        InsnList derivedLong = new InsnList();
        JvmPassBytecode.pushDerivedLongMaterial(derivedLong, 4, 0x434F4E53544A5346L, 0x4E554D4C4F4E4731L);
        AbstractInsnNode[] derivedLongInsns = derivedLong.toArray();
        assertTrue(isProtectedNumericMaterial(derivedLongInsns, 0, derivedLongInsns.length));
        assertNoDirectLargeProtectedNumericMaterial(derivedLongInsns, 0, derivedLongInsns.length);
        assertNoSelfCancelingDerivedNumericMaterial(derivedLongInsns, 0, derivedLongInsns.length);

        InsnList directLarge = new InsnList();
        directLarge.add(new LdcInsnNode(0x6A09E667));
        AbstractInsnNode[] directLargeInsns = directLarge.toArray();
        assertFalse(isProtectedNumericMaterial(directLargeInsns, 0, directLargeInsns.length));
        assertThrows(
            AssertionError.class,
            () -> assertNoDirectLargeProtectedNumericMaterial(directLargeInsns, 0, directLargeInsns.length)
        );

        InsnList xorSelfCancel = new InsnList();
        xorSelfCancel.add(new VarInsnNode(Opcodes.ILOAD, 2));
        JvmPassBytecode.pushInt(xorSelfCancel, 0x13579BDF);
        xorSelfCancel.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(xorSelfCancel, 0x13579BDF);
        xorSelfCancel.add(new InsnNode(Opcodes.IXOR));
        AbstractInsnNode[] xorSelfCancelInsns = xorSelfCancel.toArray();
        assertFalse(isProtectedNumericMaterial(xorSelfCancelInsns, 0, xorSelfCancelInsns.length));
        assertThrows(
            AssertionError.class,
            () -> assertNoSelfCancelingDerivedNumericMaterial(
                xorSelfCancelInsns,
                0,
                xorSelfCancelInsns.length
            )
        );

        InsnList inversePair = new InsnList();
        inversePair.add(new VarInsnNode(Opcodes.ILOAD, 2));
        JvmPassBytecode.pushInt(inversePair, 0x2468ACE0);
        inversePair.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(inversePair, 0x2468ACE0);
        inversePair.add(new InsnNode(Opcodes.ISUB));
        AbstractInsnNode[] inversePairInsns = inversePair.toArray();
        assertFalse(isProtectedNumericMaterial(inversePairInsns, 0, inversePairInsns.length));
        assertThrows(
            AssertionError.class,
            () -> assertNoSelfCancelingDerivedNumericMaterial(inversePairInsns, 0, inversePairInsns.length)
        );
    }

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
        assertProtectedIntegerDecodeMaterialIsDerived(outputJar);
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
                ConstantDecodeProof proof = constantDecodeProofAtRawStore(insns, i);
                if (proof != null) {
                    int limit = nextConstantRawStore(insns, i + 1, Math.min(insns.length, i + 760));
                    if (limit < 0) limit = Math.min(insns.length, i + 760);
                    int compactCall = firstCompactConstantDecodeCall(insns, i + 1, limit);
                    if (compactCall >= 0) {
                        assertTrue(
                            compactCallReceivesRuntimeDerivedMaterial(insns, compactCall, proof),
                            "compact constant decode call did not receive runtime-derived data material in " +
                                method.name + method.desc
                        );
                        checkedCompactCalls++;
                    } else {
                        assertTrue(
                            inlineDecodeReceivesRuntimeDerivedMaterial(insns, proof, limit),
                            "inline constant decode did not consume runtime-derived data material in " +
                                method.name + method.desc
                        );
                    }
                    checkedBaseLoads++;
                } else if (isCompactConstantDecodeCall(insns[i])) {
                    assertTrue(
                        compactCallReceivesRuntimeDerivedMaterial(
                            insns,
                            i,
                            previousConstantDecodeProof(insns, i)
                        ),
                        "compact constant decode call did not receive runtime-derived data material in " +
                            method.name + method.desc
                    );
                    checkedCompactCalls++;
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

    private void assertProtectedIntegerDecodeMaterialIsDerived(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("ConstantShapes");
        MethodNode ints = null;
        for (MethodNode method : clazz.asmNode().methods) {
            if ("ints".equals(method.name) && method.desc.endsWith(")I")) {
                ints = method;
                break;
            }
        }
        assertTrue(ints != null && ints.instructions != null, "constant fixture did not retain ints() method");

        AbstractInsnNode[] insns = ints.instructions.toArray();
        int checkedDecodeSlices = 0;
        int checkedDerivedWindows = 0;
        for (int i = 0; i < insns.length; i++) {
            assertFalse(insns[i].getOpcode() == Opcodes.IINC, "iinc survived protected integer replacement");
            ConstantDecodeProof proof = constantDecodeProofAtRawStore(insns, i);
            if (proof == null) continue;
            int limit = nextConstantRawStore(insns, i + 1, Math.min(insns.length, i + 900));
            if (limit < 0) limit = Math.min(insns.length, i + 900);
            List<DerivedMaterialWindow> windows = derivedIntMaterialWindows(
                insns,
                proof.rawBaseLocal,
                proof.rawStoreIndex + 1,
                limit
            );
            assertTrue(
                windows.size() >= 2,
                "protected integer decode slice did not contain enough live-derived material in ints()"
            );
            for (DerivedMaterialWindow window : windows) {
                assertTrue(
                    isProtectedNumericMaterial(insns, window.startInclusive, window.endExclusive),
                    "derived integer material window was not classified as protected"
                );
                assertNoDirectLargeProtectedNumericMaterial(insns, window.startInclusive, window.endExclusive);
                assertNoSelfCancelingDerivedNumericMaterial(insns, window.startInclusive, window.endExclusive);
                checkedDerivedWindows++;
            }
            checkedDecodeSlices++;
        }
        assertTrue(
            checkedDecodeSlices > 0,
            "constant fixture did not expose protected integer decode slices in ints()"
        );
        assertTrue(
            checkedDerivedWindows >= checkedDecodeSlices * 2,
            "protected integer decode material was not consistently live-derived"
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

    private int previousRealIndex(AbstractInsnNode[] insns, int fromExclusive) {
        for (int i = fromExclusive - 1; i >= 0; i--) {
            if (insns[i].getOpcode() >= 0) return i;
        }
        return -1;
    }

    private int nextRealIndex(AbstractInsnNode[] insns, int fromInclusive, int limitExclusive) {
        for (int i = fromInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i].getOpcode() >= 0) return i;
        }
        return -1;
    }

    private ConstantDecodeProof constantDecodeProofAtRawStore(AbstractInsnNode[] insns, int rawStoreIndex) {
        if (!(insns[rawStoreIndex] instanceof VarInsnNode rawStore)
            || rawStore.getOpcode() != Opcodes.ISTORE) {
            return null;
        }
        int dataStoreIndex = previousRealIndex(insns, rawStoreIndex);
        if (dataStoreIndex < 0) return null;
        if (!(insns[dataStoreIndex] instanceof VarInsnNode dataStore)
            || dataStore.getOpcode() != Opcodes.ISTORE
            || dataStore.var == rawStore.var) {
            return null;
        }
        int dataLoadIndex = previousRealIndex(insns, dataStoreIndex);
        if (dataLoadIndex < 0) return null;
        if (!(insns[dataLoadIndex] instanceof VarInsnNode dataLoad)
            || dataLoad.getOpcode() != Opcodes.ILOAD
            || dataLoad.var == rawStore.var) {
            return null;
        }
        int baseStoreIndex = previousRealIndex(insns, dataLoadIndex);
        if (baseStoreIndex < 0) return null;
        if (!(insns[baseStoreIndex] instanceof VarInsnNode baseStore)
            || baseStore.getOpcode() != Opcodes.ISTORE
            || baseStore.var == rawStore.var
            || baseStore.var == dataLoad.var) {
            return null;
        }
        int currentDataLoad = previousVarLoadIndex(
            insns,
            dataLoad.var,
            Math.max(0, baseStoreIndex - 120),
            baseStoreIndex
        );
        if (currentDataLoad < 0 || !hasNonlinearMixAfter(insns, currentDataLoad + 1, baseStoreIndex)) {
            return null;
        }
        return new ConstantDecodeProof(rawStore.var, dataLoad.var, rawStoreIndex);
    }

    private boolean isCompactConstantDecodeCall(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call
            && call.name.startsWith("__neko_num_i")
            && "(III)I".equals(call.desc);
    }

    private boolean compactCallReceivesRuntimeDerivedMaterial(
        AbstractInsnNode[] insns,
        int callIndex,
        ConstantDecodeProof proof
    ) {
        if (proof == null) return false;
        if (insns[callIndex] instanceof MethodInsnNode call && call.name.startsWith("__neko_num_ip")) {
            return protectedCompactCallReceivesRuntimeDerivedMaterial(insns, callIndex, proof);
        }
        int seedIndex = previousRealIndex(insns, callIndex);
        if (seedIndex < 0) return false;
        int swapIndex;
        if (intLiteral(insns[seedIndex]) != null) {
            swapIndex = previousRealIndex(insns, seedIndex);
        } else {
            int seedStart = previousDerivedIntMaterialStart(insns, proof.rawBaseLocal, callIndex, 32);
            if (seedStart < 0) return false;
            swapIndex = previousRealIndex(insns, seedStart);
        }
        if (swapIndex < 0 || insns[swapIndex].getOpcode() != Opcodes.SWAP) return false;
        MaterialNoiseProof noise = materialNoiseProof(insns, proof, swapIndex);
        if (noise == null) return false;
        int helperBoundStart = previousBoundBaseStart(insns, proof.rawBaseLocal, noise.local, swapIndex, 96);
        if (helperBoundStart < 0) return false;
        int materialEnd = previousRealIndex(insns, helperBoundStart);
        if (materialEnd < 0 || insns[materialEnd].getOpcode() != Opcodes.IXOR) return false;
        if (!materialSliceConsumesRuntimeBoundMask(insns, proof, noise, materialEnd)) {
            return false;
        }
        return boundBaseSliceConsumesNoise(insns, proof.rawBaseLocal, noise.local, helperBoundStart, swapIndex);
    }

    private boolean protectedCompactCallReceivesRuntimeDerivedMaterial(
        AbstractInsnNode[] insns,
        int callIndex,
        ConstantDecodeProof proof
    ) {
        int seedStart = previousDerivedIntMaterialStart(insns, proof.rawBaseLocal, callIndex, 32);
        if (seedStart < 0) return false;
        List<DerivedMaterialWindow> windows = derivedIntMaterialWindows(
            insns,
            proof.rawBaseLocal,
            proof.rawStoreIndex + 1,
            callIndex
        );
        if (windows.size() < 2 || windows.get(windows.size() - 1).startInclusive != seedStart) {
            return false;
        }
        int encryptedStart = windows.get(0).startInclusive;
        return varLoadCount(insns, proof.rawBaseLocal, proof.rawStoreIndex + 1, callIndex) >= 2
            && isProtectedNumericMaterial(insns, encryptedStart, callIndex)
            && selfCancelingNumericMaterialIndex(insns, encryptedStart, callIndex) < 0
            && noUncoveredLargeNumericLdc(
                insns,
                proof.rawStoreIndex + 1,
                callIndex,
                windows
            );
    }

    private boolean noUncoveredLargeNumericLdc(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive,
        List<DerivedMaterialWindow> windows
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof LdcInsnNode && isLargeNumericLiteral(insns[i]) && !coveredByWindow(i, windows)) {
                return false;
            }
        }
        return true;
    }

    private boolean coveredByWindow(int index, List<DerivedMaterialWindow> windows) {
        for (DerivedMaterialWindow window : windows) {
            if (index >= window.startInclusive && index < window.endExclusive) {
                return true;
            }
        }
        return false;
    }

    private boolean inlineDecodeReceivesRuntimeDerivedMaterial(
        AbstractInsnNode[] insns,
        ConstantDecodeProof proof,
        int limitExclusive
    ) {
        MaterialNoiseProof noise = materialNoiseProof(insns, proof, limitExclusive);
        if (noise == null) return false;
        List<Integer> boundStarts = boundBaseStarts(
            insns,
            proof.rawBaseLocal,
            noise.local,
            noise.storeIndex + 1,
            limitExclusive
        );
        if (boundStarts.size() < 6) return false;
        int materialEnd = previousRealIndex(insns, boundStarts.get(3));
        if (materialEnd < 0 || insns[materialEnd].getOpcode() != Opcodes.IXOR) return false;
        return materialSliceConsumesRuntimeBoundMask(insns, proof, noise, materialEnd)
            && boundBaseSliceConsumesNoise(
                insns,
                proof.rawBaseLocal,
                noise.local,
                boundStarts.get(3),
                boundStarts.get(4)
            )
            && boundBaseSliceConsumesNoise(
                insns,
                proof.rawBaseLocal,
                noise.local,
                boundStarts.get(4),
                boundStarts.get(5)
            )
            && boundBaseSliceConsumesNoise(
                insns,
                proof.rawBaseLocal,
                noise.local,
                boundStarts.get(5),
                limitExclusive
            )
            && hasOpcode(insns, Opcodes.ISUB, boundStarts.get(3), limitExclusive)
            && hasOpcode(insns, Opcodes.IOR, boundStarts.get(3), limitExclusive)
            && hasOpcode(insns, Opcodes.IXOR, boundStarts.get(5), limitExclusive);
    }

    private int firstCompactConstantDecodeCall(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (isCompactConstantDecodeCall(insns[i])) return i;
        }
        return -1;
    }

    private ConstantDecodeProof previousConstantDecodeProof(AbstractInsnNode[] insns, int limitExclusive) {
        int start = Math.max(0, limitExclusive - 760);
        for (int i = limitExclusive - 1; i >= start; i--) {
            ConstantDecodeProof proof = constantDecodeProofAtRawStore(insns, i);
            if (proof != null) return proof;
        }
        return null;
    }

    private int nextConstantRawStore(AbstractInsnNode[] insns, int startInclusive, int limitExclusive) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (constantDecodeProofAtRawStore(insns, i) != null) return i;
        }
        return -1;
    }

    private boolean hasOpcode(
        AbstractInsnNode[] insns,
        int opcode,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i].getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProtectedNumericMaterial(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        if (hasDirectLargeProtectedNumericMaterial(insns, startInclusive, limitExclusive)) {
            return false;
        }
        if (selfCancelingNumericMaterialIndex(insns, startInclusive, limitExclusive) >= 0) {
            return false;
        }
        boolean sawLiveLoad = false;
        boolean sawMultiply = false;
        boolean sawShift = false;
        boolean sawMix = false;
        int literals = 0;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn instanceof VarInsnNode var
                && (var.getOpcode() == Opcodes.ILOAD || var.getOpcode() == Opcodes.LLOAD)) {
                sawLiveLoad = true;
            }
            if (numericLiteralBits(insn) != null) {
                literals++;
            }
            int opcode = insn.getOpcode();
            sawMultiply |= opcode == Opcodes.IMUL || opcode == Opcodes.LMUL;
            sawShift |= opcode == Opcodes.IUSHR || opcode == Opcodes.LUSHR || opcode == Opcodes.LSHL;
            sawMix |= opcode == Opcodes.IXOR
                || opcode == Opcodes.LXOR
                || opcode == Opcodes.IADD
                || opcode == Opcodes.LADD
                || opcode == Opcodes.IOR
                || opcode == Opcodes.LOR;
        }
        return sawLiveLoad && sawMultiply && sawShift && sawMix && literals >= 3;
    }

    private static void assertNoDirectLargeProtectedNumericMaterial(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        assertFalse(
            hasDirectLargeProtectedNumericMaterial(insns, startInclusive, limitExclusive),
            "direct large protected numeric LdcInsnNode material survived"
        );
    }

    private static void assertNoSelfCancelingDerivedNumericMaterial(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        assertEquals(
            -1,
            selfCancelingNumericMaterialIndex(insns, startInclusive, limitExclusive),
            "self-canceling derived numeric material survived"
        );
    }

    private static boolean hasDirectLargeProtectedNumericMaterial(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        int realInsns = 0;
        int largeLdc = 0;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn.getOpcode() < 0) continue;
            realInsns++;
            if (insn instanceof LdcInsnNode && isLargeNumericLiteral(insn)) {
                largeLdc++;
            }
        }
        return realInsns == 1 && largeLdc == 1;
    }

    private static int selfCancelingNumericMaterialIndex(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        int limit = Math.min(limitExclusive, insns.length);
        for (int i = startInclusive; i < limit; i++) {
            Long first = numericLiteralBits(insns[i]);
            if (first == null) continue;
            int opAIndex = nextRealIndexStatic(insns, i + 1, limit);
            if (opAIndex < 0) continue;
            int literalBIndex = nextNumericLiteralIndex(insns, opAIndex + 1, Math.min(limit, opAIndex + 5));
            if (literalBIndex < 0) continue;
            Long second = numericLiteralBits(insns[literalBIndex]);
            if (!first.equals(second)) continue;
            int opBIndex = nextRealIndexStatic(insns, literalBIndex + 1, limit);
            if (opBIndex < 0) continue;
            if (isSelfCancelingPair(insns[opAIndex].getOpcode(), insns[opBIndex].getOpcode())) {
                return i;
            }
        }
        return -1;
    }

    private static int nextNumericLiteralIndex(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (numericLiteralBits(insns[i]) != null) return i;
        }
        return -1;
    }

    private static int nextRealIndexStatic(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i].getOpcode() >= 0) return i;
        }
        return -1;
    }

    private static boolean isSelfCancelingPair(int opcodeA, int opcodeB) {
        return (opcodeA == Opcodes.IXOR && opcodeB == Opcodes.IXOR)
            || (opcodeA == Opcodes.LXOR && opcodeB == Opcodes.LXOR)
            || (opcodeA == Opcodes.IADD && opcodeB == Opcodes.ISUB)
            || (opcodeA == Opcodes.ISUB && opcodeB == Opcodes.IADD)
            || (opcodeA == Opcodes.LADD && opcodeB == Opcodes.LSUB)
            || (opcodeA == Opcodes.LSUB && opcodeB == Opcodes.LADD);
    }

    private static boolean isLargeNumericLiteral(AbstractInsnNode insn) {
        Long bits = numericLiteralBits(insn);
        return bits != null && (bits > Short.MAX_VALUE || bits < Short.MIN_VALUE);
    }

    private static Long numericLiteralBits(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number value) {
            if (value instanceof Integer i) return i.longValue();
            if (value instanceof Long l) return l;
            if (value instanceof Float f) return (long) Float.floatToRawIntBits(f);
            if (value instanceof Double d) return Double.doubleToRawLongBits(d);
        }
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return (long) (opcode - Opcodes.ICONST_0);
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return (long) ((IntInsnNode) insn).operand;
        }
        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;
        return null;
    }

    private int previousVarLoadIndex(
        AbstractInsnNode[] insns,
        int local,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = limitExclusive - 1; i >= startInclusive && i >= 0; i--) {
            if (insns[i] instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ILOAD
                && load.var == local) {
                return i;
            }
        }
        return -1;
    }

    private MaterialNoiseProof materialNoiseProof(
        AbstractInsnNode[] insns,
        ConstantDecodeProof proof,
        int limitExclusive
    ) {
        int limit = Math.min(insns.length, limitExclusive);
        for (int i = proof.rawStoreIndex + 1; i < limit; i++) {
            if (!(insns[i] instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ISTORE) {
                continue;
            }
            if (store.var == proof.rawBaseLocal || store.var == proof.dataLocal) {
                continue;
            }
            if (storeHasNonlinearSpecificDataInput(
                insns,
                proof.rawStoreIndex + 1,
                i,
                store.var,
                proof.dataLocal
            )) {
                return new MaterialNoiseProof(store.var, i);
            }
        }
        return null;
    }

    private boolean storeHasNonlinearSpecificDataInput(
        AbstractInsnNode[] insns,
        int startInclusive,
        int storeIndex,
        int storedLocal,
        int dataLocal
    ) {
        boolean sawData = false;
        boolean sawMul = false;
        boolean sawShift = false;
        boolean sawXor = false;
        for (int i = startInclusive; i < storeIndex; i++) {
            if (insns[i] instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ILOAD
                && load.var == dataLocal) {
                sawData = true;
            }
            if (!sawData) continue;
            if (insns[i].getOpcode() == Opcodes.IMUL) {
                sawMul = true;
            } else if (insns[i].getOpcode() == Opcodes.IUSHR) {
                sawShift = true;
            } else if (insns[i].getOpcode() == Opcodes.IXOR) {
                sawXor = true;
            }
        }
        return sawData && sawMul && sawShift && sawXor
            && insns[storeIndex] instanceof VarInsnNode store
            && store.var == storedLocal;
    }

    private boolean materialSliceConsumesRuntimeBoundMask(
        AbstractInsnNode[] insns,
        ConstantDecodeProof proof,
        MaterialNoiseProof noise,
        int materialEnd
    ) {
        int materialBoundStart = previousBoundBaseStart(
            insns,
            proof.rawBaseLocal,
            noise.local,
            materialEnd,
            260
        );
        if (materialBoundStart <= noise.storeIndex) return false;
        return varLoadCount(insns, proof.rawBaseLocal, noise.storeIndex + 1, materialBoundStart) >= 1
            && boundBaseSliceConsumesNoise(insns, proof.rawBaseLocal, noise.local, materialBoundStart, materialEnd)
            && intLiteralCount(insns, noise.storeIndex + 1, materialEnd + 1) >= 10
            && hasOpcode(insns, Opcodes.IADD, noise.storeIndex + 1, materialEnd + 1)
            && hasOpcode(insns, Opcodes.IMUL, noise.storeIndex + 1, materialEnd + 1)
            && hasOpcode(insns, Opcodes.IXOR, noise.storeIndex + 1, materialEnd + 1);
    }

    private int firstBoundBaseStart(
        AbstractInsnNode[] insns,
        int rawBaseLocal,
        int noiseLocal,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (isBoundBaseStart(insns, i, rawBaseLocal, noiseLocal, limitExclusive)) return i;
        }
        return -1;
    }

    private List<Integer> boundBaseStarts(
        AbstractInsnNode[] insns,
        int rawBaseLocal,
        int noiseLocal,
        int startInclusive,
        int limitExclusive
    ) {
        List<Integer> starts = new ArrayList<>();
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (isBoundBaseStart(insns, i, rawBaseLocal, noiseLocal, limitExclusive)) {
                starts.add(i);
            }
        }
        return starts;
    }

    private int previousBoundBaseStart(
        AbstractInsnNode[] insns,
        int rawBaseLocal,
        int noiseLocal,
        int fromExclusive,
        int maxDistance
    ) {
        int start = Math.max(0, fromExclusive - maxDistance);
        for (int i = fromExclusive - 1; i >= start; i--) {
            if (isBoundBaseStart(insns, i, rawBaseLocal, noiseLocal, fromExclusive)) return i;
        }
        return -1;
    }

    private boolean isBoundBaseStart(
        AbstractInsnNode[] insns,
        int index,
        int rawBaseLocal,
        int noiseLocal,
        int limitExclusive
    ) {
        if (!(insns[index] instanceof VarInsnNode rawLoad)
            || rawLoad.getOpcode() != Opcodes.ILOAD
            || rawLoad.var != rawBaseLocal) {
            return false;
        }
        int next = nextRealIndex(insns, index + 1, limitExclusive);
        if (next < 0) return false;
        if (!(insns[next] instanceof VarInsnNode noiseLoad)
            || noiseLoad.getOpcode() != Opcodes.ILOAD
            || noiseLoad.var != noiseLocal) {
            return false;
        }
        int localLimit = Math.min(limitExclusive, index + 42);
        return hasOpcode(insns, Opcodes.IMUL, next + 1, localLimit)
            && hasOpcode(insns, Opcodes.IUSHR, next + 1, localLimit)
            && hasOpcode(insns, Opcodes.IADD, next + 1, localLimit)
            && hasOpcode(insns, Opcodes.IXOR, next + 1, localLimit)
            && varLoadCount(insns, noiseLocal, index, localLimit) >= 2;
    }

    private boolean boundBaseSliceConsumesNoise(
        AbstractInsnNode[] insns,
        int rawBaseLocal,
        int noiseLocal,
        int startInclusive,
        int limitExclusive
    ) {
        return isBoundBaseStart(insns, startInclusive, rawBaseLocal, noiseLocal, limitExclusive)
            && varLoadCount(insns, noiseLocal, startInclusive, limitExclusive) >= 2
            && hasOpcode(insns, Opcodes.IMUL, startInclusive, limitExclusive)
            && hasOpcode(insns, Opcodes.IUSHR, startInclusive, limitExclusive)
            && hasOpcode(insns, Opcodes.IXOR, startInclusive, limitExclusive);
    }

    private int varLoadCount(
        AbstractInsnNode[] insns,
        int local,
        int startInclusive,
        int limitExclusive
    ) {
        int count = 0;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ILOAD
                && load.var == local) {
                count++;
            }
        }
        return count;
    }

    private int intLiteralCount(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        int count = 0;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (intLiteral(insns[i]) != null) {
                count++;
            }
        }
        return count;
    }

    private List<DerivedMaterialWindow> derivedIntMaterialWindows(
        AbstractInsnNode[] insns,
        int liveLocal,
        int startInclusive,
        int limitExclusive
    ) {
        List<DerivedMaterialWindow> windows = new ArrayList<>();
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            int end = derivedIntMaterialEnd(insns, i, liveLocal, limitExclusive);
            if (end >= 0) {
                windows.add(new DerivedMaterialWindow(i, end));
                i = end - 1;
            }
        }
        return windows;
    }

    private int derivedIntMaterialEnd(
        AbstractInsnNode[] insns,
        int startIndex,
        int liveLocal,
        int limitExclusive
    ) {
        if (!(insns[startIndex] instanceof VarInsnNode liveLoad)
            || liveLoad.getOpcode() != Opcodes.ILOAD
            || liveLoad.var != liveLocal) {
            return -1;
        }
        int cursor = nextRealIndex(insns, startIndex + 1, limitExclusive);
        if (cursor < 0 || numericLiteralBits(insns[cursor]) == null) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IXOR) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.DUP) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || numericLiteralBits(insns[cursor]) == null) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IUSHR) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IXOR) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || numericLiteralBits(insns[cursor]) == null) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IMUL) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.DUP) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || numericLiteralBits(insns[cursor]) == null) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IUSHR) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IADD) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || numericLiteralBits(insns[cursor]) == null) return -1;
        cursor = nextRealIndex(insns, cursor + 1, limitExclusive);
        if (cursor < 0 || insns[cursor].getOpcode() != Opcodes.IXOR) return -1;
        return cursor + 1;
    }

    private int previousDerivedIntMaterialStart(
        AbstractInsnNode[] insns,
        int liveLocal,
        int fromExclusive,
        int maxDistance
    ) {
        int start = Math.max(0, fromExclusive - maxDistance);
        for (int i = fromExclusive - 1; i >= start; i--) {
            int end = derivedIntMaterialEnd(insns, i, liveLocal, fromExclusive);
            if (end < 0) continue;
            if (nextRealIndex(insns, end, fromExclusive) < 0) {
                return i;
            }
        }
        return -1;
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

    private static final class ConstantDecodeProof {
        private final int rawBaseLocal;
        private final int dataLocal;
        private final int rawStoreIndex;

        private ConstantDecodeProof(int rawBaseLocal, int dataLocal, int rawStoreIndex) {
            this.rawBaseLocal = rawBaseLocal;
            this.dataLocal = dataLocal;
            this.rawStoreIndex = rawStoreIndex;
        }
    }

    private static final class MaterialNoiseProof {
        private final int local;
        private final int storeIndex;

        private MaterialNoiseProof(int local, int storeIndex) {
            this.local = local;
            this.storeIndex = storeIndex;
        }
    }

    private static final class DerivedMaterialWindow {
        private final int startInclusive;
        private final int endExclusive;

        private DerivedMaterialWindow(int startInclusive, int endExclusive) {
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
        }
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
