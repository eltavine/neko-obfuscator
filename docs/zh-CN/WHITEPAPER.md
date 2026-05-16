# NekoObfuscator 白皮书

[English](../WHITEPAPER.md)

## 威胁模型

NekoObfuscator 提高静态分析成本，但不提供密码学安全边界。

| 攻击者 | 预期抗性 |
|---|---|
| 普通反编译器用户 | CFF 开启时有强摩擦。 |
| 人工 bytecode 阅读 | 控制流和调用 key 噪声显著。 |
| 专用反混淆器 | 部分抗性；公式和 dispatch 形态可以被建模。 |
| 动态插桩 | 抗性低，除非配合 native 模式和额外加固。 |
| Native 调试器 / VM 专家 | 抗性低；HotSpot patch 可被 native 工具检查。 |

## 当前 JVM 混淆模型

当前 JVM surface 是链式 pass pipeline：

1. `renamer` 在后续 pass 写入 metadata 之前移除稳定的类/成员身份。
2. `keyDispatch` 创建方法本地 key material 和隐藏调用链 key 传播。
3. `methodParameterObfuscation` 将可处理方法参数打包成单个 `Object[]` carrier。
4. `controlFlowFlattening` 消费 key material，将方法控制流重写为 keyed island dispatcher。
5. `constantObfuscation` 只在 CFF live state metadata 可用时重写数值常量。
6. `stringObfuscation` 使用 AES/DES + XOR 加密字符串字面量，并从 CFF live state 派生运行时 key。

JVM string、constant 和 CFF pass 不注入 runtime/helper class。解码逻辑直接写入转换后的方法和类初始化器。

## Renamer

`JvmRenamerPass` 在 key dispatch 和 CFF 之前运行。

应用类名从 `packagePrefix` 下的短 internal-name 序列分配：

```text
default: a/a, a/b, a/c, ...
```

字段和方法使用短名。方法重命名组保持应用继承/interface 兼容。覆盖外部库方法的方法、构造器、类初始化器、native 方法和 Java 静态入口 `main([Ljava/lang/String;)V` 会保留。

Renamer 会重写可推断 owner 的直接反射面：

- class-name string 和 dotted class-name string；
- `*.class` 资源字符串；
- package prefix 命中已重命名包的资源路径；
- 直接 `Class.getMethod`、`Class.getDeclaredMethod`、`Class.getField` 和 `Class.getDeclaredField` 名称字面量。

Pipeline 后续还会执行 generated-helper API renaming。它会把 CFF class key table 和 string cache 等 synthetic helper 字段从 `$...` 或 `__...` 名称改成同一套短名风格，并全局重写 generated reflection-filter literal。这样 generated 名称不会成为另一套可识别的命名模式。

## Key Dispatch

`JvmKeyDispatchPass` 为可处理方法建立隐藏 long key。

### 适用条件

Descriptor widening 只用于可以安全承载隐藏 JVM `long` 且不破坏 ABI surface 的应用方法：

- runtime class 和 generated helper 方法会被忽略；
- annotation class、native 方法、构造器、类初始化器、Java `main`、外部 override slot、LambdaMetafactory SAM slot 保持原描述符；
- abstract/interface 应用方法仍可被 descriptor-widened，使应用内 implementor 和 call site 的 ABI 一致，但它们没有方法体本地 key prologue。

### 签名拓宽

对可处理方法：

```text
original:  owner.name(args)ret
rewritten: owner.name(args[0:keyIndex], long hiddenKey, args[keyIndex:])ret
```

`keyIndex` 通常是原参数数量。LambdaMetafactory implementation target 使用为该 bootstrap target 记录的 captured-argument-aware index，以保留 unbound receiver 形态。插入 slot 之后的 local variable index 后移两个 slot。frame 和 debug locals 会清空，让 ASM 重算方法形态。

调用被拓宽应用方法的 call site 会 spill receiver 和原始参数，然后围绕 `keyIndex` 重新加载隐藏 key 并调用 keyed descriptor：

```text
reload args before keyIndex
load callerKey
reload args after keyIndex
invoke owner.name(args..., long, args...)ret
```

可行时会通过类层级解析继承应用调用。该 pass 不拓宽 direct constructor call。

### 方法 key 初始化

本地方法 key 始终来自同一个三步 incoming-key mixer：

```text
A = mix(seed ^ 0x474B4D49584131, rotl(mask, 17))
B = mix(seed + 0x474B4D41444431, mask ^ 0x4B4D4144444D534B)
B = B != 0 ? B : 0x4B4D4144444D534B
C = mix(seed ^ rotl(mask, 41), 0x474B4D49584231)

mixed(raw, seed, mask) = ((raw ^ A) + B) ^ C
rawFor(target, seed, mask) = ((target ^ C) - B) ^ A
```

边界方法保持描述符，并在 mixer 前压入 `rawFor(seed, seed, mask)`，因此初始化后的本地 key 是 canonical method seed。接收 incoming key 的方法使用 `mask = 0x4E4B4F4A564D4B31` 原地混合隐藏 long 参数。后续 CFF key-transfer 重写可以把静态 raw 常量替换为动态解码 material，但 callee 侧 mixer 不变。

方法 seed 在同一次构建内确定。非 static、非 private 的 virtual family 使用选定的应用 family root，使 override 参与者一致：

```text
normal:
  h = masterSeed ^ 0x9E3779B97F4A7C15
  h = mix(h, hash(className))
  h = mix(h, hash(methodName))
  h = mix(h, hash(methodDescriptor))
  h = mix(h, instructionCount)
  h = mix(h, maxLocals)

virtual family:
  h = masterSeed ^ 0x9E3779B97F4A7C15
  h = mix(h, hash(familyRootOwner))
  h = mix(h, hash(methodName))
  h = mix(h, hash(methodDescriptor))
  h = mix(h, 0x5649525453454544)

seed = h != 0 ? h : 0x5DEECE66D
```

共享 64-bit mix 函数：

```text
mix(state, value):
  z = state + value + 0x9E3779B97F4A7C15
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)
```
## Method Parameter Obfuscation

`JvmMethodParameterObfuscationPass` 在 key dispatch 后运行。它把可处理描述符替换成单个 object-array carrier：

```text
key dispatch 后原始形态: owner.m(A, B, long hiddenKey)R
packed:                 owner.m(Object[])R
```

调用方分配 `Object[]`，box primitive 参数，直接存入 reference 参数，然后调用 packed 方法。被调方 prologue 解包数组：

```text
arg0 = (A) carrier[0]
arg1 = (B) carrier[1]
hiddenKey = ((Long) carrier[n]).longValue()
```

该 pass 会迁移 key-dispatch metadata，使 CFF 仍能看到正确的 `keyLocal`。它排除 JVM 形态敏感方法、外部 override、annotation、generated 方法和 invokedynamic handle target。如果多个 overload 会折叠到同一个 packed descriptor，则先分配确定性的 `$nkop$<hash>` 后缀，之后仍可被 renamer 改名。

## Control-Flow Flattening

`ControlFlowFlatteningPass` 在原方法体上执行 direct keyed CFF。

### 方法形态保持

- 构造器只在强制 `this(...)` 或 `super(...)` 调用之后平坦化。
- 跳过 runtime class、生成方法、abstract 方法、native 方法和 generated class-key-table `<clinit>` 方法体。
- 按 local-frame signature 分组 dispatcher target，保持 verifier 兼容。

### Locals

CFF 分配：

| Local | 含义 |
|---|---|
| `keyLocal` | 来自 `keyDispatch` 的 long key。 |
| `pcLocal` | 目标 block 的加密 `pcToken`。 |
| `guardLocal` | 从 `keyLocal` 派生的方法 guard。 |
| `pathKeyLocal` | 演化中的 edge/path key。 |
| `blockKeyLocal` | 演化中的 block key。 |
| `domainLocal` | 加密后的 island-domain token。 |
| `exceptionLocal` | 存在 handler bridge 时的临时异常对象。 |

### State 分配

对一个方法：

```text
salt = mix(masterSeed, hash(className + "." + methodName + methodDesc))
states = uniqueStates((int)(salt >>> 32), blockCount)
```

每个 block label 获得一个唯一 int state。Alias label 映射到 canonical block 的 state。

### Dispatcher 分组

Block 按 verifier frame signature 分组：

```text
frameSignature(label) =
  concat(local[0].descriptorOrMarker, ';', local[1].descriptorOrMarker, ';', ...)
```

每个 group 包含：

- 一个 hub；
- 1 到 4 个 island label；
- 1 到 3 个 alias hub；
- group salt；
- per-target `selectorSeed` 和 `domainSeed`。

Island 数量：

```text
islandCount = 1                         if blockCount <= 1
islandCount = min(4, max(2, (n + 3)/4)) otherwise
islandFor(i, n, islandCount) = (i * islandCount) / max(1, n)
aliasHubCount = 1                  if n <= 2
aliasHubCount = min(3, 1 + n / 6)  otherwise
```

### Key 初始化

CFF 从 `keyDispatch` 的 long local 开始，派生活跃 control-key triad：

```text
guard = fold32(keyLocal)                         具体变体由 seed bits 选择
pathKey = ((int)keyLocal ^ (int)seed)
pathKey = pathKey ^ (pathKey >>> shift(seed, 5))
blockKey = fold16(((int)(keyLocal >>> 32) ^ guard ^ (int)(seed >>> 32)))
```

`fold16(x)` 表示：

```text
x = x ^ (x >>> 16)
```

如果存在 class key table，初始化会立即把 table word 混入三个 local：

```text
classWord = table[index(keyLocal, token, seed)] ^ keyMix(keyLocal, seed)
guard   = guard + (classWord ^ mix(seed, GUARD_MIX))
pathKey = (pathKey ^ guard) + mix(seed, PATH_MIX)
blockKey = (blockKey + pathKey) ^ classWord
```

### State、domain 和 token 编码

CFF 区分逻辑 block state 和 dispatcher local 中实际保存的值：

- `stateByLabel` 为每个受保护 block 分配唯一 int state。
- 每个 block 还会得到一个 `CffBlockKeyState`：`guardKey`、`pathKey`、`blockKey`、`pcToken`、`methodKey`、`methodSalt`。
- `pcLocal` 保存加密后的 `pcToken`，不是 raw state。`domainLocal` 保存加密后的 island-domain token。

旧的 direct `encodedState(state)` 模型已经被 token materialization 取代。transition 用预期目标 key state 派生的 mask 加密每个 stored token：

```text
encryptedToken =
  token
  ^ classTokenMask(targetKeys, seed)
  ^ classObjectTokenMask(targetKeys, seed)
  ^ controlTokenMask(targetKeys, seed)

controlTokenMask(keys, seed):
  x = keys.guardKey + (keys.pathKey ^ mix(seed, 0x4354504D31))
  x = x ^ (keys.blockKey + mix(seed, 0x4354424D31))
  return x ^ (x >>> shift(seed, 9))
```

Class mask 使用 `guardKey`、`pathKey`、`blockKey` 索引 class key table，并把选中的 table word 与同一 key state 的 digest 混合。大型或 shared material 路径会把加密 row 放入 class material table，并通过 generated helper 解码，而不是在 bytecode 中留下明文 dispatch token。

Dispatcher 比较解码后的 route token，而不是原始 bytecode label。token 或 domain 不匹配会进入 poison path，不会回到原始 bytecode。

### Edge key transfer

每条 transition 计算 relation seed：

```text
stepSeed = mix(edgeSeed ^ selectorSeed, state)
stepSeed = mix(stepSeed ^ domainSeed, island ^ roleOrdinal)
if stepSeed == 0:
  stepSeed = edgeSeed ^ 0x4346465354455031

baseSeed = stepSeed ^ 0x5452414E534B4559 ^ roleOrdinal
```

当前 hot transition path 不再用彼此独立的 tiny operation 增量修改 live triad。它先从 source live locals 和 method key 计算 compact control-token base：

```text
base =
  classTokenMask(sourceKeys, baseSeed ^ 0x4347434C41535331)
  ^ (guard + (pathKey ^ mix(baseSeed, 0x43475041544831)))
  ^ (blockKey * (mix(baseSeed, 0x4347424C4F434B31) | 1))
base = base + methodKeyFold(keyLocal, baseSeed ^ 0x43474D45544831)
base = base ^ (base >>> shift(baseSeed, 11))
```

随后它用该 base 从加密常量或 material-table row 解码目标 `guardKey`、`pathKey`、`blockKey`、`pcToken`、可选 domain token 和目标 method-key word。提交后的 live triad 与 long method key 因此作为一次 keyed transfer 移动到目标 block 的 key state；它们不是仅凭 descriptor、label 或静态 seed 重新计算出来的。

### Edge 类型

每条 transition 选择一种路径：

| Edge kind | 行为 |
|---|---|
| `DIRECT_ISLAND` | 解码目标 keys，保存加密 `pcToken`，然后直接跳到 island dispatcher。 |
| `ALIAS_HUB` | 解码目标 keys，保存加密 `pcToken` 和加密 domain token，然后跳到 alias hub。 |
| `HUB` | 解码目标 keys，保存加密 `pcToken` 和加密 domain token，然后跳到 group hub。 |

Handler 优先选择 hub 或 alias-hub。普通 edge 由 seed bits 选择，并在可行时混入 direct-island edge。

### Poison 路径

Dispatcher 比较 keyed token。`pcToken` 或 domain token 无匹配时进入 poison path，而不是回到原始 bytecode。

## Numeric Constant Obfuscation

`JvmConstantObfuscationPass` 明确限定为 numeric-only。覆盖：

- int push 形态，包括 iconst/bipush/sipush 和 numeric integer `LDC`；
- long/float/double numeric `LDC`；
- `IINC`；
- numeric static `ConstantValue` 字段。

对指令位置，该 pass 使用原始受保护指令的 CFF metadata。site seed 绑定 master seed、owner、method descriptor、CFF block/state identity 和 ordinal：

```text
siteSeed = mix(masterSeed ^ domain, ownerHash)
siteSeed = mix(siteSeed, methodNameHash)
siteSeed = mix(siteSeed, methodDescHash)
siteSeed = mix(siteSeed, cffBlockIndex)
siteSeed = mix(siteSeed, cffState)
siteSeed = mix(siteSeed, ordinal)
```

运行时 mask 不是局部固定值，而是从 live CFF state 派生：

```text
dyn = F(guardLocal, pathKeyLocal, blockKeyLocal, pcLocal, domainLocal, keyLocal, siteSeed)
table = classKeyTable[index(dyn, keyLocal, siteSeed)]
constant = encryptedPayload ^ G(dyn, table, keyLocal, siteSeed)
```

该 pass 只重写 CFF 标记为 protected 的原始应用指令。Generated key-dispatch、CFF transition、poison、reflection-filter、class-key-table initialization 和 string/constant helper node 都会被标记为 generated 并跳过。如果候选位置无法绑定到 CFF live state，它不会降级成较弱的 method-key-only decode。

Numeric `ConstantValue` 字段会清空并改由 `<clinit>` 赋值。Float/double 常量通过 raw bits 解码：`Float.intBitsToFloat` 和 `Double.longBitsToDouble`。

## String Obfuscation

`JvmStringObfuscationPass` 覆盖：

- 直接 string `LDC`；
- 降入 `<clinit>` 的 string `ConstantValue` 字段；
- `StringConcatFactory` recipe literal piece 和 string bootstrap constant。

每个字符串位置：

1. 构建 CFF-bound `siteSeed`。
2. 从 live CFF locals 和 class key table 派生 root word。
3. 将 UTF-8 字符串编码为 `length || bytes || randomPad`。
4. 使用 root word 派生的 stream 对 payload 做 XOR。
5. 用 `AES/ECB/NoPadding` 或 `DES/ECB/NoPadding` 加密结果。

偶数 ordinal 使用 AES。部分奇数 ordinal 在派生 DES key 不是弱 key 时使用 DES；弱 DES key 会在 transform 阶段回退到 AES。

运行时 decode 会重算同一个 live root word 和 key bytes：

```text
key[word] = streamWord(root, keySeed(siteSeed, word, algorithm))
xor[i]    = streamWord(root, xorSeed(siteSeed), i / 4)
plain     = cipherDecrypt(ciphertext, key) ^ xor
```

该 pass 安装类内 cache：

- 每个 class 每种算法一个 cached `Cipher` 字段；
- 每个 site 一个 encrypted byte payload；
- 每个 site 一个 volatile fingerprint 和 decoded string cache。

这些 cache 是 generated field，不是 runtime/helper class。Reflection filter 会从 `getFields()` 和 `getDeclaredFields()` 中隐藏它们。启用 `renamer` 时，后置 generated-helper renamer 会归一化这些字段名。

## Native 混淆模型

native 重写不是 JNI fallback 方案。Java 方法体会被替换成大型 `LinkageError` stub，HotSpot Method entry 会被 patch 到生成的 trampoline。

### 选择与闭包

native 选择使用注解和 glob 配置。初选后，stage 会将应用内被调用者加入 candidate set。之后重复 safety checking，直到 selected method set 稳定。

不支持的方法会被拒绝。`skipOnError: false` 时，拒绝或翻译失败会终止构建。

### 翻译前降低

native stage 在翻译前降低受支持的 invokedynamic：

- Lambda metafactory 降为生成的 lambda class。
- Record/object methods bootstrap 降为显式 bytecode。

只有 translator 有对应 lowering 或 direct handling path 的 invokedynamic bootstrap family 才应被接受。

### C 生成

translator 生成：

- 每个 translated method 一个 raw implementation function；
- 等价 call shape 共享 signature dispatcher；
- native-to-Java direct dispatch helper；
- virtual dispatch inline cache；
- translated try handler 的 exception dispatch；
- 可能持有 oop 的 translated local 的 object-local root；
- translated frame 的 shadow stack push/pop；
- tail-recursion landing pad。

### HotSpot patch

`JNI_OnLoad` 保持受控，但会执行必要 native 初始化：

1. 捕获当前 JavaThread register。
2. 调用 `GetEnv`。
3. 保存 JNI function table anchor。
4. 初始化 HotSpot layout 与 VMStruct metadata。
5. 初始化 GC 与 object metadata helper。
6. 发现 manifest method 并 patch Method entry。

生成的 trampoline 从 patched Java ABI entry 进入，此时 JavaThread 保持 `_thread_in_java`。缺少必要 metadata、barrier、CodeHeap 支持或 call-stub 支持时必须 abort，不能 fallback 到 Java bytecode。

### 重写后的 Java 方法体

translated Java method 被重写为大型不可 inline stub，并抛出：

```text
LinkageError("please check your native library load correctly")
```

如果该 stub 被执行，说明 native loading 或 Method entry patch 失败。

## 限制

- 实现是 HotSpot 专用。
- VMStruct 和 symbol 可用性随 JDK 构建变化。
- ZGC 和 Shenandoah 需要正确 barrier metadata；跳过翻译不是兼容性。
- 每次实现变更后都必须重新生成 native artifact 并运行测试。
- 混淆 key 公式对拿到 JAR 且有足够时间的分析者是公开可恢复的。
