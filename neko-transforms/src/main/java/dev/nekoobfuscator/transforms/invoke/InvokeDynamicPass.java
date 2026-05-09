package dev.nekoobfuscator.transforms.invoke;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.data.NumberEncryptionPass;
import dev.nekoobfuscator.transforms.flow.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import dev.nekoobfuscator.transforms.key.KeyDispatcherSupport;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * InvokeDynamic Obfuscation: wraps method calls in invokedynamic instructions.
 * The target metadata is encrypted and recovered in the bootstrap method.
 */
public final class InvokeDynamicPass implements TransformPass {

    public static final String HARDEN_GENERATED_HELPERS_KEY = "invokeDynamic.hardenGeneratedHelpers";
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String FLOW_KEY_LOCAL_BY_METHOD_KEY = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String EXCLUDED_INVOKE_INSNS_KEY = "invokeDynamic.excludedInvokeInsns";
    private static final String SKIP_TRY_CATCH_METHODS_OPTION = "skipMethodsWithTryCatch";
    private static final String SKIP_SWITCH_METHODS_OPTION = "skipMethodsWithSwitches";
    private static final String SKIP_MONITOR_METHODS_OPTION = "skipMethodsWithMonitors";
    private static final String SKIP_SENSITIVE_API_METHODS_OPTION = "skipSensitiveApiMethods";
    private static final String SKIP_PRIMITIVE_LOOP_CALLS_OPTION = "skipPrimitiveLoopCalls";
    private static final String USE_CONTROL_FLOW_KEY_OPTION = "useControlFlowKey";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BRANCH_COUNT_OPTION = "maxBranchCount";
    private static final String WRAP_SPECIAL_CALLS_OPTION = "wrapSpecialCalls";
    private static final String BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIIIII)Ljava/lang/invoke/CallSite;";
    private static final String LOCAL_BOOTSTRAP_PREFIX = "__neko_b";
    private static final String KEY_OWNER = "dev/nekoobfuscator/runtime/NekoKeyDerivation";
    private static final String STRING_DECRYPT_OWNER = "dev/nekoobfuscator/runtime/NekoStringDecryptor";
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final long INVOKE_FLOW_KEY_DOMAIN = 0x4E454B4F494E4459L;

    @Override public String id() { return "invokeDynamic"; }
    @Override public String name() { return "InvokeDynamic Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() {
        return Set.of("controlFlowFlattening", "stringEncryption", "numberEncryption");
    }

    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;
    private int targetCounter;
    private final Map<String, BootstrapProfile> bootstrapProfiles = new HashMap<>();

    public static void excludeGeneratedInvokeInsns(PipelineContext pctx, InsnList insns) {
        Set<AbstractInsnNode> excluded = excludedInvokeInsns(pctx);
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            excluded.add(insn);
        }
    }

    public static void excludeGeneratedInvokeInsn(PipelineContext pctx, AbstractInsnNode insn) {
        excludedInvokeInsns(pctx).add(insn);
    }

    public static boolean isExcludedGeneratedInvokeInsn(PipelineContext pctx, AbstractInsnNode insn) {
        Set<AbstractInsnNode> excluded = pctx.getPassData(EXCLUDED_INVOKE_INSNS_KEY);
        return excluded != null && excluded.contains(insn);
    }

    private static Set<AbstractInsnNode> excludedInvokeInsns(PipelineContext pctx) {
        Set<AbstractInsnNode> excluded = pctx.getPassData(EXCLUDED_INVOKE_INSNS_KEY);
        if (excluded == null) {
            excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            pctx.putPassData(EXCLUDED_INVOKE_INSNS_KEY, excluded);
        }
        return excluded;
    }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
        classKey = keyEngine.deriveClassKey(pctx.currentL1Class());
        targetCounter = 0;
        bootstrapProfiles.clear();
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        IdentityHashMap<AbstractInsnNode, Long> flowKeyValues = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        Map<String, Integer> flowKeyLocalByMethod = pctx.getPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY);
        TransformConfig config = pctx.config().transforms().get("invokeDynamic");
        if (!method.hasCode()) return;
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)
                || (TransformGuards.isGeneratedMethod(method) && !isGeneratedInvokeTarget(method, hardenGeneratedHelpers))) {
            JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                "guarded-runtime-or-generated");
            return;
        }
        if (TransformGuards.isReflectionShapeSensitive(pctx, clazz)
                || TransformGuards.hasStackIntrospection(method)) {
            insertSafeInvokeGate(pctx, clazz, method, "reflection-or-stack-observer-safe-tier");
            return;
        }
        MethodRiskStats stats = analyzeMethod(method.instructions());
        double intensity = pctx.config().getTransformIntensity("invokeDynamic");
        InsnList insns = method.instructions();
        int methodNameHash = method.name().hashCode();
        int methodDescHash = method.descriptor().hashCode();
        long methodKey = DynamicKeyDerivationEngine.mix(
            DynamicKeyDerivationEngine.mix(classKey, methodNameHash), methodDescHash);

        List<MethodInsnNode> targets = new ArrayList<>();
        int safeTierCalls = 0;
        boolean constructorReady = !method.isConstructor();
        boolean wrapSpecialCalls = booleanOption(config, WRAP_SPECIAL_CALLS_OPTION, true);
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                if (isExcludedGeneratedInvoke(pctx, mi)) {
                    continue;
                }
                if (method.isConstructor() && !constructorReady) {
                    if (mi.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(mi.name)) {
                        constructorReady = true;
                    }
                    continue;
                }
                int op = mi.getOpcode();
                if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEINTERFACE
                        || (wrapSpecialCalls && op == Opcodes.INVOKESPECIAL)) {
                    if (!"<init>".equals(mi.name) && !"<clinit>".equals(mi.name) && !mi.owner.startsWith("[")
                            && !TransformGuards.isSupportCall(mi)) {
                        if (isSensitiveApiCall(mi)) {
                            safeTierCalls++;
                            continue;
                        }
                        if (!shouldSkipCall(method, mi, stats, pctx.config().transforms().get("invokeDynamic"))
                                && pctx.random().nextDouble() <= intensity) {
                            targets.add(mi);
                        }
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            if (safeTierCalls > 0) {
                insertSafeInvokeGate(pctx, clazz, method, "sensitive-call-safe-tier");
            } else {
                JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                    "no-legal-method-calls");
            }
            return;
        }
        boolean configuredFlowKey = booleanOption(config, USE_CONTROL_FLOW_KEY_OPTION, false)
            && requireFullControlFlowKey(pctx);
        KeyDispatcherSupport.Profile keyDispatcher = !configuredFlowKey && KeyDispatcherSupport.enabled(config)
            ? KeyDispatcherSupport.getOrCreate(pctx, clazz, config, "invoke", methodNameHash)
            : null;
        BootstrapProfile bootstrapProfile = getOrCreateBootstrap(clazz, pctx, keyDispatcher);
        String methodKeyId = clazz.name() + "." + method.name() + method.descriptor();
        Integer flowKeyLocalSlot = flowKeyLocalByMethod == null
            ? null : flowKeyLocalByMethod.get(methodKeyId);

        MethodNode mn = method.asmNode();
        for (MethodInsnNode mi : targets) {
            int targetId = targetCounter++;
            int siteSalt = pctx.random().nextInt();
            Long exactFlowKey = flowKeyValues == null ? null : flowKeyValues.get(mi);
            Long siteFlowKey = configuredFlowKey ? exactFlowKey : resolveCffFlowKey(insns, mi, flowKeyValues);
            boolean useFlowKey = booleanOption(config, USE_CONTROL_FLOW_KEY_OPTION, false)
                && siteFlowKey != null
                && flowKeyLocalSlot != null;
            if (configuredFlowKey && !useFlowKey) {
                failClosed(pctx, clazz, method, flowKeyLocalSlot == null
                    ? "invoke-method-missing-cff-flow-key-local"
                    : "invoke-site-missing-cff-flow-key:"
                        + mi.owner + "." + mi.name + mi.desc + ":op=" + mi.getOpcode());
            }
            long flowKey = useFlowKey ? siteFlowKey : 0L;

            long siteBaseKey = deriveSiteBaseKey(methodKey, siteSalt, targetId, mi.getOpcode(), flowKey, useFlowKey);
            int keyComponent = pctx.random().nextInt();
            long effectiveSiteBaseKey = keyDispatcher != null
                ? KeyDispatcherSupport.dispatch(keyDispatcher, siteBaseKey, keyComponent)
                : siteBaseKey;
            String encryptedOwner = encryptMetadataString(mi.owner, deriveMetadataKey(effectiveSiteBaseKey, 1));
            String encryptedName = encryptMetadataString(mi.name, deriveMetadataKey(effectiveSiteBaseKey, 2));
            String encryptedDesc = encryptMetadataString(mi.desc, deriveMetadataKey(effectiveSiteBaseKey, 3));

            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                opaqueCallsiteName(pctx),
                invokedynamicDescriptor(mi),
                new Handle(Opcodes.H_INVOKESTATIC, clazz.name(), bootstrapProfile.methodName(),
                    BOOTSTRAP_DESC, (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0),
                encryptedOwner,
                encryptedName,
                encryptedDesc,
                methodNameHash,
                methodDescHash,
                siteSalt,
                mi.getOpcode(),
                targetId,
                useFlowKey ? 1 : 0,
                keyDispatcher != null ? 1 : 0,
                keyComponent
            );
            if (useFlowKey) {
                InsnList sync = new InsnList();
                long mask = pctx.random().nextLong();
                sync.add(new LdcInsnNode(siteFlowKey ^ mask));
                sync.add(new LdcInsnNode(mask));
                sync.add(new InsnNode(Opcodes.LXOR));
                sync.add(new InsnNode(Opcodes.DUP2));
                sync.add(new VarInsnNode(Opcodes.LSTORE, flowKeyLocalSlot));
                sync.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER,
                    "setCurrentFlowKey", "(J)V", false));
                insns.insertBefore(mi, sync);
            }
            insns.set(mi, indy);
        }

        pctx.currentL1Class().markDirty();
        JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(),
            "local-bootstrap-invokedynamic-sites=" + targets.size());
    }

    private Long resolveCffFlowKey(InsnList insns, AbstractInsnNode site,
            IdentityHashMap<AbstractInsnNode, Long> flowKeyValues) {
        if (flowKeyValues == null || site == null) return null;
        Long exact = flowKeyValues.get(site);
        if (exact != null) return exact;

        Long before = null;
        int beforeDistance = Integer.MAX_VALUE;
        int distance = 0;
        for (AbstractInsnNode cursor = site.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
            distance++;
            Long flowKey = flowKeyValues.get(cursor);
            if (flowKey != null) {
                before = flowKey;
                beforeDistance = distance;
                break;
            }
            if (isFlowKeyInferenceBoundary(cursor)) break;
        }

        Long after = null;
        int afterDistance = Integer.MAX_VALUE;
        distance = 0;
        for (AbstractInsnNode cursor = site.getNext(); cursor != null; cursor = cursor.getNext()) {
            distance++;
            Long flowKey = flowKeyValues.get(cursor);
            if (flowKey != null) {
                after = flowKey;
                afterDistance = distance;
                break;
            }
            if (isFlowKeyInferenceBoundary(cursor)) break;
        }

        if (before == null) return after;
        if (after == null) return before;
        return beforeDistance <= afterDistance ? before : after;
    }

    private boolean isFlowKeyInferenceBoundary(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode == Opcodes.GOTO
            || opcode == Opcodes.TABLESWITCH
            || opcode == Opcodes.LOOKUPSWITCH
            || opcode == Opcodes.RET
            || opcode == Opcodes.ATHROW
            || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN);
    }

    private String invokedynamicDescriptor(MethodInsnNode mi) {
        if (mi.getOpcode() == Opcodes.INVOKESTATIC) {
            return mi.desc;
        }
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type[] indyArgs = new Type[args.length + 1];
        indyArgs[0] = Type.getObjectType(mi.owner);
        System.arraycopy(args, 0, indyArgs, 1, args.length);
        return Type.getMethodDescriptor(Type.getReturnType(mi.desc), indyArgs);
    }

    private boolean isExcludedGeneratedInvoke(PipelineContext pctx, AbstractInsnNode insn) {
        Set<AbstractInsnNode> excluded = pctx.getPassData(EXCLUDED_INVOKE_INSNS_KEY);
        return excluded != null && excluded.contains(insn);
    }

    private void insertSafeInvokeGate(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        LabelNode body = new LabelNode();
        LabelNode dflt = new LabelNode();
        int state = pctx.random().nextInt();
        int mask = pctx.random().nextInt() | 1;
        InsnList gate = new InsnList();
        gate.add(pushInt(state ^ mask));
        gate.add(pushInt(mask));
        gate.add(new InsnNode(Opcodes.IXOR));
        gate.add(new LookupSwitchInsnNode(dflt, new int[] { state }, new LabelNode[] { body }));
        gate.add(dflt);
        gate.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        gate.add(new InsnNode(Opcodes.DUP));
        gate.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        gate.add(new InsnNode(Opcodes.ATHROW));
        gate.add(body);
        if (method.isConstructor()) {
            AbstractInsnNode anchor = firstConstructorInitCall(method);
            if (anchor == null) {
                JvmObfuscationCoverage.get(pctx).failClosed(id(), clazz.name(), method.name(), method.descriptor(),
                    "constructor-safe-invoke-gate-no-init-anchor");
                throw new IllegalStateException("Cannot place invokeDynamic safe gate in "
                    + clazz.name() + "." + method.name() + method.descriptor());
            }
            method.instructions().insert(anchor, gate);
        } else {
            method.instructions().insert(gate);
        }
        method.asmNode().maxStack = Math.max(method.asmNode().maxStack, 4);
        clazz.markDirty();
        JvmObfuscationCoverage.get(pctx).safe(id(), clazz.name(), method.name(), method.descriptor(), reason);
        pctx.invalidate(method);
    }

    private void failClosed(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).failClosed(id(), clazz.name(), method.name(), method.descriptor(), reason);
        throw new IllegalStateException("InvokeDynamic obfuscation failed closed for "
            + clazz.name() + "." + method.name() + method.descriptor() + ": " + reason);
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

    private boolean isMethodEligible(PipelineContext pctx, L1Method method, MethodRiskStats stats) {
        TransformConfig config = pctx.config().transforms().get("invokeDynamic");
        if (!method.tryCatchBlocks().isEmpty() && booleanOption(config, SKIP_TRY_CATCH_METHODS_OPTION, false)) {
            return false;
        }
        if (stats.hasSwitch() && booleanOption(config, SKIP_SWITCH_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasMonitor() && booleanOption(config, SKIP_MONITOR_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasSensitiveApi() && booleanOption(config, SKIP_SENSITIVE_API_METHODS_OPTION, true)) {
            return false;
        }
        if (method.instructionCount() > intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 260)) {
            return false;
        }
        return stats.branchCount() <= intOption(config, MAX_BRANCH_COUNT_OPTION, 24);
    }

    private boolean shouldSkipCall(L1Method method, MethodInsnNode mi, MethodRiskStats stats, TransformConfig config) {
        if (mi.owner.startsWith("dev/nekoobfuscator/runtime/")) {
            return true;
        }
        if (mi.name.startsWith("__neko_")) {
            return true;
        }
        if (stats.backwardBranches() > 0 && booleanOption(config, SKIP_PRIMITIVE_LOOP_CALLS_OPTION, false)
                && hasPrimitiveSignature(mi.desc)) {
            return false;
        }
        return false;
    }

    private boolean requireFullControlFlowKey(PipelineContext pctx) {
        if (!pctx.config().isTransformEnabled("controlFlowFlattening")) return false;
        TransformConfig cff = pctx.config().transforms().get("controlFlowFlattening");
        return booleanOption(cff, "requireFullStateMachine", false)
            || (!booleanOption(cff, "allowSafeFallbacks", false)
                && booleanOption(cff, "strictCoverage", false));
    }

    private boolean isGeneratedInvokeTarget(L1Method method, boolean hardenGeneratedHelpers) {
        return hardenGeneratedHelpers && method.name().startsWith("__neko_o");
    }

    private MethodRiskStats analyzeMethod(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        int branchCount = 0;
        int backwardBranches = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;
        boolean hasSensitiveApi = false;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                branchCount++;
                Integer from = positions.get(insn);
                Integer target = positions.get(jump.label);
                if (from != null && target != null && target <= from) {
                    backwardBranches++;
                }
                continue;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                continue;
            }
            if (insn instanceof MethodInsnNode mi && isSensitiveApiCall(mi)) {
                hasSensitiveApi = true;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
        }
        return new MethodRiskStats(branchCount, backwardBranches, hasSwitch, hasMonitor, hasSensitiveApi);
    }

    private boolean hasPrimitiveSignature(String descriptor) {
        for (Type argType : Type.getArgumentTypes(descriptor)) {
            if (isPrimitive(argType)) return true;
        }
        return isPrimitive(Type.getReturnType(descriptor));
    }

    private boolean isPrimitive(Type type) {
        int sort = type.getSort();
        return sort != Type.OBJECT && sort != Type.ARRAY && sort != Type.METHOD && sort != Type.VOID;
    }

    private boolean isHotPrimitiveOwner(String methodOwner, String targetOwner) {
        return false;
    }

    private boolean isSensitiveApiCall(MethodInsnNode mi) {
        String owner = mi.owner;
        if (owner.startsWith("java/lang/reflect/")) return true;
        if (owner.startsWith("java/lang/annotation/")) return true;
        if (owner.equals("java/lang/Class") || owner.startsWith("java/lang/ClassLoader")) return true;
        if (owner.equals("java/lang/invoke/MethodHandles") || owner.startsWith("java/lang/invoke/MethodHandle")) return true;
        if (owner.equals("java/lang/StackWalker")) return true;
        return (owner.equals("java/lang/Thread") || owner.equals("java/lang/Throwable"))
            && "getStackTrace".equals(mi.name);
    }

    private boolean isNekoSupportMethod(L1Method method) {
        if (method.owner().name().startsWith("dev/nekoobfuscator/runtime/")) {
            return true;
        }
        return method.name().startsWith("__neko_");
    }

    private long deriveSiteBaseKey(long methodKey, int siteSalt, int targetId, int invokeType,
            long flowKey, boolean useFlowKey) {
        if (useFlowKey) {
            long siteKey = DynamicKeyDerivationEngine.mix(flowKey ^ INVOKE_FLOW_KEY_DOMAIN, siteSalt);
            siteKey = DynamicKeyDerivationEngine.mix(siteKey, targetId);
            return DynamicKeyDerivationEngine.mix(siteKey, invokeType);
        }
        long siteKey = DynamicKeyDerivationEngine.mix(methodKey, siteSalt);
        siteKey = DynamicKeyDerivationEngine.mix(siteKey, targetId);
        siteKey = DynamicKeyDerivationEngine.mix(siteKey, invokeType);
        return siteKey;
    }

    private long deriveMetadataKey(long siteBaseKey, int componentId) {
        return DynamicKeyDerivationEngine.finalize_(DynamicKeyDerivationEngine.mix(siteBaseKey, componentId));
    }

    private String encryptMetadataString(String value, long key) {
        byte[] encrypted = DynamicKeyDerivationEngine.encrypt(value.getBytes(StandardCharsets.UTF_8), key);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String opaqueCallsiteName(PipelineContext pctx) {
        return "_" + Integer.toUnsignedString(pctx.random().nextInt(), 36);
    }

    private BootstrapProfile getOrCreateBootstrap(L1Class clazz, PipelineContext pctx,
            KeyDispatcherSupport.Profile keyDispatcher) {
        String profileKey = keyDispatcher == null ? "plain" : keyDispatcher.methodName();
        BootstrapProfile existing = bootstrapProfiles.get(profileKey);
        if (existing != null) return existing;

        String methodName = uniqueMethodName(clazz,
            LOCAL_BOOTSTRAP_PREFIX + Integer.toUnsignedString(pctx.random().nextInt(), 36));
        BootstrapProfile profile = new BootstrapProfile(methodName, keyDispatcher);
        emitBootstrap(clazz, pctx, profile);
        bootstrapProfiles.put(profileKey, profile);
        clazz.markDirty();
        return profile;
    }

    private void emitBootstrap(L1Class clazz, PipelineContext pctx, BootstrapProfile profile) {
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        boolean itf = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
        access |= itf ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode method = new MethodNode(access, profile.methodName(), BOOTSTRAP_DESC, null,
            new String[] { "java/lang/Throwable" });
        InsnList insns = method.instructions;

        int callerClassLocal = 14;
        int siteKeyLocal = 15;
        int ownerLocal = 17;
        int nameLocal = 18;
        int descLocal = 19;
        int ownerClassLocal = 20;
        int targetTypeLocal = 21;
        int targetLookupLocal = 22;
        int handleLocal = 23;

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, callerClassLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, callerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "classKey", "(Ljava/lang/Class;)J", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));

        LabelNode noFlow = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, 11));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, noFlow));
        MethodInsnNode inboundFlowKeyRead = new MethodInsnNode(Opcodes.INVOKESTATIC,
            CONTEXT_OWNER, "flowKey", "()J", false);
        ControlFlowFlatteningPass.markInboundFlowKeyRead(pctx, inboundFlowKeyRead);
        insns.add(inboundFlowKeyRead);
        insns.add(new LdcInsnNode(INVOKE_FLOW_KEY_DOMAIN));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));
        insns.add(noFlow);

        mixSiteKey(insns, siteKeyLocal, 8);
        mixSiteKey(insns, siteKeyLocal, 10);
        mixSiteKey(insns, siteKeyLocal, 9);

        if (profile.keyDispatcher() != null) {
            LabelNode noKey = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ILOAD, 12));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, noKey));
            insns.add(new VarInsnNode(Opcodes.LLOAD, siteKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, 13));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name(),
                profile.keyDispatcher().methodName(), "(JI)J", itf));
            insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));
            insns.add(noKey);
        }

        emitMetadataDecrypt(insns, 3, 1, siteKeyLocal, ownerLocal);
        emitMetadataDecrypt(insns, 4, 2, siteKeyLocal, nameLocal);
        emitMetadataDecrypt(insns, 5, 3, siteKeyLocal, descLocal);

        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, '/'));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, '.'));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace",
            "(CC)Ljava/lang/String;", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, callerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
            "()Ljava/lang/ClassLoader;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, ownerClassLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, descLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
            "()Ljava/lang/ClassLoader;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodType",
            "fromMethodDescriptorString",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, targetTypeLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ASTORE, targetLookupLocal));

        LabelNode staticLabel = new LabelNode();
        LabelNode specialLabel = new LabelNode();
        LabelNode virtualLabel = new LabelNode();
        LabelNode afterResolve = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.INVOKESTATIC));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, staticLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.INVOKESPECIAL));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, specialLabel));
        insns.add(new JumpInsnNode(Opcodes.GOTO, virtualLabel));

        insns.add(staticLabel);
        emitLookupCall(insns, targetLookupLocal, ownerClassLocal, nameLocal, targetTypeLocal,
            "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(specialLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, targetLookupLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerClassLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, targetTypeLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, callerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "findSpecial",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
            false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(virtualLabel);
        emitLookupCall(insns, targetLookupLocal, ownerClassLocal, nameLocal, targetTypeLocal,
            "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(afterResolve);

        LabelNode fixed = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "isVarargsCollector", "()Z", false));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, fixed));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "asFixedArity", "()Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(fixed);

        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/invoke/ConstantCallSite"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite",
            "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxStack = 8;
        method.maxLocals = 24;
        NumberEncryptionPass.excludeGeneratedNumericInsns(pctx, insns);
        excludeGeneratedInvokeInsns(pctx, insns);
        clazz.asmNode().methods.add(method);
    }

    private void mixSiteKey(InsnList insns, int siteKeyLocal, int intLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, intLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));
    }

    private void emitMetadataDecrypt(InsnList insns, int encryptedLocal, int componentId,
            int siteKeyLocal, int targetLocal) {
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64",
            "getDecoder", "()Ljava/util/Base64$Decoder;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, encryptedLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder",
            "decode", "(Ljava/lang/String;)[B", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteKeyLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0 + componentId));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "finalize_", "(J)J", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STRING_DECRYPT_OWNER, "decrypt",
            "([BJ)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
    }

    private void emitLookupCall(InsnList insns, int lookupLocal, int ownerClassLocal,
            int nameLocal, int targetTypeLocal, String methodName, String desc) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, lookupLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerClassLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, targetTypeLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            methodName, desc, false));
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String name = base;
        int suffix = 0;
        while (hasMethod(clazz, name)) {
            name = base + "_" + (++suffix);
        }
        return name;
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private boolean hasMethod(L1Class clazz, String name, String desc) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int intOption(TransformConfig config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private AbstractInsnNode pushInt(int value) {
        return NumberEncryptionPass.generatedInt(value);
    }

    private void boxIfNeeded(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.BYTE -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.CHAR -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.FLOAT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.LONG -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.DOUBLE -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> {
            }
        }
    }

    private void adaptReturnValue(InsnList insns, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> insns.add(new InsnNode(Opcodes.POP));
            case Type.BOOLEAN -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.BYTE -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.CHAR -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Character", "charValue", "()C", false));
            }
            case Type.SHORT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.FLOAT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.LONG -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Long", "longValue", "()J", false));
            }
            case Type.DOUBLE -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Double", "doubleValue", "()D", false));
            }
            default -> insns.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
        }
    }

    private record MethodRiskStats(int branchCount, int backwardBranches, boolean hasSwitch,
                                   boolean hasMonitor, boolean hasSensitiveApi) {}
    private record BootstrapProfile(String methodName, KeyDispatcherSupport.Profile keyDispatcher) {}
}
