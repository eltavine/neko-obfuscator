# NekoObfuscator Whitepaper

[Chinese version](./zh-CN/WHITEPAPER.md)

## Threat Model

NekoObfuscator raises static analysis cost. It does not provide a cryptographic security boundary.

| Attacker | Expected resistance |
|---|---|
| Casual decompiler use | Strong friction when CFF is enabled. |
| Manual bytecode review | Significant control-flow and call-key noise. |
| Dedicated deobfuscator | Partial resistance; formulas and dispatch shape can be modeled with enough effort. |
| Dynamic instrumentation | Low resistance unless combined with native mode and external hardening. |
| Native debugger / VM expert | Low resistance; HotSpot patching is inspectable with native tooling. |

## Current JVM Obfuscation Model

The current JVM surface is intentionally narrow:

1. `keyDispatch` creates method-local key material and hidden call-chain key propagation.
2. `controlFlowFlattening` consumes that key material and rewrites method control flow into keyed island dispatchers.

This replaces older documentation that described unregistered string/number/indy passes as active features.

## Key Dispatch

`JvmKeyDispatchPass` establishes a hidden long key per eligible method.

### Eligibility

The pass skips runtime classes, generated helper methods, abstract/native methods, stack-introspection shapes, and reflection-sensitive classes. Constructor, class initializer, and Java `main` signatures are not widened.

### Signature widening

For an eligible non-boundary method:

```text
original:  owner.name(args)ret
rewritten: owner.name(args, long hiddenKey)ret
```

The hidden key slot is inserted at the end of the original parameter area. Local variable indexes at or after the slot are shifted by two slots. Frames and debug locals are cleared so ASM can recompute the method shape.

Every application call to a widened method is rewritten:

```text
load callerKey
invoke owner.name(args, long)ret
```

Inherited application calls are resolved through the class hierarchy when possible.

### Boundary key initialization

Boundary methods keep their descriptor and seed a local key:

```text
key = (seed ^ mask) ^ mask
```

Incoming-key methods derive their local key from the hidden argument:

```text
key = incomingKey ^ seed
key = key + 0x9E3779B97F4A7C15
key = key ^ (key >>> 31)
```

The method seed is deterministic for a build:

```text
h = masterSeed ^ 0x9E3779B97F4A7C15
h = mix(h, hash(className))
h = mix(h, hash(methodName))
h = mix(h, hash(methodDescriptor))
h = mix(h, instructionCount)
h = mix(h, maxLocals)
seed = h != 0 ? h : 0x5DEECE66D
```

The shared 64-bit mix function is:

```text
mix(state, value):
  z = state + value + 0x9E3779B97F4A7C15
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)
```

## Control-Flow Flattening

`ControlFlowFlatteningPass` performs direct keyed CFF over the original method body.

### Method shape preservation

- Constructors are flattened only after the mandatory `this(...)` or `super(...)` call.
- Runtime classes, generated methods, abstract/native methods, stack-introspection methods, and reflection-sensitive class shapes are skipped.
- Verifier compatibility is preserved by grouping dispatcher targets by local-frame signature.

### Locals

CFF allocates:

| Local | Meaning |
|---|---|
| `keyLocal` | Long key from `keyDispatch`. |
| `pcLocal` | Encoded target state. |
| `guardLocal` | Method guard derived from `keyLocal`. |
| `pathKeyLocal` | Evolving edge/path key. |
| `blockKeyLocal` | Evolving block key. |
| `domainLocal` | Encoded island selector. |
| `exceptionLocal` | Temporary exception object when handler bridges exist. |

### State assignment

For a method:

```text
salt = mix(masterSeed, hash(className + "." + methodName + methodDesc))
states = uniqueStates((int)(salt >>> 32), blockCount)
```

Each block label gets one unique integer state. Alias labels map to their canonical block's state.

### Dispatcher grouping

Blocks are grouped by verifier frame signature:

```text
frameSignature(label) =
  concat(local[0].descriptorOrMarker, ';', local[1].descriptorOrMarker, ';', ...)
```

Each group has:

- one hub;
- 1 to 4 island labels;
- 1 to 3 alias hubs;
- a per-group salt;
- per-target `selectorSeed` and `domainSeed`.

Island count:

```text
islandCount = 1                         if blockCount <= 1
islandCount = min(4, max(2, (n + 3)/4)) otherwise
islandFor(i, n, islandCount) = (i * islandCount) / max(1, n)
aliasHubCount = 1                  if n <= 2
aliasHubCount = min(3, 1 + n / 6)  otherwise
```

### Key initialization

The initial key triad is derived from the method key:

```text
guard = fold32(keyLocal)                         variant selected by seed bits
pathKey = ((int)keyLocal ^ (int)seed)
pathKey = pathKey ^ (pathKey >>> shift(seed, 5))
blockKey = fold16(((int)(keyLocal >>> 32) ^ guard ^ (int)(seed >>> 32)))
```

`fold16(x)` means:

```text
x = x ^ (x >>> 16)
```

### Encoded state and domain values

Both state and domain use the same keyed value encoder with different seed domains:

```text
encodedState(state)  = encodedKeyedValue(state,  selectorSeed ^ 0x53544154454B5631)
encodedDomain(idx)   = encodedKeyedValue(idx,    domainSeed   ^ 0x444F4D41494B5631)
```

`encodedKeyedValue(value, seed)` chooses one of four arithmetic forms from `(seed >>> 41) & 3`:

```text
case 0:
  ((value + low(seed) + guard) ^ pathKey) ^
  (blockKey + high(seed))

case 1:
  (((blockKey + low(seed)) ^ (value ^ high(seed))) + guard) ^
  (pathKey ^ low(mix(seed, 0x50415448)))

case 2:
  tmp = pathKey + value + high(seed)
  tmp = tmp ^ (tmp >>> shift(seed, 7))
  (tmp ^ guard) + (blockKey + low(mix(seed, 0x424C4F43)))

case 3:
  ((((value ^ low(mix(seed, 0x56414C5545))) + guard) ^
    (pathKey + high(seed))) + blockKey) ^ low(seed)
```

All arithmetic is JVM `int` arithmetic.

### Edge key evolution

Each transition calculates:

```text
stepSeed = mix(edgeSeed ^ selectorSeed, state)
stepSeed = mix(stepSeed ^ domainSeed, island ^ roleOrdinal)
if stepSeed == 0:
  stepSeed = edgeSeed ^ 0x4346465354455031
```

When the edge updates keys, it mutates one or two of `guard`, `pathKey`, `blockKey`, and sometimes the long method key. The exact tiny operation is selected by seed bits:

```text
dst = dst + (source ^ c)
dst = (dst ^ c) + source
dst = (dst + source) ^ c
dst = (dst ^ source) + c
```

Method-key mutation variants include:

```text
key = (key + (source & 0xffffffffL)) ^ c
key = (key ^ c) + source
key = (key ^ ((long)source << 32)) + c
key = (key + c) ^ source
```

### Edge kinds

For each transition, CFF chooses one path:

| Edge kind | Behavior |
|---|---|
| `DIRECT_ISLAND` | Store encoded `pc`, then jump directly to an island dispatcher. |
| `ALIAS_HUB` | Store encoded `pc` and encoded domain, then jump to an alias hub. |
| `HUB` | Store encoded `pc` and encoded domain, then jump to the group hub. |

Handlers prefer hub or alias-hub routing. Normal edges choose from seed bits, with direct-island edges mixed in when possible.

### Poison path

Dispatchers compare encoded values. No matching state or domain goes to a poison path rather than original bytecode.

## Native Obfuscation Model

The native rewrite is not a JNI fallback scheme. The Java method body is replaced by a large `LinkageError` stub, and HotSpot Method entry points are patched to generated trampolines.

### Selection and closure

Native selection uses annotation and glob configuration. After initial selection, the stage adds application callees to the candidate set. It then repeats safety checking until the selected method set is stable.

Unsupported methods are rejected. With `skipOnError: false`, a rejection or translation failure aborts the build.

### Lowering before translation

The stage lowers supported invokedynamic forms before native translation:

- Lambda metafactory into generated lambda classes.
- Record/object methods bootstrap into explicit bytecode.

The safety checker accepts additional invokedynamic bootstrap families only when the translator has a matching lowering or direct handling path.

### C generation

The translator emits:

- one raw implementation function per translated method;
- signature dispatchers shared by equivalent call shapes;
- native-to-Java direct dispatch helpers;
- inline caches for virtual dispatch;
- exception dispatch to translated try handlers;
- object-local root preparation when translated locals may hold oops;
- shadow stack push/pop for translated frames;
- tail-recursion landing pads.

### HotSpot patching

`JNI_OnLoad` is deliberately small but performs mandatory native initialization:

1. Capture current JavaThread register.
2. Call `GetEnv`.
3. Store the JNI function table anchor.
4. Initialize HotSpot layout and VMStruct metadata.
5. Initialize GC and object metadata helpers.
6. Discover manifest methods and patch Method entry points.

Generated trampolines enter from patched Java ABI entry points while the JavaThread remains in `_thread_in_java`. Missing required metadata, barriers, CodeHeap support, or call-stub support must abort instead of falling back to Java bytecode.

### Rewritten Java bodies

Translated Java methods are rewritten to large non-inlinable stubs that throw:

```text
LinkageError("please check your native library load correctly")
```

If such a stub executes, native loading or Method entry patching failed.

## Limits

- The implementation is HotSpot-specific.
- VMStruct and symbol availability vary by JDK build.
- ZGC and Shenandoah require correct barrier metadata; skipping translation is not compatibility.
- Native artifacts must be regenerated and runtime-tested after every implementation change.
- Obfuscation key formulas are public to anyone who has the JAR and enough time.
