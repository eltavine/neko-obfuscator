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
- [-] Subtask 4: Optimize full-obf JVM runtime performance without weakening CFF.
  - Scope: use the Subtask 3 full-obf perf baseline as evidence and implement a
    generic performance fix inspired by the ZKM notes: preserve full CFF
    coverage, flowkey/key-table binding, lazy cache/self-patching behavior, and
    all transforms, while reducing measured runtime overhead.
  - Required evidence: fresh baseline report shows `TEST` original Calc at
    13 ms and full-obf Calc at 155 ms; `obfusjack` original matrix Seq at 3 ms
    and full-obf matrix Seq at 4709 ms. The first optimization target must be a
    concrete repeated runtime path proven by bytecode/source inspection, not a
    config downgrade, sample skip, CFF block merge, CFF block-boundary change,
    or transform disable.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`
  - Completion criteria: full-obf TEST and obfusjack still run from freshly
    generated jars, fresh perf report records TEST Calc at 80 ms or less,
    obfusjack matrix `Seq` at 200 ms or less, and obfusjack `Virtual` at 40 ms
    or less. CFF audits must still prove live key/table token decoding, and the
    implementation must not reduce transform coverage, CFF block granularity,
    key propagation, or wrong-key pollution behavior.
  - Implementation evidence: optimized the repeated runtime token-mask decode
    path only. The generated path still loads the per-class CFF key table,
    indexes it from live guard/path/block state, mixes a live-state digest, and
    xors the encrypted token with the derived mask. The change removes repeated
    shift/duplicate avalanche bytecode from every token decode and replaces the
    table index with a shorter live-state multiply/add expression; it does not
    change CFF block construction, coverage, key propagation, transform
    enablement, fallback behavior, or sample selection.
  - Partial validation result: passed
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`.
    Fresh report
    `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json`
    recorded full-obf obfusjack `Seq` improving from the Subtask 3 baseline
    4709 ms to 4261 ms, and `Virtual` improving from 60 ms to 52 ms. TEST Calc
    remained in the same range at 157 ms versus the Subtask 3 baseline 155 ms.
    This is not complete under the revised user thresholds.
  - Current hot-path evidence: fresh `javap` inspection of the generated
    full-obf jars shows obfusjack `a.a` contains 518 generated transition
    helper methods and 518 `invokestatic (JIIIII[I)J` transition calls; TEST
    `a.b` contains 161 of the same helper/call pairs. This identifies the
    current transition outliner call plus shared `int[]` write/read protocol as
    the dominant generic CFF runtime overhead to optimize before any string or
    invokedynamic cache work.
  - Additional hot-path constraint: method-parameter obfuscation must remain
    enabled and must continue to use the packed `Object[]` carrier. Fresh
    `javap` evidence shows matrix benchmark methods are protected as packed
    `Object[]` entries, for example full-obf obfusjack uses generated
    `static void ea(java.lang.Object[])` for a `[[D, [[D, [[D, long` target.
    The next optimization therefore must keep the `Object[]` ABI and hidden key
    transfer, while reducing generic carrier allocation overhead.
  - Rejected implementation evidence: a generic ThreadLocal `Object[]` carrier
    reuse attempt preserved the packed ABI but increased generated callsite
    bytecode enough that fresh obfusjack full-obf writing failed with
    `MethodTooLargeException: Method too large: a/a.main([Ljava/lang/String;)V`
    and `main` estimated at 86,367 bytes. That approach is not a valid
    architecture fix and was reverted.
  - Rejected implementation evidence: changing normal CFF transition helpers
    from `int[] out` state return to a single returned method key preserved live
    key pollution, but caller-side recovery of guard/path/block/pc/domain
    expanded generated methods too much. Fresh TEST full-obf writing failed
    with `MethodTooLargeException: Method too large: a/y.run(J)V`. That
    approach is not a valid size-safe architecture fix and was reverted.
  - Current implementation target: CFF transition and island-dispatch helpers
    already receive the live method key as local 0 and compute keyed
    guard/path/block/pc/domain transitions. Pipeline hardening currently sees
    these helpers as missing `controlFlowFlattening.flowKeyLocalByMethod` and
    re-runs CFF over hundreds of CFF-generated helpers (`methods=911` in the
    Subtask 3 obfusjack run). Publish the existing helper flow-key local so
    helper hardening distinguishes truly unkeyed generated helpers from
    CFF-generated keyed transition helpers.
  - Additional fresh validation result: after publishing CFF helper flow keys
    and shortening method-key reconstruction, the full validation command
    passed but the fresh report still missed the user thresholds: TEST Calc
    141 ms, obfusjack matrix `Seq` 3981 ms, and `Virtual` 53 ms. The current
    change is therefore not complete.
  - Next hot-path evidence: generated obfusjack matrix bytecode contains
    repeated field/method invokedynamic sites in the numeric loops. The
    `MutableCallSite` path already self-patches after first resolution, but the
    appended runtime long is still computed at every call site. Its flow helper
    currently performs five runtime calls to the splitmix helper before using
    live guard/path/block/pc state, method salt, and the CFF class key table.
    Replace that heavy runtime salt derivation with a lightweight live-state
    mix that keeps the same key sources and class-table dependency, without
    changing callsite descriptors, resolver behavior, Object[] carriers,
    transform enablement, or CFF coverage.
  - Additional fresh validation result: lightweight invokedynamic runtime-long
    derivation passed the full validation command and improved TEST Calc from
    141 ms to 126 ms and obfusjack `Seq` from 3981 ms to 3835 ms, but still
    missed all revised thresholds. Fresh `javap` evidence for the packed matrix
    method shows no outlined CFF transition calls in that method, 18 dispatcher
    switches, and only 2 invokedynamic sites. The remaining hot path is the
    inlined CFF transition algebra itself, especially per-edge method-key
    reconstruction and token masking repeated inside numeric loops.
  - Rejected implementation evidence: replacing method-key reconstruction with
    a shorter high/low int mix preserved correctness and passed the full
    validation command, but fresh performance regressed to TEST Calc 147 ms,
    obfusjack `Seq` 3950 ms, and `Virtual` 53 ms. That formula is not retained.
    The next implementation target is the dispatcher token mask, which is
    executed at every CFF switch and can be shortened while still depending on
    live guard/path/block state, the live method key, the current pc token, and
    per-site seed material.
  - Rejected implementation evidence: skipping domain-token recomputation for
    same-island transitions broke fresh CFF runtime validation. The audit and
    full-obf TEST jar both failed with `IllegalStateException`, proving domain
    refresh is semantically part of the current wrong-key pollution and
    dispatcher state chain. That path was reverted and is not retained.
  - Rejected implementation evidence: removing the final avalanche from
    transition encrypted-token masks (`emitEncryptedToken` and
    `emitControlTokenMaskFromBase`) also broke fresh runtime validation with
    early `IllegalStateException` in the CFF audit and full-obf TEST jars. That
    mask shape participates in collision avoidance/correct token recovery and
    was reverted.
  - Additional implementation evidence: bundled per-edge route token
    materialization into a single target route base. The route base is derived
    from target guard/path/block plus the per-class CFF key table, then decodes
    pc and domain tokens from token-specific masks. This keeps class-context
    token decoding and wrong-key pollution while avoiding two full class-table
    token decoders per transition. Fresh validation passed; the best retained
    report in this round recorded TEST Calc 132 ms, obfusjack `Seq` 3110 ms,
    and `Virtual` 47 ms. This remains incomplete under the revised thresholds.
  - Additional implementation evidence: target method keys are now decoded from
    the live source transition base rather than reconstructed only from target
    state arithmetic. This keeps method key transfer tied to live source
    guard/path/block/method state and avoids the previous long multiply
    reconstruction path. A fixed nonzero rescue was removed from this decoded
    path so wrong keys remain polluted instead of being normalized to a static
    fallback constant.
  - Additional implementation evidence: dispatcher token masks now use the
    active CFF guard/path/block state and pc token directly instead of folding
    the materialized long method key at every island switch. The method key
    remains dynamically transferred for downstream consumers, but switch
    dispatch no longer pays repeated `long` high/low extraction.
  - Additional implementation evidence: direct-island transitions no longer
    materialize a domain token that cannot be read before the direct island
    dispatcher jump. Hub and alias transitions still materialize domain tokens.
    This is narrower than the rejected same-island skip and passed fresh
    validation.
  - Rejected implementation evidence: a rolling stream decoder for
    guard/path/block words wrote verifiable classes but fresh runtime execution
    entered the CFF poison path with `IllegalStateException`, proving the
    rolling mask was incompatible with the current transition recovery chain.
    It was reverted.
  - Rejected implementation evidence: lazy method-key materialization that
    removed method-key input from the compact transition base also entered the
    CFF poison path in fresh runtime validation. The current transition chain
    still requires every edge to update method key state, so that lazy
    materialization attempt was reverted.
  - Additional implementation evidence: moved the class-table dependency into
    the shared transition base and decoded pc/domain/method key from that
    class-bound source base. This removed the extra target route-base table
    path while preserving class context and live source key dependency for all
    transition words. Fresh validation passed; the retained report recorded
    TEST Calc 132 ms, obfusjack `Seq` 3143 ms, and `Virtual` 47 ms.
  - Next hot-path evidence: fresh generated obfusjack bytecode still contains
    hundreds of CFF-generated transition helpers with descriptor
    `(JIIIII[I)J`. Each helper call writes guard/path/block/pc/domain into a
    shared `int[]`, and island dispatch also writes a result token; the caller
    then performs matching `iaload` recovery before the next dispatcher step.
    This is a generic CFF helper protocol cost, not a fixture-specific path.
    The next implementation target is to compact the helper return protocol
    from six `int` slots to three packed `long` slots while preserving the same
    live state values, helper call graph, CFF coverage, dynamic key transfer,
    and `Object[]` method-parameter carrier.
  - Additional implementation evidence: compacted the generic CFF helper return
    protocol to `(JIIIII[J)J` with three packed `long` slots. Fresh validation
    passed and generated bytecode now shows `newarray long`, `laload`, and
    `(JIIIII[J)J` helper calls. The retained report recorded TEST Calc
    135 ms, obfusjack `Seq` 3015 ms, and `Virtual` 43 ms, so this is correct
    but still incomplete.
  - Next hot-path evidence: the generated matrix hot method still executes
    repeated indy runtime-flow derivation immediately before numeric
    invokedynamic sites. The callsites are already `MutableCallSite`
    self-patching; after first resolution the target drops the appended runtime
    `long`, but the caller still recomputes that `long` on every loop
    iteration. Implement a generic per-method callsite flow-word lazy cache:
    first execution derives the word from live CFF guard/path/block/pc,
    method salt, site seed, and the class key table; subsequent executions of
    the same callsite load the cached word. This preserves the original first
    resolution key source and all transforms while removing repeated hot-loop
    key-schedule work.
  - Additional implementation evidence: caching every indy site passed fresh
    validation and improved obfusjack `Seq` to 2825 ms, but generated bytecode
    showed hundreds of method-entry `lstore` initializers for non-loop
    callsites. `Virtual` stayed above threshold at 47 ms. The cache scope must
    therefore be architectural and loop-aware: apply lazy flow-word caching only
    to callsites inside generic backward-edge loop regions, leaving one-shot
    callsites on the original live derivation path.
  - Additional implementation evidence: loop-aware indy flow-word caching and
    per-class numeric lazy cache tables passed the full validation command.
    The fresh report recorded TEST Calc 127 ms, obfusjack `Seq` 2977 ms, and
    `Virtual` 36 ms. `Virtual` is now under the user threshold, but `Seq`
    remains dominated by the CFF loop body rather than indy or constant
    helpers.
  - Next hot-path evidence: fresh `javap -verbose` inspection of generated
    packed matrix methods shows CFF-protected loop methods with code bodies
    around or above HotSpot's huge-method compilation budget, for example
    generated `g(Object[])` ends near bytecode offset 8280 and `ea(Object[])`
    near 16673, while `javap -c` shows no `(JIIIII[J)J` transition-helper
    calls inside those methods. The existing transition outliner is therefore
    not applied to loop-heavy methods that need it for JIT-sized bodies. The
    next implementation target is a generic loop/JIT-size-aware transition
    outliner rule that moves equivalent keyed transition algebra to generated
    helpers without changing block construction, block selection, Object[]
    carriers, transform enablement, or dynamic key propagation.
  - Rejected implementation evidence: adding a projected-size gate on top of
    backward-edge detection passed validation, but fresh `javap` still showed
    no `(JIIIII[J)J` transition-helper calls inside generated matrix loop
    methods. The fresh report recorded TEST Calc 111 ms, obfusjack `Seq`
    3014 ms, and `Virtual` 43 ms. That rule did not exercise the intended
    runtime path and is not sufficient.
  - Additional implementation evidence: applying transition outliner to every
    backward-edge CFF method passed validation and exercised the intended
    runtime path. Fresh `javap` showed 790 `(JIIIII[J)J` helper calls overall
    and 38 inside generated matrix `ea(Object[])`; obfusjack `Seq` dropped to
    202 ms and `Virtual` to 26 ms. TEST Calc regressed to 131 ms because small
    hot loops/recursive methods also paid helper-call overhead. The next rule
    must keep the loop architecture but gate it by the existing generic
    dispatcher code-pressure budget so small loop methods stay inline while
    large loop bodies are outlined.
  - Rejected implementation evidence: gating loop transition outlining by the
    existing dispatcher code-pressure budget passed validation and still
    exercised matrix transition helpers, but obfusjack `Seq` regressed to
    264 ms and TEST Calc stayed at 130 ms. The remaining generic hot cost is
    method-parameter carrier packing: recursive and loop callsites still box
    the live hidden key as `Long` inside the `Object[]` carrier. The next
    implementation target keeps `Object[]` as the carrier for original
    arguments, but transfers the hidden live key as a primitive trailing
    `long` in the packed ABI so key propagation remains dynamic without per-call
    `Long` allocation or an extra carrier slot.
  - Current verifier evidence: the first primitive-key ABI implementation
    compiled but failed fresh TEST full-obf writing before any runtime report
    update. The verifier rejected generated class `a/l` with `Error at
    instruction 0: Expected J, but found .`; the largest generated methods
    include split descriptors such as `g([Ljava/lang/Object;J)Ljava/lang/String;`.
    This proves the rewritten method entry descriptor expects a primitive
    `long` parameter, but the prologue/local layout did not reserve the new
    descriptor argument slots before allocating temporary locals. The fix must
    be generic: reserve the packed descriptor's argument locals first, then
    allocate unpack temporaries, while keeping the `Object[]` carrier and
    primitive live-key transfer.
  - Follow-up verifier evidence: reserving only the packed descriptor argument
    slots did not change the fresh failure; the verifier still rejected `a/l`
    at instruction 0 with `Expected J, but found .`. The split ABI removes the
    original hidden-key parameter from the formal descriptor, while downstream
    keyDispatch/CFF metadata still intentionally uses the recorded hidden-key
    local as the live method-key slot after the prologue mixes the incoming
    primitive key. Therefore the rewritten method must also reserve
    `plan.keyLocal()+2` as an internal live-key local before later passes
    allocate CFF locals.
  - Exact verifier evidence: temporary detailed verifier output identified the
    failing method as `g([Ljava/lang/Object;J)Ljava/lang/String;` with entry
    bytecode `LLOAD 5; LSTORE 5` while local 5 was uninitialized. The method
    parameter prologue had already been marked as generated before
    `rewriteFirstKeyDispatchLoad` scanned generated keyDispatch nodes, so when
    the old hidden-key local and the new trailing primitive-key local both used
    slot 2, the scanner rewrote the prologue's own `LLOAD 2` to the temp slot.
    The generic fix is to insert the unpack prologue unmarked, rewrite the
    pre-existing keyDispatch generated nodes, and only then mark the unpack
    prologue as generated for later CFF/string/indy passes.
  - MethodHandle ABI evidence: after fixing the generated-node ordering, TEST
    full-obf wrote and ran, but obfusjack failed during CFF frame analysis of
    `main([Ljava/lang/String;)V` with `AnalyzerException: Illegal LDC value J`
    at instruction 758. The split MethodHandle lookup rewrite emitted
    `LDC Type.LONG_TYPE` while constructing `MethodType(Object[].class,
    long.class)`. Primitive class literals are not valid `LDC` class constants
    in bytecode; the correct JVM ABI form is `GETSTATIC java/lang/Long.TYPE`.
  - Reflection ABI evidence: after fixing primitive class constants, obfusjack
    wrote and began running but failed at a reflective lookup with
    `NoSuchMethodException: a.a.hiddenAdd([Ljava.lang.Object;)`. Reflection
    lookups and `Method.invoke` had already been generically rewritten by
    keyDispatch to carry a boxed live key inside the argument array; without a
    target-specific bridge layer, method-parameter obfuscation cannot recover a
    primitive trailing `long` for every reflective target from the erased
    `Method` object. Reflection-keyed entries must therefore keep the existing
    boxed-key `Object[]` ABI, while direct and MethodHandle call paths can use
    the primitive split ABI. This preserves method-parameter obfuscation,
    Object[] carriers, live key propagation, and reflection compatibility.
  - Additional key-index evidence: keyDispatch may insert the hidden key at a
    lambda-specific parameter index, not only at the end. Split and boxed
    carrier packing must use the recorded hidden-key local/index instead of
    assuming the final argument is the key.
  - Follow-up reflection evidence: the same `hiddenAdd` reflective target was
    not covered by keyDispatch's existing reflective-entry marker because the
    source lookup uses explicit `int,int` parameter types rather than the
    no-argument lookup shape recorded there. Generic literal
    `Class.getMethod/getDeclaredMethod` owner/name targets must also be treated
    as reflection ABI targets and kept on the boxed-key `Object[]` carrier.
  - Virtual ABI evidence: after preserving literal reflection targets, obfusjack
    progressed past `hiddenAdd` but failed with `AbstractMethodError: Missing
    implementation of resolved method 'abstract void pa(java.lang.Object[])'`.
    This proves primitive split was applied to a concrete virtual method while
    an abstract/interface family declaration kept the boxed `Object[]`
    descriptor. Descriptor ABI changes for virtual dispatch must be family-wide;
    until family-wide split metadata exists, primitive split is safe only for
    closed dispatch entries. A follow-up run still failed on the same abstract
    family after allowing final/final-class methods, proving a final
    implementation can still satisfy an inherited abstract/interface slot.
    Until family-wide split metadata exists, the safe split set is static and
    private methods only. Other virtual/interface/abstract paths keep the boxed
    `Object[]` live-key carrier.
  - Fresh performance evidence after the static/private split ABI fix: the full
    validation command passed. The report recorded obfusjack `Seq` at 182 ms
    and `Virtual` at 25 ms, both under the user thresholds, but TEST Calc was
    still 120 ms. Fresh `javap` shows Calc methods `m/k/l/n` use the split
    `([Ljava/lang/Object;J)V` descriptor, but hot callsites still allocate new
    `Object[]` carriers in the 10,000-iteration loop and the recursive private
    `call` path. The next generic target is carrier reuse that preserves the
    `Object[]` ABI and live primitive key transfer while avoiding repeated
    per-call carrier allocation on direct transformed callsites.
  - Carrier reuse compatibility evidence: enabling carrier reuse globally wrote
    verifiable classes, but obfusjack failed in the reflection path with
    `InvocationTargetException` caused by `IllegalStateException` inside
    reflected `hiddenAdd`. The class mixes direct packed calls and erased
    reflection `Method.invoke` key transfer, so carrier reuse must be disabled
    for classes containing reflection lookup/invoke sites. Non-reflection
    classes, such as the TEST Calc class, can still use the reusable internal
    carrier table.
  - Rejected implementation evidence: limiting carrier reuse to non-reflection
    classes passed the full validation command but regressed fresh TEST Calc to
    123 ms and obfusjack `Seq` to 196 ms. The `ThreadLocal` carrier lookup cost
    is not a useful generic optimization here and the path is not retained.
    Fresh `javap` of Calc still shows no transition helper calls in the
    recursive private `call` path, so the next target is generic recursion-aware
    transition outlining: self-recursive methods should outline equivalent CFF
    transition algebra to reduce hot recursive method body size without
    changing CFF block construction, coverage, or key propagation.
  - Rejected implementation evidence: recursion-aware transition outlining
    passed the full validation command and exercised the intended path in the
    recursive packed Calc method, but fresh performance regressed to TEST Calc
    140 ms while obfusjack remained under threshold (`Seq` 187 ms, `Virtual`
    24 ms). The helper-call overhead is too expensive for small recursive hot
    methods, so this path is not retained. Fresh `javap` of Calc shows repeated
    loop string/callsite work remains in `runAll`/`runStr`; the next target is
    a generic loop-local lazy cache for repeated protected constants/strings or
    carrier packing that keeps first-use live key derivation and the packed
    `Object[]` ABI.
  - Rejected implementation evidence: sharing a per-class empty `Object[]`
    carrier for zero-original-argument transformed callsites preserved the
    packed carrier ABI and passed the full validation command, but fresh TEST
    Calc stayed at 123 ms and obfusjack `Seq` regressed to 203 ms. The extra
    static carrier field path is not useful and is not retained. Fresh bytecode
    still shows the Calc hot path repeatedly paying invokedynamic/string helper
    work inside loops, so the next target remains a loop-local cache for
    protected string/indy helper results keyed by live CFF state.
  - Current hot-path evidence: fresh `javap` of Calc shows repeated CFF method
    key reconstruction blocks ending in `dup2; lconst_0; lcmp; ifne; pop2;
    ldc2_w fixed`. Valid generated block keys are already produced as non-zero
    method keys, while the zero rescue normalizes wrong state to a fixed static
    long. The next generic implementation removes that rescue from
    `emitMethodKeyFromDecodedState`, reducing every inline transition and
    preserving wrong-key pollution instead of replacing zero with a constant.
  - Rejected implementation evidence: removing the method-key zero rescue
    passed the full validation command but regressed fresh TEST Calc to 126 ms
    and obfusjack `Seq` to 205 ms. The path is not retained.
  - Current implementation target: prior full backward-edge transition
    outlining proved large loop methods benefit (`Seq` dropped near threshold),
    while recursion-aware outlining proved self-recursive hot methods regress.
    The next generic CFF rule outlines transition algebra for methods with
    backward CFG edges only when the method is not self-recursive, preserving
    CFF blocks/key propagation while avoiding helper-call overhead on recursive
    leaf hot paths.
  - Rejected implementation evidence: outlining all non-recursive backward-edge
    methods passed the full validation command, but fresh TEST Calc stayed at
    123 ms and obfusjack `Seq` regressed to 254 ms. This path is not retained.
  - Current implementation target: transformed methods already receive a
    synthetic packed `Object[]` carrier and immediately unpack it into real
    locals. After that prologue, the incoming carrier is no longer part of the
    original program state. Direct transformed callsites inside the same method
    can reuse that carrier when the target carrier width matches, overwriting
    all slots before dispatch. This is a generic method-parameter architecture
    optimization that preserves the packed `Object[]` ABI and live key transfer
    while avoiding repeated carrier allocation in recursion and loops.
  - Rejected implementation evidence: reusing the incoming packed carrier for
    same-width direct transformed callsites passed validation and removed
    recursive `Object[]` allocation in fresh Calc bytecode, but fresh TEST Calc
    stayed at 124 ms and obfusjack `Seq` regressed to 282 ms. This indicates
    the shared carrier shape harms JIT scalarization/escape behavior, so it is
    not retained.
  - Current implementation target: the remaining Calc hot path is a closed
    `static void` self-tail-recursive method after keyDispatch has inserted the
    live hidden key. A generic pre-CFF tail-recursion rewrite can store the
    recursive arguments back into the original argument locals and jump to the
    post-unpack body label. The packed `Object[]` entry ABI, hidden key local,
    and later CFF coverage remain intact, while the recursive method no longer
    pays per-step direct call packing/indy dispatch.
  - Fresh tail-recursion validation evidence: after aligning the generated
    loop header with the CFF protected region, the full JVM performance/audit
    command passed. The report captured at `2026-05-12T10:12:00Z` showed
    obfusjack `Seq: 185 ms` and `Virtual threads: 29 ms`, meeting the user
    thresholds for those rows, but TEST `Calc: 122 ms`, still above the
    required `80 ms`.
  - Continuing evidence for the remaining Calc failure: fresh `javap` on
    `build/test-jvm-full-obf-perf/TEST-full-jvm-obf.jar` shows the Calc
    `runStr` path still enters generated string-concat helper sites from a hot
    loop. The helper decodes embedded string constants through the string
    decode helper on every helper invocation, which means already cached
    strings still pay live-key/fingerprint/cache-check cost for every loop
    iteration. The next generic change is to add a caller-local lazy cache for
    loop string literals and to externalize string-concat constants from the
    helper into the caller-side decode path, so the first loop iteration
    remains live-key derived while later iterations reuse the local decoded
    value.
  - Fresh loop-string-cache validation evidence: the full validation command
    passed, proving verifier/runtime compatibility, but the report captured at
    `2026-05-12T10:21:29Z` still showed TEST `Calc: 118 ms` and obfusjack
    `Seq: 203 ms`; this path alone is insufficient.
  - Current implementation target: fresh Calc bytecode shows the CFF hot paths
    contain many small `lookupswitch` dispatchers with two to six real/decoy
    cases. For such small case sets, a keyed `if_icmpeq` chain preserves the
    exact same live masked token checks and fake targets while avoiding
    `lookupswitch` overhead. This is a generic dispatcher architecture
    optimization and does not change block construction, block granularity,
    transform coverage, or key propagation.
  - Fresh small-dispatch validation evidence: the full validation command
    passed, and fresh bytecode shows small token dispatchers lowered to
    `if_icmpeq` chains, but TEST `Calc` was still `123 ms`. The remaining
    generic hot cost is numeric constant cache checks in loops: the constant
    pass already uses class-level numeric cache arrays for loop sites, but
    every loop iteration still pays flag-array/value-array field loads. The
    next target is a method-local lazy numeric cache for loop constants:
    first use decodes from live CFF key material, then later iterations reuse a
    primitive local value. This preserves constant obfuscation and wrong-key
    pollution while removing repeated static cache probes from hot loops.
  - Rejected implementation evidence: the method-local lazy numeric cache
    inserted a per-site flag local at method entry. Fresh full validation
    failed before runtime with ASM verifier errors in generated transformed
    methods (`Expected I, but found .` at the cache flag load). The exact
    invariant is that CFF/transformed control-flow can reach the loop-site
    replacement along paths where the verifier cannot prove the new flag local
    was initialized. That implementation is not retained.
  - Current implementation target: keep numeric lazy caching generic but move
    the cache state to class-owned primitive arrays initialized in `<clinit>`.
    Loop numeric sites still decode from live CFF key material on the first
    use, then reuse cached primitive values through generated class fields.
    This removes the verifier-sensitive local flag while preserving numeric
    obfuscation, Object[] parameter packing, CFF coverage, and live key-driven
    first decode.
  - Fresh class-cache validation evidence: the full validation command passed;
    the report showed TEST `Calc: 129 ms`, obfusjack `Seq: 200 ms`, and
    `Virtual threads: 23 ms`. Seq and Virtual meet the requested thresholds,
    but Calc still exceeds `80 ms`, so this subtask remains open.
  - Current implementation target: fresh Calc bytecode shows the loop hot path
    now has several lazy cache probes for indy runtime long, loop strings, and
    numeric constants. The cache-hit path still emits a second unconditional
    `goto done` after the miss test (`lcmp/ifeq; goto done` or
    `ifnull; goto done`). The next generic change flips those probes so the
    hot cache-hit path branches directly to the loaded value and only the first
    miss path performs the extra jump. This preserves lazy first-use
    live-key derivation and only changes cache probe layout.
  - Fresh cache-probe validation evidence: the full validation command passed
    and bytecode now shows direct `ifne` cache-hit branches, but TEST `Calc`
    only moved to `128 ms`; obfusjack `Seq` stayed passing at `194 ms` and
    `Virtual threads` at `26 ms`. The remaining Calc hot loop still performs
    repeated synthetic Object[] carrier allocation at transformed direct
    callsites.
  - Current implementation target: keep the Object[] ABI mandatory, but make
    loop direct-call carrier allocation lazy per callsite. Each loop callsite
    owns one method-local carrier initialized to null; the first execution
    allocates the exact carrier width and later iterations overwrite every
    carrier slot before invoking the packed target. This preserves the Object[]
    carrier, hidden-key transfer, direct-call semantics, and full CFF/indy
    coverage while removing repeated hot-loop carrier allocation.
  - Fix evidence for the carrier-cache implementation: the first implementation
    left the cached Object[] on the operand stack at the lazy branch merge.
    Since method-parameter obfuscation runs before CFF, CFF rejected obfusjack
    with `cannot normalize divergent non-empty stack edge shapes` in a packed
    method. The corrected implementation makes the branch merge stack-empty
    and reloads the carrier from its local after the merge.
  - Additional fix evidence: the first empty-merge correction still left the
    newly allocated array on the miss edge before the shared reload, so ASM
    frame analysis rejected TEST `a/u.m([Ljava/lang/Object;J)V` with stack
    size `1`. The miss edge now stores the new array and leaves no value on
    the operand stack before the merge.
  - Rejected implementation evidence: the corrected loop direct-call carrier
    cache passed the full validation command and preserved the required
    `Object[]` carrier ABI, but the fresh report regressed obfusjack matrix
    `Seq` from the prior passing `194 ms` to `262 ms`. This proves the cached
    carrier shape harms the generic packed-call hot path, so it is not
    retained. The next implementation must reduce hot-loop overhead without
    reusing mutable callsite carriers.
  - Fresh rollback validation evidence: after removing the loop direct-call
    carrier cache, the full validation command passed again. The fresh report
    recorded TEST `Calc: 122 ms`, obfusjack `Seq: 186 ms`, and obfusjack
    `Virtual threads: 25 ms`. Seq and Virtual are back under the requested
    thresholds; Calc remains the active failing row.
  - Current implementation target: `rewriteStringConcat` now externalizes loop
    string constants into caller-local lazy caches, but still appends
    `guard/path/block/pc` state arguments to every generated concat helper.
    Source inspection shows helpers whose string constants were fully
    externalized do not call the string decoder internally, so those four CFF
    state arguments are dead helper ABI traffic on the hot concat path. The
    next generic change is to make concat helper descriptors include CFF state
    arguments only when the helper still performs internal string decoding.
    Caller-side cached string decoding remains live-key derived on first use,
    and CFF/string coverage is unchanged.
  - Rejected implementation evidence: conditional concat-helper state
    arguments passed the full validation command, but fresh performance
    regressed to TEST `Calc: 126 ms` and obfusjack `Seq: 253 ms`. The current
    helper descriptor shape appears to preserve better JIT linkage despite
    extra integer arguments, so the conditional ABI change is not retained.

## Updated User Acceptance Constraints - 2026-05-12

- TEST full-obf correctness: the freshly generated
  `build/test-jvm-full-obf-perf/TEST-full-jvm-obf.jar` must pass every TEST
  row except `Test 2.8: Sec`, which remains allowed to fail. The currently
  observed failing rows requiring concrete evidence before implementation are
  `2.1 Counter`, `2.4 Field`, `2.5 Loader`, `2.6 ReTrace`, and
  `2.7 Annotation`.
- Performance thresholds are tightened to TEST `Calc <= 50 ms`, obfusjack
  matrix `Seq <= 40 ms`, obfusjack `Parallel <= 20 ms`, obfusjack
  `VThreads <= 20 ms`, and obfusjack `Virtual threads <= 40 ms`.
- CFF strength constraints remain hard requirements: no CFF coverage
  reduction, no block coarsening, no key-update removal, no static key
  replacement, no hidden `long` removal, no `Object[]` carrier removal, no
  reflected-method exclusion, no transform disablement, no fixture-specific
  behavior, and no fallback/original-bytecode rescue path. Any performance
  improvement must keep or improve live key dispatch strength.
- Renamer constraints are now also hard requirements: application classes,
  methods, and fields must not be excluded from renaming for reflection,
  loader, annotation, method-parameter, invokedynamic, CFF, or known fixture
  compatibility. Method-parameter obfuscation and key dispatch must likewise
  stay enabled for reflection-visible methods; hidden `long` and packed
  `Object[]` ABI changes must remain in force. The only allowed preserved
  names/descriptors are real JVM or external ABI requirements such as
  constructors, class initializers, `main([Ljava/lang/String;)V`, and inherited
  external API override slots. Reflection compatibility must be achieved by
  generic literal/resource/metadata rewrites, reflective lookup descriptor
  rewrites, `Method.invoke` argument carrier construction, and generated-member
  filtering to the final obfuscated ABI, not by keeping application identifiers
  such as a private `add(Object[], long)` method name visible after obfuscation
  or by preserving an original application descriptor.
- ZKM reference recommendation: performance and strength work should use
  `/mnt/d/Code/Reverse/ZKM 26/ZKM.jar` as a comparative implementation
  reference when it can provide useful architecture evidence. The reference
  should be inspected through Recaf MCP reverse
  engineering of the loaded ZKM workspace, with notes focused on generic
  architecture evidence such as global invokedynamic tables, lazy caches,
  MutableCallSite self-patching, layered string pools, and contextual runtime
  key derivation. ZKM observations are advisory only: they must not
  justify fixture-specific branches, transform exclusions, static key exposure,
  fallback paths, or any weakening of CFF/key propagation.
- Reflection rewrite obfuscation constraint: any bytecode inserted to support
  reflection, MethodHandle, annotation, loader, or dynamic invocation
  compatibility is part of the protected application surface. Rewritten
  owner/name/descriptor/resource strings, parameter-type constants, seed
  material, branch predicates, and invoke adapters must still be eligible for
  CFF, constant obfuscation, string obfuscation, and invokedynamic
  obfuscation. A compatibility rewrite must not leave new plaintext strings or
  naked constants merely because the instructions were generated by an earlier
  pass; generated markers may only protect transform bookkeeping that would be
  invalid to reprocess, not skip obfuscation of user-visible reflective data.
  The reflection rewrite path therefore must be audited as a protected
  transform input, not as an unprotected compatibility layer.
- Parallel evidence collection: one explorer is assigned to TEST correctness
  failures, one explorer is assigned to full-JVM perf bottlenecks, and one
  explorer is assigned to optional ZKM reference analysis through Recaf MCP
  where available. Their outputs are advisory only; implementation still
  requires local evidence and fresh validation.

## Next Subtasks Under Updated Constraints

- [x] Subtask 4A: Fresh correctness evidence for TEST full-obf failures.
  - Scope: regenerate or inspect a fresh TEST full-obf artifact and identify
    exact failing invariants for `Counter`, `Field`, `Loader`, `ReTrace`, and
    `Annotation`, excluding only `Sec`.
  - Required evidence: stdout/stderr rows, generated class/method bytecode,
    and transform/runtime path proving the root cause for each failing row.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest` plus direct
    `java -jar build/test-jvm-full-obf-perf/TEST-full-jvm-obf.jar` inspection
    when needed.
  - Completion criteria: every TEST row except `2.8 Sec` passes in the freshly
    generated full-obf jar with the same enabled transform set.
  - Validation result: passed after the invokedynamic resolver-level reflection
    filter change. Fresh `TEST.full-obf.run.stdout.log` reports `Counter PASS`,
    `Field PASS`, `Loader PASS`, `ReTrace PASS`, and `Annotation PASS`, with
    only the allowed `Sec ERROR` remaining. The same gate command passed
    generation/JUnit.
- [-] Subtask 4B: Fresh performance evidence under the tightened thresholds.
  - Scope: measure and inspect TEST Calc plus obfusjack Seq/Parallel/VThreads
    after any correctness fix, then implement only generic architecture-level
    optimizations.
  - Required evidence: report rows and generated bytecode showing the dominant
    repeated runtime path before each change.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`.
  - Completion criteria: fresh report records TEST `Calc <= 50 ms`,
    obfusjack `Seq <= 40 ms`, `Parallel <= 20 ms`, `VThreads <= 20 ms`, and
    `Virtual threads <= 40 ms`, with CFF audits still proving live keyed
    dispatch.
- [x] Subtask 4C: Full renamer coverage without reflection pinning.
  - Scope: identify every generic renamer exclusion that keeps application
    member identifiers visible for reflection or transform compatibility, then
    replace those exclusions with metadata/literal/resource rewrites,
    reflective lookup descriptor rewrites, `Method.invoke` carrier
    construction, or generated-member filters. Standard JVM ABI and external
    inherited ABI signatures remain preserved; application methods do not keep
    original names or descriptors merely because reflection can observe them.
  - Required evidence: generated bytecode or decompiler output showing a
    non-ABI application identifier, for example a private transformed
    `add(Object[], long)` method, survives renaming; source evidence showing
    which renamer guard preserved it.
  - Current implementation target: source evidence shows `JvmRenamerPass` keeps
    reflection-pinned method names through
    `TransformGuards.isReflectionMethodNamePinned`, and
    `JvmMethodParameterObfuscationPass` excludes stack-introspection,
    reflection-shape-sensitive, and reflection-pinned methods from packed
    `Object[]` rewriting. Generated TEST evidence shows those exclusions and
    partial reflection rewrites leave a private `add(Object[], long)` name
    visible and leave reflection lookups targeting stale descriptors. The
    generic fix is to remove those reflection exclusions and rewrite reflection
    lookup/invoke sites to the final packed descriptor, including split hidden
    `long` targets.
  - Fresh post-edit evidence: the validation command passed compilation and
    generation, and `TEST.full-obf.run.stdout.log` changed `Test 2.4: Field`
    from `FAIL` to `PASS`, proving that final-name/final-descriptor reflective
    lookup plus split hidden-key invoke rewriting works for literal method
    lookups without preserving the original `add` identifier. Remaining rows
    are `2.1 Counter FAIL`, `2.5 Loader ERROR`, `2.6 ReTrace ERROR`, and
    `2.7 Annotation ERROR`.
  - Counter evidence: original `Count.run` requires `Countee.getFields() == 1`,
    `getDeclaredFields() == 4`, `getMethods() > 4`, and
    `getDeclaredMethods() == 4`. Fresh `javap -v -p a.z` shows `Countee`
    still has four non-synthetic declared methods (`u/t/r/s(Object[])`) and all
    transform helpers are `ACC_SYNTHETIC`, so the remaining invariant is the
    generated reflection-array filters and name tests, not missing full
    renaming or disabled method-parameter obfuscation.
  - Loader evidence: original `Loader.findClass` loads the resource
    `pack/tests/reflects/loader/LTest.class` and passes the caller-supplied
    binary name to `defineClass`; the fresh output jar maps `LTest -> a/da`
    and writes renamed resources such as `a/TEST`. Loader compatibility must
    therefore rewrite class-byte resource paths and binary names to the final
    obfuscated ABI generically instead of preserving the original class name.
  - Dynamic reflection evidence: original `Tracee.doTrace` calls
    `getDeclaredMethod(stackTrace.getMethodName(), int.class)` and then
    `Method.invoke`; original `annot.run` enumerates
    `annoe.class.getDeclaredMethods()` and invokes a selected `Method`. These
    paths do not have a stable literal method lookup immediately before
    `Method.invoke`, so the reflective invoke rewrite must select the final
    target seed/packed ABI from runtime `Method` metadata or tracked method
    array provenance while keeping the hidden key and `Object[]` carrier.
  - Reflection obfuscation requirement: the rewritten reflective support must
    not expose final class/member names, descriptor strings, resource paths, or
    key-selection constants as plaintext. The implementation must either leave
    inserted data eligible for later string/constant/indy/CFF passes or emit an
    equivalent protected form directly.
  - Fresh validation evidence after dynamic reflection work: `Loader` moved to
    `PASS`, proving the generic class/resource/name path is improving, while
    `Counter` changed to `ERROR`, `ReTrace` remains `ERROR`, and `Annotation`
    remains `ERROR`. The next correction must keep the reflection rewrite
    instructions visible to later obfuscation passes unless a narrower marker
    is needed for key-dispatch bookkeeping only.
  - Fresh validation evidence after reverting the rejected CFF generated-jump
    block split: the full JVM gate passes generation and JUnit, and direct TEST
    stdout is `Counter ERROR`, `Field PASS`, `Loader PASS`, `ReTrace PASS`,
    `Annotation ERROR`, `Sec ERROR`, `Calc: 121ms`. Direct exception logging
    shows `Counter` throws `IllegalStateException` in CFF helper
    `a/y.tc(JIIIII[J)J` called from `a/y.a(Object[])V` at bci 6992 after
    reflection-array filtering, and `Annotation` throws in
    `a/x.dc(JIIIII[J)J` called from `a/x.a(Object[])V` at bci 2305 after
    annotation method-array filtering. The generated annotation class
    `a/v` remains a real annotation interface with `val()` and `val2()`, but
    `a/x.a(Object[])V` still contains an indy annotation element call
    `InvokeDynamic #3:(La/v;J)Ljava/lang/String;`, proving that annotation
    proxy element calls are a JVM metadata/proxy ABI and must not be converted
    into hidden-key indy calls.
  - Current implementation target: keep reflection compatibility instructions
    in the protected application surface by removing the generated marker from
    CFF-inserted reflection filters, while preserving only true transform
    bookkeeping markers elsewhere. Also add a generic invokedynamic guard for
    annotation element interface calls so JVM annotation proxy dispatch keeps
    its mandated `()T` ABI; surrounding transformed code remains CFF-covered
    and later string/constant sites stay protected.
  - Follow-up CFF graph evidence: after removing the generated marker and
    preserving annotation element direct ABI, direct inspection shows
    `a/x.a(Object[])V` uses `invokeinterface a/v.val:()String`, but
    `Counter` and `Annotation` still fail in CFF transition helpers. The
    failing sites are immediately after reflection-array filter loops whose
    internal `if/goto` labels remain raw inside a linearized CFF block. That
    proves the invariant violation is missing CFF leader registration for
    compatibility-inserted reflection filter labels: raw internal branches can
    bypass the transition order expected by adjacent CFF helper calls.
  - Current implementation target: when CFF inserts reflection filters, collect
    all zero-stack labels owned by those filters and pass them into block
    construction as leaders. This increases CFF graph coverage for inserted
    reflection compatibility control flow and preserves live dynamic dispatch,
    hidden key propagation, full renamer coverage, and `Object[]` carriers.
  - Follow-up constant/CFF evidence: after CFF leader registration, the
    failing Counter path reaches a reflection-filter `aastore` sequence where
    an original `IINC` was replaced by numeric lazy-cache bytecode while
    `arrayref,index` are already live on the operand stack. `IINC` is a local
    side-effect instruction and may legally occur with a non-empty stack; a
    replacement that introduces cache-hit/cache-miss branches at that point
    creates non-empty-stack control flow after CFF has fixed transition state.
    The generic fix is to keep `IINC` protected by live-key constant decode but
    make its replacement branchless by disabling the lazy numeric cache for
    `IINC` sites.
  - Invoke resolver evidence: direct exception logging for Counter shows a
    `NoSuchMethodError` from JDK's internal
    `DirectMethodHandle$Holder.invokeSpecial(...)` immediately before CFF
    state poisoning. The resolver already resolves and self-patches a
    `MutableCallSite`, but the first invocation executes the adapted target via
    `asSpreader(Object[].class, n).invoke(args)`. That signature-polymorphic
    spreader path is invalid for some special/private MethodHandles. The
    generic fix is to keep the cached/self-patched target architecture but use
    `MethodHandle.invokeWithArguments(Object[])` only for the resolver's first
    execution path; later calls still bypass the resolver through the
    self-patched target.
  - Additional invokespecial evidence: the remaining internal
    `DirectMethodHandle$Holder.invokeSpecial(...)` linkage failure can also be
    produced when the indy callsite descriptor models an `invokespecial`
    receiver as the target owner rather than the current caller/special-caller
    class. `MethodHandles.Lookup.findSpecial` returns a handle whose receiver
    is the special caller. The generic fix is to derive the indy descriptor for
    `INVOKESPECIAL` from the current class receiver while keeping the encrypted
    payload target owner/name/descriptor unchanged for resolution.
  - Rejected implementation evidence: changing the resolver first execution
    path to `MethodHandle.invokeWithArguments(Object[])` kept the
    `DirectMethodHandle$Holder.invokeSpecial(...)` failure and regressed
    `ReTrace` to `NoSuchMethodException: a.ga.invokeWithArguments([Object])`,
    because stack-introspection code observed the extra
    `invokeWithArguments` frame. The resolver must keep the spreader/invoke
    first-call shape or another stack-transparent path; `invokeWithArguments`
    is rejected.
  - Follow-up constant/CFF evidence: fresh Counter bytecode still shows
    post-CFF lazy numeric cache fields `c[]/d[]` and cache-hit/cache-miss
    branches immediately before CFF transition helpers. These branches are
    inserted after CFF metadata and therefore do not participate in CFF
    state/key transitions. Disable this branchy post-CFF numeric cache
    generically; constants remain protected by live CFF-key decoding, and a
    later performance step must reintroduce caching only as CFF-aware or
    branchless protected logic.
  - Additional reflection rewrite constraint: reflection lookup names,
    descriptor arrays, annotation element calls, resource paths, and rewritten
    reflective invocation arguments must not remain as plain strings or plain
    constants after compatibility rewriting. Any inserted reflection
    compatibility logic must enter the CFF/constant/string/invokedynamic
    protection surface or emit an equivalent protected form directly.
  - Fresh post-CFF reflection evidence: generated `TEST-full-jvm-obf.jar`
    still shows raw `Method.isSynthetic` plus `Arrays.copyOf` reflection-array
    filter loops in obfuscated `a/y` after CFF, immediately after reflection
    result invokedynamic sites. Source inspection identifies these loops as
    `JvmInvokeDynamicObfuscationPass.installInjectedMethodReflectionFilter`
    and `installInjectedFieldReflectionFilter`, which run from `ensureHelpers`
    after CFF and mark the inserted filters as generated. `JvmStringObfuscationPass`
    has the same post-CFF field-filter pattern for string helper fields. This
    violates the reflection rewrite obfuscation constraint and duplicates the
    CFF pass's already protected synthetic-member reflection filters.
  - Current implementation target: stop appending post-CFF generated
    reflection-array filters from the string and invokedynamic passes. Generated
    helper methods and fields are synthetic, and the CFF pass owns the generic
    reflection compatibility filter before block construction so the filter
    control flow and string/constant operands remain eligible for later
    protection. This is a generic pipeline-order fix, not a reflection
    exclusion or obfuscation downgrade.
  - Follow-up reflection/CFF evidence: after removing the post-CFF filters,
    fresh bytecode inspection confirms the raw `Method.isSynthetic`/`Arrays.copyOf`
    filter loops are gone from `a/y` and `a/x`, but TEST still fails Counter and
    Annotation in CFF transition helpers immediately after the protected
    synthetic-member filter keeps a non-synthetic reflection member. Both
    failing sites are after the filter's `writeLocal` increment and `aastore`.
    The filter currently emits JVM `IINC` instructions, which are later handled
    by the constant pass's special IINC rewrite path rather than the normal
    numeric-site path. The next generic correction is to emit ordinary
    `ILOAD/ICONST_1/IADD/ISTORE` increments in CFF-inserted reflection filters
    so the increment constants still enter constant obfuscation but no longer
    rely on the IINC-special rewrite inside protected reflection loops.
  - Updated implementation target: the `IINC`-free inlined filter still enters
    the same CFF poison path, proving the problem is the extra in-method
    reflection filtering loop as a CFF control-flow subgraph rather than the
    IINC opcode alone. Move synthetic static member filtering into the existing
    invokedynamic resolver/self-patching target: `Class.getMethods`,
    `getDeclaredMethods`, `getFields`, and `getDeclaredFields` remain CFF
    callsites and are resolved through indy, but the cached `MethodHandle`
    target is composed with a synthetic return-value filter before
    `MutableCallSite.setTarget`. This preserves hidden helper hiding for
    post-CFF string/constant/indy helpers without appending naked callsite
    loops or adding plaintext lookup strings.
  - Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`
    plus bytecode inspection of the fresh full-obf jars for exposed
    non-ABI application names.
  - Completion criteria: fresh TEST full-obf correctness still passes every
    allowed row, generated jars no longer expose non-ABI application member
    names through renamer exclusions, and no transform is disabled or weakened.
  - Validation result: passed for the correctness/renamer checkpoint. Fresh
    TEST full-obf runtime passes every row except the allowed `2.8 Sec`.
    `obfusjack-full-jvm-obf.jar.map` maps `org/example/Main.hiddenAdd(II)I`
    to obfuscated method `i`, and `javap -p a.a` exposes no `add` or
    `hiddenAdd` method. TEST mappings still rename application `add` methods
    to obfuscated names such as `c`, while standard/external ABI entries
    remain preserved. Reflection helper filtering is implemented by the indy
    self-patched target rather than renamer exclusions.
- [ ] Subtask 4D: Optional Recaf-based ZKM architecture reference.
  - Scope: inspect `/mnt/d/Code/Reverse/ZKM 26/ZKM.jar` through Recaf MCP and
    record the generic mechanisms that explain its performance/strength
    balance for invokedynamic, string, and runtime key dispatch when this
    reference materially helps the current bottleneck.
  - Required evidence: Recaf workspace source path, package/class overview, and
    decompiled resolver/table/cache/key-schedule snippets summarized in the
    plan without copying large source bodies.
  - Validation command: Recaf MCP `get_workspace_info`, `list_packages` or
    `list_classes`, and targeted `decompile_class`/`search_references` calls
    against the loaded ZKM workspace.
  - Completion criteria: the plan records which ZKM mechanisms are applicable
    as generic architecture targets for this codebase, and which are rejected
    because they would violate the full-obfuscation/key-propagation constraints.
