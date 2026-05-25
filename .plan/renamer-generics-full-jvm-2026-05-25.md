# Renamer / Full JVM Generic Signature Compatibility - 2026-05-25

## Evidence chain

- Failing invariant under investigation: JVM generic `Signature` attributes must
  stay structurally compatible with the final class, field, and method ABI after
  renamer and full JVM obfuscation.
- Runtime path under investigation: renamer remaps application classes and
  members before full JVM passes; `methodParameterObfuscation` later rewrites
  eligible application method descriptors to packed `Object[]` carrier
  descriptors.
- Concrete source observation before implementation: `JvmMethodParameterObfuscationPass`
  assigns `mn.desc = plan.packedDesc()` for eligible methods and
  `cleanupParameterMetadata` clears parameter metadata, but it does not clear or
  rewrite `mn.signature`. A generic method whose descriptor is packed while its
  generic signature still describes the original parameter list can produce an
  invalid or misleading classfile ABI.
- Fresh regression observation before implementation: running
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmGenericSignatureObfuscationIntegrationTest`
  generated `build/tmp/neko-test-generics/generic-shapes-full-jvm.jar` and
  failed at runtime with `IllegalAccessError: class z.a$k1hywi tried to access
  private field z.a.b`. `javap -p z.a` showed `z.a.b` is a private synthetic
  static `Object[]` material field, and `javap -c -p z.a$k1hywi` showed
  relocated CFF helper methods issuing `getstatic z/a.b:[Ljava/lang/Object;`.
- Fresh signature observation from the same generated artifact: `javap -v -p
  z.a` showed method `z.a.a` has final descriptor
  `([Ljava/lang/Object;)Ljava/lang/String;` while retaining signature
  `<T::Ljava/lang/CharSequence;R:Ljava/lang/Number;>(Ljava/util/List<+Lz/b<TT;>;>;Lz/c<TT;TR;>;Ljava/util/function/Function<-TT;Ljava/lang/String;>;)Ljava/lang/String;`.
- Required architectural fix: generic signature metadata must be handled
  generically at every ABI-changing JVM transform boundary, and relocated
  generated helper classes must not retain bytecode references that violate JVM
  member access rules. The fix must not special-case a fixture, preserve
  original descriptors for compatibility, skip transforms, or add fallback
  execution.

## Subtasks

- [x] 1. Generic full-JVM regression fixture and evidence.
  - Scope: add a focused test class that builds a generic application fixture,
    runs renamer-only and full JVM obfuscation, executes the fresh artifacts,
    and inspects generic signatures on the full-obfuscated output.
  - Required evidence: the fixture includes generic class inheritance, a generic
    method with an eligible non-ABI descriptor, bridge/erasure-sensitive calls,
    and reflective generic API access; the pre-fix failure or stale signature
    observation identifies the exact method/signature invariant.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmGenericSignatureObfuscationIntegrationTest`
  - Completion criteria: the test exists, a fresh generated artifact produces
    either an observed runtime failure or an inspected stale method
    descriptor/signature mismatch before the implementation fix, and the
    fixture is not tied to a sample jar, log string, or benchmark artifact.
  - Completion evidence: targeted Gradle test was run fresh and failed on the
    generated full-JVM artifact with `IllegalAccessError` from relocated helper
    `z.a$k1hywi` accessing private synthetic field `z.a.b`; `javap` inspection
    also found packed method `z.a.a([Ljava/lang/Object;)Ljava/lang/String;`
    retaining the original generic method signature.

- [x] 2. Relocated CFF helper access fix.
  - Scope: implement a generic fix for large CFF helper relocation so relocated
    helper classes cannot access private members on the original owner.
  - Dependency: starts after subtask 1 checkpoint records the fresh runtime and
    signature evidence.
  - Required evidence: changed code applies to relocated helper access
    invariants, not to `z.a`, `b`, or this fixture; generated full-JVM artifact
    no longer has relocated-helper bytecode that violates JVM access rules
    through private owner-member references.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmGenericSignatureObfuscationIntegrationTest`
  - Completion criteria: the fresh test progresses past the prior
    `IllegalAccessError`, or the output inspection proves no cross-class
    relocated helper references private owner members if a later independent
    failure appears.
  - Completion evidence: after the generic relocation fix, the same targeted
    test no longer fails at runtime. It progresses to the independent stale
    generic-signature assertion. `javap -v -p z.a$k1hywi` shows the relocated
    helper host has `NestHost: class z/a`, making the existing private material
    references legal on the Java 21 fixture path; the implementation also
    relaxes only actually referenced synthetic static owner members for
    pre-nestmate classfile versions.

- [x] 3. Generic signature metadata fix.
  - Scope: implement a generic fix for methods whose JVM descriptors are changed
    by full JVM parameter packing so their generic signatures cannot reference
    the old parameter ABI.
  - Dependency: do not start this subtask until subtask 2 removes the earlier
    relocated-helper `IllegalAccessError` runtime blocker.
  - Required evidence: changed code applies by transform invariant, not by
    owner/name/descriptor; full-obfuscated generic output has no method
    signature incompatible with its final descriptor.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmGenericSignatureObfuscationIntegrationTest`
  - Completion criteria: fresh regression test passes and output inspection
    proves packed methods do not retain original generic method signatures.
  - Completion evidence: targeted Gradle test passed fresh after clearing
    stale method signatures on all packed methods, including abstract/interface
    methods processed during planning. `javap -v -p z.b` shows packed method
    `c([Ljava/lang/Object;)Ljava/lang/CharSequence;` has no method `Signature`
    attribute, while the class-level generic signature remains remapped and
    valid.

- [ ] 4. Existing renamer/full-JVM compatibility validation.
  - Scope: run existing renamer and full JVM compatibility tests that cover
    interactions with generated helper renaming and full JVM runtime execution.
  - Required evidence: freshly executed target tests pass after the generic
    signature fix.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`
  - Completion criteria: no stale output is used; failures are investigated
    before any completion claim.

## Constraints

- Do not modify native/runtime paths.
- Do not add Java runtime helper classes.
- Do not skip renamer, method parameter obfuscation, CFF, key dispatch, string,
  constant, or indy transforms to make the fixture pass.
- Do not preserve original application descriptors or generic signatures when
  the executable ABI has been changed.
- Do not create files or folders under `/tmp`.
- Ask for permission before using the repository `./gradlew`.
