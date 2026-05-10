# NekoObfuscator Architecture

[Chinese version](./zh-CN/ARCHITECTURE.md)

## Scope

This document describes the current implementation, not the older planned architecture. The current CLI registers the rebuilt JVM passes in `neko-transforms/jvm` and optionally runs the native compilation stage from `neko-native`.

## Module Topology

```text
neko-cli
  -> neko-config
  -> neko-core
       -> neko-transforms
       -> neko-native     (loaded reflectively when native.enabled=true)
       -> neko-runtime    (classes injected into output JARs)
```

| Module | Role |
|---|---|
| `neko-api` | Configuration model, annotations, transform contracts. |
| `neko-config` | YAML parsing and validation. |
| `neko-core` | JAR input/output, class hierarchy, pass scheduling, cleanup, runtime injection, mapping output. |
| `neko-transforms` | Current JVM obfuscation passes: `keyDispatch` and `controlFlowFlattening`. |
| `neko-native` | Method selection, safety checking, invokedynamic lowering, JVM-to-C translation, C emission, native build, HotSpot method patching. |
| `neko-runtime` | Minimal runtime classes injected into output JARs. Currently `NekoNativeLoader`. |
| `neko-test` | JUnit integration and native tests. |

## IR Layers

| Layer | Current use |
|---|---|
| L1 | ASM `ClassNode` / `MethodNode` wrappers. All current JVM passes mutate L1 directly. |
| L2 | CFG/SSA infrastructure retained in `neko-core`; not the main path for the rebuilt CFF pass. |
| L3 | C-level model used by native translation and C generation. |

The rebuilt JVM CFF no longer depends on a generic L2 lowering path. It analyzes verifier frames directly from ASM, splits safe block leaders, and rewrites the original method body in place.

## Main Pipeline

`ObfuscationPipeline.execute(inputJar, outputJar)` performs:

1. Read classes and resources from the input JAR.
2. Build the class hierarchy and optional classpath hierarchy.
3. Create `PipelineContext`, including the build master seed.
4. Filter registered passes by `transforms.<id>.enabled`.
5. Schedule passes by phase and dependency.
6. Run enabled passes over L1 classes and methods.
7. Clean dirty bytecode: strip frames, remove unreachable code, sanitize try/catch ranges, recompute locals.
8. Harden generated helper methods when relevant.
9. Inject runtime classes needed by enabled features.
10. Run optional native compilation.
11. Write the output JAR and optional mapping file.

## JVM Passes

### `keyDispatch`

`JvmKeyDispatchPass` runs before CFF. It prepares hidden method key material:

- Eligible non-constructor, non-`main`, non-reflection-sensitive application methods have their descriptor rewritten from `(...args)R` to `(...args, long)R`.
- Call sites to rewritten application methods push the caller key before invocation.
- Boundary methods keep their JVM-mandated signature and seed a local key directly.
- Constructors and shape-sensitive methods are left with valid JVM signatures.

The pass publishes the local key slot under `controlFlowFlattening.flowKeyLocalByMethod`, so CFF and call-chain key dispatch share the same local.

### `controlFlowFlattening`

`ControlFlowFlatteningPass` depends on `keyDispatch` and rewrites each eligible method in place:

- Constructor code before the mandatory `this(...)` or `super(...)` call is preserved.
- Basic blocks are built only at labels with compatible verifier state.
- Dispatch groups are split by frame signature so a dispatcher never merges incompatible local states.
- Each group is split into islands with optional alias hubs.
- The active program counter (`pcLocal`) and island domain (`domainLocal`) are encoded using `guardLocal`, `pathKeyLocal`, and `blockKeyLocal`.
- Exception handlers receive bridges into the same keyed dispatcher model.

## Native Stage

When `native.enabled: true`, `ObfuscationPipeline` reflectively loads `NativeCompilationStage`.

The native stage:

1. Parses all classes.
2. Lowers supported `invokedynamic` forms before selection:
   - `LambdaMetafactory.metafactory`
   - `ObjectMethods.bootstrap`
3. Selects methods by annotation and glob patterns.
4. Closes over application callees so translated methods do not call untranslated application methods through an unsupported edge.
5. Iteratively rejects unsafe methods until the native manifest is closed and safe.
6. Translates selected bytecode to C functions.
7. Emits C source, headers, manifest, signature dispatchers, trampolines, and HotSpot patching support.
8. Builds platform libraries with Zig.
9. Rewrites translated Java methods into large `LinkageError` stubs that are not intended to execute.
10. Injects `NekoNativeLoader.load()` into owner class `<clinit>`.
11. Packages native libraries under `/neko/native/`.

The actual native entry path is HotSpot method-entry patching, not Java `native` stubs. `JNI_OnLoad` captures the current `JavaThread`, initializes HotSpot metadata from VMStructs and symbols, discovers manifest entries, and patches Method entry points to generated trampolines.

## Runtime Injection

The current runtime surface is intentionally small. `NekoNativeLoader` is injected only when native mode is enabled. It extracts the platform library to a temporary file and calls `System.load`.

No Java-side string decryptor, bootstrap hub, or key-derivation runtime is currently injected by the CLI path described here.

## Failure Model

For strict native work, configure `native.skipOnError: false`. With `skipOnError: true`, the current code can return the original class/resource set if translation or compilation fails; that is useful for development but is not acceptable validation for native coverage.

Generated native code should fail closed when mandatory HotSpot metadata, GC barriers, CodeHeap support, or call-stub support is missing.
