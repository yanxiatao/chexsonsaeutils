# Chexson's AE Utils

## 项目概述

`Chexson's AE Utils` 当前是一个面向 `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21` 的迁移仓库，而不是旧版 `1.20.1 + Forge` 分支的继续维护副本。

本仓库的目标是把原项目已经存在的 AE2 扩展能力迁移到新的平台组合上，并尽量保留原有功能语义、配置开关、持久化格式与玩家可见行为，不用“能编译通过”去交换“功能被删减或悄悄降级”。

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

