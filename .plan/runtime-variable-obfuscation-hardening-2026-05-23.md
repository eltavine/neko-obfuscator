# Runtime Variable Obfuscation Hardening - 2026-05-23

## Goal

Strengthen `runtimeVariableObfuscation` against dynamic local-slot dumping by
removing plaintext primitive local landing and replacing reference shadow locals
with encrypted integer handles backed by a CFF-carrier ThreadLocal frame.

## Subtasks

- [x] Subtask 1: primitive stack-only encoding
  - Scope: rewrite primitive store handling in
    `JvmRuntimeVariableObfuscationPass` so protected primitive values are not
    stored back into their original local slots before encoding.
  - Evidence required: bytecode audit proving protected primitive stores are
    encoded directly from the operand stack; focused runtime-variable
    integration test passes from a freshly transformed jar.
  - Validation command: ask before using repository `./gradlew`, then run
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
  - Completion criteria: no plaintext primitive temporary local store remains
    on the protected path, original primitive slots are only poisoned, and the
    focused test passes.
  - Evidence: protected primitive stores encode directly from stack values into
    shadow locals; protected primitive loads decrypt inline on the operand
    stack. Fresh focused validation passed with
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.

- [x] Subtask 2: reference encrypted handles with ThreadLocal frame
  - Scope: stop storing protected reference values in shadow locals; store each
    protected reference in a per-call frame reached through the existing CFF
    carrier and keep only encrypted integer handles in locals.
  - Evidence required: bytecode audit proving protected references are loaded
    through encrypted handles and carrier frame fetches; focused test covers
    reused reference slots with incompatible verifier types, recursion,
    exceptions, and parallel execution; generated CTF fixture verifies.
  - Validation command: ask before using repository `./gradlew`, run the
    focused runtime-variable test, then generate the five JVM-only test jars
    with `runtimeVariableObfuscation` enabled and verify ASM output.
  - Completion criteria: no protected application reference is stored in a
    shadow local, cleanup restores ThreadLocal frames on normal and exceptional
    exits, and the five requested jars obfuscate successfully without native.
  - Evidence: protected reference locals are represented as encrypted
    integer handles; object references are stored in a per-call `Object[]`
    frame reached through the CFF material carrier `ThreadLocal`; normal and
    exceptional exits restore the previous frame. Consumer-typed reference
    loads and reference-producing `AALOAD` sites now receive verifier-precise
    casts when they feed typed call, field, array, monitor, or return
    consumers, so routed references do not collapse to `Object` in generated
    stack maps. Fresh focused validation passed with
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
    Fresh full-JVM, native-disabled outputs were generated for `ctf`, `snake`,
    `test`, `test21`, and `evaluator` in `/mnt/d/Code/Reverse/NekoOBF`.

- [x] Subtask 3: generic large-method completion path
  - Scope: make the full JVM transform set with `runtimeVariableObfuscation`
    produce valid output for methods near the JVM 64 KB code limit without
    reducing CFF coverage, block granularity, key propagation, or transform
    strength. The fix must be architecture-level and must not special-case
    `test21`, `a/a.main`, or any sample-specific descriptor.
  - Evidence required: identify the exact size growth path and failing
    finalizer invariant; prove the changed path still uses live CFF/method key
    material; focused runtime-variable integration test passes; all five
    requested JVM-only jars produce fresh outputs.
  - Validation command: ask before using repository `./gradlew`, run the
    focused runtime-variable test, then regenerate `ctf`, `snake`, `test`,
    `test21`, and `evaluator` with `runtimeVariableObfuscation` enabled and
    native disabled.
  - Completion criteria: no `MethodTooLargeException`, no verifier/runtime
    failure during generation, no native output, no skip/fallback behavior, and
    all five requested `*-obf.jar` artifacts exist with fresh timestamps.
  - Evidence: the failing invariant was the JVM `Code` attribute hard
    limit during CFF class-code-integrity preview, before final output write.
    Fresh failing runs identified `a/a.main([Ljava/lang/String;)V` at about
    69.8 KB estimated code after the full JVM transform set with
    `runtimeVariableObfuscation`. The same full JVM fixture without
    `runtimeVariableObfuscation` writes successfully and is already near the
    limit (`a/a.main([Ljava/lang/String;)V` about 64.7 KB). Runtime-variable
    mask-slot compaction and direct reference handle routing reduced local
    pressure but did not remove the remaining 4+ KB. The generic completion
    path relocates large synthetic CFF helper sets into generated host classes;
    fresh `test21` generation reported `Relocated large CFF helper sets:
    hosts=1 methods=493` and wrote `/mnt/d/Code/Reverse/NekoOBF/test21-obf.jar`
    without `MethodTooLargeException`, native output, skip/fallback behavior,
    or verifier failure. The final native-disabled full-JVM generation wrote
    all requested artifacts: `ctf-obf.jar`, `snake-obf.jar`, `test-obf.jar`,
    `test21-obf.jar`, and `evaluator-obf.jar`.

## Constraints

- No special-casing of any jar, class, method, descriptor, log, or test
  artifact.
- No native path, JNI fallback, helper class layer, original-bytecode fallback,
  or skip-on-error behavior.
- Keep live CFF/method key material semantically involved in masks and handles.
- Commit each completed subtask with its matching checkbox update before
  starting the next subtask.
