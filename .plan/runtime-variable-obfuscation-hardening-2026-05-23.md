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
  - Evidence: focused runtime-variable integration test passed through a
    manual runner using freshly compiled classes because the Gradle `test`
    task was blocked by an unrelated locked
    `neko-test/build/test-results/test/binary` directory.

- [ ] Subtask 2: reference encrypted handles with ThreadLocal frame
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

## Constraints

- No special-casing of any jar, class, method, descriptor, log, or test
  artifact.
- No native path, JNI fallback, helper class layer, original-bytecode fallback,
  or skip-on-error behavior.
- Keep live CFF/method key material semantically involved in masks and handles.
- Commit each completed subtask with its matching checkbox update before
  starting the next subtask.
