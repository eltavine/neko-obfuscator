# NekoObfuscator 白皮书

[English](../WHITEPAPER.md)

## 威胁模型

NekoObfuscator 旨在指数级提升静态分析的成本，但**不提供**密码学意义上的绝对安全边界。

| 攻击者类型              | 预期防御能力 (抗性)                               |
|--------------------|-------------------------------------------|
| 普通反编译器用户           | 当开启 CFF（控制流平坦化）时，会产生极高的分析阻力。              |
| 人工阅读字节码专家          | 控制流与调用密钥 (Call Key) 产生的噪声极其显著。            |
| 专用反混淆器开发者          | 具备部分抗性；但其混淆公式与分发器 (Dispatcher) 形态可以被数学建模。 |
| 动态插桩框架             | 抗性较低，除非配合 Native 模式以及额外的环境加固手段。           |
| Native 调试器 / VM 专家 | 抗性较低；HotSpot 层的 Patch 机制可被底层 Native 工具审查。 |

## 当前 JVM 混淆模型

当前的 JVM 防御面采用链式的 Pass 流水线 (Pipeline) 架构：

1. `renamer`：在后续 Pass 写入元数据之前，彻底擦除类与成员的固定身份标识。
2. `keyDispatch`：生成方法本地的密钥材料 (Key Material)，并隐蔽地在调用链中传播密钥。
3. `methodParameterObfuscation`：将目标方法的参数统一打包进单一的 `Object[]` 载体 (Carrier) 中。
4. `controlFlowFlattening`：消耗密钥材料，将原方法的控制流重写为带密钥的孤岛分发器 (Keyed Island Dispatcher)。
5. `constantObfuscation`：仅在 CFF 动态状态 (Live State) 元数据可用时，重写数值型常量。
6. `stringObfuscation`：采用 AES/DES + XOR 算法加密字符串字面量，并在运行时从 CFF 动态状态派生解密密钥。

**架构原则**：JVM 层面的字符串混淆、常量混淆以及 CFF 机制，**绝对不会**向项目中注入任何额外的 Runtime 或 Helper 类。所有的解码逻辑均会被直接内联写入被转换的方法或类初始化器 (`<clinit>`) 内部。

## 重命名器 (Renamer)

`JvmRenamerPass` 运行在密钥分发 (Key Dispatch) 与 CFF 之前。

应用层的类名将基于 `packagePrefix` 配置，按照简短的内部名称 (Internal Name) 序列进行重新分配：

```text
default: a/a, a/b, a/c, ...
```

字段与方法均采用短命名策略。方法的重命名组 (Rename Group) 会严格维持应用层面的继承与接口兼容性。覆盖 (Override) 外部库方法的方法、构造器、类初始化器、Native 方法以及 Java 静态入口 `main([Ljava/lang/String;)V` 将被强制保留原名。

Renamer 还会主动重写那些能够推断出归属类 (Owner) 的直接反射面：

* 类名字符串 (Class-name String) 以及带点号的类名字符串；
* `*.class` 格式的资源字符串；
* 包前缀命中了已被重命名包的资源路径；
* 显式的 `Class.getMethod`、`Class.getDeclaredMethod`、`Class.getField` 和 `Class.getDeclaredField` 名称字面量。

在流水线的后续阶段，还会执行辅助生成的 API 重命名 (Generated-helper API Renaming)。该阶段会将 CFF 类密钥表 (Class Key Table) 与字符串缓存等合成辅助字段，从最初的 `$...` 或 `__...` 统一转换为同一种短名风格，并全局重写生成的反射过滤字面量 (Reflection-filter Literal)。这一机制确保了生成的名称不会暴露成为另一套可被静态识别的命名模式。

## 密钥分发 (Key Dispatch)

`JvmKeyDispatchPass` 负责为符合条件的方法建立隐藏的 `long` 型密钥。

### 适用条件

描述符拓宽 (Descriptor Widening) 仅适用于能够安全承载隐藏的 JVM `long` 类型，且不破坏外部 ABI（应用程序二进制接口）的应用方法：

* 运行时的内部类与生成的辅助方法将被忽略。
* 注解类、Native 方法、构造器、类初始化器、Java `main` 方法、外部重写槽位 (Override Slot) 以及 LambdaMetafactory SAM 槽位等，将严格保留其原始描述符。
* 抽象方法 (Abstract) 与接口 (Interface) 等应用方法依然会被拓宽描述符，以确保应用内的实现者与调用点保持 ABI 一致性，但它们内部不会生成处理本地密钥的方法前言 (Prologue)。

### 签名拓宽机制

针对可处理的方法：

```text
原始签名:  owner.name(args)ret
重写后签名: owner.name(args[0:keyIndex], long hiddenKey, args[keyIndex:])ret
```

`keyIndex` 通常等同于原始参数的数量。LambdaMetafactory 的实现目标方法，会使用针对该 Bootstrap Target 记录的“感知捕获参数的索引” (Captured-argument-aware Index)，从而保留未绑定接收者 (Unbound Receiver) 的形态。插入密钥槽位之后，所有的局部变量索引 (Local Variable Index) 会自动向后平移两个槽位。当前方法的帧 (Frame) 与局部调试信息 (Debug Locals) 会被清空，交由 ASM 重新计算方法形态。

调用这些被拓宽方法的调用点 (Call Site) 将被改写：首先将接收者与原始参数溢出转储 (Spill) 到局部变量中，随后围绕 `keyIndex` 重新加载隐藏密钥并调用带密钥的描述符：

```text
reload args before keyIndex
load callerKey
reload args after keyIndex
invoke owner.name(args..., long, args...)ret
```

在可行的情况下，该 Pass 会通过类层级体系，解析并继承应用内的调用链。此 Pass 不会拓宽直接的构造器调用 (Direct Constructor Call)。

### 方法密钥初始化

本地方法密钥的来源，始终固定为一个分为三步的入参密钥混合器 (Incoming-key Mixer)：

```text
A = mix(seed ^ 0x474B4D49584131, rotl(mask, 17))
B = mix(seed + 0x474B4D41444431, mask ^ 0x4B4D4144444D534B)
B = B != 0 ? B : 0x4B4D4144444D534B
C = mix(seed ^ rotl(mask, 41), 0x474B4D49584231)

mixed(raw, seed, mask) = ((raw ^ A) + B) ^ C
rawFor(target, seed, mask) = ((target ^ C) - B) ^ A
```

边界方法保留了原始描述符，但会在 Mixer 逻辑之前显式压入 `rawFor(seed, seed, mask)` 操作，因此初始化后的本地密钥即为规范的方法种子 (Canonical Method Seed)。而接收外部入参密钥的方法，则使用固定掩码 `mask = 0x4E4B4F4A564D4B31` 原地混合隐藏的 `long` 型参数。后续的 CFF 密钥转移 (Key-transfer) 重写逻辑，可以将静态的 raw 常量替换为动态解码材料，但被调用方 (Callee) 侧的混合器逻辑永远保持不变。

方法种子 (Method Seed) 在同一次构建生命周期内是确定且唯一的。针对非静态、非私有的虚方法家族 (Virtual Family)，系统会提取其选定的家族根节点 (Family Root) 参与计算，确保所有参与重写的方法行为一致：

```text
普通方法 (normal):
  h = masterSeed ^ 0x9E3779B97F4A7C15
  h = mix(h, hash(className))
  h = mix(h, hash(methodName))
  h = mix(h, hash(methodDescriptor))
  h = mix(h, instructionCount)
  h = mix(h, maxLocals)

虚方法家族 (virtual family):
  h = masterSeed ^ 0x9E3779B97F4A7C15
  h = mix(h, hash(familyRootOwner))
  h = mix(h, hash(methodName))
  h = mix(h, hash(methodDescriptor))
  h = mix(h, 0x5649525453454544)

seed = h != 0 ? h : 0x5DEECE66D
```

系统内部共享的 64 位混合函数如下：

```text
mix(state, value):
  z = state + value + 0x9E3779B97F4A7C15
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)
```

## 方法参数混淆 (Method Parameter Obfuscation)

`JvmMethodParameterObfuscationPass` 在密钥分发之后运行。它负责将符合条件的描述符彻底替换为单一的 `Object[]` 载体数组：

```text
密钥分发后的原始形态: owner.m(A, B, long hiddenKey)R
打包后形态 (Packed): owner.m(Object[])R
```

调用方将分配一个 `Object[]`，对基本类型参数进行装箱 (Box)，将引用类型参数直接存入，随后调用被打包的方法。被调用方的前言 (Prologue) 则负责解包数组：

```text
arg0 = (A) carrier[0]
arg1 = (B) carrier[1]
hiddenKey = ((Long) carrier[n]).longValue()
```

该 Pass 会同步迁移密钥分发的元数据，以确保 CFF 仍能正确观测到 `keyLocal`。该阶段排除了对 JVM 形态敏感的方法、外部覆盖方法、注解、生成的辅助方法以及 `invokedynamic` 的句柄目标。如果存在多个重载 (Overload) 方法被迫折叠成相同的打包描述符，系统会预先分配带有确定性的 `$nkop$<hash>` 后缀，随后这些后缀仍会被 Renamer 重命名。

## 控制流平坦化 (Control-Flow Flattening)

`ControlFlowFlatteningPass` 会在目标方法的原方法体上，执行直接带密钥的 CFF (Direct Keyed CFF) 重写。

### 方法形态的兼容与保持

* 构造器仅在强制执行 `this(...)` 或 `super(...)` 调用之后的部分，才会被执行平坦化处理。
* 自动跳过 Runtime 运行时类、内部生成的方法、抽象方法、Native 方法以及用于生成类密钥表的 `<clinit>` 静态块。
* 严格按照局部帧签名 (Local-frame Signature) 对分发器目标进行分组，以绝对保证 JVM 验证器 (Verifier) 的兼容性。

### 局部变量 (Locals) 布局

CFF 在底层重新分配了以下局部变量：

| 局部变量 (Local)     | 物理含义                              |
|------------------|-----------------------------------|
| `keyLocal`       | 继承自 `keyDispatch` 阶段的 `long` 型密钥。 |
| `pcLocal`        | 目标基本块的加密程序计数器令牌 (`pcToken`)。      |
| `guardLocal`     | 由 `keyLocal` 派生而来的方法级守卫 (Guard)。  |
| `pathKeyLocal`   | 处于动态演化中的路径密钥 (Edge/Path Key)。     |
| `blockKeyLocal`  | 处于动态演化中的基本块密钥 (Block Key)。        |
| `domainLocal`    | 加密后的孤岛域令牌 (Island-domain Token)。  |
| `exceptionLocal` | 当存在异常处理器桥接时，临时暂存的异常对象。            |

### 状态分配 (State Allocation)

针对每一个方法，初始化过程如下：

```text
salt = mix(masterSeed, hash(className + "." + methodName + methodDesc))
states = uniqueStates((int)(salt >>> 32), blockCount)
```

每个基本块的 Label 都会被分配一个全局唯一的整型状态码。针对别名标签 (Alias Label)，会被直接映射到规范基本块 (Canonical Block) 的状态。

### 分发器分组机制 (Dispatcher Grouping)

基本块严格按照 JVM 的验证器帧签名进行分组：

```text
frameSignature(label) =
  concat(local[0].descriptorOrMarker, ';', local[1].descriptorOrMarker, ';', ...)
```

每一个组 (Group) 内部包含：

* 一个核心枢纽 (Hub)；
* 1 到 4 个孤岛标签 (Island Label)；
* 1 到 3 个别名枢纽 (Alias Hub)；
* 组级别的 Salt；
* 针对每个目标的 `selectorSeed` 与 `domainSeed`。

### 状态、域与令牌的编码设计

CFF 在设计上严格区分了“逻辑上的基本块状态”与“实际存储在分发器局部变量中的值”：

* `stateByLabel` 为每个受保护的基本块分配唯一的整型逻辑状态。
* 每个基本块还会获得一个独有的 `CffBlockKeyState` 结构：包含 `guardKey`、`pathKey`、`blockKey`、`pcToken`、`methodKey` 以及 `methodSalt`。
* `pcLocal` 存储的是**加密后**的 `pcToken`，而绝非原始的明文状态。`domainLocal` 则存储加密后的 Island-domain Token。

旧版的“直接编码状态 (`encodedState`)”模型已被“令牌物化 (Token Materialization)”彻底取代。状态转换 (Transition) 时，会使用预期目标密钥状态所派生出的掩码，对每一个存储的令牌进行加密：

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

### 毒药路径 (Poison Path)

分发器在运行时只会比较带密钥的令牌 (Keyed Token)。如果解密后的 `pcToken` 或 Domain Token 发生哪怕 1 bit 的不匹配，执行流会立刻坠入毒药路径 (Poison Path)，彻底阻断执行逻辑，而绝不会退回到原始的正常字节码中。

## 数值常量混淆 (Numeric Constant Obfuscation)

`JvmConstantObfuscationPass` 明确限定为仅处理数值型常量。覆盖范围包括：

* 整型入栈形态，包括 `iconst`/`bipush`/`sipush` 以及数值型整数的 `LDC` 指令；
* 长整型、单精度、双精度 (long/float/double) 的数值型 `LDC` 指令；
* `IINC`（局部变量自增）指令；
* 类型为基本数值的静态 `ConstantValue` 字段。

对于指令所在位置，该 Pass 会严格绑定原始指令被 CFF 保护时产生的元数据。位置种子 (Site Seed) 会深度融合主种子 (Master Seed)、宿主类哈希、方法描述符、CFF 基本块索引、状态以及调用序号：

```text
siteSeed = mix(masterSeed ^ domain, ownerHash)
siteSeed = mix(siteSeed, methodNameHash)
...
```

运行时的解码掩码并非局部固定常量，而是从活跃的 CFF 状态 (Live CFF State) 动态派生而来：

```text
dyn = F(guardLocal, pathKeyLocal, blockKeyLocal, pcLocal, domainLocal, keyLocal, siteSeed)
table = classKeyTable[index(dyn, keyLocal, siteSeed)]
constant = encryptedPayload ^ G(dyn, table, keyLocal, siteSeed)
```

如果某个候选位置由于优化或裁剪，无法成功绑定到 CFF 动态状态，该指令将跳过混淆，绝不会降级为安全性更弱的仅依赖方法密钥的解码模式。数值型 `ConstantValue` 字段的属性会被清空，统一改由 `<clinit>` 进行动态赋值；浮点数则通过底层比特级解码 (`Float.intBitsToFloat` 和 `Double.longBitsToDouble`) 还原。

## 字符串混淆 (String Obfuscation)

`JvmStringObfuscationPass` 的防御覆盖了：

* 直接的字符串 `LDC` 指令；
* 在混淆处理前被降级迁入 `<clinit>` 的静态字符串 `ConstantValue` 字段；
* `StringConcatFactory` 拼接配方中的字符串字面量片段。

每个字符串位置的加密流程如下：

1. 构建绑定了 CFF 状态的位置种子 (`siteSeed`)。
2. 从活跃的 CFF 局部变量与类密钥表中，派生出根密钥字面量 (Root Word)。
3. 将 UTF-8 字符串封装为 `长度 || 字节数据 || 随机填充码 (RandomPad)` 格式。
4. 使用由 Root Word 派生的密钥流对载荷进行 XOR 异或。
5. 采用 `AES/ECB/NoPadding` 或 `DES/ECB/NoPadding` 算法加密最终结果。

为了兼顾性能与隐蔽性，该 Pass 会在当前宿主类中安装内联缓存 (Inline Cache)，包括缓存 Cipher 实例、加密的字节载荷、校验指纹以及解码后的明文引用。这些缓存字段均被标记为生成属性 (Generated Field)，不会向项目注入任何额外的 Runtime 类，并在启用了 `renamer` 时由后置逻辑统一归一化命名。

## Native 混淆模型

Native 层的重写绝非传统 JNI 方案的降级回调 (Fallback)。经过翻译的 Java 方法体会被彻底掏空，替换为一个抛出 `LinkageError` 的巨型桩代码 (Stub)；而底层的 HotSpot 方法入口 (Method Entry) 会被直接 Patch（重定向）到通过 Zig 预编译好的跳板函数 (Trampoline) 中。

### 选择闭包与指令降级 (Lowering)

Native 编译引擎使用注解与 Glob 模式来定位目标方法。在初步筛选后，系统会构建一个调用闭包 (Candidate Set)，将应用内部所有的被调用者递归纳入。这一安全性校验会反复迭代，直到选定的方法集合达到稳态。

在正式翻译为 C 代码之前，Native 阶段会对 JVM 中高度动态的 `invokedynamic` 指令执行降级 (Lowering) 预处理：

* 将 Lambda Metafactory 彻底降级为内部生成的 Lambda 类。
* 将 Record/Object Methods 的引导逻辑 (Bootstrap) 降级为显式的底层字节码。

### C 代码生成 (C Generation)

翻译器 (Translator) 会生成一套极度复杂的底层防御体系：

* 为每个被翻译的 Java 方法生成一个原始的 C 语言实现函数 (Implementation Function)；
* 具有等价调用形态 (Call Shape) 的函数将共享同一套签名分发器 (Signature Dispatcher)；
* 提供 Native 至 Java 的直接分发辅助机制；
* 注入底层虚方法调用的内联缓存 (Virtual Dispatch Inline Cache)；
* 为被翻译的 `try-catch` 结构生成专门的异常分发器；
* 构建影子栈 (Shadow Stack) 的入栈/出栈机制，追踪并维护可能持有普通对象指针 (oop) 局部变量的局部根 (Object-local Root)；
* 部署针对尾递归优化的着陆垫 (Landing Pad)。

### 异常拦截机制与重写后的 Java 方法体

被成功翻译的 Java 方法会被重写为一个包含海量无用字节码、不可被 JIT 内联 (Non-inlineable) 的超大 Stub，并且在执行时直接抛出极其逼真的异常信息：

```text
LinkageError("please check your native library load correctly")
```

**安全断言**：在正常运行的防御体系中，该 Stub 绝对不可能被触发。一旦该 Stub 代码被执行，即证明攻击者破坏了 Native Library 的加载流程，或是抹除了 HotSpot 层面的 Method Entry Patch 机制。

## 架构限制

* 当前的 Native 实现高度依赖，且仅专用于 HotSpot 虚拟机。
* `VMStruct` 与底层符号的可用性会随着 JDK 版本/构建参数的不同而发生剧烈变化。
* ZGC 与 Shenandoah 垃圾回收器需要极其精确的内存屏障 (Barrier) 元数据支持；在遇到无法推导屏障的情况下，“跳过翻译”仅仅是为了不崩溃，而非真正意义上的完美兼容。
* 每次针对代码的任何细微更改，都必须重新生成 Native 制品 (Artifact) 并执行全套集成测试。
* 从密码学攻防的本质来看：只要攻击者拥有足够的算力、时间并拿到最终的 JAR 包，上述提及的混淆密钥生成公式在理论上依然是“公开且可恢复的”。

