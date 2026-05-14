package dev.nekoobfuscator.test;

import dev.nekoobfuscator.core.jar.JarInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures a JVM-only full-obfuscation performance baseline for the repository
 * sample jars. This is a baseline gate: it records timings and requires fresh
 * full-obf artifacts to run where the fixture has a non-interactive perf path.
 */
class JvmFullObfuscationPerfTest {
    private static final Pattern PERF_TOPIC_PATTERN = Pattern.compile(
        "(?i)(calc|matrix|seq|thread|virtual|parallel|vthreads|time)"
    );
    private static final Pattern PERF_TIME_PATTERN = Pattern.compile(
        "(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:ns|us|micros?|ms|milliseconds?|s|seconds?)\\b"
    );
    private static final Pattern MISSING_FLOWKEY_HELPERS_PATTERN = Pattern.compile(
        "Control-flow generated helpers missing flow keys: candidates=(\\d+) keyed=(\\d+)"
    );

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void fullJvmObfuscationPerformanceBaseline() throws Exception {
        Path workDir = Files.createDirectories(
            NativeObfuscationHelper.projectRoot()
                .resolve("build")
                .resolve("test-jvm-full-obf-perf")
        );
        Path config = NativeObfuscationHelper.jarsDir().resolve("full-jvm-obf.yml");
        assertTrue(Files.exists(config), () -> "Missing full JVM obfuscation config: " + config);

        List<Fixture> fixtures = List.of(
            new Fixture("TEST", "TEST.jar", true, true),
            new Fixture("obfusjack", "obfusjack-test21.jar", true, false),
            new Fixture("SnakeGame", "SnakeGame.jar", false, false),
            new Fixture("evaluator", "evaluator-unobf.jar", false, false)
        );

        List<PerfRecord> records = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            Path input = NativeObfuscationHelper.jarsDir().resolve(fixture.jarName());
            assertTrue(Files.exists(input), () -> "Missing fixture jar: " + input);
            Path output = workDir.resolve(fixture.name() + "-full-jvm-obf.jar");
            NativeObfuscationHelper.ObfuscationRunResult obfuscation =
                NativeObfuscationHelper.obfuscateJar(input, output, config, Duration.ofMinutes(2));
            assertNoStaticGeneratedHelperHardening(fixture, obfuscation);
            RunRecord originalRun = null;
            RunRecord obfuscatedRun = null;
            if (fixture.runAfterObfuscation()) {
                originalRun = runFixture(workDir, fixture, input, "original");
                obfuscatedRun = runFixture(workDir, fixture, output, "full-obf");
                assertNoForbiddenFullObfMarkers(output);
            }
            records.add(new PerfRecord(fixture, input, output, obfuscation, originalRun, obfuscatedRun));
        }

        Path report = workDir.resolve("jvm-full-obf-performance-baseline.json");
        Files.writeString(report, renderReport(config, records), StandardCharsets.UTF_8);
        assertTrue(Files.exists(report), () -> "Missing JVM full-obf perf report: " + report);
    }

    private static RunRecord runFixture(
        Path workDir,
        Fixture fixture,
        Path jar,
        String variant
    )
        throws Exception {
        Path stdout = workDir.resolve(fixture.name() + "." + variant + ".run.stdout.log");
        Path stderr = workDir.resolve(fixture.name() + "." + variant + ".run.stderr.log");
        NativeObfuscationHelper.JarRunResult run = NativeObfuscationHelper.runJar(
            jar,
            List.of("-XX:-UsePerfData"),
            List.of(),
            stdout,
            stderr,
            Duration.ofMinutes(2)
        );
        String combined = NativeObfuscationHelper.combinedOutput(run);
        assertEquals(0, run.exitCode(), () -> fixture.name() + " " + variant + " run failed\n" + combined);

        Long calcMillis = null;
        if (fixture.parseCalc()) {
            calcMillis = NativeObfuscationHelper.parseCalcMillis(combined);
        } else {
            assertTrue(
                combined.contains("=== All tests completed ==="),
                () -> fixture.name() + " " + variant + " run did not complete\n" + combined
            );
        }
        return new RunRecord(run, calcMillis, extractPerformanceTimingLines(combined));
    }

    private static void assertNoForbiddenFullObfMarkers(Path jar) throws Exception {
        for (String entry : NativeObfuscationHelper.jarEntries(jar)) {
            assertTrue(
                !entry.startsWith("dev/nekoobfuscator/runtime/"),
                () -> "runtime helper class injected in full-obf artifact: " + entry
            );
        }

        JarInput input = new JarInput(jar);
        Map<String, Integer> helperPrefixCounts = new LinkedHashMap<>();
        Map<String, Integer> helperDescriptorCounts = new LinkedHashMap<>();
        int syntheticObjectCarriers = 0;
        for (var clazz : input.classMap().values()) {
            int classSyntheticObjectCarriers = 0;
            for (var field : clazz.asmNode().fields) {
                assertTrue(!"[I".equals(field.desc), () -> "standalone int[] field survived in " + clazz.name());
                assertTrue(!"[J".equals(field.desc), () -> "standalone long[] field survived in " + clazz.name());
                assertTrue(!"Ljavax/crypto/Cipher;".equals(field.desc), () -> "standalone Cipher field survived in " + clazz.name());
                if ((field.access & Opcodes.ACC_SYNTHETIC) != 0) {
                    if ((field.access & Opcodes.ACC_STATIC) != 0 && "[Ljava/lang/Object;".equals(field.desc)) {
                        syntheticObjectCarriers++;
                        classSyntheticObjectCarriers++;
                    }
                    assertTrue(!"[B".equals(field.desc), () -> "synthetic byte[] static material survived in " + clazz.name());
                    assertTrue(!"J".equals(field.desc), () -> "synthetic long static cache material survived in " + clazz.name());
                    assertTrue(
                        !"Ljava/lang/String;".equals(field.desc),
                        () -> "synthetic String static cache material survived in " + clazz.name()
                    );
                }
                assertTrue(
                    !"Ljava/util/concurrent/ConcurrentHashMap;".equals(field.desc),
                    () -> "standalone indy cache field survived in " + clazz.name()
                );
            }
            assertTrue(
                classSyntheticObjectCarriers <= 1,
                () -> "multiple synthetic Object[] carrier fields in " + clazz.name()
            );
            for (var method : clazz.asmNode().methods) {
                recordHelperPrefix(helperPrefixCounts, method.name);
                helperDescriptorCounts.merge(method.desc, 1, Integer::sum);
                assertTrue(!method.name.startsWith("__neko_strkey"), () -> "string key init helper survived in " + clazz.name());
                assertTrue(!method.name.startsWith("__neko_strstream"), () -> "string stream helper survived in " + clazz.name());
                assertTrue(!method.name.startsWith("__neko_indy_mix"), () -> "indy mix helper survived in " + clazz.name());
                assertTrue(!method.name.startsWith("__neko_cff_didx"), () -> "direct-index CFF helper survived in " + clazz.name());
                assertTrue(!method.name.startsWith("__neko_indy_bsm_shared"), () -> "shared indy BSM registry route survived in " + clazz.name());
                assertTrue(!method.name.startsWith("__neko_indy_register"), () -> "indy carrier registry helper survived in " + clazz.name());
                assertTrue(!"(IIJJ)I".equals(method.desc), () -> "old string stream helper ABI survived in " + clazz.name());
                assertTrue(!"(JJ)J".equals(method.desc), () -> "old indy mix helper ABI survived in " + clazz.name());
                if (method.instructions == null) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call && call.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC) {
                        assertTrue(!"(IIJJ)I".equals(call.desc), () -> "old string stream helper call survived in " + clazz.name());
                        assertTrue(!"(JJ)J".equals(call.desc), () -> "old indy mix helper call survived in " + clazz.name());
                    }
                }
            }
        }
        assertTrue(
            syntheticObjectCarriers > 0,
            () -> "missing class-owned synthetic Object[] carrier surface in full-obf artifact: " + jar
        );
        assertAcceptedSharedHelperCounts(jar, helperPrefixCounts, helperDescriptorCounts);
    }

    private static void assertNoStaticGeneratedHelperHardening(
        Fixture fixture,
        NativeObfuscationHelper.ObfuscationRunResult obfuscation
    ) {
        String combined = obfuscation.combinedOutput();
        var matcher = MISSING_FLOWKEY_HELPERS_PATTERN.matcher(combined);
        while (matcher.find()) {
            int keyed = Integer.parseInt(matcher.group(2));
            assertEquals(
                0,
                keyed,
                () -> fixture.name() + " generated helper without live flowkey was keyed by CFF"
            );
        }
    }

    private static void recordHelperPrefix(Map<String, Integer> counts, String methodName) {
        for (String prefix : List.of(
            "__neko_strtail$",
            "__neko_indy_resolve",
            "__neko_indy_flow",
            "__neko_indy_guard",
            "__neko_cff_mkey$",
            "__neko_cff_int$",
            "__neko_cff_tmat$"
        )) {
            if (methodName.startsWith(prefix)) {
                counts.merge(prefix, 1, Integer::sum);
                return;
            }
        }
    }

    private static void assertAcceptedSharedHelperCounts(
        Path jar,
        Map<String, Integer> helperPrefixCounts,
        Map<String, Integer> helperDescriptorCounts
    ) {
        for (String prefix : List.of(
            "__neko_strtail$",
            "__neko_indy_resolve",
            "__neko_indy_flow",
            "__neko_indy_guard",
            "__neko_cff_mkey$",
            "__neko_cff_int$",
            "__neko_cff_tmat$"
        )) {
            int count = helperPrefixCounts.getOrDefault(prefix, 0);
            assertTrue(
                count <= 1,
                () -> "accepted shared helper regressed in " + jar + ": " + prefix + " count=" + count
            );
        }
        for (String desc : List.of(
            "([Ljava/lang/Object;IJI)Ljava/lang/String;",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;I[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodType;Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            "(IIII[Ljava/lang/Object;IJI)J",
            "(JJ)Z",
            "(IIIIJJ)J",
            "([IIIIIII)I",
            "([Ljava/lang/Object;IIII)I"
        )) {
            int count = helperDescriptorCounts.getOrDefault(desc, 0);
            assertTrue(
                count <= 1,
                () -> "accepted shared helper descriptor regressed in " + jar + ": " + desc + " count=" + count
            );
        }
    }

    private static List<String> extractPerformanceTimingLines(String output) {
        return output.lines()
            .filter(line -> PERF_TOPIC_PATTERN.matcher(line).find())
            .filter(line -> PERF_TIME_PATTERN.matcher(line).find())
            .toList();
    }

    private static String renderReport(Path config, List<PerfRecord> records)
        throws Exception {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        sb.append("  \"capturedAt\": ").append(json(Instant.now().toString())).append(",\n");
        sb.append("  \"config\": ").append(json(config.toString())).append(",\n");
        sb.append("  \"jdk\": {\n");
        sb.append("    \"javaVersion\": ").append(json(System.getProperty("java.version"))).append(",\n");
        sb.append("    \"javaVendor\": ").append(json(System.getProperty("java.vendor"))).append(",\n");
        sb.append("    \"javaVmName\": ").append(json(System.getProperty("java.vm.name"))).append("\n");
        sb.append("  },\n");
        sb.append("  \"fixtures\": [\n");
        for (int i = 0; i < records.size(); i++) {
            appendRecord(sb, records.get(i), "    ");
            if (i + 1 < records.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendRecord(StringBuilder sb, PerfRecord record, String indent)
        throws Exception {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"name\": ").append(json(record.fixture().name())).append(",\n");
        sb.append(indent).append("  \"inputJar\": ").append(json(record.inputJar().toString())).append(",\n");
        sb.append(indent).append("  \"outputJar\": ").append(json(record.outputJar().toString())).append(",\n");
        sb.append(indent).append("  \"inputBytes\": ").append(Files.size(record.inputJar())).append(",\n");
        sb.append(indent).append("  \"outputBytes\": ").append(Files.size(record.outputJar())).append(",\n");
        sb.append(indent).append("  \"obfuscationMillis\": ")
            .append(record.obfuscation().duration().toMillis()).append(",\n");
        sb.append(indent).append("  \"obfuscationStdout\": ")
            .append(json(record.obfuscation().stdoutPath().toString())).append(",\n");
        sb.append(indent).append("  \"obfuscationStderr\": ")
            .append(json(record.obfuscation().stderrPath().toString()));
        if (record.originalRun() != null) {
            sb.append(",\n");
            appendRun(sb, "originalRun", record.originalRun(), indent + "  ");
            sb.append(",\n");
            appendRun(sb, "fullObfRun", record.fullObfRun(), indent + "  ");
            sb.append('\n');
        } else {
            sb.append('\n');
        }
        sb.append(indent).append('}');
    }

    private static void appendRun(StringBuilder sb, String name, RunRecord run, String indent) {
        sb.append(indent).append(json(name)).append(": {\n");
        sb.append(indent).append("  \"durationMillis\": ").append(run.result().duration().toMillis()).append(",\n");
        sb.append(indent).append("  \"exitCode\": ").append(run.result().exitCode()).append(",\n");
        sb.append(indent).append("  \"stdout\": ").append(json(run.result().stdoutPath().toString())).append(",\n");
        sb.append(indent).append("  \"stderr\": ").append(json(run.result().stderrPath().toString())).append(",\n");
        sb.append(indent).append("  \"calcMillis\": ")
            .append(run.calcMillis() == null ? "null" : run.calcMillis()).append(",\n");
        sb.append(indent).append("  \"timingLines\": [");
        for (int i = 0; i < run.timingLines().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(json(run.timingLines().get(i)));
        }
        sb.append("]\n");
        sb.append(indent).append('}');
    }

    private static String json(String value) {
        Objects.requireNonNull(value, "value");
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private record Fixture(
        String name,
        String jarName,
        boolean runAfterObfuscation,
        boolean parseCalc
    ) {}

    private record RunRecord(
        NativeObfuscationHelper.JarRunResult result,
        Long calcMillis,
        List<String> timingLines
    ) {}

    private record PerfRecord(
        Fixture fixture,
        Path inputJar,
        Path outputJar,
        NativeObfuscationHelper.ObfuscationRunResult obfuscation,
        RunRecord originalRun,
        RunRecord fullObfRun
    ) {}
}
