# Runtime Variable Obfuscation - 2026-05-23

## Evidence chain

- Failing invariant: existing JVM obfuscation protects method names, constants, strings, and control flow, but ordinary JVM local slots can still hold stable plaintext runtime values after assignments such as `x = getNumber()`.
- Runtime path: protected application bytecode stores values with normal `ISTORE/LSTORE/FSTORE/DSTORE/ASTORE` and later reloads them with normal local loads.
- Attack observation supplied for this task: a validation routine with a per-character mismatch accumulator leaks an oracle because correct prefixes keep the accumulator at zero and a wrong character changes it to non-zero.
- Required architectural fix: protected application local values must not remain stable plaintext in original local slots; their uses must be rewritten through CFF live-keyed state, and equality-style comparisons must avoid restoring a dumpable plaintext accumulator local.

## Subtasks

- [x] 1. Transform skeleton and registration.
  - Scope: add `runtimeVariableObfuscation` pass after `validationSinkHardening` and before indy/constants/strings, with CFF metadata binding and no effective bytecode rewrite yet.
  - Required evidence: scheduled pass list includes `runtimeVariableObfuscation`; methods without CFF metadata are not modified.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`
  - Completion criteria: targeted test proves pass registration/config and no unrelated files are changed.
  - Completion evidence: targeted runtime-variable integration test passed with `BUILD SUCCESSFUL in 1s`; logs showed scheduled `[runtimeVariableObfuscation] JVM Runtime Variable Obfuscation` after `validationSinkHardening`.

- [x] 2. Primitive local shadow encryption.
  - Scope: rewrite protected int-like, long, float, and double local stores/loads/IINC into live CFF-keyed shadow locals and poison original local slots.
  - Required evidence: generated fixture output matches original; bytecode inspection shows protected primitive original locals are poisoned after stores and later loads use shadow decode paths.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`
  - Completion criteria: fixture covers primitive locals and mismatch accumulator without verifier errors.
  - Completion evidence: targeted integration test passed; fixture exercised int accumulator, long, float, and double locals; bytecode audit found primitive zero poison stores after shadow encryption.

- [x] 3. Reference local shadow protection.
  - Scope: rewrite protected object and array local stores/loads into verifier-valid shadow slots; original reference locals are nulled after stores. Plan adjustment: raw object encryption/carrier projection was not used because preserving verifier type at every object use site requires a wider typed projection pass; this subtask closes the verifier-safe reference-local storage layer.
  - Required evidence: fixture output matches original across object locals, arrays, casts, virtual calls, null checks, returns, throws, and monitor-sensitive paths.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`
  - Completion criteria: object/reference paths remain verifier-safe and do not retain protected object locals in original slots after stores.
  - Completion evidence: targeted integration test passed; fixture exercised object locals, arrays, casts, virtual calls, synchronized monitor use, and returns; bytecode audit found null stores to original reference slots.

- [x] 4. Encoded equality comparisons.
  - Scope: rewrite safe equality branches over protected int-like and long locals to compare encoded shadow values directly instead of decoding into plaintext original locals.
  - Required evidence: bytecode inspection of the mismatch accumulator fixture shows equality branches use encoded comparison material rather than plaintext accumulator loads immediately before the branch.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`
  - Completion criteria: accumulator oracle fixture still executes correctly and inspection proves encoded equality comparison.
  - Completion evidence: targeted integration test passed; bytecode audit found encoded `ILOAD shadow`/`ILOAD mask` plus `IF_ICMPEQ/IF_ICMPNE` pattern for accumulator zero comparison.

- [ ] 5. Full JVM compatibility validation and docs.
  - Scope: document the transform, add config examples, and run focused existing JVM compatibility tests.
  - Required evidence: targeted runtime-variable test plus existing CFF/indy/constants/strings/method-parameter JVM tests pass freshly.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
  - Completion criteria: docs match implementation and all targeted tests pass with the same enabled transform set.
  - Current evidence: docs/config entries were updated. Broader validation is temporarily blocked before test execution because an unrelated running Gradle native performance task in the same checkout holds `neko-test/build/test-results/test/binary/.fuse_hidden...`, causing Gradle to fail deleting the binary test-results directory.

## Constraints

- Do not alter native/runtime paths or existing dirty native todo files.
- Do not add JVM runtime helper classes.
- Do not use JNI, JVMTI, fallback execution, original-bytecode fallback, or skip-on-error behavior.
- Do not encode raw object pointers; reference protection must remain JVM verifier and GC safe.
