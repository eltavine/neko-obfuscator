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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JvmMethodParameterObfuscationIntegrationTest {
    @Test
    void methodParameterObfuscationPacksEligibleMethodsIntoObjectArray() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-method-parameters"));
        Path source = work.resolve("ParameterShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("parameter-shapes.jar");
        writeJar(inputJar, classes, "ParameterShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("parameter-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("PARAMETER OBF OK"), obfuscated);
        assertPackedDescriptors(outputJar);
        assertCallsUsePackedDescriptors(outputJar);
        assertCarrierIndexMarkersRemoved(outputJar);
        assertHiddenKeyCarrierReadsUseDecodedIndexes(outputJar);
        assertCarrierStoresUseDecodedIndexes(outputJar);
        assertCarrierAttestationValidationPresent(outputJar);
        assertForgedCarrierFails(work, outputJar);
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("methodParameterObfuscation", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("constantObfuscation", new TransformConfig(true, 1.0));
        transforms.put("stringObfuscation", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x4D504152414D31L);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void assertPackedDescriptors(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if ("<clinit>".equals(method.name)) continue;
                if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) continue;
                if (method.name.startsWith("__neko_")) continue;
                assertTrue(
                    isPackedParameterDescriptor(method.desc),
                    clazz.name() + "." + method.name + method.desc + " was not packed"
                );
            }
        }
    }

    private void assertCallsUsePackedDescriptors(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    if (!call.owner.startsWith("ParameterShapes")) continue;
                    if ("<clinit>".equals(call.name)) continue;
                    if (call.name.startsWith("__neko_")) continue;
                    assertTrue(
                        isPackedParameterDescriptor(call.desc),
                        "application call was not packed: " + call.owner + "." + call.name + call.desc
                    );
                }
            }
        }
    }

    private void assertCarrierIndexMarkersRemoved(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    assertTrue(
                        !"dev/nekoobfuscator/runtime/CarrierIndex".equals(call.owner),
                        "carrier index marker leaked into generated jar: " +
                            clazz.name() + "." + method.name + method.desc
                    );
                }
            }
        }
    }

    private void assertHiddenKeyCarrierReadsUseDecodedIndexes(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        int decodedHiddenKeyReads = 0;
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                if (method.instructions == null || !isPackedParameterDescriptor(method.desc)) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof TypeInsnNode cast) || !"java/lang/Long".equals(cast.desc)) continue;
                    AbstractInsnNode load = previousReal(insn.getPrevious());
                    if (!(load instanceof InsnNode aaload) || aaload.getOpcode() != Opcodes.AALOAD) continue;
                    AbstractInsnNode index = previousReal(load.getPrevious());
                    assertTrue(
                        index == null || !isIntConstant(index),
                        "hidden key carrier read still uses a literal index in " +
                            clazz.name() + "." + method.name + method.desc
                    );
                    assertTrue(
                        hasClassKeyObjectFieldLoadBefore(load),
                        "hidden key carrier read does not use class-key table material in " +
                            clazz.name() + "." + method.name + method.desc
                    );
                    decodedHiddenKeyReads++;
                }
            }
        }
        assertTrue(decodedHiddenKeyReads > 0, "no decoded hidden key carrier reads were found");
    }

    private void assertCarrierStoresUseDecodedIndexes(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        int decodedStores = 0;
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InsnNode store) || store.getOpcode() != Opcodes.AASTORE) continue;
                    AbstractInsnNode index = previousCarrierStoreIndex(store);
                    if (index == null) continue;
                    if (!isIntConstant(index) && hasClassKeyObjectFieldLoadBefore(store)) {
                        decodedStores++;
                    }
                }
            }
        }
        assertTrue(decodedStores >= 8, "expected decoded carrier stores for direct, virtual, MethodHandle, and reflection paths");
    }

    private void assertCarrierAttestationValidationPresent(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        int guardedPackedMethods = 0;
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                if (method.instructions == null || !isPackedParameterDescriptor(method.desc)) continue;
                int longCarrierReads = countLongCarrierReads(method);
                if (longCarrierReads < 3) continue;
                assertTrue(
                    countLongCompareGuards(method) >= 2,
                    "packed carrier attestation did not compare a derived long tag in " +
                        clazz.name() + "." + method.name + method.desc
                );
                assertTrue(
                    hasHardFailThrow(method),
                    "packed carrier attestation did not hard-fail in " +
                        clazz.name() + "." + method.name + method.desc
                );
                guardedPackedMethods++;
            }
        }
        assertTrue(guardedPackedMethods > 0, "no packed carrier attestation guards were found");
    }

    private void assertForgedCarrierFails(Path work, Path jar) throws Exception {
        Path source = work.resolve("ForgedCarrierProbe.java");
        Files.writeString(source, forgedCarrierProbeText(), StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(work.resolve("probe-classes"));
        run(
            List.of(
                "javac",
                "-J-XX:-UsePerfData",
                "-cp",
                jar.toString(),
                "-d",
                classes.toString(),
                source.toString()
            ),
            Duration.ofSeconds(30)
        );
        String output = run(
            List.of(
                "java",
                "-XX:-UsePerfData",
                "-cp",
                classes + System.getProperty("path.separator") + jar,
                "ForgedCarrierProbe"
            ),
            Duration.ofSeconds(30)
        );
        assertTrue(output.contains("FORGED CARRIER REJECTED"), output);
    }

    private String forgedCarrierProbeText() {
        return """
            import java.lang.reflect.InvocationTargetException;
            import java.lang.reflect.Method;
            import java.util.Arrays;

            public final class ForgedCarrierProbe {
                public static void main(String[] args) throws Exception {
                    Method target = null;
                    for (Method method : ParameterShapes.class.getDeclaredMethods()) {
                        if (method.getName().equals("add")
                            && method.getParameterCount() >= 1
                            && method.getParameterTypes()[0].isArray()) {
                            target = method;
                            break;
                        }
                    }
                    if (target == null) {
                        throw new AssertionError("packed add missing");
                    }
                    target.setAccessible(true);
                    Object[] carrier = new Object[5];
                    Arrays.fill(carrier, Long.valueOf(0L));
                    Object[] invokeArgs = target.getParameterCount() == 2
                        ? new Object[] {carrier, Long.valueOf(0L)}
                        : new Object[] {carrier};
                    try {
                        target.invoke(null, invokeArgs);
                        throw new AssertionError("forged carrier accepted");
                    } catch (InvocationTargetException ex) {
                        Throwable cause = ex.getCause();
                        if (!(cause instanceof SecurityException)) {
                            throw new AssertionError("unexpected forged-carrier failure: " + cause, cause);
                        }
                    }
                    System.out.println("FORGED CARRIER REJECTED");
                }
            }
            """;
    }

    private int countLongCarrierReads(MethodNode method) {
        int reads = 0;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode cast && "java/lang/Long".equals(cast.desc)) {
                AbstractInsnNode load = previousReal(insn.getPrevious());
                if (load instanceof InsnNode aaload && aaload.getOpcode() == Opcodes.AALOAD) {
                    reads++;
                }
            }
        }
        return reads;
    }

    private int countLongCompareGuards(MethodNode method) {
        int guards = 0;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof InsnNode cmp) || cmp.getOpcode() != Opcodes.LCMP) continue;
            int scanned = 0;
            for (AbstractInsnNode next = insn.getNext(); next != null && scanned++ < 8; next = next.getNext()) {
                if (next.getOpcode() < 0) continue;
                int opcode = next.getOpcode();
                if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
                    guards++;
                    break;
                }
            }
        }
        return guards;
    }

    private boolean hasHardFailThrow(MethodNode method) {
        boolean sawSecurityException = false;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.NEW
                && "java/lang/SecurityException".equals(type.desc)) {
                sawSecurityException = true;
                continue;
            }
            if (sawSecurityException && insn.getOpcode() == Opcodes.ATHROW) return true;
            if (insn.getOpcode() == Opcodes.ATHROW) {
                AbstractInsnNode previous = previousReal(insn.getPrevious());
                if (previous != null && previous.getOpcode() == Opcodes.ACONST_NULL) return true;
            }
        }
        return false;
    }

    private AbstractInsnNode previousCarrierStoreIndex(AbstractInsnNode store) {
        AbstractInsnNode scan = store.getPrevious();
        for (int seen = 0; scan != null && seen++ < 96; scan = scan.getPrevious()) {
            if (scan.getOpcode() < 0) continue;
            if (!(scan instanceof InsnNode dup) || dup.getOpcode() != Opcodes.DUP) continue;
            AbstractInsnNode index = nextReal(dup.getNext());
            if (index == null || index == store) continue;
            AbstractInsnNode cursor = nextReal(index.getNext());
            while (cursor != null && cursor != store) {
                if (cursor.getOpcode() == Opcodes.AASTORE) break;
                cursor = nextReal(cursor.getNext());
            }
            if (cursor == store) return index;
        }
        return null;
    }

    private AbstractInsnNode nextReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private AbstractInsnNode previousReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private boolean hasClassKeyObjectFieldLoadBefore(AbstractInsnNode anchor) {
        int scanned = 0;
        for (AbstractInsnNode insn = anchor.getPrevious(); insn != null && scanned++ < 192; insn = insn.getPrevious()) {
            if (insn instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC
                && "[Ljava/lang/Object;".equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIntConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return true;
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return true;
        return insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof Integer;
    }

    private boolean isPackedParameterDescriptor(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        return args.length > 0 && "[Ljava/lang/Object;".equals(args[0].getDescriptor());
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
            import java.lang.reflect.Constructor;
            import java.lang.reflect.Method;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import java.util.Arrays;

            public class ParameterShapes {
                interface Worker {
                    int work(int value, String text);
                }

                interface ReflectInvoker {
                    int invoke(Method method, Object target, Object[] args) throws Throwable;
                }

                static class Impl implements Worker {
                    public int work(int value, String text) {
                        return value + text.length();
                    }
                }

                static class ReflectInvokerImpl implements ReflectInvoker {
                    public int invoke(Method method, Object target, Object[] args) throws Throwable {
                        method.setAccessible(true);
                        return ((Integer) method.invoke(target, args)).intValue();
                    }
                }

                static class Box {
                    private final int base;
                    private final String tag;

                    Box(int base) {
                        this(base, "q");
                    }

                    Box(int base, String tag) {
                        this.base = base;
                        this.tag = tag;
                    }

                    int mix(int a, long b, double c, Object[] values) {
                        return base + tag.length() + a + (int) b + (int) c + values.length;
                    }
                }

                static class SpecialBox extends Box {
                    SpecialBox(int base, String tag) {
                        super(base, tag);
                    }

                    int mix(int a, long b, double c, Object[] values) {
                        return -1000;
                    }

                    int callSuper(MethodHandle handle) throws Throwable {
                        return (int) handle.invokeExact(this, 5, 6L, 7.0d, new Object[] {"s"});
                    }
                }

                static class UnusedConstructor {
                    UnusedConstructor() {
                    }
                }

                public static void main(String[] args) throws Throwable {
                    String out = runAll(args);
                    System.out.println(out);
                    if (!out.equals("total:382:true")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("PARAMETER OBF OK");
                }

                static String runAll(String[] args) throws Throwable {
                    ParameterShapes shapes = new ParameterShapes();
                    Worker worker = new Impl();
                    Box box = new Box(3, "xy");
                    int direct = directAndReflectionPaths(shapes, worker, box);
                    if (direct != 276) {
                        throw new AssertionError("direct:" + direct);
                    }
                    int methodHandles = methodHandlePaths(box);
                    if (methodHandles != 67) {
                        throw new AssertionError("methodHandles:" + methodHandles);
                    }
                    int interfaces = interfacePaths(worker);
                    if (interfaces != 39) {
                        throw new AssertionError("interfaces:" + interfaces);
                    }
                    int total = direct + methodHandles + interfaces;
                    return join("total", total, Arrays.asList(args).isEmpty());
                }

                static int directAndReflectionPaths(ParameterShapes shapes, Worker worker, Box box) throws Throwable {
                    int direct = directCore(shapes, worker, box);
                    if (direct != 88) {
                        throw new AssertionError("direct-core:" + direct);
                    }
                    int reflectedMethods = reflectMethods();
                    if (reflectedMethods != 90) {
                        throw new AssertionError("reflect-methods:" + reflectedMethods);
                    }
                    int reflectedConstructor = reflectConstructor();
                    if (reflectedConstructor != 98) {
                        throw new AssertionError("reflect-constructor:" + reflectedConstructor);
                    }
                    int total = direct;
                    total += reflectedMethods;
                    total += reflectedConstructor;
                    return total;
                }

                static int directCore(ParameterShapes shapes, Worker worker, Box box) {
                    int total = add(4, 5);
                    if (total != 9) throw new AssertionError("core-add:" + total);
                    total += shapes.noArg();
                    if (total != 15) throw new AssertionError("core-noarg:" + total);
                    total += shapes.overload(7);
                    if (total != 23) throw new AssertionError("core-overload-int:" + total);
                    total += shapes.overload("abcd");
                    if (total != 29) throw new AssertionError("core-overload-string:" + total);
                    total += shapes.overload(2, 6);
                    if (total != 41) throw new AssertionError("core-overload-two:" + total);
                    total += worker.work(8, "abc");
                    if (total != 52) throw new AssertionError("core-worker:" + total);
                    total += box.mix(9, 10L, 11.0d, new Object[] {"z"});
                    return total;
                }

                static int reflectMethods() throws Throwable {
                    int total = 0;
                    Class<?> actualOwner = ParameterShapes.class;
                    String actualName = "reflectTarget";
                    Class<?>[] actualParams = new Class<?>[] {String.class, int.class};
                    Class<?> wrongOwner = Box.class;
                    String wrongName = "mix";
                    Class<?>[] wrongParams = new Class<?>[] {int.class, long.class, double.class, Object[].class};
                    if (wrongOwner == null || wrongName.length() == -1 || wrongParams.length == -1) {
                        total += 1000;
                    }
                    Method method = actualOwner.getDeclaredMethod(actualName, actualParams);
                    method.setAccessible(true);
                    total += ((Integer) method.invoke(null, new Object[] {"qr", 12})).intValue();
                    total += reflectSameLocalAfterThrow();
                    total += methodInvokeEscaped(method, null, new Object[] {"wx", 16});
                    ReflectInvoker invoker = new ReflectInvokerImpl();
                    total += invoker.invoke(method, null, new Object[] {"ij", 18});

                    Method[] actualMethods = ParameterShapes.class.getDeclaredMethods();
                    Method[] wrongMethods = Box.class.getDeclaredMethods();
                    if (wrongMethods.length == -1) {
                        total += wrongMethods.length;
                    }
                    for (Method candidate : actualMethods) {
                        if (candidate.getName().equals("reflectTarget")) {
                            candidate.setAccessible(true);
                            if (method.getName().equals("notReflectTarget")) {
                                total += 1000;
                            }
                            int reflected = ((Integer) candidate.invoke(null, new Object[] {"uv", 15})).intValue();
                            total += reflected;
                            break;
                        }
                    }
                    return total;
                }

                static int reflectSameLocalAfterThrow() throws Throwable {
                    Class<?>[] params = new Class<?>[] {String.class, int.class};
                    if (never()) {
                        params = new Class<?>[] {int.class};
                        throw new AssertionError("stale-params:" + params.length);
                    }
                    Method method = ParameterShapes.class.getDeclaredMethod("reflectTarget", params);
                    method.setAccessible(true);
                    return ((Integer) method.invoke(null, new Object[] {"sl", 19})).intValue();
                }

                static boolean never() {
                    return Boolean.getBoolean("neko.never") && System.nanoTime() == Long.MIN_VALUE;
                }

                static int methodInvokeEscaped(Method method, Object target, Object[] args) throws Throwable {
                    method.setAccessible(true);
                    return ((Integer) method.invoke(target, args)).intValue();
                }

                static int reflectConstructor() throws Throwable {
                    int total = 0;
                    Class<Box> ctorOwner = Box.class;
                    Class<?>[] ctorParams = new Class<?>[] {int.class, String.class};
                    Class<?> wrongCtorOwner = UnusedConstructor.class;
                    Class<?>[] wrongCtorParams = new Class<?>[] {};
                    if (wrongCtorOwner == null || wrongCtorParams.length == -1) {
                        total += 1000;
                    }
                    Constructor<Box> ctor = ctorOwner.getDeclaredConstructor(ctorParams);
                    ctor.setAccessible(true);
                    Box reflected = ctor.newInstance(new Object[] {13, "rs"});
                    total += reflected.mix(1, 2L, 3.0d, new Object[] {"a", "b"});

                    Object stored = Box.class.getDeclaredConstructor(int.class, String.class);
                    ((Constructor<?>) stored).setAccessible(true);
                    Box escapedBox = (Box) ((Constructor<?>) stored).newInstance(new Object[] {14, "tu"});
                    total += escapedBox.mix(1, 2L, 3.0d, new Object[] {"a", "b"});

                    Box arrayBox = boxFromConstructorArray();
                    total += arrayBox.mix(1, 2L, 3.0d, new Object[] {"a", "b"});

                    Box helperBox = constructEscaped(
                        Box.class.getDeclaredConstructor(int.class, String.class),
                        16,
                        "yz"
                    );
                    total += helperBox.mix(1, 2L, 3.0d, new Object[] {"a", "b"});
                    return total;
                }

                static Box constructEscaped(Constructor<?> constructor, int base, String tag) throws Throwable {
                    constructor.setAccessible(true);
                    return (Box) constructor.newInstance(new Object[] {base, tag});
                }

                static Box boxFromConstructorArray() throws Throwable {
                    Constructor<?>[] actualConstructors = Box.class.getDeclaredConstructors();
                    Constructor<?>[] wrongConstructors = UnusedConstructor.class.getDeclaredConstructors();
                    if (wrongConstructors.length == -1) {
                        throw new AssertionError(wrongConstructors.length);
                    }
                    for (Constructor<?> candidate : actualConstructors) {
                        Class<?>[] params = candidate.getParameterTypes();
                        if (params.length == 2 && params[0] == int.class && params[1] == String.class) {
                            candidate.setAccessible(true);
                            return (Box) candidate.newInstance(new Object[] {15, "vw"});
                        }
                        if (params.length == 3
                            && params[0].isArray()
                            && params[1] == long.class
                            && params[2] == long.class) {
                            candidate.setAccessible(true);
                            return (Box) candidate.newInstance(new Object[] {15, "vw"});
                        }
                    }
                    throw new AssertionError("constructor-array");
                }

                static int methodHandlePaths(Box box) throws Throwable {
                    int total = 0;
                    Class<?> staticOwner = ParameterShapes.class;
                    String staticName = "methodHandleTarget";
                    MethodType staticType = MethodType.methodType(int.class, String.class, int.class);
                    if (never()) {
                        Class<?> staleOwner = Box.class;
                        String staleName = "mix";
                        MethodType staleType = MethodType.methodType(
                            int.class,
                            int.class,
                            long.class,
                            double.class,
                            Object[].class
                        );
                        total += staleName.length() + staleType.parameterCount() + (staleOwner == null ? 1 : 0);
                    }
                    MethodHandle handle = MethodHandles.lookup().findStatic(
                        staticOwner,
                        staticName,
                        staticType
                    );
                    total += (int) handle.invokeExact("mh", 14);

                    MethodHandle virtualHandle = MethodHandles.lookup().findVirtual(
                        Box.class,
                        "mix",
                        MethodType.methodType(int.class, int.class, long.class, double.class, Object[].class)
                    );
                    total += (int) virtualHandle.invokeExact(box, 2, 3L, 4.0d, new Object[] {"v"});

                    MethodHandle specialHandle = MethodHandles.privateLookupIn(
                        SpecialBox.class,
                        MethodHandles.lookup()
                    ).findSpecial(
                        Box.class,
                        "mix",
                        MethodType.methodType(int.class, int.class, long.class, double.class, Object[].class),
                        SpecialBox.class
                    );
                    total += new SpecialBox(4, "sp").callSuper(specialHandle);

                    Class<?> ctorOwner = Box.class;
                    MethodType ctorType = MethodType.methodType(void.class, int.class, String.class);
                    if (never()) {
                        Class<?> staleCtorOwner = UnusedConstructor.class;
                        MethodType staleCtorType = MethodType.methodType(void.class);
                        total += staleCtorType.parameterCount() + (staleCtorOwner == null ? 1 : 0);
                    }
                    MethodHandle constructorHandle = MethodHandles.lookup().findConstructor(ctorOwner, ctorType);
                    Box constructed = (Box) constructorHandle.invokeExact(6, "hc");
                    total += constructed.mix(1, 1L, 1.0d, new Object[] {});

                    return total;
                }

                static int add(int left, int right) {
                    return left + right;
                }

                int noArg() {
                    return 6;
                }

                int overload(int value) {
                    return value + 1;
                }

                int overload(String value) {
                    return value.length() + 2;
                }

                int overload(int left, int right) {
                    return left * right;
                }

                static int reflectTarget(String text, int value) {
                    return text.length() + value;
                }

                static int methodHandleTarget(String text, int value) {
                    return text.length() + value;
                }

                static int callWorkerHandle(MethodHandle handle, Worker worker) throws Throwable {
                    return (int) handle.invokeExact(worker, 16, "iface");
                }

                static int interfacePaths(Worker worker) throws Throwable {
                    MethodHandle interfaceHandle = MethodHandles.lookup().findVirtual(
                        Worker.class,
                        "work",
                        MethodType.methodType(int.class, int.class, String.class)
                    );
                    Method interfaceMethod = Worker.class.getMethod("work", int.class, String.class);
                    return callWorkerHandle(interfaceHandle, worker)
                        + ((Integer) interfaceMethod.invoke(worker, new Object[] {17, "r"})).intValue();
                }

                static String join(String prefix, int value, boolean flag) {
                    return prefix + ":" + value + ":" + flag;
                }
            }
            """;
    }
}
