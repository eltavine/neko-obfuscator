package dev.nekoobfuscator.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
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
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Exercises AES/DES plus XOR string obfuscation bound to CFF live key state.
 */
public class JvmStringObfuscationIntegrationTest {
    private static final int STRING_MATERIAL_SLOT = 66;
    private static final int STRING_MATERIAL_SELECTOR_SLOT = 69;
    private static final int CLASS_KEY_WORDS_SLOT = 65;
    private static final int CLASS_KEY_WORDS_SELECTOR_SLOT = 73;
    private static final int STRING_KEY_CELL_PAYLOAD_SLOT = 8;
    private static final int STRING_KEY_CELL_CACHE_SLOT = 9;
    private static final int STRING_KEY_CELL_LENGTH = 10;
    private static final int STRING_LOGICAL_KEY_WORDS = 4;
    private static final int STRING_SELECTOR_LAYOUT_SHIFT = 8;
    private static final int STRING_SELECTOR_LAYOUT_MASK = 0x3F;
    private static final int STRING_SELECTOR_FINGERPRINT_SHIFT = 14;
    private static final int STRING_SELECTOR_FINGERPRINT_MASK = 0x3FFFF;
    private static final long STRING_KEY_CELL_PAYLOAD_MASK = 0x5354525041594C44L;
    private static final long STRING_KEY_CELL_CACHE_MASK = 0x5354524341434845L;
    private static final long STRING_KEY_CELL_LENGTH_MASK = 0x5354524C454E3031L;
    private static final List<String> SECRET_STRINGS = List.of(
        "alpha-secret-cff-flow-17",
        "omega-flow-tail-23",
        "beta-state-keydispatch-29",
        "gamma-aes-des-xor-31",
        "delta-domain-token-43",
        "epsilon-live-key-47",
        "zeta-static-final-field-59",
        "eta-unused-static-field-61",
        "STRING OBF OK",
        "alpha-secret-cff-flow-17|tail-23|beta-state-keydispatch-29|gamma-aes-des-xor-31:delta-domain-token-43:epsilon-live-key-47:zeta-static-final-field-59"
    );

    @Test
    void stringObfuscationUsesCffKeyedAesDesXorWithoutHelpers() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-strings"));
        Path source = work.resolve("StringShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("string-shapes.jar");
        writeJar(inputJar, classes, "StringShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("string-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("STRING OBF OK"), obfuscated);
        assertPlaintextRemoved(outputJar);
        assertAesDesXorDecodeUsesCffState(outputJar);
        assertStringSitesHaveIndependentMaterialLayouts(outputJar);
    }

    @Test
    void stringObfuscationUsesDynamicTablesForInterfaceStringSites() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-interface-strings"));
        Path source = work.resolve("InterfaceStringShapes.java");
        Files.writeString(source, interfaceSourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("interface-string-shapes.jar");
        writeJar(inputJar, classes, "InterfaceStringShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("interface-string-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("INTERFACE STRING OBF OK"), obfuscated);

        JarInput input = new JarInput(outputJar);
        L1Class iface = input.classMap().get("InterfaceStrings");
        int objectTables = 0;
        boolean sawBytePayloadField = false;
        for (var field : iface.asmNode().fields) {
            if ("[Ljava/lang/Object;".equals(field.desc)) {
                objectTables++;
                assertTrue((field.access & Opcodes.ACC_FINAL) != 0, "interface material table must be final");
            }
            if ("[B".equals(field.desc)) {
                sawBytePayloadField = true;
            }
        }
        assertTrue(objectTables >= 1, "interface string sites should use a coalesced string key object table");
        assertFalse(sawBytePayloadField, "interface string sites must not use per-site byte payload fields");
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("constantObfuscation", new TransformConfig(true, 1.0));
        transforms.put("stringObfuscation", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x51724E6B5A11CFFL);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void assertPlaintextRemoved(Path jar) throws Exception {
        byte[] classBytes = NativeObfuscationHelper.extractEntry(jar, "StringShapes.class");
        String classText = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String secret : SECRET_STRINGS) {
            assertFalse(classText.contains(secret), "plaintext string remained in class bytes: " + secret);
        }

        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("StringShapes");
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            boolean classInitPath = "<clinit>".equals(method.name)
                || method.name.startsWith("__neko_strinit$")
                || method.name.startsWith("__neko_strcipher$");
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    assertFalse(SECRET_STRINGS.contains(value), "secret String LDC survived: " + value);
                }
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof String value) {
                            assertFalse(SECRET_STRINGS.contains(value), "secret indy bootstrap string survived: " + value);
                        }
                    }
                }
            }
        }
        for (var field : clazz.asmNode().fields) {
            if ("Ljava/lang/String;".equals(field.desc)) {
                assertEquals(null, field.value, "String ConstantValue remained on field " + field.name);
            }
        }

        for (String entry : NativeObfuscationHelper.jarEntries(jar)) {
            assertFalse(entry.startsWith("dev/nekoobfuscator/runtime/"), "runtime helper class injected: " + entry);
        }
    }

    private void assertAesDesXorDecodeUsesCffState(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("StringShapes");
        boolean sawCipher = false;
        boolean sawSecretKeySpec = false;
        boolean sawDoFinal = false;
        boolean sawUtf8 = false;
        boolean sawClassKeyTableLoad = false;
        boolean sawMethodKeyLoad = false;
        int standaloneCipherCacheFields = 0;
        boolean sawPayloadCacheField = false;
        int objectMaterialTables = 0;
        boolean sawBoxedFingerprintCacheRead = false;
        boolean sawBoxedFingerprintCacheWrite = false;
        boolean sawCipherMonitor = false;
        boolean sawCacheCompare = false;
        boolean sawXor = false;
        boolean sawRotate = false;
        boolean sawKeyCellUpdate = false;
        boolean sawSiteHelperKeyCellUpdate = false;
        boolean sawSharedStringDecodeTail = false;
        boolean sawTailLoadsStringMaterialSelectorSlot = false;
        boolean sawTailDirectStringMaterialSlotLoad = false;
        boolean sawConcatLoadsClassKeySelectorSlot = false;
        boolean sawConcatDirectClassKeySlotLoad = false;
        boolean sawLiveFlowTailCall = false;
        boolean sawTailConsumesDataWord = false;
        boolean sawStringConcatFactoryRecipe = false;
        int siteHelperDoFinalCalls = 0;
        int tailDoFinalCalls = 0;
        int siteHelperTailCalls = 0;
        int oldSharedTailAbiCalls = 0;
        int checkedStringDataTailCalls = 0;
        int siteHelperCount = 0;
        int sharedTailCount = 0;
        int getInstanceCalls = 0;
        int clinitGetInstanceCalls = 0;
        int getBytesCalls = 0;
        int clinitGetBytesCalls = 0;
        int standaloneStringMaterialHelpers = 0;
        int standaloneCipherInitHelpers = 0;
        int standaloneStringKeyInitHelpers = 0;
        int standaloneStringStreamHelpers = 0;
        int oldStreamHelperMethods = 0;
        int oldStreamHelperCalls = 0;

        for (var field : clazz.asmNode().fields) {
            if ("Ljavax/crypto/Cipher;".equals(field.desc)) {
                standaloneCipherCacheFields++;
            }
            if ("[Ljava/lang/Object;".equals(field.desc)) {
                objectMaterialTables++;
                sawPayloadCacheField = true;
            }
        }
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            AbstractInsnNode[] insns = method.instructions.toArray();
            boolean classInitPath = "<clinit>".equals(method.name)
                || method.name.startsWith("__neko_strinit$")
                || method.name.startsWith("__neko_strmat$")
                || method.name.startsWith("__neko_strkey$")
                || method.name.startsWith("__neko_strcache$")
                || method.name.startsWith("__neko_strcipher$");
            boolean stringDecodeHelper = method.name.startsWith("__neko_str$");
            boolean stringDecodeTail = method.name.startsWith("__neko_strtail$");
            boolean stringConcatHelper = method.name.startsWith("__neko_strcat$");
            if (method.name.startsWith("__neko_strmat$") || method.name.startsWith("__neko_strcache$")) {
                standaloneStringMaterialHelpers++;
            }
            if (method.name.startsWith("__neko_strcipher$")) {
                standaloneCipherInitHelpers++;
            }
            if (method.name.startsWith("__neko_strkey$")) {
                standaloneStringKeyInitHelpers++;
            }
            if (method.name.startsWith("__neko_strstream")) {
                standaloneStringStreamHelpers++;
            }
            if ("(IIJJ)I".equals(method.desc)) {
                oldStreamHelperMethods++;
            }
            if (stringDecodeHelper) {
                siteHelperCount++;
            }
            if (stringDecodeTail) {
                sawSharedStringDecodeTail = true;
                sharedTailCount++;
                if (tailSelectorDecodeConsumesDataWord(insns)) {
                    sawTailConsumesDataWord = true;
                }
            }
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof MethodInsnNode call
                    && "StringShapes".equals(call.owner)
                    && call.name.startsWith("__neko_strtail$")
                    && "([Ljava/lang/Object;JIIIIII)Ljava/lang/String;".equals(call.desc)) {
                    sawLiveFlowTailCall = true;
                    assertTrue(
                        stringTailCallReceivesDataDigest(insns, i),
                        "string tail call does not receive a dataLocal-derived live word in " +
                            method.name + method.desc
                    );
                    checkedStringDataTailCalls++;
                }
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy
                    && indy.bsm != null
                    && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
                    && "makeConcatWithConstants".equals(indy.bsm.getName())) {
                    sawStringConcatFactoryRecipe = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "javax/crypto/Cipher".equals(call.owner)
                    && "getInstance".equals(call.name)) {
                    sawCipher = true;
                    getInstanceCalls++;
                    if (classInitPath) {
                        clinitGetInstanceCalls++;
                    }
                }
                if (insn instanceof MethodInsnNode call
                    && "java/lang/String".equals(call.owner)
                    && "getBytes".equals(call.name)
                    && "(Ljava/nio/charset/Charset;)[B".equals(call.desc)) {
                    getBytesCalls++;
                    if (classInitPath) {
                        clinitGetBytesCalls++;
                    }
                }
                if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && "(IIJJ)I".equals(call.desc)) {
                    oldStreamHelperCalls++;
                }
                if (insn instanceof MethodInsnNode call
                    && "javax/crypto/spec/SecretKeySpec".equals(call.owner)
                    && "<init>".equals(call.name)) {
                    sawSecretKeySpec = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "javax/crypto/Cipher".equals(call.owner)
                    && "doFinal".equals(call.name)) {
                    sawDoFinal = true;
                    if (stringDecodeHelper) {
                        siteHelperDoFinalCalls++;
                    }
                    if (stringDecodeTail) {
                        tailDoFinalCalls++;
                    }
                }
                if (stringDecodeHelper
                    && insn instanceof MethodInsnNode call
                    && "StringShapes".equals(call.owner)
                    && call.name.startsWith("__neko_strtail$")
                    && "([Ljava/lang/Object;IJI)Ljava/lang/String;".equals(call.desc)) {
                    siteHelperTailCalls++;
                }
                if (insn instanceof MethodInsnNode call
                    && "StringShapes".equals(call.owner)
                    && call.name.startsWith("__neko_strtail$")
                    && ("([Ljava/lang/Object;IJIII)Ljava/lang/String;".equals(call.desc)
                        || "([Ljava/lang/Object;IJIIII)Ljava/lang/String;".equals(call.desc)
                        || "([Ljava/lang/Object;JIIIIIIII)Ljava/lang/String;".equals(call.desc)
                        || "([Ljava/lang/Object;JIIIIIII)Ljava/lang/String;".equals(call.desc))) {
                    oldSharedTailAbiCalls++;
                }
                if (insn instanceof MethodInsnNode call
                    && "java/lang/Long".equals(call.owner)
                    && "longValue".equals(call.name)
                    && "()J".equals(call.desc)) {
                    sawBoxedFingerprintCacheRead = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "java/lang/Long".equals(call.owner)
                    && "valueOf".equals(call.name)
                    && "(J)Ljava/lang/Long;".equals(call.desc)) {
                    sawBoxedFingerprintCacheWrite = true;
                }
                if (insn instanceof FieldInsnNode field
                    && "java/nio/charset/StandardCharsets".equals(field.owner)
                    && "UTF_8".equals(field.name)) {
                    sawUtf8 = true;
                }
                if (insn instanceof FieldInsnNode field
                    && "[I".equals(field.desc)
                    && "StringShapes".equals(field.owner)) {
                    sawClassKeyTableLoad = true;
                }
                if (insn instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.CHECKCAST
                    && "[I".equals(type.desc)) {
                    sawClassKeyTableLoad = true;
                }
                if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.LLOAD) {
                    sawMethodKeyLoad = true;
                }
                if (insn.getOpcode() == Opcodes.IXOR) {
                    sawXor = true;
                }
                if (stringDecodeTail && insn.getOpcode() == Opcodes.IASTORE) {
                    sawKeyCellUpdate = true;
                }
                if (stringDecodeTail
                    && pushesInt(insn, STRING_MATERIAL_SELECTOR_SLOT)
                    && insn.getNext() != null
                    && insn.getNext().getOpcode() == Opcodes.AALOAD) {
                    sawTailLoadsStringMaterialSelectorSlot = true;
                }
                if (stringDecodeTail
                    && pushesInt(insn, STRING_MATERIAL_SLOT)
                    && insn.getNext() != null
                    && insn.getNext().getOpcode() == Opcodes.AALOAD) {
                    sawTailDirectStringMaterialSlotLoad = true;
                }
                if (stringConcatHelper
                    && pushesInt(insn, CLASS_KEY_WORDS_SELECTOR_SLOT)
                    && insn.getNext() != null
                    && insn.getNext().getOpcode() == Opcodes.AALOAD) {
                    sawConcatLoadsClassKeySelectorSlot = true;
                }
                if (stringConcatHelper
                    && pushesInt(insn, CLASS_KEY_WORDS_SLOT)
                    && insn.getNext() != null
                    && insn.getNext().getOpcode() == Opcodes.AALOAD) {
                    sawConcatDirectClassKeySlotLoad = true;
                }
                if (stringDecodeHelper && insn.getOpcode() == Opcodes.IASTORE) {
                    sawSiteHelperKeyCellUpdate = true;
                }
                if (insn.getOpcode() == Opcodes.LCMP) {
                    sawCacheCompare = true;
                }
                if (insn.getOpcode() == Opcodes.MONITORENTER || insn.getOpcode() == Opcodes.MONITOREXIT) {
                    sawCipherMonitor = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "java/lang/Integer".equals(call.owner)
                    && ("rotateLeft".equals(call.name) || "rotateRight".equals(call.name))) {
                    sawRotate = true;
                }
            }
        }

        assertTrue(sawCipher, "string pass should use JCE Cipher without helper injection");
        assertEquals(0, standaloneCipherCacheFields, "AES and DES Cipher instances should be coalesced into the string key object table");
        assertTrue(sawPayloadCacheField, "encrypted payload bytes should be loaded through class static state");
        assertTrue(objectMaterialTables >= 1, "string material should use a class object table");
        assertEquals(0, standaloneStringMaterialHelpers, "string payload/cache material should be coalesced into the key table");
        assertEquals(0, standaloneCipherInitHelpers, "string cipher cache setup should be coalesced into the key table init path");
        assertEquals(0, standaloneStringKeyInitHelpers, "string key material init should be inlined into <clinit>");
        assertEquals(0, standaloneStringStreamHelpers, "string stream helper should be inlined into the shared tail");
        assertEquals(0, oldStreamHelperMethods, "old string stream helper ABI should be absent");
        assertEquals(0, oldStreamHelperCalls, "shared string tail should not call old stream helper ABI");
        assertTrue(sawBoxedFingerprintCacheRead, "plaintext cache should read boxed live-key fingerprint state");
        assertTrue(sawBoxedFingerprintCacheWrite, "plaintext cache should write boxed live-key fingerprint state");
        assertTrue(sawCacheCompare, "plaintext cache should compare the current live-key fingerprint");
        assertTrue(sawCipherMonitor, "shared Cipher cache should be guarded for concurrent callers");
        assertEquals(clinitGetInstanceCalls, getInstanceCalls, "Cipher.getInstance should be limited to <clinit> cache setup");
        assertEquals(clinitGetBytesCalls, getBytesCalls, "encrypted payload getBytes should be limited to <clinit> setup");
        assertTrue(getInstanceCalls <= 2, "Cipher.getInstance should be per class algorithm, not per string site");
        assertTrue(sawSecretKeySpec, "string pass should build keys inline");
        assertTrue(sawDoFinal, "string pass should decrypt inline");
        assertTrue(sawSharedStringDecodeTail, "string decrypt body should be consolidated into shared tail helpers");
        assertTrue(sawTailLoadsStringMaterialSelectorSlot, "string shared tail should load a material selector from the passed CFF object carrier");
        assertFalse(sawTailDirectStringMaterialSlotLoad, "string shared tail should not directly load the fixed material slot");
        assertTrue(sawConcatLoadsClassKeySelectorSlot, "string concat helper should load class-key selector from the CFF object carrier");
        assertFalse(sawConcatDirectClassKeySlotLoad, "string concat helper should not directly load the fixed class-key slot");
        assertTrue(sawLiveFlowTailCall, "string shared tail calls should pass a live long flow key");
        assertTrue(checkedStringDataTailCalls > 0, "string shared tail calls should pass dataLocal-derived material");
        assertTrue(sawTailConsumesDataWord, "string shared tail should consume dataLocal-derived material");
        assertEquals(0, oldSharedTailAbiCalls, "string shared tail calls should not pass static payload/cache/length slots");
        assertTrue(sharedTailCount <= 1, "string shared tails should be selector-dispatched per class, not per algorithm/layer/order");
        assertEquals(0, siteHelperCount, "string decode should call shared tails directly instead of registering per-site wrappers");
        assertEquals(0, siteHelperDoFinalCalls, "per-site string wrappers should not duplicate Cipher.doFinal bodies");
        assertTrue(tailDoFinalCalls > 0, "shared string tail helpers should own Cipher.doFinal bodies");
        assertEquals(0, siteHelperTailCalls, "per-site string wrappers should be eliminated");
        assertTrue(sawUtf8, "decoded bytes should construct UTF-8 strings without UTF-8 LDC");
        assertTrue(sawClassKeyTableLoad, "decode should depend on CFF class key table");
        assertTrue(sawMethodKeyLoad, "decode should depend on keyDispatch/CFF method key local");
        assertTrue(sawXor, "decode should include XOR stream unmasking");
        assertTrue(sawKeyCellUpdate, "shared string tail should dynamically re-encode key material cells");
        assertFalse(sawSiteHelperKeyCellUpdate, "per-site string wrappers should pass key tables instead of owning key-cell updates");
        assertFalse(sawRotate, "string decode must not use rotate self-cancelling masks");
        assertFalse(sawStringConcatFactoryRecipe, "concat recipe strings should be rewritten and encrypted");
    }

    private void assertStringSitesHaveIndependentMaterialLayouts(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("StringShapes");
        List<StringMaterialCell> cells = new ArrayList<>();
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            for (int[] rawCell : literalIntArrays(method.instructions.toArray())) {
                StringMaterialCell decoded = decodeStringMaterialCell(rawCell);
                if (decoded != null) {
                    cells.add(decoded);
                }
            }
        }
        assertTrue(cells.size() >= 2, "string fixture should emit at least two string material cells");

        List<String> layoutFingerprints = new ArrayList<>();
        for (StringMaterialCell cell : cells) {
            String pair = cell.layoutId() + ":" + cell.fingerprint();
            if (!layoutFingerprints.contains(pair)) {
                layoutFingerprints.add(pair);
            }
        }
        assertTrue(
            layoutFingerprints.size() >= 2,
            "string sites should not share one layout/fingerprint pair: " + layoutFingerprints
        );

        boolean observedSeedDecodesEveryCell = false;
        for (StringMaterialCell candidate : cells) {
            boolean decodesEveryCell = true;
            for (StringMaterialCell cell : cells) {
                if (!decodesWords(cell.rawCell(), cell.words(), candidate.siteSeed(), cell.epoch())) {
                    decodesEveryCell = false;
                    break;
                }
            }
            if (decodesEveryCell) {
                observedSeedDecodesEveryCell = true;
                break;
            }
        }
        assertFalse(
            observedSeedDecodesEveryCell,
            "one observed string site seed decoded every material cell"
        );
    }

    private List<int[]> literalIntArrays(AbstractInsnNode[] insns) {
        List<int[]> arrays = new ArrayList<>();
        for (int i = 0; i < insns.length; i++) {
            Integer length = pushedIntValue(insns[i]);
            if (length == null || length <= 0 || length > 256) continue;
            int newArray = nextRealIndex(insns, i + 1, insns.length);
            if (newArray < 0 ||
                !(insns[newArray] instanceof IntInsnNode arrayType) ||
                arrayType.getOpcode() != Opcodes.NEWARRAY ||
                arrayType.operand != Opcodes.T_INT) {
                continue;
            }
            int[] values = new int[length];
            boolean ok = true;
            int cursor = newArray + 1;
            for (int entry = 0; entry < length; entry++) {
                int dup = nextRealIndex(insns, cursor, insns.length);
                int indexInsn = dup < 0 ? -1 : nextRealIndex(insns, dup + 1, insns.length);
                int valueInsn = indexInsn < 0 ? -1 : nextRealIndex(insns, indexInsn + 1, insns.length);
                int store = valueInsn < 0 ? -1 : nextRealIndex(insns, valueInsn + 1, insns.length);
                Integer index = indexInsn < 0 ? null : pushedIntValue(insns[indexInsn]);
                Integer value = valueInsn < 0 ? null : pushedIntValue(insns[valueInsn]);
                if (dup < 0 ||
                    insns[dup].getOpcode() != Opcodes.DUP ||
                    index == null ||
                    value == null ||
                    index < 0 ||
                    index >= length ||
                    store < 0 ||
                    insns[store].getOpcode() != Opcodes.IASTORE) {
                    ok = false;
                    break;
                }
                values[index] = value;
                cursor = store + 1;
            }
            if (ok) {
                arrays.add(values);
                i = cursor;
            }
        }
        return arrays;
    }

    private StringMaterialCell decodeStringMaterialCell(int[] rawCell) {
        if (rawCell.length != 11) return null;
        int epoch = rawCell[4];
        long siteSeed = ((long) (rawCell[5] ^ stringSiteTokenCellMask(epoch, 0)) << 32) |
            ((rawCell[6] ^ stringSiteTokenCellMask(epoch, 1)) & 0xFFFFFFFFL);
        int selector = rawCell[7] ^ stringTailSelectorCellMask(epoch);
        int layoutId = (selector >>> STRING_SELECTOR_LAYOUT_SHIFT) & STRING_SELECTOR_LAYOUT_MASK;
        int fingerprint = (selector >>> STRING_SELECTOR_FINGERPRINT_SHIFT) &
            STRING_SELECTOR_FINGERPRINT_MASK;
        if (layoutId == 0 || fingerprint == 0) return null;
        int payloadSlot = rawCell[STRING_KEY_CELL_PAYLOAD_SLOT] ^ stringSiteMetadataCellMask(
            siteSeed,
            epoch,
            STRING_KEY_CELL_PAYLOAD_MASK
        );
        int cacheSlot = rawCell[STRING_KEY_CELL_CACHE_SLOT] ^ stringSiteMetadataCellMask(
            siteSeed,
            epoch,
            STRING_KEY_CELL_CACHE_MASK
        );
        int encryptedLength = rawCell[STRING_KEY_CELL_LENGTH] ^ stringSiteMetadataCellMask(
            siteSeed,
            epoch,
            STRING_KEY_CELL_LENGTH_MASK
        );
        if (payloadSlot < 0 || cacheSlot < 0 || encryptedLength <= 0 || encryptedLength > 4096) {
            return null;
        }
        int[] words = decodedWords(rawCell, siteSeed, epoch);
        return new StringMaterialCell(rawCell.clone(), siteSeed, epoch, layoutId, fingerprint, words);
    }

    private int[] decodedWords(int[] rawCell, long siteSeed, int epoch) {
        int[] words = new int[STRING_LOGICAL_KEY_WORDS];
        for (int i = 0; i < words.length; i++) {
            words[i] = rawCell[i] ^ stringKeyCellMask(siteSeed, i, epoch);
        }
        return words;
    }

    private boolean decodesWords(int[] rawCell, int[] expectedWords, long siteSeed, int epoch) {
        for (int i = 0; i < expectedWords.length; i++) {
            if ((rawCell[i] ^ stringKeyCellMask(siteSeed, i, epoch)) != expectedWords[i]) {
                return false;
            }
        }
        return true;
    }

    private int stringKeyCellMask(long siteSeed, int wordIndex, int epoch) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D41534BL + wordIndex));
        x ^= x >>> shift(siteSeed, 9 + wordIndex);
        x *= nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D554C31L + wordIndex)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D46494EL + wordIndex));
    }

    private int stringSiteTokenCellMask(int epoch, int wordIndex) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(0x5354525349544531L, wordIndex));
        x ^= x >>> (7 + wordIndex);
        x *= nonZeroInt(JvmPassBytecode.mix(0x5354525349544532L, wordIndex)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(0x5354525349544533L, wordIndex));
    }

    private int stringTailSelectorCellMask(int epoch) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(0x53545253454C3131L, 0x5441494C53454CL));
        x ^= x >>> 13;
        x *= nonZeroInt(JvmPassBytecode.mix(0x53545253454C3132L, 0x5441494C53454CL)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(0x53545253454C3133L, 0x5441494C53454CL));
    }

    private int stringSiteMetadataCellMask(long siteSeed, int epoch, long maskSeed) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, maskSeed));
        x ^= x >>> shift(siteSeed, 11);
        x *= nonZeroInt(JvmPassBytecode.mix(siteSeed, maskSeed ^ 0x5354524D45544131L)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, maskSeed ^ 0x5354524D45544132L));
    }

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30L);
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x3D6B2A4F : v;
    }

    private boolean stringTailCallReceivesDataDigest(AbstractInsnNode[] insns, int callIndex) {
        int start = Math.max(0, callIndex - 40);
        int intLoads = 0;
        int longLoads = 0;
        int lastIntLoad = -1;
        for (int i = start; i < callIndex; i++) {
            if (insns[i] instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
                intLoads++;
                lastIntLoad = i;
            } else if (insns[i] instanceof VarInsnNode load && load.getOpcode() == Opcodes.LLOAD) {
                longLoads++;
            }
        }
        if (longLoads == 0 || intLoads < 5 || lastIntLoad < 0) return false;
        int encodedSiteArgs = 0;
        for (int i = lastIntLoad + 1; i < callIndex; i++) {
            if (pushesAnyInt(insns[i])) {
                encodedSiteArgs++;
            }
        }
        return encodedSiteArgs >= 1;
    }

    private boolean tailSelectorDecodeConsumesDataWord(AbstractInsnNode[] insns) {
        int selectorLocal = 38;
        int dataWordLocal = 12;
        int siteSeedLocal = 22;
        return dataBoundSelectorBranchCount(insns, selectorLocal, dataWordLocal, siteSeedLocal) >= 4
            && plainSelectorBranchCount(insns, selectorLocal, dataWordLocal) == 0;
    }

    private boolean rootTransportConsumesDataWord(
        AbstractInsnNode[] insns,
        int dataLocal,
        int startInclusive,
        int limitExclusive
    ) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ILOAD
                || load.var != dataLocal) {
                continue;
            }
            int limit = Math.min(limitExclusive, i + 80);
            if (hasOpcode(insns, Opcodes.IMUL, i + 1, limit)
                && hasOpcode(insns, Opcodes.IUSHR, i + 1, limit)
                && hasOpcode(insns, Opcodes.IOR, i + 1, limit)) {
                return true;
            }
        }
        return false;
    }

    private int dataBoundSelectorBranchCount(
        AbstractInsnNode[] insns,
        int selectorLocal,
        int dataWordLocal,
        int siteSeedLocal
    ) {
        int count = 0;
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode selectorLoad)
                || selectorLoad.getOpcode() != Opcodes.ILOAD
                || selectorLoad.var != selectorLocal) {
                continue;
            }
            int limit = Math.min(insns.length, i + 180);
            if (varLoadCount(insns, dataWordLocal, i + 1, limit) == 0) continue;
            if (longLoadCount(insns, siteSeedLocal, i + 1, limit) == 0) continue;
            if (!hasOpcode(insns, Opcodes.IAND, i + 1, limit)) continue;
            if (!hasOpcode(insns, Opcodes.IMUL, i + 1, limit)) continue;
            if (!hasOpcode(insns, Opcodes.IUSHR, i + 1, limit)) continue;
            if (!hasOpcode(insns, Opcodes.IXOR, i + 1, limit)) continue;
            int selectorBranch = firstSelectorBranch(insns, i + 1, limit);
            if (selectorBranch < 0) continue;
            if (opcodeCount(insns, Opcodes.IOR, i + 1, selectorBranch) < 2) continue;
            if (opcodeCount(insns, Opcodes.IMUL, i + 1, selectorBranch) < 2) continue;
            if (selectorBranch >= 0) {
                count++;
                i = selectorBranch;
            }
        }
        return count;
    }

    private int plainSelectorBranchCount(AbstractInsnNode[] insns, int selectorLocal, int dataWordLocal) {
        int count = 0;
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof VarInsnNode selectorLoad)
                || selectorLoad.getOpcode() != Opcodes.ILOAD
                || selectorLoad.var != selectorLocal) {
                continue;
            }
            int limit = Math.min(insns.length, i + 80);
            if (varLoadCount(insns, dataWordLocal, i + 1, limit) != 0) continue;
            if (firstSelectorBranch(insns, i + 1, limit) >= 0) {
                count++;
            }
        }
        return count;
    }

    private int firstSelectorBranch(AbstractInsnNode[] insns, int startInclusive, int limitExclusive) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            int previous = previousRealIndex(insns, i);
            if (previous < 0) continue;
            if (insns[i].getOpcode() == Opcodes.IF_ICMPEQ
                && (pushesInt(insns[previous], 1) || pushesInt(insns[previous], 2))) {
                return i;
            }
            if (insns[i].getOpcode() == Opcodes.IFNE
                && insns[previous].getOpcode() == Opcodes.IAND) {
                return i;
            }
            if (insns[i].getOpcode() == Opcodes.IFEQ) {
                return i;
            }
        }
        return -1;
    }

    private int previousSelectorIand(AbstractInsnNode[] insns, int callIndex) {
        int start = Math.max(0, callIndex - 120);
        for (int i = callIndex - 1; i >= start; i--) {
            if (insns[i].getOpcode() != Opcodes.IAND) continue;
            int previous = previousRealIndex(insns, i);
            if (previous >= 0 && insns[previous].getOpcode() == Opcodes.ICONST_1) {
                return i;
            }
        }
        return -1;
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

    private boolean hasOpcode(AbstractInsnNode[] insns, int opcode, int startInclusive, int limitExclusive) {
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i].getOpcode() == opcode) return true;
        }
        return false;
    }

    private int opcodeCount(AbstractInsnNode[] insns, int opcode, int startInclusive, int limitExclusive) {
        int count = 0;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i].getOpcode() == opcode) {
                count++;
            }
        }
        return count;
    }

    private int varLoadCount(AbstractInsnNode[] insns, int local, int startInclusive, int limitExclusive) {
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

    private int longLoadCount(AbstractInsnNode[] insns, int local, int startInclusive, int limitExclusive) {
        int count = 0;
        for (int i = startInclusive; i < limitExclusive && i < insns.length; i++) {
            if (insns[i] instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.LLOAD
                && load.var == local) {
                count++;
            }
        }
        return count;
    }

    private static boolean pushesInt(AbstractInsnNode insn, int value) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return value == opcode - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode intInsn) {
            return intInsn.operand == value;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer intValue) {
            return intValue == value;
        }
        return false;
    }

    private static boolean pushesAnyInt(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
            || insn instanceof IntInsnNode
            || (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer);
    }

    private static Integer pushedIntValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode intInsn) {
            return intInsn.operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer intValue) {
            return intValue;
        }
        return null;
    }

    private record StringMaterialCell(
        int[] rawCell,
        long siteSeed,
        int epoch,
        int layoutId,
        int fingerprint,
        int[] words
    ) {}

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
            public class StringShapes {
                private static final String FIELD_SECRET = "zeta-static-final-field-59";
                private static final String UNUSED_FIELD_SECRET = "eta-unused-static-field-61";

                public static void main(String[] args) {
                    String a = "alpha-secret-cff-flow-17";
                    String b = "omega-flow-tail-23";
                    String c = "beta-state-keydispatch-29";
                    String d = "gamma-aes-des-xor-31";
                    String e = "delta-domain-token-43";
                    String f = "epsilon-live-key-47";
                    String g = reflectedField();
                    String out = a + "|" + b.substring(11) + "|" + c + "|" + d + ":" + e + ":" + f + ":" + g;
                    System.out.println(out);
                    if (!out.equals("alpha-secret-cff-flow-17|tail-23|beta-state-keydispatch-29|gamma-aes-des-xor-31:delta-domain-token-43:epsilon-live-key-47:zeta-static-final-field-59")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("STRING OBF OK");
                }

                private static String reflectedField() {
                    try {
                        java.lang.reflect.Field field = StringShapes.class.getDeclaredField("FIELD_SECRET");
                        field.setAccessible(true);
                        return (String) field.get(null);
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                }
            }
            """;
    }

    private String interfaceSourceText() {
        return """
            interface InterfaceStrings {
                static String message() {
                    String left = "iface-flow-key-71";
                    String right = "iface-tail-73";
                    return left + ":" + right;
                }
            }

            public class InterfaceStringShapes {
                public static void main(String[] args) {
                    String out = InterfaceStrings.message();
                    System.out.println(out);
                    if (!out.equals("iface-flow-key-71:iface-tail-73")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("INTERFACE STRING OBF OK");
                }
            }
            """;
    }
}
