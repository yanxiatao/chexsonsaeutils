# Chexson's AE Utils

## 项目概述

`Chexson's AE Utils` 当前是一个面向 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21` 的迁移仓库，而不是旧版 `1.20.1 + Forge` 分支的继续维护副本。

本仓库的目标是把原项目已经存在的 AE2 扩展能力迁移到新的平台组合上，并尽量保留原有功能语义、配置开关、持久化格式与玩家可见行为，不用“能编译通过”去交换“功能被删减或悄悄降级”。

## 当前迁移状态

- Phase 01 已完成：目标平台版本组合、Gradle 构建链、模组元数据、最小加载入口与公共配置读取链路已经锁定到当前 `1.21.1 + NeoForge` 基线。
- Phase 02 已完成：multi-level emitter 的物品/部件注册、菜单与界面链路、watcher 运行时逻辑、表达式输入与兼容性验证已经迁移到 AE2 19 接缝。
- Phase 03 已完成：processing pattern replacement 的规则编辑、metadata 持久化、解码与 planning / execution 替换语义已经恢复。
- Phase 04 已完成：crafting continuation 的确认流模式切换、等待分支追踪、持久化与 CPU 状态投影已经恢复。
- Phase 05 尚未完成：回归加固、目标平台烟雾验证与最终验证收口仍待执行。
- Phase 06 正在执行：统一仓库文档语言、修正文档中的英文脚手架与历史工件说明，并保持 UTF-8 无 BOM + CRLF 约束。

## 核心能力

### 1. Multi-Level Emitter

- 在 AE2 原生发信器语义之上扩展多槽位监控。
- 每个槽位可独立设置阈值、比较模式、模糊匹配与 crafting 联动条件。
- 支持表达式逻辑、括号分组与校验反馈，并保持菜单/屏幕链路与运行时状态同步。

### 2. Processing Pattern Replacement

- 允许在 processing terminal 中为输入槽配置 replacement rule。
- 将 replacement metadata 持久化到 encoded pattern，并在解码后恢复 replacement-aware 语义。
- 在 planning / execution 阶段按既有规则选择候选输入，而不是回退到旧的单一输入语义。

### 3. Crafting Continuation

- 在 craft confirm 流程中为任务级合成提供 ignore-missing / continuation 模式。
- 材料不足时仅阻塞受影响分支，已满足条件的子步骤继续执行。
- 在 AE2 CPU 相关菜单与界面中保留等待详情、分支状态与运行中摘要投影。

### 4. 兼容性与开关

- continuation 与 processing pattern replacement 仍通过公共配置开关控制。
- 旧版 emitter NBT、旧版 pattern metadata 与 continuation 持久化状态都以“安全读取或明确降级”为目标。
- 当前仓库仍在迁移与验证阶段，不能把尚未完成的 Phase 05/06 误认为最终发布验收。

## 开发与验证命令

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runServer
```

如需把 Gradle 缓存固定在仓库本地目录：

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user')
.\gradlew.bat test
```

说明：

- `build` 用于验证当前 NeoForge 构建链是否仍可完成解析、编译、资源处理与打包。
- `test` 当前主要运行迁移阶段保留的关键回归切片与结构契约测试，详见 `build.gradle` 中的 `tasks.named('test', Test)` 配置。
- `runClient` / `runServer` 用于最小启动与功能接缝验证。

## 目录与验证入口

- `src/main/java/`：模组主逻辑、AE2 接缝、mixin 与运行时行为实现。
- `src/main/resources/` 与 `src/generated/resources/`：模组元数据、资源、数据包与生成产物入口。
- `.planning/REQUIREMENTS.md`：迁移需求、需求状态与阶段追踪矩阵。
- `.planning/ROADMAP.md`：阶段目标、计划清单、波次与整体进度。
- `.planning/STATE.md`：当前执行位置、阶段状态、计划完成度与最近活动。
- `.planning/phases/`：各阶段的 `PLAN.md`、`SUMMARY.md`、`CONTEXT.md`、`VERIFICATION.md` 等执行工件。
- `CLAUDE.md`：GSD 同步上下文、架构摘要、工作流约束与受管标记块入口。

如需快速了解当前仓库状态，建议按下面顺序阅读：

1. `README.md`
2. `.planning/ROADMAP.md`
3. `.planning/STATE.md`
4. `.planning/REQUIREMENTS.md`
5. `CLAUDE.md`
