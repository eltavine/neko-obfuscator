# JVM Obfuscation Semantic Entanglement Plan - 2026-05-27

## Scope

Strengthen JVM-only obfuscation structure so CFF state, live data flow,
numeric constants, string material, invokedynamic callsite keys, and validation
sink checks are mutually bound instead of appearing as clean independent
surfaces.

This plan covers JVM transform source and focused JVM tests only. It must not
touch native translation/runtime files, generated C, JNI removal, GC barriers,
native performance plans, or unrelated dirty worktree changes.

The selected strength profile is structural hardening with controlled size:
shared class helpers may remain where they are already the architecture, but
per-site material, layouts, seeds, fingerprints, and call paths must become
independent and data-flow-bound.

## Non-Negotiable Constraints

- Preserve JVM ABI compatibility, full transform coverage, hidden method-key
  propagation, packed `Object[]` carriers, reflection/MethodHandle/lambda
  compatibility, constructor compatibility, and existing pass ordering.
- Do not add fallback behavior, original-bytecode rescue, skip-on-error paths,
  JNI/JVMTI, Java runtime helper layers, plaintext reflective data, or
  descriptor-only/static key recomputation.
- Do not weaken CFF block coverage, block boundaries, transition semantics,
  method-key transfer, or obfuscation coverage to pass tests.
- Do not create files under `/tmp`; use repository-local build directories for
  validation artifacts.
- Use the repository `./gradlew` directly for validation.
- Do not modify the currently dirty native files or native todo files.

## Current Evidence

Read-only exploration before this plan found the relevant implementation
surfaces:

- CFF metadata is published by
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffClassSetup.java`
  as `CffMethodMetadata` with key/guard/path/block/pc/domain locals and
  `CffInstructionState` with static per-block key state, but no live
  application-data digest local.
- Dispatch masks are emitted in
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffDispatchEmitter.java`
  from guard/path/block/pc and method key material. Later constant, string, and
  indy passes consume CFF state, but the dispatcher itself does not bind its
  state to application data-flow updates.
- Concrete CFF source evidence: `CffSharedState.java:283` through `:294`
  defines `CffMethodMetadata` without a data-flow local; `ControlFlowFlatteningPass.java:216`
  through `:224` allocates pc/guard/path/block/domain locals without a data
  digest; `CffClassSetup.java:4174` through `:4184` publishes only those clean
  state locals; and `CffDispatchEmitter.java:2112` through `:2162` computes and
  emits dispatch token masks from guard/path/block/pc plus constants, with no
  application data input.
- Concrete CFF artifact evidence from
  `build/tmp/neko-test-cff-audit/cff-audit-shapes-obf.jar`: `javap` on
  `CffAuditShapes.value(IIJ)I` shows `stack=10, locals=16, args_size=4` and
  lines `7038` through `7057` derive dispatcher locals `8`, `9`, and `10` from
  `lload_3` and constants only. The same method then selects dispatch material
  at lines `7130` through `7151` from `getstatic $74gfz8`, locals `8/9/10`,
  and `__neko_cff_tmat`, before loading the original `x/y` argument data. This
  proves current dispatcher state is tied to clean CFF/key locals rather than
  live application data flow.
- String material currently uses fixed-width key-cell structure in
  `JvmStringObfuscationPass`: `StringKeyMaterial` carries `int[4]` words and
  `encodedStringKeyCell` emits an `int[11]` cell loaded through `[I` casts.
  This is not the literal `int[16]` shape mentioned by the request, but it is
  the same kind of recognizable fixed key-material array surface.
- Numeric constant decode in `JvmConstantObfuscationPass` still pushes
  encrypted values, masks, site seeds, and mix constants through
  `JvmPassBytecode.pushInt/pushLong`, which becomes direct `LdcInsnNode` for
  larger values.
- InvokeDynamic reference obfuscation already has per-site seeds and flow slots,
  but shared per-class resolver and flow tables still expose regular seed-table
  and payload decode shapes.
- Validation sink hardening currently replaces `String.equals` with keyed tag
  helpers using two formula variants and a single helper call shape
  `(Ljava/lang/String;JJI)Z`; this is a cleaner solver target than the rest of
  the obfuscation graph.
- Existing focused tests already audit several surfaces:
  `ControlFlowFlatteningAlgebraicAuditTest`,
  `JvmConstantObfuscationIntegrationTest`,
  `JvmStringObfuscationIntegrationTest`,
  `JvmInvokeDynamicObfuscationIntegrationTest`, and
  `JvmFullObfuscationPerfTest`.

Fresh baseline artifacts were regenerated before implementation with:

`./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --rerun-tasks`

Result: build success. The current generated artifacts used for audit are:

- `build/tmp/neko-test-constants/constant-shapes-obf.jar`
- `build/tmp/neko-test-strings/string-shapes-obf.jar`
- `build/tmp/neko-test-indy-reference/indy-reference-shapes-obf.jar`
- `build/tmp/neko-test-validation-sink/validation-sink-shape-obf.jar`
- `build/tmp/neko-test-cff-audit/cff-audit-shapes-obf.jar`

Baseline bytecode audits identify the current clean semantic structures with
concrete source and artifact evidence:

- Numeric constants:
  source `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/internal/JvmPassBytecode.java:12`
  and `:24` emit large int/long values as `LdcInsnNode`; source
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/constants/JvmConstantObfuscationPass.java:374`
  through `:389` pushes `encrypted` and `siteSeed` directly, `:532` through
  `:543` pushes site-mask constants directly, and `:643` through `:657` pushes
  static decode masks directly.
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-constants/constant-shapes-obf.jar -c -v ConstantShapes | rg -n "ldc(_w|2_w)?[[:space:]]+#.*// (int|long)"`
  returned direct large numeric material loads in the generated fixture,
  including:
  `6269: 0: ldc2_w #25 // long 3735818046909144493l`,
  `6292: 36: ldc #35 // int 114204369`, and
  `6305: 54: ldc #36 // int -313755818`.
- String material:
  source
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/strings/JvmStringObfuscationPass.java:2424`
  through `:2432` constructs `StringKeyMaterial` with `int[4]`; `:2435`
  through `:2454` constructs a fixed `int[11]` cell; `:2857` through
  `:2872` registers that fixed cell; `:2994` through `:3001` emits it as a
  JVM `int[]`; and `:3702` stores the fixed record shape.
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-strings/string-shapes-obf.jar -c -v StringShapes | rg -n "(__neko_strtail|newarray[[:space:]]+int|checkcast[[:space:]]+#.*\"\\[I\"|class \"\\[I\")"`
  shows the generated string decode tail and `[I` key-cell surface, including
  `43: #35 = Utf8 [I`,
  `107: #110 = Utf8 __neko_strtail$`,
  `2123: 79: checkcast #36 // class "[I"`, and
  `6451: 1023: newarray int`.
- InvokeDynamic material:
  source
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/invoke/JvmInvokeDynamicObfuscationPass.java:169`
  through `:181` assigns per-site seed/flow slots, `:471` through `:499`
  emits shared helper names, and `:589` through `:720` stores regular
  fixed-stride `long[]` flow/resolver cells.
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-indy-reference/indy-reference-shapes-obf.jar -c -v IndyReferenceShapes | rg -n "__neko_indy_bsm|__neko_indy_flow|BootstrapMethods|REF_invokeStatic IndyReferenceShapes\\.__neko_indy_bsm|REF_getStatic IndyReferenceShapes\\..*:\\[Ljava/lang/Object;"`
  shows the shared per-class bootstrap/resolver surface and carrier field used
  by every reference site, including
  `81: #79 = Utf8 __neko_indy_flow`,
  `89: #87 = Utf8 __neko_indy_bsm`,
  `93: #91 = MethodHandle ... REF_invokeStatic IndyReferenceShapes.__neko_indy_bsm`,
  and repeated bootstrap entries at `16203`, `16208`, and `16213`.
- Validation sink:
  source
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/validation/JvmValidationSinkHardeningPass.java:42`
  defines the single helper descriptor `(Ljava/lang/String;JJI)Z`; `:111`
  through `:124` emits one helper call per protected compare; `:163` through
  `:181` creates one generated helper per variant; and `:187` through `:246`
  puts the full length/hash/compare predicate in that helper body.
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-validation-sink/validation-sink-shape-obf.jar -c -v ValidationSinkShape | rg -n "__neko_vsink|\\(Ljava/lang/String;JJI\\)Z|invokestatic .*__neko_vsink"`
  shows the current single validation-helper call shape, including
  `779: #808 = Utf8 __neko_vsink0$p`,
  `780: #809 = Utf8 (Ljava/lang/String;JJI)Z`,
  and `6450: 2242: invokestatic #811 // Method __neko_vsink0$p:(Ljava/lang/String;JJI)Z`.

## Runtime-Derived Material Contract

Protected numeric material may be reconstructed at runtime only from fragments
bound to at least one live source in the current transformed path. Allowed live
sources are the hidden method key local, CFF guard/path/block/pc/domain locals,
the new CFF data digest local, decoded class-key table words selected through
live CFF state, and primitive argument/local values already present on the
validated bytecode path. Site seed, owner/name/descriptor hashes, method salts,
block indexes, and static class metadata may select a layout or salt a live
derivation, but they are not sufficient by themselves for protected runtime
material.

Static initialization material that has no CFF metadata must still be split
into multiple small deterministic fragments and class-carrier-derived words; it
must not appear as one large `ldc` value.

Forbidden derived-material shapes:

- `encrypted` plus `mask` emitted as two direct large constants followed by a
  final inverse operation such as `IXOR` or `LXOR`.
- Self-canceling pairs such as `x ^ k ^ k`, `x + k - k`, rotate-left followed
  by rotate-right with the same count, or equivalent neutral algebra.
- Descriptor-only, owner/name-only, slot-only, or seed-only recomputation for a
  protected key, decode word, mask, payload seed, or callsite key.
- New generated Java helper methods outside an existing transform-owned
  generated-method surface, runtime helper classes, bootstrap adapters,
  fallback paths, or original-call rescue paths. Existing generated methods
  owned by the CFF, string, indy, and validation transforms may be reshaped
  only when that row explicitly records the surface and proves it is not a
  bridge, adapter, fallback, or runtime helper layer.

## Pass/Fail Audit Strategy

`javap` commands in this document record human-readable baseline evidence. The
implementation acceptance for each row must be enforced by focused JUnit/ASM
audits in the listed test classes, so the result is pass/fail instead of a
manual grep interpretation. The audits must classify generated helper paths,
application paths, protected decode replacements, structural small immediates,
and JVM-required descriptors before deciding whether a bytecode shape is
forbidden.

## Checkpoint Discipline

For every implementation row below:

- Record scope, required evidence, validation target, and completion criteria
  before editing that row.
- Make one coherent generic architecture-level change.
- Run the row's fresh validation and the listed bytecode/source audit.
- Dispatch a subagent review for the current diff, evidence, validation, and
  scope discipline.
- If review passes, update this plan checkbox and commit only that row's
  implementation plus this plan update before starting the next row.
- If review fails, keep the row open, revise the same row, rerun validation,
  and repeat review before committing.

## Status Legend

- `[ ]` not implemented / not freshly verified
- `[-]` actively in progress
- `[x]` implemented, freshly verified, reviewed, and committed where required
- `[rejected]` measured and reverted or not retained because acceptance failed

## Execution Rows

### [x] JSE-0: Record and review this high-risk plan

- Scope: create this standalone plan, record the active-agent todo entries, run
  a plan-intake review, report the plan scope/order/tradeoffs, and commit only
  this plan checkpoint before implementation starts.
- Required evidence: read-only source findings above plus current dirty
  worktree awareness showing native files are unrelated and must remain
  untouched.
- Validation command or runtime target: read-only repository inspection and
  subagent plan-intake review.
- Completion criteria: plan-intake review passes or is reconciled, user-facing
  plan report is sent, and a checkpoint commit contains only this plan file and
  any matching todo-only update.

### [x] JSE-1: Reserve and publish CFF data digest local

- Scope: allocate one int `dataLocal` for each protected CFF method, publish it
  through `CffMethodMetadata`, and reserve it from runtime-variable
  obfuscation. Do not initialize, update, or consume it in this row, and do not
  change block construction, transition targets, method ABI, or helper ABI.
- Required evidence: source diff shows local allocation in
  `ControlFlowFlatteningPass`, `dataLocal` in `CffMethodMetadata`,
  publication in `CffClassSetup`, and protected-local exclusion in
  `JvmRuntimeVariableObfuscationPass`.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
- Static/ASM audit: source audit
  `rg -n "dataLocal|CffMethodMetadata\\(" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/variables/JvmRuntimeVariableObfuscationPass.java`;
  existing CFF ASM tests must still pass without new consumers.
- Completion criteria: focused tests pass; static audit proves metadata/local
  plumbing exists and no consumer has been added before initialization.

### [x] JSE-2: Initialize CFF data digest from live method-entry data

- Scope: initialize `dataLocal` at CFF method entry from the hidden method key
  plus verifier-safe primitive argument values, including original application
  constructor and static/instance method paths. Object/reference values may be
  folded only through verifier-safe deterministic bytecode that preserves
  semantics. Do not update data digest later and do not consume it yet.
- Required evidence: source diff shows a generic entry initializer that walks
  method descriptors and access flags rather than matching fixture names;
  bytecode audit shows protected fixture methods store `dataLocal` before the
  first dispatcher decision.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
- Static/ASM audit: add or extend a CFF ASM assertion named
  `assertCffDataDigestInitializedFromEntryData`; source audit
  `rg -n "emit.*DataDigest|dataLocal|Type\\.getArgumentTypes|method\\.asmNode\\(\\)\\.desc" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff`.
- Completion criteria: focused tests pass; the initializer is generic, live
  method-entry data contributes to `dataLocal`, and no dispatcher consumer is
  added before later update/mix rows.

### [x] JSE-3: Update CFF data digest from primitive data-flow observations

- Scope: add stack-neutral `dataLocal` update snippets at generic
  verifier-safe primitive observation points selected from CFF-protected
  application instructions. Eligible observations are primitive constants,
  primitive local loads/stores, `iinc`, primitive arithmetic results, and
  primitive array load/store payloads where the value is already on the stack or
  in a local. Do not consume `dataLocal` in dispatcher state yet.
- Required evidence: source diff shows opcode-category predicates, not
  class/method/test-name predicates; inserted update snippets preserve operand
  stack shape and are marked consistently with existing generated CFF code.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
- Static/ASM audit: add or extend a CFF ASM assertion named
  `assertCffDataDigestUpdatedFromPrimitiveFlow`; source audit
  `rg -n "dataLocal|primitive|IINC|IASTORE|LSTORE|FSTORE|DSTORE|ISTORE" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff`.
- Completion criteria: focused tests pass; source and bytecode audits prove
  live primitive data can change `dataLocal`; no block coverage or transition
  semantics changed.

### [x] JSE-4: Mix CFF data digest into dispatcher token masks

- Scope: consume `dataLocal` only in CFF token dispatch mask calculation and
  emitted token dispatch mask bytecode. Keep downstream constant/string/indy/
  validation live-word helpers unchanged in this row.
- Required evidence: source diff shows `dispatchTokenMask` and
  `emitDispatchTokenMask` both include `dataLocal` in the same non-linear
  position; token collision modeling uses the same formula; block construction,
  target selection, and key-transfer semantics are unchanged.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
- Static/ASM audit: add `assertCffDispatchConsumesDataDigest`. Exact
  predicate: every protected dispatch selector slice ending in `LOOKUPSWITCH`,
  `TABLESWITCH`, or small-token compare must contain an `ILOAD` of the
  published `dataLocal` between the current `pcLocal` load and the branch
  instruction; the same slice must also contain at least one non-linear mix
  opcode (`IMUL`, rotated/xor-shift, or class-key decode) after that load.
- Completion criteria: focused CFF/key-transfer tests pass; dispatcher state is
  data-flow-bound and no self-canceling/static-key expression is introduced.

### [x] JSE-5: Bind constant live-base transport to CFF data digest

- Scope: update only `JvmConstantObfuscationPass` live-base cache/helper
  transport so CFF-protected constant decode sites carry a
  `metadata.dataLocal()`-encoded base representation and current data-derived
  multiplier. Do not change string, indy, validation, or numeric literal
  emission yet.
- Required evidence: source diff shows `emitLiveConstantBase` stores a
  data-digest-encoded base representation and every protected decode site
  passes a current-`dataLocal`-encoded base into helper/inline mask
  reconstruction. The encryption-side `liveConstantBase` remains the canonical
  decoded-base model because the runtime data digest is not statically known
  without changing literal material format. Tests prove protected constant
  decode slices consume the current data load before helper/inline
  reconstruction.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- Static/ASM audit: add `assertConstantLiveWordConsumesDataDigest`. Exact
  predicate: protected numeric decode replacements must contain an
  `ILOAD metadata.dataLocal()` feeding the encoded-base and multiplier
  dependency slice before the helper call or final value-reconstruction opcode,
  while structural immediates remain classified separately.
- Completion criteria: constant fixture output is unchanged; constant decode
  base transport is data-flow-bound without changing literal material format.
  Non-canceling constant mask semantics are deferred to JSE-5B because they
  require runtime-derived numeric material rather than the original single
  encrypted literal format.

### [x] JSE-5B: Bind constant masks through runtime-derived numeric material

- Dependency reason: JSE-5 review proved that non-canceling current-data
  binding is not possible while keeping the original single encrypted numeric
  literal format; any correct constant value for arbitrary runtime data must
  either cancel the data term or derive companion material at runtime. This row
  therefore moves the original constant mask binding requirement behind the
  numeric material split that was previously planned later.
- Scope: update only `JvmConstantObfuscationPass` numeric material emission and
  helper/inline reconstruction so data-derived material participates in the
  final constant mask without an immediately removable
  `m * inverse(m)` callsite-local pair. Do not change string, indy, or
  validation passes.
- Required evidence: source diff shows static encrypted numeric material is
  split into multiple runtime-derived fragments, at least one fragment consumes
  `metadata.dataLocal()` in the final mask/material slice, and no helper or
  inline path immediately inverts the same data-derived multiplier introduced
  at that callsite.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- Static/ASM audit: extend `assertConstantLiveWordConsumesDataDigest` to reject
  same-slice encode/decode cancellation and to prove every protected numeric
  decode helper/inline site consumes `metadata.dataLocal()` in a final
  value-reconstruction dependency slice.
- Completion criteria: constant fixture output is unchanged; numeric literal
  material is split into runtime-derived fragments; constant masks are
  data-flow-bound without same-site multiplier cancellation.

### [ ] JSE-6: Bind string live-word derivation to CFF data digest

- Scope: update only `JvmStringObfuscationPass` live string word derivation and
  emitted live string word bytecode so protected string roots, selectors, and
  byte-layer decisions consume `metadata.dataLocal()`. Do not change string
  key-cell layout or numeric material emission yet.
- Required evidence: source diff shows `liveStringWord` and
  `emitLiveStringWord` use `dataLocal`; ASM audit proves string decode sites
  load `dataLocal` before reconstructing per-site live words.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest`.
- Static/ASM audit: add `assertStringLiveWordConsumesDataDigest`. Exact
  predicate: every non-generated string decode callsite must include an
  `ILOAD metadata.dataLocal()` in the live-word slice feeding root key,
  selector, or byte-layer material.
- Completion criteria: string fixture passes; string live-word material is
  data-flow-bound and fixed key-cell layout remains unchanged for later rows.

### [ ] JSE-7: Bind indy flow-word derivation to CFF data digest

- Scope: update only `JvmInvokeDynamicObfuscationPass` live indy flow word
  derivation and the existing flow helper ABI so each transformed indy callsite
  passes and consumes `metadata.dataLocal()`. Do not change indy material table
  layout, payload encryption, resolver seeds, or cache keys yet.
- Required evidence: source diff shows `liveIndyWord`, `emitLiveIndyWord`, and
  the existing `__neko_indy_flow` helper descriptor/body consume `dataLocal`.
  This is a shape change within the existing indy helper surface, not a new
  bridge, adapter, or runtime layer.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- Static/ASM audit: add `assertIndyFlowConsumesDataDigest`. Exact predicate:
  every application `invokedynamic` replacement must pass one extra int sourced
  from `metadata.dataLocal()` into the existing flow helper, and the flow helper
  body must mix that parameter before returning the flow word.
- Completion criteria: indy fixture passes; indy flow word is data-flow-bound
  without changing resolver/payload/cache material layout.

### [ ] JSE-8: Bind validation live-word derivation to CFF data digest

- Scope: update only `JvmValidationSinkHardeningPass` live-word derivation so
  validation tag seed and expected-tag material consume `metadata.dataLocal()`.
  Do not split helper predicates or route through indy yet.
- Required evidence: source diff shows validation `emitLiveWord` uses
  `dataLocal`; ASM audit proves validation helper call material loads
  `dataLocal` before seed/tag reconstruction.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- Static/ASM audit: add `assertValidationLiveWordConsumesDataDigest`. Exact
  predicate: every validation sink replacement must contain an
  `ILOAD metadata.dataLocal()` in the material slice that computes tag seed,
  expected tag, or protected length before the generated helper call.
- Completion criteria: validation fixture passes; validation material is
  data-flow-bound without changing helper topology.

### [ ] JSE-9: Add derived numeric material emitters and classifier audit

- Scope: add transformer-only bytecode emitter utilities for derived int/long
  material and add JUnit/ASM classifier helpers that distinguish protected
  numeric material from structural immediates. This row must not migrate any
  pass to the new emitter and must not add generated runtime helpers.
- Required evidence: source diff confines the emitter to transformer utility
  code and test utility/assertion code; tests include negative assertions for
  direct large protected `LdcInsnNode` material and self-canceling inverse
  patterns.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
- Static/ASM audit: add classifier helpers named
  `isProtectedNumericMaterial`, `assertNoDirectLargeProtectedNumericMaterial`,
  and `assertNoSelfCancelingDerivedNumericMaterial`; source audit
  `rg -n "pushDerived|Derived.*Material|self-cancel|LdcInsnNode" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm neko-test/src/test/java/dev/nekoobfuscator/test/JvmConstantObfuscationIntegrationTest.java`.
- Completion criteria: classifier unit samples prove direct large protected
  material and self-canceling expressions are rejected; existing fixture output
  remains unchanged until migration rows; the utility is transformer-only.

### [ ] JSE-10: Migrate CFF-protected integer constant decode material

- Scope: migrate `JvmConstantObfuscationPass` integer decode words, integer
  masks, site-seed fragments, and `iinc` replacement material that execute in
  CFF-protected application methods to the derived numeric emitter. Static
  `<clinit>` material and long/double material remain unchanged in this row.
- Required evidence: source diff touches only constant int/iinc decode paths
  and tests; ASM audit proves protected integer decode replacements no longer
  contain single large numeric `LdcInsnNode` material or self-canceling inverse
  pairs.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- Static/ASM audit: `JvmConstantObfuscationIntegrationTest` must run
  `assertNoDirectLargeProtectedNumericMaterial` for protected int/iinc decode
  slices and `assertNoSelfCancelingDerivedNumericMaterial`; optional artifact
  inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-constants/constant-shapes-obf.jar -c -v ConstantShapes | rg -n "ldc(_w)?[[:space:]]+#.*// int"`.
- Completion criteria: constant fixture output is unchanged; protected integer
  material is derived from live CFF/data-local state plus fragments; unrelated
  static and long paths are not changed.

### [ ] JSE-11: Migrate CFF-protected long constant decode material

- Scope: migrate only CFF-protected long constant decode material in
  `JvmConstantObfuscationPass`, including high/low word reconstruction and
  long masks. Int, float/double, and static initialization material are outside
  this row except where existing helper code is shared mechanically.
- Required evidence: source diff touches long decode paths and shared emitters
  only as needed; ASM audit proves protected long decode replacements contain
  no single large long `LdcInsnNode` material and no self-canceling inverse
  pairs.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
- Static/ASM audit: extend `assertNoDirectLargeProtectedNumericMaterial` for
  protected `long` decode slices. Exact predicate: long reconstruction must
  consume hidden method key or CFF guard/path/block/pc/domain/data locals plus
  fragmented material before `LOR`/`LADD`/final reconstruction.
- Completion criteria: constant fixture output is unchanged; protected long
  material is live-derived and no static numeric path changes.

### [ ] JSE-12: Migrate CFF-protected float/double decode material

- Scope: migrate only CFF-protected float and double raw-bit decode material in
  `JvmConstantObfuscationPass`. Integer/long and static material are outside
  this row except for already-migrated shared emitters.
- Required evidence: ASM audit proves `Float.intBitsToFloat` and
  `Double.longBitsToDouble` callsites are fed by derived protected material and
  not by direct large raw-bit constants.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
- Static/ASM audit: extend `assertNoDirectLargeProtectedNumericMaterial` for
  float/double raw-bit slices. Exact predicate: the value feeding
  `intBitsToFloat`/`longBitsToDouble` must include live CFF/data-local sources
  and fragmented material.
- Completion criteria: constant fixture output is unchanged; protected
  float/double material is live-derived.

### [ ] JSE-13: Migrate static numeric material

- Scope: migrate static numeric decode material that has no CFF metadata,
  including moved `ConstantValue` initialization. Static paths must use multiple
  deterministic fragments plus class-carrier-derived words instead of one large
  direct `ldc`; they must not claim live CFF binding where no CFF state exists.
- Required evidence: ASM audit proves static numeric material no longer appears
  as one large direct constant and does not use descriptor-only recomputation.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Static/ASM audit: `JvmConstantObfuscationIntegrationTest` must run
  `assertStaticNumericMaterialIsFragmented` for static decode slices, with
  structural immediates classified separately;
  optional artifact inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-constants/constant-shapes-obf.jar -c -v ConstantShapes | rg -n "ldc(_w|2_w)?[[:space:]]+#.*// (int|long)"`.
- Completion criteria: constant and full-JVM tests pass; protected constants
  remain live-derived, static constants are split, and no plaintext numeric
  payload or helper fallback is introduced.

### [ ] JSE-14: Replace fixed string key material model

- Scope: replace `StringKeyMaterial(long siteSeed, int epoch, int[] words)` and
  fixed `int[4]` generation with a per-site variable material model containing
  layout id, shuffled word indexes, independent epoch/fingerprint, and payload
  metadata. This row may keep the old emitted `[I` cell while adapting source
  data structures and tests to prove per-site independence.
- Required evidence: source diff removes the fixed `int[4]` record contract;
  ASM test proves at least two string sites in the fixture receive distinct
  layout/fingerprint material and no global seed can decode all sites.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest`.
- Static/ASM audit: add `assertStringSitesHaveIndependentMaterialLayouts`
  in `JvmStringObfuscationIntegrationTest`; source audit
  `rg -n "StringKeyMaterial|int\\[4\\]|new int\\[4\\]|fingerprint|layout" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/strings/JvmStringObfuscationPass.java neko-test/src/test/java/dev/nekoobfuscator/test/JvmStringObfuscationIntegrationTest.java`.
- Completion criteria: string fixture passes; source no longer has a fixed
  `int[4]` key-material contract; emitted cell migration is left for JSE-15.

### [ ] JSE-15: Replace string `[I` key-cell emission with variable carrier layout

- Scope: migrate string key material storage from fixed emitted `int[11]`
  cells to variable per-site layouts inside the existing class `Object[]`
  carrier and update string decode tails to consume the layout descriptor.
  Shared decode tails may remain, but no fixed `[I` string key-cell contract may
  remain.
- Required evidence: transformed string fixture has no `[I` key-cell casts or
  `newarray int` for string material; any remaining `[I` casts are classified
  as class-key or unrelated structural arrays by the ASM audit.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Static/ASM audit: add `assertStringKeyMaterialHasNoFixedIntArrayCell`
  in `JvmStringObfuscationIntegrationTest`; optional artifact inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-strings/string-shapes-obf.jar -c -v StringShapes | rg -n "(checkcast[[:space:]]+#.*\"\\[I\"|newarray[[:space:]]+int|__neko_strtail)"`.
- Completion criteria: plaintext strings remain absent; no fixed string
  key-cell `[I` pattern survives; no standalone key/material fields or helper
  fallbacks appear.

### [ ] JSE-16: Apply derived numeric fragments to string material

- Scope: migrate string material initialization, string site token material,
  string tail selector material, and protected string live-word salts to the
  derived numeric emitter. Required live sources for protected string material
  are hidden method key, CFF guard/path/block/pc/domain/data locals, and a
  decoded class-key word selected from live state. Do not change the variable
  layout introduced by JSE-15.
- Required evidence: ASM audit classifies string protected material and proves
  it is split/live-derived rather than emitted as single large `ldc` values;
  material reconstruction slices must load `metadata.dataLocal()`.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Static/ASM audit: add `assertStringProtectedMaterialIsDerived` in
  `JvmStringObfuscationIntegrationTest`. Exact predicate: each protected string
  material reconstruction slice contains `ILOAD metadata.dataLocal()`, a hidden
  method-key fold, and a live selected class-key word before final material use;
  optional artifact inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-strings/string-shapes-obf.jar -c -v StringShapes | rg -n "ldc(_w|2_w)?[[:space:]]+#.*// (int|long)"`.
- Completion criteria: string tests pass; string protected seed/material values
  are per-site and derived; no plaintext strings or standalone carriers appear.

### [ ] JSE-17: Diversify indy flow/resolver material layout

- Scope: replace fixed-stride indy flow/resolver `long[]` cell layout with
  per-site variable layout descriptors, independent epoch/fingerprint, shuffled
  seed slots, and per-site guarded target identity material inside the existing
  class carrier. Keep existing shared helper architecture and cache carrier.
- Required evidence: ASM audit proves there is no fixed `FLOW_SALT_CELL_STRIDE`
  indexing contract, no fixed resolver stride of `3`, no `new long[] {methodSalt,
  siteSeed, stateToken}` or `new long[] {siteSeed, salt}` source shape, and at
  least two indy sites have distinct layout ids and fingerprints.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
- Static/ASM audit: add `assertIndySitesHaveIndependentMaterialLayouts`
  in `JvmInvokeDynamicObfuscationIntegrationTest`. Exact predicate: number of
  distinct `(layoutId,fingerprint)` pairs is at least the number of transformed
  indy sites in the fixture, and no method indexes material with one global
  stride constant; source audit
  `rg -n "FLOW_SALT_CELL_STRIDE|resolverCells\\(\\).*new long\\[\\]|new long\\[\\] \\{siteSeed|layout|fingerprint" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/invoke/JvmInvokeDynamicObfuscationPass.java neko-test/src/test/java/dev/nekoobfuscator/test/JvmInvokeDynamicObfuscationIntegrationTest.java`.
- Completion criteria: indy fixture passes; shared helper names may remain, but
  fixed global material layout and single recoverable seed table do not.

### [ ] JSE-18: Derive indy resolver seed material per callsite

- Scope: migrate only indy resolver seed/salt reconstruction to per-callsite
  derived material. Required live sources are the flow word returned by the
  existing flow helper, resolver-slot epoch loaded from the class carrier, the
  helper method key, and the per-site layout fingerprint. Do not change payload
  character masks, flow fingerprint updates, or cache-key derivation yet.
- Required evidence: ASM audit proves resolver seed reconstruction loads
  flow/token material, epoch, helper key, and layout fingerprint before seed
  use; no raw two-long resolver cell or direct large seed/salt `ldc` remains.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
- Static/ASM audit: add `assertIndyResolverSeedsArePerSiteDerived`. Exact
  predicate: resolver seed dependency slice contains flow local, epoch local,
  helper-key local, and layout/fingerprint local before the first payload
  decode or MethodHandle lookup.
- Completion criteria: indy fixture passes; recovering one resolver seed does
  not reveal a class-global resolver seed layout.

### [ ] JSE-19: Derive indy payload character masks per callsite

- Scope: migrate only indy encrypted payload character-stream mask derivation.
  Required live sources are dynamic flow word, token, per-site layout
  fingerprint, resolver seed material, and character index. Do not change cache
  keys or flow fingerprint updates yet.
- Required evidence: ASM audit proves payload decode loops mix flow/token/index
  and per-site fingerprint before each character reconstruction; bootstrap args
  still contain no plaintext owner/name/descriptor data.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
- Static/ASM audit: add `assertIndyPayloadMasksArePerSiteDerived`. Exact
  predicate: the payload decode loop dependency slice for `CALOAD`/`CASTORE` or
  string reconstruction contains dynamic flow, token, layout fingerprint, and
  loop index loads; no payload decode slice is seed-only or slot-only.
- Completion criteria: indy fixture passes; one payload/callsite cannot decode
  another callsite with the same recovered seed.

### [ ] JSE-20: Derive indy flow fingerprints and cache keys per callsite

- Scope: migrate only indy flow fingerprint updates and cache-key derivation
  salts. Required live sources are dynamic flow word, token, resolver seed
  material, per-site layout fingerprint, `MethodType`, and guarded target
  identity material. Do not add bridge methods, adapter methods, runtime
  dispatch layers, helper classes, fallback paths, or plaintext member
  descriptors.
- Required evidence: ASM audit proves each callsite has independent payload and
  cache-key material and no old mix-helper ABI, plaintext member reference, or
  standalone cache field appears.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Static/ASM audit: add `assertIndyPayloadAndCacheMaterialIsPerSite`
  and `assertIndyProtectedMaterialIsDerived` in
  `JvmInvokeDynamicObfuscationIntegrationTest`. Exact predicate: map lookup and
  `MutableCallSite.setTarget` dependency slices include dynamic flow, token,
  per-site fingerprint, method type, and guarded target identity material before
  cache key construction; optional artifact inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-indy-reference/indy-reference-shapes-obf.jar -c -v IndyReferenceShapes | rg -n "(__neko_indy_bsm|__neko_indy_flow|BootstrapMethods|REF_invokeStatic IndyReferenceShapes\\.__neko_indy_bsm|REF_getStatic IndyReferenceShapes\\..*:\\[Ljava/lang/Object;|ldc(_w|2_w)?[[:space:]]+#.*// (int|long))"`.
- Completion criteria: indy and full-JVM tests pass; cache remains in the class
  carrier; per-site material cannot be globally recovered from one site.

### [ ] JSE-21: Split validation sink predicate into staged helpers

- Scope: split validation sink checks so length gate, seeded hash fold, per-char
  fold, and final compare are spread across multiple generated stage methods
  per site/variant within the existing validation-sink generated-method
  surface. Preserve no-plaintext-target and no-standalone-carrier invariants.
  These generated methods must remain direct validation transform artifacts;
  they must not become a runtime helper class, bridge adapter, fallback, or
  original-call rescue.
- Required evidence: transformed fixture no longer has a single
  `(Ljava/lang/String;JJI)Z` helper body carrying the full predicate; ASM audit
  proves staged helpers are per-site/variant and their material is CFF/data
  digest bound.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
- Static/ASM audit: extend `assertValidationSinkUsesKeyedTag` with
  `assertValidationSinkPredicateIsStaged` and
  `assertValidationSinkStagesUseCffDataDigest`; optional artifact inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-validation-sink/validation-sink-shape-obf.jar -c -v ValidationSinkShape | rg -n "(__neko_vsink|\\(Ljava/lang/String;JJI\\)Z|invokestatic .*__neko_vsink)"`.
- Completion criteria: validation fixture passes; no plaintext target,
  `String.equals` compare, standalone target carrier, or single-solver helper
  remains.

### [ ] JSE-22: Route validation final stage through existing indy surface

- Scope: route the final validation stage through the existing invokeDynamic
  obfuscation surface and per-callsite material from JSE-17 through JSE-20. This
  row must not add a new Java bootstrap, new helper layer, bridge adapter,
  fallback, or original-call rescue.
- Required evidence: transformed fixture contains an indy-protected validation
  final stage; bootstrap args contain no plaintext target or descriptor-only
  key material.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
- Static/ASM audit: extend validation fixture audit with
  `assertValidationFinalStageUsesInvokeDynamic`; optional artifact inspection:
  `javap -J-XX:-UsePerfData -classpath build/tmp/neko-test-validation-sink/validation-sink-shape-obf.jar -c -v ValidationSinkShape | rg -n "(InvokeDynamic|BootstrapMethods|__neko_vsink|swordfish|String.equals)"`.
- Completion criteria: validation and indy fixtures pass; validation final
  stage is not a single direct helper call and no forbidden helper/fallback
  path is introduced.

### [ ] JSE-23: Full JVM semantic-entanglement acceptance

- Scope: run the focused suite and full JVM obfuscation gate after all retained
  subtasks, inspect generated artifacts for forbidden patterns, and run final
  plan review.
- Required evidence: fresh test outputs, static bytecode audits, current git
  diff limited to JVM transform/test/plan files, and review result.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Completion criteria: all retained subtasks are checked off with fresh
  evidence and committed in order; final review accepts evidence freshness,
  validation coverage, atomic commits, and scope discipline.
