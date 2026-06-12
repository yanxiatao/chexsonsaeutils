# Chexson's AE Utils

[English README](README_EN.md)

`Chexson's AE Utils` 是一个面向 Applied Energistics 2 的实用扩展模组，当前目标平台为 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`。它补充了多槽发信器、处理样板替换规则、缺料时继续合成等自动化能力，适合需要更细粒度 ME 网络控制和自动合成容错的整合包或技术向存档。

本仓库是迁移到 `Minecraft 1.21.1 + NeoForge` 的分支，不是旧版 `1.20.1 + Forge` 分支的直接发布说明。发布前请以实际构建产物、变更日志和测试结果为准。

## AI 编写提示

本模组的代码、文档与部分资源主要由 AI 在开发者指令下编写、迁移和整理，并由人工进行取舍与审阅。请将它视为需要独立验证的软件：在重要存档或服务器中使用前，建议先备份世界、确认依赖版本，并在测试环境中验证核心自动化链路。

## 版本与依赖

- Minecraft: `1.21.1`
- NeoForge: `21.1.222` 或更高的兼容 `21.1.x` 版本
- Applied Energistics 2: `19.2.17` 或更高的兼容 `19.2.x` 版本
- Java: `21`
- Mod ID: `chexsonsaeutils`
- License: `MIT`

该分支依赖 NeoForge 与 AE2 的当前内部 API 和 mixin 接缝。升级 AE2、NeoForge 或 Minecraft 后，应重新运行测试并做游戏内验证。

## 功能说明

### ME 多槽发信器

`ME 多槽发信器` 是对 AE2 原生发信器语义的扩展，物品 ID 为 `chexsonsaeutils:multi_level_emitter`。它仍作为 AE2 部件放置在可接受 AE2 part 的网络侧面，但可以在一个部件里管理多个监控槽位。

主要能力：

- 最多配置 `64` 个监控槽位。
- 每个槽位可独立标记物品、设置阈值、比较模式和模糊匹配模式。
- 支持通过合成卡启用与自动合成相关的槽位行为。
- 支持表达式逻辑，例如 `#1 OR (#2 AND #3)`。
- 表达式支持 `AND`、`OR` 和括号分组，并会对越界槽位、未标记槽位和语法问题给出反馈。
- 槽位数量、阈值、比较方式、模糊匹配、合成模式和表达式会持久化到部件 NBT。

合成配方是无序合成：

- `ae2:level_emitter`
- `ae2:logic_processor`
- `ae2:engineering_processor`

产物为 `chexsonsaeutils:multi_level_emitter`。

### 处理样板替换规则

处理样板替换功能允许玩家为 AE2 processing pattern 的输入槽保存替换规则。它适合“同一个处理流程可以接受同类材料或指定候选物品”的自动化场景。

主要能力：

- 在 AE2 样板编码终端中为处理输入槽配置规则。
- 支持按共享物品标签分组选择，也支持显式选择单个物品。
- 将替换规则写入编码样板的 metadata，根标签为 `chexsonsaeutils_processing_replacements`。
- 解码样板时恢复 replacement-aware 语义。
- 在 planning 和 execution 阶段按规则选择可用候选输入，而不是固定使用编码时的单一输入。
- UI 会区分未配置、已配置和部分失效状态，便于排查标签或物品变化造成的规则问题。

### 合成续跑与缺料忽略

合成续跑功能扩展 AE2 的合成确认流程，在合成计划存在缺料时提供 `Default` 与 `Ignore Missing` 模式切换。

主要能力：

- 在 Craft Confirm 界面切换任务级合成模式。
- `Default` 保持 AE2 默认行为。
- `Ignore Missing` 会尽量提交可执行分支，只让受缺料影响的分支等待。
- 合成 CPU 菜单和状态显示会保留等待数量、等待分支与运行中摘要。
- 与多槽发信器的合成状态联动能力配合使用时，可构建更细粒度的补货与等待逻辑。

## 配置

公共配置文件位于：

```text
config/chexsonsaeutils-common.toml
```

当前配置项：

```toml
craftingContinuationEnabled = true
processingPatternReplacementEnabled = true
```

- `craftingContinuationEnabled`：启用或禁用 AE2 合成续跑 / 缺料忽略功能包。
- `processingPatternReplacementEnabled`：启用或禁用 AE2 处理样板替换功能包。

这两个配置在启动时读取，修改后需要重启游戏或服务器。

## 安装说明

1. 安装 Minecraft `1.21.1`、NeoForge `21.1.222+` 与 Java `21`。
2. 安装 Applied Energistics 2 `19.2.17+`。
3. 将本模组 jar 放入 `mods` 目录。
4. 首次启动后检查 `config/chexsonsaeutils-common.toml`。
5. 在测试世界中验证多槽发信器、处理样板替换和合成续跑是否符合整合包预期。

建议在服务器环境中先进行离线或测试服验证，再迁移到正式存档。

## 开发环境

本仓库使用 Gradle Wrapper。常用命令：

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat continuationTest
.\gradlew.bat patternReplacementTest
.\gradlew.bat runGameTestServer
.\gradlew.bat runClient
.\gradlew.bat runServer
```

命令说明：

- `build`：编译、处理资源并打包。
- `test`：运行当前配置的回归测试切片。
- `continuationTest`：运行合成续跑相关回归测试。
- `patternReplacementTest`：运行处理样板替换相关回归测试。
- `runGameTestServer`：以代码驱动的真实游戏内测试主入口，优先用于自动化 smoke / GameTest 验收。
- `runClient` / `runServer`：启动开发客户端或服务端进行补充游戏内验证。

如需把 Gradle 缓存固定到仓库本地目录：

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user')
.\gradlew.bat test
```

## 仓库结构

- `src/main/java/`：模组主逻辑、AE2 接缝、mixin、菜单与运行时行为。
- `src/main/resources/`：模组元数据、资源、语言文件、贴图和数据包入口。
- `src/main/templates/META-INF/neoforge.mods.toml`：NeoForge mod 元数据模板。
- `src/main/resources/assets/chexsonsaeutils/lang/`：中文与英文语言文件。
- `src/main/resources/data/chexsonsaeutils/recipes/`：模组配方。
- `config/chexsonsaeutils-common.toml`：开发环境下的默认公共配置示例。

项目文件约定使用 UTF-8 编码与 CRLF 换行。

## 兼容性与限制

- 本模组不是 AE2 官方项目，也不代表 AE2 官方兼容承诺。
- 当前分支只面向 `Minecraft 1.21.1 + NeoForge + AE2 19`。
- 功能依赖 AE2 菜单、样板、合成服务和部件系统的内部行为；AE2 更新后可能需要适配。
- 修改处理样板 metadata 和多槽发信器 NBT 前，建议备份重要世界。
- 如果整合包中还有修改 AE2 合成、样板或终端界面的模组，建议重点测试交互兼容性。

## 反馈问题

提交问题时建议包含：

- Minecraft、NeoForge、AE2 和本模组版本。
- 是否在客户端、服务端或专用服务器出现。
- 完整日志和崩溃报告。
- 复现步骤、涉及的样板或发信器配置。
- `config/chexsonsaeutils-common.toml` 中的相关配置。

## 许可证

本项目使用 `MIT` 许可证。详见仓库中的许可证文件或发布页面说明。
