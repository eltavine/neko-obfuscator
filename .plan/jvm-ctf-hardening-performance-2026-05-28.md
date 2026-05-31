# JVM CTF Hardening And Runtime Performance Plan - 2026-05-28

## Scope

This plan covers JVM-only full obfuscation for the `test-jars/full-jvm-obf.yml`
profile. It addresses two concrete problem groups:

- CTF recoverability in the freshly generated
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar`.
- JVM full-obfuscation runtime performance for `test.jar` and `test21.jar`.
- Additional downstream full-profile smoke/performance coverage for the
  user-requested `test-jars/full.jar` Java 21 fixture.

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

### [x] JCP-3: Scatter Reversible Validation Helpers And Table Checks

- Scope: harden small reversible helper chains such as standalone byte/int
  transforms and table-compare validators. Route table loads, transform steps,
  and final compares through multiple CFF-bound stages and existing indy
  callsite material rather than one clean helper plus one static table compare.
  This subtask also includes the generic CFF helper-relocation,
  transition-material, and transition-outliner repairs required by that
  hardening path to generate and run under full JVM obfuscation without
  weakening block coverage, key propagation, or transition semantics.
- Required evidence before editing: source and artifact proof of the current
  `j -> l -> table compare` solver path in `ctf-obf.jar`; identification of a
  generic transform surface that applies to application validation code without
  fixture-specific matching.
- Fresh evidence before editing: original `test-jars/ctf.jar` bytecode in
  `Vault.accepts(String)` has the compact validation chain: UTF-8 bytes are
  length-checked against 15, each byte is mixed with `MASK[i]`, adjusted by
  `31 + 17 * i`, passed through the standalone reversible
  `rotateLeft8(int,int)` helper, accumulated as
  `acc |= rotated ^ state ^ CHECK[i]`, and the final boolean is `acc == 0`.
  The source artifact was captured in
  `build/tmp/ctf-vault-original-jcp3-evidence.javap.txt`; the key sequence is
  the `baload`, `MASK` `iaload`, `rotateLeft8:(II)I`, `CHECK` `iaload`,
  `ixor`, `ior`, and final `ifne`/`ireturn` path. The current regenerated
  `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` no longer exposes plaintext
  primitive arrays or accepts forged packed carriers after JCP-1/JCP-2, but
  `javap -c -p a.c` still exposes a distinct packed private
  `j(Object[], long)` validation method and a distinct packed private
  `l(Object[], long)` integer helper. The `j` bytecode still contains the
  validation-loop shape through protected `baload`/`iaload` table loads,
  accumulator-style integer mixing, and later `ireturn` exits, while `l`
  remains an independently analyzable integer transform method. The current
  transform surface is also incomplete in source: `JvmValidationSinkHardeningPass`
  only recognizes fixed `String.equals` validation sinks. It does not recognize
  primitive table-compare validators such as `acc |= expression ^ table[i]`,
  even though that shape is generic application validation code and is not
  tied to `ctf.jar`, class names, method names, passphrases, or table values.
- In-progress failure evidence: the first primitive-table implementation
  replaced one accumulator `IXOR` in the application method with four staged
  calls (`entry`, `guard`, `data`, `final`) so the final stage could be
  transformed by invokeDynamic in the same application method. The focused
  validation-sink fixture passed, but the full JCP-3 Gradle validation failed
  during the `test21.jar` CFF-only-stack ablation write:
  `MethodTooLargeException: Method too large: a/a.main ([Ljava/lang/String;)V`
  with `estimatedCodeBytes=80521`, and the same log recorded
  `validationSinkHardening appliedFull=1`. This proves the repair path must
  keep primitive validation hardening but move final-stage indy protection back
  into generated helper code instead of expanding large application methods
  with multiple extra callsites.
- Repair approach evidence: invokeDynamic already has a generated-helper
  size-pressure guard, while `siteSpec` explicitly permits generated validation
  final-stage calls and rejects other generated-name calls. Therefore the
  generic repair is to leave only one live-keyed primitive entry call in the
  application method, keep the split `value`/`guard`/`data`/`final` helper
  stages, and allow generated helpers under size pressure to run invokeDynamic
  only when they contain validation final-stage calls. This preserves
  obfuscation strength and coverage without weakening CFF block construction or
  adding fixture-specific skips.
- Final implementation evidence: generated-helper final-stage indy cannot be
  used as the primitive-table proof point because generated CFF helpers in the
  current pipeline have no flow-key metadata (`Control-flow generated helpers
  missing flow keys: candidates=44 keyed=0` in the focused fixture, and
  `candidates=61 keyed=0` in `test21.jar`). The final implementation therefore
  protects the single primitive validation entry callsite itself with
  invokeDynamic, descriptor `(IIJJJ)I`, while the generated entry helper still
  splits the computation through live-keyed `value`, `data`, `guard`, and
  `final` stages. The application method receives only one extra protected
  callsite for each matched table accumulator, so the transform does not add
  multi-call staged growth to large methods.
- Current validation evidence: after the validation-sink/key-data repair, the
  minimized invokeDynamic reference fixture exposed an independent generic
  constructor path failure in the `methodParameterObfuscation` + CFF
  interaction. Fresh generated `IndyReferenceShapes$Inner.<init>` bytecode
  showed the incoming raw key first decoded correctly into the canonical method
  key, but `rewriteFirstKeyDispatchLoad` then overwrote the active key local
  with a fallback expression that omitted the final incoming-key xor. The
  concrete observed values were: full mix produced target seed
  `4435259575852332461`, while the fallback overwrite produced
  `3932239884164406260`. The repair is generic: the fallback now discards the
  old stack value and delegates to `JvmKeyDispatchPass.emitIncomingKeyMix(...)`
  instead of hand-emitting a shortened expression. The same constructor audit
  also proved CFF entry data digest had been reading method-parameter synthetic
  packed ABI locals; those hidden-key/suffix transport locals are now recorded
  by method-parameter planning and excluded from CFF's primitive argument data
  digest.
- Historical CFF size-blocker evidence: the failing
  `build/test-jvm-runtime-ablation/obfusjack-cff-only-stack.obfuscate.stdout.log`
  records `a/a.main([Ljava/lang/String;)V` with 168 CFF island helpers,
  126 trivial candidates, no fake helpers, 42 multi-real helpers, 255 real
  blocks, 255 result tokens, 168 poison rows, and finalizer
  `estimatedCodeBytes=80396`. Source inspection shows that in outliner mode
  `insertIslandDispatchers` routes every group, including single-block/no-fake
  groups, through a generated group dispatch helper and caller-side result
  router. That caller glue is generic CFF dispatcher overhead, not validation
  fixture logic. A tested repair attempt kept full block coverage and live
  `pc/data/key` token validation while emitting compact inline dispatch for
  single-block/no-fake groups. The focused CFF regression passed, but fresh
  `test21.jar` cff-only-stack regeneration increased the largest method
  estimate from `80396` to `82883`, proving the existing helper/result-router
  path is smaller than inline token validation for this caller. That attempt
  was reverted; the size repair must instead reduce duplicated caller-side
  state glue or move equivalent state restoration into an existing keyed helper
  path.
- Prior regenerated CTF evidence: `/mnt/d/Code/Reverse/NekoOBF/m/ctf-obf.jar` was
  freshly regenerated at 18:33:08 Asia/Taipei with full JVM obfuscation.
  Coverage reported `validationSinkHardening appliedFull=1`,
  `invokeDynamic appliedFull=18`, `constantObfuscation appliedFull=10`, and
  `stringObfuscation appliedFull=5`. `ReflectDump a.c` reports `c=0`,
  `d=null`, `a=null`, `e=null`, `b=null`, and non-null `f`; stale direct
  `CallCore` wrappers for both `test` and `swing-gate-0427` throw
  `SecurityException`; `ForgedCore` prints `FORGED CARRIER REJECTED`.
  Bytecode audit in `build/tmp/ctf-ac-jcp3-after.javap.txt` shows the primitive
  validation accumulator in `j(Object[], long)` no longer remains a clean
  `value ^ table` path: immediately before the accumulator `ior`, it routes
  through an indy-protected primitive entry
  `InvokeDynamic ... (IIJJJ)I`, and the surrounding loop still receives live
  packed/CFF state and protected array material rather than plaintext static
  tables.
- Historical outlined-indy runtime blocker evidence: the generic CFF size repairs now allow
  `test21.jar` `full-no-const-string` to generate successfully without
  `MethodTooLargeException`; the freshly generated
  `build/test-jvm-runtime-ablation/obfusjack-current-full-no-const-string.jar`
  has `a/a.main([Ljava/lang/String;)V` at code length `65068`. Runtime still
  fails deterministically at the first outlined indy site with
  `StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 46`
  in the indy resolver path `a.b.e -> a.ja.tja -> a.a.main`, proving the
  encrypted payload is decoded with mismatched live callsite material rather
  than a target lookup failure. Bytecode inspection shows the caller computes
  the live flow word at the original CFF site with
  `a/b.o:(IIIII[Ljava/lang/Object;IJ)J`, then calls relocated helper
  `a/ja.tja:(JJ)Ljava/io/PrintStream;`; the helper contains only
  `lload_0`, `lload_2`, and the embedded indy `(JJ)Ljava/io/PrintStream;`.
  The helper bootstrap still carries resolver slot `19` and the original
  material handle `REF_getStatic a/a.i:[Ljava/lang/Object;`. The mapping file
  proves this helper originated as
  `a/a.__neko_indysite$...(JJ)Ljava/io/PrintStream; -> tja`, while source
  inspection proves `appendRelocatableRenamedGeneratedHelpers` relocates
  renamed generated helpers that are not referenced by a `Handle`. Therefore
  outlined indy-site helpers are callsite-bound generated methods whose
  internal indy receives a different VM-provided bootstrap `Lookup` after CFF
  helper relocation. The repair scope is to keep only `__neko_indysite$`
  outlined callsite helpers in their declaring owner while retaining their
  renaming, indy protection, live key/flow arguments, and relocation of true
  CFF/material helpers.
- Historical generated-material size evidence: after rebuilding the CLI from the
  current sources, fresh `test21.jar` `full-no-const-string` generation now
  reaches output writing and fails on relocated helper host `a/la` with
  `MethodTooLargeException: Method too large: a/la.yha ()V`. The mapping file
  identifies `yha` as the renamed `a/a.__neko_indyinit$()V` method. The writer
  diagnostic shows the failing method starts by constructing encoded indy
  material arrays, including `BIPUSH 10`, `NEWARRAY 11`, `LDC <long>`, and
  `LASTORE`; the largest-method estimate is `65657` bytes. This proves the
  current blocker is a single generated indy material initializer, not an
  application method, CFF block count, or outlined callsite lookup path.
  The repair scope is to split the existing generated indy material init surface
  into bounded generated chunk helpers that fill the same live carrier table,
  while preserving the same encoded per-site material, bootstrap handles,
  callsite seeds, and generated-helper relocation/renaming behavior.
- Historical validation-sink semantic evidence: after indy material-init chunking
  and shared indy key decoding, fresh fixed-seed `test21.jar`
  `full-no-const-string` generation completes and the runtime no longer throws
  in the indy resolver path. The output still diverges from original semantics:
  the string-switch row that prints `42` in the original and prior obfuscated
  artifact now prints `-1`; `area(circle)` prints `0.0` instead of
  `12.566370614359172`; clone hash output prints `7` instead of `3820`.
  Targeted resolver instrumentation proved the first string-switch failure is
  not encrypted indy payload corruption: the `java/lang/String.hashCode()I`
  payload resolves first, followed by the generated validation final-stage
  callsite `a/a.__neko_vsend1$35(JJI)Z`, and the final-stage result returns
  false. Source inspection of `JvmValidationSinkHardeningPass.liveWord` and
  `emitLiveWord` identifies the exact invariant gap: transform-time masks mix
  `metadata.classKeyTable().values()[idx]`, while runtime masks re-read the
  mutable sealed class-key word table. That table is intentionally perturbed by
  runtime class-load state, so validation-sink decoding can diverge from the
  transform-time mask even when the indy resolver payload and target lookup are
  correct. The generic repair target is the validation-sink mask formula:
  preserve live CFF/key/data binding, but remove the unstable runtime class-key
  table dependency from this generated validation material unless it is stored
  as stable per-site protected material.
- Repair evidence after validation-sink live-data binding fix: the generated
  validation live word no longer rereads the runtime sealed class-key word
  table. Instead, the table selector contributes a deterministic per-site
  index-derived word at transform time and in emitted bytecode, while the
  remaining mask terms still consume live CFF state, path/block/guard data,
  pc/data words, and the method key. A second targeted probe showed that the
  string-switch final-stage failure persisted after removing the unstable
  class-key-table dependency because the inserted validation helper arguments
  were still encoded with a branch-local CFF live mask that did not match the
  helper-side data word. The final repair now encodes the seed/tag arguments
  from the live canonical method key plus the validation data word: compile
  time stores `value ^ argumentKeyMask(methodKey, siteSeed, domain)`, runtime
  recomputes the same method-key mask from the live hidden-key local, then
  applies the existing validation-data mask so the helper receives values bound
  to live method-entry material and current CFF data.
- Fresh validation evidence after final cleanup: `env
  GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home
  JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp
  ./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests
  dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest` passed
  on 2026-05-29 after removing temporary `DEBUG-JCP3` / `DEBUG-KXFER`
  instrumentation and test bisection switches. A source grep for
  `DEBUG-JCP3`, `DEBUG-KXFER`, `neko.debug`, and `neko.test.*.enabled`
  returns no matches in the JVM transform sources or the invokeDynamic
  integration test. The invokeDynamic integration audit was updated to verify
  the real shared bootstrap helper owner instead of assuming helpers must live
  on the reference-site owner class.
- Fresh test21 semantic evidence after final cleanup: `env
  JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp
  neko-cli/build/install/neko-cli/bin/neko-cli obfuscate --config
  build/test-jvm-runtime-ablation/configs/full-no-const-string.yml --input
  test-jars/test21.jar --output
  build/test-jvm-runtime-ablation/obfusjack-current-full-no-const-string.jar`
  completed on 2026-05-29. A direct
  `java -XX:-UsePerfData -Djava.io.tmpdir=... -jar` run of that fresh artifact
  exited 0 and includes `GREEN`, `42`, `Inner.value=106`,
  `area(circle)=12.566370614359172`, and
  `clone equal? true hash1=3820 hash2=3820`. The same run reports
  `Platform threads: 145 ms`, `Virtual threads: 162 ms`, `Seq: 546 ms`,
  `Parallel: 837 ms`, and `VThreads: 857 ms`; these timings remain JCP-4+
  performance work and are not treated as JCP-3 semantic completion evidence.
- Fresh CTF artifact evidence after final cleanup: `env
  JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp
  neko-cli/build/install/neko-cli/bin/neko-cli obfuscate --config
  test-jars/full-jvm-obf.yml --input test-jars/ctf.jar --output
  build/test-jvm-runtime-ablation/ctf-current-full.jar` completed on
  2026-05-29. Coverage reported `validationSinkHardening appliedFull=1`,
  `invokeDynamic appliedFull=18`, `constantObfuscation appliedFull=10`, and
  `stringObfuscation appliedFull=5`. Fresh `ReflectDump a.c` reports `c=0`,
  `d=null`, `a=null`, `e=null`, `b=null`, and non-null `f`; stale direct
  `CallCore` wrappers for both `test` and `swing-gate-0427` throw
  `SecurityException`; `ForgedCore` prints `FORGED CARRIER REJECTED`.
  Bytecode audit in `build/tmp/ctf-ac-current-jcp3-final.javap.txt` still
  shows the primitive validation entry protected by an invokedynamic descriptor
  `(IIJJJ)I`, while the surrounding primitive arrays are accessed as protected
  object-carrier material rather than plaintext static array values.
- Review-scope correction evidence: final review
  `019e71d9-5cb5-7650-be9f-8e0146445a9c` returned FAIL because the proposed
  JCP-3 review scope omitted executable CFF files that directly contributed to
  the fresh `test21` and CTF evidence. Those files are now explicitly included
  in this JCP-3 scope: `CffClassSetup` relocates generated CFF helpers by
  method count or estimated host code size and keeps outlined indy callsite
  helpers in their declaring owner; `CffMaterialTables` extends transition
  material rows so transition PC state is bound to the current CFF data digest;
  `CffTransitionOutliner`, `CffDispatchEmitter`, `CffSharedState`, and
  `ControlFlowFlatteningPass` preserve full transition semantics while moving
  duplicated caller-side state restoration into keyed material/helper paths and
  sharing poison/result routing where equivalent. These changes are not final
  performance acceptance for JCP-4+; they are part of the JCP-3 correctness and
  size evidence because the freshly validated artifacts were generated with
  them present.
- Final review evidence: after the scope correction, subagent review
  `019e71d9-5cb5-7650-be9f-8e0146445a9c` returned PASS for the expanded JCP-3
  diff. No blocking issues were found in the corrected scope.
- Superseded validation evidence retained for history: `env
  GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home
  JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp
  ./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests
  dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest` passed
  on 2026-05-29. A fresh fixed-seed `test21.jar` `full-no-const-string`
  artifact generated successfully at
  `build/test-jvm-runtime-ablation/obfusjack-current-full-no-const-string.jar`;
  direct runtime output now includes `GREEN`, `42`,
  `area(circle)=12.566370614359172`, and
  `clone equal? true hash1=3820 hash2=3820`, with no `DEBUG-JCP3` or
  `neko.debug` markers in the transform sources. The same run still reports
  performance rows around `Platform threads: 140 ms`, `Virtual threads: 172 ms`,
  `Seq: 496 ms`, `Parallel: 830 ms`, and `VThreads: 911 ms`, so performance
  remains deferred to JCP-4 and later measurement/repair tasks rather than
  treated as JCP-3 semantic evidence.
- Fresh CTF artifact evidence after the same repair: `ctf.jar` full JVM
  obfuscation generated successfully to
  `build/test-jvm-runtime-ablation/ctf-current-full.jar` on 2026-05-29.
  Coverage again reported `validationSinkHardening appliedFull=1`,
  `invokeDynamic appliedFull=18`, `constantObfuscation appliedFull=10`, and
  `stringObfuscation appliedFull=5`. `ReflectDump a.c` reports `c=0`,
  `d=null`, `a=null`, `e=null`, `b=null`, and non-null `f`; stale `CallCore`
  direct-entry wrappers for both `test` and `swing-gate-0427` throw
  `SecurityException`; `ForgedCore swing-gate-0427` prints
  `FORGED CARRIER REJECTED`. Bytecode audit in
  `build/tmp/ctf-ac-current-jcp3-final.javap.txt` shows the primitive
  validation entry is protected through an invokedynamic descriptor
  `(IIJJJ)I`, while static primitive arrays remain protected material rather
  than plaintext reflective fields.
- Validation command or runtime target:
  focused JCP-3 Gradle regressions for CFF algebra and invokeDynamic
  integration, plus regenerated `test21.jar` `full-no-const-string` and
  regenerated `ctf.jar` full-JVM runtime/bytecode audits.
- Completion criteria: regenerated validation no longer presents a single
  reversible helper/table-compare chain; final decision remains live-key and
  CFF-data bound; JCP-3 itself adds only one primitive entry indy callsite per
  matched accumulator; constructor/method-parameter key transfer preserves the
  canonical method key before CFF dispatch; CFF transition material remains
  bound to live data digest while helper relocation/outlining preserves full
  CFF semantics; and the focused JCP-3 command plus regenerated CTF/test21
  semantic audits pass freshly. Full performance thresholds remain open in
  JCP-4 and later subtasks.

### [ ] JCP-4: Add Measurement-Only Performance Gate Harness

- Scope: add or update JVM full-obfuscation performance validation so the
  requested rows are captured as a repeatable focused measurement harness
  without enforcing the final thresholds yet. Include `test-jars/full.jar` as
  a non-enforcing full-profile smoke/performance fixture, run without a
  `quick` / `--quick` application argument, so later repairs do not regress the
  broader Java 21 feature surface.
- Required evidence before editing: current local run and ablation medians
  recorded above.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`
  with XML/result report inspection.
- Completion criteria: the test records fresh `Calc`, `Seq`, `Parallel`, and
  `VThreads` timings and emits enough report data for later threshold
  enforcement; it must not fail merely because the known pre-repair baseline is
  above the requested thresholds.

### [-] JCP-4A: Repair Shared String Decode Canonical CFF Pc Binding

- Scope: repair the generic shared string decode/CFF binding invariant before
  JCP-4 measurement can complete. The runtime shared string tail was receiving
  `metadata.pcLocal()` directly from transformed callers. In large CFF methods
  that local can hold the raw dispatch pc, while `CffInstructionState.pcToken`
  records the canonical block key pc token used to encrypt the string payload.
  String decode must derive the canonical pc token from live method key,
  guard, path, and block state before entering the shared tail.
- Required evidence before editing: fresh `TEST` full JVM run failed with
  `StringIndexOutOfBoundsException` in `String.<init>([B,I,I,Charset)` from
  `a.na.gg`; fresh `full-no-indy` failed with the same decode shape; fresh
  `stringObfuscation`-only failed with the same decode shape; fresh
  `constObfuscation`-only ran without this string-constructor crash. Further
  ablations proved the same crash remained with method-parameter obfuscation
  disabled, with validation-sink hardening disabled, and with the minimal
  `keyDispatch + controlFlowFlattening + stringObfuscation` transform set.
  Temporary tagged diagnostics on the minimal `TEST` repro showed the first
  failing string site had matching runtime and compile-time `methodKey`,
  `guard`, `path`, and `block`, but mismatched pc material:
  compile-time `pc=-1503747671`, runtime tail `pc=1724244705`. A key-byte
  ordering hypothesis was tested and rejected: changing transform-time key
  byte writes did not remove the crash, and the runtime selector branch only
  changes store order, not final byte layout.
- Validation command or runtime target: regenerate and run fresh `TEST`
  `stringObfuscation`-only, `full-no-indy`, and full JVM artifacts using
  repository-local build directories, then rerun
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest.fullJvmObfuscationPerformanceBaseline`.
- Completion criteria: fresh string-only and full JVM `TEST` artifacts no
  longer throw from the string constructor; the JCP-4 measurement harness can
  proceed to the requested no-quick full fixture coverage; selector diversity
  and dynamic per-site key material remain enabled; no fallback, skipped
  string transform, original literal path, or reduced CFF coverage is
  introduced.

### [-] JCP-4B: Keep Generated Reflection Filters Out Of Application Constant Rewrites

- Scope: repair the generic constant-obfuscation interaction with generated
  reflection member filters. CFF inserts generated filters after
  `Class.getFields`, `getDeclaredFields`, `getMethods`, and
  `getDeclaredMethods` so synthetic/static generated members do not leak into
  application reflection counts. Constant obfuscation must not treat those
  generated bookkeeping instructions as original application numeric sites when
  it later scans a normal application method, while still allowing the explicit
  generated-helper hardening pass to protect real generated helper methods.
- Required evidence before editing: a fresh regenerated `TEST`
  const-enabled artifact runs without the earlier string-constructor crash but
  prints `Counter FAIL`, `Field ERROR`, and `Annotation ERROR`; a fresh
  `full-no-const-string` artifact keeps `Counter PASS`, `Field PASS`, and
  `Annotation PASS`; bytecode inspection of obfuscated `Count.run` shows CFF
  reflection filters around `getFields` / `getDeclaredFields` /
  `getMethods` / `getDeclaredMethods`, but those filter bodies now contain
  constant-obfuscation decode calls such as `a/a.j(...)` and `a/a.s(...)`.
  Source inspection shows `JvmConstantObfuscationPass.transformMethod` skips an
  entire generated helper method unless hardening is enabled, but its
  per-instruction scan only checks `metadata.applicationInstructions()` and
  does not reject `JvmKeyDispatchPass.isGeneratedNode(...)` inside ordinary
  application methods.
- Validation command or runtime target: regenerate and run fresh `TEST`
  const-enabled and full JVM artifacts, then rerun the focused constant/string
  regressions and the no-quick JVM full-obfuscation measurement harness.
- Completion criteria: generated reflection member filters in application
  methods are no longer rewritten as application numeric constants; explicit
  generated-helper hardening still runs for generated helper methods; fresh
  const-enabled `TEST` no longer introduces `Counter`, `Field`, or
  `Annotation` regressions beyond the remaining independently tracked
  reflective construction/stack-trace blockers; no application constants,
  strings, or full-obfuscation surfaces are skipped.

### [-] JCP-4C: Repair Cross-Class Carrier Index Table Binding

- Scope: repair the generic packed-carrier logical-slot invariant before the
  `full.jar` smoke fixture can be accepted. A carrier producer and consumer
  must decode the same logical carrier slot to the same physical `Object[]`
  index even when the call crosses class boundaries.
- Required evidence before editing: fresh no-quick full JVM obfuscation of
  `test-jars/full.jar` succeeded, but the resulting
  `build/test-jvm-full-obf-perf/full-current-full.jar` failed immediately with
  `NullPointerException: Cannot throw exception because "null" is null` in
  `a.o.<init>`. A fresh no-indy full JVM obfuscation failed the same way,
  proving the failure is not invokedynamic-specific. Bytecode inspection of
  `a.o.<init>` shows the carrier length check succeeds and the failure is in
  the pre-super carrier token whitelist path. The direct caller `a.mh.kj`
  stores the three constructor carrier logical slots through `a.mh.a`, while
  the constructor loads those same logical slots through `a.o.a`; these are
  different CFF class-key tables, so logical-to-physical carrier slot decoding
  is not stable across the producer/consumer boundary.
- Validation command or runtime target: regenerate `test-jars/full.jar` with
  `test-jars/full-jvm-obf.yml`, run the fresh artifact without `quick` /
  `--quick`, and rerun the focused method-parameter integration test.
- Completion criteria: fresh full and no-indy `full.jar` artifacts reach the
  Java 21 feature runner instead of failing in constructor carrier attestation;
  carrier slot decoding remains bound to live carrier/key material and an
  existing CFF class-key table; no carrier fallback, original descriptor path,
  reduced CFF coverage, or unprotected constructor path is introduced.
- Dependency note: the cross-class carrier table repair changed the fresh
  `full.jar` failure mode from constructor carrier attestation to Java 21 runner
  execution and exposed the separate interface-default packed-entry CFF key
  blocker recorded in JCP-4D. JCP-4C remains open until the same fresh full
  `full.jar` acceptance run completes after JCP-4D; proceeding to JCP-4D is the
  next prerequisite for closing JCP-4C, not a workaround around it.

### [x] JCP-4D: Isolate Runtime String Tail Helpers By Owner Class

- Scope: repair the generic interaction between runtime string decode helpers,
  CFF state, and immediately following packed virtual/interface entry calls.
  Runtime string decode helpers must remain bound to the caller owner's live
  class table/CFF/key material; package-shared string tails allow a caller to
  invoke a helper hosted in another transformed application class, then
  continue into a packed virtual/interface call with perturbed state.
- Required evidence before editing: a fresh string+CFF+method-parameter
  obfuscation of `test-jars/full.jar` succeeded, but
  `java -jar build/test-jvm-full-obf-perf/full-string-current-validation.jar --list`
  failed with `NullPointerException: Cannot invoke
  "String.toLowerCase(java.util.Locale)" because "<local1>" is null` in
  obfuscated `RunnerOptions.accepts`. Mapping and bytecode inspection showed
  `ClassLiteralArrayFeatureTest.id()` mapped to `a/o.jf([Object])String`,
  where carrier length, hidden key, carrier-index key, token, and tag checks
  pass before CFF reaches an `aconst_null; areturn` poison path. Caller
  bytecode in `Runner.printList` showed a runtime string helper call
  `invokestatic a/o.c([Object;JIIIIII)String` immediately before constructing
  the next packed interface carrier. A static-string probe replacing only the
  runtime decode helper call with static string emission made the same fresh
  artifact's `--list` path pass, proving the failure came from the runtime
  string helper call. A per-class tail probe changing
  `ensureStringDecodeTail` from `packageName(clazz.name())` to `clazz.name()`
  made the same string+CFF artifact's `--list` path pass, proving the
  package-shared helper ownership was the failing invariant. A focused
  synthetic regression for cross-class string-tail decode passed under the old
  strategy, so the actual `full.jar` bytecode path remains the runtime target
  for this subtask.
- Validation command or runtime target: rerun
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`,
  regenerate `test-jars/full.jar` with the string+CFF profile and
  `test-jars/full-jvm-obf.yml`, run both fresh artifacts with `--list`, then
  run the fresh full-profile artifact without `quick` / `--quick` to expose
  any remaining independent Java 21 ABI failures.
- Completion criteria: the focused method-parameter integration suite passes;
  fresh string+CFF and full-profile `full.jar --list` no longer return null
  from the string decode / packed interface path; runtime string encryption,
  per-site seeds, CFF live key binding, and packed-carrier hardening stay
  enabled; no static string fallback, original-bytecode fallback, reduced CFF
  coverage, or skipped packed virtual/interface path is introduced. Full
  no-quick `full.jar` acceptance is delegated to JCP-4E subtasks for the
  independent JVM ABI/metadata failures exposed after this fix.
- Completion evidence: implementation review passed for the owner-isolated
  string tail cache key. Fresh
  `JvmMethodParameterObfuscationIntegrationTest` passed; fresh string+CFF
  `full.jar --list` passed; fresh full-profile `full.jar --list` passed; the
  no-quick full-profile run advanced to independent Java 21 ABI/metadata
  failures recorded under JCP-4E.

### [ ] JCP-4E: Honor JVM Metadata ABI Surfaces Under Full Profile

- Scope: parent task for generic JVM ABI and metadata surfaces that must keep
  VM-defined shape while the rest of the class remains fully obfuscated. This
  parent task records the observed fresh Java 21 failure surface; each ABI
  family below must be repaired, reviewed, validated, and committed as its own
  bounded high-risk subtask.
- Required evidence before editing any child subtask: after JCP-4D's per-owner
  string tail repair, fresh `test-jars/full.jar` obfuscation with
  `test-jars/full-jvm-obf.yml` succeeds and
  `java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --list`
  succeeds without `quick` / `--quick`. The same fresh artifact run with no
  application arguments reaches the Java 21 runner but emits independent
  failures in record metadata, annotation defaults, external JDK reflective
  lookups, service construction, serialization, enum switch/name behavior,
  bridge methods, classfile metadata, and name-sensitive reflection.
- Validation command or runtime target: after each child subtask, rerun its
  focused regression, regenerate the fresh full-profile `full.jar`, and run
  the relevant focused `--only features --include ...` target. After all child
  subtasks pass, run the full artifact without `quick` / `--quick`.
- Completion criteria: all JCP-4E child subtasks are complete; fresh full JVM
  `full.jar` no-quick run no longer fails on JVM ABI/metadata surfaces;
  eligible non-ABI application methods, constants, strings, CFF blocks, and
  callsites remain fully transformed without fallback, skip-on-error, or
  original bytecode rescue.

### [x] JCP-4E1: Preserve Record Metadata And Canonical Constructor ABI

- Scope: first bounded JCP-4E repair slice. Preserve JVM record component
  metadata, accessor naming, and canonical constructor ABI for record classes
  as true JVM metadata/serialization surfaces, while preserving full
  obfuscation for non-record-ABI methods and callsites in the same classes.
- Required evidence before editing: a focused no-quick run
  `java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.records.reflection`
  exits 1 with `record component name expected <name> but got <b>`.
  The original record class
  `com/java21test/features/RecordsFeatureTest$Person` has `Record:` entries
  `java.lang.String name` and `int age`, accessor methods `name()` and
  `age()`, and public canonical constructor `(Ljava/lang/String;I)V` with
  `MethodParameters` `name` and `age`. In the fresh full-obfuscated artifact,
  the corresponding record class has `Record:` entries `java.lang.String a`
  and `int b`, accessor methods renamed to packed `Object[]` entry methods,
  and the public constructor shape changed to `([Ljava/lang/Object;J)V`.
  Source inspection shows `JvmRenamerPass.Renamer.mapRecordComponentName`
  explicitly maps record component names through the field member map, and
  `JvmMethodParameterObfuscationPass.isEligible(...)` currently includes
  original application record constructors in packed-parameter rewriting.
- Validation command or runtime target: add a focused integration regression
  for a record with reflected component names/accessors, canonical constructor
  lookup/invocation, and serialization round trip under the full JVM transform
  set; rerun
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest`;
  regenerate `test-jars/full.jar` with `test-jars/full-jvm-obf.yml`; run
  `java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.records.reflection`
  and the record-canonical-constructor focused serialization regression
  without `quick` / `--quick`. The broad
  `java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.serialization.roundtrip`
  target is non-blocking for JCP-4E1 unless bytecode evidence proves the
  remaining failure is caused by record canonical constructor or record
  metadata rather than the separate serialization-magic ABI surface assigned to
  JCP-4E8.
- Completion criteria: record component names/accessors and canonical
  constructors reflect the JVM record ABI and the focused record/serialization
  targets pass; non-record-ABI methods in record classes remain eligible for
  CFF, string/constant/indy hardening, method-key propagation, and packed
  carrier rewriting; no general constructor fallback or original-bytecode path
  is introduced.
- Implementation/evidence:
  - Added generic `JvmRecordAbi` detection for record classes, component
    fields/accessors, canonical constructors, and true record ABI methods.
    Renamer exemptions are limited to record component fields/accessors and
    record component metadata names; non-ABI record methods still rename and
    transform.
  - `JvmMethodParameterObfuscationPass` excludes only record ABI methods and
    canonical constructors from packed descriptor/key injection. Reflective
    `Method` provenance is strict: merged producers are accepted only when all
    producers resolve to non-empty candidate plans. Static review found no
    remaining `sourced == null continue` or `stored == null continue` partial
    proof path in the affected helper parsers.
  - `JvmKeyDispatchPass` excludes only record component accessors from keyed
    descriptor injection and uses `SourceInterpreter` provenance for
    `Class.getMethod`/`getDeclaredMethod` and `Method.invoke` key-transfer
    decisions. Unknown provenance remains conservative; exact plain/unkeyed
    record accessor lookups are not rewritten.
  - Current harness exposed no callable subagent-dispatch tool for the required
    implementation re-review, so the nearest equivalent was a file/line static
    implementation review against the current working tree. Review result:
    PASS for JCP-4E1; no record ABI or strict-source findings remained. Earlier
    subagent FAIL items for direct reflective lookup, prior-LDC key dispatch,
    and partial `SourceValue` proof were rechecked against the current diff.
  - Fresh focused validation passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile`.
  - Fresh regression set passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest`.
  - Fresh no-quick full-profile obfuscation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 316 classes and 9 resources.
  - Fresh full.jar record target passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.records.reflection`
    with `PASS features.records.reflection 8.752 ms`.
  - Static bytecode check: `RecordsFeatureTest$Person -> a/wc`; `javap -p -s`
    shows `a.wc extends java.lang.Record`, component fields `name`/`age`,
    canonical constructor `(Ljava/lang/String;I)V`, and accessors
    `name()Ljava/lang/String;` / `age()I`. Non-ABI `label()` remains obfuscated
    as `pd([Ljava/lang/Object;)Ljava/lang/String;`.
  - Broad `features.serialization.roundtrip` still fails with
    `InvalidObjectException: enum constant FAST does not exist in class a.pd`.
    Static map shows `SerializationRoundTripFeatureTest$Mode.FAST -> b` and
    `SAFE -> c`; this is the separate enum/serialization ABI surface assigned
    to JCP-4E8, not record metadata/canonical constructor ABI.

### [x] JCP-4E2: Preserve Annotation Default And Annotation Element ABI

- Scope: repair annotation metadata surfaces separately from record metadata.
  Annotation element methods and default values must remain valid JVM
  annotation metadata while other eligible application code remains fully
  transformed.
- Required evidence before editing: collect source and bytecode proof for the
  fresh focused failure
  `features.jvm.annotation-defaults -> AnnotationFormatError: Invalid default`,
  identifying the exact transform path that rewrites the annotation enum/type
  default into invalid metadata.
- Validation command or runtime target: add focused annotation-default
  regression under the full JVM transform set, rerun the relevant integration
  tests, regenerate full `full.jar`, and run the focused annotation target
  without `quick` / `--quick`.
- Completion criteria: annotation defaults and element lookups remain valid
  JVM metadata; no broad annotation-class skip beyond true annotation ABI
  surfaces is introduced.
- Dependency/evidence update:
  - Fresh focused `full.jar` run without `--quick` fails at
    `features.jvm.annotation-defaults` with
    `AnnotationFormatError: Invalid default: public abstract a.e a.d.mode()`.
    Original bytecode shows `Complex.mode()` has `AnnotationDefault`
    `Lcom/java21test/features/AnnotationDefaultsFeatureTest$Mode;.SECOND`.
  - The first generic annotation metadata repair now rewrites enum annotation
    values and `MethodNode.annotationDefault` enum constant names through the
    renamer field map before `ClassRemapper`, including nested annotations and
    annotation arrays. The focused regression fixture shows the metadata side
    is rewritten to final enum field names such as `La/d;.c`.
  - Fresh regression still fails because the enum class itself no longer
    exposes the JVM-required enum ABI: `javap -p` on the generated fixture
    shows no `values()` or `valueOf(String)` method and the enum constructor is
    packed as `([Ljava/lang/Object;J)V`. `AnnotationParser` therefore cannot
    resolve the rewritten enum default even after the annotation metadata name
    is correct.
  - Plan order is adjusted: JCP-4E4 enum ABI is now a prerequisite for closing
    JCP-4E2's enum-default validation. JCP-4E2 remains in progress until the
    annotation-focused target passes after JCP-4E4.
- Implementation/evidence:
  - `JvmRenamerPass` now recursively rewrites enum names stored in annotation
    metadata before `ClassRemapper` runs. The rewrite covers class, field,
    method, parameter, local-variable, type-use, record-component annotations,
    nested annotations, annotation arrays, and `MethodNode.annotationDefault`.
    Only enum annotation values with a final field mapping are changed; class
    descriptors remain handled by the existing descriptor remapper.
  - Added a full-profile regression fixture with explicit enum annotation
    values, enum array defaults, nested annotation defaults, and reflective
    `Method.getDefaultValue()` checks.
  - Implementation review note: multi-agent spawning remains unavailable under
    the active tool contract without an explicit user request for sub-agents.
    The nearest equivalent review was a scoped static diff audit plus the
    fresh full-profile regression/runtime evidence listed here. Review result:
    PASS for JCP-4E2 metadata scope.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming`.
  - Fresh full-profile `full.jar` obfuscation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 319 classes and 9 resources.
  - Fresh no-quick focused `full.jar` validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.annotation-defaults --verbose`,
    with `PASS features.jvm.annotation-defaults 10.502 ms`.

### [x] JCP-4E3: Preserve External Reflection And MethodHandle ABI Boundaries

- Scope: repair generic detection of external JDK/library members so
  reflection and MethodHandle lookup descriptors for non-application targets
  are not rewritten as packed application ABIs.
- Required evidence before editing: fresh full-profile `test-jars/full.jar`
  obfuscation succeeded with
  `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`.
  The fresh no-quick focused feature run
  `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only feature --verbose`
  failed with exact external lookup corruption:
  `features.jvm.jpms-module-layer -> NoSuchMethodException:
  sample.alpha.api.PublicApi.d(long)` and
  `features.jvm.tooling-jmx-jfr -> NoSuchMethodException:
  jdk.jfr.Recording.start(long)`. Source inspection identifies
  `JvmKeyDispatchPass.methodLookupNeedsKey(...)` as the rewrite gate:
  `sourceReflectiveLookupTarget(...)` can prove an exact owner/name/parameter
  array, but `methodLookupKeyState(...)` returns `UNKNOWN` when the owner is
  absent from `pctx.classMap()`, and the gate currently rewrites every state
  except `PLAIN`. That makes precise JDK/library/module reflection lookups
  receive a generated trailing hidden `long` even though no transformed
  application method owns that ABI. The original bytecode for the two focused
  failures shows the exact owner provenance shape that the gate failed to
  parse: `ModuleLayerAccessFeatureTest.run(...)` loads
  `sample.alpha.api.PublicApi` through
  `Class.forName(String, boolean, ClassLoader)` before `Class.getMethod("call",
  new Class[0])`, and `JvmToolingFeatureTest.jfrIfAvailable(...)` loads
  `jdk.jfr.Recording` through `Class.forName(String)` before
  `Class.getMethod("start", new Class[0])`. Both owners are precise
  non-application classes and therefore must not receive an application hidden
  key parameter.
- Validation command or runtime target: add focused external-reflection and
  external-MethodHandle regression, rerun relevant integration tests,
  regenerate full `full.jar`, and run the tooling/JFR focused target without
  `quick` / `--quick`.
- Completion criteria: external JDK/library reflection and MethodHandle
  descriptors preserve their true ABI; original application reflection paths
  remain rewritten to the final obfuscated ABI.
- Implementation/evidence:
  - `JvmKeyDispatchPass.methodLookupNeedsKey(...)` now first proves the exact
    reflective lookup owner/name/parameter source. If the owner is known and
    absent from the application class map, the lookup is left at the external
    ABI and no generated hidden `long` is appended. Exact application owners
    still rewrite only when the resolved method state proves a keyed
    application ABI; unknown provenance remains conservative.
  - The reflective lookup source analysis now understands literal
    `Class.forName(String)` and `Class.forName(String, boolean, ClassLoader)`
    owners, so dynamically loaded external classes such as `jdk.jfr.Recording`
    and module-layer API classes are classified by their actual owner instead
    of by descriptor-only fallback.
  - `JvmRenamerPass` now applies the same owner-boundary rule to
    `Class.getMethod`/`getDeclaredMethod`, field reflection, and
    `MethodHandles.Lookup.findStatic`/`findVirtual`/`findSpecial` plus
    MethodHandle field lookups. A known external owner suppresses the old
    global unique-name fallback; a known application owner uses the
    owner-specific final name map. This prevents an application method named
    `call` from rewriting `java.util.concurrent.Callable.call`.
  - Added a full-profile regression fixture that reflectively and via
    MethodHandle calls external `Integer.parseInt`, `Thread.getName`,
    `String.charAt`, and `Callable.call` while also declaring a local
    application `call()` method to force a real rename-collision check.
  - Implementation review note: this harness exposed the multi-agent tooling
    only after the implementation was complete, and that tool's active
    contract allows spawning only when the user explicitly requests
    sub-agents. To avoid violating the higher-priority tool contract, the
    nearest equivalent review was a scoped static diff audit plus the fresh
    runtime/JUnit evidence listed below. Review result: PASS for JCP-4E3 scope;
    the remaining full-suite failures are separate recorded JCP/performance
    surfaces.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile`.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.
  - Fresh no-quick full-profile obfuscation passed after the MethodHandle
    boundary fix:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 317 classes and 9 resources.
  - Fresh no-quick focused `full.jar` validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.jpms-module-layer --include features.jvm.tooling-jmx-jfr --verbose`,
    with `PASS features.jvm.jpms-module-layer 126.707 ms` and
    `PASS features.jvm.tooling-jmx-jfr 206.743 ms`.
  - Fresh complete no-quick `full.jar` run was also attempted with no
    `--quick` parameter:
    `timeout 600s env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --verbose`.
    It revalidated that `features.jvm.jpms-module-layer` and
    `features.jvm.tooling-jmx-jfr` pass in the full run, then exposed separate
    later failures in OOP inheritance/classloader isolation/concurrent
    utilities/bridge/annotation defaults/classfile attributes/varargs
    MethodHandle/GC subprocess/dynamic proxy/ServiceLoader/serialization/enum
    and name-sensitive surfaces, and finally timed out after 600 seconds after
    `perf.crypto.rsa-oaep` without producing `perf.crypto.xor`. The previous
    SIGQUIT evidence mapped that same long-running point to
    `CryptoXorPerfTest.xor([B[B[BI)V -> a/ef.af`, so full-suite completion is
    still blocked by later JCP/performance subtasks rather than by the external
    reflection/MethodHandle ABI boundary repaired here.

### [x] JCP-4E4: Preserve Enum Class, Constant, And Switch ABI

- Scope: repair enum-specific JVM ABI surfaces: enum class shape, constant
  names/ordinals, synthetic switch map expectations, and `Enum.valueOf` /
  `Class.isEnum` behavior.
- Required evidence before editing: collect source and bytecode proof for the
  fresh focused failures in enum switch/name behavior, including the exact
  transform path that changes enum shape or corrupts switch helper state.
- Validation command or runtime target: add focused enum regression under the
  full JVM transform set, rerun relevant integration tests, regenerate full
  `full.jar`, and run the enum focused targets without `quick` / `--quick`.
- Completion criteria: enum ABI behavior passes focused targets; non-enum
  application methods in enum-owning classes remain fully transformed where
  JVM ABI permits.
- Implementation/evidence:
  - Added `JvmEnumAbi` to identify true enum ABI surfaces: enum constant
    fields, `values()`, `valueOf(String)`, and constructors whose mandated
    leading parameters are `(String, int)`.
  - `JvmRenamerPass` now preserves enum constant field names and the
    `values`/`valueOf` method names. `JvmKeyDispatchPass` no longer injects
    hidden key parameters into `values`/`valueOf`. `JvmMethodParameterObfuscationPass`
    no longer packs enum ABI methods or enum constructors, and constructor
    suffix planning excludes enum constructors.
  - Implementation review note: the same active tool contract prevented
    spawning a sub-agent review without an explicit user request. The nearest
    equivalent review was a scoped static diff audit plus the fresh
    full-profile regression/runtime evidence listed here. Review result: PASS
    for JCP-4E4 enum ABI scope; remaining full-suite failures are separate
    recorded surfaces.
  - Fresh no-quick focused `full.jar` enum validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.enum-switch --include features.name-sensitive.enum-names --include features.serialization.roundtrip --include features.jvm.enum-constant-body --verbose`.
    `features.jvm.enum-constant-body`, `features.enum-switch`, and
    `features.name-sensitive.enum-names` passed. The included
    `features.serialization.roundtrip` advanced past the prior enum failure
    and now fails at the separate custom-serialization transient restoration
    assertion, which belongs to JCP-4E8 rather than enum shape/name ABI.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.
  - Fresh complete no-quick `full.jar` run was attempted again after the
    enum/annotation repair:
    `timeout 600s env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --verbose`.
    It confirmed `features.jvm.annotation-defaults`,
    `features.jvm.enum-constant-body`, `features.enum-switch`, and
    `features.name-sensitive.enum-names` pass in the full run. Later unrelated
    failures remain in OOP/classloader/concurrent/bridge/classfile-attribute/
    varargs-MethodHandle/GC/dynamic-proxy/ServiceLoader/serialization/
    repeatable-annotation/stackwalker/name-sensitive surfaces, and the run
    again timed out after 600 seconds after `perf.crypto.rsa-oaep` without a
    `perf.crypto.xor` result.

### [x] JCP-4E5: Preserve Synthetic Bridge Method ABI

- Scope: repair generic bridge method visibility and descriptor surfaces needed
  by JVM dispatch, generic signatures, and reflection.
- Required evidence before editing: collect source and bytecode proof for the
  fresh focused bridge failures, including whether renaming, descriptor
  packing, generated-member filtering, or signature rewriting hides the bridge.
- Validation command or runtime target: add focused bridge/generic-signature
  regression under the full JVM transform set, rerun relevant integration
  tests, regenerate full `full.jar`, and run the bridge focused target without
  `quick` / `--quick`.
- Completion criteria: bridge methods required by JVM and reflection remain
  visible with correct ABI descriptors; non-bridge application methods remain
  fully transformed.
- Implementation/evidence:
  - Fresh focused `full.jar` no-quick bridge run failed before this repair:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.covariant-bridge --include features.generics.bridge-signature --verbose`,
    with `FAIL features.jvm.covariant-bridge AssertionError: covariant bridge method missing`
    and `FAIL features.generics.bridge-signature AssertionError: bridge method missing`.
    Original bytecode contained `ACC_BRIDGE, ACC_SYNTHETIC` methods
    `Child.value()Ljava/lang/Number;` and `StringGetter.get()Ljava/lang/Object;`.
    The failing obfuscated bytecode still contained bridge bodies, but their
    JVM ABI names/descriptors had been rewritten to packed `Object[]` methods
    such as `r([Ljava/lang/Object;)Ljava/lang/Number;` and
    `va([Ljava/lang/Object;)Ljava/lang/Object;`, so reflection by the original
    bridge name could no longer observe the JVM bridge contract.
  - Added generic `JvmBridgeAbi` detection for `ACC_BRIDGE` methods and related
    bridge-family methods across the application inheritance/interface graph.
    `JvmRenamerPass`, `JvmKeyDispatchPass`, and
    `JvmMethodParameterObfuscationPass` now preserve only those true bridge ABI
    families from renaming, hidden-key descriptor injection, and packed
    `Object[]` carrier rewriting. Other unrelated application methods remain
    eligible for the full transform set.
  - Added a full-profile bridge regression fixture covering covariant return
    bridges, generic interface bridges, normal virtual/interface dispatch, and
    reflective `Method.isBridge()` discovery by original bridge name.
  - Implementation review note: the active multi-agent tool contract still
    prevents spawning a sub-agent review without an explicit user request. The
    nearest equivalent review was a scoped static diff audit plus the fresh
    JUnit/runtime evidence listed here. Review result: PASS for JCP-4E5 bridge
    ABI scope; remaining full-suite failures are separate recorded surfaces.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting`.
  - Fresh full-profile `full.jar` obfuscation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 319 classes and 9 resources.
  - Fresh no-quick focused `full.jar` bridge validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.covariant-bridge --include features.generics.bridge-signature --verbose`,
    with `PASS features.jvm.covariant-bridge 4.990 ms` and
    `PASS features.generics.bridge-signature 3.232 ms`.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.

### [x] JCP-4E6: Preserve Service Provider Construction ABI

- Scope: repair service-provider public no-arg construction and service
  resource compatibility while retaining class/resource renaming and protected
  method bodies where ABI permits.
- Required evidence before editing: collect source and bytecode proof for the
  fresh focused service-loader failure, including whether constructor packing,
  access changes, resource rewriting, or provider class renaming caused
  `ServiceLoader` to miss a public no-arg constructor.
- Evidence before editing: fresh no-quick focused run on the JCP-4E5
  full-profile artifact failed with
  `ServiceConfigurationError: a.ph: a.oh Unable to get public no-arg constructor`
  caused by `NoSuchMethodException: a.oh.<init>()`. Original bytecode for
  `com/java21test/services/DefaultGreetingService` has a public
  `DefaultGreetingService.<init>()V` and implements
  `GreetingService.greet(String)`. The obfuscated resource rewrite is correct:
  original `META-INF/services/com.java21test.services.GreetingService` contains
  `com.java21test.services.DefaultGreetingService`, while the obfuscated
  artifact contains `META-INF/services/a.ph` with provider `a.oh`. The failing
  obfuscated provider class `a.oh` is still public and implements `a.ph`, but
  its only constructor is `public a.oh([Ljava/lang/Object;J)V`; therefore the
  failing invariant is method-parameter constructor packing of a
  resource-declared ServiceLoader provider's public no-arg constructor.
- Validation command or runtime target: add focused service-loader regression
  under the full JVM transform set, rerun relevant integration tests,
  regenerate full `full.jar`, and run the service focused target without
  `quick` / `--quick`.
- Completion criteria: service providers load and instantiate through the JDK
  `ServiceLoader` ABI; provider implementation methods remain fully
  obfuscated where JVM/library ABI permits.
- Implementation/evidence:
  - `PipelineContext` now carries the original input resources so JVM
    transforms can prove external resource-declared ABI surfaces before output
    resource rewriting. `JvmServiceAbi` parses `META-INF/services/*` files and
    module `provides` entries, maps original provider names through
    `renamer.classMap` when renamer has already run, and identifies only
    resource/module-declared provider public `()V` constructors as ServiceLoader
    construction ABI.
  - `JvmMethodParameterObfuscationPass` now excludes only those provider
    public no-arg constructors from packed descriptor rewriting and constructor
    ABI suffix planning. Provider business methods and service interface
    methods remain eligible for packing/key/CFF/indy/string/constant
    hardening.
  - `ObfuscationPipeline` now rewrites `META-INF/services/*` contents with
    service-specific binary-name semantics. This fixes the generic default-
    package provider case where the previous generic text remapper could write
    an invalid internal name such as `a/c` into a ServiceLoader resource; normal
    resource path remapping still renames the service resource itself.
  - Added a full-profile regression fixture that loads a resource-declared
    provider through `ServiceLoader`, invokes the provider through the
    transformed service interface, and reflects `getConstructor()` on the
    loaded provider.
  - Implementation review note: the active multi-agent tool contract still
    prevents spawning a sub-agent review without an explicit user request. The
    nearest equivalent review was a scoped static diff audit plus the fresh
    JUnit/runtime evidence listed here. Review result: PASS for JCP-4E6 service
    ABI scope; remaining full-suite failures are separate recorded surfaces.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serviceProviderConstructorsSurviveFullProfileAbiRewriting`.
  - Fresh full-profile `full.jar` obfuscation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 319 classes and 9 resources.
  - Fresh no-quick focused `full.jar` service validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.service-loader --verbose`,
    with `PASS features.service-loader 3.253 ms`.
  - Static runtime-artifact check: `META-INF/services/a.ph` contains `a.oh`;
    `javap -p -s a.oh a.ph` shows provider constructor `public a.oh()V` while
    service method dispatch remains transformed as
    `a.ph.ih([Ljava/lang/Object;)Ljava/lang/String;` and
    `a.oh.ih([Ljava/lang/Object;)Ljava/lang/String;`.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serviceProviderConstructorsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.

### [x] JCP-4E7: Preserve Classfile MethodParameters Metadata ABI

- Scope: repair method-parameter metadata surfaces that code can observe via
  reflection, without exposing plaintext for non-ABI application lookup data.
- Required evidence before editing: collect source and bytecode proof for the
  fresh focused classfile metadata failure, identifying the exact pass that
  removes or corrupts required `MethodParameters` attributes.
- Evidence before editing: the fresh no-quick focused run on the current
  full-profile artifact failed with
  `FAIL features.jvm.classfile-attributes AssertionError: classfile attribute MethodParameters missing in a/re.class`
  and
  `FAIL features.name-sensitive.method-parameters AssertionError: method parameter names not present; compile with -parameters`.
  The fresh map shows
  `com/java21test/features/namesensitive/ParameterNameFixture -> a/re` and
  `ParameterNameFixture.combine(Ljava/lang/String;I)Ljava/lang/String; -> ue`.
  Original `javap -v` shows `ParameterNameFixture.combine(String,int)` has a
  `MethodParameters` attribute with `leftPart` and `rightCount`; the
  full-obfuscated bytecode changed the method to
  `public java.lang.String ue(java.lang.Object[])` and no
  `MethodParameters` attribute remains. Source inspection identifies the exact
  destructive path: `JvmKeyDispatchPass.prepareKeyedDescriptors(...)` can first
  add a hidden `long` ABI to eligible methods, then
  `JvmMethodParameterObfuscationPass.prepare(...)` packs the descriptor and
  calls `cleanupParameterMetadata(mn)`, which sets `mn.parameters = null`.
  Therefore any method whose parameter metadata is observed through an exact
  reflective `Executable.getParameters()` path must be treated as a JVM
  metadata ABI surface before both key-dispatch descriptor injection and
  packed-carrier rewriting.
- Validation command or runtime target: add focused `MethodParameters`
  reflection regression under the full JVM transform set, rerun relevant
  integration tests, regenerate full `full.jar`, and run the classfile metadata
  focused target without `quick` / `--quick`.
- Completion criteria: required `MethodParameters` metadata remains valid for
  ABI surfaces; non-ABI reflective lookup data remains protected or rewritten
  to the final obfuscated ABI.
  - Implemented generic `JvmMethodParametersAbi` source-proven observer
    analysis. It records only methods or constructors whose
    `MethodParameters` metadata is observed through a resolved
    `Executable.getParameters()` path, including exact
    `getMethod`/`getDeclaredMethod` and constructor lookups with class-array
    provenance.
  - `JvmKeyDispatchPass` now preserves the original descriptor for those
    observed metadata ABI surfaces before hidden `long` key injection, and it
    avoids appending hidden key parameters to same-observer reflective lookups
    whose target metadata surface is proven even when the pre-existing
    parameter-array analyser cannot resolve every non-empty class array.
  - `JvmMethodParameterObfuscationPass` now keeps those observed metadata ABI
    surfaces out of packed-carrier descriptor rewriting and constructor suffix
    injection. Ordinary methods without proven `getParameters()` observation
    remain eligible for parameter packing and hidden-key propagation.
  - Added a full-profile regression fixture compiled with `javac -parameters`.
    It verifies reflective method and constructor parameter names with
    `getParameters()`, then invokes the same members after obfuscation.
  - Implementation review note: the active multi-agent tool contract still
    prevents spawning a sub-agent review without an explicit user request. The
    nearest equivalent review was a scoped static diff audit plus the fresh
    JUnit/runtime evidence listed here. Review result: PASS for JCP-4E7
    metadata ABI scope; remaining full-suite failures are separate recorded
    surfaces.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParametersMetadataSurvivesFullProfileAbiRewriting`.
  - Fresh full-profile `full.jar` obfuscation passed without quick mode:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 314 classes and 9 resources in 27305 ms.
  - Fresh no-quick focused `full.jar` metadata validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.classfile-attributes --include features.name-sensitive.method-parameters --verbose`,
    with `PASS features.jvm.classfile-attributes 9.569 ms`,
    `PASS features.name-sensitive.method-parameters 4.720 ms`, and
    `Feature summary: passed=2 failed=0 skipped=59 nameSensitiveFailed=0`.
  - Static runtime-artifact check: the fresh map shows
    `ParameterNameFixture -> a/re` and `combine(Ljava/lang/String;I)Ljava/lang/String; -> ue`;
    `javap -p -v a/re` shows
    `public java.lang.String ue(java.lang.String, int)`,
    descriptor `(Ljava/lang/String;I)Ljava/lang/String;`, and
    `MethodParameters` names `leftPart` and `rightCount`.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParametersMetadataSurvivesFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serviceProviderConstructorsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.

### [x] JCP-4E8: Preserve Serialization Magic Member ABI

- Scope: repair Java serialization magic surfaces such as `serialVersionUID`,
  `serialPersistentFields`, `readResolve`, `writeReplace`, `readObject`,
  `writeObject`, and record serialization constructor expectations.
- Required evidence before editing: collect source and bytecode proof for the
  fresh focused serialization failures, separating record canonical constructor
  fallout already covered by JCP-4E1 from serialization-specific magic member
  lookup or descriptor failures.
- Evidence before editing: the fresh focused no-quick run on the current
  full-profile artifact passed `features.jvm.serialization-proxy` but failed
  `features.serialization.roundtrip` with
  `custom serialization transient restoration expected <tail> but got <null>`
  and failed `features.name-sensitive.serialization-fields` with
  `serialVersionUID expected <2101> but got <7359731577184801303>`.
  The fresh map shows generic serialization ABI members were renamed:
  `SerializationRoundTripFeatureTest$CustomData.writeObject(Ljava/io/ObjectOutputStream;)V -> zd`,
  `CustomData.readObject(Ljava/io/ObjectInputStream;)V -> xd`,
  `CanonicalSingleton.readResolve()Ljava/lang/Object; -> wd`, and multiple
  `serialVersionUIDJ` fields were renamed. The same map shows
  `StableNamedFixture.publicValue`, `privateNumber`, and `serialVersionUID`
  were renamed even though `ObjectStreamClass.getFields()` and
  `getSerialVersionUID()` observe those names as the serialized-form ABI.
  Original `javap -p -s` shows `CustomData` declares exact private
  `writeObject(ObjectOutputStream)` and `readObject(ObjectInputStream)`,
  `CanonicalSingleton` declares exact private `readResolve()Object`, and
  `StableNamedFixture` declares `serialVersionUID`, `publicValue`, and
  `privateNumber`. Fresh obfuscated `javap -p -s` shows the same members as
  renamed and descriptor-mutated, for example
  `a.od.zd([Ljava/lang/Object;J)V`, `a.od.xd([Ljava/lang/Object;J)V`, and
  `a.nd.wd([Ljava/lang/Object;J)Ljava/lang/Object;`. Therefore serialization
  ABI surfaces must be identified before renaming, hidden-key injection,
  packed-carrier rewriting, and static numeric constant movement.
- Validation command or runtime target: add focused serialization regression
  under the full JVM transform set, rerun relevant integration tests,
  regenerate full `full.jar`, and run serialization focused targets without
  `quick` / `--quick`.
- Completion criteria: Java serialization magic members and descriptors remain
  discoverable by the JDK serialization runtime; ordinary application methods
  remain fully transformed.
  - Implemented generic `JvmSerializationAbi` classification for classes that
    implement `Serializable` or `Externalizable` directly or through
    transformed application supertypes. It identifies serialization magic
    methods, `serialVersionUID`, `serialPersistentFields`, and non-static /
    non-transient default serialized fields whose names are observed by
    `ObjectStreamClass`.
  - `JvmRenamerPass` now preserves serialization magic method names and
    serialized-form field names while continuing to rename ordinary methods,
    transient fields, static non-magic fields, and helper members.
  - `JvmKeyDispatchPass` and `JvmMethodParameterObfuscationPass` now keep
    serialization magic method descriptors exact, so JDK serialization can
    invoke `readObject`, `writeObject`, `readResolve`, and `writeReplace`
    without hidden-key or packed-carrier ABI drift.
  - `JvmConstantObfuscationPass` now preserves `serialVersionUID` as a real
    static field value instead of moving it behind generated `<clinit>`
    material.
  - Added a full-profile regression fixture covering `serialVersionUID`,
    default serialized field names, `readObject` / `writeObject`,
    `readResolve`, and `writeReplace`.
  - Implementation review note: the active multi-agent tool contract still
    prevents spawning a sub-agent review without an explicit user request. The
    nearest equivalent review was a scoped static diff audit plus the fresh
    JUnit/runtime evidence listed here. Review result: PASS for JCP-4E8
    serialization ABI scope; remaining full-suite failures are separate
    recorded surfaces.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serializationMagicMembersSurviveFullProfileAbiRewriting`.
  - Fresh full-profile `full.jar` obfuscation passed without quick mode:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 317 classes and 9 resources in 25837 ms.
  - Fresh no-quick focused `full.jar` serialization validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.serialization-proxy --include features.serialization.roundtrip --include features.name-sensitive.serialization-fields --verbose`,
    with `PASS features.jvm.serialization-proxy 9.501 ms`,
    `PASS features.serialization.roundtrip 12.316 ms`, and
    `PASS features.name-sensitive.serialization-fields 3.633 ms`.
  - Static runtime-artifact check: `javap -p -s a/od a/nd a/te` shows
    `serialVersionUID`, `writeObject(ObjectOutputStream)`,
    `readObject(ObjectInputStream)`, `readResolve()Object`,
    `publicValue`, and `privateNumber` preserved, while the transient
    `CustomData` field and non-magic static `STATIC_TOKEN` remain renamed.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serializationMagicMembersSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParametersMetadataSurvivesFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serviceProviderConstructorsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.

### [x] JCP-4E9A: Normalize Varargs Metadata After Descriptor Packing

- Scope: repair transformed `ACC_VARARGS` methods whose final JVM descriptor no
  longer ends in an array after hidden-key or packed-carrier rewriting. This is
  a metadata consistency fix for transformed methods; it must not preserve the
  original descriptor, remove hidden keys, remove packed carriers, add adapters,
  or special-case any fixture.
- Required evidence before editing: collect source and bytecode proof that the
  current failure is stale `ACC_VARARGS` metadata on a descriptor whose final
  parameter is no longer an array, and prove the failure happens through a JDK
  MethodHandle lookup path rather than through an application-specific branch.
- Evidence before editing: fresh no-quick `full.jar` run failed
  `features.jvm.unsafe-varhandle-deep` with
  `IllegalAccessException: cannot make variable arity: a.ie.le(Object[],long)Object/invokeStatic does not have a trailing array parameter`
  and `perf.unsafe-varhandle.deep` with the same failure on `a.rg.le`.
  Static `javap -p -v` on the fresh full-obfuscated artifact shows both
  methods as `static java.lang.Object le(java.lang.Object..., long)` with
  descriptor `([Ljava/lang/Object;J)Ljava/lang/Object;` and flags
  `ACC_STATIC, ACC_VARARGS`. Original bytecode for
  `UnsafeVarHandleDeepFeatureTest$UnsafeAccess.invoke` has descriptor
  `(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;`
  and flags `ACC_STATIC, ACC_VARARGS`; after packing, the final descriptor
  parameter is the hidden `long`, not an array. Therefore the failing invariant
  is stale varargs metadata after generic descriptor rewriting.
- Validation command or runtime target: add a focused full-profile regression
  for a varargs method reached through `MethodHandles.Lookup.findStatic`, rerun
  the relevant MethodHandle/parameter regression group, regenerate `full.jar`
  without quick mode, and run `features.jvm.unsafe-varhandle-deep` plus
  `perf.unsafe-varhandle.deep` without `quick` / `--quick`.
- Completion criteria: descriptor-packed methods no longer expose invalid
  `ACC_VARARGS`; MethodHandle lookup of transformed varargs targets succeeds;
  hidden-key and packed-carrier rewriting remain active for those methods.
- Implementation/evidence:
  - Added `JvmVarargsMetadata.normalizeAfterDescriptorRewrite(...)` and call it
    immediately after hidden-key descriptor injection and packed-carrier
    descriptor rewriting. The helper clears `ACC_VARARGS` only when the final
    descriptor no longer ends in an array; it does not preserve original
    descriptors, remove hidden keys, remove packed carriers, or add adapters.
  - Added a full-profile regression whose transformed varargs target is found
    through `MethodHandles.Lookup.findStatic` and through reflection, and whose
    output jar is statically audited for invalid `ACC_VARARGS` descriptors.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.varargsMetadataIsConsistentAfterFullProfilePacking`.
  - Fresh no-quick full-profile obfuscation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-current-seed-invariant.jar`,
    writing 313 classes and 9 resources.
  - Static runtime-artifact check: `javap -classpath build/test-jvm-full-obf-perf/full-current-seed-invariant.jar -p a.ie a.rg`
    shows `le(java.lang.Object[], long)` for both previously failing methods;
    neither method is rendered as `Object...`, proving stale `ACC_VARARGS` is
    absent on the final packed/keyed descriptor.

### [x] JCP-4E9B: Bound Runtime Reflection Hidden-Key Injection To Proven Application Targets

- Scope: repair runtime `Class.getMethod` / `getDeclaredMethod` callsites whose
  lookup owner, name, or parameter array cannot be statically proven. These
  sites must not receive a static hidden-key parameter-array rewrite unless the
  transform can prove the lookup resolves to a keyed application ABI. This must
  not preserve exact application reflection lookups, remove hidden keys from
  proven application methods, add original-bytecode fallback, or special-case a
  fixture.
- Required evidence before editing: after the JCP-4E9A varargs metadata repair,
  fresh no-quick `full.jar` validation advanced past the prior
  `IllegalAccessException: cannot make variable arity` failure and exposed
  `NoSuchMethodException: sun.misc.Unsafe.objectFieldOffset(java.lang.reflect.Field,long)`
  in both `features.jvm.unsafe-varhandle-deep` and
  `perf.unsafe-varhandle.deep`. Original bytecode for
  `UnsafeVarHandleDeepFeatureTest$UnsafeAccess.invoke` shows a generic runtime
  helper shape: `target.getClass().getMethod(name, parameterTypes)` followed by
  `Method.invoke(target, args)`, where the owner, name, and parameter array are
  method inputs rather than exact application constants. Current
  `JvmKeyDispatchPass.methodLookupNeedsKey(...)` returns true when
  `sourceReflectiveLookupTarget(...)` cannot prove the lookup, so it appends
  `Long.TYPE` to an unproven runtime parameter array even though
  `reflectiveInvokeTargetSeed(...)` cannot transfer a live application key for
  that same unproven `Method.invoke` path. The failing external JDK lookup
  proves the hidden-key parameter rewrite crossed the JVM/application ABI
  boundary without a proven application target.
- Validation command or runtime target: add a focused full-profile regression
  for an external runtime reflection helper whose owner/name/parameter array are
  passed through method inputs, rerun the external-reflection and varargs
  regression group, regenerate `full.jar` without quick mode, and run
  `features.jvm.unsafe-varhandle-deep` plus `perf.unsafe-varhandle.deep`
  without `quick` / `--quick`.
- Completion criteria: unproven runtime reflection helpers preserve external
  JDK/library lookup descriptors; exact proven application reflection lookups
  continue to receive the final obfuscated keyed ABI and live key transfer.
- Implementation/evidence:
  - `JvmKeyDispatchPass.methodLookupNeedsKey(...)` now returns false when
    `sourceReflectiveLookupTarget(...)` cannot prove an owner/name/parameter
    tuple. Proven application owners still flow through `methodLookupKeyState`
    and receive hidden-key parameter-array rewriting only when the resolved
    application ABI is keyed. Proven external owners still preserve their true
    ABI.
  - Extended the external-reflection full-profile regression with a runtime
    helper that receives `Object target`, method name, and parameter array as
    method inputs, then calls `target.getClass().getMethod(...)`; this covers
    the same unproven runtime reflection shape as the full.jar Unsafe helper
    without special-casing `Unsafe`.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.varargsMetadataIsConsistentAfterFullProfilePacking`.
  - Fresh no-quick full-profile `full.jar` validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.jvm.unsafe-varhandle-deep --verbose`,
    with `PASS features.jvm.unsafe-varhandle-deep 26.719 ms`.
  - Fresh no-quick perf target passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' timeout 120s java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only perf --include perf.unsafe-varhandle.deep --verbose`,
    with `PERF perf.unsafe-varhandle.deep measure=4,240.002 ms`.
  - Fresh combined regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.varargsMetadataIsConsistentAfterFullProfilePacking --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serializationMagicMembersSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParametersMetadataSurvivesFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serviceProviderConstructorsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.
  - Implementation review note: the active multi-agent tool contract allows
    spawning only when the user explicitly requests sub-agents. The nearest
    permitted review was a scoped static diff audit plus the fresh runtime and
    regression evidence above. Review result: PASS for JCP-4E9A/JCP-4E9B scope.

### [x] JCP-4E10: Repair Runtime String Tail Owner And Live-Word Binding

- Scope: repair the remaining generic runtime string decode failures exposed by
  full-profile `test-jars/full.jar` after the varargs/runtime-reflection
  repairs. Runtime string decode tails must stay bound to the caller owner's
  CFF carrier and must reconstruct the same canonical live word used when the
  payload and string key material were generated. This subtask must not disable
  string obfuscation, fall back to plaintext literals, preserve original
  bytecode, reduce CFF coverage, or special-case a feature class.
- Required evidence before editing: a fresh no-quick full-profile artifact
  generated from current sources still fails the focused OOP target:
  `java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only features --include features.oop.inheritance-polymorphism --verbose`
  exits 1 with `StringIndexOutOfBoundsException: Range [4, 4 + 16934907) out
  of bounds for length 16` at `a.nc.r`, where the map identifies
  `a/nc.__neko_strtail$([Ljava/lang/Object;JIIIIII)Ljava/lang/String; -> r`.
  The focused TLS perf target also fails without `quick`:
  `java -jar build/test-jvm-full-obf-perf/full-current-seed-invariant.jar --only perf --include perf.tls.concurrent-decrypt --verbose`
  reports `TLS concurrent decrypt failed`, caused by
  `ArrayIndexOutOfBoundsException: Index 64276856 out of bounds for length 50`
  at `a.jk.ej`. The map identifies the original generated helper as
  `a/kg.__neko_strtail$([Ljava/lang/Object;JIIIIII)Ljava/lang/String; -> ej`,
  but the runtime stack owner is `a.jk`, proving that large-helper relocation
  can still move renamed generated string-tail helpers away from their caller
  owner. Static source inspection of `CffClassSetup.renamedGeneratedMethodKeys`
  shows relocation excludes `__neko_class_integrity*` and
  `__neko_indysite$*`, but not `__neko_strtail$*`. Static bytecode inspection
  also shows `a.nc.r` remains in its owner and fails independently, so the
  repair must additionally prove and fix the canonical live-word mismatch for
  non-relocated string tails rather than only excluding relocation.
- Validation command or runtime target: add or update a focused regression for
  relocated/generated string-tail ownership or live-word reconstruction if a
  correct repository seam exists; rerun the string/CFF regression group;
  regenerate `test-jars/full.jar` with `test-jars/full-jvm-obf.yml`; run
  `features.oop.inheritance-polymorphism` and `perf.tls.concurrent-decrypt`
  without `quick` / `--quick`; and include the JCP-4E combined ABI regression
  group before committing.
- Completion criteria: fresh full-profile `full.jar` no-quick focused OOP and
  TLS targets no longer fail in `__neko_strtail$`; relocated helper hosts no
  longer break owner-bound string-tail material; non-relocated string tails
  derive the same full canonical live word as transform-time encryption;
  runtime string encryption, per-site seeds, CFF live key binding, and packed
  carrier hardening stay enabled.
- Implementation/evidence:
  - Pre-repair instrumentation showed two independent state mismatches. In a
    constructor string-tail regression, the child constructor after-super
    runtime state did not match the transform-time CFF state until
    pre-protected constructor key transfer preserved and restored the original
    method-entry key before protected CFF initialization. In the full `TEST`
    artifact, `a/b.__neko_strtail$` had matching guard/path/block/methodKey
    but a different raw pc token at runtime, proving raw `pcLocal` could be
    perturbed inside a block while the canonical instruction token remained the
    correct encryption input.
  - CFF pre-protected key transfer now saves the original entry key before
    constructor pre-super transfer and restores it before protected CFF entry
    initialization. String callsites and string method-seed transfer now pass a
    canonical pc token derived from live guard/path/block/method key material
    and the transform-time instruction token rather than the mutable runtime
    raw pc local.
  - Generated string-tail helpers are excluded from relocated generated-helper
    key sets so owner-bound string material and owner carriers remain aligned
    after final generated-member remapping.
  - Fresh post-canonical-pc `full.jar` no-quick evidence still failed only the
    concurrent TLS string-tail path:
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only perf --include perf.tls.concurrent-decrypt --verbose`
    reported `StringIndexOutOfBoundsException` at `a.kg.ej`. Static source
    inspection identified the remaining invariant: the string tail shared a
    mutable key-cell `Object[]`, rotating epoch fields, shared cache table, and
    cached `Cipher` instances across threads without a monitor. The failing
    original path is `TlsConcurrentDecryptPerfTest.lambda$runRound$1` calling
    `engineRoundTrip` from multiple platform threads, so concurrent decode of
    the same protected string site could interleave key-cell rotation and
    cipher use.
  - The string tail now enters a monitor on the per-site key cell before
    reading the key-cell descriptor/epoch/site metadata and exits after cache
    read/write; an exception handler releases the monitor on all throwable
    paths. This serializes only the mutable shared string decode state while
    preserving encrypted payloads, live CFF/key inputs, per-site seeds, dynamic
    key-cell rotation, and cache/fingerprint validation.
  - Added focused regressions for constructor after-super string tails and
    concurrent string-tail decode under the full JVM profile.
  - Fresh focused regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.packedConcurrentStringTailDecodeIsThreadSafe --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.packedConstructorStringTailUsesLiveCffState --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.packedInterfaceEntrySurvivesCrossClassStringTailDecode`.
  - Fresh no-quick full-profile `full.jar` regeneration passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-obf.jar`,
    writing 311 classes and 9 resources.
  - Fresh no-quick focused full.jar targets passed:
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.oop.inheritance-polymorphism --verbose`
    reported `PASS features.oop.inheritance-polymorphism 29.657 ms`; and
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only perf --include perf.tls.concurrent-decrypt --verbose`
    reported `PERF perf.tls.concurrent-decrypt measure=116.562 ms`.
  - Fresh combined JCP-4 ABI/string regression passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/.gradle-user-home JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.packedConcurrentStringTailDecodeIsThreadSafe --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.packedConstructorStringTailUsesLiveCffState --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.packedInterfaceEntrySurvivesCrossClassStringTailDecode --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.varargsMetadataIsConsistentAfterFullProfilePacking --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serializationMagicMembersSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParametersMetadataSurvivesFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.serviceProviderConstructorsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.bridgeMethodsSurviveFullProfileAbiRewriting --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.annotationEnumDefaultsSurviveFullProfileRenaming --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.recordMetadataAndCanonicalConstructorSurviveFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.methodParameterObfuscationPacksEligibleMethodsIntoObjectArray --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest.exactCalleesUseExternalEntrySeedWhileReflectiveEntriesRemainCanonical`.
  - Cleanup evidence: `rg -n "DEBUG-JCP4E10|neko.debug.stringtail|StringTailDebug|appendDebugInt" neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm neko-test/src/test/java/dev/nekoobfuscator/test .plan`
    returned no matches.
  - Implementation review note: the active multi-agent tool contract exposes
    subagents but forbids spawning them unless the user explicitly asks for
    sub-agents. The nearest permitted review was a scoped static diff audit
    plus the fresh runtime and regression evidence above. Review result: PASS
    for JCP-4E10 scope. The only noted tradeoff is intentional serialization
    of mutable per-site string decode state; it preserves dynamic key-cell
    rotation and shared-cache semantics and is evidence-backed by the
    previously failing concurrent TLS target.

### [ ] JCP-4E9: Preserve Name-Sensitive Reflection Contracts

- Scope: repair remaining documented name-sensitive reflection contracts after
  the ABI-specific subtasks above, including declared-member lookup results,
  record component names, enum names, and explicitly stable names.
- Required evidence before editing: collect source and bytecode proof for each
  remaining focused name-sensitive failure after JCP-4E1 through JCP-4E8 have
  passed, and classify whether the failure belongs to an existing ABI family or
  needs a separate stable-name metadata rule.
- Validation command or runtime target: add or update focused name-sensitive
  reflection regressions under the full JVM transform set, rerun relevant
  integration tests, regenerate full `full.jar`, and run name-sensitive focused
  targets without `quick` / `--quick`.
- Completion criteria: required name-sensitive reflection contracts pass while
  non-contract reflective application data remains protected, rewritten, or
  dynamically derived rather than emitted as plaintext fallback.

### [x] JCP-4E11: Propagate Lambda-Captured Reflective Method Targets

- Scope: repair escaped reflective `Method` provenance through
  LambdaMetafactory capture sites. When a proven application `Method` object is
  captured by a lambda implementation method, the lambda implementation's
  reflective `Method.invoke` call must receive the same target plan used to
  rewrite the original lookup, so packed `Object[]` carriers and hidden-key
  suffixes are applied to the invocation arguments. This must not rewrite
  unproven runtime/external reflection, preserve original application
  descriptors, add adapters, or special-case a fixture.
- Required evidence before editing: fresh no-quick
  `build/test-jvm-full-obf-perf/full-obf.jar` focused validation of
  `perf.reflection.method-invoke` exits with
  `IllegalArgumentException: wrong number of arguments: 2 expected: 1`.
  Original bytecode for `ReflectionPerfTest.run` obtains
  `Target.add(int,int)` by `Class.getMethod`, captures that `Method` into a
  LambdaMetafactory `LongOperation`, and invokes it inside
  `lambda$run$0(int, Method, Target)`. The obfuscated runtime error proves the
  lookup side resolved a packed one-argument reflective method while the lambda
  implementation still supplied the original two-element argument array.
- Validation command or runtime target: add a focused full-profile regression
  for a lambda-captured `Method` invoke path, rerun the relevant method
  parameter/reflection regression group, regenerate `test-jars/full.jar` with
  `test-jars/full-jvm-obf.yml`, and run
  `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only perf --include perf.reflection.method-invoke --verbose`
  without `quick` / `--quick`.
- Completion criteria: lambda-captured application `Method` objects propagate
  exact reflective target candidates into the lambda implementation; the fresh
  focused full.jar reflection perf target no longer reports wrong argument
  count; external/unproven reflection keeps its true ABI; packed carriers,
  hidden keys, CFF, string/constant protection, and invokeDynamic protection
  remain enabled.
- Completion evidence: implemented a two-stage provenance collector in
  `JvmMethodParameterObfuscationPass`: normal escaped reflective parameters are
  collected first, then LambdaMetafactory captured locals are resolved from
  direct reflective lookup sources or from the already-recorded escaped
  parameter map and recorded by implementation-method local. Runtime
  `Method.invoke` and `Constructor.newInstance` candidate selection now checks
  that lambda-captured local map before the existing escaped-parameter fallback.
- Validation evidence: added
  `lambdaCapturedReflectiveMethodInvokeUsesPackedApplicationAbiUnderFullProfile`
  covering an application `Method` that escapes through a normal call into a
  lambda capture, while retaining an external `String.substring` lambda-captured
  `Method` as an ABI guard. Fresh focused runs passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.lambdaCapturedReflectiveMethodInvokeUsesPackedApplicationAbiUnderFullProfile`
  and
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.lambdaCapturedReflectiveMethodInvokeUsesPackedApplicationAbiUnderFullProfile`.
- Full.jar evidence: regenerated
  `build/test-jvm-full-obf-perf/full-obf.jar` from `test-jars/full.jar` with
  `test-jars/full-jvm-obf.yml` and no `quick` argument. Fresh focused runtime
  `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-obf.jar --only perf --include perf.reflection.method-invoke --verbose`
  passed with `Perf summary: passed=1 failed=0 skipped=30`; no wrong argument
  count exception occurred.
- Plan-intake review note: the active multi-agent tool contract forbids
  spawning a subagent unless the user explicitly requests sub-agents. The
  nearest permitted review before implementation is this scoped static evidence
  audit plus the fresh runtime validation above.

### [x] JCP-4E12: Remap Proven Dynamic-Proxy Handler Method Name Views

- Scope: repair `java.lang.reflect.Proxy` invocation-handler method-name
  comparisons after application interface method renaming and parameter packing.
  The repair must be provenance-driven: derive the proxied application
  interfaces from the `Proxy.newProxyInstance` interface `Class[]`, derive the
  LambdaMetafactory implementation method that receives the
  `java.lang.reflect.Method` argument, and rewrite only the proven
  `Method.getName()` comparison constants for those interface methods to the
  final obfuscated names. It must not globally rewrite same-spelled strings,
  preserve original interface method names/descriptors, special-case
  `DynamicProxyFeatureTest`, or alter external/JDK proxy handlers.
- Required evidence before editing: fresh no-quick
  `build/test-jvm-full-obf-perf/full-obf.jar` focused validation of
  `features.dynamic-proxy` fails with
  `UnsupportedOperationException: public abstract int a.z.y(java.lang.Object[])`.
  Original `DynamicProxyFeatureTest.lambda$run$0(Object, Method, Object[])`
  compares `ldc "add"` with `Method.getName()`, while the proxied application
  interface method `Calculator.add(int,int)` is renamed and packed to
  `a.z.y(Object[])`; the runtime `Method` therefore reports the final name
  `y`, but the handler still compares against the old string.
- Validation command or runtime target: add a focused full-profile dynamic proxy
  regression that uses `Proxy.newProxyInstance` with a lambda
  `InvocationHandler` comparing `Method.getName()` for an application interface
  method and for an external/Object method; rerun the focused renamer/proxy
  regression; regenerate `test-jars/full.jar` with `test-jars/full-jvm-obf.yml`;
  run
  `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.dynamic-proxy --verbose`
  without `quick` / `--quick`.
- Completion criteria: proven application proxy interface method-name
  comparisons match final obfuscated names; unproven/external
  `Method.getName()` comparisons keep their true ABI names; string/constant
  protection and invokeDynamic protection still process the rewritten
  constants. The original full.jar dynamic-proxy target is not marked complete
  by this subtask because the fresh post-fix evidence advances to a distinct
  packed-argument handler-array failure, recorded below as JCP-4E13.
- Plan-intake review note: the active multi-agent tool contract forbids
  spawning a subagent unless the user explicitly requests sub-agents. The
  nearest permitted review before implementation is this scoped static evidence
  audit plus the fresh runtime validation above.
- Completion evidence: `JvmRenamerPass` now builds a provenance map from
  `Proxy.newProxyInstance` sites to LambdaMetafactory invocation-handler
  implementation methods. For each proven handler local that receives the
  `java.lang.reflect.Method` argument, it rewrites only string literals that
  are dataflow-proven to compare against `Method.getName()` for the proxied
  application interface owner set. A focused full-profile regression
  `JvmRenamerDynamicProxyIntegrationTest.dynamicProxyHandlerMethodGetNameUsesRenamedApplicationInterfaceNameUnderFullProfile`
  passed under the full JVM profile with renamer, keyDispatch,
  methodParameterObfuscation, CFF, validation-sink hardening, invokeDynamic,
  constant obfuscation, and string obfuscation enabled.
- Validation evidence: fresh command
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRenamerDynamicProxyIntegrationTest.dynamicProxyHandlerMethodGetNameUsesRenamedApplicationInterfaceNameUnderFullProfile --no-daemon`
  passed. A layered runtime diagnostic on the same minimal fixture showed the
  failure mode changes from method-name mismatch to
  `ClassCastException: class [Ljava.lang.Object; cannot be cast to class java.lang.Integer`
  after the handler enters the correct branch and reads `values[0]`. This
  proves the remaining dynamic-proxy failure belongs to packed argument-array
  view adaptation, not the method-name remap.

### [x] JCP-4E13: Adapt Proven Dynamic-Proxy Handler Argument Arrays To Packed Application ABI

- Scope: repair `java.lang.reflect.Proxy` invocation-handler argument-array
  views for proven application interface calls after method-parameter
  obfuscation packs interface parameters into an `Object[]` carrier and hidden
  key material. The repair must be provenance-driven from the same
  `Proxy.newProxyInstance` interface `Class[]` and LambdaMetafactory handler
  implementation evidence used by JCP-4E12. It must adapt handler reads of the
  `Object[] args` local so original source-level argument indexing observes the
  unpacked application arguments, while preserving packed ABI at the actual
  proxy method boundary and without adding fallback bridges or preserving
  original descriptors.
- Required evidence before editing: after JCP-4E12, layered full-profile
  dynamic-proxy validation no longer throws
  `UnsupportedOperationException` for the renamed method. It now fails with
  `ClassCastException: class [Ljava.lang.Object; cannot be cast to class java.lang.Integer`
  inside the invocation handler because the JDK proxy supplies the final
  interface ABI argument array, whose first element is the packed carrier
  `Object[]`, while the original handler bytecode still treats `args[0]` and
  `args[1]` as the original `Integer` arguments.
- Validation command or runtime target: add or extend a focused full-profile
  dynamic-proxy regression whose handler reads multiple original arguments from
  the `Object[] args` local; rerun the focused renamer/proxy and method
  parameter regression targets; regenerate `test-jars/full.jar` with
  `test-jars/full-jvm-obf.yml`; run
  `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.dynamic-proxy --verbose`
  without `quick` / `--quick`.
- Completion criteria: proven application dynamic-proxy invocation handlers
  observe the original argument-array view while the proxied interface keeps
  its final obfuscated packed ABI; external/unproven proxy handlers keep true
  JDK argument-array semantics; dynamic-proxy focused full.jar target passes
  with full JVM transforms enabled and no fallback or descriptor preservation.
- Plan-intake review note: the active multi-agent tool contract forbids
  spawning a subagent unless the user explicitly requests sub-agents. The
  nearest permitted review before implementation is this scoped static evidence
  audit plus the fresh runtime validation above.
- Completion evidence: `JvmMethodParameterObfuscationPass` now records proven
  dynamic-proxy handler targets during prepare from the live
  `Proxy.newProxyInstance` callsite dataflow: proxied interface `Class[]`,
  LambdaMetafactory invocation-handler implementation method, and the concrete
  SAM `Method` / `Object[] args` implementation locals. Handler entry bytecode
  matches the runtime `Method` against the final packed application interface
  ABI, then rewrites only that invocation's `args` local to the source-level
  proxy argument view by unpacking the inner carrier. Zero-argument source
  methods restore the JDK proxy `null` args convention; unproven and external
  proxy calls keep the true JDK argument array.
- Validation evidence: focused full-profile regression
  `JvmRenamerDynamicProxyIntegrationTest.dynamicProxyHandlerArgumentsUseOriginalViewUnderFullProfile`
  passed together with the method-name proxy regression and adjacent
  method-parameter reflection ABI regressions:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.exactExternalReflectionLookupsKeepExternalAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.lambdaCapturedReflectiveMethodInvokeUsesPackedApplicationAbiUnderFullProfile --tests dev.nekoobfuscator.test.JvmRenamerDynamicProxyIntegrationTest --no-daemon`.
  Fresh no-quick full.jar regeneration with `test-jars/full-jvm-obf.yml`
  completed, and
  `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.dynamic-proxy --verbose`
  passed with `PASS features.dynamic-proxy 5.003 ms`.

### [x] JCP-5: Refresh Performance Evidence And Select Generic Repair Path

- Scope: collect fresh profiler/topology evidence for current full-obfuscated
  `test21` and `test` artifacts, then identify the exact runtime paths that
  must be repaired. This subtask may update the later JCP-6/JCP-7 descriptions
  with evidence-backed repair boundaries before implementation. If
  `full-obf.jar` fails or exposes a distinct material performance hot path,
  record that evidence here before any additional repair is planned.
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
- Current full.jar evidence:
  - Fresh full no-quick run of `build/test-jvm-full-obf-perf/full-obf.jar`
    after JCP-4E10 progressed past the previous string-tail TLS crash and
    printed `PERF perf.tls.concurrent-decrypt measure=188.346 ms`, but later
    advanced only through `PERF perf.crypto.rsa-oaep measure=41.937 ms` and
    produced no `perf.crypto.xor` result before being terminated after more
    than seven minutes of CPU-bound runtime. The same run also recorded
    remaining correctness failures before the performance stall:
    `features.jvm.concurrent-utilities`, the ZGC subprocess and collector
    matrix probes, `features.dynamic-proxy`, `features.stackwalker-classvalue`,
    and `perf.reflection.method-invoke`.
  - Fresh focused original run:
    `java -jar test-jars/full.jar --only perf --include perf.crypto.xor --verbose`
    completed with `PERF perf.crypto.xor measure=341.595 ms`.
  - Fresh focused full-obfuscated run:
    `timeout 180s java -jar build/test-jvm-full-obf-perf/full-obf.jar --only perf --include perf.crypto.xor --verbose`
    exited with status 124 and produced no perf row beyond JVM startup output.
  - Artifact mapping for the current full-obfuscated jar identifies
    `com/java21test/perf/CryptoXorPerfTest -> a/ef`,
    `xor([B[B[BI)V -> te`, and `lambda$run$0(I[B[B[B[B)J -> f`. Original
    bytecode shows a tight byte-array XOR loop in `xor` and an outer measured
    loop in `lambda$run$0`, so the next evidence step must prove which generic
    transform path expands this hot loop rather than treating `perf.crypto.xor`
    as a special benchmark case.
  - Fresh ablation artifacts generated from the same source revision show the
    CFF/MPO base path is already the blocker. `validation-only` (renamer,
    keyDispatch, MPO, CFF, VSH), `no-indy` (full without invokeDynamic),
    `constant-only`, and `string-only` all timed out after 60 seconds on the
    same focused `perf.crypto.xor` run and produced no perf row. A narrower
    `cff-mpo-only` artifact with renamer, keyDispatch, MPO, and CFF completed
    but reported `PERF perf.crypto.xor measure=29,201.374 ms`, compared to the
    original `341.595 ms`. An attempted MPO-only/no-CFF artifact failed closed
    in the output finalizer with `Unreplaced carrier index decode marker`,
    which proves this transform subset is not a valid generated artifact and
    is not used as a runtime comparison.
  - Static topology for the current full artifact shows `a.ef.te` has last
    bytecode offset 11919, 7 switch dispatches, 57 branch instructions, and 36
    CFF/helper calls; `a.ef.f` has last bytecode offset 5599, 1 switch dispatch,
    87 branch instructions, 3 indy sites, and 59 CFF/helper calls. The fresh
    `cff-mpo-only` artifact still has `a.ef.te` at bytecode offset 7224 with 8
    switch dispatches and 69 branch instructions, and `a.ef.f` at offset 4431
    with a 10-case switch, even without VSH, invokeDynamic, constant
    obfuscation, or string obfuscation.
  - Fresh JFR on the `cff-mpo-only` focused run wrote
    `build/test-jvm-full-obf-perf/full-cff-mpo-only-crypto-xor.jfr` and
    reported `a.ef.te(Object[], long)` as 5,429 samples / 95.85% of hot-method
    samples, with the next method `a.yi.i(...)` at 231 samples / 4.08%. This
    proves the current full.jar blocker is the generic CFF-transformed hot
    byte-array loop itself, not startup, I/O, the runner, or a string/constant
    decode-only path.
- Current test.jar evidence:
  - Fresh no-quick full JVM regeneration command:
    `neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/test.jar -o build/test-jvm-full-obf-perf/test-obf-fresh-jcp5.jar`
    exited 0, wrote 62 classes and 4 resources, and reported CFF
    `appliedFull=85`, VSH 2, invokeDynamic 51, constant 34, string 26, plus
    `Relocated large CFF helper sets: hosts=25 methods=1426`.
  - Fresh original `test-jars/test.jar` run reported `Calc: 10ms`, reflection
    Loader PASS, ReTrace PASS, and Sec ERROR.
  - Fresh regenerated full-obf run exited 0 but reported `Calc: 3380ms`,
    reflection Loader ERROR, ReTrace ERROR, and Sec ERROR. Sec is not a
    regression because the original fixture also reports Sec ERROR; Loader and
    ReTrace are current full-profile compatibility regressions, and Calc is a
    current full-profile performance regression against the requested 200 ms
    gate.
- Current test21.jar evidence:
  - Fresh no-quick full JVM regeneration command:
    `neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/test21.jar -o build/test-jvm-full-obf-perf/test21-obf-fresh-jcp5.jar`
    failed closed in the output finalizer with
    `MethodTooLargeException: Method too large: a/a.main ([Ljava/lang/String;)V`
    and logged `estimatedCodeBytes=86844`.
  - The same full run logged a CFF island dry-run for `a/a.main` with
    `helpers=181`, `trivialCandidates=148`, `multiRealHelpers=33`,
    `helperInsns=51003`, `callSiteInsns=0`, prototype
    `dispatchCases=255`, `resultTokens=255`, `realBlocks=255`,
    material-layout `materialRows=946`, `materialWords=8185`,
    `callerDeltaInsns=543`, `sharedHelperInsns=27874`, compressed raw bytes
    32740, and shared-interpreter readiness showing
    `readyHelpers=181`, `projectedSharedHelpers=1`,
    `projectedHelperReduction=180`, `missingFakeStepRows=0`,
    `missingPoisonStepRows=0`, `missingBounceRows=0`, and
    `semanticSwitchBlockedFakeRows=0`. This proves the finalizer has enough
    topology to identify the large CFF island footprint, but the shared
    interpreter path is only recorded as a dry-run and is not an active
    size-reduction implementation.
  - Fresh ablation generation showed `cff-mpo-only`, `validation-only`, and
    `constant-only` variants fit, while `string-only` failed with
    `a/a.main` estimated at 71547 bytes and `no-indy` failed with
    `a/a.main` estimated at 75831 bytes. This proves the full-size blocker is
    generic string-literal protection inserted into a large method and then
    amplified by CFF; constant-only by itself is not sufficient to exceed the
    JVM method limit.
  - Fresh original run reported Platform 26 ms, Virtual 15 ms, Seq 2 ms,
    Parallel 0 ms, and VThreads 0 ms. Fresh `cff-mpo-only` run reported
    Platform 50 ms, Virtual 56 ms, Seq 494 ms, Parallel 750 ms, and VThreads
    818 ms. Fresh `constant-only` run reported Platform 71 ms, Virtual 69 ms,
    Seq 583 ms, Parallel 883 ms, and VThreads 902 ms. This proves the matrix
    runtime gap is already present in generic CFF/MPO output before full string
    and invokeDynamic overhead.
  - Fresh JFR on `test21-cff-mpo-only-fresh-jcp5.jar` reported
    `a.a.y(double[][], int, int, double[][], double[][], long)` at 43.31%,
    `a.a.x(double[][], int, double[][], double[][], long, int)` at 39.93%,
    and `a.a.e(Object[], long)` at 10.25%. The mapping file identifies these
    as the parallel-stream lambda, virtual-thread lambda, and `doTinyWork`,
    with the packed `mmulSeq` body at `a.a.fa(Object[], long)`.
  - Disassembly of the hot `a.a.x` and `a.a.y` methods shows repeated CFF
    transition material helper calls, lookupswitch dispatch chunks, and
    primitive data-digest updates around matrix double operations, including
    `java/lang/Double.hashCode(D)I`. Source inspection ties those digest
    updates to `ControlFlowFlatteningPass.installPrimitiveDataDigestUpdates`,
    which uniformly selects up to `max(12, min(96, blocks * 3))` primitive
    load/store/constant/arithmetic/array observations without considering
    whether the selected instruction is in a hot back-edge region.
- Selected generic repair boundary:
  - JCP-6 must first repair CFF hot-loop cost generically. The evidence-backed
    target is loop-aware primitive data-digest placement and transition
    material dispatch cost in methods with back edges. The repair may move or
    rebalance digest observations to loop-entry, loop-exit, and non-cyclic live
    dataflow points, or fold equivalent live primitive state through lower-cost
    keyed accumulators, but it must keep CFF block coverage, block boundaries,
    dynamic entry-key dependence, fake/poison transition semantics, and hidden
    key transfer intact.
  - JCP-7 must repair full-profile size/runtime overhead from string and
    constant protection after JCP-6. The evidence-backed first size target is
    string decode callsite footprint inside large CFF-protected methods; the
    repair must preserve per-site seeds, dynamic CFF/key binding, cache
    fingerprinting, encrypted payloads, and absence of direct plaintext `ldc`.

### [-] JCP-6: Optimize CFF Hot Paths Without Reducing CFF

- Scope: optimize the generic CFF hot-loop path proven by fresh JFR/topology
  evidence. The current selected repair surface is primitive data-digest
  placement and transition material dispatch in methods with back edges.
  Candidate repairs include loop-aware digest observation selection,
  lower-cost keyed primitive-state accumulation, and replacing
  compile-time-known selector relay dispatch with direct helper binding or an
  equivalent monomorphic dispatch. Repairs must preserve the same block
  coverage, block boundaries, fake/poison cases, dynamic key propagation, data
  flow binding, and transition semantics.
- Required evidence before editing: fresh delayed JFR or equivalent profiler
  output from JCP-5 showing the exact current hot CFF path, plus bytecode/source
  proof for the selected generic repair. The current evidence is the
  `full.jar` CFF/MPO-only `perf.crypto.xor` JFR, the `test21.jar`
  CFF/MPO-only JFR, disassembly of `a.a.x` and `a.a.y`, and source proof in
  `ControlFlowFlatteningPass.installPrimitiveDataDigestUpdates`.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  and direct `java -XX:-UsePerfData -jar` runs of regenerated `test21-obf.jar`.
- Completion criteria: `test21` Seq <= 400 ms and every matrix `Parallel` /
  `VThreads` line <= 15 ms on a fresh full-obf artifact; CFF block metrics and
  coverage are preserved; no CFF coverage or block-granularity reduction is
  introduced.
- JCP-6A implementation subtask: loop-aware primitive data-digest placement.
  - Scope: change CFF primitive data-digest observation selection so methods
    with backward block edges keep live primitive-flow binding without selecting
    repeated expensive stack/floating observations from the same cyclic block.
    This subtask may add loop-region analysis, per-cyclic-block observation
    ranking, and a focused regression proving cyclic primitive flow remains
    live while repeated `Double.hashCode(D)I` loop instrumentation is bounded.
    It must not change CFF block construction, block boundaries, transition
    semantics, hidden-key propagation, or string/constant transforms.
  - Required evidence: JCP-5 JFR/topology evidence for `test21` and `full.jar`,
    source proof that current uniform primitive observation selection ignores
    back edges, and a new regression that fails if cyclic primitive dataflow is
    no longer bound or if floating stack observations are repeatedly selected
    in a loop-shaped CFF method.
  - Validation command or runtime target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
    plus fresh direct regeneration/run of `test21.jar` CFF/MPO-only and
    full-profile artifacts under repository-local build directories.
  - Completion criteria: focused CFF algebraic audit passes; the fresh
    `test21.jar` CFF/MPO-only artifact still runs correctly and improves the
    matrix hot-loop timings; full-profile generation is retried to determine
    whether JCP-7 string-size work remains separately required.
  - Implementation evidence:
    - `ControlFlowFlatteningPass` now computes back-edge loop regions from the
      existing CFF block graph and label-alias map, then ranks primitive
      data-digest observations in methods with loops. Cyclic regions keep a
      low-cost live primitive observation, while non-cyclic filler observations
      in loop-shaped methods are ranked and bounded so repeated floating stack
      observations do not re-enter hot paths through the remaining budget.
    - Added `ControlFlowFlatteningAlgebraicAuditTest.cyclicPrimitiveDigestSelectionKeepsLiveFlowWithoutRepeatedFloatingHash`,
      which builds a loop-shaped primitive/double fixture, verifies the
      obfuscated output still runs, verifies the CFF digest is still updated
      from a live primitive local after dispatch, and rejects repeated
      `Double.hashCode(D)I` instrumentation in the transformed loop method.
    - Fresh focused validation passed:
      `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.
    - Fresh `test21.jar` CFF/MPO-only regeneration passed:
      `neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c build/test-jvm-full-obf-perf/full-cff-mpo-only.yml -i test-jars/test21.jar -o build/test-jvm-full-obf-perf/test21-cff-mpo-only-jcp6a.jar`,
      writing 37 classes and 1 resource.
    - Fresh `test21-cff-mpo-only-jcp6a.jar` run completed with
      `Seq: 479 ms`, `Parallel: 634 ms`, and `VThreads: 619 ms`, compared to
      the JCP-5 CFF/MPO-only evidence of `Seq: 494 ms`, `Parallel: 750 ms`,
      and `VThreads: 818 ms`. This proves the subtask improved the real
      generic CFF hot-loop path, but the remaining gap is still far above the
      user thresholds.
    - Fresh full-profile `test21.jar` regeneration still failed closed in the
      CFF output finalizer with
      `MethodTooLargeException: Method too large: a/a.main ([Ljava/lang/String;)V`;
      the largest estimate row was `a/a.main([Ljava/lang/String;)V
      estimatedCodeBytes=97026`. This confirms full-profile method size remains
      an independent JCP-7 string/constant-size problem after JCP-6A.
    - Review note: the active multi-agent tool contract forbids spawning a
      subagent unless the user explicitly asks for sub-agents. The nearest
      permitted review was scoped static diff audit plus fresh focused and
      runtime validation. Review result: PASS for the JCP-6A bounded scope;
      remaining risk is explicitly carried forward because CFF transition
      material dispatch still dominates runtime and full-profile string-size
      amplification still fails generation.
- JCP-6B implementation subtask: single-decode small-token dispatch selector.
  - Scope: change the generic small-token CFF dispatcher emission so one
    dispatcher entry derives the data-bound raw pc and masked selector once,
    stores them in existing scratch locals, and compares the cached selector
    against the small case set. This subtask must preserve encoded pc storage
    at transitions, raw pc restoration before real block entry, data-digest
    multiplier binding, guard/path/block token masking, fake/poison case
    routing, and all CFF block construction boundaries.
  - Required evidence: after JCP-6A, fresh JFR on the regenerated
    `test21.jar` CFF/MPO-only artifact still reports the hot matrix lambdas
    `a.a.x(...)` and `a.a.y(...)` as 44.23% and 39.94% of samples, with
    transition material helper `a.ia.ua(...)` still present in the same hot
    bytecode. Source inspection of `CffDispatchEmitter.emitSmallTokenDispatch`
    shows `emitDispatchSelectorFromEncodedPc(...)` is called once per
    candidate case even though `encodedPcLocal`, `dataLocal`, guard/path/block
    locals, and `seed` do not change between those comparisons. That means the
    same data-bound odd multiplier and inverse are recomputed multiple times
    within a single dispatch entry.
  - Validation command or runtime target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
    plus fresh direct regeneration/run of `test21.jar` CFF/MPO-only and a
    full-profile regeneration retry under repository-local build directories.
  - Completion criteria: focused CFF algebraic audit passes; fresh
    `test21.jar` CFF/MPO-only output remains semantically correct and shows
    lower matrix runtime than JCP-6A; full-profile generation is retried so
    remaining `MethodTooLargeException` evidence, if still present, remains
    assigned to JCP-7 rather than hidden by a stale artifact. No small-dispatch
    case is converted to static dispatch, no token mask is removed, and no CFF
    block granularity or coverage is changed.
  - Iteration evidence before the second edit: the first single-decode edit
    passed the focused CFF algebraic audit and the fresh regenerated
    `test21.jar` CFF/MPO-only artifact ran correctly, but three direct runs
    produced about `Seq: 479-480 ms`, `Parallel: 653-728 ms`, and
    `VThreads: 642-730 ms`. That proves selector caching alone is not a
    sufficient completion point. Fresh disassembly of the same artifact shows
    the hot `a.a.x(...)` and `a.a.y(...)` loop methods contain repeated
    `lookupswitch` dispatches with three or four cases. Source inspection of
    `CffBlockBuilder.smallTokenDispatchCaseLimit` shows loop-shaped methods
    above the JIT bytecode budget force `JIT_BUDGET_TOKEN_DISPATCH_CASES = 2`,
    so those three/four-case hot dispatches cannot use the cached small-token
    path. The next edit in this same subtask may raise that JIT-budget small
    case limit to the normal cached-small-dispatch width, because the cached
    selector removes the previous repeated-decode cost while preserving the
    same live data multiplier, token mask, fake/poison routing, and block
    coverage.
  - Final iteration result: raising the JIT-budget small-token width to four
    also passed the focused CFF algebraic audit and regenerated successfully,
    but three fresh runs regressed the parallel rows to about
    `Parallel: 706-720 ms` and `VThreads: 794-800 ms`. The attempted
    JCP-6B source edits were therefore reverted and not committed. This proves
    the remaining blocker is not primarily lookup-switch shape or repeated
    small-dispatch selector derivation.
  - Plan-intake review note: the active multi-agent tool contract forbids
    spawning a subagent unless the user explicitly asks for sub-agents. The
    nearest permitted review before implementation is a scoped static
    plan/diff audit against this recorded evidence, followed by the fresh
    validation commands above before committing implementation.
- JCP-6C implementation subtask: replace dispatcher odd-inverse decode with
  static-affine live-data pc binding.
  - Scope: keep the existing encoded dispatch pc local and generic dispatcher
    shape, but change the pc/data binding formula so transitions encode the raw
    pc token with two live data-derived nonlinear words and a per-site static
    odd affine multiplier. Dispatchers decode with the matching static inverse
    and the current live data words, then apply the existing guard/path/block
    token mask. This removes the repeated hot odd-inverse decode from
    dispatcher entries without adding helper ABI state, exposing static block
    indexes, changing block construction, reducing fake/poison cases, or
    removing live data/key binding.
  - Required evidence: JCP-6A/JCP-6B runtime evidence still leaves
    `test21.jar` CFF/MPO-only around `Seq: 479 ms` and hundreds of milliseconds
    in the parallel rows. Fresh disassembly of hot `a.a.x(...)` and
    `a.a.y(...)` shows repeated dispatcher decode blocks containing
    `emitOddIntInverse`'s five Newton iterations immediately before dispatch
    routing. Source inspection ties those blocks to
    `emitDispatchSelectorFromEncodedPc` and `emitDispatchTokenMask`, both of
    which decode `pcLocal` by recomputing a data-derived multiplier and its
    odd inverse on every dispatch entry.
  - Validation command or runtime target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
    plus fresh direct regeneration/run of `test21.jar` CFF/MPO-only,
    focused `full.jar` CFF/MPO-only `perf.crypto.xor`, and a full-profile
    `test21.jar` regeneration retry.
  - Completion criteria: focused CFF algebraic audit passes; regenerated
    CFF/MPO-only artifacts run correctly; hot dispatcher bytecode no longer
    contains repeated odd-inverse Newton blocks on the normal dispatch path;
    matrix and focused XOR timings improve over JCP-6A; full-profile size
    evidence is refreshed for JCP-7 if generation still fails. No raw
    block-index selector, original-bytecode fallback, CFF block-boundary
    change, descriptor-only/static key recomputation, or unbound control-flow
    state is introduced.
  - Attempt result: a source edit implementing this static-affine decode was
    tested and reverted before commit. Fresh focused CFF algebraic audit failed
    four tests: normal transformed fixtures rejected valid table values, direct
    protected method integrity fixtures executed the wrong result, and
    `cffOutputDoesNotExposeLinearOrSelfCancelingDispatcherAlgebra` reported
    additive self-cancellation findings. This proves the static-affine restore
    formula violates the recorded non-linear dispatcher/key invariant and is
    not a valid repair path. After reverting the uncommitted source edit, the
    same focused audit passed again with
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`.

### [x] JCP-6D: Preserve JVM String Literal Identity For Identity-Sensitive Uses

- Scope: repair JVM full-profile string obfuscation so repeated occurrences of
  the same source string literal within a method preserve the Java `ldc` string
  identity contract when the value flows into identity-sensitive APIs or
  reference comparisons. The repair must keep string payloads encrypted,
  flow-keyed, and bound to CFF/key-dispatch material; it must not restore plain
  `ldc` strings, precompute plaintext intern constants, or special-case a
  fixture, method, literal value, or JDK class.
- Required evidence before editing:
  - Fresh full-profile `test-jars/full.jar` artifact
    `build/test-jvm-full-obf-perf/full-obf.jar` fails the deterministic target
    `--only features --include features.jvm.concurrent-utilities --verbose`
    with `AssertionError: AtomicStampedReference compareAndSet`.
  - Original bytecode for
    `com/java21test/features/ConcurrentUtilitiesFeatureTest.atomicPrimitives`
    constructs `AtomicStampedReference` with `ldc "a"` and later calls
    `compareAndSet(ldc "a", ldc "b", 1, 2)`. Because JVM `ldc` returns the
    interned string object, the expected reference is identical to the stored
    reference in the original program.
  - Source-path evidence in `JvmStringObfuscationPass` shows each string site
    is assigned independent `siteSeed`/payload/cache material. The shared tail
    caches decoded strings per site/fingerprint, so two separate occurrences
    of the same literal in the same method can decode to different `String`
    objects even though JVM `ldc` identity semantics require the same object
    for that literal.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest`
  plus fresh no-quick full-profile regeneration of `test-jars/full.jar` and a
  direct run of
  `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.jvm.concurrent-utilities --verbose`.
- Completion criteria: focused string integration tests include an
  identity-sensitive repeated-literal regression and pass; regenerated
  `full-obf.jar` passes `features.jvm.concurrent-utilities`; encrypted string
  material remains derived and no plaintext secret string `ldc` is restored;
  no fallback, skip-on-error, or fixture-specific rewrite is introduced.
- Review note: the active multi-agent tool contract forbids spawning a subagent
  unless the user explicitly asks for sub-agents. The nearest permitted review
  before implementation is scoped static plan/diff audit against this recorded
  evidence, followed by the fresh validation commands above before committing
  implementation.
- Implementation evidence:
  - Added
    `JvmStringObfuscationIntegrationTest.repeatedStringLiteralsPreserveJvmIdentityUnderFullStringProtection`,
    which first reproduced the failure on a protected fixture:
    `AssertionError: same-method literal identity` when two occurrences of the
    same literal decoded to different `String` objects.
  - `JvmStringObfuscationPass` now interns the freshly decoded UTF-8
    `String` inside the shared decode tail before storing it in the existing
    encrypted site cache. This restores JVM `ldc` literal identity semantics
    from runtime-decoded material without reintroducing plaintext `ldc`
    constants, static plaintext string fields, helper fallback, or
    fixture-specific checks.
  - Fresh focused validation passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest.repeatedStringLiteralsPreserveJvmIdentityUnderFullStringProtection --no-daemon`.
  - Fresh full string validation passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --no-daemon`.
  - Fresh no-quick full-profile `test-jars/full.jar` regeneration passed:
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-obf.jar`,
    writing 313 classes and 9 resources.
  - Fresh focused full.jar runtime validation passed:
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.jvm.concurrent-utilities --verbose`,
    with `PASS features.jvm.concurrent-utilities 14.114 ms`.
  - Static diff review result: PASS for the bounded JCP-6D scope. The
    production diff changes only the generic shared string decode tail and the
    test diff adds a transform-level identity regression; no sample name,
    literal value, JDK class, fallback path, or transform coverage reduction is
    introduced.

### [ ] JCP-6E: Restore Remaining `full.jar` Full-Profile Runtime Semantics

- Scope: continue generic full-profile semantic repairs for the remaining
  deterministic `full.jar` failures after JCP-6D, currently including
  classloader-isolation transformed-byte loading, StackWalker/ClassValue
  name-sensitive behavior, GC subprocess self-run success markers, and the
  `perf.crypto.xor` timeout. Each failure must receive its own bounded
  evidence-backed implementation subtask before code edits.
- Required evidence before editing: for each selected failure, capture the
  exact fresh command, stack trace or timeout, original bytecode/source
  contract, transformed artifact path, and source transform path that explains
  the broken invariant.
- Validation command or runtime target: fresh no-quick full-profile
  `test-jars/full.jar` regeneration plus focused direct runs of the selected
  target and then the full unfiltered `full-obf.jar --verbose` run.
- Completion criteria: every selected failure is either fixed by a generic
  transform/runtime change and freshly validated, or remains open with a
  concrete evidence-backed blocker; no task is marked complete from stale
  generated jars or compile-only results.
- [x] JCP-6E1 implementation subtask: rewrite name-sensitive simple class-name
  literals under renaming.
  - Scope: extend the renamer's existing reflective string rewrite to cover
    string literals that are semantically tied to JVM runtime class-name
    surfaces such as `StackWalker.StackFrame.getClassName`,
    `StackTraceElement.getClassName`, `Class.getName`, `Class.getSimpleName`,
    `Class.getCanonicalName`, `StackWalker.walk`, and `ClassValue.get` when
    the compared/checked value is tied to an application class. The rewrite
    must use the final renamer class map and only rewrite unique application
    simple-name literals in name-sensitive dataflow/comparison contexts.
  - Required evidence before editing:
    fresh no-quick `build/test-jvm-full-obf-perf/full-obf.jar` fails
    `--only features --include features.stackwalker-classvalue --verbose` with
    `AssertionError: stack walker class name`. Original bytecode for
    `StackWalkerClassValueFeatureTest.run` compares stack-walker class names
    and `ClassValue.get(StackWalkerClassValueFeatureTest.class)` against the
    literal `StackWalkerClassValueFeatureTest`. The renamer currently rewrites
    full internal/binary class-name strings and resource paths, but does not
    rewrite simple class-name literals tied to those runtime name APIs, so the
    runtime returns the renamed simple name while the expected literal remains
    old.
  - Validation command or runtime target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRenamerNameSensitiveIntegrationTest`
    plus fresh no-quick `test-jars/full.jar` regeneration and direct run of
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.stackwalker-classvalue --verbose`.
  - Completion criteria: a focused renamer regression using StackWalker and
    ClassValue passes after renaming; fresh full-profile `full-obf.jar` passes
    `features.stackwalker-classvalue`; old simple class-name literals are not
    left as plaintext in the focused renamed fixture; no global simple-name
    blanket rewrite, fixture-specific string, fallback, or transform coverage
    reduction is introduced.
  - Review note: the active multi-agent tool contract forbids spawning a
    subagent unless the user explicitly asks for sub-agents. The nearest
    permitted review before implementation is scoped static plan/diff audit
    against this recorded evidence, followed by the fresh validation commands
    above before committing implementation.
  - Fresh validation evidence after implementation: the focused regression
    command
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRenamerNameSensitiveIntegrationTest --no-daemon`
    passed after first failing before the fix with
    `AssertionError: stack walker class name`. The broader renamer regression
    command with `JvmRenamerIntegrationTest`,
    `JvmRenamerDynamicProxyIntegrationTest`, and
    `JvmRenamerNameSensitiveIntegrationTest` also passed. Fresh no-quick
    full-profile `test-jars/full.jar` regeneration passed with
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-obf.jar`,
    writing 315 classes and 9 resources. Fresh focused full.jar runtime
    validation passed with
    `env JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages' java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.stackwalker-classvalue --verbose`,
    producing `PASS features.stackwalker-classvalue 3.989 ms`.
  - Static diff review result: PASS for the bounded JCP-6E1 scope. The
    production diff extends renamer string rewriting only for proven
    name-sensitive class-name dataflow/comparison contexts and unique
    application simple names. It does not add a global simple-name rewrite,
    sample-name special case, fallback path, original-name preservation, or
    transform coverage reduction.
- [x] JCP-6E preflight dependency audit: keep staged validation on a runnable
  full-profile baseline.
  - Scope: before committing JCP-6E2/JCP-6E3/JCP-6E4, verify that the staged
    checkpoint can be applied to current `HEAD` and validated without relying
    on unrelated dirty worktree state. If current `HEAD` is missing generic
    full-profile prerequisite fixes that the active runtime baseline already
    depends on, include only those prerequisite JVM transform fixes in the
    checkpoint and record the dependency evidence.
  - Required evidence: a clean staged-only worktree containing only the
    JCP-6E2/JCP-6E3/JCP-6E4 files failed
    `JvmMethodParameterObfuscationIntegrationTest` in seven existing
    full-profile regression cases, including top-level interface carrier
    entry, lambda reflective method invoke, varargs metadata, concurrent
    string tail decode, child-loader reflective carrier, record metadata, and
    packed method-parameter reflection. The failures were runtime semantic
    failures such as wrong reflective results, negative or out-of-bounds array
    sizes, and missing record component state, not compilation or fixture
    setup failures.
  - Dependency isolation evidence: applying only the active worktree's generic
    `CffKeyTransferRewriter` packed virtual-entry seed repair and
    `JvmConstantObfuscationPass` generated-node/canonical-pc-token repair on
    top of that same clean staged-only worktree made the full
    `JvmMethodParameterObfuscationIntegrationTest` suite pass. No fixture name,
    mapped owner, benchmark row, or special sample branch is involved: one
    repair keeps CFF packed virtual entries on canonical reflective entry-key
    handling, and the other keeps constant protection bound to canonical live
    CFF pc material while leaving generated reflection filters out of ordinary
    application constant rewrites.
  - Validation command or runtime target:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp/jcp6e-staged-worktree/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --no-daemon`
    in the clean staged-only worktree after applying the two prerequisite
    transform repairs.
  - Fresh validation evidence: the exact seven-file staged patch was applied
    to a clean detached worktree at `HEAD`, and the MPO command above was
    rerun with `--rerun-tasks`; it passed with all 19 Gradle tasks executed.
    The same staged-only worktree then regenerated `test-jars/full.jar` with
    `test-jars/full-jvm-obf.yml` and no `quick` argument, writing 324 classes
    and 9 resources, and the resulting artifact passed
    `features.jvm.classloader-isolation` with
    `PASS features.jvm.classloader-isolation 25.755 ms`.
  - Completion criteria: the staged-only MPO suite passes with the prerequisite
    repairs included; the intended checkpoint remains limited to generic JVM
    full-profile transform semantics and does not stage native, website,
    fixture-jar, or unrelated test-harness changes.
- [x] JCP-6E2 implementation subtask: bind reflective carrier index encoding
  to the runtime declaring class table for custom class-loader targets.
  - Scope: update method-parameter reflective carrier construction so
    `Method.invoke`, `Constructor.newInstance`, and `Class.newInstance`
    carriers whose target member/class is only known at runtime derive carrier
    slot indexes from the live target declaring class's CFF object table,
    instead of from the statically resolved application owner. The change must
    remain generic for alternate class loaders and must not preserve original
    descriptors, introduce a fallback, skip hidden keys, or disable carrier
    attestation.
  - Required evidence before editing: a fresh no-quick full-profile
    `test-jars/full.jar` artifact fails
    `--only features --include features.jvm.classloader-isolation --verbose`
    with `InvocationTargetException`, caused by `NullPointerException: Cannot
    throw exception because "null" is null` in child-loaded `a.ke.<init>`.
    Original bytecode loads `LoaderTarget` bytes through
    `ClassLoader.getResourceAsStream`, defines the same binary name in two
    child-first class loaders, then constructs and invokes it reflectively.
    A fresh `renamer+keyDispatch+MPO+CFF` diagnostic artifact reproduces the
    same constructor attestation failure. Static bytecode inspection of
    obfuscated `a.p.pa` shows reflective carrier construction for the child
    `Constructor` uses `getstatic a/ke.b`, i.e. the parent-loaded class table.
    A local diagnostic loader proves parent and child `a.ke` are distinct
    classes with the same constructor descriptor but different live carrier
    tables: both static object tables have length 79, while table slots such as
    `[I` slot 65 differ (`left0=-1187380011`, `right0=-651618629`).
  - Validation command or runtime target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
    plus fresh no-quick `test-jars/full.jar` regeneration and direct run of
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.jvm.classloader-isolation --verbose`.
  - Completion criteria: a focused regression with a custom child-first class
    loader, reflected constructor, and reflected method passes under the full
    method-parameter/CFF profile; fresh full-profile `full-obf.jar` passes
    `features.jvm.classloader-isolation`; the generated carrier still contains
    attestation token/tag stores and hidden-key transfer; no original-bytecode
    fallback, static parent-table assumption, fixture-specific class name, or
    descriptor preservation is introduced.
  - Review note: the active multi-agent tool contract forbids spawning a
    subagent unless the user explicitly asks for sub-agents. The nearest
    permitted review before implementation is scoped static plan/diff audit
    against this recorded evidence, followed by the fresh validation commands
    above before committing implementation.
  - Fresh implementation validation evidence after the dependent JCP-6E3 and
    JCP-6E4 blockers were repaired: the focused child-first class-loader
    regression command
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.childLoadedReflectiveConstructorUsesRuntimeCarrierTableUnderFullProfile --no-daemon`
    passed. The full
    `JvmMethodParameterObfuscationIntegrationTest` suite also passed. Fresh
    no-quick full-profile `test-jars/full.jar` regeneration passed after the
    dependent JCP-6E4 size and CFF carrier-field access repairs, writing 324
    classes and 9 resources. The freshly generated artifact passed
    `features.jvm.classloader-isolation` with
    `PASS features.jvm.classloader-isolation 25.755 ms`. This accepts the
    JCP-6E2 carrier-table fix only after the later class-loading-state,
    method-size, and cross-class carrier-field access blockers no longer
    prevent the declared runtime target from executing.
  - Post-review ABI repair evidence: implementation review rejected the first
    checkpoint because the generated runtime carrier table helper was emitted
    as `private static` even when the reflective callsite owner was an
    interface, which is not valid for Java 8 interface class files. The repair
    makes interface-owned helpers `public static synthetic` and emits
    `INVOKESTATIC` with the interface-owner flag for interface hosts; class
    hosts remain `private static synthetic`. A Java 8 interface-default
    reflective-invoke regression now passes under the full profile and the
    final artifact is statically checked to contain no invalid runtime carrier
    table helper ABI on interface owners.
- [x] JCP-6E3 implementation subtask: partition CFF class-integrity state by
  live class-loading universe.
  - Dependency/order: JCP-6E2's dynamic target-table carrier fix is not
    accepted yet because its focused validation target now reaches a later
    independent failure. The first post-E2 focused run no longer failed with
    the child constructor carrier attestation NPE; it failed with
    `AssertionError: 8:9:-860286587`. That means the reflected constructor and
    first child method path progressed, but the second child-loaded definition
    had already received wrong class initialization/key material. JCP-6E3 must
    repair this class-loading-state invariant before JCP-6E2 can be freshly
    validated and committed.
  - Scope: update CFF class-integrity global state initialization so its
    mutable global/node/order state is keyed by the live runtime class-loading
    universe of the `lookupClass()` argument, not by the selected helper host's
    static field alone and not by only the transformed binary owner hash. The
    helper may still live in a shared host class, but duplicate transformed
    application classes with the same binary name loaded by different
    class-loaders must not mutate the same class-integrity state instance. The
    runtime state key must use live JVM class loading state such as the actual
    lookup class or class loader identity, while preserving CFF class-order
    binding, class-code hash binding, ticket issue/consume behavior, dynamic
    method-entry key propagation, and fail-closed behavior.
  - Required evidence before editing:
    after the current JCP-6E2 edits, the focused child-loader regression command
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.childLoadedReflectiveConstructorUsesRuntimeCarrierTableUnderFullProfile --no-daemon`
    compiles and runs but fails as `AssertionError: 8:9:-860286587`. A runtime
    probe against the freshly generated focused obfuscated jar prints
    `helper=class a.a loader=jdk.internal.loader.ClassLoaders$AppClassLoader`,
    `same helper carrier=true`, two distinct child loaders for `class a.c`,
    `first state=7`, `second state=0`, and different class key-table words
    (`first table=-57484075`, `second table=-174373262`). Static bytecode for
    child-loaded `a.c.<clinit>` calls
    `MethodHandles.lookup().lookupClass()` and then shared helper
    `a/a.i:(IJJLjava/lang/Class;JJ)J`; `javap -p a.a` shows the helper host
    owns public static `Object b`, the single class-integrity carrier. The
    original focused source has `private static int state = 7`, so `state=0`
    before any second-loader `bump()` call proves class initialization/key
    material is wrong before reflective method dispatch.
  - Validation command or runtime target:
    rerun the focused child-loader regression above; rerun
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`;
    regenerate `test-jars/full.jar` with
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-obf.jar`
    without `quick`; and run
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.jvm.classloader-isolation --verbose`.
  - Completion criteria: duplicate binary-name classes loaded by independent
    child-first class loaders each receive correct static initialization and
    class key material; focused reflected constructor/method regression passes;
    fresh full-profile `full.jar` passes `features.jvm.classloader-isolation`;
    the generated class-integrity helper still binds root computation to live
    `lookupClass`, class code hash, required order bloom, and live mutable
    state; no owner/name special case, static reset, original-bytecode fallback,
    skip behavior, descriptor preservation, or CFF coverage reduction is
    introduced.
  - Plan-intake review: subagent `019e76be-90a8-7ff3-be79-c5531a1bc2e7`
    returned PASS. The review found the task generic, evidence-backed,
    dependency-ordered, validation-targeted, and scoped away from fallback or
    CFF-coverage reduction.
  - Fresh implementation validation evidence after the JCP-6E2/JCP-6E3 edits:
    the focused command
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest.childLoadedReflectiveConstructorUsesRuntimeCarrierTableUnderFullProfile --no-daemon`
    passed. A runtime probe against the focused obfuscated jar printed the
    shared helper host as the app loader, two distinct child loaders for
    `class a.c`, `first state=7`, `second state=7`, and equal class key-table
    words, proving duplicate child-loaded binary names no longer share the
    same mutable class-integrity state. The broader
    `JvmMethodParameterObfuscationIntegrationTest` command also passed. After
    the independent JCP-6E4 generation-size and CFF carrier-field access
    blockers were repaired, fresh no-quick `full.jar` regeneration passed and
    the freshly generated artifact passed `features.jvm.classloader-isolation`,
    proving duplicate binary-name child-loaded classes receive correct static
    initialization and class key material under the declared full profile.
- [x] JCP-6E4 baseline/repair subtask: repair the current full-profile CFF
  output size blocker without weakening CFF.
  - Dependency/order: JCP-6E2 and JCP-6E3 cannot be accepted against
    `features.jvm.classloader-isolation` until `test-jars/full.jar` can be
    regenerated under the same full JVM profile. This is a generation blocker,
    not a runtime feature failure, so it must be diagnosed and repaired before
    re-running the classloader-isolation target.
  - Scope: identify and repair the generic transform path that makes a
    full-profile transformed method exceed the JVM 65535-byte code limit during
    CFF class-code finalization. The repair may move equivalent protected
    generated logic or shared keyed material out of the oversized method, but
    must preserve CFF block construction, block boundaries, block count,
    dynamic dispatcher semantics, hidden-key propagation, packed carriers,
    constant/string/indy protection, and fail-closed behavior. The repair must
    not special-case `full.jar`, `a/p`, `pa`, a feature name, a benchmark row,
    or a mapped original class/method.
  - Required evidence before editing production code: fresh no-quick full
    obfuscation of `test-jars/full.jar` with
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-obf.jar`
    exits 1 in the CFF output finalizer after coverage reports
    `methodParameterObfuscation appliedFull=687`, `controlFlowFlattening
    appliedFull=1037`, `invokeDynamic appliedFull=657`,
    `constantObfuscation appliedFull=316`, and `stringObfuscation
    appliedFull=425`. The same run reports
    `Relocated large CFF helper sets: hosts=117 methods=6653`, then fails
    previewing `a/p` with
    `MethodTooLargeException: Method too large: a/p.pa
    ([Ljava/lang/Object;)V`. Largest estimates are
    `pa([Ljava/lang/Object;)V estimatedCodeBytes=86282`,
    `e([Ljava/lang/Object;J)[B estimatedCodeBytes=17168`,
    `jjb([Ljava/lang/Object;JIIIIII)Ljava/lang/String; estimatedCodeBytes=11069`,
    `<clinit>()V estimatedCodeBytes=8507`, and smaller methods below 3000
    bytes. No `quick` or `--quick` argument was used.
  - Diagnostic evidence before the repair edit: the oversized method maps to
    original `com/java21test/features/ClassLoaderIsolationFeatureTest.run`;
    after method-parameter obfuscation it is packed as
    `a/p.pa([Ljava/lang/Object;)V`. A fresh CFF-only no-MPO diagnostic
    obfuscation succeeded, while a fresh CFF+MPO-only diagnostic obfuscation
    failed in the same output finalizer with
    `a/p.pa([Ljava/lang/Object;)V estimatedCodeBytes=71577` and
    `Relocated large CFF helper sets: hosts=75 methods=4070`. This isolates
    the first size root cause to method-parameter reflective runtime-carrier
    table discovery being inlined into a method that reflectively constructs
    and invokes child-loaded classes. Full indy/string/constant protection
    increases the same method to 86282 estimated bytes but is not the first
    failing surface.
  - Implementation evidence: reflective carrier construction now emits one
    generated per-owner helper method for runtime carrier table discovery
    instead of inlining the full `Class.getDeclaredFields()` synthetic
    `Object[]` table scan at every reflective callsite. The helper is selected
    by live `Class` / `Member` / receiver data, preserves the existing
    fail-closed `SecurityException` behavior when no runtime carrier table is
    found, and keeps the generated table-scan bytecode out of the CFF-packed
    application method. The same implementation keeps method-call receiver
    fallback only for live non-static method targets, so interface/abstract
    declaring classes still resolve through the actual receiver class without
    using a static parent table. Generated reflection member filters skip
    generated nodes so the helper can see the generated carrier fields while
    ordinary application reflection filtering remains active. Interface-owned
    runtime carrier-table helpers are emitted as `public static synthetic` and
    invoked with the interface owner flag, while class-owned helpers remain
    `private static synthetic`, preserving Java 8 interface ABI.
  - Dependent CFF carrier-field access evidence: after excluding the initial
    unrecorded CFF object-table visibility hunks from a staged-only validation
    worktree, the focused full-profile `full.jar`
    `features.jvm.classloader-isolation` run failed with
    `IllegalAccessError: class a.aa tried to access private field a.ba.a`. The
    generated protected CFF code therefore contains legitimate cross-class
    reads of synthetic static `Object[]` carrier fields, and leaving every
    class carrier private violates JVM access checks once helpers are relocated
    across owners.
  - Dependent CFF carrier-field access implementation: CFF carrier fields now
    keep the narrow default shape for their owner (`private static final
    synthetic` on classes, `public static final synthetic` on interfaces), then
    a post-restore scan relaxes only actually referenced cross-class synthetic
    static `Object[]` carrier fields. Same-package references are relaxed to
    package-private; cross-package references become public. This avoids the
    rejected blanket-public visibility change while preserving the generated
    protected dataflow path that caused the verifier/runtime access failure.
  - Fresh implementation validation evidence: after rebuilding
    `:neko-cli:installDist`, the focused child-loader regression command
    passed, the full
    `JvmMethodParameterObfuscationIntegrationTest` suite passed, including the
    Java 8 interface-default reflective-invoke ABI regression. Fresh
    no-quick full-profile `test-jars/full.jar` regeneration with
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp neko-cli/build/install/neko-cli/bin/neko-cli obfuscate -c test-jars/full-jvm-obf.yml -i test-jars/full.jar -o build/test-jvm-full-obf-perf/full-obf.jar`
    passed, writing 324 classes and 9 resources. The freshly generated artifact
    passed
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp java -XX:-UsePerfData -XX:+ShowCodeDetailsInExceptionMessages -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.jvm.classloader-isolation --verbose`
    with `PASS features.jvm.classloader-isolation 25.755 ms` after the
    interface ABI and targeted CFF carrier-field access repairs.
  - Static artifact evidence: the fresh map records
    `CLASS com/java21test/features/ClassLoaderIsolationFeatureTest -> a/p` and
    a generated helper mapping
    `METHOD a/p.__neko_mpo_rtab$0(Ljava/lang/Object;Ljava/lang/Object;I)[Ljava/lang/Object; -> a`.
    `javap` on the fresh obfuscated artifact shows the packed
    `pa([Ljava/lang/Object;)V` method is emitted with `stack=16, locals=440`,
    so the JVM code-size limit is no longer exceeded.
  - Plan-intake review: subagent `019e76d3-420c-7d02-b85a-8ce868b659bf`
    returned PASS. The review found the JCP-6E4 subtask generic,
    evidence-backed, bounded to the current generation-size invariant, and
    compliant with the no-fallback and no-CFF-weakening constraints.
  - First implementation review: subagent
    `019e76df-96aa-7463-931e-ea2836f6e6dd` returned FAIL because the runtime
    carrier table helper did not handle interface-host ABI generically and
    because unrelated CFF object-table visibility hunks must not be included in
    the checkpoint. The interface-host ABI issue was repaired and revalidated.
    A later staged-only validation with those visibility hunks removed proved a
    real access invariant failure, so the checkpoint now includes only the
    evidence-backed targeted carrier-field access repair described above, not
    the rejected blanket visibility change.
  - Validation command or runtime target: rerun the focused method-parameter
    child-loader regression, rerun the full
    `JvmMethodParameterObfuscationIntegrationTest`, regenerate
    `test-jars/full.jar` with `test-jars/full-jvm-obf.yml` without `quick`,
    and then run
    `java -jar build/test-jvm-full-obf-perf/full-obf.jar --only features --include features.jvm.classloader-isolation --verbose`.
  - Completion criteria: fresh no-quick `full.jar` regeneration no longer
    fails with `MethodTooLargeException`; the classloader-isolation target
    passes on the freshly generated artifact; the focused child-loader
    regression remains green; generated bytecode does not introduce original
    bytecode fallback, skip behavior, static key exposure, descriptor-only key
    recomputation, reduced CFF coverage, or sample-specific checks.
  - Post-checkpoint full-run evidence: the same staged-only no-quick
    `full-obf.jar --verbose` run advanced past `features.jvm.classloader-
    isolation` and most feature targets, proving this checkpoint removes the
    selected class-loader blocker. The full run is not yet green: it failed
    `features.jvm.gc.collector-subprocess` with
    `AssertionError: ZGC probe output missing success marker`, failed
    `features.jvm.gc.collector-compat-matrix` with
    `AssertionError: ZGC output missing success marker`, and failed
    `features.jvm.nio-network-io` with `SocketException: Unix domain path too
    long` under the long staged worktree path. It then emitted perf rows
    through `perf.crypto.rsa-oaep` and produced no further output for more than
    10 minutes; the hanging validation processes were terminated after 636
    seconds elapsed. JCP-6E remains open for these next blockers; this evidence
    does not invalidate the scoped JCP-6E2/JCP-6E3/JCP-6E4 acceptance.
- [x] JCP-6E5 baseline/repair subtask: restore ZGC internal probe success
  markers under full JVM obfuscation.
  - Dependency/order: JCP-6E2/JCP-6E3/JCP-6E4 are complete and committed, and
    the fresh full-profile artifact now reaches the GC subprocess feature
    tests. This blocker is independent from classloader-isolation and must be
    repaired before a full unfiltered `full.jar` run can be accepted.
  - Scope: diagnose and repair the generic transform/runtime invariant that
    makes internal GC probe subprocesses running under ZGC exit successfully
    without printing the required `GC_PROBE_OK` / `GC_COMPAT_OK` markers. The
    repair must preserve full JVM obfuscation, internal argument parsing,
    stdout marker emission, string/constant/CFF/key protection, and GC
    compatibility. It must not skip ZGC, skip subprocess probes, preserve
    plaintext marker strings as a fallback, special-case probe class names, or
    reduce transform coverage for GC-related classes.
  - Required evidence before editing production code:
    - Fresh no-quick full-profile artifact
      `build/tmp/jcp6e-staged-worktree/build/test-jvm-full-obf-perf/full-obf.jar`
      fails `--only features --include features.jvm.gc.collector-subprocess
      --verbose` with `AssertionError: ZGC probe output missing success marker`.
    - The same artifact fails `--only features --include
      features.jvm.gc.collector-compat-matrix --verbose` with
      `AssertionError: ZGC output missing success marker`.
    - Direct internal probe commands return exit code 0 but emit no marker:
      `java -XX:+UseZGC -jar .../full-obf.jar --internal-gc-probe ZGC` prints
      no `GC_PROBE_OK`, and
      `java -XX:+UseZGC -jar .../full-obf.jar --internal-gc-compat-probe ZGC`
      prints no `GC_COMPAT_OK`. With `-Xlog:gc`, the same obfuscated probe only
      prints the VM line `Using The Z Garbage Collector`.
    - Direct G1 internal probe on the same obfuscated artifact prints
      `GC_PROBE_OK collector=G1 ...`, proving the internal probe route and jar
      path are not globally broken.
    - Original `test-jars/full.jar` passes both GC focused targets with
      `PASS features.jvm.gc.collector-subprocess 169.783 ms` and
      `PASS features.jvm.gc.collector-compat-matrix 753.883 ms`; original
      direct ZGC internal probe prints `GC_PROBE_OK collector=ZGC ...`.
    - Original bytecode contract:
      `Main.main` checks `--internal-gc-probe` and
      `--internal-gc-compat-probe` before normal runner parsing, then exits
      with the return value of `GcProbe.run` or `GcCompatibilityProbe.run`.
      `GcProbe.run` catches `Throwable`, prints failures to `System.out`, and
      otherwise emits `GC_PROBE_OK ...`; `GcCompatibilityProbe.run` similarly
      emits `GC_COMPAT_OK ...`.
  - Validation command or runtime target: regenerate `test-jars/full.jar` with
    `test-jars/full-jvm-obf.yml` without `quick`; run direct obfuscated
    internal probes under G1 and ZGC; run focused
    `features.jvm.gc.collector-subprocess` and
    `features.jvm.gc.collector-compat-matrix`; then rerun the full unfiltered
    `full-obf.jar --verbose` until it advances past these two targets.
  - Completion criteria: fresh obfuscated ZGC internal probes emit the expected
    success markers; both focused GC targets pass; unsupported collector
    handling still fail-closes or skips only when the JVM reports an unsupported
    collector; no ZGC skip, subprocess skip, marker fallback, plaintext marker
    exposure, original-bytecode fallback, or transform coverage reduction is
    introduced.
  - Diagnostic isolation evidence: fresh ablation artifacts showed the failure
    is already present with renamer+CFF only. `cff-no-key-no-mpo.jar` printed
    `GC_PROBE_OK ...` and `GC_COMPAT_OK ...` under G1, but under actual
    `-XX:+UseZGC` it exited 0 with no marker. Class-load logging for the same
    jar showed G1 loaded `a.a` and `a.sa`, while ZGC loaded only `a.a`, proving
    the internal probe target was never reached and the failing surface was
    CFF dispatch/root state in `Main`, not the probe class, collector-name
    string, indy, constant, string, MPO, or keyDispatch.
  - Root-cause evidence: a repo-local `Unsafe` layout probe showed G1/Serial
    report `Object[]` scale 4 and compact `java.lang.Class` reference-field
    offsets, while ZGC reports `Object[]` scale 8 and widened reference-field
    offsets. CFF class-integrity expected roots had included this build-time
    reference layout fingerprint, so ZGC computed a different live root and
    selected a legal poisoned route before the internal probe call. Primitive
    array base/scale, primitive value probes, address size, field names, field
    types, and primitive field offsets remained stable across the measured
    collectors.
  - Implementation evidence: class-integrity layout fingerprinting now keeps
    portable live JVM material only: address size, `Object[]` base offset,
    primitive array base/scale, primitive array value probes, `Class` field
    names/types, and primitive field offsets. It no longer binds expected CFF
    roots to GC/reference-compression-dependent `Object[]` reference scale or
    reference-field object offsets. The class root still binds to live
    `lookupClass`, class loader context, class code hash, class order bloom,
    mutable node/global state, and portable live VM metadata; no ZGC skip,
    marker fallback, original-bytecode fallback, or CFF coverage reduction was
    introduced.
  - Fresh validation evidence: the focused regression
    `ControlFlowFlatteningAlgebraicAuditTest.cffClassIntegrityRootSurvivesZgcReferenceLayout`
    passed, then the full `ControlFlowFlatteningAlgebraicAuditTest` and
    `JvmMethodParameterObfuscationIntegrationTest` suites passed. A fresh
    CFF-only `full.jar` ablation regenerated after the repair printed
    `GC_PROBE_OK ...` under `-XX:+UseZGC -Xlog:gc` and also printed
    `GC_COMPAT_OK ...` under ZGC. Fresh no-quick full-profile
    `test-jars/full.jar` regeneration with `test-jars/full-jvm-obf.yml`
    succeeded, writing 324 classes and 9 resources. Direct full-profile
    internal probes printed `GC_PROBE_OK ...` under G1, `GC_PROBE_OK ...` under
    ZGC with a visible `System.gc()` log line, and `GC_COMPAT_OK ...` under
    ZGC. Focused full-profile runtime targets passed with
    `PASS features.jvm.gc.collector-subprocess 412.511 ms` and
    `PASS features.jvm.gc.collector-compat-matrix 1,229.877 ms`. A no-quick
    unfiltered `full-obf.jar --verbose` run then advanced past both GC targets
    with `PASS features.jvm.gc.collector-subprocess 364.078 ms` and
    `PASS features.jvm.gc.collector-compat-matrix 1,202.313 ms` before timing
    out at the already recorded later `perf.crypto.xor` blocker.
- [ ] JCP-6E6 baseline/repair subtask: restore full-profile
  `perf.crypto.xor` completion.
  - Dependency/order: JCP-6E5 must run before this subtask when validating a
    full unfiltered `full.jar` run, because the full runner reaches the GC
    subprocess failures before the perf suite. The focused perf command may be
    used independently for diagnosis, but final acceptance must use a freshly
    regenerated full-profile artifact after the GC blocker is fixed.
  - Scope: diagnose and repair the generic transform/runtime invariant that
    makes the obfuscated `perf.crypto.xor` hot byte-array loop fail to finish
    within 240 seconds under full JVM obfuscation. The repair must preserve CFF
    block coverage, method-parameter packing, invokedynamic, string/constant
    protection, dynamic key propagation, and array/loop semantics. It must not
    special-case the XOR benchmark, byte-array loops, mapped class/method
    names, benchmark ids, or reduce CFF granularity.
  - Required evidence before editing production code:
    - The post-JCP-6E no-quick full unfiltered `full-obf.jar --verbose` run
      emits perf rows through `perf.crypto.rsa-oaep`, then produces no further
      output for more than 10 minutes and must be terminated.
    - Fresh focused obfuscated run
      `timeout 240s java -jar .../full-obf.jar --only perf --include
      perf.crypto.xor --verbose` exits 124 with no `PERF perf.crypto.xor`
      output.
    - Original `test-jars/full.jar --only perf --include perf.crypto.xor
      --verbose` passes with `PERF perf.crypto.xor measure=339.404 ms`.
    - Original bytecode for `CryptoXorPerfTest` uses a pure byte-array XOR
      loop over `byte[]` inputs and `PerfSupport.measure`; no I/O, subprocess,
      or external dependency participates in the measured path.
    - Fresh JCP-6E6 focused runs on 2026-05-31 refreshed the baseline with
      repository-local tmp dirs: original `test-jars/full.jar --only perf
      --include perf.crypto.xor --verbose` passed with
      `PERF perf.crypto.xor measure=355.655 ms`, while the freshly regenerated
      full-profile `build/test-jvm-full-obf-perf/full-obf.jar` exited 124 after
      240 seconds and printed only the JVM startup line.
    - Fresh JCP-6E6 ablations regenerated from current sources isolate the
      bad path before invokeDynamic/string/constant-specific runtime:
      `renamer+keyDispatch+MPO+CFF` completed but reported
      `PERF perf.crypto.xor measure=20,048.082 ms`; `full-no-const-string`
      completed with `23,619.108 ms`; `full-no-indy` exited 124 after 240
      seconds with no PERF row. `full-no-cff` failed closed during generation
      with `constantObfuscation requires CFF class key table`, so it is not a
      valid runtime comparison.
    - Fresh JFR on the `renamer+keyDispatch+MPO+CFF` ablation reported
      `a.ef.te(Object[], long)` at 3,663 samples / 91.35% and the shared CFF
      int helper `a.zi.i(...)` at 339 samples / 8.45%. This proves the focused
      blocker is the transformed XOR loop body itself, not the runner,
      reporting, I/O, or a string/constant decode-only path.
    - Fresh `-XX:+PrintCompilation -XX:+PrintInlining` on the same ablation
      shows `a.ef.te` reaches C2, but its dispatcher bytecode still contains
      repeated `emitOddIntInverse` selector decode inside the same small-token
      dispatch entry and the outer lambda reports `a.ef::te (6828 bytes) hot
      method too big` for inlining. The first falsifiable repair candidate was
      therefore the generic small-token dispatcher path: derive the data-bound
      raw pc and selector once per dispatch entry, then compare the cached
      selector across cases. This preserved the same encoded pc storage, live
      data multiplier, guard/path/block token mask, fake/poison routing, block
      coverage, and key propagation; it did not change block construction or
      introduce a static selector.
    - Iteration result: the single-decode small-token selector edit passed the
      full `ControlFlowFlatteningAlgebraicAuditTest`, then a freshly
      regenerated `renamer+keyDispatch+MPO+CFF` full.jar ablation reported
      `PERF perf.crypto.xor measure=21,456.037 ms`, worse than the immediate
      pre-edit `20,048.082 ms` baseline. The edit was reverted before commit;
      JCP-6E6 must select a different generic CFF hot-path repair.
    - Iteration result: forcing compact transition wrappers for every outlined
      transition also passed the CFF algebraic audit, but the fresh
      `renamer+keyDispatch+MPO+CFF` full.jar ablation reported
      `PERF perf.crypto.xor measure=22,142.672 ms`, again worse than the
      20,048.082 ms baseline. That edit was reverted before commit; moving
      transition material behind per-edge wrappers is not the current repair.
    - Iteration result: reducing JIT-budget small-token dispatch width from 2
      to 1 passed the CFF audit and improved the same ablation only slightly to
      `19,832.013 ms`; reducing it to 0 regressed to `21,192.169 ms`. Reducing
      the odd-inverse Newton decode from five iterations to four also passed
      the CFF audit but regressed to `21,140.450 ms`. These edits were reverted
      before commit; dispatch-shape and inverse micro-tuning are insufficient
      for the JCP-6E6 blocker.
    - Fresh JCP-6E6 `full-no-indy` JFR on the focused XOR run exited 124 after
      120 seconds and reported `a.ef.te(Object[], long)` at 11,554 samples /
      99.63%. This proves the full-profile timeout still executes the
      transformed hot loop body rather than hanging in class initialization,
      string table setup, runner output, or invokedynamic bootstrap.
    - Fresh bytecode topology comparison of `a.ef.te` shows
      `full-cff-mpo-only.jar` has a final instruction offset of 6,827 bytes,
      while `full-no-indy.jar` grows the same method to 11,854 bytes. The
      selected `te` instruction-pattern count rises from 714 to 1,242, and the
      simple byte-array XOR mask sequence around `bastore` expands from
      `arraylength; iconst_1; isub; iand; baload; ixor` into hundreds of
      runtime-derived integer fragments before the same `baload; ixor;
      bastore`. This identifies the next repair surface as generic
      constant/string numeric-material expansion in loop-carried code, not a
      CFF dispatcher-only invariant.
  - Validation command or runtime target: after the concrete repair is chosen,
    run the focused transformed perf target with a hard timeout, rerun
    relevant CFF/MPO/string/constant regression tests for the touched path,
    regenerate `test-jars/full.jar` without `quick`, and rerun the full
    unfiltered `full-obf.jar --verbose`.
  - Completion criteria: fresh full-profile `perf.crypto.xor` emits a PERF row
    and completes within the runner's normal perf envelope; the full unfiltered
    run advances past `perf.crypto.xor`; no static key recomputation,
    descriptor-only keying, fallback, original-bytecode rescue, benchmark
    special-case, CFF thinning, or transform coverage reduction is introduced.
- [x] JCP-6E full-run environment classification: classify the staged-worktree
  Unix-domain socket failure.
  - Scope: determine whether `features.jvm.nio-network-io` is a transform
    semantic failure or a validation-path artifact before creating a repair
    task.
  - Evidence: the full unfiltered run in the long staged worktree failed
    `features.jvm.nio-network-io` with `SocketException: Unix domain path too
    long`. Re-running the same obfuscated artifact with a shorter
    repository-local `java.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/t`
    passed the focused target with
    `PASS features.jvm.nio-network-io 18.065 ms`.
  - Completion criteria: do not create a production repair for this failure
    unless it reproduces under a non-pathological repository-local runtime path.

### [ ] JCP-7: Reduce Full Constant/String Hot-Path Runtime Cost

- Scope: optimize protected numeric/string decode runtime and size overhead
  responsible for the `TEST full` versus `full-no-const-string` gap and for the
  `test21.jar` full-profile `main` method-size failure. Candidate repairs must
  preserve derived material and semantic entanglement, but may hoist repeated
  live-base material within a method, share already-derived primitive locals,
  compact string decode callsite setup, or replace expensive repeated decode
  helpers with equivalent monomorphic keyed code.
- Required evidence before editing: fresh profiler or bytecode/topology proof
  showing the exact full constant/string hot path in `test-obf.jar`, not just
  the ablation delta.
  - Fresh evidence before the first JCP-7 repair:
  - The JCP-6E6 full-profile focused `perf.crypto.xor` run and the
    `full-no-indy` ablation both time out, while `renamer+keyDispatch+MPO+CFF`
    completes at `20,048.082 ms` and `full-no-const-string` completes at
    `23,619.108 ms`. This isolates a base CFF cost plus a larger
    constant/string numeric-material multiplier.
  - The `full-no-indy` JFR reports `a.ef.te(Object[], long)` at 99.63% of
    samples, and bytecode topology proves the hot `te` body grows from 6,827
    bytes to 11,854 bytes when constant/string protection is enabled. The hot
    XOR store retains the same byte-array operation but inserts large
    runtime-derived numeric fragments for loop-local constants such as the
    array index mask.
  - Source inspection shows `JvmConstantObfuscationPass` already computes
    `loopRegionInstructions(mn)` but does not consume that set when choosing
    compact numeric decode. The first JCP-7 repair will use that existing
    generic loop topology signal to route loop-region numeric sites through the
    compact keyed numeric decode path, preserving per-site seeds, live CFF/data
    base binding, class-key-table material, and full coverage while reducing
    hot-loop bytecode expansion. It will not skip constants, preserve
    plaintext values, special-case arrays, or reduce CFF block coverage.
- First repair validation:
  - Implemented the generic loop-region compact-selection rule in
    `JvmConstantObfuscationPass` by consuming the existing
    `loopRegionInstructions(mn)` result for numeric and primitive-array
    constant material. The change does not inspect benchmark names, class
    names, method names, owners, descriptors, array element types, or constants
    values.
  - Focused Gradle validation passed:
    `JvmConstantObfuscationIntegrationTest`,
    `JvmStringObfuscationIntegrationTest`, and
    `ControlFlowFlatteningAlgebraicAuditTest`.
  - Fresh `full-no-indy` ablation regenerated from current sources wrote 316
    classes. The hot `a.ef.te` bytecode maximum offset dropped from 11,854 to
    7,400 and the selected hot-path instruction-pattern count dropped from
    1,242 to 774. The focused XOR run no longer timed out and reported
    `PERF perf.crypto.xor measure=42,243.871 ms`.
  - Fresh full-profile regeneration with `test-jars/full-jvm-obf.yml` wrote
    324 classes with `invokeDynamic appliedFull=657`,
    `constantObfuscation appliedFull=316`, and `stringObfuscation
    appliedFull=425`. The focused full-profile XOR run no longer timed out and
    reported `PERF perf.crypto.xor measure=42,231.689 ms`.
  - JCP-7 remains open: this repair restores completion and reduces bytecode
    expansion, but it does not meet the requested final performance thresholds.
    The next repair must target the remaining hot-loop decode/runtime cost
    without reducing constant, string, invokedynamic, CFF, or key-dispatch
    coverage.
- Second repair evidence before editing:
  - Fresh post-loopcompact JFR on the full-profile focused
    `perf.crypto.xor` run still reports `a.ef.te(Object[], long)` as the
    dominant method with 5,598 samples / 96.32%, while the focused run
    completes at `PERF perf.crypto.xor measure=42,231.689 ms`.
  - Fresh bytecode extraction of the current full-profile `a.ef.te` shows
    final method offset 7,767, 55 `invokestatic` instructions, 6 compact
    numeric base helper calls, and 3 protected numeric helper calls. The
    CFF/MPO-only comparison method has final offset 6,740, 44 `invokestatic`
    instructions, and no compact numeric base/protected helper calls.
  - The XOR-store window in the full-profile method contains repeated
    `__neko_num_base(IIIIII)J` calls immediately before
    `__neko_num_ip(IIIII)I`; the CFF/MPO-only window performs the same
    byte-array load/xor/store without numeric helper calls. This identifies
    the next generic repair as compact numeric base refresh cost, not runner,
    invokedynamic bootstrap, CFF dispatcher selection, or string table setup.
  - The second JCP-7 repair will keep the compact numeric material keyed by
    live CFF state and per-site seeds, but will cache the raw live constant
    base in a method local alongside the encoded base. Generated bytecode will
    reuse the cached raw base only when the live CFF data local still matches
    the cached data local, and will call the existing compact base helper and
    update both cached forms when the data changes. This preserves data-flow
    binding while removing redundant same-state base refresh helper calls in
    hot loops.
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

### [ ] JCP-10: Final Full JVM Regeneration And Acceptance

- Scope: regenerate the six originally requested test jars plus
  `test-jars/full.jar` with full JVM obfuscation into
  `/mnt/d/Code/Reverse/NekoOBF/m` using `originname-obf.jar`, then run the
  final CTF hardening, performance, and Java 21 fixture smoke audits.
- Required evidence: fresh output file list, `jar tf` readability checks,
  CTF reflection/direct-entry negative checks, focused Gradle XML, and direct
  performance runs.
- Validation command or runtime target:
  full CLI regeneration for `crackme.jar`, `ctf.jar`, `evaluator.jar`,
  `snake.jar`, `test.jar`, `test21.jar`, and `full.jar`; `jar tf` on every
  output; direct runs for `test-obf.jar`, `test21-obf.jar`, and
  `full-obf.jar`.
- Completion criteria: all requested outputs exist and are readable; CTF plaintext
  table/direct-entry exploit no longer works on the regenerated artifact;
  performance thresholds are met; `full-obf.jar` completes its runnable smoke
  target or records a generic evidence-backed blocker for repair; final plan
  review returns PASS.
