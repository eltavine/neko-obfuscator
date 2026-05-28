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

### [ ] JCP-2: Bind Packed Entry Carriers To Internal Callsites

- Scope: extend method-parameter packing/key dispatch so internal packed
  `Object[]` call carriers contain per-callsite hidden attestation material
  derived from live caller key/CFF data. Callees must mix and validate that
  material before accepting hidden key transfer.
- Required evidence before editing: bytecode proof of the current direct-entry
  path (`CallCore` succeeds with copied `Object[]` plus hidden long) and source
  path evidence for carrier creation/unpack in
  `JvmMethodParameterObfuscationPass`.
- Validation command or runtime target:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest`
  plus regenerated `ctf-obf.jar` direct-entry negative test.
- Completion criteria: internal application callsites still run; direct
  external wrapper calls with copied hidden long fail closed; generated carrier
  slots are not direct constants and are bound to live caller material.

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
