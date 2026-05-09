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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Keyed control-flow flattening for verifier-stable JVM methods.
 *
 * <p>This pass rewrites eligible straight-line methods into a dispatcher loop.
 * Every transition stores the next state encoded with the current method key,
 * and the dispatcher decodes that value immediately before the lookup switch.
 * Existing branch-heavy methods are left for later CFG-aware flattening instead
 * of receiving shape-specific rewrites.</p>
 */
public final class ControlFlowFlatteningPass implements TransformPass {
    public static final String ID = "controlFlowFlattening";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Control Flow Flattening";
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
        return Set.of(JvmKeyDispatchPass.ID);
    }

    @Override
    public void transformClass(TransformContext ctx) {
        // Flattening is method-local; class state is supplied by key dispatch.
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;

        if (!isFlatteningCandidate(pctx, clazz, method)) {
            JvmObfuscationCoverage.get(ctx).notApplicable(id(), clazz.name(), method.name(),
                method.descriptor(), "method-shape-not-flattened");
            return;
        }

        MethodNode mn = method.asmNode();
        List<AbstractInsnNode> code = bytecodeInstructions(mn);
        if (code.size() < 8 || !lastInstructionTerminates(code)) {
            JvmObfuscationCoverage.get(ctx).notApplicable(id(), clazz.name(), method.name(),
                method.descriptor(), "too-small-or-open-ended");
            return;
        }

        List<List<AbstractInsnNode>> blocks = splitStackBalancedBlocks(clazz.name(), mn, code, targetBlockCount(ctx));
        if (blocks.size() < 2) {
            JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
                method.descriptor(), "single-balanced-block");
            return;
        }

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        Integer recordedKeyLocal = JvmKeyDispatchPass.findMethodKeyLocal(ctx, methodKey);
        int keyLocal = recordedKeyLocal != null ? recordedKeyLocal : mn.maxLocals;
        long methodSeed = JvmKeyDispatchPass.methodSeed(pctx.masterSeed(), clazz, method, mn);
        if (recordedKeyLocal == null) {
            mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
            JvmKeyDispatchPass.recordMethodKeyLocal(ctx, methodKey, keyLocal, methodSeed);
        }
        int stateLocal = mn.maxLocals;
        mn.maxLocals = Math.max(mn.maxLocals, stateLocal + 1);

        long salt = JvmPassBytecode.mix(pctx.masterSeed(), methodKey.hashCode());
        int stateMask = (int) salt;
        int[] states = uniqueStates((int) (salt >>> 32), blocks.size());

        InsnList flattened = new InsnList();
        LabelNode dispatcher = new LabelNode();
        LabelNode dflt = new LabelNode();
        LabelNode[] labels = new LabelNode[blocks.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = new LabelNode();

        JvmKeyDispatchPass.emitKeyInit(flattened, keyLocal, methodSeed, 0x4346464B65794C31L);
        emitStoreEncodedState(flattened, stateLocal, keyLocal, states[0], stateMask);
        flattened.add(new JumpInsnNode(Opcodes.GOTO, dispatcher));
        flattened.add(dispatcher);
        emitDecodeState(flattened, stateLocal, keyLocal, stateMask);

        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        for (int i = 0; i < states.length; i++) cases.put(states[i], labels[i]);
        flattened.add(new LookupSwitchInsnNode(dflt,
            cases.keySet().stream().mapToInt(Integer::intValue).toArray(),
            cases.values().toArray(LabelNode[]::new)));

        flattened.add(dflt);
        flattened.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        flattened.add(new InsnNode(Opcodes.DUP));
        flattened.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/IllegalStateException", "<init>", "()V", false));
        flattened.add(new InsnNode(Opcodes.ATHROW));

        for (int i = 0; i < blocks.size(); i++) {
            flattened.add(labels[i]);
            for (AbstractInsnNode insn : blocks.get(i)) {
                flattened.add(insn.clone(new HashMap<>()));
            }
            if (i + 1 < blocks.size()) {
                emitStoreEncodedState(flattened, stateLocal, keyLocal, states[i + 1], stateMask);
                flattened.add(new JumpInsnNode(Opcodes.GOTO, dispatcher));
            }
        }

        mn.instructions = flattened;
        mn.tryCatchBlocks = List.of();
        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = Math.max(mn.maxStack, 5);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), "keyed-dispatcher-blocks-" + blocks.size());
    }

    private boolean isFlatteningCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (!JvmKeyDispatchPass.isKeyCandidate(pctx, clazz, method)) return false;
        if (method.isConstructor() || method.isClassInit()) return false;
        if ((method.access() & Opcodes.ACC_SYNCHRONIZED) != 0) return false;
        MethodNode mn = method.asmNode();
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return false;
        int parameterLocalLimit = parameterLocalLimit(method);
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) return false;
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET || opcode == Opcodes.MONITORENTER
                    || opcode == Opcodes.MONITOREXIT) return false;
            if (insn instanceof VarInsnNode varInsn && writesLocal(opcode)
                    && varInsn.var >= parameterLocalLimit) return false;
            if (insn instanceof org.objectweb.asm.tree.IincInsnNode iincInsn
                    && iincInsn.var >= parameterLocalLimit) return false;
        }
        return !TransformGuards.isSupportMethod(method);
    }

    private int parameterLocalLimit(L1Method method) {
        int slot = method.isStatic() ? 0 : 1;
        for (org.objectweb.asm.Type argument : method.argumentTypes()) {
            slot += argument.getSize();
        }
        return slot;
    }

    private boolean writesLocal(int opcode) {
        return opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
    }

    private int targetBlockCount(TransformContext ctx) {
        double intensity = Math.max(0.1, Math.min(1.0, ctx.config().getTransformIntensity(id())));
        Object configured = ctx.config().transforms().getOrDefault(id(),
            new dev.nekoobfuscator.api.config.TransformConfig(true)).options().get("maxBlocks");
        if (configured instanceof Number n) return Math.max(2, n.intValue());
        return Math.max(2, (int) Math.round(3 + intensity * 9));
    }

    private List<AbstractInsnNode> bytecodeInstructions(MethodNode mn) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) result.add(insn);
        }
        return result;
    }

    private boolean lastInstructionTerminates(List<AbstractInsnNode> code) {
        int opcode = code.get(code.size() - 1).getOpcode();
        return (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
    }

    private List<List<AbstractInsnNode>> splitStackBalancedBlocks(String owner, MethodNode mn,
            List<AbstractInsnNode> code, int maxBlocks) {
        Set<AbstractInsnNode> splitAfter = stackZeroSplitPoints(owner, mn);
        List<List<AbstractInsnNode>> blocks = new ArrayList<>();
        List<AbstractInsnNode> current = new ArrayList<>();
        int minBlockSize = Math.max(2, code.size() / Math.max(2, maxBlocks));
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode insn = code.get(i);
            current.add(insn);
            boolean last = i == code.size() - 1;
            boolean canSplit = splitAfter.contains(insn) && current.size() >= minBlockSize;
            if (!last && canSplit && blocks.size() + 1 < maxBlocks) {
                blocks.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) blocks.add(current);
        return blocks;
    }

    private Set<AbstractInsnNode> stackZeroSplitPoints(String owner, MethodNode mn) {
        Set<AbstractInsnNode> result = new HashSet<>();
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(owner, mn);
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length - 1; i++) {
                if (insns[i].getOpcode() < 0) continue;
                int next = nextBytecodeIndex(insns, i + 1);
                if (next < 0 || frames[next] == null) continue;
                if (frames[next].getStackSize() == 0) result.add(insns[i]);
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    private int nextBytecodeIndex(AbstractInsnNode[] insns, int start) {
        for (int i = start; i < insns.length; i++) {
            if (insns[i].getOpcode() >= 0) return i;
        }
        return -1;
    }

    private int[] uniqueStates(int seed, int count) {
        int[] states = new int[count];
        Set<Integer> used = new HashSet<>();
        long state = seed;
        for (int i = 0; i < count; i++) {
            int candidate;
            do {
                state = JvmPassBytecode.mix(state, i + 0x51ED2705L);
                candidate = (int) state;
            } while (!used.add(candidate));
            states[i] = candidate;
        }
        return states;
    }

    private void emitStoreEncodedState(InsnList insns, int stateLocal, int keyLocal, int state, int mask) {
        JvmPassBytecode.pushInt(insns, state ^ mask);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
    }

    private void emitDecodeState(InsnList insns, int stateLocal, int keyLocal, int mask) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        JvmPassBytecode.pushInt(insns, mask);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }
}
