# NekoObfuscator 架构

[English](../ARCHITECTURE.md)

## 范围

本文描述当前实现，不描述旧规划架构。当前 CLI 注册 `neko-transforms/jvm` 中重构后的 JVM pass，并可选运行 `neko-native` 的 native 编译阶段。

## 模块拓扑

```text
neko-cli
  -> neko-config
  -> neko-core
       -> neko-transforms
       -> neko-native     (native.enabled=true 时反射加载)
       -> neko-runtime    (注入输出 JAR)
```

| 模块 | 角色 |
|---|---|
| `neko-api` | 配置模型、注解、transform 合约。 |
| `neko-config` | YAML 解析和校验。 |
| `neko-core` | JAR I/O、类层级、pass 调度、清理、运行时注入、mapping 输出。 |
| `neko-transforms` | 当前 JVM pass：`keyDispatch` 和 `controlFlowFlattening`。 |
| `neko-native` | 方法选择、安全检查、invokedynamic 降低、JVM 到 C 翻译、C 生成、native 构建、HotSpot method patch。 |
| `neko-runtime` | 注入输出 JAR 的最小运行时类；当前是 `NekoNativeLoader`。 |
| `neko-test` | JUnit 集成与 native 测试。 |

## IR 层

| 层级 | 当前用途 |
|---|---|
| L1 | ASM `ClassNode` / `MethodNode` 包装；当前 JVM pass 直接修改 L1。 |
| L2 | `neko-core` 中保留 CFG/SSA 基础设施；不是重构 CFF 的主路径。 |
| L3 | native 翻译和 C 生成使用的 C 级模型。 |

重构后的 JVM CFF 不依赖通用 L2 lowering。它直接用 ASM 分析 verifier frame，拆分安全 block leader，并在原方法体内重写。

## 主流水线

`ObfuscationPipeline.execute(inputJar, outputJar)` 执行：

1. 读取输入 JAR 的类和资源。
2. 构建类层级和可选 classpath 层级。
3. 创建 `PipelineContext`，包括构建 master seed。
4. 按 `transforms.<id>.enabled` 过滤注册 pass。
5. 按 phase 与依赖调度 pass。
6. 在 L1 类和方法上运行启用的 pass。
7. 清理 dirty bytecode：移除 frame、删除不可达代码、清理 try/catch 范围、重算 locals。
8. 在相关场景下 harden 生成的 helper 方法。
9. 注入启用功能需要的 runtime 类。
10. 可选运行 native 编译。
11. 写出输出 JAR 和可选 mapping 文件。

## JVM Pass

### `keyDispatch`

`JvmKeyDispatchPass` 在 CFF 之前运行，用于准备隐藏方法 key：

- 可安全承载 key 的非构造器、非 `main`、非反射敏感应用方法，其描述符从 `(...args)R` 改写为 `(...args, long)R`。
- 调用这些应用方法的 call site 会先压入 caller key。
- 边界方法保持 JVM 规定签名，并在本地生成 root key。
- 构造器和形态敏感方法保持合法 JVM 签名。

该 pass 通过 `controlFlowFlattening.flowKeyLocalByMethod` 发布本地 key slot，使 CFF 和调用链 key dispatch 共享同一个 local。

### `controlFlowFlattening`

`ControlFlowFlatteningPass` 依赖 `keyDispatch`，并在每个可处理方法内原地重写：

- 保留构造器强制 `this(...)` 或 `super(...)` 调用之前的代码。
- 只在 verifier 状态兼容的 label 处构建基本块。
- dispatcher group 按 frame signature 拆分，避免合并不兼容 local 状态。
- 每个 group 拆成 island，并可生成 alias hub。
- 活跃程序计数器 `pcLocal` 和 island 域 `domainLocal` 使用 `guardLocal`、`pathKeyLocal`、`blockKeyLocal` 编码。
- 异常 handler 通过 bridge 接入同一 keyed dispatcher 模型。

## Native Stage

当 `native.enabled: true` 时，`ObfuscationPipeline` 会反射加载 `NativeCompilationStage`。

native stage：

1. 解析所有类。
2. 在选择前降低受支持的 `invokedynamic`：
   - `LambdaMetafactory.metafactory`
   - `ObjectMethods.bootstrap`
3. 通过注解和 glob pattern 选择方法。
4. 闭包纳入应用内被调用者，避免 translated 方法通过未支持边调用未翻译应用方法。
5. 迭代拒绝不安全方法，直到 native manifest 闭合且安全。
6. 将选中 bytecode 翻译为 C 函数。
7. 生成 C source、header、manifest、signature dispatcher、trampoline 和 HotSpot patch 支持。
8. 使用 Zig 构建平台 native library。
9. 将已翻译 Java 方法重写为大型 `LinkageError` stub；这些 stub 不应被执行。
10. 向 owner class `<clinit>` 注入 `NekoNativeLoader.load()`。
11. 将 native library 打包到 `/neko/native/`。

实际 native 入口是 HotSpot Method entry patch，不是 Java `native` stub。`JNI_OnLoad` 捕获当前 `JavaThread`，通过 VMStructs 和符号初始化 HotSpot 元数据，发现 manifest entry，并将 Method entry patch 到生成的 trampoline。

## Runtime 注入

当前 runtime surface 很小。只有 native 开启时才注入 `NekoNativeLoader`。它将平台 library 解压到临时文件并调用 `System.load`。

当前 CLI 路径不会注入 Java 侧字符串解密器、bootstrap hub 或 key-derivation runtime。

## 失败模型

严格 native 工作建议设置 `native.skipOnError: false`。当前代码在 `skipOnError: true` 时，翻译或编译失败可能返回原始类和资源；这对开发有用，但不能作为 native 覆盖验证。

生成的 native 代码在缺少必要 HotSpot 元数据、GC barrier、CodeHeap 支持或 call-stub 支持时应 fail closed。
