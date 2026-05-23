<div align="center">

# NekoObfuscator

高级 Java 字节码与 Native 混淆工具。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](./LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-red.svg)](https://adoptium.net/)
[![Build: Gradle](https://img.shields.io/badge/build-Gradle-02303A.svg)](./gradle)

[English](./README.md)

</div>

## 项目状态

NekoObfuscator 目前仍处于早期研发与探索阶段。其内部 API、配置字段、底层字节码形态以及 Native 运行时假设，在未来均可能发生不兼容的破坏性变更。**请勿将当前版本的混淆产物直接用作生产级别的 DRM（数字版权管理）或高强度的安全防御边界。**

目前，CLI（命令行接口）默认注册了重构后的 JVM 混淆流水线 (Pipeline)：

- `renamer`：ZKM 风格的类与成员重命名，导出映射 (Mapping) 文件，并重写常见的反射字符串与资源文本。
- `keyDispatch`：为目标方法建立基于隐藏长整型密钥 (`long key`) 的隐蔽派发机制。
- `methodParameterObfuscation`：在密钥分发完成后，将目标方法的所有参数打包进单一的 `Object[]` 载体中。
- `controlFlowFlattening` (CFF)：在原方法体内生成带密钥的孤岛分发器 (Keyed Island Dispatcher)。
- `invokeDynamic`：基于 CFF 的动态状态 (CCF Live State)，通过 `invokedynamic` 指令对方法与字段的引用进行间接化处理。
- `constantObfuscation`：基于 CFF 动态状态 (CCF Live State) 的数值型常量解码。
- `stringObfuscation`：基于 CFF 动态状态 (CCF Live State) 与 AES/DES + XOR 算法的字符串字面量解码，并采用类级别的密码器缓存 (Cipher Cache)。

Native 流水线是一套专为 HotSpot 虚拟机设计的方法翻译与入口修补 (Patch) 系统。它**并非**可移植的 JVM 扩展层，针对每一种 JDK 版本、操作系统平台以及 GC（垃圾回收器）的组合，都需要重新进行严格的运行时验证。

## 核心功能

| 领域                      | 当前实现行为                                                                                                                              |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| JVM 重命名 (Renaming)      | 重命名应用代码的类、字段与方法；严格保留 JVM 入口与重写 (Override) 约束；重写常见的反射字符串与文本资源，并输出 `.map` 文件。                                                         |
| JVM 密钥分发 (Key Dispatch) | 为能够安全承载密钥的方法追加隐藏的 `long` 型参数；重写应用内的调用链以传递密钥；并为边界方法实现本地种子化 (Seeding)。                                                                |
| 方法参数混淆                  | 将目标方法的所有参数统一打包为一个 `Object[]` 载体，在保持隐藏密钥传播的同时全面重写调用点 (Call Site)。                                                                    |
| 控制流平坦化 (CFF)            | 严格按照验证器帧形态 (Verifier Frame Shape) 拆分分发器；使用动态演化的密钥对状态/域进行编码；强制保留构造器初始化前缀代码的原始顺序。                                                     |
| 数值常量混淆                  | 重写数值入栈、数值型 `LDC`、`IINC` 以及数值型 `ConstantValue` 字段；运行时解码高度依赖 CFF 的动态局部变量与类密钥表。                                                        |
| 字符串混淆                   | 加密直接的字符串 `LDC` 指令、降入 `<clinit>` 的静态字符串常量以及拼接配方常量；运行时结合 AES/DES 算法、XOR 密钥流、CFF 状态与类内缓存进行解密，**不注入任何辅助类**。                             |
| Native 翻译               | 结合注解与 Glob 模式筛选目标方法；构建调用闭包将应用内的被调用者一同纳入；对受支持的 `invokedynamic` 进行指令降级；生成等效 C 代码并使用 Zig 构建；最终通过 `JNI_OnLoad` 修补 (Patch) HotSpot 方法入口。 |
| 运行时注入与加载                | 仅在启用 Native 模式时，向项目中注入最小化的 `NekoNativeLoader`，用于加载 `/neko/native/libneko_<platform>_<arch>.<ext>` 动态库。                              |
| 资源加密                    | 在 Native 阶段，提供可选的 AES-256-GCM 高强度非 Class 资源加密。                                                                                      |

## 仓库结构

| 模块                | 核心职责                                                     |
|-------------------|----------------------------------------------------------|
| `neko-api`        | 提供注解、Transform 契约接口以及配置数据模型。                             |
| `neko-config`     | 负责 YAML 配置文件的解析与严格校验。                                    |
| `neko-core`       | 处理 JAR I/O、各级 IR (中间表示)、Pass 调度、冗余清理、运行时注入及混淆映射。         |
| `neko-transforms` | 包含重构后的核心 JVM Pass：重命名、密钥分发、参数打包、带密钥的 CFF、常量混淆与字符串混淆。     |
| `neko-native`     | 负责 Native 目标选择、安全检查、字节码降级、C 代码生成、Zig 工具链构建以及 HotSpot 修补。 |
| `neko-runtime`    | 注入到输出 JAR 包中的运行时类；当前仅包含 `NekoNativeLoader`。              |
| `neko-cli`        | 基于 picocli 构建的命令行终端入口。                                   |
| `neko-test`       | 提供端到端的集成测试与严格的 Native 验证测试。                              |

## 快速开始

**前置环境要求：**
- JDK 17 或更高版本。
- 项目已内置 Gradle Wrapper，无需全局安装 Gradle。
- 仅当配置 `native.enabled: true` 时，系统才需要依赖 [Zig](https://ziglang.org/) 工具链。

**构建 CLI 可执行文件：**

```bash
./gradlew :neko-cli:shadowJar
```

**运行混淆任务：**

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
  --config config.yml \
  --input path/to/input.jar \
  --output path/to/output.jar
```

**查看目标 JAR 包信息：**

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar info path/to/input.jar
```

**执行测试用例：**

```bash
./gradlew :neko-test:test
```

## 最小配置参考

**纯 JVM 混淆示例：**

```yaml
input: path/to/input.jar
output: path/to/output.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  invokeDynamic: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
```

**开启 Native 翻译示例：**

```yaml
# ... (transforms 配置同上) ...

native:
  enabled: true
  targets: [LINUX_X64]
  zigPath: zig
  methods: ["com/example/**"]
  excludePatterns: []
  includeAnnotated: true
  skipOnError: false
  resourceEncryption: true
```

> ⚠️ **提示**：在进行严格的 Native 验证或生成生产级制品时，强烈建议将 `skipOnError` 配置为 `false`。如果允许失败回退 (Fallback)，编译器在遇到错误时将悄悄退化为输出普通字节码，这将掩盖 Native 转换覆盖率缺失的问题，无法作为 Native 翻译成功的有效证明。

## 详细文档

| 文档名称 | 涵盖内容 |
| --- | --- |
| [架构 (Architecture)](./docs/zh-CN/ARCHITECTURE.md) | 深入解析当前的模块拓扑图、混淆流水线、运行时注入机制与 Native 修补模型。 |
| [配置 (Configuration)](./docs/zh-CN/CONFIG.md) | 详述当前解析器支持的所有 YAML 字段、CLI 参数覆盖及转换选项。 |
| [白皮书 (Whitepaper)](./docs/zh-CN/WHITEPAPER.md) | 揭示核心防御面的技术细节，包含 CFF 算法公式、密钥分发逻辑及 Native 实现模型。 |

*English Documentation:*

* [README.md](./README.md)
* [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)
* [docs/CONFIG.md](./docs/CONFIG.md)
* [docs/WHITEPAPER.md](./docs/WHITEPAPER.md)

## 验证与测试标准

修改纯文本类型的文档不会触发 Native 构建产物的重新生成。但针对任何 CFF 核心算法或 Native 翻译实现的底层变更，**都必须**使用新生成的运行时制品进行全面的端到端验证。

提交代码前，请确保系统在运行时**不出现**以下异常：

* 验证器错误 (Verifier Error)
* 本地代码崩溃 (Native Crash)
* 翻译方法数为零 (`translated=0`)
* 依赖库加载缺失
* JNI 异常回退 (JNI Fallback)
* 意外退化回原始明文字节码

## 许可协议

NekoObfuscator 采用 GNU General Public License v3.0 (GPLv3) 协议开源发布。详情请查阅仓库根目录下的 [LICENSE](./LICENSE) 文件。
