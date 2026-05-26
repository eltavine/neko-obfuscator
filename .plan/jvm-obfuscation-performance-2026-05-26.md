# JVM Obfuscation Performance Plan - 2026-05-26

## Scope

This plan covers JVM-only obfuscated artifact runtime performance. Native
translation, generated C, JNI removal, GC barriers, native runtime performance,
and obfuscation wall-clock/build-time performance are out of scope for this
plan.

The target transform set is `test-jars/full-jvm-obf.yml`:

- `renamer`
- `keyDispatch`
- `methodParameterObfuscation`
- `controlFlowFlattening`
- `validationSinkHardening`
- `invokeDynamic`
- `constantObfuscation`
- `stringObfuscation`
- `native.enabled: false`

All optimization work must preserve JVM ABI compatibility, full transform
coverage for eligible application code, live dynamic key propagation, packed
`Object[]` carriers, hidden method keys, reflection/MethodHandle compatibility,
and full-strength CFF block coverage. No benchmark-, method-, owner-,
descriptor-, fixture-, or crash-site-specific optimization is allowed.

## Fresh Evidence

### Baseline command

Fresh baseline command:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Result: `BUILD SUCCESSFUL in 23s`, 19 tasks executed.

Fresh runtime/topology report:

```text
build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json
```

JDK in report:

```text
OpenJDK 21.0.11, Arch Linux, OpenJDK 64-Bit Server VM
```

### Full-obfuscation runtime evidence

Single-run fresh report:

| Runtime row | Original | Full JVM obf |
| --- | ---: | ---: |
| TEST Calc | 9 ms | 179 ms |
| obfusjack Platform | 26 ms | 73 ms |
| obfusjack Virtual | 15 ms | 119 ms |
| obfusjack Seq matrix | 3 ms | 2500 ms |
| obfusjack Parallel | 0 ms | 8 ms |
| obfusjack VThreads | 0 ms | 9 ms |

Fresh 5-run repeat medians from generated jars:

| Fixture / row | Original median | CFF-only-stack median | Full JVM obf median |
| --- | ---: | ---: | ---: |
| TEST Calc | 11 ms | 94 ms | 198 ms |
| obfusjack Platform | 25 ms | 74 ms | 85 ms |
| obfusjack Virtual | 16 ms | 96 ms | 122 ms |
| obfusjack Seq matrix | 2 ms | 2459 ms | 2490 ms |
| obfusjack Parallel | 0 ms | 7 ms | 8 ms |
| obfusjack VThreads | 0 ms | 8 ms | 10 ms |

The repeated CFF-only-stack jars used `renamer`, `keyDispatch`,
`methodParameterObfuscation`, `controlFlowFlattening`, and
`validationSinkHardening`, with `invokeDynamic`, `constantObfuscation`, and
`stringObfuscation` disabled.

### Ablation evidence

Temporary measurement configs were written under `build/jvm-perf-ablation/`.
They are runtime measurement artifacts only, not product configs.

Valid single-run ablations:

| Variant | TEST Calc | obfusjack Seq | obfusjack Platform | obfusjack Virtual | Notes |
| --- | ---: | ---: | ---: | ---: | --- |
| `cff` | 100 ms | 2437 ms | 72 ms | 93 ms | CFF stack alone reproduces the matrix bottleneck. |
| `full-no-indy` | 126 ms | 2458 ms | 77 ms | 99 ms | Removing indy does not move obfusjack Seq. |
| `full-no-const-string` | 175 ms | 2428 ms | 86 ms | 101 ms | Keeping indy but removing constant/string does not move obfusjack Seq. |

Invalid ablation:

- `base` (`renamer` + `keyDispatch`) runs TEST at `Calc: 10ms`, but
  obfusjack fails with `NoSuchMethodException` /
  `NoSuchMethodError` for a MethodHandle path. This is useful only as a
  dependency finding: the obfusjack JDK21 fixture needs the fuller JVM
  compatibility surface, so this cannot be used as a performance baseline.

### JFR runtime hot-path evidence

Fresh CFF-only-stack JFR command:

```bash
mkdir -p build/jvm-runtime-perf/jfr build/jvm-runtime-perf/java-tmp
java -XX:-UsePerfData \
  -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
  -XX:StartFlightRecording=filename=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.jfr,settings=profile,delay=1s,dumponexit=true \
  -jar build/jvm-perf-ablation/cff-obfusjack.jar \
  > build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.stdout.log \
  2> build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.stderr.log
jfr view hot-methods build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.jfr
jfr print --events ExecutionSample --stack-depth 20 build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.jfr
```

Runtime output completed with `Seq: 2435 ms`, `Parallel: 19 ms`,
`VThreads: 8 ms`, and `=== All tests completed ===`.

Top delayed-JFR samples, with recording delayed to target the matrix stage:

| Method | Samples | Percent |
| --- | ---: | ---: |
| `a.v.__neko_cff_relay$0(long, int, int, int, int, int, long[], int)` | 184 | 42.59% |
| `a.a.y(double[][], int, int, double[][], double[][], long)` | 113 | 26.16% |
| `a.a.x(double[][], int, double[][], double[][], long, int)` | 84 | 19.44% |
| `a.da.ua(long, int, int, int, Object[], int, int, long[])` | 18 | 4.17% |
| `a.v.__neko_cff_relay$4(long, int, int, int, Object[], int, int, long[], int)` | 12 | 2.78% |

Map evidence for `build/jvm-perf-ablation/cff-obfusjack.jar.map`:

- `a/a` is `org/example/Main`.
- `org/example/Main.mmulSeq([[D[[D[[D)V` maps to `a.a.fa`.
- Execution-sample stacks place `__neko_cff_relay$0` and
  `__neko_cff_relay$1` under `a.a.fa(Object[], long)`, then
  `a.a.o(double[][], double[][], double[][], long)`,
  `a.a.ja(Object[], long)`, `a.a.ba(Object[], long)`, and `a.a.main`.

Source evidence for the relay path:

- `CffClassSetup.relocateLargeCffHelperSets` moves CFF helper methods to
  synthetic public host classes.
- `installRelocatedCffHelperRelays` creates `__neko_cff_relay$*` methods by
  descriptor.
- `createRelocatedCffHelperRelay` emits `ILOAD selector` followed by
  `LOOKUPSWITCH`, then invokes the selected helper.
- `rewriteRelocatedCffHelperCalls` inserts the selector at every callsite and
  rewrites the call to the relay.

This proves an exact runtime path for the first CFF repair: a generic
compile-time-known selector dispatch layer is executing in the hot obfusjack
matrix `Seq` run under the obfuscated `mmulSeq` stack.

Fresh full JVM-obf JFR command:

```bash
java -XX:-UsePerfData \
  -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
  -XX:StartFlightRecording=filename=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/jfr/full-obfusjack-matrix.jfr,settings=profile,delay=1s,dumponexit=true \
  -jar build/test-jvm-full-obf-perf/test21-obf.jar \
  > build/jvm-runtime-perf/jfr/full-obfusjack-matrix.stdout.log \
  2> build/jvm-runtime-perf/jfr/full-obfusjack-matrix.stderr.log
jfr view hot-methods build/jvm-runtime-perf/jfr/full-obfusjack-matrix.jfr
jfr print --events ExecutionSample --stack-depth 20 build/jvm-runtime-perf/jfr/full-obfusjack-matrix.jfr
```

Runtime output completed with `Seq: 2512 ms`, `Parallel: 7 ms`,
`VThreads: 9 ms`, and `=== All tests completed ===`.

Top delayed-JFR full-obf samples:

| Method | Samples | Percent |
| --- | ---: | ---: |
| `a.v.__neko_cff_relay$0(long, int, int, int, int, int, long[], int)` | 148 | 42.53% |
| `a.a.x(double[][], int, double[][], double[][], long, int)` | 87 | 25.00% |
| `a.a.y(double[][], int, int, double[][], double[][], long)` | 71 | 20.40% |
| `a.da.sa(int[], int, int, int, int, int, int)` | 12 | 3.45% |
| `a.da.ua(long, int, int, int, Object[], int, int, long[])` | 9 | 2.59% |

Full-obf execution-sample stacks place `__neko_cff_relay$0` and
`__neko_cff_relay$1` under `a.a.fa(Object[], long)`, then the full-obf indy
resolver `a.b.d(...)`, then `a.a.o(...)`, `a.a.ja(...)`,
`a.a.ba(...)`, another full-obf indy resolver frame, and `a.a.main`. This
proves the same relay dispatch layer is present on the actual full-obf target
runtime path for P1.

### Topology evidence

Fresh full-obf `TEST` topology:

- output grew from `29184` bytes to `1123454` bytes.
- `totalEstimatedMethodBytes=1098163`.
- `totalInstructions=579053`.
- `totalInvokeDynamicInstructions=475`.
- `totalCffOutlinedDispatchCalls=622`.
- `totalCffSharedGroupDispatchCalls=9`.
- `totalCffTransitionMaterialCalls=621`.
- `totalCffStepMaterialCalls=520`.
- `totalCffIslandMaterialCalls=493`.
- CFF dry-run sums include `helpers=666`, `materialWords=42286`,
  `readyHelpers=333`, `currentHelpers=333`, `projectedSharedHelpers=9`,
  and `projectedHelperReduction=324`.

Fresh full-obf `obfusjack` topology:

- output grew from `28291` bytes to `929479` bytes.
- `totalEstimatedMethodBytes=896769`.
- `totalInstructions=460741`.
- `totalInvokeDynamicInstructions=525`.
- `totalCffOutlinedDispatchCalls=514`.
- `totalCffSharedGroupDispatchCalls=6`.
- `totalCffTransitionMaterialCalls=1`.
- `totalCffStepMaterialCalls=1`.
- `totalCffIslandMaterialCalls=1`.
- CFF dry-run sums include `helpers=550`, `materialWords=33110`,
  `readyHelpers=275`, `currentHelpers=275`, `projectedSharedHelpers=6`,
  and `projectedHelperReduction=269`.

### Source evidence

- `JvmFullObfuscationPerfTest` records full JVM obf runtime, topology,
  helper descriptor counts, largest methods, and CFF call counts.
  `neko-test/src/test/java/dev/nekoobfuscator/test/JvmFullObfuscationPerfTest.java`
- `CffClassSetup.logIslandDryRunMethodStats` emits the dry-run rows proving
  current helper count, material rows/words, live dispatch token rows, and
  projected shared-helper reduction.
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffClassSetup.java`
- `CffClassSetup` already has shared-helper metadata in `CffClassKeyTable`
  and relocates large helper sets through `__neko_cff_relay$*` relays.
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffClassSetup.java`
- `invokeDynamic`, `constantObfuscation`, and `stringObfuscation` bind to CFF
  metadata and fail closed when CFF class key table or instruction state is
  missing. This is why "full minus CFF" is not a valid ablation.
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/invoke/JvmInvokeDynamicObfuscationPass.java`
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/constants/JvmConstantObfuscationPass.java`
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/strings/JvmStringObfuscationPass.java`

## Findings

### Primary runtime bottleneck

The main current runtime bottleneck is the CFF execution surface:
per-method/per-helper dispatch, material-row access, fake/poison row handling,
and relocated helper relay calls. This is proven by the ablation where the
CFF-only-stack median reproduces the full-obf obfusjack matrix slowdown:
`2459ms` vs full `2490ms`, while original is `2ms`.

This is not an `invokeDynamic`, string, or constant-only bottleneck for the
obfusjack matrix path. Removing those transforms leaves the matrix `Seq` row at
roughly the same 2.4s range.

### Secondary runtime bottleneck

For TEST Calc, full JVM obf is slower than the CFF-only stack:
`94ms` CFF-only median vs `198ms` full median. The single-run ablations show
`full-no-indy` at `126ms` and `full-no-const-string` at `175ms`, so invokedynamic
materialization and constant/string materialization both add measurable runtime
work on this fixture. This is secondary because the largest observed regression
is still the CFF-driven obfusjack matrix path.

## Plan

## Runtime Acceptance

Final runtime acceptance for this plan:

- P1 acceptance: full JVM obf obfusjack matrix `Seq` five-run median must be
  `<= 200 ms`.
- P2 acceptance: full JVM obf TEST `Calc` five-run median must be `<= 60 ms`.
- Non-target runtime rows must not regress by more than 10% relative to the
  fresh full-obf medians in this plan:
  - obfusjack Platform `85 ms`.
  - obfusjack Virtual `122 ms`.
  - obfusjack Parallel `8 ms`.
  - obfusjack VThreads `10 ms`.
- Functional validation must stay green for TEST, obfusjack, SnakeGame
  headless behavior, and evaluator.
- Any verifier, bootstrap, reflection, MethodHandle, LambdaMetafactory,
  plaintext marker, fallback, skipped transform, reduced CFF block coverage, or
  static/non-live key propagation regression fails the plan.

### Common runtime validation commands

Before accepting any runtime-performance subtask, regenerate fresh full JVM
obf artifacts with:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Then run exact five-run runtime repeats:

```bash
mkdir -p build/jvm-runtime-perf/repeats build/jvm-runtime-perf/java-tmp
for variant in original full; do
  case "$variant" in
    original)
      testjar=test-jars/test.jar
      obfjar=test-jars/test21.jar
      ;;
    full)
      testjar=build/test-jvm-full-obf-perf/test-obf.jar
      obfjar=build/test-jvm-full-obf-perf/test21-obf.jar
      ;;
  esac
  for i in 1 2 3 4 5; do
    java -XX:-UsePerfData \
      -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
      -jar "$testjar" \
      > "build/jvm-runtime-perf/repeats/${variant}-TEST-${i}.stdout.log" \
      2> "build/jvm-runtime-perf/repeats/${variant}-TEST-${i}.stderr.log"
    java -XX:-UsePerfData \
      -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
      -jar "$obfjar" \
      > "build/jvm-runtime-perf/repeats/${variant}-obfusjack-${i}.stdout.log" \
      2> "build/jvm-runtime-perf/repeats/${variant}-obfusjack-${i}.stderr.log"
  done
done
```

Median report command:

```bash
mkdir -p build/jvm-runtime-perf/repeats
{
  printf 'variant\tfixture\tmetric\tvalues_sorted_ms\tmedian_ms\n'
  metric_value() {
    metric="$1"
    file="$2"
    case "$metric" in
      Calc) rg -o 'Calc:[[:space:]]+[0-9]+ms' "$file" | rg -o '[0-9]+' ;;
      Platform) rg -o 'Platform threads:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      Virtual) rg -o 'Virtual threads:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      Seq) rg -o 'Seq:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      Parallel) rg -o 'Parallel:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      VThreads) rg -o 'VThreads:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
    esac
  }
  for variant in original full; do
    for fixture in TEST obfusjack; do
      if [ "$fixture" = TEST ]; then
        metrics='Calc'
      else
        metrics='Platform Virtual Seq Parallel VThreads'
      fi
      for metric in $metrics; do
        values=$(
          for i in 1 2 3 4 5; do
            metric_value "$metric" "build/jvm-runtime-perf/repeats/${variant}-${fixture}-${i}.stdout.log"
          done | sort -n
        )
        count=$(printf '%s\n' "$values" | sed '/^$/d' | wc -l)
        test "$count" -eq 5 || exit 1
        median=$(printf '%s\n' "$values" | sed -n '3p')
        joined=$(printf '%s\n' "$values" | paste -sd, -)
        printf '%s\t%s\t%s\t%s\t%s\n' "$variant" "$fixture" "$metric" "$joined" "$median"
      done
    done
  done
} | tee build/jvm-runtime-perf/repeats/runtime-medians.tsv
```

Optional human-readable timing dump:

```bash
for variant in original full; do
  for fixture in TEST obfusjack; do
    printf '\n%s %s\n' "$variant" "$fixture"
    for i in 1 2 3 4 5; do
      f="build/jvm-runtime-perf/repeats/${variant}-${fixture}-${i}.stdout.log"
      printf '%s ' "$(basename "$f")"
      rg 'Calc:|Platform threads|Virtual threads|Seq:|Parallel:|VThreads:' "$f" |
        sed -E 's/^[[:space:]]+//; s/[[:space:]]+/ /g' |
        tr '\n' ' '
      printf '\n'
    done
  done
done
```

Required runtime log inspection:

```bash
rg -n "Exception|Error|VerifyError|BootstrapMethodError|NoSuchMethod|NoSuchField|WrongMethodType|ClassCastException|ArrayIndexOutOfBoundsException|fallback|skip|static-decrypt|__neko_cff_didx|__neko_indy_bsm_shared|__neko_indy_register" \
  build/jvm-runtime-perf/repeats \
  build/test-jvm-full-obf-perf/*.run.stdout.log \
  build/test-jvm-full-obf-perf/*.run.stderr.log \
  build/test-jvm-full-obf-perf/*obfuscate.stdout.log \
  build/test-jvm-full-obf-perf/*obfuscate.stderr.log
```

Expected allowed runtime text remains TEST `Test 2.8: Sec ERROR` and
SnakeGame headless `java.awt.HeadlessException`. The obfusjack fixture's
expected exception-path output includes `Caught MyException: boom`, and its
obfuscation log may include class-load debug text for
`org/example/Main$MyException`. Any new unexpected hit must be resolved before
acceptance.

### P0 Baseline and plan intake

Scope:

- Keep this plan JVM-only.
- Preserve the fresh test reports and log paths as the evidence baseline.
- Do not implement runtime changes in this subtask.
- Exclude obfuscation wall-clock/build-time optimization from the plan.

Required evidence:

- Fresh `JvmFullObfuscationPerfTest` pass.
- Fresh 5-run original/CFF/full medians for TEST and obfusjack.
- Fresh ablation evidence showing CFF-only-stack reproduces the primary
  regression.
- Fresh JFR runtime hot-method evidence mapping the first CFF repair surface to
  the matrix runtime path.
- Plan-intake review result before implementation.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Completion criteria:

- Plan file exists under `.plan/`.
- Active todo system reflects planning progress.
- No runtime code changes are included in the planning commit.

Status: `[x]` plan-intake review passed after runtime-target revisions.

### P1 Bring obfusjack matrix runtime median to <= 200 ms

Scope:

- Optimize only generic CFF runtime execution overhead proven on the full
  JVM-obfuscated artifact runtime path.
- Preserve full CFF block coverage, block boundaries, fake rows, poison rows,
  material rows, hard-fail rows, dynamic live key propagation, class key tables,
  hidden method keys, packed carriers, and all JVM ABI behavior.

#### P1.1 Eliminate compile-time-constant relocated CFF relay dispatch

Scope:

- Replace `push selector; invokestatic __neko_cff_relay$N(...)` callsites with
  direct calls to the relocated helper target when the selector was introduced
  by `rewriteRelocatedCffHelperCalls` and the relocated helper is public static
  or nestmate-accessible.
- Keep relay generation only where a later source audit proves a callsite
  cannot legally call the target helper directly.
- Do not change helper bodies, CFF block partitioning, CFF material rows, fake
  rows, poison rows, or key formulas.

Required evidence:

- Existing JFR shows `a.v.__neko_cff_relay$0` consumes 42.59% of samples in a
  delayed matrix-stage `Seq: 2435 ms` CFF-only obfusjack runtime and 42.53% of
  samples in a delayed matrix-stage `Seq: 2512 ms` full-obf obfusjack runtime.
- Source evidence shows the selector is compile-time-known at every rewritten
  callsite and the relay uses `LOOKUPSWITCH`.
- Generated bytecode inspection after the change must show hot CFF helper
  callsites no longer invoke `__neko_cff_relay$0` for direct-callable targets.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ObfuscationIntegrationTest --rerun-tasks
```

Runtime target:

- Run the common runtime validation commands.
- Run a fresh JFR on full obfusjack if the full-obf `Seq` median remains above
  `200 ms`.

Completion criteria:

- Functional tests pass.
- Full-obf obfusjack `Seq` five-run median improves from `2490 ms`; if it is
  still above `200 ms`, continue to P1.2 rather than claiming P1 complete.
- No non-target row regresses by more than 10% from the fresh full-obf medians.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

Status: `[x]` implemented, validated, and implementation review passed.
Evidence:

- Targeted JVM validation command passed after the source change.
- Corrected five-run median report:
  - full TEST `Calc=195 ms`.
  - full obfusjack `Platform=88 ms`, `Virtual=90 ms`, `Seq=409 ms`,
    `Parallel=8 ms`, `VThreads=8 ms`.
- Actual `test-obf.jar` and `test21-obf.jar` bytecode inspection found no
  `__neko_cff_relay$*` methods or calls.
- Post-P1.1 full-obfusjack JFR completed with `Seq: 462 ms`; hot samples moved
  from `__neko_cff_relay$0` to `a.a.fa` and CFF helper host methods under the
  `mmulSeq` stack, so P1.2 remains required.

#### P1.2 Complete shared per-class CFF dispatch/material execution

Scope:

- For dry-run rows with `readyHelpers == currentHelpers`,
  `staticDispatchTokenRows == 0`, and all missing-row counters equal to zero,
  replace remaining per-helper CFF dispatch/material execution with a generic
  per-class shared execution route.
- The shared route must consume the same live method-entry key material,
  dispatch token rows, material words, fake rows, poison rows, and hard-fail
  rows as the current route.
- The shared route may move equivalent protected logic but must not reduce
  block coverage, merge blocks, remove fake/poison semantics, expose keys, or
  replace live key flow with static seeds.

Implementation order:

1. P1.2.1 collapses the remaining compile-time-known shared-group dispatch
   layer that P1.1 exposed. This is first because the post-P1.1 full-obfusjack
   artifact still executes `SHARED_GROUP_DESC` on the obfuscated `mmulSeq`
   (`a.a.fa`) runtime path before entering per-island material helpers.
2. P1.2.2 completes the shared per-class island execution route if P1.2.1 does
   not bring full-obf obfusjack `Seq` to `<= 200 ms`.

Required evidence:

- Post-P1.1 JFR or bytecode topology must identify the remaining dominant CFF
  runtime method(s), helper descriptor(s), and source emission path(s).
- A written invariant mapping must be added to this plan before code edit,
  comparing old and new dispatch/material inputs and outputs.
- Generated bytecode topology must show reduced per-helper dispatch/material
  call surface without reducing `realRows`, `fakeRows`, `poisonRows`, or
  `liveDispatchTokenRows` in CFF dry-run logs.

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- Run JFR on full obfusjack and confirm relay/material helpers are no longer
  the dominant samples, or record the exact remaining runtime path for the next
  subtask.

Completion criteria:

- Full-obf obfusjack `Seq` five-run median is `<= 200 ms`.
- Full-obf TEST `Calc` does not regress from the post-P1.1 median and remains
  no worse than the fresh full-obf median `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

#### P1.2.1 Collapse compile-time-known shared-group dispatch

Scope:

- Replace group-hub `push groupIndex; goto sharedGroupDispatch` with a direct
  call to that group's prepared dispatch helper.
- Remove generation/use of the `SHARED_GROUP_DESC` helper where the source
  group is compile-time-known at the hub.
- Preserve the group dispatch helper, domain router, island dispatch helpers,
  result router, fake rows, poison rows, material rows, and all live key locals.

Required evidence:

- Post-P1.1 full-obfusjack JFR shows the relay layer is gone and the
  `Seq: 462 ms` run records `81` samples with `a.a.fa(Object[], long)` on the
  stack. Of those, `41` include
  `a.ba.us(long, int, int, int, int, int, int, long[])`, and `22` include
  `a.da.ua(long, int, int, int, Object[], int, int, long[])`.
- Fresh map evidence identifies `a.a.fa([Ljava/lang/Object;J)V` as
  `org/example/Main.mmulSeq([[D[[D[[D)V`.
- Fresh generated bytecode inspection of `build/test-jvm-full-obf-perf/test21-obf.jar`
  shows `a.a.fa([Ljava/lang/Object;J)V` calls
  `a.ba.us:(JIIIIII[J)J` at bytecode offset `613`. This descriptor is
  `CffTransitionOutliner.SHARED_GROUP_DESC`, proving the shared-group dispatch
  route is on the hot `mmulSeq` runtime path.
- Source evidence shows `CffDispatchEmitter` writes a constant `groupIndex` at
  every outlined group hub before jumping to one shared dispatch label, and
  `CffTransitionOutliner.createSharedGroupDispatchHelper` immediately switches
  on that constant to call the already prepared group helper.

Invariant mapping:

| Old path | New path | Preserved invariant |
| --- | --- | --- |
| group hub stores compile-time `groupIndex` to `sharedGroupLocal` | group hub does not store `groupIndex` | group identity is still the same compile-time `IslandGroup` selected by the hub |
| group hub jumps to `sharedGroupDispatch` | group hub directly invokes `prepareGroupDispatchHelper(group, ...)` output through `emitGroupDispatchCall` | call inputs remain `key`, `guard`, `path`, `block`, `pc`, `domain`, and `out` |
| shared helper switches on `groupIndex` | no shared switch | no application block, island, fake row, poison row, or material row is removed |
| shared helper invokes the selected group helper | group hub invokes the same selected group helper | group helper body, domain router, island helpers, result router, and `out` encoding stay unchanged |
| shared helper returns the group helper's `long` key result | direct group call stores the same `long` key result | caller reloads guard/path/block/pc/domain from the same `out` array layout |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- Generated bytecode topology must show `totalCffSharedGroupDispatchCalls=0`
  for TEST and obfusjack full-obf artifacts.
- If full-obf obfusjack `Seq` remains above `200 ms`, run fresh full-obfusjack
  JFR and record the remaining runtime path before P1.2.2 code edit.

Completion criteria:

- Full-obf obfusjack `Seq` five-run median improves from the P1.1 `409 ms`
  median, or P1.2.1 is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the P1.1 `195 ms` median and the
  original plan baseline `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

Status: `[x]` implemented, validated, and implementation review passed.
Evidence:

- Targeted JVM validation command passed after the source change.
- Generated topology now reports `totalCffSharedGroupDispatchCalls=0` for TEST
  and obfusjack full-obf artifacts.
- Fresh bytecode inspection of `test-obf.jar` and `test21-obf.jar` found no
  `__neko_cff_relay$*` methods/calls and no `(JIIIIII[J)J` shared-group helper
  calls.
- Corrected five-run median report:
  - full TEST `Calc=186 ms`.
  - full obfusjack `Platform=77 ms`, `Virtual=83 ms`, `Seq=327 ms`,
    `Parallel=7 ms`, `VThreads=8 ms`.
- Post-P1.2.1 full-obfusjack JFR completed with `Seq: 331 ms`. Hot samples no
  longer include `SHARED_GROUP_DESC`; the remaining `a.a.fa` stack contains
  per-island helpers such as `a.ba.*:(JIIIII[J)J` and material helper
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J`, so P1.2.2 remains required.

#### P1.2.2 Complete shared island execution route

Scope:

- If P1.2.1 does not reach P1 acceptance, continue reducing the remaining
  outlined island dispatch execution in dependency order.
- P1.2.2a first removes the proven runtime wrapper layer where a group helper
  switches on `domain` only to invoke an already prepared island helper. This
  keeps the existing per-island helper semantics unchanged and removes one hot
  helper frame before attempting a wider semantic interpreter switch.
- If P1.2.2a does not bring full-obf obfusjack `Seq` to `<= 200 ms`, P1.2.2b
  attempts the semantic shared island interpreter for rows satisfying the
  dry-run readiness constraints.
- Preserve every row and token category listed in the parent P1.2 scope.

Required evidence before editing:

- Post-P1.2.1 JFR hot-method report and generated topology identifying the
  remaining dominant island/material helper path.
- A new invariant mapping for the shared island helper inputs, table rows,
  return `out` words, fake bounce behavior, and poison behavior.

Post-P1.2.1 evidence:

- Corrected five-run median after P1.2.1 still reports full-obf obfusjack
  `Seq=327 ms`, above the P1 `<= 200 ms` target.
- Fresh full-obfusjack JFR completed with `Seq: 331 ms`.
- Filtering execution samples containing `a.a.fa(Object[], long)` reports
  `fa_samples=66`, `ba_any_under_fa=39`, `da_any_under_fa=18`, and
  `indy_under_fa=0`.
- Sample stacks under the obfuscated `mmulSeq` path include
  `a.ba.bs(long, int, int, int, int, int, long[])` above
  `a.ba.cs(long, int, int, int, int, int, long[])` above
  `a.a.fa(Object[], long)`. This proves the remaining hot CFF path still pays
  a group-helper frame that immediately enters an island helper.
- Generated topology for obfusjack after P1.2.1 reports
  `totalCffOutlinedDispatchCalls=514`, `totalCffSharedGroupDispatchCalls=0`,
  `totalCffTransitionMaterialCalls=391`, `totalCffStepMaterialCalls=463`,
  and `totalCffIslandMaterialCalls=347`.
- The same topology reports obfusjack readiness sums
  `readyHelpers=275`, `currentHelpers=275`, `projectedSharedHelpers=6`,
  `projectedHelperReduction=269`, `liveDispatchTokenRows=347`,
  `staticDispatchTokenRows=0`, `realRows=347`, `fakeRows=188`,
  `poisonRows=550`, `missingFakeStepRows=0`, `missingPoisonStepRows=0`,
  `missingBounceRows=0`, `missingFakeSourceKeyProofRows=0`, and
  `semanticSwitchBlockedFakeRows=0`.
- The hot obfuscated `mmulSeq` method itself reports
  `method=a/a.fa([Ljava/lang/Object;J)V`, `currentHelpers=19`,
  `projectedSharedHelpers=1`, `projectedHelperReduction=18`,
  `liveDispatchTokenRows=20`, `staticDispatchTokenRows=0`, `realRows=20`,
  `fakeRows=41`, `poisonRows=19`, and no missing fake/poison/bounce rows.

P1.2.2a invariant mapping:

| Old path | New path | Preserved invariant |
| --- | --- | --- |
| group hub calls a generated group helper with `key`, `guard`, `path`, `block`, `pc`, `domain`, and `out` | group hub emits the same `domain` switch at the hub callsite | dispatch inputs and live key locals are unchanged |
| group helper switches on `domainToken(group.salt(), island)` and optional direct-island domain tokens | inline hub switch uses the same domain token set | selected island identity is unchanged |
| selected group-helper case invokes that island's existing helper | selected inline case invokes the same prepared island helper | real dispatch rows, fake rows, poison rows, material rows, and helper body semantics are unchanged |
| group helper poison case writes the group poison result token to `out` and returns the live key | inline poison case writes the same `out` words and routes to the same result router | poison routing, dense/sparse result masking, and `out[0..2]` layout stay unchanged |
| caller reloads `guard`, `path`, `block`, `pc`, `domain`, and result token from `out` after the group helper returns | each inline case reloads from the same `out` layout before jumping to the same router | result router inputs and downstream block labels are unchanged |

P1.2.2a implementation dependency:

- The existing island helpers must still be created through
  `createIslandDispatchHelper`, and each generated helper must still publish
  its flow-key metadata through the existing helper flow-key path.
- The inline group-domain switch may move only the wrapper dispatch layer. It
  must not rewrite, skip, merge, or reinterpret island helper bodies, fake
  cases, poison cases, material-row decode, or result-router token assignment.
- The direct-island entry domain-token aliases recorded by
  `needsGroupedIslandEntry` must be preserved in the same domain switch.

P1.2.2a validation target:

- Same targeted JVM command as P1.1.
- Fresh generated topology must record medians and dry-run row counts after the
  change.
- Bytecode/JFR inspection must prove the changed path no longer executes a
  `group helper -> island helper` wrapper chain on the full-obfusjack
  `mmulSeq` stack. If `Seq` remains above `200 ms`, record the exact remaining
  CFF runtime path before P1.2.2b.

P1.2.2a completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Generated topology/bytecode inspection shows the group-domain wrapper call
  path is removed or no longer on the hot full-obfusjack `mmulSeq` runtime
  stack.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.1 artifact.
- Full-obf obfusjack `Seq` improves from the P1.2.1 `327 ms` median or the
  source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.2.2a status: `[x]` measured, rejected, and reverted before P1.2.2b.
No implementation commit was made for this branch.

P1.2.2a rejection evidence:

- Inline group-domain switch without a method-size gate failed the fresh
  targeted JVM validation during full-obfusjack generation with
  `MethodTooLargeException: Method too large: a/a.main ([Ljava/lang/String;)V`.
  The fresh finalizer log estimated `a/a.main` at `79101` bytes and
  `<clinit>` at `61104` bytes, proving the generic inline wrapper removal can
  exceed the JVM method-size limit on current full-obfusjack topology.
- A generic size-gated version preserved current group-helper routing for
  materialized direct-island paths and passed the same targeted JVM validation.
  Its generated topology still preserved coverage: obfusjack reported
  `totalCffSharedGroupDispatchCalls=0`, `totalCffTransitionMaterialCalls=391`,
  `totalCffStepMaterialCalls=451`, `totalCffIslandMaterialCalls=347`,
  `readyHelpers=275`, `currentHelpers=275`, `projectedHelperReduction=269`,
  `liveDispatchTokenRows=347`, `staticDispatchTokenRows=0`, `realRows=347`,
  `fakeRows=176`, `poisonRows=550`, and no missing-row blockers.
- The size-gated version failed P1.2.2a runtime acceptance. Fresh five-run
  medians were full TEST `Calc=224 ms`, full obfusjack `Platform=87 ms`,
  `Virtual=85 ms`, `Seq=326 ms`, `Parallel=8 ms`, and `VThreads=8 ms`.
  This violates the P1.2.2a criteria because TEST Calc regressed beyond the
  `198 ms` baseline and obfusjack `Seq` did not improve from the P1.2.1
  `327 ms` median.
- The source change was fully reverted to the P1.2.1 committed shape before
  continuing. P1.2.2b must therefore attack the remaining island execution
  cost through the semantic shared-interpreter route, not through the rejected
  group-wrapper inline route.

P1.2.2b execution split:

1. P1.2.2b.1 first removes the remaining hot real-hit material decode helper
   frame by caching the caller class-owned island material arrays inside each
   existing island helper and inlining the exact live material-word decode
   formula for the real-result word. This is a narrower semantic-preserving
   prerequisite because the post-P1.2.1 JFR records `a.da.ua` island material
   helper samples on the obfuscated `mmulSeq` stack, while the active runtime
   path currently decodes only one live real-result word from the compressed
   island material row.
2. P1.2.2b.2 attempts the full semantic shared island interpreter only if
   P1.2.2b.1 does not reach P1. The full interpreter must first consume the
   remaining real/fake/poison/router material without exposing static material
   seeds or weakening fake/poison coverage.

P1.2.2b.1 scope:

- Change only the generated island helper body emitted by
  `createIslandDispatchHelper`.
- Preserve the existing group helper, island helper count, dispatch token
  switch, fake source router, fake bounce, poison path, compressed material
  storage, runtime-source bucket selection, result router, hidden key ABI, and
  `out[0..2]` layout.
- Replace `emitCompressedIslandMaterialWordDecode -> __neko_cff_imat$` only
  for the existing live-decoded real-result word with an inline copy of the
  same live decode formula, using the same live `key`, `guard`, `path`,
  `block`, source-adjusted cursor, class-owned `Object[]` carrier, island
  material `int[]`, and class key words.
- Do not decode static-mask material fields, add static seed constants, rewrite
  fake/poison rows, change CFF block construction, or remove material rows.

P1.2.2b.1 required evidence:

- Post-P1.2.1 JFR already places `a.da.ua` under `a.a.fa(Object[], long)`,
  proving the material decode helper is on the full-obfusjack `mmulSeq`
  runtime path.
- Source audit shows the current island helper calls
  `emitCompressedIslandMaterialWordDecode` only for `realResultWordIndexes`
  before `finishOutlinedDispatchReturnFromLocal`; fake and poison paths use
  separate `emitMaterializedStepKeys` and bounce/poison emitters.
- Source audit shows `installCompressedIslandMaterialHelper` decodes a word
  from the class-owned island material entry with live `key`, `guard`, `path`,
  `block`, source-adjusted cursor, word index, and `CLASS_KEY_WORDS_SLOT`.

P1.2.2b.1 validation target:

- Same targeted JVM command as P1.1.
- Generated topology must show CFF row counts and helper coverage are unchanged
  except for instruction counts expected from inlining and the expected
  decrease in island-material helper call sites on the real-result path.
- Bytecode/JFR inspection must show the real-result path no longer invokes the
  island material helper from the hot full-obfusjack `mmulSeq` stack. If `Seq`
  remains above `200 ms`, record the remaining hot CFF path before P1.2.2b.2.

P1.2.2b.1 completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the P1.2.1 `327 ms` median or the
  source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.1 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.2.2b.1 status: `[x]` measured, rejected, and reverted before P1.2.2b.2.
No implementation commit was made for this branch.

P1.2.2b.1 rejection evidence:

- The inline real-result material decode implementation passed the fresh
  targeted JVM validation command for the CFF algebraic audit, strong-entry
  seed regression, full JVM obfuscation performance test, invokedynamic,
  constant, string, method-parameter, renamer, and obfuscation integration
  tests. Gradle reported `BUILD SUCCESSFUL` with `19 executed` tasks.
- The same implementation failed runtime acceptance. Fresh five-run medians
  were original TEST `Calc=10 ms`, original obfusjack `Seq=2 ms`, full TEST
  `Calc=210 ms`, full obfusjack `Platform=81 ms`, `Virtual=84 ms`,
  `Seq=327 ms`, `Parallel=7 ms`, and `VThreads=8 ms`.
- This violates the P1.2.2b.1 criteria because full TEST `Calc` regressed
  beyond the original plan baseline `198 ms`, and full obfusjack `Seq` did not
  improve from the P1.2.1 `327 ms` median.
- The source change was fully reverted to the P1.2.2b.1 plan-checkpoint shape.
  The next accepted implementation attempt must therefore target a different
  remaining CFF runtime path; inlining only the island-material helper real
  result decode is not sufficient.

P1.2.2b.2 revised scope:

- Before attempting the full shared interpreter, remove the repeated
  `Thread.currentThread()` / `System.identityHashCode(Thread)` work now proven
  at the top of the hot island helper path.
- Expand the existing generated transition scratch `long[] out` from 3 to 4
  slots and store a cached current-thread identity word in `out[3]` when the
  CFF method initializes the scratch array.
- Change island helper runtime-source emission to consume `out[3]` instead of
  calling `Thread.currentThread()` and `System.identityHashCode()` at every
  island helper entry. The runtime-source value must still be mixed with live
  method key, guard, path, block, pc, and domain state at each helper entry.
- Preserve the helper ABI `(JIIIII[J)J`, CFF block construction, group/island
  helper topology, fake/poison behavior, material rows, hidden method keys, and
  `out[0..2]` transition result layout.
- Do not cache guard/path/block/pc/domain, decoded material rows, dispatch
  tokens, static seeds, or descriptor-derived values. Do not use ThreadLocal
  rescue state, fallback, skip behavior, or original-bytecode paths.

P1.2.2b.2 required evidence:

- Fresh post-revert JFR on full-obfusjack completed with `Seq: 335 ms`.
  Filtering execution samples to the obfuscated `mmulSeq` method
  `a.a.fa(Object[], long)` reports `fa=37`, `ba_under_fa=23`, and
  `da_under_fa=8`, proving the remaining Seq hot path is still CFF helper
  execution.
- Fresh bytecode inspection of a sampled helper, for example
  `a.ba.bs:(JIIIII[J)J`, shows the helper begins by executing
  `Thread.currentThread()` and `System.identityHashCode(Object)` before mixing
  live key/control/pc/domain state to select the island material runtime-source
  bucket.
- Source inspection shows `emitInitTransitionOut` allocates only the generated
  transition scratch array and existing readers/writers use `out[0]`,
  `out[1]`, and `out[2]` for guard/path, block/domain, and result token. Adding
  `out[3]` is internal to generated CFF helper ABI and does not alter external
  JVM ABI.

P1.2.2b.2 validation target:

- Same targeted JVM command as P1.1.
- Generated bytecode inspection must show hot island helpers no longer contain
  `Thread.currentThread()` / `System.identityHashCode()` at helper entry, while
  the method entry scratch initialization still computes and stores current
  thread identity into `out[3]`. Inspection must also show the helper still
  mixes `out[3]` with live method key, guard, path, block, pc, and domain state.
- Generated topology must preserve CFF helper coverage and dry-run row counts.

P1.2.2b.2 completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the P1.2.1 `327 ms` median or the
  source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.1 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.2.2b.2 status: `[x]` implemented, validated, and implementation review
passed. This is accepted only as a generic incremental P1 optimization. It
does not complete P1 because full-obf obfusjack `Seq` remains above the
`<= 200 ms` target.

P1.2.2b.2 evidence:

- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL` and `19 executed` tasks.
- Fresh five-run medians after regenerating the full JVM-obf artifacts were
  original TEST `Calc=10 ms`, original obfusjack `Seq=2 ms`, full TEST
  `Calc=180 ms`, full obfusjack `Platform=89 ms`, `Virtual=84 ms`,
  `Seq=312 ms`, `Parallel=7 ms`, and `VThreads=8 ms`.
- This improves full-obf obfusjack `Seq` from the P1.2.1 `327 ms` median and
  keeps full TEST `Calc` under the original plan baseline `198 ms`; P1 remains
  open because `312 ms` is still above the P1 `<= 200 ms` target.
- Fresh generated topology for obfusjack preserved the P1.2.1 CFF surface:
  `totalCffOutlinedDispatchCalls=514`, `totalCffSharedGroupDispatchCalls=0`,
  `totalCffTransitionMaterialCalls=391`, `totalCffStepMaterialCalls=451`,
  `totalCffIslandMaterialCalls=347`, `readyHelpers=275`,
  `currentHelpers=275`, `projectedHelperReduction=269`,
  `liveDispatchTokenRows=347`, `staticDispatchTokenRows=0`,
  `realRows=347`, `fakeRows=176`, `poisonRows=550`, and all missing-row
  counters remain `0`.
- Fresh generated bytecode inspection of obfuscated `mmulSeq`
  `a/a.fa([Ljava/lang/Object;J)V` shows the method initializes `long[4]`,
  stores `Thread.currentThread()` / `System.identityHashCode(Object)` into
  `out[3]`, and then passes that same scratch array to CFF island helpers.
- Fresh generated bytecode inspection of sampled hot island helper
  `a/ba.bs:(JIIIII[J)J` shows no `Thread.currentThread()` or
  `System.identityHashCode(Object)` call at helper entry. The helper loads
  `out[3]` with `LALOAD`, converts it with `L2I`, and still mixes it with live
  `key`, `guard`, `path`, `block`, `pc`, and `domain` locals.
- The required runtime/log scan found only expected text: SnakeGame headless
  `HeadlessException`, obfusjack `Caught MyException: boom`, and class-load
  debug for `org/example/Main$MyException`.
- Because full-obf obfusjack `Seq` remains above `200 ms`, a fresh post-change
  full-obfusjack JFR was captured at
  `build/jvm-runtime-perf/jfr/post-p1-2-2b-2-full-obfusjack-matrix.jfr`. The
  run completed with `Seq: 334 ms`, `Parallel: 9 ms`, `VThreads: 9 ms`, and
  `=== All tests completed ===`.
- The post-change JFR still records the remaining `mmulSeq` stack through
  `a.a.fa(Object[], long)`. Filtered execution samples report `fa=45`,
  `ba_under_fa=19`, `da_ua_under_fa=17`, `da_za_under_fa=18`, and
  `indy_under_fa=45`, proving the next P1 work must address the remaining
  generic CFF material/helper execution and full-obf invokedynamic entry cost
  on the same runtime path rather than the removed thread-identity acquisition.
- Subagent implementation review passed with no blockers. The review confirmed
  the diff is generic, preserves helper ABI and `out[0..2]`, keeps `out[3]`
  internal, preserves live `key/guard/path/block/pc/domain` mixing, introduces
  no fallback/skip/static-seed behavior, and supports retaining the subtask
  without claiming P1 completion.

P1.2.2b shared-interpreter invariant mapping, if P1.2.2a is insufficient:

| Current per-island helper surface | Shared interpreter surface | Preserved invariant |
| --- | --- | --- |
| helper ABI is `(JIIIII[J)J` with `key`, `guard`, `path`, `block`, `pc`, `domain`, and `out` | interpreter consumes the same live locals plus the protected island material cursor | method-entry key and CFF state remain live inputs |
| real cases use `emitTokenDispatch` with live mask and static generated case labels | real rows consume live-decoded dispatch token, raw pc token, dispatch-mask words, result token, result mask, and key-state words from class-owned island material | real block identity, dispatch token uniqueness, and result-router token are unchanged |
| real case stores result token through `finishOutlinedDispatchReturnFromLocal` | interpreter writes the same result token or masked result word into `out[2]` | dense and sparse result routers observe the same token space |
| miss path runs `emitDynamicFakeSourceRouter` and then fake key-step/bounce logic | fake rows must be selected from the same live source router proof and consume the stored fake step, bounce token, bounce domain token, result mask, bounce key state, and method/domain seed material | fake rows are not skipped and do not require static fake-token dispatch |
| poison path materializes poison step keys and writes poison result | poison rows consume the stored poison step material and write the same poison result behavior | hard-fail/poison rows remain present and fail closed |
| helper returns the updated `long` key and encodes `out[0]`, `out[1]`, `out[2]` | interpreter returns the updated `long` key and encodes the same `out` words | caller/router ABI is unchanged |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- Run fresh full-obfusjack JFR if `Seq` remains above `200 ms`.

Completion criteria:

- Full-obf obfusjack `Seq` five-run median is `<= 200 ms`.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

#### P1.3 candidate rejected: retain multiple live-flow invokedynamic targets

Status: `[x]` measured, plan-intake rejected, and not implemented.

Rejected scope:

- The candidate was to change only
  `JvmInvokeDynamicObfuscationPass.emitInstallGuardedTarget` so a resolved
  invokedynamic live-flow target would install
  `guardWithTest(flow, resolvedTarget, previousCallsiteTarget)` instead of
  `guardWithTest(flow, resolvedTarget, initialResolverMissHandle)`.

Rejection evidence:

- Subagent plan-intake review failed before implementation because the evidence
  did not prove that the same hot callsite repeatedly observes a finite set of
  multiple live flows. The recorded evidence only proved that the resolver
  frame is on the post-P1.2.2b.2 `mmulSeq` stack and that the current source
  uses the initial resolver miss handle as the guard false target.
- A fresh `IndyCacheProbe` against
  `build/test-jvm-full-obf-perf/test21-obf.jar` completed the full obfusjack
  runtime with `Seq: 311 ms`, `Parallel: 7 ms`, `VThreads: 8 ms`, and
  `=== All tests completed ===`. It reported `INDY_CACHE size=911`,
  `liveLongKeys=379`, `resolverMissKeys=379`, and live value counts including
  `(long)AtomicLong=5` and `(Object,long,long)long=7`. This proves live cache
  population exists, but it does not prove repeated multi-flow reuse at one hot
  callsite; the descriptor counts can be explained by multiple callsites with
  those descriptors.
- Fresh bytecode inspection of obfuscated `mmulSeq` shows hot invokedynamic
  sites such as `#340` with descriptor `(J)AtomicLong` and `#341` with
  descriptor `(Object,JJ)J`, but the inspected cache data still does not
  distinguish per-callsite repeated flow reuse from multiple distinct
  callsites.
- A fresh same-JVM repeat probe invoked obfuscated `a.a.main(String[])` twice
  through reflection. Run 1 reported `Seq: 309 ms`, `Parallel: 7 ms`,
  `VThreads: 9 ms`, and elapsed `1856 ms`. Run 2 reported `Seq: 329 ms`,
  `Parallel: 7 ms`, `VThreads: 7 ms`, and elapsed `1018 ms`. The lower whole
  run elapsed is attributable to warm class/JIT/setup state, while the target
  `Seq` row did not improve.

Conclusion:

- The guard-chain candidate remains a plausible future optimization only if a
  later probe proves repeated multi-flow reuse at the same hot callsite.
  Current evidence does not justify changing the invokedynamic resolver.
- No runtime source changes were made for this candidate. P1 continues through
  the remaining CFF material/helper execution surface proven by the
  post-P1.2.2b.2 JFR (`ba_under_fa=19`, `da_ua_under_fa=17`,
  `da_za_under_fa=18`).

#### P1.4 Add a no-domain direct-island transition material helper

Scope:

- Optimize only the generic CFF transition-material helper path for
  `EdgeKind.DIRECT_ISLAND` transitions.
- Add a direct-island transition-material helper variant that decodes and
  publishes only the values the direct-island caller consumes: guard, path,
  block, pc, and method key. It must not decode or write the target domain
  word.
- Route only direct-island materialized transition callsites to the new helper.
  All non-direct transitions keep the existing transition-material helper and
  descriptor.
- Preserve transition material row registration, row encryption/masking,
  method-key update, CFF block construction, island helper topology, hidden
  method keys, packed carriers, fake/poison coverage, and `out[0]` /
  `out[1]` layout.
- Do not remove domain material from the stored row, change domain-token
  semantics, special-case a method/class/fixture, expose row words, or add any
  fallback/skip/original-bytecode path.

Dependency/order reason:

- P1.3 was rejected before implementation because invokedynamic guard-chain
  reuse was not proven at the hot callsite. The latest amplified JFR instead
  proves that CFF material helpers still dominate the target `mmulSeq` stack.
- This subtask is smaller than the full shared island interpreter and removes
  a source-proven unused decode from the currently hot transition helper
  without changing island dispatch semantics.

Required evidence:

- A fresh same-JVM 8-run full-obfusjack JFR completed all runs. The target
  `Seq` rows were `307`, `313`, `315`, `323`, `309`, `310`, `323`, and
  `308 ms`, proving P1 remains open in the current artifact.
- Filtering the amplified execution samples to stacks containing obfuscated
  `mmulSeq`, `a.a.fa(Object[], long)`, reports `fa_total=484`,
  `da_ua_under_fa=165`, `da_za_under_fa=207`, and `ba_under_fa=227`. The top
  frames under `fa` include `a.da.ua(...)` with `165` samples and
  `a.da.za(...)` with `207` samples.
- Fresh bytecode inspection of `a.a.fa(Object[], long)` reports `22`
  transition-material helper calls to `a.da.ua:(JIII[Ljava/lang/Object;II[J)J`.
  Only `2` of those calls reload `out[2]` after the helper; `20` do not reload
  the domain word.
- Source inspection shows `CffTransitionOutliner.emitCall` already calls
  `emitTransitionOutLoads(..., edgeKind != EdgeKind.DIRECT_ISLAND)`, so a
  direct-island caller intentionally does not consume the helper's decoded
  domain value.
- Source inspection shows `installTransitionMaterialHelper` always decodes
  `TRANSITION_MATERIAL_DOMAIN_WORD` and calls
  `emitTransitionOutStores(..., true)`, so the current direct-island path pays
  for a decoded domain value that the caller does not read.

Invariant mapping:

| Current direct-island transition path | New direct-island transition path | Preserved invariant |
| --- | --- | --- |
| callsite passes live key, guard, path, block, object material, row, domain, and out | callsite passes live key, guard, path, block, object material, row, and out | live key/control state and encrypted row identity remain the inputs |
| helper decodes guard, path, block, pc, method key high/low, and domain | helper decodes guard, path, block, pc, and method key high/low only | every value consumed by the direct-island caller is unchanged |
| helper writes `out[0]`, `out[1]`, and `out[2]` high domain | helper writes `out[0]` and `out[1]` only | caller reads the same guard/path/block/pc words and still does not read domain |
| non-direct transition helper handles domain-bearing paths | unchanged | hub/router/handler domain transfer semantics stay on the existing path |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- If full-obf obfusjack `Seq` remains above `200 ms`, run a fresh amplified
  full-obfusjack JFR and record whether `a.da.ua(...)` samples under
  `a.a.fa(Object[], long)` decrease.

Completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the P1.2.2b.2 `312 ms` median, or
  the source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the current P1.2.2b.2
  before-edit median `180 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- Generated bytecode inspection shows direct-island transition material
  callsites use the no-domain helper and do not reload `out[2]`, while
  non-direct transition material callsites still use the original helper.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.2b.2 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.4 status: `[x]` measured, rejected, and reverted. No implementation commit
was made for this branch.

P1.4 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  obfuscated `mmulSeq`, `a.a.fa(Object[], long)`, kept `2` calls to the
  original domain-bearing transition-material helper
  `(JIII[Ljava/lang/Object;II[J)J` and used the no-domain helper
  `(JIII[Ljava/lang/Object;[JI)J` for the other `20` direct-island calls.
  The no-domain helper contained only two `LASTORE` writes and did not write
  `out[2]`.
- The first fresh five-run runtime gate rejected the implementation. Full
  TEST `Calc` sorted values were `202,203,203,203,210` with median `203 ms`.
  Full obfusjack `Seq` sorted values were `290,295,297,305,314` with median
  `297 ms`.
- Because TEST timing is noisy, a fresh user-requested ten-run gate was run on
  the same artifact. Full TEST `Calc` sorted values were
  `201,201,202,206,206,207,207,210,215,216`, with lower/upper medians
  `206/207 ms`. Full obfusjack `Seq` sorted values were
  `290,291,295,297,299,301,303,304,304,305`, with lower/upper medians
  `299/301 ms`.
- The optimization improved full-obf obfusjack `Seq` from the P1.2.2b.2
  `312 ms` median, but it consistently regressed full TEST `Calc` beyond the
  current P1.2.2b.2 before-edit median `180 ms`. This violates P1.4 completion
  criteria, so the source change was fully reverted before continuing.
- The next P1 attempt must not reuse the no-domain direct-island transition
  helper unless it first explains and removes the Calc regression generically.

#### P1.5 Mark direct-island transition rows on the existing helper ABI

Scope:

- Optimize the same generic CFF direct-island transition-material path proven
  by P1.4, but keep the existing transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J` and helper count.
- For direct-island materialized transition callsites only, set a private
  high-bit flag on the row argument before invoking the existing helper.
- In the existing transition-material helper, read and clear that row high-bit
  flag before row-index scaling. When the flag is set, decode guard, path,
  block, pc, and method key exactly as before, but skip
  `TRANSITION_MATERIAL_DOMAIN_WORD` decode and write only `out[0]` / `out[1]`.
  When the flag is clear, execute the current domain-bearing path unchanged.
- Preserve transition material row registration, row encryption/masking,
  method-key update, CFF block construction, island helper topology, hidden
  method keys, packed carriers, fake/poison coverage, and non-direct domain
  semantics.
- Do not add a new helper method or descriptor, remove domain material from the
  stored row, change domain-token semantics, special-case a method/class/
  fixture, expose row words, or add fallback/skip/original-bytecode behavior.

Dependency/order reason:

- P1.4 proved the direct-island domain decode is generated and unconsumed on
  the hot `mmulSeq` path, and that removing it can reduce full-obfusjack
  `Seq`, but P1.4 also introduced a second transition-material helper and
  regressed TEST `Calc`.
- P1.5 keeps the original helper ABI and helper count while removing only the
  unconsumed direct-island domain decode. It therefore tests the same proven
  runtime invariant without the rejected P1.4 helper-surface change.

Required evidence:

- The freshly regenerated post-P1.4-revert full-obfusjack artifact completed a
  same-JVM 8-run JFR probe with target `Seq` rows `310`, `308`, `306`, `305`,
  `319`, `308`, `306`, and `305 ms`.
- Filtering that fresh JFR to stacks containing obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, reports `fa_total=474`, `da_za_under_fa=240`,
  `da_ua_under_fa=149`, and `ba_under_fa=260`, proving the transition helper
  remains on the accepted post-revert hot path.
- P1.4 bytecode inspection before revert proved the current obfuscated
  `mmulSeq` direct-island shape: `22` transition-material helper callsites,
  with only `2` reloading `out[2]` afterward. The other `20` direct-island
  callsites consumed only `out[0]` / `out[1]`.
- Source inspection shows `emitTransitionOutLoads(..., edgeKind !=
  EdgeKind.DIRECT_ISLAND)` still intentionally avoids loading the domain word
  for direct-island transitions, while `installTransitionMaterialHelper`
  currently decodes `TRANSITION_MATERIAL_DOMAIN_WORD` and stores `out[2]`
  unconditionally.
- Source inspection shows `TRANSITION_MATERIAL_TABLE_SIZE` is `16_384` in
  `CffSharedState`, and `registerTransitionMaterialRow` increments
  `transitionMaterialCounter` then hard-fails when
  `index >= TRANSITION_MATERIAL_TABLE_SIZE`. Therefore every real transition
  material row index is below `0x4000`, and a private `0x80000000` callsite
  flag can be stripped before scaling without colliding with any real row
  identity.
- P1.4 rejection evidence requires avoiding the extra no-domain helper
  descriptor unless the Calc regression is explained and removed. P1.5
  therefore forbids adding that helper descriptor and changes only the existing
  helper's internal row-flag path.

Invariant mapping:

| Current transition path | P1.5 transition path | Preserved invariant |
| --- | --- | --- |
| direct and non-direct callsites invoke the same helper descriptor | unchanged | helper ABI and generated helper count do not change |
| row argument is a table index below `TRANSITION_MATERIAL_TABLE_SIZE` | direct-island callsites pass the same row index with a private high-bit flag; helper clears the flag before scaling | material row identity and row table lookup are unchanged |
| helper always decodes guard/path/block/pc/method/domain | helper decodes domain only when the high-bit flag is clear | every caller-consumed value is unchanged; non-direct domain semantics remain unchanged |
| direct-island caller reads `out[0]` and `out[1]`, not `out[2]` | unchanged | direct-island routing observes the same guard/path/block/pc and method key |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- If full-obf obfusjack `Seq` remains above `200 ms`, run a fresh full
  obfusjack JFR and record the remaining `a.da.ua(...)` samples under
  `a.a.fa(Object[], long)`.

Completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the current post-revert `312 ms`
  median, or the source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the current P1.2.2b.2
  before-edit median `180 ms`.
- Generated bytecode inspection shows no new transition-material helper
  descriptor was added, direct-island callsites still invoke the original
  helper descriptor with flagged row constants, and the helper clears the flag
  before indexing the row.
- Non-direct transition callsites still pass unflagged row constants and still
  decode/store domain.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.2b.2 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.5 status: `[x]` measured, rejected, and reverted. No implementation commit
was made for this branch.

P1.5 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated
  without adding the P1.4 rejected helper descriptor: obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, still invoked the original transition-material
  helper descriptor `(JIII[Ljava/lang/Object;II[J)J`; `20` direct-island
  call windows used flagged row constants; and the helper bytecode cleared the
  high-bit flag with `Integer.MAX_VALUE` before row scaling.
- A fresh user-requested ten-run runtime gate rejected the implementation.
  Full TEST `Calc` sorted values were
  `177,178,178,180,181,182,183,184,185,193`, with lower/upper medians
  `181/182 ms`. Full obfusjack `Seq` sorted values were
  `362,362,364,364,365,373,374,374,375,378`, with lower/upper medians
  `365/373 ms`.
- The optimization regressed full-obf obfusjack `Seq` from the current
  accepted post-P1.2.2b.2 median `312 ms` and did not keep TEST `Calc` at or
  below the current before-edit median `180 ms`. This violates P1.5 completion
  criteria, so the source change was fully reverted before continuing.
- Runtime/log scanning reported only the expected SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- The next P1 attempt must not reuse row-flagged direct-island transition
  domain skipping unless it first explains why the existing-helper branch
  shape regresses `Seq` and `Calc` generically.

#### P1.6 Pre-scale transition-material row offsets at the call boundary

Status: `[x]` measured, rejected, and reverted. No implementation commit was
made for this branch.

Scope:

- Optimize only the generic CFF transition-material helper row-index boundary.
- Keep the existing transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J` and helper count.
- Change the helper's `row` argument contract from a transition-material row
  number to the already-scaled material-word offset.
- For every generated transition-material helper callsite, pass
  `registeredRow * TRANSITION_MATERIAL_ROW_WORDS`.
- Remove the helper-entry `row * TRANSITION_MATERIAL_ROW_WORDS` multiplication
  and continue using the row local as the material array word offset.
- Preserve transition material row registration, row encryption/masking,
  method-key update, CFF block construction, island helper topology, hidden
  method keys, packed carriers, fake/poison coverage, direct/non-direct
  domain semantics, and helper ABI.
- Do not special-case a method/class/fixture, remove domain decode, add a new
  helper descriptor, expose row words, or add fallback/skip/original-bytecode
  behavior.

Subtasks:

1. `[x]` Record and review the row-offset contract change.
   Evidence requirement: current accepted 10-run runtime baseline, fresh
   full-obfusjack JFR attribution, bytecode evidence for the helper-entry
   multiplication, and source evidence that all current transition-material
   helper callsites are generated from registered rows.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passed; the plan
   checkpoint must be committed before source edits.
2. `[x]` Implement, validate, measure, reject, and revert the row-offset
   boundary change.
   Evidence requirement: source diff shows only the helper-entry scaling and
   generated callsite row constants changed; bytecode inspection shows
   `a.da.ua` no longer contains the entry `bipush 37; imul`, while obfuscated
   `mmulSeq` still calls the original helper descriptor.
   Validation target: same targeted JVM command as P1.1, plus the common
   runtime validation commands with a 10-run gate.
   Completion criteria: targeted JVM validation passes; full-obf obfusjack
   `Seq` improves from the current accepted 10-run lower/upper median
   `318/320 ms`, or the source change is reverted before proceeding; full-obf
   TEST `Calc` remains no worse than the current accepted 10-run lower/upper
   median `182/183 ms`; no CFF dry-run row count or missing-row blocker
   regresses; no forbidden runtime/log/marker scan hit is introduced; subagent
   implementation review passes before commit.

Dependency/order reason:

- P1.4 and P1.5 both targeted unconsumed direct-island domain decode and were
  rejected, so the next P1 attempt must not reuse that path.
- The fresh accepted-source 10-run gate after the P1.5 revert still leaves P1
  open: full TEST `Calc` sorted values were
  `179,180,181,181,182,183,183,184,185,185` with lower/upper medians
  `182/183 ms`, and full obfusjack `Seq` sorted values were
  `306,310,314,318,318,320,321,322,326,334` with lower/upper medians
  `318/320 ms`.
- A fresh same-JVM 8-run full-obfusjack JFR on the accepted-source artifact
  completed with `Seq` rows `313`, `311`, `326`, `305`, `321`, `337`,
  `319`, and `323 ms`.
- Filtering that JFR to stacks containing obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, reports `fa_total=493`,
  `a.da.ua(...)=208`, `a.da.za(...)=193`, and `a.b.d(...)=154`, proving the
  transition-material helper remains one of the current hot P1 runtime paths.
- Fresh bytecode inspection of `a.da.ua` shows every invocation enters with:
  `iload row; bipush 37; imul; istore row`, before the helper reads any
  transition-material words.
- Fresh bytecode inspection of obfuscated `mmulSeq` shows `22` calls to the
  original transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J`.
- Source inspection shows the current material transition path in
  `CffTransitionOutliner.emitCall` always registers a transition-material row
  through `registerTransitionMaterialRow` before invoking the shared helper,
  and the helper-wrapper path in `createHelper` does the same. Source
  inspection also shows `registerTransitionMaterialRow` stores each row at
  `index * TRANSITION_MATERIAL_ROW_WORDS`, while the helper currently repeats
  the same multiplication at runtime.

Invariant mapping:

| Current transition-material path | P1.6 path | Preserved invariant |
| --- | --- | --- |
| callsite passes registered row index | callsite passes `registered row index * TRANSITION_MATERIAL_ROW_WORDS` | selected material row is unchanged |
| helper multiplies row index by `TRANSITION_MATERIAL_ROW_WORDS` before material loads | helper uses the already-scaled word offset directly | every material word load addresses the same array element |
| helper descriptor and argument order are `(JIII,Object[],int,int,long[])` | unchanged | JVM ABI surface and generated helper count stay unchanged |
| transition row storage uses `index * TRANSITION_MATERIAL_ROW_WORDS` | unchanged | row encryption/masking and table layout stay unchanged |

P1.6 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  obfuscated `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` no longer contained the
  entry `iload row; bipush 37; imul; istore row` sequence, obfuscated
  `mmulSeq`, `a.a.fa(Object[], long)`, still invoked the original
  transition-material helper descriptor `22` times, and inspected helper
  callsites passed constants such as `12062`, `12099`, and `12136`, proving
  row constants were pre-scaled by `TRANSITION_MATERIAL_ROW_WORDS`.
- A fresh ten-run runtime gate rejected the implementation. Full TEST `Calc`
  sorted values were `199,206,207,208,210,211,215,218,221,225`, with
  lower/upper medians `210/211 ms`. Full obfusjack `Seq` sorted values were
  `308,309,309,309,310,315,316,318,318,349`, with lower/upper medians
  `310/315 ms`.
- The optimization improved full-obf obfusjack `Seq` slightly from the
  current accepted 10-run lower/upper median `318/320 ms`, but regressed full
  TEST `Calc` from the current accepted 10-run lower/upper median `182/183 ms`
  to `210/211 ms`. This violates P1.6 completion criteria, so the source
  change was fully reverted before continuing.
- Runtime/log scanning reported only the expected SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- The next P1 attempt must not reuse row-boundary pre-scaling unless it first
  explains and removes the TEST `Calc` regression generically.

#### P1.7 Specialize compressed island material cursor adjustment for mandatory thread buckets

Status: `[x]` measured, rejected, and reverted. No implementation commit was
made for this branch.

Scope:

- Optimize only the generic compressed island-material helper entry path
  generated by `installCompressedIslandMaterialHelper`.
- Keep the existing island-material helper descriptor
  `(JIII[Ljava/lang/Object;III)I`, helper count, compressed material layout,
  material encryption/masking, class-key mixing, and callsite runtime-source
  computation.
- Use the current generator invariant that every compressed island-material
  cursor is encoded with `CFF_ISLAND_RUNTIME_SOURCE_THREAD`.
- Replace the helper's runtime cursor-mode extraction and `mode != 0` branch
  with the equivalent mandatory bucket adjustment:
  `cursor = (cursor & CFF_ISLAND_CURSOR_INDEX_MASK) + ((source ^ (source >>> 16)) & (CFF_ISLAND_RUNTIME_SOURCE_BUCKETS - 1))`.
- Preserve live method-entry key flow, guard/path/block/source mixing,
  fake/poison rows, result-router rows, transition material rows, hidden keys,
  packed carriers, full CFF coverage, and JVM ABI.
- Do not change `cffIslandRuntimeSourceMode`, bucket count, material row
  registration, row contents, word masks, helper descriptor, or add
  fallback/skip/original-bytecode behavior.

Subtasks:

1. `[x]` Record and review the mandatory-thread cursor contract.
   Evidence requirement: fresh accepted-source 10-run runtime baseline, fresh
   full-obfusjack JFR attribution, source evidence that
   `cffIslandRuntimeSourceMode` unconditionally returns
   `CFF_ISLAND_RUNTIME_SOURCE_THREAD`, and bytecode evidence that the current
   island-material helper executes mode extraction and branch work before every
   material word decode.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passed; the plan
   checkpoint must be committed before source edits.
2. `[x]` Implement, validate, measure, reject, and revert the
   mandatory-thread cursor adjustment.
   Evidence requirement: bytecode inspection shows `a.da.za` no longer begins
   by extracting `cursor >>> 24` and branching on mode, while it still masks
   the cursor index, applies the source bucket adjustment, loads the same
   material entry and class-key words, and returns `value ^ mask`.
   Validation target: same targeted JVM command as P1.1, plus the common
   runtime validation commands with a 10-run gate.
   Completion criteria: targeted JVM validation passes; full-obf obfusjack
   `Seq` improves from the current accepted 10-run lower/upper median
   `321/322 ms`, or the source change is reverted before proceeding; full-obf
   TEST `Calc` remains no worse than the current accepted 10-run lower/upper
   median `199/199 ms`; no CFF dry-run row count or missing-row blocker
   regresses; no forbidden runtime/log/marker scan hit is introduced; subagent
   implementation review passes before commit.

Dependency/order reason:

- P1.4/P1.5 direct-island domain decode changes and P1.6 row-boundary
  pre-scaling were measured and rejected, so the next P1 attempt must target a
  different proven hot path.
- After reverting P1.6 and freshly regenerating the accepted-source artifact,
  a fresh ten-run gate still leaves P1 open. Full TEST `Calc` sorted values
  were `194,194,195,196,199,199,204,205,209,210`, with lower/upper medians
  `199/199 ms`. Full obfusjack `Seq` sorted values were
  `307,311,312,320,321,322,323,324,332,336`, with lower/upper medians
  `321/322 ms`.
- The same accepted-source artifact completed a fresh same-JVM 8-run
  full-obfusjack JFR with `Seq` rows `312`, `323`, `312`, `313`, `313`,
  `319`, `322`, and `314 ms`.
- Filtering that JFR to stacks containing obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, reports `fa_total=481`,
  `a.da.za(...)=219`, `a.da.ua(...)=172`, and `a.b.d(...)=154`, proving the
  island-material helper is currently the largest sampled P1 helper frame.
- Source inspection shows `cffIslandRuntimeSourceMode(int materialWords)`
  currently returns `CFF_ISLAND_RUNTIME_SOURCE_THREAD` unconditionally, and
  `registerCompressedIslandMaterialBlob` derives every encoded compressed
  island-material cursor through that function.
- Source and bytecode inspection show the helper still extracts
  `mode = cursor >>> CFF_ISLAND_CURSOR_MODE_SHIFT`, masks the cursor index,
  and runs `emitCffIslandRuntimeSourceCursorFromLocal`, whose first operation
  branches on `mode == 0`, even though the current generator never emits
  compressed island-material cursors with mode `0`.

Invariant mapping:

| Current island-material path | P1.7 path | Preserved invariant |
| --- | --- | --- |
| every registered compressed island-material cursor is encoded with THREAD mode | unchanged | runtime-source bucket family is unchanged |
| helper extracts mode and, for nonzero mode, adds `(source ^ (source >>> 16)) & 3` to the cursor index | helper directly adds the same bucket offset | selected material bucket is unchanged for every generated cursor |
| helper loads `Object[]` island entries, casts the selected `int[]` row, decodes `word`, computes live mask, and returns `word ^ mask` | unchanged after cursor selection | material word identity and live key masking stay unchanged |
| helper descriptor and callsite arguments are `(JIII,Object[],source,cursor,word)` | unchanged | JVM ABI surface and generated helper count stay unchanged |

P1.7 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  obfuscated `a.da.za:(JIII[Ljava/lang/Object;III)I` no longer began by
  extracting `cursor >>> 24` or branching on mode. The helper entry directly
  computed `cursor & 16777215`, added `(source ^ (source >>> 16)) & 3`, then
  loaded `objectMaterial[77]`, selected the `int[]` material row, loaded the
  requested word, loaded `objectMaterial[65]` class-key words, computed the
  live mask, and returned `value ^ mask`.
- A fresh ten-run runtime gate rejected the implementation. Full TEST `Calc`
  sorted values were `209,211,211,218,218,221,224,224,227,228`, with
  lower/upper medians `218/221 ms`. Full obfusjack `Seq` sorted values were
  `308,310,311,311,312,315,318,334,338,350`, with lower/upper medians
  `312/315 ms`.
- The optimization improved full-obf obfusjack `Seq` from the current
  accepted 10-run lower/upper median `321/322 ms`, but regressed full TEST
  `Calc` from the current accepted 10-run lower/upper median `199/199 ms` to
  `218/221 ms`. This violates P1.7 completion criteria, so the source change
  was fully reverted before continuing.
- Runtime/log scanning reported only the expected SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- The next P1 attempt must not reuse mandatory-thread cursor branch removal
  unless it first explains and removes the TEST `Calc` regression generically.

### P2 Bring TEST Calc runtime median to <= 60 ms

Scope:

- Optimize only full JVM-obfuscated runtime overhead that remains after P1.
- Address the secondary TEST Calc delta between CFF-only median `94 ms` and
  full JVM obf median `198 ms`.
- Do not expose plaintext constants, descriptor strings, names, handles, keys,
  or static seed material, and do not add Java helper classes or adapter layers.

Dependency/order update:

- P1.4, P1.5, P1.6, and P1.7 all produced fresh runtime evidence that small
  generic CFF material-helper optimizations can improve full-obf obfusjack
  `Seq`, but fail acceptance by regressing full-obf TEST `Calc`.
- The current accepted-source ten-run gate after reverting P1.6/P1.7 reports
  full TEST `Calc` sorted values
  `194,194,195,196,199,199,204,205,209,210`, with lower/upper medians
  `199/199 ms`, and full obfusjack `Seq` sorted values
  `307,311,312,320,321,322,323,324,332,336`, with lower/upper medians
  `321/322 ms`.
- P1 therefore cannot safely continue through more `mmulSeq`-only helper
  micro-optimizations until the Calc regression surface is attributed and a
  generic Calc-safe optimization route is proven. P2.1 is pulled forward as a
  plan-level dependency before the next P1 implementation attempt.
- This order change does not change the final acceptance targets: P1 still
  requires full JVM obf obfusjack `Seq <= 200 ms`, and P2 still requires full
  JVM obf TEST `Calc <= 60 ms`.

#### P2.1 Attribute remaining TEST Calc runtime samples

Scope:

- Run JFR on the post-P1 full TEST artifact.
- Map hot obfuscated methods to original methods through the fresh map file.
- Identify whether remaining samples are CFF execution, invokedynamic
  materialization, string decode/materialization, constant materialization, or
  packed carrier/key-transfer overhead.

Required evidence:

- JFR hot-method report and runtime stdout proving TEST completed and reporting
  `Calc`.
- Bytecode/topology evidence for the top sampled obfuscated methods.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Runtime target:

```bash
mkdir -p build/jvm-runtime-perf/jfr build/jvm-runtime-perf/java-tmp
java -XX:-UsePerfData \
  -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
  -XX:StartFlightRecording=filename=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/jfr/post-p1-test-calc.jfr,settings=profile,dumponexit=true \
  -jar build/test-jvm-full-obf-perf/test-obf.jar \
  > build/jvm-runtime-perf/jfr/post-p1-test-calc.stdout.log \
  2> build/jvm-runtime-perf/jfr/post-p1-test-calc.stderr.log
jfr view hot-methods build/jvm-runtime-perf/jfr/post-p1-test-calc.jfr
jfr print --events ExecutionSample --stack-depth 20 build/jvm-runtime-perf/jfr/post-p1-test-calc.jfr
```

Required post-run mapping and log inspection:

```bash
rg -n "Exception|Error|VerifyError|BootstrapMethodError|NoSuchMethod|NoSuchField|WrongMethodType|ClassCastException|ArrayIndexOutOfBoundsException|fallback|skip|static-decrypt|__neko_cff_didx|__neko_indy_bsm_shared|__neko_indy_register" \
  build/jvm-runtime-perf/jfr/post-p1-test-calc.stdout.log \
  build/jvm-runtime-perf/jfr/post-p1-test-calc.stderr.log \
  build/test-jvm-full-obf-perf/TEST.full-obf.run.stdout.log \
  build/test-jvm-full-obf-perf/TEST.full-obf.run.stderr.log \
  build/test-jvm-full-obf-perf/test-obf.obfuscate.stdout.log \
  build/test-jvm-full-obf-perf/test-obf.obfuscate.stderr.log
```

Top sampled obfuscated methods must be mapped through the fresh
`build/test-jvm-full-obf-perf/test-obf.jar.map` file and, where needed,
through `javap` output under `build/test-jvm-full-obf-perf/`.

Completion criteria:

- Plan is updated with an exact post-P1 TEST Calc hot runtime path before P2.2
  code edit begins.
- No runtime code changes in this subtask.

P2.1 status: `[x]` completed as the P1 dependency attribution step. No runtime
code changes were made.

P2.1 evidence:

- A higher-sampling TEST Calc JFR was captured against the accepted-source
  full JVM-obf artifact at
  `build/jvm-runtime-perf/jfr/current-full-test-calc-max.jfr`.
- The JFR run completed with `Calc: 197ms` and no unexpected runtime/log scan
  hits.
- `jfr view hot-methods` reported `116` execution samples. The top sampled
  methods were:
  - `a.u.l(Object[], long)` with `30` samples.
  - `a.u.o(Object[], long)` with `17` samples.
  - `a.u.m(Object[], long)` with `12` samples.
  - `a.na.kf(int, int, int, int, Object[], int, long)` with `9` samples.
  - `a.na.lf(Object[], int, long, int, int, int)` with `2` samples.
- Fresh map evidence identifies:
  - `pack/tests/bench/Calc -> a/u`.
  - `Calc.call(I)V -> a.u.l`.
  - `Calc.runAdd()V -> a.u.m`.
  - `Calc.runStr()V -> a.u.o`.
  - `Calc.runAll()V -> a.u.n`.
  - `a/b.__neko_indy_flow -> a.na.kf`.
  - `a/b.__neko_strtail$ -> a.na.lf`.
  - `a/a.__neko_cff_tmat$... -> a.a.j`.
- Bytecode inspection of the accepted full TEST artifact shows the hot Calc
  methods execute the token-material helper `a.a.j([Ljava/lang/Object;IIII)I`
  repeatedly:
  - `a.u.l` has `6` token-material helper calls, `2` `__neko_indy_flow`
    calls, and `2` invokedynamic sites.
  - `a.u.m` has `7` token-material helper calls, `2` `__neko_indy_flow`
    calls, and `2` invokedynamic sites.
  - `a.u.o` has `12` token-material helper calls, `6` `__neko_indy_flow`
    calls, `6` invokedynamic sites, and `2` string-tail calls.
  - `a.u.n` has `2` token-material helper calls, `12` `__neko_indy_flow`
    calls, `12` invokedynamic sites, `3` string-tail calls, and the remaining
    CFF transition/material calls from the loop driver.
- This attribution proves the current Calc path is not dominated by the
  P1.6/P1.7 transition/island helper entry work. The Calc path is dominated
  by Calc method CFF bodies plus generic token materialization,
  invokedynamic-flow materialization, and string-tail materialization.

#### P2.2 Optimize repeated loop-site materialization on the proven Calc path

Scope:

- Optimize the single generic category proven by P2.1, using live CFF state and
  existing protected carrier/table surfaces.
- If P2.1 shows multiple categories, choose one bounded category and record a
  new P2 subtask before editing the next category.
- Do not cache or compact values from static descriptors, owner/name strings,
  static seeds, plaintext constants, or unkeyed helper state.

Required evidence:

- P2.1 runtime attribution.
- Source invariant showing cached/compacted material is still keyed by live
  method-entry/CFF state and remains eligible for the enabled JVM obfuscation
  surfaces or emits an equivalent protected form directly.

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.

Completion criteria:

- Full-obf TEST `Calc` five-run median is `<= 60 ms`.
- Full-obf obfusjack `Seq` remains `<= 200 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No generated helper count, descriptor count, plaintext marker, or fallback
  regression.
- Subagent implementation review passes before commit.

#### P2.2.1 Specialize token-material helper packed row loads

Scope:

- Optimize only the generic encrypted CFF token-material helper generated by
  `installEncryptedTokenMaterialHelper`.
- Keep the helper descriptor `([Ljava/lang/Object;IIII)I`, generated helper
  count, object carrier layout, token material row encryption/masking,
  `AtomicLong` object-cell side effect, class-key mask, control mask, hidden
  method keys, packed carriers, and all CFF block coverage unchanged.
- Replace the helper-internal dynamic packed-word branch
  `cursor & 1 ? low-word : high-word` with generated fixed-parity loads for
  the 13 sequential token material words in a row.
- Use the same live `guard`, `path`, and `block` locals, the same class-owned
  token `long[]`, the same class-key words, and the same object-cell
  `getPlain` / `setPlain` update.
- Do not change token row registration, row contents, row count, seed
  formulas, callsite constants, token helper ABI, key-transfer material
  cursor encoding, or add fallback/skip/original-bytecode behavior.

Required evidence:

- P2.1 JFR and bytecode evidence place hot TEST Calc execution in obfuscated
  Calc methods that repeatedly call the token-material helper
  `a.a.j([Ljava/lang/Object;IIII)I`.
- Bytecode inspection of `a.a.j` shows every token material word load currently
  executes a dynamic parity branch:
  `DUP; ICONST_1; IAND; IFNE lowWord; ...; GOTO done`.
- Source evidence shows `registerEncryptedTokenMaterial` stores every token
  row with `emitPackedMaterialLongStores(..., index * TOKEN_MATERIAL_ROW_LONGS,
  values)` and returns `index * TOKEN_MATERIAL_ROW_LONGS * 2`, so every normal
  token helper cursor starts on an even packed-word boundary.
- Source evidence shows key-transfer runtime-source buckets also require
  contiguous cursors spaced by `TOKEN_MATERIAL_ROW_LONGS * 2`, preserving the
  same row-aligned even base cursor invariant.
- The token helper consumes exactly `TOKEN_MATERIAL_ROW_WORDS == 13` material
  words in a fixed order: encrypted token, three class-mask words, six
  object-mask words, and three control-mask words.

Invariant mapping:

| Current token-material helper | P2.2.1 token-material helper | Preserved invariant |
| --- | --- | --- |
| callsite passes class object carrier, row cursor, guard, path, and block | unchanged | helper ABI and live CFF state inputs stay unchanged |
| row cursor is a generated row-aligned packed-word cursor | unchanged | selected encrypted token row stays unchanged |
| helper increments a cursor and branches on each word's runtime parity | helper computes the same row long index and emits fixed high/low extraction for word offsets `0..12` | decoded material word sequence stays unchanged |
| class mask, object-cell mask/update, and control mask consume decoded words | unchanged formulas consume the same decoded word values | dynamic key/state masking and object-cell side effects stay live |
| helper returns the decoded token | unchanged | downstream CFF dispatch token semantics stay unchanged |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands with a ten-run gate.
- If full TEST `Calc` improves but remains above `60 ms`, run fresh TEST Calc
  JFR and record the remaining hot path before the next P2 subtask.
- If full obfusjack `Seq` regresses above the current accepted ten-run
  lower/upper median `321/322 ms`, revert before proceeding.

Completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and ten-run medians are recorded.
- Full-obf TEST `Calc` improves from the current accepted-source ten-run
  lower/upper median `199/199 ms`, or the source change is reverted before
  proceeding.
- Full-obf obfusjack `Seq` remains no worse than the current accepted-source
  ten-run lower/upper median `321/322 ms`.
- Generated bytecode inspection of the token-material helper shows the
  repeated `cursor & 1` branch pattern is removed while the helper descriptor,
  row lookup, class-key lookup, object-cell `getPlain`/`setPlain`, and return
  behavior remain.
- No CFF dry-run row count, helper count, descriptor count, plaintext marker,
  fallback, skip, verifier, bootstrap, reflection, MethodHandle, or
  LambdaMetafactory regression is introduced.
- Subagent implementation review passes before commit.

P2.2.1 status: `[x]` implemented and validated as an accepted incremental
Calc optimization. P2 remains open because full-obf TEST `Calc` is still above
the `<= 60 ms` target, and P1 remains open because full-obf obfusjack `Seq`
is still above the `<= 200 ms` target.

P2.2.1 evidence:

- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL` and `19 executed` tasks.
- Fresh generated bytecode inspection of the full TEST token-material helper
  `a.a.j([Ljava/lang/Object;IIII)I` proves the helper descriptor is unchanged
  and the dynamic per-word `cursor & 1` branch pattern is gone. The helper now
  shifts the generated row cursor once with `iushr 1`, then uses fixed
  `laload` high/low extraction for the 13 token-material words.
- The same bytecode inspection confirms the helper still loads the class-owned
  token `long[]`, class-key `int[]`, and object-cell `AtomicLong`, and still
  executes `AtomicLong.getPlain()` / `AtomicLong.setPlain(long)` before
  returning the decoded token.
- Fresh ten-run runtime gate:
  - full TEST `Calc` sorted values were
    `183,184,186,188,189,190,192,193,196,197`, with lower/upper medians
    `189/190 ms`.
  - full obfusjack `Seq` sorted values were
    `311,311,312,312,314,315,315,316,319,341`, with lower/upper medians
    `314/315 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=85/88 ms`,
    `Virtual=89/91 ms`, `Parallel=8/8 ms`, and `VThreads=9/9 ms`.
- This improves full TEST `Calc` from the accepted-source ten-run
  `199/199 ms` median and keeps full obfusjack `Seq` no worse than the
  accepted-source ten-run `321/322 ms` median.
- Runtime/log scanning reported only expected text: SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`.
- Because full TEST `Calc` remains above `60 ms`, a fresh post-change TEST
  Calc JFR was captured at
  `build/jvm-runtime-perf/jfr/p2-2-1-full-test-calc-max.jfr`. The run
  completed with `Calc: 202ms`, reflecting normal single-run noise.
- The post-change TEST Calc JFR reports the remaining top samples in the
  Calc CFF bodies and invokedynamic flow materialization:
  `a.u.l(Object[], long)=29`, `a.u.m(Object[], long)=14`,
  `a.u.o(Object[], long)=14`, and
  `a.na.kf(int, int, int, int, Object[], int, long)=7`.
  Filtered stacks still include `a.b.if(...)` under the Calc frames because
  the invokedynamic resolver/callsite path invokes the target methods.
- The next P2 subtask must target a different proven generic Calc runtime
  surface, most likely invokedynamic-flow materialization or remaining Calc
  CFF body overhead, and must not claim P2 completion until `Calc <= 60 ms`
  is proven by a fresh runtime gate.

#### P2.2.2 Shrink token object-cell key update bytecode

Status: `[x]` implemented and validated as an accepted incremental Calc/key
update optimization. P2 remains open because full-obf TEST `Calc` is still
above the `<= 60 ms` target, and P1 remains open because full-obf obfusjack
`Seq` is still above the `<= 200 ms` target.

Scope:

- Optimize only the generic CFF object-cell key update used by encrypted token
  material and compressed island material helpers.
- Keep the generated class-owned `Object[]` carrier, token-material helper
  descriptor `([Ljava/lang/Object;IIII)I`, island-material helper descriptor
  `(JIII[Ljava/lang/Object;III)I`, token material rows, island material rows,
  class-key words, the 64 separate `AtomicLong` object cells in
  `objectMaterial[0..63]`, object-cell mask formula, live `guard/path/block`
  inputs, hidden method keys, packed carriers, and CFF block coverage
  unchanged.
- Rewrite only the helper-internal object-cell update expression so the
  decoded current object-cell word `encoded ^ mask(epoch)` is materialized
  once and reused for both the returned object token and the next encoded
  epoch. Stream the next encoded word directly into the `setPlain(long)` pack
  instead of storing it in a temporary local and reloading it.
- Apply the same expression-level rewrite to both generated object-cell
  consumers that currently emit the full update formula:
  `CffMaterialTables.emitAlignedTokenMaterialObjectMask` and
  `CffIslandMaterial.emitTokenMaterialObjectMask`.
- Do not remove the object-cell key update, do not replace it with a static
  value, do not collapse or replace the 64 `AtomicLong` object cells, do not
  expose raw object-table values, do not change token or island row
  registration, and do not add fallback/skip/original-bytecode behavior.

Required evidence:

- The accepted P2.2.1 ten-run gate still leaves P2 open: full TEST `Calc`
  sorted values were `183,184,186,188,189,190,192,193,196,197`, with
  lower/upper medians `189/190 ms`.
- The post-P2.2.1 TEST Calc JFR still places samples in the obfuscated Calc
  CFF bodies: `a.u.l(Object[], long)=29`, `a.u.m(Object[], long)=14`,
  `a.u.o(Object[], long)=14`, with invokedynamic-flow materialization
  secondary at `a.na.kf(...)=7`.
- Fresh bytecode inspection of the current full TEST artifact shows the hot
  Calc methods repeatedly call the token-material helper `a.a.j(...)` and
  method-key helper `a.a.r(...)`: `a.u.l` has `6` token helper and `3`
  method-key helper calls, `a.u.m` has `6` and `3`, `a.u.o` has `10` and `5`,
  and `a.u.n` has `2` and `1`.
- Fresh `PrintInlining` evidence from
  `build/jvm-runtime-perf/p2-2-1-full-test-print-inlining.stdout.log` shows
  `a.a::j (313 bytes)` is compiled separately, many hot Calc callsites report
  `a.a::j ... callee is too large`, and only some later hot callsites inline
  it. The same log shows `AtomicLong.getPlain` and `AtomicLong.setPlain` from
  inside `a.a::j` are inline-hot, proving this key-state update is on the
  JIT-compiled Calc path rather than only an obfuscation-time artifact.
- Fresh bytecode inspection of `a.a.j([Ljava/lang/Object;IIII)I` shows the
  helper computes a live object-cell index from `guard/path/block` plus
  protected material words, loads `objectMaterial[index]`, casts it to
  `AtomicLong`, calls `getPlain()`, computes the decoded object token and next
  encoded epoch, then calls `setPlain(long)`.
- The same bytecode shows the current helper computes `encoded ^
  currentMask` twice in the object-cell update: once for the returned object
  token and once for `nextEncoded`, then stores and reloads `nextEncoded`
  before packing the `setPlain(long)` value.
- Source inspection shows `installClassKeyTableInit` initializes these 64
  object cells from `table.objectValues()` and `cffObjectCellEpoch`, while
  `CffMaterialTables.emitAlignedTokenMaterialObjectMask` and
  `CffIslandMaterial.emitTokenMaterialObjectMask` both consume the same
  packed cell value and apply the same update formula.
- Source and bytecode inspection show the older
  `emitClassObjectTokenMaskAndUpdate -> table.objectHelperName()` path is not
  a current generated object-cell runtime consumer: `emitEncryptedToken`
  returns through the shared token-material helper whenever `activeKeyTable`
  is present, the fallback branch then calls
  `emitClassObjectTokenMaskAndUpdate` only with `activeKeyTable == null`,
  and fresh `javap`/`rg` inspection found no
  `([Ljava/lang/Object;IIIIIIIII)I` object-helper descriptor in the current
  full TEST or obfusjack artifacts.

Invariant mapping:

| Current object-cell path | P2.2.2 path | Preserved invariant |
| --- | --- | --- |
| `objectMaterial[0..63]` hold 64 separate `AtomicLong` packed cells | unchanged | object graph, packed carrier layout, and 64 mutable object-cell key states are preserved |
| helper computes live `index = f(guard, path, block, protected words) & 63` | unchanged | live CFF state still selects the object-cell key state |
| helper reads `((AtomicLong)objectMaterial[index]).getPlain()` | unchanged | plain atomic cell read semantics and selected packed value are preserved |
| helper stores `currentMask = mask(epoch)`, computes `encoded ^ currentMask` for result, then recomputes `encoded ^ currentMask` for `nextEncoded` | helper stores `decoded = encoded ^ mask(epoch)` once and reuses it for both result and next encoding | decoded object-cell value is unchanged and remains derived from live mutable state |
| helper stores `nextEncoded`, reloads it, packs `(nextEncoded << 32) ^ nextEpoch`, and calls `AtomicLong.setPlain(long)` | helper computes the same `nextEncoded` on stack, packs it directly, and calls `AtomicLong.setPlain(long)` | packed cell contents and plain atomic write semantics are unchanged |
| token and island helpers both emit the same update formula shape | both are rewritten to the same reduced expression shape | no mixed generated-runtime formula is introduced |

Subtasks:

1. `[x]` Record and review the object-cell expression shrink.
   Evidence requirement: this P2.2.2 plan section, current TEST JFR evidence,
   current `javap` evidence for `a.a.j`/`a.u.*`, current `PrintInlining`
   evidence, and source evidence identifying every object-cell runtime
   consumer.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passes; the plan
   checkpoint is committed before source edits.
2. `[x]` Implement, validate, measure, and either accept or revert the
   expression shrink.
   Evidence requirement: source diff updates class-key table initialization
   nowhere and updates only both token/island object-cell helper emitters;
   generated bytecode shows token and island helpers still load
   `objectMaterial[index]`, still cast to `AtomicLong`, still call
   `getPlain()J` and `setPlain(J)V`, and no longer recompute
   `encoded ^ currentMask` or store/reload `nextEncoded`.
   Validation target: same targeted JVM command as P1.1, plus a ten-run common
   runtime gate and required runtime/log scan.
   Completion criteria: targeted JVM validation passes; full-obf TEST `Calc`
   improves from the accepted P2.2.1 ten-run lower/upper median `189/190 ms`,
   or the source change is reverted before proceeding; full-obf obfusjack
   `Seq` remains no worse than the accepted P2.2.1 ten-run lower/upper median
   `314/315 ms`; non-target obfusjack rows stay within the regression budget;
   helper descriptors and CFF dry-run row counts do not regress; no forbidden
   runtime/log/marker scan hit is introduced; subagent implementation review
   passes before commit.

P2.2.2 evidence:

- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL` and `19 executed` tasks.
- Fresh generated bytecode inspection of the full TEST token-material helper
  `a.a.j([Ljava/lang/Object;IIII)I` shows the 64 separate `AtomicLong`
  object-cell layout is preserved: the helper still loads
  `objectMaterial[index]`, casts to `AtomicLong`, calls `getPlain()J`, and
  calls `setPlain(J)V`.
- The same bytecode inspection shows the object-cell update now stores the
  decoded current word once in local `16` and reuses it for both result
  computation and the next encoded word. The helper no longer stores and
  reloads `nextEncoded` before packing the `setPlain(long)` value.
- The token-material helper bytecode shrank from `226` javap instruction
  lines in the accepted P2.2.1 artifact to `222` instruction lines in the
  P2.2.2 artifact. Helper descriptors and carrier layout are unchanged.
- Fresh ten-run runtime gate:
  - full TEST `Calc` sorted values were
    `178,179,181,182,183,183,186,186,186,189`, with lower/upper medians
    `183/183 ms`.
  - full obfusjack `Seq` sorted values were
    `309,309,311,311,312,314,315,320,328,343`, with lower/upper medians
    `312/314 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=86/86 ms`,
    `Virtual=88/90 ms`, `Parallel=8/8 ms`, and `VThreads=8/9 ms`.
- This improves full TEST `Calc` from the accepted P2.2.1 ten-run
  `189/190 ms` median and keeps full obfusjack `Seq` no worse than the
  accepted P2.2.1 ten-run `314/315 ms` median.
- Runtime/log scanning reported only expected text: SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`.

#### P2.2.3 Reuse packed token-material row pairs inside the key-update helper

Status: `[x]` measured, rejected, and reverted. No implementation commit was
made for this branch.

Scope:

- Optimize only the generic encrypted CFF token-material helper generated by
  `installEncryptedTokenMaterialHelper`.
- Keep the helper descriptor `([Ljava/lang/Object;IIII)I`, helper count,
  class-owned `Object[]` carrier layout, token material row layout,
  row-aligned cursor contract, token material encryption/masking,
  `AtomicLong` object-cell update, class-key mask, control mask, hidden method
  keys, packed carriers, and full CFF block coverage unchanged.
- Replace repeated high/low extraction loads from the same packed `long[]`
  material row element with a helper-local packed-pair scratch value: when an
  even token-material word is loaded, duplicate/store that row `long`; when
  the following odd word is loaded, read the cached `long` and extract the low
  word instead of reloading the same array element.
- Reuse only an internal long scratch local that is dead before and after each
  packed-pair extraction. Do not cache decoded tokens, decoded object-cell
  values, class-key words, object-cell state, guard/path/block state, static
  seeds, descriptors, owner/name strings, or callsite-derived values.
- Do not change token row registration, row contents, row count, seed
  formulas, helper ABI, key-transfer material cursor encoding, object-cell
  side effects, or add fallback/skip/original-bytecode behavior.

Dependency/order reason:

- P2.2.2 reduced one object-cell expression and improved full TEST `Calc` from
  `189/190 ms` to `183/183 ms`, but P2 remains open because this is still
  above the `<= 60 ms` target.
- The user direction for the next pass is to keep focus on key updates rather
  than invokedynamic. This subtask stays on the proven token-material
  key-update helper path and does not change the invokedynamic resolver or
  cache.

Required evidence:

- Fresh `JvmFullObfuscationPerfTest` regeneration initially hit the known
  random original fixture `Test 1.6: Pool FAIL`; an immediate rerun completed
  with `BUILD SUCCESSFUL` and `19 executed` tasks, producing fresh full
  JVM-obf artifacts.
- Fresh post-P2.2.2 TEST Calc JFR at
  `build/jvm-runtime-perf/jfr/p2-2-2-current-test-calc-max.jfr` completed with
  `Calc: 194ms`. Hot-method samples remain in the obfuscated Calc CFF bodies:
  `a.u.l(Object[], long)=21`, `a.u.o(Object[], long)=13`,
  `a.u.m(Object[], long)=10`, with `a.na.kf(...)=10` as secondary
  invokedynamic-flow materialization.
- Fresh map evidence identifies `pack/tests/bench/Calc -> a/u` and
  `a/a.__neko_cff_tmat$... -> a.a.j`.
- Fresh bytecode inspection of the current full TEST artifact shows the hot
  Calc methods still repeatedly call token-material helper `a.a.j(...)` and
  method-key helper `a.a.r(...)`: `a.u.l` has `6` token helper and `3`
  method-key helper calls, `a.u.m` has `7` and `3`, `a.u.o` has `12` and `5`,
  and `a.u.n` has `2` and `1`.
- Fresh current `PrintInlining` evidence from
  `build/jvm-runtime-perf/p2-2-2-current-full-test-print-inlining.stdout.log`
  shows `a.a::j (306 bytes)` is still reported as `callee is too large` at
  Calc callsites before later hot inlining, while `AtomicLong.getPlain` and
  `AtomicLong.setPlain` inside `a.a::j` are inline-hot. This proves the helper
  remains on the JIT-compiled key-update path after P2.2.2.
- Fresh bytecode inspection of `a.a.j([Ljava/lang/Object;IIII)I` shows the
  helper keeps the P2.2.1 fixed-parity layout but still reloads the same
  packed token material `long[]` entry for the high and low words of each row
  pair, for example `row[base]` for word `0` and word `1`, `row[base + 1]`
  for word `2` and word `3`, and so on.
- Source inspection shows `TOKEN_MATERIAL_ROW_WORDS == 13`, every normal
  encrypted token-material cursor is row-aligned, and P2.2.1 already proved
  the helper consumes those 13 words in fixed order.

Invariant mapping:

| Current token-material helper | P2.2.3 token-material helper | Preserved invariant |
| --- | --- | --- |
| helper loads `row[base + pair]` once to extract the high word and loads the same `row[base + pair]` again to extract the following low word | helper loads `row[base + pair]` once, stores it in an internal long scratch local, extracts the high word, then extracts the following low word from the scratch local | decoded token material word sequence is unchanged |
| odd token-material word loads address the class-owned encrypted `long[]` row directly | odd word loads use the cached packed long produced by the immediately preceding even word | selected encrypted row identity and packed high/low layout are unchanged |
| helper row cursor, token row registration, class-key mask, object-cell update, and control mask consume the decoded words | unchanged formulas consume the same decoded words in the same order | live key/state masking and mutable object-cell side effects stay live |
| helper descriptor and generated callsites are `([Ljava/lang/Object;IIII)I` | unchanged | JVM ABI surface and generated helper count stay unchanged |

Subtasks:

1. `[x]` Record and review the packed-pair token-material helper plan.
   Evidence requirement: this P2.2.3 plan section, current TEST JFR evidence,
   current `javap` evidence for `a.a.j`/`a.u.*`, current `PrintInlining`
   evidence, and source evidence for the fixed 13-word row consumption.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passed; the plan
   checkpoint is committed before source edits.
2. `[x]` Implement, validate, measure, and reject/revert the
   packed-pair scratch reuse.
   Evidence requirement: source diff updates only the token-material helper
   packed word-load emission path; generated bytecode shows `a.a.j` still uses
   the same descriptor, row cursor, class-key load, `AtomicLong.getPlain()` /
   `setPlain(long)`, and return behavior, while odd material-word extraction
   no longer reloads the same `long[]` element as the immediately preceding
   even word.
   Validation target: same targeted JVM command as P1.1, plus a ten-run common
   runtime gate and required runtime/log scan.
   Completion criteria: targeted JVM validation passes; full-obf TEST `Calc`
   improves from the accepted P2.2.2 ten-run lower/upper median `183/183 ms`,
   or the source change is reverted before proceeding; full-obf obfusjack
   `Seq` remains no worse than the accepted P2.2.2 ten-run lower/upper median
   `312/314 ms`; non-target obfusjack rows stay within the regression budget;
   helper descriptors and CFF dry-run row counts do not regress; no forbidden
   runtime/log/marker scan hit is introduced; subagent implementation review
   passes before commit.

P2.2.3 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  token-material helper `a.a.j([Ljava/lang/Object;IIII)I` kept the same
  descriptor, row cursor, class-key load, `AtomicLong.getPlain()` /
  `setPlain(long)`, and return behavior. The helper used a fresh non-
  overlapping long scratch local `17/18`; `laload` count inside `a.a.j`
  dropped from `13` to `7`, and the helper javap method segment shrank from
  `225` to `215` lines.
- A fresh ten-run runtime gate rejected the implementation. Full TEST `Calc`
  sorted values were `176,176,177,178,180,181,181,181,182,182`, with
  lower/upper medians `180/181 ms`. Full obfusjack `Seq` sorted values were
  `307,317,317,320,322,323,327,329,333,341`, with lower/upper medians
  `322/323 ms`.
- Although full TEST `Calc` improved slightly from the accepted P2.2.2
  `183/183 ms` median, full obfusjack `Seq` regressed from the accepted
  P2.2.2 `312/314 ms` median. This violates P2.2.3 acceptance, so the source
  change was fully reverted before proceeding.
- Runtime/log scanning reported only expected text: SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- The next key-update attempt must not reuse packed-pair scratch caching in
  `a.a.j` unless it first explains and removes the generic obfusjack `Seq`
  regression.

#### P2.2.4 Hoist key-transfer method-key fold inside the key-update helper

Status: `[x]` measured, rejected, and reverted. No implementation commit was
made for this branch.

Scope:

- Optimize only the generic key-transfer material helper generated by
  `installKeyTransferMaterialHelper`.
- Keep the helper descriptor `(JIII[Ljava/lang/Object;II)J`, generated helper
  count, key-transfer material rows, runtime-source cursor encoding, token
  material helper calls, class-integrity ticket issue/defer behavior, hidden
  method keys, packed carriers, CFF block coverage, and helper ABI unchanged.
- Compute `methodKeyFold(key, KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED)` once per
  key-transfer helper invocation, store it in an internal int scratch local, and
  reuse that value when decoding the high and low key-transfer material words.
- Do not change runtime-source acquisition, StackWalker/stack-trace mixing,
  thread mixing, key-transfer cursor selection, token row registration, token
  row contents, object-cell update behavior, static seed values, or
  invokedynamic resolver/cache behavior.

Dependency/order reason:

- P2.2.3 proved that packed-pair scratch caching in the token-material helper
  improves TEST `Calc` but regresses obfusjack `Seq`, so the next key-update
  attempt must target a different emitted helper shape.
- The user direction is to keep focus on key updates and avoid treating
  invokedynamic as the primary bottleneck without new evidence. This subtask
  changes only the key-transfer material helper's repeated live-key fold
  expression and leaves invokedynamic code unchanged.
- The same-mode runtime-source sharing candidate is not selected for this
  subtask because source inspection shows stack runtime-source mixing can
  include `StackWalker$StackFrame.getByteCodeIndex()`. P2.2.4 therefore
  preserves the current per-cursor runtime-source acquisition and only hoists a
  pure fold of the unchanged live `key` local.

Required evidence:

- Fresh accepted-source bytecode inspection of the full TEST key-transfer
  helper `a.a.l:(JIII[Ljava/lang/Object;II)J` shows the method is `1161` bytes
  in `PrintInlining` and executes repeated runtime-source and token-material
  decode paths. The same log reports `a.a::l (1161 bytes)` as `callee is too
  large` or `hot method too big` at Calc `runAll` callsites.
- Fresh bytecode inspection of obfuscated `Calc.runAll`, `a.u.n`, shows eight
  key-transfer material helper calls to
  `a.a.l:(JIII[Ljava/lang/Object;II)J` at bytecode offsets `1582`, `1626`,
  `1652`, `1699`, `2088`, `2111`, `2480`, and `2503`.
- Fresh bytecode inspection of `a.a.l` shows each decoded high/low word call to
  token-material helper `a.a.j([Ljava/lang/Object;IIII)I` is followed by the
  same emitted `emitMethodKeyFold` sequence using `lload_0`, `l2i`,
  `lload_0 >>> 32`, and the same constants from
  `KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED`.
- Source inspection shows both high and low key-transfer material words are
  registered with `KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED` in
  `CffKeyTransferRewriter.emitMaterializedDynamicBoundDecodedLong`.
- Source inspection shows `emitKeyTransferMaterialDecodedWord` appends
  `emitMethodKeyFold(insns, keyLocal, methodSeed)` after every token-material
  helper call, and `installKeyTransferMaterialHelper` invokes it for both the
  high and low decoded words. The helper does not store to `keyLocal` before
  either decode result is produced.
- Source inspection of `emitMethodKeyFold` shows the expression depends only on
  the live `key` local and the supplied seed. Hoisting it into a helper-local
  scratch preserves live method-entry key dependence and does not introduce a
  static or descriptor-derived key source.

Invariant mapping:

| Current key-transfer helper | P2.2.4 key-transfer helper | Preserved invariant |
| --- | --- | --- |
| high decoded word is `tokenMaterial(cursorHigh) ^ methodKeyFold(key, seed)` | high decoded word is `tokenMaterial(cursorHigh) ^ cachedFold` | decoded high word is unchanged |
| low decoded word is `tokenMaterial(cursorLow) ^ methodKeyFold(key, seed)` | low decoded word is `tokenMaterial(cursorLow) ^ cachedFold` | decoded low word is unchanged |
| `methodKeyFold` reads the live `key` local for each decoded word | helper computes the same fold once before material decode and reuses the local while `key` is unchanged | live key dependence remains the input |
| runtime-source cursor acquisition runs separately for each current cursor path | unchanged | thread/stack runtime-source semantics, including BCI-sensitive stack mixing, are preserved |
| class-integrity ticket helper consumes the packed high/low key-transfer result | unchanged | ticket issue/defer semantics and returned key value stay unchanged |

Subtasks:

1. `[x]` Record and review the key-transfer fold-hoist plan.
   Evidence requirement: this P2.2.4 section, accepted-source `javap` evidence
   for `a.a.l` and `a.u.n`, current `PrintInlining` evidence for `a.a::l`,
   and source evidence for the shared key-transfer method seed and pure
   `emitMethodKeyFold` expression.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passes; the plan
   checkpoint is committed before source edits.
2. `[x]` Implement, validate, measure, and reject/revert the
   key-transfer fold hoist.
   Evidence requirement: source diff changes only key-transfer material helper
   emission and the local helper used to decode key-transfer material words;
   generated bytecode shows `a.a.l` still has descriptor
   `(JIII[Ljava/lang/Object;II)J`, still calls `a.a.j(...)` for high and low
   words, still performs current runtime-source acquisition for each cursor,
   and stores a single helper-local method-key fold that is loaded for both
   high and low decoded words.
   Validation target: same targeted JVM command as P1.1, plus a ten-run common
   runtime gate and required runtime/log scan.
   Completion criteria: targeted JVM validation passes; full-obf TEST `Calc`
   improves from the accepted P2.2.2 ten-run lower/upper median `183/183 ms`,
   or the source change is reverted before proceeding; full-obf obfusjack
   `Seq` remains no worse than the accepted P2.2.2 ten-run lower/upper median
   `312/314 ms`; non-target obfusjack rows stay within the regression budget;
   helper descriptors and CFF dry-run row counts do not regress; no forbidden
   runtime/log/marker scan hit is introduced; subagent implementation review
   passes before commit.

P2.2.4 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  key-transfer helper `a.a.l:(JIII[Ljava/lang/Object;II)J` kept the same
  descriptor, kept `4` token-material helper calls to `a.a.j(...)`, and kept
  the same runtime-source acquisition count in the method segment
  (`Thread.currentThread` remained `8`). The helper computed the method-key
  fold once into local `20`, then each high/low decoded material word used
  `iload 20; ixor`. The `a.a.l` javap method segment shrank from `653` lines
  to `613` lines.
- A fresh ten-run runtime gate rejected the implementation. Full TEST `Calc`
  sorted values were `181,184,184,185,185,186,186,189,189,194`, with
  lower/upper medians `185/186 ms`. Full obfusjack `Seq` sorted values were
  `308,310,314,315,316,317,318,320,322,325`, with lower/upper medians
  `316/317 ms`.
- The implementation regressed full TEST `Calc` from the accepted P2.2.2
  `183/183 ms` median and did not keep full obfusjack `Seq` at or below the
  accepted P2.2.2 `312/314 ms` median. This violates P2.2.4 completion
  criteria, so the source change was fully reverted before proceeding.
- Runtime/log scanning reported only the expected SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- After the source revert, `JvmFullObfuscationPerfTest --rerun-tasks` passed
  again with `BUILD SUCCESSFUL` and `19 executed` tasks to regenerate accepted
  source artifacts. The next key-update attempt must not reuse this helper
  fold-hoist shape unless it first explains and removes the generic `Calc` and
  `Seq` regressions.

#### P2.2.5 Stream token object-cell encoded word into the key-update decode

Status: `[x]` implemented and validated as an accepted incremental Calc/key
update optimization. P2 remains open because full-obf TEST `Calc` is still
above the `<= 60 ms` target, and P1 remains open because full-obf obfusjack
`Seq` is still above the `<= 200 ms` target.

Scope:

- Optimize only the current generated encrypted token-material helper emitted
  by `installEncryptedTokenMaterialHelper`.
- Keep the token-material helper descriptor `([Ljava/lang/Object;IIII)I`,
  helper count, class-owned `Object[]` carrier layout, 64 `AtomicLong` object
  cells, token material rows, class-key mask, object-cell mask/update formula,
  control mask, hidden method keys, packed carriers, and CFF coverage
  unchanged.
- Remove only the helper-local store/reload of the packed object-cell high
  word currently named `encodedLocal`. Stream that high word directly into the
  existing `decoded = encoded ^ cffObjectCellMask(epoch)` expression, then keep
  the decoded value in the existing decoded/current-mask local for both result
  and next encoded cell computation.
- Drop the unused `nextEncodedLocal` allocation in the same helper emission.
- Do not change `AtomicLong.getPlain()` / `setPlain(long)`, epoch extraction,
  next-epoch computation, row-word consumption order, token row registration,
  seed formulas, token helper ABI, object-cell count, object-cell initialization,
  key-transfer material cursor encoding, or invokedynamic behavior.

Dependency/order reason:

- P2.2.3 and P2.2.4 both shrank key-update bytecode but failed runtime
  acceptance. P2.2.5 targets a different object-cell expression shape inside
  the accepted P2.2.2 key-update surface instead of packed-row scratch caching
  or key-transfer fold hoisting.
- P2.2.2 proved object-cell expression shrink can improve full TEST `Calc`
  while preserving obfusjack `Seq`; P2.2.5 is the remaining single-use local
  in that same accepted object-cell expression.

Required evidence:

- The accepted P2.2.2 ten-run gate remains the acceptance baseline: full TEST
  `Calc` lower/upper median `183/183 ms`, and full obfusjack `Seq`
  lower/upper median `312/314 ms`.
- Fresh current `PrintInlining` evidence shows `a.a::j (306 bytes)` is still
  reported as `callee is too large` at Calc callsites, while
  `AtomicLong.getPlain` and `AtomicLong.setPlain` from inside `a.a::j` are on
  the JIT-compiled key-update path.
- Fresh bytecode inspection of current full TEST `a.a.j([Ljava/lang/Object;IIII)I`
  shows the helper still loads `objectMaterial[index]`, casts to
  `AtomicLong`, calls `getPlain()J`, stores the low 32 bits as `epochLocal`,
  stores the high 32 bits as `encodedLocal`, immediately loads `epochLocal` to
  compute `cffObjectCellMask(epoch)`, then loads `encodedLocal` exactly once
  for the `encoded ^ mask` decode.
- Source inspection of `emitAlignedTokenMaterialObjectMask` shows
  `encodedLocal` is used only for that single decode, and `nextEncodedLocal`
  is no longer used in the current P2.2.2 source shape.
- Source inspection shows the helper already keeps the decoded object-cell word
  in `currentMaskLocal` and uses it for both the returned object mask and the
  next encoded packed cell. Streaming the high word into that same decoded
  local preserves the accepted P2.2.2 formula.

Invariant mapping:

| Current token object-cell path | P2.2.5 token object-cell path | Preserved invariant |
| --- | --- | --- |
| helper reads packed cell with `AtomicLong.getPlain()` | unchanged | selected mutable object-cell state is preserved |
| helper stores `epoch = (int) packed` | unchanged | epoch and next-epoch inputs remain unchanged |
| helper stores `encoded = (int) (packed >>> 32)`, then computes `decoded = encoded ^ mask(epoch)` | helper leaves `encoded` on stack and computes the same `decoded = encoded ^ mask(epoch)` without a store/reload | decoded object-cell word is unchanged |
| helper stores decoded word in `currentMaskLocal` and reuses it for result and next encoded value | unchanged | accepted P2.2.2 live key-state update is preserved |
| helper writes `((decoded ^ mask(nextEpoch)) << 32) ^ nextEpoch` through `AtomicLong.setPlain(long)` | unchanged | packed cell contents and plain write semantics remain unchanged |

Subtasks:

1. `[x]` Record and review the object-cell encoded-word streaming plan.
   Evidence requirement: this P2.2.5 section, current `a.a.j` bytecode
   evidence, current `PrintInlining` evidence for `a.a::j`, and source
   evidence for the single-use `encodedLocal` and unused `nextEncodedLocal`.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passes; the plan
   checkpoint is committed before source edits.
2. `[x]` Implement, validate, measure, and either accept or revert the
   object-cell encoded-word streaming change.
   Evidence requirement: source diff changes only token-material object-cell
   expression emission; generated bytecode shows `a.a.j` still has descriptor
   `([Ljava/lang/Object;IIII)I`, still loads/casts `AtomicLong`, still calls
   `getPlain()J` and `setPlain(J)V`, still stores epoch, and no longer stores
   or reloads a separate encoded word local before the decode.
   Validation target: same targeted JVM command as P1.1, plus a ten-run common
   runtime gate and required runtime/log scan.
   Completion criteria: targeted JVM validation passes; full-obf TEST `Calc`
   improves from the accepted P2.2.2 ten-run lower/upper median `183/183 ms`,
   or the source change is reverted before proceeding; full-obf obfusjack
   `Seq` remains no worse than the accepted P2.2.2 ten-run lower/upper median
   `312/314 ms`; non-target obfusjack rows stay within the regression budget;
   helper descriptors and CFF dry-run row counts do not regress; no forbidden
   runtime/log/marker scan hit is introduced; subagent implementation review
   passes before commit.

P2.2.5 evidence:

- Source diff changes only the generated encrypted token-material helper's
  object-cell decode expression in `CffMaterialTables`: it removes the
  single-use `encodedLocal`, removes the unused `nextEncodedLocal` allocation,
  streams `(int) (packed >>> 32)` directly into the existing
  `encoded ^ cffObjectCellMask(epoch)` decode, and reduces helper `maxLocals`
  from `17` to `15`.
- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL in 19s` and `19 executed`
  tasks.
- Fresh generated bytecode inspection of full TEST `a.a.j` shows the helper
  descriptor remains `([Ljava/lang/Object;IIII)I`, the 64 `AtomicLong`
  object-cell layout is preserved, and the helper still casts to
  `AtomicLong`, calls `getPlain()J`, stores the epoch, and calls
  `setPlain(J)V`. The decode no longer stores and reloads a separate encoded
  high-word local before `IXOR`; the helper instruction segment shrank from
  `225` javap lines in the accepted post-P2.2.4-revert artifact to `223`
  javap lines in the P2.2.5 artifact.
- Fresh ten-run runtime gate:
  - full TEST `Calc` sorted values were
    `177,177,178,178,181,183,183,183,186,189`, with lower/upper medians
    `181/183 ms`.
  - full obfusjack `Seq` sorted values were
    `309,310,311,311,312,312,314,316,324,338`, with lower/upper medians
    `312/312 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=77/79 ms`,
    `Virtual=80/80 ms`, `Parallel=7/8 ms`, and `VThreads=8/8 ms`.
- This improves the full TEST `Calc` lower median from the accepted P2.2.2
  ten-run `183/183 ms` baseline while keeping the upper median equal, and
  keeps full obfusjack `Seq` no worse than the accepted P2.2.2 ten-run
  `312/314 ms` baseline. The non-target obfusjack rows are no worse than the
  accepted P2.2.2 non-target medians.
- Current topology confirms the token-material helper descriptor count remains
  `([Ljava/lang/Object;IIII)I = 1` for each generated fixture. Current full
  TEST CFF counts are `outlined=622`, `shared=0`, `transition=621`,
  `step=505`, and `island=493`; current full obfusjack CFF counts are
  `outlined=514`, `shared=0`, `transition=391`, `step=454`, and
  `island=347`.
- Runtime/log scanning reported only expected text: SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`.

#### P2.2.6 Carry token object-cell result on stack across the state update

Status: `[x]` measured, rejected, and reverted. No implementation commit was
made for this branch.

Scope:

- Optimize only the current generated encrypted token-material helper emitted
  by `installEncryptedTokenMaterialHelper`.
- Keep the token-material helper descriptor `([Ljava/lang/Object;IIII)I`,
  helper count, class-owned `Object[]` carrier layout, 64 `AtomicLong` object
  cells, token material rows, class-key mask, object-cell mask/update formula,
  control mask, hidden method keys, packed carriers, row-word load order, CFF
  coverage, and `AtomicLong.getPlain()` / `setPlain(long)` unchanged.
- Remove only the helper-local store/reload of the decoded object-cell result
  currently named `objectResultLocal`. Leave the computed object-cell result on
  the operand stack while the helper computes `nextEpoch` and writes the next
  packed object-cell state, so the same result remains on stack after
  `setPlain(long)` returns.
- Do not change epoch extraction, current-mask decode, next-epoch computation,
  packed-cell write ordering, token row registration, seed formulas, token
  helper ABI, object-cell count, object-cell initialization, key-transfer
  material cursor encoding, or invokedynamic behavior.

Dependency/order reason:

- P2.2.5 removed the separate encoded-word local and left the accepted
  key-update helper at `302` JIT-reported bytes. Fresh PrintInlining still
  reports `a.a::j (302 bytes)` as `callee is too large` at cold Calc callsites
  before hot inlining, and still shows `AtomicLong.getPlain` and
  `AtomicLong.setPlain` inline-hot inside that helper.
- Fresh P2.2.5 bytecode inspection shows the remaining single-use
  `objectResultLocal` pattern: the helper stores the object-cell result before
  next-epoch and `setPlain(long)`, then reloads it immediately after
  `setPlain(long)` before returning to the caller's accumulator/control-mask
  combination.
- Keeping the result on the operand stack preserves side-effect order:
  object-cell result computation still happens before next-epoch computation
  and before `setPlain(long)`, and the mutable object-cell state is still
  written before the helper returns.

Required evidence:

- The accepted P2.2.5 ten-run gate is the acceptance baseline: full TEST
  `Calc` lower/upper median `181/183 ms`, and full obfusjack `Seq`
  lower/upper median `312/312 ms`.
- Fresh P2.2.5 TEST Calc 1ms JFR at
  `build/jvm-runtime-perf/jfr/p2-2-5-current-test-calc-1ms.jfr` completed
  with `Calc: 198ms` and no unexpected runtime/log scan hits. `jfr view
  hot-methods` reported `127` samples, with hot Calc CFF bodies still
  dominating: `a.u.l(Object[], long)=34`, `a.u.m(Object[], long)=22`, and
  `a.u.o(Object[], long)=21`. The invokedynamic-flow helper was secondary at
  `a.na.kf(...)=2`, matching the user direction to keep this pass on
  key-update rather than invokedynamic.
- Fresh P2.2.5 PrintInlining evidence at
  `build/jvm-runtime-perf/p2-2-5-current-full-test-print-inlining.stdout.log`
  reports `a.a::j (302 bytes)` and shows `AtomicLong.getPlain` /
  `AtomicLong.setPlain` inline-hot inside the key-update helper. The same log
  shows Calc methods `a.u.l`, `a.u.m`, and `a.u.o` are large hot compiled
  methods and repeatedly encounter `a.a::j`.
- Fresh bytecode inspection of current full TEST
  `a.a.j([Ljava/lang/Object;IIII)I` shows:
  - the helper stores the decoded object-cell result in local `11` at bytecode
    offset `175`;
  - it computes `nextEpoch`, stores it in local `12`, calls
    `AtomicLong.setPlain(J)V` at offset `253`, and then reloads local `11` at
    offset `256`;
  - that result is then XORed with the class-mask accumulator and control-mask
    result before `IRETURN`.

Invariant mapping:

| Current token object-cell path | P2.2.6 token object-cell path | Preserved invariant |
| --- | --- | --- |
| helper computes decoded object-cell result and stores it in `objectResultLocal` | helper computes the same result and leaves it on the operand stack | returned object-cell word is unchanged |
| helper computes `nextEpoch` after the object-cell result | unchanged, with the result below the next-epoch computation on the operand stack | key-state update order is unchanged |
| helper writes the next packed object-cell state with `AtomicLong.setPlain(long)` before returning | unchanged, with the result below the receiver/argument stack for `setPlain(long)` | mutable object-cell state update is preserved |
| helper reloads `objectResultLocal` after `setPlain(long)` | helper uses the already-stacked result after `setPlain(long)` returns | same value reaches the accumulator/control-mask XOR |

Subtasks:

1. `[x]` Record and review the object-cell result stack-carry plan.
   Evidence requirement: this P2.2.6 section, current P2.2.5 JFR evidence,
   current P2.2.5 PrintInlining evidence, and current `a.a.j` bytecode
   evidence for the single-use `objectResultLocal`.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passes; the plan
   checkpoint is committed before source edits.
2. `[x]` Implement, validate, measure, and reject/revert the object-cell
   result stack-carry change.
   Evidence requirement: source diff changes only token-material object-cell
   expression emission; generated bytecode shows `a.a.j` still has descriptor
   `([Ljava/lang/Object;IIII)I`, still loads/casts `AtomicLong`, still calls
   `getPlain()J` and `setPlain(J)V`, still stores epoch and next epoch, no
   longer stores or reloads a separate object-cell result local, and leaves the
   object-cell result on stack across `setPlain(J)V`.
   Validation target: same targeted JVM command as P1.1, plus a ten-run common
   runtime gate and required runtime/log scan.
   Completion criteria: targeted JVM validation passes; full-obf TEST `Calc`
   improves from the accepted P2.2.5 ten-run lower/upper median `181/183 ms`,
   or the source change is reverted before proceeding; full-obf obfusjack
   `Seq` remains no worse than the accepted P2.2.5 ten-run lower/upper median
   `312/312 ms`; non-target obfusjack rows stay within the regression budget;
   helper descriptors and CFF dry-run row counts do not regress; no forbidden
   runtime/log/marker scan hit is introduced; subagent implementation review
   passes before commit.

P2.2.6 plan-intake review:

- Subagent plan-intake review passed. The review found the plan
  evidence-backed and scoped to the generic encrypted token-material helper.
- The review confirmed the stack-carry transformation is semantically valid in
  the current linear helper shape: the object-cell result can remain below the
  next-epoch computation and below the `AtomicLong.setPlain(J)V`
  receiver/argument stack, and after the void call returns the same `int`
  remains available for the accumulator/control-mask XOR.
- Residual implementation risks to prove during implementation: generated
  bytecode must verify with the carried value and unchanged `setPlain(J)V`
  ordering, bytecode inspection must show the result `ISTORE`/`ILOAD` pair is
  gone, and runtime acceptance must revert if TEST `Calc` does not improve or
  obfusjack `Seq` regresses.

P2.2.6 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL in 18s` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  `a.a.j([Ljava/lang/Object;IIII)I` kept the same descriptor, still cast the
  selected object cell to `AtomicLong`, still called `getPlain()J`, still
  stored epoch and next epoch, and still called `setPlain(J)V` before the
  accumulator/control-mask XOR. The old object-result store/reload pair was
  removed: after computing the object result, bytecode continued directly into
  next-epoch computation, called `setPlain(J)V` at offset `251`, then used the
  carried result at the following `IXOR`. The helper segment changed from
  `223` javap lines in P2.2.5 to `222` javap lines in P2.2.6.
- A fresh ten-run runtime gate rejected the implementation. Full TEST `Calc`
  sorted values were `208,210,212,214,215,216,216,222,222,223`, with
  lower/upper medians `215/216 ms`. Full obfusjack `Seq` sorted values were
  `306,307,307,308,308,309,309,309,313,319`, with lower/upper medians
  `308/309 ms`.
- Although full obfusjack `Seq` improved from the accepted P2.2.5
  `312/312 ms` median, full TEST `Calc` regressed from the accepted P2.2.5
  `181/183 ms` median. This violates P2.2.6 completion criteria, so the
  source change was fully reverted before proceeding.
- Runtime/log scanning reported only expected text: SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- After the source revert, `JvmFullObfuscationPerfTest --rerun-tasks` passed
  again with `BUILD SUCCESSFUL in 13s` and `19 executed` tasks to regenerate
  accepted-source artifacts. The next key-update attempt must not reuse the
  object-result stack-carry shape unless it first explains and removes the
  generic TEST `Calc` regression.

#### P2.2.7 Rejected candidate: precompute transition-material row bases

Status: `[x]` plan-intake rejected before implementation. No source change was
made for this branch.

Rejected scope:

- The candidate was to change the generated CFF transition-material helper's
  sixth `int` argument from transition row index to precomputed transition row
  base offset. `registerTransitionMaterialRow` already computes
  `base = index * TRANSITION_MATERIAL_ROW_WORDS` to initialize the row; the
  candidate would have returned that base to callsites and removed the helper
  entry `row * TRANSITION_MATERIAL_ROW_WORDS` computation.

Fresh supporting evidence for considering the candidate:

- Fresh post-P2.2.6-revert obfusjack JFR filtered to the actual sequential
  matrix stack (`a.a.o(...)`) captured `273` Seq samples. The first-frame
  distribution was `a.da.za(...)=112`, `a.da.ua(...)=102`, and
  `a.a.fa(Object[], long)=47`; no invokedynamic helper was in this Seq hot
  stack.
- Fresh obfusjack PrintInlining shows `a.da::ua (473 bytes)` compiled as a hot
  transition-material helper and repeatedly reported as `callee is too large`
  or `hot method too big` at `a.a::fa` callsites.
- Fresh bytecode inspection of generated obfusjack `a.da.ua` shows the helper
  starts by multiplying argument local `6` by `37` before any material word
  load, and callsite inspection shows `a.a.fa` passes generated constant row
  indexes.

Plan-intake rejection evidence:

- Subagent plan-intake review failed because this candidate is the same
  row-boundary pre-scaling shape already tested and rejected in P1.6.
- P1.6 already implemented the equivalent contract change, passed the fresh
  targeted JVM validation command, and generated the intended bytecode:
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` no longer contained the entry
  `iload row; bipush 37; imul; istore row`, while `a.a.fa(Object[], long)`
  still invoked the original helper descriptor and passed pre-scaled constants
  such as `12062`, `12099`, and `12136`.
- P1.6 was rejected by a fresh ten-run runtime gate: full TEST `Calc` sorted
  values were `199,206,207,208,210,211,215,218,221,225`, with lower/upper
  medians `210/211 ms`; full obfusjack `Seq` sorted values were
  `308,309,309,309,310,315,316,318,318,349`, with lower/upper medians
  `310/315 ms`.
- The P1.6 shape slightly improved full-obf obfusjack `Seq` from the then
  accepted `318/320 ms` median, but regressed full TEST `Calc` from
  `182/183 ms` to `210/211 ms`. Therefore the same row-boundary pre-scaling
  must not be reused unless a later plan first explains and removes that Calc
  regression generically.

Conclusion:

- No P2.2.7 implementation is allowed from this plan. The next key-update
  optimization must target a different proven runtime path or include new
  concrete evidence that removes the prior P1.6 Calc regression mechanism.

#### P2.2.8 Stream transition-material decoded outputs into `out[]`

Status: `[x]` implemented, measured, rejected, and source reverted.

Scope:

- Optimize only the generic CFF transition-material helper emitted by
  `CffMaterialTables.installTransitionMaterialHelper`.
- Keep the existing helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J`, transition row layout, row index contract,
  material encryption/masking formulas, method-key high/low decode, domain
  decode, packed `out[]` ABI, hidden method key, CFF block coverage, and
  callsite rewriting unchanged.
- Replace the helper-local decoded output staging pattern with immediate
  output stores: decode `guard` and `path`, pack them into `out[0]`; decode
  `block` and `pc`, pack them into `out[1]`; decode the method-key high/low
  words into the returned method key as before; decode `domain` and pack it
  into the high half of `out[2]`.
- Do not pre-scale transition row bases, do not change transition row
  callsite constants, do not share runtime-source/stack-derived cursor work,
  do not change key-transfer material semantics, and do not add fallback,
  skip, helper-layer rescue, or original-bytecode behavior.

Required evidence:

- Fresh post-P2.2.6-revert obfusjack Seq JFR filtered to
  `a.a.o(double[][], double[][], double[][], long)` captured `273` sequential
  matrix samples. The first-frame distribution was
  `a.da.za(...)=112`, `a.da.ua(...)=102`, and
  `a.a.fa(Object[], long)=47`, with no invokedynamic helper frame in the Seq
  hot stack. The backing artifacts are
  `build/jvm-runtime-perf/jfr/post-p2-2-6-revert-obfusjack-1ms.jfr`,
  `build/jvm-runtime-perf/jfr/post-p2-2-6-revert-obfusjack-1ms-samples.txt`,
  `build/jvm-runtime-perf/jfr/post-p2-2-6-revert-obfusjack-1ms.stdout.log`,
  and
  `build/jvm-runtime-perf/jfr/post-p2-2-6-revert-obfusjack-1ms.stderr.log`.
  This identifies transition/island/key-material helpers as the current Seq
  runtime path.
- Fresh post-P2.2.6-revert obfusjack PrintInlining reports
  `a.da::ua (473 bytes)` as a compiled hot transition-material helper and
  repeatedly reports it as `callee is too large` / `hot method too big` under
  `a.a::fa(Object[], long)`. The backing artifact is
  `build/jvm-runtime-perf/inlining/post-p2-2-6-revert-obfusjack-print-inlining.stdout.log`.
- Fresh accepted-source full TEST PrintInlining reports the same generated
  transition helper shape as `a.a::k (473 bytes)`, repeatedly `callee is too
  large` / `hot method too big` at full TEST callsites. The backing artifact is
  `build/jvm-runtime-perf/p2-2-5-current-full-test-print-inlining.stdout.log`.
- Fresh bytecode inspection of generated transition helpers
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` and
  `a.a.k:(JIII[Ljava/lang/Object;II[J)J` shows the helper decodes
  `guard`, `path`, `block`, `pc`, method-key high/low, and `domain`, stores
  decoded output words into locals, then reloads those locals only to pack
  `out[0]`, `out[1]`, and `out[2]`. The decoded `guard/path/block/pc/domain`
  values are not read by subsequent material-word decode; subsequent decode
  uses the already computed transition-material `baseLocal`. The backing
  artifacts are
  `build/jvm-runtime-perf/post-p2-2-6-revert-obfusjack-a-da.javap.txt` and
  `build/jvm-runtime-perf/post-p2-2-6-revert-test-a-a.javap.txt`.
- Source inspection of `installTransitionMaterialHelper` matches the bytecode:
  after `emitTransitionMaterialBase`, every decoded output word is emitted
  through `emitTransitionMaterialDecodedWord(..., baseLocal, word)`, staged in
  a local, and finally written by `emitTransitionOutStores(...)`.
- P2.2.7 rejected row-base pre-scaling as a P1.6 duplicate. P2.2.8 does not
  change row indexes or callsite row constants, so it does not reuse that
  rejected contract.
- P2.2.6 was rejected after carrying a token object-cell result on the operand
  stack across `AtomicLong.setPlain(J)V`. P2.2.8 does not touch token-material
  object-cell decoding, `AtomicLong.getPlain()J`, `AtomicLong.setPlain(J)V`,
  the token helper, or any mutable object-cell state. It changes only
  transition-helper output packing after decoded transition words have already
  been produced.
- P2.2.4 explicitly did not choose same-mode key-transfer runtime-source
  sharing because stack runtime-source mixing can include
  `StackWalker$StackFrame.getByteCodeIndex()`. P2.2.8 does not touch
  runtime-source acquisition or key-transfer cursors.

Invariant preservation:

| Current transition helper | P2.2.8 transition helper | Preserved invariant |
| --- | --- | --- |
| row argument is a transition row index and helper computes `row * 37` | unchanged | P1.6 rejected row-boundary contract is not reused |
| `emitTransitionMaterialBase` computes one live decode base from input key and current guard/path/block | unchanged | material decode remains keyed by live entry state |
| decoded `guard/path/block/pc/domain` are staged in locals before `out[]` packing | decoded pairs are packed into `out[]` immediately after both words are decoded | packed transition output values are unchanged |
| decoded method-key high/low words are combined into returned `long` method key | unchanged | hidden method-key propagation remains live and dynamic |
| helper writes `out[0]`, `out[1]`, and high half of `out[2]` | unchanged | transition out ABI consumed by callers is unchanged |

Subtasks:

1. `[x]` Record and review the transition decoded-output streaming plan.
   Evidence requirement: this P2.2.8 section, fresh JFR/PrintInlining evidence,
   generated `javap` evidence for `a.da.ua` and `a.a.k`, source evidence for
   `installTransitionMaterialHelper`, and explicit non-duplication against
   P1.6/P2.2.7, P2.2.6, and P2.2.4.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic, evidence-backed,
   invariant-preserving, and compliant before source edits begin.
2. `[x]` Implement, validate, measure, reject, and revert transition
   decoded-output streaming.
   Evidence requirement: source diff changes only transition-material output
   staging in `CffMaterialTables` and any tightly scoped helper emission needed
   for stack-based `out[]` stores; generated transition helper bytecode no
   longer stores/reloads locals solely for `out[0]`, `out[1]`, and `out[2]`;
   helper descriptor, row contract, material decode sequence, and returned
   method key are unchanged.
   Validation target:
   the same targeted JVM command as P1.1:
   `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ObfuscationIntegrationTest --rerun-tasks`,
   followed by generated transition-helper `javap` inspection, topology and
   helper descriptor count comparison, stdout/stderr scan for verifier/linkage
   errors and fallback/skip markers, `hs_err` scan in the repository tree, and
   a fresh ten-run JVM runtime gate for full TEST and full obfusjack generated
   jars.
   Completion criteria: targeted Gradle validation passes without verifier,
   linkage, fallback, skip, or forbidden marker failures; full obfusjack `Seq`
   improves from accepted P2.2.5 ten-run lower/upper median `312/312 ms`;
   full TEST `Calc` does not regress from accepted P2.2.5 ten-run lower/upper
   median `181/183 ms`; Platform, Virtual, Parallel, and VThreads do not
   regress beyond the existing random spread recorded in the accepted P2.2.5
   gate. If either target regresses, revert the source change, regenerate the
   accepted-source artifact, record rejection evidence, and commit only the
   rejection record.

P2.2.8 plan-intake review:

- Initial subagent review failed because the first plan draft did not record
  concrete artifact paths, did not explicitly distinguish the change from the
  rejected P2.2.6 object-cell stack-carry shape, and did not list bytecode,
  topology, log, and `hs_err` checks in the validation target.
- The plan was revised before source edits to add exact JFR, PrintInlining,
  and `javap` artifact paths; to state that P2.2.8 does not touch token
  object-cell decoding, `AtomicLong.getPlain()J`, `AtomicLong.setPlain(J)V`,
  or mutable object-cell state; and to expand validation to the same targeted
  JVM command as P1.1 plus bytecode/topology/log/runtime-gate checks.
- Re-review passed. The subagent confirmed P2.2.8 is generic to the generated
  transition-material helper, keeps the helper descriptor and row contract
  unchanged, does not reduce CFF coverage, does not add fallback/static-key
  exposure/ABI weakening, and is ready for a plan-only checkpoint before source
  work.

P2.2.8 rejection evidence:

- The implementation changed only `CffMaterialTables`: it replaced the
  transition-material helper's decoded `guard/path/block/pc/domain` local
  staging with direct decoded pair/high stores to `out[0]`, `out[1]`, and
  `out[2]`. It did not change token object-cell code, `AtomicLong` access,
  key-transfer runtime-source code, helper descriptors, row layout, or callsite
  row indexes.
- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL in 19s` and `19 executed`
  tasks.
- Fresh generated bytecode inspection showed the TEST transition helper
  `a.a.k:(JIII[Ljava/lang/Object;II[J)J` and obfusjack transition helper
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` kept the same descriptor and still
  performed the helper-entry `row * 37` multiplication. The implemented
  bytecode wrote `out[0]`, `out[1]`, and `out[2]` directly with `LASTORE`
  after decoded material words, while method-key high/low still combined into
  the returned `long`.
- Fresh topology/helper descriptor inspection after the implementation showed
  the transition helper descriptor count stayed at
  `(JIII[Ljava/lang/Object;II[J)J = 1` for both generated full TEST and
  obfusjack artifacts. Full TEST CFF counts were
  `transition=621`, `step=485`, and `island=493`; full obfusjack CFF counts
  were `transition=391`, `step=465`, and `island=347`.
- Runtime/log scans for the targeted generated artifacts found no verifier
  errors, linkage errors, fallback/skip markers, SIGSEGV/SIGABRT, native
  fallback markers, or fresh `hs_err` files. The only matched runtime strings
  were the expected obfusjack `Caught MyException: boom` line and the existing
  SnakeGame headless stderr rows from both original and full-obf runs.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-8-10x/runtime-medians.tsv` rejected the
  implementation:
  - full TEST `Calc` sorted values were
    `177,179,181,182,183,185,187,192,195,196`, with lower/upper medians
    `183/185 ms`, regressing from accepted P2.2.5 `181/183 ms`.
  - full obfusjack `Seq` sorted values were
    `335,336,337,337,340,342,343,348,351,353`, with lower/upper medians
    `340/342 ms`, regressing from accepted P2.2.5 `312/312 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=78/79 ms`,
    `Virtual=82/82 ms`, `Parallel=8/8 ms`, and `VThreads=8/8 ms`.
- The source change was reverted with `apply_patch`; `git diff --
  neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffMaterialTables.java`
  became empty. After the revert, `JvmFullObfuscationPerfTest --rerun-tasks`
  passed again with `BUILD SUCCESSFUL in 14s` and `19 executed` tasks,
  regenerating accepted-source artifacts.
- The next transition-material optimization must not move transition `out[]`
  stores ahead of later decoded material words in this direct-store shape,
  because that fresh runtime gate regressed both full TEST `Calc` and
  obfusjack `Seq`.

#### P2.2.9 Stream compressed-island material helper locals

Status: `[x]` implemented, measured, rejected, and source reverted.

Scope:

- Optimize only the generic compressed CFF island material helper emitted by
  `CffIslandMaterial.installCompressedIslandMaterialHelper`.
- Keep the existing helper descriptor
  `(JIII[Ljava/lang/Object;III)I`, island material table layout, cursor mode
  encoding, runtime-source bucket adjustment, class-key mask formula,
  material word identity, helper count, CFF block coverage, and callsite
  rewriting unchanged.
- Replace helper-local single-use staging for the island material entry array,
  row words array, decoded material value, class-key words array, and final
  mask with operand-stack streaming inside the helper. The helper still loads
  the same `Object[]` island material slot, same `int[]` row selected by the
  adjusted cursor, same `word` index, and same class-key word selected by the
  computed mask.
- Do not change `emitCffIslandRuntimeSourceCursorFromLocal`, do not specialize
  or remove the cursor mode branch, do not change runtime-source acquisition,
  do not change compressed island row registration/unpacking, do not change
  transition/key-transfer/token helpers, and do not add fallback, skip,
  helper-layer rescue, or original-bytecode behavior.

Required evidence:

- After reverting P2.2.8 and regenerating accepted-source artifacts, a fresh
  obfusjack JFR run at
  `build/jvm-runtime-perf/jfr/p2-2-8-revert-obfusjack-1ms.jfr` completed with
  `Seq: 335 ms` and `=== All tests completed ===`; stdout/stderr are at
  `build/jvm-runtime-perf/jfr/p2-2-8-revert-obfusjack-1ms.stdout.log` and
  `build/jvm-runtime-perf/jfr/p2-2-8-revert-obfusjack-1ms.stderr.log`.
- `jfr view hot-methods` on that fresh artifact showed
  `a.da.za(long, int, int, int, Object[], int, int, int)` in the hot method
  table with `11` samples. Filtering the execution samples at
  `build/jvm-runtime-perf/jfr/p2-2-8-revert-obfusjack-1ms-samples.txt` to the
  sequential matrix stack `a.a.o(...)` captured `35` Seq samples, including
  `a.da.za(...)=11`, `a.da.ua(...)=17`, and `a.a.fa(...)=35`.
- Fresh bytecode inspection at
  `build/jvm-runtime-perf/p2-2-8-revert-obfusjack-a-da.javap.txt` shows
  `a.da.za:(JIII[Ljava/lang/Object;III)I` first adjusts the cursor, then
  stores the island `Object[]` material entries into local `9`, stores the
  selected `int[]` row into local `10`, stores `row[word]` into local `11`,
  stores the computed mask into local `12`, stores class-key words into local
  `13`, then reloads those locals only to compute `value ^ mask`.
- Source inspection of `installCompressedIslandMaterialHelper` matches the
  bytecode: `entriesLocal`, `wordsLocal`, `valueLocal`, `maskLocal`, and
  `classWordsLocal` are helper-internal staging locals after cursor
  adjustment. The material value and final mask are pure integer/array-read
  expressions and no mutable object-cell, `AtomicLong`, key-transfer ticket,
  or transition `out[]` side effect is present in this helper.
- P1.7 rejected mandatory-thread cursor branch removal for compressed island
  material. P2.2.9 does not alter cursor mode extraction, cursor masking, or
  `emitCffIslandRuntimeSourceCursorFromLocal`; it keeps the current mode
  branch and bucket formula unchanged.
- P2.2.6 rejected carrying a token object-cell result across
  `AtomicLong.setPlain(J)V`; P2.2.8 rejected moving transition `out[]` stores
  ahead of later decoded material words. P2.2.9 does neither: it only streams
  pure compressed-island helper array reads and integer masks before the
  single `IRETURN`.

Invariant preservation:

| Current compressed-island helper | P2.2.9 compressed-island helper | Preserved invariant |
| --- | --- | --- |
| cursor mode is extracted, cursor index masked, and nonzero mode adjusts cursor with `source ^ (source >>> 16)` bucket bits | unchanged | P1.7 rejected cursor contract is not reused |
| helper loads `material[CFF_ISLAND_MATERIAL_SLOT]`, selects `entries[cursor]`, casts to `int[]`, and reads `row[word]` | unchanged, without single-use `entries/words/value` locals | selected protected material word is unchanged |
| mask uses live `key`, `guard`, `path`, `block`, adjusted cursor, word, class-key table, and constant seal | unchanged, without single-use `mask/classWords` locals | live key/state masking remains dynamic |
| helper returns `value ^ mask` | unchanged | material decode result is unchanged |

Subtasks:

1. `[x]` Record and review the compressed-island local streaming plan.
   Evidence requirement: this P2.2.9 section, fresh post-P2.2.8-revert JFR
   evidence, generated `javap` evidence for `a.da.za`, source evidence for
   `installCompressedIslandMaterialHelper`, and explicit non-duplication
   against P1.7, P2.2.6, and P2.2.8.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic, evidence-backed,
   invariant-preserving, and compliant before source edits begin.
2. `[x]` Implement, validate, measure, and reject/revert compressed-island
   local streaming.
   Evidence requirement: source diff changes only compressed-island material
   helper expression emission in `CffIslandMaterial`; generated bytecode for
   `a.da.za` no longer stages locals solely for `entries`, `words`, `value`,
   `mask`, or `classWords`, while descriptor, cursor branch, material slot,
   class-key word seal, and return expression are unchanged.
   Validation target: the same targeted JVM command as P1.1, followed by
   generated compressed-island helper `javap` inspection, topology/helper
   descriptor comparison, stdout/stderr scan for verifier/linkage errors and
   fallback/skip markers, `hs_err` scan in the repository tree, and a fresh
   ten-run JVM runtime gate for full TEST and full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes without verifier,
   linkage, fallback, skip, or forbidden marker failures; full obfusjack `Seq`
   improves from accepted P2.2.5 ten-run lower/upper median `312/312 ms`;
   full TEST `Calc` does not regress from accepted P2.2.5 ten-run lower/upper
   median `181/183 ms`; non-target Platform, Virtual, Parallel, and VThreads
   remain within the accepted P2.2.5 runtime spread. If either target regresses,
   revert the source change, regenerate the accepted-source artifact, record
   rejection evidence, and commit only the rejection record.

P2.2.9 plan-intake review:

- Subagent plan-intake review passed. The review confirmed that only this plan
  file is changed, the evidence is fresh and concrete, the scope is generic to
  `CffIslandMaterial.installCompressedIslandMaterialHelper`, the branch does
  not reuse P1.7/P2.2.6/P2.2.8 rejected shapes, and the validation target is
  sufficient.
- Required post-implementation checks from the review: descriptor
  `a.da.za:(JIII[Ljava/lang/Object;III)I` unchanged; cursor mode extraction,
  cursor mask, and runtime-source branch still present; no local staging solely
  for `entries`, `words`, `value`, `mask`, or `classWords`; same material slot,
  class-key seal, and `value ^ mask` return; targeted JVM suite passes; full
  obfusjack `Seq` improves from `312/312 ms`; TEST `Calc` does not regress
  from `181/183 ms`; no verifier/linkage/fallback/skip/`hs_err` hits.

P2.2.9 rejection evidence:

- The implementation changed only `CffIslandMaterial`: it removed the generated
  compressed-island helper's single-use `entries`, `words`, `value`, `mask`,
  and `classWords` local staging, used operand-stack streaming for the same
  `material[CFF_ISLAND_MATERIAL_SLOT]`, `entries[cursor]`, `row[word]`, mask,
  and class-key word reads, moved `modeLocal` to local `9`, and reduced
  `maxLocals` from `15` to `10`.
- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL in 19s` and `19 executed`
  tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-9-obfusjack-a-da.javap.txt` showed
  `a.da.za:(JIII[Ljava/lang/Object;III)I` kept the same descriptor, cursor
  mode extraction, cursor mask, and runtime-source branch. The helper still
  loaded slot `77`, selected `entries[cursor]`, read `row[word]`, used class
  key slot `65`, applied the same class-key seal, and returned `value ^ mask`.
  The old stores/reloads for local `9` through `13` were gone except for the
  mode local at `9`.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-9-10x/runtime-medians.tsv` rejected the
  implementation:
  - full TEST `Calc` sorted values were
    `173,174,174,175,175,176,176,177,180,184`, with lower/upper medians
    `175/176 ms`, improving from accepted P2.2.5 `181/183 ms`.
  - full obfusjack `Seq` sorted values were
    `308,309,309,314,315,322,324,329,331,336`, with lower/upper medians
    `315/322 ms`, regressing from accepted P2.2.5 `312/312 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=83/84 ms`,
    `Virtual=82/85 ms`, `Parallel=8/8 ms`, and `VThreads=8/8 ms`.
- Runtime/log scans for the P2.2.9 run and regenerated test result found no
  verifier errors, linkage errors, fallback/skip markers, SIGSEGV/SIGABRT,
  native fallback markers, or fresh `hs_err` files. The only text match was
  the generated XML attribute `skipped="0"` for the passing
  `JvmFullObfuscationPerfTest`.
- Because full obfusjack `Seq` failed the required `312/312 ms` acceptance
  baseline, the source change was reverted with `apply_patch`; `git diff --
  neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffIslandMaterial.java`
  became empty. After the revert, `JvmFullObfuscationPerfTest --rerun-tasks`
  passed again with `BUILD SUCCESSFUL in 14s` and `19 executed` tasks,
  regenerating accepted-source artifacts.
- The next compressed-island/key-material optimization must not reuse this
  local-streaming shape as-is. It helps full TEST `Calc` but does not satisfy
  the full obfusjack `Seq` gate.

#### P2.2.10 Walk transition-material row words with a helper-local cursor

Status: `[x]` implemented, validated, measured, and implementation review
passed.

Scope:

- Optimize only the generated transition-material helper's internal material
  word load shape for `TRANSITION_MATERIAL_HELPER_DESC`.
- Keep the existing helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J`, callsite row argument contract, helper
  entry `row * TRANSITION_MATERIAL_ROW_WORDS` multiplication, material table
  slot, transition row layout, decoded guard/path/block/pc/domain words,
  method-key high/low reconstruction, `out[]` contract, CFF block coverage,
  and callsite rewriting unchanged.
- Replace repeated helper-internal `rowBase + constantOffset` word-address
  arithmetic with a monotonic helper-local row cursor. The helper still starts
  from the same row base and reads the same transition row words in the same
  order; only the bytecode used to address consecutive words changes from
  repeated `ILOAD row; push offset; IADD` to `IINC row, delta; ILOAD row`.
- Do not change transition row registration, row index values passed by
  callsites, transition `out[]` stores or loads, compressed island material,
  token object-cell helpers, invokedynamic, runtime-source cursor logic, or any
  fallback/skip/original-bytecode behavior.

Required evidence:

- The accepted-source artifact regenerated after P2.2.9 rejection completed a
  fresh obfusjack JFR run at
  `build/jvm-runtime-perf/jfr/p2-2-10-current-obfusjack-1ms.jfr` with
  `Seq: 327 ms` and `=== All tests completed ===`; stdout/stderr are at
  `build/jvm-runtime-perf/jfr/p2-2-10-current-obfusjack-1ms.stdout.log` and
  `build/jvm-runtime-perf/jfr/p2-2-10-current-obfusjack-1ms.stderr.log`.
- `jfr view hot-methods` for that fresh artifact recorded
  `a.da.ua(long, int, int, int, Object[], int, int, long[])` in the hot method
  table with `10` samples. Filtering
  `build/jvm-runtime-perf/jfr/p2-2-10-current-obfusjack-1ms-samples.txt` to
  stack traces containing the sequential matrix frame `a.a.o(...)` captured
  `28` Seq samples, including `a.da.ua(...)=10`, `a.da.za(...)=9`,
  `a.a.fa(Object[], long)=7`, and one sample each in `a.ba.yr(...)` and
  `a.ba.cs(...)`.
- Fresh bytecode inspection at
  `build/jvm-runtime-perf/p2-2-10-current-obfusjack-a-da.javap.txt` shows
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` keeps the helper-entry
  `iload row; bipush 37; imul; istore row`, then repeatedly loads transition
  row words with `ALOAD material; ILOAD row; push constant offset; IADD;
  IALOAD` for offsets `0` through `36`.
- Source inspection shows this bytecode is emitted by
  `CffMaterialTables.installTransitionMaterialHelper` through
  `CffIslandMaterial.emitTransitionMaterialBase`,
  `emitTransitionMaterialDecodedWord`, `emitTransitionMaterialMaskFromBase`,
  and `emitTransitionMaterialWordLoad`. The word load offsets are emitted in
  strictly increasing order for this helper: base words `0..8`, then seven
  decoded transition words at offsets `9..36`.
- P1.6 and P2.2.7 rejected changing the call-boundary row contract to pass
  pre-scaled row bases. P2.2.10 keeps the same callsite argument and the same
  helper-entry `row * 37` multiplication, so it does not reuse that rejected
  contract change.
- P2.2.8 rejected moving transition decoded output stores directly into
  `out[]`. P2.2.10 does not change `out[]` stores or callsite `out[]` loads;
  it only changes helper-internal material word address arithmetic before the
  same decoded values are produced.

Invariant preservation:

| Current transition helper | P2.2.10 transition helper | Preserved invariant |
| --- | --- | --- |
| callsite passes a transition row index and helper multiplies it by `37` | unchanged | P1.6/P2.2.7 rejected call-boundary contract is not reused |
| each transition word load addresses `material[rowBase + offset]` | helper mutates a private row cursor to the same `rowBase + offset` before `IALOAD` | selected protected material word is unchanged |
| base mask uses live method key, guard, path, block, class-key table, and row base words | unchanged | live key/state masking remains dynamic |
| decoded guard/path/block/pc/domain and method high/low are stored to locals, packed to `out[]`, and key is returned | unchanged | transition state transfer ABI is unchanged |

Subtasks:

1. `[x]` Record and review the transition row-cursor plan.
   Evidence requirement: this P2.2.10 section, fresh accepted-source JFR
   evidence, generated `javap` evidence for `a.da.ua`, source evidence for
   the transition-material helper word-load path, and explicit
   non-duplication against P1.6/P2.2.7 and P2.2.8.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, invariant-preserving, and compliant before source edits
   begin.
2. `[x]` Implement, validate, and measure transition row-cursor word loads.
   Evidence requirement: source diff changes only transition-material helper
   word-load emission and matching abstract method signatures if needed;
   generated bytecode for `a.da.ua` keeps descriptor, helper-entry `row * 37`,
   material slot, decoded values, `out[]` stores, and returned key unchanged,
   while consecutive material word loads no longer contain repeated
   `push offset; IADD` address arithmetic.
   Validation target: the same targeted JVM command as P1.1, followed by
   generated transition helper `javap` inspection, helper descriptor/topology
   comparison, stdout/stderr scan for verifier/linkage errors and
   fallback/skip markers, `hs_err` scan in the repository tree, and a fresh
   ten-run JVM runtime gate for full TEST and full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes without verifier,
   linkage, fallback, skip, or forbidden marker failures; full obfusjack
   `Seq` improves from accepted P2.2.5 ten-run lower/upper median
   `312/312 ms`; full TEST `Calc` does not regress from accepted P2.2.5
   ten-run lower/upper median `181/183 ms`; non-target Platform, Virtual,
   Parallel, and VThreads remain within the accepted P2.2.5 runtime spread.
   If either target regresses, revert the source change, regenerate the
   accepted-source artifact, record rejection evidence, and commit only the
   rejection record.

P2.2.10 plan-intake review:

- Subagent plan-intake review passed. The review confirmed that only this plan
  file is changed, the evidence is concrete, the scope is generic to the
  transition-material helper, and the branch does not reuse P1.6/P2.2.7
  call-boundary row-base pre-scaling or P2.2.8 direct `out[]` store movement.
- Required post-implementation checks from the review: `a.da.ua` still has
  descriptor `(JIII[Ljava/lang/Object;II[J)J`, still performs the helper-entry
  `row * 37`, still returns the same key and writes the same `out[]` values,
  and consecutive transition word loads no longer retain repeated
  `push offset; IADD` address arithmetic.

P2.2.10 acceptance evidence:

- The implementation changed only the transition-material helper word-load
  emission path in `CffSharedState`, `CffMaterialTables`, and
  `CffIslandMaterial`. It added a compile-time emitter cursor object and used
  it only while emitting the generated transition-material helper. No runtime
  helper, fallback, callsite rewrite, material registration, row table layout,
  compressed island helper, token object-cell helper, or invokedynamic path was
  changed.
- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL in 21s` and `19 executed`
  tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-10-obfusjack-a-da.javap.txt` showed
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` kept the same descriptor, still
  loaded transition material slot `75`, still performed helper-entry
  `iload row; bipush 37; imul; istore row`, still used material row words
  `0..36` in order, still wrote `out[0]`, `out[1]`, and `out[2]` with the
  same packed guard/path/block/pc/domain shape, and still returned the decoded
  method key. Consecutive transition material word loads changed from repeated
  `push offset; IADD; IALOAD` addressing to `IINC rowLocal, 1; ILOAD rowLocal;
  IALOAD`.
- Fresh topology/helper descriptor evidence from
  `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json` kept
  one generated transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J` per full fixture. Current topology counts
  were TEST `transition=621`, `step=518`, `island=493`; obfusjack
  `transition=391`, `step=471`, `island=347`; SnakeGame `transition=110`,
  `step=220`, `island=96`; evaluator `transition=455`, `step=800`,
  `island=432`.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-10-10x/runtime-medians.tsv` accepted
  the implementation:
  - full TEST `Calc` sorted values were
    `173,173,175,178,179,179,179,181,183,183`, with lower/upper medians
    `179/179 ms`, improving from accepted P2.2.5 `181/183 ms`.
  - full obfusjack `Seq` sorted values were
    `301,301,302,302,307,309,309,311,313,314`, with lower/upper medians
    `307/309 ms`, improving from accepted P2.2.5 `312/312 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=77/79 ms`,
    `Virtual=80/80 ms`, `Parallel=8/8 ms`, and `VThreads=9/10 ms`. These
    values stayed inside the accepted P2.2.5 observed ten-run spreads:
    Platform `67..96 ms`, Virtual `73..86 ms`, Parallel `7..8 ms`, and
    VThreads `8..11 ms`.
- Runtime/log scans for the P2.2.10 ten-run output found no verifier errors,
  linkage errors, fallback/skip markers, SIGSEGV/SIGABRT, native fallback
  markers, or fresh `hs_err` files.
- Subagent implementation review passed. The review confirmed the diff is
  scoped to the transition-material helper word-load emission path plus this
  evidence update, the cursor object is emitter-side only, generated
  `a.da.ua` preserves descriptor/slot/`row * 37`/`out[]`/returned-key
  invariants, runtime gate acceptance is valid, topology/helper descriptor
  evidence does not show reduced CFF coverage, and unrelated dirty native,
  core, test, and untracked files must stay out of the checkpoint commit.

#### P2.2.11 Replace packed transition-state `long[]` carrier with `int[]`

Status: `[x]` rejected after measurement; source reverted and accepted-source
artifact regenerated.

Scope:

- Optimize only the generated CFF transition-state carrier used by transition
  helpers, step helpers, and transition outliners to return decoded `int`
  state through an array.
- Replace the current packed `long[]` transition carrier with an `int[]`
  carrier using explicit slots:
  - `0 = guard`.
  - `1 = path`.
  - `2 = block`.
  - `3 = pc`.
  - `4 = domain`.
  - `5 = result/token low value`.
  - `6 = thread identity hash` for runtime-source mixing.
- Keep the returned method key as `long`, keep the same live method-entry key
  inputs, transition material rows, step material rows, outliner dispatch
  formulas, result route masks, runtime-source cursor semantics, CFF block
  coverage, fake/poison semantics, and helper generation coverage.
- Update only generated/internal helper descriptors and descriptor recognizers
  that currently carry the transition state as `[J`, including
  `TRANSITION_MATERIAL_HELPER_DESC`, `STEP_MATERIAL_HELPER_DESC`,
  `CffTransitionOutliner.DESC`, and relocatable CFF helper descriptor checks.
  These are generated helper ABIs, not original application JVM ABI surfaces.
- Do not change transition row indexes, row-base scaling, row cursor logic,
  transition material word order, direct `out[]` store timing, token
  object-cell helpers, compressed island material, invokedynamic, reflection,
  MethodHandle, LambdaMetafactory, hidden method-key injection, fallback/skip
  behavior, or original bytecode coverage.

Required evidence:

- P2.2.10 accepted-source ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-10-10x/runtime-medians.tsv` is the
  current comparison baseline: full TEST `Calc=179/179 ms` and full
  obfusjack `Seq=307/309 ms`.
- Fresh accepted-source JFR at
  `build/jvm-runtime-perf/jfr/p2-2-11-current-obfusjack-1ms.jfr` completed
  with `=== All tests completed ===`. `jfr view hot-methods` recorded
  `a.da.ua(long, int, int, int, Object[], int, int, long[])` with `12`
  samples, `a.da.za(...)` with `9` samples, and `a.a.fa(Object[], long)` with
  `4` samples in the current hot-method table.
- Filtering
  `build/jvm-runtime-perf/jfr/p2-2-11-current-obfusjack-1ms-samples.txt` to
  stacks containing the sequential matrix frame `a.a.o(...)` found `25`
  sequential samples: `a.da.ua(...)=12`, `a.da.za(...)=9`, and
  `a.a.fa(Object[], long)=4`. This binds the carrier work to the obfusjack
  `Seq` runtime path rather than to parallel/thread setup samples.
- Fresh bytecode inspection at
  `build/jvm-runtime-perf/p2-2-11-current-obfusjack-a-a.javap.txt` shows the
  hot `a.a.fa(Object[], long)` path allocates a `newarray long` carrier,
  invokes `a.da.ua:(JIII[Ljava/lang/Object;II[J)J`, then reloads and unpacks
  the decoded transition state through repeated `LALOAD`, `LUSHR`, and `L2I`
  sequences before dispatch.
- The same bytecode inspection shows generated transition outliners still use
  descriptor shape `(JIIIII[J)J`, so the packed carrier is shared by both the
  material-helper path and outlined transition helper path.
- Fresh bytecode inspection at
  `build/jvm-runtime-perf/p2-2-11-current-obfusjack-a-da.javap.txt` shows
  generated `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` still performs the
  helper-entry `row * 37`, decodes the same transition material words, writes
  packed state to `out[0]`, `out[1]`, and `out[2]` with `LASTORE`, and returns
  the decoded method key as `long`.
- Source inspection shows the current carrier contract is centralized in
  `CffKeyTransferRewriter.emitInitTransitionOut`, `emitTransitionOutStores*`,
  `emitTransitionOutPairStore*`, `emitTransitionOutHighStore`,
  `emitTransitionOutPairLoad`, `emitTransitionOutHighLoad`, and
  `emitTransitionOutLowLoad`; the same helpers are consumed by
  `CffMaterialTables.installTransitionMaterialHelper`,
  `CffMaterialTables.installStepMaterialHelper`,
  `CffKeyStateEmitter.emitStepKeys`, and `CffTransitionOutliner`.
- `CffIslandMaterial.emitCffIslandCallsiteRuntimeSource` currently reads the
  thread hash from `long out[3]` for runtime-source mixing; this must become
  `int out[6]` without removing the thread contribution.

Invariant preservation:

| Current carrier | P2.2.11 carrier | Preserved invariant |
| --- | --- | --- |
| `long[0]` high bits hold guard and low bits hold path | `int[0]` guard and `int[1]` path | decoded guard/path values remain separate live `int` state |
| `long[1]` high bits hold block and low bits hold pc | `int[2]` block and `int[3]` pc | decoded block/pc values remain separate live `int` state |
| `long[2]` high bits hold domain and low bits hold result/token | `int[4]` domain and `int[5]` result/token | domain and result routing remain live and mask-protected |
| `long[3]` holds thread identity hash | `int[6]` holds thread identity hash | runtime-source thread mixing remains live |
| helper returns decoded method key as `long` | unchanged | hidden method-key propagation remains live |

Rejected-route separation:

- P1.6 and P2.2.7 changed the transition row call-boundary contract by passing
  pre-scaled row bases. P2.2.11 keeps row indexes, row-base scaling, and the
  P2.2.10 helper-local row cursor unchanged.
- P2.2.8 moved decoded transition output stores directly into `out[]`.
  P2.2.11 does not move store timing or decode formulas; it only changes the
  carrier element type and removes pair pack/unpack operations.
- P2.2.9 streamed compressed-island local staging. P2.2.11 does not touch
  compressed-island material lookup or its value/mask decode shape.
- The current evidence also shows `invokeDynamic` is not the primary P1 path;
  P2.2.11 does not alter invokedynamic bootstrap, caching, or callsite
  materialization.

Subtasks:

1. `[x]` Record and review the transition-state carrier plan.
   Evidence requirement: this P2.2.11 section, fresh P2.2.10 runtime baseline,
   fresh accepted-source JFR evidence, generated `javap` evidence for
   `a.a.fa` and `a.da.ua`, source evidence for every centralized carrier
   store/load/descriptor site, and explicit non-duplication against rejected
   P1.6/P2.2.7, P2.2.8, and P2.2.9 routes.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, correctly bounded to generated/internal transition-state
   carrier ABI, invariant-preserving, and compliant before source edits begin.
2. `[x]` Implement, validate, and measure the `int[]` transition-state carrier.
   Evidence requirement: source diff changes only the transition-state carrier
   allocation, centralized carrier store/load helpers, generated/internal
   helper descriptors/descriptor checks, and the runtime-source thread-hash
   slot load required by the carrier slot map.
   Validation target: the same targeted JVM command as P1.1, followed by
   generated bytecode inspection for `a.a.fa`, `a.da.ua`, transition outliners,
   and step helper callsites; helper descriptor/topology comparison;
   stdout/stderr scan for verifier/linkage errors and fallback/skip markers;
   `hs_err` scan in the repository tree; and a fresh ten-run JVM runtime gate
   for full TEST and full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes; generated bytecode
   shows transition-state carriers are allocated with `NEWARRAY int`, internal
   helper descriptors use `[I` for the transition carrier, carrier reloads use
   `IALOAD` without transition-state `LALOAD/LUSHR/L2I` pair unpacking,
   thread-hash runtime-source mixing still reads the carrier slot, full
   obfusjack `Seq` improves from accepted P2.2.10 ten-run lower/upper median
   `307/309 ms`, full TEST `Calc` does not regress from accepted P2.2.10
   `179/179 ms`, and Platform, Virtual, Parallel, and VThreads remain within
   the accepted P2.2.10 observed spreads. If either target regresses, revert
   the source change, regenerate the accepted-source artifact, record
   rejection evidence, and commit only the rejection record.

P2.2.11 plan-intake review:

- Subagent plan-intake review passed. The review confirmed that the plan is
  evidence-backed by the current P2.2.10 ten-run baseline, fresh JFR/sample
  attribution, generated `javap` evidence for the `[J` transition carrier
  allocation and pack/unpack path, and source-level carrier ownership sites.
- The review confirmed the scope is generic and architecture-level because it
  targets the generated transition-state carrier contract across transition
  helpers, step helpers, and outliners, and limits descriptor changes to
  generated/internal helper ABIs rather than original application JVM ABI
  surfaces.
- Required post-implementation checks from the review: update all centralized
  carrier descriptors and descriptor checks together, leave unrelated
  non-carrier `[J` uses untouched, prove generated bytecode uses `[I`
  carriers and `IALOAD` instead of transition-state `LALOAD/LUSHR/L2I`
  unpacking, keep CFF/topology coverage unchanged, and beat the P2.2.10
  obfusjack `Seq` baseline without TEST `Calc` regression.

P2.2.11 rejection evidence:

- The first implementation changed only the generated/internal
  transition-state carrier ABI and matching tests: `CffSharedState` helper
  descriptors, `CffKeyTransferRewriter` carrier allocation/store/load helpers,
  `CffTransitionOutliner.DESC`, `CffClassSetup` relocatable descriptor
  checks, `CffIslandMaterial` runtime-source thread-hash load, and the test
  descriptor expectations. It changed the transition carrier from `long[4]`
  to `int[7]`, kept the returned method key as `long`, and did not alter row
  registration, transition material word order, token object-cell helpers,
  compressed island material, invokedynamic, or fallback/skip behavior.
- The first implementation passed the targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests with `BUILD SUCCESSFUL in 18s`
  and `19 executed` tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-11-int-carrier-obfusjack-a-a.javap.txt` showed
  the hot `a.a.fa(Object[], long)` path allocated `newarray int`, stored the
  thread identity hash at carrier slot `6`, invoked
  `a.da.ua:(JIII[Ljava/lang/Object;II[I)J`, invoked outliners with
  `(JIIIII[I)J`, and reloaded transition state with `IALOAD`.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-11-int-carrier-obfusjack-a-da.javap.txt` showed
  generated `a.da.ua:(JIII[Ljava/lang/Object;II[I)J` kept helper-entry
  `row * 37`, decoded transition material words in the same order, wrote
  decoded state with `IASTORE`, and returned the decoded method key as `long`.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-11-10x/runtime-medians.tsv` rejected
  the first implementation:
  - full TEST `Calc` sorted values were
    `174,179,179,180,180,181,182,189,190,194`, with lower/upper medians
    `180/181 ms`, regressing from accepted P2.2.10 `179/179 ms`.
  - full obfusjack `Seq` sorted values were
    `290,294,302,303,305,305,305,307,317,329`, with lower/upper medians
    `305/305 ms`, improving from accepted P2.2.10 `307/309 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=74/78 ms`,
    `Virtual=79/79 ms`, `Parallel=7/7 ms`, and `VThreads=8/8 ms`.
- A second implementation kept the same `int[7]` carrier ABI but changed
  adjacent pair store/load emission to reuse one array reference with `DUP`,
  reducing the duplicate `ALOAD` work introduced by the first int-carrier
  implementation. It did not change descriptor scope, row contracts, decode
  formulas, store timing, or helper coverage.
- The second implementation also passed the targeted JVM validation command
  with `BUILD SUCCESSFUL in 18s` and `19 executed` tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-11-int-carrier-final-obfusjack-a-a.javap.txt`
  and
  `build/jvm-runtime-perf/p2-2-11-int-carrier-final-obfusjack-a-da.javap.txt`
  showed the same `[I` carrier descriptors, `newarray int`, `IALOAD` reloads,
  `IASTORE` decoded-state writes, preserved `row * 37`, and returned method
  key as `long`.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-11-final-10x/runtime-medians.tsv`
  rejected the second implementation:
  - full TEST `Calc` sorted values were
    `172,177,178,180,182,182,182,183,185,187`, with lower/upper medians
    `182/182 ms`, regressing from accepted P2.2.10 `179/179 ms`.
  - full obfusjack `Seq` sorted values were
    `299,304,305,306,307,309,309,311,312,312`, with lower/upper medians
    `307/309 ms`, matching but not improving accepted P2.2.10 `307/309 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=77/79 ms`,
    `Virtual=80/81 ms`, `Parallel=8/8 ms`, and `VThreads=8/8 ms`.
- Runtime repeat-log scans for both rejected ten-run outputs found no verifier
  errors, linkage errors, fallback/skip markers, SIGSEGV/SIGABRT, or native
  fallback markers, and an `hs_err` scan since `2026-05-26 22:48:39 +0800`
  found no files.
- Because neither int-carrier implementation satisfied the required
  no-TEST-Calc-regression gate, the source changes were reverted with
  `apply_patch`; `git diff --` for the touched JVM/CFF source and test files
  became empty. After the revert,
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
  passed with `BUILD SUCCESSFUL in 14s` and `19 executed` tasks, regenerating
  the accepted-source artifacts.
- The next transition/key-update optimization must not reuse this whole
  `int[7]` transition-state carrier replacement shape as-is. It can improve
  obfusjack `Seq` in one form, but the extra decoded-state stores do not
  satisfy the TEST `Calc` gate.

#### P2.2.12 Precompute compressed-island effective material cursor once

Status: `[x]` rejected after measurement; source reverted and accepted-source
artifact regenerated.

Scope:

- Optimize only the generated compressed CFF island material cursor path used
  before calls to `CFF_ISLAND_MATERIAL_HELPER_DESC`.
- Keep the compressed-island helper descriptor
  `(JIII[Ljava/lang/Object;III)I`, material table layout, encrypted blob
  layout, class-key seal, value/mask decode formula, helper local staging,
  result routing, fake/poison rows, CFF block coverage, dynamic key flow,
  runtime-source thread/stack contribution, and hidden method-key propagation
  unchanged.
- Move the encoded-cursor mode extraction and runtime-source cursor adjustment
  from every compressed material word decode inside the shared helper to the
  generated island dispatch helper entry, where the runtime source is already
  computed once for the island. The helper receives the same descriptor but
  its `cursor` argument becomes the already-effective material cursor; its
  `source` argument remains present for descriptor compatibility but is no
  longer used by the helper body.
- Do not change transition `out[]` carrier type, transition material helper
  descriptor, transition row cursor, row-base contract, token object-cell
  helpers, P2.2.9 rejected local-streaming shape, invokedynamic, reflection,
  MethodHandle, LambdaMetafactory, fallback/skip behavior, or original
  bytecode coverage.

Required evidence:

- P2.2.11 source changes were rejected and reverted, so P2.2.10 remains the
  accepted runtime baseline: full TEST `Calc=179/179 ms` and full obfusjack
  `Seq=307/309 ms`.
- Fresh accepted-source JFR at
  `build/jvm-runtime-perf/jfr/p2-2-12-current-obfusjack.jfr` completed with
  `Seq: 314 ms` and `=== All tests completed ===`.
- Filtering
  `build/jvm-runtime-perf/jfr/p2-2-12-current-obfusjack-samples.txt` to
  stacks containing the sequential matrix frame `a.a.o(...)` found `60`
  sequential samples: `a.da.za(...)=27`, `a.da.ua(...)=20`,
  `a.a.fa(Object[], long)=11`, and `a.ba.wr(...)=2`. Sample stacks place
  `a.da.za` under generated outliners such as `a.ba.vr/wr`,
  `a.ba.xr/yr`, `a.ba.bs/cs`, and `a.ba.zr/as`, then under
  `a.a.fa(Object[], long)` and the obfusjack `Seq` matrix frame.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-12-current-obfusjack-a-da.javap.txt` shows
  `a.da.za:(JIII[Ljava/lang/Object;III)I` begins by extracting mode from the
  encoded cursor with `iload cursor; bipush 24; iushr`, masking the cursor
  with `16777215`, branching on mode, and adding
  `((source ^ (source >>> 16)) & 3)` to the cursor before loading the island
  material entry.
- Source inspection shows
  `CffTransitionOutliner.createIslandDispatchHelper` already calls
  `emitCffIslandCallsiteRuntimeSource(...)` once at helper entry and stores
  the result in `helperSourceLocal` before token dispatch, while each real
  result block later calls
  `emitCompressedIslandMaterialWordDecode(...)` with the same encoded
  `islandMaterialCursor`.
- Source inspection shows
  `CffIslandMaterial.installCompressedIslandMaterialHelper` currently repeats
  the mode extraction and source-based cursor adjustment for every material
  word decode inside the helper. That repeated work is the exact path sampled
  as `a.da.za` in the sequential matrix stack.
- P1.7 previously rejected mandatory-thread cursor branch removal. Its
  recorded bytecode shape still computed the source bucket adjustment inside
  `a.da.za` for every material word decode after removing the mode branch,
  and its ten-run gate regressed TEST `Calc` from `199/199 ms` to
  `218/221 ms` while improving obfusjack `Seq` from `321/322 ms` to
  `312/315 ms`.
- P2.2.12 is materially different from the P1.7 rejected bytecode shape:
  `a.da.za` will not compute either the mode branch or the source bucket
  adjustment per material word. The source bucket adjustment is computed once
  at the generated island dispatch helper entry, before token dispatch, and
  the already-effective cursor is reused by every material-word decode in
  that helper. This is the specific P1.7 overlap that must be inspected in
  generated bytecode before runtime acceptance.

Invariant preservation:

| Current path | P2.2.12 path | Preserved invariant |
| --- | --- | --- |
| outliner helper computes runtime source once, stores it, and passes encoded cursor to every `za` call | outliner helper computes effective material cursor once and passes that cursor to every `za` call | runtime source remains live and per-helper, but repeated per-word cursor decode is removed |
| `za` extracts mode/cursor and adjusts cursor from source before material lookup | `za` receives the already-effective cursor and performs the same material lookup | selected encrypted island material row is unchanged |
| `za` keeps descriptor `(JIII[Ljava/lang/Object;III)I` | descriptor unchanged; source parameter remains present but unused | generated helper ABI and callsite shape remain compatible |
| value/mask/class-key decode uses live key, guard, path, block, cursor, word, and class-key table | unchanged decode formula after effective cursor selection | dynamic key and class-key masking remain live |

Rejected-route separation:

- P2.2.9 rejected streaming the compressed-island helper's local staging for
  `entries`, `words`, `value`, `mask`, and `classWords`. P2.2.12 keeps that
  local staging shape intact and changes only where the effective cursor is
  computed.
- P1.7 rejected removing the mandatory-thread mode branch while leaving
  source bucket adjustment inside `za` for every word decode. P2.2.12 does not
  reuse that helper shape: `za` receives an effective cursor and does not
  perform per-word source adjustment; the generated outliner helper computes
  the effective cursor once. If generated bytecode still contains the P1.7
  helper-entry `cursor & mask` plus `(source ^ source >>> 16) & 3` adjustment
  sequence inside `za`, the implementation must be rejected before runtime
  acceptance.
- P2.2.11 rejected replacing the whole transition-state carrier with
  `int[7]`. P2.2.12 does not change transition `out[]`, transition helper
  descriptors, or transition state stores/loads.
- P1.6/P2.2.7 and P2.2.10 concern transition material row addressing.
  P2.2.12 does not change transition row indexes, row bases, or row cursors.

Subtasks:

1. `[x]` Record and review the compressed-island effective-cursor plan.
   Evidence requirement: this P2.2.12 section, fresh accepted-source JFR
   evidence after the P2.2.11 rejection, generated `javap` evidence for
   `a.da.za`, source evidence for the outliner helper entry and compressed
   material helper, P1.7 rejection evidence and bytecode-shape separation,
   and explicit non-duplication against P1.7, P2.2.9, P2.2.11, and
   transition-row rejected routes.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, descriptor-preserving, invariant-preserving, and compliant
   before source edits begin.
2. `[x]` Implement, validate, and measure effective cursor precomputation.
   Evidence requirement: source diff changes only compressed-island effective
   cursor computation and matching callsite emission; the helper descriptor,
   material slots, encrypted blob layout, helper local staging, value/mask
   decode, and class-key seal remain unchanged.
   Validation target: the same targeted JVM command as P1.1, followed by
   generated bytecode inspection for `a.da.za` and generated outliner helper
   callsites, helper descriptor/topology comparison, stdout/stderr scan for
   verifier/linkage errors and fallback/skip markers, `hs_err` scan in the
   repository tree, and a fresh ten-run JVM runtime gate for full TEST and
   full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes; generated
   `a.da.za:(JIII[Ljava/lang/Object;III)I` no longer contains the helper-entry
   encoded-cursor mode extraction/branch or the P1.7 per-word source bucket
   adjustment sequence while preserving descriptor and value/mask decode;
   generated outliner helpers compute the effective cursor once before token
   dispatch; full obfusjack `Seq` improves from accepted P2.2.10 ten-run
   lower/upper median `307/309 ms`; full TEST `Calc` does not regress from
   accepted P2.2.10 `179/179 ms`; and Platform, Virtual, Parallel, and
   VThreads remain within the accepted P2.2.10 observed spreads. If either
   target regresses, revert the source change, regenerate the accepted-source
   artifact, record rejection evidence, and commit only the rejection record.

P2.2.12 initial plan-intake review:

- Subagent plan-intake review failed because the first P2.2.12 draft did not
  address P1.7's rejected mandatory-thread cursor branch-removal route. The
  required revision was to add explicit P1.7 rejected-route separation, define
  the exact bytecode-shape difference, and state why the P1.7 per-word source
  bucket adjustment shape is not being retried.
- This revision adds P1.7 dependency reasoning: P1.7 removed the branch but
  kept source bucket adjustment inside `za` for every material word; P2.2.12
  moves the effective cursor computation to the outliner helper entry and
  requires generated bytecode to prove `za` no longer contains either the
  branch or the per-word source adjustment sequence.

P2.2.12 revised plan-intake review:

- Subagent revised plan-intake review passed. The review confirmed that P2.2.12
  now records P1.7's rejected bytecode shape and distinguishes this route:
  P1.7 removed the mode branch but still did per-word source bucket adjustment
  inside `za`, while P2.2.12 requires generated outliner helpers to compute
  the effective cursor once and requires `za` to contain neither the mode
  branch nor the P1.7 `(source ^ source >>> 16) & 3` adjustment sequence.
- The review confirmed the current diff is plan-only, JVM/CFF source diff is
  empty, and the plan is evidence-backed, generic, descriptor-preserving, and
  bounded by javap checks plus the ten-run gate against P2.2.10
  `Calc=179/179 ms` and `Seq=307/309 ms`.

P2.2.12 rejection evidence:

- The implementation changed only `CffIslandMaterial`,
  `CffMaterialTables`, `CffSharedState`, and `CffTransitionOutliner`. It kept
  `CFF_ISLAND_MATERIAL_HELPER_DESC` as `(JIII[Ljava/lang/Object;III)I`,
  kept the compressed material table/blob layout, helper local staging,
  value/mask/class-key decode formula, transition `out[]`, transition helper
  descriptors, token object-cell helpers, invokedynamic, and fallback/skip
  behavior unchanged.
- The implementation passed the targeted JVM validation command for the CFF
  algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests with `BUILD SUCCESSFUL in 18s`
  and `19 executed` tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-12-effective-cursor-obfusjack-a-da.javap.txt`
  showed `a.da.za:(JIII[Ljava/lang/Object;III)I` kept the same descriptor and
  no longer began with `cursor >>> 24` mode extraction, cursor masking, or the
  P1.7 per-word source bucket adjustment sequence. The helper began directly
  with the material slot load, selected `entries[cursor]`, loaded `word`, kept
  class-key slot `65`, applied the class-key seal, and returned `value ^ mask`.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-12-effective-cursor-obfusjack-a-ba.javap.txt`
  showed generated outliner helpers computed the effective cursor before token
  dispatch by folding thread-hash, key/control state, and source bucket
  material into a local, then passed that local to each
  `a.da.za:(JIII[Ljava/lang/Object;III)I` call.
- Fresh topology/helper descriptor evidence from
  `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json` kept
  the compressed island material helper descriptor
  `(JIII[Ljava/lang/Object;III)I` and outliner descriptor `(JIIIII[J)J`.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-12-10x/runtime-medians.tsv` rejected
  the implementation:
  - full TEST `Calc` sorted values were
    `179,182,184,185,189,190,191,193,195,200`, with lower/upper medians
    `189/190 ms`, regressing from accepted P2.2.10 `179/179 ms`.
  - full obfusjack `Seq` sorted values were
    `305,306,307,309,311,312,314,314,315,315`, with lower/upper medians
    `311/312 ms`, regressing from accepted P2.2.10 `307/309 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=80/81 ms`,
    `Virtual=82/83 ms`, `Parallel=8/8 ms`, and `VThreads=8/8 ms`.
- Runtime repeat-log scans for the P2.2.12 ten-run output found no verifier
  errors, linkage errors, fallback/skip markers, SIGSEGV/SIGABRT, or native
  fallback markers, and an `hs_err` scan since `2026-05-26 23:10:20 +0800`
  found no files.
- Because the implementation regressed both TEST `Calc` and obfusjack `Seq`,
  the source changes were reverted with `apply_patch`; `git diff --` for the
  touched JVM/CFF source files became empty. After the revert,
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
  passed with `BUILD SUCCESSFUL in 14s` and `19 executed` tasks, regenerating
  the accepted-source artifacts.
- The next compressed-island cursor optimization must not reuse this
  effective-cursor hoisting shape as-is. Unlike P1.7, it removed the per-word
  source adjustment from `za`, but the ten-run gate still regressed both
  target rows.

#### P2.2.13 Cache step-material mask words inside the key-update helper

Status: `[x]` measured, rejected, and source reverted.

Scope:

- Optimize only the generated CFF step-material helper emitted by
  `installStepMaterialHelper`.
- Keep `STEP_MATERIAL_HELPER_DESC` as `(JIII[Ljava/lang/Object;I[J)J`, helper
  count, row index contract, packed `long[]` step material table, row
  registration, runtime-source acquisition, StackWalker/thread mixing,
  `out[]` carrier ABI, hidden method-key update formulas, CFF coverage, fake
  rows, poison rows, and hard-fail behavior unchanged.
- Load the protected per-row step-material mask words `5`, `6`, and `7` once
  into helper-local `int` scratch locals after the row base is computed, then
  reuse those locals in `emitStepMaterialDecodeBase` and
  `emitStepMaterialWordMask`.
- Do not cache decoded material words, decoded guard/path/block, decoded
  method keys, runtime source, thread/stack values, row indexes, static seeds,
  descriptors, plaintext constants, token-material words, transition material,
  compressed-island material, invokedynamic state, or object-cell state.
- Do not change `emitMaterializedStepKeys` callsites or the generated
  step-material row contents; this is helper-internal row-word load reduction
  only.

Required evidence:

- P2.2.10 remains the accepted runtime baseline after the rejected P2.2.11 and
  P2.2.12 branches: full TEST `Calc=179/179 ms` and full obfusjack
  `Seq=307/309 ms` from
  `build/jvm-runtime-perf/repeats-p2-2-10-10x/runtime-medians.tsv`.
- Fresh accepted-source TEST Calc 1ms JFR at
  `build/jvm-runtime-perf/jfr/p2-2-13-current-test-calc-1ms.jfr` completed
  with `Calc: 179ms`. `jfr view hot-methods` reported the Calc bodies as the
  dominant runtime path: `a.u.l(Object[], long)=30`,
  `a.u.m(Object[], long)=12`, `a.u.o(Object[], long)=10`, and
  `a.u.n(Object[], long)=3`; `a.na.kf(...)=7` remained secondary. This keeps
  the next branch on key/material updates rather than invokedynamic.
- Fresh bytecode inspection at
  `build/jvm-runtime-perf/p2-2-13-current-test-a-u.javap.txt` shows the
  obfuscated Calc class has `30` calls to the generated step-material helper
  `a.a.m:(JIII[Ljava/lang/Object;I[J)J`, alongside `86` token-material calls,
  `39` method-key calls, `17` transition-material calls, `8` key-transfer
  calls, and `17` island-material calls.
- Fresh PrintInlining at
  `build/jvm-runtime-perf/p2-2-13-current-full-test-print-inlining.stdout.log`
  shows `a.a::m (1248 bytes)` is compiled separately and reports
  `callee is too large` at `26` Calc callsites. This proves the step-material
  helper is on the runtime-compiled Calc key-update path and is not only
  obfuscation-time code.
- Fresh bytecode inspection of `a.a.m` at
  `build/jvm-runtime-perf/p2-2-13-current-test-a-a.javap.txt` shows the
  current generated helper executes `31` `laload` instructions. Most decoded
  step-material word masks reload row words `5`, `6`, and `7` through
  `aload words; iload base; iconst_2/iconst_3; iadd; laload` sequences even
  though those three protected mask words are immutable for the selected row
  during one helper invocation.
- Source inspection shows `emitStepMaterialDecodeBase` and
  `emitStepMaterialWordMask` are the repeated consumers of step-material row
  words `5`, `6`, and `7`, while `registerStepMaterialRow` still initializes
  the packed row through the existing protected material table.

Rejected-route separation:

- P2.2.3 rejected packed-pair scratch caching in the token-material helper
  after it improved TEST `Calc` but regressed obfusjack `Seq`. P2.2.13 does
  not touch token-material helper `a.a.j`, token packed-pair extraction,
  token object-cell state, or token row layout.
- P2.2.4 rejected key-transfer method-key fold hoisting. P2.2.13 does not
  touch key-transfer helper `a.a.l`, key-transfer runtime-source cursors, or
  class-integrity ticket issue/defer behavior.
- P2.2.10 accepted transition-material row cursor walking. P2.2.13 does not
  touch transition-material helper `a.a.k`, transition row indexes, row-base
  contracts, or transition `out[]` stores.
- P2.2.11 rejected the transition-state `int[7]` carrier. P2.2.13 does not
  change any carrier descriptor or packed transition-state ABI.
- P2.2.12 rejected compressed-island effective cursor hoisting. P2.2.13 does
  not touch compressed-island material helper `a.a.p`, island cursors, or
  runtime-source cursor mode handling.

Invariant preservation:

| Current step-material helper | P2.2.13 step-material helper | Preserved invariant |
| --- | --- | --- |
| callsite passes live key, guard, path, block, object material, row, and `out[]` | unchanged | helper ABI, live CFF state inputs, and hidden key propagation stay unchanged |
| helper computes `base = row * STEP_MATERIAL_ROW_LONGS` and reads row words from the class-owned packed `long[]` | unchanged | selected protected step-material row stays unchanged |
| decode-base and every decoded-word mask reload row words `5`, `6`, and `7` from the same row | helper loads row words `5`, `6`, and `7` once into private scratch locals and reuses them | mask values are identical and remain selected by the live row |
| runtime-source acquisition uses thread and stack material per helper invocation | unchanged | BCI/thread-sensitive dynamic key flow remains live |
| tiny updates and optional method-key update consume decoded words and write guard/path/block/key | unchanged formulas consume the same decoded words | CFF state transitions and method-key update semantics stay unchanged |

Subtasks:

1. `[x]` Record and review the step-material mask-word caching plan.
   Evidence requirement: this P2.2.13 section, fresh TEST Calc 1ms JFR,
   fresh `a.u` and `a.a.m` `javap` evidence, fresh PrintInlining evidence,
   and explicit non-duplication against P2.2.3, P2.2.4, P2.2.10, P2.2.11,
   and P2.2.12.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, invariant-preserving, and compliant before source edits.
2. `[x]` Implement, validate, measure, reject, and revert step-material
   mask-word caching.
   Evidence requirement: source diff changes only step-material helper
   emission and matching abstract method signatures needed to pass the three
   helper-local mask-word locals; generated `a.a.m` keeps descriptor
   `(JIII[Ljava/lang/Object;I[J)J`, keeps the same `row * 4` base, keeps the
   same runtime-source path, keeps `out[]` writes and method-key update
   formulas, and no longer reloads row words `5`, `6`, and `7` for every
   decoded-word mask.
   Validation target: the same targeted JVM command as P1.1, followed by
   generated bytecode inspection for `a.a.m`, helper descriptor/topology
   comparison, stdout/stderr scan for verifier/linkage errors and
   fallback/skip markers, `hs_err` scan in the repository tree, and a fresh
   ten-run JVM runtime gate for full TEST and full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes; generated step helper
   `laload` count decreases from the current `31` without changing helper
   descriptor or CFF topology; full TEST `Calc` improves from accepted
   P2.2.10 `179/179 ms`; full obfusjack `Seq` does not regress from accepted
   P2.2.10 `307/309 ms`; Platform, Virtual, Parallel, and VThreads remain
   within accepted P2.2.10 observed spreads. If either target regresses,
   revert the source change, regenerate the accepted-source artifact, record
   rejection evidence, and commit only the rejection record.

P2.2.13 plan-intake review:

- Subagent plan-intake review passed. The review confirmed the plan is tied to
  fresh TEST Calc JFR, `a.u` callsite counts, PrintInlining evidence for
  `a.a::m (1248 bytes)` at Calc callsites, and `a.a.m` bytecode showing
  repeated row-word `5/6/7` `laload` loads.
- The review confirmed the scope is generic and helper-internal: it preserves
  `STEP_MATERIAL_HELPER_DESC`, row contract, packed `long[]` material table,
  runtime-source/StackWalker/thread mixing, `out[]` ABI, live key flow, CFF
  coverage, fake/poison rows, and no fallback/skip behavior.
- The review confirmed this branch does not duplicate the rejected token
  packed-pair cache, key-transfer fold hoist, transition carrier, or
  compressed-island cursor routes, and does not interfere with the accepted
  transition row-cursor optimization.
- Required post-implementation checks from the review: `javap` must show the
  same descriptor/topology, fewer `laload`s in `a.a.m`, unchanged
  runtime-source and `out[]` behavior, and the ten-run gate must improve TEST
  Calc over `179/179 ms` without regressing obfusjack `Seq` from
  `307/309 ms`.

P2.2.13 rejection evidence:

- The implementation changed only `CffMaterialTables` and `CffSharedState`.
  It loaded step-material row words `5`, `6`, and `7` once into helper-local
  `int` locals inside the generated step-material helper and threaded those
  locals through the decode-base and decoded-word mask emitters. It did not
  change helper descriptors, row registration, callsites, runtime-source
  acquisition, StackWalker/thread mixing, `out[]` stores, token material,
  transition material, compressed-island material, invokedynamic, or
  fallback/skip behavior.
- The implementation passed the targeted JVM validation command for the CFF
  algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests with `BUILD SUCCESSFUL in 19s`
  and `19 executed` tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-13-step-mask-test-a-a.javap.txt` and the
  extracted method segment at
  `build/jvm-runtime-perf/p2-2-13-step-mask-test-a-a-m.javap.txt` proved the
  intended route was generated: `a.a.m:(JIII[Ljava/lang/Object;I[J)J` kept
  its descriptor, still computed `row * 4`, still contained
  `Thread.currentThread`, `StackWalker.getInstance`, `Thread.getStackTrace`,
  `out[]` `lastore`, and `lreturn`, and reduced the step helper `laload`
  count from `31` to `10`.
- Fresh topology/helper descriptor evidence from
  `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json`
  during the implementation run kept one step-material helper descriptor
  `(JIII[Ljava/lang/Object;I[J)J` for each generated fixture.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-13-10x/runtime-medians.tsv` rejected
  the implementation:
  - full TEST `Calc` sorted values were
    `174,177,179,180,180,181,184,185,186,187`, with lower/upper medians
    `180/181 ms`, regressing from accepted P2.2.10 `179/179 ms`.
  - full obfusjack `Seq` sorted values were
    `302,308,308,309,309,310,310,311,314,324`, with lower/upper medians
    `309/310 ms`, regressing from accepted P2.2.10 `307/309 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=76/78 ms`,
    `Virtual=82/82 ms`, `Parallel=8/8 ms`, and `VThreads=8/8 ms`.
- Runtime repeat-log scans for the P2.2.13 ten-run output found no verifier
  errors, linkage errors, fallback/skip markers, SIGSEGV/SIGABRT, native
  fallback markers, `translated=0`, or `Native compilation produced no
  libraries`; an `hs_err` scan since `2026-05-26 23:20:00 +0800` found no
  files.
- Because both target rows regressed, the source changes were reverted with
  `apply_patch`; `git diff --` for the touched JVM/CFF source files became
  empty. After the revert,
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
  passed with `BUILD SUCCESSFUL in 14s` and `19 executed` tasks, regenerating
  the accepted-source artifacts.
- The next step-material/key-update optimization must not reuse this
  helper-local mask-word caching shape as-is. It reduces `laload` count, but
  the fresh runtime gate shows the extra locals/load shape regresses both
  TEST `Calc` and obfusjack `Seq`.

### P2.2.14 Cache CFF material carrier reference per protected method

Scope:

- JVM runtime performance only. Do not change obfuscation-time work, native
  code, invokedynamic linkage, helper descriptors, material row layout, hidden
  key parameters, packed parameter carriers, CFF block coverage, or CFF block
  boundaries.
- Insert one generated method-local cache of the class-owned CFF material
  carrier `Object[]` for each CFF-transformed application method that has an
  active `CffClassKeyTable`.
- Use the cached carrier only for original transformed-method callsites that
  currently reload the same static carrier before token-material,
  transition-material, step-material, key-transfer material, and compressed
  island-material helper calls.
- Generated helper methods that do not initialize their own carrier local must
  keep their existing `GETSTATIC` carrier loads.

Required evidence:

- P2.2.10 remains the accepted runtime baseline for source behavior after the
  rejected P2.2.11, P2.2.12, and P2.2.13 branches: full TEST
  `Calc=179/179 ms` and full obfusjack `Seq=307/309 ms` from
  `build/jvm-runtime-perf/repeats-p2-2-10-10x/runtime-medians.tsv`.
- A fresh accepted-source before-edit 10x run from the regenerated current jar
  under `build/jvm-runtime-perf/repeats-p2-2-14-before-10x` completed without
  verifier/linkage/runtime failures. Its sorted target values were TEST
  `Calc=178,179,180,180,181,183,185,193,200,225` with medians `181/183 ms`
  and obfusjack `Seq=305,307,309,313,316,317,323,324,331,340` with medians
  `316/317 ms`. This records current run-to-run noise; acceptance remains
  measured against the stricter accepted P2.2.10 baseline.
- Fresh TEST Calc 1ms JFR at
  `build/jvm-runtime-perf/jfr/p2-2-13-current-test-calc-1ms.jfr` completed
  with `Calc: 179ms` and reported the Calc bodies as the dominant runtime
  path: `a.u.l(Object[], long)=30`, `a.u.m(Object[], long)=12`,
  `a.u.o(Object[], long)=10`, and `a.u.n(Object[], long)=3`; `a.na.kf(...)=7`
  remained secondary. This keeps the branch on key/material update code rather
  than invokedynamic.
- Fresh bytecode inspection at
  `build/jvm-runtime-perf/p2-2-13-current-test-a-u.javap.txt` proves repeated
  static material carrier loads in the hot Calc methods. The same generated
  field `private static final Object[] b` is loaded `20` times in
  `a.u.l(Object[], long)`, `21` times in `a.u.m(Object[], long)`, and `23`
  times in `a.u.o(Object[], long)`. Those methods also contain repeated
  token-material helper calls (`6`, `7`, and `10` respectively), while
  generated transition/step/island/key-transfer callsites in the same class
  load the same carrier immediately before their helper calls.
- Source inspection shows `ensureClassKeyTable` creates the carrier field as
  `ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC`, and
  `installClassKeyTableInit` performs the carrier `PUTSTATIC` in class
  initialization before `table.initEnd()`.
- Source inspection shows `protectedStartLabel` starts constructors only after
  the required owner/super `<init>` call, starts generated table `<clinit>`
  protection after both method-key init and table initialization, and starts
  ordinary methods after method-key initialization. A carrier cache inserted
  immediately before `protectedStart` therefore does not read the table before
  class initialization has installed it and does not execute before a
  constructor's required initialization call.

Rejected-route separation:

- P2.2.3 rejected token helper packed-pair scratch caching. P2.2.14 does not
  change token helper internals, token row layout, token object-cell updates,
  or token material formulas.
- P2.2.10 accepted transition-material row cursor walking. P2.2.14 does not
  change transition row cursors, row registration, row-base arithmetic, or
  `out[]` layout.
- P2.2.11 rejected transition-state `int[7]` carrier ABI changes. P2.2.14
  does not change any helper descriptor, transition carrier descriptor, or
  packed state representation.
- P2.2.12 rejected compressed-island effective cursor hoisting. P2.2.14 does
  not change compressed-island cursor calculation, runtime-source cursor mode,
  or island material word decoding formulas.
- P2.2.13 rejected helper-local step mask-word caching. P2.2.14 does not
  introduce helper-local row-word caches, reduce helper `laload` sequences, or
  change step-material decode masks.

Invariant preservation:

| Current carrier path | P2.2.14 carrier path | Preserved invariant |
| --- | --- | --- |
| each generated callsite executes `GETSTATIC owner.material : Object[]` immediately before helper invocation | a generated prologue executes the same `GETSTATIC` once after key/table/super-init safety point and stores the reference in a new local; callsites execute `ALOAD local` | helper receives the same class-owned carrier reference |
| carrier field is static final and assigned by CFF table initialization | unchanged | no new mutable carrier source, fallback, or alternate table |
| helper descriptors accept the carrier as the same `Object[]` argument | unchanged | JVM ABI and generated helper contracts stay unchanged |
| token, transition, step, key-transfer, and island helpers consume live key/guard/path/block inputs plus carrier cursor/row material | unchanged | dynamic key propagation and keyed material selection stay live |
| generated helper methods use their own frame/local set | unchanged unless a helper explicitly initializes its own cache | no invalid helper-local references to original-method locals |

Subtasks:

1. `[x]` Record and review the per-method material carrier cache plan.
   Evidence requirement: this P2.2.14 section, accepted P2.2.10 runtime
   baseline, fresh P2.2.14 before-edit 10x run, fresh TEST Calc JFR, fresh
   `a.u` carrier-load bytecode counts, and source evidence for final carrier
   field initialization plus constructor/`<clinit>` safe insertion points.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, invariant-preserving, correctly separated from rejected
   P2.2.3/P2.2.11/P2.2.12/P2.2.13 routes, and compliant before source edits.
2. `[x]` Implement, validate, measure, and either accept or reject per-method
   material carrier caching.
   Evidence requirement: source diff is limited to CFF emission and shared
   signatures required to thread an optional material-carrier local through
   original transformed-method helper callsites; generated helper descriptors
   and row layouts stay unchanged; generated hot Calc bytecode shows one
   generated carrier `GETSTATIC`/`ASTORE` at the safe insertion point and
   helper callsites use `ALOAD` instead of repeated carrier `GETSTATIC`.
   Validation target: the same targeted JVM command as P1.1, generated
   bytecode inspection for hot TEST Calc methods and shared helpers,
   stdout/stderr scan for verifier/linkage/runtime errors and fallback/skip
   markers, `hs_err` scan in the repository tree, and a fresh ten-run JVM
   runtime gate for full TEST and full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes; hot Calc repeated
   carrier `GETSTATIC` counts decrease without changing helper descriptors or
   CFF topology; full TEST `Calc` improves from accepted P2.2.10 `179/179 ms`;
   full obfusjack `Seq` does not regress from accepted P2.2.10 `307/309 ms`;
   Platform, Virtual, Parallel, and VThreads remain within accepted P2.2.10
   observed spreads. If either target regresses, revert the source change,
   regenerate the accepted-source artifact, record rejection evidence, and
   commit only the rejection record.

P2.2.14 plan-intake review:

- Subagent plan-intake review passed. The review confirmed the plan is
  evidence-backed, scoped to JVM runtime material/key-update callsites, and
  generic: one per-method cache of the existing class-owned `Object[]`
  carrier, with helper descriptors, row layouts, packed carriers, hidden keys,
  CFF coverage, and live key inputs unchanged.
- The review confirmed the constructor and `<clinit>` safety evidence:
  `protectedStartLabel` places constructor protection after owner/super
  `<init>` and `<clinit>` after both key initialization and table
  initialization; `installClassKeyTableInit` performs the carrier `PUTSTATIC`
  before `initEnd`.
- The review confirmed rejected-route separation from P2.2.3, P2.2.11,
  P2.2.12, and P2.2.13 is explicit and non-overlapping.
- Required post-implementation checks from the review: generated bytecode must
  have the planned shape, one safe `GETSTATIC`/`ASTORE` per protected method,
  helper callsites using `ALOAD`, unchanged helper descriptors/topology, and
  the ten-run gate against the P2.2.10 baseline.

P2.2.14 rejection evidence:

- The implementation changed only JVM CFF emission files:
  `ControlFlowFlatteningPass`, `CffSharedState`, `CffMaterialTables`,
  `CffKeyStateEmitter`, `CffKeyTransferRewriter`, and
  `CffTransitionOutliner`. It inserted one generated method-local material
  carrier cache before `protectedStart`, routed material-carrier loads through
  the cache while emitting original protected methods, and disabled the cache
  while emitting generated helper methods. It did not change helper
  descriptors, material row layouts, token/transition/step/key-transfer/island
  formulas, hidden-key propagation, invokedynamic, native code, or fallback
  behavior.
- The implementation passed the targeted JVM validation command for the CFF
  algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests with `BUILD SUCCESSFUL in 19s`
  and `19 executed` tasks.
- Fresh generated bytecode inspection at
  `build/jvm-runtime-perf/p2-2-14-material-cache-test-a-u.javap.txt` proved
  the planned route was generated in the hot TEST Calc class. Compared with
  accepted-source inspection, carrier `GETSTATIC b:[Ljava/lang/Object;` counts
  dropped from `20/21/23` to `6/7/6` in `a.u.l(Object[], long)`,
  `a.u.m(Object[], long)`, and `a.u.o(Object[], long)` respectively, with
  token-material callsites using cached `ALOAD` carrier values.
- Fresh helper bytecode inspection at
  `build/jvm-runtime-perf/p2-2-14-material-cache-test-a-a.javap.txt` kept the
  shared helper descriptors unchanged, including
  `j(Object[], int, int, int, int)`,
  `k(long, int, int, int, Object[], int, int, long[])`,
  `l(long, int, int, int, Object[], int, int)`,
  `m(long, int, int, int, Object[], int, long[])`, and
  `p(long, int, int, int, Object[], int, int, int)`.
- Fresh ten-run runtime gate at
  `build/jvm-runtime-perf/repeats-p2-2-14-10x/runtime-medians.tsv` rejected
  the implementation:
  - full TEST `Calc` sorted values were
    `180,185,185,185,190,190,190,191,192,198`, with lower/upper medians
    `190/190 ms`, regressing from accepted P2.2.10 `179/179 ms`.
  - full obfusjack `Seq` sorted values were
    `306,311,311,312,312,312,313,320,322,323`, with lower/upper medians
    `312/312 ms`, regressing from accepted P2.2.10 `307/309 ms`.
  - full obfusjack non-target lower/upper medians were `Platform=81/82 ms`,
    `Virtual=85/86 ms`, `Parallel=8/8 ms`, and `VThreads=8/8 ms`.
- Runtime repeat-log scans for the P2.2.14 ten-run output found no verifier
  errors, linkage errors, fallback/skip markers, SIGSEGV/SIGABRT, native
  fallback markers, `translated=0`, or `Native compilation produced no
  libraries`; stderr logs were empty. An `hs_err` scan since
  `2026-05-27 00:00:00 +0800` found no files.
- Because both target rows regressed, the source changes were reverted from
  the saved implementation diff at
  `build/jvm-runtime-perf/p2-2-14-material-cache-source.diff`; `git diff --`
  for the touched JVM/CFF source files became empty. After the revert,
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
  passed with `BUILD SUCCESSFUL in 14s` and `19 executed` tasks, regenerating
  the accepted-source artifacts.
- The next key/material optimization must not retry this per-method material
  carrier cache shape as-is. Reducing repeated carrier `GETSTATIC` counts did
  not improve runtime; the added method-local reference/load shape regressed
  both TEST `Calc` and obfusjack `Seq`.

### P2.2.15 Shrink method-key helper with signed state mixing

Scope:

- JVM runtime performance only. Do not change obfuscation-time work, native
  code, invokedynamic linkage, CFF block coverage, CFF block boundaries,
  hidden-key parameters, packed parameter carriers, or fallback behavior.
- Change only the generated CFF method-key-from-state formula and its matching
  static generator-side formula. The helper descriptor remains `(IIIIJJ)J`,
  and callsites still pass live guard, path, block, pc token, masked method
  salt, and salt mask.
- Replace the current zero-extension of `pathKey` and `pcToken` with direct
  signed `int` to `long` mixing in both runtime helper bytecode and
  `methodKeyFromBlock`. Keep the guard high-word shift, block/method-salt
  mixing, `METHOD_KEY_PC_MIX` multiplication, and nonzero guard.

Required evidence:

- P2.2.10 remains the accepted runtime baseline for source behavior:
  full TEST `Calc=179/179 ms` and full obfusjack `Seq=307/309 ms` from
  `build/jvm-runtime-perf/repeats-p2-2-10-10x/runtime-medians.tsv`.
- P2.2.14 proved repeated material-carrier `GETSTATIC` removal is not an
  acceptable bottleneck fix: reducing hot Calc carrier loads still regressed
  TEST `Calc` to `190/190 ms` and obfusjack `Seq` to `312/312 ms`.
- Fresh post-P2.2.14-revert TEST Calc 1ms JFR at
  `build/jvm-runtime-perf/jfr/p2-2-15-before-test-calc-1ms.jfr`, with samples
  recorded in
  `build/jvm-runtime-perf/jfr/p2-2-15-before-test-calc-1ms-samples.txt`,
  reported the Calc bodies as the dominant runtime path:
  `a.u.l(Object[], long)=27`, `a.u.o(Object[], long)=16`,
  `a.u.m(Object[], long)=9`, and `a.u.n(Object[], long)=3`.
- Fresh post-P2.2.14-revert bytecode inspection at
  `build/jvm-runtime-perf/p2-2-15-before-test-a-u.javap.txt` shows `33`
  method-key helper calls to `a.a.r:(IIIIJJ)J` in the Calc class.
- Fresh post-P2.2.14-revert PrintInlining at
  `build/jvm-runtime-perf/p2-2-15-before-full-test-print-inlining.stdout.log`
  shows `a.a::r (43 bytes)` compiled separately, with many Calc callsites
  reporting `callee is too large`; only a few hot callsites are inlined.
- Fresh post-P2.2.14-revert helper bytecode inspection at
  `build/jvm-runtime-perf/p2-2-15-before-test-a-a.javap.txt` shows
  `a.a.r:(IIIIJJ)J` is 43 bytecode bytes and spends two `ldc2_w
  4294967295; land` mask sequences on zero-extending `pathKey` and `pcToken`
  before mixing. Those masks are the only planned bytecode removal.
- Source inspection shows `methodKeyFromBlock`, `installMethodKeyFromStateHelper`,
  and the no-table `emitMethodKeyFromDecodedState` path all use the same
  zero-extension formula today, so the runtime helper and generator-side
  expected key can be changed together without changing any helper ABI.

Rejected-route separation:

- P2.2.3 rejected token helper packed-pair scratch caching. P2.2.15 does not
  touch token helper internals, token row layout, token object-cell state, or
  token material formulas.
- P2.2.10 accepted transition-material row cursor walking. P2.2.15 does not
  touch transition material rows, cursors, row-base arithmetic, or `out[]`.
- P2.2.11 rejected transition-state carrier ABI changes. P2.2.15 does not
  change helper descriptors, carriers, or packed state representation.
- P2.2.12 rejected compressed-island effective cursor hoisting. P2.2.15 does
  not touch island cursors, runtime-source cursor mode, or island material
  formulas.
- P2.2.13 rejected helper-local step mask-word caching. P2.2.15 does not
  touch step-material helper internals or row-word loads.
- P2.2.14 rejected per-method material carrier caching. P2.2.15 does not
  introduce carrier locals, carrier load caching, or helper emission context.

Invariant preservation:

| Current method-key path | P2.2.15 method-key path | Preserved invariant |
| --- | --- | --- |
| helper descriptor `(IIIIJJ)J` receives guard/path/block/pc plus masked method salt and mask | unchanged | generated ABI and masked salt transfer stay unchanged |
| runtime helper zero-extends path and pc before long mixing | runtime helper sign-extends path and pc before long mixing | live path and pc inputs still drive the method key |
| `methodKeyFromBlock` computes the expected key with the same zero-extension formula as the helper | `methodKeyFromBlock` computes the expected key with the same signed-mixing formula as the helper | runtime key and generated expected key stay semantically linked |
| helper applies `METHOD_KEY_PC_MIX` and nonzero guard | unchanged | pc token remains mixed and method key remains nonzero |
| raw method salt is represented as two masked long constants at callsites | unchanged | raw static method key/salt is not exposed |

No-weakening proof:

- Java `int` to `long` sign extension is injective for the full 32-bit input
  domain, so replacing `((long) x) & 0xFFFFFFFFL` with `(long) x` does not
  introduce collisions among path or pc input states.
- The low 32 bits of `(long) x` exactly equal the original `int` bit pattern;
  the high 32 bits carry the sign bit instead of zeros. No path or pc bit is
  dropped, removed from live flow, or recomputed from static metadata.
- `METHOD_KEY_PC_MIX` remains applied to the signed pc contribution and its
  current value is odd, so the multiplication remains a permutation modulo
  `2^64`; it does not collapse the pc contribution.
- Guard high-word shift, path contribution, block xor method-salt contribution,
  callsite salt masking, and the nonzero-key guard remain present. The changed
  expression is still driven by live decoded CFF state and masked method salt.
- Runtime helper bytecode, no-table helper emission, and generator-side
  `methodKeyFromBlock` are changed together. There is no descriptor-only,
  static-seed, or fallback recomputation path.

Subtasks:

1. `[x]` Record and review the signed method-key mixing plan.
   Evidence requirement: this P2.2.15 section, accepted P2.2.10 runtime
   baseline, P2.2.14 rejected-route evidence, fresh post-P2.2.14-revert TEST
   Calc JFR, fresh `a.u` method-key call count, fresh PrintInlining evidence
   for `a.a::r (43 bytes)`, helper bytecode showing two zero-extension masks,
   the no-weakening proof above, and source evidence that
   runtime/helper/generator formulas can be changed together.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, invariant-preserving, correctly separated from rejected
   routes, and compliant before source edits.
   Completed: subagent plan-intake review passed. Residual risk is that the
   helper may still fail to inline if caller budget dominates; PrintInlining
   inspection and the ten-run runtime gate remain required acceptance controls.
2. `[x]` Implement, validate, measure, and either accept or reject signed
   method-key mixing.
   Evidence requirement: source diff is limited to `methodKeyFromBlock`,
   generated method-key helper emission, and matching no-table method-key
   emission; helper descriptor remains `(IIIIJJ)J`; generated `a.a.r`
   bytecode drops below the normal inline-size threshold while retaining the
   masked salt inputs, `METHOD_KEY_PC_MIX`, and nonzero guard.
   Validation target: the same targeted JVM command as P1.1, generated
   bytecode inspection for `a.a.r`, PrintInlining inspection for method-key
   helper callsites, stdout/stderr scan for verifier/linkage/runtime errors
   and fallback/skip markers, `hs_err` scan in the repository tree, and a
   fresh ten-run JVM runtime gate for full TEST and full obfusjack generated
   jars.
   Completion criteria: targeted JVM validation passes; generated helper
   descriptor/topology is unchanged except for removal of the two path/pc
   zero-extension mask sequences; PrintInlining no longer reports most
   `a.a::r` Calc callsites as `callee is too large`; full TEST `Calc`
   improves from accepted P2.2.10 `179/179 ms`; full obfusjack `Seq` does not
   regress from accepted P2.2.10 `307/309 ms`; Platform, Virtual, Parallel,
   and VThreads remain within accepted P2.2.10 observed spreads. If either
   target regresses, revert the source change, regenerate the accepted-source
   artifact, record rejection evidence, and commit only the rejection record.
   Rejected: targeted JVM validation failed after the signed-mixing source
   change. The failing Gradle report was intentionally overwritten by the
   post-revert accepted-source regeneration, so the stable failure evidence is
   the captured failure transcript from the targeted run:
   `21 tests completed, 2 failed`; `JvmFullObfuscationPerfTest` failed with
   `TEST full-obf run failed`, `StringIndexOutOfBoundsException: Range [0, -1)`
   at `a.b.if`, and `expected: <0> but was: <1>`;
   `JvmInvokeDynamicObfuscationIntegrationTest` failed with
   `ExceptionInInitializerError` caused by
   `StringIndexOutOfBoundsException: Range [0, -1)` in
   `IndyReferenceShapes.__neko_indy_resolve`, also ending in
   `expected: <0> but was: <1>`. This proves the signed method-key formula
   broke protected string/indy key semantics before any runtime performance
   gate. Source was reverted to the accepted zero-extension formula; scoped
   `git diff --` on `CffMaterialTables.java`, `CffKeyStateEmitter.java`, and
   `CffKeyTransferRewriter.java` is empty. Fresh accepted-source regeneration
   with `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
   passed with `BUILD SUCCESSFUL in 14s` and `19 actionable tasks: 19
   executed`. P2.2.15 must not be retried as signed method-key mixing.

### P2.2.16 Split key-transfer material helper for no-runtime-source cursors

Status: `[ ]` planned; source edits not started.

Scope:

- JVM runtime performance only. Do not change obfuscation-time goals, native
  code, invokedynamic linkage, CFF block coverage, CFF block boundaries,
  hidden-key parameters, packed parameter carriers, token material formulas,
  key-transfer row registration, class-integrity ticket semantics, or fallback
  behavior.
- Change only the generated CFF key-transfer material helper surface and the
  generated callsite helper selection for key-transfer material. Keep
  `KEY_TRANSFER_MATERIAL_HELPER_DESC` unchanged for the existing generic
  runtime-source-aware helper.
- Add a separate generated key-transfer material helper for the
  `NONE/NONE` runtime-source mode pair. The new helper must decode the two
  token-material words directly from the two cursor indexes and then issue or
  defer the same class-integrity ticket. It must not emit the thread/stack
  runtime-source branch, mode comparison, bucket selection, or StackWalker path
  because `registerKeyTransferMaterialWord(..., NONE)` registers exactly one
  bucket and `encodeKeyTransferMaterialCursor` leaves the mode bits clear.
- Route a callsite to the new helper only when the source instruction's
  `keyTransferRuntimeSourceMode(sourceInsn)` is
  `KEY_TRANSFER_RUNTIME_SOURCE_NONE`. Route all other modes to the existing
  generic helper unchanged. This keeps async/thread and stack-sensitive paths
  on the current per-cursor runtime-source acquisition path.

Required evidence:

- Fresh accepted-source regeneration after the rejected P2.2.15 source revert
  passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
  reported `BUILD SUCCESSFUL in 14s` with `19 actionable tasks: 19 executed`.
- Fresh P2.2.16 before-edit ten-run baseline at
  `build/jvm-runtime-perf/repeats-p2-2-16-before-10x/runtime-medians.tsv`
  reported full TEST `Calc=178/179 ms` and full obfusjack `Seq=308/309 ms`.
  Non-target full obfusjack medians were `Platform=74/77 ms`,
  `Virtual=79/80 ms`, `Parallel=7/7 ms`, and `VThreads=8/8 ms`.
  Stderr files were empty, an `hs_err` scan found no fresh crash logs, and a
  runtime/log scan found no verifier/linkage/runtime/fallback/skip markers
  after excluding the fixture's expected `Caught MyException: boom` output.
- Fresh TEST Calc JFR at
  `build/jvm-runtime-perf/jfr/p2-2-16-before-test-calc-1ms.jfr`, with samples
  summarized in
  `build/jvm-runtime-perf/jfr/p2-2-16-before-test-calc-1ms-samples.txt`,
  reported the Calc bodies as the dominant runtime path:
  `a.u.o(Object[], long)=18`, `a.u.l(Object[], long)=18`,
  `a.u.m(Object[], long)=7`, `a.na.kf(...)=7`, and
  `a.u.n(Object[], long)=1`.
- Fresh PrintInlining at
  `build/jvm-runtime-perf/p2-2-16-before-full-test-print-inlining.stdout.log`
  reports the generated key-transfer helper `a.a::l (1161 bytes)` as
  `callee is too large` at the hot `a.u.n` callsites and later as
  `hot method too big` at bytecode offsets `1540`, `1584`, `1610`, `1657`,
  `2056`, `2079`, `2452`, and `2475`.
- Fresh helper bytecode at
  `build/jvm-runtime-perf/p2-2-16-before-test-a-a.javap.txt` shows
  `a.a.l:(JIII[Ljava/lang/Object;II)J` is the current key-transfer helper and
  contains the runtime-source mode extraction, same-mode/split-mode branch,
  thread identity/name hashing, stack runtime-source path, token helper calls,
  method-key fold, and ticket helper call in one `1161`-byte method.
- Fresh hot-class bytecode inspection at
  `build/jvm-runtime-perf/p2-2-16-before-test-a-u.javap.txt` shows the hot
  `a.u.n` callsites invoke `a.a.l` eight times with encoded cursor pairs
  `84/98`, `280/294`, `252/266`, `168/182`, `112/126`, `196/210`,
  `140/154`, and `224/238`. All eight cursors have mode `NONE` after applying
  the helper's existing ticket-mask semantics.
- Fresh full-jar key-transfer callsite scan at
  `build/jvm-runtime-perf/p2-2-16-before-kxfer-callsite-modes.normalized.tsv`
  found `457` total key-transfer material callsites across current full TEST
  and full obfusjack generated jars. `453` callsites are `NONE/NONE`, while
  only `4` are `THREAD/THREAD` after stripping the ticket-defer high bit with
  the same `0x7fffffff` mask used by the current helper. TEST contributes
  `212` `NONE/NONE` and `3` `THREAD/THREAD` callsites; obfusjack contributes
  `241` `NONE/NONE` and `1` `THREAD/THREAD` callsites.
- Source inspection shows `keyTransferRuntimeSourceMode(sourceInsn)` computes a
  single runtime-source mode per key-transfer source instruction, and both high
  and low material cursors are registered with that same mode in
  `CffKeyTransferRewriter`. Therefore a generated callsite can choose the
  `NONE/NONE` helper using the already-computed mode without inspecting a
  benchmark class or method.
- Source inspection shows `registerKeyTransferMaterialWord` registers one
  bucket for `KEY_TRANSFER_RUNTIME_SOURCE_NONE` and four buckets for non-NONE
  modes. The planned `NONE/NONE` helper is valid only for the one-bucket
  encoded cursor case and cannot be used for thread/stack cursor modes.

Rejected-route separation:

- P2.2.3 rejected token helper packed-pair scratch caching. P2.2.16 does not
  change token helper internals, token row layout, token object-cell state,
  token helper descriptor, or token material formulas.
- P2.2.4 rejected key-transfer method-key fold hoisting. P2.2.16 does not
  hoist or rewrite the method-key fold formula; it only avoids the
  runtime-source mode machinery when the source mode is statically `NONE`.
- P2.2.8 rejected direct decoded-output stores. P2.2.16 does not move
  `out[]` stores or decoded result routing.
- P2.2.10 accepted transition-material row cursor walking. P2.2.16 does not
  touch transition-material helpers, transition rows, row cursors, or `out[]`.
- P2.2.11 rejected transition-state carrier ABI changes. P2.2.16 does not
  change the packed state carrier ABI or transition/step helper descriptors.
- P2.2.12 rejected compressed-island effective cursor hoisting. P2.2.16 does
  not touch compressed-island cursors, island runtime-source mode handling, or
  island material formulas.
- P2.2.13 rejected step material mask-word caching. P2.2.16 does not change
  step-material helpers or mask-word caching.
- P2.2.14 rejected per-method material carrier caching. P2.2.16 does not
  introduce method-local carrier caching or repeated `GETSTATIC` removal.
- P2.2.15 rejected signed method-key mixing. P2.2.16 does not change
  method-key formula, signedness, masked salts, or invokedynamic/string key
  semantics.

Invariant preservation:

| Current key-transfer path | P2.2.16 key-transfer path | Preserved invariant |
| --- | --- | --- |
| high and low cursors are registered from the same source instruction runtime-source mode | unchanged | cursor mode remains tied to the source instruction and generated row registration |
| generic helper handles NONE, THREAD, STACK, and combined modes using runtime mode extraction and per-cursor source acquisition | unchanged for non-NONE modes | async/thread/stack-sensitive dynamic key flow remains live |
| NONE cursor mode has exactly one token-material bucket and mode bits clear after ticket-mask stripping | callsite with mode NONE invokes a no-runtime-source helper that uses the cursor indexes directly | generated helper matches the row-registration invariant for NONE mode |
| token helper decodes each high/low material word and method-key fold xors the live entry key into the decoded token | unchanged in both helpers | high/low key-transfer material stays driven by live guard/path/block/key inputs |
| class-integrity ticket helper consumes the packed high/low transfer result and the defer/issue mode | unchanged | ticket issue/defer behavior and returned key value stay semantically linked |
| non-NONE helper may call Thread/StackWalker-derived runtime-source code per cursor | unchanged | stack BCI-sensitive and thread-sensitive paths are not shared, skipped, or approximated |

No-weakening proof:

- The generated callsite already computes `runtimeSourceMode` before registering
  high and low key-transfer cursors. Selecting a helper by that mode does not
  add a new static seed, descriptor-only recomputation, or fallback path.
- For `runtimeSourceMode == NONE`, the current generic helper executes the
  mode-zero branch and stores `baseCursor` into each cursor local before token
  decoding. The new helper emits only that mode-zero behavior and then runs the
  same token decode, method-key fold, pack, and ticket helper sequence.
- For all non-NONE modes, callsites continue to invoke the existing generic
  helper. Thread and stack runtime-source bucket selection still mixes the live
  method entry key, guard, path, block, current thread identity/name, and stack
  material where applicable.
- The planned change does not alter the encrypted token material rows, class
  object table, hidden method-key parameter, CFF dispatcher state, CFF block
  graph, invoke descriptors for original application methods, or protected
  reflective/dynamic call paths.
- The new helper is an additional generated CFF helper inside the existing
  bootstrap/material surface. It is not a Java-side bridge, adapter, fallback,
  or helper layer outside the planned transform surface.

Subtasks:

1. `[x]` Record and review the no-runtime-source key-transfer helper split
   plan.
   Evidence requirement: this P2.2.16 section, fresh post-revert Gradle
   regeneration proof, fresh P2.2.16 before-edit ten-run baseline, fresh JFR,
   fresh PrintInlining showing `a.a::l (1161 bytes)` too large at hot
   callsites, fresh generated bytecode for `a.a.l` and hot `a.u.n`, full-jar
   key-transfer mode distribution, source evidence for mode registration and
   one-bucket NONE rows, rejected-route separation, invariant table, and
   no-weakening proof.
   Validation target: subagent plan-intake review.
   Completion criteria: subagent confirms the plan is generic,
   evidence-backed, invariant-preserving, correctly decomposed, separated from
   rejected routes, and compliant before source edits.
   Completed: initial plan-intake review failed because the validation target
   only listed `JvmFullObfuscationPerfTest`; the plan was revised before any
   source edits to require the full targeted JVM suite used by P1.1, including
   invokedynamic, constants, strings, method-parameter, renamer, and integration
   coverage. Re-review passed. The reviewer confirmed routing by
   `keyTransferRuntimeSourceMode(sourceInsn) == NONE` is generic, high/low
   cursors are registered from the same mode, NONE rows are one-bucket,
   non-NONE stays on the existing runtime-source-aware helper, and the new
   helper remains within the existing generated CFF material-helper surface.
2. `[ ]` Implement, validate, measure, and either accept or reject the
   no-runtime-source key-transfer helper split.
   Evidence requirement: source diff is limited to CFF key-transfer helper
   naming/table metadata, helper installation, and callsite helper selection;
   non-NONE callsites still invoke the existing generic helper; NONE/NONE
   callsites invoke the new helper; generated non-NONE helper bytecode remains
   runtime-source-aware; generated NONE/NONE helper bytecode has no
   Thread/StackWalker/runtime-source branch and still calls the token helper
   twice plus the ticket helper once.
   Validation target: the same targeted JVM command as P1.1,
   `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ObfuscationIntegrationTest --rerun-tasks`,
   followed by generated bytecode inspection for key-transfer helper
   descriptors and callsite routing, PrintInlining inspection for the new
   helper and hot Calc callsites, stdout/stderr scan for verifier/linkage/runtime
   errors and fallback/skip markers, repository `hs_err` scan, and a fresh
   ten-run JVM runtime gate for full TEST and full obfusjack generated jars.
   Completion criteria: targeted JVM validation passes; full-jar callsite mode
   scan shows `NONE/NONE` callsites routed to the new helper and non-NONE
   callsites still routed to the generic helper; generated helper topology does
   not reduce CFF coverage or original application method obfuscation; full
   TEST `Calc` improves from the fresh P2.2.16 before-edit `178/179 ms` and
   accepted P2.2.10 `179/179 ms`; full obfusjack `Seq` does not regress from
   fresh P2.2.16 before-edit `308/309 ms` or accepted P2.2.10 `307/309 ms`;
   Platform, Virtual, Parallel, and VThreads remain within the accepted
   P2.2.10/P2.2.16 observed spreads. If either target regresses or targeted
   validation fails, revert the source change, regenerate the accepted-source
   artifact, record rejection evidence, and commit only the rejection record.

### P3 Add source-controlled JVM runtime ablation reporting

Scope:

- Convert the manual ablation matrix into a source-controlled test/report so
  future runtime optimization claims do not depend on ad hoc shell runs.
- Keep it test-only and JVM-only.
- Record per-run runtime timing rows, medians, output jar sizes, CFF topology,
  helper descriptor counts, largest methods, and invalid ablation failures.
- Do not add obfuscation wall-clock or pass-time acceptance gates in this row.

Required evidence before editing:

- Existing manual ablation logs from `build/jvm-perf-ablation`.
- Existing `JvmFullObfuscationPerfTest` reporting fields to reuse.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Completion criteria:

- Report includes original, CFF-only-stack, full-no-indy,
  full-no-const-string, and full JVM obf rows.
- The report records invalid ablations as invalid instead of treating failures
  as performance data.
- No runtime or transform behavior changes are included.
- Subagent review passes before commit.

## Review Status

First plan-intake review failed because the plan lacked numeric acceptance
targets, had coarse P1/P2 tasks, did not define exact runtime validation
commands, and did not tie P1 to an exact hot runtime path. This revision records
the user-approved P1/P2 numeric targets, exact runtime commands, finer
subtasks, and JFR hot-path evidence for the first P1 repair surface.

Second plan-intake review failed because P2.1 lacked an explicit runtime target
with concrete TEST Calc JFR commands, output paths, map path, and log
inspection. This revision adds that P2.1 runtime target before implementation.

Third plan-intake review passed. No implementation blockers remained under the
JVM-runtime-only scope and the user-approved P1/P2 thresholds.
