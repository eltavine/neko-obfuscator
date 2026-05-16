# Native maintainability refactor todo

## Scope

Improve native obfuscation code maintainability without changing generated native semantics, weakening obfuscation, adding fallbacks, or changing JVM/native ABI behavior. Current user acceptance target: every Java source under `neko-native/src/main/java` must remain below 2000 lines and native code must be grouped by function.

## Evidence gathered

- `CCodeGenerator` was 6821 lines and mixed slot registries, bind/class resolution support, runtime support, HotSpot fast access helpers, object/array helpers, IC helpers, and generated C string escaping.
- `MethodPatcherEmitter` was 2871 lines and mixed VMStruct layout declarations, VMStruct walking, thunk allocation/patching, and bootstrap layout initialization.
- Duplicate C string literal escapers existed with divergent behavior:
  - `CCodeGenerator.c(String)` escaped backslash, quote, newline, carriage return, tab, backspace, and formfeed.
  - `NativeTranslator.c(String)` escaped backslash, quote, newline, carriage return, and tab.
  - `OpcodeTranslator.cStringLiteral(String)` escaped backslash, quote, newline, carriage return, and tab.
  - `ManifestEmitter.escape(String)` escaped only backslash and quote.
- Fresh line-count check after extraction found no native Java source above 2000 lines. Largest native files observed: `OpcodeTranslator.java` 1542 lines and `NativeBindSupportEmitter.java` 1518 lines.

## Subtasks

### [x] S1: Centralize generated-C string literal escaping

- Scope: replace duplicated Java-side helper methods that emit the inside of C string literals.
- Required evidence: exact duplicate helper definitions and all affected callsites were identified before editing.
- Runtime target row: compile/test path that exercises generated C source construction through `CCodeGeneratorTest` after using the repository Gradle wrapper with permission.
- Completion criteria:
  - One shared native code-generation utility owns C string literal escaping.
  - Existing callsites in `CCodeGenerator`, `NativeTranslator`, `OpcodeTranslator`, and `ManifestEmitter` use the shared utility.
  - No fallback, JNI, ABI, transform coverage, or generated control-flow behavior was changed.
  - New test coverage covers newline, carriage return, tab, backspace, formfeed, backslash, and double quote escaping.
  - Targeted test passed on freshly compiled code.

### [x] S2: Split oversized native emitters by function

- Scope: reduce all native Java files below 2000 lines while preserving generated C/native output order.
- Required evidence: line counts identified `CCodeGenerator.java` and `MethodPatcherEmitter.java` as oversized; method boundaries were extracted by existing functional sections.
- Runtime target row: compile `:neko-native:compileJava :neko-test:compileTestJava`, then run `:neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest`.
- Completion criteria:
  - `CCodeGenerator` remains the composer/registry owner and delegates large generated-C regions to function-specific emitters.
  - Method patching is split into layout, VMStruct walking, thunk patching, and bootstrap emitters.
  - Every native Java file is below 2000 lines.
  - Targeted compile and generator tests pass.
