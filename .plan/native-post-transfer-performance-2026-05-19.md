# Native Post-Transfer Performance and Compile-Time Execution Plan - 2026-05-19

## Scope

Continue the current native work after the native transfer/JNI-removal stages by
executing one evidence-backed optimization at a time across two tracks:

- runtime/native-transfer performance: `native-gentle-flamingo-todo.md` Stage 4
  and `native-performance-optimization-todo.md` P3+;
- native compile-time/code-shape performance: continuation after
  `.plan/native-compile-parallelization-2026-05-17.md` P41.

This document is the matching `.plan/` audit queue for the current work. It does
not replace the existing source plans; each implementation row below must update
the source plan that owns the changed path before it can be considered complete.

## Status Legend

- `[ ]` not implemented / not freshly verified
- `[-]` actively in progress only while that exact row is being worked
- `[x]` implemented, freshly regenerated, runtime-verified, inspected, and
  committed where repository policy permits
- `[rejected]` measured and reverted or not retained because acceptance failed

## Non-Negotiable Constraints

- Do not implement or optimize for a special jar, benchmark, class, method,
  owner, descriptor, crash site, or log string.
- Do not weaken JVM ABI behavior, obfuscation coverage, CFF/key-flow semantics,
  native coverage, GC compatibility, or JNI-removal goals.
- Do not add JNI fallback, JVMTI, skip-on-error success, original-bytecode
  fallback, Java helper layers, or new bootstrap behavior.
- Do not reuse stale jars, stale generated C, stale manifests, unit-only passes,
  or compile-only results as runtime proof.
- Use a repository-local temporary directory such as `build/native-run-tmp` for
  runtime commands; do not use `/tmp`.
- Repository `./gradlew` use requires explicit user permission before running.

## Existing Plan State Reconciled Before Implementation

- `native-gentle-flamingo-todo.md` Stage 4 remains open. T4.0 is first in the
  Stage 4 ordering because `neko_exception_check_resolve_env_offset` is recorded
  as a structural post-transfer performance prerequisite before the wider T4.14
  performance gate.
- `native-performance-optimization-todo.md` has P0/P1 complete. P2 is blocked by
  GC strict prerequisites. P3 is partially implemented by
  `.plan/native-compile-parallelization-2026-05-17.md` P25 but still needs the
  broader dependency-jar stack-trace fixture and GC strict compatibility gate.
  P4-P15 remain open.
- `.plan/native-compile-parallelization-2026-05-17.md` has accepted work through
  P41. Rejected rows P27/P29/P33/P34/P36/P37/P40 must not be retried in the same
  shape without new root-cause evidence explaining the rejection.
- `.plan/native-full-validation.md` V1-V3 remain open as a final validation
  sweep, and `.plan/native-full-repair.md` R2-R5 remain open for full native-only
  repair/acceptance.

## Execution Rows

### [x] NPT-0: Reconcile current native performance and compile-time plan state

- Scope: locate the active runtime performance, native transfer, compile-time,
  full-validation, and full-repair plans before changing code.
- Required evidence: exact file paths, open rows, validation targets, rejected
  shapes, and current generated-artifact locations.
- Validation command or runtime target: read-only repository exploration; no
  Gradle or runtime command.
- Completion criteria: this document records the current source-plan state and
  the next executable rows without inventing a bottleneck or changing runtime
  behavior.
- Evidence: completed read-only exploration of
  `native-performance-optimization-todo.md`, `native-gentle-flamingo-todo.md`,
  `.plan/native-compile-parallelization-2026-05-17.md`,
  `.plan/native-full-validation.md`, and `.plan/native-full-repair.md`.

### [x] NPT-1: Capture fresh post-P41 baseline before the next optimization

- Scope: regenerate and measure the current accepted source state after P41 so
  both runtime and compile-time work starts from fresh artifacts, not the stale
  P41 evidence embedded in the plan.
- Required evidence: git revision/status, focused native audit output, fresh
  native-only generation rows for TEST/test21/SnakeGame/evaluator, per-source
  compile/link elapsed rows, generated source counts, packaged library sizes,
  direct runtime stdout/stderr for TEST/test21/evaluator/SnakeGame smoke, parsed
  TEST Calc and test21 Platform/Virtual/Seq/Parallel/VThreads timings, newest
  `hs_err_pid*.log` scan, and generated-C forbidden-marker inspection.
- Validation command or runtime target: after explicit permission to use
  `./gradlew`, run the focused native audit/tests needed to regenerate native
  artifacts, then run native-only generation through `.plan/native-only-full.yml`
  and direct jar commands with `-Djava.io.tmpdir=build/native-run-tmp`.
- Completion criteria: regenerated artifacts report `translated>0 rejected=0`,
  no native compilation fallback, no skip-on-error success, no original-bytecode
  fallback, no executable forbidden JNI/JVMTI markers, no fatal JVM/runtime
  errors, and the next runtime plus compile-time rows are selected from the
  fresh measurements.
- Partial evidence: focused Gradle audit passed after explicit `./gradlew`
  permission with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Exit code `0`; Gradle reported
  `BUILD SUCCESSFUL in 8s` with 19 actionable tasks. This is only the focused
  audit checkpoint; NPT-1 remains open until fresh four-jar native-only
  generation, direct runtime runs, manifest/timing capture, `hs_err` scan, and
  forbidden-marker inspection are complete.
- Completion evidence 2026-05-20 at git `4f537f98685afee2debcea9b6e1eb0b2d0a9017a`
  with clean worktree before generation: focused Gradle audit reran after
  explicit permission and passed with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL in 2s`, 19
  up-to-date tasks). Fresh `.plan/native-only-full.yml` CLI generation wrote
  `build/native-post-transfer-baseline/{TEST,obfusjack,SnakeGame,evaluator}-native-only.jar`;
  generated counts / translated rows were TEST 67 C files, `translated=49
  rejected=0`; obfusjack 113 C files, `translated=93 rejected=0`; SnakeGame 35
  C files, `translated=18 rejected=0`; evaluator 143 C files, `translated=122
  rejected=0`. Direct runtime logs under `build/native-post-transfer-baseline/`
  had no fatal markers: TEST x5 Calc = 34/39/34/36/35 ms; obfusjack x5
  completed with Seq = 29/28/29/28/29 ms, Parallel = 3/3/3/3/3 ms, VThreads =
  5/7/5/6/6 ms, Platform = 813/771/767/894/865 ms, Virtual =
  793/812/814/892/932 ms; SnakeGame exited with expected headless behavior;
  evaluator exited 0. No `hs_err_pid*.log` was created under
  `build/native-run-tmp`, `build/native-post-transfer-baseline`, or
  `build/neko-native-work`; newest root `hs_err_pid1039252.log` predates this
  run. Generated-C executable-marker inspection in
  `build/native-post-transfer-baseline/native-post-transfer-baseline-summary.json`
  found zero `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, function-table indexing,
  JNI call wrappers, array JNI wrappers, or JVMTI markers. Compile totals/top
  rows are recorded in the same summary JSON.

### [x] NPT-2: Stage 4 T4.0 eager exception-check env-offset publication

- Scope: implement the Stage 4.A structural performance prerequisite from
  `native-gentle-flamingo-todo.md` T4.0: ensure
  `neko_exception_check_resolve_env_offset` is not reachable from the hot
  `neko_exception_check` path after publication.
- Required evidence: current generated C and runtime logs proving the resolver
  can still be reached from hot exception-check sites, the exact publication
  invariant chosen (`JNI_OnLoad` bootstrap, first dispatcher entry, TLS cache, or
  outlined unlikely helper), and generated C proving the hot path is an
  acquire-load plus pointer arithmetic plus `_pending_exception` read.
- Validation command or runtime target: `R-build`, `R-test` x5,
  `R-obfusjack` x5, `R-native-obfusjack` x10, `R-inspect`, and `R-negative` as
  specified by T4.0, with fresh artifacts only.
- Completion criteria: perf/debug evidence shows at most one env-offset
  derivation per process, missing-offset paths hard abort, TEST Calc and
  obfusjack timing gates do not regress, and generated/runtime inspection shows
  no new JNI function-table use or fallback path.
- Dependency: NPT-1, unless a plan-level update records why a narrower fresh
  baseline is sufficient.
- Reopened evidence 2026-05-22: fresh TEST artifact
  `build/npt-3bw/TEST-native.jar` and obfusjack artifact
  `build/npt-3bw/obfusjack-native.jar` have the collapsed hot
  `neko_exception_check` body in generated C and no forbidden JNI grep matches,
  but an obfusjack `NEKO_PATCH_DEBUG=1` runtime printed repeated
  `eager env-offset publication via memory walk: off=960` lines in one JVM
  process. The exact failing invariant is process-wide publication: every
  generated shared library owns a private
  `g_neko_off_thread_jni_environment_for_check`, so each library repeats the
  cold memory walk even though the hot path remains resolver-free. The next
  implementation substep is a generic generated-native bootstrap cache: publish
  the validated JNIEnv-to-JavaThread offset into a process-wide cache after the
  first successful cold derivation, have later libraries validate that cached
  offset against the current `JNIEnv`, JNI functions table, pending-exception
  slot, vtable window, and thread-state invariant before accepting it, and keep
  the hot `neko_exception_check` path as a plain global load plus pointer
  arithmetic plus `_pending_exception` read. Validation remains the row's
  existing fresh `R-build`, repeated TEST/obfusjack runtime, `R-inspect`, and
  negative/fail-closed evidence.
- Completion evidence 2026-05-22: implemented a generic generated-native
  process cache for `JNIEnv* -> JavaThread*` distance publication. The first
  generated library validates and publishes `NEKO_NATIVE_JNI_ENV_OFFSET` after
  the cold memory walk; later generated libraries parse and revalidate that
  cache against the current `JNIEnv`, JNI functions table, vtable window,
  pending-exception slot, and thread-state invariant before accepting it.
  Invalid cache values are ignored after diagnostic proof and do not corrupt
  the hot path. The cache helpers are emitted as cold/noinline bootstrap-only
  code, and `neko_exception_check` remains a plain load of
  `g_neko_off_thread_jni_environment_for_check`, pointer arithmetic, and direct
  `_pending_exception` read with no resolver call. Focused `R-build` passed:
  `./gradlew --no-daemon :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Fresh artifacts:
  `build/npt-2-cache/TEST-native.jar` from
  `build/neko-native-work/run-31103118865777` (`translated=49 rejected=0`,
  lib `1043048` bytes) and `build/npt-2-cache/obfusjack-native.jar` from
  `build/neko-native-work/run-31110445445738`
  (`translated=93 rejected=0`, lib `1811816` bytes). `R-inspect` over those
  run dirs found `0` `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` generated-C
  matches and showed the cache helpers plus cold/noinline declarations.
  `NEKO_PATCH_DEBUG=1` obfusjack showed exactly one
  `derived JNIEnv->JavaThread distance via memory walk` and one
  `eager env-offset publication via memory walk`, followed by repeated
  `eager env-offset publication via process cache` lines; no later library
  repeated the memory walk. Invalid-cache negative runtime with
  `NEKO_NATIVE_JNI_ENV_OFFSET=8 NEKO_PATCH_DEBUG=1` exited cleanly, logged
  `ignored invalid process JNIEnv->JavaThread offset cache`, then performed one
  validated memory walk and completed obfusjack. Runtime gates on the final
  artifacts passed: TEST x5 retained `Tests r Finished` and Calc
  `71/70/71/68/69` ms (median `70`); original obfusjack x5 completed with
  Seq `2/2/2/3/2` ms; native obfusjack x10 completed with Platform
  `44/44/46/48/47/46/43/43/43/45` ms, Virtual
  `37/40/39/43/36/37/39/36/38/37` ms, Seq
  `17/17/17/20/17/17/17/17/17/17` ms, and Parallel/VThreads `1` ms. Same-
  session old-artifact comparison was similarly noisy and did not show a
  distinct regression: prior obfusjack samples included Platform
  `47/46/48/46`, Virtual `40/37/42/36`, and Seq `23/18/17/17` ms.
- Validation addendum 2026-05-22: closed the remaining T4.0 negative-proof gap
  by adding the generic diagnostic gate
  `NEKO_NATIVE_DIAG_FAIL_ENV_OFFSET_PUBLICATION` in `neko_method_layout_init`.
  The gate forces the same missing-publication hard-error path before any
  translated dispatch; it does not skip classes, enter JNI fallback, or preserve
  original bytecode. Focused generator validation passed with
  `CCodeGeneratorTest`; fresh `NativeObfuscationPerfTest` regenerated TEST at
  `build/neko-native-work/run-44896788247250` and obfusjack at
  `build/neko-native-work/run-44901712281896` and passed with TEST x5 Calc
  `3/2/3/3/3` ms and obfusjack x5 medians Platform `37`, Virtual `31`, Seq
  `10`, Parallel/VThreads `1`. Post-change `NEKO_PATCH_DEBUG=1` TEST and
  obfusjack runs each logged exactly one memory-walk derivation, then process
  cache reuse for later generated libraries, with no `memory-walk derivation
  failed` or `hot-path env-offset unpublished` hit. Generated-C inspection
  found resolver use only in the support helper definition and bootstrap
  layout-init call, while the hot `neko_exception_check` body remained a direct
  offset load plus pointer arithmetic and `_pending_exception` read. Native
  obfusjack x10 completed green with Seq
  `11/11/11/10/10/10/11/11/10/11` ms. Negative TEST-native and
  obfusjack-native runs with the diagnostic gate set aborted at startup with
  exit `134` and the log `eager env-offset publication forced missing by
  diagnostic gate; refusing to enter translated native path`.

### [-] NPT-3: Runtime P4 const fast-state accessor layer

- Scope: implement `native-performance-optimization-todo.md` P4 after the T4.0
  hot exception-check path is stable: post-bootstrap immutable fast-state
  accessors should read `g_hotspot_const` instead of mutable-looking
  `g_hotspot`, while collector-dynamic values such as ZGC mask pointers remain
  mutable.
- Required evidence: generated C/source inspection proving which accessors are
  immutable after bootstrap, which collector fields must remain dynamic, and how
  the changed accessors are used by hot helpers.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, performance gate, and GC strict compatibility
  gate from `native-performance-optimization-todo.md` P4.
- Completion criteria: generated C or compiler diagnostics show the intended
  immutable reads use `g_hotspot_const`, dynamic GC mask paths remain mutable,
  TEST/obfusjack runtime and GC gates pass with fresh artifacts, and no ABI,
  GC, JNI-removal, or fallback invariant is weakened.
- Dependency update: NPT-1 fresh generated C shows T4.0's structural hot path
  is already present in current source: `neko_exception_check` reads
  `g_neko_off_thread_jni_environment_for_check` directly, and the cold
  resolver is driven from `neko_method_layout_init`, not from the hot path.
  The remaining measured gap is performance, so P4 is the next generic
  post-bootstrap invariant optimization before revisiting wider T4.0/T4.14
  gates.
- P4 implementation evidence 2026-05-20: changed only
  `NativeHotSpotFastAccessEmitter` const accessors so post-bootstrap immutable
  fields read `g_hotspot_const`; source and generated C keep ZGC live-mask
  helpers on mutable `g_hotspot.z_zglobals_*` pointers. Focused Gradle audit
  passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL in 6s`).
  Fresh `.plan/native-only-full.yml` artifacts in
  `build/native-post-transfer-p4/` report TEST `translated=49 rejected=0`,
  obfusjack `translated=93 rejected=0`, SnakeGame `translated=18 rejected=0`,
  evaluator `translated=122 rejected=0`; generated TEST C
  `build/neko-native-work/run-3244581472985/neko_native_support.c` shows all
  `neko_const_*` immutable accessors reading `g_hotspot_const`, while dynamic
  ZGC mask helpers still read mutable mask pointers. Runtime: TEST x5 Calc =
  35/35/35/33/34 ms; obfusjack x5 completed with Seq = 23/23/23/22/25 ms,
  Parallel = 3/3/3/3/3 ms, VThreads = 4/5/6/4/5 ms. SnakeGame preserved
  headless behavior; evaluator exited 0. `NativeObfuscationIntegrationTest`
  passed (`BUILD SUCCESSFUL in 40s`). P4 remains `[-]` rather than `[x]`
  because the full performance gate still misses TEST Calc <= 20 ms and GC
  strict remains blocked: ZGC/Shenandoah abort at existing mask publication
  diagnostics (`ZGC oop load masks unavailable addr=0x0 good=0x0`), and
  Parallel/Serial obfusjack direct runs did not provide a clean strict-gate
  completion under the 240s direct-run cap.

### [x] NPT-4: Stage 4 T4.1 primitive Class mirror slots

- Scope: complete `native-gentle-flamingo-todo.md` T4.1 for primitive
  `Class` constants by routing both `neko_class_for_descriptor` and
  LDC primitive class slot binding through the generated descriptor → mirror
  table.
- Required evidence: current generated C proving `neko_class_for_descriptor`
  already uses `neko_primitive_mirror_for_char`, plus generated/source evidence
  that `neko_bind_primitive_class_slot` still uses `JVM_FindPrimitiveClass` for
  LDC primitive class descriptors.
- Validation command or runtime target: `R-build`, `R-test`,
  `R-obfusjack`, `R-native-test`, `R-inspect`, and `R-negative`; fresh
  generated C must show no `JVM_FindPrimitiveClass unavailable for LDC Class
  descriptor` path and no `FindClass` / `GetStaticFieldID` /
  `GetStaticObjectField` primitive-Class fallback.
- Completion criteria: primitive `Z/B/C/S/I/J/F/D/V` class slots bind through
  the mirror table populated at OnLoad, primitive-array kind mapping remains
  limited to legal array element primitive types, missing wrapper/TYPE/mirror
  metadata aborts, TEST and obfusjack complete with fresh artifacts, and no JNI
  or original-bytecode fallback is introduced.
- Negative-proof addendum recorded before editing: add a generic diagnostic gate
  that forces a selected primitive mirror-table wrapper lookup to take the same
  hard-abort path as missing wrapper metadata. The gate is table/tag driven only;
  it must not skip classes, preserve original bytecode, or add JNI fallback.
- Completion evidence 2026-05-22: `neko_bind_primitive_class_slot` now routes
  primitive class slots through `neko_primitive_mirror_for_char(env, desc[0])`.
  The mirror table covers `Z/B/C/S/I/J/F/D/V` with `java/lang/Void.TYPE`, while
  primitive-array kind mapping remains limited to the eight legal primitive
  array element types. `CCodeGeneratorTest` passed. Fresh full perf validation
  passed with
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeObfuscationPerfTest --rerun-tasks --no-parallel -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp`;
  generated artifacts were TEST `build/neko-native-work/run-46729377030886` and
  obfusjack `build/neko-native-work/run-46734567148804`. Perf medians were TEST
  Calc `3` ms and obfusjack Platform `40` ms, Virtual `36` ms, Seq `11` ms,
  Parallel/VThreads `1` ms. Generated-C inspection found
  `localClass = neko_primitive_mirror_for_char(env, desc[0]);`, `case 'V'`,
  `java/lang/Void`, and `NEKO_NATIVE_DIAG_FAIL_PRIMITIVE_MIRROR_TAG`; grep found
  zero `JVM_FindPrimitiveClass unavailable for LDC Class descriptor` and zero
  primitive-Class fallback table-index hits for `6/144/145`. `R-negative` with
  `NEKO_NATIVE_DIAG_FAIL_PRIMITIVE_MIRROR_TAG=I` aborted both TEST-native and
  obfusjack-native with exit `134` and log `T4.1 missing wrapper-class mirror
  for java/lang/Integer (kind=4 tag=I)`, proving missing metadata fails closed.

### [x] NPT-4a: Stage 4 T4.2a class lookup wrapper call sites

- Scope: complete `native-gentle-flamingo-todo.md` T4.2a by proving generated
  helper and manifest class-lookup call sites no longer call `neko_find_class`
  and route through `neko_resolve_class_with_env` /
  `neko_resolve_class_mirror_with_env` instead.
- Required evidence: source-emitter and generated-C grep for live
  `neko_find_class(...)` callsites, plus generated-C evidence that enumerated
  class-lookup paths use the resolver path.
- Validation command or runtime target: reuse the fresh T4.1 `R-build`,
  `R-test`, `R-obfusjack`, `R-native-test`, and `R-inspect` artifacts because no
  runtime source changed after T4.1; missing class resolution inherits T2.2
  `R-negative`.
- Completion criteria: live-callsite grep reports zero `neko_find_class(...)`
  callsites outside comments, generated C shows resolver calls in the enumerated
  helper/manifest paths, and no wrapper deletion is attempted before T4.11.
- Completion evidence 2026-05-22: no runtime code change was required. Fresh
  T4.1 artifacts TEST `build/neko-native-work/run-46729377030886` and obfusjack
  `build/neko-native-work/run-46734567148804` were produced by the passing
  `NativeObfuscationPerfTest` gate. Live-callsite grep
  `rg -n "neko_find_class[[:space:]]*\\(" ... | rg -v "^[^:]+:[0-9]+:[[:space:]]*(/\\*|\\*|//)"`
  returned zero for both generated artifacts and source emitters; raw hits were
  comments only. Generated C shows the class-lookup paths using
  `neko_resolve_class_with_env` / `neko_resolve_class_mirror_with_env`, and the
  wrapper remains undeleted for the T4.11 sweep gate.

### [x] NPT-4b: Stage 4 T4.2b method lookup wrapper call sites

- Scope: complete `native-gentle-flamingo-todo.md` T4.2b by proving generated
  helper and manifest method-lookup call sites no longer call
  `neko_get_method_id` / `neko_get_static_method_id` and route through
  `neko_resolve_method` instead.
- Required evidence: source-emitter and generated-C grep for live
  `neko_get_method_id(...)` / `neko_get_static_method_id(...)` callsites, plus
  generated-C/source evidence that method lookup uses `neko_resolve_method`.
- Validation command or runtime target: reuse the fresh T4.1 `R-build`,
  `R-test`, `R-obfusjack`, `R-native-test`, and `R-inspect` artifacts because no
  runtime source changed after T4.1; missing method resolution inherits T2.3
  `R-negative`.
- Completion criteria: live-callsite grep reports zero method-ID wrapper
  callsites outside comments, generated C/source emitters show resolver calls,
  and no wrapper deletion is attempted before T4.11.
- Completion evidence 2026-05-22: no runtime code change was required. Fresh
  T4.1 artifacts TEST `build/neko-native-work/run-46729377030886` and obfusjack
  `build/neko-native-work/run-46734567148804` were produced by the passing
  `NativeObfuscationPerfTest` gate. Live-callsite grep for
  `neko_get_method_id[[:space:]]*\\(` and
  `neko_get_static_method_id[[:space:]]*\\(` returned zero outside comments in
  both generated artifacts and source emitters. Generated C/source emitters show
  `neko_resolve_method(...)` for method resolution, and the wrappers remain
  undeleted for the T4.11 sweep gate.

### [x] NPT-4c: Stage 4 T4.2c static field lookup wrapper call sites

- Scope: complete `native-gentle-flamingo-todo.md` T4.2c by proving generated
  static-field lookup call sites no longer call `neko_get_static_field_id` and
  route through `neko_resolve_field(..., JNI_TRUE)` instead.
- Required evidence: source-emitter and generated-C grep for live
  `neko_get_static_field_id(...)` callsites, plus generated-C/source evidence
  that static field lookup uses `neko_resolve_field` with `is_static=true`.
- Validation command or runtime target: reuse the fresh T4.1 `R-build`,
  `R-test`, `R-obfusjack`, `R-native-test`, and `R-inspect` artifacts because no
  runtime source changed after T4.1; missing field resolution inherits T2.4
  `R-negative`.
- Completion criteria: live-callsite grep reports zero static-field-ID wrapper
  callsites outside comments, generated C/source emitters show resolver calls
  with `JNI_TRUE`, and no wrapper deletion is attempted before T4.11.
- Completion evidence 2026-05-22: no runtime code change was required. Fresh
  T4.1 artifacts TEST `build/neko-native-work/run-46729377030886` and obfusjack
  `build/neko-native-work/run-46734567148804` were produced by the passing
  `NativeObfuscationPerfTest` gate. Live-callsite grep for
  `neko_get_static_field_id[[:space:]]*\\(` returned zero outside comments in
  both generated artifacts and source emitters. Generated C/source emitters show
  `neko_resolve_field(..., JNI_TRUE)` for static field resolution, including the
  primitive `TYPE` and `IMPL_LOOKUP` paths. T4.2 is complete as a call-site
  replacement gate; wrapper deletion remains scoped to T4.11.

### [x] NPT-4d: Stage 4 T4.3a MethodType descriptor direct call

- Scope: complete `native-gentle-flamingo-todo.md` T4.3a by replacing
  `neko_method_type_from_descriptor`'s descriptor-string allocation and
  `MethodType.fromMethodDescriptorString(String, ClassLoader)` invocation with
  the existing interned-string path plus HotSpot call_stub/NJX dispatch.
- Required evidence: fresh generated C currently shows
  `g_neko_jni_new_string_utf_fn(env, desc)` and
  `g_neko_jni_call_static_object_method_a_fn(env, mtClass, mid, args)` inside
  `neko_method_type_from_descriptor`; source shows the same path in
  `NativeRuntimeSupportEmitter`.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, and `R-inspect`; generated C must show
  `neko_method_type_from_descriptor` uses `neko_intern_string` and
  `neko_njx_S_L_LL`, and contains no `g_neko_jni_new_string_utf_fn` or
  `g_neko_jni_call_static_object_method_a_fn` in that function.
- Completion criteria: descriptor jstring is produced through
  `neko_intern_string`, the static MethodType call uses a cached Method* and
  entry with `neko_njx_S_L_LL`, missing method/entry/thread/symbol prerequisites
  abort, TEST and obfusjack complete from fresh artifacts, and no JNI/original
  bytecode fallback is introduced.
- Negative-proof addendum recorded before editing: add a generic diagnostic
  gate that forces the MethodType descriptor NJX entry cache to take the same
  missing-entry hard-abort branch used when HotSpot cannot provide the required
  entry. The gate must not skip classes, enter JNI fallback, or preserve
  original bytecode; it may only prove the fail-closed path for the required
  MethodType descriptor call.
- User-updated acceptance recorded 2026-05-20: Calc must match or beat the
  original JVM jar in the same run environment; obfusjack Seq must be <= 10 ms;
  every other parsed benchmark/test timing must match original or stay within
  1.5x slowdown. Older absolute threshold rows are superseded for ongoing
  performance work.
- Completion evidence 2026-05-22: implemented generic LDC MethodType routing
  in `OpcodeTranslator.translateLdc`, added the `neko_njx_S_L_LL` shape, and
  replaced `neko_method_type_from_descriptor` with a cached Method*/i-entry
  call-stub path. The descriptor `jstring` now comes from
  `neko_intern_string(thread, env, (const uint8_t*)desc, strlen(desc));`, and
  the MethodType class resolver publishes `Klass*` through
  `neko_resolve_class_mirror_with_env(env, "java/lang/invoke/MethodType", NULL,
  &mt_klass)`. Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_methodTypeLdcUsesDescriptorNjxPath --rerun-tasks --no-parallel -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp`.
  The focused fixture generated
  `build/neko-native-work/run-48407471768465`; generated
  `neko_native_impl_0.c` contains
  `PUSH_O(neko_method_type_from_descriptor(env, "(Ljava/lang/String;I)Ljava/lang/String;"));`,
  the normal runtime printed `methodtype-ldc-ok`, and `R-negative` with
  `NEKO_NATIVE_DIAG_FAIL_METHODTYPE_DESCRIPTOR_ENTRY=1` aborted with
  `MethodType.fromMethodDescriptorString entry unavailable`. Fresh full
  `NativeObfuscationPerfTest --no-parallel` passed after the final source edit
  (`BUILD SUCCESSFUL in 46s`), producing TEST
  `build/neko-native-work/run-48428692005490` and obfusjack
  `build/neko-native-work/run-48433464484047`; medians were TEST Calc `3` ms
  and obfusjack Platform `40` ms, Virtual `34` ms, Seq `12` ms,
  Parallel/VThreads `1` ms. Generated-C inspection found the new
  `neko_method_type_from_descriptor` body uses `neko_intern_string` and
  `neko_njx_S_L_LL`, and found zero hits for
  `g_neko_jni_new_string_utf_fn(env, desc)` or
  `g_neko_jni_call_static_object_method_a_fn(env, mtClass, mid, args)`.

### [x] NPT-4e: Stage 4 T4.3b MethodType parameterArray direct call

- Scope: complete `native-gentle-flamingo-todo.md` T4.3b by replacing
  `neko_bootstrap_parameter_array`'s `MethodType.parameterArray()` invocation
  with a cached Method*/i-entry call through the existing NJX call-stub
  machinery.
- Required evidence: fresh generated C currently shows
  `neko_bootstrap_parameter_array` resolving `parameterArray` through
  `neko_resolve_jmethodID(env, mtClass, "parameterArray", "()[Ljava/lang/Class;")`
  and invoking it through `g_neko_jni_call_object_method_a_fn(env, mt, mid,
  NULL)` in both TEST `build/neko-native-work/run-48428692005490` and obfusjack
  `build/neko-native-work/run-48433464484047`.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and `R-negative`; generated C must show
  `neko_bootstrap_parameter_array` uses a cached Method*/entry and
  `neko_njx_V_L_`, and contains no `neko_resolve_jmethodID(... "parameterArray"
  ...)` or `g_neko_jni_call_object_method_a_fn(env, mt, mid, NULL)` in that
  function.
- Completion criteria: `MethodType.parameterArray()` executes through an
  instance NJX call-stub with receiver `mt`, missing method/entry/thread
  prerequisites abort, focused BSM/CONDY runtime exercises the path from a
  freshly generated native artifact, TEST and obfusjack complete from fresh
  artifacts, and no JNI/original-bytecode fallback is introduced.
- Negative-proof addendum recorded before editing: add a generic diagnostic
  gate that forces the parameterArray NJX entry cache to take the same
  missing-entry hard-abort branch used when HotSpot cannot provide the required
  entry. The gate must only prove fail-closed behavior and must not skip
  classes, enter JNI fallback, or preserve original bytecode.
- Runtime proof prerequisite recorded 2026-05-22: the focused BSM/CONDY
  fixture generated `build/neko-native-work/run-48808079908376` with
  `neko_native_impl_0.c:27` as `/* unsupported ldc constant */`, followed by
  the next `String.equals` call at line 29 consuming a null receiver and
  producing the observed bare Java `NullPointerException`. The freshly
  generated `neko_bootstrap_parameter_array` body at
  `neko_native_support_helpers_0.c:427-455` already contains the intended
  cached `neko_njx_V_L_` call, but the unsupported ConstantDynamic LDC prevents
  the runtime proof from reaching it. The generic prerequisite for this
  substep is therefore to route top-level `LdcInsnNode ConstantDynamic` through
  the existing `neko_resolve_constant_dynamic` native path instead of emitting
  an unsupported no-op; this does not complete or alter the later T4.3c/T4.3d
  de-JNI removals.
- Completion evidence 2026-05-22: implemented the generic prerequisite by
  lowering top-level `LdcInsnNode ConstantDynamic` through the existing
  `neko_resolve_constant_dynamic` runtime path, then completed
  `neko_bootstrap_parameter_array` with a cached `Method*`/i-entry and
  `neko_njx_V_L_` receiver call. Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_methodTypeLdcUsesDescriptorNjxPath --rerun-tasks --no-parallel -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp`
  (`BUILD SUCCESSFUL in 15s`). Fresh focused artifact
  `build/neko-native-work/run-49054367165176` generated
  `neko_native_impl_0.c:27` as a call to `neko_resolve_constant_dynamic(...)`
  instead of `/* unsupported ldc constant */`, and
  `neko_native_support_helpers_0.c:427-455` shows
  `neko_bootstrap_parameter_array` using `neko_njx_V_L_` with
  `g_neko_method_type_parameter_array_method` and
  `g_neko_method_type_parameter_array_entry`. Runtime stdout was
  `methodtype-ldc-ok`; the diagnostic negative run wrote
  `[neko-bind] MethodType.parameterArray entry unavailable` to
  `neko-test/build/test-native/methodtype-ldc-parameter-negative.stderr.log`
  and exited nonzero, proving fail-closed behavior without JNI fallback or
  original-bytecode fallback. Full performance validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeObfuscationPerfTest --rerun-tasks --no-parallel -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp`
  (`BUILD SUCCESSFUL in 48s`), with fresh TEST artifact
  `build/neko-native-work/run-49107782373059` (`translated=49 rejected=0`) and
  obfusjack artifact `build/neko-native-work/run-49141506190971`
  (`translated=93 rejected=0`). Medians were TEST Calc `2` ms
  (`2/4/2/2/2`), obfusjack Platform `39` ms (`39/39/36/39/36`), Virtual
  `32` ms (`32/33/30/35/30`), Seq `11` ms (`10/11/11/11/10`), Parallel `1`
  ms, and VThreads `1` ms; all remain at or below the previous T4.3a medians
  and below the recorded thresholds. Static inspection of the focused and full
  generated artifacts found no
  `neko_resolve_jmethodID(env, mtClass, "parameterArray", ...)` or
  `g_neko_jni_call_object_method_a_fn(env, mt, mid, NULL)` in
  `neko_bootstrap_parameter_array`; later T4.3c/T4.3d JNI uses remain visible
  only in their still-open functions.

### [x] NPT-4f: Stage 4 T4.3c bootstrap invocation direct calls

- Scope: complete `native-gentle-flamingo-todo.md` T4.3c by replacing
  `neko_invoke_bootstrap`'s reflective `Class.getDeclaredMethod`,
  `AccessibleObject.setAccessible`, and `Method.invoke` calls with cached
  `Method*`/i-entry dispatch through existing NJX call-stub shapes. The
  subtask may keep using the planned native/bootstrap helper surface and must
  not introduce Java helper layers, JNI fallback, or original-bytecode fallback.
- Required evidence: fresh generated C still shows
  `neko_invoke_bootstrap` resolving `Class.getDeclaredMethod` through
  `neko_resolve_jmethodID(env, classClass, "getDeclaredMethod",
  "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")`,
  constructing the bootstrap name through `g_neko_jni_new_string_utf_fn(env,
  bsm_name)`, calling it through
  `g_neko_jni_call_object_method_a_fn(env, bsmClass, getDeclaredMethod,
  getArgs)`, resolving/invoking `AccessibleObject.setAccessible` through
  `g_neko_jni_call_void_method_a_fn`, and resolving/invoking
  `Method.invoke` through `g_neko_jni_call_object_method_a_fn` in both TEST
  `build/neko-native-work/run-49107782373059` and obfusjack
  `build/neko-native-work/run-49141506190971`. The source emitter has the same
  path at `NativeRuntimeSupportEmitter.java:760-781`.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and `R-negative`; focused BSM/CONDY runtime
  must execute the bootstrap method through the new NJX path from a freshly
  generated artifact; generated `neko_invoke_bootstrap` must contain no
  `neko_resolve_jmethodID(... "getDeclaredMethod" ...)`,
  `neko_resolve_jmethodID(... "setAccessible" ...)`,
  `neko_resolve_jmethodID(... "invoke" ...)`,
  `g_neko_jni_new_string_utf_fn(env, bsm_name)`,
  `g_neko_jni_call_object_method_a_fn`, or
  `g_neko_jni_call_void_method_a_fn` in that function.
- Completion criteria: the bootstrap owner class, bootstrap method name,
  parameter type array, target object, and invoke argument array are passed
  through live jobject handles to cached NJX calls; missing class/method/entry
  or thread prerequisites hard-abort; focused BSM/CONDY, TEST, and obfusjack
  runtime targets pass from fresh artifacts; static inspection proves the old
  JNI calls are removed only from `neko_invoke_bootstrap` while T4.3d object
  array JNI remains open in `neko_resolve_constant_dynamic`.
- Negative-proof addendum recorded before editing: add a generic diagnostic
  gate that forces one required bootstrap-invocation NJX entry cache to take
  the same missing-entry hard-abort branch as a missing HotSpot entry. The gate
  must fail closed without skipping classes, entering JNI fallback, or
  preserving original bytecode.
- Completion evidence 2026-05-22: focused generator/integration validation
  passed with the repo Gradle wrapper and fresh focused artifact
  `build/neko-native-work/run-49487087892904`. The generated
  `neko_invoke_bootstrap` now resolves bootstrap invocation metadata through
  `neko_ensure_bootstrap_invoke_cache`, interns `bsm_name` through
  `neko_intern_string`, invokes `Class.getDeclaredMethod` and `Method.invoke`
  through `neko_njx_V_L_LL`, and invokes `AccessibleObject.setAccessible`
  through the collapsed integer shape `neko_njx_V_V_I`. The focused runtime
  printed `methodtype-ldc-ok`, and the
  `NEKO_NATIVE_DIAG_FAIL_BOOTSTRAP_GET_DECLARED_METHOD_ENTRY=1` negative run
  hard-aborted with `[neko-bind] Bootstrap Class.getDeclaredMethod entry
  unavailable`. Full `NativeObfuscationPerfTest --no-parallel` passed from
  fresh artifacts TEST `build/neko-native-work/run-50657289768705` and
  obfusjack `build/neko-native-work/run-50692730718348`; medians were Calc
  `2` ms, Platform `39` ms, Virtual `34` ms, Seq `11` ms, Parallel `1` ms,
  and VThreads `1` ms. Static inspection found zero hits for the old
  `g_neko_jni_new_string_utf_fn(env, bsm_name)`,
  `g_neko_jni_call_object_method_a_fn`, `g_neko_jni_call_void_method_a_fn`, or
  `neko_resolve_jmethodID(... getDeclaredMethod/setAccessible/invoke ...)`
  bootstrap-invocation path in the latest TEST and obfusjack artifacts.

### [x] NPT-4g: Stage 4 T4.3d ConstantDynamic object-array direct path

- Scope: complete `native-gentle-flamingo-todo.md` T4.3d by replacing
  `neko_resolve_constant_dynamic`'s JNI object-array length/allocation/load/
  store calls and the ConstantDynamic name UTF allocation with the existing
  native/bootstrap surface: direct array length from `arrayOopDesc` layout,
  direct object-array allocation through the validated T3.9 allocator,
  barrier-aware T3.7 `neko_fast_aaload`/`neko_fast_aastore`, and
  `neko_intern_string` for the ConstantDynamic name. The caller-owner Lookup
  chain remains delegated to T4.4. The subtask must not introduce Java helper
  layers, JNI fallback, skip-on-error, or original-bytecode fallback.
- Required evidence: fresh generated C from TEST
  `build/neko-native-work/run-50657289768705` and obfusjack
  `build/neko-native-work/run-50692730718348` still shows
  `neko_resolve_constant_dynamic` using `g_neko_jni_get_array_length_fn` for
  `paramTypes` and `static_args`, `g_neko_jni_new_object_array_fn` for
  `invokeArgs`, `g_neko_jni_set_object_array_element_fn` for slots 0/1/2 and
  static arguments, `g_neko_jni_get_object_array_element_fn` for static
  arguments, and `g_neko_jni_new_string_utf_fn(env, name)` for the
  ConstantDynamic name. Source evidence is
  `NativeRuntimeSupportEmitter.java:955-969`. Existing generic surfaces
  include `neko_fast_array_length` in `NativeHotSpotFastAccessEmitter`,
  `neko_fast_aaload`/`neko_fast_aastore` and
  `neko_fast_new_object_array` in `NativeFastObjectAccessEmitter`.
  Allocation dependency update recorded before implementation adjustment:
  focused validation of the first `oopFactory::new_objArray` implementation
  failed closed in the normal runtime with `[neko-bind] ConstantDynamic
  oopFactory::new_objArray entry unavailable`; native logs show
  `oopfactory=(nil)/(nil)`, and local `nm -D`/`strings` evidence for
  `/usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so` and Java 22 shows no
  exported `oopFactory::new_objArray` or `oopFactory::new_typeArray` symbol.
  Therefore this substep uses the existing T3.9 direct object-array allocator
  as the generic non-JNI allocation prerequisite and keeps fail-closed behavior
  through its direct-path abort.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and `R-negative`; focused BSM/CONDY runtime
  must execute the ConstantDynamic path from a freshly generated artifact;
  generated `neko_resolve_constant_dynamic` must contain no
  `g_neko_jni_new_string_utf_fn(env, name)`, `g_neko_jni_get_array_length_fn`,
  `g_neko_jni_new_object_array_fn`, `g_neko_jni_get_object_array_element_fn`,
  or `g_neko_jni_set_object_array_element_fn` in that function.
- Completion criteria: param/static argument counts are read from the direct
  array length path, `invokeArgs` is allocated through
  `neko_fast_new_object_array` or aborts if the direct allocation path is
  unavailable, all element transfers use barrier-aware load/store helpers, the
  ConstantDynamic name is interned without `NewStringUTF`, focused BSM/CONDY,
  TEST, and obfusjack runtime targets pass from fresh artifacts, and static
  inspection proves the removed JNI calls are gone only from
  `neko_resolve_constant_dynamic` while later T4 call sites remain explicitly
  open.
- Negative-proof addendum recorded before editing: add a generic diagnostic
  gate that forces the required ConstantDynamic direct object-array allocation
  path to take the same hard-abort branch as an unavailable direct allocation
  prerequisite. The gate must fail closed without skipping classes, entering
  JNI fallback, or preserving original bytecode.
- Completion evidence 2026-05-22: focused generator/integration validation
  passed with the repo Gradle wrapper and fresh focused artifact
  `build/neko-native-work/run-51392742529807`. The normal focused runtime
  printed `methodtype-ldc-ok`; `NEKO_NATIVE_DIAG_FAIL_CONDY_OBJ_ARRAY_ALLOC=1`
  hard-aborted with `[neko-bind] ConstantDynamic object-array direct allocation
  unavailable`; the existing MethodType descriptor, parameterArray, and
  bootstrap-invocation negative gates still hard-aborted on their own required
  entries. Static inspection of the focused artifact and the full perf
  artifacts shows `neko_resolve_constant_dynamic` now uses
  `neko_fast_array_length`, `neko_condy_new_object_array`,
  `neko_intern_string`, `neko_fast_aaload`, and `neko_fast_aastore`, with zero
  hits in that function for `g_neko_jni_new_string_utf_fn(env, name)`,
  `g_neko_jni_get_array_length_fn`, `g_neko_jni_new_object_array_fn`,
  `g_neko_jni_get_object_array_element_fn`, or
  `g_neko_jni_set_object_array_element_fn`. Full
  `NativeObfuscationPerfTest --no-parallel` passed twice; the accepted repeat
  pass used TEST artifact `build/neko-native-work/run-51575914263949` and
  obfusjack artifact `build/neko-native-work/run-51580899179255`, with
  calc-bench runs `3/2/3/2/2` ms (steady median `2` ms) and baseline medians
  Platform `40` ms, Virtual `34` ms, Seq `10` ms, Parallel `1` ms, VThreads
  `1` ms. No fresh `hs_err` file was produced. The strict Platform comparator
  against the previous T4.3c median is not treated as T4.3d-only evidence in
  this checkpoint because unrelated dirty CFF/core worktree changes are present
  and materially alter obfusjack generation; the next performance recovery row
  remains open for the overall target.

### [x] NPT-4h: Stage 4 T4.4a IMPL_LOOKUP cached slot

- Scope: complete `native-gentle-flamingo-todo.md` T4.4a by turning
  `neko_impl_lookup` from a per-call class/field/static-base read into a
  one-shot bind-time cache. The first successful read may use the existing
  T4.2 direct class/field/static-object machinery and the existing bind-time
  global-ref capture surface; subsequent calls must return the cached jobject
  slot directly. The subtask must not introduce Java helper layers, JNI field
  access fallback, skip-on-error, or original-bytecode fallback.
- Required evidence: fresh generated C from TEST
  `build/neko-native-work/run-51575914263949` and obfusjack
  `build/neko-native-work/run-51580899179255` shows `neko_impl_lookup` still
  resolving `java/lang/invoke/MethodHandles$Lookup`, calling
  `neko_resolve_field(klass, "IMPL_LOOKUP",
  "Ljava/lang/invoke/MethodHandles$Lookup;", JNI_TRUE)`, and returning
  `neko_fast_get_static_object_field(...)` on every call. Source evidence is
  `NativeMethodResolutionEmitter.java:338-369`. The forbidden JNI table
  indices `[6]`, `[144]`, and `[145]` are already absent from this function
  after T4.2; T4.4a removes the repeated direct metadata/read work from the
  hot helper path.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and `R-negative`; focused BSM/CONDY runtime
  must reach `neko_lookup_for_class` and obtain `IMPL_LOOKUP` through the
  cached slot from a freshly generated artifact; generated `neko_impl_lookup`
  must contain a cached slot read and no per-call `neko_resolve_field(...,
  "IMPL_LOOKUP", ...)` or `neko_fast_get_static_object_field(...)` in its final
  public body.
- Completion criteria: a missing slot at first read hard-aborts, successful
  first read creates and stores a global jobject in the static cache, repeated
  reads return that cache without resolving the class/field again, focused
  BSM/CONDY, TEST, and obfusjack runtime targets pass from fresh artifacts, and
  static inspection proves the generated `neko_impl_lookup` body has zero
  `[6]`, `[144]`, and `[145]` indexing plus no repeated direct field-read path.
- Negative-proof addendum recorded before editing: add a generic diagnostic
  gate that forces the first cached-slot read to take the same hard-abort branch
  as a missing cached `IMPL_LOOKUP` slot. The gate must fail closed without
  skipping classes, entering JNI field fallback, or preserving original
  bytecode.
- Completion evidence 2026-05-22: focused generator/integration validation
  passed with the repo Gradle wrapper and fresh focused artifact
  `build/neko-native-work/run-52023000803100`. Normal runtime printed
  `methodtype-ldc-ok`; `NEKO_NATIVE_DIAG_FAIL_IMPL_LOOKUP_SLOT=1` hard-aborted
  with `[neko-bind] IMPL_LOOKUP cached slot unavailable`, while the earlier
  MethodType, bootstrap, and ConstantDynamic negative gates still hard-aborted
  on their own required entries. Full `NativeObfuscationPerfTest --no-parallel`
  passed from fresh artifacts TEST `build/neko-native-work/run-52023000803100`
  and obfusjack `build/neko-native-work/run-52028071557407`; baseline medians
  were Calc `2` ms, Platform `40` ms, Virtual `33` ms, Seq `11` ms, Parallel
  `1` ms, VThreads `1` ms. Static inspection of both artifacts found the
  public `neko_impl_lookup` body reading `g_neko_impl_lookup_global`, using the
  diagnostic gate, calling `neko_read_impl_lookup_direct(env)` only for first
  bind, creating the cached global through `g_neko_jni_new_global_ref_fn`, and
  containing no `neko_resolve_field(... "IMPL_LOOKUP" ...)`,
  `neko_fast_get_static_object_field`, `FindClass`, `GetStaticFieldID`, or
  `GetStaticObjectField` path. No fresh `hs_err` file was produced.

### [x] NPT-4i: Stage 4 T4.4b privateLookupIn NJX path

- Scope: complete `native-gentle-flamingo-todo.md` T4.4b by replacing
  `neko_lookup_for_jclass`'s `MethodHandles.privateLookupIn(Class, Lookup)`
  static JNI invocation with a cached `Method*`/i-entry and
  `neko_njx_S_L_LL`. The owner-class argument must continue to route through
  `neko_resolve_class_mirror_with_env` in `neko_lookup_for_class`, and
  `IMPL_LOOKUP` must come from the T4.4a cached slot. The subtask must not
  introduce Java helper layers, JNI fallback, skip-on-error, or original
  bytecode fallback.
- Required evidence: fresh generated C from TEST
  `build/neko-native-work/run-52023000803100` and obfusjack
  `build/neko-native-work/run-52028071557407` shows `neko_lookup_for_jclass`
  still resolving `MethodHandles.privateLookupIn` through
  `neko_resolve_jmethodID_with_kind(..., JNI_TRUE)` and invoking it through
  `g_neko_jni_call_static_object_method_a_fn(env, mhClass, mid, args)`.
  Source evidence is `NativeRuntimeSupportEmitter.java:602-608`.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and `R-negative`; focused BSM/CONDY runtime
  must execute caller-owner lookup through the new NJX path from a freshly
  generated artifact; generated `neko_lookup_for_jclass` must contain no
  `neko_resolve_jmethodID_with_kind(... "privateLookupIn" ...)`,
  `g_neko_jni_call_static_object_method_a_fn`, `FindClass`,
  `GetStaticMethodID`, or `CallStaticObjectMethodA` path.
- Completion criteria: missing class/method/entry/thread prerequisites
  hard-abort, successful runs call `privateLookupIn` through cached Method*/
  i-entry and `neko_njx_S_L_LL`, focused BSM/CONDY, TEST, and obfusjack runtime
  targets pass from fresh artifacts, and static inspection proves the old JNI
  static invocation is removed only from `neko_lookup_for_jclass`.
- Negative-proof addendum recorded before editing: add a generic diagnostic
  gate that forces the required `privateLookupIn` NJX entry cache to take the
  same hard-abort branch as a missing HotSpot entry. The gate must fail closed
  without skipping classes, entering JNI fallback, or preserving original
  bytecode.
- Completion evidence 2026-05-22: focused generator/integration validation
  passed with the repo Gradle wrapper after fixing the generic bind-time
  `neko_resolve_method` call shape, and the fresh focused artifact
  `build/neko-native-work/run-52418773696489` printed `methodtype-ldc-ok`.
  `NEKO_NATIVE_DIAG_FAIL_PRIVATE_LOOKUP_ENTRY=1` hard-aborted with
  `[neko-bind] MethodHandles.privateLookupIn entry unavailable`. Full
  `NativeObfuscationPerfTest --no-parallel` passed from fresh artifacts TEST
  `build/neko-native-work/run-52418773696489` and obfusjack
  `build/neko-native-work/run-52423738487152`; the perf baseline artifacts were
  TEST `build/neko-native-work/run-52427587494029` and obfusjack
  `build/neko-native-work/run-52461986266540`, with baseline medians Calc `3`
  ms, Platform `37` ms, Virtual `33` ms, Seq `12` ms, Parallel `1` ms, and
  VThreads `1` ms. Static inspection of both accepted generated helper files
  found `neko_ensure_private_lookup_cache`, cached
  `g_neko_private_lookup_method`/`g_neko_private_lookup_entry`, and
  `neko_njx_S_L_LL` in the lookup path, with no
  `neko_resolve_jmethodID_with_kind(... "privateLookupIn" ...)`,
  `g_neko_jni_call_static_object_method_a_fn`, `FindClass`,
  `GetStaticMethodID`, or `CallStaticObjectMethodA` in
  `neko_lookup_for_jclass`.

### [x] NPT-4j: Stage 4 T4.5a shadow stack jstring intern path

- Scope: complete `native-gentle-flamingo-todo.md` T4.5a by replacing the
  `Throwable.getStackTrace()` shadow-frame owner/method/file C-string to
  jstring path with `neko_intern_string` plus stack-scoped handles. The change
  is limited to string materialization in `neko_shadow_dotted_string` and its
  immediate shadow-stack callers; StackTraceElement allocation, constructor
  invocation, and object-array stores remain owned by T4.5b. The subtask must
  not introduce JNI fallback, Java helper layers, skip-on-error, or original
  bytecode fallback.
- Required evidence: fresh generated C from TEST
  `build/neko-native-work/run-52418773696489` and obfusjack
  `build/neko-native-work/run-52423738487152` shows
  `neko_shadow_dotted_string` returning
  `g_neko_jni_new_string_utf_fn(env, buf)`, and `neko_shadow_stack_trace`
  passing `desc->method` and `desc->file` through
  `g_neko_jni_new_string_utf_fn(env, ...)`. Source evidence is
  `NativeRuntimeSupportEmitter.java:462-494`. Existing direct string evidence
  is `neko_intern_string(thread, env, (const uint8_t*)..., strlen(...))`
  followed by `neko_handle_push(thread, oop)` in the MethodType descriptor path.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, and `R-inspect`; generated `neko_shadow_dotted_string` and
  the shadow-frame string setup inside `neko_shadow_stack_trace` must contain no
  `g_neko_jni_new_string_utf_fn`, `NewStringUTF`, or JNI function-table index
  `167` path.
- Completion criteria: shadow-frame owner, method, and file strings are interned
  through `neko_intern_string`, null or missing-thread prerequisites fail
  closed, focused generator/runtime coverage and the full native performance
  gate pass from fresh artifacts, and static inspection proves only the
  T4.5a-owned string conversion path changed while T4.5b-owned allocation and
  constructor JNI paths remain explicit pending work.
- Completion evidence 2026-05-22: focused generator/runtime validation passed
  with the repo Gradle wrapper, including
  `NativeObfuscationIntegrationTest.nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`,
  and produced fresh focused artifact
  `build/neko-native-work/run-52860834972168`. Full
  `NativeObfuscationPerfTest --no-parallel` passed from fresh artifacts TEST
  `build/neko-native-work/run-52906013671894` and obfusjack
  `build/neko-native-work/run-52910713919869`; medians were Calc `3` ms,
  Platform `37` ms, Virtual `32` ms, Seq `11` ms, Parallel `1` ms, and
  VThreads `1` ms. Static inspection of both accepted helper files found
  `neko_shadow_utf_string`, `neko_intern_string(thread, env, ...)`,
  `neko_handle_push(thread, string_oop)`, and owner/method/file shadow-frame
  setup through the new helper, with no `g_neko_jni_new_string_utf_fn`,
  `NewStringUTF`, or `[167]` path in the T4.5a string conversion section.
  Remaining `NewObjectArray`, `NewObjectA`, and `SetObjectArrayElement`
  wrappers in that section are the recorded T4.5b work. No fresh `hs_err` file
  was produced.

### [x] NPT-4k: Stage 4 T4.5b shadow stack object/array direct path

- Scope: complete `native-gentle-flamingo-todo.md` T4.5b by replacing the
  remaining `Throwable.getStackTrace()` shadow-stack object/array JNI wrappers
  in `neko_shadow_stack_trace`. `StackTraceElement[]` allocation must use the
  existing T3.9 direct object-array allocator with `[Ljava/lang/StackTraceElement;`
  klass bits because the recorded T4.3d dependency proof shows local Java 21/22
  product builds do not export `oopFactory::new_objArray` or
  `oopFactory::new_typeArray` (`oopfactory=(nil)/(nil)`). Each
  `StackTraceElement` instance must be allocated through the existing direct
  object allocation path, initialized through a bind-time-cached
  `StackTraceElement.<init>(String,String,String,int)` Method*/i-entry and NJX
  call_stub, and stored through barrier-aware `neko_fast_aastore`. The subtask
  must not introduce JNI fallback, Java helper layers, skip-on-error, or
  original bytecode fallback.
- Required evidence: fresh generated C from TEST
  `build/neko-native-work/run-52906013671894` and obfusjack
  `build/neko-native-work/run-52910713919869` shows
  `neko_shadow_stack_trace` still resolving `StackTraceElement.<init>` with
  `neko_resolve_jmethodID(env, ste_cls, "<init>", ...)`, allocating the return
  array with `g_neko_jni_new_object_array_fn`, constructing elements with
  `g_neko_jni_new_object_a_fn`, and storing elements with
  `g_neko_jni_set_object_array_element_fn`. Source evidence is
  `NativeRuntimeSupportEmitter.java:493-520` after T4.5a.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and `R-negative`; focused translated
  stack-trace runtime must execute the new path from a freshly generated
  artifact; generated `neko_shadow_stack_trace` must contain no
  `neko_resolve_jmethodID(env, ste_cls, "<init>"...)`,
  `g_neko_jni_new_object_array_fn`, `g_neko_jni_new_object_a_fn`,
  `g_neko_jni_set_object_array_element_fn`, `FindClass`, `GetMethodID`,
  `NewObjectA`, `NewObjectArray`, or `SetObjectArrayElement`.
- Completion criteria: missing array klass bits, direct array allocation,
  constructor Method*/i-entry, object allocation, or AASTORE prerequisites
  hard-abort; positive focused stack-trace, TEST, and obfusjack runtime targets
  pass from fresh artifacts; negative diagnostics prove fail-closed behavior;
  and static inspection proves only the T4.5b-owned object/array path changed.
- Performance addendum recorded before hot-path guard edit: focused T4.5b
  validation passed after initializing `java/lang/StackTraceElement` before
  binding its constructor i-entry. Full perf then passed functionally, but
  obfusjack median Platform/Virtual regressed while generated obfusjack C had
  no `neko_shadow_stack_trace(env)` call and still pushed/popped shadow frames
  in every translated method. Complete this subtask with a generic artifact
  guard: only emit shadow-frame push/pop when the translated artifact contains
  a `Throwable.getStackTrace()` call that can consume the shadow stack. This
  must preserve the focused stack-trace fixture and remove dead shadow-frame
  hot-path work from artifacts that do not translate that intrinsic.
- Completion evidence 2026-05-22: focused generator/runtime validation passed
  with the repo Gradle wrapper after adding the generic class-initialization
  prerequisite for `StackTraceElement.<init>` i-entry binding. The positive
  dependency-shadow runtime printed `dependency-stacktrace-ok`; negative gates
  `NEKO_NATIVE_DIAG_FAIL_SHADOW_TRACE_ARRAY_ALLOC=1` and
  `NEKO_NATIVE_DIAG_FAIL_SHADOW_STE_CTOR_ENTRY=1` hard-aborted with
  `[neko-bind] StackTraceElement array direct allocation unavailable` and
  `[neko-bind] StackTraceElement.<init> entry unavailable`. Full
  `NativeObfuscationPerfTest --no-parallel` passed from fresh artifacts TEST
  `build/neko-native-work/run-54346235581712` and obfusjack
  `build/neko-native-work/run-54350891613747`; medians were Calc `3` ms,
  Platform `37` ms, Virtual `31` ms, Seq `10` ms, Parallel `1` ms, and
  VThreads `1` ms. Static inspection found `neko_fast_new_object_array`,
  `neko_fast_alloc_object`, `neko_njx_V_V_LLLI`, and `neko_fast_aastore` in the
  shadow-stack body, with no `neko_resolve_jmethodID(env, ste_cls, "<init>"...)`,
  `g_neko_jni_new_object_array_fn`, `g_neko_jni_new_object_a_fn`,
  `g_neko_jni_set_object_array_element_fn`, `FindClass`, `GetMethodID`,
  `NewObjectA`, `NewObjectArray`, or `SetObjectArrayElement`. Static
  inspection also confirmed obfusjack generated no `neko_shadow_push`,
  `neko_shadow_pop`, or `neko_shadow_stack_trace(env)` call while TEST retained
  shadow frames for its translated `Throwable.getStackTrace()` path. No fresh
  `hs_err` file was produced.

### [x] T4.6a: Generic stack-scoped handle window primitive

- Scope: complete the first generic handle-window checkpoint by making the
  existing `neko_handle_window_begin` / `neko_handle_window_end` primitive
  callable from earlier runtime-support helpers and converting one helper that
  creates transient local handles. This substep must not delete every
  `neko_delete_local_ref` call site; that is reserved for T4.6b.
- Required evidence: source and generated-C proof that the primitive is
  declared before `NativeRuntimeSupportEmitter` use, that at least one converted
  helper calls `neko_handle_window_begin` / `neko_handle_window_end`, and that
  the converted helper preserves any returned reference by converting it to a
  raw oop before window restore and pushing one new return handle afterwards.
- Validation command or runtime target: focused generator/runtime tests with
  repository `./gradlew`, then `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, and `R-inspect` through the performance gate.
- Completion criteria: a fresh generated TEST artifact exercises the converted
  helper successfully, generated `neko_shadow_stack_trace` contains the
  stack-scoped handle window and no JNI local-delete replacement, generated C
  contains no new JNI/JVMTI/original-bytecode fallback path, and performance
  medians remain within the recorded gates.
- Evidence recorded before editing 2026-05-22: `JniHandlesShimEmitter` already
  emits `neko_handle_window_begin` / `neko_handle_window_end`, but
  `CCodeGenerator` emits `NativeRuntimeSupportEmitter` before the shim, and
  only `neko_handle_oop` / `neko_handle_push` are forward-declared there.
  `neko_shadow_stack_trace` is the smallest generic converted helper candidate:
  after T4.5b it allocates a `StackTraceElement[]`, pushes transient string and
  element handles, calls the cached `StackTraceElement.<init>` entry through
  NJX, and returns the trace array. The return handle must survive the window by
  using the existing `neko_prepare_return_oop(thread, trace, "shadow_stack_trace")`
  before restoring the window, followed by one `neko_handle_push` for the return.
- Completion evidence 2026-05-22: focused generator/runtime validation passed
  with `CCodeGeneratorTest` and
  `NativeObfuscationIntegrationTest.nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`;
  the positive runtime printed `dependency-stacktrace-ok`, and the existing
  shadow-stack negative runs still hard-aborted with
  `StackTraceElement array direct allocation unavailable` and
  `StackTraceElement.<init> entry unavailable`. Full
  `NativeObfuscationPerfTest --no-parallel` passed from fresh artifacts TEST
  `build/neko-native-work/run-54888323119993` and obfusjack
  `build/neko-native-work/run-54893502305395`; medians were Calc `3` ms,
  Platform `33` ms, Virtual `33` ms, Seq `11` ms, Parallel `1` ms, and
  VThreads `1` ms. Static inspection found `neko_shadow_stack_trace` using
  `neko_handle_window_begin(thread, &handle_window)`,
  `neko_prepare_return_oop(thread, trace, "shadow_stack_trace")`,
  `neko_handle_window_end(&handle_window)`, and a final return
  `neko_handle_push(thread, trace_oop)`; the old StackTraceElement constructor
  and array JNI call wrappers remained absent from the helper body. No fresh
  top-level `hs_err` file was produced; the newest existing one remained
  `hs_err_pid3.log` from 2026-05-22 04:11.

### [x] T4.6b: Convert remaining local-ref deletes to handle windows

- Scope: remove every remaining runtime call site that invokes the captured
  `DeleteLocalRef` pointer as `g_neko_jni_delete_local_ref_fn(env, ...)`,
  replacing each with the generic T4.6a handle-window primitive or with a raw
  oop return path when the helper already unwraps the local reference. This
  substep does not delete the captured pointer declaration or bootstrap capture;
  wrapper sweep deletion remains T4.11 after all callers are gone.
- Required evidence: source and generated-C grep showing no
  `g_neko_jni_delete_local_ref_fn(env, ...)` call sites remain; generated C must
  show the converted helper windows around the local-producing operations; any
  returned reference that crosses a window must be converted to a raw oop with
  `neko_prepare_return_oop` before restore and re-pushed only after restore.
- Validation command or runtime target: focused generator/runtime tests with
  repository `./gradlew`, then `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, and the performance gate. `R-negative` is the
  grep/build fail-closed proof that any residual local-ref delete call site
  aborts the subtask before T4.11 deletion.
- Completion criteria: regenerated TEST and obfusjack artifacts run green,
  generated C has no `g_neko_jni_delete_local_ref_fn(env,` calls, the
  `neko_icache_dispatch` PIC-miss path uses a handle window with no performance
  regression against the current T4.6a accepted medians, and no JNI/JVMTI or
  original-bytecode fallback is introduced.
- Evidence recorded before editing 2026-05-22: source grep after T4.6a found
  remaining local-ref delete callers in `NativeBindSupportEmitter`
  (`neko_ensure_class_initialized`, slow byte-array allocation, TLAB scratch
  refill, slow primitive-array allocation, string intern cleanup, and declared
  method materialization), `NativeStringSupportEmitter`
  (`neko_intern_string_without_raw_heap` local byte-array cleanup),
  `NativeHotSpotFastAccessEmitter` (`neko_icache_dispatch` PIC-miss
  `exactMirror` cleanup), and `ManifestEmitter` (`owner_cls` / `anchor_cls`
  cleanup). Fresh accepted artifacts TEST
  `build/neko-native-work/run-54888323119993` and obfusjack
  `build/neko-native-work/run-54893502305395` generated matching
  `g_neko_jni_delete_local_ref_fn(env, ...)` calls in support helpers,
  icache support, and manifest C; obfusjack expands the manifest owner cleanup
  into many generated calls from one source emitter site.
- Completion evidence 2026-05-22: focused `CCodeGeneratorTest` passed after
  the final source edit, and full `NativeObfuscationPerfTest --no-parallel`
  passed from fresh accepted artifacts TEST
  `build/neko-native-work/run-55551256714109` and obfusjack
  `build/neko-native-work/run-55555910623507`. Medians were Calc `3` ms,
  Platform `36` ms, Virtual `29` ms, Seq `11` ms, Parallel `1` ms, and
  VThreads `1` ms, which remains within the T4.6a accepted gate. Static
  inspection of those two accepted artifacts found zero
  `g_neko_jni_delete_local_ref_fn(env, ...)` call sites. It found the converted
  handle windows in `neko_ensure_class_initialized`, slow byte-array allocation,
  slow primitive-array allocation, `neko_intern_string`,
  `neko_intern_string_without_raw_heap`, `neko_link_class_methods`,
  `neko_icache_dispatch`, and manifest owner/anchor discovery. The only
  remaining `g_neko_jni_delete_local_ref_fn` references are declarations and
  the bootstrap capture of JNI table index `[23]`, reserved for T4.11 sweep
  deletion after all T4.x caller removals are complete. No fresh top-level
  `hs_err` file was produced; the newest existing one remained
  `hs_err_pid3.log` from 2026-05-22 04:11.

### [x] T4.7: Delete string allocation JNI bootstrap probe

- Scope: complete `native-gentle-flamingo-todo.md` T4.7 by ensuring the
  deleted `neko_fast_string_runtime_init` bootstrap probe remains absent. The
  removed probe used inline JNI `NewStringUTF("")` and `NewByteArray(0)` calls
  to derive `g_neko_string_klass_bits` and `g_neko_byte_array_klass_bits`;
  `neko_ensure_string_alloc_bits` is the only accepted readiness path and must
  hard-abort when VMStructs or layout prerequisites are unavailable.
- Required evidence: source and generated-C grep must find no
  `neko_fast_string_runtime_init` definition or call, no captured
  `g_neko_jni_new_byte_array_fn`, no `NewByteArray` probe, and no
  `g_neko_jni_new_string_utf_fn(env, ...)` call in the string-allocation
  readiness path. Generated C must still call `neko_ensure_string_alloc_bits`
  from bootstrap/bind-time initialization paths.
- Validation command or runtime target: `R-build`, `R-test`,
  `R-obfusjack`, `R-native-test`, and `R-inspect` through the performance gate.
  `R-negative` is the fail-closed source path in `neko_ensure_string_alloc_bits`
  for missing VMStructs, missing raw-heap mode, missing class mirrors, missing
  byte-array klass bits, or missing TLAB layout prerequisites.
- Completion criteria: regenerated TEST and obfusjack artifacts run green,
  generated C contains no deleted string-allocation probe or NewByteArray /
  NewStringUTF readiness call, and the only readiness mechanism is
  `neko_ensure_string_alloc_bits(env)` with abort-on-missing-prerequisite
  behavior.
- Evidence recorded before checkpoint 2026-05-22: current source already
  contains the generic T4.7 deletion. `NativeFastObjectAccessEmitter` and
  `MethodPatcherBootstrapEmitter` document the removed probe, while
  `NativeBindSupportEmitter` defines `neko_ensure_string_alloc_bits` as the
  authoritative VMStructs path. Source grep found no
  `neko_fast_string_runtime_init` function body, no
  `g_neko_jni_new_byte_array_fn`, no `NewByteArray` call, and no
  `g_neko_jni_new_string_utf_fn(env, ...)` call; the only live readiness calls
  are `neko_ensure_string_alloc_bits(env)`.
- Completion evidence 2026-05-22: the fresh T4.6b performance gate accepted
  TEST artifact `build/neko-native-work/run-55551256714109` and obfusjack
  artifact `build/neko-native-work/run-55555910623507`; medians were Calc
  `3` ms, Platform `36` ms, Virtual `29` ms, Seq `11` ms, Parallel `1` ms,
  and VThreads `1` ms. Static inspection of those accepted artifacts found no
  `neko_fast_string_runtime_init`, no `g_neko_jni_new_byte_array_fn`, no
  `NewByteArray`, and no `g_neko_jni_new_string_utf_fn(env, ...)` readiness
  call. The generated artifacts retain only `neko_ensure_string_alloc_bits(env)`
  calls plus the expected comments and unrelated wrapper declarations reserved
  for later T4.x sweep deletion. No fresh top-level `hs_err` file was produced;
  the newest existing one remained `hs_err_pid3.log` from 2026-05-22 04:11.

### [x] T4.8a: Remove dead icache global-ref release path

- Scope: start `native-gentle-flamingo-todo.md` T4.8 with the hot-path inline
  cache release sites. `neko_icache_site.cached_class` is currently a legacy
  diagnostic field: source grep finds writes and stale-entry deletes, but no
  reads. Dispatch identity is keyed by `receiver_key`, and resolved targets are
  held in `target` / `target2` / `target_kind`. This substep removes the
  `DeleteGlobalRef` calls from icache eviction/store paths without changing
  class binding, string binding, manifest owner storage, IMPL_LOOKUP, or Unsafe
  object-cache behavior; those remain later T4.8 substeps.
- Required evidence: source grep must prove `cached_class` is not read by any
  dispatch path, and generated C from the accepted T4.6b artifacts must show
  the three current icache `g_neko_jni_delete_global_ref_fn(env, ...)` call
  sites. After the edit, generated C must contain no icache global-ref delete
  call and must continue to key dispatch on `receiver_key`.
- Validation command or runtime target: focused `CCodeGeneratorTest`, then
  `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, and the
  performance gate.
- Completion criteria: TEST and obfusjack regenerate and run green from fresh
  artifacts; static inspection shows the icache eviction/store paths no longer
  call `g_neko_jni_delete_global_ref_fn`; generated dispatch still contains
  `receiver_key`, `target`, `target2`, and `target_kind`; no JNI/JVMTI,
  fallback, or original-bytecode path is introduced.
- Evidence recorded before editing 2026-05-22: source grep for
  `cached_class` finds the struct field and only three runtime write/delete
  sites in `NativeHotSpotFastAccessEmitter`: `neko_icache_claim_slot`,
  `neko_icache_store_direct`, and `neko_icache_store_direct_njx`. No source
  read consumes `cached_class`; the cache hit path uses `receiver_key` through
  `neko_icache_find_slot`. Fresh accepted artifacts TEST
  `build/neko-native-work/run-55551256714109` and obfusjack
  `build/neko-native-work/run-55555910623507` generate the same three
  `g_neko_jni_delete_global_ref_fn(env, site->cached_class[...])` calls in
  every prelude/support copy.
- Completion evidence 2026-05-22: focused `CCodeGeneratorTest` passed after
  removing the legacy field and updating the icache assertions. Full
  `NativeObfuscationPerfTest --no-parallel` then passed from fresh accepted
  artifacts TEST `build/neko-native-work/run-56652351573369` and obfusjack
  `build/neko-native-work/run-56657546304182`; medians were Calc `2` ms,
  Platform `40` ms, Virtual `31` ms, Seq `11` ms, Parallel `1` ms, and
  VThreads `1` ms. Static inspection of those artifacts found no
  `cached_class` field and no
  `g_neko_jni_delete_global_ref_fn(env, site->cached_class...)` call. It found
  the retained dispatch state `receiver_key`, `target`, `target2`, and
  `target_kind`, plus the narrowed `neko_icache_claim_slot`,
  `neko_icache_store_direct`, and `neko_icache_store_direct_njx` signatures
  that no longer accept `JNIEnv*` or a cached class handle. No fresh top-level
  `hs_err` file was produced; the newest existing one remained
  `hs_err_pid3.log` from 2026-05-22 04:11.

### [x] T4.8b: Remove IMPL_LOOKUP global object cache

- Scope: continue T4.8 by removing the persistent
  `g_neko_impl_lookup_global` object handle and its `NewGlobalRef` call from
  `neko_impl_lookup`. This substep is limited to the
  `MethodHandles$Lookup.IMPL_LOOKUP` receiver path used by MethodType,
  MethodHandle, and ConstantDynamic bootstrap helpers. It must not change class
  globals, string globals, manifest owner storage, Unsafe receiver caching, or
  any class-loader lifetime behavior.
- Required evidence: source and generated C must show `neko_impl_lookup`
  obtains `IMPL_LOOKUP` by calling `neko_read_impl_lookup_direct(env)` and
  returning the resulting local handle. `neko_read_impl_lookup_direct` must
  still resolve the static field through `neko_resolve_field(..., JNI_TRUE)`
  and read it via `neko_fast_get_static_object_field`, which materializes a
  scoped local handle from the static-field oop. The public
  `neko_impl_lookup` body must contain no `g_neko_impl_lookup_global`, no
  `g_neko_jni_new_global_ref_fn`, no `NewGlobalRef`, and no JNI table indexes
  `[21]`, `[144]`, or `[145]`.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeObfuscationIntegrationTest.nativeObfuscation_methodTypeLdcUsesDescriptorNjxPath`,
  then `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, and
  the performance gate.
- Completion criteria: fresh TEST and obfusjack native artifacts run green;
  the MethodType/ConstantDynamic integration negative gate still fails closed
  when `NEKO_NATIVE_DIAG_FAIL_IMPL_LOOKUP_SLOT=1`; static inspection confirms
  the IMPL_LOOKUP path has no global-ref allocation; no JNI/JVMTI, fallback,
  original-bytecode path, or cached raw oop is introduced.
- Evidence recorded before editing 2026-05-22: source grep found the only
  non-test `g_neko_impl_lookup_global` declaration/use in
  `NativeMethodResolutionEmitter.neko_impl_lookup`. The current
  `neko_read_impl_lookup_direct` path already resolves
  `MethodHandles$Lookup.IMPL_LOOKUP` with `neko_resolve_field(..., JNI_TRUE)`
  and returns `neko_fast_get_static_object_field(...)`; that helper reads the
  static oop and calls `neko_direct_oop_to_handle_origin`, so the replacement
  handle is scoped to the active local handle machinery instead of being a
  persistent global reference. Existing accepted T4.8a artifacts TEST
  `build/neko-native-work/run-56652351573369` and obfusjack
  `build/neko-native-work/run-56657546304182` passed the runtime/performance
  gate before this substep.
- Completion evidence 2026-05-22: focused `CCodeGeneratorTest` passed, and
  `NativeObfuscationIntegrationTest.nativeObfuscation_methodTypeLdcUsesDescriptorNjxPath`
  passed with normal output `methodtype-ldc-ok` and the
  `NEKO_NATIVE_DIAG_FAIL_IMPL_LOOKUP_SLOT=1` negative run hard-aborting with
  `[neko-bind] IMPL_LOOKUP direct local handle unavailable`. Full
  `NativeObfuscationPerfTest --no-parallel` passed from fresh accepted
  artifacts TEST `build/neko-native-work/run-57155823898002` and obfusjack
  `build/neko-native-work/run-57161091124931`; `native-performance-baseline.json`
  captured at `2026-05-22T04:15:52.818855778Z` reports medians Calc `3` ms,
  Platform `33` ms, Virtual `32` ms, Seq `11` ms, Parallel `1` ms, and
  VThreads `1` ms. Static inspection of those artifacts found
  `neko_impl_lookup` returning `localLookup` from
  `neko_read_impl_lookup_direct(env)`, with the direct reader retaining
  `neko_resolve_field(klass, "IMPL_LOOKUP", ..., JNI_TRUE)` and
  `neko_fast_get_static_object_field(...)`. It found no
  `g_neko_impl_lookup_global`, no
  `g_neko_jni_new_global_ref_fn(env, localLookup)`, and no `NewGlobalRef` in
  the IMPL_LOOKUP body. No fresh top-level `hs_err` file was produced; the
  newest existing one remained `hs_err_pid3.log` from 2026-05-22 04:11.

### [ ] T4.8c: Remove Unsafe.allocateInstance receiver global object cache

- Scope: continue T4.8 by removing `g_neko_unsafe_instance_global` and the
  `NewGlobalRef` promotion of `Unsafe.theUnsafe`. This substep is limited to
  the NJX receiver used by the VM-managed `Unsafe.allocateInstance(Class)`
  slow paths. It must not change string literal global slots, class global
  slots, manifest owner storage, icache dispatch, or the existing
  `allocateInstance` Method*/entry binding contract.
- Required evidence: source and generated C must show the cache initializer
  stores only stable metadata (`Unsafe` Klass and `theUnsafe` static-field
  offset) plus the existing `allocateInstance` Method*/entry pointers. Every
  `neko_njx_V_L_L` call to `Unsafe.allocateInstance` must obtain its receiver
  from a helper that rereads the static field and returns a local handle through
  `neko_fast_get_static_object_field`. The changed path must contain no
  `g_neko_unsafe_instance_global`, no
  `g_neko_jni_new_global_ref_fn(env, unsafe_local)`, and no cached raw oop.
- Validation command or runtime target: focused `CCodeGeneratorTest`, then
  `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, the
  performance gate, and GC strict TEST/obfusjack runs under G1, Parallel,
  Serial, ZGC with `ZVerifyViews`, and Shenandoah verification where the local
  JDK exposes those collectors/options.
- Completion criteria: fresh TEST and obfusjack native artifacts run green;
  static inspection confirms the Unsafe receiver path has no global-ref
  allocation and still calls `neko_njx_V_L_L` with a non-null local receiver;
  GC strict runs do not crash or skip; no JNI/JVMTI, fallback, original
  bytecode path, or persistent raw oop is introduced.
- Evidence recorded before editing 2026-05-22: source grep found
  `g_neko_unsafe_instance_global` declared in `NativeFastObjectAccessEmitter`
  and used only by the Unsafe cache initializer plus three
  `Unsafe.allocateInstance` receiver call paths: raw-disabled string intern,
  raw String graph slow allocation, and NEW slow allocation. The current
  initializer already resolves bootstrap `jdk/internal/misc/Unsafe`,
  initializes it, resolves the static `theUnsafe` field with
  `neko_resolve_field(..., JNI_TRUE)`, and reads that static value through
  `neko_fast_get_static_object_field`. The only persistent object-root step is
  the `g_neko_jni_new_global_ref_fn(env, unsafe_local)` promotion that this
  substep removes.

### [ ] T4.8d: Resolve GC strict bootstrap barrier capability gate

- Scope: unblock the GC strict portion of the T4.8c acceptance gate by fixing
  only the generic bootstrap/runtime barrier readiness path for ZGC and
  Shenandoah. This substep must not skip collectors, skip classes, weaken native
  coverage, add JNI fallback, or treat TEST/obfusjack as special cases.
- Required evidence: fresh debug runs must identify the exact failing bootstrap
  invariant for ZGC and Shenandoah before implementation. For ZGC, evidence must
  distinguish callable runtime barrier availability from the existing inline
  dynamic-mask path and prove whether complete live mask pointer publication is
  sufficient. For Shenandoah, evidence must identify a generic callable barrier
  source or prove that no safe local implementation exists. Missing required
  barrier capability must continue to hard abort.
- Validation command or runtime target: focused `CCodeGeneratorTest`, then fresh
  `R-build`, `R-test`, `R-obfusjack`, `R-inspect`, and GC strict TEST/obfusjack
  direct runs under G1, Parallel, Serial, ZGC with `ZVerifyViews`, and
  Shenandoah verification where those collectors/options are available locally.
- Completion criteria: ZGC and Shenandoah bootstrap either become ready through a
  proven generic barrier mechanism and pass the direct native runtime targets, or
  the plan records a concrete unsupported HotSpot capability with fail-closed
  evidence. In either case the path must contain no JNI/JVMTI fallback,
  skip-on-error success, original-bytecode fallback, or collector bypass.
- Evidence recorded before editing 2026-05-22: T4.8c focused and full
  performance gates passed, but GC strict ZGC and Shenandoah runs aborted during
  native layout initialization before translated execution. ZGC detected
  BarrierSet tag `5` through the JDK 21 fallback, but readiness rejected the
  collector because `sym_z_load_barrier_on_oop_field_preloaded`,
  `sym_z_load_barrier_on_oop_array`,
  `sym_z_store_barrier_on_oop_field_with_healing`, and all sampled
  ZGlobals mask values were missing/zero. Shenandoah detected BarrierSet tag `4`
  through the JDK 21 fallback, but readiness rejected it because
  `sym_shenandoah_load_reference_barrier_strong`,
  `sym_shenandoah_write_ref_field_pre_entry`, and
  `sym_shenandoah_arraycopy_barrier_oop_entry` were all missing. Source audit
  shows ZGC already has inline field/array load and field store paths that still
  hard-abort if live masks are unavailable or a bad-marked oop requires runtime
  healing; Shenandoah has no equivalent inline alternative in the current code.
- Rejected evidence 2026-05-22: a probe change that treated complete ZGlobals
  live mask pointer publication as ZGC-ready was regenerated and run against
  `TEST-native.jar`. It moved the failure from bootstrap readiness to the first
  required ZGC oop load, which hard-aborted with
  `ZGC oop load masks unavailable addr=0x0 good=0x0`; therefore pointer
  publication with zero live mask values is not a sufficient capability on this
  HotSpot build. The same probe extended the stripped `CompilerToVM::Data` scan
  to search plain Z store and Shenandoah barrier strings. Runtime logs found the
  strings `store_barrier_on_oop_field_with_healing`,
  `load_reference_barrier_strong`, and `write_ref_field_pre_entry`, but no
  executable address slots were bound; Shenandoah still failed readiness with
  `lrb=(nil) pre=(nil) array=(nil)`. The probe implementation was reverted and
  is not retained.
- Additional implementation evidence 2026-05-22: fresh strict ZGC/Shenandoah
  debug runs after regenerating current source still fail closed at bootstrap,
  but the CodeCache walk exposes HotSpot-generated C1 barrier CodeBlobs. ZGC
  publishes `load_barrier_on_oop_field_preloaded_runtime_stub` and
  `store_barrier_on_oop_field_with_healing`; Shenandoah source publishes
  `shenandoah_load_reference_barrier_strong_slow` and
  `shenandoah_pre_barrier_slow`. OpenJDK 21 source shows these stubs load
  stack arguments with `C1_MacroAssembler::load_parameter`: load stubs take
  `(oop, oop*)` and return the resolved oop in `rax`; store/pre stubs take one
  field/pre-value argument. The implementation may therefore harvest exactly
  those named CodeBlobs and bridge their C1 stack-argument ABI. Missing named
  stubs remain a hard bootstrap capability failure.
- Additional implementation evidence 2026-05-22: after the C1 CodeBlob
  harvesting change, Shenandoah bootstrap readiness becomes true with
  `sh_c1_load` and `sh_c1_pre`, then aborts before translated execution at
  `[neko-bind] array klass bits layout unavailable for [Lpack/Main;`. Source
  inspection shows `NEKO_HOTSPOT_FAST_RAW_HEAP` is intentionally disabled for
  both ZGC and Shenandoah, but the object-array klass-bits binder and generated
  array hot guards exempt only ZGC from the raw-heap bit requirement. OpenJDK 21
  allocation source initializes array memory, length, and Klass header directly
  before publication; this matches the existing ZGC direct-array path and is
  collector-generic when strict load/store barriers are ready. A sidecar source
  audit of OpenJDK 21 x86 barrier stubs also found Shenandoah
  `shenandoah_pre_barrier_slow` reads `r15_thread`; the C1 one-argument bridge
  must publish the current JavaThread in `r15` while preserving the caller's
  register state.
- Additional implementation evidence 2026-05-22: after allowing Shenandoah
  direct array metadata and publishing `r15` for the one-argument C1 bridge,
  `TEST-native.jar` under `-XX:+UseShenandoahGC -XX:+ShenandoahVerify` reaches
  translated `pack/Main.main` execution and then aborts at
  `[neko-direct] unresolved virtual dispatch println(Ljava/lang/String;)V`.
  The dispatch code aborts only when the receiver-key path is unavailable;
  source inspection shows `neko_receiver_key_supported()` allows non-raw Klass
  key reads for ZGC but not Shenandoah even though Shenandoah readiness,
  `klass_offset_bytes`, and compact-header checks are already satisfied. This is
  the same moving-GC metadata gate class as the object-array layout failure, not
  a method/site-specific dispatch problem.
- Additional implementation evidence 2026-05-22: after enabling Shenandoah
  receiver keys, the strict Shenandoah TEST run reaches
  `pack/tests/basics/ctrl/Ctrl.runt()` and crashes in native code. Fresh
  `hs_err_pid3.log` shows `RDI` at the harvested
  `shenandoah_pre_barrier_slow` CodeBlob, `R15` as the current JavaThread, and
  `RIP` equal to the JavaThread address. The inline C1 one-argument bridge used
  register operands directly and then overwrote `r15` before `call *%0`; when
  the compiler allocated the stub operand in `r15`, the call target became the
  JavaThread pointer. The bridge must first copy stub, argument, and thread from
  stable memory operands into scratch registers, then publish `r15`, then call
  the scratch stub register.
- Additional implementation evidence 2026-05-22: after the scratch-register C1
  bridge fix, focused codegen and the full native performance gate passed, and
  strict Shenandoah TEST progressed past the pre-barrier stub into
  `pack/tests/basics/ctrl/Ctrl.runt()`. Fresh `hs_err_pid14.log` now crashes at
  `libneko_2386952722587874045.so+0x472c1`; `objdump` maps that offset to
  `neko_card_mark_field`'s card-table byte store. Registers show `R11` still
  holds the harvested `shenandoah_pre_barrier_slow` address and `R15` is the
  JavaThread, proving the prior bridge target invariant is fixed and the new
  failing invariant is the selected Shenandoah post-store barrier. Local
  OpenJDK 21 source audit shows Shenandoah C1 stores run
  `pre_barrier(...)`, optional IU barrier, then `BarrierSetC1::store_at_resolved`
  (`tmp/openjdk-jdk21u/src/hotspot/share/gc/shenandoah/c1/shenandoahBarrierSetC1.cpp:188`);
  C2 stores do the same SATB/IU work and then
  `BarrierSetC2::store_at_resolved`
  (`tmp/openjdk-jdk21u/src/hotspot/share/gc/shenandoah/c2/shenandoahBarrierSetC2.cpp:495`).
  The Shenandoah source tree contains no `write_ref_field_post`, no
  `CardTableBarrierSet`, and no card-table post barrier implementation. The
  generated Shenandoah post-store barrier must therefore be a no-op; card-table
  marking remains only for CardTable/G1 paths.
- Additional validation evidence 2026-05-22: after making Shenandoah post-store
  no-op, focused codegen and the full native performance gate passed. Strict
  Shenandoah TEST then progressed through basics and into
  `pack/tests/reflects/field/FTest.run()V`, where fresh `hs_err_pid14.log`
  crashed at `libneko_4390777485134236610.so+0xd75c2` with `si_addr=0x3c`.
  The loaded library hash maps to fresh generated run
  `build/neko-native-work/run-61341939442667`; generated C shows
  `neko_native_impl_32.c:57` invokes `FObject.<init>(I)` through NJX, then
  line `58` immediately `POP_O()`s the missing result into `AASTORE` before the
  pending-exception dispatch at line `62`. The crash registers show
  `_pending_exception` contains a real `java.lang.NoSuchMethodException` for
  `pack.tests.reflects.field.FObject.<init>(int)`, proving the invoked method
  threw and the next bytecode consumed invalid native stack state. The failing
  invariant is generic JVM exception semantics: after any potentially throwing
  translated bytecode, the next real bytecode must not execute while
  `_pending_exception` is set, regardless of unchanged try-handler coverage.
  The deferred-check optimization in
  `NativeTranslator.needsCheckBefore(...)` must therefore flush before the next
  real instruction, while labels/handler dispatch and method-exit propagation
  remain unchanged.
- Additional implementation evidence 2026-05-22: after the pending-exception
  fix, focused codegen and the full native performance gate passed, and strict
  Shenandoah TEST completed with empty stderr and `Calc: 3ms`. Strict
  Shenandoah obfusjack then failed as a Java exception, not a VM crash:
  `java.lang.IllegalArgumentException: not primitive: void` during
  `MethodHandles$Lookup.findStatic` immediately after generated
  `org/example/Main.main` reads `java/lang/Void.TYPE`, builds
  `MethodType.methodType(Void.TYPE, String.class)`, and calls `findStatic` for
  `staticHello`. The original `test-jars/test21.jar` passes under the same
  Shenandoah verification flags. Fresh debug logs show Shenandoah direct
  runtime symbols are stripped and only C1 CodeBlobs are available:
  `sh_c1_load=...`, `sh_c1_pre=...`, while generated `neko_handle_oop` still
  resolves JNI handle slots by a raw slot load and never applies a Shenandoah
  native/off-heap load barrier. OpenJDK 21 source proves this is a distinct
  ABI path: `generate_c1_load_reference_barrier_runtime_stub` uses
  `ShenandoahRuntime::load_reference_barrier_strong` for `IN_NATIVE` loads and
  `load_reference_barrier_strong_narrow` only for compressed in-heap loads; the
  corresponding C1 CodeBlob is named
  `shenandoah_load_reference_barrier_strong_native_slow`. The generic fix is
  to harvest that native C1 stub and use it for JNI handle-slot loads under
  Shenandoah; heap field/array loads keep the existing non-native barrier.

### [-] NPT-3a: Runtime P10 generic NJX call-parameter packing

- Scope: continue runtime performance work by optimizing only the generic
  native-to-Java call-stub machinery used to jump to original JVM methods.
  This row must not add owner/name/descriptor-specific native implementations.
- Required evidence: audit of current translator/codegen special cases, rollback
  of the uncommitted `java/lang/Math` → C libm lowering, source/generated-C
  proof that Math calls still bind and jump through NJX to the original JVM
  methods, and generated-C proof that full `memset(call_params, ...)` is gone
  while every used call parameter slot and required two-slot padding word is
  explicitly initialized.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew` after permission, then the owning P10 runtime gate
  before marking complete.
- Completion criteria: no `translateMathIntrinsicInvoke`, libm call, or `-lm`
  link flag remains; generated C contains bound `java/lang/Math` method entries
  rather than native math calls; NJX stack packing preserves the previous zeroed
  high/padding words without full-array memset; no JNI/JVMTI/original-bytecode
  fallback or target-method control-flow replacement is introduced.
- Evidence update 2026-05-20: rolled back the forbidden `java/lang/Math` native
  intrinsic route and removed the unused method-specific native helper bodies
  for `String.length`, `Object.getClass`, `String.concat`, and
  `Atomic*.addAndGet`. Source audit over `neko-native/src/main/java` found no
  remaining `translateMathIntrinsicInvoke`, libm `sqrt`/`pow`, `-lm`, or those
  fast helper definitions; generated audit over
  `build/neko-native-work/run-5500725993204` found no emitted forbidden helper
  symbols or libm calls. The remaining `translateIntrinsicMethodInvoke` cases
  are caller/stack compatibility bridges, not performance replacements.
- Evidence update 2026-05-20: retained only generic native-to-Java machinery
  changes: explicit NJX `call_params` slot initialization, shape-specialized
  call-stub parameter packing, receiver inline-cache keys derived from the
  already-resolved `Klass*`, and fused nested-array helper cleanup that removes
  a duplicate outer-bounds check after the same check has already succeeded.
  These changes preserve the original JVM/JDK target call; no named target
  method body is implemented in native code.
- Validation update 2026-05-20: focused generator/audit tests passed with
  `./gradlew :neko-test:test --rerun-tasks --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.OpcodeTranslatorUnitTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL`, artifact
  `artifact://112`). Full native integration passed with
  `./gradlew :neko-test:test --rerun-tasks --tests
  dev.nekoobfuscator.test.NativeObfuscationIntegrationTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL`, artifact
  `artifact://114`).
- Open gate evidence 2026-05-20: parity is still not accepted. Fresh repeated
  direct runs written to
  `build/native-run-tmp/parity-current/summary-after-generic.json` recorded
  TEST original/native Calc medians `12 ms` / `135 ms`; obfusjack
  original/native medians Seq `2 ms` / `19 ms`, Platform `25 ms` / `50 ms`,
  Virtual `15 ms` / `47 ms`. This row remains `[-]`; no checkpoint commit is
  valid until the parity gate is met or the retained subset is split into an
  accepted, freshly validated prerequisite row.
  - Evidence update 2026-05-20: read-only forbidden-substitution audit
    `agent://8-ForbiddenNativeSubstitutionAudit` found no remaining
    owner/name/descriptor-specific performance replacements for Math/libm,
    String.length/concat, Object.getClass, Atomic addAndGet, or similar
    targets in the requested native translator/codegen files. Remaining named
    cases are compatibility bridges that call cached original JVM Method*
    targets through NJX.

### [rejected] NPT-3b: Runtime P10 compiled-entry NJX bridge prototype

- Scope: replace the shape-specialized NJX call-stub bridge with the existing
  generic compiled-entry trampoline path only if the trampoline calls each
  supplied Method* `_from_compiled_entry` target unchanged. This must not
  implement any owner/name/descriptor-specific Java/JDK method in native code
  and must not alter translated-target selection.
- Required evidence: source and generated-C proof that direct, virtual, helper,
  reflection-adapter, String concat/valueOf, and implicit-exception NJX sites
  pass compiled-entry pointers when the bridge expects compiled entries; proof
  that each generated shape is keyed only by ABI shape; runtime logs or
  generated C proving original JVM/JDK methods are still invoked by Method*
  entry pointers instead of native replacements.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew`, `NativeObfuscationIntegrationTest`, direct TEST and
  obfusjack parity runs under `-Djava.io.tmpdir=build/native-run-tmp`, and
  generated-C forbidden-marker inspection. If any runtime, verifier, GC-root,
  stack-walk, or parity gate fails, revert this row before trying another
  optimization.
- Completion criteria: fresh artifacts report `translated>0 rejected=0`; no
  fatal JVM/runtime errors, JNI/JVMTI wrappers, fallback markers, or native
  method-body substitutions are present; TEST Calc and obfusjack parsed
  timings move toward same-run original JVM parity without replacing original
  target calls.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://151`), but fresh `NativeObfuscationIntegrationTest` failed with
  repeated SIGSEGVs in HotSpot stack walking / `Throwable.fillInStackTrace`
  while frames crossed `~BufferBlob::neko_njx_trampoline` (`artifact://153`,
  `artifact://155`, `hs_err_pid1442764.log`, `hs_err_pid1500630.log`).
  Adjusting the synthetic BufferBlob frame size from `8 + stackArgs + pad` to
  `9 + stackArgs + pad` did not remove the crash. The compiled-entry bridge
  prototype was reverted before any checkpoint commit because it violates the
  row's stack-walk/runtime acceptance criteria.

### [x] NPT-3c: Runtime P10 call_stub guard register-save trim

- Scope: optimize only the generic HotSpot call_stub bridge used by
  shape-specialized NJX dispatchers. The bridge must keep calling the supplied
  original Method* entry and must not introduce any owner/name/descriptor
  native implementation.
- Required evidence: the current guarded bridge saves callee-saved registers
  around a C-ABI call to HotSpot `call_stub`; the proposed change may remove
  only wrapper-local redundant saves while preserving SysV stack alignment and
  the two stack-passed call_stub arguments. Generated C must still call
  `neko_call_stub_guarded` / `g_neko_call_stub_entry` with the same Method* and
  entry pointers.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct TEST and obfusjack parity runs, and
  generated-C forbidden-marker inspection under
  `-Djava.io.tmpdir=build/native-run-tmp`.
- Completion criteria: fresh artifacts run without SIGSEGV/SIGABRT/verifier
  errors, no forbidden JNI/JVMTI/fallback or method-body substitution appears,
  and same-run direct timings improve or do not regress versus the committed
  call_stub baseline.
- Completion evidence 2026-05-20: removed the wrapper-local callee-saved
  register pushes/pops and alignment-only `subq $8` from
  `neko_call_stub_guarded`; the bridge still passes the same call_stub, Method*,
  entry pointer, parameter stack, result buffer, and JavaThread arguments to
  HotSpot. Focused generator/audit tests passed (`artifact://167`) and
  `NativeObfuscationIntegrationTest` passed (`artifact://169`). Fresh direct
  parity runs under `build/native-run-tmp/parity-p10c/` completed with no
  fatal markers: TEST original/native Calc medians `11 ms` / `134 ms`;
  obfusjack original/native medians Seq `2 ms` / `18 ms`, Platform
  `26 ms` / `50 ms`, Virtual `15 ms` / `44 ms`. Generated C inspection in
  `build/neko-native-work/run-7465333913274/neko_native_support.c` preserved
  call_stub dispatch and did not reintroduce Math/libm or named JDK native
  substitutions. The row is an accepted generic call_stub bridge micro-
  optimization, but P10 remains open because same-run original JVM parity is
  still not achieved.

### [rejected] NPT-3d: Runtime P10 bounded JavaCallWrapper zeroing

- Scope: reduce per-NJX-call stack zeroing in the call_stub bridge by clearing
  only the generated JavaCallWrapper prefix that is actually populated and
  whose frame-anchor extent is already validated before every call. This must
  keep the same HotSpot call_stub target, Method*, entry pointer, arguments,
  result handling, handle scope, and thread-state transitions.
- Required evidence: source/generated-C proof that the cleared byte count covers
  the thread, JNIHandleBlock, Method*, receiver, and JavaFrameAnchor fp/pc/sp
  fields before call_stub observes the wrapper; runtime validation must reject
  any stack-walk, exception, GC-root, or verifier failure.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct TEST and obfusjack parity runs, and
  generated-C forbidden-marker inspection under
  `-Djava.io.tmpdir=build/native-run-tmp`.
- Completion criteria: no SIGSEGV/SIGABRT/verifier/fatal markers; no forbidden
  JNI/JVMTI/fallback or named method-body substitutions; same-run direct
  timings improve or do not regress relative to the NPT-3c checkpoint.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://179`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://181`), but fresh direct parity runs in
  `build/native-run-tmp/parity-p10d/` regressed against the NPT-3c checkpoint:
  TEST native Calc median `136 ms` vs `134 ms`; obfusjack native Seq
  `19 ms` vs `18 ms`; Virtual `46 ms` vs `44 ms` while Platform remained
  `50 ms`. The bounded-zeroing source change was reverted before any
  implementation checkpoint because the row's no-regression criterion was not
  met.

### [rejected] NPT-3e: Runtime P10 Java-thread-state hot branch

- Scope: optimize only the generic NJX call_stub thread-state precheck by making
  the already-required `_thread_in_java` path the predicted fallthrough. Native
  callers must still transition through `neko_transition_native_to_java`; any
  unsupported state must still hard abort.
- Required evidence: source/generated-C proof that the same call_stub Method*
  and entry pointers are used and that only branch shape changed.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3c.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://186`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://188`), but direct timing did not meet the no-regression gate.
  Partial five-run parity in `build/native-run-tmp/parity-p10e/` recorded TEST
  native Calc values `137/132/136/137/134 ms`; the obfusjack native series hit
  a 120s timeout during the third repeated run. A follow-up three-run obfusjack
  native check under `build/native-run-tmp/parity-p10e-extra/` completed but
  showed Platform `53/53/52 ms` versus the NPT-3c median `50 ms`. The branch
  hint source change was reverted before any implementation checkpoint.

### [rejected] NPT-3f: Runtime P10 direct call_stub guard arguments

- Scope: remove the per-NJX-call temporary `neko_call_stub_args_t` aggregate
  from the generic call_stub bridge and pass the same call_stub arguments
  directly through the guard function's C ABI. The guard must still invoke the
  supplied HotSpot `call_stub` with the same JavaCallWrapper mirror, Method*,
  entry pointer, parameter stack, result buffer, result type, and JavaThread.
- Required evidence: generated-C proof that the guard call is still shape-
  generic and not owner/name/descriptor-specific; source proof that the x86-64
  SysV stack-passed `size` and `thread` arguments keep correct alignment.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3c.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://199`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://201`), but direct parity in
  `build/native-run-tmp/parity-p10f/` regressed against NPT-3c on TEST Calc
  and obfusjack Platform/Virtual: TEST native Calc median `136 ms` vs `134 ms`;
  obfusjack native Seq improved `17 ms` vs `18 ms`, but Platform was
  `51 ms` vs `50 ms` and Virtual was `45 ms` vs `44 ms`. The direct-argument
  source/test changes were reverted before any implementation checkpoint.

### [rejected] NPT-3g: Runtime P11 conditional JNIHandleBlock `_last` publication

- Scope: optimize the generic local-handle push paths by writing
  `JNIHandleBlock::_last` only when a previously empty active block receives
  its first pushed handle. Overflow block creation must still initialize its
  own `_last`, and restore must still restore the saved `_last` value.
- Required evidence: source/generated-C proof that every active block with
  `_top > 0` still has `_last == block` before HotSpot can allocate into it,
  while repeated pushes into the same non-empty block avoid redundant `_last`
  stores. This must apply generically to signature-dispatch and raw-oop return
  handle paths, not to a benchmark-specific method.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/GC-root/forbidden-marker regressions
  and same-run timings improve or do not regress relative to NPT-3c.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://206`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://208`), but direct parity in
  `build/native-run-tmp/parity-p10g/` did not meet the no-regression gate.
  TEST native Calc values were `132/133/137/138/138 ms` (median `137 ms`,
  worse than NPT-3c `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after four completed runs. The conditional `_last` source
  changes were reverted before any implementation checkpoint.

### [x] NPT-3h: Runtime P10 return-kind-specific NJX result loads

- Scope: optimize only shape-specialized NJX call_stub result unpacking by
  loading `__call_result` according to the known return kind: integer/object
  returns read the integer lane, float/double returns read the FP lane, and
  void returns read neither. The HotSpot call_stub target, Method*, entry
  pointer, argument stack, exception behavior, handle scope, and target method
  selection must remain unchanged.
- Required evidence: generated-C proof that each shape still uses the same
  call_stub bridge and only omits dead result-lane loads for return kinds that
  cannot observe them.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3c.
- Completion evidence 2026-05-20: shape-specialized NJX dispatchers now load
  only the result lane required by their return kind: void shapes skip
  `__call_result` reads, integer/object shapes load `out_rax`, and FP shapes
  copy only `out_xmm0`. Focused generator/audit tests passed
  (`artifact://214`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://216`). Fresh direct parity in
  `build/native-run-tmp/parity-p10h/` completed with no fatal markers: TEST
  original/native Calc medians `12 ms` / `134 ms`; obfusjack original/native
  medians Seq `2 ms` / `17 ms`, Platform `26 ms` / `50 ms`, Virtual
  `14 ms` / `44 ms`. Generated-C inspection of
  `build/neko-native-work/run-9173021508276/neko_native_support.c` shows
  call_stub dispatch is still used and the old combined
  `out_rax = ...; memcpy(&out_xmm0, ...)` unpack is gone from shape bodies.
  The row is accepted as a generic bridge micro-optimization; full P10 parity
  remains open.

### [rejected] NPT-3i: Runtime P10 single-slot NJX result buffer

- Scope: reduce the shape-specialized NJX call_stub result buffer from two
  machine words to the single `intptr_t` word needed to hold any JVM primitive
  or object return lane on the supported 64-bit targets. The call_stub result
  pointer and BasicType must remain unchanged, and void/integer/object/FP
  returns must preserve their existing semantics.
- Required evidence: generated-C proof that the buffer remains at least
  `sizeof(intptr_t)` and all result unpacking reads only that word after
  NPT-3h. This must be shape-generic and not target a method owner/name.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://221`), but fresh `NativeObfuscationIntegrationTest` failed in
  `nativeObfuscation_randomRuntimeStableTenRuns` because
  `obfusjack-native.jar` timed out after 45s (`artifact://223`). The
  single-slot result-buffer source change was reverted before any
  implementation checkpoint because the runtime stability gate failed.

### [rejected] NPT-3j: Runtime P10 constant-index NJX call parameter packing

- Scope: optimize only shape-specialized NJX call parameter packing by emitting
  constant `call_params` slot indexes for each known ABI shape instead of the
  mutable `__njx_pos` cursor. The exact slot layout, two-slot long/double
  padding word, object handle unwrapping, Method*, entry pointer, and call_stub
  behavior must remain unchanged.
- Required evidence: source/generated-C proof that generated shapes use fixed
  slot indexes and still preserve the existing zero-padding semantics for
  one-slot primitives and two-slot long/double arguments.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://227`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://229`), but direct parity in
  `build/native-run-tmp/parity-p10j/` did not meet the no-regression gate.
  TEST native Calc values were `140/140/133/136/134 ms` (median `136 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after three completed runs. The constant-index packing source
  change was reverted before any implementation checkpoint.

### [rejected] NPT-3k: Runtime P10 return-kind-specific NJX result locals

- Scope: continue NPT-3h by emitting only the result local variables and debug
  log arguments required by each shape return kind. Integer/object shapes need
  only `out_rax`, FP shapes need only `out_xmm0`, and void shapes need neither.
  The call_stub result buffer, BasicType, Method*, entry pointer, arguments,
  handle scope, and exception behavior must remain unchanged.
- Required evidence: generated-C proof that result locals/logging are
  return-kind-specific and no target method body or control-flow replacement is
  introduced.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://235`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://237`), but direct parity in
  `build/native-run-tmp/parity-p10k/` did not meet the no-regression gate.
  TEST native Calc values were `138/146/139/133/138 ms` (median `138 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after two completed runs. The return-kind-local source change
  was reverted before any implementation checkpoint.

### [rejected] NPT-3l: Runtime P10 mark call_stub guard hot

- Scope: mark the generic x86-64 `neko_call_stub_guarded` wrapper as a hot
  function so the native compiler places/optimizes the mandatory bridge as hot
  code. The wrapper remains `naked`, `noinline`, and calls the same supplied
  HotSpot call_stub with the same arguments.
- Required evidence: source/generated-C proof that only the function attribute
  changed and no owner/name/descriptor-specific method body or target selection
  behavior changed.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://243`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://245`), but direct parity in
  `build/native-run-tmp/parity-p10l/` regressed against NPT-3h: TEST native
  Calc median `140 ms` vs `134 ms`, obfusjack native Platform `51 ms` vs
  `50 ms`, and Virtual `47 ms` vs `44 ms` while Seq remained `17 ms`. The
  `hot` attribute source change was reverted before any implementation
  checkpoint.

### [rejected] NPT-3m: Runtime P6 primitive-array cold failure outlining

- Scope: outline only the primitive array helper failure `fprintf`/`abort`
  blocks into cold noinline helpers. Fast-path null/bounds/metadata checks,
  array load/store semantics, exception/fail-closed behavior, and generated
  target method selection must remain unchanged.
- Required evidence: generated-C proof that `neko_fast_*aload` and
  `neko_fast_*astore` hot bodies call cold failure helpers instead of carrying
  inline `fprintf` blocks.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://250`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://252`), but direct parity in
  `build/native-run-tmp/parity-p10m/` did not meet the no-regression gate.
  TEST native Calc values were `136/141/135/143/139 ms` (median `139 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after two completed runs. The primitive-array cold failure
  helper source change was reverted before any implementation checkpoint.

### [rejected] NPT-3n: Runtime P10 remove hot NJX dispatch stats counter

- Scope: remove only the per-NJX-call diagnostic dispatch counter from the hot
  shape-specialized call_stub path. Direct invocation remains mandatory, debug
  logging still works through `NEKO_DIRECT_LOG`, resolve-failure counting
  remains available, and target Method*/entry selection is unchanged.
- Required evidence: source/generated-C proof that shape dispatchers no longer
  call `neko_njx_note_dispatch()` while call_stub, Method*, entry pointer,
  parameter stack, result unpacking, exception checks, and handle scopes remain
  unchanged.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://260`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://262`), but direct parity in
  `build/native-run-tmp/parity-p10n/` did not meet the no-regression gate.
  TEST native Calc values were `142/139/141/141/135 ms` (median `141 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after one completed run. The dispatch-stats source change was
  reverted before any implementation checkpoint.

### [rejected] NPT-3o: Runtime P10 localize NJX debug log gate

- Scope: keep NJX debug logging behavior but avoid repeated hot-path
  `neko_njx_debug()` loads in each shape-specialized call_stub dispatcher by
  caching the debug gate in a local and using it for the entry/exit debug log
  branches. Direct invocation, dispatch stats, resolve-failure stats, Method*,
  entry pointer, arguments, handle scopes, and exception behavior must remain
  unchanged.
- Required evidence: generated-C proof that shape dispatchers still emit the
  same debug log strings when enabled and still call the same HotSpot call_stub
  target; no owner/name/descriptor-specific method body replacement is allowed.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://268`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://270`), but direct parity in
  `build/native-run-tmp/parity-p10o/` did not meet the no-regression gate.
  TEST native Calc values were `138/138/141/142/136 ms` (median `138 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after four completed runs. The local debug-gate source change
  was reverted before any implementation checkpoint.

### [x] NPT-3p: Runtime P10 remove unused generic NJX dispatcher body

- Scope: delete only the generated static generic NJX dispatcher and its
  generic return-BasicType helper after source search proves registered call
  sites emit shape-specialized dispatchers. This removes dead generated C and
  must not change any Method*/entry target, call_stub bridge, argument packing,
  handle scope, exception behavior, or debug behavior on reachable paths.
- Required evidence: source search showing `neko_njx_dispatch_generic` is not
  referenced by any emitted callsite; generated C still contains the
  shape-specialized `neko_njx_*` dispatchers and no generic dispatcher body.
- Validation command or runtime target: focused generator/audit tests and
  `NativeObfuscationIntegrationTest`. Direct parity is recorded if runtime
  paths change; this row is intended to remove unreachable code only.
- Completion criteria: fresh integration passes, generated C has no
  `neko_njx_dispatch_generic`, no forbidden JNI/JVMTI/fallback or named
  method-body substitution appears, and worktree is checkpointed.
- Completion evidence 2026-05-20: source search found no emitter callsite for
  `neko_njx_dispatch_generic`; all reachable callsites use shape-specialized
  dispatchers registered through `renderShapeCallStub`. Removed the unused
  generated generic dispatcher body and its private `neko_njx_result_basic_type`
  helper. Focused generator/audit tests passed (`artifact://280`) and fresh
  `NativeObfuscationIntegrationTest` passed (`artifact://282`). Generated C in
  `build/neko-native-work/run-12646002423282/` contains shape-specialized
  `neko_njx_*` dispatchers and no `neko_njx_dispatch_generic` or
  `neko_njx_result_basic_type` symbols; target Method*/entry call paths remain
  shape-specialized call_stub dispatches with no named JVM/JDK native method
  replacement.

### [x] NPT-3q: Runtime P10 remove obsolete compiled-entry trampoline generator

- Scope: delete the unused Java emitter code and generated declarations for the
  rejected compiled-entry NJX trampoline path. The retained generated runtime
  must continue to use HotSpot call_stub shape dispatchers for original JVM
  Method*/entry targets; no call target, argument packing, handle scope,
  exception behavior, or thread-state transition may change.
- Required evidence: source search proves `renderBodies()` emits
  `renderShapeCallStub` and no code references the compiled-entry generator,
  wrapper globals, or trampoline begin/end symbols after cleanup.
- Validation command or runtime target: focused generator/audit tests and
  `NativeObfuscationIntegrationTest`.
- Completion criteria: fresh integration passes, generated C has no unused
  `g_njx_wrapper_*` or `neko_njx_tramp_*` declarations, and shape-specialized
  call_stub dispatchers remain present.
- Completion evidence 2026-05-20: removed the unused compiled-entry trampoline
  emitter, per-shape `neko_njx_tramp_*` declarations, `g_njx_wrapper_*`
  globals, and unused runtime/frame-layout helper code from
  `NativeToJavaInvokeEmitter`. Focused generator/audit tests passed
  (`artifact://287`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://289`). Generated C under
  `build/neko-native-work/run-12921477179290/` contains no
  `g_njx_wrapper*`, `neko_njx_tramp_*`, or `neko_njx_runtime_t` symbols while
  retaining shape-specialized `neko_njx_*` call_stub dispatchers for the same
  Method*/entry targets.

### [rejected] NPT-3r: Runtime P12 remove unused virtual icache declared-mid argument

- Scope: remove the virtual/interface `neko_icache_dispatch` declared-method
  `jmethodID` parameter because the current generic dispatch path resolves the
  concrete target from receiver `Klass*` plus callsite name/descriptor metadata
  and never dereferences the declared id. This must not change receiver-null
  handling, exact-method resolution, Method*/entry target selection, PIC
  storage, or call_stub dispatch.
- Required evidence: source/generator proof that the parameter is used only as
  a null precondition and diagnostic value, and generated C proves callsites no
  longer bind/load a declared `jmethodID` solely for `neko_icache_dispatch`.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://296`), but fresh `NativeObfuscationIntegrationTest` failed
  (`artifact://298`) in `nativeObfuscation_randomRuntimeStableTenRuns` with
  an obfusjack native timeout after 45s. The timed-out run reached only the
  platform-thread section in
  `neko-test/build/test-native/native_obfusjack_stability_9.stdout.log`, so
  the declared-`jmethodID` removal was reverted before any implementation
  checkpoint.

### [rejected] NPT-3s: Runtime P12 outline virtual icache miss resolver

- Scope: split only the cold virtual/interface `neko_icache_dispatch` miss
  resolution block into a `cold,noinline` helper. The hot dispatch function
  must keep receiver null/meta checks, receiver `Klass*` key computation, PIC
  lookup, direct-C hits, direct-NJX hits, and the same Method*/entry/call_stub
  target path. No owner/name/descriptor-specific native implementation may be
  introduced.
- Required evidence: generated-C proof that `neko_icache_dispatch` contains
  the PIC hit path and calls `neko_icache_dispatch_miss` only after a miss,
  while the miss helper still performs the existing exact receiver method
  resolution and call_stub dispatch.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://307`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://309`), but direct parity in
  `build/native-run-tmp/parity-p10s/` failed the no-regression gate. TEST
  native Calc was `137/134/138/136/139 ms` (median `137 ms`, worse than
  NPT-3h `134 ms`), and obfusjack native was Seq `18/18/19/18/18 ms`
  (median `18 ms`, worse than `17 ms`) and Platform `53/55/55/42/52 ms`
  (median `53 ms`, worse than `50 ms`). The cold-miss outline source change
  was reverted before any implementation checkpoint.

### [rejected] NPT-3t: Runtime P11 direct-oop handle fast-slot prediction

- Scope: add generic branch prediction only to the `neko_direct_oop_to_handle`
  active JNIHandleBlock slot availability check. The helper must still push
  the same raw oop into the same active JNIHandleBlock slot, update `_top` and
  `_last` exactly as before, allocate an overflow block only on the same
  overflow condition, and hard-abort on the same unavailable paths.
- Required evidence: source/generated-C proof that only the generic
  `top < g_neko_jnih_block_capacity` predicate is marked as the expected fast
  path and no owner/name/descriptor-specific method replacement or handle
  semantics change is introduced.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://315`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://317`), but direct parity in
  `build/native-run-tmp/parity-p10t/` did not complete: the fifth obfusjack
  native run timed out after 180s. Completed runs did not justify retaining
  the change: TEST native Calc median was `134 ms`, but obfusjack native Seq
  completed values were `17/18/18/17 ms` and the run timed out before the
  required five-run median. The branch-prediction source change was reverted
  before any implementation checkpoint.

### [rejected] NPT-3u: Runtime P10 no-handle NJX for static primitive shapes

- Scope: extend the existing descriptor-proven no-handle-window policy to
  native-to-Java call_stub dispatchers only for static shapes with primitive
  arguments and primitive/void returns. The dispatcher must still call the
  original Method*/entry through HotSpot call_stub, preserve JavaCallWrapper
  anchor fields, thread-state handling, exception handling, and all reference
  shapes' handle save/install/restore behavior.
- Required evidence: generated-C proof that only `S:*` shapes with no `L`
  argument and no `L` return omit `neko_handle_save`,
  `neko_njx_install_java_handles`, `neko_njx_restore_java_handles`, and
  `neko_handle_restore`; virtual, object-arg, and object-return NJX shapes must
  retain the handle frame.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://325`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://327`), but direct parity in
  `build/native-run-tmp/parity-p10u/` did not complete: the fifth obfusjack
  native run timed out after 180s. Completed TEST native Calc median was
  `134 ms`, but obfusjack native completed values were Seq `17/18/18/17 ms`,
  Platform `52/51/51/51 ms`, and Virtual `44/42/47/45 ms`; the incomplete
  obfusjack run fails the required no-regression gate. The no-handle NJX
  source change was reverted before any implementation checkpoint.

### [rejected] NPT-3v: Runtime P10 skip empty JavaFrameAnchor copy

- Scope: optimize only generic NJX call_stub JavaFrameAnchor bookkeeping.
  When all saved JavaThread anchor slots are already null (`saved_sp`,
  `saved_pc`, and `saved_fp`), skip copying those nulls into the already
  zeroed JavaCallWrapper anchor and skip clearing the already-empty thread
  anchor. Keep the existing full JavaCallWrapper `memset`, Method*/entry
  target, call_stub invocation, parameters, result buffer, handle frame,
  thread-state transitions, exception handling, and final anchor restore.
- Required evidence: OpenJDK 21 JavaCallWrapper/JavaFrameAnchor source shows
  empty anchors are represented by null anchor slots and `clear()` writes nulls;
  generated C proves only the pre-call anchor copy/clear block is conditional
  on null slots, with no owner/name/descriptor-specific native replacement.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://338`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://340`), but direct parity in
  `build/native-run-tmp/parity-p10v/` failed the no-regression gate. TEST
  native Calc was `136/137/136/137/140 ms` (median `137 ms`, worse than
  NPT-3h `134 ms`), and obfusjack native Virtual was `47/46/48/46/40 ms`
  (median `46 ms`, worse than `44 ms`) while Seq stayed median `17 ms` and
  Platform stayed median `50 ms`. The anchor fast-path source change was
  reverted before any implementation checkpoint.

### [rejected] NPT-3w: Runtime P10 shrink NJX stack handle block buffer

- Scope: reduce only the fixed stack reserve used for NJX call_stub's
  temporary JNIHandleBlock from 512 bytes to 384 bytes. Runtime still checks
  `stack_handles_size >= sizeof_JNIHandleBlock` before using the stack block
  and falls back to the existing heap block path when a JVM layout is larger.
  Current validation logs show the JDK 21 `JNIHandleBlock` layout is 296 bytes,
  so 384 preserves the stack path for the measured runtime while reducing each
  NJX dispatcher frame footprint. Method*/entry target selection, call_stub
  invocation, JavaCallWrapper, thread state, exception handling, and handle
  restore semantics must remain unchanged.
- Required evidence: source/generated-C proof that only
  `__njx_handle_buf` size changed, layout logs show `blk_size=296`, and no
  owner/name/descriptor-specific native replacement is introduced.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://346`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://348`), but direct parity in
  `build/native-run-tmp/parity-p10w/` failed the no-regression gate. TEST
  native Calc stayed median `134 ms`, and obfusjack native Seq stayed median
  `17 ms`, but Platform regressed to median `52 ms` vs NPT-3h `50 ms`, and
  Virtual regressed to median `46 ms` vs `44 ms`. The 384-byte NJX stack
  handle buffer source change was reverted before any implementation
  checkpoint.

### [rejected] NPT-3x: Runtime P10 use cached patch debug flag in handle resolver

- Scope: replace the remaining hot `getenv("NEKO_PATCH_DEBUG")` checks inside
  `neko_handle_oop` with the existing cached `NEKO_PATCH_DEBUG` macro. This
  must only change debug gating; direct-oop classification, ZGC bootstrap
  handling, local/global handle resolution, GC barriers, Method*/entry target
  selection, call_stub invocation, and exception behavior must remain
  unchanged.
- Required evidence: generated-C proof that `neko_handle_oop` no longer calls
  `getenv` on the hot handle path and still emits the same diagnostic blocks
  behind `NEKO_PATCH_DEBUG`, with no owner/name/descriptor-specific native
  replacement.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://356`), but fresh `NativeObfuscationIntegrationTest` failed
  (`artifact://358`) with SIGSEGV in obfusjack runs; the debug runtime log
  wrote `hs_err_pid3307640.log` and crashed after `merged=579.0`. The handle
  debug-cache source change was reverted before any implementation checkpoint.

### [x] NPT-3y: Runtime P10 inline cached interpreted-entry lookup

- Scope: optimize only the generated `neko_bound_method_i_entry_ref` path used
  by static/direct and helper NJX callsites before entering HotSpot call_stub.
  After bind-time has populated the interpreted-entry slot, generated code
  should read the cached slot directly and call `neko_bound_method_i_entry`
  only on the existing null/missing-entry path. This preserves the same
  original JVM Method*/entry target and does not replace any method body.
- Required evidence: source/generated-C proof that callsites still pass the
  same `g_mptr_*` and `g_mientry_*` slots to call_stub dispatchers, and the
  helper remains the fail-closed path for null entry slots.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Completion evidence 2026-05-21: `neko_bound_method_i_entry_ref(ref)` now
  reads the cached interpreted-entry slot only when both cached `Method*` and
  interpreted-entry slots are populated; otherwise it calls the existing
  fail-closed `neko_bound_method_i_entry` helper. Focused generator/audit tests
  passed with `./gradlew :neko-test:test --rerun-tasks --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL in 7s`). Fresh
  `NativeObfuscationIntegrationTest` passed (`BUILD SUCCESSFUL in 41s`). Direct
  parity logs in `build/native-run-tmp/parity-p10y/` completed five runs each
  without fatal markers: TEST original/native Calc medians `10 ms` / `87 ms`;
  obfusjack original/native medians Seq `2 ms` / `17 ms`, Platform `26 ms` /
  `43 ms`, Virtual `12 ms` / `36 ms`. Generated C in
  `build/neko-native-work/run-3262720662482/` contains the cached-entry macro in
  `neko_native_support.c` and `neko_native_impl_prelude.h`; callsites still use
  `neko_bound_method_i_entry_ref(&g_method_entry_ref_...)`; the fallback helper
  remains referenced for null slots. Executable forbidden-marker inspection
  found no `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, JNI call wrappers, array JNI
  wrappers, or JVMTI markers; remaining `FindClass`/`NewStringUTF`/`NewObject`
  strings are comments or `JVM_FindClass*` symbol names. No new `hs_err` files
  were created during this validation; newest root `hs_err_pid*.log` still
  predates the run.


### [rejected] NPT-3z: Runtime P6 primitive-array cold diagnostic outlining

- Scope: optimize only generated primitive array fast helpers by moving the
  unreachable-success-path diagnostic `fprintf`/`abort` blocks for primitive
  `xALOAD`/`xASTORE` direct-path failures into one cold, noinline, noreturn
  helper. The hot helper success checks, null/bounds semantics, primitive array
  layout reads, GC handling, and hard-abort behavior must remain unchanged.
- Required evidence: source/generated-C proof that primitive array hot helper
  bodies call the cold helper on failure instead of embedding `fprintf`, and
  generated-C proof that no JNI/JVMTI/fallback/original-bytecode path is added.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions; same-run
  timings improve or do not regress relative to NPT-3y; P6 remains open for
  other hot-helper diagnostic blocks not covered by this narrow row.
- Rejection evidence 2026-05-21: source changed only primitive array direct-path
  failure diagnostics into a shared cold, noinline, noreturn helper. Focused
  generator/audit tests passed and `NativeObfuscationIntegrationTest` passed,
  but direct parity in `build/native-run-tmp/parity-p10z/` regressed relative
  to NPT-3y: TEST native Calc median `90 ms` vs `87 ms`; obfusjack native Seq
  `18 ms` vs `17 ms`, Platform `45 ms` vs `43 ms`, and Virtual `42 ms` vs
  `36 ms`. The source change was reverted before checkpoint.


### [rejected] NPT-3aa: Runtime P7 remove CHECKCAST success-path exception check

- Scope: optimize only translated `CHECKCAST` success paths. `neko_fast_is_instance_of`
  is classified as `never_sets_pending_exception` on normal return and hard-abort
  on missing metadata; the cast-failure branch calls the existing implicit
  exception raiser and immediately jumps to `__neko_exception_exit`. Therefore
  the trailing `if (!neko_exception_check(env)) { PUSH_O(obj); }` after a
  successful cast can become `PUSH_O(obj)` without changing JVM exception
  ordering, handler selection, pending-exception visibility, or ABI behavior.
- Required evidence: source/generated-C proof that only `CHECKCAST` success
  path checks are removed; failure paths still raise `ClassCastException` and
  jump to exception exit; no NJX, Java-calling, reflection, MethodHandle,
  direct translated call, or try/catch boundary checks are removed.
- Validation command or runtime target: focused translator/generator tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: generated C shows `CHECKCAST` success pushes directly,
  runtime and parity runs have no fatal/forbidden-marker regressions, and same-run
  timings improve or do not regress relative to NPT-3y.
- Rejection evidence 2026-05-21: source changed only the `CHECKCAST` success
  continuation to push directly after `neko_fast_is_instance_of`; the cast
  failure branch still raised `ClassCastException` and jumped to
  `__neko_exception_exit`. Focused translator/generator tests passed and
  `NativeObfuscationIntegrationTest` passed, but direct parity in
  `build/native-run-tmp/parity-p10aa/summary-p10aa.json` failed the no-regression
  gate relative to NPT-3y: TEST native Calc median `86 ms` vs `87 ms` improved,
  but obfusjack native Platform regressed to `45 ms` vs `43 ms` and Virtual
  regressed to `42 ms` vs `36 ms`; Seq stayed `17 ms`. The source change was
  reverted before checkpoint.

### [x] NPT-4: Compile-time post-P41 bottleneck selection

- Scope: choose the next native compile-time optimization only from the NPT-1
  fresh manifests and generated-source inspection.
- Required evidence: top compile-sum/max/wall-time rows for TEST/test21/
  SnakeGame/evaluator, generated source snippets for the dominant repeated
  generic code shape, rejected-shape comparison against P27/P29/P33/P34/P36/P37/
  P40, and a proof that the proposed change is source-shape or build-architecture
  generic rather than fixture-specific.
- Validation command or runtime target: read-only inspection of NPT-1 artifacts;
  no implementation in this row.
- Completion criteria: one concrete compile-time implementation row is added to
  `.plan/native-compile-parallelization-2026-05-17.md` with scope, required
  evidence, validation commands, and completion criteria before code changes.
- Dependency: NPT-1.
- Completion evidence 2026-05-21: selected `.plan/native-compile-parallelization-2026-05-17.md`
  P42 from the fresh NPT-1 post-P41 baseline. The baseline summary shows TEST
  compile sum/max `25439ms/1216ms`, obfusjack `41138ms/2053ms`, SnakeGame
  `9816ms/770ms`, and evaluator `45750ms/1629ms`. Source inspection of the
  top generated impl files found exact `if (!neko_exception_check(env)) {`
  continuation guards still widespread after P41: TEST `77`, obfusjack `301`
  with `82` in `neko_native_impl_39.c`, SnakeGame `61`, and evaluator `239`.
  Larger top-file repeated shapes were rejected by prior rows and are not
  selected: P33 string literal descriptors, P40 current-owner class macro,
  P36 method-pointer macro, P29 static setter refs, P37 fieldID elision, P27
  nested primitive-store fusion, and P34 helper shard rebalancing. P42 records
  a source-shape-only macro compaction target that preserves every exception
  check and does not implement the rejected P7/NPT-3aa check-removal route.

### [rejected] NPT-5: Implement the first post-P41 compile-time optimization row

- Scope: implement only the concrete row selected by NPT-4, preserving Zig,
  `-O3`, current target flags, native coverage, no-JNI/no-JVMTI policy, and
  runtime semantics unless the selected row explicitly proves an equivalent
  behavior-preserving code-shape change.
- Required evidence: the selected row in
  `.plan/native-compile-parallelization-2026-05-17.md`, focused tests covering
  the generated source contract, fresh manifests, direct runtime output, and
  forbidden-marker inspection.
- Validation command or runtime target: focused generator/unit tests,
  generated-C hot-path audit, fresh native-only TEST/test21/SnakeGame/evaluator
  generation, direct runtime smoke, timing comparison against NPT-1, newest
  `hs_err` scan, and forbidden-marker scan.
- Completion criteria: at least one build-size/top-tail/wall-time metric improves
  without runtime regression, all fresh artifacts report `translated>0
  rejected=0`, and no rejected prior shape or fallback mechanism is reintroduced.
- Dependency: NPT-4.
- Rejection evidence 2026-05-21: selected compile-time row P42 was implemented
  and fully validated but rejected. Focused Gradle tests passed, fresh
  native-only generation under `build/p42-native-validation/` reported
  TEST/obfusjack/SnakeGame/evaluator translated/rejected rows `49/0`, `93/0`,
  `18/0`, and `122/0`, direct runtime was clean with no fresh `hs_err`, and
  generated-C inspection found `NEKO_IF_NO_EXCEPTION(env)` at rewritten sites
  with old exact continuation guards at `0`. The row failed completion because
  compile metrics were mixed and obfusjack no-debug runtime repeats regressed
  versus NPT-3y. P42 source/test changes were reverted before checkpoint; a new
  compile-time selection row is required before another implementation attempt.

### [x] NPT-5a: Select the next post-P42 compile-time optimization row

- Scope: choose the next native compile-time optimization from fresh post-P41/
  post-P42 evidence without retrying the rejected P42 macro-only guard shape or
  any previously rejected body/runtime shape.
- Required evidence: generated prelude/header sizes, implementation include
  counts, per-source compile tails from NPT-1/P42 artifacts, read-only
  generated-source inspection identifying the repeated generic declaration
  surface, and explicit rejection-boundary comparison against P27/P29/P33/P34/
  P36/P37/P40/P42 and NPT-3aa.
- Validation command or runtime target: read-only artifact/source inspection; no
  Gradle or runtime command for the selection row.
- Completion criteria: the owning compile plan records one concrete P43 row with
  scope, required evidence, validation commands, completion criteria, and a
  dependency/order reason before implementation starts.
- Dependency reason: NPT-2 and runtime rows remain open, but this selected row
  changes only generated source packaging/declaration inclusion for compile-time
  work. It must not alter runtime helper behavior or translated statement
  bodies, and the implementation row remains blocked on fresh runtime smoke
  validation before acceptance.
- Selection evidence 2026-05-21: selected
  `.plan/native-compile-parallelization-2026-05-17.md` P43. Read-only
  inspection found the canonical generated implementation prelude is included by
  almost every implementation file and is large in the NPT-1 artifacts: TEST
  about `353094` bytes/`6768` lines, obfusjack `410699` bytes/`7970` lines,
  SnakeGame `293653` bytes/`5569` lines, and evaluator `388879` bytes/`7251`
  lines. Obfusjack's hot `neko_native_impl_39.c` includes the full prelude but
  uses only subsets of numbered method-entry refs, method-id refs, field refs,
  class refs, inline-cache site macros, and method-pointer globals. P43 targets
  per-implementation sliced contract headers and explicitly does not change
  translated bodies, exception checks, strings, current-owner binding,
  method-pointer operands, static setters, field binding semantics, nested
  stores, or helper shard size.

### [x] NPT-5b: Implement P43 sliced implementation contract headers

- Scope: implement only P43's source-packaging change: generate per-impl sliced
  prelude headers for translated implementation sources while retaining the
  canonical full `neko_native_impl_prelude.h` for support/late-support shards.
- Required evidence: focused source-set tests, generated manifest/header-size
  data, fresh native-only TEST/obfusjack/SnakeGame/evaluator generation,
  generated-C inspection proving implementation body text is unchanged except
  include filenames and sufficient referenced declarations are present, direct
  runtime output, forbidden-marker scan, and newest `hs_err` scan.
- Validation command or runtime target: after explicit permission for
  repository `./gradlew`, run focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, then fresh native-only generation with
  `.plan/native-only-full.yml`, direct TEST/obfusjack/evaluator/SnakeGame smoke
  with `-Djava.io.tmpdir=build/native-run-tmp`, manifest/header comparison, and
  forbidden-marker inspection.
- Completion criteria: refreshed artifacts report `translated>0 rejected=0`;
  compile/link succeeds without fallback; per-impl header parse volume and at
  least one build tail/compile-sum/wall-time metric improves or remains within
  noise with no runtime regression; support shards still use the canonical full
  contract; no runtime semantics, compiler flags, native coverage, JNI/JVMTI
  fallback, skip-on-error behavior, original-bytecode fallback, or stale proof
  is introduced.
- Dependency: NPT-5a and P43.
- Completion evidence 2026-05-21: P43 is implemented and validated. The code
  now emits `neko_native_impl_N_prelude.h` for implementation sources and keeps
  `neko_native_impl_prelude.h` for support shards. A dependency-closure fix was
  added after focused audit exposed a generic sliced-macro dependency failure:
  retained `neko_concat_accumulate_string` needed `g_off_*` externs even though
  the implementation body referenced only the macro. Focused Gradle validation
  passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest -Djava.io.tmpdir=build/native-run-tmp`.
  Fresh native-only generation under `build/p43-native-validation/` produced
  TEST/obfusjack/SnakeGame/evaluator translated/rejected rows `49/0`, `93/0`,
  `18/0`, and `122/0`. Fresh run dirs
  `build/neko-native-work/run-8052435246015`,
  `run-8067151202699`, `run-8077119367912`, and
  `run-8091067486053` recorded generated C counts `67`, `113`, `34`, and
  `143`, and implementation header counts/sliced counts `50/49`, `94/93`,
  `19/18`, and `123/122`. Representative sliced headers are materially smaller
  than the full contract: TEST impl0 `299509` vs `356323` bytes, obfusjack
  impl39 `333519` vs `416657`, SnakeGame impl0 `275269` vs `295686`, evaluator
  impl104 `298320` vs `393898`. Direct runtime smoke with
  `-XX:+PerfDisableSharedMem -Djava.io.tmpdir=build/native-run-tmp` passed for
  TEST, obfusjack, and evaluator; SnakeGame preserved the expected headless GUI
  exception. Forbidden-marker inspection found no executable JNI/JVMTI/fallback
  markers in the fresh generated C/header/property set, and no fresh
  `hs_err_pid*.log` appeared in the validation build/runtime directories.

### [ ] NPT-6: Sync final validation plans after the first accepted optimization

- Scope: update `.plan/native-full-validation.md`, `.plan/native-full-repair.md`,
  and the owning runtime/compile-time source plan with the fresh evidence from
  the first accepted implementation row.
- Required evidence: commands, exit codes, output paths, generated C/native
  inspection, runtime outputs, timing rows, and rejection criteria status.
- Validation command or runtime target: no new runtime command beyond the owning
  row; this is an audit-sync row.
- Completion criteria: no stale checkbox is marked complete; every updated
  checkbox has a matching fresh artifact and evidence path.

### [x] NPT-3ab: Runtime P12 virtual/interface PIC-hit audit

- Scope: add only default-off diagnostic counters for the existing
  `neko_icache_dispatch` virtual/interface PIC path. The audit may count
  direct-C hits, direct-NJX hits, misses, translated-stub stores, direct-NJX
  stores, and unresolved exits in the existing `NEKO_DIRECT_DEBUG=1` stats
  summary.
- Required evidence: current source shows direct-C and direct-NJX hit branches,
  interleaved miss resolution, and only a per-site `miss_count`; previous P12
  declared-mid removal and cold-miss outlining were rejected. The new audit must
  prove the current hit/miss/target-kind mix before any further dispatch shape
  change.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew` after permission, fresh
  `NativeObfuscationIntegrationTest`, default-off TEST/obfusjack parity runs,
  one opt-in `NEKO_DIRECT_DEBUG=1` diagnostic runtime, and generated-C
  inspection.
- Completion criteria: generated C shows only audit counters/logging added
  around existing branches; default-off parity has no accepted regression; the
  opt-in diagnostic run emits useful PIC hit/miss/target-kind counts; no
  receiver-key computation, PIC size, target selection, miss resolution,
  direct-C dispatch, direct-NJX call-stub dispatch, handle scope, exception
  behavior, JNI/JVMTI fallback, skip-on-error behavior, or original-bytecode
  fallback changes.
- Completion evidence: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed; fresh
  `NativeObfuscationIntegrationTest` passed; opt-in TEST audit artifact
  `build/p12-icache-audit/TEST-native-audit.jar` generated with
  `translated=49 rejected=0` from
  `build/neko-native-work/run-9527959662306`; its build manifest recorded
  `icache.audit.build=true` and compile commands containing
  `-DNEKO_ICACHE_AUDIT=1`. The opt-in `NEKO_DIRECT_DEBUG=1` runtime completed
  and emitted counters including `icache_direct_njx_hit=519999`,
  `icache_miss=43`, `icache_direct_njx_store=44`, `resolve_failed=0`, and
  `icache_unresolved=0`; a second loaded native segment reported
  `icache_direct_njx_hit=20`, `icache_miss=76`, `icache_direct_njx_store=77`,
  `resolve_failed=0`, `icache_unresolved=0`. Generated-C inspection found no
  executable forbidden JNI/JVMTI markers in the icache support path; remaining
  support-file JNI strings were comments or internal JVM symbol names.
- Outcome: the audit is accepted as measurement infrastructure. The recorded
  runtime mix is dominated by direct-NJX PIC hits, so no further P12 cold-miss
  outlining or dispatch-shape change is justified without new evidence.
- Dependency update: P2/P4 GC strict acceptance remains blocked by the fresh
  ZGC mask-publication abort (`ZGC oop load masks unavailable addr=0x0
  good=0x0`). This P12 row is measurement-only runtime hot-path debugging to
  guide the next performance change; it does not mark any GC blocker complete.

### [-] NPT-3ac: Runtime P13 raw-function flattening validation

- Scope: validate the existing structural raw-function flattening heuristic in
  `CCodeGenerator` without changing runtime code. The current source emits
  `NEKO_FLATTEN NEKO_HOT` only for translated raw bodies whose statement count
  is at or below `MAX_FLATTEN_STATEMENTS`, and emits `NEKO_HOT` without
  `NEKO_FLATTEN` for larger translated bodies.
- Required evidence: source line evidence for `MAX_FLATTEN_STATEMENTS`,
  `isLargeImplementation`, and conditional raw-function emission; fresh
  generated-C evidence showing both flattened and non-flattened translated
  bodies selected by statement count; runtime/performance evidence that the
  current source does not regress the accepted baseline.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew` after permission, fresh TEST/obfusjack native
  generation and runtime, generated-C inspection, and performance capture.
- Completion criteria: no owner/name/descriptor/sample-specific selection,
  no CFF block change, no bytecode translation change, no JNI/JVMTI fallback,
  no skip/original-bytecode fallback, and no accepted runtime/performance
  regression.
- Rejected validation 2026-05-21: the structural heuristic is generic and was
  present in source, but this row is not accepted as a performance win. Source
  evidence: `CCodeGenerator.MAX_FLATTEN_STATEMENTS = 128`,
  `fn.body().size() > MAX_FLATTEN_STATEMENTS`, and conditional raw emission at
  `CCodeGenerator.java:2150-2157`. Fresh TEST native generation translated
  `49` methods with `rejected=0`; generated C showed large
  `neko_native_impl_0.c` emitted as `NEKO_HOT` without `NEKO_FLATTEN` while
  small bodies such as `neko_native_impl_8.c` kept
  `NEKO_FLATTEN NEKO_HOT`. Fresh obfusjack native generation translated `93`
  methods with `rejected=0`; generated C showed large bodies
  `neko_native_impl_39.c` (`1085` lines), `neko_native_impl_44.c` (`181`
  lines), and `neko_native_impl_46.c` (`146` lines) emitted as `NEKO_HOT`
  without `NEKO_FLATTEN`, while small bodies such as `neko_native_impl_13.c`
  (`38` lines) and `neko_native_impl_0.c` (`30` lines) kept
  `NEKO_FLATTEN NEKO_HOT`. Runtime evidence: TEST native completed five fresh
  runs with Calc `86/90/85/90/83 ms`, median `86 ms`, which is below the
  accepted NPT-3y TEST median `87 ms`. Obfusjack native completed fresh runs
  with Platform `45/48/50/45/45 ms`, Virtual `47/37/39/48/43 ms`, and Seq
  `17/19/17/17/17 ms`; medians were Platform `45 ms`, Virtual `43 ms`, and
  Seq `17 ms`. One 120s run timed out after printing `=== All tests completed
  ===`; a 180s rerun exited `0` with Platform `44 ms`, Virtual `42 ms`, and
  Seq `18 ms`, so no crash/fatal/JNI fallback was observed. The row remains
  rejected because obfusjack Platform and Virtual regressed versus the accepted
  baseline and missed the performance gate (`Platform <= 44 ms`,
  `Virtual <= 35 ms`, `Seq <= 14 ms`). Do not change the flattening threshold
  or selection shape without new generated-code and compiler-layout evidence.

### [x] NPT-3ad: Runtime P14 opt-in native compiler diagnostics

- Scope: add only an optional native compiler diagnostics mode controlled by
  `NEKO_NATIVE_OPT_DIAGNOSTICS=1`. The mode may add clang/zig optimization
  record flags and manifest entries for per-source diagnostic files.
- Required evidence: `NativeBuildEngine` currently records debug and icache
  audit build flags and assembles common compile flags, but has no
  optimization-diagnostics flag or per-source diagnostics manifest paths.
- Validation command or runtime target: focused Gradle tests with repository
  `./gradlew` after permission, one normal native generation proving default
  compile commands are unchanged and diagnostics disabled, one opt-in native
  generation proving diagnostics enabled in the manifest/compile commands and
  producing inspectable compiler diagnostics or optimization-record files, plus
  generated-C forbidden-marker inspection.
- Completion criteria: default builds do not include diagnostics flags and do
  not change generated C, translated bytecode, runtime dispatch, JNI/JVMTI
  behavior, fallback behavior, or release compile/link flags; opt-in builds
  expose enough compiler output to guide P4/P6/P8/P13 without making
  diagnostics mandatory for normal artifacts.
- Completion evidence: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Normal TEST generation
  `build/neko-native-work/run-9988143205398` produced `translated=49
  rejected=0`, manifest `opt.diagnostics.build=false`, and no
  `-fsave-optimization-record` or `-foptimization-record-file` compile flags.
  The first opt-in attempt rejected unsupported
  `-foptimization-record-format=yaml`; the accepted narrowed implementation
  generated `build/p14-opt-diagnostics/TEST-native-opt-diagnostics.jar` from
  `build/neko-native-work/run-10032888234072` with `translated=49 rejected=0`,
  manifest `opt.diagnostics.build=true`, compile commands containing
  `-fsave-optimization-record` and per-source `-foptimization-record-file=...`,
  and 67 `.opt.yaml` files under `opt-diagnostics/linux_x64`. Representative
  `neko_native_impl_0.opt.yaml` records inline pass diagnostics such as
  `AlwaysInline`. The opt-in TEST jar ran successfully with `Calc: 83ms`.
  Generated-C inspection found no executable forbidden JNI/JVMTI/fallback
  markers in the checked support/icache paths; remaining support-file JNI
  strings were comments or internal JVM symbol names.
- Outcome: P14 is accepted as opt-in compiler evidence infrastructure. It is
  not itself a runtime optimization, but it gives the next P13/P4 work a
  concrete inlining/optimization record without perturbing normal artifacts.

### [x] NPT-3ae: Runtime ZGC barrier readiness capability invariant

- Scope: fix only the ZGC GC-barrier readiness predicate so readiness means
  translated oop load/store paths have an executable ZGC barrier capability.
  The change must not add JNI fallback, skip behavior, original-bytecode
  fallback, sample-derived ZGC masks, owner/name/descriptor selection, or any
  new Java helper.
- Required evidence: fresh strict ZGC logs show `ZGlobalsForVMStructs`
  metadata is discovered, but the published values are all zero and `z_lrb`,
  `z_array`, and `z_store` are all `(nil)` while the current runtime still
  reports `gc barrier: ready=1 kind=3`. Runtime then aborts later with
  `ZGC oop load masks unavailable addr=0x0 good=0x0`. Source evidence:
  `neko_gc_barrier_layout_ready` treats ZGC instance pointer plus offsets as
  ready even when no callable runtime barrier and no nonzero live masks are
  available.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with patch logging, and generated-C/log
  inspection.
- Completion criteria: ZGC readiness is capability-based: either callable ZGC
  runtime/CompilerToVM barriers are present, or the inline path has nonzero
  live masks sufficient for translated oop loads. If neither capability is
  present, the runtime fails closed before selecting translated oop paths and
  does not reach a later missing-mask abort, JNI fallback, skip, or original
  bytecode fallback.
- Completion evidence 2026-05-21: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh TEST native generation
  produced `build/npt-3ae-zgc/TEST-native.jar` from
  `build/neko-native-work/run-10986264548170` with `translated=49 rejected=0`
  and `libneko_linux_x64.so` size `1088536` bytes. Generated C contains the
  new capability predicate in `neko_native_support_helpers_2.c`: it reads
  live/frozen ZGC address, load-good, load-bad, store-good, and store-bad masks
  and requires all five to be nonzero before accepting the inline VMStruct
  path. Strict ZGC TEST with `NEKO_PATCH_DEBUG=1 -XX:+UseZGC
  -XX:+UnlockDiagnosticVMOptions -XX:+ZVerifyViews` aborted at layout
  initialization with `gc barrier: ready=0 kind=3` and
  `gc barrier path not ready for kind=3`; the previous later
  `ZGC oop load masks unavailable addr=0x0 good=0x0` abort did not appear.
  The same generated TEST native jar completed under the default collector with
  `Calc: 89ms`. The row is accepted as a fail-closed capability-invariant fix;
  P2/P4 GC strict completion still requires a real callable ZGC barrier or
  nonzero-mask capability on this JDK.

### [x] NPT-3af: Runtime ZGC CompilerToVM and long-constant capability audit

- Scope: add only generic, default-off patch-debug evidence collection for
  HotSpot-published ZGC capability sources that are not currently audited:
  `gHotSpotVMLongConstants`, `CompilerToVM::Data` Z barrier fields, and
  thread-local ZGC mask offsets such as `thread_address_bad_mask_offset`.
  This row must not call CodeBlob runtime stubs, select a new ZGC barrier path,
  derive sample masks, add JNI/JVMTI/helper fallback, or alter translated
  runtime behavior outside opt-in diagnostics.
- Required evidence: post-NPT-3ae strict ZGC TEST fails closed at native layout
  initialization with `gc barrier: ready=0 kind=3`; local `libjvm.so` strings
  contain `thread_address_bad_mask_offset` and `ZBarrierSetRuntime_*` names,
  while `nm -D` does not expose matching dynamic symbols. Current source walks
  `gHotSpotVMStructs`, `gHotSpotVMTypes`, and `gHotSpotVMIntConstants`, but
  has no `gHotSpotVMLongConstants` walker and current patch-debug logs do not
  prove whether CompilerToVM or long-constant entries are reachable.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with `NEKO_PATCH_DEBUG=1`, and generated-C/log
  inspection.
- Completion criteria: fresh logs either prove a generic, HotSpot-published,
  ABI-safe source for ZGC masks/barriers exists and identify its exact service
  table path, or prove that this JDK does not expose such a source through the
  runtime's permitted VMStructs/VMTypes/VMIntConstants/VMLongConstants/dlsym
  paths. The runtime must still fail closed under ZGC until a later row records
  complete ABI evidence and implements an executable barrier capability.
- Completion evidence 2026-05-21: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed after adding the diagnostics.
  Fresh TEST native generation produced `build/npt-3af-zgc/TEST-native.jar`
  from `build/neko-native-work/run-11515458382165` with `translated=49
  rejected=0` and `libneko_linux_x64.so` size `1090200` bytes. Generated C
  contains the new `neko_walk_vm_long_constants` audit and VMStruct/VMInt
  zcap summaries. Strict ZGC TEST with `NEKO_PATCH_DEBUG=1 -XX:+UseZGC
  -XX:+UnlockDiagnosticVMOptions -XX:+ZVerifyViews` still failed closed at
  native layout initialization with `gc barrier: ready=0 kind=3`. The fresh
  stderr log shows standard VMStructs expose only 11 `ZGlobalsForVMStructs`
  zcap entries and `compilertovm_zcap_matches=0`; VMIntConstants expose
  `vmint zcap matches=0`; VMLongConstants expose only four zero-valued
  `ZAddress*` constants. The same jar completed under the default collector
  with `Calc: 92ms`. Independent local OpenJDK source inspection found the
  missing `CompilerToVM::Data` Z barrier entries and
  `thread_address_bad_mask_offset` in JVMCI VMStruct tables
  (`jvmciHotSpotVMStructs` / `jvmciHotSpotVMIntConstants` /
  `jvmciHotSpotVMLongConstants`), so the next implementation row must add a
  generic JVMCI serviceability-table walker before selecting any ZGC barrier
  capability.

### [x] NPT-3ag: Runtime JVMCI serviceability-table ZGC binding

- Scope: add a generic native walker for the JVMCI HotSpot serviceability
  tables that are already exported by libjvm on JVMCI-capable builds:
  `jvmciHotSpotVMStructs`, `jvmciHotSpotVMIntConstants`, and
  `jvmciHotSpotVMLongConstants`. Bind only the ZGC-relevant
  `CompilerToVM::Data` entries that the local OpenJDK source publishes and
  whose ABI already matches the runtime call surface:
  `thread_address_bad_mask_offset` and
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded`. Audit, but do not
  publish as callable, `ZBarrierSetRuntime_load_barrier_on_oop_array` because
  local OpenJDK source shows its ABI is `void(oop*, size_t)` while the current
  runtime typedef is still `void*(void*)`. This row must not treat the
  C1 CodeBlob runtime stubs as callable C ABI functions, must not derive
  sample masks, and must not mark ZGC ready until store/no-store semantics are
  recorded and proven by a later row.
- Required evidence: NPT-3af fresh logs prove the standard
  `gHotSpotVMStructs`/VMInt/VMLong tables do not expose CompilerToVM ZGC
  capability entries on this JDK. Local OpenJDK source shows JVMCI exports
  `jvmciHotSpotVMStructs`, `jvmciHotSpotVMIntConstants`, and
  `jvmciHotSpotVMLongConstants`; `vmStructs_jvmci.cpp` lists
  `CompilerToVM::Data::thread_address_bad_mask_offset`,
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded`, and
  `ZBarrierSetRuntime_load_barrier_on_oop_array`; `XBarrierSetRuntime.hpp`
  proves the field-load ABI matches the current two-argument runtime typedef,
  while the array ABI does not match yet; and
  `jvmciCompilerToVMInit.cpp` initializes those fields under `UseZGC` from
  `XThreadLocalData::address_bad_mask_offset()` and
  `XBarrierSetRuntime::*_addr()`.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with `NEKO_PATCH_DEBUG=1`, generated-C/log
  inspection, and default collector TEST smoke.
- Completion criteria: generated C contains a JVMCI-table walker that uses the
  same VMStruct entry layout offsets as the standard table, fresh strict-ZGC
  logs show whether the JVMCI table is present and whether the expected
  CompilerToVM entries bind or are deliberately left unbound for ABI reasons,
  and the runtime still fails closed unless a complete ZGC barrier capability
  is proven. No JNI fallback,
  JVMTI, original bytecode, skip behavior, or benchmark/sample-specific logic
  may be introduced.
- Completion evidence 2026-05-21: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed after adding the JVMCI walkers.
  Fresh TEST native generation produced `build/npt-3ag-zgc/TEST-native.jar`
  from `build/neko-native-work/run-12028033311558` with `translated=49
  rejected=0` and `libneko_linux_x64.so` size `1093336` bytes. Generated C
  contains `neko_walk_jvmci_vm_structs`, `neko_walk_jvmci_vm_constants`, the
  `z_bad_off` GC-barrier diagnostic, and the corrected
  `off_zglobals_pointer_store_bad_mask = -1` initialization. Strict ZGC TEST
  with patch logging failed closed at layout initialization in both default
  JVMCI state and with `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`;
  both fresh logs report `jvmci vmstructs unavailable: table=(nil)`,
  `jvmci vmint constants unavailable: table=(nil)`, and
  `jvmci vmlong constants unavailable: table=(nil)`, then
  `gc barrier: ready=0 kind=3 ... z_lrb=(nil) z_array=(nil) z_store=(nil)
  z_bad_off=-1`. ELF inspection of the active
  `/usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so` shows no dynamic or
  regular symbols for `jvmciHotSpotVMStructs`, while `strings` still contains
  the names `thread_address_bad_mask_offset` and
  `ZBarrierSetRuntime_load_barrier_on_oop_*`. The same jar completed under the
  default collector with `Calc: 94ms`. The row is accepted as a generic
  dlsym-reachable JVMCI-table audit/binder; this runtime does not expose the
  table, so ZGC strict remains fail-closed.

### [x] NPT-3ah: Runtime stripped JVMCI serviceability-table recovery audit

- Scope: determine whether the active product `libjvm.so` contains a
  recoverable JVMCI VMStructEntry table despite stripping the exported
  `jvmciHotSpotVM*` symbols. This is a read-only audit/prototype row only:
  inspect ELF sections, local OpenJDK layout, and runtime logs; do not add a
  runtime scanner, do not scan arbitrary heap/code memory, do not select a ZGC
  barrier path, and do not change executable behavior until a complete generic
  invariant is recorded.
- Required evidence: NPT-3ag shows `dlsym` cannot resolve
  `jvmciHotSpotVMStructs`, `jvmciHotSpotVMIntConstants`, or
  `jvmciHotSpotVMLongConstants` even with `-XX:+EnableJVMCI`; `nm` and
  `readelf -Ws` show no matching symbols in the active Arch OpenJDK
  `libjvm.so`; `strings` still contains
  `thread_address_bad_mask_offset` and `ZBarrierSetRuntime_load_barrier_on_oop_*`;
  local OpenJDK source defines VMStructEntry layout and the JVMCI table fields.
- Validation command or runtime target: read-only local source and ELF
  inspection, plus optional repository-local prototype output under `build/`
  if needed. No Gradle or runtime artifact is required unless the audit
  becomes an implementation row.
- Completion criteria: either identify a generic, bounded, section-scoped way
  to recover JVMCI VMStructEntry records from the mapped `libjvm.so` image with
  enough evidence for a later implementation row, or record that the product
  JDK does not expose a safe recoverable JVMCI table path and choose the next
  non-ZGC performance/GC blocker.
- Completion evidence 2026-05-21: read-only ELF/source inspection identified a
  bounded recovery path. `readelf -S` shows the active
  `/usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so` contains `.rodata`,
  `.data.rel.ro`, and `.data` sections; `.symtab` is absent and `readelf -Ws`
  shows no `jvmciHotSpotVM*` symbols. `grep -aob` found
  `CompilerToVM::Data` at file/VMA `0xf4c22a`,
  `thread_address_bad_mask_offset` at `0xfb1eb8`,
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` at `0xfb1ed8`,
  and `ZBarrierSetRuntime_load_barrier_on_oop_array` at `0xfb2060`. A
  read-only prototype search for little-endian pointer addends found
  VMStructEntry-shaped records in the data image: entry `0x12f0930` points to
  `CompilerToVM::Data`, `thread_address_bad_mask_offset`, type string `int`,
  `isStatic=1`, and static-address addend `0x1314db0`; entry `0x12f0960`
  points to `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded`, type
  string `address`, and static-address addend `0x1314da8`; entry `0x12f0a80`
  points to `ZBarrierSetRuntime_load_barrier_on_oop_array`, type string
  `address`, and static-address addend `0x1314d78`. Local OpenJDK source
  confirms the VMStructEntry layout is six fields / 48 bytes and the JVMCI
  table emits sentinel-terminated arrays. The next implementation row may add
  a generic libjvm image-section scanner limited to the mapped libjvm
  data/RELRO ranges and these VMStructEntry invariants; it must still keep the
  array barrier unbound until its ABI is corrected.

### [x] NPT-3ai: Runtime stripped JVMCI VMStructEntry scanner

- Scope: implement a generic, bounded native scanner that recovers only
  stripped JVMCI `CompilerToVM::Data` VMStructEntry records from mapped
  `libjvm.so` data/RELRO ranges when the `jvmciHotSpotVM*` export symbols are
  unavailable. VMStructEntry scanning must stay limited to path-tagged
  `libjvm.so` non-executable mappings; VMStruct static slots may additionally
  validate against immediately adjacent anonymous writable data mappings
  created by the loader for `libjvm.so` data. The scanner may bind
  `thread_address_bad_mask_offset` and
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` if their entries
  satisfy the exact VMStructEntry invariant. It must audit but not publish
  `ZBarrierSetRuntime_load_barrier_on_oop_array` until the array ABI row fixes
  the runtime call surface. It must not scan arbitrary process memory, treat
  raw CodeBlob stubs as C ABI calls, derive sample masks, mark ZGC ready, or
  change store/no-store semantics.
- Required evidence: NPT-3ah proves the active stripped `libjvm.so` contains
  VMStructEntry-shaped records in the data image pointing to the exact
  `CompilerToVM::Data` and ZGC field strings, and local OpenJDK source proves
  the entry layout and field ABI for the field-load barrier. NPT-3ag proves
  dlsym-exported JVMCI tables are unavailable at runtime.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with `NEKO_PATCH_DEBUG=1`, generated-C/log
  inspection, and default collector TEST smoke.
- Completion criteria: generated C contains a scanner bounded to mapped
  `libjvm.so` non-executable data/RELRO ranges, adjacent loader-created
  anonymous libjvm data slots, and exact VMStructEntry pointer invariants; fresh
  strict-ZGC logs show the scanner either binds
  `thread_address_bad_mask_offset` and the field-load barrier or proves the
  entries were not recoverable; the array barrier remains unbound with an ABI
  diagnostic; ZGC readiness remains fail-closed until a later complete barrier
  row.
- Completion evidence 2026-05-21: focused generator/audit tests passed. Fresh
  TEST native generation produced
  `build/npt-3ai-zgc/TEST-native.jar` from
  `build/neko-native-work/run-13077085932357` with `translated=49 rejected=0`.
  Strict ZGC TEST with `NEKO_PATCH_DEBUG=1` found bounded libjvm scan, slot,
  and executable ranges, including adjacent anonymous slot ranges, recovered the exact
  `CompilerToVM::Data` VMStructEntry records, bound
  `thread_address_bad_mask_offset` from slot `0x7f2d12b14db0` with value `0`,
  observed a null field-load barrier slot, audited executable target validation
  for future non-null field barrier slots, audited the array barrier as
  not-bound due to the current ABI mismatch, and failed closed with
  `gc barrier: ready=0 kind=3`. Generated-C inspection shows the scanner is
  emitted in `neko_native_support.c` and the bootstrap call is emitted in
  `neko_native_support_helpers_3.c`. Default collector TEST completed with
  `Calc: 91ms`.

### [x] NPT-3aj: JVMCI ZGC barrier slot initialization audit

- Scope: determine why the recovered stripped JVMCI
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` static slot is null
  during strict ZGC native layout initialization even though the
  `CompilerToVM::Data` VMStructEntry record and slot address are recoverable.
  This is a read-only evidence row. It may inspect OpenJDK source, local
  `libjvm.so` symbols/strings, generated native logs, and existing generated C,
  but must not call JVMCI initialization paths, bind raw CodeBlob stubs, mark
  ZGC ready, change store/no-store semantics, or add fallback behavior.
- Required evidence: NPT-3ai proves the VMStructEntry scanner recovers the
  bad-mask static slot and the field-load barrier static slot, but fresh strict
  ZGC logs show the field-load barrier slot value is null while the GC barrier
  path remains fail-closed.
- Validation command or runtime target: source/symbol/log audit only; if a
  candidate runtime path is found, record a later implementation row with its
  exact invariant and runtime validation target before editing executable code.
- Completion criteria: identify the exact initializer or runtime publication
  path responsible for filling the JVMCI ZGC barrier slots, prove whether it is
  callable or observable from the current native bootstrap surface without JNI
  fallback/JVMTI/new helper layers, and record the next generic implementation
  or rejection decision.
- Completion evidence 2026-05-21: OpenJDK source shows
  `CompilerToVM::Data::initialize(JVMCI_TRAPS)` publishes the ZGC slots from
  `XBarrierSetRuntime::*_addr()` under `UseZGC`; the initializer is invoked
  from `readConfiguration0`, which is reached through JVMCI Java/native
  configuration paths, not from the VMStruct table walk itself. The native
  JVMCI runtime entry `JVM_GetJVMCIRuntime` rejects calls unless `EnableJVMCI`
  is set and initializes the Java `HotSpotJVMCIRuntime`; this path requires the
  JVMCI/JNI Java runtime surface and is not a valid native-bootstrap trigger
  under the no-JNI/no-new-helper constraints. Runtime evidence confirms the
  timing: default strict ZGC keeps the recovered field-load and array slots
  null, while strict ZGC with
  `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+EagerJVMCI` fills
  `thread_address_bad_mask_offset=40`,
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded=0x7f398144ace0`,
  and `ZBarrierSetRuntime_load_barrier_on_oop_array=0x7f398144ab70`.
  Readiness still fails closed because the current native surface intentionally
  does not bind the mismatched array ABI and has no resolved store barrier.
  Next implementation must be a separate row: correct and bind the ZGC array
  ABI from the recovered JVMCI slot, then reassess ZGC readiness around the
  actually available callable barriers and inline fail-closed store path.

### [x] NPT-3ak: ZGC C1 barrier stub target recovery audit

- Scope: determine whether the named ZGC C1 barrier CodeBlobs present in the
  CodeCache can provide a generic, architecture-level recovery path to the
  underlying callable runtime leaf functions when `libjvm.so` symbols are
  stripped and JVMCI `CompilerToVM::Data` slots have not been initialized. This
  is a read-only evidence row. It may inspect OpenJDK C1/ZGC source, current
  generated logs, generated C, and static binary metadata. It must not bind raw
  CodeBlob stubs, execute recovered targets, infer addresses from sample
  benchmarks, mark ZGC ready, or add executable behavior.
- Required evidence: NPT-3aj proves default strict ZGC does not publish the
  JVMCI barrier slot values during early native layout initialization, while
  fresh logs show named `load_barrier_on_oop_field_preloaded_runtime_stub`
  CodeBlobs exist even in the default strict-ZGC run.
- Validation command or runtime target: source/log/static audit only. If a
  target-recovery invariant is proven, record a later implementation row with
  the exact CodeBlob name, instruction/site invariant, ABI, compressed-oops
  applicability, and runtime validation target before editing executable code.
- Completion criteria: either prove a generic and safe way to recover callable
  ZGC runtime leaf targets from named stubs without binding the stubs as call
  targets, or reject this path with the exact missing invariant and choose the
  next barrier-capability route.
- Completion evidence 2026-05-21: source shows the named C1 load barrier
  blobs are generated by `XBarrierSetC1::generate_c1_runtime_stubs` or
  `ZBarrierSetC1::generate_c1_runtime_stubs`, and the x86 generator emits a
  VM-leaf call to `XBarrierSetRuntime::load_barrier_on_oop_field_preloaded_addr`
  or `ZBarrierSetRuntime::load_barrier_on_oop_field_preloaded_addr`. Fresh
  strict-ZGC logs confirm the default run contains
  `load_barrier_on_oop_field_preloaded_runtime_stub` and weak-load sibling
  CodeBlobs. However, those CodeBlobs are C1 runtime stubs with VM/stub entry
  ABI, not C ABI functions for direct native calls, and binding the raw stub is
  rejected. More importantly, the active `gc/x` source has no corresponding C1
  store-barrier stubs, and neither `gc/x` nor `gc/z` C1 generation provides the
  object-array runtime barrier as a named CodeBlob. This path can at most
  recover a field-load leaf target after an architecture-specific instruction
  invariant is separately proven; it cannot provide the complete load-array and
  store capability required to mark ZGC ready. Reject C1-stub target recovery as
  the next readiness path. The next generic route is to fix the recovered JVMCI
  array ABI for already-published JVMCI slots and separately keep default
  strict-ZGC fail-closed until a complete non-JVMCI store/load capability is
  proven.

### [x] NPT-3al: Correct recovered ZGC object-array barrier ABI

- Scope: correct the native ZGC object-array load barrier ABI from the current
  single-argument/returning call shape to the OpenJDK `void(oop*, size_t)` shape
  for wide oop arrays, update dlsym names accordingly, and bind the recovered
  stripped JVMCI `ZBarrierSetRuntime_load_barrier_on_oop_array` slot only when
  its target is already published and validated inside executable `libjvm.so`.
  The implementation must not use the runtime array barrier for compressed-oop
  array slots, must not bind raw CodeBlob stubs, must not initialize JVMCI, must
  not mark default strict ZGC ready, and must not change store-barrier
  semantics.
- Required evidence: NPT-3aj proves the JVMCI array slot is published under
  eager JVMCI and null in default early layout; local OpenJDK source proves the
  array runtime ABI is `void(oop*, size_t)` while the current native typedef is
  `void *(*)(void*)`; NPT-3ak rejects C1 stubs as a complete readiness path.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, strict ZGC with eager JVMCI to prove the array slot is
  bound and logged, strict default ZGC to prove it remains fail-closed, and
  generated-C inspection for the corrected ABI and absence of JNI fallback.
- Completion criteria: generated C uses the corrected array typedef/call shape,
  dlsym probes use the corrected mangled two-argument symbol names, recovered
  JVMCI array targets are executable-range validated before binding, compressed
  oop arrays do not call the wide array runtime, and ZGC readiness remains
  blocked by missing complete store/default capability.
- Completion evidence 2026-05-21: focused generator/audit tests passed. Fresh
  TEST native generation produced
  `build/npt-3al-zgc/TEST-native.jar` from
  `build/neko-native-work/run-13753557747563` with `translated=49 rejected=0`.
  Generated C contains `typedef void (*neko_z_lrb_array_t)(void**, size_t)`,
  the corrected
  `_ZN18{Z,X}BarrierSetRuntime25load_barrier_on_oop_arrayEPP7oopDescm` dlsym
  probes, and executable-range validated array binding logs. Strict ZGC with
  eager JVMCI bound `z_lrb=0x7fe898c4ace0`,
  `z_array=0x7fe898c4ab70`, and `z_bad_off=40`, then still failed closed due
  to missing `z_store`. Default strict ZGC kept field/array slots null and
  failed closed with `z_lrb=(nil)`, `z_array=(nil)`, `z_store=(nil)`.
  Default collector TEST completed with `Calc: 87ms`.

### [x] NPT-3am: Split primitive field volatile access

- Scope: expose resolved Java field `access_flags` through generated native
  field-slot metadata and use it so primitive field helpers emit volatile C
  accesses only for fields with `ACC_VOLATILE`. Non-volatile Java fields should
  use ordinary C loads/stores. Unknown or unresolved metadata must remain
  fail-closed or conservatively volatile; this row must not change object
  reference barrier semantics, field resolution ownership, JNI fallback,
  exception behavior, or ABI layout for unrelated helpers.
- Required evidence: the performance todo identifies current primitive field
  helpers as using volatile pointer dereferences for every primitive load/store,
  while field resolution already records HotSpot field access flags. This row
  must confirm the exact generated slot layout and access sites before editing.
- Validation command or runtime target: focused generator/unit tests with
  repository `./gradlew`, generated-C inspection proving volatile appears only
  on volatile primitive field paths or conservative unknown paths, fresh TEST
  native generation, default collector TEST smoke, and performance samples for
  TEST Calc/Platform if the row passes functional validation.
- Completion criteria: generated metadata carries field volatility generically,
  primitive field helpers preserve volatile semantics for volatile fields,
  normal fields no longer force volatile C memory access, unknown metadata stays
  conservative, and runtime validation shows no regression or fallback.
- Completion evidence 2026-05-21: fresh source/diff review showed this target
  is already implemented in the current code before any NPT-3am executable
  edit: generated static and instance field refs carry `access_flags_slot`,
  owner binding calls pass `g_access_*` slots to
  `neko_bind_instance_field_offset` and `neko_bind_static_field_metadata`, and
  those bind helpers write `native_field.access_flags` into the slot. Fresh
  generated C from `build/neko-native-work/run-13753557747563` proves primitive
  helpers take `uint32_t access_flags` and branch on `0x0040u`: volatile fields
  use `volatile` pointer access and non-volatile fields use ordinary pointer
  access for all primitive get/set and static get/set helpers. Primitive field
  callsites pass the resolved `g_access_*` slots. Focused generator/audit tests
  passed during NPT-3al, fresh TEST native generation produced `translated=49
  rejected=0`, and default collector TEST completed with `Calc: 87ms`. No
  executable change was needed for this row.

### [x] NPT-3an: Outline hot-helper diagnostics

- Scope: move selected inline `fprintf`/`abort` diagnostic construction out of
  hot native helper bodies into shared `cold`, `noinline` diagnostic functions.
  The first implementation must be narrowly scoped to helpers where the cold
  branch already terminates unconditionally and where argument values can be
  passed without changing evaluation order. It must not change exception
  behavior, pending-exception handling, GC barriers, array bounds/null ordering,
  or field/array access semantics.
- Required evidence: current generated helper source contains inline diagnostic
  blocks in hot array and field helpers; NPT-3am/P5 did not require executable
  edits, leaving P6 as the next performance row.
- Validation command or runtime target: focused generator/audit tests, generated
  C inspection proving hot helper bodies call cold diagnostics instead of
  containing large inline diagnostic formatting, fresh TEST native generation,
  and default collector TEST smoke. Broader performance gate remains a later
  row unless this change produces a runtime regression.
- Completion criteria: selected hot helper error paths are outlined to cold
  noinline functions, normal fast-path branches and semantic checks are
  unchanged, generated C compiles, and runtime smoke shows no new failure.
- Completion evidence 2026-05-21: object-field GETFIELD/GETSTATIC/PUTFIELD/
  PUTSTATIC diagnostic exits were outlined to `cold`, `noinline` hidden helper
  functions. Focused generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh TEST native
  generation succeeded in `build/neko-native-work/run-14414463312953` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1037656` bytes.
  Generated C inspection showed hot helpers in `neko_native_support.c` call
  `neko_abort_object_*_unavailable`, while
  `neko_native_support_helpers_4.c` defines those functions as
  `__attribute__((visibility("hidden"))) void __attribute__((cold, noinline))`.
  Default collector TEST smoke on `build/npt-3an-r2/TEST-native.jar` completed
  with no stderr and `Calc: 91ms`.

### [x] NPT-3ao: Outline object-array diagnostics

- Scope: move object-array-only diagnostic `fprintf`/`abort` construction from
  `neko_array_store_check`, `neko_fast_aastore`, and `neko_fast_aaload` into
  `cold`, `noinline` hidden helper functions. Keep successful object-array
  fast paths, null and bounds checks, array-store type checks, GC barriers, raw
  oop loads/stores, and handle creation unchanged. Do not touch the primitive
  array diagnostic shape rejected by NPT-3z.
- Required evidence: current source keeps unconditional diagnostic abort blocks
  in object-array helper failure paths. The NPT-3z regression applies to the
  generated primitive-array helpers, while the object-array blocks remain an
  untested portion of P6.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving object-array hot
  helper bodies call cold diagnostics instead of containing large inline
  formatting blocks, and default collector TEST smoke.
- Completion criteria: selected object-array diagnostic exits are outlined to
  cold noinline helpers, all normal branches and semantic checks are unchanged,
  generated C compiles with `translated>0 rejected=0`, and runtime smoke shows
  no new failure.
- Completion evidence 2026-05-21: `neko_array_store_check`,
  `neko_fast_aastore`, and `neko_fast_aaload` now call hidden `cold`,
  `noinline` diagnostic helpers for their terminal diagnostic exits. Focused
  generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh TEST native
  generation succeeded in `build/neko-native-work/run-14632991188519` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1038728` bytes.
  Generated C inspection showed object-array hot helpers in
  `neko_native_support.c` call `neko_abort_aastore_*` and
  `neko_abort_fast_aa*`, while `neko_native_support_helpers_4.c` defines those
  helpers with `visibility("hidden")`, `cold`, and `noinline`. Default
  collector TEST smoke on `build/npt-3ao/TEST-native.jar` completed with no
  stderr and `Calc: 89ms`.

### [x] NPT-3ap: Outline object fused-array diagnostics

- Scope: move object-array checked/fused diagnostic `fprintf`/`abort`
  construction from `neko_checked_aaload`, `neko_checked_aastore`, and
  `neko_fast_aaload_aaload` into hidden `cold`, `noinline` helper functions.
  Keep returned reason codes, null and bounds ordering, handle resolution,
  array-store checks, GC barriers, and raw oop loads/stores unchanged. Do not
  touch generated primitive checked/fused helpers or the primitive direct array
  diagnostic shape rejected by NPT-3z.
- Required evidence: current source still emits unconditional diagnostic aborts
  in object-array checked/fused failure paths after NPT-3ao. These paths are
  separate from the rejected primitive-array direct diagnostic row and can be
  moved without changing success-path semantics.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving the selected
  object-array checked/fused helpers call cold diagnostics instead of containing
  inline formatting blocks, and default collector TEST smoke.
- Completion criteria: selected object-array checked/fused diagnostic exits are
  outlined to cold noinline helpers, all normal checks and reason-code returns
  are unchanged, generated C compiles with `translated>0 rejected=0`, and
  runtime smoke shows no new failure.
- Completion evidence 2026-05-21: `neko_checked_aaload`,
  `neko_checked_aastore`, and `neko_fast_aaload_aaload` now call hidden `cold`,
  `noinline` diagnostic helpers for layout and unresolved-handle hard-abort
  paths. Focused generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh TEST native
  generation succeeded in `build/neko-native-work/run-14840042038598` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1036952` bytes.
  Generated C inspection showed selected checked/fused object-array helpers in
  `neko_native_support.c` call `neko_abort_checked_*` and
  `neko_abort_fused_aaload_aaload_*`, while `neko_native_support_helpers_4.c`
  defines those helpers with `visibility("hidden")`, `cold`, and `noinline`.
  Default collector TEST smoke on `build/npt-3ap/TEST-native.jar` completed
  with no stderr and `Calc: 97ms`; repeated TEST samples showed NPT-3ao
  `88,88,95,95,94 ms` (median `94ms`) versus NPT-3ap
  `89,85,92,88,86 ms` (median `88ms`).

### [rejected] NPT-3aq: Outline fused primitive AALOAD diagnostics

- Scope: move generated fused `AALOAD+xALOAD` and raw fused
  `raw AALOAD+xALOAD` diagnostic layout/outer-handle hard-abort formatting into
  hidden `cold`, `noinline` helper functions. Preserve all reason-code returns
  for outer null, outer bounds, inner null, and inner bounds; preserve the raw
  fused null check before `neko_zgc_good_oop`. Do not touch direct primitive
  array helpers or generated checked primitive load/store helpers.
- Required evidence: current source templates for
  `neko_fast_aaload_{b,c,s,i,l,f,d}aload` and
  `neko_fast_raw_aaload_{b,c,s,i,l,f,d}aload` still emit inline diagnostic
  formatting in hard-abort-only paths. The subagent audit identified these as
  distinct from the NPT-3z rejected direct primitive-array diagnostic shape.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving fused generated helper
  bodies call cold diagnostics instead of containing inline formatting blocks,
  default collector TEST smoke, and repeated TEST sample comparison against
  NPT-3ap if a smoke timing regresses.
- Completion criteria: generated fused primitive AALOAD diagnostic exits are
  outlined to cold noinline helpers, reason-code behavior is unchanged,
  generated C compiles with `translated>0 rejected=0`, and runtime validation
  shows no new failure or measured regression.
- Rejection evidence 2026-05-21: the attempted generic cold-helper shape moved
  `neko_fast_aaload_{b,c,s,i,l,f,d}aload` layout/outer-handle diagnostics and
  `neko_fast_raw_aaload_{b,c,s,i,l,f,d}aload` layout diagnostics to shared
  tag-parameterized cold helpers. Focused generator/audit tests passed, fresh
  generation succeeded in `build/neko-native-work/run-15069482044439` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1037208` bytes,
  generated C inspection proved the intended calls, and default TEST smoke
  completed with no stderr and `Calc: 90ms`. Repeated TEST samples regressed
  versus the NPT-3ap median: NPT-3aq `92,95,98,91,87 ms` (median `92ms`)
  versus NPT-3ap `89,85,92,88,86 ms` (median `88ms`). The source change was
  reverted before commit. Do not retry this tag-parameterized fused primitive
  diagnostic outlining without new branch-layout or compiler-output evidence.

### [x] NPT-3ar: Close descriptor shadow-frame and primitive-volatility rows

- Scope: close stale performance todo rows whose generic implementation is
  already present in the current source and validated by fresh generated
  artifacts. This is an evidence-only audit update; it must not change
  executable code.
- Required evidence: P3 requires one shadow-stack push/pop per translated
  method while replacing three per-slot strings with one descriptor pointer.
  P5 requires primitive field access to branch on resolved Java `ACC_VOLATILE`
  metadata so non-volatile fields use ordinary C loads/stores.
- Validation command or runtime target: source inspection plus generated-C
  inspection from the fresh NPT-3ap TEST artifact, with the same focused tests,
  generation, and smoke evidence already recorded for NPT-3ap/NPT-3am.
- Completion criteria: P3 and P5 todo rows are marked complete only if current
  generated C proves descriptor-pointer shadow frames and access-flag-driven
  primitive field volatility, with no executable diff in this row.
- Completion evidence 2026-05-21: source emits
  `static const neko_shadow_frame_desc __neko_shadow_desc` and
  `neko_shadow_push(&__neko_shadow_desc)` at translated method entry, and
  runtime support stores only `const neko_shadow_frame_desc *desc` per shadow
  frame before expanding owner/method/file in `neko_shadow_stack_trace`.
  Generated C from `build/neko-native-work/run-14840042038598` shows per-method
  descriptor pushes in `neko_native_impl_*.c`, `neko_shadow_frame` containing
  only `desc`, and trace expansion through `desc->owner`, `desc->method`, and
  `desc->file`. The same generated artifact proves primitive field helpers
  accept `uint32_t access_flags`, branch on `0x0040u`, use `volatile` only for
  volatile paths, and use ordinary pointer loads/stores otherwise. No
  executable source changed in this row.

### [x] NPT-3as: Add dependency shadow-stack fixture

- Scope: add the missing P3 acceptance fixture proving an external, unmodified
  Java classpath caller can invoke translated native code and later observe the
  translated frames produced by the descriptor-backed shadow stack. This is a
  test-only change; it must not change runtime/native code.
- Required evidence: NPT-3ar proved descriptor-pointer shadow frames in source
  and generated C, but the original P3 row also required a dependency-jar
  stack-trace fixture.
- Validation command or runtime target: focused
  `NativeObfuscationIntegrationTest` test using repository `./gradlew`.
- Completion criteria: the test creates an input jar containing only the
  native-translated target class, creates a separate dependency jar containing
  the external caller, runs `java -cp <native-output>:<dependency>`, and proves
  the returned stack trace contains translated target frames.
- Completion evidence 2026-05-21: added
  `nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`, which builds
  `dependency-shadow-target.jar` with `pkg.NativeStackTarget`, builds a separate
  `dependency-shadow-caller.jar` with `dep.ExternalStackCaller`, obfuscates only
  the target jar with `native-test.yml`, and runs `java -cp
  <native-output>:<dependency> dep.ExternalStackCaller`. The caller validates
  the returned stack trace string contains both
  `pkg.NativeStackTarget.leaf` and `pkg.NativeStackTarget.capture`. Focused
  validation passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests
  dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`.
  Runtime stdout was `dependency-stacktrace-ok` and stderr was empty.

### [x] NPT-3at: Tighten translated local-root reservation

- Scope: reduce translated method-entry local-handle reservation only when the
  method ABI and bytecode prove that no reference value is stored in a JVM local
  slot. This is a generic P11 substep that avoids unnecessary
  `neko_prepare_local_oop_roots` calls without reusing HotSpot-owned
  `JNIHandleBlock::_next` blocks.
- Required evidence: current source emits local-root reservation for every
  static primitive-ABI method that is not fully primitive-only; generated TEST
  `Calc.runAll` calls translated `call`, `runAdd`, and `runStr` 10,000 times,
  and `call`/`runAdd` reserve roots even though they have no object-local
  stores while `runStr` needs roots for its string local. P11 ownership audit
  proves reusing existing `_next` blocks is unsafe because restore treats
  `saved_next` as pre-existing caller/VM state and restores `_top` only for the
  saved active block.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection for removed/retained
  `neko_prepare_local_oop_roots`, default TEST smoke, and repeated TEST timing
  comparison.
- Completion criteria: instance methods, reference parameters, `ASTORE` paths,
  and reference tail-recursive rewrites still reserve local roots; no-object-local
  static primitive methods do not reserve local roots; generated artifacts report
  `translated>0 rejected=0`; runtime smoke has no fatal/error output; timing does
  not regress.
- Completion evidence 2026-05-21: `NativeTranslator.methodMayUseObjectLocalRoots`
  now reserves local roots for static primitive-ABI methods only when bytecode
  contains an object-local store; instance methods and reference parameters
  still reserve roots. Focused generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Fresh TEST native generation produced
  `build/npt-3at/TEST-native.jar` from
  `build/neko-native-work/run-15969669901673` with `translated=49 rejected=0`
  and `libneko_linux_x64.so` size `1034616` bytes. Generated C inspection shows
  `Calc.runAll`, `Calc.call`, and `Calc.runAdd` no longer call
  `neko_prepare_local_oop_roots`, while `Calc.runStr` still reserves roots and
  uses `neko_store_local_oop_ref` for its string local. Direct TEST smoke ran
  cleanly with empty stderr; same-session comparison showed prior accepted
  `npt-3ap` Calc samples `89,90,92,90,90 ms` (median `90ms`) versus NPT-3at
  `88,85,83,101,94 ms` (median `88ms`). Focused native integration tests for
  TEST Calc and obfusjack completion also passed.

### [x] NPT-3bx: P11 default-off direct-oop handle overflow audit

- Scope: add measurement only for `native-performance-optimization-todo.md`
  P11 before changing local-handle overflow allocation. The audit must instrument
  the generic `neko_direct_oop_to_handle` path, not a benchmark-specific caller,
  and must not alter default runtime behavior or handle ownership.
- Required evidence: current source shows overflow `calloc` in
  `neko_direct_oop_to_handle`; generated C/source inspection identifies all
  counters as opt-in only; opt-in TEST and obfusjack runtimes report total
  direct-oop handle calls, active-block fast-slot hits, overflow block
  allocations, unavailable direct-slot exits, and max observed active-block
  top/capacity. The evidence must decide whether a reusable-block/window
  implementation is justified; zero or negligible overflow means P11 should
  remain an audit finding rather than an allocation rewrite.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh opt-in TEST/obfusjack generation
  using repository-local build/temp/cache directories, `NEKO_DIRECT_DEBUG=1`
  runtime for both artifacts, default non-audit smoke for at least TEST, and
  generated-C inspection showing audit counters are absent or inert in default
  builds and no forbidden JNI/JVMTI/fallback markers are introduced.
- Completion criteria: opt-in stats print useful nonzero total-call data;
  default builds retain the existing stats surface and hot path; runtime
  artifacts report `translated>0 rejected=0`; TEST and obfusjack complete
  without fatal/error output; the row records whether P11 implementation should
  proceed or be deferred.
- Completion evidence 2026-05-22: added default-off `NEKO_HANDLE_AUDIT`
  counters to the generic `neko_direct_oop_to_handle` path and exposed the
  build through `NEKO_NATIVE_HANDLE_AUDIT=1` / manifest
  `handle.audit.build=true`. Default builds keep `NEKO_HANDLE_AUDIT=0` and do
  not print the handle stats fields unless explicitly compiled with the audit
  flag and run with `NEKO_DIRECT_DEBUG=1`. Focused validation passed:
  `./gradlew --no-daemon :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Fresh opt-in artifacts:
  `build/npt-3bx/TEST-handle-audit.jar` from
  `build/neko-native-work/run-31631741784087` (`translated=49 rejected=0`,
  manifest `handle.audit.build=true`, lib `1059560` bytes) and
  `build/npt-3bx/obfusjack-handle-audit.jar` from
  `build/neko-native-work/run-31640797565320`
  (`translated=93 rejected=0`, manifest `handle.audit.build=true`, lib
  `1838584` bytes). Strict forbidden-JNI grep over those generated run dirs
  found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` matches. Opt-in TEST
  runtime with `NEKO_DIRECT_DEBUG=1` completed with `Calc: 387ms` under audit
  overhead and printed two stats rows; the dominant row showed
  `handle_direct_total=510054`, `handle_direct_fast_slot=491718`,
  `handle_direct_overflow_alloc=18336`, `handle_direct_unavailable=0`,
  `handle_direct_max_top=32`, and `handle_direct_max_capacity=32`. Opt-in
  obfusjack runtime completed and printed `handle_direct_total=854741`,
  `handle_direct_fast_slot=846154`, `handle_direct_overflow_alloc=8587`,
  `handle_direct_unavailable=0`, `handle_direct_max_top=32`, and
  `handle_direct_max_capacity=32`. Fresh default non-audit TEST artifact
  `build/npt-3bx/TEST-default.jar` from
  `build/neko-native-work/run-31689142844822` reported
  `handle.audit.build=false`, completed with `Calc: 73ms`, and emitted no
  `[neko-direct] stats` line. Conclusion: P11 overflow allocation is hot enough
  to justify a generic reusable-block/window implementation; do not defer P11
  on zero-overflow grounds.

### [x] NPT-3by: P11 batch native-owned JNIHandleBlock overflow blocks

- Scope: reduce the measured P11 overflow allocation cost by batching and
  recycling only JNIHandleBlock memory allocated by generated native code. This
  is a generic scoped block allocator below the existing native/bootstrap
  surface, not a JNI fallback and not a benchmark-specific capacity tweak.
- Required evidence: NPT-3bx opt-in audit proves overflow allocation is hot:
  TEST `handle_direct_overflow_alloc=18336` and obfusjack
  `handle_direct_overflow_alloc=8587`, both with `handle_direct_max_top=32` and
  `handle_direct_max_capacity=32`. Source evidence must show restore knows the
  saved active block and `saved_next`, so blocks above that boundary are
  native-owned by the current translated scope and may be cleared and recycled
  only after they are removed from active JNI handle visibility. Fresh
  recycle-only audit then reported TEST still at
  `handle_direct_overflow_alloc=18336`, proving long single-invocation handle
  bursts need scoped batching before restore-time recycling can help.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh TEST and obfusjack native
  generation, default TEST/obfusjack runtime, opt-in `NEKO_NATIVE_HANDLE_AUDIT=1`
  TEST/obfusjack generation and `NEKO_DIRECT_DEBUG=1` runtime proving overflow
  allocation count drops and unavailable count remains zero, generated-C
  inspection for bounded recycle helpers and forbidden JNI/JVMTI/fallback
  markers, and repeated timing comparison against the before-edit default
  artifacts.
- Completion criteria: live JNI handles remain visible until restore; restore
  returns the active block, `_top`, `_next`, and `_last` to the saved state;
  scoped slabs are freed only after their blocks leave the active handle chain;
  recycled blocks are zeroed before reuse/pooling; the recycle list is bounded
  and per-thread; missing allocation still hard aborts; runtime artifacts report
  `translated>0 rejected=0`; TEST and obfusjack complete without fatal/error
  output; performance does not regress.
- Completed 2026-05-22: implemented scoped native-owned JNIHandleBlock slab
  allocation under the existing handle save/restore window, with the no-scope
  per-thread recycle list retained as a bounded fallback. Focused validation
  passed with `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest`.
  Fresh default artifacts: TEST `build/npt-3by-slab/TEST-default-r2.jar` from
  `build/neko-native-work/run-32502768700627` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1079896`), obfusjack
  `build/npt-3by-slab/obfusjack-default-r2.jar` from
  `build/neko-native-work/run-32505682956586` (`translated=93 rejected=0`,
  `handle.audit.build=false`, lib `1880200`). Strict generated-C grep for
  `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` returned no matches. Default TEST
  x5 Calc `68/70/76/78/67` ms, median `70`; default obfusjack x5 Platform
  `44/42/43/44/47` ms, median `44`, Virtual `36/37/44/38/41` ms, median `38`,
  Seq `17/17/18/17/17` ms, median `17`, Parallel/VThreads `1/1`. Fresh opt-in
  audit artifacts: TEST `build/npt-3by-slab/TEST-handle-audit-r2.jar` from
  `run-32559694871235`, obfusjack
  `build/npt-3by-slab/obfusjack-handle-audit-r2.jar` from
  `run-32562676424869`, both `handle.audit.build=true`. Opt-in TEST dominant
  stats dropped `handle_direct_overflow_alloc` from NPT-3bx `18336` to `286`
  with `handle_direct_unavailable=0`; opt-in obfusjack dropped from `8587` to
  `898` with `handle_direct_unavailable=0`.

### [x] NPT-3bz: P9 default-off direct-handle origin audit

- Scope: add a default-off origin audit for calls into
  `neko_direct_oop_to_handle` so P9 can choose a generic raw-oop lifetime
  target from runtime evidence instead of static callsite counts. The audit is
  permitted only under `NEKO_NATIVE_HANDLE_AUDIT=1`; default native artifacts
  must keep the same semantics and must not emit or execute audit-specific
  origin accounting.
- Required evidence: after NPT-3by, opt-in TEST still reports
  `handle_direct_total=510054` and obfusjack reports
  `handle_direct_total=854741`, while allocation-call counts fell sharply. This
  proves the remaining P9 question is not overflow allocation but which generic
  producer is causing direct-handle materialization. Source evidence: the
  existing fusion only handles adjacent `AALOAD; <int-push>; XALOAD` in
  `OpcodeTranslator`, while regular `neko_fast_aaload` still materializes a
  `jobject` through `neko_direct_oop_to_handle`.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh opt-in TEST and obfusjack
  generation/runtime with `NEKO_DIRECT_DEBUG=1`, generated-C inspection proving
  origin counters exist only in `NEKO_NATIVE_HANDLE_AUDIT` builds, default
  generated-C/manifest inspection proving `handle.audit.build=false` artifacts
  compile out the origin counters, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: default runtime behavior and generated helper call
  shapes are unchanged; opt-in TEST and obfusjack complete without fatal/error
  output; origin counter totals reconcile with `handle_direct_total`; no
  `handle_direct_unavailable` path appears; the recorded evidence identifies
  whether P9 should target `AALOAD` raw-oop lifetime or a different generic
  producer first.
- Completed 2026-05-22: added opt-in origin counters compiled under
  `NEKO_HANDLE_AUDIT` only. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh default artifacts:
  `build/npt-3bz/TEST-default.jar` from
  `build/neko-native-work/run-32880538686672` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1084344`) and
  `build/npt-3bz/obfusjack-default.jar` from `run-32883581015154`
  (`translated=93 rejected=0`, `handle.audit.build=false`, lib `1876824`).
  Default smoke passed with TEST `Calc: 70ms` and obfusjack Platform `41ms`,
  Virtual `36ms`, Seq `17ms`; neither emitted `[neko-direct]` stats. Strict
  generated-C forbidden grep returned no matches. Fresh opt-in artifacts:
  TEST `build/npt-3bz/TEST-handle-origin-audit.jar` from `run-32932004575725`
  and obfusjack `build/npt-3bz/obfusjack-handle-origin-audit.jar` from
  `run-32935171276056`, both `handle.audit.build=true`; strict forbidden grep
  returned no matches. TEST dominant origin row reconciled exactly to
  `handle_direct_total=510054`: `njx_return=510004`, `object_alloc=16`,
  `static_object_field=24`, `bound_string=10`, all AALOAD origins `0`, and
  `handle_direct_unavailable=0`. The second TEST stats row reconciled to
  `172`, with only `checked_aaload=8`. Obfusjack origins reconciled to
  `handle_direct_total=854741`: `njx_return=301141`, `object_alloc=200430`,
  `static_object_field=200096`, `checked_aaload=148993`, `object_field=3484`,
  `primitive_array_alloc=577`, `object_array_alloc=13`, `fused_aaload_aaload=1`,
  `bound_string=6`, `other=0`, and `handle_direct_unavailable=0`. Conclusion:
  P9 AALOAD lifetime is relevant for obfusjack but not the dominant TEST Calc
  lever; the next generic performance substep should prioritize NJX return
  handle materialization before a P9 lifetime rewrite.

### [x] NPT-3ca: P10 default-off NJX object-return shape audit

- Scope: add default-off per-shape counters for object-return NJX call_stub
  dispatches. This is an evidence-only P10 substep to identify whether the
  `njx_return` handle-materialization hot path is concentrated in one generic
  shape or spread across several shapes. It must not alter default call_stub
  arguments, Method*/entry selection, JavaCallWrapper layout, handle-window
  semantics, returned object materialization, exception behavior, or introduce
  any named-JDK/native substitution.
- Required evidence: NPT-3bz showed TEST `njx_return=510004` and obfusjack
  `njx_return=301141`, the largest single direct-handle origin in both targets.
  That proves NJX object returns are hot but does not yet identify the generic
  shape distribution needed to choose between a shape-level, all-object-return,
  or caller-consumption optimization.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh default TEST/obfusjack smoke proving
  `handle.audit.build=false` artifacts emit no shape stats and complete
  normally, fresh opt-in TEST/obfusjack runtime with `NEKO_DIRECT_DEBUG=1`
  showing per-shape object-return counters, generated-C/manifest inspection for
  `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: opt-in shape totals reconcile with the NPT-3bz
  `njx_return` origin totals for non-null object returns; default artifacts have
  no behavior change; TEST and obfusjack complete without fatal/error output;
  the recorded evidence identifies the next generic NJX-return optimization
  boundary.
- Completed 2026-05-22: added `NEKO_HANDLE_AUDIT`-gated per-object-return-shape
  counters and a shape summary. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh default artifacts:
  `build/npt-3ca/TEST-default.jar` from
  `build/neko-native-work/run-33252836211792` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1084344`) and
  `build/npt-3ca/obfusjack-default.jar` from `run-33255732064280`
  (`translated=93 rejected=0`, `handle.audit.build=false`, lib `1876824`).
  Default smoke passed: TEST `Calc: 72ms`; obfusjack rerun completed with
  Platform `46ms`, Virtual `39ms`, Seq `17ms`; neither default run emitted
  `[neko-direct]` stats. Strict forbidden-JNI grep returned no matches. Fresh
  opt-in artifacts: TEST `build/npt-3ca/TEST-shape-audit.jar` from
  `run-33444693183231` and obfusjack
  `build/npt-3ca/obfusjack-shape-audit.jar` from `run-33447791842344`, both
  `handle.audit.build=true`, with strict forbidden-JNI grep clean. TEST
  dominant shape row reconciled with `njx_return=510004`: `V:L:L=510002`,
  `V:L:=1`, `V:L:J=1`. Obfusjack reconciled with `njx_return=301141`:
  `V:L:=300822`, `V:L:L=159`, `S:L:J=57`, `S:L:I=50`, `S:L:L=19`, `S:L:=9`,
  `S:L:II=9`, `S:L:D=5`, `S:L:LLL=3`, `S:L:LLLL=2`, `V:L:LLL=2`,
  `S:L:LL=1`, `V:L:LIL=1`, `V:L:LL=1`, `V:L:II=1`. Conclusion: TEST and
  obfusjack have different dominant object-return shapes, so the next generic
  optimization boundary must be object-return continuation/lifetime, not a
  single shape or named JDK method.

### [x] NPT-3cb: P10 default-off NJX object-return continuation audit

- Scope: add default-off continuation counters for the existing generic
  invokedynamic string-concat accumulator. This is an evidence-only P10 substep
  to determine whether the dominant TEST `V:L:L` NJX object returns are
  intermediate concat accumulator values or final stack values. It must not
  construct strings natively, change the `String.concat` Method*/entry target,
  change default runtime behavior, or special-case a sample, class, method, or
  benchmark.
- Required evidence: NPT-3ca showed TEST `njx_return=510004` with
  `V:L:L=510002`. Source evidence shows the generic concat lowering calls
  `neko_concat_accumulate_string` for each concat piece and emits one final
  `PUSH_O(__acc)`. A raw-return optimization is justified only if runtime
  counters prove a material number of NJX object returns are intermediate
  accumulator values that are consumed by the next concat step before any
  safepoint-capable operation.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh default TEST/obfusjack smoke proving
  `handle.audit.build=false` artifacts emit no continuation stats, fresh opt-in
  TEST/obfusjack runtime with `NEKO_DIRECT_DEBUG=1` showing concat accumulator
  and final-push counters, generated-C/manifest inspection for
  `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: opt-in counters reconcile with the NPT-3ca `V:L:L`
  traffic enough to identify intermediate versus final concat object returns;
  default artifacts have no behavior change; TEST and obfusjack complete without
  fatal/error output; the recorded evidence either justifies or rejects a
  generic raw-return concat-continuation optimization.
- Completed 2026-05-22: added `NEKO_HANDLE_AUDIT`-gated concat continuation
  counters. Focused `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest`
  passed. Fresh default artifacts: `build/npt-3cb/TEST-default.jar` from
  `build/neko-native-work/run-33684566669446` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1084344`) and
  `build/npt-3cb/obfusjack-default.jar` from `run-33687571188325`
  (`translated=93 rejected=0`, `handle.audit.build=false`, lib `1876824`).
  Default smoke passed without `[neko-direct]` stats: TEST `Calc: 72ms`,
  obfusjack Platform `47ms`, Virtual `38ms`, Seq `17ms`. Strict forbidden-JNI
  grep returned no matches. Fresh opt-in artifacts: TEST
  `build/npt-3cb/TEST-continuation-audit.jar` from `run-33733597034954` and
  obfusjack `build/npt-3cb/obfusjack-continuation-audit.jar` from
  `run-33736528676932`, both `handle.audit.build=true`, with strict
  forbidden-JNI grep clean. TEST still reported `V:L:L=510002` and
  `njx_return=510004`, but concat continuation counters were all zero:
  `accumulate_total=0`, `accumulate_njx=0`, `final_push=0`,
  `intermediate_candidate=0`. Obfusjack reported `accumulate_total=137`,
  `accumulate_njx=89`, `final_push=48`, `intermediate_candidate=41`, far below
  its `njx_return=301141`. Conclusion: generic concat-continuation raw returns
  are rejected as the next performance route; TEST's dominant `V:L:L` returns
  come from the other generic concat fast path that immediately pushes the
  returned object.

### [x] NPT-3cc: P10 default-off StringBuilder concat fast-path audit

- Scope: add default-off audit counters for the recognized
  `StringBuilder.append(String).append(String).toString` concat fast path emitted
  by `NativeTranslator`. This is an evidence-only P10 substep to determine
  whether the dominant TEST `V:L:L` NJX object returns come from the immediate
  `neko_concat_append_inline(...)` plus `PUSH_O(__fastConcat)` path. It must not
  construct strings natively, change the `String.concat` Method*/entry target,
  change stack/local root semantics, change default runtime behavior, or
  special-case a sample, class, method, or benchmark.
- Required evidence: NPT-3cb proved the invokedynamic accumulator counters are
  zero for TEST while source generation shows the other generic concat lowering
  emits `neko_concat_append_inline(...)` followed by immediate `PUSH_O` in both
  literal and non-literal second-argument branches.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh default TEST/obfusjack smoke proving
  `handle.audit.build=false` artifacts emit no fast-path audit stats, fresh
  opt-in TEST/obfusjack runtime with `NEKO_DIRECT_DEBUG=1` showing the
  StringBuilder fast-path counters, generated-C/manifest inspection for
  `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: opt-in counters reconcile with the NPT-3ca/NPT-3cb
  `V:L:L` traffic enough to identify whether the immediate StringBuilder concat
  fast path is the next generic optimization boundary; default artifacts have no
  behavior change; TEST and obfusjack complete without fatal/error output; the
  recorded evidence either justifies or rejects optimizing that boundary.
- Completed 2026-05-22: added `NEKO_HANDLE_AUDIT`-gated StringBuilder
  fast-concat counters in both recognized `NativeTranslator` branches. Focused
  `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest` passed. Fresh
  default artifacts: TEST `build/neko-native-work/run-34233855782829`
  (`translated=49 rejected=0`, `handle.audit.build=false`, lib `1084344`) and
  obfusjack `run-34238244839435` (`translated=93 rejected=0`,
  `handle.audit.build=false`, lib `1876824`). Default five-run medians were TEST
  Calc `73ms`, obfusjack Platform `47ms`, Virtual `41ms`, Seq `17ms`; default
  benchmark stderr logs emitted no `[neko-direct]` audit rows, and strict
  generated-C grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` returned no
  matches. Fresh opt-in artifacts: TEST `run-34356662706187`
  (`handle.audit.build=true`, lib `1132648`) and obfusjack
  `run-34361066685190` (`handle.audit.build=true`, lib `1958600`). Direct
  opt-in TEST runtime completed with `V:L:L=510002`, `njx_return=510004`,
  concat-continuation zero, and `stringbuilder-fast-concat: total=510000
  literal=510000 dynamic=0`. Direct opt-in obfusjack runtime completed with
  full program output, `njx_return=301141`, concat-continuation
  `accumulate_total=137 accumulate_njx=89 final_push=48`, and
  `stringbuilder-fast-concat: total=0 literal=0 dynamic=0`. Conclusion:
  immediate StringBuilder fast concat accounts for all but two of TEST's
  dominant `V:L:L` object returns and is the next justified generic optimization
  boundary; obfusjack remains dominated by other object-return and direct-handle
  origins.

### [x] NPT-3cd: P10 raw-return local-root publication for immediate StringBuilder concat stores

- Scope: optimize only the recognized StringBuilder fast-concat path when its
  returned object is consumed by the immediately following `ASTORE`. The change
  may add a raw-oop NJX return helper for the already-resolved `V:L:L` call-stub
  shape and a local-root raw store helper, then fuse same-basic-block immediate
  `ASTORE` consumers. It must still call the original JVM `String.concat`
  Method*/entry, must not construct string payloads natively, must not bypass
  local-root publication, must not change non-immediate consumers, and must not
  alter non-StringBuilder paths.
- Required evidence: NPT-3cc proved TEST executes the immediate StringBuilder
  fast-concat path `510000` times against `V:L:L=510002`. Fresh generated C for
  the current artifact shows the exact sequence `neko_concat_append_inline(...)`,
  `PUSH_O(__fastConcat)`, then `{ jobject __ref = POP_O(); locals[N].o =
  neko_store_local_oop_ref(...) }`; source evidence shows
  `neko_store_local_oop_ref` resolves the just-created NJX handle back to a raw
  oop before publishing the existing local root.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh TEST/obfusjack generation, generated-C
  inspection proving the hot immediate-`ASTORE` path emits raw-return local-root
  publication and no `PUSH_O(__fastConcat)`/`POP_O` pair while non-immediate paths
  keep the normal handle path, strict generated-C grep for forbidden JNI wrappers,
  TEST and obfusjack runtime, and performance gate.
- Completion criteria: pending-exception dispatch is still driven by the original
  concat/toString potentially-excepting site; local-root stores still publish to
  `__neko_local_roots[N]`; TEST and obfusjack complete without fatal/error output;
  TEST Calc median improves or does not regress versus the NPT-3cc default median
  while obfusjack Platform/Virtual/Seq do not regress.
- Completed 2026-05-22: added a raw-oop variant for the registered `V:L:L` NJX
  return shape and fused only same-basic-block immediate StringBuilder
  concat-to-`ASTORE` consumers into `neko_concat_append_inline_store_local(...)`.
  The helper still calls the resolved `String.concat` Method*/entry and publishes
  through `__neko_local_roots[N]`; non-immediate consumers keep the normal
  handle-return path.
- Fresh validation: focused `OpcodeTranslatorUnitTest`
  (`stringBuilderConcatImmediateAstoreUsesRawLocalPublication` and existing
  StringBuilder concat test), `CCodeGeneratorTest`, and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh default perf capture passed
  with TEST artifact `run-35239467010830` and obfusjack artifact
  `run-35243935226564`; generated C shows the hot loop now emits
  `neko_concat_append_inline_store_local(thread, env, &__neko_local_roots[0], ...)`
  with no `PUSH_O(__fastConcat)`/`POP_O` pair on that path. Strict generated-C
  grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was empty. Median timings:
  TEST Calc `63 ms`; obfusjack Platform `45 ms`, Virtual `37 ms`, Seq `17 ms`,
  Parallel `1 ms`, VThreads `1 ms`. This improves TEST versus the NPT-3cc default
  median `73 ms` and does not regress obfusjack versus NPT-3cc defaults
  (`47/41/17 ms` for Platform/Virtual/Seq).

### [x] NPT-3ce: P10 post-NPT-3cd audit gate correction

- Scope: verify whether the post-NPT-3cd opt-in audit silence is an audit-gate
  defect before editing runtime code. Default builds must remain unchanged.
- Required evidence: fresh post-NPT-3cd opt-in perf capture generated TEST
  artifact `run-35475737821910` with `handle.audit.build=true`, and generated C
  still emits `NEKO_HANDLE_AUDIT_HIT(g_neko_stringbuilder_concat_fast_total_count)`
  in the hot immediate-`ASTORE` StringBuilder concat loop. The perf-capture
  stderr logs were empty because that harness did not set `NEKO_DIRECT_DEBUG`,
  not because the StringBuilder audit gate was dead.
- Completed 2026-05-22 with no executable change: direct opt-in/debug TEST
  runtime completed and printed `V:L:L=510002`, `dispatched=510061`,
  `handle_direct_total=54`, `njx_return=4`, concat-continuation zero, and
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Direct
  opt-in/debug obfusjack completed and printed `dispatched=976247`,
  `handle_direct_total=854741`, `njx_return=301141`, concat-continuation
  `accumulate_total=137 accumulate_njx=89 final_push=48 intermediate_candidate=41`,
  and `stringbuilder-fast-concat: total=0 literal=0 dynamic=0`. No audit code
  change is justified; this proves the remaining TEST hot boundary is still
  510000 `V:L:L` `String.concat` call-stub dispatches with raw local publication,
  while obfusjack remains dominated by other NJX/direct-handle paths.

### [x] NPT-3au: Split translated direct-call body entry

- Scope: reduce translated-to-translated direct-call raw-entry overhead by
  splitting generated raw methods into an external ABI wrapper and an internal
  body entry. The body entry may skip only the runtime capability checks already
  proven by the caller wrapper/dispatcher; it must preserve all Java-level method
  activation behavior.
- Required evidence: direct-call audit shows `OpcodeTranslator.translateDirectInvoke`
  calls `binding.rawFunctionName()` directly, while every generated raw function
  starts with `neko_hotspot_fast_require(thread, env)`. Fresh NPT-3at TEST C
  still shows `Calc.runAll` calling `Calc.call`, `Calc.runAdd`, and `Calc.runStr`
  in the 10,000-iteration loop, with each callee rerunning the same fast gate.
  `neko_hotspot_fast_require` checks only post-bootstrap fast-state invariants
  (`neko_const_initialized`, non-null `thread`, and supported GC barrier kind)
  and then emits compiler assumptions; callee roots and shadow frames remain
  separate required semantics.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving external wrappers still
  call `neko_hotspot_fast_require` and internal direct calls target body symbols,
  default TEST smoke, same-session timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: manifest/dispatcher ABI continues to target
  `neko_native_impl_*` wrappers; translated internal direct calls target
  `neko_native_impl_*_body`; body entries keep activation arrays, object-local
  roots where required, parameter-to-local rooting, and shadow push/pop; no
  JNI/JVMTI/fallback markers or fatal runtime errors appear; timing does not
  regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. First generated TEST runtime failed with
  `UnsatisfiedLinkError: undefined symbol: neko_native_impl_19_body`, proving the
  exact split-source invariant violation: `_body` declarations were static in
  implementation preludes while callers were in different translation units. The
  generic fix externalizes `_body` prototypes/definitions alongside raw wrappers.
  Fresh generation `build/neko-native-work/run-16584698339661` built
  `libneko_linux_x64.so` at `1034872` bytes with `translated=49 rejected=0`.
  Generated C shows external wrappers call `neko_hotspot_fast_require`, bodies
  call `neko_hotspot_fast_assume`, and `Calc.runAll` calls
  `neko_native_impl_20_body`, `neko_native_impl_21_body`, and
  `neko_native_impl_22_body`. Same-session smoke comparison passed with stderr
  empty: NPT-3at `86,87,90,86,91 ms` (median `87ms`) versus NPT-3au
  `83,86,84,83,96 ms` (median `84ms`). Focused native integration tests for
  TEST Calc and obfusjack completion also passed.

### [rejected] NPT-3av: Fast static primitive field refs after class init

- Scope: reduce repeated static primitive field get/put metadata work by using
  the existing `neko_static_field_ref` slots directly after class initialization
  and direct field metadata are already proven available. This must remain a
  generic helper/translator change for primitive static fields only, not a
  benchmark or field-name special case.
- Required evidence: fresh NPT-3au generated C shows the translated TEST Calc
  loop calls `neko_fast_get_static_I_field_ref(env, &g_static_field_ref_3)` and
  emits static puts as `neko_bound_class_ref`, `neko_ensure_class_initialized_once`,
  `neko_bound_field_ref`, then `neko_fast_set_static_I_field` on each looped
  translated call. Current helper source resolves the class and field inside
  every `neko_fast_get_static_*_field_ref`, even though bind support records
  `class_init_slot`, `static_base_slot`, `static_offset_slot`, and
  `access_flags_slot`; `neko_bind_static_field_metadata` sets base/offset/access
  from the resolved field metadata and hard-aborts on invalid static metadata.
- Validation command or runtime target: focused translator/generator/audit tests,
  fresh TEST native generation, generated-C inspection proving static primitive
  gets and puts use ref helpers and the helper fast path checks initialized
  class plus direct metadata before direct memory access, same-session TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: static field class initialization semantics remain
  preserved because uninitialized or incomplete refs still route through the
  existing class-init/bind slow path; missing direct metadata still aborts, not
  falls back; object static field helpers are unchanged; no JNI/JVMTI/fallback
  markers are introduced; timing does not regress.
- Rejected 2026-05-22. Focused generator/audit tests passed, and fresh TEST
  generation `build/neko-native-work/run-17090115800257` built
  `libneko_linux_x64.so` at `1037336` bytes with `translated=49 rejected=0`.
  Generated C showed `Calc.call`, `Calc.runAdd`, and `Calc.runStr` using
  `neko_fast_set_static_I_field_ref` and no per-site
  `neko_ensure_class_initialized_once`/`neko_bound_field_ref` sequence for the
  static primitive put. Runtime smoke was functionally green, but same-session
  timing regressed versus NPT-3au: NPT-3au `84,84,83,88,85 ms` (median `84ms`)
  versus NPT-3av `87,83,92,93,86 ms` (median `87ms`). A tightened inline-direct
  variant in `build/neko-native-work/run-17205237824086` also passed
  generation (`translated=49 rejected=0`) but regressed: NPT-3au
  `85,83,85,85,88 ms` (median `85ms`) versus NPT-3av
  `87,87,86,89,84 ms` (median `87ms`). Source/test edits were reverted; do not
  retry this static-field-ref shape without new code-size or branch-layout
  evidence.

### [x] NPT-3aw: Elide translated monitor storage for no-monitor methods

- Scope: avoid allocating translated monitor state in generated raw bodies whose
  bytecode contains no `MONITORENTER` or `MONITOREXIT`. This is a structural
  bytecode invariant, not an owner/method/benchmark special case.
- Required evidence: fresh NPT-3au generated TEST C emits
  `neko_monitor_record monitors[...]` and `int monitor_sp = 0` in every raw body,
  including `Calc.call`, `Calc.runAdd`, and `Calc.runStr`, while generated TEST
  contains no `neko_fast_monitor_enter` or `neko_fast_monitor_exit` use outside
  helper definitions. `OpcodeTranslator` is the only translated opcode path that
  references `monitors`/`monitor_sp`, and it does so only for monitor bytecodes.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving no-monitor methods omit
  `monitors`/`monitor_sp` while monitor-bytecode methods retain them, default
  TEST smoke/timing comparison, and focused native integration tests for TEST
  Calc and obfusjack completion.
- Completion criteria: no monitor-bytecode method loses monitor storage; no
  monitor helper call refers to undeclared storage; synchronized method JVM
  entry semantics are unchanged because this substep only controls translated
  explicit monitor opcode storage; no JNI/JVMTI/fallback markers are introduced;
  timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-17599946034860` built `libneko_linux_x64.so` at
  `1034872` bytes with `translated=49 rejected=0`. Generated C inspection showed
  `Calc.runAll`, `Calc.call`, and `Calc.runAdd` bodies no longer declare
  `neko_monitor_record monitors[...]` or `int monitor_sp = 0`, and `rg` found no
  generated TEST body monitor helper call sites. Same-session smoke comparison
  passed with stderr empty: NPT-3au `94,91,84,85,87 ms` (median `87ms`) versus
  NPT-3aw `84,84,84,87,86 ms` (median `84ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bg: Fuse primitive constant local stores

- Scope: fuse only adjacent same-basic-block primitive constant producers
  (`ICONST_*`, `BIPUSH`, `SIPUSH`, `LCONST_*`, `FCONST_*`, `DCONST_*`, and
  numeric `LDC`) followed immediately by a matching primitive local store
  (`ISTORE`, `LSTORE`, `FSTORE`, or `DSTORE`). The fused C must assign the same
  literal expression directly to the target local slot. It must not cross
  labels, fold object/reference stores, fold non-constant producers, change
  local indexes, or alter exception dispatch boundaries.
- Required evidence: fresh NPT-3bf generated TEST C at
  `build/neko-native-work/run-21337326266905/neko_native_impl_1.c`,
  `neko_native_impl_19.c`, and `neko_native_impl_21.c` shows hot methods still
  initialize primitive locals through constant `PUSH_*` followed immediately by
  `locals[n].* = POP_*()`. These are primitive non-throwing stack round trips.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving primitive
  constant local stores use direct assignments with no constant push/pop round
  trip, default TEST smoke/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: only primitive constant local stores fuse; label-blocked,
  type-mismatched, and non-constant cases retain stack traffic; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-21666360179964` built `libneko_linux_x64.so` at
  `1034760` bytes with `translated=49 rejected=0`. Generated C inspection
  showed hot primitive initializers in `Digi.run`, `Calc.runAll`, and
  `Calc.runAdd` use direct local assignments such as `locals[1].d = 0.0`,
  `locals[3].i = 0`, and `locals[4].f = 1.1f`. Static grep found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating TEST smoke comparison passed with empty
  stderr: NPT-3bf `90,88,86,93,87,88,94 ms` (median `88ms`) versus NPT-3bg
  `91,84,91,88,89,88,87 ms` (median `88ms`), with library size reduced from
  `1034808` to `1034760` bytes. Focused native integration tests for TEST Calc
  and obfusjack completion also passed.

### [x] NPT-3bh: Fuse single int producers into static translated direct calls

- Scope: fuse only adjacent same-basic-block pure `int` producers
  (`ICONST_*`, `BIPUSH`, `SIPUSH`, or `ILOAD`) feeding an immediate
  `INVOKESTATIC` whose target is already translated and whose descriptor has
  exactly one `int` argument. The fused C must call the existing translated
  direct-call body with the producer expression as the argument and must reuse
  the existing direct-call target class/guard/result handling. It must not cross
  labels, fold non-static calls, fold NJX or virtual/interface dispatch, fold
  multi-argument calls, fold non-int arguments, or alter pending-exception
  coalescing.
- Required evidence: fresh NPT-3bg generated TEST C at
  `build/neko-native-work/run-21666360179964/neko_native_impl_19.c` shows the
  hot `Calc.runAll` loop still materializes the constant argument to translated
  static `Calc.call(I)V` through `PUSH_I(100)` and immediate `POP_I()` inside
  the direct translated call wrapper.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the
  direct translated call receives the int expression directly without
  argument-stack push/pop, default TEST smoke/timing comparison, and focused
  native integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only one-int-argument static translated direct calls
  fuse; label-blocked, non-static, non-translated, multi-argument, and non-int
  cases retain stack traffic; no JNI/JVMTI/fallback markers are introduced;
  timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-22094701424303` built `libneko_linux_x64.so` at
  `1034712` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.runAll` passes `(jint)(100)` directly to
  `neko_native_impl_20_body` without argument push/pop, while other dispatch
  forms remain unchanged. Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`,
  or `env->` markers in the generated work directory. Same-session alternating
  TEST smoke comparison passed with empty stderr: NPT-3bg
  `84,85,88,88,90,84,90 ms` (median `88ms`) versus NPT-3bh
  `83,86,84,91,87,88,87 ms` (median `87ms`), with library size reduced from
  `1034760` to `1034712` bytes. Focused native integration tests for TEST Calc
  and obfusjack completion also passed.

### [x] NPT-3bf: Fuse primitive float/double same-local add updates

- Scope: fuse only adjacent same-basic-block `FLOAD` or `DLOAD` of a local,
  followed by a matching pure float/double constant producer, `FADD` or `DADD`,
  and `FSTORE` or `DSTORE` back to the same local. The fused C must assign the
  same arithmetic expression directly to the local slot and must preserve the
  existing fallback C arithmetic shape. It must not cross labels, fold
  subtraction/multiplication/division/remainder, fold non-constant RHS values,
  change local indexes, touch long/int/object/ref locals, or alter exception
  dispatch boundaries.
- Required evidence: fresh NPT-3be generated TEST C at
  `build/neko-native-work/run-21034784798896/neko_native_impl_1.c` and
  `neko_native_impl_21.c` shows hot loops increment float/double locals through
  `PUSH_F`/`PUSH_D`, `FADD`/`DADD`, then immediate `FSTORE`/`DSTORE` to the
  same local. These instructions are primitive, non-throwing stack round trips.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving same-local
  float/double add updates use direct local assignments with no add-result
  stack round trip, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-local float/double constant add updates fuse;
  label-blocked and different-local cases retain stack traffic; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-21337326266905` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed hot float/double loop increments in `Digi.run` and `Calc.runAdd` use
  direct same-local assignments such as `locals[4].f = locals[4].f + 1.3f` and
  `locals[0].d = locals[0].d + 0.99`. Static grep found no `NEKO_JNI_FN_PTR`,
  `(*env)->`, or `env->` markers in the generated work directory. Same-session
  alternating TEST smoke comparison passed with empty stderr: NPT-3be
  `83,88,84,84,89,88,87 ms` (median `87ms`) versus NPT-3bf
  `93,85,92,86,85,87,85 ms` (median `86ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3ax: Elide unreferenced exception-exit blocks

- Scope: omit the generated `__neko_exception_exit` label and default return
  tail only for translated methods whose generated body contains no structural
  branch/reference to that label. This is a generic generated-body invariant,
  not an owner/name/benchmark special case.
- Required evidence: fresh NPT-3aw generated TEST C shows `Calc.call` and
  `Calc.runAdd` still emit `__neko_exception_exit` plus shadow-pop/default
  return despite having no `goto __neko_exception_exit`. Methods such as
  `Calc.runAll` and `Calc.runStr` do contain pending-exception branches to the
  label and must retain the block.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving no-reference methods
  omit the exception-exit block and referenced methods retain it, default TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: every generated `goto __neko_exception_exit` still has a
  matching label; methods without a generated reference omit only unreachable
  cleanup; normal return paths and shadow pops remain unchanged; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-17954422278482` built `libneko_linux_x64.so` at
  `1034872` bytes with `translated=49 rejected=0`. Generated C inspection showed
  `Calc.call` and `Calc.runAdd` omit `__neko_exception_exit`, while `Calc.runAll`
  and `Calc.runStr` keep the label for real pending-exception branches.
  Same-session smoke comparison passed with stderr empty: NPT-3aw
  `92,90,89,94,91 ms` (median `91ms`) versus NPT-3ax
  `89,85,90,92,89 ms` (median `89ms`). Focused native integration tests for
  TEST Calc and obfusjack completion also passed.

### [x] NPT-3ay: Elide same-owner static direct-call class guards

- Scope: remove the generated `targetCls` local and null guard only for
  translated direct `INVOKESTATIC` calls whose callee owner is the current
  translated owner. Keep cross-owner static calls, instance calls, pending
  exception checks, direct body dispatch, and class binding behavior unchanged.
- Required evidence: fresh NPT-3ax generated TEST C shows same-owner static
  direct calls in `Calc.runAll` materialize `jclass targetCls = (jclass)clazz`
  and then guard `if (targetCls != NULL)` before calling `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` bodies. The JVM native entry ABI supplies a
  non-null current `clazz`, and same-owner direct body calls already propagate
  that current class.
- Validation command or runtime target: focused translator/generator tests,
  fresh TEST native generation, generated-C inspection proving same-owner static
  direct calls pass `(jclass)clazz` directly with no `targetCls != NULL` guard,
  generated-C inspection proving cross-owner/instance direct call guard shape is
  unchanged, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: the optimization is owner/ABI driven rather than
  benchmark driven; no null guard is removed from cross-owner or instance
  direct calls; every post-call pending-exception check remains in place; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-18549458339348` built `libneko_linux_x64.so` at
  `1035160` bytes with `translated=49 rejected=0`. Generated C inspection showed
  `Calc.runAll` same-owner static direct calls now invoke `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` bodies with `(jclass)clazz` directly, while
  cross-owner static and instance direct calls still retain `targetCls` guards.
  Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the
  generated work directory. Same-session alternating smoke comparison passed
  with empty stderr and equal medians: NPT-3ax
  `93,88,89,84,85,89,96 ms` (median `89ms`) versus NPT-3ay
  `88,89,98,85,101,89,85 ms` (median `89ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3az: Fuse primitive integer branch producers

- Scope: fuse only adjacent same-basic-block primitive integer branch producer
  sequences in `NativeTranslator`: `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`
  followed by `IFEQ`/`IFNE`/`IFLT`/`IFGE`/`IFGT`/`IFLE`, and two such
  producers followed by `IF_ICMP*`. The fused C branch must read the primitive
  expression directly and avoid stack mutation. It must not cross labels,
  branches, try-handler boundary flushes, pending-exception dispatch, fields,
  arrays, invokes, monitors, object/ref stack operations, arithmetic, division,
  floating-point comparisons, or helper calls.
- Required evidence: fresh NPT-3ax/NPT-3ay generated TEST C contains generic
  straight-line integer branch stack round trips such as `PUSH_I(local);
  if (POP_I() != 0)` and `PUSH_I(local); PUSH_I(const); POP/POP; if (...)`.
  Source inspection shows `OpcodeTranslator.intPushExpression` already
  recognizes only primitive constants and `ILOAD`, while the central
  `NativeTranslator` bytecode walk is where multi-instruction peepholes can
  consume the branch bytecode and preserve pending-exception flush ordering.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the known
  integer branch round trips are replaced without touching object/field/invoke
  paths, default TEST smoke/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: branch targets and comparison semantics match JVM
  bytecode; fused producers are primitive-only and non-throwing; no label or
  handler boundary is crossed; pending-exception checks still flush before the
  branch when required; no JNI/JVMTI/fallback markers are introduced; timing
  does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-19020205937571` built `libneko_linux_x64.so` at
  `1034840` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.runAll` and `Digi.run` loop guards use direct primitive compare
  C, and `Tracee.toTrace` uses `if (locals[1].i != 0)` without stack mutation;
  object/field/invoke paths remained outside the fused slice. Static grep found
  no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating smoke comparison passed with empty stderr:
  NPT-3ay `89,83,83,88,88,85,91 ms` (median `88ms`) versus NPT-3az
  `87,82,89,84,105,88,85 ms` (median `87ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3ba: Fuse same-field static int add updates

- Scope: fuse only adjacent same-basic-block `GETSTATIC int`, primitive int
  constant producer, `IADD`, and matching `PUTSTATIC int` for the same owner,
  field name, and descriptor. The fused C must still call the existing
  `neko_fast_get_static_I_field_ref`, `neko_bound_class_ref`,
  `neko_ensure_class_initialized_once`, `neko_bound_field_ref`, and
  `neko_fast_set_static_I_field` helpers in the same relative order as the
  unfused sequence; it may only replace stack traffic with a primitive local
  value expression. It must not change static field-ref helper layout, static
  base/offset binding, class initialization, object/reference fields, instance
  fields, non-int primitives, subtraction/division/arithmetic in general,
  labels, try-handler boundary flushes, pending-exception dispatch, invokes,
  arrays, monitors, or helper fallback behavior.
- Required evidence: fresh NPT-3az generated TEST C shows `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` all execute `GETSTATIC Calc.errors`, push
  `1`, perform `IADD`, pop into `PUTSTATIC Calc.errors`, and then emit the
  existing static set helper sequence. This is a generic same-field update
  stack round trip and does not require changing the previously rejected
  static primitive field-ref helper shape.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the Calc
  static int updates use one primitive local and the same get/class-init/set
  helpers, default TEST smoke/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-field static int add updates fuse; all helper
  calls and class-init ordering are retained; no object/reference/instance
  field path changes; no JNI/JVMTI/fallback markers are introduced; timing does
  not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-19381987030477` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.call`, `Calc.runAdd`, and `Calc.runStr` static int updates use a
  single primitive `val` expression while retaining the existing static get,
  class bind/init, field bind, and static set helpers. Static grep found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating smoke comparison passed with empty stderr:
  NPT-3az `86,85,89,95,90,82,89 ms` (median `89ms`) versus NPT-3ba
  `83,87,94,89,85,101,88 ms` (median `88ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bb: Reject direct pending-exception reads for opcode result guards

- Scope: replace translated opcode result/yield guards of the form
  `!neko_exception_check(env)` with `neko_pending_exception_oop(thread) == NULL`
  only where `thread` is already in scope and the guard is preserving a result
  after a native/JVM call. This keeps the exception check and changes only the
  access path from `env -> JavaThread -> _pending_exception` to the existing
  direct `thread -> _pending_exception` read. Do not change the CHECKCAST guard,
  exception dispatch checks, static primitive field paths, shadow frames, JNI
  bootstrap, helper fallback behavior, or exception timing.
- Required evidence: fresh NPT-3az generated TEST C contains many
  `if (!neko_exception_check(env)) { PUSH_*... }` guards after icache, NJX,
  direct translated body, MethodHandle bridge, intrinsic, and string-switch
  calls, while the direct hard-aborting helper `neko_pending_exception_oop` is
  already emitted and used throughout translated methods for control-flow
  exception dispatch.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving call
  result guards no longer use `neko_exception_check(env)` and CHECKCAST remains
  unchanged, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: every changed guard still reads pending exception before
  consuming the result; CHECKCAST and exception-dispatch semantics remain
  unchanged; no JNI/JVMTI/fallback markers are introduced; timing does not
  regress.
- Rejected 2026-05-22. The implementation changed only `OpcodeTranslator`
  result/yield guard predicates and left CHECKCAST unchanged. Focused
  generator/audit tests passed, and fresh TEST generation
  `build/neko-native-work/run-19677117143362` built `libneko_linux_x64.so` at
  `1038488` bytes with `translated=49 rejected=0`. Generated C inspection
  showed call-result guards no longer used `neko_exception_check(env)`, the only
  remaining impl occurrences were four CHECKCAST guards, and static grep found
  no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers. Runtime smoke stayed
  functional with empty stderr, but performance/code-size evidence rejected the
  slice: first alternating run regressed NPT-3ba
  `85,85,88,96,84,88,94 ms` (median `88ms`) versus NPT-3bb
  `91,91,87,84,87,97,93 ms` (median `91ms`); a second alternating run was equal
  at median `87ms`, but combined samples still skewed slower and the library
  grew from `1034808` to `1038488` bytes. Source edits were reverted. Do not
  retry this broad predicate replacement without new code-size or branch-layout
  evidence.

### [x] NPT-3bc: Fuse primitive int arithmetic returns

- Scope: fuse only adjacent same-basic-block `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`,
  second matching int producer, `IADD` or `IMUL`, and `IRETURN` into a direct
  primitive return expression. The fused C must still execute the normal shadow
  pop before returning and must flush any pending exception dispatch before the
  return. It must not cross labels, try-handler boundary flushes, object/ref
  operations, fields, arrays, invokes, monitors, division/remainder, subtraction
  with operand-order risk, long/float/double arithmetic, or helper calls.
- Required evidence: fresh NPT-3ba generated TEST C shows generic leaf methods
  such as `Abst1.mul(II)I`, `Top.add(II)I`, and `flo.solve(II)I` load two int
  locals, emit `IADD`/`IMUL`, then immediately pop and return the result. This
  is a stack-only round trip in primitive non-throwing arithmetic.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the leaf
  arithmetic returns use direct primitive return expressions, default TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: only primitive int add/mul immediate returns fuse;
  operand order and shadow-pop behavior match the original generated sequence;
  pending-exception flush before return is preserved; no JNI/JVMTI/fallback
  markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-20027566395481` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Abst1.mul`, `Top.add`, and `flo.solve` use direct primitive int
  return expressions with `neko_shadow_pop()` retained and no stack round trip.
  Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the
  generated work directory. Same-session alternating smoke comparison passed
  with empty stderr: NPT-3ba `87,87,84,85,93,98,92 ms` (median `87ms`) versus
  NPT-3bc `88,85,86,86,84,99,94 ms` (median `86ms`). Focused native integration
  tests for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bd: Fuse primitive int arithmetic into single-arg self tail calls

- Scope: fuse only adjacent same-basic-block `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`,
  second matching int producer, `IADD`, `ISUB`, or `IMUL`, then a static
  self-recursive tail call for a method with exactly one `int` argument and a
  matching return. The fused C must assign the computed expression directly to
  the restarted local, clear the operand stack, and jump to the existing
  tail-call landing pad. It must not cross labels between the primitive
  producers and the self call. Like the existing tail-call rewrite, labels,
  lines, and frames may appear between the call and the matching return only
  when no intervening real instruction exists. It must not cross
  try-handler-covered call sites, object/ref operations, fields, arrays, invokes
  other than the proven self tail call, monitors, division/remainder,
  long/float/double arithmetic, multi-argument calls, instance receiver calls,
  or helper calls.
- Required evidence: fresh NPT-3bc generated TEST C at
  `build/neko-native-work/run-20027566395481/neko_native_impl_20.c` shows the
  generic static self-recursive `int -> void` tail path evaluating
  `locals[0].i - 1` through `PUSH_I(locals[0].i)`, `PUSH_I(1)`, `ISUB`, then
  immediately popping that value into `locals[0].i` inside the existing
  tail-call rewrite. This is stack-only primitive arithmetic immediately
  consumed by the generic TCO path.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the
  single-arg arithmetic self tail call uses a direct local assignment with the
  existing `sp = 0; goto __neko_tco_entry;`, default TEST smoke/timing
  comparison, and focused native integration tests for TEST Calc and obfusjack
  completion.
- Completion criteria: only one-argument static `int` self tail calls with
  same-block primitive int add/sub/mul argument arithmetic fuse; operand order
  for subtraction matches JVM stack order; call-site active handlers remain
  rejected; no JNI/JVMTI/fallback markers are introduced; timing does not
  regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-20650186041509` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.call(I)V` assigns `locals[0].i - 1` directly in the tail-call
  restart path while preserving the normal `L3` return epilogue. Static grep
  found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated
  work directory. Same-session alternating TEST smoke comparison passed with
  empty stderr: NPT-3bc `81,88,99,94,95,90,89 ms` (median `90ms`) versus
  NPT-3bd `88,92,88,90,86,87,95 ms` (median `88ms`). Focused native
  integration tests for TEST Calc and obfusjack completion also passed.

### [x] NPT-3be: Fuse primitive compare results into zero branches

- Scope: fuse only adjacent same-basic-block pure primitive producers feeding
  `LCMP`, `FCMPL`, `FCMPG`, `DCMPL`, or `DCMPG`, followed immediately by an
  `IFEQ`, `IFNE`, `IFLT`, `IFGE`, `IFGT`, or `IFLE` zero-branch. Initial
  producers are limited to primitive local loads and numeric constants that the
  existing translator already lowers as direct literals or local reads. The
  fused C must compute the exact existing compare-result expression into a
  local `jint __cmp` and branch on `__cmp`, preserving `FCMPL`/`FCMPG` and
  `DCMPL`/`DCMPG` NaN polarity. It must not simplify float/double comparisons
  into direct relational branches, cross labels, fold throwing/side-effecting
  operations, or alter exception dispatch boundaries.
- Required evidence: fresh NPT-3bd generated TEST C at
  `build/neko-native-work/run-20650186041509/neko_native_impl_1.c` and
  `neko_native_impl_21.c` shows `FCMPL` and `DCMPG` compare results are pushed
  to the operand stack and immediately popped by zero-branches in hot loop
  paths. The existing fallback compare expressions at `OpcodeTranslator` lines
  for `LCMP`/`FCMPL`/`FCMPG`/`DCMPL`/`DCMPG` provide the exact expression shape
  that must be reused.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving fused
  compare branches use `jint __cmp` without compare-result `PUSH_I`/`POP_I`
  round trips, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-block primitive compare-result zero-branches
  fuse; NaN polarity matches the existing fallback expression; label-blocked
  cases retain stack traffic; no JNI/JVMTI/fallback markers are introduced;
  timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-21034784798896` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed pure `FCMPL`/`DCMPG` zero-branches use local `jint __cmp` branches in
  `Digi.run` and `Calc.runAdd`, while the mixed `double -> float` conversion
  path remained on the fallback stack form. Static grep found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating TEST smoke comparison passed with empty
  stderr: NPT-3bd `82,82,90,91,87,89,88 ms` (median `88ms`) versus NPT-3be
  `85,92,87,83,88,93,97 ms` (median `88ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bi: Intrinsify `java/lang/String.length()I`

- Scope: lower only exact `INVOKEVIRTUAL java/lang/String.length:()I` sites to
  a generic native helper that pops the receiver, preserves the existing
  null-receiver NPE path, reads the already-bound `String.value:[B` and
  `String.coder:B` offsets, loads the backing byte-array length through the
  existing array length offset, and pushes `value.length >> coder`. The helper
  must hard-abort if String field metadata, object/array layout,
  compressed-oop decoding, or GC load-barrier prerequisites are unavailable. It
  must not apply to other owners, names, descriptors, static/special/interface
  calls, user methods, or virtual dispatch in general; it must not add
  JNI/JVMTI/fallback behavior or bypass the implicit-exception helper.
- Required evidence: fresh NPT-3bh generated TEST C at
  `build/neko-native-work/run-22094701424303/neko_native_impl_22.c` still
  dispatches `java/lang/String.length()I` through `neko_icache_dispatch` inside
  the hot `Calc.runStr` loop. That loop runs about 101 iterations per
  `runStr`, and `runAll` calls `runStr` 10000 times, so the site executes about
  one million virtual dispatches per TEST run. Existing native infrastructure
  already binds `java/lang/String.value:[B` and `java/lang/String.coder:B`
  offsets for string concat/literal support, reads object fields with
  `neko_barrier_load_oop_field`, decodes narrow oops, and reads array lengths
  through `neko_const_array_length_offset()`.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving
  `String.length` sites emit `neko_fast_string_length` and no
  `neko_icache_dispatch` for that call, default TEST smoke/timing comparison,
  focused native integration tests for TEST Calc and obfusjack completion, and
  focused runtime coverage for null, Latin1, UTF16, and concat-produced
  strings.
- Completion criteria: only exact `String.length()I` calls are intrinsified;
  null receivers still raise and route through the normal implicit NPE helper;
  Latin1 and UTF16 length results match JVM semantics; generated C has no
  forbidden JNI wrappers or fallback markers; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed for
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-22968063094268` built `libneko_linux_x64.so` at
  `1036696` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.runStr` calls `neko_fast_string_length(__str, g_off_10,
  g_off_11)` and no longer uses `neko_icache_dispatch` for
  `java/lang/String.length()I`; null receivers still route through
  `neko_raise_implicit_exception_ref`. Strict forbidden JNI grep for
  `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Same-session
  alternating TEST smoke comparison passed with empty stderr: NPT-3bh
  `85,85,95,106,86,85,88 ms` (median `86ms`) versus NPT-3bi
  `73,69,68,70,85,84,71 ms` (median `71ms`). Focused native integration tests
  for TEST Calc, obfusjack completion, and implicit exception/String length
  runtime coverage passed.

### [x] NPT-3bj: Reject intrinsic `String.length()` compare-branch fusion

- Scope: fuse only adjacent same-basic-block `ALOAD` receiver, exact
  `INVOKEVIRTUAL java/lang/String.length:()I`, pure int constant/local
  producer, and `IF_ICMP*` branch. The fused C must preserve the exact
  null-receiver implicit NPE path, check pending exception before evaluating
  the branch, and compare the computed length directly against the second int
  operand without materializing the result through the operand stack. The first
  implementation is restricted to call sites with no active try handlers so a
  null receiver can keep the existing `__neko_exception_exit` route; try/catch
  covered sites must retain the unfused opcode sequence. It must not fuse other
  methods, other owners, non-virtual calls, object/reference compares,
  label-crossing patterns, concat paths, or any path where exception handler
  routing is not proven identical.
- Required evidence: fresh NPT-3bi generated TEST C at
  `build/neko-native-work/run-22968063094268/neko_native_impl_22.c` shows
  `Calc.runStr` now emits `neko_fast_string_length` for the hot
  `String.length` call, but still pushes the result, pushes constant `101`,
  checks pending exception, then pops both ints in `IF_ICMPGE`. This stack
  round trip executes once per append-loop iteration after the accepted length
  intrinsic.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving hot
  `String.length` compare branches use a direct local length compare with
  pending exception check retained, default TEST smoke/timing comparison, and
  focused native integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-block no-active-handler exact `String.length`
  int-compare branches fuse; null receiver behavior and pending-exception
  ordering remain identical; try/catch-covered cases stay unfused; generated C
  has no forbidden JNI wrappers or fallback markers; timing does not regress.
- Rejected 2026-05-22. The implementation tested only the no-active-handler
  same-block `String.length` plus `IF_ICMP*` branch shape. It preserved the
  normal implicit NPE helper and emitted an explicit pending exception check
  before the fused branch. Focused generator/audit tests passed, and fresh TEST
  generation `build/neko-native-work/run-23558603672735` built
  `libneko_linux_x64.so` at `1036744` bytes with `translated=49 rejected=0`.
  Generated C inspection showed `Calc.runStr` used a direct `__len` local and
  no longer emitted `PUSH_I(neko_fast_string_length(...))` or `PUSH_I(101)`;
  strict forbidden JNI grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was
  clean. Runtime stayed functional, but same-session alternating TEST timing
  rejected the slice: NPT-3bi `69,72,77,71,74,72,77 ms` (median `72ms`) versus
  NPT-3bj `73,72,77,87,74,72,75 ms` (median `74ms`), and the native library
  grew from `1036696` to `1036744` bytes. Source/test edits were reverted. Do
  not retry this branch-fusion shape without new code-size or branch-layout
  evidence.

### [x] NPT-3bk: Reject guarded generated concat literal string binding

- Scope: when native string-concat pattern emission creates a generated static
  `jstring` literal slot, emit a slot-null guard around the existing
  `neko_bind_string_slot(thread, env, &slot, "...")` call. The change may only
  affect generated concat literal slots created by `literalStringProducer`; it
  must not change owner string binding, intern/global-ref creation,
  `neko_concat_append`, `StringConcatFactory` recipe lowering, raw string
  construction, JNI usage, exception behavior, or GC barriers.
- Required evidence: fresh NPT-3bi generated TEST C at
  `build/neko-native-work/run-22968063094268/neko_native_impl_22.c` shows the
  hot `Calc.runStr` loop executes `neko_bind_string_slot(thread, env,
  &__neko_concat_lit_0, "ax")` on every concat iteration before
  `neko_concat_append`. The helper itself returns immediately when `slot !=
  NULL && *slot != NULL` (`NativeBindSupportEmitter`), so the generated guard
  is the same predicate moved to the call site. The helper already writes a
  global reference into the static slot, and concurrent first binds are already
  governed by the helper's unsynchronized slot check; adding the same outer
  check must not introduce a new race class.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving concat
  literal sites emit `if (__neko_concat_lit_N == NULL)
  neko_bind_string_slot(...)` and no hot unconditional bind call, strict
  forbidden JNI/fallback grep, default TEST smoke/timing comparison, and
  focused native integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only generated concat literal slots gain the guard;
  first-use bind still hard-aborts on missing thread/env/global-ref failure;
  subsequent uses reuse the same global slot; generated C has no forbidden
  JNI/JVMTI/fallback markers; timing does not regress.
- Rejected 2026-05-22. Focused generator/audit tests passed and fresh TEST
  generation `build/neko-native-work/run-23910172237245` built
  `libneko_linux_x64.so` at `1036712` bytes with `translated=49 rejected=0`.
  Generated C inspection showed the intended
  `if (__neko_concat_lit_0 == NULL) { neko_bind_string_slot(...) }` shape in
  `Calc.runStr`, and strict JNI marker grep for `NEKO_JNI_FN_PTR`, `(*env)->`,
  and `env->` was clean. The slice was rejected before timing acceptance
  because the generated outer guard performs a plain non-atomic read of a
  function-local static `jstring` while `neko_bind_string_slot` publishes the
  slot through a plain non-atomic write. Concurrent first use would therefore
  keep the existing duplicate-bind race and add a C data-race read in generated
  hot code. Source/test edits were reverted. Do not retry local static concat
  literal slot guarding without atomic publication and losing-global-ref
  cleanup evidence.

### [x] NPT-3bl: Reject exact integer `java/lang/Math.min` intrinsic

- Scope: lower only exact `INVOKESTATIC java/lang/Math.min:(II)I` and
  `INVOKESTATIC java/lang/Math.min:(JJ)J` bytecode sites to direct primitive C
  comparisons after popping both operands in JVM argument order. The change must
  not affect `Math.max`, float/double overloads, non-JDK owners, translated
  application methods, method handles/reflection, class initialization,
  exception paths, object/GC behavior, or NJX in general.
- Required evidence: fresh generated obfusjack native C at
  `build/neko-native-work/run-23052048100862/neko_native_impl_48.c:29` and
  `neko_native_impl_65.c:26` shows exact `Math.min(II)I` sites still dispatch
  through `neko_njx_S_I_II`; `neko_native_impl_55.c:55` shows exact
  `Math.min(JJ)J` still dispatches through `neko_njx_S_J_JJ`. These wrappers
  build argument arrays, install/restore Java handle blocks, check thread
  state, call through the guarded call stub, and marshal results for a pure
  integer operation. Java `Math.min` for `int` and `long` is exactly
  `a <= b ? a : b`; restricting the slice to integer overloads avoids
  floating-point NaN and signed-zero semantics.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh obfusjack native generation, generated-C inspection proving the
  exact `Math.min(II)I` and `(JJ)J` sites emit direct `PUSH_I`/`PUSH_L`
  compares and no `neko_njx_*` call for those sites, strict forbidden JNI grep,
  obfusjack native runtime/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: only exact integer `Math.min` overloads are lowered;
  operand order and signed comparison semantics match Java; no pending
  exception check is introduced for the pure operation; generated C has no
  forbidden JNI/JVMTI/fallback markers; obfusjack timing does not regress.
- Implementation note 2026-05-22: the first generated form kept a call to
  `neko_ensure_class_initialized_once` at every lowered `Math.min` site.
  Same-session obfusjack timing rejected that shape because the steady-state
  helper call offset the NJX removal. The next implementation keeps the same
  first-use class initialization, but emits the existing initialized-slot check
  in generated code so the hot path enters the helper only while the
  `java/lang/Math` slot is not initialized.
- Rejected 2026-05-22. Focused generator/audit tests passed. Fresh obfusjack
  generation `build/neko-native-work/run-24493430020950` built
  `libneko_linux_x64.so` at `1804984` bytes with `translated=93 rejected=0`.
  Generated C inspection proved the target sites emitted direct primitive
  compares with a cold `g_cls_initialized_26` guard:
  `neko_native_impl_48.c:29` and `neko_native_impl_65.c:26` for
  `Math.min(II)I`, and `neko_native_impl_55.c:55` for `Math.min(JJ)J`. Strict
  grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Runtime
  rejected the slice: one NPT-3bl obfusjack run dumped core before completion,
  and the completed same-session alternating runs regressed against the cached
  pre-change obfusjack jar. Baseline Platform `44,45,46,46,45 ms` (median
  `45ms`) versus NPT-3bl completed Platform `52,46,47,47 ms` (median
  `47ms`); baseline Virtual `33,32,34,49,32 ms` (median `33ms`) versus NPT-3bl
  completed Virtual `37,43,34,38 ms` (median `37.5ms`); baseline Seq
  `16,16,16,18,18 ms` (median `16ms`) versus NPT-3bl completed Seq
  `17,17,17,17 ms` (median `17ms`). Source/test edits were reverted. Do not
  retry this exact `Math.min` intrinsic shape without new evidence explaining
  the crash and branch/code-layout regression.

### [x] NPT-3bm: Reject owner-bound strings for StringBuilder concat literals

- Scope: for the existing native StringBuilder concat pattern, replace
  generated function-local static literal slots with the same owner-bound
  string cache expression used by normal LDC and indy concat literals. The
  change may only affect literal producers inside the already-recognized
  StringBuilder concat pattern. It must not change string construction,
  `neko_concat_append`, raw concat allocation, owner binding semantics,
  non-literal producers, StringConcatFactory lowering, JNI usage, exception
  behavior, or GC barriers.
- Required evidence: fresh NPT-3bi TEST generated C shows `Calc.runStr` calls
  `neko_bind_owner_strings_14(thread, env)` at method entry and uses
  `neko_bound_string(thread, env, &g_str_37, "")` for the normal LDC empty
  string, but the hot `"ax"` concat literal uses a separate
  `static jstring __neko_concat_lit_0` plus `neko_bind_string_slot(...)` inside
  the append loop. The owner bind function for `pack/tests/bench/Calc` does
  not include `"ax"`, so the same literal is rebound through the helper on
  every loop iteration. `OpcodeTranslator.cachedStringExpression` already
  registers literals with `CCodeGenerator.registerOwnerStringReference`, and
  `methodMayUseBoundStrings` already adds the owner-string bind call for
  methods containing LDC strings.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the hot
  concat literal uses `neko_bound_string(thread, env, &g_str_N, "ax")`, the
  owner bind function contains `"ax"`, and no `__neko_concat_lit_N` local
  static remains for that pattern, strict forbidden JNI grep, default TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: only StringBuilder concat literal producers switch to
  owner-bound string slots; first-use/global-ref binding stays centralized in
  the existing owner-string binder; no new static local publication path is
  introduced; generated C has no forbidden JNI/JVMTI/fallback markers; timing
  does not regress.
- Rejected 2026-05-22. Focused generator/audit tests passed. Fresh TEST
  generation `build/neko-native-work/run-24803874241514` built
  `libneko_linux_x64.so` at `1036728` bytes with `translated=49 rejected=0`.
  Generated C inspection proved `Calc.runStr` moved the hot `"ax"` literal to
  `neko_bound_string(thread, env, &g_str_38, "ax")`, `neko_bind_owner_strings_14`
  binds `g_str_38` once at owner-string bind time, and no
  `__neko_concat_lit_N` local static slot remained for the pattern. Strict grep
  for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Runtime smoke
  passed, but same-session alternating TEST timing rejected the slice:
  NPT-3bi `76,76,74,73,79,72,75 ms` (median `75ms`) versus NPT-3bm
  `72,71,83,70,87,80,76 ms` (median `76ms`). Source/test edits were reverted.
  Do not retry this owner-bound concat literal shape without new code-layout or
  inlining evidence.

### [x] NPT-3bn: Inline concat append wrapper before existing String.concat NJX

- Scope: add a prelude-local inline concat append wrapper that preserves the
  existing `neko_concat_append` semantics: normalize null accumulator and null
  rhs to `neko_string_null(env)`, then call the existing inline
  `neko_require_fast_string_concat` path. Update generated concat accumulation
  paths to use the inline wrapper so hot loops do not pay an extra hidden C
  function call before the existing String.concat NJX dispatch. The change must
  not construct String payloads natively, change String.concat semantics,
  alter null-to-"null" behavior, remove hard aborts, change allocation/GC
  behavior, or affect non-concat call sites.
- Required evidence: fresh NPT-3bi generated C shows `Calc.runStr` calls
  hidden external `neko_concat_append(...)` once per append-loop iteration.
  `NativeFastObjectAccessEmitter` defines `neko_require_fast_string_concat` as
  `NEKO_FAST_INLINE`, but `neko_concat_append` itself is emitted as a hidden
  out-of-line support helper; `neko_concat_accumulate` is inline yet still
  calls that hidden helper when `acc != NULL`. This adds a native function-call
  layer before the already-required NJX String.concat dispatch.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving concat
  paths call the inline wrapper and hot `Calc.runStr` no longer calls hidden
  `neko_concat_append`, strict forbidden JNI grep, default TEST smoke/timing
  comparison, and focused native integration tests for TEST Calc and obfusjack
  completion.
- Completion criteria: inline wrapper preserves exact previous null
  normalization and `neko_require_fast_string_concat` call; no raw String
  construction or fallback is introduced; generated C has no forbidden
  JNI/JVMTI/fallback markers; timing does not regress.
- Completed 2026-05-22: focused translator/generator/audit tests passed, fresh
  TEST native generation produced `build/npt-3bn/TEST-native.jar` from
  `build/neko-native-work/run-25167378564670` with `translated=49` and
  `rejected=0`. Generated `neko_native_impl_22.c` calls
  `neko_concat_append_inline(...)` in `Calc.runStr` and no longer calls hidden
  `neko_concat_append(...)` on the append-loop hot path. Strict forbidden JNI
  grep over the fresh run directory returned no matches. TEST smoke completed
  with `Calc: 74ms`. Alternating timing comparison versus the accepted NPT-3bi
  jar was NPT-3bi `78,72,72,76,71,88,81 ms` (median `76ms`) versus NPT-3bn
  `71,67,69,67,79,71,68 ms` (median `69ms`). Focused native integration tests
  for TEST Calc and obfusjack completion passed.

### [x] NPT-3bo: Post-P35 bottleneck selection and current-head gate

- Scope: capture a fresh current-head post-P35 performance and generated-code
  evidence set before selecting the next implementation slice. This is an
  audit/selection subtask only. It may inspect generated TEST and obfusjack
  native artifacts, runtime timings, `hs_err` files, generated C, and support
  helper call shapes. It must not change runtime behavior, special-case a
  sample, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm shapes, or implement
  raw native String construction without complete compact-string, allocation,
  exception, and GC-barrier evidence.
- Required evidence: fresh current-head TEST native artifact after NPT-3bn
  shows `translated>0`, `rejected=0`, strict forbidden JNI grep clean, and
  `Calc.runStr` still reaches the remaining hot loop through
  `neko_fast_string_length` plus `neko_concat_append_inline` and the
  `neko_njx_V_L_L` String.concat call-stub path. Current-head TEST timing must
  be repeated at least 5 times. Obfusjack native timing/completion evidence
  must include Seq/Platform/Virtual when the fixture output exposes them.
- Validation command or runtime target: fresh TEST and obfusjack native
  generation when no current-head artifact is available, strict generated-C
  grep for forbidden JNI/JVMTI/fallback markers, repeated TEST native runs,
  repeated obfusjack native runs, generated-C call-shape counts for remaining
  hot helper/NJX/string paths, and inspection for `hs_err` or abort logs.
- Completion criteria: record a concrete next implementation row with scope,
  evidence, validation target, and completion criteria, or record that no
  implementation is justified because the evidence chain is incomplete. The
  selected row must be generic and architecture-level, must preserve JVM ABI and
  full native invariants, and must not depend on one fixture or benchmark
  artifact.
- Completed 2026-05-22: fresh current-head TEST generation produced
  `build/npt-3bo/TEST-native.jar` from
  `build/neko-native-work/run-25611539669398` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1036952` bytes. Fresh
  obfusjack generation produced `build/npt-3bo/obfusjack-native.jar` from
  `build/neko-native-work/run-25625820070350` with `translated=93`,
  `rejected=0`, and `libneko_linux_x64.so` size `1810840` bytes. Strict
  forbidden JNI grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` over both
  run directories returned no matches, and no newer `hs_err_pid*.log` appeared.
  Current-head TEST timing was `72,74,74,74,72,75,75 ms` (median `74ms`).
  Obfusjack repeated timing was Platform `46,45,46,43,36 ms` (median `45ms`),
  Virtual `39,41,34,38,48 ms` (median `39ms`), Seq `18,17,18,17,17 ms`
  (median `17ms`), Parallel `1ms`, and VThreads `1ms`. Generated
  `Calc.runStr` still contains `PUSH_O(locals[0].o)` followed by a
  `String.length` `POP_O()` consumer, and `neko_concat_append_inline(...)`
  followed by `PUSH_O(__fastConcat)` and immediate `neko_store_local_oop_ref`.
  Selected NPT-3bp/P37 to fuse only those same-basic-block operand-stack
  shuffles while preserving the existing fast String.length helper, existing
  NJX String.concat path, pending-exception checks, and local-root storage.

### [x] NPT-3bp: Reject local String.length and concat-result store stack traffic

- Scope: add generic same-basic-block peephole lowering for two already-proven
  generated shapes: `ALOAD local; INVOKEVIRTUAL java/lang/String.length()I`
  may read `locals[local].o` directly into the existing
  `neko_fast_string_length` intrinsic, and a recognized StringBuilder concat
  producer immediately followed by `ASTORE local` may store the concat result
  directly through `neko_store_local_oop_ref`. This must not change
  String.concat allocation/copy behavior, introduce raw native String
  construction, remove the NJX String.concat call, bypass local-root storage,
  move or remove pending-exception checks, cross labels or handler boundaries,
  special-case Calc, or alter non-adjacent operand-stack semantics.
- Required evidence: fresh P36 generated TEST C shows `Calc.runStr` line 24
  emits `PUSH_O(locals[0].o)` and line 25 immediately pops that same operand
  for the already-accepted `neko_fast_string_length` path. The same method line
  30 emits accepted `neko_concat_append_inline(...)` and `PUSH_O(__fastConcat)`,
  and line 31 immediately pops the same reference into
  `neko_store_local_oop_ref`. `runAll` invokes `runStr` inside the 10000-loop,
  and `runStr` appends until String length reaches `101`, so both shuffles are
  hot without relying on a fixture-specific semantic shortcut.
- Validation command or runtime target: focused translator/generator/audit
  tests covering direct local String.length and concat-result direct store,
  fresh TEST native generation, generated-C inspection proving the two
  `PUSH_O`/`POP_O` pairs are removed only for adjacent same-block patterns,
  strict forbidden JNI grep, repeated TEST timing comparison against the P36
  current-head baseline, and focused native integration tests for TEST Calc and
  obfusjack completion.
- Completion criteria: generated code removes only the proven redundant
  operand-stack traffic; null behavior, pending-exception ordering,
  `neko_store_local_oop_ref`, NJX String.concat semantics, JNI policy, and GC
  rooting remain unchanged; no forbidden JNI/JVMTI/fallback markers appear; and
  timing does not regress.
- Rejected 2026-05-22: focused translator/generator/audit tests passed. Fresh
  TEST generation produced `build/npt-3bp/TEST-native.jar` from
  `build/neko-native-work/run-26009768901566` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1037048` bytes. Generated
  `Calc.runStr` inspection proved the intended shape: the hot
  `String.length` path directly reads `locals[0].o`, and the concat result is
  stored directly through `neko_store_local_oop_ref` without
  `PUSH_O(__fastConcat)` plus immediate `ASTORE`. Strict forbidden JNI grep
  was clean and TEST smoke completed, but the same-session alternating timing
  gate rejected the slice: P36 `69,71,89,71,68,77,70 ms` (median `71ms`)
  versus P37 `74,68,67,74,78,72,80 ms` (median `74ms`). Source/test edits were
  reverted. Do not retry this operand-stack shuffle fusion without new
  code-layout or optimizer evidence.

### [x] NPT-3bq: Reject raw native String.concat until returned-String publication invariants are proven

- Scope: compare JDK 21 `java/lang/String.concat`,
  `StringConcatHelper.simpleConcat`, `newArray`, `newString`,
  `String(byte[],byte)`, and `String(String)` semantics against the current
  native raw allocation, compact-string field, byte-array allocation, copy,
  exception, TLAB refill, OOME/overflow, GC barrier, and strict-GC capability
  surface. This is an evidence-only selection subtask. It must not implement
  raw native String construction, special-case Calc or obfusjack, retry
  rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm/NPT-3bp shapes, introduce
  JNI/JVMTI/original-bytecode fallback, weaken hard abort behavior, or change
  runtime behavior.
- Required evidence: local JDK bytecode for `String.concat` and
  `StringConcatHelper` proving empty-string identity/copy behavior,
  compact-string coder propagation, overflow/OOME behavior, byte-array
  allocation size calculation, and final `String` field initialization; current
  generated native support evidence for `neko_require_fast_string_concat` still
  crossing `neko_njx_V_L_L`; current raw string literal allocation and
  `neko_store_oop_raw` evidence showing which allocation/barrier pieces already
  exist; and explicit missing-invariant notes for any unsupported coder,
  allocation, GC, or exception path.
- Validation command or runtime target: source and bytecode inspection,
  generated-C inspection on the fresh P36/P37 native artifacts, strict
  forbidden JNI/fallback marker review of the current native support surface,
  and subagent audit cross-checks.
- Completion criteria: record either a concrete next implementation row with a
  complete invariant/evidence chain and runtime validation target, or record a
  no-go/rejection because a required compact-string, allocation, exception, or
  GC invariant is not yet proven. No source implementation may start until this
  row is completed and committed.
- Completion evidence: rejected/no-go 2026-05-22. Local JDK is OpenJDK
  `21.0.11+10`, and bytecode inspection proves `String.concat(String)` first
  evaluates `rhs.isEmpty()` (preserving null-rhs NPE), returns the original
  receiver for empty rhs, and otherwise dispatches to
  `StringConcatHelper.simpleConcat`. `simpleConcat` preserves additional
  empty-side copy behavior with `new String(other)`, computes compact-string
  coder and length through `mix`, allocates byte arrays through `newArray`,
  copies through `String.getBytes(byte[], int, byte)`, and returns
  `newString(byte[], long)`. `newArray` throws Java
  `OutOfMemoryError("Overflow: String length out of range")` for overflow and
  uses `Unsafe.allocateUninitializedArray(Byte.TYPE, len)`. Current native
  support still routes concat through `neko_require_fast_string_concat` and
  `neko_njx_V_L_L`; raw String allocation exists only in string literal/intern
  binding, not as a generic returned Java object path. The audit did not find
  complete evidence for freshly allocated returned `String` publication under
  G1, Serial, Parallel, ZGC, and Shenandoah, nor a generic Java-exception path
  matching JDK concat overflow and rhs-null behavior. Missing invariants are
  returned raw `String`/`byte[]` publication without constructor/NJX,
  collector-specific or generic barriered `String.value` initialization, exact
  Latin1/UTF16 `String.getBytes` copy/inflation, Java OOME/NPE behavior, and
  empty-side identity/copy behavior. No raw concat implementation is justified
  until those prerequisites are proven. The next selected implementation slice
  is NPT-3br/P39, which reuses resolved static-field refs in the already-proven
  same-field static int add/update fusion without touching raw String
  construction.

### [x] NPT-3br: Reject resolved static-field ref reuse in fused static int add updates

- Scope: for the existing generic same-field fusion
  `GETSTATIC int; const-int; IADD; PUTSTATIC same int field`, resolve the
  `neko_static_field_ref` once and reuse that same carrier for both read and
  write. This must preserve class-initialization ordering, field metadata
  hard-aborts, volatile access flags, static-base handling, same-field proof,
  and non-fused behavior. It must not change raw String construction, retry any
  rejected concat/Math/stack-shuffle shape, introduce JNI/JVMTI/original-
  bytecode fallback, or special-case Calc or obfusjack.
- Required evidence: fresh P36 generated TEST C emits
  `neko_fast_get_static_I_field_ref(env, &g_static_field_ref_3)` and then
  separately emits `neko_bound_class_ref`, `neko_ensure_class_initialized_once`,
  `neko_bound_field_ref`, and `neko_fast_set_static_I_field` for the same
  proven field. `neko_static_field_ref` already carries class, init, field,
  static-base, offset, and access-flag slots; `tryStaticIntAddUpdateFusion`
  already proves same owner/name/descriptor and same basic block.
- Validation command or runtime target: focused generator coverage for the
  fused same-field shape, fresh TEST native generation, generated-C inspection
  proving the duplicate class/init/field binding sequence is removed only for
  the fused same-field update, strict forbidden JNI/fallback grep, repeated
  same-session TEST timing against the current-head baseline, and focused
  TEST/obfusjack native smoke.
- Completion criteria: generated static int updates reuse one resolved
  `neko_static_field_ref` while retaining hard-abort metadata checks and exact
  field semantics; non-same-field updates remain unfused; no forbidden markers
  appear; timing does not regress.
- Completion evidence: rejected 2026-05-22. Implementation added a generated
  `neko_fast_add_static_I_field_ref` helper that resolved class/init/field
  once, read the current int field value through existing hard-abort direct
  metadata, and wrote the updated value through the existing static int setter.
  Focused generator/hot-path audit tests passed after fixing helper emission.
  Fresh TEST generation produced `build/npt-3br/TEST-native.jar` from
  `build/neko-native-work/run-26581728457250` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1035992` bytes. Generated
  `Calc.runStr` contained only `neko_fast_add_static_I_field_ref(env,
  &g_static_field_ref_3, (jint)(1))` for the fused static update, and strict
  forbidden JNI grep over the run directory returned no matches. Runtime timing
  rejected the slice: P36 `71,69,66,71,74,69,72 ms` (median `71ms`) versus
  P39 `80,68,71,71,75,82,74 ms` (median `74ms`). Source/test edits were
  reverted. Do not retry this helper shape without new inlining or code-layout
  evidence.

### [x] NPT-3bs: Post-P39 bottleneck selection and raw-publication prerequisite decision

- Scope: re-audit the current post-P35/P39 state before selecting another
  runtime implementation. This is an evidence-only selection subtask. It may
  inspect generated C, runtime timings, native support helpers, existing todo
  prerequisites, and subagent audit results. It must not implement code,
  special-case a fixture, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm/
  NPT-3bp/NPT-3br shapes, or implement raw native `String.concat` before the
  returned-String publication invariants from NPT-3bq are proven.
- Required evidence: current generated TEST/obfusjack C showing the remaining
  hot runtime path, strict JNI/fallback marker status, current baseline timings
  after reverted P39 source, and explicit comparison between any non-raw-string
  candidate and the raw-publication prerequisite route.
- Validation command or runtime target: source/generated-C inspection, repeated
  runtime timing where a fresh current artifact is needed, strict forbidden
  marker grep, and subagent cross-checks for both generic hot-path candidates
  and raw returned-String prerequisites.
- Completion criteria: record a concrete next implementation/prerequisite row
  with complete scope, evidence, validation target, and completion criteria, or
  record that no implementation is justified yet because the evidence chain is
  incomplete.
- Completion evidence: completed 2026-05-22. Fresh current-head TEST
  generation after the reverted P39 source produced
  `build/npt-3bs/TEST-native.jar` from
  `build/neko-native-work/run-26815840155097` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1035992` bytes. Fresh
  obfusjack generation produced `build/npt-3bs/obfusjack-native.jar` from
  `build/neko-native-work/run-26860456274238` with `translated=93`,
  `rejected=0`, and `libneko_linux_x64.so` size `1810760` bytes. Strict
  forbidden JNI grep over both run directories returned no matches. Current
  TEST timing was `67,69,70,69,70,70,72 ms` (median `70ms`). Current obfusjack
  timing was Platform `43,47,44,42,45 ms` (median `44ms`), Virtual
  `40,38,43,44,32 ms` (median `40ms`), Seq `17,17,17,17,22 ms` (median
  `17ms`), and Parallel `1ms`. Generated TEST still has the hot
  `neko_fast_string_length` plus `neko_concat_append_inline` path, and the
  inline append still reaches the `neko_njx_V_L_L` `String.concat` call stub.
  Generated current artifacts contain 58 TEST and 182 obfusjack `neko_njx_*`
  call sites, with one TEST `neko_concat_append_inline` and one generated
  concat literal binding at the hot `runStr` site. A sidecar audit proposed
  inlining `neko_bound_method_ref` as a generic micro-optimization, but the
  current large blocker remains raw returned `String` publication; the method
  binding slice is deferred because it cannot plausibly close the TEST
  `<=20ms` gap. Selected NPT-3bt/P41 to prove and harden the generic returned
  fresh oop publication/rooting/barrier/exception prerequisite without
  replacing `String.concat` yet.

### [x] NPT-3bt: Reject/defer returned fresh oop publication until strict-GC barrier mapping is fixed

- Scope: implement the smallest generic prerequisite needed before raw native
  `String.concat`: a returned-fresh-object publication surface for translated
  native object returns. Scope is limited to freshly allocated object/array
  graphs, immediate local rooting before any possible safepoint or VM/NJX call,
  return-handle to raw-oop handoff, collector-valid field/array store barriers,
  and Java exception propagation for allocation/length failures. This must not
  replace `String.concat`, special-case benchmark code, add JNI/JVMTI/original
  bytecode fallback, introduce Java helper layers, or weaken hard-abort
  behavior for missing native capabilities.
- Required evidence: `ARETURN` currently returns a `jobject` from translated
  native code; object-return dispatch converts handle to raw oop and restores
  the handle window before returning to the trampoline; existing
  `neko_direct_oop_to_handle` and pre-reserved local root slots provide local
  rooting primitives but are not yet tied to freshly constructed returned
  object graphs; raw primitive array allocation currently returns handles and
  aborts on negative/allocation failures; string literal binding allocates
  `byte[]` and `String` only for bind-time/intern paths; `neko_store_oop_raw`
  colors ZGC oops but ordinary object field stores use the full selected
  pre/post barrier path.
- Validation command or runtime target: focused generator/runtime coverage for
  freshly allocated returned object and byte-array graphs, source/generated-C
  inspection proving immediate rooting and no forbidden JNI/JVMTI/original-
  bytecode fallback, `R-build`, `R-test`, `R-native-test`, `R-inspect`, and GC
  strict runs under G1, Serial, Parallel, ZGC with `ZVerifyViews`, and
  Shenandoah verification. Negative coverage must prove length/allocation/
  overflow failures become Java exceptions where the raw returned-object
  surface claims Java-equivalent behavior, otherwise the path must hard abort
  before being used by raw `String.concat`.
- Completion criteria: a generic returned fresh oop graph can survive the
  translated return handoff and GC/safepoint pressure under all required
  collectors, with exact recorded limits for which Java exception paths are
  implemented versus still blocking raw concat. Raw native `String.concat`
  remains out of scope until this row is completed and committed.
- Completion evidence: rejected/deferred 2026-05-22. The implementation
  hardened object-return dispatch by converting non-null return handles through
  a generated `neko_prepare_return_oop(thread, __ret, "sigN")` before
  restoring the handle window. Focused generator and hot-path audit tests
  passed. Fresh TEST generation produced `build/npt-3bt/TEST-native.jar` from
  `build/neko-native-work/run-27224871548608` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1035848` bytes; fresh
  obfusjack generation produced `build/npt-3bt/obfusjack-native.jar` from
  `build/neko-native-work/run-27238526919313` with `translated=93`,
  `rejected=0`, and `libneko_linux_x64.so` size `1803816` bytes. Generated C
  used `neko_prepare_return_oop` before `neko_handle_restore`, and strict
  forbidden JNI grep over both run directories returned no matches. Default
  TEST timing was `71,70,68,69,84 ms` (median `70ms`); obfusjack smoke was
  green with Platform `44,43,32 ms`, Virtual `46,36,46 ms`, Seq `17,17,17 ms`,
  and Parallel `1ms`. G1, Serial, and Parallel TEST GC runs passed with Calc
  `69ms`, `71ms`, and `68ms`. ZGC with `ZVerifyViews` and Shenandoah with
  verification aborted during native layout initialization before the changed
  object-return path. The same ZGC/Shenandoah bootstrap failure reproduces on
  the pre-P41 `build/npt-3bs/TEST-native.jar` baseline. Patch-debug evidence
  shows BarrierSet `tag=5`, VMStruct constants `z=-1` and `shen=-1`,
  `use_zgc=0`, `use_shen=0`, and the selected GC barrier path not ready. The
  source/test implementation was reverted. Do not resume this row until the
  strict-GC barrier mapping/capability prerequisite is fixed and validated.

### [x] NPT-3bu: Fix strict-GC barrier tag and capability detection for ZGC/Shenandoah bootstrap

- Scope: limit the implementation to generic HotSpot GC barrier identification
  and readiness detection during native layout/bootstrap. It must map or
  structurally detect current ZGC and Shenandoah BarrierSet layouts so the
  native runtime selects a complete supported barrier path, or hard-aborts with
  an exact missing-symbol or missing-capability diagnostic. This must not skip
  classes or methods, fall back to JNI/JVMTI/original bytecode, disable
  ZGC/Shenandoah, weaken barriers, implement raw `String.concat`, or change
  unrelated opcode/runtime behavior.
- Required evidence: strict ZGC patch-debug bootstrap for both NPT-3bt and the
  NPT-3bs baseline reports a live BarrierSet `tag=5`, missing VMStruct
  ZGC/Shenandoah tag constants (`z=-1`, `shen=-1`), `use_zgc=0`,
  `use_shen=0`, and a selected barrier kind whose required path is not ready,
  causing `[neko-bootstrap] native layout initialization failed`. The failing
  invariant is bootstrap selecting/validating GC barrier capability from
  incomplete tag metadata before any translated object-return change executes.
- Validation command or runtime target: focused generator/source tests for the
  barrier detection shape, fresh TEST native generation, `R-inspect` strict
  forbidden JNI/fallback grep, TEST native runs under G1, Serial, Parallel,
  ZGC with `ZVerifyViews`, and Shenandoah verification. If ZGC or Shenandoah
  required symbols are absent, the runtime must hard-abort with an exact
  missing capability message rather than misclassifying the collector or
  falling back.
- Completion criteria: fresh generated TEST initializes native layout under all
  required supported collectors or fails closed with exact capability
  diagnostics for genuinely missing required VM support; no forbidden fallback
  markers appear.
- Completion evidence 2026-05-22: implemented generic JDK 21 BarrierSet tag
  fallbacks for Shenandoah `tag=4` and ZGC `tag=5`, ordered strict-GC
  detection before CardTable/G1 families, removed the unsafe
  `ZGlobalsForVMStructs::_instance_p` ZGC structural fingerprint that
  misclassified Shenandoah, and made ZGC readiness require either callable
  runtime barrier symbols or complete nonzero live ZGlobals masks. Focused
  generator/audit tests passed. Fresh TEST native generation produced
  `build/npt-3bu/TEST-native.jar` from
  `build/neko-native-work/run-28846828379689` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1038104` bytes. Strict
  forbidden JNI/fallback grep over that run returned no matches. Default,
  G1, Serial, and Parallel TEST native runs passed with Calc `89ms`, `76ms`,
  `73ms`, and `76ms`. ZGC with `ZVerifyViews` now reports
  `barrier-tag detected: tag=5 ... z=-1 shen=-1`, then fails closed during
  layout with `ZGC barrier capability missing: symbols field=(nil) array=(nil)
  store=(nil) masks addr=0x0 load_good=0x0 load_bad=0x0 store_good=0x0
  store_bad=0x0` and `[neko-bootstrap] native layout initialization failed`.
  Shenandoah with verification now reports `barrier-tag detected: tag=4 ...
  z=-1 shen=-1`, then fails closed during layout with `Shenandoah barrier
  capability missing: lrb=(nil) pre=(nil) array=(nil)` and
  `[neko-bootstrap] native layout initialization failed`. No JNI, JVMTI,
  original-bytecode, skip, or collector-disabling fallback was introduced.

### [x] NPT-3bv: Tighten structured native performance median gates

- Scope: limit the implementation to native performance test instrumentation
  and assertions in `NativeObfuscationPerfTest`. Parse TEST Calc timing and
  obfusjack Platform, Virtual, Seq, Parallel, and VThreads timing rows from the
  repeated native-path runs already performed by the test. Record per-run
  timing maps and medians in the baseline JSON report, and assert that current
  fixture timing rows are parseable, complete when present, positive, and within
  conservative current-source sanity ceilings. This must not change generated
  runtime code, native code generation, JNI/JVMTI behavior, fallback behavior,
  obfuscation coverage, or collector behavior.
- Required evidence: current source records `calcMillis` for TEST and stores
  raw obfusjack timing lines as strings, but it does not compute or assert
  obfusjack matrix/thread medians. The observed current obfusjack output emits
  `Platform threads`, `Virtual threads`, `Seq`, `Parallel`, and `VThreads`
  rows, so the hot-path gate can become structured without runtime changes.
- Validation command or runtime target: focused
  `NativeObfuscationPerfTest` execution with `java.io.tmpdir` under
  `build/native-run-tmp`, plus diff inspection proving only test/report
  instrumentation changed.
- Completion criteria: fresh focused perf test passes, baseline JSON contains
  parsed per-run timing maps and median timing maps, and no runtime/codegen
  source files are changed.
- Completion evidence 2026-05-22: focused `NativeObfuscationPerfTest` passed
  using repository `./gradlew` with `java.io.tmpdir` under
  `build/native-run-tmp`. The generated
  `neko-test/build/test-native/native-performance-baseline.json` includes
  `timingsMillis` for every repeated TEST and obfusjack run plus
  `mediansMillis`: TEST Calc `69 ms`; obfusjack Platform `45 ms`, Virtual
  `36 ms`, Seq `17 ms`, Parallel `1 ms`, and VThreads `1 ms`. Diff inspection
  shows this checkpoint changed only perf test/report instrumentation and the
  matching todo/plan rows; no runtime/codegen source, JNI/JVMTI path, fallback
  behavior, obfuscation coverage, generated C, or collector behavior changed.

### [x] NPT-3bw: Resume returned object-result publication after strict-GC capability detection

- Scope: restore only the generic returned-object publication boundary from the
  deferred NPT-3bt/P41 implementation. Non-null object-return handles produced
  by translated native methods must pass through a generated
  `neko_prepare_return_oop(thread, handle, site)` helper before the signature
  dispatcher restores its handle window. The helper may resolve the handle,
  normalize collector-specific good-oop representation, and fail closed on an
  unresolved handle. This must not allocate, replace `String.concat`,
  special-case benchmark code, call JNI/JVMTI, add Java helper layers, change
  pending-exception behavior, or change primitive/void return dispatchers.
- Required evidence: current object-return dispatch in
  `SignatureDispatcherEmitter` resolves `__ret` through `neko_handle_oop`,
  restores the saved JNIHandleBlock window, then returns the raw oop. The
  pending-exception branch restores the window and returns `NULL`. NPT-3bt
  already proved the minimal generated-C ordering and runtime smoke shape, but
  was deferred because strict ZGC/Shenandoah bootstrap capability detection was
  incomplete. NPT-3bu/P42 now provides exact collector tag fallback and
  fail-closed missing-capability diagnostics for that prerequisite.
- Validation command or runtime target: focused generator tests for
  object-return dispatcher ordering and unchanged primitive/void dispatchers;
  fresh TEST native generation; `R-inspect` strict forbidden JNI/fallback grep;
  TEST native default, G1, Serial, and Parallel runs; strict ZGC/Shenandoah
  runs proving either execution on a capable VM or P42's exact fail-closed
  capability diagnostics.
- Completion criteria: generated object-return dispatchers call
  `neko_prepare_return_oop` before handle-window restore, pending-exception
  return behavior is unchanged, primitive/void return dispatchers are
  unchanged, fresh TEST native runtime passes under supported collectors, and
  no JNI/JVMTI/original-bytecode/skip fallback or raw `String.concat` path is
  introduced.
- Completion evidence 2026-05-22: implemented `neko_prepare_return_oop` in the
  generated fast-access support and changed only object-return signature
  dispatchers to call it before `neko_handle_restore`. The pending-exception
  branch still restores and returns `NULL`; primitive/void dispatchers are
  unchanged. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh TEST native generation
  produced `build/npt-3bw/TEST-native.jar` from
  `build/neko-native-work/run-29733047147870` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1037768` bytes. Generated C
  shows object-return dispatchers calling `neko_prepare_return_oop` before
  handle-window restore, and strict grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and
  `env->` returned no matches. Default, G1, Serial, and Parallel TEST native
  runs passed with Calc `70ms`, `73ms`, `67ms`, and `68ms`. ZGC with
  `ZVerifyViews` retained P42's fail-closed bootstrap diagnostic:
  `barrier-tag detected: tag=5 ... z=-1 shen=-1`,
  `ZGC barrier capability missing: symbols field=(nil) array=(nil) store=(nil)
  masks addr=0x0 load_good=0x0 load_bad=0x0 store_good=0x0 store_bad=0x0`, and
  `[neko-bootstrap] native layout initialization failed`. Shenandoah with
  verification retained P42's fail-closed diagnostic:
  `barrier-tag detected: tag=4 ... z=-1 shen=-1`,
  `Shenandoah barrier capability missing: lrb=(nil) pre=(nil) array=(nil)`, and
  `[neko-bootstrap] native layout initialization failed`. No raw
  `String.concat`, JNI/JVMTI, original-bytecode, skip, or collector-disabling
  fallback was introduced.

### [x] NPT-3cf: Select post-publication raw String concat prerequisite

- Scope: re-audit the raw `String.concat` blocker after NPT-3cd/NPT-3ce and
  NPT-3bw/P43 before selecting another implementation. This is an evidence-only
  selection subtask. It must not implement raw concat, special-case TEST
  `Calc.runStr`, retry rejected static-field or call-stub micro-shapes, call
  JNI/JVMTI, add Java helper layers, or introduce original-bytecode fallback.
- Required evidence: local OpenJDK 21 source for `String.concat`,
  `StringConcatHelper.simpleConcat`, `StringConcatHelper.newArray`,
  `StringConcatHelper.newString`, and package-private
  `String(byte[],byte)`/`String.getBytes`; current native concat helpers still
  crossing `neko_njx_V_L_L`/`neko_njx_V_L_L_raw_oop`; P43 object-return
  publication evidence; and current raw string literal allocation evidence
  showing which byte-array, `String.value`, `String.coder`, and local-root
  pieces exist only for bind-time string literals.
- Validation command or runtime target: source inspection, current generated
  helper inspection, strict forbidden-fallback review, and selection of one
  concrete next row with scope/evidence/validation/completion criteria before
  any runtime edit.
- Completion criteria: record a concrete implementation row that advances the
  missing raw returned-String construction invariants without replacing
  `String.concat` prematurely, or record a no-go if the evidence remains
  incomplete.
- Completion evidence 2026-05-22: OpenJDK 21 source proves
  `String.concat(String)` preserves rhs-null NPE by evaluating
  `str.isEmpty()`, returns `this` for empty rhs, and otherwise enters
  `StringConcatHelper.simpleConcat`; `simpleConcat` creates a new `String` copy
  for either empty side, mixes coder and char length, allocates a byte array via
  `newArray`, copies bytes with `String.getBytes`, and finalizes with
  `new String(byte[], byte)` through `newString`. `newArray` throws Java
  `OutOfMemoryError("Overflow: String length out of range")` on overflow and
  otherwise uses `Unsafe.allocateUninitializedArray(byte.class, len)`.
  Current source still routes the hot translated StringBuilder fast path through
  `neko_require_fast_string_concat_store_local`, which calls the original
  `String.concat` Method*/entry via `neko_njx_V_L_L_raw_oop` before publishing
  to a prepared local root. P43 proves the handle-to-raw object-return boundary,
  but it does not construct a fresh `String`/`byte[]` graph. Bind-time string
  literal code already proves raw byte-array header/length initialization,
  direct byte copying, `String.value`/`coder` writes, and bound-string local
  rooting, but that path is not a returned runtime concat path and does not
  implement `String.concat` empty-side copy/identity, overflow/OOME behavior,
  or generic lhs/rhs coder mixing. Selected NPT-3cg/P44 to implement the next
  generic prerequisite: a source-level raw newborn `String` graph constructor
  audit helper with full `String` instance sizing, byte-array sizing/copy,
  coder mixing, local-root publication, and focused runtime coverage, still
  not wired into the hot `String.concat` path until its exception and
  unsupported-allocation limits are proven.

### [x] NPT-3cg: Build raw newborn String graph prerequisite without changing concat dispatch

- Scope: add the smallest generic raw newborn `String` graph construction
  surface needed before replacing `String.concat`: allocate a fresh byte array
  and a fresh `java/lang/String` instance using current VM layout metadata,
  copy/inflate bytes from two existing `jstring` inputs according to their
  coders, initialize `String.value` and `String.coder`, and publish the result
  into an already prepared local root for focused validation. This row must not
  change the production `neko_require_fast_string_concat*` dispatch, must not
  special-case TEST or any literal value, must not call JNI/JVMTI or add Java
  helpers, must not fall back to original bytecode, and must hard-abort on
  missing raw heap, layout, allocation, or barrier capability not explicitly
  proven by the row.
- Required evidence: NPT-3cf source evidence; `NativeBindSupportEmitter`
  bind-time raw string literal allocation currently initializes byte-array
  header/length, copies Latin1/UTF16 payload, writes `String.value` through
  `neko_store_oop_raw`, writes `String.coder`, roots the new string with
  `neko_direct_oop_to_handle_origin`, and interns it; `NativeFastObjectAccessEmitter`
  already exposes `neko_fast_tlab_alloc`, `neko_init_oop_header`,
  `neko_fast_string_length`, `neko_store_local_oop_raw`, byte-array klass bits,
  and object allocation metadata; P43 proves object-return publication ordering
  but not newborn graph construction.
- Validation command or runtime target: focused generator/source tests for the
  helper emission and unchanged concat dispatch; a focused runtime fixture that
  exercises raw construction for Latin1+Latin1, Latin1+UTF16, empty lhs, empty
  rhs, and local-root publication without using the hot concat dispatch; fresh
  TEST native generation; strict generated-C grep for forbidden JNI/fallback
  markers; default/G1/Serial/Parallel TEST native smoke; and ZGC/Shenandoah
  strict runs proving either execution on a capable VM or the exact existing
  fail-closed capability diagnostics.
- Completion criteria: the helper can build and root fresh `String` graphs with
  correct bytes/coder under supported collectors, production concat helpers
  still call the original Method*/entry, no forbidden markers appear, and the
  row records the exact remaining exception/allocation semantics before a later
  row may replace the hot concat dispatch.
- Completion evidence 2026-05-22: implemented a default-off raw newborn
  `String` graph helper behind `NEKO_RAW_STRING_GRAPH_PREREQ`; default
  production concat dispatch still emits the original `neko_njx_V_L_L(...)`
  Method*/entry path. The helper allocates and roots a fresh byte array,
  reloads lhs/rhs value pointers after any allocation-capable transition,
  copies/inflates Latin1/UTF16 payloads, allocates a fresh `String` with a TLAB
  fast path and existing managed allocation cache only when needed, reloads the
  byte-array handle after String allocation, writes `String.value`/`coder`, and
  publishes the result into the prepared local root. Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  The focused runtime fixture printed `raw-string-graph-ok` under the opt-in
  helper after exercising Latin1+Latin1, Latin1+UTF16, empty lhs, and empty rhs
  concat shapes. Fresh direct smokes passed: opt-in TEST native `Calc: 71ms`,
  default TEST native `Calc: 65ms`, and obfusjack reached
  `=== All tests completed ===`. G1/Serial/Parallel TEST smokes passed with
  `Calc: 64ms`, `58ms`, and `60ms`. Strict generated-C grep over
  `run-37288355022467`, `run-37292658797327`, and `run-37295214600332` found
  no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`. ZGC and Shenandoah strict runs
  failed closed with the existing diagnostics: `ZGC barrier capability missing`
  / `Shenandoah barrier capability missing`, `gc barrier path not ready`, and
  `[neko-bootstrap] native layout initialization failed`.
  Remaining semantics before default hot concat replacement: Java-visible
  `String.concat` empty-side identity, Java exception delivery for allocation
  failures, and final default dispatch replacement are not claimed by this
  prerequisite; missing layout/allocation/cache/barrier capability still hard
  aborts.

### [x] NPT-3ch: Correct raw String allocation size from Klass layout helper

- Scope: fix the raw `java/lang/String` graph allocation size calculation so positive
  `Klass::_layout_helper` instance values are treated as already aligned byte
  sizes, not multiplied by pointer size, while respecting bit 0 as HotSpot's
  instance slow-path allocation flag. Apply the correction only to the
  P45 raw newborn String graph metadata; do not change the generic
  `neko_fast_alloc_object` path in this row because direct generic NEW sizing
  needs separate liveness proof. This row must not special-case TEST, obfusjack,
  or any class name beyond the existing metadata binding, must
  not change CFF or bytecode selection, must not introduce JNI/JVMTI or
  original-bytecode fallback, and must hard-abort on invalid non-positive
  layout helper values.
- Required evidence: OpenJDK HotSpot `klass.hpp` documents that for instances
  layout helper is a positive number equal to the instance size and already
  aligned/scaled to bytes, with the low-order bit set when the instance cannot
  use the fast allocation path; `layout_helper_size_in_bytes` masks that bit
  with `~_lh_instance_slow_path_bit`. Current source multiplies this value by
  `sizeof(void*)` in `NativeBindSupportEmitter.neko_ensure_string_alloc_bits`;
  a P46 work-in-progress attempt to apply the same direct byte-size change to
  `NativeFastObjectAccessEmitter.neko_fast_alloc_object` made obfusjack print
  `=== All tests completed ===` but not exit before `timeout 120s`, so generic
  NEW sizing is rejected from this row until it has a separate liveness proof.
  P45 opt-in
  runtime evidence showed raw `String` allocation requesting `192` bytes before
  the managed slow allocation fallback, and current default/opt-in TEST Calc
  remains `65ms`/`71ms`, indicating allocation pressure still blocks the
  concat hot path.
- Validation command or runtime target: focused generator/source tests proving
  the byte-size calculation; fresh native TEST/raw-string-graph generation;
  opt-in raw-string graph fixture; opt-in and default TEST native smokes;
  obfusjack native smoke; G1/Serial/Parallel TEST native smokes; strict
  generated-C grep for forbidden JNI wrappers; and ZGC/Shenandoah strict
  fail-closed diagnostics.
- Completion criteria: generated C uses `layout_helper & ~1` for positive
  `String` instance sizes and hard-aborts if `java/lang/String` requires the
  layout-helper slow path, raw `String` graph allocation no longer requests
  pointer-size inflated object bytes, focused opt-in concat-shape fixture still passes,
  default behavior remains correct, and performance evidence records whether
  this sizing correction is sufficient before any default raw concat dispatch
  replacement.
- Completion evidence 2026-05-22: `neko_ensure_string_alloc_bits` now rejects
  a `java/lang/String` slow-path allocation bit and stores
  `g_neko_string_instance_bytes = (size_t)(layout_helper & ~1)`. The generic
  `neko_fast_alloc_object` path is intentionally unchanged in this row; a
  work-in-progress direct-byte generic NEW variant made obfusjack print
  `=== All tests completed ===` but fail to exit before `timeout 120s`, so that
  broader change remains unshipped. Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  Fresh generated dirs were `run-38199290827824` (TEST),
  `run-38203811063440` (obfusjack), and `run-38206555063361` (raw string
  fixture). The opt-in raw-string fixture printed `raw-string-graph-ok`;
  opt-in TEST native passed with `Calc: 64ms`; default TEST native passed with
  `Calc: 64ms`; obfusjack native exited normally and reached
  `=== All tests completed ===` with Platform `45ms`, Virtual `35ms`, Seq
  `17ms`, Parallel `1ms`, and VThreads `1ms`. G1/Serial/Parallel TEST smokes
  passed with `Calc: 69ms`, `71ms`, and `63ms`. Strict generated-C grep over
  the three fresh dirs found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`.
  ZGC and Shenandoah strict runs failed closed with the existing diagnostics:
  `ZGC barrier capability missing` / `Shenandoah barrier capability missing`,
  `gc barrier path not ready`, and `[neko-bootstrap] native layout
  initialization failed`. This sizing correction is not sufficient for the
  final performance target; raw/default TEST still remain around `64ms`, so a
  later row must reduce the remaining concat dispatch/allocation cost.

### [x] NPT-3ci: Defer raw String byte-array rooting until allocation slow path

- Scope: optimize the P45 raw newborn `String` graph helper by keeping a freshly
  TLAB-allocated byte array as a raw oop when the immediately following
  `java/lang/String` allocation also succeeds in the TLAB. Publish the byte
  array to a local handle only if a later allocation-capable slow path is about
  to run after the byte array exists. This must preserve the current rooted
  path for slow byte-array allocation, String TLAB refill, managed String
  allocation, GC/barrier fail-closed behavior, and local-root publication of
  the returned String. It must not special-case TEST, obfusjack, class names,
  literals, or any benchmark shape, and must not introduce JNI/JVMTI or
  fallback behavior.
- Required evidence: fresh P46 handle-audit build generated TEST at
  `build/neko-native-work/run-38490167041237` and obfusjack at
  `build/neko-native-work/run-38493171298515`. Default TEST debug audit still
  showed `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`
  and `njx-return-shapes: ... V:L:L=510002 ...`, proving the default path still
  crosses NJX for concat. The same fresh TEST artifact with
  `NEKO_RAW_STRING_GRAPH_PREREQ=1` reduced dispatch to `dispatched=61` and
  `njx-return-shapes: ... V:L:L=2 ...`, proving the raw graph route removes
  the concat NJX crossings, but reported `handle_direct_total=509984` with
  `primitive_array_alloc=509930`, proving the raw graph now publishes almost
  every fast byte-array allocation to a handle even when the immediately
  following String allocation can stay inside the TLAB. Current source in
  `NativeFastObjectAccessEmitter.neko_build_raw_string_graph_store_local`
  publishes `array_oop` with `neko_direct_oop_to_handle_origin(...)` before
  attempting the String allocation and then reloads it after allocation.
- Validation command or runtime target: focused generator/source tests for the
  raw graph helper; fresh handle-audit native generation; opt-in TEST audit
  proving primitive-array handle publications are eliminated or materially
  reduced on the all-TLAB raw graph path while dispatch remains low; opt-in and
  default TEST native smokes; obfusjack native smoke; G1/Serial/Parallel TEST
  native smokes; strict generated-C grep for forbidden JNI wrappers; and
  ZGC/Shenandoah strict fail-closed diagnostics.
- Completion criteria: the all-TLAB raw graph path never calls
  `neko_direct_oop_to_handle_origin` for the intermediate byte array, every
  allocation-capable slow path after byte-array creation roots the byte array
  before safepointing, raw concat shape fixture remains correct, no forbidden
  JNI/fallback markers appear, and timing/counters record whether this is
  sufficient before making the raw graph default.
- Completion evidence 2026-05-22: `neko_build_raw_string_graph_store_local`
  now leaves fast TLAB byte arrays unrooted only across the immediate
  non-safepoint String TLAB allocation, while slow byte-array allocation uses
  its returned local handle and the String slow-allocation path publishes the
  byte array before `neko_refill_tlab_with_slow_byte_array`,
  `neko_resolve_class_mirror_with_env`, or `Unsafe.allocateInstance` can run.
  Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  Fresh handle-audit generation passed with TEST at
  `build/neko-native-work/run-38722568601819` and obfusjack at
  `build/neko-native-work/run-38725591470562`. Opt-in TEST audit proved the
  intended hot-path change: before this row P46 opt-in reported
  `primitive_array_alloc=509930`, `handle_direct_total=509984`,
  `dispatched=61`, and `V:L:L=2`; after this row it reported
  `primitive_array_alloc=32`, `handle_direct_total=87`, `dispatched=62`, and
  `V:L:L=3`, while retaining
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Default
  TEST audit remained on the original dispatch path with `V:L:L=510002`.
  Opt-in TEST x5 was `42/42/41/53/41 ms` (median `42ms`) versus default TEST
  x5 `63/64/62/63/63 ms` (median `63ms`). Default obfusjack reached
  `=== All tests completed ===` with Platform `47ms`, Virtual `47ms`, Seq
  `18ms`, Parallel `1ms`, and VThreads `1ms`. Opt-in G1/Serial/Parallel TEST
  smokes passed with `Calc: 42ms`, `44ms`, and `46ms`. Strict generated-C grep
  over both fresh audit dirs found no `NEKO_JNI_FN_PTR`, `(*env)->`, or
  `env->`. ZGC with `ZVerifyViews` and Shenandoah with verification both
  failed closed at bootstrap with `[neko-bootstrap] native layout
  initialization failed`. This row improves the raw graph opt-in path but is
  not sufficient for the final target; the next row must reduce the remaining
  raw allocation/copy cost and eventually make a fully proven path default.

### [x] NPT-3cj: Skip raw String input reloads when byte-array allocation cannot safepoint

- Scope: remove only the redundant lhs/rhs handle and `String.value` reload
  block in `neko_build_raw_string_graph_store_local` when the intermediate
  byte array was allocated by the TLAB fast path and no allocation-capable slow
  path has run. Keep the existing reload and validation block when
  `array_handle != NULL`, which covers slow byte-array allocation and any path
  where the byte array was rooted because a safepoint-capable operation has
  already happened. This must not skip reloads after slow allocation, TLAB
  refill, managed String allocation, NJX, or any future safepoint-capable path,
  and must not change concat selection, CFF, JNI/JVMTI usage, fallback policy,
  or exception/abort behavior.
- Required evidence: P47 opt-in TEST audit showed the raw graph path now has
  low dispatch and handle traffic (`dispatched=62`, `handle_direct_total=87`,
  `primitive_array_alloc=32`) while still executing
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0` and
  median `42ms`. Current source still reloads `left_oop`, `right_oop`,
  `left_value`, and `right_value` unconditionally after byte-array allocation,
  even though the all-TLAB path between the initial reads and payload copy only
  performs `neko_fast_tlab_alloc`, `neko_init_oop_header`, and the byte-array
  length store. That path cannot safepoint, so the reloads are required only
  when `array_handle != NULL` proves a slow/rooted byte-array path already
  occurred.
- Validation command or runtime target: focused generator/source tests; fresh
  raw-string graph runtime fixture; fresh handle-audit TEST generation; opt-in
  TEST audit proving dispatch and handle counters remain low; opt-in/default
  TEST timing comparison; obfusjack smoke; strict generated-C grep for
  forbidden JNI wrappers; and G1/Serial/Parallel plus ZGC/Shenandoah
  fail-closed smokes.
- Completion criteria: generated raw graph helper guards the post-byte-array
  lhs/rhs reload block with `if (array_handle != NULL)`, all slow/rooted paths
  retain the reload and null checks, all-TLAB raw concat keeps correct
  Latin1/UTF16/empty-shape output, no forbidden markers appear, and timing does
  not regress versus P47 opt-in/default medians.
- Completion evidence 2026-05-22: `neko_build_raw_string_graph_store_local`
  now executes the post-byte-array lhs/rhs and value reload block only when
  `array_handle != NULL`; slow/rooted paths still keep the existing null checks
  and all-TLAB raw concat proceeds directly to the payload copies. Focused
  validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  Fresh handle-audit generation passed with TEST at
  `build/neko-native-work/run-39101082444663` and obfusjack at
  `build/neko-native-work/run-39104735242967`. Generated-C inspection found
  the guarded reload block and strict grep over both fresh dirs found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`. Opt-in TEST audit reported
  `dispatched=61`, `V:L:L=2`, `handle_direct_total=74`, and
  `primitive_array_alloc=20` while retaining
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Opt-in
  TEST x5 was `32/30/31/31/38 ms` (median `31ms`) versus P47 opt-in median
  `42ms`; default TEST remained on the original dispatch path with x5
  `64/71/67/65/68 ms` (median `67ms`). Default obfusjack reached
  `=== All tests completed ===` with Platform `43ms`, Virtual `40ms`, Seq
  `17ms`, Parallel `1ms`, and VThreads `1ms`. Opt-in G1/Serial/Parallel TEST
  smokes passed with `Calc: 46ms`, `32ms`, and `33ms`. ZGC with
  `ZVerifyViews` and Shenandoah with verification both failed closed at
  bootstrap with `[neko-bootstrap] native layout initialization failed`. This
  row materially improves the raw graph opt-in path but final acceptance is
  still open because default TEST remains above target and raw graph still
  requires opt-in.

### [x] NPT-3ck: Scalarize proven same-local StringBuilder concat recurrence

- Scope: add a generic bytecode proof and native emission path for a
  non-escaping same-local recurrence of the form `s = new
  StringBuilder().append(s).append(x).toString()` where the updated local is
  used only for scalar observations proven equivalent before final
  materialization. The first accepted surface is limited to same-method,
  same-local recurrence with `String.length()` loop observation and a final
  normal method exit or proven local dead point where materializing the final
  `String` is not Java-observable. It must reject any field/array/static store,
  return, invoke argument, monitor, reflection, MethodHandle, exception object,
  stack escape, aliasing store, or unproven observation between recurrence
  updates. Proof failure must leave the existing concat path unchanged at
  translation time; no runtime fallback after partial scalarization is allowed.
  This row must not special-case TEST, class names, literals, or a benchmark
  method, must not weaken CFF/key propagation, and must not introduce JNI,
  JVMTI, Java helpers, original-bytecode fallback, or skip behavior.
- Required evidence: P48 shows the remaining opt-in hot path is no longer NJX
  or handle publication: TEST opt-in x5 was `32/30/31/31/38 ms` (median
  `31ms`) with `dispatched=61`, `V:L:L=2`, `handle_direct_total=74`,
  `primitive_array_alloc=20`, and
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Generated
  TEST `runStr` is the generic recurrence shape: local `0` is length-checked,
  concatenated with a string literal through the recognized StringBuilder
  concat pattern, and stored back to the same local. The remaining cost is
  therefore per-iteration `String`/`byte[]` allocation plus payload copy for
  intermediate values whose identity is not observed in the proven recurrence.
- Validation command or runtime target: focused source tests for recurrence
  proof acceptance and rejection; generated-C inspection proving accepted
  recurrence sites do not call `neko_build_raw_string_graph_store_local` per
  iteration and rejection fixtures retain the current concat path; fresh TEST
  default x5 with no opt-in dependency targeting `<=20ms`; obfusjack x5
  non-regression for Seq/Platform/Virtual/Parallel/VThreads; strict
  generated-C grep for forbidden JNI/fallback markers; G1/Serial/Parallel TEST
  and obfusjack smokes; and ZGC/Shenandoah strict fail-closed diagnostics unless
  barrier support is completed in a prior row.
- Completion criteria: scalarization applies only when bytecode proof shows all
  intermediate String identities are unobservable and scalar observations are
  equivalent, proof failure preserves the existing safe concat emission,
  default TEST median is materially improved without `NEKO_RAW_STRING_GRAPH_PREREQ`,
  obfusjack does not regress, and all required runtime/static checks pass.
- Gate blocker 2026-05-22: source tests passed and fresh TEST `runStr`
  generated C in `build/neko-native-work/run-39893685712791/neko_native_impl_22.c`
  contains `scalarized same-local StringBuilder concat recurrence`, removing the
  per-iteration concat call. Default TEST x5 reported Calc `2/3/2/2/3 ms`
  (median `2ms`). P49 cannot complete yet because obfusjack x5 hard-aborted
  once in an unrelated primitive array allocation path:
  `[neko-direct] primitive array allocation direct path unavailable len=192
  kind=7 ... raw=1 zgc=0 coh=0 tlab=1`. NPT-3cl is recorded as the required
  allocator prerequisite before P49 can be checkpointed.
- Completion evidence 2026-05-22: after NPT-3cl removed the primitive-array
  allocation abort, the recurrence scalarizer remains validated in the focused
  Gradle slice and fresh generated TEST C still removes the per-iteration
  StringBuilder concat path. Fresh TEST x5 after the current translator changes
  reported Calc `3/3/2/3/3 ms` with Pool PASS, preserving the default
  `<=20ms` target without `NEKO_RAW_STRING_GRAPH_PREREQ`. Fresh obfusjack x5
  completed without aborts and reported Platform `49/43/49/49/46 ms`, Virtual
  `39/47/36/40/41 ms`, Seq `13/11/11/11/11 ms`, Parallel `1 ms`, and VThreads
  `1 ms`; the remaining Platform/Virtual misses are tracked by the next
  thread-work hot-path row, while the string recurrence path did not regress
  obfusjack correctness. Strict grep over fresh `run-413*` generated C/headers
  found no forbidden JNI wrapper markers. G1/Serial/Parallel TEST smokes
  reported Pool PASS and Calc `3/3/3 ms`.

### [x] NPT-3cl: Use JVM_NewArray slow primitive-array allocation when TLAB refill cannot satisfy direct allocation

- Scope: extend the existing non-JNI JVM symbol slow allocation surface used by
  byte-array TLAB refill to cover all primitive array kinds when
  `neko_fast_new_primitive_array` has valid metadata but TLAB allocation still
  returns NULL after refill. The helper must resolve the primitive mirror by
  kind through existing `JVM_FindPrimitiveClass`, allocate with existing
  `JVM_NewArray`, return the resulting local handle, and hard-abort on missing
  symbols, invalid kind, pending exception, unresolved handle, or null result.
  It must not use the JNI function table, JNI array functions, Java helpers,
  skip behavior, original-bytecode fallback, or benchmark-specific logic.
- Required evidence: P49 validation exposed an obfusjack runtime abort in
  matrix multiply after four successful runs: `len=192 kind=7` (`double[]`),
  `g_hotspot.initialized=1`, primitive array klass/base/scale present, raw heap
  enabled, compact headers disabled, and `g_neko_tlab_alloc_ready=1`. Source
  evidence in `NativeFastObjectAccessEmitter.neko_fast_new_primitive_array`
  shows it refills via a slow byte array and aborts if the second TLAB attempt
  still fails; `NativeBindSupportEmitter.neko_alloc_jbyte_array_oop_slow` proves
  the runtime already has a fail-closed `JVM_FindPrimitiveClass`/`JVM_NewArray`
  slow path for byte arrays.
- Validation command or runtime target: focused generator tests covering slow
  primitive array helper emission and hard-abort guards, fresh native artifact
  regeneration, obfusjack x5 without primitive-array allocation abort, TEST x5
  preserving the P49 Calc win, strict generated-C forbidden JNI/fallback grep,
  and G1/Serial/Parallel TEST smokes. ZGC/Shenandoah remain fail-closed unless
  barrier support is completed in a prior row.
- Completion criteria: direct primitive array allocation remains the hot path;
  only TLAB exhaustion after refill uses the JVM symbol slow allocation path;
  missing symbols/capabilities hard-abort; obfusjack no longer aborts in the
  observed primitive array path; TEST and generated-C inspections do not
  regress.
- Completion evidence 2026-05-22: `neko_fast_new_primitive_array` still uses
  direct TLAB allocation first, then refills through the existing slow byte-array
  path, and only calls `neko_alloc_primitive_array_slow(env, len, kind)` if the
  second TLAB allocation returns NULL. The slow helper maps all primitive kinds
  to `JVM_FindPrimitiveClass` names and allocates with `JVM_NewArray`, with
  hard aborts on invalid kind, missing symbols, pending exceptions, null arrays,
  and unresolved handles. Focused Gradle validation passed:
  `:neko-test:test --tests CCodeGeneratorTest --tests
  NativeGeneratedCHotPathAuditTest --tests OpcodeTranslatorUnitTest --tests
  NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated C in `build/neko-native-work/run-40149471798380` and
  `run-40152881799054` contains the slow helper call and no forbidden JNI
  markers by strict grep. TEST x5 after P50 preserved the P49 Calc win
  (`2/4/3/3/3 ms`, median `3ms`; one Pool fixture printed FAIL once and four
  subsequent runs printed PASS). G1/Serial/Parallel TEST smokes reported Calc
  `2/3/3 ms`. Obfusjack x5 completed without the primitive-array allocation
  abort; timings were Platform `50/46/48/41/50 ms`, Virtual `40/40/42/45/43 ms`,
  Seq `18/17/17/17/17 ms`, Parallel `1 ms`, VThreads `1 ms`.

### [x] NPT-3cn: Scalarize counted double[][] multiply-accumulate inner loops

- Scope: add a generic translator loop fusion for a counted `int` loop whose
  body updates one `double` accumulator local with the product of two
  `double[][]` element reads and then increments the counted local by one. The
  proof must require a single backedge to the loop header, no active try
  handler over the covered range, no branch target inside the covered range
  other than the header/exit labels, an `IF_ICMPGE`/`IF_ICMPGT` style exit guard
  that dominates the body, a same-local induction increment, and no writes to
  the array reference, bound, index, or accumulator locals except the proven
  update. The emitted C may replace the bytecode body with one native `for`
  loop that uses the existing raw fused `AALOAD+DALOAD` helper, preserves the
  helper failure reason dispatch, and jumps to the original exit label. It must
  not hoist nullable array dereferences before the original loop guard, must not
  change exception order on the first failing access, and must not specialize
  owner, method, class, descriptor, source line, or benchmark artifact names.
- Required evidence: after P51, fresh generated obfusjack C in
  `build/neko-native-work/run-40744661698418/neko_native_impl_52.c` proves the
  `mmulSeq` inner loop is now reduced to a scalar multiply-add statement but
  still executes the translated label loop, loop-local stores, increment,
  pending-exception check, and backedge every iteration. Fresh obfusjack x5
  after P51 still misses the gate with Seq `17/20/17/17/17 ms`, while TEST Calc
  remains under target at `4/3/2/3/3 ms`. The exact failing invariant is
  loop-dispatch overhead remaining after the straight-line multiply-add fusion;
  the changed path is the generic counted-loop translator path for the same
  bytecode shape.
- Validation command or runtime target: focused translator tests for positive
  counted-loop fusion and rejection when a protected range, interior branch
  target, non-unit induction update, mismatched accumulator store, or escaping
  write breaks the proof; fresh artifact regeneration; generated-C inspection
  proving the inner loop emits one scalar native `for` and no per-iteration
  `PUSH_D`/`POP_D` multiply-add sequence or translated backedge in the covered
  range; TEST x5 preserving Calc; obfusjack x5 measuring Seq/Platform/Virtual/
  Parallel/VThreads; strict forbidden JNI/fallback grep; G1/Serial/Parallel
  TEST smokes.
- Completion criteria: the optimization is enabled only by the generic
  bytecode/control-flow proof, rejection tests keep the existing translation,
  generated C preserves fast-array failure dispatch and first failing access
  semantics, obfusjack Seq reaches or materially moves toward the `<=14ms`
  target without runtime aborts, and TEST does not regress.
- Completion evidence 2026-05-22: implemented the generic counted-loop
  scalarizer in `NativeTranslator` with proof checks for the loop header, single
  `IF_ICMPGE` exit, unit `IINC` induction update, matching accumulator store,
  no protected range, and no interior branch target. Focused Gradle validation
  passed for `OpcodeTranslatorUnitTest`, `CCodeGeneratorTest`,
  `NativeGeneratedCHotPathAuditTest`, and
  `NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated obfusjack C in
  `build/neko-native-work/run-41392045707312/neko_native_impl_52.c` emits
  `scalarized counted double[][] multiply-accumulate loop` as one native `for`
  using `neko_fast_raw_aaload_daload`, preserves fast-array reason dispatch, and
  removes the translated inner-loop `locals[9].i += 1` backedge from the covered
  range. Strict grep over fresh `run-413*` generated C/headers found no
  forbidden JNI wrapper markers. Fresh TEST x5 reported Calc `3/3/2/3/3 ms`
  with Pool PASS. Fresh obfusjack x5 reported Platform `49/43/49/49/46 ms`,
  Virtual `39/47/36/40/41 ms`, Seq `13/11/11/11/11 ms`, Parallel `1 ms`, and
  VThreads `1 ms`, so the Seq median is `11 ms` and meets the `<=14ms` gate.
  G1/Serial/Parallel TEST smokes reported Pool PASS and Calc `3/3/3 ms`.

### [x] NPT-3co: Remove redundant getClass probes after generated lambda allocation

- Scope: update native LambdaMetafactory lowering so the replacement bytecode
  emits `new`, `dup`, captured arguments, and `<init>` for the generated lambda
  class, but no longer appends the unused `dup; Object.getClass(); pop` probe.
  This applies only to generated lambda classes created by the native stage's
  generic LambdaMetafactory lowering. It must not change lambda capture order,
  constructor invocation, SAM implementation, generated class metadata,
  manifest patching, hidden-key propagation, or any non-lambda invokedynamic
  lowering.
- Required evidence: fresh generated obfusjack C in
  `build/neko-native-work/run-42691025802299/neko_native_impl_63.c` and
  `neko_native_impl_66.c` shows each generated `Callable` factory allocates a
  `Main$NekoLambda$*`, invokes its constructor, then duplicates the object and
  dispatches a no-arg virtual call through `neko_icache_dispatch` before
  popping the result. Source evidence in
  `NativeCompilationStage.lowerLambdaMetafactory` proves this is the explicit
  unused `Object.getClass()` probe inserted after construction. The freshly
  allocated object is non-null, allocation already resolves the generated
  class, and the returned `Class` object is discarded, so the probe has no
  observable lambda result semantics while adding one virtual dispatch to each
  generated lambda factory call. The obfusjack thread benchmark builds 50,000
  task lambdas in the timed Platform and Virtual paths, and prior arithmetic,
  AtomicLong, shadow-frame, and direct-call-check trials did not meet the
  remaining Platform/Virtual gates.
- Validation command or runtime target: focused native-stage/source tests
  proving lowered LambdaMetafactory bytecode no longer contains an unused
  `Object.getClass()` call after generated lambda construction while generated
  lambda invocation still works; fresh artifact regeneration; generated-C
  inspection proving `lambda$microbenchThreads$6` and
  `lambda$microbenchThreads$9` no longer contain the post-constructor no-arg
  `neko_icache_dispatch`, while constructor invocation and returned lambda
  object remain; TEST x5; obfusjack x5 measuring Platform/Virtual/Seq/Parallel/
  VThreads; strict forbidden JNI/fallback grep; G1/Serial/Parallel TEST smokes.
- Completion criteria: the removed probe is only the native-stage generated
  lambda `getClass` probe, lambda construction/invocation remains correct,
  manifest discovery still patches generated lambda classes, TEST and Seq stay
  within target, Platform and Virtual medians move toward or meet Platform
  `<=44ms` and Virtual `<=35ms`, and no runtime aborts or forbidden JNI/
  fallback markers appear.
- Completion evidence: focused Gradle validation passed with fresh native
  regeneration:
  `:neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated obfusjack C in
  `build/neko-native-work/run-43586804685160/neko_native_impl_63.c` and
  `neko_native_impl_66.c` allocates each generated lambda object, invokes its
  constructor, and returns the object without the prior post-constructor
  no-arg `neko_icache_dispatch`/`Object.getClass` probe. Fresh TEST x5
  reported Calc `4/2/2/3/3 ms`. Fresh obfusjack x5 reported Platform
  `40/47/40/44/43 ms`, Virtual `35/36/53/39/42 ms`, Seq
  `14/11/11/17/11 ms`, Parallel `1 ms`, and VThreads `1 ms`, so Platform
  median is `43 ms` and meets `<=44 ms`; Seq median is `11 ms` and meets
  `<=14 ms`; Virtual median is `39 ms` and remains the next open gate.
  G1/Serial/Parallel TEST smokes reported Pool PASS and Calc `3/3/3 ms`.

### [x] NPT-3cp: Omit LambdaMetafactory Object return adapter casts

- Scope: update native-stage LambdaMetafactory adapter generation so object or
  array values adapted to `java/lang/Object` do not receive a redundant
  `CHECKCAST java/lang/Object`. Keep all other casts intact, including casts to
  specific object types, interface types, arrays, unboxing, boxing, void
  adaptation, and all non-lambda bytecode. This is a generic assignability
  cleanup in the generated lambda adapter, not a runtime fallback and not a
  benchmark-specific transform.
- Required evidence: fresh generated obfusjack C in
  `build/neko-native-work/run-43586804685160/neko_native_impl_89.c` and
  `neko_native_impl_91.c` shows generated `Callable.call()` adapters for
  `Main$NekoLambda$17` and `Main$NekoLambda$19` call the translated task body,
  then execute a checkcast block that binds `java/lang/Object` and calls
  `neko_fast_is_instance_of` before returning. The corresponding generated
  class bytecode shows the LambdaMetafactory adapter is returning `Object` from
  a more specific reference return, and `NativeCompilationStage.adaptStackValue`
  currently emits `CHECKCAST` for every object-to-object mismatch. A cast to
  `java/lang/Object` cannot fail for any non-null reference and preserves null,
  so it adds work without changing lambda return semantics. The obfusjack
  Platform/Virtual task paths execute these generated `Callable.call()` methods
  50,000 times per timed path.
- Validation command or runtime target: focused Gradle validation with fresh
  native regeneration; generated-C inspection proving `NekoLambda$17.call` and
  `NekoLambda$19.call` no longer contain the post-task `java/lang/Object`
  checkcast block while casts to narrower types remain emitted elsewhere; TEST
  x5; obfusjack x5 measuring Platform/Virtual/Seq/Parallel/VThreads; strict
  forbidden JNI/fallback grep; G1/Serial/Parallel TEST smokes.
- Completion criteria: LambdaMetafactory adapters still preserve JVM
  assignability for all non-Object targets, lambda construction and invocation
  remain correct, TEST and Seq stay within target, Virtual median moves toward
  or meets `<=35 ms` without regressing Platform beyond `<=44 ms`, and no
  runtime aborts or forbidden JNI/fallback markers appear.
- Completion evidence: focused Gradle validation passed with fresh native
  regeneration:
  `:neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated obfusjack C in
  `build/neko-native-work/run-43946550536660/neko_native_impl_89.c` and
  `neko_native_impl_91.c` returns directly after the translated task body and
  no longer emits the prior post-task `java/lang/Object`
  `neko_fast_is_instance_of` checkcast block. Targeted forbidden JNI/fallback
  grep over those changed generated methods returned no matches. Fresh TEST x5
  reported Calc `3/3/2/3/2 ms`. Fresh obfusjack x5 reported Platform
  `24/35/30/38/31 ms`, Virtual `28/35/44/36/29 ms`, Seq
  `11/11/11/11/12 ms`, Parallel `1 ms`, and VThreads `1 ms`; medians are
  Platform `31 ms`, Virtual `35 ms`, and Seq `11 ms`, meeting the recorded
  thresholds. G1/Serial/Parallel TEST smokes reported Pool PASS and Calc
  `3/3/3 ms`.

### [-] NPT-4: Complete T4.8d strict Shenandoah native handle-slot barrier

- Scope: correct only the generic Shenandoah JNI/native handle-slot load
  barrier path exposed while validating T4.8c. Heap field and array barriers,
  MethodType lowering, argument order, and benchmark transforms are out of
  scope for this row.
- Required evidence: fresh strict Shenandoah obfusjack failure after
  `NativeObfuscationPerfTest` regeneration, generated C proving the failing
  `MethodType.methodType(Class, Class)` call receives `Void.TYPE` then
  `String.class`, generated support code proving native handle-slot loads still
  prefer the ordinary heap-field LRB over the dedicated native handle C1 stub,
  and HotSpot evidence that native/off-heap handle loads use
  `shenandoah_load_reference_barrier_strong_native_slow`.
- Validation command or runtime target: focused `CCodeGeneratorTest`, fresh
  `NativeObfuscationPerfTest --no-parallel` regeneration, strict Shenandoah
  TEST-native and obfusjack-native direct runs, generated-C forbidden-marker
  inspection, then the broader GC/perf gate if the focused Shenandoah proof
  passes.
- Completion criteria: Shenandoah native handle slots use the native C1 barrier
  or hard-abort if it is unavailable; heap field/array load semantics remain
  unchanged; TEST-native and obfusjack-native complete under
  `-XX:+UseShenandoahGC -XX:+ShenandoahVerify`; no JNI/JVMTI/fallback/original
  bytecode path is added.
- Evidence recorded before editing 2026-05-22: fresh generated artifact
  `build/neko-native-work/run-65283390794103` has the failing
  `neko_native_impl_39.c` call to `neko_njx_S_L_LL` for
  `MethodType.methodType(Ljava/lang/Class;Ljava/lang/Class;)`; immediately
  before the call it reads `java/lang/Void.TYPE` and `java/lang/String.class`.
  Strict Shenandoah TEST passes with empty stderr, but strict Shenandoah
  obfusjack exits with `IllegalArgumentException: not primitive: void`.
  Generated `neko_barrier_load_oop_native_handle_with_thread` chooses
  `g_neko_barrier_load_oop_field_preloaded` before
  `g_neko_barrier_load_oop_native_c1_stub`, which violates the already-recorded
  HotSpot native/off-heap handle barrier split for
  `shenandoah_load_reference_barrier_strong_native_slow`.
- Follow-up evidence before editing 2026-05-22: after requiring the native
  handle C1 stub, focused codegen passed and generated
  `build/neko-native-work/run-65751179443694/neko_native_support_helpers_1.c`
  no longer falls back to the ordinary field barrier for native handle slots.
  Strict Shenandoah TEST still passes, but strict Shenandoah obfusjack still
  fails at the same `MethodType` call. Generated `neko_njx_S_L_LL` installs a
  fresh Java handle block, then writes raw object oops to `call_params` without
  re-rooting those objects in the newly active block; the source handles remain
  in the previous handle block. The next generic implementation is to re-root
  every NJX object argument in the active Java handle block before writing the
  raw oop argument.
- Follow-up evidence before diagnostic 2026-05-22: after re-rooting every NJX
  object argument in the active Java handle block, focused `CCodeGeneratorTest`
  passed and full `NativeObfuscationPerfTest --no-parallel` passed. Strict
  Shenandoah TEST-native still passes with empty stderr, but strict Shenandoah
  obfusjack-native generated from `build/neko-native-work/run-66188078455008`
  still exits at the same Java-level `MethodType.methodType(Class, Class)`
  path with `IllegalArgumentException: not primitive: void`; stdout reaches
  `Files.mismatch index=2` before the failure. Generated
  `neko_njx_S_L_LL` now contains `neko_njx_root_arg_oop`, so handle-block
  lifetime alone does not explain the surviving invariant violation. The next
  step is temporary env-gated generic NJX object-argument diagnostics that
  print the object argument class mirror state for direct Java upcalls; no
  runtime behavior change is permitted until that evidence identifies the
  exact mismatched argument state.
- Diagnostic evidence before field-barrier implementation 2026-05-22:
  `NEKO_NJX_ARG_DEBUG=1` strict Shenandoah obfusjack on fresh artifact
  `build/neko-native-work/run-66655040145198` shows the failing
  `S:L:LL` `MethodType.methodType(Class, Class)` call receives slot 0 as a
  `java/lang/Class` mirror whose mirror Klass is `NULL` and whose Java string
  form is `void`, and slot 1 as `java/lang/String`. The Java failure is from
  `Wrapper.forPrimitiveType` not matching that `void` mirror by identity
  against `Void.TYPE`, proving a stale/unhealed primitive mirror oop rather
  than argument-order or handle-block lifetime. Source/generated audit shows
  the mirror comes from `neko_fast_get_static_object_field_ref` through
  `neko_barrier_load_oop_field`, and the Shenandoah C1 field-load fallback
  calls `neko_call_c1_oop2_stub` without the explicit `thread` even though the
  already-implemented C1 bridge has `neko_call_c1_oop2_stub_thread`; the TLS
  `g_neko_current_java_thread_for_barrier` is only declared/initialized and is
  not published on this path. Generic implementation scope: object/static field
  load helpers that already receive `thread` must call a thread-aware field
  load barrier so Shenandoah C1 barriers receive a stable JavaThread in `r15`;
  non-Shenandoah behavior and heap field semantics must remain unchanged.
- Follow-up evidence before static-base ordering implementation 2026-05-22:
  fresh `build/neko-native-work/run-67021067423154` contains the
  thread-aware field barrier call, but strict Shenandoah obfusjack still
  produces the same stale `void` mirror at `g_static_field_ref_10`
  (`java/lang/Void.TYPE`). Generated static field metadata contains both a
  bound class slot and a VM-resolved `static_base_slot`; however
  `neko_fast_get_static_object_field` computes the field address from the
  bound class mirror first and only falls back to `staticBase` if the class
  mirror cannot be resolved. For direct static field access the VM-resolved
  static base is the concrete field container associated with the recorded
  offset, so preferring the class mirror can read from a stale mirror copy
  under a moving collector. Generic implementation scope: object GETSTATIC
  must compute its field address from `staticBase` first and retain the class
  mirror only as a fail-closed fallback; no method, owner, descriptor, or
  benchmark-specific logic is allowed.
- Corrected root-cause evidence before handle-tag implementation 2026-05-22:
  sidecar source audit plus the surviving stale mirror after the
  static-field/base experiments identified the actual remaining NJX marshalling
  invariant. Re-rooted NJX object arguments are local JNI handles in the active
  handle block, but `neko_shenandoah_handle_oop_thread` treats tag `0` handles
  as a raw slot dereference and returns `slot_oop` without
  `neko_barrier_load_oop_native_handle_with_thread`; only tag `2` handles use
  the native/off-heap Shenandoah handle barrier. The failing `Void.TYPE` handle
  in the diagnostic is a tag-0 local handle, so the raw oop handed to
  `call_stub` can be a stale from-space mirror even after argument re-rooting.
  Generic implementation scope: every non-direct Shenandoah JNI handle slot,
  including tag-0 local handles and tag-2 global handles, must resolve through
  the native handle-slot barrier; unsupported tags still hard-abort.
- Rejected evidence 2026-05-22: routing tag-0 local handles through the native
  handle-slot barrier moves past the `Void.TYPE` failure, but corrupts earlier
  strict Shenandoah Java output and then fails in `BigDecimal.<init>(String)`.
  Tag-0 local handles are therefore not the same native/global handle-slot
  class as tag-2 handles. The remaining invariant is the synthetic
  `JavaCallWrapper`: object arguments are re-rooted in `__njx_java_handles`,
  while the wrapper's handle-block field is populated with `__njx_old_handles`.
  Generic implementation scope: keep tag-0 local handle raw-slot behavior and
  publish the active NJX handle block in the synthetic wrapper so safepoint
  scanning can see the re-rooted object arguments.
- Rejected evidence 2026-05-22: publishing `__njx_java_handles` in the
  synthetic wrapper makes the default `NativeObfuscationPerfTest` obfusjack
  native run time out after `PT2M`, so the wrapper field is not a safe active
  handle publication point in its current layout. This experiment is reverted;
  the remaining fix still requires a generic GC-root proof for NJX object
  arguments without changing tag-0 local handle semantics or hanging the
  default collector path.
- Corrected evidence before primitive-mirror barrier implementation
  2026-05-22: after removing stale generated jars, rerunning the default full
  native performance gate passed, and strict Shenandoah obfusjack still failed
  at `MethodType.methodType(Class, Class)` with `IllegalArgumentException: not
  primitive: void`. `NEKO_PATCH_DEBUG=1` on that failing artifact proves the
  active VM exposes only Shenandoah C1 barriers (`sh_lrb=(nil)`,
  `sh_lrb_narrow=(nil)`, `sh_c1_load!=NULL`, `sh_c1_native!=NULL`). Source
  audit shows `neko_primitive_mirror_for_char` still reads wrapper
  `Klass::_java_mirror`'s OopHandle and the wrapper `TYPE` static field by raw
  slot/field loads, then calls `neko_barrier_load_oop_field` without the
  JavaThread even though this runtime has no callable preloaded Shenandoah
  barrier. The prior field/static-base changes do not cover this bind-time
  primitive mirror path. Generic implementation scope: primitive mirror
  materialization must derive the current JavaThread before object loads, use
  the thread-aware native-handle barrier for the wrapper mirror OopHandle, and
  use the thread-aware Shenandoah field-load bridge for the wrapper `TYPE`
  static field. Non-Shenandoah behavior remains the existing raw/local-handle
  path; missing thread/barrier capability still hard-aborts.
- Follow-up evidence before NJX call-argument materialization correction
  2026-05-22: the primitive-mirror barrier edit regenerated fresh artifacts and
  passed the full default native performance gate, but strict Shenandoah
  obfusjack still failed at the same `MethodType.methodType(Class, Class)`
  site. Fresh generated C proves the live failing bytecode is a translated
  `GETSTATIC java/lang/Void.TYPE` (`g_static_field_ref_10`) followed by an NJX
  `neko_njx_S_L_LL` call. Sidecar HotSpot source audit proves the synthetic
  `JavaCallWrapper::_handles` field must remain the saved old handle block,
  `JavaThread::_active_handles` is the temporary active block, and
  `call_stub` receives raw oop stack words. The rejected global tag-0 handle
  barrier corrupted unrelated local-handle paths; therefore the next generic
  implementation is narrower: after `neko_njx_root_arg_oop` re-roots an object
  argument in the temporary NJX handle block, materialize only that freshly
  rooted call argument through the Shenandoah native/off-heap handle-slot
  barrier immediately before writing `call_params`. This preserves global
  tag-0 local-handle semantics, wrapper `_handles = old_handles`, raw
  call-stub parameters, and fail-closed missing-barrier behavior.
- Rejected evidence 2026-05-22: applying the Shenandoah native/off-heap
  handle-slot barrier only to the freshly re-rooted NJX object-argument slots
  preserves the default collector full performance gate, but strict
  Shenandoah still corrupts Java output and fails later in
  `BigDecimal.<init>(String)` with `ArrayIndexOutOfBoundsException`, matching
  the earlier global tag-0 barrier corruption. This proves tag-0 local
  `JNIHandleBlock` slots are not valid inputs to the native/off-heap
  `IN_NATIVE` barrier, even when used only for NJX call arguments. The change
  is reverted; the remaining valid evidence still points to `GETSTATIC
  java/lang/Void.TYPE` producing a stale primitive mirror before the
  `MethodType.methodType` NJX call.
- Evidence before primitive-wrapper TYPE intrinsic implementation
  2026-05-22: fresh generated obfusjack C identifies the surviving failing
  value as translated `GETSTATIC java/lang/Void.TYPE` via
  `g_static_field_ref_10`, not an LDC primitive class descriptor. The existing
  T4.1 primitive mirror table is the generic VM metadata surface for all
  primitive class mirrors, already covering `Z/B/C/S/I/J/F/D/V`, and the
  previous edit made that table use thread-aware Shenandoah barriers. Generic
  implementation scope: object `GETSTATIC` of primitive wrapper `TYPE` fields
  (`Boolean.TYPE` through `Void.TYPE`) must materialize from the primitive
  mirror table instead of the ordinary mutable-object static field fast path.
  The change is table/tag driven for the JVM primitive-wrapper ABI surface and
  does not alter ordinary static object fields, NJX call semantics, or missing
  metadata hard-abort behavior.
- Rejected evidence 2026-05-22: the primitive-wrapper `TYPE` intrinsic
  regenerated fresh native artifacts and the default full native performance
  gate passed (`native-performance-baseline.json` captured at
  `2026-05-22T07:41:53Z`: TEST Calc median `2 ms`, obfusjack Seq median
  `10 ms`, Platform median `40 ms`, Virtual median `37 ms`, Parallel and
  VThreads medians `1 ms`). Strict Shenandoah TEST still exited `0`, but
  strict Shenandoah obfusjack still failed at
  `MethodType.methodType(Class, Class)` with
  `IllegalArgumentException: not primitive: void`. Fresh generated C
  (`run-69524151926358/neko_native_impl_39.c`) proves the artifact already
  executes `neko_fast_get_static_object_field_ref(&g_static_field_ref_10)`;
  `neko_native_impl_prelude.h` proves that helper returns
  `neko_primitive_mirror_for_char(env, 'V')` for `java/lang/Void.TYPE`.
  The intrinsic is therefore not the root cause and is reverted.
- Corrected implementation scope 2026-05-22: generated `neko_njx_S_L_LL`
  currently saves the old `JavaThread::_active_handles`, installs the
  temporary NJX handle block, and only then resolves object handles into raw
  `call_params`. That decodes pre-existing local handles after their original
  JNIHandleBlock is no longer the thread's active handle root block. The
  generic fix is to resolve incoming jobject handles to raw oop words while
  the original active handle block is still installed, then install the NJX
  Java handle block and re-root only those raw oops into the temporary block
  before invoking `call_stub`. This preserves
  `JavaCallWrapper::_handles = old_handles`, leaves tag-0 local handle
  semantics unchanged, keeps raw oop call-stub parameters, and does not add
  JNI/JVMTI/fallback behavior.
- Follow-up evidence before HotSpot primitive-class resolver implementation
  2026-05-22: the NJX handle materialization-order correction regenerated
  fresh artifacts and the default performance gate passed on retry
  (`native-performance-baseline.json` captured at `2026-05-22T07:52:47Z`:
  TEST Calc median `3 ms`, obfusjack Seq median `10 ms`, Platform median
  `39 ms`, Virtual median `36 ms`, Parallel and VThreads medians `1 ms`), but
  strict Shenandoah obfusjack still failed at
  `MethodType.methodType(Class, Class)` with
  `IllegalArgumentException: not primitive: void`. The failed path proves the
  wrapper `TYPE`/primitive-mirror-table materialization can still hand
  `call_stub` a mirror that HotSpot rejects as primitive under Shenandoah.
  The runtime already resolves and hard-requires the non-JNI HotSpot
  `JVM_FindPrimitiveClass` symbol for slow primitive-array allocation; that VM
  entry is the generic primitive Class materialization surface and is not a
  JNI function-table fallback. Implementation scope: make
  `neko_primitive_mirror_for_char` call `JVM_FindPrimitiveClass(env,
  primitive_name)` for `Z/B/C/S/I/J/F/D/V`, hard-abort on missing symbol,
  NULL, or pending exception, and keep the existing table initialization as
  bootstrap metadata validation rather than the source of the returned mirror.
- Follow-up evidence 2026-05-22: the HotSpot primitive-class resolver alone
  regenerated fresh artifacts and passed the default performance gate, but
  strict Shenandoah obfusjack still failed at the same
  `MethodType.methodType(Class, Class)` site because the earlier rejected
  primitive-wrapper `TYPE` intrinsic had been reverted. Therefore translated
  `GETSTATIC java/lang/Void.TYPE` again used the ordinary static-field path
  instead of `neko_primitive_mirror_for_char`. Corrected implementation scope:
  restore the generic primitive-wrapper `TYPE` dispatch for
  `Boolean.TYPE` through `Void.TYPE`, now backed by
  `JVM_FindPrimitiveClass` rather than wrapper `TYPE` field reconstruction.
- Follow-up evidence before generic class-mirror OopHandle barrier
  2026-05-22: with primitive-wrapper `TYPE` routed through
  `JVM_FindPrimitiveClass`, strict Shenandoah obfusjack moved past
  `Wrapper.forPrimitiveType` and failed later in the generated MethodHandle
  bridge with `WrongMethodTypeException: handle's method type (String)void but
  found (String)void`. Fresh generated C shows the MethodType argument list is
  `Void.TYPE` plus `neko_bound_class_ref(env, &g_class_ref_9)` for
  `String.class`. Source audit shows the generic
  `neko_klass_java_mirror_handle` helper still raw-dereferences
  `Klass::_java_mirror` OopHandles and pushes the raw oop, unlike the
  primitive-specific fix. Implementation scope: apply the same
  thread-aware Shenandoah native-handle barrier to every
  `Klass::_java_mirror` materialization in `neko_klass_java_mirror_handle`,
  hard-aborting on missing JavaThread/barrier capability and leaving
  non-Shenandoah behavior unchanged.
- Rejected evidence 2026-05-22: applying the Shenandoah native-handle barrier
  to every `Klass::_java_mirror` materialization preserved the default full
  native performance gate, but strict Shenandoah obfusjack corrupted earlier
  Java state and failed in `BigDecimal.divide` with
  `NullPointerException: java.math.BigDecimal.LONG_TEN_POWERS_TABLE is null`.
  This matches the prior corruption class from over-applying the native handle
  barrier to tag-0/local slots. The generic class-mirror barrier is reverted;
  the remaining accepted evidence is that primitive-wrapper `TYPE` backed by
  `JVM_FindPrimitiveClass` moves the failure from `Wrapper.forPrimitiveType`
  to MethodHandle exact type checking.
- Follow-up implementation scope 2026-05-22: the surviving strict Shenandoah
  failure is inside the generated MethodHandle bridge at the
  signature-polymorphic `invokeExact` check, after HotSpot prints identical
  actual and expected types. The native translator already uses this generated
  Java bridge as the generic no-array/no-JNI MethodHandle invocation surface.
  Change only the bridge's signature-polymorphic call from
  `MethodHandle.invokeExact` to `MethodHandle.invoke` while preserving the
  exact static descriptor, direct call-stub entry, and no object-array fallback.
  This tests whether routing through HotSpot's adaptable polymorphic entry
  avoids the strict-GC MethodType identity mismatch without adding a runtime
  fallback or sample-specific path.
- Follow-up evidence before primitive Class VM-handle windowing 2026-05-22:
  changing the generated MethodHandle bridge from `invokeExact` to `invoke`
  passed focused codegen/translator validation and a fresh full default
  `NativeObfuscationPerfTest --no-parallel` gate. Strict Shenandoah
  TEST-native still exits `0`. Strict Shenandoah obfusjack-native now moves
  past `staticHello: from MethodHandle`, reaches the matrix-multiply section,
  and aborts in Shenandoah verification with
  `Before Mark, Roots; Should not be forwarded`. Fresh `hs_err_pid14.log`
  identifies the root slot as outside the Java heap in anonymous JVM native
  memory (`0x00007fd8842684c0` within `7fd884000000-7fd8844f7000`) and the
  rooted object as a forwarded `java.lang.Class` oop. Source audit shows the
  current primitive Class materializer is the only retained
  `JVM_FindPrimitiveClass` helper that returns the VM-created local handle
  directly; the existing slow byte-array and primitive-array helpers bracket
  the same VM entry in `neko_handle_window_begin/end`, resolve the returned
  handle to a raw oop, pop the transient VM locals, and push one native-owned
  local handle for the caller. Generic implementation scope: make
  `neko_primitive_mirror_for_char` use that existing handle-window pattern for
  `Z/B/C/S/I/J/F/D/V`, hard-aborting on missing JavaThread, unresolved handle,
  NULL, pending exception, or missing `JVM_FindPrimitiveClass`. This does not
  add JNI/JVMTI/fallback behavior and leaves the primitive-wrapper `TYPE`
  dispatch table-driven.
- Rejected evidence 2026-05-22: the primitive Class VM-handle window change
  passed focused codegen/translator validation, the fresh full default native
  performance gate, and strict Shenandoah TEST-native, but strict Shenandoah
  obfusjack-native still aborts in matrix multiply with
  `Before Mark, Roots; Should not be forwarded`. The fresh failing root remains
  a forwarded `java.lang.Class` oop in an outside-heap anonymous JVM native root
  slot (`0x00007f5a8c26f410` within `7f5a8c000000-7f5a8c502000`), not a
  generated `libneko` mapping. OpenJDK `JavaThread::oops_do` and
  `JNIHandleBlock::oops_do` prove this slot shape is a live JNIHandleBlock root
  (`active_handles()->oops_do(f)`, scanning `_handles[index]` for
  `index < _top`), so stale bytes above a restored top are not sufficient
  evidence. Generic next scope: heal raw oops at the point they are stored into
  native-owned JNIHandleBlock/local-root slots under Shenandoah, using the
  existing thread-aware native handle-slot C1 barrier or hard-aborting if that
  capability is unavailable. This applies to `neko_handle_push`,
  `neko_direct_oop_to_handle_origin`, and prepared local-root stores; heap
  field/array load barriers, MethodHandle bridge lowering, argument order, and
  benchmark-specific paths remain out of scope.
- Rejected implementation evidence 2026-05-22: applying the dedicated
  Shenandoah native/off-heap handle C1 barrier to every native-owned
  local-root store preserved the focused codegen tests and the full default
  native performance gate, but strict Shenandoah TEST-native failed before the
  first TEST banner with `java.io.IOException: No such file or directory` from
  `File.createNewFile`. `NEKO_PATCH_DEBUG=1` shows translated execution has
  entered `pack/Main.main` and repeatedly materialized `java/lang/String`
  mirrors before the Java exception, so this has the same corruption class as
  the prior tag-0 local-handle native-barrier experiments. Corrected generic
  scope: keep tag-0 local handle reads unchanged and heal raw oops at the
  native-owned root-store point with the existing thread-aware strong load
  barrier path, then store the healed value back into the JNIHandleBlock slot.
  Missing thread or barrier capability remains a hard abort.
- Rejected implementation evidence 2026-05-22: replacing the native/off-heap
  root-store barrier with the thread-aware strong field-load barrier fixed the
  compile-order issue and passed focused codegen plus a fresh full default
  native performance gate, but strict Shenandoah TEST-native failed in the same
  pre-banner `File.createNewFile` path. The broad local-root-store healing
  mechanism is therefore rejected as a class of fix and is reverted from the
  retained implementation. The next runtime change must identify and patch the
  specific stale `java.lang.Class` root producer rather than applying a barrier
  to every local-root publication.
- Diagnostic scope 2026-05-22: add a temporary env-gated
  `NEKO_ROOT_SLOT_DEBUG` trace for native-owned JNIHandleBlock/local-root slot
  creation sites. The trace records the slot address, raw oop, active block,
  top, and helper/origin for `neko_handle_push`,
  `neko_direct_oop_to_handle_origin`, and prepared local-root stores. It must
  not change runtime behavior, add fallback, skip any method/class, or persist
  outside diagnostics. Completion evidence is a fresh strict Shenandoah
  obfusjack failure whose `hs_err` root address can be matched to a diagnostic
  line identifying the exact generic root producer for the next implementation
  slice.
- Diagnostic evidence 2026-05-22: the env-gated root-slot run reproduced the
  strict Shenandoah obfusjack verifier abort. `hs_err_pid14.log` reports root
  slot `0x00007fcb7825f558` containing forwarded `java.lang.Class` oop
  `0x000000051d215758`. The diagnostic log records nearby native-owned slots in
  the same JVM handle-block arena, but no `neko_handle_push`,
  `neko_direct_oop_to_handle_origin`, or prepared local-root store created that
  exact slot. Source audit identifies the unlogged producer class:
  `neko_resolve_class_mirror_with_env` and the tolerant resolver return
  `JVM_FindClassFromBootLoader` / `JVM_FindClassFromClass` local handles
  directly, unlike the windowed primitive-array and primitive-Class helpers.
  Generic implementation scope: bracket those VM class-lookup locals in a
  handle window, resolve the returned handle to a raw oop, restore the window,
  and push one native-owned caller handle; the resolved `Klass*` validation and
  hard-abort behavior stay unchanged.
- Follow-up scope 2026-05-22: after windowing `JVM_FindClass*` locals,
  strict Shenandoah TEST-native passes and performance medians meet target, but
  strict Shenandoah obfusjack still aborts with a forwarded `java.lang.Class`
  root during matrix execution. The remaining generic Class materializer that
  still raw-dereferences a VM root is `neko_klass_java_mirror_handle`
  (`Klass::_java_mirror` OopHandle). The previous native/off-heap handle C1
  barrier on this path was rejected because it corrupted BigDecimal state.
  Corrected implementation scope: resolve only `Klass::_java_mirror` through
  the existing thread-aware strong load barrier under Shenandoah before pushing
  the caller local handle; tag-0 local handles, object roots, field/array
  loads, and benchmark paths remain unchanged.
- Rejected/follow-up evidence 2026-05-22: resolving only
  `Klass::_java_mirror` through the thread-aware strong load barrier passed
  focused `CCodeGeneratorTest` / `OpcodeTranslatorUnitTest`, a fresh default
  `NativeObfuscationPerfTest --no-parallel` gate, static generated-C forbidden
  JNI inspection, and strict Shenandoah TEST-native with empty stderr and
  `Calc: 2ms`. Fresh default medians were TEST Calc `2 ms`, obfusjack Platform
  `37 ms`, Virtual `35 ms`, Seq `10 ms`, Parallel `1 ms`, and VThreads
  `1 ms`. Strict Shenandoah obfusjack-native then deterministically aborted at
  `[neko-direct] MONITORENTER null object` after generated
  `neko_native_impl_39.c` read `g_static_field_ref_6`, which generated metadata
  maps to `org/example/Main.LOCK` with `g_static_base_20/g_static_off_20`.
  The current top-level `hs_err_pid14.log` still describes the prior forwarded
  Class-root verifier failure, so the monitor abort is the fresh actionable
  invariant. Generic diagnostic scope: add temporary env-gated object
  `GETSTATIC` tracing in `neko_fast_get_static_object_field_ref` /
  `neko_fast_get_static_object_field` to record class handle, staticBase
  handle, unwrapped base oop, offset, field address, raw narrow/wide slot, and
  post-barrier oop when a static object read returns NULL. The diagnostic must
  not change semantics, add fallback, skip any method/class, or persist after
  it identifies the generic static-base/barrier invariant.
- Diagnostic evidence 2026-05-22: the env-gated GETSTATIC/class-init trace on
  a freshly regenerated Shenandoah obfusjack artifact proves the failing
  static read is not a raw field-barrier miss: the raw global slot value, both
  Shenandoah-resolved class mirrors, and the selected field address all contain
  NULL at offset `112` for `org/example/Main.LOCK`. The same fresh jar passes
  under G1 with the GETSTATIC debug env enabled. `NEKO_CLASS_INIT_DEBUG=1`
  shows `neko_ensure_class_initialized_once` calls the VM init path for
  `org/example/Main` with `before=0` and sets the slot after return, but
  `JVM_FindClassFromClass(..., initialize=true, ...)` returns a different
  initialized local class handle from the cached global class/static-base
  handle that the later direct static read uses. Generic implementation scope:
  make class initialization return a caller-owned initialized class handle, and
  have static field refs use that handle as the immediate static-base mirror
  when it differs from the cached base. This preserves the existing metadata
  offset, hard-abort behavior, and no-JNI-fallback rule; the temporary
  diagnostics must be removed before checkpoint validation.
- Rejected evidence 2026-05-22: bypassing the per-class initialization cache for
  Shenandoah/ZGC regenerated and ran, but strict Shenandoah TEST-native reported
  semantic failures (`Pool FAIL`, `ReTrace FAIL`, `Calc: 13ms`) and strict
  Shenandoah obfusjack-native returned to the forwarded `java.lang.Class` root
  verifier abort. The moving-collector no-cache shape is rejected and reverted;
  class initialization caching must remain intact.
- Rejected evidence 2026-05-22: refreshing every static-field ref class under
  Shenandoah by materializing a new local handle from `Klass::_java_mirror`
  passed focused codegen but failed the default
  `NativeObfuscationPerfTest --no-parallel` gate when
  `obfusjack-native.jar` timed out after `PT2M`. The per-static-field local
  handle refresh shape is rejected; the next generic fix must preserve default
  collector liveness and avoid hot-path handle growth.
- Implementation scope before editing 2026-05-22: keep the class-init return
  handle and class-init cache, but separate static-field data access from local
  handle materialization. Add a raw `Klass::_java_mirror` oop helper that applies
  the existing thread-aware Shenandoah barrier and does not push a local handle.
  Object `GETSTATIC` should use that raw mirror oop as the Shenandoah static
  field container, then fall back to the VM-bound static base/class handles.
  This targets the proven `org/example/Main.LOCK`/`ATOMIC` static-base invariant
  without weakening transforms, adding JNI/JVMTI/fallback, or special-casing
  any owner, field, descriptor, benchmark, or log string.
- Rejected/follow-up evidence 2026-05-22: the raw static-base mirror helper
  preserved the default performance gate (`translated=49/93 rejected=0`, TEST
  Calc `4 ms`, obfusjack Platform `39 ms`, Virtual `36 ms`, Seq `10 ms`), and
  strict Shenandoah TEST-native exited `0`, but strict Shenandoah
  obfusjack-native still aborted at `[neko-direct] MONITORENTER null object`.
  Same-execution `NEKO_GETSTATIC_DEBUG=1` diagnostics on fresh
  `build/neko-native-work/run-76286023375730` show
  `neko_fast_get_static_object_field` selected `base=0x7ffc000d8` and
  `field=0x7ffc00148` for `org/example/Main.LOCK`, while a later diagnostic
  reread of the initialized class handle found the real mirror
  `base_oop=0x51d0a7ba8`, field `0x51d0a7c18`, raw oop `0x51d2b9f70`, and a
  heap-field barrier result `0x7ffc021e0`. The failing invariant is now
  concrete: `Klass::_java_mirror` is an off-heap OopHandle slot, but the raw
  helper was resolving it with the heap-field C1 barrier, which returns a
  stack-like address. Corrected scope: resolve `Klass::_java_mirror` through the
  existing thread-aware Shenandoah native handle-slot barrier and hard-abort if
  the native handle barrier is unavailable; do not add fallback, skip behavior,
  or owner/field-specific handling.
- Rejected evidence 2026-05-22: switching `Klass::_java_mirror` OopHandle
  resolution to the native handle-slot barrier preserved focused codegen and the
  default performance gate, but strict Shenandoah obfusjack-native exited with
  `java.lang.NullPointerException: Cannot read the array length because
  "java.math.BigDecimal.LONG_TEN_POWERS_TABLE" is null`. This reproduces the
  earlier BigDecimal corruption class for native-barriered class mirrors and is
  rejected again. Corrected scope: stop using `Klass::_java_mirror` as the object
  `GETSTATIC` static-base source under Shenandoah. For already-initialized
  static field refs, reacquire a current class mirror through the existing
  windowed `JVM_FindClassFromClass(..., initialize=false, ...)` class-resolution
  path and use that caller-owned local only for the immediate static read.
- Rejected evidence 2026-05-22: the windowed class re-resolution shape preserved
  focused codegen and the default performance gate, but strict Shenandoah
  TEST-native regressed to semantic failures (`Pool FAIL`, `ReTrace FAIL`,
  `Calc: 27ms`) and strict Shenandoah obfusjack-native aborted in the verifier
  with `Before Mark, Roots; Should not be forwarded` for a `java.lang.Class`
  root in outside-heap memory. This recreates the VM-local Class root problem and
  is rejected. Corrected scope: static field access must use the canonical
  field-holder `Klass` plus offset, read the VM-owned `Klass::_java_mirror`
  OopHandle slot as a raw temporary field container, and avoid publishing that
  mirror as a native-owned local root. Ordinary `jclass` handle materialization
  remains on the previously validated barriered path.
- Follow-up evidence 2026-05-22: the canonical field-holder `Klass` plus raw
  `Klass::_java_mirror` temporary field-container shape preserved focused
  codegen tests and the full default native performance gate. Fresh artifacts
  `build/neko-native-work/run-76853375166690` and
  `build/neko-native-work/run-76857995229630` reported `translated=49/93
  rejected=0`, TEST Calc median `4 ms`, obfusjack Platform `40 ms`, Virtual
  `35 ms`, Seq `10 ms`, Parallel/VThreads `1 ms`. Strict Shenandoah TEST-native
  exited `0` with empty stderr, but strict Shenandoah obfusjack-native aborted
  during matrix execution in Shenandoah verification with
  `Before Mark, Roots; Should not be forwarded`; `hs_err_pid4.log` reports an
  outside-heap root slot `0x00007f32b42cc8e8` containing a forwarded
  `java.lang.Class` oop. The current change is therefore not accepted. Next
  diagnostic scope: add temporary env-gated `NEKO_ROOT_SLOT_DEBUG` tracing to
  native-owned root publication points (`neko_handle_push`,
  `neko_direct_oop_to_handle_origin`, and prepared local-root stores), recording
  slot address, raw oop, block/top, and origin without changing behavior. The
  completion evidence for the diagnostic is a fresh strict Shenandoah
  obfusjack failure whose `hs_err` root slot either matches a trace line or
  proves the root is produced by an unlogged VM-owned local/materialization path.
- Diagnostic evidence 2026-05-22: `NEKO_ROOT_SLOT_DEBUG=1` reproduced the
  strict Shenandoah obfusjack verifier abort on a freshly regenerated artifact.
  The new `hs_err_pid4.log` root slot is `0x00007f8aa8328858`, containing
  forwarded `java.lang.Class` oop `0x00000005375f2a30`. The trace logged
  native-owned publications in the same JNIHandleBlock arena, for example
  `handle_push_overflow slot=0x7f8aa8325048` and a run of Class-like
  `handle_push` slots through `0x7f8aa8325658`, but no trace line created
  `0x7f8aa8328858`. Later prepared local-root stores were in the separate
  `0x7f8aa832fda0` range and also did not match. Source audit identifies a
  generic unscoped bind-time class-local path: `neko_resolve_class_with_env`
  discards the caller local handle returned by `neko_resolve_class_mirror_with_env`
  after extracting only `Klass*`; `neko_bind_class_slot_from` and
  `neko_bind_primitive_class_slot` create global refs from temporary local
  Class handles but leave those locals in the active JNIHandleBlock. Corrected
  scope: wrap these bind-time class local lifetimes in the existing
  `neko_handle_window_begin/end` primitive so metadata/global refs survive but
  temporary local `java.lang.Class` roots are popped before translated runtime.
- Follow-up evidence 2026-05-22: after bind-time class-local windowing,
  focused codegen, the full default performance gate, and strict Shenandoah
  TEST-native still pass, but strict Shenandoah obfusjack-native still aborts
  with the same forwarded `java.lang.Class` root class. A second
  `NEKO_ROOT_SLOT_DEBUG=1` run reports root slot `0x00007f5ff432d328`; the
  nearest native-owned trace in that same active-handle chain is
  `handle_push_overflow slot=0x7f5ff432a358 raw=0x7ffc041f8`, after which the
  failing slot appears about 41 JNIHandleBlock blocks later with no native-owned
  publication trace. The surrounding trace is inside repeated NJX return/static
  object handling, and source audit shows NJX replaces
  `JavaThread::_active_handles` with a stack/calloc synthetic block before
  `call_stub`, allowing VM-created Class locals during the Java upcall to land
  in a synthetic active JNIHandleBlock chain. Corrected scope: keep
  `JavaThread::_active_handles` on the existing HotSpot active block, root NJX
  object arguments into that block under the already existing
  `neko_handle_save/restore` window, and restore the window after `call_stub`.
  The JavaCallWrapper `_handles` field remains the saved old handle block; no
  JNI fallback, helper layer, or benchmark-specific path is introduced.
- Rejected evidence 2026-05-22: removing the synthetic NJX active handle block
  and rooting call arguments in the existing active block passed focused
  codegen, but failed the default performance/runtime gate before strict GC.
  `NativeObfuscationPerfTest` aborted TEST at `Test 2.5: Loader` with
  `[neko-direct] NJX object argument did not resolve handle=...`, proving
  object handles passed through loader/reflection NJX paths can require the
  dedicated Java-call handle materialization order. The no-synthetic-block
  experiment is rejected and reverted; the remaining root fix must preserve
  default TEST loader semantics while addressing Shenandoah's unlogged
  VM-created Class locals.
- Corrected scope after sidecar audit 2026-05-22: preserve the existing NJX
  synthetic Java-call handle block, but add handle windows around runtime
  bootstrap/descriptor/lookup helper chains that create temporary
  `java.lang.Class`, `MethodType`, `Lookup`, `Method`, string, and Object[]
  handles. `neko_method_type_from_descriptor`,
  `neko_bootstrap_parameter_array`, `neko_lookup_for_class`,
  `neko_lookup_for_jclass`, `neko_invoke_bootstrap`, and
  `neko_resolve_constant_dynamic` must prepare the actual returned oop before
  ending the window and then push one caller-owned return handle. This keeps
  default NJX object-argument materialization intact while bounding transient
  VM-created Class locals.
- Rejected evidence 2026-05-22: broad runtime bootstrap/descriptor/lookup
  handle-windowing passed focused codegen and the TEST calc runtime, but failed
  the default performance gate because `obfusjack-native.jar` timed out after
  `PT2M` during `nativeObfuscation_captureNativePathPerformanceBaseline`.
  This shape is too broad for the hot MethodType/bootstrap paths and is
  reverted. The remaining fix must be narrower and preserve default obfusjack
  completion time before strict-GC validation.
- Corrected narrow NJX scope 2026-05-22: preserve the synthetic front
  JNIHandleBlock for NJX argument roots, but before `call_stub` repoint that
  front block's `_last` field to the existing HotSpot active-handle chain tail.
  Current evidence shows unlogged VM-created Class locals appear dozens of
  blocks after the synthetic block's first overflow; this happens because
  `neko_njx_install_java_handles` initializes `_last` to the synthetic block
  and the argument-root writes leave VM handle allocation behind the synthetic
  block. The narrow fix must keep default NJX object argument semantics,
  JavaCallWrapper `_handles = old_handles`, and synthetic argument roots, while
  directing VM-created locals during the upcall to the existing handle-chain
  tail.
- Rejected evidence 2026-05-22: the NJX `_last` tail repoint preserved focused
  codegen, the fresh full default native performance gate, and strict
  Shenandoah TEST-native with empty stderr and `Calc: 4ms`, but strict
  Shenandoah obfusjack-native still aborted with `Before Mark, Roots; Should not
  be forwarded`. The non-debug run reported outside-heap root slot
  `0x00007f0e80349328` containing forwarded `java.lang.Class` oop
  `0x00000005374fbf48`; the `NEKO_ROOT_SLOT_DEBUG=1` run reported root slot
  `0x00007fc4c833cd88` containing forwarded `java.lang.Class` oop
  `0x00000005375eef50`. The exact root slot and oop did not appear in the
  native-owned root trace, and the nearest same-chain trace remained
  `handle_push_overflow slot=0x7fc4c8339cb8 raw=0x7ffc041f8 index=4986`, about
  41 JNIHandleBlock blocks before the failing slot. The `_last` repoint does
  not change the failing root producer and is rejected unless a later diagnostic
  proves it is needed for a separate invariant. Next scope: add targeted
  NJX/active-handle diagnostics around install, argument rooting, tail publish,
  and `call_stub` entry/return to prove whether VM-created locals allocate from
  the synthetic front block, a hidden old-chain tail, or another active-handle
  publication path before making another runtime change.
- Diagnostic evidence 2026-05-22: targeted NJX chain tracing on a freshly
  regenerated artifact reproduced the strict Shenandoah verifier abort with
  root slot `0x00007ff238325d28` containing forwarded `java.lang.Class` oop
  `0x00000005378f6418`. Native-owned root tracing still did not create the
  exact slot; the nearest same-arena native trace is
  `handle_push_overflow slot=0x7ff238322df8 raw=0x7ffc041f8 index=4986`, and
  the failing slot is about 41 `JNIHandleBlock` allocations after that native
  overflow block (`sizeof_JNIHandleBlock=296`, capacity `32`). The NJX trace
  proves `neko_njx_publish_old_handle_tail` is active at call entry, but the
  failing root is not produced by the synthetic front block's `_last` field.
  Source evidence identifies the generic invariant break: native overflow in
  `neko_handle_push` and `neko_direct_oop_to_handle_origin` makes the new block
  the JavaThread active head and links `_next` back to the previous block,
  whereas HotSpot `JNIHandleBlock` allocation uses the active block as the head
  and appends additional blocks through head `_last` and tail `_next`. The next
  implementation scope is to make native overflow append to the active head's
  `_last` chain, matching the existing `neko_prepare_local_oop_roots` pattern,
  so later VM-created locals and verifier scanning share one forward chain.
- Rejected evidence 2026-05-22: changing native overflow to append directly to
  the active head's `_last` chain passed focused codegen but failed the fresh
  default performance gate before strict GC. `TEST-native.jar` failed in the
  default collector with `NullPointerException: Cannot invoke
  "Object.getClass()" because "this" is null` from `PrintStream.println`,
  proving direct append mutates saved outer handle chains across scoped
  `neko_handle_save/restore` windows. The corrected scope must preserve the
  existing scoped prepend behavior for native-owned overflow blocks, but publish
  the prepended block's `_last` as the previous chain tail so VM-created locals
  allocated after the native overflow do not grow behind that prepended block.
- Corrected scope 2026-05-22: keep the HotSpot-compatible append overflow
  shape, but make `neko_handle_save/restore` tail-aware. A handle scope must
  record the saved tail block and that tail's `_top`; restore must reset the
  saved tail `_top`, trim any blocks appended after the saved tail, and restore
  head `_next`/`_last`. This preserves scoped local lifetime while keeping
  native overflow and later VM-created locals in the same forward
  `JNIHandleBlock` chain.
- Follow-up evidence 2026-05-22: tail-aware append compiled and ran, and it is
  retained as a HotSpot-compatible `JNIHandleBlock::_last` shape correction, but
  it is not sufficient for strict Shenandoah. Focused
  `CCodeGeneratorTest`/`OpcodeTranslatorUnitTest`, the full default
  `NativeObfuscationPerfTest --no-parallel` gate, and strict Shenandoah
  TEST-native passed. Fresh default medians were TEST Calc `4 ms`, obfusjack
  Platform `46 ms`, Virtual `40 ms`, Seq `10 ms`, Parallel/VThreads `1 ms`.
  Strict Shenandoah obfusjack-native still aborted with `Before Mark, Roots;
  Should not be forwarded`; the non-debug run reported root slot
  `0x00007f5b8031f8e8` containing forwarded `java.lang.Class` oop
  `0x0000000526c77088` with forwardee `0x00000007ffc03e78`. A fresh
  `NEKO_ROOT_SLOT_DEBUG=1` run reported root slot `0x00007f1d242f7468`
  containing `0x0000000526cc6378` with forwardee `0x00000007ffa1ed80`. That
  exact slot was not emitted by native root tracing; the nearest same-arena
  native publications included `direct_handle_tail slot=0x7f1d242f2858
  raw=0x526cc62f0 origin=9`, `local_root_store slot=0x7f1d242f2860`, and
  `handle_push slot=0x7f1d2b5fcd90`, all for a nearby but different oop. The
  remaining diagnostic scope is to prove whether the unlogged Class root is a
  VM-created local-root lifetime issue or a native root publication that still
  escapes current tracing.
- Diagnostic scope 2026-05-22: add an env-gated active-handle-chain scanner
  around NJX call-stub entry/return and handle restoration. Local HotSpot 21
  source proves `JNIHandleBlock::oops_do` scans `_handles[0.._top)` and follows
  `_next` only while the current block is full; `ShenandoahVerifyNoForwarded`
  reads root slots with `RawAccess` and fails when a slot's raw oop differs from
  its forwarding header target. The diagnostic must mirror that scan order,
  report only slots whose mark word is already forwarded, and must not mutate
  roots or run unless the env var is set. Completion evidence is a strict
  Shenandoah obfusjack run that either logs the exact failing slot before the VM
  verifier or proves the slot is created after our instrumented NJX/native
  handle boundaries.
- Follow-up diagnostic scope 2026-05-22: extend the scanner to HotSpot
  `HandleArea` roots. The NJX active/saved `JNIHandleBlock` scanner reproduced
  the strict Shenandoah verifier abort without logging a forwarded slot at NJX
  or safepoint boundaries, while local OpenJDK 21 source shows
  `Thread::oops_do_no_frames` scans `handle_area()->oops_do(f)` and
  `JavaCalls::call_helper` brackets the direct `call_stub` with
  `HandleMark hm(thread)`. The generated synthetic call-stub path has no
  equivalent VM `HandleMark`. The next diagnostic must prove whether the exact
  VM verifier root slot is in the current thread `HandleArea`; it remains
  env-gated, read-only, and must not heal or clear roots.
- Rejected diagnostic evidence 2026-05-22: the first direct `HandleArea` scanner
  shape passed focused codegen and a fresh full `NativeObfuscationPerfTest
  --no-parallel` regeneration, but strict Shenandoah obfusjack-native aborted
  before application output with SIGSEGV in generated native code
  (`libneko_13625905931107475994.so+0x49dc9`, `hs_err_pid13.log`). It emitted no
  `neko-root-area` line before the crash, so the scanner did not provide slot
  proof and is rejected as an unsafe memory-walk diagnostic. The implementation
  is removed before further evidence gathering.
- Corrected implementation scope 2026-05-22: add a synthetic VM HandleArea
  save/restore boundary around generated NJX `call_stub`. Evidence chain:
  strict Shenandoah verifier reports an outside-heap root slot in unknown C-heap
  memory containing a forwarded `java.lang.Class`; the active/saved
  `JNIHandleBlock` scanner reproduced the abort without finding that slot;
  `Thread::oops_do_no_frames` scans `handle_area()->oops_do(f)`; and
  `JavaCalls::call_helper` wraps the exact direct `call_stub` region with
  `HandleMark hm(thread)`. The implementation must use VMStructs-provided
  `Thread::_resource_area` plus local product HotSpot layout to reach
  `Thread::_handle_area`, validate the derived Arena fields before use, restore
  `_chunk/_hwm/_max/_size_in_bytes` after the upcall, and hard-abort if the
  layout is incoherent. It must not scan arbitrary memory, call JNI/JVMTI, fall
  back to Java helpers, skip collectors/classes, or change NJX argument/result
  ABI.
- Rejected implementation evidence 2026-05-22: the synthetic VM HandleArea
  save/restore boundary passed focused codegen/translator tests and a rerun of
  the full default `NativeObfuscationPerfTest --no-parallel` gate, but it is not
  accepted. The first full gate attempt timed out running obfusjack-native after
  `PT2M`; direct reruns of both generated obfusjack jars then completed under 30
  seconds, and the second full gate passed. Fresh default medians were TEST Calc
  `4 ms`, obfusjack Platform `60 ms`, Virtual `83 ms`, Seq `10 ms`,
  Parallel/VThreads `1 ms`, still over the Platform/Virtual targets. Strict
  Shenandoah obfusjack-native still aborted with `Before Mark, Roots; Should not
  be forwarded`; `hs_err_pid3.log` reported outside-heap root slot
  `0x00007f124037a708` containing forwarded `java.lang.Class` oop
  `0x00000005374f85d8` with forwardee `0x00000007fe9e3380`. The direct
  HandleArea boundary is removed as insufficient and too expensive.
- Corrected diagnostic scope 2026-05-22: add only an env-gated, read-only
  derived `HandleArea` scanner to the existing `NEKO_ROOT_SCAN_DEBUG` path.
  The previous direct scanner is rejected because it walked unsafe candidate
  memory and crashed before producing evidence. The corrected diagnostic must
  use VMStructs `Thread::_resource_area`, the local product HotSpot field order
  (`_resource_area` followed by `_handle_area`), and OpenJDK 21 `Arena` /
  `Chunk` layout evidence to scan only the current thread's validated
  `HandleArea`. It must validate candidate addresses against readable process
  mappings and Arena chunk bounds before reading slots, report only forwarded
  oop slots with site/thread/slot/chunk/raw/forwardee, never mutate roots, and
  stay inactive unless `NEKO_ROOT_SCAN_DEBUG` is set. Completion evidence is a
  fresh strict Shenandoah obfusjack run whose `hs_err` root slot either appears
  in the derived `HandleArea` trace or is proven to be outside the current
  thread's `JNIHandleBlock` and `HandleArea` scans.
- Diagnostic overhead evidence 2026-05-23: the first derived `HandleArea`
  scanner with per-slot `/proc/self/maps` readability checks passed focused
  generator/translator validation and regenerated a fresh default artifact once,
  but the `NEKO_ROOT_SCAN_DEBUG=1` strict Shenandoah obfusjack diagnostic timed
  out after `120s` at the microbench section with no `hs_err`, stderr, or
  forwarded-slot output. After caching process maps, a fresh full
  `NativeObfuscationPerfTest --rerun-tasks --no-parallel` run still failed
  with `Timed out running jar .../obfusjack-native.jar after PT2M`. The
  diagnostic is therefore narrowed again: validate the current `HandleArea`
  chunk bounds against cached maps, then scan the validated oop-slot range
  directly, matching HotSpot `HandleArea::oops_do` and avoiding per-slot map
  probes.
- Diagnostic evidence 2026-05-23: the narrowed derived `HandleArea` scan
  regenerated and passed the default full native performance gate. Fresh
  medians were TEST Calc `4 ms`, obfusjack Platform `59 ms`, Virtual `89 ms`,
  Seq `10 ms`, Parallel/VThreads `1 ms`, so the strict performance target
  remains open. A strict Shenandoah obfusjack diagnostic with
  `NEKO_ROOT_SCAN_DEBUG=1` reproduced the verifier abort in matrix execution at
  outside-heap slot `0x00007f7a1c307778`, containing forwarded
  `java.lang.Class` oop `0x0000000526cc8880` with forwardee
  `0x00000007ffa1ed80`, but still emitted no `neko-root-area`,
  `neko-root-scan`, or native publisher line for that slot. The slot is in the
  same anonymous C-heap mapping as worker JavaThreads and about `0x6108` bytes
  before `ForkJoinPool.commonPool-worker-1`'s `JavaThread`. Next diagnostic is
  an env-gated, capped `NEKO_ROOT_AREA_SUMMARY` range trace for validated
  current-thread `HandleArea` chunks, to classify the next failing slot as
  inside or outside a scanned HandleArea range without mutating roots.
- Method-frame evidence 2026-05-22: generated translated wrappers currently call
  `neko_native_impl_N_body(...)` and return the body result directly, while
  handle-exposing bodies emit `neko_prepare_local_oop_roots(...)` and reserve
  JNIHandleBlock slots without any method-frame restore. Source audit also shows
  optimized translated-to-translated calls use `_body(...)` directly, so hot
  loops can allocate helper/class/object local handles repeatedly without a
  callee lifetime boundary. The generic invariant is that every translated
  method body that can expose JNI handles must run inside a handle save/restore
  window on both external dispatch and optimized direct-call entry. Reference
  returns must be resolved to an oop before restoring the method window, then
  republished as one caller-owned handle in the outer active handle scope so
  translated direct callers never carry an unrooted raw oop on their operand
  stack. Validation target: fresh generated C must show
  handle-exposing wrappers bracketing `_body(...)` with
  `neko_handle_save/restore`, reference-return wrappers preserving via
  `neko_prepare_return_oop` and `neko_direct_oop_to_handle_origin`, no-handle
  translated bodies staying unwindowed, and direct translated calls selecting
  the wrapper unless the target was proven no-handle safe. Runtime proof is
  focused codegen, full default performance, strict Shenandoah TEST, strict
  Shenandoah obfusjack, and generated-C grep for forbidden JNI wrappers/fallback
  markers.
- Validation evidence 2026-05-22: the conservative translated method-frame
  implementation passed focused `CCodeGeneratorTest`/`OpcodeTranslatorUnitTest`
  and a fresh full default `NativeObfuscationPerfTest --no-parallel` gate.
  Fresh medians were TEST Calc `4 ms`, obfusjack Platform `48 ms`, Virtual
  `52 ms`, Seq `10 ms`, Parallel `1 ms`, and VThreads `1 ms`; the default gate
  passes but Platform/Virtual remain above the stricter native performance
  target. Strict Shenandoah TEST-native still exits `0` with empty stderr and
  `Calc: 3ms`. Strict Shenandoah obfusjack-native still aborts in verifier root
  scanning with `Before Mark, Roots; Should not be forwarded`; the latest
  root-debug run reports root slot `0x00007f842431ff28` containing forwarded
  `java.lang.Class` oop `0x00000005378f25c0`, with nearest same-arena native
  traces `direct_handle_overflow slot=0x7f842431cbd8` and
  `handle_push_overflow slot=0x7f8424326c28`, but no native-owned trace for the
  exact failing slot. The method-frame change is retained as a generic lifetime
  boundary for translated bodies, but it is not sufficient for T4.8d/T4.15.
- Follow-up scope 2026-05-22: clear stale oop slots from every native-owned
  `JNIHandleBlock` before recycle/free and before freeing scoped slab storage.
  Source evidence shows `neko_recycle_jnih_block` clears blocks only on the
  retained recycle-list path, while the immediate-free path and scoped slab
  cleanup can release blocks with nonzero `_top` and old handle words. Restore
  also unlinks scoped blocks without clearing them when `scope_slabs != NULL`.
  Sidecar audit also identified `neko_njx_restore_java_handles` as the same
  class: it saves `_next` before releasing detached Java-call handle blocks, but
  it frees heap blocks and leaves stack-backed blocks uncleared before restoring
  `JavaThread::_active_handles`.
  The generic invariant is that any block removed from `JavaThread::_active_handles`
  ownership by `neko_handle_restore` must have its top and oop slots cleared
  before it can be recycled, freed, or returned to scoped slab storage. The
  implementation must save `_next` before clearing a block, must not clear the
  still-active saved head before restoring `saved_top`, and must not change
  ordinary handle semantics or add JNI/JVMTI/fallback behavior. Validation is
  focused codegen/translator tests, fresh full default performance gate, strict
  Shenandoah TEST, strict Shenandoah obfusjack, and generated-C forbidden-marker
  inspection.
- Rejected/follow-up evidence 2026-05-22: stale-slot clearing passed focused
  codegen/translator tests, a fresh full default performance gate, and strict
  Shenandoah TEST-native, but strict Shenandoah obfusjack-native still aborted
  in matrix execution with `Before Mark, Roots; Should not be forwarded`.
  Root-debug reported failing slot `0x00007f78503316f8` containing forwarded
  `java.lang.Class` oop `0x0000000526c74400`. The exact slot was not created
  by native root tracing; the nearest same-arena native publication was
  `direct_handle_overflow slot=0x7f7850334438 raw=0x526c744b8 origin=9`, where
  origin `9` is `NEKO_HANDLE_ORIGIN_NJX_RETURN`. The stale-clearing change does
  not remove the failing root class by itself. Corrected generic scope: heal
  raw object oops returned from HotSpot `call_stub` before publishing them into
  a native local handle in `neko_njx_oop_to_handle`. This is limited to NJX
  return publication and must not reapply the previously rejected tag-0 local
  handle native barrier, broad class-mirror native barrier, no-synthetic-NJX
  block shape, or broad bootstrap windowing.
- Rejected evidence 2026-05-22: healing raw NJX object returns through the
  Shenandoah native-handle barrier passed focused codegen/translator tests but
  failed the default full performance gate before strict-GC validation:
  `nativeObfuscation_captureNativePathPerformanceBaseline` timed out after
  `PT2M` running regenerated `obfusjack-native.jar`. This shape is rejected and
  reverted. The remaining fix must not barrier every NJX object return through
  the native-handle C1 stub on the default path.
- Corrected scope 2026-05-22: apply a cheap generic root-publication heal before
  generated native code writes a raw oop into any native-owned
  `JNIHandleBlock` or translated local-root slot. OpenJDK 21 `markWord.hpp`
  defines `marked_value = 3`, `is_marked()` as the low lock bits equal to
  `11`, and `decode_pointer()` as `clear_lock_bits().value()`, matching the
  observed Shenandoah verifier reports where a mark such as
  `marked(0x00000007ffc03df3)` has forwardee `0x00000007ffc03df0`. The generic
  invariant is that under Shenandoah a raw oop with a marked forwarding header
  must be decoded to its forwardee before native code publishes it as a root.
  Apply this only at root publication (`neko_handle_push`,
  `neko_direct_oop_to_handle_origin`, and `neko_store_local_oop_raw`), not on
  every NJX return or tag-0 handle read.
- Follow-up evidence 2026-05-22: the cheap root-publication heal passed focused
  codegen/translator validation, the fresh full default native performance
  gate, and strict Shenandoah TEST-native with empty stderr and `Calc: 4ms`.
  Strict Shenandoah obfusjack-native still aborted during matrix execution with
  `Before Mark, Roots; Should not be forwarded`; fresh `hs_err_pid4.log`
  reports outside-heap root slot `0x00007f6c1832db98` containing forwarded
  `java.lang.Class` oop `0x00000005379f07c8` with forwardee
  `0x00000007fe99ab80`. Sidecar HotSpot-source audit proves `call_stub` takes
  raw oop argument words and entry-frame scanning treats those words as roots,
  while `JavaCallWrapper::_handles` remains the saved previous active handle
  block. Generated `neko_njx_*` support still writes object args by re-rooting
  them, unwrapping the temporary local back to a raw oop, and storing that raw
  oop directly into `call_params` before `call_stub`; those raw words are not
  covered by the JNIHandleBlock/local-root publication hook. Corrected generic
  scope: apply the same cheap Shenandoah marked-forwarding decode to raw NJX
  object oops immediately before publishing them to `call_params`/entry-frame
  roots, preserving the existing synthetic Java-call handle block and avoiding
  the rejected tag-0/native-handle C1 barrier path.
- Rejected/follow-up evidence 2026-05-22: applying the cheap forwarding decode
  to raw NJX object arguments passed focused codegen/translator validation and
  the fresh full default native performance gate (`TEST` Calc median `4 ms`;
  obfusjack medians Platform `50 ms`, Virtual `50 ms`, Seq `10 ms`,
  Parallel/VThreads `1 ms`). Strict Shenandoah TEST-native still exited `0`,
  but strict Shenandoah obfusjack-native again aborted during matrix execution
  with `Before Mark, Roots; Should not be forwarded`; fresh `hs_err_pid4.log`
  reports outside-heap root slot `0x00007f268c3255e8` containing forwarded
  `java.lang.Class` oop `0x00000005375ea388` with forwardee
  `0x00000007fe9fb780`. The `call_params` root-publication hook is retained as
  a generic root hygiene fix, but it is not the remaining producer. The next
  diagnostic scope is to rerun the same artifact with `NEKO_ROOT_SLOT_DEBUG=1`
  and compare the exact failing root slot against native-owned
  `JNIHandleBlock`, local-root, and NJX publication traces before changing
  another runtime path.
- Diagnostic evidence 2026-05-22: `NEKO_ROOT_SLOT_DEBUG=1` on the same fresh
  artifact reproduced the strict Shenandoah verifier abort with root slot
  `0x00007f859c3277f8` containing forwarded `java.lang.Class` oop
  `0x00000005374f2478`. The exact slot is not emitted by native root
  publishers; nearest same-arena trace entries are `direct_handle_overflow`
  at `0x7f859c3248e8` and later `handle_push_overflow` at
  `0x7f859c32e938`, again placing the failing root inside VM-created
  `JNIHandleBlock` storage during the Java upcall. Local HotSpot 21 source
  shows `JavaCallWrapper` allocates a fresh `JNIHandleBlock` whose `_next` is
  initialized to `NULL`, stores the old active block only in
  `JavaCallWrapper::_handles`, then installs the fresh block as
  `JavaThread::_active_handles`. Current generated NJX install instead links
  the synthetic fresh block's `_next` to the old active chain. Corrected
  generic scope: make `neko_njx_install_java_handles` match the HotSpot
  JavaCallWrapper block shape by setting the fresh active block `_next` to
  `NULL` and keeping the old active block only in the synthetic wrapper
  `_handles`/restore state. Do not change argument order, wrapper `_handles`,
  or the rejected tag-0/native-handle barrier path.
- Follow-up evidence 2026-05-22: the HotSpot-compatible NJX fresh-block
  `_next = NULL` shape passed focused `CCodeGeneratorTest` /
  `OpcodeTranslatorUnitTest` validation and a fresh full default
  `NativeObfuscationPerfTest --no-parallel` gate. Fresh medians were TEST Calc
  `4 ms`, obfusjack Platform `49 ms`, Virtual `53 ms`, Seq `10 ms`,
  Parallel/VThreads `1 ms`. Strict Shenandoah TEST-native still exited `0`
  with empty stderr and `Calc: 4ms`, but strict Shenandoah obfusjack-native
  still aborted during matrix execution with `Before Mark, Roots; Should not be
  forwarded`; the verifier reported outside-heap root slot
  `0x00007f64d82ff3f8` containing forwarded `java.lang.Class` oop
  `0x0000000526cc8f90` with forwardee `0x00000007ffc04370`. The block-shape
  correction is retained as a generic HotSpot JavaCallWrapper parity fix, but it
  is not sufficient for T4.8d/T4.15. Next evidence must come from a fresh
  `NEKO_ROOT_SLOT_DEBUG=1` run on the retained shape before another runtime
  path changes.
- Diagnostic evidence 2026-05-22: the fresh `NEKO_ROOT_SLOT_DEBUG=1`
  retained-shape run reproduced strict Shenandoah obfusjack abort with root
  slot `0x00007f08dc2fdfd8` containing forwarded `java.lang.Class` oop
  `0x00000005270c6008` and forwardee `0x00000007ffa1ed80`. The exact slot is
  not emitted by native root publishers; nearest same-arena native overflow
  slots include `0x7f08dc2f6d68` / `0x7f08dc2f6e90` and later native
  publications, so the failing slot remains VM-created local storage during
  Java upcall. OpenJDK 21 `JavaCalls::call_helper` creates
  `JavaCallWrapper`, then calls `JavaCallArguments::parameters()` to resolve
  object arguments into raw oop words; it does not re-root those object
  arguments into the fresh active `JNIHandleBlock`. The old argument handles
  remain reachable because `JavaCallWrapper::oops_do()` scans the saved
  previous handle block. Current NJX code decodes arguments before installing
  the synthetic wrapper but then pushes each raw object argument into the fresh
  active Java-call block before writing `call_params`, which makes the new
  block top nonzero and duplicates argument roots in a block HotSpot expects to
  start empty for Java-call locals. Corrected generic scope: keep decoding
  incoming object handles before installing the synthetic block, keep applying
  `neko_gc_root_publish_oop` before writing raw `call_params`, but stop
  re-rooting object arguments into the fresh NJX Java-call handle block. The
  saved old handles must remain in wrapper `_handles`, the fresh block remains
  `JavaThread::_active_handles`, and missing object handle resolution still
  hard-aborts.
- Follow-up evidence 2026-05-22: removing the redundant NJX object-argument
  re-root into the fresh Java-call handle block passed focused
  `CCodeGeneratorTest` / `OpcodeTranslatorUnitTest` validation and the fresh
  full default `NativeObfuscationPerfTest --no-parallel` gate. Fresh medians
  were TEST Calc `4 ms`, obfusjack Platform `46 ms`, Virtual `38 ms`, Seq
  `10 ms`, Parallel/VThreads `1 ms`. Strict Shenandoah TEST-native exited `0`
  with empty stderr and `Calc: 4ms`, but strict Shenandoah obfusjack-native
  still aborted during matrix execution with `Before Mark, Roots; Should not be
  forwarded`; the verifier reported outside-heap root slot
  `0x00007f6d6032c608` containing forwarded `java.lang.Class` oop
  `0x00000005379eb6c0` with forwardee `0x00000007fea33e80`. The HotSpot
  JavaCallArguments parity change is retained as generic shape correction but
  is not sufficient for T4.8d/T4.15. Next diagnostic must compare this exact
  retained shape under `NEKO_ROOT_SLOT_DEBUG=1` before a further runtime change.
- Diagnostic evidence 2026-05-22: the retained no-argument-reroot
  `NEKO_ROOT_SLOT_DEBUG=1` run reproduced the strict Shenandoah verifier abort
  at root slot `0x00007f4764327708` with forwarded `java.lang.Class` oop
  `0x00000005375e4a88` and forwardee `0x00000007fea03880`. The exact slot is
  still unlogged, but the same arena shows native `direct_handle_overflow`
  origin `9` at block `0x7f4764324248`, followed by VM-created local storage
  before later native overflow at `0x7f476432e298`. This proves the remaining
  producer is behind the native-prepended overflow head, not NJX argument
  re-rooting. OpenJDK 21 `JNIHandleBlock::allocate_handle` appends through the
  active head's `_last` and `_next` chain; current `neko_handle_push` /
  `neko_direct_oop_to_handle_origin` overflow instead allocate a new block,
  set its `_next` to the previous active block, and publish it as
  `JavaThread::_active_handles`. Corrected generic scope: make native overflow
  append through the current active head's `_last` chain, and make
  `neko_handle_save/restore` tail-aware by recording the saved tail block and
  tail top so scoped restore can trim blocks appended inside the window without
  mutating older outer chains.
- Follow-up diagnostic evidence 2026-05-23: the tail-aware append shape and
  subsequent global-ref slot diagnostic passed focused codegen/translator
  validation and the full default `NativeObfuscationPerfTest --rerun-tasks
  --no-parallel` gate, but strict Shenandoah obfusjack still aborted with
  `Before Mark, Roots; Should not be forwarded`. The latest
  `NEKO_ROOT_SLOT_DEBUG=1` / global-ref run reported verifier root slot
  `0x00007f886832caa8`, raw oop `0x00000005379097a0`, and forwardee
  `0x00000007fe9d3180`. Exact grep found no `neko-global-ref` or native root
  publisher line for that slot; the nearest logged native slot was
  `handle_push_tail slot=0x7f8868329a00` in block `0x7f88683299e8`. The
  failing slot is `12480` bytes after that block; with the fresh runtime
  `sizeof_JNIHandleBlock=296`, this is scoped block index `42` plus handle-slot
  offset `48`. `hs_err` thread events place the same address range next to
  short-lived JavaThread allocations that had exited before the verifier crash.
  This proves the global-ref cache is not the exact producer and gives a
  concrete next diagnostic invariant: classify whether the verifier slot is
  inside a native-owned scoped `JNIHandleBlock` slab whose later blocks can be
  populated by VM local-handle allocation during Java upcalls.
- Corrected diagnostic scope 2026-05-23: add a low-overhead, env-gated scoped
  `JNIHandleBlock` slab range trace and cache root-debug env checks once per
  generated library. The range trace must log only slab start/end/block-size
  metadata on slab allocation, not every slot, and must be inactive unless
  `NEKO_ROOT_SLAB_DEBUG` is set. Existing root/HandleArea diagnostics must stop
  paying repeated hot-path `getenv` calls when their env vars are unset. The
  next completion evidence is a fresh strict Shenandoah obfusjack run whose
  `hs_err` verifier slot is either inside a logged native slab range or proven
  outside the native slab, global-ref, JNIHandleBlock-publisher, and current
  thread `HandleArea` diagnostics before any runtime root fix is attempted.
- Diagnostic completion evidence 2026-05-23: focused
  `CCodeGeneratorTest`/`OpcodeTranslatorUnitTest` validation and a fresh full
  `NativeObfuscationPerfTest --rerun-tasks --no-parallel` regeneration passed
  after the env-cache/slab diagnostic. Fresh medians were TEST Calc `4 ms`,
  obfusjack Platform `39 ms`, Virtual `36 ms`, Seq `10 ms`, and
  Parallel/VThreads `1 ms`; Platform recovered but Virtual remains one
  millisecond above the strict `<=35 ms` gate and Calc remains above the
  best-known `2-3 ms` target. Strict Shenandoah obfusjack with
  `NEKO_ROOT_SLAB_DEBUG=1` reproduced the verifier abort. `hs_err_slab_4.log`
  reports root slot `0x00007f57c8339298` containing forwarded
  `java.lang.Class` oop `0x00000005371dd728` with forwardee
  `0x00000007fe9baf80`. The slab log contains
  `scope=0x7f57cfdfd850 slab=0x7f57c8335eb0 start=0x7f57c8335ec8
  end=0x7f57c833a8c8 block_size=296 blocks=64 index=28`; the failing slot is
  inside that native-owned scoped `JNIHandleBlock` slab at offset `13264`,
  block index `44`, remainder `240`. This proves the remaining forwarded root
  is in a native scoped JNIHandleBlock chain visible to HotSpot root scanning,
  not in a global ref, generated library mapping, current-thread HandleArea, or
  unlogged exact publisher.
- Corrected implementation scope 2026-05-23: before a generated NJX Java upcall
  allows HotSpot `JavaCallWrapper` to scan the saved old active-handle chain,
  refresh the current active `JNIHandleBlock` chain under Shenandoah by decoding
  marked-forwarding oops in slots below each block top and writing the forwardee
  back to the same root slot. Traverse the HotSpot-compatible `_last`/`_next`
  chain only through active blocks, hard-abort on malformed block metadata or
  missing layout, and do not add JNI/JVMTI/fallback paths. Required evidence:
  generated C must show the refresh happens before `neko_njx_install_java_handles`
  and before `call_stub`; focused codegen/translator validation must pass; a
  fresh strict Shenandoah obfusjack run must no longer report a forwarded root
  from the native scoped slab, or must provide a new exact invariant before any
  further runtime change. Performance validation must preserve the already
  recorded best-known Calc `2-3 ms` target and Virtual `<=35 ms` gate before
  final acceptance.
- Follow-up evidence 2026-05-23: the first refresh implementation rejected a
  valid `JNIHandleBlock::_top` offset `0` and hard-aborted before translated
  output; after correcting the offset guard, focused validation and fresh full
  regeneration passed. The strict Shenandoah run still aborted during matrix
  execution with a forwarded `java.lang.Class` root. `NEKO_ROOT_SLAB_DEBUG=1`
  after the full-chain refresh still matched the verifier slot
  `0x00007fb3b03327e8` to a native scoped slab
  `start=0x7fb3b032f4f8 end=0x7fb3b0333ef8`, offset `13760`, block index
  `46`, remainder `144`. Because this slot is allocated after the pre-call
  refresh inside the same outer NJX handle scope, the remaining invariant is
  allocator lifetime: `g_neko_current_handle_scope` stays set to `__njx_hsave`
  while `call_stub` executes Java and can reenter translated native code, so
  reentrant native handle allocations are owned by the outer NJX scope and can
  become visible to HotSpot root scanning after Shenandoah moves them.
  Corrected scope: detach the TLS scoped JNIHandleBlock allocator to the
  previous scope for the duration of `call_stub`, then restore the NJX scope
  immediately after return before normal `neko_njx_restore_java_handles` and
  `neko_handle_restore`. Nested translated native calls must create and restore
  their own scopes; no JNI/JVMTI/fallback or collector skipping is allowed.
- Rejected/follow-up evidence 2026-05-23: detaching the TLS scope around
  `call_stub` passed focused validation and fresh full regeneration, but strict
  Shenandoah obfusjack still aborted with a forwarded `java.lang.Class` root.
  The latest slab diagnostic moved the failing slot to a different active scope
  while still matching scoped slab storage:
  `slot=0x00007f3c14317da8`, slab
  `start=0x7f3c14316a78 end=0x7f3c1431b478`, offset `4928`, block index
  `16`, remainder `192` for that slab range. This proves the remaining
  invariant is not only the outer NJX scope: VM-visible `JNIHandleBlock`
  overflow blocks allocated from batch scoped slabs can outlive or be exposed
  independently of the native scope owner that frees/reuses the slab memory.
  Corrected scope: remove scoped batch-slab allocation for VM-visible
  `JNIHandleBlock` overflow blocks and allocate each block through the existing
  individual recycled/calloc path with mandatory clear-before-recycle/free.
  Keep diagnostics env-gated, preserve HotSpot `_last`/`_next` chain shape, and
  do not introduce JNI/JVMTI/fallback paths.
- Follow-up evidence 2026-05-23: after removing scoped slab allocation,
  focused validation and fresh full regeneration passed, but strict Shenandoah
  obfusjack still aborted with a forwarded `java.lang.Class` root in
  individually allocated C-heap memory near short-lived worker JavaThread
  allocations. Exact `NEKO_ROOT_SLOT_DEBUG=1` did not log the verifier slot
  `0x00007f585c3154f8`; nearby same-arena publisher lines show repeated
  `handle_push_tail`/`direct_handle_tail` writes of Class roots into
  `JNIHandleBlock` storage, including already-forwarded Class mirrors and
  later raw Class mirrors. This proves the remaining issue is not the batch
  allocator, but stale Class roots in handle blocks that can be inactive during
  a Shenandoah update phase and later become visible. Corrected scope: refresh
  the active `JNIHandleBlock` chain at generic `neko_handle_save` entry under
  Shenandoah, before recording the saved top/tail state, so every translated
  native method/window heals stale roots before saving or exposing the chain.
- Rejected evidence 2026-05-23: adding the generic `neko_handle_save` entry
  refresh passed focused validation, but the fresh full performance gate failed
  by timing out `obfusjack-native.jar` after `PT2M`. The broad refresh is
  rejected and removed. The next root fix must be narrower than every
  translated handle-scope entry, or must identify the exact active-chain owner
  at the failing safepoint before adding another refresh point.
- Diagnostic scope 2026-05-23: with scoped slabs removed, extend the existing
  env-gated range diagnostic to individual recycled/calloc `JNIHandleBlock`
  allocations. The trace must log block start/end, scope pointer, and block
  size only under `NEKO_ROOT_SLAB_DEBUG`, so the next strict Shenandoah
  verifier slot can be matched to the exact individual block owner before
  another runtime fix is attempted.
- Rejected evidence 2026-05-23: the individual-block diagnostic artifact
  failed the full performance gate by timing out `obfusjack-native.jar` after
  `PT2M`. The strict Shenandoah diagnostic still reproduced the verifier abort,
  but the failing slot `0x00007f7a20301a08` did not match any logged native
  individual block range; the first nearby logged native block was
  `0x7f7a20302a20..0x7f7a20302b48`. This rejects the no-slab allocator change
  as both insufficient and too slow. Restore scoped batch allocation and pursue
  the remaining root as a VM-created local/root owner issue rather than a native
  block allocation issue.
- Fresh evidence 2026-05-23: after restoring scoped allocation and removing the
  broad refresh/block-range probes, a clean `NativeObfuscationPerfTest` focused
  baseline regenerated TEST `build/neko-native-work/run-45681875164044` and
  obfusjack `build/neko-native-work/run-45689393440171`. The default medians
  were TEST Calc `4 ms`, obfusjack Platform `42 ms`, Virtual `36 ms`, Seq
  `10 ms`, Parallel/VThreads `1 ms`; the user-stated best Calc `2-3 ms` is not
  restored and strict Shenandoah remains open. A fresh strict Shenandoah run
  with `NEKO_ROOT_SLAB_DEBUG=1` failed at verifier root slot
  `0x00007fd2b832c278`, raw `0x00000005375daa70`, forwardee
  `0x00000007fe9a2c80`. The slot is inside scoped slab
  `start=0x7fd2b83291a8 end=0x7fd2b832dba8`, offset `12496`, block index
  `42`, remainder `64`. A second run with `NEKO_ROOT_SLOT_DEBUG=1` failed at
  slot `0x00007fb244326b98` in scoped slab
  `start=0x7fb244322c38 end=0x7fb244327638`, offset `16224`, block index
  `54`, remainder `240`; the trace shows this long-lived scope already held
  repeated Class roots before the later microbench/matrix safepoint. The
  invariant is therefore that roots in the saved native chain can become
  forwarded during an NJX Java upcall and then survive into a later Shenandoah
  mark cycle. Corrected scope: after each generated NJX `call_stub` returns and
  before restoring the temporary Java handle block, refresh the saved native
  `JNIHandleBlock` chain under Shenandoah by decoding marked-forwarding slots.
  This is narrower than the rejected `neko_handle_save` refresh, preserves
  HotSpot chain shape, and adds no JNI/JVMTI/fallback or collector skip.
- Rejected evidence 2026-05-23: adding that post-`call_stub` saved-chain
  refresh passed focused `CCodeGeneratorTest` / `OpcodeTranslatorUnitTest`, but
  the fresh full default `NativeObfuscationPerfTest` gate failed by timing out
  `obfusjack-native.jar` after `PT2M` on the obfusjack baseline run. The first
  obfusjack run completed green (`Platform 40 ms`, `Virtual 31 ms`, `Seq
  10 ms`), but the gate as a whole exceeded the timeout. The post-call refresh
  is therefore too broad/expensive for hot NJX paths and is removed. The next
  fix must avoid whole-chain scans on every NJX call and instead reduce
  long-lived Class-root publication or refresh only at a proven low-frequency
  owner.
- Implementation scope 2026-05-23: shorten local-root lifetime in the
  low-frequency MethodType/bootstrap/ConstantDynamic support surface instead of
  scanning hot NJX chains. Source evidence: `neko_lookup_for_class`,
  `neko_lookup_for_jclass`, `neko_method_type_from_descriptor`,
  `neko_bootstrap_parameter_array`, `neko_invoke_bootstrap`, and
  `neko_resolve_constant_dynamic` publish Class mirrors, method-name strings,
  MethodType/parameter arrays, reflective Method objects, Lookup receivers, and
  bootstrap results into the caller's active translated-method handle scope.
  Those temporaries are used within the helper operation and only the helper's
  return value needs to escape. Generic fix: add helper-local
  `neko_handle_window_begin/end` windows and re-push only the returned raw oop
  after `neko_prepare_return_oop`; null bootstrap/condy results remain null.
  This preserves ABI/JVM semantics, avoids JNI/JVMTI/fallback, and avoids the
  rejected per-NJX whole-chain scan.
- Rejected evidence 2026-05-23: the helper-local window implementation passed
  focused generator/translator tests, but the fresh full default performance
  gate timed out on obfusjack. A direct run of the same generated
  `obfusjack-native.jar` printed `=== All tests completed ===` with Platform
  `44 ms`, Virtual `35 ms`, Seq `11 ms`, then failed to terminate until
  `timeout 120s` killed the process with exit `124`. The helper-window shape is
  therefore rejected and removed because it introduces a JVM lifecycle/handle
  restoration regression even though application output completes.
- Implementation scope 2026-05-23: reduce long-lived `java.lang.Class` local
  roots on the generated static-field path. Source evidence: every
  `neko_static_field_ref_class` calls `neko_ensure_class_initialized_once` and
  then uses the returned class only as the same static field holder; the once
  wrapper currently calls `neko_ensure_class_initialized`, which performs a
  windowed `JVM_FindClassFromClass(init=true)` and then pushes a new local
  Class root into the caller scope. The initialization side effect and init-slot
  publication are required, but the caller-visible new Class local is not.
  Generic fix: after successful initialization, set the slot and return the
  original bound class handle `cls`; do not re-publish the initialized local in
  the once path. Also keep `NEKO_CLASS_INIT_DEBUG` behind the initialized-slot
  fast return so the hot initialized path performs no env lookup. Direct callers
  of `neko_ensure_class_initialized` keep the existing API.
- Fresh evidence 2026-05-23T04:40:37Z: after the static-field once change,
  focused validation and the default full native perf gate passed. TEST Calc
  returned to the user-stated best range with runs `3/2/3/3/2 ms` (median `3`),
  obfusjack reported Platform `44/40/43/41/42 ms` (median `42`), Virtual
  `32/36/31/31/30 ms` (median `31`), and Seq `11/11/10/10/12 ms` (median
  `11`). Strict Shenandoah still aborts in matrix multiply with a forwarded
  `java.lang.Class` root in scoped `JNIHandleBlock` slab storage. The current
  `NEKO_ROOT_SLOT_DEBUG=1` run reports verifier slot `0x00007f78fc3382b8`,
  raw `0x00000005379dc2c8`, forwardee `0x00000007fea33e80`, inside slab
  `start=0x7f78fc335568 end=0x7f78fc339f68`, offset `11600`, block index
  `39`, remainder `56`. A second `NEKO_ROOT_SCAN_DEBUG=1` run reproduced the
  same invariant at slot `0x00007fcb44335908`, raw `0x00000005378f4cf8`,
  forwardee `0x00000007fe9aac80`. Source evidence for the next narrow producer:
  `neko_unsafe_instance_handle` materializes `Unsafe.class` through
  `neko_klass_java_mirror_handle` only to read the static `theUnsafe` field,
  but the `java.lang.Class` mirror handle then remains in the caller's active
  translated scope across later NJX allocation calls. Generic fix: bracket only
  that helper-local mirror/read in a handle window, resolve the returned
  `Unsafe` object to a raw oop with `neko_prepare_return_oop`, close the window,
  and push a caller-owned handle for the returned `Unsafe` object. This removes
  an unnecessary long-lived Class local without changing object allocation ABI,
  JNI/JVMTI usage, fallback behavior, or collector selection.
- Fresh evidence 2026-05-23: after the Unsafe helper-local window change,
  focused generator/translator validation and fresh full default regeneration
  passed. The generated performance baseline preserved the user-stated best
  Calc range at median `3 ms`, with obfusjack medians Platform `40 ms`,
  Virtual `32 ms`, Seq `10 ms`, and Parallel/VThreads `1 ms`. Strict
  Shenandoah obfusjack still aborts in matrix multiply with a forwarded
  `java.lang.Class` root. `NEKO_ROOT_SLOT_DEBUG=1` reports verifier slot
  `0x00007f645c326298`, raw `0x0000000527074380`, forwardee
  `0x00000007ffc03e78`; the exact slot is not emitted by native root-slot
  logging, and a temporary `NEKO_RETURN_OOP_DEBUG=1` probe later reproduced the
  same invariant at slot `0x00007f1fa0323b98`, raw `0x00000005374f6918`,
  forwardee `0x00000007fe992980`, without any `neko_prepare_return_oop` line
  for that stale raw value. Read-only subagent audit identifies the narrowest
  remaining producer as the VM lookup branch in
  `neko_resolve_class_mirror_with_env`: `JVM_FindClassFromBootLoader` /
  `JVM_FindClassFromClass` can create the returned `jclass` local directly in
  HotSpot's active handle block, bypassing native publication logging, before
  the helper decodes and re-pushes the mirror. Diagnostic scope: add only
  env-gated `NEKO_CLASS_LOCAL_DEBUG` logging around that VM call and re-push
  point, recording class name, returned local slot, raw oop, active handle block
  and top before/after. The diagnostic must not alter runtime behavior and must
  be removed or converted into a proven generic fix before a checkpoint commit.
- Rejected evidence 2026-05-23: the class-local diagnostic artifact passed
  focused validation and fresh full default regeneration, but the diagnostic
  artifact regressed Virtual median to `36 ms`, so it cannot be retained.
  Strict Shenandoah reproduced the verifier abort with slot
  `0x00007f7f1031bc38`, raw `0x00000005378fe7d0`, forwardee
  `0x00000007fe9a2b80`. Neither the exact slot nor raw appeared in
  `NEKO_CLASS_LOCAL_DEBUG`; nearby slab diagnostics place the slot inside
  scoped slab `start=0x7f7f10318ae8 end=0x7f7f1031d4e8`, offset `12624`,
  block index `42`, remainder `192`. This rejects the exact VM-result branch
  of `neko_resolve_class_mirror_with_env` as the current root source. The
  temporary class-local diagnostic is removed; the next substep must identify a
  different low-frequency producer/owner for unlogged Class roots in the same
  scoped handle slab.
- Implementation scope 2026-05-23: shorten the raw-String managed allocation
  fallback's `Unsafe.allocateInstance(Class)` root lifetime. Source evidence:
  the latest strict Shenandoah class-local diagnostic rejects the exact
  `neko_resolve_class_mirror_with_env` VM-result slot, while nearby native
  root-slot logs show the failing slab populated by static-object roots
  (`NEKO_HANDLE_ORIGIN_STATIC_OBJECT_FIELD`) and NJX object returns
  (`NEKO_HANDLE_ORIGIN_NJX_RETURN`) immediately around the raw String fallback.
  The source owner is `neko_build_raw_string_graph_store_local`: when direct
  String TLAB allocation fails, it resolves `java/lang/String`, reads the
  scoped `Unsafe.theUnsafe` receiver, passes the String `Class` local through
  generated `neko_njx_V_L_L`, and only needs the allocated String object to
  escape. Generic fix: bracket that managed-allocation branch with a helper
  handle window, decode the NJX result with `neko_prepare_return_oop`, close the
  window, and publish only the allocated String object back to the caller's
  scope. This does not special-case a benchmark or class, does not skip a
  collector, and does not add JNI/JVMTI/fallback behavior. Completion evidence
  must include focused codegen/translator validation, fresh default full native
  performance regeneration preserving Calc `2-3 ms` and obfusjack thresholds,
  and a fresh strict Shenandoah obfusjack run proving the forwarded Class-root
  verifier abort is gone or providing a new exact producer.
- Implementation scope 2026-05-23: shorten the helper-local Class argument
  lifetime in the generic raw String managed-allocation fallback. Evidence:
  the latest strict Shenandoah verifier slot is inside the same scoped slab
  that immediately receives `NEKO_HANDLE_ORIGIN_STATIC_OBJECT_FIELD` and
  `NEKO_HANDLE_ORIGIN_NJX_RETURN` roots during the raw String fallback; source
  shows `neko_raw_string_graph` resolves `java/lang/String`, passes that
  `jclass` as the sole object argument to `Unsafe.allocateInstance(Class)`,
  and then keeps the Class mirror and Unsafe receiver in the enclosing
  translated-method handle scope even though only the newly allocated String
  object needs to escape. Generic fix: wrap only that managed allocation
  subpath in a handle window, prepare the returned String oop before closing
  the window, close the window to pop `String.class`/Unsafe temporaries, then
  push a caller-owned handle for the returned String object before storing its
  fields. This must not change raw-TLAB allocation, byte-array rooting, NJX ABI
  shape, JNI/JVMTI usage, fallback behavior, or collector selection.
- Follow-up evidence 2026-05-23: the raw String managed-allocation window
  passed focused validation and fresh full default regeneration while
  preserving performance: TEST Calc `3/2/2/3/2 ms`, obfusjack Platform median
  `41 ms`, Virtual median `32 ms`, Seq median `10 ms`. Strict Shenandoah still
  aborts in matrix multiply, and the debug repro reports verifier slot
  `0x00007fdf4c32b838`, raw `0x00000005379e6318`, forwardee
  `0x00000007fea13a80` inside scoped slab
  `start=0x7fdf4c3289a8 end=0x7fdf4c32d3a8`, offset `11920`, block index
  `40`, remainder `80`. The root-slot trace again reaches the same active
  slab and then hits the diagnostic cap before the exact slot, so reducing one
  producer is not sufficient. Corrected generic scope: refresh the active
  `JNIHandleBlock` chain only when a translated thread observes a real
  safepoint request in `neko_safepoint_poll_thread`, before it waits in
  `_thread_in_native_trans`. This targets the verifier invariant at the GC
  boundary, avoids the rejected per-handle-save and per-NJX whole-chain scans,
  and must remain Shenandoah-only with hard abort on missing handle layout.
- Rejected evidence 2026-05-23: the safepoint-request refresh passed focused
  validation and fresh full default regeneration while preserving performance:
  TEST Calc `3/3/2/3/3 ms`, obfusjack Platform median `43 ms`, Virtual median
  `34 ms`, Seq median `10 ms`, Parallel median `1 ms`, VThreads median `1 ms`.
  Strict Shenandoah still aborted in matrix multiply with verifier slot
  `0x00007f65e834fe28`, raw `0x00000005374f3020`, forwardee
  `0x00000007fe9eb480`. A follow-up `NEKO_ROOT_SCAN_DEBUG=1` run failed again
  at slot `0x00007f3c58346968`, raw `0x00000005379080e0`, forwardee
  `0x00000007fe9aac80`, and emitted no `safepoint_poll_request`,
  `safepoint_poll_entry`, or root-scan lines. This rejects
  `neko_safepoint_poll_thread` as the current failing boundary; the refresh is
  removed before the next diagnostic.
- Diagnostic scope 2026-05-23: identify the active scoped handle owner for the
  remaining forwarded Class root. Previous strict runs prove the verifier slot
  is inside a scoped JNIHandleBlock slab, but the native root-slot diagnostic
  reaches the same active slab and hits the cap before the exact slot, and the
  class-local VM-result diagnostic did not match the failing slot/raw value.
  Add a temporary diagnostic-only `scope_site` tag to `neko_handle_save_t`, set
  it from generated translated method frames and from handle-window callers,
  and include it in `NEKO_ROOT_SLAB_DEBUG` slab-range output. Validation is a
  focused codegen/translator regeneration followed by a fresh strict
  Shenandoah obfusjack run with `NEKO_ROOT_SLAB_DEBUG=1` and
  `NEKO_ROOT_SLOT_DEBUG=1`. Completion criteria: the diagnostic identifies the
  concrete owner function/scope for the failing slab, or the run passes; the
  temporary tag must be removed or converted into a proven generic fix before a
  checkpoint commit.
- Diagnostic completion 2026-05-23: focused codegen/translator validation
  passed with the `scope_site` tag. The full default diagnostic artifact timed
  out in obfusjack after `PT2M`, so the tag is diagnostic-only and cannot be
  retained. The strict diagnostic run failed at verifier slot
  `0x00007f6904323948`, raw `0x00000005375d5e20`, forwardee
  `0x00000007fe9eb580`; the slot is inside slab
  `0x7f6904320898..0x7f6904325298`, block index `42`, owned by
  `site=neko_native_impl_47_frame`. The generated manifest maps
  `neko_native_impl_47` to `org/example/Main.demoRandomApi()V`. This completes
  the owner diagnostic and proves the remaining root is in a later block of a
  translated method-frame handle chain, not in the rejected class-local
  resolver branch.
- Implementation scope 2026-05-23: refresh the full verifier-visible
  JNIHandleBlock chain for Shenandoah. Source evidence: current
  `neko_refresh_jnih_chain_for_shenandoah` stops at cached `_last`, while the
  env-gated debug scanner also truncates on `top < capacity`. The strict owner
  diagnostic proves the verifier-visible stale Class root sits in a later
  `_next` block of a method-frame chain. Generic fix: remove the temporary
  `scope_site` tag and make the Shenandoah refresh traverse every
  `_next`-reachable block up to the hard chain cap; align the diagnostic scanner
  to the same full-chain traversal. This changes refresh coverage only, does
  not add JNI/JVMTI/fallback/collector skipping, and keeps missing/malformed
  chain layout as a hard abort. Completion evidence must include focused
  codegen/translator validation, fresh full default performance preserving Calc
  `2-3 ms`, and strict Shenandoah obfusjack.
- Follow-up evidence 2026-05-23: the full-chain refresh passed focused
  validation and fresh full default performance: TEST Calc `2/3/3/3/2 ms`,
  obfusjack medians Platform `41 ms`, Virtual `32 ms`, Seq `11 ms`,
  Parallel/VThreads `1 ms`. Strict Shenandoah still aborted at verifier slot
  `0x00007fce3c328f28`, raw `0x0000000526c75ee8`, forwardee
  `0x00000007ffc03e78`; a `NEKO_ROOT_SCAN_DEBUG=1` strict run aborted at slot
  `0x00007f15bc32e148`, raw `0x00000005370ecc80`, forwardee
  `0x00000007fe99aa80`, and emitted no root-scan lines before the verifier
  failure. This proves the remaining stale Class mirror is exposed at a
  boundary not covered by the NJX pre-call refresh/debug scans. The full-chain
  traversal remains a coverage correction but is not sufficient alone.
- Implementation scope 2026-05-23: avoid method-frame local publication for
  Klass mirror handles under moving collectors. Source evidence:
  `neko_klass_java_mirror_handle` is the generic publisher for Class
  LDC/current-owner/icache exact mirrors; it loads `Klass::_java_mirror`, then
  pushes a duplicate local handle into the current translated method frame.
  The latest strict failures are stale `java.lang.Class` roots in translated
  method-frame slabs. Generic fix: for moving collectors, load-barrier the
  mirror oop through the existing `Klass::_java_mirror` OopHandle slot and
  return that VM-owned slot as the generated `jobject`/`jclass` operand instead
  of pushing a duplicate local into the method frame. The VM mirror OopHandle is
  the owning root; missing mirror offset/slot remains a hard abort or NULL as
  before. No JNI/JVMTI/fallback/collector skip is introduced. Completion
  evidence must include focused validation, fresh full default performance
  preserving Calc `2-3 ms`, and strict Shenandoah obfusjack.
- Rejected evidence 2026-05-23: the direct Klass mirror OopHandle operand
  change passed focused validation and full default performance with TEST Calc
  `3/3/2/3/2 ms`, obfusjack medians Platform `41 ms`, Virtual `35 ms`, Seq
  `11 ms`, Parallel/VThreads `1 ms`, but strict Shenandoah still aborted at
  verifier slot `0x00007f4df432cbc8`, raw `0x00000005378f2118`, forwardee
  `0x00000007fea23b80`. The attempted OopHandle operand change is removed
  because it does not resolve the failing invariant.
- Implementation scope 2026-05-23: clear the synthetic JavaCallWrapper
  saved-handle pointer after `call_stub` returns. Source evidence: generated
  NJX stores `__njx_old_handles` into the stack-local JavaCallWrapper mirror at
  `__jcw_buf + 8` before `call_stub` and currently leaves that pointer live
  while `neko_njx_restore_java_handles`, frame-anchor restoration, and
  `neko_handle_restore` can trim/free/reuse scoped JNIHandleBlock slabs. The
  strict failures continue to report stale Class roots in outside-heap
  JNIHandleBlock-like memory, and `NEKO_ROOT_SCAN_DEBUG=1` emitted no
  active/old-handle scan lines before the verifier failure, leaving the
  synthetic wrapper saved-handle slot as the unclosed root exposure. Generic
  fix: write NULL to the synthetic wrapper `_handles` slot immediately after
  `neko_call_stub_guarded` returns, before any handle restoration or slab
  teardown. This changes only synthetic wrapper teardown lifetime and adds no
  JNI/JVMTI/fallback/collector skip. Completion evidence must include focused
  validation, fresh full default performance preserving Calc `2-3 ms`, strict
  Shenandoah obfusjack, and generated-C inspection showing the clear precedes
  handle restore.
- Rejected evidence 2026-05-23: the JavaCallWrapper `_handles` clear passed
  focused codegen/translator validation and generated-C inspection proved the
  clear occurs immediately after `neko_call_stub_guarded(&__stub_args)` and
  before `g_neko_current_handle_scope` restore,
  `neko_njx_restore_java_handles`, frame-anchor restoration, and
  `neko_handle_restore(&__njx_hsave)`. The partial fresh default performance
  run preserved TEST Calc in the best-known range (`3/3/2/3/2 ms`) before the
  obfusjack baseline timed out on a later iteration. Strict Shenandoah still
  aborted with a forwarded `java.lang.Class` root; `NEKO_ROOT_SLAB_DEBUG=1`
  matched the verifier slot `0x00007f956832c668` to scoped slab
  `start=0x7f9568329308 end=0x7f956832dd08`, block index `44`, slot offset
  `128`. A follow-up `NEKO_ROOT_SCAN_DEBUG=1` run again emitted no
  active-chain scan lines before abort and matched slot `0x00007f836833a098`
  to scoped slab `start=0x7f8368336c18 end=0x7f836833b618`, block index `45`,
  slot offset `120`. This rejects the wrapper-clear lifetime as sufficient;
  the remaining invariant is an exact stale Class publisher inside the
  translated method-frame JNIHandleBlock chain.
- Diagnostic scope 2026-05-23: capture the exact publisher/origin for the
  remaining stale `java.lang.Class` root. Source evidence: the existing
  `NEKO_ROOT_SLOT_DEBUG` stream reaches the same active slab but hits its
  hard-coded diagnostic cap before the verifier slot, while previous
  `scope_site` diagnostics already mapped the owner to
  `org/example/Main.demoRandomApi()V`. Temporarily raise only the env-gated
  root-slot diagnostic cap high enough for a strict Shenandoah obfusjack run,
  preserving default behavior when the env var is absent. Validation is
  focused codegen/translator regeneration followed by strict Shenandoah
  obfusjack with `NEKO_ROOT_SLOT_DEBUG=1` and `NEKO_ROOT_SLAB_DEBUG=1`.
  Completion criteria: the exact verifier slot or raw forwarded Class oop must
  be tied to a generic publisher/origin, or the diagnostic must explain why the
  slot is VM-created and unlogged; the raised cap must be removed before any
  checkpoint commit unless converted into an accepted env-only diagnostic.
- Diagnostic completion and implementation scope 2026-05-23: the raised
  root-slot diagnostic cap produced over two million env-gated publisher lines.
  Strict Shenandoah still aborted at verifier slot `0x00007f0c7031e2b0` with
  stale raw Class oop `0x0000000527026790` and forwardee
  `0x00000007ffc03ee8`. The exact verifier slot and stale raw oop do not appear
  in native publisher logs, but the forwardee appears later as a normal
  `handle_push_tail` root (`slot=0x7f0c70329380 raw=0x7ffc03ee8`). The verifier
  slot matches historical scoped slab ranges, proving the bad slot is not a
  current native publisher store and is consistent with VM-created locals being
  appended through stale JNIHandleBlock chain metadata. Generic fix: repair the
  active JNIHandleBlock head `_last` field to the actually `_next`-reachable
  tail at handle-scope save/restore and NJX restore boundaries. This traverses
  only block metadata, does not scan or mutate object root slots, and hard
  aborts on malformed chains; it must not add JNI/JVMTI/fallback/collector
  skipping. Completion evidence must include focused validation, generated-C
  inspection, fresh default performance preserving Calc `2-3 ms`, and strict
  Shenandoah obfusjack.
- Rejected evidence 2026-05-23: the `_last` metadata repair passed focused
  codegen/translator validation, but the fresh default performance gate timed
  out on the first obfusjack baseline run after the application had printed
  completion. That run reported Platform `34 ms`, Virtual `321 ms`, Seq
  `11 ms`, Parallel/VThreads `1 ms`, while TEST Calc stayed in range
  (`3/3/3/2/3 ms`). The repair changes VM local-handle lifecycle enough to
  reintroduce the completed-output/non-terminating obfusjack failure class, so
  the `_last` repair and temporary high diagnostic cap are removed before the
  next substep.
- Implementation scope 2026-05-23: refresh the saved translated method-frame
  JNIHandleBlock chain after each NJX Java upcall returns under Shenandoah.
  Source evidence: current generated NJX refreshes `__njx_old_handles` before
  installing the Java-call active handle block, then stores the same old handle
  chain into the synthetic `JavaCallWrapper` `_handles` field for
  `call_stub`; after `call_stub` returns it restores TLS/active handles without
  refreshing that old chain again. The strict failures are stale
  `java.lang.Class` roots in the saved method-frame slab, the exact stale slot
  is absent from native publisher logs, and prior root-scan diagnostics emitted
  no active-chain scan before the verifier abort. Generic fix: add a
  Shenandoah-only `neko_refresh_jnih_chain_for_shenandoah(__njx_old_handles,
  "njx_old_refresh_after_call_*")` immediately after `call_stub` returns and
  before `neko_njx_restore_java_handles` / method-scope restore. This does not
  mutate metadata, does not scan under non-Shenandoah collectors, adds no
  JNI/JVMTI/fallback/collector skip, and avoids the rejected `_last` repair and
  broad bootstrap-windowing shapes. Completion evidence must include focused
  codegen/translator validation, generated-C inspection showing the post-call
  refresh before handle restoration, fresh default performance preserving Calc
  `2-3 ms`, and strict Shenandoah obfusjack.
- Rejected evidence 2026-05-23: the NJX post-call old-handle refresh passed
  focused codegen/translator validation. A fresh obfusjack artifact was
  regenerated despite the JUnit XML report writer failing after execution; the
  artifact log reports `translated=93 rejected=0`, and generated
  `run-51992814092166/neko_native_support.c` shows
  `neko_refresh_jnih_chain_for_shenandoah(__njx_old_handles,
  "njx_old_refresh_after_call_*")` immediately after
  `neko_call_stub_guarded(&__stub_args)` and before TLS scope restoration,
  `neko_njx_restore_java_handles`, and `neko_handle_restore`. Strict
  Shenandoah obfusjack still aborted during matrix execution with
  `Before Mark, Roots; Should not be forwarded`; the verifier slot was
  `0x00007f9c44325e38`, stale raw Class oop `0x00000005375e1ac0`, forwardee
  `0x00000007fe9f3680`. This rejects the post-call NJX refresh as sufficient;
  the line is removed before the next substep.
- Implementation scope 2026-05-23: make inline-cache miss resolution try
  HotSpot metadata before calling VM member materialization. Source evidence:
  generated `neko_icache_dispatch` currently opens `icache_window`, publishes
  the receiver `Klass::_java_mirror` as a local handle, calls
  `neko_link_class_methods(env, exactMirror, ...)`, and only then calls
  `neko_resolve_method(receiverKlass, ...)`. `neko_link_class_methods` enters
  `JVM_GetClassDeclaredMethods` / `JVM_GetClassDeclaredConstructors`, creating
  unlogged VM locals under the same method-frame handle chain; this boundary is
  not an NJX call-stub boundary and matches the sidecar audit plus the absent
  native publisher logs. Generic fix: split the existing resolver into a
  nullable metadata-only `neko_try_resolve_method` and an aborting wrapper, then
  make `neko_icache_dispatch` call the nullable resolver first; only if it
  returns NULL should it publish the exact mirror and call
  `neko_link_class_methods`, then re-resolve with the existing aborting path.
  This avoids VM member materialization when metadata is already available,
  keeps the existing hard abort for true unresolved methods, and adds no
  JNI/JVMTI/fallback/collector skip. Completion evidence must include focused
  codegen/translator validation, generated-C inspection proving the metadata
  attempt precedes `neko_link_class_methods`, fresh default performance
  preserving Calc `2-3 ms`, and strict Shenandoah obfusjack.
- Rejected evidence 2026-05-23: the metadata-first icache miss change passed
  focused codegen/translator validation and the fresh generated artifact showed
  `void *exactMethod = neko_try_resolve_method(...)` before the conditional
  `neko_link_class_methods(...)` call. However, the direct default
  `obfusjack-native.jar` run timed out after `30s` before printing the Platform
  thread timing, with stdout stopping immediately after the microbench header.
  This is an unacceptable default runtime/completion regression before strict
  GC validation, so the nullable resolver prototype/body and icache change are
  removed before the next substep.
- Implementation scope 2026-05-23: refresh the active JNIHandleBlock chain
  immediately after VM member materialization in the inline-cache miss path.
  Source evidence: `neko_icache_dispatch` enters `neko_link_class_methods`,
  which calls `JVM_GetClassDeclaredMethods` / `JVM_GetClassDeclaredConstructors`
  inside the active translated method-frame handle chain and is not covered by
  NJX call-stub refresh points. The metadata-first attempt proved changing
  link order can regress default completion, so the order must stay intact.
  Generic fix: keep the existing exact-mirror window and
  `neko_link_class_methods` call, then call
  `neko_refresh_active_handles_for_shenandoah(thread, "icache_link_after")`
  before resolving/storing the direct target. This is a narrow Shenandoah-only
  refresh at a VM boundary, adds no JNI/JVMTI/fallback/collector skip, and does
  not change non-Shenandoah hot paths. Completion evidence must include
  focused validation, generated-C inspection showing the refresh immediately
  after `neko_link_class_methods`, fresh default obfusjack completion and Calc
  `2-3 ms`, and strict Shenandoah obfusjack.
- Rejected evidence 2026-05-23: the icache post-link active-handle refresh
  passed focused `CCodeGeneratorTest` / `OpcodeTranslatorUnitTest`, regenerated
  fresh obfusjack native artifacts with `translated=93 rejected=0`, and direct
  default obfusjack completed with Platform `42 ms`, Virtual `51 ms`, Seq
  `10 ms`, Parallel/VThreads `1 ms`. The fresh generated C showed
  `neko_refresh_active_handles_for_shenandoah(thread, "icache_link_after")`
  immediately after `neko_link_class_methods`, but strict Shenandoah still
  aborted during matrix execution with verifier slot `0x00007f8cd4330618`,
  stale raw Class oop `0x00000005378f6ca8`, and forwardee
  `0x00000007fea0b880`. This rejects icache post-link refresh as sufficient;
  the call and temporary declaration are removed before the next substep.
- Implementation scope 2026-05-23: refresh active JNIHandleBlock roots after a
  translated native safepoint wait under Shenandoah. Evidence: on the clean
  regenerated artifact `build/neko-native-work/run-52712906915051`, strict
  Shenandoah root-slot diagnostics still abort during matrix execution with
  outside-heap `java.lang.Class` roots. `NEKO_ROOT_SLOT_DEBUG=1` reports
  verifier slot `0x00007f26b83240b8`, raw `0x00000005379dfd00`, forwardee
  `0x00000007fe9aad80`; the exact slot is not emitted by native root publishers.
  `NEKO_ROOT_SLAB_DEBUG=1` reports verifier slot `0x00007f47cc328250`, raw
  `0x00000005270180a8`, forwardee `0x00000007ffc03c28`; the slot is between
  logged scoped-slab payload ranges, not inside one. `NEKO_ROOT_SCAN_DEBUG=1`
  and `NEKO_ROOT_AREA_SUMMARY=1` report verifier slot `0x00007fbd9032fd98`,
  raw `0x00000005378f7b28`, forwardee `0x00000007fe9db280`; no forwarded
  active-handle or HandleArea slot is seen at method save/restore scan points.
  Source evidence: `neko_safepoint_poll_thread` waits on HotSpot's polling word
  and only calls `neko_debug_scan_active_handles(thread, "safepoint_poll_wait")`
  when diagnostics are enabled; it does not refresh native-owned active
  `JNIHandleBlock` roots after the safepoint completes. Generic fix scope:
  after a real safepoint wait (`spins > 0`), call the existing Shenandoah-only
  active-handle refresh before returning to translated execution. Required
  evidence: focused validation, generated-C inspection showing the refresh is
  only after a nonzero safepoint wait, default obfusjack completion preserving
  the best Calc `2-3 ms` target, and strict Shenandoah obfusjack.
- Rejected evidence 2026-05-23: the safepoint post-wait active-handle refresh
  passed focused `CCodeGeneratorTest` / `OpcodeTranslatorUnitTest`, regenerated
  obfusjack with `translated=93 rejected=0`, and generated C showed
  `neko_refresh_active_handles_for_shenandoah(thread,
  "safepoint_poll_after_wait")` only under `if (spins != 0)` after the polling
  loop. Direct default obfusjack completed with Platform `43 ms`, Virtual
  `30 ms`, Seq `10 ms`, Parallel/VThreads `1 ms`. Strict Shenandoah still
  aborted during matrix execution with verifier slot `0x00007f83e02feb38`,
  stale raw Class oop `0x0000000526cc9aa0`, and forwardee
  `0x00000007ffc04370`. This rejects safepoint post-wait refresh as sufficient;
  the runtime line is removed before the next substep.
- Diagnostic scope 2026-05-23: add an env-gated NJX JavaCallWrapper/call-params
  range trace around generated `call_stub` setup. Evidence: the clean
  safepoint-rejected run's failing verifier slot is in the anonymous native
  arena that also contains JavaThread structures, while exact slots remain
  absent from native root-publisher, scoped-slab, active-handle, and derived
  HandleArea diagnostics. Generated NJX code builds a synthetic
  `JavaCallWrapper` in `__jcw_buf`, writes wrapper fields at fixed offsets
  `+0/+8/+16/+24`, passes that buffer to `call_stub`, and publishes raw object
  arguments through stack `call_params`. Required evidence: focused generation
  validation, generated-C inspection showing the trace logs `__jcw_buf`,
  `__jcw_buf + 0/+8/+16/+24`, `__jcw_anchor`, and `call_params` ranges under
  `NEKO_ROOT_SLOT_DEBUG`, then strict Shenandoah obfusjack comparing the
  verifier `slot=` address to those ranges. Completion criteria: if the slot is
  inside the logged wrapper or params range, the next implementation must use a
  generic JavaCallWrapper layout/size correction with hard abort on missing
  VMStruct data; if it is outside, this candidate is rejected and the next
  target is HotSpot-owned transition/call-stub frame roots.
- Rejected diagnostic evidence 2026-05-23: focused `CCodeGeneratorTest` /
  `OpcodeTranslatorUnitTest` passed, fresh obfusjack regeneration produced
  `build/neko-native-work/run-53519574884651`, and generated
  `neko_native_support.c` emitted `neko_debug_njx_call_stub_roots(...)` for
  every NJX dispatcher. Strict Shenandoah with `NEKO_ROOT_SLOT_DEBUG=1`
  reproduced the verifier abort with slot `0x00007fdcc83241c8`, stale raw
  Class oop `0x00000005379b7ce0`, and forwardee `0x00000007fe98a980`.
  The logged wrapper/argument ranges were stack addresses around
  `0x7fdcccffb3b0..0x7fdcccffb6c0`, while the verifier slot was in the native
  C-heap arena, was absent from `neko-njx-jcw` and native root-publisher logs,
  and was `0x178` bytes before a JavaThread allocation reported in `hs_err`.
  This rejects the synthetic JavaCallWrapper/call-params range as the remaining
  root location; the temporary trace is removed before the next substep.
- Diagnostic scope 2026-05-23: extend the existing `NEKO_ROOT_SLOT_DEBUG`
  native root-publisher trace with the owning `JavaThread*`. Evidence: the
  JavaCallWrapper diagnostic rejected stack wrapper/argument ranges, while the
  same run showed native-owned root publications in the same C-heap arena around
  `0x7fdcc8320e98..0x7fdcc8320ec8`; the verifier slot
  `0x00007fdcc83241c8` is absent from those logs and is adjacent to an exited
  JavaThread allocation (`0x00007fdcc8324340`) in `hs_err` thread events. The
  current native publisher trace records slot/block/top/origin but not the
  `JavaThread*`, so it cannot distinguish main-thread, worker-thread, and
  exited-thread handle ownership. Required evidence: focused validation,
  generated-C inspection showing `[neko-root]` includes `thread=`, and strict
  Shenandoah obfusjack with `NEKO_ROOT_SLOT_DEBUG=1` that correlates nearby
  same-arena native blocks to a concrete thread or proves the remaining slot is
  outside all native-published thread-owned roots.
- Diagnostic evidence 2026-05-23: focused validation passed, fresh obfusjack
  regeneration produced `build/neko-native-work/run-53921422067847`, and
  generated `neko_native_support.c` emitted `[neko-root] site=%s thread=%p ...`
  with thread-aware call sites. Strict Shenandoah with `NEKO_ROOT_SLOT_DEBUG=1`
  reproduced the verifier abort at slot `0x00007f1fd032c468`, stale raw Class
  oop `0x00000005379f10d8`, and forwardee `0x00000007fea13a80`. Native root
  publisher logs showed nearby generated handle storage at
  `0x7f1fd0329118..0x7f1fd0329148` owned by main `JavaThread`
  `0x7f1fd00d8650`, but did not publish the verifier slot. `hs_err` thread
  events show `JavaThread` `0x00007f1fd032c5e0` was added at `4.145`, exited at
  `4.184`, and the verifier slot is again exactly `0x178` bytes before that
  JavaThread allocation. This rejects direct native root-publisher ownership as
  the remaining root source and keeps the next diagnostic on generic
  object-carrier/root ownership across safepointing native boundaries.
- Diagnostic scope 2026-05-23: add an env-gated object-carrier/local-root
  lifetime trace around generated object stores and safepointing call
  boundaries. Source evidence: generated object locals are prepared by
  `neko_prepare_local_oop_roots`, object stores write the rooted slot but return
  `return (jobject)raw_oop`, and `PUSH_O` / `POP_O` carry untyped `jobject`
  values across later `neko_njx_*`, `neko_icache_dispatch`, safepoint poll, and
  Java-call boundaries. Required evidence: focused validation, generated-C
  inspection proving the trace is behind `NEKO_ROOT_SLOT_DEBUG`, and strict
  Shenandoah obfusjack proving whether the verifier slot maps to a Neko-owned
  local-root/JNIHandleBlock range or whether a raw object carrier crossed a
  safepoint and was later re-published stale. Completion criteria: either the
  exact producer is identified for a generic handle-backed object carrier fix,
  or this candidate is rejected with a fresh verifier slot and absence from the
  new object-carrier/local-root trace.
- Rejected diagnostic evidence 2026-05-23: the object-carrier/local-root trace
  passed focused codegen/translator validation and fresh obfusjack regeneration
  produced `build/neko-native-work/run-54388925603133`. Generated
  `neko_native_support.c` emitted the env-gated `[neko-obj-life]` helper, traced
  `PUSH_O` / `POP_O`, `local_root_prepare`, `local_root_store_return`, and
  generated NJX argument decode/parameter publication sites. Strict Shenandoah
  with `NEKO_ROOT_SLOT_DEBUG=1` reproduced the verifier abort at slot
  `0x00007f7e20323728`, stale raw Class oop `0x00000005379e5dc0`, and forwardee
  `0x00000007fe9c3080`. The exact slot, stale raw oop, and forwardee were absent
  from `[neko-root]` and `[neko-obj-life]` logs. Nearby same-arena traces showed
  generated object carriers moving handle slot `0x7f7e20320600`, but the
  verifier slot is again exactly `0x178` bytes before an exited JavaThread
  allocation (`0x00007f7e203238a0`) reported by `hs_err` thread events. This
  rejects raw generated object carriers and Neko local-root publication as the
  current root owner. Diagnostic limitation: the current readable-range helper
  caches `/proc/self/maps` once, so later C-heap allocations can be reported as
  unreadable in debug output; refreshing the map cache on misses is required
  before relying on carrier decode kind.
- Diagnostic scope 2026-05-23: add a read-only, env-gated thread-adjacent root
  window trace for the repeated `JavaThread* - 0x178` invariant. Evidence: the
  last three strict Shenandoah failures placed the verifier root outside logged
  native publisher, JavaCallWrapper/call-params, and object-carrier/local-root
  ranges while `hs_err` thread events placed the slot exactly `0x178` bytes
  before a short-lived JavaThread allocation. Scope: under `NEKO_ROOT_SLOT_DEBUG`
  or `NEKO_ROOT_SCAN_DEBUG` only, refresh `/proc/self/maps` on debug
  readable-range misses, scan a bounded readable window from `thread - 0x200`
  through `thread`, and extend the existing HotSpot HandleArea scanner to
  include `slot-thread` relation output at translated safepoint/upcall
  boundaries. Both traces must log only words whose object header is a
  Shenandoah forwarding pointer. Required evidence: focused validation,
  generated-C inspection proving the diagnostic is env-gated and read-only, and
  a strict Shenandoah obfusjack run whose verifier slot either appears in the
  thread-adjacent/HandleArea trace or remains absent. Completion criteria: a
  matching slot identifies the concrete VM-owned
  transition/thread-adjacent root lifecycle for the next generic fix; absence
  rejects the `JavaThread* - 0x178` window as the active root owner.
- Diagnostic refinement 2026-05-23: the first `NEKO_ROOT_SCAN_DEBUG=1`
  strict Shenandoah run reached obfusjack microbench output but timed out after
  120s with empty stderr and no `[neko-thread-adjacent]` / `[neko-root-area]`
  hits, so the broad scan mode is too slow for this reproducer. A same-artifact
  strict run without diagnostics still aborted quickly at slot
  `0x00007f6ebc337d28`, stale raw Class oop `0x00000005370ee6b8`, forwardee
  `0x00000007fe9a2b80`; `hs_err` thread events place a short-lived JavaThread
  allocation at `0x00007f6ebc337ed0`, exited at `0.998`, leaving the verifier
  slot in the same pre-JavaThread arena window. Refined diagnostic scope: add
  `NEKO_THREAD_ADJ_DEBUG`, a lower-overhead gate that scans only the bounded
  pre-thread window plus HotSpot HandleArea forwarded roots at translated
  transition/upcall boundaries, without enabling root publisher,
  object-carrier, or full JNIHandleBlock chain scans.
- Diagnostic correction 2026-05-23: the first `NEKO_THREAD_ADJ_DEBUG=1`
  strict Shenandoah run timed out after 120s, reached obfusjack Platform/Virtual
  microbench output, emitted empty stderr, and produced no
  `[neko-thread-adjacent]` / `[neko-root-area]` hits or `hs_err` file. Source
  inspection showed the bounded pre-thread scanner was enabled by
  `NEKO_THREAD_ADJ_DEBUG`, but the derived HotSpot `HandleArea` scanner still
  returned unless `NEKO_ROOT_SCAN_DEBUG` or `NEKO_ROOT_SLOT_DEBUG` was set. This
  invalidates the first narrow run as a HandleArea rejection. Corrected scope:
  allow `NEKO_THREAD_ADJ_DEBUG` to enter only the existing validated HandleArea
  scanner while keeping native publisher, object-carrier, and full
  JNIHandleBlock-chain scans disabled.
- Rejected diagnostic evidence 2026-05-23: after the corrected
  `NEKO_THREAD_ADJ_DEBUG` gate, focused codegen/translator validation passed and
  fresh obfusjack regeneration produced `build/neko-native-work/run-56062264734744`.
  Generated C showed `NEKO_THREAD_ADJ_DEBUG`, the NJX
  `neko_debug_thread_adjacent_roots(...)` call sites, and the HandleArea scanner
  condition accepting the thread-adjacent gate. Strict Shenandoah with
  `NEKO_THREAD_ADJ_DEBUG=1` still timed out after 120s, reached obfusjack
  Platform/Virtual microbench output, and emitted no stderr, no `hs_err`, and no
  `[neko-thread-adjacent]` / `[neko-root-area]` hits. The same fresh artifact
  without diagnostics aborted during matrix execution at slot
  `0x00007f7610305c08`, stale raw Class oop `0x000000051d313288`, forwardee
  `0x00000007ffc01a00`; `hs_err` thread events place that slot in the same
  native arena before a cluster of short-lived JavaThread allocations, including
  `0x00007f7610309f10`. The per-slot HandleArea diagnostic is therefore too
  expensive for this reproducer. Next diagnostic scope: keep the bounded
  pre-thread forwarding check, but replace `NEKO_THREAD_ADJ_DEBUG`'s derived
  HandleArea per-slot walk with an opt-in range-only summary of validated
  HandleArea chunks, so strict runs can compare the verifier slot to
  `[neko-root-area-range]` without reading every candidate oop slot.
- Diagnostic volume correction 2026-05-23: the first range-only run passed
  focused validation and regeneration, then timed out after 120s with no
  `hs_err` while `threadadj-ranges.err` reached the 20,000-line summary cap.
  All sampled tail rows repeated the same main JavaThread range
  `bottom=0x7f76d80084b0 hwm=0x7f76d80084b0 top=0x7f76d8008588`, so duplicate
  range logging starved later worker-thread evidence. Corrected scope: dedupe
  `[neko-root-area-range]` by `(thread, chunk, bottom, hwm, top)` and keep the
  diagnostic read-only/env-gated.
- Diagnostic overhead correction 2026-05-23: deduped range logging reduced
  stderr to one range line, but the strict run still timed out after 120s before
  completing Platform/Virtual output. The remaining enabled work is the bounded
  pre-thread scanner running from every translated transition hook. Corrected
  scope: under `NEKO_THREAD_ADJ_DEBUG` alone, inspect only NJX call-boundary
  sites; keep transition/safepoint coverage available through the broader
  `NEKO_ROOT_SLOT_DEBUG` / `NEKO_ROOT_SCAN_DEBUG` diagnostics.
- Diagnostic split 2026-05-23: after narrowing `NEKO_THREAD_ADJ_DEBUG` to NJX
  call-boundary sites, strict Shenandoah still timed out before Platform
  completion and emitted one deduped main-thread range. The remaining overhead
  is the bounded pre-thread forwarding scan on frequent NJX calls. Corrected
  scope: allow `NEKO_ROOT_AREA_SUMMARY=1` alone to emit deduped NJX HandleArea
  ranges without the pre-thread forwarding scan; keep `NEKO_THREAD_ADJ_DEBUG`
  for the bounded pre-thread scan.
- Diagnostic evidence 2026-05-23: summary-only strict Shenandoah reached matrix
  execution and reproduced the verifier abort at slot `0x00007f69cc329aa8`,
  stale raw Class oop `0x00000005378f43f8`, forwardee
  `0x00000007fe9a2b80`. The exact JavaThread allocation
  `0x00007f69cc329c50` was logged by `hs_err`; the failing slot is
  `thread - 0x1a8`. The matching deduped HandleArea summary for that thread was
  `chunk=0x7f69cc326900 bottom=0x7f69cc326910 hwm=0x7f69cc326918 top=0x7f69cc3269e8`,
  so the verifier slot is outside the derived HandleArea range. Next diagnostic
  scope: extend the same low-overhead `NEKO_ROOT_AREA_SUMMARY` NJX path to log
  active JNIHandleBlock block/handle ranges, proving whether the remaining
  slot is in active JNI handles or in another JavaThread-adjacent VM allocation.
- Rejected diagnostic evidence 2026-05-23: after adding the
  `NEKO_ROOT_AREA_SUMMARY` active JNIHandleBlock range summary, focused
  codegen/translator validation passed. Fresh obfusjack regeneration produced
  `build/neko-native-work/run-57408100959121` and a current
  `neko-test/build/test-native/obfusjack-native.jar`; Gradle failed only while
  writing the XML test report, and generated C inspection confirmed
  `neko_debug_summarize_active_jnih` was present in the fresh artifact. Strict
  Shenandoah summary-only repro reached matrix execution and aborted at verifier
  slot `0x00007f0e24331fc8`, stale raw Class oop `0x00000005374f2038`,
  forwardee `0x00000007fe9d3180`. `hs_err` thread events report JavaThread
  `0x00007f0e24332170`; the verifier slot is exactly `thread - 0x1a8`. The
  same-thread active JNIHandleBlock summaries showed handle ranges outside that
  slot, for example `head=0x7f0d2e1fdbe0 handles=0x7f0d2e1fdbe0
  hwm=0x7f0d2e1fdbe0 cap=0x7f0d2e1fdce0 top=0`, and later summaries for that
  thread still had `top=0` and distant `0x7f0d...` storage. The same-thread
  HandleArea ranges were `0x7f0e2432e9c0..0x7f0e2432ea98` and later
  `0x7f0e24341450..0x7f0e24341528`, neither containing
  `0x00007f0e24331fc8`. This rejects active JNIHandleBlock and derived
  HandleArea ownership for the reproduced root. Next diagnostic scope: under
  summary-only `NEKO_ROOT_AREA_SUMMARY`, add a deduped, read-only raw
  pre-JavaThread window dump for `thread - 0x200` through `thread`, logging
  aligned slots and values without dereferencing or mutating them. Required
  evidence is a fresh strict Shenandoah run showing whether the stale Class oop
  is already present in the JavaThread-adjacent VM window before HotSpot root
  verification aborts.
- Rejected diagnostic evidence 2026-05-23: the pre-JavaThread window dump
  passed focused codegen/translator validation, and fresh obfusjack native
  regeneration passed with `build/neko-native-work/run-57792900472522` as the
  current artifact. The first strict summary-only Shenandoah run aborted at
  verifier slot `0x00007ffb38331d78`, stale raw Class oop
  `0x00000005374e3838`, forwardee `0x00000007fe962380`; `hs_err` reports
  JavaThread `0x00007ffb38331f20`, so the slot is `thread - 0x1a8`. A captured
  rerun aborted at verifier slot `0x00007efc9032eaf8`, stale raw Class oop
  `0x000000052f974f30`, forwardee `0x00000007fe90c9f0`; `hs_err` reports
  JavaThread `0x00007efc9032ec70`, so the slot is `thread - 0x178`. The same
  captured stderr logged that exact slot for that exact thread at NJX boundary
  fingerprints `index=7` and `index=80`, and both rows had `raw=(nil)`. No
  `0x52f974f30` row appeared in the summary log. Same-thread active
  JNIHandleBlock summaries had `top=0` with distant `0x7efb...` storage, and
  same-thread HandleArea ranges were distant from `0x00007efc9032eaf8`. This
  rejects NJX-boundary-only ownership for the stale slot: the slot is null at
  logged NJX boundaries and contains the stale Class oop at root verification.
  Next diagnostic scope: keep the summary-only window read-only/env-gated, but
  also sample translated handle-scope save/restore boundaries
  (`handle_save`, `handle_restore_before`, `handle_restore_after`) to identify
  the first native boundary where the JavaThread-adjacent slot changes.
- Diagnostic volume correction 2026-05-23: the first handle-scope summary run
  used fresh artifact `build/neko-native-work/run-58309830770410` and timed out
  after `120s` with no `hs_err`; stdout reached obfusjack Platform
  `1225 ms`, and stderr grew to `8208929` bytes. Tail rows show
  `[neko-root-jnih-range]` from `handle_save`, `handle_restore_before`, and
  `handle_restore_after` consuming the 20,000-line active-JNI summary cap on a
  long `0x7f4f68204be0..0x7f4f68205770` handle chain. The active-JNI and
  HandleArea ownership questions were already rejected by NJX summary evidence.
  Corrected scope: for handle-scope sites under summary-only
  `NEKO_ROOT_AREA_SUMMARY`, emit only the deduped pre-JavaThread raw window;
  keep active-JNI and HandleArea summaries on NJX sites.
- Diagnostic evidence 2026-05-23: after narrowing handle sites to window-only,
  focused validation and fresh obfusjack regeneration passed with
  `build/neko-native-work/run-58588744670798`. Strict summary-only Shenandoah
  aborted during matrix execution. The run reached Platform `397 ms` and
  Virtual `5058 ms` under diagnostics, then `hs_err_thread_window_scope2_4.log`
  reported verifier slot `0x00007f063831ea38`, stale raw Class oop
  `0x0000000527074660`, forwardee `0x00000007ffc03e78`. The nearest matching
  short-lived JavaThread event in the same allocation cluster is
  `0x00007f06383247a0`, making the verifier slot `thread - 0x5d68`; this is
  outside the current `thread - 0x200` raw window. The summary log contains no
  `[neko-thread-window]` row within `0x2000` bytes of the verifier slot. Next
  diagnostic scope: at handle-scope summary sites, add a read-only larger
  pre-thread forwarded-oop scan over `thread - 0x8000` through `thread`, logging
  only slots whose raw value is an oop with a forwarding mark, capped globally.
- Diagnostic volume correction 2026-05-23: the first `thread - 0x8000`
  forwarded scan used fresh artifact `build/neko-native-work/run-58825160045729`
  and timed out after `120s` with no `hs_err`; stdout reached Platform
  `396 ms`, stderr reached `8798106` bytes, and the log contained `36994`
  `[neko-thread-arena-forwarded]` lines. The first and last cap rows repeatedly
  report the same slot `0x7f79f80d83c8` with raw `0x7f7900000000`, which is
  not in the zero-based compressed Java heap range previously reported by
  `hs_err` (`0x000000051d000000..0x0000000800000000`). Corrected scope: filter
  forwarded-scan candidates through the current compressed-oop heap address
  range and dedupe logged hits by `(slot, raw)` before consuming the global cap.
- Diagnostic evidence 2026-05-23: after heap-range filtering and `(slot, raw)`
  dedupe, focused validation and fresh obfusjack regeneration passed. Strict
  summary-only Shenandoah still timed out after `120s` with no `hs_err`; stdout
  reached the Platform/Virtual section header but no Platform timing, and
  stderr dropped to `993394` bytes. The filtered scan logged two real
  Java-heap forwarded roots at `handle_save` for thread `0x7f396c3023e0`:
  slot `0x7f396c2fe058`, raw `0x51d311788`, forwardee `0x7ffc01910`,
  `thread - 0x4388`; and slot `0x7f396c2fe0d8`, raw `0x51d313680`,
  forwardee `0x7ffc01a00`, `thread - 0x4308`. Active JNIHandleBlock and
  HandleArea summaries did not contain these slots. Corrected scope: move the
  larger forwarded-only pre-thread scan off frequent handle boundaries and run
  it only from the `safepoint_poll_wait` diagnostic path, where translated code
  has observed a real safepoint request.
- Rejected diagnostic evidence 2026-05-23: after moving the larger forwarded
  scan to `safepoint_poll_wait`, focused codegen/translator validation passed
  and fresh obfusjack native regeneration passed. Strict summary-only
  Shenandoah aborted after Platform `390 ms` and Virtual `4674 ms`.
  `hs_err_safepoint_arena_resume_4.log` reported verifier slot
  `0x00007f5278323fa8`, stale raw Class oop `0x000000052f480d98`, and
  forwardee `0x00000007fe958270`. `hs_err` thread events show JavaThread
  `0x00007f5278324150` added at `1.279`, exited at `1.361`, added again at
  `1.669`, and exited at `1.752`; the verifier slot is `thread - 0x1a8`.
  The same stderr captured the exact slot for that exact thread as `raw=(nil)`
  at `handle_save` lines `6331`, `6396`, and `12931`, and the same window was
  present at NJX after-call summary line groups before the verifier abort. The
  run emitted zero `[neko-thread-arena-forwarded]` rows. This rejects
  safepoint-wait-only wide scanning as a sufficient diagnostic scope for the
  remaining stale Class root: no translated `safepoint_poll_wait` evidence was
  emitted before HotSpot root verification failed. Next diagnostic scope:
  source-map the remaining pre-JavaThread root owner and add a read-only,
  low-overhead proof point at a generic transition that occurs after the last
  nil handle/NJX window and before HotSpot root verification.
- Diagnostic scope 2026-05-23: add a finite, read-only `NEKO_GLOBAL_REF_DEBUG`
  trace for every Neko bind-time and manifest `NewGlobalRef` result. Source
  evidence: OpenJDK 21 Shenandoah root verification scans `JNIHandles::oops_do`
  before `Threads::possibly_parallel_oops_do`, while the current diagnostics
  cover translated active handles, current HandleArea ranges, object carriers,
  NJX wrapper ranges, and pre-JavaThread windows but do not log bind-time
  global JNI handle slots unless the broad `NEKO_ROOT_SLOT_DEBUG` path is
  enabled. Current source evidence shows remaining `NewGlobalRef` call sites in
  `neko_bind_owner_class_slot`, `neko_bind_class_slot_from`,
  `neko_bind_primitive_class_slot`, `neko_bind_string_slot`, and manifest
  owner/alias preparation. Required evidence: focused codegen/translator
  validation, generated-C inspection showing the new gate is read-only and
  limited to global-ref slot logging, fresh obfusjack native regeneration, and
  strict Shenandoah obfusjack with `NEKO_GLOBAL_REF_DEBUG=1`. Completion
  criteria: if the verifier slot matches a `[neko-global-ref] slot=...` row,
  the next generic repair is the existing T4.8 global-ref de-JNI/global-handle
  surface; if no exact slot match is present, Neko bind-time/manifest global
  refs are rejected as the owner of the remaining stale Class root for this
  reproducer.
- Rejected diagnostic evidence 2026-06-01: the `NEKO_GLOBAL_REF_DEBUG`
  diagnostic passed focused codegen/translator validation, and fresh obfusjack
  native regeneration produced current artifact
  `build/neko-native-work/run-40222423595912` plus
  `neko-test/build/test-native/obfusjack-native.jar`. The Gradle integration
  harness regenerated and ran the jar to completion, then failed only while
  writing the XML result file; runtime stdout ended with
  `=== All tests completed ===`, stderr was empty, and the obfuscation log
  reported `Native stage: translated=93 rejected=0`. Strict Shenandoah with
  `NEKO_GLOBAL_REF_DEBUG=1` exited `134` and wrote
  `build/native-run-tmp/hs_err_global_ref_4.log`. The verifier reported
  outside-heap root slot `0x00007fa1a4322bb8`, stale raw Class oop
  `0x0000000527076440`, and forwardee `0x00000007ffc03e78`. Current-run stderr
  contained 6,284 `[neko-global-ref]` rows for owner class, class slot,
  primitive class slot, string slot, and manifest owner global refs, but exact
  grep found no row with `slot=0x7fa1a4322bb8` and no row with
  `raw=0x527076440`. This rejects Neko bind-time and manifest global refs as
  the exact owner of the remaining stale Class root in this reproducer. A
  follow-up broad `NEKO_ROOT_SLOT_DEBUG=1 NEKO_ROOT_SCAN_DEBUG=1
  NEKO_THREAD_ADJ_DEBUG=1` run timed out after `120s` with no `hs_err`; stdout
  reached the Platform/Virtual section and stderr logged native-owned main
  thread local roots, but the run did not reproduce the verifier abort and does
  not provide owner proof. Next diagnostic scope: add a lower-overhead,
  forwarded-only proof point at a generic handle/NJX/transition boundary that
  occurs before HotSpot root verification, without enabling full root publisher
  or object-carrier logging.
- Diagnostic scope 2026-06-01: add a finite, read-only `NEKO_ROOT_AREA_SUMMARY`
  / `NEKO_THREAD_OOPHANDLE_DEBUG` trace for VMStruct-exposed `JavaThread`
  OopHandle fields (`_threadObj`, `_vthread`, `_jvmti_vthread`,
  `_scopedValueCache`) at the same summary handle/NJX boundaries already used
  for the pre-JavaThread window. Source evidence: the fresh summary-only strict
  Shenandoah run exited `134` with
  `build/native-run-tmp/hs_err_summary_current_4.log`; the verifier root slot
  was `0x00007fc3ac320178`, stale raw Class oop `0x000000052f973ad8`, and
  forwardee `0x00000007fe904070`. `hs_err` thread events show JavaThread
  `0x00007fc3ac3202f0` was added and exited twice before the verifier abort,
  while the live JavaThread list at abort did not contain it. The verifier slot
  is exactly `thread - 0x178`; current-run stderr logged that exact slot for
  that exact JavaThread at `handle_save` three times with `raw=(nil)`. Local
  OpenJDK 21 source evidence maps the failing verifier phase to Shenandoah root
  scanning through `Threads::possibly_parallel_oops_do` and VM strong
  `OopStorageSet`, and VMStructs expose the JavaThread OopHandle fields.
  Required evidence: focused codegen/translator validation, generated-C
  inspection proving the trace is read-only, bounded, and env-gated, fresh
  obfusjack native regeneration, and strict Shenandoah obfusjack with the new
  OopHandle trace. Completion criteria: an exact verifier `slot=` match in
  `[neko-thread-oophandle]` identifies the remaining owner as a JavaThread
  OopHandle/OopStorage root; no exact match rejects those OopHandle fields for
  the reproduced run and redirects the next generic proof point to another
  HotSpot-owned root family.
- Rejected diagnostic evidence 2026-06-01: the JavaThread OopHandle diagnostic
  passed focused codegen/translator validation, generated-C inspection showed
  `NEKO_THREAD_OOPHANDLE_DEBUG` and `[neko-thread-oophandle]` rows in fresh
  obfusjack artifact `build/neko-native-work/run-41658063184898`, and fresh
  obfusjack native regeneration produced `translated=93 rejected=0` with
  runtime stdout ending in `=== All tests completed ===`. Strict Shenandoah
  with `NEKO_THREAD_OOPHANDLE_DEBUG=1` exited `134` and wrote
  `build/native-run-tmp/hs_err_oophandle_4.log`. The verifier reported
  outside-heap root slot `0x00007f848833dd98`, stale raw Class oop
  `0x00000005365da448`, and forwardee `0x00000007fe9cb180`. Current-run
  stderr contained 8,316 `[neko-thread-oophandle]` rows for `_threadObj`,
  `_vthread`, `_jvmti_vthread`, and `_scopedValueCache`; exact grep found no
  row containing `0x7f848833dd98`, `0x5365da448`, or `0x7fe9cb180`. This
  rejects those VMStruct-exposed JavaThread OopHandle fields as the exact owner
  of the remaining stale Class root in this reproducer.
- Diagnostic scope 2026-06-01: add a finite, read-only
  `NEKO_KLASS_MIRROR_DEBUG` trace at `Klass::_java_mirror` OopHandle loads.
  Source evidence: local OpenJDK 21 exposes `Klass::_java_mirror` as an
  `OopHandle`; current generated support has `off_klass_java_mirror`,
  `neko_klass_java_mirror_oop_raw`, and a static-object field path that reads
  the mirror through the raw helper before static base fallback. The verifier
  stale object is a `java.lang.Class`, while active JNI, current HandleArea,
  bind-time/manifest global refs, and JavaThread OopHandle fields have been
  rejected by exact slot comparison. Required evidence: focused
  codegen/translator validation, generated-C inspection proving the trace is
  read-only, bounded, and env-gated, fresh obfusjack native regeneration, and
  strict Shenandoah obfusjack with `NEKO_KLASS_MIRROR_DEBUG=1`. Completion
  criteria: an exact verifier `slot=` match in `[neko-klass-mirror]` identifies
  `Klass::_java_mirror` OopStorage as the owner; no exact match rejects that
  OopHandle family and redirects the next generic proof point to another
  HotSpot-owned strong OopStorage family.
- Implementation repair scope 2026-06-01: route Shenandoah static-object field
  mirror-base resolution through the existing `neko_klass_java_mirror_oop(thread,
  klass)` helper instead of `neko_klass_java_mirror_oop_raw(klass)`. Evidence:
  strict Shenandoah with `NEKO_KLASS_MIRROR_DEBUG=1` matched verifier slot
  `0x00007f6e00338568` and raw Class oop `0x00000005378f9da0` to
  `[neko-klass-mirror] site=klass_java_mirror_raw klass=0x7f6d56035da0
  slot=0x7f6e00338568 raw=0x5378f9da0`; the source audit shows the only
  executable raw caller outside the barriered helper is
  `neko_fast_get_static_object_field`. Scope: one generic static-object field
  fast-path change; retain hard abort behavior for missing Shenandoah thread or
  barrier capability through `neko_barrier_load_oop_field_thread`. Validation:
  focused codegen/translator tests, generated-C inspection showing the
  static-object fast path calls `neko_klass_java_mirror_oop(thread, klass)` and
  no longer calls the raw helper there, fresh obfusjack regeneration, strict
  Shenandoah obfusjack, and the normal performance gate follow-up. Completion
  criteria: strict Shenandoah obfusjack no longer aborts on the matched
  `Klass::_java_mirror` stale Class root and the fresh artifact reports
  `translated=93 rejected=0`.
- Follow-up repair scope 2026-06-01: make Shenandoah class-mirror to `Klass*`
  resolution thread-aware before class initialization and static-object field
  mirror-base selection. Evidence: after the static-object mirror-base repair,
  strict Shenandoah no longer wrote `hs_err`; it hard-aborted in Neko with
  `[neko-direct] MONITORENTER null object`. `NEKO_GETSTATIC_DEBUG=1` identified
  the null object as `org/example/Main.LOCK` at static offset `112`, with the
  helper reading `raw=(nil)` from `field=0x7ffc00148`, `cls=0x7fbc7c2edfc2`,
  and the class handle slot containing `slot_value=0x7ffc000d8`. The same
  debug row showed thread-aware Shenandoah handle resolution for that class
  handle produced distinct mirror oops (`cls_oop=0x7ffc021e0`,
  `base_oop=0x7ffc02268`), while `NEKO_CLASS_INIT_DEBUG=1` showed
  `org/example/Main` initialization completed before the null `LOCK` read.
  Source audit shows `neko_class_mirror_to_klass(jclass)` resolves through
  `neko_handle_oop` without a JavaThread, and the static-object path calls it
  under Shenandoah. Scope: introduce a thread-aware
  `neko_class_mirror_to_klass_thread(thread, mirror)` and use it for
  Shenandoah class initialization input normalization plus static-object
  field mirror-base selection. Validation: focused codegen/translator tests,
  generated-C inspection showing the new thread-aware helper on those paths,
  fresh obfusjack regeneration, and strict Shenandoah obfusjack.
- Follow-up repair scope 2026-06-01: make Shenandoah static-object stores use
  the same thread-aware mirror base as static-object reads. Evidence: after the
  thread-aware class-mirror repair, focused codegen/translator validation
  passed and fresh obfusjack native regeneration produced
  `build/neko-native-work/run-42662168770576` with `translated=93 rejected=0`
  and runtime stdout ending in `=== All tests completed ===`. Strict
  Shenandoah still exits with a Java `NullPointerException`, not `hs_err`.
  `NEKO_CLASS_INIT_DEBUG=1 NEKO_GETSTATIC_DEBUG=1` shows
  `org/example/Main$Color` class init once `before=0`, class init done,
  `after=1`, then `GETSTATIC` of `GREEN` at offset `116` reads
  `raw=(nil)` from the static-object field. Source evidence shows the read path
  now selects a Shenandoah barriered `Klass::_java_mirror` via
  `neko_klass_java_mirror_oop(thread, klass)`, while
  `neko_fast_set_static_object_field` still selects its base with raw
  `neko_static_base_oop((jobject)cls)` before `staticBase` and resolves the
  stored object with the threadless `neko_handle_oop(val)`. Scope: one generic
  T3.4 static-object store repair that uses the same thread-aware,
  barriered mirror-base selection as the static-object load path under
  Shenandoah, and resolves the stored object handle through the existing
  Shenandoah thread-aware handle resolver. Validation: focused
  codegen/translator tests, generated-C inspection showing both GETSTATIC and
  PUTSTATIC object paths use the thread-aware/barriered mirror-base logic under
  Shenandoah, fresh obfusjack regeneration, and strict Shenandoah obfusjack.
- Follow-up repair scope 2026-06-01: return the initialized class handle from
  the static-field class-init helper. Evidence: after the static-object store
  alignment, fresh obfusjack native regeneration still completes normally, but
  strict Shenandoah exits with a Java `NullPointerException`. Generated method
  mapping identifies `neko_native_impl_13` as
  `org/example/Main$Color.$values()[Lorg/example/Main$Color;`; original
  bytecode writes `RED`, `GREEN`, and `BLUE` before invoking `$values`.
  Current strict debug shows `neko_ensure_class_initialized` returns a distinct
  initialized handle for `org/example/Main$Color`
  (`cls=0x7f5e00305ab2`, `initialized=0x7f5e0030e720`), then
  `neko_ensure_class_initialized_once` discards that return value, marks the
  init slot, and returns the old handle; the immediately following GETSTATIC of
  `GREEN` reads `raw=(nil)`. Source evidence: the helper currently ends with
  `return cls;`. Scope: one generic static-field helper correction so the
  caller receives the initialized class mirror returned by the VM initialization
  path for the current access. Validation: focused codegen/translator tests,
  fresh obfusjack regeneration, strict Shenandoah obfusjack, and generated-C
  inspection showing the helper returns the initialized handle.
- Diagnostic scope 2026-06-01: add a finite, read-only
  `NEKO_GETSTATIC_DEBUG` static-object candidate trace that preserves the
  generated `static_base_slot` value before any class-handle substitution.
  Evidence: after the class-init helper was changed to return the initialized
  handle, focused codegen/translator validation passed and fresh obfusjack
  native regeneration produced `build/neko-native-work/run-43489804053650`
  with `translated=93 rejected=0`; strict Shenandoah no longer wrote `hs_err`,
  but exited with Java `NullPointerException`. The fresh debug row for
  `org/example/Main$Color.GREEN` shows `cls=0x7f6f682f6620`,
  `staticBase=0x7f6f682f6620`, `base=0x7ffc003e0`, `offset=116`,
  and `raw=(nil)` / `barrier=(nil)`, while the original generated
  `static_base_slot` value is not retained in the trace. Source evidence shows
  `neko_fast_get_static_object_field_ref` reads `*(ref->static_base_slot)` and
  then replaces it with `cls` before invoking the helper. Scope: record, under
  the existing debug gate only, the original slot handle, original slot oop,
  class-handle oop, `Klass::_java_mirror` raw and barriered oops, and each
  candidate field value at the VM field offset. Validation: focused
  codegen/translator tests, generated-C inspection proving the probe is
  env-gated and read-only, fresh obfusjack regeneration, and strict Shenandoah
  obfusjack with `NEKO_CLASS_INIT_DEBUG=1 NEKO_GETSTATIC_DEBUG=1`. Completion
  criteria: the trace identifies the exact candidate whose field contains the
  interpreter-written enum singleton, or proves all recorded candidates are
  null and redirects the next generic proof point to class-initialization
  ordering or VM static-field metadata.
- Implementation repair scope 2026-06-01: load `Klass::_java_mirror`
  OopHandle slots through the existing Shenandoah native-handle load barrier
  instead of the object-field load barrier. Evidence: the fresh
  `NEKO_CLASS_INIT_DEBUG=1 NEKO_GETSTATIC_DEBUG=1` strict Shenandoah run on
  `build/neko-native-work/run-43992599798362` exited with Java
  `NullPointerException` and no `hs_err`. For `org/example/Main$Color.GREEN`,
  the candidate trace showed `klass_raw_mirror=0x51d1b4310` at offset `116`
  containing `narrow=0xfff80265`, raw enum oop `0x7ffc01328`, and barriered
  enum oop `0x7ffc025e8`; the current `klass-mirror-barrier` candidate was
  `0x7ffc003e0` and its field at the same offset was null. Source evidence
  shows `Klass::_java_mirror` is an OopHandle and
  `neko_klass_java_mirror_oop(thread, klass)` currently calls
  `neko_barrier_load_oop_field_thread(thread, mirror_handle, mirror_oop)` on
  that off-heap handle slot. Scope: change only `Klass::_java_mirror`
  materialization to use `neko_barrier_load_oop_native_handle_with_thread`
  under Shenandoah, preserving hard abort behavior for missing thread or
  native-handle barrier and leaving heap object-field barriers unchanged.
  Validation: focused codegen/translator tests, generated-C inspection showing
  the helper uses the native-handle barrier and no object-field barrier for
  `Klass::_java_mirror`, fresh obfusjack regeneration, strict Shenandoah
  obfusjack, and performance gate follow-up after correctness is restored.
- Implementation repair scope 2026-06-01: preserve JVM `NEW`
  class-initialization semantics before direct native object allocation.
  Evidence: after the `Klass::_java_mirror` native-handle barrier repair,
  strict Shenandoah obfusjack reached `GREEN`, `42`, `sparse ok`,
  `Optional=246`, and `ParallelSum=332833500`, then exited with
  `NullPointerException: Cannot read the array length because
  java.math.BigDecimal.LONG_TEN_POWERS_TABLE is null` in
  `java.math.BigDecimal.divide`. The debug run showed no
  `java/math/BigDecimal` class-init row before `neko_native_impl_39` allocated
  two `BigDecimal` instances with `neko_fast_alloc_object(thread, env, cls)`
  and invoked their constructors. Generated source inspection at
  `neko_native_impl_39.c:480-497` shows translated `NEW java/math/BigDecimal`
  emits direct allocation from `g_class_ref_17` with no class-init helper,
  while source inspection shows `OpcodeTranslator.java` maps every
  `Opcodes.NEW` directly to `neko_fast_alloc_object`. Scope: one generic
  translator change so JVM `NEW` emits `cls =
  neko_ensure_class_initialized_once(env, cls, owner, &g_cls_initialized_owner)`
  before direct native allocation, and so the shared static class-init emission
  assigns the helper's returned initialized handle back to `cls` instead of
  discarding it. This applies to every translated allocated class and existing
  static PUT callsite that already performed class initialization, and does not
  add JNI fallback, helper-layer fallback, or owner-specific behavior.
  Validation: focused codegen/translator tests, generated-C inspection showing
  `NEW java/math/BigDecimal` and other NEW sites initialize the allocation
  class before direct allocation, fresh obfusjack regeneration, strict
  Shenandoah obfusjack, and performance gate follow-up because this adds a
  once-guarded check on allocation paths.
- Rejected implementation evidence 2026-06-01: assigning the initialized
  class handle back to `cls` before every translated direct allocation is not
  accepted. Fresh focused codegen/translator validation passed and fresh
  obfusjack regeneration produced `build/neko-native-work/run-44589309871258`
  with `translated=93 rejected=0`, but the default runtime crashed after
  `anon-sup`. The fresh `hs_err_pid942698.log` reports `SIGSEGV` in
  `libneko_2031857248429656656.so+0x29d5a5`; objdump maps that offset to
  `neko_fast_alloc_object` dereferencing the `Klass*` resolved from the class
  mirror supplied to allocation. A debug rerun with `NEKO_CLASS_INIT_DEBUG=1`
  wrote `build/native-run-tmp/hs_err_g1_new_debug_4.log`; stderr last completed
  `org/example/Main$Rectangle` class init with
  `cls=0x7fdbb029b15a` and `initialized=0x7fdbb02a4158`, then crashed before
  the next translated label. Generated C at
  `build/neko-native-work/run-44589309871258/neko_native_impl_39.c:1027`
  used that returned initialized handle as the input to
  `neko_fast_alloc_object`. Corrected scope: translated `NEW` must call
  `neko_ensure_class_initialized_once` to preserve JVM class-initialization
  order, but direct allocation must keep using the stable bound class mirror.
  Shared static class-init emission is restored to side-effect-only for
  translated allocation and static PUT callsites; returned-handle use remains
  limited to runtime paths that explicitly resolve static field mirror bases.
- Diagnostic scope 2026-06-01: add a finite, read-only
  `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG` scan around decoded generated global-ref
  slots. Evidence: after restoring translated `NEW` to side-effect-only class
  initialization before stable-class allocation, focused validation passed,
  fresh obfusjack regeneration produced `build/neko-native-work/run-45088067716859`
  with `translated=93 rejected=0`, and generated C shows
  `NEW java/math/BigDecimal` calls `neko_ensure_class_initialized_once(...)`
  before `neko_fast_alloc_object(thread, env, cls)`. Strict Shenandoah then
  progressed into matrix execution and aborted with
  `Before Mark, Roots; Double forwarding`. The fresh
  `NEKO_GLOBAL_REF_DEBUG=1` run wrote
  `build/native-run-tmp/hs_err_shen_global_only_4.log`: verifier slot
  `0x00007f5498181380` held stale Class oop `0x000000051d000c90` with forwardee
  `0x00000007ffc7d6b8`. Current-run global-ref stderr emitted 6,284
  `[neko-global-ref]` rows and no exact verifier-slot row; its first decoded
  generated global-ref slot in the same address area was `0x7f5498181420`,
  placing the verifier slot `0xa0` before that logged generated slot. Scope:
  under the new env gate only, scan a small aligned readable window around each
  decoded generated global-ref slot, print forwarded neighbor roots plus their
  nearest generated ref site/owner, and do not mutate slots or alter normal
  global-ref binding. Validation: focused codegen/translator tests, generated-C
  inspection proving the scan is bounded, env-gated, and read-only, fresh
  obfusjack regeneration, and strict Shenandoah obfusjack with
  `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG=1`. Completion criteria: if the verifier slot
  appears in a `[neko-global-ref-neighbor]` row, use that row's nearest
  generated site/owner and relative offset as the next exact evidence target;
  if no exact row appears, reject generated global-ref OopStorage neighborhood
  ownership for this reproducer and redirect to the next HotSpot-owned root
  family.
- Follow-up diagnostic scope 2026-06-01: retain the decoded generated
  global-ref origin slots under `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG` and rescan
  those same small windows at existing handle/NJX/safepoint debug boundaries.
  Evidence: the first neighbor-only strict Shenandoah run wrote
  `build/native-run-tmp/hs_err_shen_global_neighbor_4.log` with verifier slot
  `0x00007f8370181468`, stale Class oop `0x000000051d001608`, and forwardee
  `0x00000007ffc7aa08`; current-run stderr had no exact
  `[neko-global-ref-neighbor]` row for that slot or oop. The both-env run wrote
  `build/native-run-tmp/hs_err_shen_global_both_4.log` with verifier slot
  `0x00007f1b9c181380`, stale Class oop `0x000000051d000c90`, and forwardee
  `0x00000007ffc7d6b8`; current-run stderr had no exact verifier slot row, and
  its first generated global-ref row was `slot=0x7f1b9c181420`. These runs
  prove the bind-time neighbor scan executes before the verifier slot becomes a
  forwarded root. Scope: record a bounded list of generated global-ref origin
  slots only while the env gate is enabled, and call a read-only checkpoint
  scanner from existing diagnostic boundary sites without enabling broad root
  publisher scans or mutating any root. Validation: focused codegen/translator
  tests, generated-C inspection showing the retained origin list and checkpoint
  scan are env-gated and read-only, fresh obfusjack regeneration, and strict
  Shenandoah obfusjack with `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG=1`. Completion
  criteria: an exact checkpoint row for the verifier slot identifies the root
  area relative to a generated global-ref origin; if no exact row appears, the
  current generated global-ref neighborhood diagnostic is rejected and the next
  proof point must target another HotSpot-owned root family.
- Implementation repair scope 2026-06-01: refresh recorded generated
  global-handle-area windows at real Shenandoah safepoint waits using the
  existing Shenandoah native-handle load barrier. Evidence: the late
  `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG=1` run timed out before verifier abort but
  produced the exact stale-root row before timeout:
  `[neko-global-ref-neighbor] checkpoint=handle_save site=owner_class
  owner=org/example/Main$1 origin_slot=0x7f3d3c181420 slot=0x7f3d3c181380
  raw=0x51d000c90 forwardee=0x7ffc7d6b8 rel=-160 origin_index=0 index=209`.
  Earlier `hs_err_shen_global_both_4.log` reported the same slot shape
  (`0xa0` before the first generated global-ref origin), same stale
  `java.lang.Class` raw oop, and same forwardee at verifier abort. Source
  inspection shows no Neko write to that exact slot; Neko only creates
  persistent global refs with `g_neko_jni_new_global_ref_fn`, stores the
  returned handle in generated caches, and later resolves handles through
  native-handle barriers under Shenandoah. Scope: under Shenandoah only, record
  unique generated global-ref origin windows during binding, and when a
  translated safepoint poll observes an armed polling word, scan those recorded
  windows for marked forwarded roots that pass the native-handle root-shape
  checks, then heal them through `neko_barrier_load_oop_native_handle_with_thread`.
  This must not run under non-Shenandoah collectors, must not scan unrecorded
  memory, must not add JNI/JVMTI/fallback paths, and must not change generated
  global-ref creation semantics. Validation: focused codegen/translator tests,
  generated-C inspection showing safepoint-only Shenandoah refresh and no
  default hot-path call, fresh obfusjack regeneration, strict Shenandoah
  obfusjack, and performance gate follow-up preserving Calc `2-3 ms`.
- Rejected evidence 2026-06-01: the safepoint-wait-only global-handle-window
  refresh passed focused validation, fresh obfusjack regeneration, and
  generated-C inspection, but strict Shenandoah still aborted. The no-debug run
  wrote `build/native-run-tmp/hs_err_shen_refresh_4.log` with verifier slot
  `0x00007f88a8181308`, stale `sun.nio.cs.UTF_8` raw oop `0x000000051d00d368`,
  and forwardee `0x00000007ffc80728`. The matching debug run timed out after
  reaching obfusjack Platform output, emitted zero `checkpoint=safepoint_poll_wait`
  and zero `[neko-global-ref-refresh]` rows, then logged the same stale
  raw/forwardee at the recorded global-handle neighborhood during `handle_save`:
  `[neko-global-ref-neighbor] checkpoint=handle_save site=owner_class
  owner=org/example/Main$1 origin_slot=0x7f11801813e0 slot=0x7f1180181348
  raw=0x51d00d368 forwardee=0x7ffc80728 rel=-152 origin_index=0 index=227`.
  This rejects the safepoint-only placement for the current failing root.
  Corrected scope: run the same Shenandoah-only bounded refresh at
  `handle_save`, the only current boundary with an exact stale-root row. Keep
  `handle_restore_*`, NJX, non-Shenandoah collectors, global-ref creation
  semantics, and unrecorded memory untouched. Retain hard abort behavior for
  missing Shenandoah handle-barrier support. Validation: focused
  codegen/translator tests, generated-C inspection showing handle-save-only
  Shenandoah refresh and no non-Shenandoah hot-path call, fresh obfusjack
  regeneration, strict Shenandoah obfusjack, then the performance gate
  preserving Calc `2-3 ms`.
- Rejected evidence 2026-06-01: the handle-save full-window refresh passed
  focused validation, fresh obfusjack regeneration, and generated-C inspection
  proving the only call site was `neko_handle_save`, but strict Shenandoah
  obfusjack timed out after `120s`. The run wrote no `hs_err` and no stderr,
  reached the Platform/Virtual microbench, and printed
  `Platform threads: 29080 ms (50000 tasks)`, proving the full sweep at every
  `handle_save` is too broad for the current handle frequency even though it
  suppresses the previous verifier abort. Corrected scope: keep the repair at
  `handle_save` only, but make it incremental and bounded by scanning a small
  rotating batch of recorded global-handle origin windows per call, with a
  single readability check per window before slot iteration. This preserves
  progress across all recorded windows without full-set scanning on every method
  frame, keeps `handle_restore_*`, NJX, non-Shenandoah collectors, global-ref
  creation semantics, and unrecorded memory untouched, and retains hard abort
  behavior for missing Shenandoah handle-barrier support. Validation: focused
  codegen/translator tests, generated-C inspection showing handle-save-only
  incremental refresh, fresh obfusjack regeneration, strict Shenandoah
  obfusjack, then the performance gate preserving Calc `2-3 ms`.
- Follow-up evidence 2026-06-01: handle-save incremental batch `4` passed
  focused validation, fresh obfusjack regeneration, and generated-C inspection,
  but strict Shenandoah still aborted during matrix execution. The no-debug run
  reached `Platform threads: 551 ms` and `Virtual threads: 3622 ms`, then
  `build/native-run-tmp/hs_err_shen_incr_4.log` reported verifier slot
  `0x00007fe958247450`, stale raw oop `0x000000051d001608`, and forwardee
  `0x00000007ffc7aa08`. A current-artifact
  `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG=1` run timed out before the Platform timing
  line and wrote no `hs_err`, but it logged the same raw/forwardee in the
  recorded origin-0 owner-class window at `handle_restore_before`:
  `[neko-global-ref-neighbor] checkpoint=handle_restore_before site=owner_class
  owner=org/example/Main$1 origin_slot=0x7fa710181420 slot=0x7fa710181428
  raw=0x51d001608 forwardee=0x7ffc7aa08 rel=8 origin_index=0 index=2669`;
  subsequent `checkpoint=handle_save` refresh rows healed that same raw, proving
  handle-save-only leaves a stale-root interval after restore and before the
  next save. Corrected scope: keep handle-save incremental batch `4`, and add
  a Shenandoah-only refresh of only recorded origin index `0` at
  `handle_restore_before`. Keep `handle_restore_after`, NJX, non-Shenandoah
  collectors, global-ref creation semantics, and unrecorded memory untouched.
  Validation: focused codegen/translator tests, generated-C inspection showing
  handle-save incremental refresh plus handle-restore-before origin-0 refresh,
  fresh obfusjack regeneration, strict Shenandoah obfusjack, then the
  performance gate preserving Calc `2-3 ms`.
- Validation correction evidence 2026-06-01: the first origin-0
  `handle_restore_before` implementation passed focused tests but failed fresh
  default obfusjack native regeneration/runtime validation. The Gradle
  integration test timed out after `2s` inside `runJar`; stdout reached only
  `--- Microbench: Platform vs Virtual Threads ---`, stderr was empty, and
  generated C showed an unconditional call to
  `neko_refresh_first_global_ref_area_for_shenandoah(save->thread,
  "handle_restore_before")`. This violates the recorded no-non-Shen-hot-path
  requirement even though the helper returns internally for non-Shenandoah.
  Corrected implementation detail: guard both handle-save and restore-before
  refresh calls with `neko_const_use_shenandoah()` at the call site so default
  collectors do not pay a helper call.
- Follow-up evidence 2026-06-01: after the guarded origin-0
  `handle_restore_before` refresh, focused tests, fresh obfusjack regeneration,
  and generated-C inspection passed, but strict Shenandoah still aborted during
  matrix execution. The no-debug run reached `Platform threads: 757 ms` and
  `Virtual threads: 3502 ms`, then
  `build/native-run-tmp/hs_err_shen_restore0_4.log` reported verifier slot
  `0x00007f97cc254820`, stale `java.lang.Class` raw oop
  `0x00000007ffc281d0`, and forwardee `0x00000007ffc60230`. Existing
  current-artifact debug evidence from
  `build/native-run-tmp/shen-incr-dbg.err` contains the same raw/forwardee pair
  at `handle_restore_before` in a recorded generated global-handle window:
  `[neko-global-ref-neighbor] checkpoint=handle_restore_before
  site=manifest_owner owner=org/example/Main$2
  origin_slot=0x7fa71024ec80 slot=0x7fa71024ecf8 raw=0x7ffc281d0
  forwardee=0x7ffc60230 rel=120 origin_index=1 index=95`; subsequent
  `checkpoint=handle_save` rows healed the same raw/forwardee for origin index
  `1`. This proves the remaining stale interval is not limited to origin index
  `0`. Corrected scope: replace the origin-0-only restore-before refresh with
  the existing Shenandoah-only bounded rotating global-ref window refresh at
  `handle_restore_before`, preserving the call-site `neko_const_use_shenandoah()`
  guard, handle-save batch `4`, non-Shenandoah no-call behavior, global-ref
  creation semantics, and unrecorded-memory exclusion. Validation: focused
  codegen/translator tests, generated-C inspection showing both handle-save and
  restore-before use the bounded rotating refresh only under Shenandoah, fresh
  obfusjack regeneration, strict Shenandoah obfusjack, then the performance gate
  preserving Calc `2-3 ms`.
- Rejected evidence 2026-06-01: replacing the origin-0-only restore-before
  refresh with the bounded rotating refresh at `handle_restore_before` passed
  focused validation, fresh obfusjack regeneration, and generated-C inspection.
  Fresh generated artifact `build/neko-native-work/run-49260220606423` shows
  `neko_const_use_shenandoah()` guarded calls to
  `neko_refresh_global_ref_area_for_shenandoah` at `handle_save` and
  `handle_restore_before`, contains `const uint64_t batch = 4u`, and contains
  no `neko_refresh_first_global_ref_area_for_shenandoah`. Strict Shenandoah
  still aborted during matrix execution after `Platform threads: 1099 ms` and
  `Virtual threads: 4134 ms`. The fresh
  `build/native-run-tmp/hs_err_shen_restore_batch_4.log` reports verifier slot
  `0x00007f4e182701b0`, stale `java.lang.Class` raw oop
  `0x00000007ffc7eef8`, and forwardee `0x00000007ffc7f1d8`. Existing
  current debug logs contain no row for raw `0x7ffc7eef8` or forwardee
  `0x7ffc7f1d8`, so the next change must gather lower-overhead current-artifact
  correlation before another repair changes refresh coverage or batch size.
  Diagnostic scope: add a diagnostic-only `NEKO_GLOBAL_REF_REFRESH_DEBUG` gate
  that prints only `[neko-global-ref-refresh]` rows from the existing
  Shenandoah global-ref window refresh and does not enable the read-only
  neighbor checkpoint scanner. Keep `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG` behavior
  unchanged, keep normal behavior unchanged when both env gates are absent, and
  do not mutate any additional slots. Validation: focused codegen/translator
  tests, generated-C inspection proving the refresh-only env gate is separate
  from neighbor scanning and no origin-only helper returns, fresh obfusjack
  regeneration, and strict Shenandoah obfusjack with
  `NEKO_GLOBAL_REF_REFRESH_DEBUG=1` to determine whether the fresh verifier raw
  is ever healed by the bounded refresh path before abort.
- Rejected diagnostic evidence 2026-06-01: the first refresh-only diagnostic
  passed focused validation, fresh obfusjack regeneration, and generated-C
  inspection. Fresh generated artifact `build/neko-native-work/run-49536621283100`
  shows `NEKO_GLOBAL_REF_REFRESH_DEBUG` only in
  `neko_global_ref_refresh_debug_enabled`, while the checkpoint neighbor scanner
  remains gated only by `NEKO_GLOBAL_REF_NEIGHBOR_DEBUG`; the artifact still has
  `const uint64_t batch = 4u`, guarded `handle_save` and
  `handle_restore_before` refresh calls, and no
  `neko_refresh_first_global_ref_area_for_shenandoah`. Strict Shenandoah with
  `NEKO_GLOBAL_REF_REFRESH_DEBUG=1` timed out after `120s`, wrote no `hs_err`,
  emitted `5,550,991` refresh rows (`1.2G`) to
  `build/native-run-tmp/shen-refresh-only.err`, and stdout reached only
  `--- Microbench: Platform vs Virtual Threads ---`. This diagnostic volume
  prevents reaching the verifier failure. Corrected diagnostic scope: keep the
  refresh-only gate and add env-gated skip/cap controls
  `NEKO_GLOBAL_REF_REFRESH_DEBUG_SKIP` and
  `NEKO_GLOBAL_REF_REFRESH_DEBUG_LIMIT` around refresh-row printing only. The
  controls must not affect refresh mutation, batch selection, origin recording,
  neighbor scanning, non-Shenandoah behavior, or default execution when the
  refresh debug gate is absent. Validation: focused codegen/translator tests,
  generated-C inspection proving skip/cap apply only to diagnostic printing,
  fresh obfusjack regeneration, and strict Shenandoah obfusjack with
  `NEKO_GLOBAL_REF_REFRESH_DEBUG=1`, skip `5500000`, and a bounded limit to
  capture late refresh rows near the matrix failure without another unbounded
  log.
- Rejected diagnostic evidence 2026-06-01: the bounded skip/cap refresh-only
  diagnostic passed focused validation, fresh obfusjack regeneration, and
  generated-C inspection. Fresh artifact `build/neko-native-work/run-49907188076132`
  contains `NEKO_GLOBAL_REF_REFRESH_DEBUG_SKIP`,
  `NEKO_GLOBAL_REF_REFRESH_DEBUG_LIMIT`, guarded
  `neko_refresh_global_ref_area_for_shenandoah` calls at `handle_save` and
  `handle_restore_before`, and no
  `neko_refresh_first_global_ref_area_for_shenandoah`. Strict Shenandoah with
  `NEKO_GLOBAL_REF_REFRESH_DEBUG=1`,
  `NEKO_GLOBAL_REF_REFRESH_DEBUG_SKIP=5500000`, and
  `NEKO_GLOBAL_REF_REFRESH_DEBUG_LIMIT=20000` aborted after reaching
  `Platform threads: 1137 ms`, `Virtual threads: 4258 ms`, and matrix startup.
  The run emitted `20001` stderr lines (`4.5M`) and wrote
  `build/native-run-tmp/hs_err_shen_refresh_cap_4.log`. That `hs_err` reports
  verifier slot `0x00007f2d44257770`, stale `java.lang.Class` raw oop
  `0x00000007ffc7eef8`, and forwardee `0x00000007ffc7f1d8`. Exact grep over
  `build/native-run-tmp/shen-refresh-cap.err`, `.out`, and the `hs_err` finds
  those slot/raw/forwardee values only in the `hs_err` and copied VM stdout,
  not in any `[neko-global-ref-refresh]` row. The captured refresh windows are
  rooted around generated global-ref origins such as `0x7f2d442df7b8`
  (`org/example/Main$1`), `0x7f2d442e1200` (`java/lang/StringBuilder`),
  `0x7f2d442e25c0` (`java/util/concurrent/TimeUnit`), and `0x7f2d442e2748`
  (`org/example/Main$Greeter$NekoLambda$1`), while the verifier slot is
  `0x00007f2d44257770`. This rejects the current global-ref origin-window
  refresh as the failing root family for this artifact. Next diagnostic scope:
  use existing root-owner diagnostics, starting with summary-only
  `NEKO_ROOT_AREA_SUMMARY=1`, to map the fresh verifier slot against active
  `JNIHandleBlock`, HandleArea, thread-window, and JavaThread OopHandle ranges
  before another runtime repair changes refresh coverage.
- Rejected diagnostic evidence 2026-06-01: the existing summary-only root-owner
  diagnostic reproduced the strict Shenandoah verifier abort without new code.
  `NEKO_ROOT_AREA_SUMMARY=1` exited `134` after `21.812260s`, reached
  `Platform threads: 2089 ms`, `Virtual threads: 6083 ms`, and matrix startup,
  emitted `27808` stderr lines (`4.4M`), and wrote
  `build/native-run-tmp/hs_err_shen_area_summary_4.log`. The verifier reported
  slot `0x00007fc1a8267ea0`, stale `java.lang.Class` raw oop
  `0x00000007ffc7eef8`, and forwardee `0x00000007ffc7f1d8`. Exact grep over
  `build/native-run-tmp/shen-area-summary.err`, `.out`, and the `hs_err` found
  those values only in the `hs_err` and copied VM stdout, not in any
  diagnostic row. Interval matching over `[neko-root-jnih-range]`
  `handles..cap`, `[neko-root-area-range]` `bottom..top`,
  `[neko-thread-window]` `slot`, and `[neko-thread-oophandle]` `slot` emitted
  no range containing `0x00007fc1a8267ea0`. The `hs_err` memory map places that
  slot inside anonymous native mapping `0x7fc1a8000000-0x7fc1a8405000`, while
  the OpenJDK 21 Shenandoah verifier source shows root verification scans
  CodeCache, ClassLoaderData, every strong `OopStorageSet` storage, then thread
  roots. Current diagnostics have now rejected generated global-ref windows,
  active `JNIHandleBlock` chains, HandleArea chunks, JavaThread OopHandle
  fields, and the near-thread window for the reproduced slot. Next diagnostic
  scope: add a diagnostic-only OopStorage owner mapper for Shenandoah root
  verification, resolving `JNIHandles::_global_handles` and
  `_weak_global_handles` from VMStructs static-field addresses and printing only
  bounded ranges/forwarded slots needed to determine whether the verifier slot
  belongs to JNI Global or JNI Weak OopStorage. This must be env-gated,
  read-only, absent from default execution, and must not alter root contents or
  refresh behavior.
- Diagnostic implementation scope 2026-06-01: add only
  `NEKO_OOPSTORAGE_DEBUG` support for JNI Global/JNI Weak OopStorage owner
  mapping. Scope: capture VMStruct static-field addresses for
  `JNIHandles::_global_handles` and `JNIHandles::_weak_global_handles`; derive
  only the JDK 21 `OopStorage`/`ActiveArray`/`Block` offsets needed for
  read-only range/match diagnostics; guard every private-layout read with
  readable-range, owner-address, count, and alignment checks; emit bounded
  `[neko-oopstorage-range]`/`[neko-oopstorage-forwarded]` rows only when the
  env gate is set. Required evidence: OpenJDK 21 source lines show
  `JNIHandles` static OopStorage fields are exposed by VMStructs, OopStorage
  nonstatic internals are not VMStruct-exposed, `ActiveArray` block pointers
  start at aligned `sizeof(ActiveArray)`, `Block::_data` is first, and
  `Block::_owner_address` is the owner validation field. Validation: focused
  codegen tests, generated-C inspection proving the diagnostic is env-gated
  and read-only, fresh obfusjack native regeneration, then strict Shenandoah
  obfusjack with `NEKO_OOPSTORAGE_DEBUG=1` and refresh logging capped off.
  Completion criteria: the diagnostic either maps the verifier slot/raw to JNI
  Global or JNI Weak OopStorage, rejects both with concrete range evidence, or
  hard-aborts only on malformed private layout; no JNI/JVMTI/fallback/original
  bytecode path, collector skip, root mutation, or default hot-path work is
  introduced.
- Diagnostic refinement scope 2026-06-01: the first OopStorage diagnostic
  passed focused codegen tests, regenerated fresh obfusjack native artifact
  `build/neko-native-work/run-51193904437796` with `translated=93 rejected=0`,
  and generated C shows `NEKO_OOPSTORAGE_DEBUG` is env-gated and read-only.
  Standalone default obfusjack completed green, while the JUnit wrapper timed
  out on its two-second `runJar` wait. Strict Shenandoah with
  `NEKO_OOPSTORAGE_DEBUG=1` and refresh logging disabled reached the Platform
  thread benchmark header, timed out at `120s`, wrote no `hs_err`, emitted
  `233918` stderr rows (`57M`), including `8340`
  `[neko-oopstorage-range]` rows and `225578`
  `[neko-oopstorage-forwarded]` rows. The rows identify JNI Global OopStorage
  as carrying forwarded roots under Shenandoah, with one JNI Weak empty-range
  row and no JNI Weak forwarded row. This diagnostic volume prevents reaching
  the verifier failure. Corrected diagnostic scope: keep the same read-only
  JNI Global/JNI Weak OopStorage mapper, but add env-configured scan and print
  caps for OopStorage diagnostics only. The caps must not mutate roots, affect
  default execution, change refresh behavior, change non-Shenandoah behavior,
  or alter the mapped OopStorage layout checks. Validation: focused codegen
  tests, generated-C inspection proving caps guard only diagnostic scanning and
  row printing, fresh obfusjack regeneration, then strict Shenandoah obfusjack
  with `NEKO_OOPSTORAGE_DEBUG=1` and bounded caps to obtain either a verifier
  `hs_err` with OopStorage correlation or a concrete rejection of JNI Global
  and JNI Weak OopStorage ownership.
- Rejected diagnostic evidence 2026-06-01: the bounded range-only OopStorage
  diagnostic passed focused codegen tests, regenerated fresh obfusjack native
  artifact `build/neko-native-work/run-51787101937391`, passed the JUnit
  obfusjack wrapper, and generated C shows the scan skip/limit, row limits,
  and `NEKO_OOPSTORAGE_FORWARDED_LIMIT=0` fast path guard only diagnostics.
  Strict Shenandoah with `NEKO_OOPSTORAGE_DEBUG=1`,
  `NEKO_OOPSTORAGE_FORWARDED_LIMIT=0`,
  `NEKO_OOPSTORAGE_RANGE_LIMIT=20000`, and refresh logging disabled exited
  `134`, reached `Platform threads: 16322 ms`, `Virtual threads: 32446 ms`,
  and matrix startup, emitted `8348` stderr lines (`2.1M`), and wrote
  `build/native-run-tmp/hs_err_shen_oopstorage_bounded_4.log`. The verifier
  reported slot `0x00007f9f90268980`, stale `java.lang.Class` raw oop
  `0x00000007ffc7eef8`, and forwardee `0x00000007ffc7f1d8`. The stderr
  contains `8347` `[neko-oopstorage-range]` rows, zero forwarded rows, zero
  cap rows, `8340` JNI Global rows, and `7` JNI Weak rows. Interval matching
  found no JNI Global or JNI Weak `slot_start..slot_end` row containing
  `0x00007f9f90268980`; the nearest JNI Global data range was
  `0x7f9f902684c0..0x7f9f902686c0` at block index `8`, with the verifier slot
  `705` bytes after that range, and the next logged JNI Global active-array
  block was `0x7f9f9026bd00`. This rejects JNI Global/JNI Weak active-array
  data-window ownership for the exact verifier slot, but it does not reject
  another strong OopStorage in the same native arena.
- Diagnostic implementation scope 2026-06-01: add an env-gated OopStorage
  candidate-owner mapper using OpenJDK 21 `OopStorage::Block::block_for_ptr`
  rules. Source evidence: OpenJDK 21 `OopStorage` defines blocks as
  `BitsPerWord` oop entries split into `BytesPerWord` sections of
  `BitsPerByte` entries, aligned to `sizeof(oop) * BitsPerByte`, and
  `block_for_ptr` maps a candidate root slot by walking those section-aligned
  block starts and validating the candidate `_owner_address`. Scope: under a
  new diagnostic env gate only, scan a bounded neighborhood around already
  observed OopStorage block allocations for forwarded oop values, derive the
  candidate block and owner from the slot address using the same section-walk
  owner validation, read the owner `_name` string when readable, and print a
  bounded `[neko-oopstorage-candidate]` row. Required evidence: generated C
  must show the candidate mapper is read-only, env-gated, cap-bounded, and
  independent from refresh mutation; strict Shenandoah must either map the
  verifier slot/raw to a named OopStorage owner, or reject the candidate-owner
  path with no cap exhaustion covering the relevant neighborhood. No JNI,
  JVMTI, fallback/original-bytecode path, collector skip, root mutation, or
  default hot-path work is allowed.
- Diagnostic overhead correction 2026-06-01: the first candidate-owner mapper
  implementation passed focused codegen tests and regenerated fresh obfusjack
  native artifact `build/neko-native-work/run-52918493735546` with
  `translated=93 rejected=0`. Generated C showed
  `NEKO_OOPSTORAGE_CANDIDATE_DEBUG`, candidate caps, and read-only candidate
  rows. Strict Shenandoah with `NEKO_OOPSTORAGE_DEBUG=1`,
  `NEKO_OOPSTORAGE_CANDIDATE_DEBUG=1`,
  `NEKO_OOPSTORAGE_FORWARDED_LIMIT=0`, `NEKO_OOPSTORAGE_RANGE_LIMIT=20000`,
  `NEKO_OOPSTORAGE_CANDIDATE_LIMIT=60000`, and
  `NEKO_OOPSTORAGE_CANDIDATE_SCAN_BYTES=1536` timed out after `120s`, wrote no
  stdout and no `hs_err`, and emitted `11609` stderr rows (`4.4M`). The log
  contains candidate rows for validated JNI Global blocks, but also contains
  rows with one-character `candidate_owner_name=t`, proving `_owner_address`
  plus readable name/active slots is not sufficient owner validation and the
  resulting scan volume prevents reaching the verifier failure. Corrected
  implementation scope: require sane owner `ActiveArray` metadata and a
  readable block-pointer array, require owner names of at least three printable
  characters, require candidate block membership in the owner active-array for
  non-source owners, and skip exact forwarded scanning for the source block's
  own data window because the previous range-only diagnostic already rejected
  that window for the verifier slot. The correction remains env-gated,
  read-only, cap-bounded, and inactive by default; the timed-out run is
  overhead evidence only and does not reject any OopStorage owner.
- Diagnostic evidence 2026-06-01 after owner-validation tightening: focused
  codegen validation passed, fresh obfusjack regeneration produced
  `build/neko-native-work/run-53281903491567` with `translated=93 rejected=0`,
  and generated-C inspection showed the candidate mapper remained env-gated,
  read-only, and absent from default execution. Strict Shenandoah with
  `NEKO_OOPSTORAGE_CANDIDATE_DEBUG=1`, skip `54000`, limit `5000`,
  candidate limit `60000`, and scan bytes `1536` exited `134`; the verifier
  slot was `0x00007fa3bc260ac0`, raw `0x00000007ffc7eef8`, and forwardee
  `0x00000007ffc7f1d8`. The nearest valid candidate row was JNI Global block
  `0x7fa3bc260600..0x7fa3bc260800`, placing the verifier slot `704` bytes
  after the data window, and the diagnostic hit `scan_limit` at index `59000`.
  The same fresh artifact with skip `59000` and limit `5000` exited `134`;
  the verifier slot was `0x00007f3d44268770`, raw
  `0x00000007ffc7eef8`, and forwardee `0x00000007ffc7f1d8`. The nearest valid
  candidate row was JNI Global block `0x7f3d442682c0..0x7f3d442684c0`,
  placing the verifier slot `688` bytes after the data window, and the
  diagnostic hit `scan_limit` at index `64000`. Exact greps found each run's
  verifier slot/raw/forwardee only in stdout and `hs_err`, not in candidate
  rows. These runs prove the current candidate mapper does not emit enough
  failed-section evidence to accept or reject the `block_for_ptr` candidate
  set for the verifier slot.
- Corrected diagnostic scope 2026-06-01: extend the env-gated OopStorage
  candidate-owner mapper with a capped reject trace for every section-aligned
  candidate block considered by the OpenJDK `block_for_ptr` walk. Each reject
  row must include the source storage/block, candidate block address, reject
  reason, and the candidate owner address when readable; successful candidate
  rows remain unchanged. The trace must be deduped, cap-bounded, read-only,
  inactive unless `NEKO_OOPSTORAGE_CANDIDATE_DEBUG` is set, and must not scan,
  refresh, mutate, or publish any root. Validation: focused codegen tests,
  generated-C inspection proving the reject trace is env-gated/read-only, fresh
  obfusjack regeneration, and strict Shenandoah with a bounded skip window.
  Completion criteria: for the fresh verifier slot, either a valid
  `[neko-oopstorage-candidate]` row maps the slot to a named owner, or the
  eight section-walk candidate starts for that slot have concrete reject rows
  without relevant cap exhaustion.
