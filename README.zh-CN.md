<div align="center">

# NekoObfuscator

高级 Java 字节码与 native 混淆工具。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](./LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-red.svg)](https://adoptium.net/)
[![Build: Gradle](https://img.shields.io/badge/build-Gradle-02303A.svg)](./gradle)

[English](./README.md)

</div>

## 状态

NekoObfuscator 仍是早期研究项目。内部 API、配置字段、字节码形态和 native 运行时假设都可能不兼容变更。不要把当前输出当成生产级 DRM 或安全边界。

当前 CLI 注册的是重构后的 JVM 混淆流水线：

- `keyDispatch`：为应用方法建立隐藏 long key 派发。
- `controlFlowFlattening`：在原方法体内生成 keyed island dispatcher。

native 流水线是面向 HotSpot 的方法翻译与入口 patch 系统。它不是可移植 JVM 扩展层，每个 JDK、平台和 GC 组合都需要重新做运行时验证。

## 功能

| 领域 | 当前行为 |
|---|---|
| JVM key dispatch | 为可安全承载 key 的方法追加隐藏 long 参数，重写应用内调用传递 key，并为边界方法本地种子化。 |
| 控制流平坦化 | 按 verifier frame shape 拆分 dispatcher，使用不断演化的 key 编码 state/domain，并保留构造器强制初始化前缀。 |
| Native translation | 选择注解或 glob 命中的方法，闭包纳入应用内被调用者，降低受支持的 invokedynamic，生成 C，使用 Zig 构建，并在 `JNI_OnLoad` patch HotSpot Method entry。 |
| 运行时加载 | 仅 native 开启时注入最小 `NekoNativeLoader`，加载 `/neko/native/libneko_<platform>_<arch>.<ext>`。 |
| 资源加密 | native stage 可选 AES-256-GCM 资源加密。 |

旧版字符串、数字、invokeDynamic、stack、advanced JVM pass 的说明已经从主文档移除，直到这些 pass 重新被当前 CLI 注册。

## 仓库结构

| 模块 | 职责 |
|---|---|
| `neko-api` | 注解、transform 接口和配置模型。 |
| `neko-config` | YAML 解析与校验。 |
| `neko-core` | JAR I/O、L1/L2/L3 IR、pass 调度、清理、运行时注入、mapping。 |
| `neko-transforms` | 重构后的 JVM pass：key dispatch 与 keyed CFF。 |
| `neko-native` | native 选择、安全检查、字节码降低、C 生成、Zig 构建、HotSpot patch。 |
| `neko-runtime` | 注入到输出 JAR 的运行时类；当前只有 `NekoNativeLoader`。 |
| `neko-cli` | picocli 命令行入口。 |
| `neko-test` | 集成与 native 验证测试。 |

## 快速开始

前置条件：

- JDK 17 或更新版本。
- 仓库自带 Gradle wrapper。
- 仅在 `native.enabled: true` 时需要 Zig。

构建 CLI：

```bash
./gradlew :neko-cli:shadowJar
```

运行混淆：

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
  --config config.yml \
  --input path/to/input.jar \
  --output path/to/output.jar
```

查看 JAR 信息：

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar info path/to/input.jar
```

运行测试：

```bash
./gradlew :neko-test:test
```

## 最小配置

```yaml
input: path/to/input.jar
output: path/to/output.jar

transforms:
  keyDispatch: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
```

native 翻译示例：

```yaml
input: app.jar
output: app-native.jar

transforms:
  keyDispatch: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }

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

严格 native 验证建议使用 `skipOnError: false`。fallback 构建可能掩盖 native 覆盖缺失，不能作为 native 翻译成功的证明。

## 文档

| 文档 | 内容 |
|---|---|
| [架构](./docs/zh-CN/ARCHITECTURE.md) | 当前模块拓扑、流水线、运行时注入和 native patch 模型。 |
| [配置](./docs/zh-CN/CONFIG.md) | 当前代码真实解析的 YAML 字段。 |
| [白皮书](./docs/zh-CN/WHITEPAPER.md) | 当前 CFF 公式、key dispatch 逻辑和 native 实现模型。 |

英文文档：

- [README.md](./README.md)
- [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)
- [docs/CONFIG.md](./docs/CONFIG.md)
- [docs/WHITEPAPER.md](./docs/WHITEPAPER.md)

## 验证说明

文档修改不会重新生成 native artifact。任何 CFF 或 native 翻译实现变更都必须用新生成的运行时 artifact 验证，并拒绝 verifier error、native crash、`translated=0`、缺失库、JNI fallback 和原始字节码 fallback。

## 许可

NekoObfuscator 基于 GNU General Public License v3.0 发布，详见 [LICENSE](./LICENSE)。
