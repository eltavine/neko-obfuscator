# JVM obfuscation performance hotspot analysis — 2026-05-16

## Context

User requested analysis of current JVM obfuscation performance hotspots, especially per-CFF-basic-block key updates that generate large amounts of computation/outlining, and the performance impact of the AtomicLong key table.

## Subtasks

- [x] Map performance paths
  - Scope: locate JVM CFF key-update generation and AtomicLong key-table usage paths.
  - Required evidence: exact source files/classes/methods and generated-code shape responsible for per-block key updates and key-table access.
  - Validation command or runtime target: static source inspection only; do not run repository `./gradlew` without user permission.
  - Completion criteria: concrete source/runtime paths and invariants identified without implementation changes.
  - Evidence found: transition insertion in `CffDispatchEmitter.transition` and `emitTransitionCore`; edge rewrites for goto/conditional/switch/fallthrough call it; simple per-edge step update is `CffKeyStateEmitter.emitStepKeys`; large-method materialized step helper is `emitMaterializedStepKeys` -> `CffMaterialTables.installStepMaterialHelper`; encrypted PC/domain token path uses `emitEncryptedToken` -> token material helper; class key material and AtomicLong object cells are installed in `CffMaterialTables.installClassKeyTableInit`; additional AtomicLong global/class helper code is in `CffClassSetup` and island material object mask code is in `CffIslandMaterial.emitTokenMaterialObjectMask`.

- [ ] Assess hotspot costs
  - Scope: analyze cost drivers in per-CFF-block key updates and AtomicLong key table access.
  - Required evidence: call graph/source snippets/logical operation counts from source and the user-provided generated/decompiled example.
  - Validation command or runtime target: static analysis; optional dynamic benchmark only after user grants permission to use repository `./gradlew`.
  - Completion criteria: generic architecture-level recommendations preserving live key propagation and obfuscation strength.
