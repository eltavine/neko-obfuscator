package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.jar.ClassHierarchy;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.data.NumberEncryptionPass;
import dev.nekoobfuscator.transforms.invoke.InvokeDynamicPass;
import dev.nekoobfuscator.core.util.AsmUtil;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.*;

/**
 * Control Flow Flattening: converts method CFG into a state-machine dispatcher.
 * Each basic block becomes a case in a switch statement.
 */
public final class ControlFlowFlatteningPass implements TransformPass {
    private static final String FLATTENED_METHODS_KEY = "controlFlowFlattening.methods";
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String INBOUND_FLOW_KEY_READS_KEY = "controlFlowFlattening.inboundFlowKeyReads";
    public static final String FLOW_KEY_LOCAL_BY_METHOD_KEY = "controlFlowFlattening.flowKeyLocalByMethod";
    public static final String HARDEN_GENERATED_HELPERS_KEY = "controlFlowFlattening.hardenGeneratedHelpers";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final String ZKM_STYLE_OPTION = "zkmStyle";
    private static final String TAIL_CHAIN_INTENSITY_OPTION = "tailChainIntensity";
    private static final String TRY_CATCH_TAIL_CHAIN_MULTIPLIER_OPTION = "tryCatchTailChainMultiplier";
    private static final String ALLOW_TRY_CATCH_METHODS_OPTION = "allowTryCatchMethods";
    private static final String TRY_CATCH_MAIN_ONLY_OPTION = "tryCatchMainOnly";
    private static final String MAX_TRY_CATCH_BLOCKS_OPTION = "maxTryCatchBlocks";
    private static final String TRY_CATCH_BRANCH_BONUS_OPTION = "tryCatchBranchBonus";
    private static final String TRY_CATCH_INSTRUCTION_BONUS_OPTION = "tryCatchInstructionBonus";
    private static final String ENTRYPOINT_TAIL_CHAIN_MULTIPLIER_OPTION = "entrypointTailChainMultiplier";
    private static final String ENTRYPOINT_MAX_TRY_CATCH_BLOCKS_OPTION = "entrypointMaxTryCatchBlocks";
    private static final String ENTRYPOINT_BRANCH_BONUS_OPTION = "entrypointBranchBonus";
    private static final String ENTRYPOINT_INSTRUCTION_BONUS_OPTION = "entrypointInstructionBonus";
    private static final String ALLOW_SWITCH_METHODS_OPTION = "allowSwitchMethods";
    private static final String ALLOW_MONITOR_METHODS_OPTION = "allowMonitorMethods";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BACKWARD_BRANCHES_OPTION = "maxBackwardBranches";
    private static final String MAX_BRANCHES_OPTION = "maxBranchCount";
    private static final String DISPATCHER_DEPTH_OPTION = "dispatcherDepth";
    private static final String DISPATCHER_SHAPE_VARIATION_OPTION = "dispatcherShapeVariation";
    private static final String EDGE_KEYED_OPTION = "edgeKeyed";
    private static final String DISPATCHER_FRAGMENTS_OPTION = "dispatcherFragments";
    private static final String MAX_EDGE_CLONE_BLOCKS_OPTION = "maxEdgeCloneBlocks";
    private static final String LINEAR_CHUNK_SIZE_OPTION = "linearChunkSize";
    private static final String REQUIRE_FULL_STATE_MACHINE_OPTION = "requireFullStateMachine";
    private static final String ALLOW_SAFE_FALLBACKS_OPTION = "allowSafeFallbacks";
    private static final String LOOP_FAST_PATH_INSTRUCTION_THRESHOLD_OPTION = "loopFastPathInstructionThreshold";
    private static final String LOOP_FAST_PATH_BACKWARD_BRANCH_THRESHOLD_OPTION = "loopFastPathBackwardBranchThreshold";
    private static final InsnNode AFTER_HANDLER_SYNC_ANCHOR = new InsnNode(Opcodes.NOP);

    @Override public String id() { return "controlFlowFlattening"; }
    @Override public String name() { return "Control Flow Flattening"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("stackObfuscation"); }

    private dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine keyEngine;
    private String lastFrameRejectReason = "";
    private String lastConstructorRejectReason = "";

    public static void markInboundFlowKeyRead(PipelineContext pctx, AbstractInsnNode insn) {
        Set<AbstractInsnNode> reads = pctx.getPassData(INBOUND_FLOW_KEY_READS_KEY);
        if (reads == null) {
            reads = Collections.newSetFromMap(new IdentityHashMap<>());
            pctx.putPassData(INBOUND_FLOW_KEY_READS_KEY, reads);
        }
        reads.add(insn);
    }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Class clazz = pctx.currentL1Class();
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
        if (requireFullStateMachine(pctx)) {
            materializeStringConstantFieldsForCff(clazz);
        }
    }

    @Override
    public boolean isApplicable(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)) return false;
        if (method == null) return true;
        if (TransformGuards.isGeneratedMethod(method) && !hardenGeneratedHelpers) return false;
        return method.hasCode();
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();

        if (method == null || !method.hasCode()) return;
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)
                || (TransformGuards.isGeneratedMethod(method) && !hardenGeneratedHelpers)) {
            recordNotApplicable(pctx, clazz, method, "guarded-runtime-or-generated");
            return;
        }

        boolean requireFullStateMachine = requireFullStateMachine(pctx);
        if (method.isConstructor()) {
            if (requireFullStateMachine) {
                lastConstructorRejectReason = "";
                if (transformConstructorPostInit(pctx, clazz, method)) {
                    return;
                }
                failClosed(pctx, clazz, method, lastConstructorRejectReason.isBlank()
                    ? "constructor-post-init-state-machine-unavailable"
                    : lastConstructorRejectReason);
            }
            if (!insertConstructorFlowGate(pctx, method)) {
                failClosed(pctx, clazz, method, "constructor-has-no-post-init-anchor");
            }
            clazz.markDirty();
            flattenedMethods(pctx).add(methodKey(method));
            recordSafe(pctx, clazz, method, "constructor-post-init-gate");
            pctx.invalidate(method);
            return;
        }

        double intensity = pctx.config().getTransformIntensity("controlFlowFlattening");
        if (!requireFullStateMachine && pctx.random().nextDouble() > intensity) {
            recordNotApplicable(pctx, clazz, method, "intensity-gate");
            return;
        }

        if (method.instructionCount() <= 10 && !requireFullStateMachine) {
            safeFallbackOrFail(pctx, clazz, method, requireFullStateMachine, "method-too-small-for-state-machine");
            return;
        }
        if (!isStructureSafe(method, pctx) && !requireFullStateMachine) {
            safeFallbackOrFail(pctx, clazz, method, requireFullStateMachine, "method-structure-rejected-for-state-machine");
            return;
        }


        ControlFlowGraph cfg = pctx.getCFG(method);
        MethodNode mn = method.asmNode();
        int originalMaxLocalsValue = mn.maxLocals;
        int originalMaxStackValue = mn.maxStack;
        List<TryCatchBlockNode> originalTryCatchBlocks = mn.tryCatchBlocks == null
            ? null
            : new ArrayList<>(mn.tryCatchBlocks);
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzedFrames = analyzeFrames(clazz.name(), mn, pctx.hierarchy());
        List<BasicBlock> blocks = new ArrayList<>(cfg.blocks());
        List<BasicBlock> dispatchBlocks = new ArrayList<>();
        List<BasicBlock> handlerBlocks = new ArrayList<>();
        partitionBlocks(blocks, dispatchBlocks, handlerBlocks);
        if (!handlerBlocks.isEmpty()) {
            dispatchBlocks = new ArrayList<>(blocks);
        }
        BasicBlock entryBlock = cfg.entryBlock();
        if (dispatchBlocks.isEmpty()) {
            failClosed(pctx, clazz, method, "state-machine-has-no-dispatch-blocks");
        }
        if (dispatchBlocks.size() < 3 && !requireFullStateMachine) {
            List<BasicBlock> linearBlocks = splitLinearDispatchBlocks(method, dispatchBlocks,
                handlerBlocks, analyzedFrames, pctx);
            if (linearBlocks == null || linearBlocks.size() < 3) {
                safeFallbackOrFail(pctx, clazz, method, requireFullStateMachine, "small-cfg-not-splittable-for-state-machine");
                return;
            }
            blocks = linearBlocks;
            dispatchBlocks = new ArrayList<>(linearBlocks);
            handlerBlocks = new ArrayList<>();
            entryBlock = linearBlocks.get(0);
        }

        long classKey = keyEngine.deriveClassKey(clazz);
        long methodKey = keyEngine.deriveMethodKey(method, classKey);
        long methodFlowSeed = deriveMethodFlowSeed(methodKey);
        int stateMask = foldMethodKey(methodKey ^ 0x4E454B4F4C4FL);
        int stateDelta = foldMethodKey(Long.rotateLeft(methodKey, 19) ^ 0xC0DEC0DE5EEDL);
        int stateRotate = 5 + Math.floorMod((int) (methodKey >>> 11), 19);
        boolean zkmStyle = isZkmStyleEnabled(pctx);
        double tailChainIntensity = tailChainIntensity(pctx, method);

        // Assign random state numbers
        Map<BasicBlock, Integer> stateMap = new HashMap<>();
        Set<Integer> usedStates = new HashSet<>();
        for (BasicBlock block : dispatchBlocks) {
            int state = pctx.random().nextIntExcluding(usedStates);
            stateMap.put(block, state);
            usedStates.add(state);
        }

        Map<BasicBlock, Long> flowKeyMap = new HashMap<>();
        for (BasicBlock block : blocks) {
            Integer state = stateMap.get(block);
            long flowSeed = state != null
                ? deriveBlockFlowKey(methodFlowSeed, state)
                : dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
                    dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodFlowSeed,
                        0x4E454B4F00000000L ^ block.id()));
            flowKeyMap.put(block, flowSeed);
        }
        if (booleanOption(pctx.config().transforms().get("controlFlowFlattening"), EDGE_KEYED_OPTION, true)) {
            applySinglePredecessorEdgeKeys(blocks, entryBlock, flowKeyMap, methodFlowSeed);
        }

        int initialState = stateMap.get(entryBlock);
        long initialFlowKey = flowKeyMap.getOrDefault(entryBlock, 0L);

        IdentityHashMap<AbstractInsnNode, Integer> stackHeights = extractStackHeights(analyzedFrames);
        Map<BasicBlock, List<StackSlotKind>> blockEntryStacks = analyzeBlockEntryStacks(dispatchBlocks, analyzedFrames);
        Map<BasicBlock, List<String>> blockEntryStackTypes = analyzeBlockEntryStackTypes(dispatchBlocks, analyzedFrames);
        Map<BasicBlock, List<StackSlotKind>> blockExitStacks = analyzeBlockExitStacks(blocks, analyzedFrames);
        promoteConnectorStackRequirements(dispatchBlocks, blockEntryStacks, blockExitStacks);
        Map<BasicBlock, List<LocalSlotState>> blockEntryLocals = analyzeBlockEntryLocals(blocks, dispatchBlocks, analyzedFrames, originalMaxLocals(method), method);
        Map<BasicBlock, BitSet> blockExitInitializedLocals = analyzeBlockExitInitializedLocals(
            blocks, analyzedFrames, originalMaxLocals(method));
        applyExceptionHandlerStackTypes(blocks, mn.tryCatchBlocks, blockEntryStacks, blockEntryStackTypes, pctx.hierarchy());
        Map<BasicBlock, String> exceptionSpillTypes = analyzeExceptionSpillTypes(handlerBlocks, analyzedFrames);
        for (BasicBlock handlerBlock : handlerBlocks) {
            blockEntryStacks.put(handlerBlock, List.of());
            blockEntryStackTypes.put(handlerBlock, List.of());
            blockEntryLocals.put(handlerBlock, List.of());
        }
        InsnList newInsns = new InsnList();
        int originalMaxLocals = mn.maxLocals;
        int nextLocal = mn.maxLocals;
        boolean preserveInboundFlowKey = hasInboundFlowKeyRead(pctx, mn.instructions);
        int inboundFlowKeyVar = -1;
        if (preserveInboundFlowKey) {
            inboundFlowKeyVar = nextLocal;
            nextLocal += 2;
        }
        int flowKeyVar = nextLocal;
        nextLocal += 2;
        int flowMixVar = nextLocal++;
        int encodedStateVar = nextLocal++;
        int dispatchStateVar = nextLocal++;
        int stateMaskVar = nextLocal++;
        int stateDeltaVar = nextLocal++;
        int tailSeedVar = nextLocal++;
        int tailFlagVar = nextLocal++;
        Map<BasicBlock, Integer> exceptionSpillLocals = new IdentityHashMap<>();
        for (BasicBlock handlerBlock : handlerBlocks) {
            exceptionSpillLocals.put(handlerBlock, nextLocal++);
        }
        Map<BasicBlock, Integer> blockSpillBases = new IdentityHashMap<>();
        nextLocal = allocateSpillLocals(dispatchBlocks, blockEntryStacks, blockSpillBases, nextLocal);
        Map<BasicBlock, Integer> blockLocalSpillBases = new IdentityHashMap<>();
        nextLocal = allocateLocalSpillLocals(dispatchBlocks, blockEntryLocals, blockLocalSpillBases, nextLocal);
        int transformedMaxLocals = nextLocal;

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();

        // Label remap for internal jumps within blocks
        Map<LabelNode, LabelNode> labelRemap = new HashMap<>();
        for (BasicBlock block : blocks) {
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode origLabel) {
                    labelRemap.put(origLabel, new LabelNode());
                }
            }
        }
        Map<LabelNode, ExceptionHandlerStub> exceptionHandlerStubs = buildExceptionHandlerStubs(
            mn.tryCatchBlocks, blocks);

        if (preserveInboundFlowKey) {
            emitInboundFlowKeyCapture(newInsns, inboundFlowKeyVar);
        }
        emitOriginalLocalInitialization(newInsns, method, originalMaxLocals);
        emitExceptionSpillInitializers(newInsns, exceptionSpillLocals);
        initializeSyntheticSpillLocals(newInsns, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        spillLocalsForTarget(newInsns, entryBlock, blockEntryLocals, blockLocalSpillBases);

        // Prologue masks are split into 2-LDC XOR so static analysis can't read
        // mask/delta/rot as a single literal at the method head and reverse the
        // state-encoding function in one pass.
        emitSplitIntStore(newInsns, stateMask, stateMaskVar, methodKey);
        emitSplitIntStore(newInsns, stateDelta, stateDeltaVar,
            Long.rotateLeft(methodKey, 7) ^ 0x6B6F4D5A4D533132L);
        int tailSeedValue = foldMethodKey(Long.rotateRight(methodKey, 27) ^ 0x5A4B4D7E1F2DL);
        emitSplitIntStore(newInsns, tailSeedValue, tailSeedVar,
            Long.rotateLeft(methodKey, 19) ^ 0x4D734E7E5443BFL);
        newInsns.add(new InsnNode(Opcodes.ICONST_0));
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, tailFlagVar));
        emitFlowKeyAbsolute(newInsns, methodKey, initialFlowKey, flowKeyVar, flowMixVar, 0);
        emitEncodedStateStore(newInsns, initialState, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, 0);
        if (!exceptionHandlerStubs.isEmpty()) {
            newInsns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
            emitExceptionHandlerStubs(newInsns, exceptionHandlerStubs, exceptionSpillLocals,
                flowKeyMap, methodKey, flowKeyVar, flowMixVar, stateMap, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, loopStart);
        }

        newInsns.add(loopStart);
        emitRuntimeFlowContextSync(newInsns, flowKeyVar);
        emitStateDecode(newInsns, encodedStateVar, dispatchStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate);
        newInsns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));

        // Build sorted lookupswitch
        int[] keys = new int[dispatchBlocks.size()];
        LabelNode[] switchLabels = new LabelNode[dispatchBlocks.size()];
        Map<BasicBlock, LabelNode> blockCaseLabels = new HashMap<>();

        List<Map.Entry<BasicBlock, Integer>> entries = new ArrayList<>(stateMap.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getValue));

        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getValue();
            LabelNode label = new LabelNode();
            switchLabels[i] = label;
            blockCaseLabels.put(entries.get(i).getKey(), label);
        }

        LabelNode dispatcherDefault = blockCaseLabels.getOrDefault(entryBlock, switchLabels[0]);
        emitDispatcherSwitch(newInsns, pctx, methodKey, dispatchStateVar, keys, switchLabels, dispatcherDefault);

        List<TailChain> tailChains = new ArrayList<>();
        IdentityHashMap<AbstractInsnNode, AbstractInsnNode> emittedOrigins = new IdentityHashMap<>();
        Set<LabelNode> reentryTargets = Collections.newSetFromMap(new IdentityHashMap<>());
        reentryTargets.add(loopStart);
        int[] emissionOrder = blockEmissionOrder(pctx, dispatchBlocks.size(), false);
        for (int index : emissionOrder) {
            BasicBlock block = dispatchBlocks.get(index);
            emitDispatchBlock(newInsns, block, blockCaseLabels, labelRemap, pctx, flowKeyMap,
                flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases,
                blockEntryStackTypes,
                blockEntryLocals, blockLocalSpillBases, exceptionSpillLocals, blockExitStacks,
                blockExitInitializedLocals, exceptionSpillTypes, requireFullStateMachine,
                inboundFlowKeyVar, emittedOrigins);
        }

        if (!tailChains.isEmpty()) {
            int[] tailOrder = pctx.random().randomPermutation(tailChains.size());
            for (int index : tailOrder) {
                TailChain chain = tailChains.get(index);
                reentryTargets.add(chain.entry());
                newInsns.add(chain.entry());
                newInsns.add(chain.body());
            }
        }

        newInsns.add(loopEnd);
        emitSafetyReturn(newInsns, method.returnType());

        List<TryCatchBlockNode> transformedTryCatchBlocks;
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            transformedTryCatchBlocks = rebuildTryCatchBlocksFromEmittedOrigins(
                mn.tryCatchBlocks, labelRemap, exceptionHandlerStubs, emittedOrigins, newInsns);
        } else {
            transformedTryCatchBlocks = new ArrayList<>();
        }

        int transformedMaxStack = conservativeTransformedMaxStack(mn.maxStack, newInsns);
        if (requireFullStateMachine) {
            repairLoopReentryStackLeaks(clazz.name(), mn, newInsns, transformedTryCatchBlocks,
                transformedMaxLocals, transformedMaxStack, reentryTargets);
        }
        MethodNode probe = methodProbe(mn, newInsns, transformedTryCatchBlocks,
            transformedMaxLocals, transformedMaxStack);
        if (!canComputeFrames(clazz, probe, pctx.hierarchy())) {
            mn.maxLocals = originalMaxLocalsValue;
            mn.maxStack = originalMaxStackValue;
            mn.tryCatchBlocks = originalTryCatchBlocks;
            safeFallbackOrFail(pctx, clazz, method, requireFullStateMachine,
                "state-machine-frame-verification-rejected" + frameRejectSuffix());
            return;
        }

        mn.instructions = newInsns;
        mn.tryCatchBlocks = transformedTryCatchBlocks;
        mn.localVariables = null;
        mn.maxLocals = transformedMaxLocals;
        mn.maxStack = transformedMaxStack;
        excludeGeneratedCffNumericInsns(pctx, newInsns, emittedOrigins);

        clazz.markDirty();
        flattenedMethods(pctx).add(methodKey(method));
        flowKeyLocalByMethod(pctx).put(methodKey(method), flowKeyVar);
        recordFull(pctx, clazz, method, "state-machine");
        pctx.invalidate(method);
    }

    private void excludeGeneratedCffNumericInsns(PipelineContext pctx, InsnList instructions,
            IdentityHashMap<AbstractInsnNode, AbstractInsnNode> emittedOrigins) {
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            AbstractInsnNode origin = emittedOrigins.get(insn);
            if (origin == null || NumberEncryptionPass.isExcludedGeneratedNumericInsn(pctx, origin)) {
                NumberEncryptionPass.excludeGeneratedNumericInsn(pctx, insn);
            }
            if (origin == null || InvokeDynamicPass.isExcludedGeneratedInvokeInsn(pctx, origin)) {
                InvokeDynamicPass.excludeGeneratedInvokeInsn(pctx, insn);
            }
        }
    }

    private Map<String, Integer> flowKeyLocalByMethod(PipelineContext pctx) {
        Map<String, Integer> map = pctx.getPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY);
        if (map == null) {
            map = new HashMap<>();
            pctx.putPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY, map);
        }
        return map;
    }

    private void insertMinimalVerifiedGate(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        MethodNode mn = method.asmNode();
        LabelNode body = new LabelNode();
        LabelNode dflt = new LabelNode();
        int state = pctx.random().nextInt();
        int mask = pctx.random().nextInt() | 1;
        InsnList gate = new InsnList();
        gate.add(AsmUtil.pushIntAny(state ^ mask));
        gate.add(AsmUtil.pushIntAny(mask));
        gate.add(new InsnNode(Opcodes.IXOR));
        gate.add(new LookupSwitchInsnNode(dflt, new int[] { state }, new LabelNode[] { body }));
        gate.add(dflt);
        gate.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        gate.add(new InsnNode(Opcodes.DUP));
        gate.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        gate.add(new InsnNode(Opcodes.ATHROW));
        gate.add(body);

        if (method.isConstructor()) {
            AbstractInsnNode initCall = firstConstructorInitCall(method);
            if (initCall == null) {
                recordFailClosed(pctx, clazz, method, "constructor-minimal-gate-no-init-anchor");
                throw new IllegalStateException("Cannot place constructor CFF safe gate for " + methodKey(method));
            }
            method.instructions().insert(initCall, gate);
        } else {
            method.instructions().insert(gate);
        }
        mn.maxStack = Math.max(mn.maxStack, 4);
        clazz.markDirty();
        flattenedMethods(pctx).add(methodKey(method));
        recordSafe(pctx, clazz, method, reason);
        pctx.invalidate(method);
    }

    private boolean transformConstructorPostInit(PipelineContext pctx, L1Class clazz, L1Method method) {
        AbstractInsnNode anchor = firstConstructorInitCall(method);
        if (anchor == null || anchor.getNext() == null) {
            recordConstructorReject(pctx, clazz, method, "constructor-post-init-anchor-missing");
            return false;
        }

        MethodNode original = method.asmNode();
        Set<AbstractInsnNode> suffixNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn = anchor.getNext(); insn != null; insn = insn.getNext()) {
            suffixNodes.add(insn);
        }
        if (suffixNodes.isEmpty()) {
            recordConstructorReject(pctx, clazz, method, "constructor-post-init-body-empty");
            return false;
        }

        Map<LabelNode, LabelNode> prefixLabels = collectLabelMap(original.instructions.getFirst(), anchor);
        InsnList prefix = cloneRange(original.instructions.getFirst(), anchor, prefixLabels);

        Map<LabelNode, LabelNode> suffixLabels = collectLabelMap(anchor.getNext(), original.instructions.getLast());
        InsnList suffix = cloneRange(anchor.getNext(), original.instructions.getLast(), suffixLabels);
        List<TryCatchBlockNode> suffixTryCatch = cloneSuffixTryCatchBlocks(method, suffixNodes, suffixLabels);
        if (suffixTryCatch == null) {
            recordConstructorReject(pctx, clazz, method, "constructor-try-catch-crosses-init-boundary");
            return false;
        }

        MethodNode suffixNode = new MethodNode(original.access,
            "__neko_ctor_body" + Integer.toUnsignedString(Math.floorMod(methodKey(method).hashCode(), Integer.MAX_VALUE), 36),
            original.desc, original.signature,
            original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        suffixNode.instructions = suffix;
        suffixNode.tryCatchBlocks = suffixTryCatch;
        suffixNode.maxLocals = original.maxLocals;
        suffixNode.maxStack = Math.max(original.maxStack, 1);
        suffixNode.localVariables = null;

        boolean trivialSuffix = isTrivialConstructorSuffix(suffixNode.instructions);
        if (trivialSuffix) {
            emitTrivialConstructorSuffixStateMachine(pctx, clazz, method, suffixNode);
        } else {
            L1Method suffixMethod = new L1Method(clazz, suffixNode);
            L1Method saved = pctx.currentL1Method();
            Boolean savedHardenGenerated = pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY);
            try {
                pctx.setCurrentL1Method(suffixMethod);
                pctx.putPassData(HARDEN_GENERATED_HELPERS_KEY, Boolean.TRUE);
                transformMethod(pctx);
            } finally {
                pctx.putPassData(HARDEN_GENERATED_HELPERS_KEY,
                    savedHardenGenerated == null ? Boolean.FALSE : savedHardenGenerated);
                pctx.setCurrentL1Method(saved);
            }
        }

        InsnList combined = new InsnList();
        combined.add(prefix);
        combined.add(suffixNode.instructions);
        List<TryCatchBlockNode> combinedTryCatch = suffixNode.tryCatchBlocks == null
            ? new ArrayList<>()
            : new ArrayList<>(suffixNode.tryCatchBlocks);

        int combinedMaxLocals = Math.max(original.maxLocals, suffixNode.maxLocals);
        int combinedMaxStack = conservativeTransformedMaxStack(
            Math.max(original.maxStack, suffixNode.maxStack), combined);
        MethodNode probe = methodProbe(original, combined, combinedTryCatch, combinedMaxLocals, combinedMaxStack);
        if (!canComputeFrames(clazz, probe, pctx.hierarchy())) {
            recordConstructorReject(pctx, clazz, method,
                "constructor-post-init-state-machine-frame-rejected" + frameRejectSuffix());
            return false;
        }

        Map<String, Integer> locals = flowKeyLocalByMethod(pctx);
        if (!trivialSuffix) {
            L1Method suffixMethod = new L1Method(clazz, suffixNode);
            Integer suffixFlowLocal = locals.get(methodKey(suffixMethod));
            if (suffixFlowLocal == null) {
                recordConstructorReject(pctx, clazz, method,
                    "constructor-post-init-state-machine-missing-flow-key-local");
                return false;
            }
            locals.put(methodKey(method), suffixFlowLocal);
        }

        original.instructions = combined;
        original.tryCatchBlocks = combinedTryCatch;
        original.localVariables = null;
        original.maxLocals = combinedMaxLocals;
        original.maxStack = combinedMaxStack;

        flattenedMethods(pctx).add(methodKey(method));
        recordFull(pctx, clazz, method, "constructor-post-init-state-machine");
        clazz.markDirty();
        pctx.invalidate(method);
        return true;
    }

    private boolean isTrivialConstructorSuffix(InsnList suffix) {
        boolean sawTerminator = false;
        for (AbstractInsnNode insn = suffix.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                sawTerminator = true;
                continue;
            }
            return false;
        }
        return sawTerminator;
    }

    private void emitTrivialConstructorSuffixStateMachine(PipelineContext pctx, L1Class clazz,
            L1Method method, MethodNode suffixNode) {
        long classKey = keyEngine.deriveClassKey(clazz);
        long methodKey = keyEngine.deriveMethodKey(method, classKey);
        long methodFlowSeed = deriveMethodFlowSeed(methodKey);
        int state = pctx.random().nextInt();
        int stateMask = foldMethodKey(methodKey ^ 0x4E454B4F4C4FL);
        int stateDelta = foldMethodKey(Long.rotateLeft(methodKey, 19) ^ 0xC0DEC0DE5EEDL);
        int stateRotate = 5 + Math.floorMod((int) (methodKey >>> 11), 19);
        long flowKey = deriveBlockFlowKey(methodFlowSeed, state);

        int nextLocal = suffixNode.maxLocals;
        int flowKeyVar = nextLocal;
        nextLocal += 2;
        int flowMixVar = nextLocal++;
        int encodedStateVar = nextLocal++;
        int dispatchStateVar = nextLocal++;
        int stateMaskVar = nextLocal++;
        int stateDeltaVar = nextLocal++;

        LabelNode loopStart = new LabelNode();
        LabelNode caseLabel = new LabelNode();
        LabelNode defaultLabel = new LabelNode();

        InsnList insns = new InsnList();
        emitSplitIntStore(insns, stateMask, stateMaskVar, methodKey);
        emitSplitIntStore(insns, stateDelta, stateDeltaVar,
            Long.rotateLeft(methodKey, 7) ^ 0x6B6F4D5A4D533132L);
        emitFlowKeyAbsolute(insns, methodKey, flowKey, flowKeyVar, flowMixVar, 0);
        emitEncodedStateStore(insns, state, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, 0);
        insns.add(loopStart);
        emitRuntimeFlowContextSync(insns, flowKeyVar);
        emitStateDecode(insns, encodedStateVar, dispatchStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate);
        insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
        insns.add(new LookupSwitchInsnNode(defaultLabel, new int[] { state }, new LabelNode[] { caseLabel }));
        insns.add(defaultLabel);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        insns.add(caseLabel);
        insns.add(new InsnNode(Opcodes.RETURN));

        NumberEncryptionPass.excludeGeneratedNumericInsns(pctx, insns);
        InvokeDynamicPass.excludeGeneratedInvokeInsns(pctx, insns);
        suffixNode.instructions = insns;
        suffixNode.tryCatchBlocks = new ArrayList<>();
        suffixNode.maxLocals = nextLocal;
        suffixNode.maxStack = Math.max(suffixNode.maxStack, 8);
        flowKeyLocalByMethod(pctx).put(methodKey(method), flowKeyVar);
    }

    private Map<LabelNode, LabelNode> collectLabelMap(AbstractInsnNode start, AbstractInsnNode endInclusive) {
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
            if (insn == endInclusive) break;
        }
        return labels;
    }

    private InsnList cloneRange(AbstractInsnNode start, AbstractInsnNode endInclusive,
            Map<LabelNode, LabelNode> labels) {
        InsnList out = new InsnList();
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            out.add(insn.clone(labels));
            if (insn == endInclusive) break;
        }
        return out;
    }

    private List<TryCatchBlockNode> cloneSuffixTryCatchBlocks(L1Method method,
            Set<AbstractInsnNode> suffixNodes, Map<LabelNode, LabelNode> suffixLabels) {
        List<TryCatchBlockNode> cloned = new ArrayList<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks()) {
            boolean startIn = suffixNodes.contains(tcb.start);
            boolean endIn = suffixNodes.contains(tcb.end);
            boolean handlerIn = suffixNodes.contains(tcb.handler);
            if (!startIn && !endIn && !handlerIn) {
                continue;
            }
            if (!startIn || !endIn || !handlerIn) {
                return null;
            }
            LabelNode start = suffixLabels.get(tcb.start);
            LabelNode end = suffixLabels.get(tcb.end);
            LabelNode handler = suffixLabels.get(tcb.handler);
            if (start == null || end == null || handler == null) {
                return null;
            }
            cloned.add(new TryCatchBlockNode(start, end, handler, tcb.type));
        }
        return cloned;
    }

    private void safeFallbackOrFail(PipelineContext pctx, L1Class clazz, L1Method method,
            boolean requireFullStateMachine, String reason) {
        if (requireFullStateMachine) {
            failClosed(pctx, clazz, method, reason);
        }
        insertMinimalVerifiedGate(pctx, clazz, method, "minimal-verified-gate-" + reason);
    }

    private boolean requireFullStateMachine(PipelineContext pctx) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        if (booleanOption(config, REQUIRE_FULL_STATE_MACHINE_OPTION, false)) {
            return true;
        }
        if (booleanOption(config, ALLOW_SAFE_FALLBACKS_OPTION, false)) {
            return false;
        }
        return booleanOption(config, "strictCoverage", false);
    }

    private void materializeStringConstantFieldsForCff(L1Class clazz) {
        InsnList init = new InsnList();
        boolean changed = false;
        for (FieldNode field : clazz.asmNode().fields) {
            if (!"Ljava/lang/String;".equals(field.desc) || !(field.value instanceof String value)) {
                continue;
            }
            field.value = null;
            init.add(new LdcInsnNode(value));
            init.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), field.name, field.desc));
            changed = true;
        }
        if (!changed) return;

        MethodNode clinit = ensureClassInitForCff(clazz);
        insertBeforeReturnForCff(clinit, init);
        clinit.maxStack = Math.max(clinit.maxStack, 1);
        clazz.markDirty();
    }

    private MethodNode ensureClassInitForCff(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                return method;
            }
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions = new InsnList();
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private void insertBeforeReturnForCff(MethodNode method, InsnList init) {
        AbstractInsnNode target = null;
        for (AbstractInsnNode insn = method.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                target = insn;
                break;
            }
        }
        if (target == null) {
            method.instructions.add(init);
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            method.instructions.insertBefore(target, init);
        }
    }

    private void failClosed(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        recordFailClosed(pctx, clazz, method, reason);
        throw new IllegalStateException("Full control-flow flattening failed closed for "
            + methodKey(method) + ": " + reason);
    }

    private void recordFull(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordSafe(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).safe(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordNotApplicable(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordFailClosed(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).failClosed(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordConstructorReject(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        lastConstructorRejectReason = reason;
        recordFailClosed(pctx, clazz, method, reason);
    }

    private MethodNode methodProbe(MethodNode original, InsnList instructions,
            List<TryCatchBlockNode> tryCatchBlocks, int maxLocals, int maxStack) {
        MethodNode probe = new MethodNode(original.access, original.name, original.desc,
            original.signature, original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        probe.instructions = instructions;
        probe.tryCatchBlocks = tryCatchBlocks;
        probe.maxLocals = maxLocals;
        probe.maxStack = maxStack;
        return probe;
    }

    private int conservativeTransformedMaxStack(int originalMaxStack, InsnList instructions) {
        int realInstructions = 0;
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                realInstructions++;
            }
        }
        long bound = Math.max(originalMaxStack, 64) + Math.min(realInstructions, 8192);
        return (int) Math.min(65535L, Math.max(8L, bound));
    }

    private void repairLoopReentryStackLeaks(String ownerName, MethodNode original, InsnList instructions,
            List<TryCatchBlockNode> tryCatchBlocks, int maxLocals, int maxStack,
            Set<LabelNode> reentryTargets) {
        if (reentryTargets == null || reentryTargets.isEmpty()) {
            return;
        }
        for (int attempt = 0; attempt < 128; attempt++) {
            MethodNode probe = methodProbe(original, instructions, tryCatchBlocks, maxLocals, maxStack);
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            try {
                analyzer.analyze(ownerName, probe);
                return;
            } catch (AnalyzerException failure) {
                AbstractInsnNode failed = failure.node;
                AbstractInsnNode[] array = instructions.toArray();
                int failureIndex = -1;
                for (int i = 0; i < array.length; i++) {
                    if (array[i] == failed) {
                        failureIndex = i;
                        break;
                    }
                }
                if (failureIndex < 0) {
                    failureIndex = parseAnalyzerInstructionIndex(failure.getMessage());
                }
                if (failureIndex < 0 || failureIndex >= array.length) {
                    return;
                }
                AbstractInsnNode candidate = array[failureIndex];
                if (!(candidate instanceof JumpInsnNode jump)
                        || jump.getOpcode() != Opcodes.GOTO
                        || !reentryTargets.contains(jump.label)) {
                    return;
                }
                Frame<BasicValue>[] frames = analyzer.getFrames();
                Frame<BasicValue> frame = frames == null || failureIndex >= frames.length
                    ? null
                    : frames[failureIndex];
                if (frame == null || frame.getStackSize() == 0) {
                    return;
                }
                insertStackPopsBefore(instructions, candidate, frame);
            }
        }
    }

    private void insertStackPopsBefore(InsnList instructions, AbstractInsnNode target, Frame<BasicValue> frame) {
        InsnList pops = new InsnList();
        for (int i = frame.getStackSize() - 1; i >= 0; i--) {
            BasicValue value = frame.getStack(i);
            pops.add(new InsnNode(value != null && value.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        instructions.insertBefore(target, pops);
    }

    private boolean canComputeFrames(L1Class clazz, MethodNode method, ClassHierarchy hierarchy) {
        lastFrameRejectReason = "";
        ClassNode owner = clazz.asmNode();
        ClassNode probe = new ClassNode();
        probe.version = owner.version;
        probe.access = owner.access;
        probe.name = owner.name;
        probe.signature = owner.signature;
        probe.superName = owner.superName;
        probe.interfaces = owner.interfaces == null ? new ArrayList<>() : new ArrayList<>(owner.interfaces);
        probe.fields = owner.fields == null ? new ArrayList<>() : new ArrayList<>(owner.fields);
        probe.methods = new ArrayList<>();
        // Include all sibling methods (unchanged) so the probe constant pool
        // matches the actual write more closely. Replace the single method
        // we're testing with the rewritten copy.
        for (MethodNode sibling : owner.methods) {
            if (sibling.name.equals(method.name) && sibling.desc.equals(method.desc)) {
                probe.methods.add(method);
            } else {
                probe.methods.add(sibling);
            }
        }
        try {
            Analyzer<BasicValue> frameAnalyzer = new Analyzer<>(new BasicInterpreter());
            try {
                frameAnalyzer.analyze(owner.name, method);
            } catch (AnalyzerException analyzerException) {
                dumpAnalyzerFailure(owner.name, method, frameAnalyzer, analyzerException);
                throw analyzerException;
            }
            ClassWriter cw = new HierarchyAwareClassWriter(hierarchy);
            probe.accept(cw);
            byte[] bytes = cw.toByteArray();
            // Run CheckClassAdapter against the freshly-emitted bytes. ASM's
            // COMPUTE_FRAMES path can leak illegal opcode bytes when methods
            // grow past certain sizes; the bytes look fine to ClassReader's
            // tolerant parser but the JVM verifier rejects them at load time.
            // CheckClassAdapter walks the actual bytecode and reports any
            // verification failure, so we can hard-reject the CFF result
            // instead of shipping unverifiable bytecode downstream.
            java.io.StringWriter sw = new java.io.StringWriter();
            try {
                org.objectweb.asm.util.CheckClassAdapter.verify(
                    new org.objectweb.asm.ClassReader(bytes),
                    false,
                    new java.io.PrintWriter(sw));
            } catch (Throwable verifyError) {
                String verifyDiagnostic = throwableDiagnostic(verifyError);
                if (containsOnlyClassNotFoundErrors(verifyDiagnostic)) {
                    dumpRejectedDiagnostic(owner.name, method.name, method.desc, verifyDiagnostic);
                } else {
                    lastFrameRejectReason = compactDiagnostic(verifyDiagnostic);
                    dumpRejectedClass(owner.name, method.name, method.desc, bytes);
                    dumpRejectedDiagnostic(owner.name, method.name, method.desc, verifyDiagnostic);
                    return false;
                }
            }
            String diagnostics = sw.toString();
            boolean structurallyOk = diagnostics.isBlank() || containsOnlyClassNotFoundErrors(diagnostics);
            if (!structurallyOk) {
                lastFrameRejectReason = compactDiagnostic(diagnostics);
                dumpRejectedClass(owner.name, method.name, method.desc, bytes);
                dumpRejectedDiagnostic(owner.name, method.name, method.desc, diagnostics);
                return false;
            }
            if (codeAttributeHasIllegalOpcodes(bytes)) {
                lastFrameRejectReason = "illegal opcode in emitted Code attribute";
                dumpRejectedClass(owner.name, method.name, method.desc, bytes);
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            lastFrameRejectReason = ignored.getClass().getSimpleName() + ": " + ignored.getMessage();
            dumpRejectedMethod(owner.name, method);
            return false;
        }
    }

    private String frameRejectSuffix() {
        return lastFrameRejectReason == null || lastFrameRejectReason.isBlank()
            ? ""
            : ": " + lastFrameRejectReason;
    }

    private static String compactDiagnostic(String diagnostics) {
        if (diagnostics == null || diagnostics.isBlank()) return "";
        String oneLine = diagnostics.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 360 ? oneLine : oneLine.substring(0, 360);
    }

    private static String throwableDiagnostic(Throwable throwable) {
        if (throwable == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static void dumpRejectedClass(String owner, String methodName, String desc, byte[] bytes) {
        if (!Boolean.getBoolean("neko.cff.dumpRejects")) return;
        try {
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("build", "cff-rejects"));
            String safe = (owner + "." + methodName + desc)
                .replace('/', '_')
                .replaceAll("[^A-Za-z0-9_.-]", "_");
            java.nio.file.Files.write(dir.resolve(safe + ".class"), bytes);
        } catch (Throwable ignored) {
        }
    }

    private static void dumpRejectedDiagnostic(String owner, String methodName, String desc, String diagnostics) {
        if (!Boolean.getBoolean("neko.cff.dumpRejects")) return;
        try {
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("build", "cff-rejects"));
            String safe = (owner + "." + methodName + desc)
                .replace('/', '_')
                .replaceAll("[^A-Za-z0-9_.-]", "_");
            java.nio.file.Files.writeString(dir.resolve(safe + ".diagnostics.txt"), diagnostics);
        } catch (Throwable ignored) {
        }
    }

    private static void dumpRejectedMethod(String owner, MethodNode method) {
        if (!Boolean.getBoolean("neko.cff.dumpRejects")) return;
        try {
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("build", "cff-rejects"));
            String safe = (owner + "." + method.name + method.desc)
                .replace('/', '_')
                .replaceAll("[^A-Za-z0-9_.-]", "_");
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            org.objectweb.asm.util.Textifier textifier = new org.objectweb.asm.util.Textifier();
            org.objectweb.asm.util.TraceMethodVisitor tmv = new org.objectweb.asm.util.TraceMethodVisitor(textifier);
            method.accept(tmv);
            textifier.print(pw);
            pw.flush();
            java.nio.file.Files.writeString(dir.resolve(safe + ".method.txt"), sw.toString());
            StringBuilder indexed = new StringBuilder();
            int idx = 0;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext(), idx++) {
                indexed.append(idx).append(": ");
                int op = insn.getOpcode();
                if (op >= 0 && op < org.objectweb.asm.util.Printer.OPCODES.length) {
                    indexed.append(org.objectweb.asm.util.Printer.OPCODES[op]);
                } else {
                    indexed.append(insn.getClass().getSimpleName());
                }
                if (insn instanceof LabelNode label) {
                    indexed.append(" ").append(label.getLabel());
                } else if (insn instanceof JumpInsnNode jump) {
                    indexed.append(" -> ").append(jump.label.getLabel());
                }
                indexed.append('\n');
            }
            java.nio.file.Files.writeString(dir.resolve(safe + ".indexed.txt"), indexed.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void dumpAnalyzerFailure(String owner, MethodNode method,
            Analyzer<BasicValue> analyzer, AnalyzerException failure) {
        if (!Boolean.getBoolean("neko.cff.dumpRejects")) return;
        try {
            java.nio.file.Path dir = java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("build", "cff-rejects"));
            String safe = (owner + "." + method.name + method.desc)
                .replace('/', '_')
                .replaceAll("[^A-Za-z0-9_.-]", "_");
            AbstractInsnNode[] insns = method.instructions.toArray();
            int failureIndex = -1;
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] == failure.node) {
                    failureIndex = i;
                    break;
                }
            }
            if (failureIndex < 0) {
                failureIndex = parseAnalyzerInstructionIndex(failure.getMessage());
            }
            Frame<BasicValue>[] frames = analyzer.getFrames();
            StringBuilder out = new StringBuilder();
            out.append(failure.getClass().getSimpleName()).append(": ")
                .append(failure.getMessage()).append('\n');
            out.append("failureIndex=").append(failureIndex).append('\n');
            int start = Math.max(0, failureIndex - 8);
            int end = Math.min(insns.length, failureIndex + 9);
            for (int i = start; i < end; i++) {
                out.append(i).append(": ").append(insnDebug(insns[i]))
                    .append(" frame=").append(frameDebug(frames == null || i >= frames.length ? null : frames[i]))
                    .append('\n');
            }
            java.nio.file.Files.writeString(dir.resolve(safe + ".frames.txt"), out.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String insnDebug(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        String name = op >= 0 && op < org.objectweb.asm.util.Printer.OPCODES.length
            ? org.objectweb.asm.util.Printer.OPCODES[op]
            : insn.getClass().getSimpleName();
        if (insn instanceof LabelNode label) {
            return name + " " + label.getLabel();
        }
        if (insn instanceof JumpInsnNode jump) {
            return name + " -> " + jump.label.getLabel();
        }
        return name;
    }

    private static int parseAnalyzerInstructionIndex(String message) {
        if (message == null) {
            return -1;
        }
        String needle = "instruction ";
        int start = message.indexOf(needle);
        if (start < 0) {
            return -1;
        }
        start += needle.length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String frameDebug(Frame<BasicValue> frame) {
        if (frame == null) {
            return "null";
        }
        return "locals=" + frame.getLocals() + ",stack=" + frame.getStackSize();
    }

    /**
     * Walk every Code attribute byte-by-byte and verify each opcode is a known
     * JVM opcode. ASM's COMPUTE_FRAMES path occasionally writes an illegal
     * byte (0xF6–0xFD) that ClassReader silently maps to a -1 InsnNode but
     * which the JVM verifier rejects with "Bad instruction". Catching this in
     * our probe lets the caller revert to the original, unflattened method.
     */
    private static boolean codeAttributeHasIllegalOpcodes(byte[] bytes) {
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            final boolean[] bad = { false };
            reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions) {
                    return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (!isValidOpcode(opcode)) bad[0] = true;
                        }
                    };
                }
            }, 0);
            if (bad[0]) return true;

            // ClassReader strips illegal bytes silently. Walk the raw class
            // file, find each Code attribute, and validate each instruction's
            // opcode byte directly.
            return scanRawBytecode(bytes);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isValidOpcode(int op) {
        return op >= 0 && op <= 201;
    }

    /**
     * Minimal class-file walker that locates each method's Code attribute and
     * validates that every instruction starts with a recognised opcode. We
     * accept the JVMS-defined range 0x00–0xC9 plus 0xC4 (wide), 0xC5 (multianewarray),
     * 0xC6/0xC7 (ifnull/ifnonnull), 0xC8/0xC9 (goto_w/jsr_w). 0xCA–0xFF are
     * reserved/illegal and indicate ASM emission corruption.
     */
    private static boolean scanRawBytecode(byte[] bytes) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            buf.getInt(); // magic
            buf.getShort(); // minor
            buf.getShort(); // major
            int cpCount = buf.getShort() & 0xFFFF;
            String[] utf8 = new String[cpCount];
            int i = 1;
            while (i < cpCount) {
                int tag = buf.get() & 0xFF;
                switch (tag) {
                    case 1: // Utf8
                        int len = buf.getShort() & 0xFFFF;
                        byte[] str = new byte[len];
                        buf.get(str);
                        utf8[i] = new String(str, java.nio.charset.StandardCharsets.UTF_8);
                        break;
                    case 5: case 6: // Long, Double - takes 2 slots
                        buf.getLong();
                        i++;
                        break;
                    case 7: case 8: case 16: case 19: case 20:
                        buf.getShort();
                        break;
                    case 3: case 4:
                        buf.getInt();
                        break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        buf.getInt();
                        break;
                    case 15:
                        buf.get();
                        buf.getShort();
                        break;
                    default:
                        return true; // unknown tag, fail safe
                }
                i++;
            }
            buf.getShort(); // access
            buf.getShort(); // this_class
            buf.getShort(); // super_class
            int ifaceCount = buf.getShort() & 0xFFFF;
            buf.position(buf.position() + ifaceCount * 2);
            // fields
            int fieldCount = buf.getShort() & 0xFFFF;
            for (int f = 0; f < fieldCount; f++) {
                buf.getShort(); buf.getShort(); buf.getShort();
                int attrs = buf.getShort() & 0xFFFF;
                for (int a = 0; a < attrs; a++) {
                    buf.getShort();
                    int attrLen = buf.getInt();
                    buf.position(buf.position() + attrLen);
                }
            }
            // methods
            int methodCount = buf.getShort() & 0xFFFF;
            for (int m = 0; m < methodCount; m++) {
                buf.getShort(); buf.getShort(); buf.getShort();
                int attrs = buf.getShort() & 0xFFFF;
                for (int a = 0; a < attrs; a++) {
                    int attrName = buf.getShort() & 0xFFFF;
                    int attrLen = buf.getInt();
                    int attrEnd = buf.position() + attrLen;
                    if ("Code".equals(utf8[attrName])) {
                        buf.getShort(); // max_stack
                        buf.getShort(); // max_locals
                        int codeLen = buf.getInt();
                        int codeStart = buf.position();
                        if (!walkCode(bytes, codeStart, codeLen)) {
                            return true;
                        }
                    }
                    buf.position(attrEnd);
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean walkCode(byte[] bytes, int start, int length) {
        int p = start;
        int end = start + length;
        while (p < end) {
            int op = bytes[p] & 0xFF;
            int size = opcodeSize(bytes, p, p - start, end, op);
            if (size <= 0) return false;
            p += size;
        }
        return p == end;
    }

    private static int opcodeSize(byte[] bytes, int pos, int bci, int codeEnd, int op) {
        // Returns total instruction size, or -1 if illegal.
        switch (op) {
            case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
            case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c: case 0x0d: case 0x0e: case 0x0f:
                return 1;
            case 0x10: case 0x12: return 2; // bipush, ldc
            case 0x11: case 0x13: case 0x14: return 3; // sipush, ldc_w, ldc2_w
            case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: return 2; // *load N
            case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: case 0x20: case 0x21:
            case 0x22: case 0x23: case 0x24: case 0x25: case 0x26: case 0x27: case 0x28: case 0x29:
            case 0x2a: case 0x2b: case 0x2c: case 0x2d: case 0x2e: case 0x2f: case 0x30: case 0x31:
            case 0x32: case 0x33: case 0x34: case 0x35:
                return 1; // *load_X, *aload
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3a: return 2; // *store N
            case 0x3b: case 0x3c: case 0x3d: case 0x3e: case 0x3f: case 0x40: case 0x41: case 0x42:
            case 0x43: case 0x44: case 0x45: case 0x46: case 0x47: case 0x48: case 0x49: case 0x4a:
            case 0x4b: case 0x4c: case 0x4d: case 0x4e: case 0x4f: case 0x50: case 0x51: case 0x52:
            case 0x53: case 0x54: case 0x55: case 0x56:
                return 1; // *store_X, *astore
            case 0x57: case 0x58: case 0x59: case 0x5a: case 0x5b: case 0x5c: case 0x5d: case 0x5e:
            case 0x5f:
                return 1; // pop, dup, swap, etc
            case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
            case 0x68: case 0x69: case 0x6a: case 0x6b: case 0x6c: case 0x6d: case 0x6e: case 0x6f:
            case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
            case 0x78: case 0x79: case 0x7a: case 0x7b: case 0x7c: case 0x7d: case 0x7e: case 0x7f:
            case 0x80: case 0x81: case 0x82: case 0x83:
                return 1; // arithmetic
            case 0x84: return 3; // iinc
            case 0x85: case 0x86: case 0x87: case 0x88: case 0x89: case 0x8a: case 0x8b: case 0x8c:
            case 0x8d: case 0x8e: case 0x8f: case 0x90: case 0x91: case 0x92: case 0x93:
                return 1; // conversions
            case 0x94: case 0x95: case 0x96: case 0x97: case 0x98:
                return 1; // lcmp/fcmp/dcmp
            case 0x99: case 0x9a: case 0x9b: case 0x9c: case 0x9d: case 0x9e: case 0x9f: case 0xa0:
            case 0xa1: case 0xa2: case 0xa3: case 0xa4: case 0xa5: case 0xa6:
                return 3; // if* with 2-byte offset
            case 0xa7: case 0xa8: return 3; // goto, jsr
            case 0xa9: return 2; // ret
            case 0xaa: { // tableswitch
                int padding = (4 - ((bci + 1) & 3)) & 3;
                int start = pos + 1 + padding;
                if (start + 12 > codeEnd) return -1;
                int low = ((bytes[start + 4] & 0xFF) << 24) | ((bytes[start + 5] & 0xFF) << 16)
                    | ((bytes[start + 6] & 0xFF) << 8) | (bytes[start + 7] & 0xFF);
                int high = ((bytes[start + 8] & 0xFF) << 24) | ((bytes[start + 9] & 0xFF) << 16)
                    | ((bytes[start + 10] & 0xFF) << 8) | (bytes[start + 11] & 0xFF);
                if (high < low) return -1;
                long count = (long) high - low + 1;
                if (count < 0 || count > 100000) return -1;
                long size = 1L + padding + 12 + count * 4;
                return pos + size > codeEnd ? -1 : (int) size;
            }
            case 0xab: { // lookupswitch
                int padding = (4 - ((bci + 1) & 3)) & 3;
                int start = pos + 1 + padding;
                if (start + 8 > codeEnd) return -1;
                int npairs = ((bytes[start + 4] & 0xFF) << 24) | ((bytes[start + 5] & 0xFF) << 16)
                    | ((bytes[start + 6] & 0xFF) << 8) | (bytes[start + 7] & 0xFF);
                if (npairs < 0 || npairs > 100000) return -1;
                long size = 1L + padding + 8 + (long) npairs * 8;
                return pos + size > codeEnd ? -1 : (int) size;
            }
            case 0xac: case 0xad: case 0xae: case 0xaf: case 0xb0: case 0xb1:
                return 1; // *return
            case 0xb2: case 0xb3: case 0xb4: case 0xb5: case 0xb6: case 0xb7: case 0xb8:
                return 3; // get/put*field/static, invokevirtual/special/static
            case 0xb9: return 5; // invokeinterface
            case 0xba: return 5; // invokedynamic
            case 0xbb: case 0xbd: case 0xc0: case 0xc1:
                return 3; // new, anewarray, checkcast, instanceof
            case 0xbc: return 2; // newarray
            case 0xbe: case 0xbf: return 1; // arraylength, athrow
            case 0xc2: case 0xc3: return 1; // monitorenter/exit
            case 0xc4: { // wide
                if (pos + 1 >= codeEnd) return -1;
                int wOp = bytes[pos + 1] & 0xFF;
                int size = wOp == 0x84 ? 6 : 4; // wide iinc or wide *load/*store/ret
                return pos + size > codeEnd ? -1 : size;
            }
            case 0xc5: return 4; // multianewarray
            case 0xc6: case 0xc7: return 3; // ifnull, ifnonnull
            case 0xc8: case 0xc9: return 5; // goto_w, jsr_w
            default: return -1; // illegal opcode
        }
    }

    private static boolean containsOnlyClassNotFoundErrors(String diagnostics) {
        if (diagnostics.isBlank()) return true;
        String lower = diagnostics.toLowerCase(java.util.Locale.ROOT);
        // Reject if structural issues are reported.
        if (lower.contains("bad instruction")
            || lower.contains("bad type on operand stack")
            || lower.contains("inconsistent stackmap")
            || lower.contains("expecting a stackmap frame")
            || lower.contains("expecting type")
            || lower.contains("get long/double overflows")
            || lower.contains("bad local variable type")
            || lower.contains("invalid opcode")) {
            return false;
        }
        if (diagnostics.contains("ClassNotFoundException")) {
            return true;
        }
        // CheckClassAdapter prints AnalyzerException with cause; if every cause
        // is ClassNotFoundException we treat the diagnostic as benign.
        for (String line : diagnostics.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("ClassNotFoundException")) {
                continue;
            }
            if (trimmed.startsWith("at ")) continue;
            if (trimmed.startsWith("... ") && trimmed.endsWith(" more")) continue;
            if (trimmed.startsWith("Caused by")
                && trimmed.contains("ClassNotFoundException")) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean hasOnlyValidOpcodes(MethodNode method) {
        if (method.instructions == null) {
            return true;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == -1) {
                continue;
            }
            if (op < 0 || op > 201) {
                return false;
            }
        }
        return true;
    }

    private static final class FrameProbeClassWriter extends ClassWriter {
        private FrameProbeClassWriter() {
            super(ClassWriter.COMPUTE_FRAMES);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static final class HierarchyAwareClassWriter extends ClassWriter {
        private final ClassHierarchy hierarchy;

        HierarchyAwareClassWriter(ClassHierarchy hierarchy) {
            super(ClassWriter.COMPUTE_FRAMES);
            this.hierarchy = hierarchy;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (hierarchy != null) {
                String result = hierarchy.getCommonSuperClass(type1, type2);
                if (result != null && !result.equals("java/lang/Object")) {
                    return result;
                }
            }
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }

    private boolean isTerminator(AbstractInsnNode insn, BasicBlock block) {
        int opcode = insn.getOpcode();
        return opcode == Opcodes.GOTO || opcode == 200
            || AsmUtil.isConditionalJump(opcode)
            || insn instanceof TableSwitchInsnNode
            || insn instanceof LookupSwitchInsnNode;
    }

    private void emitDispatcherSwitch(InsnList insns, PipelineContext pctx, long methodKey, int dispatchStateVar,
            int[] keys, LabelNode[] switchLabels, LabelNode dispatcherDefault) {
        int fragments = dispatcherFragments(pctx, keys.length);
        if (fragments > 1 && keys.length >= fragments * 2) {
            DispatcherShape fragmentShape = dispatcherShape(pctx,
                methodKey ^ 0x465241474D454E54L);
            Map<Integer, List<Integer>> fragmentIndexes = new TreeMap<>();
            for (int i = 0; i < keys.length; i++) {
                int fragment = Math.floorMod(keys[i] ^ fragmentShape.salt(), fragments);
                fragmentIndexes.computeIfAbsent(fragment, ignored -> new ArrayList<>()).add(i);
            }

            int[] fragmentKeys = new int[fragmentIndexes.size()];
            LabelNode[] fragmentLabels = new LabelNode[fragmentIndexes.size()];
            int fragmentIndex = 0;
            for (Integer fragment : fragmentIndexes.keySet()) {
                fragmentKeys[fragmentIndex] = fragment;
                fragmentLabels[fragmentIndex] = new LabelNode();
                fragmentIndex++;
            }

            insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
            insns.add(AsmUtil.pushIntAny(fragmentShape.salt()));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(AsmUtil.pushIntAny(fragments));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "floorMod", "(II)I", false));
            insns.add(new LookupSwitchInsnNode(dispatcherDefault, fragmentKeys, fragmentLabels));

            fragmentIndex = 0;
            for (List<Integer> indexes : fragmentIndexes.values()) {
                indexes.sort(Comparator.comparingInt(i -> keys[i]));
                int[] innerKeys = new int[indexes.size()];
                LabelNode[] innerLabels = new LabelNode[indexes.size()];
                for (int i = 0; i < indexes.size(); i++) {
                    int originalIndex = indexes.get(i);
                    innerKeys[i] = keys[originalIndex];
                    innerLabels[i] = switchLabels[originalIndex];
                }
                insns.add(fragmentLabels[fragmentIndex++]);
                insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
                insns.add(new LookupSwitchInsnNode(dispatcherDefault, innerKeys, innerLabels));
            }
            return;
        }

        int depth = dispatcherDepth(pctx, keys.length);
        if (depth <= 1 || keys.length < 4) {
            insns.add(new LookupSwitchInsnNode(dispatcherDefault, keys, switchLabels));
            return;
        }

        int bucketMask = depth - 1;
        DispatcherShape shape = dispatcherShape(pctx, methodKey);
        Map<Integer, List<Integer>> bucketIndexes = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) {
            bucketIndexes.computeIfAbsent(dispatchBucket(keys[i], bucketMask, shape), ignored -> new ArrayList<>()).add(i);
        }

        int[] outerKeys = new int[bucketIndexes.size()];
        LabelNode[] outerLabels = new LabelNode[bucketIndexes.size()];
        int outerIndex = 0;
        for (Integer bucket : bucketIndexes.keySet()) {
            outerKeys[outerIndex] = bucket;
            outerLabels[outerIndex] = new LabelNode();
            outerIndex++;
        }

        emitDispatchBucketValue(insns, dispatchStateVar, bucketMask, shape);
        insns.add(new LookupSwitchInsnNode(dispatcherDefault, outerKeys, outerLabels));

        outerIndex = 0;
        for (List<Integer> indexes : bucketIndexes.values()) {
            indexes.sort(Comparator.comparingInt(i -> keys[i]));
            int[] innerKeys = new int[indexes.size()];
            LabelNode[] innerLabels = new LabelNode[indexes.size()];
            for (int i = 0; i < indexes.size(); i++) {
                int originalIndex = indexes.get(i);
                innerKeys[i] = keys[originalIndex];
                innerLabels[i] = switchLabels[originalIndex];
            }
            insns.add(outerLabels[outerIndex++]);
            insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
            insns.add(new LookupSwitchInsnNode(dispatcherDefault, innerKeys, innerLabels));
        }
    }

    private void emitDispatchBlock(InsnList insns, BasicBlock block,
            Map<BasicBlock, LabelNode> blockCaseLabels, Map<LabelNode, LabelNode> labelRemap,
            PipelineContext pctx, Map<BasicBlock, Long> flowKeyMap,
            int flowKeyVar, int flowMixVar, Map<BasicBlock, Integer> stateMap,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            int tailSeedVar, int tailFlagVar, boolean zkmStyle, double tailChainIntensity,
            List<TailChain> tailChains, LabelNode loopStart, LabelNode loopEnd,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<String>> blockEntryStackTypes,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases,
            Map<BasicBlock, Integer> exceptionSpillLocals,
            Map<BasicBlock, List<StackSlotKind>> blockExitStacks,
            Map<BasicBlock, BitSet> blockExitInitializedLocals,
            Map<BasicBlock, String> exceptionSpillTypes,
            boolean requireFullStateMachine,
            int inboundFlowKeyVar,
            IdentityHashMap<AbstractInsnNode, AbstractInsnNode> emittedOrigins) {
        LabelNode caseLabel = blockCaseLabels.get(block);
        insns.add(caseLabel);
        insns.add(new InsnNode(Opcodes.NOP));
        restoreBlockEntryLocals(insns, block, blockEntryLocals, blockLocalSpillBases);
        restoreBlockEntryStack(insns, block, blockEntryStacks, blockEntryStackTypes, blockSpillBases);
        Integer exceptionSpillLocal = exceptionSpillLocals.get(block);
        if (exceptionSpillLocal != null) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, exceptionSpillLocal));
            String exceptionType = exceptionSpillTypes.get(block);
            if (exceptionType != null && !"java/lang/Object".equals(exceptionType)) {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, exceptionType));
            }
        }

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof FrameNode) continue;
            if (insn instanceof LineNumberNode) continue;
            if (insn instanceof JumpInsnNode
                    || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) {
                continue;
            }
            if (isTerminator(insn, block)) continue;

            if (insn instanceof LabelNode origLabel) {
                LabelNode remapped = labelRemap.get(origLabel);
                if (remapped != null) insns.add(remapped);
                continue;
            }

            if (isInboundFlowKeyRead(pctx, insn)) {
                if (inboundFlowKeyVar < 0) {
                    throw new IllegalStateException("Inbound flow-key read was not captured before CFF dispatch");
                }
                AbstractInsnNode load = new VarInsnNode(Opcodes.LLOAD, inboundFlowKeyVar);
                insns.add(load);
                emittedOrigins.put(load, insn);
                recordInstructionFlowKey(pctx, load, flowKeyMap.getOrDefault(block, 0L));
                continue;
            }

            AbstractInsnNode clone = insn.clone(labelRemap);
            insns.add(clone);
            emittedOrigins.put(clone, insn);
            recordInstructionFlowKey(pctx, clone, flowKeyMap.getOrDefault(block, 0L));
            // No mid-block flowKey resync: flowKey is updated only at block-exit transitions,
            // so it stays valid throughout the block body. Removing this sync eliminates the
            // ThreadLocal hot-path tax on every MethodInsn / InvokeDynamicInsn.
        }

        emitStateTransition(insns, block, stateMap,
            flowKeyMap, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
            tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
            tailChains, loopStart, loopEnd, blockEntryStacks, blockExitStacks, blockSpillBases,
            blockEntryLocals, blockLocalSpillBases, blockExitInitializedLocals, requireFullStateMachine);
    }

    private void emitStateTransition(InsnList insns, BasicBlock block,
            Map<BasicBlock, Integer> stateMap,
            Map<BasicBlock, Long> flowKeyMap, int flowKeyVar, int flowMixVar,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            int tailSeedVar, int tailFlagVar, boolean zkmStyle, double tailChainIntensity,
            List<TailChain> tailChains, LabelNode loopStart, LabelNode loopEnd,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, List<StackSlotKind>> blockExitStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases,
            Map<BasicBlock, BitSet> blockExitInitializedLocals,
            boolean requireFullStateMachine) {

        List<CFGEdge> outEdges = normalOutEdges(block);
        if (outEdges.isEmpty()) return;

        long sourceFlowKey = flowKeyMap.getOrDefault(block, 0L);

        AbstractInsnNode lastReal = findLastRealInsn(block);
        if (lastReal == null) {
                emitUnconditionalTransition(insns, outEdges.get(0).target(), stateMap,
                    flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                    tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                    loopStart, 0, residualStackKinds(block, blockEntryStacks, blockExitStacks),
                    blockEntryStacks, blockSpillBases,
                    blockEntryLocals, blockLocalSpillBases, blockExitInitializedLocals.get(block));
            return;
        }

        int opcode = lastReal.getOpcode();

        if (AsmUtil.isReturn(opcode) || opcode == Opcodes.ATHROW) return;

        if (AsmUtil.isConditionalJump(opcode)) {
            CFGEdge trueEdge = null, falseEdge = null;
            for (CFGEdge edge : outEdges) {
                if (edge.type() == CFGEdge.Type.CONDITIONAL_TRUE) trueEdge = edge;
                else if (edge.type() == CFGEdge.Type.CONDITIONAL_FALSE) falseEdge = edge;
                else if (trueEdge == null) trueEdge = edge;
                else if (falseEdge == null) falseEdge = edge;
            }
            if (trueEdge == null || falseEdge == null) {
                emitUnconditionalTransition(insns, outEdges.get(0).target(), stateMap,
                    flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                    tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                    loopStart, block.id(), residualStackKinds(block, blockEntryStacks, blockExitStacks),
                    blockEntryStacks, blockSpillBases,
                    blockEntryLocals, blockLocalSpillBases, blockExitInitializedLocals.get(block));
                return;
            }

            int trueState = requiredState(stateMap, trueEdge.target());
            int falseState = requiredState(stateMap, falseEdge.target());
            List<StackSlotKind> branchResidualStack = popTopStackKind(
                residualStackKinds(block, blockEntryStacks, blockExitStacks));

            LabelNode trueLabel = new LabelNode();
            LabelNode joinLabel = new LabelNode();
            insns.add(new JumpInsnNode(opcode, trueLabel));

            if (!requireFullStateMachine) {
                discardUnforwardedStack(insns, branchResidualStack, blockEntryStacks.get(falseEdge.target()));
            }
            spillStackForTarget(insns, falseEdge.target(), blockEntryStacks, blockSpillBases);
            spillLocalsForTarget(insns, falseEdge.target(), blockEntryLocals, blockLocalSpillBases,
                blockExitInitializedLocals.get(block));
            emitFlowKeyDelta(insns, sourceFlowKey, flowKeyMap.getOrDefault(falseEdge.target(), 0L),
                flowKeyVar, flowMixVar);
            emitEncodedStateStore(insns, falseState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, block.id() + 1);
            insns.add(new JumpInsnNode(Opcodes.GOTO, joinLabel));

            insns.add(trueLabel);
            if (!requireFullStateMachine) {
                discardUnforwardedStack(insns, branchResidualStack, blockEntryStacks.get(trueEdge.target()));
            }
            spillStackForTarget(insns, trueEdge.target(), blockEntryStacks, blockSpillBases);
            spillLocalsForTarget(insns, trueEdge.target(), blockEntryLocals, blockLocalSpillBases,
                blockExitInitializedLocals.get(block));
            emitFlowKeyDelta(insns, sourceFlowKey, flowKeyMap.getOrDefault(trueEdge.target(), 0L),
                flowKeyVar, flowMixVar);
            emitEncodedStateStore(insns, trueState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, block.id());
            insns.add(joinLabel);
            emitLoopReentry(insns, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                tailChains, loopStart, block.id());
            return;
        }

        if (lastReal instanceof TableSwitchInsnNode tableSwitch) {
            emitTableSwitchTransition(insns, outEdges, stateMap, flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar,
                encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, tableSwitch, block.id(),
                popTopStackKind(residualStackKinds(block, blockEntryStacks, blockExitStacks)),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
                blockExitInitializedLocals.get(block), requireFullStateMachine);
            return;
        }

        if (lastReal instanceof LookupSwitchInsnNode lookupSwitch) {
            emitLookupSwitchTransition(insns, outEdges, stateMap, flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar,
                encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, lookupSwitch, block.id(),
                popTopStackKind(residualStackKinds(block, blockEntryStacks, blockExitStacks)),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
                blockExitInitializedLocals.get(block), requireFullStateMachine);
            return;
        }

        for (CFGEdge edge : outEdges) {
            emitUnconditionalTransition(insns, edge.target(), stateMap,
                flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                loopStart, block.id(), residualStackKinds(block, blockEntryStacks, blockExitStacks),
                blockEntryStacks, blockSpillBases,
                blockEntryLocals, blockLocalSpillBases, blockExitInitializedLocals.get(block));
            return;
        }
    }

    private void emitUnconditionalTransition(InsnList insns, BasicBlock target,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            long sourceFlowKey,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, int variantSeed,
            List<StackSlotKind> residualStackKinds,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases,
            BitSet sourceInitializedLocals) {
        int nextState = requiredState(stateMap, target);
        discardUnforwardedStack(insns, residualStackKinds, blockEntryStacks.get(target));
        spillStackForTarget(insns, target, blockEntryStacks, blockSpillBases);
        spillLocalsForTarget(insns, target, blockEntryLocals, blockLocalSpillBases, sourceInitializedLocals);
        emitFlowKeyDelta(insns, sourceFlowKey, flowKeyMap.getOrDefault(target, 0L),
            flowKeyVar, flowMixVar);
        emitEncodedStateStore(insns, nextState, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, variantSeed);
        emitLoopReentry(insns, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
            tailChains, loopStart, variantSeed);
    }

    private void emitTableSwitchTransition(InsnList insns, List<CFGEdge> outEdges,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            long sourceFlowKey,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart,
            TableSwitchInsnNode tableSwitch, int variantSeed,
            List<StackSlotKind> switchResidualStack,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases,
            BitSet sourceInitializedLocals,
            boolean requireFullStateMachine) {
        BasicBlock defaultTarget = null;
        Map<Integer, BasicBlock> targets = new TreeMap<>();
        for (CFGEdge edge : outEdges) {
            if (edge.type() == CFGEdge.Type.SWITCH_DEFAULT) {
                defaultTarget = edge.target();
            } else if (edge.type() == CFGEdge.Type.SWITCH_CASE) {
                targets.put(edge.switchKey(), edge.target());
            }
        }
        if (defaultTarget == null && !outEdges.isEmpty()) {
            defaultTarget = outEdges.get(0).target();
        }
        if (defaultTarget == null) return;

        LabelNode defaultLabel = new LabelNode();
        LabelNode[] labels = new LabelNode[tableSwitch.max - tableSwitch.min + 1];
        List<Map.Entry<Integer, LabelNode>> caseLabels = new ArrayList<>();
        for (int key = tableSwitch.min; key <= tableSwitch.max; key++) {
            if (targets.containsKey(key)) {
                LabelNode label = new LabelNode();
                labels[key - tableSwitch.min] = label;
                caseLabels.add(Map.entry(key, label));
            } else {
                labels[key - tableSwitch.min] = defaultLabel;
            }
        }
        insns.add(new TableSwitchInsnNode(tableSwitch.min, tableSwitch.max, defaultLabel, labels));
        for (Map.Entry<Integer, LabelNode> entry : caseLabels) {
            insns.add(entry.getValue());
            if (!requireFullStateMachine) {
                discardUnforwardedStack(insns, switchResidualStack, blockEntryStacks.get(targets.get(entry.getKey())));
            }
            emitUnconditionalTransition(insns, targets.get(entry.getKey()), stateMap, flowKeyMap,
                sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + entry.getKey(),
                null, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
                sourceInitializedLocals);
        }
        insns.add(defaultLabel);
        if (!requireFullStateMachine) {
            discardUnforwardedStack(insns, switchResidualStack, blockEntryStacks.get(defaultTarget));
        }
        emitUnconditionalTransition(insns, defaultTarget, stateMap, flowKeyMap,
            sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
            stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + 31,
            null, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
            sourceInitializedLocals);
    }

    private void emitLookupSwitchTransition(InsnList insns, List<CFGEdge> outEdges,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            long sourceFlowKey,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart,
            LookupSwitchInsnNode lookupSwitch, int variantSeed,
            List<StackSlotKind> switchResidualStack,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases,
            BitSet sourceInitializedLocals,
            boolean requireFullStateMachine) {
        BasicBlock defaultTarget = null;
        Map<Integer, BasicBlock> targets = new TreeMap<>();
        for (CFGEdge edge : outEdges) {
            if (edge.type() == CFGEdge.Type.SWITCH_DEFAULT) {
                defaultTarget = edge.target();
            } else if (edge.type() == CFGEdge.Type.SWITCH_CASE) {
                targets.put(edge.switchKey(), edge.target());
            }
        }
        if (defaultTarget == null && !outEdges.isEmpty()) {
            defaultTarget = outEdges.get(0).target();
        }
        if (defaultTarget == null) return;

        LabelNode defaultLabel = new LabelNode();
        List<Integer> sortedKeys = new ArrayList<>(targets.keySet());
        Collections.sort(sortedKeys);
        int[] keys = new int[sortedKeys.size()];
        LabelNode[] labels = new LabelNode[sortedKeys.size()];
        for (int i = 0; i < sortedKeys.size(); i++) {
            keys[i] = sortedKeys.get(i);
            labels[i] = new LabelNode();
        }
        insns.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));
        for (int i = 0; i < sortedKeys.size(); i++) {
            insns.add(labels[i]);
            if (!requireFullStateMachine) {
                discardUnforwardedStack(insns, switchResidualStack, blockEntryStacks.get(targets.get(sortedKeys.get(i))));
            }
            emitUnconditionalTransition(insns, targets.get(sortedKeys.get(i)), stateMap, flowKeyMap,
                sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + i,
                null, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
                sourceInitializedLocals);
        }
        insns.add(defaultLabel);
        if (!requireFullStateMachine) {
            discardUnforwardedStack(insns, switchResidualStack, blockEntryStacks.get(defaultTarget));
        }
        emitUnconditionalTransition(insns, defaultTarget, stateMap, flowKeyMap,
            sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
            stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + 29,
            null, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
            sourceInitializedLocals);
    }

    private int requiredState(Map<BasicBlock, Integer> stateMap, BasicBlock target) {
        Integer state = stateMap.get(target);
        if (state == null) {
            throw new IllegalStateException("Missing dispatch state for block " + target.id());
        }
        return state;
    }

    private void emitLoopReentry(InsnList insns, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, int variantSeed) {
        if (!shouldUseTailChain(zkmStyle, tailChainIntensity, variantSeed)) {
            insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
            return;
        }

        LabelNode tailEntry = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.GOTO, tailEntry));
        tailChains.add(new TailChain(tailEntry,
            buildTailChain(loopStart, tailSeedVar, tailFlagVar, variantSeed)));
    }

    private InsnList buildTailChain(LabelNode loopStart, int tailSeedVar, int tailFlagVar, int variantSeed) {
        InsnList tail = new InsnList();
        LabelNode fallback = new LabelNode();
        tail.add(new IincInsnNode(tailSeedVar, 1 + Math.floorMod(variantSeed, 7)));
        tail.add(new VarInsnNode(Opcodes.ILOAD, tailSeedVar));
        tail.add(AsmUtil.pushIntAny(foldMethodKey(0x5F3759DFL ^ (variantSeed * 0x45D9F3B))));
        tail.add(new InsnNode(Opcodes.IXOR));
        tail.add(new InsnNode(Opcodes.ICONST_1));
        tail.add(new InsnNode(Opcodes.IOR));
        tail.add(new InsnNode(Opcodes.DUP));
        tail.add(new VarInsnNode(Opcodes.ISTORE, tailFlagVar));
        tail.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        tail.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        tail.add(fallback);
        tail.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        return tail;
    }

    private void emitStateDecode(InsnList insns, int encodedStateVar, int dispatchStateVar,
            int stateMaskVar, int stateDeltaVar, int flowMixVar, int stateRotate) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, encodedStateVar));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateDeltaVar));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(AsmUtil.pushIntAny(stateRotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
            "rotateRight", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowMixVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dispatchStateVar));
    }

    private void emitEncodedStateStore(InsnList insns, int decodedState, int encodedStateVar,
            int stateMaskVar, int stateDeltaVar, int flowMixVar, int stateRotate, int variantSeed) {
        emitEncodedStateValue(insns, decodedState, stateMaskVar, stateDeltaVar, flowMixVar, stateRotate, variantSeed);
        insns.add(new VarInsnNode(Opcodes.ISTORE, encodedStateVar));
    }

    private void emitEncodedStateValue(InsnList insns, int decodedState, int stateMaskVar,
            int stateDeltaVar, int flowMixVar, int stateRotate, int variantSeed) {
        int variant = Math.floorMod(variantSeed, 3);
        if (variant == 1) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
            insns.add(AsmUtil.pushIntAny(decodedState));
        } else {
            insns.add(AsmUtil.pushIntAny(decodedState));
            insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
        }
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowMixVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(AsmUtil.pushIntAny(stateRotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
            "rotateLeft", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateDeltaVar));
        if (variant == 2) {
            insns.add(new InsnNode(Opcodes.INEG));
            insns.add(new InsnNode(Opcodes.ISUB));
        } else {
            insns.add(new InsnNode(Opcodes.IADD));
        }
    }

    /**
     * Real edge-keyed flowKey transition: flowKey ^= delta(source -> target).
     * The delta is the only LDC, and it's meaningless unless the running flowKey is correct,
     * so static analysis cannot recover flowKey at any point without symbolically simulating
     * the entire CFG from method entry.
     */
    private void emitFlowKeyDelta(InsnList insns, long sourceFlowKey, long targetFlowKey,
            int flowKeyVar, int flowMixVar) {
        long delta = sourceFlowKey ^ targetFlowKey;
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowKeyVar));
        insns.add(new LdcInsnNode(delta));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitStoreFlowKeyAndUpdateMix(insns, flowKeyVar, flowMixVar);
    }

    /**
     * Absolute flowKey assignment. Used at method entry and exception-handler entry where
     * predecessor flowKey is not predictable. Splits the value into two LDCs combined via
     * LXOR so neither LDC equals the target flowKey directly.
     */
    private void emitFlowKeyAbsolute(InsnList insns, long methodKey, long targetFlowKey,
            int flowKeyVar, int flowMixVar, int splitHint) {
        long splitSeed = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(
                methodKey ^ 0x4E454B0F4D4F4F4EL, splitHint));
        long maskA = splitSeed | 1L;
        long maskB = targetFlowKey ^ maskA;
        insns.add(new LdcInsnNode(maskA));
        insns.add(new LdcInsnNode(maskB));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitStoreFlowKeyAndUpdateMix(insns, flowKeyVar, flowMixVar);
    }

    /**
     * Backwards-compatible facade used only where source flowKey is unknown (legacy paths).
     * Prefer emitFlowKeyDelta / emitFlowKeyAbsolute. Embeds the value as a two-LDC XOR split
     * so the literal target value never appears as a single LDC.
     */
    private void emitFlowKeyStore(InsnList insns, long flowKey, int flowKeyVar, int flowMixVar) {
        emitFlowKeyAbsolute(insns, flowKey ^ 0xC0FFEE5EEDC0DEFFL, flowKey, flowKeyVar, flowMixVar, 0);
    }

    /**
     * Bytecode equivalent of: flowKey = (top of stack); flowMix = (int)(flowKey ^ (flowKey >>> 32)) | 1.
     * Consumes the long on top of stack, stores into flowKeyVar, and writes flowMixVar.
     */
    private void emitStoreFlowKeyAndUpdateMix(InsnList insns, int flowKeyVar, int flowMixVar) {
        // stack: [..., long newFlowKey]
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new VarInsnNode(Opcodes.LSTORE, flowKeyVar));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(AsmUtil.pushIntAny(32));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, flowMixVar));
    }

    private void emitRuntimeFlowContextSync(InsnList insns, int flowKeyVar) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowKeyVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER,
            "setCurrentFlowKey", "(J)V", false));
    }

    /**
     * Emit `value = a ^ b; ISTORE slot` as a 2-LDC XOR split, where the salt
     * derives a deterministic mask so neither LDC equals the literal value.
     */
    private void emitSplitIntStore(InsnList insns, int value, int slot, long salt) {
        int maskA = (int) (salt ^ (salt >>> 32)) | 1;
        int maskB = value ^ maskA;
        insns.add(AsmUtil.pushIntAny(maskA));
        insns.add(AsmUtil.pushIntAny(maskB));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
    }

    private void recordInstructionFlowKey(PipelineContext pctx, AbstractInsnNode insn, long flowKey) {
        flowKeyValues(pctx).put(insn, flowKey);
    }

    private Set<String> flattenedMethods(PipelineContext pctx) {
        Set<String> flattenedMethods = pctx.getPassData(FLATTENED_METHODS_KEY);
        if (flattenedMethods == null) {
            flattenedMethods = new HashSet<>();
            pctx.putPassData(FLATTENED_METHODS_KEY, flattenedMethods);
        }
        return flattenedMethods;
    }

    private IdentityHashMap<AbstractInsnNode, Long> flowKeyValues(PipelineContext pctx) {
        IdentityHashMap<AbstractInsnNode, Long> flowKeys = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        if (flowKeys == null) {
            flowKeys = new IdentityHashMap<>();
            pctx.putPassData(FLOW_KEY_VALUES_KEY, flowKeys);
        }
        return flowKeys;
    }

    private void emitOriginalLocalInitialization(InsnList insns, L1Method method, int originalMaxLocals) {
        if (originalMaxLocals <= 0) return;

        LocalInitKind[] kinds = inferOriginalLocalKinds(method, originalMaxLocals);
        for (int slot = parameterSlotCount(method); slot < originalMaxLocals; slot++) {
            LocalInitKind kind = kinds[slot];
            switch (kind) {
                case REFERENCE -> {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
                }
                case INT -> {
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
                }
                case FLOAT -> {
                    insns.add(new InsnNode(Opcodes.FCONST_0));
                    insns.add(new VarInsnNode(Opcodes.FSTORE, slot));
                }
                case LONG -> {
                    insns.add(new InsnNode(Opcodes.LCONST_0));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
                    slot++;
                }
                case DOUBLE -> {
                    insns.add(new InsnNode(Opcodes.DCONST_0));
                    insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
                    slot++;
                }
                default -> {
                }
            }
        }
    }

    private boolean hasInboundFlowKeyRead(PipelineContext pctx, InsnList instructions) {
        Set<AbstractInsnNode> reads = pctx.getPassData(INBOUND_FLOW_KEY_READS_KEY);
        if (reads == null || reads.isEmpty()) return false;
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (reads.contains(insn)) return true;
        }
        return false;
    }

    private boolean isInboundFlowKeyRead(PipelineContext pctx, AbstractInsnNode insn) {
        Set<AbstractInsnNode> reads = pctx.getPassData(INBOUND_FLOW_KEY_READS_KEY);
        return reads != null && reads.contains(insn);
    }

    private void emitInboundFlowKeyCapture(InsnList insns, int inboundFlowKeyVar) {
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER,
            "flowKey", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, inboundFlowKeyVar));
    }

    private void emitExceptionSpillInitializers(InsnList insns,
            Map<BasicBlock, Integer> exceptionSpillLocals) {
        for (Integer slot : exceptionSpillLocals.values()) {
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
        }
    }

    private LocalInitKind[] inferOriginalLocalKinds(L1Method method, int originalMaxLocals) {
        LocalInitKind[] kinds = new LocalInitKind[originalMaxLocals];
        LocalInitKind[] firstSeenKinds = new LocalInitKind[originalMaxLocals];
        Arrays.fill(kinds, LocalInitKind.UNKNOWN);
        Arrays.fill(firstSeenKinds, LocalInitKind.UNKNOWN);

        int slot = 0;
        if (!method.isStatic() && originalMaxLocals > 0) {
            kinds[0] = LocalInitKind.REFERENCE;
            firstSeenKinds[0] = LocalInitKind.REFERENCE;
            slot = 1;
        }
        for (Type argumentType : method.argumentTypes()) {
            if (slot >= originalMaxLocals) break;
            markTypeKind(kinds, slot, argumentType);
            recordTypeKind(firstSeenKinds, slot, argumentType);
            slot += argumentType.getSize();
        }

        for (LocalVariableNode localVariable : method.localVariables()) {
            if (localVariable.index < 0 || localVariable.index >= originalMaxLocals) continue;
            markTypeKind(kinds, localVariable.index, Type.getType(localVariable.desc));
            recordTypeKind(firstSeenKinds, localVariable.index, Type.getType(localVariable.desc));
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode varInsn) {
                markOpcodeKind(kinds, varInsn.getOpcode(), varInsn.var);
                recordOpcodeKind(firstSeenKinds, varInsn.getOpcode(), varInsn.var);
            } else if (insn instanceof IincInsnNode iincInsn) {
                markLocalKind(kinds, iincInsn.var, LocalInitKind.INT);
                recordPreferredKind(firstSeenKinds, iincInsn.var, LocalInitKind.INT);
            }
        }

        for (int i = 0; i < kinds.length; i++) {
            if ((kinds[i] == LocalInitKind.UNKNOWN || kinds[i] == LocalInitKind.CONFLICT)
                    && firstSeenKinds[i] != LocalInitKind.UNKNOWN
                    && firstSeenKinds[i] != LocalInitKind.CONFLICT
                    && firstSeenKinds[i] != LocalInitKind.RESERVED) {
                kinds[i] = firstSeenKinds[i];
                if ((firstSeenKinds[i] == LocalInitKind.LONG || firstSeenKinds[i] == LocalInitKind.DOUBLE)
                        && i + 1 < kinds.length
                        && (kinds[i + 1] == LocalInitKind.UNKNOWN || kinds[i + 1] == LocalInitKind.CONFLICT)) {
                    kinds[i + 1] = LocalInitKind.RESERVED;
                }
            }
        }
        return kinds;
    }

    private int parameterSlotCount(L1Method method) {
        int slots = method.isStatic() ? 0 : 1;
        for (Type argumentType : method.argumentTypes()) {
            slots += argumentType.getSize();
        }
        return slots;
    }

    private void markOpcodeKind(LocalInitKind[] kinds, int opcode, int slot) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> markLocalKind(kinds, slot, LocalInitKind.INT);
            case Opcodes.FLOAD, Opcodes.FSTORE -> markLocalKind(kinds, slot, LocalInitKind.FLOAT);
            case Opcodes.LLOAD, Opcodes.LSTORE -> markWideLocalKind(kinds, slot, LocalInitKind.LONG);
            case Opcodes.DLOAD, Opcodes.DSTORE -> markWideLocalKind(kinds, slot, LocalInitKind.DOUBLE);
            case Opcodes.ALOAD, Opcodes.ASTORE -> markLocalKind(kinds, slot, LocalInitKind.REFERENCE);
            default -> {
            }
        }
    }

    private void recordOpcodeKind(LocalInitKind[] kinds, int opcode, int slot) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> recordPreferredKind(kinds, slot, LocalInitKind.INT);
            case Opcodes.FLOAD, Opcodes.FSTORE -> recordPreferredKind(kinds, slot, LocalInitKind.FLOAT);
            case Opcodes.LLOAD, Opcodes.LSTORE -> recordPreferredWideKind(kinds, slot, LocalInitKind.LONG);
            case Opcodes.DLOAD, Opcodes.DSTORE -> recordPreferredWideKind(kinds, slot, LocalInitKind.DOUBLE);
            case Opcodes.ALOAD, Opcodes.ASTORE -> recordPreferredKind(kinds, slot, LocalInitKind.REFERENCE);
            default -> {
            }
        }
    }

    private void markTypeKind(LocalInitKind[] kinds, int slot, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> markWideLocalKind(kinds, slot, LocalInitKind.LONG);
            case Type.DOUBLE -> markWideLocalKind(kinds, slot, LocalInitKind.DOUBLE);
            case Type.FLOAT -> markLocalKind(kinds, slot, LocalInitKind.FLOAT);
            case Type.ARRAY, Type.OBJECT -> markLocalKind(kinds, slot, LocalInitKind.REFERENCE);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                markLocalKind(kinds, slot, LocalInitKind.INT);
            default -> {
            }
        }
    }

    private void recordTypeKind(LocalInitKind[] kinds, int slot, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> recordPreferredWideKind(kinds, slot, LocalInitKind.LONG);
            case Type.DOUBLE -> recordPreferredWideKind(kinds, slot, LocalInitKind.DOUBLE);
            case Type.FLOAT -> recordPreferredKind(kinds, slot, LocalInitKind.FLOAT);
            case Type.ARRAY, Type.OBJECT -> recordPreferredKind(kinds, slot, LocalInitKind.REFERENCE);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                recordPreferredKind(kinds, slot, LocalInitKind.INT);
            default -> {
            }
        }
    }

    private void markWideLocalKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        markLocalKind(kinds, slot, kind);
        if (slot + 1 < kinds.length) {
            LocalInitKind existing = kinds[slot + 1];
            if (existing == LocalInitKind.UNKNOWN || existing == LocalInitKind.RESERVED) {
                kinds[slot + 1] = LocalInitKind.RESERVED;
            } else if (existing != kind) {
                kinds[slot + 1] = LocalInitKind.CONFLICT;
            }
        }
    }

    private void recordPreferredWideKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        recordPreferredKind(kinds, slot, kind);
        if (slot + 1 < kinds.length && kinds[slot + 1] == LocalInitKind.UNKNOWN) {
            kinds[slot + 1] = LocalInitKind.RESERVED;
        }
    }

    private void markLocalKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        if (slot < 0 || slot >= kinds.length) return;
        LocalInitKind existing = kinds[slot];
        if (existing == LocalInitKind.UNKNOWN || existing == kind) {
            kinds[slot] = kind;
            return;
        }
        if (existing == LocalInitKind.RESERVED && kind == LocalInitKind.RESERVED) {
            return;
        }
        kinds[slot] = LocalInitKind.CONFLICT;
    }

    private void recordPreferredKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        if (slot < 0 || slot >= kinds.length) {
            return;
        }
        if (kinds[slot] == LocalInitKind.UNKNOWN) {
            kinds[slot] = kind;
        }
    }

    private int[] blockEmissionOrder(PipelineContext pctx, int blockCount, boolean preserveTryCatchOrder) {
        if (!preserveTryCatchOrder) {
            return pctx.random().randomPermutation(blockCount);
        }

        int[] order = new int[blockCount];
        for (int i = 0; i < blockCount; i++) {
            order[i] = i;
        }
        return order;
    }

    private void partitionBlocks(List<BasicBlock> blocks, List<BasicBlock> dispatchBlocks, List<BasicBlock> handlerBlocks) {
        for (BasicBlock block : blocks) {
            if (block.isExceptionHandler()) {
                handlerBlocks.add(block);
            } else {
                dispatchBlocks.add(block);
            }
        }
    }

    private List<BasicBlock> splitLinearDispatchBlocks(L1Method method, List<BasicBlock> dispatchBlocks,
            List<BasicBlock> handlerBlocks, IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames,
            PipelineContext pctx) {
        if (!handlerBlocks.isEmpty() || !method.tryCatchBlocks().isEmpty() || dispatchBlocks.size() != 1) {
            return null;
        }
        if (!isEligibleEntryPoint(method)) {
            return null;
        }

        BasicBlock original = dispatchBlocks.get(0);
        int realInsns = 0;
        for (AbstractInsnNode insn : original.instructions()) {
            if (AsmUtil.isRealInstruction(insn)) realInsns++;
        }
        int chunkSize = Math.max(8, intOption(pctx.config().transforms().get("controlFlowFlattening"),
            LINEAR_CHUNK_SIZE_OPTION, 24));
        if (realInsns < chunkSize * 2) return null;

        List<BasicBlock> split = new ArrayList<>();
        BasicBlock current = new BasicBlock(0);
        int currentRealInsns = 0;
        int nextId = 1;

        for (AbstractInsnNode insn : original.instructions()) {
            if (!current.instructions().isEmpty()
                    && currentRealInsns >= chunkSize
                    && isLinearSplitPoint(insn, frames)) {
                split.add(current);
                current = new BasicBlock(nextId++);
                currentRealInsns = 0;
            }
            current.addInstruction(insn);
            if (AsmUtil.isRealInstruction(insn)) {
                currentRealInsns++;
            }
        }
        if (!current.instructions().isEmpty()) {
            split.add(current);
        }
        if (split.size() < 3) return null;

        for (int i = 0; i + 1 < split.size(); i++) {
            CFGEdge edge = new CFGEdge(split.get(i), split.get(i + 1), CFGEdge.Type.FALL_THROUGH);
            split.get(i).addOutEdge(edge);
            split.get(i + 1).addInEdge(edge);
        }
        return split;
    }

    private boolean isLinearSplitPoint(AbstractInsnNode insn,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames) {
        if (!AsmUtil.isRealInstruction(insn)) return false;
        Frame<BasicValue> frame = frames.get(insn);
        return frame != null && frame.getStackSize() == 0;
    }

    private IdentityHashMap<LabelNode, Integer> labelCodePositions(InsnList insns) {
        IdentityHashMap<LabelNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                positions.put(label, index);
            }
            if (isBytecodeInsn(insn)) {
                index++;
            }
        }
        return positions;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> codePositions(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index);
            if (isBytecodeInsn(insn)) {
                index++;
            }
        }
        return positions;
    }

    private Map<LabelNode, ExceptionHandlerStub> buildExceptionHandlerStubs(
            List<TryCatchBlockNode> tryCatchBlocks, List<BasicBlock> blocks) {
        if (tryCatchBlocks == null || tryCatchBlocks.isEmpty()) {
            return Map.of();
        }
        Map<LabelNode, BasicBlock> labelOwners = new IdentityHashMap<>();
        for (BasicBlock block : blocks) {
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode label) {
                    labelOwners.put(label, block);
                }
            }
        }

        Map<LabelNode, ExceptionHandlerStub> stubs = new LinkedHashMap<>();
        for (TryCatchBlockNode tcb : tryCatchBlocks) {
            BasicBlock handlerBlock = labelOwners.get(tcb.handler);
            if (handlerBlock == null) {
                throw new IllegalStateException("CFF cannot rebuild try/catch: missing handler block");
            }
            stubs.computeIfAbsent(tcb.handler, ignored ->
                new ExceptionHandlerStub(new LabelNode(), handlerBlock));
        }
        return stubs;
    }

    private void emitExceptionHandlerStubs(InsnList insns,
            Map<LabelNode, ExceptionHandlerStub> exceptionHandlerStubs,
            Map<BasicBlock, Integer> exceptionSpillLocals,
            Map<BasicBlock, Long> flowKeyMap, long methodKey,
            int flowKeyVar, int flowMixVar, Map<BasicBlock, Integer> stateMap,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            LabelNode loopStart) {
        for (ExceptionHandlerStub stub : exceptionHandlerStubs.values()) {
            BasicBlock handlerBlock = stub.handlerBlock();
            Integer exceptionSpillLocal = exceptionSpillLocals.get(handlerBlock);
            Integer handlerState = stateMap.get(handlerBlock);
            if (exceptionSpillLocal == null || handlerState == null) {
                throw new IllegalStateException("CFF cannot route exception handler through dispatcher");
            }
            long handlerFlowKey = flowKeyMap.getOrDefault(handlerBlock, 0L);
            insns.add(stub.label());
            insns.add(new VarInsnNode(Opcodes.ASTORE, exceptionSpillLocal));
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerBlock.id());
            emitEncodedStateStore(insns, handlerState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, handlerBlock.id());
            insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        }
    }

    private List<TryCatchBlockNode> rebuildTryCatchBlocksFromEmittedOrigins(
            List<TryCatchBlockNode> originalTryCatchBlocks,
            Map<LabelNode, LabelNode> labelRemap,
            Map<LabelNode, ExceptionHandlerStub> exceptionHandlerStubs,
            IdentityHashMap<AbstractInsnNode, AbstractInsnNode> emittedOrigins,
            InsnList emittedInsns) {
        List<TryCatchBlockNode> rebuilt = new ArrayList<>();
        for (TryCatchBlockNode tcb : originalTryCatchBlocks) {
            ExceptionHandlerStub stub = exceptionHandlerStubs.get(tcb.handler);
            LabelNode newHandler = stub == null ? labelRemap.get(tcb.handler) : stub.label();
            if (newHandler == null) {
                throw new IllegalStateException("CFF cannot rebuild try/catch: missing remapped handler");
            }

            Set<AbstractInsnNode> protectedOriginals = Collections.newSetFromMap(new IdentityHashMap<>());
            for (AbstractInsnNode insn = tcb.start; insn != null && insn != tcb.end; insn = insn.getNext()) {
                if (isExceptionTableRelevantInsn(insn)) {
                    protectedOriginals.add(insn);
                }
            }
            if (protectedOriginals.isEmpty()) {
                continue;
            }

            AbstractInsnNode segmentStart = null;
            AbstractInsnNode lastProtected = null;
            for (AbstractInsnNode emitted = emittedInsns.getFirst(); emitted != null; emitted = emitted.getNext()) {
                AbstractInsnNode origin = emittedOrigins.get(emitted);
                boolean protectedClone = origin != null && protectedOriginals.contains(origin);
                if (protectedClone) {
                    if (segmentStart == null) {
                        segmentStart = emitted;
                    }
                    lastProtected = emitted;
                    continue;
                }

                if (segmentStart != null && isBytecodeInsn(emitted)) {
                    appendPreciseTryCatchRange(rebuilt, emittedInsns, segmentStart, emitted, newHandler, tcb.type);
                    segmentStart = null;
                    lastProtected = null;
                }
            }

            if (segmentStart != null && lastProtected != null) {
                appendPreciseTryCatchRange(rebuilt, emittedInsns, segmentStart,
                    lastProtected.getNext(), newHandler, tcb.type);
            }
        }
        return rebuilt;
    }

    private void appendPreciseTryCatchRange(List<TryCatchBlockNode> rebuilt, InsnList emittedInsns,
            AbstractInsnNode startInsn, AbstractInsnNode endInsn, LabelNode handler, String type) {
        if (startInsn == null || handler == null) {
            return;
        }
        LabelNode start = insertFreshLabelBefore(emittedInsns, startInsn);
        LabelNode end = endInsn == null
            ? appendFreshLabel(emittedInsns)
            : insertFreshLabelBefore(emittedInsns, endInsn);
        if (start != end && start != handler && end != handler) {
            rebuilt.add(new TryCatchBlockNode(start, end, handler, type));
        }
    }

    private LabelNode insertFreshLabelBefore(InsnList insns, AbstractInsnNode target) {
        LabelNode label = new LabelNode();
        insns.insertBefore(target, label);
        return label;
    }

    private LabelNode appendFreshLabel(InsnList insns) {
        LabelNode label = new LabelNode();
        insns.add(label);
        return label;
    }

    private boolean isExceptionTableRelevantInsn(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode < 0) {
            return false;
        }
        if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) {
            return true;
        }
        if (insn instanceof FieldInsnNode || insn instanceof LdcInsnNode || insn instanceof TypeInsnNode) {
            return true;
        }
        if (insn instanceof MultiANewArrayInsnNode) {
            return true;
        }
        return switch (opcode) {
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                 Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                 Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                 Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
                 Opcodes.IDIV, Opcodes.LDIV, Opcodes.IREM, Opcodes.LREM,
                 Opcodes.ARRAYLENGTH, Opcodes.ATHROW,
                 Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                 Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> true;
            default -> false;
        };
    }

    private boolean isBytecodeInsn(AbstractInsnNode insn) {
        return !(insn instanceof LabelNode)
            && !(insn instanceof FrameNode)
            && !(insn instanceof LineNumberNode);
    }

    private IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzeFrames(String ownerName, MethodNode mn,
            ClassHierarchy hierarchy) {
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn = new IdentityHashMap<>();
        if (analyzeWithInterpreter(ownerName, mn, framesByInsn, new RefTypedInterpreter(hierarchy))) {
            return framesByInsn;
        }
        framesByInsn.clear();
        analyzeWithInterpreter(ownerName, mn, framesByInsn, new BasicInterpreter());
        return framesByInsn;
    }

    private boolean analyzeWithInterpreter(String ownerName, MethodNode mn,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn,
            Interpreter<BasicValue> interpreter) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(interpreter);
            Frame<BasicValue>[] frames = analyzer.analyze(ownerName, mn);
            AbstractInsnNode[] instructions = mn.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                Frame<BasicValue> frame = frames[i];
                if (frame != null) {
                    framesByInsn.put(instructions[i], frame);
                }
            }
            return true;
        } catch (AnalyzerException | RuntimeException ignored) {
            return false;
        }
    }

    private IdentityHashMap<AbstractInsnNode, Integer> extractStackHeights(
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        IdentityHashMap<AbstractInsnNode, Integer> stackHeights = new IdentityHashMap<>();
        for (Map.Entry<AbstractInsnNode, Frame<BasicValue>> entry : framesByInsn.entrySet()) {
            stackHeights.put(entry.getKey(), entry.getValue().getStackSize());
        }
        return stackHeights;
    }

    private Map<BasicBlock, List<StackSlotKind>> analyzeBlockEntryStacks(List<BasicBlock> blocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        Map<BasicBlock, List<StackSlotKind>> blockEntryStacks = new HashMap<>();
        for (BasicBlock block : blocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                blockEntryStacks.put(block, List.of());
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getStackSize() == 0) {
                blockEntryStacks.put(block, List.of());
                continue;
            }
            List<StackSlotKind> stackKinds = new ArrayList<>(frame.getStackSize());
            for (int i = 0; i < frame.getStackSize(); i++) {
                stackKinds.add(stackSlotKind(frame.getStack(i)));
            }
            blockEntryStacks.put(block, List.copyOf(stackKinds));
        }
        return blockEntryStacks;
    }

    private Map<BasicBlock, List<String>> analyzeBlockEntryStackTypes(List<BasicBlock> blocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        Map<BasicBlock, List<String>> blockEntryStackTypes = new HashMap<>();
        for (BasicBlock block : blocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                blockEntryStackTypes.put(block, List.of());
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getStackSize() == 0) {
                blockEntryStackTypes.put(block, List.of());
                continue;
            }
            List<String> stackTypes = new ArrayList<>(frame.getStackSize());
            for (int i = 0; i < frame.getStackSize(); i++) {
                stackTypes.add(referenceStackTypeName(frame.getStack(i)));
            }
            blockEntryStackTypes.put(block, Collections.unmodifiableList(stackTypes));
        }
        return blockEntryStackTypes;
    }

    private Map<BasicBlock, String> analyzeExceptionSpillTypes(List<BasicBlock> handlerBlocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        Map<BasicBlock, String> exceptionSpillTypes = new IdentityHashMap<>();
        for (BasicBlock handlerBlock : handlerBlocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(handlerBlock);
            if (firstInsn == null) {
                exceptionSpillTypes.put(handlerBlock, "java/lang/Throwable");
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            String typeName = null;
            if (frame != null && frame.getStackSize() > 0) {
                typeName = referenceStackTypeName(frame.getStack(0));
            }
            exceptionSpillTypes.put(handlerBlock, typeName == null ? "java/lang/Throwable" : typeName);
        }
        return exceptionSpillTypes;
    }

    private void applyExceptionHandlerStackTypes(List<BasicBlock> blocks, List<TryCatchBlockNode> tryCatchBlocks,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, List<String>> blockEntryStackTypes,
            ClassHierarchy hierarchy) {
        if (tryCatchBlocks == null || tryCatchBlocks.isEmpty()) {
            return;
        }
        Map<LabelNode, BasicBlock> labelOwners = new IdentityHashMap<>();
        for (BasicBlock block : blocks) {
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode label) {
                    labelOwners.put(label, block);
                }
            }
        }
        for (TryCatchBlockNode tcb : tryCatchBlocks) {
            BasicBlock handlerBlock = labelOwners.get(tcb.handler);
            if (handlerBlock == null) {
                continue;
            }
            List<StackSlotKind> stackKinds = blockEntryStacks.get(handlerBlock);
            if (stackKinds == null || stackKinds.isEmpty() || stackKinds.get(0) != StackSlotKind.REFERENCE) {
                continue;
            }
            List<String> stackTypes = new ArrayList<>(blockEntryStackTypes.getOrDefault(handlerBlock, List.of()));
            while (stackTypes.size() < stackKinds.size()) {
                stackTypes.add(null);
            }
            String catchType = tcb.type == null ? "java/lang/Throwable" : tcb.type;
            stackTypes.set(0, mergeExceptionStackType(stackTypes.get(0), catchType, hierarchy));
            blockEntryStackTypes.put(handlerBlock, Collections.unmodifiableList(stackTypes));
        }
    }

    private String mergeExceptionStackType(String existing, String catchType, ClassHierarchy hierarchy) {
        if (catchType == null || "java/lang/Object".equals(catchType)) {
            catchType = "java/lang/Throwable";
        }
        if (existing == null || "java/lang/Object".equals(existing)) {
            return catchType;
        }
        if (existing.equals(catchType)) {
            return existing;
        }
        String merged = hierarchy == null ? null : hierarchy.getCommonSuperClass(existing, catchType);
        if (merged == null || "java/lang/Object".equals(merged)) {
            return "java/lang/Throwable";
        }
        return merged;
    }

    private String referenceStackTypeName(BasicValue value) {
        if (value instanceof RefTypedValue typed && typed.referenceType != null
                && !"java/lang/Object".equals(typed.referenceType)) {
            return typed.referenceType;
        }
        Type type = value == null ? null : value.getType();
        if (type == null) {
            return null;
        }
        return switch (type.getSort()) {
            case Type.OBJECT -> "java/lang/Object".equals(type.getInternalName()) ? null : type.getInternalName();
            case Type.ARRAY -> type.getDescriptor();
            default -> null;
        };
    }

    private Map<BasicBlock, List<StackSlotKind>> analyzeBlockExitStacks(List<BasicBlock> blocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        Map<BasicBlock, List<StackSlotKind>> blockExitStacks = new HashMap<>();
        for (BasicBlock block : blocks) {
            AbstractInsnNode lastBodyInsn = null;
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                    continue;
                }
                if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                    continue;
                }
                if (isTerminator(insn, block)) {
                    continue;
                }
                lastBodyInsn = insn;
            }
            Frame<BasicValue> frame = frameAfter(lastBodyInsn, block, framesByInsn);
            blockExitStacks.put(block, stackKinds(frame));
        }
        return blockExitStacks;
    }

    private Map<BasicBlock, BitSet> analyzeBlockExitInitializedLocals(List<BasicBlock> blocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn, int originalMaxLocals) {
        Map<BasicBlock, BitSet> initializedByBlock = new IdentityHashMap<>();
        for (BasicBlock block : blocks) {
            AbstractInsnNode lastBodyInsn = null;
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                    continue;
                }
                if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                    continue;
                }
                if (isTerminator(insn, block)) {
                    continue;
                }
                lastBodyInsn = insn;
            }
            Frame<BasicValue> frame = frameAfter(lastBodyInsn, block, framesByInsn);
            initializedByBlock.put(block, initializedLocals(frame, originalMaxLocals));
        }
        return initializedByBlock;
    }

    private BitSet initializedLocals(Frame<BasicValue> frame, int originalMaxLocals) {
        BitSet initialized = new BitSet();
        if (frame == null || originalMaxLocals <= 0) {
            return initialized;
        }
        int upperBound = Math.min(originalMaxLocals, frame.getLocals());
        for (int slot = 0; slot < upperBound; slot++) {
            if (isInitializedLocalValue(frame.getLocal(slot))) {
                initialized.set(slot);
            }
        }
        return initialized;
    }

    private void promoteConnectorStackRequirements(List<BasicBlock> blocks,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, List<StackSlotKind>> blockExitStacks) {
        for (BasicBlock block : blocks) {
            if (hasCloneableBodyInstruction(block)) {
                continue;
            }
            List<StackSlotKind> entry = blockEntryStacks.get(block);
            List<StackSlotKind> exit = blockExitStacks.get(block);
            if (exit != null && (entry == null || exit.size() > entry.size())) {
                blockEntryStacks.put(block, exit);
            }
        }
    }

    private boolean hasCloneableBodyInstruction(BasicBlock block) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                continue;
            }
            if (isTerminator(insn, block)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private Frame<BasicValue> frameAfter(AbstractInsnNode insn, BasicBlock block,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        if (insn == null) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            return firstInsn == null ? null : framesByInsn.get(firstInsn);
        }
        for (AbstractInsnNode cursor = insn.getNext(); cursor != null; cursor = cursor.getNext()) {
            Frame<BasicValue> frame = framesByInsn.get(cursor);
            if (frame != null) {
                return frame;
            }
            if (!(cursor instanceof LabelNode || cursor instanceof FrameNode || cursor instanceof LineNumberNode)) {
                break;
            }
        }
        return framesByInsn.get(insn);
    }

    private List<StackSlotKind> stackKinds(Frame<BasicValue> frame) {
        if (frame == null || frame.getStackSize() == 0) {
            return List.of();
        }
        List<StackSlotKind> stackKinds = new ArrayList<>(frame.getStackSize());
        for (int i = 0; i < frame.getStackSize(); i++) {
            stackKinds.add(stackSlotKind(frame.getStack(i)));
        }
        return List.copyOf(stackKinds);
    }

    private Map<BasicBlock, List<LocalSlotState>> analyzeBlockEntryLocals(List<BasicBlock> allBlocks,
            List<BasicBlock> dispatchBlocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn, int originalMaxLocals,
            L1Method method) {
        Map<BasicBlock, List<LocalSlotState>> blockEntryLocals = new HashMap<>();
        if (originalMaxLocals <= 0 || allBlocks.isEmpty() || dispatchBlocks.isEmpty()) {
            return blockEntryLocals;
        }

        Map<BasicBlock, LocalFlowSummary> localFlowByBlock = new IdentityHashMap<>();
        Map<BasicBlock, Frame<BasicValue>> entryFrames = new IdentityHashMap<>();

        for (BasicBlock block : allBlocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                localFlowByBlock.put(block, new LocalFlowSummary(new BitSet(), new BitSet()));
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getLocals() == 0 || originalMaxLocals <= 0) {
                localFlowByBlock.put(block, new LocalFlowSummary(new BitSet(), new BitSet()));
                continue;
            }
            entryFrames.put(block, frame);
            int upperBound = Math.min(originalMaxLocals, frame.getLocals());
            localFlowByBlock.put(block, summarizeLocalFlow(block, upperBound));
        }

        BitSet everWritten = new BitSet();
        for (LocalFlowSummary flow : localFlowByBlock.values()) {
            if (flow != null) {
                everWritten.or(flow.defs());
            }
        }

        Map<BasicBlock, BitSet> liveIn = new IdentityHashMap<>();
        Map<BasicBlock, BitSet> liveOut = new IdentityHashMap<>();
        for (BasicBlock block : allBlocks) {
            LocalFlowSummary flow = localFlowByBlock.get(block);
            liveIn.put(block, copyBitSet(flow == null ? null : flow.uses()));
            liveOut.put(block, new BitSet());
        }

        boolean changed;
        do {
            changed = false;
            for (int i = allBlocks.size() - 1; i >= 0; i--) {
                BasicBlock block = allBlocks.get(i);
                LocalFlowSummary flow = localFlowByBlock.get(block);
                if (flow == null) {
                    continue;
                }

                BitSet newOut = new BitSet();
                for (CFGEdge edge : block.outEdges()) {
                    BitSet successorLiveIn = liveIn.get(edge.target());
                    if (successorLiveIn != null) {
                        newOut.or(successorLiveIn);
                    }
                }

                BitSet newIn = copyBitSet(newOut);
                newIn.andNot(flow.defs());
                newIn.or(flow.uses());

                if (!newOut.equals(liveOut.get(block))) {
                    liveOut.put(block, newOut);
                    changed = true;
                }
                if (!newIn.equals(liveIn.get(block))) {
                    liveIn.put(block, newIn);
                    changed = true;
                }
            }
        } while (changed);

        IdentityHashMap<AbstractInsnNode, Integer> originalPositions = computeInsnPositions(method);
        for (BasicBlock block : dispatchBlocks) {
            Frame<BasicValue> frame = entryFrames.get(block);
            if (frame == null || frame.getLocals() == 0) {
                blockEntryLocals.put(block, List.of());
                continue;
            }
            int upperBound = Math.min(originalMaxLocals, frame.getLocals());
            BitSet entryLive = copyBitSet(liveIn.get(block));
            entryLive.and(everWritten);
            LocalFlowSummary directFlow = localFlowByBlock.get(block);
            if (directFlow != null) {
                entryLive.or(directFlow.uses());
            }
            // Always include `this` so the dispatcher restore can CHECKCAST
            // back to the declared owner type even though the bytecode never
            // explicitly writes slot 0.
            if (!method.isStatic() && upperBound > 0) {
                BasicValue slot0 = frame.getLocal(0);
                if (slot0 != null && slot0 != BasicValue.UNINITIALIZED_VALUE) {
                    entryLive.set(0);
                }
            }
            AbstractInsnNode entryPoint = firstExecutableInsn(block);
            blockEntryLocals.put(block,
                materializeLiveInLocals(entryLive, frame, upperBound, method, block, entryPoint, originalPositions));
        }
        return blockEntryLocals;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> computeInsnPositions(L1Method method) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        InsnList insns = method.instructions();
        if (insns == null) {
            return positions;
        }
        int idx = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, idx++);
        }
        return positions;
    }

    private String inferReferenceType(L1Method method, int slot, AbstractInsnNode position,
            IdentityHashMap<AbstractInsnNode, Integer> positions) {
        if (slot == 0 && !method.isStatic()) {
            return method.owner().name();
        }

        int currentSlot = method.isStatic() ? 0 : 1;
        for (Type argType : method.argumentTypes()) {
            if (currentSlot == slot) {
                if (argType.getSort() == Type.OBJECT) {
                    return argType.getInternalName();
                }
                if (argType.getSort() == Type.ARRAY) {
                    return argType.getDescriptor();
                }
                return null;
            }
            currentSlot += argType.getSize();
            if (currentSlot > slot) {
                return null;
            }
        }

        if (position == null) {
            return null;
        }
        Integer positionIdx = positions.get(position);
        if (positionIdx == null) {
            return null;
        }

        LocalVariableNode best = null;
        int bestScopeSize = Integer.MAX_VALUE;
        for (LocalVariableNode lv : method.localVariables()) {
            if (lv.index != slot) continue;
            Integer startIdx = positions.get(lv.start);
            Integer endIdx = positions.get(lv.end);
            if (startIdx == null || endIdx == null) continue;
            if (positionIdx < startIdx || positionIdx > endIdx) continue;
            Type t = Type.getType(lv.desc);
            if (t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY) continue;
            int scopeSize = endIdx - startIdx;
            if (scopeSize < bestScopeSize) {
                bestScopeSize = scopeSize;
                best = lv;
            }
        }
        if (best == null) {
            return null;
        }
        Type t = Type.getType(best.desc);
        if (t.getSort() == Type.OBJECT) {
            return t.getInternalName();
        }
        if (t.getSort() == Type.ARRAY) {
            return t.getDescriptor();
        }
        return null;
    }

    private LocalFlowSummary summarizeLocalFlow(BasicBlock block, int upperBound) {
        if (upperBound <= 0) {
            return new LocalFlowSummary(new BitSet(), new BitSet());
        }

        boolean[] written = new boolean[upperBound];
        BitSet uses = new BitSet(upperBound);
        BitSet defs = new BitSet(upperBound);

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }

            if (insn instanceof IincInsnNode iinc) {
                if (iinc.var < upperBound && !written[iinc.var]) {
                    uses.set(iinc.var);
                }
                markWritten(written, iinc.var, 1);
                defs.set(iinc.var);
                continue;
            }

            if (!(insn instanceof VarInsnNode varInsn)) {
                continue;
            }

            int slot = varInsn.var;
            if (slot >= upperBound) {
                continue;
            }

            int opcode = varInsn.getOpcode();
            if (isLocalLoadOpcode(opcode)) {
                if (!written[slot]) {
                    uses.set(slot);
                }
                continue;
            }

            if (isLocalStoreOpcode(opcode)) {
                int size = localSlotSize(opcode);
                markWritten(written, slot, size);
                defs.set(slot);
                if (size == 2 && slot + 1 < upperBound) {
                    defs.set(slot + 1);
                }
            }
        }

        return new LocalFlowSummary(uses, defs);
    }

    private List<LocalSlotState> materializeLiveInLocals(BitSet liveIn,
            Frame<BasicValue> entryFrame, int upperBound, L1Method method, BasicBlock block,
            AbstractInsnNode entryPoint, IdentityHashMap<AbstractInsnNode, Integer> positions) {
        if (liveIn == null || liveIn.isEmpty() || upperBound <= 0) {
            return List.of();
        }

        List<LocalSlotState> locals = new ArrayList<>();
        for (int slot = liveIn.nextSetBit(0); slot >= 0 && slot < upperBound; slot = liveIn.nextSetBit(slot + 1)) {
            BasicValue value = entryFrame.getLocal(slot);
            StackSlotKind kind = isInitializedLocalValue(value)
                ? stackSlotKind(value)
                : inferLiveInLocalKindFromUses(block, slot);
            if (kind == null) continue;
            String referenceType = null;
            if (kind == StackSlotKind.REFERENCE) {
                if (value instanceof RefTypedValue typed) {
                    referenceType = typed.referenceType;
                }
                if (referenceType == null) {
                    referenceType = inferReferenceType(method, slot, entryPoint, positions);
                }
                if (referenceType == null || "java/lang/Object".equals(referenceType)) {
                    referenceType = inferReferenceTypeFromUses(block, slot);
                }
            }
            locals.add(new LocalSlotState(slot, kind, referenceType));
        }
        return locals.isEmpty() ? List.of() : List.copyOf(locals);
    }

    private StackSlotKind inferLiveInLocalKindFromUses(BasicBlock block, int slot) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof VarInsnNode var && var.var == slot) {
                int opcode = var.getOpcode();
                if (isLocalStoreOpcode(opcode)) {
                    return null;
                }
                return switch (opcode) {
                    case Opcodes.ILOAD -> StackSlotKind.INT;
                    case Opcodes.LLOAD -> StackSlotKind.LONG;
                    case Opcodes.FLOAD -> StackSlotKind.FLOAT;
                    case Opcodes.DLOAD -> StackSlotKind.DOUBLE;
                    case Opcodes.ALOAD -> StackSlotKind.REFERENCE;
                    default -> null;
                };
            }
        }
        return null;
    }

    private String inferReferenceTypeFromUses(BasicBlock block, int slot) {
        List<Integer> recentReferenceLoads = new ArrayList<>();
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof VarInsnNode var) {
                if (var.getOpcode() == Opcodes.ALOAD) {
                    recentReferenceLoads.add(var.var);
                } else if (var.var == slot && isLocalStoreOpcode(var.getOpcode())) {
                    return null;
                }
            }
            if (insn instanceof MethodInsnNode methodInsn) {
                String type = inferReferenceTypeFromInvocation(slot, recentReferenceLoads,
                    methodInvocationReferenceArguments(methodInsn));
                if (type != null) return type;
            } else if (insn instanceof InvokeDynamicInsnNode indyInsn) {
                String type = inferReferenceTypeFromInvocation(slot, recentReferenceLoads,
                    invocationReferenceArguments(Type.getArgumentTypes(indyInsn.desc)));
                if (type != null) return type;
            }
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode || isTerminator(insn, block)) {
                break;
            }
        }
        return null;
    }

    private List<String> methodInvocationReferenceArguments(MethodInsnNode methodInsn) {
        List<String> types = new ArrayList<>();
        if (methodInsn.getOpcode() != Opcodes.INVOKESTATIC) {
            types.add(methodInsn.owner);
        }
        types.addAll(invocationReferenceArguments(Type.getArgumentTypes(methodInsn.desc)));
        return types;
    }

    private List<String> invocationReferenceArguments(Type[] argumentTypes) {
        List<String> types = new ArrayList<>();
        for (Type argumentType : argumentTypes) {
            if (argumentType.getSort() == Type.OBJECT) {
                types.add(argumentType.getInternalName());
            } else if (argumentType.getSort() == Type.ARRAY) {
                types.add(argumentType.getDescriptor());
            }
        }
        return types;
    }

    private String inferReferenceTypeFromInvocation(int slot, List<Integer> recentReferenceLoads,
            List<String> referenceArguments) {
        if (referenceArguments.isEmpty() || recentReferenceLoads.size() < referenceArguments.size()) {
            return null;
        }
        int loadOffset = recentReferenceLoads.size() - referenceArguments.size();
        for (int i = 0; i < referenceArguments.size(); i++) {
            if (recentReferenceLoads.get(loadOffset + i) == slot) {
                String type = referenceArguments.get(i);
                return "java/lang/Object".equals(type) ? null : type;
            }
        }
        return null;
    }

    private BitSet copyBitSet(BitSet source) {
        return source == null ? new BitSet() : (BitSet) source.clone();
    }

    private void markWritten(boolean[] written, int slot, int size) {
        if (slot < 0 || slot >= written.length) {
            return;
        }
        written[slot] = true;
        if (size == 2 && slot + 1 < written.length) {
            written[slot + 1] = true;
        }
    }

    private boolean isLocalLoadOpcode(int opcode) {
        return opcode == Opcodes.ILOAD
            || opcode == Opcodes.LLOAD
            || opcode == Opcodes.FLOAD
            || opcode == Opcodes.DLOAD
            || opcode == Opcodes.ALOAD;
    }

    private boolean isLocalStoreOpcode(int opcode) {
        return opcode == Opcodes.ISTORE
            || opcode == Opcodes.LSTORE
            || opcode == Opcodes.FSTORE
            || opcode == Opcodes.DSTORE
            || opcode == Opcodes.ASTORE;
    }

    private int localSlotSize(int opcode) {
        return opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD
            || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
    }

    private AbstractInsnNode firstExecutableInsn(BasicBlock block) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            return insn;
        }
        return null;
    }

    private int allocateSpillLocals(List<BasicBlock> dispatchBlocks,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases, int nextLocal) {
        for (BasicBlock block : dispatchBlocks) {
            List<StackSlotKind> stackKinds = blockEntryStacks.get(block);
            if (stackKinds == null || stackKinds.isEmpty()) {
                continue;
            }
            blockSpillBases.put(block, nextLocal);
            for (StackSlotKind kind : stackKinds) {
                nextLocal += kind.slotSize();
            }
        }
        return nextLocal;
    }

    private int allocateLocalSpillLocals(List<BasicBlock> dispatchBlocks,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases, int nextLocal) {
        for (BasicBlock block : dispatchBlocks) {
            List<LocalSlotState> locals = blockEntryLocals.get(block);
            if (locals == null || locals.isEmpty()) {
                continue;
            }
            blockLocalSpillBases.put(block, nextLocal);
            for (LocalSlotState local : locals) {
                nextLocal += local.kind().slotSize();
            }
        }
        return nextLocal;
    }

    private void initializeSyntheticSpillLocals(InsnList insns,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        for (Map.Entry<BasicBlock, Integer> entry : blockSpillBases.entrySet()) {
            initializeSpillRange(insns, blockEntryStacks.get(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<BasicBlock, Integer> entry : blockLocalSpillBases.entrySet()) {
            initializeLocalSpillRange(insns, blockEntryLocals.get(entry.getKey()), entry.getValue());
        }
    }

    private void initializeSpillRange(InsnList insns, List<StackSlotKind> stackKinds, int spillBase) {
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        int offset = 0;
        for (StackSlotKind kind : stackKinds) {
            emitDefaultStore(insns, kind, spillBase + offset);
            offset += kind.slotSize();
        }
    }

    private void initializeLocalSpillRange(InsnList insns, List<LocalSlotState> locals, int spillBase) {
        if (locals == null || locals.isEmpty()) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            emitDefaultStore(insns, local.kind(), spillBase + offset);
            offset += local.kind().slotSize();
        }
    }

    private void emitDefaultStore(InsnList insns, StackSlotKind kind, int slot) {
        switch (kind) {
            case REFERENCE -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            case INT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
            }
            case FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new VarInsnNode(Opcodes.FSTORE, slot));
            }
            case LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
            }
            case DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
            }
        }
    }

    private boolean isInitializedLocalValue(BasicValue value) {
        return value != null && value != BasicValue.UNINITIALIZED_VALUE;
    }

    private int originalMaxLocals(L1Method method) {
        return method.asmNode().maxLocals;
    }

    private void restoreBlockEntryStack(InsnList insns, BasicBlock block,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, List<String>> blockEntryStackTypes,
            Map<BasicBlock, Integer> blockSpillBases) {
        List<StackSlotKind> stackKinds = blockEntryStacks.get(block);
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        Integer spillBase = blockSpillBases.get(block);
        if (spillBase == null) {
            return;
        }
        List<String> stackTypes = blockEntryStackTypes.getOrDefault(block, List.of());
        int index = 0;
        int offset = 0;
        for (StackSlotKind kind : stackKinds) {
            insns.add(new VarInsnNode(kind.loadOpcode(), spillBase + offset));
            if (kind == StackSlotKind.REFERENCE && index < stackTypes.size()) {
                String typeName = stackTypes.get(index);
                if (typeName != null) {
                    insns.add(new TypeInsnNode(Opcodes.CHECKCAST, typeName));
                }
            }
            offset += kind.slotSize();
            index++;
        }
    }

    private void restoreBlockEntryLocals(InsnList insns, BasicBlock block,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        List<LocalSlotState> locals = blockEntryLocals.get(block);
        if (locals == null || locals.isEmpty()) {
            return;
        }
        Integer spillBase = blockLocalSpillBases.get(block);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            insns.add(new VarInsnNode(local.kind().loadOpcode(), spillBase + offset));
            if (local.kind() == StackSlotKind.REFERENCE
                    && local.referenceType() != null
                    && !"java/lang/Object".equals(local.referenceType())) {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, local.referenceType()));
            }
            insns.add(new VarInsnNode(local.kind().storeOpcode(), local.slot()));
            offset += local.kind().slotSize();
        }
    }

    private void spillStackForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases) {
        List<StackSlotKind> stackKinds = blockEntryStacks.get(target);
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        Integer spillBase = blockSpillBases.get(target);
        if (spillBase == null) {
            return;
        }
        int[] offsets = new int[stackKinds.size()];
        int offset = 0;
        for (int i = 0; i < stackKinds.size(); i++) {
            offsets[i] = offset;
            offset += stackKinds.get(i).slotSize();
        }
        for (int i = stackKinds.size() - 1; i >= 0; i--) {
            StackSlotKind kind = stackKinds.get(i);
            insns.add(new VarInsnNode(kind.storeOpcode(), spillBase + offsets[i]));
        }
    }

    private void discardUnforwardedStack(InsnList insns, List<StackSlotKind> residualStackKinds,
            List<StackSlotKind> targetStackKinds) {
        if (residualStackKinds == null || residualStackKinds.isEmpty()) {
            return;
        }
        int targetSize = targetStackKinds == null ? 0 : targetStackKinds.size();
        for (int i = residualStackKinds.size() - 1; i >= targetSize; i--) {
            StackSlotKind kind = residualStackKinds.get(i);
            insns.add(new InsnNode(kind.slotSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
    }

    private List<StackSlotKind> residualStackKinds(BasicBlock block,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, List<StackSlotKind>> blockExitStacks) {
        List<StackSlotKind> exit = blockExitStacks.get(block);
        List<StackSlotKind> entry = blockEntryStacks.get(block);
        if (exit == null || exit.size() < (entry == null ? 0 : entry.size())) {
            return entry;
        }
        return exit;
    }

    private List<StackSlotKind> popTopStackKind(List<StackSlotKind> stackKinds) {
        if (stackKinds == null || stackKinds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(stackKinds.subList(0, stackKinds.size() - 1));
    }

    private void spillLocalsForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        spillLocalsForTarget(insns, target, blockEntryLocals, blockLocalSpillBases, null);
    }

    private void spillLocalsForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases,
            BitSet sourceInitializedLocals) {
        List<LocalSlotState> locals = blockEntryLocals.get(target);
        if (locals == null || locals.isEmpty()) {
            return;
        }
        Integer spillBase = blockLocalSpillBases.get(target);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            if (sourceInitializedLocals == null || sourceInitializedLocals.get(local.slot())) {
                insns.add(new VarInsnNode(local.kind().loadOpcode(), local.slot()));
            } else {
                emitDefaultValue(insns, local.kind());
            }
            insns.add(new VarInsnNode(local.kind().storeOpcode(), spillBase + offset));
            offset += local.kind().slotSize();
        }
    }

    private void emitDefaultValue(InsnList insns, StackSlotKind kind) {
        switch (kind) {
            case LONG -> insns.add(new InsnNode(Opcodes.LCONST_0));
            case FLOAT -> insns.add(new InsnNode(Opcodes.FCONST_0));
            case DOUBLE -> insns.add(new InsnNode(Opcodes.DCONST_0));
            case REFERENCE -> insns.add(new InsnNode(Opcodes.ACONST_NULL));
            case INT -> insns.add(new InsnNode(Opcodes.ICONST_0));
        }
    }

    private StackSlotKind stackSlotKind(BasicValue value) {
        if (value == BasicValue.LONG_VALUE) {
            return StackSlotKind.LONG;
        }
        if (value == BasicValue.DOUBLE_VALUE) {
            return StackSlotKind.DOUBLE;
        }
        if (value == BasicValue.FLOAT_VALUE) {
            return StackSlotKind.FLOAT;
        }
        if (value != null && value.isReference()) {
            return StackSlotKind.REFERENCE;
        }
        return StackSlotKind.INT;
    }

    private List<RemappedTryCatchRange> remapTryCatchRanges(TryCatchBlockNode tcb,
            Map<LabelNode, LabelNode> labelRemap,
            IdentityHashMap<AbstractInsnNode, Integer> originalInstructionPositions,
            IdentityHashMap<LabelNode, Integer> emittedLabelPositions,
            InsnList emittedInsns) {
        LabelNode newHandler = labelRemap.get(tcb.handler);
        if (newHandler == null || !emittedLabelPositions.containsKey(newHandler)) {
            return List.of();
        }

        Integer originalStartPos = originalInstructionPositions.get(tcb.start);
        Integer originalEndPos = originalInstructionPositions.get(tcb.end);
        if (originalStartPos == null || originalEndPos == null || originalStartPos >= originalEndPos) {
            return List.of();
        }

        Set<LabelNode> protectedLabels = Collections.newSetFromMap(new IdentityHashMap<>());

        for (AbstractInsnNode insn = tcb.start; insn != null; insn = insn.getNext()) {
            Integer pos = originalInstructionPositions.get(insn);
            if (pos == null || pos >= originalEndPos) {
                break;
            }
            if (insn instanceof LabelNode originalLabel) {
                LabelNode emittedLabel = labelRemap.get(originalLabel);
                Integer emittedPos = emittedLabel == null ? null : emittedLabelPositions.get(emittedLabel);
                if (emittedPos == null) {
                    continue;
                }
                if (emittedLabel == newHandler) {
                    continue;
                }
                protectedLabels.add(emittedLabel);
            }
        }

        if (protectedLabels.isEmpty()) {
            return List.of();
        }

        List<RemappedTryCatchRange> remappedRanges = new ArrayList<>();
        LabelNode segmentStart = null;
        LabelNode segmentLastProtected = null;
        int segmentStartPos = Integer.MIN_VALUE;
        int segmentLastProtectedPos = Integer.MIN_VALUE;

        for (AbstractInsnNode insn = emittedInsns.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LabelNode label)) {
                continue;
            }
            Integer pos = emittedLabelPositions.get(label);
            if (pos == null) {
                continue;
            }
            boolean isProtected = protectedLabels.contains(label);

            if (isProtected) {
                if (segmentStart == null) {
                    segmentStart = label;
                    segmentStartPos = pos;
                }
                segmentLastProtected = label;
                segmentLastProtectedPos = pos;
                continue;
            }

            if (segmentStart != null && pos > segmentLastProtectedPos) {
                appendRemappedTryCatchRange(remappedRanges, segmentStart, segmentStartPos, label, pos, newHandler);
                segmentStart = null;
                segmentLastProtected = null;
                segmentStartPos = Integer.MIN_VALUE;
                segmentLastProtectedPos = Integer.MIN_VALUE;
            }
        }

        if (segmentStart != null && segmentLastProtected != null) {
            LabelNode segmentEnd = nextEmittedLabelSkippingHandler(emittedInsns, segmentLastProtected, emittedLabelPositions, newHandler);
            Integer segmentEndPos = segmentEnd == null ? null : emittedLabelPositions.get(segmentEnd);
            if (segmentEndPos != null) {
                appendRemappedTryCatchRange(remappedRanges, segmentStart, segmentStartPos, segmentEnd, segmentEndPos, newHandler);
            }
        }

        return remappedRanges.isEmpty() ? List.of() : List.copyOf(remappedRanges);
    }

    private void appendRemappedTryCatchRange(List<RemappedTryCatchRange> remappedRanges,
            LabelNode start, int startPos, LabelNode end, Integer endPos, LabelNode handler) {
        if (start == null || end == null || endPos == null) {
            return;
        }
        if (start == handler || end == handler || start == end || startPos >= endPos) {
            return;
        }
        remappedRanges.add(new RemappedTryCatchRange(start, end, handler));
    }

    private LabelNode nextEmittedLabelSkippingHandler(InsnList insns, LabelNode from,
            IdentityHashMap<LabelNode, Integer> positions, LabelNode handler) {
        Integer fromPos = positions.get(from);
        if (fromPos == null) return null;
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                Integer pos = positions.get(label);
                if (pos != null && pos > fromPos && label != handler) {
                    return label;
                }
            }
        }
        return null;
    }

    private LabelNode nextEmittedLabel(InsnList insns, LabelNode from,
            IdentityHashMap<LabelNode, Integer> positions) {
        Integer fromPos = positions.get(from);
        if (fromPos == null) return null;
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                Integer pos = positions.get(label);
                if (pos != null && pos > fromPos) {
                    return label;
                }
            }
        }
        return null;
    }

    private List<CFGEdge> normalOutEdges(BasicBlock block) {
        List<CFGEdge> normalEdges = new ArrayList<>();
        for (CFGEdge edge : block.outEdges()) {
            if (edge.type() != CFGEdge.Type.EXCEPTION) {
                normalEdges.add(edge);
            }
        }
        return normalEdges;
    }

    private void emitHandlerBlock(InsnList insns, BasicBlock handlerBlock,
            Map<LabelNode, LabelNode> labelRemap, PipelineContext pctx,
            Map<BasicBlock, Long> flowKeyMap, long methodKey,
            int flowKeyVar, int flowMixVar,
            Map<BasicBlock, Integer> stateMap, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, LabelNode loopEnd,
            IdentityHashMap<AbstractInsnNode, Integer> stackHeights,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases,
            IdentityHashMap<AbstractInsnNode, AbstractInsnNode> emittedOrigins) {
        boolean requiresStateTransition = !normalOutEdges(handlerBlock).isEmpty();
        AbstractInsnNode syncAnchor = requiresStateTransition
            ? findHandlerSyncAnchor(handlerBlock, stackHeights)
            : null;
        boolean syncedFlowKey = false;
        boolean emittedRealInsn = false;
        boolean waitingForExceptionConsumption = requiresStateTransition
            && handlerBlock.isExceptionHandler()
            && syncAnchor == null;
        long handlerFlowKey = flowKeyMap.getOrDefault(handlerBlock, 0L);
        int handlerSplitHint = handlerBlock.id();
        for (AbstractInsnNode insn : handlerBlock.instructions()) {
            if (insn instanceof FrameNode) continue;
            if (insn instanceof LineNumberNode) continue;
            if (insn instanceof JumpInsnNode
                    || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode) {
                continue;
            }

            if (insn instanceof LabelNode origLabel) {
                LabelNode remapped = labelRemap.get(origLabel);
                if (remapped != null) insns.add(remapped);
                continue;
            }

            if (isTerminator(insn, handlerBlock)) continue;

            if (insn == syncAnchor && !syncedFlowKey) {
                emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
                emitRuntimeFlowContextSync(insns, flowKeyVar);
                syncedFlowKey = true;
            }

            AbstractInsnNode clone = insn.clone(labelRemap);
            insns.add(clone);
            emittedOrigins.put(clone, insn);
            recordInstructionFlowKey(pctx, clone, handlerFlowKey);
            emittedRealInsn = true;

            if (!syncedFlowKey && !waitingForExceptionConsumption) {
                emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
                emitRuntimeFlowContextSync(insns, flowKeyVar);
                syncedFlowKey = true;
            }
            // No mid-block flowKey resync after MethodInsn / InvokeDynamicInsn — flowKey is
            // updated only at block-exit transitions.
        }

        if (syncAnchor == AFTER_HANDLER_SYNC_ANCHOR && !syncedFlowKey) {
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
            syncedFlowKey = true;
            waitingForExceptionConsumption = false;
        }

        if (!syncedFlowKey && !emittedRealInsn) {
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
        } else if (!syncedFlowKey && !waitingForExceptionConsumption) {
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
        }

        emitStateTransition(insns, handlerBlock, stateMap, flowKeyMap, flowKeyVar, flowMixVar,
            encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, loopEnd,
            blockEntryStacks, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases,
            Map.of(), false);
    }

    private AbstractInsnNode findHandlerSyncAnchor(BasicBlock handlerBlock,
            IdentityHashMap<AbstractInsnNode, Integer> stackHeights) {
        if (!handlerBlock.isExceptionHandler()) {
            return null;
        }

        for (AbstractInsnNode insn : handlerBlock.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            Integer stackSize = stackHeights.get(insn);
            if (stackSize == null) {
                continue;
            }
            if (isTerminator(insn, handlerBlock)) {
                if (stackSize == 0) {
                    return AFTER_HANDLER_SYNC_ANCHOR;
                }
                continue;
            }
            if (stackSize == 0) {
                return insn;
            }
        }
        return null;
    }

    private boolean requiresFlowKeyResync(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode
            || insn instanceof InvokeDynamicInsnNode;
    }

    private String methodKey(L1Method method) {
        return method.owner().name() + '.' + method.name() + method.descriptor();
    }

    private boolean isZkmStyleEnabled(PipelineContext pctx) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        if (config == null) return true;
        Object option = config.options().get(ZKM_STYLE_OPTION);
        return !(option instanceof Boolean enabled) || enabled;
    }

    private double tailChainIntensity(PipelineContext pctx, L1Method method) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        double intensity = 0.7;
        if (config != null) {
            Object option = config.options().get(TAIL_CHAIN_INTENSITY_OPTION);
            if (option instanceof Number number) {
                intensity = Math.max(0.0, Math.min(1.0, number.doubleValue()));
            }
        }
        if (!method.tryCatchBlocks().isEmpty()) {
            double multiplier = doubleOption(config, TRY_CATCH_TAIL_CHAIN_MULTIPLIER_OPTION, 0.35);
            intensity *= multiplier;
            if (isEligibleEntryPoint(method)) {
                intensity *= doubleOption(config, ENTRYPOINT_TAIL_CHAIN_MULTIPLIER_OPTION, 0.08);
            }
        }
        return Math.max(0.0, Math.min(1.0, intensity));
    }

    private boolean shouldUseTailChain(boolean zkmStyle, double tailChainIntensity, int variantSeed) {
        if (!zkmStyle || tailChainIntensity <= 0.0) return false;
        int bucket = Math.floorMod(variantSeed * 1103515245 + 12345, 1000);
        return bucket < (int) Math.round(tailChainIntensity * 1000.0);
    }

    private int dispatcherDepth(PipelineContext pctx, int stateCount) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        int requested = intOption(config, DISPATCHER_DEPTH_OPTION, 1);
        if (requested <= 1 || stateCount < 4) return 1;

        int depth = 1;
        int cap = Math.min(8, Math.min(requested, stateCount));
        while ((depth << 1) <= cap) {
            depth <<= 1;
        }
        return depth;
    }

    private int dispatcherFragments(PipelineContext pctx, int stateCount) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        int requested = intOption(config, DISPATCHER_FRAGMENTS_OPTION, 1);
        if (requested <= 1 || stateCount < 6) return 1;
        return Math.max(1, Math.min(Math.min(8, stateCount), requested));
    }

    private DispatcherShape dispatcherShape(PipelineContext pctx, long methodKey) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        if (!booleanOption(config, DISPATCHER_SHAPE_VARIATION_OPTION, true)) {
            return new DispatcherShape(0, 0, 0);
        }
        int mode = Math.floorMod((int) (methodKey ^ (methodKey >>> 32)), 3);
        int salt = foldMethodKey(Long.rotateLeft(methodKey, 13) ^ 0x4B44535053484150L);
        int rotate = 1 + Math.floorMod((int) (methodKey >>> 23), 15);
        return new DispatcherShape(mode, salt, rotate);
    }

    private int dispatchBucket(int state, int bucketMask, DispatcherShape shape) {
        int value = switch (shape.mode()) {
            case 1 -> state ^ shape.salt();
            case 2 -> Integer.rotateRight(state ^ shape.salt(), shape.rotate());
            default -> state;
        };
        return value & bucketMask;
    }

    private void emitDispatchBucketValue(InsnList insns, int dispatchStateVar, int bucketMask,
            DispatcherShape shape) {
        if (shape.mode() == 1) {
            insns.add(AsmUtil.pushIntAny(shape.salt()));
            insns.add(new InsnNode(Opcodes.IXOR));
        } else if (shape.mode() == 2) {
            insns.add(AsmUtil.pushIntAny(shape.salt()));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(AsmUtil.pushIntAny(shape.rotate()));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
                "rotateRight", "(II)I", false));
        }
        insns.add(AsmUtil.pushIntAny(bucketMask));
        insns.add(new InsnNode(Opcodes.IAND));
    }

    private boolean isStructureSafe(L1Method method, PipelineContext pctx) {
        MethodSafetyStats stats = analyzeMethodStructure(method.instructions());
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        boolean hasTryCatch = !method.tryCatchBlocks().isEmpty();
        boolean isEntryPoint = isEligibleEntryPoint(method);

        if (hasTryCatch && !booleanOption(config, ALLOW_TRY_CATCH_METHODS_OPTION, true)) return false;
        if (hasTryCatch && callsGeneratedHelper(method)) return false;
        if (hasTryCatch && booleanOption(config, TRY_CATCH_MAIN_ONLY_OPTION, false) && !isEntryPoint) {
            return false;
        }

        int maxTryCatchBlocks = intOption(config, MAX_TRY_CATCH_BLOCKS_OPTION, 18);
        if (isEntryPoint) {
            maxTryCatchBlocks = Math.max(maxTryCatchBlocks,
                intOption(config, ENTRYPOINT_MAX_TRY_CATCH_BLOCKS_OPTION, 64));
        }
        if (method.tryCatchBlocks().size() > maxTryCatchBlocks) return false;
        if (hasReferenceLocalFrameConflicts(method)) return false;

        if (!isEntryPoint && stats.hasSwitch() && !booleanOption(config, ALLOW_SWITCH_METHODS_OPTION, false)) return false;
        if (!isEntryPoint && stats.hasMonitor() && !booleanOption(config, ALLOW_MONITOR_METHODS_OPTION, false)) return false;

        int maxInstructionCount = intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 180);
        if (hasTryCatch) {
            maxInstructionCount += intOption(config, TRY_CATCH_INSTRUCTION_BONUS_OPTION, 160);
        }
        if (isEntryPoint) {
            maxInstructionCount += intOption(config, ENTRYPOINT_INSTRUCTION_BONUS_OPTION, 0);
        }
        if (method.instructionCount() > maxInstructionCount) return false;

        int maxBackward = intOption(config, MAX_BACKWARD_BRANCHES_OPTION, 2);
        if (isEntryPoint) {
            maxBackward = Math.max(maxBackward, 8);
        }
        if (stats.backwardBranches() > maxBackward) return false;

        int maxBranchCount = intOption(config, MAX_BRANCHES_OPTION, 16);
        if (hasTryCatch) {
            int bonusPerTryCatch = intOption(config, TRY_CATCH_BRANCH_BONUS_OPTION, 2);
            maxBranchCount += method.tryCatchBlocks().size() * bonusPerTryCatch;
        }
        if (isEntryPoint) {
            maxBranchCount += intOption(config, ENTRYPOINT_BRANCH_BONUS_OPTION, 0);
        }
        return stats.branchCount() <= maxBranchCount;
    }

    private boolean callsGeneratedHelper(L1Method method) {
        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.name.startsWith("__neko_")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldUseLoopFastPath(PipelineContext pctx, L1Method method, MethodSafetyStats stats) {
        if (isEligibleEntryPoint(method)) return false;
        if (stats.backwardBranches() <= 0) return false;
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        int insnThreshold = intOption(config, LOOP_FAST_PATH_INSTRUCTION_THRESHOLD_OPTION, 220);
        int backwardThreshold = intOption(config, LOOP_FAST_PATH_BACKWARD_BRANCH_THRESHOLD_OPTION, 2);
        return method.instructionCount() >= insnThreshold || stats.backwardBranches() >= backwardThreshold;
    }

    private void insertLoopFastPathGate(PipelineContext pctx, L1Method method) {
        LabelNode body = new LabelNode();
        int state = pctx.random().nextInt();
        InsnList gate = new InsnList();
        gate.add(AsmUtil.pushIntAny(state));
        gate.add(new LookupSwitchInsnNode(body, new int[] { state }, new LabelNode[] { body }));
        gate.add(body);
        method.instructions().insert(gate);
    }

    private boolean insertConstructorFlowGate(PipelineContext pctx, L1Method method) {
        AbstractInsnNode anchor = firstConstructorInitCall(method);
        if (anchor == null || anchor.getNext() == null) return false;

        MethodNode mn = method.asmNode();
        int keyLocal = mn.maxLocals;
        int stateLocal = keyLocal + 2;
        mn.maxLocals = stateLocal + 1;

        long seedKey = pctx.random().nextLong();
        long caseKey = seedKey ^ pctx.random().nextLong();
        int decodedState = pctx.random().nextInt();
        int encodedState = decodedState ^ foldFlowKey(seedKey);
        int falseState = decodedState ^ foldMethodKey(caseKey);
        long caseSalt = pctx.random().nextLong();
        long defaultSalt = pctx.random().nextLong();

        LabelNode defaultLabel = new LabelNode();
        LabelNode caseLabel = new LabelNode();
        LabelNode body = new LabelNode();
        InsnList gate = new InsnList();
        gate.add(new LdcInsnNode(seedKey));
        gate.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        gate.add(AsmUtil.pushIntAny(encodedState));
        gate.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));

        gate.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        gate.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        gate.add(new InsnNode(Opcodes.L2I));
        gate.add(new InsnNode(Opcodes.IXOR));
        gate.add(new LookupSwitchInsnNode(defaultLabel, new int[] { decodedState }, new LabelNode[] { caseLabel }));

        gate.add(defaultLabel);
        gate.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        gate.add(new LdcInsnNode(defaultSalt));
        gate.add(new InsnNode(Opcodes.LXOR));
        gate.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        gate.add(AsmUtil.pushIntAny(falseState));
        gate.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
        gate.add(new JumpInsnNode(Opcodes.GOTO, body));

        gate.add(caseLabel);
        gate.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        gate.add(new LdcInsnNode(caseSalt));
        gate.add(new InsnNode(Opcodes.LXOR));
        gate.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        gate.add(body);

        method.instructions().insert(anchor, gate);
        mn.maxStack = Math.max(mn.maxStack, 6);
        return true;
    }

    private AbstractInsnNode firstConstructorInitCall(L1Method method) {
        if (!method.isConstructor()) return null;
        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && mi.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(mi.name)) {
                return insn;
            }
        }
        return null;
    }

    private boolean hasReferenceLocalFrameConflicts(L1Method method) {
        Map<Integer, String> seen = new HashMap<>();

        int slot = 0;
        if (!method.isStatic()) {
            seen.put(0, method.owner().name());
            slot = 1;
        }
        for (Type argumentType : method.argumentTypes()) {
            if (argumentType.getSort() == Type.OBJECT || argumentType.getSort() == Type.ARRAY) {
                seen.put(slot, localTypeName(argumentType));
            }
            slot += argumentType.getSize();
        }

        for (LocalVariableNode localVariable : method.localVariables()) {
            if (localVariable.index < 0) continue;
            Type type = Type.getType(localVariable.desc);
            if ((type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)
                    && recordReferenceLocalType(seen, localVariable.index, localTypeName(type))) {
                return true;
            }
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FrameNode frame) || frame.local == null) {
                continue;
            }
            int frameSlot = 0;
            for (Object local : frame.local) {
                if (local instanceof String typeName) {
                    if (recordReferenceLocalType(seen, frameSlot, typeName)) {
                        return true;
                    }
                    frameSlot++;
                    continue;
                }
                if (local instanceof LabelNode) {
                    return true;
                }
                if (local == Opcodes.LONG || local == Opcodes.DOUBLE) {
                    frameSlot += 2;
                } else {
                    frameSlot++;
                }
            }
        }
        return false;
    }

    private boolean recordReferenceLocalType(Map<Integer, String> seen, int slot, String typeName) {
        if (typeName == null || "null".equals(typeName)) {
            return false;
        }
        String previous = seen.putIfAbsent(slot, typeName);
        return previous != null && !previous.equals(typeName);
    }

    private String localTypeName(Type type) {
        return type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
    }

    private MethodSafetyStats analyzeMethodStructure(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        int branchCount = 0;
        int backwardBranches = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                branchCount++;
                continue;
            }
            if (insn instanceof JumpInsnNode jump) {
                branchCount++;
                Integer from = positions.get(insn);
                Integer target = positions.get(jump.label);
                if (from != null && target != null && target <= from) {
                    backwardBranches++;
                }
            }
        }

        return new MethodSafetyStats(branchCount, backwardBranches, hasSwitch, hasMonitor);
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean enabled ? enabled : defaultValue;
    }

    private int intOption(TransformConfig config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(0, number.intValue()) : defaultValue;
    }

    private double doubleOption(TransformConfig config, String key, double defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(0.0, number.doubleValue()) : defaultValue;
    }

    private boolean isEligibleEntryPoint(L1Method method) {
        return method.isStatic()
            && "main".equals(method.name())
            && "([Ljava/lang/String;)V".equals(method.descriptor());
    }

    private long deriveMethodFlowSeed(long methodKey) {
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodKey ^ 0x4E454B4F464C4F57L,
                0x13579BDF2468ACE0L));
    }

    private long deriveBlockFlowKey(long methodFlowSeed, int state) {
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodFlowSeed, state));
    }

    private void applySinglePredecessorEdgeKeys(List<BasicBlock> blocks, BasicBlock entryBlock,
            Map<BasicBlock, Long> flowKeyMap, long methodFlowSeed) {
        for (BasicBlock block : blocks) {
            if (block == entryBlock) continue;
            List<CFGEdge> incoming = normalInEdges(block);
            if (incoming.size() != 1) continue;
            CFGEdge edge = incoming.get(0);
            Long sourceKey = flowKeyMap.get(edge.source());
            if (sourceKey == null) continue;
            flowKeyMap.put(block, deriveEdgeFlowKey(sourceKey, edge, methodFlowSeed));
        }
    }

    private List<CFGEdge> normalInEdges(BasicBlock block) {
        List<CFGEdge> normalEdges = new ArrayList<>();
        for (CFGEdge edge : block.inEdges()) {
            if (edge.type() != CFGEdge.Type.EXCEPTION) {
                normalEdges.add(edge);
            }
        }
        return normalEdges;
    }

    private long deriveEdgeFlowKey(long sourceKey, CFGEdge edge, long methodFlowSeed) {
        long key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(sourceKey, edge.source().id());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key, edge.target().id());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key, edge.type().ordinal());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key, edge.switchKey());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key,
            methodFlowSeed ^ ((long) edge.source().id() << 32) ^ edge.target().id());
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(key);
    }

    private int foldFlowKey(long flowKey) {
        return foldMethodKey(flowKey ^ Long.rotateLeft(flowKey, 17));
    }

    private int foldMethodKey(long value) {
        int mixed = (int) (value ^ (value >>> 32));
        mixed ^= Integer.rotateLeft(mixed, 13);
        mixed ^= Integer.rotateRight(mixed, 7);
        return mixed != 0 ? mixed : 0x13579BDF;
    }

    private enum LocalInitKind {
        UNKNOWN,
        INT,
        FLOAT,
        LONG,
        DOUBLE,
        REFERENCE,
        RESERVED,
        CONFLICT
    }

    private enum StackSlotKind {
        INT(Opcodes.ILOAD, Opcodes.ISTORE, 1),
        FLOAT(Opcodes.FLOAD, Opcodes.FSTORE, 1),
        LONG(Opcodes.LLOAD, Opcodes.LSTORE, 2),
        DOUBLE(Opcodes.DLOAD, Opcodes.DSTORE, 2),
        REFERENCE(Opcodes.ALOAD, Opcodes.ASTORE, 1);

        private final int loadOpcode;
        private final int storeOpcode;
        private final int slotSize;

        StackSlotKind(int loadOpcode, int storeOpcode, int slotSize) {
            this.loadOpcode = loadOpcode;
            this.storeOpcode = storeOpcode;
            this.slotSize = slotSize;
        }

        int loadOpcode() {
            return loadOpcode;
        }

        int storeOpcode() {
            return storeOpcode;
        }

        int slotSize() {
            return slotSize;
        }
    }

    private record LocalFlowSummary(BitSet uses, BitSet defs) {}

    private record LocalSlotState(int slot, StackSlotKind kind, String referenceType) {
        LocalSlotState(int slot, StackSlotKind kind) { this(slot, kind, null); }
    }

    private record MethodSafetyStats(int branchCount, int backwardBranches, boolean hasSwitch, boolean hasMonitor) {}

    private record TailChain(LabelNode entry, InsnList body) {}

    private record DispatcherShape(int mode, int salt, int rotate) {}

    private record RemappedTryCatchRange(LabelNode start, LabelNode end, LabelNode handler) {}

    private record ExceptionHandlerStub(LabelNode label, BasicBlock handlerBlock) {}

    /**
     * BasicValue that also tracks the reference type (internal name or array
     * descriptor) so we can emit CHECKCAST after CFF spill/restore. Falls back
     * to plain BasicValue.REFERENCE_VALUE behavior for non-reference values.
     */
    private static final class RefTypedValue extends BasicValue {
        final String referenceType;

        RefTypedValue(Type type, String referenceType) {
            super(type);
            this.referenceType = referenceType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RefTypedValue other)) return false;
            return java.util.Objects.equals(getType(), other.getType())
                && java.util.Objects.equals(referenceType, other.referenceType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(getType(), referenceType);
        }
    }

    /**
     * Custom interpreter that tracks reference types beyond BasicInterpreter's
     * single-Object value. Uses the project's ClassHierarchy for LCM merges
     * when paths produce different reference types.
     */
    private static final class RefTypedInterpreter extends BasicInterpreter {
        private final ClassHierarchy hierarchy;

        RefTypedInterpreter(ClassHierarchy hierarchy) {
            super(Opcodes.ASM9);
            this.hierarchy = hierarchy;
        }

        private static BasicValue typedRef(Type type, String name) {
            return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), name);
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type == null) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            switch (type.getSort()) {
                case Type.VOID:
                    return null;
                case Type.OBJECT:
                    return typedRef(type, type.getInternalName());
                case Type.ARRAY:
                    return typedRef(type, type.getDescriptor());
                default:
                    return super.newValue(type);
            }
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL:
                    return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), null);
                case Opcodes.LDC:
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof String) {
                        return typedRef(Type.getObjectType("java/lang/String"), "java/lang/String");
                    }
                    if (cst instanceof Type t) {
                        if (t.getSort() == Type.METHOD) {
                            return typedRef(Type.getObjectType("java/lang/invoke/MethodType"),
                                "java/lang/invoke/MethodType");
                        }
                        return typedRef(Type.getObjectType("java/lang/Class"), "java/lang/Class");
                    }
                    if (cst instanceof Handle) {
                        return typedRef(Type.getObjectType("java/lang/invoke/MethodHandle"),
                            "java/lang/invoke/MethodHandle");
                    }
                    return super.newOperation(insn);
                case Opcodes.NEW: {
                    String desc = ((TypeInsnNode) insn).desc;
                    return typedRef(Type.getObjectType(desc), desc);
                }
                default:
                    return super.newOperation(insn);
            }
        }

        @Override
        public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            return value;
        }

        @Override
        public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.CHECKCAST: {
                    String desc = ((TypeInsnNode) insn).desc;
                    if (desc.startsWith("[")) {
                        return typedRef(Type.getType(desc), desc);
                    }
                    return typedRef(Type.getObjectType(desc), desc);
                }
                case Opcodes.GETFIELD: {
                    Type ft = Type.getType(((FieldInsnNode) insn).desc);
                    if (ft.getSort() == Type.OBJECT) {
                        return typedRef(ft, ft.getInternalName());
                    }
                    if (ft.getSort() == Type.ARRAY) {
                        return typedRef(ft, ft.getDescriptor());
                    }
                    return super.unaryOperation(insn, value);
                }
                case Opcodes.ANEWARRAY: {
                    String elem = ((TypeInsnNode) insn).desc;
                    String arrayDesc = elem.startsWith("[")
                        ? "[" + elem
                        : "[L" + elem + ";";
                    return typedRef(Type.getType(arrayDesc), arrayDesc);
                }
                case Opcodes.NEWARRAY: {
                    int operand = ((IntInsnNode) insn).operand;
                    String desc = switch (operand) {
                        case Opcodes.T_BOOLEAN -> "[Z";
                        case Opcodes.T_CHAR -> "[C";
                        case Opcodes.T_FLOAT -> "[F";
                        case Opcodes.T_DOUBLE -> "[D";
                        case Opcodes.T_BYTE -> "[B";
                        case Opcodes.T_SHORT -> "[S";
                        case Opcodes.T_INT -> "[I";
                        case Opcodes.T_LONG -> "[J";
                        default -> null;
                    };
                    if (desc != null) {
                        return typedRef(Type.getType(desc), desc);
                    }
                    return super.unaryOperation(insn, value);
                }
                default:
                    return super.unaryOperation(insn, value);
            }
        }

        @Override
        public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue value1, BasicValue value2)
                throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.AALOAD) {
                if (value1 instanceof RefTypedValue arr && arr.referenceType != null
                        && arr.referenceType.startsWith("[")) {
                    String elem = arr.referenceType.substring(1);
                    if (elem.startsWith("L") && elem.endsWith(";")) {
                        String name = elem.substring(1, elem.length() - 1);
                        return typedRef(Type.getObjectType(name), name);
                    }
                    if (elem.startsWith("[")) {
                        return typedRef(Type.getType(elem), elem);
                    }
                }
                return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), "java/lang/Object");
            }
            return super.binaryOperation(insn, value1, value2);
        }

        @Override
        public BasicValue naryOperation(AbstractInsnNode insn, List<? extends BasicValue> values)
                throws AnalyzerException {
            if (insn instanceof MethodInsnNode mi) {
                Type rt = Type.getReturnType(mi.desc);
                if (rt.getSort() == Type.OBJECT) {
                    return typedRef(rt, rt.getInternalName());
                }
                if (rt.getSort() == Type.ARRAY) {
                    return typedRef(rt, rt.getDescriptor());
                }
            } else if (insn instanceof InvokeDynamicInsnNode id) {
                Type rt = Type.getReturnType(id.desc);
                if (rt.getSort() == Type.OBJECT) {
                    return typedRef(rt, rt.getInternalName());
                }
                if (rt.getSort() == Type.ARRAY) {
                    return typedRef(rt, rt.getDescriptor());
                }
            } else if (insn.getOpcode() == Opcodes.MULTIANEWARRAY) {
                String desc = ((MultiANewArrayInsnNode) insn).desc;
                return typedRef(Type.getType(desc), desc);
            }
            return super.naryOperation(insn, values);
        }

        @Override
        public BasicValue merge(BasicValue v1, BasicValue v2) {
            if (v1 == v2 || v1.equals(v2)) {
                return v1;
            }
            if (v1 instanceof RefTypedValue r1 && v2 instanceof RefTypedValue r2
                    && java.util.Objects.equals(r1.getType(), r2.getType())) {
                String t1 = r1.referenceType;
                String t2 = r2.referenceType;
                if (t1 == null) return r2;
                if (t2 == null) return r1;
                if (t1.equals(t2)) return r1;
                String lcm;
                if (t1.startsWith("[") || t2.startsWith("[")) {
                    lcm = t1.equals(t2) ? t1 : "java/lang/Object";
                } else {
                    lcm = hierarchy != null ? hierarchy.getCommonSuperClass(t1, t2) : "java/lang/Object";
                    if (lcm == null) {
                        lcm = "java/lang/Object";
                    }
                }
                return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), lcm);
            }
            return super.merge(v1, v2);
        }
    }

    private AbstractInsnNode findLastRealInsn(BasicBlock block) {
        for (int i = block.instructions().size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private void emitSafetyReturn(InsnList insns, org.objectweb.asm.Type retType) {
        switch (retType.getSort()) {
            case org.objectweb.asm.Type.VOID -> insns.add(new InsnNode(Opcodes.RETURN));
            case org.objectweb.asm.Type.INT, org.objectweb.asm.Type.BOOLEAN,
                 org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
                 org.objectweb.asm.Type.SHORT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new InsnNode(Opcodes.IRETURN));
            }
            case org.objectweb.asm.Type.LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new InsnNode(Opcodes.LRETURN));
            }
            case org.objectweb.asm.Type.FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new InsnNode(Opcodes.FRETURN));
            }
            case org.objectweb.asm.Type.DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }
}
