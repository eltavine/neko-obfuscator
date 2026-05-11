# CFF ZKM-Inspired Key Dispatch Optimization

## Scope

Improve current JVM CFF performance, obfuscation strength, and key
source/dispatch architecture using the ZKM observations as design pressure,
without sample-specific behavior, fallback paths, skipped transforms, or reduced
CFF coverage.

## Runtime Target Rows

- R-build: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`
- R-test: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ObfuscationIntegrationTest`
- R-inspect: static inspection of transformed CFF bytecode tests for plain
  dispatch/key constants and method-key-free decoding paths.

## Subtasks

- [x] Subtask 1: CFF runtime token constants use class context and live method key.
  - Scope: replace runtime CFF encrypted-token literal pushes with a generic
    decoder that derives a mask from the per-class CFF key table, the live
    method key, and the active guard/path/block key state. Dead helper paths are
    not evidence and are not the implementation target.
  - Required evidence: `emitEncryptedToken`, `emitEncryptedBoundToken`, and
    related transition paths currently push encrypted token material as bytecode
    constants, then decode it only from local key state. These paths are reached
    by dispatcher state, domain, callsite seed, and CFF transition generation.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`
  - Completion criteria: transformed bytecode remains valid, existing CFF
    algebraic audits pass, and a new audit proves generated token decoding uses
    a CFF class key table load in runtime methods.
  - Validation result: passed
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`;
    `javap` inspection of the fresh CFF audit jar showed runtime
    `getstatic [I` / `iaload` class table loads in non-`<clinit>` transformed
    methods.
- [x] Subtask 2: CFF entry key schedule carries explicit class/method context.
  - Scope: after Subtask 1 is committed, inspect the entry seed/runtime path and
    strengthen entry key derivation only if fresh evidence shows a static or
    descriptor-only path remains.
  - Required evidence: exact bytecode path and transformed artifact showing the
    entry seed source that remains insufficient.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`
  - Completion criteria: entry key audit proves method-entry material remains
    live through dispatcher decisions without static-key exposure.
  - Evidence: `entryInitSeed` currently derives from dispatcher group salt plus
    fixed external-entry constants only; `methodSeed` is used as the key value
    to `initialKeyState`, but not as entry seed input.
  - Validation result: passed
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- [x] Subtask 3: JVM full-obf performance baseline for CFF-covered jars.
  - Scope: add a source-controlled JVM obfuscation performance test that uses
    `test-jars/full-jvm-obf.yml` and the repository test jars named by the user.
    TEST and obfusjack must have separate run/perf observations after
    obfuscation. SnakeGame and evaluator are smoke-obfuscated under the same
    config so the full-obf path covers all requested inputs without inventing
    sample-specific behavior.
  - Required evidence: `test-jars/full-jvm-obf.yml` enables CFF plus
    keyDispatch, method parameters, invokeDynamic, constant, string, and renamer
    transforms with native disabled; `test-jars/TEST.jar`,
    `test-jars/obfusjack-test21.jar`, `test-jars/SnakeGame.jar`, and
    `test-jars/evaluator-unobf.jar` are present in the repository. Fresh
    validation before the fix failed while writing full-obf `TEST.jar` because
    renamed `a/b.main([Ljava/lang/String;)V` was estimated at 92,239 bytes and
    ASM raised `MethodTooLargeException`; this identifies CFF transition/token
    code size pressure as the first prerequisite before runtime perf can be
    measured.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`
  - Completion criteria: the test obfuscates all requested jars with
    `full-jvm-obf.yml`, writes a structured timing report under the build
    directory, runs fresh obfuscated TEST and obfusjack artifacts successfully,
    records TEST Calc and obfusjack runtime observations separately, and does
    not alter CFF block coverage, CFF boundaries, native fallback behavior, or
    transform strength.
  - Implementation evidence: CFF transition outliner thresholds were tightened
    by generic size pressure only; no block boundaries, block selection,
    coverage, fallback behavior, or transform enablement were changed. The
    existing outlined transition path still carries guard/path/block/method key
    state and returns packed keyed transition state.
  - Validation result: passed
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`;
    report written to
    `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json`.
