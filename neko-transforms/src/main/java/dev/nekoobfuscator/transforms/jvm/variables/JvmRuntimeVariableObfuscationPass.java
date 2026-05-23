package dev.nekoobfuscator.transforms.jvm.variables;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import dev.nekoobfuscator.transforms.jvm.validation.JvmValidationSinkHardeningPass;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Keeps protected application locals out of their original plaintext slots.
 *
 * <p>The pass runs after CFF and derives store-site masks from live CFF state.
 * Primitive values are kept in encrypted shadow locals, while reference values
 * are moved to verifier-valid shadow locals and the original slot is nulled.</p>
 */
public final class JvmRuntimeVariableObfuscationPass implements TransformPass {
    public static final String ID = "runtimeVariableObfuscation";

    private static final int MASK_A = 0x6D2B79F5;
    private static final int MASK_B = 0x1B873593;
    private static final int MASK_C = 0x85EBCA6B;
    private static final int MASK_D = 0xC2B2AE35;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Runtime Variable Obfuscation";
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
        return Set.of(ControlFlowFlatteningPass.ID, JvmValidationSinkHardeningPass.ID);
    }

    @Override
    public void transformClass(TransformContext ctx) {
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return;
        if (method.isAbstract() || method.isNative()) return;

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        ControlFlowFlatteningPass.CffMethodMetadata metadata =
            ControlFlowFlatteningPass.methodMetadata(pctx).get(methodKey);
        if (metadata == null || metadata.classKeyTable() == null) return;

        MethodNode mn = method.asmNode();
        Map<LocalKey, LocalShadow> shadows = planShadows(mn, metadata);
        if (shadows.isEmpty()) return;

        allocateShadows(mn, shadows, metadata);
        int initialized = initializeShadowDefaults(pctx, mn, shadows);
        int encodedBranches = rewriteEncodedZeroBranches(pctx, mn, metadata, shadows);
        int rewritten = rewriteLocalInstructions(pctx, mn, metadata, shadows);
        if (initialized + encodedBranches + rewritten == 0) return;

        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = Math.max(mn.maxStack, 24);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(
            id(),
            clazz.name(),
            method.name(),
            method.descriptor(),
            "cff-keyed-local-shadows-init-" + initialized +
                "-branches-" + encodedBranches +
                "-sites-" + rewritten
        );
    }

    private Map<LocalKey, LocalShadow> planShadows(
        MethodNode mn,
        ControlFlowFlatteningPass.CffMethodMetadata metadata
    ) {
        Map<LocalKey, LocalShadow> shadows = new LinkedHashMap<>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            if (insn instanceof VarInsnNode var) {
                LocalKind kind = kindForVarOpcode(var.getOpcode());
                if (kind != null && isStoreOpcode(var.getOpcode()) && !isReservedLocal(var.var, metadata)) {
                    shadows.putIfAbsent(new LocalKey(var.var, kind), new LocalShadow(var.var, kind));
                }
            }
        }
        return shadows;
    }

    private void allocateShadows(
        MethodNode mn,
        Map<LocalKey, LocalShadow> shadows,
        ControlFlowFlatteningPass.CffMethodMetadata metadata
    ) {
        int next = mn.maxLocals;
        List<Map.Entry<LocalKey, LocalShadow>> ordered = new ArrayList<>(shadows.entrySet());
        ordered.sort(Comparator
            .comparingInt((Map.Entry<LocalKey, LocalShadow> e) -> e.getKey().var())
            .thenComparing(e -> e.getKey().kind().ordinal()));
        for (Map.Entry<LocalKey, LocalShadow> entry : ordered) {
            LocalShadow shadow = entry.getValue();
            shadow.shadowLocal = next;
            next += shadow.kind.shadowSize();
            if (shadow.kind.primitive()) {
                shadow.maskLocal = next;
                next += shadow.kind.maskSize();
            }
        }
        mn.maxLocals = Math.max(mn.maxLocals, next);
    }

    private int initializeShadowDefaults(
        PipelineContext pctx,
        MethodNode mn,
        Map<LocalKey, LocalShadow> shadows
    ) {
        int changed = 0;
        List<LocalShadow> ordered = shadows.values().stream()
            .sorted(Comparator.comparingInt(LocalShadow::var))
            .toList();
        InsnList init = new InsnList();
        for (LocalShadow shadow : ordered) {
            emitDefaultShadow(init, shadow);
            changed++;
        }
        if (changed > 0) {
            JvmKeyDispatchPass.markGenerated(pctx, init);
            mn.instructions.insert(init);
        }
        return changed;
    }

    private int rewriteEncodedZeroBranches(
        PipelineContext pctx,
        MethodNode mn,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        Map<LocalKey, LocalShadow> shadows
    ) {
        int changed = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!(insn instanceof JumpInsnNode jump)) continue;
            if (!metadata.applicationInstructions().contains(insn)) continue;
            int opcode = jump.getOpcode();
            if (opcode != Opcodes.IFEQ && opcode != Opcodes.IFNE) continue;
            AbstractInsnNode previous = previousReal(insn.getPrevious());
            if (!(previous instanceof VarInsnNode load)) continue;
            if (load.getOpcode() != Opcodes.ILOAD || !metadata.applicationInstructions().contains(load)) continue;
            LocalShadow shadow = shadows.get(new LocalKey(load.var, LocalKind.INT));
            if (shadow == null || shadow.shadowLocal < 0 || shadow.maskLocal < 0) continue;

            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ILOAD, shadow.shadowLocal));
            replacement.add(new VarInsnNode(Opcodes.ILOAD, shadow.maskLocal));
            replacement.add(new JumpInsnNode(opcode == Opcodes.IFEQ ? Opcodes.IF_ICMPEQ : Opcodes.IF_ICMPNE, jump.label));
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(previous, replacement);
            mn.instructions.remove(previous);
            mn.instructions.remove(insn);
            changed++;
        }
        return changed;
    }

    private int rewriteLocalInstructions(
        PipelineContext pctx,
        MethodNode mn,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        Map<LocalKey, LocalShadow> shadows
    ) {
        int changed = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            ControlFlowFlatteningPass.CffInstructionState state = metadata.instructionStates().get(insn);
            if (state == null) continue;
            if (insn instanceof VarInsnNode var) {
                LocalKind kind = kindForVarOpcode(var.getOpcode());
                if (kind == null) continue;
                LocalShadow shadow = shadows.get(new LocalKey(var.var, kind));
                if (shadow == null || shadow.shadowLocal < 0) continue;
                InsnList replacement = new InsnList();
                if (isLoadOpcode(var.getOpcode())) {
                    emitLoadFromShadow(replacement, shadow);
                } else if (isStoreOpcode(var.getOpcode())) {
                    emitStoreShadowFromStack(replacement, shadow, metadata, state);
                } else {
                    continue;
                }
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                changed++;
            } else if (insn instanceof IincInsnNode iinc) {
                LocalShadow shadow = shadows.get(new LocalKey(iinc.var, LocalKind.INT));
                if (shadow == null || shadow.shadowLocal < 0 || shadow.maskLocal < 0) continue;
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ILOAD, shadow.shadowLocal));
                replacement.add(new VarInsnNode(Opcodes.ILOAD, shadow.maskLocal));
                replacement.add(new InsnNode(Opcodes.IXOR));
                JvmPassBytecode.pushInt(replacement, iinc.incr);
                replacement.add(new InsnNode(Opcodes.IADD));
                emitStoreShadowFromStack(replacement, shadow, metadata, state);
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                changed++;
            }
        }
        return changed;
    }

    private void emitDefaultShadow(InsnList insns, LocalShadow shadow) {
        switch (shadow.kind) {
            case INT, FLOAT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.maskLocal));
            }
            case LONG, DOUBLE -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.maskLocal));
            }
            case REF -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new VarInsnNode(Opcodes.ASTORE, shadow.shadowLocal));
            }
        }
    }

    private void emitStoreShadowFromStack(
        InsnList insns,
        LocalShadow shadow,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        switch (shadow.kind) {
            case INT -> {
                insns.add(new InsnNode(Opcodes.DUP));
                emitMaskInt(insns, metadata, state, shadow);
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.maskLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.POP));
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.var));
            }
            case LONG -> {
                insns.add(new InsnNode(Opcodes.DUP2));
                emitMaskLong(insns, metadata, state, shadow);
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.maskLocal));
                insns.add(new VarInsnNode(Opcodes.LLOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.LXOR));
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.POP2));
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.var));
            }
            case FLOAT -> {
                insns.add(new InsnNode(Opcodes.DUP));
                emitMaskInt(insns, metadata, state, shadow);
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.maskLocal));
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "floatToRawIntBits",
                    "(F)I",
                    false
                ));
                insns.add(new VarInsnNode(Opcodes.ILOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ISTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.POP));
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new VarInsnNode(Opcodes.FSTORE, shadow.var));
            }
            case DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DUP2));
                emitMaskLong(insns, metadata, state, shadow);
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.maskLocal));
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "doubleToRawLongBits",
                    "(D)J",
                    false
                ));
                insns.add(new VarInsnNode(Opcodes.LLOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.LXOR));
                insns.add(new VarInsnNode(Opcodes.LSTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.POP2));
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new VarInsnNode(Opcodes.DSTORE, shadow.var));
            }
            case REF -> {
                insns.add(new VarInsnNode(Opcodes.ASTORE, shadow.shadowLocal));
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new VarInsnNode(Opcodes.ASTORE, shadow.var));
            }
        }
    }

    private void emitLoadFromShadow(InsnList insns, LocalShadow shadow) {
        switch (shadow.kind) {
            case INT -> {
                insns.add(new VarInsnNode(Opcodes.ILOAD, shadow.shadowLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            case LONG -> {
                insns.add(new VarInsnNode(Opcodes.LLOAD, shadow.shadowLocal));
                insns.add(new VarInsnNode(Opcodes.LLOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.LXOR));
            }
            case FLOAT -> {
                insns.add(new VarInsnNode(Opcodes.ILOAD, shadow.shadowLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case DOUBLE -> {
                insns.add(new VarInsnNode(Opcodes.LLOAD, shadow.shadowLocal));
                insns.add(new VarInsnNode(Opcodes.LLOAD, shadow.maskLocal));
                insns.add(new InsnNode(Opcodes.LXOR));
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
            case REF -> insns.add(new VarInsnNode(Opcodes.ALOAD, shadow.shadowLocal));
        }
    }

    private void emitMaskLong(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        LocalShadow shadow
    ) {
        emitMaskInt(insns, metadata, state, shadow.withDomain(0x48494748));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitMaskInt(insns, metadata, state, shadow.withDomain(0x4C4F574B));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private void emitMaskInt(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        LocalShadow shadow
    ) {
        long seed = JvmPassBytecode.mix(
            metadata.methodSeed() ^ state.selectorSeed() ^ 0x52564F4D41534B31L,
            ((long) shadow.var << 32) ^ shadow.kind.ordinal() ^ shadow.domain
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x47554152444D31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x424C4F434B4D31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4D554C4D41534B31L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        emitMethodKeyFold(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x50434D41534B3031L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544154454D31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, metadata.classKeyTable().values().length - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new org.objectweb.asm.tree.FieldInsnNode(
            Opcodes.GETSTATIC,
            metadata.classKeyTable().owner(),
            metadata.classKeyTable().objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new InsnNode(Opcodes.SWAP));
        ControlFlowFlatteningPass.emitDecodedSealedClassKeyWord(
            insns,
            ControlFlowFlatteningPass.CLASS_KEY_WORD_SEAL
        );
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5441424D41534B31L)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, MASK_A ^ (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (MASK_B ^ (int) (seed >>> 32)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, MASK_C + state.blockIndex());
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, MASK_D ^ shadow.var);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitMethodKeyFold(
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

    private AbstractInsnNode previousReal(AbstractInsnNode insn) {
        for (AbstractInsnNode cursor = insn; cursor != null; cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    private boolean isReservedLocal(
        int local,
        ControlFlowFlatteningPass.CffMethodMetadata metadata
    ) {
        return local == metadata.keyLocal()
            || local == metadata.keyLocal() + 1
            || local == metadata.guardLocal()
            || local == metadata.pathKeyLocal()
            || local == metadata.blockKeyLocal()
            || local == metadata.pcLocal()
            || local == metadata.domainLocal();
    }

    private LocalKind kindForVarOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> LocalKind.INT;
            case Opcodes.LLOAD, Opcodes.LSTORE -> LocalKind.LONG;
            case Opcodes.FLOAD, Opcodes.FSTORE -> LocalKind.FLOAT;
            case Opcodes.DLOAD, Opcodes.DSTORE -> LocalKind.DOUBLE;
            case Opcodes.ALOAD, Opcodes.ASTORE -> LocalKind.REF;
            default -> null;
        };
    }

    private boolean isLoadOpcode(int opcode) {
        return opcode == Opcodes.ILOAD
            || opcode == Opcodes.LLOAD
            || opcode == Opcodes.FLOAD
            || opcode == Opcodes.DLOAD
            || opcode == Opcodes.ALOAD;
    }

    private boolean isStoreOpcode(int opcode) {
        return opcode == Opcodes.ISTORE
            || opcode == Opcodes.LSTORE
            || opcode == Opcodes.FSTORE
            || opcode == Opcodes.DSTORE
            || opcode == Opcodes.ASTORE;
    }

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30L);
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x52564F31 : v;
    }

    private enum LocalKind {
        INT(1, 1, true),
        LONG(2, 2, true),
        FLOAT(1, 1, true),
        DOUBLE(2, 2, true),
        REF(1, 0, false);

        private final int shadowSize;
        private final int maskSize;
        private final boolean primitive;

        LocalKind(int shadowSize, int maskSize, boolean primitive) {
            this.shadowSize = shadowSize;
            this.maskSize = maskSize;
            this.primitive = primitive;
        }

        int shadowSize() {
            return shadowSize;
        }

        int maskSize() {
            return maskSize;
        }

        boolean primitive() {
            return primitive;
        }
    }

    private record LocalKey(int var, LocalKind kind) {}

    private static final class LocalShadow {
        private final int var;
        private final LocalKind kind;
        private final int domain;
        private int shadowLocal = -1;
        private int maskLocal = -1;

        LocalShadow(int var, LocalKind kind) {
            this(var, kind, 0);
        }

        LocalShadow(int var, LocalKind kind, int domain) {
            this.var = var;
            this.kind = kind;
            this.domain = domain;
        }

        LocalShadow withDomain(int domain) {
            LocalShadow copy = new LocalShadow(var, kind, domain);
            copy.shadowLocal = shadowLocal;
            copy.maskLocal = maskLocal;
            return copy;
        }

        int var() {
            return var;
        }

    }
}
