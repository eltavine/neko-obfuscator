package dev.nekoobfuscator.transforms.jvm.constants;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import dev.nekoobfuscator.transforms.jvm.internal.JvmCodeSizeEstimator;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Numeric constant protection that is tied to CFF's live key locals.
 */
public final class JvmConstantObfuscationPass implements TransformPass {
    public static final String ID = "constantObfuscation";
    private static final String NUMERIC_HELPERS = "constantObfuscation.numericHelpers";
    private static final String PROTECTED_NUMERIC_HELPERS = "constantObfuscation.protectedNumericHelpers";
    private static final int COMPACT_SITE_THRESHOLD = 32;
    private static final int COMPACT_SIZE_PRESSURE = 18_000;
    private static final int GENERATED_HELPER_HARDENING_SIZE_PRESSURE = 12_000;
    private static final int SITE_MIX_A = 0x4F1BBCDC;
    private static final int SITE_MIX_B = 0x2C9277B5;
    private static final int SITE_MIX_C = 0x7FEB352D;
    private static final int SITE_MIX_D = 0x846CA68B;
    private static final int MATERIAL_WORD_A = 0x5B6D8F21;
    private static final int MATERIAL_WORD_B = 0x17A43C59;
    private static final int MATERIAL_WORD_C = 0x6D22B975;
    private static final int MATERIAL_WORD_D = 0x31C4AE07;
    private static final int MATERIAL_ADD_A = 0x24D37B91;
    private static final int MATERIAL_ADD_B = 0x7139E65D;
    private static final int MATERIAL_ADD_C = 0x49C11F33;
    private static final int MATERIAL_ADD_D = 0x5E8A6C2B;
    private static final int MATERIAL_XOR_A = 0x3A95D74F;
    private static final int MATERIAL_XOR_B = 0x66E51B29;
    private static final int MATERIAL_XOR_C = 0x1F2D4B87;
    private static final int MATERIAL_XOR_D = 0x7C03A1D5;
    private static final int MATERIAL_ROTATE = 9;
    private static final long DERIVED_INT_SPLIT_A = 0x44494E5453504C41L;
    private static final long DERIVED_INT_SPLIT_B = 0x44494E5453504C42L;
    private static final long DERIVED_INT_SPLIT_X = 0x44494E5453504C58L;
    private static final long DERIVED_INT_SITE_A = 0x44494E54534D4153L;
    private static final long DERIVED_INT_SITE_B = 0x44494E54534D4253L;
    private static final long DERIVED_INT_SITE_C = 0x44494E54534D4353L;
    private static final long DERIVED_INT_BOUND_A = 0x44494E54424E4441L;
    private static final long DERIVED_INT_BOUND_B = 0x44494E54424E4442L;
    private static final long DERIVED_INT_BOUND_C = 0x44494E54424E4443L;
    private static final long DERIVED_INT_NOISE_A = 0x44494E544E4F4953L;
    private static final long DERIVED_INT_NOISE_B = 0x44494E544E4F4954L;
    private static final long DERIVED_INT_NOISE_C = 0x44494E544E4F4955L;
    private static final long DERIVED_INT_HELPER_SEED = 0x44494E5448454C50L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Constant Obfuscation";
    }

    @Override
    public TransformPhase phase() {
        return TransformPhase.TRANSFORM;
    }

    @Override
    public IRLevel requiredLevel() {
        return IRLevel.L1;
    }

    @Override
    public Set<String> dependsOn() {
        return Set.of(ControlFlowFlatteningPass.ID);
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        if (clazz == null || TransformGuards.isRuntimeClass(clazz)) return;

        int moved = moveNumericConstantValues(pctx, clazz);
        if (moved > 0) {
            clazz.markDirty();
        }
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;
        if (TransformGuards.isRuntimeClass(clazz)) return;
        boolean generatedHelper = TransformGuards.isGeneratedMethod(method);
        boolean hardenGeneratedHelper =
            Boolean.TRUE.equals(pctx.getPassData("constantObfuscation.hardenGeneratedHelpers"));
        if (generatedHelper && !hardenGeneratedHelper) return;
        if (method.isAbstract() || method.isNative()) return;
        if (generatedHelper && hardenGeneratedHelper && generatedHelperUnderSizePressure(method.asmNode())) {
            return;
        }

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        ControlFlowFlatteningPass.CffMethodMetadata metadata =
            ControlFlowFlatteningPass.methodMetadata(pctx).get(methodKey);
        if (metadata == null) return;
        if (metadata.classKeyTable() == null) {
            throw new IllegalStateException(
                "constantObfuscation requires CFF class key table metadata for " + methodKey
            );
        }

        MethodNode mn = method.asmNode();
        List<NumericSite> sites = new ArrayList<>();
        List<ArrayConstantSite> arraySites = new ArrayList<>();
        Set<AbstractInsnNode> arrayConsumed = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<AbstractInsnNode> loopInstructions = loopRegionInstructions(mn);
        int ordinal = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            ControlFlowFlatteningPass.CffInstructionState state =
                metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException(
                    "constantObfuscation cannot bind CFF state for " + methodKey
                );
            }
            if (!arrayConsumed.contains(insn)) {
                ArrayConstantSite arraySite = primitiveArrayConstantSite(pctx, metadata, insn, state);
                if (arraySite != null) {
                    arraySite = arraySite.withSeed(siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++));
                    arraySites.add(arraySite);
                    arrayConsumed.addAll(arraySite.consumed());
                    continue;
                }
            }
        }
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (arrayConsumed.contains(insn)) continue;
            if (!metadata.applicationInstructions().contains(insn)) continue;
            ControlFlowFlatteningPass.CffInstructionState state =
                metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException(
                    "constantObfuscation cannot bind CFF state for " + methodKey
                );
            }
            NumericKind kind = numericKind(insn);
            if (kind == null) continue;
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++);
            sites.add(new NumericSite(insn, kind, state, siteSeed));
        }

        if (sites.isEmpty() && arraySites.isEmpty()) return;

        int baseLocal = mn.maxLocals++;
        int baseMultiplierLocal = mn.maxLocals++;
        int baseInverseLocal = mn.maxLocals++;
        int baseDataLocal = mn.maxLocals++;
        Map<Integer, FlowSite> firstSiteByBlock = new LinkedHashMap<>();
        Map<AbstractInsnNode, FlowSite> flowSitesByInsn = new IdentityHashMap<>();
        for (NumericSite site : sites) {
            flowSitesByInsn.put(site.insn(), new FlowSite(site.insn(), site.state()));
        }
        for (ArrayConstantSite site : arraySites) {
            flowSitesByInsn.put(site.lengthInsn(), new FlowSite(site.lengthInsn(), site.state()));
        }
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            FlowSite site = flowSitesByInsn.get(insn);
            if (site != null) {
                firstSiteByBlock.putIfAbsent(site.state().blockIndex(), site);
            }
        }
        for (FlowSite site : firstSiteByBlock.values()) {
            InsnList base = new InsnList();
            emitLiveConstantBase(base, metadata, site.state());
            base.add(new VarInsnNode(Opcodes.ISTORE, baseLocal));
            base.add(new VarInsnNode(Opcodes.ILOAD, metadata.dataLocal()));
            base.add(new VarInsnNode(Opcodes.ISTORE, baseDataLocal));
            JvmKeyDispatchPass.markGenerated(pctx, base);
            mn.instructions.insertBefore(site.insn(), base);
        }

        boolean compact = useCompactNumericDecode(mn, sites);
        String compactHelper = compact ? ensureIntDecodeHelper(pctx, clazz) : null;
        String compactProtectedHelper = compact && hasProtectedIntSites(sites)
            ? ensureProtectedIntDecodeHelper(pctx, clazz)
            : null;
        int transformed = 0;
        int transformedArrays = 0;
        for (ArrayConstantSite site : arraySites) {
            InsnList replacement = new InsnList();
            emitDecodedPrimitiveArray(
                replacement,
                site,
                metadata,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                clazz
            );
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(site.lengthInsn(), replacement);
            for (AbstractInsnNode consumed : site.consumed()) {
                mn.instructions.remove(consumed);
            }
            transformedArrays++;
        }
        for (NumericSite site : sites) {
            AbstractInsnNode insn = site.insn();
            if (insn instanceof IincInsnNode iinc) {
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ILOAD, iinc.var));
                emitDecodedProtectedInt(
                    replacement,
                    iinc.incr,
                    site.siteSeed(),
                    metadata,
                    site.state(),
                    baseLocal,
                    baseMultiplierLocal,
                    baseInverseLocal,
                    baseDataLocal,
                    compactProtectedHelper,
                    clazz
                );
                replacement.add(new InsnNode(Opcodes.IADD));
                replacement.add(new VarInsnNode(Opcodes.ISTORE, iinc.var));
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                transformed++;
                continue;
            }
            InsnList replacement = new InsnList();
            emitDecodedConstant(
                replacement,
                insn,
                site.kind(),
                site.siteSeed(),
                metadata,
                site.state(),
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                compactProtectedHelper,
                clazz
            );
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            mn.instructions.remove(insn);
            transformed++;
        }

        if (transformed > 0 || transformedArrays > 0) {
            mn.maxStack = Math.max(mn.maxStack, 32);
            clazz.markDirty();
            pctx.invalidate(method);
            JvmObfuscationCoverage.get(ctx).full(
                id(),
                clazz.name(),
                method.name(),
                method.descriptor(),
                (compact ? "compact-" : "") +
                    "cff-keyed-numeric-sites-" + transformed +
                    "-primitive-arrays-" + transformedArrays
            );
        }
    }

    private boolean useCompactNumericDecode(MethodNode mn, List<NumericSite> sites) {
        if (sites.size() >= COMPACT_SITE_THRESHOLD) return true;
        int estimatedGrowth = 0;
        int protectedIntSites = 0;
        for (NumericSite site : sites) {
            if (site.kind() == NumericKind.INT || site.kind() == NumericKind.IINC) {
                protectedIntSites++;
                estimatedGrowth += 260;
            } else {
                estimatedGrowth += site.kind() == NumericKind.LONG || site.kind() == NumericKind.DOUBLE ? 38 : 18;
            }
        }
        if (protectedIntSites >= 4) return true;
        return JvmCodeSizeEstimator.estimateMethodBytes(mn) + estimatedGrowth >= COMPACT_SIZE_PRESSURE;
    }

    private boolean hasProtectedIntSites(List<NumericSite> sites) {
        for (NumericSite site : sites) {
            if (site.kind() == NumericKind.INT || site.kind() == NumericKind.IINC) {
                return true;
            }
        }
        return false;
    }

    private boolean generatedHelperUnderSizePressure(MethodNode mn) {
        return JvmCodeSizeEstimator.estimateMethodBytes(mn) >= GENERATED_HELPER_HARDENING_SIZE_PRESSURE;
    }

    private int moveNumericConstantValues(PipelineContext pctx, L1Class clazz) {
        if (clazz.asmNode().fields == null) return 0;
        int moved = 0;
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.value == null || !isNumericConstantValue(field)) continue;
            Object value = field.value;
            field.value = null;
            MethodNode clinit = findOrCreateClassInit(clazz);
            InsnList assignment = new InsnList();
            long seed = JvmPassBytecode.mix(
                pctx.masterSeed() ^ 0x434F4E535456414CL,
                (clazz.name() + "." + field.name + field.desc).hashCode()
            );
            emitFieldValue(assignment, value, field.desc, seed);
            assignment.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), field.name, field.desc));
            JvmKeyDispatchPass.markGenerated(pctx, assignment);
            AbstractInsnNode returnInsn = firstReturn(clinit);
            if (returnInsn == null) {
                clinit.instructions.add(assignment);
                clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            } else {
                clinit.instructions.insertBefore(returnInsn, assignment);
            }
            clinit.maxStack = Math.max(clinit.maxStack, 8);
            moved++;
        }
        return moved;
    }

    private boolean isNumericConstantValue(FieldNode field) {
        if ((field.access & Opcodes.ACC_STATIC) == 0) return false;
        return switch (field.desc) {
            case "B", "C", "S", "I", "J", "F", "D" -> field.value instanceof Number;
            default -> false;
        };
    }

    private void emitFieldValue(InsnList insns, Object value, String desc, long seed) {
        switch (desc) {
            case "J" -> emitStaticDecodedLong(insns, ((Number) value).longValue(), seed);
            case "F" -> {
                emitStaticDecodedInt(insns, Float.floatToRawIntBits(((Number) value).floatValue()), seed);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case "D" -> {
                emitStaticDecodedLong(insns, Double.doubleToRawLongBits(((Number) value).doubleValue()), seed);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
            default -> emitStaticDecodedInt(insns, ((Number) value).intValue(), seed);
        }
    }

    private void emitDecodedConstant(
        InsnList insns,
        AbstractInsnNode source,
        NumericKind kind,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        String compactProtectedHelper,
        L1Class clazz
    ) {
        switch (kind) {
            case INT -> emitDecodedProtectedInt(
                insns,
                intConstant(source),
                siteSeed,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactProtectedHelper,
                clazz
            );
            case LONG -> emitDecodedLong(
                insns,
                longConstant(source),
                siteSeed,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                clazz
            );
            case FLOAT -> {
                emitDecodedInt(
                    insns,
                    Float.floatToRawIntBits(floatConstant(source)),
                    siteSeed,
                    metadata,
                    state,
                    baseLocal,
                    baseMultiplierLocal,
                    baseInverseLocal,
                    baseDataLocal,
                    compactHelper,
                    clazz
                );
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case DOUBLE -> {
                emitDecodedLong(
                    insns,
                    Double.doubleToRawLongBits(doubleConstant(source)),
                    siteSeed,
                    metadata,
                    state,
                    baseLocal,
                    baseMultiplierLocal,
                    baseInverseLocal,
                    baseDataLocal,
                    compactHelper,
                    clazz
                );
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
            case IINC -> throw new IllegalStateException("IINC is handled by caller");
        }
    }

    private void emitDecodedProtectedInt(
        InsnList insns,
        int value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactProtectedHelper,
        L1Class clazz
    ) {
        emitDecodedProtectedIntUncached(
            insns,
            value,
            siteSeed,
            metadata,
            state,
            baseLocal,
            baseMultiplierLocal,
            baseInverseLocal,
            baseDataLocal,
            compactProtectedHelper,
            clazz
        );
    }

    private void emitDecodedProtectedIntUncached(
        InsnList insns,
        int value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactProtectedHelper,
        L1Class clazz
    ) {
        int expectedRawBase = liveConstantBase(metadata, state);
        int expectedMask = constantMaskFromBase(expectedRawBase, (int) siteSeed);
        int encrypted = value ^ expectedMask;
        if (compactProtectedHelper != null) {
            emitDecodedLiveConstantBaseForMask(
                insns,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal
            );
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseMultiplierLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, baseMultiplierLocal));
            emitExactDerivedIntMaterial(
                insns,
                baseMultiplierLocal,
                expectedRawBase,
                encrypted,
                siteSeed,
                DERIVED_INT_SPLIT_A
            );
            emitExactDerivedIntMaterial(
                insns,
                baseMultiplierLocal,
                expectedRawBase,
                (int) siteSeed,
                siteSeed,
                DERIVED_INT_HELPER_SEED
            );
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                clazz.name(),
                compactProtectedHelper,
                "(III)I",
                clazz.isInterface()
            ));
        } else {
            emitDecodedLiveConstantBaseForMask(
                insns,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal
            );
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseMultiplierLocal));
            emitConstantMaterialNoiseDerived(insns, siteSeed, metadata, state, baseMultiplierLocal, expectedRawBase);
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseInverseLocal));
            emitRuntimeDerivedProtectedEncryptedInt(
                insns,
                encrypted,
                siteSeed,
                state,
                baseMultiplierLocal,
                expectedRawBase,
                baseInverseLocal
            );
            emitRuntimeMaterialDecodeDerived(
                insns,
                siteSeed,
                state,
                baseMultiplierLocal,
                expectedRawBase,
                baseInverseLocal
            );
        }
    }

    private void emitDecodedInt(
        InsnList insns,
        int value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedIntUncached(
            insns,
            value,
            siteSeed,
            metadata,
            state,
            baseLocal,
            baseMultiplierLocal,
            baseInverseLocal,
            baseDataLocal,
            compactHelper,
            clazz
        );
    }

    private void emitDecodedIntUncached(
        InsnList insns,
        int value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        L1Class clazz
    ) {
        int expectedMask = constantMaskFromBase(liveConstantBase(metadata, state), (int) siteSeed);
        int encrypted = value ^ expectedMask;
        if (compactHelper != null) {
            emitDecodedLiveConstantBaseForMask(
                insns,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal
            );
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseMultiplierLocal));
            emitConstantMaterialNoise(insns, siteSeed, metadata, state);
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseInverseLocal));
            emitRuntimeDerivedEncryptedInt(
                insns,
                encrypted,
                siteSeed,
                state,
                baseMultiplierLocal,
                baseInverseLocal
            );
            emitRuntimeBoundConstantBase(insns, siteSeed, state, baseMultiplierLocal, baseInverseLocal);
            insns.add(new InsnNode(Opcodes.SWAP));
            JvmPassBytecode.pushInt(insns, (int) siteSeed);
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                clazz.name(),
                compactHelper,
                "(III)I",
                clazz.isInterface()
            ));
        } else {
            emitDecodedLiveConstantBaseForMask(
                insns,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal
            );
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseMultiplierLocal));
            emitConstantMaterialNoise(insns, siteSeed, metadata, state);
            insns.add(new VarInsnNode(Opcodes.ISTORE, baseInverseLocal));
            emitRuntimeDerivedEncryptedInt(
                insns,
                encrypted,
                siteSeed,
                state,
                baseMultiplierLocal,
                baseInverseLocal
            );
            emitRuntimeMaterialDecode(insns, siteSeed, state, baseMultiplierLocal, baseInverseLocal);
        }
    }

    private void emitDecodedLong(
        InsnList insns,
        long value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedLongUncached(
            insns,
            value,
            siteSeed,
            metadata,
            state,
            baseLocal,
            baseMultiplierLocal,
            baseInverseLocal,
            baseDataLocal,
            compactHelper,
            clazz
        );
    }

    private void emitDecodedLongUncached(
        InsnList insns,
        long value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedInt(
            insns,
            (int) (value >>> 32),
            siteSeed ^ 0x484947484B31L,
            metadata,
            state,
            baseLocal,
            baseMultiplierLocal,
            baseInverseLocal,
            baseDataLocal,
            compactHelper,
            clazz
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitDecodedInt(
            insns,
            (int) value,
            siteSeed ^ 0x4C4F574B31L,
            metadata,
            state,
            baseLocal,
            baseMultiplierLocal,
            baseInverseLocal,
            baseDataLocal,
            compactHelper,
            clazz
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private int liveConstantBase(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        int x =
            (state.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424731L, 0x43474B31L))) +
            state.pathKey();
        x ^= x >>> 7;
        x = (x + state.blockKey()) ^
            nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424231L, 0x43424B31L));
        x *= nonZeroInt(JvmPassBytecode.mix(0x434F4E53544D554CL, 0x434D554CL)) | 1;
        x ^= derivedMethodKeyFold(metadata, state);
        x += state.pcToken() ^ nonZeroInt(JvmPassBytecode.mix(0x434F4E5354504331L, 0x43504331L));
        int idx =
            (x ^ state.state() ^ nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544142L, 0x435441424CL))) &
            (metadata.classKeyTable().values().length - 1);
        x ^= metadata.classKeyTable().values()[idx] +
            nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544149L, 0x435441494CL));
        x += x >>> 19;
        x *= nonZeroInt(JvmPassBytecode.mix(0x473138434F4E5354L, state.methodSalt())) | 1;
        x ^= x >>> 11;
        return x;
    }

    private int constantMaskFromBase(int base, int seed) {
        int x = base + (seed ^ SITE_MIX_A);
        x ^= x >>> 16;
        x *= (seed ^ SITE_MIX_B) | 1;
        x ^= seed * SITE_MIX_C + SITE_MIX_D;
        return x;
    }

    private int derivedMethodKeyFold(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        int x = (state.guardKey() ^ state.pathKey()) + state.blockKey();
        x ^= (int) state.methodSalt();
        x ^= (int) state.methodKey();
        return x;
    }

    private void emitLiveConstantBase(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424731L, 0x43474B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 7);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424231L, 0x43424B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E53544D554CL, 0x434D554CL)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        emitDerivedMethodKeyFold(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354504331L, 0x43504331L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544142L, 0x435441424CL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, metadata.classKeyTable().values().length - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            metadata.classKeyTable().owner(),
            metadata.classKeyTable().objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new InsnNode(Opcodes.SWAP));
        ControlFlowFlatteningPass.emitDecodedSealedClassKeyWord(
            insns,
            ControlFlowFlatteningPass.CLASS_KEY_WORD_SEAL
        );
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544149L, 0x435441494CL)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 19);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x473138434F4E5354L, state.methodSalt())) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 11);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitConstantBaseDataMultiplier(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IMUL));
    }

    private void emitSiteMaskFromTop(
        InsnList insns,
        int seed
    ) {
        JvmPassBytecode.pushInt(insns, seed ^ SITE_MIX_A);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (seed ^ SITE_MIX_B) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, seed * SITE_MIX_C + SITE_MIX_D);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitSiteMaskFromTopDerived(
        InsnList insns,
        int seed,
        long siteSeed,
        int liveIntLocal,
        int expectedLiveValue
    ) {
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            seed ^ SITE_MIX_A,
            siteSeed,
            DERIVED_INT_SITE_A
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            (seed ^ SITE_MIX_B) | 1,
            siteSeed,
            DERIVED_INT_SITE_B
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            seed * SITE_MIX_C + SITE_MIX_D,
            siteSeed,
            DERIVED_INT_SITE_C
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitDecodedLiveConstantBaseForMask(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, baseLocal));
        emitConstantBaseDataMultiplier(insns, metadata, state, baseDataLocal);
        insns.add(new VarInsnNode(Opcodes.ISTORE, baseMultiplierLocal));
        emitOddIntInverse(insns, baseMultiplierLocal, baseInverseLocal);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        emitConstantBaseDataMultiplier(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, baseLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.dataLocal()));
        insns.add(new VarInsnNode(Opcodes.ISTORE, baseDataLocal));
    }

    private void emitRuntimeDerivedEncryptedInt(
        InsnList insns,
        int encrypted,
        long siteSeed,
        ControlFlowFlatteningPass.CffInstructionState state,
        int rawBaseLocal,
        int noiseLocal
    ) {
        emitSplitEncryptedInt(insns, encrypted, siteSeed);
        insns.add(new VarInsnNode(Opcodes.ILOAD, rawBaseLocal));
        emitSiteMaskFromTop(insns, (int) siteSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeBoundConstantBase(insns, siteSeed, state, rawBaseLocal, noiseLocal);
        emitInlineMaterialWordFromTop(
            insns,
            (int) siteSeed,
            MATERIAL_WORD_A,
            MATERIAL_WORD_B,
            MATERIAL_WORD_C,
            MATERIAL_WORD_D,
            13
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRotateLeftTop(insns, MATERIAL_ROTATE);
        emitRuntimeBoundConstantBase(insns, siteSeed, state, rawBaseLocal, noiseLocal);
        emitInlineMaterialWordFromTop(
            insns,
            (int) siteSeed,
            MATERIAL_ADD_A,
            MATERIAL_ADD_B,
            MATERIAL_ADD_C,
            MATERIAL_ADD_D,
            17
        );
        insns.add(new InsnNode(Opcodes.IADD));
        emitRuntimeBoundConstantBase(insns, siteSeed, state, rawBaseLocal, noiseLocal);
        emitInlineMaterialWordFromTop(
            insns,
            (int) siteSeed,
            MATERIAL_XOR_A,
            MATERIAL_XOR_B,
            MATERIAL_XOR_C,
            MATERIAL_XOR_D,
            11
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeDerivedProtectedEncryptedInt(
        InsnList insns,
        int encrypted,
        long siteSeed,
        ControlFlowFlatteningPass.CffInstructionState state,
        int rawBaseLocal,
        int expectedRawBase,
        int noiseLocal
    ) {
        emitSplitDerivedEncryptedInt(insns, encrypted, siteSeed, rawBaseLocal, expectedRawBase);
        insns.add(new VarInsnNode(Opcodes.ILOAD, rawBaseLocal));
        emitSiteMaskFromTopDerived(insns, (int) siteSeed, siteSeed, rawBaseLocal, expectedRawBase);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeBoundConstantBaseDerived(insns, siteSeed, state, rawBaseLocal, expectedRawBase, noiseLocal);
        emitInlineMaterialWordFromTopDerived(
            insns,
            (int) siteSeed,
            MATERIAL_WORD_A,
            MATERIAL_WORD_B,
            MATERIAL_WORD_C,
            MATERIAL_WORD_D,
            13,
            rawBaseLocal,
            expectedRawBase,
            siteSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRotateLeftTop(insns, MATERIAL_ROTATE);
        emitRuntimeBoundConstantBaseDerived(insns, siteSeed, state, rawBaseLocal, expectedRawBase, noiseLocal);
        emitInlineMaterialWordFromTopDerived(
            insns,
            (int) siteSeed,
            MATERIAL_ADD_A,
            MATERIAL_ADD_B,
            MATERIAL_ADD_C,
            MATERIAL_ADD_D,
            17,
            rawBaseLocal,
            expectedRawBase,
            siteSeed
        );
        insns.add(new InsnNode(Opcodes.IADD));
        emitRuntimeBoundConstantBaseDerived(insns, siteSeed, state, rawBaseLocal, expectedRawBase, noiseLocal);
        emitInlineMaterialWordFromTopDerived(
            insns,
            (int) siteSeed,
            MATERIAL_XOR_A,
            MATERIAL_XOR_B,
            MATERIAL_XOR_C,
            MATERIAL_XOR_D,
            11,
            rawBaseLocal,
            expectedRawBase,
            siteSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitSplitEncryptedInt(InsnList insns, int encrypted, long siteSeed) {
        int xorFragment = (int) JvmPassBytecode.mix(siteSeed, 0x434F4E5354465841L);
        int addTarget = encrypted ^ xorFragment;
        int addFragmentA = (int) JvmPassBytecode.mix(siteSeed, 0x434F4E5354464141L);
        int addFragmentB = addTarget - addFragmentA;
        JvmPassBytecode.pushInt(insns, addFragmentA);
        JvmPassBytecode.pushInt(insns, addFragmentB);
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, xorFragment);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitSplitDerivedEncryptedInt(
        InsnList insns,
        int encrypted,
        long siteSeed,
        int liveIntLocal,
        int expectedLiveValue
    ) {
        int xorFragment = (int) JvmPassBytecode.mix(siteSeed, 0x434F4E5354465841L);
        int addTarget = encrypted ^ xorFragment;
        int addFragmentA = (int) JvmPassBytecode.mix(siteSeed, 0x434F4E5354464141L);
        int addFragmentB = addTarget - addFragmentA;
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            addFragmentA,
            siteSeed,
            DERIVED_INT_SPLIT_A
        );
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            addFragmentB,
            siteSeed,
            DERIVED_INT_SPLIT_B
        );
        insns.add(new InsnNode(Opcodes.IADD));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            xorFragment,
            siteSeed,
            DERIVED_INT_SPLIT_X
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeBoundConstantBase(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffInstructionState state,
        int rawBaseLocal,
        int noiseLocal
    ) {
        long seed = JvmPassBytecode.mix(
            siteSeed ^ state.methodSalt(),
            ((long) state.blockIndex() << 32) ^ state.state()
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, rawBaseLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, noiseLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354424E41L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 3));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354424E42L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, noiseLocal));
        JvmPassBytecode.pushInt(insns, shift(seed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354424E43L)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeBoundConstantBaseDerived(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffInstructionState state,
        int rawBaseLocal,
        int expectedRawBase,
        int noiseLocal
    ) {
        long seed = JvmPassBytecode.mix(
            siteSeed ^ state.methodSalt(),
            ((long) state.blockIndex() << 32) ^ state.state()
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, rawBaseLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, noiseLocal));
        emitExactDerivedIntMaterial(
            insns,
            rawBaseLocal,
            expectedRawBase,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354424E41L)) | 1,
            siteSeed,
            DERIVED_INT_BOUND_A
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 3));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitExactDerivedIntMaterial(
            insns,
            rawBaseLocal,
            expectedRawBase,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354424E42L)) | 1,
            siteSeed,
            DERIVED_INT_BOUND_B
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, noiseLocal));
        JvmPassBytecode.pushInt(insns, shift(seed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        emitExactDerivedIntMaterial(
            insns,
            rawBaseLocal,
            expectedRawBase,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354424E43L)),
            siteSeed,
            DERIVED_INT_BOUND_C
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeMaterialDecode(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffInstructionState state,
        int rawBaseLocal,
        int noiseLocal
    ) {
        emitRuntimeBoundConstantBase(insns, siteSeed, state, rawBaseLocal, noiseLocal);
        emitInlineMaterialWordFromTop(
            insns,
            (int) siteSeed,
            MATERIAL_XOR_A,
            MATERIAL_XOR_B,
            MATERIAL_XOR_C,
            MATERIAL_XOR_D,
            11
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeBoundConstantBase(insns, siteSeed, state, rawBaseLocal, noiseLocal);
        emitInlineMaterialWordFromTop(
            insns,
            (int) siteSeed,
            MATERIAL_ADD_A,
            MATERIAL_ADD_B,
            MATERIAL_ADD_C,
            MATERIAL_ADD_D,
            17
        );
        insns.add(new InsnNode(Opcodes.ISUB));
        emitRotateRightTop(insns, MATERIAL_ROTATE);
        emitRuntimeBoundConstantBase(insns, siteSeed, state, rawBaseLocal, noiseLocal);
        emitInlineMaterialWordFromTop(
            insns,
            (int) siteSeed,
            MATERIAL_WORD_A,
            MATERIAL_WORD_B,
            MATERIAL_WORD_C,
            MATERIAL_WORD_D,
            13
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeMaterialDecodeDerived(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffInstructionState state,
        int rawBaseLocal,
        int expectedRawBase,
        int noiseLocal
    ) {
        emitRuntimeBoundConstantBaseDerived(insns, siteSeed, state, rawBaseLocal, expectedRawBase, noiseLocal);
        emitInlineMaterialWordFromTopDerived(
            insns,
            (int) siteSeed,
            MATERIAL_XOR_A,
            MATERIAL_XOR_B,
            MATERIAL_XOR_C,
            MATERIAL_XOR_D,
            11,
            rawBaseLocal,
            expectedRawBase,
            siteSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeBoundConstantBaseDerived(insns, siteSeed, state, rawBaseLocal, expectedRawBase, noiseLocal);
        emitInlineMaterialWordFromTopDerived(
            insns,
            (int) siteSeed,
            MATERIAL_ADD_A,
            MATERIAL_ADD_B,
            MATERIAL_ADD_C,
            MATERIAL_ADD_D,
            17,
            rawBaseLocal,
            expectedRawBase,
            siteSeed
        );
        insns.add(new InsnNode(Opcodes.ISUB));
        emitRotateRightTop(insns, MATERIAL_ROTATE);
        emitRuntimeBoundConstantBaseDerived(insns, siteSeed, state, rawBaseLocal, expectedRawBase, noiseLocal);
        emitInlineMaterialWordFromTopDerived(
            insns,
            (int) siteSeed,
            MATERIAL_WORD_A,
            MATERIAL_WORD_B,
            MATERIAL_WORD_C,
            MATERIAL_WORD_D,
            13,
            rawBaseLocal,
            expectedRawBase,
            siteSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitInlineMaterialWordFromTop(
        InsnList insns,
        int seed,
        int mixA,
        int mixB,
        int mixC,
        int mixD,
        int shift
    ) {
        JvmPassBytecode.pushInt(insns, seed ^ mixA);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (seed ^ mixB) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, seed * mixC + mixD);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitInlineMaterialWordFromTopDerived(
        InsnList insns,
        int seed,
        int mixA,
        int mixB,
        int mixC,
        int mixD,
        int shift,
        int liveIntLocal,
        int expectedLiveValue,
        long siteSeed
    ) {
        long materialDomain = JvmPassBytecode.mix(
            0x44494E544D574F52L,
            ((long) mixA << 32) ^ mixB ^ mixC ^ mixD ^ shift
        );
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            seed ^ mixA,
            siteSeed,
            JvmPassBytecode.mix(materialDomain, 0x41L)
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            (seed ^ mixB) | 1,
            siteSeed,
            JvmPassBytecode.mix(materialDomain, 0x42L)
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            seed * mixC + mixD,
            siteSeed,
            JvmPassBytecode.mix(materialDomain, 0x43L)
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitHelperMaterialWordFromTop(
        InsnList insns,
        int seedLocal,
        int mixA,
        int mixB,
        int mixC,
        int mixD,
        int shift
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, mixA);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, mixB);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, mixC);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, mixD);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRotateLeftTop(InsnList insns, int distance) {
        int shift = distance & 31;
        if (shift == 0) return;
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.SWAP));
        JvmPassBytecode.pushInt(insns, 32 - shift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IOR));
    }

    private void emitRotateRightTop(InsnList insns, int distance) {
        int shift = distance & 31;
        if (shift == 0) return;
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.SWAP));
        JvmPassBytecode.pushInt(insns, 32 - shift);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IOR));
    }

    private void emitConstantMaterialNoise(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        long seed = JvmPassBytecode.mix(siteSeed, state.methodSalt() ^ state.state());
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.dataLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E53544D4E41L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 9));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E53544D4E42L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E53544D4E43L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 23));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitConstantMaterialNoiseDerived(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int liveIntLocal,
        int expectedLiveValue
    ) {
        long seed = JvmPassBytecode.mix(siteSeed, state.methodSalt() ^ state.state());
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.dataLocal()));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E53544D4E41L)),
            siteSeed,
            DERIVED_INT_NOISE_A
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 9));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E53544D4E42L)) | 1,
            siteSeed,
            DERIVED_INT_NOISE_B
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitExactDerivedIntMaterial(
            insns,
            liveIntLocal,
            expectedLiveValue,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E53544D4E43L)) | 1,
            siteSeed,
            DERIVED_INT_NOISE_C
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 23));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitExactDerivedIntMaterial(
        InsnList insns,
        int liveIntLocal,
        int expectedLiveValue,
        int value,
        long siteSeed,
        long domain
    ) {
        long seed = JvmPassBytecode.mix(siteSeed, domain);
        int xorWord = nonZeroInt(JvmPassBytecode.mix(seed, 0x4E4D4154494E5431L));
        int multiplyWord = nonZeroInt(JvmPassBytecode.mix(seed, 0x4E4D4154494E5432L)) | 1;
        int shiftA = shift(seed, 7);
        int shiftB = shift(seed, 19);
        int expected = expectedLiveValue ^ xorWord;
        expected ^= expected >>> shiftA;
        expected *= multiplyWord;
        expected += expected >>> shiftB;
        int finalWord = expected ^ value;

        insns.add(new VarInsnNode(Opcodes.ILOAD, liveIntLocal));
        JvmPassBytecode.pushInt(insns, xorWord);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shiftA);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, multiplyWord);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shiftB);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, finalWord);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitConstantBaseDataMultiplier(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        emitConstantBaseDataMultiplier(insns, metadata, state, metadata.dataLocal());
    }

    private void emitConstantBaseDataMultiplier(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int dataLocal
    ) {
        long seed = JvmPassBytecode.mix(
            state.methodSalt(),
            ((long) state.blockIndex() << 32) ^ state.blockKey()
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, dataLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354444D41L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354444741L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354444241L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354444D42L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 21));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434F4E5354444D43L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
    }

    private void emitOddIntInverse(
        InsnList insns,
        int multiplierLocal,
        int inverseLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, multiplierLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, inverseLocal));
        for (int i = 0; i < 5; i++) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, inverseLocal));
            JvmPassBytecode.pushInt(insns, 2);
            insns.add(new VarInsnNode(Opcodes.ILOAD, multiplierLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, inverseLocal));
            insns.add(new InsnNode(Opcodes.IMUL));
            insns.add(new InsnNode(Opcodes.ISUB));
            insns.add(new InsnNode(Opcodes.IMUL));
            insns.add(new VarInsnNode(Opcodes.ISTORE, inverseLocal));
        }
        insns.add(new VarInsnNode(Opcodes.ILOAD, inverseLocal));
    }

    @SuppressWarnings("unchecked")
    private String ensureIntDecodeHelper(PipelineContext pctx, L1Class clazz) {
        Map<String, String> helpers = pctx.getPassData(NUMERIC_HELPERS);
        if (helpers == null) {
            helpers = new LinkedHashMap<>();
            pctx.putPassData(NUMERIC_HELPERS, helpers);
        }
        String key = clazz.name();
        String existing = helpers.get(key);
        if (existing != null) return existing;

        String name = uniqueMethodName(clazz, "__neko_num_i");
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        access |= (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode helper = new MethodNode(access, name, "(III)I", null, null);
        emitHelperMaterialDecode(helper.instructions);
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 4;
        helper.maxStack = 8;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        clazz.markDirty();
        helpers.put(key, name);
        return name;
    }

    @SuppressWarnings("unchecked")
    private String ensureProtectedIntDecodeHelper(PipelineContext pctx, L1Class clazz) {
        Map<String, String> helpers = pctx.getPassData(PROTECTED_NUMERIC_HELPERS);
        if (helpers == null) {
            helpers = new LinkedHashMap<>();
            pctx.putPassData(PROTECTED_NUMERIC_HELPERS, helpers);
        }
        String key = clazz.name();
        String existing = helpers.get(key);
        if (existing != null) return existing;

        String name = uniqueMethodName(clazz, "__neko_num_ip");
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        access |= (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode helper = new MethodNode(access, name, "(III)I", null, null);
        emitProtectedHelperDecode(helper.instructions);
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 3;
        helper.maxStack = 8;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        clazz.markDirty();
        helpers.put(key, name);
        return name;
    }

    private void emitHelperMaterialDecode(InsnList insns) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        emitHelperMaterialWordFromTop(
            insns,
            2,
            MATERIAL_XOR_A,
            MATERIAL_XOR_B,
            MATERIAL_XOR_C,
            MATERIAL_XOR_D,
            11
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        emitHelperMaterialWordFromTop(
            insns,
            2,
            MATERIAL_ADD_A,
            MATERIAL_ADD_B,
            MATERIAL_ADD_C,
            MATERIAL_ADD_D,
            17
        );
        insns.add(new InsnNode(Opcodes.ISUB));
        emitRotateRightTop(insns, MATERIAL_ROTATE);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        emitHelperMaterialWordFromTop(
            insns,
            2,
            MATERIAL_WORD_A,
            MATERIAL_WORD_B,
            MATERIAL_WORD_C,
            MATERIAL_WORD_D,
            13
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitProtectedHelperDecode(InsnList insns) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        emitHelperSiteMaskFromTop(insns, 2);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitHelperSiteMaskFromTop(InsnList insns, int seedLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, SITE_MIX_A);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, SITE_MIX_B);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, SITE_MIX_C);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, SITE_MIX_D);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String candidate = base;
        int suffix = 0;
        while (hasMethod(clazz, candidate)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (name.equals(method.name)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void emitDerivedMethodKeyFold(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, (int) state.methodSalt());
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30L);
    }

    private void emitStaticDecodedInt(InsnList insns, int value, long seed) {
        int mask = nonZeroInt(JvmPassBytecode.mix(seed, 0x5354415449434B31L));
        JvmPassBytecode.pushInt(insns, value ^ mask);
        JvmPassBytecode.pushInt(insns, mask);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStaticDecodedLong(InsnList insns, long value, long seed) {
        emitStaticDecodedInt(insns, (int) (value >>> 32), seed ^ 0x484947484B31L);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitStaticDecodedInt(insns, (int) value, seed ^ 0x4C4F574B31L);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private ArrayConstantSite primitiveArrayConstantSite(
        PipelineContext pctx,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        AbstractInsnNode lengthInsn,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        if (numericKind(lengthInsn) != NumericKind.INT) return null;
        int length = intConstant(lengthInsn);
        if (length < 0) return null;
        AbstractInsnNode arrayInsn = nextReal(lengthInsn.getNext());
        if (!(arrayInsn instanceof IntInsnNode newArray) || newArray.getOpcode() != Opcodes.NEWARRAY) {
            return null;
        }
        PrimitiveArrayKind kind = PrimitiveArrayKind.fromNewArrayOperand(newArray.operand);
        if (kind == null || !isApplicationPatternNode(pctx, metadata, arrayInsn)) return null;

        List<AbstractInsnNode> consumed = new ArrayList<>();
        consumed.add(lengthInsn);
        consumed.add(arrayInsn);
        List<ArrayElement> elements = new ArrayList<>();
        boolean[] seen = new boolean[length];
        AbstractInsnNode cursor = nextReal(arrayInsn.getNext());
        while (cursor != null && cursor.getOpcode() == Opcodes.DUP) {
            if (!isApplicationPatternNode(pctx, metadata, cursor) || elements.size() >= length) return null;
            AbstractInsnNode indexInsn = nextReal(cursor.getNext());
            if (!isApplicationPatternNode(pctx, metadata, indexInsn) || numericKind(indexInsn) != NumericKind.INT) {
                return null;
            }
            int index = intConstant(indexInsn);
            if (index < 0 || index >= length || seen[index]) return null;
            AbstractInsnNode valueInsn = nextReal(indexInsn.getNext());
            if (!isApplicationPatternNode(pctx, metadata, valueInsn)) return null;
            Long valueBits = arrayElementBits(kind, valueInsn);
            if (valueBits == null) return null;
            AbstractInsnNode storeInsn = nextReal(valueInsn.getNext());
            if (
                !isApplicationPatternNode(pctx, metadata, storeInsn) ||
                storeInsn.getOpcode() != kind.storeOpcode()
            ) {
                return null;
            }
            seen[index] = true;
            elements.add(new ArrayElement(index, valueBits));
            consumed.add(cursor);
            consumed.add(indexInsn);
            consumed.add(valueInsn);
            consumed.add(storeInsn);
            cursor = nextReal(storeInsn.getNext());
        }
        if (elements.size() != length) return null;
        return new ArrayConstantSite(lengthInsn, kind, length, elements, state, 0L, consumed);
    }

    private void emitDecodedPrimitiveArray(
        InsnList insns,
        ArrayConstantSite site,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedInt(
            insns,
            site.length(),
            JvmPassBytecode.mix(site.siteSeed(), 0x4152524C454E4754L),
            metadata,
            site.state(),
            baseLocal,
            baseMultiplierLocal,
            baseInverseLocal,
            baseDataLocal,
            compactHelper,
            clazz
        );
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, site.kind().newArrayOperand()));
        for (int ordinal = 0; ordinal < site.elements().size(); ordinal++) {
            ArrayElement element = site.elements().get(ordinal);
            long elementSeed = JvmPassBytecode.mix(
                site.siteSeed() ^ 0x415252454C454D31L,
                ((long) element.index() << 32) ^ ordinal ^ site.kind().newArrayOperand()
            );
            insns.add(new InsnNode(Opcodes.DUP));
            emitDecodedInt(
                insns,
                element.index(),
                JvmPassBytecode.mix(elementSeed, 0x4152524944583031L),
                metadata,
                site.state(),
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                clazz
            );
            emitDecodedArrayElement(
                insns,
                site.kind(),
                element.valueBits(),
                JvmPassBytecode.mix(elementSeed, 0x41525256414C3031L),
                metadata,
                site.state(),
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                clazz
            );
            insns.add(new InsnNode(site.kind().storeOpcode()));
        }
    }

    private void emitDecodedArrayElement(
        InsnList insns,
        PrimitiveArrayKind kind,
        long valueBits,
        long seed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        int baseMultiplierLocal,
        int baseInverseLocal,
        int baseDataLocal,
        String compactHelper,
        L1Class clazz
    ) {
        switch (kind.valueKind()) {
            case INT, IINC -> emitDecodedInt(
                insns,
                (int) valueBits,
                seed,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                clazz
            );
            case LONG -> emitDecodedLong(
                insns,
                valueBits,
                seed,
                metadata,
                state,
                baseLocal,
                baseMultiplierLocal,
                baseInverseLocal,
                baseDataLocal,
                compactHelper,
                clazz
            );
            case FLOAT -> {
                emitDecodedInt(
                    insns,
                    (int) valueBits,
                    seed,
                    metadata,
                    state,
                    baseLocal,
                    baseMultiplierLocal,
                    baseInverseLocal,
                    baseDataLocal,
                    compactHelper,
                    clazz
                );
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case DOUBLE -> {
                emitDecodedLong(
                    insns,
                    valueBits,
                    seed,
                    metadata,
                    state,
                    baseLocal,
                    baseMultiplierLocal,
                    baseInverseLocal,
                    baseDataLocal,
                    compactHelper,
                    clazz
                );
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
        }
    }

    private Long arrayElementBits(PrimitiveArrayKind kind, AbstractInsnNode valueInsn) {
        return switch (kind.valueKind()) {
            case INT -> numericKind(valueInsn) == NumericKind.INT ? (long) intConstant(valueInsn) : null;
            case LONG -> numericKind(valueInsn) == NumericKind.LONG ? longConstant(valueInsn) : null;
            case FLOAT -> numericKind(valueInsn) == NumericKind.FLOAT
                ? (long) Float.floatToRawIntBits(floatConstant(valueInsn))
                : null;
            case DOUBLE -> numericKind(valueInsn) == NumericKind.DOUBLE
                ? Double.doubleToRawLongBits(doubleConstant(valueInsn))
                : null;
            case IINC -> null;
        };
    }

    private boolean isApplicationPatternNode(
        PipelineContext pctx,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        AbstractInsnNode insn
    ) {
        return insn != null &&
            insn.getOpcode() >= 0 &&
            metadata.applicationInstructions().contains(insn) &&
            !JvmKeyDispatchPass.isGeneratedNode(pctx, insn);
    }

    private AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn;
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private Set<AbstractInsnNode> loopRegionInstructions(MethodNode mn) {
        AbstractInsnNode[] nodes = mn.instructions.toArray();
        Map<AbstractInsnNode, Integer> indexByNode = new IdentityHashMap<>();
        for (int i = 0; i < nodes.length; i++) {
            indexByNode.put(nodes[i], i);
        }
        Set<AbstractInsnNode> loop = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < nodes.length; i++) {
            AbstractInsnNode node = nodes[i];
            if (node instanceof JumpInsnNode jump) {
                markBackwardRegion(loop, nodes, indexByNode, i, jump.label);
            } else if (node instanceof LookupSwitchInsnNode lookup) {
                markBackwardRegion(loop, nodes, indexByNode, i, lookup.dflt);
                for (LabelNode label : lookup.labels) {
                    markBackwardRegion(loop, nodes, indexByNode, i, label);
                }
            } else if (node instanceof TableSwitchInsnNode table) {
                markBackwardRegion(loop, nodes, indexByNode, i, table.dflt);
                for (LabelNode label : table.labels) {
                    markBackwardRegion(loop, nodes, indexByNode, i, label);
                }
            }
        }
        return loop;
    }

    private void markBackwardRegion(
        Set<AbstractInsnNode> loop,
        AbstractInsnNode[] nodes,
        Map<AbstractInsnNode, Integer> indexByNode,
        int sourceIndex,
        LabelNode target
    ) {
        Integer targetIndex = indexByNode.get(target);
        if (targetIndex == null || targetIndex > sourceIndex) {
            return;
        }
        for (int i = targetIndex; i <= sourceIndex; i++) {
            loop.add(nodes[i]);
        }
    }

    private NumericKind numericKind(AbstractInsnNode insn) {
        if (insn instanceof IincInsnNode) return NumericKind.IINC;
        int opcode = insn.getOpcode();
        if ((opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
            || opcode == Opcodes.BIPUSH
            || opcode == Opcodes.SIPUSH) {
            return NumericKind.INT;
        }
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return NumericKind.LONG;
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return NumericKind.FLOAT;
        if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return NumericKind.DOUBLE;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number cst) {
            if (cst instanceof Integer) return NumericKind.INT;
            if (cst instanceof Long) return NumericKind.LONG;
            if (cst instanceof Float) return NumericKind.FLOAT;
            if (cst instanceof Double) return NumericKind.DOUBLE;
        }
        return null;
    }

    private int intConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return ((IntInsnNode) insn).operand;
        }
        return (Integer) ((LdcInsnNode) insn).cst;
    }

    private long longConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;
        return (Long) ((LdcInsnNode) insn).cst;
    }

    private float floatConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) {
            return opcode - Opcodes.FCONST_0;
        }
        return (Float) ((LdcInsnNode) insn).cst;
    }

    private double doubleConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.DCONST_0) return 0.0d;
        if (opcode == Opcodes.DCONST_1) return 1.0d;
        return (Double) ((LdcInsnNode) insn).cst;
    }

    private long siteSeed(
        long masterSeed,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffInstructionState state,
        int ordinal
    ) {
        long h = JvmPassBytecode.mix(masterSeed ^ 0x434F4E53544F4246L, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, state.blockIndex());
        h = JvmPassBytecode.mix(h, state.state());
        return JvmPassBytecode.mix(h, ordinal);
    }

    private MethodNode findOrCreateClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name)) return method;
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private AbstractInsnNode firstReturn(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) return insn;
        }
        return null;
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x5A17C9E3 : v;
    }

    private enum NumericKind {
        INT(false),
        LONG(true),
        FLOAT(false),
        DOUBLE(true),
        IINC(false);

        private final boolean wideCache;

        NumericKind(boolean wideCache) {
            this.wideCache = wideCache;
        }

        boolean wideCache() {
            return wideCache;
        }
    }

    private record NumericSite(
        AbstractInsnNode insn,
        NumericKind kind,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed
    ) {}

    private record FlowSite(
        AbstractInsnNode insn,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {}

    private record ArrayConstantSite(
        AbstractInsnNode lengthInsn,
        PrimitiveArrayKind kind,
        int length,
        List<ArrayElement> elements,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        List<AbstractInsnNode> consumed
    ) {
        ArrayConstantSite withSeed(long seed) {
            return new ArrayConstantSite(lengthInsn, kind, length, elements, state, seed, consumed);
        }
    }

    private record ArrayElement(int index, long valueBits) {}

    private enum PrimitiveArrayKind {
        BOOLEAN(Opcodes.T_BOOLEAN, Opcodes.BASTORE, NumericKind.INT),
        BYTE(Opcodes.T_BYTE, Opcodes.BASTORE, NumericKind.INT),
        CHAR(Opcodes.T_CHAR, Opcodes.CASTORE, NumericKind.INT),
        SHORT(Opcodes.T_SHORT, Opcodes.SASTORE, NumericKind.INT),
        INT(Opcodes.T_INT, Opcodes.IASTORE, NumericKind.INT),
        LONG(Opcodes.T_LONG, Opcodes.LASTORE, NumericKind.LONG),
        FLOAT(Opcodes.T_FLOAT, Opcodes.FASTORE, NumericKind.FLOAT),
        DOUBLE(Opcodes.T_DOUBLE, Opcodes.DASTORE, NumericKind.DOUBLE);

        private final int newArrayOperand;
        private final int storeOpcode;
        private final NumericKind valueKind;

        PrimitiveArrayKind(int newArrayOperand, int storeOpcode, NumericKind valueKind) {
            this.newArrayOperand = newArrayOperand;
            this.storeOpcode = storeOpcode;
            this.valueKind = valueKind;
        }

        int newArrayOperand() {
            return newArrayOperand;
        }

        int storeOpcode() {
            return storeOpcode;
        }

        NumericKind valueKind() {
            return valueKind;
        }

        static PrimitiveArrayKind fromNewArrayOperand(int operand) {
            for (PrimitiveArrayKind kind : values()) {
                if (kind.newArrayOperand == operand) return kind;
            }
            return null;
        }
    }

}
