package dev.nekoobfuscator.transforms.jvm;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Establishes hidden key material for JVM obfuscation passes.
 *
 * <p>Methods that can safely carry a caller key are rewritten in place from
 * {@code method(args...)} to {@code method(args..., long)}. Boundary methods
 * such as constructors and Java entry points keep their JVM-mandated shape and
 * seed a root key locally. CFF then consumes the same local key, so path
 * evolution and call-chain key dispatch share one skeleton without generating
 * companion implementation methods.</p>
 */
public final class JvmKeyDispatchPass implements TransformPass {
    public static final String ID = "keyDispatch";
    static final String LOCAL_BY_METHOD = "keyDispatch.localByMethod";
    static final String SEED_BY_METHOD = "keyDispatch.seedByMethod";
    static final String CFF_LOCAL_BY_METHOD = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String PREPARED = "keyDispatch.preparedInPlaceSignatures";
    private static final String SIGNATURE_BY_CALL = "keyDispatch.signatureByCall";
    private static final String SIGNATURE_BY_METHOD = "keyDispatch.signatureByMethod";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Key Dispatch";
    }

    @Override
    public TransformPhase phase() {
        return TransformPhase.PRE_TRANSFORM;
    }

    @Override
    public IRLevel requiredLevel() {
        return IRLevel.L1;
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        if (Boolean.TRUE.equals(ctx.getPassData(PREPARED))) return;
        ctx.putPassData(PREPARED, Boolean.TRUE);

        for (L1Class clazz : new ArrayList<>(pctx.classMap().values())) {
            prepareInPlaceKeySignatures(pctx, clazz);
        }
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;

        if (!isKeyCandidate(pctx, clazz, method)) {
            JvmObfuscationCoverage.get(ctx).notApplicable(id(), clazz.name(), method.name(),
                method.descriptor(), "method-shape-not-keyed");
            return;
        }

        MethodNode mn = method.asmNode();
        String methodKey = coverageKey(clazz, method);
        Map<String, Integer> locals = localMap(ctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(methodKey);
        if (existing != null) {
            publishControlFlowLocal(ctx, methodKey, existing);
            JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
                method.descriptor(), "key-local-already-present");
            return;
        }

        SignatureTarget self = signatureByMethod(ctx).get(methodKey);
        int keyLocal = self == null ? mn.maxLocals : self.hiddenKeySlot();
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        if (self == null) {
            emitKeyInit(prologue, keyLocal, seed, 0x4E4B4F4A564D4B31L);
        } else {
            emitIncomingKeyInit(prologue, keyLocal, seed);
        }

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return;
        mn.instructions.insertBefore(first, prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        rewriteApplicationCalls(pctx, mn, keyLocal);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(ctx, methodKey, keyLocal, seed);
        JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
            method.descriptor(), "method-key-local");
    }

    static boolean isKeyCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (method.isAbstract() || method.isNative()) return false;
        if (TransformGuards.hasStackIntrospection(method)) return false;
        return !TransformGuards.isReflectionShapeSensitive(pctx, clazz);
    }

    static int ensureMethodKeyLocal(PipelineContext pctx, L1Class clazz, L1Method method) {
        String key = coverageKey(clazz, method);
        Map<String, Integer> locals = localMap(pctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(key);
        if (existing != null) {
            publishControlFlowLocal(pctx, key, existing);
            return existing;
        }

        MethodNode mn = method.asmNode();
        SignatureTarget self = signatureByMethod(pctx).get(key);
        int keyLocal = self == null ? mn.maxLocals : self.hiddenKeySlot();
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        if (self == null) {
            emitKeyInit(prologue, keyLocal, seed, 0x6A766D4B65794C31L);
        } else {
            emitIncomingKeyInit(prologue, keyLocal, seed);
        }

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return -1;
        mn.instructions.insertBefore(first, prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        rewriteApplicationCalls(pctx, mn, keyLocal);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(pctx, key, keyLocal, seed);
        return keyLocal;
    }

    static Integer findMethodKeyLocal(TransformContext ctx, String methodKey) {
        return localMap(ctx, LOCAL_BY_METHOD).get(methodKey);
    }

    static Long findMethodSeed(TransformContext ctx, String methodKey) {
        return seedMap(ctx).get(methodKey);
    }

    static void recordMethodKeyLocal(TransformContext ctx, String methodKey, int keyLocal, long seed) {
        localMap(ctx, LOCAL_BY_METHOD).put(methodKey, keyLocal);
        seedMap(ctx).put(methodKey, seed);
        publishControlFlowLocal(ctx, methodKey, keyLocal);
    }

    static void emitKeyInit(InsnList insns, int keyLocal, long seed, long mask) {
        JvmPassBytecode.pushLong(insns, seed ^ mask);
        JvmPassBytecode.pushLong(insns, mask);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LXOR));
        insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    static void emitIncomingKeyInit(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LADD));
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LUSHR));
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    static String coverageKey(L1Class clazz, L1Method method) {
        return clazz.name() + "." + method.name() + method.descriptor();
    }

    static long methodSeed(long masterSeed, L1Class clazz, L1Method method, MethodNode mn) {
        long h = masterSeed ^ 0x9E3779B97F4A7C15L;
        h = JvmPassBytecode.mix(h, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, mn.instructions == null ? 0 : mn.instructions.size());
        h = JvmPassBytecode.mix(h, mn.maxLocals);
        return h == 0L ? 0x5DEECE66DL : h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> localMap(TransformContext ctx, String key) {
        Map<String, Integer> map = ctx.getPassData(key);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(key, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> seedMap(TransformContext ctx) {
        Map<String, Long> map = ctx.getPassData(SEED_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(SEED_BY_METHOD, map);
        }
        return map;
    }

    private static void publishControlFlowLocal(TransformContext ctx, String methodKey, int keyLocal) {
        localMap(ctx, CFF_LOCAL_BY_METHOD).put(methodKey, keyLocal);
    }

    private void prepareInPlaceKeySignatures(PipelineContext pctx, L1Class clazz) {
        if (clazz == null || TransformGuards.isRuntimeClass(clazz) || clazz.isInterface()) return;
        if (TransformGuards.isReflectionShapeSensitive(pctx, clazz)) return;

        List<MethodNode> methods = new ArrayList<>(clazz.asmNode().methods);
        for (MethodNode mn : methods) {
            L1Method method = new L1Method(clazz, mn);
            if (!isSignatureCarrierCandidate(pctx, clazz, method)) continue;

            String originalDesc = mn.desc;
            String newDesc = appendLongArgument(originalDesc);
            int hiddenSlot = parameterLocalLimit(mn.access, originalDesc);
            shiftLocalsForHiddenKey(mn, hiddenSlot);
            clearDebugLocals(mn);
            removeFrames(mn);
            mn.desc = newDesc;
            mn.maxLocals = Math.max(mn.maxLocals + 2, hiddenSlot + 2);

            SignatureTarget target = new SignatureTarget(clazz.name(), mn.name, originalDesc, newDesc, hiddenSlot);
            signatureByCall(pctx).put(callKey(clazz.name(), mn.name, originalDesc), target);
            signatureByMethod(pctx).put(clazz.name() + "." + mn.name + newDesc, target);
            clazz.markDirty();
        }
    }

    private boolean isSignatureCarrierCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (!isKeyCandidate(pctx, clazz, method)) return false;
        if (method.isConstructor() || method.isClassInit()) return false;
        MethodNode mn = method.asmNode();
        return !isJavaMain(mn);
    }

    private boolean isJavaMain(MethodNode mn) {
        return "main".equals(mn.name)
            && "([Ljava/lang/String;)V".equals(mn.desc)
            && (mn.access & Opcodes.ACC_STATIC) != 0
            && (mn.access & Opcodes.ACC_PUBLIC) != 0;
    }

    private static void rewriteApplicationCalls(TransformContext ctx, MethodNode method, int keyLocal) {
        boolean changed = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            SignatureTarget target = findSignatureTarget(ctx, call);
            if (target == null) continue;

            InsnList keyArg = new InsnList();
            keyArg.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            method.instructions.insertBefore(call, keyArg);
            call.desc = target.newDesc();
            changed = true;
        }
        if (changed) {
            method.maxStack = Math.max(method.maxStack, 8);
        }
    }

    private static SignatureTarget findSignatureTarget(TransformContext ctx, MethodInsnNode call) {
        SignatureTarget exact = signatureByCall(ctx).get(callKey(call.owner, call.name, call.desc));
        if (exact != null || !(ctx instanceof PipelineContext pctx)) return exact;

        String owner = call.owner;
        while (owner != null) {
            L1Class clazz = pctx.classMap().get(owner);
            if (clazz == null) return null;
            SignatureTarget inherited = signatureByCall(ctx).get(callKey(clazz.name(), call.name, call.desc));
            if (inherited != null) return inherited;
            owner = clazz.superName();
        }
        return null;
    }

    private void shiftLocalsForHiddenKey(MethodNode method, int hiddenSlot) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode var && var.var >= hiddenSlot) {
                var.var += 2;
            } else if (insn instanceof IincInsnNode iinc && iinc.var >= hiddenSlot) {
                iinc.var += 2;
            }
        }
        if (method.localVariables != null) {
            for (var local : method.localVariables) {
                if (local.index >= hiddenSlot) local.index += 2;
            }
        }
    }

    private void clearDebugLocals(MethodNode method) {
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    private void removeFrames(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof FrameNode) method.instructions.remove(insn);
            insn = next;
        }
    }

    private static int parameterLocalLimit(int access, String desc) {
        int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(desc)) {
            slot += argument.getSize();
        }
        return slot;
    }

    private static String appendLongArgument(String desc) {
        int close = desc.indexOf(')');
        return desc.substring(0, close) + "J" + desc.substring(close);
    }

    private static String callKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SignatureTarget> signatureByCall(TransformContext ctx) {
        Map<String, SignatureTarget> map = ctx.getPassData(SIGNATURE_BY_CALL);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(SIGNATURE_BY_CALL, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SignatureTarget> signatureByMethod(TransformContext ctx) {
        Map<String, SignatureTarget> map = ctx.getPassData(SIGNATURE_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(SIGNATURE_BY_METHOD, map);
        }
        return map;
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return mn.instructions.getFirst();
    }

    private record SignatureTarget(String owner, String name, String originalDesc,
            String newDesc, int hiddenKeySlot) {}
}
