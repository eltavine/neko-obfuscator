# NekoObfuscator 架构

[English](../ARCHITECTURE.md)

## 范围

本文档旨在描述当前的实际代码实现，而非早期的架构规划。目前，CLI 会注册 `neko-transforms/jvm` 目录下重构后的 JVM Pass，并支持可选地执行 `neko-native` 的 Native（本地代码）编译阶段。
## 模块拓扑

```text
neko-cli
  -> neko-config
  -> neko-core
       -> neko-transforms
       -> neko-native     (当 native.enabled=true 时通过反射加载)
       -> neko-runtime    (注入到输出 JAR 中)
```

| 模块                | 角色                                                                                                                                       |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `neko-api`        | 提供配置模型、注解以及 Transform 契约接口。                                                                                                              |
| `neko-config`     | 负责 YAML 配置文件的解析与校验。                                                                                                                      |
| `neko-core`       | 处理 JAR I/O、类层级分析、Pass 调度、无用代码清理、运行时注入以及混淆映射 (Mapping) 的输出。                                                                               |
| `neko-transforms` | 包含当前的 JVM Pass：`renamer`、`keyDispatch`、`methodParameterObfuscation`、`controlFlowFlattening`、`runtimeVariableObfuscation`、`constantObfuscation` 和 `stringObfuscation`。 |
| `neko-native`     | 负责方法选择、安全检查、invokedynamic 指令降级、JVM 到 C 的代码翻译、C 代码生成、Native 构建以及 HotSpot 方法修补 (Method Patch)。。                                            |
| `neko-runtime`    | 注入到输出 JAR 中的最小化运行时类；当前仅包含 `NekoNativeLoader`。                                                                                            |
| `neko-test`       | 提供 JUnit 集成测试与 Native 测试支持。                                                                                                              |

## IR 层

| 层级 | 当前用途                                                                  |
|----|-----------------------------------------------------------------------|
| L1 | 对 ASM 的 `ClassNode` / `MethodNode` 的直接包装；当前的 JVM Pass 直接在 L1 层面进行修改。  |
| L2 | 保留在 `neko-core` 中的 CFG/SSA（控制流图/静态单赋值）基础设施；目前不再是重构后 CFF（控制流平坦化）的主干路径。 |
| L3 | 用于 Native 翻译与 C 代码生成的 C 语言级别数据模型。                                     |

重构后的 JVM CFF 不再依赖通用的 L2 降级 (Lowering) 机制。相反，它直接利用 ASM 分析验证器帧 (Verifier Frame)，拆分安全的控制流基本块入口 (Block Leader)，并在原方法体内进行原地重写。
## 主流水线

`ObfuscationPipeline.execute(inputJar, outputJar)` 的完整执行流程如下：

1. 读取输入 JAR 包中的类文件与资源。
2. 构建类层级 (Class Hierarchy)，以及可选的类路径 (Classpath) 层级。
3. 创建 `PipelineContext`（流水线上下文），包括构建主种子 (Master Seed)。
4. 根据配置 `transforms.<id>.enabled` 过滤并筛选已注册的 Pass。
5. 依照各个 Pass 的执行阶段 (Phase) 与依赖关系进行调度排序。
6. 在 L1 的类和方法层级上，依次运行已启用的 Pass。
7. 清理脏字节码 (Dirty Bytecode)：移除无用帧、删除不可达代码、清理冗余的 try/catch 范围并重新计算局部变量 (Locals)。
8. 在相关场景下，对生成的辅助 (Helper) 方法进行安全加固 (Harden)。
9. 若启用了 `renamer`：对后续 JVM Pass 新增的生成类/辅助 API 进行名称归一化，并在全局范围内重写对应生成的字符串常量。
10. 注入已启用功能所需的 Runtime 运行时类。
11. 在相关场景下，执行运行时的控制流加固 (Runtime-control-flow Hardening)。
12. 根据 `renamer` 的结果，更新 Manifest、文本资源文件以及资源路径。
13. （可选）执行 Native 编译阶段。
14. 若启用了 `strictCoverage`，则对 JVM 层的混淆覆盖率进行严格校验。
15. 若启用了 `renamer.renameRuntime`，则对注入的 Runtime API 名称进行混淆。
16. 最终写出输出 JAR 包，并可选择性导出映射 (Mapping) 文件。

## JVM Pass

默认 CLI 注册顺序：

```text
renamer
keyDispatch
methodParameterObfuscation
controlFlowFlattening
runtimeVariableObfuscation
constantObfuscation
stringObfuscation
```

调度器会严格遵守元数据中的阶段 (Phase) 与依赖声明：`methodParameterObfuscation` 和 `controlFlowFlattening` 均依赖于 `keyDispatch`；`runtimeVariableObfuscation` 依赖于 `controlFlowFlattening` 和 `validationSinkHardening`；而 `constantObfuscation` 和 `stringObfuscation` 则依赖于 `controlFlowFlattening`。

### `renamer`（重命名器）

`JvmRenamerPass` 运行在 `PRE_TRANSFORM` 阶段，早于 CFF 与字符串辅助 (String Helper) 字段的生成：

- 将类重命名并归入 `transforms.renamer.packagePrefix` 定义的包前缀中（默认为 `a/`）。
- 将字段和方法重命名为短名称。Override/Interface 方法组保持名称兼容性，`main([Ljava/lang/String;)V` 等 JVM 规范入口强制保留，覆盖外部库方法的方法不参与重命名。
- 常见的类名字符串、`.class` 资源字符串、包资源路径，以及那些可以直接解析出 Owner 或具备全局唯一映射的 `Class.getMethod` / `Class.getField` 反射调用字面量，都会被同步重写。
- 剥离 Source File、Line Number 以及 Local Debug Table 等调试信息。
- 混淆映射结果会输出到生成的 JAR 包同目录下的 `<output>.map` 文件中。

由于 CFF 和 String Obfuscation 会在重命名阶段之后继续添加 synthetic（合成的）辅助字段，因此 `ObfuscationPipeline` 会在流水线末尾执行第二阶段的重命名。该阶段会将诸如 `$...`、`__e...`、`__neko_n...` 这类生成的辅助字段/方法，统一转换为标准的短名风格，全局更新生成的反射过滤 (Reflection-filter) 字符串，并将其追加到 mapping 文件中。通过此机制，最终输出的字节码中绝不会残留 `$1e76bobp` 这类特征明显的生成字段名。

### `keyDispatch`（密钥分发）

`JvmKeyDispatchPass` 在 CFF 之前运行，旨在为隐藏方法密钥 (Hidden Method Key) 做好前置准备：

- 对于允许安全拓宽 ABI（应用程序二进制接口）的应用内方法，会在其记录的 `keyIndex` 处插入一个隐藏的 `long` 型参数。构造器、类初始化器、Java `main` 方法、外部 Override 槽位、注解、Native 方法以及 LambdaMetafactory SAM 槽位等，将严格保持 JVM 要求的原始签名形态。
- 调用被篡改方法的 Call Site（调用点）会被改写：通过寄存器溢出 (Spill) 保存现有状态，围绕隐藏密钥重新加载参数，确保最终的操作数栈 (Operand Stack) 与带密钥的方法描述符 (keyed Descriptor) 完全匹配。
- 边界方法 (Boundary Methods) 保持 JVM 规定的签名不变，但会在方法内部注入与其被调用者相同的密钥混合器 (Incoming-key Mixer) 来初始化本地密钥。
- Abstract/Interface 等抽象应用方法为了保证 ABI 的一致性，其描述符会被拓宽，但自身不会生成包含具体逻辑的方法前言 (Prologue)。

该 Pass 会通过 `controlFlowFlattening.flowKeyLocalByMethod` 发布本地的 Key 槽位，从而让 CFF 与调用链的密钥分发共享同一个局部变量 (Local)。

### `controlFlowFlattening`（控制流平坦化）

`ControlFlowFlatteningPass` 依赖于 `keyDispatch`，并在每个符合条件的方法内部进行原地重写：

* 强制保留构造器中 `this(...)` 或 `super(...)` 调用之前的原始代码流。
* 仅在验证器状态 (Verifier State) 兼容的 Label 处切分基本块。
* 分发器组 (Dispatcher Group) 严格按照帧签名 (Frame Signature) 进行拆分，从而避免合并不兼容的局部变量状态。
* 每个分发器组被进一步拆分为多个孤岛 (Island)，并支持生成别名枢纽 (Alias Hub) 以增加混淆复杂度。
* `pcLocal` 负责存储加密后的 `pcToken`，`domainLocal` 则存储加密的 island-domain token；状态转换 (Transition) 过程中，会结合动态源状态 (Live Source State) 与类级密钥表掩码 (Class-key/Material-table Mask) 来动态解码目标的 `guardKey`、`pathKey`、`blockKey` 以及方法密钥。
* 异常处理器 (Exception Handler) 会通过桥接代码 (Bridge) 无缝接入同一套带密钥的分发器模型 (Keyed Dispatcher) 中。

此外，CFF 还会为每个包含受保护代码的类安装一个合成的类密钥表字段 (Class Key Table Field)。针对 `Class.getFields()` 和 `Class.getDeclaredFields()` 的反射调用会被拦截和过滤，确保生成的表字段在运行时不可见；此机制同样涵盖了跨类检查另一受保护类的反射调用点。

### `runtimeVariableObfuscation`（运行时变量混淆）

`JvmRuntimeVariableObfuscationPass` 在 CFF 发布 live-state metadata 后运行：

- 受保护的应用层 primitive local 写入会移动到加密 shadow slot；store mask 来自 live CFF locals、method key material 与 class key table。
- 原始 primitive local slot 会在每次受保护 store 后被污染；后续受保护 load 只会在操作数栈上临时从 encrypted shadow 投影真实值。
- 受保护的应用层 reference local 写入会移动到 verifier-valid shadow slot，原始 slot 会被置空。
- 安全的 int-like local zero/equality 分支可以直接比较 encoded shadow 与 mask local，而不需要恢复 plaintext accumulator local。

### `methodParameterObfuscation`（方法参数混淆）

`JvmMethodParameterObfuscationPass` 在 `keyDispatch` 之后运行：

* 将目标方法从包含了密钥分发的最终描述符，进一步改写为仅接受单一 `Object[]` 数组作为参数的描述符。
* 方法的前言 (Prologue) 会被修改为从数组中逐个解包原始参数：基本类型 (Primitive) 会自动拆箱 (Unbox)，引用类型会进行强转 (Cast)，并写回原有的局部变量内存布局中。
* 所有对应的调用点 (Call Site) 均被改写：先创建并填充载体数组 (Carrier Array)，然后再发起调用。
* 因打包重载 (Packing Overload) 引起的命名冲突，会使用确定性的 `$nkop$<hash>` 后缀来解决，这些后缀在后续阶段依然会被重命名器处理。
* 构造器、类初始化器、`main` 方法、Native 方法、外部 Override、注解、内部生成的方法以及 `invokedynamic` Handle 目标均会被排除在此转换之外。

### `constantObfuscation`（常量混淆）

`JvmConstantObfuscationPass` 在 CFF 之后运行，它仅重写被 CFF 标记为“受保护”的原始应用指令：

* 整数入栈指令 (Integer push opcode)、针对长整型/浮点型/双精度型的数值型 `LDC` 与 `IINC` 指令；
* 类型为 byte/char/short/int/long/float/double 的数值型静态 `ConstantValue` 字段。

在运行时的解码环节，上述指令位置高度依赖 CFF 的动态元数据：`guardLocal`、`pathKeyLocal`、`blockKeyLocal`、`pcLocal`、`domainLocal`、`keyLocal` 以及类密钥表字面量。**如果某条指令无法成功绑定到当前的 CFF 状态元数据上，该位置将采取阻断运行（Fail Closed）策略，直接导致解码失败，而不是降级退化为仅依赖方法密钥。** 数值型字段常量的 `ConstantValue` 属性会被彻底擦除，改为在 `<clinit>`（静态初始化块）中通过生成的解码字节码进行动态赋值。Float/Double 类型则直接使用原始比特级解码（即 `Float.intBitsToFloat` 和 `Double.longBitsToDouble`）。

### `stringObfuscation`（字符串混淆）

`JvmStringObfuscationPass` 同样在 CFF 之后运行，负责重写受保护的原始字符串调用位置：

* 直接的字符串 `LDC` 指令；
* 在执行 CFF 或 String 处理前，被降级 (Lowered) 迁入 `<clinit>` 的静态 final 字符串 `ConstantValue` 字段；
* `StringConcatFactory` 配方 (Recipe) 中的字面量与常量字符串片段。

每个加密后的载荷 (Payload) 均为带有长度前缀的 UTF-8 字节数组，并被填充 (Padding) 至 AES 或 DES 的块大小。加密过程首先使用由 CFF 动态状态派生的密钥流进行 XOR 异或运算，随后采用 `AES/ECB/NoPadding` 或 `DES/ECB/NoPadding` 进行加密。运行时将根据活跃的 CFF 状态与类密钥表重新计算解密密钥。为了降低内存分配开销，该 Pass 会在宿主类 (Owner Class) 中缓存各个类的 `Cipher` 实例，以及各个调用点的加密载荷、指纹和字符串字段，**整个过程不会向项目中注入任何外部的 Runtime/Helper 类**。

生成的字符串辅助字段均为合成 (Synthetic) 属性，会自动从反射字段枚举中隐藏。当启用 `renamer` 时，这些辅助名称将被后置的生成辅助 API 重命名逻辑进行彻底归一化。

## Native Stage (Native 编译阶段) 

当配置 `native.enabled: true` 时，`ObfuscationPipeline` 会通过反射加载 `NativeCompilationStage` 模块。

Native 阶段的执行步骤如下：

1. 解析项目中的所有类。
2. 在筛选方法前，对受支持的 `invokedynamic` 指令进行降级处理：
   - `LambdaMetafactory.metafactory`
   - `ObjectMethods.bootstrap`
3. 通过注解与 Glob 匹配模式 (Pattern) 筛选目标方法。
4. 构建调用闭包，将应用内的被调用者一同纳入翻译范围，以防止已翻译 (Translated) 的方法通过不受支持的边界去调用未翻译的应用方法。
5. 迭代剔除不安全的方法，直到 Native Manifest 的集合完全闭合并确认安全。
6. 将选中的字节码翻译为等效的 C 函数。
7. 生成 C 源码、头文件、Manifest、签名分发器 (Signature Dispatcher)、跳板函数 (Trampoline) 以及 HotSpot 修补 (Patch) 的支持代码。
8. 调用 Zig 工具链，构建对应平台的 Native Library。
9. 将已翻译的 Java 方法体直接掏空，替换为抛出 `LinkageError` 的桩代码 (Stub)；在正常流程中，这些 Java 层的 Stub 绝不应该被执行到。
10. 向宿主类的 `<clinit>` 块中注入 `NekoNativeLoader.load()` 调用逻辑。
11. 将编译好的 Native Library 打包输出至 `/neko/native/` 目录下。

值得注意的是，实际的 Native 入口并非 Java 的 `native` 关键字存根，而是通过 HotSpot 的 Method Entry Patch 实现的。生成的 `JNI_OnLoad` 会捕获当前的 `JavaThread`，利用 VMStructs 和底层符号初始化 HotSpot 元数据，寻找 Manifest 入口，最终将方法的执行流 (Method Entry) 强行 Patch（重定向）到生成的跳板函数中。

## Runtime 注入

当前 NekoObfuscator 运行时的暴露面 (Runtime Surface) 非常小。只有在开启 Native 功能时，才会注入 `NekoNativeLoader` 类。它的唯一职责就是将平台的动态库解压至临时文件夹，并调用 `System.load` 进行加载。

JVM 层面的字符串与常量混淆**不会**注入任何 Runtime/Helper 类。所有的解码逻辑都会被直接内联 (Inline) 到被转换的目标方法与类初始化器中，实现了极高的隐蔽性。

## 失败模型

如果在严格的 Native 环境下工作，强烈建议设置 `native.skipOnError: false`。在当前代码逻辑中，若配置为 `skipOnError: true`，一旦翻译或编译失败，流水线可能会退而求其次返回原始的类文件与资源；这种容错机制对开发调试阶段很有帮助，但绝对不能用于生产环境的 Native 覆盖率验证。

生成的 Native 代码在遭遇缺失必要的 HotSpot 元数据、GC 屏障 (Barrier) 异常、CodeHeap 不支持或 Call-stub 异常等底层错误时，**应当采取阻断运行 (Fail Closed) 策略，直接触发崩溃以保护代码逻辑，而非退化执行或返回异常结果**。
