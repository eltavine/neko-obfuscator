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
  - Dependency status: blocked by subtask 5. The broader gate has produced a
    fresh independent full-JVM runtime failure after subtasks 2 and 3. Subtask
    4 must remain open until that runtime path is fixed and the broader gate is
    rerun.
  - Current evidence: the targeted generic-signature integration test passes,
    but the broader full-JVM gate is not complete. Fresh execution of
    `JvmFullObfuscationPerfTest` now runs past the prior relocated-helper
    `IllegalAccessError` and fails with `TEST full-obf missing baseline row
    Test 2.4: Field PASS`; stdout contains `Test 2.4: Field ERROR` and stderr
    is empty. Reverting the CFF relocation access fix reproduces the earlier
    `IllegalAccessError`, proving that fix is still required. Temporarily
    reverting the generic-signature cleanup does not change the `Field ERROR`,
    so the remaining failure is a separate full-JVM reflective method-key path,
    not the packed generic-signature invariant fixed by subtask 3.
  - Current evidence after subtask 5: the TEST full-obf fixture now prints
    `Test 2.4: Field PASS`, proving the reflective literal-null method lookup
    verifier failure is fixed for that path. The broader gate remains open
    because the same fresh `JvmFullObfuscationPerfTest` run now reaches
    `obfusjack full-obf` and fails before main-class initialization with
    `VerifyError: Bad local variable type`, location
    `a/a.main([Ljava/lang/String;)V @16291: aload`, reason `Type top (current
    frame, locals[155]) is not assignable to reference type`. That is a later
    independent verifier/local-frame failure and is not marked complete by
    subtask 5.

- [x] 5. Full-JVM reflective no-arg method lookup/key binding.
  - Scope: fix the generic reflective method lookup and invocation key-transfer
    path when the original lookup parameter-type array is the JVM literal
    `null`, while method-parameter packing, key dispatch, CFF,
    string/constant obfuscation, invokedynamic obfuscation, and renaming are all
    enabled. The fix must apply to reflective packed method targets by
    transform invariant, not to the TEST fixture, `FTest`, `FObject`, `add`,
    `Field`, or any mapped obfuscated name.
  - Dependency: starts after subtask 4 records the fresh broader-gate failure
    as an independent runtime path. This is the prerequisite needed to unblock
    subtask 4, not a completion claim for subtask 4.
  - Failing invariant: a reflectively invoked application method whose final
    executable ABI is packed and keyed must have a verifier-valid reflective
    lookup rewrite and must receive the same dynamically materialized target
    method key that a direct packed call would receive. When the source lookup
    uses literal `null` for an empty parameter-type array, generated lookup
    code must not leave a reachable verifier frame where the `null` type flows
    into `arraylength` or array copy. The reflective lookup descriptor,
    reflective invocation argument array, and packed carrier key slot must stay
    linked to the final obfuscated target method seed through key dispatch,
    method-parameter packing, CFF relocation, and invokedynamic reference
    obfuscation.
  - Investigation evidence from the current stale artifact only: the
    post-failure artifact maps `pack/tests/reflects/field/FObject.add()V` to
    packed target `a.aa.c([Ljava/lang/Object;)V`, and caller
    `a.ba.a([Ljava/lang/Object;)V` is itself packed as
    `([Ljava/lang/Object;)V`. Bytecode inspection of that caller shows the
    reflective lookup is rewritten to a single `Object[].class` parameter,
    creates an inner packed carrier, stores a generated hidden-key value into
    that carrier through the CFF key-transfer material path, stores the carrier
    inside the outer `Method.invoke` argument array, and then invokes the
    obfuscated `Method.invoke` site. A stale diagnostic run with
    `java -XX:-UsePerfData -Xlog:exceptions=info -jar
    build/test-jvm-full-obf-perf/test-obf.jar` records the thrown exception
    behind `Test 2.4: Field ERROR` as `VerifyError: Bad type on operand stack
    in arraylength`, location `a/ba.a([Ljava/lang/Object;)V @5730`, with stack
    `{ null }`. The corresponding bytecode is the generated reflective lookup
    copy-existing-parameters path that loads the saved parameter array local and
    executes `arraylength`. Source inspection shows
    `JvmKeyDispatchPass.rewriteMethodLookup` emits that copy-existing branch
    even when `literalNoArgMethodLookup` has already proven the source
    parameter-types operand is literal `ACONST_NULL`; after CFF/frame
    regeneration that branch has a verifier frame where the local is exact
    `null`. Reverting the generic-signature cleanup does not change this
    failure, while reverting the CFF relocation access fix brings back the
    earlier `IllegalAccessError`.
  - Required evidence before implementation: before editing executable code,
    regenerate or otherwise freshly confirm the failure on the current source
    tree and record that the current artifact still throws `VerifyError: Bad
    type on operand stack in arraylength` on the reflective field row, with
    bytecode showing a literal-null reflective parameter-type operand feeding a
    verifier-reachable generated `arraylength`/copy-existing path. If the fresh
    artifact shows a different exception or bytecode invariant, update this
    subtask before implementation. With that fresh confirmation, the root cause
    is the generic mismatch between a literal-null reflective parameter-type
    operand and the key-dispatch lookup rewrite that still emits a
    verifier-reachable `arraylength`/copy path for an existing array. The
    implementation must preserve the dynamic path for non-null or unknown
    arrays and only use the null-specialized construction when the bytecode
    operand is statically literal `ACONST_NULL`.
  - Fresh pre-implementation evidence: after permission, a fresh
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
    run regenerated `build/test-jvm-full-obf-perf/test-obf.jar` and failed with
    `TEST full-obf missing baseline row Test 2.4: Field PASS`; fresh stdout has
    `Test 2.4: Field ERROR` and stderr is empty. Running the fresh artifact with
    `java -XX:-UsePerfData -Xlog:exceptions=info:file=build/test-jvm-full-obf-perf/TEST.full-obf.fresh.exceptions.log -jar build/test-jvm-full-obf-perf/test-obf.jar`
    records `VerifyError: Bad type on operand stack in arraylength`, location
    `a/ba.a([Ljava/lang/Object;)V @6011`, reason `Invalid type: 'null'
    (current frame, stack[0])`. `javap -c -p a.ba` on the fresh artifact shows
    the generated path at `6006: aload 7`, `6008: checkcast #479 // class null`,
    `6011: arraylength`, proving the current source still emits a
    verifier-reachable copy-existing-parameter-array path for a literal-null
    reflective method lookup.
  - Required inspection after implementation: inspect the freshly regenerated
    full-JVM TEST artifact and prove the changed reflective path still looks up
    the final obfuscated packed descriptor, still invokes through the nested
    packed carrier, still materializes the target hidden key from live caller
    key state, has no `arraylength`/copy-existing path fed by verifier-exact
    `null`, and does not expose original application method names, descriptors,
    raw method keys, skip markers, or fallback/original-bytecode behavior.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
    after permission to use the repository Gradle wrapper.
  - Completion criteria: a freshly regenerated full-JVM TEST artifact prints
    `Test 2.4: Field PASS`; the broader `JvmFullObfuscationPerfTest` is rerun
    and any later independent failures are recorded before subtask completion.
    The implementation must not skip reflection, preserve original descriptors,
    expose raw keys/plain reflective metadata, or add fallback behavior.
  - Completion evidence: implemented a generic literal-null parameter-type
    specialization in `JvmKeyDispatchPass.rewriteMethodLookup`. When the source
    `Class.getMethod`/`getDeclaredMethod` parameter-types operand is statically
    `ACONST_NULL`, the generated lookup code now constructs the required
    one-slot `Class[]` directly instead of emitting the verifier-reachable
    copy-existing-array `arraylength` branch. Non-null and unknown arrays retain
    the existing dynamic append/copy path. Fresh validation with
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
    regenerated `build/test-jvm-full-obf-perf/test-obf.jar`; TEST full-obf
    stdout now contains `Test 2.4: Field PASS`. Fresh artifact inspection of
    `a.ba.a([Ljava/lang/Object;)V` shows the reflective method lookup still
    uses the final packed target descriptor via a single `java/lang/Object`
    array class parameter and an obfuscated invokedynamic
    `(Class,String,Class[],J)Method` site, while the later reflective invoke
    still uses nested packed carrier arrays and an obfuscated
    `(Method,Object,Object[],J)Object` site. The old literal-null
    `arraylength` site is absent from that lookup path. The broader gate was
    rerun and now records the later independent obfusjack verifier failure
    under subtask 4.

## Constraints

- Do not modify native/runtime paths.
- Do not add Java runtime helper classes.
- Do not skip renamer, method parameter obfuscation, CFF, key dispatch, string,
  constant, or indy transforms to make the fixture pass.
- Do not preserve original application descriptors or generic signatures when
  the executable ABI has been changed.
- Do not create files or folders under `/tmp`.
- Ask for permission before using the repository `./gradlew`.
