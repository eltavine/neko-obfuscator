# JVM InvokeDynamic Generated-Helper Renamer Repair

## Context

`phase-shift21.jar` fails after full JVM obfuscation when the generated
invokedynamic resolver tries to bind a validation-sink primitive helper by an
old generated name:

```text
java.lang.NoSuchMethodException: no such method:
a.b.__neko_vsx0$18(int,int,long)int/invokeStatic
```

The original input jar accepts the same key and prints the expected flag:

```text
accepted: K5J9-R2QX-7M4D-8V2P-C6ZA-H3TN
flag{java21_non_linear_keyspace_collapses_to_one_key}
```

## Evidence

- Fresh runtime of `/mnt/d/Code/Reverse/NekoOBF/phase-shift21-obf.jar` with
  `K5J9-R2QX-7M4D-8V2P-C6ZA-H3TN` exits 1 with
  `NoSuchMethodException` for
  `a.b.__neko_vsx0$18(IIJ)I`.
- The mapping file records that generated helper as renamed:
  `METHOD a/b.__neko_vsx0$18(IIJ)I -> qa`.
- Fresh `javap -p -s` over the generated output shows the actual `(IIJ)I`
  helper exists as `a.l.qa(IIJ)I`, not `a.b.__neko_vsx0$18(IIJ)I`.
- Source evidence:
  `JvmInvokeDynamicObfuscationPass.payload(SiteSpec)` encodes
  `kind`, `owner`, `name`, and `desc` into an encrypted bootstrap payload.
  `siteSpec(...)` deliberately allows generated validation primitive entry
  calls so the call to `__neko_vsx...` can be converted to invokedynamic.
  Later, `ObfuscationPipeline.obfuscateGeneratedHelperApi(...)` renames
  generated helper methods, and `CffClassSetup.relocateLargeCffHelperSets(...)`
  may relocate renamed generated helper methods to synthetic host classes.
  That relocation rewrites only `MethodInsnNode` callsites, while the encrypted
  invokedynamic payload still contains the pre-rename/pre-relocation
  owner/name/descriptor.
- Therefore the failing invariant is:
  every generated invokedynamic payload that targets a generated helper must
  resolve to the helper's final owner/name/descriptor after generated-helper
  renaming and relocation.

## Tasks

### [x] JIR-1: Baseline And Evidence

- Scope: reproduce the failure and identify the exact stale runtime target.
- Required evidence: runtime stack trace, original jar success output, mapping
  line, generated class method location, and source paths proving the stale
  payload path.
- Validation target: direct `java -jar` runs with repo-local
  `java.io.tmpdir` and `-XX:-UsePerfData`, plus `javap`/mapping inspection.
- Completion criteria: the root invariant is concrete and does not rely on a
  sample-specific guess.

### [x] JIR-2: Plan Intake Review

- Scope: have an independent subagent review this plan before implementation.
- Required evidence: reviewer PASS or concrete revision requests.
- Validation target: plan-intake review over this file and the cited source
  paths.
- Completion criteria: review confirms the repair is generic, preserves JVM
  ABI, does not reduce renamer/helper obfuscation, and has adequate validation.
- First review result: FAIL. Required revisions were: split implementation
  tasks so each high-risk subtask has its own review/commit; make JIR-3/JIR-4
  validation targets concrete; require a composable final remap across
  generated-helper rename and relocation; add a generic fixture/assertion
  beyond `phase-shift21`; add final plan-completion review.
- Second review result: PASS. The reviewer confirmed the revised plan has
  concrete subtask validation/commit boundaries, a composable final target map,
  generic fixture requirements, and a final plan-completion review.

### [x] JIR-3: Composable Generated-Helper Final Target Map

- Scope: add a generic metadata path that records final generated-helper
  owner/name/descriptor changes made after invokedynamic payload creation.
  The map must be composable: an original target key recorded in an
  invokedynamic payload must resolve through generated-helper API renaming and
  any later CFF helper relocation to the final owner/name/descriptor. For
  example, `oldOwner.__neko_helper(desc) -> oldOwner.renamed(desc) ->
  host.renamed(desc)` must be queryable from the original payload target.
- Required evidence: source diff showing a final member remap is recorded for
  generated helper renames and relocations, keyed by old owner/name/descriptor
  and pointing to the final owner/name/descriptor.
- Validation target:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/t bash ./gradlew :neko-transforms:compileJava :neko-test:test --tests dev.nekoobfuscator.test.GeneratedHelperTargetMapTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --no-daemon`.
  The new/updated test must build a generic generated-helper remap chain
  using synthetic owner/name/descriptor values and assert lookup from the
  original target returns the final target after both rename and relocation.
- Completion criteria: no generated helper is excluded from renaming or
  relocation as a workaround; no plaintext target strings are introduced into
  generated bytecode; direct bytecode calls and delayed invokedynamic metadata
  can both observe the same final target map. After this subtask passes its
  validation target, dispatch implementation review and commit only this
  implementation plus the matching plan update before starting JIR-4.
- Completion evidence:
  - Added `GeneratedHelperTargetMap`, a pipeline pass-data table that composes
    generated-helper method remaps from an original target to the final
    owner/name/descriptor.
  - Generated-helper API renaming now records
    `old owner/name/desc -> same owner/renamed name/desc`, and CFF helper
    relocation now records `current owner/name/desc -> host owner/name/desc`.
    The table updates earlier entries so original payload targets resolve to
    the final relocated target.
  - Added `GeneratedHelperTargetMapTest`, a generic synthetic
    owner/name/descriptor regression that proves an original generated-helper
    target resolves through rename plus relocation without preserving the old
    helper name.
  - Focused validation passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/t bash ./gradlew :neko-transforms:compileJava :neko-test:test --tests dev.nekoobfuscator.test.GeneratedHelperTargetMapTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --no-daemon`
    with `BUILD SUCCESSFUL`.

### [x] JIR-4: InvokeDynamic Payload Reconciliation

- Scope: before writing output, rewrite encrypted invokedynamic payloads whose
  decoded target matches a recorded generated-helper remap, then re-encrypt
  the payload with the original site seed, token, live flow word, and resolver
  descriptor.
- Required evidence: implementation keeps payload encrypted, preserves existing
  bootstrap descriptor/hidden-key flow, and updates only targets proven by the
  final generated-helper remap.
- Validation target:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/t bash ./gradlew :neko-cli:installDist :neko-transforms:compileJava :neko-test:test --tests dev.nekoobfuscator.transforms.jvm.invoke.JvmInvokeDynamicPayloadReconciliationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --no-daemon`.
  The generic regression must exercise an invokedynamic payload targeting a
  generated helper whose final target changes through the composed remap, then
  assert decoded/reconciled payload resolution uses the final
  owner/name/descriptor. `phase-shift21` remains a runtime regression target,
  not the only proof.
- Completion criteria: no fallback, no original-call rescue, no helper rename
  downgrade, no descriptor preservation workaround, and no sample-specific
  class/method/key checks. After this subtask passes its validation target,
  dispatch implementation review and commit only this implementation plus the
  matching plan update before starting JIR-5.
- Completion evidence:
  - `JvmInvokeDynamicObfuscationPass` now records each generated indy site's
    payload target and encryption context when the site is created.
  - `CffClassSetup.finalizeClassCodeIntegrity` now invokes payload
    reconciliation after generated-helper relocation/name repair and before
    class-code integrity material is finalized, so rewritten payload bytes are
    included in the final integrity hash.
  - Reconciliation resolves the recorded original target through
    `GeneratedHelperTargetMap` and re-encrypts only payloads whose target has a
    recorded final owner/name/descriptor. It does not preserve old helper names,
    skip renaming/relocation, or add runtime fallback behavior.
  - Added `JvmInvokeDynamicPayloadReconciliationTest`, a generic synthetic
    regression proving an encrypted payload for an original generated-helper
    target is rewritten to the composed relocated target while staying
    encrypted.
  - Focused validation passed:
    `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/t bash ./gradlew :neko-cli:installDist :neko-transforms:compileJava :neko-test:test --tests dev.nekoobfuscator.transforms.jvm.invoke.JvmInvokeDynamicPayloadReconciliationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --no-daemon`
    with `BUILD SUCCESSFUL`.

### [ ] JIR-5: Full Runtime Validation And Output Refresh

- Scope: rebuild the CLI, regenerate `test-jars/phase-shift21.jar` with the
  full JVM profile and no quick mode, write
  `/mnt/d/Code/Reverse/NekoOBF/phase-shift21-obf.jar`, and run the reported
  key through the obfuscated output.
- Required evidence: full transform coverage log, `jar tf` readability, direct
  runtime success output, and static inspection showing the `(IIJ)I` validation
  helper target is no longer requested under the stale owner/name.
- Validation target:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/t bash ./gradlew :neko-cli:installDist :neko-transforms:compileJava :neko-test:test --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --no-daemon`
  plus fresh CLI obfuscation and runtime of `phase-shift21-obf.jar`.
- Completion criteria: regenerated output runs successfully for
  `K5J9-R2QX-7M4D-8V2P-C6ZA-H3TN`; existing full JVM obfuscation surfaces stay
  enabled; generated helper renaming remains active; no stale generated helper
  payload target remains.
  After this validation passes, dispatch implementation review for the output
  refresh evidence and commit only the matching plan update if no source code
  changes remain in this subtask.

### [ ] JIR-6: Final Plan Completion Review

- Scope: have a separate subagent review the whole plan after JIR-3, JIR-4,
  and JIR-5 have been individually reviewed, validated, and committed.
- Required evidence: reviewer PASS over task completion, evidence freshness,
  validation coverage, and atomic commit discipline.
- Validation target: final plan review over this file, `git log`, current
  source diff, and fresh validation/runtime output.
- Completion criteria: final reviewer confirms no stale work is marked
  complete, no unreviewed high-risk implementation remains uncommitted, and
  the refreshed output jar remains present at the requested path.
