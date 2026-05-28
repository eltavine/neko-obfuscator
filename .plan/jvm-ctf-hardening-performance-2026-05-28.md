# JVM CTF Hardening And Runtime Performance Plan - 2026-05-28

## Scope

This plan covers JVM-only full obfuscation for the `test-jars/full-jvm-obf.yml`
profile. It addresses two concrete problem groups:

- CTF recoverability in the freshly generated
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar`.
- JVM full-obfuscation runtime performance for `test.jar` and `test21.jar`.

Native translation/runtime, JNI removal, GC barriers, generated C, website
files, and unrelated dirty native worktree changes are out of scope.

The validation thresholds requested for this plan are:

- `test.jar` full JVM obfuscated `Calc` <= 200 ms.
- `test21.jar` full JVM obfuscated `Seq` <= 400 ms.
- every `Parallel` and `VThreads` matrix timing line in `test21.jar` <= 15 ms.

These targets are validation gates, not special-case implementation criteria.
All fixes must be generic transform/runtime architecture changes.

## Non-Negotiable Constraints

- Preserve JVM ABI compatibility, full transform coverage for eligible
  application code, hidden method-key propagation, packed `Object[]` carriers,
  constructor compatibility, reflection/MethodHandle/lambda compatibility, and
  full-strength CFF block coverage.
- Do not add fallback behavior, original-bytecode rescue, skip-on-error paths,
  JNI/JVMTI, Java runtime helper layers, plaintext reflective data, or
  descriptor-only/static key recomputation.
- Do not weaken CFF block construction, block boundaries, block count,
  transition semantics, dynamic token masking, hidden-key transfer, or enabled
  transform coverage to pass the performance gate.
- Do not special-case `ctf.jar`, `test.jar`, `test21.jar`, a class name, method
  name, descriptor, benchmark string, CTF flag, passphrase, or output row.
- Do not create files under `/tmp`; use repository-local build directories for
  validation artifacts.
- Use repository `./gradlew` for validation.
- Do not stage or modify unrelated native/website/test-jar dirty worktree
  files.

## Fresh Evidence

### CTF Recoverability

Fresh artifact under analysis:

```text
/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar
```

Local structure audit:

```bash
unzip -p /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar META-INF/MANIFEST.MF
jar tf /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar
javap -classpath /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar -p a.c
```

Observed facts:

- Manifest entry remains `Main-Class: a.b`.
- Core classes are still in `a/`.
- `a.c` exposes a compact solver-oriented shape:
  `h(Object[], long)`, `j(Object[], long)`, `k(Object[], long)`,
  `l(Object[], long)`, and static fields `int[] d`, `int[] a`,
  `byte[] e`, `int[] b`, `Object[] f`.

Local reflection audit command:

```bash
java -cp build/manual-audit:/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar ReflectDump a.c
```

Observed plaintext field dump:

```text
d=[49, 199, 90, 13, 132, 34, 225, 107, 16, 169, 71, 60, 240, 85, 155]
a=[81, 98, 218, 155, 56, 108, 149, 118, 139, 183, 181, 112, 112, 61, 39]
e=[66, 19, 55, 33, -64, 90, 126, 9]
b=[11, 31, 15, 96, 60, 116, 146, 28, 169, 41, 117, 212, 106, 3, 73, 29, 178, 188, 127, 117, 233, 106, 90, 246, 48, 41, 5, 82, 114, 157, 122]
```

Local direct-entry audit commands:

```bash
java -XX:-UsePerfData -cp build/manual-audit:/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar CallCore test
java -XX:-UsePerfData -cp build/manual-audit:/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar CallCore swing-gate-0427
```

Observed output:

```text
<null>
flag{java_swing_vault_unlocked}
```

This proves that the current artifact can be solved by combining external
reflection of static plaintext primitive arrays with a direct call into a
packed internal method once the caller-captured hidden long is copied.

Additional bytecode audit:

```bash
javap -classpath /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar -c -p a.c
```

Observed shape includes static `<clinit>` primitive `newarray` / `iastore`
material for the application tables, protected byte/int table loads in `j`,
and a small standalone integer helper `l(Object[], long)`.

### Runtime Performance

Fresh generated artifacts:

```text
/mnt/d/Code/Reverse/NekoOBF/m/test-obf.jar
/mnt/d/Code/Reverse/NekoOBF/m/test21-obf.jar
```

Single-run local baseline:

| Target | Original | Full JVM obf |
| --- | ---: | ---: |
| `test.jar` Calc | 14 ms | 1288 ms |
| `test21.jar` Seq | 2 ms | 525-544 ms |
| `test21.jar` Parallel | 0 ms | 944-977 ms |
| `test21.jar` VThreads | 1 ms | 898-928 ms |

Fresh repository ablation report:

```text
build/test-jvm-runtime-ablation/jvm-runtime-ablation-report.json
```

Key medians from that report:

| Fixture / variant | Calc | Seq | Parallel | VThreads |
| --- | ---: | ---: | ---: | ---: |
| TEST original | 10 ms | n/a | n/a | n/a |
| TEST cff-only-stack | 128 ms | n/a | n/a | n/a |
| TEST full-no-indy | 289 ms | n/a | n/a | n/a |
| TEST full-no-const-string | 209 ms | n/a | n/a | n/a |
| TEST full | 1205 ms | n/a | n/a | n/a |
| obfusjack original | n/a | 2 ms | 0 ms | 0 ms |
| obfusjack cff-only-stack | n/a | 434 ms | 818 ms | 828 ms |
| obfusjack full-no-indy | n/a | 522 ms | 835 ms | 873 ms |
| obfusjack full-no-const-string | n/a | 433 ms | 752 ms | 782 ms |
| obfusjack full | n/a | 535 ms | 923 ms | 839 ms |

This proves two independent performance problems:

- `test21` matrix parallel/VThreads are already dominated by the CFF-only path.
- `test.jar` Calc is close to the requested gate without constant/string, but
  full constant/string hardening currently adds roughly 1 second.

Existing prior plan evidence in `.plan/jvm-obfuscation-performance-2026-05-26.md`
identified a concrete CFF hot path: delayed JFR samples placed
`__neko_cff_relay$0` and `__neko_cff_relay$1` under obfuscated `mmulSeq`
stacks, with relay dispatch consuming about 42% of delayed matrix samples.
This plan will refresh that evidence before changing CFF performance code.

## Tasks

### [x] JCP-0: Baseline, Plan, And Plan-Intake Checkpoint

- Scope: record the CTF and performance evidence above, decompose the work,
  run plan-intake review, report the plan, and commit only this plan file.
- Required evidence: local structure audit of `ctf-obf.jar`, local reflection
  and direct-entry proof, local performance baseline, ablation report, and
  review result.
- Validation command or target: read-only commands listed in Fresh Evidence;
  plan-intake subagent review.
- Completion criteria: review returns PASS; plan is committed before runtime
  implementation starts.
- Review evidence: first plan-intake review failed because threshold
  enforcement was ordered before repairs and CFF relay-only repair was too
  narrow for the `Parallel` / `VThreads` target. The plan was revised so JCP-4
  is measurement-only, JCP-5 refreshes profiler evidence and selects generic
  repair paths, JCP-6 covers all evidence-backed generic CFF hot paths, and
  final threshold enforcement moved to JCP-8 after performance repairs.
  Second plan-intake review `019e6c88-380b-7393-b355-f9da9972e492` returned
  PASS with no required fixes.

### [x] JCP-1: Replace Reflectable Static Primitive Tables With Protected Material

- Scope: generically transform eligible application-owned static primitive
  array tables that are initialized from constants and only read by protected
  code. Replace plaintext `int[]` / `byte[]` fields and their direct element
  loads with encoded protected material bound to live CFF/method-key data.
- Required evidence before editing: field-use census proving the target tables
  have no non-`<clinit>` writes and no unrewritable external application uses;
  source-path evidence showing where array literal material currently bypasses
  field-level protection.
- Fresh source-path evidence: `JvmConstantObfuscationPass` only protects
  inline primitive array construction and scalar `ConstantValue` fields. The
  CTF tables are static primitive array fields; after CFF, `invokeDynamic`
  rewrites their `PUTSTATIC` / `GETSTATIC` instructions into field callsites,
  but the backing fields still receive decoded arrays. Runtime reflection
  therefore reads the field value directly and bypasses the indy/CFF access
  path.
- Fresh field-use census: the original `Vault` tables are initialized only in
  `<clinit>` from primitive array literals. `MASK`, `CHECK`, and
  `ENCRYPTED_FLAG` are consumed by element reads or `arraylength`; `SALT` is
  consumed by element reads and the JDK `MessageDigest.update(byte[])`
  read-only array consumer. No non-`<clinit>` `PUTSTATIC`, identity-sensitive
  use, monitor use, return, local alias escape, or original application field
  reflection path was observed for those tables. The observed full-obf `a.c`
  `<clinit>` has exactly four static primitive array stores routed through indy
  field setter sites, and application reads are routed through protected indy
  getter sites.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  plus regenerating `ctf-obf.jar` with `test-jars/full-jvm-obf.yml`.
- Completion criteria: obfuscated static-array regression fixtures run with the
  same keyDispatch+CFF+indy+constant transform set; regenerated `ctf-obf.jar`
  class initialization succeeds; reflection no longer dumps plaintext
  `int[]` / `byte[]` challenge tables; `javap -p a.c` may still list the
  private JVM fields, but those fields must not store plaintext table values;
  no fallback or original-bytecode path is introduced.
- Failed review evidence: subagent review
  `019e6c9d-b896-7000-a897-f46b5f1d2073` rejected the first implementation
  because it materialized fresh arrays for every eligible `GETSTATIC`, marked
  original fields synthetic, and lacked unsafe-reference regression coverage.
  Subagent review `019e6cab-722d-7852-8af6-260a4d92a996` rejected the revised
  implementation because the eligibility proof still scanned mutable
  post-indy bytecode for field reflection and did not account for original
  MethodHandle, VarHandle, or field-handle constant dynamic access. Subagent
  review `019e6cb2-933f-7c10-979d-75740ea976d9` rejected the next revision
  because non-handle `ConstantDynamic` and custom invokedynamic bootstrap
  protocols can carry owner/name/type field-resolution material without an ASM
  field `Handle`; materializing the field in that case would make the true
  backing field null while the original dynamic protocol still resolves it.
  Subagent review `019e6cc0-ba23-7422-a2cd-d199cfdedbf9` rejected the
  follow-up because the guard considered bootstrap arguments but not
  `ConstantDynamic.getName()` or `InvokeDynamicInsnNode.name`, and because it
  only recognized `ConstantBootstraps.getStaticFinal` rather than the JDK field
  VarHandle bootstraps. Subagent review
  `019e6cc6-b546-7260-bf48-70d9df17552b` rejected the next revision because
  ASM `Type.METHOD` bootstrap arguments can carry primitive-array types in
  method descriptors such as `()[I`, while the guard only recognized direct
  ASM `Type.ARRAY` arguments. Subagent review
  `019e6cca-5c7f-7981-b0b9-10bdbd9e08e9` rejected the next revision because
  `MethodHandles.Lookup.unreflectGetter(Field)`,
  `unreflectSetter(Field)`, and `unreflectVarHandle(Field)` are MethodHandle
  field-access paths that can receive a `Field` from outside the transformed
  application without any original `Class.getDeclaredField` bytecode in the
  application itself.
- Implementation evidence: CFF now records original static primitive array
  literal material and a pre-CFF use census before CFF creates helper methods
  or destroys the initializer and use patterns. InvokeDynamic only treats
  private static primitive array fields as protected material when the original
  census proves read-only, non-escaping, non-identity-sensitive use; original
  application field reflection, MethodHandle field lookup, VarHandle field
  lookup/use, or primitive-array field handle constants disable this
  materialization path before invokeDynamic mutates calls. Getter callsites
  decode per-callsite encrypted material from the indy/CFF carrier, while
  initializer setter callsites consume the decoded literal with a no-op target
  so the backing field never stores the plaintext array. Original fields are
  not marked synthetic.
- Fresh validation evidence:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  passed on 2026-05-28, including fixtures for read-only table materialization,
  `MessageDigest.update(byte[])` table consumption, and unsafe array reference
  semantics, plus MethodHandle/VarHandle dynamic field access and original
  reflection guards recorded before indy mutation. Regenerated
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` with
  `test-jars/full-jvm-obf.yml`; `ReflectDump a.c` now reports `d=null`,
  `a=null`, `e=null`, and `b=null` instead of the plaintext challenge arrays.
  `javap -p a.c` still lists the private static array field symbols, but the
  runtime field values are not plaintext arrays. The stale copied-key
  `CallCore` wrapper now fails with `ArrayIndexOutOfBoundsException`, so it is
  no longer a valid internal-run proof and is recorded only as a direct-entry
  negative observation. After the third review failure, the original dynamic
  access guard was extended to treat non-handle `ConstantDynamic` bootstrap
  arguments and custom invokedynamic bootstrap arguments that carry field-name
  strings plus primitive-array type material as unsafe dynamic field protocols.
  The validation command above passed again on 2026-05-28, including new ASM
  fixtures for `ConstantDynamic` and custom invokedynamic field protocols whose
  callsite descriptors do not expose primitive-array types. After the fourth
  review failure, the guard was extended again so `ConstantDynamic.getName()`
  and `InvokeDynamicInsnNode.name` are treated as possible field-name carriers
  when paired with primitive-array type material, and
  `ConstantBootstraps.fieldVarHandle` / `staticFieldVarHandle` are recognized
  as field-resolving bootstraps. The ASM fixtures now carry the field name only
  in the condy/indy name while bootstrap arguments carry owner/type material.
  The validation command above passed again on 2026-05-28 after that change.
  After the fifth review failure, the primitive-array type detector was
  extended to recurse into ASM method types and inspect return and argument
  descriptors. The name-carried condy/indy fixtures now use
  `Type.getMethodType("()[I")` as the only primitive-array type carrier. The
  validation command above passed again on 2026-05-28 after that MethodType
  change. After the sixth review failure, the original dynamic access guard was
  extended to reject `Lookup.unreflectGetter`, `Lookup.unreflectSetter`, and
  `Lookup.unreflectVarHandle`. A regression fixture now obtains a `Field` from
  a non-input provider class and the transformed application bytecode only uses
  the `unreflect*` APIs, proving this path is not accidentally covered by the
  original field-reflection guard. The validation command above passed again on
  2026-05-28 after that unreflect change. A freshly regenerated
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` initializes successfully for
  reflection audit; `ReflectDump a.c` reports `d=null`, `a=null`, `e=null`,
  and `b=null`, while `javap -p a.c` still lists only the private field
  symbols. Final JCP-1 review `019e6ccf-7c0f-7260-b704-0248652b553b`
  returned PASS and noted only a non-blocking coverage tradeoff: the dynamic
  access guard is intentionally class-global and conservative, which can reduce
  static-array materialization coverage for classes with dynamic field
  protocols, but preserves JVM semantics and does not introduce fallback.

### [x] JCP-2: Bind Packed Entry Carriers To Internal Callsites

- Scope: extend method-parameter packing/key dispatch so internal packed
  `Object[]` call carriers contain per-callsite hidden attestation material
  derived from live caller key/CFF data. Callees must mix and validate that
  material before accepting hidden key transfer.
- Required evidence before editing: source and bytecode proof that packed
  entry carriers currently contain only argument material, a carrier-index key,
  and the transferred hidden key, with no callsite attestation accepted by the
  callee before unpacking.
- Fresh evidence before editing: after JCP-1 the stale copied-key `CallCore`
  exploit no longer succeeds and is not used as a positive proof for this
  subtask. The regenerated `ctf-obf.jar` still exposes public packed entry
  descriptors such as `a.c.h(Object[], long)`, and the GUI caller constructs an
  `Object[]` carrier before an invokedynamic callsite. In source,
  `packCallArguments`, `rewriteMethodHandleInvoke`, and
  `emitReflectiveCarrierForPlan` allocate `carrierArgumentCount(plan)`, store
  the carrier-index key via `emitCarrierIndexKeyStore`, then store the original
  arguments and hidden-key material. `installUnpackPrologue` reads the
  carrier-index key and incoming hidden key, then immediately decodes and
  unpacks argument slots. No per-callsite token or tag is stored by carrier
  builders, and no callee-side validation binds the accepted carrier to the
  protected caller key path before hidden-key transfer is trusted.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  plus regenerated `ctf-obf.jar` direct-entry negative test.
- Completion criteria: internal application callsites still run; direct
  external wrapper calls with copied hidden long fail closed; generated carrier
  slots are not direct constants and are bound to live caller material.
- Implementation evidence: hidden-key packed carriers now reserve two
  additional decoded logical slots after the carrier-index key: a per-callsite
  token and a tag. During method-parameter preparation, direct, MethodHandle,
  exact reflection, and runtime reflection candidate callsites are censused and
  assigned independent site seeds. Each seed is registered against the target
  carrier family, not just one concrete owner, so virtual/interface callsites
  and implementation callees share the same accepted site set. Carrier builders
  write token/tag slots through the existing CFF carrier-index decode path. The
  token is derived from live target-key material, live carrier-index-key
  material, and the registered per-callsite seed. Callee prologues load the
  token/tag before unpacking user arguments, first prove the token matches one
  registered callsite seed for the accepted carrier family, then recompute the
  tag from the accepted token, incoming hidden key, and accepted carrier-index
  key. Mismatch hard-fails with `SecurityException`. MethodHandle rewriting now
  retains lookup kind; `findVirtual` and `findSpecial` preserve the receiver in
  the polymorphic invoke descriptor and stack while packing only the target
  method arguments into the carrier. Reflection invoke rewriting spills any
  pre-existing operand-stack prefix to locals before generating runtime
  carrier-selection branches, then restores it immediately before
  `Method.invoke`; this keeps generated carrier selection zero-stack internally
  and preserves JVM expression semantics. A CFF verifier repair treats ASM
  `Lnull;` local descriptors as no-cast material, so null reflective targets no
  longer produce invalid `CHECKCAST null` while splitting mixed verifier local
  shapes. For MethodHandle values whose lookup and `invokeExact` use occur in
  different methods, runtime invoke-site rewriting now stores the original
  operands, matches only internal packed direct handles by packed
  `MethodHandle.type()`, protected exact declaring-class/name character guards,
  and packed `MethodType`, then constructs the attested carrier from the current
  invoke site's live key. Non-matching external handles fall through to the
  original JVM MethodHandle call shape; `revealDirect` is guarded by a narrow
  `IllegalArgumentException` catch so adapted/non-direct handles with the same
  runtime type become candidate misses instead of breaking the original call.
  Callsite attestation seed computation uses the caller's final ABI identity
  even during pre-declaration census, and duplicate pre/post census of the same
  instruction hard-fails if it would produce different seed material.
  Attestation seed material is now derived
  from live incoming/caller key and carrier-index key through a non-linear
  runtime expression instead of `value ^ mask; mask; LXOR` constant pairs. The
  unpack prologue also separates raw incoming key-transfer material from the
  canonical method key used by keyDispatch/CFF: it computes a canonical
  method-key temp after the raw key is available for both split and non-split
  carrier paths. Review `019e6d45-577f-73c1-8801-9b35048c4d7b` returned FAIL:
  constructors were still excluded from method-parameter plans, exact
  `Constructor.newInstance` had no carrier rewrite, and the integration audit
  skipped `<init>` descriptors/callsites. The same JCP-2 subtask now remains
  in progress until constructor packed-carrier attestation is implemented,
  reviewed, and freshly validated. The constructor repair now includes
  original application constructors in method-parameter plans, assigns
  constructor packed descriptors of the form `([Ljava/lang/Object;J...)V`,
  preserves overload separation with live-key-derived suffix arguments, rewrites
  direct `new` / `dup` / `invokespecial <init>` callsites, rewrites exact
  `Constructor.newInstance(Object[])`, and carries constructor MethodHandle
  lookup metadata for `findConstructor`. Constructor callee prologues validate
  the same carrier token/tag material before accepting the split hidden `long`.
  A CFF verifier-graph repair also prevents fail-closed, no-callsite
  constructors from creating island dispatch blocks for verifier-unreachable
  constructor tails after the unconditional attestation failure; CFF prunes
  verifier-unreachable real instructions before leader construction, preserving
  every executable verifier-frame block without flattening dead code. Review
  `019e6d69-00d2-79b3-bed6-a3a1463e0283` returned FAIL because overloaded
  `findConstructor` calls were not resolved by parameter types and escaped or
  array-enumerated `Constructor.newInstance` calls had no runtime candidate
  rewrite. The follow-up repair now resolves `findConstructor` and
  `getDeclaredConstructor` owners/parameter arrays through ASM source frames,
  adds runtime constructor candidates for known-owner constructor arrays, and
  covers exact, stored, array-enumerated, and MethodHandle constructor paths in
  the integration fixture. The runtime reflective carrier builder also now
  writes the split hidden-key outer slot whenever runtime arity is at least two;
  the failed case was a constructor packed descriptor with a live ABI suffix
  (`Object[], long, suffix`) where the previous `outerArity == 2` guard left
  slot 1 null.
- Fresh validation evidence:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  passed on 2026-05-28 at 15:36 Asia/Taipei after the constructor runtime
  arity fix. The focused
  `JvmMethodParameterObfuscationIntegrationTest` also passed after adding the
  escaped `findSpecial` MethodHandle runtime candidate path, escaped interface
  MethodHandle path, interface `Method.invoke` path, section-level result
  assertions, non-split canonical-key repair, and exact protected runtime
  MethodHandle name matching. The method-parameter integration fixture now asserts
  decoded token/tag carrier reads, at least two derived `long` guards for
  callsite-token whitelist plus tag validation, and a hard-fail branch in
  packed callees while running direct, virtual dispatch, static MethodHandle,
  virtual MethodHandle, escaped `findSpecial` MethodHandle, escaped interface
  MethodHandle, exact reflection, runtime reflection, and interface reflection
  paths. After the constructor repair, the focused
  `JvmMethodParameterObfuscationIntegrationTest` passed with an unused
  application constructor fixture that previously reproduced a CFF
  no-verifier-frame island target. The full JCP-2 focused validation command
  passed again on 2026-05-28. A fresh CTF CFF-only debug regeneration with
  `test-jars/codex-cff-debug.yml` completed at 15:37 Asia/Taipei during the
  initial constructor verifier-frame repair; that repair was later rejected and
  replaced by the verifier-unreachable pruning repair recorded below. A fresh
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` regenerated at 15:38 Asia/Taipei
  with `test-jars/full-jvm-obf.yml`; `jar -J-XX:-UsePerfData tf` is readable,
  `javap -classpath /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar -p a.c` shows
  packed direct-entry methods such as `h(Object[], long)`, `ReflectDump a.c`
  with `-XX:-UsePerfData` reports `d=null`, `a=null`, `e=null`, and `b=null`,
  and the stale copied-key `CallCore` direct-entry wrapper returns `<null>` for
  both `test` and `swing-gate-0427`, so the previously published direct-entry
  solve path no longer returns the flag or a plaintext validation oracle.
- Failed review evidence: subagent review
  `019e6ced-6a73-7ce3-a037-5ea253a70d92` rejected this implementation because
  the callee accepted `tokenTemp` as a free input and did not prove it came
  from a registered per-callsite seed, so the stale `CallCore` length failure
  did not prove a correct-length forged carrier would be rejected. The review
  also identified that MethodHandle `findVirtual` / `findSpecial` paths were
  not covered: lookup metadata did not retain the dispatch family, and
  `rewriteMethodHandleInvoke` would drop the receiver when rewriting
  polymorphic invoke descriptors.
- Failed review evidence: subagent review
  `019e6cfa-072d-7ca1-affa-611c26333805` rejected the follow-up because
  `findSpecial` lookup rewriting was still treated like a three-operand
  lookup and did not preserve the fourth `specialCaller` class operand. The
  review also noted that the method-parameter fixture covered static and
  virtual MethodHandle paths but not `findSpecial`. The follow-up production
  fix preserves the `specialCaller` operand and adds runtime MethodHandle
  candidate rewriting for escaped direct handles, because the first `findSpecial`
  fixture exposed an ABI mismatch when the packed handle crossed an application
  method boundary before `invokeExact`.
- Failed review evidence: subagent review
  `019e6d08-33cd-7eb0-8c26-1ecd58e1ed20` rejected the second follow-up because
  attestation constants were emitted as algebraically foldable long pairs,
  escaped interface/abstract MethodHandle and reflection paths were not covered,
  and runtime MethodHandle matching embedded owner/name strings as plaintext
  `LDC` values. The follow-up repair removed the foldable long material,
  includes interface/abstract candidates, adds escaped interface/reflection
  fixture coverage, and fixes the raw incoming versus canonical method-key
  local split needed for CFF correctness.
- Failed review evidence: subagent review
  `019e6d28-0d54-7ad0-9dec-2e284931692c` rejected the next follow-up because
  length plus `String.hashCode()` owner/name matching was collision-prone and
  not exact JVM lookup semantics. The repair now keeps the runtime string on
  stack and checks every character through an obfuscated integer transform
  before allowing a MethodHandle/reflection candidate branch; this keeps exact
  match semantics without emitting plaintext owner/name `LDC` strings.
- Failed review evidence: subagent review
  `019e6d31-3073-74c2-b63b-76f5d7d97425` rejected the next follow-up because
  runtime MethodHandle matching called `revealDirect` after only a type check,
  so adapted/non-direct handles with the same packed type could throw instead
  of falling through to the original invoke shape. The repair wraps only
  `revealDirect` in an `IllegalArgumentException` guard and treats that as a
  candidate miss.
- Failed review evidence: subagent review
  `019e6d39-aef6-7020-8d7b-a4712cac6c44` rejected the next follow-up because
  pre-pack and post-pack callsite census could compute different accepted
  seeds for the same instruction, leaving stale pre-pack material in the callee
  whitelist. The repair computes caller identity from the caller's final ABI
  when a `MethodPlan` exists, and rejects conflicting duplicate seeds for the
  same target/instruction/site-kind tuple.
- Failed review evidence: subagent review
  `019e6d87-2f23-7231-ac94-dbf7b49ac924` rejected the constructor follow-up
  because escaped reflective members were still not covered. Runtime
  `Method.invoke` / `Constructor.newInstance` candidate discovery only used
  same-method lookup or same-method array evidence, so a packed reflective
  member passed to another method could fall through with the original
  un-obfuscated argument array and break reflective ABI/key propagation. JCP-2
  remains in progress until escaped reflective members are rewritten,
  validated, and reviewed. The escaped-member repair records interprocedural
  provenance when an application method receives a `Method` or `Constructor`
  argument sourced from an exact reflective lookup, then uses the callee's
  packed argument-local mapping at the escaped `invoke` / `newInstance` site to
  recover only that recorded candidate set. Same-method `getDeclaredMethods()`
  enumeration now extracts the existing `Method.getName().equals(...)` guard
  before `Method.invoke` and narrows runtime candidates by owner plus guarded
  name. A naive all-candidate escaped-reflection repair was rejected during
  validation by `MethodTooLargeException` in
  `ParameterShapes.reflectMethods([Ljava/lang/Object;J)I`; the owner+name guard
  repair reduced the method to `helpers=139` and
  `estimatedCodeBytes=44966`, eliminating the method-size failure. The next
  focused run failed in the CFF finalizer with
  `ClassTooLargeException: Class too large: ParameterShapes` and
  `constantPoolCount=68248`; the finalizer's largest-method estimates showed
  `__neko_cff_tmat_init$*` and `__neko_cff_xmat_init$*` material initializer
  helpers in the original class. The CFF repair keeps full CFF coverage and
  moves those generated material initializer helpers through the existing
  helper-host relocation mechanism, then rewrites the generated `<clinit>`
  calls to the relocated host methods. This relocates equivalent generated
  material initialization without changing CFF block construction, block
  boundaries, dispatch semantics, or key material.
- Fresh validation evidence after escaped-reflection repair:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
  passed on 2026-05-28 at 16:03 Asia/Taipei. The full JCP-2 focused validation
  command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  passed on 2026-05-28 at 16:03 Asia/Taipei. A fresh CTF CFF-only debug
  regeneration with `test-jars/codex-cff-debug.yml` completed at 16:03
  Asia/Taipei and relocated `hosts=2 methods=66`. A fresh
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` regenerated at 16:03 Asia/Taipei
  with `test-jars/full-jvm-obf.yml`; finalizer evidence shows
  `Relocated large CFF helper sets: hosts=8 methods=462` and
  `Finalized class-integrity class-code key material: classes=3`. `jar
  -J-XX:-UsePerfData tf` is readable, `javap -classpath
  /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar -p a.c` still exposes the JVM ABI
  surface `h(Object[], long)` but no plaintext array values, `ReflectDump a.c`
  reports `d=null`, `a=null`, `e=null`, and `b=null`, and the stale copied-key
  `CallCore` direct-entry wrapper now fails before validation with
  `ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 2` for both
  `test` and `swing-gate-0427`, proving the previously published two-slot
  direct carrier no longer reaches the validation oracle.
- Failed review evidence: subagent review
  `019e6d9e-c7d3-7a61-b4df-75e49c46a50b` rejected the escaped-reflection
  repair because escaped reflective provenance was exact-callee keyed rather
  than dispatch-family keyed, so interface or virtual dispatch could record
  candidates under an interface/super method but consume them inside an
  implementation method. The review also rejected the first
  `getDeclaredMethods()` owner+name narrowing because it scanned for the first
  prior `Method.getName()` / string pair without proving that the string guard
  was tied to the same `Method` value or dominated the eventual
  `Method.invoke`. Finally, the review rejected the CTF negative evidence
  because the stale two-slot carrier failed with array bounds before exercising
  token whitelist/tag validation. JCP-2 remains in progress until these three
  points are repaired, freshly validated, and reviewed.
- Implementation evidence after the `019e6d9e-c7d3-7a61-b4df-75e49c46a50b`
  review: escaped reflective member provenance now records against every
  dispatch-family callee whose old owner/name/descriptor can receive the
  bytecode call, not only the exact declared owner. Exact `Method.invoke` and
  `Constructor.newInstance` rewrites are now tied to the actual reflective
  member receiver dataflow through `SourceValue`, local stores, and
  `CHECKCAST`, rather than a previous lookup found by bounded instruction
  scanning. Runtime reflection candidate lists are precomputed once on the
  original method before any callsite rewrite mutates the same method, so
  earlier direct/interface/MethodHandle rewrites cannot pollute dataflow
  analysis for later reflective calls. `getDeclaredMethods()` /
  `getDeclaredConstructors()` array enumeration now recovers the array owner
  from SourceAnalyzer `AALOAD` provenance, while guarded method-name narrowing
  still requires the same `Method` local and a dominating
  `getName().equals(...)` false branch that skips the eventual invoke.
  Split packed entries whose ABI carries a split `long` but whose original
  method did not expose a key-local parameter are now modeled as synthetic
  hidden-key entries, so their carrier-index key, token, and tag slots are
  mandatory. Carrier attestation shape validation and token/tag validation run
  before the canonical method key is stored for CFF, making forged carriers
  fail closed before invalid entry key material can dispatch into CFF poison
  returns. Attestation slot reads use bounded in-carrier index normalization
  only for the pre-CFF validation loads; normal argument loads still use the
  carrier-index decode after validation has accepted the token/tag.
- Failed review evidence: subagent review
  `019e6dcb-d661-74e2-a10a-ef7c9cd28df7` rejected the follow-up because exact
  reflection lookup planning and array-enumerated reflection still retained
  stale bounded backward-scan fallbacks. A misleading prior `Class[]`,
  owner/name constant, `getDeclaredMethods()`, or `getDeclaredConstructors()`
  call could be selected instead of the actual operand feeding the current
  reflective call. The review also rejected filtering labels without verifier
  frames as a CFF block-selection workaround.
- Implementation evidence after the `019e6dcb-d661-74e2-a10a-ef7c9cd28df7`
  review: exact `getMethod` / `getDeclaredMethod` planning now uses only
  SourceAnalyzer dataflow for receiver class, name, and parameter-array
  operands. The Class[] parser follows `ALOAD`, `CHECKCAST`, `DUP`, constant
  integer indexes, and the actual `ANEWARRAY java/lang/Class` allocation feeding
  the current call; unrelated earlier arrays no longer contribute. Runtime
  `getDeclaredMethods()` / `getDeclaredConstructors()` enumeration now accepts
  only SourceAnalyzer `AALOAD` provenance from the actual member array source,
  and the stale previous-array-owner fallbacks were removed. The integration
  fixture includes misleading earlier owner/name/Class[] values and unrelated
  method/constructor arrays in the same method before the real reflection sites.
  The CFF no-frame repair was changed from label filtering to verifier-unreachable
  instruction pruning before CFF leader construction; the reproduced failure was
  a constructor tail label with `prev=java/lang/Object.<init>()V` and `next=RETURN`
  that ASM did not assign a frame because the tail was unreachable.
- Fresh validation evidence after the source-only reflection and CFF cleanup
  repair: the focused command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
  passed on 2026-05-28 at 17:27 Asia/Taipei. The fixture now covers direct,
  virtual/interface, MethodHandle, escaped MethodHandle, exact reflection,
  escaped reflection through interface dispatch, array-enumerated reflection
  with unrelated name guards, exact and escaped constructor reflection, runtime
  constructor arrays, constructor MethodHandle paths, and an exact-shape forged
  packed carrier for `ParameterShapes.add(Object[], long)`. The forged carrier
  uses the current exact five-slot carrier shape and fails with
  `SecurityException`, proving token/tag validation is exercised rather than
  an array-bounds failure.
- Fresh focused regression evidence: the full JCP-2 focused command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  passed on 2026-05-28 at 17:28 Asia/Taipei.
- Fresh CTF validation evidence: `neko-cli/build/install/neko-cli/bin/neko-cli
  obfuscate -c test-jars/codex-cff-debug.yml -i test-jars/ctf.jar -o
  build/tmp/ctf-cff-debug.jar` completed on 2026-05-28 at 17:29 Asia/Taipei
  and relocated `hosts=2 methods=65`. A fresh full JVM
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` regenerated with
  `test-jars/full-jvm-obf.yml` at 17:29 Asia/Taipei; finalizer output shows
  `Relocated large CFF helper sets: hosts=7 methods=424` and
  `Finalized class-integrity class-code key material: classes=3`. `jar
  -J-XX:-UsePerfData tf` is readable, `javap -classpath
  /mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar -p a.c` still shows only the JVM
  ABI packed entry surface, and `ReflectDump a.c` reports `d=null`, `a=null`,
  `e=null`, and `b=null`. The stale copied-key `CallCore` direct-entry wrapper
  now throws `SecurityException` for both `test` and `swing-gate-0427`. A
  separate exact-shape forged `a.c.h(Object[], long)` carrier with four slots
  also prints `FORGED CARRIER REJECTED`, proving the regenerated CTF artifact
  rejects the direct packed-entry forgery through attestation rather than
  array bounds.
- Failed review evidence: subagent review
  `019e6de3-e913-7d93-bbf2-5e128da0f925` rejected the source-only reflection
  cleanup because `storedLocalValue` still retained a bounded textual
  backward scan after SourceAnalyzer lookup failed. A misleading store to the
  same local in an unreachable or non-dominating branch could be selected as
  the parameter-array source for a later reflective lookup.
- Implementation evidence after the `019e6de3-e913-7d93-bbf2-5e128da0f925`
  review: `storedLocalValue` no longer scans previous instructions. It now
  reads the SourceAnalyzer frame at the current load instruction and resolves
  local-store source nodes through each store instruction's own stack frame,
  preserving bytecode dataflow and excluding unrelated textual stores. The
  integration fixture adds a same-local false source inside a `throw` branch
  before the real reflective parameter array; the transformed path must ignore
  the non-feeding store and still rewrite the actual lookup/callsite.
- Fresh validation evidence after frame-local provenance repair: the focused
  command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
  passed on 2026-05-28 at 17:27 Asia/Taipei. The full JCP-2 focused command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  passed on 2026-05-28 at 17:28 Asia/Taipei. Fresh `ctf.jar` CFF-only debug
  regeneration completed at 17:29 Asia/Taipei with `hosts=2 methods=65`, and
  fresh full JVM `ctf-obf.jar` regeneration completed at 17:29 Asia/Taipei
  with `hosts=7 methods=424` and `class-integrity` material for 3 classes.
  Local audit of that artifact shows 10 readable classes, `a.c` exposes only
  the JVM ABI packed methods and private fields, `ReflectDump a.c` reports
  `c=0`, `d=null`, `a=null`, `e=null`, `b=null`, and a non-null `f`, stale
  `CallCore` wrappers for both `test` and `swing-gate-0427` throw
  `SecurityException`, and the exact-shape forged carrier prints
  `FORGED CARRIER REJECTED`.
- Failed review evidence: subagent review
  `019e6dee-14f4-74c2-805e-ade9c20e508c` rejected the follow-up because
  MethodHandle lookup planning still used bounded textual backward scans.
  `previousMethodHandleLookupTarget` scanned prior `LDC` owner/name material
  for `findStatic` / `findVirtual` / `findSpecial`, and
  `previousMethodTypeParameterTypes` scanned prior `MethodType.methodType`
  producers without proving they fed the current `MethodHandles.Lookup`
  call. This could bind a packed MethodHandle callsite seed to unrelated
  owner/name/MethodType material in the same method.
- Implementation evidence after the `019e6dee-14f4-74c2-805e-ade9c20e508c`
  review: MethodHandle lookup planning now reads the SourceAnalyzer frame at
  the actual `findStatic`, `findVirtual`, `findSpecial`, or `findConstructor`
  call and derives the owner class, member name, and MethodType parameter
  array from the operand `SourceValue`s feeding that call. Same-method
  `MethodHandle.invoke` / `invokeExact` planning now resolves the actual
  MethodHandle receiver value through SourceAnalyzer and local-store frames
  rather than selecting a previous lookup textually. The integration fixture
  adds misleading MethodHandle owner/name/MethodType constants and a stale
  constructor MethodType branch before the real lookup; the transformed
  MethodHandle calls must ignore the stale material and still rewrite the
  actual static, virtual, special, interface, and constructor MethodHandle
  paths. During validation this exposed an exact/runtime MethodHandle census
  classification mismatch for constructor handles after lookup rewriting:
  transform-time exact or runtime MethodHandle carrier builders could
  otherwise create a new site seed after callee prologue generation. The
  MethodHandle carrier builders now reuse whichever exact or runtime
  MethodHandle attestation kind was already recorded for that invoke site
  during prepare, preserving the prepared per-site seed instead of generating
  a late unaccepted token.
- Fresh validation evidence after MethodHandle source-provenance and
  prepared-site-kind repair: the focused command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
  passed on 2026-05-28 at 17:46 Asia/Taipei. The full JCP-2 focused command
  with `JvmMethodParameterObfuscationIntegrationTest`,
  `CffStrongEntrySeedRegressionTest`, and
  `ControlFlowFlatteningAlgebraicAuditTest` passed on 2026-05-28 at 17:46
  Asia/Taipei. Fresh `ctf.jar` CFF-only debug regeneration completed at
  17:47:39 Asia/Taipei with `hosts=2 methods=65`. Fresh full JVM
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` regeneration completed at
  17:47:53 Asia/Taipei with `hosts=7 methods=424` and `class-integrity`
  material for 3 classes. Local audit of that artifact shows 10 readable
  classes, `a.c` exposes only the JVM ABI packed methods and private fields,
  `ReflectDump a.c` reports `c=0`, `d=null`, `a=null`, `e=null`, `b=null`,
  and a non-null `f`, stale `CallCore` wrappers for both `test` and
  `swing-gate-0427` throw `SecurityException`, and the exact-shape forged
  carrier prints `FORGED CARRIER REJECTED`.
- Final review evidence: subagent review
  `019e6dfd-1afb-7bc1-8e9a-20ac4170c8b2` returned PASS. It verified that
  `storedLocalValue` is frame-dataflow based, MethodHandle lookup and
  `invokeExact` planning are sourced from actual operands/receiver values,
  exact/runtime MethodHandle attestation kind reuse avoids late unaccepted
  seeds, reflection/constructor/escaped/member-array paths remain compatible,
  and the CFF verifier-unreachable pruning repair does not filter labels or
  reduce block coverage. The only residual note was non-blocking:
  `previousMethodArrayGuardName` still uses a bounded textual scan, but it is
  tied back to the actual reflective member source locals and is not the
  rejected MethodHandle or stored-local fallback path.

### [ ] JCP-3: Scatter Reversible Validation Helpers And Table Checks

- Scope: harden small reversible helper chains such as standalone byte/int
  transforms and table-compare validators. Route table loads, transform steps,
  and final compares through multiple CFF-bound stages and existing indy
  callsite material rather than one clean helper plus one static table compare.
- Required evidence before editing: source and artifact proof of the current
  `j -> l -> table compare` solver path in `ctf-obf.jar`; identification of a
  generic transform surface that applies to application validation code without
  fixture-specific matching.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`
  plus regenerated `ctf-obf.jar` bytecode audit.
- Completion criteria: regenerated validation no longer presents a single
  reversible helper/table-compare chain; final decision remains live-key and
  CFF-data bound; performance does not regress against the JCP baseline.

### [ ] JCP-4: Add Measurement-Only Performance Gate Harness

- Scope: add or update JVM full-obfuscation performance validation so the
  requested rows are captured as a repeatable focused measurement harness
  without enforcing the final thresholds yet.
- Required evidence before editing: current local run and ablation medians
  recorded above.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`
  with XML/result report inspection.
- Completion criteria: the test records fresh `Calc`, `Seq`, `Parallel`, and
  `VThreads` timings and emits enough report data for later threshold
  enforcement; it must not fail merely because the known pre-repair baseline is
  above the requested thresholds.

### [ ] JCP-5: Refresh Performance Evidence And Select Generic Repair Path

- Scope: collect fresh profiler/topology evidence for current full-obfuscated
  `test21` and `test` artifacts, then identify the exact runtime paths that
  must be repaired. This subtask may update the later JCP-6/JCP-7 descriptions
  with evidence-backed repair boundaries before implementation.
- Required evidence before editing later performance code: fresh delayed JFR or
  equivalent profiler output for `test21-obf.jar`; fresh profiler or
  bytecode/topology evidence for `test-obf.jar`; source-path evidence tying the
  hot methods to generic transform mechanisms.
- Validation command or runtime target: direct `java -XX:-UsePerfData -jar`
  runs plus profiler commands using repository-local build directories.
- Completion criteria: the plan records which exact generic runtime paths
  explain the `Seq`, `Parallel`, `VThreads`, and `Calc` gaps well enough to
  justify the following implementation subtasks. No production code changes are
  made in this subtask.

### [ ] JCP-6: Optimize CFF Hot Paths Without Reducing CFF

- Scope: optimize the generic CFF relocated-helper relay path proven by fresh
  JFR/topology evidence, and any additional generic CFF hot path found by
  JCP-5. Candidate repairs include replacing compile-time-known selector relay
  dispatch with direct helper binding or equivalent monomorphic dispatch, and
  reducing repeated CFF material recomputation on hot loop paths while
  preserving the same block coverage, fake/poison cases, dynamic key
  propagation, and transition semantics.
- Required evidence before editing: fresh delayed JFR or equivalent profiler
  output from JCP-5 showing the exact current hot CFF path, plus bytecode/source
  proof for the selected generic repair.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  and direct `java -XX:-UsePerfData -jar` runs of regenerated `test21-obf.jar`.
- Completion criteria: `test21` Seq <= 400 ms and every matrix `Parallel` /
  `VThreads` line <= 15 ms on a fresh full-obf artifact; CFF block metrics and
  coverage are preserved; no CFF coverage or block-granularity reduction is
  introduced.

### [ ] JCP-7: Reduce Full Constant/String Hot-Path Runtime Cost

- Scope: optimize protected numeric/string decode runtime overhead responsible
  for the `TEST full` versus `full-no-const-string` gap. Candidate repairs must
  preserve derived material and semantic entanglement, but may hoist repeated
  live-base material within a method, share already-derived primitive locals,
  or replace expensive repeated decode helpers with equivalent monomorphic
  keyed code.
- Required evidence before editing: fresh profiler or bytecode/topology proof
  showing the exact full constant/string hot path in `test-obf.jar`, not just
  the ablation delta.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Completion criteria: `test.jar` full JVM obfuscated `Calc` <= 200 ms on a
  fresh artifact; generated material remains runtime-derived and not direct
  plaintext `ldc`; no self-canceling key logic is introduced.

### [ ] JCP-8: Enforce Final Performance Thresholds

- Scope: convert the measurement harness from JCP-4 into an enforcing gate for
  the requested thresholds after the performance repairs have been validated.
- Required evidence: fresh JCP-6 and JCP-7 validation showing the thresholds
  are attainable on regenerated full-obf artifacts.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`
  with XML/result report inspection.
- Completion criteria: focused JVM full-obfuscation test fails if `Calc` >
  200 ms, `Seq` > 400 ms, or any `Parallel` / `VThreads` matrix timing exceeds
  15 ms; production code contains no benchmark-specific checks.

### [ ] JCP-9: Global Class-Load-State Key Table Initialization

- Scope: repair the `g18` and classtable key-table initialization path so
  dynamic table perturbation depends on true current JVM global class-loading
  state, including load-order-sensitive state, rather than only mixing the low
  16 bits of a local perturbation value.
- Required evidence before editing: source and generated-artifact proof of the
  current `g18` / classtable initialization path, the exact word-width or mask
  that limits perturbation to the low 16 bits, and a concrete runtime trace or
  bytecode artifact showing that different class-loading orders do not
  currently produce meaningfully different full-width key-table state.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`
  plus a focused regenerated-artifact audit that runs two deterministic class
  loading orders and proves full-width key-table initialization changes with
  the observed global load state.
- Completion criteria: key-table initialization is generic and architecture
  level; it consumes live global class-loading state across more than the low
  16 bits; class initialization remains deterministic for a fixed load order;
  CFF, invokeDynamic, constant, string, and method-parameter transforms remain
  compatible under the full JVM profile; no static key exposure, descriptor-only
  recomputation, or fallback path is introduced.

### [ ] JCP-10: Final Six-Jar Full JVM Regeneration And Acceptance

- Scope: regenerate all six test jars with full JVM obfuscation into
  `/mnt/d/Code/Reverse/NekoOBF/m` using `originname-obf.jar`, then run the
  final CTF hardening and performance audits.
- Required evidence: fresh output file list, `jar tf` readability checks,
  CTF reflection/direct-entry negative checks, focused Gradle XML, and direct
  performance runs.
- Validation command or runtime target:
  full CLI regeneration for `crackme.jar`, `ctf.jar`, `evaluator.jar`,
  `snake.jar`, `test.jar`, and `test21.jar`; `jar tf` on every output; direct
  runs for `test-obf.jar` and `test21-obf.jar`.
- Completion criteria: all six outputs exist and are readable; CTF plaintext
  table/direct-entry exploit no longer works on the regenerated artifact;
  performance thresholds are met; final plan review returns PASS.
