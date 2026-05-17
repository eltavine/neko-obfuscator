# Native Compile Parallelization Plan - 2026-05-17

## Objective

Reduce native obfuscation compile latency by changing generated native output
from one monolithic C translation unit into multiple C translation units that
can be compiled in parallel, while preserving native runtime semantics and
obfuscated output behavior.

## Evidence Recorded Before Implementation

- `CCodeGenerator.generateSource(...)` currently emits all support code,
  translated native implementations, manifest tables, dispatchers, trampolines,
  and `JNI_OnLoad` into one generated source string.
- `NativeBuildEngine.build(...)` currently writes only `neko_native.c` plus
  `neko_native.h`, then invokes one `zig cc -shared ... neko_native.c`.
- The generated build manifest currently records only `generated.c.path` and
  one compiler command per target, so existing tests inspect a single source
  file.
- Existing focused native fixtures cover three jars through
  `NativeObfuscationHelper`: `test.jar`, `snake.jar`, and `test21.jar`.
- Existing `.plan/native-full-repair.md` identifies four manual native-only full
  jars: `evaluator.jar`, `test21.jar`, `snake.jar`, and `test.jar`.

## Runtime Target Rows

### [ ] P1: Split generated source model

- Scope: introduce a generated-source artifact model that can represent one
  shared support translation unit plus method implementation translation units.
- Required evidence: code references showing all current source consumers are
  updated from one source string to a structured source set.
- Validation command or runtime target: compile-time tests covering
  `NativeTranslator.translate(...)` and `CCodeGenerator` source generation.
- Completion criteria: unit tests can still inspect generated source content,
  and no source consumer assumes a single generated C file.

### [ ] P2: Parallel native compilation and link

- Scope: change `NativeBuildEngine` to write all generated source files, compile
  each `.c` file to an object file, and link objects into the target library.
- Required evidence: generated build manifest lists all source and object files;
  target command lines show per-source compile commands plus a link command.
- Validation command or runtime target: `R-build` using the repository Gradle
  wrapper, with generated manifest inspection.
- Completion criteria: native build produces a library from multiple object
  files and preserves hard failure on compile/link errors.

### [ ] P3: Generated-C audit compatibility

- Scope: update generated-C audit and performance capture tests to consume all
  generated C paths rather than a single `generated.c.path`.
- Required evidence: tests parse manifest source list and audit every generated
  C file.
- Validation command or runtime target: targeted native generated-C audit test.
- Completion criteria: audit still finds translated `neko_native_impl_*`
  regions and reports forbidden JNI/fallback markers across all generated
  source files.

### [ ] P4: Runtime equivalence for four jars

- Scope: freshly regenerate native-obfuscated artifacts for
  `evaluator.jar`, `test21.jar`, `snake.jar`, and `test.jar`, then compare
  their runtime outputs with baseline jars using the same application args and
  documented non-interactive mode where required.
- Required evidence: fresh obfuscation logs, generated manifest inspection,
  stdout/stderr for baseline and native runs, and `hs_err` scan.
- Validation command or runtime target: manual four-jar baseline-vs-native run
  plus existing native integration/perf tests.
- Completion criteria: all four native outputs match the accepted baseline
  output contract, no fatal JVM error occurs, no `translated=0`, no
  `Native compilation produced no libraries`, and no fallback marker appears.

## Notes

- This plan must not change JVM obfuscation transforms, method selection,
  control-flow flattening, key propagation, JNI fallback policy, or runtime
  helper policy.
- The implementation must be architecture-level: no jar-, class-, method-, log-,
  benchmark-, or test-specific compile split.
- Each completed subtask requires a fresh validation run and a commit containing
  only that subtask's implementation plus this todo update.
