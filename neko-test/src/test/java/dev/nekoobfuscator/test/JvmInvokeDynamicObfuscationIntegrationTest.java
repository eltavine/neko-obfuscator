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
import java.util.LinkedHashSet;
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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class JvmInvokeDynamicObfuscationIntegrationTest {
    private static final int INDY_MATERIAL_SLOT = 67;
    private static final int INDY_MATERIAL_SELECTOR_SLOT = 71;
    private static final int CLASS_KEY_WORDS_SLOT = 65;
    private static final int CLASS_KEY_WORDS_SELECTOR_SLOT = 73;
    private static final int INDY_CACHE_SLOT = 74;
    private static final String RESOLVER_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OLD_RESOLVER_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/String;I[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String FLOW_DESC = "(IIIII[Ljava/lang/Object;IJ)J";
    private static final String OLD_FLOW_THIN_DESC = "(IIII[Ljava/lang/Object;IJ)J";
    private static final String OLD_FLOW_STATE_DESC = "(IIII[Ljava/lang/Object;IJI)J";
    private static final int INDY_CELL_SPAN_SHIFT = 0;
    private static final int INDY_CELL_STRIDE_SHIFT = 8;
    private static final int INDY_CELL_OFFSET_SHIFT = 16;
    private static final int INDY_CELL_LAYOUT_SHIFT = 24;
    private static final long INDY_CELL_BYTE_MASK = 0xFFL;
    private static final int FLOW_SLOT_LOCAL = 6;
    private static final int RESOLVER_SLOT_LOCAL = 4;
    private static final int RESOLVER_FLOW_LOCAL = 12;
    private static final int RESOLVER_EPOCH_LOCAL = 39;
    private static final int RESOLVER_SEED_LOCAL = 43;
    private static final int RESOLVER_SALT_LOCAL = 45;
    private static final int RESOLVER_DESCRIPTOR_LOCAL = 50;
    private static final int RESOLVER_HELPER_KEY_LOCAL = 52;

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
        assertIndySitesHaveIndependentMaterialLayouts(outputJar);
        assertIndyResolverSeedsArePerSiteDerived(outputJar);
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
        boolean sawInlineResolverDecode = false;
        boolean sawFlowHelperLoadsIndyMaterialSelectorSlot = false;
        boolean sawFlowHelperDirectIndyMaterialSlotLoad = false;
        boolean sawFlowHelperLoadsClassKeySelectorSlot = false;
        boolean sawFlowHelperDirectClassKeySlotLoad = false;
        boolean sawResolverNewDesc = false;
        boolean sawResolverOldDesc = false;
        boolean sawResolverLoadsCacheSlot = false;
        boolean sawResolverGetsStaticCarrier = false;
        boolean sawResolverGetsStaticCache = false;
        boolean sawResolverSetTarget = false;
        boolean sawResolverGuardWithTest = false;
        boolean sawResolverPrimitiveGuardHandle = false;
        boolean sawResolverBoxedEqualsGuard = false;
        boolean sawResolverGetTarget = false;
        boolean sawFlowThinDesc = false;
        boolean sawFlowOldThinDesc = false;
        boolean sawFlowOldStateDesc = false;
        boolean sawFlowHelperConsumesDataWord = false;
        int checkedDataBoundFlowCalls = 0;
        boolean sawCacheField = false;
        boolean sawMixCall = false;
        int indySites = 0;
        Set<String> bootstrapHelpers = new LinkedHashSet<>();
        Map<String, Integer> helperCounts = new LinkedHashMap<>();
        for (String helper : List.of(
            "__neko_indy_bsm",
            "__neko_indy_resolve",
            "__neko_indy_flow",
            "__neko_indy_guard"
        )) {
            helperCounts.put(helper, 0);
        }
        for (var field : clazz.asmNode().fields) {
            if ("Ljava/util/concurrent/ConcurrentHashMap;".equals(field.desc)) {
                sawCacheField = true;
            }
        }
        for (var method : clazz.asmNode().methods) {
            for (String helper : helperCounts.keySet()) {
                if (method.name.startsWith(helper)) {
                    helperCounts.put(helper, helperCounts.get(helper) + 1);
                }
            }
            assertFalse(method.name.startsWith("__neko_indy_mix"), "indy mix helper should be inlined");
            assertFalse(method.name.startsWith("__neko_indy_decode"), "indy decode helper should be inlined into resolver");
            assertFalse(method.name.startsWith("__neko_indy_filter_methods"), "indy Method[] filter helper should be inlined into resolver");
            assertFalse(method.name.startsWith("__neko_indy_filter_fields"), "indy Field[] filter helper should be inlined into resolver");
            if (method.name.startsWith("__neko_indy_resolve")) {
                sawResolverNewDesc |= RESOLVER_DESC.equals(method.desc);
                sawResolverOldDesc |= OLD_RESOLVER_DESC.equals(method.desc);
                sawInlineResolverDecode |= resolverInlinesDecode(method);
                sawResolverLoadsCacheSlot |= methodLoadsMaterialSlot(method, INDY_CACHE_SLOT);
                sawResolverGetsStaticCarrier |= methodGetsStatic(method, "[Ljava/lang/Object;");
                sawResolverGetsStaticCache |= methodGetsStatic(method, "Ljava/util/concurrent/ConcurrentHashMap;");
                sawResolverSetTarget |= methodCalls(
                    method,
                    "java/lang/invoke/MutableCallSite",
                    "setTarget",
                    "(Ljava/lang/invoke/MethodHandle;)V"
                );
                sawResolverGuardWithTest |= methodCalls(
                    method,
                    "java/lang/invoke/MethodHandles",
                    "guardWithTest",
                    "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;"
                );
                sawResolverPrimitiveGuardHandle |= methodLoadsHandle(
                    method,
                    clazz.name(),
                    "__neko_indy_guard",
                    "(JJ)Z"
                );
                sawResolverBoxedEqualsGuard |= methodLoadsString(method, "equals");
                sawResolverGetTarget |= methodCalls(
                    method,
                    "java/lang/invoke/CallSite",
                    "getTarget",
                    "()Ljava/lang/invoke/MethodHandle;"
                );
            }
            if (method.name.startsWith("__neko_indy_flow")) {
                sawFlowThinDesc |= FLOW_DESC.equals(method.desc);
                sawFlowOldThinDesc |= OLD_FLOW_THIN_DESC.equals(method.desc);
                sawFlowOldStateDesc |= OLD_FLOW_STATE_DESC.equals(method.desc);
                sawFlowHelperConsumesDataWord |= flowHelperConsumesDataWord(method);
                sawFlowHelperLoadsIndyMaterialSelectorSlot |= methodLoadsMaterialSlot(method, INDY_MATERIAL_SELECTOR_SLOT);
                sawFlowHelperDirectIndyMaterialSlotLoad |= methodLoadsMaterialSlot(method, INDY_MATERIAL_SLOT);
                sawFlowHelperLoadsClassKeySelectorSlot |= methodLoadsMaterialSlot(method, CLASS_KEY_WORDS_SELECTOR_SLOT);
                sawFlowHelperDirectClassKeySlotLoad |= methodLoadsMaterialSlot(method, CLASS_KEY_WORDS_SLOT);
            }
            if (method.instructions == null || method.name.startsWith("__neko_")) continue;
            AbstractInsnNode[] insns = method.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                AbstractInsnNode insn = insns[i];
                if (insn instanceof InvokeDynamicInsnNode indy
                    && indy.bsm != null
                    && "IndyReferenceShapes".equals(indy.bsm.getOwner())) {
                    sawIndy = true;
                    indySites++;
                    bootstrapHelpers.add(indy.bsm.getName());
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
                    && "(JJ)J".equals(call.desc)) {
                    sawMixCall = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "IndyReferenceShapes".equals(call.owner)
                    && call.name.startsWith("__neko_indy_flow")
                    && FLOW_DESC.equals(call.desc)) {
                    assertTrue(
                        indyFlowCallReceivesDataDigest(insns, i),
                        "indy flow helper call should receive a dataLocal-derived transport word in " +
                            method.name + method.desc
                    );
                    checkedDataBoundFlowCalls++;
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
        assertTrue(indySites > 1, "fixture should exercise multiple indy sites");
        assertTrue(checkedDataBoundFlowCalls > 0, "indy replacement should pass dataLocal-derived flow transport material");
        assertEquals(1, bootstrapHelpers.size(), "indy sites should share one per-class bootstrap helper");
        for (var entry : helperCounts.entrySet()) {
            assertEquals(1, entry.getValue(), "indy helper should be per-class, not per-site: " + entry.getKey());
        }
        assertTrue(sawResolverNewDesc, "indy resolver should use the carrier-bound ABI");
        assertFalse(sawResolverOldDesc, "old indy resolver ABI without bound carrier should be absent");
        assertTrue(sawFlowThinDesc, "indy flow helper should accept dataLocal-derived transport material");
        assertFalse(sawFlowOldThinDesc, "old indy flow helper ABI without data transport should be absent");
        assertFalse(sawFlowOldStateDesc, "old indy flow helper ABI with call-site state token should be absent");
        assertTrue(sawFlowHelperConsumesDataWord, "indy flow helper should consume dataLocal-derived material");
        assertFalse(sawCacheField, "indy cache should live in the CFF object carrier, not a separate ConcurrentHashMap field");
        assertTrue(sawResolverLoadsCacheSlot, "indy resolver should load cache from the CFF carrier slot");
        assertFalse(sawResolverGetsStaticCarrier, "indy resolver should not load the CFF carrier through GETSTATIC");
        assertFalse(sawResolverGetsStaticCache, "indy resolver should not load a separate cache field through GETSTATIC");
        assertTrue(sawResolverSetTarget, "indy resolver should install a live-flow guarded call-site target");
        assertTrue(sawResolverGuardWithTest, "indy resolver setTarget should be guarded by live flow");
        assertTrue(sawResolverPrimitiveGuardHandle, "indy resolver should use primitive guard helper for live flow");
        assertFalse(sawResolverBoxedEqualsGuard, "indy resolver should not build boxed Long.equals guard");
        assertFalse(sawResolverGetTarget, "indy resolver should not chain guarded call-site targets");
        assertTrue(sawInlineResolverDecode, "indy resolver should inline payload decode");
        assertTrue(sawFlowHelperLoadsIndyMaterialSelectorSlot, "indy flow helper should load a material selector from the passed CFF object carrier");
        assertFalse(sawFlowHelperDirectIndyMaterialSlotLoad, "indy flow helper should not directly load the fixed material slot");
        assertTrue(sawFlowHelperLoadsClassKeySelectorSlot, "indy flow helper should load class-key selector from the CFF object carrier");
        assertFalse(sawFlowHelperDirectClassKeySlotLoad, "indy flow helper should not directly load the fixed class-key slot");
        assertFalse(sawMixCall, "indy mix helper calls should be inlined");
        assertTrue(sawLongLongDescriptor, "indy reference sites should carry keyed long arguments");
    }

    private void assertIndySitesHaveIndependentMaterialLayouts(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        var clazz = input.classMap().get("IndyReferenceShapes");
        int indySites = 0;
        MethodNode flowHelper = null;
        MethodNode resolverHelper = null;
        Set<String> layoutFingerprints = new LinkedHashSet<>();
        int materialCells = 0;
        for (var method : clazz.asmNode().methods) {
            if (method.name.startsWith("__neko_indy_flow")) {
                flowHelper = method;
            }
            if (method.name.startsWith("__neko_indy_resolve")) {
                resolverHelper = method;
            }
            for (long[] cell : literalLongArrays(method)) {
                if (!isIndyMaterialCell(cell)) continue;
                long descriptor = cell[0];
                int layoutId = descriptorByte(descriptor, INDY_CELL_LAYOUT_SHIFT);
                long fingerprint = descriptor >>> 32;
                layoutFingerprints.add(layoutId + ":" + Long.toUnsignedString(fingerprint));
                materialCells++;
            }
            if (method.instructions == null || method.name.startsWith("__neko_")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof InvokeDynamicInsnNode indy
                    && indy.bsm != null
                    && "IndyReferenceShapes".equals(indy.bsm.getOwner())) {
                    indySites++;
                }
            }
        }
        assertTrue(indySites > 1, "fixture should exercise multiple indy sites");
        assertTrue(materialCells >= indySites, "indy material should be emitted as per-site cells");
        assertTrue(
            layoutFingerprints.size() >= indySites,
            "indy sites should not share one recoverable layout/fingerprint: " + layoutFingerprints
        );
        assertTrue(flowHelper != null, "flow helper missing");
        assertTrue(resolverHelper != null, "resolver helper missing");
        assertTrue(methodUsesVariableIndyMaterialIndex(flowHelper), "flow helper should derive material indexes from descriptors");
        assertTrue(methodUsesVariableIndyMaterialIndex(resolverHelper), "resolver helper should derive material indexes from descriptors");
        assertFalse(
            fixedSlotStrideMultiply(flowHelper, FLOW_SLOT_LOCAL, 4),
            "flow helper should not index flow material with one global stride"
        );
        assertFalse(
            fixedSlotStrideMultiply(resolverHelper, RESOLVER_SLOT_LOCAL, 3),
            "resolver helper should not index resolver material with one global stride"
        );
    }

    private void assertIndyResolverSeedsArePerSiteDerived(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        var clazz = input.classMap().get("IndyReferenceShapes");
        MethodNode resolverHelper = null;
        for (var method : clazz.asmNode().methods) {
            if (method.name.startsWith("__neko_indy_resolve")) {
                resolverHelper = method;
                break;
            }
        }
        assertTrue(resolverHelper != null, "resolver helper missing");
        AbstractInsnNode[] insns = resolverHelper.instructions.toArray();
        int firstDecode = firstCall(insns, "java/lang/String", "toCharArray", "()[C");
        int seedStore = firstVarStore(insns, Opcodes.LSTORE, RESOLVER_SEED_LOCAL, 0);
        int saltStore = firstVarStore(insns, Opcodes.LSTORE, RESOLVER_SALT_LOCAL, seedStore + 1);
        int firstSeedUse = firstVarLoad(insns, Opcodes.LLOAD, RESOLVER_SEED_LOCAL, seedStore + 1);
        int firstSaltUse = firstVarLoad(insns, Opcodes.LLOAD, RESOLVER_SALT_LOCAL, saltStore + 1);

        assertTrue(firstDecode > 0, "resolver payload decode missing");
        assertTrue(seedStore > 0 && seedStore < firstDecode, "seed should be reconstructed before payload decode");
        assertTrue(saltStore > seedStore && saltStore < firstDecode, "salt should be reconstructed before payload decode");
        assertTrue(firstSeedUse > seedStore, "seed should be used after reconstruction");
        assertTrue(firstSaltUse > saltStore, "salt should be used after reconstruction");
        assertResolverSeedSlice(insns, 0, seedStore, "seed");
        assertResolverSeedSlice(insns, seedStore + 1, saltStore, "salt");
        assertTrue(
            hiddenIndyArgumentsAreMethodKeyAndFlow(clazz.asmNode().methods),
            "indy callsites should pass live method key before the final flow guard word"
        );
    }

    private static void assertResolverSeedSlice(AbstractInsnNode[] insns, int start, int limit, String label) {
        assertTrue(
            sliceContainsVarLoad(insns, Opcodes.LLOAD, RESOLVER_FLOW_LOCAL, start, limit),
            label + " reconstruction should load the final flow word"
        );
        assertTrue(
            sliceContainsVarLoad(insns, Opcodes.LLOAD, RESOLVER_EPOCH_LOCAL, start, limit),
            label + " reconstruction should load resolver epoch"
        );
        assertTrue(
            sliceContainsVarLoad(insns, Opcodes.LLOAD, RESOLVER_HELPER_KEY_LOCAL, start, limit),
            label + " reconstruction should load the hidden helper method key"
        );
        assertTrue(
            sliceContainsDescriptorFingerprint(insns, start, limit),
            label + " reconstruction should load the layout fingerprint"
        );
    }

    private static boolean hiddenIndyArgumentsAreMethodKeyAndFlow(List<MethodNode> methods) {
        int checked = 0;
        for (MethodNode method : methods) {
            if (method.instructions == null || method.name.startsWith("__neko_")) continue;
            AbstractInsnNode[] insns = method.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof InvokeDynamicInsnNode indy)
                    || indy.bsm == null
                    || !"IndyReferenceShapes".equals(indy.bsm.getOwner())) {
                    continue;
                }
                Type[] args = Type.getArgumentTypes(indy.desc);
                if (args.length < 2
                    || args[args.length - 2].getSort() != Type.LONG
                    || args[args.length - 1].getSort() != Type.LONG) {
                    return false;
                }
                int flowCall = previousFlowHelperCall(insns, i);
                if (flowCall < 0) return false;
                if (!sliceContainsOpcodeVarLoad(insns, Opcodes.LLOAD, Math.max(0, flowCall - 48), flowCall)) {
                    return false;
                }
                checked++;
            }
        }
        return checked > 0;
    }

    private static boolean methodLoadsMaterialSlot(MethodNode method, int slot) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof IntInsnNode intInsn
                && intInsn.operand == slot
                && insn.getNext() != null
                && insn.getNext().getOpcode() == Opcodes.AALOAD) {
                return true;
            }
        }
        return false;
    }

    private static List<long[]> literalLongArrays(MethodNode method) {
        List<long[]> arrays = new ArrayList<>();
        if (method.instructions == null) return arrays;
        AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            Integer length = pushedIntValue(insns[i]);
            if (length == null || length < 0) continue;
            int newArray = nextInstructionIndex(insns, i + 1);
            if (newArray < 0
                || !(insns[newArray] instanceof IntInsnNode intInsn)
                || intInsn.getOpcode() != Opcodes.NEWARRAY
                || intInsn.operand != Opcodes.T_LONG) {
                continue;
            }
            long[] values = new long[length];
            boolean[] seen = new boolean[length];
            int cursor = nextInstructionIndex(insns, newArray + 1);
            int writes = 0;
            while (writes < length && cursor >= 0 && insns[cursor].getOpcode() == Opcodes.DUP) {
                int indexInsn = nextInstructionIndex(insns, cursor + 1);
                int valueInsn = nextInstructionIndex(insns, indexInsn + 1);
                int storeInsn = nextInstructionIndex(insns, valueInsn + 1);
                if (indexInsn < 0 || valueInsn < 0 || storeInsn < 0) break;
                Integer index = pushedIntValue(insns[indexInsn]);
                Long value = pushedLongValue(insns[valueInsn]);
                if (index == null || value == null || insns[storeInsn].getOpcode() != Opcodes.LASTORE) break;
                if (index < 0 || index >= length || seen[index]) break;
                values[index] = value;
                seen[index] = true;
                writes++;
                cursor = nextInstructionIndex(insns, storeInsn + 1);
            }
            if (writes == length) {
                arrays.add(values);
            }
        }
        return arrays;
    }

    private static boolean isIndyMaterialCell(long[] cell) {
        if (cell.length < 7) return false;
        long descriptor = cell[0];
        int span = descriptorByte(descriptor, INDY_CELL_SPAN_SHIFT);
        int stride = descriptorByte(descriptor, INDY_CELL_STRIDE_SHIFT);
        int offset = descriptorByte(descriptor, INDY_CELL_OFFSET_SHIFT);
        int layoutId = descriptorByte(descriptor, INDY_CELL_LAYOUT_SHIFT);
        long fingerprint = descriptor >>> 32;
        if (span != cell.length - 1 || stride <= 0 || stride >= span || offset >= span) return false;
        if (layoutId == 0 || layoutId > 0x7E || fingerprint == 0L) return false;
        Set<Integer> physical = new LinkedHashSet<>();
        for (int logical = 0; logical < 3; logical++) {
            int index = 1 + Math.floorMod(logical * stride + offset, span);
            if (index <= 0 || index >= cell.length || !physical.add(index)) return false;
        }
        return true;
    }

    private static int descriptorByte(long descriptor, int shift) {
        return (int) ((descriptor >>> shift) & INDY_CELL_BYTE_MASK);
    }

    private static boolean methodUsesVariableIndyMaterialIndex(MethodNode method) {
        if (method.instructions == null) return false;
        AbstractInsnNode[] insns = method.instructions.toArray();
        return hasOpcode(insns, Opcodes.IREM, 0, insns.length)
            && hasOpcode(insns, Opcodes.LUSHR, 0, insns.length)
            && hasOpcode(insns, Opcodes.LALOAD, 0, insns.length);
    }

    private static boolean fixedSlotStrideMultiply(MethodNode method, int slotLocal, int stride) {
        if (method.instructions == null) return false;
        AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ILOAD
                || load.var != slotLocal) {
                continue;
            }
            int strideInsn = nextInstructionIndex(insns, i + 1);
            int multiplyInsn = nextInstructionIndex(insns, strideInsn + 1);
            if (strideInsn >= 0
                && multiplyInsn >= 0
                && Integer.valueOf(stride).equals(pushedIntValue(insns[strideInsn]))
                && insns[multiplyInsn].getOpcode() == Opcodes.IMUL) {
                return true;
            }
        }
        return false;
    }

    private static boolean sliceContainsVarLoad(
        AbstractInsnNode[] insns,
        int opcode,
        int local,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode load && load.getOpcode() == opcode && load.var == local) {
                return true;
            }
        }
        return false;
    }

    private static boolean sliceContainsOpcodeVarLoad(
        AbstractInsnNode[] insns,
        int opcode,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode load && load.getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    private static boolean sliceContainsDescriptorFingerprint(
        AbstractInsnNode[] insns,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.LLOAD
                || load.var != RESOLVER_DESCRIPTOR_LOCAL) {
                continue;
            }
            int shiftIndex = nextInstructionIndex(insns, i + 1);
            int ushrIndex = nextInstructionIndex(insns, shiftIndex + 1);
            if (shiftIndex >= 0
                && ushrIndex >= 0
                && Integer.valueOf(32).equals(pushedIntValue(insns[shiftIndex]))
                && insns[ushrIndex].getOpcode() == Opcodes.LUSHR) {
                return true;
            }
        }
        return false;
    }

    private static int previousFlowHelperCall(AbstractInsnNode[] insns, int index) {
        for (int i = index - 1; i >= 0 && i >= index - 96; i--) {
            if (insns[i] instanceof MethodInsnNode call
                && "IndyReferenceShapes".equals(call.owner)
                && call.name.startsWith("__neko_indy_flow")
                && FLOW_DESC.equals(call.desc)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean methodGetsStatic(MethodNode method, String desc) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC
                && desc.equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodCalls(MethodNode method, String owner, String name, String desc) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                && owner.equals(call.owner)
                && name.equals(call.name)
                && desc.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodLoadsHandle(MethodNode method, String owner, String namePrefix, String desc) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc
                && ldc.cst instanceof Handle handle
                && owner.equals(handle.getOwner())
                && handle.getName().startsWith(namePrefix)
                && desc.equals(handle.getDesc())) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodLoadsString(MethodNode method, String value) {
        if (method.instructions == null) return false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                return true;
            }
        }
        return false;
    }

    private static boolean resolverInlinesDecode(MethodNode method) {
        boolean sawToCharArray = false;
        boolean sawStringFromChars = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/String".equals(call.owner)
                && "toCharArray".equals(call.name)
                && "()[C".equals(call.desc)) {
                sawToCharArray = true;
            }
            if (insn instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL
                && "java/lang/String".equals(call.owner)
                && "<init>".equals(call.name)
                && "([C)V".equals(call.desc)) {
                sawStringFromChars = true;
            }
        }
        return sawToCharArray && sawStringFromChars;
    }

    private static boolean indyFlowCallReceivesDataDigest(AbstractInsnNode[] insns, int callIndex) {
        int start = Math.max(0, callIndex - 32);
        int consecutiveIntLoads = 0;
        boolean sawFiveLiveIntLoads = false;
        boolean sawCarrierLoad = false;
        boolean sawSlotPush = false;
        boolean sawMethodKeyLoad = false;
        for (int i = start; i < callIndex; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
                consecutiveIntLoads++;
                if (consecutiveIntLoads >= 5) {
                    sawFiveLiveIntLoads = true;
                }
            } else if (insn.getOpcode() >= 0) {
                consecutiveIntLoads = 0;
            }
            if (sawFiveLiveIntLoads && insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                sawCarrierLoad = true;
            }
            if (sawCarrierLoad && isIntPush(insn)) {
                sawSlotPush = true;
            }
            if (sawSlotPush && insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.LLOAD) {
                sawMethodKeyLoad = true;
            }
        }
        return sawFiveLiveIntLoads && sawCarrierLoad && sawSlotPush && sawMethodKeyLoad;
    }

    private static boolean flowHelperConsumesDataWord(MethodNode method) {
        if (method.instructions == null) return false;
        AbstractInsnNode[] insns = method.instructions.toArray();
        int dataWordLocal = 4;
        int flowSlotLocal = 6;
        return varLoadCount(insns, dataWordLocal, 0, insns.length) >= 3
            && varLoadCount(insns, flowSlotLocal, 0, insns.length) >= 3
            && hasOpcode(insns, Opcodes.IMUL, 0, insns.length)
            && hasOpcode(insns, Opcodes.IUSHR, 0, insns.length)
            && hasOpcode(insns, Opcodes.IXOR, 0, insns.length);
    }

    private static boolean isIntPush(AbstractInsnNode insn) {
        return pushedIntValue(insn) != null;
    }

    private static Integer pushedIntValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode intInsn && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
            return intInsn.operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
            return value;
        }
        return null;
    }

    private static Long pushedLongValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long value) {
            return value;
        }
        return null;
    }

    private static int firstCall(AbstractInsnNode[] insns, String owner, String name, String desc) {
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] instanceof MethodInsnNode call
                && owner.equals(call.owner)
                && name.equals(call.name)
                && desc.equals(call.desc)) {
                return i;
            }
        }
        return -1;
    }

    private static int firstVarStore(AbstractInsnNode[] insns, int opcode, int local, int startInclusive) {
        for (int i = Math.max(0, startInclusive); i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode store && store.getOpcode() == opcode && store.var == local) {
                return i;
            }
        }
        return -1;
    }

    private static int firstVarLoad(AbstractInsnNode[] insns, int opcode, int local, int startInclusive) {
        for (int i = Math.max(0, startInclusive); i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode load && load.getOpcode() == opcode && load.var == local) {
                return i;
            }
        }
        return -1;
    }

    private static int nextInstructionIndex(AbstractInsnNode[] insns, int start) {
        for (int i = Math.max(0, start); i < insns.length; i++) {
            if (insns[i].getOpcode() >= 0) return i;
        }
        return -1;
    }

    private static boolean hasOpcode(AbstractInsnNode[] insns, int opcode, int startInclusive, int limitExclusive) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i].getOpcode() == opcode) return true;
        }
        return false;
    }

    private static int varLoadCount(AbstractInsnNode[] insns, int local, int startInclusive, int limitExclusive) {
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
