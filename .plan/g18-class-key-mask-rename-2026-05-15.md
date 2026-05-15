# G18 class-key mask emitter rename — 2026-05-15

## Scope

Rename the bytecode emitter currently named `emitG18ClassRootWord` in `CffMaterialTables` to a name that describes what it emits: the runtime class-key word mask derived from the g18 class root.

## Evidence

- `emitG18ClassRootWord(...)` is private to `CffMaterialTables`.
- LSP references found only the declaration and one callsite inside `installClassKeyTableInit(...)`.
- The method does not emit a root; it emits the per-index int mask used to decode `CLASS_KEY_WORDS_SLOT` entries.

## Subtasks

### [x] 1. Record rename scope and evidence

- Scope: `CffMaterialTables.emitG18ClassRootWord(...)` and its callsite.
- Required evidence: LSP references prove the rename is local and complete.
- Validation target: LSP references and compile.
- Completion criteria: plan records why the old name is wrong and the new name is semantically tied to class-key mask decoding.

### [x] 2. Rename helper and callsite

- Scope: declaration and callsite in `CffMaterialTables`.
- Required evidence: no remaining `emitG18ClassRootWord` source references.
- Evidence: LSP rename updated the declaration and sole callsite; source search for `emitG18ClassRootWord` returned no matches.
- Validation target: source search.
- Completion criteria: helper is named `emitG18ClassKeyWordMask`.

### [x] 3. Validate compile and commit

- Scope: renamed Java source and this plan.
- Required evidence: `./gradlew :neko-transforms:compileJava` passes.
- Evidence: `./gradlew :neko-transforms:compileJava` passed after the rename.
- Validation target: compile.
- Completion criteria: scoped changes are committed.
