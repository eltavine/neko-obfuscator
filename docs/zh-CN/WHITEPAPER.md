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

当前 JVM surface 有意收窄：

1. `keyDispatch` 创建方法本地 key material 和隐藏调用链 key 传播。
2. `controlFlowFlattening` 消费这些 key material，将方法控制流重写为 keyed island dispatcher。

这取代了旧文档中把未注册 string/number/indy pass 描述为活跃功能的内容。

## Key Dispatch

`JvmKeyDispatchPass` 为可处理方法建立隐藏 long key。

### 适用条件

该 pass 跳过 runtime class、生成的 helper 方法、abstract/native 方法、stack introspection 形态和反射敏感 class。构造器、类初始化器和 Java `main` 签名不会被拓宽。

### 签名拓宽

对可处理的非边界方法：

```text
original:  owner.name(args)ret
rewritten: owner.name(args, long hiddenKey)ret
```

隐藏 key slot 插入到原参数区末尾。slot 之后的 local variable index 后移两个 slot。frame 和 debug locals 会清空，让 ASM 重算方法形态。

调用被拓宽应用方法的 call site 会改写为：

```text
load callerKey
invoke owner.name(args, long)ret
```

可行时会通过类层级解析继承应用调用。

### 边界 key 初始化

边界方法保持描述符，并在本地生成 key：

```text
key = (seed ^ mask) ^ mask
```

接收 incoming key 的方法从隐藏参数派生本地 key：

```text
key = incomingKey ^ seed
key = key + 0x9E3779B97F4A7C15
key = key ^ (key >>> 31)
```

方法 seed 在同一次构建内确定：

```text
h = masterSeed ^ 0x9E3779B97F4A7C15
h = mix(h, hash(className))
h = mix(h, hash(methodName))
h = mix(h, hash(methodDescriptor))
h = mix(h, instructionCount)
h = mix(h, maxLocals)
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

## Control-Flow Flattening

`ControlFlowFlatteningPass` 在原方法体上执行 direct keyed CFF。

### 方法形态保持

- 构造器只在强制 `this(...)` 或 `super(...)` 调用之后平坦化。
- 跳过 runtime class、生成方法、abstract/native 方法、stack introspection 方法和反射敏感 class 形态。
- 按 local-frame signature 分组 dispatcher target，保持 verifier 兼容。

### Locals

CFF 分配：

| Local | 含义 |
|---|---|
| `keyLocal` | 来自 `keyDispatch` 的 long key。 |
| `pcLocal` | 编码后的目标 state。 |
| `guardLocal` | 从 `keyLocal` 派生的方法 guard。 |
| `pathKeyLocal` | 演化中的 edge/path key。 |
| `blockKeyLocal` | 演化中的 block key。 |
| `domainLocal` | 编码后的 island selector。 |
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

初始 key triad 从方法 key 派生：

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

### State 和 domain 编码

state 与 domain 使用相同 keyed value encoder，但 seed domain 不同：

```text
encodedState(state)  = encodedKeyedValue(state,  selectorSeed ^ 0x53544154454B5631)
encodedDomain(idx)   = encodedKeyedValue(idx,    domainSeed   ^ 0x444F4D41494B5631)
```

`encodedKeyedValue(value, seed)` 通过 `(seed >>> 41) & 3` 选择四种算术形态之一：

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

全部算术都是 JVM `int` 算术。

### Edge key 演化

每条 transition 计算：

```text
stepSeed = mix(edgeSeed ^ selectorSeed, state)
stepSeed = mix(stepSeed ^ domainSeed, island ^ roleOrdinal)
if stepSeed == 0:
  stepSeed = edgeSeed ^ 0x4346465354455031
```

当 edge 更新 key 时，它会修改 `guard`、`pathKey`、`blockKey` 中的一到两个，有时也会修改 long method key。tiny operation 由 seed bits 选择：

```text
dst = dst + (source ^ c)
dst = (dst ^ c) + source
dst = (dst + source) ^ c
dst = (dst ^ source) + c
```

method-key mutation 变体包括：

```text
key = (key + (source & 0xffffffffL)) ^ c
key = (key ^ c) + source
key = (key ^ ((long)source << 32)) + c
key = (key + c) ^ source
```

### Edge 类型

每条 transition 选择一种路径：

| Edge kind | 行为 |
|---|---|
| `DIRECT_ISLAND` | 保存编码 `pc`，直接跳到 island dispatcher。 |
| `ALIAS_HUB` | 保存编码 `pc` 和 domain，然后跳到 alias hub。 |
| `HUB` | 保存编码 `pc` 和 domain，然后跳到 group hub。 |

Handler 优先选择 hub 或 alias-hub。普通 edge 由 seed bits 选择，并在可行时混入 direct-island edge。

### Poison 路径

Dispatcher 比较编码值。state 或 domain 无匹配时进入 poison path，而不是回到原始 bytecode。

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
